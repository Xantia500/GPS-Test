package android.hardware;
import android.os.Handler;
import android.os.Looper;
public class SensorManager {
    public static final int SENSOR_DELAY_UI = 2;
    public static final int SENSOR_DELAY_NORMAL = 3;
    public Sensor getDefaultSensor(int type) { return null; }
    public boolean registerListener(SensorEventListener l, Sensor s, int rate) { return true; }
    public boolean registerListener(SensorEventListener l, Sensor s, int rate, Handler h) { return true; }
    public void unregisterListener(SensorEventListener l) {}
    public static boolean getRotationMatrixFromVector(float[] R, float[] v) { return true; }
    public static float[] getOrientation(float[] R, float[] v) { return v; }
}
