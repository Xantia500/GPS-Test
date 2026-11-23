package androidx.core.app;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
public class NotificationCompat {
    public static final int PRIORITY_LOW = -1;
    public static final int PRIORITY_DEFAULT = 0;
    public static final int VISIBILITY_PUBLIC = 1;
    public static class Builder {
        public Builder(Context c, String channelId) {}
        public Builder setSmallIcon(int res) { return this; }
        public Builder setContentTitle(CharSequence t) { return this; }
        public Builder setContentText(CharSequence t) { return this; }
        public Builder setContentIntent(PendingIntent pi) { return this; }
        public Builder addAction(int icon, CharSequence title, PendingIntent pi) { return this; }
        public Builder setOngoing(boolean b) { return this; }
        public Builder setOnlyAlertOnce(boolean b) { return this; }
        public Builder setPriority(int p) { return this; }
        public Builder setVisibility(int v) { return this; }
        public Builder setAutoCancel(boolean b) { return this; }
        public Builder setSilent(boolean b) { return this; }
        public Builder setCategory(String c) { return this; }
        public Notification build() { return new Notification(); }
    }
}
