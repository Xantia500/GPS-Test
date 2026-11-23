package com.example.gpstest;

/**
 * MODES DE THÈME — logique PURE, aucun import Android, testable hors appareil.
 *
 * ══════════════════════════════════════════════════════════════════════
 * POURQUOI CETTE CLASSE EST SÉPARÉE DE ThemeManager
 * ══════════════════════════════════════════════════════════════════════
 *
 * C'est la même leçon que DestPresets/Destination, et je l'avais déjà
 * apprise dans ce projet avant de la répéter : une classe qui contient à la
 * fois une RÈGLE et un EFFET ANDROID ne peut pas être testée. Résultat, la
 * règle — la seule partie qui a des cas limites — n'est vérifiée par rien.
 *
 *   ThemeMode    -> données et décisions pures      (ce fichier, testé)
 *   ThemeManager -> effets Android (Configuration, Resources)
 *
 * Les cas limites qui comptent vraiment vivent ici : une préférence
 * absente, une valeur corrompue, une casse différente, une chaîne hostile.
 */
public final class ThemeMode {

    private ThemeMode() { }

    /**
     * Les trois modes proposés à l'utilisateur.
     *
     * L'ORDRE COMPTE : SYSTEM est en premier parce que c'est le défaut, et
     * que l'interface propose les boutons dans l'ordre de valeurs().
     */
    public enum Mode {
        SYSTEM("system", "Suivre le téléphone"),
        LIGHT("light",   "Clair"),
        DARK("dark",     "Sombre");

        /**
         * IDENTIFIANT PERSISTÉ. Ne le changez JAMAIS dans une version future :
         * il est écrit sur le disque de l'utilisateur. Le modifier ferait
         * perdre son réglage à quiconque l'a déjà choisi.
         */
        public final String id;

        /** Libellé affiché — peut être traduit sans aucun risque. */
        public final String label;

        Mode(String id, String label) {
            this.id = id;
            this.label = label;
        }

        /** Vrai pour le mode qui ne force rien. */
        public boolean isSystem() {
            return this == SYSTEM;
        }

        /** Vrai pour les modes qui imposent une apparence. */
        public boolean isForced() {
            return this == LIGHT || this == DARK;
        }

        /**
         * Vrai si ce mode impose le SOMBRE.
         * Faux pour LIGHT (impose le clair) et pour SYSTEM (n'impose rien).
         */
        public boolean forcesDark() {
            return this == DARK;
        }

        /**
         * Lit un mode persisté.
         *
         * Ne lève JAMAIS : une préférence absente, vide, corrompue ou écrite
         * par une version future donne SYSTEM — le défaut qui suit le
         * téléphone, donc celui qui ne surprend personne.
         */
        public static Mode fromId(String id) {
            if (id != null) {
                for (Mode m : values()) {
                    if (m.id.equals(id)) return m;
                }
            }
            return SYSTEM;
        }
    }
}
