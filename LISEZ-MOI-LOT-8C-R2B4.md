# LOT 8C-R2B.4 — fichiers à remplacer

Cette archive apporte le durcissement de l'acquittement et de l'intégrité de la récupération après interruption.

## Installation

1. Décompresser cette archive à la racine du projet GPS-Test, au même niveau que `app`, `tests`, `tools` et `docs`.
2. Conserver l'arborescence proposée et accepter le remplacement des six fichiers existants.
3. Les fichiers de documentation et résultats sont nouveaux.
4. Ouvrir le projet dans Android Studio, synchroniser Gradle et lancer `:app:assembleDebug`.

Aucun patch Git n'est à appliquer. Aucun fichier de GitHub n'a été modifié.

## Pourquoi ce lot ?

- Le journal n'est effacé après récupération que si la révision exacte lue avant le travail est encore courante. Un checkpoint plus récent de la même session ne peut plus être supprimé par une opération retardée.
- Le préfixe copié est comparé à la source par SHA-256, même si taille et date de modification restent identiques.
- Les noms de fichier comportant des espaces de bord sont rejetés plutôt que normalisés implicitement.
- Les tests couvrent la coupure d'un caractère UTF-8, les appels concurrents, un GPX sans segment, le nettoyage des temporaires et le checkpoint plus récent d'une session.

Le fichier original GPX reste non modifié. En cas de doute ou d'erreur, le journal et la source sont conservés.

## Résultats de préparation

43 suites JVM, 1 513 assertions, zéro échec. Audits Android et cartographie statiques, vérifications R2B.1/R2B.2/R2B.4 et analyse YAML réussis. La compilation Android R2B.4 doit être confirmée dans Android Studio ; la compilation R2B.3 a été confirmée par l'utilisateur.
