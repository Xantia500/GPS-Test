package android.app;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Window;
public class Activity extends Context {
    public Window getWindow() { return new Window(); }
    protected void onCreate(Bundle b) {}
    protected void onStart() {}
    protected void onResume() {}
    protected void onPause() {}
    protected void onStop() {}
    protected void onDestroy() {}
    public void setContentView(int res) {}
    public <T extends android.view.View> T findViewById(int id) { return null; }
    public void finish() {}
    public void startActivity(Intent i) {}
    public Intent getIntent() { return null; }
    public void runOnUiThread(Runnable r) {}
    public java.util.concurrent.Executor getMainExecutor() { return null; }
    public void onSaveInstanceState(Bundle b) {}
    public void onBackPressed() {}
}
