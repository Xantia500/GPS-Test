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
 * Modèle indépendant d'Android pour le registre durable des GPX à récupérer.
 *
 * Le registre conserve au plus une entrée par couple (sessionId, génération).
 * Plusieurs segments d'une même session peuvent coexister temporairement : la
 * fermeture du précédent peut être encore en attente dans la FIFO GPX lorsque
 * le suivant est réservé, ou son checkpoint final peut arriver en retard.
 * Chaque entrée est un snapshot SessionJournal complet, encodé en hexadécimal
 * afin que ses retours à la ligne ne perturbent pas le format externe.
 */
public final class RecoveryRegistry {
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_ENTRIES = 64;
    public static final int MAX_TEXT_CHARS = 512 * 1024;
    private static final int MAX_ENTRY_HEX_CHARS = 32 * 1024;

    private final ArrayList<SessionJournal> entries;

    private RecoveryRegistry(ArrayList<SessionJournal> source) {
        this.entries = source;
    }

    public static RecoveryRegistry empty() {
        return new RecoveryRegistry(new ArrayList<>());
    }

    /** Null signifie registre illisible : l'appelant doit préserver le fichier. */
    public static RecoveryRegistry parse(String text) {
        if (text == null || text.length() > MAX_TEXT_CHARS) return null;
        // Le format interne est canonique : LF uniquement, une seule ligne
        // terminale vide et aucun contenu caché après le dernier enregistrement.
        if (!text.endsWith("\n") || text.indexOf('\r') >= 0) return null;
        String[] lines = text.split("\\n", -1);
        if (lines.length < 3 || !"registryFormat=1".equals(lines[0])) return null;
        if (!lines[1].startsWith("count=")) return null;
        int expected;
        try {
            expected = Integer.parseInt(lines[1].substring("count=".length()));
        } catch (NumberFormatException badCount) {
            return null;
        }
        if (expected < 0 || expected > MAX_ENTRIES || lines.length != expected + 3
                || !lines[expected + 2].isEmpty()) return null;

        ArrayList<SessionJournal> parsed = new ArrayList<>(expected);
        for (int i = 0; i < expected; i++) {
            String line = lines[i + 2];
            if (!line.startsWith("entry=")) return null;
            String encoded = line.substring("entry=".length());
            if (encoded.isEmpty() || encoded.length() > MAX_ENTRY_HEX_CHARS
                    || (encoded.length() & 1) != 0) return null;
            String journalText = decodeHex(encoded);
            if (journalText == null) return null;
            SessionJournal journal = SessionJournal.parse(journalText);
            if (!isEligible(journal)) return null;
            for (SessionJournal old : parsed) {
                if (sameSegment(old, journal)) return null;
            }
            parsed.add(copy(journal));
        }
        return new RecoveryRegistry(parsed);
    }

    public int size() { return entries.size(); }

    public boolean isEmpty() { return entries.isEmpty(); }

    /** Renvoie des copies pour éviter qu'un appelant modifie le snapshot conservé. */
    public List<SessionJournal> snapshots() {
        ArrayList<SessionJournal> result = new ArrayList<>(entries.size());
        for (SessionJournal entry : entries) result.add(copy(entry));
        return Collections.unmodifiableList(result);
    }

    /**
     * Ajoute ou actualise la dernière génération/longueur durable d'une session.
     * Une génération ou un offset plus ancien ne peut jamais faire régresser
     * l'entrée. La limite est stricte : aucune récupération n'est évincée.
     */
    public boolean upsert(SessionJournal incoming) {
        if (!isEligible(incoming)) return false;
        for (int i = 0; i < entries.size(); i++) {
            SessionJournal current = entries.get(i);
            if (!sameSegment(current, incoming)) continue;
            if (!current.file.equals(incoming.file)
                    || incoming.safeLengthBytes < current.safeLengthBytes
                    || incoming.revision < current.revision
                    || incoming.savedAtMs < current.savedAtMs) return false;
            entries.set(i, copy(incoming));
            return true;
        }
        if (entries.size() >= MAX_ENTRIES) return false;
        entries.add(copy(incoming));
        return true;
    }

    /**
     * Avance le checkpoint d'un segment déjà enregistré, même si le journal
     * courant de SessionStore est passé à une autre génération/session.
     */
    public boolean advanceCheckpoint(String sessionId, long generation, String fileName,
                                     long safeLengthBytes, long savedAtMs) {
        if (sessionId == null || sessionId.isEmpty() || generation < 0L
                || fileName == null || fileName.isEmpty() || safeLengthBytes <= 0L) return false;
        for (int i = 0; i < entries.size(); i++) {
            SessionJournal current = entries.get(i);
            if (!current.sessionId.equals(sessionId)
                    || current.segmentGeneration != generation
                    || !current.file.equals(fileName)) continue;
            if (safeLengthBytes < current.safeLengthBytes) return false;
            SessionJournal updated = copy(current);
            updated.safeLengthBytes = safeLengthBytes;
            updated.savedAtMs = Math.max(updated.savedAtMs, savedAtMs);
            updated.revision = updated.revision == Long.MAX_VALUE
                    ? Long.MAX_VALUE : updated.revision + 1L;
            entries.set(i, updated);
            return true;
        }
        return false;
    }

    /** Retire un segment fermé uniquement si son dernier offset durable correspond. */
    public boolean removeSegment(String sessionId, long generation, String fileName,
                                 long safeLengthBytes) {
        if (sessionId == null || fileName == null) return false;
        for (int i = 0; i < entries.size(); i++) {
            SessionJournal entry = entries.get(i);
            if (sessionId.equals(entry.sessionId)
                    && generation == entry.segmentGeneration
                    && fileName.equals(entry.file)
                    && safeLengthBytes == entry.safeLengthBytes) {
                entries.remove(i);
                return true;
            }
        }
        return false;
    }

    /** Retire une entrée uniquement si le snapshot lu avant récupération est exact. */
    public boolean removeIfUnchanged(SessionJournal expected) {
        if (expected == null) return false;
        for (int i = 0; i < entries.size(); i++) {
            if (sameSnapshot(entries.get(i), expected)) {
                entries.remove(i);
                return true;
            }
        }
        return false;
    }

    /** Retrait réservé à la fin explicite ou à l'ouverture d'un nouveau segment fermé proprement. */
    public boolean removeSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return false;
        for (int i = 0; i < entries.size(); i++) {
            if (sessionId.equals(entries.get(i).sessionId)) {
                entries.remove(i);
                return true;
            }
        }
        return false;
    }

    public String toText() {
        StringBuilder text = new StringBuilder(64 + entries.size() * 1024);
        text.append("registryFormat=").append(FORMAT_VERSION).append('\n');
        text.append("count=").append(entries.size()).append('\n');
        for (SessionJournal entry : entries) {
            text.append("entry=").append(encodeHex(entry.toText())).append('\n');
        }
        return text.toString();
    }

    public static boolean isEligible(SessionJournal journal) {
        return journal != null && journal.dirty && journal.expectedAlive
                && !journal.suspended && journal.recording
                && journal.file != null && !journal.file.isEmpty()
                && journal.safeLengthBytes > 0L
                && journal.segmentGeneration >= 0L
                && journal.sessionId != null && !journal.sessionId.isEmpty();
    }

    public static boolean sameSnapshot(SessionJournal a, SessionJournal b) {
        // Comparer la sérialisation canonique inclut également les indicateurs
        // dirty/recording/suspended/noticeShown et les compteurs. Un traitement
        // retardé ne doit jamais acquitter un journal dont un seul champ a
        // changé sans que son identité de segment ait changé.
        return a != null && b != null && a.toText().equals(b.toText());
    }

    private static boolean sameSegment(SessionJournal a, SessionJournal b) {
        return a != null && b != null && a.sessionId.equals(b.sessionId)
                && a.segmentGeneration == b.segmentGeneration;
    }

    private static SessionJournal copy(SessionJournal source) {
        return SessionJournal.parse(source.toText());
    }

    private static String encodeHex(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        char[] digits = "0123456789abcdef".toCharArray();
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int v = b & 0xff;
            out.append(digits[v >>> 4]).append(digits[v & 0x0f]);
        }
        return out.toString();
    }

    private static String decodeHex(String value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length() / 2);
        for (int i = 0; i < value.length(); i += 2) {
            int hi = asciiHex(value.charAt(i));
            int lo = asciiHex(value.charAt(i + 1));
            if (hi < 0 || lo < 0) return null;
            out.write((hi << 4) | lo);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(out.toByteArray())).toString();
        } catch (CharacterCodingException malformedUtf8) {
            return null;
        }
    }

    private static int asciiHex(char value) {
        if (value >= '0' && value <= '9') return value - '0';
        if (value >= 'a' && value <= 'f') return value - 'a' + 10;
        if (value >= 'A' && value <= 'F') return value - 'A' + 10;
        return -1;
    }
}
