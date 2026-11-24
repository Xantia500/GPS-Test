# Appliquer le correctif LOT 8B.1

Le build GitHub Actions a trouvé deux incompatibilités d'API. Le ZIP `CORRECTIF_8B_1.zip` contient les fichiers corrigés avec leurs chemins relatifs exacts.

## Fichiers à mettre à jour à la racine du dépôt `GPS-Test`

1. `app/src/main/java/com/example/gpstest/OfflineMapsActivity.java`
2. `app/src/main/java/com/example/gpstest/TrackingService.java`
3. `tools/stubs/android/location/LocationRequest.java`
4. `tools/verify_android_project.py`
5. Documentation : `docs/LOT_8B_CORRECTIF_API_ANDROID.md`
6. Rapport mis à jour : `docs/LOT_8B_PREMIERE_CORRECTION_COMPILATION.md`

Extraire le contenu de ce ZIP à la racine du dépôt, en conservant les sous-dossiers et en remplaçant les fichiers existants. Puis créer un commit, par exemple `Fix Android API compilation errors`, sur la branche `main`.

Ne pas placer un dossier supplémentaire `GPS-Test` à l'intérieur du dépôt.

Après le commit, relancer le workflow `Android build and audit` sur `main` (nouvelle exécution, pas « Re-run jobs » sur l'ancien commit).
