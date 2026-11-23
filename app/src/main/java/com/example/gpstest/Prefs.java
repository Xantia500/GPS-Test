package com.example.gpstest;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * STOCKAGE des réglages — un seul fichier, écrit à chaque modification.
 *
 * ══════════════════════════════════════════════════════════════════════
 * RÈGLE 1 : ON ÉCRIT IMMÉDIATEMENT, IL N'Y A PAS DE BOUTON « ENREGISTRER »
 * ══════════════════════════════════════════════════════════════════════
 *
 * Un réglage qu'on peut oublier d'enregistrer est un bug d'ergonomie, pas
 * une fonctionnalité manquante : l'utilisateur ferme l'écran en croyant
 * avoir fait son choix, et il a perdu. C'est pour cette raison que
 * l'écran de réglages n'a AUCUN bouton de validation : chaque geste écrit.
 *
 * Même règle pour les sélecteurs de la carte : changer le datum ou le
 * filtre d'affichage des satellites écrit dans les préférences, tout de
 * suite. Sinon, faire tourner le téléphone remet le réglage par défaut —
 * cas réel, et parfaitement déroutant.
 *
 * ══════════════════════════════════════════════════════════════════════
 * RÈGLE 2 : commit() POUR CE QUE LE SERVICE LIT, apply() POUR LE RESTE
 * ══════════════════════════════════════════════════════════════════════
 *
 * apply() écrit en mémoire immédiatement et sur disque en arrière-plan.
 * commit() écrit en mémoire ET attend le disque.
 *
 * · Politique d'arrière-plan : commit(). Le service la relit quelques
 *   millisecondes plus tard, depuis un autre thread, dans onCreate(). Avec
 *   apply(), il peut lire l'ANCIENNE valeur. Bug réel, intermittent, et
 *   impossible à reproduire en debuggage. Quelques octets à écrire : le
 *   coût de commit() est nul.
 *
 * · Tout le reste : apply(). Rien ne lit ces valeurs dans la foulée, et
 *   bloquer le thread principal pour du confort est un mauvais échange.
 *
 * ══════════════════════════════════════════════════════════════════════
 * RÈGLE 3 : LE NOM DU FICHIER EST VERSIONNÉ
 * ══════════════════════════════════════════════════════════════════════
 *
 * Le fichier s'appelait « gpstest_prefs ». Un déploiement par-dessus
 * l'ancienne version (c'est le cas de TOUT utilisateur qui met à jour une
 * app, donc le cas normal) récupère un fichier contenant d'anciennes clés
 * dont on ne maîtrise plus la sémantique.
 *
 * D'où « gpstest_prefs_v2 » : un nouveau fichier, des clés propres, aucune
 * migration à écrire, aucun réglage fantôme. Les réglages perdus à la mise
 * à jour sont trois boutons radio — c'est le prix, et il est juste.
 */
public final class Prefs {

    private Prefs() { }

    /** v2 : la destination des traces est un réglage persistant (v5.4). */
    public static final String FILE = "gpstest_prefs_v2";

    private static final String KEY_POLICY        = "background_policy";
    private static final String KEY_GPX_VERSION   = "gpx_version";
    public static final String KEY_GNSS_INTERVAL_MS = "gnss_acquisition_interval_ms";
    private static final String KEY_DEST_URI      = "dest_tree_uri";
    private static final String KEY_DEST_LABEL    = "dest_label";
    private static final String KEY_DATUM         = "map_datum";
    private static final String KEY_SAT_FILTER    = "sat_filter_mode";
    private static final String KEY_THEME_MODE    = "theme_mode";
    private static final String KEY_DEST_PRESET   = "dest_preset_id";
    private static final String KEY_VOICE_ALERT   = "voice_alert_enabled";
    private static final String KEY_VOICE_RECOVERY = "voice_recovery_enabled";
    private static final String KEY_VOICE_REPEAT_MS = "voice_repeat_ms";
    private static final String KEY_VOICE_MAX      = "voice_max_count";
    private static final String KEY_UNITS          = "units_system";
    private static final String KEY_COMPASS_NORTH  = "compass_north_ref";
    private static final String KEY_MAP_STYLE = "map_style";
    private static final String KEY_MAP_HEADING_UP = "map_heading_up";
    public  static final String KEY_MIN_DISTANCE_M = "min_distance_m";
    public  static final String KEY_MIN_TIME_MS    = "min_time_ms";
    public  static final String KEY_TRACK_MODE     = "track_mode";
    private static final String KEY_POSTPROCESS    = "postprocess_enabled";
    private static final String KEY_PP_TOLERANCE   = "postprocess_tolerance_m";
    private static final String KEY_RAW_CSV_TIME = "expert_raw_csv_time";
    private static final String KEY_RAW_CSV_SIGNAL = "expert_raw_csv_signal";
    private static final String KEY_RAW_CSV_DOPPLER = "expert_raw_csv_doppler";
    private static final String KEY_RAW_CSV_PHASE = "expert_raw_csv_phase";
    private static final String KEY_RAW_CSV_NMEA = "expert_raw_csv_nmea";
    private static final String KEY_RAW_FULL_TRACKING = "expert_raw_full_tracking";

    /**
     * Accès aux préférences pour l'ÉCOUTE des changements (service).
     *
     * Volontairement distinct de sp() : le service en a besoin pour
     * s'enregistrer, mais on ne veut pas ouvrir sp() publiquement — sinon
     * n'importe quelle classe pourrait écrire dans les préférences en
     * contournant les méthodes write*(), qui sont les seules à valider.
     */
    public static SharedPreferences spForListener(Context ctx) {
        return sp(ctx);
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------
    // Options d'enregistrement GNSS brut (mode Expert uniquement)
    // ------------------------------------------------------------------

    public static boolean readRawCsvTime(Context ctx) {
        return sp(ctx).getBoolean(KEY_RAW_CSV_TIME, true);
    }

    public static void writeRawCsvTime(Context ctx, boolean enabled) {
        sp(ctx).edit().putBoolean(KEY_RAW_CSV_TIME, enabled).apply();
    }

    public static boolean readRawCsvSignal(Context ctx) {
        return sp(ctx).getBoolean(KEY_RAW_CSV_SIGNAL, true);
    }

    public static void writeRawCsvSignal(Context ctx, boolean enabled) {
        sp(ctx).edit().putBoolean(KEY_RAW_CSV_SIGNAL, enabled).apply();
    }

    public static boolean readRawCsvDoppler(Context ctx) {
        return sp(ctx).getBoolean(KEY_RAW_CSV_DOPPLER, true);
    }

    public static void writeRawCsvDoppler(Context ctx, boolean enabled) {
        sp(ctx).edit().putBoolean(KEY_RAW_CSV_DOPPLER, enabled).apply();
    }

    public static boolean readRawCsvPhase(Context ctx) {
        return sp(ctx).getBoolean(KEY_RAW_CSV_PHASE, true);
    }

    public static void writeRawCsvPhase(Context ctx, boolean enabled) {
        sp(ctx).edit().putBoolean(KEY_RAW_CSV_PHASE, enabled).apply();
    }

    public static boolean readRawCsvNmea(Context ctx) {
        return sp(ctx).getBoolean(KEY_RAW_CSV_NMEA, true);
    }

    public static void writeRawCsvNmea(Context ctx, boolean enabled) {
        sp(ctx).edit().putBoolean(KEY_RAW_CSV_NMEA, enabled).apply();
    }

    /**
     * Mode de mesure GNSS complet (API 31+). Désactivé par défaut car il
     * réduit le duty-cycling du chipset et augmente la consommation.
     */
    public static boolean readRawFullTracking(Context ctx) {
        return sp(ctx).getBoolean(KEY_RAW_FULL_TRACKING, false);
    }

    public static void writeRawFullTracking(Context ctx, boolean enabled) {
        sp(ctx).edit().putBoolean(KEY_RAW_FULL_TRACKING, enabled).apply();
    }

    // ------------------------------------------------------------------
    // Politique d'arrière-plan
    // ------------------------------------------------------------------

    public static BackgroundPolicy.Policy readPolicy(Context ctx) {
        return BackgroundPolicy.Policy.fromId(
                sp(ctx).getInt(KEY_POLICY, BackgroundPolicy.Policy.CONTINUE.id));
    }

    public static void writePolicy(Context ctx, BackgroundPolicy.Policy p) {
        if (p == null) p = BackgroundPolicy.Policy.CONTINUE;
        // commit() et pas apply() : le service lit cette valeur dans onCreate,
        // potentiellement quelques millisecondes plus tard, depuis le même
        // process mais un autre thread. apply() est asynchrone ; sur un
        // changement de réglage suivi d'un démarrage de service, on peut lire
        // l'ancienne valeur. L'écriture est de quelques octets : le coût est nul.
        sp(ctx).edit().putInt(KEY_POLICY, p.id).commit();
    }

    // ------------------------------------------------------------------
    // Cadence d'acquisition GNSS (réglage prêt pour la prochaine version)
    // ------------------------------------------------------------------

    public static long readGnssIntervalMs(Context ctx) {
        long requested = sp(ctx).getLong(KEY_GNSS_INTERVAL_MS,
                GnssAcquisitionConfig.DEFAULT_INTERVAL_MS);
        return GnssAcquisitionConfig.normalizeIntervalMs(requested);
    }

    public static void writeGnssIntervalMs(Context ctx, long intervalMs) {
        long normalized = GnssAcquisitionConfig.normalizeIntervalMs(intervalMs);
        // Le service peut se réabonner immédiatement après la modification.
        sp(ctx).edit().putLong(KEY_GNSS_INTERVAL_MS, normalized).commit();
    }

    // ------------------------------------------------------------------
    // Version GPX
    // ------------------------------------------------------------------

    public static GpxWriter.Version readGpxVersion(Context ctx) {
        String name = sp(ctx).getString(KEY_GPX_VERSION, GpxWriter.Version.V11.name());
        try {
            return GpxWriter.Version.valueOf(name);
        } catch (Exception e) {
            return GpxWriter.Version.V11;   // valeur inconnue -> défaut sûr
        }
    }

    public static void writeGpxVersion(Context ctx, GpxWriter.Version v) {
        if (v == null) v = GpxWriter.Version.V11;
        // commit() aussi : le service lit la version à l'OUVERTURE du segment,
        // juste après un appui sur ENREGISTRER. Même course que la politique.
        sp(ctx).edit().putString(KEY_GPX_VERSION, v.name()).commit();
    }

    /** Explication affichée à l'utilisateur, pour chacune des deux versions. */
    public static String gpxExplanation(GpxWriter.Version v) {
        if (v == GpxWriter.Version.V10) {
            return "1.0 : vitesse et cap écrits en direct dans chaque point. "
                    + "Lisible par les outils anciens. Pas de bloc metadata.";
        }
        return "1.1 (recommandé) : vitesse et cap dans un bloc extensions "
                + "propre. Standard actuel, validé par QGIS et les validateurs de schéma.";
    }

    // ------------------------------------------------------------------
    // Destination des traces (v5.4)
    //
    // Deux clés, pas une : l'URI et le LIBELLÉ. On ne peut pas reconstruire
    // un libellé lisible à partir de l'URI dans tous les cas, et afficher
    // « content://com.android.externalstorage.documents/tree/primary%3ADownload »
    // à un utilisateur non informaticien est exactement ce qu'on veut éviter.
    // On mémorise donc ce qu'on a affiché au moment du choix.
    // ------------------------------------------------------------------

    public static String readDestUri(Context ctx) {
        return sp(ctx).getString(KEY_DEST_URI, null);
    }

    public static void writeDestUri(Context ctx, String uri) {
        // commit() : le service peut démarrer un enregistrement immédiatement
        // après la validation du dialogue de démarrage, sur un autre thread.
        SharedPreferences.Editor e = sp(ctx).edit();
        if (uri == null) e.remove(KEY_DEST_URI); else e.putString(KEY_DEST_URI, uri);
        e.commit();
    }

    public static String readDestLabel(Context ctx) {
        return sp(ctx).getString(KEY_DEST_LABEL, null);
    }

    /**
     * Identifiant du preset actif ("download", "documents", "app"), ou null
     * si l'utilisateur a choisi un dossier via le sélecteur.
     *
     * POURQUOI PAS LE LIBELLÉ : il est affiché, donc traduit, et il contient
     * des apostrophes. Une clé de préférence doit être stable quelle que
     * soit la langue. Le libellé reste stocké à part, pour l'affichage.
     */
    public static String readDestPreset(Context ctx) {
        return sp(ctx).getString(KEY_DEST_PRESET, null);
    }

    public static void writeDestPreset(Context ctx, String presetId) {
        SharedPreferences.Editor e = sp(ctx).edit();
        if (presetId == null) e.remove(KEY_DEST_PRESET);
        else e.putString(KEY_DEST_PRESET, presetId);
        e.commit();
    }

    public static void writeDestLabel(Context ctx, String label) {
        SharedPreferences.Editor e = sp(ctx).edit();
        if (label == null) e.remove(KEY_DEST_LABEL); else e.putString(KEY_DEST_LABEL, label);
        e.commit();
    }

    /** Efface toute la destination : URI et libellé, jamais l'un sans l'autre. */
    public static void clearDest(Context ctx) {
        sp(ctx).edit().remove(KEY_DEST_URI).remove(KEY_DEST_LABEL).commit();
    }

    // ------------------------------------------------------------------
    // Réglages de la carte (datum, filtre satellites)
    //
    // POURQUOI LES PERSISTER, ALORS QUE CE SONT DES RÉGLAGES D'AFFICHAGE :
    // parce qu'un réglage qui ne survit pas à une rotation d'écran n'est pas
    // un réglage, c'est un incident. L'utilisateur choisit « Lambert 93 »
    // pour lire ses coordonnées françaises, il tourne le téléphone en
    // paysage, et tout revient en WGS84. Il croit à un bug — à juste titre.
    // ------------------------------------------------------------------

    public static Datum readDatum(Context ctx) {
        String name = sp(ctx).getString(KEY_DATUM, null);
        if (name != null) {
            for (Datum d : Datum.ALL) {
                if (d.name.equals(name)) return d;
            }
        }
        return Datum.WGS84;
    }

    public static void writeDatum(Context ctx, Datum d) {
        if (d == null) d = Datum.WGS84;
        sp(ctx).edit().putString(KEY_DATUM, d.name).apply();
    }

    // ------------------------------------------------------------------
    // Annonce vocale de perte de signal (v5.6)
    //
    // ACTIVÉE PAR DÉFAUT : la demande explicite était de signaler les pertes.
    // Un utilisateur qui n'a jamais ouvert les réglages doit être averti.
    // ------------------------------------------------------------------

    public static boolean readVoiceAlert(Context ctx) {
        return sp(ctx).getBoolean(KEY_VOICE_ALERT, true);
    }

    public static void writeVoiceAlert(Context ctx, boolean enabled) {
        sp(ctx).edit().putBoolean(KEY_VOICE_ALERT, enabled).apply();
    }

    /** Annoncer AUSSI le retour du signal ? Vrai par défaut. */
    public static boolean readVoiceRecovery(Context ctx) {
        return sp(ctx).getBoolean(KEY_VOICE_RECOVERY, true);
    }

    public static void writeVoiceRecovery(Context ctx, boolean enabled) {
        sp(ctx).edit().putBoolean(KEY_VOICE_RECOVERY, enabled).apply();
    }

    /**
     * Rythme des rappels de perte (ms). 0 = jamais de rappel.
     *
     * On persiste la VALEUR, pas un index : si demain on ajoute « 3 min »
     * dans la liste, les préférences existantes restent valides. Un index
     * aurait décalé tous les réglages.
     */
    public static long readVoiceRepeatMs(Context ctx) {
        long v = sp(ctx).getLong(KEY_VOICE_REPEAT_MS, LossAnnouncer.DEFAULT_REPEAT_MS);
        if (v != LossAnnouncer.REPEAT_NEVER
                && (v < LossAnnouncer.MIN_REPEAT_MS || v > LossAnnouncer.MAX_REPEAT_MS)) {
            return LossAnnouncer.DEFAULT_REPEAT_MS;
        }
        return v;
    }

    public static void writeVoiceRepeatMs(Context ctx, long ms) {
        sp(ctx).edit().putLong(KEY_VOICE_REPEAT_MS, ms).apply();
    }

    /** Plafond d'annonces par épisode. 0 = illimité. */
    public static int readVoiceMaxCount(Context ctx) {
        return sp(ctx).getInt(KEY_VOICE_MAX, LossAnnouncer.DEFAULT_MAX_ANNOUNCEMENTS);
    }

    public static void writeVoiceMaxCount(Context ctx, int n) {
        sp(ctx).edit().putInt(KEY_VOICE_MAX, n).apply();
    }

    // ------------------------------------------------------------------
    // Système d'unités (v5.6) — métrique par défaut (France)
    // ------------------------------------------------------------------

    public static Units.System readUnits(Context ctx) {
        return Units.System.fromId(sp(ctx).getString(KEY_UNITS, null));
    }

    public static void writeUnits(Context ctx, Units.System sys) {
        if (sys == null) sys = Units.System.METRIC;
        sp(ctx).edit().putString(KEY_UNITS, sys.id).apply();
    }

    // ------------------------------------------------------------------
    // Espacement des points (v5.10)
    // ------------------------------------------------------------------

    public static int readMinDistanceM(Context ctx) {
        return sp(ctx).getInt(KEY_MIN_DISTANCE_M, SamplePolicy.DEFAULT_MIN_DISTANCE_M);
    }

    public static void writeMinDistanceM(Context ctx, int m) {
        sp(ctx).edit().putInt(KEY_MIN_DISTANCE_M, m).apply();
    }

    /** Mode d'enregistrement (WALK / BIKE / CAR / CUSTOM). */
    public static TrackMode.Mode readTrackMode(Context ctx) {
        return TrackMode.fromId(
                sp(ctx).getString(KEY_TRACK_MODE, TrackMode.Mode.WALK.name()),
                TrackMode.Mode.WALK);
    }

    public static void writeTrackMode(Context ctx, TrackMode.Mode m) {
        if (m == null) m = TrackMode.Mode.WALK;
        sp(ctx).edit().putString(KEY_TRACK_MODE, m.name()).apply();
    }

    /** Le post-traitement est-il appliqué à la fin d'une session ? */
    public static boolean readPostProcess(Context ctx) {
        return sp(ctx).getBoolean(KEY_POSTPROCESS, true);
    }

    public static void writePostProcess(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean(KEY_POSTPROCESS, on).apply();
    }

    /** Tolérance Douglas-Peucker (mètres). 0 = pas de simplification. */
    public static double readPpTolerance(Context ctx) {
        double v = sp(ctx).getFloat(KEY_PP_TOLERANCE,
                (float) TrackPostProcess.DEFAULT_TOLERANCE_M);
        if (v < 0 || v > 100) return TrackPostProcess.DEFAULT_TOLERANCE_M;
        return v;
    }

    public static void writePpTolerance(Context ctx, double m) {
        sp(ctx).edit().putFloat(KEY_PP_TOLERANCE, (float) m).apply();
    }

    public static long readMinTimeMs(Context ctx) {
        return sp(ctx).getLong(KEY_MIN_TIME_MS, SamplePolicy.DEFAULT_MIN_TIME_MS);
    }

    public static void writeMinTimeMs(Context ctx, long ms) {
        sp(ctx).edit().putLong(KEY_MIN_TIME_MS, ms).apply();
    }

    // ------------------------------------------------------------------
    // Thème (v5.5)
    // ------------------------------------------------------------------

    public static String readThemeMode(Context ctx) {
        return sp(ctx).getString(KEY_THEME_MODE, ThemeMode.Mode.SYSTEM.id);
    }

    public static void writeThemeMode(Context ctx, String id) {
        // apply() suffit : rien ne relit cette valeur dans les millisecondes
        // qui suivent. Le thème est appliqué au prochain onCreate().
        sp(ctx).edit().putString(KEY_THEME_MODE, id).apply();
    }

    // ------------------------------------------------------------------
    // Boussole : nord de référence (v5.14)
    // ------------------------------------------------------------------

    public static Compass.North readCompassNorth(Context ctx) {
        return Compass.North.fromId(sp(ctx).getString(KEY_COMPASS_NORTH, null));
    }

    public static void writeCompassNorth(Context ctx, Compass.North n) {
        if (n == null) n = Compass.North.MAGNETIC;
        sp(ctx).edit().putString(KEY_COMPASS_NORTH, n.id).apply();
    }

    // ------------------------------------------------------------------
    // Cartographie Mapsforge et orientation (LOT 7F)
    // ------------------------------------------------------------------

    public static MapStyle readMapStyle(Context ctx) {
        return MapStyle.fromId(sp(ctx).getString(KEY_MAP_STYLE, MapStyle.STANDARD.id));
    }

    public static void writeMapStyle(Context ctx, MapStyle style) {
        if (style == null) style = MapStyle.STANDARD;
        sp(ctx).edit().putString(KEY_MAP_STYLE, style.id).apply();
    }

    public static boolean readMapHeadingUp(Context ctx) {
        return sp(ctx).getBoolean(KEY_MAP_HEADING_UP, false);
    }

    public static void writeMapHeadingUp(Context ctx, boolean enabled) {
        sp(ctx).edit().putBoolean(KEY_MAP_HEADING_UP, enabled).apply();
    }

    public static SatelliteFilter.Mode readSatFilter(Context ctx) {
        String name = sp(ctx).getString(KEY_SAT_FILTER, null);
        if (name != null) {
            try {
                return SatelliteFilter.Mode.valueOf(name);
            } catch (Exception ignored) { }
        }
        return SatelliteFilter.Mode.IN_FIX;
    }

    public static void writeSatFilter(Context ctx, SatelliteFilter.Mode m) {
        if (m == null) m = SatelliteFilter.Mode.IN_FIX;
        sp(ctx).edit().putString(KEY_SAT_FILTER, m.name()).apply();
    }
}
