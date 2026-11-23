package android.location;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.Executor;

/**
 * Subset des signatures LocationManager réellement présentes sur Android 7 à 16.
 * Ces stubs servent aux tests JVM et ne sont pas embarqués dans l'application.
 */
public class LocationManager {
    public static final String GPS_PROVIDER = "gps";
    public static final String NETWORK_PROVIDER = "network";

    public boolean isProviderEnabled(String provider) { return true; }

    // API historique (API 1), avec livraison sur un Looper (API 1).
    public void requestLocationUpdates(String provider, long minTimeMs, float minDistanceM,
                                       LocationListener listener) { }
    public void requestLocationUpdates(String provider, long minTimeMs, float minDistanceM,
                                       LocationListener listener, Looper looper) { }
    public void removeUpdates(LocationListener listener) { }

    // API 31 : LocationRequest + Executor.
    public void requestLocationUpdates(String provider, LocationRequest request,
                                       Executor executor, LocationListener listener) { }

    // API 24 : GNSS status sur Handler ; API 30 : Executor.
    public boolean registerGnssStatusCallback(GnssStatus.Callback callback, Handler handler) {
        return true;
    }
    public boolean registerGnssStatusCallback(GnssStatus.Callback callback) { return true; }
    public boolean registerGnssStatusCallback(Executor executor, GnssStatus.Callback callback) {
        return true;
    }
    public void unregisterGnssStatusCallback(GnssStatus.Callback callback) { }

    // API 24 : NMEA sur Handler ; API 30 : Executor.
    public boolean addNmeaListener(OnNmeaMessageListener listener, Handler handler) { return true; }
    public boolean addNmeaListener(OnNmeaMessageListener listener) { return true; }
    public boolean addNmeaListener(Executor executor, OnNmeaMessageListener listener) { return true; }
    public void removeNmeaListener(OnNmeaMessageListener listener) { }

    public boolean registerGnssMeasurementsCallback(GnssMeasurementsEvent.Callback callback) {
        return true;
    }
    public boolean registerGnssMeasurementsCallback(Executor executor,
                                                       GnssMeasurementsEvent.Callback callback) {
        return true;
    }
    public void unregisterGnssMeasurementsCallback(GnssMeasurementsEvent.Callback callback) { }
    public GnssCapabilities getGnssCapabilities() { return new GnssCapabilities(); }
    public boolean sendExtraCommand(String provider, String command, android.os.Bundle extras) {
        return true;
    }
}
