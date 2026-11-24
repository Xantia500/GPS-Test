# GPS-Test — LOT 8C-R2B.1 — Ouverture GPX asynchrone

**Date :** 24 novembre 2026  
**Base source :** archive complète `GPS-Test-LOT-8C-R2A4-PROJET-COMPLET-2026-11-24.zip`  
**Branche distante :** aucune modification GitHub effectuée  
**Périmètre :** `TrackingService`, cycle de vie du segment GPX, tests JVM et contrôle statique CI.

---

## 1. Objectif

Avant ce lot, les points GPX étaient écrits de façon asynchrone sur le worker FIFO `ioFile`, mais l'ouverture d'un segment demeurait synchrone dans `TrackingService` : récupération du répertoire, recherche d'un nom disponible, création du `FileOutputStream`, création du `BufferedWriter`, écriture et flush de l'en-tête. Ces opérations pouvaient donc bloquer le thread qui déclenchait `startRecording()` ou `resumeTracking()`, alors que le stockage externe peut être lent, saturé ou momentanément indisponible.

Le but de R2B.1 est de déplacer toutes les E/S d'ouverture sur `ioFile` sans perdre l'ordre strict :

`réservation du segment → ouverture → en-tête complet et flush → points acceptés → fermeture → post-traitement/publication`.

Aucun point ne doit précéder l'en-tête. Un arrêt, une suspension ou une révocation de permission pendant l'ouverture doit se transformer en tâche de fermeture postée derrière l'ouverture, pas en fermeture concurrente du flux.

## 2. Architecture retenue

### 2.1 Réservation rapide côté appelant

`openRecordingSegment(int)` ne crée plus de fichier. Elle lit les options de session, calcule le nom, capture la version GPX, la destination SAF, l'identifiant de segment et l'epoch de compteurs. Elle crée ensuite un `RecordingSegment` logique, le publie dans `activeSegment` et poste `openRecordingSegmentOnIo(...)` dans `ioFile`.

Cette demande est postée pendant que l'appelant détient `gnssProcessingLock`. Les callbacks GNSS qui acceptent les points sont sérialisés par le même verrou. La tâche d'ouverture est donc placée dans la FIFO avant tout point du segment.

### 2.2 Machine d'état indépendante d'Android

Nouveau fichier : `app/src/main/java/com/example/gpstest/GpxSegmentIoState.java`.

États :

- `OPENING` : segment réservé, mais en-tête pas encore flushé ; aucun point inscriptible ;
- `READY` : ouverture et en-tête terminés ; les points peuvent être écrits ;
- `FAILED` : échec d'ouverture ; aucun point ne doit être écrit ;
- `CLOSED` : cycle terminé, y compris après échec.

Les transitions sont synchronisées. `markReady()` ne réussit que depuis `OPENING`; `failOpen()` ne réussit que depuis `OPENING`; `finishClose()` rend le cycle terminal et indique si un writer avait été prêt.

### 2.3 Ouverture sur `ioFile`

`openRecordingSegmentOnIo(...)` réalise sur le worker fichier :

1. vérification que le segment est encore `OPENING` ;
2. récupération/création du répertoire ;
3. réservation atomique du nom GPX ;
4. création du flux et du writer UTF-8 ;
5. écriture de l'en-tête GPX ;
6. `flush()` de l'en-tête ;
7. publication des ressources dans l'objet segment ;
8. passage à `READY`.

Le thread appelant ne fait plus de `getExternalFilesDir`, `createNewFile`, `FileOutputStream`, `BufferedWriter`, écriture de l'en-tête ni recherche de collision.

### 2.4 Réservation atomique du nom

`uniqueFile(File)` utilise maintenant `File.createNewFile()` pour réserver le nom de façon exclusive. Le schéma précédent `exists()` puis `FileOutputStream(..., false)` possédait une fenêtre de course. Il retournait aussi le nom initial si 999 variantes étaient déjà prises, ce qui pouvait écraser le fichier initial.

Le nouveau comportement :

- tente le nom de base par création exclusive ;
- tente ensuite les suffixes `-2` à `-999` ;
- lève `IOException` si toutes les variantes sont occupées ;
- ne retourne jamais un nom déjà existant ;
- le flux est ouvert en mode append sur le fichier vide qui vient d'être réservé, afin de ne pas introduire de troncature implicite.

### 2.5 Points acceptés pendant l'ouverture

`appendPoint(...)` peut capturer le `RecordingSegment` dès qu'il est publié dans `activeSegment`, même si le writer n'est pas encore disponible. Le point est placé dans la FIFO derrière l'ouverture. La limite existante `MAX_GPX_QUEUE_DEPTH = 120` continue à borner les points en attente.

Au moment où le Runnable du point s'exécute, il exige simultanément :

- segment non fermé ;
- aucune erreur d'écriture ou d'ouverture ;
- état `READY` ;
- writer disponible.

Si l'en-tête a échoué, les points qui ont déjà été postés sont comptés comme échecs après mise en file ; ceux qui arrivent après la constatation de l'échec sont rejetés et comptés avant mise en file. Aucun point ne peut être écrit dans un document sans en-tête valide.

### 2.6 Fermeture, suspension et arrêt immédiat

`closeRecordingSegment()` est sérialisée par `gnssProcessingLock`, désactive immédiatement l'acceptation pour le segment puis poste `closeSegmentOnIo(...)` sur la même FIFO. La fermeture arrive donc derrière l'ouverture et derrière tous les points acceptés.

`closeSegmentOnIo(...)` :

- écrit le pied GPX, flush, sync et ferme uniquement si le segment est `READY` et sans erreur d'écriture ;
- si l'ouverture a échoué, ferme ce qui existe sans fabriquer de pied trompeur ;
- préserve le fichier partiel éventuel ;
- met l'état à `CLOSED` ;
- ne publie pas un segment incomplet.

Le repli si le Handler n'accepte plus la fermeture utilise le même `ioLock`, afin de ne pas fermer un flux pendant une écriture déjà en cours.

### 2.7 Post-traitement après la fermeture réelle

`runPostProcess(RecordingSegment)` ne capture plus un `gpxFile` global qui peut être nul quand l'ouverture est encore en attente. Il poste une barrière dans `ioFile`; cette barrière observe le segment après l'ouverture et la fermeture. Le nettoyeur ne démarre que si le fichier existe, que le segment est fermé et que l'ouverture/écriture ne sont pas en erreur. Sinon, la trace brute éventuelle est conservée et le statut l'indique.

### 2.8 Journal de session au drainage final

Le service peut être détruit alors que l'ouverture est encore dans `ioFile`. Le premier `persistSession()` de `onDestroy()` peut donc précéder la création du fichier et ne pas connaître son nom.

`persistSessionTo(Handler)` permet au dernier jalon de drainage du worker fichier de prendre un snapshot après les tâches GPX déjà mises en file, puis de le poster sur le worker méta avant `quitSafely()` de ce dernier. Le chemin est conditionné par `!stopRequested` : un arrêt volontaire ne recrée jamais le journal qui vient d'être effacé.

Le worker méta reste distinct de la FIFO GPX. Le snapshot final contient toujours `safeLengthBytes = 0L` à ce stade : R2B.1 ne prétend pas avoir livré de checkpoint durable.

### 2.9 Publication SAF de secours

À la destruction, les références aux Handler peuvent être retirées alors que les tâches déjà mises en file continuent leur drainage. Si `ioPublish` n'accepte plus la publication finale d'un segment complet, un thread daemon dédié exécute le repli `publishSnapshot`. Il n'effectue pas cette opération sur le thread principal ni sur `ioFile`.

---

## 3. Fichiers livrés

### Fichiers applicatifs/tests/CI (6)

| Chemin relatif | Nature | Rôle |
|---|---|---|
| `app/src/main/java/com/example/gpstest/TrackingService.java` | modifié | ouverture, échec, fermeture, post-traitement et drainage final asynchrones |
| `app/src/main/java/com/example/gpstest/GpxSegmentIoState.java` | nouveau | machine d'état pure du cycle I/O d'un segment |
| `tests/GpxSegmentIoStateTest.java` | nouveau | transitions et interdictions d'écriture |
| `tests/GpxSegmentQueueOrderTest.java` | nouveau | FIFO ouverture/points/fermeture et ordre du snapshot méta final |
| `tools/verify_gpx_async_open.py` | nouveau | contrôle statique des invariants appliqués au service réel |
| `.github/workflows/android-build.yml` | modifié | exécute le contrôle statique R2B.1 dans le job Android |

La livraison remplaçable ajoute également cette note dans `docs/` et un `LISEZ-MOI` à la racine du projet.

## 4. Tests réellement exécutés

Résultats sur la copie R2A.4 modifiée :

- tests JVM : **41 suites, 1 421 assertions réussies, 0 échec** ;
- `tools/verify_gpx_async_open.py` : tous les invariants structurels annoncés passent ;
- `tools/verify_android_project.py` : **17 XML, 81 sources Java**, cohérence statique manifeste/permissions/FGS/SDK/R/Edge-to-Edge ;
- `tools/verify_offline_map_architecture.py` : **99 fichiers**, architecture cartographique locale sans moteur raster ;
- YAML du workflow GitHub Actions analysé avec succès.

Les tests JVM simulent les tâches sur un HandlerThread de test et couvrent les transitions pures et l'ordre FIFO. Le contrôle statique vérifie les appels et barrières dans `TrackingService`. Ce n'est pas un test instrumenté du vrai Service Android ni un essai de stockage/OEM.

## 5. Validations non exécutées / limites

La compilation Android, Android Lint et les essais sur appareil n'ont pas été exécutés dans l'environnement de préparation : Gradle est absent, le Gradle Wrapper n'est pas versionné dans le dépôt source et aucun Android SDK utilisable n'est présent.

L'utilisateur a confirmé la compilation sans erreur pour R2A.4. Cette confirmation ne vaut pas compilation du présent R2B.1. La prochaine action est donc une compilation locale du projet livré, puis retour du résultat avant le lot suivant.

GitHub n'a pas été modifié. Le job Actions ne sera exécuté que lorsque l'utilisateur aura lui-même reporté les fichiers dans le dépôt et déclenché le workflow.

## 6. Risques résiduels et tests appareil à prévoir

À tester sur Android réel, idéalement plusieurs fabricants :

1. START puis STOP immédiat, avec stockage rapide ;
2. START puis STOP immédiat, avec stockage lent ou indisponible ;
3. suspension écran pendant `OPENING`, puis reprise ;
4. révocation de permission pendant `OPENING` ;
5. arrêt du service/processus alors que l'ouverture est en file ;
6. disque plein ou erreur de création, d'écriture d'en-tête, de flush et de close ;
7. rafale de points pendant ouverture lente : limite des 120 points et compteurs ;
8. reprise d'un segment après pause : ordre et absence d'écrasement ;
9. destination SAF active : publication normale et repli à l'arrêt du worker ;
10. vérifier que le journal final contient le nom du dernier GPX existant après drainage, tout en restant `dirty` après interruption.

Un kill brutal (`SIGKILL`, crash natif, batterie arrachée) ne garantit pas l'exécution de `onDestroy()`. Le journal final au drainage réduit les trous pendant un arrêt propre du service ; il ne remplace pas le checkpoint fsync de R2B.2/R2C.

---

## 7. Suite prévue

- **R2B.2** : checkpoint GPX d'un préfixe durable, aligné sur une barrière FIFO + `flush()` + `FileDescriptor.sync()` réussi + longueur exacte du fichier + journal atomique.
- **R2C** : récupération après arrêt/crash, validation du préfixe, troncature seulement vers un checkpoint confirmé, fermeture GPX idempotente, registre des sessions récupérables.
- Tests sur appareils, stockage OEM et scénarios de processus tué avant de considérer l'enregistrement robuste en production.

Ne pas déduire `safeLengthBytes` de `pointsWritten` : les buffers Java peuvent encore contenir les données et `flush()` sans `sync()` ne constitue pas le même niveau de durabilité.
