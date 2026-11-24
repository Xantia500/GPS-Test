# GPS-Test — Livraison LOT 8C-R2A.2

## Tu n'as pas à appliquer de patch Git

Cette livraison contient **le projet complet**, pas seulement un diff. Elle a été préparée dans une copie séparée de la base ETAPE_15, avec R2A.1 + R2A.2. Aucun fichier n'a été modifié sur GitHub.

## Deux façons de récupérer le travail

1. **Pour examiner ou compiler la livraison complète :** décompresser cette archive dans un nouveau dossier de travail et ouvrir le dossier `GPS-Test` avec Android Studio.
2. **Pour reporter uniquement les changements dans ton dépôt actuel :** remplacer les six fichiers listés ci-dessous par les versions livrées, en conservant exactement leur arborescence. Ne pas utiliser `git apply`, ne pas lancer de commande de patch.

Avant de remplacer les fichiers de ton dépôt, vérifier que ton `main` correspond bien à la base ETAPE_15 utilisée ici. L'accès distant GitHub n'a pas permis de confirmer le commit actuel dans cette session.

## Fichiers de code/tests à remplacer

- `app/src/main/java/com/example/gpstest/GpxWriter.java`
- `app/src/main/java/com/example/gpstest/GpxPreservation.java`
- `app/src/main/java/com/example/gpstest/GpxParser.java`
- `tests/AdvancedTest.java`
- `tests/GpxPreservationTest.java`
- `tests/SamplingAndParsingTest.java`

Les deux documents de lot associés sont dans `docs/` :

- `LOT_8C_R2A1_GPX10_ORDRE_XSD_2026-11-24.md`
- `LOT_8C_R2A2_GPX_SCALAIRES_2026-11-24.md`

## Ce que corrigent ces fichiers

- Ordre XSD des champs GPX 1.0 dans `trkpt` ; GPX 1.1 garde les champs de mesure spécifiques dans les extensions.
- Rejet des valeurs `xsd:decimal` lexicalement invalides (`1e-10`, `NaN`, `Infinity`) pour les coordonnées et scalaires connus.
- Validation des champs GPX connus selon la version, sans supprimer les extensions de tiers inconnues.
- Compatibilité lecture/préservation pour GPX 1.0 historiques sans namespace et filtrage défensif pour les GPX 1.1 sans namespace portant `version="1.1"`.
- Valeurs de secours décimales sans notation scientifique.
- Dernière barrière writer pour les coordonnées, (0,0), altitudes et scalaires facultatifs.

## Tests exécutés

- Tests JVM : **39 suites, 1 368 assertions, 0 échec**.
- Vérification statique Android : OK (17 XML, 80 Java, ressources, manifeste et configuration cohérents).
- Vérification architecture cartographique hors ligne : OK (98 fichiers, aucun moteur raster).
- Aucune compilation Android (`assembleDebug`), Lint ou validation sur appareil n'a été exécutée : Gradle est absent et le Gradle Wrapper n'est pas versionné dans la base locale.

## Important

Cette livraison n'est pas fusionnée sur GitHub. `MainActivity.java`, `TrackingService.java`, `SessionJournal.java` et `SessionStore.java` n'ont pas été remplacés : leur logique R1 reste intacte. Le checkpoint `safeLengthBytes` réellement synchronisé et la récupération de sessions après crash ne sont pas encore implémentés.


## Addendum — LOT 8C-R2A.3 (banc XSD)

Le projet contient maintenant un banc de validation XSD GPX 1.0/1.1. Depuis la racine, lancer :

```bash
python3 tools/download_gpx_schemas.py
python3 tools/validate_gpx_xsd.py
```

Le téléchargement utilise les deux URL canoniques Topografix et demande un accès HTTPS. GitHub Actions exécute ces étapes avant les suites JVM. Voir `docs/LOT_8C_R2A3_BANC_XSD_2026-11-24.md` pour les détails et les résultats de préparation.
