package android.os;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Lightweight JVM-test Looper. Not a replacement for Android's runtime. */
public class Looper {
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final Looper MAIN = new Looper("jvm-main-looper");
    final ScheduledThreadPoolExecutor executor;

    public Looper() { this("jvm-looper-" + IDS.incrementAndGet()); }

    Looper(String name) {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
        executor = new ScheduledThreadPoolExecutor(1, factory);
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    public static Looper getMainLooper() { return MAIN; }
    public static Looper myLooper() { return MAIN; }

    void quitSafely() { executor.shutdown(); }
}
