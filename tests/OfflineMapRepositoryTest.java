package com.example.gpstest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class OfflineMapRepositoryTest {
    private static int ok;
    private static int ko;

    private static void check(String label, boolean condition) {
        if (condition) {
            ok++;
            System.out.println("  OK   " + label);
        } else {
            ko++;
            System.out.println("  FAIL " + label);
        }
    }

    private static byte[] mapsforgeHeader(long createdAt) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write("mapsforge binary OSM".getBytes(StandardCharsets.US_ASCII));
        out.writeInt(42);              // Taille de l'en-tête, hors signature de 20 octets.
        out.writeInt(5);               // Format Mapsforge v5.
        out.writeLong(62);             // Taille totale de ce fixture (en-tête uniquement).
        out.writeLong(createdAt);
        out.writeInt(0);
        out.writeInt(0);
        out.writeInt(90_000_000);
        out.writeInt(180_000_000);
        out.writeShort(256);
        out.flush();
        return bytes.toByteArray();
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder out = new StringBuilder();
        for (byte b : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }

    private static boolean installFails(OfflineMapRepository repo, String id, byte[] bytes,
                                        long expectedSize, String hash,
                                        OfflineMapRepository.CancellationToken cancellation) {
        try {
            repo.install(id, "Test", new ByteArrayInputStream(bytes), expectedSize, hash,
                    "Test fixture", cancellation, null);
            return false;
        } catch (IOException expected) {
            return true;
        }
    }

    public static void main(String[] args) throws Exception {
        File temp = Files.createTempDirectory("gpstest-offline-map-test-").toFile();
        OfflineMapRepository repo = new OfflineMapRepository(temp);
        byte[] valid = mapsforgeHeader(1_800_000_000_000L);
        String validHash = sha256(valid);
        AtomicInteger progress = new AtomicInteger();

        OfflineMapRepository.InstalledMap installed = repo.install("ile-de-france", "Île-de-France",
                new ByteArrayInputStream(valid), valid.length, validHash, "Mapsforge mirror", null,
                (read, expected) -> progress.incrementAndGet());
        check("installation valide d'un flux Mapsforge v5", installed != null
                && "ile-de-france".equals(installed.regionId));
        check("le fichier installé utilise le SHA-256 complet dans son nom",
                installed.file.getName().equals("ile-de-france-" + validHash + ".map"));
        check("taille, empreinte et version sont enregistrées", installed.sizeBytes == valid.length
                && installed.sha256.equals(validHash) && installed.formatVersion == 5);
        check("emprise géographique et tileSize du header sont exposés au catalogue",
                installed.minLatitude == 0.0 && installed.minLongitude == 0.0
                        && installed.maxLatitude == 90.0 && installed.maxLongitude == 180.0
                        && installed.tileSize == 256);
        check("progression de copie signalée", progress.get() > 0);
        check("MapDataSource retourne le même fichier", repo.getMapFile("ile-de-france").equals(installed.file));
        check("catalogue local liste une région installée", repo.listInstalledMaps().size() == 1);

        File stagingDirectory = new File(temp, "staging");
        stagingDirectory.mkdirs();
        File stagedFile = new File(stagingDirectory, "download.map");
        Files.write(stagedFile.toPath(), valid);
        OfflineMapRepository.InstalledMap moved = repo.installFile("test-stage", "Carte stage",
                stagedFile, "Mapsforge mirror", "2026-11-23 09:00", null, null, "offline-map-install-123");
        check("installFile déplace le staging vers le stockage permanent", moved.file.isFile() && !stagedFile.exists());
        check("installFile conserve la version distante du catalogue",
                moved.sourceVersion.equals("2026-11-23 09:00"));
        check("installFile calcule le SHA-256 local", moved.sha256.equals(validHash));
        check("token de finalisation WorkManager persisté pour reprise idempotente",
                moved.installId.equals("offline-map-install-123"));

        File invalidStage = new File(stagingDirectory, "invalid.map");
        Files.write(invalidStage.toPath(), "HTML".getBytes(StandardCharsets.UTF_8));
        boolean invalidStageRejected = false;
        try { repo.installFile("bad-stage", "Bad", invalidStage, "Test", "", null, null); }
        catch (IOException expected) { invalidStageRejected = true; }
        check("installFile refuse un fichier non Mapsforge sans le déplacer", invalidStageRejected && invalidStage.exists());

        File commitStage = new File(stagingDirectory, "commit-fail.map");
        Files.write(commitStage.toPath(), valid);
        File blockedMetadataPart = new File(new File(temp, "metadata"), "commit-fail.properties.part");
        blockedMetadataPart.mkdirs();
        Files.write(new File(blockedMetadataPart, "blocker").toPath(), new byte[] {1});
        boolean commitRollback = false;
        try { repo.installFile("commit-fail", "Commit fail", commitStage,
                "Test", "", null, null); }
        catch (IOException expected) { commitRollback = commitStage.isFile(); }
        check("échec du commit des métadonnées remet le fichier au staging", commitRollback);
        new File(blockedMetadataPart, "blocker").delete();
        blockedMetadataPart.delete();

        AtomicBoolean closed = new AtomicBoolean(false);
        ByteArrayInputStream closeAware = new ByteArrayInputStream(valid) {
            @Override public void close() throws IOException { closed.set(true); super.close(); }
        };
        repo.install("bretagne", "Bretagne", closeAware, valid.length, validHash,
                "Test", null, null);
        check("le repository ferme le flux fourni", closed.get());

        byte[] invalidSignature = "<html>not a map</html>".getBytes(StandardCharsets.UTF_8);
        check("HTML renommé en .map refusé", installFails(repo, "bad-html", invalidSignature,
                invalidSignature.length, null, null));
        byte[] unknownVersion = valid.clone();
        unknownVersion[27] = 6; // 4 octets version à offset 24..27.
        check("version Mapsforge inconnue refusée", installFails(repo, "bad-version", unknownVersion,
                unknownVersion.length, null, null));
        check("taille annoncée incohérente refusée", installFails(repo, "bad-size", valid,
                valid.length + 1L, validHash, null));
        check("empreinte SHA-256 erronée refusée", installFails(repo, "bad-hash", valid,
                valid.length, "0".repeat(64), null));
        check("identifiant de région avec traversal refusé", installFails(repo, "../escape", valid,
                valid.length, validHash, null));

        check("cancellation pendant l'installation refusée", installFails(repo, "cancelled", valid,
                valid.length, validHash, () -> true));
        File[] mapFiles = new File(temp, "maps").listFiles();
        boolean partFound = false;
        if (mapFiles != null) {
            for (File f : mapFiles) if (f.getName().endsWith(".part")) partFound = true;
        }
        check("aucun .part ne reste après une annulation", !partFound);

        byte[] changed = mapsforgeHeader(1_800_000_100_000L);
        String changedHash = sha256(changed);
        repo.install("ile-de-france", "Île-de-France actualisée", new ByteArrayInputStream(changed),
                changed.length, changedHash, "Second fixture", null, null);
        OfflineMapRepository.InstalledMap reinstalled = repo.listInstalledMaps().stream()
                .filter(m -> m.regionId.equals("ile-de-france")).findFirst().orElse(null);
        check("mise à jour d'une région bascule vers le nouveau contenu", reinstalled != null
                && changedHash.equals(reinstalled.sha256));
        check("l'ancienne version reste isolée au lieu d'être supprimée à l'aveugle",
                installed.file.isFile() && !installed.file.equals(reinstalled.file));

        MapFileLeaseRegistry.acquire(installed.file);
        OfflineMapRepository.CleanupResult deferredCleanup = repo.cleanupUnreferencedMapFiles();
        check("un fichier .map orphelin mais encore ouvert est différé",
                deferredCleanup.deletedFiles == 0 && deferredCleanup.deferredFiles == 1
                        && installed.file.isFile());
        MapFileLeaseRegistry.release(installed.file);
        OfflineMapRepository.CleanupResult completedCleanup = repo.cleanupUnreferencedMapFiles();
        check("l'ancienne version est supprimée après fermeture du renderer",
                completedCleanup.deletedFiles == 1 && !installed.file.exists());
        check("les cartes référencées par leurs métadonnées restent installées",
                repo.listInstalledMaps().size() == 3);

        File metadata = new File(new File(temp, "metadata"), "corrupt.properties");
        try (FileOutputStream out = new FileOutputStream(metadata)) {
            out.write("regionId=corrupt\nfileName=../../escape.map\nsizeBytes=62\n".getBytes(StandardCharsets.UTF_8));
        }
        check("métadonnées corrompues ou chemin dangereux ignorés", repo.listInstalledMaps().size() == 3);
        OfflineMapRepository.CleanupResult uncertainCleanup = repo.cleanupUnreferencedMapFiles();
        check("un cleanup est entièrement suspendu si les métadonnées sont incertaines",
                uncertainCleanup.skippedBecauseMetadataUncertain && uncertainCleanup.deletedFiles == 0);
        check("fixture de métadonnées corrompues retiré après le test", metadata.delete());

        MapFileLeaseRegistry.acquire(repo.getMapFile("bretagne"));
        File leasedBretagne = repo.getMapFile("bretagne");
        check("suppression logique d'une carte toujours ouverte conserve le fichier",
                repo.remove("bretagne") && leasedBretagne.isFile());
        MapFileLeaseRegistry.release(leasedBretagne);
        check("nettoyage différé supprime le fichier après fermeture", 
                repo.cleanupUnreferencedMapFiles().deletedFiles == 1 && !leasedBretagne.exists());
        check("région supprimée absente du catalogue", repo.listInstalledMaps().size() == 2);
        check("deuxième suppression idempotente retourne false", !repo.remove("bretagne"));

        boolean unsafeLookupRejected = false;
        try { repo.getMapFile("../escape"); } catch (IOException expected) { unsafeLookupRejected = true; }
        check("recherche de région avec traversal refusée", unsafeLookupRejected);

        System.out.println("RESULTAT : " + ok + " réussis, " + ko + " échoués");
        if (ko != 0) throw new AssertionError(ko + " test(s) échoué(s)");
    }
}
