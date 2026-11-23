package com.example.gpstest;

import java.util.List;

public final class MapsforgeBoundaryCatalogParserTest {
    private static int ok, ko;
    private static void check(String label, boolean value) {
        if (value) { ok++; System.out.println("  OK   " + label); }
        else { ko++; System.out.println("  FAIL " + label); }
    }
    public static void main(String[] args) {
        String html = "<html><table>"
                + "<tr><td><a href=\"../\">Parent Directory</a></td></tr>"
                + "<tr><td><a href=\"ile-de-france.poly\">ile-de-france.poly</a></td><td>2026-11-20 12:00</td><td>24K</td></tr>"
                + "<tr><td><a href=\"bretagne.poly\">bretagne.poly</a></td><td>2026-11-18 08:00</td><td>11K</td></tr>"
                + "<tr><td><a href=\"france/\">france/</a></td><td>2026-11-18 08:00</td></tr>"
                + "<tr><td><a href=\"france.map\">france.map</a></td><td>2026-11-18 08:00</td></tr>"
                + "<tr><td><a href=\"../../evil.poly\">evil</a></td></tr>"
                + "<tr><td><a href=\"https://evil.example/x.poly\">external</a></td></tr>"
                + "</table></html>";
        List<MapsforgeBoundaryCatalogParser.Entry> entries =
                MapsforgeBoundaryCatalogParser.parse(html, "europe/france/");
        check("catalogue .poly : deux frontières et un dossier retenus", entries.size() == 3);
        check("dossier classé avant frontières", entries.get(0).directory);
        MapsforgeBoundaryCatalogParser.Entry idf = entries.stream()
                .filter(e -> e.name.equals("ile-de-france.poly")).findFirst().orElse(null);
        check("chemin relatif et date de frontière conservés", idf != null
                && idf.relativePath.equals("europe/france/ile-de-france.poly")
                && idf.lastModified.equals("2026-11-20 12:00"));
        check("taille du fichier .poly analysée", idf != null && idf.sizeBytes >= 24 * 1024L);
        check("libellé régional humanisé", idf != null && idf.displayName.equals("Ile de france"));
        check("fichiers non .poly et liens dangereux rejetés", entries.stream().noneMatch(e -> e.name.contains("evil")));
        check("chemin traversal en entrée neutralisé", MapsforgeBoundaryCatalogParser.parse(html, "../private/").isEmpty());
        check("index vide retourne une liste vide", MapsforgeBoundaryCatalogParser.parse("", "").isEmpty());
        check("index trop grand refusé", MapsforgeBoundaryCatalogParser.parse("x".repeat(2 * 1024 * 1024 + 1), "").isEmpty());
        System.out.println("RESULTAT : " + ok + " réussis, " + ko + " échoués");
        if (ko != 0) throw new AssertionError(ko + " test(s) échoué(s)");
    }
}
