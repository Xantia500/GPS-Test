package com.example.gpstest;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Stockage persistant de cartes vectorielles Mapsforge dans un dossier
 * app-specific. Aucun cache de tuiles, aucun téléchargement réseau ici.
 *
 * L'installation est transactionnelle : flux -> .part -> contrôle taille,
 * SHA-256 et en-tête -> fichier versionné par empreinte -> métadonnées.
 * Une interruption ou un fichier invalide ne remplace jamais la carte déjà
 * installée pour cette région.
 */
public final class OfflineMapRepository implements MapDataSource {
    public static final long MAX_MAP_FILE_BYTES = 4L * 1024L * 1024L * 1024L;
    public static final long MIN_FREE_SPACE_MARGIN_BYTES = 64L * 1024L * 1024L;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern HASH_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern MAP_FILENAME_PATTERN = Pattern.compile(
            "[a-z0-9][a-z0-9._-]{0,63}-[0-9a-f]{64}\\.map");

    public interface CancellationToken { boolean isCancelled(); }
    public interface ProgressListener { void onProgress(long bytesRead, long expectedBytes); }

    public static final class InstalledMap {
        public final String regionId;
        public final String displayName;
        public final File file;
        public final long sizeBytes;
        public final String sha256;
        public final int formatVersion;
        public final long mapCreatedAtUtcMs;
        public final long installedAtUtcMs;
        public final String sourceLabel;
        /** Version distante déclarée par le catalogue (date HTTP/index), non signée. */
        public final String sourceVersion;
        /** Identifiant WorkManager de l'installation, vide pour les installations historiques. */
        public final String installId;
        public final double minLatitude;
        public final double minLongitude;
        public final double maxLatitude;
        public final double maxLongitude;
        public final int tileSize;

        private InstalledMap(String regionId, String displayName, File file,
                             long sizeBytes, String sha256, int formatVersion,
                             long mapCreatedAtUtcMs, long installedAtUtcMs,
                             String sourceLabel, String sourceVersion, String installId,
                             double minLatitude, double minLongitude,
                             double maxLatitude, double maxLongitude, int tileSize) {
            this.regionId = regionId;
            this.displayName = displayName;
            this.file = file;
            this.sizeBytes = sizeBytes;
            this.sha256 = sha256;
            this.formatVersion = formatVersion;
            this.mapCreatedAtUtcMs = mapCreatedAtUtcMs;
            this.installedAtUtcMs = installedAtUtcMs;
            this.sourceLabel = sourceLabel;
            this.sourceVersion = sourceVersion == null ? "" : sourceVersion;
            this.installId = installId == null ? "" : installId;
            this.minLatitude = minLatitude;
            this.minLongitude = minLongitude;
            this.maxLatitude = maxLatitude;
            this.maxLongitude = maxLongitude;
            this.tileSize = tileSize;
        }
    }

    private final File root;
    private final File mapsDirectory;
    private final File metadataDirectory;

    /** root doit être un dossier persistant app-specific, jamais getCacheDir(). */
    public OfflineMapRepository(File rootDirectory) throws IOException {
        if (rootDirectory == null) throw new IOException("Dossier de stockage absent");
        this.root = rootDirectory.getCanonicalFile();
        this.mapsDirectory = new File(root, "maps");
        this.metadataDirectory = new File(root, "metadata");
        ensureDirectory(root);
        ensureDirectory(mapsDirectory);
        ensureDirectory(metadataDirectory);
    }

    @Override
    public synchronized List<InstalledMap> listInstalledMaps() throws IOException {
        ensureDirectory(mapsDirectory);
        ensureDirectory(metadataDirectory);
        File[] metadataFiles = metadataDirectory.listFiles((dir, name) -> name.endsWith(".properties"));
        if (metadataFiles == null || metadataFiles.length == 0) return Collections.emptyList();

        ArrayList<InstalledMap> result = new ArrayList<>();
        for (File metadataFile : metadataFiles) {
            try {
                Properties p = readProperties(metadataFile);
                String id = p.getProperty("regionId", "");
                String mapName = p.getProperty("fileName", "");
                if (!isValidRegionId(id) || !MAP_FILENAME_PATTERN.matcher(mapName).matches()
                        || !mapName.startsWith(id + "-")) continue;
                File mapFile = new File(mapsDirectory, mapName).getCanonicalFile();
                if (!mapsDirectory.getCanonicalFile().equals(mapFile.getParentFile())) continue;
                long storedSize = parseLong(p.getProperty("sizeBytes"), -1L);
                if (!mapFile.isFile() || storedSize <= 0 || mapFile.length() != storedSize) continue;
                MapsforgeMapHeader.Info header = MapsforgeMapHeader.inspect(mapFile);
                String hash = p.getProperty("sha256", "");
                if (!HASH_PATTERN.matcher(hash).matches()
                        || !mapName.equals(id + "-" + hash + ".map")) continue;
                result.add(new InstalledMap(id, cleanDisplayName(p.getProperty("displayName", id)),
                        mapFile, storedSize, hash, header.formatVersion,
                        header.createdAtUtcMs, parseLong(p.getProperty("installedAtUtcMs"), 0L),
                        cleanSourceLabel(p.getProperty("sourceLabel", "")),
                        cleanSourceVersion(p.getProperty("sourceVersion", "")),
                        cleanInstallId(p.getProperty("installId", "")),
                        header.minLatitude, header.minLongitude, header.maxLatitude,
                        header.maxLongitude, header.tileSize));
            } catch (Exception ignored) {
                // Une métadonnée corrompue n'empêche pas l'affichage des autres cartes.
            }
        }
        result.sort(Comparator.comparing(m -> m.displayName.toLowerCase(java.util.Locale.ROOT)));
        return Collections.unmodifiableList(result);
    }

    @Override
    public synchronized File getMapFile(String regionId) throws IOException {
        validateRegionId(regionId);
        for (InstalledMap map : listInstalledMaps()) {
            if (regionId.equals(map.regionId)) return map.file;
        }
        throw new IOException("Carte non installée : " + regionId);
    }

    /**
     * Installe un fichier Mapsforge v5 reçu en flux.
     *
     * expectedBytes peut valoir -1 si la source ne fournit pas sa taille.
     * expectedSha256 peut être null si la source ne publie pas d'empreinte.
     * Le repository consomme puis ferme le flux fourni, y compris en cas
     * d'annulation ou d'erreur. L'appelant ne doit pas le réutiliser.
     */
    public synchronized InstalledMap install(String regionId, String displayName,
            InputStream input, long expectedBytes, String expectedSha256,
            String sourceLabel, CancellationToken cancellation,
            ProgressListener progress) throws IOException {
        return install(regionId, displayName, input, expectedBytes, expectedSha256,
                sourceLabel, "", cancellation, progress);
    }

    public synchronized InstalledMap install(String regionId, String displayName,
            InputStream input, long expectedBytes, String expectedSha256,
            String sourceLabel, String sourceVersion, CancellationToken cancellation,
            ProgressListener progress) throws IOException {
        validateRegionId(regionId);
        if (input == null) throw new IOException("Flux cartographique absent");
        String label = cleanDisplayName(displayName);
        String source = cleanSourceLabel(sourceLabel);
        if (expectedBytes == 0 || expectedBytes < -1 || expectedBytes > MAX_MAP_FILE_BYTES) {
            throw new IOException("Taille annoncée du fichier cartographique invalide");
        }
        String wantedHash = expectedSha256 == null ? null : expectedSha256.trim().toLowerCase(java.util.Locale.ROOT);
        if (wantedHash != null && !HASH_PATTERN.matcher(wantedHash).matches()) {
            throw new IOException("Empreinte SHA-256 attendue invalide");
        }
        long usableAtStart = root.getUsableSpace();
        if (usableAtStart > 0) {
            long required = MIN_FREE_SPACE_MARGIN_BYTES;
            if (expectedBytes > 0) {
                try { required = Math.addExact(expectedBytes, MIN_FREE_SPACE_MARGIN_BYTES); }
                catch (ArithmeticException overflow) { throw new IOException("Taille de carte excessive"); }
            }
            if (usableAtStart < required) {
                throw new IOException("Espace libre insuffisant pour installer cette carte");
            }
        }

        File part = new File(mapsDirectory, regionId + ".map.part");
        if (part.exists() && !part.delete()) throw new IOException("Impossible de nettoyer un téléchargement précédent");

        long bytes = 0L;
        long nextSpaceCheck = 1024L * 1024L;
        MessageDigest digest = sha256Digest();
        boolean committed = false;
        try {
            try (BufferedInputStream in = new BufferedInputStream(input);
                 FileOutputStream fileOut = new FileOutputStream(part, false);
                 BufferedOutputStream out = new BufferedOutputStream(fileOut)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (isCancelled(cancellation)) throw new IOException("Installation cartographique annulée");
                    bytes += read;
                    if (bytes > MAX_MAP_FILE_BYTES || (expectedBytes > 0 && bytes > expectedBytes)) {
                        throw new IOException("Fichier cartographique plus volumineux que prévu");
                    }
                    if (bytes >= nextSpaceCheck) {
                        long usable = root.getUsableSpace();
                        if (usable > 0 && usable < MIN_FREE_SPACE_MARGIN_BYTES) {
                            throw new IOException("Espace libre insuffisant pendant l'installation de la carte");
                        }
                        nextSpaceCheck = bytes + 1024L * 1024L;
                    }
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                    if (progress != null) progress.onProgress(bytes, expectedBytes);
                }
                out.flush();
                // Les données de la carte doivent être durables avant que les
                // métadonnées atomiques ne la rendent visible comme installée.
                fileOut.getFD().sync();
            }
            if (isCancelled(cancellation)) throw new IOException("Installation cartographique annulée");
            if (bytes == 0 || (expectedBytes > 0 && bytes != expectedBytes)) {
                throw new IOException("Téléchargement incomplet : taille reçue différente de la taille annoncée");
            }
            String actualHash = toHex(digest.digest());
            if (wantedHash != null && !wantedHash.equals(actualHash)) {
                throw new IOException("Empreinte SHA-256 incorrecte : fichier corrompu ou altéré");
            }
            MapsforgeMapHeader.Info header = MapsforgeMapHeader.inspect(part);
            String finalName = regionId + "-" + actualHash + ".map";
            File finalMap = new File(mapsDirectory, finalName);
            if (finalMap.exists()) {
                if (finalMap.length() != bytes || !actualHash.equals(sha256(finalMap))) {
                    if (!finalMap.delete()) throw new IOException("Carte existante corrompue et impossible à remplacer");
                }
            }
            if (!finalMap.exists() && !part.renameTo(finalMap)) {
                throw new IOException("Impossible de finaliser le fichier cartographique");
            }
            if (part.exists() && !part.delete()) {
                // Le fichier final est déjà complet. Le .part ne sera jamais listé comme installé.
            }

            Properties properties = new Properties();
            properties.setProperty("regionId", regionId);
            properties.setProperty("displayName", label);
            properties.setProperty("fileName", finalName);
            properties.setProperty("sizeBytes", Long.toString(bytes));
            properties.setProperty("sha256", actualHash);
            properties.setProperty("formatVersion", Integer.toString(header.formatVersion));
            properties.setProperty("mapCreatedAtUtcMs", Long.toString(header.createdAtUtcMs));
            properties.setProperty("installedAtUtcMs", Long.toString(System.currentTimeMillis()));
            properties.setProperty("sourceLabel", source);
            properties.setProperty("sourceVersion", cleanSourceVersion(sourceVersion));
            commitMetadata(regionId, properties);
            committed = true;

            for (InstalledMap installed : listInstalledMaps()) {
                if (regionId.equals(installed.regionId)) return installed;
            }
            throw new IOException("La carte a été écrite mais ses métadonnées sont illisibles");
        } finally {
            if (part.exists() && !part.delete()) {
                // Nettoyage best-effort ; les .part ne sont jamais reconnus par listInstalledMaps().
            }
            if (!committed) {
                // Tout fichier versionné non référencé reste un orphelin inoffensif ;
                // le nettoyage différé ne doit pas supprimer une carte actuellement ouverte.
            }
        }
    }

    /**
     * Installe un fichier déjà téléchargé dans un dossier de staging du même
     * volume. Le déplacement évite de dupliquer 100 à 500 Mio pendant
     * l'installation DownloadManager. En cas d'échec du commit des métadonnées,
     * le déplacement est annulé vers le staging lorsque cela reste possible.
     * Le SHA-256 est calculé localement ; il ne constitue pas une signature
     * fournisseur lorsque la source ne publie pas de hash.
     */
    public synchronized InstalledMap installFile(String regionId, String displayName,
            File stagedFile, String sourceLabel, String sourceVersion,
            String expectedSha256, CancellationToken cancellation) throws IOException {
        return installFile(regionId, displayName, stagedFile, sourceLabel, sourceVersion,
                expectedSha256, cancellation, "");
    }

    /** Variante idempotente : persiste le token du Worker dans les métadonnées de carte. */
    public synchronized InstalledMap installFile(String regionId, String displayName,
            File stagedFile, String sourceLabel, String sourceVersion,
            String expectedSha256, CancellationToken cancellation, String installId) throws IOException {
        validateRegionId(regionId);
        String safeInstallId = cleanInstallId(installId);
        if (stagedFile == null || !stagedFile.isFile() || stagedFile.length() <= 0) {
            throw new IOException("Fichier téléchargé absent ou vide");
        }
        // Le gestionnaire de téléchargements Android peut exposer un chemin
        // sous un alias de montage (ex. stockage émulé). Le contrôle du dossier
        // app-specific est effectué par l'appelant ; ici, on travaille toujours
        // sur le chemin canonique afin d'éviter de déplacer un lien symbolique.
        File canonicalStage = stagedFile.getCanonicalFile();
        if (!canonicalStage.isFile()) {
            throw new IOException("Fichier temporaire inaccessible");
        }
        long bytes = canonicalStage.length();
        if (bytes > MAX_MAP_FILE_BYTES) throw new IOException("Fichier cartographique trop volumineux");
        if (isCancelled(cancellation)) throw new IOException("Installation cartographique annulée");
        String wantedHash = expectedSha256 == null ? null
                : expectedSha256.trim().toLowerCase(java.util.Locale.ROOT);
        if (wantedHash != null && !HASH_PATTERN.matcher(wantedHash).matches()) {
            throw new IOException("Empreinte SHA-256 attendue invalide");
        }
        String actualHash = sha256(canonicalStage, cancellation);
        if (wantedHash != null && !wantedHash.equals(actualHash)) {
            throw new IOException("Empreinte SHA-256 incorrecte : fichier corrompu ou altéré");
        }
        if (isCancelled(cancellation)) throw new IOException("Installation cartographique annulée");
        MapsforgeMapHeader.Info header = MapsforgeMapHeader.inspect(canonicalStage);
        String finalName = regionId + "-" + actualHash + ".map";
        File finalMap = new File(mapsDirectory, finalName).getCanonicalFile();
        if (!mapsDirectory.getCanonicalFile().equals(finalMap.getParentFile())) {
            throw new IOException("Destination cartographique invalide");
        }
        if (finalMap.exists()) {
            if (finalMap.length() != bytes || !actualHash.equals(sha256(finalMap))) {
                if (!finalMap.delete()) throw new IOException("Carte existante corrompue et impossible à remplacer");
            }
        }
        boolean movedFromStaging = false;
        if (!finalMap.exists()) {
            if (!canonicalStage.renameTo(finalMap)) {
                throw new IOException("Impossible de déplacer la carte téléchargée vers le stockage permanent. "
                        + "Vérifiez l'espace disponible et le volume de stockage.");
            }
            movedFromStaging = true;
        }
        Properties properties = new Properties();
        properties.setProperty("regionId", regionId);
        properties.setProperty("displayName", cleanDisplayName(displayName));
        properties.setProperty("fileName", finalName);
        properties.setProperty("sizeBytes", Long.toString(bytes));
        properties.setProperty("sha256", actualHash);
        properties.setProperty("formatVersion", Integer.toString(header.formatVersion));
        properties.setProperty("mapCreatedAtUtcMs", Long.toString(header.createdAtUtcMs));
        properties.setProperty("installedAtUtcMs", Long.toString(System.currentTimeMillis()));
        properties.setProperty("sourceLabel", cleanSourceLabel(sourceLabel));
        properties.setProperty("sourceVersion", cleanSourceVersion(sourceVersion));
        if (!safeInstallId.isEmpty()) properties.setProperty("installId", safeInstallId);
        try {
            commitMetadata(regionId, properties);
        } catch (IOException commitFailure) {
            if (movedFromStaging && finalMap.isFile() && !finalMap.renameTo(canonicalStage)) {
                IOException rollback = new IOException(
                        "Le commit des métadonnées a échoué et le fichier n'a pas pu revenir au staging.");
                commitFailure.addSuppressed(rollback);
            }
            throw commitFailure;
        }
        for (InstalledMap installed : listInstalledMaps()) {
            if (regionId.equals(installed.regionId)) return installed;
        }
        throw new IOException("La carte a été écrite mais ses métadonnées sont illisibles");
    }

    /** Supprime une région. L'appelant doit d'abord fermer le moteur qui l'utilise. */
    public synchronized boolean remove(String regionId) throws IOException {
        validateRegionId(regionId);
        File metadata = metadataFile(regionId);
        if (!metadata.exists()) return false;
        Properties p = readProperties(metadata);
        String fileName = p.getProperty("fileName", "");
        if (!MAP_FILENAME_PATTERN.matcher(fileName).matches() || !fileName.startsWith(regionId + "-")) {
            throw new IOException("Métadonnées de carte invalides ; suppression refusée");
        }
        File map = new File(mapsDirectory, fileName).getCanonicalFile();
        if (!mapsDirectory.getCanonicalFile().equals(map.getParentFile())) {
            throw new IOException("Chemin de carte invalide ; suppression refusée");
        }
        // Un renderer peut encore avoir MapFile ouvert pendant que l'écran de
        // gestion est affiché au-dessus de la carte. Dans ce cas, on retire la
        // référence (la carte disparaît au prochain rechargement) et on laisse
        // le fichier orphelin jusqu'au nettoyage post-destruction du renderer.
        if (MapFileLeaseRegistry.isInUse(map)) {
            return !metadata.exists() || metadata.delete();
        }
        // Supprimer d'abord le fichier. Si la suppression échoue, les métadonnées
        // restent intactes et la région peut être réessayée.
        if (map.exists() && !map.delete()) return false;
        return !metadata.exists() || metadata.delete();
    }

    /**
     * Supprime uniquement les fichiers .map versionnés qui ne sont plus référencés
     * par aucune métadonnée valide et qui ne sont ouverts par aucun renderer.
     * À appeler après fermeture/recréation de la carte ; les fichiers .part et les
     * téléchargements staging ne sont jamais touchés.
     */
    public synchronized CleanupResult cleanupUnreferencedMapFiles() throws IOException {
        File[] metadataFiles = metadataDirectory.listFiles((dir, name) -> name.endsWith(".properties"));
        java.util.HashSet<String> referenced = new java.util.HashSet<>();
        boolean uncertain = false;
        if (metadataFiles != null) {
            for (File metadataFile : metadataFiles) {
                try {
                    Properties p = readProperties(metadataFile);
                    String fileName = p.getProperty("fileName", "");
                    if (MAP_FILENAME_PATTERN.matcher(fileName).matches()) {
                        File candidate = new File(mapsDirectory, fileName).getCanonicalFile();
                        if (mapsDirectory.getCanonicalFile().equals(candidate.getParentFile())) {
                            referenced.add(candidate.getName());
                        } else {
                            uncertain = true;
                        }
                    } else {
                        // Une métadonnée inconnue peut référencer un fichier que
                        // cette version ne sait pas interpréter : ne rien détruire.
                        uncertain = true;
                    }
                } catch (Exception corruptMetadata) {
                    uncertain = true;
                }
            }
        }
        if (uncertain) return new CleanupResult(0, 0, true);
        File[] mapFiles = mapsDirectory.listFiles((dir, name) -> name.endsWith(".map"));
        if (mapFiles == null) return new CleanupResult(0, 0, false);
        int deleted = 0;
        int deferred = 0;
        for (File file : mapFiles) {
            if (referenced.contains(file.getName())) continue;
            if (MapFileLeaseRegistry.isInUse(file)) { deferred++; continue; }
            if (file.delete()) deleted++; else deferred++;
        }
        return new CleanupResult(deleted, deferred, false);
    }

    public static final class CleanupResult {
        public final int deletedFiles;
        public final int deferredFiles;
        public final boolean skippedBecauseMetadataUncertain;
        private CleanupResult(int deletedFiles, int deferredFiles, boolean skipped) {
            this.deletedFiles = deletedFiles;
            this.deferredFiles = deferredFiles;
            this.skippedBecauseMetadataUncertain = skipped;
        }
    }

    private void commitMetadata(String regionId, Properties properties) throws IOException {
        File target = metadataFile(regionId);
        File temp = new File(metadataDirectory, regionId + ".properties.part");
        File backup = new File(metadataDirectory, regionId + ".properties.bak");
        if (temp.exists() && !temp.delete()) throw new IOException("Impossible de préparer les métadonnées");
        try (FileOutputStream out = new FileOutputStream(temp, false)) {
            properties.store(out, "GPS-Test offline vector map");
            out.getFD().sync();
        }
        if (backup.exists() && !backup.delete()) throw new IOException("Impossible de remplacer les anciennes métadonnées");
        boolean hadTarget = target.exists();
        if (hadTarget && !target.renameTo(backup)) throw new IOException("Impossible de sauvegarder les métadonnées actuelles");
        if (!temp.renameTo(target)) {
            if (hadTarget) backup.renameTo(target);
            throw new IOException("Impossible de valider les métadonnées de la carte");
        }
        if (backup.exists() && !backup.delete()) {
            // La nouvelle métadonnée est déjà engagée ; l'ancien backup est sans danger.
        }
    }

    private File metadataFile(String regionId) { return new File(metadataDirectory, regionId + ".properties"); }

    private static Properties readProperties(File file) throws IOException {
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(file)) { p.load(in); }
        return p;
    }

    private static boolean isCancelled(CancellationToken token) {
        return token != null && token.isCancelled();
    }

    private static void validateRegionId(String regionId) throws IOException {
        if (!isValidRegionId(regionId) || regionId.contains("..")) {
            throw new IOException("Identifiant de région invalide");
        }
    }

    private static boolean isValidRegionId(String id) {
        return id != null && ID_PATTERN.matcher(id).matches() && !id.contains("..");
    }

    private static String cleanDisplayName(String name) throws IOException {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty()) throw new IOException("Nom de région manquant");
        if (value.length() > 120) value = value.substring(0, 120).trim();
        return value;
    }

    private static String cleanSourceLabel(String value) {
        String source = value == null ? "" : value.trim();
        return source.length() > 120 ? source.substring(0, 120) : source;
    }

    private static String cleanInstallId(String value) throws IOException {
        if (value == null || value.isEmpty()) return "";
        String clean = value.trim();
        if (clean.length() > 128 || !clean.matches("[A-Za-z0-9._-]+")) {
            throw new IOException("Identifiant de transaction d'installation invalide");
        }
        return clean;
    }

    private static String cleanSourceVersion(String value) {
        String source = value == null ? "" : value.trim();
        return source.length() > 160 ? source.substring(0, 160) : source;
    }

    private static long parseLong(String value, long fallback) {
        try { return Long.parseLong(value); } catch (Exception ignored) { return fallback; }
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (directory.isDirectory()) return;
        if (directory.exists() || !directory.mkdirs()) {
            throw new IOException("Impossible de créer le dossier " + directory.getAbsolutePath());
        }
    }

    private static MessageDigest sha256Digest() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IOException("SHA-256 indisponible", impossible); }
    }

    private static String sha256(File file) throws IOException {
        return sha256(file, null);
    }

    private static String sha256(File file, CancellationToken cancellation) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (isCancelled(cancellation)) throw new IOException("Installation cartographique annulée");
                digest.update(buffer, 0, read);
            }
        }
        return toHex(digest.digest());
    }

    private static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }
}
