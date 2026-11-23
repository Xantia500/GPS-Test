package com.example.gpstest;

/**
 * LOGIQUE PURE de la destination : quels dossiers on propose, et comment on
 * les nomme.
 *
 * ══════════════════════════════════════════════════════════════════════
 * POURQUOI CETTE CLASSE EXISTE SÉPARÉMENT
 * ══════════════════════════════════════════════════════════════════════
 *
 * La première version mettait tout dans Destination : les presets, les
 * libellés, ET l'écriture via ContentResolver. Résultat : impossible de
 * tester la partie qui a RÉELLEMENT buggé (le preset « Documents » qui
 * construisait l'URI de « Téléchargements ») sans un téléphone.
 *
 * C'est exactement la faute que ce projet s'interdit partout ailleurs :
 * BackgroundPolicy ne contient que des règles (testables), Prefs ne contient
 * que des accès Android. Même règle ici :
 *
 *   DestPresets  -> données et calculs purs, aucun import Android, testés
 *   Destination  -> effets de bord (URIs, ContentResolver, fichiers)
 *
 * Un test qui ne peut pas tourner ne protège de rien. C'est ce qui a laissé
 * passer le bug.
 */
public final class DestPresets {

    private DestPresets() { }

    /** Un dossier proposé à l'utilisateur. */
    public static final class Preset {
        /**
         * IDENTIFIANT STABLE, indépendant de la langue.
         *
         * C'est la clé qu'on utilise pour retrouver « quel preset est
         * actif ». On ne peut PAS utiliser le libellé : il est affiché, donc
         * traduit, et il contient des apostrophes (« Dossier de
         * l'application »). Utiliser un texte affiché comme clé de
         * préférence est une faute classique : le jour où on corrige une
         * faute de frappe dans le libellé, tous les utilisateurs perdent
         * leur réglage.
         *
         *   "download" / "documents" / "app"
         */
        public final String id;
        /** Ce que l'utilisateur lit sur le bouton — traduit. */
        public final String label;
        /** Nom du dossier de premier niveau du stockage partagé
         *  (« Download », « Documents »), ou null pour le dossier privé. */
        public final String relative;

        Preset(String id, String label, String relative) {
            this.id = id;
            this.label = label;
            this.relative = relative;
        }

        /** true pour le dossier privé de l'application (aucun accès partagé). */
        public boolean isAppPrivate() {
            return relative == null;
        }

        /**
         * Identifiant de document SAF correspondant, ou null si privé.
         *
         * C'EST LA MÉTHODE QUI A CORRIGÉ LE BUG : chaque preset tire son
         * identifiant de SON propre champ, au lieu qu'une méthode unique
         * renvoie toujours celui de Téléchargements.
         */
        public String documentId() {
            return relative == null ? null : "primary:" + relative;
        }
    }

    /**
     * Les trois destinations proposées.
     *
     * POURQUOI CES TROIS-LÀ : ce sont les seuls emplacements qu'un
     * utilisateur non informaticien retrouve dans son gestionnaire de
     * fichiers ou depuis un ordinateur branché en USB. Proposer de naviguer
     * toute l'arborescence est une source d'erreur, pas une liberté.
     */
    public static final Preset[] PRESETS = {
            new Preset("download",  "Téléchargements",           "Download"),
            new Preset("documents", "Documents",                 "Documents"),
            new Preset("app",       "Dossier de l'application",  null)
    };

    /**
     * Nom lisible d'un dossier à partir de son identifiant SAF.
     *
     *   "primary:Download"              -> "Download"
     *   "primary:Download/Mes traces"   -> "Mes traces"
     *   "1B2C-3D4E:GPSTest"             -> "GPSTest"
     *   "primary:"                      -> "Racine du stockage"
     *
     * Ne lève JAMAIS : un identifiant malformé donne un nom de repli. Cette
     * fonction alimente un affichage, elle n'a pas le droit de faire tomber
     * un écran.
     */
    public static String labelOf(String docId) {
        if (docId == null) return "Dossier choisi";
        int colon = docId.indexOf(':');
        if (colon < 0) return "Dossier choisi";        // pas un identifiant SAF
        String tail = docId.substring(colon + 1);
        if (tail.isEmpty()) return "Racine du stockage";
        int slash = tail.lastIndexOf('/');
        String last = slash >= 0 ? tail.substring(slash + 1) : tail;
        return last.isEmpty() ? "Racine du stockage" : last;
    }

    /** Preset correspondant à un identifiant stable, ou null. */
    public static Preset byId(String id) {
        if (id == null) return null;
        for (Preset p : PRESETS) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }
}
