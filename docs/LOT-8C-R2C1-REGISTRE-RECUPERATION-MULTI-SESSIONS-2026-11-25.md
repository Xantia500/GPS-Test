# LOT 8C-R2C.1 — Registre durable multi-sessions et multi-segments

**Date :** 25 novembre 2026  
**Base :** projet complet LOT 8C-R2B.4, compilation Android confirmée sans erreur par l'utilisateur  
**Statut de cette candidate :** tests JVM et audits statiques passés ; compilation Android R2C.1 à confirmer par l'utilisateur  
**GitHub :** aucun fichier modifié directement ; livraison locale destinée au remplacement manuel par l'utilisateur.

## 1. Problème traité

Jusqu'à R2B.4, `SessionStore` conservait un journal primaire unique (`session.state`) qui désignait le segment courant. Au démarrage d'une nouvelle session, ce journal était remplacé. Si une ancienne trace interrompue n'avait pas encore été récupérée, ou si la fermeture/checkpoint final d'un segment arrivait en retard dans les files `ioFile` / `ioMeta`, le nouveau démarrage pouvait faire perdre l'unique référence persistante vers cette trace.

Le problème concerne aussi plusieurs segments d'une même session : un segment peut être réservé alors que la fermeture ou le dernier checkpoint du précédent n'a pas encore achevé sa séquence asynchrone.

## 2. Conception ajoutée

Une classe Java pure `RecoveryRegistry` et un fichier persistant séparé `recovery.registry` ont été ajoutés. Le journal primaire `session.state` reste la représentation de l'état courant ; le registre conserve, de manière indépendante, les segments dont le checkpoint durable existe mais dont la récupération ou la fermeture complète n'a pas encore été acquittée.

Une entrée est identifiée par le couple :

- `sessionId` (UUID de session logique) ;
- `segmentGeneration` (génération du segment dans la session).

La combinaison est complétée et vérifiée par le nom de fichier. Plusieurs générations d'une même session ainsi que plusieurs sessions peuvent donc coexister.

Le format du registre est versionné (`registryFormat=1`). Chaque snapshot `SessionJournal` est sérialisé en UTF-8 puis représenté en hexadécimal sur une ligne `entry=`, ce qui évite que les retours à la ligne du journal imbriqué perturbent le format extérieur. Le fichier est écrit par `AtomicFile`, avec `flush()` puis `FileDescriptor.sync()` avant `finishWrite()`.

Limites volontairement strictes :

- au plus 64 entrées ;
- texte total borné à 512 Kio ;
- chaque entrée encodée bornée à 32 Kio ;
- aucune éviction automatique ;
- toute corruption ou dépassement de capacité bloque la mutation plutôt que d'effacer silencieusement une ancienne récupération.

Le parseur rejette les versions inconnues, les comptes incohérents, les doublons `(sessionId, génération)`, les lignes supplémentaires, les séparateurs non canoniques, les caractères hexadécimaux non ASCII et les séquences UTF-8 mal formées. Un registre illisible est conservé pour diagnostic ; il n'est pas remplacé par un registre vide.

## 3. Éligibilité et cycle de vie d'une entrée

Une entrée est enregistrable lorsqu'elle représente une interruption potentiellement récupérable : `dirty=true`, `expectedAlive=true`, `recording=true`, `suspended=false`, fichier local présent dans le snapshot et `safeLengthBytes > 0`.

Le checkpoint n'est jamais inventé : l'appel `updateCheckpoint()` n'est effectué par le service qu'après `BufferedWriter.flush()`, `FileDescriptor.sync()` et mesure de la longueur du fichier correspondant.

Cycle :

1. `SessionStore.write()` inscrit d'abord dans le registre le snapshot récupérable courant avant qu'un nouveau `sessionId` ne remplace `session.state`.
2. Les écritures de snapshots d'un segment actif actualisent le registre avant le journal primaire.
3. `registerSegment()` associe le fichier à sa génération dans le journal courant.
4. Un checkpoint retardé d'un ancien segment est routé par `(sessionId, génération, nom)` vers son entrée du registre, sans pouvoir écraser le segment courant.
5. Si l'ouverture asynchrone d'un ancien segment ne publie son premier checkpoint qu'après un nouveau START, `updateCheckpoint()` peut créer une entrée détachée à partir du nom/génération exacts, mais seulement après synchronisation du fichier.
6. À la fermeture, `TrackingService` ne demande le retrait de l'entrée qu'après écriture/synchronisation du pied GPX et seulement si la longueur confirmée est exactement égale à `File.length()`.
7. La récupération au démarrage parcourt le registre depuis `MainActivity` ou le repli de `TrackingService`. Chaque acquittement retire le snapshot exact et ne touche pas aux autres sessions/segments.

## 4. Acquittement strict

La comparaison `RecoveryRegistry.sameSnapshot()` compare maintenant la sérialisation canonique complète `SessionJournal.toText()`, et pas seulement l'identifiant, le fichier et l'offset.

Un cas important est couvert : une récupération peut lire un snapshot, puis un champ du journal courant peut changer pendant la copie (notice, état `recording`, compteurs, autre checkpoint). Même si l'offset est encore identique, le journal courant n'est supprimé que si son snapshot complet est encore identique. Une récupération idempotente répétée est préférable à l'effacement d'un état plus récent.

Si l'entrée du registre a été acquittée mais que le journal primaire a évolué, celui-ci est conservé. Un prochain scan peut réinscrire le snapshot courant ; `GpxRecovery` réutilise alors la copie identique au lieu de dupliquer les données.

## 5. Modifications du code

| Chemin | Action | Rôle |
|---|---|---|
| `app/src/main/java/com/example/gpstest/RecoveryRegistry.java` | nouveau | modèle pur, sérialisation, limites, parseur strict, monotonie et opérations segmentées |
| `app/src/main/java/com/example/gpstest/SessionStore.java` | modifié | stockage `AtomicFile` du registre, migration/upsert, routage des checkpoints retardés et acquittement exact |
| `app/src/main/java/com/example/gpstest/TrackingService.java` | modifié | préservation des anciens segments/checkpoints asynchrones, retrait uniquement après fermeture durable |
| `app/src/main/java/com/example/gpstest/MainActivity.java` | modifié | récupération de toutes les entrées de registre au démarrage |
| `tests/RecoveryRegistryTest.java` | nouveau | tests purs des sessions/segments multiples, monotonie, parsing et limite |
| `tests/SessionStoreTest.java` | modifié | scénarios d'intégration multi-sessions, segments retardés, corruption et acquittement concurrent |
| `tools/verify_gpx_recovery.py` | modifié | invariants structurels du registre, du routage et de l'acquittement exact |
| `tools/verify_gpx_checkpoint.py` | modifié | vérification du checkpoint avec callbacks d'anciens segments |
| `docs/LOT-8C-R2C1-REGISTRE-RECUPERATION-MULTI-SESSIONS-2026-11-25.md` | nouveau | présente note |
| `LISEZ-MOI-LOT-8C-R2C1.md` | nouveau | instructions d'installation |

Aucun changement de manifeste, permission, format `SessionJournal` (qui reste format 5), écriture GPX, GNSS, XSD ou workflow GitHub Actions n'est requis. Le workflow Android exécute déjà les scripts statiques et le runner JVM ; les deux scripts mis à jour sont ainsi contrôlés lors de la CI.

## 6. Tests et résultats exécutés

Commandes lancées sur la candidate locale :

- `python3 tools/run_jvm_tests.py` : **44 suites, 1 581 assertions, 0 échec** ;
- `python3 tools/verify_gpx_recovery.py` : tous les invariants passent ;
- `python3 tools/verify_gpx_checkpoint.py` : tous les invariants passent ;
- `python3 tools/verify_gpx_async_open.py` : tous les invariants passent ;
- `python3 tools/verify_android_project.py` : **17 XML, 84 sources Java**, ressources/manifeste/permissions/FGS/SDK vérifiés ;
- `python3 tools/verify_offline_map_architecture.py` : **102 fichiers**, aucun moteur/provider raster ;
- `python3 -m py_compile tools/verify_gpx_recovery.py tools/verify_gpx_checkpoint.py` : OK.

Répartition notable : `RecoveryRegistryTest` 33 assertions ; `SessionStoreTest` 95 assertions. Les tests couvrent trois segments répartis sur deux sessions, un premier checkpoint retardé après nouveau START, une ancienne génération mise à jour après passage à un nouveau segment, acquittement indépendant, capacité 64 sans éviction, corruption de registre préservée, parsing UTF-8/hex strict et mutation du journal à offset identique.

Ces contrôles sont des tests JVM et audits statiques. Ils ne constituent **ni une compilation Android de R2C.1, ni Android Lint, ni un essai sur appareil réel**. La compilation Android R2B.4 est confirmée par l'utilisateur ; celle de cette candidate R2C.1 reste à confirmer après remplacement.

## 7. Limites connues et précautions

- La limite de 64 entrées ne supprime jamais une récupération. Si elle est atteinte, une opération de persistance peut échouer ; le journal existant est conservé. Il faut surveiller les avertissements de durabilité et prévoir une expérience utilisateur de gestion/diagnostic si un tel cas se produit.
- Un registre corrompu est préservé et bloque les écritures destructives. La lecture de la liste retourne alors vide ; le fallback du journal primaire reste possible, mais les entrées historiques que le registre corrompu contenait ne sont pas interprétables. Il ne faut ni effacer le fichier corrompu ni le remplacer automatiquement.
- Le registre est protégé par un verrou statique dans le processus et `AtomicFile`. La coordination inter-processus n'est pas revendiquée ; vérifier le `android:process` avant toute évolution multi-processus.
- Le récupérateur crée toujours la copie dans le répertoire privé app-specific de traces. La publication vers le dossier SAF choisi et l'état transactionnel de publication restent à concevoir.
- Les essais de panne avec `adb` et appareil physique sont explicitement reportés à une étape ultérieure (voir section 37.11 du dossier de reprise).

## 8. Installation

Utiliser `GPS-Test-LOT-8C-R2C1-FICHIERS-A-REMPLACER-2026-11-25.zip`, extraire à la racine du projet local et accepter les remplacements. Garder l'arborescence incluse dans le ZIP. Aucun patch Git à appliquer. Ne pas copier seulement `RecoveryRegistry.java` : il faut aussi les changements coordonnés dans le service, le store, l'activité, les tests et les outils.

Le projet complet `GPS-Test-LOT-8C-R2C1-PROJET-COMPLET-2026-11-25.zip` est fourni comme référence autonome.

Après installation, lancer Sync/Build Android Studio. La confirmation d'absence d'erreur sera considérée comme validation de compilation de cette livraison. Ne pas modifier GitHub depuis l'assistant ; l'utilisateur reste seul à reporter les fichiers dans son dépôt.

## 9. Suite recommandée

Prochain lot logiciel proposé : **R2C.2 — publication SAF transactionnelle des copies récupérées**. Objectif : rendre la copie récupérée visible dans le dossier choisi par l'utilisateur, avec états explicites (copie interne prête, destination SAF créée, octets vérifiés, publication acquittée), vérification SHA-256 et reprise idempotente après interruption. Le registre ne devra être acquitté qu'au moment de la garantie précisément choisie (copie interne durable ou publication SAF validée) ; cette règle doit être décidée avant implémentation.

Ensuite, ajouter une interface de gestion des récupérations échouées/corrompues et de la capacité du registre, puis effectuer la campagne réelle Android/ADB reportée.
