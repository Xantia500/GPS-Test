# GPS-Test — récupération du LOT 8C-R2A.4

Ce lot corrige le formatage des distances négatives et non finies.

## Fichiers à remplacer

Décompresser cette archive **à la racine du projet GPS-Test**, en conservant l'arborescence et en acceptant le remplacement de :

- `app/src/main/java/com/example/gpstest/Units.java`
- `tests/ModesAndPostProcessTest.java`

Aucun patch Git n'est nécessaire. Aucun fichier n'a été modifié directement sur GitHub.

## Vérification JVM

À la racine du projet :

```bash
python3 tools/run_jvm_tests.py
```

Résultat obtenu lors de la préparation : 39 suites, 1 374 assertions, 0 échec.

La compilation Android Gradle doit être vérifiée dans Android Studio. Elle n'a pas été exécutée dans l'environnement de préparation (Gradle absent et Wrapper non versionné dans l'archive).

## Documentation

Voir `docs/LOT_8C_R2A4_UNITES_DISTANCE_2026-11-24.md`.
