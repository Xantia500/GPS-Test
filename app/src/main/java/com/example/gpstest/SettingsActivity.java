package com.example.gpstest;

import android.os.Bundle;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * L'ÉCRAN DE RÉGLAGES.
 *
 * Il ne possède aucune donnée : il lit la politique persistée au démarrage,
 * et chaque clic écrit immédiatement (pas de bouton « Enregistrer » — un
 * réglage qu'on oublie d'enregistrer est une source de bugs).
 *
 * Le changement de politique est envoyé au service PAR INTENT ACTION, jamais
 * en écrivant directement dans l'état partagé : le service reste le seul
 * propriétaire de la décision « suspendre ou non ». L'Activity propose,
 * le service dispose — c'est la même règle qu'avec le GPS.
 */
public class SettingsActivity extends AppCompatActivity {

    private RadioGroup rgBackground, rgGpx;
    private TextView tvDataSummary, tvDataPath, tvDestPath, tvDestWarning;
    private RadioGroup rgDest;
    private RadioButton rbDestDownload, rbDestDocuments, rbDestApp;
    /** true pendant qu'on reflète l'état réel dans les boutons : empêche
     *  leurs écouteurs de considérer cela comme un choix de l'utilisateur. */
    private boolean suppressDestEvents = false;

    private RadioGroup rgUnits, rgTheme;
    private RadioButton rbUnitsMetric, rbUnitsImperial, rbUnitsNautical;
    private android.widget.CheckBox cbVoiceAlert, cbVoiceRecovery;
    private android.widget.Spinner spMinDistance, spMinTime;
    // v5.11
    private android.widget.Spinner spTrackMode, spPpTolerance;
    private android.widget.CheckBox cbPostProcess;
    private android.widget.TextView tvModeSummary, tvModeAccuracy;
    private android.widget.TextView tvSamplingDistance, tvSamplingTime,
            tvSamplingIntro, tvPpTolerance;
    private boolean suppressSamplingEvents = false;
    private android.widget.Spinner spVoiceRepeat, spVoiceMax;
    private android.view.View grpVoiceOptions;
    private boolean suppressVoiceEvents = false;

    /** Correspondance position d'affichage <-> valeur persistée. */
    private static final long[] REPEAT_VALUES = LossAnnouncer.REPEAT_CHOICES;
    private static final int[] MAX_VALUES = LossAnnouncer.MAX_CHOICES;   // {1,3,5,10,0}
    private boolean suppressUnitsEvents = false;
    /** Boussole (v5.14) : sélecteur du nord de référence. */
    private RadioGroup rgCompassNorth;
    private RadioButton rbNorthMagnetic, rbNorthTrue;
    private boolean suppressNorthEvents = false;
    private RadioButton rbThemeSystem, rbThemeLight, rbThemeDark;
    /** true pendant qu'on reflète le thème réel : empêche l'écouteur de
     *  prendre ce positionnement pour un choix de l'utilisateur. */
    private boolean suppressThemeEvents = false;

    /**
     * SÉLECTEUR DE DOSSIER (SAF).
     *
     * OpenDocumentTree rend une URI de DOSSIER avec une autorisation
     * persistable. C'est le seul mécanisme propre sur Android pour laisser
     * l'utilisateur choisir un emplacement : un chemin de fichier comme
     * /storage/emulated/0/Documents ne veut pas dire la même chose selon
     * l'appareil, et y écrire exige des permissions que Google restreint
     * depuis Android 10.
     */
    private final androidx.activity.result.ActivityResultLauncher<android.net.Uri> treeLauncher =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
                    uri -> {
                        if (uri == null) return;   // annulé : aucun changement
                        Destination.setTreeUri(this, uri);
                        // Dossier ARBITRAIRE : aucun preset ne le représente.
                        // On efface l'identifiant, sinon l'écran continuerait
                        // d'afficher le libellé de l'ancien preset.
                        Prefs.writeDestPreset(this, null);
                        Prefs.writeDestLabel(this, labelForUri(uri));
                        Toast.makeText(this,
                                getString(R.string.dest_set_ok, Destination.humanPath(this)),
                                Toast.LENGTH_LONG).show();
                        refreshDestination();
                    });
    private RadioButton rbContinue, rbKeepOn, rbSuspend, rbGpx11, rbGpx10;
    private TextView tvContinueDesc, tvKeepOnDesc, tvSuspendDesc,
            tvGpx11Desc, tvGpx10Desc, tvWarnSegment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // THÈME AVANT setContentView : les vues doivent être créées avec
        // les bonnes couleurs. Appliqué après, on verrait un écran repeint
        // à moitié. Coin supérieur gauche = on est au tout début d'onCreate.
        ThemeManager.applyTo(this, ThemeManager.read(this));

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        EdgeToEdge.apply(this);

        rgBackground = findViewById(R.id.rgBackground);
        rgGpx = findViewById(R.id.rgGpx);
        rbContinue = findViewById(R.id.rbContinue);
        rbKeepOn = findViewById(R.id.rbKeepOn);
        rbSuspend = findViewById(R.id.rbSuspend);
        rbGpx11 = findViewById(R.id.rbGpx11);
        rbGpx10 = findViewById(R.id.rbGpx10);
        tvContinueDesc = findViewById(R.id.tvContinueDesc);
        tvKeepOnDesc = findViewById(R.id.tvKeepOnDesc);
        tvSuspendDesc = findViewById(R.id.tvSuspendDesc);
        tvGpx11Desc = findViewById(R.id.tvGpx11Desc);
        tvGpx10Desc = findViewById(R.id.tvGpx10Desc);
        tvWarnSegment = findViewById(R.id.tvWarnSegment);
        tvDataSummary = findViewById(R.id.tvDataSummary);
        tvDataPath = findViewById(R.id.tvDataPath);
        tvDestPath = findViewById(R.id.tvDestPath);
        tvDestWarning = findViewById(R.id.tvDestWarning);
        rgDest = findViewById(R.id.rgDest);
        rbDestDownload = findViewById(R.id.rbDestDownload);
        rbDestDocuments = findViewById(R.id.rbDestDocuments);
        rbDestApp = findViewById(R.id.rbDestApp);
        rgUnits = findViewById(R.id.rgUnits);
        rbUnitsMetric = findViewById(R.id.rbUnitsMetric);
        rbUnitsImperial = findViewById(R.id.rbUnitsImperial);
        rbUnitsNautical = findViewById(R.id.rbUnitsNautical);
        cbVoiceAlert = findViewById(R.id.cbVoiceAlert);
        cbVoiceRecovery = findViewById(R.id.cbVoiceRecovery);
        spVoiceRepeat = findViewById(R.id.spVoiceRepeat);
        spVoiceMax = findViewById(R.id.spVoiceMax);
        grpVoiceOptions = findViewById(R.id.grpVoiceOptions);
        spMinDistance = findViewById(R.id.spMinDistance);
        spMinTime = findViewById(R.id.spMinTime);
        spTrackMode = findViewById(R.id.spTrackMode);
        spPpTolerance = findViewById(R.id.spPpTolerance);
        cbPostProcess = findViewById(R.id.cbPostProcess);
        tvModeSummary = findViewById(R.id.tvModeSummary);
        tvModeAccuracy = findViewById(R.id.tvModeAccuracy);
        tvSamplingDistance = findViewById(R.id.tvSamplingDistance);
        tvSamplingTime = findViewById(R.id.tvSamplingTime);
        tvSamplingIntro = findViewById(R.id.tvSamplingIntro);
        tvPpTolerance = findViewById(R.id.tvPpTolerance);
        rgCompassNorth = findViewById(R.id.rgCompassNorth);
        rbNorthMagnetic = findViewById(R.id.rbNorthMagnetic);
        rbNorthTrue = findViewById(R.id.rbNorthTrue);
        rgTheme = findViewById(R.id.rgTheme);
        rbThemeSystem = findViewById(R.id.rbThemeSystem);
        rbThemeLight = findViewById(R.id.rbThemeLight);
        rbThemeDark = findViewById(R.id.rbThemeDark);

        fillLabels();

        // État initial relu depuis le stockage (pas depuis l'état partagé :
        // l'Activity doit être juste même si le service n'a jamais démarré).
        setChecked(Prefs.readPolicy(this));
        setGpxChecked(Prefs.readGpxVersion(this));

        rgBackground.setOnCheckedChangeListener((group, checkedId) -> {
            BackgroundPolicy.Policy p = policyFromCheckedId(checkedId);
            if (p == null) return;
            applyPolicy(p);
        });

        rgGpx.setOnCheckedChangeListener((group, checkedId) -> {
            GpxWriter.Version v = checkedId == R.id.rbGpx10
                    ? GpxWriter.Version.V10 : GpxWriter.Version.V11;
            Prefs.writeGpxVersion(this, v);
            // Un changement de version ne réécrit PAS la trace en cours :
            // elle serait incohérente. Elle s'applique au segment suivant.
            tvWarnSegment.setText(getString(R.string.warn_gpx_applied, v.label));
        });

        ((Button) findViewById(R.id.btnCloseSettings)).setOnClickListener(v -> finish());

        ((Button) findViewById(R.id.btnShareLatest)).setOnClickListener(v -> {
            if (!ExportHelper.shareLatestGpx(this)) {
                Toast.makeText(this, R.string.data_share_failed, Toast.LENGTH_SHORT).show();
            }
        });

        ((Button) findViewById(R.id.btnManageRecovered)).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, RecoveryManagementActivity.class)));

        ((TextView) findViewById(R.id.tvThemeSystemDesc)).setText(R.string.theme_system_desc);
        ((TextView) findViewById(R.id.tvThemeLightDesc)).setText(R.string.theme_light_desc);
        ((TextView) findViewById(R.id.tvThemeDarkDesc)).setText(R.string.theme_dark_desc);

        // État réel du thème, PUIS écouteur : même règle que partout ailleurs
        // dans cet écran (cocher un bouton par programme ne doit pas passer
        // pour un choix de l'utilisateur).
        reflectTheme();
        rgTheme.setOnCheckedChangeListener((group, checkedId) -> {
            if (suppressThemeEvents) return;
            ThemeMode.Mode m;
            if (checkedId == R.id.rbThemeLight) m = ThemeMode.Mode.LIGHT;
            else if (checkedId == R.id.rbThemeDark) m = ThemeMode.Mode.DARK;
            else if (checkedId == R.id.rbThemeSystem) m = ThemeMode.Mode.SYSTEM;
            else return;
            applyTheme(m);
        });

        reflectUnits();
        rgUnits.setOnCheckedChangeListener((group, checkedId) -> {
            if (suppressUnitsEvents) return;
            Units.System sys;
            if (checkedId == R.id.rbUnitsImperial) sys = Units.System.IMPERIAL;
            else if (checkedId == R.id.rbUnitsNautical) sys = Units.System.NAUTICAL;
            else if (checkedId == R.id.rbUnitsMetric) sys = Units.System.METRIC;
            else return;
            // ÉCRITURE IMMÉDIATE, comme partout dans cet écran. Aucune
            // recréation nécessaire : les unités ne changent que du
            // formatage, pas les ressources du thème.
            Prefs.writeUnits(this, sys);
        });

        reflectCompassNorth();
        rgCompassNorth.setOnCheckedChangeListener((group, checkedId) -> {
            if (suppressNorthEvents) return;
            Compass.North n;
            if (checkedId == R.id.rbNorthTrue)      n = Compass.North.TRUE;
            else if (checkedId == R.id.rbNorthMagnetic) n = Compass.North.MAGNETIC;
            else return;
            // Écriture immédiate, comme les unités : ce réglage ne change
            // aucune ressource de thème, donc aucune recréation.
            Prefs.writeCompassNorth(this, n);
        });

        ((TextView) findViewById(R.id.tvVoiceDesc)).setText(R.string.settings_voice_desc);
        setupVoiceOptions();
        setupMode();
        setupSampling();

        refreshDataSummary();
        refreshDestination();

        // Écouteur APRÈS refreshDestination() : celui-ci coche le bouton
        // déjà actif, ce qui déclencherait l'écouteur et réécrirait la
        // préférence à l'ouverture de l'écran. Inoffensif aujourd'hui,
        // source de bug dès qu'une écriture aura un effet de bord.
        rgDest.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rbDestDownload) {
                applyDestination(Destination.PRESETS[0]);
            } else if (checkedId == R.id.rbDestDocuments) {
                applyDestination(Destination.PRESETS[1]);
            } else if (checkedId == R.id.rbDestApp) {
                applyDestination(Destination.PRESETS[2]);
            }
        });

        ((Button) findViewById(R.id.btnChooseFolder)).setOnClickListener(v -> {
            // Pas de document suggéré (null) : l'utilisateur choisit librement.
            try {
                treeLauncher.launch(null);
            } catch (Exception e) {
                // Appareils sans fournisseur de documents (rare, mais réel
                // sur certains ROM). Un message vaut mieux qu'un crash.
                Toast.makeText(this, R.string.dest_set_failed, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ------------------------------------------------------------------
    // Espacement des points (v5.10)
    // ------------------------------------------------------------------

    private void setupSampling() {
        String[] dist = new String[SamplePolicy.DISTANCE_CHOICES.length];
        for (int i = 0; i < dist.length; i++) {
            int m = SamplePolicy.DISTANCE_CHOICES[i];
            dist[i] = (m == 0) ? getString(R.string.sampling_distance_none)
                               : (m >= 1000 ? (m / 1000) + " km" : m + " m");
        }
        String[] time = new String[SamplePolicy.TIME_CHOICES.length];
        for (int i = 0; i < time.length; i++) {
            long ms = SamplePolicy.TIME_CHOICES[i];
            if (ms == 0) time[i] = getString(R.string.sampling_time_none);
            else if (ms < 60_000) time[i] = (ms / 1000) + " s";
            else time[i] = (ms / 60_000) + " min";
        }

        spMinDistance.setAdapter(simpleAdapter(dist));
        spMinTime.setAdapter(simpleAdapter(time));

        suppressSamplingEvents = true;
        try {
            spMinDistance.setSelection(indexOfInt(
                    SamplePolicy.DISTANCE_CHOICES, Prefs.readMinDistanceM(this)));
            spMinTime.setSelection(indexOfLong(
                    SamplePolicy.TIME_CHOICES, Prefs.readMinTimeMs(this)));
        } finally {
            suppressSamplingEvents = false;
        }

        spMinDistance.setOnItemSelectedListener(new SimpleItemSelectedListener() {
            @Override public void onItemSelected(int position) {
                if (suppressSamplingEvents) return;
                if (position >= 0 && position < SamplePolicy.DISTANCE_CHOICES.length) {
                    Prefs.writeMinDistanceM(SettingsActivity.this,
                            SamplePolicy.DISTANCE_CHOICES[position]);
                    // EN MODE PERSONNALISÉ, LA LIGNE DE RÉSUMÉ DOIT SUIVRE.
                    // Sans cet appel, l'utilisateur change « 20 m » en
                    // « 500 m » et la ligne juste au-dessus continue
                    // d'afficher « 20 m » : l'écran contredit le réglage.
                    applyModeVisibility();
                }
            }
        });
        spMinTime.setOnItemSelectedListener(new SimpleItemSelectedListener() {
            @Override public void onItemSelected(int position) {
                if (suppressSamplingEvents) return;
                if (position >= 0 && position < SamplePolicy.TIME_CHOICES.length) {
                    Prefs.writeMinTimeMs(SettingsActivity.this,
                            SamplePolicy.TIME_CHOICES[position]);
                    applyModeVisibility();
                }
            }
        });
    }

    private static int indexOfInt(int[] values, int v) {
        for (int i = 0; i < values.length; i++) if (values[i] == v) return i;
        return 2;   // 25 m
    }

    private static int indexOfLong(long[] values, long v) {
        for (int i = 0; i < values.length; i++) if (values[i] == v) return i;
        return 4;   // 10 s
    }

    // ------------------------------------------------------------------
    // Alertes vocales : trois réglages liés
    // ------------------------------------------------------------------

    private void setupVoiceOptions() {
        // Adaptateurs construits depuis les tableaux de VALEURS : l'ordre
        // affiché et l'ordre des valeurs ne peuvent pas diverger silencieusement.
        String[] repeats = {
                getString(R.string.voice_repeat_never),
                getString(R.string.voice_repeat_30s),
                getString(R.string.voice_repeat_1min),
                getString(R.string.voice_repeat_2min),
                getString(R.string.voice_repeat_5min)
        };
        String[] maxes = {
                getString(R.string.voice_max_1),
                getString(R.string.voice_max_3),
                getString(R.string.voice_max_5),
                getString(R.string.voice_max_10),
                getString(R.string.voice_max_inf)
        };
        spVoiceRepeat.setAdapter(simpleAdapter(repeats));
        spVoiceMax.setAdapter(simpleAdapter(maxes));

        // --- état initial, sans déclencher les écouteurs ---
        suppressVoiceEvents = true;
        try {
            cbVoiceAlert.setChecked(Prefs.readVoiceAlert(this));
            cbVoiceRecovery.setChecked(Prefs.readVoiceRecovery(this));
            spVoiceRepeat.setSelection(indexOf(REPEAT_VALUES, Prefs.readVoiceRepeatMs(this)));
            spVoiceMax.setSelection(indexOf(MAX_VALUES, Prefs.readVoiceMaxCount(this)));
        } finally {
            suppressVoiceEvents = false;
        }
        applyVoiceEnabledState();

        cbVoiceAlert.setOnCheckedChangeListener((btn, checked) -> {
            if (suppressVoiceEvents) return;
            Prefs.writeVoiceAlert(this, checked);
            // Réafficher immédiatement l'état du bloc dépendant : on ne
            // laisse pas des réglages actifs à l'écran alors qu'ils ne le
            // sont plus.
            applyVoiceEnabledState();
        });

        cbVoiceRecovery.setOnCheckedChangeListener((btn, checked) -> {
            if (suppressVoiceEvents) return;
            Prefs.writeVoiceRecovery(this, checked);
        });

        spVoiceRepeat.setOnItemSelectedListener(new SimpleItemSelectedListener() {
            @Override public void onItemSelected(int position) {
                if (suppressVoiceEvents) return;
                if (position >= 0 && position < REPEAT_VALUES.length) {
                    Prefs.writeVoiceRepeatMs(SettingsActivity.this,
                            REPEAT_VALUES[position]);
                }
            }
        });

        spVoiceMax.setOnItemSelectedListener(new SimpleItemSelectedListener() {
            @Override public void onItemSelected(int position) {
                if (suppressVoiceEvents) return;
                if (position >= 0 && position < MAX_VALUES.length) {
                    Prefs.writeVoiceMaxCount(SettingsActivity.this,
                            MAX_VALUES[position]);
                }
            }
        });
    }

    /**
     * Grise et désactive le bloc quand l'alerte vocale est décochée.
     *
     * « Visible mais inerte » plutôt que « caché » : l'utilisateur voit
     * quelles options il retrouverait, sans risquer de croire qu'elles
     * s'appliquent alors que l'alerte est coupée.
     */
    private void applyVoiceEnabledState() {
        boolean on = cbVoiceAlert.isChecked();
        grpVoiceOptions.setAlpha(on ? 1.0f : 0.4f);
        grpVoiceOptions.setEnabled(on);
        setEnabledDeep(grpVoiceOptions, on);
    }

    private void setEnabledDeep(android.view.View v, boolean enabled) {
        v.setEnabled(enabled);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                setEnabledDeep(g.getChildAt(i), enabled);
            }
        }
    }

    private android.widget.ArrayAdapter<String> simpleAdapter(String[] items) {
        // simple_spinner_item a un texte BLANC par défaut sur fond clair en
        // thème jour : illisible. On force la couleur du texte.
        android.widget.ArrayAdapter<String> a = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    private static int indexOf(long[] values, long v) {
        for (int i = 0; i < values.length; i++) if (values[i] == v) return i;
        return 1;   // 30 s : valeur par défaut
    }

    private static int indexOf(int[] values, int v) {
        for (int i = 0; i < values.length; i++) if (values[i] == v) return i;
        return 4;   // sans limite : valeur par défaut
    }

    /** Écouteur de sélection minimal, pour ne pas réécrire les 2 méthodes. */
    private abstract static class SimpleItemSelectedListener
            implements android.widget.AdapterView.OnItemSelectedListener {
        public abstract void onItemSelected(int position);
        @Override
        public final void onItemSelected(android.widget.AdapterView<?> parent,
                                         android.view.View view, int position, long id) {
            onItemSelected(position);
        }
        @Override public final void onNothingSelected(android.widget.AdapterView<?> parent) { }
    }

    /** Coche le bouton qui correspond au système d'unités persisté. */
    private void reflectUnits() {
        suppressUnitsEvents = true;
        try {
            Units.System sys = Prefs.readUnits(this);
            if (sys == Units.System.IMPERIAL)      rbUnitsImperial.setChecked(true);
            else if (sys == Units.System.NAUTICAL) rbUnitsNautical.setChecked(true);
            else                                   rbUnitsMetric.setChecked(true);
        } finally {
            suppressUnitsEvents = false;
        }
    }

    /**
     * Coche le nord de référence persisté.
     *
     * Le garde {{@code suppressNorthEvents}} est indispensable : setChecked
     * déclenche le listener, qui écrirait la préférence qu'on vient de
     * lire. Sans lui, un simple affichage de l'écran réécrirait le
     * stockage — inoffensif ici, mais c'est exactement le genre de
     * boucle qui devient un bug le jour où la valeur écrite diffère.
     */
    private void reflectCompassNorth() {
        suppressNorthEvents = true;
        try {
            Compass.North n = Prefs.readCompassNorth(this);
            if (n == Compass.North.TRUE) rbNorthTrue.setChecked(true);
            else                         rbNorthMagnetic.setChecked(true);
        } finally {
            suppressNorthEvents = false;
        }
    }

    /** Coche le bouton qui correspond au thème réellement persisté. */
    private void reflectTheme() {
        suppressThemeEvents = true;
        try {
            ThemeMode.Mode m = ThemeManager.read(this);
            if (m == ThemeMode.Mode.LIGHT)      rbThemeLight.setChecked(true);
            else if (m == ThemeMode.Mode.DARK)  rbThemeDark.setChecked(true);
            else                                   rbThemeSystem.setChecked(true);
        } finally {
            suppressThemeEvents = false;
        }
    }

    /**
     * Applique le thème choisi et recrée l'écran.
     *
     * POURQUOI recreate() EST INÉVITABLE ICI, UNE FOIS, ET PAS EN BOUCLE :
     * changer uiMode change les ressources résolues (@color/gps_bg vaut une
     * autre valeur). Les vues déjà créées gardent les anciennes. Il faut donc
     * reconstruire l'écran. On le fait UNE fois, ici, à la demande explicite
     * de l'utilisateur — et pas à chaque retour au premier plan, ce qui
     * ferait clignoter l'app en permanence.
     *
     * On ne recrée que si quelque chose a réellement changé : appuyer sur le
     * bouton déjà coché ne doit rien faire du tout.
     */
    private void applyTheme(ThemeMode.Mode m) {
        ThemeManager.write(this, m);
        boolean changed = ThemeManager.applyTo(this, m);
        if (changed) {
            recreate();
        } else {
            // Rien à forcer (mode SYSTÈME) ou déjà dans ce mode : on
            // resynchronise les boutons sur l'état réel, au cas où le clic
            // ait porté sur un bouton déjà coché.
            reflectTheme();
        }
    }

    /**
     * Applique un répertoire préréglé. Écrit TOUT DE SUITE — pas de bouton
     * « Enregistrer », par cohérence avec le reste de cet écran.
     */
    private void applyDestination(DestPresets.Preset preset) {
        // Une seule tentative, un seul message, un seul rafraîchissement.
        // Ma première version appelait Prefs.writeDestLabel DEUX fois (une
        // fois null puis une fois la valeur) et affichait deux Toasts
        // successifs : le second recouvrait le premier et l'utilisateur ne
        // voyait que le dernier mot. Le code court est ici le code juste.
        boolean ok = Destination.applyPreset(this, preset);
        if (ok) {
            // ID stable ET libellé : l'ID sert de clé, le libellé de repli
            // pour les dossiers arbitraires. Les deux, pas l'un ou l'autre.
            Prefs.writeDestPreset(this, preset.id);
            Prefs.writeDestLabel(this, preset.isAppPrivate() ? null : preset.label);
            Toast.makeText(this,
                    getString(R.string.dest_set_ok, Destination.humanPath(this)),
                    Toast.LENGTH_SHORT).show();
        } else {
            // Échec réel : stockage absent, ou fournisseur qui refuse
            // l'écriture. On le dit, et refreshDestination() remet les
            // boutons sur l'état RÉEL — laisser coché un réglage qui n'a
            // pas pris serait un mensonge de l'interface.
            Toast.makeText(this, R.string.dest_set_failed, Toast.LENGTH_LONG).show();
        }
        refreshDestination();
    }

    /**
     * Reflète la destination réelle dans l'écran.
     *
     * Point important : on ne se fie PAS au bouton coché, on relit l'état
     * réel. Si l'utilisateur a révoqué l'accès depuis les réglages Android
     * entre-temps, le bouton correspondant doit signaler une perte d'accès,
     * pas prétendre que tout va bien.
     */
    private void refreshDestination() {
        // GARDE DE RÉENTRANCE. setChecked() déclenche l'écouteur du groupe,
        // qui appelle applyDestination(), qui appelle refreshDestination()…
        // Une boucle qui se stabilise par chance sur trois boutons, mais qui
        // réécrirait les préférences à chaque rafraîchissement. Un booléen
        // suffit à la couper net.
        if (suppressDestEvents) return;
        suppressDestEvents = true;
        try {
            refreshDestinationInner();
        } finally {
            suppressDestEvents = false;
        }
    }

    private void refreshDestinationInner() {
        tvDestPath.setText(Destination.humanPath(this));

        boolean external = Destination.isExternalActive(this);
        boolean configured = Destination.treeUri(this) != null;

        if (!configured) {
            tvDestWarning.setText(R.string.dest_app_warning);
            rbDestApp.setChecked(true);
        } else if (!external) {
            tvDestWarning.setText(R.string.dest_lost);
        } else {
            tvDestWarning.setText("");
        }

        if (configured) {
            // On compare des IDENTIFIANTS, pas des libellés : un libellé est
            // traduit, donc instable entre deux langues. Comparer des textes
            // affichés pour retrouver un réglage est une faute classique.
            DestPresets.Preset active = DestPresets.byId(Prefs.readDestPreset(this));
            if (active == DestPresets.PRESETS[0]) {
                rbDestDownload.setChecked(true);
            } else if (active == DestPresets.PRESETS[1]) {
                rbDestDocuments.setChecked(true);
            } else if (active == DestPresets.PRESETS[2]) {
                rbDestApp.setChecked(true);
            } else {
                // Dossier choisi via le sélecteur : aucun bouton préréglé ne
                // le représente. On les décoche tous — le chemin affiché
                // au-dessus est la seule vérité.
                rgDest.clearCheck();
            // Un dossier choisi au sélecteur n'est représenté par aucun des
            // trois boutons. On laisse l'utilisateur SANS choix coché plutôt
            // que d'en cocher un faux — le chemin affiché au-dessus est la
            // vérité. clearCheck() ne déclenche pas l'écouteur (checkedId
            // vaut -1, ignoré par le switch), donc pas de boucle ici.
            // MAIS on doit alors empêcher refreshDestination() de recocher
            // « Dossier de l'app » au prochain passage : c'est le rôle de
            // configured, déjà pris en compte ci-dessus.
            }
        }
    }

    /** Nom lisible d'une URI de dossier : délègue à la logique pure testée. */
    private static String labelForUri(android.net.Uri uri) {
        try {
            return DestPresets.labelOf(
                    android.provider.DocumentsContract.getTreeDocumentId(uri));
        } catch (Exception e) {
            return "Dossier choisi";
        }
    }

    /**
     * Résumé de ce qui a été produit : combien de traces, combien de CSV,
     * quelle place, et le chemin exact. C'est la réponse à « où sont mes
     * données ? » — question légitime à laquelle une app doit répondre
     * elle-même, pas renvoyer vers ADB.
     */
    private void refreshDataSummary() {
        java.util.List<java.io.File> gpx = ExportHelper.listGpx(this);
        java.util.List<java.io.File> csv = ExportHelper.listCsv(this);
        long bytes = ExportHelper.totalBytes(this);

        if (gpx.isEmpty() && csv.isEmpty()) {
            tvDataSummary.setText(R.string.data_none);
        } else {
            tvDataSummary.setText(getString(R.string.data_summary,
                    gpx.size(), csv.size(), ExportHelper.formatSize(bytes)));
        }
        java.io.File dir = ExportHelper.dataDir(this);
        tvDataPath.setText(dir != null ? dir.getAbsolutePath() : "");
    }

    private void fillLabels() {
        rbContinue.setText(BackgroundPolicy.Policy.CONTINUE.label);
        rbKeepOn.setText(BackgroundPolicy.Policy.KEEP_SCREEN_ON.label);
        rbSuspend.setText(BackgroundPolicy.Policy.SUSPEND_ON_SCREEN_OFF.label);
        tvContinueDesc.setText(BackgroundPolicy.Policy.CONTINUE.summary);
        tvKeepOnDesc.setText(BackgroundPolicy.Policy.KEEP_SCREEN_ON.summary);
        tvSuspendDesc.setText(BackgroundPolicy.Policy.SUSPEND_ON_SCREEN_OFF.summary);

        tvGpx11Desc.setText(Prefs.gpxExplanation(GpxWriter.Version.V11));
        tvGpx10Desc.setText(Prefs.gpxExplanation(GpxWriter.Version.V10));
    }

    private void setChecked(BackgroundPolicy.Policy p) {
        switch (p) {
            case KEEP_SCREEN_ON:      rbKeepOn.setChecked(true); break;
            case SUSPEND_ON_SCREEN_OFF: rbSuspend.setChecked(true); break;
            case CONTINUE:
            default:                  rbContinue.setChecked(true); break;
        }
    }

    private void setGpxChecked(GpxWriter.Version v) {
        if (v == GpxWriter.Version.V10) rbGpx10.setChecked(true);
        else rbGpx11.setChecked(true);
    }

    private static BackgroundPolicy.Policy policyFromCheckedId(int id) {
        if (id == R.id.rbKeepOn) return BackgroundPolicy.Policy.KEEP_SCREEN_ON;
        if (id == R.id.rbSuspend) return BackgroundPolicy.Policy.SUSPEND_ON_SCREEN_OFF;
        if (id == R.id.rbContinue) return BackgroundPolicy.Policy.CONTINUE;
        return null;
    }

    private void applyPolicy(BackgroundPolicy.Policy p) {
        // Le service décide ; nous on transmet et on affiche une explication
        // honnête des conséquences, notamment pour l'économie de batterie.
        TrackingService.setPolicy(this, p);
        if (p == BackgroundPolicy.Policy.SUSPEND_ON_SCREEN_OFF) {
            tvWarnSegment.setText(R.string.warn_policy_suspend);
        } else if (p == BackgroundPolicy.Policy.KEEP_SCREEN_ON) {
            tvWarnSegment.setText(R.string.warn_policy_keep_on);
        } else {
            tvWarnSegment.setText(R.string.warn_policy_continue);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // MODE D'ENREGISTREMENT (v5.11)
    // ══════════════════════════════════════════════════════════════════
    //
    // Le sélecteur de mode et les deux spinners distance/temps partagent la
    // même vérité. Quand le mode est prédéfini, les deux spinners sont
    // GRISÉS (ils affichent les valeurs du mode, sans qu'on puisse les
    // changer) : autrement l'utilisateur croirait pouvoir régler la marche
    // à 500 m et s'étonnerait que rien ne change.

    private void setupMode() {
        final TrackMode.Mode[] modes = TrackMode.DISPLAY_ORDER;
        String[] labels = new String[modes.length];
        for (int i = 0; i < modes.length; i++) labels[i] = modeLabel(modes[i]);

        spTrackMode.setAdapter(simpleAdapter(labels));

        suppressSamplingEvents = true;
        try {
            int idx = 0;
            TrackMode.Mode current = Prefs.readTrackMode(this);
            for (int i = 0; i < modes.length; i++) if (modes[i] == current) idx = i;
            spTrackMode.setSelection(idx);
        } finally {
            suppressSamplingEvents = false;
        }

        cbPostProcess.setChecked(Prefs.readPostProcess(this));
        setupPpTolerance();

        spTrackMode.setOnItemSelectedListener(new SimpleItemSelectedListener() {
            @Override public void onItemSelected(int position) {
                if (suppressSamplingEvents) return;
                if (position >= 0 && position < modes.length) {
                    Prefs.writeTrackMode(SettingsActivity.this, modes[position]);
                    // En quittant « personnalisé », on garde les valeurs
                    // personnalisées en préférence : elles resserviront si
                    // l'utilisateur y revient. On ne les écrase pas.
                    applyModeVisibility();
                }
            }
        });

        applyModeVisibility();
    }

    private String modeLabel(TrackMode.Mode m) {
        switch (m) {
            case BIKE: return getString(R.string.mode_bike);
            case CAR:  return getString(R.string.mode_car);
            case CUSTOM: return getString(R.string.mode_custom);
            case WALK:
            default: return getString(R.string.mode_walk);
        }
    }

    private void setupPpTolerance() {
        double[] tol = TrackPostProcess.TOLERANCE_CHOICES;
        String[] labels = new String[tol.length];
        for (int i = 0; i < tol.length; i++) {
            labels[i] = (tol[i] == 0) ? getString(R.string.mode_pp_off)
                                      : (int) tol[i] + " m";
        }
        spPpTolerance.setAdapter(simpleAdapter(labels));

        suppressSamplingEvents = true;
        try {
            double cur = Prefs.readPpTolerance(this);
            int idx = 0;
            for (int i = 0; i < tol.length; i++) if (tol[i] == cur) idx = i;
            spPpTolerance.setSelection(idx);
        } finally {
            suppressSamplingEvents = false;
        }

        spPpTolerance.setOnItemSelectedListener(new SimpleItemSelectedListener() {
            @Override public void onItemSelected(int position) {
                if (suppressSamplingEvents) return;
                if (position >= 0 && position < TrackPostProcess.TOLERANCE_CHOICES.length) {
                    Prefs.writePpTolerance(SettingsActivity.this,
                            TrackPostProcess.TOLERANCE_CHOICES[position]);
                }
            }
        });

        cbPostProcess.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(
                            android.widget.CompoundButton b, boolean checked) {
                        Prefs.writePostProcess(SettingsActivity.this, checked);
                        applyPpVisibility();
                    }
                });
        applyPpVisibility();
    }

    /**
     * Met à jour la visibilité et le texte d'aide. Appelée à chaque
     * changement de mode, ET après le retour au premier plan (le mode a pu
     * changer ailleurs).
     */
    private void applyModeVisibility() {
        TrackMode.Mode m = Prefs.readTrackMode(this);
        TrackMode.Effective eff = TrackMode.resolve(m,
                Prefs.readMinDistanceM(this), Prefs.readMinTimeMs(this));
        boolean custom = (m == TrackMode.Mode.CUSTOM);

        // Les deux spinners n'ont de sens qu'en mode personnalisé.
        setEnabledDeep(spMinDistance, custom);
        setEnabledDeep(spMinTime, custom);
        tvSamplingDistance.setAlpha(custom ? 1f : 0.45f);
        tvSamplingTime.setAlpha(custom ? 1f : 0.45f);
        tvSamplingIntro.setAlpha(custom ? 1f : 0.45f);
        tvSamplingIntro.setText(custom ? R.string.settings_sampling_intro
                                       : R.string.mode_custom_hint);

        if (custom) {
            tvModeSummary.setText(getString(R.string.mode_summary,
                    getString(R.string.mode_custom),
                    formatMeters(eff.distanceM), formatSeconds(eff.timeMs)));
        } else {
            tvModeSummary.setText(getString(R.string.mode_summary,
                    modeLabel(m), formatMeters(eff.distanceM),
                    formatSeconds(eff.timeMs)));
        }
        tvModeAccuracy.setText(getString(R.string.mode_accuracy,
                formatMeters(Math.round(eff.accuracyLimitM))));
    }

    private void applyPpVisibility() {
        boolean on = Prefs.readPostProcess(this);
        spPpTolerance.setEnabled(on);
        spPpTolerance.setAlpha(on ? 1f : 0.45f);
        tvPpTolerance.setAlpha(on ? 1f : 0.45f);
    }


    private static String formatMeters(long m) {
        return (m >= 1000) ? (m / 1000) + " km" : m + " m";
    }

    private static String formatSeconds(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + " s";
        if (s % 60 == 0) return (s / 60) + " min";
        return (s / 60) + " min " + (s % 60) + " s";
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Le mode peut avoir changé pendant qu'on était en arrière-plan :
        // on resynchronise l'affichage, sans réécrire les préférences.
        if (spTrackMode != null) {
            TrackMode.Mode cur = Prefs.readTrackMode(this);
            int idx = 0;
            TrackMode.Mode[] modes = TrackMode.DISPLAY_ORDER;
            for (int i = 0; i < modes.length; i++) if (modes[i] == cur) idx = i;
            if (spTrackMode.getSelectedItemPosition() != idx) {
                suppressSamplingEvents = true;
                try { spTrackMode.setSelection(idx); }
                finally { suppressSamplingEvents = false; }
            }
            applyModeVisibility();
        }
    }

}
