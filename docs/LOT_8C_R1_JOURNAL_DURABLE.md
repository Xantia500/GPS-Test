# LOT 8C-R1 — Journal de session durable

**Date : 24 novembre 2026**  
**Base de travail : ETAPE_14 / dernière archive source fournie**  
**Statut : correctif JVM vérifié ; compilation Android et CI à confirmer après intégration dans `main`.**

## 1. Objectif

Rendre le journal de session résilient aux interruptions et aux courses entre l'activité, le service et les workers. Le journal doit rester une source d'état non destructive : le lire ou présenter une notice ne doit pas supprimer les informations nécessaires aux prochains lots de récupération GPX.

Ce lot ne restaure pas encore les octets GPX après crash. Le champ `safeLengthBytes` est introduit dans le format de journal, mais `TrackingService.persistSession()` écrit encore `0` jusqu'au LOT 8C-R2.

## 2. Changements réalisés

### 2.1 `SessionJournal` — format 4

Le modèle indépendant d'Android porte désormais :

- `sessionId` : UUID stable de la session logique, distinct des identifiants de segments ;
- `revision` : révision incrémentée lors des snapshots successifs du même identifiant ;
- `dirty` : indique qu'une session n'a pas été explicitement terminée ;
- `expectedAlive` et `suspended` : distinguent l'acquisition attendue d'une pause ;
- `safeLengthBytes` : emplacement du futur checkpoint GPX durable ;
- `noticeShown` : acquittement durable de la notice sans consommer le journal.

Les formats historiques 1 à 3 restent lisibles. Lorsqu'ils n'ont pas d'UUID, une identité déterministe `legacy:` est construite à partir du début, de la date de sauvegarde et du nom de fichier. Les formats futurs inconnus ou les booléens critiques invalides sont refusés plutôt que réinterprétés avec des valeurs par défaut.

Le nom de fichier conservé dans le journal est limité à un nom simple : les séparateurs `/` et `\\`, les chemins `.` / `..`, les retours de ligne et les caractères de contrôle sont rejetés. Le journal ne doit jamais devenir un chemin contrôlé par une donnée externe.

### 2.2 `SessionStore` — stockage privé et transactionnel

- Source de vérité déplacée vers `Context.getFilesDir()/session.state` (stockage interne privé), indépendante du volume où se trouvent les GPX.
- Remplacement des écritures texte directes par `android.util.AtomicFile` (`startWrite`, `finishWrite`, `failWrite`, `openRead`, `delete`).
- Lecture non destructive ; l'ancienne API `consume()` est conservée temporairement comme alias déprécié de `read()`.
- Migration de l'ancien `getExternalFilesDir(null)/session.state` vers le stockage interne seulement après commit atomique réussi.
- Un journal interne présent mais illisible ne masque pas silencieusement l'ancien journal externe et n'est pas supprimé par `read()`.
- Avant qu'un nouveau START remplace un journal interne illisible, son contenu est conservé dans `session.state.corrupt` (ou un suffixe libre). Si cette préservation échoue, le nouveau journal n'écrase pas l'ancien. Un arrêt explicite efface aussi ces copies de quarantaine privées.
- Les anciens journaux externes non migrés ne sont pas supprimés par une écriture sans rapport.
- Taille de lecture plafonnée à 16 Kio ; un fichier trop grand est considéré suspect et reste présent pour diagnostic.
- Les opérations du store sont protégées par un verrou statique de processus. Les écritures de session du service sont sérialisées par `ioMeta`.

`AtomicFile` protège le remplacement d'un fichier contre une écriture interrompue ; il ne constitue pas un verrou interprocessus. Le projet utilise un seul processus applicatif pour ce store. Toute future déclaration `android:process` devra conduire à une réévaluation de ce contrat.

### 2.3 `TrackingService` et `MainActivity`

- Un UUID est créé au début de chaque nouvelle session réellement démarrée.
- Les snapshots capturent l'UUID avant d'être mis en file ; un snapshot retardé appartenant à une ancienne session est ignoré.
- Le `dirty` persiste jusqu'à l'arrêt explicite qui efface le journal. Il ne dépend plus de `state.isUserWantsTracking()` : le retrait runtime de la permission met ce dernier à `false`, mais ne signifie pas que l'utilisateur a terminé sa session.
- Les suppressions du service sont conditionnées par l'UUID attendu.
- `MainActivity` lit le journal sans le consommer.
- La notice est acquittée après son affichage et le journal reste disponible pour R2.
- Une écriture ultérieure de la même session conserve `noticeShown` et incrémente la révision.

## 3. Scénarios testés

`SessionStoreTest` vérifie notamment :

1. écriture/lecture dans le stockage interne ;
2. restauration d'un backup après interruption d'une transaction AtomicFile simulée ;
3. traitement d'un `.new` orphelin après première écriture interrompue ;
4. migration d'un journal historique externe ;
5. lecture répétée non destructive ;
6. acquittement de notice sans effacement ;
7. maintien de l'acquittement lors des snapshots suivants ;
8. refus d'un ancien clear lorsque l'identité a changé ;
9. refus d'un format futur inconnu ;
10. conservation/quarantaine d'un journal interne illisible avant nouveau START ;
11. préservation d'une source legacy quand l'interne est illisible ;
12. rejet et conservation d'un journal de plus de 16 Kio.

`SessionJournalTest` couvre le format 4, le round-trip, la compatibilité des formats 1–3, les booléens, l'identité legacy, le checkpoint, les états pause/interruption et le rejet des noms de chemins.

## 4. Résultats de vérification locale

| Contrôle | Résultat |
|---|---:|
| Suites JVM | 39 réussies |
| Assertions JVM | 1 344 réussies |
| Échecs | 0 |
| Vérification Android statique | 17 XML, 80 sources Java ; OK |
| Architecture cartographique hors ligne | 98 fichiers ; aucun moteur/fournisseur raster détecté |
| Workflow GitHub Actions | YAML valide |

Les tests s'exécutent avec des stubs JVM. Le stub `android.util.AtomicFile` vérifie le contrat simulé de transaction et de restauration ; il ne remplace pas la compilation contre le véritable SDK Android. Gradle, le SDK Android et `adb` ne sont pas disponibles dans l'environnement de préparation. Aucune réussite `assembleDebug` ou `lintDebug` n'est revendiquée pour ce lot.

## 5. Limites connues — à traiter en R2/R3

- `TrackingService.persistSession()` inscrit encore `safeLengthBytes = 0` : aucun offset réellement confirmé par le writer n'est encore stocké.
- `GpxCrashRecovery` n'a pas encore été réintroduit. Un GPX tronqué après mort brutale du processus n'est donc pas réparé par ce lot.
- Il faut coordonner les checkpoints avec `ioFile` : un checkpoint ne doit être publié qu'après les points précédents, `flush()` et `FileDescriptor.sync()` du segment concerné.
- La notice d'interruption ne valide pas à elle seule le XML du GPX. Elle ne promet plus que le fichier est lisible.
- Un nouveau suivi peut remplacer le journal de la session précédente ; R2 devra introduire un manifeste/répertoire de récupérations en attente afin de ne jamais perdre la référence à un GPX interrompu lorsque l'utilisateur démarre une nouvelle session.
- Les travaux post-traitement ne sont pas encore durables via WorkManager ; c'est le LOT 8C-R3.
- Les essais par `kill -9`, arrêt système, stockage saturé, SAF révoqué et restauration sur appareils Android restent à réaliser.

## 6. Critères de sortie du LOT 8C-R1

- [x] journal privé interne ;
- [x] écriture transactionnelle AtomicFile ;
- [x] migration legacy après commit ;
- [x] lecture et acquittement non destructifs ;
- [x] identité de session et suppression conditionnelle ;
- [x] protection permission révoquée / session non terminée ;
- [x] protection de journal illisible et limite de taille ;
- [x] tests JVM et contrôles statiques ;
- [ ] compilation Android réelle sur GitHub Actions ;
- [ ] Android Lint du commit R1 ;
- [ ] essai sur appareil.

## 7. Étape suivante

LOT 8C-R2 : introduire une file de checkpoints GPX liée à l'identité du segment, publier un offset uniquement après synchronisation du fichier, stocker un manifeste des traces à récupérer, puis réparer de façon idempotente une copie de la trace sans écraser l'original ni inventer de points.
