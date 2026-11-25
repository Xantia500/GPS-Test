# Installation — LOT 8C-R2C.5

1. Fermer Android Studio ou arrêter les opérations d'édition en cours.
2. Décompresser `GPS-Test-LOT-8C-R2C5-FICHIERS-A-REMPLACER-2026-11-25.zip` à la racine du projet GPS-Test, au même niveau que `app`, `tests`, `tools`, `docs` et `.github`.
3. Conserver l'arborescence des dossiers et accepter le remplacement des fichiers existants.
4. Ouvrir/synchroniser le projet dans Android Studio et lancer la compilation Android localement.
5. Après compilation, ouvrir **Réglages → Données enregistrées → GÉRER LES TRACES RÉCUPÉRÉES**.

Il n'y a aucun patch Git à appliquer. Aucun changement n'a été effectué sur GitHub.

## Ce que contient l'écran

- la liste des copies `_RECUPEREE` inscrites au registre de conservation ;
- la date de publication, l'âge et la date d'examen manuel possible ;
- les noms source/copie, taille, identifiants, empreinte SHA-256 et URI SAF ;
- l'état de la permission SAF et un contrôle non destructif de présence/longueur du fichier local.

L'écran est consultatif. Il ne supprime rien, ne relit pas spontanément les fournisseurs SAF et n'active aucune purge. La vérification SAF affichée est celle effectuée lors de la publication initiale.

La compilation Android n'a pas été lancée pendant la préparation du lot, conformément à la consigne de l'utilisateur.
