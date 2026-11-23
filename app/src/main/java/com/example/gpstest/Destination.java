package com.example.gpstest;

import android.content.Context;
import android.content.UriPermission;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.List;

/**
 * OÙ VONT LES TRACES — et pourquoi ce n'est pas aussi simple qu'un chemin.
 *
 * ══════════════════════════════════════════════════════════════════════
 * LA QUESTION QUE VOUS AVEZ POSÉE, ET LA RÉPONSE HONNÊTE
 * ══════════════════════════════════════════════════════════════════════
 *
 * « Pourquoi ne pas écrire DIRECTEMENT dans le répertoire choisi, au lieu
 *  d'écrire puis copier à la fin ? »
 *
 * Réponse courte : parce que, sur Android, le répertoire choisi par
 * l'utilisateur n'est pas un chemin de fichier. C'est une URI de document.
 *
 *     file:///storage/emulated/0/Android/data/.../files/Trace_….GPX
 *     content://com.android.externalstorage.documents/tree/primary%3ADownload
 *
 * La première est un fichier : open, write, flush, close, append.
 * La seconde est un CONTRAT passée à une AUTRE APPLICATION
 * (DocumentsUI / MediaProvider). On ne l'ouvre pas : on lui demande
 * d'écrire à notre place, document par document.
 *
 * Conséquence directe, et c'est le point que personne n'anticipe :
 * **il n'y a pas d'append sur une URI de document.** La seule façon
 * d'ajouter 343 octets à la fin est de réécrire le document entier.
 * Or nos traces font 1,18 Mo par heure et l'utilisateur peut lancer une
 * randonnée de 8 heures.
 *
 * ══════════════════════════════════════════════════════════════════════
 * LES TROIS STRATÉGIES POSSIBLES, COMPARÉES
 * ══════════════════════════════════════════════════════════════════════
 *
 * A. Écrire DIRECTEMENT dans l'URI, en réécrivant tout à chaque flush.
 *    Coût : à 8 h de trace, 9,4 Mo par flush, toutes les 2 s = 4,7 Mo/s
 *    d'écriture CONTINUE. Sur 1 h : réécriture de 1,2 Mo 1 800 fois =
 *    2,1 Go écrits pour 1,18 Mo utiles. C'est exactement le scénario
 *    « 3600 fois la même zone » que vous soupçonniez — sauf qu'ici il
 *    serait RÉEL. Et le contrat SAF ne garantit pas que l'écriture soit
 *    atomique : un kill pendant une réécriture laisse un fichier tronqué.
 *    → REJETÉ. Techniquement réalisable, structurellement absurde.
 *
 * B. Écrire directement avec PIPE (ParcelFileDescriptor en mode append).
 *    Il EXISTE une méthode openFileDescriptor(uri, "wa") qui donne un
 *    descripteur de fichier en écriture + ajout. C'est la voie propre...
 *    mais le fournisseur de documents n'est PAS obligé de la supporter :
 *    Android lève UnsupportedOperationException ou FileNotFoundException
 *    sur beaucoup de fournisseurs (Drive, OneDrive, SMB, MTP).
 *    → En faire la SEULE voie, c'est une app qui marche chez vous et
 *      plante chez le voisin. C'est inacceptable pour un enregistreur.
 *
 * C. Écrire LOCALEMENT (chemin de fichier, append, flush, robuste) puis
 *    PUBLIER une copie complète à la fermeture du segment.
 *    Le contrat SAF n'est utilisé que pour createDocument + écriture
 *    complète, sans toucher au fichier local append-only.
 *    → RETENU.
 *
 * ══════════════════════════════════════════════════════════════════════
 * CE QUE L'UTILISATEUR VOIT, ET QUI EST LE VRAI CRITÈRE
 * ══════════════════════════════════════════════════════════════════════
 *
 * Il choisit « Téléchargements » une fois. À partir de là :
 *   - l'enregistrement continue même si son dossier, sa carte SD ou son
 *     compte cloud devient inaccessible (tunnel, avion, batterie) ;
 *   - la source locale est écrite en continu et reste la copie de référence ;
 *   - le dossier choisi reçoit une copie complète à chaque fermeture de
 *     segment (pause/arrêt). Pendant un segment actif, la copie externe peut
 *     donc être en retard ; l'interface doit le dire explicitement ;
 *   - une erreur SAF ne bloque jamais le suivi ni l'écriture locale.
 *
 * Cette stratégie évite de réécrire tout le document toutes les 30 s : le
 * coût cumulé serait quadratique sur les longues traces. L'utilisateur
 * récupère la copie externe à la clôture du segment ; le fichier privé est
 * la source de vérité pendant l'enregistrement.
 *
 * ══════════════════════════════════════════════════════════════════════
 * LE PIÈGE DU QUOTA DE PERMISSIONS (celui qui casse les apps en réel)
 * ══════════════════════════════════════════════════════════════════════
 *
 * takePersistableUriPermission() persiste l'autorisation d'accès. Android
 * plafonne le nombre d'autorisations persistées à **128 par application**.
 * Une app qui persiste une autorisation à chaque fois que l'utilisateur
 * parcourt un dossier finit par épuiser ce quota, sans message clair, et
 * les nouvelles demandes échouent silencieusement.
 *
 * D'où clearOldPermissions() : on ne garde JAMAIS plus d'une autorisation.
 * Quand l'utilisateur change de répertoire, on libère l'ancienne.
 */
public final class Destination {

    private Destination() { }

    /** Alias de compatibilité : la logique pure vit dans DestPresets. */
    public static final DestPresets.Preset[] PRESETS = DestPresets.PRESETS;

    // ------------------------------------------------------------------
    // État de la destination
    // ------------------------------------------------------------------

    /** URI persistée, ou null si aucun répertoire choisi. */
    public static Uri treeUri(Context ctx) {
        String s = Prefs.readDestUri(ctx);
        if (s == null || s.isEmpty()) return null;
        try {
            return Uri.parse(s);
        } catch (Exception e) {
            return null;   // URI corrompue en préférences : on repart au défaut
        }
    }

    /**
     * Vérifie que l'autorisation existe ENCORE.
     *
     * Ne jamais se fier à l'URI seule : l'utilisateur peut révoquer l'accès
     * depuis les réglages Android, ou le fournisseur (carte SD retirée,
     * compte cloud déconnecté) peut disparaître. Dans ce cas, l'URI est
     * toujours en préférences mais toute écriture échouera. On le DÉTECTE
     * à l'avance, et on continue localement sans rien perdre.
     */
    public static boolean isGranted(Context ctx, Uri uri) {
        if (uri == null) return false;
        try {
            List<UriPermission> perms =
                    ctx.getContentResolver().getPersistedUriPermissions();
            for (UriPermission p : perms) {
                if (p.isWritePermission() && p.getUri().equals(uri)) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    /** true si une destination externe est configurée ET encore autorisée. */
    public static boolean isExternalActive(Context ctx) {
        Uri u = treeUri(ctx);
        return u != null && isGranted(ctx, u);
    }

    /**
     * Mémorise la destination. Libère TOUTE autre autorisation persistée.
     *
     * C'est le remède au quota de 128 : une seule autorisation à la fois.
     */
    public static void setTreeUri(Context ctx, Uri uri) {
        if (uri == null) return;
        try {
            // On libère les autres AVANT de prendre la nouvelle : si le quota
            // est plein, l'ancienne libération rend de la place.
            List<UriPermission> perms =
                    ctx.getContentResolver().getPersistedUriPermissions();
            for (UriPermission p : perms) {
                if (!p.getUri().equals(uri)) {
                    try {
                        ctx.getContentResolver().releasePersistableUriPermission(
                                p.getUri(),
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    } catch (Exception ignored) { }
                }
            }
            ctx.getContentResolver().takePersistableUriPermission(uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {
            // Autorisation non persistable (fournisseur exotique) : on garde
            // quand même l'URI, elle fonctionnera tant que le process vit.
        }
        Prefs.writeDestUri(ctx, uri.toString());
    }

    /** Revenir au dossier privé de l'app, et libérer l'autorisation. */
    public static void clear(Context ctx) {
        // ORDRE CRITIQUE : on libère l'autorisation AVANT d'oublier l'URI.
        // Ma première version effaçait la préférence d'abord ; si le
        // release échouait ensuite, l'autorisation persistée restait
        // orpheline et consommait une place sur les 128 autorisées —
        // définitivement, puisque plus personne ne connaissait son URI.
        // Une fuite silencieuse et irréversible.
        Uri u = treeUri(ctx);
        if (u != null) {
            try {
                ctx.getContentResolver().releasePersistableUriPermission(u,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) { }
        }
        Prefs.clearDest(ctx);
    }

    /**
     * URI du dossier « Téléchargements » construit sans lire le disque.
     *
     * On fabrique l'URI à la main (volume "primary", domaine
     * "downloads") au lieu d'interroger le système : c'est le seul moyen
     * d'obtenir un identifiant de dossier SANS afficher de sélecteur. Le
     * nom du volume est toujours "primary" pour la mémoire interne.
     */
    public static Uri downloadsTreeUri() {
        return externalStorageTreeUri("primary:Download");
    }

    /** URI « tree » d'un dossier du stockage partagé, sans sélecteur. */
    public static Uri externalStorageTreeUri(String documentId) {
        return DocumentsContract.buildTreeDocumentUri(
                "com.android.externalstorage.documents", documentId);
    }

    /**
     * Sélectionne un des répertoires préréglés.
     *
     * Téléchargements et Documents appartiennent au fournisseur
     * "externalstorage" : on peut donc les cibler directement. Pour tout
     * autre emplacement (carte SD, cloud), il faut le sélecteur, et
     * l'utilisateur y consent explicitement — c'est la règle Android.
     *
     * @return true si la destination a été enregistrée.
     */
    public static boolean applyPreset(Context ctx, DestPresets.Preset preset) {
        if (preset == null) return false;
        if (preset.isAppPrivate()) {
            clear(ctx);
            return true;
        }
        // CORRECTIF D'UN BUG RÉEL : la première version construisait
        // l'URI de « Téléchargements » pour TOUS les presets, si bien que
        // choisir « Documents » envoyait les traces dans Download. Le
        // document id vient maintenant du preset lui-même.
        //
        // On vérifie aussi que le fournisseur sait écrire là : une URI
        // fabriquée peut désigner un dossier absent. On teste AVANT de
        // promettre, jamais après.
        Uri tree = externalStorageTreeUri(preset.documentId());
        try {
            // Écriture d'essai d'un document minuscule puis suppression :
            // le seul test honnête. Aucune API ne répond « ce dossier est
            // inscriptible » sans essayer.
            if (!probe(ctx, tree)) return false;
        } catch (Exception e) {
            return false;
        }
        setTreeUri(ctx, tree);
        return true;
    }

    /** Écrit puis supprime un document de test. Aucun effet de bord visible. */
    private static boolean probe(Context ctx, Uri tree) {
        Uri doc = null;
        try {
            Uri dir = DocumentsContract.buildDocumentUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree));
            doc = DocumentsContract.createDocument(
                    ctx.getContentResolver(), dir, "text/plain", ".gpstest-probe");
            if (doc == null) return false;
            OutputStream os = ctx.getContentResolver().openOutputStream(doc, "w");
            if (os == null) return false;
            os.write('x');
            os.flush();
            os.close();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (doc != null) {
                try { DocumentsContract.deleteDocument(ctx.getContentResolver(), doc); }
                catch (Exception ignored) { }
            }
        }
    }

    // ------------------------------------------------------------------
    // Publication d'un fichier local vers le répertoire choisi
    //
    // TROIS BUGS QUE LA PREMIÈRE VERSION DE CE CODE AVAIT, ET LEUR CORRECTIF
    // (le détail vaut la peine : ce sont les pièges classiques du SAF)
    //
    // 1. « createDocument » à chaque publication crée un fichier DE PLUS.
    //    SAF ne remplace pas un document existant : il en crée un autre, avec
    //    un nom dérivé (« Trace_….GPX (1) »). La version actuelle ne publie
    //    qu'une fois à la fermeture de chaque segment ; le document est donc
    //    créé à ce moment-là et son URI reste attachée au contexte du segment.
    //
    // 2. Un snapshot pris avant la fermeture n'est pas un GPX complet :
    //    le pied (</trkseg></trk></gpx>) n'est écrit qu'à la fermeture.
    //    La stratégie actuelle ne publie donc qu'après flush + sync + close.
    //    Le contrôle/ajout de pied reste une sécurité de dernier recours.
    //
    // 3. Réécrire « en place » peut tronquer si le processus meurt en pleine
    //    écriture. Cela n'affecte pas la source locale append-only, qui reste
    //    l'original. La copie SAF est un export secondaire récupérable.
    // ------------------------------------------------------------------

    /**
     * Crée le document de destination, une fois par segment.
     *
     * @return URI du document, ou null si le fournisseur refuse.
     */
    public static Uri createDocument(Context ctx, Uri tree, String displayName) {
        if (tree == null) return null;
        try {
            Uri dir = DocumentsContract.buildDocumentUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree));
            return DocumentsContract.createDocument(ctx.getContentResolver(), dir,
                    "application/gpx+xml", displayName);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Réécrit le document de destination avec le contenu du fichier local.
     *
     * Écriture SÉQUENTIELLE complète : c'est le seul mode que tous les
     * fournisseurs SAF implémentent de la même façon.
     *
     * @param doc      URI du document créé par createDocument().
     * @param local    fichier local, source de vérité.
     * @param addFoot  true si le fichier local n'a pas encore son pied GPX
     *                 (publication en cours de session).
     * @return true si la copie est complète et fermée.
     */
    public static boolean overwrite(Context ctx, Uri doc, File local, boolean addFoot) {
        if (doc == null || local == null || !local.isFile()) return false;
        // try-with-resources est important ici : une exception à close() est
        // aussi un échec de publication, pas un succès silencieux après flush.
        try (InputStream in = new FileInputStream(local);
             OutputStream out = ctx.getContentResolver().openOutputStream(doc, "wt")) {
            if (out == null) return false;
            // « wt » = write + truncate. Avec « w » seul, certains fournisseurs
            // gardent les octets de l'ancien contenu si le nouveau est plus court.
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (n == 0) continue;
                out.write(buf, 0, n);
            }
            if (addFoot) out.write(GpxWriter.footer().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * true si le fichier se termine DÉJÀ par la balise de fermeture GPX.
     *
     * ══════════════════════════════════════════════════════════════════
     * POURQUOI ON LIT LE FICHIER AU LIEU DE SE FIER À UN DRAPEAU
     * ══════════════════════════════════════════════════════════════════
     *
     * La première version utilisait un booléen `segmentClosed`, mis à true
     * au moment où l'on DEMANDE la fermeture. Or la fermeture est écrite
     * dans une AUTRE file, sur un autre thread. Entre le moment où le
     * drapeau passe à true et le moment où les octets du pied arrivent sur
     * le disque, une publication concurrente pouvait lire le drapeau, en
     * déduire « pas besoin d'ajouter le pied », et publier un GPX
     * sans </gpx> — invalide, et donc inouvrable dans QGIS.
     *
     * En lisant les 16 derniers octets, on interroge la SEULE source de
     * vérité : le fichier lui-même. Aucun ordre d'exécution ne peut rendre
     * cette réponse fausse. Coût : une lecture de 16 octets.
     *
     * Règle générale : un état qui duplique une information déjà présente
     * dans les données finira désynchronisé. Ici, la donnée est le fichier.
     */
    public static boolean endsWithGpxClose(File local) {
        if (local == null || !local.isFile()) return false;
        RandomAccessFile raf = null;
        try {
            long len = local.length();
            if (len < 8) return false;
            raf = new RandomAccessFile(local, "r");
            int tail = (int) Math.min(16L, len);
            raf.seek(len - tail);
            byte[] b = new byte[tail];
            raf.readFully(b);
            return new String(b, "UTF-8").contains("</gpx>");
        } catch (Exception e) {
            // En cas de doute : on considère qu'il FAUT le pied. Une balise
            // fermante en trop casserait le XML ; une manquante aussi. Le
            // choix par défaut est celui qui ne dépend d'aucun autre état.
            return false;
        } finally {
            if (raf != null) try { raf.close(); } catch (Exception ignored) { }
        }
    }

    public static void deleteDocument(Context ctx, Uri doc) {
        if (doc == null) return;
        try { DocumentsContract.deleteDocument(ctx.getContentResolver(), doc); }
        catch (Exception ignored) { }
    }

    /**
     * Libellé lisible de la destination, pour l'afficher EN CLAIR à
     * l'utilisateur. C'est le texte qu'il verra dans la confirmation de
     * démarrage et dans les réglages.
     *
     * On n'affiche jamais l'URI brute : elle est illisible
     * (« content://com.android.externalstorage.documents/tree/primary%3ADownload »).
     */
    public static String describe(Context ctx) {
        Uri u = treeUri(ctx);
        if (u == null) return "Dossier privé de l'application (invisible dans un gestionnaire de fichiers)";
        if (!isGranted(ctx, u)) {
            return "Dossier choisi — ACCÈS PERDU (trace enregistrée localement)";
        }
        return "Dossier choisi : " + displayName(ctx, u);
    }

    /**
     * Où le fichier apparaîtra RÉELLEMENT, en chemin lisible par un humain.
     *
     * C'est la deuxième question de tout utilisateur : « d'accord, mais je
     * le trouve où ? ». On répond avec un chemin qu'il peut suivre :
     * « Téléchargements » se traduit par Internal storage/Download.
     */
    public static String humanPath(Context ctx) {
        Uri u = treeUri(ctx);
        if (u == null) {
            File f = ctx.getExternalFilesDir(null);
            return f == null ? "(stockage indisponible)" : f.getAbsolutePath();
        }
        if (!isGranted(ctx, u)) {
            File f = ctx.getExternalFilesDir(null);
            String local = f == null ? "(stockage indisponible)" : f.getAbsolutePath();
            return "Accès perdu — actuellement : " + local;
        }
        return "Stockage interne / " + displayName(ctx, u);
    }

    /**
     * Nom à AFFICHER pour la destination courante.
     *
     * Trois sources, par ordre de priorité :
     *   1. l'identifiant STABLE du preset ("download"…) -> son libellé
     *      courant, donc toujours à jour et traduisible ;
     *   2. le libellé mémorisé au moment du choix — nécessaire pour un
     *      dossier arbitraire, qu'on ne sait pas recalculer ;
     *   3. le dernier segment de l'URI, en dernier recours.
     *
     * POURQUOI PAS LE LIBELLÉ SEUL, COMME AVANT : il est figé au moment du
     * choix. Un utilisateur qui avait choisi « Téléchargements » gardait ce
     * texte même après avoir changé la langue de l'app. L'identifiant stable
     * corrige cela — c'est la même leçon que pour le thème.
     */
    private static String displayName(Context ctx, Uri u) {
        DestPresets.Preset p = DestPresets.byId(Prefs.readDestPreset(ctx));
        if (p != null) return p.label;

        String name = Prefs.readDestLabel(ctx);
        if (name != null && !name.isEmpty()) return name;

        return lastPathSegment(u);
    }

    /** Dernier segment d'une URI « tree » : le nom du dossier.
     *  La logique de nommage est PURE et vit dans DestPresets (testée). */
    private static String lastPathSegment(Uri u) {
        try {
            return DestPresets.labelOf(DocumentsContract.getTreeDocumentId(u));
        } catch (Exception e) {
            return "dossier";
        }
    }
}
