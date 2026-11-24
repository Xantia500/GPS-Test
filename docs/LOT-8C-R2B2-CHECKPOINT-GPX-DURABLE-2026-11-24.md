# GPS-Test — LOT 8C-R2B.2 : checkpoint GPX durable

**Date :** 24 novembre 2026  
**Base :** projet complet LOT 8C-R2B.1  
**Branche GitHub :** aucune modification distante effectuée  
**État :** candidat préparé localement ; compilation Android de ce lot à confirmer par l'utilisateur.

## 1. Objectif

Le LOT 8C-R2B.1 a déplacé l'ouverture GPX et l'écriture de l'en-tête sur le worker `ioFile`. Il manquait encore une mesure persistante du dernier préfixe de fichier effectivement synchronisé.

Le champ `SessionJournal.safeLengthBytes`, présent mais nul jusque-là, devient le point de reprise exact d'un segment. Il ne représente ni le nombre de points reçus, ni `pointsWritten`, ni une longueur seulement vidée du `BufferedWriter`.

## 2. Invariant de durabilité

Un nouvel offset est publié uniquement après la séquence suivante, dans une tâche de la FIFO `ioFile` et sous `segment.ioLock` :

1. `BufferedWriter.flush()` ;
2. `FileDescriptor.sync()` retourne sans exception ;
3. lecture de `segment.file.length()` ;
4. avancement du `GpxCheckpoint` si le nom de fichier correspond et si la longueur ne régresse pas ;
5. mise en file de `SessionStore.updateCheckpoint()` sur `ioMeta`, avec session, génération, nom du fichier et longueur exacte.

Si `FileDescriptor.sync()` échoue, le checkpoint ne progresse pas. L'application conserve l'ancien offset connu et signale un avertissement de durabilité. Une longueur nulle, un nom différent ou une longueur inférieure sont également refusés.

Le premier checkpoint est tenté immédiatement après l'écriture et le vidage de l'en-tête GPX. Cela évite de rester sans préfixe de référence pendant les trente premières secondes. Ensuite, le rythme de synchronisation existant reste de trente secondes au maximum, avec un jalon lors de la fermeture du segment. La fermeture écrit le pied GPX, vide le writer, synchronise le FD, puis mesure la longueur ; lorsque cette synchronisation réussit, le checkpoint final inclut le pied.

## 3. Association du segment et anti-course

Chaque `RecordingSegment` possède :

- un identifiant de génération `long` ;
- l'identifiant UUID de la session ;
- son propre `GpxCheckpoint` (nom de fichier + longueur sûre).

Le nom est réservé exclusivement par `uniqueFile()` avant ouverture. L'association `registerSegment()` est envoyée sur `ioMeta` dès la réservation du nom, avant l'envoi du premier checkpoint. La FIFO méta reçoit donc l'association avant `updateCheckpoint()`.

`SessionStore` refuse :

- une mise à jour d'une autre session ;
- un segment d'une génération plus ancienne ;
- un nom de fichier différent de celui associé à la génération active ;
- une longueur qui régresse.

Un nouveau segment remet son offset à zéro. Un snapshot UI plus ancien d'une même génération ne peut pas effacer le fichier ou faire reculer le préfixe durable. Cette protection couvre les tâches retardées de `ioFile`, `ioMeta` et du thread UI.

## 4. Format du journal

`SessionJournal.FORMAT_VERSION` passe à **5** avec la clé :

```text
segmentGeneration=<identifiant long du segment>
```

La clé `safeLength` est désormais alimentée à partir du checkpoint du segment, jamais à partir d'un compteur de points.

Les formats antérieurs 1 à 4 restent lisibles. En l'absence de `segmentGeneration`, la génération historique vaut zéro. Le stockage continue d'utiliser `AtomicFile` et conserve ses protections de sauvegarde, migration et mise en quarantaine.

### Destruction du service

`onDestroy()` remet `state.recording` à `false` pour l'interface après avoir demandé la fermeture du segment. Or le snapshot final peut s'exécuter plus tard, après drainage de `ioFile`. Un indicateur dédié `serviceDestroyedWhileRecording` mémorise donc si la destruction non volontaire a commencé pendant un enregistrement. Le snapshot final conserve alors `recording=true`, sans changer le comportement d'un arrêt volontaire (`stopRequested=true`, journal supprimé).

Ce point est essentiel : un checkpoint avec un nom et des octets mais sans indication que l'enregistrement était actif pourrait être mal interprété par la prochaine étape de récupération.

## 5. Fichiers modifiés ou ajoutés

| Chemin | Action | Rôle |
|---|---|---|
| `app/src/main/java/com/example/gpstest/TrackingService.java` | modifié | flush, sync FD, longueur réelle, checkpoint initial/périodique/final, maintien du marqueur d'interruption |
| `app/src/main/java/com/example/gpstest/SessionJournal.java` | modifié | format 5, génération, offset sûr, anciens formats lisibles |
| `app/src/main/java/com/example/gpstest/SessionStore.java` | modifié | association segment/fichier, update monotone et rejet des générations anciennes |
| `app/src/main/java/com/example/gpstest/GpxCheckpoint.java` | nouveau | état pur Java du fichier et du préfixe sûr |
| `tests/GpxCheckpointTest.java` | nouveau | association, monotonie, rejet des offsets erronés, simulation de troncature au dernier préfixe synchronisé |
| `tests/SessionJournalTest.java` | modifié | format 5 et compatibilité des formats antérieurs |
| `tests/SessionStoreTest.java` | modifié | générations, fichier actif, monotonie et snapshots retardés |
| `tools/verify_gpx_checkpoint.py` | nouveau | contrôle structurel du contrat flush/sync/length/store et de l'arrêt |
| `.github/workflows/android-build.yml` | modifié | ajout du contrôle R2B.2 au workflow Android |

La livraison comprend aussi cette note et `LISEZ-MOI-LOT-8C-R2B2.md`.

## 6. Contrôles réellement exécutés

Sur le candidat local R2B.2 :

| Contrôle | Résultat |
|---|---|
| `python3 tools/run_jvm_tests.py` | **42 suites, 1 463 assertions, 0 échec** |
| `python3 tools/verify_gpx_checkpoint.py` | 15 invariants structurels passés |
| `python3 tools/verify_gpx_async_open.py` | invariants R2B.1 passés |
| `python3 tools/verify_android_project.py` | 17 XML et 82 sources Java audités ; manifeste, permissions, FGS, SDK et Edge-to-Edge cohérents |
| `python3 tools/verify_offline_map_architecture.py` | 100 fichiers Android analysés ; aucun moteur de tuiles raster |
| Parseur YAML PyYAML du workflow | réussi ; jobs `android` et `gpx-xsd` reconnus |
| `python3 -m py_compile tools/verify_gpx_checkpoint.py` | réussi |
| Compilation Android / Lint / tests instrumentés / appareil | non exécutés dans l'environnement de préparation |

L'utilisateur a confirmé l'absence d'erreur de compilation sur R2B.1. Cela ne valide pas automatiquement R2B.2 : cette livraison doit être compilée à son tour dans Android Studio.

## 7. Limites explicites

Ce lot enregistre un offset sûr ; il **ne réalise pas encore la récupération automatique**. À ce stade :

- le fichier peut encore contenir des octets après `safeLengthBytes` ;
- le journal peut volontairement indiquer un offset antérieur si le processus est tué après le `sync()` du GPX mais avant la validation atomique du journal ; c'est conservateur : il peut perdre un peu de trace, mais ne revendique pas des octets non validés ;
- si le stockage ou le matériel ne respecte pas réellement les demandes de synchronisation, aucun code Java ne peut garantir l'intégrité physique absolue ;
- le prochain lot devra vérifier l'existence du fichier, que sa longueur est au moins égale à l'offset, tronquer la queue non confirmée et finaliser le document GPX sans ajouter deux fois le pied ;
- la synchronisation du contenu du fichier ne constitue pas à elle seule une preuve de durabilité de l'entrée de répertoire après panne matérielle ; la récupération devra tolérer le cas où le nom de fichier n'existe plus.

Ne pas annoncer que GPS-Test sait déjà récupérer automatiquement une trace après SIGKILL, crash natif ou coupure électrique : cette capacité reste à implémenter et à tester.

## 8. Installation par l'utilisateur

L'assistant ne modifie pas GitHub. Pour utiliser ce lot, remplacer les fichiers listés dans l'archive de remplacement à la racine du dépôt local, en gardant les chemins. Ne pas appliquer de patch Git.

Après remplacement :

1. ouvrir le projet dans Android Studio ;
2. lancer **Sync Project with Gradle Files** si demandé ;
3. lancer `assembleDebug` ;
4. signaler toute erreur de compilation avec le premier message complet et le fichier/ligne concernés.

## 9. Étape suivante proposée — LOT 8C-R2B.3

Construire le récupérateur de trace interrompue :

1. lire le journal `dirty` non acquitté et valider le nom comme simple nom de fichier app-specific ;
2. vérifier que le fichier correspondant existe et que sa longueur est au moins le checkpoint ;
3. si sa longueur est inférieure à l'offset, classer le fichier comme dégradé et ne jamais étendre/inventer des octets ;
4. si elle est supérieure, tronquer uniquement la queue postérieure à `safeLengthBytes` ;
5. détecter si le pied GPX est déjà présent (checkpoint pris après fermeture) ou si le document est un préfixe ouvert ;
6. compléter et synchroniser le pied seulement si nécessaire ;
7. valider le XML avec le parseur/contrat GPX, préserver le fichier d'origine en cas d'échec et fournir un rapport d'intégrité ;
8. acquitter la récupération dans le journal de façon transactionnelle, sans supprimer l'original avant validation.

Avant cette étape, compiler R2B.2 dans Android Studio et confirmer qu'il n'y a aucune erreur. Aucun fichier de GitHub n'a été modifié par l'assistant.
