# GPS-Test — LOT 8C-R2B.3 : récupération contrôlée après interruption

**Date :** 24 novembre 2026  
**Base :** projet complet LOT 8C-R2B.2  
**Dépôt distant :** aucune modification GitHub effectuée  
**État :** candidat préparé et contrôlé localement ; compilation Android du lot à confirmer par l'utilisateur.

## 1. Objectif et portée

Le LOT 8C-R2B.2 persistait `SessionJournal.safeLengthBytes`, c'est-à-dire la longueur du préfixe GPX après `BufferedWriter.flush()` et retour réussi de `FileDescriptor.sync()`. Il ne savait pas encore restaurer une trace interrompue.

Le LOT 8C-R2B.3 ajoute cette récupération. Elle n'essaie pas de réparer la trace source en place. Elle fabrique un **fichier distinct** suffixé `_RECUPEREE`, exclusivement à partir du préfixe checkpointé. Le fichier d'origine n'est jamais ouvert en écriture, tronqué, renommé ou supprimé.

La récupération est déclenchée au prochain lancement visible de `MainActivity`, et possède aussi un chemin de secours asynchrone lorsque le service relit un journal précédent. Elle ne concerne que les sessions qualifiées par `SessionJournal.isUnexpectedRecordingInterruption()` : journal dirty, acquisition attendue, non suspendue, `recording=true`, nom de fichier non vide.

## 2. Contrat de sécurité

### 2.1 Source non destructive

- Le nom doit être un simple nom de fichier GPX (pas de `/`, `\\`, contrôle, `.` ou `..`).
- Le fichier canonique doit rester directement dans `getExternalFilesDir(null)`.
- Le fichier doit exister, être régulier et lisible.
- `safeLengthBytes` doit être strictement positif et inférieur ou égal à la taille réelle.
- Un fichier plus court que le checkpoint est refusé : aucune extension artificielle, aucune tentative de compléter les octets manquants.
- Seuls les octets `[0, safeLengthBytes)` sont copiés. Toute queue postérieure au checkpoint est ignorée.

### 2.2 Construction de la copie

1. Création d'un temporaire dans le même dossier que la source.
2. Copie exacte du préfixe, avec vérification de longueur et `FileDescriptor.sync()` du temporaire.
3. Essai de lecture du préfixe comme document GPX complet.
4. S'il n'est pas déjà complet, ajout à la copie uniquement du pied standard `GpxWriter.footer()` puis nouvelle validation.
5. Vérification que la taille et la date de modification de la source n'ont pas changé pendant l'opération.
6. Publication par renommage du temporaire vers un nom distinct `*_RECUPEREE.GPX`.

Si le préfixe contient déjà le pied (cas où le checkpoint final a été persisté avant la mort du processus), aucun pied n'est ajouté une deuxième fois.

### 2.3 Contrôle XML sécurisé

La copie est analysée par SAX avec :

- `FEATURE_SECURE_PROCESSING` activé ;
- `disallow-doctype-decl=true` ;
- entités générales et paramètres externes désactivés ;
- chargement des DTD externes désactivé ;
- un résolveur qui refuse les entités externes.

Le contrôle vérifie que le document est bien formé, que la racine est `gpx`, que le couple version/namespace attendu correspond (GPX 1.0 ou GPX 1.1), et que la structure contient au moins un `<trk>` avec un `<trkseg>` enfant. Ce contrôle n'est **pas** une validation XSD complète. Le job séparé GPX XSD de GitHub Actions demeure le contrôle de schéma.

### 2.4 Nommage, idempotence et collisions

- Nom principal : `nom_source_RECUPEREE.GPX` (casse de l'extension préservée).
- Si ce nom existe avec un contenu byte-à-byte identique, la copie existante est réutilisée.
- Si le nom existe avec un contenu différent, un suffixe `-2`, `-3`, etc. est utilisé.
- Aucun fichier existant n'est écrasé.
- Un résultat d'échec supprime le temporaire lorsque cela est possible et conserve la source.

## 3. Intégration dans le cycle de vie

### MainActivity

Au démarrage, un thread de fond lit `SessionStore`. Pour une interruption GPX, il tente la récupération même si `noticeShown=true` a été enregistré par une ancienne version. Cela permet aux sessions R2B.2 déjà signalées de bénéficier du nouveau mécanisme.

- Succès : le journal est effacé avec `SessionStore.clearIfSameSession()` et seulement si l'identifiant de session n'a pas changé ; l'interface affiche le nom de la copie et rappelle que l'original est conservé.
- Échec : le journal n'est pas acquitté ; un message expose le motif et le prochain lancement peut retenter.
- Les interruptions qui ne concernent pas un enregistrement GPX conservent le comportement historique de notice et d'acquittement.
- Si un nouvel enregistrement démarre pendant la tâche de récupération, le message ancien ne remplace pas l'affichage de la session active.

### TrackingService

`checkPreviousSession()` dispose d'un chemin de secours : si le journal lu décrit une interruption GPX, il démarre un thread `gpx-session-recovery`. La copie et l'analyse XML ne bloquent ni `ioMeta` (checkpoints de la session nouvelle), ni `ioFile` (FIFO des points GNSS), ni le thread principal.

Le nettoyage du journal utilise également `clearIfSameSession()`, empêchant une tâche de récupération retardée de supprimer le journal d'une nouvelle session.

Les deux chemins peuvent se déclencher pendant une course de démarrage ; `GpxRecovery` sérialise les opérations dans le processus et réutilise une copie identique existante. Le protocole ne dépend donc pas d'un unique déclencheur Activity.

## 4. Résultats possibles

`GpxRecovery.Status` expose :

- `RECOVERED` : copie publiée ou copie identique déjà présente ;
- `INVALID_ARGUMENT` : dossier ou nom non sûr ;
- `NO_CHECKPOINT` : offset absent ou nul ;
- `SOURCE_MISSING` : fichier absent, non régulier ou illisible ;
- `CHECKPOINT_BEYOND_EOF` : le fichier est plus court que l'offset journalisé ;
- `SOURCE_CHANGED` : taille/date source modifiée pendant la copie ;
- `INVALID_PREFIX` : préfixe qui ne devient pas un GPX complet après ajout éventuel du pied ;
- `IO_ERROR` : erreur de copie ou de publication.

Tout échec laisse la trace originale en place. Il n'y a aucune réparation partielle « best effort » d'un point tronqué ou d'un fragment XML invalide.

## 5. Fichiers modifiés ou ajoutés

| Chemin | Action | Rôle |
|---|---|---|
| `app/src/main/java/com/example/gpstest/GpxRecovery.java` | nouveau | moteur pur Java de copie du préfixe, finalisation GPX et contrôle XML sécurisé |
| `app/src/main/java/com/example/gpstest/MainActivity.java` | modifié | récupération automatique au lancement visible et message de résultat |
| `app/src/main/java/com/example/gpstest/TrackingService.java` | modifié | déclencheur de secours asynchrone et effacement conditionnel du journal |
| `tests/GpxRecoveryTest.java` | nouveau | tests GPX 1.0/1.1, suffixe, copie exacte, source intacte, offset invalide, mauvais namespace, XXE/DOCTYPE, point tronqué, répétition et collision |
| `tools/verify_gpx_recovery.py` | nouveau | invariants statiques de récupération et de workflow |
| `.github/workflows/android-build.yml` | modifié | exécution du contrôle statique R2B.3 dans le job Android |
| `docs/LOT-8C-R2B3-RECUPERATION-GPX-2026-11-24.md` | nouveau | présente note technique |
| `LISEZ-MOI-LOT-8C-R2B3.md` | nouveau | consignes de remplacement pour l'utilisateur |

Aucun changement de schéma du `SessionJournal` n'est requis : les formats et `safeLengthBytes` de R2B.2 sont conservés.

## 6. Contrôles réellement exécutés

Sur le candidat extrait de l'archive complète R2B.2 puis modifié localement :

| Contrôle | Résultat |
|---|---|
| `python3 tools/run_jvm_tests.py` | **43 suites, 1 490 assertions, 0 échec** |
| `GpxRecoveryTest` | 27 assertions passées |
| `python3 tools/verify_gpx_recovery.py` | tous les invariants structurels passés |
| `python3 tools/verify_gpx_checkpoint.py` | invariants R2B.2 passés |
| `python3 tools/verify_gpx_async_open.py` | invariants R2B.1 passés |
| `python3 tools/verify_android_project.py` | 17 XML et 83 sources Java ; ressources, manifeste, permissions, FGS, SDK et Edge-to-Edge cohérents |
| `python3 tools/verify_offline_map_architecture.py` | 101 fichiers ; aucun moteur/provider de tuiles raster |
| Analyse YAML PyYAML du workflow | jobs `android` et `gpx-xsd` reconnus |
| `tools/check_android_build_environment.sh` | **non exécutable ici** : Gradle absent et Wrapper non versionné |
| Compilation Android / Lint / tests instrumentés / appareils | non exécutés dans l'environnement de préparation |

Les tests JVM vérifient la classe `GpxRecovery` réelle et compilent son appel à `GpxWriter`. Ils ne remplacent pas une compilation complète de `MainActivity` et `TrackingService` via Android Gradle Plugin.

## 7. Limites restantes et points de vigilance

1. **Pas de validation XSD dans le récupérateur lui-même.** La copie est contrôlée comme XML bien formé avec racine/version/namespace attendus. La conformité XSD doit être vérifiée via le job GPX XSD indépendant.
2. **Préfixe mal formé : refus.** Si un checkpoint venait exceptionnellement à tomber au milieu d'une balise, valeur ou séquence UTF-8, l'ajout du pied ne peut pas reconstruire les données manquantes. L'original reste intact et aucune coordonnée n'est inventée.
3. **Perte bornée avant le checkpoint :** les points non synchronisés depuis le dernier checkpoint ne sont volontairement pas récupérés. Ils ne sont pas revendiqués comme durables.
4. **Fichier app-specific :** la copie est créée dans le dossier GPX app-specific utilisé par le writer local. Elle n'est pas automatiquement publiée vers un dossier SAF/public sélectionné ; la publication SAF de la trace interrompue relève d'un sous-lot distinct.
5. **Durabilité du renommage :** le contenu temporaire est synchronisé avant renommage. Le répertoire parent n'est pas explicitement fsync ; l'original est conservé comme filet de sécurité si la nouvelle entrée de répertoire ne survit pas à une panne matérielle.
6. **Appareils réels non testés :** il reste à simuler un kill du processus pendant la trace, un stockage saturé/démonté, un fichier source absent ou raccourci, puis contrôler le résultat dans plusieurs lecteurs GPX.
7. **Registre multi-sessions :** non ajouté. R2B.3 récupère seulement le fichier/segment courant explicitement pointé par le journal actif. La récupération de plusieurs segments ou sessions en attente doit faire l'objet d'une évolution dédiée.

## 8. Installation

Remplacer ou ajouter les fichiers de l'archive à la racine du dépôt local, en conservant exactement l'arborescence. Ne pas appliquer de patch Git. Aucun dépôt distant n'a été modifié par l'assistant.

Puis ouvrir Android Studio et lancer la compilation habituelle. Le candidat n'est pas déclaré compilé Android par les seuls tests JVM et audits statiques. Si la compilation est sans erreur, cette étape est validée pour la suite.

## 9. Prochaine étape suggérée

Après compilation R2B.3 et essais sur appareil, le travail suivant est une campagne de scénarios d'interruption réelle : process kill (sans `onDestroy`), arrêt forcé, crash pendant une écriture, stockage retiré, fichier plus court que checkpoint, fermeture déjà synchronisée et reprise pendant qu'un nouveau suivi démarre. On décidera ensuite s'il faut ajouter une action UI explicite de récupération, une publication SAF et un registre multi-segments.
