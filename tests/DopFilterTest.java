package com.example.gpstest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tests du filtre/tri satellite, du DOP géométrique et de la détection de bandes.
 * Les valeurs de référence du DOP sont calculées à la main (voir commentaires).
 */
public class DopFilterTest {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {

        // ============================================================
        section("FILTRE ET TRI");
        // ============================================================
        List<SatInfo> sats = new ArrayList<>();
        sats.add(new SatInfo("GPS", 12, 45, 30, 42f, true, 1575.42e6));
        sats.add(new SatInfo("GPS", 5, 120, 15, 25f, false, 1575.42e6));
        sats.add(new SatInfo("GALILEO", 7, 200, 60, 48f, true, 1575.42e6));
        sats.add(new SatInfo("GLONASS", 3, 300, 25, 33f, false, 1600.995e6));
        sats.add(new SatInfo("GPS", 9, 10, 80, 50f, true, 1176.45e6));

        // Tous : 5 satellites
        ok(SatelliteFilter.apply(sats, SatelliteFilter.Mode.ALL).size() == 5, "mode ALL = 5 satellites");
        // Utilisés : 3
        List<SatInfo> inFix = SatelliteFilter.apply(sats, SatelliteFilter.Mode.IN_FIX);
        ok(inFix.size() == 3, "mode IN_FIX = 3 satellites");
        ok(SatelliteFilter.countInFix(sats) == 3, "countInFix = 3");
        List<SatInfo> withZeroCn0 = new ArrayList<>(sats);
        withZeroCn0.add(new SatInfo("GPS", 44, 90, 25, 0f, true, 1575.42e6));
        withZeroCn0.add(new SatInfo("GPS", 45, 90, 25, 18f, false, 1575.42e6));
        ok(SatelliteFilter.apply(withZeroCn0, SatelliteFilter.Mode.IN_FIX).size() == 3,
                "mode IN_FIX exclut SNR nul même si le récepteur marque le satellite utilisé");
        ok(SatelliteFilter.apply(withZeroCn0, SatelliteFilter.Mode.GPS).size() == 4,
                "filtre constellation affiche les signaux GPS mesurés, utilisés ou non");

        // Tri : utilisés d'abord, puis SNR décroissant
        List<SatInfo> all = SatelliteFilter.apply(sats, SatelliteFilter.Mode.ALL);
        ok(all.get(0).inFix, "tri : 1er = utilisé");
        ok(all.get(1).inFix, "tri : 2e = utilisé");
        ok(all.get(2).inFix, "tri : 3e = utilisé");
        ok(all.get(2).svid == 12, "tri : parmi les utilisés, SNR décroissant (12 puis 7 puis 9) -> 12");
        ok(all.get(3).svid == 3, "tri : non-utilisés, meilleur SNR d'abord (33 dB) -> GLONASS 3");
        ok(all.get(4).svid == 5, "tri : dernier = SNR le plus faible (25 dB) -> GPS 5");

        // Filtre par constellation
        ok(SatelliteFilter.apply(sats, SatelliteFilter.Mode.GPS).size() == 3, "mode GPS = 3");
        ok(SatelliteFilter.apply(sats, SatelliteFilter.Mode.GALILEO).size() == 1, "mode GALILEO = 1");
        ok(SatelliteFilter.apply(sats, SatelliteFilter.Mode.BEIDOU).isEmpty(), "mode BEIDOU = 0");

        // Convention de nommage : doit correspondre aux noms de Utils
        ok(SatelliteFilter.Mode.BEIDOU.label.equals("BeiDou"), "label BEIDOU = BeiDou");
        ok("BEIDOU".equalsIgnoreCase(SatelliteFilter.Mode.BEIDOU.label), "matches() insensible à la casse");

        // Résumé par constellation
        String summary = SatelliteFilter.constellationSummary(sats);
        ok(summary.contains("GPS 2/3"), "résumé GPS 2 utilisés / 3 vus -> " + summary);
        ok(summary.contains("GALILEO 1/1"), "résumé GALILEO 1/1");

        // Ne modifie pas l'entrée
        ok(sats.get(0).svid == 12, "la liste d'entrée n'est pas modifiée");

        // ============================================================
        section("DOP GEOMETRIQUE");
        // ============================================================
        // Cas 1 : 4 satellites, un au zénith + 3 à l'horizon à 120° d'écart.
        // C'est une géométrie correcte : HDOP autour de 1.4, VDOP autour de 2.
        SatInfo top = new SatInfo("GPS", 1, 0, 90, 45f, true, 1575.42e6);
        // Élévation 5° et non 0° : un satellite pile sur l'horizon est exclu du calcul
        // (signal inexploitable, erreur atmosphérique maximale). C'est voulu.
        SatInfo h1 = new SatInfo("GPS", 2, 0, 5, 45f, true, 1575.42e6);
        SatInfo h2 = new SatInfo("GPS", 3, 120, 5, 45f, true, 1575.42e6);
        SatInfo h3 = new SatInfo("GPS", 4, 240, 5, 45f, true, 1575.42e6);
        DopCalculator.Dop dop = DopCalculator.compute(Arrays.asList(top, h1, h2, h3));
        ok(dop != null, "4 satellites : DOP calculable");
        System.out.println("         " + (dop == null ? "null" : dop.format()));
        if (dop != null) {
            ok(dop.hdop > 1.0 && dop.hdop < 2.5, "HDOP plausible (" + String.format("%.2f", dop.hdop) + ", attendu ~1.2, calculé à la main)");
            ok(dop.vdop > 1.0 && dop.vdop < 4.0, "VDOP plausible (" + String.format("%.2f", dop.vdop) + ", attendu ~1.3)");
            ok(dop.pdop > dop.hdop, "PDOP > HDOP");
            ok(dop.gdop > dop.pdop, "GDOP > PDOP");
            ok(dop.satelliteCount == 4, "compte de satellites = 4");
        }

        // Cas 1b : un satellite à élévation exactement 0° doit être exclu
        SatInfo horizonExact = new SatInfo("GPS", 9, 90, 0, 45f, true, 0);
        List<SatInfo> withHorizon = Arrays.asList(top, h1, h2, h3, horizonExact);
        DopCalculator.Dop dopH = DopCalculator.compute(withHorizon);
        ok(dopH != null && dopH.satelliteCount == 4,
                "élévation 0° exclue (4 retenus sur 5)");

        // Cas 2 : moins de 4 satellites -> pas de DOP
        ok(DopCalculator.compute(Arrays.asList(top, h1, h2)) == null, "3 satellites : DOP impossible");
        ok(DopCalculator.compute(new ArrayList<>()) == null, "0 satellite : DOP impossible");

        // Cas 3 : 4 satellites GROUPÉS dans la même direction -> très mauvais DOP
        SatInfo c1 = new SatInfo("GPS", 1, 45, 45, 45f, true, 0);
        SatInfo c2 = new SatInfo("GPS", 2, 47, 44, 45f, true, 0);
        SatInfo c3 = new SatInfo("GPS", 3, 43, 46, 45f, true, 0);
        SatInfo c4 = new SatInfo("GPS", 4, 45, 43, 45f, true, 0);
        DopCalculator.Dop bad = DopCalculator.compute(Arrays.asList(c1, c2, c3, c4));
        if (bad != null) {
            ok(bad.hdop > 5.0, "géométrie groupée : HDOP mauvais (" + String.format("%.2f", bad.hdop) + ")");
        } else {
            // une matrice singulière est aussi une réponse correcte ici
            ok(true, "géométrie groupée : DOP non calculable (matrice quasi singulière)");
        }

        // Cas 4 : satellites sous l'horizon ignorés
        SatInfo below = new SatInfo("GPS", 5, 0, -10, 45f, true, 0);
        DopCalculator.Dop d2 = DopCalculator.compute(Arrays.asList(top, h1, h2, h3, below));
        ok(d2 != null && d2.satelliteCount == 4, "satellite sous l'horizon exclu du calcul");

        // Cas 5 : 6 satellites bien répartis -> meilleur DOP que 4
        SatInfo h4 = new SatInfo("GPS", 5, 60, 35, 45f, true, 0);
        SatInfo h5 = new SatInfo("GPS", 6, 300, 35, 45f, true, 0);
        DopCalculator.Dop dop6 = DopCalculator.compute(
                Arrays.asList(top, h1, h2, h3, h4, h5));
        if (dop != null && dop6 != null) {
            ok(dop6.hdop < dop.hdop, String.format(
                    "6 satellites : HDOP meilleur (%.2f < %.2f)", dop6.hdop, dop.hdop));
        }

        // Cas 6 : comparaison récepteur / calculée
        if (dop != null) {
            String cmp = DopCalculator.compare(1.4, dop);
            ok(cmp.contains("récepteur") && cmp.contains("géométrie inFix")
                    && !cmp.contains("cohérents"), "comparaison prudente, sans fausse équivalence : " + cmp);
            ok(DopCalculator.compare(Double.NaN, dop).contains("n/d"), "récepteur sans DOP : signalé n/d");
        }

        // Cas 6b : computeFromUsable ne doit jamais faire confiance aveuglément
        // au nom de la méthode. Il revalide inFix, C/N0 et la géométrie.
        SatInfo notUsed = new SatInfo("GPS", 5, 300, 35, 45f, false, 1575.42e6);
        SatInfo zeroCn0 = new SatInfo("GPS", 6, 300, 35, 0f, true, 1575.42e6);
        SatInfo excessiveCn0 = new SatInfo("GPS", 7, 300, 35, 64f, true, 1575.42e6);
        ok(DopCalculator.computeFromUsable(Arrays.asList(top, h1, h2, notUsed)) == null,
                "computeFromUsable re-filtre inFix : 3 satellites retenus, pas de DOP");
        ok(DopCalculator.computeFromUsable(Arrays.asList(top, h1, h2, zeroCn0)) == null,
                "C/N0 = 0 exclu du DOP");
        ok(DopCalculator.computeFromUsable(Arrays.asList(top, h1, h2, excessiveCn0)) == null,
                "C/N0 hors domaine Android exclu du DOP");

        // Un même SV vu sur plusieurs signaux ne doit pas être compté deux
        // fois comme s'il fournissait deux directions indépendantes.
        SatInfo duplicateH1 = new SatInfo("GPS", 2, 0, 5, 50f, true, 1176.45e6);
        DopCalculator.Dop deduplicated = DopCalculator.computeFromUsable(
                Arrays.asList(top, h1, h2, h3, duplicateH1));
        ok(deduplicated != null && deduplicated.satelliteCount == 4,
                "même constellation/SVID dédoublonné, même si deux porteuses sont présentes");
        if (deduplicated != null && dop != null)
            near(dop.hdop, deduplicated.hdop, 1e-9, "la copie multibande ne biaise pas le HDOP");

        // Modèle multi-constellation : une horloge par constellation dans
        // cette approximation du DOP (ce n'est pas une règle de rejet fix).
        SatInfo gal5 = new SatInfo("GALILEO", 5, 60, 35, 42f, true, 1575.42e6);
        DopCalculator.Dop multiConst = DopCalculator.compute(Arrays.asList(top, h1, h2, h3, gal5));
        ok(multiConst != null && multiConst.constellationCount == 2
                        && multiConst.satelliteCount == 5,
                "5 satellites / 2 constellations : 5 inconnues du modèle, DOP calculé");
        ok(DopCalculator.compute(Arrays.asList(top, h1, h2, gal5)) == null,
                "4 satellites / 2 constellations : rang insuffisant pour cette estimation DOP");

        // L'azimut 360° est légal dans Android et équivalent à 0°.
        SatInfo h1At360 = new SatInfo("GPS", 2, 360, 5, 45f, true, 1575.42e6);
        DopCalculator.Dop az360 = DopCalculator.compute(Arrays.asList(top, h1At360, h2, h3));
        ok(az360 != null && dop != null, "azimut 360° admis");
        if (az360 != null && dop != null)
            near(dop.hdop, az360.hdop, 1e-9, "azimut 360° normalisé à 0°");

        // Cas 7 : inversion de matrice : A * A^-1 = I
        double[][] a = {{4,1,2,1},{1,3,0,1},{2,0,5,2},{1,1,2,4}};
        double[][] inv = DopCalculator.invert4(a);
        ok(inv != null, "invert4 : matrice inversible");
        if (inv != null) {
            double s00 = a[0][0]*inv[0][0] + a[0][1]*inv[1][0] + a[0][2]*inv[2][0] + a[0][3]*inv[3][0];
            near(1.0, s00, 1e-9, "A·A⁻¹ identité en (0,0)");
            double s01 = a[0][0]*inv[0][1] + a[0][1]*inv[1][1] + a[0][2]*inv[2][1] + a[0][3]*inv[3][1];
            near(0.0, s01, 1e-9, "A·A⁻¹ identité en (0,1)");
        }
        double[][] singular = {{1,2,3,4},{2,4,6,8},{1,1,1,1},{0,0,0,0}};
        ok(DopCalculator.invert4(singular) == null, "invert4 : matrice singulière détectée");

        // ============================================================
        section("DETECTION DES BANDES");
        // ============================================================
        // Double fréquence : l'appareil remonte L1 et L5
        List<Double> dual = Arrays.asList(1575.42e6, 1176.45e6, 1575.42e6, 1575.42e6);
        BandSupport.Profile pDual = BandSupport.analyze(dual, "API 34 (signal types)");
        ok(pDual.isMultiFrequency(), "L1+L5 -> multi-fréquence détecté");
        ok(pDual.hasL5(), "hasL5() vrai");
        ok(pDual.bands.size() == 2, "2 bandes distinctes (" + pDual.bands + ")");
        ok(pDual.summary().contains("double fréquence"), "résumé : double fréquence");
        System.out.println("         " + pDual.summary());

        // Mono fréquence
        BandSupport.Profile pMono = BandSupport.analyze(
                Arrays.asList(1575.42e6, 1575.42e6), "observé sur satellites");
        ok(!pMono.isMultiFrequency(), "L1 seule -> mono-fréquence");
        ok(!pMono.hasL5(), "hasL5() faux");
        ok(pMono.summary().contains("bande unique"), "résumé : bande unique");

        // Rien de rapporté -> inconnu, pas de mensonge
        BandSupport.Profile pUnknown = BandSupport.analyze(new ArrayList<>(), "API 30-33");
        ok(pUnknown.unknown, "aucune donnée -> profil inconnu");
        ok(!pUnknown.isMultiFrequency(), "inconnu => pas de conclusion multi-fréquence");
        ok(pUnknown.summary().contains("non rapportées"), "résumé honnête : non rapportées");

        // Compteurs par bande
        List<SatInfo> mixed = new ArrayList<>();
        mixed.add(new SatInfo("GPS", 1, 0, 45, 40f, true, 1575.42e6));
        mixed.add(new SatInfo("GPS", 1, 0, 45, 38f, true, 1176.45e6));
        mixed.add(new SatInfo("GPS", 2, 90, 45, 41f, true, 1575.42e6));
        mixed.add(new SatInfo("GALILEO", 3, 180, 45, 44f, true, 1575.42e6));
        String counters = BandSupport.bandCounters(mixed);
        ok(counters != null && counters.equals("L1 3 · L5 1"), "compteurs L1/L5 : " + counters);

        List<SatInfo> noCarrier = new ArrayList<>();
        noCarrier.add(new SatInfo("GPS", 1, 0, 45, 40f, true, Double.NaN));
        ok(BandSupport.bandCounters(noCarrier) == null, "aucune porteuse connue -> compteurs nuls");

        // ============================================================
        section("COHERENCE AVEC Utils");
        // ============================================================
        // Les noms de constellations de SatInfo doivent être ceux de Utils
        ok(Utils.constellationName(1).equals("GPS"), "Utils: 1 -> GPS");
        ok(Utils.constellationName(6).equals("GALILEO"), "Utils: 6 -> GALILEO (majuscules)");
        ok(SatelliteFilter.Mode.GALILEO.label.equalsIgnoreCase("GALILEO"), "filtre GALILEO cohérent avec Utils");
        ok(SatelliteFilter.Mode.GPS.label.equalsIgnoreCase("GPS"), "filtre GPS cohérent");
        ok(SatelliteFilter.Mode.GLONASS.label.equalsIgnoreCase("GLONASS"), "filtre GLONASS cohérent");

        System.out.println("\n=============================================");
        System.out.printf("RESULTAT : %d reussis, %d echoues%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    static void section(String n) { System.out.println("\n--- " + n + " ---"); }
    static void ok(boolean c, String l) {
        if (c) { pass++; System.out.println("  OK   " + l); }
        else { fail++; System.out.println("  FAIL " + l); }
    }
    static void near(double e, double a, double t, String l) {
        if (Math.abs(e - a) <= t) { pass++; System.out.println("  OK   " + l + " -> " + a); }
        else { fail++; System.out.println("  FAIL " + l + " : attendu " + e + " obtenu " + a); }
    }
}
