# Lot 7B — Premier moteur de rendu Mapsforge

Date de travail : 23 novembre 2026  
Base : `GPS-Test-lot-7A.zip`  
Périmètre : intégration du moteur vectoriel local et abstraction du rendu cartographique. Le catalogue et le téléchargement des régions restent dans le lot 7C.

## 1. Décision de dépendance

Dépendances ajoutées dans `app/build.gradle` :

```gradle
implementation 'org.mapsforge:mapsforge-map-android:0.25.0'
implementation 'org.mapsforge:mapsforge-themes:0.25.0'
```

Au 23/11/2026, la version amont Mapsforge consultée est 0.31.0, mais la distribution publiée sur Maven Central consultée pour `mapsforge-map-android` reste 0.25.0. Les publications Maven ont été interrompues après les changements de l'infrastructure OSSRH. Le projet épingle donc 0.25.0, version disponible sur le dépôt configuré, au lieu de référencer une version amont qui ne serait pas résolue par Maven Central.

Cette épingle est volontaire et doit être revue lorsque les artefacts amont auront un canal de publication public et reproductible. Les bibliothèques Mapsforge portent une licence LGPL ; les obligations de redistribution et de fourniture des notices doivent être revues avant toute distribution publique de l'APK.

## 2. Architecture ajoutée

### `MapPoint`

Petite valeur géographique sans dépendance à osmdroid ou Mapsforge. Elle vérifie latitude et longitude, mais accepte le couple `(0,0)` comme coordonnée géographique. Le pipeline GNSS de l'application conserve sa propre politique de rejet des fixes live nuls ; cette classe représente une coordonnée cartographique et ne réinterprète pas les données GNSS.

### `MapRenderer`

Contrat commun à la carte vectorielle hors ligne et au moteur raster de repli. `MapActivity` n'a plus à créer directement les marqueurs, polylignes ou mouvements caméra spécifiques à un moteur.

### `OsmdroidMapRenderer`

Adaptateur de l'implémentation osmdroid existante. Il conserve Mapnik comme mode réseau/cache de repli. Aucun téléchargement massif ni préchargement de tuiles n'est ajouté. Son `destroy()` détache la MapView afin de libérer le provider et ses ressources.

### `MapsforgeMapRenderer`

Moteur vectoriel qui ouvre uniquement les fichiers `.map` déjà installés dans le dépôt privé de l'application. Il utilise :

- `AndroidGraphicFactory` pour le rendu Android ;
- `MapFile` pour ouvrir chaque fichier régional ;
- `MultiMapDataStore` avec `DataPolicy.DEDUPLICATE` pour combiner les régions et éviter de dessiner deux fois les objets présents aux frontières ;
- `TileRendererLayer` et `MapsforgeThemes.DEFAULT` pour le rendu ;
- `FixedPixelCircle` pour la position GNSS et l'extrémité de la trace ;
- `Polyline` pour dessiner chaque segment GPX séparément.

Les régions absentes, illisibles ou incompatibles sont ignorées individuellement. Si aucune carte exploitable ne reste, l'initialisation échoue proprement et `MapActivity` conserve osmdroid. Si des cartes ont des tailles de tuile différentes, celles qui ne correspondent pas à la première carte lisible sont écartées : la fusion hétérogène n'est pas tentée.

## 3. Sélection du moteur

Au démarrage de `MapActivity` :

1. Le dépôt `OfflineMapRepository` énumère les régions installées dans `getExternalFilesDir("offline-maps")`, avec repli dans le stockage privé interne.
2. Si au moins une région installée est lisible par Mapsforge, une `MapsforgeMapRenderer` est créée.
3. Sa vue est placée dans `mapContainer`.
4. La `MapView` osmdroid présente dans le layout XML est ensuite détachée et retirée pour éviter de conserver deux moteurs/providers actifs.
5. Si l'initialisation vectorielle échoue, la vue osmdroid reste disponible comme solution de repli.

La sélection actuelle est automatique : présence d'au moins une carte vectorielle exploitable = Mapsforge ; sinon = osmdroid.

## 4. Position, traces et caméra

- La position vient toujours de `TrackingState` ; aucun deuxième abonnement GNSS n'est créé dans la carte.
- Un cercle de position n'est créé qu'après réception d'une coordonnée valide ; aucune fausse position au centre de la région n'est affichée.
- La couleur du marqueur distingue une position fraîche de la dernière position connue.
- Les segments GPX sont rendus comme des polylignes distinctes, sans trait artificiel à travers les interruptions.
- Le remplacement des overlays est transactionnel : les nouveaux objets sont préparés avant de retirer l'ancienne trace.
- `MapZoomCalculator` calcule un zoom d'emprise en projection Mercator et prend en compte la discontinuité de longitude à l'antiméridien.
- Le premier cadrage attend les dimensions réelles de la vue (`OnLayoutChangeListener`) au lieu d'utiliser un `post()` qui pourrait s'exécuter avant le premier layout.

## 5. Cache et stockage

Les fichiers cartographiques régionaux sont stockés par `OfflineMapRepository` dans un emplacement app-specific persistant. Ils ne sont pas placés dans `getCacheDir()`.

Mapsforge utilise un cache de rendu local créé par `AndroidUtil.createTileCache(..., persistent = false)`. Ce cache ne contient que des résultats de rendu temporaires ; ce n'est ni un fournisseur de cartes en ligne, ni un jeu de tuiles OSM téléchargées. Le fond cartographique hors ligne est issu des fichiers vectoriels `.map` locaux.

Aucun fichier `.map` réel n'est inclus dans l'archive du projet. Le lot 7B ne fournit pas encore de catalogue régional ni d'interface de téléchargement ; ces éléments sont le lot 7C.

## 6. Ressources et cycle de vie

- La `MapView` Mapsforge est détruite avec `destroyAll()`.
- Le cache de rendu est géré par le `TileRendererLayer` / `MapView` quand la couche a été attachée ; les ressources sont explicitement détruites si la construction échoue avant attachement.
- `AndroidGraphicFactory` étant global au processus, son cache mémoire n'est vidé qu'au départ du dernier renderer Mapsforge actif. Un renderer ne doit pas libérer les ressources partagées alors qu'une autre carte est encore ouverte.
- Osmdroid est détaché via `onDetach()` lorsqu'il n'est pas choisi et lors de la destruction de son renderer.

## 7. Attribution et politique de données

Le texte d'attribution OSM reste visible dans l'interface et ouvre la page de copyright OSM. Il indique aussi le moteur vectoriel en mode Mapsforge.

Le serveur public `tile.openstreetmap.org` n'est pas utilisé pour précharger ou télécharger des régions. Le lot 7B ne déclenche aucun téléchargement réseau de cartes vectorielles. Les conditions et la licence de la source des fichiers `.map` devront être contrôlées individuellement dans le lot 7C avant d'afficher un bouton de téléchargement.

## 8. Tests réalisés

- 23 suites JVM ; 1 032 assertions réussies ; 0 échec.
- Les tests `MapPointTest` couvrent coordonnées valides, extrêmes, NaN et infinis.
- Les tests `MapZoomCalculatorTest` couvrent cadrage d'une région/pays, antiméridien, petite emprise et entrées invalides.
- `OfflineMapRepositoryTest` couvre l'installation du flux, SHA-256, validation de l'en-tête Mapsforge v5, métadonnées, annulation, traversée de chemin, remplacement et suppression.
- Les 15 ressources XML Android ont été analysées avec succès.
- L'archive ZIP a été vérifiée après création.

## 9. Limites de validation — importantes

L'environnement de travail ne contient ni Gradle, ni `gradlew`, ni Android SDK. La compilation Android de `MapsforgeMapRenderer`, la résolution des dépendances Maven et la génération d'un APK n'ont donc pas été réalisées ici. Les tests JVM couvrent les classes pures et le dépôt de stockage, pas le code Android Mapsforge.

Aucun fichier `.map` régional complet n'était fourni dans les éléments de travail. Les tests de dépôt s'appuient sur un fichier synthétique portant un en-tête Mapsforge v5 ; ce test ne constitue pas une validation du lecteur/rendu d'un véritable jeu de données. Les signatures API de Mapsforge 0.25.0 ont été vérifiées contre la documentation Javadoc et l'exemple Android officiel, mais l'ouverture et le dessin d'une carte réelle doivent encore être validés sur un poste avec SDK et sur appareil.

Avant de déclarer le moteur opérationnel, il faudra donc :

1. synchroniser Gradle avec Maven Central ;
2. compiler et produire un APK ;
3. installer au moins une vraie carte `.map` (petite région de test) ;
4. vérifier rendu, zoom, déplacement, position GNSS, GPX multi-segments et destruction/recréation de l'Activity ;
5. tester deux régions adjacentes et une carte endommagée ;
6. tester un appareil avec peu de mémoire.

## 10. Suite proposée — Lot 7C

Construire l'écran catalogue et gestion des régions : catalogue signé/versionné, source cartographique autorisée, tailles et emprises, téléchargement en flux, progression, annulation, SHA-256, installation transactionnelle, mises à jour et suppression. Le moteur de rendu devra rester indépendant de la source de téléchargement.
