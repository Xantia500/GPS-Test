package com.example.gpstest;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * Carte du ciel dessinée "à la main" avec Canvas.
 *
 * CONVENTIONS (identiques aux vrais récepteurs) :
 *   - Nord en haut, Est à droite, Sud en bas, Ouest à gauche
 *   - zénith (90° d'élévation) au CENTRE
 *   - horizon (0°) sur le BORD du cercle
 *
 * Un View custom, c'est :
 *   1) hériter de View
 *   2) redéfinir onDraw() pour peindre
 *   3) appeler invalidate() pour demander un redessin
 *
 * C'est de la "vue" pure : aucun XML de contenu, tout est dessiné au Canvas.
 * Pourquoi ? Parce qu'un cercle + 30 points, c'est plus simple en code qu'en
 * hiérarchie de vues. C'est le cas où le dessin manuel gagne, même en XML-based.
 */
public class SkyView extends View {

    private List<SatInfo> satellites;
    private boolean empty;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint satPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);

    public SkyView(Context context) { super(context); init(); }
    public SkyView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }
    public SkyView(Context context, @Nullable AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle); init();
    }

    /**
     * TEXTES RÉSOLUS UNE FOIS, PAS À CHAQUE IMAGE.
     *
     * getString() dans onDraw() allouerait quatre chaînes à chaque
     * rafraîchissement — et le ciel est redessiné plusieurs fois par
     * seconde. Les développeurs Android appellent ça « allocation dans la
     * boucle de dessin » : ça produit des GC pendant le rendu, donc des
     * micro-saccades visibles.
     *
     * On les résout donc une seule fois, dans init(). Le prix : le texte ne
     * suit pas un changement de langue à chaud — acceptable, un changement
     * de langue recrée l'Activity, donc la vue.
     *
     * Avant, ces lettres étaient écrites en dur (« N », « S », « E », « O ») :
     * non traduisibles, et les chaînes sky_* déclarées dans strings.xml
     * n'étaient utilisées nulle part. Du code mort déguisé en ressource.
     */
    private String labelN, labelS, labelE, labelW, labelEmpty;

    /**
     * RÉSOLUTION DES COULEURS, ET POURQUOI ELLE N'EST PAS DANS init().
     *
     * Un View custom ne peut pas référencer @color dans un layout : il peint
     * des pixels. Il lui faut donc des valeurs int, qu'on lit depuis les
     * ressources — et il faut les RELIRE quand le thème change.
     *
     * On redéfinit onConfigurationChanged() plus bas : c'est appelé quand
     * uiMode change, y compris quand c'est nous qui le forçons. Sans cette
     * relecture, changer de thème laisserait la carte du ciel dans les
     * couleurs de l'ancien mode jusqu'au prochain redémarrage.
     */
    private void applyColors() {
        android.content.Context c = getContext();
        gridPaint.setColor(ThemeManager.color(c, R.color.gps_green_dim, 0xFF2E7D32));
        textPaint.setColor(ThemeManager.color(c, R.color.gps_text_dim, 0xFF9E9E9E));
        // Couleurs de signal : identiques jour et nuit (ce sont des points
        // lumineux), mais lues depuis les ressources pour rester alignées
        // avec le reste de l'app si elles changent un jour.
        satStrong = ThemeManager.color(c, R.color.gps_signal, 0xFF00A83B);
        satMedium = ThemeManager.color(c, R.color.gps_warn,   0xFFE08A00);
        satWeak   = ThemeManager.color(c, R.color.gps_alert,  0xFFD32F2F);
        satLabel  = ThemeManager.color(c, R.color.gps_text_dim, 0xFF9E9E9E);
    }

    private int satStrong, satMedium, satWeak, satLabel;

    @Override
    protected void onConfigurationChanged(android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        applyColors();     // le thème a changé : on repeint
        invalidate();
    }

    private void init() {
        labelN = getContext().getString(R.string.sky_north);
        labelS = getContext().getString(R.string.sky_south);
        labelE = getContext().getString(R.string.sky_east);
        labelW = getContext().getString(R.string.sky_west);
        labelEmpty = getContext().getString(R.string.sky_empty);

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(2f);

        textPaint.setTextSize(28f);
        textPaint.setTextAlign(Paint.Align.CENTER);

        satPaint.setStyle(Paint.Style.FILL);

        applyColors();
    }

    public void setSatellites(List<SatInfo> list) {
        this.satellites = list;
        this.empty = (list == null || list.isEmpty());
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(cx, cy) - 22f;

        // --- Cercles d'élévation : 0° (horizon), 30°, 60° ---
        canvas.drawCircle(cx, cy, radius, gridPaint);
        canvas.drawCircle(cx, cy, radius * 2f / 3f, gridPaint);
        canvas.drawCircle(cx, cy, radius / 3f, gridPaint);

        // --- Croix cardinales ---
        canvas.drawLine(cx, cy - radius, cx, cy + radius, gridPaint);
        canvas.drawLine(cx - radius, cy, cx + radius, cy, gridPaint);

        // --- Lettres cardinales (résolues dans init(), voir plus haut) ---
        canvas.drawText(labelN, cx, cy - radius - 6f, textPaint);
        canvas.drawText(labelS, cx, cy + radius + 26f, textPaint);
        canvas.drawText(labelE, cx + radius + 16f, cy + 9f, textPaint);
        canvas.drawText(labelW, cx - radius - 16f, cy + 9f, textPaint);

        // Taille fixe des numéros : réglée UNE fois, pas à chaque satellite.
        textPaint.setTextSize(20f);

        if (empty || satellites == null) {
            // ATTENTION : on ne fait que changer la couleur du textPaint, on
            // ne la restaure pas — le prochain onDraw la relit via satLabel
            // pour les numéros de satellite. Voir plus bas.
            textPaint.setColor(satLabel);
            canvas.drawText(labelEmpty, cx, cy + radius * 0.55f, textPaint);
            return;
        }

        // --- Les satellites ---
        //
        // AUCUNE ALLOCATION DANS LA BOUCLE (corrigé v5.13).
        //
        // Avant, chaque satellite construsait un float[] (azElToXY) ET une
        // chaîne via String.format. À 24 satellites et 2 rafraîchissements
        // par seconde : ~48 tableaux + 48 chaînes par seconde, uniquement
        // pour dessiner. C'est du travail pour le ramasse-miettes au moment
        // précis où on veut que le dessin reste fluide.
        //
        // Les positions sont maintenant calculées en flottants locaux, et
        // le numéro du satellite est converti par Integer.toString (pas de
        // formateur, pas d'analyse de format).
        for (SatInfo sat : satellites) {
            float clampedEl = Math.max(0f, Math.min(90f, sat.elevation));
            float rr = (90f - clampedEl) / 90f * radius;
            double angle = Math.toRadians(sat.azimuth);
            float xy0 = cx + rr * (float) Math.sin(angle);
            float xy1 = cy - rr * (float) Math.cos(angle);
            float fraction = Utils.snrFraction(sat.cn0);

            // Taille proportionnelle au signal : 5 à 11 px de rayon
            float r = 5f + fraction * 6f;

            int color;
            if (fraction >= 0.6f)      color = satStrong;
            else if (fraction >= 0.35f) color = satMedium;
            else                        color = satWeak;

            satPaint.setColor(color);
            canvas.drawCircle(xy0, xy1, r, satPaint);

            // Numéro du satellite, dans la couleur du point.
            // setTextSize est HORS de la boucle : il ne change jamais.
            textPaint.setColor(color);
            canvas.drawText(Integer.toString(sat.svid),
                    xy0, xy1 - r - 5f, textPaint);
        }
    }
}
