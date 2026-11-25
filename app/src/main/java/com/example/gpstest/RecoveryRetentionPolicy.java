package com.example.gpstest;

import java.util.List;

/** Politique pure de conservation : évalue, mais ne supprime jamais de fichier. */
public final class RecoveryRetentionPolicy {
    public static final int MIN_RETENTION_DAYS = 30;
    public static final long MIN_RETENTION_MS = MIN_RETENTION_DAYS * 24L * 60L * 60L * 1000L;
    /** Seuil indicatif uniquement : il ne déclenche aucune suppression. */
    public static final long SOFT_QUOTA_BYTES = 512L * 1024L * 1024L;
    public static final int MAX_TRACKED_ENTRIES = RecoveryRetentionRegistry.MAX_ENTRIES;

    public enum Decision {
        KEEP_MINIMUM_RETENTION,
        READY_FOR_EXPLICIT_REVIEW,
        CLOCK_MOVED_BACKWARDS,
        INVALID_ENTRY
    }

    public static final class Summary {
        public final int trackedCopies;
        public final int stillRetained;
        public final int readyForReview;
        public final long trackedBytes;
        public final boolean softQuotaExceeded;

        private Summary(int trackedCopies, int stillRetained, int readyForReview,
                        long trackedBytes, boolean softQuotaExceeded) {
            this.trackedCopies = trackedCopies;
            this.stillRetained = stillRetained;
            this.readyForReview = readyForReview;
            this.trackedBytes = trackedBytes;
            this.softQuotaExceeded = softQuotaExceeded;
        }
    }

    private RecoveryRetentionPolicy() { }

    public static Decision evaluate(RecoveryRetentionRegistry.Entry entry, long nowMs) {
        if (entry == null || nowMs <= 0L || entry.publishedAtMs <= 0L
                || entry.recoveredLengthBytes <= 0L || entry.sourceName == null
                || entry.recoveredName == null || entry.sourceName.equals(entry.recoveredName)
                || entry.sha256 == null || entry.sha256.length() != 64
                || entry.treeUri == null || !entry.treeUri.startsWith("content://")
                || entry.documentUri == null || !entry.documentUri.startsWith("content://")) {
            return Decision.INVALID_ENTRY;
        }
        if (nowMs < entry.publishedAtMs) return Decision.CLOCK_MOVED_BACKWARDS;
        // Évite un overflow lors de l'addition d'un timestamp corrompu/proche de Long.MAX_VALUE.
        if (entry.publishedAtMs > Long.MAX_VALUE - MIN_RETENTION_MS)
            return Decision.INVALID_ENTRY;
        return nowMs >= entry.publishedAtMs + MIN_RETENTION_MS
                ? Decision.READY_FOR_EXPLICIT_REVIEW : Decision.KEEP_MINIMUM_RETENTION;
    }

    public static Summary summarize(RecoveryRetentionRegistry registry, long nowMs) {
        if (registry == null) return new Summary(0, 0, 0, 0L, false);
        int retained = 0;
        int ready = 0;
        long bytes = 0L;
        List<RecoveryRetentionRegistry.Entry> entries = registry.entries();
        for (RecoveryRetentionRegistry.Entry entry : entries) {
            Decision decision = evaluate(entry, nowMs);
            if (decision == Decision.KEEP_MINIMUM_RETENTION
                    || decision == Decision.CLOCK_MOVED_BACKWARDS
                    || decision == Decision.INVALID_ENTRY) retained++;
            else if (decision == Decision.READY_FOR_EXPLICIT_REVIEW) ready++;
            long length = entry == null ? 0L : Math.max(0L, entry.recoveredLengthBytes);
            bytes = saturatedAdd(bytes, length);
        }
        return new Summary(entries.size(), retained, ready, bytes, bytes > SOFT_QUOTA_BYTES);
    }

    private static long saturatedAdd(long current, long value) {
        if (value <= 0L) return current;
        return current > Long.MAX_VALUE - value ? Long.MAX_VALUE : current + value;
    }

    /** Cette version n'autorise volontairement aucune purge automatique. */
    public static boolean automaticDeletionEnabled() { return false; }
}
