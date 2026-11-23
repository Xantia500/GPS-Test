package com.example.gpstest;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Moteur de secours strictement hors ligne.
 *
 * Il ne télécharge ni ne lit aucune tuile raster. Quand aucune carte Mapsforge
 * exploitable n'est disponible, il conserve l'utilité de l'écran (position et
 * trace GPX) sur un repère Web Mercator explicite, sans prétendre afficher un
 * fond géographique qui n'aurait pas été installé.
 */
public final class OfflinePlaceholderMapRenderer implements MapRenderer {
    private final OfflineCanvas view;
    private boolean destroyed;

    public OfflinePlaceholderMapRenderer(Activity activity) {
        view = new OfflineCanvas(activity);
    }

    @Override public View getView() { return view; }
    @Override public boolean isOffline() { return true; }
    @Override public int getInstalledRegionCount() { return 0; }
    @Override public boolean coversCoordinate(double latitude, double longitude) { return false; }
    @Override public void setMyLocation(MapPoint point, boolean fresh) {
        if (!destroyed) view.setMyLocation(point, fresh);
    }
    @Override public void animateTo(MapPoint point) {
        if (!destroyed) view.centerOn(point);
    }
    @Override public void setMapStyle(MapStyle style) { /* Aucun thème sans carte Mapsforge. */ }
    @Override public void setMapOrientation(float degrees) {
        if (!destroyed) view.setMapOrientation(degrees);
    }
    @Override public void setOnUserInteractionListener(Runnable listener) {
        if (!destroyed) view.setOnUserInteractionListener(listener);
    }
    @Override public void replaceTrace(List<List<MapPoint>> segments, MapPoint lastPoint,
            String title, double minLatitude, double maxLatitude,
            double westLongitude, double eastLongitude, double centerLongitude,
            boolean pointLike, int color, float strokeWidthPx) {
        if (!destroyed) view.replaceTrace(segments, lastPoint, color, strokeWidthPx);
    }
    @Override public void onResume() { }
    @Override public void onPause() { }
    @Override public void destroy() {
        destroyed = true;
        view.clear();
    }

    private static final class OfflineCanvas extends View {
        private static final int BG = 0xFF101923;
        private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gridText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tracePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint locationFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint locationStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint endFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int touchSlop;
        private MapRegionProjection.Bounds bounds = MapRegionProjection.worldBounds();
        private List<List<MapPoint>> traceSegments = Collections.emptyList();
        private MapPoint lastPoint;
        private MapPoint location;
        private boolean locationFresh;
        private float zoom = 1f;
        private float panX, panY;
        private float rotationDegrees;
        private float downX, downY, lastX, lastY, lastPinchDistance;
        private boolean moved, pinching, interactionNotified;
        private float pinchFocusX, pinchFocusY;
        private double pinchAnchorLat = Double.NaN, pinchAnchorLon = Double.NaN;
        private Runnable userInteractionListener;

        OfflineCanvas(Context context) {
            super(context);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            // Conserver l'accélération matérielle Android : le canevas ne
            // requiert aucun effet non accéléré et peut afficher de longues traces.
            setBackgroundColor(BG);
            grid.setStyle(Paint.Style.STROKE);
            grid.setStrokeWidth(dp(0.8f));
            grid.setColor(0x334F7796);
            gridText.setColor(0xFF8195A9);
            gridText.setTextSize(dp(9));
            tracePaint.setStyle(Paint.Style.STROKE);
            tracePaint.setStrokeCap(Paint.Cap.ROUND);
            tracePaint.setStrokeJoin(Paint.Join.ROUND);
            locationFill.setStyle(Paint.Style.FILL);
            locationStroke.setStyle(Paint.Style.STROKE);
            locationStroke.setColor(0xFFF2F5F7);
            locationStroke.setStrokeWidth(dp(2));
            endFill.setStyle(Paint.Style.FILL);
            endFill.setColor(0xFFFF8A24);
            textPaint.setColor(0xFFBBC6D2);
            textPaint.setTextSize(dp(11));
            textPaint.setTextAlign(Paint.Align.CENTER);
        }

        void setMyLocation(MapPoint point, boolean fresh) {
            if (point == null) return;
            location = point;
            locationFresh = fresh;
            invalidate();
        }

        void centerOn(MapPoint point) {
            if (point == null || getWidth() <= 0 || getHeight() <= 0) return;
            double[] xy = project(point.latitude, point.longitude);
            if (!Double.isFinite(xy[0]) || !Double.isFinite(xy[1])) return;
            panX += getWidth() * 0.5 - (float) xy[0];
            panY += getHeight() * 0.5 - (float) xy[1];
            invalidate();
        }

        void setMapOrientation(float degrees) {
            if (Float.isFinite(degrees)) {
                rotationDegrees = Compass.normalize(degrees);
                invalidate();
            }
        }

        void setOnUserInteractionListener(Runnable listener) {
            userInteractionListener = listener;
        }

        void replaceTrace(List<List<MapPoint>> segments, MapPoint end,
                int color, float strokeWidthPx) {
            ArrayList<List<MapPoint>> copied = new ArrayList<>();
            ArrayList<MapPoint> allPoints = new ArrayList<>();
            if (segments != null) {
                for (List<MapPoint> segment : segments) {
                    if (segment == null || segment.isEmpty()) continue;
                    ArrayList<MapPoint> safe = new ArrayList<>(segment.size());
                    for (MapPoint point : segment) {
                        if (point != null) {
                            safe.add(point);
                            allPoints.add(point);
                        }
                    }
                    if (!safe.isEmpty()) copied.add(Collections.unmodifiableList(safe));
                }
            }
            if (end != null) allPoints.add(end);
            traceSegments = Collections.unmodifiableList(copied);
            lastPoint = end;
            if (!allPoints.isEmpty()) {
                bounds = MapRegionProjection.fromPoints(allPoints);
                zoom = 0.92f;
                panX = panY = 0f;
            }
            tracePaint.setColor(color);
            tracePaint.setStrokeWidth(Math.max(dp(1.5f), strokeWidthPx));
            invalidate();
        }

        void clear() {
            userInteractionListener = null;
            traceSegments = Collections.emptyList();
            lastPoint = null;
            location = null;
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(BG);
            if (getWidth() <= 0 || getHeight() <= 0) return;
            canvas.save();
            canvas.rotate(rotationDegrees, getWidth() * 0.5f, getHeight() * 0.5f);
            drawGraticule(canvas);
            drawTrace(canvas);
            drawPosition(canvas);
            canvas.restore();
            textPaint.setColor(0xFFBBC6D2);
            canvas.drawText("REPÈRES GÉOGRAPHIQUES · AUCUN FOND RÉSEAU",
                    getWidth() * 0.5f, getHeight() - dp(12), textPaint);
        }

        private void drawGraticule(Canvas canvas) {
            double scale = MapRegionProjection.scale(bounds, getWidth(), getHeight(), zoom,
                    Math.round(dp(34)), Math.round(dp(46)));
            double visibleLongitude = getWidth() / scale * 180.0 / Math.PI;
            double step = longitudeStep(visibleLongitude);
            double left = bounds.centerLongitude - visibleLongitude * 0.5
                    - panX / scale * 180.0 / Math.PI;
            double right = bounds.centerLongitude + visibleLongitude * 0.5
                    - panX / scale * 180.0 / Math.PI;
            double first = Math.ceil(left / step) * step;
            for (double lon = first; lon <= right && lon - first < 80 * step; lon += step) {
                double[] a = project(MapRegionProjection.MAX_MERCATOR_LATITUDE, lon);
                double[] b = project(-MapRegionProjection.MAX_MERCATOR_LATITUDE, lon);
                canvas.drawLine((float) a[0], (float) a[1], (float) b[0], (float) b[1], grid);
                if (a[0] >= 4 && a[0] <= getWidth() - 4) {
                    canvas.drawText(Math.round(MapRegionProjection.normalizeLongitude(lon)) + "°",
                            (float) a[0] + dp(3), dp(13), gridText);
                }
            }
            double latitudeStep = Math.max(1.0, step * 0.5);
            double topLeft[] = unproject(0, 0);
            double bottomRight[] = unproject(getWidth(), getHeight());
            double minLat = Math.max(-85, Math.min(topLeft[0], bottomRight[0]) - latitudeStep);
            double maxLat = Math.min(85, Math.max(topLeft[0], bottomRight[0]) + latitudeStep);
            for (double lat = Math.ceil(minLat / latitudeStep) * latitudeStep;
                    lat <= maxLat; lat += latitudeStep) {
                // Les longitudes sont normalisées autour du centre par project() :
                // utiliser center +/- 360 produirait le même x et donc une ligne
                // horizontale de longueur nulle. Le y dépend seulement de la latitude.
                double[] atLatitude = project(lat, bounds.centerLongitude);
                float y = (float) atLatitude[1];
                canvas.drawLine(0f, y, getWidth(), y, grid);
                if (y >= dp(12) && y <= getHeight() - dp(20)) {
                    canvas.drawText(Math.round(lat) + "°", dp(18), y - dp(2), gridText);
                }
            }
        }

        private static double longitudeStep(double spanDegrees) {
            double[] steps = {0.1, 0.2, 0.5, 1, 2, 5, 10, 15, 30, 45, 90};
            double target = Math.max(0.1, spanDegrees / 6.0);
            for (double step : steps) if (step >= target) return step;
            return 180.0;
        }

        private void drawTrace(Canvas canvas) {
            for (List<MapPoint> segment : traceSegments) {
                if (segment.size() < 2) continue;
                Path path = new Path();
                boolean started = false;
                for (MapPoint point : segment) {
                    double[] xy = project(point.latitude, point.longitude);
                    if (!Double.isFinite(xy[0]) || !Double.isFinite(xy[1])) continue;
                    if (!started) {
                        path.moveTo((float) xy[0], (float) xy[1]);
                        started = true;
                    } else {
                        path.lineTo((float) xy[0], (float) xy[1]);
                    }
                }
                if (started) canvas.drawPath(path, tracePaint);
            }
            if (lastPoint != null) {
                double[] xy = project(lastPoint.latitude, lastPoint.longitude);
                if (visible(xy)) {
                    canvas.drawCircle((float) xy[0], (float) xy[1], dp(6), endFill);
                    canvas.drawCircle((float) xy[0], (float) xy[1], dp(6), locationStroke);
                }
            }
        }

        private void drawPosition(Canvas canvas) {
            if (location == null) return;
            double[] xy = project(location.latitude, location.longitude);
            if (!visible(xy)) return;
            locationFill.setColor(locationFresh ? 0xFF20B45B : 0xFFFFA126);
            canvas.drawCircle((float) xy[0], (float) xy[1], dp(8), locationFill);
            canvas.drawCircle((float) xy[0], (float) xy[1], dp(8), locationStroke);
            canvas.drawCircle((float) xy[0], (float) xy[1], dp(2), locationStroke);
        }

        private boolean visible(double[] xy) {
            return xy != null && xy.length >= 2 && Double.isFinite(xy[0]) && Double.isFinite(xy[1])
                    && xy[0] >= -dp(40) && xy[0] <= getWidth() + dp(40)
                    && xy[1] >= -dp(40) && xy[1] <= getHeight() + dp(40);
        }

        private double[] project(double latitude, double longitude) {
            return MapRegionProjection.project(latitude, longitude, bounds,
                    getWidth(), getHeight(), zoom, panX, panY,
                    Math.round(dp(34)), Math.round(dp(46)));
        }

        private double[] unproject(float screenX, float screenY) {
            return MapRegionProjection.unproject(screenX, screenY, bounds,
                    getWidth(), getHeight(), zoom, panX, panY,
                    Math.round(dp(34)), Math.round(dp(46)));
        }

        private float[] unrotate(float x, float y) {
            double radians = Math.toRadians(-rotationDegrees);
            float cx = getWidth() * 0.5f, cy = getHeight() * 0.5f;
            float dx = x - cx, dy = y - cy;
            float rx = (float) (dx * Math.cos(radians) - dy * Math.sin(radians));
            float ry = (float) (dx * Math.sin(radians) + dy * Math.cos(radians));
            return new float[]{cx + rx, cy + ry};
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event == null) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    downX = lastX = event.getX();
                    downY = lastY = event.getY();
                    moved = pinching = interactionNotified = false;
                    return true;
                }
                case MotionEvent.ACTION_POINTER_DOWN: {
                    if (event.getPointerCount() >= 2) {
                        lastPinchDistance = distance(event);
                        pinchFocusX = midpointX(event);
                        pinchFocusY = midpointY(event);
                        float[] focus = unrotate(pinchFocusX, pinchFocusY);
                        double[] anchor = unproject(focus[0], focus[1]);
                        pinchAnchorLat = anchor[0];
                        pinchAnchorLon = anchor[1];
                        pinching = moved = true;
                        notifyInteraction();
                    }
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (event.getPointerCount() >= 2) {
                        float distance = distance(event);
                        float focusX = midpointX(event), focusY = midpointY(event);
                        if (lastPinchDistance > 0 && distance > 0) {
                            zoom = Math.max(0.15f, Math.min(24f, zoom * distance / lastPinchDistance));
                            float[] focus = unrotate(focusX, focusY);
                            double[] screen = project(pinchAnchorLat, pinchAnchorLon);
                            panX += focus[0] - (float) screen[0];
                            panY += focus[1] - (float) screen[1];
                            invalidate();
                        }
                        lastPinchDistance = distance;
                        pinching = moved = true;
                        notifyInteraction();
                    } else if (!pinching) {
                        float x = event.getX(), y = event.getY();
                        float dx = x - lastX, dy = y - lastY;
                        panX += dx;
                        panY += dy;
                        if (Math.abs(x - downX) > touchSlop || Math.abs(y - downY) > touchSlop) {
                            moved = true;
                            notifyInteraction();
                        }
                        lastX = x;
                        lastY = y;
                        invalidate();
                    }
                    return true;
                }
                case MotionEvent.ACTION_POINTER_UP: {
                    pinching = false;
                    lastPinchDistance = 0f;
                    if (event.getPointerCount() > 1) {
                        int remaining = event.getActionIndex() == 0 ? 1 : 0;
                        lastX = downX = event.getX(remaining);
                        lastY = downY = event.getY(remaining);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    pinching = false;
                    lastPinchDistance = 0f;
                    performClick();
                    return true;
                default:
                    return true;
            }
        }

        private void notifyInteraction() {
            if (interactionNotified) return;
            interactionNotified = true;
            if (userInteractionListener != null) userInteractionListener.run();
        }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }

        private static float distance(MotionEvent event) {
            if (event.getPointerCount() < 2) return 0f;
            float dx = event.getX(0) - event.getX(1);
            float dy = event.getY(0) - event.getY(1);
            return (float) Math.sqrt(dx * dx + dy * dy);
        }
        private static float midpointX(MotionEvent event) {
            return (event.getX(0) + event.getX(1)) * 0.5f;
        }
        private static float midpointY(MotionEvent event) {
            return (event.getY(0) + event.getY(1)) * 0.5f;
        }
        private float dp(float value) {
            return value * getResources().getDisplayMetrics().density;
        }
    }
}
