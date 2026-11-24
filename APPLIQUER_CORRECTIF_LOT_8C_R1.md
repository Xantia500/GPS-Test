# Appliquer le correctif LOT 8C-R1 — Journal de session durable

1. Télécharger et extraire le ZIP à la racine du dépôt local `GPS-Test` (le dossier qui contient `.git`).
2. Accepter les remplacements des fichiers et ajouts listés ci-dessous ; ne jamais supprimer `.git`.
3. Dans GitHub Desktop, vérifier que les modifications ne concernent que les fichiers du LOT 8C-R1.
4. Créer le commit : `LOT 8C-R1: rend le journal de session atomique et non destructif`.
5. Faire `Push origin` sur `main`.
6. Ouvrir le nouveau run `Android build and audit` correspondant exactement au commit poussé.
7. Vérifier `JVM regression tests`, `Assemble debug APK` et `Run Android Lint`. Récupérer APK et rapports Lint.
8. Ne pas commencer R2 avant d'avoir confirmé une CI Android verte ou traité ses diagnostics.

## Fichiers concernés

- `README.md`
- `app/src/main/java/com/example/gpstest/SessionJournal.java`
- `app/src/main/java/com/example/gpstest/SessionStore.java`
- `app/src/main/java/com/example/gpstest/TrackingService.java`
- `app/src/main/java/com/example/gpstest/MainActivity.java`
- `tests/SessionJournalTest.java`
- `tests/SessionStoreTest.java`
- `tools/stubs/android/content/Context.java`
- `tools/stubs/android/util/AtomicFile.java`
- `docs/LOT_8C_R1_JOURNAL_DURABLE.md`
- `APPLIQUER_CORRECTIF_LOT_8C_R1.md`

## Portée et limites

Le lot durcit uniquement le journal d'état et ses accès. Il ne modifie pas les mesures GNSS, la sélection/qualité des fixes, le format du GPX ni la cartographie. Il n'implémente pas encore la récupération des octets GPX après crash : cela relève du LOT 8C-R2. Les tests JVM utilisent un stub AtomicFile ; la compilation contre le SDK Android et Lint doivent être confirmés par GitHub Actions.
