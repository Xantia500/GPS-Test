package com.example.gpstest;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Tests JVM du logger CSV : file d'attente, finalisation et flush en absence d'événement. */
public final class RawMeasurementsCsvLoggerTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("gpstest-raw-csv-test").toFile();
        try {
            long firstGeneration = testPeriodicFlushWithoutFurtherEvents(new File(root, "idle-flush"));
            testBoundedQueueAndFinalCounters(new File(root, "bounded-queue"), firstGeneration);
            testOpenFailureReportedOnce(new File(root, "not-a-directory"));
        } finally {
            deleteTree(root);
        }
        System.out.printf("RawMeasurementsCsvLoggerTest : %d réussis, %d échoués%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    private static long testPeriodicFlushWithoutFurtherEvents(File directory) throws Exception {
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<File> csv = new AtomicReference<>();
        AtomicReference<RawMeasurementsCsvLogger.Snapshot> finalSnapshot = new AtomicReference<>();
        AtomicReference<String> finalError = new AtomicReference<>();
        RawMeasurementsCsvLogger logger = new RawMeasurementsCsvLogger(
                new RawMeasurementsCsvLogger.Listener() {
                    @Override public void onOpened(File file, RawMeasurementsCsvLogger.Snapshot snapshot) {
                        csv.set(file);
                        opened.countDown();
                    }
                    @Override public void onUpdated(RawMeasurementsCsvLogger.Snapshot snapshot) { }
                    @Override public void onClosed(File file, RawMeasurementsCsvLogger.Snapshot snapshot,
                            String error) {
                        if (file != null) csv.set(file);
                        finalSnapshot.set(snapshot);
                        finalError.set(error);
                        closed.countDown();
                    }
                });

        check(logger.start(directory), "démarrage asynchrone accepté");
        check(opened.await(5, TimeUnit.SECONDS), "en-tête CSV ouvert");
        List<String> rows = new ArrayList<>();
        rows.add("row-one");
        rows.add("row-two");
        check(logger.enqueueEvent(rows), "événement accepté");

        // Le worker a écrit dans BufferedWriter, mais aucune nouvelle mesure
        // n'arrive : seule la maintenance périodique peut rendre les lignes
        // visibles dans le fichier avant l'arrêt explicite.
        boolean flushedWithoutNextEvent = false;
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            File file = csv.get();
            if (file != null && file.exists()) {
                List<String> content = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
                if (content.contains("row-two")) {
                    flushedWithoutNextEvent = true;
                    break;
                }
            }
            Thread.sleep(40L);
        }
        check(flushedWithoutNextEvent, "flush périodique même sans callback GNSS ultérieur");

        logger.stop();
        check(closed.await(5, TimeUnit.SECONDS), "finalisation du fichier terminée");
        RawMeasurementsCsvLogger.Snapshot stats = finalSnapshot.get();
        check(finalError.get() == null, "aucune erreur de fermeture");
        check(stats != null && stats.eventsReceived == 1, "un événement GNSS compté");
        check(stats != null && stats.rowsQueued == 2 && stats.rowsWritten == 2,
                "deux lignes mises en file et sérialisées");
        check(stats != null && stats.rowsPending == 0 && stats.rowsDropped == 0,
                "file vide et aucune ligne perdue après fermeture");
        List<String> all = Files.readAllLines(csv.get().toPath(), StandardCharsets.UTF_8);
        check(all.size() == 3 && all.get(0).equals(RawMeasurementsFormatter.CSV_HEADER),
                "en-tête CSV + deux lignes conservés");
        long generation = stats == null ? 0L : stats.generation;
        logger.shutdown();
        return generation;
    }

    private static void testOpenFailureReportedOnce(File pathThatIsAFile) throws Exception {
        File parent = pathThatIsAFile.getParentFile();
        if (parent != null) Files.createDirectories(parent.toPath());
        Files.write(pathThatIsAFile.toPath(), new byte[] {1});
        CountDownLatch closed = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger closeCallbacks = new java.util.concurrent.atomic.AtomicInteger();
        AtomicReference<String> error = new AtomicReference<>();
        AtomicReference<RawMeasurementsCsvLogger.Snapshot> snapshot = new AtomicReference<>();
        RawMeasurementsCsvLogger logger = new RawMeasurementsCsvLogger(
                new RawMeasurementsCsvLogger.Listener() {
                    @Override public void onOpened(File file, RawMeasurementsCsvLogger.Snapshot state) { }
                    @Override public void onUpdated(RawMeasurementsCsvLogger.Snapshot state) { }
                    @Override public void onClosed(File file, RawMeasurementsCsvLogger.Snapshot state,
                            String message) {
                        closeCallbacks.incrementAndGet();
                        error.set(message);
                        snapshot.set(state);
                        closed.countDown();
                    }
                });
        check(logger.start(pathThatIsAFile), "démarrage accepté avant validation asynchrone du dossier");
        check(closed.await(5, TimeUnit.SECONDS), "échec d'ouverture retourné au listener");
        Thread.sleep(150L);
        check(closeCallbacks.get() == 1, "un seul callback de fermeture après erreur d'ouverture");
        check(error.get() != null && !error.get().isEmpty(), "cause d'ouverture conservée");
        check(snapshot.get() != null && snapshot.get().generation > 0L,
                "génération du journal exposée dans le snapshot");
        logger.shutdown();
    }

    private static void testBoundedQueueAndFinalCounters(File directory, long previousGeneration) throws Exception {
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<File> csv = new AtomicReference<>();
        AtomicReference<RawMeasurementsCsvLogger.Snapshot> finalSnapshot = new AtomicReference<>();
        RawMeasurementsCsvLogger logger = new RawMeasurementsCsvLogger(
                new RawMeasurementsCsvLogger.Listener() {
                    @Override public void onOpened(File file, RawMeasurementsCsvLogger.Snapshot snapshot) {
                        csv.set(file);
                        opened.countDown();
                    }
                    @Override public void onUpdated(RawMeasurementsCsvLogger.Snapshot snapshot) { }
                    @Override public void onClosed(File file, RawMeasurementsCsvLogger.Snapshot snapshot,
                            String error) {
                        if (file != null) csv.set(file);
                        finalSnapshot.set(snapshot);
                        closed.countDown();
                    }
                });

        check(logger.start(directory), "second logger démarré");
        check(opened.await(5, TimeUnit.SECONDS), "second CSV ouvert");
        check(logger.snapshot().generation > previousGeneration,
                "génération globale distincte entre deux instances de logger du même processus");
        List<String> oversizedEvent = new ArrayList<>();
        for (int i = 0; i < 4100; i++) oversizedEvent.add("measurement-" + i);
        check(logger.enqueueEvent(oversizedEvent), "gros événement accepté avec limitation interne");
        check(logger.snapshot().rowsQueued == 4096, "file bornée à 4096 lignes en attente");
        check(logger.snapshot().rowsDropped == 4, "les 4 lignes excédentaires sont comptées perdues");

        logger.stop();
        check(closed.await(10, TimeUnit.SECONDS), "fermeture après vidage de la file");
        RawMeasurementsCsvLogger.Snapshot stats = finalSnapshot.get();
        check(stats != null && stats.rowsWritten == 4096, "les 4096 lignes acceptées sont sérialisées");
        check(stats != null && stats.rowsPending == 0 && stats.rowsDropped == 4,
                "aucun point en attente après finalisation, pertes explicites");
        List<String> all = Files.readAllLines(csv.get().toPath(), StandardCharsets.UTF_8);
        check(all.size() == 4097, "le fichier contient l'en-tête et exactement 4096 lignes");
        logger.shutdown();
    }

    private static void check(boolean condition, String label) {
        if (condition) { passed++; System.out.println("OK   " + label); }
        else { failed++; System.err.println("FAIL " + label); }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
