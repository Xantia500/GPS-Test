package com.example.gpstest;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** Rapprochement pur des frontières .poly avec le catalogue de cartes .map. */
public final class MapsforgeRegionMatcher {
    public static final int STATE_AVAILABLE = 0;
    public static final int STATE_INSTALLED = 1;
    public static final int STATE_UPDATE = 2;

    private MapsforgeRegionMatcher() { }

    public static MapsforgeCatalogParser.Entry findTarget(
            MapsforgeBoundaryCatalogParser.Entry boundary,
            List<MapsforgeCatalogParser.Entry> mapEntries) {
        if (boundary == null || mapEntries == null || boundary.directory) return null;
        String base = boundary.name.replaceFirst("(?i)\\.poly$", "");
        for (MapsforgeCatalogParser.Entry entry : mapEntries) {
            if (entry.directory && entry.name.equals(base)) return entry;
            if (!entry.directory && entry.name.equalsIgnoreCase(base + ".map")) return entry;
        }
        return null;
    }

    public static int state(boolean installed, String installedSourceVersion, String remoteVersion) {
        if (!installed) return STATE_AVAILABLE;
        String local = installedSourceVersion == null ? "" : installedSourceVersion;
        if (remoteVersion != null && !remoteVersion.isEmpty()
                && (local.isEmpty() || remoteVersion.compareTo(local) > 0)) return STATE_UPDATE;
        return STATE_INSTALLED;
    }

    public static String directoryRegionId(String relativePath) {
        return "dir-" + hashPrefix(relativePath == null ? "" : relativePath);
    }

    private static String hashPrefix(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(16);
            for (int i = 0; i < 8; i++) out.append(String.format(Locale.ROOT, "%02x", bytes[i] & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }
}
