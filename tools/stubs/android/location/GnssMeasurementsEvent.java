package android.location;
public class GnssMeasurementsEvent {
    public java.util.Collection<GnssMeasurement> getMeasurements() { return null; }
    public static abstract class Callback {
        public void onGnssMeasurementsReceived(GnssMeasurementsEvent event) {}
        public void onStatusChanged(int status) {}
    }
}
