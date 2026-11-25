package com.example.gpstest;

import android.content.Context;
import android.util.AtomicFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Persistance atomique des transactions de publication SAF des traces récupérées. */
public final class RecoveryPublicationStore implements GpxRecoveryPublisher.RegistryStore {
    private static final Object LOCK = new Object();
    private static final String FILE_NAME = "recovery-publications.registry";
    private static final int MAX_BYTES = RecoveryPublicationRegistry.MAX_TEXT_CHARS;
    private final Context context;

    public RecoveryPublicationStore(Context context) {
        this.context = context == null ? null : context.getApplicationContext();
    }

    private File file() {
        if (context == null) return null;
        File dir = context.getFilesDir();
        return dir == null ? null : new File(dir, FILE_NAME);
    }

    @Override public RecoveryPublicationRegistry read() throws IOException {
        synchronized (LOCK) {
            File base = file();
            if (base == null) throw new IOException("Stockage privé indisponible");
            File backup = new File(base.getPath() + ".bak");
            if (!base.exists() && !backup.exists()) return RecoveryPublicationRegistry.empty();
            try (InputStream in = new AtomicFile(base).openRead()) {
                String text = readUtf8(in);
                RecoveryPublicationRegistry registry = RecoveryPublicationRegistry.parse(text);
                if (registry == null) throw new IOException("Registre de publication corrompu");
                return registry;
            }
        }
    }

    @Override public boolean write(RecoveryPublicationRegistry registry) {
        if (registry == null) return false;
        synchronized (LOCK) {
            File base = file();
            if (base == null) return false;
            File backup = new File(base.getPath() + ".bak");
            if (base.exists() || backup.exists()) {
                try { if (readLocked(base) == null) return false; }
                catch (IOException corrupt) { return false; }
            }
            File parent = base.getParentFile();
            if (parent == null || (!parent.exists() && !parent.mkdirs() && !parent.isDirectory())) return false;
            AtomicFile atomic = new AtomicFile(base);
            FileOutputStream out = null;
            try {
                out = atomic.startWrite();
                out.write(registry.toText().getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.getFD().sync();
                atomic.finishWrite(out);
                out = null;
                return true;
            } catch (Exception error) {
                if (out != null) try { atomic.failWrite(out); } catch (Exception ignored) { }
                return false;
            }
        }
    }

    /** Retire une transaction explicitement abandonnée (destination privée choisie). */
    public boolean removeAny(String publicationId) {
        synchronized (LOCK) {
            File base = file();
            if (base == null) return false;
            RecoveryPublicationRegistry registry;
            try { registry = readLocked(base); }
            catch (IOException error) { return false; }
            if (registry == null) return false;
            RecoveryPublicationRegistry.Entry entry = registry.find(publicationId);
            if (entry == null) return true;
            // Reconstruire un registre sans cette entrée en sérialisant les autres snapshots.
            RecoveryPublicationRegistry replacement = RecoveryPublicationRegistry.empty();
            for (RecoveryPublicationRegistry.Entry item : registry.entries()) {
                if (!item.publicationId.equals(publicationId) && !replacement.upsert(item)) return false;
            }
            return write(replacement);
        }
    }

    /** Retire uniquement une transaction dont l'état VERIFIED a été commité. */
    public boolean removeVerified(String publicationId) {
        synchronized (LOCK) {
            File base = file();
            if (base == null) return false;
            RecoveryPublicationRegistry registry;
            try { registry = readLocked(base); }
            catch (IOException error) { return false; }
            if (registry == null || !registry.removeVerified(publicationId)) return false;
            return write(registry);
        }
    }

    private static RecoveryPublicationRegistry readLocked(File base) throws IOException {
        File backup = new File(base.getPath() + ".bak");
        if (!base.exists() && !backup.exists()) return RecoveryPublicationRegistry.empty();
        try (InputStream in = new AtomicFile(base).openRead()) {
            RecoveryPublicationRegistry registry = RecoveryPublicationRegistry.parse(readUtf8(in));
            if (registry == null) throw new IOException("Registre de publication corrompu");
            return registry;
        }
    }

    private static String readUtf8(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = input.read(buffer)) != -1) {
            if (n == 0) continue;
            if (out.size() + n > MAX_BYTES) throw new IOException("Registre de publication trop volumineux");
            out.write(buffer, 0, n);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(out.toByteArray())).toString();
        } catch (CharacterCodingException invalid) {
            throw new IOException("UTF-8 invalide dans le registre de publication", invalid);
        }
    }
}
