package com.example.gpstest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Garantit l'ordre FIFO et l'exécution non concurrente du pipeline GNSS. */
public final class GnssEventPipelineTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        GnssEventPipeline pipeline = new GnssEventPipeline();
        check(pipeline.start(), "le pipeline démarre");
        check(pipeline.start(), "le démarrage est idempotent");

        final int count = 80;
        CountDownLatch done = new CountDownLatch(count);
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
                done.countDown();
            }), "événement " + i + " accepté");
        }
        check(done.await(5, TimeUnit.SECONDS), "tous les événements sont traités");
        check(order.size() == count, "aucun événement n'est perdu");
        boolean fifo = true;
        for (int i = 0; i < order.size(); i++) if (order.get(i) != i) { fifo = false; break; }
        check(fifo, "ordre FIFO conservé");
        check(maxActive.get() == 1, "aucun callback exécuté en concurrence");
        check(pipeline.submittedCount() == count, "compteur submitted exact");
        check(pipeline.completedCount() == count, "compteur completed exact");
        check(pipeline.rejectedCount() == 0, "aucune soumission rejetée");

        CountDownLatch afterFailure = new CountDownLatch(1);
        check(pipeline.post(() -> { throw new IllegalStateException("fixture"); }),
                "un callback qui échouera est accepté dans la file");
        check(pipeline.post(afterFailure::countDown),
                "la file accepte encore un événement après le callback fautif");
        check(afterFailure.await(5, TimeUnit.SECONDS),
                "le pipeline reste opérationnel après une exception de callback");
        check(pipeline.failedCount() == 1, "l'exception de callback est comptabilisée");
        check(pipeline.completedCount() == count + 2,
                "les événements terminés incluent aussi le callback en échec");

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
