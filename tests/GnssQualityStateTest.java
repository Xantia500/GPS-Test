package com.example.gpstest;

/** Tests de fraîcheur des mesures NMEA/DOP et de présentation du statut de fix. */
public final class GnssQualityStateTest {
    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        TrackingState state = TrackingState.get();
        state.resetSession(1_800_000_000_000L, 1_000L);

        section("DOP GSA ET GGA — SOURCE ET FRAÎCHEUR");
        state.setReceiverDop(1.2, 1.8, 2.1, 3, 1_000L);
        near(1.2, state.getFreshRxHdop(6_000L, 5_000L), "HDOP GSA frais à la limite");
        near(1.8, state.getFreshRxVdop(6_000L, 5_000L), "VDOP GSA frais");
        ok(Double.isNaN(state.getFreshRxHdop(6_001L, 5_000L)), "DOP GSA périmé masqué");
        ok(state.getFreshNmeaFixType(6_000L, 5_000L) == 3, "type GSA 3D frais");
        ok(state.getFreshNmeaFixType(6_001L, 5_000L) == 0, "type GSA périmé devient inconnu");

        state.setGgaHdop(0.9, 2_000L);
        near(0.9, state.getFreshGgaHdop(7_000L, 5_000L), "HDOP GGA disponible indépendamment du GSA");
        ok(Double.isNaN(state.getFreshGgaHdop(7_001L, 5_000L)), "HDOP GGA expire indépendamment");
        state.setFixQuality("RTK fixé", 3_000L);
        ok("RTK fixé".equals(state.getFreshFixQuality(8_000L, 5_000L)), "qualité GGA fraîche");
        ok(state.getFreshFixQuality(8_001L, 5_000L) == null, "qualité GGA périmée masquée");

        section("VITESSE NMEA ET HEURE GNSS — FRAÎCHEUR");
        state.setNmeaSpeed("12,3 km/h", 4_000L);
        ok("12,3 km/h".equals(state.getFreshNmeaSpeed(9_000L, 5_000L)),
                "vitesse NMEA affichable tant que récente");
        ok(state.getFreshNmeaSpeed(9_001L, 5_000L) == null,
                "vitesse NMEA périmée masquée");
        state.setNmeaSpeed(null, 5_000L);
        ok(state.getFreshNmeaSpeed(5_001L, 5_000L) == null,
                "invalidité explicite efface immédiatement la vitesse NMEA");
        state.setGpsTime("12:00:00 · 23/11/2026", 6_000L);
        ok(state.getFreshGpsTime(16_000L, 10_000L) != null,
                "heure GNSS récente affichée");
        ok(state.getFreshGpsTime(16_001L, 10_000L) == null,
                "heure GNSS périmée masquée");

        section("DOP GÉOMÉTRIQUE — FRAÎCHEUR");
        DopCalculator.Dop d = new DopCalculator.Dop(2.0, 1.5, 1.0, 1.1, 1.3, 8, 2);
        state.setCalcDop(d, 4_000L);
        ok(state.getFreshCalcDop(9_000L, 5_000L) == d, "DOP inFix frais retourné");
        ok(state.getFreshCalcDop(9_001L, 5_000L) == null, "DOP inFix périmé masqué");

        section("ÉTAT DE FIX — 2D / 3D / INCONNU");
        ok("No fix".equals(FixStatePresentation.fixLabel(0, false, false)), "absence de position = No fix");
        ok("2D fix".equals(FixStatePresentation.fixLabel(2, false, true)), "GSA 2D explicite");
        ok("3D fix".equals(FixStatePresentation.fixLabel(3, true, true)), "GSA 3D explicite");
        ok("Fix + alt.".equals(FixStatePresentation.fixLabel(0, true, true)),
                "altitude présente sans GSA ne devient pas une affirmation 3D");
        ok("Fix horizontal".equals(FixStatePresentation.fixLabel(0, false, true)),
                "fix sans preuve de dimension reste horizontal");
        ok(FixStatePresentation.fixColorRes(2, true, false) == R.color.gps_warn,
                "fix 2D ambre");
        ok(FixStatePresentation.fixColorRes(3, true, true) == R.color.gps_green,
                "fix 3D vert");
        ok(FixStatePresentation.fixColorRes(0, true, true) == R.color.gps_warn,
                "dimension inconnue reste ambre");
        ok(FixStatePresentation.fixColorRes(0, false, false) == R.color.gps_danger,
                "absence de fix rouge");

        section("RESET DE SESSION");
        state.resetSession(1_800_000_001_000L, 10_000L);
        ok(Double.isNaN(state.getFreshRxHdop(10_000L, 5_000L)), "reset efface DOP GSA");
        ok(Double.isNaN(state.getFreshGgaHdop(10_000L, 5_000L)), "reset efface HDOP GGA");
        ok(state.getFreshNmeaFixType(10_000L, 5_000L) == 0, "reset efface dimension GSA");
        ok(state.getFreshFixQuality(10_000L, 5_000L) == null, "reset efface qualité GGA");
        ok(state.getFreshGpsTime(10_000L, 10_000L) == null, "reset efface heure GNSS fraîche");
        ok(state.getFreshNmeaSpeed(10_000L, 5_000L) == null, "reset efface vitesse NMEA fraîche");
        ok(state.getFreshCalcDop(10_000L, 5_000L) == null, "reset efface DOP calculé");

        System.out.printf("TOTAL : %d réussis, %d échoués%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    private static void section(String name) { System.out.println("\n--- " + name + " ---"); }
    private static void ok(boolean condition, String message) {
        if (condition) { pass++; System.out.println("OK   " + message); }
        else { fail++; System.out.println("FAIL " + message); }
    }
    private static void near(double expected, double actual, String message) {
        ok(Double.isFinite(actual) && Math.abs(expected - actual) < 1e-9,
                message + " (attendu " + expected + ", obtenu " + actual + ")");
    }
}
