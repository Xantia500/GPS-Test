package com.example.gpstest;

/**
 * État pure-Java du dernier préfixe GPX confirmé par flush + FileDescriptor.sync().
 *
 * Un checkpoint appartient à un seul nom de fichier. Il est append-only :
 * une longueur plus petite, un nom différent ou une longueur nulle ne peuvent
 * jamais remplacer un préfixe déjà confirmé.
 */
final class GpxCheckpoint {

    static final class Snapshot {
        final String fileName;
        final long safeLengthBytes;

        Snapshot(String fileName, long safeLengthBytes) {
            this.fileName = fileName;
            this.safeLengthBytes = safeLengthBytes;
        }
    }

    private String fileName = "";
    private long safeLengthBytes;

    /** Associe l'état à un nouveau fichier. Le checkpoint repart à zéro. */
    synchronized boolean register(String value) {
        String normalized = normalizeFileName(value);
        if (normalized.isEmpty()) return false;
        fileName = normalized;
        safeLengthBytes = 0L;
        return true;
    }

    /**
     * Avance le préfixe uniquement après le succès effectif du sync système.
     * Une même longueur est acceptée (sync sans nouveaux octets) ; une
     * régression est refusée, car les fichiers GPX sont écrits en append-only.
     */
    synchronized boolean advance(String expectedFileName, long byteLength) {
        String normalized = normalizeFileName(expectedFileName);
        if (normalized.isEmpty() || !fileName.equals(normalized)
                || byteLength <= 0L || byteLength < safeLengthBytes) {
            return false;
        }
        safeLengthBytes = byteLength;
        return true;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(fileName, safeLengthBytes);
    }

    private static String normalizeFileName(String value) {
        if (value == null) return "";
        String name = value.trim();
        if (name.isEmpty() || name.length() > 512 || ".".equals(name) || "..".equals(name)
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0) return "";
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) < 0x20) return "";
        }
        return name;
    }
}
