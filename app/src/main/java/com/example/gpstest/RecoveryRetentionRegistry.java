package com.example.gpstest;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Registre des copies locales de récupération dont la publication SAF a été
 * vérifiée et dont le snapshot source a déjà été acquitté.
 *
 * Ce registre n'autorise aucune suppression à lui seul. Il constitue une
 * preuve de provenance pour une éventuelle action future, explicitement
 * confirmée par l'utilisateur.
 */
public final class RecoveryRetentionRegistry {
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_ENTRIES = 128;
    public static final int MAX_TEXT_CHARS = 2 * 1024 * 1024;

    public static final class Entry {
        public final String publicationId;
        public final String sessionId;
        public final long segmentGeneration;
        public final String sourceName;
        public final String recoveredName;
        public final long recoveredLengthBytes;
        public final String sha256;
        public final String treeUri;
        public final String documentUri;
        public final String displayName;
        public final long publishedAtMs;

        private Entry(String publicationId, String sessionId, long segmentGeneration,
                      String sourceName, String recoveredName, long recoveredLengthBytes,
                      String sha256, String treeUri, String documentUri,
                      String displayName, long publishedAtMs) {
            this.publicationId = publicationId;
            this.sessionId = sessionId;
            this.segmentGeneration = segmentGeneration;
            this.sourceName = sourceName;
            this.recoveredName = recoveredName;
            this.recoveredLengthBytes = recoveredLengthBytes;
            this.sha256 = sha256;
            this.treeUri = treeUri;
            this.documentUri = documentUri;
            this.displayName = displayName;
            this.publishedAtMs = publishedAtMs;
        }

        public static Entry published(String publicationId, String sessionId,
                                      long segmentGeneration, String sourceName,
                                      String recoveredName, long recoveredLengthBytes,
                                      String sha256, String treeUri, String documentUri,
                                      String displayName, long publishedAtMs) {
            Entry entry = new Entry(publicationId, sessionId, segmentGeneration,
                    sourceName, recoveredName, recoveredLengthBytes, sha256, treeUri,
                    documentUri, displayName, publishedAtMs);
            return isValid(entry) ? entry : null;
        }

        private static boolean isValid(Entry e) {
            return e != null
                    && isHex(e.publicationId, 64)
                    && safeToken(e.sessionId, 128)
                    && e.segmentGeneration >= 0L
                    && safeLeaf(e.sourceName)
                    && safeRecoveredLeaf(e.recoveredName)
                    && !e.sourceName.equals(e.recoveredName)
                    && e.recoveredLengthBytes > 0L
                    && isHex(e.sha256, 64)
                    && contentUri(e.treeUri)
                    && contentUri(e.documentUri)
                    && safeLeaf(e.displayName)
                    && e.displayName.toLowerCase(Locale.ROOT).endsWith(".gpx")
                    && e.publishedAtMs > 0L;
        }

        public boolean sameRecord(Entry other) {
            return other != null
                    && publicationId.equals(other.publicationId)
                    && sessionId.equals(other.sessionId)
                    && segmentGeneration == other.segmentGeneration
                    && sourceName.equals(other.sourceName)
                    && recoveredName.equals(other.recoveredName)
                    && recoveredLengthBytes == other.recoveredLengthBytes
                    && sha256.equals(other.sha256)
                    && treeUri.equals(other.treeUri)
                    && documentUri.equals(other.documentUri)
                    && displayName.equals(other.displayName)
                    && publishedAtMs == other.publishedAtMs;
        }
    }

    private final ArrayList<Entry> entries;

    private RecoveryRetentionRegistry(ArrayList<Entry> entries) { this.entries = entries; }

    public static RecoveryRetentionRegistry empty() {
        return new RecoveryRetentionRegistry(new ArrayList<>());
    }

    /** Null indique un registre illisible : le store conserve son fichier brut. */
    public static RecoveryRetentionRegistry parse(String text) {
        if (text == null || text.length() > MAX_TEXT_CHARS || !text.endsWith("\n")
                || text.indexOf('\r') >= 0) return null;
        String[] lines = text.split("\n", -1);
        if (lines.length < 3
                || !("retentionFormat=" + FORMAT_VERSION).equals(lines[0])
                || !lines[1].startsWith("count=")) return null;
        final int count;
        try { count = Integer.parseInt(lines[1].substring(6)); }
        catch (NumberFormatException invalid) { return null; }
        if (count < 0 || count > MAX_ENTRIES || lines.length != count + 3
                || !lines[count + 2].isEmpty()) return null;

        ArrayList<Entry> parsed = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String line = lines[i + 2];
            if (!line.startsWith("entry=")) return null;
            String[] fields = line.substring(6).split("\\|", -1);
            if (fields.length != 11) return null;
            String publicationId = decode(fields[0]);
            String sessionId = decode(fields[1]);
            String sourceName = decode(fields[3]);
            String recoveredName = decode(fields[4]);
            String sha256 = decode(fields[6]);
            String treeUri = decode(fields[7]);
            String documentUri = decode(fields[8]);
            String displayName = decode(fields[9]);
            final long generation, length, publishedAt;
            try {
                generation = Long.parseLong(fields[2]);
                length = Long.parseLong(fields[5]);
                publishedAt = Long.parseLong(fields[10]);
            } catch (NumberFormatException invalid) { return null; }
            if (publicationId == null || sessionId == null || sourceName == null
                    || recoveredName == null || sha256 == null || treeUri == null
                    || documentUri == null || displayName == null) return null;
            Entry entry = Entry.published(publicationId, sessionId, generation,
                    sourceName, recoveredName, length, sha256, treeUri, documentUri,
                    displayName, publishedAt);
            if (entry == null) return null;
            for (Entry old : parsed) if (old.publicationId.equals(entry.publicationId)) return null;
            parsed.add(entry);
        }
        return new RecoveryRetentionRegistry(parsed);
    }

    public int size() { return entries.size(); }
    public boolean isEmpty() { return entries.isEmpty(); }

    public List<Entry> entries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public Entry find(String publicationId) {
        if (publicationId == null) return null;
        for (Entry entry : entries) if (publicationId.equals(entry.publicationId)) return entry;
        return null;
    }

    /**
     * L'ajout est idempotent uniquement si tous les champs sont identiques.
     * Une entrée historique ne peut pas être rajeunie par une nouvelle tentative.
     */
    public boolean addOrVerify(Entry incoming) {
        if (!Entry.isValid(incoming)) return false;
        for (Entry existing : entries) {
            if (!existing.publicationId.equals(incoming.publicationId)) continue;
            return existing.sameRecord(incoming);
        }
        if (entries.size() >= MAX_ENTRIES) return false;
        entries.add(incoming);
        return true;
    }

    /** Retrait exact réservé au futur mécanisme de purge explicitement confirmé. */
    public boolean removeIfUnchanged(Entry expected) {
        if (expected == null) return false;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).sameRecord(expected)) {
                entries.remove(i);
                return true;
            }
        }
        return false;
    }

    public String toText() {
        StringBuilder out = new StringBuilder(128 + entries.size() * 512);
        out.append("retentionFormat=").append(FORMAT_VERSION).append('\n');
        out.append("count=").append(entries.size()).append('\n');
        for (Entry e : entries) {
            out.append("entry=").append(encode(e.publicationId)).append('|')
                    .append(encode(e.sessionId)).append('|').append(e.segmentGeneration).append('|')
                    .append(encode(e.sourceName)).append('|').append(encode(e.recoveredName)).append('|')
                    .append(e.recoveredLengthBytes).append('|').append(encode(e.sha256)).append('|')
                    .append(encode(e.treeUri)).append('|').append(encode(e.documentUri)).append('|')
                    .append(encode(e.displayName)).append('|').append(e.publishedAtMs).append('\n');
        }
        return out.toString();
    }

    private static String encode(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        char[] digits = "0123456789abcdef".toCharArray();
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int v = b & 255;
            out.append(digits[v >>> 4]).append(digits[v & 15]);
        }
        return out.toString();
    }

    private static String decode(String value) {
        if (value == null || (value.length() & 1) != 0 || value.length() > 32 * 1024) return null;
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < value.length(); i += 2) {
            int high = hex(value.charAt(i)), low = hex(value.charAt(i + 1));
            if (high < 0 || low < 0) return null;
            bytes[i / 2] = (byte) ((high << 4) | low);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) { return null; }
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        return -1;
    }

    private static boolean isHex(String value, int length) {
        if (value == null || value.length() != length) return false;
        for (int i = 0; i < value.length(); i++) if (hex(value.charAt(i)) < 0) return false;
        return true;
    }

    private static boolean safeToken(String value, int max) {
        if (value == null || value.isEmpty() || value.length() > max) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'
                    || c >= '0' && c <= '9' || c == '-' || c == '_')) return false;
        }
        return true;
    }

    private static boolean safeLeaf(String value) {
        if (value == null || value.isEmpty() || value.length() > 512
                || value.equals(".") || value.equals("..")
                || value.contains("/") || value.contains("\\")) return false;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 0x20) return false;
        return true;
    }

    private static boolean safeRecoveredLeaf(String value) {
        return safeLeaf(value) && value.toLowerCase(Locale.ROOT).endsWith(".gpx")
                && value.toLowerCase(Locale.ROOT).contains("_recuperee");
    }

    private static boolean contentUri(String value) {
        return value != null && value.startsWith("content://") && value.length() <= 8192;
    }
}
