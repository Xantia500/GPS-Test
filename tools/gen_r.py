#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Régénère tools/rsim/com/example/gpstest/R.java à partir des ressources réelles.

POURQUOI CE SCRIPT EXISTE :
  R.java est le seul fichier Java du projet qu'on n'écrit pas à la main.
  Android Studio le génère à partir des XML. Ici, sans Gradle, il faut le
  fabriquer — et le fabriquer À PARTIR DES XML, jamais à la main. La leçon
  a déjà coûté cher une fois : un R.java édité par insertion de blocs a
  désynchronisé les identifiants et cassé la compilation de façon opaque.

  Ce script est la version « exécutable » de cette leçon : une seule
  source de vérité (les XML), un seul sens de génération, zéro édition
  manuelle.

USAGE :  python3 tools/gen_r.py
"""
import os
import re
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
OUT = os.path.join(ROOT, "tools", "rsim", "com", "example", "gpstest", "R.java")
OUT2 = os.path.join(ROOT, "tools", "stubs", "com", "example", "gpstest", "R.java")

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def collect():
    ids, strings, layouts, colors, styles = set(), set(), set(), set(), set()
    # Deux natures de drawable, et c'est important :
    #   - les PNG (mipmap/drawable) : ressource binaire, pas de XML à lire ;
    #   - les XML (drawable/*.xml) : une forme, un sélecteur, une couleur.
    # Un <shape> placé dans res/drawable/ produit une entrée R.drawable.
    drawables, mipmaps = set(), set()

    for dirpath, _, files in os.walk(RES):
        kind = os.path.basename(dirpath)
        for fn in files:
            # Les mipmaps sont des PNG : on les compte AVANT le filtre .xml,
            # sinon l'icône de l'application n'apparaît jamais dans R.mipmap
            # et le manifest (@mipmap/ic_launcher) ne serait pas vérifiable.
            if kind.startswith("mipmap"):
                mipmaps.add(os.path.splitext(fn)[0])
                continue
            if not fn.endswith(".xml"):
                continue
            path = os.path.join(dirpath, fn)
            stem = fn[:-4]
            if kind.startswith("layout"):
                layouts.add(stem)
            elif kind.startswith("drawable"):
                drawables.add(stem)
            elif kind.startswith("mipmap"):
                mipmaps.add(stem)
            elif kind.startswith("values"):
                if stem == "strings":
                    for m in re.finditer(r'<string\s+name="([^"]+)"', open(path, encoding="utf-8").read()):
                        strings.add(m.group(1))
                elif stem == "colors":
                    for m in re.finditer(r'<color\s+name="([^"]+)"', open(path, encoding="utf-8").read()):
                        colors.add(m.group(1))
                elif stem in ("styles", "themes"):
                    for m in re.finditer(r'<style\s+name="([^"]+)"', open(path, encoding="utf-8").read()):
                        styles.add(m.group(1))
            # @+id dans n'importe quel XML
            try:
                txt = open(path, encoding="utf-8").read()
            except Exception:
                continue
            for m in re.finditer(r'@\+id/([A-Za-z0-9_]+)', txt):
                ids.add(m.group(1))
    return ids, strings, layouts, colors, styles, drawables, mipmaps


def sanitize_styles(styles):
    """Style.Dark -> Style_Dark, Theme.GpsTest -> Theme_GpsTest (règles aapt2)."""
    return {s.replace(".", "_"): s for s in styles}


def emit(ids, strings, layouts, colors, styles, drawables, mipmaps):
    L = []
    L.append("package com.example.gpstest;")
    L.append("")
    L.append("/**")
    L.append(" * R factice : permet de compiler tout le projet hors Android Studio.")
    L.append(" * GÉNÉRÉ par tools/gen_r.py à partir de app/src/main/res — NE PAS ÉDITER.")
    L.append(" */")
    L.append("public final class R {")

    def block(cls, names):
        L.append("    public static final class %s {" % cls)
        for i, n in enumerate(sorted(names), 1):
            L.append("        public static final int %s = %d;" % (n, i))
        L.append("    }")

    block("layout", layouts)
    block("id", ids)
    block("string", strings)
    if colors:
        block("color", colors)
    # R.drawable : les formes XML ET les variantes mipmap (ic_launcher).
    # Sans ce bloc, @drawable/list_bg compile en XML mais toute référence
    # Java R.drawable.list_bg échouerait — ou pire, passerait avec un id
    # fantôme pointant sur rien à l'exécution.
    all_drawables = set(drawables) | set(mipmaps)
    if all_drawables:
        block("drawable", all_drawables)
    if styles:
        L.append("    public static final class style {")
        for i, (safe, _orig) in enumerate(sorted(sanitize_styles(styles).items()), 1):
            L.append("        public static final int %s = %d;" % (safe, i))
        L.append("    }")
    if mipmaps:
        block("mipmap", mipmaps)

    L.append("}")
    return "\n".join(L) + "\n"


def main():
    ids, strings, layouts, colors, styles, drawables, mipmaps = collect()
    src = emit(ids, strings, layouts, colors, styles, drawables, mipmaps)
    for path in (OUT, OUT2):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(src)
    print("R.java régénéré : %d ids, %d strings, %d layouts, %d couleurs, "
          "%d styles, %d drawables, %d mipmaps"
          % (len(ids), len(strings), len(layouts), len(colors), len(styles),
             len(drawables), len(mipmaps)))


if __name__ == "__main__":
    main()
