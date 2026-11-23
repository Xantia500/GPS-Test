package com.example.gpstest;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Détection du support multi-fréquence de l'appareil.
 *
 * POURQUOI DEUX CHEMINS :
 *  - API 34+ : GnssCapabilities.getGnssSignalTypes() donne la réponse OFFICIELLE
 *              (liste des couples constellation / fréquence porteuse supportés).
 *  - API 30-33 : cette méthode n'existe pas. Il faut INFÉRER en observant si les
 *              satellites remontent une fréquence porteuse (GnssStatus.hasCarrierFrequencyHz, API 26).
 *  - Si rien n'est rapporté : on ne peut pas savoir. On l'affiche honnêtement
 *              plutôt que d'inventer.
 *
 * Classe 100 % PURE : testable sans Android.
 */
public final class BandSupport {

    private BandSupport() { }

    public static final class Profile {
        public final Set<String> bands;
        public final boolean unknown;
        public final String source;

        Profile(Set<String> bands, boolean unknown, String source) {
            this.bands = bands;
            this.unknown = unknown;
            this.source = source;
        }

        /** Deux bandes distinctes ou plus = récepteur multi-fréquence. */
        public boolean isMultiFrequency() {
            return !unknown && bands.size() >= 2;
        }

        public boolean hasL5() {
            return bands.contains("L5") || bands.contains("E5a") || bands.contains("E5");
        }

        public boolean hasBand(String b) {
            return bands.contains(b);
        }

        /** Phrase prête à afficher. */
        public String summary() {
            if (unknown || bands.isEmpty()) {
                return "Bandes : non rapportées par l'appareil";
            }
            StringBuilder sb = new StringBuilder("Bandes : ");
            boolean first = true;
            for (String b : bands) {
                if (!first) sb.append(" + ");
                sb.append(b);
                first = false;
            }
            if (bands.size() >= 2) sb.append("  (double fréquence)");
            else sb.append("  (bande unique)");
            return sb.toString();
        }

        /** Version courte pour une ligne d'info. */
        public String shortLabel() {
            if (unknown || bands.isEmpty()) return "?";
            StringBuilder sb = new StringBuilder();
            for (String b : bands) {
                if (sb.length() > 0) sb.append("+");
                sb.append(b);
            }
            return sb.toString();
        }
    }

    /** Profil inconnu (l'appareil ne rapporte rien). */
    public static Profile unknown(String source) {
        return new Profile(new LinkedHashSet<>(), true, source);
    }

    /**
     * Analyse une collection de fréquences porteuses (Hz).
     * @param carrierHz fréquences observées ou déclarées par le chipset
     * @param source    "API 34 (signal types)" ou "observé sur satellites"
     */
    public static Profile analyze(Collection<Double> carrierHz, String source) {
        Set<String> bands = new LinkedHashSet<>();
        if (carrierHz != null) {
            for (Double hz : carrierHz) {
                if (hz == null || hz.isNaN() || hz <= 0) continue;
                String band = RawMeasurementsFormatter.bandLabel(hz);
                if (!"?".equals(band)) bands.add(band);
            }
        }
        return new Profile(bands, bands.isEmpty(), source);
    }

    public static Profile analyze(Collection<Double> carrierHz) {
        return analyze(carrierHz, "observé sur satellites");
    }

    /**
     * Comptage par bande principale pour l'affichage : "L1 14 · L5 4".
     * Retourne null si aucune bande n'est connue.
     */
    public static String bandCounters(Collection<SatInfo> sats) {
        int l1 = 0, l5 = 0, other = 0;
        boolean anyCarrier = false;
        if (sats != null) {
            for (SatInfo s : sats) {
                if (!s.hasCarrier()) continue;
                anyCarrier = true;
                String b = s.band();
                if ("L1".equals(b)) l1++;
                else if ("L5".equals(b) || "E5a".equals(b) || "E5".equals(b)) l5++;
                else other++;
            }
        }
        if (!anyCarrier) return null;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.FRANCE, "L1 %d", l1));
        if (l5 > 0) sb.append(String.format(Locale.FRANCE, " · L5 %d", l5));
        if (other > 0) sb.append(String.format(Locale.FRANCE, " · autres %d", other));
        return sb.toString();
    }
}
