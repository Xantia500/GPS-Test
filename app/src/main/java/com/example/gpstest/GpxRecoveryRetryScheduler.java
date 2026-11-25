package com.example.gpstest;

import android.content.Context;

import androidx.work.BackoffPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** Déclenche une seule reprise persistante des publications/récupérations en attente. */
public final class GpxRecoveryRetryScheduler {
    private GpxRecoveryRetryScheduler() { }

    public static boolean enqueue(Context context) {
        if (context == null) return false;
        Context app = context.getApplicationContext();
        try {
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(GpxRecoveryRetryWorker.class)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15L, TimeUnit.SECONDS)
                    .addTag(GpxRecoveryRetryWorker.UNIQUE_WORK_NAME)
                    .build();
            WorkManager.getInstance(app).enqueueUniqueWork(
                    GpxRecoveryRetryWorker.UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.KEEP, request);
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
