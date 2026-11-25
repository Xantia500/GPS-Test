package com.example.gpstest;

import android.content.Context;
import android.util.AtomicFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Persistance atomique du journal de session.
 *
 * Source de vérité : stockage interne privé (getFilesDir), indépendant du
 * volume externe qui contient les GPX. L'ancien emplacement externe est lu
 * puis migré une seule fois. Toutes les opérations sont sérialisées dans le
 * processus ; AtomicFile protège le remplacement contre une écriture coupée.
 */
public final class SessionStore {

    private static final Object LOCK = new Object();
    /** Le journal ne contient que quelques champs : toute taille supérieure est suspecte. */
    private static final int MAX_JOURNAL_BYTES = 16 * 1024;
    private static final String RECOVERY_REGISTRY_FILE = "recovery.registry";

    private SessionStore() { }

    private static File file(Context ctx) {
        if (ctx == null) return null;
        File dir = ctx.getFilesDir();
        return dir == null ? null : new File(dir, SessionJournal.FILE_NAME);
    }

    private static File recoveryRegistryFile(Context ctx) {
        if (ctx == null) return null;
        File dir = ctx.getFilesDir();
        return dir == null ? null : new File(dir, RECOVERY_REGISTRY_FILE);
    }

    private static File legacyFile(Context ctx) {
        if (ctx == null) return null;
        File dir = ctx.getExternalFilesDir(null);
        return dir == null ? null : new File(dir, SessionJournal.FILE_NAME);
    }

    /** Lecture non destructive. Un journal corrompu n'est jamais supprimé ici. */
    public static SessionJournal read(Context ctx) {
        synchronized (LOCK) {
            return readLocked(ctx);
        }
    }

    private static SessionJournal readLocked(Context ctx) {
        File current = file(ctx);
        if (current == null) return null;
        File backup = new File(current.getPath() + ".bak");
        File fresh = new File(current.getPath() + ".new");
        if (current.exists() || backup.exists()) {
            // Si le stockage interne contient une version committée ou son
            // backup, ne pas masquer une corruption par un ancien journal.
            return readAtomic(current);
        }
        if (fresh.exists()) {
            // .new sans base ni .bak est une première écriture interrompue :
            // il n'a jamais été committé et peut être écarté. La source legacy
            // reste disponible et sera migrée si elle est valide.
            fresh.delete();
        }

        File legacy = legacyFile(ctx);
        SessionJournal migrated = readPlain(legacy);
        if (migrated != null && writeAtomic(current, migrated)) {
            // La source historique n'est supprimée qu'après commit atomique.
            if (legacy != null) legacy.delete();
        }
        return migrated;
    }

    /** Liste durable des segments GPX interrompus. À appeler hors thread principal. */
    public static java.util.List<SessionJournal> readRecoveries(Context ctx) {
        synchronized (LOCK) {
            if (ctx == null) return java.util.Collections.emptyList();
            RecoveryRegistry registry = readRegistryLocked(ctx);
            if (registry == null) return java.util.Collections.emptyList();
            SessionJournal current = readLocked(ctx);
            if (RecoveryRegistry.isEligible(current)) {
                if (!registry.upsert(current) || !writeRegistryLocked(ctx, registry))
                    return registry.snapshots();
            }
            return registry.snapshots();
        }
    }

    /** Acquitte une entrée récupérée sans toucher au journal d'une autre session. */
    public static boolean clearRecoveryIfUnchanged(Context ctx, SessionJournal expected) {
        if (ctx == null || expected == null) return false;
        synchronized (LOCK) {
            RecoveryRegistry registry = readRegistryLocked(ctx);
            boolean removedRegistry = false;
            if (registry != null) {
                removedRegistry = registry.removeIfUnchanged(expected);
                if (removedRegistry && !writeRegistryLocked(ctx, registry)) return false;
            } else if (hasRegistryState(ctx)) {
                return false;
            }
            SessionJournal current = readLocked(ctx);
            boolean removedCurrent = RecoveryRegistry.sameSnapshot(current, expected);
            // Le journal courant n'est supprimé que si tous ses champs sont
            // encore identiques au snapshot utilisé pour cette récupération.
            // Même un offset égal ne suffit pas : des points peuvent être en
            // attente du prochain checkpoint, ou l'état recording/notice peut
            // avoir évolué pendant la copie. Une récupération répétée est
            // préférable à la suppression d'un état plus récent.
            if (removedCurrent) deleteJournalOnlyLocked(ctx);
            return removedRegistry || removedCurrent;
        }
    }

    /** Retire un segment seulement après fermeture et checkpoint final confirmés. */
    public static boolean clearRecoverySegment(Context ctx, String sessionId,
                                               long generation, String fileName,
                                               long safeLengthBytes) {
        if (ctx == null || sessionId == null || sessionId.isEmpty()
                || fileName == null || fileName.isEmpty() || safeLengthBytes <= 0L) return false;
        synchronized (LOCK) {
            RecoveryRegistry registry = readRegistryLocked(ctx);
            if (registry == null) return !hasRegistryState(ctx);
            if (!registry.removeSegment(sessionId, generation, fileName, safeLengthBytes)) return false;
            return writeRegistryLocked(ctx, registry);
        }
    }

    private static RecoveryRegistry readRegistryLocked(Context ctx) {
        File base = recoveryRegistryFile(ctx);
        if (base == null) return null;
        File backup = new File(base.getPath() + ".bak");
        if (!base.exists() && !backup.exists()) return RecoveryRegistry.empty();
        try (InputStream in = new AtomicFile(base).openRead()) {
            return RecoveryRegistry.parse(readUtf8(in, RecoveryRegistry.MAX_TEXT_CHARS));
        } catch (Exception error) {
            return null;
        }
    }

    private static boolean hasRegistryState(Context ctx) {
        File base = recoveryRegistryFile(ctx);
        return base != null && (base.exists() || new File(base.getPath() + ".bak").exists()
                || new File(base.getPath() + ".new").exists());
    }

    private static boolean writeRegistryLocked(Context ctx, RecoveryRegistry registry) {
        File base = recoveryRegistryFile(ctx);
        if (base == null || registry == null) return false;
        if (hasRegistryState(ctx) && readRegistryLocked(ctx) == null) return false;
        File dir = base.getParentFile();
        if (dir == null || (!dir.exists() && !dir.mkdirs())) return false;
        AtomicFile atomic = new AtomicFile(base);
        FileOutputStream stream = null;
        try {
            stream = atomic.startWrite();
            stream.write(registry.toText().getBytes(StandardCharsets.UTF_8));
            stream.flush();
            stream.getFD().sync();
            atomic.finishWrite(stream);
            stream = null;
            return true;
        } catch (Exception error) {
            if (stream != null) {
                try { atomic.failWrite(stream); } catch (Exception ignored) { }
            }
            return false;
        }
    }

    private static boolean upsertRecoveryLocked(Context ctx, SessionJournal journal) {
        if (!RecoveryRegistry.isEligible(journal)) return true;
        RecoveryRegistry registry = readRegistryLocked(ctx);
        return registry != null && registry.upsert(journal) && writeRegistryLocked(ctx, registry);
    }

    private static SessionJournal readAtomic(File base) {
        try (InputStream in = new AtomicFile(base).openRead()) {
            return SessionJournal.parse(readUtf8(in));
        } catch (Exception e) {
            return null;
        }
    }

    private static SessionJournal readPlain(File source) {
        if (source == null || !source.isFile()) return null;
        try (InputStream in = new FileInputStream(source)) {
            return SessionJournal.parse(readUtf8(in));
        } catch (Exception e) {
            return null;
        }
    }

    /** Écriture atomique : false signifie que l'ancien journal est préservé. */
    public static boolean write(Context ctx, SessionJournal journal) {
        if (journal == null) return false;
        synchronized (LOCK) {
            File base = file(ctx);
            if (base == null) return false;
            SessionJournal current = readLocked(ctx);
            if (current == null && hasUncommittedOrUnreadableState(base)) {
                // Un nouveau START ne doit pas écraser silencieusement un journal
                // que le parseur ne sait plus comprendre. On le met d'abord en
                // quarantaine (copie synchronisée ou renommage rapide si énorme).
                if (!preserveUnreadableState(base)) return false;
            }
            if (current != null && !current.sessionId.equals(journal.sessionId)
                    && RecoveryRegistry.isEligible(current)) {
                // Préserver l'ancien checkpoint avant qu'un nouveau START
                // remplace le journal courant unique.
                if (!upsertRecoveryLocked(ctx, current)) return false;
            }
            if (current != null && current.sessionId.equals(journal.sessionId)) {
                // Les snapshots d'un segment ancien peuvent arriver après le
                // démarrage du suivant (threads UI, ioFile et ioMeta). La
                // génération empêche un état retardé de faire reculer le journal.
                if (journal.segmentGeneration < current.segmentGeneration) return false;
                if (journal.segmentGeneration == current.segmentGeneration) {
                    if (journal.file.isEmpty() && !current.file.isEmpty()) {
                        // Snapshot capturé pendant OPENING, committé après la
                        // réservation du fichier : garder l'identité déjà liée.
                        journal.file = current.file;
                        journal.safeLengthBytes = current.safeLengthBytes;
                    } else if (journal.file.equals(current.file)) {
                        // Un snapshot UI plus ancien ne peut pas faire régresser
                        // le dernier préfixe confirmé par FileDescriptor.sync().
                        journal.safeLengthBytes = Math.max(journal.safeLengthBytes,
                                current.safeLengthBytes);
                    } else if (!current.file.isEmpty()) {
                        // Deux noms différents pour une même génération indiquent
                        // un snapshot incohérent : ne pas changer de fichier.
                        return false;
                    }
                }
                // Une mise à jour ultérieure du service ne doit pas faire réapparaître
                // une notice déjà présentée pour cette même session.
                journal.revision = current.revision == Long.MAX_VALUE
                        ? Long.MAX_VALUE : current.revision + 1L;
                journal.savedAtMs = Math.max(journal.savedAtMs, current.savedAtMs);
                journal.noticeShown = journal.noticeShown || current.noticeShown;
            } else {
                journal.revision = Math.max(1L, journal.revision);
            }
            if (RecoveryRegistry.isEligible(journal)
                    && !upsertRecoveryLocked(ctx, journal)) return false;
            if (!writeAtomic(base, journal)) return false;
            // Le journal legacy n'est supprimé que dans readLocked(), après
            // migration réussie. S'il coexiste avec un interne illisible, on
            // le conserve au lieu de le détruire au premier nouveau START.
            return true;
        }
    }


    /**
     * Lie le journal au fichier d'un segment nouvellement ouvert.
     * Appelé depuis ioFile dès que le nom du fichier a été réservé, avant le
     * premier checkpoint. La FIFO ioMeta reçoit donc l'association avant tout
     * updateCheckpoint de ce segment. Une génération plus ancienne est refusée.
     */
    public static boolean registerSegment(Context ctx, String expectedSessionId,
                                          long segmentGeneration, String fileName) {
        if (expectedSessionId == null || expectedSessionId.trim().isEmpty()
                || segmentGeneration < 0L) return false;
        SessionJournal probe = SessionJournal.of(expectedSessionId, true,
                0L, 0L, true, false, 0L, 0, 0L, 0, fileName, 0L,
                segmentGeneration);
        if (probe.file.isEmpty()) return false;
        synchronized (LOCK) {
            SessionJournal current = readLocked(ctx);
            if (current == null || !expectedSessionId.equals(current.sessionId)
                    || segmentGeneration < current.segmentGeneration) return false;
            if (segmentGeneration == current.segmentGeneration) {
                if (!current.file.isEmpty() && !current.file.equals(probe.file)) return false;
                if (current.file.equals(probe.file)) return true;
            }
            current.segmentGeneration = segmentGeneration;
            current.file = probe.file;
            current.safeLengthBytes = 0L;
            current.segmentPoints = 0L;
            current.savedAtMs = System.currentTimeMillis();
            current.revision = current.revision == Long.MAX_VALUE
                    ? Long.MAX_VALUE : current.revision + 1L;
            return writeLocked(ctx, current);
        }
    }

    /**
     * Enregistre uniquement un préfixe déjà flushé puis synchronisé sur le FD.
     * Le nom ET la génération doivent correspondre au segment encore courant.
     */
    public static boolean updateCheckpoint(Context ctx, String expectedSessionId,
                                           long segmentGeneration, String fileName,
                                           long safeLengthBytes) {
        if (expectedSessionId == null || expectedSessionId.trim().isEmpty()
                || segmentGeneration < 0L || safeLengthBytes <= 0L) return false;
        SessionJournal probe = SessionJournal.of(expectedSessionId, true,
                0L, 0L, true, false, 0L, 0, 0L, 0, fileName, safeLengthBytes,
                segmentGeneration);
        if (probe.file.isEmpty()) return false;
        synchronized (LOCK) {
            SessionJournal current = readLocked(ctx);
            long now = System.currentTimeMillis();
            if (current != null && expectedSessionId.equals(current.sessionId)
                    && current.segmentGeneration == segmentGeneration
                    && current.file.equals(probe.file)) {
                if (safeLengthBytes < current.safeLengthBytes) return false;
                if (safeLengthBytes == current.safeLengthBytes) {
                    return !RecoveryRegistry.isEligible(current)
                            || upsertRecoveryLocked(ctx, current);
                }
                current.safeLengthBytes = safeLengthBytes;
                current.savedAtMs = Math.max(current.savedAtMs, now);
                current.revision = current.revision == Long.MAX_VALUE
                        ? Long.MAX_VALUE : current.revision + 1L;
                // Le registre précède le journal courant : un arrêt entre les
                // deux écritures ne perd pas le checkpoint déjà synchronisé.
                if (RecoveryRegistry.isEligible(current)
                        && !upsertRecoveryLocked(ctx, current)) return false;
                return writeLocked(ctx, current);
            }

            // La fermeture d'un segment peut finir après le passage du journal
            // courant au segment suivant (ou à une autre session). Son entrée
            // reste indépendante et doit recevoir son checkpoint final.
            RecoveryRegistry registry = readRegistryLocked(ctx);
            if (registry == null) return false;
            if (!registry.advanceCheckpoint(expectedSessionId, segmentGeneration,
                    probe.file, safeLengthBytes, now)) {
                // Un segment dont l'ouverture était encore en file peut publier
                // son premier checkpoint après le START d'une autre session.
                // L'appel n'arrive qu'après flush + fd.sync sur le writer exact :
                // créer alors un snapshot minimal est sûr, même sans journal actif.
                SessionJournal detached = SessionJournal.of(expectedSessionId, true,
                        0L, now, true, false, 0L, 0, 0L, 0,
                        probe.file, safeLengthBytes, segmentGeneration);
                if (!registry.upsert(detached)) return false;
            }
            return writeRegistryLocked(ctx, registry);
        }
    }

    private static boolean writeAtomic(File base, SessionJournal journal) {
        File dir = base.getParentFile();
        if (dir == null || (!dir.exists() && !dir.mkdirs())) return false;
        AtomicFile atomic = new AtomicFile(base);
        FileOutputStream stream = null;
        try {
            stream = atomic.startWrite();
            stream.write(journal.toText().getBytes(StandardCharsets.UTF_8));
            stream.flush();
            atomic.finishWrite(stream);
            stream = null;
            return true;
        } catch (Exception e) {
            if (stream != null) {
                try { atomic.failWrite(stream); } catch (Exception ignored) { }
            }
            return false;
        }
    }

    /**
     * Marque la notice comme présentée sans consommer le journal : une trace
     * interrompue doit rester disponible pour la récupération GPX du prochain
     * sous-lot. La comparaison d'identité protège une nouvelle session.
     */
    public static boolean markNoticeShown(Context ctx, SessionJournal expected) {
        if (expected == null || expected.sessionId == null || expected.sessionId.isEmpty()) return false;
        synchronized (LOCK) {
            SessionJournal current = readLocked(ctx);
            if (current == null || !expected.sessionId.equals(current.sessionId)) return false;
            if (current.noticeShown) return true;
            current.noticeShown = true;
            current.revision = current.revision == Long.MAX_VALUE
                    ? Long.MAX_VALUE : current.revision + 1L;
            return writeLocked(ctx, current);
        }
    }

    private static boolean writeLocked(Context ctx, SessionJournal journal) {
        File base = file(ctx);
        return base != null && writeAtomic(base, journal);
    }

    private static boolean hasUncommittedOrUnreadableState(File base) {
        return base.exists() || new File(base.getPath() + ".bak").exists();
    }

    /** Préserve un journal illisible avant qu'un nouveau START ne le remplace. */
    private static boolean preserveUnreadableState(File base) {
        File backup = new File(base.getPath() + ".bak");
        File source = base.isFile() ? base : (backup.isFile() ? backup : null);
        if (source == null) return true;
        File quarantine = null;
        for (int i = 0; i < 100; i++) {
            File candidate = new File(base.getPath() + (i == 0 ? ".corrupt" : ".corrupt-" + i));
            if (!candidate.exists()) { quarantine = candidate; break; }
        }
        if (quarantine == null) return false;

        // Un journal normal est minuscule : une copie synchronisée évite une
        // fenêtre où la source disparaîtrait avant la création de la quarantaine.
        // Pour un fichier anormalement volumineux, le renommage évite une copie
        // potentiellement coûteuse sur le thread I/O du service.
        if (source.length() > MAX_JOURNAL_BYTES) {
            return source.renameTo(quarantine);
        }
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(quarantine, false)) {
            byte[] buffer = new byte[512];
            int count;
            while ((count = in.read(buffer)) > 0) out.write(buffer, 0, count);
            out.flush();
            out.getFD().sync();
            return true;
        } catch (Exception e) {
            if (quarantine.exists()) quarantine.delete();
            return false;
        }
    }

    /** Efface uniquement le journal de la session attendue. */
    public static boolean clearIfSameSession(Context ctx, String expectedSessionId) {
        if (ctx == null || expectedSessionId == null || expectedSessionId.isEmpty()) return false;
        synchronized (LOCK) {
            SessionJournal current = readLocked(ctx);
            if (current == null || !expectedSessionId.equals(current.sessionId)) return false;
            // Les entrées de segments sont retirées par clearRecoverySegment
            // seulement après fermeture et synchronisation finales prouvées.
            deleteJournalOnlyLocked(ctx);
            return true;
        }
    }

    /** Variante stricte pour les traitements différés qui portent un snapshot précis. */
    public static boolean clearIfUnchanged(Context ctx, SessionJournal expected) {
        if (expected == null) return false;
        synchronized (LOCK) {
            SessionJournal current = readLocked(ctx);
            if (current == null || !RecoveryRegistry.sameSnapshot(current, expected)) return false;
            RecoveryRegistry registry = readRegistryLocked(ctx);
            if (registry == null && hasRegistryState(ctx)) return false;
            if (registry != null && registry.removeIfUnchanged(expected)
                    && !writeRegistryLocked(ctx, registry)) return false;
            deleteJournalOnlyLocked(ctx);
            return true;
        }
    }

    /** Suppression inconditionnelle réservée à un arrêt explicite, dans la FIFO du service. */
    public static void clear(Context ctx) {
        synchronized (LOCK) {
            deleteLocked(ctx);
        }
    }

    private static void deleteLocked(Context ctx) {
        File registry = recoveryRegistryFile(ctx);
        if (registry != null) new AtomicFile(registry).delete();
        deleteJournalOnlyLocked(ctx);
    }

    private static void deleteJournalOnlyLocked(Context ctx) {
        File current = file(ctx);
        if (current != null) new AtomicFile(current).delete();
        File legacy = legacyFile(ctx);
        if (legacy != null && legacy.exists()) legacy.delete();
        // La suppression explicite de session efface aussi les copies de
        // quarantaine privées associées au journal, pour ne pas les conserver
        // indéfiniment après une demande utilisateur de terminaison.
        if (current != null) {
            for (int i = 0; i < 100; i++) {
                File corrupt = new File(current.getPath()
                        + (i == 0 ? ".corrupt" : ".corrupt-" + i));
                if (corrupt.exists()) corrupt.delete();
            }
        }
    }

    /** Ancienne API conservée sans suppression : consume ne doit plus détruire l'état. */
    @Deprecated
    public static SessionJournal consume(Context ctx) {
        return read(ctx);
    }

    public static boolean exists(Context ctx) {
        synchronized (LOCK) {
            File current = file(ctx);
            if (current != null && (current.exists()
                    || new File(current.getPath() + ".bak").exists()
                    || new File(current.getPath() + ".new").exists())) return true;
            File legacy = legacyFile(ctx);
            return (legacy != null && legacy.exists()) || hasRegistryState(ctx);
        }
    }

    private static String readUtf8(InputStream in) throws Exception {
        return readUtf8(in, MAX_JOURNAL_BYTES);
    }

    private static String readUtf8(InputStream in, int maxBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(384);
        byte[] buffer = new byte[2048];
        int count;
        int total = 0;
        while ((count = in.read(buffer)) > 0) {
            total += count;
            if (total > maxBytes) throw new java.io.IOException("Journal/registre trop volumineux");
            out.write(buffer, 0, count);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
