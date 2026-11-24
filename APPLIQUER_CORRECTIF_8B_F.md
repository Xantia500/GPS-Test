# Appliquer le correctif LOT 8B-F

1. Décompresser cette archive à la racine du dépôt local `GPS-Test` (le dossier qui contient `.git`).
2. Accepter le remplacement de `README.md`, `tests/GnssEventPipelineTest.java`, `tools/verify_android_project.py`, `tools/check_android_build_environment.sh` et `.github/workflows/android-build.yml` ; ajouter `docs/LOT_8B_F_CLOTURE.md` et ce fichier d'instructions.
3. Ne pas remplacer ni supprimer `.git`, `app/`, `gradle.properties`, les autres tests ou les documentations existantes.
4. Dans GitHub Desktop, vérifier que les changements correspondent uniquement aux fichiers de ce lot, puis créer un commit descriptif, par exemple `LOT 8B-F: fiabilise test GNSS et bootstrap Gradle Wrapper`.
5. Pousser `main` et suivre le nouveau run `Android build and audit`.
6. Vérifier les étapes `Generate pinned Gradle Wrapper`, `Verify generated Gradle Wrapper`, `JVM regression tests`, `Assemble debug APK` et `Run Android Lint`.
7. Télécharger les artefacts `gradle-wrapper-bootstrap`, `gps-test-debug-apk` et `android-lint-reports`.
8. Extraire `gradle-wrapper-bootstrap` à la racine du dépôt, en conservant les chemins `gradle/wrapper/`. Committer ensuite les quatre fichiers du Wrapper.
9. Ne retirer le bootstrap du workflow qu'après avoir vérifié que les quatre fichiers sont présents et versionnés sur `main`.

Le correctif ne modifie pas le code GNSS de production. Il ne change ni `minSdk`, ni `targetSdk`, ni les règles GPX ou cartographiques. Le build Android complet et Android Lint restent à confirmer par GitHub Actions après ce push.
