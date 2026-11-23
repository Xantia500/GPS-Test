# Lot 7D — Sélection géographique, états des régions et finalisation persistante

Date de réalisation : 23 novembre 2026  
Base : `GPS-Test-lot-7C.zip`

## 1. Périmètre livré

Ce lot complète le gestionnaire introduit en 7C. Il ajoute une sélection tactile des régions fondée sur les fichiers de frontières `.poly`, la recherche textuelle, les états graphiques des cartes, une gestion bornée du cache de frontières, la synchronisation de la carte principale avec les changements du dépôt, et la finalisation d'installation par WorkManager après le transfert Android `DownloadManager`.

Le téléchargement des gros fichiers `.map` reste assuré par `DownloadManager`. WorkManager ne remplace pas ce transport : il valide et installe le fichier après la fin du transfert.

## 2. Sélection des régions via `.poly`

- `MapsforgeBoundaryClient` ne contacte que l'hôte HTTPS autorisé du miroir Mapsforge.
- `MapsforgeBoundaryCatalogParser` analyse les index de frontières et refuse les chemins douteux.
- `MapsforgePolyParser` valide les anneaux, trous, coordonnées WGS84 et fins de fichier.
- `OfflineBoundaryRepository` met en cache les contours dans le stockage app-specific persistant.
- `MapRegionSelectionView` dessine les contours, prend en charge le pincement/zoom, le déplacement et le toucher pour ouvrir/télécharger la région.

### Bornes de ressources

| Limite | Valeur |
|---|---:|
| Taille d'un `.poly` | 512 Kio |
| Anneaux par `.poly` | 256 |
| Sommets par `.poly` | 100 000 |
| Polygones affichés simultanément | 160 |
| Sommets chargés cumulativement dans la vue | 180 000 |
| Octets de contours chargés cumulativement dans la vue | 8 Mio |
| Cache persistant global des `.poly` | 64 Mio |
| Sommets dessinés par anneau | environ 1 400 maximum |

Le dessin utilise un échantillonnage visuel des contours afin de limiter le coût du Canvas. Le test de sélection tactile continue d'utiliser la géométrie intégrale non échantillonnée.

Le cache `.poly` est élagué par ancienneté lorsque son budget global dépasse 64 Mio. Les polygones déjà chargés dans la vue restent utilisables en mémoire.

## 3. États installés / disponibles / mise à jour

La carte de sélection utilise trois états :

- **Bleu** : région disponible au téléchargement.
- **Vert** : région déjà installée.
- **Orange** : une mise à jour est détectée.

La comparaison de version est faite avec la date de modification du fichier `.map` dans son propre catalogue, et non avec celle du contour `.poly` : les dates des deux fichiers peuvent différer.

La recherche filtre la liste du dossier actuellement ouvert et les frontières déjà chargées pour ce dossier. La liste des cartes installées reste globale à l'application.

## 4. Finalisation persistante et synchronisation

### Flux

1. `DownloadManager` télécharge le `.map` vers le dossier staging app-specific.
2. `DownloadCompleteReceiver` reçoit `ACTION_DOWNLOAD_COMPLETE` et vérifie que l'identifiant appartient au téléchargement courant de l'application.
3. `OfflineMapInstallScheduler` marque atomiquement `KEY_INSTALLING` puis met en file un travail unique `OneTimeWorkRequest`.
4. `OfflineMapInstallWorker` relit l'entrée de `DownloadManager`, contrôle le chemin local, la taille, l'en-tête Mapsforge et le SHA-256, puis installe la carte dans `OfflineMapRepository`.
5. Le Worker publie le résultat et efface les clés de progression dans une transaction `SharedPreferences`.
6. En cas de réussite, il incrémente `KEY_MAPS_REVISION` et envoie un broadcast limité au package de l'application.

L'appel depuis l'Activity et celui du receiver peuvent se chevaucher : le nom de travail est unique par identifiant de téléchargement et la politique `KEEP` empêche les doublons.

Le Worker utilise un identifiant `installId` pour reconnaître le cas où le fichier et les métadonnées ont été validés, mais où le processus a été arrêté avant l'accusé de réception final. Cette reprise est idempotente.

### Synchronisation de `MapActivity`

`MapActivity` observe le broadcast interne lorsqu'elle est démarrée et relit également la révision dans `onResume()`. Une différence entraîne la recréation de la carte afin de fermer les `MapFile` précédents et d'ouvrir les nouvelles métadonnées.

La suppression d'une carte incrémente la même révision. Si le renderer utilise encore le fichier, le dépôt retire d'abord la métadonnée et conserve le fichier jusqu'à libération du lease. Le nettoyage différé retire ensuite les fichiers `.map` orphelins qui ne sont plus référencés et ne sont plus ouverts.

Le bouton Annuler est désactivé dès que le marqueur persistant d'installation est posé. L'utilisateur peut fermer l'écran : la finalisation continue via WorkManager.

## 5. Cache et nettoyage des cartes

- Le cache des frontières `.poly` est limité à 64 Mio.
- Les anciennes versions `.map` sont versionnées par SHA-256 complet.
- Le nettoyage du dépôt vérifie les métadonnées et les leases du renderer.
- Si une métadonnée est corrompue ou inconnue, le nettoyage de fichiers non référencés est suspendu par prudence.
- Une carte utilisée par le renderer n'est jamais supprimée en cours d'utilisation.

## 6. Dépendances et compatibilité

- `androidx.work:work-runtime:2.12.0` est déjà présent depuis le lot 7C.
- WorkManager 2.12.0 impose `minSdk 24`, cohérent avec le `minSdk 24` de l'application.
- Le projet cible `compileSdk 36` / `targetSdk 36`.

La finalisation par Worker effectue un hachage complet du fichier. La documentation Android fixe une durée maximale de dix minutes pour un Worker standard ; un fichier multi-gigaoctets sur stockage lent devra donc faire l'objet d'un essai réel. Si des temps d'installation dépassent cette borne, une variante long-running avec notification et service de premier plan devra être étudiée, en tenant compte des quotas Android 16. Le transfert HTTP lui-même n'est pas exécuté dans le Worker : il reste géré par `DownloadManager`.

## 7. Limites connues

- La racine du catalogue contient les dossiers des continents, pas une frontière `.poly` mondiale unique. À la racine, l'utilisateur choisit d'abord un continent dans la liste ; la carte tactile affiche ensuite les frontières des régions de ce niveau. Ce n'est pas encore une carte mondiale interactive complète de type Organic Maps.
- La vue de sélection est actuellement une carte de contours sur fond sombre, sans fond topographique. Le rendu de navigation hors ligne reste assuré par Mapsforge dans `MapActivity` quand un `.map` local couvre la zone.
- La recherche porte sur le dossier de catalogue courant, pas sur une exploration exhaustive de tout le serveur.
- L'index du catalogue n'est pas encore mis en cache hors ligne. Les cartes installées restent listées, mais la navigation de nouvelles régions exige une connexion ou une extension de cache d'index.
- Le miroir ne fournit pas de SHA-256 distant signé. Le SHA-256 calculé par l'application protège l'intégrité du fichier local après téléchargement, mais ne constitue pas une signature d'origine indépendante.
- Les tests de ce lot ne téléchargent pas une vraie région `.map` de plusieurs centaines de Mio.

## 8. Vérifications

- 31 suites JVM.
- 1 133 assertions réussies.
- 0 échec.
- 17 ressources XML Android analysées.
- Aucun identifiant dupliqué dans `activity_offline_maps.xml`.
- Archive ZIP vérifiée séparément avant livraison.

**Non vérifié :** compilation Gradle/Android complète, génération APK, intégration WorkManager réelle sur téléphone, téléchargement long, installation après redémarrage, affichage Mapsforge de plusieurs régions et comportement Android 13 à 16. L'environnement de travail ne contient pas Gradle, le wrapper ou le SDK Android.

## 9. Sources Android et Mapsforge vérifiées

- WorkManager est recommandé pour le travail persistant qui doit survivre à la fermeture de l'application et aux redémarrages ; il conserve les travaux dans sa base et les reprogramme : documentation Android `Task scheduling`.
- Un Worker standard est limité à dix minutes. Android décrit les travaux de plus longue durée via `setForeground()` et avertit des quotas Android 16 : documentation WorkManager et Android 16.
- `DownloadManager.ACTION_DOWNLOAD_COMPLETE` est le broadcast officiel émis à la fin d'un téléchargement : API Android `DownloadManager`.
- Les index `.poly` utilisés sont ceux du miroir Mapsforge, distincts des fichiers `.map` téléchargés.
