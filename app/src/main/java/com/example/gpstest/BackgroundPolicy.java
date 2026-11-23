package com.example.gpstest;

/**
 * POLITIQUE D'ARRIÈRE-PLAN : que fait l'app quand l'écran s'éteint ?
 *
 * POURQUOI CETTE CLASSE EXISTE
 * Un Foreground Service garantit que le système ne nous tue pas... tant que
 * l'utilisateur veut bien payer le prix. Ce prix n'est pas le même selon l'usage :
 *
 *   - relevé GNSS sur le terrain, écran dans la poche  -> garder l'enregistrement
 *   - test au banc, téléphone posé sur la table        -> garder l'écran allumé
 *   - mesure de 10 minutes pour vérifier le SNR        -> couper à l'extinction
 *     pour ne pas vider la batterie
 *
 * C'est un choix UTILISATEUR, pas un choix de développeur. D'où cette classe :
 * l'app propose, l'utilisateur choisit, et le choix est PERSISTÉ (il survit à
 * la rotation, à la mort du process et au redémarrage du téléphone).
 *
 * Classe 100 % PURE : aucun import Android. Toute la décision (faut-il
 * suspendre, garder l'écran allumé, segmenter la trace) se teste hors
 * appareil. Le stockage des préférences vit dans Prefs.java.
 *
 * À NE PAS CONFONDRE avec :
 *   - le type de foreground service (android:foregroundServiceType="location"),
 *     qui dit au système CE QU'ON FAIT (localisation), pas COMMENT on se comporte ;
 *   - le mode "Ne pas déranger" du téléphone, sans rapport.
 */
public final class BackgroundPolicy {

    private BackgroundPolicy() { }

    /**
     * Les trois comportements possibles quand l'écran s'éteint.
     *
     * id       : entier stable, persisté dans les préférences. NE JAMAIS
     *            renuméroter : un utilisateur qui met à jour l'app garderait
     *            une valeur qui ne veut plus rien dire.
     * label    : texte affiché dans les réglages.
     * summary  : une phrase d'explication, affichée sous le label.
     */
    public enum Policy {

        /** Défaut : la trace continue écran éteint, le plus longtemps possible. */
        CONTINUE(0,
                "Continuer écran éteint (recommandé)",
                "La trace et les mesures continuent. La batterie se vide plus vite, "
                + "mais rien n'est perdu. C'est le mode d'un relevé sur le terrain."),

        /** L'écran reste allumé tant que l'app est au premier plan. */
        KEEP_SCREEN_ON(1,
                "Garder l'écran allumé",
                "L'écran ne s'éteint pas tant que l'app est visible (test au banc, "
                + "démonstration). Si vous quittez l'app, l'écran peut s'éteindre et "
                + "le mode « Continuer écran éteint » reprend la main."),

        /** Économie de batterie : on coupe le GPS quand l'écran s'éteint. */
        SUSPEND_ON_SCREEN_OFF(2,
                "Pause à l'extinction de l'écran (économie)",
                "Le GPS et l'enregistrement sont mis en pause dès que l'écran "
                + "s'éteint, et reprennent au rallumage. Économise la batterie, "
                + "mais laisse un TROU dans la trace : les points de la période "
                + "écran éteint n'existent pas.");

        public final int id;
        public final String label;
        public final String summary;

        Policy(int id, String label, String summary) {
            this.id = id;
            this.label = label;
            this.summary = summary;
        }

        /** Retrouve une politique depuis son id persisté. Jamais null. */
        public static Policy fromId(int id) {
            for (Policy p : values()) {
                if (p.id == id) return p;
            }
            return CONTINUE;   // valeur inconnue (vieille version, corruption) -> défaut
        }
    }

    // ------------------------------------------------------------------
    // Aides pures (testables sans Android)
    // ------------------------------------------------------------------

    /**
     * Faut-il demander à la fenêtre de rester allumée ?
     * Seul KEEP_SCREEN_ON le demande. Les deux autres laissent l'écran
     * s'éteindre normalement (c'est le comportement attendu d'un téléphone).
     */
    public static boolean wantsKeepScreenOn(Policy p) {
        return p == Policy.KEEP_SCREEN_ON;
    }

    /**
     * Faut-il mettre le suivi en pause quand l'écran s'éteint ?
     * Seul SUSPEND_ON_SCREEN_OFF. Attention : CONTINUE et KEEP_SCREEN_ON
     * continuent tous les deux, mais pas pour la même raison.
     */
    public static boolean suspendsOnScreenOff(Policy p) {
        return p == Policy.SUSPEND_ON_SCREEN_OFF;
    }

    /**
     * Décide si le suivi doit se mettre en pause à l'instant T.
     *
     * C'est LA fonction de décision, testable sans téléphone. Le service
     * l'appelle quand l'écran change d'état et quand la politique change ;
     * il passe ensuite par un petit verrou (plus bas) pour éviter les
     * décisions contradictoires prises depuis deux threads différents.
     *
     * Règle : on ne suspend que si la politique le demande ET que l'écran
     * est éteint. Un écran rallumé lève donc immédiatement la suspension,
     * quelle que soit la politique.
     */
    public static boolean shouldSuspend(Policy p, boolean screenOn) {
        return suspendsOnScreenOff(p) && !screenOn;
    }

    /**
     * Faut-il garer la trace dans un coin quand on perd le focus,
     * ou bien tourner une seconde trace ?
     *
     * Renvoie le suffixe à ajouter au nom de fichier, ou null pour continuer
     * le fichier courant. Deux traces distinctes valent mieux qu'une trace
     * avec un trou de 40 minutes dont personne ne se souvient : au moins le
     * fichier dit la vérité sur ce qu'il contient.
     */
    public static String segmentSuffix(Policy p, int segmentIndex) {
        if (p != Policy.SUSPEND_ON_SCREEN_OFF) return null;
        return "-p" + (segmentIndex + 1);
    }
}
