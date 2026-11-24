package com.example.gpstest;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/** Tests de récupération conservatrice d'un préfixe GPX durable. */
public final class GpxRecoveryTest {
    private static int passed, failed;

    public static void main(String[] args) throws Exception {
        recoversV11FromOnlyTheSyncedPrefix();
        recoversV10AndPreservesACompletedPrefix();
        rejectsMissingOrImpossibleCheckpoints();
        rejectsUnsafeNamesAndNonGpxDocuments();
        rejectsDoctypeAndExternalEntities();
        rejectsAMidPointPrefixWithoutChangingTheSource();
        rejectsCheckpointCutInsideUtf8Sequence();
        rejectsDocumentsWithoutTrackSegmentsAndCleansTemporaryFiles();
        detectsSameLengthSourceMutationWithPrefixDigest();
        concurrentRecoveryCallsPublishOnlyOneCopy();
        reusesAnIdenticalRecoveredCopyAndNeverOverwritesAConflict();
        System.out.printf("GpxRecoveryTest : %d réussis, 0 échoués%n", passed);
        if (failed > 0) System.exit(1);
    }

    private static void recoversV11FromOnlyTheSyncedPrefix() throws Exception {
        File dir = Files.createTempDirectory("gpstest-recovery-v11-").toFile();
        String name = "Trace_v11.GPX";
        String prefix = validPrefix(GpxWriter.Version.V11);
        byte[] durable = bytes(prefix);
        byte[] original = bytes(prefix + "<trkpt lat=\"50\" lon=\"2\"><time>partial");
        File source = write(dir, name, original);
        GpxRecovery.Result result = GpxRecovery.recover(dir, name, durable.length);
        check(result.isSuccess(), "un préfixe GPX 1.1 durable est récupéré");
        check(result.footerWasAdded, "le pied est ajouté lorsque le préfixe est ouvert");
        check((prefix + GpxWriter.footer()).equals(read(new File(dir, result.recoveredFileName))),
                "la copie contient exactement le préfixe durable puis le pied, sans queue non synchronisée");
        check(Arrays.equals(original, Files.readAllBytes(source.toPath())),
                "le fichier source n'est jamais tronqué ni modifié");
        check(result.recoveredFileName.endsWith("_RECUPEREE.GPX"), "la copie a un nom distinct et explicite");
        cleanup(dir);
    }

    private static void recoversV10AndPreservesACompletedPrefix() throws Exception {
        File dir = Files.createTempDirectory("gpstest-recovery-v10-").toFile();
        String name = "Trace_v10.GPX";
        String complete = validPrefix(GpxWriter.Version.V10) + GpxWriter.footer();
        byte[] sourceBytes = bytes(complete + "UNSYNCED-TAIL");
        File source = write(dir, name, sourceBytes);
        GpxRecovery.Result result = GpxRecovery.recover(dir, name, bytes(complete).length);
        check(result.isSuccess(), "un GPX 1.0 déjà complet est reconnu");
        check(!result.footerWasAdded, "un pied déjà présent n'est pas ajouté deux fois");
        check(complete.equals(read(new File(dir, result.recoveredFileName))),
                "le GPX 1.0 complet est recopié à l'identique");
        check(source.isFile(), "l'original complet est conservé");
        check(Arrays.equals(sourceBytes, Files.readAllBytes(source.toPath())),
                "la queue après un checkpoint final n'est pas utilisée et la source reste intacte");
        cleanup(dir);
    }

    private static void rejectsMissingOrImpossibleCheckpoints() throws Exception {
        File dir = Files.createTempDirectory("gpstest-recovery-checkpoint-").toFile();
        write(dir, "trace.gpx", bytes("<not-a-gpx/>"));
        check(GpxRecovery.recover(dir, "trace.gpx", 0).status == GpxRecovery.Status.NO_CHECKPOINT,
                "un checkpoint nul ne déclenche aucune réparation");
        check(GpxRecovery.recover(dir, "trace.gpx", 99_999).status == GpxRecovery.Status.CHECKPOINT_BEYOND_EOF,
                "un offset supérieur à la taille est refusé");
        check(GpxRecovery.recover(dir, "absent.gpx", 10).status == GpxRecovery.Status.SOURCE_MISSING,
                "un fichier source absent est signalé");
        cleanup(dir);
    }

    private static void rejectsUnsafeNamesAndNonGpxDocuments() throws Exception {
        File dir = Files.createTempDirectory("gpstest-recovery-invalid-").toFile();
        check(GpxRecovery.recover(dir, "../private.gpx", 12).status == GpxRecovery.Status.INVALID_ARGUMENT,
                "un chemin traversant le dossier est refusé");
        check(GpxRecovery.recover(dir, " spaced.gpx", 12).status == GpxRecovery.Status.INVALID_ARGUMENT,
                "un nom de fichier avec espaces de bord est refusé plutôt que normalisé implicitement");
        check(GpxRecovery.recover(dir, "trace.txt", 12).status == GpxRecovery.Status.INVALID_ARGUMENT,
                "une extension différente de GPX est refusée");
        String bad = "<?xml version=\"1.0\"?><gpx version=\"1.1\" xmlns=\"urn:wrong\"><trk/></gpx>";
        write(dir, "wrong.gpx", bytes(bad));
        GpxRecovery.Result result = GpxRecovery.recover(dir, "wrong.gpx", bytes(bad).length);
        check(result.status == GpxRecovery.Status.INVALID_PREFIX,
                "un XML bien formé avec mauvais namespace GPX est refusé");
        check(!new File(dir, "wrong_RECUPEREE.gpx").exists(), "aucune copie invalide n'est publiée");
        cleanup(dir);
    }


    private static void rejectsDoctypeAndExternalEntities() throws Exception {
        File dir = Files.createTempDirectory("gpstest-recovery-xxe-").toFile();
        String malicious = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE gpx [<!ENTITY leak SYSTEM \"file:///etc/passwd\">]>"
                + "<gpx version=\"1.1\" xmlns=\"http://www.topografix.com/GPX/1/1\"><trk>&leak;</trk></gpx>";
        byte[] bytes = bytes(malicious);
        File source = write(dir, "external.gpx", bytes);
        GpxRecovery.Result result = GpxRecovery.recover(dir, "external.gpx", bytes.length);
        check(result.status == GpxRecovery.Status.INVALID_PREFIX,
                "un DOCTYPE avec entité externe est refusé par le parseur sécurisé");
        check(Arrays.equals(bytes, Files.readAllBytes(source.toPath())),
                "le rejet d'une entité externe ne modifie pas le fichier source");
        check(!new File(dir, "external_RECUPEREE.gpx").exists(),
                "aucune copie n'est publiée pour un document hostile");
        cleanup(dir);
    }

    private static void rejectsAMidPointPrefixWithoutChangingTheSource() throws Exception {
        File dir = Files.createTempDirectory("gpstest-recovery-midpoint-").toFile();
        String name = "partial.gpx";
        String prefix = validPrefix(GpxWriter.Version.V11)
                + "<trkpt lat=\"1\" lon=\"2\"><time>2026-11-24T";
        byte[] data = bytes(prefix);
        File source = write(dir, name, data);
        GpxRecovery.Result result = GpxRecovery.recover(dir, name, data.length);
        check(result.status == GpxRecovery.Status.INVALID_PREFIX,
                "une coupure au milieu d'un point ne devient pas un faux GPX valide");
        check(Arrays.equals(data, Files.readAllBytes(source.toPath())),
                "un préfixe irrécupérable laisse la source strictement intacte");
        cleanup(dir);
    }


    private static void rejectsCheckpointCutInsideUtf8Sequence() throws Exception {
        File dir = Files.createTempDirectory("gpstest-recovery-utf8-").toFile();
        String name = "utf8-cut.gpx";
        String prefix = validPrefix(GpxWriter.Version.V11);
        byte[] all = bytes(prefix + GpxWriter.footer());
        byte[] character = bytes("é");
        int at = indexOf(all, character);
        check(at >= 0, "le scénario contient un caractère UTF-8 multioctet");
        if (at >= 0) {
            byte[] original = all.clone();
            File source = write(dir, name, original);
            long checkpoint = at + 1L; // premier octet du caractère, séquence coupée
            GpxRecovery.Result result = GpxRecovery.recover(dir, name, checkpoint);
            check(result.status == GpxRecovery.Status.INVALID_PREFIX,
                    "un checkpoint au milieu d'une séquence UTF-8 est refusé");
            check(Arrays.equals(original, Files.readAllBytes(source.toPath())),
                    "la source reste intacte après une coupure UTF-8");
            check(countPrefix(dir, ".gpstest-recovery-") == 0,
                    "le temporaire est nettoyé après rejet UTF-8");
        }
        cleanup(dir);
    }

    private static void rejectsDocumentsWithoutTrackSegmentsAndCleansTemporaryFiles() throws Exception {
        File dir = Files.createTempDirectory("gpstest-recovery-no-track-").toFile();
        String noTrack = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<gpx version=\"1.1\" creator=\"test\" "
                + "xmlns=\"http://www.topografix.com/GPX/1/1\"><metadata><name>vide</name>"
                + "</metadata></gpx>";
        byte[] original = bytes(noTrack);
        File source = write(dir, "sans-trace.gpx", original);
        GpxRecovery.Result result = GpxRecovery.recover(dir, "sans-trace.gpx", original.length);
        check(result.status == GpxRecovery.Status.INVALID_PREFIX,
                "un document sans trk/trkseg n'est pas présenté comme une trace récupérée");
        check(Arrays.equals(original, Files.readAllBytes(source.toPath())),
                "le document source sans trace reste inchangé");
        check(countPrefix(dir, ".gpstest-recovery-") == 0,
                "le temporaire est supprimé après rejet du document sans trace");
        check(countSuffix(dir, "_RECUPEREE.gpx") == 0,
                "aucune copie de récupération vide n'est publiée");
        cleanup(dir);
    }

    private static void detectsSameLengthSourceMutationWithPrefixDigest() throws Exception {
        File dir = Files.createTempDirectory("gpstest-recovery-digest-").toFile();
        File source = write(dir, "source.bin", bytes("ABCDEFGHIJKLMN"));
        File copy = write(dir, "copy.bin", bytes("ABCDEFGHIJKLMN"));
        long timestamp = 1_700_000_000_000L;
        source.setLastModified(timestamp);
        copy.setLastModified(timestamp);
        check(GpxRecovery.prefixContentsEqual(source, copy, 10L),
                "les préfixes identiques sont reconnus");
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(source, "rw")) {
            raf.seek(3L);
            raf.write('x'); // même longueur, même date restaurée ensuite
        }
        source.setLastModified(timestamp);
        check(source.length() == copy.length() && source.lastModified() == copy.lastModified(),
                "le scénario simule une modification invisible aux seules métadonnées");
        check(!GpxRecovery.prefixContentsEqual(source, copy, 10L),
                "le digest détecte une réécriture de même longueur et même date");
        cleanup(dir);
    }

    private static void concurrentRecoveryCallsPublishOnlyOneCopy() throws Exception {
        File dir = Files.createTempDirectory("gpstest-recovery-concurrent-").toFile();
        String name = "concurrent.gpx";
        String prefix = validPrefix(GpxWriter.Version.V11);
        byte[] original = bytes(prefix + "TAIL-NON-SYNCHRONISEE");
        File source = write(dir, name, original);
        final int workers = 6;
        final java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(workers);
        final java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        final GpxRecovery.Result[] results = new GpxRecovery.Result[workers];
        final Throwable[] errors = new Throwable[workers];
        Thread[] threads = new Thread[workers];
        for (int i = 0; i < workers; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    results[index] = GpxRecovery.recover(dir, name, bytes(prefix).length);
                } catch (Throwable error) {
                    errors[index] = error;
                }
            }, "gpx-recovery-test-" + i);
            threads[i].start();
        }
        ready.await();
        start.countDown();
        for (Thread thread : threads) thread.join(10_000L);
        boolean allDone = true;
        for (Thread thread : threads) if (thread.isAlive()) allDone = false;
        check(allDone, "les récupérations concurrentes terminent sans blocage");
        boolean allSuccess = allDone;
        String recoveredName = null;
        for (int i = 0; i < workers; i++) {
            if (errors[i] != null || results[i] == null || !results[i].isSuccess()) allSuccess = false;
            else if (recoveredName == null) recoveredName = results[i].recoveredFileName;
            else if (!recoveredName.equals(results[i].recoveredFileName)) allSuccess = false;
        }
        check(allSuccess, "toutes les demandes concurrentes réutilisent le même résultat réussi");
        check(countSuffix(dir, "_RECUPEREE.gpx") == 1,
                "une seule copie finale est publiée malgré six demandes concurrentes");
        check(countPrefix(dir, ".gpstest-recovery-") == 0,
                "aucun fichier temporaire ne subsiste après les demandes concurrentes");
        check(Arrays.equals(original, Files.readAllBytes(source.toPath())),
                "la source reste intacte malgré les demandes concurrentes");
        cleanup(dir);
    }

    private static void reusesAnIdenticalRecoveredCopyAndNeverOverwritesAConflict() throws Exception {
        File dir = Files.createTempDirectory("gpstest-recovery-idempotent-").toFile();
        String name = "idempotent.gpx";
        String prefix = validPrefix(GpxWriter.Version.V11);
        byte[] sourceBytes = bytes(prefix + "TRAILING-UNSYNCED");
        File source = write(dir, name, sourceBytes);
        GpxRecovery.Result first = GpxRecovery.recover(dir, name, bytes(prefix).length);
        GpxRecovery.Result second = GpxRecovery.recover(dir, name, bytes(prefix).length);
        check(first.isSuccess() && second.isSuccess(), "deux récupérations identiques réussissent");
        check(first.recoveredFileName.equals(second.recoveredFileName),
                "une deuxième récupération réutilise la copie identique");
        File conflict = new File(dir, "collision_RECUPEREE.gpx");
        byte[] userBytes = bytes("fichier utilisateur");
        Files.write(conflict.toPath(), userBytes);
        write(dir, "collision.gpx", sourceBytes);
        GpxRecovery.Result third = GpxRecovery.recover(dir, "collision.gpx", bytes(prefix).length);
        check(third.isSuccess() && third.recoveredFileName.equals("collision_RECUPEREE-2.gpx"),
                "un nom déjà occupé entraîne un suffixe, jamais un écrasement");
        check(Arrays.equals(userBytes, Files.readAllBytes(conflict.toPath())),
                "un fichier existant au nom de récupération reste inchangé");
        check(Arrays.equals(sourceBytes, Files.readAllBytes(source.toPath())),
                "la source reste intacte après les répétitions");
        cleanup(dir);
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        outer: for (int i = 0; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) if (data[i + j] != pattern[j]) continue outer;
            return i;
        }
        return -1;
    }
    private static int countPrefix(File dir, String prefix) {
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int count = 0;
        for (File file : files) if (file.getName().startsWith(prefix)) count++;
        return count;
    }
    private static int countSuffix(File dir, String suffix) {
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int count = 0;
        for (File file : files) if (file.getName().endsWith(suffix)) count++;
        return count;
    }

    private static String validPrefix(GpxWriter.Version version) {
        GpxWriter.TrackPoint point = new GpxWriter.TrackPoint(1_795_000_000_000L, 48.1, 2.2, 35.0);
        return GpxWriter.header(version, "Récupération sûre", 1_795_000_000_000L)
                + GpxWriter.pointXml(point, version);
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static File write(File dir, String name, byte[] data) throws Exception {
        File file = new File(dir, name); Files.write(file.toPath(), data); return file;
    }
    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
    private static void cleanup(File dir) {
        File[] children = dir.listFiles();
        if (children != null) for (File child : children) child.delete();
        dir.delete();
    }
    private static void check(boolean ok, String message) {
        if (ok) passed++; else { failed++; System.out.println("  ÉCHEC : " + message); }
    }
}
