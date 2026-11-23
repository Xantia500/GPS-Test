package android.view;
import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
public class View {
    public static final int VISIBLE = 0;
    public static final int INVISIBLE = 4;
    public static final int GONE = 8;
    public View(Context c) {}
    public View(Context c, AttributeSet a) {}
    public View(Context c, AttributeSet a, int d) {}
    public Context getContext() { return null; }
    public void invalidate() {}
    public void postInvalidate() {}
    public int getWidth() { return 0; }
    public int getHeight() { return 0; }
    public void setVisibility(int v) {}
    public int getVisibility() { return 0; }
    protected void onDraw(Canvas c) {}
    protected void onMeasure(int w, int h) { setMeasuredDimension(0,0); }
    protected final void setMeasuredDimension(int w, int h) {}
    public void setBackgroundColor(int c) {}
    @SuppressWarnings("unchecked")
    public <T extends View> T findViewById(int id) { return null; }
    public void setAlpha(float a) {}
    public void setPadding(int a, int b, int c, int d) {}
    public void requestLayout() {}
    public void setLayoutParams(Object p) {}
    public boolean post(Runnable r) { return true; }
    public void setOnClickListener(OnClickListener l) {}
    public interface OnClickListener { void onClick(View v); }
    public void setTag(Object o) {}
    public Object getTag() { return null; }
    public android.content.res.Resources getResources() { return new android.content.res.Resources(); }
}
