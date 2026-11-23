package com.example.gpstest;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

public final class OfflineBoundaryRepositoryTest {
    private static int ok, ko;
    private static void check(String label, boolean value) {
        if (value) { ok++; System.out.println("  OK   " + label); }
        else { ko++; System.out.println("  FAIL " + label); }
    }
    private static byte[] poly(String name, double offset) {
        return (name + "\n1\n" + offset + " 48\n" + (offset + 1) + " 48\n"
                + (offset + 1) + " 49\n" + offset + " 49\nEND\nEND\n")
                .getBytes(StandardCharsets.UTF_8);
    }
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("boundary-cache-test-").toFile();
        OfflineBoundaryRepository repo = new OfflineBoundaryRepository(root);
        AtomicInteger fetches = new AtomicInteger();
        OfflineBoundaryRepository.Fetcher fetcher = (path, cancel) -> {
            fetches.incrementAndGet();
            return poly("sample", 2.0);
        };
        OfflineBoundaryRepository.BoundaryData first = repo.loadOrFetch(
                "europe/france/sample.poly", "2026-11-20 10:00", fetcher, null);
        check("premier chargement télécharge et valide le polygone", first.downloaded
                && !first.stale && first.polygon.contains(48.5, 2.5) && fetches.get() == 1);
        check("taille de frontière connue pour borner le cumul mémoire", first.sizeBytes > 0);
        OfflineBoundaryRepository.BoundaryData cached = repo.loadOrFetch(
                "europe/france/sample.poly", "2026-11-20 10:00", (p, c) -> {
                    throw new IOException("ne doit pas appeler le réseau");
                }, null);
        check("version identique réutilise le cache sans réseau", !cached.downloaded
                && !cached.stale && fetches.get() == 1);
        check("taille du cache correspond au fichier stocké", cached.sizeBytes == first.sizeBytes);
        java.util.List<OfflineBoundaryRepository.BoundaryData> offlineDirectory =
                repo.loadCachedDirectory("europe/france/");
        check("index des frontières déjà consultées lisible sans réseau",
                offlineDirectory.size() == 1
                        && offlineDirectory.get(0).relativePath.equals("europe/france/sample.poly")
                        && offlineDirectory.get(0).stale);
        check("un dossier voisin ne reçoit pas le cache d'une autre région",
                repo.loadCachedDirectory("europe/belgium/").isEmpty());
        OfflineBoundaryRepository.BoundaryData stale = repo.loadOrFetch(
                "europe/france/sample.poly", "2026-11-21 10:00", (p, c) -> {
                    throw new IOException("hors ligne");
                }, null);
        check("échec réseau conserve une frontière en cache et signale son ancienneté",
                stale.stale && !stale.downloaded && "2026-11-20 10:00".equals(stale.sourceVersion));
        OfflineBoundaryRepository.BoundaryData updated = repo.loadOrFetch(
                "europe/france/sample.poly", "2026-11-21 10:00",
                (p, c) -> poly("sample-v2", 4.0), null);
        check("nouvelle version distante remplace le cache après validation",
                updated.downloaded && updated.polygon.contains(48.5, 4.5));
        OfflineBoundaryRepository.BoundaryData retained = repo.loadOrFetch(
                "europe/france/sample.poly", "2026-11-21 10:00", null, null);
        check("nouveau cache relu hors ligne", !retained.stale && retained.polygon.contains(48.5, 4.5));
        boolean invalidPath = false;
        try { repo.loadOrFetch("../outside.poly", "v", fetcher, null); }
        catch (IOException expected) { invalidPath = true; }
        check("chemin traversal de frontière refusé", invalidPath);
        boolean cancel = false;
        try { repo.loadOrFetch("europe/france/cancel.poly", "v", fetcher, () -> true); }
        catch (IOException expected) { cancel = true; }
        check("annulation avant téléchargement respectée", cancel);
        OfflineBoundaryRepository.BoundaryData other = repo.loadOrFetch(
                "europe/france/other.poly", "v1", (path, token) -> poly("other", 7.0), null);
        File[] cacheFiles = root.listFiles((d, n) -> n.endsWith(".poly"));
        check("cache ne conserve que les frontières validées", cacheFiles != null && cacheFiles.length == 2);
        if (cacheFiles == null || cacheFiles.length != 2) {
            throw new AssertionError("État de cache inattendu avant purge");
        }
        java.util.Arrays.sort(cacheFiles, java.util.Comparator.comparing(File::getName));
        File older = cacheFiles[0];
        File newer = cacheFiles[1];
        check("le système de fichiers accepte les dates de test déterministes",
                older.setLastModified(1_000L) && newer.setLastModified(2_000L));
        long budget = Math.max(older.length(), newer.length());
        int pruned = repo.pruneToMaxBytes(budget);
        File[] afterPrune = root.listFiles((d, n) -> n.endsWith(".poly"));
        check("cache global retire exactement le fichier le plus ancien au-delà du budget",
                pruned == 1 && afterPrune != null && afterPrune.length == 1
                        && afterPrune[0].getName().equals(newer.getName()));
        File remaining = afterPrune == null || afterPrune.length == 0 ? null : afterPrune[0];
        File remainingMetadata = remaining == null ? null : new File(root,
                remaining.getName().substring(0, remaining.getName().length() - 5) + ".properties");
        long cutoff = 3_000L;
        int removed = repo.removeStaleCacheFiles(cutoff);
        check("nettoyage ancien cache retire polygone et métadonnées", removed == 1
                && root.listFiles((d, n) -> n.endsWith(".poly")).length == 0
                && remainingMetadata != null && !remainingMetadata.exists());
        System.out.println("RESULTAT : " + ok + " réussis, " + ko + " échoués");
        if (ko != 0) throw new AssertionError(ko + " test(s) échoué(s)");
    }
}
