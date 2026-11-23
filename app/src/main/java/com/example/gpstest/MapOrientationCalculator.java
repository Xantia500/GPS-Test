package com.example.gpstest;

/** Conventions de rotation communes aux moteurs cartographiques. */
public final class MapOrientationCalculator {
    private MapOrientationCalculator() { }

    /** Rotation du fond nécessaire pour placer le cap de l'appareil vers le haut. */
    public static float headingUpRotation(float headingDegrees) {
        if (!Float.isFinite(headingDegrees)) return Float.NaN;
        return Compass.normalize(-headingDegrees);
    }
}
