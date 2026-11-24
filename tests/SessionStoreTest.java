package com.example.gpstest;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

/** Tests JVM de la persistance atomique et des gardes d'identité du journal. */
public final class SessionStoreTest {
    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("gpstest-session-store-").toFile();
        TestContext context = new TestContext(new File(root, "internal"),
                new File(root, "external"));
        context.getFilesDir().mkdirs();
        context.getExternalFilesDir(null).mkdirs();

        section("Write/read atomique en stockage interne privé");
        String id = UUID.randomUUID().toString();
        SessionJournal journal = SessionJournal.of(id, true, 100L, 200L,
                true, false, 55L, 1, 55L, 0, "track.gpx", 4_096L);
        ok(SessionStore.write(context, journal), "écriture atomique réussie");
        File internal = new File(context.getFilesDir(), SessionJournal.FILE_NAME);
        ok(internal.isFile(), "journal écrit dans getFilesDir");
        ok(!new File(context.getExternalFilesDir(null), SessionJournal.FILE_NAME).exists(),
                "aucun nouveau journal écrit dans le stockage externe");
        SessionJournal read = SessionStore.read(context);
        ok(read != null && id.equals(read.sessionId), "lecture retrouve la bonne session");
        ok(read.safeLengthBytes == 4_096L, "checkpoint relu");
        long firstRevision = read.revision;
        SessionJournal refreshed = SessionJournal.of(id, true, 100L, 201L,
                true, false, 56L, 1, 56L, 0, "track.gpx", 0L);
        ok(SessionStore.write(context, refreshed), "snapshot suivant écrit pour la même session");
        SessionJournal afterRefresh = SessionStore.read(context);
        ok(afterRefresh.revision > firstRevision, "révision incrémentée à chaque snapshot du même identifiant");
        ok(afterRefresh.safeLengthBytes == 4_096L,
                "un snapshot plus ancien ne fait pas régresser le checkpoint du même fichier");

        section("Checkpoint lié au bon segment et protégé des snapshots retardés");
        SessionStore.clear(context);
        SessionJournal generationOne = SessionJournal.of("generation-session", true,
                10L, 20L, true, false, 1L, 0, 0L, 0, "", 0L, 1L);
        ok(SessionStore.write(context, generationOne), "journal initial de génération 1 écrit");
        ok(SessionStore.registerSegment(context, "generation-session", 1L, "segment-1.gpx"),
                "ouverture du segment 1 associée au journal");
        ok(SessionStore.updateCheckpoint(context, "generation-session", 1L,
                "segment-1.gpx", 8_192L), "checkpoint synchronisé du segment 1 écrit");
        ok(!SessionStore.updateCheckpoint(context, "generation-session", 1L,
                "segment-1.gpx", 4_096L), "un checkpoint régressif est refusé");
        ok(!SessionStore.updateCheckpoint(context, "generation-session", 1L,
                "autre-fichier.gpx", 16_384L), "un checkpoint d'un autre fichier est refusé");
        SessionJournal currentCheckpoint = SessionStore.read(context);
        ok(currentCheckpoint.safeLengthBytes == 8_192L,
                "le dernier offset durable reste monotone");
        SessionJournal openingSnapshot = SessionJournal.of("generation-session", true,
                10L, 30L, true, false, 2L, 0, 0L, 0, "", 0L, 1L);
        ok(SessionStore.write(context, openingSnapshot),
                "snapshot OPENING du même segment accepté sans écraser le fichier déjà réservé");
        SessionJournal afterOpeningSnapshot = SessionStore.read(context);
        ok("segment-1.gpx".equals(afterOpeningSnapshot.file)
                        && afterOpeningSnapshot.safeLengthBytes == 8_192L,
                "le nom et le checkpoint survivent au snapshot OPENING retardé");
        SessionJournal generationTwo = SessionJournal.of("generation-session", true,
                10L, 40L, true, false, 0L, 1, 0L, 0, "", 0L, 2L);
        ok(SessionStore.write(context, generationTwo), "nouvelle génération de segment acceptée");
        SessionJournal afterGenerationTwo = SessionStore.read(context);
        ok(afterGenerationTwo.segmentGeneration == 2L && afterGenerationTwo.file.isEmpty()
                        && afterGenerationTwo.safeLengthBytes == 0L,
                "le nouveau segment repart sans hériter du fichier ni de l'offset précédent");
        ok(!SessionStore.updateCheckpoint(context, "generation-session", 1L,
                "segment-1.gpx", 16_384L), "un checkpoint retardé du segment 1 est rejeté");
        ok(SessionStore.registerSegment(context, "generation-session", 2L, "segment-2.gpx"),
                "le fichier du segment 2 est enregistré");
        ok(SessionStore.updateCheckpoint(context, "generation-session", 2L,
                "segment-2.gpx", 1_024L), "le segment 2 peut enregistrer son propre checkpoint");
        SessionJournal staleGenerationOne = SessionJournal.of("generation-session", true,
                10L, 50L, true, false, 1L, 0, 1L, 0, "segment-1.gpx", 16_384L, 1L);
        ok(!SessionStore.write(context, staleGenerationOne),
                "un snapshot retardé d'une génération ancienne ne peut pas remplacer la nouvelle");
        ok(SessionStore.read(context).safeLengthBytes == 1_024L
                        && "segment-2.gpx".equals(SessionStore.read(context).file),
                "le checkpoint du segment 2 reste la source de vérité");

        section("Récupération du backup après coupure pendant AtomicFile");
        SessionJournal beforeCrash = SessionJournal.of("backup-session", true, 10L, 20L,
                false, false, 0L, 0, 0L, 0, "", 0L);
        ok(SessionStore.write(context, beforeCrash), "état initial pour scénario de crash écrit");
        android.util.AtomicFile transaction = new android.util.AtomicFile(internal);
        FileOutputStream interruptedWrite = transaction.startWrite();
        interruptedWrite.write("format=4\nsessionId=partial\ndirty=true\n"
                .getBytes(StandardCharsets.UTF_8));
        interruptedWrite.flush();
        interruptedWrite.getFD().sync();
        interruptedWrite.close(); // arrêt du processus : FD fermé, finishWrite absent
        // Simule un arrêt brutal : startWrite a déplacé le fichier committé
        // vers .bak, mais finishWrite/failWrite n'a jamais été exécuté.
        SessionJournal restored = SessionStore.read(context);
        ok(restored != null && "backup-session".equals(restored.sessionId),
                "AtomicFile restaure le snapshot committé depuis .bak");
        ok(!new File(internal.getPath() + ".bak").exists(),
                "backup transactionnel réconcilié après lecture");
        ok(SessionStore.write(context, journal), "journal initial restauré pour la suite des scénarios");

        section("Première écriture interrompue : migration legacy encore possible");
        SessionStore.clear(context);
        File legacyFirstWrite = new File(context.getExternalFilesDir(null), SessionJournal.FILE_NAME);
        try (FileOutputStream out = new FileOutputStream(legacyFirstWrite)) {
            out.write("format=3\nstart=77\nsavedAt=88\ndirty=true\nrecording=false\n"
                    .getBytes(StandardCharsets.UTF_8));
        }
        File orphanNew = new File(internal.getPath() + ".new");
        try (FileOutputStream out = new FileOutputStream(orphanNew)) {
            out.write("partial".getBytes(StandardCharsets.UTF_8));
        }
        SessionJournal afterFirstWriteCrash = SessionStore.read(context);
        ok(afterFirstWriteCrash != null && afterFirstWriteCrash.startMs == 77L,
                "journal legacy récupéré malgré .new orphelin");
        ok(!orphanNew.exists(), "fichier .new incomplet écarté");

        ok(SessionStore.write(context, journal), "snapshot initial rétabli après le scénario de migration");

        section("Notice acquittée sans consommer les données de récupération");
        ok(SessionStore.markNoticeShown(context, read), "acquittement de la notice accepté");
        SessionJournal acknowledged = SessionStore.read(context);
        ok(acknowledged != null && acknowledged.noticeShown, "noticeShown persistant");
        ok(internal.isFile(), "journal conservé pour la récupération GPX ultérieure");
        ok(SessionStore.read(context) != null, "lecture répétée non destructive");
        SessionJournal nextSnapshot = SessionJournal.of(id, true, 100L, 202L,
                true, false, 57L, 1, 57L, 0, "track.gpx", 4_096L);
        ok(SessionStore.write(context, nextSnapshot), "snapshot suivant de la session acquittée écrit");
        ok(SessionStore.read(context).noticeShown,
                "un snapshot ultérieur ne fait pas réapparaître une notice acquittée");

        section("Une ancienne opération ne doit pas effacer une nouvelle session");
        SessionJournal newer = SessionJournal.of("new-session", true, 300L, 400L,
                true, false, 1L, 0, 1L, 0, "new.gpx", 0L);
        ok(SessionStore.write(context, newer), "nouvelle session enregistrée");
        ok(!SessionStore.clearIfSameSession(context, id),
                "clear ancien refusé lorsque l'identité a changé");
        ok("new-session".equals(SessionStore.read(context).sessionId),
                "journal de la nouvelle session préservé");
        ok(!SessionStore.clearIfUnchanged(context, acknowledged),
                "snapshot/révision ancien ne peut pas supprimer le journal courant");

        section("Une nouvelle révision de la même session ne doit pas être effacée");
        SessionJournal recoverSnapshot = SessionJournal.of("same-session", true,
                700L, 800L, true, false, 10L, 2, 10L, 0, "same.gpx", 4_096L, 2L);
        ok(SessionStore.write(context, recoverSnapshot),
                "snapshot de session à récupérer écrit");
        SessionJournal recoveryRead = SessionStore.read(context);
        SessionJournal laterCheckpoint = SessionJournal.of("same-session", true,
                700L, 900L, true, false, 12L, 2, 12L, 0, "same.gpx", 8_192L, 2L);
        ok(SessionStore.write(context, laterCheckpoint),
                "nouveau checkpoint de la même session écrit pendant une opération retardée");
        ok(!SessionStore.clearIfUnchanged(context, recoveryRead),
                "l'acquittement d'une ancienne révision ne peut pas effacer la même session actualisée");
        SessionJournal stillCurrent = SessionStore.read(context);
        ok(stillCurrent != null && stillCurrent.safeLengthBytes == 8_192L,
                "le checkpoint plus récent reste présent après le refus d'acquittement");
        ok(SessionStore.clearIfUnchanged(context, stillCurrent),
                "seul le snapshot courant exact peut être acquitté");
        ok(SessionStore.read(context) == null,
                "le journal exact est supprimé après son acquittement");

        section("Migration du journal historique externe");
        SessionStore.clear(context);
        File legacy = new File(context.getExternalFilesDir(null), SessionJournal.FILE_NAME);
        try (FileOutputStream out = new FileOutputStream(legacy)) {
            out.write(("format=3\nstart=10\nsavedAt=20\ndirty=true\n"
                    + "recording=true\nsuspended=false\nfile=legacy.gpx\nsafeLength=2048\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        SessionJournal migrated = SessionStore.read(context);
        ok(migrated != null && migrated.file.equals("legacy.gpx"), "ancien journal externe lu");
        ok(new File(context.getFilesDir(), SessionJournal.FILE_NAME).isFile(),
                "ancien journal migré vers stockage interne");
        ok(!legacy.exists(), "ancienne copie supprimée après migration atomique");
        ok(migrated.safeLengthBytes == 2_048L, "offset historique conservé pendant migration");

        section("Journal interne inconnu/corrompu non effacé par une lecture");
        SessionStore.clear(context);
        try (FileOutputStream out = new FileOutputStream(internal)) {
            out.write("format=999\nfuture=data\n".getBytes(StandardCharsets.UTF_8));
        }
        try (FileOutputStream out = new FileOutputStream(legacy)) {
            out.write("format=3\nstart=10\ndirty=true\n".getBytes(StandardCharsets.UTF_8));
        }
        ok(SessionStore.read(context) == null, "format futur non interprété");
        ok(internal.isFile(), "fichier interne non supprimé" );
        ok(legacy.isFile(), "ancienne source non détruite en cas d'interne illisible");
        SessionJournal replacement = SessionJournal.of("replacement-session", true,
                500L, 600L, true, false, 1L, 0, 1L, 0, "replacement.gpx", 0L);
        ok(SessionStore.write(context, replacement),
                "un nouveau START peut écrire après mise en quarantaine du journal illisible");
        ok(new File(internal.getPath() + ".corrupt").isFile(),
                "le contenu interne illisible est conservé en quarantaine avant remplacement");
        ok(legacy.isFile(),
                "une ancienne source externe valide reste conservée si l'interne était illisible");
        ok("replacement-session".equals(SessionStore.read(context).sessionId),
                "le nouveau journal devient la source active");

        SessionStore.clear(context);
        ok(!new File(internal.getPath() + ".corrupt").exists(),
                "un arrêt explicite efface aussi les copies de quarantaine privées");
        try (FileOutputStream out = new FileOutputStream(internal)) {
            byte[] oversized = new byte[20 * 1024];
            java.util.Arrays.fill(oversized, (byte) 'x');
            out.write(oversized);
        }
        ok(SessionStore.read(context) == null, "journal supérieur à 16 Kio refusé");
        ok(internal.isFile(), "journal volumineux conservé pour diagnostic, non supprimé");

        SessionStore.clear(context);
        deleteTree(root);
        System.out.println("\n---------------------------------------------");
        System.out.println("RESULTAT : " + pass + " reussis, " + fail + " echoues");
        if (fail > 0) System.exit(1);
    }

    private static void section(String value) { System.out.println("\n=== " + value + " ==="); }
    private static void ok(boolean condition, String label) {
        if (condition) pass++;
        else { fail++; System.out.println("  ECHEC " + label); }
    }
    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        f.delete();
    }

    private static final class TestContext extends Context {
        private final File files;
        private final File external;
        TestContext(File files, File external) { this.files = files; this.external = external; }
        @Override public File getFilesDir() { return files; }
        @Override public File getExternalFilesDir(String type) { return external; }
    }
}
