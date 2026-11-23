package com.example.gpstest;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Contrôle rapide et défensif de l'en-tête d'un fichier Mapsforge v5.
 *
 * Ce contrôle évite d'installer un HTML d'erreur, une archive renommée en
 * .map ou un format inconnu. Il ne remplace PAS l'ouverture complète par
 * org.mapsforge.map.reader.MapFile lors de l'intégration du moteur.
 */
public final class MapsforgeMapHeader {
    public static final String MAGIC = "mapsforge binary OSM";
    public static final int SUPPORTED_FORMAT_VERSION = 5;
    private static final int MAGIC_LENGTH = 20;
    private static final int MIN_HEADER_SIZE = 42;
    private static final int MAX_HEADER_SIZE = 16 * 1024 * 1024;

    private MapsforgeMapHeader() { }

    public static final class Info {
        public final int headerSize;
        public final int formatVersion;
        public final long declaredFileSize;
        public final long createdAtUtcMs;
        public final double minLatitude;
        public final double minLongitude;
        public final double maxLatitude;
        public final double maxLongitude;
        public final int tileSize;

        private Info(int headerSize, int formatVersion, long declaredFileSize,
                     long createdAtUtcMs, int minLat, int minLon, int maxLat,
                     int maxLon, int tileSize) {
            this.headerSize = headerSize;
            this.formatVersion = formatVersion;
            this.declaredFileSize = declaredFileSize;
            this.createdAtUtcMs = createdAtUtcMs;
            this.minLatitude = minLat / 1_000_000.0;
            this.minLongitude = minLon / 1_000_000.0;
            this.maxLatitude = maxLat / 1_000_000.0;
            this.maxLongitude = maxLon / 1_000_000.0;
            this.tileSize = tileSize;
        }
    }

    public static Info inspect(File file) throws IOException {
        if (file == null || !file.isFile()) {
            throw new IOException("Fichier cartographique absent");
        }
        long actualSize = file.length();
        if (actualSize < MAGIC_LENGTH + MIN_HEADER_SIZE) {
            throw new IOException("Fichier Mapsforge trop court");
        }
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            byte[] magic = new byte[MAGIC_LENGTH];
            in.readFully(magic);
            if (!MAGIC.equals(new String(magic, StandardCharsets.US_ASCII))) {
                throw new IOException("Signature Mapsforge absente");
            }

            int headerSize = in.readInt();
            int formatVersion = in.readInt();
            long declaredSize = in.readLong();
            long createdAt = in.readLong();
            int minLat = in.readInt();
            int minLon = in.readInt();
            int maxLat = in.readInt();
            int maxLon = in.readInt();
            int tileSize = in.readUnsignedShort();

            if (headerSize < MIN_HEADER_SIZE || headerSize > MAX_HEADER_SIZE
                    || MAGIC_LENGTH + (long) headerSize > actualSize) {
                throw new IOException("En-tête Mapsforge incohérent");
            }
            if (formatVersion != SUPPORTED_FORMAT_VERSION) {
                throw new IOException("Version de carte non prise en charge : " + formatVersion
                        + " (version attendue : " + SUPPORTED_FORMAT_VERSION + ")");
            }
            if (declaredSize != actualSize) {
                throw new IOException("Taille déclarée différente de la taille téléchargée");
            }
            if (minLat < -90_000_000 || maxLat > 90_000_000
                    || minLon < -180_000_000 || maxLon > 180_000_000
                    || minLat > maxLat || minLon > maxLon) {
                throw new IOException("Emprise géographique Mapsforge invalide");
            }
            if (tileSize < 64 || tileSize > 1024) {
                throw new IOException("Taille de tuile Mapsforge invalide");
            }
            return new Info(headerSize, formatVersion, declaredSize, createdAt,
                    minLat, minLon, maxLat, maxLon, tileSize);
        }
    }
}
