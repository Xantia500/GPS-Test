# GPS-Test — installation du LOT 8C-R2C.4

## Archives

- Remplacement direct : `GPS-Test-LOT-8C-R2C4-FICHIERS-A-REMPLACER-2026-11-25.zip` (**16 fichiers**, avec chemins relatifs à la racine du projet).
- Projet autonome : `GPS-Test-LOT-8C-R2C4-PROJET-COMPLET-2026-11-25.zip`.

## Installation

1. Fermer Android Studio ou au minimum arrêter Gradle Sync et toute compilation en cours.
2. Décompresser l'archive « fichiers à remplacer » à la racine du projet GPS-Test, au même niveau que `app`, `tests`, `tools`, `docs` et `.github`.
3. Conserver l'arborescence et accepter les remplacements.
4. Rouvrir/synchroniser le projet et effectuer la compilation Android localement.
5. Aucun patch Git n'est nécessaire. Aucun fichier GitHub n'a été modifié.

## Politique introduite

- Seules les copies locales `*_RECUPEREE.gpx` dont la publication SAF a été relue (longueur + SHA-256) et dont le snapshot de récupération a été acquitté sont inscrites dans `recovery-retention.registry`.
- La longueur `recoveredLengthBytes` désigne la taille de la copie locale récupérée publiée, et non celle du GPX source interrompu.
- Durée minimale avant examen : 30 jours après publication vérifiée et acquittement.
- Seuil indicatif : 512 Mio, pour les seules copies suivies ; avertissement seulement.
- Capacité : 128 enregistrements, sans éviction ni rajeunissement d'une entrée existante.
- Aucune suppression automatique ou manuelle n'est activée dans ce lot.
- Les fichiers sources interrompus, les copies privées non publiées et les copies dont le registre n'a pas pu être écrit restent conservés sans limite.

## Validation préparée

50 suites JVM / 1 715 assertions / 0 échec. Audits statiques Android (17 XML / 95 sources Java), cartographie (113 fichiers), ouverture GPX asynchrone, checkpoint, récupération, publication SAF, reprise WorkManager et rétention réussis.

L'assistant ne lance plus la compilation Android, conformément à la consigne de l'utilisateur ; elle est effectuée par l'utilisateur. Les essais réels sur téléphone, ADB et fournisseurs SAF restent reportés à plus tard.
