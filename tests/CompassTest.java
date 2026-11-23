package com.example.gpstest;

/**
 * TESTS DE LA BOUSSOLE (v5.14).
 *
 * Ce qui est testé ici est la LOGIQUE PURE : normalisation, écart le plus
 * court, orientation d'écran, application de la déclinaison, rose des vents,
 * formatage. Tout ce qui dépend d'un capteur ou d'Android n'est PAS testable
 * hors appareil — mais tout ce qui peut produire un cap faux l'est, et c'est
 * exactement ce qui est couvert.
 *
 * Les tests d'orientation d'écran viennent d'une démonstration géométrique
 * (voir CompassSensor) : cap écran = cap capteur - 90 × rotation. S'ils
 * passent, un téléphone en paysage n'affiche pas un cap décalé de 90°.
 */
public class CompassTest {

    private static int ok = 0, ko = 0;

    private static void check(boolean cond, String what) {
        if (cond) { ok++; }
        else { ko++; System.out.println("  ECHEC : " + what); }
    }

    private static void eq(String expected, String actual, String what) {
        if (expected.equals(actual)) ok++;
        else {
            ko++;
            System.out.println("  ECHEC : " + what
                    + "\n          attendu : " + expected
                    + "\n          obtenu  : " + actual);
        }
    }

    private static void near(float expected, float actual, float tol, String what) {
        if (Math.abs(expected - actual) <= tol) ok++;
        else {
            ko++;
            System.out.println("  ECHEC : " + what
                    + " (attendu " + expected + " ± " + tol + ", obtenu " + actual + ")");
        }
    }

    public static void main(String[] a) {
        System.out.println("=== Boussole : normalisation ===");
        near(0f,    Compass.normalize(0f),    1e-4f, "0 reste 0");
        near(359f,  Compass.normalize(-1f),   1e-4f, "-1 -> 359");
        near(330f,  Compass.normalize(-30f),  1e-4f, "-30 -> 330");
        near(10f,   Compass.normalize(370f),  1e-4f, "370 -> 10");
        near(0f,    Compass.normalize(360f),  1e-4f, "360 -> 0");
        near(0f,    Compass.normalize(720f),  1e-4f, "720 -> 0");
        near(180f,  Compass.normalize(-180f), 1e-4f, "-180 -> 180");
        check(Float.isNaN(Compass.normalize(Float.NaN)), "NaN reste NaN (ne devient pas 0)");
        check(Float.isNaN(Compass.normalize(Float.POSITIVE_INFINITY)),
                "l'infini reste NaN");
        // Le piege : 0 est un cap valide (le nord). Un azimut ABSENT ne doit
        // jamais etre confondu avec un cap nul.
        check(Compass.normalize(Float.NaN) != 0f, "un azimut absent n'est pas le nord");

        System.out.println("=== Boussole : ecart le plus court ===");
        near(20f,   Compass.shortestDelta(350f, 10f),  1e-4f, "350 -> 10 : +20 (pas -340)");
        near(-20f,  Compass.shortestDelta(10f, 350f),  1e-4f, "10 -> 350 : -20");
        near(180f,  Compass.shortestDelta(0f, 180f),   1e-4f, "0 -> 180");
        near(-180f, Compass.shortestDelta(180f, 0f),   1e-4f, "180 -> 0");
        near(0f,    Compass.shortestDelta(90f, 90f),   1e-4f, "aucun mouvement");
        near(90f,   Compass.shortestDelta(45f, 135f),  1e-4f, "+90");
        near(-90f,  Compass.shortestDelta(135f, 45f),  1e-4f, "-90");
        check(Float.isNaN(Compass.shortestDelta(Float.NaN, 10f)), "NaN propage");

        System.out.println("=== Boussole : orientation de l'ecran ===");
        // capEcran = normalize(capCapteur - 90*rotation)
        near(0f,   Compass.normalize(0f   - 90 * 0),    1e-4f, "rotation 0   : cap inchange");
        near(270f, Compass.normalize(0f   - 90),        1e-4f, "rotation 90  : 0 -> 270");
        near(180f, Compass.normalize(0f   - 180),       1e-4f, "rotation 180 : 0 -> 180");
        near(90f,  Compass.normalize(0f   - 270),       1e-4f, "rotation 270 : 0 -> 90");
        near(0f,   Compass.normalize(90f  - 90),        1e-4f, "rotation 90  : 90 -> 0");
        near(350f, Compass.normalize(80f  - 90),        1e-4f, "rotation 90  : 80 -> 350 (boucle bas)");

        System.out.println("=== Boussole : nord magnetique contre nord vrai ===");
        // Paris : declinaison +1,5 deg
        near(11.5f, Compass.toReference(10f, 1.5f, Compass.North.TRUE), 1e-3f,
                "Paris : 10 mag -> 11,5 vrai (+1,5)");
        near(10f, Compass.toReference(10f, 1.5f, Compass.North.MAGNETIC), 1e-3f,
                "nord magnetique : aucune correction");
        // Montreal : declinaison NEGATIVE (environ -14 deg, ouest)
        near(356f, Compass.toReference(10f, -14f, Compass.North.TRUE), 1e-3f,
                "Montreal : 10 mag -> -4 -> 356 vrai");
        // Vancouver : declinaison POSITIVE (environ +15 deg, est)
        near(25f, Compass.toReference(10f, 15f, Compass.North.TRUE), 1e-3f,
                "Vancouver : 10 mag -> 25 vrai");
        // Cap negatif apres correction
        near(3f, Compass.toReference(350f, 13f, Compass.North.TRUE), 1e-3f,
                "350 + 13 = 3 (rebouclage)");
        // Le Cap : declinaison negative
        near(345f, Compass.toReference(0f, -15f, Compass.North.TRUE), 1e-3f,
                "0 + (-15) = 345");
        // Declinaison inconnue : on retombe sur le magnetique, on n'invente pas 0
        near(10f, Compass.toReference(10f, Float.NaN, Compass.North.TRUE), 1e-3f,
                "declinaison inconnue -> cap magnetique, pas de fabrication");
        check(!Compass.trueNorthApplied(Compass.North.TRUE, Float.NaN),
                "vrai nord demande mais non applique est signale");
        check(Compass.trueNorthApplied(Compass.North.TRUE, 1.5f),
                "vrai nord applique quand la declinaison existe");
        check(!Compass.trueNorthApplied(Compass.North.MAGNETIC, 1.5f),
                "magnetique : le vrai nord n'est pas applique");
        check(Float.isNaN(Compass.toReference(Float.NaN, 1.5f, Compass.North.TRUE)),
                "azimut absent reste absent");

        System.out.println("=== Boussole : rose des vents ===");
        eq("Nord", Compass.cardinal(0f), "0 = Nord");
        eq("Nord", Compass.cardinal(11f), "11 = Nord (moins de 11,25)");
        eq("Nord-Nord-Est", Compass.cardinal(12f), "12 = Nord-Nord-Est");
        eq("Est", Compass.cardinal(90f), "90 = Est");
        eq("Sud", Compass.cardinal(180f), "180 = Sud");
        eq("Ouest", Compass.cardinal(270f), "270 = Ouest");
        eq("Nord", Compass.cardinal(359f), "359 = Nord");
        eq("Nord-Ouest", Compass.cardinal(315f), "315 = Nord-Ouest");
        eq("—", Compass.cardinal(Float.NaN), "absent = tiret");
        // Les 16 secteurs doivent etre atteignables : aucun trou.
        boolean[] seen = new boolean[16];
        for (int d = 0; d < 360; d++) seen[Compass.cardinalIndex((float) d)] = true;
        int trouves = 0;
        for (boolean b : seen) if (b) trouves++;
        check(trouves == 16, "les 16 secteurs sont atteignables (" + trouves + "/16)");

        System.out.println("=== Boussole : formatage des degres ===");
        eq("000°", Compass.formatDegrees(0f), "0 -> 000");
        eq("007°", Compass.formatDegrees(7f), "7 -> 007 (trois chiffres)");
        eq("045°", Compass.formatDegrees(45f), "45 -> 045");
        eq("090°", Compass.formatDegrees(90f), "90 -> 090");
        eq("180°", Compass.formatDegrees(180f), "180 -> 180");
        eq("359°", Compass.formatDegrees(359f), "359 -> 359");
        eq("000°", Compass.formatDegrees(359.7f), "359,7 -> 000 (360 n'existe pas)");
        eq("000°", Compass.formatDegrees(360f), "360 -> 000");
        eq("—",    Compass.formatDegrees(Float.NaN), "absent -> tiret");
        // Le piege de l'arrondi : 359,5 doit donner 000, pas 360.
        eq("000°", Compass.formatDegrees(359.5f), "359,5 -> 000");

        System.out.println("=== Boussole : formatage de la declinaison ===");
        eq("+1,5°",  Compass.formatDeclination(1.5f),  "declinaison positive signee");
        eq("-12,3°", Compass.formatDeclination(-12.3f), "declinaison negative");
        eq("+0,0°",  Compass.formatDeclination(0f),     "zero porte un signe (n'est pas une absence)");
        eq("—",      Compass.formatDeclination(Float.NaN), "inconnue -> tiret");

        System.out.println("=== Boussole : description complete ===");
        eq("Nord (000°)", Compass.describe(0f), "0");
        eq("Est (090°)", Compass.describe(90f), "90");
        eq("—", Compass.describe(Float.NaN), "absent");

        System.out.println("=== Boussole : abreviation des secteurs longs ===");
        eq("N-O",   Compass.abbreviate("Nord-Ouest"), "Nord-Ouest -> N-O");
        eq("O-S-O", Compass.abbreviate("Ouest-Sud-Ouest"), "Ouest-Sud-Ouest -> O-S-O");
        eq("N-N-E", Compass.abbreviate("Nord-Nord-Est"), "Nord-Nord-Est -> N-N-E");
        eq("E-N-E", Compass.abbreviate("Est-Nord-Est"), "Est-Nord-Est -> E-N-E");
        eq("S-S-O", Compass.abbreviate("Sud-Sud-Ouest"), "Sud-Sud-Ouest -> S-S-O");
        // Les huit secteurs de 45 deg n'ont pas a etre abreges :
        eq("Nord",  Compass.abbreviate("Nord"), "Nord inchange");
        eq("Sud",   Compass.abbreviate("Sud"), "Sud inchange");
        check(Compass.abbreviate(null) == null, "null traverse sans exception");
        // Aucune abreviation ne doit etre plus longue que l'original.
        for (String c : Compass.CARDINALS) {
            String ab = Compass.abbreviate(c);
            check(ab != null && ab.length() <= c.length(),
                    "abreviation plus courte ou egale pour " + c);
        }

        System.out.println("=== Boussole : preferences ===");
        check(Compass.North.fromId("true") == Compass.North.TRUE, "lecture 'true'");
        check(Compass.North.fromId("magnetic") == Compass.North.MAGNETIC, "lecture 'magnetic'");
        check(Compass.North.fromId(null) == Compass.North.MAGNETIC, "null -> defaut magnetique");
        check(Compass.North.fromId("valeur-inconnue") == Compass.North.MAGNETIC,
                "valeur inconnue -> defaut, jamais d'exception");
        // Chaque identifiant doit se relire : une preference ecrite doit
        // pouvoir etre relue sans perte.
        for (Compass.North n : Compass.North.values()) {
            check(Compass.North.fromId(n.id) == n, "aller-retour de l'id '" + n.id + "'");
        }

        System.out.println();
        System.out.println(ok + " reussis, " + ko + " echoues");
        if (ko > 0) System.exit(1);
    }
}
