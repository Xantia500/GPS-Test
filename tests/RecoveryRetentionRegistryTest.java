package com.example.gpstest;

/** Tests JVM du registre durable des copies récupérées conservées après publication SAF. */
public final class RecoveryRetentionRegistryTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        RecoveryRetentionRegistry registry = RecoveryRetentionRegistry.empty();
        RecoveryRetentionRegistry.Entry first = entry("a", 1L, 1_700_000_000_000L);
        check(first != null && registry.addOrVerify(first), "première copie publiée ajoutée");
        check(registry.size() == 1, "une copie suivie");
        check(registry.addOrVerify(first), "ajout strictement identique idempotent");

        RecoveryRetentionRegistry parsed = RecoveryRetentionRegistry.parse(registry.toText());
        check(parsed != null && parsed.size() == 1, "round-trip du registre");
        RecoveryRetentionRegistry.Entry restored = parsed.find(first.publicationId);
        check(restored != null && restored.sameRecord(first), "tous les champs persistés sont restitués");
        check(!parsed.addOrVerify(entry("a", 1L, 1_700_000_000_001L)),
                "une nouvelle tentative ne peut pas rajeunir la date de publication");
        check(!parsed.addOrVerify(entry("a", 2L, 1_700_000_000_000L)),
                "une identité de segment ne peut pas être remplacée");
        check(!parsed.removeIfUnchanged(entry("a", 1L, 1_700_000_000_001L)),
                "un snapshot différent ne peut pas retirer l'entrée");
        check(parsed.removeIfUnchanged(first), "retrait conditionnel exact disponible pour une future purge confirmée");
        check(parsed.isEmpty(), "entrée retirée uniquement avec son snapshot exact");

        check(RecoveryRetentionRegistry.parse("retentionFormat=2\ncount=0\n") == null,
                "version inconnue rejetée");
        check(RecoveryRetentionRegistry.parse("retentionFormat=1\ncount=0\nextra\n") == null,
                "ligne supplémentaire rejetée");
        check(RecoveryRetentionRegistry.parse("retentionFormat=1\r\ncount=0\r\n") == null,
                "CRLF non canonique rejeté");
        check(RecoveryRetentionRegistry.parse("retentionFormat=1\ncount=129\n") == null,
                "dépassement de capacité rejeté");
        check(RecoveryRetentionRegistry.parse("retentionFormat=1\ncount=1\nentry=c328\n") == null,
                "UTF-8 mal formé rejeté");

        RecoveryRetentionRegistry capped = RecoveryRetentionRegistry.empty();
        boolean allAccepted = true;
        for (int i = 0; i < RecoveryRetentionRegistry.MAX_ENTRIES; i++) {
            allAccepted &= capped.addOrVerify(entry("cap" + i, i, 1_700_000_000_000L + i));
        }
        check(allAccepted && capped.size() == 128, "capacité de 128 entrées atteinte sans éviction");
        check(!capped.addOrVerify(entry("overflow", 129L, 1_700_000_000_999L)),
                "129e entrée refusée sans perdre les précédentes");

        check(RecoveryRetentionRegistry.Entry.published(repeat('e', 64), "session", 1,
                "interrupted.gpx", "interrupted.gpx", 10, repeat('a', 64),
                "content://provider/tree", "content://provider/doc", "recovered.gpx", 1_700_000_000_000L) == null,
                "source et copie locale doivent être distinctes");
        check(RecoveryRetentionRegistry.Entry.published(repeat('e', 64), "session", 1,
                "interrupted.gpx", "recovered_RECUPEREE.gpx", 0, repeat('a', 64),
                "content://provider/tree", "content://provider/doc", "recovered.gpx", 1_700_000_000_000L) == null,
                "longueur nulle refusée");
        check(RecoveryRetentionRegistry.Entry.published(repeat('e', 64), "session", 1,
                "interrupted.gpx", "recovered_RECUPEREE.gpx", 10, repeat('a', 64),
                "/storage/tree", "content://provider/doc", "recovered.gpx", 1_700_000_000_000L) == null,
                "URI non SAF refusée");
        check(RecoveryRetentionRegistry.Entry.published(repeat('e', 64), "session", 1,
                "interrupted.gpx", "recovered.gpx", 10, repeat('a', 64),
                "content://provider/tree", "content://provider/doc", "recovered.gpx", 1_700_000_000_000L) == null,
                "copie sans marqueur RECUPEREE refusée");

        System.out.println("RecoveryRetentionRegistryTest : " + passed + " réussis, " + failed + " échoués");
        if (failed > 0) System.exit(1);
    }

    private static RecoveryRetentionRegistry.Entry entry(String suffix, long generation, long publishedAt) {
        String session = "session-" + suffix;
        String source = "interrupted-" + suffix + ".gpx";
        String recovered = "interrupted-" + suffix + "_RECUPEREE.gpx";
        return RecoveryRetentionRegistry.Entry.published(idFor(suffix), session, generation,
                source, recovered, 100L + generation, repeat('b', 64),
                "content://provider/tree", "content://provider/document/" + suffix,
                "published-" + suffix + ".gpx", publishedAt);
    }
    private static String idFor(String value) {
        String hex = Integer.toUnsignedString(value.hashCode(), 16);
        StringBuilder out = new StringBuilder(64);
        for (int i = hex.length(); i < 8; i++) out.append('0');
        out.append(hex);
        while (out.length() < 64) out.append('e');
        return out.toString();
    }
    private static String repeat(char c, int n) {
        StringBuilder b = new StringBuilder(n);
        for (int i = 0; i < n; i++) b.append(c);
        return b.toString();
    }
    private static void check(boolean condition, String label) {
        if (condition) passed++; else { failed++; System.out.println("ECHEC : " + label); }
    }
}
