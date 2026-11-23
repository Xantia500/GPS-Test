package com.example.gpstest;

/**
 * Politique centrale de cadence d'acquisition GNSS.
 *
 * La cadence demandée au fournisseur n'est PAS une garantie de callback à
 * intervalle fixe : Android, le chipset, l'environnement radio et la gestion
 * d'énergie restent maîtres de la fréquence réellement disponible.
 *
 * Les valeurs sont séparées des modes d'échantillonnage GPX : acquérir chaque
 * seconde ne signifie pas nécessairement écrire chaque seconde dans la trace.
 */
public final class GnssAcquisitionConfig {
    public static final long DEFAULT_INTERVAL_MS = 1_000L;
    public static final long MIN_INTERVAL_MS = 1_000L;
    public static final long MAX_INTERVAL_MS = 10_000L;
    private static final long[] SUPPORTED = {1_000L, 2_000L, 5_000L, 10_000L};
    private static final long MIN_STATUS_FRESHNESS_MS = 3_000L;

    private GnssAcquisitionConfig() { }

    /** Ramène toute valeur persistée à l'une des cadences officiellement supportées. */
    public static long normalizeIntervalMs(long requestedMs) {
        if (requestedMs <= MIN_INTERVAL_MS) return MIN_INTERVAL_MS;
        if (requestedMs >= MAX_INTERVAL_MS) return MAX_INTERVAL_MS;
        long best = SUPPORTED[0];
        long bestDistance = Math.abs(requestedMs - best);
        for (long candidate : SUPPORTED) {
            long distance = Math.abs(requestedMs - candidate);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    /**
     * Âge maximal d'un état satellites utilisé pour qualifier un fix.
     * On garde au moins 3 s à 1 Hz, puis on adapte la fenêtre à la cadence
     * demandée : une future option 5/10 s ne doit pas rendre tous les statuts
     * GNSS artificiellement périmés.
     */
    public static long statusFreshnessMs(long intervalMs) {
        long normalized = normalizeIntervalMs(intervalMs);
        return Math.max(MIN_STATUS_FRESHNESS_MS, normalized * 3L);
    }

    public static boolean isSupported(long intervalMs) {
        for (long candidate : SUPPORTED) if (candidate == intervalMs) return true;
        return false;
    }

    public static long[] supportedIntervals() { return SUPPORTED.clone(); }
}
