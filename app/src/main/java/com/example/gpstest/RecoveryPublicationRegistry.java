package com.example.gpstest;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Modèle pur du journal des publications SAF de récupérations GPX.
 *
 * Il est séparé de RecoveryRegistry : le premier protège les octets locaux à
 * récupérer, le second mémorise le transfert secondaire vers le fournisseur
 * SAF. Une publication ne remplace jamais la copie locale.
 */
public final class RecoveryPublicationRegistry {
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_ENTRIES = 64;
    public static final int MAX_TEXT_CHARS = 2 * 1024 * 1024;
    private static final int MAX_FIELD_HEX = 32 * 1024;

    public enum State { PREPARED, DOCUMENT_CREATED, VERIFIED }

    public static final class Entry {
        public final String publicationId;
        public final String sessionId;
        public final long segmentGeneration;
        public final String sourceName;
        public final String recoveredName;
        public final long sourceLength;
        public final String sha256;
        public final String treeUri;
        public final String displayName;
        public final String documentUri;
        public final State state;

        private Entry(String publicationId, String sessionId, long segmentGeneration,
                      String sourceName, String recoveredName, long sourceLength,
                      String sha256, String treeUri, String displayName,
                      String documentUri, State state) {
            this.publicationId = publicationId;
            this.sessionId = sessionId;
            this.segmentGeneration = segmentGeneration;
            this.sourceName = sourceName;
            this.recoveredName = recoveredName;
            this.sourceLength = sourceLength;
            this.sha256 = sha256;
            this.treeUri = treeUri;
            this.displayName = displayName;
            this.documentUri = documentUri;
            this.state = state;
        }

        public static Entry prepared(String publicationId, String sessionId, long generation,
                                     String sourceName, String recoveredName, long sourceLength,
                                     String sha256, String treeUri, String displayName) {
            Entry value = new Entry(publicationId, sessionId, generation, sourceName,
                    recoveredName, sourceLength, sha256, treeUri, displayName, "", State.PREPARED);
            return isValid(value) ? value : null;
        }

        public Entry withDocument(String uri) {
            Entry value = new Entry(publicationId, sessionId, segmentGeneration, sourceName,
                    recoveredName, sourceLength, sha256, treeUri, displayName,
                    uri == null ? "" : uri, State.DOCUMENT_CREATED);
            return isValid(value) ? value : null;
        }

        public Entry verified() {
            if (documentUri.isEmpty()) return null;
            return new Entry(publicationId, sessionId, segmentGeneration, sourceName,
                    recoveredName, sourceLength, sha256, treeUri, displayName,
                    documentUri, State.VERIFIED);
        }

        /** Changer de dossier ne détruit pas l'intention ; l'ancien document reste intact. */
        public Entry retarget(String newTreeUri) {
            Entry value = new Entry(publicationId, sessionId, segmentGeneration, sourceName,
                    recoveredName, sourceLength, sha256, newTreeUri, displayName, "", State.PREPARED);
            return isValid(value) ? value : null;
        }

        private static boolean isValid(Entry e) {
            return e != null && isHex(e.publicationId, 64)
                    && safeToken(e.sessionId, 128)
                    && e.segmentGeneration >= 0L
                    && safeLeaf(e.sourceName) && safeLeaf(e.recoveredName)
                    && e.sourceLength > 0L && isHex(e.sha256, 64)
                    && contentUri(e.treeUri) && safeLeaf(e.displayName)
                    && e.displayName.toLowerCase(java.util.Locale.ROOT).endsWith(".gpx")
                    && (e.documentUri.isEmpty() || contentUri(e.documentUri))
                    && e.state != null
                    && (e.state == State.PREPARED ? e.documentUri.isEmpty()
                        : !e.documentUri.isEmpty());
        }
    }

    private final ArrayList<Entry> entries;

    private RecoveryPublicationRegistry(ArrayList<Entry> source) { entries = source; }

    public static RecoveryPublicationRegistry empty() { return new RecoveryPublicationRegistry(new ArrayList<>()); }

    /** Null signifie données illisibles : le store doit conserver le fichier brut. */
    public static RecoveryPublicationRegistry parse(String text) {
        if (text == null || text.length() > MAX_TEXT_CHARS || !text.endsWith("\n")
                || text.indexOf('\r') >= 0) return null;
        String[] lines = text.split("\n", -1);
        if (lines.length < 3 || !("publicationFormat=" + FORMAT_VERSION).equals(lines[0])
                || !lines[1].startsWith("count=")) return null;
        final int count;
        try { count = Integer.parseInt(lines[1].substring(6)); }
        catch (NumberFormatException bad) { return null; }
        if (count < 0 || count > MAX_ENTRIES || lines.length != count + 3
                || !lines[count + 2].isEmpty()) return null;

        ArrayList<Entry> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String line = lines[i + 2];
            if (!line.startsWith("entry=")) return null;
            String[] fields = line.substring(6).split("\\|", -1);
            if (fields.length != 11) return null;
            String publicationId = decode(fields[0]);
            String sessionId = decode(fields[1]);
            String sourceName = decode(fields[3]);
            String recoveredName = decode(fields[4]);
            String sha = decode(fields[6]);
            String treeUri = decode(fields[7]);
            String displayName = decode(fields[8]);
            String documentUri = decode(fields[9]);
            if (publicationId == null || sessionId == null || sourceName == null
                    || recoveredName == null || sha == null || treeUri == null
                    || displayName == null || documentUri == null) return null;
            final long generation, length;
            final int stateCode;
            try {
                generation = Long.parseLong(fields[2]);
                length = Long.parseLong(fields[5]);
                stateCode = Integer.parseInt(fields[10]);
            } catch (NumberFormatException bad) { return null; }
            if (stateCode < 0 || stateCode >= State.values().length) return null;
            Entry entry = new Entry(publicationId, sessionId, generation, sourceName,
                    recoveredName, length, sha, treeUri, displayName, documentUri,
                    State.values()[stateCode]);
            if (!Entry.isValid(entry)) return null;
            for (Entry old : result) if (old.publicationId.equals(entry.publicationId)) return null;
            result.add(entry);
        }
        return new RecoveryPublicationRegistry(result);
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

    /** Ajout ou progression monotone. Une identité/digest existante est immuable. */
    public boolean upsert(Entry incoming) {
        if (!Entry.isValid(incoming)) return false;
        for (int i = 0; i < entries.size(); i++) {
            Entry current = entries.get(i);
            if (!current.publicationId.equals(incoming.publicationId)) continue;
            if (!sameRecovery(current, incoming)) return false;
            if (!current.treeUri.equals(incoming.treeUri)) {
                if (incoming.state != State.PREPARED || !incoming.documentUri.isEmpty()) return false;
            } else {
                if (incoming.state.ordinal() < current.state.ordinal()) return false;
                if (!current.documentUri.isEmpty()
                        && !current.documentUri.equals(incoming.documentUri)) return false;
                if (current.state == State.VERIFIED && incoming.state != State.VERIFIED) return false;
            }
            entries.set(i, incoming);
            return true;
        }
        if (entries.size() >= MAX_ENTRIES) return false;
        entries.add(incoming);
        return true;
    }

    /** Retrait seulement après vérification de lecture du document SAF. */
    public boolean removeVerified(String publicationId) {
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.publicationId.equals(publicationId) && entry.state == State.VERIFIED) {
                entries.remove(i);
                return true;
            }
        }
        return false;
    }

    public String toText() {
        StringBuilder out = new StringBuilder(128 + entries.size() * 512);
        out.append("publicationFormat=").append(FORMAT_VERSION).append('\n');
        out.append("count=").append(entries.size()).append('\n');
        for (Entry e : entries) {
            out.append("entry=").append(encode(e.publicationId)).append('|')
                    .append(encode(e.sessionId)).append('|').append(e.segmentGeneration).append('|')
                    .append(encode(e.sourceName)).append('|').append(encode(e.recoveredName)).append('|')
                    .append(e.sourceLength).append('|').append(encode(e.sha256)).append('|')
                    .append(encode(e.treeUri)).append('|').append(encode(e.displayName)).append('|')
                    .append(encode(e.documentUri)).append('|').append(e.state.ordinal()).append('\n');
        }
        return out.toString();
    }

    private static boolean sameRecovery(Entry a, Entry b) {
        return a.publicationId.equals(b.publicationId) && a.sessionId.equals(b.sessionId)
                && a.segmentGeneration == b.segmentGeneration && a.sourceName.equals(b.sourceName)
                && a.recoveredName.equals(b.recoveredName) && a.sourceLength == b.sourceLength
                && a.sha256.equals(b.sha256) && a.displayName.equals(b.displayName);
    }

    private static String encode(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        char[] digits = "0123456789abcdef".toCharArray();
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) { int v = b & 255; out.append(digits[v >>> 4]).append(digits[v & 15]); }
        return out.toString();
    }

    private static String decode(String value) {
        if (value == null || value.length() > MAX_FIELD_HEX || (value.length() & 1) != 0) return null;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length() / 2);
        for (int i = 0; i < value.length(); i += 2) {
            int hi = hex(value.charAt(i)), lo = hex(value.charAt(i + 1));
            if (hi < 0 || lo < 0) return null;
            bytes.write((hi << 4) | lo);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException bad) { return null; }
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }
    private static boolean isHex(String value, int exactLength) {
        if (value == null || value.length() != exactLength) return false;
        for (int i = 0; i < value.length(); i++) if (hex(value.charAt(i)) < 0) return false;
        return true;
    }
    private static boolean contentUri(String value) {
        return value != null && value.startsWith("content://") && value.length() <= 4096
                && !hasControl(value);
    }
    private static boolean safeToken(String value, int max) {
        return value != null && !value.isEmpty() && value.length() <= max && !hasControl(value);
    }
    private static boolean safeLeaf(String value) {
        if (!safeToken(value, 512) || value.equals(".") || value.equals("..")
                || value.contains("/") || value.contains("\\")) return false;
        return true;
    }
    private static boolean hasControl(String value) {
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 0x20) return true;
        return false;
    }
}
