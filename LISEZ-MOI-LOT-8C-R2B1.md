# GPS-Test — Instructions de remplacement — LOT 8C-R2B.1

**Base attendue :** projet complet GPS-Test — LOT 8C-R2A.4 (ou une copie strictement dérivée de cette livraison).  
**Important :** aucune modification n'est effectuée directement sur GitHub.

## Fichiers fournis

Cette archive contient les fichiers suivants avec leurs chemins relatifs exacts :

1. `.github/workflows/android-build.yml` — remplacer le fichier existant ;
2. `app/src/main/java/com/example/gpstest/TrackingService.java` — remplacer le fichier existant ;
3. `app/src/main/java/com/example/gpstest/GpxSegmentIoState.java` — nouveau fichier ;
4. `tests/GpxSegmentIoStateTest.java` — nouveau fichier ;
5. `tests/GpxSegmentQueueOrderTest.java` — nouveau fichier ;
6. `tools/verify_gpx_async_open.py` — nouveau fichier ;
7. `docs/LOT-8C-R2B1-OUVERTURE-GPX-ASYNCHRONE-2026-11-24.md` — note technique ;
8. `LISEZ-MOI-LOT-8C-R2B1.md` — présent fichier d'instructions.

## Procédure

1. Faire une copie de sauvegarde du projet actuel.
2. Décompresser l'archive à la racine du dossier `GPS-Test` (le dossier qui contient `app/`, `tests/`, `tools/` et `.github/`).
3. Accepter le remplacement de `TrackingService.java` et de `.github/workflows/android-build.yml`.
4. Vérifier que les quatre nouveaux fichiers de code/tests/outils sont bien placés selon les chemins de l'archive.
5. Ouvrir le projet dans Android Studio et lancer **Build > Make Project** ou la compilation habituelle.
6. Ne pas envoyer les fichiers sur GitHub avant d'avoir vérifié que le projet utilisé est bien issu de R2A.4 et que la compilation locale réussit.

Il n'y a aucun patch Git à appliquer. Il suffit de remplacer/ajouter les fichiers dans l'arborescence.

## Résultats de préparation

- JVM : 41 suites, 1 421 assertions, zéro échec ;
- audit Android statique : réussi (17 XML, 81 sources Java) ;
- audit architecture cartes hors ligne : réussi (99 fichiers) ;
- contrôle statique des invariants R2B.1 : réussi ;
- analyse YAML du workflow : réussie.

La compilation Android réelle n'a pas été exécutée dans l'environnement de préparation (Gradle et Android SDK non disponibles). Merci de confirmer le résultat Android Studio avant la poursuite du chantier.
