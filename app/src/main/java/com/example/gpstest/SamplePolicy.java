package com.example.gpstest;

/**
 * ESPACEMENT DES POINTS ENREGISTRÉS — décision pure, testable sans Android.
 *
 * ══════════════════════════════════════════════════════════════════════
 * POURQUOI CE N'EST PAS « 2 MÈTRES »
 * ══════════════════════════════════════════════════════════════════════
 *
 * Le document proposait un filtre fixe à 2 m. C'est très peu, et
 * l'utilisateur a raison : à 2 m, une marche à 5 km/h produit un point
 * toutes les 1,4 s — soit 2 500 points par heure, dont 90 % n'apportent
 * aucune information de forme.
 *
 * Pire, 2 m ne résout pas le vrai problème : **à l'arrêt**, un GNSS oscille
 * de ±2 à 5 m. Un seuil à 2 m est donc franchi en permanence par du bruit
 * pur. Il faut un seuil qui ne puisse pas être atteint par l'oscillation.
 *
 * ══════════════════════════════════════════════════════════════════════
 * DEUX CRITÈRES INDÉPENDANTS, ET POURQUOI ILS SONT INDÉPENDANTS
 * ══════════════════════════════════════════════════════════════════════
 *
 *   DISTANCE : évite d'accumuler du bruit à l'arrêt.
 *   TEMPS    : évite les trous. Sans critère de temps, quelqu'un qui reste
 *              immobile 20 minutes n'a AUCUN point dans le GPX. En rouvrant
 *              sa trace, il croit que l'enregistrement s'est arrêté, et le
 *              fichier devient illisible pour un lecteur qui attend un
 *              échantillonnage régulier.
 *
 *   → On écrit si l'une OU l'autre des conditions est remplie.
 *     C'est le point que la plupart des implémentations ratent : elles
 *     appliquent les deux en ET, et n'écrivent alors presque jamais quand
 *     l'utilisateur ralentit.
 *
 * ══════════════════════════════════════════════════════════════════════
 * LE PIÈGE DE L'ÉCONOMIE DE POINTS
 * ══════════════════════════════════════════════════════════════════════
 *
 * « Distance 500 m + temps 30 s » : un piéton à 4 km/h doit attendre
 * 7,5 minutes, mais le critère de temps le sauve à 30 s — tant qu'il
 * bouge ou qu'il reste immobile ? Non : le critère de temps s'applique
 * TOUJOURS. Donc à l'arrêt, on écrit un point toutes les 30 s. C'est
 * voulu : c'est ce qui permet de savoir que la trace continue.
 *
 * Et le cas opposé, celui qui coûte cher : distance 1 km + temps 10 min
 * sur une promenade de 800 m en 12 minutes — on écrit 3 points. La trace
 * existe, elle est juste grossière. C'est le choix de l'utilisateur, et
 * il doit pouvoir voir, dans l'écran expert, combien de points ont été
 * écartés.
 */
public final class SamplePolicy {

    private SamplePolicy() { }

    // ------------------------------------------------------------------
    // Bornes proposées dans les réglages
    // ------------------------------------------------------------------

    /** Distances proposées (mètres). 0 = écrire à chaque point valide. */
    public static final int[] DISTANCE_CHOICES = {
            0, 10, 25, 50, 100, 250, 500, 1000
    };

    /** Temps proposés (millisecondes). 0 = pas de critère de temps. */
    public static final long[] TIME_CHOICES = {
            0L,
            1_000L,     // 1 s
            5_000L,     // 5 s
            10_000L,    // 10 s
            30_000L,    // 30 s
            60_000L,    // 1 min
            120_000L,   // 2 min
            300_000L,   // 5 min
            600_000L    // 10 min
    };

    /** Valeurs par défaut : parcimonieuses mais sûres. */
    public static final int DEFAULT_MIN_DISTANCE_M = 25;
    public static final long DEFAULT_MIN_TIME_MS = 10_000L;

    /** Bornes de sécurité (acceptées depuis les préférences). */
    public static final int MIN_DISTANCE_FLOOR_M = 0;
    public static final int MIN_DISTANCE_CEIL_M = 5_000;
    public static final long MIN_TIME_FLOOR_MS = 0L;
    public static final long MIN_TIME_CEIL_MS = 3_600_000L;

    // ------------------------------------------------------------------
    // Décision
    // ------------------------------------------------------------------

    /** Résultat : écrire ou non, et pourquoi. */
    public static final class Verdict {
        public final boolean write;
        /** Vrai si le critère de DISTANCE a déclenché l'écriture. */
        public final boolean byDistance;
        /** Vrai si le critère de TEMPS a déclenché l'écriture. */
        public final boolean byTime;
        public final double distanceM;
        public final long elapsedMs;

        Verdict(boolean write, boolean byDistance, boolean byTime,
                double distanceM, long elapsedMs) {
            this.write = write;
            this.byDistance = byDistance;
            this.byTime = byTime;
            this.distanceM = distanceM;
            this.elapsedMs = elapsedMs;
        }
    }

    /**
     * Faut-il écrire ce point ?
     *
     * @param minDistanceM  seuil de distance (0 = pas de critère)
     * @param minTimeMs     seuil de temps (0 = pas de critère)
     * @param distanceM     distance depuis le dernier point ÉCRIT (NaN si
     *                      aucun précédent : on écrit alors toujours)
     * @param elapsedMs     temps depuis le dernier point ÉCRIT (< 0 si aucun)
     *
     * COMPORTEMENT QUAND LES DEUX SEUILS SONT À ZÉRO : on écrit tout.
     * C'est le comportement d'origine, et un utilisateur qui les met à zéro
     * demande explicitement « tout enregistrer » — y compris le bruit.
     */
    public static Verdict decide(int minDistanceM, long minTimeMs,
                                 double distanceM, long elapsedMs) {
        // Premier point d'une session ou après une coupure : toujours écrit.
        if (Double.isNaN(distanceM) || elapsedMs < 0) {
            return new Verdict(true, true, true, distanceM, elapsedMs);
        }

        boolean distanceOk = minDistanceM > 0 && distanceM >= minDistanceM;
        boolean timeOk = minTimeMs > 0 && elapsedMs >= minTimeMs;
        boolean noCriteria = minDistanceM <= 0 && minTimeMs <= 0;

        boolean write = noCriteria || distanceOk || timeOk;
        return new Verdict(write, distanceOk, timeOk, distanceM, elapsedMs);
    }

    /** Nettoie une valeur venue des préférences. */
    public static int sanitizeDistance(int meters) {
        if (meters < MIN_DISTANCE_FLOOR_M) return DEFAULT_MIN_DISTANCE_M;
        if (meters > MIN_DISTANCE_CEIL_M) return MIN_DISTANCE_CEIL_M;
        return meters;
    }

    /** Nettoie une valeur venue des préférences. */
    public static long sanitizeTime(long ms) {
        if (ms < MIN_TIME_FLOOR_MS) return DEFAULT_MIN_TIME_MS;
        if (ms > MIN_TIME_CEIL_MS) return MIN_TIME_CEIL_MS;
        return ms;
    }

    // ------------------------------------------------------------------
    // Compteur de points écartés, pour l'écran expert
    // ------------------------------------------------------------------

    /**
     * État d'une session : dernier point écrit, et compteur de points
     * valides mais non écrits.
     *
     * CE COMPTEUR EST IMPORTANT : sans lui, un utilisateur qui règle
     * « 1 km / 10 min » et obtient 3 points pour une randonnée de 2 h croit
     * à un bug. Avec lui, l'écran expert affiche « 142 points écartés » et
     * la politique devient compréhensible.
     */
    public static final class Session {
        private double lastWrittenLat = Double.NaN;
        private double lastWrittenLon = Double.NaN;
        private long lastWrittenElapsedMs = -1L;
        private int writtenCount = 0;
        private int skippedCount = 0;

        public int writtenCount() { return writtenCount; }
        public int skippedCount() { return skippedCount; }

        /** Ancien point écrit, pour tracer une distance exacte. */
        public double lastLat() { return lastWrittenLat; }
        public double lastLon() { return lastWrittenLon; }

        /**
         * Décide pour un point candidat (déjà validé par FixQuality).
         * Met à jour l'état si l'écriture est retenue.
         *
         * @param lat,lon        position candidate
         * @param elapsedMs      horodatage MONOTONE du candidat
         */
        public Verdict offer(int minDistanceM, long minTimeMs,
                             double lat, double lon, long elapsedMs) {
            double distance = Double.NaN;
            long dt = -1L;
            if (!Double.isNaN(lastWrittenLat) && lastWrittenElapsedMs >= 0) {
                distance = FixGeometry.distanceMeters(
                        lastWrittenLat, lastWrittenLon, lat, lon);
                dt = elapsedMs - lastWrittenElapsedMs;
            }
            Verdict v = decide(minDistanceM, minTimeMs, distance, dt);
            if (v.write) {
                lastWrittenLat = lat;
                lastWrittenLon = lon;
                lastWrittenElapsedMs = elapsedMs;
                writtenCount++;
            } else {
                skippedCount++;
            }
            return v;
        }

        /** Après une coupure de trace : le prochain point sera toujours écrit. */
        public void breakSegment() {
            lastWrittenLat = Double.NaN;
            lastWrittenLon = Double.NaN;
            lastWrittenElapsedMs = -1L;
        }

        public void reset() {
            breakSegment();
            writtenCount = 0;
            skippedCount = 0;
        }
    }
}
