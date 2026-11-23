package com.example.gpstest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calcul DOP géométrique sur les satellites réellement utilisés par le fix.
 *
 * Le modèle introduit un biais d'horloge par constellation : trois inconnues
 * de position + k paramètres d'horloge (k = nombre de constellations distinctes).
 * Pour une seule constellation, on retrouve le modèle GNSS classique 4x4.
 * Ce calcul reste une estimation à partir du statut Android, pas une copie
 * exacte du solveur interne du récepteur (pondération, exclusions et biais
 * inter-systèmes propriétaires peuvent différer). Le calcul est non pondéré :
 * le C/N0 sert à écarter les mesures nulles/hors domaine, pas à donner un
 * poids statistique à chaque satellite. Les résultats sont des indicateurs
 * géométriques, pas une prédiction directe de l'erreur en mètres.
 */
public final class DopCalculator {
    private DopCalculator() { }

    public static final class Dop {
        public final double gdop, pdop, hdop, vdop, tdop;
        public final int satelliteCount;
        public final int constellationCount;

        Dop(double gdop, double pdop, double hdop, double vdop, double tdop,
            int satelliteCount, int constellationCount) {
            this.gdop = gdop;
            this.pdop = pdop;
            this.hdop = hdop;
            this.vdop = vdop;
            this.tdop = tdop;
            this.satelliteCount = satelliteCount;
            this.constellationCount = constellationCount;
        }

        /** Compatibilité avec les anciens tests/consommateurs. */
        Dop(double gdop, double pdop, double hdop, double vdop, double tdop, int n) {
            this(gdop, pdop, hdop, vdop, tdop, n, 1);
        }

        public String format() {
            return String.format(java.util.Locale.FRANCE,
                    "H %.1f · V %.1f · P %.1f · G %.1f", hdop, vdop, pdop, gdop);
        }
        public String quality() { return NmeaParser.dopQuality(hdop); }
    }

    public static Dop compute(List<SatInfo> satellites) {
        return computeFromUsable(satellites);
    }

    /**
     * Calcule le DOP à partir des seuls satellites réellement utilisés.
     * La méthode revalide elle-même ses entrées : elle reste sûre même si un
     * appelant lui fournit une liste non filtrée ou des données synthétiques.
     * Un même SV (constellation + SVID) n'est compté qu'une fois ; si un
     * fournisseur le duplique, on conserve l'entrée utilisée la plus forte
     * en C/N0 afin de ne pas lui donner artificiellement deux poids.
     */
    public static Dop computeFromUsable(List<SatInfo> usable) {
        if (usable == null || usable.isEmpty()) return null;

        Map<String, SatInfo> uniqueSatellites = new LinkedHashMap<>();
        for (SatInfo s : usable) {
            if (s == null || !s.usableForDop() || !finiteGeometry(s)) continue;
            String system = systemName(s);
            String key = system + "#" + s.svid;
            SatInfo previous = uniqueSatellites.get(key);
            if (previous == null || s.cn0 > previous.cn0) uniqueSatellites.put(key, s);
        }
        List<SatInfo> sats = new ArrayList<>(uniqueSatellites.values());
        if (sats.size() < 4) return null;

        // Ordre stable, important pour que le calcul soit reproductible.
        Map<String, Integer> systems = new LinkedHashMap<>();
        for (SatInfo s : sats) {
            String name = systemName(s);
            if (!systems.containsKey(name)) systems.put(name, systems.size());
        }
        int k = systems.size();
        int n = sats.size();
        int unknowns = 3 + k;
        if (k == 0 || n < unknowns) return null;

        double[][] g = new double[n][unknowns];
        for (int i = 0; i < n; i++) {
            SatInfo s = sats.get(i);
            double el = Math.toRadians(s.elevation);
            double normalizedAzimuth = s.azimuth >= 360.0f ? 0.0 : s.azimuth;
            double az = Math.toRadians(normalizedAzimuth);
            g[i][0] = -Math.cos(el) * Math.sin(az); // Est
            g[i][1] = -Math.cos(el) * Math.cos(az); // Nord
            g[i][2] = -Math.sin(el);                // Haut
            String name = systemName(s);
            Integer clockColumn = systems.get(name);
            if (clockColumn == null) return null;
            g[i][3 + clockColumn] = 1.0;
        }

        double[][] normal = new double[unknowns][unknowns];
        for (int a = 0; a < unknowns; a++) {
            for (int b = 0; b < unknowns; b++) {
                double sum = 0;
                for (int i = 0; i < n; i++) sum += g[i][a] * g[i][b];
                normal[a][b] = sum;
            }
        }
        double[][] q = invert(normal);
        if (q == null) return null;

        double h2 = q[0][0] + q[1][1];
        double v2 = q[2][2];
        double p2 = h2 + v2;
        double t2 = 0;
        for (int i = 3; i < unknowns; i++) t2 += q[i][i];
        double g2 = p2 + t2;
        if (!Double.isFinite(h2) || !Double.isFinite(v2) || h2 < 0 || v2 < 0
                || !Double.isFinite(g2) || g2 < 0) return null;
        return new Dop(Math.sqrt(g2), Math.sqrt(p2), Math.sqrt(h2),
                Math.sqrt(v2), Math.sqrt(t2), n, k);
    }

    private static String systemName(SatInfo s) {
        return s.constellation == null || s.constellation.trim().isEmpty()
                ? "UNKNOWN" : s.constellation.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static boolean finiteGeometry(SatInfo s) {
        return s != null && Double.isFinite(s.azimuth) && s.azimuth >= 0 && s.azimuth <= 360
                && Double.isFinite(s.elevation) && s.elevation > 0 && s.elevation <= 90;
    }

    /** Inversion générale par Gauss-Jordan avec pivot partiel. */
    static double[][] invert(double[][] a) {
        if (a == null || a.length == 0) return null;
        int n = a.length;
        for (double[] row : a) if (row == null || row.length != n) return null;
        double[][] m = new double[n][2 * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (!Double.isFinite(a[i][j])) return null;
                m[i][j] = a[i][j];
            }
            m[i][i + n] = 1.0;
        }
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(m[r][col]) > Math.abs(m[pivot][col])) pivot = r;
            }
            if (Math.abs(m[pivot][col]) < 1e-12) return null;
            if (pivot != col) { double[] tmp = m[pivot]; m[pivot] = m[col]; m[col] = tmp; }
            double pv = m[col][col];
            for (int j = 0; j < 2 * n; j++) m[col][j] /= pv;
            for (int r = 0; r < n; r++) {
                if (r == col) continue;
                double f = m[r][col];
                if (f == 0) continue;
                for (int j = 0; j < 2 * n; j++) m[r][j] -= f * m[col][j];
            }
        }
        double[][] out = new double[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) out[i][j] = m[i][j + n];
        return out;
    }

    /** Compatibilité avec les tests historiques. */
    static double[][] invert4(double[][] a) {
        return a != null && a.length == 4 ? invert(a) : null;
    }

    public static String compare(double receiverHdop, Dop calculated) {
        if (calculated == null) {
            return Double.isFinite(receiverHdop) && receiverHdop > 0
                    ? String.format(java.util.Locale.FRANCE, "récepteur %.1f · estimation n/d", receiverHdop)
                    : "récepteur n/d · estimation n/d";
        }
        String receiver = Double.isFinite(receiverHdop) && receiverHdop > 0
                ? String.format(java.util.Locale.FRANCE, "récepteur %.1f", receiverHdop)
                : "récepteur n/d";
        return String.format(java.util.Locale.FRANCE,
                "%s · géométrie inFix estimée %.1f", receiver, calculated.hdop);
    }
}
