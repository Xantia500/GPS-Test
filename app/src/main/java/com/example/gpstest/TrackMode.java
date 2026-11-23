package com.example.gpstest;

/**
 * MODES PRÉDÉFINIS D'ENREGISTREMENT — décision pure, testable sans Android.
 *
 * ══════════════════════════════════════════════════════════════════════
 * POURQUOI DES MODES, ET PAS SEULEMENT DEUX CHAMPS
 * ══════════════════════════════════════════════════════════════════════
 *
 * Demander à l'utilisateur de régler deux nombres (distance, temps) est un
 * piège : il ne sait pas ce qui est raisonnable, il met des valeurs au
 * hasard, obtient une trace inutilisable, et conclut que l'application est
 * mauvaise.
 *
 * Un mode, c'est une **intention** : « je marche ». Les deux nombres en
 * découlent. L'utilisateur qui sait ce qu'il fait garde le mode
 * « personnalisé » et règle ce qu'il veut.
 *
 * ══════════════════════════════════════════════════════════════════════
 * LES QUATRE MODES ET LEURS VALEURS
 * ══════════════════════════════════════════════════════════════════════
 *
 *   MARCHE     distance  20 m   temps  10 s
 *   VÉLO       distance  25 m   temps  15 s
 *   VOITURE    distance  50 m   temps  30 s
 *   PERSONNALISÉ  (les deux réglables)
 *
 * POURQUOI CES VALEURS, ET PAS CELLES DU DOCUMENT
 *
 * Marche : à 5 km/h (1,4 m/s), 20 m représentent 14 s. Le critère de
 * distance est donc atteint AVANT le critère de temps (10 s). C'est voulu :
 * en marche, la forme de la trace compte plus que la régularité. Et à
 * l'arrêt, le critère de temps écrit un point toutes les 10 s — la trace
 * montre qu'elle continue.
 *
 * Vélo : à 20 km/h (5,5 m/s), 25 m = 4,5 s. Le temps (15 s) prend le
 * relais dès qu'on ralentit ou qu'on s'arrête à un feu. C'est exactement
 * le comportement attendu d'un enregistreur de sortie vélo.
 *
 * Voiture : à 90 km/h (25 m/s), 50 m = 2 s. On écrit donc 1 point sur 2
 * (l'échantillonnage GNSS est à 1 Hz) — soit 1 800 points/heure à 90 km/h,
 * ce qui reste raisonnable pour une trace routière où la précision brute
 * n'a pas d'intérêt à 2 s d'intervalle.
 *
 * ══════════════════════════════════════════════════════════════════════
 * LA RÈGLE DE REJET PAR PRÉCISION — CORRIGÉE
 * ══════════════════════════════════════════════════════════════════════
 *
 * Proposition initiale :
 *
 *     rejeter si  accuracy >= 0.4 * intervalle_distance
 *
 * Mesuré sur les modes ci-dessus, cela donne :
 *
 *     MARCHE (20 m)  : rejette dès 8 m de précision
 *     VÉLO   (25 m)  : rejette dès 10 m
 *     VOITURE(50 m)  : rejette dès 20 m
 *
 * PROBLÈME 1 — c'est trop strict en marche et en vélo. En forêt ou entre
 * deux immeubles, la précision typique est de 5 à 15 m. Le mode marche
 * rejetterait alors la quasi-totalité des points : la trace serait VIDE,
 * sans qu'aucun message ne l'explique.
 *
 * PROBLÈME 2 — le seuil dépend du mode, donc de la vitesse. Or la
 * précision GNSS dépend du CIEL, pas de la vitesse. Un piéton sous un
 * couvert végétal et un automobiliste sur autoroute n'ont pas le même
 * ciel : lier le seuil à la distance parcourue n'a pas de sens physique.
 *
 * → RÈGLE RETENUE : le seuil est une fraction de la distance entre deux
 *   points, avec un plancher et un plafond ABSOLUS.
 *
 *       seuil = clamp( 0.4 * distanceMin , 8 m , 50 m )
 *
 *   MARCHE / VÉLO : 8 m (plancher) — on n'exige jamais mieux que 8 m,
 *                   sinon on jette des positions réelles.
 *   VOITURE       : 20 m — la distance de 50 m adoucit naturellement.
 *
 *   Et par-dessus, le plafond global de 50 m de FixQuality reste en
 *   vigueur : au-delà, un point n'a plus de sens, quel que soit le mode.
 *
 * ══════════════════════════════════════════════════════════════════════
 * LA RÈGLE DE VITESSE — CE QU'ELLE FAIT VRAIMENT
 * ══════════════════════════════════════════════════════════════════════
 *
 * Proposition initiale : rejeter si vitesse <= 1 km/h.
 *
 * Un piéton avance à 4-5 km/h : la règle ne le concerne pas. Elle
 * n'élimine donc que les points pris à l'ARRÊT (vitesse ≈ 0).
 *
 * Deux effets, l'un voulu, l'autre TOXIQUE :
 *
 *   VOULU : à l'arrêt, un GNSS oscille de ±2 à 5 m. Ces points créent une
 *   « pelote de laine » sur la trace. Les écarter est correct.
 *
 *   TOXIQUE : si on rejette ces points, le critère de TEMPS ne s'applique
 *   plus à eux, et une session immobile de vingt minutes ne produit AUCUN
 *   point. En rouvrant sa trace, l'utilisateur croit que l'enregistrement
 *   s'est arrêté.
 *
 * → RÈGLE RETENUE : à l'arrêt, on n'écrit pas un point de MOUVEMENT, mais
 *   on écrit une ANCRE toutes les `minTimeMs`. C'est le même critère de
 *   temps que celui du mode, appliqué sans condition de vitesse.
 *
 *   Autrement dit : la vitesse filtre les points, elle ne suspend pas la
 *   trace.
 */
public final class TrackMode {

    private TrackMode() { }

    // ------------------------------------------------------------------
    // Modes
    // ------------------------------------------------------------------

    public enum Mode {
        WALK(20, 10_000L),
        BIKE(25, 15_000L),
        CAR(50, 30_000L),
        /** Les deux valeurs viennent des préférences. */
        CUSTOM(SamplePolicy.DEFAULT_MIN_DISTANCE_M,
               SamplePolicy.DEFAULT_MIN_TIME_MS);

        public final int distanceM;
        public final long timeMs;

        Mode(int distanceM, long timeMs) {
            this.distanceM = distanceM;
            this.timeMs = timeMs;
        }
    }

    /** Valeurs par défaut des modes, dans l'ordre d'affichage. */
    public static final Mode[] DISPLAY_ORDER = {
            Mode.WALK, Mode.BIKE, Mode.CAR, Mode.CUSTOM
    };

    public static Mode fromId(String id, Mode fallback) {
        if (id == null) return fallback;
        for (Mode m : Mode.values()) {
            if (m.name().equals(id)) return m;
        }
        return fallback;
    }

    public static String labelFr(Mode m) {
        switch (m) {
            case WALK: return "Marche, randonnée";
            case BIKE: return "VTT, cyclisme";
            case CAR:  return "Véhicule motorisé";
            default:   return "Personnalisé";
        }
    }

    // ------------------------------------------------------------------
    // Paramètres effectifs d'une session
    // ------------------------------------------------------------------

    /** Ce que la session utilise réellement. */
    public static final class Effective {
        public final int distanceM;
        public final long timeMs;
        /** Seuil de rejet par précision, en mètres. */
        public final float accuracyLimitM;
        /** Vrai si les valeurs viennent du mode personnalisé. */
        public final boolean custom;

        Effective(int distanceM, long timeMs, float accuracyLimitM, boolean custom) {
            this.distanceM = distanceM;
            this.timeMs = timeMs;
            this.accuracyLimitM = accuracyLimitM;
            this.custom = custom;
        }

        public String describe() {
            String d = distanceM >= 1000 ? (distanceM / 1000) + " km" : distanceM + " m";
            String t = timeMs < 60_000 ? (timeMs / 1000) + " s" : (timeMs / 60_000) + " min";
            return d + " / " + t;
        }
    }

    /**
     * Résout les paramètres effectifs.
     *
     * @param customDistance distance choisie dans le mode personnalisé
     * @param customTime     temps choisi dans le mode personnalisé
     */
    public static Effective resolve(Mode mode, int customDistance, long customTime) {
        int d;
        long t;
        boolean custom = (mode == Mode.CUSTOM);
        if (custom) {
            d = SamplePolicy.sanitizeDistance(customDistance);
            t = SamplePolicy.sanitizeTime(customTime);
        } else {
            d = mode.distanceM;
            t = mode.timeMs;
        }
        return new Effective(d, t, accuracyLimitFor(d), custom);
    }

    // ------------------------------------------------------------------
    // Seuil de précision
    // ------------------------------------------------------------------

    /** Plancher du seuil : on n'exige jamais mieux que 8 m. */
    public static final float ACCURACY_FLOOR_M = 8f;
    /** Plafond du seuil : au-delà, FixQuality rejette de toute façon. */
    public static final float ACCURACY_CEIL_M = FixQuality.ACCURACY_RECORD_MAX_M;

    /**
     * Seuil de position au-delà duquel un point n'est pas écrit.
     *
     *   seuil = clamp( 0.4 × distance , 8 m , 50 m )
     *
     * Voir l'en-tête de la classe pour le raisonnement : la proposition
     * initiale (0.4 × distance sans plancher) rejetait 4 m de précision en
     * mode marche, ce qui viderait la trace en forêt.
     */
    public static float accuracyLimitFor(int distanceM) {
        float raw = 0.4f * Math.max(0, distanceM);
        if (raw < ACCURACY_FLOOR_M) return ACCURACY_FLOOR_M;
        if (raw > ACCURACY_CEIL_M) return ACCURACY_CEIL_M;
        return raw;
    }

    // ------------------------------------------------------------------
    // Vitesse
    // ------------------------------------------------------------------

    /** Vitesse en dessous de laquelle on considère l'utilisateur à l'arrêt. */
    public static final float STATIONARY_KMH = 1.0f;
    public static final float STATIONARY_MPS = STATIONARY_KMH / 3.6f;

    /**
     * Vrai si le point est « à l'arrêt ».
     *
     * ATTENTION : si la vitesse est INCONNUE (NaN), on ne conclut RIEN.
     * Répondre « à l'arrêt » reviendrait à supprimer tous les points d'un
     * appareil qui ne sait pas mesurer la vitesse — et certains appareils
     * ne la mesurent pas.
     */
    public static boolean isStationary(float speedMps) {
        return !Float.isNaN(speedMps) && speedMps >= 0f && speedMps <= STATIONARY_MPS;
    }

    // ------------------------------------------------------------------
    // Décision d'écriture propre au mode
    // ------------------------------------------------------------------

    /**
     * Décide, AVANT le critère distance/temps, si le point doit être rejeté.
     *
     * @return une raison lisible, ou null si le point passe
     */
    public static String rejectReason(float accuracyM, float speedMps,
                                      Effective eff) {
        // 1. Précision propre au mode. Le plafond global (50 m) est déjà
        //    appliqué par FixQuality : ici on ajoute la contrainte du mode.
        if (!Float.isNaN(accuracyM) && accuracyM > eff.accuracyLimitM) {
            return "précision " + Math.round(accuracyM) + " m > "
                    + Math.round(eff.accuracyLimitM) + " m";
        }
        // 2. À l'arrêt : on ne rejette PAS. On laisse le critère de temps
        //    écrire une ancre. Voir l'en-tête : rejeter ici laissait des
        //    sessions immobiles sans aucun point.
        return null;
    }

    /**
     * À l'arrêt, faut-il écrire quand même ?
     *
     * Oui — à condition que le temps soit écoulé. Cette méthode existe pour
     * que la règle soit écrite UNE fois et testée, plutôt que dispersée.
     */
    public static boolean writeAnchorWhenStationary(float speedMps,
                                                    boolean hasPrev,
                                                    long elapsedSinceWriteMs,
                                                    Effective eff) {
        if (!isStationary(speedMps)) return false;   // pas à l'arrêt : autre règle
        if (!hasPrev) return true;                   // premier point : toujours
        return eff.timeMs > 0 && elapsedSinceWriteMs >= eff.timeMs;
    }
}
