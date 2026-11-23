package com.example.gpstest;

/**
 * VALIDITÉ D'UN FIX ET MACHINE À ÉTATS DE LA RÉCEPTION — v5.7
 *
 * ══════════════════════════════════════════════════════════════════════
 * CE QUI A CHANGÉ DEPUIS LA v5.6, ET POURQUOI
 * ══════════════════════════════════════════════════════════════════════
 *
 * Trois de mes choix ont été corrigés par l'utilisateur. Ils étaient faux :
 *
 * 1. LE SEUIL DE 200 km/h EST SUPPRIMÉ.
 *    « On retire la limite des 200 km/h, on peut même utiliser son GPS dans
 *     un avion d'ailleurs. »
 *    Il avait raison sur deux plans : un avion de ligne vole à 900 km/h, et
 *    surtout aucun saut de position ne ressemble à 900 km/h — un saut de
 *    tunnel fait des MILLIERS de km/h. Le seuil à 200 km/h refusait des
 *    déplacements réels sans attraper les artefacts. C'est le pire des deux
 *    mondes.
 *
 *    Ce qui reste n'est plus un seuil de « plausibilité » mais un DÉTECTEUR
 *    DE BUG. Voir SPEED_SANITY_MPS plus bas.
 *
 * 2. LE POINT (0,0) EST TOUJOURS UN DÉFAUT.
 *    Ma version acceptait (0,0) si la précision annoncée était bonne, en
 *    arguant que c'est une position réelle dans le golfe de Guinée.
 *    Théoriquement vrai. En pratique : faux. Voir NULL_ISLAND_RUN plus bas.
 *
 * 3. LE TEMPS N'EST PAS UN CRITÈRE D'ORDRE.
 *    La documentation Android est explicite :
 *
 *      « the device's Unix epoch time system clock is not monotonic; it can
 *        jump forwards or backwards unpredictably and may be changed at any
 *        time by the user, so this time should not be used to order or
 *        compare locations. Prefer getElapsedRealtimeNanos() »
 *                            — developer.android.com/reference/android/location/Location
 *
 *    Donc oui, le temps peut reculer — mais pas pour la raison que
 *    j'invoquais. Ce n'est pas « l'horloge GPS des satellites » : c'est
 *    l'horloge du TÉLÉPHONE, qui peut être recalée par le réseau (NTP), par
 *    l'utilisateur, ou changer d'heure d'été.
 *
 *    CONSÉQUENCE DE CONCEPTION : on ne prend plus AUCUNE décision
 *    d'ordonnancement avec l'heure murale. Toutes les durées (perte de
 *    signal, vitesse implicite, intervalle entre points) utilisent
 *    l'horloge MONOTONE du système. L'heure murale ne sert plus qu'à une
 *    chose : écrire le <time> du GPX.
 *
 * ══════════════════════════════════════════════════════════════════════
 * LES CINQ CONDITIONS, ET LA NOUVELLE ÉCHELLE DE PRÉCISION
 * ══════════════════════════════════════════════════════════════════════
 *
 *   1. COORDONNÉES FINIES         NaN, ±Inf : jamais.
 *   2. DANS LES BORNES            |lat| ≤ 90, |lon| ≤ 180.
 *   3. PAS (0,0)                  rejet systématique selon la politique de l'app.
 *   4. PRÉCISION                  voir l'échelle ci-dessous.
 *   5. TEMPS COHÉRENT             ni futur, ni périmé.
 *
 * ── L'ÉCHELLE DE PRÉCISION (votre question « 50 m, 100 m ? ») ─────────
 *
 *   ≤ 50 m        OK       Le point est affiché ET enregistré.
 *   50 – 100 m    DÉGRADÉ  Affiché, RIEN d'enregistré, écran grisé.
 *   > 100 m       INUTILE  Traité exactement comme une perte de signal.
 *
 * Pourquoi trois paliers plutôt que deux : entre 50 et 100 m la position
 * existe encore (on la montre, on ne la croit pas) ; au-delà de 100 m, elle
 * n'a plus de sens physique — c'est un récepteur qui tâtonne. Traiter les
 * deux cas identiquement serait soit trop laxiste, soit trop brutal.
 *
 * La précision est la donnée la plus importante du flux GNSS, avant les
 * coordonnées elles-mêmes : une position fausse PRÉCISE est dangereuse, une
 * position fausse IMPRÉCISE est bénigne si on sait qu'elle est imprécise.
 */
public final class FixQuality {

    private FixQuality() { }

    // ------------------------------------------------------------------
    // Paramètres
    // ------------------------------------------------------------------

    /**
     * Silence au-delà duquel on considère le signal perdu (ms).
     *
     * Un GNSS bien réglé émet 1 point par seconde. Cinq callbacks manquants
     * d'affilée, c'est une perte ; un callback manqué, c'est du jitter.
     */
    public static final long LOSS_TIMEOUT_MS = 5_000L;

    /**
     * Seuil de détection accéléré quand le GPS est DÉSACTIVÉ dans Android.
     *
     * Différence essentielle : dans un tunnel, le récepteur se tait mais on
     * ne sait pas POURQUOI. Quand le fournisseur est désactivé, on sait —
     * inutile d'attendre 5 s, 1,5 s suffit et l'alerte arrive tout de suite.
     */
    public static final long GPS_OFF_TIMEOUT_MS = 1_500L;

    /** Précision au-delà de laquelle le point n'est plus enregistré (m). */
    public static final float ACCURACY_RECORD_MAX_M = 50f;

    /** Précision au-delà de laquelle la position n'a plus de sens (m). */
    public static final float ACCURACY_USELESS_M = 100f;

    /**
     * En dessous de cette précision annoncée, on se méfie (m).
     *
     * Un smartphone civil ne descend pas sous ~0,5 m en usage courant. Une
     * précision de 0 m pile poil n'est pas une excellence, c'est un champ
     * non renseigné. On ne rejette donc pas le point, mais on ne le croit
     * pas aveuglément non plus : voir la valeur retenue.
     */
    public static final float ACCURACY_SUSPICIOUS_BELOW_M = 0.5f;

    /**
     * DÉTECTEUR DE BUG — ce n'est PAS une limite de plausibilité.
     *
     * 1200 km/h ≈ Mach 1. Aucun véhicule terrestre, aucun avion de ligne
     * (900 km/h) ne l'atteint. Un récepteur défaillant qui saute de 500 km
     * entre deux points d'une seconde, lui, le dépasse de très loin.
     *
     * Ce seuil ne sert donc qu'à attraper une incohérence IMPOSSIBLE, pas à
     * juger un déplacement. Il ne s'applique que si les deux points sont
     * séparés de moins de SPEED_WINDOW_MS : au-delà, un grand déplacement
     * est parfaitement normal (on a pu faire 300 km pendant une heure sans
     * signal).
     */
    public static final double SPEED_SANITY_MPS = 1200.0 / 3.6;

    /** Fenêtre au-delà de laquelle le contrôle de cohérence ne s'applique
     *  plus (ms). 30 s : largement le temps de faire un vrai déplacement. */
    public static final long SPEED_WINDOW_MS = 30_000L;

    /**
     * ══════════════════════════════════════════════════════════════════
     * REPÈRE GÉOMÉTRIQUE INDICATIF : 3 + UNE HORLOGE PAR CONSTELLATION.
     * ══════════════════════════════════════════════════════════════════
     *
     *   Dans le modèle simplifié de DopCalculator : 3 inconnues de position
     *   + 1 biais d'horloge par constellation modélisée.
     *
     *   Ce compte est un repère de rang pour CETTE approximation DOP,
     *   pas un minimum universel imposé à Android ou au récepteur. Le moteur
     *   de position peut intégrer des contraintes, des biais, des mesures et
     *   des exclusions qui ne sont pas exposés par l'API publique.
     *
     * POURQUOI CE N'EST PAS « TOUJOURS 4 » DANS LE CALCUL DOP :
     * un modèle multi-constellation doit représenter les biais entre les
     * échelles de temps. DopCalculator réserve donc un paramètre par groupe
     * constellation. C'est une approximation explicite, pas une description
     * exhaustive du solveur GNSS du chipset.
     *
     * POURQUOI CE REPÈRE NE BLOQUE PAS LE FIX :
     * exiger ce nombre dans FixQuality pourrait rejeter un fix déjà résolu
     * par Android, alors que les détails internes du solveur ne sont pas
     * tous accessibles. Nous conservons le nombre à titre de diagnostic ;
     * la décision d'enregistrer repose sur la validité, la fraîcheur et la
     * précision horizontale de Location, jamais sur ce seuil seul.
     *
     * ── CE QUE CETTE RÈGLE NE FAIT PAS ────────────────────────────────
     * Elle ne vérifie PAS la répartition dans le ciel (DOP). Un récepteur
     * peut satisfaire 3+n avec des satellites tous groupés et produire une
     * position fausse. C'est assumé : Android ne fournit pas le DOP dans
     * `Location`, et on ne veut pas exiger de l'utilisateur qu'il active
     * les trames NMEA (que tout le monde n'a pas). L'écran expert affiche
     * le DOP calculé pour qui veut creuser.
     */
    public static final int SATELLITE_BASE = 3;

    /** Minimum pour UNE constellation. Utiliser minSatellitesFor(int). */
    public static final int MIN_SATELLITES_USED = SATELLITE_BASE + 1;

    /**
     * Compte de référence du modèle DOP simplifié pour `activeConstellations`
     * constellations distinctes. Ce n'est PAS un seuil de validation du fix.
     * Si le nombre est inconnu (0), on retourne le cas à une constellation.
     */
    public static int minSatellitesFor(int activeConstellations) {
        int n = Math.max(1, activeConstellations);
        return SATELLITE_BASE + n;
    }

    /** En dessous de ce nombre, on AFFICHE un avertissement (pas un refus). */
    public static final int MIN_SATELLITES_COMFORTABLE = 6;

    /** Nombre de fixes consécutifs valides exigés après une reprise. */
    public static final int REACQUIRE_GOOD_FIXES = 3;

    /** Nombre de (0,0) consécutifs avant de conclure à un défaut. */
    public static final int NULL_ISLAND_RUN = 2;

    /** Âge maximal d'un fix (ms). */
    public static final long MAX_FIX_AGE_MS = 10_000L;

    /** Tolérance sur l'heure « dans le futur » (ms) — dérive d'horloge. */
    public static final long MAX_FUTURE_SKEW_MS = 2_000L;

    // ------------------------------------------------------------------
    // États et raisons
    // ------------------------------------------------------------------

    public enum SignalState {
        /** Aucune réception : rien n'est enregistré, tout est grisé. */
        NO_SIGNAL,
        /** Réception présente, fixes pas encore fiables (ou trop imprécis). */
        REACQUIRING,
        /** Réception valide, position affichée et enregistrable. */
        OK
    }

    /** Raison du rejet, pour pouvoir la DIRE à l'utilisateur. */
    public enum RejectReason {
        OK(""),
        NON_FINITE("coordonnées invalides"),
        OUT_OF_BOUNDS("hors des bornes"),
        NULL_ISLAND("position 0,0 — récepteur en défaut"),
        NO_ACCURACY("précision inconnue"),
        BAD_ACCURACY("précision insuffisante pour enregistrer"),
        USELESS_ACCURACY("précision sans valeur — GPS en difficulté"),
        STALE("point périmé"),
        FUTURE("horodatage dans le futur"),
        INCOHERENT_MOVE("déplacement incohérent (défaut du récepteur)"),
        TOO_FEW_SATELLITES("trop peu de satellites utilisés"),
        TWO_D_FIX("fix 2D — altitude non résolue");

        public final String label;
        RejectReason(String label) { this.label = label; }

        public boolean isOk() { return this == OK; }
    }

    // ------------------------------------------------------------------
    // 1. Validité intrinsèque d'un fix
    // ------------------------------------------------------------------

    /** État d'un fix vis-à-vis de l'échelle de précision. */
    public enum AccuracyBand {
        GOOD,       // ≤ 50 m : enregistrable
        DEGRADED,   // 50–100 m : affichable, non enregistrable
        USELESS     // > 100 m : équivalent à une perte
    }

    /** Classe la précision. Une précision inconnue est USELESS. */
    public static AccuracyBand band(float accuracyM) {
        if (Float.isNaN(accuracyM) || accuracyM <= 0f) return AccuracyBand.USELESS;
        if (accuracyM <= ACCURACY_RECORD_MAX_M) return AccuracyBand.GOOD;
        if (accuracyM <= ACCURACY_USELESS_M) return AccuracyBand.DEGRADED;
        return AccuracyBand.USELESS;
    }

    /**
     * Vérifie la validité intrinsèque d'un fix (conditions 1 à 5).
     *
     * ── TOUS LES CONTRÔLES DE TEMPS SONT MONOTONES ─────────────────────
     * `fixElapsedMs` et `nowElapsedMs` viennent de SystemClock.elapsedRealtime()
     * (ou de Location.getElapsedRealtimeNanos()/1e6), jamais de l'heure
     * murale. L'heure murale ne sert QU'À écrire le <time> du GPX.
     *
     * C'est la conséquence directe de votre question « le temps qui recule
     * est-il vraiment possible ? ». Réponse : oui — ma v5.6 vérifiait la
     * péremption avec l'heure murale. Si le téléphone recule d'une heure
     * (réglage manuel, synchronisation réseau), TOUS les points auraient été
     * rejetés comme périmés pendant une heure entière. L'horloge monotone du
     * noyau ne peut pas reculer : le défaut disparaît complètement.
     *
     * @param nullIslandRun conservé pour compatibilité ascendante de l'API ;
     *                      n'est plus utilisé (voir condition 3).
     */
    public static RejectReason checkFix(double lat, double lon, float accuracyM,
                                        long fixElapsedMs, long nowElapsedMs,
                                        int nullIslandRun) {
        return checkFix(lat, lon, accuracyM, fixElapsedMs, nowElapsedMs,
                MIN_SATELLITES_USED, true);
    }

    /**
     * Version complète : ajoute les deux conditions que le récepteur seul
     * peut renseigner.
     *
     * @param satellitesUsed nombre de satellites RÉELLEMENT utilisés dans la
     *        solution (GnssStatus.usedInFix), pas seulement « en vue ».
     *        0 signifie « information indisponible » : dans ce cas on ne
     *        rejette pas, on ne peut pas juger.
     * @param hasAltitude    faux = fix 2D. Il reste admissible pour la
     *        position horizontale ; l'absence d'altitude est signalée par
     *        l'UI et ne doit pas être confondue avec un fix 3D.
     */
    public static RejectReason checkFix(double lat, double lon, float accuracyM,
                                        long fixElapsedMs, long nowElapsedMs,
                                        int satellitesUsed, boolean hasAltitude) {
        return checkFix(lat, lon, accuracyM, fixElapsedMs, nowElapsedMs,
                satellitesUsed, activeConstellationCount(satellitesUsed, 0), hasAltitude);
    }

    /**
     * Version complète avec le nombre de constellations.
     *
     * @param activeConstellations nombre de constellations DISTINCTES
     *        comptant au moins un satellite utilisé dans la solution.
     *        0 = inconnu -> on suppose 1 (le cas le moins exigeant qui ait
     *        un sens).
     */
    public static RejectReason checkFix(double lat, double lon, float accuracyM,
                                        long fixElapsedMs, long nowElapsedMs,
                                        int satellitesUsed, int activeConstellations,
                                        boolean hasAltitude) {
        // 1. Finitude
        if (Double.isNaN(lat) || Double.isNaN(lon)
                || Double.isInfinite(lat) || Double.isInfinite(lon)) {
            return RejectReason.NON_FINITE;
        }
        // 2. Bornes
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) {
            return RejectReason.OUT_OF_BOUNDS;
        }
        // 3. Île Null.
        //
        // (0,0) est géométriquement valide — golfe de Guinée. Mais vous avez
        // raison sur l'observation terrain : les modules renvoient (0,0) en
        // cas de problème, et le passage brutal de (0,0) à la vraie position
        // fabrique des distances et vitesses absurdes.
        //
        // La règle « on accepte si la précision est bonne » ne protège pas :
        // en phase d'acquisition, un récepteur peut annoncer 5 m de précision
        // sur un (0,0) qu'il n'a pas encore résolu.
        //
        // → Dans cette application, (0,0) est systématiquement rejeté à
        //   l'entrée du pipeline GNSS. Cela évite le saut « position nulle →
        //   vraie position » et ne transforme jamais ce sentinel matériel en
        //   point GPX. Le Golfe de Guinée est une position géographique valide
        //   en théorie ; l'application assume explicitement de ne pas couvrir
        //   ce cas extrêmement improbable pour un outil GPS grand public.
        if (lat == 0.0 && lon == 0.0) {
            return RejectReason.NULL_ISLAND;
        }
        // 4. Précision
        AccuracyBand b = band(accuracyM);
        if (b == AccuracyBand.USELESS) {
            return Float.isNaN(accuracyM) || accuracyM <= 0f
                    ? RejectReason.NO_ACCURACY
                    : RejectReason.USELESS_ACCURACY;
        }
        // 5. Temps — MONOTONE uniquement.
        if (nowElapsedMs > 0) {
            if (fixElapsedMs > nowElapsedMs + MAX_FUTURE_SKEW_MS) return RejectReason.FUTURE;
            if (nowElapsedMs - fixElapsedMs > MAX_FIX_AGE_MS) return RejectReason.STALE;
        }
        // 6. SATELLITES UTILISÉS : INDICATEUR, PAS FILTRE BLOQUANT.
        // Android Location est déjà le résultat de la solution du récepteur.
        // Des seuils applicatifs « 3 + une horloge par constellation » sont
        // trop stricts : le moteur peut résoudre certains cas avec des
        // contraintes supplémentaires, des constellations hybrides ou des
        // signaux dont l'API ne détaille pas la répartition. On conserve ces
        // constantes et minSatellitesFor() comme repères diagnostiques, mais
        // on ne rejette plus un fix fourni par le récepteur sur ce seul critère.
        // 7. FIX 2D : une position horizontale peut être utile. L'absence
        // d'altitude est conservée comme état distinct et l'interface la
        // présente en ambre ; elle ne transforme pas lat/lon en absence de fix.
        return RejectReason.OK;
    }

    /**
     * Nombre de constellations à supposer quand l'information est absente.
     *
     * Si on connaît le nombre de satellites utilisés mais pas leur
     * répartition, on ne peut pas savoir combien d'horloges sont en jeu.
     * Supposer 1 est le choix le moins exigeant — donc celui qui risque le
     * moins de refuser un bon fix. C'est cohérent avec la règle « on ne
     * rejette pas sur une information absente ».
     */
    public static int activeConstellationCount(int satellitesUsed,
                                               int knownConstellations) {
        if (knownConstellations > 0) return knownConstellations;
        return 1;
    }

    /** Surcharge sans contexte d'île Null. */
    public static RejectReason checkFix(double lat, double lon, float accuracyM,
                                        long fixElapsedMs, long nowElapsedMs) {
        return checkFix(lat, lon, accuracyM, fixElapsedMs, nowElapsedMs, 0);
    }

    // ------------------------------------------------------------------
    // 2. Ce fix peut-il devenir le prochain point de la trace ?
    // ------------------------------------------------------------------

    public static final class Decision {
        public final boolean accept;
        public final boolean recordable;
        public final RejectReason reason;
        public final double impliedSpeedMps;
        public final double distanceM;
        Decision(boolean accept, boolean recordable, RejectReason reason,
                 double impliedSpeedMps, double distanceM) {
            this.accept = accept;
            this.recordable = recordable;
            this.reason = reason;
            this.impliedSpeedMps = impliedSpeedMps;
            this.distanceM = distanceM;
        }
    }

    /**
     * Décide si un fix peut devenir le prochain point.
     *
     * ORDONNANCEMENT : les durées viennent de l'horloge MONOTONE (elapsed),
     * jamais de l'heure murale. C'est la correction décisive de la v5.7.
     *
     * @param prevElapsedMs horodatage monotone du dernier point accepté,
     *                      ou ≤ 0 si aucun.
     */
    public static Decision acceptAsNextPoint(double lat, double lon, float accuracyM,
                                             long fixElapsedMs, long nowElapsedMs,
                                             double prevLat, double prevLon,
                                             long prevElapsedMs) {
        return acceptAsNextPoint(lat, lon, accuracyM, fixElapsedMs, nowElapsedMs,
                MIN_SATELLITES_USED, true, prevLat, prevLon, prevElapsedMs);
    }

    public static Decision acceptAsNextPoint(double lat, double lon, float accuracyM,
                                             long fixElapsedMs, long nowElapsedMs,
                                             int satellitesUsed, boolean hasAltitude,
                                             double prevLat, double prevLon,
                                             long prevElapsedMs) {
        return acceptAsNextPoint(lat, lon, accuracyM, fixElapsedMs, nowElapsedMs,
                satellitesUsed, 0, hasAltitude, prevLat, prevLon, prevElapsedMs);
    }

    public static Decision acceptAsNextPoint(double lat, double lon, float accuracyM,
                                             long fixElapsedMs, long nowElapsedMs,
                                             int satellitesUsed, int activeConstellations,
                                             boolean hasAltitude,
                                             double prevLat, double prevLon,
                                             long prevElapsedMs) {
        RejectReason base = checkFix(lat, lon, accuracyM, fixElapsedMs, nowElapsedMs,
                satellitesUsed, activeConstellations, hasAltitude);
        if (!base.isOk()) {
            return new Decision(false, false, base, Double.NaN, Double.NaN);
        }

        boolean recordable = band(accuracyM) == AccuracyBand.GOOD;
        boolean hasPrev = !Double.isNaN(prevLat) && !Double.isNaN(prevLon)
                && prevElapsedMs > 0;

        if (!hasPrev) {
            return new Decision(true, recordable, RejectReason.OK,
                    Double.NaN, Double.NaN);
        }

        double d = FixGeometry.distanceMeters(prevLat, prevLon, lat, lon);
        long dt = fixElapsedMs - prevElapsedMs;

        // Le temps monotone ne peut pas reculer : s'il le fait, c'est un
        // changement d'horloge du téléphone (mise en veille profonde, mise à
        // jour). On ne rejette PAS le point : on ne peut pas calculer de
        // vitesse, c'est tout.
        if (dt <= 0) {
            return new Decision(true, recordable, RejectReason.OK,
                    Double.NaN, d);
        }

        double speed = d / (dt / 1000.0);

        // Détecteur de bug, appliqué SEULEMENT sur une courte fenêtre. Sur
        // une longue interruption, un grand déplacement est normal et ne
        // doit jamais être refusé — c'est exactement le cas d'usage « avion ».
        if (dt <= SPEED_WINDOW_MS && speed > SPEED_SANITY_MPS) {
            return new Decision(false, false, RejectReason.INCOHERENT_MOVE,
                    speed, d);
        }
        return new Decision(true, recordable, RejectReason.OK, speed, d);
    }

    // ------------------------------------------------------------------
    // 3. Machine à états
    // ------------------------------------------------------------------

    public static final class Tracker {

        private SignalState signalState = SignalState.NO_SIGNAL;
        private long lastCallbackElapsedMs = 0L;
        private long lastGoodElapsedMs = 0L;
        private int consecutiveGood = 0;
        private int nullIslandRun = 0;
        private int lostEpisodeCount = 0;
        private int clampedCount = 0;

        private double lastLat = Double.NaN;
        private double lastLon = Double.NaN;
        private long lastElapsedMs = 0L;
        /** Dernière heure murale ÉCRITE (pour garder le GPX croissant). */
        private long lastWrittenWallMs = 0L;

        private RejectReason lastReject = RejectReason.OK;
        private int lastSatellitesUsed = 0;
        private int lastActiveConstellations = 0;

        /** Dernier nombre de satellites UTILISÉS vu (0 = inconnu). */
        public int lastSatellitesUsed() { return lastSatellitesUsed; }

        /** Dernier nombre de constellations actives (0 = inconnu). */
        public int lastActiveConstellations() { return lastActiveConstellations; }

        /**
         * Nombre de référence du modèle DOP simplifié pour la session.
         * Le nom historique requiredSatellites() est conservé pour compatibilité,
         * mais ce nombre ne rejette jamais un fix déjà fourni par Android.
         */
        public int requiredSatellites() {
            return minSatellitesFor(activeConstellationCount(
                    lastSatellitesUsed, lastActiveConstellations));
        }

        public SignalState state() { return signalState; }
        public boolean isOk() { return signalState == SignalState.OK; }
        public boolean isLost() { return signalState == SignalState.NO_SIGNAL; }
        public int consecutiveGood() { return consecutiveGood; }
        public int lostEpisodes() { return lostEpisodeCount; }
        public int clampedTimes() { return clampedCount; }
        public int nullIslandRun() { return nullIslandRun; }
        public double lastLat() { return lastLat; }
        public double lastLon() { return lastLon; }
        public long lastWrittenWallMs() { return lastWrittenWallMs; }
        public RejectReason lastReject() { return lastReject; }

        /** Durée depuis le dernier bon fix (ms), ou -1 si aucun. */
        public long ageSinceLastGoodMs(long nowElapsedMs) {
            return lastGoodElapsedMs <= 0 ? -1L
                    : Math.max(0L, nowElapsedMs - lastGoodElapsedMs);
        }

        /** Durée de la perte courante (ms), ou -1 si pas en perte. */
        public long lostForMs(long nowElapsedMs) {
            if (signalState != SignalState.NO_SIGNAL) return -1L;
            if (lastGoodElapsedMs <= 0) return -1L;
            return Math.max(0L, nowElapsedMs - lastGoodElapsedMs);
        }

        /**
         * À appeler depuis le watchdog. SEUL mécanisme de détection : un
         * récepteur muet n'émet aucun événement.
         *
         * @param gpsProviderEnabled faux si l'utilisateur a coupé le GPS :
         *        la détection est alors accélérée (GPS_OFF_TIMEOUT_MS).
         * @return true si l'état a CHANGÉ.
         */
        public boolean tick(long nowElapsedMs, boolean gpsProviderEnabled) {
            if (lastCallbackElapsedMs <= 0) return false;   // jamais eu de signal
            if (signalState == SignalState.NO_SIGNAL) return false;

            long silence = nowElapsedMs - lastCallbackElapsedMs;
            long timeout = gpsProviderEnabled ? LOSS_TIMEOUT_MS : GPS_OFF_TIMEOUT_MS;
            if (silence > timeout) {
                signalState = SignalState.NO_SIGNAL;
                consecutiveGood = 0;
                return true;
            }
            return false;
        }

        /** Surcharge : GPS actif. */
        public boolean tick(long nowElapsedMs) {
            return tick(nowElapsedMs, true);
        }

        /**
         * Traite un fix entrant. Toutes les durées sont MONOTONES.
         */
        public Decision onFix(double lat, double lon, float accuracyM,
                              long fixElapsedMs, long nowElapsedMs) {
            // Surcharge : satellites inconnus (0) et altitude présente.
            // C'est ce que voit le moteur quand GnssStatus n'est pas encore
            // arrivé — on ne rejette pas sur une information absente.
            return onFix(lat, lon, accuracyM, fixElapsedMs, nowElapsedMs, 0, true);
        }

        public Decision onFix(double lat, double lon, float accuracyM,
                              long fixElapsedMs, long nowElapsedMs,
                              int satellitesUsed, boolean hasAltitude) {
            return onFix(lat, lon, accuracyM, fixElapsedMs, nowElapsedMs,
                    satellitesUsed, 0, hasAltitude);
        }

        public Decision onFix(double lat, double lon, float accuracyM,
                              long fixElapsedMs, long nowElapsedMs,
                              int satellitesUsed, int activeConstellations,
                              boolean hasAltitude) {
            lastCallbackElapsedMs = nowElapsedMs;
            lastSatellitesUsed = satellitesUsed;
            lastActiveConstellations = activeConstellations;

            // Compteur d'île Null, conservé pour l'écran expert (combien de
            // (0,0) d'affilée le récepteur a-t-il crachés ?).
            if (lat == 0.0 && lon == 0.0) nullIslandRun++;
            else nullIslandRun = 0;

            Decision d = acceptAsNextPoint(lat, lon, accuracyM,
                    fixElapsedMs, nowElapsedMs, satellitesUsed, activeConstellations,
                    hasAltitude, lastLat, lastLon, lastElapsedMs);

            if (!d.accept) {
                consecutiveGood = 0;
                lastReject = d.reason;
                if (signalState == SignalState.OK) signalState = SignalState.REACQUIRING;
                return d;
            }

            lastReject = d.reason;

            // La précision dégradée n'est pas « une perte » : le récepteur
            // parle, la position existe. Mais elle n'est pas enregistrable.
            //
            // POINT SUBTIL : un fix dégradé NE COMPTE PAS dans la séquence de
            // réacquisition. Sinon deux fixes à 80 m suivis d'un fix à 5 m
            // établiraient OK — c'est-à-dire que la trace reprendrait sur la
            // foi de précisément les positions auxquelles on ne fait pas
            // confiance. Trouvé par un test, pas par la compilation.
            AccuracyBand b = band(accuracyM);
            if (b == AccuracyBand.DEGRADED) {
                consecutiveGood = 0;
                if (signalState == SignalState.OK) signalState = SignalState.REACQUIRING;
                return d;
            }

            consecutiveGood++;

            // Reprise : on exige une SÉQUENCE, pas un point isolé.
            if (signalState != SignalState.OK) {
                signalState = (consecutiveGood >= REACQUIRE_GOOD_FIXES)
                        ? SignalState.OK : SignalState.REACQUIRING;
            }

            if (signalState == SignalState.OK) {
                lastLat = lat;
                lastLon = lon;
                lastElapsedMs = fixElapsedMs;
                lastGoodElapsedMs = nowElapsedMs;
            }
            return d;
        }

        // ── ÉPOQUE DE TRACE ────────────────────────────────────────────
        //
        // RÈGLE (votre point 3, appliquée telle quelle) :
        //
        //   t = heure_du_premier_fix + (elapsed_maintenant - t0)
        //
        // avec t0 = lecture de getElapsedRealtimeNanos() au moment du premier
        // fix. Autrement dit : la trace est une horloge MONOTONE, ancrée sur
        // l'heure murale du premier point.
        //
        // POURQUOI C'EST LA BONNE SOLUTION, et ce qu'elle règle d'un coup :
        //
        //  1. Le temps ne peut PLUS reculer : les incréments viennent d'une
        //     horloge monotone. Plus besoin du recalage « +1 ms » de la v5.7,
        //     qui était un pansement.
        //
        //  2. Les creux et les bosses de l'heure murale (synchronisation
        //     réseau, réglage utilisateur) n'apparaissent pas dans le fichier.
        //
        //  3. Les intervalles entre points sont EXACTS, pas arrondis par un
        //     horodatage GPS qui peut avoir 1 s de granularité.
        //
        //  4. La trace démarre à une heure crédible (l'heure du premier fix),
        //     pas à 1970 ni à une heure système fausse.
        //
        // CONTREPARTIE, assumée et documentée : si l'heure du téléphone est
        // fausse au premier fix, TOUTE la trace est décalée du même montant.
        // C'est préférable à une trace dont les timestamps sautent — mais
        // l'utilisateur doit pouvoir le voir : si le décalage dépasse
        // epochDriftWarnMs, l'écran expert le signale.

        /** Heure murale du premier point accepté (ancre de la trace). */
        private long epochWallMs = 0L;
        /** Lecture monotone au même instant (t0). */
        private long epochElapsedMs = 0L;
        private boolean epochSet = false;
        private long lastEpochTimeMs = Long.MIN_VALUE;

        /** Seuil au-delà duquel une dérive d'horloge mérite d'être signalée. */
        public static final long EPOCH_DRIFT_WARN_MS = 5 * 60_000L;

        /** Vrai si l'ancre de trace est établie. */
        public boolean isEpochSet() { return epochSet; }

        /**
         * Établit l'ancre de la trace. Appelé par le service au premier fix
         * ACCEPTÉ (pas au premier callback).
         *
         * @param firstFixWallMs heure murale du premier fix (location.getTime())
         * @param t0ElapsedMs    getElapsedRealtimeNanos()/1e6 lu AU MOMENT de
         *                       ce fix. En pratique on réutilise l'horodatage
         *                       monotone du fix lui-même.
         */
        public void beginEpoch(long firstFixWallMs, long t0ElapsedMs) {
            epochWallMs = firstFixWallMs;
            epochElapsedMs = t0ElapsedMs;
            epochSet = true;
            // SENTINELLE, PAS ZÉRO NI LA VALEUR COURANTE.
            //
            // Ma première version initialisait lastEpochTimeMs à
            // firstFixWallMs : le PREMIER point, dont l'heure vaut exactement
            // firstFixWallMs, était alors considéré comme « pas après le
            // précédent » et forcé à +1 ms. Une milliseconde de décalage sur
            // toute la trace, et un compteur de corrections faux dès le
            // premier point. Trouvé par le test « à t0 : t = heure du
            // premier fix », pas à la compilation.
            lastEpochTimeMs = Long.MIN_VALUE;
        }

        /**
         * Heure à écrire dans le GPX pour un point arrivé à `nowElapsedMs`.
         *
         *   t = heure_du_premier_fix + (nowElapsedMs - t0)
         *
         * Garantie strictement croissante par construction (l'horloge
         * monotone ne recule pas) — sauf si deux points arrivent dans la
         * même milliseconde, cas où on incrémente de 1 ms pour que le GPX
         * reste valide.
         */
        public long epochTimeMs(long nowElapsedMs) {
            if (!epochSet) return 0L;
            long t = epochWallMs + (nowElapsedMs - epochElapsedMs);
            if (t <= lastEpochTimeMs) {
                t = lastEpochTimeMs + 1;
                clampedCount++;
            }
            lastEpochTimeMs = t;
            return t;
        }

        /**
         * Décalage mesuré entre l'horloge de trace et l'heure murale réelle.
         * Non nul si le téléphone a été recalé pendant la session : la trace
         * reste cohérente en relatif, mais son heure absolue dérive.
         */
        public long driftMs(long nowWallMs, long nowElapsedMs) {
            if (!epochSet) return 0L;
            return (epochWallMs + (nowElapsedMs - epochElapsedMs)) - nowWallMs;
        }

        /**
         * HEURE À ÉCRIRE DANS LE GPX, garantie strictement croissante.
         *
         * Pourquoi forcer : l'horloge du téléphone peut reculer (NTP, réglage
         * manuel, heure d'été). Un GPX dont les <time> reculent est rejeté
         * ou mal interprété par plusieurs lecteurs.
         *
         * Pourquoi on ne JETTE PAS le point : la géométrie (lat/lon) est
         * vraie, seule l'horodatage est douteux. Perdre une position réelle
         * parce que le téléphone a synchronisé son heure serait absurde —
         * c'est le choix inverse de la v5.6, et il était mauvais.
         *
         * On avance donc de 1 ms si nécessaire. Le décalage est
         * INDISCERNABLE sur une trace (1 ms) et le fichier reste valide.
         */
        public long wallTimeForWriting(long fixWallMs) {
            long t = fixWallMs;
            if (t <= lastWrittenWallMs) {
                t = lastWrittenWallMs + 1;
                clampedCount++;
            }
            lastWrittenWallMs = t;
            return t;
        }

        public boolean shouldWrite() { return signalState == SignalState.OK; }

        public void reset() {
            signalState = SignalState.NO_SIGNAL;
            lastCallbackElapsedMs = 0L;
            lastGoodElapsedMs = 0L;
            consecutiveGood = 0;
            nullIslandRun = 0;
            lostEpisodeCount = 0;
            clampedCount = 0;
            lastLat = Double.NaN;
            lastLon = Double.NaN;
            lastElapsedMs = 0L;
            lastWrittenWallMs = 0L;
            lastReject = RejectReason.OK;
            lastSatellitesUsed = 0;
            lastActiveConstellations = 0;
            epochSet = false;
            epochWallMs = 0L;
            epochElapsedMs = 0L;
            lastEpochTimeMs = Long.MIN_VALUE;
        }

        public void forceLoss() {
            if (signalState != SignalState.NO_SIGNAL) lostEpisodeCount++;
            signalState = SignalState.NO_SIGNAL;
            consecutiveGood = 0;
        }
    }

}
