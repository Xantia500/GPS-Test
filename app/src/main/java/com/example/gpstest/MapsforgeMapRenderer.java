package com.example.gpstest;

import android.content.Context;
import android.view.View;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import org.mapsforge.core.graphics.Paint;
import org.mapsforge.core.graphics.Style;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.Rotation;
import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.android.util.AndroidUtil;
import org.mapsforge.map.android.view.MapView;
import org.mapsforge.map.datastore.MapDataStore;
import org.mapsforge.map.datastore.MultiMapDataStore;
import org.mapsforge.map.layer.Layer;
import org.mapsforge.map.layer.Layers;
import org.mapsforge.map.layer.cache.TileCache;
import org.mapsforge.map.layer.overlay.FixedPixelCircle;
import org.mapsforge.map.layer.overlay.Polyline;
import org.mapsforge.map.layer.renderer.TileRendererLayer;
import org.mapsforge.map.reader.MapFile;
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Moteur vectoriel Mapsforge lisant uniquement des fichiers .map installés localement. */
public final class MapsforgeMapRenderer implements MapRenderer {
    private static final Object GRAPHICS_LOCK = new Object();
    private static boolean graphicsInitialized;
    private static int activeRenderers;

    private final Context context;
    private final Context applicationContext;
    private final MapView mapView;
    private final ArrayList<OfflineMapRepository.InstalledMap> maps;
    private final ArrayList<Layer> traceLayers = new ArrayList<>();
    private final ArrayList<File> leasedMapFiles = new ArrayList<>();
    private FixedPixelCircle locationCircle;
    private Paint locationFill;
    private Paint locationStroke;
    private boolean destroyed;
    private boolean countedActive;
    private final int tileSize;
    private final int touchSlop;
    private Runnable userInteractionListener;
    private float touchDownX, touchDownY;
    private boolean interactionNotified;
    private MultiMapDataStore mapDataStore;
    private TileCache tileCache;
    private TileRendererLayer tileRendererLayer;
    private MapStyle mapStyle = MapStyle.STANDARD;

    public MapsforgeMapRenderer(Context context,
            List<OfflineMapRepository.InstalledMap> installedMaps) throws IOException {
        if (context == null || installedMaps == null || installedMaps.isEmpty()) {
            throw new IOException("Aucune carte Mapsforge installée");
        }
        Context appContext = context.getApplicationContext();
        this.context = context; // Conserver le thème et la configuration de l'Activity pour la View.
        this.applicationContext = appContext != null ? appContext : context;
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        this.maps = new ArrayList<>();

        synchronized (GRAPHICS_LOCK) {
            if (!graphicsInitialized) {
                AndroidGraphicFactory.createInstance(this.applicationContext);
                graphicsInitialized = true;
            }
        }

        MultiMapDataStore store = new MultiMapDataStore(MultiMapDataStore.DataPolicy.DEDUPLICATE);
        TileCache cache = null;
        MapView view = null;
        TileRendererLayer renderer = null;
        boolean layerAttached = false;
        int commonTileSize = -1;
        try {
            for (OfflineMapRepository.InstalledMap installed : installedMaps) {
                if (installed == null || installed.file == null || !installed.file.isFile()) {
                    continue;
                }
                if (installed.tileSize < 64 || installed.tileSize > 1024) continue;
                if (commonTileSize != -1 && installed.tileSize != commonTileSize) {
                    android.util.Log.w("GPS-Test", "Carte Mapsforge ignorée (taille de tuile différente) : "
                            + installed.regionId);
                    continue;
                }

                // Poser le verrou AVANT d'ouvrir le fichier : le gestionnaire
                // ne peut ainsi pas remplacer/supprimer une carte pendant le parsing
                // de ses index Mapsforge.
                MapFileLeaseRegistry.acquire(installed.file);
                leasedMapFiles.add(installed.file);
                MapDataStore fileStore = null;
                boolean retained = false;
                try {
                    fileStore = new MapFile(installed.file);
                    if (fileStore.boundingBox() != null) {
                        store.addMapDataStore(fileStore, false, false);
                        this.maps.add(installed);
                        retained = true;
                        if (commonTileSize == -1) commonTileSize = installed.tileSize;
                    } else {
                        fileStore.close();
                    }
                } catch (RuntimeException corruptMap) {
                    if (fileStore != null) {
                        try { fileStore.close(); } catch (RuntimeException ignored) { }
                    }
                    android.util.Log.w("GPS-Test", "Carte Mapsforge illisible, région ignorée : "
                            + installed.regionId, corruptMap);
                } finally {
                    if (!retained) {
                        MapFileLeaseRegistry.release(installed.file);
                        leasedMapFiles.remove(installed.file);
                    }
                }
            }
            if (this.maps.isEmpty() || store.boundingBox() == null) {
                throw new IOException("Aucune carte Mapsforge lisible");
            }
            this.tileSize = commonTileSize;
            final int selectedTileSize = commonTileSize;
            view = new MapView(this.context);
            view.setBuiltInZoomControls(false);
            view.getMapScaleBar().setVisible(true);
            view.setZoomLevelMin((byte) 3);
            view.setZoomLevelMax((byte) 20);
            view.getModel().displayModel.setFixedTileSize(selectedTileSize);

            File cacheDir = new File(this.applicationContext.getCacheDir(), "mapsforge-render-cache");
            if (!cacheDir.isDirectory() && !cacheDir.mkdirs() && !cacheDir.isDirectory()) {
                throw new IOException("Impossible de créer le cache de rendu Mapsforge");
            }
            cache = AndroidUtil.createTileCache(this.context, cacheDir, "gps-test",
                    selectedTileSize, 0.75f, 1.5d, false);
            renderer = new TileRendererLayer(cache, store, view.getModel().mapViewPosition,
                    AndroidGraphicFactory.INSTANCE);
            renderer.setXmlRenderTheme(MapsforgeThemes.DEFAULT);
            view.getLayerManager().getLayers().add(renderer);
            layerAttached = true;

            // Le marqueur GNSS sera ajouté au premier point valide : ne pas
            // dessiner un faux marqueur au centre de la région au démarrage.
            locationFill = createPaint(0xFF20B45B, Style.FILL, 0f);
            locationStroke = createPaint(0xFFFFFFFF, Style.STROKE, dp(2));
            LatLong initialCenter = store.boundingBox().getCenterPoint();
            view.setCenter(initialCenter);
            view.setZoomLevel((byte) 6);
            // Une fois la taille réelle connue, le cadrage initial est ajusté au viewport.
            final MapView initialView = view;
            final org.mapsforge.core.model.BoundingBox initialBounds = store.boundingBox();
            // Le post() seul peut s'exécuter avant le premier layout (largeur=0).
            // Le listener garantit le fit une fois que le viewport a des dimensions réelles.
            initialView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View changed, int left, int top,
                        int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    int width = right - left;
                    int height = bottom - top;
                    if (destroyed || width <= 0 || height <= 0) return;
                    initialView.removeOnLayoutChangeListener(this);
                    byte initialZoom = MapZoomCalculator.fit(initialBounds.minLatitude,
                            initialBounds.maxLatitude, initialBounds.minLongitude,
                            initialBounds.maxLongitude, width, height,
                            selectedTileSize, Math.round(dp(32)), (byte) 3, (byte) 17);
                    initialView.setCenter(initialBounds.getCenterPoint());
                    initialView.setZoomLevel(initialZoom);
                }
            });
        } catch (RuntimeException | IOException | OutOfMemoryError error) {
            if (view != null) {
                try { view.destroyAll(); } catch (Throwable ignored) { }
            }
            if (!layerAttached) {
                if (renderer != null) {
                    try { renderer.onDestroy(); } catch (Throwable ignored) { }
                } else {
                    try { store.close(); } catch (Throwable ignored) { }
                }
            }
            // destroyAll() détruit aussi le TileCache lorsque la couche était attachée.
            // Si elle ne l'était pas, le cache n'est pas possédé par la MapView.
            if (!layerAttached && cache != null) {
                try { cache.destroy(); } catch (Throwable ignored) { }
            }
            for (File file : new ArrayList<>(leasedMapFiles)) MapFileLeaseRegistry.release(file);
            leasedMapFiles.clear();
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Impossible d'ouvrir les cartes Mapsforge", error);
        }
        if (view == null || cache == null || renderer == null) {
            throw new IOException("Initialisation Mapsforge incomplète");
        }
        this.mapView = view;
        this.mapDataStore = store;
        this.tileCache = cache;
        this.tileRendererLayer = renderer;
        synchronized (GRAPHICS_LOCK) {
            activeRenderers++;
            countedActive = true;
        }
    }

    private Paint createPaint(int color, Style style, float strokeWidth) {
        Paint paint = AndroidGraphicFactory.INSTANCE.createPaint();
        paint.setColor(color);
        paint.setStyle(style);
        if (strokeWidth > 0) paint.setStrokeWidth(strokeWidth);
        return paint;
    }

    private float dp(float value) {
        return value * context.getResources().getDisplayMetrics().density;
    }

    @Override public View getView() { return mapView; }
    @Override public boolean isOffline() { return true; }
    @Override public int getInstalledRegionCount() { return maps.size(); }

    @Override public boolean coversCoordinate(double latitude, double longitude) {
        if (destroyed || !Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) return false;
        try {
            // Teste le datastore effectif (et non uniquement les métadonnées du catalogue).
            // La petite emprise évite les faux négatifs sur les bords de tuile.
            double d = 0.001;
            org.mapsforge.core.model.BoundingBox area = new org.mapsforge.core.model.BoundingBox(
                    Math.max(-90, latitude - d), Math.max(-180, longitude - d),
                    Math.min(90, latitude + d), Math.min(180, longitude + d));
            return mapDataStore.supportsArea(area, (byte) 15);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    @Override public void setMyLocation(MapPoint point, boolean fresh) {
        if (destroyed || point == null) return;
        LatLong position = new LatLong(point.latitude, point.longitude);
        if (locationCircle == null) {
            locationCircle = new FixedPixelCircle(position, dp(8), locationFill, locationStroke);
            mapView.getLayerManager().getLayers().add(locationCircle);
        } else {
            locationCircle.setLatLong(position);
        }
        locationFill.setColor(fresh ? 0xFF20B45B : 0xFFFFA126);
        mapView.getLayerManager().redrawLayers();
    }

    @Override public void animateTo(MapPoint point) {
        if (!destroyed && point != null) {
            mapView.getModel().mapViewPosition.animateTo(new LatLong(point.latitude, point.longitude));
        }
    }

    @Override public void setMapStyle(MapStyle style) {
        if (destroyed || style == null || tileRendererLayer == null || tileCache == null) return;
        if (mapStyle == style) return;
        switch (style) {
            case HIKING:
                tileRendererLayer.setXmlRenderTheme(MapsforgeThemes.BIKER);
                break;
            case MOTORIDER:
                tileRendererLayer.setXmlRenderTheme(MapsforgeThemes.MOTORIDER);
                break;
            case CLASSIC:
                tileRendererLayer.setXmlRenderTheme(MapsforgeThemes.OSMARENDER);
                break;
            case STANDARD:
            default:
                tileRendererLayer.setXmlRenderTheme(MapsforgeThemes.DEFAULT);
                break;
        }
        // Le MapDataStore et les MapFile restent ouverts : seule la couche
        // de présentation change. On invalide les images déjà rendues.
        tileCache.purge();
        mapStyle = style;
        mapView.getLayerManager().redrawLayers();
    }

    @Override public void setMapOrientation(float degrees) {
        if (destroyed || !Float.isFinite(degrees)) return;
        float normalized = Compass.normalize(degrees);
        mapView.getModel().mapViewPosition.setRotation(new Rotation(normalized,
                mapView.getWidth() / 2f, mapView.getHeight() / 2f));
    }

    @Override public void setOnUserInteractionListener(Runnable listener) {
        userInteractionListener = listener;
        mapView.setOnTouchListener((view, event) -> {
            if (destroyed || event == null) return false;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                touchDownX = event.getX(); touchDownY = event.getY();
                interactionNotified = false;
            } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE && !interactionNotified
                    && userInteractionListener != null) {
                float dx = event.getX() - touchDownX, dy = event.getY() - touchDownY;
                if (dx * dx + dy * dy >= (float) touchSlop * touchSlop) {
                    interactionNotified = true;
                    userInteractionListener.run();
                }
            } else if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN
                    && userInteractionListener != null && !interactionNotified) {
                interactionNotified = true;
                userInteractionListener.run();
            }
            return false; // Laisser MapView/Mapsforge traiter le zoom et le panoramique.
        });
    }

    @Override public void replaceTrace(List<List<MapPoint>> segments, MapPoint lastPoint,
            String title, double minLat, double maxLat, double west, double east,
            double centerLon, boolean pointLike, int color, float strokeWidthPx) {
        if (destroyed || lastPoint == null) return;
        ArrayList<Layer> newLayers = new ArrayList<>();
        Layers layers = mapView.getLayerManager().getLayers();
        FixedPixelCircle newEnd = null;
        try {
            Paint linePaint = createPaint(color, Style.STROKE, strokeWidthPx);
            for (List<MapPoint> segment : segments) {
                if (segment == null || segment.size() < 2) continue;
                ArrayList<LatLong> points = new ArrayList<>(segment.size());
                for (MapPoint p : segment) points.add(new LatLong(p.latitude, p.longitude));
                Polyline line = new Polyline(linePaint, AndroidGraphicFactory.INSTANCE);
                line.setPoints(points);
                newLayers.add(line);
            }
            Paint endFill = createPaint(0xFFFF8A24, Style.FILL, 0f);
            Paint endStroke = createPaint(0xFFFFFFFF, Style.STROKE, dp(2));
            newEnd = new FixedPixelCircle(new LatLong(lastPoint.latitude, lastPoint.longitude),
                    dp(7), endFill, endStroke);
            newLayers.add(newEnd);
            layers.addAll(newLayers);
            if (pointLike) {
                mapView.setCenter(new LatLong((minLat + maxLat) / 2.0, centerLon));
                mapView.setZoomLevel((byte) 16);
            } else {
                int width = mapView.getWidth() > 0 ? mapView.getWidth()
                        : context.getResources().getDisplayMetrics().widthPixels;
                int height = mapView.getHeight() > 0 ? mapView.getHeight()
                        : context.getResources().getDisplayMetrics().heightPixels;
                byte zoom = MapZoomCalculator.fit(minLat, maxLat, west, east, width, height,
                        tileSize, Math.round(dp(48)), (byte) 3, (byte) 18);
                mapView.setCenter(new LatLong((minLat + maxLat) / 2.0, centerLon));
                mapView.setZoomLevel(zoom);
            }
        } catch (RuntimeException | OutOfMemoryError error) {
            layers.removeAll(newLayers);
            mapView.getLayerManager().redrawLayers();
            throw error;
        }
        layers.removeAll(traceLayers);
        traceLayers.clear();
        traceLayers.addAll(newLayers);
        mapView.getLayerManager().redrawLayers();
    }

    @Override public void onResume() { /* MapView Mapsforge gère son LayerManager en interne. */ }
    @Override public void onPause() { /* Aucun abonnement GNSS ni thread périodique côté renderer. */ }

    @Override public void destroy() {
        if (destroyed) return;
        destroyed = true;
        userInteractionListener = null;
        mapView.setOnTouchListener(null);
        // MapView.destroyAll() détruit chaque TileLayer et son cache associé.
        // Ne pas détruire tileCache une seconde fois.
        try { mapView.destroyAll(); } catch (Throwable ignored) { }
        for (File file : new ArrayList<>(leasedMapFiles)) MapFileLeaseRegistry.release(file);
        leasedMapFiles.clear();
        // AndroidGraphicFactory est global au processus. Ne pas vider son cache
        // tant qu'un autre MapView Mapsforge reste actif dans une autre Activity.
        synchronized (GRAPHICS_LOCK) {
            if (countedActive) {
                countedActive = false;
                activeRenderers = Math.max(0, activeRenderers - 1);
            }
            if (activeRenderers == 0) {
                try { AndroidGraphicFactory.clearResourceMemoryCache(); }
                catch (Throwable ignored) { }
            }
        }
    }
}
