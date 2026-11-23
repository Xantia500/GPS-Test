package androidx.appcompat.app;
import android.app.Activity;
import android.os.Bundle;
import androidx.activity.result.ActivityResultCaller;
public class AppCompatActivity extends Activity implements ActivityResultCaller {
    @Override protected void onCreate(Bundle b) {}
    @Override public void setContentView(int res) {}
    public <I, O> androidx.activity.result.ActivityResultLauncher<I> registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContract<I, O> c,
            androidx.activity.result.ActivityResultCallback<O> cb) { return null; }
    public void runOnUiThread(Runnable r) {}
    @Override public java.util.concurrent.Executor getMainExecutor() { return null; }
}
