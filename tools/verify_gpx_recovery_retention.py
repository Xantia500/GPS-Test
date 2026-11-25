#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Vérifie les invariants conservateurs de la politique de rétention GPX."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/example/gpstest"
FILES = {
    "workflow": JAVA / "GpxRecoveryWorkflow.java",
    "worker": JAVA / "GpxRecoveryRetryWorker.java",
    "registry": JAVA / "RecoveryRetentionRegistry.java",
    "store": JAVA / "RecoveryRetentionStore.java",
    "policy": JAVA / "RecoveryRetentionPolicy.java",
    "activity": JAVA / "MainActivity.java",
    "registry_test": ROOT / "tests/RecoveryRetentionRegistryTest.java",
    "policy_test": ROOT / "tests/RecoveryRetentionPolicyTest.java",
    "store_test": ROOT / "tests/RecoveryRetentionStoreTest.java",
    "ci": ROOT / ".github/workflows/android-build.yml",
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
    workflow, worker, registry, store, policy, activity = (
        read(k) for k in ("workflow", "worker", "registry", "store", "policy", "activity")
    )
    registry_test, policy_test, store_test, ci = (
        read(k) for k in ("registry_test", "policy_test", "store_test", "ci")
    )

    published_branch = workflow[workflow.index("if (!published.isSuccess())"):]
    require(published_branch.index("SessionStore.clearRecoveryIfUnchanged(context, expected)")
            < published_branch.index("RecoveryRetentionRegistry.Entry.published("),
            "une entrée de rétention n'est créée qu'après acquittement exact du journal")
    require("published.documentUri" in workflow and "published.sha256" in workflow
            and "published.bytes" in workflow and "tree.toString()" in workflow,
            "le registre conserve URI SAF, digest et longueur de la copie vérifiée")
    require("sera conservée sans limite" in workflow,
            "un échec de registre conserve la copie locale sans la rendre purgeable")

    require("MIN_RETENTION_DAYS = 30" in policy
            and "READY_FOR_EXPLICIT_REVIEW" in policy,
            "la durée minimale de conservation est de 30 jours, puis examen manuel")
    require("SOFT_QUOTA_BYTES = 512L * 1024L * 1024L" in policy
            and "softQuotaExceeded" in policy,
            "le quota de 512 Mio est seulement indicatif")
    require("automaticDeletionEnabled() { return false; }" in policy,
            "aucune suppression automatique n'est autorisée dans ce lot")
    require("CLOCK_MOVED_BACKWARDS" in policy and "Long.MAX_VALUE - MIN_RETENTION_MS" in policy,
            "horloge incohérente et overflow sont traités conservativement")
    require("safeRecoveredLeaf" in registry and "!e.sourceName.equals(e.recoveredName)" in registry,
            "seules les copies _RECUPEREE distinctes de la source peuvent être suivies")
    require("MAX_ENTRIES = 128" in registry and "addOrVerify" in registry,
            "registre borné à 128 entrées sans éviction et sans rajeunissement")
    require("AtomicFile" in store and "out.getFD().sync()" in store
            and "RecoveryRetentionRegistry.parse" in store,
            "registre de conservation écrit atomiquement et parsé strictement")
    require("registre de conservation n'a pas pu être mis à jour" in workflow,
            "l'échec du suivi de rétention ne compromet pas la copie publiée")
    require("Conservation des copies publiées" in worker
            and "Seuil indicatif de stockage" in worker
            and "aucune purge n'est activée" in worker,
            "le worker expose les copies suivies, les échéances et l'avertissement de quota")
    require("retentionNotice" in activity and "Conservation des copies publiées" in activity,
            "le bandeau de reprise reste visible pour présenter le résumé de conservation")
    require("round-trip du registre" in registry_test and "sans éviction" in registry_test
            and "registre corrompu restent préservés" in store_test,
            "les tests couvrent round-trip, limite et conservation après corruption")
    require("durée minimale fixée à 30 jours" in policy_test
            and "exactement ne dépasse pas" in policy_test
            and "aucune suppression automatique" in policy_test,
            "les tests couvrent échéance, horloge, quota et absence de purge")
    require("Verify conservative GPX recovery retention policy" in ci
            and "verify_gpx_recovery_retention.py" in ci,
            "la CI exécute le contrôle structurel de rétention")

    # Garde forte : aucun code de ce lot ne peut supprimer un fichier.
    for key in ("registry", "store", "policy"):
        require(".delete(" not in read(key),
                f"aucun appel de suppression de fichier dans {key}")
    print("OK : invariants structurels du LOT 8C-R2C.4 vérifiés.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
