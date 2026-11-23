package android.content;
public class Intent {
    public static final String ACTION_SEND = "android.intent.action.SEND";
    public static final String ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE";
    public static final String EXTRA_STREAM = "android.intent.extra.STREAM";
    public static final String EXTRA_SUBJECT = "android.intent.extra.SUBJECT";
    public static final int FLAG_GRANT_READ_URI_PERMISSION = 1;
    public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
    public static Intent createChooser(Intent t, CharSequence title) { return t; }
    public static final String ACTION_SCREEN_ON = "android.intent.action.SCREEN_ON";
    public static final String ACTION_SCREEN_OFF = "android.intent.action.SCREEN_OFF";
    public static final int FLAG_ACTIVITY_SINGLE_TOP = 0x20000000;
    public static final int FLAG_ACTIVITY_CLEAR_TOP = 0x04000000;
    public Intent() {}
    public Intent(Context c, Class<?> cls) {}
    public Intent(String action) {}
    public Intent setAction(String a) { return this; }
    public String getAction() { return null; }
    public Intent setFlags(int f) { return this; }
    public Intent addFlags(int f) { return this; }
    public Intent setType(String t) { return this; }
    public Intent setClipData(android.content.ClipData c) { return this; }
    public Intent putExtra(String k, int v) { return this; }
    public Intent putExtra(String k, String v) { return this; }
    public Intent putExtra(String k, boolean v) { return this; }
    public Intent putExtra(String k, android.net.Uri v) { return this; }
    public Intent putExtra(String k, java.util.ArrayList<android.net.Uri> v) { return this; }
    public int getIntExtra(String k, int d) { return d; }
    public String getStringExtra(String k) { return null; }
    public boolean getBooleanExtra(String k, boolean d) { return d; }
}
