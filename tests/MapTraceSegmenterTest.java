package com.example.gpstest;

import java.util.Arrays;
import java.util.List;

public final class MapTraceSegmenterTest {
    private static int passed, failed;
    private static void check(String label, boolean value) {
        if (value) passed++; else failed++;
        System.out.println((value ? "  OK   " : "  FAIL ") + label);
    }
    public static void main(String[] args) {
        List<MapPoint> line = Arrays.asList(new MapPoint(10, 179.5), new MapPoint(10.1, 179.9),
                new MapPoint(10.2, -179.8), new MapPoint(10.3, -179.5));
        List<List<MapPoint>> split = MapTraceSegmenter.splitAtAntimeridian(line);
        check("passage de l'antiméridien produit deux polylignes", split.size() == 2);
        check("les deux parties gardent les points d'origine et les intersections", split.size() == 2
                && split.get(0).size() == 3 && split.get(1).size() == 3
                && split.get(0).get(0).longitude == 179.5
                && split.get(1).get(split.get(1).size() - 1).longitude == -179.5);
        boolean noLongJump = true;
        for (List<MapPoint> part : split) for (int i = 1; i < part.size(); i++) {
            if (Math.abs(part.get(i).longitude - part.get(i - 1).longitude) > 180) noLongJump = false;
        }
        check("aucune liaison artificielle à travers la carte", noLongJump);
        check("les deux côtés atteignent la frontière +/-180°", split.size() == 2
                && split.get(0).get(split.get(0).size() - 1).longitude == 180.0
                && split.get(1).get(0).longitude == -180.0);
        List<List<MapPoint>> twoPoint = MapTraceSegmenter.splitAtAntimeridian(Arrays.asList(
                new MapPoint(0, 179.8), new MapPoint(1, -179.8)));
        check("une trace de deux points qui traverse l'antiméridien reste visible des deux côtés",
                twoPoint.size() == 2 && twoPoint.get(0).size() == 2 && twoPoint.get(1).size() == 2);
        check("latitude du point d'intersection interpolée sur le segment géographique",
                twoPoint.size() == 2
                        && Math.abs(twoPoint.get(0).get(1).latitude - 0.5) < 1e-8
                        && Math.abs(twoPoint.get(1).get(0).latitude - 0.5) < 1e-8);
        List<List<MapPoint>> reverse = MapTraceSegmenter.splitAtAntimeridian(Arrays.asList(
                new MapPoint(1, -179.8), new MapPoint(0, 179.8)));
        check("franchissement dans le sens inverse également interpolé",
                reverse.size() == 2 && reverse.get(0).get(1).longitude == -180.0
                        && reverse.get(1).get(0).longitude == 180.0);
        List<List<MapPoint>> normal = MapTraceSegmenter.splitAtAntimeridian(Arrays.asList(
                new MapPoint(48, 2), new MapPoint(48.1, 2.1), new MapPoint(48.2, 2.2)));
        check("une trace normale reste un seul segment", normal.size() == 1 && normal.get(0).size() == 3);
        check("moins de deux points n'engendre pas de ligne", MapTraceSegmenter.splitAtAntimeridian(
                Arrays.asList(new MapPoint(0, 0))).isEmpty());
        System.out.println("RESULTAT : " + passed + " réussis, " + failed + " échoués");
        if (failed > 0) throw new AssertionError(failed + " échec(s)");
    }
}
