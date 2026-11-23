package com.example.gpstest;

/** Teste le réglage central de cadence, préparé pour une option utilisateur future. */
public final class GnssAcquisitionConfigTest {
    private static int passed;
    private static int failed;
    public static void main(String[] args) {
        check(GnssAcquisitionConfig.normalizeIntervalMs(0) == 1_000L, "borne basse -> 1 s");
        check(GnssAcquisitionConfig.normalizeIntervalMs(1_000) == 1_000L, "1 s conservée");
        check(GnssAcquisitionConfig.normalizeIntervalMs(1_400) == 1_000L, "1,4 s ramenée à l'option la plus proche");
        check(GnssAcquisitionConfig.normalizeIntervalMs(1_600) == 2_000L, "1,6 s arrondie vers 2 s");
        check(GnssAcquisitionConfig.normalizeIntervalMs(4_000) == 5_000L, "4 s ramenée à 5 s");
        check(GnssAcquisitionConfig.normalizeIntervalMs(99_000) == 10_000L, "borne haute -> 10 s");
        check(GnssAcquisitionConfig.statusFreshnessMs(1_000L) == 3_000L,
                "statut GNSS frais pendant 3 s à 1 Hz");
        check(GnssAcquisitionConfig.statusFreshnessMs(2_000L) == 6_000L,
                "fenêtre de fraîcheur adaptée à un intervalle de 2 s");
        check(GnssAcquisitionConfig.statusFreshnessMs(10_000L) == 30_000L,
                "fenêtre de fraîcheur adaptée à la cadence lente de 10 s");
        for (long v : GnssAcquisitionConfig.supportedIntervals()) {
            check(GnssAcquisitionConfig.isSupported(v), "liste publiée cohérente : " + v);
        }
        long[] a = GnssAcquisitionConfig.supportedIntervals();
        a[0] = 99;
        check(GnssAcquisitionConfig.supportedIntervals()[0] == 1_000L, "tableau exposé défensivement");
        System.out.printf("GnssAcquisitionConfigTest : %d réussis, %d échoués%n", passed, failed);
        if (failed > 0) System.exit(1);
    }
    private static void check(boolean ok, String label) {
        if (ok) { passed++; System.out.println("OK   " + label); }
        else { failed++; System.err.println("FAIL " + label); }
    }
}
