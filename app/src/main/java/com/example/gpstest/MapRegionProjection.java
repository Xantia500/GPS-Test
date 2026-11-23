package com.example.gpstest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Projection Web Mercator et emprise circulaire pour la carte de sélection .poly. */
public final class MapRegionProjection {
    public static final double MAX_MERCATOR_LATITUDE = 85.05112878;
    private static final double TWO_PI = Math.PI * 2.0;

    public static final class Bounds {
        public final double minX, maxX, minY, maxY;
        /** Centre longitude non normalisé : peut dépasser +/-180° à l'antiméridien. */
        public final double centerLongitude;
        private Bounds(double minX, double maxX, double minY, double maxY,
                       double centerLongitude) {
            this.minX = minX; this.maxX = maxX;
            this.minY = minY; this.maxY = maxY;
            this.centerLongitude = centerLongitude;
        }
    }

    private MapRegionProjection() { }

    public static Bounds fromPolygons(List<MapsforgePolyParser.Polygon> polygons) {
        if (polygons == null || polygons.isEmpty()) return worldBounds();
        int count = 0;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (MapsforgePolyParser.Polygon polygon : polygons) {
            if (polygon == null) continue;
            for (MapsforgePolyParser.Ring ring : polygon.rings) {
                count += ring.points.size();
                for (MapsforgePolyParser.Point point : ring.points) {
                    double y = mercatorY(point.latitude);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (count == 0 || !Double.isFinite(minY) || !Double.isFinite(maxY)) return worldBounds();

        double[] longitudes = new double[count];
        int index = 0;
        for (MapsforgePolyParser.Polygon polygon : polygons) {
            if (polygon == null) continue;
            for (MapsforgePolyParser.Ring ring : polygon.rings) {
                for (MapsforgePolyParser.Point point : ring.points) {
                    longitudes[index++] = normalize360(point.longitude);
                }
            }
        }
        if (index != count) {
            longitudes = java.util.Arrays.copyOf(longitudes, index);
        }
        java.util.Arrays.sort(longitudes);

        // L'arc de longitude minimal est le complément du plus grand vide
        // circulaire. Cela évite d'afficher une région +/-180° sur presque
        // toute la largeur du monde.
        double largestGap = -1.0;
        int gapAfter = -1;
        for (int i = 0; i < longitudes.length; i++) {
            double current = longitudes[i];
            double next = i + 1 < longitudes.length ? longitudes[i + 1]
                    : longitudes[0] + 360.0;
            if (next - current > largestGap) {
                largestGap = next - current;
                gapAfter = i;
            }
        }
        double west = longitudes[(gapAfter + 1) % longitudes.length];
        double east = longitudes[gapAfter];
        if (gapAfter < longitudes.length - 1) east += 360.0;
        double center = (west + east) / 2.0;
        double minX = Math.toRadians(west);
        double maxX = Math.toRadians(east);
        if (maxX - minX < 1e-8) {
            minX -= 1e-4;
            maxX += 1e-4;
        }
        if (maxY - minY < 1e-8) {
            minY -= 1e-4;
            maxY += 1e-4;
        }
        return new Bounds(minX, maxX, minY, maxY, center);
    }

    /** Emprise minimale circulaire d'une collection de points (gère +180/-180). */
    public static Bounds fromPoints(List<MapPoint> points) {
        if (points == null || points.isEmpty()) return worldBounds();
        ArrayList<Double> longitudes = new ArrayList<>(points.size());
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (MapPoint point : points) {
            if (point == null || !Double.isFinite(point.latitude)
                    || !Double.isFinite(point.longitude)
                    || point.latitude < -90.0 || point.latitude > 90.0
                    || point.longitude < -180.0 || point.longitude > 180.0) continue;
            longitudes.add(normalize360(point.longitude));
            double y = mercatorY(point.latitude);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        if (longitudes.isEmpty() || !Double.isFinite(minY) || !Double.isFinite(maxY))
            return worldBounds();
        Collections.sort(longitudes);
        double largestGap = -1.0;
        int gapAfter = 0;
        for (int i = 0; i < longitudes.size(); i++) {
            double current = longitudes.get(i);
            double next = i + 1 < longitudes.size()
                    ? longitudes.get(i + 1) : longitudes.get(0) + 360.0;
            if (next - current > largestGap) {
                largestGap = next - current;
                gapAfter = i;
            }
        }
        double west = longitudes.get((gapAfter + 1) % longitudes.size());
        double east = longitudes.get(gapAfter);
        if (gapAfter < longitudes.size() - 1) east += 360.0;
        double center = (west + east) * 0.5;
        double minX = Math.toRadians(west);
        double maxX = Math.toRadians(east);
        if (maxX - minX < 1e-8) {
            minX -= Math.toRadians(0.005);
            maxX += Math.toRadians(0.005);
        }
        if (maxY - minY < 1e-8) {
            minY -= 1e-4;
            maxY += 1e-4;
        }
        return new Bounds(minX, maxX, minY, maxY, center);
    }

    public static Bounds worldBounds() {
        return new Bounds(-Math.PI, Math.PI,
                mercatorY(-MAX_MERCATOR_LATITUDE), mercatorY(MAX_MERCATOR_LATITUDE), 0.0);
    }

    public static double mercatorY(double latitude) {
        double clamped = Math.max(-MAX_MERCATOR_LATITUDE,
                Math.min(MAX_MERCATOR_LATITUDE, latitude));
        double radians = Math.toRadians(clamped);
        return Math.log(Math.tan(Math.PI / 4.0 + radians / 2.0));
    }

    /** Déroule une longitude vers la représentation la plus proche d'une référence. */
    public static double unwrapLongitude(double longitude, double referenceLongitude) {
        if (!Double.isFinite(longitude) || !Double.isFinite(referenceLongitude)) return longitude;
        double value = longitude;
        while (value - referenceLongitude > 180.0) value -= 360.0;
        while (value - referenceLongitude < -180.0) value += 360.0;
        return value;
    }

    public static double normalizeLongitude(double longitude) {
        double value = longitude;
        while (value > 180.0) value -= 360.0;
        while (value < -180.0) value += 360.0;
        return value;
    }

    public static double normalize360(double longitude) {
        double value = longitude % 360.0;
        if (value < 0.0) value += 360.0;
        return value;
    }

    public static double[] project(double latitude, double longitude, Bounds bounds,
                                   int width, int height, float zoom,
                                   float panX, float panY, int horizontalPadding,
                                   int verticalPadding) {
        if (bounds == null || width <= 0 || height <= 0) return new double[]{Double.NaN, Double.NaN};
        double scale = scale(bounds, width, height, zoom, horizontalPadding, verticalPadding);
        double x = Math.toRadians(unwrapLongitude(longitude, bounds.centerLongitude));
        double y = mercatorY(latitude);
        return new double[]{width / 2.0 + (x - (bounds.minX + bounds.maxX) / 2.0) * scale + panX,
                height / 2.0 - (y - (bounds.minY + bounds.maxY) / 2.0) * scale + panY};
    }

    public static double[] unproject(double screenX, double screenY, Bounds bounds,
                                     int width, int height, float zoom,
                                     float panX, float panY, int horizontalPadding,
                                     int verticalPadding) {
        if (bounds == null || width <= 0 || height <= 0) return new double[]{Double.NaN, Double.NaN};
        double scale = scale(bounds, width, height, zoom, horizontalPadding, verticalPadding);
        double x = (bounds.minX + bounds.maxX) / 2.0
                + (screenX - width / 2.0 - panX) / scale;
        double y = (bounds.minY + bounds.maxY) / 2.0
                - (screenY - height / 2.0 - panY) / scale;
        double latitude = Math.toDegrees(2.0 * Math.atan(Math.exp(y)) - Math.PI / 2.0);
        double longitude = normalizeLongitude(Math.toDegrees(x));
        return new double[]{latitude, longitude};
    }

    public static double scale(Bounds bounds, int width, int height, float zoom,
                               int horizontalPadding, int verticalPadding) {
        double availableWidth = Math.max(1, width - 2.0 * Math.max(0, horizontalPadding));
        double availableHeight = Math.max(1, height - 2.0 * Math.max(0, verticalPadding));
        double dx = Math.max(1e-10, bounds.maxX - bounds.minX);
        double dy = Math.max(1e-10, bounds.maxY - bounds.minY);
        return Math.max(1e-6, Math.min(availableWidth / dx, availableHeight / dy)
                * Math.max(0.1f, zoom));
    }

    private static double normalize360ForReference(double longitude, double reference) {
        return unwrapLongitude(longitude, reference);
    }

    /** Longitude centrale robuste d'un polygone, pré-calculée lors du parsing. */
    public static double polygonCenterLongitude(MapsforgePolyParser.Polygon polygon) {
        return polygon == null ? 0.0 : polygon.centerLongitude;
    }

    static double centerLongitudeForRings(List<MapsforgePolyParser.Ring> rings) {
        int count = 0;
        if (rings != null) for (MapsforgePolyParser.Ring ring : rings) {
            if (ring != null) count += ring.points.size();
        }
        if (count == 0) return 0.0;
        double[] longitudes = new double[count];
        int index = 0;
        for (MapsforgePolyParser.Ring ring : rings) {
            if (ring == null) continue;
            for (MapsforgePolyParser.Point point : ring.points) {
                longitudes[index++] = normalize360(point.longitude);
            }
        }
        java.util.Arrays.sort(longitudes);
        double largestGap = -1.0;
        int gapAfter = 0;
        for (int i = 0; i < longitudes.length; i++) {
            double next = i + 1 < longitudes.length ? longitudes[i + 1] : longitudes[0] + 360.0;
            double gap = next - longitudes[i];
            if (gap > largestGap) { largestGap = gap; gapAfter = i; }
        }
        double west = longitudes[(gapAfter + 1) % longitudes.length];
        double east = longitudes[gapAfter];
        if (gapAfter < longitudes.length - 1) east += 360.0;
        return (west + east) / 2.0;
    }
}
