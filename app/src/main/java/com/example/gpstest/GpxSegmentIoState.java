package com.example.gpstest;

/**
 * Petit état synchronisé du cycle d'ouverture/fermeture d'un segment GPX.
 *
 * L'objet est volontairement indépendant d'Android : il rend explicites les
 * transitions que le worker ioFile doit respecter et permet de les tester sur
 * la JVM. Les opérations disque restent dans TrackingService/ioFile.
 */
final class GpxSegmentIoState {
    enum Phase { OPENING, READY, FAILED, CLOSED }

    private Phase phase = Phase.OPENING;

    synchronized boolean isOpening() {
        return phase == Phase.OPENING;
    }

    synchronized boolean markReady() {
        if (phase != Phase.OPENING) return false;
        phase = Phase.READY;
        return true;
    }

    synchronized boolean failOpen() {
        if (phase != Phase.OPENING) return false;
        phase = Phase.FAILED;
        return true;
    }

    synchronized boolean canWrite() {
        return phase == Phase.READY;
    }

    /** Termine le cycle ; renvoie true si un writer était effectivement ouvert. */
    synchronized boolean finishClose() {
        if (phase == Phase.CLOSED) return false;
        boolean wasReady = phase == Phase.READY;
        phase = Phase.CLOSED;
        return wasReady;
    }

    synchronized Phase phase() {
        return phase;
    }
}
