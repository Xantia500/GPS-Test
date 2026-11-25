package com.example.gpstest;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * L'ÉCRAN. Plus aucun capteur ici : tout le GPS est dans TrackingService.
 *
 * C'EST LA LEÇON DU CYCLE DE VIE :
 *   - une Activity est détruite à la rotation et devient invisible quand l'app
 *     passe en arrière-plan : elle ne peut donc PAS porter un enregistrement ;
 *   - elle lit l'état partagé (TrackingState) à chaque tick et se contente d'afficher.
 *
 * Conséquence pratique : après une rotation ou un retour dans l'app, l'affichage
 * reprend tout seul, sans aucun code de restauration spécifique.
 */
public class MainActivity extends AppCompatActivity {

    private static final long UI_TICK_MS = 500L;

    // --- Vues ---
    private TextView tvStatus, tvLat, tvLon, tvAlt, tvAcc, tvSats, tvBands,
            tvBandSupport, tvConstellationSummary, tvSpeed, tvBearing, tvTtff,
            tvElapsed, tvMaxSats, tvMaxSnr, tvFixQuality, tvPdop, tvHdop, tvVdop,
            tvDopCompare, tvCompass, tvDatumPos, tvGpx, tvUtm, tvGpsTime,
            tvSun, tvMoon, tvNmeaSpeed;
    private ProgressBar pbSnr;
    private ListView lvSatellites;
    private SkyView skyView;
    private Button btnToggle, btnReset, btnAgps, btnGpx, btnExpert, btnSettings, btnMap,
            btnRetryRecovery;
    private Spinner spDatum, spSatFilter;
    private android.view.View rowDest, grpPosition, layoutRecoveryRetry;
    private TextView tvRecoveryRetrySummary;
    private final AtomicBoolean recoveryStatusLoading = new AtomicBoolean(false);
    private volatile String recoveryWorkSummary = "";
    private volatile int recoveryLastPublished;
    private volatile int recoveryLastLocalOnly;
    private volatile int recoveryWaitingPermission;
    private volatile int recoveryIntervention;
    private volatile boolean recoveryRecentNotice;
    private boolean lastRecoveryTrackingWanted;
    private android.widget.TextView tvSignalNote;
    private android.widget.TextView tvHudClock, tvHudFix, tvHudSats, tvHudBattery;
    private TextView tvDest;
    private TextView tvCompassNorth, tvDeclination, tvCompassNote;
    private CompassView compassView;

    /**
     * BOUSSOLE (v5.14) — voir CompassSensor pour la justification
     * architecturale : les capteurs d'AFFICHAGE vivent dans l'Activity,
     * pas dans le service, pour pouvoir travailler avant « Démarrer » et
     * cesser de consommer quand l'écran n'est pas visible.
     */
    private CompassSensor compass;
    private Compass.North northRef = Compass.North.MAGNETIC;
    /** Déclinaison du lieu, en degrés. NaN tant que la position est inconnue. */
    private float declinationDeg = Float.NaN;
    /** Dernière position utilisée pour la déclinaison, pour ne pas la recalculer. */
    private double declinationLat = Double.NaN, declinationLon = Double.NaN;
    /** Jour (local) du dernier calcul : la déclinaison dérive de ~0,1°/an. */
    private long declinationDay = Long.MIN_VALUE;

    private SatelliteAdapter adapter;
    private List<SatInfo> displayed = new ArrayList<>();

    private Datum selectedDatum = Datum.WGS84;
    private SatelliteFilter.Mode filterMode = SatelliteFilter.Mode.IN_FIX;

    private final TrackingState state = TrackingState.get();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        boolean fine = Boolean.TRUE.equals(
                                result.get(Manifest.permission.ACCESS_FINE_LOCATION));

                        if (!fine) {
                            tvStatus.setText("Permission refusée — GPS inutilisable");
                            return;
                        }

                        // La localisation est acquise : startTrackingNow gère
                        // séparément l'explication et la demande POST_NOTIFICATIONS.
                        // Le suivi ne dépend jamais de l'acceptation de cette permission.
                        startTrackingNow();
                    });

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                // Le refus n'empêche jamais le suivi. startTrackingServiceVisible()
                // contrôle le canal et affiche un seul avertissement si nécessaire.
                startTrackingServiceVisible();
            });

    /**
     * SÉLECTEUR DE DOSSIER (SAF).
     *
     * ACTION_OPEN_DOCUMENT_TREE fait choisir un DOSSIER (pas un fichier), et
     * le résultat porte une autorisation qu'on peut conserver au-delà de la
     * session — c'est tout l'intérêt par rapport à un chemin de fichier.
     *
     * Pourquoi un launcher enregistré et pas startActivityForResult : la
     * seconde forme est dépréciée depuis longtemps et perd le résultat si le
     * processus est recréé entre-temps. La première survit à ce cas.
     */
    private final ActivityResultLauncher<android.net.Uri> treeLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(),
                    uri -> {
                        if (uri == null) return;   // l'utilisateur a annulé : on ne change rien
                        Destination.setTreeUri(this, uri);
                        Prefs.writeDestPreset(this, null);   // dossier arbitraire
                        Prefs.writeDestLabel(this, labelForUri(uri));
                        Toast.makeText(this,
                                getString(R.string.dest_set_ok, Destination.humanPath(this)),
                                Toast.LENGTH_LONG).show();
                        refreshDestSummary();
                        recoveryWorkSummary = "";
                        recoveryLastPublished = 0;
                        recoveryLastLocalOnly = 0;
                        recoveryWaitingPermission = 0;
                        recoveryIntervention = 0;
                        recoveryRecentNotice = false;
                        GpxRecoveryRetryScheduler.enqueue(this);
                        refreshRecoveryRetryStatus();
                    });

    /**
     * Nom lisible d'une URI « tree » : on prend le dernier segment du
     * document id (« primary:Download/Mes traces » -> « Mes traces »).
     *
     * On ne peut pas interroger le fournisseur pour un nom plus joli sans
     * ajouter une dépendance (DocumentsContract.getDocumentInfo est une API
     * cachée). Le dernier segment est exact, court, et c'est celui que
     * l'utilisateur reconnaît.
     */
    private static String labelForUri(android.net.Uri uri) {
        try {
            return DestPresets.labelOf(
                    android.provider.DocumentsContract.getTreeDocumentId(uri));
        } catch (Exception e) {
            return "Dossier choisi";
        }
    }

    // ------------------------------------------------------------------
    // Destination des traces
    // ------------------------------------------------------------------

    /**
     * Résumé lisible de la destination courante, avec la mention HONNÊTE
     * quand l'accès a été perdu.
     *
     * Cas réel : l'utilisateur choisit « Téléchargements », l'écrit une
     * première fois, puis révoque la permission depuis les réglages Android
     * (ou retire la carte SD). L'URI reste en préférences ; sans ce contrôle,
     * l'app promettrait une destination qu'elle n'a plus le droit d'écrire.
     */
    /** Ouvre le sélecteur de dossier système. Public : StartDialog l'appelle
     *  quand l'utilisateur choisit « Changer de dossier » depuis la
     *  confirmation de démarrage. */
    public void chooseDestinationFolder() {
        try {
            treeLauncher.launch(null);
        } catch (Exception e) {
            Toast.makeText(this, R.string.dest_set_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void refreshDestSummary() {
        if (tvDest == null || rowDest == null) return;
        if (Destination.treeUri(this) == null) {
            // Dossier privé : rien d'utile à afficher, on masque la ligne.
            // Le détail et l'avertissement vivent dans les Réglages.
            rowDest.setVisibility(android.view.View.GONE);
            return;
        }
        rowDest.setVisibility(android.view.View.VISIBLE);
        if (!Destination.isExternalActive(this)) {
            tvDest.setText(Destination.humanPath(this) + "\n" + getString(R.string.dest_lost));
        } else {
            tvDest.setText(Destination.humanPath(this));
        }
    }

    // ------------------------------------------------------------------
    // Tick d'affichage : relit l'état partagé. Aucun état propre à l'Activity.
    // ------------------------------------------------------------------
    private final Runnable uiTick = new Runnable() {
        @Override
        public void run() {
            renderState();
            ui.postDelayed(this, UI_TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // THÈME AVANT setContentView : les vues doivent être créées avec
        // les bonnes couleurs. Appliqué après, on verrait un écran repeint
        // à moitié. Coin supérieur gauche = on est au tout début d'onCreate.
        ThemeManager.applyTo(this, ThemeManager.read(this));

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        EdgeToEdge.apply(this);
        bindViews();
        compass = new CompassSensor(this);
        northRef = Prefs.readCompassNorth(this);

        // CADRAN À LA FRÉQUENCE DU CAPTEUR, RESTE À 2 Hz.
        //
        // Le cadran doit suivre la main : il est redessiné à chaque nouvel
        // azimut (~15-30 Hz). Les TextView (cap en clair, nord de
        // référence, déclinaison) sont du TEXTE : les repeindre 30 fois par
        // seconde ferait clignoter l'écran et réveillerait la mise en page
        // pour rien. C'est le tick de 500 ms qui s'en charge, via
        // renderCompass.
        //
        // CompassView.setHeading ignore de lui-même les écarts inférieurs
        // à 0,4° : à l'arrêt, le cadran ne se redessine pas.
        compass.setOnHeadingChanged(new CompassSensor.OnHeadingChanged() {
            @Override
            public void onHeadingChanged() {
                if (compassView == null || compass == null) return;
                float mag = compass.azimuth();
                if (Float.isNaN(mag)) return;
                if (compassView.getVisibility() != android.view.View.VISIBLE) {
                    compassView.setVisibility(android.view.View.VISIBLE);
                }
                boolean trueApplied = Compass.trueNorthApplied(northRef, declinationDeg);
                // L'avertissement « déclinaison inconnue » doit suivre le
                // cadran : si la préférence est « nord vrai » mais qu'aucune
                // position n'est connue, le cadran ne doit pas étiqueter
                // « NORD VRAI » un cap qui est en réalité magnétique.
                compassView.setDeclinationUnknown(
                        northRef == Compass.North.TRUE && !trueApplied);
                compassView.setHeading(
                        Compass.toReference(mag, declinationDeg, northRef),
                        trueApplied);
            }
        });

        adapter = new SatelliteAdapter(this, displayed);
        lvSatellites.setAdapter(adapter);
        skyView.setSatellites(displayed);

        setupSpinners();
        setupButtons();

        // La reprise est désormais portée par WorkManager : elle survit à la
        // destruction du processus, reste bornée et n'exécute jamais d'I/O SAF
        // sur le thread GNSS ou UI. Le bandeau permet une relance explicite.
        observeRecoveryRetryWork();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // L'UI s'abonne à l'état partagé à chaque fois qu'elle redevient visible.
        lastRecoveryTrackingWanted = state.isUserWantsTracking();
        state.addListener(onStateChanged);
        // Au retour du sélecteur de dossier, l'utilisateur doit voir le
        // résultat de son choix immédiatement — onStart est le seul point
        // qui est appelé dans tous les cas de retour.
        refreshDestSummary();
        resetRecoveryWorkSummary();
        GpxRecoveryRetryScheduler.enqueue(this);
        refreshRecoveryRetryStatus();
        ui.post(uiTick);
        applyStateToButtons();
        applyKeepScreenOn();

        // BOUSSOLE : ouverture des capteurs à chaque retour à l'écran.
        // La préférence de nord est relue ici, parce qu'on revient
        // justement des réglages par onStart — la lire une seule fois
        // dans onCreate ferait croire que le réglage ne s'applique pas.
        northRef = Prefs.readCompassNorth(this);
        if (compass != null) compass.start();
        renderCompass();
    }

    /**
     * KEEP_SCREEN_ON est une propriété de FENÊTRE, donc de l'Activity.
     * Impossible de la porter depuis le service : c'est le seul point où
     * la politique d'arrière-plan redescend dans la vue. On la réapplique
     * à chaque onStart, au cas où l'utilisateur revient des réglages.
     */
    private void applyKeepScreenOn() {
        boolean keep = BackgroundPolicy.wantsKeepScreenOn(Prefs.readPolicy(this));
        if (keep) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // L'UI se désabonne, MAIS LE SERVICE CONTINUE.
        // C'est toute la différence : on n'arrête plus le tracking ici.
        state.removeListener(onStateChanged);
        ui.removeCallbacks(uiTick);
        // BOUSSOLE : les capteurs se referment avec l'écran. C'est le
        // bénéfice principal du déménagement hors du service : un
        // enregistrement de six heures ne fait plus tourner le
        // magnétomètre derrière un écran éteint.
        if (compass != null) compass.stop();
    }

    private final TrackingState.Listener onStateChanged = new TrackingState.Listener() {
        @Override
        public void onTrackingStateChanged() {
            boolean trackingWanted = state.isUserWantsTracking();
            boolean justStopped = lastRecoveryTrackingWanted && !trackingWanted;
            lastRecoveryTrackingWanted = trackingWanted;
            applyStateToButtons();
            if (justStopped) {
                // Le worker qui aurait été déclenché pendant le suivi a pu
                // terminer sans agir. Après l'arrêt réel du GNSS, on planifie
                // une reprise unique, sans toucher à ioFile ni au thread UI.
                GpxRecoveryRetryScheduler.enqueue(getApplicationContext());
                refreshRecoveryRetryStatus();
            }
        }
    };

    private void bindViews() {
        tvStatus = findViewById(R.id.tvStatus);
        tvLat = findViewById(R.id.tvLat);
        tvLon = findViewById(R.id.tvLon);
        tvAlt = findViewById(R.id.tvAlt);
        tvAcc = findViewById(R.id.tvAcc);
        tvSats = findViewById(R.id.tvSats);
        tvBands = findViewById(R.id.tvBands);
        tvBandSupport = findViewById(R.id.tvBandSupport);
        tvConstellationSummary = findViewById(R.id.tvConstellationSummary);
        tvSpeed = findViewById(R.id.tvSpeed);
        tvBearing = findViewById(R.id.tvBearing);
        tvTtff = findViewById(R.id.tvTtff);
        tvElapsed = findViewById(R.id.tvElapsed);
        tvMaxSats = findViewById(R.id.tvMaxSats);
        tvMaxSnr = findViewById(R.id.tvMaxSnr);
        tvFixQuality = findViewById(R.id.tvFixQuality);
        tvPdop = findViewById(R.id.tvPdop);
        tvHdop = findViewById(R.id.tvHdop);
        tvVdop = findViewById(R.id.tvVdop);
        tvDopCompare = findViewById(R.id.tvDopCompare);
        tvCompass = findViewById(R.id.tvCompass);
        tvCompassNorth = findViewById(R.id.tvCompassNorth);
        tvDeclination = findViewById(R.id.tvDeclination);
        tvCompassNote = findViewById(R.id.tvCompassNote);
        compassView = findViewById(R.id.compassView);
        tvDatumPos = findViewById(R.id.tvDatumPos);
        tvUtm = findViewById(R.id.tvUtm);
        tvGpsTime = findViewById(R.id.tvGpsTime);
        tvSun = findViewById(R.id.tvSun);
        tvMoon = findViewById(R.id.tvMoon);
        tvNmeaSpeed = findViewById(R.id.tvNmeaSpeed);
        tvGpx = findViewById(R.id.tvGpx);
        pbSnr = findViewById(R.id.pbSnr);
        lvSatellites = findViewById(R.id.lvSatellites);
        skyView = findViewById(R.id.skyView);
        btnToggle = findViewById(R.id.btnToggle);
        btnReset = findViewById(R.id.btnReset);
        btnAgps = findViewById(R.id.btnAgps);
        btnMap = findViewById(R.id.btnMap);
        btnGpx = findViewById(R.id.btnGpx);
        btnExpert = findViewById(R.id.btnExpert);
        btnSettings = findViewById(R.id.btnSettings);
        btnRetryRecovery = findViewById(R.id.btnRetryRecovery);
        layoutRecoveryRetry = findViewById(R.id.layoutRecoveryRetry);
        tvRecoveryRetrySummary = findViewById(R.id.tvRecoveryRetrySummary);
        spDatum = findViewById(R.id.spDatum);
        spSatFilter = findViewById(R.id.spSatFilter);
        rowDest = findViewById(R.id.rowDest);
        grpPosition = findViewById(R.id.grpPosition);
        tvSignalNote = findViewById(R.id.tvSignalNote);
        tvHudClock = findViewById(R.id.tvHudClock);
        tvHudFix = findViewById(R.id.tvHudFix);
        tvHudSats = findViewById(R.id.tvHudSats);
        tvHudBattery = findViewById(R.id.tvHudBattery);
        tvDest = findViewById(R.id.tvDest);
    }

    // ==================================================================
    // Boutons
    // ==================================================================

    private void setupSpinners() {
        String[] datumNames = new String[Datum.ALL.length];
        for (int i = 0; i < Datum.ALL.length; i++) datumNames[i] = Datum.ALL[i].name;
        ArrayAdapter<String> da = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, datumNames);
        da.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spDatum.setAdapter(da);

        // RESTAURATION AVANT d'attacher l'écouteur : sinon setSelection
        // déclenche onItemSelected et on réécrit la préférence qu'on vient
        // de lire — inoffensif ici, mais c'est le genre de boucle qui devient
        // un vrai bug le jour où l'écriture a un effet de bord.
        Datum savedDatum = Prefs.readDatum(this);
        selectedDatum = savedDatum;
        for (int i = 0; i < Datum.ALL.length; i++) {
            if (Datum.ALL[i].name.equals(savedDatum.name)) { spDatum.setSelection(i, false); break; }
        }

        spDatum.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, android.view.View v, int pos, long id) {
                selectedDatum = Datum.ALL[pos];
                // ÉCRITURE IMMÉDIATE. Sans elle, une rotation d'écran ramène
                // le datum à WGS84 et l'utilisateur croit à un bug.
                Prefs.writeDatum(MainActivity.this, selectedDatum);
                renderState();
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });

        ArrayAdapter<String> fa = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, SatelliteFilter.labels());
        fa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spSatFilter.setAdapter(fa);

        SatelliteFilter.Mode savedMode = Prefs.readSatFilter(this);
        filterMode = savedMode;
        for (int i = 0; i < SatelliteFilter.DISPLAY_ORDER.length; i++) {
            if (SatelliteFilter.DISPLAY_ORDER[i] == savedMode) {
                spSatFilter.setSelection(i, false); break;
            }
        }

        spSatFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, android.view.View v, int pos, long id) {
                filterMode = SatelliteFilter.DISPLAY_ORDER[pos];
                Prefs.writeSatFilter(MainActivity.this, filterMode);
                applySatelliteFilter();
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
    }

    private void setupButtons() {
        btnToggle.setOnClickListener(v -> {
            if (state.isUserWantsTracking()) {
                // On DEMANDE l'arrêt, on ne le décrète pas.
                // Avant, cette ligne faisait aussi state.setUserWantsTracking(false) :
                // l'Activity écrivait un état que le service possède. Conséquence :
                // un appui sur ARRÊTER puis DÉMARRER dans la même seconde créait
                // une seconde session pendant que la première se fermait.
                // Le service reste seul propriétaire de l'intention.
                TrackingService.stop(this);
            } else {
                ensurePermissionThenStart();
            }
        });

        btnReset.setOnClickListener(v -> {
            // Même règle : l'Activity ne remet pas à zéro un compteur que le
            // service incrémente. Sinon le service réécrit l'ancienne valeur au
            // callback suivant et le bouton « ne marche pas une fois sur deux ».
            // Ici, une demande au service serait disproportionnée pour deux
            // champs : on expose donc la remise à zéro dans TrackingState et
            // c'est le service qui, au prochain callback, repart de zéro.
            state.resetPeaks();
            tvMaxSats.setText(getString(R.string.placeholder));
            tvMaxSnr.setText(getString(R.string.placeholder));
        });

        // Carte et relecture de traces (v5.10)
        btnMap.setOnClickListener(v ->
                startActivity(new android.content.Intent(this, MapActivity.class)));

        btnAgps.setOnClickListener(v -> {
            try {
                android.location.LocationManager lm =
                        (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
                boolean ok = lm.sendExtraCommand(
                        android.location.LocationManager.GPS_PROVIDER,
                        "delete_aiding_data", null);
                Toast.makeText(this,
                        ok ? "AGPS réinitialisé — le prochain fix sera plus long"
                           : "Le système a refusé la remise à zéro", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "Impossible : " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        btnGpx.setOnClickListener(v -> {
            if (state.isRecording()) {
                // ARRÊT : pas de confirmation. Confirmer une action qui
                // n'écrit rien et qu'on peut reprendre est une friction
                // gratuite — les gens cliquent « oui » sans lire.
                TrackingService.stopRecording(this);
            } else {
                if (!state.isUserWantsTracking()) {
                    Toast.makeText(this, "Démarrez d'abord le GPS", Toast.LENGTH_SHORT).show();
                    return;
                }
                // DÉMARRAGE : confirmation avec le chemin affiché en clair.
                // C'est une action qui crée un fichier et qu'on va oublier
                // en route : l'utilisateur DOIT savoir où il atterrit avant.
                StartDialog.show(this, () -> TrackingService.startRecording(this));
            }
            // L'affichage du bouton suit l'état réel, relu au tick suivant
            // (≤ 500 ms) : plus d'optimisme qui ment quand le service refuse
            // (stockage plein, écran éteint en mode économie…).
        });

        btnExpert.setOnClickListener(v -> openSafely(ExpertActivity.class));
        btnSettings.setOnClickListener(v -> openSafely(SettingsActivity.class));
        btnRetryRecovery.setOnClickListener(v -> {
            if (state.isUserWantsTracking()) {
                Toast.makeText(this, R.string.gpx_recovery_retry_after_tracking,
                        Toast.LENGTH_LONG).show();
                return;
            }
            recoveryWorkSummary = "";
            recoveryLastPublished = 0;
            recoveryLastLocalOnly = 0;
            recoveryWaitingPermission = 0;
            recoveryIntervention = 0;
            recoveryRecentNotice = false;
            if (GpxRecoveryRetryScheduler.enqueue(this)) {
                btnRetryRecovery.setEnabled(false);
                tvRecoveryRetrySummary.setText(R.string.gpx_recovery_retry_started);
            } else {
                Toast.makeText(this, R.string.gpx_recovery_retry_unavailable,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void resetRecoveryWorkSummary() {
        recoveryWorkSummary = "";
        recoveryLastPublished = 0;
        recoveryLastLocalOnly = 0;
        recoveryWaitingPermission = 0;
        recoveryIntervention = 0;
        recoveryRecentNotice = false;
    }

    /** Observe le travail persistant et actualise le bandeau après chaque tentative. */
    private void observeRecoveryRetryWork() {
        WorkManager.getInstance(getApplicationContext())
                .getWorkInfosForUniqueWorkLiveData(GpxRecoveryRetryWorker.UNIQUE_WORK_NAME)
                .observe(this, workInfos -> {
                    if (workInfos == null || workInfos.isEmpty()) return;
                    WorkInfo latest = workInfos.get(workInfos.size() - 1);
                    if (latest.getState() == WorkInfo.State.RUNNING) {
                        recoveryWorkSummary = getString(R.string.gpx_recovery_retry_running);
                        btnRetryRecovery.setEnabled(false);
                        refreshRecoveryRetryStatus();
                    } else if (latest.getState() == WorkInfo.State.ENQUEUED) {
                        resetRecoveryWorkSummary();
                        refreshRecoveryRetryStatus();
                    } else if (latest.getState() == WorkInfo.State.BLOCKED) {
                        btnRetryRecovery.setEnabled(false);
                    } else if (latest.getState().isFinished()) {
                        androidx.work.Data output = latest.getOutputData();
                        String summary = output.getString(GpxRecoveryRetryWorker.KEY_SUMMARY);
                        if (summary != null && !summary.trim().isEmpty()) recoveryWorkSummary = summary;
                        recoveryLastPublished = output.getInt(GpxRecoveryRetryWorker.KEY_PUBLISHED, 0);
                        recoveryLastLocalOnly = output.getInt(GpxRecoveryRetryWorker.KEY_LOCAL_ONLY, 0);
                        recoveryWaitingPermission = output.getInt(GpxRecoveryRetryWorker.KEY_WAITING_PERMISSION, 0);
                        recoveryIntervention = output.getInt(GpxRecoveryRetryWorker.KEY_INTERVENTION, 0);
                        recoveryRecentNotice = output.getBoolean(GpxRecoveryRetryWorker.KEY_NOTICE, false);
                        refreshRecoveryRetryStatus();
                    }
                });
    }

    /** Lecture des compteurs hors UI ; un registre illisible reste visible et n'est jamais effacé. */
    private void refreshRecoveryRetryStatus() {
        if (!recoveryStatusLoading.compareAndSet(false, true)) return;
        new Thread(() -> {
            SessionStore.RecoveryReadResult recoveries =
                    SessionStore.readRecoveriesDetailed(MainActivity.this);
            int pendingRecoveries = recoveries.sessions.size();
            boolean activeTracking = state.isUserWantsTracking();
            if (activeTracking) {
                SessionJournal current = SessionStore.read(MainActivity.this);
                if (current != null) {
                    for (SessionJournal candidate : recoveries.sessions) {
                        if (candidate.sessionId.equals(current.sessionId)
                                && candidate.segmentGeneration == current.segmentGeneration
                                && candidate.file.equals(current.file)) {
                            pendingRecoveries = Math.max(0, pendingRecoveries - 1);
                            break;
                        }
                    }
                }
            }
            int pendingPublications = 0;
            boolean publicationRegistryReadable = true;
            try {
                pendingPublications = new RecoveryPublicationStore(MainActivity.this).read().size();
            } catch (Exception unreadable) {
                publicationRegistryReadable = false;
            }
            final int recoveryCount = pendingRecoveries;
            final int publicationCount = pendingPublications;
            final boolean recoveryReadable = recoveries.registryReadable && recoveries.journalReadable
                    && (recoveries.message == null || recoveries.message.isEmpty());
            final boolean publicationsReadable = publicationRegistryReadable;
            final boolean tracking = activeTracking;
            final String detail = recoveryWorkSummary;
            runOnUiThread(() -> {
                recoveryStatusLoading.set(false);
                boolean diagnostic = !recoveryReadable || !publicationsReadable;
                boolean retentionNotice = detail != null
                        && (detail.contains("Conservation des copies publiées")
                        || detail.contains("Seuil indicatif de stockage")
                        || detail.contains("Registre de conservation illisible"));
                boolean recentOutcome = recoveryLastPublished > 0 || recoveryLastLocalOnly > 0
                        || recoveryWaitingPermission > 0 || recoveryIntervention > 0
                        || recoveryRecentNotice || retentionNotice;
                boolean visible = diagnostic || recoveryCount > 0 || publicationCount > 0
                        || recentOutcome;
                layoutRecoveryRetry.setVisibility(visible ? android.view.View.VISIBLE : android.view.View.GONE);
                if (!visible) {
                    recoveryWorkSummary = "";
                    return;
                }
                StringBuilder text = new StringBuilder();
                if (!recoveryReadable) {
                    if (!recoveries.registryReadable) {
                        text.append(getString(R.string.gpx_recovery_registry_unreadable));
                    } else if (!recoveries.journalReadable) {
                        text.append(getString(R.string.gpx_recovery_journal_unreadable));
                    } else {
                        text.append(getString(R.string.gpx_recovery_state_not_synchronized));
                    }
                    if (recoveries.message != null && !recoveries.message.isEmpty())
                        text.append(" ").append(recoveries.message);
                } else if (!publicationsReadable) {
                    text.append(getString(R.string.gpx_recovery_publication_registry_unreadable));
                } else {
                    text.append(getString(R.string.gpx_recovery_retry_counts,
                            recoveryCount, publicationCount));
                    if (recentOutcome) {
                        text.append("\n").append(getString(R.string.gpx_recovery_retry_last_result,
                                recoveryLastPublished, recoveryLastLocalOnly,
                                recoveryWaitingPermission, recoveryIntervention));
                    }
                }
                if (tracking) text.append("\n").append(getString(R.string.gpx_recovery_wait_tracking_stop));
                if (detail != null && !detail.trim().isEmpty()) text.append("\n").append(detail);
                tvRecoveryRetrySummary.setText(text.toString());
                btnRetryRecovery.setEnabled(!tracking && recoveryReadable && publicationsReadable);
            });
        }, "gpx-recovery-status").start();
    }

    /**
     * Ouvre un écran en absorbant ActivityNotFoundException.
     *
     * Cas réel : un manifest édité à la main, un build qui oublie une Activity,
     * ou (plus subtil) un appareil qui restreint le lancement. Un crash
     * « Unable to find explicit activity class » est incompréhensible pour
     * l'utilisateur ; un message l'est.
     */
    private void openSafely(Class<?> activity) {
        try {
            startActivity(new Intent(this, activity));
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "Écran indisponible : " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void ensurePermissionThenStart() {
        boolean fine = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (fine) startTrackingNow();
        else permissionLauncher.launch(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION});
    }

    /**
     * Point d'entrée UNIQUE du démarrage.
     *
     * AVANT, cette méthode demandait elle-même la permission de notification.
     * C'est ce qui créait la boucle : une méthode « démarrer » ne doit pas
     * pouvoir redemander une permission, sinon elle peut être rappelée par le
     * callback de cette même demande. Séparation stricte :
     *
     *   ensurePermissionThenStart()  -> demande, puis appelle startTrackingNow()
     *   startTrackingNow()           -> démarre, et RIEN d'autre
     */
    private void startTrackingNow() {
        android.location.LocationManager lm =
                (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null || !lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, "Activez la localisation dans les réglages",
                    Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            } catch (Exception ignored) {
                // Certains appareils n'ont pas cet écran : un message suffit.
            }
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("notification_permission_asked", false)) {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Notification de suivi")
                    .setMessage("La notification persistante permet de voir que le suivi GNSS fonctionne et de le suspendre ou l'arrêter rapidement. Le GPS peut démarrer même si vous refusez.")
                    .setNegativeButton("Continuer sans", (dialog, which) -> {
                        getPreferences(MODE_PRIVATE).edit().putBoolean("notification_permission_asked", true).apply();
                        startTrackingServiceVisible();
                    })
                    .setPositiveButton("Autoriser", (dialog, which) -> {
                        getPreferences(MODE_PRIVATE).edit().putBoolean("notification_permission_asked", true).apply();
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                    })
                    .setOnCancelListener(dialog -> {
                        getPreferences(MODE_PRIVATE).edit()
                                .putBoolean("notification_permission_asked", true).apply();
                        startTrackingServiceVisible();
                    })
                    .show();
        } else {
            startTrackingServiceVisible();
        }
    }

    private void startTrackingServiceVisible() {
        // Démarrage depuis une Activity visible : conforme aux restrictions
        // Android 12+ et au contrôle while-in-use de la localisation Android 14+.
        // Une permission refusée ou un canal bloqué n'empêche pas le suivi,
        // mais l'utilisateur doit savoir que la notification ne sera pas
        // visible dans le tiroir (l'entrée reste dans « Applications actives »).
        if (!TrackingService.areTrackingNotificationsEnabled(this)) {
            Toast.makeText(this,
                    "Suivi autorisé, mais notification désactivée. Consultez « Applications actives » ou réactivez les notifications de GPS-Test dans les réglages.",
                    Toast.LENGTH_LONG).show();
        }
        try {
            TrackingService.start(this);
        } catch (RuntimeException error) {
            // Android peut refuser le démarrage (permission révoquée,
            // transition d'état système, exception OEM). Ne pas laisser le
            // bouton donner l'impression qu'un suivi est actif.
            String message = "Impossible de démarrer le suivi : "
                    + error.getClass().getSimpleName();
            state.setTrackingNotice(message);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            state.notifyChanged();
        }
    }

    private void applyStateToButtons() {
        btnToggle.setText(state.isUserWantsTracking() ? R.string.btn_stop : R.string.btn_start);
        btnGpx.setText(state.isRecording() ? R.string.btn_gpx_stop : R.string.btn_gpx_start);
    }

    // ==================================================================
    // Rendu de l'état partagé
    // ==================================================================

    /**
     * BANDEAU PERMANENT — heure, type de fix, satellites, batterie.
     *
     * Appelé à chaque rafraîchissement d'état (1 Hz) : l'heure avance à la
     * seconde, les satellites changent à la seconde, la batterie ne bouge
     * pas plus vite. Inutile de mettre en place un second minuteur.
     *
     * Les TEXTES sont construits par StatusHud, classe PURE testée hors
     * appareil. Ici, on ne fait que les poser sur les vues.
     */
    private void renderHud() {
        if (tvHudClock == null) return;

        tvHudClock.setText(StatusHud.clockLabel(System.currentTimeMillis()));

        // La dimension GSA est prioritaire lorsqu'elle est fraîche. Sinon,
        // on indique seulement la disponibilité de l'altitude sans prétendre
        // que Location.hasAltitude() est une preuve de solution 3D.
        boolean anyFix = state.isSignalOk();
        long nowElapsed = android.os.SystemClock.elapsedRealtime();
        int receiverFixType = state.getFreshNmeaFixType(nowElapsed, 5_000L);
        boolean altitudeAvailable = anyFix && state.hasAltitudeFix();
        tvHudFix.setText(StatusHud.fixLabel(receiverFixType, altitudeAvailable, anyFix));
        tvHudFix.setTextColor(getResources().getColor(
                StatusHud.fixColorRes(receiverFixType, anyFix, altitudeAvailable), getTheme()));

        tvHudSats.setText(StatusHud.satsLabel(
                state.getSatellitesUsed(), state.getSatellitesSeen()));

        tvHudBattery.setText(StatusHud.readBattery(this).label());
    }

    private void renderState() {
        boolean running = state.isUserWantsTracking();
        boolean signalOk = state.isSignalOk();

        // ── LA BARRE DE STATUT DOIT DIRE POURQUOI, PAS SEULEMENT QUE ────
        //
        // Cinq situations distinctes, parce qu'elles n'ont pas le même sens
        // pour l'utilisateur. Les confondre serait aussi grave que de ne rien
        // dire : « pas de position » alors qu'il en avait une il y a 10 s,
        // ce n'est pas la même chose que « le GPS est éteint ».
        String reject = state.getRejectReason();
        if (running && state.isSuspended()) {
            tvStatus.setText(R.string.status_paused);
        } else if (state.getTrackingNotice() != null) {
            tvStatus.setText(state.getTrackingNotice());
        } else if (!running) {
            tvStatus.setText(R.string.status_waiting);
        } else if (signalOk) {
            tvStatus.setText(R.string.status_fix);
        } else if (state.getLostForMs() >= 0) {
            // La DURÉE s'écrit ici, à l'écran — jamais dans le message vocal.
            tvStatus.setText(getString(R.string.status_signal_lost_long,
                    LossAnnouncer.durationLabel(state.getLostForMs())));
        } else if (state.getModeReject() != null) {
            // MODE (v5.11) : le point est refusé par le seuil du mode, pas
            // par le moteur. On le dit avec le CHIFFRE et la LIMITE : « 12 m
            // (limite 8 m) » est actionnable, « précision insuffisante »
            // ne l'est pas. Et ça n'apparaît QUE si le moteur accepte le
            // fix — sinon le message du moteur (plus grave) prend la place.
            tvStatus.setText(state.getModeReject());
        } else if (reject != null) {
            // Réception présente mais fix refusé : on dit POURQUOI.
            //
            // Pour « trop peu de satellites », on dit COMBIEN : « 4 / 6 »
            // est actionnable (« il m'en manque deux »), « précision
            // insuffisante » ne l'est pas. Et comme le minimum dépend du
            // nombre de constellations actives, l'utilisateur découvre au
            // passage que son téléphone mélange GPS et Galileo.
            if (reject.equals(FixQuality.RejectReason.TOO_FEW_SATELLITES.label)) {
                int used = state.getSatellitesUsed();
                int need = FixQuality.minSatellitesFor(
                        Math.max(1, state.getActiveConstellations()));
                tvStatus.setText(getString(R.string.status_sat_short, used, need));
            } else {
                tvStatus.setText(getString(R.string.status_signal_invalid, reject));
            }
        } else {
            tvStatus.setText(R.string.status_no_fix);
        }

        // ══════════════════════════════════════════════════════════════
        // GRISAGE — l'exigence impérative.
        //
        // Rien n'est enregistré tant que le signal n'est pas valide (c'est
        // la machine à états qui l'applique). Mais l'AFFICHAGE doit le dire
        // aussi : une position figée, lisible et non grisée, est un
        // mensonge. L'utilisateur croirait que sa trace se remplit.
        //
        // On utilise l'alpha sur le conteneur des valeurs de position
        // plutôt que de remplacer chaque texte par « — » : la dernière
        // valeur connue RESTE visible (utile pour se repérer), mais
        // visiblement inerte.
        // ══════════════════════════════════════════════════════════════
        // GRISAGE — on grise dès que la réception n'est pas valide, qu'on
        // ait déjà eu un fix ou non. Une position à 200 m de précision
        // affichée en pleine opacité est un mensonge, même si elle est neuve.
        renderHud();

        float alpha = (running && !signalOk) ? 0.35f : 1.0f;
        if (grpPosition != null) {
            grpPosition.setAlpha(alpha);
        }
        // Le texte peut être vrai et grisé : on ajoute une ligne qui dit
        // explicitement que rien ne s'enregistre, pour que l'utilisateur ne
        // se demande pas si la trace continue « quand même ».
        if (tvSignalNote != null) {
            boolean showNote = running && !signalOk && state.isRecording();
            tvSignalNote.setVisibility(showNote ? android.view.View.VISIBLE
                                                : android.view.View.GONE);
        }

        // Position
        if (state.hasFix()) {
            tvLat.setText(Utils.formatDms(state.getLatitude(), true));
            tvLon.setText(Utils.formatDms(state.getLongitude(), false));
            // LES UNITÉS SONT LUES À CHAQUE TICK, PAS MÉMORISÉES.
            // Un utilisateur qui change m/s -> mph dans les réglages voit le
            // changement au retour, sans redémarrer l'app. Une préférence lue
            // une fois dans onCreate ne serait pas rafraîchie et donnerait
            // l'impression que le réglage ne marche pas.
            Units.System sys = Prefs.readUnits(this);
            int freshType = state.getFreshNmeaFixType(
                    android.os.SystemClock.elapsedRealtime(), 5_000L);
            tvAlt.setText(state.hasAltitudeFix()
                    ? Units.formatAltitude(sys, state.getAltitude())
                    : (freshType == 2 ? "— (fix 2D, altitude non résolue)"
                            : "— (altitude non fournie)"));
            tvAcc.setText(Units.formatAccuracy(sys, state.getAccuracy()));
            tvSpeed.setText(Units.formatSpeed(sys, state.getSpeedMps()));
            tvBearing.setText(Float.isNaN(state.getBearingDeg())
                    ? "Cap en attente" : Utils.formatBearing(state.getBearingDeg()));
            renderDatumAndAstro();
        } else {
            tvLat.setText("—"); tvLon.setText("—"); tvAlt.setText("—"); tvAcc.setText("—");
            tvSpeed.setText("—"); tvBearing.setText("—"); tvDatumPos.setText("—");
            tvUtm.setText("—"); tvSun.setText("—"); tvMoon.setText("—");
        }

        // Satellites
        tvSats.setText(String.format(Locale.FRANCE, "%d vus / %d utilisés",
                state.getSatellitesSeen(), state.getSatellitesUsed()));
        pbSnr.setProgress(Math.round(
                Utils.snrFraction(state.getMaxCn0()) * 100f));
        tvBands.setText(state.getBandCounters() == null ? "—" : state.getBandCounters());
        tvBandSupport.setText(state.getBandSupport() == null
                ? "Bandes : détection en cours…" : state.getBandSupport());
        List<SatInfo> snapshot = state.snapshotSatellites();
        tvConstellationSummary.setText(SatelliteFilter.constellationSummary(snapshot));

        applySatelliteFilter(snapshot);

        // Qualité / DOP. GGA (type de correction) et GSA (dimension 2D/3D)
        // restent deux sources explicitement séparées.
        long dopNowElapsed = android.os.SystemClock.elapsedRealtime();
        int nmeaFixType = state.getFreshNmeaFixType(dopNowElapsed, 5_000L);
        String qualityLabel = state.getFreshFixQuality(dopNowElapsed, 5_000L);
        String dimensionLabel = nmeaFixType == 2 ? "GSA 2D"
                : nmeaFixType == 3 ? "GSA 3D"
                : nmeaFixType == 1 ? "GSA sans fix" : null;
        if (dimensionLabel != null) {
            tvFixQuality.setText((qualityLabel == null ? "GGA n/d" : "GGA " + qualityLabel)
                    + " · " + dimensionLabel);
        } else {
            tvFixQuality.setText(qualityLabel == null ? "—" : "GGA " + qualityLabel);
        }
        double rxH = state.getFreshRxHdop(dopNowElapsed, 5_000L);
        String rxHSource = "GSA";
        if (!Double.isFinite(rxH) || rxH <= 0) {
            rxH = state.getFreshGgaHdop(dopNowElapsed, 5_000L);
            rxHSource = "GGA";
        }
        double rxV = state.getFreshRxVdop(dopNowElapsed, 5_000L);
        double rxP = state.getFreshRxPdop(dopNowElapsed, 5_000L);
        DopCalculator.Dop calc = state.getFreshCalcDop(dopNowElapsed, 5_000L);
        tvHdop.setText(formatDopPair(rxH, calc != null ? calc.hdop : Double.NaN, true,
                Double.isFinite(rxH) && rxH > 0 ? rxHSource : "Rx"));
        tvVdop.setText(formatDopPair(rxV, calc != null ? calc.vdop : Double.NaN, false, "GSA"));
        tvPdop.setText(formatDopPair(rxP, calc != null ? calc.pdop : Double.NaN, false, "GSA"));
        tvDopCompare.setText(DopCalculator.compare(rxH, calc));

        // Où va la trace, en permanence pendant l'enregistrement.
        // On ne l'affiche que si une destination externe est configurée :
        // sinon la ligne est masquée par refreshDestSummary().
        if (tvDest != null && rowDest != null
                && rowDest.getVisibility() == android.view.View.VISIBLE) {
            String line = StartDialog.statusLine(this);
            if (line != null) tvDest.setText(line + "\n" + Destination.humanPath(this));
        }

        // Boussole (v5.14) : le cadran, le nord de référence, la déclinaison
        renderCompass();

        // Temps
        if (state.getSessionStartElapsedMs() > 0 && running) {
            long nowElapsed = android.os.SystemClock.elapsedRealtime();
            tvElapsed.setText(Utils.formatDuration(nowElapsed - state.getSessionStartElapsedMs()));
            if (state.getFirstFixElapsedMs() > 0) {
                tvTtff.setText(Utils.formatDuration(
                        state.getFirstFixElapsedMs() - state.getSessionStartElapsedMs()));
            }
        } else {
            tvElapsed.setText("—"); tvTtff.setText("—");
        }
        tvMaxSats.setText(state.getMaxSatellitesSeen() == 0 ? "—"
                : String.valueOf(state.getMaxSatellitesSeen()));
        tvMaxSnr.setText(state.getMaxCn0Seen() <= 0 ? "—"
                : String.format(Locale.FRANCE, "%.1f dBHz", state.getMaxCn0Seen()));
        long nmeaNowElapsed = android.os.SystemClock.elapsedRealtime();
        String freshGpsTime = state.getFreshGpsTime(nmeaNowElapsed, 10_000L);
        String freshNmeaSpeed = state.getFreshNmeaSpeed(nmeaNowElapsed, 5_000L);
        tvGpsTime.setText(freshGpsTime == null ? "—" : freshGpsTime);
        tvNmeaSpeed.setText(freshNmeaSpeed == null ? "—" : freshNmeaSpeed);

        // GPX
        //
        // ORDRE VOLONTAIRE : le résultat du nettoyage passe AVANT le compte
        // de points. Après un « Terminer », c'est la seule information que
        // l'utilisateur cherche — « où est mon fichier, et qu'a-t-il
        // changé ? ». L'afficher après le nombre de points la rendrait
        // invisible sous le bruit.
        if (state.getGpxError() != null) {
            tvGpx.setText("Erreur : " + state.getGpxError()
                    + (state.isRecording() ? "\n" + gpxCounterLine() : ""));
        } else if (state.getCleanResult() != null) {
            tvGpx.setText(state.getCleanResult());
        } else if (state.getSessionNotice() != null) {
            tvGpx.setText(state.getSessionNotice());
        } else if (state.isRecording()) {
            tvGpx.setText("GPX : " + gpxCounterLine()
                    + (state.getGpxDurabilityWarning() == null ? "" : "\n" + state.getGpxDurabilityWarning()));
        } else if (state.getLastGpxFile() != null) {
            tvGpx.setText(String.format(Locale.FRANCE, "%d points sérialisés — %s%s",
                    state.getPointsWritten(), state.getLastGpxFile(),
                    state.getGpxDurabilityWarning() == null ? "" : "\n" + state.getGpxDurabilityWarning()));
        } else {
            tvGpx.setText("—");
        }
    }

    private static String formatDopPair(double receiver, double calculated,
                                         boolean quality, String receiverSource) {
        String rx = Double.isFinite(receiver) && receiver > 0
                ? String.format(Locale.FRANCE, "%.1f", receiver) : "—";
        String calc = Double.isFinite(calculated) && calculated > 0
                ? String.format(Locale.FRANCE, "%.1f", calculated) : "—";
        String source = receiverSource == null || receiverSource.trim().isEmpty()
                ? "Rx" : "Rx " + receiverSource;
        String text = source + " " + rx + " · Est. " + calc;
        if (quality) {
            if (Double.isFinite(receiver) && receiver > 0) {
                text += " · " + NmeaParser.dopQuality(receiver);
            } else if (Double.isFinite(calculated) && calculated > 0) {
                text += " · " + NmeaParser.dopQuality(calculated);
            }
        }
        return text;
    }

    private String gpxCounterLine() {
        return String.format(Locale.FRANCE,
                "%d acceptés · %d en file · %d sérialisés · %d échecs (avant %d / après %d) · attente %d",
                state.getPointsAccepted(), state.getPointsQueued(), state.getPointsWritten(),
                state.getPointsFailed(), state.getPointsFailedBeforeQueue(),
                state.getPointsFailedAfterQueue(), state.getGpxWriteQueueDepth());
    }

    // ── MÉMOÏSATION DES CALCULS CÉLESTES (v5.13) ────────────────────────
    //
    // Levé et coucher du Soleil se calculent à partir de la date, de la
    // latitude et de la longitude. Rien de tout cela ne change d'une
    // demi-seconde à l'autre : pourtant renderDatumAndAstro() était appelé
    // deux fois par seconde et recalculait les deux éphémérides complètes.
    //
    // Ce n'est pas gratuit : Astro.compute() fait de la trigonométrie
    // sphérique et une recherche par itération. Faire tourner ça 7 200 fois
    // par heure pour afficher deux heures qui ne changent pas est
    // exactement le genre de travail que l'utilisateur paie en batterie.
    //
    // On mémorise donc sur la clé (latitude au 1e-4°, longitude au 1e-4°,
    // jour julien local). C'est 11 m de précision en latitude : largement
    // assez pour un lever de soleil, et cela rend le cache stable sans
    // scintiller quand le GNSS oscille de deux mètres.
    private double astroLat = Double.NaN;
    private double astroLon = Double.NaN;
    private long astroDay = -1L;
    private String astroSunText = null;
    private String astroMoonText = null;
    private Datum astroDatum = null;
    private String astroDatumText = null;
    private String astroUtmText = null;
    private double astroPosLat = Double.NaN;
    private double astroPosLon = Double.NaN;

    // ══════════════════════════════════════════════════════════════════
    // BOUSSOLE (v5.14)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Met à jour le cadran, l'étiquette du nord de référence et la
     * déclinaison.
     *
     * TROIS CAS À DISTINGUER, et les confondre est précisément ce que
     * faisait la version précédente :
     *
     *   1. la boussole est ABSENTE (pas de capteur)  → on le DIT ;
     *   2. elle est présente mais n'a pas encore donné d'azimut
     *      (premier événement non reçu)             → « en attente » ;
     *   3. elle donne un cap                          → cadran dessiné.
     *
     * Dans les cas 1 et 2, le cadran est masqué : afficher une aiguille
     * pointée au nord serait une mesure inventée, pas une absence.
     */
    private void renderCompass() {
        if (compassView == null || compass == null) return;

        // ---- déclinaison : recalculée seulement si la position ou le jour change
        refreshDeclination();

        boolean available = compass.isAvailable();
        float mag = compass.azimuth();            // magnétique, orienté écran
        boolean hasAzimuth = available && !Float.isNaN(mag);

        float shown = Compass.toReference(mag, declinationDeg, northRef);
        boolean trueApplied = Compass.trueNorthApplied(northRef, declinationDeg);

        // ---- état du cadran
        if (available && hasAzimuth) {
            if (compassView.getVisibility() != android.view.View.VISIBLE) {
                compassView.setVisibility(android.view.View.VISIBLE);
            }
            compassView.setDeclinationUnknown(northRef == Compass.North.TRUE && !trueApplied);
            compassView.setHeading(shown, trueApplied);
        } else {
            if (compassView.getVisibility() != android.view.View.GONE) {
                compassView.setVisibility(android.view.View.GONE);
            }
        }

        // ---- note explicative sous le cadran
        if (tvCompassNote != null) {
            String note = null;
            if (!available) {
                // L'appareil n'a pas de capteur : on le dit explicitement,
                // comme demandé. Un tiret muet laisserait croire à une panne.
                String why = compass.unavailableReason();
                note = getString(R.string.compass_absent)
                        + (why != null ? " (" + why + ")" : "");
            } else if (!hasAzimuth) {
                note = getString(R.string.compass_waiting);
            } else if (compass.tooTilted()) {
                note = getString(R.string.compass_tilted, compass.tilt());
            } else if (compass.isUnreliable()) {
                note = getString(R.string.compass_magnetic_disturbed);
            } else if (northRef == Compass.North.TRUE && !trueApplied) {
                note = getString(R.string.compass_declination_unknown);
            }
            if (note == null) {
                if (tvCompassNote.getVisibility() != android.view.View.GONE) {
                    tvCompassNote.setVisibility(android.view.View.GONE);
                }
            } else {
                tvCompassNote.setText(note);
                if (tvCompassNote.getVisibility() != android.view.View.VISIBLE) {
                    tvCompassNote.setVisibility(android.view.View.VISIBLE);
                }
            }
        }

        // ---- description pour les lecteurs d'écran ----
        //
        // La ligne de texte « Boussole » a disparu de l'écran (le cadran
        // affiche la même chose), mais une image sans description est
        // muette pour TalkBack. On publie donc le cap sous forme de texte
        // sur la vue elle-même : un lecteur d'écran annoncera
        // « Boussole : Nord-Est (045°), nord vrai ».
        if (compassView != null) {
            String desc;
            if (!available) {
                desc = getString(R.string.compass_absent);
            } else if (!hasAzimuth) {
                desc = getString(R.string.compass_waiting);
            } else {
                String nord = trueApplied ? "nord vrai" : "nord magnétique";
                desc = Compass.describe(shown) + ", " + nord;
            }
            compassView.setContentDescription(desc);
        }
        // La TextView cachée garde la valeur brute : c'est le seul moyen de
        // récupérer le cap par un outil d'accessibilité qui lit le texte.
        if (tvCompass != null) {
            tvCompass.setText(hasAzimuth ? Compass.describe(shown) : "—");
        }

        // ---- nord de référence effectivement appliqué
        if (tvCompassNorth != null) {
            if (!available) {
                tvCompassNorth.setText("—");
            } else {
                // Chaînes en ressources, pas en dur : la règle du projet
                // est que tout texte affiché vient de strings.xml. Un
                // littéral ici serait invisible à la traduction et
                // divergerait du libellé des réglages.
                tvCompassNorth.setText(trueApplied
                        ? R.string.compass_north_true
                        : R.string.compass_north_magnetic);
            }
        }

        // ---- déclinaison du lieu
        if (tvDeclination != null) {
            if (Float.isNaN(declinationDeg)) {
                tvDeclination.setText("—");
            } else {
                tvDeclination.setText(Compass.formatDeclination(declinationDeg)
                        + (Float.isNaN(mag) ? "" : "  (à la position actuelle)"));
            }
        }
    }

    /**
     * Recalcule la déclinaison magnétique à la position courante.
     *
     * POURQUOI PAS À CHAQUE TICK : la déclinaison change de moins de
     * 0,1° par an et de quelques millièmes de degré par kilomètre. La
     * recalculer 2 fois par seconde serait du gaspillage pur — exactement
     * le défaut corrigé en v5.13 sur l'astronomie. On la recalcule donc
     * seulement quand :
     *   - on ne l'a jamais calculée,
     *   - la position a bougé de plus de 0,01° (≈ 1,1 km),
     *   - le jour local a changé (le modèle dépend de la date).
     *
     * Sans position, la déclinaison reste NaN : on ne suppose JAMAIS
     * zéro, ce qui donnerait un « nord vrai » qui n'en est pas un.
     */
    private void refreshDeclination() {
        double lat = state.getLatitude();
        double lon = state.getLongitude();
        if (!state.hasFix() || Double.isNaN(lat) || Double.isNaN(lon)) return;

        long now = System.currentTimeMillis();
        long day = Math.floorDiv(now + TimeZone.getDefault().getOffset(now), 86_400_000L);

        boolean need = Float.isNaN(declinationDeg)
                || day != declinationDay
                || Double.isNaN(declinationLat)
                || Math.abs(lat - declinationLat) > 0.01
                || Math.abs(lon - declinationLon) > 0.01;
        if (!need) return;

        float d = CompassSensor.declinationAt(lat, lon, state.getAltitude(), now);
        if (Float.isNaN(d)) return;    // échec : on garde l'ancienne valeur

        declinationDeg = d;
        declinationLat = lat;
        declinationLon = lon;
        declinationDay = day;
    }

    private void renderDatumAndAstro() {
        double lat = state.getLatitude();
        double lon = state.getLongitude();

        // ── Position dans le datum et en UTM : recalculés SEULEMENT si la
        // position ou le datum a changé. La rampe GNSS fait osciller la
        // dernière décimale à chaque fix ; on arrondit donc à 1e-5° (1 m)
        // pour ne pas recalculer une projection pour un mètre de bruit.
        boolean posChanged = selectedDatum != astroDatum
                || Math.abs(lat - astroPosLat) > 1e-5
                || Math.abs(lon - astroPosLon) > 1e-5;
        if (posChanged) {
            astroDatum = selectedDatum;
            astroPosLat = lat;
            astroPosLon = lon;
            astroDatumText = selectedDatum == Datum.WGS84
                    ? "WGS 84 (position native)"
                    : String.format(Locale.FRANCE, "%s  %s",
                        Utils.formatDms(selectedDatum.fromWgs84(lat, lon, 0)[0], true),
                        Utils.formatDms(selectedDatum.fromWgs84(lat, lon, 0)[1], false));
            astroUtmText = Utm.fromLatLon(lat, lon, selectedDatum).format();
        }
        tvDatumPos.setText(astroDatumText);
        tvUtm.setText(astroUtmText);

        // ── Soleil et Lune : une fois par jour, pas deux fois par seconde.
        long now = System.currentTimeMillis();
        long day = Math.floorDiv(now + TimeZone.getDefault().getOffset(now), 86_400_000L);
        if (day != astroDay
                || Math.abs(lat - astroLat) > 1e-4
                || Math.abs(lon - astroLon) > 1e-4
                || astroSunText == null) {
            astroDay = day;
            astroLat = lat;
            astroLon = lon;

            Astro.SunTimes sun = Astro.compute(lat, lon, now);
            if (sun.polarDay) astroSunText = "jour polaire";
            else if (sun.polarNight) astroSunText = "nuit polaire";
            else if (sun.valid()) {
                TimeZone tz = TimeZone.getDefault();
                astroSunText = String.format(Locale.FRANCE, "lever %s · coucher %s",
                        Astro.formatLocalTime(sun.sunriseUtcMs, tz),
                        Astro.formatLocalTime(sun.sunsetUtcMs, tz));
            } else astroSunText = "—";

            double phase = Astro.moonPhaseFraction(now);
            astroMoonText = String.format(Locale.FRANCE, "%s (%.0f%%)",
                    Astro.moonPhaseName(phase), Astro.moonIllumination(phase) * 100);
        }
        tvSun.setText(astroSunText);
        tvMoon.setText(astroMoonText);
    }

    private void applySatelliteFilter() {
        applySatelliteFilter(state.snapshotSatellites());
    }

    private void applySatelliteFilter(List<SatInfo> snapshot) {
        SatelliteFilter.Mode mode = filterMode != null ? filterMode : SatelliteFilter.Mode.ALL;
        List<SatInfo> filtered = SatelliteFilter.apply(snapshot, mode);
        // On ne remplace que si le contenu a changé : évite un clignotement de la liste
        if (!sameList(filtered, displayed)) {
            displayed.clear();
            displayed.addAll(filtered);
            adapter.notifyDataSetChanged();
            skyView.setSatellites(displayed);
            skyView.invalidate();
        }
    }

    private static boolean floatChanged(float a, float b, float tolerance) {
        // NaN != valeur/NaN doit être traité explicitement : Math.abs(NaN)
        // vaut NaN et la comparaison « > tolerance » renvoie false.
        if (Float.isNaN(a) != Float.isNaN(b)) return true;
        if (Float.isNaN(a)) return false;
        return Math.abs(a - b) > tolerance;
    }

    private static boolean sameList(List<SatInfo> a, List<SatInfo> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            SatInfo x = a.get(i), y = b.get(i);
            if (x.svid != y.svid || x.cn0 != y.cn0 || x.inFix != y.inFix
                    || !java.util.Objects.equals(x.constellation, y.constellation)
                    || floatChanged(x.azimuth, y.azimuth, 0.1f)
                    || floatChanged(x.elevation, y.elevation, 0.1f)
                    || Double.compare(x.carrierHz, y.carrierHz) != 0) return false;
        }
        return true;
    }
}
