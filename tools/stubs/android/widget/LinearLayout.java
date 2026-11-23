package android.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.ViewGroup;

/** Lightweight JVM-test stub with the Android LayoutParams constructor shapes. */
public class LinearLayout extends ViewGroup {
    public static final int HORIZONTAL = 0;
    public static final int VERTICAL = 1;

    public LinearLayout(Context context) { super(context); }
    public LinearLayout(Context context, AttributeSet attrs) { super(context, attrs); }
    public void setOrientation(int orientation) { }
    public void setGravity(int gravity) { }

    public static class LayoutParams extends ViewGroup.LayoutParams {
        public float weight;
        public LayoutParams(int width, int height) { }
        public LayoutParams(int width, int height, float weight) { this.weight = weight; }
    }
}
