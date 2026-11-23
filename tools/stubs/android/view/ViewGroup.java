package android.view;
import android.content.Context;
import android.util.AttributeSet;
public class ViewGroup extends View {
    public ViewGroup(Context c) { super(c); }
    public ViewGroup(Context c, AttributeSet a) { super(c, a); }
    public ViewGroup(Context c, AttributeSet a, int d) { super(c, a, d); }
    public void addView(View v) {}
    public int getChildCount() { return 0; }
    public View getChildAt(int i) { return null; }
    public static class LayoutParams {}
}
