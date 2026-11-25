#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Gardes statiques de la publication SAF transactionnelle des GPX récupérés."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/example/gpstest"
FILES = {
    "workflow": JAVA / "GpxRecoveryWorkflow.java",
    "publisher": JAVA / "GpxRecoveryPublisher.java",
    "store": JAVA / "RecoveryPublicationStore.java",
    "registry": JAVA / "RecoveryPublicationRegistry.java",
    "access": JAVA / "SafRecoveryAccess.java",
    "activity": JAVA / "MainActivity.java",
    "service": JAVA / "TrackingService.java",
    "worker": JAVA / "GpxRecoveryRetryWorker.java",
    "test": ROOT / "tests/GpxRecoveryPublisherTest.java",
    "registry_test": ROOT / "tests/RecoveryPublicationRegistryTest.java",
    "ci": ROOT / ".github/workflows/android-build.yml",
}


def read(key):
    path = FILES[key]
    if not path.is_file():
        raise SystemExit(f"ERREUR — fichier absent : {path.relative_to(ROOT)}")
    return path.read_text(encoding="utf-8")


def require(condition, label):
    if not condition:
        raise SystemExit(f"ERREUR — {label}")
    print(f"OK — {label}")


def main():
    w, p, s, r, a = (read(k) for k in ("workflow", "publisher", "store", "registry", "access"))
    activity, service = read("activity"), read("service")
    tests, registry_tests, ci = read("test"), read("registry_test"), read("ci")

    require("GpxRecovery.recover(directory" in w,
            "la récupération locale validée précède la publication SAF")
    require("Destination.treeUri(context)" in w and "hasReadWriteAccess" in w,
            "la destination choisie et les deux permissions SAF sont contrôlées")
    require("GpxRecoveryPublisher.publish(" in w and "PENDING_PERMISSION" in w
            and "PENDING_PUBLICATION" in w,
            "une permission ou publication indisponible conserve l'entrée pour reprise")
    published_branch = w[w.index("if (!published.isSuccess())"):]
    require(published_branch.index("store.removeVerified(published.publicationId)")
            < published_branch.index("SessionStore.clearRecoveryIfUnchanged(context, expected)"),
            "l'état VERIFIED est retiré avant l'acquittement du snapshot publié")
    require("SessionStore.clearRecoveryIfUnchanged(context, expected)" in w
            and "status == Status.PUBLISHED" in w and "status == Status.LOCAL_ONLY" in w,
            "les résultats publiés et privés ont des états distincts et un acquittement conditionnel")

    require("State.PREPARED" in r and "State.DOCUMENT_CREATED" in r
            and "State.VERIFIED" in r and "entry.withDocument(documentUri)" in p
            and "entry.verified()" in p,
            "la transaction progresse PREPARED → DOCUMENT_CREATED → VERIFIED")
    require("store.write(registry)" in p and "access.createDocument" in p,
            "l'intention est persistée avant la création du document externe")
    require("findByDisplayName" in p and "AMBIGUOUS_DOCUMENTS" in p,
            "un document créé avant un crash est retrouvé et les collisions ambiguës sont refusées")
    require("out.flush()" in p and "matchesSource(documentUri, sourceLength, sourceHash, access)" in p,
            "la publication est relue après fermeture et contrôlée en longueur et SHA-256")
    require("MessageDigest.getInstance(\"SHA-256\")" in p and "total == expectedLength" in p,
            "la vérification porte sur tous les octets et la longueur exacte")
    require("AtomicFile" in s and "out.getFD().sync()" in s
            and "RecoveryPublicationRegistry.parse" in s,
            "le registre transactionnel est atomique, synchronisé et parsé strictement")
    require("DocumentsContract.buildChildDocumentsUriUsingTree" in a
            and "COLUMN_DISPLAY_NAME" in a and 'openOutputStream(document, \"wt\")' in a
            and "openInputStream(document)" in a,
            "l'adaptateur SAF liste avant création, tronque à l'écriture et relit la destination")
    require("permission.isReadPermission()" in a and "permission.isWritePermission()" in a,
            "la relecture exige une permission SAF persistée en lecture et écriture")
    require("MAX_SCANNED_CHILDREN = 20_000" in a and "Dossier SAF trop volumineux" in a,
            "la recherche SAF est bornée et refuse les listes incomplètes")
    require("GpxRecoveryRetryScheduler.enqueue(this)" in activity
            and "observeRecoveryRetryWork()" in activity
            and "GpxRecoveryWorkflow.recoverAndPublish(" in service,
            "MainActivity utilise la reprise WorkManager et TrackingService conserve le workflow de secours")
    require("isJournalAcknowledgementPending()" in service
            and "isExternalPublicationPending()" in service
            and "isRecoveredLocallyOnly()" in service
            and "KEY_WAITING_PERMISSION" in read("worker")
            and "KEY_INTERVENTION" in read("worker")
            and "gpx_recovery_retry_last_result" in activity,
            "les statuts distinguent attente SAF, journal à acquitter, copie privée et intervention")
    require("resumesAfterDocumentCreatedButUriNotCommitted" in tests
            and "resumesAfterPartialWrite" in tests
            and "keepsTransactionWhenReadBackDiffers" in tests
            and "handlesDeterministicNameCollisionWithoutOverwritingAmbiguousFiles" in tests
            and "refusesSingleForeignDocumentWithReservedName" in tests
            and "retargetsAnUnfinishedTransactionToNewChosenFolder" in tests,
            "les tests couvrent crash de création, écriture partielle, vérification, collision et changement de dossier")
    require("round-trip canonique" in registry_tests and "VERIFIED" in registry_tests
            and "registre atteint sa limite" in registry_tests,
            "le modèle teste sérialisation, progression d'état, corruption et capacité")
    require("Verify transactional recovered GPX SAF publication" in ci,
            "GitHub Actions lance le contrôle structurel de publication SAF")
    print("OK : invariants structurels du LOT 8C-R2C.2 vérifiés.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
