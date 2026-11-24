#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Static guards for conservative checkpoint-based GPX recovery."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RECOVERY = ROOT / "app/src/main/java/com/example/gpstest/GpxRecovery.java"
ACTIVITY = ROOT / "app/src/main/java/com/example/gpstest/MainActivity.java"
SERVICE = ROOT / "app/src/main/java/com/example/gpstest/TrackingService.java"
TEST = ROOT / "tests/GpxRecoveryTest.java"
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
    workflow = read(WORKFLOW)

    require("copyPrefix(source, temp, checkpoint)" in recovery,
            "la récupération copie le préfixe exact du checkpoint")
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
    require("GpxRecovery.recover(getExternalFilesDir(null)" in activity,
            "MainActivity déclenche la récupération sur son worker de démarrage")
    require("SessionStore.clearIfSameSession(MainActivity.this, previous.sessionId)" in activity,
            "le journal est effacé uniquement si l'identité de session n'a pas changé")
    require('new Thread(() -> {' in service and '"gpx-session-recovery"' in service
            and "GpxRecovery.recover(" in service,
            "le service possède un repli de récupération asynchrone")
    require("SessionStore.clearIfSameSession(TrackingService.this, previous.sessionId)" in service,
            "le service ne peut pas effacer le journal d'une nouvelle session")
    require("rejectsAMidPointPrefixWithoutChangingTheSource" in tests
            and "reusesAnIdenticalRecoveredCopyAndNeverOverwritesAConflict" in tests,
            "les tests couvrent les coupures au milieu d'un point et les collisions de noms")
    require("Verify interrupted GPX recovery contract" in workflow,
            "GitHub Actions exécute le contrôle structurel de récupération")
    print("OK : invariants structurels du LOT 8C-R2B.3 vérifiés.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
