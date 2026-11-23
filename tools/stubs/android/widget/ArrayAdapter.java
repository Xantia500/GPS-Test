package android.widget;
import android.content.Context;
import android.view.View;
public class ArrayAdapter<T> extends BaseAdapter {
    public ArrayAdapter(Context c, int res, T[] objects) {}
    public ArrayAdapter(Context c, int res, java.util.List<T> objects) {}
    public void setDropDownViewResource(int res) {}
    @Override public int getCount() { return 0; }
    @Override public Object getItem(int p) { return null; }
    @Override public long getItemId(int p) { return 0; }
    @Override public View getView(int p, View v, android.view.ViewGroup g) { return null; }
}
