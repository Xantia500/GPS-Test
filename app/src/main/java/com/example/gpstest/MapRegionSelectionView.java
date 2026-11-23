package com.example.gpstest;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.List;

/** Carte vectorielle légère de sélection des régions, dessinée depuis les .poly. */
public final class MapRegionSelectionView extends View {
    public static final int STATE_AVAILABLE = 0;
    public static final int STATE_INSTALLED = 1;
    public static final int STATE_UPDATE = 2;

    public static final class RegionItem {
        public final String regionId;
        public final String displayName;
        public final MapsforgePolyParser.Polygon polygon;
        public final int state;
        public final Object tag;
        public RegionItem(String regionId, String displayName,
                MapsforgePolyParser.Polygon polygon, int state, Object tag) {
            this.regionId = regionId;
            this.displayName = displayName;
            this.polygon = polygon;
            this.state = state;
            this.tag = tag;
        }
    }

    public interface OnRegionClickListener { void onRegionClick(RegionItem item); }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint graticule = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint coordinateLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<RegionItem> regions = new ArrayList<>();
    private OnRegionClickListener listener;
    private String selectedId;
    private MapRegionProjection.Bounds bounds = MapRegionProjection.worldBounds();
    private float zoom = 1f, panX, panY;
    private float downX, downY, lastX, lastY, lastPinchDistance;
    private float pinchFocusX, pinchFocusY;
    private double pinchAnchorLatitude = Double.NaN, pinchAnchorLongitude = Double.NaN;
    private boolean moved, pinching;
    private final int touchSlop;

    public MapRegionSelectionView(Context context) {
        super(context);
        // Laisser le rendu accéléré Android : les contours régionaux peuvent
        // représenter des dizaines de milliers de sommets au total.
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(1.3f));
        label.setTextSize(dp(10));
        label.setTextAlign(Paint.Align.CENTER);
        hint.setTextSize(dp(12));
        hint.setTextAlign(Paint.Align.CENTER);
        hint.setColor(0xFFB8C4D2);
        graticule.setStyle(Paint.Style.STROKE);
        graticule.setStrokeWidth(dp(0.7f));
        graticule.setColor(0x254F7796);
        coordinateLabel.setTextSize(dp(9));
        coordinateLabel.setColor(0xFF8195A9);
        coordinateLabel.setShadowLayer(dp(1), 0, 0, 0xCC101923);
        setBackgroundColor(0xFF101923);
    }

    public void setOnRegionClickListener(OnRegionClickListener listener) { this.listener = listener; }

    /** Charge un nouveau dossier et ajuste la caméra pour cadrer ses régions. */
    public void setRegions(List<RegionItem> items) {
        regions.clear();
        if (items != null) regions.addAll(items);
        selectedId = null;
        zoom = 1f; panX = 0; panY = 0;
        recomputeBounds();
        invalidate();
    }

    /** Met à jour un filtre/état sans réinitialiser le zoom et le panoramique utilisateur. */
    public void setRegionsPreservingView(List<RegionItem> items) {
        // Cette méthode sert aux changements de filtre/état du même dossier.
        // Garder les bounds de la caméra : recalculer l'emprise sur le sous-ensemble
        // filtré modifierait l'échelle et ferait sauter la carte sous le doigt.
        regions.clear();
        if (items != null) regions.addAll(items);
        if (selectedId != null) {
            boolean stillPresent = false;
            for (RegionItem item : regions) {
                if (item != null && selectedId.equals(item.regionId)) { stillPresent = true; break; }
            }
            if (!stillPresent) selectedId = null;
        }
        invalidate();
    }

    public void select(String regionId) {
        selectedId = regionId;
        invalidate();
    }

    /** Replace la caméra sur l'emprise du dossier courant. */
    public void resetView() {
        zoom = 1f;
        panX = 0f;
        panY = 0f;
        invalidate();
    }

    /** Sélection d'une région à partir de coordonnées géographiques. */
    public boolean selectAt(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) return false;
        for (int i = regions.size() - 1; i >= 0; i--) {
            RegionItem item = regions.get(i);
            if (item != null && item.polygon != null
                    && item.polygon.contains(latitude, longitude)) {
                selectedId = item.regionId;
                invalidate();
                if (listener != null) listener.onRegionClick(item);
                return true;
            }
        }
        return false;
    }

    private void recomputeBounds() {
        ArrayList<MapsforgePolyParser.Polygon> polygons = new ArrayList<>(regions.size());
        for (RegionItem item : regions) if (item != null && item.polygon != null) polygons.add(item.polygon);
        bounds = MapRegionProjection.fromPolygons(polygons);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(0xFF101923);
        drawGraticule(canvas);
        if (regions.isEmpty()) {
            canvas.drawText("Aucune frontière disponible dans ce dossier", getWidth() / 2f,
                    getHeight() / 2f, hint);
            canvas.drawText("Choisissez un continent ou une région dans la liste", getWidth() / 2f,
                    getHeight() / 2f + dp(22), hint);
            return;
        }
        for (RegionItem region : regions) {
            if (region == null || region.polygon == null) continue;
            Path path = buildPath(region.polygon);
            if (path == null) continue;
            fill.setColor(fillColor(region));
            stroke.setColor(strokeColor(region));
            stroke.setStrokeWidth(region.regionId.equals(selectedId) ? dp(2.4f) : dp(1.1f));
            canvas.drawPath(path, fill);
            canvas.drawPath(path, stroke);
            double effectiveZoom = zoom;
            if (effectiveZoom <= 4.2 || region.regionId.equals(selectedId)) {
                double lon = MapRegionProjection.polygonCenterLongitude(region.polygon);
                double lat = (region.polygon.minLatitude + region.polygon.maxLatitude) / 2d;
                double[] xy = project(lat, lon);
                if (xy[0] > 4 && xy[0] < getWidth() - 4 && xy[1] > 14 && xy[1] < getHeight() - 6) {
                    label.setColor(0xFFEAF0F6);
                    label.setShadowLayer(dp(2), 0, 0, 0xCC000000);
                    canvas.drawText(region.displayName, (float) xy[0], (float) xy[1], label);
                    label.clearShadowLayer();
                }
            }
        }
        hint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("Pincer pour zoomer · déplacer · toucher une région",
                dp(10), getHeight() - dp(12), hint);
        hint.setTextAlign(Paint.Align.CENTER);
    }

    /** Graticule discret : repères géographiques constants même sans carte déjà installée. */
    private void drawGraticule(Canvas canvas) {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        double step = zoom >= 5f ? 5.0 : zoom >= 2.2f ? 15.0 : 30.0;
        double start = Math.floor((bounds.centerLongitude - 180.0) / step) * step;
        canvas.save();
        canvas.clipRect(0, 0, getWidth(), getHeight());
        for (double longitude = start; longitude <= bounds.centerLongitude + 180.0; longitude += step) {
            double[] a = project(-MapRegionProjection.MAX_MERCATOR_LATITUDE, longitude);
            double[] b = project(MapRegionProjection.MAX_MERCATOR_LATITUDE, longitude);
            canvas.drawLine((float) a[0], (float) a[1], (float) b[0], (float) b[1], graticule);
            if (b[0] >= 3 && b[0] <= getWidth() - 3) {
                canvas.drawText(formatCoordinate(longitude), (float) b[0] + dp(2),
                        getHeight() - dp(4), coordinateLabel);
            }
        }
        double latStep = step >= 30.0 ? 20.0 : step >= 15.0 ? 10.0 : 5.0;
        for (double latitude = -80.0; latitude <= 80.0; latitude += latStep) {
            double[] a = project(latitude, bounds.centerLongitude - 180.0);
            double[] b = project(latitude, bounds.centerLongitude + 180.0);
            canvas.drawLine((float) a[0], (float) a[1], (float) b[0], (float) b[1], graticule);
            if (a[1] >= dp(11) && a[1] <= getHeight() - dp(18)) {
                canvas.drawText(formatCoordinate(latitude), dp(4), (float) a[1] - dp(2), coordinateLabel);
            }
        }
        canvas.restore();
    }

    private static String formatCoordinate(double value) {
        long rounded = Math.round(value);
        return rounded + "°";
    }

    private Path buildPath(MapsforgePolyParser.Polygon polygon) {
        Path path = new Path();
        path.setFillType(Path.FillType.EVEN_ODD);
        boolean any = false;
        for (MapsforgePolyParser.Ring ring : polygon.rings) {
            if (ring.points.size() < 3) continue;
            MapsforgePolyParser.Point first = ring.points.get(0);
            double previousLongitude = MapRegionProjection.unwrapLongitude(
                    first.longitude, bounds.centerLongitude);
            double[] start = project(first.latitude, previousLongitude);
            path.moveTo((float) start[0], (float) start[1]);
            int size = ring.points.size();
            int stride = MapsforgePolygonSampler.stride(size);
            for (int i = 1; i < size - 1; i += stride) {
                MapsforgePolyParser.Point point = ring.points.get(i);
                previousLongitude = MapRegionProjection.unwrapLongitude(
                        point.longitude, previousLongitude);
                double[] xy = project(point.latitude, previousLongitude);
                path.lineTo((float) xy[0], (float) xy[1]);
            }
            MapsforgePolyParser.Point last = ring.points.get(size - 1);
            previousLongitude = MapRegionProjection.unwrapLongitude(last.longitude, previousLongitude);
            double[] end = project(last.latitude, previousLongitude);
            path.lineTo((float) end[0], (float) end[1]);
            path.close();
            any = true;
        }
        return any ? path : null;
    }

    private double[] project(double latitude, double longitude) {
        return MapRegionProjection.project(latitude, longitude, bounds, getWidth(), getHeight(),
                zoom, panX, panY, Math.round(dp(32)), Math.round(dp(40)));
    }

    private double[] unproject(float x, float y) {
        return MapRegionProjection.unproject(x, y, bounds, getWidth(), getHeight(), zoom,
                panX, panY, Math.round(dp(32)), Math.round(dp(40)));
    }

    private int fillColor(RegionItem item) {
        if (item.regionId.equals(selectedId)) return 0x8879C5FF;
        if (item.state == STATE_INSTALLED) return 0x6643C77B;
        if (item.state == STATE_UPDATE) return 0x66F1A340;
        return 0x553C8DCE;
    }

    private int strokeColor(RegionItem item) {
        if (item.regionId.equals(selectedId)) return 0xFFFFD166;
        if (item.state == STATE_INSTALLED) return 0xFF42C57B;
        if (item.state == STATE_UPDATE) return 0xFFFFB454;
        return 0xFF6DA9D8;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = event.getX(); downY = lastY = event.getY();
                moved = false; pinching = false;
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (event.getPointerCount() >= 2) {
                    lastPinchDistance = distance(event);
                    pinchFocusX = midpointX(event);
                    pinchFocusY = midpointY(event);
                    double[] anchor = unproject(pinchFocusX, pinchFocusY);
                    pinchAnchorLatitude = anchor[0];
                    pinchAnchorLongitude = anchor[1];
                    pinching = true;
                    moved = true;
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() >= 2) {
                    float distance = distance(event);
                    float focusX = midpointX(event), focusY = midpointY(event);
                    if (lastPinchDistance > 0 && distance > 0) {
                        zoom = Math.max(0.8f, Math.min(12f, zoom * distance / lastPinchDistance));
                        // Conserver le point géographique sous les doigts pendant
                        // le zoom, y compris si le milieu des doigts se déplace.
                        double[] screen = project(pinchAnchorLatitude, pinchAnchorLongitude);
                        panX += focusX - (float) screen[0];
                        panY += focusY - (float) screen[1];
                        invalidate();
                    }
                    lastPinchDistance = distance;
                    pinchFocusX = focusX;
                    pinchFocusY = focusY;
                    pinching = true;
                    moved = true;
                } else if (!pinching) {
                    float x = event.getX(), y = event.getY();
                    float dx = x - lastX, dy = y - lastY;
                    panX += dx; panY += dy;
                    if (Math.abs(x - downX) > touchSlop || Math.abs(y - downY) > touchSlop) moved = true;
                    lastX = x; lastY = y;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                pinching = false;
                lastPinchDistance = 0;
                if (event.getPointerCount() > 1) {
                    int remaining = event.getActionIndex() == 0 ? 1 : 0;
                    lastX = event.getX(remaining);
                    lastY = event.getY(remaining);
                    downX = lastX; downY = lastY;
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!moved && !regions.isEmpty()) {
                    double[] coordinate = unproject(event.getX(), event.getY());
                    for (int i = regions.size() - 1; i >= 0; i--) {
                        RegionItem item = regions.get(i);
                        if (item.polygon.contains(coordinate[0], coordinate[1])) {
                            selectedId = item.regionId;
                            invalidate();
                            performClick();
                            if (listener != null) listener.onRegionClick(item);
                            return true;
                        }
                    }
                }
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                pinching = false;
                lastPinchDistance = 0;
                return true;
            default: return true;
        }
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    private static float distance(MotionEvent event) {
        if (event.getPointerCount() < 2) return 0;
        float dx = event.getX(0) - event.getX(1);
        float dy = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
    private static float midpointX(MotionEvent event) { return (event.getX(0) + event.getX(1)) / 2f; }
    private static float midpointY(MotionEvent event) { return (event.getY(0) + event.getY(1)) / 2f; }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
}
