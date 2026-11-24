# Instructions — LOT 8C-R2B.2

## Méthode recommandée

1. Faire une copie de sauvegarde du dossier GPS-Test actuel (R2B.1).
2. Télécharger `GPS-Test-LOT-8C-R2B2-FICHIERS-A-REMPLACER-2026-11-24.zip`.
3. Décompresser son contenu **à la racine du dossier GPS-Test**, c'est-à-dire au même niveau que `app`, `tests`, `tools`, `docs` et `.github`.
4. Accepter le remplacement des fichiers existants et la création des nouveaux fichiers.
5. Ouvrir/synchroniser le projet dans Android Studio et lancer `assembleDebug`.

Aucun patch Git n'est à appliquer. Aucune modification n'est faite directement sur GitHub : l'utilisateur conserve la maîtrise du dépôt et recopie les fichiers livrés.

## Fichiers applicatifs concernés

- `app/src/main/java/com/example/gpstest/TrackingService.java`
- `app/src/main/java/com/example/gpstest/SessionJournal.java`
- `app/src/main/java/com/example/gpstest/SessionStore.java`
- `app/src/main/java/com/example/gpstest/GpxCheckpoint.java` (nouveau)

## Tests / CI / documentation

L'archive comprend aussi les tests JVM mis à jour, `tools/verify_gpx_checkpoint.py`, le workflow `.github/workflows/android-build.yml`, la note technique `docs/LOT-8C-R2B2-CHECKPOINT-GPX-DURABLE-2026-11-24.md` et cette notice.

## Résultats de préparation

42 suites JVM, 1 463 assertions, aucun échec ; 15 invariants checkpoint ; audit Android statique, audit cartographique, vérification R2B.1 et YAML passés.

La compilation Android de R2B.2 reste à exécuter dans Android Studio. Le lot suivant ne sera pas considéré compilé tant que ce test n'aura pas été confirmé.
