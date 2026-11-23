package androidx.activity.result.contract;
import java.util.Map;
public class ActivityResultContracts {
    public static class RequestMultiplePermissions
            extends ActivityResultContract<String[], Map<String, Boolean>> {}
    public static class RequestPermission
            extends ActivityResultContract<String, Boolean> {}
}
