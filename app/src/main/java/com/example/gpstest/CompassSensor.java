package com.example.gpstest;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Surface;
import android.view.WindowManager;

/**
 * LECTURE DU CAP — capteurs, repli, orientation d'écran.
 *
 * ══════════════════════════════════════════════════════════════════════
 * POURQUOI LA BOUSSOLE VIT DANS L'ACTIVITY, PLUS DANS LE SERVICE
 * ══════════════════════════════════════════════════════════════════════
 *
 * Jusqu'à la v5.13, l'azimut était lu par TrackingService. Deux
 * conséquences fâcheuses :
 *
 *   1. la boussole restait morte tant que l'utilisateur n'avait pas
 *      appuyé sur « Démarrer » — or un cap est utile AVANT de démarrer ;
 *   2. une fois le service lancé, le capteur de rotation tournait en
 *      continu, **écran éteint compris**, pendant tout un enregistrement
 *      de plusieurs heures, pour alimenter une valeur que personne ne
 *      lisait.
 *
 * La règle appliquée désormais est simple : les capteurs nécessaires à
 * l'ENREGISTREMENT (position, statut GNSS, NMEA) restent dans le service ;
 * les capteurs nécessaires à l'AFFICHAGE (boussole) vivent dans
 * l'Activity, qui les ouvre dans onStart et les referme dans onStop. Le
 * service n'a plus à connaître l'orientation du téléphone.
 *
 * ══════════════════════════════════════════════════════════════════════
 * TROIS SOURCES, DANS CET ORDRE
 * ══════════════════════════════════════════════════════════════════════
 *
 *   1. TYPE_ROTATION_VECTOR : une fusion déjà faite par le système
 *      (magnéto + accéléro + gyro). Le meilleur résultat, et le moins
 *      gourmand en calcul pour nous.
 *
 *   2. ACCELEROMETER + MAGNETIC_FIELD : le repli classique, quand le
 *      capteur de rotation n'existe pas. Certaines tablettes d'entrée de
 *      gamme n'en ont pas. Sans ce repli, l'ancienne version laissait la
 *      ligne « Boussole » à « — » sans la moindre explication.
 *
 *   3. Aucun des deux : la boussole est déclarée ABSENTE, et l'écran le
 *      dit noir sur blanc. On n'affiche pas un cadran figé au nord, qui
 *      serait une mesure inventée.
 *
 * ══════════════════════════════════════════════════════════════════════
 * DEUX CORRECTIONS QUE L'ANCIEN CODE N'AVAIT PAS
 * ══════════════════════════════════════════════════════════════════════
 *
 * A. ORIENTATION DE L'ÉCRAN. L'azimut du capteur est relatif au « haut »
 *    MATÉRIEL du téléphone. Si l'utilisateur passe en paysage, le haut de
 *    l'écran devient un autre axe physique : le cap affiché serait faux
 *    de 90° (ou 180°). On corrige avec Display.getRotation().
 *
 *    Démonstration du signe, une fois pour toutes : en ROTATION_90, le
 *    remappage canonique donne « haut d'écran = -X de l'appareil ». Or
 *    l'azimut de X vaut celui de Y plus 90° (les axes tournent dans le
 *    sens horaire vu de dessus, comme les caps). Donc l'azimut du haut
 *    d'écran vaut azimut(Y) - 90°. D'où la règle unique :
 *
 *        capEcran = normalize(capCapteur - 90 × rotation)
 *
 *    qui couvre les quatre rotations sans cas particulier.
 *
 * B. INCLINAISON. getOrientation suppose l'appareil à plat : quand on le
 *    tient debout, l'axe « haut » n'a plus de projection horizontale et
 *    l'azimut devient instable. C'est physique, pas logiciel. On mesure
 *    donc l'inclinaison (tangage/roulis) et, au-delà de 25°, on prévient
 *    que le cap n'est plus fiable — au lieu d'afficher une valeur qui
 *    saute sans raison apparente.
 */
public class CompassSensor implements SensorEventListener {

    /** Ce qui est réellement disponible sur cet appareil. */
    public enum Source {
        /** Aucun capteur utilisable. */
        NONE,
        /** Accéléromètre + champ magnétique, recombinés par nous. */
        ACCEL_MAG,
        /** Capteur de rotation, fusionné par le système. */
        ROTATION_VECTOR
    }

    /** Inclinaison au-delà de laquelle le cap n'est plus fiable (degrés). */
    public static final float TILT_LIMIT_DEG = 25f;

    private final SensorManager sm;
    private final WindowManager wm;
    /** Conservé pour Context.getDisplay() sur API 30+. */
    private final Context ctx;

    private Sensor rotVec, accel, magnet;
    private Source source = Source.NONE;
    private boolean started = false;

    /** Azimut brut du capteur, en degrés [0,360). NaN = jamais reçu. */
    private volatile float azimuthRaw = Float.NaN;
    /** Inclinaison courante, en degrés. */
    private volatile float tiltDeg = Float.NaN;
    /** Précision déclarée par le système, pour l'avertissement. */
    private volatile boolean unreliable = false;

    // Tableaux réutilisés : aucun tableau alloué par événement.
    private final float[] rotMatrix = new float[9];
    private final float[] orientation = new float[3];
    private final float[] gravity = new float[3];
    private final float[] geomagnetic = new float[3];
    private boolean haveGravity = false;
    private boolean haveGeomagnetic = false;

    /**
     * Prévenu à chaque nouvel azimut.
     *
     * POURQUOI UN RAPPEL ET PAS UNE LECTURE PAR TICK : l'interface se
     * rafraîchit à 2 Hz (UI_TICK_MS = 500). Pour une position ou un DOP,
     * c'est largement assez. Pour un cap, non : en tournant sur soi-même
     * à 90°/s, 2 Hz donne un saut de 45° entre deux images — le cadran
     * clignote au lieu de suivre la main. Un capteur à 15-30 Hz donne le
     * mouvement réel.
     *
     * On ne redessine RIEN ici : on prévient l'Activity, qui décide. Le
     * rappel est appelé sur le thread principal (les capteurs sont
     * enregistrés sans Handler, donc ils livrent sur le Looper du thread
     * qui a appelé start() — c'est notre thread principal).
     */
    public interface OnHeadingChanged {
        void onHeadingChanged();
    }

    private OnHeadingChanged callback;

    public void setOnHeadingChanged(OnHeadingChanged cb) { this.callback = cb; }

    public CompassSensor(Context ctx) {
        this.ctx = ctx;
        sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        if (sm != null) {
            rotVec = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            accel  = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            magnet = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        }
        if (rotVec != null) {
            source = Source.ROTATION_VECTOR;
        } else if (accel != null && magnet != null) {
            source = Source.ACCEL_MAG;
        } else {
            source = Source.NONE;
        }
    }

    public Source source() { return source; }

    /** Vrai si un cap peut être affiché sur cet appareil. */
    public boolean isAvailable() { return source != Source.NONE; }

    /**
     * Vrai quand la boussole est absente alors que l'utilisateur l'attend
     * — sert à écrire un message utile plutôt que de masquer la ligne.
     */
    public String unavailableReason() {
        if (source != Source.NONE) return null;
        if (sm == null) return "système de capteurs indisponible";
        if (accel == null) return "pas d'accéléromètre ni de capteur de rotation";
        if (magnet == null) return "pas de magnétomètre ni de capteur de rotation";
        return "aucun capteur d'orientation";
    }

    /** Ouvre les capteurs. Idempotent. */
    public void start() {
        if (started || sm == null || source == Source.NONE) return;
        started = true;
        azimuthRaw = Float.NaN;
        tiltDeg = Float.NaN;
        unreliable = false;
        haveGravity = false;
        haveGeomagnetic = false;
        if (source == Source.ROTATION_VECTOR) {
            sm.registerListener(this, rotVec, SensorManager.SENSOR_DELAY_UI);
        } else {
            sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI);
            sm.registerListener(this, magnet, SensorManager.SENSOR_DELAY_UI);
        }
    }

    /** Referme les capteurs. Idempotent, ne lève jamais. */
    public void stop() {
        if (!started || sm == null) return;
        started = false;
        try {
            sm.unregisterListener(this);
        } catch (Exception ignored) { }
        azimuthRaw = Float.NaN;
        tiltDeg = Float.NaN;
    }

    public boolean isStarted() { return started; }

    // ------------------------------------------------------------------
    // Lecture
    // ------------------------------------------------------------------

    /**
     * Cap MAGNÉTIQUE corrigé de l'orientation de l'écran, en degrés
     * [0,360). NaN si aucun événement n'a encore été reçu.
     */
    public float azimuth() {
        float raw = azimuthRaw;
        if (Float.isNaN(raw)) return Float.NaN;
        int rot = displayRotationDegrees();
        // cap écran = cap capteur - rotation (voir la démonstration en tête)
        return Compass.normalize(raw - rot);
    }

    /** Inclinaison mesurée : 0 = à plat, 90 = vertical. NaN si inconnue. */
    public float tilt() { return tiltDeg; }

    /** Vrai si l'appareil est trop incliné pour un cap fiable. */
    public boolean tooTilted() {
        float t = tiltDeg;
        return !Float.isNaN(t) && t > TILT_LIMIT_DEG;
    }

    public boolean isUnreliable() { return unreliable; }

    /**
     * Rotation de l'écran en degrés (0/90/180/270).
     *
     * DEUX CHEMINS, ET PAS UN SEUL. Display.getRotation() est l'API
     * historique ; sur API 30+, Context.getDisplay() est la voie non
     * dépréciée, mais elle n'existe qu'à partir de cette version. On
     * garde donc les deux, choisis par Build.VERSION — c'est le motif
     * utilisé partout ailleurs dans ce projet, et il évite un
     * avertissement de dépréciation tout en restant correct sur API 24.
     */
    private int displayRotationDegrees() {
        if (wm == null) return 0;
        try {
            int r;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
                    && ctx != null && ctx.getDisplay() != null) {
                r = ctx.getDisplay().getRotation();
            } else {
                r = wm.getDefaultDisplay().getRotation();
            }
            switch (r) {
                case Surface.ROTATION_90:  return 90;
                case Surface.ROTATION_180: return 180;
                case Surface.ROTATION_270: return 270;
                case Surface.ROTATION_0:
                default:                   return 0;
            }
        } catch (Exception e) {
            // Si un constructeur casse cet appel, on préfère un cap non
            // corrigé de l'orientation à un plantage.
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // Événements
    // ------------------------------------------------------------------

    @Override
    public void onSensorChanged(SensorEvent event) {
        // Le test sur `started` n'est pas décoratif : un événement déjà en
        // file dans la boucle du thread principal peut être livré APRÈS
        // stop(). Sans ce garde, on écrirait un azimut dans un objet dont
        // l'écran n'existe plus, et on rappellerait une vue détruite.
        if (!started) return;
        if (event == null || event.values == null) return;

        if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotMatrix, event.values);
        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            gravity[0] = event.values[0];
            gravity[1] = event.values[1];
            gravity[2] = event.values[2];
            haveGravity = true;
            if (!haveGeomagnetic) return;
            if (!SensorManager.getRotationMatrix(rotMatrix, null, gravity, geomagnetic)) {
                return;   // combinaison dégénérée (appareil en chute libre…)
            }
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            geomagnetic[0] = event.values[0];
            geomagnetic[1] = event.values[1];
            geomagnetic[2] = event.values[2];
            haveGeomagnetic = true;
            if (!haveGravity) return;
            if (!SensorManager.getRotationMatrix(rotMatrix, null, gravity, geomagnetic)) {
                return;
            }
        } else {
            return;
        }

        SensorManager.getOrientation(rotMatrix, orientation);
        float az = (float) Math.toDegrees(orientation[0]);
        azimuthRaw = Compass.normalize(az);

        // Inclinaison = écart de l'axe vertical de l'appareil par rapport
        // à la verticale du lieu. Tangage et roulis suffisent.
        float pitch = (float) Math.toDegrees(orientation[1]);
        float roll  = (float) Math.toDegrees(orientation[2]);
        tiltDeg = Math.max(Math.abs(pitch), Math.abs(roll));

        OnHeadingChanged cb = callback;
        if (cb != null) cb.onHeadingChanged();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (sensor != null && sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            unreliable = (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE);
        }
    }

    // ------------------------------------------------------------------
    // Déclinaison magnétique
    // ------------------------------------------------------------------

    /**
     * Déclinaison à une position et une date, en degrés (est positif).
     *
     * Repose sur android.hardware.GeomagneticField, qui embarque un modèle
     * WMM complet dans le système — donc aucune table à maintenir et aucune
     * dépendance. Renvoie NaN si la position n'est pas connue : c'est le
     * signal que le nord vrai ne peut pas être calculé, et qu'il faut
     * retomber sur le magnétique en le disant.
     */
    public static float declinationAt(double latDeg, double lonDeg,
                                      double altMeters, long timeMillis) {
        if (Double.isNaN(latDeg) || Double.isNaN(lonDeg)) return Float.NaN;
        if (latDeg < -90 || latDeg > 90 || lonDeg < -180 || lonDeg > 180) return Float.NaN;

        // ── L'ALTITUDE PEUT ÊTRE NaN, ET C'EST LE CAS NORMAL AU DÉBUT ──
        //
        // TrackingState initialise son altitude à Double.NaN : elle n'a
        // aucune valeur avant le premier fix. Or GeomagneticField attend
        // une altitude en KILOMÈTRES, et (float)(NaN / 1000.0) vaut NaN.
        // Passer NaN dans un modèle d'interpolation polaire donne un
        // résultat non défini — au mieux NaN, au pire une valeur
        // arbitraire affichée comme une déclinaison.
        //
        // On remplace donc l'altitude absente par 0 (niveau de la mer).
        // L'erreur commise est négligeable : la déclinaison varie de moins
        // de 0,01° sur les 3 000 premiers mètres. Ce n'est pas une
        // approximation de la réponse, c'est un remplacement d'une donnée
        // MANQUANTE par sa valeur la plus probable.
        double altKm = Double.isNaN(altMeters) || Double.isInfinite(altMeters)
                ? 0.0
                : altMeters / 1000.0;

        try {
            android.hardware.GeomagneticField f =
                    new android.hardware.GeomagneticField(
                            (float) latDeg, (float) lonDeg,
                            (float) altKm, timeMillis);
            float d = f.getDeclination();
            // Ceinture et bretelles : si le modèle rend quand même NaN ou
            // l'infini, on le signale comme une absence plutôt que de
            // laisser une valeur invalide remonter jusqu'à l'affichage.
            if (Float.isNaN(d) || Float.isInfinite(d)) return Float.NaN;
            if (d < -180f || d > 180f) return Float.NaN;
            return d;
        } catch (Throwable t) {
            return Float.NaN;
        }
    }
}
