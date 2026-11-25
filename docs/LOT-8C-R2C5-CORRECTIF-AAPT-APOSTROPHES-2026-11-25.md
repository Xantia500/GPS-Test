# Correctif complémentaire LOT 8C-R2C.5-AAPT.1 — ressources Android

Date : 25 novembre 2026  
Base : LOT 8C-R2C.5  
Portée : correction de compilation des ressources `values/strings.xml` et prévention statique.

## 1. Symptôme signalé

La CI échouait pendant `:app:processDebugResources` après que `:app:compileDebugJavaWithJavac` avait réussi. Le diagnostic pointait vers `app/src/main/res/values/strings.xml`, ressource `gpx_recovery_state_not_synchronized`, avec le message AAPT : `Invalid unicode escape sequence in string`.

Ce n'était pas un échec Java. Le message relatif au traitement de la ressource est à distinguer des avertissements précédents sur les API Java dépréciées.

## 2. Constat dans la source R2C.5 livrée

La valeur `gpx_recovery_state_not_synchronized` contenait deux apostrophes ASCII non échappées : `L'état` et `n'a`. AAPT2 attend que les apostrophes d'un texte de ressource soient échappées (`\'`) lorsqu'elles ne sont pas contenues dans une plage entourée de guillemets doubles. Le parseur AAPT2 traite séparément les apostrophes et les séquences Unicode `\uXXXX` ; son code source documente ces deux chemins de traitement.

L'audit de l'ensemble du `strings.xml` a trouvé dix apostrophes non échappées dans sept ressources ajoutées/complétées pour R2C.5. Elles ont toutes été échappées, sans changer le texte affiché par Android. Les séquences Unicode existantes `\u2014` et `\u2026` sont syntaxiquement valides ; aucune séquence `\u` mal formée n'a été trouvée dans l'archive R2C.5 examinée.

## 3. Correction

Dans `app/src/main/res/values/strings.xml` :

```xml
<string name="gpx_recovery_state_not_synchronized">L\'état de récupération n\'a pas pu être synchronisé. Les fichiers et journaux sont conservés.</string>
```

Les autres apostrophes non échappées détectées dans les chaînes `retention_*` ont été corrigées de la même manière.

## 4. Prévention ajoutée

`tools/verify_android_project.py` contient désormais un contrôle des textes de ressources Android :

- rejette les apostrophes non échappées hors d'une plage entre guillemets doubles ;
- valide la présence de quatre chiffres hexadécimaux après chaque `\u` ;
- rejette les échappements Unicode isolés dans la plage surrogate ;
- inclut sept auto-tests couvrant les cas valides et invalides.

Le workflow CI exécutait déjà `tools/verify_android_project.py` : aucun changement du workflow n'est nécessaire pour activer ce contrôle.

Ce contrôle est une prévention statique ; il ne remplace pas AAPT2 ni `:app:assembleDebug`.

## 5. Fichiers livrés

1. `app/src/main/res/values/strings.xml` — échappement des dix apostrophes.
2. `tools/verify_android_project.py` — scanner et auto-tests AAPT2.
3. `README.md` — note de correction.
4. `docs/LOT-8C-R2C5-CORRECTIF-AAPT-APOSTROPHES-2026-11-25.md` — présente note.
5. `docs/DOSSIER-REPRISE-ULTRA-EXHAUSTIF-2026-11-25-MAJ14.md` — reprise mise à jour.

## 6. Vérifications exécutées par l'assistant

- `python3 -m py_compile tools/*.py` : réussi.
- `python3 tools/verify_android_project.py` : réussi, 18 XML et 97 sources Java ; échappements de chaînes AAPT2 valides.
- `python3 tools/verify_offline_map_architecture.py` : réussi, 116 fichiers.
- `verify_gpx_async_open.py`, `verify_gpx_checkpoint.py`, `verify_gpx_recovery.py`, `verify_gpx_recovery_publication.py`, `verify_gpx_recovery_retry.py`, `verify_gpx_recovery_retention.py`, `verify_gpx_recovery_management.py` : tous réussis.
- Tests JVM : 51 suites, 1 740 assertions, 0 échec.

Aucune compilation Gradle/Android n'a été relancée par l'assistant, conformément à la consigne de l'utilisateur. Le statut de compilation de R2C.5 après ce correctif reste donc **à confirmer par l'utilisateur**.

## 7. Intégration

Décompresser l'archive « fichiers à remplacer » à la racine du projet GPS-Test et accepter les remplacements. Le lot ne modifie pas les sources Java de l'application ni la logique GNSS/GPX ; il corrige les ressources et ajoute la prévention statique.

Aucun fichier GitHub n'a été modifié par l'assistant.
