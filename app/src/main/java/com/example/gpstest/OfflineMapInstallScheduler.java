package com.example.gpstest;

import android.content.Context;

import androidx.work.BackoffPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** Programme exactement une finalisation persistante par téléchargement Android. */
public final class OfflineMapInstallScheduler {
    private OfflineMapInstallScheduler() { }

    public static void enqueue(Context context, long downloadId) {
        if (context == null || downloadId < 0) return;
        Context app = context.getApplicationContext();
        android.content.SharedPreferences prefs = app.getSharedPreferences(
                OfflineMapDownloadContract.PREFS, Context.MODE_PRIVATE);
        if (prefs.getLong(OfflineMapDownloadContract.KEY_ID, -1L) != downloadId) return;
        if (!prefs.edit().putBoolean(OfflineMapDownloadContract.KEY_INSTALLING, true).commit()) {
            throw new IllegalStateException("Impossible de mémoriser la finalisation de la carte");
        }
        try {
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(OfflineMapInstallWorker.class)
                    .setInputData(new androidx.work.Data.Builder()
                            .putLong(OfflineMapDownloadContract.INPUT_DOWNLOAD_ID, downloadId)
                            .build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                    .build();
            WorkManager.getInstance(app).enqueueUniqueWork(
                    OfflineMapDownloadContract.uniqueWorkName(downloadId),
                    ExistingWorkPolicy.KEEP, request);
        } catch (RuntimeException enqueueFailure) {
            if (prefs.getLong(OfflineMapDownloadContract.KEY_ID, -1L) == downloadId) {
                prefs.edit().putBoolean(OfflineMapDownloadContract.KEY_INSTALLING, false).commit();
            }
            throw enqueueFailure;
        }
    }
}
