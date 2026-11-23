package com.example.gpstest;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests des modes et du post-traitement (v5.11) — sans Android.
 */
public class ModesAndPostProcessTest {

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {

        // ============================================================
        section("1. Modes prédéfinis");
        // ============================================================
        ok(TrackMode.Mode.WALK.distanceM == 20 && TrackMode.Mode.WALK.timeMs == 10_000,
                "marche : 20 m / 10 s");
        ok(TrackMode.Mode.BIKE.distanceM == 25 && TrackMode.Mode.BIKE.timeMs == 15_000,
                "vélo : 25 m / 15 s");
        ok(TrackMode.Mode.CAR.distanceM == 50 && TrackMode.Mode.CAR.timeMs == 30_000,
                "voiture : 50 m / 30 s");
        ok(TrackMode.DISPLAY_ORDER.length == 4, "4 modes proposés");
        ok(TrackMode.DISPLAY_ORDER[3] == TrackMode.Mode.CUSTOM, "personnalisé en dernier");
        ok(TrackMode.fromId("BIKE", TrackMode.Mode.WALK) == TrackMode.Mode.BIKE,
                "identifiant relu");
        ok(TrackMode.fromId("n'importe quoi", TrackMode.Mode.WALK) == TrackMode.Mode.WALK,
                "identifiant inconnu -> repli, jamais d'exception");
        ok(TrackMode.fromId(null, TrackMode.Mode.CAR) == TrackMode.Mode.CAR, "null -> repli");

        // ============================================================
        section("2. Seuil de précision : la correction");
        // ============================================================
        // Calcul brut de la proposition initiale :
        ok(0.4 * 20 == 8.0, "marche : 0.4 × 20 m = 8 m (seuil brut)");
        ok(0.4 * 25 == 10.0, "vélo : 0.4 × 25 m = 10 m");
        ok(0.4 * 50 == 20.0, "voiture : 0.4 × 50 m = 20 m");
        // Avec plancher : les modes lents ne descendent pas sous 8 m.
        ok(TrackMode.accuracyLimitFor(20) == 8f, "marche : plancher 8 m appliqué");
        ok(TrackMode.accuracyLimitFor(25) == 10f, "vélo : 10 m (au-dessus du plancher)");
        ok(TrackMode.accuracyLimitFor(50) == 20f, "voiture : 20 m");
        // Le plafond ne doit jamais dépasser le seuil global de FixQuality.
        ok(TrackMode.accuracyLimitFor(1000) == TrackMode.ACCURACY_CEIL_M,
                "1 km -> plafonné à 50 m (le seuil global)");
        // Et 4 m de précision est TOUJOURS accepté, quel que soit le mode.
        for (TrackMode.Mode m : TrackMode.DISPLAY_ORDER) {
            TrackMode.Effective e = TrackMode.resolve(m, 20, 10_000);
            ok(TrackMode.rejectReason(4f, 1.4f, e) == null,
                    "précision 4 m acceptée en mode " + m.name());
        }
        // 12 m : accepté en marche (plancher 8 m)... non, 12 > 8 -> rejeté.
        TrackMode.Effective walk = TrackMode.resolve(TrackMode.Mode.WALK, 0, 0);
        ok(TrackMode.rejectReason(12f, 1.4f, walk) != null,
                "marche : 12 m de précision -> rejeté (seuil 8 m)");
        ok(TrackMode.rejectReason(7f, 1.4f, walk) == null,
                "marche : 7 m -> accepté");
        // La proposition INITIALE (0.4 × 20 = 8 m sans plancher) donnait le
        // même résultat pour la marche. C'est sur le mode lointain qu'elle
        // devenait absurde : avec 10 m de distance, 0.4 × 10 = 4 m.
        ok(TrackMode.accuracyLimitFor(10) == 8f,
                "si la distance était de 10 m, la proposition initiale exigeait "
                        + "4 m — le plancher de 8 m l'évite");

        // ============================================================
        section("3. Arrêt : on ne suspend pas la trace");
        // ============================================================
        ok(TrackMode.isStationary(0.0f), "vitesse 0 -> à l'arrêt");
        ok(TrackMode.isStationary(0.2f), "0,2 m/s (0,7 km/h) -> à l'arrêt");
        ok(!TrackMode.isStationary(0.5f), "0,5 m/s (1,8 km/h) -> en marche");
        ok(!TrackMode.isStationary(1.4f), "1,4 m/s (5 km/h) -> marche");
        ok(!TrackMode.isStationary(Float.NaN),
                "vitesse INCONNUE -> on ne conclut pas « à l'arrêt » "
                        + "(sinon on supprimerait tout sur un appareil sans vitesse)");

        TrackMode.Effective e10 = TrackMode.resolve(TrackMode.Mode.WALK, 0, 0);
        ok(TrackMode.writeAnchorWhenStationary(0f, true, 5_000, e10) == false,
                "immobile depuis 5 s : pas encore d'ancre (seuil 10 s)");
        ok(TrackMode.writeAnchorWhenStationary(0f, true, 10_000, e10),
                "immobile depuis 10 s : ANCRE écrite");
        ok(TrackMode.writeAnchorWhenStationary(0f, false, 0, e10),
                "premier point à l'arrêt : toujours écrit");
        ok(!TrackMode.writeAnchorWhenStationary(1.4f, true, 60_000, e10),
                "en marche : ce n'est pas la règle de l'ancre qui s'applique");

        // ============================================================
        section("4. Post-traitement : dédoublonnage");
        // ============================================================
        List<TrackPostProcess.P> dup = new ArrayList<>();
        dup.add(pt(48.0, 2.0, 0));
        dup.add(pt(48.0, 2.0, 1));       // identique
        dup.add(pt(48.0, 2.0, 2));       // identique
        dup.add(pt(48.0001, 2.0, 3));
        TrackPostProcess.Report r1 = new TrackPostProcess.Report();
        List<TrackPostProcess.P> d = TrackPostProcess.removeDuplicates(dup, r1);
        ok(d.size() == 2, "3 points identiques -> 1 seul");
        ok(r1.duplicatesRemoved == 2, "2 doublons comptés");

        // ============================================================
        section("5. Post-traitement : points aberrants");
        // ============================================================
        List<TrackPostProcess.P> out = new ArrayList<>();
        out.add(pt(48.0, 2.0, 0));
        out.add(pt(48.0001, 2.0, 10_000));      // 11 m en 10 s : normal
        out.add(pt(48.5, 2.0, 11_000));         // 55 km en 1 s : ABERRANT
        out.add(pt(48.0002, 2.0, 20_000));      // 11 m en 10 s : normal
        TrackPostProcess.Report r2 = new TrackPostProcess.Report();
        List<TrackPostProcess.P> o = TrackPostProcess.removeOutliers(out, 15.0 / 3.6, r2);
        ok(o.size() == 3, "1 point aberrant retiré sur 4");
        ok(r2.outliersRemoved == 1, "compteur d'aberrants");

        // LE PIÈGE : deux points faux d'affilée ne doivent pas faire rejeter
        // le bon point suivant. On compare toujours au dernier CONSERVÉ.
        List<TrackPostProcess.P> cascade = new ArrayList<>();
        cascade.add(pt(48.0, 2.0, 0));
        cascade.add(pt(48.4, 2.0, 1_000));      // aberrant
        cascade.add(pt(48.0, 2.0, 2_000));      // revient à la position réelle
        TrackPostProcess.Report r3 = new TrackPostProcess.Report();
        List<TrackPostProcess.P> c = TrackPostProcess.removeOutliers(
                cascade, 15.0 / 3.6, r3);
        ok(c.size() == 2, "l'aberrant est retiré, le retour à la normale est GARDÉ");
        ok(r3.outliersRemoved == 1, "1 seul retiré (pas de contagion)");

        // LE BRUIT DE POSITION N'EST PAS UN ABERRANT (v5.12).
        // 4,2 m en 1 s = 15 km/h : au-dessus du plafond marche d'avant.
        // C'était du bruit, pas un saut. Neuf points légitimes étaient
        // supprimés sur une trace de 300.
        List<TrackPostProcess.P> bruit = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            bruit.add(pt(48.0 + i * 0.000036 + (i % 2 == 0 ? 0.00002 : 0.0), 2.0, i * 1000L));
        }
        TrackPostProcess.Report rb = new TrackPostProcess.Report();
        List<TrackPostProcess.P> bruitOut =
                TrackPostProcess.removeOutliers(bruit, 25.0 / 3.6, rb);
        ok(rb.outliersRemoved == 0,
                "oscillation de ±2 m : 0 point retiré (retirés : " + rb.outliersRemoved + ")");

        // Un point sans horodatage ne doit JAMAIS être déclaré aberrant.
        List<TrackPostProcess.P> noTime = new ArrayList<>();
        noTime.add(new TrackPostProcess.P(48.0, 2.0, Double.NaN, -1, 0));
        noTime.add(new TrackPostProcess.P(49.0, 2.0, Double.NaN, -1, 0));
        ok(TrackPostProcess.removeOutliers(noTime, 15.0 / 3.6,
                new TrackPostProcess.Report()).size() == 2,
                "sans horodatage : on ne juge pas, on conserve");

        // Les points de part et d'autre d'une coupure ne sont jamais comparés.
        List<TrackPostProcess.P> across = new ArrayList<>();
        across.add(new TrackPostProcess.P(48.0, 2.0, Double.NaN, 0, 0));
        across.add(new TrackPostProcess.P(48.5, 2.0, Double.NaN, 1000, 1));  // seg 1
        ok(TrackPostProcess.removeOutliers(across, 15.0 / 3.6,
                new TrackPostProcess.Report()).size() == 2,
                "à travers une coupure : jamais comparé");

        // ============================================================
        section("6. Distance point-segment : isotrope");
        // ============================================================
        // Un point à 3 m au nord d'un segment horizontal.
        TrackPostProcess.P a = pt(48.0, 2.0, 0);
        TrackPostProcess.P b = pt(48.0, 2.001, 0);   // segment est-ouest
        double dNorth = TrackPostProcess.pointToSegmentMeters(
                pt(48.0 + 3.0 / 111_132.0, 2.0005, 0), a, b);
        ok(Math.abs(dNorth - 3.0) < 0.2,
                String.format("3 m au nord -> %.2f m", dNorth));
        // Le même écart de 3 m, mais vers l'EST (perpendiculaire à un
        // segment nord-sud) : le résultat doit être identique.
        TrackPostProcess.P pNord = pt(48.0, 2.0, 0);
        TrackPostProcess.P pSud = pt(48.001, 2.0, 0);  // segment nord-sud
        double dEast = TrackPostProcess.pointToSegmentMeters(
                pt(48.0005, 2.0 + 3.0 / (111_320.0 * Math.cos(Math.toRadians(48.0))), 0),
                pNord, pSud);
        ok(Math.abs(dEast - 3.0) < 0.2, String.format("3 m à l'est -> %.2f m", dEast));
        ok(Math.abs(dNorth - dEast) < 0.2,
                "isotrope : même distance mesurée dans les deux directions "
                        + "(un calcul en degrés donnerait 3 m vs 4,5 m)");

        // ============================================================
        section("7. Douglas-Peucker");
        // ============================================================
        // Une ligne droite de 10 points : seuls les extrémités restent.
        List<TrackPostProcess.P> line = new ArrayList<>();
        for (int i = 0; i < 10; i++) line.add(pt(48.0 + i * 0.0001, 2.0, i * 1000L));
        TrackPostProcess.Report rl = new TrackPostProcess.Report();
        List<TrackPostProcess.P> sl = TrackPostProcess.simplify(line, 3.0, rl);
        ok(sl.size() == 2, "ligne droite : 2 points (reçu " + sl.size() + ")");
        ok(rl.simplifiedAway == 8, "8 points retirés");

        // Un virage net de 90° doit être CONSERVÉ.
        List<TrackPostProcess.P> corner = new ArrayList<>();
        for (int i = 0; i < 5; i++) corner.add(pt(48.0, 2.0 + i * 0.0002, i * 1000L));
        for (int i = 1; i < 5; i++) corner.add(pt(48.0 + i * 0.0002, 2.0008, (4 + i) * 1000L));
        List<TrackPostProcess.P> sc = TrackPostProcess.simplify(corner, 3.0,
                new TrackPostProcess.Report());
        ok(sc.size() >= 3, "virage à 90° : le sommet est conservé (" + sc.size() + " points)");
        boolean hasCorner = false;
        for (TrackPostProcess.P p : sc) {
            if (Math.abs(p.lat - 48.0) < 1e-9 && Math.abs(p.lon - 2.0008) < 1e-9) {
                hasCorner = true;
            }
        }
        ok(hasCorner, "le sommet exact est dans le résultat");

        // Tolérance nulle : rien n'est simplifié.
        List<TrackPostProcess.P> untouched = TrackPostProcess.simplify(line, 0.0,
                new TrackPostProcess.Report());
        ok(untouched.size() == 10, "tolérance 0 -> trace intacte");

        // Les segments ne sont jamais fusionnés.
        List<TrackPostProcess.P> twoSeg = new ArrayList<>();
        for (int i = 0; i < 5; i++)
            twoSeg.add(new TrackPostProcess.P(48.0, 2.0 + i * 0.0002, Double.NaN,
                    i * 1000L, 0));
        for (int i = 0; i < 5; i++)
            twoSeg.add(new TrackPostProcess.P(48.5, 2.0 + i * 0.0002, Double.NaN,
                    (5 + i) * 1000L, 1));
        List<TrackPostProcess.P> s2 = TrackPostProcess.simplify(twoSeg, 3.0,
                new TrackPostProcess.Report());
        ok(s2.size() == 4, "2 segments droits -> 4 points (2 par segment)");
        int seg0 = 0, seg1 = 0;
        for (TrackPostProcess.P p : s2) { if (p.segment == 0) seg0++; else seg1++; }
        ok(seg0 == 2 && seg1 == 2, "chacun a gardé ses extrémités");

        // Grande trace bruitée : le traitement itératif ne consomme pas la
        // pile d'appels Java comme le ferait une récursion profonde.
        List<TrackPostProcess.P> dense = new ArrayList<>();
        for (int i = 0; i < 2500; i++) {
            double offset = (i % 2 == 0 ? 0.00008 : -0.00008);
            dense.add(new TrackPostProcess.P(48.0 + i * 0.000001 + offset,
                    2.0 + i * 0.000001, Double.NaN, i * 1000L, 0));
        }
        List<TrackPostProcess.P> denseOut = TrackPostProcess.simplify(dense, 0.1,
                new TrackPostProcess.Report());
        ok(denseOut.size() >= 2 && denseOut.size() <= dense.size(),
                "grande trace bruitée traitée sans erreur ni résultat hors limites");

        // ============================================================
        section("8. Pipeline complet : la trace reste fidèle");
        // ============================================================
        // Trace réaliste : 200 points sur une ligne presque droite, avec du
        // bruit de ±2 m et deux points aberrants.
        List<TrackPostProcess.P> real = new ArrayList<>();
        java.util.Random rnd = new java.util.Random(42);
        double lat = 48.0, lon = 2.0;
        for (int i = 0; i < 200; i++) {
            lat += 0.00003;                                 // ~3,3 m
            lon += (rnd.nextDouble() - 0.5) * 0.00004;      // bruit ±2 m
            real.add(new TrackPostProcess.P(lat, lon, Double.NaN, i * 1000L, 0));
        }
        // Deux points aberrants, insérés avec des horodatages MONOTONES.
        // (Un vrai relevé est toujours chronologique : notre writer le
        // garantit. Insérer un point daté d'avant le précédent ne teste rien,
        // puisque le filtre refuse — à raison — de juger sur un temps négatif.)
        real.set(45, new TrackPostProcess.P(48.4, 2.0, Double.NaN, 45_000L, 0));
        real.set(120, new TrackPostProcess.P(47.6, 2.0, Double.NaN, 120_000L, 0));

        double distBefore = rawDistance(real);
        TrackPostProcess.Report rp = new TrackPostProcess.Report();
        List<TrackPostProcess.P> cleaned = TrackPostProcess.clean(
                real, 3.0, 15.0 / 3.6, rp);
        double distAfter = rawDistance(cleaned);

        ok(rp.inputPoints == 200, "200 points en entrée (" + rp.inputPoints + ")");
        ok(rp.outliersRemoved >= 1, rp.outliersRemoved + " aberrant(s) retiré(s)");
        ok(rp.outputPoints < rp.inputPoints / 2,
                "réduction forte : " + rp.outputPoints + " points");
        // LA DISTANCE NE DOIT PAS ÊTRE DÉTRUITE.
        ok(distAfter <= distBefore,
                String.format("distance non gonflée : %.0f m -> %.0f m",
                        distBefore, distAfter));
        ok(distAfter > 500,
                String.format("mais la trace reste réelle : %.0f m conservés", distAfter));
        ok(rp.removedTotal() == 200 - rp.outputPoints,
                "le rapport est cohérent (" + rp.removedTotal() + " = 200 - " + rp.outputPoints + ")");
        ok(rp.describe().contains("→"), "le rapport est lisible : " + rp.describe());

        // Sans filtre de vitesse, les aberrants DÉTRUISENT la distance.
        TrackPostProcess.Report rNoSpeed = new TrackPostProcess.Report();
        List<TrackPostProcess.P> noSpeed = TrackPostProcess.clean(real, 3.0,
                Double.NaN, rNoSpeed);
        ok(noSpeed.size() > 0, "sans filtre de vitesse, le nettoyage ne plante pas");
        ok(rNoSpeed.outliersRemoved == 0, "et ne prétend pas avoir retiré des aberrants");


        // ============================================================
        section("9. Nommage des fichiers : l'original est intouchable");
        // ============================================================
        java.io.File orig = new java.io.File("/tmp/Trace_2026_11_22_12h23m.GPX");
        java.io.File clean = TrackCleaner.siblingName(orig);
        ok(clean.getName().equals("Trace_2026_11_22_12h23m_nettoye.GPX"),
                "Trace_X.GPX -> Trace_X_nettoye.GPX (" + clean.getName() + ")");
        ok(!clean.getName().equals(orig.getName()),
                "le nom nettoyé n'est JAMAIS celui de l'original");
        ok(clean.getParentFile().equals(orig.getParentFile()),
                "même répertoire : la copie est à côté, pas ailleurs");
        // Sans extension : on ajoute quand même, sans écraser le nom.
        ok(TrackCleaner.siblingName(new java.io.File("/tmp/trace"))
                .getName().equals("trace_nettoye.gpx"), "sans extension");
        // Un nom qui contient déjà des points ne doit pas être tronqué au
        // mauvais endroit.
        ok(TrackCleaner.siblingName(new java.io.File("/tmp/a.b.c.GPX"))
                .getName().equals("a.b.c_nettoye.GPX"), "points dans le nom");

        // ============================================================
        section("10. Rien n'est écrit si le résultat serait vide");
        // ============================================================
        // Un GPX de 2 points identiques : le dédoublonnage n'en laisse qu'un.
        // Écrire un fichier « nettoyé » à 1 point serait pire que de ne rien
        // écrire : l'utilisateur croirait avoir perdu sa trace.
        java.io.File f2 = java.io.File.createTempFile("deux", ".GPX");
        try (java.io.Writer w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(f2), "UTF-8")) {
            w.write(GpxWriter.header(GpxWriter.Version.V11, "t", 1_766_000_000_000L));
            GpxWriter.TrackPoint p =
                    new GpxWriter.TrackPoint(1_766_000_000_000L, 48.0, 2.0, 10);
            w.write(GpxWriter.pointXml(p, GpxWriter.Version.V11));
            w.write(GpxWriter.pointXml(p, GpxWriter.Version.V11));
            w.write(GpxWriter.footer());
        }
        TrackCleaner.Outcome empty = TrackCleaner.clean(f2, 3.0, 15.0 / 3.6,
                GpxWriter.Version.V11, "t");
        ok(!empty.success, "résultat à 1 point : refusé");
        ok(empty.file == null, "aucun fichier écrit");
        ok(!TrackCleaner.siblingName(f2).exists(),
                "aucun _nettoye.GPX laissé derrière");

        // Un fichier inexistant : message clair, pas d'exception.
        TrackCleaner.Outcome missing = TrackCleaner.clean(
                new java.io.File("/tmp/nexiste_pas_du_tout.GPX"),
                3.0, 15.0 / 3.6, GpxWriter.Version.V11, "t");
        ok(!missing.success && missing.message.contains("introuvable"),
                "fichier absent : « " + missing.message + " »");

        // ============================================================
        section("11. Aller-retour complet : nettoyé puis relu");
        // ============================================================
        java.io.File f3 = java.io.File.createTempFile("aller_retour", ".GPX");
        long t0 = 1_766_000_000_000L;
        try (java.io.Writer w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(f3), "UTF-8")) {
            w.write(GpxWriter.header(GpxWriter.Version.V11, "AR", t0));
            double latA = 48.85;
            java.util.Random rr = new java.util.Random(11);
            for (int i = 0; i < 120; i++) {
                latA += 0.00003;
                double lonA = 2.35 + (rr.nextDouble() - 0.5) * 0.00003;
                w.write(GpxWriter.pointXml(new GpxWriter.TrackPoint(
                        t0 + i * 1000L, latA, lonA, 35.0), GpxWriter.Version.V11));
            }
            w.write(GpxWriter.footer());
        }
        TrackCleaner.Outcome ar = TrackCleaner.clean(f3, 3.0, 15.0 / 3.6,
                GpxWriter.Version.V11, "AR");
        ok(ar.success, "nettoyage réussi");
        ok(ar.file.exists(), "fichier écrit : " + (ar.file != null ? ar.file.getName() : "—"));
        String xml = new String(java.nio.file.Files.readAllBytes(ar.file.toPath()), "UTF-8");
        GpxParser.Result back = GpxParser.parse(xml);
        ok(!back.failed, "le nettoyé est un GPX valide");
        ok(back.points.size() >= 2, "il contient des points");
        // L'HORODATAGE EST CELUI D'ORIGINE, pas un faux 1 Hz reconstruit.
        boolean timesPreserved = true;
        for (GpxParser.Point p : back.points) {
            if (p.timeMs < t0 || p.timeMs > t0 + 120_000L) timesPreserved = false;
        }
        ok(timesPreserved, "les horodatages restent dans la plage d'origine");
        // Et la distance mesurée reste dans le bon ordre de grandeur.
        GpxParser.Summary sum = GpxParser.summarize(back.points);
        ok(sum.distanceM > 300 && sum.distanceM < 420,
                String.format("distance conservée : %.0f m (attendu ~400 m)", sum.distanceM));
        // L'original n'a pas bougé d'un octet.
        ok(f3.length() > 0 && f3.exists(), "le fichier d'origine existe toujours");
        GpxParser.Result origParsed = GpxParser.parse(
                new String(java.nio.file.Files.readAllBytes(f3.toPath()), "UTF-8"));
        ok(origParsed.points.size() == 120, "l'original garde ses 120 points");


        // ============================================================
        section("12. ISO 8601 : calcul manuel contre reference (v5.13)");
        // ============================================================
        // Ce format est appele UNE FOIS PAR POINT. On le compare a
        // SimpleDateFormat, qui fait reference, sur 8 ans et sur les cas
        // limites de calendrier.
        java.text.SimpleDateFormat ref =
                new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.ROOT);
        ref.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        long[] bornes = { 0L, 1L, 999L, 1000L,
                951782400000L,      // 2000-02-29 : bissextile de siecle
                4102444800000L,     // 2100-01-01 : NON bissextile
                1709164800000L,     // 2024-02-29
                253402300799000L }; // 9999-12-31T23:59:59Z
        boolean isoOk = true;
        for (long t : bornes) {
            if (!GpxWriter.iso8601(t).equals(ref.format(new java.util.Date(t)))) isoOk = false;
        }
        ok(isoOk, "8 bornes de calendrier identiques a SimpleDateFormat");
        boolean balayage = true;
        long base = 1_600_000_000_000L;
        for (int i = 0; i < 20000; i++) {
            long t = base + i * 3_607_000L;
            if (!GpxWriter.iso8601(t).equals(ref.format(new java.util.Date(t)))) {
                balayage = false;
                break;
            }
        }
        ok(balayage, "20 000 instants sur 2 ans : identiques");
        ok(GpxWriter.iso8601(0L).equals("1970-01-01T00:00:00Z"), "epoque Unix exacte");

        // ============================================================
        section("13. Unites : jamais de valeur reelle affichee comme nulle");
        // ============================================================
        ok(Units.formatDistance(Units.System.METRIC, 20).equals("20 m"),
                "20 m s'affiche « 20 m », pas « 0,0 km » : "
                        + Units.formatDistance(Units.System.METRIC, 20));
        ok(Units.formatDistance(Units.System.METRIC, 999).equals("999 m"), "999 m");
        ok(Units.formatDistance(Units.System.METRIC, 1000).equals("1,0 km"),
                "1000 m -> 1,0 km");
        ok(Units.formatDistance(Units.System.METRIC, 12345).equals("12 km"), "12 km");
        ok(Units.formatDistance(Units.System.METRIC, 0).equals("0 m"), "zero reste zero");
        ok(Units.formatDistance(Units.System.IMPERIAL, 100).equals("328 ft"),
                "100 m en pieds : " + Units.formatDistance(Units.System.IMPERIAL, 100));
        ok(Units.formatDistance(Units.System.METRIC, Double.NaN).equals("\u2014"),
                "distance inconnue : tiret cadratin");
        // Le bug d'origine : 5 m affichait « 0,0 km ».
        ok(!Units.formatDistance(Units.System.METRIC, 5).startsWith("0"),
                "5 m ne s'affiche plus comme zero : "
                        + Units.formatDistance(Units.System.METRIC, 5));

        total();
    }

    static TrackPostProcess.P pt(double lat, double lon, long t) {
        return new TrackPostProcess.P(lat, lon, Double.NaN, t, 0);
    }

    static double rawDistance(List<TrackPostProcess.P> pts) {
        double d = 0;
        for (int i = 1; i < pts.size(); i++) {
            d += FixGeometry.distanceMeters(pts.get(i - 1).lat, pts.get(i - 1).lon,
                    pts.get(i).lat, pts.get(i).lon);
        }
        return d;
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
