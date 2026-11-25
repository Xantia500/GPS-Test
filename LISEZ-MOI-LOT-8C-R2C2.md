# LOT 8C-R2C.2 — fichiers à remplacer

Le ZIP contient les fichiers modifiés/ajoutés du LOT 8C-R2C.2 et garde les chemins relatifs du projet.

## Installation

1. Fais une copie de sauvegarde de ton projet local GPS-Test.
2. Décompresse cette archive à la racine du projet, au même niveau que `app`, `tests`, `tools`, `docs` et `.github`.
3. Accepte les remplacements.
4. Dans Android Studio, synchronise Gradle puis lance `assembleDebug` et `lintDebug`.
5. Ne reporte les fichiers dans GitHub qu'après ta vérification locale ; aucun commit/push n'a été fait par l'assistant.

Aucun patch Git n'est requis. N'applique pas les fichiers individuellement : les changements dans `MainActivity`, `TrackingService`, le nouveau workflow et le registre SAF sont coordonnés.

## Livrable et périmètre

- Publication des GPX récupérés vers le dossier SAF choisi, après récupération privée sûre.
- Vérification après écriture par relecture de la longueur et du SHA-256.
- Registre `recovery-publications.registry` atomique, états PREPARED / DOCUMENT_CREATED / VERIFIED.
- Reprise après création/écriture partielle et refus conservateur des collisions homonymes non vérifiées.
- Aucun effacement de l'original interrompu ; copie interne conservée en cas de panne SAF.
- Contrôles JVM : 46 suites, 1 637 assertions, 0 échec.
- Audits statiques récupération, publication SAF, checkpoint, ouverture asynchrone, Android et cartographie réussis.

La compilation Android R2C.2 reste à confirmer par Android Studio. Les essais réels SAF sur téléphone et la campagne ADB sont explicitement reportés à plus tard et sont détaillés dans le dossier de reprise MAJ10.

Voir `docs/LOT-8C-R2C2-PUBLICATION-SAF-TRANSACTIONNELLE-2026-11-25.md` et `docs/DOSSIER-REPRISE-ULTRA-EXHAUSTIF-2026-11-25-MAJ10.md`.
