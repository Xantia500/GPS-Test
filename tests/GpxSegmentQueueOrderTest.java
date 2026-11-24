package com.example.gpstest;

import android.os.Handler;
import android.os.HandlerThread;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Vérifie le contrat FIFO utilisé par TrackingService : ouverture, points, fermeture. */
public final class GpxSegmentQueueOrderTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        openingBarrierPrecedesPointsAndClose();
        failedHeaderNeverAllowsPointWrites();
        finalSessionSnapshotIsQueuedBeforeMetadataWorkerQuit();
        System.out.printf("GpxSegmentQueueOrderTest : %d réussis, %d échoués%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    private static void openingBarrierPrecedesPointsAndClose() throws Exception {
        HandlerThread thread = new HandlerThread("gpx-order-test");
        thread.start();
        Handler io = new Handler(thread.getLooper());
        GpxSegmentIoState state = new GpxSegmentIoState();
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch allowHeader = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        try {
            long beforePost = System.nanoTime();
            check(io.post(() -> {
                events.add("open-start");
                await(allowHeader);
                events.add("header-flushed");
                state.markReady();
            }), "l'ouverture est acceptée sur le worker FIFO");
            long postMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - beforePost);
            check(postMillis < 500L, "la demande d'ouverture ne bloque pas le thread appelant");
            check(!state.canWrite(), "aucun point n'est inscriptible pendant l'ouverture");

            check(io.post(() -> {
                if (state.canWrite()) events.add("point-1");
                else events.add("point-1-rejected");
            }), "le premier point est mis derrière l'ouverture");
            check(io.post(() -> {
                if (state.canWrite()) events.add("point-2");
                else events.add("point-2-rejected");
            }), "le second point est mis derrière le premier");
            check(io.post(() -> {
                events.add(state.canWrite() ? "footer" : "close-without-footer");
                state.finishClose();
                finished.countDown();
            }), "la fermeture est mise après les points acceptés");

            allowHeader.countDown();
            check(finished.await(5, TimeUnit.SECONDS), "la file termine dans le délai du test");
            check(events.size() == 5, "les cinq étapes attendues ont été exécutées");
            check(events.indexOf("header-flushed") < events.indexOf("point-1"),
                    "l'en-tête est complètement écrit avant le premier point");
            check(events.indexOf("point-1") < events.indexOf("point-2"),
                    "l'ordre des points est conservé");
            check(events.indexOf("point-2") < events.indexOf("footer"),
                    "le pied n'est écrit qu'après tous les points acceptés");
            check(state.phase() == GpxSegmentIoState.Phase.CLOSED,
                    "le segment est fermé après le pied");
        } finally {
            allowHeader.countDown();
            thread.quitSafely();
        }
    }

    private static void failedHeaderNeverAllowsPointWrites() throws Exception {
        HandlerThread thread = new HandlerThread("gpx-failed-open-test");
        thread.start();
        Handler io = new Handler(thread.getLooper());
        GpxSegmentIoState state = new GpxSegmentIoState();
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch finished = new CountDownLatch(1);
        try {
            check(io.post(() -> {
                events.add("open-start");
                state.failOpen();
                events.add("header-failed");
            }), "une ouverture défaillante reste une tâche FIFO");
            check(io.post(() -> {
                events.add(state.canWrite() ? "point-written" : "point-failed-after-queue");
            }), "le point accepté pendant l'ouverture défaillante reste ordonné derrière elle");
            check(io.post(() -> {
                events.add(state.canWrite() ? "footer" : "partial-file-preserved");
                state.finishClose();
                finished.countDown();
            }), "la fermeture suit également l'ouverture défaillante");
            check(finished.await(5, TimeUnit.SECONDS), "la file défaillante termine dans le délai du test");
            check(events.contains("point-failed-after-queue"),
                    "un point déjà mis en file est explicitement déclaré en échec");
            check(!events.contains("point-written"), "aucun point n'est écrit sans en-tête valide");
            check(!events.contains("footer"), "aucun pied normal n'est ajouté à un fichier incomplet");
            check(events.contains("partial-file-preserved"),
                    "le fichier partiel éventuel est conservé sans faux pied");
            check(state.phase() == GpxSegmentIoState.Phase.CLOSED,
                    "un segment dont l'ouverture a échoué se termine fermé");
        } finally {
            thread.quitSafely();
        }
    }


    private static void finalSessionSnapshotIsQueuedBeforeMetadataWorkerQuit() throws Exception {
        HandlerThread fileThread = new HandlerThread("gpx-final-drain-file-test");
        HandlerThread metaThread = new HandlerThread("gpx-final-drain-meta-test");
        fileThread.start();
        metaThread.start();
        Handler file = new Handler(fileThread.getLooper());
        Handler meta = new Handler(metaThread.getLooper());
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch finished = new CountDownLatch(1);
        try {
            boolean queued = file.post(() -> {
                events.add("file-worker-finalized-segment");
                boolean journalQueued = meta.post(() -> events.add("final-session-journal"));
                boolean quitQueued = meta.post(() -> {
                    events.add("metadata-worker-quit");
                    metaThread.quitSafely();
                    finished.countDown();
                });
                if (!journalQueued) events.add("journal-post-failed");
                if (!quitQueued) events.add("quit-post-failed");
                fileThread.quitSafely();
            });
            check(queued, "la barrière finale du worker fichier est mise en file");
            check(finished.await(5, TimeUnit.SECONDS),
                    "la journalisation finale se termine avant l'arrêt du worker méta");
            check(events.indexOf("file-worker-finalized-segment")
                            < events.indexOf("final-session-journal"),
                    "le snapshot est demandé après la finalisation du GPX");
            check(events.indexOf("final-session-journal")
                            < events.indexOf("metadata-worker-quit"),
                    "le snapshot final est placé avant quitSafely du worker méta");
            check(!events.contains("journal-post-failed") && !events.contains("quit-post-failed"),
                    "les deux tâches de fin sont acceptées");
        } finally {
            fileThread.quitSafely();
            metaThread.quitSafely();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timeout en attente du feu vert d'ouverture");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
            System.out.println("OK   " + message);
        } else {
            failed++;
            System.err.println("FAIL " + message);
        }
    }
}
