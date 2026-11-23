package com.example.gpstest;

/**
 * Datums géodésiques et transformations entre eux.
 *
 * POURQUOI C'EST NÉCESSAIRE :
 * Un GPS fournit des coordonnées dans WGS84. Un utilisateur qui compare avec une
 * carte papier locale (IGN en France, OS en Angleterre, USGS aux USA) obtient un
 * écart de 50 à 250 mètres si le datum n'est pas le même. L'écart vient de
 * l'ellipsoïde de référence ET du décalage de l'origine.
 *
 * MÉTHODE :
 *   geodetic -> ECEF (cartésien 3D) -> Helmert 7 paramètres -> ECEF -> geodetic
 * C'est la méthode standard (EPSG), exacte au décimètre près pour nos usages.
 *
 * Classe 100 % PURE : aucun import Android, donc testable sans téléphone.
 */
public final class Datum {

    public final String name;
    public final double a;      // demi grand axe (m)
    public final double invF;   // 1/f (aplatissement inverse)

    // Paramètres de Helmert, de ce datum VERS WGS84
    public final double dx, dy, dz;      // translations (m)
    public final double rx, ry, rz;      // rotations (secondes d'arc)
    public final double s;               // facteur d'échelle (ppm)

    private Datum(String name, double a, double invF,
                  double dx, double dy, double dz,
                  double rx, double ry, double rz, double s) {
        this.name = name;
        this.a = a;
        this.invF = invF;
        this.dx = dx; this.dy = dy; this.dz = dz;
        this.rx = rx; this.ry = ry; this.rz = rz;
        this.s = s;
    }

    // ------------------------------------------------------------------
    // Les datums proposés. Liste volontairement courte et utile :
    // Europe, Amérique du Nord, Australie, mondial.
    // ------------------------------------------------------------------
    public static final Datum WGS84 = new Datum("WGS 84 (GPS)",
            6378137.0, 298.257223563, 0, 0, 0, 0, 0, 0, 0);

    public static final Datum ETRS89 = new Datum("ETRS89 (Europe)",
            6378137.0, 298.257222101, 0, 0, 0, 0, 0, 0, 0);

    public static final Datum NAD83 = new Datum("NAD83 (Amérique du Nord)",
            6378137.0, 298.257222101,
            1.004, -1.910, -0.515, 0.0267, 0.00034, 0.011, -0.0015);

    public static final Datum ED50 = new Datum("ED50 (Europe, ancien)",
            6378388.0, 297.0,
            89.5, 93.8, 123.1, 0.0, 0.0, 0.156, 1.2);

    public static final Datum NAD27 = new Datum("NAD27 (Amérique N., ancien)",
            6378206.4, 294.978698214,
            -8.0, 160.0, 176.0, 0, 0, 0, 0);

    public static final Datum OSGB36 = new Datum("OSGB36 (Royaume-Uni)",
            6377563.396, 299.3249646,
            -446.448, 125.157, -542.060, 0.1502, 0.2470, 0.8421, -20.4894);

    public static final Datum AGD66 = new Datum("AGD66 (Australie)",
            6378160.0, 298.25,
            -117.763, -51.510, 139.061, -0.292, -0.443, -0.277, -0.191);

    public static final Datum AGD84 = new Datum("AGD84 (Australie)",
            6378160.0, 298.25,
            -134.0, -48.0, 149.0, -0.15, -0.02, -0.69, -0.79);

    public static final Datum SAD69 = new Datum("SAD69 (Amérique du Sud)",
            6378160.0, 298.25,
            -57.0, 1.0, -41.0, 0, 0, 0, 0);

    public static final Datum[] ALL = {
            WGS84, ETRS89, NAD83, ED50, NAD27, OSGB36, AGD66, AGD84, SAD69
    };

    public static Datum byName(String name) {
        for (Datum d : ALL) {
            if (d.name.equals(name)) return d;
        }
        return WGS84;
    }

    // ------------------------------------------------------------------
    // Conversions
    // ------------------------------------------------------------------

    /**
     * Coordonnée dans ce datum -> coordonnée WGS84.
     * @return double[]{lat, lon, hauteurEllipsoidale}
     */
    public double[] toWgs84(double lat, double lon, double h) {
        if (isIdentity()) return new double[]{lat, lon, h};
        double[] ecef = geodeticToEcef(lat, lon, h, a, invF);
        double[] sh = helmertTrustedToTarget(ecef);
        return ecefToGeodetic(sh, 6378137.0, 298.257223563);
    }

    /**
     * Coordonnée WGS84 -> coordonnée dans ce datum.
     */
    public double[] fromWgs84(double lat, double lon, double h) {
        if (isIdentity()) return new double[]{lat, lon, h};
        double[] ecef = geodeticToEcef(lat, lon, h, 6378137.0, 298.257223563);
        double[] inv = helmertTargetToTrusted(ecef);
        return ecefToGeodetic(inv, a, invF);
    }

    public boolean isIdentity() {
        return dx == 0 && dy == 0 && dz == 0 && rx == 0 && ry == 0 && rz == 0 && s == 0;
    }

    // ------------------------------------------------------------------
    // Brique 1 : géodésique -> cartésien (ECEF)
    // ------------------------------------------------------------------
    public static double[] geodeticToEcef(double latDeg, double lonDeg, double h,
                                          double a, double invF) {
        double f = 1.0 / invF;
        double e2 = f * (2.0 - f);
        double lat = Math.toRadians(latDeg);
        double lon = Math.toRadians(lonDeg);
        double sinLat = Math.sin(lat);
        double cosLat = Math.cos(lat);
        double n = a / Math.sqrt(1.0 - e2 * sinLat * sinLat);
        double x = (n + h) * cosLat * Math.cos(lon);
        double y = (n + h) * cosLat * Math.sin(lon);
        double z = (n * (1.0 - e2) + h) * sinLat;
        return new double[]{x, y, z};
    }

    // ------------------------------------------------------------------
    // Brique 2 : cartésien -> géodésique (itératif, converge en 4-5 tours)
    // ------------------------------------------------------------------
    public static double[] ecefToGeodetic(double[] ecef, double a, double invF) {
        double x = ecef[0], y = ecef[1], z = ecef[2];
        double f = 1.0 / invF;
        double e2 = f * (2.0 - f);
        double lon = Math.atan2(y, x);
        double p = Math.hypot(x, y);
        double lat = Math.atan2(z, p * (1.0 - e2));
        double h = 0;
        for (int i = 0; i < 8; i++) {
            double sinLat = Math.sin(lat);
            double n = a / Math.sqrt(1.0 - e2 * sinLat * sinLat);
            h = p / Math.cos(lat) - n;
            lat = Math.atan2(z + e2 * n * sinLat, p);
        }
        return new double[]{Math.toDegrees(lat), Math.toDegrees(lon), h};
    }

    // ------------------------------------------------------------------
    // Brique 3 : Helmert 7 paramètres (convention "position vector")
    // ------------------------------------------------------------------
    private double[][] rotationMatrix() {
        double as = Math.PI / 180.0 / 3600.0; // arcsec -> radians
        double ex = rx * as, ey = ry * as, ez = rz * as;
        return new double[][]{
                {1.0,  ez, -ey},
                {-ez, 1.0,  ex},
                {ey, -ex, 1.0}
        };
    }

    /** ECEF exprimé dans CE datum -> ECEF exprimé en WGS84 */
    private double[] helmertTrustedToTarget(double[] src) {
        double[][] r = rotationMatrix();
        double k = 1.0 + s * 1e-6;
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = r[i][0] * src[0] + r[i][1] * src[1] + r[i][2] * src[2];
            out[i] = k * v + (i == 0 ? dx : i == 1 ? dy : dz);
        }
        return out;
    }

    /** ECEF WGS84 -> ECEF dans CE datum (transformation inverse approximée) */
    private double[] helmertTargetToTrusted(double[] src) {
        double[][] r = rotationMatrix();
        double k = 1.0 + s * 1e-6;
        double[] t = {src[0] - dx, src[1] - dy, src[2] - dz};
        double[] out = new double[3];
        // inverse de R = transposée (matrice orthogonale au 1er ordre)
        for (int i = 0; i < 3; i++) {
            double v = r[0][i] * t[0] + r[1][i] * t[1] + r[2][i] * t[2];
            out[i] = v / k;
        }
        return out;
    }
}
