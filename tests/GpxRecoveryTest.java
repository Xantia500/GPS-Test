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
