# LOT 8C-R2B.4 — Durcissement de l'acquittement et de l'intégrité de récupération

**Date :** 25 novembre 2026  
**Base :** projet complet GPS-Test R2B.3, compilé sans erreur par l'utilisateur  
**Nature :** durcissement ciblé de la récupération après interruption ; aucun changement de format du journal  
**GitHub :** aucun fichier distant modifié ; aucun commit/push effectué

## 1. Objectif

R2B.3 a livré une récupération non destructive à partir de `SessionJournal.safeLengthBytes`. La revue du flux de reprise a révélé deux risques résiduels :

1. l'acquittement effaçait le journal sur la seule égalité de `sessionId`, même si une révision plus récente de cette même session avait été écrite pendant la récupération ;
2. la détection d'une modification concurrente du fichier source reposait seulement sur `length()` et `lastModified()`, qui peuvent rester identiques après une réécriture de même taille dans la granularité temporelle du système de fichiers.

R2B.4 ferme ces deux fenêtres sans modifier la règle centrale : l'original GPX n'est jamais ouvert en écriture par le récupérateur.

## 2. Changements de code

### 2.1 Acquittement strict du snapshot récupéré

Dans `MainActivity` et dans le chemin de secours de `TrackingService`, les appels de récupération utilisent désormais :

```java
SessionStore.clearIfUnchanged(context, previous);
```

au lieu de `clearIfSameSession(context, previous.sessionId)`.

La suppression ne réussit que si le journal courant correspond encore au snapshot exact lu avant la récupération : identifiant de session, révision et `savedAtMs`. Une nouvelle écriture/checkpoint de la même session fait échouer l'acquittement retardé ; le journal plus récent demeure présent.

Le `clearIfSameSession()` générique utilisé ailleurs dans le cycle de vie (par exemple l'effacement d'une session explicitement terminée) n'est pas modifié.

### 2.2 Vérification du contenu du préfixe source

`GpxRecovery` compare maintenant, juste avant la publication, le SHA-256 des octets `[0, safeLengthBytes)` de la source et du temporaire. La comparaison est effectuée en flux avec un tampon borné ; aucun fichier GPX n'est chargé entièrement en mémoire.

Cette vérification complète le contrôle préexistant `length()` / `lastModified()`. Elle détecte une modification du contenu même si le fichier garde la même taille et le même horodatage. Si le contenu n'est pas identique, le résultat est `SOURCE_CHANGED`, le temporaire est écarté et l'original reste intact.

### 2.3 Validation du nom de source

`safeName()` rejette désormais les espaces de bord plutôt que de valider une version `trim()` puis d'utiliser le nom original. Cela évite une normalisation implicite entre le contrôle et l'ouverture du fichier.

## 3. Tests ajoutés

### `GpxRecoveryTest`

Nouveaux cas :

- checkpoint qui coupe un caractère UTF-8 multioctet ; rejet sans toucher à la source ;
- document XML GPX sans structure `trk/trkseg` ; aucune copie vide publiée ;
- comparaison SHA-256 de préfixes de même taille et de même date, avec une réécriture de contenu ;
- six appels concurrents de récupération pour une même source ; une seule copie finale, même nom retourné à tous les appelants, aucun temporaire orphelin et source intacte ;
- nom avec espaces de bord rejeté.

### `SessionStoreTest`

Nouveau cas de concurrence logique : un snapshot de récupération est lu, un checkpoint plus récent est écrit pour le même `sessionId`, puis l'ancien snapshot tente l'acquittement. `clearIfUnchanged()` refuse la suppression et préserve le nouvel offset. L'acquittement du snapshot courant exact réussit.

### Vérificateur statique

`tools/verify_gpx_recovery.py` exige désormais le digest du préfixe, les acquittements stricts dans Activity/Service et les nouveaux tests de concurrence/UTF-8/révision.

## 4. Fichiers modifiés

| Chemin | Type | Rôle |
|---|---|---|
| `app/src/main/java/com/example/gpstest/GpxRecovery.java` | modifié | comparaison SHA-256 du préfixe source/copie ; validation du nom exact |
| `app/src/main/java/com/example/gpstest/MainActivity.java` | modifié | acquittement du snapshot exact |
| `app/src/main/java/com/example/gpstest/TrackingService.java` | modifié | acquittement du snapshot exact dans le repli de récupération |
| `tests/GpxRecoveryTest.java` | modifié | scénarios UTF-8, sans trace, digest, concurrence et nettoyage |
| `tests/SessionStoreTest.java` | modifié | checkpoint plus récent de la même session préservé |
| `tools/verify_gpx_recovery.py` | modifié | garde-fous statiques R2B.4 |
| `docs/LOT-8C-R2B4-SECURISATION-RECUPERATION-2026-11-25.md` | nouveau | présente note |
| `LISEZ-MOI-LOT-8C-R2B4.md` | nouveau | consignes de remplacement |
| `LOT-8C-R2B4-RESULTATS-TESTS.txt` | nouveau | résultats et limites des contrôles |

Aucun changement de `SessionJournal.FORMAT_VERSION`, `SessionStore` de production ou de la structure GPX écrite en acquisition. Le workflow existant lance déjà `verify_gpx_recovery.py` ; aucun fichier YAML n'a dû être modifié.

## 5. Résultats exécutés sur la candidate R2B.4

| Contrôle | Résultat |
|---|---|
| `python3 tools/run_jvm_tests.py` | **43 suites, 1 513 assertions, 0 échec** |
| `GpxRecoveryTest` | **44 assertions** |
| `SessionStoreTest` | **60 assertions** |
| `python3 tools/verify_gpx_recovery.py` | tous les invariants R2B.4 passent |
| `python3 tools/verify_gpx_checkpoint.py` | passe |
| `python3 tools/verify_gpx_async_open.py` | passe |
| `python3 tools/verify_android_project.py` | 17 XML et 83 sources Java ; cohérence statique passe |
| `python3 tools/verify_offline_map_architecture.py` | 101 fichiers ; aucun moteur/provider raster détecté |
| `python3 -m py_compile tools/verify_gpx_recovery.py` | passe |
| Analyse YAML du workflow | valide ; jobs `android` et `gpx-xsd` présents |
| Reconstruction de l'archive de remplacement sur R2B.3 | **réussie** : 43 suites / 1 513 assertions et tous les audits repris |
| `:app:assembleDebug`, Android Lint, tests instrumentés / appareils | non exécutés dans l'environnement de préparation |

Les tests JVM compilent/exécutent le vrai `GpxRecovery`, `SessionStore` et leurs dépendances testables avec les stubs du dépôt. Ils ne remplacent pas la compilation Android complète de `MainActivity` / `TrackingService` par AGP.

La reconstruction a été réalisée à partir d’une extraction propre du projet complet R2B.3, puis en appliquant l’archive R2B.4 « fichiers à remplacer ». Les résultats du tableau ont été reproduits sur cette reconstruction, et non seulement sur la copie de travail initiale.

## 6. Limites inchangées

- Le moteur contrôle XML bien formé, namespace/version et présence de `trk/trkseg`, mais ne réalise pas de validation XSD intégrée.
- La copie récupérée est créée dans le dossier app-specific ; elle n'est pas encore publiée automatiquement via SAF.
- Le répertoire parent n'est pas explicitement synchronisé après renommage ; la source reste le filet de sécurité.
- Le récupérateur ne restaure que le fichier/segment courant référencé par le journal unique ; aucun registre multi-sessions n'est ajouté.
- Les tests concurrents sont intra-processus ; aucun protocole inter-processus n'est revendiqué.
- L'absence de checkpoint, fichier absent, source raccourcie ou préfixe invalide reste un échec conservateur, pas une réparation approximative.
- R2B.4 doit être compilé dans Android Studio. La compilation utilisateur confirmée est R2B.3.

## 7. Procédure utilisateur

Décompresser l'archive `GPS-Test-LOT-8C-R2B4-FICHIERS-A-REMPLACER-2026-11-25.zip` à la racine du dépôt local, en conservant les chemins et en acceptant les remplacements. Aucun patch Git n'est requis. Ouvrir Android Studio et lancer Sync puis `:app:assembleDebug`.

**Ne pas pousser ni modifier GitHub automatiquement.** L'utilisateur reste maître du dépôt et effectue lui-même les remplacements locaux/dépôts.

## 8. Étape suivante proposée

Après compilation R2B.4, poursuivre la campagne de validation réelle sur Android : démarrer une trace, attendre un checkpoint, tuer le processus sans `onDestroy`, redémarrer et examiner l'original et la copie. Ajouter ensuite les scénarios stockage absent/saturé, fichier plus court que le checkpoint, et démarrage rapide d'une nouvelle session pendant la récupération. La publication SAF et le registre multi-sessions restent des sous-lots distincts.
