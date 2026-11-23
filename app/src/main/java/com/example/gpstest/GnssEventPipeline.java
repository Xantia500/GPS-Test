package com.example.gpstest;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * File unique de traitement des événements GNSS à débit modéré.
 *
 * Les callbacks position, statut satellite et NMEA sont soumis à la même
 * Looper. Leur ordre de livraison est ainsi conservé et les états dérivés
 * (FixQuality, échantillonnage, dernier statut satellite) ne sont plus
 * modifiés en concurrence par plusieurs callbacks système.
 *
 * Les mesures brutes GnssMeasurementsEvent restent sur leur worker dédié :
 * leur volume et leur sérialisation CSV ne doivent pas retarder les fixes.
 */
public final class GnssEventPipeline {
    private HandlerThread thread;
    private Handler handler;
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    /** Démarre idempotemment la file. Retourne false si le Looper est indisponible. */
    public synchronized boolean start() {
        if (handler != null) return true;
        HandlerThread candidate = new HandlerThread(
                "gnss-events", Process.THREAD_PRIORITY_DEFAULT);
        try {
            candidate.start();
            Looper looper = candidate.getLooper();
            if (looper == null) {
                candidate.quitSafely();
                return false;
            }
            thread = candidate;
            handler = new Handler(looper);
            return true;
        } catch (RuntimeException failure) {
            try { candidate.quitSafely(); } catch (RuntimeException ignored) { }
            return false;
        }
    }

    /** Handler de livraison à passer aux API Android qui proposent cette surcharge. */
    public synchronized Handler handler() {
        return handler;
    }

    /** Executor adapté aux API LocationManager récentes, même file que handler(). */
    public Executor executor() {
        return command -> {
            if (command == null || !post(command)) {
                throw new RejectedExecutionException("File GNSS arrêtée ou indisponible");
            }
        };
    }

    /** Ajoute un événement, sans exécuter de traitement sur le thread appelant. */
    public boolean post(Runnable task) {
        if (task == null) return false;
        final Handler target;
        synchronized (this) { target = handler; }
        if (target == null) {
            rejected.incrementAndGet();
            return false;
        }
        submitted.incrementAndGet();
        boolean accepted;
        try {
            accepted = target.post(() -> {
                try {
                    task.run();
                } catch (RuntimeException callbackFailure) {
                    // Une erreur d'un callback OEM ou d'un parseur ne doit
                    // pas tuer le Looper et faire perdre tous les événements
                    // GNSS suivants. L'échec reste observable au diagnostic.
                    failed.incrementAndGet();
                } finally {
                    completed.incrementAndGet();
                }
            });
        } catch (RuntimeException postFailure) {
            accepted = false;
        }
        if (!accepted) {
            submitted.decrementAndGet();
            rejected.incrementAndGet();
        }
        return accepted;
    }

    /** Arrête le Looper sans attente bloquante ; les événements déjà en file sont drainés. */
    public void stop() {
        final HandlerThread oldThread;
        synchronized (this) {
            oldThread = thread;
            thread = null;
            handler = null;
        }
        if (oldThread != null) {
            try { oldThread.quitSafely(); }
            catch (RuntimeException ignored) { }
        }
    }

    public synchronized boolean isRunning() {
        return thread != null && handler != null;
    }

    public long submittedCount() { return submitted.get(); }
    public long completedCount() { return completed.get(); }
    public long rejectedCount() { return rejected.get(); }
    public long failedCount() { return failed.get(); }
}
