package androidx.core.content;
import android.content.Context;
public class ContextCompat {
    public static int checkSelfPermission(Context c, String p) { return 0; }
    public static android.content.Intent registerReceiver(
            Context c, android.content.BroadcastReceiver r,
            android.content.IntentFilter f, int flags) { return null; }
    public static final int RECEIVER_NOT_EXPORTED = 4;
}
