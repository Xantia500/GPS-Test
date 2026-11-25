package com.example.gpstest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Tests de pannes et de reprise de la transaction SAF (fournisseur simulé). */
public final class GpxRecoveryPublisherTest {
    private static int passed, failed;
    private static final String TREE = "content://provider/tree/Download";
    private static final String GPX = "<?xml version=\"1.0\"?><gpx version=\"1.1\" "
            + "xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><trkseg>"
            + "<trkpt lat=\"48\" lon=\"2\"/></trkseg></trk></gpx>";

    public static void main(String[] args) throws Exception {
        publishesAndVerifiesReadBack();
        resumesAfterDocumentCreatedButUriNotCommitted();
        resumesAfterPartialWrite();
        keepsTransactionWhenReadBackDiffers();
        toleratesTemporaryPreReadFailureButRequiresPostWriteVerification();
        refusesToCreateWhenPermissionOrLookupUnavailable();
        handlesDeterministicNameCollisionWithoutOverwritingAmbiguousFiles();
        refusesSingleForeignDocumentWithReservedName();
        reusesIdenticalDocumentWithoutRewriting();
        retargetsAnUnfinishedTransactionToNewChosenFolder();
        System.out.println("GpxRecoveryPublisherTest : " + passed + " réussis, " + failed + " échoués");
        if (failed > 0) System.exit(1);
    }

    private static void publishesAndVerifiesReadBack() throws Exception {
        Fixture f = fixture("one");
        GpxRecoveryPublisher.Result result = f.publish();
        check(result.isSuccess(), "document SAF publié");
        check(f.access.createCount == 1, "un seul document créé");
        check(f.access.bytes(result.documentUri).equals(GPX), "octets SAF identiques à la source locale");
        check(f.store.registry.find(result.publicationId).state == RecoveryPublicationRegistry.State.VERIFIED,
                "état VERIFIED enregistré après relecture");
        check(result.bytes == bytes(GPX).length, "longueur publiée exacte");
    }

    private static void resumesAfterDocumentCreatedButUriNotCommitted() throws Exception {
        Fixture f = fixture("create-crash");
        f.store.failOnWriteNumber = 2; // l'intention PREPARED est commitée ; l'URI créée ne l'est pas
        GpxRecoveryPublisher.Result first = f.publish();
        check(first.status == GpxRecoveryPublisher.Status.REGISTRY_ERROR,
                "échec d'enregistrement URI signalé sans acquittement");
        check(f.access.createCount == 1 && f.access.documentCount() == 1,
                "un document est créé avant la coupure simulée");
        String orphanUri = "content://provider/doc/1";
        GpxRecoveryPublisher.Result retry = f.publish();
        check(retry.status == GpxRecoveryPublisher.Status.AMBIGUOUS_DOCUMENTS,
                "un document homonyme non vérifiable n'est pas adopté après le crash");
        check(f.access.createCount == 1 && f.access.documentCount() == 1
                        && f.access.bytes(orphanUri).isEmpty(),
                "le document orphelin vide n'est ni réécrit ni dupliqué");
        // Après résolution explicite de la collision (suppression manuelle
        // de l'orphelin), le même intent PREPARED peut reprendre.
        f.access.docs.remove(orphanUri);
        GpxRecoveryPublisher.Result resumed = f.publish();
        check(resumed.isSuccess(), "retry reprend après résolution de l'orphelin SAF");
        check(f.access.createCount == 2 && f.access.documentCount() == 1
                        && f.access.bytes(resumed.documentUri).equals(GPX),
                "un seul document final complet subsiste après la reprise");
    }

    private static void resumesAfterPartialWrite() throws Exception {
        Fixture f = fixture("partial-write");
        f.access.failNextWriteAfterBytes = 27;
        GpxRecoveryPublisher.Result first = f.publish();
        check(first.status == GpxRecoveryPublisher.Status.WRITE_FAILED,
                "écriture partielle signalée comme échec");
        check(f.store.registry.find(first.publicationId).state == RecoveryPublicationRegistry.State.DOCUMENT_CREATED,
                "l'état du document reste reprenable");
        GpxRecoveryPublisher.Result retry = f.publish();
        check(retry.isSuccess(), "retry réécrit et vérifie le document complet");
        check(f.access.createCount == 1 && f.access.bytes(retry.documentUri).equals(GPX),
                "la reprise réutilise le même document sans doublon");
    }

    private static void keepsTransactionWhenReadBackDiffers() throws Exception {
        Fixture f = fixture("readback-mismatch");
        f.access.corruptReadBack = true;
        GpxRecoveryPublisher.Result result = f.publish();
        check(!result.isSuccess(), "lecture SAF corrompue non déclarée comme succès");
        check(f.store.registry.size() == 1, "transaction conservée après mismatch");
        check(f.store.registry.find(result.publicationId).state == RecoveryPublicationRegistry.State.DOCUMENT_CREATED,
                "la transaction n'atteint pas VERIFIED");
    }

    private static void toleratesTemporaryPreReadFailureButRequiresPostWriteVerification() throws Exception {
        Fixture f = fixture("pre-read-temporary");
        f.access.failNextRead = true;
        GpxRecoveryPublisher.Result result = f.publish();
        check(result.isSuccess(), "échec de lecture initiale transitoire compensé par écriture et relecture");
        check(f.access.writeCount == 1 && f.access.bytes(result.documentUri).equals(GPX),
                "le contenu final est toujours vérifié après fermeture du flux");
    }

    private static void refusesToCreateWhenPermissionOrLookupUnavailable() throws Exception {
        Fixture noPermission = fixture("permission");
        noPermission.access.granted = false;
        check(noPermission.publish().status == GpxRecoveryPublisher.Status.NO_PERMISSION,
                "absence de permission refusée");
        check(noPermission.access.createCount == 0, "aucun document créé sans permission");

        Fixture noLookup = fixture("lookup");
        noLookup.access.failLookup = true;
        check(noLookup.publish().status == GpxRecoveryPublisher.Status.DOCUMENT_LOOKUP_FAILED,
                "échec de listing SAF signalé");
        check(noLookup.access.createCount == 0, "pas de création aveugle si le listing échoue");
    }

    private static void handlesDeterministicNameCollisionWithoutOverwritingAmbiguousFiles() throws Exception {
        Fixture f = fixture("ambiguous");
        String id = GpxRecoveryPublisher.publicationId(f.identity, f.source.getName());
        String display = GpxRecoveryPublisher.displayName(f.source.getName(), id);
        f.access.addDocument("content://provider/doc/foreign-1", display, "FOREIGN-1".getBytes(StandardCharsets.UTF_8));
        f.access.addDocument("content://provider/doc/foreign-2", display, "FOREIGN-2".getBytes(StandardCharsets.UTF_8));
        GpxRecoveryPublisher.Result result = f.publish();
        check(result.status == GpxRecoveryPublisher.Status.AMBIGUOUS_DOCUMENTS,
                "plusieurs documents non identiques sont traités comme collision ambiguë");
        check(f.access.createCount == 0, "aucun document supplémentaire créé en cas d'ambiguïté");
        check(f.access.bytes("content://provider/doc/foreign-1").equals("FOREIGN-1"),
                "un document existant n'est pas écrasé");
    }


    private static void refusesSingleForeignDocumentWithReservedName() throws Exception {
        Fixture f = fixture("single-collision");
        String id = GpxRecoveryPublisher.publicationId(f.identity, f.source.getName());
        String display = GpxRecoveryPublisher.displayName(f.source.getName(), id);
        byte[] foreign = "DONNEES UTILISATEUR".getBytes(StandardCharsets.UTF_8);
        f.access.addDocument("content://provider/doc/user-file", display, foreign);
        GpxRecoveryPublisher.Result result = f.publish();
        check(result.status == GpxRecoveryPublisher.Status.AMBIGUOUS_DOCUMENTS,
                "un document homonyme non identique bloque la publication");
        check(f.access.bytes("content://provider/doc/user-file").equals("DONNEES UTILISATEUR"),
                "un unique fichier utilisateur homonyme n'est jamais écrasé");
        check(f.access.writeCount == 0 && f.access.createCount == 0,
                "aucune écriture et aucune création en présence de la collision");
    }

    private static void reusesIdenticalDocumentWithoutRewriting() throws Exception {
        Fixture f = fixture("reuse");
        String id = GpxRecoveryPublisher.publicationId(f.identity, f.source.getName());
        String display = GpxRecoveryPublisher.displayName(f.source.getName(), id);
        f.access.addDocument("content://provider/doc/match", display, bytes(GPX));
        GpxRecoveryPublisher.Result result = f.publish();
        check(result.isSuccess(), "un document déjà identique est reconnu");
        check(f.access.createCount == 0 && f.access.writeCount == 0,
                "le document identique n'est ni recréé ni réécrit");
    }

    private static void retargetsAnUnfinishedTransactionToNewChosenFolder() throws Exception {
        Fixture f = fixture("retarget");
        f.access.failNextWriteAfterBytes = 12;
        GpxRecoveryPublisher.Result first = f.publish();
        check(first.status == GpxRecoveryPublisher.Status.WRITE_FAILED, "première publication incomplète");
        f.access.failNextWriteAfterBytes = -1;
        String newTree = "content://provider/tree/Documents";
        GpxRecoveryPublisher.Result second = GpxRecoveryPublisher.publish(
                f.source, f.identity, newTree, f.access, f.store);
        check(second.isSuccess(), "transaction non terminée redirigée vers le nouveau dossier choisi");
        check(f.access.bytes(second.documentUri).equals(GPX), "destination nouvelle vérifiée");
        check(f.access.documentCount() == 2, "l'ancien partiel reste conservé, le nouveau devient complet");
    }

    private static Fixture fixture(String tag) throws Exception {
        File dir = Files.createTempDirectory("gpstest-publish-" + tag + "-").toFile();
        File source = new File(dir, "trace_RECUPEREE.gpx");
        Files.write(source.toPath(), bytes(GPX));
        SessionJournal identity = SessionJournal.of("session-" + tag, true, 1L, 2L,
                true, false, 3L, 0, 3L, 0, "trace-interrompue.gpx", 100L, 4L);
        return new Fixture(source, identity, new MemoryAccess(), new MemoryStore());
    }

    private static final class Fixture {
        final File source; final SessionJournal identity; final MemoryAccess access; final MemoryStore store;
        Fixture(File source, SessionJournal identity, MemoryAccess access, MemoryStore store) {
            this.source = source; this.identity = identity; this.access = access; this.store = store;
        }
        GpxRecoveryPublisher.Result publish() {
            return GpxRecoveryPublisher.publish(source, identity, TREE, access, store);
        }
    }

    private static final class MemoryStore implements GpxRecoveryPublisher.RegistryStore {
        RecoveryPublicationRegistry registry = RecoveryPublicationRegistry.empty();
        int writeCount;
        int failOnWriteNumber = -1;
        @Override public RecoveryPublicationRegistry read() {
            return RecoveryPublicationRegistry.parse(registry.toText());
        }
        @Override public boolean write(RecoveryPublicationRegistry next) {
            writeCount++;
            if (writeCount == failOnWriteNumber) return false;
            RecoveryPublicationRegistry copy = RecoveryPublicationRegistry.parse(next.toText());
            if (copy == null) return false;
            registry = copy; return true;
        }
    }

    private static final class MemoryAccess implements GpxRecoveryPublisher.SafAccess {
        final Map<String, Doc> docs = new HashMap<>();
        int createCount, writeCount;
        boolean granted = true, failLookup, corruptReadBack, failNextRead;
        int failNextWriteAfterBytes = -1;
        @Override public boolean hasReadWriteAccess(String tree) { return granted; }
        @Override public List<GpxRecoveryPublisher.Document> findByDisplayName(String tree, String name)
                throws IOException {
            if (failLookup) throw new IOException("provider unavailable");
            List<GpxRecoveryPublisher.Document> found = new ArrayList<>();
            for (Map.Entry<String, Doc> item : docs.entrySet()) {
                if (item.getValue().name.equals(name) && item.getValue().tree.equals(tree))
                    found.add(new GpxRecoveryPublisher.Document(item.getKey(), name));
            }
            return found;
        }
        @Override public String createDocument(String tree, String name, String mime) {
            String uri = "content://provider/doc/" + (++createCount);
            docs.put(uri, new Doc(tree, name, new byte[0]));
            return uri;
        }
        @Override public OutputStream openWrite(String uri) throws IOException {
            Doc existing = docs.get(uri);
            if (existing == null) throw new IOException("missing doc");
            writeCount++;
            int failAfter = failNextWriteAfterBytes;
            failNextWriteAfterBytes = -1;
            return new OutputStream() {
                final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                boolean committed;
                int written;
                @Override public void write(int value) throws IOException {
                    if (failAfter >= 0 && written >= failAfter)
                        throw new IOException("simulated interrupted write");
                    buffer.write(value); written++;
                }
                @Override public void write(byte[] b, int off, int len) throws IOException {
                    if (failAfter >= 0 && written + len > failAfter) {
                        int allowed = Math.max(0, failAfter - written);
                        if (allowed > 0) { buffer.write(b, off, allowed); written += allowed; }
                        throw new IOException("simulated interrupted write");
                    }
                    buffer.write(b, off, len); written += len;
                }
                @Override public void close() throws IOException {
                    if (!committed) {
                        committed = true;
                        docs.put(uri, new Doc(existing.tree, existing.name, buffer.toByteArray()));
                    }
                }
            };
        }
        @Override public InputStream openRead(String uri) throws IOException {
            if (failNextRead) { failNextRead = false; throw new IOException("temporary read failure"); }
            Doc doc = docs.get(uri);
            if (doc == null) throw new IOException("missing doc");
            byte[] value = doc.data;
            if (corruptReadBack && value.length > 0) {
                value = value.clone(); value[value.length - 1] ^= 1;
            }
            return new ByteArrayInputStream(value);
        }
        void addDocument(String uri, String name, byte[] data) { docs.put(uri, new Doc(TREE, name, data)); }
        int documentCount() { return docs.size(); }
        String bytes(String uri) { Doc d = docs.get(uri); return d == null ? "" : new String(d.data, StandardCharsets.UTF_8); }
    }
    private static final class Doc {
        final String tree; final String name; final byte[] data;
        Doc(String tree, String name, byte[] data) { this.tree = tree; this.name = name; this.data = data.clone(); }
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean condition, String label) {
        if (condition) passed++; else { failed++; System.out.println("ECHEC : " + label); }
    }
}
