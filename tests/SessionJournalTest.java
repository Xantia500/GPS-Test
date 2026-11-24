package com.example.gpstest;

/**
 * Tests du journal de session (SessionJournal) — classe 100 % pure.
 *
 * Pourquoi ces tests valent la peine : le journal est la seule chose qui
 * survit à un « kill -9 ». S'il est mal formé, ou s'il lève une exception au
 * démarrage, on perd exactement l'information qu'il était censé porter. Et
 * un faux positif (« session interrompue » affiché après un arrêt normal)
 * est aussi grave qu'un faux négatif : l'utilisateur cesse de croire l'app.
 *
 * Exécution :
 *   java -cp out com.example.gpstest.SessionJournalTest
 */
public class SessionJournalTest {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {

        // ============================================================
        section("Aller-retour sérialisation -> analyse");
        // ============================================================
        SessionJournal j = SessionJournal.of(
                true, 1_700_000_000_000L, 1_700_000_600_000L,
                true, false, 421, 2, 137, 1, "trace-2026-11-22_1845-p3.gpx");
        String txt = j.toText();
        SessionJournal back = SessionJournal.parse(txt);

        ok(back != null, "analyse d'un texte valide ne renvoie pas null");
        ok(back.dirty, "dirty conservé");
        ok(back.sessionId != null && !back.sessionId.isEmpty(), "identité de session conservée");
        ok(back.safeLengthBytes == 0L, "absence de checkpoint représentée par zéro");
        ok(back.revision >= 1L, "révision initialisée");
        ok(back.startMs == 1_700_000_000_000L, "startMs conservé");
        ok(back.savedAtMs == 1_700_000_600_000L, "savedAtMs conservé");
        ok(back.recording, "recording conservé");
        ok(!back.suspended, "suspended conservé");
        ok(back.points == 421, "points conservé");
        ok(back.segment == 2, "segment conservé");
        ok(back.segmentPoints == 137, "segmentPoints conservé");
        ok(back.policyId == 1, "policyId conservé");
        ok("trace-2026-11-22_1845-p3.gpx".equals(back.file), "nom de fichier conservé");

        // Toutes les clés doivent être présentes dans le texte : c'est ce qui
        // permet de lire un journal écrit par une version future de l'app.
        for (String key : new String[]{"start=", "recording=", "points=", "file=",
                "segment=", "segmentPoints=", "policy=", "suspended=",
                "savedAt=", "dirty="}) {
            ok(txt.contains(key), "le texte contient la clé " + key);
        }

        // ============================================================
        section("Robustesse : entrées corrompues ou partielles");
        // ============================================================
        ok(SessionJournal.parse(null) == null, "null -> null (rien à signaler)");
        ok(SessionJournal.parse("") == null, "chaîne vide -> null");
        ok(SessionJournal.parse("   \n  \n") == null, "blancs seuls -> null");
        ok(SessionJournal.parse("n'importe quoi sans egal") == null,
                "texte sans aucune clé -> null");

        // Ligne cassée au milieu : on ne doit pas perdre les autres.
        SessionJournal partial = SessionJournal.parse(
                "start=123\npoints=\nsegment=abc\nfile=x.gpx\ndirty=true\n");
        ok(partial != null, "journal partiellement corrompu reste lisible");
        ok(partial.startMs == 123L, "champ valide conservé autour d'une ligne cassée");
        ok(partial.points == 0, "entier vide -> valeur par défaut (pas d'exception)");
        ok(partial.segment == 0, "entier non numérique -> valeur par défaut");
        ok("x.gpx".equals(partial.file), "nom de fichier conservé malgré le bruit");
        ok(partial.dirty, "dirty lu malgré le bruit");

        // Clé inconnue : ignorée, pas fatale (compatibilité ascendante).
        SessionJournal withUnknown = SessionJournal.parse(
                "start=1\ndirty=false\nfuture_key=42\n");
        ok(withUnknown != null && withUnknown.startMs == 1L,
                "clé inconnue ignorée sans casser l'analyse");
        ok(!withUnknown.dirty, "dirty=false correctement lu");

        // ============================================================
        section("interruptionNotice : c'est la promesse faite à l'utilisateur");
        // ============================================================
        SessionJournal clean = SessionJournal.of(false, 1, 2, false, false, 0, 0, 0, 0, "a.gpx");
        ok(clean.interruptionNotice() == null,
                "session terminée proprement -> AUCUNE notice");

        SessionJournal noFile = SessionJournal.of(true, 1, 2, true, false, 10, 0, 10, 0, "");
        ok(noFile.interruptionNotice() == null,
                "dirty mais aucun fichier connu -> pas de notice (rien de vérifiable)");

        SessionJournal interrupted = SessionJournal.of(
                true, 1, 2, true, false, 5000, 3, 250, 0, "trace.gpx");
        String notice = interrupted.interruptionNotice();
        ok(notice != null, "session interrompue -> notice présente");
        ok(notice.contains("trace.gpx"), "la notice nomme le fichier");
        ok(notice.contains("250"),
                "la notice donne les points DU SEGMENT (250), pas du total (5000)");
        SessionJournal durableNotice = SessionJournal.of("notice-session", true,
                1L, 2L, true, false, 500L, 3, 250L, 0, "trace.gpx", 8_192L, 3L);
        ok(durableNotice.interruptionNotice().contains("8192 octets"),
                "la notice durable affiche la longueur réellement synchronisée en octets");
        ok(notice.toLowerCase().contains("vérifier"),
                "la notice indique que la fin du fichier doit être vérifiée");

        // Si segmentPoints est nul mais points non nul, on retombe sur points :
        // mieux vaut un chiffre approximatif qu'aucun chiffre.
        SessionJournal fallback = SessionJournal.of(true, 1, 2, true, false, 777, 0, 0, 0, "b.gpx");
        ok(fallback.interruptionNotice().contains("777"),
                "segmentPoints absent -> on affiche le total (777)");

        // ============================================================
        section("Écriture -> analyse : le format reste rond");
        // ============================================================
        // Un fichier écrit par l'app doit être relisible par l'app, avec tous
        // les états possibles (4 combinaisons recording x suspended).
        for (boolean rec : new boolean[]{true, false}) {
            for (boolean sus : new boolean[]{true, false}) {
                for (int pol = 0; pol <= 2; pol++) {
                    SessionJournal in = SessionJournal.of(
                            true, 99, 100, rec, sus, 3, 1, 2, pol, "f.gpx");
                    SessionJournal out = SessionJournal.parse(in.toText());
                    ok(out != null
                                    && out.recording == rec
                                    && out.suspended == sus
                                    && out.policyId == pol,
                            "round-trip état recording=" + rec + " suspended=" + sus
                                    + " policy=" + pol);
                }
            }
        }

        // ============================================================
        section("Faux positif : une pause volontaire n'est PAS une interruption");
        // ============================================================
        // Cas réel qui a motivé ce test : l'utilisateur choisit « pause à
        // l'extinction », éteint l'écran 10 minutes, rouvre l'app. Il ne doit
        // PAS lire « session interrompue » — il a justement demandé la pause.
        SessionJournal paused = SessionJournal.of(
                false, 1, 2, true, true, 900, 1, 300, 2, "trace-p2.gpx");
        ok(!paused.expectedAlive, "journal d'une session suspendue : expectedAlive=false");
        ok(paused.interruptionNotice() == null,
                "session suspendue -> AUCUNE notice (sinon faux positif)");

        // À l'inverse, une session active interrompue doit crier.
        SessionJournal active = SessionJournal.of(
                true, 1, 2, true, false, 900, 0, 900, 0, "trace.gpx");
        ok(active.expectedAlive, "journal d'une session active : expectedAlive=true");
        ok(active.interruptionNotice() != null,
                "session active interrompue -> notice présente");

        // Et le champ doit survivre à l'aller-retour disque.
        SessionJournal rt = SessionJournal.parse(paused.toText());
        ok(rt != null && !rt.expectedAlive,
                "expectedAlive survit à la sérialisation");
        ok(rt.interruptionNotice() == null,
                "après relecture disque, la pause ne produit toujours pas de notice");


        // ============================================================
        section("FORMAT 4 : identité, révision et checkpoint sûr");
        // ============================================================
        SessionJournal checkpoint = SessionJournal.of("session-123", true,
                10L, 20L, true, false, 9_000L, 4, 500L, 2,
                "trace-reprise.gpx", 123_456L);
        SessionJournal checkpointRoundTrip = SessionJournal.parse(checkpoint.toText());
        ok(checkpointRoundTrip != null, "format 4 relu");
        ok("session-123".equals(checkpointRoundTrip.sessionId), "identité stable après parsing");
        ok(checkpointRoundTrip.safeLengthBytes == 123_456L, "offset checkpoint conservé");
        ok(checkpointRoundTrip.segmentGeneration == 0L, "les API historiques gardent une génération nulle");
        ok(checkpoint.toText().contains("format=5\n"), "le format 5 annonce la génération du segment");
        SessionJournal generationRoundTrip = SessionJournal.of("session-123", true,
                10L, 20L, true, false, 9_000L, 4, 500L, 2,
                "trace-reprise.gpx", 123_456L, 42L);
        SessionJournal generationBack = SessionJournal.parse(generationRoundTrip.toText());
        ok(generationBack != null && generationBack.segmentGeneration == 42L,
                "la génération du segment survit au round-trip du format 5");
        SessionJournal formatFour = SessionJournal.parse("format=4\nsessionId=legacy-v4\n"
                + "dirty=true\nrecording=true\nsuspended=false\nfile=old.gpx\nsafeLength=2048\n");
        ok(formatFour != null && formatFour.segmentGeneration == 0L
                        && formatFour.safeLengthBytes == 2048L,
                "un journal format 4 reste lisible sans génération de segment");
        ok(checkpointRoundTrip.segmentPoints == 500L, "compteur de segment conservé");
        ok(!checkpointRoundTrip.noticeShown, "notice non acquittée par défaut");
        checkpointRoundTrip.noticeShown = true;
        ok(SessionJournal.parse(checkpointRoundTrip.toText()).noticeShown,
                "acquittement notice sérialisé");
        ok(SessionJournal.parse("format=999\nstart=1\n") == null,
                "format futur inconnu refusé sans interprétation destructive");
        SessionJournal unsafePath = SessionJournal.parse(
                "format=4\nsessionId=safe-session\ndirty=true\nrecording=true\n"
                        + "suspended=false\nfile=../private/secret.gpx\n");
        ok(unsafePath != null && unsafePath.file.isEmpty(),
                "un chemin relatif fourni dans le journal n'est jamais traité comme un nom de fichier");

        SessionJournal legacy = SessionJournal.parse(
                "format=3\nstart=100\nsavedAt=200\ndirty=true\nrecording=true\n"
                + "suspended=false\nfile=old.gpx\nsafeLength=4096\nsegmentPoints=12\n");
        ok(legacy != null, "journal historique format 3 lisible");
        ok(legacy.safeLengthBytes == 4096L, "checkpoint historique préservé");
        ok(legacy.sessionId.startsWith("legacy:"), "identité déterministe créée pour l'ancien journal");
        ok(legacy.isUnexpectedRecordingInterruption(), "ancien journal actif reconnu comme interrompu");

        // ============================================================
        section("Pause : intention utilisateur distincte de l'acquisition attendue");
        // ============================================================
        SessionJournal pausedButWanted = SessionJournal.of("paused-session", true,
                1L, 2L, true, true, 900L, 1, 300L, 2, "paused.gpx", 8_192L);
        ok(pausedButWanted.dirty, "session logique toujours voulue pendant la pause");
        ok(!pausedButWanted.expectedAlive, "acquisition non attendue pendant la pause");
        ok(pausedButWanted.interruptionNotice() != null,
                "mort du service pendant la pause signalée sans prétendre qu'un point manquait");

        System.out.println("\n---------------------------------------------");
        System.out.println("RESULTAT : " + pass + " reussis, " + fail + " echoues");
        if (fail > 0) System.exit(1);
    }

    static void section(String name) {
        System.out.println("\n=== " + name + " ===");
    }

    static void ok(boolean cond, String label) {
        if (cond) { pass++; }
        else { fail++; System.out.println("  ECHEC " + label); }
    }
}
