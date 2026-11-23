package com.example.gpstest;

import java.util.List;

public final class MapsforgeRegionMatcherTest {
    private static int ok, ko;
    private static void check(String label, boolean value) {
        if (value) { ok++; System.out.println("  OK   " + label); }
        else { ko++; System.out.println("  FAIL " + label); }
    }
    public static void main(String[] args) {
        String mapHtml = "<html><table>"
                + "<tr><td><a href=\"ile-de-france.map\">ile-de-france.map</a></td></tr>"
                + "<tr><td><a href=\"bretagne/\">bretagne/</a></td></tr>"
                + "</table></html>";
        List<MapsforgeCatalogParser.Entry> maps = MapsforgeCatalogParser.parse(mapHtml, "europe/france/");
        String polyHtml = "<html><table>"
                + "<tr><td><a href=\"ile-de-france.poly\">ile-de-france.poly</a></td></tr>"
                + "<tr><td><a href=\"bretagne.poly\">bretagne.poly</a></td></tr>"
                + "</table></html>";
        List<MapsforgeBoundaryCatalogParser.Entry> polys =
                MapsforgeBoundaryCatalogParser.parse(polyHtml, "europe/france/");
        MapsforgeCatalogParser.Entry idf = MapsforgeRegionMatcher.findTarget(
                polys.stream().filter(e -> e.name.equals("ile-de-france.poly")).findFirst().orElse(null), maps);
        MapsforgeCatalogParser.Entry brittany = MapsforgeRegionMatcher.findTarget(
                polys.stream().filter(e -> e.name.equals("bretagne.poly")).findFirst().orElse(null), maps);
        check("frontière .poly associée au fichier .map homonyme", idf != null
                && "ile-de-france.map".equals(idf.name));
        check("frontière .poly associée à un dossier carte homonyme", brittany != null
                && brittany.directory && "bretagne".equals(brittany.name));
        check("frontière sans cible cartographique ignorée", MapsforgeRegionMatcher.findTarget(
                new MapsforgeBoundaryCatalogParser.Entry("missing.poly", "missing", "missing.poly", "", false, 1), maps) == null);
        check("région non installée marquée disponible",
                MapsforgeRegionMatcher.state(false, "", "2026-11-20 10:00") == MapsforgeRegionMatcher.STATE_AVAILABLE);
        check("région installée à même version distinguée",
                MapsforgeRegionMatcher.state(true, "2026-11-20 10:00", "2026-11-20 10:00")
                        == MapsforgeRegionMatcher.STATE_INSTALLED);
        check("nouvelle date distante marque une mise à jour",
                MapsforgeRegionMatcher.state(true, "2026-11-19 10:00", "2026-11-20 10:00")
                        == MapsforgeRegionMatcher.STATE_UPDATE);
        check("version distante inconnue n'affirme pas une mise à jour",
                MapsforgeRegionMatcher.state(true, "", "") == MapsforgeRegionMatcher.STATE_INSTALLED);
        check("installation sans version source signale une mise à jour à vérifier",
                MapsforgeRegionMatcher.state(true, "", "2026-11-20 10:00") == MapsforgeRegionMatcher.STATE_UPDATE);
        check("id de dossier est stable et non vide",
                MapsforgeRegionMatcher.directoryRegionId("europe/france/bretagne/").equals(
                        MapsforgeRegionMatcher.directoryRegionId("europe/france/bretagne/")));
        check("dossiers distincts ont des IDs distincts",
                !MapsforgeRegionMatcher.directoryRegionId("europe/france/bretagne/").equals(
                        MapsforgeRegionMatcher.directoryRegionId("europe/france/normandie/")));
        System.out.println("RESULTAT : " + ok + " réussis, " + ko + " échoués");
        if (ko != 0) throw new AssertionError(ko + " test(s) échoué(s)");
    }

}
