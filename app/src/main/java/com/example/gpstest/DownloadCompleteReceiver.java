package com.example.gpstest;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Relie la fin du téléchargement système à la validation persistante du fichier. */
public final class DownloadCompleteReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null
                || !DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
        long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
        if (id < 0) return;
        SharedPreferences prefs = context.getSharedPreferences(
                OfflineMapDownloadContract.PREFS, Context.MODE_PRIVATE);
        // Le receiver ignore tous les téléchargements qui ne sont pas ceux
        // explicitement initiés et mémorisés par cette application.
        if (prefs.getLong(OfflineMapDownloadContract.KEY_ID, -1L) != id) return;
        try {
            OfflineMapInstallScheduler.enqueue(context, id);
        } catch (RuntimeException schedulingFailure) {
            // L'Activity réessaie depuis le statut DownloadManager lors de son
            // prochain rafraîchissement. Ne pas faire planter le receiver système.
            if (prefs.getLong(OfflineMapDownloadContract.KEY_ID, -1L) == id) {
                prefs.edit().putBoolean(OfflineMapDownloadContract.KEY_INSTALLING, false).commit();
            }
            android.util.Log.e("GPS-Test", "Finalisation de carte non programmée", schedulingFailure);
        }
    }
}
