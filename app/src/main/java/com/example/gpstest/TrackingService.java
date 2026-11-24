package com.example.gpstest;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.GnssStatus;
import android.location.GnssMeasurementsEvent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.OnNmeaMessageListener;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.location.LocationManagerCompat;

import java.io.File;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SERVICE DE PREMIER PLAN qui porte tout le GPS.
 *
 * POURQUOI UN SERVICE :
 * Une Activity est détruite quand l'écran est tourné, et Android coupe les
 * mises à jour de localisation quand l'app passe en arrière-plan (depuis API 26).
 * Un Foreground Service, lui, continue de tourner écran éteint et app fermée,
 * au prix d'une notification permanente visible par l'utilisateur.
 *
 * RÉPARTITION DES RÔLES :
 *   Service     -> GPS, NMEA, GPX, boussole : la partie "métier"
 *   Activity    -> affichage et boutons : la partie "vue"
 *   TrackingState -> l'état partagé (le service écrit, l'UI lit)
 *
 * CYCLE DE VIE DU SERVICE :
 *   startForegroundService() depuis l'Activity
 *   -> onStartCommand() entend ACTION_START ou ACTION_RECORD
 *   -> startForeground() OBLIGATOIRE dans les 5 secondes (sinon ANR)
 *   -> tourne jusqu'à ACTION_STOP ou destruction par le système
 */
public class TrackingService extends Service {

    public static final String ACTION_START   = "com.example.gpstest.START";
    public static final String ACTION_STOP    = "com.example.gpstest.STOP";
    public static final String ACTION_RECORD  = "com.example.gpstest.RECORD";
    public static final String ACTION_UNRECORD = "com.example.gpstest.UNRECORD";
    /** Change la politique d'arrière-plan à chaud, sans redémarrer la session. */
    public static final String ACTION_SET_POLICY = "com.example.gpstest.SET_POLICY";
    /** Suspension MANUELLE (bouton de la notification) : ne change pas la politique. */
    public static final String ACTION_SUSPEND = "com.example.gpstest.SUSPEND";
    public static final String ACTION_RESUME = "com.example.gpstest.RESUME";
    /** Enregistrement GNSS brut déclenché depuis le mode Expert. */
    public static final String ACTION_RAW_LOG_START = "com.example.gpstest.RAW_LOG_START";
    public static final String ACTION_RAW_LOG_STOP = "com.example.gpstest.RAW_LOG_STOP";
    /** Réinscrit le callback brut avec la dernière préférence de full tracking. */
    public static final String ACTION_RAW_OPTIONS_CHANGED = "com.example.gpstest.RAW_OPTIONS_CHANGED";
    public static final String EXTRA_POLICY_ID = "policy_id";

    private static final String CHANNEL_ID = "gps_tracking";
    private static final int NOTIFICATION_ID = 1001;

    private final TrackingState state = TrackingState.get();

    private LocationManager locationManager;

    /**
     * File GNSS commune aux callbacks position, GnssStatus et NMEA.
     * Les mesures brutes conservent leur worker de débit dédié.
     */
    private GnssEventPipeline gnssEventPipeline;
    private final Object gnssProcessingLock = new Object();
    private final AtomicBoolean gnssUiRefreshPending = new AtomicBoolean(false);
    private final AtomicLong gnssSubscriptionGeneration = new AtomicLong();
    // Jetons indépendants : un changement de cadence doit invalider les anciens
    // callbacks de position sans interrompre NMEA / GnssStatus / CSV Expert.
    private final CallbackGenerationGate locationCallbackGeneration = new CallbackGenerationGate();
    private final CallbackGenerationGate statusCallbackGeneration = new CallbackGenerationGate();
    private final CallbackGenerationGate nmeaCallbackGeneration = new CallbackGenerationGate();
    private volatile boolean gnssCallbacksEnabled;
    private final Object notificationThrottleLock = new Object();

    private LocationListener locationListener;
    private GnssStatus.Callback gnssCallback;
    private OnNmeaMessageListener nmeaListener;
    // Références exactes enregistrées auprès de LocationManager. Les objets
    // délégués ci-dessus ne sont jamais passés directement au framework.
    private LocationListener registeredLocationListener;
    private GnssStatus.Callback registeredGnssCallback;
    private OnNmeaMessageListener registeredNmeaListener;

    // --- GNSS brut (mode Expert) ---
    // L'abonnement et le fichier appartiennent au service, jamais à l'Activity.
    // Il est activé uniquement sur demande explicite pour limiter le coût radio.
    private GnssMeasurementsEvent.Callback measurementsCallback;
    private boolean rawMeasurementsSubscribed = false;
    private volatile boolean rawMeasurementsLogRequested = false;
    private boolean rawMeasurementsSupportKnown = false;
    private boolean rawMeasurementsSupported = false;
    private volatile long lastRawMeasurementsElapsedMs = 0L;
    private long nextRawMeasurementsRetryElapsedMs = 0L;
    private static final long RAW_MEASUREMENTS_RETRY_MS = 5_000L;
    private static final long RAW_MEASUREMENTS_STALE_MS = 10_000L;
    private android.os.HandlerThread rawMeasurementsThread;
    private Handler rawMeasurementsHandler;
    private volatile RawMeasurementsCsvLogger rawCsvLogger;
    private volatile RawMeasurementsCsvLogger.Options activeRawCsvOptions;

    private volatile boolean running = false;
    private long lastNotifyMs = 0;

    // --- Politique d'arrière-plan (v5) ---
    private BackgroundPolicy.Policy policy = BackgroundPolicy.Policy.CONTINUE;
    /** Décisions « écran » et « politique » prises depuis deux threads différents :
     *  on les sérialise sur le thread principal pour ne pas se retrouver avec
     *  un état suspendu alors que l'écran est rallumé. */
    private final Object policyLock = new Object();

    /** Récepteur des événements d'écran. Enregistré dans onCreate, retiré
     *  dans onDestroy : c'est un état de service, pas d'Activity. */
    private BroadcastReceiver screenReceiver = null;

    // --- GPX ---
    /** Dernier fichier GPX connu (mis à jour après l'ouverture asynchrone). */
    private volatile File gpxFile = null;
    private volatile RecordingSegment activeSegment = null;
    /** Dernier segment fermé, utile si l'utilisateur arrête pendant une pause. */
    private volatile RecordingSegment lastClosedSegment = null;
    /** Identité unique du suivi logique courant ; change à chaque nouveau START. */
    private volatile String sessionId = "";
    private volatile long nextSegmentId = 0L;
    private volatile int gpxPointsInSegment = 0;

    /**
     * Politique de durabilité GPX :
     * - flush Java toutes les 2 s quand les fixes arrivent, et jalon de secours
     *   toutes les 15 s même si le GNSS devient silencieux ;
     * - sync() toutes les 30 s (demande au système de persister les données) ;
     * - flush + sync + close à chaque fermeture de segment.
     * Les sync sont exécutés sur ioFile, jamais sur le thread principal.
     */
    private static final long FLUSH_EVERY_MS = 2_000L;
    private static final long SYNC_EVERY_MS = 30_000L;
    /** Flush de secours même sans nouveau fix (perte de signal / immobilité). */
    private static final long IDLE_FLUSH_INTERVAL_MS = 15_000L;
    private volatile long lastPeriodicGpxFlushElapsedMs = 0L;
    /** Une carte SD/fournisseur bloqué ne doit pas faire croître la file sans limite. */
    private static final int MAX_GPX_QUEUE_DEPTH = 120;

    /** Contexte immuable d'un segment : toutes les tâches différées capturent
     *  cet objet, jamais des champs globaux susceptibles d'appartenir au
     *  segment suivant. L'URI SAF lui appartient jusqu'à la publication finale. */
    private static final class RecordingSegment {
        final long id;
        final String sessionId;
        final GpxCheckpoint checkpoint = new GpxCheckpoint();
        volatile File file;
        volatile File snapshotFile;
        final android.net.Uri destinationTree;
        final boolean publishEnabled;
        final GpxWriter.Version version;
        final int counterEpoch;
        /** Toutes les opérations sur le flux, y compris le repli, utilisent ce verrou. */
        final Object ioLock = new Object();
        final GpxSegmentIoState ioState = new GpxSegmentIoState();
        volatile FileOutputStream output;
        volatile BufferedWriter writer;
        volatile android.net.Uri document;
        volatile long lastFlushElapsedMs;
        volatile long lastSyncElapsedMs;
        int pointsAccepted;
        int pointsQueued;
        int pointsFailedAfterQueue;
        volatile int pointsWritten;
        volatile boolean acceptingPoints = true;
        volatile boolean closed;
        volatile boolean writeFailed;
        volatile boolean openFailed;

        RecordingSegment(long id, String sessionId, android.net.Uri tree,
                         GpxWriter.Version version, int counterEpoch, long nowElapsed) {
            this.id = id;
            this.sessionId = sessionId == null ? "" : sessionId;
            this.destinationTree = tree;
            this.publishEnabled = tree != null;
            this.version = version;
            this.counterEpoch = counterEpoch;
            this.lastFlushElapsedMs = nowElapsed;
            this.lastSyncElapsedMs = nowElapsed;
        }
    }

    // --- Publication vers le répertoire choisi par l'utilisateur ---
    private Handler ioPublish = null;
    private android.os.HandlerThread ioPublishThread = null;
    /** Cadence demandée : relue dans les préférences à chaque abonnement. */
    private volatile long locationIntervalMs = GnssAcquisitionConfig.DEFAULT_INTERVAL_MS;

    // --- Qualité du fix, perte et reprise de signal (v5.6) ---

    /**
     * La machine à états de la réception. C'est ELLE qui décide si une
     * donnée entre dans la trace. Avant, la seule règle était
     * « !Double.isNaN(latitude) » : toute position, même à 500 m de
     * précision ou vieille de dix minutes, était écrite.
     */
    private final FixQuality.Tracker fixTracker = new FixQuality.Tracker();

    /** Décideur d'annonces vocales (logique pure, testée). */
    private final LossAnnouncer.Policy lossPolicy = new LossAnnouncer.Policy();

    /**
     * FAUT-IL INSÉRER UNE COUPURE DE SEGMENT AVANT LE PROCHAIN POINT ?
     *
     * Positionné sur une perte de signal, consommé au premier point écrit
     * après la reprise. Sans cela, la trace relie l'entrée et la sortie du
     * tunnel par une ligne droite — et tous les outils calculent une
     * distance et une vitesse qui n'ont jamais existé.
     *
     * Ce drapeau est consommé UNE fois, à l'écriture du premier point de la
     * nouvelle réception : c'est pour cela que ce n'est pas un simple
     * booléen relu à chaque point. Un état « en coupure » resterait vrai et
     * insérerait une frontière devant chaque point suivant.
     */
    private boolean pendingSegmentBreak = false;

    /** Dernière valeur lue de « annoncer la reprise », pour ne reconfigurer
     *  la politique que si elle change. */
    private boolean voiceRecoveryOn = true;

    /**
     * Bit correspondant à un type de constellation GnssStatus.
     *
     * On ne stocke PAS le type Android tel quel : il va de 1 à 7, et
     * Integer.bitCount() compte les bits à 1. On remappe donc sur 0..6 pour
     * que les doublons de type ne soient jamais comptés deux fois.
     *
     * GNSS_CONSTELLATION_UNKNOWN vaut 0 : il prend le bit 6, ce qui ne pose
     * pas de problème de comptage (au pire une constellation de plus, donc
     * un minimum plus exigeant — jamais un faux « tout va bien »).
     */
    private static int bitForConstellation(int type) {
        switch (type) {
            case GnssStatus.CONSTELLATION_GPS:         return 1 << 0;
            case GnssStatus.CONSTELLATION_SBAS:        return 1 << 1;
            case GnssStatus.CONSTELLATION_GLONASS:     return 1 << 2;
            case GnssStatus.CONSTELLATION_QZSS:        return 1 << 3;
            case GnssStatus.CONSTELLATION_BEIDOU:      return 1 << 4;
            case GnssStatus.CONSTELLATION_GALILEO:     return 1 << 5;
            default:                                   return 1 << 6;
        }
    }

    /**
     * Politique d'espacement des points écrits (v5.10).
     *
     * RAPPEL DU RAISONNEMENT : distance OU temps, jamais ET. À l'arrêt, le
     * GNSS oscille de ±2-5 m ; sans critère de temps, une session immobile
     * de vingt minutes ne produirait AUCUN point et l'utilisateur croirait
     * que l'enregistrement s'est arrêté.
     */
    private final SamplePolicy.Session sampler = new SamplePolicy.Session();
    private int minDistanceM = SamplePolicy.DEFAULT_MIN_DISTANCE_M;
    private long minTimeMs = SamplePolicy.DEFAULT_MIN_TIME_MS;

    /** Mode actif et ses paramètres effectifs (v5.11). */
    private TrackMode.Mode trackMode = TrackMode.Mode.WALK;
    private TrackMode.Effective modeEff = TrackMode.resolve(TrackMode.Mode.WALK, 0, 0);

    /** Dernier instant (monotone) où une ancre « immobile » a été écrite. */
    private long lastAnchorElapsedMs = 0L;
    /** Instant du dernier point écrit, tous types confondus. */
    private long lastWriteElapsedMs = -1L;
    /** Ce point a été refusé par le seuil de précision du mode. */
    private boolean modeRejected = false;

    /**
     * Écoute des préférences, pour que le mode change SANS redémarrer.
     *
     * Les réglages sont modifiables en cours de session (exigence depuis
     * v5.4 : « préférences sauvées dès qu'elles sont modifiées »). Un mode
     * lu UNE FOIS au démarrage serait un mensonge : l'écran afficherait
     * « voiture, 50 m » pendant que le service écrirait un point tous les
     * 20 m. Les deux affichages doivent parler du même monde.
     */
    private android.content.SharedPreferences.OnSharedPreferenceChangeListener prefsListener;
    /** Une ancre « immobile » est due à ce point. */
    private boolean anchorPending = false;

    /** Le nettoyage asynchrone est suivi sans bloquer onDestroy(). */
    private final AtomicInteger cleanupTasksInFlight = new AtomicInteger();
    private volatile boolean serviceDestroying = false;
    /**
     * L'état UI recording est remis à false pendant onDestroy(), mais le
     * snapshot durable final doit conserver le fait qu'une trace était active
     * au moment d'une destruction non volontaire.
     */
    private volatile boolean serviceDestroyedWhileRecording = false;

    /** Instant (monotone) de réception du dernier GnssStatus, 0 si jamais. */
    private volatile long gnssStatusElapsedMs = 0L;

    /**
     * Age maximal d'un GnssStatus pour servir de source au nombre de
     * satellites UTILISES (ms).
     *
     * POURQUOI UN AGE MAXIMAL : sans borne, si le recepteur cesse d'emettre
     * le statut (bug de driver), on jugerait un fix neuf avec des donnees
     * vieilles de dix minutes : soit on accepte un fix 2D sur la foi d'un
     * "8 satellites" perime, soit on refuse un bon fix sur la foi d'un "3"
     * perime. Dans les deux cas la decision serait fausse, et silencieusement.
     *
     * La fenêtre vaut au minimum 3 s à 1 Hz et s'adapte à l'intervalle
     * demandé (3 × intervalle) pour les cadences plus lentes prévues ensuite.
     * Au-delà, on préfère ne pas juger (0 = inconnu) plutôt que de juger faux.
     */
    private long gnssStatusMaxAgeMs() {
        return GnssAcquisitionConfig.statusFreshnessMs(locationIntervalMs);
    }

    /** Synthèse vocale. Null si indisponible ou désactivée. */
    private VoiceAlert voice = null;

    /**
     * WATCHDOG. Un récepteur muet n'envoie AUCUN événement : sans une
     * horloge indépendante, la perte de signal est indétectable.
     *
     * Il tourne toutes les secondes et fait trois choses :
     *   1. avancer la machine à états (tick) ;
     *   2. décider d'une éventuelle annonce vocale ;
     *   3. publier l'état vers l'UI, pour qu'elle grise les données.
     *
     * POURQUOI ICI ET PAS DANS onLocationChanged : c'est précisément quand
     * plus aucun callback n'arrive qu'il faut agir. Un watchdog qui
     * dépendrait des callbacks serait aveugle exactement au moment utile.
     */
    private final Runnable signalWatchdog = new Runnable() {
        @Override
        public void run() {
            if (!running || serviceDestroying) return;
            // HORLOGE MONOTONE. Toutes les DURÉES viennent d'ici, jamais de
            // System.currentTimeMillis() : l'heure murale peut reculer d'une
            // heure (réglage manuel, synchronisation réseau) et ferait alors
            // croire à une perte de signal de -3600 s, ou à des points tous
            // périmés. elapsedRealtime() ne recule jamais.
            long now = android.os.SystemClock.elapsedRealtime();

            // Une permission révoquée n'est PAS une perte RF : réessayer un
            // abonnement toutes les 5 s ne peut rien résoudre et laisserait
            // un FGS inutilement vivant. On ferme le GPX, conserve un journal
            // « interrompu », puis on retire proprement le premier plan.
            if (ContextCompat.checkSelfPermission(TrackingService.this,
                    Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                stopBecauseLocationPermissionWasRevoked();
                return;
            }

            if (!rawMeasurementsLogRequested && !state.isExpertPageVisible()
                    && rawMeasurementsSubscribed) {
                unregisterRawMeasurementsCallback();
                if (!state.isRawCsvRequested()) {
                    state.setRawCsvStatus("Aperçu GNSS brut en pause : page Expert masquée.");
                    state.notifyChanged();
                }
            }

            boolean gpsProviderEnabled;
            try {
                gpsProviderEnabled = locationManager.isProviderEnabled(
                        android.location.LocationManager.GPS_PROVIDER);
            } catch (RuntimeException e) {
                gpsProviderEnabled = false;
            }
            if (gpsProviderEnabled && !state.isSuspended()) {
                // Réessayer uniquement les abonnements qui ont réellement
                // échoué (Location, GnssStatus ou NMEA). Ne jamais supprimer un
                // LocationListener accepté simplement parce qu'il n'y a pas
                // de fix : un tunnel ne justifie pas de réinitialiser le GNSS.
                boolean missingSubscription = !locationSubscribed
                        || !gnssStatusSubscribed || !nmeaSubscribed;
                if (missingSubscription && now >= nextSubscriptionRetryElapsedMs) {
                    subscribeSensors();
                }
                if ((rawMeasurementsLogRequested || state.isExpertPageVisible())
                        && !rawMeasurementsSubscribed && now >= nextRawMeasurementsRetryElapsedMs) {
                    subscribeRawMeasurementsIfNeeded();
                }
                if ((rawMeasurementsLogRequested || state.isExpertPageVisible()) && rawMeasurementsSubscribed
                        && lastRawMeasurementsElapsedMs > 0L
                        && now - lastRawMeasurementsElapsedMs >= RAW_MEASUREMENTS_STALE_MS) {
                    // Ce statut n'arrête pas l'enregistrement : un callback
                    // silencieux peut être une restriction de livraison Android,
                    // un fournisseur désactivé ou l'absence de données exploitables.
                    if (state.getRawCsvStatus() == null
                            || !state.getRawCsvStatus().startsWith("Aucun")) {
                        state.setRawCsvStatus(rawMeasurementsLogRequested
                                ? "Aucune mesure brute reçue depuis 10 s ; disponibilité arrière-plan non garantie par Android."
                                : "Aucun événement de mesure brute reçu depuis 10 s ; vérifie le GPS et le support du chipset.");
                        state.notifyChanged();
                    }
                }
                if (locationSubscribed) {
                    long reference = lastLocationCallbackElapsedMs > 0L
                            ? lastLocationCallbackElapsedMs
                            : locationSubscriptionSinceElapsedMs;
                    if (reference > 0L && now - reference >= LOCATION_CALLBACK_STALE_MS) {
                        String current = state.getTrackingNotice();
                        if (current == null || current.startsWith("Position GNSS :")) {
                            state.setTrackingNotice("Position GNSS : aucun nouveau callback depuis 30 s ; abonnement conservé, attente du récepteur (tunnel ou réception faible possible).");
                        }
                    }
                }
            }
            synchronized (gnssProcessingLock) {
            boolean changed = fixTracker.tick(now, gpsProviderEnabled);
            boolean nowOk = fixTracker.isOk();

            state.setSignalOk(nowOk);
            state.setFixAgeMs(fixTracker.ageSinceLastGoodMs(now));
            state.setLostForMs(fixTracker.lostForMs(now));

            // L'annonce vocale ne dépend PAS de changed : la grâce et le
            // rythme des rappels sont gérés par la politique, pas par les
            // transitions d'état.
            // « userDisabled » couvre TROIS décisions volontaires de
            // l'utilisateur : GPS coupé dans les réglages Android, session
            // suspendue par la politique d'économie, et enregistrement
            // arrêté. Dans les trois cas, se taire est le bon comportement.
            // « userDisabled » ne couvre QUE les décisions qui rendent
            // l'alerte absurde : session suspendue par l'économie de
            // batterie, ou GPS coupé par l'utilisateur. L'ENREGISTREMENT
            // N'ENTRE PAS EN COMPTE, et c'est volontaire : l'exigence est
            // que toute perte de réception soit signalée. Un utilisateur
            // qui utilise l'app comme simple afficheur de position veut
            // savoir que son signal tombe.
            //
            // (Le commentaire précédent affirmait inclure « enregistrement
            // arrêté ». C'était FAUX : le code ne l'a jamais testé. J'ai
            // d'abord « corrigé » le code pour l'y conformer, puis j'ai
            // relu l'exigence — c'est le commentaire qui était à corriger,
            // pas le comportement. Une alerte qui se tait pendant une perte
            // réelle serait exactement le défaut qu'on veut éviter.)
            boolean userDisabled = state.isSuspended() || !gpsProviderEnabled;
            // La configuration est relue à chaque tick : modifier le rythme
            // dans les réglages prend effet immédiatement, sans redémarrer la
            // session. Quatre lectures de SharedPreferences par seconde sont
            // négligeables, et cela évite un « pourquoi mon réglage ne
            // s'applique pas ? ».
            // `this` désignerait le Runnable : il faut qualifier le service.
            boolean voiceOn = Prefs.readVoiceAlert(TrackingService.this);
            boolean recoveryOn = Prefs.readVoiceRecovery(TrackingService.this);
            long repeat = Prefs.readVoiceRepeatMs(TrackingService.this);
            int cap = Prefs.readVoiceMaxCount(TrackingService.this);
            if (voiceOn != lossPolicy.isEnabled()
                    || recoveryOn != voiceRecoveryOn
                    || repeat != lossPolicy.repeatMs()
                    || cap != lossPolicy.maxAnnouncements()) {
                lossPolicy.configure(voiceOn, recoveryOn, repeat, cap);
                voiceRecoveryOn = recoveryOn;
            }

            LossAnnouncer.Action a = lossPolicy.tick(
                    fixTracker.isLost(), fixTracker.lostForMs(now), userDisabled, now);
            if (a != LossAnnouncer.Action.SILENT) {
                announce(a, fixTracker.lostForMs(now));
            }

            if (changed) {
                // Une transition vers NO_SIGNAL est une coupure de trace :
                // le prochain point écrit devra ouvrir un NOUVEAU <trkseg>.
                if (fixTracker.isLost()) {
                    pendingSegmentBreak = true;
                    // Après une coupure, le prochain point est toujours écrit :
                    // sinon un utilisateur immobile 10 minutes ET à 40 m du
                    // dernier point n'aurait rien dans son fichier à la reprise.
                    sampler.breakSegment();
                }
                // Transition d'état : on sauvegarde la session et on
                // rafraîchit la notification (elle doit dire « signal perdu »).
                flushNow();
                persistSession();
                updateNotification();
                state.notifyChanged();
            }
            }
            ui.postDelayed(this, SIGNAL_WATCHDOG_MS);
        }
    };
    private static final long SIGNAL_WATCHDOG_MS = 1000L;

    private final Handler ui = new Handler(Looper.getMainLooper());

    /**
     * ARRÊT DEMANDÉ PAR L'UTILISATEUR (bouton ARRÊTER de la notification).
     *
     * Point crucial : on ne peut pas simplement appeler stopTracking() dans
     * onDestroy(). Quand un service est tué par le système (mémoire, OEM
     * agressif), onDestroy() NE PASSE PAS — mais quand il passe, il ne faut
     * PAS faire comme si l'utilisateur avait arrêté. Sinon on efface le
     * journal et la « session interrompue » est invisible au redémarrage.
     * Un booléen explicite dit laquelle des deux situations on est en train
     * de vivre.
     */
    private boolean stopRequested = false;
    /** Dernier startId livré : stopSelf(startId) protège les redémarrages rapprochés. */
    private int latestStartId = 0;

    private void stopSelfSafely() {
        if (latestStartId > 0) stopSelf(latestStartId);
        else stopSelf();
    }

    /**
     * Sommes-nous actuellement abonnés aux capteurs ?
     *
     * subscribeSensors() était appelable deux fois sans conséquence (Android
     * déduplique les callbacks identiques), mais unsubscribeSensors() en plein
     * millieu d'une rafale de points reste coûteux, et surtout : rien ne
     * garantissait que l'état « suspendu » et l'état « abonné » restent
     * cohérents. Un drapeau explicite les rend vérifiables.
     */
    private boolean subscribed = false;
    private boolean locationSubscribed = false;
    private boolean gnssStatusSubscribed = false;
    private boolean nmeaSubscribed = false;
    private long nextSubscriptionRetryElapsedMs = 0L;
    private static final long SUBSCRIPTION_RETRY_MS = 5_000L;
    /** Depuis quand l'abonnement de localisation courant est installé. */
    private volatile long locationSubscriptionSinceElapsedMs = 0L;
    /** Dernier callback de position reçu (temps monotone), 0 si aucun. */
    private volatile long lastLocationCallbackElapsedMs = 0L;
    /** Âge au-delà duquel on signale l'absence de callback, sans réinitialiser
     * un abonnement accepté : un silence peut simplement être un tunnel ou
     * une mauvaise visibilité du ciel. */
    private static final long LOCATION_CALLBACK_STALE_MS = 30_000L;
    private static final String SUBSCRIPTION_NOTICE_PREFIX = "Abonnements GNSS :";

    /**
     * ÉCRITURES FICHIER HORS DU THREAD PRINCIPAL.
     *
     * On the main thread, une écriture/flush peut bloquer (stockage lent,
     * carte saturée par le système). À 1 point/seconde, ça finit par produire
     * des « skipped frames », puis un ANR. Un HandlerThread dédié coûte
     * 3 lignes et supprime tout risque.
     *
     * Deux files d'attente, à dessein :
     *   - ioFile : les points GPS dans l'ordre strict (head-of-line obligatoire) ;
     *   - ioMeta : le journal de session, qui ne doit JAMAIS attendre derrière
     *              des centaines d'écritures de points.
     */
    private android.os.HandlerThread ioThread;
    private Handler ioFile;
    private android.os.HandlerThread ioMetaThread;
    private Handler ioMeta;
    private volatile boolean ioShutdownRequested = false;
    private boolean runtimeInfrastructureInitialized = false;
    private boolean runtimeInfrastructureFailed = false;

    /** Dernière notification construite : on la réutilise dans les callbacks. */
    private Notification currentNotification = null;

    /** Sommes-nous déjà passés en premier plan ? startForeground() est
     *  idempotent, mais chaque appel renvoie une notification au système. */
    private boolean foreground = false;

    /** Signature du dernier contenu notifié. Sert à éviter un IPC vers
     *  system_server chaque seconde quand rien n'a changé. */
    private String lastNotificationSignature = null;

    /** Rafraîchissement régulier de la notification, indépendant du GPS. */
    private final Runnable notificationTick = new Runnable() {
        @Override
        public void run() {
            if (!running || serviceDestroying) return;
            updateNotification();
            // Les flush déclenchés par appendPoint() suffisent tant que les
            // fixes arrivent. Ce jalon indépendant garantit aussi un flush
            // et, toutes les 30 s, un sync lorsque le GNSS devient silencieux.
            long nowElapsed = android.os.SystemClock.elapsedRealtime();
            if (state.isRecording() && activeSegment != null
                    && (lastPeriodicGpxFlushElapsedMs == 0L
                        || nowElapsed - lastPeriodicGpxFlushElapsedMs >= IDLE_FLUSH_INTERVAL_MS)) {
                lastPeriodicGpxFlushElapsedMs = nowElapsed;
                flushNow();
            }
            ui.postDelayed(this, NOTIFICATION_TICK_MS);
        }
    };
    private static final long NOTIFICATION_TICK_MS = 1000L;

    // ==================================================================
    // Cycle de vie du service
    // ==================================================================

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        buildCallbacks();
        createNotificationChannel();

        // Le délai de startForegroundService() commence avant onCreate().
        // On garde donc onCreate léger : les HandlerThreads, préférences et
        // accès au journal seront initialisés juste APRÈS la promotion FGS,
        // dans onStartCommand().
    }

    private boolean ensureRuntimeInfrastructure() {
        if (runtimeInfrastructureInitialized) return true;
        if (runtimeInfrastructureFailed || ioShutdownRequested) return false;

        try {
            // Les callbacks de localisation, satellites et NMEA sont traités
            // dans une file sérielle commune, distincte des workers de stockage.
            gnssEventPipeline = new GnssEventPipeline();
            if (!gnssEventPipeline.start()) {
                throw new IllegalStateException("Impossible de démarrer la file GNSS");
            }

            // I/O GPX : points sérialisés dans une file stricte.
            ioThread = new android.os.HandlerThread(
                    "gpx-io", android.os.Process.THREAD_PRIORITY_BACKGROUND);
            ioThread.start();
            ioFile = new Handler(ioThread.getLooper());

            // Métadonnées et post-traitement isolés des écritures de points.
            ioMetaThread = new android.os.HandlerThread("gpx-meta",
                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
            ioMetaThread.start();
            ioMeta = new Handler(ioMetaThread.getLooper());

            // Publication SAF dans son propre worker pour ne pas bloquer les deux autres.
            ioPublishThread = new android.os.HandlerThread("gpx-publish",
                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
            ioPublishThread.start();
            ioPublish = new Handler(ioPublishThread.getLooper());

            policy = Prefs.readPolicy(this);
            state.setPolicy(policy);
            state.setScreenStateKnown(false);
            state.setScreenOn(true); // corrigé par le prochain broadcast écran
            registerScreenReceiver();
            registerPrefsListener();
            runtimeInfrastructureInitialized = true;
            checkPreviousSession();
            return true;
        } catch (RuntimeException error) {
            // En cas d'échec partiel (thread impossible à démarrer, receiver
            // refusé, préférences indisponibles), ne pas laisser un FGS actif
            // sans ses workers ni retenter de créer des threads en doublon.
            runtimeInfrastructureFailed = true;
            runtimeInfrastructureInitialized = false;
            unregisterScreenReceiver();
            unregisterPrefsListener();
            if (gnssEventPipeline != null) {
                gnssEventPipeline.stop();
                gnssEventPipeline = null;
            }
            android.util.Log.e("TrackingService",
                    "Initialisation des workers du suivi impossible", error);
            shutdownIoThreadsAfterDrain();
            return false;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        String action = intent != null ? intent.getAction() : ACTION_START;

        // OBLIGATOIRE : passer en premier plan dans les 5 s, sinon le système
        // tue le service (et lève une ANR). On le fait avant tout traitement long.
        //
        // try/catch indispensable, et voici les cas RÉELS qui lèvent :
        //   - API 34 : l'utilisateur retire la permission de localisation
        //     pendant que la session tourne -> SecurityException ;
        //   - API 31+ : l'app est restreinte (background restricted) et
        //     n'a plus le droit de lancer un FGS depuis l'arrière-plan ;
        //   - API 34 : FGS type "location" lancé alors que l'app est en
        //     arrière-plan sans exemption.
        // Sans ce catch, c'est un CRASH de l'app ; avec, c'est un arrêt propre
        // et un message compréhensible.
        // Intention posée AVANT startForeground : entre startForeground() et
        // startTracking(), l'UI peut déjà se rafraîchir (tick de 500 ms) et
        // afficherait « DÉMARRER » alors que le service vient de démarrer.
        // Cas limite : ACTION_STOP reçu alors que le service ne tourne pas.
        // On ne passe PAS en premier plan pour s'arrêter aussitôt : cela
        // ferait clignoter la notification et, sur certaines versions,
        // laisse une notification résiduelle. Rien à arrêter = on sort.
        if (ACTION_STOP.equals(action) && !running && !foreground) {
            stopSelfSafely();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            state.setUserWantsTracking(true);
        }

        try {
            // startForeground n'est nécessaire qu'une fois ; ensuite les
            // mises à jour passent par NotificationManager.notify().
            if (!foreground) {
                startForegroundCompat(buildNotification());
                foreground = true;
                lastNotificationSignature = notificationSignature();
            }
        } catch (Exception e) {
            // Attention : si startForeground() a partiellement réussi avant de
            // lever (peu probable, mais possible sur certains OEM), une
            // notification orpheline reste dans le volet. On tente le retrait,
            // et on ignore l'échec : on est déjà dans un chemin d'erreur.
            // Ne pas dépendre du booléen `foreground` : si startForeground()
            // a partiellement réussi avant de lever, ce booléen n'a pas encore
            // été mis à true. Le retrait direct évite une notification orpheline.
            foreground = false;
            lastNotificationSignature = null;
            try { stopForeground(Service.STOP_FOREGROUND_REMOVE); } catch (Exception ignored) { }
            state.setTrackingNotice("Service refusé par Android : " + e.getClass().getSimpleName()
                    + ". Vérifiez la permission de localisation.");
            state.setUserWantsTracking(false);
            state.notifyChanged();
            stopSelfSafely();
            return START_NOT_STICKY;
        }

        // À partir d'ici le service est déjà au premier plan : les tâches plus
        // coûteuses (démarrage des workers, lecture du journal) ne consomment
        // plus le délai critique laissé à startForegroundService().
        if (!ensureRuntimeInfrastructure()) {
            state.setTrackingNotice("Initialisation du service impossible : suivi non démarré. Fermez puis relancez l'application.");
            state.setUserWantsTracking(false);
            state.notifyChanged();
            stopForegroundCompat(true);
            stopSelfSafely();
            return START_NOT_STICKY;
        }

        if (ACTION_STOP.equals(action)) {
            stopRequested = true;   // arrêt VOULU : le journal doit être effacé
            stopTracking();
            stopForegroundCompat(true);
            stopSelfSafely();
            return START_NOT_STICKY;
        }

        // Toutes les actions autres que START peuvent arriver alors qu'aucune
        // session n'est active (l'utilisateur change un réglage avant d'avoir
        // démarré). Dans ce cas on ne laisse PAS un service de premier plan
        // tourner à vide avec une notification permanente : on s'arrête.
        if (ACTION_RAW_LOG_START.equals(action)) {
            startRawMeasurementsLogging();
            return finishIfIdle();
        }

        if (ACTION_RAW_LOG_STOP.equals(action)) {
            stopRawMeasurementsLogging("Arrêt demandé depuis le mode Expert", null);
            return finishIfIdle();
        }

        if (ACTION_RAW_OPTIONS_CHANGED.equals(action)) {
            if (running && !state.isSuspended()
                    && (rawMeasurementsLogRequested || state.isExpertPageVisible())) {
                unregisterRawMeasurementsCallback();
                subscribeRawMeasurementsIfNeeded();
            }
            return finishIfIdle();
        }

        if (ACTION_RECORD.equals(action)) {
            startRecording();
            return finishIfIdle();
        }

        if (ACTION_UNRECORD.equals(action)) {
            stopRecording();
            return finishIfIdle();
        }

        if (ACTION_SUSPEND.equals(action)) {
            if (running && !state.isSuspended()) suspendTracking("manuel");
            return finishIfIdle();
        }

        if (ACTION_RESUME.equals(action)) {
            if (running && state.isSuspended()) resumeTracking("manuel");
            return finishIfIdle();
        }

        if (ACTION_SET_POLICY.equals(action)) {
            BackgroundPolicy.Policy p = BackgroundPolicy.Policy.fromId(
                    intent != null ? intent.getIntExtra(EXTRA_POLICY_ID,
                            BackgroundPolicy.Policy.CONTINUE.id)
                            : BackgroundPolicy.Policy.CONTINUE.id);
            applyPolicy(p);
            return finishIfIdle();
        }

        // ACTION_START : démarrer la session si ce n'est pas déjà fait.
        // Si elle tourne déjà, on rafraîchit quand même la notification : le
        // second appui sur DÉMARRER (double tap, ou retour depuis la
        // notification) doit laisser l'appareil dans un état cohérent.
        if (!running) {
            startTracking();
        }
        if (running) updateNotification();
        // On vient d'être promu au premier plan : l'écran est forcément allumé
        // (on ne peut pas lancer l'app écran éteint). Ça corrige le cas d'un
        // démarrage sans qu'aucun broadcast d'écran ne soit encore arrivé.
        if (!state.isScreenStateKnown()) {
            state.setScreenOn(true);
            state.setScreenStateKnown(true);
        }
        return START_NOT_STICKY;
    }

    /** Arrêt non volontaire et explicite lorsque la permission fine est retirée. */
    private void stopBecauseLocationPermissionWasRevoked() {
        if (!running) return;
        running = false;
        state.setUserWantsTracking(false);
        state.setSignalOk(false);
        state.setHasFix(false);
        state.setTrackingNotice("Permission de localisation retirée : suivi arrêté. La trace locale est conservée jusqu'au dernier point écrit.");

        ui.removeCallbacks(notificationTick);
        ui.removeCallbacks(signalWatchdog);
        stopRawMeasurementsLogging("Permission de localisation retirée", null);
        unsubscribeSensors();

        boolean wasRecording = state.isRecording();
        RecordingSegment segmentToProcess = wasRecording
                ? closeRecordingSegment() : lastClosedSegment;
        state.setRecording(false);
        state.setSuspended(false);
        // Ne PAS effacer le journal comme pour un arrêt volontaire : au
        // prochain démarrage, SessionJournal signalera cette interruption.
        persistSession();
        state.notifyChanged();
        if (wasRecording) runPostProcess(segmentToProcess);

        stopForegroundCompat(true);
        stopSelfSafely();
    }

    /** Termine l'action, et éteint le service s'il n'a plus de session à porter. */
    private int finishIfIdle() {
        if (!running) {
            stopForegroundCompat(true);
            stopSelfSafely();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        serviceDestroyedWhileRecording = !stopRequested && state.isRecording();
        serviceDestroying = true; // refuser les événements GNSS déjà en file
        // ARRÊT SUBI ou VOLONTAIRE ? Toute la différence est ici :
        //   - stopRequested  -> l'utilisateur a appuyé sur ARRÊTER : on efface
        //                       le journal, la session est terminée « proprement » ;
        //   - !stopRequested -> le système nous tue : on ferme le fichier GPX
        //                       proprement (pied + flush) MAIS ON LAISSE LE
        //                       JOURNAL marqué dirty, pour que le prochain
        //                       démarrage dise « session interrompue ».
        // La v5 précédente appelait stopTracking() ici : elle effaçait le
        // journal dans TOUS les cas, donc le message « session interrompue »
        // ne pouvait jamais s'afficher. Le mécanisme était mort-né.
        unregisterScreenReceiver();
        unregisterPrefsListener();
        ui.removeCallbacks(notificationTick);
        ui.removeCallbacks(signalWatchdog);
        if (voice != null) { voice.shutdown(); voice = null; }

        if (running) {
            stopRawMeasurementsLogging("Service détruit : finalisation du CSV brut", null);
            unsubscribeSensors();      // remet subscribed = false, mesures comprises
            if (state.isRecording()) closeRecordingSegment();   // flush + close
            if (stopRequested) {
                clearSessionJournal();
            } else {
                persistSession();          // reste dirty=true : interruption constatée
            }
            running = false;
            state.setUserWantsTracking(false);
            state.setRecording(false);
            state.setSuspended(false);
            state.setSignalOk(false);
            if (!stopRequested) {
                state.setTrackingNotice("Service interrompu par Android ou le système ; la session reste marquée à vérifier au prochain démarrage.");
            }
            state.notifyChanged();
        }
        stopRawMeasurementsLogging("Service détruit : finalisation du CSV brut", null);
        unregisterRawMeasurementsCallback();
        if (gnssEventPipeline != null) {
            gnssEventPipeline.stop();
            gnssEventPipeline = null;
        }
        if (rawCsvLogger != null) rawCsvLogger.shutdown();
        if (rawMeasurementsThread != null) {
            rawMeasurementsThread.quitSafely();
            rawMeasurementsThread = null;
            rawMeasurementsHandler = null;
        }
        stopForegroundCompat(true);

        // onDestroy() ne doit JAMAIS attendre une E/S : il s'exécute sur le
        // thread principal et un stockage lent pourrait bloquer l'interface.
        // Les tâches déjà mises en file sont drainées par quitSafely() après
        // leur achèvement ; le GPX brut reste prioritaire sur le nettoyage.
        if (cleanupTasksInFlight.get() == 0) {
            shutdownIoThreadsAfterDrain();
        }

        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // service non lié : l'UI passe par TrackingState
    }

    // ==================================================================
    // Notification
    // ==================================================================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // POURQUOI PAS D'IMPORT : NotificationChannel est une classe API 26.
            // Un « import android.app.NotificationChannel; » en tête de fichier
            // rend la classe visible du vérificateur d'API du build tools
            // (« Call requires API level 26 ») et, sur Android 7.0, la classe
            // est absente du runtime : le premier accès lève NoClassDefFoundError.
            // En la référençant en pleine qualification DANS le bloc gardé par
            // SDK_INT >= O, elle n'est chargée que sur les systèmes qui l'ont.
            // (Vérifié : compile contre le vrai android.jar API 24.)
            android.app.NotificationChannel ch = new android.app.NotificationChannel(
                    CHANNEL_ID, getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW);   // LOW = pas de son
            ch.setDescription(getString(R.string.notif_channel_desc));
            ch.setShowBadge(false);
            NotificationManager nm = (NotificationManager)
                    getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, TrackingService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String text;
        if (state.isSuspended()) {
            // On ne ment pas à l'utilisateur : s'il a choisi l'économie
            // batterie et que l'écran est éteint, la notification dit
            // explicitement que la trace est en pause, pas « active ».
            text = getString(R.string.notif_paused);
        } else if (state.getTrackPoints() > 0) {
            text = String.format(Locale.FRANCE, "Enregistrement · %d points · %d satellites",
                    state.getTrackPoints(), state.getSatellitesUsed());
        } else {
            text = String.format(Locale.FRANCE, "Suivi · %d satellites", state.getSatellitesUsed());
        }

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                // Petite icône MONOCHROME, sinon Android la réduit à une tache
                // blanche (il ne garde que le canal alpha de l'image).
                .setSmallIcon(R.drawable.ic_stat_gps)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setContentTitle(getString(state.isSuspended()
                        ? R.string.notif_title_paused : R.string.notif_title))
                .setContentText(text)
                .setContentIntent(pi)
                .addAction(0, getString(R.string.btn_stop), stopPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)     // ne rejoue pas le son à chaque mise à jour
                .setPriority(NotificationCompat.PRIORITY_LOW);

        // API 31+ : la notification peut proposer de suspendre/reprendre
        // directement depuis le volet, sans rouvrir l'app. Utile et non
        // intrusif : c'est le même geste que « pause » sur un lecteur audio.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent toggle = new Intent(this, TrackingService.class).setAction(
                    state.isSuspended() ? ACTION_RESUME : ACTION_SUSPEND);
            PendingIntent togglePi = PendingIntent.getService(
                    this, 2, toggle,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(0, getString(state.isSuspended()
                            ? R.string.notif_resume : R.string.notif_pause),
                    togglePi);
        }
        return b.build();
    }

    /**
     * Android exige que startForeground() reçoive une notification VALIDE et
     * que son petit icône soit une ressource de l'app. Si l'icône est absente
     * ou invalide, c'est une RuntimeException à l'exécution (« invalid
     * notification ») — piège classique quand on change le small icon.
     * On la valide donc avant, et on échoue proprement plutôt que de crasher.
     */
    private static boolean isUsableNotification(Notification n) {
        return n != null
                && n.getSmallIcon() != null
                && n.extras != null
                && n.extras.getCharSequence(Notification.EXTRA_TITLE) != null;
    }

    /**
     * Indique si la notification de suivi peut apparaître dans le tiroir.
     * Une valeur false ne bloque JAMAIS le suivi : Android conserve l'entrée
     * du service dans « Applications actives » même si POST_NOTIFICATIONS ou
     * le canal propre à l'application est désactivé.
     */
    public static boolean areTrackingNotificationsEnabled(Context context) {
        if (context == null) return false;
        try {
            androidx.core.app.NotificationManagerCompat compat =
                    androidx.core.app.NotificationManagerCompat.from(context);
            if (!compat.areNotificationsEnabled()) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationManager manager = (NotificationManager)
                        context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (manager == null) return false;
                android.app.NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
                // Le canal n'existe pas encore avant la première création du
                // service : il sera créé dans onCreate(), ce n'est pas un refus.
                return channel == null
                        || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
            }
            return true;
        } catch (RuntimeException error) {
            // Une erreur de lecture du réglage ne doit pas bloquer le GPS.
            return true;
        }
    }

    @SuppressWarnings("deprecation")
    private void startForegroundCompat(Notification n) {
        if (!isUsableNotification(n)) {
            // Ne jamais appeler startForeground avec une notification
            // inexploitable : le service serait tué immédiatement.
            throw new IllegalStateException("Notification de service invalide");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29+ : il faut déclarer le TYPE de premier plan
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    /**
     * Retire la notification de premier plan.
     *
     * stopForeground(boolean) est DÉPRÉCIÉ depuis l'API 33 : la surcharge
     * entière est plus explicite et évite l'ambiguïté du booléen (true =
     * retirer, ce qui n'est pas évident à la lecture). Elle existe depuis
     * l'API 24, donc notre minSdk, sans garde.
     */
    private void stopForegroundCompat(boolean removeNotification) {
        if (!foreground) return;
        foreground = false;
        lastNotificationSignature = null;
        stopForeground(removeNotification
                ? Service.STOP_FOREGROUND_REMOVE
                : Service.STOP_FOREGROUND_DETACH);
    }

    private void updateNotification() {
        if (!running) return;   // ne pas faire clignoter une notification morte

        // Optimisation qui compte pour la batterie : sans ce test, on envoie
        // une notification au système TOUTES LES SECONDES, même quand le
        // nombre de satellites n'a pas bougé (écran éteint, intérieur). Chaque
        // notify() est un aller-retour IPC vers system_server.
        String signature = notificationSignature();
        if (signature.equals(lastNotificationSignature)) return;

        NotificationManager nm = (NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
        Notification n = buildNotification();
        lastNotificationSignature = signature;
        currentNotification = n;
        if (nm != null) nm.notify(NOTIFICATION_ID, n);
    }

    /** Tout ce qui, s'il change, doit provoquer une mise à jour visible. */
    private String notificationSignature() {
        return state.isSuspended() + "|" + state.getTrackPoints() + "|" + state.getSatellitesUsed();
    }

    /**
     * Ancien rafraîchissement « opportuniste » (appelé depuis onLocationChanged).
     * Conservé pour ne rien casser, mais ce n'est PLUS le mécanisme principal :
     * si le GPS perd le fix (intérieur, tunnel), plus aucun callback n'arrive
     * et la notification restait figée sur « 0 satellites » pendant des heures.
     * Le rafraîchissement garanti est notificationTick, toutes les secondes.
     */
    private void maybeUpdateNotification() {
        // Throttle = une DURÉE, donc horloge monotone. Avec l'heure murale,
        // un recul d'horloge rendait `now - lastNotifyMs` négatif et la
        // notification restait figée une heure durant.
        long now = android.os.SystemClock.elapsedRealtime();
        boolean due;
        synchronized (notificationThrottleLock) {
            due = now - lastNotifyMs >= 1000L;
            if (due) lastNotifyMs = now;
        }
        if (due) ui.post(() -> updateNotification());
    }

    // ==================================================================
    // Démarrage / arrêt du suivi
    // ==================================================================

    @SuppressLint("MissingPermission")
    private void startTracking() {
        if (running) return;

        // Les deux gardes qui suivent appelaient stopSelf() : problème, on
        // venait juste d'appeler startForeground() juste avant. Un FGS qui
        // s'auto-arrête sans retirer sa notification laisse une notification
        // fantôme dans le volet, que seul un reboot enlève. On termine donc
        // proprement (notification retirée + message clair à l'utilisateur).
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            state.setTrackingNotice("Permission de localisation manquante");
            state.setUserWantsTracking(false);
            state.notifyChanged();
            stopForegroundCompat(true);
            stopSelfSafely();
            return;
        }
        boolean gpsEnabled = false;
        try {
            gpsEnabled = locationManager != null
                    && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (RuntimeException providerError) {
            state.setTrackingNotice("État du fournisseur GPS indisponible : "
                    + providerError.getClass().getSimpleName());
        }
        if (!gpsEnabled) {
            if (state.getTrackingNotice() == null) {
                state.setTrackingNotice("GPS désactivé ou indisponible dans les réglages du téléphone");
            }
            state.setUserWantsTracking(false);
            state.setSignalOk(false);
            state.notifyChanged();
            stopForegroundCompat(true);
            stopSelfSafely();
            return;
        }

        // Un arrêt explicite suivi très rapidement d'un nouveau démarrage
        // peut être livré au même objet Service avant onDestroy(). Une ancienne
        // valeur true ferait alors effacer le journal de la nouvelle session.
        stopRequested = false;
        sessionId = SessionJournal.newSessionId();
        running = true;
        state.setUserWantsTracking(true);
        state.resetSession(System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime());
        state.setGpxSegment(0);
        state.clearExpertNmeaLines();
        state.setRawMeasurementsPreview("");
        state.setRawMeasurementsStatusLastCallback("");
        state.setGpxError(null);
        state.setTrackingNotice(null);
        state.setGpxDurabilityWarning(null);
        state.setLastGpxFile(null);
        state.setTrackPoints(0);
        state.setRecording(false);
        // Un nouveau suivi ne doit pas effacer une notice d'interruption
        // concernant la trace précédente : elle reste utile à l'utilisateur.
        // Un nouveau démarrage GPX, lui, la remplacera au moment opportun.
        // Un nouveau suivi ne doit jamais hériter du nom/fichier du dernier
        // segment fermé dans cette même instance Service. Le post-traitement
        // éventuel a déjà capturé son File dans une variable locale.
        if (activeSegment == null) {
            gpxFile = null;
            lastClosedSegment = null;
            gpxPointsInSegment = 0;
        }

        synchronized (gnssProcessingLock) {
            fixTracker.reset();
            lossPolicy.reset();
            sampler.reset();
            pendingSegmentBreak = false;
            anchorPending = false;
            modeRejected = false;
            lastAnchorElapsedMs = 0L;
            lastWriteElapsedMs = -1L;
        }
        trackMode = Prefs.readTrackMode(this);
        modeEff = TrackMode.resolve(trackMode,
                Prefs.readMinDistanceM(this), Prefs.readMinTimeMs(this));
        // En mode prédéfini, les valeurs viennent du mode ; en personnalisé,
        // des préférences. Les deux chemins passent par TrackMode.resolve
        // pour qu'il n'y ait qu'UN endroit qui décide.
        minDistanceM = modeEff.distanceM;
        minTimeMs = modeEff.timeMs;
        lastPeriodicGpxFlushElapsedMs = android.os.SystemClock.elapsedRealtime();
        gnssStatusElapsedMs = 0L;   // pas de statut herite d'une session passee
        if (voice == null) voice = new VoiceAlert(this);

        locationIntervalMs = Prefs.readGnssIntervalMs(this);
        // Marque la session comme vivante dès son démarrage. Si le processus
        // est tué avant le premier fix ou avant le premier changement d'écran,
        // le journal ne doit pas rester absent ou pointer sur l'ancienne trace.
        persistSession();
        subscribeSensors();
        updateNotification();
        ui.postDelayed(notificationTick, NOTIFICATION_TICK_MS);
        // LE WATCHDOG EST LE SEUL DÉTECTEUR DE PERTE : un récepteur muet
        // n'envoie aucun événement. Il doit tourner indépendamment des
        // callbacks GNSS.
        ui.postDelayed(signalWatchdog, SIGNAL_WATCHDOG_MS);
        state.notifyChanged();
    }

    /**
     * ABONNEMENT aux capteurs, isolé du démarrage.
     *
     * C'est la même séparation INTENTION / SOUSCRIPTION que dans l'Activity,
     * mais au niveau du service : « la session existe » et « je suis abonné
     * en ce moment » sont deux choses différentes. C'est ce qui permet de
     * suspendre (écran éteint en mode économie) puis de reprendre sans
     * perdre la session ni le fichier.
     */
    @SuppressLint("MissingPermission")
    private void subscribeSensors() {
        if (!running) return;
        if (locationManager == null) {
            locationSubscribed = false;
            gnssStatusSubscribed = false;
            nmeaSubscribed = false;
            subscribed = false;
            nextSubscriptionRetryElapsedMs = android.os.SystemClock.elapsedRealtime()
                    + SUBSCRIPTION_RETRY_MS;
            state.setTrackingNotice("Abonnements GNSS : service de localisation indisponible ; nouvelle tentative.");
            state.notifyChanged();
            return;
        }

        // Chaque API est suivie indépendamment : l'échec de GnssStatus ou NMEA
        // ne doit pas empêcher une nouvelle tentative de localisation.
        // La cadence est centralisée dans GnssAcquisitionConfig/Prefs ; elle
        // est une demande, jamais une garantie de cadence réelle du chipset.
        if (!locationSubscribed) {
            LocationListener callback = createRegisteredLocationListener();
            try {
                locationIntervalMs = Prefs.readGnssIntervalMs(this);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Android 12+ : requête explicite de haute précision,
                    // intervalle demandé ET intervalle minimal identiques,
                    // aucune distance minimale. Le fournisseur peut toutefois
                    // ralentir/refuser la cadence selon le matériel et le contexte.
                    // L'API android.location.LocationRequest.Builder ne propose
                    // pas setMaxUpdateAgeMillis() (méthode d'autres API de
                    // localisation). La fraîcheur de chaque fix reçu est donc
                    // contrôlée par FixQuality avec getElapsedRealtimeNanos()
                    // et MAX_FIX_AGE_MS ; aucun timestamp mural n'est utilisé.
                    android.location.LocationRequest request =
                            new android.location.LocationRequest.Builder(locationIntervalMs)
                                    .setMinUpdateIntervalMillis(locationIntervalMs)
                                    .setMinUpdateDistanceMeters(0f)
                                    .setQuality(android.location.LocationRequest.QUALITY_HIGH_ACCURACY)
                                    // Pas de batching : priorité à la chronologie
                                    // d'un logger GNSS plutôt qu'à l'économie d'IPC.
                                    .setMaxUpdateDelayMillis(0L)
                                    .build();
                    GnssEventPipeline pipeline = gnssEventPipeline;
                    if (pipeline == null || !pipeline.isRunning()) {
                        throw new IllegalStateException("File de traitement GNSS indisponible");
                    }
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                            request, pipeline.executor(), callback);
                } else {
                    // Android 7 à 11 : API historique équivalente pour le
                    // fournisseur GPS explicite. minTime = intervalle minimal.
                    GnssEventPipeline pipeline = gnssEventPipeline;
                    Handler callbackHandler = pipeline != null ? pipeline.handler() : null;
                    if (callbackHandler == null) {
                        throw new IllegalStateException("File de traitement GNSS indisponible");
                    }
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                            locationIntervalMs, 0f, callback,
                            callbackHandler.getLooper());
                }
                registeredLocationListener = callback;
                locationSubscribed = true;
                locationSubscriptionSinceElapsedMs = android.os.SystemClock.elapsedRealtime();
            } catch (SecurityException | IllegalArgumentException | IllegalStateException e) {
                try { locationManager.removeUpdates(callback); } catch (Exception ignored) { }
                registeredLocationListener = null;
                locationSubscribed = false;
                state.setTrackingNotice("Abonnement position refusé : " + e.getClass().getSimpleName());
            }
        }

        if (!gnssStatusSubscribed) {
            GnssStatus.Callback callback = createRegisteredGnssCallback();
            try {
                GnssEventPipeline pipeline = gnssEventPipeline;
                Handler callbackHandler = pipeline != null ? pipeline.handler() : null;
                if (callbackHandler == null) {
                    throw new IllegalStateException("File de traitement GNSS indisponible");
                }
                gnssStatusSubscribed = locationManager.registerGnssStatusCallback(
                        callback, callbackHandler);
                registeredGnssCallback = gnssStatusSubscribed ? callback : null;
            } catch (RuntimeException e) {
                registeredGnssCallback = null;
                gnssStatusSubscribed = false;
            }
        }

        if (!nmeaSubscribed) {
            OnNmeaMessageListener callback = createRegisteredNmeaListener();
            try {
                // La surcharge Handler existe depuis API 24 (minSdk du
                // projet) : elle place NMEA sur la même file FIFO que les fixes
                // et le statut satellites, toutes versions prises en charge.
                GnssEventPipeline pipeline = gnssEventPipeline;
                Handler callbackHandler = pipeline != null ? pipeline.handler() : null;
                if (callbackHandler == null) {
                    throw new IllegalStateException("File de traitement GNSS indisponible");
                }
                nmeaSubscribed = locationManager.addNmeaListener(
                        callback, callbackHandler);
                registeredNmeaListener = nmeaSubscribed ? callback : null;
            } catch (RuntimeException e) {
                registeredNmeaListener = null;
                nmeaSubscribed = false;
            }
        }

        subscribed = locationSubscribed || gnssStatusSubscribed || nmeaSubscribed;
        boolean complete = locationSubscribed && gnssStatusSubscribed && nmeaSubscribed;
        if (complete) {
            nextSubscriptionRetryElapsedMs = 0L;
            String notice = state.getTrackingNotice();
            if (notice != null && (notice.startsWith(SUBSCRIPTION_NOTICE_PREFIX)
                    || notice.startsWith("Abonnement position refusé"))) {
                state.setTrackingNotice(null);
            }
        } else {
            nextSubscriptionRetryElapsedMs = android.os.SystemClock.elapsedRealtime()
                    + SUBSCRIPTION_RETRY_MS;
            String missing = (!locationSubscribed ? "position " : "")
                    + (!gnssStatusSubscribed ? "satellites " : "")
                    + (!nmeaSubscribed ? "NMEA" : "");
            state.setTrackingNotice(SUBSCRIPTION_NOTICE_PREFIX + " partiels (" + missing.trim()
                    + ") ; nouvelle tentative automatique.");
        }
        GnssEventPipeline activePipeline = gnssEventPipeline;
        if (activePipeline != null) {
            final long generation = gnssSubscriptionGeneration.get();
            if (!activePipeline.post(() -> {
                if (generation == gnssSubscriptionGeneration.get()
                        && running && !serviceDestroying && !state.isSuspended()) {
                    gnssCallbacksEnabled = true;
                }
            })) {
                state.setTrackingNotice("File GNSS indisponible : callbacks non activés.");
            }
        }
        subscribeRawMeasurementsIfNeeded();
        state.notifyChanged();
    }

    /** Désabonnement des capteurs, sans toucher à la session ni au fichier. */
    private void unsubscribeSensors() {
        unregisterRawMeasurementsCallback();
        synchronized (gnssProcessingLock) {
            gnssCallbacksEnabled = false;
            gnssSubscriptionGeneration.incrementAndGet();
            locationCallbackGeneration.invalidate();
            statusCallbackGeneration.invalidate();
            nmeaCallbackGeneration.invalidate();
        }
        if (locationManager == null) {
            locationSubscribed = false;
            locationSubscriptionSinceElapsedMs = 0L;
            lastLocationCallbackElapsedMs = 0L;
            gnssStatusSubscribed = false;
            nmeaSubscribed = false;
            registeredLocationListener = null;
            registeredGnssCallback = null;
            registeredNmeaListener = null;
            subscribed = false;
            nextSubscriptionRetryElapsedMs = 0L;
            return;
        }
        if (registeredLocationListener != null) {
            try { locationManager.removeUpdates(registeredLocationListener); } catch (Exception ignored) { }
        }
        if (registeredGnssCallback != null) {
            try { locationManager.unregisterGnssStatusCallback(registeredGnssCallback); } catch (Exception ignored) { }
        }
        if (registeredNmeaListener != null) {
            try { locationManager.removeNmeaListener(registeredNmeaListener); } catch (Exception ignored) { }
        }
        registeredLocationListener = null;
        registeredGnssCallback = null;
        registeredNmeaListener = null;
        locationSubscribed = false;
        locationSubscriptionSinceElapsedMs = 0L;
        lastLocationCallbackElapsedMs = 0L;
        gnssStatusSubscribed = false;
        nmeaSubscribed = false;
        subscribed = false;
        nextSubscriptionRetryElapsedMs = 0L;
    }

    // ==================================================================
    // Politique d'arrière-plan
    // ==================================================================

    /**
     * Applique une nouvelle politique. Appelé :
     *   - au démarrage du service (valeur relue depuis les préférences) ;
     *   - quand l'utilisateur change le réglage (ACTION_SET_POLICY) ;
     *   - quand l'écran s'éteint ou se rallume (récepteur système).
     *
     * Point important : on ne redémarre PAS la session. On se contente
     * d'abonner ou de désabonner les capteurs. Le fichier GPX est fermé
     * proprement dans le second cas, et un nouveau segment est ouvert
     * au retour : deux fichiers honnêtes valent mieux qu'un fichier troué.
     */
    private void applyPolicy(BackgroundPolicy.Policy p) {
        synchronized (policyLock) {
            boolean changed = (p != policy);
            policy = p;
            state.setPolicy(p);
            Prefs.writePolicy(this, p);
            evaluateSuspend("politique");
            if (changed) state.notifyChanged();
        }
    }

    /**
     * Recalcule « faut-il suspendre ? » et agit en conséquence.
     *
     * Idempotent : appeler cette méthode dix fois de suite ne casse rien.
     * C'est volontaire — les sources d'événements (écran, réglage, démarrage)
     * peuvent arriver dans n'importe quel ordre.
     */
    private void evaluateSuspend(String source) {
        if (!running) return;
        boolean wantSuspend = BackgroundPolicy.shouldSuspend(policy, state.isScreenOn());
        if (wantSuspend && !state.isSuspended()) {
            suspendTracking(source);
        } else if (!wantSuspend && state.isSuspended()) {
            resumeTracking(source);
        }
    }

    private void suspendTracking(String source) {
        // 1. Sérialiser la fin du segment avec un callback de position déjà
        //    entré dans le pipeline. Aucun point ne peut être ajouté après
        //    la fermeture du segment courant.
        synchronized (gnssProcessingLock) {
            if (state.isRecording()) closeRecordingSegment();
            unsubscribeSensors();
        }
        if (rawMeasurementsLogRequested) {
            state.setRawCsvStatus("Suivi suspendu : abonnement brut suspendu ; le CSV reste ouvert.");
        }

        // ══════════════════════════════════════════════════════════════
        // CORRECTIF : LA SUSPENSION VOLONTAIRE N'EST PAS UNE PERTE DE SIGNAL.
        //
        // Sans ces trois lignes, le watchdog continuait de tourner pendant
        // la suspension (le service est toujours vivant), constatait au bout
        // de 5 s l'absence de callbacks — normale, on a coupé les capteurs —
        // et déclenchait l'alerte vocale « perte du signal GPS » toutes les
        // 30 s. À un utilisateur qui avait LUI-MÊME demandé l'économie de
        // batterie. C'est exactement le harcèlement qu'on veut éviter.
        // ══════════════════════════════════════════════════════════════
        synchronized (gnssProcessingLock) {
            fixTracker.forceLoss();
            lossPolicy.reset();
            pendingSegmentBreak = true;
            sampler.breakSegment();
        }

        state.setSuspended(true);
        state.setHasFix(false);
        state.setSignalOk(false);
        // Sans fix, la vue doit l'afficher : sinon la dernière position
        // reste figée à l'écran et laisse croire que tout marche encore.
        state.setSpeedMps(Float.NaN);
        state.setBearingDeg(Float.NaN);
        persistSession();
        updateNotification();
        state.notifyChanged();
    }

    private void resumeTracking(String source) {
        // ══════════════════════════════════════════════════════════════
        // CORRECTIF : LA REPRISE APRÈS SUSPENSION EXIGE LA MÊME SÉQUENCE
        // QU'APRÈS UN TUNNEL.
        //
        // Avant, on se réabonnait et on rouvrait le fichier. Si la machine à
        // états était restée sur OK (avant la correction ci-dessus), le
        // PREMIER fix après la reprise était écrit directement — c'est-à-dire
        // un fix pris avec des capteurs qui viennent d'être réactivés, donc
        // aussi mauvais qu'une sortie de tunnel. On aurait ouvert un fichier
        // neuf avec un point faux à son sommet.
        //
        // On remet donc explicitement l'état à zéro : la réacquisition
        // complète (3 fixes cohérents) est de nouveau obligatoire.
        // ══════════════════════════════════════════════════════════════
        synchronized (gnssProcessingLock) {
            fixTracker.forceLoss();
            lossPolicy.reset();
            state.setSuspended(false);
            if (state.isRecording() && activeSegment == null
                    && !openRecordingSegment(state.getTrackCounterEpoch())) {
                state.setRecording(false);
            }
        }
        subscribeSensors();
        persistSession();
        updateNotification();
        state.notifyChanged();
    }

    // ==================================================================
    // Récepteur d'événements d'écran
    //
    // POURQUOI ICI ET PAS DANS L'ACTIVITY : ACTION_SCREEN_OFF est un
    // broadcast qui arrive même quand aucune Activity n'existe. Si on
    // l'écoutait dans MainActivity, il ne serait reçu que quand l'app est
    // visible — exactement le contraire de ce qu'on veut.
    // ==================================================================

    private void registerScreenReceiver() {
        if (screenReceiver != null) return;
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    onScreenState(false);
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    onScreenState(true);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        // API 34 (targetSdk 34+) : le flag est OBLIGATOIRE pour tout receiver
        // enregistré à l'exécution, sinon SecurityException au boot du service.
        // On évite ContextCompat.registerReceiver : la constante
        // RECEIVER_NOT_EXPORTED n'existe que dans androidx.core récent, et
        // AppCompat 1.8.0 ne l'impose pas. Deux lignes de plus, zéro surprise.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, filter);
        }
    }

    private void unregisterScreenReceiver() {
        if (screenReceiver == null) return;
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) { }
        screenReceiver = null;
    }

    /**
     * Événements ACTION_SCREEN_ON/OFF.
     *
     * PIÈGE IMPORTANT, qu'un récepteur de service peut rencontrer :
     * le broadcast est délivré au processus. Si le service est en cours de
     * destruction (onDestroy en cours, ou tué juste après), on peut recevoir
     * un événement alors que ioThread est déjà arrêté. On vérifie donc l'état
     * avant d'écrire, et toute écriture passe par les files (no-op si mortes).
     */
    private void onScreenState(boolean on) {
        synchronized (policyLock) {
            state.setScreenOn(on);
            state.setScreenStateKnown(true);
            // Si l'écran s'éteint alors qu'on enregistre, on sauvegarde l'état
            // de session AVANT toute décision : c'est le seul moment où on est
            // sûr d'être encore en vie.
            flushNow();                  // dernières secondes sécurisées
            persistSession();
            evaluateSuspend(on ? "écran allumé" : "écran éteint");
        }
    }

    // ==================================================================
    // Journal de session (survit à la mort du process)
    // ==================================================================

    /**
     * Écrit une ligne d'état de session dans le journal.
     *
     * POURQUOI : aucune Activity ne peut garantir qu'elle sera rappelée
     * (process tué par le système, batterie arrachée, crash natif du chipset).
     * Donc on ne « sauvegarde tout à onPause » : on écrit une ligne à chaque
     * transition, et le fichier se répare tout seul au redémarrage.
     *
     * Ce n'est PAS un journal de trace (ça, c'est le GPX) : c'est un journal
     * de session, quelques centaines d'octets, lu au démarrage suivant pour
     * savoir qu'un enregistrement a été interrompu.
     */
    private void persistSession() {
        persistSessionTo(ioMeta);
    }

    /**
     * Variante ciblant explicitement le worker de métadonnées capturé lors
     * d'un arrêt. Elle permet au dernier jalon GPX de poster le snapshot final
     * après la fin des E/S fichier, puis de placer quitSafely() derrière lui.
     */
    private void persistSessionTo(final Handler meta) {
        // Snapshot immuable capturé avant l'accès disque. L'identité de session
        // permet d'écarter une écriture retardée appartenant à un ancien START.
        final String snapshotSessionId = sessionId;
        if (snapshotSessionId == null || snapshotSessionId.isEmpty()) return;
        // Le checkpoint est lu depuis l'objet segment lui-même : la longueur
        // est publiée seulement après flush + FileDescriptor.sync() sur ioFile.
        // Un segment en attente d'ouverture possède déjà une génération, mais
        // pas encore de nom de fichier ni d'offset sûr.
        final RecordingSegment journalSegment = activeSegment != null
                ? activeSegment : lastClosedSegment;
        final GpxCheckpoint.Snapshot checkpoint = journalSegment != null
                ? journalSegment.checkpoint.snapshot() : null;
        final String journalFile = checkpoint != null && !checkpoint.fileName.isEmpty()
                ? checkpoint.fileName
                : (journalSegment == null && gpxFile != null ? gpxFile.getName()
                    : (journalSegment == null && state.getLastGpxFile() != null
                        ? state.getLastGpxFile() : ""));
        final long safeLength = checkpoint != null ? checkpoint.safeLengthBytes : 0L;
        final long segmentGeneration = journalSegment != null
                ? journalSegment.id : nextSegmentId;
        // onDestroy() remet state.recording à false pour l'interface après
        // avoir demandé la fermeture. Le snapshot final, exécuté plus tard
        // après drainage de ioFile, doit néanmoins se souvenir qu'un
        // enregistrement était actif si la destruction n'était pas volontaire.
        final boolean journalRecording = state.isRecording()
                || (serviceDestroying && !stopRequested && serviceDestroyedWhileRecording);
        final SessionJournal journal = SessionJournal.of(
                snapshotSessionId,
                // Le journal reste dirty jusqu'à l'arrêt explicite qui l'efface.
                // Ne pas le déduire de userWantsTracking : un retrait de permission
                // met ce flag à false AVANT persistSession(), alors que la session
                // n'a pas été terminée volontairement et doit rester récupérable.
                true,
                state.getSessionStartMs(),
                System.currentTimeMillis(),
                journalRecording,
                state.isSuspended(),
                state.getTrackPoints(),
                state.getGpxSegment(),
                gpxPointsInSegment,
                policy != null ? policy.id : 0,
                journalFile,
                safeLength,
                segmentGeneration
        );

        // Le journal est écrit en interne via AtomicFile. Il reste dans une file
        // séparée des points GPX : il ne peut pas retarder l'acquisition. Avant
        // l'écriture, on vérifie que le START n'a pas changé entre-temps.
        if (meta == null || !meta.post(() -> {
            if (!snapshotSessionId.equals(sessionId)) return;
            if (!SessionStore.write(TrackingService.this, journal)) {
                android.util.Log.w("TrackingService", "Écriture atomique du journal de session impossible");
            }
        })) {
            android.util.Log.w("TrackingService", "Écriture du journal de session ignorée : worker indisponible");
        }
    }

    private void stopTracking() {
        if (!running) return;
        running = false;
        state.setUserWantsTracking(false);
        ui.removeCallbacks(notificationTick);

        stopRawMeasurementsLogging("Suivi GPS arrêté", null);
        unsubscribeSensors();
        ui.removeCallbacks(signalWatchdog);

        // Fermer le GPX proprement si l'utilisateur arrête tout depuis la
        // notification.
        //
        // CORRECTION v5.13 : le nettoyage de fin de trace doit être lancé ICI
        // AUSSI. Avant, seul stopRecording() (bouton « Terminer ») le
        // déclenchait. Or ARRÊTER LE GPS pendant un enregistrement ferme
        // bien le fichier par CE chemin — le fichier était donc valide mais
        // jamais nettoyé, et rien ne le disait. L'utilisateur qui arrêtait
        // « tout » n'avait pas le même résultat que celui qui arrêtait
        // « l'enregistrement », pour la même trace.
        final boolean wasRecording = state.isRecording();
        final RecordingSegment segmentToProcess = wasRecording
                ? closeRecordingSegment() : lastClosedSegment;
        state.setRecording(false);
        state.setSuspended(false);
        state.setGpxSegment(0);

        // Libération du moteur de synthèse : un TextToSpeech non libéré
        // garde un service lié actif (fuite mémoire + batterie).
        if (voice != null) {
            voice.shutdown();
            voice = null;
        }

        // Session terminée normalement : on efface le journal pour que le
        // prochain démarrage ne croie pas à une interruption.
        clearSessionJournal();

        state.notifyChanged();

        // Le nettoyage est lancé après notifyChanged. Il reste asynchrone :
        // onDestroy ne bloque jamais le thread principal. Le compteur de tâches
        // en vol retarde uniquement l'arrêt gracieux des workers tant que le
        // processus vit ; Android peut toujours tuer le processus sans callback.
        if (wasRecording) runPostProcess(segmentToProcess);
    }

    // ==================================================================
    // Enregistrement GPX
    // ==================================================================

    private void startRecording() {
        if (activeSegment != null || state.isRecording()) return;
        if (!running) {
            state.setGpxError("Démarrez d'abord le GPS");
            state.notifyChanged();
            return;
        }
        // Si la politique est « pause écran éteint » et que l'écran est déjà
        // éteint, ouvrir un fichier maintenant produirait un fichier vide
        // puis une pause immédiate : on refuse proprement.
        if (BackgroundPolicy.shouldSuspend(policy, state.isScreenOn())) {
            state.setGpxError("Suivi en pause (économie batterie)");
            state.notifyChanged();
            return;
        }
        synchronized (gnssProcessingLock) {
            if (activeSegment != null || state.isRecording() || !running) return;
            final int counterEpoch = state.resetTrackCountersAndGetEpoch();
            state.setRecording(true);
            state.setGpxSegment(0);
            state.setGpxError(null);
            state.setSessionNotice(null);
            state.setLastGpxFile(null);
            state.setCleanResult(null);
            gpxFile = null;
            lastClosedSegment = null;
            gpxPointsInSegment = 0;
            if (!openRecordingSegment(counterEpoch)) {
                state.setRecording(false);
            }
        }
        persistSession();
        state.notifyChanged();
        updateNotification();
    }

    /**
     * Réserve immédiatement le contexte du segment, puis place son ouverture
     * et son en-tête en tête de la FIFO ioFile. Aucun accès fichier n'est fait
     * ici : les fixes peuvent continuer à être acceptés et seront mis en file
     * derrière cette barrière.
     */
    private boolean openRecordingSegment(int counterEpoch) {
        Handler writerHandler = ioFile;
        if (writerHandler == null) {
            state.setGpxError("File d'écriture indisponible : ouverture GPX impossible");
            return false;
        }

        long nowWallMs = System.currentTimeMillis();
        long nowElapsedMs = android.os.SystemClock.elapsedRealtime();
        GpxWriter.Version ver = Prefs.readGpxVersion(this);
        String suffix = BackgroundPolicy.segmentSuffix(policy, state.getGpxSegment());
        String base = GpxWriter.fileName(nowWallMs, ver);
        if (suffix != null) {
            base = base.endsWith(".gpx")
                    ? base.substring(0, base.length() - 4) + suffix + ".gpx"
                    : base + suffix;
        }
        final String baseName = base;
        android.net.Uri tree = Destination.isExternalActive(this)
                ? Destination.treeUri(this) : null;

        synchronized (gnssProcessingLock) { pendingSegmentBreak = false; }
        state.setDestLabel(tree != null ? Prefs.readDestLabel(this) : null);
        state.setPublishStatus(tree != null ? "wait" : null);
        state.setPublishedName(null);
        state.setGpxDurabilityWarning(null);

        final RecordingSegment segment = new RecordingSegment(++nextSegmentId,
                sessionId, tree, ver, counterEpoch, nowElapsedMs);
        activeSegment = segment;

        // IMPORTANT : cette post est exécutée alors que l'appelant détient
        // gnssProcessingLock. Les callbacks GNSS qui ajoutent des points
        // prennent le même verrou : leur post ne peut donc jamais précéder
        // l'ouverture/en-tête de ce segment.
        boolean accepted = writerHandler.post(() -> openRecordingSegmentOnIo(
                segment, baseName, nowWallMs));
        if (!accepted) {
            activeSegment = null;
            segment.acceptingPoints = false;
            segment.openFailed = true;
            segment.ioState.failOpen();
            state.setGpxError("File d'écriture indisponible : ouverture GPX non mise en file");
            return false;
        }
        return true;
    }

    /** Effectue toutes les E/S d'ouverture sur ioFile, jamais sur le thread principal. */
    private void openRecordingSegmentOnIo(RecordingSegment segment, String base,
                                          long nowWallMs) {
        FileOutputStream output = null;
        BufferedWriter writer = null;
        File file = null;
        try {
            synchronized (segment.ioLock) {
                if (!segment.ioState.isOpening()) return;
                File dir = getExternalFilesDir(null);
                if (dir == null) throw new java.io.IOException("Stockage indisponible");
                if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                    throw new java.io.IOException("Impossible de créer le dossier GPX");
                }
                file = uniqueFile(new File(dir, base));
                segment.file = file;
                segment.snapshotFile = new File(file.getParentFile(),
                        file.getName() + ".publish.tmp");
                if (!segment.checkpoint.register(file.getName())) {
                    throw new java.io.IOException("Nom de fichier GPX invalide pour le checkpoint");
                }
                // Enregistrer l'identité du segment avant de publier son
                // premier checkpoint : ioMeta recevra ainsi toujours
                // registerSegment puis updateCheckpoint dans cet ordre.
                queueSegmentRegistration(segment);
                // uniqueFile() réserve le nom avec createNewFile() (création
                // exclusive). append=true évite toute troncature implicite.
                output = new FileOutputStream(file, true);
                writer = new BufferedWriter(
                        new OutputStreamWriter(output, StandardCharsets.UTF_8), 8192);
                writer.write(GpxWriter.header(segment.version, "GPS Test — trace", nowWallMs));
                writer.flush();

                segment.output = output;
                segment.writer = writer;
                if (!segment.ioState.markReady()) {
                    throw new java.io.IOException("État d'ouverture GPX inattendu");
                }
                segment.lastFlushElapsedMs = android.os.SystemClock.elapsedRealtime();
                segment.lastSyncElapsedMs = segment.lastFlushElapsedMs;
                // Établir immédiatement un premier préfixe sûr contenant
                // l'en-tête. Sans ce sync initial, une interruption avant le
                // premier jalon de 30 s laisserait le journal à longueur zéro.
                syncSegmentAndCheckpoint(segment);
            }

            final File openedFile = file;
            if (isLatestCounterSegment(segment)) gpxFile = openedFile;
            ui.post(() -> {
                if (!isLatestCounterSegment(segment)) return;
                gpxFile = openedFile;
                state.setLastGpxFile(openedFile.getName());
                state.setGpxError(null);
                state.notifyChanged();
                if (!stopRequested && !serviceDestroying) persistSession();
                updateNotification();
            });
        } catch (Exception e) {
            synchronized (segment.ioLock) {
                try { if (writer != null) writer.close(); else if (output != null) output.close(); }
                catch (Exception ignored) { }
                segment.file = file;
                segment.snapshotFile = file != null
                        ? new File(file.getParentFile(), file.getName() + ".publish.tmp") : null;
                if (file != null && segment.checkpoint.snapshot().fileName.isEmpty()) {
                    segment.checkpoint.register(file.getName());
                }
                segment.output = null;
                segment.writer = null;
                segment.openFailed = true;
                segment.writeFailed = true;
                segment.ioState.failOpen();
            }
            final File failedFile = file;
            if (failedFile != null) queueSegmentRegistration(segment);
            if (failedFile != null && isLatestCounterSegment(segment)) gpxFile = failedFile;
            final String message = "Échec d'ouverture GPX : " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? " — " + e.getMessage() : "");
            ui.post(() -> {
                if (!isLatestCounterSegment(segment)) return;
                if (failedFile != null) {
                    gpxFile = failedFile;
                    state.setLastGpxFile(failedFile.getName());
                }
                if (activeSegment == segment) {
                    activeSegment = null;
                    segment.acceptingPoints = false;
                    lastClosedSegment = segment;
                    state.setRecording(false);
                }
                state.setGpxError(message);
                state.notifyChanged();
                if (!stopRequested && !serviceDestroying) persistSession();
                updateNotification();
            });
        }
    }

    /**
     * Réserve atomiquement un nouveau fichier qui n'existe pas encore.
     *
     * Si « trace-….gpx » existe déjà, on essaie « trace-…-2.gpx », « -3 »…
     * createNewFile() combine vérification et création : un simple exists()
     * suivi d'un FileOutputStream aurait une fenêtre de course et pourrait
     * tronquer une trace créée entre-temps. Après 999 collisions, on échoue
     * explicitement ; on ne retourne jamais le nom initial déjà existant.
     */
    private static File uniqueFile(File wanted) throws java.io.IOException {
        // createNewFile() réalise la vérification et la réservation en une
        // seule opération atomique. Un simple exists() suivi d'un
        // FileOutputStream(..., false) pouvait écraser un fichier créé entre
        // les deux appels, ou même le nom initial après 999 collisions.
        if (wanted.createNewFile()) return wanted;

        String name = wanted.getName();
        String stem = name.endsWith(".gpx") ? name.substring(0, name.length() - 4) : name;
        File dir = wanted.getParentFile();
        for (int i = 2; i < 1000; i++) {
            File candidate = new File(dir, stem + "-" + i + ".gpx");
            if (candidate.createNewFile()) return candidate;
        }
        throw new java.io.IOException("Trop de fichiers GPX portant le même nom");
    }

    /**
     * Écrit un point sans rétention de la trace en mémoire. Les points sont
     * sérialisés sur la FIFO ioFile, flush Java toutes les 2 s et fsync toutes
     * les 30 s. Un kill brutal peut perdre au plus les données non flushées /
     * non synchronisées depuis le dernier jalon ; aucune API ne garantit une
     * écriture physique durable à chaque callback sans coût batterie/flash.
     */
    private void appendPoint(final GpxWriter.TrackPoint p) {
        final RecordingSegment segment = activeSegment;
        if (segment == null) return; // pas d'enregistrement actif
        if (segment.closed || !segment.acceptingPoints || segment.openFailed) {
            state.incrementPointsAccepted(segment.counterEpoch);
            state.incrementPointsFailedBeforeQueue(segment.counterEpoch);
            state.setGpxError(segment.openFailed
                    ? "Ouverture GPX échouée : point accepté mais non enregistré"
                    : "Point accepté mais segment GPX déjà fermé ou indisponible");
            state.notifyChanged();
            return;
        }

        // Synchroniser l'acceptation, le post Handler et le compteur queued :
        // le worker peut exécuter la tâche avant que Handler.post() ne rende
        // la main. Il attend ce verrou avant de finaliser son compteur.
        synchronized (segment) {
            state.incrementPointsAccepted(segment.counterEpoch);
            segment.pointsAccepted++;
            if (segment.writeFailed) {
                state.incrementPointsFailedBeforeQueue(segment.counterEpoch);
                state.setGpxError("Écriture GPX interrompue : le fichier est en erreur.");
                state.notifyChanged();
                return;
            }
            int pending = segment.pointsQueued - segment.pointsWritten
                    - segment.pointsFailedAfterQueue;
            if (pending >= MAX_GPX_QUEUE_DEPTH) {
                // Un stockage très lent ou bloqué ne doit pas accumuler des
                // milliers de Runnable et épuiser la mémoire. Le point reste
                // compté comme accepté par le filtre, mais est explicitement
                // compté comme échec AVANT la file.
                state.incrementPointsFailedBeforeQueue(segment.counterEpoch);
                state.setGpxError("File GPX saturée (" + MAX_GPX_QUEUE_DEPTH
                        + " points en attente) : des points n'ont pas été mis en file.");
                state.notifyChanged();
                return;
            }
            boolean posted = ioFile != null && ioFile.post(() -> {
                String errorMessage = null;
                boolean written = false;
                try {
                    synchronized (segment.ioLock) {
                        if (segment.closed || segment.writeFailed || segment.openFailed
                                || !segment.ioState.canWrite() || segment.writer == null) {
                            throw new java.io.IOException(segment.openFailed
                                    ? "ouverture GPX échouée"
                                    : "segment fermé ou écriture en erreur");
                        }
                        segment.writer.write(GpxWriter.pointXml(p, segment.version));
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (now - segment.lastFlushElapsedMs >= FLUSH_EVERY_MS) {
                            segment.writer.flush();
                            segment.lastFlushElapsedMs = now;
                        }
                        written = true; // le point complet a été remis au writer
                        if (now - segment.lastSyncElapsedMs >= SYNC_EVERY_MS) {
                            // Le helper ne publie la longueur qu'après flush +
                            // succès de FileDescriptor.sync(). Il s'exécute
                            // dans cette tâche FIFO, après l'écriture complète du point.
                            syncSegmentAndCheckpoint(segment);
                        }
                    }
                } catch (Exception e) {
                    segment.writeFailed = true;
                    written = false;
                    if (!segment.openFailed) {
                        errorMessage = "Écriture GPX : " + e.getClass().getSimpleName()
                                + (e.getMessage() != null ? " — " + e.getMessage() : "");
                    }
                }

                synchronized (segment) {
                    if (written) {
                        segment.pointsWritten++;
                        if (activeSegment == segment) gpxPointsInSegment = segment.pointsWritten;
                        state.incrementPointsWritten(segment.counterEpoch);
                    } else {
                        segment.pointsFailedAfterQueue++;
                        state.incrementPointsFailedAfterQueue(segment.counterEpoch);
                    }
                }
                if (errorMessage != null) {
                    reportSegmentGpxError(segment, errorMessage);
                }
            });
            if (posted) {
                segment.pointsQueued++;
                state.incrementPointsQueued(segment.counterEpoch);
            } else {
                state.incrementPointsFailedBeforeQueue(segment.counterEpoch);
                state.setGpxError("File d'écriture indisponible : point accepté mais non mis en file");
            }
        }
    }

    /**
     * Remonte une erreur d'un worker uniquement si le segment est toujours le
     * dernier segment connu. Une écriture retardée d'un ancien fichier ne doit
     * pas remplacer l'état de la trace que l'utilisateur vient de démarrer.
     * Ces méthodes postent systématiquement sur le thread UI : le worker ne
     * modifie jamais directement les champs d'état observés par l'interface.
     */
    private boolean isLatestCounterSegment(RecordingSegment segment) {
        return segment != null
                && segment.id == nextSegmentId
                && segment.counterEpoch == state.getTrackCounterEpoch();
    }

    /**
     * Confirme un checkpoint sur ioFile : flush, fsync, puis lecture de la
     * longueur exacte du fichier. Aucun offset n'est annoncé si fsync échoue.
     * L'appelant détient déjà segment.ioLock et se trouve dans la FIFO GPX.
     */
    private void syncSegmentAndCheckpoint(RecordingSegment segment) throws java.io.IOException {
        if (segment == null || segment.writer == null || segment.output == null
                || segment.file == null || segment.writeFailed || segment.openFailed) return;
        segment.writer.flush();
        long now = android.os.SystemClock.elapsedRealtime();
        segment.lastFlushElapsedMs = now;
        try {
            segment.output.getFD().sync();
        } catch (java.io.IOException syncError) {
            segment.lastSyncElapsedMs = now; // éviter un fsync à chaque point si le FS refuse
            reportSegmentDurability(segment, "Synchronisation stockage GPX non confirmée : "
                    + syncError.getClass().getSimpleName());
            return;
        }

        long length = segment.file.length();
        if (!segment.checkpoint.advance(segment.file.getName(), length)) {
            reportSegmentDurability(segment, "Checkpoint GPX non avancé : longueur incohérente ou fichier changé");
            segment.lastSyncElapsedMs = now;
            return;
        }
        segment.lastSyncElapsedMs = now;
        queueSegmentCheckpoint(segment, length);
        reportSegmentDurability(segment, null);
    }

    /** Enregistre l'association génération/fichier dès que le nom est réservé. */
    private void queueSegmentRegistration(final RecordingSegment segment) {
        if (segment == null || segment.file == null) return;
        final Handler meta = ioMeta;
        if (meta == null) return; // persistSessionTo() reprendra le snapshot en fin de FIFO
        final String name = segment.file.getName();
        boolean queued = meta.post(() -> {
            if (!segment.sessionId.equals(sessionId)) return;
            if (!SessionStore.registerSegment(TrackingService.this, segment.sessionId,
                    segment.id, name)) {
                android.util.Log.w("TrackingService", "Association du segment au journal différée ou refusée : " + name);
            }
        });
        if (!queued) {
            android.util.Log.w("TrackingService", "Association du segment non mise en file : " + name);
        }
    }

    /** Publie uniquement un checkpoint du fichier et de la génération qui l'ont produit. */
    private void queueSegmentCheckpoint(final RecordingSegment segment, final long length) {
        if (segment == null || segment.file == null || length <= 0L) return;
        final Handler meta = ioMeta;
        if (meta == null) return; // le snapshot de fermeture sur ioFile contient encore l'offset
        final String name = segment.file.getName();
        boolean queued = meta.post(() -> {
            if (!segment.sessionId.equals(sessionId)) return;
            if (!SessionStore.updateCheckpoint(TrackingService.this, segment.sessionId,
                    segment.id, name, length)) {
                android.util.Log.w("TrackingService", "Checkpoint GPX non inscrit (session/segment différent ou journal absent) : " + name);
                if (isLatestCounterSegment(segment)) {
                    reportSegmentDurability(segment,
                            "Préfixe GPX synchronisé, mais checkpoint du journal non confirmé");
                }
            }
        });
        if (!queued) {
            reportSegmentDurability(segment,
                    "Préfixe GPX synchronisé, mais file du journal indisponible");
        }
    }

    private void reportSegmentGpxError(RecordingSegment segment, String message) {
        ui.post(() -> {
            if (isLatestCounterSegment(segment)) {
                state.setGpxError(message);
                state.notifyChanged();
                updateNotification();
            }
        });
    }

    private void reportSegmentDurability(RecordingSegment segment, String warning) {
        ui.post(() -> {
            if (isLatestCounterSegment(segment)) {
                state.setGpxDurabilityWarning(warning);
                state.notifyChanged();
            }
        });
    }

    /**
     * Force le flush du segment actif. Un appel est placé dans la même FIFO
     * que les points : il ne peut donc jamais passer devant une écriture.
     */
    private void flushNow() {
        final RecordingSegment segment = activeSegment;
        if (segment == null) return;
        Handler writerHandler = ioFile;
        if (writerHandler == null || !writerHandler.post(() -> {
            try {
                synchronized (segment.ioLock) {
                    if (!segment.closed && !segment.openFailed
                            && segment.ioState.canWrite() && segment.writer != null) {
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (now - segment.lastSyncElapsedMs >= SYNC_EVERY_MS) {
                            syncSegmentAndCheckpoint(segment);
                        } else {
                            segment.writer.flush();
                            segment.lastFlushElapsedMs = now;
                        }
                    }
                }
            } catch (Exception e) {
                segment.writeFailed = true;
                reportSegmentGpxError(segment, "Flush GPX : " + e.getMessage());
            }
        })) {
            state.setGpxDurabilityWarning("Vidage GPX demandé mais file d'écriture indisponible");
            state.notifyChanged();
        }
    }

    /**
     * Exécute une annonce décidée par LossAnnouncer.
     *
     * On passe par une méthode dédiée plutôt que d'appeler voice.speak()
     * directement dans le watchdog : c'est ici qu'on peut décider qu'une
     * alerte n'a pas de sens (par exemple si la synthèse vocale est
     * désactivée dans les préférences), sans polluer la machine à états.
     */
    private void announce(LossAnnouncer.Action a, long lostMs) {
        // Le texte est COURT et ne contient plus la durée : elle s'affiche à
        // l'écran (state.lostForMs), où elle ne coûte rien et ne s'oublie pas
        // au milieu d'une phrase comme le ferait un message parlé.
        if (voice == null) voice = new VoiceAlert(this);
        // La chaîne vient des RESSOURCES : Android choisit la variante de la
        // langue du téléphone, exactement comme le reste de l'interface.
        int res = LossAnnouncer.textResId(a);
        if (res != 0) voice.speak(getString(res));
    }

    // ==================================================================
    // Publication vers le répertoire choisi par l'utilisateur
    // ==================================================================

    /**
     * Publie le segment complet APRÈS sa fermeture. La source locale est
     * durable indépendamment du fournisseur SAF ; on ne réécrit donc pas le
     * document entier à chaque point (coût O(n²) en session longue).
     */
    private void publishSnapshot(RecordingSegment segment, long snapshotLength) {
        String status;
        String name = null;
        try {
            if (!Destination.isGranted(this, segment.destinationTree)) {
                status = "err:accès au dossier perdu";
            } else {
                if (!copyPrefix(segment.file, segment.snapshotFile, snapshotLength)) {
                    status = "err:instantané local impossible";
                } else {
                    if (segment.document == null) {
                        segment.document = Destination.createDocument(this,
                                segment.destinationTree, segment.file.getName());
                    }
                    if (segment.document == null) {
                        status = "err:création impossible";
                    } else {
                        boolean addFoot = !Destination.endsWithGpxClose(segment.snapshotFile);
                        boolean ok = Destination.overwrite(this, segment.document,
                                segment.snapshotFile, addFoot);
                        if (!ok) {
                            // Le document est propre à ce segment et vient
                            // d'être créé : supprimer une copie probablement
                            // tronquée, plutôt que laisser un faux GPX complet.
                            Destination.deleteDocument(this, segment.document);
                            segment.document = null;
                            status = "err:écriture impossible — copie partielle supprimée, fichier local conservé";
                        } else {
                            status = "ok";
                            name = segment.file.getName();
                        }
                    }
                }
            }
        } catch (Exception e) {
            status = "err:" + e.getClass().getSimpleName();
        } finally {
            // Nettoyage quel que soit le chemin : permission révoquée,
            // source courte, échec createDocument ou écriture SAF.
            //noinspection ResultOfMethodCallIgnored
            segment.snapshotFile.delete();
        }
        finishPublish(segment, status, name);
    }

    private void finishPublish(RecordingSegment segment, String status, String name) {
        final String st = status;
        final String nm = name;
        ui.post(() -> {
            // Une publication de segment ancien peut finir après l'ouverture
            // du suivant. Elle ne doit jamais remplacer le statut du courant.
            if (isLatestCounterSegment(segment)) {
                state.setPublishStatus(st);
                if (nm != null) state.setPublishedName(nm);
                state.notifyChanged();
            }
        });
    }

    /** Copie exactement les octets du préfixe déjà flushé (append-only). */
    private static boolean copyPrefix(File source, File target, long length) {
        if (source == null || target == null || length < 0 || !source.exists()) return false;
        byte[] buffer = new byte[32 * 1024];
        boolean complete = false;
        try (java.io.InputStream in = new java.io.FileInputStream(source);
             FileOutputStream out = new FileOutputStream(target, false)) {
            if (source.length() < length) return false;
            long remaining = length;
            while (remaining > 0) {
                int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (n < 0) return false;
                if (n == 0) continue;
                out.write(buffer, 0, n);
                remaining -= n;
            }
            out.flush();
            // Le snapshot doit être complètement fermé et lisible avant SAF.
            out.getFD().sync();
            complete = remaining == 0;
            return complete;
        } catch (Exception e) {
            return false;
        } finally {
            if (!complete) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }
        }
    }

    /**
     * Ferme le segment courant : la demande est mise après l'ouverture et tous
     * les points déjà acceptés dans la même FIFO. La méthode est réentrante
     * avec gnssProcessingLock pour sérialiser l'acceptation et la fermeture.
     */
    private RecordingSegment closeRecordingSegment() {
        synchronized (gnssProcessingLock) {
            final RecordingSegment segment = activeSegment;
            if (segment == null) return null;
            activeSegment = null;
            segment.acceptingPoints = false;
            lastClosedSegment = segment;
            if (segment.file != null) {
                gpxFile = segment.file;
                state.setLastGpxFile(segment.file.getName());
            }
            state.setGpxSegment(state.getGpxSegment() + 1);

            Handler writerHandler = ioFile;
            if (writerHandler == null) {
                state.setGpxError("File d'écriture indisponible à la fermeture");
                closeSegmentFallback(segment);
                return segment;
            }
            boolean accepted = writerHandler.post(() -> closeSegmentOnIo(segment));
            if (!accepted) {
                state.setGpxError("File d'écriture indisponible à la fermeture");
                closeSegmentFallback(segment);
            }
            return segment;
        }
    }

    private void closeSegmentOnIo(final RecordingSegment segment) {
        boolean closedCleanly = false;
        try {
            synchronized (segment.ioLock) {
                if (!segment.closed) {
                    if (segment.ioState.canWrite() && segment.writer != null
                            && !segment.writeFailed) {
                        segment.writer.write(GpxWriter.footer());
                        // Le checkpoint final inclut le pied : si sync réussit,
                        // le préfixe enregistré correspond au GPX complet fermé.
                        syncSegmentAndCheckpoint(segment);
                        segment.writer.close();
                        closedCleanly = true;
                    } else {
                        // Ouverture échouée : conserver le fichier partiel
                        // éventuel, sans fabriquer un pied trompeur.
                        if (segment.writer != null) segment.writer.close();
                        else if (segment.output != null) segment.output.close();
                    }
                    segment.closed = true;
                    segment.ioState.finishClose();
                }
            }
        } catch (Exception e) {
            segment.writeFailed = true;
            segment.closed = true;
            segment.ioState.finishClose();
            reportSegmentGpxError(segment, "Fermeture GPX : " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? " — " + e.getMessage() : ""));
            try { if (segment.output != null) segment.output.close(); } catch (Exception ignored) { }
        }
        if (segment.publishEnabled) {
            if (!closedCleanly || segment.writeFailed || segment.openFailed || segment.file == null) {
                ui.post(() -> {
                    if (isLatestCounterSegment(segment)) {
                        state.setPublishStatus("err:trace locale incomplète — copie externe non publiée");
                        state.notifyChanged();
                    }
                });
            } else {
                long length = segment.file.length();
                Handler publisher = ioPublish;
                if (publisher == null || !publisher.post(() -> publishSnapshot(segment, length))) {
                    // onDestroy() retire les références aux workers avant que
                    // la FIFO fichier soit forcément vide. Si le worker SAF
                    // n'accepte plus la tâche, préserver le comportement de
                    // secours : publier sur un thread dédié, jamais sur ioFile.
                    Thread fallback = new Thread(() -> publishSnapshot(segment, length),
                            "gpx-publish-fallback");
                    fallback.setDaemon(true);
                    fallback.start();
                }
            }
        }
        ui.post(() -> {
            if (isLatestCounterSegment(segment) && segment.file != null) {
                gpxFile = segment.file;
                state.setLastGpxFile(segment.file.getName());
            }
            state.notifyChanged();
            updateNotification();
        });
    }

    /** Dernier recours si le HandlerThread a déjà été arrêté. */
    private void closeSegmentFallback(final RecordingSegment segment) {
        Thread t = new Thread(() -> closeSegmentOnIo(segment), "gpx-close-fallback");
        t.setDaemon(true);
        t.start();
    }

    private void shutdownIoThreadsAfterDrain() {
        if (ioShutdownRequested) return;
        ioShutdownRequested = true;
        final android.os.HandlerThread fileThread = ioThread;
        final android.os.HandlerThread metaThread = ioMetaThread;
        final android.os.HandlerThread publishThread = ioPublishThread;
        final Handler fileHandler = ioFile;
        final Handler metaHandler = ioMeta;
        final Handler publishHandler = ioPublish;

        // La barrière est ajoutée APRÈS toutes les écritures GPX déjà en file.
        // Elle ferme d'abord le worker fichier, puis ajoute l'arrêt du worker
        // de publication APRÈS les publications que les tâches GPX auront
        // éventuellement mises en file. Chaque échec de post a un repli :
        // aucun HandlerThread ne doit rester vivant par oubli.
        Runnable closeFileThenPublisher = () -> {
            // Tous les segments (y compris une ouverture qui était encore en
            // attente lors de onDestroy) sont maintenant passés dans la FIFO.
            // Publier le dernier nom de fichier dans le journal AVANT d'arrêter
            // ioMeta : sinon le snapshot de onDestroy pourrait ne contenir
            // aucun nom alors que l'en-tête GPX vient juste d'être créé.
            if (!stopRequested) persistSessionTo(metaHandler);
            postQuitSafely(metaHandler, metaThread);
            quitThreadSafely(fileThread);
            postQuitSafely(publishHandler, publishThread);
        };
        if (fileThread != null) {
            boolean queued = false;
            if (fileHandler != null) {
                try { queued = fileHandler.post(closeFileThenPublisher); }
                catch (RuntimeException ignored) { queued = false; }
            }
            if (!queued) {
                // File worker indisponible : aucun futur état de fichier ne
                // pourra être observé. Le dernier snapshot connu est néanmoins
                // placé avant l'arrêt du worker méta.
                if (!stopRequested) persistSessionTo(metaHandler);
                postQuitSafely(metaHandler, metaThread);
                quitThreadSafely(fileThread);
                postQuitSafely(publishHandler, publishThread);
            }
        } else {
            if (!stopRequested) persistSessionTo(metaHandler);
            postQuitSafely(metaHandler, metaThread);
            postQuitSafely(publishHandler, publishThread);
        }

        // Les références sont retirées tout de suite afin qu'aucune nouvelle
        // opération ne puisse croire que les workers sont encore disponibles.
        // Les tâches déjà acceptées continuent jusqu'à leur vidage gracieux.
        ioFile = null;
        ioMeta = null;
        ioPublish = null;
        ioThread = null;
        ioMetaThread = null;
        ioPublishThread = null;
    }

    private static void postQuitSafely(Handler handler, android.os.HandlerThread thread) {
        if (thread == null) return;
        boolean queued = false;
        if (handler != null) {
            try { queued = handler.post(thread::quitSafely); }
            catch (RuntimeException ignored) { queued = false; }
        }
        if (!queued) quitThreadSafely(thread);
    }

    private static void quitThreadSafely(android.os.HandlerThread thread) {
        if (thread == null) return;
        try { thread.quitSafely(); }
        catch (RuntimeException ignored) { /* arrêt déjà demandé */ }
    }

    /** Arrêt demandé par l'utilisateur : ferme le fichier et sort de l'enregistrement. */
    private void stopRecording() {
        final RecordingSegment segmentToProcess;
        synchronized (gnssProcessingLock) {
            if (!state.isRecording()) {
                state.notifyChanged();
                return;
            }
            RecordingSegment closed = closeRecordingSegment();
            segmentToProcess = closed != null ? closed : lastClosedSegment;
            state.setRecording(false);
            // Un message de rejet de point n'a plus de sens une fois
            // l'enregistrement arrêté : le point suivant ne sera pas écrit,
            // rejeté ou non. Le laisser affiché donnerait un statut faux.
            state.setModeReject(null);
        }
        persistSession();
        state.notifyChanged();
        updateNotification();

        // ── NETTOYAGE À LA FIN (v5.11) ─────────────────────────────────
        //
        // APRÈS la fermeture du fichier, jamais pendant : on ne touche pas
        // à un fichier que le thread d'écriture pourrait encore manipuler.
        // Le fichier d'origine n'est JAMAIS modifié — le nettoyage écrit
        // une copie `_nettoye.GPX` à côté. Si quoi que ce soit échoue,
        // l'utilisateur garde sa trace brute et on le lui dit.
        runPostProcess(segmentToProcess);
    }

    private void runPostProcess(final RecordingSegment segment) {
        if (!Prefs.readPostProcess(this) || segment == null) return;

        // La fermeture (ou l'ouverture suivie de la fermeture si l'utilisateur
        // a arrêté immédiatement) est déjà placée dans ioFile. Cette barrière
        // l'attend avant de lire le fichier, sans bloquer le thread principal.
        final Handler fileHandler = ioFile;
        final Handler metaHandler = ioMeta;
        if (fileHandler == null || metaHandler == null) return;

        final double tolerance = Prefs.readPpTolerance(this);
        final double maxSpeed = TrackPostProcess.maxSpeedFor(trackMode);
        final GpxWriter.Version versionAtStop = segment.version;
        cleanupTasksInFlight.incrementAndGet();

        boolean barrierQueued = fileHandler.post(() -> {
            final File src = segment.file;
            if (src == null || !src.exists() || segment.openFailed
                    || segment.writeFailed || !segment.closed) {
                ui.post(() -> {
                    state.setCleanResult("Trace brute conservée — segment incomplet ou ouverture GPX échouée");
                    state.notifyChanged();
                    finishCleanupAsync();
                });
                return;
            }
            boolean cleanupQueued = metaHandler.post(() -> {
                TrackCleaner.Outcome outcome;
                try {
                    outcome = TrackCleaner.clean(src, tolerance, maxSpeed, versionAtStop, null);
                } catch (Throwable error) {
                    outcome = new TrackCleaner.Outcome(false, null,
                            "Erreur : " + error.getClass().getSimpleName(), null);
                }
                final String message = outcome.success && outcome.file != null
                        ? "Nettoyage réussi — " + outcome.file.getName() + " — " + outcome.message
                        : "Trace brute conservée — " + outcome.message;
                ui.post(() -> {
                    state.setCleanResult(message);
                    state.notifyChanged();
                    finishCleanupAsync();
                });
            });
            if (!cleanupQueued) {
                ui.post(() -> {
                    state.setCleanResult("Nettoyage non lancé : worker indisponible — trace brute intacte");
                    state.notifyChanged();
                    finishCleanupAsync();
                });
            }
        });
        if (!barrierQueued) {
            state.setCleanResult("Nettoyage non lancé : file GPX indisponible — trace brute intacte");
            state.notifyChanged();
            finishCleanupAsync();
        }
    }

    /** Décompte exactement une fin de nettoyage, même si plusieurs traces sont traitées. */
    private void finishCleanupAsync() {
        int remaining = cleanupTasksInFlight.decrementAndGet();
        if (remaining < 0) {
            // Défense contre un double callback inattendu : revenir à un état
            // non négatif sans lancer un arrêt prématuré des workers.
            cleanupTasksInFlight.compareAndSet(remaining, 0);
            remaining = 0;
        }
        if (serviceDestroying && remaining == 0) shutdownIoThreadsAfterDrain();
    }

    // ==================================================================
    // Changement de mode en cours de session (v5.11)
    // ==================================================================

    private void registerPrefsListener() {
        prefsListener = new android.content.SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(
                    android.content.SharedPreferences p, String key) {
                // On ne réagit QU'AUX clés qui changent le comportement
                // d'écriture. Réagir à tout obligerait à relire l'ensemble
                // à chaque case cochée, et rendrait le code impossible à
                // auditer : on ne saurait plus ce qui déclenche quoi.
                if (Prefs.KEY_GNSS_INTERVAL_MS.equals(key)) {
                    locationIntervalMs = Prefs.readGnssIntervalMs(TrackingService.this);
                    if (running && !state.isSuspended() && locationSubscribed) {
                        // Invalider d'abord l'ancien callback sous le même verrou
                        // que son dispatch. Les callbacks position déjà en file
                        // deviennent inoffensifs ; Status/NMEA et le CSV brut
                        // restent abonnés sans interruption.
                        LocationListener oldCallback = registeredLocationListener;
                        synchronized (gnssProcessingLock) {
                            locationCallbackGeneration.invalidate();
                        }
                        registeredLocationListener = null;
                        locationSubscribed = false;
                        if (oldCallback != null) {
                            try { locationManager.removeUpdates(oldCallback); }
                            catch (Exception ignored) { }
                        }
                        subscribeSensors();
                    }
                    return;
                }
                if (!Prefs.KEY_TRACK_MODE.equals(key)
                        && !Prefs.KEY_MIN_DISTANCE_M.equals(key)
                        && !Prefs.KEY_MIN_TIME_MS.equals(key)) return;
                applyTrackMode();
            }
        };
        Prefs.spForListener(this).registerOnSharedPreferenceChangeListener(prefsListener);
    }

    private void unregisterPrefsListener() {
        if (prefsListener == null) return;
        // Le try est nécessaire : si le SharedPreferences a déjà été
        // libéré (processus en fin de vie), le désenregistrement lève.
        try {
            Prefs.spForListener(this)
                    .unregisterOnSharedPreferenceChangeListener(prefsListener);
        } catch (Exception ignored) { }
        prefsListener = null;
    }

    /**
     * Relit le mode et ses paramètres effectifs. Appelée au démarrage de
     * session ET à chaque modification des réglages.
     *
     * POINT IMPORTANT : on ne touche NI au sampler, NI aux points déjà
     * écrits. Un changement de mode s'applique AU POINT SUIVANT. Recalculer
     * rétroactivement n'aurait aucun sens : les points passés ont été
     * écrits selon une règle qui était la bonne au moment où ils l'ont été.
     */
    private void applyTrackMode() {
        trackMode = Prefs.readTrackMode(this);
        modeEff = TrackMode.resolve(trackMode,
                Prefs.readMinDistanceM(this), Prefs.readMinTimeMs(this));
        minDistanceM = modeEff.distanceM;
        minTimeMs = modeEff.timeMs;

        // RIEN À FAIRE SUR LE SAMPLER. Il ne mémorise pas la politique :
        // `offer(minDistanceM, minTimeMs, …)` la reçoit à CHAQUE appel. Il
        // suffit donc que les deux champs soient à jour — ce qui est le cas
        // trois lignes plus haut. (J'ai d'abord voulu appeler un
        // `sampler.setPolicy()` qui N'EXISTE PAS : c'est exactement le genre
        // de méthode inventée que la compilation attrape, et que la lecture
        // seule aurait laissée passer.)
        state.notifyChanged();
        updateNotification();
    }

    // ==================================================================
    // Journal de session : lecture au démarrage
    // ==================================================================

    private void clearSessionJournal() {
        // L'identité capturée empêche un clear retardé d'effacer le journal
        // d'un nouveau START dans la même instance de Service.
        final String expectedSessionId = sessionId;
        Handler meta = ioMeta;
        if (expectedSessionId == null || expectedSessionId.isEmpty()) return;
        if (meta == null || !meta.post(() ->
                SessionStore.clearIfSameSession(TrackingService.this, expectedSessionId))) {
            android.util.Log.w("TrackingService", "Effacement du journal non mis en file : worker indisponible");
        }
    }

    /**
     * Appelé au démarrage du service. Si le journal dit « dirty=true », la
     * session précédente n'a pas été terminée : on le dit à l'utilisateur
     * au lieu de l'ignorer. C'est exactement ce qu'un outil sérieux fait.
     */
    private void checkPreviousSession() {
        // Lecture disque différée : onCreate() précède onStartCommand(), donc
        // toute E/S synchrone ici consommerait une partie du délai de 5 s
        // laissé à startForegroundService(). Le journal ne fait que quelques
        // centaines d'octets, mais son support externe peut être lent.
        Handler meta = ioMeta;
        if (meta == null || !meta.post(() -> {
            // La lecture est mise en file avant persistSession() au démarrage.
            // Ne pas tester userWantsTracking ici : ce flag est posé très tôt,
            // avant startForeground(), et un lancement rapide pourrait
            // sinon masquer le journal de la session morte précédente.
            SessionJournal previous = SessionStore.read(TrackingService.this);
            if (previous == null || previous.noticeShown) return;
            String notice = previous.interruptionNotice();
            if (notice == null) return;
            ui.post(() -> {
                // La notice concerne explicitement la session précédente.
                // Elle reste pertinente même si un nouveau suivi démarre.
                if (state.getSessionNotice() == null) {
                    state.setSessionNotice(notice);
                    state.notifyChanged();
                    Handler marker = ioMeta;
                    if (marker != null) marker.post(() ->
                            SessionStore.markNoticeShown(TrackingService.this, previous));
                }
            });
        })) {
            android.util.Log.w("TrackingService", "Lecture du journal précédent non mise en file");
        }
        // Ne supprime PAS ici : MainActivity peut également lire et acquitter la notice,
        // mais le journal doit rester disponible pour la récupération GPX du LOT 8C-R2.
    }
    // ==================================================================
    // Enregistrement des mesures GNSS brutes (mode Expert)
    // ==================================================================

    private File rawMeasurementsDirectory() {
        File directory = getExternalFilesDir("gnss");
        if (directory == null) directory = new File(getFilesDir(), "gnss");
        return directory;
    }

    private void updateRawLoggerState(RawMeasurementsCsvLogger.Snapshot snapshot) {
        if (snapshot == null) return;
        state.setRawCsvCounters(snapshot.eventsReceived, snapshot.rowsQueued,
                snapshot.rowsPending, snapshot.rowsWritten, snapshot.rowsDropped);
        state.setRawCsvDurabilityWarning(snapshot.durabilityWarning);
    }

    private void ensureRawCsvLogger() {
        if (rawCsvLogger != null) return;
        rawCsvLogger = new RawMeasurementsCsvLogger(new RawMeasurementsCsvLogger.Listener() {
            @Override
            public void onOpened(File file, RawMeasurementsCsvLogger.Snapshot snapshot) {
                ui.post(() -> {
                    if (serviceDestroying || rawCsvLogger == null
                            || snapshot.generation != rawCsvLogger.snapshot().generation
                            || snapshot.generation != state.getRawCsvGeneration()) return;
                    state.setRawCsvPath(file != null ? file.getAbsolutePath() : null);
                    state.setRawCsvRequested(rawMeasurementsLogRequested);
                    state.setRawCsvStatus("CSV ouvert. Abonnement aux mesures GNSS en cours…");
                    updateRawLoggerState(snapshot);
                    if (rawMeasurementsLogRequested && running && !state.isSuspended()) {
                        subscribeRawMeasurementsIfNeeded();
                    }
                    state.notifyChanged();
                });
            }

            @Override
            public void onUpdated(RawMeasurementsCsvLogger.Snapshot snapshot) {
                ui.post(() -> {
                    if (serviceDestroying || rawCsvLogger == null
                            || snapshot.generation != rawCsvLogger.snapshot().generation
                            || snapshot.generation != state.getRawCsvGeneration()) return;
                    updateRawLoggerState(snapshot);
                    if (snapshot.opening) state.setRawCsvStatus("Ouverture du fichier CSV…");
                    else if (snapshot.closing) state.setRawCsvStatus("Finalisation du CSV…");
                    else if (snapshot.ready && rawMeasurementsSubscribed) {
                        long ageMs = lastRawMeasurementsElapsedMs > 0L
                                ? android.os.SystemClock.elapsedRealtime() - lastRawMeasurementsElapsedMs
                                : Long.MAX_VALUE;
                        if (ageMs >= RAW_MEASUREMENTS_STALE_MS) {
                            state.setRawCsvStatus("Abonnement présent, mais aucune mesure brute récente ; arrière-plan non garanti par Android.");
                        } else {
                            state.setRawCsvStatus("Réception de mesures brutes active ; Android peut suspendre leur livraison en arrière-plan.");
                        }
                    }
                    state.notifyChanged();
                });
            }

            @Override
            public void onClosed(File file, RawMeasurementsCsvLogger.Snapshot snapshot, String error) {
                ui.post(() -> {
                    if (rawCsvLogger == null
                            || snapshot.generation != rawCsvLogger.snapshot().generation
                            || snapshot.generation != state.getRawCsvGeneration()) return;
                    rawMeasurementsLogRequested = false;
                    state.setRawCsvRequested(false);
                    if (!state.isExpertPageVisible()) {
                        unregisterRawMeasurementsCallback();
                    } else if (running && !state.isSuspended()) {
                        subscribeRawMeasurementsIfNeeded();
                    }
                    if (file != null) state.setRawCsvPath(file.getAbsolutePath());
                    updateRawLoggerState(snapshot);
                    if (error != null && !error.trim().isEmpty()) state.setRawCsvError(error);
                    if (error != null && !error.trim().isEmpty()) {
                        state.setRawCsvStatus("Erreur CSV : " + error);
                    } else {
                        state.setRawCsvStatus(file != null
                                ? "CSV finalisé : " + file.getName()
                                : "Enregistrement CSV non créé");
                    }
                    state.notifyChanged();
                });
            }
        });
    }

    private void startRawMeasurementsLogging() {
        if (!running) {
            state.setRawCsvStatus("Démarre d'abord le suivi GPS depuis l'écran principal : le CSV Expert utilise le service GNSS actif.");
            state.setRawCsvError("Suivi GPS inactif");
            state.notifyChanged();
            return;
        }
        if (state.isSuspended()) {
            state.setRawCsvStatus("Suivi suspendu par la politique d'économie ; reprends le suivi avant le journal GNSS brut.");
            state.notifyChanged();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            state.setRawCsvStatus("Permission de localisation précise requise pour les mesures brutes.");
            state.setRawCsvError("Permission ACCESS_FINE_LOCATION absente");
            state.notifyChanged();
            return;
        }
        if (rawMeasurementsLogRequested || (rawCsvLogger != null && rawCsvLogger.isRequested())) return;

        activeRawCsvOptions = new RawMeasurementsCsvLogger.Options(
                Prefs.readRawCsvTime(this), Prefs.readRawCsvSignal(this),
                Prefs.readRawCsvDoppler(this), Prefs.readRawCsvPhase(this),
                Prefs.readRawCsvNmea(this));
        ensureRawCsvLogger();
        rawMeasurementsLogRequested = true;
        state.setRawCsvRequested(true);
        state.setRawCsvPath(null);
        state.setRawCsvError(null);
        state.setRawCsvDurabilityWarning(null);
        state.setRawMeasurementsPreview("");
        state.setRawMeasurementsStatusLastCallback("");
        state.setRawCsvCounters(0, 0, 0, 0, 0);
        state.setRawMeasurementsSubscribed(rawMeasurementsSubscribed);
        state.setRawCsvStatus("Ouverture du CSV GNSS brut…");
        state.notifyChanged();
        if (!rawCsvLogger.start(rawMeasurementsDirectory())) {
            rawMeasurementsLogRequested = false;
            state.setRawCsvRequested(false);
            state.setRawCsvStatus("Impossible d'ouvrir un nouveau journal CSV (un autre journal est en cours ou le stockage est indisponible).");
            state.setRawCsvError("Démarrage du logger refusé");
            state.notifyChanged();
        } else {
            // Cet identifiant est global au processus : si le service est
            // recréé avant le retour d'un ancien callback I/O, cet ancien
            // callback ne pourra pas écraser l'état du nouveau journal.
            state.setRawCsvGeneration(rawCsvLogger.snapshot().generation);
        }
    }

    private void stopRawMeasurementsLogging(String reason, String error) {
        rawMeasurementsLogRequested = false;
        if (!state.isExpertPageVisible()) unregisterRawMeasurementsCallback();
        state.setRawMeasurementsSubscribed(rawMeasurementsSubscribed);
        state.setRawCsvStatus(reason == null ? "Finalisation du CSV…" : reason + " — finalisation du CSV…");
        if (error != null) state.setRawCsvError(error);
        if (rawCsvLogger != null && rawCsvLogger.isRequested()) {
            state.setRawCsvRequested(true); // reste vrai jusqu'au callback de fermeture
            rawCsvLogger.stop();
        } else {
            state.setRawCsvRequested(false);
        }
        state.notifyChanged();
    }

    @SuppressLint("MissingPermission")
    private void subscribeRawMeasurementsIfNeeded() {
        boolean previewRequested = state.isExpertPageVisible();
        if ((!rawMeasurementsLogRequested && !previewRequested) || !running || state.isSuspended()
                || rawMeasurementsSubscribed) return;
        if (rawMeasurementsLogRequested && (rawCsvLogger == null || !rawCsvLogger.isReady())) return;
        boolean gpsEnabled;
        try {
            gpsEnabled = locationManager != null
                    && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (RuntimeException error) {
            gpsEnabled = false;
        }
        if (!gpsEnabled) {
            nextRawMeasurementsRetryElapsedMs = android.os.SystemClock.elapsedRealtime()
                    + RAW_MEASUREMENTS_RETRY_MS;
            state.setRawCsvStatus(rawMeasurementsLogRequested
                    ? "GPS désactivé ou fournisseur inaccessible : le CSV reste ouvert en attente."
                    : "GPS désactivé ou fournisseur inaccessible : aperçu brut indisponible.");
            state.notifyChanged();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            if (rawMeasurementsLogRequested) {
                stopRawMeasurementsLogging("Permission de localisation retirée", "ACCESS_FINE_LOCATION absente");
            } else {
                state.setRawCsvStatus("Permission ACCESS_FINE_LOCATION requise pour l'aperçu brut.");
                state.notifyChanged();
            }
            return;
        }

        boolean supported = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // GnssCapabilities.hasMeasurements() est disponible à partir de l'API 31.
            // Android 11 (API 30) expose getGnssCapabilities(), mais pas ce getter :
            // sur API 24–30, on laisse l'inscription du callback déterminer le support.
            try {
                supported = locationManager.getGnssCapabilities().hasMeasurements();
                rawMeasurementsSupportKnown = true;
                rawMeasurementsSupported = supported;
                state.setRawMeasurementsSupportKnown(true);
                state.setRawMeasurementsSupported(supported);
            } catch (RuntimeException ignored) {
                // API ou fournisseur OEM incomplet : l'essai d'abonnement reste
                // l'autorité, la capacité est alors inconnue plutôt que fausse.
                rawMeasurementsSupportKnown = false;
                state.setRawMeasurementsSupportKnown(false);
            }
        } else {
            // Avant API 31, aucun contrôle public équivalent n'est disponible.
            // Ne pas annoncer « non supporté » : tenter l'inscription ci-dessous.
            rawMeasurementsSupportKnown = false;
            rawMeasurementsSupported = false;
            state.setRawMeasurementsSupportKnown(false);
            state.setRawMeasurementsSupported(false);
        }
        if (!supported) {
            nextRawMeasurementsRetryElapsedMs = Long.MAX_VALUE;
            state.setRawCsvStatus("Ce chipset ne déclare pas le support des mesures GNSS brutes.");
            state.setRawCsvError("GnssCapabilities.hasMeasurements() = false");
            if (rawMeasurementsLogRequested) {
                stopRawMeasurementsLogging("Mesures brutes non supportées", "Chipset sans support GnssMeasurementsEvent");
            } else {
                state.setRawMeasurementsSubscribed(false);
                state.notifyChanged();
            }
            return;
        }

        if (rawMeasurementsThread == null || !rawMeasurementsThread.isAlive()
                || rawMeasurementsHandler == null) {
            try {
                rawMeasurementsThread = new android.os.HandlerThread("gnss-measurements-callback",
                        android.os.Process.THREAD_PRIORITY_BACKGROUND);
                rawMeasurementsThread.start();
                rawMeasurementsHandler = new Handler(rawMeasurementsThread.getLooper());
            } catch (RuntimeException error) {
                rawMeasurementsThread = null;
                rawMeasurementsHandler = null;
                nextRawMeasurementsRetryElapsedMs = android.os.SystemClock.elapsedRealtime()
                        + RAW_MEASUREMENTS_RETRY_MS;
                state.setRawCsvStatus("Impossible de démarrer le thread des mesures : "
                        + error.getClass().getSimpleName());
                state.notifyChanged();
                return;
            }
        }

        if (measurementsCallback == null) {
            nextRawMeasurementsRetryElapsedMs = Long.MAX_VALUE;
            state.setRawCsvStatus("Callback GNSS brut non initialisé.");
            if (rawMeasurementsLogRequested) {
                stopRawMeasurementsLogging("Callback GNSS brut indisponible", "Callback non initialisé");
            } else {
                state.setRawMeasurementsSubscribed(false);
                state.notifyChanged();
            }
            return;
        }
        final Handler callbackHandler = rawMeasurementsHandler;
        java.util.concurrent.Executor callbackExecutor = command -> {
            if (callbackHandler == null || !callbackHandler.post(command)) {
                throw new java.util.concurrent.RejectedExecutionException("GNSS callback thread arrêté");
            }
        };
        try {
            boolean registered;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ : le mode full tracking désactive le duty-cycling
                // du chipset. Il peut améliorer la continuité de phase et la
                // couverture des constellations/bandes, mais augmente la
                // consommation et influence aussi les fixes de localisation.
                // Une demande full tracking peut également faire recevoir le
                // mode complet aux autres écouteurs GNSS de l'appareil.
                // Il reste désactivé par défaut et doit être choisi par l'utilisateur.
                android.location.GnssMeasurementRequest request =
                        new android.location.GnssMeasurementRequest.Builder()
                                .setFullTracking(Prefs.readRawFullTracking(this))
                                .build();
                registered = locationManager.registerGnssMeasurementsCallback(
                        request, callbackExecutor, measurementsCallback);
            } else {
                // Android 7 à 11 : compat pour éviter le défaut de livraison
                // de callback sur certains appareils Android 11 pré-QPR1.
                registered = LocationManagerCompat.registerGnssMeasurementsCallback(
                        locationManager, callbackExecutor, measurementsCallback);
            }
            rawMeasurementsSubscribed = registered;
            state.setRawMeasurementsSubscribed(registered);
            if (registered) {
                lastRawMeasurementsElapsedMs = android.os.SystemClock.elapsedRealtime();
                nextRawMeasurementsRetryElapsedMs = 0L;
                state.setRawCsvStatus(rawMeasurementsLogRequested
                        ? "Abonnement GNSS actif ; CSV en cours. La livraison écran éteint dépend d'Android et du constructeur."
                        : "Aperçu GNSS brut actif ; le CSV n'est pas enregistré.");
            } else {
                nextRawMeasurementsRetryElapsedMs = android.os.SystemClock.elapsedRealtime()
                        + RAW_MEASUREMENTS_RETRY_MS;
                state.setRawCsvStatus("Abonnement GNSS brut refusé ; nouvel essai dans 5 s.");
            }
        } catch (SecurityException error) {
            rawMeasurementsSubscribed = false;
            state.setRawMeasurementsSubscribed(false);
            nextRawMeasurementsRetryElapsedMs = Long.MAX_VALUE;
            if (rawMeasurementsLogRequested) {
                stopRawMeasurementsLogging("Permission de localisation retirée", error.getClass().getSimpleName());
            } else {
                state.setRawCsvStatus("Permission de localisation refusée pour les mesures brutes.");
            }
            state.notifyChanged();
            return;
        } catch (IllegalArgumentException | UnsupportedOperationException error) {
            rawMeasurementsSubscribed = false;
            state.setRawMeasurementsSubscribed(false);
            nextRawMeasurementsRetryElapsedMs = Long.MAX_VALUE;
            if (rawMeasurementsLogRequested) {
                stopRawMeasurementsLogging("Mesures brutes indisponibles sur cet appareil",
                        error.getClass().getSimpleName() + " : " + error.getMessage());
            } else {
                state.setRawCsvStatus("Aperçu brut indisponible : " + error.getClass().getSimpleName());
            }
            state.notifyChanged();
            return;
        } catch (IllegalStateException error) {
            // Fournisseur temporairement dans un état non disponible : on
            // garde le fichier ouvert et on réessaie sans boucle serrée.
            rawMeasurementsSubscribed = false;
            state.setRawMeasurementsSubscribed(false);
            nextRawMeasurementsRetryElapsedMs = android.os.SystemClock.elapsedRealtime()
                    + RAW_MEASUREMENTS_RETRY_MS;
            state.setRawCsvStatus("Abonnement GNSS brut temporairement indisponible ; nouvel essai dans 5 s.");
        }
        state.notifyChanged();
    }

    private void unregisterRawMeasurementsCallback() {
        if (rawMeasurementsSubscribed && locationManager != null && measurementsCallback != null) {
            try { LocationManagerCompat.unregisterGnssMeasurementsCallback(locationManager, measurementsCallback); }
            catch (RuntimeException ignored) { }
        }
        rawMeasurementsSubscribed = false;
        nextRawMeasurementsRetryElapsedMs = 0L;
        state.setRawMeasurementsSubscribed(false);
        if (!rawMeasurementsLogRequested && !state.isExpertPageVisible()
                && rawMeasurementsThread != null) {
            rawMeasurementsThread.quitSafely();
            rawMeasurementsThread = null;
            rawMeasurementsHandler = null;
        }
    }


    /** Fabrique un callback LocationListener lié à sa génération d'abonnement. */
    private LocationListener createRegisteredLocationListener() {
        final long generation = locationCallbackGeneration.snapshot();
        return new LocationListener() {
            private boolean current() {
                return locationCallbackGeneration.isCurrent(generation);
            }

            @Override
            public void onLocationChanged(@NonNull Location location) {
                synchronized (gnssProcessingLock) {
                    if (current() && locationListener != null) {
                        locationListener.onLocationChanged(location);
                    }
                }
            }

            @Override
            public void onLocationChanged(@NonNull List<Location> locations) {
                if (locations == null || locations.isEmpty()) return;
                synchronized (gnssProcessingLock) {
                    if (!current() || locationListener == null) return;
                    for (Location location : locations) {
                        if (location != null) locationListener.onLocationChanged(location);
                    }
                }
            }

            @Override
            public void onProviderDisabled(@NonNull String provider) {
                synchronized (gnssProcessingLock) {
                    if (current() && locationListener != null) {
                        locationListener.onProviderDisabled(provider);
                    }
                }
            }

            @Override
            public void onProviderEnabled(@NonNull String provider) {
                synchronized (gnssProcessingLock) {
                    if (current() && locationListener != null) {
                        locationListener.onProviderEnabled(provider);
                    }
                }
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onStatusChanged(@NonNull String provider, int status,
                                        @Nullable Bundle extras) {
                synchronized (gnssProcessingLock) {
                    if (current() && locationListener != null) {
                        locationListener.onStatusChanged(provider, status, extras);
                    }
                }
            }
        };
    }

    /** Fabrique un callback satellites qui ignore les événements d'un ancien abonnement. */
    private GnssStatus.Callback createRegisteredGnssCallback() {
        final long generation = statusCallbackGeneration.snapshot();
        return new GnssStatus.Callback() {
            private boolean current() {
                return statusCallbackGeneration.isCurrent(generation);
            }

            @Override
            public void onSatelliteStatusChanged(@NonNull GnssStatus status) {
                synchronized (gnssProcessingLock) {
                    if (current() && gnssCallback != null) {
                        gnssCallback.onSatelliteStatusChanged(status);
                    }
                }
            }

            @Override
            public void onStarted() {
                synchronized (gnssProcessingLock) {
                    if (current() && gnssCallback != null) gnssCallback.onStarted();
                }
            }

            @Override
            public void onStopped() {
                synchronized (gnssProcessingLock) {
                    if (current() && gnssCallback != null) gnssCallback.onStopped();
                }
            }

            @Override
            public void onFirstFix(int ttffMillis) {
                synchronized (gnssProcessingLock) {
                    if (current() && gnssCallback != null) gnssCallback.onFirstFix(ttffMillis);
                }
            }
        };
    }

    /** Fabrique un callback NMEA lié à sa génération d'abonnement. */
    private OnNmeaMessageListener createRegisteredNmeaListener() {
        final long generation = nmeaCallbackGeneration.snapshot();
        return (message, timestamp) -> {
            synchronized (gnssProcessingLock) {
                if (nmeaCallbackGeneration.isCurrent(generation) && nmeaListener != null) {
                    nmeaListener.onNmeaMessage(message, timestamp);
                }
            }
        };
    }

    /**
     * Les callbacks GNSS s'exécutent sur gnss-events. TrackingState est partagé
     * avec plusieurs Activities : les notifications UI restent donc publiées
     * sur le thread principal. Plusieurs trames rapprochées sont coalescées.
     */
    private void notifyTrackingStateChanged() {
        if (!gnssUiRefreshPending.compareAndSet(false, true)) return;
        boolean posted = ui.post(() -> {
            gnssUiRefreshPending.set(false);
            if (!serviceDestroying) state.notifyChanged();
        });
        if (!posted) gnssUiRefreshPending.set(false);
    }

    // ==================================================================
    // Callbacks capteurs
    // ==================================================================

    private void buildCallbacks() {

        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                if (!running || serviceDestroying || !gnssCallbacksEnabled || state.isSuspended()) return;
                synchronized (gnssProcessingLock) {
                if (!running || serviceDestroying || !gnssCallbacksEnabled || state.isSuspended()) return;
                // DEUX HORLOGES, DEUX RÔLES — ne jamais les confondre :
                //
                //  • nowElapsed : monotone, pour TOUTES les décisions
                //    (péremption, vitesse implicite, durée de perte).
                //  • fixWall    : heure murale, pour ÉCRIRE le <time> du GPX.
                //
                // L'heure murale du téléphone peut reculer (réglage manuel,
                // synchronisation réseau, changement d'heure). Si elle servait
                // à décider, un recul d'une heure ferait rejeter TOUS les
                // points comme périmés pendant une heure.
                final long nowElapsed = android.os.SystemClock.elapsedRealtime();
                // Android donne getElapsedRealtimeNanos() en NANOsecondes.
                // On convertit une seule fois en MILLIsecondes pour les
                // politiques de durée et l'ancre GPX. Une Location fabriquée
                // par un test/ancien fournisseur peut exceptionnellement
                // fournir 0 : dans ce cas, l'instant de réception est le
                // meilleur repli disponible, sans mélanger les unités.
                final long fixElapsedNanos = location.getElapsedRealtimeNanos();
                final long fixElapsed = fixElapsedNanos > 0L
                        ? fixElapsedNanos / 1_000_000L : nowElapsed;
                lastLocationCallbackElapsedMs = nowElapsed;
                final long nowWall = System.currentTimeMillis();
                String trackingNotice = state.getTrackingNotice();
                if (trackingNotice != null && trackingNotice.startsWith("Position GNSS :")) {
                    state.setTrackingNotice(null);
                }
                if (state.getFirstFixMs() < 0) state.setFirstFixMs(location.getTime() > 0 ? location.getTime() : nowWall);
                if (state.getFirstFixElapsedMs() < 0) {
                    state.setFirstFixElapsedMs(Math.max(state.getSessionStartElapsedMs(), fixElapsed));
                }

                double lat = location.getLatitude();
                double lon = location.getLongitude();
                float acc = location.hasAccuracy()
                        ? location.getAccuracy() : Float.NaN;
                long fixWall = location.getTime() > 0 ? location.getTime() : nowWall;

                // NOMBRE DE SATELLITES RÉELLEMENT UTILISÉS.
                // `state.getSatellitesUsed()` vient de GnssStatus.usedInFix —
                // distinct de « en vue », comme vous l'avez souligné. À
                // savoir : GnssStatus est livré SÉPARÉMENT du fix, il peut
                // donc être en retard d'un cycle (1 s). C'est acceptable : un
                // satellite ne disparaît pas d'une solution en une seconde.
                // ... et seulement s'il est FRAIS (fenêtre dépendante de la cadence demandée).
                long statusAge = gnssStatusElapsedMs <= 0 ? Long.MAX_VALUE
                        : nowElapsed - gnssStatusElapsedMs;
                long statusFreshnessMs = gnssStatusMaxAgeMs();
                int satsUsed = (statusAge <= statusFreshnessMs)
                        ? state.getSatellitesUsed() : 0;
                int activeConstellations = (statusAge <= statusFreshnessMs)
                        ? state.getActiveConstellations() : 0;

                // Location.hasAltitude() signifie seulement que le champ est
                // présent ; ce n'est pas une déclaration formelle 2D/3D.
                // Lorsque le GSA est récent, son fixType fournit la dimension
                // déclarée par le récepteur. En 2D explicite, on n'enregistre
                // jamais l'altitude éventuellement propagée par l'OEM.
                final int freshGsaFixType = state.getFreshNmeaFixType(nowElapsed, 5_000L);
                final boolean altitudeAvailable = location.hasAltitude()
                        && freshGsaFixType != 2;

                // ══════════════════════════════════════════════════════════
                // LA DÉCISION EST PRISE ICI, ET NULLE PART AILLEURS.
                //
                // Avant la v5.6, cette méthode faisait setHasFix(true) puis
                // écrivait le point si la latitude n'était pas NaN. C'était
                // très insuffisant :
                //   - une position à 500 m de précision était écrite ;
                //   - une position vieille de dix minutes était écrite ;
                //   - hasFix ne repassait JAMAIS à false, donc un tunnel
                //     laissait l'écran afficher une position figée.
                // ══════════════════════════════════════════════════════════
                FixQuality.Decision d = fixTracker.onFix(lat, lon, acc,
                        fixElapsed, nowElapsed, satsUsed, activeConstellations, altitudeAvailable);

                state.setSignalOk(fixTracker.isOk());
                state.setHasAltitudeFix(d.accept && altitudeAvailable);
                state.setFixAgeMs(fixTracker.ageSinceLastGoodMs(nowElapsed));
                state.setLostForMs(fixTracker.lostForMs(nowElapsed));
                state.setRejectReason(d.accept ? null : d.reason.label);

                // Les champs de position sont mis à jour SEULEMENT si le fix
                // est recevable. Sinon l'écran garde la dernière valeur
                // connue — mais elle sera GRISÉE par l'Activity, qui sait
                // (via signalOk) que ces valeurs ne sont plus vivantes.
                if (d.accept) {
                    // ANCRE DE TRACE : fixée UNE fois, au premier fix
                    // accepté. t0 est la lecture monotone de CE point.
                    if (!fixTracker.isEpochSet()) {
                        fixTracker.beginEpoch(fixWall, fixElapsed);
                    }
                    state.setHasFix(true);
                    state.setLatitude(lat);
                    state.setLongitude(lon);
                    // L'ALTITUDE N'EST PLUS JAMAIS ÉCRASÉE PAR NaN.
                    //
                    // Avant, on faisait setAltitude(hasAltitude() ? … : NaN).
                    // Un fix 2D (courant sous un porche) effaçait donc
                    // l'altitude, et l'écran passait de « 35 m » à « — » sans
                    // explication. Or un fix 2D ne nous DIT RIEN sur
                    // l'altitude : il ne l'a pas mesurée, il ne dit pas
                    // qu'elle vaut « inconnu ».
                    //
                    // La règle appliquée : on GARDE la dernière altitude 3D
                    // connue, et on marque séparément hasAltitudeFix=false.
                    // Le fix horizontal 2D reste valide et s'affiche en ambre.
                    if (altitudeAvailable && Double.isFinite(location.getAltitude())) {
                        state.setAltitude(location.getAltitude());
                    }
                    state.setAccuracy(acc);
                    state.setSpeedMps(location.hasSpeed() ? location.getSpeed() : Float.NaN);
                    state.setBearingDeg(location.hasBearing() ? location.getBearing() : Float.NaN);
                }

                // ══════════════════════════════════════════════════════════
                // ÉCRITURE : deux conditions, pas une.
                //   1. l'enregistrement est actif ;
                //   2. la machine à états dit OK — donc après une reprise,
                //      pas avant la séquence de 3 fixes cohérents.
                //
                // Le point de reprise est donc STRUCTURELLEMENT impossible à
                // écrire : c'est la machine à états qui le retient, pas une
                // condition dispersée dans le code.
                // ══════════════════════════════════════════════════════════
                // ESPACEMENT : le point est valide ET l'enregistrement est
                // actif, mais on n'écrit pas forcément tout. La décision est
                // prise par SamplePolicy (pure, testée) pour que la règle
                // soit vérifiable sans téléphone.
                SamplePolicy.Verdict sample = null;
                modeRejected = false;
                // Vitesse lue UNE fois : elle sert au filtre de précision
                // ET à la règle de l'arrêt. Deux lectures séparées pourraient
                // voir deux valeurs différentes (Location est mutable côté
                // système) et donner deux décisions contradictoires.
                final float speed = location.hasSpeed()
                        ? location.getSpeed() : Float.NaN;
                if (state.isRecording() && fixTracker.shouldWrite()) {
                    // ── FILTRE DU MODE (v5.11) ─────────────────────────────
                    //
                    // Précision : le mode impose un seuil plus serré que le
                    // plafond global de 50 m. En marche, 8 m.
                    // Le point rejeté ici n'est JAMAIS écrit — mais il ne
                    // suspend PAS la trace : le critère de temps écrit une
                    // ancre à la prochaine échéance.
                    String modeReject = TrackMode.rejectReason(acc, speed, modeEff);
                    // Message déjà chiffré par TrackMode ; on y ajoute le
                    // mode pour que l'utilisateur sache QUELLE règle l'a
                    // écarté (sinon il croirait à un défaut du GPS).
                    // ORDRE CRITIQUE : on teste le rejet AVANT de formater.
                    // `new SimpleDateFormat` n'est pas en cause ici mais le
                    // principe l'est : getString(...) avec un argument null
                    // aurait levé une NullPointerException sur CHAQUE point
                    // accepté — c'est-à-dire tout le temps, sauf en cas de
                    // rejet. Le bug le plus difficile à trouver est celui qui
                    // ne se déclenche que dans le cas normal.
                    if (modeReject != null) {
                        state.setModeReject(getString(R.string.mode_reject_banner,
                                modeReject, TrackMode.labelFr(trackMode)));
                        notifyTrackingStateChanged();
                        // On ne SORT PAS du listener : plus bas, l'état
                        // général (signal, compteurs) et la notification
                        // doivent continuer d'être mis à jour. Un `return`
                        // ici aurait figé l'écran après le premier rejet.
                        modeRejected = true;
                    } else {
                        state.setModeReject(null);
                    }
                }
                if (state.isRecording() && fixTracker.shouldWrite() && !modeRejected) {

                    // L'heure de trace est calculée AVANT le sampler : c'est
                    // le même instant qui sert à décider et à écrire. Sinon
                    // les deux dérivent de quelques ms et le premier point
                    // intermédiaire était mal situé dans le temps.
                    // À L'ARRÊT : le GNSS oscille de ±2 à 5 m et fabrique une
                    // « pelote de laine ». On n'écrit donc pas ces points de
                    // mouvement — sauf une ANCRE à échéance fixe, sans quoi
                    // une session immobile de 20 min ne produirait AUCUN point
                    // et l'utilisateur croirait l'enregistrement arrêté.
                    //
                    // L'ANCRE NE PASSE PAS PAR LE SAMPLER. Si elle y passait,
                    // elle serait soumise au critère de temps (10 s en marche)
                    // en plus du critère de distance : deux règles empilées
                    // qui repoussent chacune l'autre. On décide ici, une fois.
                    boolean anchorNow = false;
                    if (TrackMode.isStationary(speed)) {
                        long sinceWrite = (lastWriteElapsedMs < 0)
                                ? Long.MAX_VALUE : fixElapsed - lastWriteElapsedMs;
                        anchorNow = TrackMode.writeAnchorWhenStationary(speed,
                                lastWriteElapsedMs >= 0, sinceWrite, modeEff);
                    }
                    if (anchorNow) {
                        lastAnchorElapsedMs = fixElapsed;
                        anchorPending = true;
                    } else {
                        sample = sampler.offer(minDistanceM, minTimeMs,
                                lat, lon, fixElapsed);
                        state.setPointsSkipped(sampler.skippedCount());
                    }
                }

                if (anchorPending || (sample != null && sample.write)) {
                    anchorPending = false;
                    // COUPURE DE TRACE : insérée UNE SEULE FOIS, au premier
                    // point de la reprise. Le drapeau est consommé ici même.
                    if (pendingSegmentBreak) {
                        pendingSegmentBreak = false;
                        final RecordingSegment segment = activeSegment;
                        if (segment != null && ioFile != null) {
                            boolean breakQueued = ioFile.post(() -> {
                                try {
                                    synchronized (segment.ioLock) {
                                        if (segment.closed || segment.writeFailed || segment.openFailed
                                                || !segment.ioState.canWrite() || segment.writer == null) return;
                                        segment.writer.write(GpxWriter.segmentBreak(segment.version));
                                        segment.writer.flush();
                                        segment.lastFlushElapsedMs = android.os.SystemClock.elapsedRealtime();
                                    }
                                } catch (Exception e) {
                                    segment.writeFailed = true;
                                    reportSegmentGpxError(segment, "Coupure GPX : " + e.getMessage());
                                }
                            });
                            if (!breakQueued) {
                                segment.writeFailed = true;
                                state.setGpxError("Coupure GPX non mise en file : worker indisponible");
                            }
                        }
                    }
                    // HEURE ÉCRITE GARANTIE CROISSANTE. Si l'horloge du
                    // téléphone a reculé, on avance de 1 ms : le décalage est
                    // indétectable sur la trace, et le GPX reste lisible par
                    // tous les outils. On ne jette PAS le point : sa géométrie
                    // est vraie, seule son étiquette de temps est douteuse.
                    // t = heure_du_premier_fix + (elapsed_maintenant - t0)
                    long writeTime = fixTracker.epochTimeMs(fixElapsed);
                    GpxWriter.TrackPoint p = new GpxWriter.TrackPoint(writeTime, lat, lon,
                            altitudeAvailable && Double.isFinite(location.getAltitude())
                                    ? location.getAltitude() : Double.NaN);
                    if (location.hasSpeed()) p.speedMps = location.getSpeed();
                    if (location.hasBearing()) p.courseDeg = location.getBearing();
                    if (location.hasAccuracy()) p.accuracyM = acc;
                    double freshReceiverHdop = state.getFreshRxHdop(nowElapsed, 5_000L);
                    if (Double.isFinite(freshReceiverHdop)) p.hdop = (float) freshReceiverHdop;
                    appendPoint(p);
                    lastWriteElapsedMs = fixElapsed;
                }

                notifyTrackingStateChanged();
                maybeUpdateNotification();
                }
            }

            @Override
            public void onProviderDisabled(@NonNull String provider) {
                if (!running || serviceDestroying || !gnssCallbacksEnabled || state.isSuspended()) return;
                synchronized (gnssProcessingLock) {
                if (!running || serviceDestroying || !gnssCallbacksEnabled || state.isSuspended()) return;
                // L'utilisateur (ou le système) vient de couper le GPS.
                // L'information est FIABLE : pas besoin d'attendre le timeout
                // de 5 s. On force la perte tout de suite, et la politique
                // d'annonce sait qu'il ne faut PAS parler dans ce cas
                // (userDisabled) — le lui dire toutes les 30 s serait du
                // harcèlement pour une décision qu'il a lui-même prise.
                fixTracker.forceLoss();
                pendingSegmentBreak = true;
                sampler.breakSegment();
                state.setHasFix(false);
                state.setSignalOk(false);
                flushNow();
                persistSession();
                ui.post(() -> updateNotification());
                notifyTrackingStateChanged();
                }
            }
            @Override
            public void onProviderEnabled(@NonNull String provider) {
                if (!running || serviceDestroying || !gnssCallbacksEnabled || state.isSuspended()) return;
                // Le GPS est rallumé : on ne remet PAS l'état à OK. La
                // machine à états exigera sa séquence de reprise, exactement
                // comme après un tunnel. Le premier fix après un rallumage
                // est aussi mauvais qu'après une sortie de tunnel.
                notifyTrackingStateChanged();
            }

            /**
             * Callback déprécié mais encore présent sur API 24–29.
             * L'implémentation par défaut n'existe que depuis API 30 :
             * il faut donc explicitement l'implémenter pour notre minSdk 24.
             * Le statut provider n'est pas utilisé comme qualité du fix GNSS.
             */
            @Override
            @SuppressWarnings("deprecation")
            public void onStatusChanged(@NonNull String provider, int status,
                                        @Nullable Bundle extras) {
                // Aucun changement d'état : GnssStatus et les fixes déterminent
                // la qualité réelle du récepteur.
            }
        };

        gnssCallback = new GnssStatus.Callback() {
            @Override
            public void onSatelliteStatusChanged(@NonNull GnssStatus status) {
                if (!running || serviceDestroying || !gnssCallbacksEnabled || state.isSuspended()) return;
                synchronized (gnssProcessingLock) {
                if (!running || serviceDestroying || !gnssCallbacksEnabled || state.isSuspended()) return;
                // HORODATAGE DE CETTE INFORMATION.
                //
                // Le GnssStatus n'est pas atomique avec le fix : il peut être
                // livré avant ou après. On retient le moment où on l'a reçu,
                // pour pouvoir dire honnêtement « ce chiffre a N secondes »
                // au lieu de faire semblant qu'il est instantané.
                gnssStatusElapsedMs = android.os.SystemClock.elapsedRealtime();

                List<SatInfo> list = new ArrayList<>();
                int used = 0;
                float best = 0f;

                // CONSTELLATIONS RÉELLEMENT ACTIVES.
                //
                // Compteur SÉPARÉ, et non un ensemble construit après coup :
                // on ajoute un type de constellation ICI, uniquement s'il a
                // au moins un satellite utilisé dans la solution. Un système
                // présent dans le ciel mais sans satellite utilisé ne coûte
                // pas d'inconnue d'horloge et ne doit donc pas relever le
                // minimum requis.
                //
                // Bitmask plutôt qu'un Set<Integer> : aucun objet créé à
                // chaque seconde.
                int constellationMask = 0;

                for (int i = 0; i < status.getSatelliteCount(); i++) {
                    boolean u = status.usedInFix(i);
                    if (u) {
                        used++;
                        constellationMask |= bitForConstellation(
                                status.getConstellationType(i));
                    }
                    float cn0 = status.getCn0DbHz(i);
                    if (u) best = Math.max(best, cn0);

                    double carrier = Double.NaN;
                    // GnssStatus.hasCarrierFrequencyHz est API 26 (minSdk 24)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        if (status.hasCarrierFrequencyHz(i)) {
                            carrier = status.getCarrierFrequencyHz(i);
                        }
                    }
                    list.add(new SatInfo(
                            Utils.constellationName(status.getConstellationType(i)),
                            status.getSvid(i),
                            status.getAzimuthDegrees(i),
                            status.getElevationDegrees(i),
                            cn0, u, carrier));
                }

                state.replaceSatellites(list);
                state.setSatellitesSeen(status.getSatelliteCount());
                state.setSatellitesUsed(used);
                state.setActiveConstellations(
                        Integer.bitCount(constellationMask));
                state.setMaxCn0(best);
                state.setBandCounters(BandSupport.bandCounters(list));

                int seen = status.getSatelliteCount();
                if (seen > state.getMaxSatellitesSeen()) state.setMaxSatellitesSeen(seen);
                if (best > state.getMaxCn0Seen()) state.setMaxCn0Seen(best);
                // API 34+: capacités officielles du chipset (pas seulement
                // les signaux actuellement visibles). Avant API 34, on ne
                // peut conclure qu'à partir des fréquences observées.
                refreshBandSupport(list);

                state.setCalcDop(DopCalculator.compute(list), android.os.SystemClock.elapsedRealtime());
                notifyTrackingStateChanged();
                maybeUpdateNotification();
                }
            }
        };

        measurementsCallback = new GnssMeasurementsEvent.Callback() {
            @Override
            public void onGnssMeasurementsReceived(@NonNull GnssMeasurementsEvent event) {
                if (!running || serviceDestroying || state.isSuspended()
                        || (!rawMeasurementsLogRequested && !state.isExpertPageVisible())) return;
                final long utcMillis = System.currentTimeMillis();
                final long callbackElapsedNanos = android.os.SystemClock.elapsedRealtimeNanos();
                final android.location.GnssClock clock = event.getClock();
                final List<android.location.GnssAutomaticGainControl> eventAgcs = new ArrayList<>();
                if (Build.VERSION.SDK_INT >= 33) {
                    try { eventAgcs.addAll(event.getGnssAutomaticGainControls()); }
                    catch (RuntimeException ignored) { /* fournisseur OEM partiel */ }
                }
                Boolean fullTracking = null;
                if (Build.VERSION.SDK_INT >= 34) {
                    try { if (event.hasIsFullTracking()) fullTracking = event.isFullTracking(); }
                    catch (RuntimeException ignored) { /* champ optionnel */ }
                }
                RawMeasurementsCsvLogger.Options options = activeRawCsvOptions;
                if (options == null) options = new RawMeasurementsCsvLogger.Options(true, true, true, true, true);

                final long clockTime = clock.getTimeNanos();
                final Double clockTimeUncertainty = clock.hasTimeUncertaintyNanos()
                        ? clock.getTimeUncertaintyNanos() : null;
                final Long clockElapsed = Build.VERSION.SDK_INT >= 29
                        && clock.hasElapsedRealtimeNanos() ? clock.getElapsedRealtimeNanos() : null;
                final Double clockElapsedUncertainty = Build.VERSION.SDK_INT >= 29
                        && clock.hasElapsedRealtimeUncertaintyNanos()
                        ? clock.getElapsedRealtimeUncertaintyNanos() : null;
                final Integer leapSecond = clock.hasLeapSecond() ? clock.getLeapSecond() : null;
                final Long fullBias = clock.hasFullBiasNanos() ? clock.getFullBiasNanos() : null;
                final Double bias = clock.hasBiasNanos() ? clock.getBiasNanos() : null;
                final Double biasUnc = clock.hasBiasUncertaintyNanos()
                        ? clock.getBiasUncertaintyNanos() : null;
                final Double drift = clock.hasDriftNanosPerSecond()
                        ? clock.getDriftNanosPerSecond() : null;
                final Double driftUnc = clock.hasDriftUncertaintyNanosPerSecond()
                        ? clock.getDriftUncertaintyNanosPerSecond() : null;
                final int discontinuity = clock.getHardwareClockDiscontinuityCount();
                Integer isbReferenceConstellationType = null;
                String isbReferenceCodeType = null;
                Double isbReferenceCarrierFrequencyHz = null;
                if (Build.VERSION.SDK_INT >= 30) {
                    try {
                        if (clock.hasReferenceConstellationTypeForIsb())
                            isbReferenceConstellationType = clock.getReferenceConstellationTypeForIsb();
                        if (clock.hasReferenceCodeTypeForIsb())
                            isbReferenceCodeType = clock.getReferenceCodeTypeForIsb();
                        if (clock.hasReferenceCarrierFrequencyHzForIsb())
                            isbReferenceCarrierFrequencyHz = clock.getReferenceCarrierFrequencyHzForIsb();
                    } catch (RuntimeException ignored) {
                        // Les champs de référence ISB sont facultatifs et certains pilotes OEM
                        // n'implémentent pas toutes les valeurs annoncées.
                    }
                }

                List<String> rows = new ArrayList<>();
                StringBuilder preview = new StringBuilder();
                double cn0Sum = 0d;
                int cn0Count = 0;
                for (android.location.GnssMeasurement m : event.getMeasurements()) {
                    final double carrierHz = m.hasCarrierFrequencyHz()
                            ? m.getCarrierFrequencyHz() : Double.NaN;
                    final double cn0 = m.getCn0DbHz();
                    final double rate = m.getPseudorangeRateMetersPerSecond();
                    final double rateUnc = m.getPseudorangeRateUncertaintyMetersPerSecond();
                    final long svTime = m.getReceivedSvTimeNanos();
                    final long svTimeUnc = m.getReceivedSvTimeUncertaintyNanos();
                    final double timeOffset = m.getTimeOffsetNanos();
                    final int measurementState = m.getState();
                    final double adr = m.getAccumulatedDeltaRangeMeters();
                    final double adrUnc = m.getAccumulatedDeltaRangeUncertaintyMeters();
                    final int adrState = m.getAccumulatedDeltaRangeState();
                    final int multipath = m.getMultipathIndicator();

                    String codeType = "";
                    if (Build.VERSION.SDK_INT >= 29 && m.hasCodeType()) {
                        try { codeType = m.getCodeType(); } catch (RuntimeException ignored) { }
                    }
                    Double basebandCn0 = null;
                    if (Build.VERSION.SDK_INT >= 30 && m.hasBasebandCn0DbHz()) {
                        try { basebandCn0 = m.getBasebandCn0DbHz(); } catch (RuntimeException ignored) { }
                    }
                    Double agc = null;
                    if (Build.VERSION.SDK_INT >= 26 && m.hasAutomaticGainControlLevelDb()) {
                        try { agc = m.getAutomaticGainControlLevelDb(); } catch (RuntimeException ignored) { }
                    }
                    Double snr = null;
                    if (Build.VERSION.SDK_INT >= 30) {
                        try { if (m.hasSnrInDb()) snr = m.getSnrInDb(); } catch (RuntimeException ignored) { }
                    }
                    Long carrierCycles = m.hasCarrierCycles() ? m.getCarrierCycles() : null;
                    Double carrierPhase = m.hasCarrierPhase() ? m.getCarrierPhase() : null;
                    Double carrierPhaseUncertainty = m.hasCarrierPhaseUncertainty()
                            ? m.getCarrierPhaseUncertainty() : null;

                    Double fullIsb = null, fullIsbUnc = null;
                    Double satelliteIsb = null, satelliteIsbUnc = null;
                    if (Build.VERSION.SDK_INT >= 30) {
                        try {
                            if (m.hasFullInterSignalBiasNanos()) fullIsb = m.getFullInterSignalBiasNanos();
                            if (m.hasFullInterSignalBiasUncertaintyNanos())
                                fullIsbUnc = m.getFullInterSignalBiasUncertaintyNanos();
                            if (m.hasSatelliteInterSignalBiasNanos())
                                satelliteIsb = m.getSatelliteInterSignalBiasNanos();
                            if (m.hasSatelliteInterSignalBiasUncertaintyNanos())
                                satelliteIsbUnc = m.getSatelliteInterSignalBiasUncertaintyNanos();
                        } catch (RuntimeException ignored) { /* pilote incomplet */ }
                    }

                    rows.add(RawMeasurementsFormatter.detailedCsvLineFull(
                            utcMillis, callbackElapsedNanos,
                            Utils.constellationName(m.getConstellationType()), m.getSvid(),
                            codeType, measurementState, carrierHz, cn0, basebandCn0, agc, snr,
                            rate, rateUnc, svTime, svTimeUnc, timeOffset,
                            adr, adrUnc, adrState, carrierCycles, carrierPhase,
                            carrierPhaseUncertainty, multipath,
                            clockTime, clockTimeUncertainty, clockElapsed,
                            clockElapsedUncertainty, leapSecond, fullBias, bias, biasUnc,
                            drift, driftUnc, fullIsb, fullIsbUnc, satelliteIsb,
                            satelliteIsbUnc, discontinuity, null, fullTracking,
                            isbReferenceConstellationType, isbReferenceCodeType,
                            isbReferenceCarrierFrequencyHz,
                            options.time, options.signal, options.doppler, options.phase));

                    if (Double.isFinite(cn0) && cn0 >= 0) { cn0Sum += cn0; cn0Count++; }
                    preview.append(String.format(Locale.ROOT,
                            "%s %02d %-6s C/N0 %.1f dB-Hz · Doppler %.2f m/s · ADR %.3f m · état 0x%X\n",
                            Utils.constellationName(m.getConstellationType()), m.getSvid(),
                            RawMeasurementsFormatter.bandLabel(carrierHz), cn0,
                            rate, adr, measurementState));
                }

                // GnssAutomaticGainControl et ses getters sont disponibles à partir
                // d'Android 13 (API 33). La lecture de la liste est déjà protégée en
                // amont ; protéger aussi son exploitation évite toute invocation de
                // ces méthodes sur Android 7 à 12 (minSdk 24).
                if (options.signal && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    for (android.location.GnssAutomaticGainControl agcInfo : eventAgcs) {
                        final int constellationType = agcInfo.getConstellationType();
                        final long carrierFrequencyHz = agcInfo.getCarrierFrequencyHz();
                        final double levelDb = agcInfo.getLevelDb();
                        final String constellation = Utils.constellationName(constellationType);
                        rows.add(RawMeasurementsFormatter.agcEventCsvLineFull(
                                utcMillis, callbackElapsedNanos,
                                constellation,
                                carrierFrequencyHz, levelDb,
                                clockTime, fullBias, bias, leapSecond, discontinuity,
                                fullTracking, isbReferenceConstellationType,
                                isbReferenceCodeType, isbReferenceCarrierFrequencyHz,
                                options.time));
                        preview.append(String.format(Locale.ROOT, "AGC %s %-6s %.1f dB\n",
                                constellation,
                                RawMeasurementsFormatter.bandLabel(carrierFrequencyHz),
                                levelDb));
                    }
                }

                RawMeasurementsCsvLogger currentLogger = rawCsvLogger;
                if (rawMeasurementsLogRequested && currentLogger != null && currentLogger.isReady()) {
                    currentLogger.enqueueEvent(rows);
                }
                lastRawMeasurementsElapsedMs = android.os.SystemClock.elapsedRealtime();
                final String previewText = preview.toString();
                final int measurementCount = event.getMeasurements().size();
                final int agcCount = eventAgcs.size();
                final double avgCn0 = cn0Count > 0 ? cn0Sum / cn0Count : Double.NaN;
                // Capturer un texte immuable pour la publication UI. fullTracking est
                // volontairement renseigné après son initialisation (API 34+) et n'est
                // donc pas une variable effectivement finale au sens du langage Java.
                // La lambda ne doit capturer que cet instantané final.
                final String fullTrackingStatus = Build.VERSION.SDK_INT >= 34
                        ? " · full tracking rapporté : "
                                + (fullTracking == null ? "—" : fullTracking.toString())
                        : "";
                ui.post(() -> {
                    state.setRawMeasurementsPreview(previewText.isEmpty()
                            ? "Événement reçu sans mesure satellite exploitable. AGC : " + agcCount
                            : previewText);
                    state.setRawMeasurementsStatusLastCallback("Dernier événement : " + measurementCount
                            + " mesures · " + agcCount + " AGC · C/N0 moyen "
                            + (Double.isFinite(avgCn0) ? String.format(Locale.FRANCE, "%.1f dB-Hz", avgCn0) : "—")
                            + fullTrackingStatus);
                    state.notifyChanged();
                });
            }
        };

        nmeaListener = new OnNmeaMessageListener() {
            @Override
            public void onNmeaMessage(String message, long timestamp) {
                if (!running || serviceDestroying || !gnssCallbacksEnabled || state.isSuspended()) return;
                synchronized (gnssProcessingLock) {
                if (!running || serviceDestroying || !gnssCallbacksEnabled || state.isSuspended()) return;
                state.appendExpertNmeaLine(message);
                RawMeasurementsCsvLogger.Options csvOptions = activeRawCsvOptions;
                RawMeasurementsCsvLogger csvLogger = rawCsvLogger;
                if (rawMeasurementsLogRequested && csvOptions != null && csvOptions.nmea
                        && csvLogger != null && csvLogger.isReady()) {
                    // Le timestamp callback et le temps monotone sont capturés
                    // à la réception, pas déduits du champ timestamp OEM.
                    csvLogger.enqueueEvent(java.util.Collections.singletonList(
                            RawMeasurementsFormatter.nmeaCsvLine(
                                    System.currentTimeMillis(),
                                    android.os.SystemClock.elapsedRealtimeNanos(), message)));
                }
                String type = NmeaParser.sentenceType(message);
                long nmeaElapsedMs = android.os.SystemClock.elapsedRealtime();
                if ("GSA".equals(type)) {
                    NmeaParser.Gsa gsa = NmeaParser.parseGsa(message);
                    if (gsa.valid) {
                        // GSA porte à la fois le type 2D/3D et les DOP du
                        // récepteur. Ces valeurs ne sont pas confondues avec
                        // la qualité GGA (GPS/DGPS/RTK).
                        state.setReceiverDop(gsa.hdop, gsa.vdop, gsa.pdop,
                                gsa.fixType, nmeaElapsedMs);
                    }
                } else if ("GGA".equals(type)) {
                    NmeaParser.Gga gga = NmeaParser.parseGga(message);
                    if (gga.valid) {
                        state.setFixQuality(gga.qualityLabel(), nmeaElapsedMs);
                        state.setGgaHdop(gga.hdop, nmeaElapsedMs);
                        if (gga.fixQuality == 0) {
                            // Une GGA valide annonce explicitement l'absence
                            // de solution : ne pas laisser une ancienne vitesse
                            // NMEA paraître encore actuelle.
                            state.setNmeaSpeed(null, nmeaElapsedMs);
                        }
                    }
                } else if ("GNS".equals(type)) {
                    NmeaParser.Gns gns = NmeaParser.parseGns(message);
                    if (gns.valid) {
                        String mode = gns.mode.isEmpty() ? "—" : gns.mode;
                        state.setNmeaGnsSummary(String.format(Locale.FRANCE,
                                "GNS modes %s · %d SV · HDOP %s",
                                mode, gns.satellitesUsed,
                                Double.isFinite(gns.hdop)
                                        ? String.format(Locale.FRANCE, "%.1f", gns.hdop) : "—"),
                                nmeaElapsedMs);
                        if (!gns.hasAnyFixMode()) state.setNmeaSpeed(null, nmeaElapsedMs);
                    }
                } else if ("GST".equals(type)) {
                    NmeaParser.Gst gst = NmeaParser.parseGst(message);
                    if (gst.valid) state.setNmeaGstSummary(gst.summary(), nmeaElapsedMs);
                } else if ("RMC".equals(type)) {
                    NmeaParser.Rmc rmc = NmeaParser.parseRmc(message);
                    if (NmeaParser.hasAcceptableChecksum(message)
                            && ("V".equals(rmc.status) || "N".equals(rmc.mode))) {
                        // RMC V = warning / données invalides. N'efface que
                        // sur une trame dont l'intégrité (si checksum présent)
                        // est acceptable.
                        state.setNmeaSpeed(null, nmeaElapsedMs);
                    } else if (rmc.valid) {
                        state.setNmeaSpeed(Double.isFinite(rmc.speedKnots)
                                ? String.format(Locale.FRANCE,
                                        "%.1f km/h (%.1f nd)",
                                        rmc.speedKnots * 1.852, rmc.speedKnots)
                                : null, nmeaElapsedMs);
                    }
                } else if ("VTG".equals(type)) {
                    NmeaParser.Vtg vtg = NmeaParser.parseVtg(message);
                    if (vtg.valid) {
                        double kmh = Double.isFinite(vtg.speedKmh)
                                ? vtg.speedKmh : vtg.speedKnots * 1.852;
                        state.setNmeaSpeed(String.format(Locale.FRANCE, "%.1f km/h", kmh),
                                nmeaElapsedMs);
                    } else if (NmeaParser.hasAcceptableChecksum(message) && "N".equals(vtg.mode)) {
                        state.setNmeaSpeed(null, nmeaElapsedMs);
                    }
                } else if ("ZDA".equals(type)) {
                    NmeaParser.Zda zda = NmeaParser.parseZda(message);
                    if (zda.valid) {
                        state.setGpsTime(String.format(Locale.FRANCE, "%s · %02d/%02d/%d",
                                zda.utcTime, zda.day, zda.month, zda.year), nmeaElapsedMs);
                    }
                }
                notifyTrackingStateChanged();
                }
            }
        };

    }

    @android.annotation.TargetApi(34)
    private void refreshBandSupport(List<SatInfo> observedSatellites) {
        String observed = null;
        BandSupport.Profile observedProfile = BandSupport.analyze(
                carrierList(observedSatellites), "observé sur satellites suivis");
        if (!observedProfile.unknown) observed = observedProfile.summary();

        if (Build.VERSION.SDK_INT >= 34) {
            try {
                List<Double> supportedHz = new ArrayList<>();
                for (android.location.GnssSignalType signal : locationManager
                        .getGnssCapabilities().getGnssSignalTypes()) {
                    double hz = signal.getCarrierFrequencyHz();
                    if (hz > 0 && Double.isFinite(hz)) supportedHz.add(hz);
                }
                BandSupport.Profile official = BandSupport.analyze(
                        supportedHz, "capacités déclarées par le chipset (API 34+)");
                String text = official.unknown ? "Capacités chipset : non rapportées"
                        : "Chipset : " + official.summary();
                if (observed != null) text += "\n" + observed;
                state.setBandSupport(text);
                return;
            } catch (RuntimeException ignored) {
                // Certains fournisseurs OEM implémentent partiellement l'API.
            }
        }
        if (observed != null) state.setBandSupport("Bandes observées (pas la liste complète du chipset) : "
                + observedProfile.summary());
    }

    private static List<Double> carrierList(List<SatInfo> list) {
        List<Double> out = new ArrayList<>();
        for (SatInfo s : list) if (s.hasCarrier()) out.add(s.carrierHz);
        return out;
    }

    // ==================================================================
    // Aides statiques : démarrer/arrêter depuis l'Activity
    // ==================================================================

    /**
     * Démarre une session. startForegroundService() est le SEUL cas où l'on a
     * besoin du démarrage « de premier plan » : le service doit ensuite appeler
     * startForeground() sous 5 s.
     */
    public static void start(Context ctx) {
        startCompat(ctx, new Intent(ctx, TrackingService.class).setAction(ACTION_START));
    }

    /**
     * startForegroundService() n'existe qu'à partir de l'API 26 ; minSdk = 24.
     * Sans ce garde, un téléphone Android 7.0 lèverait NoSuchMethodError —
     * l'app crasherait au moment d'appuyer sur DÉMARRER, et seulement là.
     * (Régression que j'avais introduite en réécrivant ces helpers.)
     */
    private static void startCompat(Context ctx, Intent i) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    /**
     * Demande l'arrêt.
     *
     * startService() et PAS startForegroundService() : celui-ci exige que le
     * service se déclare en premier plan dans les 5 s, ce qui est absurde pour
     * un arrêt. Pire, si le service n'existe pas, startForegroundService()
     * crée un service qui doit appeler startForeground() — exactement le
     * « service fantôme » qu'on veut éviter. Si le service n'existe pas, il
     * n'y a rien à arrêter : le démarrage échoue proprement, sans effet.
     */
    public static void stop(Context ctx) {
        try {
            ctx.startService(new Intent(ctx, TrackingService.class)
                    .setAction(ACTION_STOP));
        } catch (Exception ignored) {
            // Service non démarré : rien à faire, et ce n'est pas une erreur.
        }
    }

    public static void startRecording(Context ctx) {
        startCompat(ctx, new Intent(ctx, TrackingService.class).setAction(ACTION_RECORD));
    }

    public static void stopRecording(Context ctx) {
        try {
            ctx.startService(new Intent(ctx, TrackingService.class)
                    .setAction(ACTION_UNRECORD));
        } catch (Exception ignored) {
            // Pas de service, donc pas d'enregistrement à arrêter.
        }
    }

    /**
     * Démarre le CSV depuis l'écran Expert. Le service doit déjà porter une
     * session GPS explicite : aucun foreground service autonome ne sera lancé
     * uniquement pour collecter des mesures brutes.
     */
    public static boolean startRawMeasurementsLogging(Context ctx) {
        if (!TrackingState.get().isUserWantsTracking()) return false;
        try {
            ctx.startService(new Intent(ctx, TrackingService.class).setAction(ACTION_RAW_LOG_START));
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    public static void stopRawMeasurementsLogging(Context ctx) {
        try {
            ctx.startService(new Intent(ctx, TrackingService.class).setAction(ACTION_RAW_LOG_STOP));
        } catch (Exception ignored) { }
    }

    /**
     * Indique la visibilité de la page Expert. Le watchdog du service observe
     * cet état ; on ne démarre jamais un service depuis onPause() uniquement
     * pour lui demander de couper un callback.
     */
    public static void setExpertPageVisible(boolean visible) {
        TrackingState.get().setExpertPageVisible(visible);
    }

    /**
     * Applique à chaud un changement de demande full tracking, si le service
     * suit déjà l'utilisateur. Aucun service autonome n'est démarré.
     */
    public static void refreshRawMeasurementsRequest(Context ctx) {
        if (!TrackingState.get().isUserWantsTracking()) return;
        try {
            ctx.startService(new Intent(ctx, TrackingService.class)
                    .setAction(ACTION_RAW_OPTIONS_CHANGED));
        } catch (Exception ignored) { }
    }

    /**
     * Change la politique d'arrière-plan : stockage + état partagé, et
     * prévient le service SEULEMENT s'il tourne.
     *
     * Détail qui compte : démarrer un foreground service juste pour changer
     * un réglage ferait clignoter une notification permanente et pourrait
     * laisser un service fantôme. Le service relit de toute façon la
     * politique dans onCreate au prochain démarrage.
     */
    public static void setPolicy(Context ctx, BackgroundPolicy.Policy p) {
        if (p == null) p = BackgroundPolicy.Policy.CONTINUE;
        Prefs.writePolicy(ctx, p);
        TrackingState.get().setPolicy(p);
        if (!TrackingState.get().isUserWantsTracking()) return;
        try {
            ctx.startService(new Intent(ctx, TrackingService.class)
                    .setAction(ACTION_SET_POLICY)
                    .putExtra(EXTRA_POLICY_ID, p.id));
        } catch (Exception ignored) {
            // Le service ne tourne pas : la valeur persistée suffit.
        }
    }
}
