# LOT 8C-R2A.1 — Ordre XSD des champs de points GPX 1.0

**Date :** 24 novembre 2026  
**Base technique :** archive complète `ETAPE_15.zip`  
**SHA-256 base :** `9d476a3db724ec5a2cf5018d6d071137a00f6e8816796f8654ce8b889e3c301d`  
**Statut :** correctif ciblé appliqué dans une copie candidate ; tests JVM et audits statiques réussis ; comparaison au `main` distant et build Android restent à faire.

## 1. But du sous-lot

Corriger l'ordre des éléments XML directs émis par `GpxWriter.pointXml(..., Version.V10)` pour respecter l'ordre XSD GPX 1.0, sans modifier la structure GPX 1.1.

Ce sous-lot est volontairement limité. Il ne clôt pas l'ensemble du chantier de conformité GPX de LOT 8C-R2A : validation lexicale `xsd:decimal`, bornes des champs, validation par les XSD complets et autres champs GPX optionnels restent à traiter séparément.

## 2. Défaut constaté sur la base ETAPE_15

Avant correction, le writer partageait l'émission de `<hdop>` entre les deux versions puis, en GPX 1.0, émettait `<speed>` avant `<course>`.

L'ordre constaté était donc :

```xml
<ele>...</ele>
<time>...</time>
<hdop>...</hdop>
<speed>...</speed>
<course>...</course>
```

Ce XML peut être bien formé au sens XML, mais l'ordre des enfants ne respecte pas la séquence attendue par GPX 1.0 lorsque plusieurs champs optionnels sont présents.

Le schéma GPX 1.0 permet `<course>` et `<speed>` en enfants directs du `trkpt`. L'ordre des points de trace suit la séquence `ele`, `time`, `course`, `speed`, puis les champs descriptifs et les champs de précision (`sat`, `hdop`, etc.). Références : manuel Topografix GPX 1.0 et présentation de la séquence du schéma GPX 1.0 par GPS-Wizard.

## 3. Correction réalisée

### GPX 1.0

`GpxWriter.pointXml()` traite désormais explicitement la version 1.0 et écrit, parmi les champs actuellement produits par l'application :

1. `<ele>` si altitude disponible ;
2. `<time>` si timestamp UTC valide selon la logique déjà présente ;
3. `<course>` si disponible ;
4. `<speed>` si disponible et non négative ;
5. `<hdop>` si disponible.

La branche se termine ensuite comme auparavant. Aucun bloc `<extensions>` n'est ajouté en GPX 1.0.

### GPX 1.1

La branche 1.1 continue d'écrire :

1. `<ele>` si disponible ;
2. `<time>` si disponible ;
3. `<hdop>` si disponible ;
4. `<extensions>` lorsque vitesse, cap ou accuracy existent.

Les noms d'éléments et namespace d'extension ne sont pas changés.

## 4. Fichiers modifiés

- `app/src/main/java/com/example/gpstest/GpxWriter.java`
- `tests/AdvancedTest.java`

Deux assertions ont été ajoutées :

- l'ordre GPX 1.0 `course < speed < hdop` ;
- la présence de `hdop` avant `<extensions>` en GPX 1.1.

Aucun autre fichier source applicatif n'a été modifié dans cette copie candidate.

## 5. Vérifications exécutées

Sur la copie candidate :

| Vérification | Résultat |
|---|---:|
| Suites JVM | 39 réussies |
| Assertions JVM | 1 347 réussies |
| Échecs | 0 |
| Audit statique Android | 17 XML, 80 sources Java ; OK |
| Audit cartographique | 98 fichiers ; aucun moteur/fournisseur raster ; OK |

La suite `AdvancedTest` passe de 113 à 115 assertions. L'augmentation de deux assertions correspond aux deux contrôles d'ordre ajoutés.

### Limites explicites

- Aucun validateur n'a chargé ici le XSD officiel complet GPX 1.0/1.1 : les assertions testent l'ordre des fragments générés, elles ne prétendent pas remplacer une validation XSD intégrale.
- Aucun `assembleDebug`, `lintDebug`, test instrumenté ou test sur appareil n'a été exécuté. L'environnement ne dispose pas de Gradle, du Gradle Wrapper versionné ni du SDK Android configuré.
- La copie candidate est basée sur l'archive ETAPE_15 ; le SHA actuel du `main` distant n'a pas pu être vérifié dans cette session.

## 6. Mode d'intégration recommandé

1. Vérifier le SHA courant de `main` et le comparer à la base réellement utilisée par le dépôt.
2. Si la base a évolué, appliquer le changement de façon sélective sur `GpxWriter.java` et intégrer les deux assertions dans le test équivalent, sans remplacer les fichiers complets par ceux de cette archive.
3. Exécuter `bash tools/run_jvm_tests.sh`.
4. Exécuter `python3 tools/verify_android_project.py` et `python3 tools/verify_offline_map_architecture.py`.
5. Faire tourner la CI Android sur le commit candidat et vérifier séparément l'assemblage APK et Android Lint.
6. Ajouter ensuite une validation XSD complète GPX 1.0/1.1, avec schémas épinglés dans les tests ou une dépendance contrôlée, avant de considérer LOT 8C-R2A terminé.

## 7. Hors périmètre / étapes suivantes

Ce sous-lot ne corrige pas encore :

- la validation lexicale stricte des valeurs `xsd:decimal` ;
- les formats de secours `Double.toString()` pouvant produire une notation exponentielle ;
- la plage de validité de `course` et le cas frontière `360` lors de la préservation ;
- la validation des champs optionnels GPX non encore couverts ;
- les gardes `(0,0)` aux frontières génériques d'écriture GPX ;
- la synchronisation des points et le checkpoint durable R2 ;
- l'ouverture du segment GPX dans la FIFO d'écriture.

Ne pas fusionner le ZIP de modifications locales pré-R1 en bloc pour réaliser ces tâches.
