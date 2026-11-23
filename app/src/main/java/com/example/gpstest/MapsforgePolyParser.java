package com.example.gpstest;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;

/** Parseur borné du format Osmosis .poly utilisé par les frontières Mapsforge. */
public final class MapsforgePolyParser {
    public static final int MAX_BYTES = 512 * 1024;
    public static final int MAX_RINGS = 256;
    public static final int MAX_POINTS = 100_000;
    private static final int MAX_LINE_CHARS = 256;

    public static final class Point {
        public final double longitude;
        public final double latitude;
        public Point(double longitude, double latitude) {
            this.longitude = longitude;
            this.latitude = latitude;
        }
    }

    public static final class Ring {
        public final String id;
        public final boolean hole;
        public final List<Point> points;
        /** Longitudes déroulées à la construction pour les anneaux +/-180°. */
        final double[] unwrappedLongitudes;
        final double minUnwrappedLongitude, maxUnwrappedLongitude, centerUnwrappedLongitude;
        final double minLatitude, maxLatitude;
        private Ring(String id, boolean hole, List<Point> points) {
            this.id = id;
            this.hole = hole;
            this.points = Collections.unmodifiableList(new ArrayList<>(points));
            this.unwrappedLongitudes = new double[points.size()];
            double previous = points.isEmpty() ? 0.0 : points.get(0).longitude;
            double minLon = Double.POSITIVE_INFINITY, maxLon = Double.NEGATIVE_INFINITY;
            double minLat = Double.POSITIVE_INFINITY, maxLat = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < points.size(); i++) {
                Point point = points.get(i);
                double lon = i == 0 ? point.longitude
                        : MapRegionProjection.unwrapLongitude(point.longitude, previous);
                unwrappedLongitudes[i] = lon;
                previous = lon;
                minLon = Math.min(minLon, lon); maxLon = Math.max(maxLon, lon);
                minLat = Math.min(minLat, point.latitude); maxLat = Math.max(maxLat, point.latitude);
            }
            this.minUnwrappedLongitude = minLon;
            this.maxUnwrappedLongitude = maxLon;
            this.centerUnwrappedLongitude = (minLon + maxLon) / 2.0;
            this.minLatitude = minLat;
            this.maxLatitude = maxLat;
        }
    }

    public static final class Polygon {
        public final String sourceName;
        public final List<Ring> rings;
        public final int pointCount;
        public final double minLatitude, maxLatitude, minLongitude, maxLongitude;
        /** Centre de longitude non normalisé, calculé une seule fois au parsing. */
        public final double centerLongitude;

        private Polygon(String sourceName, List<Ring> rings, int pointCount,
                        double minLatitude, double maxLatitude,
                        double minLongitude, double maxLongitude) {
            this.sourceName = sourceName;
            this.rings = Collections.unmodifiableList(new ArrayList<>(rings));
            this.pointCount = pointCount;
            this.minLatitude = minLatitude;
            this.maxLatitude = maxLatitude;
            this.minLongitude = minLongitude;
            this.maxLongitude = maxLongitude;
            this.centerLongitude = MapRegionProjection.centerLongitudeForRings(this.rings);
        }

        /** Union de tous les anneaux extérieurs, moins l'union des anneaux ! (trous). */
        public boolean contains(double latitude, double longitude) {
            if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                    || latitude < minLatitude || latitude > maxLatitude) return false;
            boolean inOuter = false;
            for (Ring ring : rings) {
                if (containsRing(ring, latitude, longitude)) {
                    if (ring.hole) return false;
                    inOuter = true;
                }
            }
            return inOuter;
        }

        private static boolean containsRing(Ring ring, double latitude, double longitude) {
            if (latitude < ring.minLatitude || latitude > ring.maxLatitude) return false;
            // Déplace la longitude testée dans le même intervalle déroulé que
            // l'anneau, par exemple -175° devient +185° pour un polygone 170°..190°.
            double testLongitude = MapRegionProjection.unwrapLongitude(
                    longitude, ring.centerUnwrappedLongitude);
            if (testLongitude < ring.minUnwrappedLongitude
                    || testLongitude > ring.maxUnwrappedLongitude) return false;
            boolean inside = false;
            List<Point> points = ring.points;
            double[] longitudes = ring.unwrappedLongitudes;
            for (int i = 0, j = points.size() - 1; i < points.size(); j = i++) {
                Point a = points.get(i);
                Point b = points.get(j);
                double lonA = longitudes[i];
                double lonB = longitudes[j];
                if (((a.latitude > latitude) != (b.latitude > latitude))
                        && testLongitude < (lonB - lonA)
                        * (latitude - a.latitude) / (b.latitude - a.latitude) + lonA) {
                    inside = !inside;
                }
            }
            return inside;
        }
    }

    private MapsforgePolyParser() { }

    public static Polygon parse(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IOException("Fichier .poly vide ou trop volumineux");
        }
        return parse(new ByteArrayInputStream(bytes));
    }

    public static Polygon parse(InputStream input) throws IOException {
        if (input == null) throw new IOException("Flux .poly absent");
        // La lecture est bornée même si l'InputStream ne connaît pas sa taille.
        LimitedInputStream bounded = new LimitedInputStream(input, MAX_BYTES);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(bounded, StandardCharsets.UTF_8), 8192)) {
            String sourceName = reader.readLine();
            if (sourceName == null || sourceName.trim().isEmpty()) {
                throw new IOException("Nom de fichier .poly absent");
            }
            sourceName = sourceName.trim();
            ArrayList<Ring> rings = new ArrayList<>();
            ArrayList<Point> current = null;
            String ringId = null;
            boolean hole = false;
            int totalPoints = 0;
            int lineNo = 1;
            boolean finalEnd = false;
            String line;
            double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;

            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.length() > MAX_LINE_CHARS) throw error(lineNo, "ligne trop longue");
                String value = line.trim();
                if (value.isEmpty()) continue;
                if (current == null) {
                    if ("END".equalsIgnoreCase(value)) {
                        finalEnd = true;
                        break;
                    }
                    if (rings.size() >= MAX_RINGS) throw error(lineNo, "trop d'anneaux");
                    ringId = value;
                    hole = value.startsWith("!");
                    if (hole && rings.stream().noneMatch(r -> !r.hole)) {
                        throw error(lineNo, "un trou précède tout anneau extérieur");
                    }
                    current = new ArrayList<>();
                } else if ("END".equalsIgnoreCase(value)) {
                    if (current.size() < 3) throw error(lineNo, "anneau avec moins de trois sommets");
                    Point first = current.get(0);
                    Point last = current.get(current.size() - 1);
                    if (!same(first, last)) current.add(new Point(first.longitude, first.latitude));
                    if (distinctCount(current) < 3) throw error(lineNo, "anneau dégénéré");
                    rings.add(new Ring(ringId, hole, current));
                    current = null;
                    ringId = null;
                } else {
                    String[] pair = value.split("\\s+");
                    if (pair.length != 2) throw error(lineNo, "coordonnée attendue : longitude latitude");
                    double lon, lat;
                    try {
                        lon = Double.parseDouble(pair[0]);
                        lat = Double.parseDouble(pair[1]);
                    } catch (NumberFormatException e) {
                        throw error(lineNo, "coordonnée non numérique");
                    }
                    if (!Double.isFinite(lon) || !Double.isFinite(lat)
                            || lon < -180 || lon > 180 || lat < -90 || lat > 90) {
                        throw error(lineNo, "coordonnée hors limites WGS84");
                    }
                    current.add(new Point(lon, lat));
                    totalPoints++;
                    if (totalPoints > MAX_POINTS) throw error(lineNo, "trop de sommets");
                    minLat = Math.min(minLat, lat); maxLat = Math.max(maxLat, lat);
                    minLon = Math.min(minLon, lon); maxLon = Math.max(maxLon, lon);
                }
            }
            if (current != null) throw new IOException(".poly tronqué : anneau sans END");
            if (!finalEnd) throw new IOException(".poly tronqué : END final absent");
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) throw new IOException("Données après le END final");
            }
            if (rings.isEmpty()) throw new IOException("Aucun anneau dans le fichier .poly");
            return new Polygon(sourceName, rings, totalPoints,
                    minLat, maxLat, minLon, maxLon);
        }
    }

    private static IOException error(int line, String message) {
        return new IOException(".poly ligne " + line + " : " + message);
    }

    private static boolean same(Point a, Point b) {
        return Math.abs(a.longitude - b.longitude) < 1e-10
                && Math.abs(a.latitude - b.latitude) < 1e-10;
    }

    private static int distinctCount(List<Point> points) {
        // HashSet évite un parcours quadratique sur les frontières détaillées
        // (certaines régions comportent plusieurs dizaines de milliers de sommets).
        HashSet<String> unique = new HashSet<>();
        for (Point point : points) {
            unique.add(Double.toHexString(point.longitude) + "," + Double.toHexString(point.latitude));
            if (unique.size() >= 3) return 3;
        }
        return unique.size();
    }

    private static final class LimitedInputStream extends java.io.FilterInputStream {
        private final int max;
        private int count;
        LimitedInputStream(InputStream in, int max) { super(in); this.max = max; }
        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0 && ++count > max) throw new IOException(".poly dépasse la taille autorisée");
            return value;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int allowed = Math.min(len, max - count + 1);
            if (allowed <= 0) throw new IOException(".poly dépasse la taille autorisée");
            int read = super.read(b, off, allowed);
            if (read > 0) {
                count += read;
                if (count > max) throw new IOException(".poly dépasse la taille autorisée");
            }
            return read;
        }
    }
}
