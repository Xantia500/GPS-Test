package com.example.gpstest;

/**
 * QUAND PARLER — politique d'annonce vocale, configurable — v5.7
 *
 * ══════════════════════════════════════════════════════════════════════
 * CE QUI CHANGE PAR RAPPORT À LA v5.6
 * ══════════════════════════════════════════════════════════════════════
 *
 * La v5.6 imposait : grâce 15 s, rappel 30 s, plafond 6. Des constantes.
 * L'utilisateur a demandé que ce soit RÉGLABLE :
 *
 *   « pour perte de signal avertir : jamais, 30 s, 1 min, 2 min, 5 min,
 *     ou un nombre de fois »
 *
 * Et, point important :
 *
 *   « on ne dira pas "depuis 45 secondes" mais on peut l'écrire sur l'écran
 *     sans gêner la visibilité du reste »
 *
 * Le message vocal devient donc COURT et CONSTANT (« Signal GPS perdu »).
 * La durée s'affiche à l'écran, où elle ne coûte rien et ne s'oublie pas
 * au milieu d'une phrase.
 *
 * ══════════════════════════════════════════════════════════════════════
 * POURQUOI PAS DE « GRÂCE » SÉPARÉE
 * ══════════════════════════════════════════════════════════════════════
 *
 * Le premier intervalle EST le délai de grâce. « Avertir toutes les 1 min »
 * signifie : première alerte au bout d'1 min, puis toutes les minutes.
 * Un réglage séparé « grâce » aurait produit des combinaisons absurdes
 * (grâce 10 s + rappel 5 min) que personne ne peut prévoir.
 *
 * ══════════════════════════════════════════════════════════════════════
 * DÉCISION ET EFFET SÉPARÉS
 * ══════════════════════════════════════════════════════════════════════
 *
 * Cette classe DÉCIDE, elle ne parle pas. Tous les cas limites (réarmement,
 * épisode, plafond, reprise confirmée) sont ici et sont testables sans
 * téléphone. VoiceAlert se contente de prononcer.
 */
public final class LossAnnouncer {

    private LossAnnouncer() { }

    // ------------------------------------------------------------------
    // Paramètres
    // ------------------------------------------------------------------

    /** Rappel impossible : on ne répète jamais. */
    public static final long REPEAT_NEVER = 0L;
    public static final long REPEAT_30S  = 30_000L;
    public static final long REPEAT_1MIN = 60_000L;
    public static final long REPEAT_2MIN = 120_000L;
    public static final long REPEAT_5MIN = 300_000L;

    /** Plafonds proposés dans les réglages. 0 = sans limite. */
    public static final int[] MAX_CHOICES = {1, 3, 5, 10, 0};

    /** Rythme par défaut : une minute. */
    public static final long DEFAULT_REPEAT_MS = REPEAT_1MIN;

    /** Valeurs proposées dans les réglages, dans l'ordre d'affichage. */
    public static final long[] REPEAT_CHOICES = {
            REPEAT_NEVER, REPEAT_30S, REPEAT_1MIN, REPEAT_2MIN, REPEAT_5MIN
    };

    /**
     * Plafond par défaut : SANS LIMITE (0).
     *
     * La v5.6 imposait 6. L'utilisateur a tranché autrement, et il a raison :
     * le rythme est désormais réglable (1 min par défaut), donc un plafond
     * fixe n'a plus de justification. Celui qui choisit « toutes les 30 s »
     * sait ce qu'il fait ; celui qui ne veut pas être dérangé choisit
     * « jamais » ou descend le rythme.
     *
     * Le plafond reste PROPOSÉ dans les réglages : c'est une protection que
     * certains voudront, pas une décision à imposer.
     */
    public static final int DEFAULT_MAX_ANNOUNCEMENTS = 0;   // 0 = sans limite

    /** Anti-rebond sur le retour : les satellites reviennent par vagues. */
    public static final long RECOVERY_CONFIRM_MS = 3_000L;

    /** Bornes acceptées pour la configuration (garde-fous). */
    public static final long MIN_REPEAT_MS = 5_000L;
    public static final long MAX_REPEAT_MS = 3_600_000L;

    // ------------------------------------------------------------------
    // Ce qu'il faut faire
    // ------------------------------------------------------------------

    public enum Action {
        SILENT,
        ANNOUNCE_LOSS,
        ANNOUNCE_RECOVERY
    }

    /**
     * Identifiant de ressource du texte à prononcer — PAS le texte.
     *
     * Pourquoi un identifiant et non une chaîne : la langue de l'alerte doit
     * être CELLE DU TÉLÉPHONE. Si on renvoie une chaîne française en dur, un
     * utilisateur hispanophone entend du français prononcé par une voix
     * espagnole. En passant par les ressources, Android choisit la variante
     * linguistique correcte automatiquement — et le jour où l'app est
     * traduite, l'alerte l'est aussi, sans une ligne de code à changer.
     *
     * La classe reste PURE : elle renvoie un int, pas un Context.
     */
    public static int textResId(Action a) {
        switch (a) {
            case ANNOUNCE_LOSS:     return R.string.voice_text_loss;
            case ANNOUNCE_RECOVERY: return R.string.voice_text_recovery;
            case SILENT:
            default:                return 0;
        }
    }

    /**
     * Texte de durée POUR L'ÉCRAN uniquement (jamais parlé).
     * Exemple : « depuis 1 min 45 s ».
     */
    public static String durationLabel(long lostMs) {
        if (lostMs < 0) return "";
        long s = lostMs / 1000;
        if (s < 60) return "depuis " + s + " s";
        long m = s / 60, r = s % 60;
        if (r == 0) return "depuis " + m + " min";
        return "depuis " + m + " min " + r + " s";
    }

    // ------------------------------------------------------------------
    // Décideur
    // ------------------------------------------------------------------

    public static final class Policy {

        // Configuration
        private boolean enabled = true;
        private boolean announceRecovery = true;
        private long repeatMs = DEFAULT_REPEAT_MS;
        private int maxAnnouncements = DEFAULT_MAX_ANNOUNCEMENTS;

        // Épisode de perte
        private int announcementsThisEpisode = 0;
        private long lastAnnounceMs = 0L;
        private boolean inLossEpisode = false;

        // Épisode de reprise
        private boolean recoveryStarted = false;
        private long recoveryStartMs = 0L;
        private boolean recoveryAnnounced = false;

        private Action lastAction = Action.SILENT;

        /** Applique la configuration des préférences. Réarme si nécessaire. */
        public void configure(boolean enabled, boolean announceRecovery,
                              long repeatMs, int maxAnnouncements) {
            boolean wasEnabled = this.enabled;
            this.enabled = enabled;
            this.announceRecovery = announceRecovery;
            this.repeatMs = Math.max(REPEAT_NEVER, repeatMs);
            this.maxAnnouncements = Math.max(0, maxAnnouncements);
            // Passer de désactivé à activé (ou l'inverse) doit réarmer : un
            // utilisateur qui réactive pendant un épisode doit être prévenu.
            if (wasEnabled != enabled) reset();
        }

        public boolean isEnabled() { return enabled; }
        public long repeatMs() { return repeatMs; }
        public int maxAnnouncements() { return maxAnnouncements; }
        public Action lastAction() { return lastAction; }
        public int announcementsThisEpisode() { return announcementsThisEpisode; }
        public boolean isInLossEpisode() { return inLossEpisode; }

        /**
         * Un tick par seconde. `lost` vient de la machine à états, qui a
         * déjà tranché ce qu'est « perdu » (silence, précision inutilisable,
         * GPS coupé...).
         */
        public Action tick(boolean lost, long lostForMs, boolean userDisabled,
                           long nowMs) {
            if (!enabled || userDisabled) {
                lastAction = Action.SILENT;
                return Action.SILENT;
            }

            // ORDRE DES BLOCS : c'est un bug corrigé. Tester « signal
            // présent » AVANT « en épisode » réinitialisait la confirmation
            // de reprise à chaque tick, et la reprise n'était JAMAIS
            // annoncée.
            if (!lost && inLossEpisode) {
                if (!recoveryStarted) {
                    recoveryStarted = true;
                    recoveryStartMs = nowMs;
                }
                if (!recoveryAnnounced
                        && nowMs - recoveryStartMs >= RECOVERY_CONFIRM_MS) {
                    recoveryAnnounced = true;
                    inLossEpisode = false;
                    recoveryStarted = false;
                    announcementsThisEpisode = 0;
                    lastAnnounceMs = 0L;
                    lastAction = announceRecovery ? Action.ANNOUNCE_RECOVERY
                                                  : Action.SILENT;
                    return lastAction;
                }
                lastAction = Action.SILENT;
                return Action.SILENT;
            }

            if (lost) {
                if (!inLossEpisode) {
                    inLossEpisode = true;
                    announcementsThisEpisode = 0;
                    lastAnnounceMs = 0L;
                    recoveryStarted = false;
                    recoveryStartMs = 0L;
                    recoveryAnnounced = false;
                }

                // REPEAT_NEVER : une seule annonce, dès que la perte dépasse
                // le délai minimal de détection. Pas de grâce séparée : si
                // l'utilisateur choisit « jamais de rappel », il veut être
                // prévenu UNE fois, tôt.
                if (repeatMs == REPEAT_NEVER) {
                    if (announcementsThisEpisode == 0) {
                        announcementsThisEpisode = 1;
                        lastAnnounceMs = nowMs;
                        lastAction = Action.ANNOUNCE_LOSS;
                        return Action.ANNOUNCE_LOSS;
                    }
                    lastAction = Action.SILENT;
                    return Action.SILENT;
                }

                // Le PREMIER intervalle sert de délai de grâce.
                if (lostForMs < repeatMs && announcementsThisEpisode == 0) {
                    lastAction = Action.SILENT;
                    return Action.SILENT;
                }
                if (maxAnnouncements > 0
                        && announcementsThisEpisode >= maxAnnouncements) {
                    lastAction = Action.SILENT;
                    return Action.SILENT;
                }
                boolean due = (announcementsThisEpisode == 0)
                        || (nowMs - lastAnnounceMs >= repeatMs);
                if (due) {
                    announcementsThisEpisode++;
                    lastAnnounceMs = nowMs;
                    lastAction = Action.ANNOUNCE_LOSS;
                    return Action.ANNOUNCE_LOSS;
                }
                lastAction = Action.SILENT;
                return Action.SILENT;
            }

            lastAction = Action.SILENT;
            return Action.SILENT;
        }

        public void reset() {
            announcementsThisEpisode = 0;
            lastAnnounceMs = 0L;
            inLossEpisode = false;
            recoveryStarted = false;
            recoveryStartMs = 0L;
            recoveryAnnounced = false;
            lastAction = Action.SILENT;
        }
    }
}
