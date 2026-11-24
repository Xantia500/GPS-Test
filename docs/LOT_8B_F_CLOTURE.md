# LOT 8B-F — clôture technique et reproductibilité

**Date de préparation : 24 novembre 2026**  
**Base source :** `GPS-Test-lot-8B-Android-37.2-Lint-AGC-corrige-2026-11-24.zip`  
**Périmètre :** fiabilisation du test du pipeline GNSS, préparation du Gradle Wrapper et mise à jour de l'état documentaire/CI.

## 1. Fiabilisation de `GnssEventPipelineTest`

Le test antérieur attendait un `CountDownLatch` décrémenté à l'intérieur du callback, puis lisait immédiatement `completedCount()`. Or le callback est exécuté dans l'enveloppe publiée par `GnssEventPipeline.post()` : l'incrément de `completed` intervient dans le bloc `finally`, après le retour du callback. Le thread de test pouvait donc se réveiller avant cet incrément.

Le même problème existait après les 80 événements du test FIFO : le latch du dernier callback était libéré avant la fin de son enveloppe.

Le test utilise désormais deux barrières FIFO :

- une première tâche témoin est ajoutée après les 80 événements ; au début de cette tâche, les blocs `finally` des 80 événements précédents sont terminés ;
- après le callback volontairement fautif, une seconde tâche témoin vérifie que `failedCount` et `completedCount` ont été mis à jour avant sa propre exécution.

Le code de production `GnssEventPipeline.java` n'est pas modifié. Les délais restent bornés à 5 secondes pour éviter qu'un défaut de pipeline ne bloque indéfiniment la suite.

## 2. Gradle Wrapper

L'archive source initiale ne contenait pas `gradlew`, `gradlew.bat` ni `gradle/wrapper/`. L'environnement utilisé pour préparer ce correctif ne dispose pas de Gradle 9.9.2 ; il n'était donc pas possible de générer honnêtement le Wrapper localement.

Le script `tools/check_android_build_environment.sh` préfère maintenant `bash gradlew` lorsque le Wrapper est présent ; il conserve le recours au Gradle système 9.9.2 pour les checkouts antérieurs.

Le workflow GitHub Actions est préparé pour :

1. installer Gradle 9.9.2 avec `gradle/actions/setup-gradle` ;
2. générer le Wrapper officiel avec la distribution `bin` 9.9.2 et son SHA-256 épinglé ;
3. vérifier son lancement avec `./gradlew --version` ;
4. exécuter `assembleDebug` et `lintDebug` via `./gradlew` ;
5. publier les quatre fichiers du Wrapper dans l'artefact temporaire `gradle-wrapper-bootstrap`.

Après le prochain run GitHub Actions, télécharger cet artefact et copier les chemins suivants à la racine du dépôt :

```text
gradlew
gradlew.bat
gradle/wrapper/gradle-wrapper.jar
gradle/wrapper/gradle-wrapper.properties
```

Committer ces fichiers. Dans une étape ultérieure, retirer le bootstrap de génération du workflow et supprimer l'installation explicite de Gradle si elle n'est plus nécessaire ; le workflow devra appeler directement le Wrapper versionné.

## 3. Rétention des artefacts CI

Les artefacts `gradle-wrapper-bootstrap`, `gps-test-debug-apk` et `android-lint-reports` ont une rétention explicite de 14 jours. Cela évite de conserver indéfiniment les APK et rapports intermédiaires.

Les uploads utilisent `if: always()` afin que les rapports soient récupérables même lorsque Lint échoue après la génération de l'APK.

## 4. README

Le README indiquait encore que la compilation Gradle n'avait jamais été réalisée et que la CI n'avait pas été exécutée. Ces phrases ont été remplacées par l'état vérifiable :

- `compileDebugJavaWithJavac` et `assembleDebug` ont réussi dans les journaux GitHub Actions communiqués ;
- après les corrections API 31 et API 33, le succès de Lint a été rapporté par l'utilisateur ;
- les identifiants du run final et son rapport post-correctifs restent à archiver ;
- la compilation Debug ne constitue pas une validation sur appareil ni une validation Release.

Le décalage entre le `v5.14` historique du README et `versionName = "1.0"` / `versionCode = 1` dans Gradle est signalé, sans changer arbitrairement l'identité/version de l'application.

## 5. Vérifications locales de cette préparation

À exécuter après modification :

```bash
python3 tools/verify_android_project.py
python3 tools/verify_offline_map_architecture.py
bash tools/run_jvm_tests.sh
```

La génération/validation du Wrapper officiel, `assembleDebug` et `lintDebug` doivent être confirmées par le prochain run GitHub Actions. Aucune compilation Android locale n'est revendiquée dans ce lot.

## 6. Hors périmètre

- aucun changement dans `TrackingService`, `GnssEventPipeline` de production ou le traitement GNSS ;
- aucune modification des règles GPX ou cartographiques ;
- aucune augmentation de `minSdk` ou `targetSdk` ;
- aucune baseline Lint créée ;
- aucune signature Release ou publication configurée.

## 7. Critères de clôture

- [x] Test FIFO synchronisé sans course sur `completedCount`.
- [x] Workflow préparé pour générer et vérifier Gradle Wrapper 9.9.2.
- [x] Artefact temporaire du Wrapper prévu pour récupération/commit.
- [x] Rétention des artefacts Debug/Lint fixée à 14 jours.
- [x] README mis à jour et incohérences historiques signalées.
- [x] Vérificateur d'environnement local préparé pour préférer le Wrapper versionné.
- [ ] Exécution GitHub Actions après ce correctif confirmée verte.
- [ ] Wrapper récupéré depuis l'artefact et committé dans `main`.
- [ ] Rapport Lint final archivé avec SHA et numéro de run.
- [ ] Validation de l'APK sur au moins un appareil Android réel.
