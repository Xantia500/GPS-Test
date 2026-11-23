# Lot 7C — Catalogue et gestion des cartes hors ligne

Date de travail : 23 novembre 2026  
Base : `GPS-Test-lot-7B.zip`  
Périmètre : navigation du catalogue Mapsforge v5, téléchargement manuel de régions, progression, annulation, contrôle local, installation persistante, mise à jour et suppression.

## 1. Source de données retenue

La page de téléchargement officielle Mapsforge présente les cartes vectorielles mondiales et indique :

- le serveur Mapsforge principal, explicitement décrit comme inadapté aux téléchargements massifs ;
- un miroir rapide à la Hochschule Esslingen.

Le client de cette version utilise uniquement le miroir HTTPS référencé par Mapsforge :

```text
https://ftp-stud.hs-esslingen.de/pub/Mirrors/download.mapsforge.org/maps/v5/
```

Le catalogue est navigué par hiérarchie de dossiers : continent, pays, sous-région, fichier `.map`. Chaque téléchargement est déclenché explicitement par l'utilisateur. Il n'y a ni préchargement global, ni téléchargement automatique de plusieurs régions, ni requête de tuiles raster.

La page de téléchargement Mapsforge attribue les données à OpenStreetMap et indique la licence ODbL. L'application affiche l'attribution OSM dans le gestionnaire et la rend cliquable vers la page de licence/copyright.

La présence de ce miroir sur la page Mapsforge constitue la justification de son utilisation comme source de catalogue. Avant une distribution commerciale ou une redistribution des fichiers cartographiques, les obligations ODbL et les conditions propres à la source des cartes doivent néanmoins être revues. Le client ne distribue pas les cartes avec l'APK ; il permet à l'utilisateur de télécharger la région qu'il choisit.

## 2. Catalogue

Nouvelles classes :

- `MapsforgeCatalogParser` : analyse bornée des index Apache autoindex. Elle n'accepte que les sous-dossiers et les fichiers `.map` relatifs ; les URL absolues, les traversées de chemin, les liens non cartographiques et les index de plus de 2 Mio sont ignorés/refusés.
- `MapsforgeCatalogClient` : client HTTPS limité à l'hôte du miroir connu. Les redirections éventuelles sont limitées au même hôte HTTPS et au sous-arbre Mapsforge v5.
- `OfflineMapsActivity` : interface native de navigation, liste des cartes installées, téléchargement, progression, annulation, mise à jour et suppression.

Les métadonnées du catalogue exposées sont :

- libellé de région ;
- chemin relatif ;
- taille indicative de l'index ;
- date `Last modified` lorsqu'elle est fournie par le serveur.

L'identifiant interne de région est construit à partir du chemin complet avec un suffixe SHA-256 tronqué à 64 bits. Le suffixe évite les collisions de slug dues notamment aux séparateurs `/`, `-` et `_`. L'identifiant est conservé dans les métadonnées locales.

Le navigateur de cette étape est une liste hiérarchique. Il ne s'agit pas encore d'une carte mondiale avec polygones cliquables. Les fichiers de frontières `.poly`, le surlignage géographique et la sélection tactile des régions sont reportés à une étape ultérieure.

## 3. Téléchargement Android

Le téléchargement est délégué à `DownloadManager` Android :

- un seul transfert de carte est autorisé à la fois dans cette interface ;
- transfert HTTPS ;
- notification système de fin de téléchargement ;
- suivi des octets téléchargés et de la taille totale lorsque le serveur la fournit ;
- pause/reprise et nouvelles tentatives selon le comportement de `DownloadManager` et du serveur ;
- données mobiles désactivées par défaut ;
- l'utilisateur doit explicitement cocher l'autorisation de données mobiles ;
- itinérance désactivée ;
- annulation possible tant que la validation/installation n'a pas commencé.

`DownloadManager` peut continuer le transfert lorsque l'Activity est fermée ou que le processus de l'application est arrêté. L'identifiant du téléchargement et les paramètres nécessaires sont conservés dans les préférences. Si Android tue le processus après la fin du transfert mais avant la finalisation, le fichier reste dans le dossier temporaire app-specific ; l'installation est reprise lorsque l'utilisateur rouvre le gestionnaire des cartes. Cette version n'ajoute pas de Worker WorkManager ou de BroadcastReceiver autonome pour finaliser l'installation sans réouverture de l'écran.

Le téléchargement temporaire est enregistré dans `getExternalFilesDir("offline-map-downloads")`. Si Android ne fournit pas ce dossier app-specific externe, le téléchargement via `DownloadManager` est refusé explicitement (même si le dépôt existant peut encore utiliser son repli interne). Le dépôt permanent se trouve dans `getExternalFilesDir("offline-maps")`, avec repli dans le stockage privé interne. Aucun accès global au stockage, `READ_EXTERNAL_STORAGE` ou `WRITE_EXTERNAL_STORAGE` n'est ajouté.

## 4. Contrôle et installation

Après téléchargement, l'application :

1. vérifie que l'URI locale fournie par `DownloadManager` correspond au fichier attendu dans le dossier temporaire privé ;
2. vérifie que le fichier existe, n'est pas vide, ne dépasse pas la limite de 4 Gio et correspond à la taille indiquée par le gestionnaire lorsque celle-ci est connue ;
3. calcule un SHA-256 local complet ;
4. inspecte l'en-tête binaire Mapsforge, exige le format v5 et vérifie la taille déclarée et les valeurs d'en-tête ;
5. déplace le fichier vers un nom versionné par région et SHA-256 ;
6. publie les métadonnées par fichier temporaire et renommage ;
7. conserve l'ancienne carte référencée tant que le commit des nouvelles métadonnées n'a pas réussi.

### Limite cryptographique importante

L'index du miroir ne publie pas de somme SHA-256 signée ou de manifeste signé utilisé par cette application. Le SHA-256 calculé localement sert à identifier et contrôler le fichier stocké ; il ne prouve pas à lui seul que le fichier téléchargé est authentique par rapport à une publication Mapsforge officielle.

La protection actuelle repose sur HTTPS vers le miroir, le contrôle du chemin de téléchargement, la taille, et la validation structurelle du format Mapsforge. Une signature ou un hash publié par une source de confiance serait nécessaire pour une vérification cryptographique d'origine complète.

## 5. Mise à jour et suppression

Les métadonnées locales contiennent `sourceVersion`, renseignée à partir du `Last modified` affiché dans le catalogue. Lorsqu'une date plus récente est visible, l'interface propose « Mettre à jour ». Le téléchargement est complet : il ne s'agit pas d'un patch différentiel.

Si le serveur ne fournit pas de date exploitable, l'application ne peut pas détecter automatiquement qu'un fichier a changé. Une date de catalogue n'est pas une signature cryptographique.

La section « Cartes installées » est indépendante du catalogue distant : les cartes déjà présentes restent listées et supprimables même lorsque le réseau ou le catalogue ne répond pas. La suppression est effectuée via `OfflineMapRepository.remove()` et la carte active est rechargée lorsque l'utilisateur revient à `MapActivity`.

Les fichiers régionaux sont placés dans le stockage privé persistant de l'application, et non dans `getCacheDir()`. Ils sont conservés entre les redémarrages et les mises à jour de l'application, mais sont supprimés lors de la désinstallation de l'application ou si l'utilisateur efface ses données.

## 6. Intégration à l'écran Carte

Un bouton « Cartes hors ligne » ouvre `OfflineMapsActivity`.

Après le retour, `MapActivity` recrée son moteur afin de fermer les `MapFile` précédemment ouverts, relire les régions installées et sélectionner Mapsforge ou le repli osmdroid. La trace GPX préparée et l'état du suivi caméra sont conservés via `onRetainNonConfigurationInstance()` pendant cette recréation.

La carte reste utilisable sans téléchargement si des régions couvrent la zone. La sélection de moteur demeure celle du lot 7B : Mapsforge lorsqu'une carte locale exploitable existe, osmdroid en repli lorsqu'aucune région locale n'est disponible.

## 7. Corrections supplémentaires découvertes pendant le lot

- Fusion de deux méthodes `onActivityResult()` en doublon dans `MapActivity`. La version finale traite les résultats du sélecteur GPX et du gestionnaire de cartes dans une seule méthode, évitant une erreur de compilation Java.
- Conservation de la trace GPX lors du retour du gestionnaire et de la recréation de l'Activity.
- Identifiants de régions protégés contre les collisions de normalisation.
- Liste des régions installées indépendante de l'état du catalogue distant.
- Verrou de processus empêchant deux instances d'Activity (recréation/rotation) de valider simultanément le même fichier téléchargé.
- L'annulation est désactivée dès que la validation et l'installation ont commencé.
- En cas d'échec de commit des métadonnées après déplacement, le repository tente de remettre le fichier dans le dossier temporaire ; un test vérifie ce rollback.
- Le client de catalogue limite les redirections à l'hôte HTTPS connu et au sous-chemin Mapsforge v5.

## 8. Tests

Résultats du script `tools/run_jvm_tests.sh` après les modifications finales :

- 25 suites JVM ;
- 1 061 assertions réussies ;
- 0 échec.

Les tests supplémentaires couvrent notamment :

- parsing d'un index Apache avec dossiers, fichiers, dates et tailles ;
- rejet des URL absolues, des traversées de chemin, des extensions autres que `.map` et des chemins encodés ;
- génération d'identifiants distincts pour les chemins longs et les noms qui ne diffèrent que par underscore/tiret ;
- installation de fichier staging, SHA-256 local, en-tête Mapsforge et version source ;
- rollback si le commit des métadonnées échoue ;
- présence de cartes déjà installées même si le catalogue réseau n'est pas disponible (logique UI revue manuellement).

Le script régénère `R.java` à partir des ressources. Le manifeste et les ressources XML sont inclus dans les contrôles de parsing habituels.

## 9. Limites de validation

L'environnement ne contient ni Gradle, ni `gradlew`, ni Android SDK. En conséquence :

- la compilation Android de `OfflineMapsActivity` n'a pas été effectuée ;
- aucune APK n'a été produite ;
- aucun téléchargement réel d'une région de 200 à 350 Mio n'a été réalisé depuis un appareil ;
- le rendu Mapsforge d'un fichier régional réel doit toujours être essayé sur appareil ;
- la reprise après destruction du processus est assurée par l'identifiant `DownloadManager` persisté et une finalisation au prochain retour dans l'écran, pas par un worker autonome.

Les tests JVM vérifient les classes de parsing, validation URL, dépôt de stockage et logique métier testable. Ils ne valident pas l'intégration réelle de `DownloadManager`, les notifications système, les politiques réseau des constructeurs Android ou la compilation de l'Activity.

## 10. Étape ultérieure proposée

- téléchargement et traitement des polygones `.poly` pour afficher les régions sur une carte de sélection ;
- recherche de région et filtres pays/continent ;
- finalisation de l'installation hors Activity via WorkManager, si l'expérience doit afficher « installé » même lorsque l'utilisateur ne rouvre pas le gestionnaire ;
- gestion des cartes devenues obsolètes et nettoyage contrôlé des anciennes versions non référencées ;
- essais réels sur Android 13 à 16 et sur stockage presque plein.
