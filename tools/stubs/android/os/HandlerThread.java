package android.os;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Lightweight JVM-test HandlerThread. The Looper executor serializes work. */
public class HandlerThread extends Thread {
    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile Looper looper;
    private volatile boolean quitting;

    public HandlerThread(String name) { super(name); }
    public HandlerThread(String name, int priority) { super(name); }

    @Override public void run() {
        looper = new Looper(getName() + "-looper");
        ready.countDown();
        while (!quitting) {
            try { Thread.sleep(100L); }
            catch (InterruptedException ignored) { if (quitting) break; }
        }
        if (looper != null) looper.quitSafely();
    }

    public Looper getLooper() {
        if (!isAlive() && looper == null) return null;
        try { ready.await(2, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return looper;
    }

    public boolean quitSafely() {
        quitting = true;
        interrupt();
        return true;
    }
}
