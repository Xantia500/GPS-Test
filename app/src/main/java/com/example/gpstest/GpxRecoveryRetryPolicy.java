package com.example.gpstest;

/** Règles pures de reprise automatique des récupérations GPX et publications SAF. */
public final class GpxRecoveryRetryPolicy {
    public static final int MAX_AUTOMATIC_ATTEMPTS = 5;

    public enum Decision { COMPLETE, WAIT_FOR_USER, RETRY_AUTOMATICALLY, NEEDS_INTERVENTION }

    private GpxRecoveryRetryPolicy() { }

    /**
     * Les noms d'états sont transmis comme chaînes afin de garder ce modèle
     * indépendant d'Android/WorkManager et de le tester avec le JDK seul.
     */
    public static Decision classify(String workflowStatus, String publisherStatus,
                                    String recoveryStatus) {
        if ("PENDING_PERMISSION".equals(workflowStatus)
                || "NO_PERMISSION".equals(publisherStatus)) return Decision.WAIT_FOR_USER;
        if ("PUBLICATION_CONFLICT".equals(workflowStatus)
                || "AMBIGUOUS_DOCUMENTS".equals(publisherStatus)
                || "SOURCE_CHANGED".equals(publisherStatus)
                || "INVALID_SOURCE".equals(publisherStatus)
                || "REGISTRY_ERROR".equals(publisherStatus)) return Decision.NEEDS_INTERVENTION;
        if ("JOURNAL_NOT_ACKNOWLEDGED".equals(workflowStatus)
                || "PENDING_PUBLICATION".equals(workflowStatus)) {
            return Decision.RETRY_AUTOMATICALLY;
        }
        if ("RECOVERY_FAILED".equals(workflowStatus)) {
            if ("IO_ERROR".equals(recoveryStatus)) return Decision.RETRY_AUTOMATICALLY;
            return Decision.NEEDS_INTERVENTION;
        }
        if ("PUBLISHED".equals(workflowStatus) || "LOCAL_ONLY".equals(workflowStatus))
            return Decision.COMPLETE;
        return Decision.NEEDS_INTERVENTION;
    }

    /** runAttemptCount est indexé à zéro par WorkManager. */
    public static boolean shouldRetry(boolean transientFailure, int runAttemptCount) {
        return transientFailure && runAttemptCount >= 0
                && runAttemptCount + 1 < MAX_AUTOMATIC_ATTEMPTS;
    }
}
