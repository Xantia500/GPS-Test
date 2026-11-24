#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Vérifie les invariants structurels du LOT 8C-R2B.1.

Ce contrôle ne remplace pas les tests Android sur appareil : il empêche surtout
une régression évidente qui réintroduirait l'ouverture/écriture de l'en-tête sur
le thread appelant ou qui déplacerait l'ouverture après les points dans la FIFO.
"""
from __future__ import annotations

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
SERVICE = ROOT / "app/src/main/java/com/example/gpstest/TrackingService.java"
STATE = ROOT / "app/src/main/java/com/example/gpstest/GpxSegmentIoState.java"


def method_body(source: str, signature: str) -> str:
    start = source.find(signature)
    if start < 0:
        raise ValueError(f"méthode absente : {signature}")
    opening = source.find("{", start)
    if opening < 0:
        raise ValueError(f"accolade ouvrante absente : {signature}")
    depth = 0
    in_string = False
    in_char = False
    in_line_comment = False
    in_block_comment = False
    escaped = False
    index = opening
    while index < len(source):
        char = source[index]
        next_char = source[index + 1] if index + 1 < len(source) else ""
        if in_line_comment:
            if char == "\n":
                in_line_comment = False
            index += 1
            continue
        if in_block_comment:
            if char == "*" and next_char == "/":
                in_block_comment = False
                index += 2
            else:
                index += 1
            continue
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            index += 1
            continue
        if in_char:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == "'":
                in_char = False
            index += 1
            continue
        if char == "/" and next_char == "/":
            in_line_comment = True
            index += 2
            continue
        if char == "/" and next_char == "*":
            in_block_comment = True
            index += 2
            continue
        if char == '"':
            in_string = True
        elif char == "'":
            in_char = True
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return source[opening + 1:index]
        index += 1
    raise ValueError(f"accolades non équilibrées : {signature}")


def require(condition: bool, message: str, failures: list[str]) -> None:
    if condition:
        print(f"OK — {message}")
    else:
        failures.append(message)
        print(f"ÉCHEC — {message}", file=sys.stderr)


def main() -> int:
    failures: list[str] = []
    if not SERVICE.is_file() or not STATE.is_file():
        print("ERREUR : TrackingService.java ou GpxSegmentIoState.java absent", file=sys.stderr)
        return 2

    source = SERVICE.read_text(encoding="utf-8")
    try:
        open_request = method_body(source, "private boolean openRecordingSegment(int counterEpoch)")
        open_worker = method_body(source, "private void openRecordingSegmentOnIo(")
        append = method_body(source, "private void appendPoint(final GpxWriter.TrackPoint p)")
        close = method_body(source, "private RecordingSegment closeRecordingSegment()")
        close_worker = method_body(source, "private void closeSegmentOnIo(final RecordingSegment segment)")
        post_process = method_body(source, "private void runPostProcess(final RecordingSegment segment)")
        shutdown = method_body(source, "private void shutdownIoThreadsAfterDrain()")
        unique_file = method_body(source, "private static File uniqueFile(File wanted)")
    except ValueError as error:
        print(f"ERREUR : {error}", file=sys.stderr)
        return 2

    require("writerHandler.post(() -> openRecordingSegmentOnIo" in open_request,
            "la demande d'ouverture est postée sur le worker GPX", failures)
    for forbidden in ("getExternalFilesDir(", "new FileOutputStream(",
                      "new BufferedWriter(", "writer.write(GpxWriter.header(",
                      "uniqueFile("):
        require(forbidden not in open_request,
                f"aucune E/S disque d'ouverture dans la méthode appelante ({forbidden})", failures)
    require("getExternalFilesDir(" in open_worker and "uniqueFile(" in open_worker
            and "new FileOutputStream(" in open_worker
            and "writer.write(GpxWriter.header(" in open_worker,
            "répertoire, fichier et en-tête sont traités par le worker", failures)
    require("segment.ioState.markReady()" in open_worker,
            "le segment ne devient inscriptible qu'après l'en-tête", failures)
    require("ioFile.post(() ->" in append and "segment.ioState.canWrite()" in append,
            "les points sont postés et exigent un segment READY à l'exécution", failures)
    require("writerHandler.post(() -> closeSegmentOnIo(segment))" in close,
            "la fermeture est postée après les tâches déjà acceptées", failures)
    require("segment.ioState.canWrite()" in close_worker
            and "segment.ioState.finishClose()" in close_worker,
            "la fermeture ajoute un pied seulement à un segment prêt", failures)
    require('"gpx-publish-fallback"' in close_worker
            and "publishSnapshot(segment, length)" in close_worker,
            "la publication SAF conserve un repli hors des workers arrêtés", failures)
    require("fileHandler.post(() ->" in post_process
            and "metaHandler.post(() ->" in post_process
            and "segment.closed" in post_process,
            "le post-traitement attend la fermeture avant de lire le segment", failures)
    barrier = shutdown.find("Runnable closeFileThenPublisher")
    final_persist = shutdown.find("persistSessionTo(metaHandler)", barrier)
    meta_quit = shutdown.find("postQuitSafely(metaHandler, metaThread)", barrier)
    require(barrier >= 0 and final_persist > barrier and meta_quit > final_persist,
            "le snapshot final de session est posté avant l'arrêt du worker méta", failures)
    require("if (!stopRequested) persistSessionTo(metaHandler)" in shutdown,
            "un arrêt volontaire ne recrée pas le journal effacé", failures)
    require("wanted.createNewFile()" in unique_file
            and "candidate.createNewFile()" in unique_file
            and "throw new java.io.IOException" in unique_file,
            "le nom GPX est réservé atomiquement sans retomber sur un fichier existant", failures)
    require("new FileOutputStream(file, true)" in open_worker,
            "l'ouverture utilise le fichier réservé sans troncature implicite", failures)

    if failures:
        print(f"ÉCHEC : {len(failures)} invariant(s) non respecté(s).", file=sys.stderr)
        return 1
    print("OK : invariants structurels du LOT 8C-R2B.1 vérifiés.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
