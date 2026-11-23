package com.example.gpstest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Filtrage et tri de la liste des satellites, comme dans l'application originale :
 *  - afficher TOUS les satellites ou seulement ceux UTILISÉS dans le fix
 *  - filtrer par constellation (GPS, GLONASS, GALILEO, ...)
 *  - trier : les satellites utilisés d'abord, puis par SNR décroissant
 *
 * Classe 100 % PURE : testable sans Android.
 */
public final class SatelliteFilter {

    public enum Mode {
        ALL("Tous"),
        IN_FIX("Utilisés"),
        GPS("GPS"),
        GLONASS("GLONASS"),
        GALILEO("Galileo"),
        BEIDOU("BeiDou"),
        QZSS("QZSS"),
        IRNSS("IRNSS"),
        SBAS("SBAS");

        public final String label;
        Mode(String label) { this.label = label; }
    }

    /** Ordre d'affichage dans le sélecteur. */
    public static final Mode[] DISPLAY_ORDER = {
            Mode.ALL, Mode.IN_FIX, Mode.GPS, Mode.GLONASS,
            Mode.GALILEO, Mode.BEIDOU, Mode.QZSS, Mode.IRNSS, Mode.SBAS
    };

    public static String[] labels() {
        String[] out = new String[DISPLAY_ORDER.length];
        for (int i = 0; i < DISPLAY_ORDER.length; i++) out[i] = DISPLAY_ORDER[i].label;
        return out;
    }

    private SatelliteFilter() { }

    /**
     * Filtre puis trie. Ne modifie pas la liste d'entrée.
     */
    public static List<SatInfo> apply(List<SatInfo> input, Mode mode) {
        List<SatInfo> out = new ArrayList<>();
        if (input == null) return out;
        for (SatInfo s : input) {
            if (matches(s, mode)) out.add(s);
        }
        sortForDisplay(out);
        return out;
    }

    public static boolean matches(SatInfo s, Mode mode) {
        if (mode == null || mode == Mode.ALL) return true;
        // L'écran de référence « satellites utilisés » ne montre que les
        // valeurs C/N0 réellement rapportées (> 0), pas des barres artificielles.
        if (mode == Mode.IN_FIX) return s.inFix && Float.isFinite(s.cn0) && s.cn0 > 0f;
        return s.constellation != null && s.constellation.equalsIgnoreCase(mode.label)
                && Float.isFinite(s.cn0) && s.cn0 > 0f;
    }

    /**
     * Tri d'affichage :
     *   1) satellites UTILISÉS dans le fix en premier
     *   2) puis SNR décroissant (le meilleur signal en tête)
     *   3) puis constellation, puis numéro (ordre stable et lisible)
     */
    public static void sortForDisplay(List<SatInfo> list) {
        Collections.sort(list, COMPARATOR);
    }

    private static final Comparator<SatInfo> COMPARATOR = new Comparator<SatInfo>() {
        @Override
        public int compare(SatInfo a, SatInfo b) {
            // 1) utilisés d'abord
            if (a.inFix != b.inFix) return a.inFix ? -1 : 1;
            // 2) SNR décroissant
            int cn0 = Float.compare(b.cn0, a.cn0);
            if (cn0 != 0) return cn0;
            // 3) constellation alphabétique
            String ca = a.constellation == null ? "" : a.constellation;
            String cb = b.constellation == null ? "" : b.constellation;
            int cmp = ca.compareTo(cb);
            if (cmp != 0) return cmp;
            // 4) numéro croissant
            return Integer.compare(a.svid, b.svid);
        }
    };

    /** Combien de satellites utilisés dans le fix ? */
    public static int countInFix(List<SatInfo> list) {
        int n = 0;
        if (list != null) for (SatInfo s : list) if (s.inFix) n++;
        return n;
    }

    /** Comptage par constellation, pour un résumé "GPS 8 · GALILEO 5 · GLONASS 4". */
    public static String constellationSummary(List<SatInfo> list) {
        if (list == null || list.isEmpty()) return "";
        String[] order = {"GPS", "GALILEO", "GLONASS", "BEIDOU", "QZSS", "IRNSS", "SBAS", "?"};
        StringBuilder sb = new StringBuilder();
        for (String c : order) {
            int total = 0, used = 0;
            for (SatInfo s : list) {
                if (c.equalsIgnoreCase(s.constellation)) {
                    total++;
                    if (s.inFix) used++;
                }
            }
            if (total == 0) continue;
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(String.format(java.util.Locale.FRANCE, "%s %d/%d", c, used, total));
        }
        return sb.toString();
    }
}
