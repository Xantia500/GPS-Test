package android.location;

/** Signature minimale du LocationRequest.Builder d'Android 12+ (API 31). */
public final class LocationRequest {
    public static final int QUALITY_HIGH_ACCURACY = 100;

    private LocationRequest() { }

    public static final class Builder {
        public Builder(long intervalMillis) { }
        public Builder setMinUpdateIntervalMillis(long millis) { return this; }
        public Builder setMinUpdateDistanceMeters(float meters) { return this; }
        public Builder setQuality(int quality) { return this; }
        public Builder setMaxUpdateAgeMillis(long millis) { return this; }
        public Builder setMaxUpdateDelayMillis(long millis) { return this; }
        public LocationRequest build() { return new LocationRequest(); }
    }
}
