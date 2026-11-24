# LOT 8B — Correctif Android Lint et fins de ligne (24 novembre 2026)

## Diagnostic du journal GitHub

- `:app:assembleDebug` a réussi : l'APK Debug est bien construit.
- `:app:lintDebug` échoue ; 7 erreurs et 210 avertissements sont signalés.
- La première erreur visible concerne `GnssCapabilities.hasMeasurements()`, méthode disponible depuis Android API 31. Le code ne la protégeait que par `Build.VERSION_CODES.R` (API 30).

## Correction

Le contrôle de capacité est désormais exécuté uniquement sous `Build.VERSION_CODES.S` (API 31+). Sous API 24 à 30, le support reste inconnu et l'application tente directement l'inscription au callback, dont le succès/échec demeure l'autorité. Aucun relèvement de `minSdk` ou `targetSdk` n'est effectué.

## Politique SDK conservée

- `minSdk 24` (Android 7) conservé.
- `targetSdk 36` conservé : il satisfait actuellement la règle Google Play applicable depuis le 31 août 2026 (API 36 ou supérieure pour les nouvelles applications et mises à jour).
- `compileSdk 37.2` conservé pour compiler contre les API et répondre aux métadonnées AAR.
- AGP 9.5.2, Gradle 9.9.2, JDK 17 et Build Tools 36.0.0 conservés.

## Fins de ligne

Ajout de `.gitattributes` (`* text=auto eol=lf`) et `.editorconfig` (`end_of_line = lf`) pour empêcher la conversion accidentelle des fichiers texte en CRLF. Les fichiers binaires sont explicitement déclarés `binary`.

## Diagnostics CI

Le workflow sépare désormais `assembleDebug` et `lintDebug`. L'APK Debug et les rapports Lint HTML/SARIF/TXT sont téléversés avec `if: always()`, même si Lint échoue, afin que les 7 erreurs complètes puissent être examinées sans perdre l'APK construit.

## Validation locale disponible

Les contrôles statiques et tests JVM doivent être rejoués. La compilation AGP/Lint complète devra être confirmée par GitHub Actions ; l'environnement de préparation ne possède pas le SDK/AGP complet.
