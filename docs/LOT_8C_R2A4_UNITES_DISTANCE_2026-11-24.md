# LOT 8C-R2A.4 — Validation des distances affichées

**Date :** 24 novembre 2026  
**Base :** archive complète `GPS-Test-LOT-8C-R2A3-BANC-XSD-PROJET-COMPLET-2026-11-24.zip`  
**État GitHub :** aucun dépôt distant modifié. Les fichiers sont remis pour remplacement manuel.

## 1. Objet

Ce sous-lot corrige un défaut localisé dans `Units.formatDistance(System, double)` : les valeurs négatives étaient converties et affichées comme des distances valides. Cela pouvait produire `-12 m`, `-1,0 mi` ou `-1,0 NM`, alors qu'une distance de trace négative n'est pas une mesure exploitable.

La méthode renvoie désormais le tiret cadratin `—` pour :

- toute distance strictement négative ;
- `NaN` ;
- `+Infinity` et `-Infinity`.

La valeur `-0.0` est normalisée en zéro positif, pour ne pas afficher `-0 m`. Les distances valides, conversions, unités et règles de précision existantes ne changent pas.

## 2. Fichiers modifiés

- `app/src/main/java/com/example/gpstest/Units.java`
- `tests/ModesAndPostProcessTest.java`

Aucun changement apporté au GNSS, au GPX, au journal durable R1, aux schémas XSD, au manifeste, aux ressources ou à la configuration Gradle.

## 3. Tests ajoutés

Six assertions de régression couvrent :

1. distance métrique négative ;
2. distance impériale négative ;
3. distance nautique négative ;
4. distance `+Infinity` ;
5. distance `-Infinity` ;
6. `-0.0` affiché comme `0 m`.

Les tests historiques restent présents : zéro, `NaN`, 20 m, 999 m, 1 000 m, 12 345 m et conversion impériale.

## 4. Résultats exécutés dans l'environnement de préparation

Commande :

```bash
python3 tools/run_jvm_tests.py
```

Résultat : **39 suites, 1 374 assertions réussies, 0 échec**.

Commandes d'audit :

```bash
python3 tools/verify_android_project.py
python3 tools/verify_offline_map_architecture.py
```

Résultats :

- Android statique : **OK**, 17 XML, 80 sources Java, ressources R, manifeste, permissions, service de premier plan, SDK et Edge-to-Edge cohérents ;
- cartographie : **OK**, 98 fichiers analysés, aucun moteur/fournisseur raster, repli local et sélecteur local présents.

La compilation Android Gradle n'a pas été exécutée dans cet environnement : Gradle est absent et le Gradle Wrapper n'est pas versionné dans l'archive. Le contrôle d'environnement se termine explicitement avec le message demandant Gradle 9.9.2 ou l'artefact `gradle-wrapper-bootstrap` du workflow GitHub Actions.

La compilation Android précédemment confirmée par l'utilisateur concerne le lot R2A.3/R2A.2 et n'est pas présentée ici comme une nouvelle compilation de R2A.4.

## 5. Installation dans le dépôt local

Décompresser l'archive `GPS-Test-LOT-8C-R2A4-FICHIERS-A-REMPLACER-2026-11-24.zip` **à la racine du projet GPS-Test**, en conservant l'arborescence. Accepter le remplacement des deux fichiers listés ci-dessus. Aucun patch Git à appliquer.

Puis compiler le projet Android dans Android Studio comme d'habitude et exécuter les tests JVM avec la commande ci-dessus. Aucun changement à pousser automatiquement : l'utilisateur garde la main sur le dépôt et la publication.

## 6. Étape suivante

Le sujet prioritaire suivant est l'ouverture de segment GPX asynchrone. Dans la base R2A.3, `startRecording()` et `resumeTracking()` appellent encore `openRecordingSegment()` alors qu'ils détiennent `gnssProcessingLock`. Cette méthode crée le `FileOutputStream`, écrit l'entête et fait `flush()` synchroniquement. Les points sont déjà envoyés sur la FIFO `ioFile`.

La conception suivante devra déplacer ouverture + entête dans cette même FIFO sous forme de barrière, sans permettre à un point de passer avant l'entête. Elle devra aussi couvrir :

- l'état transitoire « ouverture en attente » ;
- l'arrêt ou la suspension avant la fin de l'ouverture ;
- l'échec de création (stockage indisponible/plein/permission) ;
- les compteurs accepted / queued / written / failed ;
- l'ordre avec fermeture, foot GPX, `flush()` et `FileDescriptor.sync()` ;
- les écritures retardées appartenant à un ancien segment ;
- le journal de session et le futur `safeLengthBytes` réel.

Ne pas remplacer globalement `TrackingService.java` par un snapshot antérieur. Il faut modifier sélectivement la base R2A.3/R1 et garder le journal durable existant.
