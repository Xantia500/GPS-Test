# Appliquer le correctif Lint AGC API 33

Ce correctif remplace deux fichiers à la racine du dépôt GPS-Test :

- `app/src/main/java/com/example/gpstest/TrackingService.java`
- `tools/verify_android_project.py`

## Application avec GitHub Desktop

1. Télécharger et extraire cette archive.
2. Copier son contenu à la racine du dépôt local `GPS-Test` (au même niveau que `app`, `tools`, `docs`, `build.gradle`).
3. Accepter le remplacement des deux fichiers.
4. Vérifier que GitHub Desktop montre les deux fichiers comme modifiés.
5. Commit : `Guard GNSS AGC API 33 calls for minSdk 24`.
6. Push origin et vérifier le nouveau workflow `Android build and audit`.

Le correctif n'augmente ni `minSdk` ni `targetSdk`. Il protège les getters AGC API 33 par `Build.VERSION_CODES.TIRAMISU` et ajoute un contrôle statique anti-régression.
