package com.example.gpstest;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Properties;

/** Cache persistant des petits fichiers .poly, séparé des cartes .map. */
public final class OfflineBoundaryRepository {
    public interface Fetcher {
        byte[] fetch(String relativePath, MapsforgeBoundaryClient.CancellationToken cancellation)
                throws IOException;
    }

    public static final class BoundaryData {
        public final String relativePath;
        public final String sourceVersion;
        public final MapsforgePolyParser.Polygon polygon;
        public final boolean stale;
        public final boolean downloaded;
        public final long sizeBytes;
        private BoundaryData(String relativePath, String sourceVersion,
                MapsforgePolyParser.Polygon polygon, boolean stale, boolean downloaded,
                long sizeBytes) {
            this.relativePath = relativePath; this.sourceVersion = sourceVersion;
            this.polygon = polygon; this.stale = stale; this.downloaded = downloaded;
            this.sizeBytes = sizeBytes;
        }
    }

    private final File root;

    public OfflineBoundaryRepository(File root) throws IOException {
        if (root == null) throw new IOException("Dossier de frontières absent");
        this.root = root.getCanonicalFile();
        if (!this.root.isDirectory() && !this.root.mkdirs() && !this.root.isDirectory()) {
            throw new IOException("Impossible de créer le cache des frontières");
        }
    }

    public synchronized BoundaryData loadOrFetch(String relativePath, String sourceVersion,
            Fetcher fetcher, MapsforgeBoundaryClient.CancellationToken cancellation)
            throws IOException {
        String path = validatePath(relativePath);
        String key = sha256(path.getBytes(StandardCharsets.UTF_8));
        File cached = new File(root, key + ".poly").getCanonicalFile();
        File meta = new File(root, key + ".properties").getCanonicalFile();
        if (!root.equals(cached.getParentFile()) || !root.equals(meta.getParentFile())) {
            throw new IOException("Chemin de cache de frontière invalide");
        }
        Properties properties = read(meta);
        String cachedVersion = properties.getProperty("sourceVersion", "");
        if (cached.isFile() && cached.length() > 0
                && (sourceVersion == null || sourceVersion.isEmpty()
                    || sourceVersion.equals(cachedVersion))) {
            try (FileInputStream in = new FileInputStream(cached)) {
                return new BoundaryData(path, cachedVersion,
                        MapsforgePolyParser.parse(in), false, false, cached.length());
            } catch (IOException corruptCache) {
                // Le cache invalide sera remplacé depuis la source si possible.
            }
        }
        if (fetcher == null) throw new IOException("Aucune source disponible pour les frontières");
        try {
            if (cancellation != null && cancellation.isCancelled()) {
                throw new IOException("Chargement des frontières annulé");
            }
            byte[] bytes = fetcher.fetch(path, cancellation);
            MapsforgePolyParser.Polygon polygon = MapsforgePolyParser.parse(bytes);
            File temp = new File(root, key + ".poly.part");
            if (temp.exists() && !temp.delete()) throw new IOException("Cache de frontière temporaire bloqué");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(bytes);
                out.getFD().sync();
            }
            if (cached.exists() && !cached.delete()) {
                temp.delete();
                throw new IOException("Impossible de remplacer l'ancienne frontière");
            }
            if (!temp.renameTo(cached)) {
                temp.delete();
                throw new IOException("Impossible de publier la frontière dans le cache");
            }
            Properties newProperties = new Properties();
            newProperties.setProperty("relativePath", path);
            newProperties.setProperty("sourceVersion", sourceVersion == null ? "" : sourceVersion);
            newProperties.setProperty("cachedAtUtcMs", Long.toString(System.currentTimeMillis()));
            commit(meta, newProperties);
            // Les .poly sont petits, mais parcourir de nombreux pays ne doit
            // pas remplir sans limite le stockage persistant de l'application.
            pruneToMaxBytes(64L * 1024L * 1024L);
            return new BoundaryData(path, sourceVersion == null ? "" : sourceVersion,
                    polygon, false, true, bytes.length);
        } catch (IOException fetchFailure) {
            if (cached.isFile() && cached.length() > 0) {
                try (FileInputStream in = new FileInputStream(cached)) {
                    return new BoundaryData(path, cachedVersion,
                            MapsforgePolyParser.parse(in), true, false, cached.length());
                } catch (IOException invalidCached) {
                    fetchFailure.addSuppressed(invalidCached);
                }
            }
            throw fetchFailure;
        }
    }

    /**
     * Charge les contours déjà mémorisés pour un dossier lorsque le réseau
     * n'est pas disponible. Seuls les .poly directement contenus dans ce
     * dossier sont retournés ; aucune exploration implicite des sous-dossiers.
     */
    public synchronized java.util.List<BoundaryData> loadCachedDirectory(String directory)
            throws IOException {
        String prefix = normalizeDirectory(directory);
        File[] metadataFiles = root.listFiles((dir, name) -> name.matches("[0-9a-f]{64}\\.properties"));
        if (metadataFiles == null || metadataFiles.length == 0) return java.util.Collections.emptyList();
        java.util.Arrays.sort(metadataFiles, java.util.Comparator.comparing(File::getName));
        java.util.ArrayList<BoundaryData> result = new java.util.ArrayList<>();
        for (File metadata : metadataFiles) {
            Properties properties;
            try { properties = read(metadata); }
            catch (IOException ignored) { continue; }
            String path = properties.getProperty("relativePath", "");
            if (!path.startsWith(prefix) || path.length() <= prefix.length()
                    || path.substring(prefix.length()).contains("/")) continue;
            try {
                validatePath(path);
                String key = metadata.getName().substring(0, metadata.getName().length()
                        - ".properties".length());
                File cached = new File(root, key + ".poly").getCanonicalFile();
                if (!root.equals(cached.getParentFile()) || !cached.isFile()
                        || cached.length() <= 0 || cached.length() > MapsforgePolyParser.MAX_BYTES) continue;
                try (FileInputStream in = new FileInputStream(cached)) {
                    MapsforgePolyParser.Polygon polygon = MapsforgePolyParser.parse(in);
                    result.add(new BoundaryData(path, properties.getProperty("sourceVersion", ""),
                            polygon, true, false, cached.length()));
                }
            } catch (IOException malformed) {
                // Un contour endommagé ne bloque pas les autres régions en cache.
            }
        }
        return java.util.Collections.unmodifiableList(result);
    }

    private static String normalizeDirectory(String directory) throws IOException {
        if (directory == null || directory.isEmpty()) return "";
        String value = directory.replace('\\', '/');
        while (value.startsWith("/")) value = value.substring(1);
        if (!value.endsWith("/")) value += "/";
        if (value.contains("..") || value.contains(":") || value.contains("?")
                || value.contains("%") || value.contains("//")) {
            throw new IOException("Dossier de frontières invalide");
        }
        String checked = value.substring(0, value.length() - 1);
        for (String part : checked.split("/")) {
            if (!part.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")) {
                throw new IOException("Dossier de frontières invalide");
            }
        }
        return value;
    }

    /**
     * Borne le cache total des frontières. Les plus anciennes sont retirées
     * en premier ; les polygones déjà chargés dans la View restent en mémoire.
     */
    public synchronized int pruneToMaxBytes(long maxBytes) throws IOException {
        if (maxBytes < 0L) throw new IOException("Limite de cache négative");
        File[] files = root.listFiles((dir, name) -> name.matches("[0-9a-f]{64}\\.poly"));
        if (files == null || files.length == 0) return 0;
        java.util.Arrays.sort(files, java.util.Comparator
                .comparingLong(File::lastModified)
                .thenComparing(File::getName));
        long total = 0L;
        for (File file : files) {
            long length = file.length();
            total = length > Long.MAX_VALUE - total ? Long.MAX_VALUE : total + length;
        }
        int removed = 0;
        for (File file : files) {
            if (total <= maxBytes) break;
            long length = file.length();
            if (!file.delete()) continue;
            total = Math.max(0L, total - length);
            removed++;
            File metadata = new File(root, file.getName().substring(0, file.getName().length() - 5)
                    + ".properties");
            if (metadata.exists()) metadata.delete();
        }
        return removed;
    }

    public synchronized int removeStaleCacheFiles(long olderThanUtcMs) throws IOException {
        File[] files = root.listFiles((dir, name) -> name.endsWith(".poly"));
        if (files == null) return 0;
        int removed = 0;
        for (File file : files) {
            if (file.lastModified() >= olderThanUtcMs) continue;
            String key = file.getName().substring(0, file.getName().length() - 5);
            File metadata = new File(root, key + ".properties");
            if (file.delete()) { removed++; if (metadata.exists()) metadata.delete(); }
        }
        return removed;
    }

    private void commit(File target, Properties value) throws IOException {
        File temp = new File(root, target.getName() + ".part");
        if (temp.exists() && !temp.delete()) throw new IOException("Métadonnées de frontière bloquées");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            value.store(out, "GPS-Test boundary cache");
            out.getFD().sync();
        }
        if (target.exists() && !target.delete()) throw new IOException("Anciennes métadonnées de frontière bloquées");
        if (!temp.renameTo(target)) throw new IOException("Impossible de valider le cache des frontières");
    }

    private static Properties read(File file) throws IOException {
        Properties p = new Properties();
        if (file.isFile()) try (FileInputStream in = new FileInputStream(file)) { p.load(in); }
        return p;
    }

    private static String validatePath(String path) throws IOException {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.contains("..")
                || path.contains("\\") || path.contains(":") || path.contains("%")
                || !path.toLowerCase(java.util.Locale.ROOT).endsWith(".poly")) {
            throw new IOException("Chemin de frontière invalide");
        }
        for (String part : path.split("/")) {
            if (!part.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")) {
                throw new IOException("Chemin de frontière invalide");
            }
        }
        return path;
    }

    private static String sha256(byte[] value) throws IOException {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder out = new StringBuilder(64);
            for (byte b : hash) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 indisponible", e);
        }
    }
}
