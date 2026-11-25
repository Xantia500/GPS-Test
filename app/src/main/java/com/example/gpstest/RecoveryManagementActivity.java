package com.example.gpstest;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Consultation des copies GPX récupérées et publiées.
 *
 * L'écran est strictement non destructif : il ne propose aucune suppression,
 * ne modifie aucun registre et ne relit pas spontanément les fournisseurs SAF.
 * Le statut SAF décrit la vérification faite au moment de la publication.
 */
public final class RecoveryManagementActivity extends AppCompatActivity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean loading = new AtomicBoolean(false);
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "recovery-retention-screen");
        thread.setDaemon(true);
        return thread;
    });

    private TextView tvSummary;
    private TextView tvStatus;
    private TextView tvEmpty;
    private ProgressBar progress;
    private LinearLayout rowsContainer;
    private Button btnRefresh;

    private static final class DisplayRow {
        final RecoveryRetentionPresentation.Row row;
        final boolean hasSafPermission;
        final boolean isCurrentDestination;

        DisplayRow(RecoveryRetentionPresentation.Row row, boolean permission,
                   boolean currentDestination) {
            this.row = row;
            hasSafPermission = permission;
            isCurrentDestination = currentDestination;
        }
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTo(this, ThemeManager.read(this));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recovery_management);
        EdgeToEdge.apply(this);

        tvSummary = findViewById(R.id.tvRetentionSummary);
        tvStatus = findViewById(R.id.tvRetentionStatus);
        tvEmpty = findViewById(R.id.tvRetentionEmpty);
        progress = findViewById(R.id.pbRetentionLoading);
        rowsContainer = findViewById(R.id.containerRetentionEntries);
        btnRefresh = findViewById(R.id.btnRefreshRetention);
        Button btnClose = findViewById(R.id.btnCloseRetention);

        btnRefresh.setOnClickListener(view -> refreshRegistry());
        btnClose.setOnClickListener(view -> finish());
    }

    @Override protected void onResume() {
        super.onResume();
        refreshRegistry();
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void refreshRegistry() {
        if (!loading.compareAndSet(false, true)) return;
        progress.setVisibility(View.VISIBLE);
        btnRefresh.setEnabled(false);
        tvStatus.setText(R.string.retention_loading);
        tvEmpty.setVisibility(View.GONE);
        io.execute(() -> {
            RecoveryRetentionRegistry registry;
            List<DisplayRow> displayRows = new ArrayList<>();
            RecoveryRetentionPolicy.Summary summary;
            String error = null;
            long now = System.currentTimeMillis();
            try {
                Context app = getApplicationContext();
                registry = new RecoveryRetentionStore(app).read();
                summary = RecoveryRetentionPolicy.summarize(registry, now);
                java.io.File directory = app.getExternalFilesDir(null);
                List<RecoveryRetentionPresentation.Row> rows =
                        RecoveryRetentionPresentation.buildRows(registry, directory, now);
                Uri activeTree = Destination.treeUri(app);
                String activeTreeText = activeTree == null ? "" : activeTree.toString();
                SafRecoveryAccess saf = new SafRecoveryAccess(app);
                for (RecoveryRetentionPresentation.Row row : rows) {
                    boolean permission = saf.hasReadWriteAccess(row.entry.treeUri);
                    boolean current = !activeTreeText.isEmpty()
                            && activeTreeText.equals(row.entry.treeUri);
                    displayRows.add(new DisplayRow(row, permission, current));
                }
            } catch (Exception failure) {
                summary = null;
                error = failure.getClass().getSimpleName()
                        + (failure.getMessage() == null ? "" : " : " + failure.getMessage());
            }
            final RecoveryRetentionPolicy.Summary resultSummary = summary;
            final String resultError = error;
            final List<DisplayRow> resultRows = displayRows;
            main.post(() -> {
                loading.set(false);
                if (isFinishing() || isDestroyed()) return;
                progress.setVisibility(View.GONE);
                btnRefresh.setEnabled(true);
                if (resultError != null || resultSummary == null) {
                    tvStatus.setText(getString(R.string.retention_registry_error, resultError));
                    tvSummary.setText(R.string.retention_no_summary_on_error);
                    tvEmpty.setVisibility(View.GONE);
                    rowsContainer.removeAllViews();
                    return;
                }
                renderSummary(resultSummary, resultRows);
            });
        });
    }

    private void renderSummary(RecoveryRetentionPolicy.Summary summary,
                               List<DisplayRow> rows) {
        int localMissing = 0;
        int permissionMissing = 0;
        for (DisplayRow item : rows) {
            if (!item.row.isLocalCopyConsistentByLength()) localMissing++;
            if (!item.hasSafPermission) permissionMissing++;
        }

        StringBuilder text = new StringBuilder();
        text.append(getString(R.string.retention_summary_counts,
                summary.trackedCopies, summary.stillRetained, summary.readyForReview));
        text.append('\n').append(getString(R.string.retention_summary_size,
                formatBytes(summary.trackedBytes)));
        if (summary.softQuotaExceeded) {
            text.append('\n').append(getString(R.string.retention_quota_warning,
                    formatBytes(RecoveryRetentionPolicy.SOFT_QUOTA_BYTES)));
        }
        if (localMissing > 0) {
            text.append('\n').append(getString(R.string.retention_local_attention, localMissing));
        }
        if (permissionMissing > 0) {
            text.append('\n').append(getString(R.string.retention_permission_attention,
                    permissionMissing));
        }
        tvSummary.setText(text.toString());
        tvStatus.setText(R.string.retention_registry_read_ok);
        rowsContainer.removeAllViews();
        if (rows.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            return;
        }
        tvEmpty.setVisibility(View.GONE);
        for (DisplayRow display : rows) rowsContainer.addView(createEntryCard(display));
    }

    private View createEntryCard(DisplayRow display) {
        RecoveryRetentionPresentation.Row row = display.row;
        RecoveryRetentionRegistry.Entry entry = row.entry;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        card.setPadding(pad, pad, pad, pad);
        GradientDrawable background = new GradientDrawable();
        background.setColor(color(R.color.gps_card));
        background.setCornerRadius(dp(8));
        background.setStroke(dp(1), color(R.color.gps_bg));
        card.setBackground(background);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(dp(10), 0, dp(10), dp(10));
        card.setLayoutParams(cardParams);

        addLine(card, entry.displayName, 16, color(R.color.gps_green), true, false);
        addLine(card, getString(R.string.retention_local_copy, entry.recoveredName),
                12, color(R.color.gps_text), true, false);
        addLine(card, getString(R.string.retention_source_file, entry.sourceName),
                11, color(R.color.gps_text_dim), false, false);
        addLine(card, getString(R.string.retention_session_segment,
                        entry.sessionId, entry.segmentGeneration),
                10, color(R.color.gps_text_dim), false, true);
        addLine(card, getString(R.string.retention_published_at,
                        formatDateTime(entry.publishedAtMs), ageText(row.ageDays)),
                12, color(R.color.gps_text), false, false);
        addLine(card, retentionDeadlineText(row), 12,
                row.isReadyForExplicitReview() ? color(R.color.gps_amber) : color(R.color.gps_text),
                true, false);
        addLine(card, getString(R.string.retention_file_size, formatBytes(entry.recoveredLengthBytes)),
                12, color(R.color.gps_text), false, false);
        addLine(card, localCopyText(row.localCopyState), 12,
                row.isLocalCopyConsistentByLength() ? color(R.color.gps_green) : color(R.color.gps_amber),
                true, false);
        addLine(card, getString(R.string.retention_saf_verification,
                        getString(display.hasSafPermission ? R.string.retention_permission_present
                                : R.string.retention_permission_missing)),
                12, display.hasSafPermission ? color(R.color.gps_green) : color(R.color.gps_amber),
                false, false);
        addLine(card, getString(display.isCurrentDestination
                ? R.string.retention_destination_current
                : R.string.retention_destination_historical),
                11, color(R.color.gps_text_dim), false, false);
        addLine(card, getString(R.string.retention_sha256, entry.sha256),
                10, color(R.color.gps_text_dim), false, true);
        addLine(card, getString(R.string.retention_tree_uri, entry.treeUri),
                10, color(R.color.gps_text_dim), false, true);
        addLine(card, getString(R.string.retention_document_uri, entry.documentUri),
                10, color(R.color.gps_text_dim), false, true);
        addLine(card, getString(R.string.retention_publication_id, entry.publicationId),
                9, color(R.color.gps_text_dim), false, true);
        return card;
    }

    private String retentionDeadlineText(RecoveryRetentionPresentation.Row row) {
        if (row.retentionDecision == RecoveryRetentionPolicy.Decision.READY_FOR_EXPLICIT_REVIEW) {
            return getString(R.string.retention_review_due, formatDate(row.reviewAfterMs));
        }
        if (row.retentionDecision == RecoveryRetentionPolicy.Decision.CLOCK_MOVED_BACKWARDS) {
            return getString(R.string.retention_clock_warning);
        }
        if (row.retentionDecision == RecoveryRetentionPolicy.Decision.INVALID_ENTRY
                || row.reviewAfterMs <= 0L) {
            return getString(R.string.retention_deadline_invalid);
        }
        return getString(R.string.retention_review_after,
                formatDate(row.reviewAfterMs), row.daysUntilReview);
    }

    private String localCopyText(RecoveryRetentionPresentation.LocalCopyState state) {
        switch (state) {
            case PRESENT_LENGTH_MATCHES: return getString(R.string.retention_local_ok);
            case MISSING: return getString(R.string.retention_local_missing);
            case NOT_A_REGULAR_FILE: return getString(R.string.retention_local_not_file);
            case NOT_READABLE: return getString(R.string.retention_local_not_readable);
            case LENGTH_MISMATCH: return getString(R.string.retention_local_length_mismatch);
            case OUTSIDE_RECOVERY_DIRECTORY: return getString(R.string.retention_local_outside);
            case DIRECTORY_UNAVAILABLE: return getString(R.string.retention_local_directory_unavailable);
            case CHECK_FAILED:
            default: return getString(R.string.retention_local_check_failed);
        }
    }

    private String ageText(long days) {
        if (days < 0L) return getString(R.string.retention_age_unknown);
        return getResources().getQuantityString(R.plurals.retention_age_days, (int) Math.min(days, Integer.MAX_VALUE), days);
    }

    private void addLine(LinearLayout parent, String value, int sp, int textColor,
                         boolean bold, boolean selectable) {
        TextView line = new TextView(this);
        line.setText(value);
        line.setTextSize(sp);
        line.setTextColor(textColor);
        line.setPadding(0, dp(3), 0, dp(3));
        if (bold) line.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        if (selectable) {
            line.setTextIsSelectable(true);
            line.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY);
        }
        parent.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private String formatDateTime(long timeMs) {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT,
                Locale.getDefault()).format(new Date(timeMs));
    }

    private String formatDate(long timeMs) {
        return DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
                .format(new Date(timeMs));
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " o";
        final String[] units = {"Kio", "Mio", "Gio", "Tio"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024.0 && unit < units.length - 1);
        return String.format(Locale.getDefault(), "%.1f %s", value, units[unit]);
    }

    private int color(int resource) { return getResources().getColor(resource, getTheme()); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
