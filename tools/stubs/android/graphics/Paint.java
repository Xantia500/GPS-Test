package android.graphics;
public class Paint {
    public static final int ANTI_ALIAS_FLAG = 1;
    public static final int FILL = 0;
    public static final int STROKE = 1;
    public Paint() {}
    public Paint(int flags) {}
    public void setColor(int c) {}
    public void setStrokeWidth(float w) {}
    public void setStyle(int s) {}
    public void setTextSize(float s) {}
    public void setTextAlign(int a) {}
    public void setAntiAlias(boolean b) {}
    public void setTypeface(Object t) {}
    public float measureText(String t) { return 0; }
    public void setStrokeCap(int c) {}
    public static final int CAP_ROUND = 1;
    public static class Cap {
        public static final int BUTT = 0;
        public static final int ROUND = 1;
        public static final int SQUARE = 2;
    }
    public static class Style {
        public static final int FILL = 0;
        public static final int STROKE = 1;
        public static final int FILL_AND_STROKE = 2;
    }
    public static class Align {
        public static final int LEFT = 0;
        public static final int CENTER = 1;
        public static final int RIGHT = 2;
    }
}
