package com.example.gpstest;

import java.util.Locale;

/**
 * UNITÉS D'AFFICHAGE — mètres, kilomètres, miles, pieds, nœuds.
 *
 * ══════════════════════════════════════════════════════════════════════
 * RÈGLE DE CONCEPTION : LE MOTEUR NE CONNAÎT QUE LE SI
 * ══════════════════════════════════════════════════════════════════════
 *
 * Toutes les mesures circulent en unités SI : mètres, mètres par seconde,
 * degrés. La conversion n'a lieu QU'À L'AFFICHAGE. Deux raisons :
 *
 *   1. le GPX et les calculs (distance, vitesse implicite, DOP) exigent une
 *      unité unique — mélanger miles et mètres dans le même calcul est une
 *      source de bugs silencieux ;
 *   2. changer d'unité ne doit RIEN recalculer : c'est un formatage.
 *
 * C'est pour cette raison que la classe est PURE et séparée : la conversion
 * est testable, l'affichage ne l'est pas.
 *
 * ══════════════════════════════════════════════════════════════════════
 * LE PIÈGE DES ARRONDIS — pourquoi on n'écrit pas « %.1f m » partout
 * ══════════════════════════════════════════════════════════════════════
 *
 * « Précision : 0,0 m » n'existe pas physiquement ; « Précision : 3,0 m »
 * avec une décimale sur une valeur de 2,96 m est du bruit d'affichage.
 * Chaque quantité a donc sa règle de précision :
 *
 *   - précision du fix : entier, en mètres (c'est déjà une estimation) ;
 *   - altitude : entier, en mètres ou pieds ;
 *   - distance : 1 décimale sous 10 km, entier au-delà ;
 *   - vitesse : 1 décimale.
 */
public final class Units {

    private Units() { }

    /** Système d'unités choisi par l'utilisateur. */
    public enum System {
        /** Mètres, kilomètres, km/h. Défaut : la France. */
        METRIC("metric", "Métrique", "m", "km", "km/h"),
        /** Pieds, milles, mph. */
        IMPERIAL("imperial", "Impérial", "ft", "mi", "mph"),
        /** Mètres pour la distance courte, milles marins, nœuds. */
        NAUTICAL("nautical", "Maritime", "m", "NM", "kn");

        public final String id;
        public final String label;
        public final String shortUnit;      // précision, petite distance
        public final String longUnit;       // distance de trace
        public final String speedUnit;

        System(String id, String label, String shortUnit, String longUnit, String speedUnit) {
            this.id = id;
            this.label = label;
            this.shortUnit = shortUnit;
            this.longUnit = longUnit;
            this.speedUnit = speedUnit;
        }

        /** Lecture d'une préférence. Ne lève jamais. */
        public static System fromId(String id) {
            if (id != null) {
                for (System s : values()) {
                    if (s.id.equals(id)) return s;
                }
            }
            return METRIC;
        }
    }

    // ------------------------------------------------------------------
    // Constantes de conversion (facteurs exacts, par définition)
    // ------------------------------------------------------------------

    public static final double METERS_PER_FOOT = 0.3048;              // exact
    public static final double METERS_PER_MILE = 1609.344;            // exact
    public static final double METERS_PER_NAUTICAL_MILE = 1852.0;     // exact
    public static final double MPS_TO_KMH = 3.6;                      // exact
    public static final double MPS_TO_MPH = 3600.0 / METERS_PER_MILE;
    public static final double MPS_TO_KNOT = 3600.0 / METERS_PER_NAUTICAL_MILE;

    // ------------------------------------------------------------------
    // Conversions brutes (pures, testables)
    // ------------------------------------------------------------------

    public static double metersToFeet(double m) { return m / METERS_PER_FOOT; }
    public static double metersToMiles(double m) { return m / METERS_PER_MILE; }
    public static double metersToNm(double m) { return m / METERS_PER_NAUTICAL_MILE; }
    public static double mpsToKmh(double v) { return v * MPS_TO_KMH; }
    public static double mpsToMph(double v) { return v * MPS_TO_MPH; }
    public static double mpsToKnots(double v) { return v * MPS_TO_KNOT; }

    // ------------------------------------------------------------------
    // Formattage
    // ------------------------------------------------------------------

    /**
     * Précision / exactitude d'un fix.
     *
     * Arrondi à l'ENTIER : une précision de « 3,4 m » suggère une finesse
     * que le GNSS civil n'a pas. 3 m suffit, et n'induit personne en erreur.
     */
    public static String formatAccuracy(System sys, double meters) {
        if (Double.isNaN(meters)) return "—";
        if (sys == System.IMPERIAL) {
            return String.format(Locale.FRANCE, "%.0f ft", metersToFeet(meters));
        }
        return String.format(Locale.FRANCE, "%.0f m", meters);
    }

    /** Altitude : signe explicite, entier. */
    public static String formatAltitude(System sys, double meters) {
        if (Double.isNaN(meters)) return "—";
        if (sys == System.IMPERIAL) {
            return String.format(Locale.FRANCE, "%.0f ft", metersToFeet(meters));
        }
        return String.format(Locale.FRANCE, "%.0f m", meters);
    }

    /**
     * Petite distance (écart de précision, rayon de doute).
     * En maritime et métrique : mètres. En impérial : pieds.
     */
    public static String formatShortDistance(System sys, double meters) {
        if (Double.isNaN(meters)) return "—";
        if (sys == System.IMPERIAL) {
            return String.format(Locale.FRANCE, "%.0f ft", metersToFeet(meters));
        }
        return String.format(Locale.FRANCE, "%.0f m", meters);
    }

    /**
     * Distance de trace : 1 décimale sous 10 unités, entier au-delà.
     * « 3,4 km » est utile ; « 1 234,7 km » ne l'est pas.
     */
    public static String formatDistance(System sys, double meters) {
        // Une distance négative ou non finie n'est pas une mesure exploitable.
        // Ne pas afficher une valeur incohérente (par exemple « -12 m » ou « ∞ km »).
        if (!Double.isFinite(meters) || meters < 0.0) return "—";
        // Normalise aussi -0.0 : une distance nulle doit rester « 0 m ».
        if (meters == 0.0) meters = 0.0;

        // ── SOUS UNE UNITÉ, ON RESTE EN MÈTRES (corrigé v5.13) ─────────
        //
        // Avant, tout passait par les kilomètres (ou les miles, ou les
        // nautiques) : une trace de 20 m s'affichait « 0,0 km ». Ce n'est
        // pas un arrondi malheureux, c'est une valeur réelle présentée
        // comme nulle — le même défaut que le DOP affiché « — ». Un outil
        // de mesure qui affiche zéro pour vingt mètres fait douter de tout
        // le reste de ses chiffres.
        //
        // Sous l'unité principale, on garde donc l'unité de base : mètres,
        // pieds, ou (pour le nautique) rien de plus petit que le mille —
        // les navigateurs comptent en milles et en dixièmes de mille, pas
        // en mètres, donc pour le nautique on garde le dixième de mille.
        if (sys == System.METRIC) {
            if (Math.abs(meters) < 1000.0) {
                return String.format(Locale.FRANCE, "%.0f m", meters);
            }
            double km = meters / 1000.0;
            return km < 10.0
                    ? String.format(Locale.FRANCE, "%.1f km", km)
                    : String.format(Locale.FRANCE, "%.0f km", km);
        }
        if (sys == System.IMPERIAL) {
            if (Math.abs(meters) < METERS_PER_MILE) {
                return String.format(Locale.FRANCE, "%.0f ft", metersToFeet(meters));
            }
            double mi = metersToMiles(meters);
            return mi < 10.0
                    ? String.format(Locale.FRANCE, "%.1f mi", mi)
                    : String.format(Locale.FRANCE, "%.0f mi", mi);
        }
        // NAUTICAL : le mille marin et ses dixièmes.
        double nm = metersToNm(meters);
        return nm < 10.0
                ? String.format(Locale.FRANCE, "%.1f NM", nm)
                : String.format(Locale.FRANCE, "%.0f NM", nm);
    }

    /** Vitesse : une décimale, toujours. */
    public static String formatSpeed(System sys, double mps) {
        if (Double.isNaN(mps)) return "—";
        switch (sys) {
            case IMPERIAL: return String.format(Locale.FRANCE, "%.1f mph", mpsToMph(mps));
            case NAUTICAL: return String.format(Locale.FRANCE, "%.1f kn", mpsToKnots(mps));
            case METRIC:
            default:       return String.format(Locale.FRANCE, "%.1f km/h", mpsToKmh(mps));
        }
    }

    /** Unité de vitesse seule, pour les libellés. */
    public static String speedUnit(System sys) { return sys.speedUnit; }
}
