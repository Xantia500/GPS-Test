package com.example.gpstest;

/** Tests de durée minimale, horloge, quota indicatif et absence de purge automatique. */
public final class RecoveryRetentionPolicyTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        long publishedAt = 1_700_000_000_000L;
        RecoveryRetentionRegistry.Entry entry = entry("one", 100L, publishedAt);
        check(RecoveryRetentionPolicy.MIN_RETENTION_DAYS == 30,
                "durée minimale fixée à 30 jours");
        check(RecoveryRetentionPolicy.evaluate(entry, publishedAt)
                        == RecoveryRetentionPolicy.Decision.KEEP_MINIMUM_RETENTION,
                "copie conservée à la date de publication");
        check(RecoveryRetentionPolicy.evaluate(entry,
                        publishedAt + RecoveryRetentionPolicy.MIN_RETENTION_MS - 1L)
                        == RecoveryRetentionPolicy.Decision.KEEP_MINIMUM_RETENTION,
                "copie conservée jusqu'à la dernière milliseconde des 30 jours");
        check(RecoveryRetentionPolicy.evaluate(entry,
                        publishedAt + RecoveryRetentionPolicy.MIN_RETENTION_MS)
                        == RecoveryRetentionPolicy.Decision.READY_FOR_EXPLICIT_REVIEW,
                "copie proposée à examen manuel au terme exact des 30 jours");
        check(RecoveryRetentionPolicy.evaluate(entry, publishedAt - 1L)
                        == RecoveryRetentionPolicy.Decision.CLOCK_MOVED_BACKWARDS,
                "horloge reculée : conservation prolongée");
        check(RecoveryRetentionPolicy.evaluate(entry, 0L)
                        == RecoveryRetentionPolicy.Decision.INVALID_ENTRY,
                "date courante invalide refusée");
        check(RecoveryRetentionPolicy.evaluate(entry("overflowTimestamp", 7L, Long.MAX_VALUE - 5_000L), Long.MAX_VALUE)
                        == RecoveryRetentionPolicy.Decision.INVALID_ENTRY,
                "overflow d'addition de date refusé");
        check(!RecoveryRetentionPolicy.automaticDeletionEnabled(),
                "aucune suppression automatique dans ce lot");

        RecoveryRetentionRegistry registry = RecoveryRetentionRegistry.empty();
        RecoveryRetentionRegistry.Entry young = entry("young", 200L, publishedAt);
        RecoveryRetentionRegistry.Entry old = entry("old", 300L,
                publishedAt - RecoveryRetentionPolicy.MIN_RETENTION_MS - 1L);
        check(registry.addOrVerify(young) && registry.addOrVerify(old), "fixtures registre prêtes");
        RecoveryRetentionPolicy.Summary summary = RecoveryRetentionPolicy.summarize(
                registry, publishedAt + RecoveryRetentionPolicy.MIN_RETENTION_MS / 2L);
        check(summary.trackedCopies == 2, "deux copies suivies");
        check(summary.stillRetained == 1, "une copie encore sous la durée minimale");
        check(summary.readyForReview == 1, "une copie à examiner manuellement");
        check(summary.trackedBytes == young.recoveredLengthBytes + old.recoveredLengthBytes,
                "somme des tailles sans perte");
        check(!summary.softQuotaExceeded, "quota indicatif non dépassé");

        check(RecoveryRetentionPolicy.SOFT_QUOTA_BYTES == 512L * 1024L * 1024L,
                "seuil indicatif fixé à 512 Mio");
        RecoveryRetentionRegistry large = RecoveryRetentionRegistry.empty();
        check(large.addOrVerify(entry("large-a", 1L, publishedAt, 300L * 1024L * 1024L)),
                "premier fichier volumineux enregistré");
        check(large.addOrVerify(entry("large-b", 2L, publishedAt, 212L * 1024L * 1024L)),
                "second fichier volumineux enregistré");
        RecoveryRetentionPolicy.Summary atQuota = RecoveryRetentionPolicy.summarize(large, publishedAt);
        check(!atQuota.softQuotaExceeded, "512 Mio exactement ne dépasse pas le seuil");
        check(large.addOrVerify(entry("large-c", 3L, publishedAt, 1L)), "dépassement ajouté");
        check(RecoveryRetentionPolicy.summarize(large, publishedAt).softQuotaExceeded,
                "le dépassement déclenche seulement un avertissement indicatif");

        RecoveryRetentionRegistry huge = RecoveryRetentionRegistry.empty();
        check(huge.addOrVerify(entry("huge", 1L, publishedAt, Long.MAX_VALUE)),
                "entrée taille extrême admise pour test de saturation");
        check(RecoveryRetentionPolicy.summarize(huge, publishedAt).trackedBytes == Long.MAX_VALUE,
                "somme des octets saturée sans overflow négatif");

        System.out.println("RecoveryRetentionPolicyTest : " + passed + " réussis, " + failed + " échoués");
        if (failed > 0) System.exit(1);
    }

    private static RecoveryRetentionRegistry.Entry entry(String suffix, long generation, long publishedAt) {
        return entry(suffix, generation, publishedAt, 1024L);
    }
    private static RecoveryRetentionRegistry.Entry entry(String suffix, long generation,
                                                           long publishedAt, long length) {
        return RecoveryRetentionRegistry.Entry.published(idFor(suffix), "session-" + suffix,
                generation, "source-" + suffix + ".gpx", "source-" + suffix + "_RECUPEREE.gpx",
                length, repeat('b', 64), "content://provider/tree",
                "content://provider/document/" + suffix, "published-" + suffix + ".gpx", publishedAt);
    }
    private static String idFor(String value) {
        String hex = Integer.toUnsignedString(value.hashCode(), 16);
        StringBuilder out = new StringBuilder(64);
        for (int i = hex.length(); i < 8; i++) out.append('0');
        out.append(hex);
        while (out.length() < 64) out.append('e');
        return out.toString();
    }
    private static String repeat(char c, int n) {
        StringBuilder b = new StringBuilder(n);
        for (int i = 0; i < n; i++) b.append(c);
        return b.toString();
    }
    private static void check(boolean condition, String label) {
        if (condition) passed++; else { failed++; System.out.println("ECHEC : " + label); }
    }
}
