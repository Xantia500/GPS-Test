package android.location;
public class GnssStatus {
    public int getSatelliteCount() { return 0; }
    public boolean usedInFix(int i) { return false; }
    public float getCn0DbHz(int i) { return 0; }
    public int getConstellationType(int i) { return 0; }
    public int getSvid(int i) { return 0; }
    public float getAzimuthDegrees(int i) { return 0; }
    public float getElevationDegrees(int i) { return 0; }
    public boolean hasCarrierFrequencyHz(int i) { return false; }
    public float getCarrierFrequencyHz(int i) { return 0; }
    public static abstract class Callback {
        public void onSatelliteStatusChanged(GnssStatus status) {}
        public void onStarted() {}
        public void onStopped() {}
        public void onFirstFix(int ttff) {}
    }
}
