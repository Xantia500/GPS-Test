package com.example.gpstest;

/** Vérifie l'algèbre des trois étapes d'écriture GPX, sans Android. */
public final class TrackingCountersTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        TrackingState s = TrackingState.get();
        s.resetTrackCounters();

        s.incrementPointsAccepted();
        s.incrementPointsAccepted();
        s.incrementPointsAccepted();
        s.incrementPointsQueued();
        s.incrementPointsQueued();
        s.incrementPointsFailedBeforeQueue();
        check(s.getPointsAccepted() == 3, "accepted inclut tous les points valides avant mise en file");
        check(s.getPointsQueued() == 2, "queued inclut uniquement les post réussis");
        check(s.getPointsFailedBeforeQueue() == 1, "échec pré-file séparé");
        check(s.getGpxWriteQueueDepth() == 2, "les deux tâches restent en attente");

        s.incrementPointsWritten();
        check(s.getPointsWritten() == 1, "written après écriture réussie");
        check(s.getGpxWriteQueueDepth() == 1, "un succès libère une place de file");
        check(s.getTrackPoints() == 1, "l'ancien compteur affiché reflète les écritures réelles");

        s.incrementPointsFailedAfterQueue();
        check(s.getPointsFailedAfterQueue() == 1, "échec d'écriture après enqueue séparé");
        check(s.getGpxWriteQueueDepth() == 0, "échec d'une tâche en file libère la file");
        check(s.getPointsFailed() == 2, "total des deux types d'échec");
        check(s.getPointsAccepted() == s.getPointsQueued() + s.getPointsFailedBeforeQueue(),
                "invariant accepted = queued + failedBeforeQueue");
        check(s.getPointsQueued() == s.getPointsWritten() + s.getPointsFailedAfterQueue()
                        + s.getGpxWriteQueueDepth(),
                "invariant queued = written + failedAfterQueue + pending");

        int oldEpoch = s.getTrackCounterEpoch();
        int newEpoch = s.resetTrackCountersAndGetEpoch();
        check(newEpoch != oldEpoch, "nouvelle trace incrémente la génération de compteurs");
        s.incrementPointsAccepted(oldEpoch);
        s.incrementPointsQueued(oldEpoch);
        s.incrementPointsWritten(oldEpoch);
        check(s.getPointsAccepted() == 0 && s.getPointsQueued() == 0
                        && s.getPointsWritten() == 0 && s.getGpxWriteQueueDepth() == 0,
                "une tâche retardée d'une ancienne trace ne pollue pas les nouveaux compteurs");
        s.incrementPointsAccepted(newEpoch);
        s.incrementPointsQueued(newEpoch);
        s.incrementPointsWritten(newEpoch);
        check(s.getPointsAccepted() == 1 && s.getPointsQueued() == 1
                        && s.getPointsWritten() == 1 && s.getGpxWriteQueueDepth() == 0,
                "les mutations de la nouvelle génération restent comptées");

        int beforeSessionReset = s.getTrackCounterEpoch();
        s.resetSession(1_800_000_000_000L, 123_000L);
        int afterSessionReset = s.getTrackCounterEpoch();
        check(afterSessionReset != beforeSessionReset,
                "resetSession invalide aussi les callbacks I/O de l'ancienne session");
        s.incrementPointsWritten(beforeSessionReset);
        check(s.getPointsWritten() == 0 && s.getTrackPoints() == 0,
                "une écriture tardive après resetSession ne pollue pas les compteurs");

        System.out.printf("TrackingCountersTest : %d réussis, %d échoués%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    private static void check(boolean condition, String label) {
        if (condition) { passed++; System.out.println("OK   " + label); }
        else { failed++; System.err.println("FAIL " + label); }
    }
}
