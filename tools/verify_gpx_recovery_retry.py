#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Gardes statiques du workflow durable de reprise et de relance des GPX."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/example/gpstest"
FILES = {
    "worker": JAVA / "GpxRecoveryRetryWorker.java",
    "scheduler": JAVA / "GpxRecoveryRetryScheduler.java",
    "policy": JAVA / "GpxRecoveryRetryPolicy.java",
    "activity": JAVA / "MainActivity.java",
    "store": JAVA / "SessionStore.java",
    "workflow": JAVA / "GpxRecoveryWorkflow.java",
    "layout": ROOT / "app/src/main/res/layout/activity_main.xml",
    "strings": ROOT / "app/src/main/res/values/strings.xml",
    "gradle": ROOT / "app/build.gradle",
    "ci": ROOT / ".github/workflows/android-build.yml",
    "policy_test": ROOT / "tests/GpxRecoveryRetryPolicyTest.java",
    "store_test": ROOT / "tests/SessionStoreTest.java",
}


def read(key: str) -> str:
    path = FILES[key]
    if not path.is_file():
        raise SystemExit(f"ERREUR — fichier absent : {path.relative_to(ROOT)}")
    return path.read_text(encoding="utf-8")


def require(condition: bool, label: str) -> None:
    if not condition:
        raise SystemExit(f"ERREUR — {label}")
    print(f"OK — {label}")


def main() -> int:
    worker, scheduler, policy, activity, store, workflow = (
        read(k) for k in ("worker", "scheduler", "policy", "activity", "store", "workflow")
    )
    layout, strings, gradle, ci = (read(k) for k in ("layout", "strings", "gradle", "ci"))
    policy_test, store_test = read("policy_test"), read("store_test")

    require("androidx.work:work-runtime:2.12.0" in gradle
            and "extends Worker" in worker
            and "OneTimeWorkRequest.Builder(GpxRecoveryRetryWorker.class)" in scheduler,
            "le moteur de reprise utilise WorkManager déjà présent dans le projet")
    require("enqueueUniqueWork" in scheduler and "ExistingWorkPolicy.KEEP" in scheduler
            and "BackoffPolicy.EXPONENTIAL" in scheduler,
            "les tâches sont uniques et utilisent un backoff exponentiel persistant")
    require("MAX_AUTOMATIC_ATTEMPTS = 5" in policy
            and "getRunAttemptCount()" in worker
            and "Result.retry()" in worker,
            "les erreurs transitoires sont automatiquement réessayées avec une limite")
    require("PENDING_PERMISSION" in policy and "NO_PERMISSION" in policy
            and "WAIT_FOR_USER" in policy,
            "une permission SAF absente attend une action de l'utilisateur")
    require("PUBLICATION_CONFLICT" in policy and "AMBIGUOUS_DOCUMENTS" in policy
            and "SOURCE_CHANGED" in policy and "REGISTRY_ERROR" in policy
            and "NEEDS_INTERVENTION" in policy,
            "un conflit, une source modifiée ou un registre illisible ne déclenche pas de retry aveugle")
    require("TrackingState.get().isUserWantsTracking()" in worker
            and "GpxRecoveryWorkflow.recoverAndPublish(" in worker,
            "le worker refuse d'intervenir pendant le suivi et réutilise le workflow commun")
    require("SessionStore.readRecoveriesDetailed(context)" in worker
            and "RecoveryPublicationStore(context).read()" in worker,
            "les deux registres sont inspectés avec leurs diagnostics")
    require("Transaction SAF orpheline" in worker and "intervention++" in worker,
            "une transaction SAF sans snapshot de récupération est signalée comme intervention")
    require("KEY_LOCAL_ONLY" in worker and "KEY_WAITING_PERMISSION" in worker
            and "KEY_INTERVENTION" in worker and "KEY_NOTICE" in worker,
            "le worker expose des résultats distincts : privé, permission, intervention et notice")
    require("GpxRecoveryRetryScheduler.enqueue(this)" in activity
            and "refreshRecoveryRetryStatus()" in activity,
            "l'Activity programme la reprise au retour au premier plan")
    require("boolean justStopped = lastRecoveryTrackingWanted && !trackingWanted" in activity
            and "GpxRecoveryRetryScheduler.enqueue(getApplicationContext())" in activity,
            "une reprise ignorée pendant le GNSS est reprogrammée après l'arrêt du suivi")
    require("getWorkInfosForUniqueWorkLiveData" in activity
            and "GpxRecoveryRetryWorker.KEY_INTERVENTION" in activity
            and "gpx_recovery_retry_last_result" in activity,
            "l'interface observe le travail et distingue les résultats de reprise")
    require("btnRetryRecovery.setOnClickListener" in activity
            and "gpx_recovery_retry_after_tracking" in activity
            and "GpxRecoveryRetryScheduler.enqueue(this)" in activity,
            "la relance manuelle est disponible et protégée pendant le suivi")
    require("layoutRecoveryRetry" in layout and "btnRetryRecovery" in layout
            and "tvRecoveryRetrySummary" in layout,
            "le bandeau de reprise et son bouton sont présents dans l'écran principal")
    require("gpx_recovery_retry_counts" in strings
            and "gpx_recovery_retry_last_result" in strings
            and "gpx_recovery_registry_unreadable" in strings
            and "gpx_recovery_journal_unreadable" in strings
            and "gpx_recovery_state_not_synchronized" in strings,
            "les états utilisateur sont localisés et les corruptions restent explicites")
    require("readRecoveriesDetailed(Context ctx)" in store
            and "registryReadable" in store and "journalReadable" in store
            and "Le registre des récupérations est illisible" in store
            and "sa copie dans le registre n'a pas pu être synchronisée" in store,
            "SessionStore distingue l'absence d'état, la corruption et l'échec de synchronisation")
    require("registre absent interprété comme un registre vide valide" in store_test
            and "la lecture détaillée distingue un registre présent mais illisible" in store_test,
            "les diagnostics de registre vide/corrompu sont testés")
    require("NO_PERMISSION" in policy_test and "REGISTRY_ERROR" in policy_test
            and "MAX_AUTOMATIC_ATTEMPTS" in policy_test,
            "les classes de reprise et la limite sont couvertes par les tests JVM")
    require("Verify persistent GPX recovery retry workflow" in ci
            and "verify_gpx_recovery_retry.py" in ci,
            "GitHub Actions exécute les invariants de reprise persistante")
    require("clearRecoveryIfUnchanged" in workflow and "recoveredFile" in workflow,
            "la reprise n'acquitte le snapshot que par le workflow conservateur existant")
    print("OK : invariants structurels du LOT 8C-R2C.3 vérifiés.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
