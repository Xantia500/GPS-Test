package com.example.gpstest;

/**
 * Test autonome, sans Android ni JVM Android : on vérifie la logique PURE.
 * Compilation + exécution :
 *   javac -d out app/src/main/java/com/example/gpstest/Utils.java tests/UtilsTest.java
 *   java -cp out com.example.gpstest.UtilsTest
 */
public class UtilsTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        // --- Coordonnées (Tour Eiffel) ---
        assertEquals("48°51'30,2\"N", Utils.formatDms(48.8584, true), "DMS latitude positive");
        assertEquals("2°17'40,2\"E",  Utils.formatDms(2.2945, false), "DMS longitude positive");

        // --- Hémisphères négatifs (Rio / Sydney) ---
        assertTrue(Utils.formatDms(-22.9068, true).endsWith("S"), "latitude sud -> S");
        assertTrue(Utils.formatDms(-43.1729, false).endsWith("O"), "longitude ouest -> O");

        // --- Vitesse ---
        assertEquals("4,3 km/h", Utils.formatKmh(1.2f), "conversion m/s -> km/h");

        // --- Cap ---
        assertEquals("Nord (000°)",        Utils.formatBearing(0f),   "cap 0 = Nord");
        assertEquals("Nord (360°)",        Utils.formatBearing(360f), "cap 360 = Nord (wrap)");
        assertTrue(Utils.formatBearing(90f).startsWith("Est"), "cap 90 = Est");
        assertTrue(Utils.formatBearing(312f).startsWith("Nord-Ouest"), "cap 312 = Nord-Ouest");

        // --- Durées ---
        assertEquals("12,4 s", Utils.formatDuration(12_400L), "TTFF secondes");
        assertEquals("01:05",  Utils.formatDuration(65_000L), "TTFF minutes");

        // --- Géométrie : azimut 0 / élévation 0 -> bord haut (Nord) ---
        float[] north = Utils.azElToXY(0f, 0f, 100f, 100f, 50f);
        assertNear(100f, north[0], 0.01f, "azimut 0 : x au centre");
        assertNear(50f,  north[1], 0.01f, "azimut 0 : y au bord haut");

        // --- azimut 90 / élévation 0 -> bord droit (Est) ---
        float[] east = Utils.azElToXY(90f, 0f, 100f, 100f, 50f);
        assertNear(150f, east[0], 0.01f, "azimut 90 : x au bord droit");
        assertNear(100f, east[1], 0.01f, "azimut 90 : y au centre");

        // --- zénith -> centre ---
        float[] zenith = Utils.azElToXY(45f, 90f, 100f, 100f, 50f);
        assertNear(100f, zenith[0], 0.01f, "zénith : x au centre");
        assertNear(100f, zenith[1], 0.01f, "zénith : y au centre");

        // --- élévation 45 -> à mi-chemin (rayon 25) vers le Sud ---
        float[] south = Utils.azElToXY(180f, 45f, 100f, 100f, 50f);
        assertNear(100f, south[0], 0.01f, "élévation 45 : x au centre");
        assertNear(125f, south[1], 0.01f, "él. 45 vers Sud : y = centre + rayon/2");

        // --- SNR ---
        assertNear(1.00f, Utils.snrFraction(50f),  0.001f, "SNR 50 dBHz -> 1");
        assertNear(0.60f, Utils.snrFraction(30f),  0.001f, "SNR 30 dBHz -> 0,6");
        assertNear(0.00f, Utils.snrFraction(-5f),  0.001f, "SNR négatif -> 0");
        assertNear(1.00f, Utils.snrFraction(200f), 0.001f, "SNR énorme -> plafonné à 1");

        // --- Constellations ---
        assertEquals("GALILEO", Utils.constellationName(6), "code 6 = Galileo");
        assertEquals("GLONASS", Utils.constellationName(3), "code 3 = GLONASS");
        assertEquals("?",       Utils.constellationName(99), "code inconnu");

        System.out.println("---------------------------------------------");
        System.out.printf("RESULTAT : %d reussis, %d echoues%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void assertEquals(String expected, String actual, String label) {
        if (expected.equals(actual)) {
            passed++;
            System.out.println("  OK   " + label + " -> " + actual);
        } else {
            failed++;
            System.out.println("  FAIL " + label + " : attendu [" + expected + "] obtenu [" + actual + "]");
        }
    }

    private static void assertTrue(boolean condition, String label) {
        if (condition) {
            passed++;
            System.out.println("  OK   " + label);
        } else {
            failed++;
            System.out.println("  FAIL " + label);
        }
    }

    private static void assertNear(float expected, float actual, float tolerance, String label) {
        if (Math.abs(expected - actual) <= tolerance) {
            passed++;
            System.out.println("  OK   " + label + " -> " + actual);
        } else {
            failed++;
            System.out.println("  FAIL " + label + " : attendu " + expected + " obtenu " + actual);
        }
    }
}
