package com.example.gpstest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Coupe les polylignes au passage +/-180° et ajoute les points d'intersection réels. */
public final class MapTraceSegmenter {
    private MapTraceSegmenter() { }

    public static List<List<MapPoint>> splitAtAntimeridian(List<MapPoint> input) {
        if (input == null || input.size() < 2) return Collections.emptyList();
        ArrayList<List<MapPoint>> result = new ArrayList<>();
        ArrayList<MapPoint> current = new ArrayList<>();
        MapPoint previous = null;
        for (MapPoint point : input) {
            if (point == null) continue;
            if (previous != null) {
                double delta = point.longitude - previous.longitude;
                if (Math.abs(delta) > 180.0) {
                    boolean crossesEast = delta < -180.0;
                    double adjustedLongitude = point.longitude + (crossesEast ? 360.0 : -360.0);
                    double boundary = crossesEast ? 180.0 : -180.0;
                    double denominator = adjustedLongitude - previous.longitude;
                    double fraction = denominator == 0.0 ? 0.0
                            : (boundary - previous.longitude) / denominator;
                    fraction = Math.max(0.0, Math.min(1.0, fraction));
                    double crossingLatitude = previous.latitude
                            + (point.latitude - previous.latitude) * fraction;
                    MapPoint firstEdge = new MapPoint(crossingLatitude, boundary);
                    MapPoint otherEdge = new MapPoint(crossingLatitude, -boundary);
                    current.add(firstEdge);
                    if (current.size() >= 2) result.add(immutableCopy(current));
                    current = new ArrayList<>();
                    current.add(otherEdge);
                }
            }
            current.add(point);
            previous = point;
        }
        if (current.size() >= 2) result.add(immutableCopy(current));
        return Collections.unmodifiableList(result);
    }

    public static List<List<MapPoint>> splitAll(List<List<MapPoint>> input) {
        if (input == null || input.isEmpty()) return Collections.emptyList();
        ArrayList<List<MapPoint>> result = new ArrayList<>();
        for (List<MapPoint> line : input) result.addAll(splitAtAntimeridian(line));
        return Collections.unmodifiableList(result);
    }

    private static List<MapPoint> immutableCopy(List<MapPoint> points) {
        return Collections.unmodifiableList(new ArrayList<>(points));
    }
}
