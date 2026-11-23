package android.widget;
import android.content.Context;
import android.util.AttributeSet;
import android.view.ViewGroup;
public class RadioGroup extends ViewGroup {
    public RadioGroup(Context c) { super(c); }
    public RadioGroup(Context c, AttributeSet a) { super(c, a); }
    public void setOnCheckedChangeListener(OnCheckedChangeListener l) {}
    public int getCheckedRadioButtonId() { return 0; }
    public interface OnCheckedChangeListener {
        void onCheckedChanged(RadioGroup g, int checkedId);
    }
}
