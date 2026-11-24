package com.example.gpstest;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Tests JVM de l'association fichier/longueur du dernier préfixe GPX synchronisé. */
public final class GpxCheckpointTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        GpxCheckpoint checkpoint = new GpxCheckpoint();
        GpxCheckpoint.Snapshot initial = checkpoint.snapshot();
        check(initial.fileName.isEmpty(), "aucun fichier n'est associé avant l'ouverture");
        check(initial.safeLengthBytes == 0L, "aucun offset n'est annoncé avant sync");
        check(!checkpoint.register("../outside.gpx"), "un chemin n'est pas accepté comme nom de fichier");
        check(checkpoint.register("segment-001.gpx"), "un nom de segment valide peut être enregistré");
        check(checkpoint.advance("segment-001.gpx", 512L), "le premier préfixe synchronisé est mémorisé");
        check(checkpoint.snapshot().safeLengthBytes == 512L, "la longueur synchronisée est restituée");
        check(checkpoint.advance("segment-001.gpx", 512L), "un sync sans nouveaux octets est idempotent");
        check(!checkpoint.advance("segment-001.gpx", 0L), "un offset nul ne remplace pas un checkpoint");
        check(!checkpoint.advance("segment-001.gpx", 400L), "une longueur décroissante est refusée");
        check(!checkpoint.advance("other.gpx", 900L), "un checkpoint d'un autre fichier est refusé");
        check(checkpoint.snapshot().safeLengthBytes == 512L, "les tentatives invalides ne régressent pas le checkpoint");
        check(checkpoint.register("segment-002.gpx"), "un nouveau segment reçoit une nouvelle association");
        GpxCheckpoint.Snapshot next = checkpoint.snapshot();
        check("segment-002.gpx".equals(next.fileName), "le nouveau nom remplace l'ancien");
        check(next.safeLengthBytes == 0L, "le nouveau segment repart sans offset sûr");
        check(!checkpoint.advance("segment-001.gpx", 700L), "un ancien segment ne peut plus avancer le nouveau checkpoint");
        check(checkpoint.advance("segment-002.gpx", 128L), "le nouveau segment peut confirmer son propre préfixe");

        verifiesCrashTruncationKeepsOnlyTheLastSyncedPrefix();

        System.out.printf("GpxCheckpointTest : %d réussis, %d échoués%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    private static void verifiesCrashTruncationKeepsOnlyTheLastSyncedPrefix() throws Exception {
        File file = Files.createTempFile("gpstest-checkpoint-", ".gpx").toFile();
        GpxCheckpoint state = new GpxCheckpoint();
        String header = "<gpx><trk><trkseg>\n";
        String point = "<trkpt lat=\"1\" lon=\"2\"><time>2026-11-24T20:00:00Z</time></trkpt>\n";
        String interruptedTail = "<trkpt lat=\"3\" lon=\"4\"><time>PARTIAL";
        long checkpointLength;
        try {
            try (FileOutputStream out = new FileOutputStream(file, false)) {
                out.write(header.getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.getFD().sync();
                state.register(file.getName());
                check(state.advance(file.getName(), file.length()),
                        "l'en-tête ne devient checkpoint qu'après sync réussi");

                out.write(point.getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.getFD().sync();
                check(state.advance(file.getName(), file.length()),
                        "un point complet augmente le préfixe après sync");
                checkpointLength = state.snapshot().safeLengthBytes;

                out.write(interruptedTail.getBytes(StandardCharsets.UTF_8));
                out.flush(); // octets visibles, mais volontairement non synchronisés / non checkpointés
                check(file.length() > checkpointLength,
                        "une queue non checkpointée peut dépasser la longueur sûre");
            }
            try (RandomAccessFile recovery = new RandomAccessFile(file, "rw")) {
                recovery.setLength(checkpointLength);
            }
            String recovered = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            check(recovered.equals(header + point),
                    "la troncature au dernier offset sûr conserve l'en-tête et le point complet");
            check(!recovered.contains("PARTIAL"),
                    "la queue partielle postérieure au checkpoint est supprimée");
        } finally {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
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
