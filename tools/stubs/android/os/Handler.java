package android.os;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Lightweight JVM-test Handler. */
public class Handler {
    private final Looper looper;
    private final Map<Runnable, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

    public Handler() { this(Looper.getMainLooper()); }
    public Handler(Looper looper) { this.looper = looper == null ? Looper.getMainLooper() : looper; }
    public Looper getLooper() { return looper; }

    public boolean post(Runnable runnable) {
        try {
            ScheduledFuture<?> future = looper.executor.schedule(() -> {
                try { runnable.run(); }
                finally { futures.remove(runnable); }
            }, 0, TimeUnit.MILLISECONDS);
            futures.put(runnable, future);
            return true;
        } catch (RuntimeException rejected) { return false; }
    }

    public boolean postDelayed(Runnable runnable, long delayMillis) {
        try {
            ScheduledFuture<?> future = looper.executor.schedule(() -> {
                try { runnable.run(); }
                finally { futures.remove(runnable); }
            }, Math.max(0L, delayMillis), TimeUnit.MILLISECONDS);
            futures.put(runnable, future);
            return true;
        } catch (RuntimeException rejected) { return false; }
    }

    public void removeCallbacks(Runnable runnable) {
        ScheduledFuture<?> future = futures.remove(runnable);
        if (future != null) future.cancel(false);
    }
}
