package com.example.gpstest;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * SORTIR LES DONNÉES DE L'APP.
 *
 * POURQUOI CETTE CLASSE EXISTE : c'est la réponse à un défaut structurel
 * complet, pas à un bug.
 *
 * Les traces sont écrites dans /Android/data/com.example.gpstest/files/.
 * Ce dossier est invisible depuis un gestionnaire de fichiers depuis
 * Android 11, et inaccessible aux autres applications. L'utilisateur
 * enregistrait donc des traces qu'il ne pouvait pas récupérer autrement
 * qu'en branchant un câble et en utilisant ADB. Pour un outil GNSS, c'est
 * disqualifiant : une trace qui ne peut pas s'ouvrir dans QGIS n'a pas
 * rempli son contrat.
 *
 * DEUX CHEMINS DE SORTIE, complémentaires :
 *   1. PARTAGER (ACTION_SEND + FileProvider) : envoie le fichier vers
 *      n'importe quelle app — mail, Drive, Telegram, QGIS, OsmAnd. Ne
 *      nécessite AUCUNE permission de stockage.
 *   2. FICHIERS DE L'APP : rappelle le chemin exact et ouvre le sélecteur
 *      système quand c'est possible.
 *
 * Ce qui n'est PAS fait, volontairement : écrire dans le stockage partagé
 * (Documents/). Cela exigerait des permissions de stockage que Google
 * restreint depuis Android 10, et le partage couvre le besoin réel.
 */
public final class ExportHelper {

    private ExportHelper() { }

    /** Autorité du FileProvider : package + ".files", déclaré au manifest. */
    private static final String AUTHORITY_SUFFIX = ".files";

    /** Nombre maximal de fichiers listés, pour ne pas noyer l'utilisateur. */
    private static final int MAX_LIST = 20;

    // ------------------------------------------------------------------
    // Inventaire des fichiers produits par l'app
    // ------------------------------------------------------------------

    /** Dossier où TOUT est écrit : traces GPX, mesures CSV, journal. */
    public static File dataDir(Context ctx) {
        return ctx.getExternalFilesDir(null);
    }

    /**
     * Liste les fichiers GPX du plus récent au plus ancien.
     * Ne lève jamais : un stockage indisponible donne une liste vide.
     */
    public static List<File> listGpx(Context ctx) {
        File dir = dataDir(ctx);
        List<File> out = new ArrayList<>();
        if (dir == null) return out;
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (f.isFile() && f.getName().toLowerCase().endsWith(".gpx")) out.add(f);
        }
        sortNewestFirst(out);
        if (out.size() > MAX_LIST) return out.subList(0, MAX_LIST);
        return out;
    }

    public static List<File> listCsv(Context ctx) {
        File dir = dataDir(ctx);
        List<File> out = new ArrayList<>();
        if (dir == null) return out;
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (f.isFile() && f.getName().toLowerCase().endsWith(".csv")) out.add(f);
        }
        sortNewestFirst(out);
        if (out.size() > MAX_LIST) return out.subList(0, MAX_LIST);
        return out;
    }

    private static void sortNewestFirst(List<File> list) {
        list.sort(new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
    }

    /** Taille totale des données produites, en octets. */
    public static long totalBytes(Context ctx) {
        File dir = dataDir(ctx);
        if (dir == null) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        long n = 0;
        for (File f : files) if (f.isFile()) n += f.length();
        return n;
    }

    /** « 1,2 Mo » — format lisible pour un humain. */
    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " o";
        if (bytes < 1048576) return String.format(java.util.Locale.FRANCE, "%.0f Ko", bytes / 1024.0);
        if (bytes < 1073741824L) return String.format(java.util.Locale.FRANCE, "%.2f Mo", bytes / 1048576.0);
        return String.format(java.util.Locale.FRANCE, "%.2f Go", bytes / 1073741824.0);
    }

    // ------------------------------------------------------------------
    // Partage
    // ------------------------------------------------------------------

    /**
     * URI content:// pour un fichier du dossier privé.
     * Renvoie null si le fichier est hors du périmètre déclaré, ou si le
     * FileProvider n'est pas correctement configuré (ce serait une erreur de
     * manifest, on ne fait pas crasher l'app pour autant).
     */
    public static Uri uriFor(Context ctx, File f) {
        try {
            return FileProvider.getUriForFile(
                    ctx, ctx.getPackageName() + AUTHORITY_SUFFIX, f);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Partage un ou plusieurs fichiers via le sélecteur Android.
     *
     * Renvoie true si le sélecteur a été lancé. Aucune permission n'est
     * requise : FileProvider accorde un droit de lecture temporaire et
     * nominatif à l'application choisie par l'utilisateur.
     */
    public static boolean shareFiles(Context ctx, List<File> files, String subject) {
        if (files == null || files.isEmpty()) return false;

        ArrayList<Uri> uris = new ArrayList<>();
        for (File f : files) {
            Uri u = uriFor(ctx, f);
            if (u != null) uris.add(u);
        }
        if (uris.isEmpty()) return false;

        Intent send;
        if (uris.size() == 1) {
            send = new Intent(Intent.ACTION_SEND);
            send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            send = new Intent(Intent.ACTION_SEND_MULTIPLE);
            send.putExtra(Intent.EXTRA_STREAM, uris);
        }
        send.setType("application/gpx+xml");
        // MIME générique en secours : certains gestionnaires ne connaissent
        // pas le type GPX et n'afficheraient aucune cible.
        send.setType("*/*");
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (subject != null) send.putExtra(Intent.EXTRA_SUBJECT, subject);
        // ClipData : indispensable pour que le droit de lecture soit
        // effectivement transmis sur toutes les versions d'Android.
        send.setClipData(ClipData.newRawUri("trace", uris.get(0)));

        try {
            ctx.startActivity(Intent.createChooser(send,
                    ctx.getString(com.example.gpstest.R.string.share_chooser_title))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception e) {
            Toast.makeText(ctx, "Aucune application pour partager",
                    Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    /** Partage la trace la plus récente. Pratique juste après un arrêt. */
    public static boolean shareLatestGpx(Context ctx) {
        List<File> gpx = listGpx(ctx);
        if (gpx.isEmpty()) return false;
        return shareFiles(ctx, Arrays.asList(gpx.get(0)), "Trace GPS");
    }
}
