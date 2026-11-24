# LOT 8C-R2A.2 — Validation des valeurs numériques GPX

**Date :** 24 novembre 2026  
**Base de travail :** ETAPE_15 / LOT 8C-R1, avec le correctif R2A.1 relatif à l'ordre GPX 1.0 déjà présent dans cette copie candidate.  
**État GitHub :** aucun fichier n'a été modifié dans le dépôt distant. Les changements restent dans la copie de préparation et sont remis directement à l'utilisateur.

## 1. Objectif

Fiabiliser la lecture, la préservation et la réécriture des valeurs numériques GPX, sans :

- accepter une notation numérique interdite par `xsd:decimal` (par exemple `1e-10`) ;
- accepter `NaN`, `INF` ou `Infinity` comme valeurs GPX décimales ;
- réécrire involontairement un champ standard invalide ;
- interpréter une extension d'un autre fournisseur comme une mesure GPS-Test ;
- perdre les extensions de tiers qui ne sont pas connues de l'application ;
- émettre une notation scientifique dans les solutions de repli numériques du writer ;
- écrire des coordonnées ou mesures explicitement interdites par les règles métier du projet.

Ce lot complète R2A.1 : il ne remplace ni le journal durable R1 ni le chemin d'acquisition GNSS.

## 2. Changements de code

### 2.1 `app/src/main/java/com/example/gpstest/GpxWriter.java`

**Ordre GPX 1.0 (R2A.1 conservé dans cette livraison).** `pointXml()` écrit maintenant les champs directs dans l'ordre attendu :

1. `ele` si disponible ;
2. `time` si disponible ;
3. `course` ;
4. `speed` ;
5. `hdop` après les champs précédents.

Pour GPX 1.1, `hdop` reste avant `extensions` et `speed`, `course` et `accuracy` restent dans le namespace GPS-Test de l'extension.

**Format décimal de secours.** `safeElevationText()` et `safeCoordinateText()` préservent un texte source uniquement s'il correspond déjà à une forme décimale admissible et correspond à la valeur numérique. Sinon, `plainDecimal()` utilise `BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()`. La solution de repli ne produit donc pas `1.0E-10`.

**Dernière barrière d'écriture.** `pointXml()` refuse de sérialiser un point si :

- l'objet point est absent ;
- la latitude ou longitude n'est pas finie ;
- latitude est hors de [-90, 90] ou longitude hors de [-180, 180] ;
- la position est exactement (0,0), interdite par la politique de GPS-Test ;
- la version GPX n'est pas fournie.

Les champs facultatifs sont émis uniquement si leur valeur est finie et dans les limites métier :

- altitude : finie et comprise entre -10 000 m et +10 000 m inclus ; sinon omission de `ele` ;
- vitesse : finie et non négative ;
- cap : fini et entre 0 et 360 degrés inclus ;
- HDOP : fini et non négatif ;
- précision GPS-Test : finie et non négative.

Une coordonnée invalide provoque une `IllegalArgumentException` au niveau du writer, plutôt que l'écriture d'une fausse coordonnée XML. Dans le worker de suivi, cette erreur remonte au chemin d'échec d'écriture déjà existant et doit être visible dans les compteurs/erreurs GPX.

### 2.2 `app/src/main/java/com/example/gpstest/GpxParser.java`

Le parseur numérique s'aligne désormais sur la forme lexicale `xsd:decimal` : signe facultatif, chiffres et séparateur décimal point uniquement ; pas de notation exponentielle, `NaN`, `INF` ou `Infinity`.

Cette règle s'applique aux coordonnées lat/lon et aux scalaires numériques analysés par le parseur. Une coordonnée à notation exponentielle est comptée comme point invalide, et un scalaire numérique non admissible n'est pas transformé en mesure.

Le parseur ne capture `speed` et `course` comme enfants directs de `trkpt` que pour GPX 1.0. En GPX 1.1, il ne lit ces mesures que depuis le namespace GPS-Test exact `https://example.org/gpstest/ext`.

Compatibilité historique : certains GPX 1.0 sont rencontrés sans namespace XML. Le parseur reconnaît ce cas uniquement lorsque la racine porte explicitement `version="1.0"`, puis conserve la lecture des `speed` et `course` directs.

### 2.3 `app/src/main/java/com/example/gpstest/GpxPreservation.java`

La préservation valide maintenant sélectivement les champs numériques standards connus d'un point, selon la version identifiée par le namespace racine ou, pour les documents sans namespace, selon l'attribut `version` explicite.

| Champ connu | GPX 1.0 | GPX 1.1 |
|---|---|---|
| `speed` direct | décimal, non négatif | supprimé : enfant direct non conforme |
| `course` direct | 0 à 360 inclus | supprimé : enfant direct non conforme |
| `magvar` | décimal signé (variation magnétique, valeurs négatives admises) | 0 inclus à 360 exclu |
| `geoidheight` | décimal signé | décimal signé |
| `hdop`, `vdop`, `pdop`, `ageofdgpsdata` | décimal non négatif (contrôle métier en plus du type décimal) | même contrôle |
| `sat` | entier non négatif | entier non négatif |
| `dgpsid` | entier de 0 à 1023 | entier de 0 à 1023 |
| `fix` | `none`, `2d`, `3d` | `none`, `2d`, `3d`, `dgps`, `pps` |
| extension GPS-Test `speed`, `accuracy` | non utilisée par cette règle GPX 1.0 | décimal non négatif, namespace exact |
| extension GPS-Test `course` | non utilisée par cette règle GPX 1.0 | 0 à 360 inclus, namespace exact |

Le type `xsd:decimal` n'impose pas à lui seul une limite métier à plusieurs de ces champs. Les contraintes de non-négativité appliquées à DOP, âge, vitesse et précision sont donc aussi des contrôles de cohérence des mesures, pas uniquement des contraintes lexicales XSD.

Les éléments dans le namespace GPS-Test sont reconnus seulement sous `https://example.org/gpstest/ext`. Les éléments d'extensions qui appartiennent à un autre namespace ne sont ni interprétés ni supprimés par cette validation, même s'ils s'appellent `speed`, `course` ou contiennent `NaN` / `1e2`.

Les valeurs scalaires connues sont bornées en taille par `GpxParser.MAX_SCALAR_CHARS`. La validation utilise `BigDecimal` et `BigInteger`, évitant de faire dépendre les contrôles de plage des arrondis ou dépassements de `double`.

Les coordonnées préservées utilisent aussi la forme lexicale décimale stricte, puis les contrôles de plage et la règle projet interdisant (0,0).

### 2.4 Tests

- `tests/AdvancedTest.java` : ordre GPX 1.0, ordre GPX 1.1 et vérifications de la dernière barrière writer (coordonnées invalides, (0,0), altitude hors limites, scalaires non finis/négatifs).
- `tests/GpxPreservationTest.java` : champs scalaires GPX 1.0/1.1, limites de course/magvar/DGPS, champs invalides, préservation des extensions tierces, cas sans namespace, repli décimal sans exposant.
- `tests/SamplingAndParsingTest.java` : lecture GPX 1.0 namespacé et historique sans namespace, ignorance des `speed`/`course` directs en GPX 1.1, lecture de l'extension GPS-Test exacte, rejet de coordonnées/scalaires exponentiels et de `NaN`.

## 3. Résultats réellement exécutés

| Vérification | Résultat |
|---|---|
| Tests JVM | **39 suites, 1 368 assertions, 0 échec** |
| Vérification statique Android | **OK** — 17 XML et 80 sources Java analysées ; cohérence ressources R, manifeste, permissions, FGS, SDK et Edge-to-Edge |
| Vérification architecture cartes hors ligne | **OK** — 98 fichiers analysés ; aucun moteur/fournisseur raster détecté ; sélecteur et repli locaux présents |
| Environnement de compilation Android | **Non disponible** — Gradle absent et Gradle Wrapper non versionné dans la base fournie |
| `assembleDebug`, Android Lint, tests instrumentés, appareil physique/OEM | **Non exécutés dans cette session** |
| Validation exhaustive des fichiers émis avec le XSD officiel | **Pas encore exécutée** ; prévue dans une passe de validation dédiée |

Les tests JVM et les vérifications statiques ne remplacent pas une compilation Android, Android Lint ou un test réel sur téléphone.

## 4. Fichiers modifiés par rapport à ETAPE_15

Fichiers de code/test :

- `app/src/main/java/com/example/gpstest/GpxWriter.java`
- `app/src/main/java/com/example/gpstest/GpxPreservation.java`
- `app/src/main/java/com/example/gpstest/GpxParser.java`
- `tests/AdvancedTest.java`
- `tests/GpxPreservationTest.java`
- `tests/SamplingAndParsingTest.java`

Documentation ajoutée :

- `docs/LOT_8C_R2A1_GPX10_ORDRE_XSD_2026-11-24.md`
- `docs/LOT_8C_R2A2_GPX_SCALAIRES_2026-11-24.md`
- `LISEZ_MOI_LOT_8C_R2A2.md` à la racine du projet.

Aucun changement n'est fait dans `MainActivity.java`, `TrackingService.java`, `SessionJournal.java` ou `SessionStore.java`. Le chemin de journal durable R1 reste donc intact.

## 5. Limites et points à surveiller

1. Le namespace d'extension GPS-Test reste `https://example.org/gpstest/ext`, hérité de la base. Il doit être remplacé par un URI permanent contrôlé par le projet avant publication, avec stratégie de compatibilité pour les anciens GPX.
2. Les règles de plage métier de `GpxPreservation` sont plus strictes que le seul type `xsd:decimal` pour plusieurs champs. Ne pas les présenter comme les seules contraintes imposées par le XSD.
3. Les champs inconnus d'extensions tierces restent préservés sans validation spécifique : c'est intentionnel pour éviter de détruire des données d'autres applications.
4. La validation contre les schémas XSD officiels GPX 1.0 et 1.1, y compris des exemples complets avec tous les éléments optionnels, reste à faire.
5. Le contrôle des coordonnées du writer est une dernière protection. Le filtrage principal doit rester en amont, de sorte qu'un point GNSS invalide n'atteigne normalement jamais le worker d'écriture.
6. Le LOT 8C-R1 conserve encore `safeLengthBytes=0` ; le checkpoint réel après flush + `FileDescriptor.sync()` et l'index des sessions interrompues appartiennent à la suite R2, pas à ce sous-lot.

## 6. Références de spécification

- GPX 1.1 XSD officiel : <https://www.topografix.com/GPX/1/1/gpx.xsd>
- GPX 1.0 manuel/définition de format Topografix : <https://www.topografix.com/gpx_manual.asp>
- W3C XML Schema Part 2 — Datatypes (`decimal`, `integer`) : <https://www.w3.org/TR/xmlschema-2/>

---

**Statut :** correctif local candidat testé en JVM et statiquement vérifié, prêt à être repris manuellement par l'utilisateur dans son dépôt après confirmation de l'identifiant exact de son `main`. Il n'a pas été poussé sur GitHub et ne doit pas être considéré comme fusionné.
