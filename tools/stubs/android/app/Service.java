package android.app;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
public abstract class Service extends Context {
    public static final int START_STICKY = 1;
    public static final int START_NOT_STICKY = 2;
    public static final int START_REDELIVER_INTENT = 3;
    public static final int STOP_FOREGROUND_REMOVE = 1;
    public static final int STOP_FOREGROUND_DETACH = 2;
    public void onCreate() {}
    public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }
    public void onDestroy() {}
    public IBinder onBind(Intent intent) { return null; }
    public final void startForeground(int id, Notification n) {}
    public final void startForeground(int id, Notification n, int type) {}
    public final void stopForeground(int flags) {}
    public final void stopSelf() {}
    public final void stopSelf(int id) {}
    public java.util.concurrent.Executor getMainExecutor() { return null; }
}
