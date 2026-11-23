package com.example.gpstest;

/** Calcul pur du niveau de zoom nécessaire pour faire tenir une emprise. */
public final class MapZoomCalculator {
    private static final double MAX_MERCATOR_LAT = 85.05112878;
    private MapZoomCalculator() { }

    public static byte fit(double minLat, double maxLat, double westLon, double eastLon,
                           int widthPx, int heightPx, int tileSizePx, int paddingPx,
                           byte minZoom, byte maxZoom) {
        if (!Double.isFinite(minLat) || !Double.isFinite(maxLat)
                || !Double.isFinite(westLon) || !Double.isFinite(eastLon)
                || minLat < -90 || maxLat > 90 || minLat > maxLat
                || eastLon <= westLon || widthPx <= 0 || heightPx <= 0
                || tileSizePx <= 0 || minZoom > maxZoom) {
            return minZoom;
        }
        double lonFraction = Math.min(1.0, (eastLon - westLon) / 360.0);
        double north = mercatorY(Math.max(-MAX_MERCATOR_LAT, Math.min(MAX_MERCATOR_LAT, maxLat)));
        double south = mercatorY(Math.max(-MAX_MERCATOR_LAT, Math.min(MAX_MERCATOR_LAT, minLat)));
        double latFraction = Math.max(0.0, south - north);
        int availableWidth = Math.max(1, widthPx - 2 * Math.max(0, paddingPx));
        int availableHeight = Math.max(1, heightPx - 2 * Math.max(0, paddingPx));
        double scaleX = lonFraction < 1e-12 ? Double.POSITIVE_INFINITY
                : availableWidth / (tileSizePx * lonFraction);
        double scaleY = latFraction < 1e-12 ? Double.POSITIVE_INFINITY
                : availableHeight / (tileSizePx * latFraction);
        double scale = Math.min(scaleX, scaleY);
        if (!Double.isFinite(scale)) return maxZoom;
        int zoom = (int) Math.floor(Math.log(Math.max(1.0, scale)) / Math.log(2.0));
        return (byte) Math.max(minZoom, Math.min(maxZoom, zoom));
    }

    private static double mercatorY(double latitude) {
        double radians = Math.toRadians(latitude);
        double sin = Math.sin(radians);
        return 0.5 - Math.log((1.0 + sin) / (1.0 - sin)) / (4.0 * Math.PI);
    }
}
