# LOT 8C-R2C.3 — Reprise explicite et persistante des récupérations / publications SAF

**Date : 25 novembre 2026**  
**Base : LOT 8C-R2C.2, compilation Android confirmée par l'utilisateur**  
**État : candidate locale préparée et contrôles JVM/statique réussis ; compilation Android R2C.3 laissée à l'utilisateur, conformément à sa consigne.**

## 1. Objectif

R2C.2 a rendu la publication SAF transactionnelle : copie locale conservée, création de document, écriture, fermeture, relecture complète, vérification longueur + SHA-256, puis acquittement conditionnel. R2C.3 apporte le cycle opérationnel manquant : déclencher une nouvelle tentative après retour de permission, après l'arrêt du suivi GNSS, au retour de l'application ou à la demande explicite de l'utilisateur.

Le lot doit aussi rendre les résultats compréhensibles, en distinguant :

- trace encore à récupérer ;
- transaction SAF encore à publier ou à vérifier ;
- permission utilisateur à rétablir ;
- erreur transitoire qui peut être réessayée ;
- conflit ou corruption qui exige une intervention ;
- copie locale conservée sans publication externe demandée ;
- publication vérifiée ;
- journal non acquitté parce qu'il a changé depuis la lecture.

## 2. Exécution persistante WorkManager

Deux nouvelles classes Android sont ajoutées :

- `GpxRecoveryRetryScheduler` : programme une `OneTimeWorkRequest` unique nommée `gps-test-gpx-recovery-retry`, avec `ExistingWorkPolicy.KEEP` et backoff exponentiel initial de 15 secondes ;
- `GpxRecoveryRetryWorker` : lit le registre détaillé, traite les snapshots avec `GpxRecoveryWorkflow.recoverAndPublish()`, puis restitue un résumé et des compteurs via `WorkData`.

La dépendance `androidx.work:work-runtime:2.12.0` était déjà présente dans `app/build.gradle` ; aucune nouvelle dépendance n'est ajoutée.

Le worker est déclenché :

1. au retour de `MainActivity` au premier plan (`onStart`) ;
2. après le choix/changement du dossier SAF ;
3. par le bouton `RELANCER` ;
4. après le passage de l'intention GNSS de `true` à `false` observé par `MainActivity`.

Le dernier point est important : si un worker a été lancé alors que le suivi GNSS était actif, il termine sans manipuler les récupérations. Lorsque le service annonce ensuite l'arrêt du suivi, l'Activity programme une nouvelle tentative. Si l'Activity était en arrière-plan pendant cet arrêt, le `onStart` suivant relance également le travail.

Le worker vérifie `TrackingState.isUserWantsTracking()` avant le parcours et avant chaque récupération. Il ne fait aucun accès GNSS, ne modifie pas `ioFile` et appelle uniquement le workflow de récupération/publication déjà existant, sur le thread de travail WorkManager.

## 3. Politique de reprise

Nouveau modèle pur : `GpxRecoveryRetryPolicy`.

Décisions :

- `COMPLETE` : `PUBLISHED` ou `LOCAL_ONLY` ;
- `WAIT_FOR_USER` : `PENDING_PERMISSION` ou `NO_PERMISSION` détecté pendant la publication ;
- `RETRY_AUTOMATICALLY` : erreurs I/O/transfert transitoires et acquittement à retenter ;
- `NEEDS_INTERVENTION` : registre SAF illisible, source invalide/modifiée, documents homonymes ambigus, conflit de publication ou préfixe GPX invalide.

Les retries WorkManager sont bornés à cinq exécutions automatiques par chaîne (`MAX_AUTOMATIC_ATTEMPTS = 5`). Une permission absente ne boucle pas : le worker termine en attente d'action utilisateur. Un conflit n'est pas réessayé aveuglément. Une transaction SAF présente sans entrée de récupération correspondante est signalée « orpheline / intervention nécessaire » et son état est conservé.

Un nouvel appui manuel crée une nouvelle chaîne unique après la fin de la précédente ; le retry automatique n'est jamais infini.

## 4. Diagnostics des registres

`SessionStore.readRecoveriesDetailed()` expose déjà :

- `registryReadable` / `registryPresent` ;
- `journalReadable` / `journalPresent` ;
- les snapshots disponibles ;
- un message de diagnostic.

R2C.3 corrige la lecture diagnostic quand le registre multi-sessions est corrompu : le journal courant est désormais lu indépendamment. Un `recovery.registry` illisible ne signifie donc pas automatiquement que `session.state` est lui aussi illisible. La méthode signale aussi une écriture de synchronisation impossible (par exemple capacité atteinte ou stockage en erreur) par un message non vide. Le worker ne continue pas en traitant l'état comme vide lorsqu'un tel message existe.

L'interface distingue maintenant registre illisible, journal illisible et état non synchronisé. Les fichiers et journaux restent préservés dans tous ces cas.

## 5. Interface utilisateur

`activity_main.xml` reçoit un bandeau compact contenant :

- le nombre de récupérations restantes ;
- le nombre de transactions SAF restantes ;
- un résumé du dernier passage (publiées, copies privées, permissions, interventions) ;
- les diagnostics éventuels ;
- le bouton `RELANCER`.

Le bouton est refusé pendant le suivi GNSS et reste désactivé lorsque le registre de récupération ou de publication est illisible. Le texte de statut est actualisé par observation du `WorkInfo` et par lecture des registres hors du thread UI. Les résultats du worker sont affichés sans annoncer une publication si la vérification de retour n'a pas réussi.

Le résumé conserve aussi les notices historiques non destructives. Un indicateur de notice est retourné dans `WorkData` afin qu'un message ne disparaisse pas parce que les compteurs de récupération sont à zéro.

## 6. Conservation et limites

R2C.3 **ne supprime aucun fichier récupéré automatiquement**. Les copies `_RECUPEREE` restent des éléments de sécurité locaux. La règle de rétention (durée, quotas, nombre de copies, comportement si l'utilisateur change de destination, suppression après publication SAF vérifiée) doit être conçue séparément et validée avant tout code de purge.

La reprise SAF est testée avec les fournisseurs simulés déjà présents dans le banc R2C.2. Elle n'est pas encore validée sur DocumentsUI/MediaProvider, fournisseur cloud, carte SD/MTP ou fournisseur tiers réel. La campagne téléphone/ADB demeure explicitement reportée (voir dossier de reprise MAJ11, sections 38.11 et 39.10).

Le worker actuel utilise une requête unique `OneTimeWorkRequest`, pas un `PeriodicWorkRequest`. Les points d'entrée sont les événements réels ci-dessus. L'utilisateur peut toujours provoquer un passage par `RELANCER`.

## 7. Fichiers modifiés / ajoutés

| Chemin | Action |
|---|---|
| `.github/workflows/android-build.yml` | ajout du contrôle `verify_gpx_recovery_retry.py` |
| `app/src/main/java/com/example/gpstest/GpxRecoveryRetryPolicy.java` | nouveau modèle pur de classification |
| `app/src/main/java/com/example/gpstest/GpxRecoveryRetryScheduler.java` | nouveau déclencheur WorkManager unique |
| `app/src/main/java/com/example/gpstest/GpxRecoveryRetryWorker.java` | nouveau worker persistant, retries bornés et résumé |
| `app/src/main/java/com/example/gpstest/GpxRecoveryWorkflow.java` | état `PUBLICATION_CONFLICT` et classification exposée |
| `app/src/main/java/com/example/gpstest/MainActivity.java` | bandeau, bouton, observation WorkInfo, relance sur arrêt GNSS et diagnostic |
| `app/src/main/java/com/example/gpstest/SessionStore.java` | diagnostic indépendant registre/journal et échec de synchronisation |
| `app/src/main/res/layout/activity_main.xml` | bandeau de récupération |
| `app/src/main/res/values/strings.xml` | chaînes de statut |
| `tests/GpxRecoveryRetryPolicyTest.java` | nouvelle suite de politique de reprise |
| `tests/SessionStoreTest.java` | assertions du diagnostic détaillé |
| `tools/verify_gpx_recovery.py` | adapté à l'orchestration Worker |
| `tools/verify_gpx_recovery_publication.py` | vérifie le statut via l'UI/Worker |
| `tools/verify_gpx_recovery_retry.py` | nouveau contrôle structurel R2C.3 |
| `tools/rsim/com/example/gpstest/R.java` | généré depuis les ressources XML |
| `tools/stubs/com/example/gpstest/R.java` | copie générée pour les tests JVM |
| `README.md` | état courant R2C.3 |
| `docs/LOT-8C-R2C3-REPRISE-AUTOMATIQUE-PUBLICATIONS-SAF-2026-11-25.md` | présente note technique |
| `docs/DOSSIER-REPRISE-ULTRA-EXHAUSTIF-2026-11-25-MAJ11.md` | dossier de passation mis à jour |
| `LISEZ-MOI-LOT-8C-R2C3.md` | instructions de remplacement |
| `GPS-Test-LOT-8C-R2C3-RESULTATS-TESTS.txt` | relevé reproductible des contrôles |

## 8. Résultats effectivement exécutés

| Contrôle | Résultat |
|---|---:|
| `python3 tools/run_jvm_tests.py` | **47 suites, 1 663 assertions, 0 échec** |
| `GpxRecoveryRetryPolicyTest` | **20 assertions** |
| `SessionStoreTest` | **101 assertions** |
| `verify_android_project.py` | **17 XML, 92 sources Java**, OK |
| `verify_offline_map_architecture.py` | **110 fichiers**, aucun moteur/provider raster, OK |
| `verify_gpx_async_open.py` | OK |
| `verify_gpx_checkpoint.py` | OK |
| `verify_gpx_recovery.py` | OK |
| `verify_gpx_recovery_publication.py` | OK |
| `verify_gpx_recovery_retry.py` | OK |
| `python3 -m py_compile tools/*.py` | OK |
| Analyse YAML du workflow | OK |

Aucune compilation Android n'a été lancée dans cette préparation : l'utilisateur a explicitement demandé que les compilations Android soient effectuées par lui-même. La compilation R2C.2 est confirmée par l'utilisateur ; la compilation R2C.3 doit être faite par l'utilisateur après remplacement. Les tests JVM et audits statiques ne sont pas présentés comme une compilation Android.

## 9. Installation

Utiliser l'archive `GPS-Test-LOT-8C-R2C3-FICHIERS-A-REMPLACER-2026-11-25.zip`, décompresser à la racine du projet local en conservant l'arborescence et accepter les remplacements. L'archive complète `GPS-Test-LOT-8C-R2C3-PROJET-COMPLET-2026-11-25.zip` est une copie autonome. Aucun `.patch` Git n'est requis. Aucun fichier GitHub n'a été modifié par l'assistant.

## 10. Suite logicielle recommandée

Prochain travail : concevoir avant de coder la politique de rétention des copies de récupération. Aucun fichier ne doit être supprimé avant d'avoir défini les invariants : copie locale vérifiée, publication SAF `VERIFIED`, snapshot de récupération acquitté, absence de transaction SAF ouverte, âge minimum, quota et comportement si le fournisseur SAF devient ensuite indisponible. La suppression devra passer par un registre transactionnel distinct et être couverte par tests de panne ; la copie d'origine interrompue ne sera pas supprimée automatiquement.

La campagne réelle Android / fournisseurs SAF / ADB reste reportée et sera menée plus tard, après installation de la candidate sur un téléphone.
