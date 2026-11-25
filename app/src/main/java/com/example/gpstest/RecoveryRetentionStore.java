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

/** Persistance atomique du registre de conservation des copies récupérées publiées. */
public final class RecoveryRetentionStore {
    private static final Object LOCK = new Object();
    private static final String FILE_NAME = "recovery-retention.registry";
    private static final int MAX_BYTES = RecoveryRetentionRegistry.MAX_TEXT_CHARS;
    private final Context context;

    public RecoveryRetentionStore(Context context) {
        this.context = context == null ? null : context.getApplicationContext();
    }

    private File file() {
        if (context == null) return null;
        File directory = context.getFilesDir();
        return directory == null ? null : new File(directory, FILE_NAME);
    }

    public RecoveryRetentionRegistry read() throws IOException {
        synchronized (LOCK) {
            File base = file();
            if (base == null) throw new IOException("Stockage privé indisponible");
            File backup = new File(base.getPath() + ".bak");
            if (!base.exists() && !backup.exists()) return RecoveryRetentionRegistry.empty();
            try (InputStream in = new AtomicFile(base).openRead()) {
                RecoveryRetentionRegistry registry = RecoveryRetentionRegistry.parse(readUtf8(in));
                if (registry == null) throw new IOException("Registre de conservation corrompu");
                return registry;
            }
        }
    }

    public boolean addOrVerify(RecoveryRetentionRegistry.Entry entry) {
        if (entry == null) return false;
        synchronized (LOCK) {
            File base = file();
            if (base == null) return false;
            RecoveryRetentionRegistry registry;
            try { registry = readLocked(base); }
            catch (IOException invalid) { return false; }
            if (registry == null || !registry.addOrVerify(entry)) return false;
            return writeLocked(base, registry);
        }
    }

    private static RecoveryRetentionRegistry readLocked(File base) throws IOException {
        File backup = new File(base.getPath() + ".bak");
        if (!base.exists() && !backup.exists()) return RecoveryRetentionRegistry.empty();
        try (InputStream in = new AtomicFile(base).openRead()) {
            RecoveryRetentionRegistry registry = RecoveryRetentionRegistry.parse(readUtf8(in));
            if (registry == null) throw new IOException("Registre de conservation corrompu");
            return registry;
        }
    }

    private static boolean writeLocked(File base, RecoveryRetentionRegistry registry) {
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
        } catch (Exception failure) {
            if (out != null) try { atomic.failWrite(out); } catch (Exception ignored) { }
            return false;
        }
    }

    private static String readUtf8(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = input.read(buffer)) != -1) {
            if (n == 0) continue;
            if (output.size() + n > MAX_BYTES) throw new IOException("Registre trop volumineux");
            output.write(buffer, 0, n);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(output.toByteArray())).toString();
        } catch (CharacterCodingException invalid) {
            throw new IOException("UTF-8 invalide dans le registre de conservation", invalid);
        }
    }
}
