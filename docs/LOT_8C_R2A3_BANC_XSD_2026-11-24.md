# LOT 8C-R2A.3 — Banc de validation GPX par schémas XSD

**Date :** 24 novembre 2026  
**Base :** archive complète `GPS-Test-LOT-8C-R2A2-PROJET-COMPLET-2026-11-24.zip`  
**SHA-256 de l'archive de base :** `4b854676f813d69369594e33de374f23ceabfaf4eef0ab0935704511191c68eb`  
**État GitHub :** aucun dépôt distant modifié. Les fichiers de ce lot sont remis à l'utilisateur pour remplacement manuel.

## 1. Objet

R2A.1 a corrigé l'ordre des enfants `trkpt` en GPX 1.0. R2A.2 a durci les valeurs numériques connues, la préservation et le parseur. Les tests JVM existants contrôlent déjà de nombreux fragments et cas métier, mais ils ne constituaient pas encore une validation du document complet émis par `GpxWriter` contre les schémas GPX.

R2A.3 ajoute un banc autonome qui :

- télécharge uniquement les schémas Topografix GPX 1.0 et GPX 1.1 depuis leurs URL canoniques HTTPS ;
- vérifie avant usage la racine XSD, le `targetNamespace`, la déclaration globale `gpx` et la version fixe attendue ;
- compile les schémas avec le validateur XML du JDK 17, sans dépendance Java externe ;
- valide des documents complets réellement construits avec `GpxWriter.build()` ;
- vérifie aussi que le validateur refuse deux documents volontairement invalides.

## 2. Fichiers ajoutés ou modifiés

Ajouts :

- `tools/download_gpx_schemas.py` — téléchargement HTTPS et contrôle de cohérence des deux XSD ;
- `tools/GpxXsdValidation.java` — construction et validation des documents par `SchemaFactory` / `Validator` JAXP ;
- `tools/validate_gpx_xsd.py` — compilation du validateur et exécution du banc ;
- `docs/LOT_8C_R2A3_BANC_XSD_2026-11-24.md` — présente note.

Modifications :

- `.github/workflows/android-build.yml` — deux étapes avant les tests JVM : télécharger les schémas Topografix, puis valider les sorties GPX ;
- `.gitignore` — ignore `tools/xsd/`, répertoire généré par téléchargement et non versionné.

Aucun fichier de l'application (`app/src/main`) ni de logique d'acquisition GNSS, journal R1 ou sérialisation GPX existante n'a été modifié dans ce sous-lot.

## 3. Utilisation locale

À la racine du projet :

```bash
python3 tools/download_gpx_schemas.py
python3 tools/validate_gpx_xsd.py
```

Le premier script crée `tools/xsd/gpx-1.0.xsd` et `tools/xsd/gpx-1.1.xsd`. Ces fichiers sont ignorés par Git, car ils sont récupérés depuis Topografix à chaque exécution CI. Le second compile le programme Java de validation et lance les contrôles.

Le téléchargement nécessite un accès HTTPS aux domaines Topografix. Si le réseau est indisponible, la validation XSD s'arrête explicitement avec un code d'erreur ; aucun contrôle de substitution n'est présenté comme équivalent au XSD officiel.

## 4. Documents contrôlés

Le banc crée un point avec :

- latitude et longitude valides ;
- altitude finie ;
- horodatage UTC connu ;
- vitesse, cap, HDOP et précision renseignés ;
- cap à `360.0` degrés pour exercer la borne inclusive GPX 1.0.

Puis il valide :

1. la sortie complète GPX 1.0 produite par `GpxWriter.build()` ;
2. la sortie complète GPX 1.1 produite par `GpxWriter.build()` ;
3. un GPX 1.0 volontairement incorrect, où `hdop` est déplacé avant `course` et `speed` ;
4. un GPX 1.1 volontairement incorrect, où la vitesse est déplacée du namespace d'extension vers un élément GPX direct non autorisé.

Le test négatif est essentiel : il vérifie que le banc détecte bien une non-conformité et ne se contente pas de déclarer les XML bien formés « valides ».

## 5. Intégration CI

Le workflow Android ajoute ces étapes :

```yaml
- name: Download official Topografix GPX schemas
  run: python3 tools/download_gpx_schemas.py

- name: Validate GPX 1.0 and 1.1 output against XSD
  run: python3 tools/validate_gpx_xsd.py
```

Elles précèdent les tests JVM habituels. Une indisponibilité du service Topografix fait échouer explicitement le job ; cela évite de masquer une absence de schéma.

## 6. Résultats exécutés dans la présente session

- Tests JVM sur le projet R2A.2 : **39 suites, 1 368 assertions réussies, 0 échec**.
- Vérification statique Android : **OK** — 17 XML et 80 sources Java analysés.
- Vérification cartographie hors ligne : **OK** — 98 fichiers analysés.
- Compilation de `tools/GpxXsdValidation.java` avec `javac` : **OK**.
- Compilation syntaxique Python des deux scripts : **OK**.
- Analyse syntaxique YAML du workflow : **OK** — jobs `android` et `gpx-xsd` présents.
- Téléchargement des schémas officiels : **échec réseau contrôlé** (`Temporary failure in name resolution`) ; la résolution DNS sortante est bloquée dans l'environnement de préparation.
- Validation des quatre cas contre les XSD Topografix : **en attente d'exécution** sur un environnement avec accès HTTPS aux schémas. Le harnais a été testé séparément contre deux petits XSD temporaires couvrant uniquement le sous-ensemble utilisé par le générateur : 4 contrôles sur 4 passent dans ce test de fonctionnement du harnais. Ces schémas temporaires ne sont pas les XSD Topografix et ne constituent pas une preuve de conformité GPX officielle.
- Compilation Android : non concernée par les changements de ce lot ; la compilation R2A.2 a été confirmée par l'utilisateur avant R2A.3.

Il ne faut donc pas annoncer que des fichiers GPX ont déjà passé les schémas officiels dans cette session. Le banc est préparé et intégré au workflow, mais son résultat XSD réel sera fourni par le prochain run CI ou par l'exécution locale après téléchargement.

## 7. Références

- Topografix GPX 1.1 schema : `https://www.topografix.com/GPX/1/1/gpx.xsd`
- Topografix GPX 1.0 schema : `https://www.topografix.com/GPX/1/0/gpx.xsd`
- Documentation du schéma GPX 1.1 : `https://www.topografix.com/GPX/1/1/`
- Manuel Topografix GPX 1.0 : `https://www.topografix.com/gpx_manual.asp`

**Statut :** banc XSD ajouté et compilé côté Java ; exécution contre les XSD officiels à effectuer sur un environnement réseau. Aucun changement n'a été poussé sur GitHub.
