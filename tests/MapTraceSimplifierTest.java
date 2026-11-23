package com.example.gpstest;

import java.util.ArrayList;
import java.util.List;

public final class MapTraceSimplifierTest {
    private static int passed;
    private static int failed;

    private static void check(String label, boolean condition) {
        if (condition) passed++; else failed++;
        System.out.println((condition ? "  OK   " : "  FAIL ") + label);
    }

    public static void main(String[] args) {
        ArrayList<MapPoint> straight = new ArrayList<>();
        for (int i = 0; i <= 1000; i++) straight.add(new MapPoint(48.0 + i * 0.00001, 2.0 + i * 0.00001));
        List<MapPoint> simplified = MapTraceSimplifier.simplify(straight, 5.0, 2000);
        check("une ligne droite est simplifiée même si le budget dépasse l'entrée", simplified.size() <= 3);
        check("premier et dernier points conservés",
                simplified.get(0) == straight.get(0)
                        && simplified.get(simplified.size() - 1) == straight.get(straight.size() - 1));

        ArrayList<MapPoint> bend = new ArrayList<>();
        bend.add(new MapPoint(0, 0));
        bend.add(new MapPoint(0, 0.001));
        bend.add(new MapPoint(0.001, 0.001));
        bend.add(new MapPoint(0.002, 0.001));
        List<MapPoint> preservedBend = MapTraceSimplifier.simplify(bend, 5.0, 10);
        check("un virage dépassant la tolérance est conservé", preservedBend.size() >= 3);

        ArrayList<MapPoint> noisy = new ArrayList<>();
        for (int i = 0; i < 4000; i++) {
            double lon = i * 0.00001;
            double lat = 45 + ((i & 1) == 0 ? 0.00008 : -0.00008);
            noisy.add(new MapPoint(lat, lon));
        }
        List<MapPoint> bounded = MapTraceSimplifier.simplify(noisy, 0.1, 250);
        check("budget de points respecté pour une trace bruitée", bounded.size() <= 250);
        check("budget minimal conserve les deux extrémités",
                bounded.get(0) == noisy.get(0) && bounded.get(bounded.size() - 1) == noisy.get(noisy.size() - 1));
        check("entrée vide renvoie une liste vide", MapTraceSimplifier.simplify(null, 1, 10).isEmpty());
        List<MapPoint> shortTrace = MapTraceSimplifier.simplify(bend, 1, 10);
        check("trace courte garde ses extrémités et son virage", shortTrace.size() >= 3
                && shortTrace.get(0) == bend.get(0)
                && shortTrace.get(shortTrace.size() - 1) == bend.get(bend.size() - 1));
        System.out.println("RESULTAT : " + passed + " réussis, " + failed + " échoués");
        if (failed > 0) throw new AssertionError(failed + " tests échoués");
    }
}
