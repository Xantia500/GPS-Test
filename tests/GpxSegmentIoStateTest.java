package com.example.gpstest;

/** Tests JVM de la machine d'état de l'ouverture asynchrone d'un segment GPX. */
public final class GpxSegmentIoStateTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        GpxSegmentIoState state = new GpxSegmentIoState();
        check(state.phase() == GpxSegmentIoState.Phase.OPENING,
                "un segment neuf commence en ouverture");
        check(state.isOpening(), "l'ouverture en attente est explicite");
        check(!state.canWrite(), "aucun point ne peut être écrit avant l'en-tête");
        check(state.markReady(), "l'ouverture réussie passe à READY");
        check(state.canWrite(), "le segment READY accepte les écritures");
        check(!state.isOpening(), "READY n'est plus un état d'ouverture");
        check(!state.markReady(), "une ouverture ne peut pas réussir deux fois");
        check(!state.failOpen(), "un segment ouvert ne peut plus devenir un échec d'ouverture");
        check(state.finishClose(), "la fermeture d'un segment READY confirme qu'un flux était ouvert");
        check(state.phase() == GpxSegmentIoState.Phase.CLOSED, "la fermeture mène à CLOSED");
        check(!state.canWrite(), "un segment fermé n'est plus inscriptible");
        check(!state.finishClose(), "une seconde fermeture est idempotente");

        GpxSegmentIoState failedOpen = new GpxSegmentIoState();
        check(failedOpen.failOpen(), "l'échec d'en-tête passe à FAILED");
        check(failedOpen.phase() == GpxSegmentIoState.Phase.FAILED, "FAILED est observable");
        check(!failedOpen.canWrite(), "un segment dont l'en-tête a échoué n'accepte aucun point");
        check(!failedOpen.markReady(), "un segment FAILED ne peut pas redevenir READY");
        check(!failedOpen.finishClose(), "fermer un segment FAILED ne prétend pas avoir un flux valide");
        check(failedOpen.phase() == GpxSegmentIoState.Phase.CLOSED,
                "le segment FAILED est finalement fermé");

        GpxSegmentIoState neverStarted = new GpxSegmentIoState();
        check(!neverStarted.finishClose(),
                "le repli ferme un segment dont l'ouverture n'a pas abouti sans prétendre avoir un flux prêt");
        check(neverStarted.phase() == GpxSegmentIoState.Phase.CLOSED,
                "le repli rend l'état terminal");
        check(!neverStarted.markReady(), "une ouverture tardive ne peut pas ressusciter un segment fermé");

        System.out.printf("GpxSegmentIoStateTest : %d réussis, %d échoués%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
            System.out.println("OK   " + message);
        } else {
            failed++;
            System.err.println("FAIL " + message);
        }
    }
}
