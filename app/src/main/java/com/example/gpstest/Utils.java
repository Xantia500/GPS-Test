package com.example.gpstest;

import java.util.Locale;

/**
 * Fonctions PURES : aucun import Android.
 * Conséquence : elles sont compilables et testables hors appareil (voir tests/UtilsTest.java).
 * Pour un débutant, c'est le meilleur moyen de vérifier la logique sans téléphone.
 */
public final class Utils {

    private Utils() { }

    // ------------------------------------------------------------------
    // Constellations GNSS.
    // Ce sont les valeurs officielles et stables de GnssStatus (API Android).
    // On les recopie en dur pour éviter un import Android dans cette classe.
    // ------------------------------------------------------------------
    private static final int C_UNKNOWN = 0;
    private static final int C_GPS     = 1;
    private static final int C_SBAS    = 2;
    private static final int C_GLONASS = 3;
    private static final int C_QZSS    = 4;
    private static final int C_BEIDOU  = 5;
    private static final int C_GALILEO = 6;
    private static final int C_IRNSS   = 7;

    public static String constellationName(int type) {
        switch (type) {
            case C_GPS:     return "GPS";
            case C_SBAS:    return "SBAS";
            case C_GLONASS: return "GLONASS";
            case C_QZSS:    return "QZSS";
            case C_BEIDOU:  return "BEIDOU";
            case C_GALILEO: return "GALILEO";
            case C_IRNSS:   return "IRNSS";
            default:        return "?";
        }
    }

    // ------------------------------------------------------------------
    // Formatage
    // ------------------------------------------------------------------

    /**
     * Coordonnée décimale -> degrés/minutes/secondes hémisphère.
     * Exemple : 48.8584, latitude -> 48°51'30,2"N
     */
    public static String formatDms(double value, boolean isLatitude) {
        String hemisphere;
        if (isLatitude) {
            hemisphere = (value >= 0) ? "N" : "S";
        } else {
            hemisphere = (value >= 0) ? "E" : "O";
        }
        double abs = Math.abs(value);
        int degrees = (int) abs;
        double remainderMinutes = (abs - degrees) * 60.0;
        int minutes = (int) remainderMinutes;
        double seconds = (remainderMinutes - minutes) * 60.0;
        return String.format(Locale.FRANCE, "%d\u00B0%02d'%04.1f\"%s",
                degrees, minutes, seconds, hemisphere);
    }

    /** Vitesse en m/s -> "4,3 km/h" */
    public static String formatKmh(float metersPerSecond) {
        return String.format(Locale.FRANCE, "%.1f km/h", metersPerSecond * 3.6f);
    }

    /** Cap en degrés -> "Nord-Ouest (312°)" */
    public static String formatBearing(float degrees) {
        String[] directions = {
                "Nord", "Nord-Nord-Est", "Nord-Est", "Est-Nord-Est",
                "Est", "Est-Sud-Est", "Sud-Est", "Sud-Sud-Est",
                "Sud", "Sud-Sud-Ouest", "Sud-Ouest", "Ouest-Sud-Ouest",
                "Ouest", "Ouest-Nord-Ouest", "Nord-Ouest", "Nord-Nord-Ouest"
        };
        int index = Math.round(degrees / 22.5f) & 15; // & 15 : 360° revient sur Nord
        return String.format(Locale.FRANCE, "%s (%03.0f\u00B0)", directions[index], degrees);
    }

    /** Distance en mètres -> "12,5 m" */
    public static String formatMeters(double meters) {
        return String.format(Locale.FRANCE, "%.1f m", meters);
    }

    /** Durée en ms -> "12,4 s" ou "01:05" au-delà d'une minute */
    public static String formatDuration(long milliseconds) {
        if (milliseconds < 60_000L) {
            return String.format(Locale.FRANCE, "%.1f s", milliseconds / 1000.0);
        }
        long totalSeconds = milliseconds / 1000L;
        return String.format(Locale.FRANCE, "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L);
    }

    // ------------------------------------------------------------------
    // Géométrie de la carte du ciel
    // ------------------------------------------------------------------

    /**
     * Convertit (azimut, élévation) en coordonnées écran dans un cercle.
     * Convention : azimut 0° = Nord en HAUT, 90° = Est à DROITE
     * (carte du ciel vue du dessus, comme sur un vrai récepteur).
     * Élévation 90° = zénith = centre du cercle ; 0° = horizon = bord.
     *
     * @return float[] {x, y}
     */
    public static float[] azElToXY(float azimuthDeg, float elevationDeg,
                                   float centerX, float centerY, float radius) {
        float clampedElevation = Math.max(0f, Math.min(90f, elevationDeg));
        float r = (90f - clampedElevation) / 90f * radius;
        double angle = Math.toRadians(azimuthDeg);
        float x = centerX + r * (float) Math.sin(angle);
        float y = centerY - r * (float) Math.cos(angle);
        return new float[]{x, y};
    }

    /**
     * Fraction de signal normalisée 0..1.
     * Le C/N0 d'un bon satellite tourne autour de 45-50 dBHz ; on plafonne à 50.
     */
    public static float snrFraction(float cn0DbHz) {
        return Math.max(0f, Math.min(1f, cn0DbHz / 50f));
    }
}
