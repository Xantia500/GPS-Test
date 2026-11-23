package android.widget;
import android.content.Context;
import android.util.AttributeSet;
public class RadioButton extends TextView {
    public RadioButton(Context c) { super(c); }
    public RadioButton(Context c, AttributeSet a) { super(c, a); }
    public void setChecked(boolean b) {}
    public boolean isChecked() { return false; }
}
