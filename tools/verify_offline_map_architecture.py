#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Contrat statique : aucun moteur/endpoint de tuiles dans le code Android actif."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
ACTIVE = ROOT / "app" / "src" / "main"
PATTERNS = {
    "import osmdroid": re.compile(r"\bimport\s+org\.osmdroid\b"),
    "classe osmdroid": re.compile(r"\borg\.osmdroid\."),
    "Mapnik / TileSourceFactory": re.compile(r"\bTileSourceFactory\b|\bMAPNIK\b"),
    "activation réseau MapView": re.compile(r"\bsetUseDataConnection\s*\("),
    "ancien moteur osmdroid": re.compile(r"\bOsmdroidMapRenderer\b"),
}
failures = []
scanned = 0
active_files = [p for p in ACTIVE.rglob("*")
                if p.is_file() and p.suffix.lower() in {".java", ".xml", ".gradle"}]
active_files.append(ROOT / "app" / "build.gradle")
for path in active_files:
    scanned += 1
    text = path.read_text(encoding="utf-8", errors="replace")
    for label, pattern in PATTERNS.items():
        if pattern.search(text):
            failures.append(f"{path.relative_to(ROOT)} : {label}")

map_activity = (ACTIVE / "java/com/example/gpstest/MapActivity.java").read_text(encoding="utf-8")
offline_activity = (ACTIVE / "java/com/example/gpstest/OfflineMapsActivity.java").read_text(encoding="utf-8")
checks = {
    "repli local explicit": "new OfflinePlaceholderMapRenderer(this)" in map_activity,
    "sélecteur géographique local": "new MapRegionSelectionView(this)" in offline_activity,
}
for label, passed in checks.items():
    if not passed:
        failures.append(f"contrat manquant : {label}")

if failures:
    print("ÉCHEC - architecture cartographique locale")
    for item in failures:
        print(" - " + item)
    sys.exit(1)

print(f"OK - {scanned} fichiers Android analysés ; aucun moteur ou fournisseur de tuiles raster ; repli local et sélecteur local présents.")
