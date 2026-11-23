package com.example.gpstest;

public final class MapPointTest {
    private static int passed;
    private static int failed;

    private static void check(String label, boolean condition) {
        if (condition) passed++; else failed++;
        System.out.println((condition ? "  OK   " : "  FAIL ") + label);
    }

    private static void rejects(String label, double latitude, double longitude) {
        boolean rejected = false;
        try {
            new MapPoint(latitude, longitude);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check(label, rejected);
    }

    public static void main(String[] args) {
        MapPoint origin = new MapPoint(0.0, 0.0);
        check("origine géographique conservée comme coordonnée valide du moteur", 
                origin.latitude == 0.0 && origin.longitude == 0.0);

        MapPoint northEast = new MapPoint(90.0, 180.0);
        MapPoint southWest = new MapPoint(-90.0, -180.0);
        check("limites géographiques nord-est acceptées",
                northEast.latitude == 90.0 && northEast.longitude == 180.0);
        check("limites géographiques sud-ouest acceptées",
                southWest.latitude == -90.0 && southWest.longitude == -180.0);

        rejects("latitude au-delà de 90° rejetée", 90.000001, 0.0);
        rejects("latitude sous -90° rejetée", -90.000001, 0.0);
        rejects("longitude au-delà de 180° rejetée", 0.0, 180.000001);
        rejects("longitude sous -180° rejetée", 0.0, -180.000001);
        rejects("NaN rejeté", Double.NaN, 0.0);
        rejects("infini rejeté", 0.0, Double.POSITIVE_INFINITY);

        System.out.println("RESULTAT : " + passed + " réussis, " + failed + " échoués");
        if (failed > 0) throw new AssertionError(failed + " tests échoués");
    }
}
