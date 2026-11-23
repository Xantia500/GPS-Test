package com.example.gpstest;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;


import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import android.text.Editable;
import android.text.TextWatcher;

/** Catalogue et gestion des cartes vectorielles Mapsforge téléchargées à la demande. */
public final class OfflineMapsActivity extends Activity {
    private static final String PREFS = OfflineMapDownloadContract.PREFS;
    private static final String KEY_ID = OfflineMapDownloadContract.KEY_ID;
    private static final String KEY_REGION = OfflineMapDownloadContract.KEY_REGION;
    private static final String KEY_NAME = OfflineMapDownloadContract.KEY_NAME;
    private static final String KEY_PATH = OfflineMapDownloadContract.KEY_PATH;
    private static final String KEY_VERSION = OfflineMapDownloadContract.KEY_VERSION;
    private static final String STAGING_DIR = OfflineMapDownloadContract.STAGING_DIR;
    private static final long MAX_DOWNLOAD_BYTES = OfflineMapRepository.MAX_MAP_FILE_BYTES;
    private static final int MAX_BOUNDARY_POLYGONS = 160;
    private static final int MAX_BOUNDARY_POINTS_TOTAL = 180_000;
    private static final long MAX_BOUNDARY_BYTES_TOTAL = 8L * 1024L * 1024L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "offline-map-manager");
        t.setDaemon(true);
        return t;
    });

    private TextView tvStatus, tvPath, tvDownloadTitle, tvDownloadProgress, tvMapLegend;
    private LinearLayout entries, downloadPanel;
    private ProgressBar downloadProgress;
    private Button btnUp, btnCancelDownload, btnMapView, btnMapReset;
    private EditText etSearch;
    private FrameLayout mapSelectionFrame;
    private MapRegionSelectionView regionSelectionView;
    private final ExecutorService boundaryWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "offline-map-boundaries");
        t.setDaemon(true);
        return t;
    });
    private volatile AtomicBoolean boundaryCancelled = new AtomicBoolean(false);
    private int boundaryGeneration;
    private boolean mapViewVisible;
    private String searchQuery = "";
    private List<MapRegionSelectionView.RegionItem> boundaryItems = new ArrayList<>();
    private OfflineBoundaryRepository boundaryRepository;
    private String currentDirectory = "";
    private List<MapsforgeCatalogParser.Entry> currentEntries = new ArrayList<>();
    private List<MapsforgeCatalogParser.Entry> cachedSearchResults = new ArrayList<>();
    private OfflineCatalogRepository catalogRepository;
    private boolean currentCatalogStale;
    private boolean cachedSearchLoading;
    private int catalogRequestGeneration;
    private volatile AtomicBoolean catalogCancelled = new AtomicBoolean(false);
    private int cachedSearchGeneration;
    private Runnable cachedSearchDebounce;
    private List<OfflineMapRepository.InstalledMap> installedMaps = new ArrayList<>();
    private Map<String, OfflineMapRepository.InstalledMap> installedById = new HashMap<>();
    private OfflineMapRepository repository;
    private DownloadManager downloadManager;
    private volatile boolean catalogLoading;
    private volatile boolean installing;
    private long activeDownloadId = -1L;
    private boolean activityResumed;
    private boolean installedMapsLoaded;

    private final Runnable pollDownload = new Runnable() {
        @Override public void run() {
            if (!activityResumed || isFinishing()) return;
            refreshDownloadState();
            if (activeDownloadId >= 0) ui.postDelayed(this, installing ? 1400L : 900L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        ThemeManager.applyTo(this, ThemeManager.read(this));
        super.onCreate(state);
        setContentView(R.layout.activity_offline_maps);
        EdgeToEdge.apply(this);

        tvStatus = findViewById(R.id.tvOfflineMapsStatus);
        tvPath = findViewById(R.id.tvOfflineMapsPath);
        entries = findViewById(R.id.offlineMapEntries);
        downloadPanel = findViewById(R.id.offlineDownloadPanel);
        tvDownloadTitle = findViewById(R.id.tvOfflineDownloadTitle);
        tvDownloadProgress = findViewById(R.id.tvOfflineDownloadProgress);
        downloadProgress = findViewById(R.id.pbOfflineDownload);
        btnUp = findViewById(R.id.btnOfflineUp);
        btnCancelDownload = findViewById(R.id.btnOfflineCancelDownload);
        btnMapView = findViewById(R.id.btnOfflineMapView);
        btnMapReset = findViewById(R.id.btnOfflineMapReset);
        etSearch = findViewById(R.id.etOfflineMapSearch);
        mapSelectionFrame = findViewById(R.id.offlineMapSelectionFrame);
        tvMapLegend = findViewById(R.id.tvOfflineMapLegend);
        downloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);

        // Le sélecteur est entièrement vectoriel et local : les frontières
        // .poly déjà consultées sont dessinées sur une grille géographique.
        // Aucun moteur de tuiles, cache raster ou accès réseau cartographique.
        regionSelectionView = new MapRegionSelectionView(this);
        regionSelectionView.setOnRegionClickListener(this::onBoundaryRegionSelected);
        mapSelectionFrame.addView(regionSelectionView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        regionSelectionView.invalidate();
        tvMapLegend.setText("Bleu : disponible · Vert : installée · Orange : mise à jour. Fond géographique local sans tuiles raster. Les contours .poly sont récupérés à la première consultation puis mis en cache ; le catalogue et les téléchargements nécessitent une connexion.");
        btnMapView.setOnClickListener(v -> toggleRegionMap());
        btnMapReset.setOnClickListener(v -> {
            if (regionSelectionView != null) regionSelectionView.resetView();
        });
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchQuery = s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT);
                renderEntries();
                applyBoundarySearch();
                scheduleCachedCatalogSearch();
            }
            @Override public void afterTextChanged(Editable e) { }
        });

        File root = getExternalFilesDir("offline-maps");
        if (root == null) root = new File(getFilesDir(), "offline-maps");
        try {
            repository = new OfflineMapRepository(root);
        } catch (IOException e) {
            tvStatus.setText("Stockage des cartes indisponible : " + safeMessage(e));
        }
        File catalogRoot = getExternalFilesDir("offline-map-catalog");
        if (catalogRoot == null) catalogRoot = new File(getFilesDir(), "offline-map-catalog");
        try {
            catalogRepository = new OfflineCatalogRepository(catalogRoot);
        } catch (IOException e) {
            tvStatus.setText("Cache du catalogue indisponible : " + safeMessage(e));
        }
        findViewById(R.id.btnOfflineBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnOfflineRefresh).setOnClickListener(v -> loadDirectory(currentDirectory));
        TextView attribution = findViewById(R.id.tvOfflineMapsAttribution);
        attribution.setPaintFlags(attribution.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        attribution.setClickable(true);
        attribution.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.openstreetmap.org/copyright")));
            } catch (Exception e) {
                toast("Impossible d'ouvrir les informations de licence : " + safeMessage(e));
            }
        });
        btnUp.setOnClickListener(v -> navigateUp());
        btnCancelDownload.setOnClickListener(v -> cancelDownload());

        loadInstalledMaps();
        loadDirectory("");
    }

    @Override protected void onResume() {
        super.onResume();
        activityResumed = true;
        restorePendingDownload();
        if (activeDownloadId >= 0) ui.post(pollDownload);
    }

    @Override protected void onPause() {
        activityResumed = false;
        ui.removeCallbacks(pollDownload);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (cachedSearchDebounce != null) ui.removeCallbacks(cachedSearchDebounce);
        cachedSearchGeneration++;
        catalogCancelled.set(true);
        ui.removeCallbacksAndMessages(null);
        // Ne pas interrompre une validation/installation déjà commencée : elle
        // peut déplacer un fichier volumineux et publier ses métadonnées.
        // Les callbacks UI vérifient isDestroyed() avant d'accéder aux vues.
        worker.shutdown();
        boundaryCancelled.set(true);
        boundaryWorker.shutdownNow();
        super.onDestroy();
    }

    private boolean isUiAlive() {
        return !isFinishing() && !isDestroyed();
    }

    private void loadDirectory(String directory) {
        catalogCancelled.set(true);
        final AtomicBoolean requestCancellation = new AtomicBoolean(false);
        catalogCancelled = requestCancellation;
        currentDirectory = directory == null ? "" : directory;
        final String requestedDirectory = currentDirectory;
        final int generation = ++catalogRequestGeneration;
        cancelBoundaryLoad();
        boundaryItems = new ArrayList<>();
        if (regionSelectionView != null) regionSelectionView.setRegions(boundaryItems);
        currentEntries = new ArrayList<>();
        currentCatalogStale = false;
        catalogLoading = true;
        tvStatus.setText("Chargement du catalogue Mapsforge…");
        tvPath.setText("/maps/v5/" + currentDirectory);
        btnUp.setVisibility(currentDirectory.isEmpty() ? View.GONE : View.VISIBLE);
        entries.removeAllViews();
        worker.execute(() -> {
            List<MapsforgeCatalogParser.Entry> found = null;
            boolean stale = false;
            String status = null;
            try {
                String html = MapsforgeCatalogClient.fetchDirectoryHtml(requestedDirectory,
                        requestCancellation::get);
                if (requestCancellation.get()) return;
                found = MapsforgeCatalogParser.parse(html, requestedDirectory);
                if (catalogRepository != null) {
                    try { catalogRepository.saveDirectory(requestedDirectory, html, System.currentTimeMillis()); }
                    catch (IOException cacheFailure) {
                        android.util.Log.w("GPS-Test", "Index Mapsforge reçu mais non mémorisé", cacheFailure);
                    }
                }
                status = found.size() + " élément(s) · miroir Mapsforge Hochschule Esslingen";
            } catch (Exception networkFailure) {
                if (requestCancellation.get()) return;
                if (catalogRepository != null) {
                    try {
                        OfflineCatalogRepository.Snapshot cached = catalogRepository.loadDirectory(requestedDirectory);
                        if (cached != null) {
                            found = cached.entries;
                            stale = true;
                            status = found.size() + " élément(s) · catalogue local ancien (hors ligne)";
                        }
                    } catch (IOException cacheFailure) {
                        networkFailure.addSuppressed(cacheFailure);
                    }
                }
                if (found == null) status = "Catalogue indisponible : " + safeMessage(networkFailure);
            }
            final List<MapsforgeCatalogParser.Entry> result = found == null
                    ? new ArrayList<>() : new ArrayList<>(found);
            final boolean fromCache = stale;
            final String resultStatus = status;
            ui.post(() -> {
                if (!isUiAlive() || generation != catalogRequestGeneration
                        || !requestedDirectory.equals(currentDirectory)) return;
                currentEntries = result;
                currentCatalogStale = fromCache;
                catalogLoading = false;
                renderEntries();
                tvStatus.setText(resultStatus == null ? "Catalogue chargé." : resultStatus);
                if (result.isEmpty() && !fromCache && resultStatus != null
                        && resultStatus.startsWith("Catalogue indisponible")) {
                    addMessageRow("Vérifiez votre connexion. Les dossiers déjà consultés restent recherchables localement.");
                }
                if (mapViewVisible) loadBoundariesForCurrentDirectory();
            });
        });
    }

    /** Recherche locale uniquement dans les index déjà consultés (aucun scan réseau mondial). */
    private void scheduleCachedCatalogSearch() {
        if (cachedSearchDebounce != null) ui.removeCallbacks(cachedSearchDebounce);
        final int generation = ++cachedSearchGeneration;
        final String query = searchQuery == null ? "" : searchQuery.trim();
        cachedSearchResults = new ArrayList<>();
        cachedSearchLoading = query.length() >= 2 && catalogRepository != null;
        renderEntries();
        if (!cachedSearchLoading) return;
        cachedSearchDebounce = () -> worker.execute(() -> {
            List<MapsforgeCatalogParser.Entry> found;
            try { found = catalogRepository.searchCached(query, 100); }
            catch (IOException e) { found = new ArrayList<>(); }
            final List<MapsforgeCatalogParser.Entry> result = new ArrayList<>(found);
            ui.post(() -> {
                if (!isUiAlive() || generation != cachedSearchGeneration
                        || !query.equals(searchQuery)) return;
                cachedSearchResults = result;
                cachedSearchLoading = false;
                renderEntries();
            });
        });
        ui.postDelayed(cachedSearchDebounce, 350L);
    }

    private void navigateUp() {
        if (currentDirectory.isEmpty()) return;
        String value = currentDirectory;
        if (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        int slash = value.lastIndexOf('/');
        loadDirectory(slash < 0 ? "" : value.substring(0, slash + 1));
    }

    private void loadInstalledMaps() {
        if (repository == null) {
            installedMapsLoaded = true;
            renderEntries();
            return;
        }
        worker.execute(() -> {
            try {
                List<OfflineMapRepository.InstalledMap> maps = repository.listInstalledMaps();
                ui.post(() -> {
                    if (!isUiAlive()) return;
                    installedMaps = maps;
                    installedMapsLoaded = true;
                    installedById.clear();
                    for (OfflineMapRepository.InstalledMap map : maps) installedById.put(map.regionId, map);
                    renderEntries();
                    if (mapViewVisible && !currentEntries.isEmpty()) loadBoundariesForCurrentDirectory();
                });
            } catch (IOException e) {
                ui.post(() -> {
                    if (!isUiAlive()) return;
                    installedMapsLoaded = true;
                    tvStatus.setText("Lecture des cartes installées impossible : " + safeMessage(e));
                    renderEntries();
                });
            }
        });
    }

    private boolean matchesSearch(String... values) {
        if (searchQuery == null || searchQuery.isEmpty()) return true;
        for (String value : values) {
            if (value != null && value.toLowerCase(Locale.ROOT).contains(searchQuery)) return true;
        }
        return false;
    }

    private void toggleRegionMap() {
        mapViewVisible = !mapViewVisible;
        mapSelectionFrame.setVisibility(mapViewVisible ? View.VISIBLE : View.GONE);
        if (tvMapLegend != null) tvMapLegend.setVisibility(mapViewVisible ? View.VISIBLE : View.GONE);
        if (btnMapReset != null) btnMapReset.setVisibility(mapViewVisible ? View.VISIBLE : View.GONE);
        btnMapView.setText(mapViewVisible ? "Masquer" : "Carte");
        if (mapViewVisible) {
            if (boundaryItems.isEmpty()) loadBoundariesForCurrentDirectory();
            else applyBoundarySearch();
        } else {
            cancelBoundaryLoad();
        }
    }

    private void cancelBoundaryLoad() {
        boundaryGeneration++;
        boundaryCancelled.set(true);
    }

    private void loadBoundariesForCurrentDirectory() {
        if (!mapViewVisible || isFinishing()) return;
        cancelBoundaryLoad();
        final int generation = boundaryGeneration;
        final AtomicBoolean token = new AtomicBoolean(false);
        boundaryCancelled = token;
        final String directory = currentDirectory;
        final List<MapsforgeCatalogParser.Entry> mapEntries = new ArrayList<>(currentEntries);
        final Map<String, OfflineMapRepository.InstalledMap> installedSnapshot =
                new HashMap<>(installedById);
        tvStatus.setText("Chargement des frontières de sélection…");

        boundaryWorker.execute(() -> {
            ArrayList<MapRegionSelectionView.RegionItem> loaded = new ArrayList<>();
            int staleCount = 0;
            int totalBoundaryPoints = 0;
            long totalBoundaryBytes = 0L;
            try {
                if (boundaryRepository == null) {
                    File root = getExternalFilesDir("offline-map-boundaries");
                    if (root == null) root = new File(getFilesDir(), "offline-map-boundaries");
                    boundaryRepository = new OfflineBoundaryRepository(root);
                }
                List<MapsforgeBoundaryCatalogParser.Entry> boundaryEntries;
                Map<String, OfflineBoundaryRepository.BoundaryData> cachedBoundaryData = new HashMap<>();
                try {
                    boundaryEntries = MapsforgeBoundaryClient.fetchDirectory(directory, token::get);
                } catch (IOException networkFailure) {
                    List<OfflineBoundaryRepository.BoundaryData> cached =
                            boundaryRepository.loadCachedDirectory(directory);
                    ArrayList<MapsforgeBoundaryCatalogParser.Entry> offlineEntries = new ArrayList<>();
                    for (OfflineBoundaryRepository.BoundaryData data : cached) {
                        String path = data.relativePath;
                        String name = path.substring(path.lastIndexOf('/') + 1);
                        String display = name.replaceAll("(?i)\\.poly$", "")
                                .replace('-', ' ').replace('_', ' ');
                        if (!display.isEmpty()) display = Character.toUpperCase(display.charAt(0)) + display.substring(1);
                        offlineEntries.add(new MapsforgeBoundaryCatalogParser.Entry(name, display,
                                path, data.sourceVersion, false, data.sizeBytes));
                        cachedBoundaryData.put(path, data);
                    }
                    if (offlineEntries.isEmpty()) throw networkFailure;
                    boundaryEntries = offlineEntries;
                }
                int considered = 0;
                for (MapsforgeBoundaryCatalogParser.Entry boundary : boundaryEntries) {
                    if (token.get() || isFinishing()) break;
                    if (boundary.directory || !boundary.name.toLowerCase(Locale.ROOT).endsWith(".poly")) continue;
                    MapsforgeCatalogParser.Entry target = matchMapEntry(boundary, mapEntries);
                    if (target == null) continue;
                    if (++considered > MAX_BOUNDARY_POLYGONS) break;
                    OfflineBoundaryRepository.BoundaryData data = cachedBoundaryData.get(boundary.relativePath);
                    if (data == null) {
                        data = boundaryRepository.loadOrFetch(
                                boundary.relativePath, boundary.lastModified,
                                (path, ignored) -> MapsforgeBoundaryClient.fetchPolygon(path, token::get),
                                token::get);
                    }
                    if (data.polygon.pointCount > MAX_BOUNDARY_POINTS_TOTAL - totalBoundaryPoints
                            || data.sizeBytes > MAX_BOUNDARY_BYTES_TOTAL - totalBoundaryBytes) {
                        // Ne pas charger une collection de polygones démesurée
                        // sur le thread UI ; les régions restent accessibles via la liste.
                        break;
                    }
                    totalBoundaryPoints += data.polygon.pointCount;
                    totalBoundaryBytes += data.sizeBytes;
                    if (data.stale) staleCount++;
                    String regionId = target.directory
                            ? MapsforgeRegionMatcher.directoryRegionId(target.relativePath) : target.regionId();
                    OfflineMapRepository.InstalledMap installed = target.directory
                            ? null : installedSnapshot.get(regionId);
                    // L'état installé/à mettre à jour doit comparer les dates
                    // du fichier .map, pas celles du contour .poly (qui peut
                    // être publié à une date différente).
                    int state = MapsforgeRegionMatcher.state(installed != null,
                            installed == null ? "" : installed.sourceVersion, target.lastModified);
                    loaded.add(new MapRegionSelectionView.RegionItem(regionId,
                            target.displayName, data.polygon, state, target));
                }
                if (token.get()) return;
                final int stale = staleCount;
                ui.post(() -> {
                    if (!isUiAlive() || generation != boundaryGeneration || !mapViewVisible) return;
                    boundaryItems = loaded;
                    regionSelectionView.setRegions(boundaryItems);
                    applyBoundarySearch();
                    String suffix = stale > 0 ? " · " + stale + " frontière(s) en cache ancien" : "";
                    tvStatus.setText(loaded.size() + " frontière(s) sélectionnable(s)" + suffix);
                });
            } catch (Exception e) {
                if (token.get()) return;
                ui.post(() -> {
                    if (!isUiAlive() || generation != boundaryGeneration) return;
                    boundaryItems = loaded;
                    regionSelectionView.setRegions(boundaryItems);
                    tvStatus.setText("Frontières indisponibles : " + safeMessage(e)
                            + ". Utilisez la liste des régions ci-dessous.");
                });
            }
        });
    }

    private MapsforgeCatalogParser.Entry matchMapEntry(
            MapsforgeBoundaryCatalogParser.Entry boundary,
            List<MapsforgeCatalogParser.Entry> mapEntries) {
        return MapsforgeRegionMatcher.findTarget(boundary, mapEntries);
    }

    private void applyBoundarySearch() {
        if (regionSelectionView == null) return;
        if (searchQuery == null || searchQuery.isEmpty()) {
            regionSelectionView.setRegionsPreservingView(boundaryItems);
            return;
        }
        ArrayList<MapRegionSelectionView.RegionItem> filtered = new ArrayList<>();
        for (MapRegionSelectionView.RegionItem item : boundaryItems) {
            if (matchesSearch(item.displayName, item.regionId)) filtered.add(item);
        }
        regionSelectionView.setRegionsPreservingView(filtered);
    }

    private void onBoundaryRegionSelected(MapRegionSelectionView.RegionItem item) {
        if (item == null || !(item.tag instanceof MapsforgeCatalogParser.Entry)) return;
        MapsforgeCatalogParser.Entry entry = (MapsforgeCatalogParser.Entry) item.tag;
        if (entry.directory) {
            loadDirectory(entry.directoryPath(currentDirectory));
            return;
        }
        OfflineMapRepository.InstalledMap installed = installedById.get(entry.regionId());
        boolean updateAvailable = installed != null && !entry.lastModified.isEmpty()
                && entry.lastModified.compareTo(installed.sourceVersion) > 0;
        if (installed != null && !updateAvailable) {
            toast("Carte déjà installée : " + entry.displayName);
        } else {
            confirmDownload(entry, installed);
        }
    }

    private void renderEntries() {
        entries.removeAllViews();
        if (!installedMaps.isEmpty()) {
            addSectionHeader("Cartes installées · " + installedMaps.size());
            for (OfflineMapRepository.InstalledMap installed : installedMaps) {
                if (matchesSearch(installed.displayName, installed.regionId)) addInstalledMapRow(installed);
            }
            addDivider();
        } else if (!installedMapsLoaded) {
            addSectionHeader("Cartes installées · vérification…");
        }

        addSectionHeader(currentCatalogStale
                ? "Catalogue Mapsforge · copie locale (hors ligne)"
                : "Catalogue Mapsforge");
        if (currentEntries.isEmpty()) {
            addMessageRow(catalogLoading
                    ? "Chargement des régions…"
                    : "Aucune entrée dans ce dossier. Les cartes installées et les résultats locaux restent accessibles.");
        }
        int visibleCount = 0;
        java.util.HashSet<String> visiblePaths = new java.util.HashSet<>();
        for (MapsforgeCatalogParser.Entry entry : currentEntries) {
            if (!matchesSearch(entry.displayName, entry.name, entry.relativePath)) continue;
            if (entry.directory) addDirectoryRow(entry, false);
            else addMapRow(entry);
            visiblePaths.add(entry.relativePath);
            visibleCount++;
        }
        if (searchQuery.length() >= 2) {
            addSectionHeader("Autres résultats des dossiers déjà consultés");
            int cachedVisible = 0;
            for (MapsforgeCatalogParser.Entry entry : cachedSearchResults) {
                if (visiblePaths.contains(entry.relativePath)) continue;
                if (entry.directory) addDirectoryRow(entry, true);
                else addMapRow(entry);
                visiblePaths.add(entry.relativePath);
                cachedVisible++;
            }
            if (cachedSearchLoading) addMessageRow("Recherche dans le catalogue local…");
            else if (cachedVisible == 0) addMessageRow("Aucun autre résultat dans les dossiers déjà consultés.");
            visibleCount += cachedVisible;
        }
        if (visibleCount == 0 && !searchQuery.isEmpty()) addMessageRow("Aucun résultat pour « " + searchQuery + " ».");
    }

    private void addSectionHeader(String title) {
        TextView header = new TextView(this);
        header.setText(title);
        header.setTextColor(getResources().getColor(R.color.gps_text, getTheme()));
        header.setTextSize(13);
        header.setTypeface(null, android.graphics.Typeface.BOLD);
        header.setPadding(8, 12, 8, 5);
        entries.addView(header);
    }

    private void addInstalledMapRow(OfflineMapRepository.InstalledMap installed) {
        LinearLayout row = rowContainer();
        LinearLayout text = textColumn();
        addText(text, "✓  " + installed.displayName, 14, true);
        addText(text, formatBytes(installed.sizeBytes) + " · région " + installed.regionId
                + (installed.sourceVersion.isEmpty() ? "" : "\nCatalogue : " + installed.sourceVersion),
                10, false);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
        Button remove = button("Supprimer");
        remove.setOnClickListener(v -> confirmRemove(installed));
        row.addView(remove);
        entries.addView(row);
        addDivider();
    }

    private void addDirectoryRow(MapsforgeCatalogParser.Entry entry) {
        addDirectoryRow(entry, false);
    }

    private void addDirectoryRow(MapsforgeCatalogParser.Entry entry, boolean absolutePath) {
        LinearLayout row = rowContainer();
        LinearLayout text = textColumn();
        addText(text, "📁  " + entry.displayName, 15, true);
        addText(text, "Parcourir les cartes de ce dossier", 11, false);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
        Button open = button("Ouvrir");
        open.setOnClickListener(v -> loadDirectory(absolutePath
                ? entry.relativePath : entry.directoryPath(currentDirectory)));
        row.addView(open);
        entries.addView(row);
        addDivider();
    }

    private void addMapRow(MapsforgeCatalogParser.Entry entry) {
        String id = entry.regionId();
        OfflineMapRepository.InstalledMap installed = installedById.get(id);
        boolean updateAvailable = installed != null && !entry.lastModified.isEmpty()
                && !entry.lastModified.equals(installed.sourceVersion)
                && entry.lastModified.compareTo(installed.sourceVersion) > 0;
        LinearLayout row = rowContainer();
        LinearLayout text = textColumn();
        addText(text, "▦  " + entry.displayName, 14, true);
        StringBuilder detail = new StringBuilder();
        if (!entry.sizeLabel.isEmpty()) detail.append("≈ ").append(entry.sizeLabel);
        else detail.append("Taille déterminée pendant le téléchargement");
        if (!entry.lastModified.isEmpty()) detail.append(" · MAJ ").append(entry.lastModified);
        if (installed != null) {
            detail.append("\nInstallée · ").append(formatBytes(installed.sizeBytes));
            if (installed.sourceVersion.isEmpty()) detail.append(" · version locale inconnue");
            if (updateAvailable) detail.append(" · mise à jour disponible");
            else if (entry.lastModified.isEmpty()) detail.append(" · date distante inconnue");
        }
        addText(text, detail.toString(), 10, false);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));

        Button action;
        if (installed == null || updateAvailable) {
            action = button(installed == null ? "Télécharger" : "Mettre à jour");
            action.setOnClickListener(v -> confirmDownload(entry, installed));
            action.setEnabled(activeDownloadId < 0 && !catalogLoading && repository != null);
        } else {
            action = button("Installée");
            action.setEnabled(false);
        }
        row.addView(action);
        entries.addView(row);
        addDivider();
    }

    private void confirmDownload(MapsforgeCatalogParser.Entry entry,
                                 OfflineMapRepository.InstalledMap installed) {
        if (activeDownloadId >= 0) {
            toast("Un seul téléchargement de carte peut être actif à la fois.");
            return;
        }
        long free = repository == null ? -1 : repositoryFreeSpace();
        if (entry.approximateSizeBytes > 0 && free >= 0
                && free < entry.approximateSizeBytes + OfflineMapRepository.MIN_FREE_SPACE_MARGIN_BYTES) {
            new AlertDialog.Builder(this).setTitle("Espace insuffisant")
                    .setMessage("Cette carte nécessite environ " + formatBytes(entry.approximateSizeBytes)
                            + ". L'espace disponible est " + formatBytes(free) + ".")
                    .setPositiveButton("OK", null).show();
            return;
        }
        CheckBox metered = new CheckBox(this);
        metered.setText("Autoriser le téléchargement sur données mobiles (peut consommer plusieurs centaines de Mo)");
        metered.setChecked(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(24, 6, 24, 0);
        TextView message = new TextView(this);
        message.setText((installed == null ? "Télécharger " : "Mettre à jour ") + entry.displayName
                + " ?\n\nTaille indicative : "
                + (entry.sizeLabel.isEmpty() ? "inconnue" : entry.sizeLabel)
                + "\nSource : miroir Mapsforge de l'Université de Hochschule Esslingen."
                + "\n\nLe catalogue ne publie pas de hash signé. L'application calculera un SHA-256 local,"
                + " contrôlera l'en-tête Mapsforge et la taille réellement reçue."
                + "\n\nLes données OpenStreetMap sont sous licence ODbL 1.0.");
        message.setTextSize(13);
        content.addView(message);
        content.addView(metered);
        new AlertDialog.Builder(this)
                .setTitle(installed == null ? "Télécharger une région" : "Mettre à jour la région")
                .setView(content)
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Continuer", (dialog, which) -> startDownload(entry, metered.isChecked()))
                .show();
    }

    private void startDownload(MapsforgeCatalogParser.Entry entry, boolean allowMetered) {
        if (downloadManager == null) {
            toast("Gestionnaire de téléchargements Android indisponible.");
            return;
        }
        if (activeDownloadId >= 0) return;
        if (repository == null) {
            toast("Le stockage permanent des cartes est indisponible.");
            return;
        }
        try {
            URL url = MapsforgeCatalogClient.downloadUrl(entry.relativePath);
            if (!"https".equalsIgnoreCase(url.getProtocol())
                    || !MapsforgeCatalogClient.HOST.equalsIgnoreCase(url.getHost())) {
                throw new IOException("Serveur de téléchargement non autorisé");
            }
            File staging = getExternalFilesDir(STAGING_DIR);
            if (staging == null) {
                throw new IOException("Le stockage app-specific externe est indisponible. "
                        + "Android DownloadManager ne peut pas créer le fichier temporaire.");
            }
            if (!staging.isDirectory() && !staging.mkdirs()) {
                throw new IOException("Stockage temporaire indisponible");
            }
            String fileName = entry.regionId() + ".map";
            File staged = new File(staging, fileName);
            if (staged.exists() && !staged.delete()) throw new IOException("Ancien fichier temporaire impossible à supprimer");

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url.toString()));
            request.setTitle("Carte " + entry.displayName);
            request.setDescription("Carte vectorielle Mapsforge · données OpenStreetMap");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedOverMetered(allowMetered);
            request.setAllowedOverRoaming(false);
            request.addRequestHeader("User-Agent", MapsforgeCatalogClient.USER_AGENT);
            request.setDestinationInExternalFilesDir(this, STAGING_DIR, fileName);
            long id = downloadManager.enqueue(request);
            activeDownloadId = id;
            boolean persisted = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putLong(KEY_ID, id)
                    .putString(KEY_REGION, entry.regionId())
                    .putString(KEY_NAME, entry.displayName)
                    .putString(KEY_PATH, entry.relativePath)
                    .putString(KEY_VERSION, entry.lastModified)
                    .putBoolean(OfflineMapDownloadContract.KEY_INSTALLING, false)
                    .remove(OfflineMapDownloadContract.KEY_RESULT_SUCCESS)
                    .remove(OfflineMapDownloadContract.KEY_RESULT_MESSAGE)
                    .remove(OfflineMapDownloadContract.KEY_RESULT_TIME)
                    .commit();
            if (!persisted) {
                downloadManager.remove(id);
                throw new IOException("Impossible de mémoriser l'identifiant du téléchargement Android");
            }
            downloadPanel.setVisibility(View.VISIBLE);
            tvDownloadTitle.setText("Téléchargement · " + entry.displayName);
            tvDownloadProgress.setText("En attente du gestionnaire Android…");
            downloadProgress.setIndeterminate(true);
            btnCancelDownload.setEnabled(true);
            renderEntries();
            ui.removeCallbacks(pollDownload);
            ui.post(pollDownload);
        } catch (Exception e) {
            activeDownloadId = -1;
            toast("Téléchargement impossible : " + safeMessage(e));
        }
    }

    private void restorePendingDownload() {
        if (activeDownloadId >= 0) return;
        long id = getSharedPreferences(PREFS, MODE_PRIVATE).getLong(KEY_ID, -1L);
        if (id < 0) return;
        activeDownloadId = id;
        downloadPanel.setVisibility(View.VISIBLE);
        tvDownloadTitle.setText("Téléchargement · "
                + getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_NAME, "carte"));
        boolean pendingInstall = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(OfflineMapDownloadContract.KEY_INSTALLING, false);
        btnCancelDownload.setEnabled(!pendingInstall);
        if (pendingInstall) scheduleInstall(id);
        else {
            ui.removeCallbacks(pollDownload);
            ui.post(pollDownload);
        }
    }

    private void refreshDownloadState() {
        if (activeDownloadId < 0) return;
        long persistedId = getSharedPreferences(PREFS, MODE_PRIVATE).getLong(KEY_ID, -1L);
        if (persistedId != activeDownloadId) {
            activeDownloadId = persistedId;
            if (activeDownloadId < 0) {
                installing = false;
                downloadPanel.setVisibility(View.GONE);
                loadInstalledMaps();
                android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
                long resultTime = prefs.getLong(OfflineMapDownloadContract.KEY_RESULT_TIME, 0L);
                String result = prefs.getString(OfflineMapDownloadContract.KEY_RESULT_MESSAGE, "État du téléchargement actualisé.");
                if (resultTime > 0) tvStatus.setText(result);
                btnCancelDownload.setEnabled(false);
                renderEntries();
                return;
            }
        }
        if (getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(OfflineMapDownloadContract.KEY_INSTALLING, false)) installing = true;
        if (installing) {
            downloadPanel.setVisibility(View.VISIBLE);
            tvDownloadTitle.setText("Validation et installation · " + getPendingName());
            tvDownloadProgress.setText("Contrôle de l'en-tête Mapsforge, calcul SHA-256 et publication sécurisée…");
            downloadProgress.setIndeterminate(true);
            btnCancelDownload.setEnabled(false);
            return;
        }
        if (downloadManager == null) return;
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(activeDownloadId);
        try (Cursor cursor = downloadManager.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) {
                showDownloadFailure("Téléchargement introuvable dans Android.");
                return;
            }
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            long downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                scheduleInstall(activeDownloadId);
            } else if (status == DownloadManager.STATUS_FAILED) {
                int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                showDownloadFailure(downloadFailureMessage(reason));
            } else {
                downloadPanel.setVisibility(View.VISIBLE);
                if (total > 0) {
                    downloadProgress.setIndeterminate(false);
                    downloadProgress.setProgress((int) Math.min(1000, downloaded * 1000L / total));
                    tvDownloadProgress.setText(formatBytes(downloaded) + " / " + formatBytes(total)
                            + " · " + (downloaded * 100L / total) + "%");
                } else {
                    downloadProgress.setIndeterminate(true);
                    tvDownloadProgress.setText(formatBytes(downloaded) + " téléchargés");
                }
                if (status == DownloadManager.STATUS_PAUSED) {
                    int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                    tvDownloadTitle.setText("En pause · " + pauseMessage(reason));
                } else if (status == DownloadManager.STATUS_PENDING) {
                    tvDownloadTitle.setText("En attente · " + getPendingName());
                } else {
                    tvDownloadTitle.setText("Téléchargement · " + getPendingName());
                }
            }
        } catch (Exception e) {
            tvDownloadProgress.setText("État indisponible : " + safeMessage(e));
        }
    }

    private void scheduleInstall(long downloadId) {
        if (installing || downloadId < 0) return;
        try {
            installing = true;
            btnCancelDownload.setEnabled(false);
            downloadPanel.setVisibility(View.VISIBLE);
            tvDownloadTitle.setText("Validation et installation · " + getPendingName());
            tvDownloadProgress.setText("Finalisation persistante : vous pouvez fermer cet écran.");
            downloadProgress.setIndeterminate(true);
            OfflineMapInstallScheduler.enqueue(this, downloadId);
        } catch (RuntimeException e) {
            installing = false;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(OfflineMapDownloadContract.KEY_INSTALLING, false).commit();
            tvStatus.setText("Impossible de programmer l'installation : " + safeMessage(e));
            btnCancelDownload.setEnabled(true);
        }
    }

    private void showDownloadFailure(String message) {
        long failed = activeDownloadId;
        if (failed >= 0 && downloadManager != null) downloadManager.remove(failed);
        clearPending();
        activeDownloadId = -1;
        downloadPanel.setVisibility(View.GONE);
        tvStatus.setText("Téléchargement interrompu : " + message);
        renderEntries();
    }

    private void cancelDownload() {
        if (installing || getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(OfflineMapDownloadContract.KEY_INSTALLING, false)) {
            installing = true;
            toast("La validation est déjà en cours. Vous pouvez fermer cet écran ; l'installation continuera en arrière-plan.");
            return;
        }
        if (activeDownloadId < 0 || downloadManager == null) return;
        downloadManager.remove(activeDownloadId);
        androidx.work.WorkManager.getInstance(getApplicationContext()).cancelUniqueWork(
                OfflineMapDownloadContract.uniqueWorkName(activeDownloadId));
        clearPending();
        activeDownloadId = -1;
        installing = false;
        ui.removeCallbacks(pollDownload);
        downloadPanel.setVisibility(View.GONE);
        tvStatus.setText("Téléchargement annulé. La carte installée précédemment est conservée.");
        renderEntries();
    }

    private void confirmRemove(OfflineMapRepository.InstalledMap installed) {
        new AlertDialog.Builder(this)
                .setTitle("Supprimer cette carte ?")
                .setMessage(installed.displayName + " · " + formatBytes(installed.sizeBytes)
                        + "\n\nLe fichier local sera supprimé. Vous pourrez le télécharger à nouveau.")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Supprimer", (dialog, which) -> removeMap(installed))
                .show();
    }

    private void removeMap(OfflineMapRepository.InstalledMap installed) {
        worker.execute(() -> {
            try {
                boolean removed = repository.remove(installed.regionId);
                if (removed) publishMapsChanged();
                ui.post(() -> {
                    if (!isUiAlive()) return;
                    loadInstalledMaps();
                    renderEntries();
                    tvStatus.setText(removed ? "Carte supprimée : " + installed.displayName
                            : "Cette carte n'était déjà plus installée.");
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (isUiAlive()) tvStatus.setText("Suppression impossible : " + safeMessage(e));
                });
            }
        });
    }

    private void publishMapsChanged() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        long current = prefs.getLong(OfflineMapDownloadContract.KEY_MAPS_REVISION, 0L);
        boolean saved = prefs.edit().putLong(OfflineMapDownloadContract.KEY_MAPS_REVISION,
                OfflineMapDownloadContract.nextMapsRevision(current)).commit();
        if (saved) {
            sendBroadcast(new Intent(OfflineMapDownloadContract.ACTION_MAPS_UPDATED)
                    .setPackage(getPackageName()));
        }
    }

    private long repositoryFreeSpace() {
        File root = getExternalFilesDir("offline-maps");
        if (root == null) root = new File(getFilesDir(), "offline-maps");
        return root.getUsableSpace();
    }

    private String getPendingName() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_NAME, "carte");
    }

    private void clearPending() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .remove(KEY_ID).remove(KEY_REGION).remove(KEY_NAME)
                .remove(KEY_PATH).remove(KEY_VERSION)
                .remove(OfflineMapDownloadContract.KEY_INSTALLING).apply();
    }

    private String downloadFailureMessage(int reason) {
        switch (reason) {
            case DownloadManager.ERROR_INSUFFICIENT_SPACE: return "espace de stockage insuffisant";
            case DownloadManager.ERROR_DEVICE_NOT_FOUND: return "stockage indisponible";
            case DownloadManager.ERROR_FILE_ALREADY_EXISTS: return "un fichier temporaire existe déjà";
            case DownloadManager.ERROR_CANNOT_RESUME: return "le serveur ne permet pas de reprendre ce transfert";
            case DownloadManager.ERROR_HTTP_DATA_ERROR: return "erreur de transfert HTTP";
            case DownloadManager.ERROR_UNHANDLED_HTTP_CODE: return "réponse HTTP non prise en charge";
            case DownloadManager.ERROR_HTTP_NOT_FOUND: return "carte absente sur le serveur";
            default: return "échec du gestionnaire Android (code " + reason + ")";
        }
    }

    private String pauseMessage(int reason) {
        switch (reason) {
            case DownloadManager.PAUSED_WAITING_FOR_NETWORK: return "réseau indisponible";
            case DownloadManager.PAUSED_WAITING_TO_RETRY: return "nouvelle tentative automatique";
            case DownloadManager.PAUSED_QUEUED_FOR_WIFI: return "en attente du Wi-Fi";
            case DownloadManager.PAUSED_UNKNOWN: return "raison inconnue";
            default: return "en pause";
        }
    }

    private LinearLayout rowContainer() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(8, 8, 4, 8);
        return row;
    }

    private LinearLayout textColumn() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private void addText(LinearLayout parent, String value, int size, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(getResources().getColor(bold ? R.color.gps_text : R.color.gps_text_dim, getTheme()));
        if (bold) text.setTypeface(null, android.graphics.Typeface.BOLD);
        text.setPadding(0, 2, 6, 2);
        parent.addView(text);
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(10);
        button.setMinHeight(38);
        button.setMinimumHeight(38);
        button.setPadding(8, 0, 8, 0);
        return button;
    }

    private void addDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(getResources().getColor(R.color.gps_card, getTheme()));
        entries.addView(divider, new LinearLayout.LayoutParams(-1, 1));
    }

    private void addMessageRow(String message) {
        TextView text = new TextView(this);
        text.setText(message);
        text.setTextColor(getResources().getColor(R.color.gps_text_dim, getTheme()));
        text.setTextSize(12);
        text.setPadding(10, 16, 10, 16);
        entries.addView(text);
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }

    private static String formatBytes(long bytes) {
        if (bytes < 0) return "—";
        String[] units = {"o", "Kio", "Mio", "Gio", "Tio"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
        return new DecimalFormat(value >= 100 ? "0" : "0.0", DecimalFormatSymbolsHolder.FRENCH)
                .format(value) + " " + units[unit];
    }

    private static final class DecimalFormatSymbolsHolder {
        static final java.text.DecimalFormatSymbols FRENCH =
                java.text.DecimalFormatSymbols.getInstance(Locale.FRANCE);
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty() ? "erreur inconnue" : message;
    }
}
