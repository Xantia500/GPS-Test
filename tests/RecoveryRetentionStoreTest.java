package com.example.gpstest;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Tests JVM de la persistance atomique du registre de conservation. */
public final class RecoveryRetentionStoreTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"),
                "gpstest-retention-store-" + System.nanoTime());
        check(root.mkdirs(), "répertoire temporaire créé");
        TestContext context = new TestContext(root);
        RecoveryRetentionStore store = new RecoveryRetentionStore(context);

        RecoveryRetentionRegistry empty = store.read();
        check(empty != null && empty.isEmpty(), "registre absent lu comme vide");
        RecoveryRetentionRegistry.Entry first = entry("first", 1L);
        check(store.addOrVerify(first), "première entrée atomiquement enregistrée");
        RecoveryRetentionRegistry read = store.read();
        check(read.size() == 1 && read.find(first.publicationId).sameRecord(first),
                "entrée restaurée depuis AtomicFile");
        check(store.addOrVerify(first), "réécriture exacte idempotente");
        check(!store.addOrVerify(entry("first", 2L)),
                "une identité existante ne peut pas être remplacée");

        File registryFile = new File(root, "recovery-retention.registry");
        byte[] corrupt = "retentionFormat=99\ncount=0\n".getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(registryFile, false)) {
            out.write(corrupt);
            out.getFD().sync();
        }
        check(!store.addOrVerify(entry("other", 3L)),
                "un registre corrompu bloque toute écriture de remplacement");
        check(java.util.Arrays.equals(corrupt, readBytes(registryFile)),
                "les octets du registre corrompu restent préservés");
        boolean unreadable = false;
        try { store.read(); } catch (java.io.IOException expected) { unreadable = true; }
        check(unreadable, "la corruption est signalée explicitement");

        deleteTree(root);
        System.out.println("RecoveryRetentionStoreTest : " + passed + " réussis, " + failed + " échoués");
        if (failed > 0) System.exit(1);
    }

    private static RecoveryRetentionRegistry.Entry entry(String suffix, long generation) {
        String id = idFor(suffix);
        return RecoveryRetentionRegistry.Entry.published(id, "session-" + suffix, generation,
                "source-" + suffix + ".gpx", "source-" + suffix + "_RECUPEREE.gpx",
                512L, repeat('b', 64), "content://provider/tree",
                "content://provider/document/" + suffix, "published-" + suffix + ".gpx",
                1_700_000_000_000L);
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
    private static byte[] readBytes(File f) throws Exception {
        return java.nio.file.Files.readAllBytes(f.toPath());
    }
    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (file.exists()) file.delete();
    }
    private static void check(boolean condition, String label) {
        if (condition) passed++; else { failed++; System.out.println("ECHEC : " + label); }
    }
    private static final class TestContext extends Context {
        private final File files;
        TestContext(File files) { this.files = files; }
        @Override public File getFilesDir() { return files; }
        @Override public Context getApplicationContext() { return this; }
    }
}
