package com.example.gpstest;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Enregistreur CSV GNSS brut indépendant de toute Activity.
 *
 * Toutes les opérations disque sont sérialisées sur un HandlerThread dédié.
 * La file est bornée afin qu'un stockage lent ne provoque pas une croissance
 * mémoire illimitée. Un flush périodique continue même si les callbacks GNSS
 * se taisent (par exemple lorsque l'application passe en arrière-plan).
 */
public final class RawMeasurementsCsvLogger {

    public interface Listener {
        void onOpened(File file, Snapshot snapshot);
        void onUpdated(Snapshot snapshot);
        void onClosed(File file, Snapshot snapshot, String error);
    }

    public static final class Options {
        public final boolean time;
        public final boolean signal;
        public final boolean doppler;
        public final boolean phase;
        public final boolean nmea;

        public Options(boolean time, boolean signal, boolean doppler, boolean phase, boolean nmea) {
            this.time = time;
            this.signal = signal;
            this.doppler = doppler;
            this.phase = phase;
            this.nmea = nmea;
        }
    }

    public static final class Snapshot {
        /** Génération du fichier : permet au service d'écarter les callbacks UI retardés. */
        public final long generation;
        public final boolean opening;
        public final boolean ready;
        public final boolean closing;
        public final int eventsReceived;
        public final int rowsQueued;
        public final int rowsPending;
        public final int rowsWritten;
        public final int rowsDropped;
        public final String durabilityWarning;

        private Snapshot(long generation, boolean opening, boolean ready, boolean closing,
                int eventsReceived, int rowsQueued, int rowsPending,
                int rowsWritten, int rowsDropped, String durabilityWarning) {
            this.generation = generation;
            this.opening = opening;
            this.ready = ready;
            this.closing = closing;
            this.eventsReceived = eventsReceived;
            this.rowsQueued = rowsQueued;
            this.rowsPending = rowsPending;
            this.rowsWritten = rowsWritten;
            this.rowsDropped = rowsDropped;
            this.durabilityWarning = durabilityWarning;
        }
    }

    private static final int MAX_PENDING_ROWS = 4096;
    private static final int BUFFER_SIZE = 32 * 1024;
    private static final long FLUSH_INTERVAL_MS = 2_000L;
    private static final long SYNC_INTERVAL_MS = 30_000L;
    /** Génération globale au processus : distingue aussi deux instances de service. */
    private static final AtomicLong NEXT_GENERATION = new AtomicLong(1L);

    private final Object lock = new Object();
    private final Listener listener;
    private HandlerThread thread;
    private Handler worker;
    private BufferedWriter writer;
    private FileOutputStream output;
    private File file;
    private boolean requested;
    private boolean opening;
    private boolean ready;
    private boolean closing;
    private boolean shutdownRequested;
    private long generation;
    private long lastFlushElapsedMs;
    private long lastSyncElapsedMs;
    private int eventsReceived;
    private int rowsQueued;
    private int rowsPending;
    private int rowsWritten;
    private int rowsDropped;
    private String durabilityWarning;
    private String terminalError;

    public RawMeasurementsCsvLogger(Listener listener) {
        this.listener = listener;
    }

    /** Ouvre un nouveau CSV de façon asynchrone. */
    public boolean start(File directory) {
        if (directory == null) return false;
        final long token;
        synchronized (lock) {
            if (requested || opening || closing || shutdownRequested) return false;
            // Aucune vérification/création de dossier sur le thread appelant
            // (généralement le thread principal du service). Toute E/S est
            // déportée vers le HandlerThread dans openFile().
            if (!ensureWorkerLocked()) return false;

            token = NEXT_GENERATION.getAndIncrement();
            generation = token;
            requested = true;
            opening = true;
            ready = false;
            closing = false;
            file = null;
            terminalError = null;
            durabilityWarning = null;
            eventsReceived = 0;
            rowsQueued = 0;
            rowsPending = 0;
            rowsWritten = 0;
            rowsDropped = 0;

            if (!worker.post(() -> openFile(token, directory))) {
                requested = false;
                opening = false;
                terminalError = "Thread CSV indisponible";
                return false;
            }
        }
        notifyUpdated();
        return true;
    }

    private boolean ensureWorkerLocked() {
        if (thread != null && thread.isAlive() && worker != null) return true;
        try {
            thread = new HandlerThread("gnss-raw-csv", android.os.Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            worker = new Handler(thread.getLooper());
            return true;
        } catch (RuntimeException error) {
            thread = null;
            worker = null;
            return false;
        }
    }

    private void openFile(long token, File directory) {
        FileOutputStream openedOutput = null;
        BufferedWriter openedWriter = null;
        File target = null;
        try {
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IOException("Impossible de créer le dossier de journalisation GNSS");
            }
            if (!directory.isDirectory()) {
                throw new IOException("Le chemin de journalisation GNSS n'est pas un dossier");
            }
            target = File.createTempFile("mesures-gnss-", ".csv", directory);
            openedOutput = new FileOutputStream(target, false);
            openedWriter = new BufferedWriter(
                    new OutputStreamWriter(openedOutput, StandardCharsets.UTF_8), BUFFER_SIZE);
            openedWriter.write(RawMeasurementsFormatter.CSV_HEADER);
            openedWriter.newLine();
            openedWriter.flush();
            openedOutput.getFD().sync();

            long now = SystemClock.elapsedRealtime();
            boolean notifyOpened;
            synchronized (lock) {
                if (token != generation) {
                    closeQuietly(openedWriter, openedOutput);
                    return;
                }
                file = target;
                output = openedOutput;
                writer = openedWriter;
                opening = false;
                notifyOpened = requested;
                ready = notifyOpened;
                lastFlushElapsedMs = now;
                lastSyncElapsedMs = now;
            }
            if (notifyOpened && listener != null) listener.onOpened(target, snapshot());
            notifyUpdated();
            if (notifyOpened) scheduleMaintenance(token);
        } catch (Exception error) {
            closeQuietly(openedWriter, openedOutput);
            if (target != null) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }
            boolean finalizerAlreadyQueued = false;
            synchronized (lock) {
                if (token == generation) {
                    opening = false;
                    ready = false;
                    requested = false;
                    finalizerAlreadyQueued = closing;
                    // Si stop() a déjà placé le finalizer derrière openFile(),
                    // il sera l'unique propriétaire du callback onClosed().
                    // On lui transmet l'erreur via terminalError.
                    closing = finalizerAlreadyQueued;
                    terminalError = message(error);
                }
            }
            if (!finalizerAlreadyQueued && listener != null) {
                listener.onClosed(null, snapshot(), message(error));
            }
            notifyUpdated();
        }
    }

    /** Copie les lignes : aucune référence mutable Android ne traverse le worker. */
    public boolean enqueueEvent(List<String> rows) {
        if (rows == null) rows = Collections.emptyList();
        final List<String> safeRows = new ArrayList<>(rows.size());
        for (String row : rows) if (row != null) safeRows.add(row);

        synchronized (lock) {
            if (!requested || !ready || closing || worker == null) return false;
            eventsReceived++;
            int remainingCapacity = Math.max(0, MAX_PENDING_ROWS - rowsPending);
            int acceptedCount = Math.min(remainingCapacity, safeRows.size());
            int droppedNow = safeRows.size() - acceptedCount;
            if (droppedNow > 0) rowsDropped += droppedNow;

            if (acceptedCount > 0) {
                final List<String> acceptedRows = new ArrayList<>(safeRows.subList(0, acceptedCount));
                final long token = generation;
                rowsPending += acceptedCount;
                if (worker.post(() -> writeRows(token, acceptedRows))) {
                    rowsQueued += acceptedCount;
                } else {
                    rowsPending -= acceptedCount;
                    rowsDropped += acceptedCount;
                }
            }
        }
        notifyUpdated();
        return true;
    }

    private void writeRows(long token, List<String> rows) {
        int remaining = rows.size();
        BufferedWriter targetWriter;
        synchronized (lock) {
            targetWriter = token == generation ? writer : null;
        }
        if (targetWriter == null) {
            synchronized (lock) {
                rowsPending = Math.max(0, rowsPending - remaining);
                rowsDropped += remaining;
            }
            notifyUpdated();
            return;
        }

        try {
            for (String row : rows) {
                targetWriter.write(row);
                targetWriter.newLine();
                synchronized (lock) {
                    rowsWritten++;
                    rowsPending = Math.max(0, rowsPending - 1);
                }
                remaining--;
            }
            flushAndSyncIfDue(token, targetWriter);
        } catch (Exception error) {
            synchronized (lock) {
                rowsPending = Math.max(0, rowsPending - remaining);
                rowsDropped += remaining;
            }
            failWriter(token, message(error));
        }
        notifyUpdated();
    }

    /** Maintenance périodique même en l'absence de nouveaux callbacks GNSS. */
    private void scheduleMaintenance(long token) {
        Handler target;
        synchronized (lock) {
            if (token != generation || !requested || !ready || closing) return;
            target = worker;
        }
        if (target != null) target.postDelayed(() -> maintenanceTick(token), FLUSH_INTERVAL_MS);
    }

    private void maintenanceTick(long token) {
        BufferedWriter currentWriter;
        synchronized (lock) {
            if (token != generation || !requested || !ready || closing) return;
            currentWriter = writer;
        }
        if (currentWriter == null) return;
        try {
            flushAndSyncIfDue(token, currentWriter);
        } catch (Exception error) {
            failWriter(token, message(error));
            notifyUpdated();
            return;
        }
        notifyUpdated();
        scheduleMaintenance(token);
    }

    /** Appelé exclusivement sur le worker. L'horloge utilisée est monotone (ms). */
    private void flushAndSyncIfDue(long token, BufferedWriter targetWriter) throws Exception {
        long now = SystemClock.elapsedRealtime();
        if (now - lastFlushElapsedMs >= FLUSH_INTERVAL_MS) {
            targetWriter.flush();
            lastFlushElapsedMs = now;
        }
        if (now - lastSyncElapsedMs >= SYNC_INTERVAL_MS) {
            targetWriter.flush();
            FileOutputStream currentOutput;
            synchronized (lock) {
                if (token != generation) return;
                currentOutput = output;
            }
            if (currentOutput != null) {
                try {
                    currentOutput.getFD().sync();
                    synchronized (lock) { durabilityWarning = null; }
                } catch (Exception syncError) {
                    synchronized (lock) {
                        durabilityWarning = "Synchronisation disque non confirmée : "
                                + syncError.getClass().getSimpleName();
                    }
                }
            }
            lastSyncElapsedMs = now;
        }
    }

    /** Stoppe les nouvelles écritures et programme la clôture derrière la file. */
    private void failWriter(long token, String error) {
        BufferedWriter currentWriter;
        FileOutputStream currentOutput;
        Handler target;
        boolean mustPostFinalizer;
        synchronized (lock) {
            if (token != generation) return;
            // Si stop() a déjà mis un finalizer en file, on le conserve : il
            // passera après les autres lots déjà acceptés. En revanche, on
            // retire immédiatement le writer défectueux pour que ces lots
            // soient comptés comme perdus plutôt que réécrits dans un flux cassé.
            mustPostFinalizer = !closing;
            requested = false;
            ready = false;
            closing = true;
            terminalError = error;
            currentWriter = writer;
            currentOutput = output;
            writer = null;
            output = null;
            target = worker;
        }
        closeQuietly(currentWriter, currentOutput);
        if (mustPostFinalizer) {
            boolean posted = target != null && target.post(() -> finalizeFile(token, error));
            if (!posted) finalizeFile(token, error);
        }
    }

    /** Arrête l'enregistrement après toutes les lignes déjà acceptées. */
    public void stop() {
        final long token;
        synchronized (lock) {
            if (!requested && !opening && !ready) {
                if (shutdownRequested && worker != null && thread != null) {
                    worker.post(thread::quitSafely);
                }
                return;
            }
            requested = false;
            ready = false;
            closing = true;
            token = generation;
            if (worker == null || !worker.post(() -> finalizeFile(token, null))) {
                Thread fallback = new Thread(() -> finalizeFile(token,
                        "Worker CSV arrêté avant la finalisation"), "gnss-csv-close-fallback");
                fallback.setDaemon(true);
                fallback.start();
            }
        }
        notifyUpdated();
    }

    private void finalizeFile(long token, String forcedError) {
        BufferedWriter currentWriter;
        FileOutputStream currentOutput;
        File completed;
        String closeError = forcedError;
        synchronized (lock) {
            if (token != generation) return;
            if (closeError == null) closeError = terminalError;
            currentWriter = writer;
            currentOutput = output;
            writer = null;
            output = null;
            completed = file;
        }
        try {
            if (currentWriter != null) currentWriter.flush();
            if (currentOutput != null) {
                try {
                    currentOutput.getFD().sync();
                    synchronized (lock) { durabilityWarning = null; }
                } catch (Exception syncError) {
                    synchronized (lock) {
                        durabilityWarning = "Synchronisation finale non confirmée : "
                                + syncError.getClass().getSimpleName();
                    }
                }
            }
        } catch (Exception error) {
            if (closeError == null) closeError = message(error);
        } finally {
            try {
                if (currentWriter != null) currentWriter.close();
                else if (currentOutput != null) currentOutput.close();
            } catch (Exception error) {
                if (closeError == null) closeError = message(error);
                try { if (currentOutput != null) currentOutput.close(); } catch (Exception ignored) { }
            }
        }

        Snapshot stats;
        HandlerThread quitThread = null;
        synchronized (lock) {
            if (token != generation) return;
            opening = false;
            ready = false;
            requested = false;
            closing = false;
            if (closeError != null) terminalError = closeError;
            stats = snapshotLocked();
            if (shutdownRequested) quitThread = thread;
        }
        if (listener != null) listener.onClosed(completed, stats, closeError);
        notifyUpdated();
        if (quitThread != null) quitThread.quitSafely();
    }

    /** Arrêt définitif du worker lorsque le service est détruit. */
    public void shutdown() {
        synchronized (lock) { shutdownRequested = true; }
        stop();
    }

    public boolean isRequested() {
        synchronized (lock) { return requested || opening || closing; }
    }

    public boolean isReady() {
        synchronized (lock) { return ready && requested && !closing; }
    }

    public File getFile() {
        synchronized (lock) { return file; }
    }

    public Snapshot snapshot() {
        synchronized (lock) { return snapshotLocked(); }
    }

    private Snapshot snapshotLocked() {
        return new Snapshot(generation, opening, ready, closing, eventsReceived, rowsQueued,
                rowsPending, rowsWritten, rowsDropped, durabilityWarning);
    }

    private void notifyUpdated() {
        if (listener != null) listener.onUpdated(snapshot());
    }

    private static String message(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static void closeQuietly(BufferedWriter writer, FileOutputStream output) {
        try {
            if (writer != null) writer.close();
            else if (output != null) output.close();
        } catch (Exception ignored) {
            try { if (output != null) output.close(); } catch (Exception ignoredAgain) { }
        }
    }
}
