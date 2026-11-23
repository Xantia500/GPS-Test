package com.example.gpstest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Simplification Douglas-Peucker bornée pour les polylignes affichées sur la carte. */
public final class MapTraceSimplifier {
    private static final double EARTH_RADIUS_M = 6_371_008.8;
    private MapTraceSimplifier() { }

    public static List<MapPoint> simplify(List<MapPoint> input, double toleranceMeters,
                                           int maxPoints) {
        if (input == null || input.isEmpty()) return Collections.emptyList();
        if (input.size() <= 2) {
            return Collections.unmodifiableList(new ArrayList<>(input));
        }
        if (maxPoints < 2) maxPoints = 2;
        double tolerance = Double.isFinite(toleranceMeters) && toleranceMeters > 0
                ? toleranceMeters : 2.0;
        boolean[] keep;
        int kept;
        do {
            keep = simplifyMask(input, tolerance);
            kept = count(keep);
            if (kept <= maxPoints) break;
            tolerance *= 1.7;
        } while (Double.isFinite(tolerance) && tolerance < 100_000_000.0);

        ArrayList<MapPoint> out = new ArrayList<>(Math.min(kept, maxPoints));
        if (kept <= maxPoints) {
            for (int i = 0; i < keep.length; i++) if (keep[i]) out.add(input.get(i));
        } else {
            // Garde-fou déterministe si une trace très bruitée dépasse encore le budget.
            int stride = (int) Math.ceil((double) (input.size() - 1) / (maxPoints - 1));
            for (int i = 0; i < input.size() - 1; i += stride) out.add(input.get(i));
            MapPoint last = input.get(input.size() - 1);
            if (out.isEmpty() || out.get(out.size() - 1) != last) out.add(last);
        }
        return Collections.unmodifiableList(out);
    }

    private static boolean[] simplifyMask(List<MapPoint> points, double tolerance) {
        int n = points.size();
        boolean[] keep = new boolean[n];
        keep[0] = true;
        keep[n - 1] = true;
        ArrayDeque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[]{0, n - 1});
        double toleranceSquared = tolerance * tolerance;
        while (!stack.isEmpty()) {
            int[] range = stack.pop();
            int start = range[0], end = range[1];
            if (end - start <= 1) continue;
            MapPoint a = points.get(start), b = points.get(end);
            double referenceLat = Math.toRadians((a.latitude + b.latitude) * 0.5);
            double cosLat = Math.max(1e-6, Math.abs(Math.cos(referenceLat)));
            double ax = 0.0, ay = 0.0;
            double bx = EARTH_RADIUS_M * Math.toRadians(
                    MapRegionProjection.unwrapLongitude(b.longitude, a.longitude) - a.longitude) * cosLat;
            double by = EARTH_RADIUS_M * Math.toRadians(b.latitude - a.latitude);
            double vx = bx - ax, vy = by - ay;
            double denominator = vx * vx + vy * vy;
            double maxDistanceSquared = -1.0;
            int farthest = -1;
            for (int i = start + 1; i < end; i++) {
                MapPoint p = points.get(i);
                double px = EARTH_RADIUS_M * Math.toRadians(
                        MapRegionProjection.unwrapLongitude(p.longitude, a.longitude) - a.longitude) * cosLat;
                double py = EARTH_RADIUS_M * Math.toRadians(p.latitude - a.latitude);
                double t = denominator <= 1e-12 ? 0.0
                        : Math.max(0.0, Math.min(1.0, (px * vx + py * vy) / denominator));
                double dx = px - (ax + t * vx), dy = py - (ay + t * vy);
                double distanceSquared = dx * dx + dy * dy;
                if (distanceSquared > maxDistanceSquared) {
                    maxDistanceSquared = distanceSquared;
                    farthest = i;
                }
            }
            if (farthest >= 0 && maxDistanceSquared > toleranceSquared) {
                keep[farthest] = true;
                stack.push(new int[]{start, farthest});
                stack.push(new int[]{farthest, end});
            }
        }
        return keep;
    }

    private static int count(boolean[] values) {
        int total = 0;
        for (boolean value : values) if (value) total++;
        return total;
    }
}
