# LOT 8B — Première correction de compilation et revue des signatures Android

Date : 23 novembre 2026  
Base : `GPS-Test-lot-8A.zip`  
Statut : une erreur Java certaine corrigée ; contrôles statiques et JVM réussis ; compilation Gradle/Android toujours bloquée par l'environnement.

## 1. Défaut concret trouvé

La passe de diagnostic `javac` de l'archive 8A signalait une incompatibilité de type dans `MapsforgeMapRenderer.java` :

- `dp(float)` renvoie un `float` ;
- `MapZoomCalculator.fit(..., int paddingPx, ...)` attend un entier ;
- les paddings de 32 dp (cadrage initial) et 48 dp (cadrage des traces) étaient fournis directement.

Les deux appels utilisent maintenant `Math.round(dp(...))`. La conversion est explicite en pixels entiers et le calcul pur `MapZoomCalculator` conserve son contrat.

Le vérificateur statique `tools/verify_android_project.py` contrôle désormais ces deux appels pour éviter une régression.

## 2. Diagnostic des signatures Android et faux positifs de stubs

La compilation exploratoire de toutes les sources Java avec les stubs JVM n'est pas une compilation Android : il manque `android.jar`, les bibliothèques AndroidX et Mapsforge. Elle signalait aussi des erreurs sur des surcharges `LocationManager` qui existent réellement dans le SDK.

Vérification auprès de la référence Android officielle :

- `registerGnssStatusCallback(GnssStatus.Callback, Handler)` : API 24 ;
- `addNmeaListener(OnNmeaMessageListener, Handler)` : API 24 ;
- `requestLocationUpdates(String, LocationRequest, Executor, LocationListener)` : API 31.

Les stubs de `tools/stubs/android/location/LocationManager.java` ont été complétés pour représenter ces signatures réelles. Un stub `LocationRequest.Builder` API 31 et `Handler.getLooper()` ont été ajoutés. `LinearLayout.LayoutParams` possède maintenant les formes de constructeurs utilisées par l'interface.

Sources officielles :

- https://developer.android.com/reference/android/location/LocationManager#registerGnssStatusCallback(android.location.GnssStatus.Callback,android.os.Handler)
- https://developer.android.com/reference/android/location/LocationManager#addNmeaListener(android.location.OnNmeaMessageListener,android.os.Handler)
- https://developer.android.com/reference/android/location/LocationManager#requestLocationUpdates(java.lang.String,android.location.LocationRequest,java.util.concurrent.Executor,android.location.LocationListener)

Ces compléments rendent la compilation JVM exploratoire moins trompeuse ; ils ne remplacent pas le SDK officiel.

## 3. Vérifications après correction

| Contrôle | Résultat |
|---|---:|
| Suites JVM | 38 / 38 |
| Assertions | 1 288 réussies |
| Échec JVM | 0 |
| Ressources XML | 17 analysées, 0 erreur |
| Sources Java applicatives examinées par le vérificateur | 80 |
| Contrat cartographique sans tuiles | 98 fichiers analysés, OK |

Les tests ont été compilés ensemble, puis exécutés dans des processus JVM isolés en six workers. Tous les tests ont rendu leur bilan attendu et un code de sortie nul.

Une compilation exploratoire `javac` de l'ensemble des sources Android avec stubs incomplets reste non concluante : des références Android/AndroidX/Mapsforge et quelques méthodes de cycle de vie ne sont pas présentes dans ces stubs. Après correction du `float → int`, aucun autre défaut de conversion de type certain n'a été identifié dans cette passe. Cela ne permet pas d'affirmer que Gradle compile.

## 4. Compilation Android réelle : toujours bloquée

`bash tools/check_android_build_environment.sh` s'arrête avec :

`ERREUR : Gradle absent. Installer Gradle 8.13 (ou utiliser la CI GitHub fournie).`

Le workspace n'a ni Gradle 8.13, ni wrapper, ni Android SDK Platform 36, ni Build Tools 35.0.0. Le réseau n'est pas disponible depuis le conteneur pour installer ces composants. Aucun `assembleDebug`, Android Lint ou APK n'a été exécuté.

La CI GitHub de l'archive 8A reste la voie reproductible : elle installe JDK 17, Gradle 8.13, SDK 36/Build Tools 35.0.0, exécute les vérifications et lance `:app:assembleDebug :app:lintDebug`. Elle n'a pas été lancée depuis ce workspace.

## 5. Points d'audit encore ouverts

- La divergence entre `versionName "1.0"` dans Gradle et l'étiquette historique `v5.14` du README n'a pas été harmonisée : il faut fixer la version produit voulue, et le prochain `versionCode` doit rester monotone si des APK ont déjà été distribués.
- `android:allowBackup="true"` reste à examiner explicitement pour les données internes de localisation avant publication.
- Les tests de callbacks LocationManager, de réabonnement, de suspension/reprise et de cycle de vie nécessitent toujours un appareil ou un émulateur Android réel.
- Le moteur Mapsforge doit encore être compilé contre l'artefact 0.25.0 et essayé avec de vraies cartes `.map` ; les JVM tests ne chargent pas ce moteur natif Android.

## 6. Conclusion

LOT 8B corrige les deux appels de cadrage de `MapsforgeMapRenderer` qui transmettaient un `float` là où le calculateur attend un `int`, et améliore la fidélité des stubs JVM par rapport aux signatures Android utilisées. Les 38 suites et 1 288 assertions repassent.

La prochaine preuve nécessaire est toujours le résultat d'un véritable build Gradle + Android Lint via la CI ou Android Studio. LOT 8B n'est donc pas une validation Android finale.
