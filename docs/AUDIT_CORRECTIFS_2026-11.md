# GPS Test — correctifs et audit technique (novembre 2026)

## Portée de cette livraison

**État intermédiaire — développement par lots.** Le répertoire contient des modifications dans plusieurs modules, issues de la passe de travail interrompue. Elles ne doivent pas toutes être considérées comme définitivement validées. Le lot actuellement clôturé et revérifié porte sur le flux d'écriture GPX (horloges/unités, isolation des segments, publication différée, compteurs et gestion des erreurs). Voir `LOT_1_GPX.md`. Les chantiers GNSS/Expert, cycle de vie complet Android, import GPX professionnel, carte et Edge-to-Edge seront repris et validés séparément.

Cette passe de travail porte sur le projet Android Java/XML fourni. Elle contient des renforcements de l'enregistrement GPX, du suivi GNSS, des mesures expertes, des filtres satellites, de l'import et du nettoyage GPX, de la carte actuelle et du passage à Edge-to-Edge / Android API 36. **La présence d'un changement dans ces zones ne signifie pas qu'il a déjà passé la validation complète Android ou appareil.**

**Aucune migration cartographique vers Mapsforge n'a été faite.** Le moteur osmdroid est conservé pour cette étape. Aucun téléchargement de zone, préchargement massif ou téléchargement hors écran de tuiles depuis `tile.openstreetmap.org` n'a été ajouté.

**Le parseur GPX n'a pas été remplacé par une bibliothèque tierce.** C'est volontaire : le remplacement par JPX ou une autre bibliothèque n'a pas été fait sans accord préalable. Les limites restantes du parseur maison sont exposées plus bas.

---

## 1. Enregistrement GPX et temps

### Horloges et unités

Le code distingue désormais explicitement trois notions :

- `Location.getElapsedRealtimeNanos()` : nanosecondes (`ns`) associées au fix ; converties une seule fois en millisecondes (`ms`) par division par `1_000_000`.
- `SystemClock.elapsedRealtime()` : millisecondes (`ms`) monotones, utilisées pour les durées, les délais, la fraîcheur des callbacks, les flush et les synchronisations.
- `System.currentTimeMillis()` / `Location.getTime()` : millisecondes depuis l'époque Unix, utilisées pour l'heure UTC du GPX et les noms de fichiers, jamais pour calculer une durée.

Le timestamp GPX est une heure civile UTC calculée à partir d'une ancre UTC et de l'écart monotone du fix. On ne met jamais `elapsedRealtime()` ou `elapsedRealtimeNanos()` directement dans `<time>` : ces horloges sont relatives au démarrage de l'appareil et ne sont pas des dates Unix.

Cela évite aussi qu'un changement manuel de l'heure ou une correction NTP fasse reculer la logique temporelle d'acquisition.

### Flush et durabilité

- `BufferedWriter.flush()` est demandé toutes les ~2 secondes quand des points arrivent.
- Un flush de garde est déclenché périodiquement même si le flux devient inactif.
- `FileDescriptor.sync()` est tenté environ toutes les 30 secondes, après vidage du buffer Java, et à la fermeture.
- Les opérations disque sont exécutées sur le worker I/O, jamais sur le thread principal.

Ce compromis évite un `fsync` à chaque fix (latence, coût énergétique et usure inutile) tout en bornant la quantité de données qui pourrait rester dans les couches de cache. Même `sync()` ne peut pas offrir une garantie absolue contre toutes les pannes matérielles / contrôleurs de stockage.

### Segments isolés

Chaque segment possède désormais un objet `RecordingSegment` qui contient son identifiant, son fichier, son writer, sa version GPX, sa destination SAF, son document de destination, ses compteurs et son état d'écriture. Les tâches asynchrones capturent cet objet précis ; elles ne relisent plus un `segmentId`, un `gpxFile` ou un `publishDoc` global qui aurait pu être remplacé par le segment suivant.

La fermeture est placée dans la même FIFO que les écritures de points. La publication SAF est lancée après fermeture et prend un instantané de longueur figée. Le fichier local append-only reste la source de vérité ; une erreur SAF ne détruit pas la trace locale.

La publication utilise un instantané temporaire pour éviter de copier un fichier qui serait encore en cours d'écriture. Les erreurs de création/écriture de la copie externe sont signalées ; une copie incomplète est supprimée quand le fournisseur le permet.

### Compteurs

Trois compteurs principaux sont exposés :

1. **Acceptés** : points qui passent le filtre de qualité/échantillonnage.
2. **Mis en file** : points dont le post sur le worker a réussi.
3. **Sérialisés** : points dont l'écriture du XML dans le `BufferedWriter` s'est achevée sans exception.

Les échecs avant mise en file et les échecs après mise en file sont séparés. La profondeur en attente suit l'invariant : `mis en file - sérialisés - échecs après mise en file`.

« Sérialisé » ne signifie pas « physiquement durable sur le support » : la durabilité dépend du prochain flush/sync. Les générations de compteurs empêchent une tâche retardée d'une ancienne trace de modifier les compteurs de la nouvelle.

La file d'écriture est bornée. Si le stockage est durablement plus lent que l'acquisition, l'application signale les points qui n'ont pas pu être mis en file au lieu de faire croître indéfiniment la mémoire.

---

## 2. Cadence GNSS et service de premier plan

### Acquisition

La cadence demandée est centralisée dans `GnssAcquisitionConfig`, et persistée via `Prefs`. Elle vaut 1 000 ms par défaut. Les valeurs futures prévues (2, 5 et 10 secondes) sont normalisées au même endroit, afin qu'une préférence invalide ou ancienne ne casse pas l'abonnement.

À partir d'Android 12, la requête `LocationRequest.Builder` exprime explicitement une haute précision, un intervalle cible, un intervalle minimal égal, aucune distance minimale et aucun âge maximal pour la position initiale. Sur les versions antérieures, l'API `requestLocationUpdates` avec `GPS_PROVIDER` reste utilisée.

**La fréquence reste une demande, pas une garantie.** Le fournisseur, le chipset, l'environnement radio, l'état GNSS, les restrictions énergétiques et le matériel décident de la cadence réellement disponible. Il n'existe pas de commande Android permettant d'imposer un fix physique exactement chaque seconde. `sendExtraCommand()` ne constitue pas une solution de cadence.

Le mode d'acquisition (1 Hz) est distinct du filtre d'échantillonnage GPX : recevoir des positions chaque seconde ne signifie pas forcément écrire un point chaque seconde.

### Foreground service

Le service est déclaré `foregroundServiceType="location"`, déclare `FOREGROUND_SERVICE_LOCATION` et commence sa promotion au premier plan avant de démarrer les opérations longues. Le démarrage reste initié depuis une activité visible après autorisation de localisation. `ACCESS_BACKGROUND_LOCATION` n'est pas ajouté par défaut : ce flux de démarrage ne justifie pas d'élargir inutilement la permission.

La localisation, le statut GNSS et NMEA sont suivis indépendamment. Le watchdog ne réinitialise pas un abonnement position simplement parce qu'aucun fix n'arrive : cela éviterait de casser l'acquisition dans un tunnel, en intérieur ou sous masque radio. Il retente uniquement les abonnements qui ont échoué / ne sont pas enregistrés et signale une absence prolongée de callbacks.

Le service reste néanmoins soumis aux décisions d'Android, du fabricant, à l'arrêt forcé par l'utilisateur, aux révocations de permissions et à la terminaison du processus. Aucun service Android ne peut être garanti « toujours vivant » dans toutes les circonstances.

### Politique de redémarrage

`START_NOT_STICKY` est conservé volontairement : une session interrompue ne redémarre pas silencieusement sans intervention de l'utilisateur. Le journal de session est laissé marqué comme interrompu pour qu'une ouverture ultérieure puisse le signaler. Cela privilégie la transparence et évite de reprendre une trace après un arrêt forcé ou un état de permissions devenu invalide.

Les terminaisons normales ne garantissent pas l'appel de `onDestroy()`. Le journal de session est donc la source de détection d'interruption, et non un callback supposé toujours arriver.

### Notifications

La demande `POST_NOTIFICATIONS` est contextualisée. Un refus ne bloque pas le suivi de localisation ; l'application avertit que la notification/commandes sont moins accessibles. Cela correspond au comportement Android 13+ : le foreground service peut fonctionner, mais la notification peut ne pas apparaître dans le tiroir habituel.

La visibilité de notification est privée afin de ne pas exposer le détail du suivi sur l'écran verrouillé.

### Wake lock

Aucun `WAKE_LOCK` ni `PARTIAL_WAKE_LOCK` n'est ajouté. Un partial wake lock empêche le CPU de s'endormir même écran éteint ; il peut augmenter sensiblement la consommation et doit impérativement être libéré. Il n'oblige pas le chipset GNSS à produire un fix exactement toutes les secondes et ne remplace pas le foreground service de type `location`.

Pour le besoin actuel, il est préférable de tester le suivi réel sur plusieurs appareils avant d'ajouter une contrainte CPU permanente. Si un fabricant précis présente des pertes documentées liées à la suspension CPU, on pourra mesurer puis évaluer un verrou borné et explicitement libéré, plutôt qu'un verrou permanent.

### Nettoyage et destruction du service

L'attente bloquante de plusieurs secondes dans `onDestroy()` a été supprimée. Le nettoyage GPX et le post-traitement sont soumis à des workers ; un compteur de tâches de nettoyage en vol évite de fermer prématurément les workers lorsqu'il reste plusieurs traces à traiter.

Le Douglas–Peucker est itératif et possède un budget de calcul : une trace pathologique est conservée plutôt que de provoquer une récursion profonde, un `StackOverflowError` ou une simplification partielle imprévisible.

---

## 3. Mode Expert — mesures GNSS et CSV

L'aperçu des mesures brutes est piloté par la visibilité de la page Expert et ne requiert pas d'enregistrement. Le CSV est, lui, piloté explicitement par l'utilisateur depuis cette page : démarrer / arrêter. Les options d'enregistrement sont persistées dans les préférences.

Le CSV comprend notamment : date UTC du callback, horodatage monotone, constellation, SVID, type de code, état de mesure, porteuse/fréquence/bande, C/N0, baseband C/N0, AGC et SNR quand disponibles, Doppler et incertitude, temps satellite et incertitude, offset, ambiguïté de phase accumulée et état, multipath, ainsi que les champs complets disponibles de l'horloge GNSS, les références ISB (constellation/code/fréquence) et les indicateurs de tracking.

Les champs non fournis par Android restent vides. Aucune pseudodistance n'est inventée : `GnssMeasurement` n'expose pas à lui seul une pseudorange absolue fiable et prête à l'emploi sans reconstruction/calibrage supplémentaire.

Le schéma CSV stable contient 47 colonnes et des lignes typées `MEASUREMENT`, `AGC` et `NMEA`. Les trames NMEA brutes sont optionnelles et conservées entières dans `rawNmeaSentence`. Les options Horloge détaillée, Signaux, Doppler/temps SV, Phase/ADR et NMEA sont enregistrées dans les préférences et figées pour chaque nouveau fichier. Les horodatages de réception UTC et monotone restent présents même si l'option Horloge détaillée est désactivée. Une option d'acquisition séparée « full tracking » est disponible à partir d'Android 12 (API 31), désactivée par défaut et non modifiable pendant un CSV : elle réduit le duty-cycling, augmente la consommation et peut influer sur les autres clients GNSS de l'appareil.

Le CSV est écrit en flux sur un worker dédié. L'écran ne conserve qu'un aperçu limité ; les compteurs événements reçus, lignes mises en file, lignes en attente, lignes sérialisées et lignes perdues sont séparés. La file est bornée à 4 096 lignes en attente ; flush périodique toutes les 2 s et `FileDescriptor.sync()` périodique toutes les 30 s, ainsi qu'à la fermeture.

**Limite Android essentielle :** les callbacks `GnssMeasurementsEvent` et NMEA documentés par `LocationManager` ne sont reçus que lorsque le GPS est activé et que l'application cliente est au premier plan. Lorsque l'écran Expert n'est plus au premier plan, le CSV reste ouvert et son worker continue les flush/sync, mais les nouvelles mesures et trames peuvent ne plus être livrées. L'interface affiche cet état sans faire croire que le flux reste complet. Cela ne désactive pas le suivi de position/GPX assuré par le foreground service. Les deux capacités sont distinctes.

L'enregistrement CSV est un journal de mesures brutes exploitable pour diagnostic et analyse, mais ne constitue pas encore un récepteur GNSS logiciel ou un moteur RTK/PPP.

---

## 4. Satellites, DOP et NMEA

### Satellites et filtres

L'écran principal affiche les satellites sous forme de liste/barres C/N0 colorées, avec valeur numérique, constellation et état utilisé dans le fix. Le filtre initial est « utilisés dans le fix » et exige un C/N0 mesuré strictement positif. Les filtres par constellation montrent les signaux effectivement observés de cette constellation, utilisés ou non, avec C/N0 mesuré positif. Le mode « tous » reste disponible.

Les symboles de constellation sont affichés lorsque le code de constellation est reconnu. Les bandes déclarées par le chipset (API Android 34+) sont séparées des bandes observées en réception ; sur les API plus anciennes, seules les bandes observées sont rapportées. L'interface évite de présenter une observation ponctuelle comme une capacité officiellement supportée par le chipset.

### DOP

Les DOP rapportés par le récepteur (notamment NMEA GSA) sont conservés distinctement des DOP calculés. Le calcul géométrique utilise les satellites marqués `usedInFix`, avec C/N0 positif et élévation/azimut exploitables. Il introduit un biais d'horloge par constellation afin de ne pas imposer artificiellement une horloge unique à un mélange multi-GNSS.

Si la géométrie est insuffisante ou singulière, le résultat calculé est indisponible plutôt que fabriqué. Un DOP géométrique calculé n'est pas une mesure de qualité de signal ni une estimation complète de l'erreur de position.

### NMEA

Les parseurs GGA, GSA, RMC, GLL, VTG et ZDA vérifient la structure, les champs/unités et le checksum lorsque présent/attendu. RMC respecte le statut `A`/`V` et le mode invalide. Les coordonnées `0,0` sont rejetées comme sentinelle invalide dans ce produit, aussi bien pour les fixes live que les trames NMEA et les GPX importés, conformément à la politique demandée.

### Fix 2D

Un fix 2D avec latitude/longitude valides et précision exploitable est accepté comme position horizontale. Il reste visuellement distinct (orange) d'un fix 3D (vert) et aucune altitude n'est inventée dans le GPX lorsqu'elle n'est pas fournie par le fix courant.

Le seuil rigide de nombre minimal de satellites utilisé auparavant n'est plus un motif automatique de rejet. Le nombre de satellites utilisés reste une information de qualité affichée ; les heuristiques historiques sont conservées en commentaires pour référence. Le contrôle de qualité s'appuie davantage sur la validité des coordonnées, la précision, la fraîcheur et la cohérence temporelle.

---

## 5. GPX, import et nettoyage

### Versions GPX

La préférence de version GPX est ajoutée : GPX 1.1 par défaut, GPX 1.0 sélectionnable. La version est figée pour chaque nouveau segment au moment de son ouverture. Les structures XML diffèrent : vitesse/cap sont écrits selon le format sélectionné. Un défaut GPX 1.0 (balise fermante de point en double) a été corrigé.

### Parseur

Le parseur maison a été renforcé pour les namespaces/préfixes courants, attributs, segments, champs standards et entités XML usuelles, avec limites du nombre de points. Il reste toutefois un parseur spécialisé pour les formes courantes, et **pas un parseur XML/GPX professionnel complet** : les extensions arbitraires et toutes les constructions XML imbriquées ne peuvent pas être garanties.

Aucune bibliothèque n'a été ajoutée sans accord. Une piste à évaluer séparément est JPX 3.2.1, compatible avec une base Java 17 ; la branche JPX 4.x demande un runtime Java nettement plus récent et n'est donc pas un remplacement direct pour ce projet Java 17. Une autre possibilité est un parseur XML streaming Android (`XmlPullParser`) avec conversion contrôlée vers les modèles GPX. Avant migration, il faudra un corpus de traces venant de GPXSee, Garmin, Komoot, Strava, Organic Maps et d'autres générateurs, puis comparer aller/retour, extensions et précision.

### Import asynchrone

L'import lit et analyse la trace hors du thread UI. La préparation des points affichés et du cadrage est elle aussi effectuée sur un worker ; seule la création des overlays graphiques est faite sur l'UI thread. L'application signale la lecture/préparation, les erreurs, la taille excessive et les limites de points. Les limites de fichier et d'échantillons rendent le coût mémoire borné.

### Nettoyage

Le nettoyage conserve l'original et produit une copie dérivée. Les champs standards reconnus (vitesse, cap, HDOP, précision selon disponibilité) sont préservés. Les timestamps absents restent absents : aucune date artificielle n'est injectée. La version GPX configurée est utilisée.

Les extensions propriétaires et métadonnées arbitraires que le parseur n'a pas reconnues ne sont toujours pas garanties à l'aller-retour. C'est un argument supplémentaire pour une évaluation d'une bibliothèque GPX complète avant de promettre la compatibilité universelle.

---

## 6. Carte et politique OSM

La carte actuelle reste osmdroid/Mapnik. Les améliorations incluent l'attribution visible, un User-Agent identifié pour l'application, une indication de position éventuellement périmée, un recentrage moins intrusif, et un cadrage qui traite la discontinuité de longitude près de l'antiméridien.

Le cache des tuiles consultées n'est pas un véritable mode hors ligne : il est incidental et peut être purgé. Aucun outil de téléchargement régional n'est ajouté. Le client ne doit charger que les tuiles du viewport effectivement affiché, sans préchargement massif ni téléchargement d'une région depuis le serveur public `tile.openstreetmap.org`.

La politique publique OSM interdit les téléchargements de zones et le préchargement/bulk download depuis ce serveur ; elle exige l'attribution, un User-Agent identifiable et le respect des en-têtes/cache. Pour le projet « régions téléchargeables 100 % offline », la piste Mapsforge sera étudiée séparément avec un fournisseur/extrait et une méthode de génération compatibles avec la licence ODbL et les conditions de redistribution.

Le dépôt amont d'osmdroid a été archivé en 2024 ; c'est un risque de maintenance à traiter dans l'étude cartographique ultérieure, pas une raison de mélanger cette migration avec la correction des enregistrements.

---

## 7. Android 15/16, Edge-to-Edge et SDK

`compileSdk` et `targetSdk` sont passés à 36 ; `minSdk` reste 24. Une classe partagée `EdgeToEdge` configure la fenêtre avec AndroidX WindowCompat et applique des insets aux racines des écrans Main, Expert, Settings et Map, y compris les barres système, le display cutout et le clavier.

La prise en charge Edge-to-Edge est ainsi uniformisée au niveau du code pour les versions supportées, mais le résultat doit encore être validé sur appareils réels avec navigation par gestes et par boutons, découpes, rotation, clavier et thèmes clair/sombre.

Il n'est pas conseillé de télécharger dynamiquement « la toute dernière API » à chaque compilation. Les versions SDK doivent rester épinglées pour assurer des builds reproductibles. Le meilleur compromis est de mettre compile/target SDK dans un seul endroit versionné et de les relever explicitement après vérification de compatibilité, plutôt que d'utiliser une version dynamique qui pourrait changer sans modification du code.

---

## 8. Vérifications réalisées

### Tests JVM

Compilation indépendante des 15 suites de tests avec les stubs Android locaux, puis exécution de chaque suite :

| Suite | Assertions réussies |
|---|---:|
| AdvancedTest | 103 |
| BackgroundPolicyTest | 31 |
| CompassTest | 92 |
| DestinationTest | 19 |
| DopFilterTest | 54 |
| FixQualityTest | 170 |
| GnssAcquisitionConfigTest | 11 |
| GpxSegmentTest | 19 |
| GpxTextReaderTest | 7 |
| ModesAndPostProcessTest | 88 |
| SamplingAndParsingTest | 69 |
| SessionJournalTest | 58 |
| ThemeTest | 31 |
| TrackingCountersTest | 17 |
| UtilsTest | 26 |
| **Total** | **795** |

Résultat : **15 suites réussies, 795 assertions réussies, 0 échec**.

Pour reproduire cette vérification depuis la racine du projet, avec JDK et Python 3 installés : `tools/run_jvm_tests.sh`.

### XML

Les 15 ressources XML et le manifeste (16 documents XML au total) ont été contrôlés pour leur bonne formation.

### Ce qui n'a PAS été vérifié ici

L'environnement de travail ne contient ni Gradle installé, ni `gradlew`, ni Android SDK (`ANDROID_HOME` absent). Le build Android complet, la génération d'APK, lint Android et les tests instrumentation/émulateur n'ont donc pas pu être exécutés. Les tests JVM ne remplacent pas un build Gradle complet ni une validation sur matériel.

Il reste impératif de tester sur appareil : démarrage/arrêt FGS, écran éteint, économie d'énergie par constructeur, refus de notification, révocation localisation, gestionnaire « applications actives », SAF, déconnexion/révocation du dossier, longues traces, import de gros GPX, rotation/IME/insets, et callbacks GNSS par version Android.

---

## 9. Architecture recommandée pour la suite

L'amélioration architecturale la plus utile n'est pas de multiplier les services Android. Elle consiste à séparer l'acquisition et le traitement d'événements GNSS du stockage et de la présentation :

1. **`GnssSessionEngine`** : propriétaire unique de l'état logique de session, des abonnements, de la qualité des fixes, de la segmentation et de la cadence demandée. Il publie des snapshots immuables.
2. **`TrackRepository` / `GpxSegmentWriter`** : interface de stockage append-only, avec back-pressure, compteurs et cycle de vie de segment. L'implémentation locale et le backend SAF sont distincts.
3. **`RawMeasurementsCsvLogger`** : enregistreur Expert autonome, activé uniquement depuis l'écran Expert, avec file bornée, persistance progressive et conscience de la restriction foreground des mesures brutes Android.
4. **`MapDataSource`** : interface séparée du moteur de rendu, qui permettra de remplacer osmdroid et brancher Mapsforge sans réécrire la logique GPX/position.

Le prochain gain subtil serait de faire passer les événements de localisation, status et NMEA par un pipeline unique sérialisé (single-writer state machine), avec horodatages monotones conservés depuis l'événement d'origine et snapshots pour l'UI. Cela réduit les races entre `Location`, `GnssStatus`, NMEA, segment courant et compteurs. Cette extraction doit rester incrémentale, après validation appareil du correctif actuel.

---

## Sources techniques externes consultées

- Android — foreground services et restrictions de démarrage : https://developer.android.com/develop/background-work/services/foreground-services
- Android — permissions de localisation : https://developer.android.com/develop/sensors-and-location/location/permissions
- Android — notification runtime permission : https://developer.android.com/develop/ui/views/notifications/notification-permission
- Android — GnssMeasurementsEvent / LocationManager : https://developer.android.com/reference/android/location/LocationManager
- Android — Edge-to-Edge : https://developer.android.com/develop/ui/views/layout/edge-to-edge
- Android — exigences Google Play target API : https://support.google.com/googleplay/android-developer/answer/11926878
- OpenStreetMap — Tile Usage Policy : https://operations.osmfoundation.org/policies/tiles/
- JPX — projet GPX Java : https://github.com/ticofab/jpx
