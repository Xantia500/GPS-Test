# Mise à jour de l’outillage Android — 24 novembre 2026

## Décision de versions

| Composant | Version active | Statut / justification |
|---|---:|---|
| Gradle | 9.9.2 | Dernière version stable vérifiée, publiée le 19/11/2026. |
| Android Gradle Plugin | 9.5.2 | Version stable actuelle affichée dans la référence API Android. AGP 9.5.x exige Gradle 9.6.0 minimum, JDK 17 et Build Tools 36.0.0. |
| JDK | 17 | Minimum AGP 9.5 ; conservé pour une configuration reproductible. |
| Android Studio (si utilisé localement) | Rabbit 2 2026.2.2 Patch 2 | Canal stable vérifié le 20/11/2026 ; compatible AGP jusqu’à 9.5. |
| Android SDK Platform | 36 | Android 16 stable. |
| compileSdk / targetSdk | 36 / 36 | API 36 satisfait le minimum Google Play (API 36 depuis le 31/08/2026). API 37 / Android 17 est encore présentée comme bêta dans les mises à jour Android du 20/11/2026 ; ne pas l’utiliser comme cible de production à ce stade. |
| Android Build Tools | 36.0.0 | Version minimale par défaut d’AGP 9.5. |
| AppCompat | 1.8.0 | Stable vérifiée. |
| AndroidX Core | 1.19.1 | Stable vérifiée ; 1.20.0-alpha n’est pas retenue. |
| Material Components | 1.14.0 | Dernière version stable vérifiée. |
| WorkManager | 2.12.0 | Dernière version stable vérifiée ; son minSdk 24 correspond au projet. |
| Mapsforge Android / Themes | 0.25.0 | Conservé : version publiée sur Maven Central utilisée par le projet ; ne pas basculer vers une version upstream non publiée sur le dépôt Maven configuré. |

## Pourquoi API 36 reste la cible

Android 17 correspond à API 37, mais les pages Android consultées le 24/11/2026 le placent encore dans le canal bêta, alors que le canal stable annonce Android 16. En parallèle, la règle Google Play en vigueur depuis le 31/08/2026 impose API 36 ou supérieure aux nouvelles applications et mises à jour ordinaires. API 36 reste donc la cible stable et conforme ; API 37 sera réévaluée après sa disponibilité stable et une campagne de compatibilité.

## Changements appliqués

- `build.gradle` racine : AGP 8.13.2 → 9.5.2.
- `.github/workflows/android-build.yml` : Gradle 8.13 → 9.9.2.
- CI SDK : Build Tools 35.0.0 → 36.0.0 ; platform android-36 conservée.
- `app/build.gradle` : AppCompat 1.7.0 → 1.8.0 ; AndroidX Core 1.17.0 → 1.19.1 ; Material 1.12.0 → 1.14.0.
- WorkManager 2.12.0 et Mapsforge 0.25.0 conservés après vérification.
- Outils `verify_android_project.py`, `check_android_build_environment.sh` et `generate_gradle_wrapper.sh` alignés sur les nouvelles versions.
- Documentation du dépôt actualisée.

## Compatibilité AGP 9

Le module applicatif est exclusivement Java/XML et son script Gradle ne contient pas de plugin Kotlin, de logique `buildSrc`, de convention plugin ou d'accès à l’ancienne Variant API. La migration utilise le nouveau DSL AGP par défaut. Cela reste à confirmer par la vraie compilation GitHub : AGP 9 est une montée de version majeure, et une vérification statique/JVM ne peut pas se substituer à `assembleDebug` et Android Lint.

## Vérifications réalisées dans cette passe

- `tools/verify_android_project.py` : 17 XML analysés et 80 sources Java contrôlées ; configuration AGP/SDK/dépendances épinglée cohérente.
- `tools/verify_offline_map_architecture.py` : 98 fichiers Android analysés ; aucun moteur de tuiles raster/osmdroid, repli local et sélecteur local présents.
- Tests JVM : 38 suites, 1 288 assertions, zéro échec. Le lanceur a été simplifié : chaque suite s'exécute dans son propre processus avec un délai maximal, puis un résumé compact est produit.
- YAML GitHub Actions parsé ; scripts shell contrôlés avec `bash -n`.

## Limites de cette livraison

- Le conteneur de travail ne dispose pas de Gradle ni du SDK Android : aucune compilation AGP 9.5.2 n’a été exécutée localement.
- Le résumé GitHub partagé précédemment montrait l’avertissement `Gradle 8.13 is out of date`, mais pas la première erreur de build. Cet avertissement n’est pas, à lui seul, la cause du `Build Outcome: failure`.
- Après push, il faut examiner la première étape rouge du job `android`, en particulier la première erreur dans `Assemble debug APK and run Android Lint`. Les messages du cache Gradle et `Build Scan not published` sont des résumés, pas nécessairement la cause.
- Aucun APK n’est encore déclaré construit ou validé. La compilation AGP 9.5.2 et Android Lint doivent être exécutés par le prochain push GitHub Actions.

## Sources officielles consultées

- Gradle 9.9.2 (19/11/2026) : https://gradle.org/releases/
- Android Gradle Plugin API — version stable 9.5.2 : https://developer.android.com/reference/tools/gradle-api
- Notes AGP 9.5 — Gradle minimum 9.6.0, JDK 17, Build Tools 36.0.0, support API 37 : https://developer.android.com/build/releases/agp-9-5-0-release-notes
- Compatibilité AGP / Gradle : https://developer.android.com/build/releases/about-agp
- AppCompat : https://developer.android.com/jetpack/androidx/releases/appcompat
- AndroidX Core : https://developer.android.com/jetpack/androidx/releases/core
- Material Components : https://github.com/material-components/material-components-android/releases
- WorkManager : https://developer.android.com/jetpack/androidx/releases/work
- Mapsforge sur Maven Central : https://central.sonatype.com/artifact/org.mapsforge/mapsforge-map-android/versions
- Dernières mises à jour Android (Android 17 bêta / Android 16 stable) : https://developer.android.com/latest-updates/
- Exigences Google Play sur target API : https://developer.android.com/google/play/requirements/target-sdk

## Correctif Java complémentaire — capture lambda

Le journal de compilation complémentaire a révélé une erreur dans `TrackingService.java` autour de `fullTracking` : cette variable locale est réaffectée pendant le décodage GNSS et ne peut donc pas être capturée par la lambda UI. La livraison combinée prépare maintenant `final String fullTrackingStatus` avant `ui.post()` et ne capture plus que cette chaîne immuable. Le vérificateur statique inclut un garde de non-régression dédié. Voir `LOT_8B_CORRECTIF_LAMBDA.md`.


## Correctif complémentaire — Android SDK Platform 37.2 (24/11/2026)

La première compilation réelle sur GitHub Actions a dépassé la compilation Java et échoue à `:app:checkDebugAarMetadata`. Le journal fourni indique que les dépendances résolues exigent un `compileSdk` au moins égal à **37.2**, alors que le module était encore configuré sur API 36. L'extrait transmis ne contient pas le nom précis de l'AAR fautif ; il serait donc hasardeux d'en désigner une.

La configuration est maintenant :

- `compileSdk 37` et `compileSdkMinor 2` (Android SDK Platform 37.2) ;
- `targetSdk 36` inchangé : aucune activation automatique des nouveaux comportements runtime d'Android 17 ;
- `minSdk 24` inchangé ;
- AGP 9.5.2 et Gradle 9.9.2 conservés ;
- Build Tools 36.0.0 conservés : l'erreur concerne le niveau de compilation du SDK, pas les outils de build.

Le workflow installe désormais `platforms;android-37.2`. Le contrôle statique et le script local vérifient aussi cette plateforme. AGP 9.5.2 dépasse le minimum AGP 9.4.0 documenté pour l'API 37.2 ; la propriété `compileSdkMinor` est présente dans la DSL Android Gradle Plugin.

La mise à jour est préparée mais doit encore être confirmée par une nouvelle exécution GitHub Actions ; aucune réussite `assembleDebug`/Lint n'est revendiquée à ce stade.
