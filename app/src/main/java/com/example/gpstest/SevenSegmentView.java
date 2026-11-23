package com.example.gpstest;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Affichage 7 segments dessiné au Canvas.
 *
 * C'est le "style" des vieux récepteurs GPS et de GPS Test Plus.
 * Vous comptez faire des dashboards avec Processing : ce View est le pendant
 * Android natif. Les deux peuvent cohabiter (Processing pour les graphiques
 * complexes, un View custom pour les chiffres).
 *
 * Chaque segment est un trait à bouts arrondis. Les segments éteints sont
 * dessinés en très sombre, comme sur un vrai afficheur LED.
 */
public class SevenSegmentView extends View {

    /** Ordre : A(haut) B(haut-droit) C(bas-droit) D(bas) E(bas-gauche) F(haut-gauche) G(milieu) */
    private static final int[][] DIGITS = {
            {1,1,1,1,1,1,0}, // 0
            {0,1,1,0,0,0,0}, // 1
            {1,1,0,1,1,0,1}, // 2
            {1,1,1,1,0,0,1}, // 3
            {0,1,1,0,0,1,1}, // 4
            {1,0,1,1,0,1,1}, // 5
            {1,0,1,1,1,1,1}, // 6
            {1,1,1,0,0,0,0}, // 7
            {1,1,1,1,1,1,1}, // 8
            {1,1,1,1,0,1,1}, // 9
    };

    private String text = "00:00:00";

    private final Paint onPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint offPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public SevenSegmentView(Context c) { super(c); init(); }
    public SevenSegmentView(Context c, @Nullable AttributeSet a) { super(c, a); init(); }
    public SevenSegmentView(Context c, @Nullable AttributeSet a, int s) { super(c, a, s); init(); }

    /**
     * RELECTURE DES COULEURS AU CHANGEMENT DE THÈME.
     *
     * Le point qui compte ici : les segments ÉTEINTS. Sur fond sombre ils
     * sont peints en #1B3320 (presque noir) — c'est ce qui donne l'aspect
     * « LED éteinte ». Sur fond clair, la même couleur serait invisible ET
     * sans contraste : l'afficheur paraîtrait cassé. On lit donc
     * @color/gps_seg_dim, qui vaut #C8D6CA en clair et #1B3320 en sombre.
     *
     * Le segment ALLUMÉ reste vert vif dans les deux modes : c'est un point
     * lumineux, il doit l'être autant au soleil que dans le noir.
     */
    private void applyColors() {
        android.content.Context c = getContext();
        int on = ThemeManager.color(c, R.color.gps_signal, 0xFF00A83B);
        onPaint.setColor(on);
        dotPaint.setColor(on);
        offPaint.setColor(ThemeManager.color(c, R.color.gps_seg_dim, 0xFFC8D6CA));
    }

    @Override
    protected void onConfigurationChanged(android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        applyColors();
        invalidate();
    }

    private void init() {
        onPaint.setStyle(Paint.Style.STROKE);
        onPaint.setStrokeCap(Paint.Cap.ROUND);

        offPaint.setStyle(Paint.Style.STROKE);
        offPaint.setStrokeCap(Paint.Cap.ROUND);

        // Les deux-points doivent être PLEINS : onPaint est en STROKE,
        // donc on ne peut pas la réutiliser pour drawCircle.
        dotPaint.setStyle(Paint.Style.FILL);

        applyColors();
    }

    public void setText(String t) {
        this.text = t;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int n = text.length();
        if (n == 0) return;

        float padding = 6f;
        float available = getWidth() - 2 * padding;
        float slot = available / n;

        float digitWidth = Math.min(slot * 0.62f, getHeight() * 0.55f);
        float digitHeight = getHeight() - 2 * padding * 1.5f;
        float stroke = Math.max(3f, digitHeight * 0.09f);

        onPaint.setStrokeWidth(stroke);
        offPaint.setStrokeWidth(stroke);

        float x = padding + (slot - digitWidth) / 2f;
        for (int i = 0; i < n; i++) {
            char ch = text.charAt(i);
            if (ch >= '0' && ch <= '9') {
                drawDigit(canvas, x, padding, digitWidth, digitHeight, DIGITS[ch - '0']);
            } else if (ch == ':') {
                drawColon(canvas, x + digitWidth / 2f, padding, digitHeight);
            }
            x += slot;
        }
    }

    private void drawDigit(Canvas canvas, float x, float y, float w, float h, int[] seg) {
        float t = Math.max(3f, h * 0.09f);
        float midY = y + h / 2f;
        float x2 = x + w;

        // A : haut
        line(canvas, x + t, y, x2 - t, y, seg[0]);
        // B : haut-droit
        line(canvas, x2, y + t, x2, midY - t, seg[1]);
        // C : bas-droit
        line(canvas, x2, midY + t, x2, y + h - t, seg[2]);
        // D : bas
        line(canvas, x + t, y + h, x2 - t, y + h, seg[3]);
        // E : bas-gauche
        line(canvas, x, midY + t, x, y + h - t, seg[4]);
        // F : haut-gauche
        line(canvas, x, y + t, x, midY - t, seg[5]);
        // G : milieu
        line(canvas, x + t, midY, x2 - t, midY, seg[6]);
    }

    private void line(Canvas c, float x1, float y1, float x2, float y2, int on) {
        c.drawLine(x1, y1, x2, y2, on == 1 ? onPaint : offPaint);
    }

    private void drawColon(Canvas canvas, float cx, float top, float h) {
        float r = Math.max(2.5f, h * 0.055f);
        canvas.drawCircle(cx, top + h * 0.33f, r, dotPaint);
        canvas.drawCircle(cx, top + h * 0.67f, r, dotPaint);
    }
}
