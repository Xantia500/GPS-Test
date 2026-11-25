#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Contrat structurel de l'écran consultatif des copies GPX récupérées."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/example/gpstest"
RES = ROOT / "app/src/main/res"
ACTIVITY = JAVA / "RecoveryManagementActivity.java"
PRESENTATION = JAVA / "RecoveryRetentionPresentation.java"
REGISTRY = JAVA / "RecoveryRetentionRegistry.java"
POLICY = JAVA / "RecoveryRetentionPolicy.java"
SETTINGS = JAVA / "SettingsActivity.java"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
LAYOUT = RES / "layout/activity_recovery_management.xml"
STRINGS = RES / "values/strings.xml"


def require(condition: bool, label: str) -> None:
    if not condition:
        raise SystemExit("ERREUR — " + label)
    print("OK — " + label)


def read(path: Path) -> str:
    if not path.is_file():
        raise SystemExit("ERREUR — fichier absent : " + str(path.relative_to(ROOT)))
    return path.read_text(encoding="utf-8")


def main() -> int:
    activity = read(ACTIVITY)
    presentation = read(PRESENTATION)
    settings = read(SETTINGS)
    manifest_text = read(MANIFEST)
    layout_text = read(LAYOUT)
    strings_text = read(STRINGS)
    registry = read(REGISTRY)
    policy = read(POLICY)

    manifest = ET.fromstring(manifest_text)
    android = "{http://schemas.android.com/apk/res/android}"
    app = manifest.find("application")
    require(app is not None, "application présente dans le manifeste")
    recovery_activity = None if app is None else next(
        (node for node in app.findall("activity")
         if node.attrib.get(android + "name") == ".RecoveryManagementActivity"), None)
    require(recovery_activity is not None, "RecoveryManagementActivity déclarée au manifeste")
    if recovery_activity is not None:
        require(recovery_activity.attrib.get(android + "exported") == "false",
                "écran de gestion non exporté")
        require(recovery_activity.attrib.get(android + "parentActivityName") == ".SettingsActivity",
                "retour hiérarchique vers les réglages")

    require("btnManageRecovered" in settings
            and "RecoveryManagementActivity.class" in settings,
            "écran accessible depuis Réglages / Données enregistrées")
    require("@+id/btnManageRecovered" in read(RES / "layout/activity_settings.xml"),
            "bouton de gestion présent dans le bloc Données enregistrées")
    require("R.layout.activity_recovery_management" in activity
            and "setContentView(R.layout.activity_recovery_management)" in activity,
            "l'activité utilise son layout dédié")
    require("onResume()" in activity and "refreshRegistry()" in activity,
            "le registre est actualisé au retour dans l'écran")
    require("io.execute" in activity and "new RecoveryRetentionStore(app).read()" in activity,
            "la lecture du registre est effectuée sur un worker et non sur le thread UI")
    require("loading.compareAndSet(false, true)" in activity,
            "les chargements simultanés sont coalescés")
    require("RecoveryRetentionPresentation.buildRows" in activity,
            "l'écran utilise le modèle de présentation testé")
    require("SafRecoveryAccess" in activity and "hasReadWriteAccess" in activity,
            "l'autorisation SAF persistante est présentée pour chaque destination")

    metadata = ("entry.displayName", "entry.recoveredName", "entry.sourceName",
                "entry.publishedAtMs", "entry.recoveredLengthBytes", "entry.sha256",
                "entry.treeUri", "entry.documentUri", "entry.publicationId")
    for field in metadata:
        require(field in activity, "métadonnée affichée : " + field)

    require("PRESENT_LENGTH_MATCHES" in presentation
            and "LENGTH_MISMATCH" in presentation
            and "getCanonicalFile()" in presentation,
            "contrôle local non destructif : confinement canonique et longueur")
    require("Collections.unmodifiableList" in presentation,
            "la liste de présentation est immuable")
    require("Publication SAF : vérifiée à la publication" in strings_text
            and "Aucune relecture SAF en direct" in strings_text,
            "l'écran distingue preuve historique et vérification SAF en direct")
    require("setTextIsSelectable(true)" in activity,
            "URI et empreintes peuvent être sélectionnées et copiées par l'utilisateur")
    require("retention_review_due" in activity and "retention_review_after" in activity
            and "retention_clock_warning" in activity,
            "l'écran distingue échéance, durée restante et horloge incohérente")

    # Aucun mécanisme destructif ne doit être présent dans ce lot d'interface.
    for token in (".delete(", "deleteDocument(", "deleteFile(", "removeIfUnchanged(",
                  "automaticDeletionEnabled(", "openWrite("):
        require(token not in activity and token not in presentation,
                "aucune action destructive ou SAF en écriture dans l'écran : " + token)
    require("automaticDeletionEnabled() { return false; }" in policy,
            "la politique conserve la suppression automatique désactivée")
    require("removeIfUnchanged" in registry,
            "le registre conserve son retrait conditionnel réservé au futur lot explicite")

    root = ET.parse(LAYOUT).getroot()
    layout_text = LAYOUT.read_text(encoding="utf-8")
    for view_id in ("tvRetentionSummary", "tvRetentionStatus", "tvRetentionEmpty",
                    "pbRetentionLoading", "containerRetentionEntries", "btnRefreshRetention",
                    "btnCloseRetention"):
        require("@+id/" + view_id in layout_text, "vue de gestion présente : " + view_id)

    # Références de ressources principales.
    require("retention_age_days" in strings_text and "<plurals" in strings_text,
            "pluriel français pour l'ancienneté déclaré")
    require("retention_screen_footer" in strings_text
            and "aucune suppression" in strings_text,
            "avertissement de non-destruction affiché à l'utilisateur")

    print("OK : contrat structurel du LOT 8C-R2C.5 vérifié.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
