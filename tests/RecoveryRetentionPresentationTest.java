package com.example.gpstest;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/** Tests du modèle d'affichage : ordre, échéances et état du fichier local. */
public final class RecoveryRetentionPresentationTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        long published = 1_700_000_000_000L;
        long now = published + 10L * 24L * 60L * 60L * 1000L;
        File directory = new File(System.getProperty("java.io.tmpdir"),
                "gpstest-retention-presentation-" + System.nanoTime());
        check(directory.mkdirs() && directory.isDirectory(), "dossier de test créé");

        RecoveryRetentionRegistry registry = RecoveryRetentionRegistry.empty();
        RecoveryRetentionRegistry.Entry older = entry("older", published, 4L);
        RecoveryRetentionRegistry.Entry newer = entry("newer", published + 3L, 5L);
        RecoveryRetentionRegistry.Entry sameTimeA = entry("same-a", published + 2L, 6L);
        RecoveryRetentionRegistry.Entry sameTimeB = entry("same-b", published + 2L, 7L);
        check(registry.addOrVerify(older) && registry.addOrVerify(newer)
                        && registry.addOrVerify(sameTimeA) && registry.addOrVerify(sameTimeB),
                "quatre entrées de présentation enregistrées");

        write(new File(directory, older.recoveredName), new byte[] {1, 2, 3, 4});
        write(new File(directory, newer.recoveredName), new byte[] {1, 2, 3, 4, 5});
        write(new File(directory, sameTimeA.recoveredName), new byte[] {1}); // mauvaise longueur
        // sameTimeB reste absent.

        List<RecoveryRetentionPresentation.Row> rows =
                RecoveryRetentionPresentation.buildRows(registry, directory, now);
        check(rows.size() == 4, "une ligne par entrée du registre");
        check(rows.get(0).entry.publicationId.equals(newer.publicationId),
                "publication la plus récente en premier");
        check(rows.get(1).entry.publicationId.compareTo(rows.get(2).entry.publicationId) < 0,
                "égalité de date départagée par publicationId de façon déterministe");
        check(rows.get(3).entry.publicationId.equals(older.publicationId),
                "publication la plus ancienne en dernier");

        RecoveryRetentionPresentation.Row olderRow = find(rows, older.publicationId);
        check(olderRow.localCopyState == RecoveryRetentionPresentation.LocalCopyState.PRESENT_LENGTH_MATCHES,
                "copie locale existante et de longueur conforme détectée");
        check(olderRow.isLocalCopyConsistentByLength(),
                "le modèle expose explicitement la conformité de longueur");
        RecoveryRetentionPresentation.Row newerRow = find(rows, newer.publicationId);
        check(newerRow.localCopyState == RecoveryRetentionPresentation.LocalCopyState.PRESENT_LENGTH_MATCHES,
                "deuxième copie locale conforme détectée");
        RecoveryRetentionPresentation.Row mismatch = find(rows, sameTimeA.publicationId);
        check(mismatch.localCopyState == RecoveryRetentionPresentation.LocalCopyState.LENGTH_MISMATCH,
                "une copie locale de longueur divergente est signalée");
        RecoveryRetentionPresentation.Row missing = find(rows, sameTimeB.publicationId);
        check(missing.localCopyState == RecoveryRetentionPresentation.LocalCopyState.MISSING,
                "une copie locale absente est signalée sans toucher au registre");

        check(olderRow.retentionDecision == RecoveryRetentionPolicy.Decision.KEEP_MINIMUM_RETENTION,
                "une copie jeune reste dans sa durée minimale");
        check(olderRow.reviewAfterMs == published + RecoveryRetentionPolicy.MIN_RETENTION_MS,
                "date de revue calculée à 30 jours exactement");
        check(olderRow.daysUntilReview == 20L,
                "jours restants calculés depuis la date de publication");
        check(olderRow.ageDays == 10L, "ancienneté affichée en jours révolus");
        check(!olderRow.isReadyForExplicitReview(),
                "l'écran ne marque pas comme révisable une copie de moins de 30 jours");

        long dueNow = published + RecoveryRetentionPolicy.MIN_RETENTION_MS;
        List<RecoveryRetentionPresentation.Row> dueRows =
                RecoveryRetentionPresentation.buildRows(single(older), directory, dueNow);
        check(dueRows.get(0).retentionDecision
                        == RecoveryRetentionPolicy.Decision.READY_FOR_EXPLICIT_REVIEW,
                "une copie atteint le statut examen manuel au seuil exact");
        check(dueRows.get(0).daysUntilReview == 0L && dueRows.get(0).isReadyForExplicitReview(),
                "échéance atteinte : zéro jour restant et examen manuel possible");

        List<RecoveryRetentionPresentation.Row> backwards =
                RecoveryRetentionPresentation.buildRows(single(older), directory, published - 1L);
        check(backwards.get(0).retentionDecision
                        == RecoveryRetentionPolicy.Decision.CLOCK_MOVED_BACKWARDS,
                "horloge antérieure : statut conservateur dédié");
        check(backwards.get(0).daysUntilReview == -1L && backwards.get(0).ageDays == -1L,
                "aucune ancienneté ou échéance trompeuse quand l'horloge recule");

        List<RecoveryRetentionPresentation.Row> noDirectory =
                RecoveryRetentionPresentation.buildRows(single(older), null, now);
        check(noDirectory.get(0).localCopyState
                        == RecoveryRetentionPresentation.LocalCopyState.DIRECTORY_UNAVAILABLE,
                "dossier de récupération indisponible explicitement signalé");
        check(RecoveryRetentionPresentation.buildRows(null, directory, now).isEmpty(),
                "registre null ne produit aucune fausse ligne");
        check(RecoveryRetentionPresentation.buildRows(registry, directory, 0L).isEmpty(),
                "horloge invalide refusée par le modèle");

        boolean immutable = false;
        try { rows.clear(); } catch (UnsupportedOperationException expected) { immutable = true; }
        check(immutable, "liste de présentation non modifiable");

        deleteTree(directory);
        check(!directory.exists(), "dossier temporaire de test nettoyé");

        System.out.println("RecoveryRetentionPresentationTest : " + passed
                + " réussis, " + failed + " échoués");
        if (failed > 0) System.exit(1);
    }

    private static RecoveryRetentionRegistry.Entry entry(String suffix, long publishedAt, long length) {
        return RecoveryRetentionRegistry.Entry.published(idFor(suffix), "session-" + suffix,
                1L, "source-" + suffix + ".gpx", "source-" + suffix + "_RECUPEREE.gpx",
                length, repeat('c', 64), "content://provider/tree",
                "content://provider/document/" + suffix, "published-" + suffix + ".gpx",
                publishedAt);
    }

    private static RecoveryRetentionRegistry registry(RecoveryRetentionRegistry.Entry entry) {
        RecoveryRetentionRegistry registry = RecoveryRetentionRegistry.empty();
        registry.addOrVerify(entry);
        return registry;
    }
    private static RecoveryRetentionRegistry single(RecoveryRetentionRegistry.Entry entry) {
        return registry(entry);
    }
    private static RecoveryRetentionPresentation.Row find(
            List<RecoveryRetentionPresentation.Row> rows, String publicationId) {
        for (RecoveryRetentionPresentation.Row row : rows)
            if (row.entry.publicationId.equals(publicationId)) return row;
        throw new AssertionError("Ligne absente : " + publicationId);
    }
    private static void write(File file, byte[] bytes) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); out.flush(); }
    }
    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
    private static String idFor(String value) {
        String hex = Integer.toUnsignedString(value.hashCode(), 16);
        StringBuilder out = new StringBuilder(64);
        for (int i = hex.length(); i < 8; i++) out.append('0');
        out.append(hex);
        while (out.length() < 64) out.append('d');
        return out.toString();
    }
    private static String repeat(char value, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
    private static void check(boolean condition, String label) {
        if (condition) passed++;
        else { failed++; System.out.println("ECHEC : " + label); }
    }
}
