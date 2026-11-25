# LOT 8C-R2C.4 — Registre et politique de conservation des copies récupérées

**Date : 25 novembre 2026**  
**Base : LOT 8C-R2C.3 ; compilation Android R2C.3 confirmée par l'utilisateur.**  
**État : candidate préparée ; tests JVM et audits statiques réussis ; compilation Android R2C.4 laissée à l'utilisateur.**

## 1. But et règle de sécurité

R2C.4 prépare la conservation contrôlée des copies locales `*_RECUPEREE.gpx` qui ont déjà été publiées vers le dossier SAF et dont le snapshot de récupération a été acquitté.

Le lot **n'active aucune suppression automatique**. Il ajoute une preuve durable de provenance, calcule une échéance minimale et expose un résumé. Les copies à échéance sont signalées pour un futur examen manuel. Aucun fichier GPX n'est supprimé par R2C.4.

Règles non négociables :

- le GPX source interrompu n'est jamais une cible de rétention ou de purge ;
- une copie privée non publiée, une publication non vérifiée ou un journal non acquitté n'entre pas dans le registre ;
- si le registre est absent, corrompu, saturé ou non synchronisable, les fichiers restent conservés sans limite ;
- le quota est un avertissement, jamais une autorisation de suppression ;
- une échéance de 30 jours n'autorise pas à elle seule la suppression ;
- les fournisseurs SAF réels n'ayant pas encore été testés, aucune suppression locale n'est déclenchée.

## 2. Ordre exact d'inscription

Le workflow `GpxRecoveryWorkflow.recoverAndPublish()` ajoute une entrée de rétention uniquement après les opérations suivantes :

1. la copie locale `*_RECUPEREE.gpx` est créée et validée depuis le préfixe checkpointé ;
2. le document SAF est écrit, fermé, relu intégralement et vérifié par longueur + SHA-256 ;
3. l'entrée de publication `VERIFIED` est acquittée dans `recovery-publications.registry` ;
4. `SessionStore.clearRecoveryIfUnchanged(context, expected)` réussit sur le snapshot exact attendu ;
5. l'entrée de rétention est enregistrée atomiquement.

Le point 5 est volontairement après l'acquittement du snapshot. Si l'application meurt entre 4 et 5, la copie locale reste présente mais ne reçoit aucun enregistrement de rétention : elle ne pourra donc jamais être purgée automatiquement par cette version. Si le registre de rétention ne peut pas être écrit (corruption, capacité atteinte, stockage indisponible), le workflow reste `PUBLISHED` car le document SAF est vérifié et le journal a été acquitté ; le message précise que la copie locale sera conservée sans limite.

Les résultats `LOCAL_ONLY`, `PENDING_PERMISSION`, `PENDING_PUBLICATION`, `PUBLICATION_CONFLICT` et `JOURNAL_NOT_ACKNOWLEDGED` ne sont pas inscrits dans ce registre.

## 3. Durée et seuils

Valeurs initiales du modèle :

| Paramètre | Valeur | Effet |
|---|---:|---|
| Durée minimale | 30 jours | Avant cette échéance : conserver obligatoirement. |
| Seuil indicatif | 512 Mio | Afficher un avertissement, sans supprimer. |
| Capacité du registre | 128 entrées | Pas d'éviction automatique ; si plein, la copie non enregistrée reste conservée sans limite. |

Le timestamp `publishedAtMs` est écrit après la vérification SAF et l'acquittement du snapshot. Il ne dépend pas de `File.lastModified()`. Une horloge en recul produit `CLOCK_MOVED_BACKWARDS` et prolonge la conservation. Les additions susceptibles de déborder `long` sont refusées.

Le quota de 512 Mio mesure **uniquement les copies publiées présentes dans `recovery-retention.registry`**. Il ne prétend pas représenter tout le stockage applicatif : les copies privées non publiées, les anciennes copies non répertoriées et les GPX d'enregistrement normal ne sont pas comptés par ce résumé.

Ces valeurs sont des valeurs techniques conservatrices pour cette version, pas des préférences utilisateur. Elles ne sont pas encore réglables dans l'interface.

## 4. `recovery-retention.registry`

Nouveaux fichiers :

- `RecoveryRetentionRegistry.java` : modèle pur, format `retentionFormat=1`, 128 entrées, parsing strict, identité et digest immuables ;
- `RecoveryRetentionStore.java` : persistance privée par `AtomicFile`, `flush()`, `FileDescriptor.sync()`, validation stricte UTF-8 et refus d'écraser un registre corrompu ;
- `RecoveryRetentionPolicy.java` : règles pures de durée, horloge, échéance et quota.

Chaque entrée conserve : publication ID, session ID, génération de segment, nom du GPX source interrompu, nom de la copie locale récupérée, longueur `recoveredLengthBytes` de cette copie locale (et non celle du GPX source interrompu), SHA-256 de cette copie, URI de l'arbre SAF, URI du document, nom publié et timestamp de publication.

Le nom de copie doit être un nom feuille sûr se terminant en `.gpx`, contenant `_RECUPEREE`, et être distinct du nom du GPX source. Les URI doivent être `content://`. Les enregistrements dupliqués ne sont acceptés que si tous les champs, y compris la date, sont identiques : une nouvelle tentative ne peut pas rajeunir la date de publication.

Une corruption ne conduit jamais à créer un registre vide de remplacement. `RecoveryRetentionStore.addOrVerify()` échoue et préserve les octets existants.

## 5. Affichage opérationnel

`GpxRecoveryRetryWorker` lit le registre de conservation lors de ses exécutions et ajoute au résumé :

- nombre de copies publiées suivies ;
- nombre encore sous les 30 jours ;
- nombre arrivé à échéance pour examen manuel ;
- volume total enregistré ;
- avertissement si le seuil indicatif de 512 Mio est dépassé.

`MainActivity` maintient visible le bandeau de récupération lorsque le dernier résultat contient un état de conservation. Un registre illisible est explicitement signalé et entraîne un état de diagnostic.

Il n'existe pas encore d'écran de gestion individuelle, de bouton de suppression, de préférence d'auto-nettoyage, de worker périodique ou de mécanisme de purge. Ce sont des tâches futures qui demanderont une décision explicite.

## 6. Tests et contrôles exécutés

| Contrôle | Résultat |
|---|---:|
| `python3 tools/run_jvm_tests.py` | **50 suites, 1 715 assertions, 0 échec** |
| `RecoveryRetentionRegistryTest` | 21 assertions |
| `RecoveryRetentionPolicyTest` | 22 assertions |
| `RecoveryRetentionStoreTest` | 9 assertions |
| `verify_android_project.py` | 17 XML, 95 sources Java, OK |
| `verify_offline_map_architecture.py` | 113 fichiers, aucun moteur/provider raster, OK |
| `verify_gpx_async_open.py` | OK |
| `verify_gpx_checkpoint.py` | OK |
| `verify_gpx_recovery.py` | OK |
| `verify_gpx_recovery_publication.py` | OK |
| `verify_gpx_recovery_retry.py` | OK |
| `verify_gpx_recovery_retention.py` | OK |
| `python3 -m py_compile tools/*.py` | OK |
| Analyse YAML de `.github/workflows/android-build.yml` | OK |

Le test du store utilise le stub JVM d'`AtomicFile` et un dossier temporaire. Il vérifie l'écriture/lecture, l'idempotence, l'absence d'écrasement d'une identité existante et la préservation d'un registre corrompu.

Aucune compilation Android n'a été lancée dans cette préparation, conformément à la consigne de l'utilisateur. R2C.3 est confirmé compilé par l'utilisateur ; R2C.4 doit l'être par lui après remplacement.

## 7. Limites assumées

- Aucune suppression de fichier n'est présente dans `RecoveryRetentionRegistry`, `RecoveryRetentionStore` ou `RecoveryRetentionPolicy`.
- Le seuil de quota ne provoque pas de suppression, même si le stockage est plein.
- Les copies non publiées et les copies dont l'inscription de rétention a échoué restent conservées sans limite et hors quota suivi.
- Le statut « prêt pour examen manuel » est un décompte dans le résumé ; l'interface détaillée par fichier n'existe pas encore.
- La copie SAF n'est pas relue à chaque résumé : la nouvelle vérification SAF, locale et des registres devra impérativement précéder toute future suppression.
- Aucun essai réel sur téléphone, ADB, DocumentsUI, MediaProvider, cloud, MTP ou carte SD n'est effectué dans ce lot. La campagne demeure reportée à plus tard.

## 8. Prochaine étape recommandée — R2C.5

Préparer une interface d'examen manuel, sans suppression automatique : liste de copies, date de publication, échéance, taille, destination, URI et état de vérification. Une future suppression ne pourra être proposée que pour une entrée arrivée à échéance et après : suivi GNSS inactif ; registres de récupération, publication et rétention lisibles ; absence de journal/transaction ouverte pour l'identité exacte ; chemin canonique confiné au dossier privé ; longueur + SHA-256 locaux exacts ; permission SAF encore valide ; relecture du document externe et égalité SHA-256/longueur au moment même de l'action ; confirmation explicite de l'utilisateur.

La première implémentation éventuelle devra supprimer **uniquement la copie locale `_RECUPEREE`**, jamais le GPX source interrompu ni le document SAF. Il faudra prévoir un état transactionnel de purge pour supporter une coupure entre l'intention, la suppression du fichier local et le retrait de l'entrée de registre.

La campagne téléphone réel / ADB / fournisseurs SAF reste un jalon séparé, reporté conformément à la demande de l'utilisateur.
