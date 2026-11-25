# LOT 8C-R2C.5 — Écran de gestion des copies GPX récupérées

**Date : 25 novembre 2026**  
**Base :** `GPS-Test-LOT-8C-R2C4-PROJET-COMPLET-2026-11-25.zip`  
**SHA-256 de la base :** `2ba488db6e4afa19ab6d4a51b9fd94c958e04f7f81069ee97235947d510b713b`  
**État :** code intégré ; 51 suites JVM / 1 740 assertions ; audits statiques réussis ; compilation Android non lancée conformément à la consigne utilisateur.

## 1. Objectif

R2C.5 ajoute un écran consultatif pour examiner les copies locales `*_RECUPEREE.gpx` inscrites dans `recovery-retention.registry` après publication SAF vérifiée et acquittement du snapshot de récupération.

L'accès se trouve dans **Réglages → Données enregistrées → GÉRER LES TRACES RÉCUPÉRÉES**. L'écran est une Activity non exportée, enfant de `SettingsActivity`.

## 2. Informations affichées pour chaque entrée

- nom du document publié dans le dossier SAF ;
- nom de la copie locale `*_RECUPEREE.gpx` ;
- nom du fichier source interrompu (présenté comme source conservée) ;
- identifiant de session et génération du segment ;
- date/heure de publication locale, ancienneté calculée et échéance d'examen manuel ;
- taille de la copie locale inscrite au registre ;
- état non destructif de la copie locale : absente, longueur conforme, longueur divergente, non lisible, non fichier ordinaire, chemin hors du dossier attendu ou dossier indisponible ;
- état de la permission SAF persistante pour l'URI de l'arbre ;
- indication si le dossier enregistré est encore le dossier actuellement choisi ;
- SHA-256 enregistré lors de la publication ;
- URI de l'arbre SAF, URI du document et identifiant de publication (textes sélectionnables/copiables).

Les lignes sont triées par date de publication décroissante ; les égalités sont départagées par `publicationId` afin de garder un ordre déterministe.

## 3. Sémantique des vérifications

Le registre R2C.4 n'est alimenté qu'après relecture SAF complète et égalité longueur + SHA-256. L'écran expose honnêtement cette preuve historique : **« vérifiée à la publication »**.

L'écran ne relit pas spontanément les fournisseurs SAF. Il vérifie en revanche, en tâche de fond, que le fichier local attendu existe dans `getExternalFilesDir(null)`, que son chemin canonique reste directement dans ce dossier, qu'il s'agit d'un fichier ordinaire lisible et que sa longueur correspond au registre. Il ne recalcule pas le SHA-256 local lors de l'affichage afin d'éviter une lecture potentiellement longue et coûteuse ; l'empreinte enregistrée est affichée, avec cette limite explicitée.

L'absence de permission SAF n'efface aucune ligne. Le fait que le dossier actif diffère de l'ancien dossier enregistré n'invalide pas la publication historique.

## 4. Conception et composants

- `RecoveryManagementActivity.java` : écran, rafraîchissement manuel et actualisation à `onResume()` ; aucune opération de registre/stockage sur le thread UI ; aucun appel de suppression ni écriture SAF.
- `RecoveryRetentionPresentation.java` : modèle pur ; tri stable, échéance/ancienneté, état de la copie locale par confinement canonique et longueur ; liste immuable.
- `activity_recovery_management.xml` : layout dédié, résumé, progression, état vide, conteneur de lignes et boutons actualiser/fermer.
- `SettingsActivity.java` + `activity_settings.xml` : point d'entrée dans le bloc Données enregistrées.
- `AndroidManifest.xml` : Activity privée (`exported=false`) avec retour hiérarchique vers les réglages.
- `strings.xml` : libellés français, pluriel des jours, messages d'état et limites de vérification.
- `verify_gpx_recovery_management.py` : contrôle de structure et garde non destructive, intégré à GitHub Actions.

## 5. Invariants de sécurité

1. L'écran ne supprime jamais de fichier et ne retire aucune entrée du registre.
2. Aucune purge automatique n'est introduite ; `RecoveryRetentionPolicy.automaticDeletionEnabled()` reste `false`.
3. La source GPX interrompue n'est jamais manipulée par l'écran.
4. Un registre illisible produit un diagnostic et non une liste vide de remplacement.
5. Les lectures du registre et les contrôles locaux se font sur un executor en arrière-plan.
6. Les chemins locaux sont confinés au dossier exact par comparaison des chemins canoniques.
7. Les URI SAF et empreintes restent copiables, mais elles sont des métadonnées historiques ; l'écran ne prétend pas revérifier le document externe.
8. L'absence de permission SAF ou une anomalie locale ne déclenche ni réparation ni suppression.

## 6. Contrôles effectués

| Contrôle | Résultat |
|---|---:|
| `python3 tools/run_jvm_tests.py` | **51 suites / 1 740 assertions / 0 échec** |
| `RecoveryRetentionPresentationTest` | **25 assertions** |
| `verify_android_project.py` | **18 XML / 97 sources Java**, OK |
| `verify_offline_map_architecture.py` | **116 fichiers**, aucun moteur/provider raster, OK |
| Audits async-open, checkpoint, recovery, publication, retry, retention | Tous OK |
| `verify_gpx_recovery_management.py` | OK |
| `python3 -m py_compile tools/*.py` | OK |

Aucune compilation Android n'a été lancée. L'utilisateur prend en charge la compilation Android locale et a explicitement demandé à l'assistant de ne plus la lancer.

## 7. Limites et tâches reportées

- Aucune vérification en direct du contenu SAF n'est déclenchée à l'ouverture de l'écran. Une future action de vérification individuelle devra s'exécuter sur un worker, relire le document et comparer longueur + SHA-256.
- Aucun partage/ouverture de fichier ni aucune suppression n'est proposé dans R2C.5.
- Les copies locales manquantes ou de longueur divergente restent inscrites pour diagnostic ; elles ne sont pas retirées du registre.
- La campagne réelle téléphone/ADB et les tests avec DocumentsUI, MediaProvider, fournisseurs cloud, MTP et cartes SD restent reportés explicitement à plus tard.

## 8. Étape suivante proposée

R2C.6 pourrait ajouter une vérification manuelle à la demande d'une entrée : relecture SAF complète, calcul SHA-256 local et distant, état « conforme / divergent / permission absente / document indisponible », sans suppression. La suppression manuelle locale restera un chantier séparé, après que le modèle d'intégrité et les essais réels auront été éprouvés.
