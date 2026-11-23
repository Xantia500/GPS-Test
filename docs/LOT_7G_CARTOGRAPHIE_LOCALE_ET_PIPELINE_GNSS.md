# LOT 7G — Cartographie strictement locale et pipeline GNSS sérialisé

## Objectif

Corriger les deux écarts constatés après le lot 7F avant d'ouvrir le lot 8 :

1. le sélecteur régional ne doit plus utiliser de fond Mapnik/osmdroid ni de tuiles raster ;
2. la carte principale ne doit plus basculer silencieusement vers un moteur en ligne quand aucune région Mapsforge n'est disponible ;
3. introduire une sérialisation cohérente des événements GNSS modérés, sans refaire l'architecture de service de zéro.

## 1. Sélecteur de régions sans tuiles raster

`OfflineMapsActivity` ne crée plus de `MapView` osmdroid. Elle ajoute directement un `MapRegionSelectionView` dans son conteneur. Ce composant dessine une grille géographique et les polygones régionaux `.poly` déjà obtenus ; zoom, déplacement et sélection s'effectuent localement.

La légende précise désormais la différence entre fond local et données réseau :

- aucune tuile raster/Mapnik n'est chargée ;
- les contours `.poly` peuvent être récupérés lors de la première consultation d'une région, puis sont mis en cache localement ;
- le catalogue de téléchargement et le téléchargement d'un fichier régional `.map` nécessitent une connexion ;
- les régions déjà consultées et les données installées restent disponibles localement selon le contenu du cache.

La carte de sélection est volontairement schématique : les contours `.poly` ne constituent pas un fond topographique ou routable.

## 2. Suppression du repli réseau de la carte principale

La sélection du renderer dans `MapActivity.initializeMapRenderer()` est maintenant explicite :

- une ou plusieurs cartes régionales valides : `MapsforgeMapRenderer` ouvre les fichiers `.map` locaux ;
- aucun fichier valide, dépôt inaccessible ou erreur d'initialisation Mapsforge : `OfflinePlaceholderMapRenderer` est utilisé ;
- aucun chemin ne crée un renderer raster ou ne demande des tuiles réseau.

Le canevas de repli est une représentation géographique honnête, et non une carte de rues : grille de latitude/longitude, position courante et traces GPX. La bannière prévient qu'aucun fond cartographique vectoriel n'est installé. La position et les traces restent utilisables sans prétendre remplacer une région Mapsforge.

Le canevas conserve l'accélération matérielle par défaut. Son dessin des parallèles a été corrigé : la projection normalise les longitudes, donc deux longitudes séparées de 360 degrés donnaient le même x ; les parallèles sont maintenant tracés de bord à bord à partir de leur coordonnée verticale.

La dépendance Gradle osmdroid, la classe `OsmdroidMapRenderer` et le `MapView` raster de `activity_map.xml` ont été retirés. La permission Internet reste nécessaire au catalogue Mapsforge, aux contours `.poly` non encore mis en cache et aux téléchargements de cartes ; elle ne sert pas à charger un fond de tuiles.

Un contrôle statique `tools/verify_offline_map_architecture.py` interdit le retour accidentel d'imports osmdroid, `TileSourceFactory`, Mapnik ou `setUseDataConnection()` dans les sources actives.

## 3. Pipeline GNSS sérialisé

### File commune

La classe `GnssEventPipeline` possède un `HandlerThread` dédié et une file FIFO unique. Les callbacks de position, statut satellite et NMEA sont enregistrés sur cette file :

- API Location récentes : `Executor` qui poste sur le Handler du pipeline ;
- API Location historiques : `Looper` du même HandlerThread ;
- `GnssStatus` et NMEA : surcharges `Handler`, compatibles avec le `minSdk` du projet.

Les mises à jour GNSS modérées sont ainsi séquencées au lieu de s'exécuter en concurrence sur plusieurs threads de callbacks.

### Cohérence et cycle de vie

`gnssProcessingLock` protège les transitions partagées qui doivent rester cohérentes avec le watchdog et les commandes de session : fix, statut satellites, NMEA, ouverture/fermeture du segment et échantillonnage. Les callbacks font une double vérification de l'état actif avant de modifier le modèle, car un désabonnement peut survenir pendant qu'un callback attend le verrou.

`gnssSubscriptionGeneration` et `gnssCallbacksEnabled` neutralisent les callbacks tardifs lors d'une suspension, d'un arrêt ou d'une réinscription. Le watchdog reste sur le thread principal et indépendant des callbacks : il doit continuer à détecter le silence GNSS même lorsque la file d'événements ne reçoit plus rien.

Les publications de l'état vers les activités sont coalescées et postées sur le thread UI ; les vues ne sont jamais mises à jour depuis le HandlerThread GNSS.

### Mesures brutes séparées

`GnssMeasurementsEvent` reste sur son worker CSV dédié. Son volume et son traitement ne doivent pas retarder les fixes à cadence nominale. Le pipeline commun vise les callbacks de position, `GnssStatus` et NMEA, dont les données sont nécessaires au même instantané de qualité GNSS.

Les erreurs RuntimeException d'un callback sont comptabilisées et ne tuent pas la file ; les compteurs de soumissions, d'achèvements, de rejets et d'échecs sont exposés par le composant pour le diagnostic.

## 4. Tests ajoutés

- `GnssEventPipelineTest` : démarrage idempotent, FIFO, absence d'exécution concurrente, compteurs, arrêt, rejet après arrêt et survie à une RuntimeException d'un callback.
- `MapRegionProjectionTest` : emprise et aller-retour de projection de traces traversant ±180°, trace réduite à un point et trace vide.
- `tools/verify_offline_map_architecture.py` : absence de dépendance et de code actif de tuiles raster/osmdroid.

La campagne a été exécutée avec une compilation JVM commune puis chaque suite lancée dans un processus séparé : **37 suites, 1 279 assertions réussies, 0 échec**. `tools/verify_offline_map_architecture.py` a analysé les sources actives et `app/build.gradle` : aucun moteur raster/osmdroid. Les 17 fichiers XML du manifeste et des ressources ont été analysés : 0 erreur. `tools/gen_r.py` a régénéré `R.java` avec 162 IDs, 213 chaînes, 7 layouts, 15 couleurs, 8 styles, 3 drawables et 1 mipmap.

Le lanceur historique `tools/run_jvm_tests.sh` n'a pas terminé dans la fenêtre de six minutes de cette session ; les 37 suites ont donc été exécutées individuellement, toutes avec un code de sortie nul, ce qui permet d'identifier précisément les suites et d'éviter de masquer un éventuel blocage de l'agrégateur.

## 5. Limites et validations restantes

Le canevas de repli n'affiche pas de routes, bâtiments, relief ni noms de lieux : il ne dispose pas de données vectorielles régionales. Pour obtenir un fond géographique réel, l'utilisateur doit installer au moins une carte Mapsforge `.map` couvrant la zone. Le sélecteur demeure schématique.

Le catalogue et les contours `.poly` peuvent demander le réseau à leur première consultation ; supprimer les tuiles n'implique pas de rendre le catalogue complet et mondial embarqué. Les fichiers `.map` téléchargés restent la source du véritable fond local.

Les tests JVM ne compilent pas les Activities Android, `LocationManager` réel, le moteur Mapsforge ni les ressources Android comme le ferait Gradle. Aucun APK ni test physique n'est revendiqué dans cette passe. La compilation Android, les essais de rendu sur vraies régions et les essais GNSS sur appareil restent à mener au LOT 8.
