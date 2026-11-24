package com.example.gpstest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Garantit l'ordre FIFO et l'exécution non concurrente du pipeline GNSS. */
public final class GnssEventPipelineTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        GnssEventPipeline pipeline = new GnssEventPipeline();
        check(pipeline.start(), "le pipeline démarre");
        check(pipeline.start(), "le démarrage est idempotent");

        final int count = 80;
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        for (int i = 0; i < count; i++) {
            final int value = i;
            check(pipeline.post(() -> {
                int now = active.incrementAndGet();
                maxActive.accumulateAndGet(now, Math::max);
                order.add(value);
                active.decrementAndGet();
            }), "événement " + i + " accepté");
        }

        // Une barrière ajoutée après les événements est exécutée par le même
        // Handler FIFO. À son entrée, le finally de chaque événement précédent
        // a donc déjà incrémenté completed : aucun réveil prématuré du test.
        CountDownLatch batchDrained = new CountDownLatch(1);
        AtomicLong completedAtBatchBarrier = new AtomicLong(-1L);
        check(pipeline.post(() -> {
            completedAtBatchBarrier.set(pipeline.completedCount());
            batchDrained.countDown();
        }), "barrière FIFO de fin de lot acceptée");
        check(batchDrained.await(5, TimeUnit.SECONDS),
                "la barrière confirme le traitement de tous les événements");
        check(order.size() == count, "aucun événement n'est perdu");
        boolean fifo = true;
        for (int i = 0; i < order.size(); i++) if (order.get(i) != i) { fifo = false; break; }
        check(fifo, "ordre FIFO conservé");
        check(maxActive.get() == 1, "aucun callback exécuté en concurrence");
        check(pipeline.submittedCount() == count + 1,
                "compteur submitted inclut la barrière FIFO");
        check(completedAtBatchBarrier.get() == count,
                "completed inclut tous les événements avant la barrière");
        check(pipeline.rejectedCount() == 0, "aucune soumission rejetée");

        check(pipeline.post(() -> { throw new IllegalStateException("fixture"); }),
                "un callback qui échouera est accepté dans la file");
        CountDownLatch recoveryBarrier = new CountDownLatch(1);
        AtomicInteger failedAtRecoveryBarrier = new AtomicInteger(-1);
        AtomicLong completedAtRecoveryBarrier = new AtomicLong(-1L);
        check(pipeline.post(() -> {
            // Cette tâche ne peut démarrer qu'après le catch et le finally du
            // callback fautif, grâce à la sérialisation FIFO du même Handler.
            failedAtRecoveryBarrier.set((int) pipeline.failedCount());
            completedAtRecoveryBarrier.set(pipeline.completedCount());
            recoveryBarrier.countDown();
        }), "la file accepte une barrière après le callback fautif");
        check(recoveryBarrier.await(5, TimeUnit.SECONDS),
                "le pipeline reste opérationnel après une exception de callback");
        check(failedAtRecoveryBarrier.get() == 1,
                "l'exception de callback est comptabilisée avant la barrière");
        check(completedAtRecoveryBarrier.get() == count + 2,
                "completed inclut le callback fautif avant la barrière suivante");

        pipeline.stop();
        check(!pipeline.isRunning(), "stop libère le handler du pipeline");
        check(!pipeline.post(() -> { }), "événement refusé après arrêt");
        check(pipeline.rejectedCount() == 1, "compteur rejected incrémenté après arrêt");
        pipeline.stop();
        check(!pipeline.isRunning(), "stop est idempotent");

        System.out.printf("GnssEventPipelineTest : %d réussis, %d échoués%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    private static void check(boolean condition, String label) {
        if (condition) { passed++; System.out.println("OK   " + label); }
        else { failed++; System.err.println("FAIL " + label); }
    }
}
