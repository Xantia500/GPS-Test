package com.example.gpstest;

public final class MapZoomCalculatorTest {
    private static int passed;
    private static int failed;

    private static void check(String label, boolean condition) {
        if (condition) passed++; else failed++;
        System.out.println((condition ? "  OK   " : "  FAIL ") + label);
    }

    public static void main(String[] args) {
        byte small = MapZoomCalculator.fit(48.80, 48.95, 2.20, 2.50,
                1080, 1800, 256, 48, (byte) 3, (byte) 18);
        byte country = MapZoomCalculator.fit(42.0, 51.2, -5.5, 8.5,
                1080, 1800, 256, 48, (byte) 3, (byte) 18);
        check("petite emprise affichée à un zoom supérieur à celui d'un pays", small > country);

        byte dateline = MapZoomCalculator.fit(-10, 10, 170, 190,
                1080, 1800, 256, 48, (byte) 3, (byte) 18);
        byte nearlyWorld = MapZoomCalculator.fit(-10, 10, -170, 170,
                1080, 1800, 256, 48, (byte) 3, (byte) 18);
        check("franchissement de l'antiméridien traité comme 20 degrés", dateline > nearlyWorld);

        byte pointLike = MapZoomCalculator.fit(48.85, 48.85000001, 2.35, 2.35000001,
                1080, 1800, 256, 48, (byte) 3, (byte) 18);
        check("emprise quasi ponctuelle plafonnée au zoom maximal", pointLike == 18);

        byte invalid = MapZoomCalculator.fit(Double.NaN, 1, 0, 1,
                1080, 1800, 256, 48, (byte) 4, (byte) 18);
        check("entrée non finie renvoie le zoom minimal", invalid == 4);

        byte narrowScreen = MapZoomCalculator.fit(48.80, 48.95, 2.20, 2.50,
                320, 640, 256, 48, (byte) 3, (byte) 18);
        check("écran étroit ne demande pas un zoom supérieur", narrowScreen <= small);

        System.out.println("RESULTAT : " + passed + " réussis, " + failed + " échoués");
        if (failed > 0) throw new AssertionError(failed + " tests échoués");
    }
}
