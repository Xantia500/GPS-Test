package android.content;
import android.content.pm.PackageManager;
import java.io.File;
public class Context {
    public static final int MODE_PRIVATE = 0;
    public static final String LOCATION_SERVICE = "location";
    public static final String SENSOR_SERVICE = "sensor";
    public static final String NOTIFICATION_SERVICE = "notification";
    public static final int RECEIVER_NOT_EXPORTED = 4;
    public static final int RECEIVER_EXPORTED = 2;
    public SharedPreferences getSharedPreferences(String n, int m) { return null; }
    public File getFilesDir() { return null; }
    public File getExternalFilesDir(String type) { return null; }
    @SuppressWarnings("unchecked")
    public <T> T getSystemService(String name) { return null; }
    public <T> T getSystemService(Class<T> cls) { return null; }
    public String getString(int id) { return ""; }
    public String getString(int id, Object... args) { return ""; }
    public void startActivity(Intent i) {}
    public void startService(Intent i) {}
    public void startForegroundService(Intent i) {}
    public void stopService(Intent i) {}
    public Intent registerReceiver(BroadcastReceiver r, IntentFilter f) { return null; }
    public Intent registerReceiver(BroadcastReceiver r, IntentFilter f, int flags) { return null; }
    public void unregisterReceiver(BroadcastReceiver r) {}
    public void sendBroadcast(Intent i) {}
    public int checkSelfPermission(String p) { return 0; }
    public PackageManager getPackageManager() { return null; }
    public String getPackageName() { return "com.example.gpstest"; }
    public Context getApplicationContext() { return this; }
}
