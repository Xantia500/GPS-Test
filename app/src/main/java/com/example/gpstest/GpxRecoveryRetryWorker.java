package com.example.gpstest;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.File;
import java.util.List;

/** Reprise différée et bornée des récupérations GPX après indisponibilité SAF ou I/O. */
public final class GpxRecoveryRetryWorker extends Worker {
    public static final String UNIQUE_WORK_NAME = "gps-test-gpx-recovery-retry";
    public static final String KEY_SUMMARY = "summary";
    public static final String KEY_PENDING = "pending";
    public static final String KEY_PUBLISHED = "published";
    public static final String KEY_LOCAL_ONLY = "local_only";
    public static final String KEY_WAITING_PERMISSION = "waiting_permission";
    public static final String KEY_INTERVENTION = "intervention";
    public static final String KEY_REGISTRY_ERROR = "registry_error";
    public static final String KEY_TRACKING_ACTIVE = "tracking_active";
    public static final String KEY_NOTICE = "notice";

    public GpxRecoveryRetryWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull @Override public Result doWork() {
        Context context = getApplicationContext();
        if (TrackingState.get().isUserWantsTracking()) {
            return Result.success(output("Reprise différée : le suivi GNSS est actif.",
                    0, 0, 0, 0, 0, false, true, false));
        }

        SessionStore.RecoveryReadResult initial = SessionStore.readRecoveriesDetailed(context);
        if (!initial.registryReadable || !initial.journalReadable
                || !initial.message.isEmpty()) {
            return Result.success(output(initial.message, initial.sessions.size(),
                    0, 0, 0, 1, true, false, false));
        }

        File directory = context.getExternalFilesDir(null);
        if (directory == null) {
            return transientOrFinish("Stockage privé des traces indisponible.",
                    initial.sessions.size(), 0, 0, 0, false, false);
        }

        int recovered = 0;
        int published = 0;
        int localOnly = 0;
        int waitingPermission = 0;
        int intervention = 0;
        boolean retryableFailure = false;
        boolean legacyNoticeShown = false;
        StringBuilder details = new StringBuilder();

        List<SessionJournal> candidates = initial.sessions;
        for (SessionJournal candidate : candidates) {
            if (isStopped()) return Result.retry();
            if (TrackingState.get().isUserWantsTracking()) break;
            if (candidate == null || !candidate.isUnexpectedRecordingInterruption()) continue;

            GpxRecoveryWorkflow.Result result = GpxRecoveryWorkflow.recoverAndPublish(
                    context, directory, candidate);
            if (result.isRecoveredLocally()) recovered++;
            if (result.isPublishedExternally()) published++;

            GpxRecoveryPublisher.Status publisherStatus = result.publication == null
                    ? null : result.publication.status;
            String publisherName = publisherStatus == null ? "" : publisherStatus.name();
            String recoveryName = result.recovery == null ? "" : result.recovery.status.name();
            GpxRecoveryRetryPolicy.Decision decision = GpxRecoveryRetryPolicy.classify(
                    result.status.name(), publisherName, recoveryName);
            switch (decision) {
                case RETRY_AUTOMATICALLY:
                    retryableFailure = true;
                    appendDetail(details, "Nouvelle tentative : " + result.message);
                    break;
                case WAIT_FOR_USER:
                    waitingPermission++;
                    appendDetail(details, "Permission à rétablir : " + result.message);
                    break;
                case NEEDS_INTERVENTION:
                    intervention++;
                    appendDetail(details, "Intervention requise : " + result.message);
                    break;
                case COMPLETE:
                default:
                    if (result.isRecoveredLocally() && result.isPublishedExternally()) {
                        appendDetail(details, "Publiée : " + result.publishedDisplayName);
                        // Inclut notamment l'information si la copie a été publiée
                        // mais n'a pas pu être inscrite dans le registre de rétention.
                        appendDetail(details, result.message);
                    } else if (result.status == GpxRecoveryWorkflow.Status.LOCAL_ONLY) {
                        localOnly++;
                        appendDetail(details, "Copie privée conservée : " + result.recoveredFileName);
                    } else if (result.isRecoveredLocally()) {
                        appendDetail(details, "Copie locale conservée : " + result.recoveredFileName);
                    }
                    break;
            }
        }

        // Compatibilité des anciens journaux qui n'ont pas pu entrer dans le
        // registre (notamment les enregistrements sans checkpoint durable).
        // On conserve le même comportement honnête : afficher une notice ou
        // refuser la réparation, sans inventer de points.
        if (candidates.isEmpty() && !TrackingState.get().isUserWantsTracking()) {
            SessionJournal legacy = SessionStore.read(context);
            if (legacy != null && legacy.isUnexpectedRecordingInterruption()) {
                GpxRecoveryWorkflow.Result result = GpxRecoveryWorkflow.recoverAndPublish(
                        context, directory, legacy);
                String recoveryName = result.recovery == null ? "" : result.recovery.status.name();
                GpxRecoveryRetryPolicy.Decision decision = GpxRecoveryRetryPolicy.classify(
                        result.status.name(), result.publication == null ? "" : result.publication.status.name(),
                        recoveryName);
                if (decision == GpxRecoveryRetryPolicy.Decision.RETRY_AUTOMATICALLY) retryableFailure = true;
                else if (decision == GpxRecoveryRetryPolicy.Decision.NEEDS_INTERVENTION) intervention++;
                appendDetail(details, result.message);
            } else if (legacy != null && legacy.isUnexpectedSessionInterruption()
                    && !legacy.noticeShown) {
                String notice = legacy.interruptionNotice();
                if (notice != null && SessionStore.markNoticeShown(context, legacy)) {
                    legacyNoticeShown = true;
                    appendDetail(details, notice);
                }
            }
        }

        SessionStore.RecoveryReadResult after = SessionStore.readRecoveriesDetailed(context);
        int remaining = after.sessions.size();
        boolean registryError = !after.registryReadable || !after.journalReadable
                || !after.message.isEmpty();
        if (registryError) {
            intervention++;
            appendDetail(details, after.message);
        }

        int publicationEntries = 0;
        boolean publicationRegistryError = false;
        try {
            publicationEntries = new RecoveryPublicationStore(context).read().size();
        } catch (Exception corrupt) {
            publicationRegistryError = true;
            intervention++;
            appendDetail(details, "Registre SAF illisible : aucun état n'a été supprimé.");
        }

        if (retryableFailure && GpxRecoveryRetryPolicy.shouldRetry(true, getRunAttemptCount())) {
            return Result.retry();
        }
        if (retryableFailure) {
            intervention++;
            appendDetail(details, "Les tentatives automatiques sont terminées ; utilisez RELANCER après vérification du stockage.");
        }
        if (publicationEntries > 0) {
            appendDetail(details, publicationEntries + " transaction(s) SAF restent inscrites.");
            if (remaining == 0) {
                // Aucune entrée de récupération ne permet de rejouer cette
                // transaction : ne pas annoncer un retry automatique qui ne
                // pourrait pas la reconstruire sans son snapshot source.
                intervention++;
                appendDetail(details, "Transaction SAF orpheline : intervention nécessaire ; état conservé.");
            }
        }
        boolean retentionRegistryError = false;
        try {
            RecoveryRetentionRegistry retention = new RecoveryRetentionStore(context).read();
            RecoveryRetentionPolicy.Summary retentionSummary =
                    RecoveryRetentionPolicy.summarize(retention, System.currentTimeMillis());
            if (retentionSummary.trackedCopies > 0) {
                appendDetail(details, "Conservation des copies publiées : "
                        + retentionSummary.trackedCopies + " copie(s) suivie(s), "
                        + retentionSummary.stillRetained + " encore dans les 30 jours minimum, "
                        + retentionSummary.readyForReview + " arrivée(s) à échéance pour examen manuel, "
                        + formatBytes(retentionSummary.trackedBytes) + " suivis.");
            }
            if (retentionSummary.softQuotaExceeded) {
                appendDetail(details, "Seuil indicatif de stockage dépassé (512 Mio) ; aucune suppression automatique n'est déclenchée.");
            }
            if (retentionSummary.readyForReview > 0) {
                appendDetail(details, "Des copies ont dépassé la durée minimale, mais aucune purge n'est activée : une confirmation explicite sera nécessaire dans une étape ultérieure.");
            }
        } catch (Exception corrupt) {
            retentionRegistryError = true;
            appendDetail(details, "Registre de conservation illisible ; les copies locales restent conservées et aucune action destructive n'est entreprise.");
        }

        if (remaining == 0 && publicationEntries == 0 && details.length() == 0) {
            details.append("Aucune récupération GPX en attente.");
        }

        String summary = "Récupérations : " + recovered + " locale(s), " + published
                + " publiée(s), " + localOnly + " copie(s) privée(s), "
                + waitingPermission + " permission(s) à rétablir, "
                + intervention + " intervention(s), " + remaining
                + " journal(aux) restant(s), " + publicationEntries + " transaction(s) SAF.\n"
                + details;
        return Result.success(output(summary, remaining, published, localOnly, waitingPermission,
                intervention, registryError || publicationRegistryError || retentionRegistryError,
                TrackingState.get().isUserWantsTracking(), legacyNoticeShown));
    }

    private Result transientOrFinish(String message, int pending, int published,
                                     int waitingPermission, int intervention,
                                     boolean registryError, boolean trackingActive) {
        if (GpxRecoveryRetryPolicy.shouldRetry(true, getRunAttemptCount())) return Result.retry();
        return Result.success(output(message, pending, published, 0, waitingPermission,
                intervention + 1, registryError, trackingActive, false));
    }

    private static Data output(String summary, int pending, int published, int localOnly,
                               int waitingPermission, int intervention,
                               boolean registryError, boolean trackingActive,
                               boolean hasNotice) {
        return new Data.Builder().putString(KEY_SUMMARY, summary == null ? "" : summary)
                .putInt(KEY_PENDING, Math.max(0, pending))
                .putInt(KEY_PUBLISHED, Math.max(0, published))
                .putInt(KEY_LOCAL_ONLY, Math.max(0, localOnly))
                .putInt(KEY_WAITING_PERMISSION, Math.max(0, waitingPermission))
                .putInt(KEY_INTERVENTION, Math.max(0, intervention))
                .putBoolean(KEY_REGISTRY_ERROR, registryError)
                .putBoolean(KEY_TRACKING_ACTIVE, trackingActive)
                .putBoolean(KEY_NOTICE, hasNotice).build();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " o";
        if (bytes < 1024L * 1024L) return (bytes / 1024L) + " Kio";
        return (bytes / (1024L * 1024L)) + " Mio";
    }

    private static void appendDetail(StringBuilder target, String message) {
        if (message == null || message.trim().isEmpty() || target.length() >= 2_500) return;
        if (target.length() > 0) target.append('\n');
        target.append(message.length() > 500 ? message.substring(0, 500) : message);
    }
}
