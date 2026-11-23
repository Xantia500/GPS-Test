package com.example.gpstest;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** Vérifie et installe une carte après que DownloadManager a terminé le transfert. */
public final class OfflineMapInstallWorker extends Worker {
    private static final int MAX_ATTEMPTS = 4;

    public OfflineMapInstallWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull @Override public Result doWork() {
        long id = getInputData().getLong(OfflineMapDownloadContract.INPUT_DOWNLOAD_ID, -1L);
        if (id < 0) return Result.failure();
        Context context = getApplicationContext();
        android.content.SharedPreferences prefs = context.getSharedPreferences(
                OfflineMapDownloadContract.PREFS, Context.MODE_PRIVATE);
        if (prefs.getLong(OfflineMapDownloadContract.KEY_ID, -1L) != id) return Result.success();

        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) return retryOrFail(context, prefs, id, "DownloadManager indisponible");

        String region = prefs.getString(OfflineMapDownloadContract.KEY_REGION, "");
        String name = prefs.getString(OfflineMapDownloadContract.KEY_NAME, "Carte");
        String sourceVersion = prefs.getString(OfflineMapDownloadContract.KEY_VERSION, "");
        if (region == null || region.isEmpty()) {
            return finishFailure(context, prefs, manager, id, "Identifiant de région absent");
        }

        try {
            DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);
            try (Cursor cursor = manager.query(query)) {
                if (cursor == null || !cursor.moveToFirst()) {
                    return retryOrFail(context, prefs, id, "Téléchargement système introuvable");
                }
                int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                if (status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_RUNNING
                        || status == DownloadManager.STATUS_PAUSED) {
                    return Result.retry();
                }
                if (status == DownloadManager.STATUS_FAILED) {
                    int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                    return finishFailure(context, prefs, manager, id,
                            "Échec du téléchargement Android (code " + reason + ")");
                }
                if (status != DownloadManager.STATUS_SUCCESSFUL) {
                    return retryOrFail(context, prefs, id, "État du téléchargement inconnu");
                }
                long total = cursor.getLong(cursor.getColumnIndexOrThrow(
                        DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                String localUriText = cursor.getString(cursor.getColumnIndexOrThrow(
                        DownloadManager.COLUMN_LOCAL_URI));
                if (localUriText == null) {
                    return finishFailure(context, prefs, manager, id,
                            "Fichier téléchargé sans chemin local");
                }
                Uri localUri = Uri.parse(localUriText);
                if (!"file".equalsIgnoreCase(localUri.getScheme())) {
                    return finishFailure(context, prefs, manager, id,
                            "Le gestionnaire Android n'a pas fourni un fichier local");
                }
                File staged = new File(localUri.getPath()).getCanonicalFile();
                File stagingRoot = context.getExternalFilesDir(
                        OfflineMapDownloadContract.STAGING_DIR);
                if (stagingRoot == null || !staged.getParentFile().equals(stagingRoot.getCanonicalFile())) {
                    return finishFailure(context, prefs, manager, id,
                            "Le fichier temporaire est hors du dossier privé attendu");
                }
                File root = context.getExternalFilesDir("offline-maps");
                if (root == null) root = new File(context.getFilesDir(), "offline-maps");
                OfflineMapRepository repository = new OfflineMapRepository(root);
                // Reprise idempotente si le processus a été tué juste après le
                // commit des métadonnées mais avant l'accusé de réception du Worker.
                String installToken = OfflineMapDownloadContract.uniqueWorkName(id);
                if (!staged.exists()) {
                    List<OfflineMapRepository.InstalledMap> installedMaps = repository.listInstalledMaps();
                    for (OfflineMapRepository.InstalledMap map : installedMaps) {
                        if (region.equals(map.regionId) && installToken.equals(map.installId)) {
                            try { manager.remove(id); } catch (RuntimeException ignored) { }
                            if (!writeResult(context, prefs, id, true,
                                    "Carte déjà installée : " + map.displayName)) return Result.retry();
                            return Result.success(new Data.Builder().putString("region", region)
                                    .putString("sha256", map.sha256).build());
                        }
                    }
                }
                if (!staged.isFile() || staged.length() <= 0
                        || staged.length() > OfflineMapRepository.MAX_MAP_FILE_BYTES
                        || (total > 0 && staged.length() != total)) {
                    return finishFailure(context, prefs, manager, id,
                            "Taille du fichier téléchargé incohérente");
                }

                setProgressAsync(new Data.Builder().putString("state", "validation")
                        .putString("region", name).build());
                OfflineMapRepository.InstalledMap installed = repository.installFile(
                        region, name, staged, MapsforgeCatalogClient.SOURCE_LABEL,
                        sourceVersion, null, () -> isStopped() || Thread.currentThread().isInterrupted(),
                        installToken);
                try { manager.remove(id); } catch (RuntimeException ignored) { }
                if (!writeResult(context, prefs, id, true, "Carte installée : " + installed.displayName
                        + " · SHA-256 local vérifié")) return Result.retry();
                return Result.success(new Data.Builder().putString("region", region)
                        .putString("sha256", installed.sha256).build());
            }
        } catch (IOException | RuntimeException error) {
            return retryOrFail(context, prefs, id, safeMessage(error));
        }
    }

    private Result retryOrFail(Context context, android.content.SharedPreferences prefs,
                               long id, String message) {
        // Un arrêt demandé par WorkManager (mise en veille, quota système ou
        // annulation) ne doit jamais être transformé en échec utilisateur.
        // WorkManager décidera s'il faut replanifier ou garder la tâche annulée.
        if (isStopped()) return Result.retry();
        if (!isPermanentValidationFailure(message)
                && getRunAttemptCount() + 1 < MAX_ATTEMPTS) return Result.retry();
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        return finishFailure(context, prefs, manager, id, message);
    }

    private static boolean isPermanentValidationFailure(String message) {
        if (message == null) return false;
        String value = message.toLowerCase(java.util.Locale.ROOT);
        return value.contains("mapsforge") || value.contains("empreinte sha-256")
                || value.contains("taille du fichier téléchargé incohérente")
                || value.contains("hors du dossier privé") || value.contains("identifiant de région absent")
                || value.contains("fichier téléchargé sans chemin local")
                || value.contains("fichier local");
    }

    private Result finishFailure(Context context, android.content.SharedPreferences prefs,
                                 DownloadManager manager, long id, String message) {
        if (manager != null) {
            try { manager.remove(id); } catch (RuntimeException ignored) { }
        }
        boolean saved = writeResult(context, prefs, id, false,
                "Installation refusée : " + message);
        return saved ? Result.failure(new Data.Builder().putString("error", message).build())
                : Result.retry();
    }

    private static boolean writeResult(Context context,
            android.content.SharedPreferences prefs, long id,
            boolean success, String message) {
        // Une exécution obsolète ne doit jamais effacer ni modifier le résultat
        // d'un téléchargement plus récent.
        if (prefs.getLong(OfflineMapDownloadContract.KEY_ID, -1L) != id) return false;
        android.content.SharedPreferences.Editor editor = prefs.edit()
                .putBoolean(OfflineMapDownloadContract.KEY_RESULT_SUCCESS, success)
                .putString(OfflineMapDownloadContract.KEY_RESULT_MESSAGE, message)
                .putLong(OfflineMapDownloadContract.KEY_RESULT_TIME, System.currentTimeMillis())
                .remove(OfflineMapDownloadContract.KEY_ID)
                .remove(OfflineMapDownloadContract.KEY_REGION)
                .remove(OfflineMapDownloadContract.KEY_NAME)
                .remove(OfflineMapDownloadContract.KEY_PATH)
                .remove(OfflineMapDownloadContract.KEY_VERSION)
                .remove(OfflineMapDownloadContract.KEY_INSTALLING);
        if (success) {
            long revision = prefs.getLong(OfflineMapDownloadContract.KEY_MAPS_REVISION, 0L);
            editor.putLong(OfflineMapDownloadContract.KEY_MAPS_REVISION,
                    OfflineMapDownloadContract.nextMapsRevision(revision));
        }
        if (!editor.commit()) return false;
        if (success) {
            try {
                context.sendBroadcast(new android.content.Intent(
                        OfflineMapDownloadContract.ACTION_MAPS_UPDATED)
                        .setPackage(context.getPackageName()));
            } catch (RuntimeException ignored) {
                // MapActivity relit aussi la révision dans onResume().
            }
        }
        return true;
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty() ? "erreur de validation inconnue" : message;
    }
}
