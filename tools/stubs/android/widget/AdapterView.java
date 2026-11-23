package android.widget;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
public class AdapterView<T> extends ViewGroup {
    public AdapterView(Context c) { super(c); }
    public AdapterView(Context c, AttributeSet a) { super(c, a); }
    public void setAdapter(Adapter a) {}
    public void setSelection(int p) {}
    public void setSelection(int p, boolean animate) {}
    public void setOnItemSelectedListener(OnItemSelectedListener l) {}
    public interface OnItemSelectedListener {
        void onItemSelected(AdapterView<?> parent, View view, int position, long id);
        void onNothingSelected(AdapterView<?> parent);
    }
}
