#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Vérifie les invariants structurels du checkpoint GPX durable LOT 8C-R2B.2."""
from __future__ import annotations

from pathlib import Path
import sys

from verify_gpx_async_open import method_body, require

ROOT = Path(__file__).resolve().parents[1]
SERVICE = ROOT / "app/src/main/java/com/example/gpstest/TrackingService.java"
JOURNAL = ROOT / "app/src/main/java/com/example/gpstest/SessionJournal.java"
STORE = ROOT / "app/src/main/java/com/example/gpstest/SessionStore.java"
CHECKPOINT = ROOT / "app/src/main/java/com/example/gpstest/GpxCheckpoint.java"


def main() -> int:
    failures: list[str] = []
    paths = (SERVICE, JOURNAL, STORE, CHECKPOINT)
    if not all(path.is_file() for path in paths):
        print("ERREUR : un fichier du mécanisme checkpoint est absent", file=sys.stderr)
        return 2

    service = SERVICE.read_text(encoding="utf-8")
    journal = JOURNAL.read_text(encoding="utf-8")
    store = STORE.read_text(encoding="utf-8")
    checkpoint = CHECKPOINT.read_text(encoding="utf-8")
    try:
        persist = method_body(service, "private void persistSessionTo(final Handler meta)")
        open_worker = method_body(service, "private void openRecordingSegmentOnIo(")
        append = method_body(service, "private void appendPoint(final GpxWriter.TrackPoint p)")
        sync = method_body(service, "private void syncSegmentAndCheckpoint(RecordingSegment segment)")
        register = method_body(service, "private void queueSegmentRegistration(final RecordingSegment segment)")
        update = method_body(service, "private void queueSegmentCheckpoint(final RecordingSegment segment, final long length)")
        flush = method_body(service, "private void flushNow()")
        close = method_body(service, "private void closeSegmentOnIo(final RecordingSegment segment)")
        write = method_body(store, "public static boolean write(Context ctx, SessionJournal journal)")
        register_store = method_body(store, "public static boolean registerSegment(")
        checkpoint_store = method_body(store, "public static boolean updateCheckpoint(")
    except ValueError as error:
        print(f"ERREUR : {error}", file=sys.stderr)
        return 2

    flush_pos = sync.find("segment.writer.flush()")
    fd_sync_pos = sync.find("segment.output.getFD().sync()")
    length_pos = sync.find("long length = segment.file.length()")
    advance_pos = sync.find("segment.checkpoint.advance(segment.file.getName(), length)")
    queue_pos = sync.find("queueSegmentCheckpoint(segment, length)")
    require(flush_pos >= 0 and fd_sync_pos > flush_pos and length_pos > fd_sync_pos
            and advance_pos > length_pos and queue_pos > advance_pos,
            "flush → FileDescriptor.sync → file.length → avancement → journal respectent cet ordre", failures)
    require("segment.file.length()" in sync and "segment.checkpoint.advance" in sync,
            "la longueur vient du fichier réel et reste monotone par segment", failures)
    require("segment.output.getFD().sync()" not in append and "segment.output.getFD().sync()" not in flush,
            "toutes les synchronisations GPX passent par le helper unique", failures)
    require("syncSegmentAndCheckpoint(segment)" in append
            and "syncSegmentAndCheckpoint(segment)" in flush
            and "syncSegmentAndCheckpoint(segment)" in close,
            "points, jalon sans fix et fermeture utilisent le même mécanisme checkpoint", failures)
    registration_pos = open_worker.find("queueSegmentRegistration(segment)")
    initial_sync_pos = open_worker.find("syncSegmentAndCheckpoint(segment)")
    require("segment.checkpoint.register(file.getName())" in open_worker
            and registration_pos >= 0 and initial_sync_pos > registration_pos,
            "le fichier est associé au journal avant son premier checkpoint d'en-tête", failures)
    require("SessionStore.registerSegment" in register
            and "segment.id" in register and "segment.sessionId" in register,
            "l'association au journal est protégée par session et génération", failures)
    require("SessionStore.updateCheckpoint" in update
            and "segment.id" in update and "name" in update and "length" in update,
            "l'offset n'est envoyé au journal qu'avec le fichier et la génération attendus", failures)
    require("journalSegment.checkpoint.snapshot()" in persist
            and "checkpoint.safeLengthBytes" in persist
            and "journalSegment.id" in persist,
            "le snapshot final de session reprend le checkpoint réel du segment", failures)
    require("serviceDestroying && !stopRequested && serviceDestroyedWhileRecording" in persist
            and "serviceDestroyedWhileRecording = !stopRequested && state.isRecording()" in service,
            "une destruction non volontaire conserve l'indicateur d'enregistrement dans le snapshot final", failures)
    require("FORMAT_VERSION = 5" in journal and '"segmentGeneration="' in journal
            and 'case "segmentGeneration"' in journal,
            "le format 5 persiste la génération tout en gardant le parseur historique", failures)
    require("journal.segmentGeneration < current.segmentGeneration" in write
            and "Math.max(journal.safeLengthBytes" in write,
            "les snapshots retardés sont rejetés et les checkpoints d'un même fichier ne régressent pas", failures)
    require("current.segmentGeneration == segmentGeneration" in checkpoint_store
            and "current.file.equals(probe.file)" in checkpoint_store
            and "safeLengthBytes < current.safeLengthBytes" in checkpoint_store
            and "registry.advanceCheckpoint" in checkpoint_store
            and "SessionJournal detached" in checkpoint_store,
            "le store vérifie le segment courant et route les callbacks retardés vers le registre", failures)
    require("segmentGeneration < current.segmentGeneration" in register_store
            and "current.safeLengthBytes = 0L" in register_store,
            "l'ouverture d'un nouveau segment réinitialise son offset et bloque les anciens", failures)
    require("byteLength <= 0L || byteLength < safeLengthBytes" in checkpoint,
            "le modèle pur rejette les offsets nuls et décroissants", failures)
    require("safeLengthBytes" in journal and "octets" in journal,
            "la notice décrit la taille sûre en octets, sans la confondre avec les points connus", failures)

    if failures:
        print(f"ÉCHEC : {len(failures)} invariant(s) non respecté(s).", file=sys.stderr)
        return 1
    print("OK : invariants structurels du checkpoint durable et du routage multi-segments vérifiés.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
