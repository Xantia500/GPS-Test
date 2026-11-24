package com.example.gpstest;

import java.util.UUID;

/**
 * Snapshot durable de l'intention et de l'état d'une session GNSS/GPX.
 *
 * Ce modèle reste indépendant d'Android pour être testable en JVM. La
 * persistance atomique et la migration des emplacements historiques sont
 * assurées par SessionStore.
 *
 * FORMAT 4 : ajoute une identité de session, une révision, l'état de livraison
 * de la notice et l'offset GPX sûr. Les formats plus anciens restent lisibles.
 */
public final class SessionJournal {

    public static final String FILE_NAME = "session.state";
    public static final int FORMAT_VERSION = 4;
    private static final int MAX_FILE_NAME_LENGTH = 512;
    private static final int MAX_SESSION_ID_LENGTH = 160;

    /** Identité unique de la session logique, indépendante des segments GPX. */
    public String sessionId = "";
    /** Révision monotone des mises à jour committées par SessionStore. */
    public long revision = 1L;
    /** true tant que l'utilisateur n'a pas explicitement terminé la session. */
    public boolean dirty = true;
    /** true si l'acquisition était attendue au dernier snapshot. */
    public boolean expectedAlive = true;
    public long startMs;
    public long savedAtMs;
    public boolean recording;
    public boolean suspended;
    /** Compteur connu au dernier snapshot (pas nécessairement tous durables). */
    public long points;
    public int segment;
    /** Nombre de points confirmés dans le segment courant au snapshot. */
    public long segmentPoints;
    public int policyId;
    /** Nom du fichier local app-specific, jamais un chemin fourni par l'utilisateur. */
    public String file = "";
    /** Longueur du préfixe GPX synchronisé, 0 si aucun checkpoint n'est disponible. */
    public long safeLengthBytes;
    /** La notice correspondant à cette interruption a déjà été présentée. */
    public boolean noticeShown;

    private SessionJournal() { }

    public static String newSessionId() {
        return UUID.randomUUID().toString();
    }

    /** Compatibilité des appels/tests historiques. */
    public static SessionJournal of(boolean dirty, long startMs, long savedAtMs,
                                    boolean recording, boolean suspended,
                                    long points, int segment, long segmentPoints,
                                    int policyId, String file) {
        return of(newSessionId(), dirty, startMs, savedAtMs, recording, suspended,
                points, segment, segmentPoints, policyId, file, 0L);
    }

    /** Compatibilité des appels/tests historiques avec offset GPX. */
    public static SessionJournal of(boolean dirty, long startMs, long savedAtMs,
                                    boolean recording, boolean suspended,
                                    long points, int segment, long segmentPoints,
                                    int policyId, String file, long safeLengthBytes) {
        return of(newSessionId(), dirty, startMs, savedAtMs, recording, suspended,
                points, segment, segmentPoints, policyId, file, safeLengthBytes);
    }

    /** Construit un snapshot explicite pour une session déjà identifiée. */
    public static SessionJournal of(String sessionId, boolean dirty,
                                    long startMs, long savedAtMs,
                                    boolean recording, boolean suspended,
                                    long points, int segment, long segmentPoints,
                                    int policyId, String file, long safeLengthBytes) {
        SessionJournal j = new SessionJournal();
        j.sessionId = normalizeSessionId(sessionId);
        if (j.sessionId.isEmpty()) j.sessionId = newSessionId();
        j.revision = 1L;
        j.dirty = dirty;
        j.expectedAlive = !suspended;
        j.startMs = Math.max(0L, startMs);
        j.savedAtMs = Math.max(0L, savedAtMs);
        j.recording = recording;
        j.suspended = suspended;
        j.points = Math.max(0L, points);
        j.segment = Math.max(0, segment);
        j.segmentPoints = Math.max(0L, segmentPoints);
        j.policyId = policyId;
        j.file = normalizeFileName(file);
        j.safeLengthBytes = Math.max(0L, safeLengthBytes);
        return j;
    }

    /** La session reste voulue même lorsque l'acquisition est temporairement suspendue. */
    public boolean isUnexpectedSessionInterruption() {
        return dirty;
    }

    /** Un fichier GPX actif peut avoir besoin d'une réparation après un crash. */
    public boolean isUnexpectedRecordingInterruption() {
        return dirty && expectedAlive && !suspended && recording
                && file != null && !file.trim().isEmpty();
    }

    public String toText() {
        StringBuilder sb = new StringBuilder(384);
        sb.append("format=").append(FORMAT_VERSION).append('\n');
        sb.append("sessionId=").append(sessionId).append('\n');
        sb.append("revision=").append(revision).append('\n');
        sb.append("start=").append(startMs).append('\n');
        sb.append("recording=").append(recording).append('\n');
        sb.append("points=").append(points).append('\n');
        sb.append("file=").append(file).append('\n');
        sb.append("segment=").append(segment).append('\n');
        sb.append("segmentPoints=").append(segmentPoints).append('\n');
        sb.append("policy=").append(policyId).append('\n');
        sb.append("suspended=").append(suspended).append('\n');
        sb.append("savedAt=").append(savedAtMs).append('\n');
        sb.append("dirty=").append(dirty).append('\n');
        sb.append("expectedAlive=").append(expectedAlive).append('\n');
        sb.append("safeLength=").append(safeLengthBytes).append('\n');
        sb.append("noticeShown=").append(noticeShown).append('\n');
        return sb.toString();
    }

    /**
     * Analyse tolérante des anciens formats. Un format futur inconnu est
     * refusé plutôt que réécrit avec des valeurs par défaut potentiellement
     * destructrices ; SessionStore conserve alors le fichier original.
     */
    public static SessionJournal parse(String text) {
        if (text == null || text.trim().isEmpty()) return null;

        SessionJournal j = new SessionJournal();
        boolean sawAnyKey = false;
        int format = 1;
        boolean invalidFormat = false;
        boolean sawSessionId = false;
        boolean invalidSessionId = false;
        boolean sawDirty = false;
        boolean invalidCriticalBoolean = false;
        boolean sawExpectedAlive = false;

        for (String raw : text.split("\\n")) {
            String line = raw.trim();
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim();
            String val = line.substring(eq + 1).trim();
            sawAnyKey = true;
            try {
                switch (key) {
                    case "format":
                        try { format = Integer.parseInt(val); }
                        catch (NumberFormatException badFormat) { invalidFormat = true; }
                        break;
                    case "sessionId":
                        j.sessionId = normalizeSessionId(val);
                        sawSessionId = !j.sessionId.isEmpty();
                        invalidSessionId = !sawSessionId;
                        break;
                    case "revision":     j.revision = parseLongSafely(val, 1L); break;
                    case "start":        j.startMs = parseLongSafely(val, 0L); break;
                    case "savedAt":      j.savedAtMs = parseLongSafely(val, 0L); break;
                    case "points":       j.points = parseLongSafely(val, 0L); break;
                    case "segment":      j.segment = parseIntSafely(val, 0); break;
                    case "segmentPoints":j.segmentPoints = parseLongSafely(val, 0L); break;
                    case "policy":       j.policyId = parseIntSafely(val, 0); break;
                    case "safeLength":   j.safeLengthBytes = parseLongSafely(val, 0L); break;
                    case "recording": {
                        Boolean parsed = parseBooleanNullable(val);
                        if (parsed == null) invalidCriticalBoolean = true;
                        else j.recording = parsed;
                        break;
                    }
                    case "suspended": {
                        Boolean parsed = parseBooleanNullable(val);
                        if (parsed == null) invalidCriticalBoolean = true;
                        else j.suspended = parsed;
                        break;
                    }
                    case "dirty": {
                        Boolean parsed = parseBooleanNullable(val);
                        if (parsed == null) invalidCriticalBoolean = true;
                        else { j.dirty = parsed; sawDirty = true; }
                        break;
                    }
                    case "expectedAlive": {
                        Boolean parsed = parseBooleanNullable(val);
                        if (parsed == null) invalidCriticalBoolean = true;
                        else { j.expectedAlive = parsed; sawExpectedAlive = true; }
                        break;
                    }
                    case "noticeShown": {
                        Boolean parsed = parseBooleanNullable(val);
                        if (parsed == null) invalidCriticalBoolean = true;
                        else j.noticeShown = parsed;
                        break;
                    }
                    case "file":         j.file = normalizeFileName(val); break;
                    default:             /* clés futures/anciennes ignorées */ break;
                }
            } catch (NumberFormatException ignored) {
                // Une valeur isolée abîmée ne doit pas empêcher la lecture du reste.
            }
        }
        if (!sawAnyKey || invalidFormat || invalidSessionId || invalidCriticalBoolean
                || !sawDirty || format < 1 || format > FORMAT_VERSION) return null;

        j.startMs = Math.max(0L, j.startMs);
        j.savedAtMs = Math.max(0L, j.savedAtMs);
        j.points = Math.max(0L, j.points);
        j.segment = Math.max(0, j.segment);
        j.segmentPoints = Math.max(0L, j.segmentPoints);
        j.safeLengthBytes = Math.max(0L, j.safeLengthBytes);
        j.revision = Math.max(1L, j.revision);
        j.file = normalizeFileName(j.file);

        // Les formats historiques ne contenaient pas d'identifiant. Une
        // identité déterministe permet une suppression conditionnelle sûre
        // pendant la migration, sans inventer un nouvel identifiant à chaque read.
        if (!sawSessionId) {
            j.sessionId = legacySessionId(j.startMs, j.savedAtMs, j.file);
        }
        // Le champ absent dans les anciens journaux est déduit de suspended.
        if (!sawExpectedAlive) j.expectedAlive = !j.suspended;
        return j;
    }

    /** Résumé honnête : la validité du XML doit encore être vérifiée/réparée. */
    public String interruptionNotice() {
        if (!isUnexpectedSessionInterruption()) return null;
        if (suspended || !expectedAlive) {
            return "Service de suivi interrompu pendant une pause. Aucun nouveau point "
                    + "n'était attendu pendant cette période.";
        }
        if (!recording) {
            return "Suivi GNSS interrompu avant son arrêt normal (aucune trace GPX en cours).";
        }
        if (file == null || file.trim().isEmpty()) return null;
        long n = segmentPoints > 0L ? segmentPoints : points;
        if (safeLengthBytes > 0L) {
            return "Enregistrement GPX interrompu : " + file + " (checkpoint sûr : "
                    + n + " points). La fin du fichier doit être vérifiée.";
        }
        if (n > 0L) {
            return "Enregistrement GPX interrompu : " + file + " (" + n
                    + " points confirmés par le writer ; checkpoint durable non disponible). Fin à vérifier.";
        }
        return "Enregistrement GPX interrompu : " + file
                + ". Le dernier checkpoint durable n'est pas disponible ; fichier à vérifier.";
    }


    private static String normalizeSessionId(String value) {
        if (value == null) return "";
        String v = value.trim();
        if (v.isEmpty() || v.length() > MAX_SESSION_ID_LENGTH
                || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) return "";
        return v;
    }

    private static String normalizeFileName(String value) {
        if (value == null) return "";
        String v = value.trim();
        if (v.length() > MAX_FILE_NAME_LENGTH || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0
                || v.indexOf('/') >= 0 || v.indexOf('\\') >= 0
                || ".".equals(v) || "..".equals(v)) {
            return "";
        }
        for (int i = 0; i < v.length(); i++) {
            if (v.charAt(i) < 0x20) return "";
        }
        return v;
    }

    private static String legacySessionId(long start, long saved, String file) {
        return "legacy:" + start + ":" + saved + ":" + normalizeFileName(file);
    }

    private static Boolean parseBooleanNullable(String value) {
        if ("true".equalsIgnoreCase(value)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(value)) return Boolean.FALSE;
        return null;
    }

    private static long parseLongSafely(String value, long fallback) {
        try { return Long.parseLong(value); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static int parseIntSafely(String value, int fallback) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { return fallback; }
    }
}
