package com.example.gpstest;

public final class MapStyleAndOrientationTest {
    private static int passed;
    private static int failed;

    private static void check(String label, boolean condition) {
        if (condition) passed++; else failed++;
        System.out.println((condition ? "  OK   " : "  FAIL ") + label);
    }

    public static void main(String[] args) {
        check("quatre styles Mapsforge sélectionnables", MapStyle.values().length == 4);
        check("préférence inconnue retombe sur Standard",
                MapStyle.fromId("future-style") == MapStyle.STANDARD);
        check("identifiants stables des styles randonnée, moto et classique",
                MapStyle.fromId("hiking") == MapStyle.HIKING
                        && MapStyle.fromId("motorider") == MapStyle.MOTORIDER
                        && MapStyle.fromId("classic") == MapStyle.CLASSIC);
        check("nord en haut : zéro degré de rotation", MapOrientationCalculator.headingUpRotation(0f) == 0f);
        check("cap est : rotation du fond vers l'ouest", MapOrientationCalculator.headingUpRotation(90f) == 270f);
        check("cap sud : demi-tour du fond", MapOrientationCalculator.headingUpRotation(180f) == 180f);
        check("valeur absente non transformée en nord", Float.isNaN(
                MapOrientationCalculator.headingUpRotation(Float.NaN)));
        System.out.println("RESULTAT : " + passed + " réussis, " + failed + " échoués");
        if (failed > 0) throw new AssertionError(failed + " tests échoués");
    }
}
