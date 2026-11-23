# Lot 3 — Mode Expert, mesures GNSS brutes et CSV

## Objectif du lot

Centraliser dans `TrackingService` la gestion des abonnements aux mesures GNSS brutes, conserver un aperçu vivant dans l'écran Expert sans imposer l'enregistrement, et remplacer l'ancien tampon CSV en mémoire par un enregistreur progressif, borné et indépendant du cycle de vie de l'Activity.

Ce lot ne modifie pas le format GPX, le parseur GPX, les calculs DOP, les filtres de qualité de position ni le moteur cartographique.

## 1. Propriété et cycle de vie des abonnements

- `TrackingService` possède le callback `GnssMeasurementsEvent.Callback` et l'enregistre avec `LocationManagerCompat.registerGnssMeasurementsCallback()` sur Android 7 à 11. Cette voie de compatibilité est importante pour les appareils Android 11 antérieurs à QPR1.
- À partir d'Android 12 (API 31), le service utilise la surcharge native avec `GnssMeasurementRequest`, afin de pouvoir demander explicitement le mode `full tracking`.
- L'aperçu est demandé lorsque la page Expert est visible. Il ne dépend pas du démarrage du CSV.
- L'enregistrement CSV est demandé explicitement par le bouton Démarrer/Arrêter de la page Expert. Quitter l'Activity ne ferme pas un CSV déjà démarré.
- Si ni l'aperçu ni le CSV ne sont demandés, le callback brut est désabonné. Une reprise de la page Expert peut le réinscrire.
- Les callbacks tardifs sont filtrés après arrêt, suspension ou destruction du service. Les callbacks d'ouverture, de mise à jour et de fermeture du CSV sont également protégés par une génération globale au processus : une ancienne opération asynchrone ne peut pas écraser l'état du nouveau fichier.
- Les tentatives d'abonnement temporaires sont espacées de cinq secondes. Une absence de mesure pendant dix secondes est signalée mais ne déclenche pas une boucle de réabonnement : un silence peut être causé par le chipset, les conditions de réception ou les restrictions Android.

### Limite de la plateforme — très importante

Android documente que les callbacks de mesures GNSS brutes ne sont délivrés que lorsque `GPS_PROVIDER` est activé et que l'application cliente est au premier plan. La documentation impose la même condition aux callbacks NMEA (`addNmeaListener(Executor, ...)`). Un foreground service de localisation ne supprime pas cette restriction.

En conséquence, lorsque l'application passe en arrière-plan :

- le CSV reste ouvert ;
- le worker disque continue les flush périodiques et les synchronisations ;
- les nouvelles mesures brutes et les nouvelles trames NMEA peuvent ne plus être livrées ;
- l'interface ne présente pas le flux comme continu et l'absence d'événements reste visible ;
- le suivi de position Android et l'enregistrement GPX du foreground service restent indépendants.

Le fichier peut donc contenir des intervalles sans mesures brutes. Il ne faut pas interpréter une période vide comme une absence de satellites, ni l'utiliser comme preuve d'une acquisition brute continue écran éteint.

## 2. Mode GNSS complet (full tracking)

Une option indépendante est disponible dans Expert : **Full tracking (Android 12+, batterie ↑, impact global)**.

- Désactivée par défaut pour préserver la batterie.
- Persistée dans les préférences.
- Modifiable uniquement quand aucun CSV n'est en cours, afin de ne pas changer silencieusement le régime d'acquisition au milieu d'un fichier.
- Sur Android 12+ elle est transmise via `GnssMeasurementRequest.Builder.setFullTracking(...)`.
- Sur Android 7 à 11 elle reste sans effet, car l'API de requête n'existe pas.

Lorsque cette option est active, le chipset peut désactiver son duty-cycling, suivre davantage de constellations/satellites/bandes et offrir une meilleure continuité de phase lorsque le matériel le permet. Elle augmente la consommation. La documentation Android indique également qu'une demande full tracking peut s'appliquer aux autres écouteurs GNSS présents sur l'appareil, et affecter les fonctions de localisation. Ce n'est donc pas un réglage purement local au CSV : l'application le présente comme un choix explicite et réversible.

À partir d'Android 14 (API 34), lorsque le fournisseur expose le champ d'état, le CSV peut enregistrer `isFullTracking`. Cela indique l'état rapporté par le fournisseur, et non une garantie universelle de comportement matériel.

## 3. Données CSV

Le schéma est stable et comprend **47 colonnes**. Les options décochées laissent leurs champs vides ; elles ne changent pas l'ordre ni le nombre de colonnes.

| Index (base 0) | Colonnes | Contenu |
|---:|---|---|
| 0–1 | `callbackUtcMillis`, `callbackElapsedRealtimeNanos` | Horodatage de réception UTC (ms Unix) et monotone (ns depuis le démarrage). Toujours remplis, même si l'horloge détaillée est décochée. |
| 2–5 | `constellation`, `svid`, `codeType`, `measurementState` | Identité du système/satellite, type de code si disponible et état brut de la mesure. |
| 6–11 | `carrierHz`, `band`, `cn0DbHz`, `basebandCn0DbHz`, `automaticGainControlDb`, `snrDb` | Porteuse, bande indicative, niveau de signal et indicateurs RF disponibles. |
| 12 | `pseudorangeM` | Volontairement vide : aucune pseudodistance absolue n'est inventée. |
| 13–17 | `pseudorangeRateMps`, `pseudorangeRateUncertaintyMps`, `receivedSvTimeNanos`, `receivedSvTimeUncertaintyNanos`, `timeOffsetNanos` | Taux de pseudodistance/Doppler, incertitudes, temps satellite reçu et offset de mesure. |
| 18–24 | `adrMeters`, `adrUncertaintyMeters`, `adrState`, `legacyCarrierCycles`, `legacyCarrierPhase`, `legacyCarrierPhaseUncertainty`, `multipath` | ADR, incertitude, état et anciens champs de phase/cycles lorsqu'ils sont exposés par l'appareil. |
| 25–34 | `clockTimeNanos` à `clockDriftUncertaintyNanosPerSecond` | Horloge GNSS : temps, incertitudes, temps monotone associé, leap second, full bias, bias et dérive. |
| 35–38 | `fullInterSignalBiasNanos`, `fullInterSignalBiasUncertaintyNanos`, `satelliteInterSignalBiasNanos`, `satelliteInterSignalBiasUncertaintyNanos` | Biais inter-signaux au niveau horloge et satellite lorsqu'ils sont exposés (API 30+). |
| 39–41 | `hardwareClockDiscontinuityCount`, `eventAutomaticGainControlDb`, `isFullTracking` | Discontinuités d'horloge, AGC de l'événement (API 33+) et état full tracking (API 34+). |
| 42–43 | `recordType`, `rawNmeaSentence` | Type de ligne (`MEASUREMENT`, `AGC` ou `NMEA`) et trame NMEA brute lorsqu'elle est activée. |
| 44–46 | `clockIsbReferenceConstellationType`, `clockIsbReferenceCodeType`, `clockIsbReferenceCarrierFrequencyHz` | Signal de référence associé aux biais inter-signaux (API 30+). |

Les champs sont protégés par des contrôles de version et par `has...()` lorsque l'API l'exige. Un champ non supporté ou indisponible reste vide. Les unités sont explicites dans les noms (`Hz`, `Nanos`, `Meters`, `Mps`, `DbHz`).

Les lignes `AGC` sont séparées des lignes satellite : les informations AGC de l'événement décrivent une bande/constellation et ne doivent pas être dupliquées artificiellement sur chaque satellite. Les NMEA brutes sont dans des lignes `NMEA` séparées. Les champs texte sont échappés selon les règles CSV, et le payload NMEA est conservé tel que fourni par Android.

## 4. Préférences d'enregistrement

Cinq choix sont sauvegardés dans les préférences et figés lors de l'ouverture de chaque fichier :

1. Horloge détaillée.
2. Signaux (porteuse, bande, C/N0, AGC, SNR disponibles).
3. Doppler et temps satellite.
4. Phase / ADR.
5. Trames NMEA brutes.

Le réglage `full tracking` est séparé : il agit sur l'acquisition GNSS, pas sur la sélection de colonnes CSV.

## 5. Écriture, back-pressure et durabilité

`RawMeasurementsCsvLogger` possède un `HandlerThread` dédié. Aucune création de dossier, ouverture, écriture, flush, synchronisation ou fermeture du fichier CSV n'est réalisée sur le thread principal.

- Fichier dans le répertoire privé de fichiers externes de l'application (`Android/data/<package>/files/gnss`), avec repli vers le stockage interne privé si nécessaire.
- En-tête écrit et synchronisé lors de l'ouverture.
- File d'attente bornée à **4 096 lignes**. Les lignes excédentaires sont comptées comme perdues au lieu de provoquer une croissance mémoire non bornée.
- Flush Java toutes les **2 secondes**, y compris sans nouvel événement GNSS.
- `FileDescriptor.sync()` toutes les **30 secondes**, puis à la fermeture.
- À l'arrêt, les lignes déjà admises passent avant la tâche de finalisation ; le writer est vidé, synchronisé et fermé.
- En cas d'échec d'ouverture ou d'écriture, un statut/une erreur est renvoyé et les lignes non conservées sont comptées comme perdues.
- Les générations d'enregistrement différencient les fichiers successifs, y compris si un ancien callback I/O revient après la création d'un nouveau service/logger.

Un flush Java n'est pas une preuve de durabilité physique immédiate. La synchronisation périodique réduit la fenêtre de perte potentielle, au prix d'un petit coût I/O. Les compteurs distinguent : événements reçus, lignes confiées au worker, lignes en attente, lignes sérialisées dans le writer, lignes perdues. « Sérialisées » ne signifie pas que chaque ligne a déjà été synchronisée physiquement sur le support ; la synchronisation est périodique.

## 6. Aperçu Expert et partage

- L'aperçu GNSS brut s'abonne quand la page Expert est visible, même sans CSV.
- Un aperçu satellite indique constellation, SVID, bande, C/N0, Doppler, ADR et état de mesure lorsque les champs sont disponibles.
- Les lignes NMEA de la session sont conservées dans un tampon mémoire limité à 200 lignes pour l'affichage ; le CSV complet, lui, est écrit sur disque si l'option NMEA est cochée.
- Le bouton Arrêter lance la finalisation. Le bouton Partager n'est activé qu'après fermeture du fichier.
- Le CSV est partagé via `FileProvider`, sans ouvrir une permission générale de stockage.

## 7. Vérifications effectuées dans cet environnement

- `tools/run_jvm_tests.sh` : **16 suites, 833 assertions réussies, 0 échec**.
- Le test du logger couvre le flush sans événement suivant, la file 4 096 lignes, les pertes, la fermeture, l'échec d'ouverture et l'unicité des générations entre instances successives.
- Les tests de format couvrent l'en-tête à 47 colonnes, les types `AGC`/`NMEA`, les champs ISB et la conservation du payload NMEA y compris son terminateur.
- `tools/gen_r.py` : 137 IDs, 202 chaînes, 6 layouts, 15 couleurs, 8 styles, 3 drawables, 1 mipmap.
- Les 15 XML de ressources et le manifeste ont été analysés comme XML bien formés.

### Non vérifié ici

Cet environnement ne contient ni Gradle, ni `gradlew`, ni Android SDK. La compilation Android complète, la génération APK, lint Android et les essais sur téléphone n'ont donc pas été exécutés. Les tests JVM utilisent des stubs et ne valident pas le linkage Android des appels ajoutés ni le comportement des pilotes GNSS.

### Essais matériels à effectuer

1. Android 7–10 : compatibilité `LocationManagerCompat` et callback avec Handler NMEA.
2. Android 11 : appareil pré-QPR1 et appareil à jour, pour valider la voie compat GNSS brute et l'executor NMEA.
3. Android 12–13 : full tracking désactivé puis activé, observer consommation, fixes, nombre de mesures et continuité ADR.
4. Android 14–16 : contrôler les champs AGC événement, full tracking, ISB et compatibilité cible API 36.
5. Démarrer CSV, quitter Expert, verrouiller l'écran, revenir : confirmer l'éventuel trou de mesures et la continuité du flush; ne pas supposer que les données brutes continuent en arrière-plan.
6. Simuler stockage indisponible, espace faible, ouverture refusée et arrêt immédiat après démarrage.
7. Vérifier que les autres applications GNSS ne subissent pas d'effet inattendu du mode full tracking et que le mode est bien relâché quand le callback de notre application est désabonné.
