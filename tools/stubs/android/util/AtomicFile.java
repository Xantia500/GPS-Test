package android.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** JVM test stub for android.util.AtomicFile. Not packaged in the APK. */
public final class AtomicFile {
    private final File base;
    private final File backup;
    private final File fresh;

    public AtomicFile(File base) {
        this.base = base;
        this.backup = new File(base.getPath() + ".bak");
        this.fresh = new File(base.getPath() + ".new");
    }

    public FileOutputStream startWrite() throws IOException {
        File parent = base.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create parent directory");
        }
        if (base.exists() && !backup.exists() && !base.renameTo(backup)) {
            throw new IOException("Cannot move base to backup");
        }
        if (fresh.exists() && !fresh.delete()) throw new IOException("Cannot delete stale new file");
        return new FileOutputStream(fresh, false);
    }

    public void finishWrite(FileOutputStream stream) {
        closeAndSync(stream);
        if (base.exists() && !base.delete()) throw new IllegalStateException("Cannot replace base");
        if (!fresh.renameTo(base)) throw new IllegalStateException("Cannot commit new file");
        if (backup.exists()) backup.delete();
    }

    public void failWrite(FileOutputStream stream) {
        try { if (stream != null) stream.close(); } catch (IOException ignored) { }
        if (fresh.exists()) fresh.delete();
        if (backup.exists()) {
            if (base.exists()) base.delete();
            backup.renameTo(base);
        }
    }

    public InputStream openRead() throws IOException {
        if (backup.exists()) {
            if (base.exists()) base.delete();
            if (!backup.renameTo(base)) throw new IOException("Cannot restore backup");
        }
        if (!base.exists()) throw new IOException("Atomic file missing");
        return new FileInputStream(base);
    }

    public void delete() {
        if (base.exists()) base.delete();
        if (backup.exists()) backup.delete();
        if (fresh.exists()) fresh.delete();
    }

    private static void closeAndSync(FileOutputStream stream) {
        if (stream == null) return;
        try { stream.getFD().sync(); } catch (IOException ignored) { }
        try { stream.close(); } catch (IOException ignored) { }
    }
}
