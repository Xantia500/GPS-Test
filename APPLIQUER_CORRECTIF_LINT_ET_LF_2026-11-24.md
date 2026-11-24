# Application du correctif Android Lint et fins de ligne

1. Décompresser cette archive à la racine du dépôt local `GPS-Test` (au même niveau que `app/`, `tools/` et `build.gradle`).
2. Accepter le remplacement des fichiers.
3. Ouvrir GitHub Desktop, vérifier les fichiers modifiés, puis créer un commit : `Fix API 31 lint guard and enforce LF line endings`.
4. Cliquer sur Push origin.
5. Dans GitHub Actions, ouvrir le nouvel enchaînement. Les étapes `Assemble debug APK` et `Run Android Lint` sont désormais séparées.
6. Même si Lint échoue, télécharger l'artefact `android-lint-reports`. Il contient les rapports HTML, SARIF et texte complets, pour identifier les autres erreurs de Lint.
7. L'artefact `gps-test-debug-apk` est également téléversé si `assembleDebug` a réussi, même si Lint échoue ensuite.

Le dépôt conserve `minSdk 24`, `targetSdk 36` et `compileSdk 37.2`. Ne change pas ces valeurs manuellement.
