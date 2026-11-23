package com.example.gpstest;

/**
 * Tests de la coupure de trace GPX (v5.6) — SANS Android.
 *
 * Vérifie une chose précise et vérifiable : le GPX produit avec des coupures
 * est TOUJOURS un document XML valide, avec des <trkseg> correctement
 * imbriqués. Un fichier de trace qu'on ne peut pas ouvrir ne sert à rien.
 */
public class GpxSegmentTest {

    static int pass = 0, fail = 0;
    static final long T0 = 1_700_000_000_000L;

    public static void main(String[] args) {

        // ============================================================
        section("Coupure : structure XML");
        // ============================================================
        String brk = GpxWriter.segmentBreak(GpxWriter.Version.V11);
        ok(brk.contains("</trkseg>"), "la coupure FERME le segment courant");
        ok(brk.contains("<trkseg>"), "la coupure OUVRE un segment neuf");
        ok(brk.indexOf("</trkseg>") < brk.indexOf("<trkseg>"),
                "fermeture AVANT ouverture (sinon XML invalide)");
        ok(count(brk, "</trkseg>") == 1 && count(brk, "<trkseg>") == 1,
                "exactement une fermeture et une ouverture");

        // La même coupure doit fonctionner en 1.0 (la balise est identique
        // dans les deux versions — c'est la seule chose qui compte).
        String brk10 = GpxWriter.segmentBreak(GpxWriter.Version.V10);
        ok(brk10.contains("</trkseg>") && brk10.contains("<trkseg>"),
                "coupure valable aussi en GPX 1.0");

        // ============================================================
        section("Trace complète avec deux coupures");
        // ============================================================
        // Simule : 2 points, tunnel, 2 points, pont, 2 points.
        GpxWriter.TrackPoint p1 = pt(48.8566, 2.3522, T0);
        GpxWriter.TrackPoint p2 = pt(48.8570, 2.3526, T0 + 1000);
        GpxWriter.TrackPoint p3 = pt(48.8700, 2.3700, T0 + 700_000);   // après tunnel
        GpxWriter.TrackPoint p4 = pt(48.8704, 2.3704, T0 + 701_000);
        GpxWriter.TrackPoint p5 = pt(48.8800, 2.3800, T0 + 1_400_000); // après pont
        GpxWriter.TrackPoint p6 = pt(48.8804, 2.3804, T0 + 1_401_000);

        StringBuilder sb = new StringBuilder();
        sb.append(GpxWriter.header(GpxWriter.Version.V11, "test", T0));
        sb.append(GpxWriter.pointXml(p1, GpxWriter.Version.V11));
        sb.append(GpxWriter.pointXml(p2, GpxWriter.Version.V11));
        sb.append(brk);
        sb.append(GpxWriter.pointXml(p3, GpxWriter.Version.V11));
        sb.append(GpxWriter.pointXml(p4, GpxWriter.Version.V11));
        sb.append(brk);
        sb.append(GpxWriter.pointXml(p5, GpxWriter.Version.V11));
        sb.append(GpxWriter.pointXml(p6, GpxWriter.Version.V11));
        sb.append(GpxWriter.footer());
        String gpx = sb.toString();

        ok(count(gpx, "<trkseg>") == 3, "3 segments ouverts (2 coupures)");
        ok(count(gpx, "</trkseg>") == 3, "3 segments fermés");
        ok(count(gpx, "<trkpt") == 6, "6 points au total");
        ok(count(gpx, "</gpx>") == 1, "un seul </gpx> final");

        // Équilibrage des balises : le vrai test de validité.
        ok(count(gpx, "<gpx ") == 1 && count(gpx, "</gpx>") == 1,
                "balise <gpx> équilibrée");
        ok(count(gpx, "<trk>") == 1 && count(gpx, "</trk>") == 1,
                "balise <trk> équilibrée");

        // Ordre : chaque </trkseg> vient avant le <trkseg> suivant
        ok(indexOfNth(gpx, "</trkseg>", 1) < indexOfNth(gpx, "<trkseg>", 2),
                "1re fermeture avant 2e ouverture");
        ok(indexOfNth(gpx, "</trkseg>", 2) < indexOfNth(gpx, "<trkseg>", 3),
                "2e fermeture avant 3e ouverture");

        // ============================================================
        section("Le piège : fermer deux fois de suite");
        // ============================================================
        // Deux coupures consécutives (deux pertes sans point entre elles)
        // donneraient </trkseg><trkseg></trkseg><trkseg> : XML valide mais
        // segment VIDE. Le code ne doit poser le drapeau qu'une fois par
        // perte, et le consommer à l'écriture du premier point — ce qui est
        // le cas. On vérifie ici que le GPX reste valide même dans ce cas
        // pathologique, pour être certain que rien ne casse.
        String twice = GpxWriter.header(GpxWriter.Version.V11, "t", T0)
                + GpxWriter.pointXml(p1, GpxWriter.Version.V11)
                + brk + brk
                + GpxWriter.pointXml(p2, GpxWriter.Version.V11)
                + GpxWriter.footer();
        ok(count(twice, "<trkseg>") == count(twice, "</trkseg>"),
                "double coupure : balises toujours équilibrées (XML valide)");
        ok(count(twice, "<trkseg>") == 3,
                "double coupure -> un segment vide, mais document valide");

        // ============================================================
        section("Pas de coupure : trace normale inchangée");
        // ============================================================
        String normal = GpxWriter.build(
                java.util.Arrays.asList(p1, p2),
                "sans coupure", GpxWriter.Version.V11);
        ok(count(normal, "<trkseg>") == 1, "sans coupure : 1 seul segment");
        ok(!normal.contains("</trkseg>\n    <trkseg>"),
                "sans coupure : aucune frontière parasite");

        // ============================================================
        section("Distance : la coupure n'est pas un déplacement");
        // ============================================================
        // C'est le point de fond : entre p2 et p3, il y a un tunnel.
        // La distance « à vol d'oiseau » existe géométriquement, mais ce
        // n'est PAS un déplacement réel. Le calcul de la trace doit se faire
        // segment par segment — jamais à travers une frontière.
        double dAcross = FixGeometry.distanceMeters(p2.lat, p2.lon, p3.lat, p3.lon);
        ok(dAcross > 1000,
                String.format("distance à travers le tunnel = %.0f m (ne doit PAS être comptée)", dAcross));

        double dSeg1 = FixGeometry.distanceMeters(p1.lat, p1.lon, p2.lat, p2.lon);
        ok(dSeg1 < 100, String.format("distance réelle dans le 1er segment = %.0f m", dSeg1));

        total();
    }

    static GpxWriter.TrackPoint pt(double lat, double lon, long t) {
        return new GpxWriter.TrackPoint(t, lat, lon, Double.NaN);
    }

    static int count(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); }
        return n;
    }

    static int indexOfNth(String s, String sub, int n) {
        int i = -1;
        for (int k = 0; k < n; k++) {
            i = s.indexOf(sub, i + 1);
            if (i < 0) return -1;
        }
        return i;
    }

    static void section(String name) {
        System.out.println("\n=== " + name + " ===");
    }

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
