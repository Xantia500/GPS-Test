# LOT 8A — Audit global et préparation de la compilation Android

Date : 23 novembre 2026  
Base : `GPS-Test-lot-7G.zip`  
État : audit statique et JVM réalisés ; compilation Android complète non réalisable dans l'environnement fourni.

## 1. Configuration Gradle / Android vérifiée

La configuration suivante est cohérente :

| Élément | Valeur projet | Vérification |
|---|---:|---|
| Android Gradle Plugin | 8.13.2 | nécessite Gradle 8.13 et JDK 17 |
| Gradle recommandé | 8.13 | non installé dans l'environnement courant |
| JDK disponible ici | 21.0.12 | satisfait le minimum JDK 17 de l'AGP |
| `compileSdk` | 36 | dans la plage prise en charge par AGP 8.13 (jusqu'à API 36.1) |
| Build Tools | 35.0.0 minimum AGP | absent ici |
| `targetSdk` | 36 | cohérent avec l'objectif de cible API 36 |
| `minSdk` | 24 | cohérent avec les appels Android vérifiés et WorkManager 2.12.0 |
| Java source / bytecode | 17 | cohérent avec les exigences de l'AGP |
| WorkManager | 2.12.0 | la version publiée le 23 septembre 2026 a `minSdk` 24 |
| Mapsforge | 0.25.0 | version volontairement épinglée sur Maven Central |

Références de compatibilité vérifiées :

- Android Gradle Plugin 8.13 : Gradle minimum 8.13, JDK 17, Build Tools 35.0.0 et support jusqu'à API 36.1 : https://developer.android.com/build/releases/agp-8-13-0-release-notes
- Matrice Gradle / Android Gradle Plugin : https://developer.android.com/build/releases/about-agp
- WorkManager 2.12.0 : sortie du 23 septembre 2026 et `minSdk` passé à 24 : https://developer.android.com/jetpack/androidx/releases/work

Aucune mise à niveau de dépendance n'a été faite simplement pour prendre le numéro le plus récent. Les versions restent épinglées afin de rendre une compilation future reproductible. WorkManager 2.12.0 et `minSdk 24` concordent exactement.

## 2. Environnement de compilation disponible

Présent :

- OpenJDK 21.0.12 ;
- `javac` et `java` ;
- Python 3 ;
- les sources et les stubs de tests du projet.

Absent :

- exécutable Gradle ;
- Gradle Wrapper (`gradlew`, `gradle-wrapper.jar`, `gradle-wrapper.properties`) ;
- Android SDK ;
- `platforms/android-36/android.jar` ;
- Build Tools / AAPT2 ;
- `adb`.

L'environnement ne résout pas les noms DNS externes ; Gradle et le SDK ne peuvent donc pas être installés pendant cette passe. Il n'a pas été possible d'exécuter `:app:assembleDebug`, le merger de manifeste, AAPT2, Android Lint ou de générer un APK. Aucun résultat de compilation Android n'est revendiqué.

## 3. Contrôles réalisés

### 3.1 Tests JVM

Exécution du script complet `tools/run_jvm_tests.sh` :

- 38 suites ;
- 1 288 assertions réussies ;
- 0 échec ;
- compilation commune des sources de test puis exécution individuelle des 38 suites.

Ces tests couvrent les classes Java testables avec stubs et ne compilent pas les Activities, services et bibliothèques Android de la même manière qu'AGP.

### 3.2 Ressources et manifeste

`tools/verify_android_project.py` :

- 17 fichiers XML analysés ;
- 80 sources Java de l'application examinées pour les références `R.*` ;
- références simples aux ressources contrôlées ;
- composants du manifeste reliés aux sources présentes ;
- permissions de localisation et de foreground service contrôlées ;
- `foregroundServiceType="location"` contrôlé ;
- `compileSdk 36`, `targetSdk 36`, `minSdk 24`, Java 17 et dépendances épinglées contrôlés ;
- présence de `EdgeToEdge.apply(this)` dans les cinq activités déclarées ;
- gardes statiques ciblés pour certaines API 29, 31 et 34.

Limite : ce vérificateur est un contrôle statique maison. Il ne remplace pas AAPT2, le manifest merger ou Android Lint.

### 3.3 Compatibilité des callbacks GNSS (minSdk 24)

La passe API a mis en évidence une subtilité de `LocationListener` :
`onStatusChanged()`, `onProviderEnabled()` et `onProviderDisabled()` n'ont une
implémentation par défaut qu'à partir d'Android 11 (API 30). Le projet supportant
API 24 à 29, ces méthodes doivent être explicitement implémentées. L'implémentation
neutre de `onStatusChanged()` ne transforme pas le statut historique du provider en
mesure de qualité du fix GNSS.

Les callbacks enregistrés auprès de `LocationManager` sont désormais des wrappers
qui capturent un jeton de génération propre à leur canal (position, statut satellites,
NMEA). Le jeton est vérifié sous le verrou de traitement avant de déléguer l'événement.
Ainsi, un callback ancien déjà en file est ignoré après désabonnement ou changement
de fréquence, sans suspendre les flux Status/NMEA et le journal CSV brut lors d'une
simple modification de cadence. Les méthodes `onProviderEnabled()` et
`onProviderDisabled()` sont également explicitement implémentées pour API 24–29 ;
le wrapper relaie aussi les lots `onLocationChanged(List<Location>)` d'API 31+ vers
le traitement unitaire dans leur ordre chronologique.

Le worker JVM vérifie le contrat du jeton de génération ; un essai réel du framework
sur Android 7 à 16 reste nécessaire.

### 3.4 Contrat cartographique hors ligne

`tools/verify_offline_map_architecture.py` : aucune dépendance osmdroid, aucun `TileSourceFactory`, Mapnik ou `setUseDataConnection()` dans les sources actives ou la configuration Gradle ; le renderer de repli local et le sélecteur régional local sont présents.

### 3.5 Edge-to-Edge

Les cinq Activities déclarées appliquent le helper commun `EdgeToEdge` après `setContentView`. Le helper utilise `WindowCompat`, les insets système, les découpes d'écran et l'IME ; les appels plateforme de contraste des barres sont gardés par API 29.

Le rendu réel reste à contrôler sur plusieurs versions et tailles d'écran.

## 4. Outillage de compilation ajouté

### CI Android

`.github/workflows/android-build.yml` prépare un runner avec JDK 17, Gradle 8.13, Android SDK Platform 36 et Build Tools 35.0.0. Il exécute ensuite :

1. audit statique du projet ;
2. contrôle cartographique hors ligne ;
3. suites JVM ;
4. `gradle :app:assembleDebug :app:lintDebug` ;
5. publication de l'APK debug comme artefact si le build réussit.

Ce workflow est fourni mais n'a pas été exécuté ici : aucun résultat CI ne peut être revendiqué avant son lancement sur le dépôt GitHub.

### Vérification locale

Sur une machine disposant de JDK 17+, Gradle 8.13 et Android SDK Platform 36 / Build Tools 35.0.0 :

```bash
sdkmanager "platforms;android-36" "build-tools;35.0.0" "platform-tools"
bash tools/check_android_build_environment.sh
```

Le script vérifie les prérequis, relance les tests JVM et contrôles statiques, puis compile l'APK debug et lance Android Lint.

Si l'utilisateur souhaite ajouter les fichiers Gradle Wrapper au dépôt, une machine disposant de Gradle 8.13 peut exécuter :

```bash
bash tools/generate_gradle_wrapper.sh
```

Les fichiers du wrapper générés devront être versionnés ensemble : `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` et `gradle/wrapper/gradle-wrapper.properties`. Aucun wrapper incomplet n'a été ajouté à cette archive.

## 5. Observations de revue à conserver

1. Le `versionName` Gradle est actuellement `1.0`, tandis que le README historique porte l'indication `v5.14`. Cette divergence de libellé n'affecte pas la compilation ; elle devra être harmonisée au moment de fixer la version produit destinée aux appareils / à une publication.
2. Le manifeste garde `android:allowBackup="true"`. Les traces GPX/CSV sont exportées dans des emplacements app-specific externes, mais une revue de confidentialité des données internes et des règles Auto Backup / Device Transfer mérite un traitement explicite avant publication.
3. Le pipeline GNSS est sérialisé et protégé par un verrou de traitement. La validation du comportement des callbacks Android réels, des réabonnements, de la suspension et des callbacks tardifs exige encore des tests appareil ; le test JVM du pipeline ne simule pas le framework `LocationManager`.
4. Les contrôles API réalisés sont ciblés, non exhaustifs. Une compilation API 36 + Android Lint est indispensable pour repérer les références de méthodes, avertissements et incohérences que les stubs ne reproduisent pas.
5. Le `LocationListener` a été rendu compatible explicitement avec les API 24–29 via `onStatusChanged()`, `onProviderEnabled()` et `onProviderDisabled()`. Les wrappers à génération indépendante filtrent les callbacks obsolètes sur position, `GnssStatus` et NMEA. La logique de jeton est testée JVM, mais le séquencement des callbacks du framework reste à valider sur appareil.

## 6. Conclusion du LOT 8A

La configuration build est cohérente sur le papier, l'outillage de vérification est renforcé et le contrôle des callbacks GNSS a corrigé un risque de compatibilité concret avec les API 24–29. La suite JVM et les contrôles statiques passent. Le blocage restant est matériel/environnemental : le projet n'a pas encore été compilé par Android Gradle Plugin et aucun APK ne doit être considéré comme validé.

Le résultat de la CI Android ou d'une compilation Android Studio devient la prochaine preuve attendue avant d'entamer les correctifs de compilation du LOT 8B.
