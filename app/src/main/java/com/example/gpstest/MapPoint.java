package com.example.gpstest;

/** Petite coordonnée géographique indépendante de tout moteur cartographique. */
public final class MapPoint {
    public final double latitude;
    public final double longitude;

    public MapPoint(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90.0 || latitude > 90.0
                || longitude < -180.0 || longitude > 180.0) {
            throw new IllegalArgumentException("Coordonnée cartographique invalide");
        }
        this.latitude = latitude;
        this.longitude = longitude;
    }
}
