#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Static guards for conservative checkpoint-based GPX recovery."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RECOVERY = ROOT / "app/src/main/java/com/example/gpstest/GpxRecovery.java"
ACTIVITY = ROOT / "app/src/main/java/com/example/gpstest/MainActivity.java"
SERVICE = ROOT / "app/src/main/java/com/example/gpstest/TrackingService.java"
TEST = ROOT / "tests/GpxRecoveryTest.java"
REGISTRY = ROOT / "app/src/main/java/com/example/gpstest/RecoveryRegistry.java"
STORE = ROOT / "app/src/main/java/com/example/gpstest/SessionStore.java"
STORE_TEST = ROOT / "tests/SessionStoreTest.java"
REGISTRY_TEST = ROOT / "tests/RecoveryRegistryTest.java"
RECOVERY_WORKFLOW = ROOT / "app/src/main/java/com/example/gpstest/GpxRecoveryWorkflow.java"
WORKFLOW = ROOT / ".github/workflows/android-build.yml"


def read(path: Path) -> str:
    if not path.is_file():
        raise SystemExit(f"ERREUR — fichier absent : {path.relative_to(ROOT)}")
    return path.read_text(encoding="utf-8")


def require(condition: bool, label: str) -> None:
    if not condition:
        raise SystemExit(f"ERREUR — {label}")
    print(f"OK — {label}")


def main() -> int:
    recovery = read(RECOVERY)
    activity = read(ACTIVITY)
    service = read(SERVICE)
    tests = read(TEST)
    registry = read(REGISTRY)
    store = read(STORE)
    store_tests = read(STORE_TEST)
    registry_tests = read(REGISTRY_TEST)
    recovery_workflow = read(RECOVERY_WORKFLOW)
    workflow = read(WORKFLOW)

    require("copyPrefix(source, temp, checkpoint)" in recovery,
            "la récupération copie le préfixe exact du checkpoint")
    require("prefixContentsEqual(source, temp, checkpoint)" in recovery
            and "MessageDigest.getInstance(\"SHA-256\")" in recovery,
            "le contenu source est comparé au préfixe copié, même à taille et date identiques")
    require("remaining > 0" in recovery and "raw.getFD().sync()" in recovery,
            "la copie de récupération est complète puis synchronisée")
    require("source.getCanonicalFile()" in recovery and "canonicalSource.getParentFile()" in recovery,
            "le fichier source est confiné au dossier de traces")
    require('n.contains("/")' in recovery and 'n.contains("\\\\")' in recovery,
            "les noms fournis ne peuvent pas traverser un chemin")
    require("disallow-doctype-decl" in recovery and "external-general-entities" in recovery
            and "external-parameter-entities" in recovery,
            "DOCTYPE et entités externes sont désactivés")
    require("sawTrack" in recovery and "sawTrackSegment" in recovery,
            "la racine contient la structure GPX track/track segment attendue")
    require("if (!isCompleteGpx(temp))" in recovery and "appendFooter(temp)" in recovery,
            "le pied n'est ajouté que si le préfixe n'est pas déjà un GPX complet")
    require("INVALID_PREFIX" in recovery and "L'original est intact" in recovery,
            "un préfixe invalide est rejeté sans modification de la source")
    require("temp.renameTo(target)" in recovery and "filesEqual(target, temp)" in recovery,
            "la copie est publiée séparément et les reprises identiques sont idempotentes")
    require("GpxRecoveryWorkflow.recoverAndPublish(" in activity
            and "new Thread(() -> {" in activity,
            "MainActivity déclenche le workflow de récupération/publication sur son worker de démarrage")
    require("SessionStore.readRecoveries(MainActivity.this)" in activity
            and "GpxRecoveryWorkflow.recoverAndPublish(" in activity,
            "MainActivity parcourt le registre multi-sessions via le workflow transactionnel")
    require('new Thread(() -> {' in service and '"gpx-session-recovery"' in service
            and "GpxRecoveryWorkflow.recoverAndPublish(" in service,
            "le service possède un repli asynchrone commun au workflow transactionnel")
    require("SessionStore.readRecoveries(TrackingService.this)" in service
            and "GpxRecoveryWorkflow.recoverAndPublish(" in service,
            "le service parcourt le registre multi-sessions via le workflow transactionnel")
    require("SessionStore.clearRecoveryIfUnchanged(context, expected)" in recovery_workflow
            and "store.removeVerified(published.publicationId)" in recovery_workflow,
            "le registre de récupération n'est acquitté qu'après publication vérifiée")
    require("rejectsAMidPointPrefixWithoutChangingTheSource" in tests
            and "rejectsCheckpointCutInsideUtf8Sequence" in tests
            and "concurrentRecoveryCallsPublishOnlyOneCopy" in tests
            and "detectsSameLengthSourceMutationWithPrefixDigest" in tests
            and "reusesAnIdenticalRecoveredCopyAndNeverOverwritesAConflict" in tests,
            "les tests couvrent les coupures XML/UTF-8, modifications de source, concurrence et collisions")
    require("SessionStore.clearIfUnchanged(context, recoveryRead)" in store_tests,
            "un checkpoint plus récent de la même session ne peut pas être effacé")
    require("MAX_ENTRIES = 64" in registry and "sameSegment" in registry
            and "advanceCheckpoint" in registry and "removeSegment" in registry
            and "onMalformedInput(CodingErrorAction.REPORT)" in registry
            and "private static int asciiHex" in registry,
            "le modèle du registre borne les entrées, garde la monotonie et parse strictement UTF-8/hex")
    require("readRecoveries(Context ctx)" in store and "clearRecoveryIfUnchanged" in store
            and "clearRecoverySegment" in store and "upsertRecoveryLocked" in store,
            "SessionStore persiste le registre et protège les acquittements ciblés")
    require("updateCheckpoint" in store and "registry.advanceCheckpoint" in store
            and "SessionJournal detached" in store,
            "un callback de checkpoint ancien/tardif reste rattaché au bon segment")
    require("queueSegmentRecoveryRemoval" in service
            and "finalCheckpoint.safeLengthBytes == segment.file.length()" in service,
            "un segment n'est retiré du registre qu'après sync complet du pied GPX")
    require("R2C : le journal courant peut déjà appartenir à un nouveau START" in service
            and "Registre multi-sessions et multi-segments durable" in store_tests
            and "MAX_ENTRIES" in registry_tests
            and "entries.size() >= MAX_ENTRIES" in registry
            and "un contenu UTF-8 mal formé est refusé strictement" in registry_tests
            and "le registre corrompu reste disponible pour diagnostic" in store_tests,
            "la couverture teste sessions/segments multiples, corruption et limite sans éviction")
    require("Verify interrupted GPX recovery contract" in workflow,
            "GitHub Actions exécute le contrôle structurel de récupération")
    print("OK : invariants structurels récupération et publication R2C vérifiés.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
