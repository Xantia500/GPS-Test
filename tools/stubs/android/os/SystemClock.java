package android.os;

/** Monotonic clock facade for JVM-only tests. */
public final class SystemClock {
    private static final long START_NANOS = System.nanoTime();
    private SystemClock() { }
    public static long elapsedRealtimeNanos() { return System.nanoTime() - START_NANOS; }
    public static long elapsedRealtime() { return elapsedRealtimeNanos() / 1_000_000L; }
}
