package com.example.gpstest;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Modèle de présentation pur du registre de conservation.
 *
 * Il ne lit jamais le fournisseur SAF et n'effectue aucune suppression. Il
 * vérifie uniquement l'existence, l'emplacement canonique et la longueur de
 * la copie locale suivie. Le SHA-256 affiché reste celui vérifié lors de la
 * publication ; son recalcul et une nouvelle lecture SAF sont volontairement
 * réservés à une future action de vérification explicite.
 */
public final class RecoveryRetentionPresentation {
    public enum LocalCopyState {
        PRESENT_LENGTH_MATCHES,
        MISSING,
        NOT_A_REGULAR_FILE,
        NOT_READABLE,
        LENGTH_MISMATCH,
        OUTSIDE_RECOVERY_DIRECTORY,
        DIRECTORY_UNAVAILABLE,
        CHECK_FAILED
    }

    public static final class Row {
        public final RecoveryRetentionRegistry.Entry entry;
        public final RecoveryRetentionPolicy.Decision retentionDecision;
        public final LocalCopyState localCopyState;
        public final long reviewAfterMs;
        public final long daysUntilReview;
        public final long ageDays;

        private Row(RecoveryRetentionRegistry.Entry entry,
                    RecoveryRetentionPolicy.Decision decision,
                    LocalCopyState localCopyState, long reviewAfterMs,
                    long daysUntilReview, long ageDays) {
            this.entry = entry;
            this.retentionDecision = decision;
            this.localCopyState = localCopyState;
            this.reviewAfterMs = reviewAfterMs;
            this.daysUntilReview = daysUntilReview;
            this.ageDays = ageDays;
        }

        public boolean isReadyForExplicitReview() {
            return retentionDecision == RecoveryRetentionPolicy.Decision.READY_FOR_EXPLICIT_REVIEW;
        }

        public boolean isLocalCopyConsistentByLength() {
            return localCopyState == LocalCopyState.PRESENT_LENGTH_MATCHES;
        }
    }

    private RecoveryRetentionPresentation() { }

    /**
     * Construit les lignes les plus récentes en premier. Une entrée illisible
     * n'est jamais transformée en liste vide : le paramètre null donne une
     * liste vide uniquement pour les appelants ayant déjà validé le registre.
     */
    public static List<Row> buildRows(RecoveryRetentionRegistry registry,
                                      File recoveryDirectory, long nowMs) {
        if (registry == null || nowMs <= 0L) return Collections.emptyList();
        ArrayList<Row> rows = new ArrayList<>();
        for (RecoveryRetentionRegistry.Entry entry : registry.entries()) {
            if (entry == null) continue;
            RecoveryRetentionPolicy.Decision decision =
                    RecoveryRetentionPolicy.evaluate(entry, nowMs);
            long reviewAfter = reviewAfter(entry.publishedAtMs);
            long remaining = daysUntilReview(reviewAfter, nowMs, decision);
            long age = ageDays(entry.publishedAtMs, nowMs);
            rows.add(new Row(entry, decision,
                    inspectLocalCopy(recoveryDirectory, entry), reviewAfter, remaining, age));
        }
        Collections.sort(rows, new Comparator<Row>() {
            @Override public int compare(Row left, Row right) {
                int byDate = Long.compare(right.entry.publishedAtMs, left.entry.publishedAtMs);
                if (byDate != 0) return byDate;
                return left.entry.publicationId.compareTo(right.entry.publicationId);
            }
        });
        return Collections.unmodifiableList(rows);
    }

    private static LocalCopyState inspectLocalCopy(File directory,
                                                    RecoveryRetentionRegistry.Entry entry) {
        if (directory == null || !directory.isDirectory())
            return LocalCopyState.DIRECTORY_UNAVAILABLE;
        try {
            File root = directory.getCanonicalFile();
            File candidate = new File(directory, entry.recoveredName);
            File canonical = candidate.getCanonicalFile();
            File parent = canonical.getParentFile();
            if (parent == null || !root.equals(parent))
                return LocalCopyState.OUTSIDE_RECOVERY_DIRECTORY;
            if (!candidate.exists()) return LocalCopyState.MISSING;
            if (!candidate.isFile()) return LocalCopyState.NOT_A_REGULAR_FILE;
            if (!candidate.canRead()) return LocalCopyState.NOT_READABLE;
            if (candidate.length() != entry.recoveredLengthBytes)
                return LocalCopyState.LENGTH_MISMATCH;
            return LocalCopyState.PRESENT_LENGTH_MATCHES;
        } catch (Exception failure) {
            return LocalCopyState.CHECK_FAILED;
        }
    }

    private static long reviewAfter(long publishedAtMs) {
        long retention = RecoveryRetentionPolicy.MIN_RETENTION_MS;
        if (publishedAtMs <= 0L || publishedAtMs > Long.MAX_VALUE - retention) return -1L;
        return publishedAtMs + retention;
    }

    private static long daysUntilReview(long reviewAfterMs, long nowMs,
                                        RecoveryRetentionPolicy.Decision decision) {
        if (reviewAfterMs <= 0L || nowMs <= 0L
                || decision == RecoveryRetentionPolicy.Decision.INVALID_ENTRY
                || decision == RecoveryRetentionPolicy.Decision.CLOCK_MOVED_BACKWARDS) return -1L;
        if (nowMs >= reviewAfterMs) return 0L;
        long remaining = reviewAfterMs - nowMs;
        long dayMs = 24L * 60L * 60L * 1000L;
        // Division ceiling sans addition susceptible de déborder long.
        return remaining / dayMs + (remaining % dayMs == 0L ? 0L : 1L);
    }

    private static long ageDays(long publishedAtMs, long nowMs) {
        if (publishedAtMs <= 0L || nowMs < publishedAtMs) return -1L;
        return (nowMs - publishedAtMs) / (24L * 60L * 60L * 1000L);
    }
}
