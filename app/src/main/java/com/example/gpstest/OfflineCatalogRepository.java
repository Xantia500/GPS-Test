package com.example.gpstest;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/** Cache durable des index HTML Mapsforge pour la navigation et la recherche hors ligne. */
public final class OfflineCatalogRepository {
    public static final int MAX_INDEX_BYTES = 2 * 1024 * 1024;
    public static final long MAX_CACHE_BYTES = 24L * 1024L * 1024L;
    public static final int MAX_CACHED_DIRECTORIES = 256;
    public static final int MAX_SEARCH_RESULTS = 500;

    public static final class Snapshot {
        public final String directory;
        public final List<MapsforgeCatalogParser.Entry> entries;
        public final long fetchedAtUtcMs;
        public final boolean stale;
        public final int htmlBytes;
        private Snapshot(String directory, List<MapsforgeCatalogParser.Entry> entries,
                         long fetchedAtUtcMs, boolean stale, int htmlBytes) {
            this.directory = directory;
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            this.fetchedAtUtcMs = fetchedAtUtcMs;
            this.stale = stale;
            this.htmlBytes = htmlBytes;
        }
    }

    private final File root;

    public OfflineCatalogRepository(File root) throws IOException {
        if (root == null) throw new IOException("Dossier du catalogue hors ligne absent");
        this.root = root.getCanonicalFile();
        if (!this.root.isDirectory() && !this.root.mkdirs() && !this.root.isDirectory()) {
            throw new IOException("Impossible de créer le cache du catalogue");
        }
    }

    public synchronized void saveDirectory(String directory, String html, long fetchedAtUtcMs)
            throws IOException {
        String normalized = normalizeDirectory(directory);
        if (html == null || html.isEmpty()) throw new IOException("Index de catalogue vide");
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_INDEX_BYTES) throw new IOException("Index de catalogue trop volumineux");
        // On valide l'HTML avant toute écriture. Une page d'erreur proxy/serveur
        // ne doit jamais remplacer un index encore exploitable hors ligne.
        List<MapsforgeCatalogParser.Entry> parsed = MapsforgeCatalogParser.parse(html, normalized);
        if (parsed.isEmpty() && !html.toLowerCase(Locale.ROOT).contains("<html")) {
            throw new IOException("Réponse de catalogue non reconnue");
        }
        String key = sha256(normalized.getBytes(StandardCharsets.UTF_8));
        File targetHtml = safeFile(key + ".html");
        File targetMeta = safeFile(key + ".properties");
        File tempHtml = safeFile(key + ".html.part");
        File tempMeta = safeFile(key + ".properties.part");
        writeSynced(tempHtml, bytes);
        Properties meta = new Properties();
        meta.setProperty("directory", normalized);
        meta.setProperty("fetchedAtUtcMs", Long.toString(Math.max(0L, fetchedAtUtcMs)));
        meta.setProperty("htmlBytes", Integer.toString(bytes.length));
        meta.setProperty("sha256", sha256(bytes));
        try (FileOutputStream out = new FileOutputStream(tempMeta)) {
            meta.store(out, "GPS-Test offline Mapsforge catalogue");
            out.getFD().sync();
        } catch (IOException e) {
            tempHtml.delete();
            tempMeta.delete();
            throw e;
        }
        replacePair(tempHtml, targetHtml, tempMeta, targetMeta, key);
        targetHtml.setLastModified(Math.max(targetHtml.lastModified(), fetchedAtUtcMs));
        pruneToLimits(MAX_CACHE_BYTES, MAX_CACHED_DIRECTORIES);
    }

    /** Retourne l'index mémorisé ; stale=true indique qu'il est utilisé sans confirmation réseau. */
    public synchronized Snapshot loadDirectory(String directory) throws IOException {
        String normalized = normalizeDirectory(directory);
        String key = sha256(normalized.getBytes(StandardCharsets.UTF_8));
        File htmlFile = safeFile(key + ".html");
        File metaFile = safeFile(key + ".properties");
        if (!htmlFile.isFile() || !metaFile.isFile()) return null;
        Properties meta = read(metaFile);
        if (!normalized.equals(meta.getProperty("directory", ""))) return null;
        long declaredBytes = parseLong(meta.getProperty("htmlBytes"), -1L);
        if (declaredBytes <= 0 || declaredBytes > MAX_INDEX_BYTES || htmlFile.length() != declaredBytes) {
            throw new IOException("Index de catalogue local incohérent");
        }
        byte[] bytes = readBounded(htmlFile, MAX_INDEX_BYTES);
        if (!sha256(bytes).equals(meta.getProperty("sha256", ""))) {
            throw new IOException("Empreinte du catalogue local incorrecte");
        }
        String html = new String(bytes, StandardCharsets.UTF_8);
        List<MapsforgeCatalogParser.Entry> entries = MapsforgeCatalogParser.parse(html, normalized);
        htmlFile.setLastModified(System.currentTimeMillis()); // LRU
        return new Snapshot(normalized, entries,
                parseLong(meta.getProperty("fetchedAtUtcMs"), 0L), true, bytes.length);
    }

    /** Recherche dans tous les dossiers déjà consultés ; aucune exploration réseau implicite. */
    public synchronized List<MapsforgeCatalogParser.Entry> searchCached(String query, int limit)
            throws IOException {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.length() < 2 || limit <= 0) return Collections.emptyList();
        int max = Math.min(MAX_SEARCH_RESULTS, limit);
        File[] metadata = root.listFiles((dir, name) -> name.matches("[0-9a-f]{64}\\.properties"));
        if (metadata == null || metadata.length == 0) return Collections.emptyList();
        java.util.Arrays.sort(metadata, Comparator.comparing(File::getName));
        LinkedHashMap<String, MapsforgeCatalogParser.Entry> results = new LinkedHashMap<>();
        for (File metaFile : metadata) {
            final Properties meta;
            final String directory;
            try {
                meta = read(metaFile);
                directory = normalizeDirectory(meta.getProperty("directory", ""));
            } catch (IOException malformedMetadata) {
                continue;
            }
            String key = metaFile.getName().substring(0, metaFile.getName().length() - ".properties".length());
            File htmlFile = safeFile(key + ".html");
            if (!htmlFile.isFile() || htmlFile.length() <= 0 || htmlFile.length() > MAX_INDEX_BYTES) continue;
            try {
                byte[] bytes = readBounded(htmlFile, MAX_INDEX_BYTES);
                if (!sha256(bytes).equals(meta.getProperty("sha256", ""))) continue;
                String html = new String(bytes, StandardCharsets.UTF_8);
                for (MapsforgeCatalogParser.Entry entry : MapsforgeCatalogParser.parse(html, directory)) {
                    String haystack = (entry.displayName + " " + entry.name + " " + entry.relativePath)
                            .toLowerCase(Locale.ROOT);
                    if (haystack.contains(needle)) results.put(entry.relativePath, entry);
                    if (results.size() >= max) break;
                }
            } catch (IOException ignored) {
                // Un index corrompu ne doit pas empêcher les autres résultats.
            }
            if (results.size() >= max) break;
        }
        ArrayList<MapsforgeCatalogParser.Entry> out = new ArrayList<>(results.values());
        out.sort(Comparator.comparing((MapsforgeCatalogParser.Entry e) -> !e.directory)
                .thenComparing(e -> e.displayName.toLowerCase(Locale.ROOT))
                .thenComparing(e -> e.relativePath));
        return Collections.unmodifiableList(out);
    }

    public synchronized int cachedDirectoryCount() {
        File[] files = root.listFiles((dir, name) -> name.matches("[0-9a-f]{64}\\.properties"));
        return files == null ? 0 : files.length;
    }

    public synchronized long cachedBytes() {
        File[] files = root.listFiles((dir, name) -> name.matches("[0-9a-f]{64}\\.html"));
        if (files == null) return 0L;
        long total = 0L;
        for (File file : files) total = file.length() > Long.MAX_VALUE - total
                ? Long.MAX_VALUE : total + file.length();
        return total;
    }

    public synchronized int pruneToLimits(long maxBytes, int maxDirectories) throws IOException {
        if (maxBytes < 0 || maxDirectories < 0) throw new IOException("Limite de cache invalide");
        File[] htmlFiles = root.listFiles((dir, name) -> name.matches("[0-9a-f]{64}\\.html"));
        if (htmlFiles == null) return 0;
        java.util.Arrays.sort(htmlFiles, Comparator.comparingLong(File::lastModified)
                .thenComparing(File::getName));
        long totalBytes = 0L;
        for (File file : htmlFiles) totalBytes = file.length() > Long.MAX_VALUE - totalBytes
                ? Long.MAX_VALUE : totalBytes + file.length();
        int count = htmlFiles.length;
        int removed = 0;
        for (File html : htmlFiles) {
            if (totalBytes <= maxBytes && count <= maxDirectories) break;
            String key = html.getName().substring(0, html.getName().length() - 5);
            File meta = safeFile(key + ".properties");
            long length = html.length();
            if (!html.delete()) continue;
            totalBytes = Math.max(0L, totalBytes - length);
            count--;
            removed++;
            if (meta.exists() && !meta.delete()) {
                // Un sidecar orphelin sera nettoyé au passage suivant ; il ne
                // représente plus un index chargeable sans le HTML associé.
            }
        }
        return removed;
    }

    private void replacePair(File tempHtml, File targetHtml, File tempMeta,
                             File targetMeta, String key) throws IOException {
        File backupHtml = safeFile(key + ".html.bak");
        File backupMeta = safeFile(key + ".properties.bak");
        backupHtml.delete(); backupMeta.delete();
        boolean hadHtml = targetHtml.exists(), hadMeta = targetMeta.exists();
        if (hadHtml && !targetHtml.renameTo(backupHtml)) throw new IOException("Ancien index impossible à sauvegarder");
        if (hadMeta && !targetMeta.renameTo(backupMeta)) {
            if (hadHtml) backupHtml.renameTo(targetHtml);
            throw new IOException("Anciennes métadonnées impossibles à sauvegarder");
        }
        boolean htmlPublished = tempHtml.renameTo(targetHtml);
        boolean metaPublished = htmlPublished && tempMeta.renameTo(targetMeta);
        if (!metaPublished) {
            if (htmlPublished) targetHtml.delete();
            if (hadHtml) backupHtml.renameTo(targetHtml);
            if (hadMeta) backupMeta.renameTo(targetMeta);
            tempHtml.delete(); tempMeta.delete();
            throw new IOException("Publication atomique du catalogue impossible");
        }
        backupHtml.delete(); backupMeta.delete();
    }

    private File safeFile(String name) throws IOException {
        File file = new File(root, name).getCanonicalFile();
        if (!root.equals(file.getParentFile())) throw new IOException("Chemin de cache invalide");
        return file;
    }

    private static String normalizeDirectory(String path) throws IOException {
        if (path == null || path.isEmpty()) return "";
        String value = path.replace('\\', '/');
        while (value.startsWith("/")) value = value.substring(1);
        if (!value.endsWith("/")) value += "/";
        if (value.contains("..") || value.contains(":") || value.contains("?")
                || value.contains("#") || value.contains("%") || value.contains("//")) {
            throw new IOException("Chemin de catalogue invalide");
        }
        String checked = value.substring(0, value.length() - 1);
        for (String part : checked.split("/")) if (!part.isEmpty()
                && !part.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")) {
            throw new IOException("Chemin de catalogue invalide");
        }
        return value;
    }

    private static Properties read(File file) throws IOException {
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(file)) { p.load(in); }
        return p;
    }

    private static byte[] readBounded(File file, int max) throws IOException {
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(file.length(), max))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (out.size() + read > max) throw new IOException("Index hors limite");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static void writeSynced(File file, byte[] bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
            out.getFD().sync();
        }
    }

    private static long parseLong(String value, long fallback) {
        try { return Long.parseLong(value); } catch (Exception ignored) { return fallback; }
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 indisponible", e);
        }
    }
}
