package com.example.gpstest;

/** Échantillonnage visuel des contours .poly ; la géométrie complète reste utilisée au hit-test. */
public final class MapsforgePolygonSampler {
    public static final int MAX_DRAW_POINTS_PER_RING = 1400;

    private MapsforgePolygonSampler() { }

    /** Pas de dessin pour conserver au plus environ MAX_DRAW_POINTS_PER_RING sommets. */
    public static int stride(int sourcePointCount) {
        if (sourcePointCount <= MAX_DRAW_POINTS_PER_RING) return 1;
        int intervals = Math.max(1, MAX_DRAW_POINTS_PER_RING - 1);
        int sourceIntervals = Math.max(1, sourcePointCount - 1);
        return Math.max(1, (sourceIntervals + intervals - 1) / intervals);
    }

    /** Nombre maximal de sommets qui seront transmis au Canvas, fermeture comprise. */
    public static int sampledPointCount(int sourcePointCount) {
        if (sourcePointCount <= 0) return 0;
        if (sourcePointCount == 1) return 1;
        int step = stride(sourcePointCount);
        int count = 1; // premier sommet
        for (int index = 1; index < sourcePointCount - 1; index += step) count++;
        count++; // dernier sommet, qui ferme l'anneau
        return count;
    }
}
