package com.example.gpstest;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;

/**
 * LE THÈME : suivre le téléphone par défaut, et laisser l'utilisateur décider.
 *
 * ══════════════════════════════════════════════════════════════════════
 * LES TROIS MODES, ET POURQUOI IL Y EN A TROIS
 * ══════════════════════════════════════════════════════════════════════
 *
 *   SYSTÈME  — l'app suit le réglage du téléphone (défaut, recommandé).
 *   CLAIR    — toujours clair, même si le téléphone est en mode nuit.
 *   SOMBRE   — toujours sombre.
 *
 * Pourquoi proposer CLAIR et SOMBRE alors que le téléphone a déjà ce
 * réglage : parce qu'un utilisateur peut vouloir une app GPS toujours
 * sombre (usage de nuit, en voiture) alors que son téléphone bascule
 * automatiquement au coucher du soleil. Un mode explicite est une attente
 * normale depuis Android 10 ; son absence se remarque.
 *
 * ══════════════════════════════════════════════════════════════════════
 * COMMENT ÇA MARCHE, SANS BIBLIOTHÈQUE ET SANS RECRÉER L'ACTIVITÉ
 * ══════════════════════════════════════════════════════════════════════
 *
 * Le « mode nuit » d'AppCompat (AppCompatDelegate.setDefaultNightMode)
 * existe, mais il passe son temps à RECRÉER les Activities : à chaque
 * changement, l'écran clignote et tout état non sauvegardé est perdu.
 * Pour une app de mesure GNSS en cours d'enregistrement, c'est inacceptable.
 *
 * On utilise donc directement l'API système, disponible depuis l'API 17 :
 *
 *     Resources.getConfiguration().uiMode  &= ~UI_MODE_NIGHT_MASK
 *     Resources.getConfiguration().uiMode  |= UI_MODE_NIGHT_YES/NO
 *     Resources.updateConfiguration(cfg, metrics)
 *
 * C'est exactement ce que fait AppCompat en interne. La différence : c'est
 * nous qui appelons recreate(), une seule fois, au moment où l'utilisateur
 * change le réglage — et pas le framework, en boucle, quand il en a envie.
 *
 * ══════════════════════════════════════════════════════════════════════
 * LA LIMITE HONNÊTE DE updateConfiguration
 * ══════════════════════════════════════════════════════════════════════
 *
 * updateConfiguration() est dépréciée depuis l'API 25 pour les changements
 * de configuration « généraux » (densité, langue…). Elle reste le seul
 * mécanisme synchrone pour forcer uiMode, et elle fonctionne — mais elle
 * n'est PAS officiellement garantie à long terme. Une migration vers
 * AppCompatDelegate reste possible plus tard ; elle aurait un coût (recréation
 * automatique, comportement moins prévisible) qu'on ne veut pas payer ici.
 *
 * Le code est donc isolé dans cette classe : si un jour il faut changer de
 * mécanisme, un seul fichier bouge.
 */
public final class ThemeManager {

    private ThemeManager() { }

    /** Alias : la logique pure vit dans ThemeMode (testée hors appareil). */
    public static final class Mode {
        public static final ThemeMode.Mode SYSTEM = ThemeMode.Mode.SYSTEM;
        public static final ThemeMode.Mode LIGHT  = ThemeMode.Mode.LIGHT;
        public static final ThemeMode.Mode DARK   = ThemeMode.Mode.DARK;
        private Mode() { }
    }

    /** Mode persisté (défaut : suivre le téléphone). */
    public static ThemeMode.Mode read(Context ctx) {
        return ThemeMode.Mode.fromId(Prefs.readThemeMode(ctx));
    }

    public static void write(Context ctx, ThemeMode.Mode m) {
        Prefs.writeThemeMode(ctx, (m == null ? ThemeMode.Mode.SYSTEM : m).id);
    }

    /**
     * Applique un mode au contexte de l'application.
     *
     * À APPELER AVANT setContentView(), dans onCreate(). Après, les vues sont
     * déjà créées avec les anciennes couleurs : on verrait un écran à moitié
     * repeint.
     *
     * @return true si la configuration a effectivement changé (l'appelant
     *         doit alors recréer l'Activity).
     */
    public static boolean applyTo(Context ctx, ThemeMode.Mode m) {
        if (m == null) m = ThemeMode.Mode.SYSTEM;

        // CAPTURE EN PREMIER, AVANT TOUT FORÇAGE. Après, il est trop tard :
        // la valeur du système aura été écrasée.
        int sysBits = captureSystemNight(ctx);

        Configuration cfg = new Configuration(ctx.getResources().getConfiguration());
        int before = cfg.uiMode & Configuration.UI_MODE_NIGHT_MASK;

        int wanted;
        switch (m) {
            case LIGHT: wanted = Configuration.UI_MODE_NIGHT_NO; break;
            case DARK:  wanted = Configuration.UI_MODE_NIGHT_YES; break;
            case SYSTEM:
            default:
                // Suivre le téléphone = RESTAURER la valeur mémorisée, pas
                // « ne rien faire ». Voir la javadoc de systemNightBits.
                wanted = sysBits;
                if (wanted == Configuration.UI_MODE_NIGHT_UNDEFINED) return false;
                break;
        }

        if (before == wanted) return false;   // déjà dans le bon mode

        cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | wanted;
        ctx.getResources().updateConfiguration(cfg,
                ctx.getResources().getDisplayMetrics());
        return true;
    }

    /**
     * RÉGLAGE NUIT DU SYSTÈME, CAPTURÉ AVANT TOUT FORÇAGE.
     *
     * ══════════════════════════════════════════════════════════════════
     * LE PROBLÈME QUE CETTE VARIABLE RÉSOUT
     * ══════════════════════════════════════════════════════════════════
     *
     * Quand l'utilisateur choisit « Toujours clair », on ÉCRASE uiMode dans
     * les Resources de l'application. Cette modification vit AUSSI LONGTEMPS
     * QUE LE PROCESSUS. Conséquence : tant qu'il tourne, plus personne — y
     * compris nous — ne peut relire le vrai réglage du téléphone. Il a été
     * écrasé.
     *
     * Scénario réel sans cette variable :
     *   1. téléphone en mode nuit, l'app force CLAIR ;
     *   2. l'utilisateur repasse à « Suivre le téléphone » → on ne force
     *      plus rien, donc on ne restaure rien ;
     *   3. l'app RESTE claire alors que le téléphone est en mode nuit, et
     *      ce jusqu'au prochain redémarrage du processus.
     *
     * Correctif : à la PREMIÈRE utilisation dans le processus — donc avant
     * qu'on ait pu forcer quoi que ce soit — on lit le vrai réglage et on
     * le MÉMORISE. Ensuite, « Suivre le téléphone » restaure cette valeur
     * au lieu de ne rien faire.
     *
     * Une valeur entière négative (UI_MODE_NIGHT_UNDEFINED) reste gérée :
     * elle signifie « le système ne dit rien », auquel cas on ne force pas.
     */
    private static int systemNightBits = -1;

    /** À appeler en tout premier dans applyTo(). Une seule capture par processus. */
    private static int captureSystemNight(Context ctx) {
        if (systemNightBits == -1) {
            systemNightBits = ctx.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
        }
        return systemNightBits;
    }

    /** Réinitialise la capture. Utilisé par les tests uniquement. */
    static void resetForTest() {
        systemNightBits = -1;
    }

    /**
     * Vrai si l'interface est ACTUELLEMENT en mode sombre.
     *
     * Utilisé par les vues qui dessinent au Canvas : elles n'ont pas de
     * layout, donc pas d'accès à @color. Encore une fois, on lit l'état
     * réel plutôt que de le dupliquer.
     */
    public static boolean isNight(Context ctx) {
        int mask = ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mask == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * Couleur d'une ressource, avec repli si elle n'existe pas.
     *
     * Utile aux vues dessinées (SkyView, SevenSegmentView) qui n'ont pas de
     * ColorStateList. On ne lève jamais : une vue custom qui plante parce
     * qu'une couleur manque serait un crash sur un écran d'affichage.
     */
    public static int color(Context ctx, int resId, int fallback) {
        try {
            return (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    ? ctx.getResources().getColor(resId, ctx.getTheme())
                    : ctx.getResources().getColor(resId);
        } catch (Exception e) {
            return fallback;
        }
    }
}
