package android.location;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.List;
/** Stub compatible with compileSdk 37.2; API 24 devices still require the legacy callbacks. */
public interface LocationListener {
    void onLocationChanged(@NonNull Location location);
    default void onLocationChanged(@NonNull List<Location> locations) {
        for (Location location : locations) onLocationChanged(location);
    }
    void onProviderDisabled(@NonNull String provider);
    void onProviderEnabled(@NonNull String provider);
    @SuppressWarnings("deprecation")
    void onStatusChanged(@NonNull String provider, int status, @Nullable Bundle extras);
}
