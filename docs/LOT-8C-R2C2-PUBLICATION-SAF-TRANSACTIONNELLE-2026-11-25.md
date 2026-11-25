# LOT 8C-R2C.2 — Publication SAF transactionnelle des GPX récupérés

**Date :** 25 novembre 2026  
**Base immédiate :** projet complet R2C.1, compilation Android confirmée sans erreur par l'utilisateur.  
**Statut de cette candidate :** tests JVM et audits statiques réussis ; compilation Android R2C.2 à confirmer par l'utilisateur.  
**GitHub :** aucun fichier distant modifié ; les fichiers sont fournis pour remplacement local par l'utilisateur.

## 1. Objectif

Rendre les traces GPX récupérées visibles dans le dossier choisi avec le Storage Access Framework (SAF), sans faire dépendre la conservation de la récupération d'un fournisseur externe. Le fichier récupéré dans le stockage privé de l'application reste la source de vérité. La publication SAF est une seconde copie transactionnelle et reprenable.

Le registre de récupération multi-sessions `recovery.registry` reste distinct du nouveau registre de publication `recovery-publications.registry`. Le premier dit quelle trace locale doit être récupérée ; le second dit où en est son transfert vers le fournisseur SAF.

## 2. Décision de durabilité

La récupération locale est effectuée et vérifiée avant toute publication SAF. L'original interrompu n'est jamais modifié. Tant que la publication externe n'a pas été relue et vérifiée, le registre de récupération reste en attente.

Une publication n'est considérée comme réussie qu'après fermeture du flux SAF, relecture complète du document et égalité simultanée de la longueur et du SHA-256 avec la copie locale. `flush()` seul ne constitue pas une preuve de publication. Si l'URI, la permission ou la relecture est indisponible, l'entrée reste en attente et la copie locale est conservée.

Après vérification SAF, l'état `VERIFIED` du registre de publication est retiré, puis le snapshot exact du registre de récupération est acquitté. Si l'acquittement du journal échoue car le snapshot a évolué, le document SAF reste publié et vérifié ; le journal récent est conservé pour une nouvelle vérification idempotente.

## 3. Architecture ajoutée

### 3.1 `RecoveryPublicationRegistry`

Modèle pur Java, versionné (`publicationFormat=1`), avec au plus 64 entrées sans éviction automatique. Chaque entrée associe : identifiant de publication, session, génération de segment, nom source, nom de la copie récupérée, longueur, SHA-256, URI tree, nom SAF, URI du document et état.

États :

- `PREPARED` : l'intention a été synchronisée avant toute création SAF ;
- `DOCUMENT_CREATED` : l'URI du document a été sauvegardée ; une écriture partielle de ce document peut être reprise ;
- `VERIFIED` : le document a été relu, longueur et SHA-256 identiques.

Le parseur rejette version inconnue, compte/format incohérents, doublons, URI invalide, hex incorrect, UTF-8 mal formé et dépassement de taille/capacité. Une donnée corrompue n'est jamais remplacée par un registre vide.

### 3.2 `RecoveryPublicationStore`

Stockage interne privé via `AtomicFile` : `startWrite`, écriture UTF-8, `flush`, `FileDescriptor.sync`, `finishWrite`. Une corruption ou une erreur de lecture bloque les modifications destructives. `removeVerified` refuse toute entrée qui n'est pas à l'état `VERIFIED`.

### 3.3 `SafRecoveryAccess`

Adaptateur Android `DocumentsContract`/`ContentResolver` :

- exige une permission persistée en lecture **et** écriture ;
- liste le dossier tree afin de retrouver un document créé avant un crash ;
- crée avec MIME `application/gpx+xml` ;
- écrit avec le mode `wt` ;
- relit avec `openInputStream` ;
- borne la recherche exhaustive à 20 000 enfants. Au-delà, la publication est refusée et reste en attente plutôt que de créer à l'aveugle.

Les fournisseurs qui ne savent pas lister ou relire le document ne permettent pas de déclarer la publication vérifiée. Le fichier local est conservé ; aucune réussite externe n'est annoncée.

### 3.4 `GpxRecoveryPublisher`

Orchestrateur pur Java avec interfaces SAF et store injectées. Il calcule le SHA-256 local, persiste l'intention `PREPARED`, retrouve un document existant par nom déterministe, écrit si nécessaire, ferme le flux, relit tous les octets, vérifie longueur + SHA-256, puis persiste `VERIFIED`.

Une écriture SAF partielle reste attachée à l'URI déjà enregistrée et peut être réécrite lors du retry. Un cas plus délicat est traité conservativement : si l'application a créé un document mais a été interrompue avant de pouvoir sauvegarder son URI, le document retrouvé par nom est adopté seulement si son contenu est déjà identique. Un document homonyme différent ou illisible n'est pas écrasé ; la transaction reste en attente. L'utilisateur pourra supprimer/résoudre ce fichier orphelin dans le dossier choisi, puis le retry pourra recommencer.

Si le dossier cible change pendant une transaction inachevée, le registre est retargeté vers le nouveau tree ; l'ancien document partiel n'est pas supprimé automatiquement.

### 3.5 `GpxRecoveryWorkflow`

Workflow commun de `MainActivity` et `TrackingService`. Il est exécuté sur thread de fond ; le verrou process-wide sérialise les démarrages de récupération concurrents. Il enchaîne récupération locale, publication SAF facultative, vérification, retrait de l'entrée de publication et acquittement conditionnel du snapshot.

États visibles dans le résultat :

- `PUBLISHED` : publication SAF vérifiée et journal acquitté ;
- `LOCAL_ONLY` : pas de dossier SAF configuré, copie interne finale ;
- `PENDING_PERMISSION` : permission de lecture/écriture non disponible ;
- `PENDING_PUBLICATION` : transfert ou acquittement transactionnel à reprendre ;
- `JOURNAL_NOT_ACKNOWLEDGED` : publication éventuellement déjà vérifiée, mais snapshot de récupération évolué ;
- `RECOVERY_FAILED` : création de la copie locale impossible.

Les notifications séparent désormais « publiée », « publication en attente », « journal à acquitter » et « copie privée récupérée ». Le résultat peut distinguer une publication SAF déjà vérifiée d'un journal dont l'acquittement attend ; il distingue aussi une copie privée locale lorsque aucun SAF n'est configuré. Une publication vérifiée n'est pas annoncée à tort comme un transfert encore incomplet.

## 4. Reprise après panne

| Moment de l'interruption | Comportement au prochain démarrage |
|---|---|
| Avant `PREPARED` | aucune publication externe commencée ; récupération locale reste déterminante |
| Après `PREPARED`, avant création | le dossier est interrogé ; le document sera créé si aucun homonyme n'existe |
| Après création, avant sauvegarde URI | le nom déterministe est recherché ; adoption uniquement si contenu déjà identique ; sinon collision conservée sans écrasement |
| Après sauvegarde URI, pendant écriture | l'URI connue est réutilisée ; le document transactionnel peut être réécrit en entier |
| Après écriture, avant vérification | le document est relu ; longueur et digest décident du succès |
| Après vérification, avant état `VERIFIED` | le document existant est relu au retry puis l'état peut être commité |
| Après `VERIFIED`, avant retrait publication | retry retrouve et revérifie le document, puis retire l'état |
| Après retrait publication, avant acquittement récupération | le document est retrouvé par nom déterministe et revérifié ; le snapshot exact est réessayé |
| Permission révoquée/fournisseur indisponible | copie locale et registre conservés ; pas d'acquittement |

Le retry est automatique lors du prochain parcours de récupération au démarrage de `MainActivity` ou du service. Un bouton de retry dédié dans l'interface n'est pas encore ajouté.

## 5. Fichiers modifiés / ajoutés

| Chemin | Action |
|---|---|
| `.github/workflows/android-build.yml` | ajout du contrôle statique publication SAF |
| `app/src/main/java/com/example/gpstest/MainActivity.java` | utilise le workflow commun et affiche les états de publication/acquittement |
| `app/src/main/java/com/example/gpstest/TrackingService.java` | utilise le workflow commun sur thread dédié ; compteurs de statut exacts |
| `app/src/main/java/com/example/gpstest/RecoveryPublicationRegistry.java` | nouveau modèle strict et borné |
| `app/src/main/java/com/example/gpstest/RecoveryPublicationStore.java` | nouveau stockage `AtomicFile` |
| `app/src/main/java/com/example/gpstest/SafRecoveryAccess.java` | nouvel adaptateur SAF avec garde de 20 000 entrées |
| `app/src/main/java/com/example/gpstest/GpxRecoveryPublisher.java` | nouvel orchestrateur de publication et de vérification |
| `app/src/main/java/com/example/gpstest/GpxRecoveryWorkflow.java` | nouveau workflow Android commun |
| `tests/RecoveryPublicationRegistryTest.java` | nouveaux tests sérialisation, monotonie, corruption, capacité |
| `tests/GpxRecoveryPublisherTest.java` | nouveaux tests de panne, reprise, collision, lecture et écriture simulées |
| `tools/verify_gpx_recovery.py` | adapté aux appels via le workflow commun |
| `tools/verify_gpx_recovery_publication.py` | nouveau contrôle structurel transaction SAF |
| `README.md` | état courant R2C.2 ajouté ; section R1 requalifiée en historique |
| `docs/DOSSIER-REPRISE-ULTRA-EXHAUSTIF-2026-11-25-MAJ10.md` | dossier de reprise mis à jour, inclut la campagne réelle ADB reportée |
| `docs/LOT-8C-R2C2-PUBLICATION-SAF-TRANSACTIONNELLE-2026-11-25.md` | présente note |
| `LISEZ-MOI-LOT-8C-R2C2.md` | consignes de remplacement |

Aucun changement manifeste ou permission Android n'a été ajouté. `Destination.setTreeUri()` conserve déjà le droit SAF READ + WRITE. Le contrat de trace GPX, le writer GNSS et le registre R2C.1 restent distincts.

## 6. Vérifications exécutées dans la copie de travail

| Vérification | Résultat observé |
|---|---|
| `python3 tools/run_jvm_tests.py` | **46 suites, 1 637 assertions, 0 échec** |
| `GpxRecoveryPublisherTest` | 36 assertions |
| `RecoveryPublicationRegistryTest` | 20 assertions |
| `python3 tools/verify_gpx_recovery.py` | OK |
| `python3 tools/verify_gpx_recovery_publication.py` | OK |
| `python3 tools/verify_gpx_checkpoint.py` | OK |
| `python3 tools/verify_gpx_async_open.py` | OK |
| `python3 tools/verify_android_project.py` | 17 XML, 89 sources Java ; OK |
| `python3 tools/verify_offline_map_architecture.py` | 107 fichiers ; aucun moteur raster ; OK |
| `python3 -m py_compile tools/*.py` | OK |
| Analyse YAML du workflow | syntaxe valide ; jobs `android` et `gpx-xsd` |
| Reconstruction par ZIP fichiers-à-remplacer sur la base R2C.1 | **réussie** : mêmes 46 suites / 1 637 assertions et audits statiques |
| `assembleDebug`, Android Lint, tests instrumentés | non exécutés dans l'environnement de préparation |
| SAF réel / téléphone / ADB | explicitement reporté à plus tard |

Les tests SAF emploient un fournisseur simulé : ils exercent les transitions transactionnelles et les pannes, mais ne prouvent pas le comportement de Google Drive, OneDrive, MTP, d'une carte SD ou d'un fournisseur OEM réel. La campagne Android réel/ADB reste reportée conformément à la décision de l'utilisateur.

## 7. Limites et choix conservateurs

- Le registre de publication est limité à 64 entrées ; pas d'éviction automatique. Si le registre est saturé/corrompu, la publication attend et la copie privée est conservée.
- Un fournisseur incapable de relire un document ne permet pas le passage à `VERIFIED`.
- Après crash exact entre `createDocument()` et sauvegarde de l'URI, un document orphelin vide/partiel peut nécessiter une résolution manuelle ; il ne sera pas écrasé sur simple correspondance de nom.
- Le scan SAF refuse les dossiers de plus de 20 000 enfants plutôt que de risquer un doublon sans recherche exhaustive.
- Changer de dossier pendant une transaction laisse l'ancien document partiel en place ; il n'est pas supprimé, car l'application ne doit pas effacer un document utilisateur sans preuve forte.
- Le retry automatique se produit au prochain démarrage ; pas encore de bouton utilisateur dédié.
- Les verrous sont process-wide ; aucun comportement multi-processus n'est revendiqué.
- La compilation R2C.1 a été confirmée sans erreur par l'utilisateur. La compilation Android R2C.2 doit être confirmée après remplacement.
- Aucune action GitHub distante n'a été faite par l'assistant.

## 8. Installation

Utiliser `GPS-Test-LOT-8C-R2C2-FICHIERS-A-REMPLACER-2026-11-25.zip` et le décompresser à la racine du dépôt local, en conservant les chemins et en acceptant les remplacements. L'archive contient les fichiers coordonnés, tests, vérificateurs, workflow CI, README et dossier de reprise MAJ10. Ne pas appliquer de patch Git. Le projet complet est fourni dans `GPS-Test-LOT-8C-R2C2-PROJET-COMPLET-2026-11-25.zip`.

Après remplacement, exécuter Sync puis `assembleDebug` et `lintDebug` dans Android Studio. Comme convenu, le silence de l'utilisateur après la livraison sera interprété comme absence d'erreur de compilation, et la validation R2C.2 pourra être notée. L'utilisateur reste le seul à reporter les fichiers sur GitHub.

## 9. Suite recommandée

Prochaine amélioration logicielle possible : intégrer un écran/une action explicite permettant de relancer les publications restées en attente et d'expliquer une collision SAF/permission révoquée/registre saturé. Cette UI devra rester séparée du thread GNSS et ne devra jamais proposer de purge destructive non confirmée.

La campagne de tests réels sur téléphone et ADB reste reportée à plus tard. Elle devra être menée avant de prétendre que l'intégration SAF est validée chez les fournisseurs Android réels.
