package com.example.gpstest;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * CADRAN DE BOUSSOLE dessiné au Canvas.
 *
 * ══════════════════════════════════════════════════════════════════════
 * CE QUI TOURNE : LE CADRAN, PAS L'AIGUILLE
 * ══════════════════════════════════════════════════════════════════════
 *
 * Une boussole physique fait tourner une aiguille sur un cadran fixe, parce
 * que le cadran est solidaire du boîtier. Une boussole logicielle n'a pas de
 * boîtier : elle est tenue par l'utilisateur, et ce qui doit rester lisible,
 * c'est le CAP — c'est-à-dire l'angle entre l'appareil et le nord.
 *
 * On fait donc tourner le CADRAN de -cap : à cap 0, le N est en haut ; à
 * cap 90, le N part à gauche et le E vient en haut. C'est le comportement
 * des applications de randonnée, et c'est le plus lisible en marche — on
 * tient le téléphone devant soi et la direction vers laquelle on va est
 * toujours en haut.
 *
 * Le repère fixe est un triangle en haut du cercle : il marque la direction
 * de visée (l'axe de l'appareil).
 *
 * ══════════════════════════════════════════════════════════════════════
 * AUCUNE ALLOCATION DANS onDraw
 * ══════════════════════════════════════════════════════════════════════
 *
 * Même règle que SkyView (voir le commentaire de v5.13) : onDraw est appelé
 * à chaque image, et une allocation par image finit en saccades et en
 * passage du ramasse-miettes pendant qu'on marche. Tous les objets Paint,
 * Path et RectF sont créés une fois dans init() ; onDraw ne fait que les
 * reconfigurer. Les nombres sont formatés par Compass, qui n'alloue pas
 * dans son chemin chaud (formatDegrees est appelé une fois par image, pas
 * par graduation).
 */
public class CompassView extends View {

    /** Cap affiché, en degrés dans [0, 360). NaN = pas d'azimut. */
    private float heading = Float.NaN;

    /** Nord vrai appliqué (true) ou magnétique (false) — change l'étiquette. */
    private boolean trueNorth = false;

    /** Vrai si le nord vrai était DEMANDÉ mais sans déclinaison connue. */
    private boolean declinationUnknown = false;

    // ---- objets réutilisés (jamais réalloués dans onDraw) ----
    private final Paint ringPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hubPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint discFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect     = new RectF();
    /**
     * Objet de métriques réutilisé.
     *
     * Paint.getFontMetrics() sans argument alloue un FontMetrics à CHAQUE
     * appel — deux allocations par image si on s'en sert pour centrer le
     * cap. La surcharge qui accepte un objet fourni n'alloue rien : c'est
     * celle qu'on utilise, avec cet objet unique.
     */
    private final Paint.FontMetrics fm = new Paint.FontMetrics();
    private final Path  needlePath  = new Path();
    private final Path  pointerPath = new Path();

    private int colorAccent;
    private int colorText;
    private int colorDim;
    /**
     * Couleurs utilisées DANS onDraw, mémorisées à l'initialisation.
     *
     * ThemeManager.color() interroge les ressources à chaque appel et peut
     * lever (il a un try/catch). L'appeler depuis onDraw, c'est payer une
     * recherche de ressource à chaque image. Ce n'est pas un détail : c'est
     * exactement le défaut corrigé dans SkyView en v5.13, et je l'ai
     * reproduit ici avant de le voir en relisant.
     */
    private int colorNeedle;
    private int colorWarn;
    /** Fond du disque central : la couleur de l'écran, pour l'isoler. */
    private int colorDisc;

    public CompassView(Context c) { super(c); init(c); }
    public CompassView(Context c, @Nullable AttributeSet a) { super(c, a); init(c); }
    public CompassView(Context c, @Nullable AttributeSet a, int s) { super(c, a, s); init(c); }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private void init(Context c) {
        colorAccent = ThemeManager.color(c, R.color.gps_green,  0xFF2E7D32);
        colorText   = ThemeManager.color(c, R.color.gps_text,   0xFF14261A);
        colorDim    = ThemeManager.color(c, R.color.gps_text_dim, 0xFF5A6B5F);
        colorNeedle = ThemeManager.color(c, R.color.gps_red,    0xFFC62828);
        colorWarn   = ThemeManager.color(c, R.color.gps_amber,  0xFFB26A00);
        colorDisc   = ThemeManager.color(c, R.color.gps_bg,     0xFFF7FAF7);

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(1.5f));
        ringPaint.setColor(colorDim);

        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setColor(colorDim);

        cardPaint.setStyle(Paint.Style.FILL);
        cardPaint.setTextAlign(Paint.Align.CENTER);
        cardPaint.setFakeBoldText(true);
        cardPaint.setColor(colorText);

        needlePaint.setStyle(Paint.Style.FILL);
        needlePaint.setColor(colorAccent);

        hubPaint.setStyle(Paint.Style.FILL);
        hubPaint.setColor(colorText);

        discFillPaint.setStyle(Paint.Style.FILL);
        discFillPaint.setColor(colorDisc);

        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(colorText);
        textPaint.setFakeBoldText(true);
        textPaint.setTypeface(android.graphics.Typeface.MONOSPACE);

        labelPaint.setStyle(Paint.Style.FILL);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setColor(colorDim);
    }

    // ------------------------------------------------------------------
    // API
    // ------------------------------------------------------------------

    /**
     * Fixe le cap à dessiner.
     *
     * On NE redessine que si le cap a bougé de plus de 0,4°. Le capteur
     * tire à ~30 Hz et la valeur oscille de quelques dixièmes de degré même
     * à l'arrêt : redessiner pour un demi-degré ferait tourner le cadran
     * sans arrêt et consommerait la batterie pour rien. En dessous du seuil,
     * l'image précédente est toujours exacte à l'écran.
     */
    public void setHeading(float deg, boolean trueNorthApplied) {
        float n = Compass.normalize(deg);
        boolean changed = Float.isNaN(n) != Float.isNaN(heading)
                || (!Float.isNaN(n) && !Float.isNaN(heading)
                    && Math.abs(Compass.shortestDelta(heading, n)) >= 0.4f);
        boolean northChanged = (trueNorthApplied != trueNorth);
        heading = n;
        trueNorth = trueNorthApplied;
        if (changed || northChanged) invalidate();
    }

    /** Signale que le nord vrai est demandé mais que la déclinaison manque. */
    public void setDeclinationUnknown(boolean unknown) {
        if (declinationUnknown != unknown) {
            declinationUnknown = unknown;
            invalidate();
        }
    }

    public boolean hasHeading() { return !Float.isNaN(heading); }

    // ------------------------------------------------------------------
    // Mesure
    // ------------------------------------------------------------------

    /**
     * Mesure : un carré, borné.
     *
     * LE PIÈGE DU SCROLLVIEW. La vue est déclarée avec
     * layout_height="wrap_content" dans un ScrollView. Android traduit cela
     * en mode AT_MOST avec une borne égale à… la hauteur de l'ÉCRAN, parce
     * que dans un ScrollView rien n'est jamais « trop grand ».
     *
     * Mesurer « le plus petit des deux côtés » donnerait donc, en paysage,
     * min(915 dp de large, 411 dp de haut) = 411 dp : un cadran qui occupe
     * tout l'écran et repousse le reste du contenu hors de vue.
     *
     * On borne donc explicitement entre 160 dp (en dessous, les lettres ne
     * sont plus lisibles) et 260 dp (au-delà, le cadran domine la page sans
     * apporter d'information supplémentaire).
     */
    private static final float MIN_DP = 160f;
    private static final float MAX_DP = 260f;

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        int modeW = MeasureSpec.getMode(widthSpec);
        int modeH = MeasureSpec.getMode(heightSpec);

        int available;
        if (modeW == MeasureSpec.UNSPECIFIED && modeH == MeasureSpec.UNSPECIFIED) {
            available = (int) dp(MAX_DP);
        } else if (modeW == MeasureSpec.UNSPECIFIED) {
            available = h;
        } else if (modeH == MeasureSpec.UNSPECIFIED) {
            available = w;
        } else {
            available = Math.min(w, h);
        }

        int size = Math.max((int) dp(MIN_DP), Math.min(available, (int) dp(MAX_DP)));
        setMeasuredDimension(size, size);
    }

    // ------------------------------------------------------------------
    // Dessin
    // ------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;

        // Rayon du cercle de graduation, avec une marge pour le repère fixe
        // et l'étiquette du bas.
        float margin = dp(16);
        float radius = Math.min(w, h) / 2f - margin;
        if (radius < dp(20)) return;      // vue écrasée : rien de lisible

        // ---- étiquette du nord de référence, en haut à gauche ----
        labelPaint.setTextSize(dp(9));
        canvas.drawText(trueNorth ? "NORD VRAI" : "NORD MAGN.",
                cx, dp(11), labelPaint);

        // ---- cercle ----
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius);
        canvas.drawCircle(cx, cy, radius, ringPaint);

        // ---- CADRAN TOURNANT + REPÈRE FIXE ----
        //
        // On tourne tout le disque de -cap : à cap 90 (on regarde l'est),
        // le N part à gauche et le E vient en haut. Le triangle en haut du
        // cercle est FIXE : il marque la direction de visée (l'axe de
        // l'appareil), et c'est toujours la graduation qui passe dessous
        // qui donne le cap — exactement comme sur une boussole de poignet
        // vue du dessus, ou sur un compas de marine.
        //
        // Les lettres sont dessinées DANS la rotation : elles tournent avec
        // le cadran, comme sur un instrument réel. C'est le compromis
        // classiquement retenu : des lettres toujours droites obligeraient
        // à les replacer une par une à chaque image, et donneraient un
        // cadran « collé » qui ne tourne pas.
        float rot = Float.isNaN(heading) ? 0f : Compass.normalize(-heading);
        canvas.save();
        canvas.rotate(rot, cx, cy);

        // 72 graduations tous les 5°. Les principales (45°) sont plus
        // longues ; les quatre cardinales portent leur lettre.
        for (int i = 0; i < 72; i++) {
            float deg = i * 5f;
            boolean major = (i % 9 == 0);        // tous les 45°
            boolean cardinal = (i % 18 == 0);    // tous les 90°
            float len = cardinal ? dp(9) : major ? dp(6) : dp(3);
            tickPaint.setStrokeWidth(cardinal ? dp(2f) : dp(1f));
            tickPaint.setColor(cardinal ? colorText : colorDim);
            drawTick(canvas, cx, cy, radius, deg, len);
        }

        // Lettres cardinales, DROITES dans le repère du cadran (donc
        // tournées avec lui à l'écran), mais non renversées : on les
        // dessine à leur place sur le cercle sans rotation propre. Une
        // lettre à 180° apparaît donc la tête en bas quand le N est en
        // bas — c'est ce que fait un vrai cadran, et c'est lisible parce
        // que le repère fixe donne l'orientation.
        drawCardinal(canvas, cx, cy, radius, 0f,   "N", true);
        drawCardinal(canvas, cx, cy, radius, 90f,  "E", false);
        drawCardinal(canvas, cx, cy, radius, 180f, "S", false);
        drawCardinal(canvas, cx, cy, radius, 270f, "O", false);

        canvas.restore();

        // ---- repère fixe : direction de visée (triangle en haut) ----
        pointerPath.reset();
        pointerPath.moveTo(cx, cy - radius + dp(15));          // pointe vers le centre
        pointerPath.lineTo(cx - dp(5), cy - radius - dp(1));
        pointerPath.lineTo(cx + dp(5), cy - radius - dp(1));
        pointerPath.close();
        canvas.drawPath(pointerPath, needlePaint);

        // ---- aiguille du nord : elle pointe vers le N DU CADRAN, donc
        //      tourne avec lui (elle est dessinée dans la même rotation) ----
        if (!Float.isNaN(heading)) {
            canvas.save();
            canvas.rotate(rot, cx, cy);
            drawNeedle(canvas, cx, cy, radius);
            canvas.restore();
        } else {
            // Pas d'azimut : un point d'interrogation plutôt qu'une
            // aiguille figée au nord, qui serait une mesure inventée.
            textPaint.setColor(colorDim);
            textPaint.setTextSize(dp(28));
            canvas.drawText("?", cx, cy + dp(10), textPaint);
        }

        // ---- moyeu central ----
        canvas.drawCircle(cx, cy, dp(3.5f), hubPaint);

        // ---- cap lisible, AU CENTRE, dans un disque opaque ----
        //
        // Le disque n'est pas décoratif : il isole le chiffre du cadran
        // tournant qui passe derrière. Sans lui, une graduation ou une
        // lettre vient couper le « 045° » au moment où on en a le plus
        // besoin. C'est aussi ce que fait n'importe quel instrument à
        // afficheur numérique central.
        if (!Float.isNaN(heading)) {
            float discR = radius * 0.44f;
            canvas.drawCircle(cx, cy, discR, discFillPaint);
            // Liseré : sans lui, le disque se confond avec le fond quand le
            // cadran passe dessous. Une ligne fine suffit à le détacher.
            canvas.drawCircle(cx, cy, discR, ringPaint);

            textPaint.setTextAlign(Paint.Align.CENTER);

            // ── LE CHIFFRE, CENTRÉ PAR MESURE ET NON PAR ESTIMATION ──
            //
            // La première version plaçait le texte à « cy + discR * 0.24 »
            // en supposant que la ligne de base était à ~26 % sous le
            // centre. Sur la planche de vérification, le chiffre débordait
            // du disque vers le bas et le mot « Nord-Ouest » en sortait
            // complètement.
            //
            // On mesure donc la police. FontMetrics est récupéré dans
            // l'objet réutilisé `fm` : la surcharge getFontMetrics(obj)
            // n'alloue rien, contrairement à getFontMetrics().
            String deg = Compass.formatDegrees(heading);
            // 0,54 et non 0,60 : à 0,60 le haut du « 312° » frôle le liseré
            // du disque, et sur une police système plus haute que celle de
            // la vérification, il le franchirait.
            float sizeDeg = discR * 0.54f;
            textPaint.setTextSize(sizeDeg);
            textPaint.getFontMetrics(fm);
            // Centrage vertical exact : le milieu optique de la ligne est
            // à (ascent + descent) / 2 sous la ligne de base.
            float baselineDeg = cy - (fm.ascent + fm.descent) / 2f - discR * 0.14f;
            textPaint.setColor(colorAccent);
            canvas.drawText(deg, cx, baselineDeg, textPaint);

            // ── LE SECTEUR : LA TAILLE S'AJUSTE AU LIEU D'ÊTRE DEVINÉE ──
            //
            // « Nord-Ouest » touchait encore le bord du disque avec un
            // simple seuil « abréger ou pas ». Le problème du seuil, c'est
            // qu'il ne change rien quand la chaîne dépasse de 3 %.
            //
            // On calcule donc la taille exacte qui fait tenir le texte dans
            // la largeur utile, au lieu de choisir entre deux tailles
            // arbitraires. measureText est une mesure, pas une allocation.
            //
            // La marge interne est de 8 % du rayon de disque : un texte qui
            // touche le liseré paraît collé.
            String card = Compass.cardinal(heading);
            // 0,26 et non 0,30 : à 0,30, « Nord-Ouest » (10 caractères)
            // occupe 1,65 × le rayon du disque pour une limite fixée à
            // 1,68 — une marge de 2 %, que la moindre différence de police
            // entre appareils suffit à consommer. À 0,26 il reste 15 % de
            // marge, et le texte est toujours parfaitement lisible.
            float sizeCard = discR * 0.26f;
            textPaint.setTextSize(sizeCard);
            float largeurMax = discR * 1.60f;      // 2 * 0.80 * discR
            float mesure = textPaint.measureText(card);
            if (mesure > largeurMax && mesure > 0f) {
                // Mise à l'échelle exacte : la largeur est proportionnelle à
                // la taille de police, donc le rapport donne directement la
                // bonne taille.
                sizeCard *= largeurMax / mesure;
                textPaint.setTextSize(sizeCard);
            }
            // Plancher : en dessous de 9 dp, le texte n'est plus lisible sur
            // un écran de téléphone. À ce stade, on abrège plutôt que de
            // réduire davantage — un « O-S-O » lisible vaut mieux qu'un
            // « Ouest-Sud-Ouest » de 6 sp.
            float plancher = dp(9);
            if (sizeCard < plancher) {
                card = Compass.abbreviate(card);
                textPaint.setTextSize(plancher);
                float m2 = textPaint.measureText(card);
                if (m2 > largeurMax && m2 > 0f) {
                    textPaint.setTextSize(plancher * largeurMax / m2);
                }
            }
            textPaint.getFontMetrics(fm);
            float baselineCard = cy + discR * 0.55f - (fm.ascent + fm.descent) / 2f;
            textPaint.setColor(colorText);
            canvas.drawText(card, cx, baselineCard, textPaint);
        }

        // ---- avertissement : nord vrai demandé, déclinaison inconnue ----
        if (declinationUnknown) {
            labelPaint.setColor(colorWarn);
            labelPaint.setTextSize(dp(9));
            canvas.drawText("cap magnétique (déclinaison inconnue)",
                    cx, h - dp(4), labelPaint);
            labelPaint.setColor(colorDim);
        }
    }

    /** Une graduation partant du bord, vers l'intérieur, à l'angle donné. */
    private void drawTick(Canvas cv, float cx, float cy, float r, float deg, float len) {
        double a = Math.toRadians(deg - 90.0);   // 0° = en haut
        float cos = (float) Math.cos(a);
        float sin = (float) Math.sin(a);
        cv.drawLine(cx + cos * r,         cy + sin * r,
                    cx + cos * (r - len), cy + sin * (r - len), tickPaint);
    }

    /** Une lettre cardinale, droite, à l'intérieur du cercle. */
    private void drawCardinal(Canvas cv, float cx, float cy, float r,
                              float deg, String letter, boolean isNorth) {
        double a = Math.toRadians(deg - 90.0);
        float rr = r * 0.74f;
        float x = cx + (float) Math.cos(a) * rr;
        float y = cy + (float) Math.sin(a) * rr;
        cardPaint.setTextSize(dp(isNorth ? 15 : 13));
        cardPaint.setColor(isNorth ? colorAccent : colorText);
        // +5 : approximation de la demi-hauteur de capitale, pour centrer
        // le glyphe sur son point d'ancrage.
        cv.drawText(letter, x, y + dp(5), cardPaint);
    }

    /**
     * L'aiguille : une flèche rouge dans la COURONNE du cadran.
     *
     * ELLE NE TRAVERSE PAS LE CENTRE. C'était le défaut de la première
     * version : l'aiguille allait de -38 dp à +17 dp du centre, et le cap
     * en gros caractères était écrit à +30 dp. Les deux se chevauchaient,
     * et un chiffre par-dessus une flèche ne se lit pas.
     *
     * Elle occupe donc l'anneau extérieur, entre 55 % et 92 % du rayon :
     * assez visible pour se lire d'un coup d'œil, assez à l'écart pour
     * laisser le centre au chiffre.
     *
     * Dessinée SANS rotation : l'appelant a déjà tourné le canevas de -cap,
     * et une aiguille aimantée pointe vers le N du cadran, pas vers le haut
     * de l'écran.
     *
     * Le Path est réutilisé à chaque image : aucune allocation.
     */
    private void drawNeedle(Canvas cv, float cx, float cy, float r) {
        // La pointe touche presque les graduations, et la base reste EN
        // DEHORS du cercle des lettres : les deux ne se superposent jamais.
        float outer = r - dp(2);      // pointe, au ras des graduations
        float inner = r * 0.80f;      // base : couronne visible sans                                       // chevaucher les lettres (à 0,74 r)
        float half = Math.max(dp(5), r * 0.075f);   // demi-largeur proportionnelle

        needlePath.reset();
        needlePath.moveTo(cx, cy - outer);                 // pointe nord
        needlePath.lineTo(cx + half, cy - inner);          // base droite
        needlePath.lineTo(cx - half, cy - inner);          // base gauche
        needlePath.close();

        needlePaint.setColor(colorNeedle);
        cv.drawPath(needlePath, needlePaint);

        // Petite queue sud, plus courte et plus terne : elle n'indique rien
        // d'utile, elle sert juste à équilibrer visuellement la flèche.
        needlePath.reset();
        needlePath.moveTo(cx, cy + outer);
        needlePath.lineTo(cx + half * 0.7f, cy + inner);
        needlePath.lineTo(cx - half * 0.7f, cy + inner);
        needlePath.close();
        needlePaint.setColor(colorDim);
        cv.drawPath(needlePath, needlePaint);
    }
}
