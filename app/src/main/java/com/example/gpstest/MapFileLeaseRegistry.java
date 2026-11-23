package com.example.gpstest;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Comptage des fichiers .map actuellement ouverts par un ou plusieurs renderers. */
public final class MapFileLeaseRegistry {
    private static final Map<String, Integer> OPEN = new HashMap<>();
    private MapFileLeaseRegistry() { }

    public static synchronized void acquire(File file) throws IOException {
        if (file == null) throw new IOException("Fichier cartographique absent");
        String path = file.getCanonicalPath();
        OPEN.put(path, OPEN.getOrDefault(path, 0) + 1);
    }

    public static synchronized void release(File file) {
        if (file == null) return;
        try {
            String path = file.getCanonicalPath();
            Integer count = OPEN.get(path);
            if (count == null) return;
            if (count <= 1) OPEN.remove(path); else OPEN.put(path, count - 1);
        } catch (IOException ignored) { }
    }

    public static synchronized boolean isInUse(File file) {
        if (file == null) return false;
        try { return OPEN.containsKey(file.getCanonicalPath()); }
        catch (IOException ignored) { return false; }
    }

    public static synchronized int activeCount() { return OPEN.values().stream().mapToInt(Integer::intValue).sum(); }
}
