package com.example.gpstest;

/** Tests purs du registre de transaction SAF des récupérations. */
public final class RecoveryPublicationRegistryTest {
    private static int passed, failed;
    public static void main(String[] args) {
        RecoveryPublicationRegistry registry = RecoveryPublicationRegistry.empty();
        RecoveryPublicationRegistry.Entry prepared = entry("session-A", 2L, "tree-A");
        check(prepared != null && registry.upsert(prepared), "intention PREPARED acceptée");
        check(registry.size() == 1, "une transaction enregistrée");
        RecoveryPublicationRegistry parsed = RecoveryPublicationRegistry.parse(registry.toText());
        check(parsed != null && parsed.size() == 1, "round-trip canonique");

        RecoveryPublicationRegistry.Entry current = parsed.find(prepared.publicationId);
        RecoveryPublicationRegistry.Entry document = current.withDocument("content://provider/document/1");
        check(document != null && parsed.upsert(document), "URI du document persistable");
        check(!parsed.upsert(prepared), "retour à PREPARED refusé après création");
        current = parsed.find(prepared.publicationId);
        RecoveryPublicationRegistry.Entry verified = current.verified();
        check(verified != null && parsed.upsert(verified), "état VERIFIED accepté");
        check(!parsed.upsert(document), "régression depuis VERIFIED refusée");
        check(!parsed.removeVerified("absent"), "une autre transaction ne peut pas être retirée");
        check(parsed.removeVerified(prepared.publicationId), "transaction VERIFIED acquittable");
        check(parsed.isEmpty(), "registre vidé après acquittement");

        RecoveryPublicationRegistry retarget = RecoveryPublicationRegistry.empty();
        RecoveryPublicationRegistry.Entry before = entry("session-B", 3L, "tree-A");
        retarget.upsert(before);
        RecoveryPublicationRegistry.Entry onB = before.retarget("content://provider/tree-B");
        check(onB != null && retarget.upsert(onB), "changement de dossier remet la transaction en PREPARED");
        check(!retarget.upsert(before.withDocument("content://provider/document/old")),
                "l'ancien document ne peut pas réintroduire la destination précédente");

        check(RecoveryPublicationRegistry.parse("publicationFormat=2\ncount=0\n") == null,
                "version inconnue rejetée");
        check(RecoveryPublicationRegistry.parse("publicationFormat=1\ncount=1\nentry=zz\n") == null,
                "hexadécimal incorrect rejeté");
        check(RecoveryPublicationRegistry.parse("publicationFormat=1\ncount=0\nextra\n") == null,
                "ligne supplémentaire rejetée");
        check(RecoveryPublicationRegistry.parse("publicationFormat=1\r\ncount=0\r\n") == null,
                "CRLF non canonique rejeté");
        check(RecoveryPublicationRegistry.parse("publicationFormat=1\ncount=65\n") == null,
                "dépassement de capacité rejeté");
        check(RecoveryPublicationRegistry.parse("publicationFormat=1\ncount=1\nentry=c328\n") == null,
                "UTF-8 mal formé rejeté");

        RecoveryPublicationRegistry capped = RecoveryPublicationRegistry.empty();
        boolean accepted = true;
        for (int i = 0; i < RecoveryPublicationRegistry.MAX_ENTRIES; i++) {
            RecoveryPublicationRegistry.Entry e = entry("capacity-" + i, i, "tree-A");
            accepted &= e != null && capped.upsert(e);
        }
        check(accepted && capped.size() == 64, "registre atteint sa limite sans éviction");
        check(!capped.upsert(entry("capacity-extra", 65L, "tree-A")),
                "65e transaction refusée sans perdre les précédentes");

        System.out.println("RecoveryPublicationRegistryTest : " + passed + " réussis, " + failed + " échoués");
        if (failed > 0) System.exit(1);
    }

    private static RecoveryPublicationRegistry.Entry entry(String session, long generation, String tree) {
        String sourceName = "interrompu-" + session + "-" + generation + ".gpx";
        String recovered = "interrompu-RECUPEREE-" + session + "-" + generation + ".gpx";
        SessionJournal identity = SessionJournal.of(session, true, 1L, 2L,
                true, false, 3L, 0, 3L, 0, sourceName, 100L, generation);
        String id = GpxRecoveryPublisher.publicationId(identity, recovered);
        return RecoveryPublicationRegistry.Entry.prepared(id, session, generation,
                sourceName, recovered, 100L, repeat('c', 64), "content://provider/" + tree,
                "published-" + session + "-" + generation + ".gpx");
    }
    private static String repeat(char c, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) out.append(c);
        return out.toString();
    }
    private static void check(boolean condition, String label) {
        if (condition) passed++; else { failed++; System.out.println("ECHEC : " + label); }
    }
}
