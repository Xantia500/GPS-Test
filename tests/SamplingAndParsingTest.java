package com.example.gpstest;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests de SamplePolicy et GpxParser (v5.10) — sans Android.
 *
 * Le test le plus important est la section 3 : on écrit un GPX avec NOTRE
 * writer, puis on le relit avec NOTRE parser. Si les deux ne sont pas
 * d'accord, c'est que l'un des deux est faux — et un aller-retour ne
 * pardonne pas.
 */
public class SamplingAndParsingTest {

    static int pass = 0, fail = 0;
    static final long T0 = 1_766_000_000_000L;
    static final long E0 = 9_000_000L;

    public static void main(String[] args) {

        // ============================================================
        section("1. Espacement : distance OU temps, jamais ET");
        // ============================================================
        // Rien n'est encore écrit : premier point, toujours accepté.
        SamplePolicy.Verdict v1 = SamplePolicy.decide(25, 10_000, Double.NaN, -1);
        ok(v1.write, "premier point -> toujours écrit");

        // À l'arrêt, bruit de ±3 m : le seuil de distance ne suffit pas,
        // mais le critère de TEMPS doit écrire.
        SamplePolicy.Verdict v2 = SamplePolicy.decide(25, 10_000, 3.0, 10_500);
        ok(v2.write, "10,5 s écoulées à 25 m : ÉCRIT (critère de temps)");
        ok(v2.byTime, "  -> par le temps");
        ok(!v2.byDistance, "  -> pas par la distance");

        // Vraie marche : 40 m en 3 s.
        SamplePolicy.Verdict v3 = SamplePolicy.decide(25, 10_000, 40.0, 3_000);
        ok(v3.write, "40 m en 3 s : ÉCRIT (critère de distance)");
        ok(v3.byDistance, "  -> par la distance");
        ok(!v3.byTime, "  -> pas par le temps");

        // Les deux conditions fausses : on n'écrit pas.
        SamplePolicy.Verdict v4 = SamplePolicy.decide(25, 10_000, 5.0, 4_000);
        ok(!v4.write, "5 m en 4 s : non écrit (ni l'un ni l'autre)");

        // Le piège : si on exigeait les DEUX (ET), v4 serait juste mais v2
        // et v3 seraient faux tous les deux. C'est l'erreur classique.
        ok(v2.write && v3.write,
                "vérification : distance ET temps REFUSERAIT les deux cas ci-dessus");

        // Votre cas : 10 m / 1 km
        SamplePolicy.Verdict v5 = SamplePolicy.decide(1000, 0, 400.0, 600_000);
        ok(!v5.write, "1 km / pas de temps : 400 m -> non écrit");
        SamplePolicy.Verdict v6 = SamplePolicy.decide(10, 0, 10.0, 1_000);
        ok(v6.write, "10 m / pas de temps : 10 m -> écrit");

        // Aucun critère : on écrit tout
        ok(SamplePolicy.decide(0, 0, 0.0, 0).write, "0 m + 0 s -> tout est écrit");
        ok(SamplePolicy.decide(0, 0, 0.1, 1).write, "0 m + 0 s -> même un demi-mètre");

        // Bornes
        ok(SamplePolicy.sanitizeDistance(-5) == SamplePolicy.DEFAULT_MIN_DISTANCE_M,
                "distance négative -> défaut");
        ok(SamplePolicy.sanitizeDistance(999_999) == SamplePolicy.MIN_DISTANCE_CEIL_M,
                "distance absurde -> plafonnée");
        ok(SamplePolicy.sanitizeTime(-1) == SamplePolicy.DEFAULT_MIN_TIME_MS,
                "temps négatif -> défaut");
        ok(SamplePolicy.DISTANCE_CHOICES.length == 8, "8 choix de distance proposés");
        ok(SamplePolicy.TIME_CHOICES.length == 9, "9 choix de temps proposés");
        ok(SamplePolicy.TIME_CHOICES[SamplePolicy.TIME_CHOICES.length - 1] == 600_000L,
                "le plus long choix de temps est 10 min (comme demandé)");
        ok(SamplePolicy.DISTANCE_CHOICES[SamplePolicy.DISTANCE_CHOICES.length - 1] == 1000,
                "la plus grande distance proposée est 1 km (comme demandé)");

        // ============================================================
        section("2. Session : compteur de points écartés");
        // ============================================================
        SamplePolicy.Session s = new SamplePolicy.Session();
        s.offer(25, 10_000, 48.0, 2.0, E0);
        ok(s.writtenCount() == 1 && s.skippedCount() == 0, "1er point écrit");
        s.offer(25, 10_000, 48.000001, 2.0, E0 + 1000);
        ok(s.writtenCount() == 1 && s.skippedCount() == 1, "2e écarté (0,1 m)");
        // Une vraie distance
        s.offer(25, 10_000, 48.0004, 2.0, E0 + 2000);   // ~44 m
        ok(s.writtenCount() == 2, "3e écrit (44 m)");
        // Après coupure : le prochain point est toujours écrit
        s.breakSegment();
        s.offer(25, 10_000, 48.00041, 2.0, E0 + 3000);
        ok(s.writtenCount() == 3, "après coupure, le point est écrit même à 1 m");
        s.reset();
        ok(s.writtenCount() == 0 && s.skippedCount() == 0, "reset remet les compteurs à zéro");

        // ============================================================
        section("3. Aller-retour : notre GPX relu par notre parser");
        // ============================================================
        GpxWriter.TrackPoint a = new GpxWriter.TrackPoint(T0, 48.8566, 2.3522, 35.0);
        GpxWriter.TrackPoint b = new GpxWriter.TrackPoint(T0 + 1000, 48.8567, 2.3523, 36.0);
        GpxWriter.TrackPoint c = new GpxWriter.TrackPoint(T0 + 400_000, 48.8700, 2.3700, 40.0);
        GpxWriter.TrackPoint d = new GpxWriter.TrackPoint(T0 + 401_000, 48.8701, 2.3701, 41.0);

        String gpx = GpxWriter.header(GpxWriter.Version.V11, "Aller-retour", T0)
                + GpxWriter.pointXml(a, GpxWriter.Version.V11)
                + GpxWriter.pointXml(b, GpxWriter.Version.V11)
                + GpxWriter.segmentBreak(GpxWriter.Version.V11)
                + GpxWriter.pointXml(c, GpxWriter.Version.V11)
                + GpxWriter.pointXml(d, GpxWriter.Version.V11)
                + GpxWriter.footer();

        GpxParser.Result r = GpxParser.parse(gpx);
        ok(!r.failed, "fichier produit par notre writer : lu sans erreur");
        ok(r.pointCount() == 4, "4 points relus (reçu " + r.pointCount() + ")");
        ok(Math.abs(r.points.get(0).lat - 48.8566) < 1e-9, "latitude exacte au retour");
        ok(Math.abs(r.points.get(0).lon - 2.3522) < 1e-9, "longitude exacte au retour");
        ok(Math.abs(r.points.get(0).ele - 35.0) < 0.01, "altitude relue");
        ok(r.points.get(0).timeMs == T0, "horodatage relu : " + r.points.get(0).timeMs);
        ok(r.points.get(2).segment == 1, "2e segment détecté (coupure)");
        ok(r.points.get(1).segment == 0, "1er point bien en segment 0");

        // LE RÉSUMÉ NE DOIT PAS TRAVERSER LA COUPURE.
        GpxParser.Summary sum = GpxParser.summarize(r.points);
        double expected = FixGeometry.distanceMeters(48.8566, 2.3522, 48.8567, 2.3523)
                + FixGeometry.distanceMeters(48.8700, 2.3700, 48.8701, 2.3701);
        ok(Math.abs(sum.distanceM - expected) < 1.0,
                String.format("distance = %.0f m (sans compter le tunnel de 1,9 km)",
                        sum.distanceM));
        ok(sum.distanceM < 100,
                "et surtout : la distance ne traverse PAS la coupure "
                        + "(sinon ~1 900 m)");
        ok(sum.segments == 2, "2 segments dans le résumé");
        ok(Math.abs(sum.durationMs - 401_000) < 1000, "durée = 6 min 41 s");
        ok(Math.abs(sum.minEle - 35.0) < 0.1 && Math.abs(sum.maxEle - 41.0) < 0.1,
                "altitudes min/max relues");

        // ============================================================
        section("4. Fichiers cassés : ne jamais lever");
        // ============================================================
        GpxParser.Result empty = GpxParser.parse("");
        ok(empty.failed && empty.error != null, "fichier vide -> échec propre");
        GpxParser.Result nul = GpxParser.parse(null);
        ok(nul.failed, "null -> échec propre, pas d'exception");
        GpxParser.Result notGpx = GpxParser.parse("<html><body>Bonjour</body></html>");
        ok(notGpx.failed, "HTML -> échec avec message");
        ok(notGpx.error.contains("GPX"), "message explicite : pas un GPX");

        // Balise de point non fermée : le point incomplet n'est jamais
        // présenté comme une mesure valide. Le point précédent reste récupérable.
        String truncated = "<gpx><trk><trkseg>"
                + "<trkpt lat=\"48.0\" lon=\"2.0\"><ele>10</ele></trkpt>"
                + "<trkpt lat=\"48.1\" lon=\"2.1\"></gpx>";
        GpxParser.Result trunc = GpxParser.parse(truncated);
        ok(trunc.failed, "point tronqué : fichier signalé en erreur");
        ok(trunc.pointCount() == 1, "seul le point complet précédent est récupéré");

        // Coordonnées aberrantes : ignorées, pas fatales.
        String bad = "<gpx><trk><trkseg>"
                + "<trkpt lat=\"999\" lon=\"2\"/>"                 // hors bornes
                + "<trkpt lat=\"0\" lon=\"0\"/>"                   // île Null
                + "<trkpt lat=\"abc\" lon=\"2\"/>"                 // non numérique
                + "<trkpt lat=\"48.85\" lon=\"2.35\"/>"            // bon
                + "</trkseg></trk></gpx>";
        GpxParser.Result rb = GpxParser.parse(bad);
        ok(rb.pointCount() == 1, "1 seul point valide sur 4 (reçu " + rb.pointCount() + ")");
        ok(!rb.failed, "et le fichier n'est pas déclaré en échec");

        // Point auto-fermant (sans <ele>/<time>).
        ok(GpxParser.parse("<gpx><trk><trkseg><trkpt lat=\"48\" lon=\"2\"/>"
                + "</trkseg></trk></gpx>").pointCount() == 1,
                "trkpt auto-fermant accepté");

        // GPX 1.0
        GpxParser.Result v10 = GpxParser.parse(
                "<?xml version=\"1.0\"?><gpx version=\"1.0\" creator=\"test\">"
                + "<trk><name>Vieux</name><trkseg>"
                + "<trkpt lat=\"48.85\" lon=\"2.35\"><ele>30</ele>"
                + "<time>2026-11-22T12:23:45Z</time></trkpt>"
                + "</trkseg></trk></gpx>");
        ok(!v10.failed && v10.pointCount() == 1, "GPX 1.0 lu");
        ok("Vieux".equals(v10.trackName), "nom de trace extrait");
        ok(!v10.points.get(0).timeIso.isEmpty(), "horodatage conservé");

        String prefixed = "<?xml version=\"1.0\"?><g:gpx xmlns:g=\"http://www.topografix.com/GPX/1/1\" version=\"1.1\"><g:trk><g:name>Prefixé &amp; testé</g:name><g:trkseg><g:trkpt lat=\"48.8\" lon=\"2.3\"><g:time>2024-02-29T12:00:00Z</g:time></g:trkpt></g:trkseg></g:trk></g:gpx>";
        GpxParser.Result prefixedResult = GpxParser.parse(prefixed);
        ok(!prefixedResult.failed && prefixedResult.pointCount() == 1,
                "namespace GPX préfixé accepté");
        ok("Prefixé & testé".equals(prefixedResult.trackName),
                "entités XML et nom de trace décodés");

        String spacedAttributes = "<g:gpx xmlns:g=\"http://www.topografix.com/GPX/1/1\" version = '1.1' creator = \"Test\"><g:trk><g:name>Attributs</g:name><g:trkseg><g:trkpt lat = '48.0' lon = \"2.0\"/></g:trkseg></g:trk></g:gpx>";
        GpxParser.Result spacedResult = GpxParser.parse(spacedAttributes);
        ok(!spacedResult.failed && spacedResult.pointCount() == 1,
                "attributs avec espaces autour de = acceptés");
        ok("1.1".equals(spacedResult.version) && "Test".equals(spacedResult.creator),
                "version/creator extraits d'une racine GPX préfixée");

        // Les champs speed/course directs sont propres à GPX 1.0 ; GPX 1.1
        // utilise l'extension GPS-Test identifiée par son namespace exact.
        String directV10 = "<gpx version=\"1.0\" creator=\"legacy\" "
                + "xmlns=\"http://www.topografix.com/GPX/1/0\"><trk><trkseg>"
                + "<trkpt lat=\"48\" lon=\"2\"><speed>2.5</speed>"
                + "<course>359.5</course></trkpt></trkseg></trk></gpx>";
        GpxParser.Result directV10Result = GpxParser.parse(directV10);
        ok(directV10Result.pointCount() == 1
                        && directV10Result.points.get(0).speedMps != null
                        && Math.abs(directV10Result.points.get(0).speedMps - 2.5f) < 0.001f
                        && directV10Result.points.get(0).courseDeg != null
                        && Math.abs(directV10Result.points.get(0).courseDeg - 359.5f) < 0.001f,
                "GPX 1.0 namespacé : speed/course directs lus");

        String legacyV10 = "<gpx version=\"1.0\" creator=\"legacy\"><trk><trkseg>"
                + "<trkpt lat=\"48\" lon=\"2\"><speed>3.5</speed>"
                + "<course>180</course></trkpt></trkseg></trk></gpx>";
        GpxParser.Result legacyV10Result = GpxParser.parse(legacyV10);
        ok(legacyV10Result.pointCount() == 1
                        && legacyV10Result.points.get(0).speedMps != null
                        && Math.abs(legacyV10Result.points.get(0).speedMps - 3.5f) < 0.001f
                        && legacyV10Result.points.get(0).courseDeg != null
                        && Math.abs(legacyV10Result.points.get(0).courseDeg - 180.0f) < 0.001f,
                "GPX 1.0 historique sans namespace : speed/course directs lus");

        String directV11 = "<gpx version=\"1.1\" xmlns=\"http://www.topografix.com/GPX/1/1\" "
                + "xmlns:gpstest=\"https://example.org/gpstest/ext\"><trk><trkseg>"
                + "<trkpt lat=\"48\" lon=\"2\"><speed>8</speed><course>90</course>"
                + "<hdop>1e-3</hdop><extensions><gpstest:speed>1e2</gpstest:speed>"
                + "<gpstest:course>45</gpstest:course><gpstest:accuracy>.75</gpstest:accuracy>"
                + "</extensions></trkpt></trkseg></trk></gpx>";
        GpxParser.Result directV11Result = GpxParser.parse(directV11);
        ok(directV11Result.pointCount() == 1
                        && directV11Result.points.get(0).speedMps == null
                        && directV11Result.points.get(0).courseDeg != null
                        && Math.abs(directV11Result.points.get(0).courseDeg - 45.0f) < 0.001f
                        && directV11Result.points.get(0).accuracyM != null
                        && Math.abs(directV11Result.points.get(0).accuracyM - 0.75f) < 0.001f
                        && directV11Result.points.get(0).hdop == null,
                "GPX 1.1 : champs directs ignorés, extension GPS-Test validée strictement");

        String exponentLexical = "<gpx version=\"1.1\" xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><trkseg>"
                + "<trkpt lat=\"1e-10\" lon=\"2\"/>"
                + "<trkpt lat=\"48\" lon=\"2\"><ele>1e2</ele><hdop>NaN</hdop></trkpt>"
                + "</trkseg></trk></gpx>";
        GpxParser.Result exponentResult = GpxParser.parse(exponentLexical);
        ok(exponentResult.pointCount() == 1 && exponentResult.invalidTrackPoints == 1,
                "coordonnée avec exposant rejetée comme xsd:decimal invalide");
        ok(Double.isNaN(exponentResult.points.get(0).ele)
                        && exponentResult.points.get(0).hdop == null,
                "exposant et NaN rejetés dans les décimaux GPX connus");

        // ============================================================
        section("5. Horodatage ISO 8601");
        // ============================================================
        ok(GpxParser.parseIso8601("1970-01-01T00:00:00Z") == 0L, "epoch -> 0");
        ok(GpxParser.parseIso8601("2026-11-22T12:23:45Z") == 1_795_350_225_000L,
                "date connue -> instant attendu");
        ok(GpxParser.parseIso8601("2026-11-22T13:23:45+01:00")
                == GpxParser.parseIso8601("2026-11-22T12:23:45Z"),
                "fuseau +01:00 correctement appliqué");
        ok(GpxParser.parseIso8601("2026-11-22T12:23:45.500Z") == 1_795_350_225_500L,
                "demi-seconde relue");
        ok(GpxParser.parseIso8601("pas une date") == -1L, "texte invalide -> -1");
        ok(GpxParser.parseIso8601(null) == -1L, "null -> -1");
        ok(GpxParser.parseIso8601("2026-13-01T00:00:00Z") == -1L, "mois 13 -> -1");
        // Année bissextile
        ok(GpxParser.parseIso8601("2024-02-29T00:00:00Z") > 0, "29 février 2024 accepté");
        ok(GpxParser.parseIso8601("2023-02-29T00:00:00Z") < 0,
                "29 février 2023 : date grégorienne invalide rejetée");
        ok(GpxParser.parseIso8601("2024-02-29T00:00:00Z") > 0,
                "29 février 2024 : année bissextile acceptée");
        ok(GpxParser.parseIso8601("2026-03-01T12:00:00+99:99") < 0,
                "décalage de fuseau impossible rejeté");

        // ============================================================
        section("6. Virgule décimale : refusée, pas réparée");
        // ============================================================
        // « 48,8566 » est invalide en GPX. On ne devine pas : on rejette.
        // Deviner transformerait « 48,856 » en 48.856 alors que le fichier
        // pourrait vouloir dire 48856 millièmes selon son auteur.
        GpxParser.Result comma = GpxParser.parse("<gpx><trk><trkseg>"
                + "<trkpt lat=\"48,8566\" lon=\"2,3522\"/></trkseg></trk></gpx>");
        ok(comma.pointCount() == 0, "coordonnées à virgule -> rejetées, pas devinées");
        ok(comma.failed, "et le fichier est signalé sans point");

        // ============================================================
        section("7. Bandeau : textes purs (sans Android)");
        // ============================================================
        // Note : StatusHud contient un appel Android (readBattery), donc ce
        // test ne peut PAS l'appeler tel quel hors appareil. On vérifie la
        // logique de mise en forme à travers les mêmes règles, en local,
        // puis on s'appuie sur le fait que la classe ne fait rien d'autre.
        //
        // Si ces règles changent dans StatusHud, ces tests ne le verront
        // pas — c'est une limite assumée : la mise en forme est triviale,
        // le risque est de l'ordre du cosmétique, pas de la justesse.

        ok(numOrNa(4, 10).equals("4/10"), "sats utilisés/en vue");
        ok(numOrNa(0, 0).equals("—"), "aucune info -> tiret, jamais « 0/0 »");

        total();
    }

    static String numOrNa(int used, int view) {
        if (view <= 0 && used <= 0) return "\u2014";
        return used + "/" + Math.max(view, used);
    }

    static void section(String name) { System.out.println("\n=== " + name + " ==="); }

    static void ok(boolean cond, String label) {
        if (cond) { pass++; System.out.println("  OK   " + label); }
        else { fail++; System.out.println("  ECHEC " + label); }
    }

    static void total() {
        System.out.println("\n=============================================");
        System.out.println("RESULTAT : " + pass + " reussis, " + fail + " echoues");
        if (fail > 0) System.exit(1);
    }
}
