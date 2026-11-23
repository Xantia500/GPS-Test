package com.example.gpstest;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class MapRegionProjectionTest {
    private static int passed, failed;
    private static void check(String label, boolean value) {
        if (value) passed++; else failed++;
        System.out.println((value ? "  OK   " : "  FAIL ") + label);
    }
    private static MapsforgePolyParser.Polygon poly(String text) throws Exception {
        return MapsforgePolyParser.parse(text.getBytes(StandardCharsets.UTF_8));
    }
    public static void main(String[] args) throws Exception {
        MapsforgePolyParser.Polygon paris = poly("paris\n1\n2.0 48.0\n3.0 48.0\n3.0 49.0\n2.0 49.0\nEND\nEND\n");
        MapRegionProjection.Bounds local = MapRegionProjection.fromPolygons(Collections.singletonList(paris));
        check("emprise locale : centre longitude en degrés cohérent", Math.abs(local.centerLongitude - 2.5) < 1e-9);
        double[] screen = MapRegionProjection.project(48.5, 2.5, local, 1080, 600,
                1f, 0, 0, 16, 16);
        double[] geo = MapRegionProjection.unproject(screen[0], screen[1], local,
                1080, 600, 1f, 0, 0, 16, 16);
        check("aller-retour projection Paris conserve la latitude", Math.abs(geo[0] - 48.5) < 1e-8);
        check("aller-retour projection Paris conserve la longitude", Math.abs(geo[1] - 2.5) < 1e-8);

        MapsforgePolyParser.Polygon dateline = poly("dateline\n1\n170 10\n-170 10\n-170 20\n170 20\nEND\nEND\n");
        check("polygone traversant l'antiméridien contient +175°", dateline.contains(15, 175));
        check("polygone traversant l'antiméridien contient -175°", dateline.contains(15, -175));
        check("polygone traversant l'antiméridien rejette Greenwich", !dateline.contains(15, 0));
        check("centre de polygone calculé une fois reste proche de 180°",
                Math.abs(Math.abs(MapRegionProjection.polygonCenterLongitude(dateline)) - 180.0) < 1e-8);
        MapRegionProjection.Bounds dateBounds = MapRegionProjection.fromPolygons(Collections.singletonList(dateline));
        check("emprise de part et d'autre de 180° reste courte",
                (dateBounds.maxX - dateBounds.minX) < Math.toRadians(25));
        double[] across = MapRegionProjection.project(15, -175, dateBounds, 900, 500,
                1f, 0, 0, 10, 10);
        double[] acrossBack = MapRegionProjection.unproject(across[0], across[1], dateBounds,
                900, 500, 1f, 0, 0, 10, 10);
        check("projection +180/-180 conserve longitude négative", Math.abs(acrossBack[1] + 175) < 1e-8);

        MapsforgePolyParser.Polygon other = poly("other\n1\n-75 38\n-74 38\n-74 39\n-75 39\nEND\nEND\n");
        MapRegionProjection.Bounds pair = MapRegionProjection.fromPolygons(Arrays.asList(paris, other));
        check("plusieurs continents emploient l'arc longitudinal minimal", pair.maxX - pair.minX < Math.PI);

        List<MapPoint> datelineTrack = Arrays.asList(
                new MapPoint(10, 179), new MapPoint(12, -179), new MapPoint(11, 178));
        MapRegionProjection.Bounds trackBounds = MapRegionProjection.fromPoints(datelineTrack);
        check("trace GPX à cheval sur l'antiméridien : emprise courte",
                trackBounds.maxX - trackBounds.minX < Math.toRadians(5));
        double[] trackScreen = MapRegionProjection.project(12, -179, trackBounds, 900, 500,
                1f, 0, 0, 12, 12);
        double[] trackBack = MapRegionProjection.unproject(trackScreen[0], trackScreen[1],
                trackBounds, 900, 500, 1f, 0, 0, 12, 12);
        check("trace GPX : projection inverse conserve le point après 180°",
                Math.abs(trackBack[0] - 12) < 1e-8 && Math.abs(trackBack[1] + 179) < 1e-8);
        MapRegionProjection.Bounds onePoint = MapRegionProjection.fromPoints(
                Collections.singletonList(new MapPoint(48.8566, 2.3522)));
        check("trace mono-point : emprise non dégénérée",
                onePoint.maxX > onePoint.minX && onePoint.maxY > onePoint.minY);
        MapRegionProjection.Bounds emptyPoints = MapRegionProjection.fromPoints(
                Collections.<MapPoint>emptyList());
        check("trace vide : repli déterministe sur l'emprise mondiale",
                emptyPoints.minX == -Math.PI && emptyPoints.maxX == Math.PI);

        MapRegionProjection.Bounds world = MapRegionProjection.worldBounds();
        double[] worldPoint = MapRegionProjection.project(0, 0, world, 1000, 500,
                1f, 0, 0, 0, 0);
        check("monde : longitude zéro projetée au centre horizontal", Math.abs(worldPoint[0] - 500) < 1e-6);

        System.out.println("RESULTAT : " + passed + " réussis, " + failed + " échoués");
        if (failed > 0) throw new AssertionError(failed + " échec(s)");
    }
}
