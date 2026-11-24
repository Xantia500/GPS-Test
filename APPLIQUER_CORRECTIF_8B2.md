# Correctif LOT 8B2 — erreur de capture de lambda Java

Le journal GitHub Actions indiquait `local variables referenced from a lambda expression must be final or effectively final` dans `TrackingService.java` autour de la ligne 3574.

## Fichiers à remplacer dans le dépôt GitHub

- `app/src/main/java/com/example/gpstest/TrackingService.java`
- `tools/verify_android_project.py`

Le rapport détaillé est `docs/LOT_8B_CORRECTIF_LAMBDA.md`. La mise à niveau Gradle 9.9.2 / AGP 9.5.2 est conservée dans cette livraison.

Après extraction, copier les deux chemins en conservant leur arborescence dans le dossier local du dépôt, valider les changements dans GitHub Desktop, puis effectuer `Commit to main` et `Push origin`.
