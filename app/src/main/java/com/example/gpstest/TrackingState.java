package com.example.gpstest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ÉTAT PARTAGÉ entre l'Activity et le Service.
 *
 * C'est un singleton volontairement simple : il survit à la rotation de l'écran
 * (l'Activity est détruite et recréée, pas cet objet) et il est accessible depuis
 * le service d'arrière-plan. Pour un projet d'apprentissage c'est le bon compromis ;
 * sur une vraie app de production on utiliserait un ViewModel + LiveData/Flow.
 *
 * Aucun import Android : la classe est testable hors appareil.
 */
public final class TrackingState {

    private static final TrackingState INSTANCE = new TrackingState();

    public static TrackingState get() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------
    // Intention de l'utilisateur (persiste tant qu'il n'appuie pas sur ARRÊTER)
    // ------------------------------------------------------------------
    private volatile boolean userWantsTracking = false;

    // ------------------------------------------------------------------
    // Session
    // ------------------------------------------------------------------
    /** Horloge civile : historique/journal uniquement. */
    private volatile long sessionStartMs = 0;
    private volatile long firstFixMs = -1;
    /** Horloge monotone : durées et TTFF, jamais currentTimeMillis(). */
    private volatile long sessionStartElapsedMs = 0;
    private volatile long firstFixElapsedMs = -1;
    private volatile boolean hasFix = false;

    // ------------------------------------------------------------------
    // Position
    // ------------------------------------------------------------------
    private volatile double latitude = Double.NaN;
    private volatile double longitude = Double.NaN;
    private volatile double altitude = Double.NaN;
    private volatile float accuracy = Float.NaN;
    private volatile float speedMps = Float.NaN;
    private volatile float bearingDeg = Float.NaN;

    // ------------------------------------------------------------------
    // Satellites
    // ------------------------------------------------------------------
    private final List<SatInfo> satellites = new ArrayList<>();
    private volatile int satellitesSeen = 0;
    private volatile int satellitesUsed = 0;
    /** Constellations DISTINCTES ayant au moins un satellite utilisé. */
    private volatile int activeConstellations = 0;

    // Chaîne de traitement GPX : accepté -> mis en file -> écrit.
    // Les compteurs sont cumulatifs pendant l'enregistrement ; queueDepth est
    // dérivé et doit revenir à zéro après vidage ou fermeture.
    /** Epoch protects counters from delayed writes of a previous GPX segment. */
    private int trackCounterEpoch = 0;
    private volatile int pointsAccepted = 0;
    private volatile int pointsQueued = 0;
    private volatile int pointsWritten = 0;
    private volatile int pointsFailedBeforeQueue = 0;
    private volatile int pointsFailedAfterQueue = 0;
    private volatile int gpxWriteQueueDepth = 0;
    private volatile int pointsSkipped = 0;
    /** false lorsque la dernière position est un fix 2D sans altitude valide. */
    private volatile boolean hasAltitudeFix = false;

    /** Raison du rejet par le mode (précision), ou null. */
    private volatile String modeReject = null;

    /** Résultat du nettoyage de fin de session, ou null. */
    private volatile String cleanResult = null;
    private volatile float maxCn0 = 0f;
    private volatile String bandCounters = null;
    private volatile String bandSupport = null;

    // ------------------------------------------------------------------
    // DOP
    // ------------------------------------------------------------------
    private volatile double rxHdop = Double.NaN, rxVdop = Double.NaN, rxPdop = Double.NaN;
    /** Instant monotone de réception des valeurs NMEA GSA. */
    private volatile long rxDopElapsedMs = 0L;
    /** HDOP rapporté par GGA : source de secours si GSA n'est pas émis. */
    private volatile double ggaHdop = Double.NaN;
    private volatile long ggaHdopElapsedMs = 0L;
    /** Type GSA : 1 aucun, 2D, 3D ; 0 inconnu ou périmé. */
    private volatile int nmeaFixType = 0;
    private volatile long nmeaFixTypeElapsedMs = 0L;
    /** Qualité GGA, indépendante de la dimension GSA et datée pour expirer. */
    private volatile String fixQuality = null;
    private volatile long fixQualityElapsedMs = 0L;
    private volatile DopCalculator.Dop calcDop = null;
    private volatile long calcDopElapsedMs = 0L;
    private volatile String nmeaGnsSummary = null;
    private volatile long nmeaGnsSummaryElapsedMs = 0L;
    private volatile String nmeaGstSummary = null;
    private volatile long nmeaGstSummaryElapsedMs = 0L;

    // ------------------------------------------------------------------
    // NMEA secondaire et télémétrie GNSS brute (mode Expert)
    // ------------------------------------------------------------------
    private volatile String gpsTime = null;
    private volatile long gpsTimeElapsedMs = 0L;
    private volatile String nmeaSpeed = null;
    private volatile long nmeaSpeedElapsedMs = 0L;
    private volatile boolean rawMeasurementsSupportKnown = false;
    private volatile boolean rawMeasurementsSupported = false;
    private volatile boolean rawMeasurementsSubscribed = false;
    /** L'Expert demande un aperçu brut tant que sa page est visible. */
    private volatile boolean expertPageVisible = false;
    /** Vrai pendant l'ouverture, l'acquisition ou la fermeture du journal CSV. */
    private volatile boolean rawCsvRequested = false;
    /** Propriétaire du CSV courant ; empêche un ancien service de polluer la session suivante. */
    private volatile long rawCsvGeneration = 0L;
    private volatile String rawCsvStatus = "Aucun journal GNSS brut actif";
    private volatile String rawCsvPath = null;
    private volatile String rawCsvError = null;
    private volatile String rawCsvDurabilityWarning = null;
    private volatile String rawMeasurementsLastCallbackStatus = "";
    private volatile int rawCsvEventsReceived = 0;
    private volatile int rawCsvRowsQueued = 0;
    private volatile int rawCsvRowsPending = 0;
    private volatile int rawCsvRowsWritten = 0;
    private volatile int rawCsvRowsDropped = 0;
    private volatile String rawMeasurementsPreview = "";
    private final List<String> expertNmeaLines = new ArrayList<>();
    private static final int MAX_EXPERT_NMEA_LINES = 200;

    // ------------------------------------------------------------------
    // Qualité du signal (v5.6)
    //
    // POURQUOI CES TROIS CHAMPS SONT SÉPARÉS DE `hasFix` :
    //   hasFix     = « a-t-on déjà eu une position dans cette session ? »
    //   signalOk   = « a-t-on une position VALIDE MAINTENANT ? »
    // Ce sont deux questions différentes. Les confondre était le bug : hasFix
    // restait vrai après un tunnel, et l'écran affichait une position périmée.
    // ------------------------------------------------------------------
    /** true si la réception est valide À CET INSTANT. */
    private volatile boolean signalOk = false;
    /** Âge du dernier bon fix, en ms (-1 si aucun). */
    private volatile long fixAgeMs = -1L;
    /** Durée de l'épisode de perte courant, en ms (-1 si pas en perte). */
    private volatile long lostForMs = -1L;
    /** Raison du dernier rejet, pour l'afficher sans mentir. */
    private volatile String rejectReason = null;

    // ------------------------------------------------------------------
    // Statistiques de session
    // ------------------------------------------------------------------
    private volatile int maxSatellitesSeen = 0;
    private volatile float maxCn0Seen = 0f;

    // ------------------------------------------------------------------
    // Enregistrement GPX
    // ------------------------------------------------------------------
    private volatile boolean recording = false;
    private volatile int trackPoints = 0;
    private volatile String lastGpxFile = null;
    private volatile String gpxError = null;
    /** Défaut ou avertissement du suivi GNSS/service, indépendant des erreurs de fichier. */
    private volatile String trackingNotice = null;
    /** Synchronisation physique GPX non confirmée : les points restent écrits/visibles. */
    private volatile String gpxDurabilityWarning = null;
    /** Message d'information persistant (ex. « session précédente interrompue »).
     *  Distinct de gpxError : une erreur d'écriture est urgente, une notice
     *  d'historique ne l'est pas. Les mélanger finit par masquer l'un ou l'autre. */
    private volatile String sessionNotice = null;

    /** Destination EXTERNE de la trace (choix de l'utilisateur), lue au
     *  démarrage de l'enregistrement. null = dossier privé de l'application.
     *  Volontairement figée pendant toute la trace : si l'utilisateur change
     *  de répertoire en cours de route, la trace courante ne doit pas se
     *  retrouver à moitié dans deux dossiers différents. */
    private volatile String destLabel = null;
    /** Dernier état de la publication externe, pour l'afficher sans mentir :
     *  null = pas de destination externe, "ok" = publiée, "err:…" = échec. */
    private volatile String publishStatus = null;
    /** Nom du fichier publié dans le répertoire de l'utilisateur. */
    private volatile String publishedName = null;

    // ------------------------------------------------------------------
    // Politique d'arrière-plan + état de l'écran (v5)
    //
    // Pourquoi ici et pas seulement dans les préférences : le service ET
    // l'Activity doivent lire la même valeur au même instant. Le
    // SharedPreferences est le stockage (il survit au redémarrage), ce champ
    // est le reflet en mémoire (il est lu des dizaines de fois par seconde).
    // ------------------------------------------------------------------
    private volatile BackgroundPolicy.Policy policy = BackgroundPolicy.Policy.CONTINUE;
    /** true = écran allumé, false = éteint. */
    private volatile boolean screenOn = true;
    /** true dès que le premier ACTION_SCREEN_* a été reçu. Avant, on ne sait pas. */
    private volatile boolean screenStateKnown = false;
    /** true si le suivi est actuellement suspendu par la politique. */
    private volatile boolean suspended = false;
    /** Numéro du segment GPX courant (0 = première trace de la session). */
    private volatile int gpxSegment = 0;

    // ------------------------------------------------------------------
    // Écouteurs (l'Activity s'abonne au retour au premier plan)
    // ------------------------------------------------------------------
    public interface Listener {
        void onTrackingStateChanged();
    }

    private final List<Listener> listeners =
            Collections.synchronizedList(new ArrayList<>());

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Notifie l'UI. Toujours appelé sur le thread principal par le service. */
    public void notifyChanged() {
        List<Listener> copy;
        synchronized (listeners) {
            copy = new ArrayList<>(listeners);
        }
        for (Listener l : copy) {
            l.onTrackingStateChanged();
        }
    }

    /** Appelé quand l'utilisateur démarre une nouvelle session. */
    public void resetSession(long nowMs) {
        resetSession(nowMs, 0L);
    }

    public void resetSession(long nowWallMs, long nowElapsedMs) {
        signalOk = false;
        fixAgeMs = -1L;
        lostForMs = -1L;
        rejectReason = null;
        sessionStartMs = nowWallMs;
        firstFixMs = -1;
        sessionStartElapsedMs = Math.max(0L, nowElapsedMs);
        firstFixElapsedMs = -1L;
        hasFix = false;
        latitude = Double.NaN;
        longitude = Double.NaN;
        altitude = Double.NaN;
        accuracy = Float.NaN;
        speedMps = Float.NaN;
        bearingDeg = Float.NaN;
        synchronized (satellites) { satellites.clear(); }
        satellitesSeen = 0;
        satellitesUsed = 0;
        activeConstellations = 0;
        // Invalide aussi les tâches I/O d'une session précédente. Un segment
        // peut terminer sa fermeture asynchrone après le redémarrage du suivi ;
        // son epoch ancien l'empêche alors de modifier les nouveaux compteurs.
        resetTrackCountersAndGetEpoch();
        gpxDurabilityWarning = null;
        trackingNotice = null;
        hasAltitudeFix = false;
        modeReject = null;
        cleanResult = null;
        maxCn0 = 0f;
        bandCounters = null;
        rxHdop = rxVdop = rxPdop = Double.NaN;
        rxDopElapsedMs = 0L;
        ggaHdop = Double.NaN;
        ggaHdopElapsedMs = 0L;
        nmeaFixType = 0;
        nmeaFixTypeElapsedMs = 0L;
        fixQuality = null;
        fixQualityElapsedMs = 0L;
        calcDop = null;
        calcDopElapsedMs = 0L;
        nmeaGnsSummary = null;
        nmeaGnsSummaryElapsedMs = 0L;
        nmeaGstSummary = null;
        nmeaGstSummaryElapsedMs = 0L;
        gpsTime = null;
        gpsTimeElapsedMs = 0L;
        nmeaSpeed = null;
        nmeaSpeedElapsedMs = 0L;
        maxSatellitesSeen = 0;
        maxCn0Seen = 0f;
    }

    /** Copie défensive : l'UI peut parcourir la liste sans risque de mutation. */
    public List<SatInfo> snapshotSatellites() {
        synchronized (satellites) {
            return new ArrayList<>(satellites);
        }
    }

    public void replaceSatellites(List<SatInfo> newList) {
        synchronized (satellites) {
            satellites.clear();
            if (newList != null) satellites.addAll(newList);
        }
    }

    // ------------------------------------------------------------------
    // Getters / setters
    // ------------------------------------------------------------------

    public boolean isUserWantsTracking() { return userWantsTracking; }
    public void setUserWantsTracking(boolean v) { this.userWantsTracking = v; }

    public long getSessionStartMs() { return sessionStartMs; }
    public long getFirstFixMs() { return firstFixMs; }
    public void setFirstFixMs(long v) { this.firstFixMs = v; }
    public long getSessionStartElapsedMs() { return sessionStartElapsedMs; }
    public long getFirstFixElapsedMs() { return firstFixElapsedMs; }
    public void setFirstFixElapsedMs(long v) { this.firstFixElapsedMs = v; }
    public boolean hasFix() { return hasFix; }
    public void setHasFix(boolean v) { this.hasFix = v; }

    public double getLatitude() { return latitude; }
    public void setLatitude(double v) { this.latitude = v; }
    public double getLongitude() { return longitude; }
    public void setLongitude(double v) { this.longitude = v; }
    public double getAltitude() { return altitude; }
    public void setAltitude(double v) { this.altitude = v; }
    public float getAccuracy() { return accuracy; }
    public void setAccuracy(float v) { this.accuracy = v; }
    public float getSpeedMps() { return speedMps; }
    public void setSpeedMps(float v) { this.speedMps = v; }
    public float getBearingDeg() { return bearingDeg; }
    public void setBearingDeg(float v) { this.bearingDeg = v; }

    public int getSatellitesSeen() { return satellitesSeen; }
    public void setSatellitesSeen(int v) { this.satellitesSeen = v; }
    public int getSatellitesUsed() { return satellitesUsed; }
    public int getActiveConstellations() { return activeConstellations; }
    public void setActiveConstellations(int v) { this.activeConstellations = v; }

    /** Points sérialisés avec succès par BufferedWriter (pas une garantie de fsync). */
    public String getCleanResult() { return cleanResult; }
    public void setCleanResult(String r) { this.cleanResult = r; }

    public String getModeReject() { return modeReject; }
    public void setModeReject(String r) { this.modeReject = r; }

    public int getPointsAccepted() { return pointsAccepted; }
    public void setPointsAccepted(int v) { this.pointsAccepted = Math.max(0, v); }
    public int getPointsQueued() { return pointsQueued; }
    public void setPointsQueued(int v) { this.pointsQueued = Math.max(0, v); }
    public int getPointsWritten() { return pointsWritten; }
    public void setPointsWritten(int v) { this.pointsWritten = Math.max(0, v); }
    /** Total des échecs : points non mis en file + écritures ayant échoué. */
    public synchronized int getPointsFailed() {
        return pointsFailedBeforeQueue + pointsFailedAfterQueue;
    }
    public synchronized int getPointsFailedBeforeQueue() { return pointsFailedBeforeQueue; }
    public synchronized int getPointsFailedAfterQueue() { return pointsFailedAfterQueue; }

    /**
     * Réinitialise les compteurs d'une nouvelle trace.
     * À appeler comme barrière dans la FIFO du writer, après la fermeture
     * du segment précédent, sinon ses dernières écritures pourraient être
     * comptabilisées dans la nouvelle trace.
     */
    public synchronized void resetTrackCounters() {
        resetTrackCountersAndGetEpoch();
    }

    /**
     * Commence une nouvelle génération de compteurs et renvoie son identifiant.
     * Une ancienne tâche GPX peut terminer après le démarrage d'une nouvelle
     * trace : les mises à jour portant l'ancien epoch sont alors ignorées.
     * Cela évite une barrière d'E/S bloquante et la course reset/append.
     */
    public synchronized int resetTrackCountersAndGetEpoch() {
        trackCounterEpoch++;
        pointsAccepted = 0;
        pointsQueued = 0;
        pointsWritten = 0;
        pointsFailedBeforeQueue = 0;
        pointsFailedAfterQueue = 0;
        gpxWriteQueueDepth = 0;
        pointsSkipped = 0;
        trackPoints = 0;
        return trackCounterEpoch;
    }

    public synchronized int getTrackCounterEpoch() { return trackCounterEpoch; }

    /** Mutation atomique des compteurs. Les overloads epoch protègent contre les tâches obsolètes. */
    public synchronized void incrementPointsAccepted() { pointsAccepted++; }
    public synchronized void incrementPointsAccepted(int epoch) {
        if (epoch == trackCounterEpoch) pointsAccepted++;
    }
    public synchronized void incrementPointsQueued() { pointsQueued++; updateQueueDepth(); }
    public synchronized void incrementPointsQueued(int epoch) {
        if (epoch == trackCounterEpoch) { pointsQueued++; updateQueueDepth(); }
    }
    public synchronized void incrementPointsWritten() {
        pointsWritten++; trackPoints = pointsWritten; updateQueueDepth();
    }
    public synchronized void incrementPointsWritten(int epoch) {
        if (epoch == trackCounterEpoch) {
            pointsWritten++; trackPoints = pointsWritten; updateQueueDepth();
        }
    }
    /** Échec avant la file (post refusé ou segment déjà défaillant). */
    public synchronized void incrementPointsFailedBeforeQueue() {
        pointsFailedBeforeQueue++; updateQueueDepth();
    }
    public synchronized void incrementPointsFailedBeforeQueue(int epoch) {
        if (epoch == trackCounterEpoch) { pointsFailedBeforeQueue++; updateQueueDepth(); }
    }
    /** Échec d'une tâche déjà comptée dans pointsQueued. */
    public synchronized void incrementPointsFailedAfterQueue() {
        pointsFailedAfterQueue++; updateQueueDepth();
    }
    public synchronized void incrementPointsFailedAfterQueue(int epoch) {
        if (epoch == trackCounterEpoch) { pointsFailedAfterQueue++; updateQueueDepth(); }
    }
    private void updateQueueDepth() {
        // Les échecs pré-file ne doivent PAS être soustraits aux travaux
        // réellement en attente : seuls les travaux échoués après enqueue
        // sont des éléments terminés de la file.
        gpxWriteQueueDepth = Math.max(0,
                pointsQueued - pointsWritten - pointsFailedAfterQueue);
    }
    public int getGpxWriteQueueDepth() { return gpxWriteQueueDepth; }
    public void setGpxWriteQueueDepth(int v) { this.gpxWriteQueueDepth = Math.max(0, v); }
    public boolean hasAltitudeFix() { return hasAltitudeFix; }
    public void setHasAltitudeFix(boolean v) { this.hasAltitudeFix = v; }

    /** Points valides mais écartés par la politique d'espacement. */
    public int getPointsSkipped() { return pointsSkipped; }
    public void setPointsSkipped(int v) { this.pointsSkipped = v; }
    public void setSatellitesUsed(int v) { this.satellitesUsed = v; }
    public float getMaxCn0() { return maxCn0; }
    public void setMaxCn0(float v) { this.maxCn0 = v; }
    public String getBandCounters() { return bandCounters; }
    public void setBandCounters(String v) { this.bandCounters = v; }
    public String getBandSupport() { return bandSupport; }
    public void setBandSupport(String v) { this.bandSupport = v; }

    public double getRxHdop() { return rxHdop; }
    public void setRxHdop(double v) { this.rxHdop = v; }
    public double getRxVdop() { return rxVdop; }
    public void setRxVdop(double v) { this.rxVdop = v; }
    public double getRxPdop() { return rxPdop; }
    public void setRxPdop(double v) { this.rxPdop = v; }

    /** Publication atomique des valeurs GSA et de leur date de fraîcheur. */
    public synchronized void setReceiverDop(double h, double v, double p,
                                             int fixType, long elapsedMs) {
        rxHdop = h;
        rxVdop = v;
        rxPdop = p;
        rxDopElapsedMs = Math.max(0L, elapsedMs);
        nmeaFixType = (fixType >= 1 && fixType <= 3) ? fixType : 0;
        nmeaFixTypeElapsedMs = Math.max(0L, elapsedMs);
    }

    public synchronized double getFreshRxHdop(long nowElapsedMs, long maxAgeMs) {
        return isFresh(nowElapsedMs, rxDopElapsedMs, maxAgeMs) ? rxHdop : Double.NaN;
    }
    public synchronized double getFreshRxVdop(long nowElapsedMs, long maxAgeMs) {
        return isFresh(nowElapsedMs, rxDopElapsedMs, maxAgeMs) ? rxVdop : Double.NaN;
    }
    public synchronized double getFreshRxPdop(long nowElapsedMs, long maxAgeMs) {
        return isFresh(nowElapsedMs, rxDopElapsedMs, maxAgeMs) ? rxPdop : Double.NaN;
    }
    public synchronized void setGgaHdop(double value, long elapsedMs) {
        ggaHdop = value;
        ggaHdopElapsedMs = Math.max(0L, elapsedMs);
    }
    public synchronized double getFreshGgaHdop(long nowElapsedMs, long maxAgeMs) {
        return isFresh(nowElapsedMs, ggaHdopElapsedMs, maxAgeMs) ? ggaHdop : Double.NaN;
    }
    public synchronized int getFreshNmeaFixType(long nowElapsedMs, long maxAgeMs) {
        return isFresh(nowElapsedMs, nmeaFixTypeElapsedMs, maxAgeMs) ? nmeaFixType : 0;
    }
    private static boolean isFresh(long now, long sample, long maxAge) {
        return sample > 0L && now >= sample && maxAge >= 0L && now - sample <= maxAge;
    }

    /** Compatibilité ancienne : valeur non datée, donc non considérée fraîche. */
    public synchronized String getFixQuality() { return fixQuality; }
    public synchronized void setFixQuality(String v) {
        this.fixQuality = v;
        this.fixQualityElapsedMs = 0L;
    }
    public synchronized void setFixQuality(String value, long elapsedMs) {
        fixQuality = value;
        fixQualityElapsedMs = Math.max(0L, elapsedMs);
    }
    public synchronized String getFreshFixQuality(long nowElapsedMs, long maxAgeMs) {
        return isFresh(nowElapsedMs, fixQualityElapsedMs, maxAgeMs) ? fixQuality : null;
    }
    public DopCalculator.Dop getCalcDop() { return calcDop; }
    public void setCalcDop(DopCalculator.Dop v) { this.calcDop = v; }
    public synchronized void setCalcDop(DopCalculator.Dop v, long elapsedMs) {
        calcDop = v;
        calcDopElapsedMs = Math.max(0L, elapsedMs);
    }
    public synchronized DopCalculator.Dop getFreshCalcDop(long nowElapsedMs, long maxAgeMs) {
        return isFresh(nowElapsedMs, calcDopElapsedMs, maxAgeMs) ? calcDop : null;
    }

    public synchronized void setNmeaGnsSummary(String value, long elapsedMs) {
        nmeaGnsSummary = value;
        nmeaGnsSummaryElapsedMs = Math.max(0L, elapsedMs);
    }
    public synchronized String getFreshNmeaGnsSummary(long nowElapsedMs, long maxAgeMs) {
        return isFresh(nowElapsedMs, nmeaGnsSummaryElapsedMs, maxAgeMs) ? nmeaGnsSummary : null;
    }
    public synchronized void setNmeaGstSummary(String value, long elapsedMs) {
        nmeaGstSummary = value;
        nmeaGstSummaryElapsedMs = Math.max(0L, elapsedMs);
    }
    public synchronized String getFreshNmeaGstSummary(long nowElapsedMs, long maxAgeMs) {
        return isFresh(nowElapsedMs, nmeaGstSummaryElapsedMs, maxAgeMs) ? nmeaGstSummary : null;
    }

    /** Valeur brute conservée pour compatibilité ; l'UI doit préférer la variante fraîche. */
    public String getGpsTime() { return gpsTime; }
    public synchronized void setGpsTime(String v) {
        this.gpsTime = v;
        this.gpsTimeElapsedMs = 0L;
    }
    public synchronized void setGpsTime(String value, long elapsedMs) {
        gpsTime = value;
        gpsTimeElapsedMs = Math.max(0L, elapsedMs);
    }
    public synchronized String getFreshGpsTime(long nowElapsedMs, long maxAgeMs) {
        return isFresh(nowElapsedMs, gpsTimeElapsedMs, maxAgeMs) ? gpsTime : null;
    }

    /** Vitesse sol provenant de NMEA (RMC/VTG), distincte de Location.getSpeed(). */
    public String getNmeaSpeed() { return nmeaSpeed; }
    public synchronized void setNmeaSpeed(String v) {
        this.nmeaSpeed = v;
        this.nmeaSpeedElapsedMs = 0L;
    }
    public synchronized void setNmeaSpeed(String value, long elapsedMs) {
        nmeaSpeed = value;
        nmeaSpeedElapsedMs = Math.max(0L, elapsedMs);
    }
    public synchronized String getFreshNmeaSpeed(long nowElapsedMs, long maxAgeMs) {
        return isFresh(nowElapsedMs, nmeaSpeedElapsedMs, maxAgeMs) ? nmeaSpeed : null;
    }

    public boolean isRawMeasurementsSupportKnown() { return rawMeasurementsSupportKnown; }
    public void setRawMeasurementsSupportKnown(boolean v) { rawMeasurementsSupportKnown = v; }
    public boolean isRawMeasurementsSupported() { return rawMeasurementsSupported; }
    public void setRawMeasurementsSupported(boolean v) { rawMeasurementsSupported = v; }
    public boolean isRawMeasurementsSubscribed() { return rawMeasurementsSubscribed; }
    public void setRawMeasurementsSubscribed(boolean v) { rawMeasurementsSubscribed = v; }
    public boolean isExpertPageVisible() { return expertPageVisible; }
    public void setExpertPageVisible(boolean v) { expertPageVisible = v; }
    public boolean isRawCsvRequested() { return rawCsvRequested; }
    public void setRawCsvRequested(boolean v) { rawCsvRequested = v; }
    public long getRawCsvGeneration() { return rawCsvGeneration; }
    public void setRawCsvGeneration(long v) { rawCsvGeneration = v; }
    public String getRawCsvStatus() { return rawCsvStatus; }
    public void setRawCsvStatus(String v) { rawCsvStatus = v == null ? "" : v; }
    public String getRawCsvPath() { return rawCsvPath; }
    public void setRawCsvPath(String v) { rawCsvPath = v; }
    public String getRawCsvError() { return rawCsvError; }
    public void setRawCsvError(String v) { rawCsvError = v; }
    public String getRawCsvDurabilityWarning() { return rawCsvDurabilityWarning; }
    public void setRawCsvDurabilityWarning(String v) { rawCsvDurabilityWarning = v; }
    public String getRawMeasurementsLastCallbackStatus() { return rawMeasurementsLastCallbackStatus; }
    public void setRawMeasurementsStatusLastCallback(String v) {
        rawMeasurementsLastCallbackStatus = v == null ? "" : v;
    }
    public int getRawCsvEventsReceived() { return rawCsvEventsReceived; }
    public int getRawCsvRowsQueued() { return rawCsvRowsQueued; }
    public int getRawCsvRowsPending() { return rawCsvRowsPending; }
    public int getRawCsvRowsWritten() { return rawCsvRowsWritten; }
    public int getRawCsvRowsDropped() { return rawCsvRowsDropped; }
    public synchronized void setRawCsvCounters(int events, int queued, int pending,
            int written, int dropped) {
        rawCsvEventsReceived = Math.max(0, events);
        rawCsvRowsQueued = Math.max(0, queued);
        rawCsvRowsPending = Math.max(0, pending);
        rawCsvRowsWritten = Math.max(0, written);
        rawCsvRowsDropped = Math.max(0, dropped);
    }
    public String getRawMeasurementsPreview() { return rawMeasurementsPreview; }
    public void setRawMeasurementsPreview(String v) {
        rawMeasurementsPreview = v == null ? "" : v;
    }
    public synchronized void appendExpertNmeaLine(String line) {
        if (line == null) return;
        String normalized = line.trim();
        if (normalized.isEmpty()) return;
        expertNmeaLines.add(normalized);
        while (expertNmeaLines.size() > MAX_EXPERT_NMEA_LINES) {
            expertNmeaLines.remove(0);
        }
    }
    public synchronized String getExpertNmeaText() {
        StringBuilder out = new StringBuilder(expertNmeaLines.size() * 48);
        for (String line : expertNmeaLines) out.append(line).append('\n');
        return out.toString();
    }
    public synchronized void clearExpertNmeaLines() { expertNmeaLines.clear(); }

    /**
     * Remet les deux maxima à zéro.
     *
     * Le service relit ces valeurs à chaque callback GNSS : il compare le
     * maximum observé au champ courant. Comme la comparaison est
     * `if (v > max) max = v`, remettre à zéro suffit — le service repart
     * naturellement de la nouvelle valeur au callback suivant, sans avoir
     * besoin d'être notifié.
     */
    public void resetPeaks() {
        maxSatellitesSeen = 0;
        maxCn0Seen = 0f;
    }

    public int getMaxSatellitesSeen() { return maxSatellitesSeen; }
    public void setMaxSatellitesSeen(int v) { this.maxSatellitesSeen = v; }
    public float getMaxCn0Seen() { return maxCn0Seen; }
    public void setMaxCn0Seen(float v) { this.maxCn0Seen = v; }

    public boolean isSignalOk() { return signalOk; }
    public void setSignalOk(boolean v) { this.signalOk = v; }
    public long getFixAgeMs() { return fixAgeMs; }
    public void setFixAgeMs(long v) { this.fixAgeMs = v; }
    public long getLostForMs() { return lostForMs; }
    public void setLostForMs(long v) { this.lostForMs = v; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String v) { this.rejectReason = v; }

    public boolean isRecording() { return recording; }
    public void setRecording(boolean v) { this.recording = v; }
    public int getTrackPoints() { return trackPoints; }
    public void setTrackPoints(int v) { this.trackPoints = v; }
    public String getLastGpxFile() { return lastGpxFile; }
    public void setLastGpxFile(String v) { this.lastGpxFile = v; }
    public String getGpxError() { return gpxError; }
    public void setGpxError(String v) { this.gpxError = v; }
    public String getTrackingNotice() { return trackingNotice; }
    public void setTrackingNotice(String v) { this.trackingNotice = v; }
    public String getGpxDurabilityWarning() { return gpxDurabilityWarning; }
    public void setGpxDurabilityWarning(String v) { this.gpxDurabilityWarning = v; }
    public String getSessionNotice() { return sessionNotice; }
    public void setSessionNotice(String v) { this.sessionNotice = v; }

    public String getDestLabel() { return destLabel; }
    public void setDestLabel(String v) { this.destLabel = v; }
    public String getPublishStatus() { return publishStatus; }
    public void setPublishStatus(String v) { this.publishStatus = v; }
    public String getPublishedName() { return publishedName; }
    public void setPublishedName(String v) { this.publishedName = v; }

    // --- Politique d'arrière-plan / écran (v5) ---

    public BackgroundPolicy.Policy getPolicy() { return policy; }
    public void setPolicy(BackgroundPolicy.Policy p) {
        this.policy = p != null ? p : BackgroundPolicy.Policy.CONTINUE;
    }

    public boolean isScreenOn() { return screenOn; }
    public void setScreenOn(boolean v) { this.screenOn = v; }
    public boolean isScreenStateKnown() { return screenStateKnown; }
    public void setScreenStateKnown(boolean v) { this.screenStateKnown = v; }
    public boolean isSuspended() { return suspended; }
    public void setSuspended(boolean v) { this.suspended = v; }
    public int getGpxSegment() { return gpxSegment; }
    public void setGpxSegment(int v) { this.gpxSegment = v; }
}
