#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Contrôles statiques reproductibles du projet Android GPS-Test.

Ce script ne remplace ni AAPT2/Gradle, ni Android Lint, ni un test appareil.
Il vérifie les invariants du manifeste, des ressources, du SDK et des versions.
"""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app"
MAIN = APP / "src/main"
JAVA = MAIN / "java/com/example/gpstest"
RES = MAIN / "res"
ANDROID = "{http://schemas.android.com/apk/res/android}"
errors = []
notes = []


def require(condition, message):
    if not condition:
        errors.append(message)


def local_name(tag):
    return tag.rsplit("}", 1)[-1]

# 1) XML bien formés.
xml_files = sorted(p for p in MAIN.rglob("*.xml") if p.is_file())
xml_trees = {}
for path in xml_files:
    try:
        xml_trees[path] = ET.parse(path).getroot()
    except ET.ParseError as exc:
        errors.append(f"XML invalide : {path.relative_to(ROOT)} : {exc}")

# 2) Inventaire des ressources Android.
resources = {}
ids = set()
for path, root in xml_trees.items():
    rel = path.relative_to(RES) if path.is_relative_to(RES) else None
    if rel:
        folder = rel.parts[0].split("-", 1)[0]
        name = path.stem
        if folder in {"layout", "drawable", "mipmap", "xml", "menu", "anim", "animator", "color", "font", "raw"}:
            resources.setdefault(folder, set()).add(name)
    for node in root.iter():
        for key, value in node.attrib.items():
            if key == ANDROID + "id" and value.startswith("@+id/"):
                ids.add(value.split("/", 1)[1])
            for m in re.finditer(r"@\+id/([A-Za-z0-9_]+)", value):
                ids.add(m.group(1))
        if node.text:
            for m in re.finditer(r"@\+id/([A-Za-z0-9_]+)", node.text):
                ids.add(m.group(1))

values = {"string": set(), "color": set(), "style": set(), "dimen": set(),
          "integer": set(), "bool": set(), "array": set(), "plurals": set(), "attr": set()}
for path, root in xml_trees.items():
    if not path.is_relative_to(RES) or not path.parent.name.startswith("values"):
        continue
    for node in root:
        name = node.attrib.get("name")
        typ = local_name(node.tag)
        if name and typ in values:
            values[typ].add(name)
resources.update(values)
resources["id"] = ids

# Binary mipmaps/drawables.
for folder in RES.glob("mipmap-*/*"):
    if folder.is_file():
        resources.setdefault("mipmap", set()).add(folder.stem)
for folder in RES.glob("drawable-*/*"):
    if folder.is_file():
        resources.setdefault("drawable", set()).add(folder.stem)

# 3) Contrôle des références de ressources dans XML.
resource_ref_pattern = re.compile(r"(?<![A-Za-z0-9_])@(?!android:|\*android:)(?:\+)?([a-zA-Z_][\w]*)/([A-Za-z0-9_.]+)")
for path in xml_files:
    try:
        text = path.read_text(encoding="utf-8")
    except OSError:
        continue
    for typ, name in resource_ref_pattern.findall(text):
        if typ in resources and name not in resources[typ]:
            errors.append(f"Référence XML sans ressource @{typ}/{name} dans {path.relative_to(ROOT)}")

# 4) Contrôle des références R.* dans le Java.
java_files = sorted(JAVA.glob("*.java"))
r_ref_pattern = re.compile(r"(?<![A-Za-z0-9_.])R\.(id|layout|string|drawable|mipmap|color|style|xml|menu|anim|animator|dimen|integer|bool|array|plurals)\.([A-Za-z0-9_]+)")
for path in java_files:
    text = path.read_text(encoding="utf-8", errors="replace")
    for typ, name in r_ref_pattern.findall(text):
        if name not in resources.get(typ, set()) and not (typ == "style" and name.replace("_", ".") in resources.get(typ, set())):
            errors.append(f"Référence Java sans ressource R.{typ}.{name} dans {path.relative_to(ROOT)}")

# 5) Manifest : composants, permissions et foreground service.
manifest_path = MAIN / "AndroidManifest.xml"
manifest = xml_trees.get(manifest_path)
require(manifest is not None, "AndroidManifest.xml absent ou illisible")
if manifest is not None:
    permission_names = {n.attrib.get(ANDROID + "name", "") for n in manifest.findall("uses-permission")}
    for perm in ("android.permission.ACCESS_FINE_LOCATION",
                 "android.permission.FOREGROUND_SERVICE",
                 "android.permission.FOREGROUND_SERVICE_LOCATION",
                 "android.permission.POST_NOTIFICATIONS",
                 "android.permission.INTERNET"):
        require(perm in permission_names, f"Permission manifeste manquante : {perm}")
    app_node = manifest.find("application")
    require(app_node is not None, "Balise application absente")
    if app_node is not None:
        for tag in ("activity", "service", "receiver", "provider"):
            for node in app_node.findall(tag):
                raw = node.attrib.get(ANDROID + "name", "")
                if raw.startswith("."):
                    cls = raw[1:]
                elif raw and "." not in raw:
                    cls = raw
                else:
                    continue
                source = JAVA / (cls.split(".")[-1] + ".java")
                if tag in ("activity", "service", "receiver", "provider"):
                    require(source.exists(), f"Composant manifeste sans source Java : {raw}")
        service = app_node.find("service")
        require(service is not None, "Service de suivi absent du manifeste")
        if service is not None:
            require(service.attrib.get(ANDROID + "foregroundServiceType") == "location",
                    "TrackingService doit déclarer foregroundServiceType=location")
            require(service.attrib.get(ANDROID + "exported") == "false",
                    "TrackingService doit rester non exporté")

# 6) Build configuration / compatibility.
root_build = (ROOT / "build.gradle").read_text(encoding="utf-8")
app_build = (APP / "build.gradle").read_text(encoding="utf-8")
require("com.android.application' version '8.13.2'" in root_build,
        "AGP attendu 8.13.2 (réviser ce contrôle si la version change explicitement)")
require(bool(re.search(r"compileSdk\s+36\b", app_build)), "compileSdk doit être 36")
require(bool(re.search(r"minSdk\s+24\b", app_build)), "minSdk doit être 24")
require(bool(re.search(r"targetSdk\s+36\b", app_build)), "targetSdk doit être 36")
require("JavaVersion.VERSION_17" in app_build, "La compatibilité du bytecode Java 17 doit être déclarée")
require("androidx.work:work-runtime:2.12.0" in app_build,
        "WorkManager 2.12.0 attendu : sa minSdk 24 correspond au projet")
require("org.mapsforge:mapsforge-map-android:0.25.0" in app_build,
        "Dépendance Mapsforge Android épinglée non trouvée")
# Le helper de zoom travaille en pixels entiers. dp() produit un float :
# convertir explicitement évite une erreur Java float -> int dans le renderer Android.
map_renderer = (JAVA / "MapsforgeMapRenderer.java").read_text(encoding="utf-8")
require("selectedTileSize, Math.round(dp(32))" in map_renderer,
        "Le padding du cadrage initial Mapsforge doit convertir dp(float) en int")
require("tileSize, Math.round(dp(48))" in map_renderer,
        "Le padding du cadrage des traces Mapsforge doit convertir dp(float) en int")

# 7) Activités : helper edge-to-edge présent sur tous les écrans déclarés.
if manifest is not None:
    app_node = manifest.find("application")
    if app_node is not None:
        for node in app_node.findall("activity"):
            raw = node.attrib.get(ANDROID + "name", "")
            cls = raw.rsplit(".", 1)[-1]
            source = JAVA / f"{cls}.java"
            if source.exists():
                body = source.read_text(encoding="utf-8", errors="replace")
                require("EdgeToEdge.apply(this)" in body,
                        f"EdgeToEdge.apply(this) absent dans {source.name}")

# 8) Niveau d'API : gardes explicites des appels connus à API récentes.
tracking = (JAVA / "TrackingService.java").read_text(encoding="utf-8")
edge = (JAVA / "EdgeToEdge.java").read_text(encoding="utf-8")
require("if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)" in tracking
        and "new android.location.LocationRequest.Builder(locationIntervalMs)" in tracking,
        "LocationRequest.Builder API 31 doit être gardé par API S")
require("if (Build.VERSION.SDK_INT >= 34)" in tracking
        and "getGnssSignalTypes()" in tracking,
        "GnssCapabilities.getGnssSignalTypes API 34 doit être gardé")
require("if (Build.VERSION.SDK_INT >= 29)" in edge
        and "setStatusBarContrastEnforced" in edge,
        "Window contrast enforcement API 29 doit être gardé")
# LocationListener.onStatusChanged est encore abstraite sur API 24–29.
require("public void onStatusChanged(@NonNull String provider, int status" in tracking
        and "@SuppressWarnings(\"deprecation\")" in tracking,
        "LocationListener.onStatusChanged doit être implémenté pour minSdk 24")
for legacy_callback in ("public void onProviderEnabled(@NonNull String provider)",
                        "public void onProviderDisabled(@NonNull String provider)"):
    require(legacy_callback in tracking,
            f"LocationListener doit implémenter {legacy_callback} pour minSdk 24")
# Les callbacks enregistrés au framework doivent capturer un jeton indépendant
# afin qu'un callback déjà en file ne contamine pas le nouvel abonnement.
for token in ("createRegisteredLocationListener()", "createRegisteredGnssCallback()",
              "createRegisteredNmeaListener()", "locationCallbackGeneration.isCurrent(generation)",
              "statusCallbackGeneration.isCurrent(generation)",
              "nmeaCallbackGeneration.isCurrent(generation)"):
    require(token in tracking, f"Protection de génération des callbacks absente : {token}")
require("registeredLocationListener = callback" in tracking
        and "removeUpdates(registeredLocationListener)" in tracking,
        "Le listener exact enregistré doit être conservé pour le désabonnement")

# 9) Hors ligne : le contrôle spécialisé est exécuté séparément ; ce script veille aussi
# à ce que le build Gradle ne réintroduise pas osmdroid.
require("osmdroid" not in app_build.lower(), "Dépendance osmdroid réintroduite dans Gradle")

# 10) Le projet n'embarque pas encore le Wrapper : pas d'affirmation trompeuse.
wrapper_props = ROOT / "gradle/wrapper/gradle-wrapper.properties"
wrapper_jar = ROOT / "gradle/wrapper/gradle-wrapper.jar"
if not (wrapper_props.exists() and wrapper_jar.exists()):
    notes.append("Gradle Wrapper absent : utiliser la CI ou générer le wrapper localement avec Gradle 8.13.")

if errors:
    print(f"ÉCHEC — {len(errors)} problème(s), {len(xml_files)} XML analysés, {len(java_files)} Java examinés")
    for item in errors:
        print(" - " + item)
    for note in notes:
        print("NOTE : " + note)
    sys.exit(1)

print(f"OK — {len(xml_files)} XML analysés ; {len(java_files)} sources Java auditées ; ressources R cohérentes ; manifeste/permissions/FGS cohérents ; SDK 36/min 24 ; Edge-to-Edge présent.")
for note in notes:
    print("NOTE : " + note)
