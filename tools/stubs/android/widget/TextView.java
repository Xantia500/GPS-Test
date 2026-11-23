package android.widget;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.text.method.MovementMethod;
public class TextView extends View {
    public TextView(Context c) { super(c); }
    public TextView(Context c, AttributeSet a) { super(c, a); }
    public void setText(CharSequence t) {}
    public void setText(int res) {}
    public CharSequence getText() { return null; }
    public void setMovementMethod(MovementMethod m) {}
    public void setTextSize(float s) {}
    public void setTextColor(int c) {}
    public void setVisibility(int v) {}
}
