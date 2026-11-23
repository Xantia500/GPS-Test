package com.example.gpstest;

/**
 * Calculs astronomiques : lever/coucher du soleil et phase de lune.
 *
 * Algorithme solaire NOAA (précision ~1 minute aux latitudes tempérées).
 * Aucune dépendance : ni réseau, ni capteur, ni Android.
 */
public final class Astro {

    private Astro() { }

    public static final class SunTimes {
        public boolean polarDay = false;    // soleil ne se couche pas
        public boolean polarNight = false;  // soleil ne se lève pas
        public long sunriseUtcMs = 0;
        public long sunsetUtcMs = 0;
        public double declinationDeg = 0;   // déclinaison solaire
        public double elevationDeg = 0;     // hauteur actuelle du soleil
        public double azimuthDeg = 0;       // azimut actuel (0=N, 90=E)

        public boolean valid() { return !polarDay && !polarNight && sunriseUtcMs != 0 && sunsetUtcMs != 0; }
    }

    /**
     * @param latDeg   latitude
     * @param lonDeg   longitude (est positif)
     * @param utcMillis instant de référence (heure UTC)
     */
    public static SunTimes compute(double latDeg, double lonDeg, long utcMillis) {
        SunTimes out = new SunTimes();

        java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        cal.setTimeInMillis(utcMillis);

        int dayOfYear = cal.get(java.util.Calendar.DAY_OF_YEAR);
        double hourUtc = cal.get(java.util.Calendar.HOUR_OF_DAY)
                + cal.get(java.util.Calendar.MINUTE) / 60.0;

        double gamma = 2.0 * Math.PI / 365.0 * (dayOfYear - 1 + (hourUtc - 12.0) / 24.0);

        // Équation du temps (minutes)
        double eqTime = 229.18 * (0.000075
                + 0.001868 * Math.cos(gamma)
                - 0.032077 * Math.sin(gamma)
                - 0.014615 * Math.cos(2 * gamma)
                - 0.040849 * Math.sin(2 * gamma));

        // Déclinaison (radians)
        double decl = 0.006918
                - 0.399912 * Math.cos(gamma)
                + 0.070257 * Math.sin(gamma)
                - 0.006758 * Math.cos(2 * gamma)
                + 0.000907 * Math.sin(2 * gamma)
                - 0.002697 * Math.cos(3 * gamma)
                + 0.00148 * Math.sin(3 * gamma);
        out.declinationDeg = Math.toDegrees(decl);

        double latRad = Math.toRadians(latDeg);
        // Hauteur du soleil maintenant
        double ha = solarHourAngle(utcMillis, eqTime);
        out.elevationDeg = Math.toDegrees(Math.asin(
                Math.sin(latRad) * Math.sin(decl)
                        + Math.cos(latRad) * Math.cos(decl) * Math.cos(ha)));
        out.azimuthDeg = Math.toDegrees(Math.atan2(
                -Math.sin(ha),
                Math.tan(decl) * Math.cos(latRad) - Math.sin(latRad) * Math.cos(ha)));
        if (out.azimuthDeg < 0) out.azimuthDeg += 360.0;

        // Angle horaire au lever/coucher (zénith 90.833° = réfraction + rayon solaire)
        double zenith = Math.toRadians(90.833);
        double cosOmega = (Math.cos(zenith) / (Math.cos(latRad) * Math.cos(decl)))
                - Math.tan(latRad) * Math.tan(decl);

        if (cosOmega > 1.0) {
            out.polarNight = true;
            return out;
        }
        if (cosOmega < -1.0) {
            out.polarDay = true;
            return out;
        }

        double omega = Math.toDegrees(Math.acos(cosOmega));

        // Minutes UTC depuis minuit
        double sunriseMinutes = 720.0 - 4.0 * (lonDeg + omega) - eqTime;
        double sunsetMinutes = 720.0 - 4.0 * (lonDeg - omega) - eqTime;

        long midnightUtc = utcMillis - (cal.get(java.util.Calendar.HOUR_OF_DAY) * 3600_000L
                + cal.get(java.util.Calendar.MINUTE) * 60_000L
                + cal.get(java.util.Calendar.SECOND) * 1000L);

        out.sunriseUtcMs = midnightUtc + Math.round(sunriseMinutes * 60_000.0);
        out.sunsetUtcMs = midnightUtc + Math.round(sunsetMinutes * 60_000.0);
        return out;
    }

    private static double solarHourAngle(long utcMillis, double eqTimeMinutes) {
        java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        cal.setTimeInMillis(utcMillis);
        double minutesUtc = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60.0
                + cal.get(java.util.Calendar.MINUTE)
                + cal.get(java.util.Calendar.SECOND) / 60.0;
        double trueSolarTime = minutesUtc + eqTimeMinutes;
        double haDeg = (trueSolarTime / 4.0) - 180.0;
        return Math.toRadians(haDeg);
    }

    // ==================================================================
    // Lune
    // ==================================================================

    private static final double SYNODIC_MONTH = 29.530588853;
    /** Nouvelle lune de référence : 2000-01-06 18:14 UTC */
    private static final long NEW_MOON_EPOCH = 947182440000L;

    /** Fraction du cycle lunaire : 0 = nouvelle lune, 0.5 = pleine lune */
    public static double moonPhaseFraction(long utcMillis) {
        double days = (utcMillis - NEW_MOON_EPOCH) / 86_400_000.0;
        double phase = (days / SYNODIC_MONTH) % 1.0;
        if (phase < 0) phase += 1.0;
        return phase;
    }

    /** Part éclairée du disque lunaire, 0..1 */
    public static double moonIllumination(double phaseFraction) {
        return (1.0 - Math.cos(2.0 * Math.PI * phaseFraction)) / 2.0;
    }

    public static String moonPhaseName(double phaseFraction) {
        if (phaseFraction < 0.0344 || phaseFraction >= 0.9656) return "Nouvelle lune";
        if (phaseFraction < 0.2159) return "Premier croissant";
        if (phaseFraction < 0.2841) return "Premier quartier";
        if (phaseFraction < 0.4656) return "Lune croissante";
        if (phaseFraction < 0.5344) return "Pleine lune";
        if (phaseFraction < 0.7159) return "Lune décroissante";
        if (phaseFraction < 0.7841) return "Dernier quartier";
        return "Dernier croissant";
    }

    // ==================================================================
    // Formatage heure locale à partir de l'UTC
    // ==================================================================

    public static String formatLocalTime(long utcMs, java.util.TimeZone tz) {
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE);
        fmt.setTimeZone(tz);
        return fmt.format(new java.util.Date(utcMs));
    }
}
