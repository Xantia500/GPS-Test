package com.example.gpstest;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Écran Expert GNSS.
 *
 * Important : cette Activity ne s'abonne plus directement aux capteurs.
 * Les abonnements GNSS/NMEA et l'enregistrement CSV appartiennent à
 * TrackingService. Quitter cet écran ne ferme donc pas un fichier en cours.
 */
public class ExpertActivity extends AppCompatActivity {

    private TextView tvHeader;
    private TextView tvMeasurements;
    private TextView tvNmea;
    private TextView tvClock7SegLabel;
    private SevenSegmentView clock7seg;
    private Button btnLog;
    private Button btnClear;
    private Button btnSave;
    private Button btnNmeaClear;
    private CheckBox cbCsvTime;
    private CheckBox cbCsvSignal;
    private CheckBox cbCsvDoppler;
    private CheckBox cbCsvPhase;
    private CheckBox cbCsvNmea;
    private CheckBox cbFullTracking;

    private final TrackingState state = TrackingState.get();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final TrackingState.Listener stateListener = this::renderState;

    private final Runnable clockTicker = new Runnable() {
        @Override public void run() {
            if (clock7seg != null && !isFinishing() && !isDestroyed()) {
                SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss", Locale.FRANCE);
                format.setTimeZone(TimeZone.getDefault());
                clock7seg.setText(format.format(new Date()));
                ui.postDelayed(this, 1000L);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTo(this, ThemeManager.read(this));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_expert);
        EdgeToEdge.apply(this);

        tvHeader = findViewById(R.id.tvExpertHeader);
        tvMeasurements = findViewById(R.id.tvMeasurements);
        tvNmea = findViewById(R.id.tvNmea);
        tvClock7SegLabel = findViewById(R.id.tvClock7Seg);
        clock7seg = findViewById(R.id.clock7seg);
        btnLog = findViewById(R.id.btnLog);
        btnClear = findViewById(R.id.btnClear);
        btnSave = findViewById(R.id.btnSave);
        btnNmeaClear = findViewById(R.id.btnNmeaClear);
        cbCsvTime = findViewById(R.id.cbCsvTime);
        cbCsvSignal = findViewById(R.id.cbCsvSignal);
        cbCsvDoppler = findViewById(R.id.cbCsvDoppler);
        cbCsvPhase = findViewById(R.id.cbCsvPhase);
        cbCsvNmea = findViewById(R.id.cbCsvNmea);
        cbFullTracking = findViewById(R.id.cbFullTracking);

        tvMeasurements.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        tvNmea.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        tvClock7SegLabel.setText("Horloge 7 segments — " + TimeZone.getDefault().getID());

        // Les options sont lues depuis le même fichier de préférences que le
        // service. Elles sont figées pour chaque nouveau fichier CSV.
        cbCsvTime.setChecked(Prefs.readRawCsvTime(this));
        cbCsvSignal.setChecked(Prefs.readRawCsvSignal(this));
        cbCsvDoppler.setChecked(Prefs.readRawCsvDoppler(this));
        cbCsvPhase.setChecked(Prefs.readRawCsvPhase(this));
        cbCsvNmea.setChecked(Prefs.readRawCsvNmea(this));
        cbFullTracking.setChecked(Prefs.readRawFullTracking(this));
        cbFullTracking.setEnabled(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S);
        cbCsvTime.setOnCheckedChangeListener((button, checked) -> Prefs.writeRawCsvTime(this, checked));
        cbCsvSignal.setOnCheckedChangeListener((button, checked) -> Prefs.writeRawCsvSignal(this, checked));
        cbCsvDoppler.setOnCheckedChangeListener((button, checked) -> Prefs.writeRawCsvDoppler(this, checked));
        cbCsvPhase.setOnCheckedChangeListener((button, checked) -> Prefs.writeRawCsvPhase(this, checked));
        cbCsvNmea.setOnCheckedChangeListener((button, checked) -> Prefs.writeRawCsvNmea(this, checked));
        cbFullTracking.setOnCheckedChangeListener((button, checked) -> {
            Prefs.writeRawFullTracking(this, checked);
            TrackingService.refreshRawMeasurementsRequest(this);
            renderState();
        });

        btnLog.setOnClickListener(v -> toggleRawLogging());
        btnClear.setOnClickListener(v -> {
            state.setRawMeasurementsPreview("");
            renderState();
        });
        btnSave.setOnClickListener(v -> shareFinalizedCsv());
        btnNmeaClear.setOnClickListener(v -> {
            state.clearExpertNmeaLines();
            renderState();
        });

        renderState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        state.addListener(stateListener);
        TrackingService.setExpertPageVisible(true);
        ui.removeCallbacks(clockTicker);
        ui.post(clockTicker);
        renderState();
    }

    @Override
    protected void onPause() {
        // Ne pas arrêter le CSV ici : l'écran Expert n'est plus propriétaire
        // du flux. Android peut suspendre la livraison des mesures brutes en
        // arrière-plan, mais le service conserve le journal et ses compteurs.
        ui.removeCallbacks(clockTicker);
        TrackingService.setExpertPageVisible(false);
        state.removeListener(stateListener);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(clockTicker);
        state.removeListener(stateListener);
        super.onDestroy();
    }

    private void toggleRawLogging() {
        if (state.isRawCsvRequested()) {
            TrackingService.stopRawMeasurementsLogging(this);
            return;
        }
        if (!state.isUserWantsTracking()) {
            Toast.makeText(this,
                    "Démarre d'abord le suivi GPS depuis l'écran principal.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!TrackingService.startRawMeasurementsLogging(this)) {
            Toast.makeText(this, "Impossible de demander le démarrage du journal GNSS brut.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void renderState() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            ui.post(this::renderState);
            return;
        }
        if (isFinishing() || isDestroyed()) return;

        boolean tracking = state.isUserWantsTracking();
        boolean logging = state.isRawCsvRequested();
        boolean supportKnown = state.isRawMeasurementsSupportKnown();
        boolean supported = state.isRawMeasurementsSupported();

        StringBuilder header = new StringBuilder();
        header.append(tracking ? "Suivi GNSS : actif" : "Suivi GNSS : inactif");
        if (state.isSuspended()) header.append(" · suspendu");
        header.append('\n');
        if (supportKnown) {
            header.append(supported ? "Mesures brutes : support annoncé par le chipset"
                    : "Mesures brutes : non supportées par le chipset");
        } else {
            header.append("Support des mesures brutes : inconnu (essai d'abonnement à la demande)");
        }
        header.append("\nNMEA et mesures brutes : Android les délivre uniquement lorsque l'application est au premier plan.");
        long nmeaNow = android.os.SystemClock.elapsedRealtime();
        String gnsSummary = state.getFreshNmeaGnsSummary(nmeaNow, 10_000L);
        String gstSummary = state.getFreshNmeaGstSummary(nmeaNow, 10_000L);
        if (gnsSummary != null) header.append("\n").append(gnsSummary);
        if (gstSummary != null) header.append("\n").append(gstSummary);
        tvHeader.setText(header.toString());

        btnLog.setText(logging ? "Arrêter CSV" : getString(R.string.btn_log_start));
        btnLog.setEnabled(logging || !state.isSuspended());
        cbCsvTime.setEnabled(!logging);
        cbCsvSignal.setEnabled(!logging);
        cbCsvDoppler.setEnabled(!logging);
        cbCsvPhase.setEnabled(!logging);
        cbCsvNmea.setEnabled(!logging);
        cbFullTracking.setEnabled(!logging
                && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S);

        StringBuilder details = new StringBuilder();
        details.append("État : ").append(state.getRawCsvStatus()).append('\n');
        if (state.isRawMeasurementsSubscribed()) {
            details.append(logging
                    ? "Abonnement mesures : inscrit pour le CSV (sans garantie de livraison en arrière-plan)\n"
                    : "Abonnement mesures : aperçu en direct, non enregistré en CSV\n");
        } else {
            details.append("Abonnement mesures : inactif\n");
        }
        details.append(String.format(Locale.FRANCE,
                "Événements de journal reçus (mesures + NMEA) : %d\nLignes confiées au worker : %d\nLignes en attente : %d\nLignes sérialisées : %d\nLignes perdues : %d\n",
                state.getRawCsvEventsReceived(), state.getRawCsvRowsQueued(),
                state.getRawCsvRowsPending(), state.getRawCsvRowsWritten(),
                state.getRawCsvRowsDropped()));
        details.append("\nHorodatages de réception : UTC (ms Unix) et temps monotone (ns depuis le démarrage).\n");
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            details.append("Full tracking demandé : ")
                    .append(Prefs.readRawFullTracking(this)
                            ? "oui (duty-cycling réduit ; consommation supérieure ; peut affecter les autres clients GNSS)\n"
                            : "non (mode économe du chipset)\n");
        } else {
            details.append("Full tracking : disponible à partir d'Android 12 (API 31).\n");
        }
        String lastEvent = state.getRawMeasurementsLastCallbackStatus();
        if (lastEvent != null && !lastEvent.isEmpty()) details.append(lastEvent).append('\n');
        details.append("Pseudodistance : non calculée ; elle n'est pas fournie directement par GnssMeasurement.\n");
        String durabilityWarning = state.getRawCsvDurabilityWarning();
        if (durabilityWarning != null && !durabilityWarning.isEmpty()) {
            details.append("Avertissement stockage : ").append(durabilityWarning).append('\n');
        }
        String path = state.getRawCsvPath();
        if (path != null && !path.trim().isEmpty()) details.append("Fichier : ").append(path).append('\n');
        String error = state.getRawCsvError();
        if (error != null && !error.trim().isEmpty()) details.append("Erreur : ").append(error).append('\n');
        tvMeasurements.setText(details + "\n" + state.getRawMeasurementsPreview());

        String nmea = state.getExpertNmeaText();
        tvNmea.setText(nmea.isEmpty() ? "Aucune trame NMEA reçue dans cette session." : nmea);

        boolean canShare = false;
        if (!logging && path != null && !path.trim().isEmpty()) {
            try { canShare = new File(path).isFile(); } catch (RuntimeException ignored) { }
        }
        btnSave.setEnabled(canShare);
    }

    private void shareFinalizedCsv() {
        String path = state.getRawCsvPath();
        if (path == null || path.trim().isEmpty() || state.isRawCsvRequested()) {
            Toast.makeText(this, "Arrête d'abord le CSV et attends sa finalisation.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        File file = new File(path);
        if (!file.isFile()) {
            Toast.makeText(this, "Le fichier CSV n'est plus disponible.", Toast.LENGTH_LONG).show();
            return;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.expert_csv_saved_title)
                .setMessage(getString(R.string.expert_csv_saved_message,
                        file.getAbsolutePath(), file.length()))
                .setPositiveButton("Partager…", (dialog, which) -> shareCsv(file))
                .setNegativeButton(R.string.expert_csv_ok, null)
                .show();
    }

    private void shareCsv(File file) {
        try {
            android.net.Uri uri = FileProvider.getUriForFile(
                    this, getPackageName() + ".files", file);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/csv");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Partager le journal GNSS CSV"));
        } catch (Exception error) {
            Toast.makeText(this, "Partage impossible : " + error.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }
}
