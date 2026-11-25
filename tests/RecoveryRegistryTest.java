package com.example.gpstest;

/** Tests JVM du modèle de registre multi-sessions/multi-segments. */
public final class RecoveryRegistryTest {
    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        section("Sérialisation et restauration d'entrées multiples");
        RecoveryRegistry registry = RecoveryRegistry.empty();
        SessionJournal a1 = journal("session-A", 1L, "A1.gpx", 2_048L, 10L);
        SessionJournal a2 = journal("session-A", 2L, "A2.gpx", 4_096L, 20L);
        SessionJournal b1 = journal("session-B", 1L, "B1.gpx", 8_192L, 30L);
        ok(registry.upsert(a1), "première entrée session A/segment 1 acceptée");
        ok(registry.upsert(a2), "deuxième segment de la même session accepté");
        ok(registry.upsert(b1), "autre session acceptée");
        ok(registry.size() == 3, "trois segments enregistrés");
        RecoveryRegistry restored = RecoveryRegistry.parse(registry.toText());
        ok(restored != null && restored.size() == 3, "round-trip du registre complet");
        ok(contains(restored, "session-A", 1L, "A1.gpx", 2_048L), "entrée A1 restaurée");
        ok(contains(restored, "session-A", 2L, "A2.gpx", 4_096L), "entrée A2 restaurée");
        ok(contains(restored, "session-B", 1L, "B1.gpx", 8_192L), "entrée B1 restaurée");

        section("Monotonie et identité du segment");
        ok(!restored.upsert(journal("session-A", 1L, "A1.gpx", 1_024L, 40L)),
                "un checkpoint A1 régressif est refusé");
        ok(!restored.upsert(journal("session-A", 1L, "autre.gpx", 4_096L, 40L)),
                "un autre nom de fichier pour le même segment est refusé");
        ok(restored.upsert(journal("session-A", 1L, "A1.gpx", 3_072L, 50L)),
                "un checkpoint A1 plus récent est accepté");
        ok(!restored.upsert(journal("session-A", 1L, "A1.gpx", 3_072L, 49L)),
                "un snapshot de même offset mais de date plus ancienne ne régresse pas");
        SessionJournal staleRevision = journal("session-A", 1L, "A1.gpx", 3_072L, 51L);
        staleRevision.revision = 1L;
        ok(!restored.upsert(staleRevision),
                "une révision plus ancienne ne remplace pas le snapshot courant");
        ok(contains(restored, "session-A", 1L, "A1.gpx", 3_072L),
                "le nouveau préfixe A1 remplace l'ancien");
        ok(restored.advanceCheckpoint("session-A", 1L, "A1.gpx", 4_096L, 60L),
                "checkpoint de fermeture retardée avancé par la clé segment");
        ok(!restored.advanceCheckpoint("session-A", 1L, "A1.gpx", 2_048L, 70L),
                "advanceCheckpoint ne fait pas régresser l'offset");
        ok(!restored.advanceCheckpoint("session-A", 3L, "A3.gpx", 1_024L, 70L),
                "un segment absent du registre n'est pas créé par un callback retardé");

        section("Acquittement exact et retrait segmenté");
        SessionJournal stale = journal("session-A", 1L, "A1.gpx", 3_072L, 50L);
        ok(!restored.removeIfUnchanged(stale),
                "un snapshot plus ancien ne retire pas l'entrée actualisée");
        SessionJournal current = find(restored, "session-A", 1L);
        SessionJournal changedFlag = SessionJournal.parse(current.toText());
        changedFlag.noticeShown = !changedFlag.noticeShown;
        ok(!restored.removeIfUnchanged(changedFlag),
                "un indicateur modifié empêche l'acquittement même si l'identité du segment est inchangée");
        ok(current != null && restored.removeIfUnchanged(current),
                "le snapshot intégralement identique peut retirer l'entrée A1");
        ok(!restored.removeSegment("session-A", 1L, "A1.gpx", 4_096L),
                "une deuxième suppression d'A1 est refusée");
        ok(restored.removeSegment("session-A", 2L, "A2.gpx", 4_096L),
                "le segment A2 se retire indépendamment");
        ok(restored.size() == 1 && contains(restored, "session-B", 1L, "B1.gpx", 8_192L),
                "la suppression d'A ne touche pas B");

        section("Validation stricte et limite sans éviction");
        ok(RecoveryRegistry.parse("registryFormat=2\ncount=0\n") == null,
                "une version de registre inconnue est refusée");
        ok(RecoveryRegistry.parse("registryFormat=1\ncount=1\nentry=zz\n") == null,
                "un enregistrement hexadécimal corrompu est refusé");
        ok(RecoveryRegistry.parse("registryFormat=1\ncount=1\nentry=c328\n") == null,
                "un contenu UTF-8 mal formé est refusé strictement");
        ok(RecoveryRegistry.parse("registryFormat=1\ncount=0\n\n") == null,
                "une ligne supplémentaire après le registre est refusée");
        ok(RecoveryRegistry.parse("registryFormat=1\r\ncount=0\r\n") == null,
                "un séparateur CRLF non canonique est refusé");
        ok(RecoveryRegistry.parse("registryFormat=1\ncount=1\nentry=١١\n") == null,
                "les chiffres Unicode ne sont pas acceptés comme hexadécimal ASCII");
        ok(RecoveryRegistry.parse("registryFormat=1\ncount=65\n") == null,
                "un compteur supérieur à la limite est refusé");
        RecoveryRegistry capped = RecoveryRegistry.empty();
        boolean allInserted = true;
        for (int i = 0; i < RecoveryRegistry.MAX_ENTRIES; i++) {
            if (!capped.upsert(journal("cap-" + i, 1L, "cap-" + i + ".gpx", 1_024L, i + 1L)))
                allInserted = false;
        }
        ok(allInserted && capped.size() == RecoveryRegistry.MAX_ENTRIES,
                "la capacité maximale est atteinte sans erreur");
        ok(!capped.upsert(journal("cap-extra", 1L, "extra.gpx", 1_024L, 999L)),
                "une entrée supplémentaire est refusée sans évincer une ancienne trace");
        ok(capped.size() == RecoveryRegistry.MAX_ENTRIES,
                "la limite ne provoque aucune suppression silencieuse");

        System.out.println("\n---------------------------------------------");
        System.out.println("RESULTAT : " + pass + " reussis, " + fail + " echoues");
        if (fail > 0) System.exit(1);
    }

    private static SessionJournal journal(String id, long generation, String file,
                                          long length, long savedAt) {
        SessionJournal journal = SessionJournal.of(id, true, 1L, savedAt,
                true, false, 10L, 0, 10L, 0, file, length, generation);
        journal.revision = Math.max(1L, savedAt);
        return journal;
    }

    private static SessionJournal find(RecoveryRegistry registry, String sessionId, long generation) {
        for (SessionJournal entry : registry.snapshots()) {
            if (sessionId.equals(entry.sessionId) && generation == entry.segmentGeneration) return entry;
        }
        return null;
    }

    private static boolean contains(RecoveryRegistry registry, String sessionId, long generation,
                                    String file, long length) {
        SessionJournal entry = find(registry, sessionId, generation);
        return entry != null && file.equals(entry.file) && entry.safeLengthBytes == length;
    }

    private static void section(String title) { System.out.println("\n=== " + title + " ==="); }
    private static void ok(boolean condition, String label) {
        if (condition) pass++; else { fail++; System.out.println(" ECHEC: " + label); }
    }
}
