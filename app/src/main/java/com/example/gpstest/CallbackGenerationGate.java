package com.example.gpstest;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Petit garde de génération pour les callbacks asynchrones Android.
 * Un callback capture snapshot() à son inscription ; invalidate() rend
 * immédiatement tous les callbacks de cette génération inacceptables.
 */
final class CallbackGenerationGate {
    private final AtomicLong generation = new AtomicLong();

    long snapshot() {
        return generation.get();
    }

    long invalidate() {
        return generation.incrementAndGet();
    }

    boolean isCurrent(long capturedGeneration) {
        return generation.get() == capturedGeneration;
    }
}
