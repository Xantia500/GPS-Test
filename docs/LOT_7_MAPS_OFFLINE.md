# LOT 7A — Cartographie hors ligne : étude et fondations de stockage

Date de l'étude : 23 novembre 2026  
Base : archive `GPS-Test-lot-6.zip`  
Périmètre livré : architecture cartographique, validation d'en-tête Mapsforge et dépôt de stockage local.  
**Ce lot ne livre pas encore l'écran de téléchargement des régions ni le remplacement du moteur osmdroid.**

## 1. Diagnostic de la carte actuelle

L'écran `MapActivity` utilise toujours `osmdroid 6.1.20` avec `TileSourceFactory.MAPNIK`. Le cache est situé dans `getCacheDir()/osmdroid` : il s'agit d'un cache des tuiles déjà vues, pas d'une carte hors ligne garantie. Le cache peut être supprimé par Android. Il n'existe actuellement ni catalogue régional, ni téléchargement de carte, ni inventaire de régions installées.

L'import GPX, le suivi de position et les overlays existants sont conservés. Ils devront être raccordés au futur moteur vectoriel, sans que leur modèle métier dépende de Mapsforge.

Aucun téléchargement de tuiles n'a été ajouté. Le service standard `tile.openstreetmap.org` interdit le préchargement et les téléchargements de régions pour usage hors ligne ; il faut employer ses propres données, son propre serveur ou un fournisseur autorisant explicitement cette utilisation.

Référence : <https://operations.osmfoundation.org/policies/tiles/>.

## 2. Décision moteur : Mapsforge vectoriel

Mapsforge est retenu comme moteur cible : bibliothèque Java pour Android, format compact Mapsforge `.map`, rendu vectoriel local à partir des données OSM et thèmes XML. Les cartes ne sont donc pas des piles de tuiles raster téléchargées. Le moteur génère localement les images nécessaires à l'affichage à partir du fichier vectoriel.

Le modèle de rendu prévu est celui montré par l'exemple Android officiel : `MapView`, `MapFile`, `TileRendererLayer`, `TileCache` et thème XML. Pour plusieurs régions, `MultiMapDataStore` sera étudié afin d'assembler les cartes adjacentes et dédupliquer les entités de bordure.

Références :
- <https://github.com/mapsforge/mapsforge>
- <https://github.com/mapsforge/mapsforge/blob/master/mapsforge-samples-android/src/main/java/org/mapsforge/samples/android/GettingStarted.java>
- <https://github.com/mapsforge/mapsforge/blob/master/docs/Changelog.md>

### Version et dépendances : point de vigilance

Au 23 novembre 2026, la dernière version upstream consultée est Mapsforge **0.31.0**, publiée le 28 octobre 2026. La page Maven Central de `org.mapsforge:mapsforge-map-android` consultée référence encore **0.25.0**. Le projet n'ajoute donc pas encore de dépendance Mapsforge : choisir simplement `0.31.0` dans Gradle sans avoir confirmé sa disponibilité Maven et sa compatibilité serait risqué ; choisir `0.25.0` sans tester reviendrait à intégrer une version en retard sur upstream.

Décision pour l'étape suivante : faire un prototype de dépendances et de rendu isolé, synchroniser Gradle sur une machine avec SDK, vérifier les API de la version effectivement résolue et compiler avant de remplacer la carte actuelle. Ne pas introduire JitPack, un snapshot ou une dépendance Git arbitraire sans raison et sans build reproductible.

Références :
- Releases upstream : <https://github.com/mapsforge/mapsforge/releases>
- Maven Central : <https://central.sonatype.com/artifact/org.mapsforge/mapsforge-map-android/versions>

La bibliothèque Mapsforge est sous LGPL v3 avec une exception indiquée par le projet permettant de l'intégrer dans une application Android propriétaire sans devoir publier le code de l'application. Les obligations de fourniture des notices et du texte de licence restent à respecter.

## 3. Source des cartes et politique de téléchargement

### Source prioritaire à tester

Le serveur de téléchargement Mapsforge publie des cartes prédéfinies et renvoie vers un miroir universitaire rapide. Le serveur principal indique explicitement qu'il n'est pas adapté aux téléchargements massifs. Le miroir expose actuellement un index de fichiers `.map` organisé par régions.

Exemple constaté le 23 novembre 2026 dans l'index France :
- `ile-de-france.map` : environ 201 Mio, modifié le 20 octobre 2026 ;
- `bretagne.map` : environ 209 Mio ;
- `provence-alpes-cote-d-azur.map` : environ 234 Mio ;
- `france.map` (carte nationale) : environ 3,3 Gio.

Cela permet de télécharger uniquement les régions nécessaires. Le premier scénario d'essai proposé est l'Île-de-France, et non le fichier France complet.

Le catalogue consulté est un index HTTP de répertoire, pas un contrat d'API documenté. La prochaine étape devra donc isoler la découverte des régions derrière un `MapCatalogProvider`, gérer les changements de fichiers et vérifier la taille reçue côté HTTP. Le client ne doit pas lancer de téléchargement en masse, de rafale de requêtes ou de scan automatique de toutes les régions. Les téléchargements seront déclenchés explicitement par l'utilisateur et limités à un seul flux actif par défaut.

Références :
- <https://download.mapsforge.org/>
- <https://ftp-stud.hs-esslingen.de/pub/Mirrors/download.mapsforge.org/maps/v5/europe/france/>

### Sources non retenues comme fournisseur applicatif par défaut

- **OpenAndroMaps** : les conditions publiées autorisent la copie privée, mais interdisent les liens directs vers les cartes et leur regroupement dans une application sans autorisation écrite. Ne pas utiliser comme fournisseur de téléchargement intégré sans accord préalable.
- **BBBike** : propose des extraits OSM dont certains formats sont adaptés à Mapsforge ; le service d'extraits personnalisés repose sur un formulaire et un lien envoyé par courriel. À garder comme piste externe/manuelle, pas comme API de téléchargement intégrée sans vérifier ses conditions et son interface.
- **Geofabrik** : fournit surtout des extraits source OSM tels que `.osm.pbf`. Il faudrait construire et maintenir notre propre pipeline de compilation de cartes Mapsforge. C'est une option de long terme, pas un chantier de cette version 0.01.
- **Tuiles raster OSM standard** : exclues pour les téléchargements hors ligne conformément à leur politique d'utilisation.

Sources :
- <https://www.openandromaps.org/en/license>
- <https://download.bbbike.org/>
- <https://download.geofabrik.de/>
- <https://operations.osmfoundation.org/policies/tiles/>

### Attribution et licence des données

Les cartes Mapsforge prédéfinies consultées indiquent que les données cartographiques sont © OpenStreetMap contributors et sous ODbL. La future interface carte et la page « À propos / Licences » devront afficher clairement « © OpenStreetMap contributors » et proposer un accès à <https://www.openstreetmap.org/copyright>.

## 4. Architecture retenue

Le moteur cartographique sera derrière des interfaces ; les couches GNSS et GPX ne dépendront pas des classes Mapsforge.

```text
MapActivity
  ├── MapRenderer (interface future)
  │     └── MapsforgeMapRenderer
  ├── MapDataSource
  │     └── OfflineMapRepository
  ├── TrackingState (position GNSS)
  └── GpxParser / overlays de traces

MapManagerActivity (étape future)
  ├── MapCatalogProvider
  ├── MapDownloadWorker
  └── OfflineMapRepository
```

Principes :
1. Un fichier `.map` installé est un actif persistant ; jamais dans `getCacheDir()`.
2. Le cache Mapsforge ne contient que des tuiles **rendu localement** et peut être purgé/reconstruit.
3. Une région n'est publiée au catalogue installé qu'après validation et écriture complète.
4. Aucun remplacement d'une carte actuellement utilisée avant fermeture de son lecteur.
5. Le stockage et le téléchargement ne possèdent pas de dépendance Mapsforge ; seul le renderer en dépendra.
6. Les traces GPX et la position GNSS sont des overlays indépendants du fournisseur.

## 5. Fondations ajoutées dans ce lot

### `MapDataSource.java`

Interface de lecture de la bibliothèque locale. Elle expose les cartes installées et le fichier d'une région sans dépendre d'osmdroid, Mapsforge ou d'un service réseau.

### `MapsforgeMapHeader.java`

Validateur défensif de l'en-tête du format Mapsforge **v5** (format correspondant au chemin `/maps/v5` de la source examinée). Il vérifie notamment :
- la signature `mapsforge binary OSM` ;
- la taille d'en-tête et la taille totale déclarée ;
- la version de format prise en charge ;
- les bornes géographiques ;
- la taille des tuiles.

Ce contrôle n'analyse pas les sections de données du fichier et ne remplace pas le lecteur officiel `MapFile`. Le futur renderer devra ouvrir chaque vraie carte dans Mapsforge et traiter toute erreur de structure avant de la proposer à l'utilisateur.

### `OfflineMapRepository.java`

Dépôt de cartes vectorielles installées dans un dossier app-specific persistant, transmis à son constructeur. L'appelant doit lui passer un dossier app-specific tel que `getExternalFilesDir("offline-maps")` (avec repli vers `getFilesDir()` si ce stockage n'est pas disponible), jamais le cache temporaire.

Le dépôt assure :
- copie en flux avec tampon de 64 Kio ;
- progression et annulation ;
- plafond de fichier de 4 Gio ;
- marge libre minimale de 64 Mio, contrôlée au démarrage et pendant la copie ;
- contrôle de taille annoncée et SHA-256 si fourni ;
- validation de l'en-tête Mapsforge v5 avant publication ;
- nom de fichier fondé sur le SHA-256 complet (`regionId-<64 hex>.map`) ;
- synchronisation du fichier avant engagement des métadonnées ;
- métadonnées séparées en propriétés, avec stratégie de remplacement par fichier temporaire/backup ;
- exposition de l'emprise du fichier, de la date de création, de la version et de la source ;
- rejet des identifiants régionaux/path traversal ;
- tolérance aux fichiers de métadonnées individuels corrompus dans le catalogue.

Le flux fourni à `install()` est consommé et fermé par le dépôt, y compris lors d'une annulation ou d'une erreur.

Lors d'une mise à jour, l'ancien fichier versionné n'est pas supprimé automatiquement : il pourrait être encore ouvert par le renderer. Le futur gestionnaire devra fermer les lecteurs, puis nettoyer les anciennes versions et fichiers orphelins.

**Responsabilité de l'appelant :** créer une instance de dépôt partagée par l'application (ou garantir l'exclusion mutuelle entre instances) et ne supprimer une région qu'après fermeture du renderer qui l'utilise.

## 6. Ce qui n'a volontairement pas été changé

- `MapActivity` continue d'utiliser osmdroid et Mapnik ; elle reste donc une carte raster en ligne avec cache.
- Aucun bouton de téléchargement de région n'est présenté comme fonctionnel à ce stade.
- Aucune dépendance Mapsforge n'a été ajoutée à `app/build.gradle`.
- Aucun fichier cartographique `.map` réel de 201 Mio n'a été téléchargé ou incorporé à l'archive.
- Aucun changement n'a été fait au suivi GNSS, au GPX ou aux autres écrans.

Ces omissions sont volontaires : le renderer doit être validé séparément et la provenance/version Maven doit être reproductible avant de supprimer osmdroid.

## 7. Vérifications réalisées

Commande : `bash tools/run_jvm_tests.sh`

Résultat : **21 suites, 1 018 assertions réussies, 0 échec**.

La nouvelle suite `OfflineMapRepositoryTest` vérifie notamment :
- installation d'un flux de fixture Mapsforge v5 ;
- nom de fichier à empreinte complète ;
- catalogue et interface `MapDataSource` ;
- métadonnées d'emprise ;
- fermeture du flux fourni ;
- rejet d'un HTML, d'une version non supportée, d'une taille ou empreinte incorrecte ;
- rejet des identifiants path traversal ;
- annulation et nettoyage du `.part` ;
- mise à jour sans suppression prématurée de l'ancienne version ;
- métadonnées dangereuses ignorées ;
- suppression et répétition sûre d'une suppression.

**Important :** la fixture Mapsforge est synthétique et ne contient que l'en-tête minimal de test. Elle ne valide pas un vrai fichier `.map` complet. Le lecteur Mapsforge et le test d'une vraie carte régionale restent à faire.

La compilation Gradle Android et l'APK n'ont pas été réalisés : cet environnement ne possède pas de Gradle/Gradle Wrapper ni de SDK Android. Les tests ci-dessus sont des tests JVM ciblés.

## 8. Étape suivante proposée : Lot 7B

1. Faire un prototype d'intégration Mapsforge avec une version Maven réellement disponible.
2. Ajouter une première `MapsforgeMapRenderer` séparée, avec ouverture d'une carte locale réelle.
3. Faire afficher une région locale et conserver les contrôles de carte.
4. Rebrancher position GNSS, marqueur et overlays GPX.
5. Tester plusieurs fichiers adjacents avec `MultiMapDataStore` et la gestion des frontières.
6. Ne supprimer osmdroid/MAPNIK qu'après validation de la carte vectorielle et des overlays sur appareils.

Le gestionnaire de régions et le téléchargement explicite (Lot 7C) viendront après que le renderer local fonctionne.
