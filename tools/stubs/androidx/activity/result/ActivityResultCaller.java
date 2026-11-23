package androidx.activity.result;
import androidx.activity.result.contract.ActivityResultContract;
public interface ActivityResultCaller {
    <I, O> ActivityResultLauncher<I> registerForActivityResult(
            ActivityResultContract<I, O> contract, ActivityResultCallback<O> callback);
}
