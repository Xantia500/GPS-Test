package com.example.gpstest;

/**
 * Projection UTM (Universal Transverse Mercator) — formules standard Snyder.
 * Classe 100 % PURE : testable sans Android.
 */
public final class Utm {

    private static final double K0 = 0.9996;

    public static final class Result {
        public final int zone;
        public final char band;
        public final double easting;
        public final double northing;
        public final boolean south;
        public final String centralMeridian;

        Result(int zone, char band, double easting, double northing, boolean south) {
            this.zone = zone;
            this.band = band;
            this.easting = easting;
            this.northing = northing;
            this.south = south;
            this.centralMeridian = String.format(java.util.Locale.FRANCE, "%d\u00B0%s",
                    Math.abs(zone * 6 - 183),
                    (zone * 6 - 183) >= 0 ? "E" : "O");
        }

        public String format() {
            return String.format(java.util.Locale.FRANCE,
                    "%d%c  %.1f E  %.1f N", zone, band, easting, northing);
        }
    }

    private Utm() { }

    /** Bande de latitude (C..X, sans I et O). */
    public static char bandLetter(double latDeg) {
        // bandes de 8° à partir de -80°
        int index = (int) Math.floor((Math.max(-80, Math.min(84, latDeg)) + 80.0) / 8.0);
        index = Math.max(0, Math.min(19, index));
        String bands = "CDEFGHJKLMNPQRSTUVWXX"; // X fait 12° (72-84) : doublé
        return bands.charAt(index);
    }

    /**
     * WGS84 (ou ellipsoïde du datum fourni) -> UTM.
     */
    public static Result fromLatLon(double latDeg, double lonDeg, Datum datum) {
        // Zone
        double zoneF = Math.floor((lonDeg + 180.0) / 6.0) + 1.0;
        int zone = (int) zoneF;
        if (zone < 1) zone = 1;
        if (zone > 60) zone = 60;
        // Cas particulier : sud de la Norvège / Svalbard
        if (latDeg >= 56.0 && latDeg < 64.0 && lonDeg >= 3.0 && lonDeg < 12.0) zone = 32;
        if (latDeg >= 72.0 && latDeg < 84.0) {
            if (lonDeg >= 0.0 && lonDeg < 9.0) zone = 31;
            else if (lonDeg >= 9.0 && lonDeg < 21.0) zone = 33;
            else if (lonDeg >= 21.0 && lonDeg < 33.0) zone = 35;
            else if (lonDeg >= 33.0 && lonDeg < 42.0) zone = 37;
        }

        double lon0 = (zone - 1) * 6.0 - 180.0 + 3.0;

        double a = datum.a;
        double f = 1.0 / datum.invF;
        double e2 = f * (2.0 - f);
        double ep2 = e2 / (1.0 - e2);

        double lat = Math.toRadians(latDeg);
        double lon = Math.toRadians(lonDeg);
        double lonOrigin = Math.toRadians(lon0);

        double n = a / Math.sqrt(1.0 - e2 * Math.sin(lat) * Math.sin(lat));
        double t = Math.tan(lat) * Math.tan(lat);
        double c = ep2 * Math.cos(lat) * Math.cos(lat);
        double aa = Math.cos(lat) * (lon - lonOrigin);

        double m = a * (
                (1.0 - e2 / 4.0 - 3.0 * e2 * e2 / 64.0 - 5.0 * Math.pow(e2, 3) / 256.0) * lat
                        - (3.0 * e2 / 8.0 + 3.0 * e2 * e2 / 32.0 + 45.0 * Math.pow(e2, 3) / 1024.0) * Math.sin(2.0 * lat)
                        + (15.0 * e2 * e2 / 256.0 + 45.0 * Math.pow(e2, 3) / 1024.0) * Math.sin(4.0 * lat)
                        - (35.0 * Math.pow(e2, 3) / 3072.0) * Math.sin(6.0 * lat));

        double x = K0 * n * (aa
                + (1.0 - t + c) * Math.pow(aa, 3) / 6.0
                + (5.0 - 18.0 * t + t * t + 72.0 * c - 58.0 * ep2) * Math.pow(aa, 5) / 120.0);

        double y = K0 * (m + n * Math.tan(lat) * (
                aa * aa / 2.0
                        + (5.0 - t + 9.0 * c + 4.0 * c * c) * Math.pow(aa, 4) / 24.0
                        + (61.0 - 58.0 * t + t * t + 600.0 * c - 330.0 * ep2) * Math.pow(aa, 6) / 720.0));

        double easting = x + 500000.0;
        double northing = y;
        boolean south = latDeg < 0;
        if (south) northing += 10000000.0;

        return new Result(zone, bandLetter(latDeg), easting, northing, south);
    }
}
