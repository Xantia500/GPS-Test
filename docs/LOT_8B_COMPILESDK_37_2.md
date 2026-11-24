# LOT 8B — Correctif compileSdk Android 37.2

Date : 24 novembre 2026

## Diagnostic

La compilation réelle a franchi `compileDebugJavaWithJavac` et échoue ensuite à `:app:checkDebugAarMetadata`. Le log signale que le module compile contre `android-36` alors qu'une dépendance AAR résolue demande au minimum Android API 37.2. L'extrait partagé ne contient pas l'identifiant précis de cette dépendance ; nous ne l'attribuons donc pas sans preuve.

## Modifications

- `app/build.gradle` : `compileSdk 37` + `compileSdkMinor 2`.
- `.github/workflows/android-build.yml` : installation de `platforms;android-37.2`.
- `tools/verify_android_project.py` : vérification explicite de `compileSdk` et `compileSdkMinor`, plus contrôle du package SDK installé par CI.
- `tools/check_android_build_environment.sh` : contrôle de `platforms/android-37.2/android.jar`.
- `README.md` et rapport de migration : configuration active mise à jour.

## Valeurs volontairement inchangées

- `targetSdk 36` : le passage du compile SDK ne change pas à lui seul les comportements d'exécution auxquels l'application choisit de s'inscrire. Android 17 n'est pas ciblé ici.
- `minSdk 24` : compatibilité des appareils conservée.
- AGP 9.5.2, Gradle 9.9.2, JDK 17 et Build Tools 36.0.0 conservés.

## Validation

Contrôles statiques, tests JVM, XML et YAML exécutés dans cet environnement. La compilation AGP complète reste à rejouer sur GitHub Actions après push ; seule cette exécution pourra confirmer que tous les AAR metadata checks sont satisfaits, puis révéler d'éventuels diagnostics suivants.
