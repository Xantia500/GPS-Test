package com.example.gpstest;

import java.util.List;

public final class MapsforgeCatalogParserTest {
    private static int ok;
    private static int ko;
    private static void check(String label, boolean value) {
        if (value) { ok++; System.out.println("  OK   " + label); }
        else { ko++; System.out.println("  FAIL " + label); }
    }

    public static void main(String[] args) {
        String html = """
                <!DOCTYPE html><html><body><table>
                <tr><td><a href="../">Parent Directory</a></td><td>-</td></tr>
                <tr><td><a href="france/">france/</a></td><td align="right">2026-10-21 03:31</td><td>-</td></tr>
                <tr><td><a href="belgium.map">belgium.map</a></td><td align="right">2026-08-21 20:35</td><td align="right">494M</td></tr>
                <tr><td><a href="andorra.map">andorra.map</a></td><td align="right">2026-09-07 09:30</td><td align="right">2.4M</td></tr>
                <tr><td><a href="malware.exe">malware.exe</a></td><td>2026-01-01 00:00</td><td>4K</td></tr>
                <tr><td><a href="//evil.example/map.map">evil</a></td><td>2026-01-01 00:00</td><td>4K</td></tr>
                </table></body></html>
                """;
        List<MapsforgeCatalogParser.Entry> entries = MapsforgeCatalogParser.parse(html, "europe/");
        check("index Apache : dossier et fichiers .map détectés", entries.size() == 3);
        check("les dossiers sont triés avant les fichiers", entries.get(0).directory);
        MapsforgeCatalogParser.Entry folder = entries.get(0);
        check("navigation de dossier conserve le chemin relatif", folder.directoryPath("europe/").equals("europe/france/"));
        MapsforgeCatalogParser.Entry andorra = entries.stream().filter(e -> e.name.equals("andorra.map")).findFirst().orElse(null);
        check("taille M décimale analysée comme estimation", andorra != null
                && andorra.approximateSizeBytes > 2_400_000L && andorra.approximateSizeBytes < 2_600_000L);
        check("dernière modification conservée", andorra != null && andorra.lastModified.equals("2026-09-07 09:30"));
        MapsforgeCatalogParser.Entry belgium = entries.stream().filter(e -> e.name.equals("belgium.map")).findFirst().orElse(null);
        check("nom de région stable inclut la hiérarchie et une empreinte de chemin", belgium != null
                && belgium.regionId().startsWith("europe-belgium-")
                && belgium.regionId().length() <= 64);
        check("les fichiers non .map et URL absolues sont ignorés", entries.stream().noneMatch(e -> e.name.equals("malware.exe")));
        List<MapsforgeCatalogParser.Entry> dotdot = MapsforgeCatalogParser.parse(
                "<html><table><tr><td><a href=\"unsafe..name.map\">unsafe..name.map</a></td></tr></table></html>", "europe/");
        check("nom de fichier contenant '..' ignoré par le parseur catalogue", dotdot.isEmpty());

        String oldStyle = "<html><pre><a href=\"world/\">world/</a><a href=\"small.map\">small.map</a></pre></html>";
        List<MapsforgeCatalogParser.Entry> old = MapsforgeCatalogParser.parse(oldStyle, "");
        check("ancienne forme Apache <pre> prise en charge", old.size() == 2);
        check("taille inconnue reste explicitement inconnue", old.stream()
                .filter(e -> e.name.equals("small.map")).findFirst().orElseThrow().approximateSizeBytes == -1L);
        check("chemin parent/traversal invalide neutralisé", MapsforgeCatalogParser.parse(html, "../secret/").stream()
                .noneMatch(e -> e.relativePath.contains("secret")));
        check("entrée HTML vide refusée", MapsforgeCatalogParser.parse("", "").isEmpty());
        check("catalogue trop volumineux refusé", MapsforgeCatalogParser.parse("x".repeat(2 * 1024 * 1024 + 1), "").isEmpty());

        String longA = "continent/" + "a".repeat(60) + "/" + "b".repeat(60) + ".map";
        String longB = "continent/" + "a".repeat(60) + "/" + "c".repeat(60) + ".map";
        MapsforgeCatalogParser.Entry a = MapsforgeCatalogParser.parse(
                "<html><table><tr><td><a href=\"" + longA.substring(longA.lastIndexOf('/') + 1)
                        + "\">map-a.map</a></td></tr></table></html>", "continent/" + "a".repeat(60) + "/")
                .stream().findFirst().orElse(null);
        MapsforgeCatalogParser.Entry b = MapsforgeCatalogParser.parse(
                "<html><table><tr><td><a href=\"" + longB.substring(longB.lastIndexOf('/') + 1)
                        + "\">map-b.map</a></td></tr></table></html>", "continent/" + "a".repeat(60) + "/")
                .stream().findFirst().orElse(null);
        check("IDs de chemins longs restent bornés à 64 caractères",
                a != null && b != null && a.regionId().length() <= 64 && b.regionId().length() <= 64);
        check("IDs de chemins longs distinguent deux régions voisines",
                a != null && b != null && !a.regionId().equals(b.regionId()));
        List<MapsforgeCatalogParser.Entry> underscore = MapsforgeCatalogParser.parse(
                "<html><table><tr><td><a href=\"some_region.map\">some_region.map</a></td></tr>"
                        + "<tr><td><a href=\"some-region.map\">some-region.map</a></td></tr></table></html>",
                "europe/france/");
        check("underscore et tiret ne provoquent pas de collision d'identifiant",
                underscore.size() == 2 && !underscore.get(0).regionId().equals(underscore.get(1).regionId()));

        System.out.println("RESULTAT : " + ok + " réussis, " + ko + " échoués");
        if (ko != 0) throw new AssertionError(ko + " test(s) échoué(s)");
    }
}
