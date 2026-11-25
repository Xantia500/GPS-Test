# GPS-Test — LOT 8C-R2C.1

## Installation

1. Faire une copie de sauvegarde locale du projet actuel.
2. Extraire l’archive de 12 fichiers `GPS-Test-LOT-8C-R2C1-FICHIERS-A-REMPLACER-2026-11-25.zip` à la racine du projet (au même niveau que `app`, `tests`, `tools`, `docs` et `.github`).
3. Accepter les remplacements et conserver les chemins/dossiers de l'archive.
4. Ouvrir/synchroniser le projet dans Android Studio et lancer la compilation habituelle.

Il n'y a aucun patch Git à appliquer. L'assistant n'a pas écrit sur GitHub. L'utilisateur reporte lui-même les fichiers dans son dépôt.

## Fichiers principaux

- `RecoveryRegistry.java` : nouveau registre durable de segments GPX en attente.
- `SessionStore.java` : persistance atomique du registre et coordination avec `session.state`.
- `TrackingService.java` : callbacks de checkpoint et fermeture des segments.
- `MainActivity.java` : parcours du registre au démarrage.

Les chemins du ZIP sont relatifs à la racine du projet. Ne pas déplacer les fichiers individuellement.

## Contrôles déjà exécutés par l'assistant

- 44 suites JVM / 1 581 assertions / zéro échec ;
- audits structurels récupération, checkpoint et ouverture asynchrone réussis ;
- audit Android statique : 17 XML et 84 sources Java ;
- audit cartographique hors ligne : 102 fichiers, aucun moteur/provider raster.

La compilation Android R2C.1, Android Lint et les essais réels ne sont pas revendiqués ici ; la compilation de R2C.1 doit être vérifiée dans Android Studio.

## Règle de sécurité

Le registre a une capacité stricte de 64 entrées sans éviction. Une corruption ou saturation doit provoquer un refus conservateur de l'écriture, jamais la suppression silencieuse d'une ancienne récupération. Ne pas supprimer `recovery.registry` manuellement : ce fichier protège les segments de sessions précédentes.
