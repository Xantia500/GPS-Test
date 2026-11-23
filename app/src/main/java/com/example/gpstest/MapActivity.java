package com.example.gpstest;

import android.app.Activity;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CancellationException;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CARTE ET RELECTURE DE TRACES — v5.10
 *
 * ══════════════════════════════════════════════════════════════════════
 * DEUX FONCTIONS, UN SEUL ÉCRAN
 * ══════════════════════════════════════════════════════════════════════
 *
 *   1. AFFICHER LA POSITION COURANTE, avec suivi automatique.
 *   2. RELIRE une trace GPX enregistrée, la tracer sur la carte, et en
 *      afficher le résumé (distance, durée, points, segments).
 *
 * ══════════════════════════════════════════════════════════════════════
 * CE QUI REND CETTE CARTE DIFFÉRENTE D'UNE CARTE « NORMALE »
 * ══════════════════════════════════════════════════════════════════════
 *
 *  1. SI DES CARTES VECTORIELLES RÉGIONALES SONT INSTALLÉES, LE RENDU
 *     UTILISE MAPSFORGE ENTIÈREMENT HORS LIGNE. Sinon, un canevas local
 *     conserve la position et les traces GPX sur une grille géographique.
 *     Aucun moteur raster ou service de tuiles cartographiques n'est embarqué.
 *
 *  2. LA CARTE NE DÉPEND PAS D'UNE MapView RASTER. Le moteur vectoriel
 *     Mapsforge ou le canevas local sont affichés dans le conteneur commun,
 *     sans téléchargement ni cache de tuiles réseau.
 *
 *  3. LE XML EST LU EN FLUX SAX. Le document complet n'est pas converti
 *     en String ni en arbre DOM. La liste métier des points reste bornée à
 *     250 000 entrées ; l'affichage est ensuite échantillonné pour ne pas
 *     créer des centaines de milliers d'objets cartographiques sur le thread UI.
 *
 * ══════════════════════════════════════════════════════════════════════
 * POURQUOI LES SEGMENTS SONT DESSINÉS SÉPARÉMENT
 * ══════════════════════════════════════════════════════════════════════
 *
 * Une trace avec un tunnel a plusieurs <trkseg>. Si on les dessinait comme
 * une seule polyligne, la carte afficherait un trait droit à travers le
 * tunnel — exactement ce que nos coupures de segment servent à éviter.
 *
 * → Une Polyline PAR SEGMENT. Elles ne se rejoignent pas visuellement, et
 *   c'est correct : il n'y a pas eu de déplacement mesuré.
 */
public class MapActivity extends Activity {

    private static final int REQ_OPEN_GPX = 4401;
    private static final int REQ_OFFLINE_MAPS = 4402;

        private FrameLayout mapContainer;
    private MapRenderer renderer;
    private int installedOfflineMapCount;
    private PreparedTrace currentPreparedTrace;
    private TextView tvInfo, tvOffline;
    private Button btnFollow;
    private Button btnMapStyle;
    private Button btnMapOrientation;
    private CompassSensor mapCompass;
    private boolean mapHeadingUp;
    private long lastMapOrientationUpdateMs;
    private long mapsRevisionSeen = -1L;
    private boolean mapsReceiverRegistered;
    private final BroadcastReceiver mapsChangedReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || !OfflineMapDownloadContract.ACTION_MAPS_UPDATED.equals(intent.getAction())) return;
            refreshForOfflineMapRevision();
        }
    };

    private boolean followMe = true;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private android.widget.ProgressBar loading;
    private LinearLayout importProgressRow;
    private TextView tvImportProgress;
    private Button btnCancelImport;
    private final ExecutorService gpxExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gpx-import"); t.setDaemon(true); return t;
    });
    private boolean loadingGpx = false;
    private AtomicBoolean importCancellation;
    private int importGeneration;
    private double lastFollowLat = Double.NaN, lastFollowLon = Double.NaN;
    // Limite stricte des octets réellement lus depuis SAF. Le parseur SAX ne
    // conserve pas une copie String du XML ; seule la liste métier est gardée.
    private static final long MAX_IMPORT_BYTES = 32L * 1024L * 1024L;
    /** Le parser garde tous les points, mais le moteur cartographique reçoit
     *  un échantillon borné pour éviter de créer des centaines de milliers
     *  d'objets de rendu sur le thread UI. */
    private static final int MAX_MAP_RENDER_POINTS = 20_000;
    /** Un GPX malveillant/fragmenté ne doit pas créer des dizaines de milliers d'overlays. */
    private static final int MAX_MAP_RENDER_SEGMENTS = 2_000;

    /** Résultat de préparation GPX construit sur le worker, appliqué au moteur cartographique sur l'UI. */
    private static final class RetainedMapState {
        final PreparedTrace trace;
        final boolean followMe;
        RetainedMapState(PreparedTrace trace, boolean followMe) {
            this.trace = trace;
            this.followMe = followMe;
        }
    }

    private static final class PreparedTrace {
        final List<List<MapPoint>> lines;
        final MapPoint lastPoint;
        final String trackName;
        final int sourcePoints, renderedPoints;
        final int renderedSegments;
        final GpxParser.Summary summary;
        final double minLat, maxLat, west, east, centerLon;
        final boolean pointLike;
        PreparedTrace(List<List<MapPoint>> lines, MapPoint lastPoint, String trackName,
                      int sourcePoints, int renderedPoints, int renderedSegments,
                      GpxParser.Summary summary, double minLat, double maxLat,
                      double west, double east, double centerLon, boolean pointLike) {
            this.lines = lines; this.lastPoint = lastPoint; this.trackName = trackName;
            this.sourcePoints = sourcePoints; this.renderedPoints = renderedPoints;
            this.renderedSegments = renderedSegments; this.summary = summary;
            this.minLat = minLat; this.maxLat = maxLat; this.west = west; this.east = east;
            this.centerLon = centerLon; this.pointLike = pointLike;
        }
    }

    /**
     * Rafraîchissement de la position : 1 fois par seconde.
     *
     * Pourquoi un Runnable et pas un LocationListener propre à cette
     * activité : le service possède déjà la position, l'écran ne fait que
     * la LIRE. Ouvrir un second abonnement GNSS consommerait de la batterie
     * pour obtenir exactement la même donnée.
     */
    private final Runnable positionTick = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) return;
            updateMyPosition();
            ui.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Même thème avant super.onCreate que les autres écrans : évite une
        // première frame avec icônes de barres système de la mauvaise couleur.
        ThemeManager.applyTo(this, ThemeManager.read(this));
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_map);
        EdgeToEdge.apply(this);

        mapContainer = findViewById(R.id.mapContainer);
        tvInfo = findViewById(R.id.tvMapInfo);
        tvOffline = findViewById(R.id.tvMapOffline);
        loading = findViewById(R.id.pbMapLoading);
        importProgressRow = findViewById(R.id.mapImportProgressRow);
        tvImportProgress = findViewById(R.id.tvMapImportProgress);
        btnCancelImport = findViewById(R.id.btnMapCancelImport);
        btnFollow = findViewById(R.id.btnMapFollow);
        btnMapStyle = findViewById(R.id.btnMapStyle);
        btnMapOrientation = findViewById(R.id.btnMapOrientation);
        TextView attribution = findViewById(R.id.tvMapAttribution);
        initializeMapRenderer();
        mapCompass = new CompassSensor(this);
        mapCompass.setOnHeadingChanged(this::updateMapOrientation);
        mapHeadingUp = Prefs.readMapHeadingUp(this);
        if (renderer != null) {
            renderer.setMapStyle(Prefs.readMapStyle(this));
            renderer.setMapOrientation(0f);
        }
        btnMapStyle.setVisibility(installedOfflineMapCount > 0
                ? android.view.View.VISIBLE : android.view.View.GONE);
        if (installedOfflineMapCount > 0)
            btnMapStyle.setText("Style : " + Prefs.readMapStyle(this).label);
        btnMapStyle.setOnClickListener(v -> showMapStyleMenu());
        btnMapOrientation.setOnClickListener(v -> toggleMapHeadingUp());
        reflectMapOrientationButton();
        if (renderer != null) {
            renderer.setOnUserInteractionListener(() -> {
                if (followMe) {
                    followMe = false;
                    reflectFollowButton();
                }
            });
        }
        attribution.setText(installedOfflineMapCount > 0
                ? "© OpenStreetMap contributors · Données vectorielles Mapsforge locales"
                : "Aucune donnée cartographique installée · affichage local seulement");
        attribution.setOnClickListener(v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.openstreetmap.org/copyright"))); }
            catch (Exception e) { toast("Attribution OpenStreetMap indisponible"); }
        });

        btnFollow.setOnClickListener(v -> {
            followMe = !followMe;
            reflectFollowButton();
            if (followMe) {
                lastFollowLat = Double.NaN;
                lastFollowLon = Double.NaN;
                updateMyPosition();
            }
        });

        findViewById(R.id.btnMapLoad).setOnClickListener(v -> openGpxPicker());
        findViewById(R.id.btnMapOffline).setOnClickListener(v -> {
            startActivityForResult(new Intent(this, OfflineMapsActivity.class), REQ_OFFLINE_MAPS);
        });
        btnCancelImport.setOnClickListener(v -> cancelGpxImport());

        tvInfo.setText(R.string.map_trace_none);
        Object retained = getLastNonConfigurationInstance();
        if (retained instanceof RetainedMapState) {
            RetainedMapState saved = (RetainedMapState) retained;
            currentPreparedTrace = saved.trace;
            followMe = saved.followMe;
        }
        reflectFollowButton();
        if (currentPreparedTrace != null) {
            try { drawPreparedTrace(currentPreparedTrace); }
            catch (RuntimeException renderFailure) {
                android.util.Log.w("GPS-Test", "Réaffichage de la trace après gestion hors ligne impossible",
                        renderFailure);
            }
        }

        // Position initiale : celle du service s'il tourne, sinon rien.
        updateMyPosition();
        updateOfflineMapNotice();
    }

    /**
     * Sélectionne exclusivement un moteur local.
     * - cartes Mapsforge valides : rendu vectoriel local ;
     * - aucune carte, dépôt indisponible ou moteur Mapsforge en échec : canevas
     *   géographique local. Il conserve le GPX et la position sans réseau.
     * Aucun chemin de repli ne déclenche de requête de tuiles.
     */
    private void initializeMapRenderer() {
        mapsRevisionSeen = readOfflineMapsRevision();
        File root = getExternalFilesDir("offline-maps");
        if (root == null) root = new File(getFilesDir(), "offline-maps");
        try {
            OfflineMapRepository repository = new OfflineMapRepository(root);
            OfflineMapRepository.CleanupResult cleanup = repository.cleanupUnreferencedMapFiles();
            if (cleanup.deletedFiles > 0) {
                android.util.Log.i("GPS-Test", "Anciennes cartes Mapsforge supprimées : "
                        + cleanup.deletedFiles);
            }
            List<OfflineMapRepository.InstalledMap> installed = repository.listInstalledMaps();
            if (!installed.isEmpty()) {
                MapsforgeMapRenderer offline = null;
                try {
                    offline = new MapsforgeMapRenderer(this, installed);
                    if (offline.getInstalledRegionCount() > 0) {
                        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT);
                        mapContainer.addView(offline.getView(), params);
                        renderer = offline;
                        installedOfflineMapCount = offline.getInstalledRegionCount();
                        return;
                    }
                    offline.destroy();
                } catch (Exception | LinkageError | OutOfMemoryError mapsforgeFailure) {
                    if (offline != null) {
                        try { offline.destroy(); } catch (RuntimeException ignored) { }
                    }
                    android.util.Log.w("GPS-Test",
                            "Mapsforge indisponible ; passage au canevas strictement local",
                            mapsforgeFailure);
                }
            }
        } catch (IOException | RuntimeException repositoryFailure) {
            android.util.Log.w("GPS-Test", "Dépôt de cartes locales indisponible",
                    repositoryFailure);
        }

        installedOfflineMapCount = 0;
        OfflinePlaceholderMapRenderer placeholder = new OfflinePlaceholderMapRenderer(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        mapContainer.addView(placeholder.getView(), params);
        renderer = placeholder;
    }



    @Override
    public Object onRetainNonConfigurationInstance() {
        return new RetainedMapState(currentPreparedTrace, followMe);
    }

    @Override
    protected void onDestroy() {
        if (mapCompass != null) {
            mapCompass.setOnHeadingChanged(null);
            mapCompass.stop();
        }
        // Invalider les callbacks UI éventuellement déjà placés dans la file.
        importGeneration++;
        if (importCancellation != null) importCancellation.set(true);
        gpxExecutor.shutdownNow();
        if (renderer != null) renderer.destroy();
        super.onDestroy();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mapHeadingUp && mapCompass != null) mapCompass.start();
        if (!mapsReceiverRegistered) {
            IntentFilter filter = new IntentFilter(OfflineMapDownloadContract.ACTION_MAPS_UPDATED);
            androidx.core.content.ContextCompat.registerReceiver(this, mapsChangedReceiver,
                    filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
            mapsReceiverRegistered = true;
        }
    }

    @Override
    protected void onStop() {
        if (mapCompass != null) mapCompass.stop();
        if (mapsReceiverRegistered) {
            try { unregisterReceiver(mapsChangedReceiver); }
            catch (IllegalArgumentException ignored) { }
            mapsReceiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (refreshForOfflineMapRevision()) return;
        if (renderer != null) renderer.onResume();
        updateOfflineMapNotice();
        if (!isFinishing()) ui.postDelayed(positionTick, 1000L);
    }

    private long readOfflineMapsRevision() {
        return getSharedPreferences(OfflineMapDownloadContract.PREFS, MODE_PRIVATE)
                .getLong(OfflineMapDownloadContract.KEY_MAPS_REVISION, 0L);
    }

    private boolean refreshForOfflineMapRevision() {
        long revision = readOfflineMapsRevision();
        if (mapsRevisionSeen < 0L) {
            mapsRevisionSeen = revision;
            return false;
        }
        if (revision != mapsRevisionSeen && !isFinishing() && !isDestroyed()) {
            // Prévenir les doubles recréations si le broadcast et onResume arrivent ensemble.
            mapsRevisionSeen = revision;
            recreate();
            return true;
        }
        return false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (renderer != null) renderer.onPause();
        // TOUJOURS retirer le Runnable : sinon il continue de tourner en
        // arrière-plan, garde une référence à l'activité et fuit la mémoire
        // (et consomme du CPU à repositionner une carte invisible).
        ui.removeCallbacks(positionTick);
    }

    // ------------------------------------------------------------------
    // Position courante
    // ------------------------------------------------------------------

    private void updateMyPosition() {
        if (renderer == null) return;
        TrackingState st = TrackingState.get();
        double lat = st.getLatitude();
        double lon = st.getLongitude();
        if (!Double.isFinite(lat) || !Double.isFinite(lon)
                || Math.abs(lat) > 90 || Math.abs(lon) > 180) return;

        boolean fresh = st.isSignalOk();
        MapPoint point;
        try { point = new MapPoint(lat, lon); }
        catch (IllegalArgumentException invalid) { return; }
        renderer.setMyLocation(point, fresh);

        if (followMe && fresh) {
            double moved = Double.isFinite(lastFollowLat)
                    ? FixGeometry.distanceMeters(lastFollowLat, lastFollowLon, lat, lon)
                    : Double.POSITIVE_INFINITY;
            // Ne pas forcer un déplacement de caméra chaque seconde :
            // cela rend le panoramique manuel pratiquement impossible.
            if (moved >= 5.0 || !Double.isFinite(lastFollowLat)) {
                renderer.animateTo(point);
                lastFollowLat = lat;
                lastFollowLon = lon;
            }
        }
    }

    private void updateOfflineMapNotice() {
        if (tvOffline == null) return;
        if (renderer == null) {
            tvOffline.setVisibility(android.view.View.VISIBLE);
            tvOffline.setText("Moteur cartographique local indisponible. Les données GPX restent conservées.");
            return;
        }
        if (installedOfflineMapCount <= 0) {
            tvOffline.setVisibility(android.view.View.VISIBLE);
            tvOffline.setText("Aucune carte vectorielle Mapsforge installée. "
                    + "Affichage hors ligne sur grille géographique ; position et trace GPX conservées. "
                    + "Téléchargez une région pour obtenir le fond cartographique.");
            return;
        }
        TrackingState state = TrackingState.get();
        double lat = state.getLatitude(), lon = state.getLongitude();
        boolean havePosition = Double.isFinite(lat) && Double.isFinite(lon)
                && Math.abs(lat) <= 90 && Math.abs(lon) <= 180;
        boolean covered = havePosition && renderer.coversCoordinate(lat, lon);
        if (havePosition && !covered) {
            tvOffline.setVisibility(android.view.View.VISIBLE);
            tvOffline.setText("Position hors des régions cartographiques installées. "
                    + installedOfflineMapCount + " région(s) locale(s) disponible(s) ; aucun fond réseau ne sera chargé.");
        } else {
            tvOffline.setVisibility(android.view.View.GONE);
            tvOffline.setText("");
        }
    }

    private void showMapStyleMenu() {
        if (renderer == null || installedOfflineMapCount <= 0) return;
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, btnMapStyle);
        MapStyle current = Prefs.readMapStyle(this);
        for (MapStyle style : MapStyle.values()) {
            android.view.MenuItem item = menu.getMenu().add(style.label);
            item.setCheckable(true);
            item.setChecked(style == current);
            item.setOnMenuItemClickListener(selected -> {
                Prefs.writeMapStyle(this, style);
                renderer.setMapStyle(style);
                btnMapStyle.setText("Style : " + style.label);
                return true;
            });
        }
        menu.show();
        btnMapStyle.setText("Style : " + current.label);
    }

    private void toggleMapHeadingUp() {
        if (mapCompass == null || !mapCompass.isAvailable()) {
            toast("Orientation indisponible : " + (mapCompass == null
                    ? "capteur absent" : mapCompass.unavailableReason()));
            return;
        }
        mapHeadingUp = !mapHeadingUp;
        Prefs.writeMapHeadingUp(this, mapHeadingUp);
        lastMapOrientationUpdateMs = 0L;
        if (mapHeadingUp) {
            mapCompass.start();
            updateMapOrientation();
        } else {
            mapCompass.stop();
            if (renderer != null) renderer.setMapOrientation(0f);
        }
        reflectMapOrientationButton();
    }

    private void reflectMapOrientationButton() {
        if (btnMapOrientation == null) return;
        boolean available = mapCompass != null && mapCompass.isAvailable();
        btnMapOrientation.setEnabled(available);
        btnMapOrientation.setText(mapHeadingUp ? "Cap en haut" : "Nord en haut");
        btnMapOrientation.setTextColor(getResources().getColor(
                mapHeadingUp ? R.color.gps_green : R.color.gps_text_dim, getTheme()));
    }

    private void updateMapOrientation() {
        if (!mapHeadingUp || renderer == null || mapCompass == null
                || isFinishing() || isDestroyed()) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastMapOrientationUpdateMs < 50L) return; // max. 20 rafraîchissements/s
        if (mapCompass.tooTilted() || mapCompass.isUnreliable()) {
            btnMapOrientation.setText("Cap indisponible");
            return; // garder le dernier angle fiable, ne pas inventer le nord
        }
        float magnetic = mapCompass.azimuth();
        if (!Float.isFinite(magnetic)) return;
        TrackingState state = TrackingState.get();
        Compass.North north = Prefs.readCompassNorth(this);
        float declination = Float.NaN;
        if (north == Compass.North.TRUE) {
            declination = CompassSensor.declinationAt(state.getLatitude(), state.getLongitude(),
                    state.getAltitude(), System.currentTimeMillis());
        }
        float heading = Compass.toReference(magnetic, declination, north);
        float rotation = MapOrientationCalculator.headingUpRotation(heading);
        if (Float.isFinite(rotation)) {
            renderer.setMapOrientation(rotation);
            lastMapOrientationUpdateMs = now;
            reflectMapOrientationButton();
        }
    }

    private void reflectFollowButton() {
        btnFollow.setText(followMe ? R.string.map_follow_on : R.string.map_follow);
        btnFollow.setTextColor(getResources().getColor(
                followMe ? R.color.gps_green : R.color.gps_text_dim, getTheme()));
    }

    // ------------------------------------------------------------------
    // Relecture d'une trace
    // ------------------------------------------------------------------

    private void openGpxPicker() {
        if (loadingGpx) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        // « */* » et non « application/gpx+xml » : beaucoup de fichiers GPX
        // sont enregistrés par les navigateurs et les gestionnaires de
        // fichiers avec le type générique, et un filtre strict les rendrait
        // invisibles dans le sélecteur.
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_OPEN_GPX);
        } catch (Exception e) {
            toast(getString(R.string.map_trace_error, e.getMessage()));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OFFLINE_MAPS) {
            // Le gestionnaire peut avoir installé, remplacé ou supprimé une carte.
            // Recréer le moteur ferme les MapFiles ouverts avant de relire le dépôt.
            recreate();
            return;
        }
        if (requestCode == REQ_OPEN_GPX && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            loadGpx(data.getData());
        }
    }

    private void loadGpx(Uri uri) {
        if (loadingGpx || uri == null) return;
        loadingGpx = true;
        final int generation = ++importGeneration;
        final AtomicBoolean cancellation = new AtomicBoolean(false);
        importCancellation = cancellation;
        android.view.View loadButton = findViewById(R.id.btnMapLoad);
        loadButton.setEnabled(false);
        btnCancelImport.setEnabled(true);
        importProgressRow.setVisibility(android.view.View.VISIBLE);
        importProgressRow.setContentDescription("Importation GPX en cours");
        tvImportProgress.setText(R.string.map_import_opening);
        loading.setVisibility(android.view.View.VISIBLE);
        tvInfo.setText("Lecture et analyse de la trace GPX…");
        try {
            gpxExecutor.execute(() -> {
                PreparedTrace prepared = null;
                String failure = null;
                boolean wasCancelled = false;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException("Flux illisible");
                    GpxParser.Result result = GpxParser.parse(in, MAX_IMPORT_BYTES,
                            (points, bytesRead) -> postImportProgress(generation,
                                    getString(R.string.map_import_analyzing, points), cancellation),
                            cancellation::get);
                    if (result.cancelled) {
                        wasCancelled = true;
                    } else if (result.failed) {
                        failure = result.error != null ? result.error : "GPX non valide";
                        if (result.pointCount() > 0) {
                            failure += " · " + result.pointCount()
                                    + " point(s) complets détectés avant l'erreur ; trace non affichée";
                        }
                    } else {
                        postImportProgress(generation, getString(R.string.map_import_preparing),
                                cancellation);
                        prepared = prepareTrace(result, cancellation, generation);
                    }
                } catch (CancellationException cancelled) {
                    wasCancelled = true;
                } catch (OutOfMemoryError oom) {
                    failure = "Mémoire insuffisante pour importer cette trace";
                } catch (Exception e) {
                    if (cancellation.get() || Thread.currentThread().isInterrupted()) {
                        wasCancelled = true;
                    } else {
                        failure = e.getMessage() != null ? e.getMessage()
                                : e.getClass().getSimpleName();
                    }
                }
                final PreparedTrace ready = prepared;
                final String error = failure;
                final boolean cancelled = wasCancelled || cancellation.get();
                ui.post(() -> {
                    if (generation != importGeneration || isFinishing() || isDestroyed()) return;
                    loadingGpx = false;
                    resetImportUi();
                    if (cancelled) {
                        tvInfo.setText(R.string.map_import_cancelled);
                        return;
                    }
                    if (error != null || ready == null) {
                        tvInfo.setText(getString(R.string.map_trace_error,
                                error != null ? error : "Lecture impossible"));
                        toast("La trace n'a pas pu être chargée : "
                                + (error != null ? error : "erreur inconnue"));
                        return;
                    }
                    try {
                        drawPreparedTrace(ready);
                    } catch (OutOfMemoryError oom) {
                        // Le rendu est transactionnel : la trace précédente reste affichée
                        // si la préparation ou l'ajout des nouveaux overlays échoue.
                        tvInfo.setText("Mémoire insuffisante pour afficher cette trace. La trace précédente est conservée et le fichier GPX n'a pas été modifié.");
                        toast("Affichage impossible : mémoire insuffisante");
                    } catch (RuntimeException renderError) {
                        tvInfo.setText("Erreur d'affichage : " + renderError.getClass().getSimpleName()
                                + ". La trace précédente est conservée.");
                        toast("Impossible d'afficher la trace : " + renderError.getMessage());
                    }
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            loadingGpx = false;
            resetImportUi();
            tvInfo.setText("Le lecteur GPX n'est plus disponible. Rouvrez la carte.");
        }
    }

    private void cancelGpxImport() {
        if (!loadingGpx || importCancellation == null) return;
        importCancellation.set(true);
        btnCancelImport.setEnabled(false);
        tvImportProgress.setText(R.string.map_import_cancelling);
    }

    private void resetImportUi() {
        loading.setVisibility(android.view.View.GONE);
        importProgressRow.setVisibility(android.view.View.GONE);
        btnCancelImport.setEnabled(true);
        findViewById(R.id.btnMapLoad).setEnabled(true);
        importCancellation = null;
    }

    private void postImportProgress(int generation, String message, AtomicBoolean cancellation) {
        if (cancellation.get()) return;
        ui.post(() -> {
            if (generation == importGeneration && loadingGpx && !cancellation.get()
                    && !isFinishing() && !isDestroyed()) {
                tvImportProgress.setText(message);
            }
        });
    }

    /** Décimation, tri des longitudes et résumé : tout le travail est exécuté hors UI. */
    private PreparedTrace prepareTrace(GpxParser.Result res, AtomicBoolean cancellation,
                                       int generation) {
        checkImportCancellation(cancellation);
        if (res.points == null || res.points.isEmpty()) {
            return new PreparedTrace(new ArrayList<>(), null, res.trackName, 0, 0, 0,
                    GpxParser.summarize(res.points), 0, 0, 0, 0, 0, true);
        }
        final int total = res.points.size();
        int stride = Math.max(1, (total + MAX_MAP_RENDER_POINTS - 1) / MAX_MAP_RENDER_POINTS);
        List<List<MapPoint>> lines = new ArrayList<>();
        int renderedCount = 0;
        int from = 0;
        while (from < total) {
            int to = from + 1;
            int segmentId = res.points.get(from).segment;
            while (to < total && res.points.get(to).segment == segmentId) {
                if ((to & 4095) == 0) checkImportCancellation(cancellation);
                to++;
            }
            if (lines.size() < MAX_MAP_RENDER_SEGMENTS) {
                List<MapPoint> rendered = new ArrayList<>(Math.min(to - from, MAX_MAP_RENDER_POINTS));
                for (int i = from; i < to; i++) {
                    if ((i & 4095) == 0) {
                        checkImportCancellation(cancellation);
                        postImportProgress(generation, getString(R.string.map_import_preparing_points,
                                i, total), cancellation);
                    }
                    if (i == from || i == to - 1 || (i - from) % stride == 0) {
                        GpxParser.Point point = res.points.get(i);
                        rendered.add(new MapPoint(point.lat, point.lon));
                    }
                }
                if (rendered.size() >= 2) {
                    rendered = new ArrayList<>(MapTraceSimplifier.simplify(
                            rendered, 3.0, MAX_MAP_RENDER_POINTS));
                    // Une trace qui traverse +/-180° doit être coupée au bord
                    // du monde, avec des points d'intersection interpolés :
                    // aucune ligne ne traverse artificiellement toute la carte.
                    List<List<MapPoint>> split = MapTraceSegmenter.splitAtAntimeridian(rendered);
                    boolean addedAny = false;
                    for (List<MapPoint> part : split) {
                        if (lines.size() >= MAX_MAP_RENDER_SEGMENTS) break;
                        lines.add(part);
                        addedAny = true;
                    }
                    if (addedAny) renderedCount += rendered.size(); // Sans compter les intersections synthétiques.
                }
            }
            from = to;
        }
        double minLat = 90, maxLat = -90;
        double[] longitudes = new double[total];
        for (int i = 0; i < total; i++) {
            if ((i & 4095) == 0) {
                checkImportCancellation(cancellation);
                postImportProgress(generation, getString(R.string.map_import_bounds,
                        i, total), cancellation);
            }
            GpxParser.Point point = res.points.get(i);
            minLat = Math.min(minLat, point.lat);
            maxLat = Math.max(maxLat, point.lat);
            double longitude = point.lon % 360.0;
            if (longitude < 0) longitude += 360.0;
            longitudes[i] = longitude;
        }
        checkImportCancellation(cancellation);
        java.util.Arrays.sort(longitudes);
        checkImportCancellation(cancellation);
        double west = longitudes[0], east = longitudes[total - 1], largestGap = -1;
        for (int i = 0; i < total; i++) {
            double a = longitudes[i];
            double b = i + 1 < total ? longitudes[i + 1] : longitudes[0] + 360.0;
            if (b - a > largestGap) {
                largestGap = b - a;
                west = i + 1 < total ? longitudes[i + 1] : longitudes[0];
                east = a;
                if (east < west) east += 360.0;
            }
        }
        double centerLon = (west + east) / 2.0;
        while (centerLon > 180) centerLon -= 360;
        while (centerLon < -180) centerLon += 360;
        boolean pointLike = (maxLat - minLat) < 1e-6 && (east - west) < 1e-6;
        GpxParser.Point last = res.points.get(total - 1);
        return new PreparedTrace(lines, new MapPoint(last.lat, last.lon), res.trackName,
                total, renderedCount, lines.size(), GpxParser.summarize(res.points),
                minLat, maxLat, west, east, centerLon, pointLike);
    }

    private static void checkImportCancellation(AtomicBoolean cancellation) {
        if (Thread.currentThread().isInterrupted()
                || (cancellation != null && cancellation.get())) {
            throw new CancellationException("Import annulé");
        }
    }

    /**
     * Prépare puis publie les nouveaux overlays de manière transactionnelle.
     * Une erreur de création/rendu ne détruit pas la trace précédemment affichée.
     * Cette méthode et toutes les opérations du moteur cartographique restent sur le thread UI.
     */
    private void drawPreparedTrace(PreparedTrace trace) {
        if (trace.sourcePoints == 0 || trace.lastPoint == null) {
            tvInfo.setText("Aucun point de trace exploitable dans ce GPX.");
            return;
        }
        if (renderer == null) throw new IllegalStateException("Moteur cartographique indisponible");
        renderer.replaceTrace(trace.lines, trace.lastPoint, trace.trackName,
                trace.minLat, trace.maxLat, trace.west, trace.east, trace.centerLon,
                trace.pointLike, getResources().getColor(R.color.gps_green, getTheme()), 6f);
        currentPreparedTrace = trace;

        GpxParser.Summary summary = trace.summary;
        String name = trace.trackName != null ? trace.trackName : "Trace sans nom";
        tvInfo.setText(getString(R.string.map_trace_loaded,
                name,
                Units.formatDistance(Prefs.readUnits(this), summary.distanceM),
                formatDuration(summary.durationMs),
                summary.points + " points" + (summary.segments > 1 ? " / " + summary.segments + " seg." : "")
                        + (trace.renderedPoints < trace.sourcePoints
                            ? " · affichage simplifié (" + trace.renderedPoints + "/"
                                + trace.sourcePoints + " points, " + trace.renderedSegments
                                + " segments dessinés)" : "")));
    }

    /** « 1 h 23 min », « 12 min 05 s », « 45 s ». */
    static String formatDuration(long ms) {
        if (ms <= 0) return "—";
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) return String.format(Locale.getDefault(), "%d h %02d min", h, m);
        if (m > 0) return String.format(Locale.getDefault(), "%d min %02d s", m, s);
        return s + " s";
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
