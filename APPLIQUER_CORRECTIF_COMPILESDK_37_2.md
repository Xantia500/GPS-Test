# Appliquer le correctif Android SDK 37.2

Ce correctif résout l'échec `:app:checkDebugAarMetadata` signalant que les bibliothèques AAR résolues demandent un compile SDK au moins égal à Android API 37.2.

## Modifications principales

- `app/build.gradle` : `compileSdk 37` + `compileSdkMinor 2`.
- `.github/workflows/android-build.yml` : la CI installe `platforms;android-37.2`.
- `tools/verify_android_project.py` : les contrôles statiques attendent le SDK 37.2.
- `tools/check_android_build_environment.sh` : le contrôle local recherche `platforms/android-37.2/android.jar`.
- `README.md` et documentation : description de la nouvelle configuration.

## Valeurs que nous gardons volontairement

- `targetSdk 36` : le passage du compile SDK à 37.2 ne cible pas Android 17 et ne demande pas à l'application d'adopter ses nouveaux comportements d'exécution.
- `minSdk 24` : les appareils pris en charge ne changent pas.
- AGP 9.5.2, Gradle 9.9.2, JDK 17 et Build Tools 36.0.0 restent épinglés.

Le journal partagé ne contenait pas l'identifiant de la dépendance AAR précise ; le correctif traite l'exigence de plateforme explicitement rapportée, sans deviner le nom de cette dépendance.

## Installation dans GitHub Desktop

1. Télécharger `CORRECTIF_COMPILESDK_37_2_2026-11-24.zip`.
2. Extraire son contenu directement dans le dossier local du dépôt `GPS-Test` (celui qui contient déjà `.git`, `app`, `tools` et `build.gradle`).
3. Accepter le remplacement des fichiers.
4. Vérifier les fichiers modifiés dans GitHub Desktop.
5. Créer le commit `Use Android SDK 37.2 for dependency AAR metadata`.
6. Cliquer sur **Push origin**.
7. Ouvrir GitHub Actions et examiner la nouvelle exécution sur `main`.

Les 38 suites JVM, 1 288 assertions, les vérifications XML, le YAML du workflow et les contrôles statiques passent dans la livraison préparée. La compilation Android complète doit être confirmée par la nouvelle exécution GitHub Actions.
