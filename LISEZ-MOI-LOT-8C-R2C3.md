# GPS-Test — installation du LOT 8C-R2C.3

## Archive à utiliser

`GPS-Test-LOT-8C-R2C3-FICHIERS-A-REMPLACER-2026-11-25.zip`

## Installation

1. Fermer Android Studio ou arrêter le Gradle Sync en cours.
2. Décompresser l'archive à la racine du projet GPS-Test, au même niveau que `app`, `tests`, `tools`, `docs` et `.github`.
3. Conserver exactement l'arborescence et accepter le remplacement des fichiers existants.
4. Rouvrir/synchroniser le projet dans Android Studio et effectuer ta compilation Android habituelle.
5. Ne pas appliquer de patch Git : aucun patch n'est nécessaire.

L'archive `GPS-Test-LOT-8C-R2C3-PROJET-COMPLET-2026-11-25.zip` contient le projet autonome complet si tu préfères ouvrir une copie séparée.

## Ce que contient le lot

Reprise automatique et explicite des récupérations GPX/publications SAF via WorkManager, bouton `RELANCER`, statut visible, déclenchement après sélection d'un dossier SAF et après arrêt du suivi GNSS, politique de retries bornés, classification des erreurs nécessitant une intervention, diagnostic indépendant des registres/journaux.

Aucun fichier GPX récupéré n'est supprimé automatiquement. Aucun fichier GitHub n'a été modifié. Les essais sur téléphone réel et ADB restent reportés à plus tard.

## Validation préparée

47 suites JVM / 1 663 assertions / 0 échec. Les audits statiques Android, cartographie, ouverture GPX asynchrone, checkpoint, récupération, publication SAF et nouveau worker passent. La compilation Android est laissée à l'utilisateur, conformément à sa consigne.
