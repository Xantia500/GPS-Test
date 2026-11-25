# GPS-Test Android — Dossier de reprise ultra exhaustif

**Référence de passation autonome pour les prochaines conversations**  
**État consolidé : 25 novembre 2026 — Europe/Paris — MAJ13 après LOT 8C-R2C.5 (écran consultatif de gestion des copies récupérées ; compilation Android R2C.5 à effectuer par l'utilisateur)**  
**Projet :** application Android native GPS-Test, Java/XML  
**Dépôt GitHub connu :** <https://github.com/Xantia500/GPS-Test> — branche de travail annoncée `main`  
**Base historique de la lignée :** `ETAPE_15.zip` — LOT 8C-R1  
**Base immédiate de R2C.5 :** `GPS-Test-LOT-8C-R2C4-PROJET-COMPLET-2026-11-25.zip` — SHA-256 `2ba488db6e4afa19ab6d4a51b9fd94c958e04f7f81069ee97235947d510b713b`  
**Document remplacé / historique :** `GPS-Test_DOSSIER_REPRISE_EXHAUSTIF_2026-11-24.md` (ancienne version centrée sur ETAPE_13 / LOT 8B)

> **MAJ13 :** le lot le plus récent est R2C.5 (écran consultatif de gestion des copies récupérées). La section 41 prévaut pour l'état opérationnel ; la section 40 décrit R2C.4 et les sections antérieures restent l'historique technique.

> **Mission de ce dossier :** permettre à une nouvelle conversation de reprendre GPS-Test sans perdre les choix du projet, les fondations acquises, les problèmes historiques déjà corrigés, l'état exact de la base, les audits récents, les changements locaux non intégrés, les limites de validation, les tâches restantes et la méthode de travail. Ce document est une synthèse technique de passation, non une transcription verbatim. Il sépare les éléments observés dans le code et les archives, les résultats effectivement exécutés dans l'environnement de préparation, les retours de l'utilisateur et les recommandations qui restent à décider.

> **Mise à jour de reprise — 24 novembre 2026, MAJ2 :** deux sous-lots GPX (R2A.1 et R2A.2) sont maintenant regroupés dans une livraison de projet complète dérivée d'ETAPE_15. Ils corrigent l'ordre XSD GPX 1.0, la validation lexicale des décimaux GPX, le filtrage versionné de scalaires connus, la préservation des extensions tierces, les derniers contrôles de sérialisation et la lecture des anciens GPX 1.0 sans namespace. Résultat actuel : 39 suites / 1 368 assertions, deux audits statiques OK. Pas de compilation Android/Lint/appareil, car Gradle n'est pas disponible. GitHub n'a pas été modifié et le SHA distant `main` n'a pas été vérifié. Les détails sont dans les sections 28 et 29.

 >  **Mise à jour MAJ7 — 24 novembre 2026 :** le LOT 8C-R2B.3 ajoute la récupération conservatrice des traces interrompues. Le moteur copie le préfixe synchronisé dans une copie distincte `*_RECUPEREE`, valide le XML avec SAX sécurisé et ne modifie jamais la source. La compilation R2B.3 a depuis été confirmée sans erreur par l’utilisateur. Voir section 35.

> **Mise à jour MAJ8 — 25 novembre 2026 :** le LOT 8C-R2B.4 durcit la récupération. `MainActivity` et `TrackingService` acquittent le snapshot attendu, et `GpxRecovery` compare par SHA-256 le préfixe source/copie même si taille et date sont identiques. Tests à la livraison : 43 suites / 1 513 assertions ; la compilation R2B.4 a ensuite été confirmée sans erreur par l'utilisateur. Voir section 36.

> **Mise à jour MAJ9 — 25 novembre 2026 :** le LOT 8C-R2C.1 ajoute le registre atomique `recovery.registry`, indépendant de `session.state`, avec jusqu'à 64 entrées non évictables indexées par `(sessionId, segmentGeneration)`. Il préserve les récupérations anciennes lors d'un nouveau START, prend en charge plusieurs segments d'une session et les checkpoints retardés, n'acquitte qu'un snapshot canonique complet, et conserve le journal courant s'il a évolué pendant la récupération. Tests : 44 suites / 1 581 assertions / 0 échec ; audits recovery/checkpoint/async/Android/cartographie réussis. La compilation Android R2C.1 a ensuite été confirmée sans erreur par l'utilisateur. Voir section 37.

> **Mise à jour MAJ10 — 25 novembre 2026 :** le LOT 8C-R2C.2 ajoute la publication SAF transactionnelle des copies GPX récupérées : registre séparé `recovery-publications.registry`, états `PREPARED` / `DOCUMENT_CREATED` / `VERIFIED`, écriture SAF puis relecture complète avec égalité longueur + SHA-256, reprise idempotente, conservation de la copie privée et refus conservateur des fichiers homonymes non vérifiés. Les notifications distinguent transfert en attente et journal à acquitter. La compilation Android R2C.2 a ensuite été confirmée par l'utilisateur. Voir section 38.

> **Mise à jour MAJ11 — 25 novembre 2026 :** R2C.3 ajoute la reprise persistante WorkManager des publications en attente, un bouton `RELANCER`, un diagnostic explicite des permissions/conflits/registres et une limite de cinq tentatives automatiques. La compilation Android R2C.3 a été confirmée sans erreur par l'utilisateur. Voir section 39.

 > **Mise à jour MAJ12 — 25 novembre 2026 :** R2C.4 ajoute `recovery-retention.registry`, `RecoveryRetentionStore` et `RecoveryRetentionPolicy`. Seules les copies locales `_RECUPEREE` dont la publication SAF a été relue et dont le snapshot de récupération a été acquitté sont suivies. Durée minimale 30 jours ; seuil indicatif 512 Mio ; registre limité à 128 entrées sans éviction. **Aucune suppression automatique n'est activée**. Tests : 50 suites / 1 715 assertions / 0 échec. La compilation Android R2C.4 a ensuite été confirmée par l'utilisateur. Voir section 40.

> **Mise à jour MAJ13 — 25 novembre 2026 :** R2C.5 ajoute un écran consultatif de gestion des copies récupérées, accessible depuis Réglages → Données enregistrées. L'écran lit le registre en arrière-plan, affiche dates/échéance/taille/destination/URI/SHA-256, contrôle sans écriture la présence, le confinement canonique et la longueur de la copie locale, ainsi que la permission SAF persistante. La vérification SAF est explicitement historique (à la publication), sans relecture live. Aucun fichier ni registre n'est supprimé ou modifié. Tests : 51 suites / 1 740 assertions / 0 échec ; audit statique Android 18 XML / 97 Java ; audit cartographique 116 fichiers ; contrats de la chaîne GPX tous réussis. La compilation Android R2C.5 est à effectuer par l'utilisateur ; l'assistant ne lance plus la compilation. Voir section 41.

 > **Règle de priorité documentaire :** la section 41 (MAJ13, R2C.5) prévaut désormais pour l'état opérationnel ; la section 40 décrit R2C.4, puis 39, 38 et 37 décrivent respectivement R2C.3, R2C.2 et R2C.1. Les sections antérieures restent l'historique. Les tests réels sur appareil, la campagne ADB et les fournisseurs SAF réels sont explicitement reportés ; ils restent à effectuer plus tard et ne sont pas présentés comme validés. Les résultats d'anciens lots ne s'additionnent jamais à ceux d'un lot ultérieur.

---

> **ÉTAT COURANT À LA REPRISE — MAJ13 / LOT 8C-R2C.5**  
> Base immédiate : projet complet R2C.4 (`GPS-Test-LOT-8C-R2C4-PROJET-COMPLET-2026-11-25.zip`, SHA-256 `2ba488db6e4afa19ab6d4a51b9fd94c958e04f7f81069ee97235947d510b713b`), dont la compilation Android a été confirmée sans erreur par l'utilisateur. La candidate R2C.5 ajoute un écran consultatif accessible depuis Réglages → Données enregistrées ; elle lit le registre de conservation hors du thread UI, présente les copies récupérées, les dates, tailles, destinations, URI et SHA-256 enregistrés, et contrôle localement confinement du chemin et longueur du fichier. Elle ne modifie ni les registres ni les fichiers et ne relit pas le contenu SAF à l'ouverture. Elle passe 51 suites JVM / 1 740 assertions et les audits statiques disponibles. **La compilation Android R2C.5 reste à effectuer par l'utilisateur** ; l'assistant ne lance plus de compilation Android. Aucun fichier GitHub n'a été écrit par l'assistant. Lire d'abord la section 41 et utiliser l'archive R2C.5 « fichiers à remplacer » à la racine du dépôt local. Les essais réels sur appareil/ADB et fournisseurs SAF restent reportés à plus tard, conformément à la demande de l'utilisateur. Le SHA distant `main` n'a pas été comparé.

---

# 0. Résumé exécutif — état exact à reprendre en premier

## 0.1 Où en est le projet ?

GPS-Test est une application Android native **Java + XML** de diagnostic GPS/GNSS, avec acquisition par `LocationManager` / `GPS_PROVIDER`, service de localisation au premier plan, mesures GNSS, écriture/lecture/nettoyage GPX et cartographie vectorielle Mapsforge hors ligne.

**État de développement au 25 novembre 2026 :** les lots R2A.1 à R2A.4 ont traité la conformité GPX et les unités ; R2B.1 a déplacé l'ouverture GPX sur la FIFO dédiée ; R2B.2 a rendu le checkpoint durable après `flush()` et `FileDescriptor.sync()` ; R2B.3 a ajouté la récupération conservatrice sur copie ; R2B.4 a renforcé l'intégrité de copie et l'acquittement ; R2C.1 ajoute le registre durable multi-sessions et multi-segments ; R2C.2 publie les copies récupérées dans le dossier SAF avec état transactionnel et vérification de lecture ; R2C.3 ajoute la reprise persistante ; R2C.4 enregistre les copies publiées acquittées et définit leur conservation minimale ; R2C.5 ajoute leur écran de gestion consultatif. La référence opérationnelle est la section 41.

Les compilations Android R2C.1, R2C.2, R2C.3 et R2C.4 ont été confirmées sans erreur par l'utilisateur. La candidate R2C.5 passe 51 suites JVM / 1 740 assertions et les audits statiques disponibles ; sa compilation Android reste à effectuer par l'utilisateur. L'utilisateur compile désormais systématiquement Android localement ; l'assistant ne lance plus ces compilations. Les essais réels Android/ADB et fournisseurs SAF ont été explicitement reportés pour une étape ultérieure. Aucun code GitHub n'a été modifié par l'assistant.

## 0.2 Base de code faisant autorité dans les fichiers disponibles

**Base historique de la lignée R1 :** `ETAPE_15.zip`. Elle n'est plus la base immédiate des modifications. La base immédiate de la candidate R2C.5 est le projet complet R2C.4, dont la compilation Android a été confirmée sans erreur par l'utilisateur. Son SHA-256 est `2ba488db6e4afa19ab6d4a51b9fd94c958e04f7f81069ee97235947d510b713b`. La candidate R2C.5 est une copie de travail dérivée de cette archive ; sa compilation Android reste à effectuer par l'utilisateur.

- SHA-256 de `ETAPE_15.zip` : `9d476a3db724ec5a2cf5018d6d071137a00f6e8816796f8654ce8b889e3c301d`.
- SHA-256 du correctif minimal R1 : `e3e8ed5537bf6f36c1a891a9ffd62393f89dd06388a86a593aa9e1aafefbbe3a`.
- SHA-256 de `ETAPE_14.zip` : `bc82d06f816cab69c6deb5f4ee7faa59c1fe969215eea825771d49996c1d35d2`.
- ETAPE_15 contient le code complet sous `GPS-Test/`, les documents PASSATION, les notes de lots, les tests et les outils. Son arborescence extraite ne contient pas `.git` ; l'archive ne prouve donc pas, à elle seule, le SHA actuellement présent sur GitHub `main`.
- Le correctif R1 est aussi livré séparément sous `GPS-Test-LOT-8C-R1-Journal-Durable-2026-11-24.zip`.

**Attention à GitHub :** le dépôt a été indiqué comme public par l'utilisateur. La tentative de lecture directe du dépôt depuis l'environnement de préparation a précédemment échoué (`Cache miss`). Cela ne prouve absolument pas que le dépôt est privé. Ne jamais prétendre que le SHA de `ETAPE_15.zip` correspond au `main` distant tant que cela n'a pas été comparé à un commit réel.

## 0.3 Résultats effectivement vérifiés sur ETAPE_15 dans cette session

Le tableau qui suit est le relevé historique des contrôles sur ETAPE_15/R1 ; il ne décrit pas les candidates ultérieures. Les résultats des lots récents sont consignés aux sections 35 à 41 ; la section 41 prévaut pour la candidate R2C.5.

| Contrôle | Résultat observé | Portée exacte |
|---|---:|---|
| `bash tools/run_jvm_tests.sh` | **39 suites, 1 345 assertions, 0 échec** | compilation/exécution de logique Java testable hors Android, avec stubs |
| `python3 tools/verify_android_project.py` | **17 XML, 80 sources Java** ; OK | contrôle statique ressources, manifeste, permissions, FGS, SDK, Edge-to-Edge |
| `python3 tools/verify_offline_map_architecture.py` | **98 fichiers** ; aucun fournisseur/moteur raster détecté | contrôle statique de l'architecture cartographique locale |

Le script de tests a régénéré les deux fichiers `R.java` factices via `tools/gen_r.py`, à partir des ressources XML. C'est prévu par le script ; ce n'est pas une modification fonctionnelle manuelle.

**Limite de l'exécution :** le conteneur dispose d'OpenJDK 21, mais pas de Gradle installé, pas de `gradlew` versionné dans l'archive et pas de SDK Android configuré (`ANDROID_HOME` / `ANDROID_SDK_ROOT` absents). Aucun `:app:assembleDebug`, `:app:lintDebug`, test instrumenté, essai `adb` ou test sur appareil n'a été exécuté ici. La configuration cible CI reste JDK 17. Le résultat JVM sous JDK 21 ne remplace pas la validation CI sous JDK 17 + SDK Android.

Une note R1 plus ancienne mentionne 1 344 assertions, tandis que `NOTE_ETAPE_15.md`, le README R1 et la relance réelle dans cette session donnent **1 345**. La relance effective de ce dossier fait foi pour le snapshot analysé : 1 345, zéro échec.

## 0.4 État réel de la compilation Android / GitHub Actions

- L'utilisateur a auparavant rapporté une compilation sans erreur pour une version antérieure du projet. Ce retour ne doit pas être automatiquement transféré au code ETAPE_15 / R1.
- La note officielle de passation ETAPE_15 indique expressément qu'aucune compilation Android ni exécution Lint n'a été réalisée dans l'environnement de préparation de R1, et demande une validation par GitHub Actions après intégration.
- **Derniers retours utilisateur :** les compilations Android R2C.1, R2C.2 et R2C.3 ont été confirmées sans erreur. Le retour utilisateur le plus récent confirme également R2C.3 ; R2C.4 est la candidate actuellement à compiler après remplacement. Par consigne explicite, l'assistant ne lance plus aucune compilation Android : l'utilisateur s'en charge.

**À reprendre :** vérifier le run `Android build and audit` correspondant exactement au SHA poussé sur `main`, et confirmer séparément `JVM regression tests`, `Assemble debug APK` et `Run Android Lint` ; aucun SHA distant actuel n’a été comparé dans cette session.
- Le workflow génère actuellement un Gradle Wrapper temporaire à partir de Gradle 9.9.2, puis publie ses fichiers comme artefact de bootstrap ; le wrapper n'est pas encore versionné dans ETAPE_15.

Ne jamais annoncer « build R1 vert » sans numéro de run, SHA et étapes vertes observés.

## 0.5 Configuration Android faisant autorité

| Composant | Valeur lue dans ETAPE_15 |
|---|---:|
| Gradle | 9.9.2 (installé/piloté par le workflow) |
| Android Gradle Plugin | 9.5.2 |
| JDK CI | 17 (Temurin) |
| `compileSdk` | 37 + `compileSdkMinor 2` = 37.2 |
| Android Build Tools | 36.0.0 |
| `targetSdk` | 36 |
| `minSdk` | 24 |
| `applicationId` | `com.example.gpstest` |
| `namespace` | `com.example.gpstest` |
| `versionCode` / `versionName` | 1 / `1.0` |
| AppCompat | 1.8.0 |
| AndroidX Core | 1.19.1 |
| Material Components | 1.14.0 |
| WorkManager | 2.12.0 |
| Mapsforge map Android / themes | 0.25.0 |

Ne pas monter `minSdk` ou `targetSdk` pour faire disparaître un avertissement isolé. `compileSdk 37.2` ne signifie pas que l'application cible automatiquement Android 17 ; `targetSdk` reste 36.

## 0.6 Principes décidés par l'utilisateur — invariants à respecter

1. La fiabilité des données GNSS et GPX prime sur l'apparence ou une fonctionnalité supplémentaire.
2. Ne jamais fabriquer une coordonnée, altitude, heure UTC GPX, vitesse, cap, accuracy, satellite, DOP, fix ou pseudodistance manquant.
3. Un fix 2D horizontal exploitable est accepté ; son état reste visuellement orange et aucune altitude n'est inventée.
4. `(0,0)` est explicitement considéré invalide par l'application, dans les flux et à l'import GPX. Cette décision ne doit pas être supprimée sans échange préalable.
5. Les coordonnées doivent être finies et dans les bornes géographiques ; les altitudes GPX sont retenues seulement si finies et comprises entre −10 000 m et +10 000 m inclus.
6. `elapsedRealtime()` / `elapsedRealtimeNanos()` sert aux durées, délais, âges et ordonnancement monotones ; `currentTimeMillis()` sert aux dates civiles/UTC GPX et noms de fichier, avec conversion explicite. Ne jamais soustraire des domaines d'horloge différents.
7. Une perte de signal doit créer une coupure logique GPX (`trkseg`) et ne jamais produire une ligne fictive reliant deux positions séparées par un tunnel ou une longue interruption.
8. La lecture d'un journal ou l'affichage d'une notice ne doit pas consommer les informations nécessaires à une récupération future.
9. Une opération asynchrone doit capturer l'identité stable de la session/du segment auquel elle appartient ; une ancienne tâche ne doit pas agir sur un nouveau segment.
10. Le foreground service reste responsable de l'acquisition GNSS continue. WorkManager sert aux travaux différés persistants, pas à un flux de positions chaque seconde.
11. Aucun `WAKE_LOCK` permanent ne doit être ajouté sans preuve mesurée d'un besoin réel et d'un bénéfice sur les appareils ciblés.
12. Ne pas ajouter `ACCESS_BACKGROUND_LOCATION` automatiquement : le parcours visé est un démarrage explicite depuis une activité visible ; justifier séparément tout cas d'usage arrière-plan.
13. Les mesures brutes GNSS/NMEA peuvent être restreintes par Android lorsque l'application cliente est en arrière-plan ; l'interface et les CSV doivent refléter honnêtement les données réellement reçues.
14. Les cartes principales utilisent exclusivement des fichiers vectoriels Mapsforge `.map` installés localement. Pas d'osmdroid, de tuiles raster ni de repli réseau silencieux.
15. Les métadonnées, namespaces et extensions GPX tierces inconnues doivent être conservés autant que possible, sans laisser repasser des valeurs invalides dans les champs connus.
16. Le nettoyage/post-traitement ne doit jamais écraser le GPX original.
17. Les changements doivent être ciblés, accompagnés de tests et d'une note de lot ; pas de réécriture générale Kotlin/Compose/Hilt sans bénéfice prouvé.
18. Le point décimal doit être conservé pour les valeurs numériques techniques, via `Locale.ROOT`, indépendamment de la langue du téléphone ; les dates et messages localisés peuvent rester localisés.

## 0.7 Ce qui est terminé, partiellement terminé et non commencé

**Acquis dans la base ETAPE_15 :** les lots historiques 1 à 7G, les fondations 8A/8B, la sérialisation des événements GNSS, le pipeline de capture, la séparation des segments GPX, les écritures en worker, les compteurs différenciés, la cartographie vectorielle locale, et le journal de session atomique R1.

**R1 terminé au niveau du code/JVM/statique :** UUID de session, révisions, stockage interne, `AtomicFile`, migration, lecture non destructive, acquittement de notice, protection des écritures différées par identité. **Reste à confirmer :** CI Android/Lint sur le commit exact et essais réels.

**En attente :** checkpoint GPX réellement synchronisé (`safeLengthBytes` reste 0), registre de plusieurs traces interrompues, réparation idempotente, ouverture de segment entièrement déportée dans la FIFO GPX, tests de destruction brutale/stockage saturé/SAF révoqué, décision explicite de datum altitude, correctifs sélectifs de l'archive locale, tests terrain multi-API/OEM, conformité publication/backup/signature.

**Pas encore intégré :** le ZIP `GPS-Test_modifications_locales_2026-11-24.zip`. Il s'agit d'un instantané partiel pré-R1 ; il doit être traité comme une source d'idées et de diffs ciblés, jamais comme un projet complet à superposer sur ETAPE_15.

---

# 1. Identité du projet et structure actuelle

## 1.1 Dépôt et noms distincts

Dépôt GitHub utilisé pendant les échanges :

<https://github.com/Xantia500/GPS-Test>

La branche de travail est `main`.

Les différents noms sont distincts :

| Élément | Valeur connue |
|---|---|
| Nom du dépôt GitHub | `GPS-Test` (le renommage vers `GPS-Test-Android` n'a pas été confirmé) |
| Nom Gradle racine (`settings.gradle`) | `GpsTest` |
| Nom visible de l'application (`strings.xml`) | `GPS Test` |
| `namespace` | `com.example.gpstest` |
| `applicationId` | `com.example.gpstest` |
| `versionCode` | `1` |
| `versionName` | `1.0` |
| README historique | mentionne `v5.14` |

La divergence `README v5.14` / `versionName 1.0` n'empêche pas la compilation, mais doit être clarifiée avant une version de test publique ou une publication. Le nom du dépôt ne modifie ni le `namespace` ni l'identifiant technique Android.

## 1.2 Organisation du projet

Structure attendue à la racine de GitHub :

```text
GPS-Test/                         (racine du dépôt)
├── .github/
│   └── workflows/
│       └── android-build.yml
├── app/
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/gpstest/
│       └── res/
├── docs/
├── tests/
├── tools/
├── .editorconfig
├── .gitattributes
├── .gitignore
├── build.gradle
├── settings.gradle
├── gradle.properties
└── README.md
```

Les tests JVM sont dans le dossier racine `tests/`, pas nécessairement dans `app/src/test/`. Le projet a été conçu avec des tests autonomes et des stubs sous `tools/stubs`.

Le chemin `/home/runner/work/GPS-Test/GPS-Test/` observé dans GitHub Actions est normal : GitHub crée un dossier de travail puis un checkout du dépôt. Il ne prouve pas un double dossier erroné dans le dépôt.

## 1.3 Classes importantes du code actuel

Quelques classes centrales (liste non exhaustive) :

### Application et service

- `MainActivity.java` : écran principal, permissions et commandes de suivi.
- `TrackingService.java` : service Android de localisation, abonnements GNSS, pipeline d'événements, GPX et coordination des workers.
- `TrackingState.java` : état partagé transmis à l'interface.
- `SessionJournal.java`, `SessionStore.java` : état de session et détection de coupures.
- `BackgroundPolicy.java`, `GnssAcquisitionConfig.java` : politique et configuration d'acquisition.
- `GnssEventPipeline.java` : sérialisation des événements GNSS modérés.
- `CallbackGenerationGate.java` : invalidation de callbacks obsolètes.

### Expert et GNSS

- `ExpertActivity.java` : présentation des mesures avancées.
- `RawMeasurementsCsvLogger.java`, `RawMeasurementsFormatter.java` : journal CSV en flux.
- `NmeaParser.java` : analyse des trames NMEA.
- `FixQuality.java`, `FixStatePresentation.java` : qualité et présentation du fix.
- `SatInfo.java`, `SatelliteAdapter.java`, `SatelliteFilter.java`, `BandSupport.java` : satellites, filtres et bandes.
- `DopCalculator.java` : calcul de DOP.
- `Compass.java`, `CompassSensor.java`, `CompassView.java`, `SkyView.java` : boussole et représentation du ciel.

### GPX

- `GpxWriter.java` : écriture GPX.
- `GpxParser.java` : lecture GPX SAX/streaming.
- `GpxPreservation.java` : conservation des métadonnées et fragments XML pertinents.
- `TrackCleaner.java`, `TrackPostProcess.java`, `TrackMode.java` : nettoyage et post-traitement.

### Cartographie

- `MapActivity.java`, `OfflineMapsActivity.java`.
- `MapRenderer.java`, `MapsforgeMapRenderer.java`, `OfflinePlaceholderMapRenderer.java`.
- `MapDataSource.java`, `OfflineMapRepository.java`, `MapFileLeaseRegistry.java`.
- `MapRegionSelectionView.java`, `MapRegionProjection.java`.
- `MapsforgeCatalogClient.java`, `MapsforgeCatalogParser.java`, `MapsforgeBoundaryClient.java`, `MapsforgeBoundaryCatalogParser.java`, `MapsforgePolyParser.java`.
- `OfflineCatalogRepository.java`, `OfflineBoundaryRepository.java`, `OfflineMapInstallScheduler.java`, `OfflineMapInstallWorker.java`.
- `MapTraceSegmenter.java`, `MapTraceSimplifier.java`, `MapOrientationCalculator.java`, `MapStyle.java`.

---

# 2. Objectif fonctionnel de GPS-Test

Construire une application Android de diagnostic GPS/GNSS riche, plus complète qu'un simple écran de coordonnées, qui combine :

- acquisition directe GNSS via `LocationManager` / `GPS_PROVIDER` ;
- informations satellites multi-constellations, SNR/C/N0, fréquence porteuse, bandes L1/L5, satellites utilisés dans le fix ;
- DOP récepteur et DOP géométrique distincts ;
- parseur et présentation NMEA ;
- écran Expert avec mesures GNSS brutes et enregistrement CSV configurable ;
- suivi en arrière-plan via foreground service de localisation ;
- enregistrement GPX robuste, segmentation, importation, relecture et nettoyage ;
- gestion de cartes régionales Mapsforge téléchargeables, stockées localement et consultables hors ligne ;
- affichage de la position GNSS et des traces GPX indépendamment du fond cartographique.

L'utilisateur veut une application techniquement sérieuse, fiable pour des sorties longues (randonnée, vélo, trajet), et souhaite que les choix soient explicités avant l'ajout d'une dépendance ou une migration importante.

---

# 3. Audit initial et exigences de l'utilisateur

Documents initiaux fournis :

1. `Echange_avec_ChatGPT_3.docx` : historique des lots et décisions au moment de la progression jusqu'au LOT 7E.
2. `AUDIT CHATGPT 1.docx` : audit technique initial de 22 pages, suivi des corrections demandées par l'utilisateur aux pages 19–22.
3. `ETAPE_3.zip` : projet `GPS-Test-lot-7E.zip` et les documentations successives des lots précédents.

L'audit initial signalait notamment :

- incohérence entre horloges monotones et heure civile dans le flush GPX ;
- risque de publication d'un ancien segment dans le document SAF d'un segment suivant ;
- compteurs de points GPX ambigus ;
- limites du foreground service et du cycle de vie Android ;
- permission notification non demandée au bon moment ;
- destruction du service susceptible de bloquer le thread principal ;
- question de l'utilité réelle de `WAKE_LOCK` ;
- mesures brutes GNSS dépendantes d'ExpertActivity et non enregistrées progressivement ;
- DOP calculé à partir de satellites visibles plutôt que ceux réellement `usedInFix` ;
- statut RMC A/V insuffisamment vérifié ;
- rejet trop radical des fixes 2D ;
- confusion entre bandes observées et capacités officiellement déclarées ;
- parseur GPX maison et import sur le thread principal ;
- perte de métadonnées et dates artificiellement créées lors du nettoyage ;
- fausse impression de carte hors ligne à cause du cache Mapnik ;
- suivi caméra qui gêne le déplacement manuel et géométrie antiméridien ;
- Edge-to-Edge à compléter ;
- cible SDK à actualiser ;
- nécessité de tests sur appareils.

L'utilisateur a demandé d'effectuer les corrections en lots, en conservant l'architecture et en s'arrêtant à la fin de chaque étape pour bilan et validation de la suite.

---

# 4. Historique des lots — synthèse chronologique

Les nombres de tests/assertions sont des résultats annoncés à la fin de chaque lot ; ce ne sont pas des compteurs à additionner. Les chiffres historiques ne remplacent pas l'état du code actuel.

## LOT 1 — fiabilité GPX et horloges

Corrections documentées :

- distinction stricte des horloges : `elapsedRealtimeNanos()` en nanosecondes, `elapsedRealtime()` en millisecondes monotones, `currentTimeMillis()` en millisecondes Unix ;
- reconstruction de la date GPX depuis une ancre UTC et le temps monotone écoulé ;
- flush Java toutes les 2 s ; flush de secours toutes les 15 s même sans nouveau fix ; `FileDescriptor.sync()` toutes les 30 s ; flush/sync/fermeture à la fin du segment ;
- pas de `sync()` à chaque point, trop coûteux pour un rythme nominal de 1 Hz ;
- contexte dédié `RecordingSegment` pour chaque segment : fichier, writer, version GPX, destination SAF, identifiant, publication ;
- publication asynchrone sur un instantané de longueur fixe ; une tâche ancienne ne peut pas prendre les données d'un nouveau segment ;
- protection contre une erreur d'écriture différée touchant le segment suivant ;
- trois compteurs séparés : points acceptés, mis en file, sérialisés ; erreurs avant et après mise en file distinguées ;
- génération de session pour invalider les anciennes opérations asynchrones ; file bornée à 120 points.

Résultat historique rapporté : 15 suites, 795 assertions.

## LOT 2 — service Android, GNSS en arrière-plan

- promotion du foreground service très tôt avant les opérations longues ;
- initialisation protégée contre les échecs partiels ;
- réabonnement périodique si une souscription échoue réellement (cadence rapportée de 5 s) ;
- watchdog détectant une absence prolongée de callbacks de position (30 s dans la conception rapportée) ;
- ne pas réinitialiser agressivement le GNSS à chaque tunnel/silence ;
- demande de localisation nominale à 1 seconde, sans garantie matérielle d'un point chaque seconde ;
- permission `POST_NOTIFICATIONS` expliquée et demandée dans un parcours contextuel ; le refus ne bloque pas le suivi GPS ; avertissement si notifications bloquées ; visibilité privée de la notification ;
- arrêt/redémarrage et session journalisés ; pas de redémarrage automatique non sollicité après mort du processus ;
- `onDestroy()` ne doit pas attendre plusieurs secondes sur le thread principal ; fermeture asynchrone des workers GPX, post-traitement et publication ;
- aucune acquisition permanente de `WAKE_LOCK` ; la permission ne maintient pas le CPU éveillé par elle-même ; les WakeLocks permanents sont écartés en l'absence de mesures démontrant un bénéfice ;
- pas d'ajout systématique de `ACCESS_BACKGROUND_LOCATION` pour un suivi démarré depuis une activité visible.

Résultat historique rapporté : 15 suites, 798 assertions.

## LOT 3 — GNSS Expert et mesures brutes

- acquisition `GnssMeasurementsEvent` déplacée sous la responsabilité de `TrackingService`, au lieu de dépendre du cycle de vie de `ExpertActivity` ;
- aperçus Expert et enregistrement CSV indépendants ; quitter Expert ne stoppe pas un CSV déjà commencé ;
- CSV progressif sur worker dédié et file bornée (4 096 lignes dans la documentation initiale) ;
- 47 colonnes annoncées dans le rapport ; mesures brutes, AGC, NMEA et informations d'horloge ;
- options de sélection des champs CSV persistées ; Full Tracking optionnel ;
- valeurs non disponibles laissées vides/absentes plutôt que calculées artificiellement ; les pseudodistances absolues ne sont pas inventées ;
- distinction essentielle : les mesures brutes et NMEA peuvent être suspendues/non livrées lorsque l'application cliente n'est pas au premier plan, même si le foreground service continue le suivi de position.

Résultat historique rapporté : 16 suites, 833 assertions.

## LOT 4 — qualité GNSS, NMEA, DOP, fixes 2D/3D

- parseur NMEA renforcé pour GGA, GSA, RMC, GLL, VTG, ZDA, GNS, GST ;
- contrôle checksum : checksum présent incorrect => phrase rejetée ; absence de checksum tolérée pour certains équipements ;
- RMC statut `A` accepté, `V` rejeté ; mode N rejeté ; vitesse et cap ne doivent pas être exposés si la phrase indique invalide ;
- distinction de trois valeurs : DOP récepteur depuis GSA, HDOP de secours depuis GGA, DOP géométrique calculé en interne ;
- calcul géométrique basé sur satellites réellement utilisés (`usedInFix`/`inFix`) avec élévation/azimut exploitables, doublons multibande traités et biais inter-constellation simplifié ; ce DOP n'est pas une mesure exacte d'erreur en mètres ;
- fix 2D accepté si coordonnées horizontales exploitables ; état orange ; pas de balise altitude inventée ;
- nombre minimal de satellites non utilisé comme règle universelle bloquant un fix Android déjà calculé ; il reste un indicateur diagnostique ;
- (0,0) rejeté dans le flux de position direct selon la politique de l'application ;
- fraîcheur des champs NMEA limitée (notamment 5 s pour certaines données, 10 s pour ZDA) pour éviter les valeurs indéfiniment périmées ;
- bandes : capacités officielles du chipset et fréquences effectivement observées doivent être distinguées ; `getGnssSignalTypes()` disponible depuis API 34 utilisé lorsque possible.

Résultat historique rapporté : 18 suites, 946 assertions.

## LOT 5 — import GPX et parseur streaming

- remplacement du parsing textuel maison par SAX natif en flux, sans ajout d'une bibliothèque tierce ;
- GPX 1.0 et 1.1, namespaces par défaut/préfixés, plusieurs traces et segments, routes et waypoints, altitudes, horodatages ISO 8601, extensions ;
- protection contre DTD et entités XML externes ;
- limites documentées : import carte 32 Mio, nettoyage 24 Mio, jusqu'à 250 000 points, 20 000 segments, profondeur XML 64, 128 attributs/élément et taille de champ limitée ;
- parsing et préparation de l'overlay hors thread UI ; progression et annulation ;
- la trace précédente reste visible tant que la nouvelle n'est pas analysée et préparée ; pas de remplacement partiel si erreur ;
- le nettoyage ne modifie jamais l'original et supprime ses sorties temporaires incomplètes ;
- limite connue : le post-traitement construit temporairement une deuxième liste d'objets ; il n'est donc pas strictement à mémoire constante.

Résultat historique rapporté : 19 suites, 966 assertions.

## LOT 6 — fidélité des traces GPX

- ajout de `GpxPreservation.java` : deuxième lecture SAX destinée à préserver métadonnées, auteur, traces/segments, waypoints, routes, extensions, namespaces et fragments XML inconnus dans la mesure prévue ;
- priorité à la version source GPX : GPX 1.0 reste GPX 1.0, GPX 1.1 reste GPX 1.1 ; versions/namespaces incompatibles refusés plutôt que convertis silencieusement ;
- aucune heure, altitude, nom ou précision inventée ;
- original jamais écrasé ; nom de sortie réservé pour éviter collisions ; sortie incomplète supprimée en cas d'erreur ;
- la conservation est sémantique et structurelle, pas octet par octet : commentaires, indentation, forme CDATA et instructions de traitement peuvent changer.

Résultat historique rapporté : 20 suites, 996 assertions.

### Décision de l'utilisateur sur les coordonnées (0,0)

L'audit initial rappelait que `(0,0)` est géographiquement une coordonnée valide dans le golfe de Guinée et suggérait de la conserver pour un fichier GPX importé. L'utilisateur a ensuite précisé qu'il voulait considérer `(0,0)` comme une valeur de panne matérielle et une alerte de réception, et a demandé que cette valeur soit traitée comme invalide en amont. La politique actuellement suivie rejette donc aussi `(0,0)` dans les GPX importés.

Ne pas changer cette politique sans en reparler explicitement avec l'utilisateur et lui rappeler la conséquence géographique.

## LOT 7A — fondations cartographiques hors ligne

- architecture `MapDataSource` indépendante du moteur de rendu ;
- `MapsforgeMapHeader` valide l'en-tête Mapsforge v5 (signature, version, tailles, emprise, etc.) ;
- `OfflineMapRepository` pour stockage persistant app-specific, staging, validation, SHA-256 local, contrôle espace disque, progression, annulation, métadonnées et remplacement transactionnel ;
- aucune dépendance Mapsforge approximative introduite tant que la publication Maven n'était pas vérifiée ;
- fond cartographique actif encore osmdroid/Mapnik à cette étape.

Résultat historique rapporté : 21 suites, 1 018 assertions.

## LOT 7B — premier moteur Mapsforge

- abstraction `MapRenderer` ; `MapsforgeMapRenderer` pour `.map` vectoriels ; osmdroid encore conservé comme repli provisoire ;
- ouverture locale des fichiers `.map` ; fusion des régions adjacentes avec `MultiMapDataStore` et déduplication ; position GPS et traces GPX en overlays ; cadrage, antiméridien, gestion des ressources ;
- Mapsforge 0.25.0 retenu pour les artefacts publiés sur Maven Central et la reproductibilité ; ne pas choisir un numéro upstream si l'artefact Android correspondant n'est pas réellement publié et vérifié.

Résultat historique rapporté : 23 suites, 1 032 assertions.

## LOT 7C — catalogue et téléchargement des régions

- activité `OfflineMapsActivity` ; navigation dans les index continentaux/régionaux ; liste des cartes disponibles et installées ; téléchargement, mise à jour, annulation, suppression ;
- `DownloadManager` pour le transfert de gros fichiers, progression et restrictions Wi-Fi / données mobiles / itinérance ;
- staging privé, vérification taille, SHA-256 local, en-tête Mapsforge v5, installation versionnée et transactionnelle ;
- correction du doublon `onActivityResult()` dans `MapActivity` ; conservation des overlays GPX au retour du gestionnaire.

Résultat rapporté : 1 061 assertions.

## LOT 7D — sélection géographique, états des régions et finalisation persistante

- sélection tactile des régions à partir de fichiers frontières `.poly` ;
- `MapsforgeBoundaryClient`, `MapsforgeBoundaryCatalogParser`, `MapsforgePolyParser`, `OfflineBoundaryRepository`, `MapRegionSelectionView` ;
- affichage états disponibles (bleu), installées (vert), mises à jour (orange) ; recherche dans le dossier courant ;
- cache .poly borné (64 Mio globalement dans la documentation) ; limites sur taille, anneaux, sommets et polygones dessinés ;
- finalisation post-téléchargement persistante par WorkManager : `DownloadCompleteReceiver` -> `OfflineMapInstallScheduler` -> `OfflineMapInstallWorker` -> dépôt local ; travail unique/idempotent ;
- suppression différée d'un fichier Mapsforge encore ouvert, grâce aux leases ; synchronisation de MapActivity avec révision du dépôt.

Résultat : 31 suites, 1 133 assertions.

Limites de l'époque : catalogue racine non mondial interactif ; index non entièrement disponible hors ligne ; pas de test d'une vraie carte de plusieurs centaines de Mio.

## LOT 7E — expérience cartographique et navigation

- projection Web Mercator cohérente pour la sélection régionale ; longitude circulaire pour polygones qui traversent ±180° ;
- gestes pinch/pan, sélection tactile sans déplacement accidentel, bouton recentrer ;
- conservation du zoom/panoramique lors d'un filtre ou d'un changement d'état ;
- recherche dans les index déjà consultés et cache de frontières ;
- amélioration du suivi caméra de la carte principale, de l'affichage GPX et du cadrage antiméridien.

Résultat : 34 suites, 1 163 assertions.

## LOT 7F — finalisation du moteur Mapsforge

- correction du moteur Mapsforge et du cycle de vie des MapFile ;
- gestion des verrous/leases des fichiers cartographiques afin qu'un fichier utilisé par un renderer ne soit pas supprimé ou remplacé prématurément ;
- thèmes et préférences cartographiques ; thèmes disponibles dans la version retenue : Standard, Vélo/Randonnée, Moto, OSM classique ;
- orientation Nord en haut / Cap en haut, gestion de l'orientation ;
- simplification des longues traces GPX pour le rendu, sans toucher au fichier source ;
- correction/attention portée aux trous de couverture, aux régions adjacentes et à l'antiméridien.

Résultat : 36 suites, 1 177 assertions.

Un écart a été identifié après 7F : le fond du sélecteur régional avait repris une vue Mapnik/osmdroid à tuiles, contraire à l'objectif strict hors ligne. Un autre écart : la carte principale retombait automatiquement sur osmdroid si Mapsforge n'était pas disponible.

## LOT 7G — cartographie strictement locale et pipeline GNSS sérialisé

Corrections des deux écarts cartographiques :

1. Le sélecteur n'utilise plus `MapView` osmdroid/Mapnik ni les tuiles raster. Il dessine une grille géographique et les contours `.poly` dans `MapRegionSelectionView`.
2. La carte principale n'effectue plus de basculement silencieux vers un moteur en ligne. Si aucune carte `.map` valide n'est installée ou si Mapsforge échoue, `OfflinePlaceholderMapRenderer` affiche un canevas géographique local neutre. La position et les traces GPX restent disponibles.
3. La classe `OsmdroidMapRenderer`, la dépendance osmdroid et le `MapView` raster de `activity_map.xml` ont été retirés.

Limite importante : le canevas neutre n'est pas une carte routière ; il n'affiche pas rues, bâtiments, relief ou noms de lieux. Les contours `.poly` et le catalogue peuvent nécessiter une connexion lors de leur première récupération ; les fichiers installés et les données mises en cache restent localement disponibles. La permission Internet reste nécessaire pour le catalogue, contours non mis en cache et téléchargements de `.map`. Aucun préchargement de tuiles depuis `tile.openstreetmap.org` n'est effectué.

Pipeline GNSS :

- `GnssEventPipeline` avec `HandlerThread` dédié et file FIFO pour callbacks de position, `GnssStatus` et NMEA ;
- callbacks anciens filtrés par générations/états après arrêt, suspension ou réabonnement ;
- `gnssProcessingLock` pour transitions cohérentes et interactions avec état/watchdog ;
- publications UI regroupées et postées sur thread principal ;
- exceptions callback comptabilisées sans tuer la file ;
- `GnssMeasurementsEvent` conservé sur worker CSV séparé, car débit et traitement plus lourds ;
- watchdog indépendant pour détecter le silence GNSS.

Résultat : 37 suites, 1 279 assertions.

## LOT 8A — audit global et préparation compilation

- audit statique des sources Java, références de ressources, manifeste, permissions de foreground service, XML, versions SDK et architecture cartographique ;
- workflow GitHub Actions et outils de vérification préparés ;
- compatibilité explicite des callbacks `LocationListener` pour minSdk 24–29, notamment `onStatusChanged`, `onProviderEnabled` et `onProviderDisabled` ;
- `CallbackGenerationGate` pour position, statut satellites et NMEA ;
- modification de fréquence : réabonnement ciblé des positions, sans interrompre NMEA/Status/CSV Expert ;
- wrappers de callback protègent contre les vieux événements déjà en file ;
- `onLocationChanged(List<Location>)` API 31+ retransmis dans l'ordre chronologique vers le traitement unitaire.

Résultat rapporté : 38 suites, 1 288 assertions.

Limite au moment du lot 8A : l'environnement d'analyse ne possédait pas Gradle, Gradle Wrapper, SDK Android ou `adb`. Il ne pouvait donc pas compiler Android ; une CI GitHub a ensuite été préparée et utilisée réellement.

## LOT 8B — compilation réelle, corrections Java/API, outillage et Lint

Ce lot a été prolongé par plusieurs correctifs successifs à partir des vraies erreurs retournées par GitHub Actions.

### 8B.1 Première erreur de dépôt/commit

Le premier workflow a utilisé un ancien commit `cd750b8607a55bd9e9ab0211cde238718892dad1` dans lequel `app/build.gradle` était absent. Le fichier était bien présent dans le `main` plus récent. Le commit initial était incomplet ; ce n'était pas une structure de dossier double dans le dépôt courant. La nouvelle exécution sur `main` a ensuite atteint la compilation Android.

### 8B.2 Erreurs Java/API corrigées

**`OfflineMapsActivity.java` :**

- `DownloadManager.ERROR_HTTP_NOT_FOUND` n'existe pas dans l'API Android ; remplacé par le code générique `DownloadManager.ERROR_UNHANDLED_HTTP_CODE` ;
- le message n'affirme plus que c'est forcément une 404 : il indique qu'une réponse HTTP n'a pas été traitée et qu'une carte absente/URL incorrecte est possible.

**`TrackingService.java` :**

- `.setMaxUpdateAgeMillis(0L)` n'existe pas dans `android.location.LocationRequest.Builder` ; l'appel a été supprimé ;
- le contrôle de fraîcheur des positions reste effectué par la logique de `FixQuality`, basée sur le temps monotone et la limite `MAX_FIX_AGE_MS` de 10 s ;
- une erreur lambda Java sur la variable `fullTracking` (non effectivement finale) a été corrigée en calculant un instantané/texte final avant publication UI ;
- les données et le comportement affiché de Full Tracking restent vrais/faux/inconnus, sans capture interdite d'une variable mutable.

**`MapsforgeMapRenderer.java` :**

- deux appels à `MapZoomCalculator.fit()` fournissaient `float` au lieu de l'`int` attendu ; conversion par `Math.round()`.

Les stubs JVM ont également été corrigés pour ne pas exposer de fausses méthodes Android inexistantes et pour coller davantage aux signatures Android utilisées.

### 8B.3 Mise à niveau outillage Android

Mise à niveau décidée pour répondre à l'avertissement Gradle 8.13 obsolète et à la compatibilité AGP :

- Gradle 9.9.2 ;
- AGP 9.5.2 ;
- JDK 17 ;
- AppCompat 1.8.0 ;
- AndroidX Core 1.19.1 ;
- Material 1.14.0 ;
- WorkManager 2.12.0 conservé ;
- Mapsforge 0.25.0 conservé, car c'est l'artefact Android résolu sur Maven Central dans le projet ; ne pas le remplacer par une version upstream non publiée/testée.

Un artefact Android a imposé l'installation d'Android SDK 37.2 pour la compilation AAR. Le projet utilise donc `compileSdk 37` et `compileSdkMinor 2`. `targetSdk 36` et `minSdk 24` restent inchangés.

### 8B.4 Compatibilité API 31 — `GnssCapabilities.hasMeasurements()`

Lint a signalé que `GnssCapabilities.hasMeasurements()` nécessite API 31, tandis qu'un chemin l'appelait à partir d'API 30.

Correction : appel protégé par `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S` (API 31). Sur API 24 à 30, la capacité reste inconnue et l'application tente l'inscription réelle au callback ; le succès/échec de cette inscription reste l'autorité. Ne pas annoncer artificiellement « chipset incompatible » sur un ancien Android.

### 8B.5 Compatibilité API 33 — `GnssAutomaticGainControl`

Le rapport détaillé Lint a signalé les getters API 33 de `GnssAutomaticGainControl` :

- `getConstellationType()` ;
- `getCarrierFrequencyHz()` ;
- `getLevelDb()`.

Chaque appel apparaissait deux fois, soit six occurrences Lint. La récupération de la liste était déjà gardée API 33, mais l'exploitation des éléments ne l'était pas suffisamment.

Correction : boucle de traitement AGC explicitement protégée par :

```java
if (options.signal && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    for (android.location.GnssAutomaticGainControl agcInfo : eventAgcs) {
        final int constellationType = agcInfo.getConstellationType();
        final long carrierFrequencyHz = agcInfo.getCarrierFrequencyHz();
        final double levelDb = agcInfo.getLevelDb();
        // formatage CSV / aperçu
    }
}
```

Sous API 24–32, ces getters ne sont jamais appelés. Les autres données brutes GNSS continuent normalement. Le contrôle statique a été renforcé.

### 8B.6 Fins de ligne LF / CRLF

GitHub affichait une notice : un fichier LF pouvait être converti en CRLF lors d'un prochain checkout Git sur Windows. Ce n'était pas une erreur Gradle.

Ajouts :

- `.gitattributes` : `* text=auto eol=lf`, avec les fichiers binaires APK/AAR/JAR/MAP/ZIP/images/PDF/keystores déclarés `binary` ;
- `.editorconfig` : UTF-8, LF, fin de ligne finale, indentation cohérente.

Ne pas convertir les fichiers binaires en texte.

### 8B.7 GitHub Actions modifié

Le workflow `.github/workflows/android-build.yml` utilise :

- `actions/checkout@v7` ;
- `actions/setup-java@v6` avec Temurin 17 ;
- `gradle/actions/setup-gradle@v6` et Gradle 9.9.2 ;
- `android-actions/setup-android@v4` ;
- installation SDK `platforms;android-37.2` et Build Tools `36.0.0` ;
- contrôles statiques ;
- contrôle architecture hors ligne ;
- tests JVM ;
- `:app:assembleDebug` ;
- `:app:lintDebug` ;
- upload artefact APK et rapport Lint avec `if: always()`.

Le workflow a séparé compilation et Lint afin de publier l'APK même si Lint échoue. Les rapports HTML, SARIF et TXT sont également conservés.

### 8B.8 Résultat Lint historique détaillé

L'archive `android-lint-reports.zip` transmise par l'utilisateur contenait :

- HTML : `reports/lint-results-debug.html` ;
- SARIF : `reports/lint-results-debug.sarif` ;
- TXT : `intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt`.

Le rapport texte indique **6 errors, 210 warnings**. Un résumé de job antérieur indiquait 7 erreurs, et un journal séparé montrait `hasMeasurements()` comme première erreur. Les corrections API 31 + les six occurrences AGC API 33 correspondent à sept problèmes/occurrences bloquants identifiés dans les journaux successifs. La différence de comptage entre résumé et rapport est documentée ; ne pas la cacher.

Après le correctif AGC API 33, l'utilisateur a confirmé « C'est passé ! ». Il faut vérifier l'exécution la plus récente et ses compteurs exacts si l'on souhaite annoncer le nombre final d'erreurs/avertissements.

---

## LOT 8C-R1 — journal de session durable (étape la plus récente)

**Base :** ETAPE_14. **Livraison consolidée :** ETAPE_15. **Date :** 24 novembre 2026.

Objectif : rendre l'état de session durable et non destructif, protéger les accès Activity/Service/workers contre les écritures retardées d'une ancienne session, et préparer le futur checkpoint GPX sans prétendre déjà restaurer le fichier.

### `SessionJournal` — format 4

Le modèle indépendant d'Android contient notamment :

- `sessionId` : UUID stable de la session logique, distinct des identifiants de segments ;
- `revision` : révision monotone des snapshots de cette session ;
- `dirty` : une session n'a pas encore été explicitement terminée ;
- `expectedAlive` et `suspended` : intention/état attendu au moment du snapshot ;
- `safeLengthBytes` : champ réservé au futur offset de checkpoint réellement durable ; il vaut encore 0 dans `TrackingService.persistSession()` ;
- `noticeShown` : acquittement durable de l'avertissement, sans effacer le journal.

Compatibilité des formats historiques 1 à 3. En l'absence d'UUID legacy, l'identité `legacy:` est déterminée à partir d'informations historiques (début/date/nom). Les formats futurs inconnus et certains états booléens critiques invalides sont refusés au lieu d'être interprétés permissivement. Le nom de fichier est un simple nom, pas un chemin : `/`, `\\`, `.` / `..`, contrôles et retours ligne sont refusés.

### `SessionStore` — écriture privée et transactionnelle

- Source de vérité : stockage interne privé `Context.getFilesDir()/session.state`, indépendant du volume contenant le GPX.
- Écriture et remplacement par `android.util.AtomicFile` (`startWrite`, `finishWrite`, `failWrite`) ; lecture `openRead`, suppression `delete`.
- Lecture répétée non destructive ; `consume()` historique reste uniquement comme alias déprécié de `read()`.
- Migration du journal historique externe seulement après commit atomique réussi.
- Journal interne illisible préservé en quarantaine (`session.state.corrupt` ou suffixe libre) avant un nouveau START ; l'ancien fichier n'est pas écrasé si la mise en quarantaine échoue.
- Lecture plafonnée à 16 Kio ; les fichiers trop grands ou suspects sont conservés pour diagnostic.
- Verrou statique de processus pour le store ; les snapshots du service passent par `ioMeta`. `AtomicFile` n'est pas un verrou interprocessus : le contrat dépend du processus applicatif unique.

### `TrackingService` et `MainActivity`

- Chaque nouveau START crée un nouvel UUID.
- Les snapshots capturent l'UUID avant leur mise en file ; une tâche appartenant à une session ancienne est ignorée.
- `dirty` demeure vrai jusqu'à l'arrêt explicite. La révocation d'une permission ne signifie pas que l'utilisateur a terminé la session.
- Les suppressions sont conditionnées à l'identifiant attendu afin qu'un vieux clear ne puisse pas effacer le journal d'une session suivante.
- `MainActivity` lit sans consommer ; l'acquittement de la notice conserve le journal et survit aux snapshots suivants.

### Tests R1 / résultats

Les tests dédiés couvrent transactions AtomicFile simulées interrompues, backup/restauration, migration legacy, lecture non destructive, acquittement, conservation de l'acquittement, course entre identité, format futur inconnu, journal corrompu, quarantaine, préservation legacy et dépassement 16 Kio. `SessionJournalTest` couvre format 4, round-trip, formats 1–3, identités legacy, checkpoint, états pause/interruption et noms de fichiers.

La relance effectuée pour ce dossier sur ETAPE_15 confirme **39 suites, 1 345 assertions, 0 échec**. Les stubs JVM de `android.util.AtomicFile` vérifient le contrat simulé ; ils ne remplacent pas l'implémentation Android réelle.

**Non fourni par R1 :** réparation des octets GPX, récupération d'un XML tronqué, manifeste de plusieurs sessions en attente, tâches WorkManager durables, essais `kill -9`/force-stop, build Android et Lint du commit R1.

Critères R1 cochés au niveau code/JVM : journal privé ; transaction atomique ; migration après commit ; lecture/acquittement non destructifs ; UUID et clear conditionnel ; révocation de permission ; quarantaine/taille max ; tests statiques/JVM. Critères R1 restant à cocher : CI Android réelle, Lint du SHA R1, essai sur appareil.

Référence : `docs/LOT_8C_R1_JOURNAL_DURABLE.md`, `APPLIQUER_CORRECTIF_LOT_8C_R1.md`, `PASSATION/NOTE_ETAPE_15.md`.

# 5. Architecture technique actuelle

## 5.1 GNSS et suivi

Architecture logique :

```text
Android LocationManager / GPS_PROVIDER
       │
       ├── Position LocationListener
       ├── GnssStatus.Callback
       ├── OnNmeaMessageListener
       └── GnssMeasurementsEvent.Callback (canal séparé)
                  │
                  ▼
         GnssEventPipeline (FIFO)
                  │
         CallbackGenerationGate
                  │
         gnssProcessingLock / état de session
                  │
                  ▼
             TrackingService
             ├── qualité du fix / FixQuality
             ├── NMEA / satellites / DOP
             ├── échantillonnage GPX
             ├── watchdog indépendant
             └── publication TrackingState
                       │
           ┌───────────┴───────────┐
           ▼                       ▼
      Workers stockage       UI sur thread principal
      GPX / CSV               Main / Expert / Map
```

Règles :

- les callbacks position, statut satellites et NMEA sont sérialisés dans `GnssEventPipeline` ;
- les generations de callbacks invalidées empêchent un ancien callback en file de polluer un nouvel abonnement/session ;
- le watchdog ne dépend pas de l'arrivée d'événements dans le pipeline ;
- les publications UI passent par le thread principal et sont regroupées ;
- le traitement lourd des mesures brutes demeure sur un worker CSV séparé ;
- la cadence demandée n'est pas une garantie de fréquence effective du chipset ; la configuration nominale actuelle est 1 s, mais l'architecture prévoit des fréquences alternatives futures (2, 5, 10 s selon la conception).

## 5.2 Mesures brutes GNSS / Expert

- l'aperçu des mesures et l'enregistrement CSV sont dissociés ;
- CSV progressif, worker dédié, file bornée, compteurs ;
- options CSV persistées (horloge détaillée, signal, Doppler/temps satellite, phase/ADR, NMEA brute selon configuration) ;
- Full Tracking optionnel ;
- mesures API 33 AGC accessibles uniquement sous API 33+ ;
- capacité `hasMeasurements()` consultée uniquement sous API 31+ ;
- les mesures brutes/NMEA ne sont pas garanties en arrière-plan par Android : le service de position peut continuer alors que le canal des mesures est suspendu ou non livré ;
- pseudodistances absolues non disponibles : ne pas les inventer.

## 5.3 Position, qualité, DOP et satellites

- `GPS_PROVIDER` utilisé pour l'accès direct aux informations GNSS, sans dépendance obligatoire aux Google Play Services ;
- `FixQuality` valide la position horizontale et sa fraîcheur ;
- `Location.hasAltitude()` ne signifie pas à lui seul « solution 3D » ;
- GSA/NMEA fournit l'information dimensionnelle si elle est récente ; en son absence, l'app doit rester prudente ;
- fix 2D accepté en orange, pas d'altitude GPX ajoutée ;
- DOP récepteur, HDOP GGA et DOP géométrique propre distingués ;
- DOP géométrique s'appuie sur satellites `usedInFix`, C/N0 et géométrie exploitable ; ce n'est pas un estimateur d'erreur absolue en mètres ;
- bande observée par fréquence satellite distinguée de la capacité officielle déclarée par le chipset ;
- filtrage satellites : tous/utilisés/constellation ; les satellites utilisés sont prioritaires et le niveau SNR/C/N0 est affiché.

## 5.4 GPX

- Horloges : monotone pour durées, UTC pour timestamp ; ne jamais mélanger s/ms/ns ;
- segments isolés, contexte propre, publication SAF attachée au segment ;
- compteurs acceptés / mis en file / sérialisés distincts ;
- file GPX bornée ; workers et fermetures asynchrones ;
- import SAX stream, protections XML ;
- `GpxPreservation` pour garder métadonnées/extensions/version ;
- aucun timestamp ou champ altitude inventé ;
- nettoyage géométrique ne modifie pas l'original ;
- limitations mémoire du post-traitement à conserver en tête ;
- politique utilisateur `(0,0)` rejetée dans les flux et imports, sauf nouvelle décision explicite.

## 5.5 Cartographie hors ligne

Principe d'architecture :

```text
OfflineMapsActivity
   ├── MapRegionSelectionView (grille locale + .poly)
   ├── catalogue de cartes Mapsforge
   └── téléchargements déclenchés par l'utilisateur
              │
              ▼
      OfflineMapRepository
      ├── staging / validation
      ├── SHA-256 local
      ├── métadonnées / version
      ├── leases de fichiers
      └── stockage persistant
              │
              ▼
       MapsforgeMapRenderer
       ├── MapFile local
       ├── fusion régions adjacentes
       ├── thèmes
       └── overlays GPX / position

En absence de carte valide : OfflinePlaceholderMapRenderer
```

Points essentiels :

- aucun osmdroid/Mapnik/tile provider raster actif ;
- sélecteur `.poly` et grille locale schématique, pas une carte routière ;
- en l'absence d'une carte Mapsforge installée, fond neutre avec position et traces, pas de repli réseau ;
- le catalogue et les fichiers `.poly` non encore mis en cache peuvent nécessiter Internet lors d'une première consultation ; le téléchargement d'une nouvelle carte `.map` nécessite évidemment le réseau ;
- une fois une carte `.map` installée et valide, le rendu est local ;
- source des cartes : catalogue/mirror Mapsforge étudié, téléchargement individuel explicite, un téléchargement simultané par défaut ;
- ne pas employer le serveur public `tile.openstreetmap.org` pour précharger/télécharger massivement des régions ;
- données OSM/Mapsforge avec attribution visible OpenStreetMap et obligations de licence à conserver ;
- les fichiers `.map` sont des actifs persistants, jamais stockés uniquement dans cache ;
- remplacement/suppression ne doit pas intervenir tant qu'un renderer maintient un lease ;
- Mapsforge 0.25.0 conservé tant qu'une version Maven publiée et testée n'est pas disponible ;
- pas de hillshade/relief ombré faute de données DEM.

## 5.6 Edge-to-Edge

Le projet possède un helper commun `EdgeToEdge.java`, utilisé par les cinq activités principales déclarées : Main, Expert, Settings, Map, OfflineMaps. Le helper emploie les insets AndroidX et tient compte des barres système, découpes d'écran et IME/clavier.

Le code est intégré, mais la validation visuelle réelle reste ouverte sur différentes versions, tailles, gestes, clavier et rotation.

---

## 5.7 Journal de session / identité / durabilité

Le journal de session R1 est un **journal d'état**, pas un journal de points GPX. Il sert à constater qu'une session avait été engagée et n'a pas été explicitement terminée, à relier cet état à un UUID et à présenter une notice fiable après redémarrage. Il n'est pas la preuve que le fichier GPX est complet ou valide.

```text
MainActivity
   └── SessionStore.read() (lecture non destructive)
          └── affiche éventuellement la notice
                 └── SessionStore.markNoticeShown(sessionId)

TrackingService
   └── crée sessionId au START
          └── capture un SessionJournal immutable par transition
                 └── ioMeta (file sérialisée)
                        └── SessionStore.write(...)
                               └── AtomicFile / session.state interne
```

Le verrou d'identité évite un scénario essentiel : un snapshot ou un clear retardé d'une session précédente ne doit pas remplacer/supprimer le journal d'une nouvelle session. Le champ `revision` ordonne les snapshots d'une même session.

**Frontière R1/R2 :** `safeLengthBytes` existe dans le modèle mais le service l'écrit à `0L`. Un offset ne devra être enregistré que si la FIFO GPX a terminé les points précédents, `flush()` a réussi et `FileDescriptor.sync()` a réussi sur le fichier/segment exact. Un compteur `pointsWritten` n'est pas à lui seul un offset durable.

---

# 6. Configuration de build actuelle — source faisant autorité

Les valeurs suivantes ont été lues dans la dernière archive source complète :

## 6.1 `app/build.gradle`

```gradle
plugins {
    id 'com.android.application'
}

android {
    namespace 'com.example.gpstest'
    compileSdk 37
    compileSdkMinor 2

    defaultConfig {
        applicationId "com.example.gpstest"
        minSdk 24
        targetSdk 36
        versionCode 1
        versionName "1.0"
    }

    buildTypes {
        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
}

dependencies {
    implementation 'androidx.appcompat:appcompat:1.8.0'
    implementation 'androidx.core:core:1.19.1'
    implementation 'com.google.android.material:material:1.14.0'
    implementation 'androidx.work:work-runtime:2.12.0'
    implementation 'org.mapsforge:mapsforge-map-android:0.25.0'
    implementation 'org.mapsforge:mapsforge-themes:0.25.0'
}
```

## 6.2 Build racine et settings

`build.gradle` :

```gradle
plugins {
    id 'com.android.application' version '9.5.2' apply false
}
```

`settings.gradle` : repositories Google, Maven Central, Gradle Plugin Portal ; `rootProject.name = "GpsTest"` ; `include ':app'`.

`gradle.properties` : JVM args `-Xmx2048m`, UTF-8, AndroidX, `android.nonTransitiveRClass=true`.

## 6.3 Pas de Gradle Wrapper vérifié dans la dernière archive

La dernière archive source ne contient pas `gradlew`, `gradlew.bat` ni `gradle/wrapper/gradle-wrapper.properties` / `.jar`. Le workflow GitHub installe Gradle 9.9.2 via `gradle/actions/setup-gradle@v6` et appelle `gradle` directement. Le script `tools/generate_gradle_wrapper.sh` existe pour générer le wrapper sur une machine équipée de Gradle 9.9.2.

Pour une reproductibilité locale/Android Studio, il serait préférable de générer et versionner le wrapper complet, après validation, mais son absence ne bloque pas la CI actuelle.

## 6.4 Dernier workflow `.github/workflows/android-build.yml`

Le YAML présent dans la dernière archive :

- déclenchement sur `push`, `pull_request`, `workflow_dispatch` ;
- permission `contents: read` ;
- runner `ubuntu-latest` ; timeout 45 minutes ;
- Checkout v7 ; Java Temurin 17 via setup-java v6 ; Gradle 9.9.2 via setup-gradle v6 ; setup Android v4 ;
- installe `platforms;android-37.2` et `build-tools;36.0.0` ;
- exécute `tools/verify_android_project.py` ;
- exécute `tools/verify_offline_map_architecture.py` ;
- exécute `bash tools/run_jvm_tests.sh` ;
- exécute `gradle --no-daemon --stacktrace :app:assembleDebug` ;
- exécute séparément `gradle --no-daemon --stacktrace :app:lintDebug` ;
- publie l'APK et les rapports Lint avec `if: always()` pour les récupérer même si Lint échoue.

Noms d'artefacts prévus :

- `gps-test-debug-apk`
- `android-lint-reports`

Le workflow actuel n'indique pas explicitement `retention-days`. Pour maîtriser le stockage, on peut envisager 14 jours, mais ce changement n'est pas présent dans le dernier YAML examiné.

---

# 7. GitHub : dépôt, commits, erreurs résolues, utilisation

## 7.1 Dépôt

Dépôt observé : `Xantia500/GPS-Test`, branche `main`. L'utilisateur utilise à la fois l'interface web GitHub et GitHub Desktop.

Le workflow se trouve dans `.github/workflows/android-build.yml` et apparaît dans Actions sous le nom `Android build and audit`.

## 7.2 Erreur initiale de structure/commit

Première erreur : le vérificateur ne trouvait pas `app/build.gradle` dans le commit `cd750b8607a55bd9e9ab0211cde238718892dad1`. L'utilisateur a vérifié que le fichier existe bien sur `main`, alors que le chemin dans l'ancien SHA rendait 404. La conclusion est que le premier commit testé était incomplet ; le `main` plus récent contenait la bonne structure.

Ne pas refaire déplacer le dossier `app` ni créer un faux `app/src/test`. Le dossier `tests/` à la racine est normal pour les suites JVM.

## 7.3 Comment appliquer un correctif depuis GitHub Desktop

1. Télécharger le ZIP du correctif ou du projet complet le plus récent.
2. Le décompresser dans le dossier local du dépôt, à la racine contenant `.git`.
3. Accepter le remplacement des fichiers existants et l'ajout des nouveaux.
4. **Ne jamais supprimer le dossier `.git`.**
5. Vérifier l'onglet Changes de GitHub Desktop.
6. Écrire un commit descriptif.
7. Commit to main, puis Push origin.
8. Dans GitHub Actions, suivre la nouvelle exécution du commit courant de `main`, pas une ancienne exécution.

## 7.4 Lire un journal CI

- Ouvrir GitHub > Actions.
- Dans la colonne gauche, choisir `Android build and audit`.
- Dans la liste des exécutions, cliquer sur la ligne/commit du run.
- Sous `All jobs`, ouvrir le job `android`.
- Ouvrir la première étape rouge et copier les diagnostics complets, pas uniquement la fin de la stacktrace Gradle.
- Sur la page de résumé de l'exécution, récupérer les artefacts sous `Artifacts` s'ils existent.

Le dossier local `app/build/` n'existe pas forcément sur le PC : il est produit sur le runner GitHub, qui est temporaire. Il faut récupérer l'APK et les rapports via Artifacts.

## 7.5 Noms

L'utilisateur a demandé comment renommer `GPS-Test`. Il n'a pas confirmé avoir renommé le dépôt. Si nécessaire : Settings > General > Repository name. Cela ne change pas `applicationId`, `namespace` ni le nom sous l'icône Android.

---

# 8. Historique des erreurs de vraie compilation et corrections

Cette section évite de refaire des investigations déjà réalisées.

| Erreur/observation | Fichier / composant | Diagnostic | Correction connue |
|---|---|---|---|
| `app/build.gradle` absent | Ancien commit `cd750b8` | Le commit testé était antérieur/incomplet ; `main` plus récent contenait le fichier | Relancer sur HEAD `main`, ne pas déplacer `app` |
| `DownloadManager.ERROR_HTTP_NOT_FOUND` introuvable | `OfflineMapsActivity.java` | Constante inexistante dans l'API Android | Utiliser `ERROR_UNHANDLED_HTTP_CODE`, message prudent (réponse HTTP non traitée ; 404 possible mais non certaine) |
| `setMaxUpdateAgeMillis(0L)` introuvable | `TrackingService.java` | Méthode absente de `android.location.LocationRequest.Builder` utilisé | Supprimer ; `FixQuality` continue la vérification fraîcheur, limite 10 s, horloge monotone |
| `float` transmis à méthode `int` | `MapsforgeMapRenderer.java` | `MapZoomCalculator.fit()` attend un entier | `Math.round(dp(...))` aux deux appels |
| variable capturée non finale dans lambda | `TrackingService.java` vers ligne ~3574 | `fullTracking` muté puis capturé par lambda | construire un texte/instantané `final` avant `ui.post()` |
| Gradle 8.13 signalé obsolète | CI | chaîne d'outillage ancienne | Gradle 9.9.2 + AGP 9.5.2 |
| AAR exige compileSdk 37.2 | `app/build.gradle` / CI | `compileSdk 36` trop bas pour une dépendance résolue | `compileSdk 37`, `compileSdkMinor 2`, CI installe `android-37.2`; target/min inchangés |
| `GnssCapabilities.hasMeasurements()` NewApi | `TrackingService.java` | Getter exige API 31, chemin appelable sur API 30 | garde `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S` ; avant API31 capacité inconnue, essai abonnement réel |
| getters AGC NewApi | `TrackingService.java` | `GnssAutomaticGainControl` getters API33 mal protégés dans la boucle de traitement | garde `options.signal && SDK_INT >= TIRAMISU` autour de toute la boucle ; `int/long/double` respectés |
| avertissement LF/CRLF | GitHub/Git | normalisation locale de fichiers texte, pas erreur build | `.gitattributes` et `.editorconfig`, LF imposé, binaires exclus |

Les lignes indiquées peuvent se déplacer après de nouveaux commits ; utiliser les signatures de méthodes/recherches de symboles plutôt que des numéros de ligne figés.

---

# 9. APK, Lint et rapports transmis

## 9.1 APK Debug produit

L'utilisateur a téléversé `gps-test-debug-apk.zip`, qui a été examiné.

Constats de l'analyse :

- ZIP de livraison lisible ;
- APK valide au format ZIP ;
- taille `app-debug.apk` : **8 612 087 octets**, environ 8,21 Mio ;
- cinq fichiers DEX (`classes.dex` à `classes5.dex`) ;
- métadonnées indiquant AGP 9.5.2 ;
- bloc de signature APK v2 (`0x7109871a`) détecté.

Limite : `apksigner` n'était pas disponible dans l'environnement d'analyse. La présence du bloc de signature v2 a été constatée, mais la validation cryptographique complète du certificat par `apksigner verify` n'a pas été effectuée. Il ne faut donc pas annoncer une validation cryptographique complète.

Un APK Debug n'est pas une version Release prête pour Google Play. La configuration Release actuelle a `minifyEnabled false`, et aucune signature de release définitive n'a été validée.

## 9.2 Ancien rapport Lint

Le rapport `android-lint-reports.zip` transmis contenait HTML, SARIF 2.1.0 et TXT.

Décompte du rapport texte : **6 erreurs et 210 avertissements**. Le résumé GitHub d'une exécution antérieure mentionnait 7 erreurs. Le journal précédent avait en particulier révélé l'appel API31 `hasMeasurements()`. Les diagnostics API 31 et AGC API 33 ont ensuite fait l'objet de correctifs.

### Répartition des 210 avertissements de ce rapport historique

| Diagnostic | Nombre | Orientation |
|---|---:|---|
| `SetTextI18n` | 82 | Textes construits dans Java ; centralisation/localisation à prévoir |
| `HardcodedText` | 25 | Textes directement dans XML ; externaliser dans `strings.xml` |
| `SmallSp` | 18 | Polices 8–10 sp à revoir visuellement/accessibilité |
| `UnusedResources` | 17 | Vérifier usages dynamiques avant suppression |
| `ButtonStyle` | 16 | Styles de boutons/bars à harmoniser |
| `RtlSymmetry` | 13 | Marges/paddings Start/End et RTL |
| `ApplySharedPref` | 10 | Ne pas remplacer `commit()` par `apply()` à l'aveugle ; vérifier la nécessité de persistance synchrone |
| `PluralsCandidate` | 7 | Chaînes de compteur et ressources pluriel |
| `Overdraw` | 5 | Arrière-plans éventuellement dessinés en double |
| `ClickableViewAccessibility` | 3 | `performClick()`/accessibilité pour vues tactiles de la carte |
| `ObsoleteSdkInt` | 3 | Vérifier les gardes redondantes sans supprimer les gardes nécessaires |
| `UsableSpace` | 3 | Gestion de l'espace libre pour cartes volumineuses |
| `DefaultLocale` | 2 | `toLowerCase()` sans locale explicite ; préférer `Locale.ROOT` pour normalisation technique |
| `TooManyViews` | 2 | Layouts avec beaucoup de vues ; optimiser après profilage |
| `Autofill` | 1 | Champ de recherche ; vérifier pertinence Autofill |
| `NestedScrolling` | 1 | `ListView` dans `ScrollView` ; vérifier gestes/mesure |
| `OldTargetApi` | 1 | target 36, compile 37.2 ; décision ci-dessous |
| `UseRequiresApi` | 1 | annotation/helper API ; revoir l'isolation API |

Somme : 210.

**Important :** ces 210 avertissements sont issus de l'ancien rapport avant le dernier correctif API33. Ils ne constituent pas le décompte exact du dernier run post-correctifs. Il faut récupérer le dernier rapport Lint si l'on veut affirmer le nombre actuel.

### Politique de correction Lint

- corriger les erreurs ;
- ne pas créer un baseline pour masquer les problèmes ;
- traiter les avertissements par familles et en comprenant la sémantique ;
- ne pas modifier automatiquement `commit()`/`apply()` ;
- ne pas supprimer des ressources utilisées par réflexion/nom dynamique ;
- vérifier les tailles de police et accessibilité sur appareils ;
- ne pas monter `targetSdk` uniquement pour faire disparaître `OldTargetApi`.

## 9.3 État ETAPE_15 / R1 après contrôle effectif

Le dernier snapshot complet ETAPE_15 n'a pas été compilé Android dans l'environnement de préparation et le workflow demande une validation après intégration. Les contrôles JVM et statiques ont été relancés ici et donnent : **39 suites / 1 345 assertions / 0 échec ; 17 XML / 80 sources Java ; architecture offline auditée sur 98 fichiers sans fournisseur raster détecté.**

Ne pas réutiliser sans vérification le rapport Lint ancien de 210 warnings et 6 erreurs : il précède les corrections API 31 (`GnssCapabilities.hasMeasurements`) et API 33 (`GnssAutomaticGainControl`). L'utilisateur avait indiqué qu'une version antérieure avait compilé sans erreur, mais cela ne prouve pas la réussite d'`assembleDebug` ni de `lintDebug` pour le SHA contenant R1.

**Action :** contrôler GitHub Actions sur le SHA qui contient effectivement R1, récupérer l'APK Debug et les rapports Lint provenant du même run, puis mettre à jour les résultats/artefacts dans le README et ce dossier.

---

# 10. SDK, politique Google Play et versions

## 10.1 Politique retenue au 24 novembre 2026

La documentation Google Play indique que depuis le 31 août 2026, les nouvelles applications et mises à jour mobiles doivent cibler Android 16/API 36 ou plus. `targetSdk 36` est donc le choix conforme connu à cette date.

`minSdk 24` est un choix de compatibilité produit (Android 7.0+), pas une obligation imposée par Google Play. L'objectif est de garder la compatibilité aussi large que possible tout en appliquant les garde-fous API.

`compileSdk 37.2` est une exigence de compilation imposée par les AAR résolus ; cela ne force pas l'application à cibler API 37.

## 10.2 Évolution future de targetSdk 37

Ne passer à `targetSdk 37` qu'après :

- vérifier la politique Google Play effective à la date du changement ;
- lire les changements de comportement Android 17/API37 ;
- tester orientation, redimensionnement, multi-fenêtre, tablettes et pliables (les comportements grands écrans sont susceptibles de changer) ;
- tester foreground service, permissions, notifications, stockage et Edge-to-Edge ;
- revoir les comportements de l'UI et les régressions ;
- compiler et faire des essais sur API36 et API37.

## 10.3 Contrôle des versions à l'avenir

Éviter les dépendances dynamiques (`9.5.+`, `latest.release`) : elles rendent les builds non reproductibles. Garder les versions épinglées, surveiller les notes officielles, puis faire des mises à jour ciblées et compilées.

Sources officielles à consulter à nouveau avant une nouvelle mise à jour :

- Google Play target API : <https://developer.android.com/google/play/requirements/target-sdk>
- AGP / compatibilité Gradle : <https://developer.android.com/build/releases/about-agp>
- Notes de versions AndroidX / WorkManager : <https://developer.android.com/jetpack/androidx/releases/work>
- Documentation Android API GNSS : <https://developer.android.com/reference/android/location/GnssCapabilities>
- `GnssAutomaticGainControl` : <https://developer.android.com/reference/android/location/GnssAutomaticGainControl>

---

# 11. GitHub Actions — facturation et quotas évoqués

L'utilisateur a demandé si les messages de facturation GitHub signifiaient que les compilations seraient bientôt payantes.

**Le plan GitHub réel de l'utilisateur et la présence d'une carte bancaire n'ont pas été confirmés.** Ne pas supposer qu'il est sur GitHub Free seulement parce qu'il utilise ChatGPT Free.

À titre de référence, la documentation GitHub consultée le 24 novembre 2026 indique pour GitHub Free :

- 2 000 minutes de calcul par mois pour les runners standard (équivalent de 12 USD de calcul inclus dans la table de facturation) ;
- 500 Mo d'artefacts ;
- 10 Go de cache par dépôt.

Le plan GitHub Pro peut avoir des quotas supérieurs. Les valeurs doivent être re-vérifiées dans Billing car les prix et quotas peuvent évoluer.

Le dernier journal fourni montrait environ **172 Mo de cache restauré** pour Gradle, bien en dessous de 10 Go.

La CI publie APK et rapports Lint. Le YAML actuel ne fixe pas `retention-days` ; une rétention de 14 jours a été proposée comme amélioration, mais n'est pas présente dans le fichier de workflow de la dernière archive vérifiée. Cela aiderait à maîtriser le stockage d'artefacts.

Pour vérifier le compte : <https://github.com/settings/billing> puis « Usage » / « Budgets and alerts ». L'utilisateur peut établir un budget et activer les alertes ; lorsqu'une option « stop usage when budget limit is reached » est disponible pour le produit concerné, l'activer si l'objectif est d'empêcher les dépassements payants.

Documentation officielle : <https://docs.github.com/en/actions/reference/limits> et <https://docs.github.com/en/billing/concepts/product-billing/github-actions>.

---

# 12. Limites, risques et validations encore nécessaires

Même si l'utilisateur a confirmé que « c'est passé », une CI verte ne signifie pas que toutes les fonctions sont déjà validées sur du matériel réel.

## 12.1 Tests sur appareils Android

Prévoir plusieurs niveaux (au minimum API24/25, API29/30, API31/32, API33, API34, API35/36, API37 selon disponibilité) et plusieurs fabricants/chipsets :

- démarrage/arrêt/suspension/reprise du foreground service ;
- écran éteint pendant une heure ou davantage ;
- mode économie de batterie ;
- tunnels/perte et retour GNSS ;
- cadence effective et timestamps ;
- notification refusée/bloquée ;
- permission localisation retirée pendant le suivi ;
- arrêt depuis écran Apps actives Android ;
- redémarrage processus et détection d'interruption ;
- réabonnement et callbacks tardifs ;
- API24–30 sans méthodes GNSS plus récentes ;
- API31+ `hasMeasurements()` ;
- API33+ AGC ;
- NMEA / Full Tracking et limitations écran éteint.

## 12.2 GPX et stockage

- démarrage/arrêt rapide de plusieurs segments ;
- erreur SAF, autorisation révoquée, document supprimé ;
- espace disque insuffisant, stockage lent, erreur writer ;
- compteurs acceptés/queue/sérialisés cohérents ;
- mort du processus et récupération du journal ;
- gros fichier externe, XML malformé, DTD, extension inconnue ;
- timestamps absents et altitudes absentes ;
- GPX 1.0/1.1, namespace préfixé, plusieurs segments ;
- traces multi-jours et traversant l'antiméridien ;
- coordonnée `(0,0)` selon la politique utilisateur ;
- corpus GPX provenant d'autres logiciels (Garmin, Komoot, Strava, GPXSee, Organic Maps, etc.).


**Compléments spécifiques à R1 et à l'audit du 24 novembre :**

- le journal d'état R1 ne répare pas le GPX ; `safeLengthBytes=0` est encore persisté ;
- un nouveau suivi peut remplacer le snapshot de session précédent : le futur registre de récupération doit pouvoir garder plusieurs sessions/segments interrompus ;
- `openRecordingSegment()` ouvre encore le fichier, crée le writer, écrit l'en-tête et fait un `flush()` de façon synchrone sur le thread du service, alors que les écritures de points sont sérialisées dans la FIFO `ioFile` ; cette ouverture doit devenir une barrière FIFO ;
- l'ordre des éléments GPX 1.0 dans `GpxWriter.pointXml()` est à corriger/valider contre le XSD : les champs `course`, `speed` et les champs de précision doivent suivre exactement la séquence du schéma ;
- validation lexicale stricte des décimaux XSD à revoir : `Double.parseDouble()` accepte la notation exponentielle qui n'est pas une lexicalisation `xsd:decimal` ;
- vérifier que le contrôle `(0,0)` est appliqué aux points écrits/reconstruits, pas seulement au parseur et au filtre amont ;
- la sémantique d'altitude Android (ellipsoïde WGS84 vs MSL) doit être décidée avant de prétendre homogénéiser les `<ele>` GPX.

## 12.3 Cartographie

- installer au moins deux véritables cartes Mapsforge `.map` adjacentes ;
- vérifier fusion/deduplication, trous de couverture, frontières, changement de version ;
- mise à jour/suppression pendant que MapActivity détient des leases ;
- arrêt/reprise pendant un téléchargement ;
- espace disque insuffisant ;
- redémarrage et relecture offline ;
- couper le réseau après téléchargement et redémarrer l'application ;
- vérifier que rien ne charge des tuiles raster ;
- tester grande trace GPX, segments et antiméridien ;
- tester orientation Nord/Cap, boussole, rotation et tailles d'écran.

Aucun vrai fichier régional complet `.map` ni test visuel Android n'a été fourni dans les premiers lots. Les tests JVM de parsing/projection n'équivalent pas à un essai du moteur Mapsforge sur appareil.

## 12.4 Sécurité, confidentialité et publication

Observations à revoir avant publication :

- le manifeste contient `android:allowBackup="true"` ; revoir les règles Auto Backup/Device Transfer et la sensibilité des données de session/localisation ;
- vérifier stockage des traces privées et exports SAF ;
- préparer la page Attribution et licences OpenStreetMap/Mapsforge ;
- vérifier les licences et notices de Mapsforge (LGPL et notices associées) ;
- établir une vraie signature Release, sécuriser keystore/secret GitHub, ne jamais inclure le keystore dans un ZIP public ;
- augmenter `versionCode` au fil des versions et définir une convention `versionName` cohérente ;
- exécuter Android Lint complet et traiter warnings prioritaires ;
- vérifier les déclarations Play Console du foreground service `location` et les politiques applicables.

---

# 13. Feuille de route de reprise — ordre recommandé

Les étapes ci-dessous sont ordonnées par dépendance et risque. Ne pas démarrer R2 tant que la base exacte et la CI R1 n'ont pas été confirmées.

## Étape 0 — identifier la base de travail réelle

1. Obtenir/consulter le commit courant de `main` et la date du dernier push.
2. Comparer le contenu de `main` à `ETAPE_15.zip` et vérifier que R1 (`SessionJournal`, `SessionStore`, changements `TrackingService`/`MainActivity`) est bien présent.
3. Dans la copie locale de l'utilisateur, exécuter `git status --short` et `git log -1 --oneline`; ne jamais écraser un travail local non commité.
4. Vérifier le dernier run GitHub Actions lié au SHA courant : tests JVM, `assembleDebug`, `lintDebug`.
5. Noter SHA, numéro de run, résultat, nombre exact d'avertissements, APK et rapports téléchargés.
6. Ne pas affirmer qu'une archive sans `.git` est identique au `main` distant.

## Étape 1 — clôturer réellement LOT 8C-R1

- appliquer le correctif R1 dans le dépôt local en conservant `.git` ; le guide `APPLIQUER_CORRECTIF_LOT_8C_R1.md` donne la liste exacte des fichiers ;
- vérifier que seuls les fichiers prévus sont modifiés/ajoutés ;
- commit descriptif : `LOT 8C-R1: rend le journal de session atomique et non destructif` ; push `main` ;
- confirmer le run CI du SHA poussé ; corriger les diagnostics Android/Lint avant d'entamer R2 ;
- faire un test manuel minimal : démarrer une session, forcer la fermeture d'Activity/revenir, vérifier la lecture de notice ; ne pas interpréter ce test comme récupération GPX.

## Étape 2 — fusion sélective de l'instantané local (lot de fusion contrôlé)

Les améliorations de l'archive locale sont intéressantes, mais le ZIP est incomplet et pré-R1. Constituer une branche/copie de travail issue de la base ETAPE_15 confirmée. Fusionner par petit groupes avec tests, jamais par extraction globale :

1. `Units`, `Utils`, formats/notation de coordonnées, préférences et strings ;
2. robustesse satellites, statut inconnu vs zéro, filtrage finites/valeurs impossibles, rendu stale ;
3. `GpxParser` et `GpxPreservation` versionnés, après ajout des correctifs lexicaux et tests XSD ;
4. `GpxWriter` et gardes d'altitude/coords après conformité XSD ;
5. `TrackingState` par blocs, en préservant l'identité des snapshots/observateurs ;
6. modifications de `MainActivity` manuellement, en conservant le contrat R1 `SessionStore.read()` / `markNoticeShown()` ;
7. modifications de `TrackingService` ligne par ligne : ne pas remplacer le fichier entier, préserver `RecordingSegment`, les files, compteurs, synchronisation et journaux R1.

Le concept `LastKnownPosition` peut être conservé, mais pas son écriture synchrone dans le callback GNSS. Le considérer comme un cache UX secondaire, jamais comme source de récupération du GPX.

## LOT 8C-R2A — conformité et intégrité GPX

Objectif : faire des fichiers émis et nettoyés des GPX valides pour la version choisie, sans perdre les extensions tierces.

- corriger l'ordre exact des éléments `trkpt` GPX 1.0 conformément au XSD : notamment `ele`, `time`, `course`, `speed`, champs descriptifs/extension/accuracy dans l'ordre prévu ; ne pas simplement déplacer les éléments à l'aveugle ;
- garder une branche d'écriture GPX 1.1 conforme à son propre XSD ; vitesse/course spécifiques dans une extension étrangère de namespace stable, pas comme enfants directs si le schéma 1.1 ne les autorise pas à cet endroit ;
- décider et figer l'URI de namespace GPS-Test avant qu'une version publique ne l'émette ; conserver la lecture des anciens fichiers émis avec l'ancienne URI si applicable ;
- appliquer une validation lexicale `xsd:decimal` aux champs connus : pas d'exposant, pas de NaN/Infinity, format plain decimal pour la sortie ; préférer `BigDecimal.toPlainString()` lorsque la représentation doit éviter `1.0E-10` ;
- traiter la borne `course=360` conformément à la version du schéma (GPX 1.0 autorise 0 à 360 inclus) ;
- couvrir les autres scalaires connus et versionnés (`vdop`, `pdop`, `geoidheight`, `magvar`, `sat`, `ageofdgpsdata`, `dgpsid`, `fix`, etc.) sans jeter les fragments d'extensions inconnues ;
- appliquer explicitement la politique `(0,0)` au niveau writer et chemin de nettoyage ;
- valider les sorties par les XSD GPX 1.0 et 1.1 versionnés localement, pas seulement par des tests de chaîne ;
- fixer la politique d'altitude : API Android `Location.getAltitude()` = ellipsoïde WGS84 ; API 34+ propose MSL ; choisir une sémantique claire et ne jamais utiliser une séparation géoïde NMEA périmée ou non appariée au fix.

**Critères de sortie R2A :** corpus de tests couvrant les versions 1.0/1.1, namespaces, ordre, décimaux, champs absents/invalides, `course=360`, `(0,0)`, altitude manquante, extensions tierces ; tous les GPX produits et nettoyés passent leur XSD ; l'original reste inchangé en cas d'échec.

## LOT 8C-R2B — ouverture de fichier, FIFO et checkpoint durable

Objectif : aucune E/S lente d'ouverture/écriture/sync ne doit bloquer le traitement GNSS ou la file de callback.

- déplacer `FileOutputStream`, `BufferedWriter`, l'en-tête et le premier `flush()` dans la file FIFO d'écriture GPX ; l'ouverture devient une barrière `OPENING → OPEN` ou `FAILED` ;
- tous les points suivants sont placés derrière cette barrière ; en cas d'échec d'ouverture, ne pas compter les points comme écrits et signaler distinctement les points acceptés mais non enregistrés ;
- préserver l'ordre accepted → queued → written → flushed → durable checkpoint ; ces notions ne sont pas interchangeables ;
- garder les compteurs séparés et documenter exactement leur sémantique UI ;
- publier un `safeLengthBytes` pour l'identité session + segment seulement après que tous les octets du préfixe correspondent aux points précédents, `flush()` a réussi et `FileDescriptor.sync()` a réussi ;
- sérialiser le checkpoint dans `ioMeta`/store avec révision et identité, sans laisser un ancien checkpoint dépasser un nouveau fichier/segment ;
- définir le comportement en erreur de `sync()` : avertissement durabilité, conserver le dernier offset confirmé, ne jamais avancer le checkpoint ;
- tester la file pleine (profondeur nominale 120), carte SD lente, espace épuisé, exception d'écriture, sortie de worker, flush idle, close et sync.

**Critères R2B :** pas d'E/S disque bloquante sous `gnssProcessingLock` ou dans la callback GNSS ; les tests montrent que tout checkpoint se réfère à un préfixe effectivement synchronisé ; les compteurs ne comptent pas de point non écrit comme écrit ; ordre FIFO prouvé par test.

## LOT 8C-R2C — registre multi-sessions et récupération idempotente

- créer un manifeste durable/registre de récupérations en attente, indexé par UUID de session et identifiant de segment ; un nouveau START ne doit pas perdre la référence à une trace antérieure interrompue ;
- à la détection d'interruption, identifier la trace, le chemin interne, la version GPX, le dernier offset confirmé, le segment et l'état de publication ;
- réparer une **copie** ou un nouveau fichier temporaire à partir du préfixe vérifié ; ne jamais modifier le fichier source en place ;
- valider XML et XSD, vérifier les octets/offsets et ajouter uniquement les fermetures GPX nécessaires ; ne pas inventer de points, d'heures ou de métadonnées ;
- publier le résultat final de façon atomique/à étapes avec état COMMITTING et idempotence ; les destinations SAF ne doivent jamais remplacer un document sain par un résultat partiel ;
- garder l'original abîmé à disposition pour diagnostic, jusqu'à validation et règle de purge explicitement définie ;
- injecter une interruption à chaque frontière : append, flush Java, fd.sync, checkpoint, footer, close, copie temp, validation, création SAF, écriture SAF, commit du registre.

**Critères R2C :** pour chaque point de crash simulé, reprise déterministe, aucun doublon, pas de faux segment reliant des intervalles, original préservé, résultat validé ou erreur explicite ; relancer la réparation plusieurs fois ne modifie pas le résultat (idempotence).

## LOT 8C-R3 — travaux différés persistants WorkManager

- introduire un manifeste durable des travaux de réparation/nettoyage/publication ;
- tâches `PENDING`, `RUNNING`, `COMMITTING`, `SUCCEEDED`, `FAILED`, `RETRYABLE` ; identifiants uniques, tokens et verrou par trace ;
- réconcilier au démarrage manifeste et WorkManager ; les travaux interrompus sont replanifiés sans duplications ;
- valider résultat, préserver original, gérer retries/backoff, quota, stockage insuffisant et droits SAF révoqués ;
- ne jamais confier l'acquisition GNSS temps réel à WorkManager.

## LOT 8C-R4 — visibilité, diagnostic et énergie

- revoir les champs partagés de `TrackingState`, threads écrivains/lecteurs, visibilité Java et cycle de reset ; privilégier des snapshots immuables pour l'UI ;
- étudier les idées historiques `LocationAcquisitionMonitor`, `LocationPowerSavePolicy` et `SessionExitInfoReader` sans les importer sans tests ;
- afficher cadence demandée, cadence reçue, âge du dernier fix, latence callback, statut fournisseur, abonnements actifs, événements rejetés et état d'écriture ;
- séparer cause observée, cause supposée et donnée indisponible ; `ApplicationExitInfo` reste indicatif/non exhaustif ;
- n'ajouter une stratégie d'économie d'énergie ou de WakeLock qu'après mesure comparative sur appareils réels.

## LOT 8D — intégration Android, terrain et non-régression

- tester API 24, 28/29, 31/32, 33, 34, 35/36 et la version courante disponible ; plusieurs fabricants/chipsets ;
- tests d'écran éteint, économie batterie, permission retirée, fournisseur GPS off/on, mode avion, notification refusée, swipe-away, force-stop, crash, reboot ;
- tests de longues sessions, tunnels, reprise, horloge système modifiée, changement fuseau, synchronisation UTC, batterie faible ;
- stockage saturé, stockage amovible lent, SAF révoqué, accès dossier supprimé, provider DocumentFile défaillant ;
- GPX externe réel multi-logiciels, traces de plusieurs heures/jours, grands fichiers, extensions, namespaces, antiméridien, données malformées ;
- installer de vraies cartes Mapsforge adjacentes, couper le réseau et redémarrer, vérifier rendu, leases et suppression/mise à jour ;
- rotation, grandes tailles d'écran, gestes, clavier, Edge-to-Edge, TalkBack, RTL et tailles de texte.

## LOT 9 — publication / confidentialité (après stabilisation fonctionnelle)

- décider de manière permanente `applicationId`, `namespace`, nom produit et convention de version ; `com.example.gpstest` ne doit pas être publié par défaut si un identifiant définitif est prévu ;
- définir les règles Auto Backup / Device Transfer pour exclure ou maîtriser traces GNSS, journal d'état et positions, après analyse des besoins ; `allowBackup=true` est présent actuellement ;
- publier une politique de confidentialité exacte, Data safety et justification/description du foreground service `location` ;
- vérifier le stockage privé, partage SAF/FileProvider, effacement et export explicites ;
- produire AAB Release signé, keystore hors ZIP, secrets CI, proguard/R8 évalué et procédure de rotation de clé documentée ;
- attribution OpenStreetMap/Mapsforge et licences/NOTICE dépendances ;
- tester sur installation propre et mise à niveau d'une version précédente ;
- confirmer les règles Google Play actuelles au moment du dépôt, ne pas se fier à une règle de 2026 dans une conversation future.

---

# 14. Procédure de travail à conserver pour chaque prochaine session

L'utilisateur travaille principalement depuis l'interface Web et GitHub Desktop. La passation doit donc être opérationnelle et reproductible.

1. Commencer par lire ce dossier, puis identifier l'archive complète ou le commit le plus récent réellement fourni/confirmé.
2. Si un dépôt Git local est disponible, vérifier `git status --short`, `git branch --show-current`, `git log -1 --oneline`, puis `git diff --stat` avant tout changement.
3. Ne pas demander à l'utilisateur de répéter une décision déjà consignée ici.
4. Ne jamais présumer qu'une archive ZIP correspond au `main` distant ; comparer son contenu/SHA ou le commit Git quand l'accès existe.
5. Créer une copie de travail issue de la base exacte ; ne jamais mélanger ETAPE_14, ETAPE_15 et le ZIP des modifications locales sans stratégie explicite.
6. Avant de modifier : définir objectif, fichiers concernés, invariants, risques de régression et critères de sortie.
7. Travailler en petits lots ; préserver les classes non concernées ; éviter la substitution complète d'un service central.
8. Ajouter tests de régression ciblés d'abord, puis exécuter toute la suite JVM et les audits statiques.
9. Exécuter le vrai build Android/Lint par GitHub Actions ou environnement Android correctement configuré ; distinguer ce résultat des tests JVM.
10. Pour les sujets GPX, valider contre les XSD réels versionnés ; des assertions sur fragments XML ne suffisent pas.
11. Pour les changements cycle de vie/GNSS/SAF, prévoir des essais Android instrumentés et sur matériel, car des stubs JVM n'imitent pas le framework ni les fournisseurs OEM.
12. Ne jamais prétendre avoir testé un cas non exécuté ; noter environnement, versions, commande, résultat exact et limitations.
13. Fournir un ZIP de patch minimal avec chemins de remplacement et un projet complet seulement si nécessaire ; inclure la note de lot et la somme SHA-256.
14. L'utilisateur applique dans le dossier racine de son dépôt local, conserve `.git`, examine l'onglet Changes, commit et pousse.
15. Vérifier la CI du SHA poussé, et non un run ancien ; récupérer APK et rapport Lint du même run.
16. Mettre à jour README, notes de lot et dossier de reprise à la fin de chaque phase importante.

Ne pas appliquer un ZIP à la racine du dépôt sans lire son contenu et son niveau de complétude. En particulier, le ZIP local du 24 novembre est une extraction partielle de fichiers modifiés, pas un snapshot complet.

---

# 15. Fichiers, archives, provenance et hiérarchie d'autorité

## 15.1 Sources complètes

| Fichier | Rôle | SHA-256 / état |
|---|---|---|
| `ETAPE_15.zip` | dernière source complète examinée ; inclut LOT 8C-R1 | `9d476a3db724ec5a2cf5018d6d071137a00f6e8816796f8654ce8b889e3c301d` |
| `ETAPE_14.zip` | source complète juste avant R1 | `bc82d06f816cab69c6deb5f4ee7faa59c1fe969215eea825771d49996c1d35d2` |
| `GPS-Test-lot-8B-Android-37.2-Lint-AGC-corrige-2026-11-24(1).zip` | source consolidée historique antérieure à R1 | historique ; ne pas préférer à ETAPE_15 |
| `ETAPE_13.zip` | archive consolidée antérieure | historique seulement |

ETAPE_15 est la base de travail recommandée **sous réserve de comparaison au `main` courant**. Elle ne contient pas son propre `.git` dans l'extraction fournie.

## 15.2 Correctif R1 ciblé

`GPS-Test-LOT-8C-R1-Journal-Durable-2026-11-24.zip`  
SHA-256 : `e3e8ed5537bf6f36c1a891a9ffd62393f89dd06388a86a593aa9e1aafefbbe3a`.

Fichiers du correctif listés dans `APPLIQUER_CORRECTIF_LOT_8C_R1.md` :

- `README.md` ;
- `app/src/main/java/com/example/gpstest/SessionJournal.java` ;
- `SessionStore.java` ;
- `TrackingService.java` ;
- `MainActivity.java` ;
- `tests/SessionJournalTest.java` ;
- `tests/SessionStoreTest.java` ;
- stubs `tools/stubs/android/content/Context.java` et `tools/stubs/android/util/AtomicFile.java` ;
- `docs/LOT_8C_R1_JOURNAL_DURABLE.md` ;
- `APPLIQUER_CORRECTIF_LOT_8C_R1.md`.

Conserver `.git`. La procédure ETAPE_15 demande un commit R1, push, puis CI (JVM, APK, Lint) sur le SHA poussé.

## 15.3 Documents d'audit à connaître

| Document | Ce qu'il apporte | Statut |
|---|---|---|
| `AUDIT CHATGPT 1(3).docx` | audit initial remis par l'utilisateur : horloges, publication GPX, compteurs, FGS, GNSS Expert, GPX, cartes, Edge-to-Edge et demandes de corrections | historique initial ; de nombreux points ont depuis été traités |
| `GPS-Test_DOSSIER_REPRISE_EXHAUSTIF_2026-11-24(1).md` | ancien dossier exhaustif centré sur ETAPE_13 / LOT 8B | historique ; remplacé par le présent dossier |
| `GPS-Test_Dossier_Reprise_2026-11-24(1).md` | passation synthétique antérieure | historique |
| `GPS-Test_AUDIT_COMPARATIF_ROBUSTESSE_2026-11-24.md` | comparaison avec architecture historique 3.6, journal de session, crash recovery, WorkManager et tests | analyse de conception |
| `Audit_GPS_Test_2026-11-24.md` | audit technique détaillé du checkout indiqué au commit `64bb1a8c82449473ba50830ff1825c8c69be03b9` (LOT 8B-F) | audit approfondi, pas un rapport de build R1 |
| `Modifications_locales_GPS-Test_2026-11-24.md` | inventaire de l'instantané local non commité | source descriptive des changements locaux |
| `GPS-Test_ANALYSE_MODIFICATIONS_LOCALES_2026-11-24.md` | analyse croisée ETAPE_14 + ZIP local, garde-fous de fusion | conclusions à utiliser pour fusion sélective |
| `PASSATION/NOTE_ETAPE_15.md` | état et tests de l'étape R1 | note de livraison la plus récente pour R1 |
| `docs/LOT_8C_R1_JOURNAL_DURABLE.md` | contrat, scénarios de tests et limites R1 | note technique du lot |

## 15.4 Instantané des modifications locales — ne pas appliquer en bloc

Fichier : `GPS-Test_modifications_locales_2026-11-24.zip`  
SHA-256 : `5df742da568a609dab3390bf07c7dc65c1f334fdfb291a8c114101f661acd2b9`.

- Archive de 46 entrées, environ 1 124 025 octets décompressés ; CRC vérifié.
- Le document d'audit indique un checkout de départ au commit `64bb1a8c82449473ba50830ff1825c8c69be03b9`, branche `main`, LOT 8B-F ; l'inventaire Git indique que les changements locaux n'étaient pas commités.
- L'inventaire final rapporte 39 fichiers suivis modifiés et 4 nouveaux fichiers ; aucun fichier source suivi supprimé dans le diff observé.
- Le ZIP contient des fichiers ciblés modifiés, les quatre nouvelles classes/tests, les deux documents d'audit et un fragment de README. Ce n'est pas un projet complet.
- Le ZIP **ne contient pas** `SessionJournal.java` ni `SessionStore.java`.
- `MainActivity.java` et `TrackingService.java` viennent d'un état pré-R1. L'ancienne logique `SessionStore.consume()` et la persistance non atomique/externe peuvent annuler les garanties R1 si elles remplacent les versions ETAPE_15.
- La règle impérative : extraire les améliorations utiles fichier par fichier dans une branche issue d'ETAPE_15 ; ne jamais remplacer entièrement `TrackingService.java` ou `MainActivity.java`.

## 15.5 Anciennes livraisons et archives intermédiaires

Les lots précédents (ETAPE_1 à ETAPE_13, archives 7F, 7G, 8A, 8B, correctifs Java/API, compileSdk, Lint, AGC) sont utiles pour retrouver une décision ou un diff, mais ne doivent pas être pris comme base sans raison documentée. À périmètre identique, choisir le snapshot complet confirmé le plus récent, pas le plus petit ZIP correctif.

## 15.6 Fichiers internes utiles dans ETAPE_15

- `README.md` : historique et commandes, mais certaines lignes historiques peuvent être dépassées ; vérifier cohérence des nombres 38/39 suites et de l'état CI.
- `docs/` : documentation détaillée des lots 1 à 8C-R1 ; commencer par les notes du lot touché.
- `tests/` : 39 classes `*Test.java`, exécutées par le runner autonome.
- `tools/run_jvm_tests.sh` / `.py` : compilation commune des sources de test et exécution isolée par suite ; parsing du compte d'assertions sur le libellé « N réussis, 0 échoués ».
- `tools/verify_android_project.py` : cohérence ressources/manifeste/permissions/SDK/insets.
- `tools/verify_offline_map_architecture.py` : garde-fou contre moteur/fournisseur raster.
- `.github/workflows/android-build.yml` : chaîne CI, génération temporaire du wrapper, tests, assembleDebug, lintDebug et artefacts.

---

# 16. Texte de démarrage à copier dans une nouvelle conversation

Copier le texte ci-dessous dans un nouveau chat et joindre **ce dossier Markdown**, `ETAPE_15.zip` ou une archive fraîche du dépôt, ainsi que le dernier rapport GitHub Actions si disponible.

> Nous reprenons le développement de mon application Android GPS-Test. Lis intégralement le fichier `GPS-Test_DOSSIER_REPRISE_ULTRA_EXHAUSTIF_2026-11-24.md` avant de proposer une modification : il contient les choix utilisateur, l'historique des lots, l'état précis ETAPE_15 / LOT 8C-R1, les audits, les changements locaux non intégrés et la feuille de route.
>
> Dépôt GitHub connu : `https://github.com/Xantia500/GPS-Test`, branche `main`. L'accès direct au dépôt a déjà échoué depuis une session précédente sans prouver que le dépôt était privé. Vérifie le SHA courant de `main` et compare-le à l'archive jointe avant toute modification. La dernière source complète examinée le 24 novembre 2026 est `ETAPE_15.zip` (SHA-256 `9d476a3db724ec5a2cf5018d6d071137a00f6e8816796f8654ce8b889e3c301d`), qui comprend le LOT 8C-R1.
>
> ETAPE_15 est validée localement sur 39 suites JVM, 1 345 assertions, zéro échec ; `verify_android_project.py` a vérifié 17 XML et 80 sources Java ; l'audit cartographique a vérifié 98 fichiers et n'a trouvé aucun moteur/fournisseur raster. Ces validations ne remplacent pas `assembleDebug`, `lintDebug`, instrumentation ou essais sur appareil. La note ETAPE_15 indique que le build Android et Lint du commit R1 doivent être confirmés dans GitHub Actions. Ne prétends pas qu'un build R1 a réussi sans le run et le SHA correspondants.
>
> Configuration : Gradle 9.9.2, AGP 9.5.2, JDK CI 17, compileSdk 37.2, Build Tools 36.0.0, targetSdk 36, minSdk 24, Mapsforge 0.25.0, WorkManager 2.12.0. L'archive ne versionne pas encore le wrapper Gradle ; le workflow le génère temporairement.
>
> Le journal de session R1 (`SessionJournal` format 4 + `SessionStore` AtomicFile) est non destructif et protégé par UUID/révision, mais ne restaure pas encore le GPX : `safeLengthBytes` vaut toujours 0 ; pas de registre multi-sessions ni de réparation idempotente. Il faut impérativement conserver ces garanties lors des prochains changements.
>
> Les trois fichiers locaux de l'audit du 24 novembre sont une source d'améliorations, mais `GPS-Test_modifications_locales_2026-11-24.zip` est un patch partiel pré-R1 et ne contient ni `SessionJournal.java` ni `SessionStore.java`. Ne remplace jamais intégralement `TrackingService.java` ou `MainActivity.java` depuis ce ZIP : fusionne sélectivement dans une branche issue d'ETAPE_15.
>
> Les chantiers prioritaires à traiter : (1) confirmer CI R1 et base Git exacte ; (2) fusion locale sélective ; (3) corriger/valider l'ordre GPX 1.0/1.1, le lexique `xsd:decimal`, le cap 360° et la politique `(0,0)` contre les XSD ; (4) clarifier le datum altitude ellipsoïde/MSL ; (5) déplacer l'ouverture GPX dans une barrière FIFO ; (6) écrire des checkpoints seulement après flush + fd.sync ; (7) registre multi-sessions et réparation GPX idempotente ; (8) WorkManager pour les travaux différés ; (9) tests Android/OEM/stockage/SAF ; (10) confidentialité et publication.
>
> Respecte les invariants : fix 2D acceptable en orange, pas de donnée inventée, `(0,0)` invalide, altitude finie entre −10 000 et +10 000 m seulement, timestamps monotones pour les durées et UTC/civils pour GPX, coupures en `<trkseg>`, Mapsforge local seulement, pas de WakeLock permanent ni de `ACCESS_BACKGROUND_LOCATION` par réflexe. Conserve le point décimal `Locale.ROOT`.
>
> Travaille par lots courts, ajoute des tests, exécute 39 suites JVM + audits statiques, fais valider le build Android/Lint par GitHub Actions sur le SHA final, fournis les sommes SHA-256 et mets à jour la documentation. Distingue toujours résultat local, résultat rapporté par moi, résultat CI et essai sur appareil. Ne demande pas à nouveau une décision déjà présente dans le dossier.

---

# 17. Références techniques utiles — à revérifier au moment du changement

Les règles Android, Google Play, Gradle, GitHub Actions et les versions de dépendances changent. Ces liens sont des points d'entrée, pas une garantie que l'information sera encore actuelle dans une future conversation.

- Google Play — exigences target API : <https://developer.android.com/google/play/requirements/target-sdk>
- Android Gradle Plugin : <https://developer.android.com/build/releases/about-agp>
- Android Lint : <https://developer.android.com/studio/write/lint>
- Foreground services : <https://developer.android.com/develop/background-work/services/foreground-services>
- `Location` / altitude ellipsoïde et MSL : <https://developer.android.com/reference/android/location/Location>
- `GnssCapabilities` : <https://developer.android.com/reference/android/location/GnssCapabilities>
- `GnssAutomaticGainControl` : <https://developer.android.com/reference/android/location/GnssAutomaticGainControl>
- WorkManager : <https://developer.android.com/topic/libraries/architecture/workmanager>
- GPX 1.0 XSD : <https://www.topografix.com/GPX/1/0/gpx.xsd>
- GPX 1.1 : <https://www.topografix.com/GPX/1/1/>
- GPX validation : <https://www.topografix.com/gpx_validation.asp>
- GPX developer guidance : <https://www.topografix.com/gpx_for_developers.asp>
- OSM tile usage policy : <https://operations.osmfoundation.org/policies/tiles/>
- OSM copyright / attribution : <https://www.openstreetmap.org/copyright>
- Mapsforge : <https://github.com/mapsforge/mapsforge>
- Mapsforge Android 0.25.0 Maven Central : <https://central.sonatype.com/artifact/org.mapsforge/mapsforge-map-android/versions>
- GitHub Actions limits : <https://docs.github.com/en/actions/reference/limits>
- GitHub Actions billing : <https://docs.github.com/en/billing/concepts/product-billing/github-actions>
- GitHub Actions artifacts : <https://docs.github.com/en/actions/using-workflows/storing-workflow-data-as-artifacts>
- Git line endings : <https://docs.github.com/en/get-started/git-basics/configuring-git-to-handle-line-endings>

---

# 18. Audit approfondi du 24 novembre 2026 — provenance et périmètre

Trois nouveaux fichiers utilisateur ont été étudiés :

1. `Audit_GPS_Test_2026-11-24.md` — audit technique profond ;
2. `Modifications_locales_GPS-Test_2026-11-24.md` — inventaire des changements locaux ;
3. `GPS-Test_modifications_locales_2026-11-24.zip` — snapshot de code correspondant.

Ils complètent l'audit initial `AUDIT CHATGPT 1(3).docx` et l'analyse comparative historique, mais ne remplacent pas ETAPE_15 comme base technique du prochain travail.

## 18.1 Provenance de l'audit

`Audit_GPS_Test_2026-11-24.md` est daté du 24 novembre 2026, 18:55 Europe/Paris. Il indique avoir audité le checkout de la branche `main` au commit `64bb1a8c82449473ba50830ff1825c8c69be03b9` (LOT 8B-F). Il décrit un audit statique des sources Java, layouts XML, manifeste, Gradle, GNSS, cycle de vie, FGS, GPX, cartographie, tests et compatibilité API.

L'audit dit explicitement : aucun crash de production n'a été reproduit ; les scénarios appareils/OEM et les interruptions brutales demeurent des validations distinctes. Les résultats de build/tests attribués au mainteneur ne sont pas requalifiés comme une exécution de build par l'auditeur.

## 18.2 Provenance de l'inventaire local

Le fichier d'inventaire indique que les changements étaient dans le répertoire de travail, non commités, et qu'aucun commit n'avait été créé. Son bilan final mentionne **39 fichiers suivis modifiés et 4 nouveaux fichiers non suivis**, sans suppression d'un fichier source suivi dans le diff observé.

Le ZIP contient 46 entrées, environ 1 124 025 octets non compressés, et son contrôle CRC passe. Son SHA-256 est `5df742da568a609dab3390bf07c7dc65c1f334fdfb291a8c114101f661acd2b9`.

Sa structure comprend `GPS-Test/app/...` (fichiers sélectionnés modifiés), `documents/` (les deux rapports Markdown) et `repo/README.md` (extrait). Il ne contient ni le projet Android complet, ni `.git`, ni `SessionJournal.java`, ni `SessionStore.java`.

**Conséquence :** ce ZIP est un paquet de modifications/diff incomplet ; il ne doit pas être extrait tel quel au-dessus du projet utilisateur ou d'ETAPE_15.

---

## 18.3 Correspondance avec l'audit initial `AUDIT CHATGPT 1(3).docx`

L'audit initial de l'utilisateur mettait particulièrement en avant les problèmes de flush GPX, de publication concurrente entre segments, de compteur de points, de limites des FGS et des mesures brutes en arrière-plan, de NMEA/qualité GNSS, d'import GPX et de cartographie. Ces thèmes ont structuré les lots successifs :

| Sujet initial | État dans les bases récentes |
|---|---|
| Mélange `elapsedRealtime()` / `currentTimeMillis()` dans les flush | corrigé dans le code de suivi ; les échéances de flush du `RecordingSegment` utilisent `lastFlushElapsedMs` et temps monotone |
| Tâches de publication d'un ancien segment capturant l'état du nouveau | corrigé par le contexte `RecordingSegment` qui porte fichier, version, destination, URI document et writer ; les tâches capturent ce contexte |
| Compteur affiché en avance sur les écritures | compteurs acceptés, mis en file, écrits et erreurs distingués dans le pipeline récent |
| Cadence demandée 1 seconde | reste une consigne de cadence, pas une garantie par le chipset ; le code doit afficher la cadence réellement reçue |
| Suivi écran éteint / FGS | architecture location FGS initiée depuis activité visible ; tester les scénarios Android/OEM ; ne pas demander automatiquement background location |
| Mesures brutes en arrière-plan | canal de service et CSV séparé, mais livraison non garantie par Android en arrière-plan ; l'état réel doit rester visible |
| Attente de `onDestroy()` pour durer | journal de session R1 rend l'état durable indépendamment d'`onDestroy`; récupération des octets GPX non terminée |
| WakeLock | pas de WakeLock permanent ; ne réexaminer qu'après mesure réelle de perte de CPU/callback |
| Expert, CSV configurables, DOP récepteur et calculé | lots 3–4 ; conserver distinction des sources et de la disponibilité |
| Fix 2D | accepté horizontalement et affiché en orange, sans altitude fabriquée |
| `(0,0)` | décision utilisateur appliquée en amont/import ; renforcer encore la frontière writer/reconstruction |
| Import GPX streaming, timestamps, métadonnées | lots 5–6 ; SAX, garde XML, conservation de fragments ; audit R2 restant pour stricte conformité XSD |
| Version GPX 1.0/1.1 | choix disponible et branches du writer/parser ; défaut d'ordre GPX 1.0 restant à corriger |
| Cartographie hors ligne sans tuiles | lots 7A–7G ; Mapsforge vectoriel local, sans bascule raster |
| Edge-to-Edge et SDK moderne | intégré, contrôles statiques ; validation visuelle réelle encore nécessaire |

Cette table empêche de rouvrir à l'aveugle des points déjà traités, tout en distinguant « correction de code » et « garantie terrain validée ».

# 19. Analyse détaillée des améliorations locales à préserver sélectivement

Les points suivants sont des améliorations crédibles à conserver après rebase et tests. Ils ne sont pas déjà intégrés dans ETAPE_15, sauf si une comparaison future du commit le prouve.

## 19.1 État GNSS et satellites

- distinguer « aucun snapshot de satellites disponible » de « snapshot valide avec zéro satellite » ; ne pas rendre une absence de données comme un 0/0 mesuré ;
- effacer/expirer les snapshots devenus périmés lors de `onStopped`, watchdog et changements d'état ; éviter que des valeurs anciennes restent affichées comme courantes ;
- valider les objets satellites, null, valeurs non finies, C/N0 anormal, fréquence absente ou incohérente ; isoler l'erreur d'un seul élément pour ne pas faire échouer toute la liste ;
- conserver les compteurs des satellites utilisés, en vue, réellement signalés et retenus par le filtre comme des grandeurs distinctes ; ne pas forcer `inView >= used` artificiellement ;
- l'état de fraîcheur du fix ne doit pas griser des compteurs satellites qui sont toujours valides indépendamment du fix ;
- `TrackingState.notifyChanged()` sur main looper, avec coalescence via `AtomicBoolean`, protège un observateur défaillant pour que les autres continuent d'être notifiés ; c'est utile, mais l'objet reste mutable et doit évoluer à terme vers des snapshots immuables.

## 19.2 Données positionnelles transmises au GPX

- transmettre au writer la vitesse et le cap déjà assainis, pas les valeurs brutes de `Location` ;
- accepter un point horizontal valide même si l'altitude est absente/invalide ; omettre `<ele>` plutôt que rejeter le point complet ;
- n'émettre le timestamp que s'il est connu/valide ; aucune date artificielle type 1969 ;
- altitude acceptée uniquement finie et dans [−10 000 m, +10 000 m] inclus, avec la réserve séparée sur le datum ;
- normaliser la longitude +180° vers −180° selon la plage GPX 1.1 retenue ; tester les deux bornes et l'antiméridien ;
- maintenir la coupure de segment en attente jusqu'à ce que le point d'ancrage ait été effectivement accepté dans la FIFO ; si la file refuse, ne pas reconnecter les segments.

## 19.3 Parseur et préservation GPX

- interpréter `<speed>` et `<course>` comme enfants directs sous namespace GPX 1.0 uniquement ; en GPX 1.1, ces informations appartiennent aux extensions étrangères au bon endroit ;
- ne reconnaître les extensions GPS-Test que sous l'URI exacte prévue ; un nom d'élément sans le bon namespace ne suffit pas ;
- conserver les extensions tierces valides, même lorsque les champs connus invalides sont filtrés ;
- remplacer les concaténations répétées `String +=` dans la conservation des fragments par un `StringBuilder` paresseux ; le test de 2 000 fragments évite une accumulation quadratique ;
- appliquer la même limite altitude au parcours de nettoyage/reconstruction qu'au writer principal ;
- les limites mémoire rapportées par l'inventaire : import carte 32 Mio / 250 000 points ; nettoyage 24 Mio / 250 000 points ; profondeur XML 64 ; 128 attributs/élément ; fragments préservés 4 Mio ; file live GPX 120 points ; rendu carte 20 000 points / 2 000 segments. Ces limites bornent la charge, mais n'éliminent pas le risque d'OOM sur appareils faibles.

## 19.4 Interface, préférences, coordonnées et unités

- trois formats de coordonnées : DD (6 décimales), DDM (4), DMS (2) ; notation hémisphère ou signe séparée du format ; valeurs à précision fixe et alignement à droite ;
- migration des anciennes préférences combinées ; les choix utilisateur existants doivent rester prioritaires ;
- choix d'unité vitesse séparé : km/h, mph, nœuds, m/s ; affichage 0 ou 1 décimale ; préférence régionale uniquement au premier démarrage ;
- `Locale.ROOT` pour décimales numériques techniques, car le point décimal a été demandé explicitement ; ne pas réintroduire la virgule automatiquement sur un téléphone français ;
- position mémorisée rendue visuellement ancienne/grisée sans griser des données satellites encore fraîches ;
- résumé GNSS Expert proposé avant les diagnostics détaillés pour rendre fix, âge, satellites, bandes et DOP plus lisibles.

## 19.5 Autres gardes mathématiques

- `FixGeometry` vérifie les coordonnées finies et bornées, et prévient les overflow temporels ;
- `TrackPostProcess.simplify` désactive la simplification pour une tolérance NaN/Inf au lieu de réduire silencieusement la trace ;
- calcul d'intervalle/différence temporelle en `double` après contrôle de l'ordre, pour prévenir les overflow `long` ;
- fréquence porteuse finie requise pour certaines inférences de bande ; fréquences anormales peuvent être conservées en brut mais pas présentées comme identifiants de bande/chipset ;
- C/N0 indisponible n'est pas transformé en barre de signal artificielle ; valeurs non finies/élévation hors domaine ignorées avant le dessin Canvas.

---

## 19.6 Inventaire exact des fichiers du ZIP local

Le manifeste local final énumère les fichiers suivants. Les chemins sont relatifs à la racine du dépôt `GPS-Test/`.

**Code existant modifié (27 fichiers) :**

`app/src/main/java/com/example/gpstest/BandSupport.java`, `Compass.java`, `DopCalculator.java`, `ExpertActivity.java`, `ExportHelper.java`, `FixGeometry.java`, `GpxParser.java`, `GpxPreservation.java`, `GpxWriter.java`, `MainActivity.java`, `MapActivity.java`, `NmeaParser.java`, `OfflineMapsActivity.java`, `Prefs.java`, `RawMeasurementsFormatter.java`, `SatInfo.java`, `SatelliteAdapter.java`, `SatelliteFilter.java`, `SettingsActivity.java`, `SkyView.java`, `StatusHud.java`, `TrackPostProcess.java`, `TrackingService.java`, `TrackingState.java`, `Units.java`, `Utils.java`, `Utm.java`.

**Ressources Android modifiées (3 fichiers) :**

`app/src/main/res/layout/activity_main.xml`, `app/src/main/res/layout/activity_settings.xml`, `app/src/main/res/values/strings.xml`.

**Tests existants modifiés (9 fichiers) :**

`tests/CompassTest.java`, `tests/FixQualityTest.java`, `tests/GnssQualityStateTest.java`, `tests/GpxPreservationTest.java`, `tests/GpxSegmentTest.java`, `tests/GpxStreamingParserTest.java`, `tests/ModesAndPostProcessTest.java`, `tests/NmeaRobustnessTest.java`, `tests/UtilsTest.java`.

**Nouveaux fichiers (4) :**

- `app/src/main/java/com/example/gpstest/CoordinateFormat.java` ;
- `app/src/main/java/com/example/gpstest/CoordinateNotation.java` ;
- `app/src/main/java/com/example/gpstest/LastKnownPosition.java` ;
- `tests/UnitsTest.java`.

Le ZIP ajoute également les deux documents `Audit_GPS_Test_2026-11-24.md`, `Modifications_locales_GPS-Test_2026-11-24.md` et un extrait `README.md`. Il faut compter le manifeste Git (39 suivis modifiés + 4 nouveaux) distinctement des 46 entrées ZIP (qui incluent les deux documents et le README fragment).

# 20. Anomalies concrètes à corriger ou décider avant intégration

Les constats ci-dessous viennent de l'analyse ligne par ligne de l'instantané local, recoupée avec ETAPE_15. Ils doivent être revalidés sur le diff exact au moment de la fusion, mais les défauts explicitement marqués « confirmé » ne doivent pas être écartés.

## 20.1 P1 — ordre des éléments GPX 1.0 incorrect

**Constat confirmé dans ETAPE_15 et le ZIP local :** `GpxWriter.pointXml()` émet actuellement, lorsque les champs sont présents, un ordre observé du type `ele`, `time`, `hdop`, puis `speed`, puis `course`. L'ordre XSD GPX 1.0 attend notamment `ele`, `time`, `course`, `speed`, les éléments descriptifs/extensions, puis les éléments de précision dont `hdop`. L'ordre `speed`/`course` est lui-même inversé et `hdop` apparaît trop tôt.

**Impact :** un GPX 1.0 peut être bien formé XML mais invalide contre le schéma, dès que plusieurs champs optionnels sont présents. Les lecteurs tolérants peuvent le charger, mais un outil strict peut le refuser ou ignorer des données.

**Correction :** produire des branches dédiées par version et écrire tous les enfants dans l'ordre XSD exact. Ajouter tests structurels et validation par le XSD officiel 1.0 épinglé. Garder la branche GPX 1.1 distincte (en particulier `extensions`).

Référence : XSD Topografix GPX 1.0 : <https://www.topografix.com/GPX/1/0/gpx.xsd>.

## 20.2 P1 — lexique `xsd:decimal` insuffisamment strict

`GpxPreservation.validKnownScalar()` utilise `Double.parseDouble()`. Celui-ci accepte par exemple `1e2`, `NaN` et `Infinity` selon le texte, alors que le type XML Schema `decimal` n'accepte pas l'exponentielle ni les valeurs non finies. Il faut séparer parse numérique Java et lexique XML accepté.

Autre piège : `Double.toString(value)` peut produire `1.0E-10`, donc l'écriture de secours peut réintroduire une notation exponentielle. Utiliser une représentation décimale non exponentielle, par exemple `BigDecimal.valueOf(value).toPlainString()`, avec une politique d'échelle/arrondi définie.

Mettre en place un validateur commun par version et champ, plutôt qu'une suite de `Double.parseDouble` permissifs. Ne pas modifier les fragments d'extensions tierces inconnues.

## 20.3 P1 — vérifier `course=360` inclusif

Le type GPX 1.0 pour le cap définit un intervalle allant de 0 à 360 inclus. Le parseur accepte la borne 360, mais `GpxPreservation.validKnownScalar("course")` du ZIP local utilise une comparaison stricte `< 360`, et peut donc supprimer un fragment source valide de 360°. Aligner parseur, préservation et writer sur le schéma exact de la version ; ne pas appliquer une normalisation modulo 360 à un champ source sans décision claire.

## 20.4 P1 — `LastKnownPosition` utile, mais persistance locale dangereuse

Le concept de dernière position est bon pour recentrer la carte et afficher un point ancien comme repère. L'implémentation du ZIP local n'est pas réutilisable telle quelle :

- `SharedPreferences.commit()` est synchrone ; l'appel dans `TrackingService` est décrit comme pouvant se produire environ toutes les 5 s depuis la voie de traitement GNSS, et peut bloquer la file/callback ;
- coordonnées `(0,0)` acceptées, contrairement à la règle utilisateur ;
- accuracy vérifiée finie et positive, mais pas bornée avec la limite applicative prévue ;
- altitude vérifiée contre l'infini mais pas forcément NaN/±10 000 ;
- appel avec `altitudeAvailable ? altitude : state.getAltitude()` peut associer la nouvelle position à l'altitude d'un fix antérieur ;
- certains `getFloat()` / `getLong()` de préférences ne sont pas tous protégés contre valeur corrompue ou type inattendu.

**Solution :** persister asynchroniquement sur une file I/O dédiée avec coalescence et révision monotone ; ancienne écriture ne peut jamais remplacer une nouvelle ; omettre altitude si non démontrée pour le même fix ; appliquer `(0,0)`, accuracy, bornes et parse défensif. C'est un cache UX, jamais un journal GPX.

## 20.5 P1 — ouverture GPX toujours synchrone

Dans ETAPE_15, `openRecordingSegment()` crée encore le `FileOutputStream`, le `BufferedWriter`, écrit l'en-tête et appelle `flush()` directement. La méthode est invoquée depuis le chemin de commande du service et le code y entre/sort autour de la synchronisation GNSS. Un `open()`/`flush()` peut bloquer sur stockage lent/plein/indisponible même sans `fsync()`.

**Solution :** introduire une barrière `OPEN_SEGMENT` dans la FIFO GPX. Les points sont mis en attente derrière cette barrière ; l'état de segment passe `OPENING` puis `OPEN`/`FAILED`. Une erreur d'ouverture doit être explicitement transmise et aucun point ne doit être compté comme écrit.

## 20.6 P1 — datum altitude pas défini

`Location.getAltitude()` Android représente une hauteur au-dessus de l'ellipsoïde WGS84. Sur API 34+, Android fournit `hasMslAltitude()` / `getMslAltitudeMeters()` pour une hauteur au niveau moyen de la mer. Le parseur NMEA sait extraire la hauteur GGA et la séparation géoïde, mais le chemin `TrackingService` vers GPX n'a pas d'association confirmée de cette séparation au même fix.

Ce point est une ambiguïté sémantique, pas la preuve que toutes les hauteurs produites sont mathématiquement fausses : le schéma GPX accepte un décimal mais ne définit pas le datum suffisamment pour résoudre ici la question produit. Décider ce que représente `<ele>` dans GPS-Test : MSL, ellipsoïde ou valeur explicitement qualifiée. Ne jamais combiner un GGA/geoid separation ancien à une nouvelle position. Si on ne peut garantir le datum retenu, omettre l'élévation ou qualifier la valeur dans une extension dédiée.

## 20.7 P2 — garde négative dans `Units.formatDistance()`

L'analyse du code local confirme que `formatDistance()` refuse les valeurs non finies mais n'exclut pas toutes les distances négatives, alors que l'inventaire promet un tiret pour les valeurs négatives. Ajouter `meters < 0` et un test dédié. Vérifier toutes les unités et la conversion avant arrondi.

## 20.8 P2 — contrat de sortie du runner `UnitsTest`

Dans le ZIP local, `UnitsTest` exécute ses 12 assertions et retourne le code 0, mais affiche `UnitsTest: 12 passed`. Le runner `run_jvm_tests.py` attend un motif de type `N réussis, 0 échoués`. Il déclare donc `résultat non reconnu`, ce qui donne un faux échec de suite malgré ses assertions réussies.

La validation de l'instantané local a temporairement normalisé la seule ligne de sortie pour démontrer le reste des tests : 39 suites, 1 328 assertions, 0 échec dans cette copie de test. Ce changement temporaire n'a pas été appliqué au ZIP fourni ni à ETAPE_15. Corriger le format de sortie du test ou améliorer formellement le parseur du runner, puis tester les deux chemins ; ne jamais modifier uniquement un artefact temporaire et en déduire que l'archive a changé.

## 20.9 P2 — politique `(0,0)` à la frontière d'écriture

Même si `FixQuality` et `GpxParser` rejettent `(0,0)`, le writer générique et le chemin de nettoyage reconstruisant des points doivent appliquer eux-mêmes cette invariance. Une frontière de sérialisation ne doit pas dépendre uniquement de filtres amont susceptibles d'être contournés par l'import, un traitement ou un appel futur.

## 20.10 P2 — documentation et compteurs contradictoires

Le README d'ETAPE_15 conserve des mentions historiques « 38 suites / 1 288 assertions » dans certains passages, tout en mentionnant « 39 / 1 345 » pour R1. `LOT_8C_R1_JOURNAL_DURABLE.md` indique 1 344, alors que `NOTE_ETAPE_15.md` et la relance du dossier donnent 1 345. Mettre à jour la documentation pour marquer les résultats historiques et les résultats R1, et conserver un seul résultat validé pour le snapshot courant.

---

# 21. Points de l'audit comparatif robustesse à garder en tête

Le rapport `GPS-Test_AUDIT_COMPARATIF_ROBUSTESSE_2026-11-24.md` compare ETAPE_14 à une version historique 3.6. Ses idées intéressantes doivent être adaptées à ETAPE_15 et non copiées en bloc.

## 21.1 Journal d'état vs récupération réelle de trace

- Une notification « enregistrement interrompu » n'assure pas que le GPX soit lisible.
- `onDestroy()` n'est pas garanti lors d'une mort brutale ; toute donnée nécessaire doit déjà être durable.
- Le journal R1 résout une partie de la persistance d'état, mais pas le dernier préfixe GPX sûr.
- Le dernier offset doit être un checkpoint confirmé par `flush + FileDescriptor.sync`, avec chemin/identité du segment.
- Une trace interrompue ne doit pas empêcher le démarrage d'une nouvelle trace, mais sa référence doit rester dans un registre multi-traces.

## 21.2 Ancien mécanisme `GpxCrashRecovery`

L'ancienne version contenait un début de mécanisme de récupération utile comme idée : parcourir un fichier partiel, repérer un point/segment XML valide et reconstruire un footer. Mais elle ne doit pas être recopiée telle quelle sans :

- validation de l'offset réellement synchronisé ;
- parsing streaming sécurisé et borné ;
- traitement explicite de partial tag, octets incomplets, encodage, namespaces et attributs ;
- vérification de l'intégrité des métadonnées/extensions conservées ;
- réparation sur un nouveau fichier, pas in-place ;
- validation XSD et publication atomique ;
- idempotence et scénario de crash pendant la réparation.

## 21.3 Manifeste de post-traitement durable

Une opération de nettoyage, réparation ou publication commencée puis interrompue doit survivre au processus. Un manifeste + WorkManager est pertinent ici, à condition qu'il y ait un identifiant par trace/travail, transitions transactionnelles et réconciliation au démarrage. WorkManager ne remplace pas le service GNSS.

## 21.4 Autres idées historiques à évaluer

- `LocationAcquisitionMonitor` : séparer les temps demandés/observés, l'âge du fix et l'absence de callback ;
- `LocationPowerSavePolicy` : envisager un mode énergie mesuré, pas une politique supposée ;
- `SessionExitInfoReader` : enrichir un diagnostic, mais ne pas prétendre connaître la cause réelle dans tous les cas ;
- documentation de la reprise après perte de signal ;
- machine à états explicite séparant intention utilisateur, service, acquisition, fix, enregistrement, fichiers et jobs différés.

## 21.5 Éléments historiques à ne pas reprendre

- anciens moteurs cartographiques osmdroid/Mapnik ou dépendances raster ;
- anciennes configurations SDK/manifest obsolètes ;
- `sendExtraCommand("delete_aiding_data")` ou reset AGPS intrusif sans justification et sans test OEM ;
- remplacement en bloc d'Activity/Service historiques ;
- promesse de disponibilité de mesures brutes en arrière-plan sans validation API/fournisseur.

---

# 22. Matrice de tests de robustesse cible

Cette section définit les scénarios à exécuter, pas des tests déjà passés. À compléter à mesure des lots.

## 22.1 Service et cycle de vie

| Scénario | Résultat attendu / preuve |
|---|---|
| Démarrer depuis Main visible après autorisation | promotion FGS rapide, notification/état cohérents, acquisition engagée |
| Permission fine retirée pendant suivi | arrêt/réabonnement sûr, session dirty conservée, aucune fausse position |
| Permission approximative seulement | message explicite/état approprié, pas de fix GNSS inventé |
| GPS/fournisseur désactivé puis réactivé | watchdog et abonnements reviennent une fois, pas de doublons |
| Perte de signal puis retour | changement d'état et nouveau `<trkseg>`, aucune ligne franchissant le trou |
| Écran éteint plusieurs heures | mesure de cadence et continuité sur les appareils testés, sans promettre 1 Hz théorique |
| Mode économie batterie / Doze / OEM | documenter comportement réel, pas hypothèse |
| Notification refusée API33+ | suivi et visibilité FGS conformes à Android, état UI honnête |
| Swipe away / Apps actives / force-stop | observer distinction arrêt tâche, arrêt service, arrêt forcé, interruption process |
| Kill process sans `onDestroy` | session dirty constatée à relance ; GPX récupéré uniquement après R2 |
| Activity recréée/rotation | UI relit TrackingState/session sans dupliquer abonnement |
| callbacks anciens après stop/restart | génération périmée ignorée |

## 22.2 GNSS et données

- fix 2D, fix 3D, aucun fix, valeurs null/NaN/Inf, précision insuffisante, point `(0,0)`, âge > seuil ;
- satellites connus/unknown, zéro valide, null dans liste, doublons multibande, constellation inconnue, fréquence/CN0 non finis ;
- NMEA checksum correct, incorrect, absent, phrase vide, champs manquants, ordre variable, trame ancienne, ZDA/GGA/RMC discordantes ;
- changement heure manuelle/fuseau/DST, timestamps fournisseur incohérents, monotonicité de l'âge et reconstruction UTC ;
- mesures brutes, AGC API33+, appel `hasMeasurements` API31+, arrière-plan et limitation par Android ;
- vérifier qu'un point 2D sans altitude reste écrit sans `<ele>` ; altitude rejetée ne supprime pas coordonnées/timestamp valides.

## 22.3 GPX et stockage

- GPX 1.0 et 1.1 générés, avec chaque combinaison d'éléments optionnels, validation XSD stricte ;
- namespaces par défaut/préfixés, metadata, extensions tierces, champs connus invalides, `course=360`, valeurs en exponentielle, entités interdites ;
- coordonnées limites ±90/±180, antiméridien, `(0,0)`, longitude +180 normalisée, élévation ±10 000 et ±10 001 ;
- aucune date inventée, horodatage UTC, absence de `time`, erreurs parse ISO-8601 ;
- écriture FIFO, queue pleine, open failure, write failure, flush, fd.sync, close, publication SAF ;
- stockage plein, volume retiré, disque lent, permission SAF révoquée, fichier existant, collisions de noms ;
- kill après chaque transaction, checkpoints et récupération ; conserver source originale ;
- import gros GPX, limites mémoire et temps, annulation worker, fermeture fichier, erreurs XML ;
- réexécuter nettoyage/réparation plusieurs fois pour confirmer idempotence.

## 22.4 Cartographie

- installation d'une carte Mapsforge valide, corrompue, tronquée, mauvaise version ;
- plusieurs régions adjacentes, recouvrement, leases, remplacement/suppression alors que carte ouverte ;
- download interrompu puis repris, espace insuffisant, checksum ;
- réseau coupé après téléchargement et application redémarrée ; aucune tuile réseau/raster ;
- position + trace sans fond de carte, carte manquante, multi-segments, grande trace, antiméridien ;
- thèmes, nord/heading, boussole, rotation et Edge-to-Edge.

## 22.5 Confidentialité et publication

- `allowBackup` et règles d'extraction testées sur backup/restore et transfert ;
- stockage privé, export explicite, suppression, FileProvider et URI grants ;
- vérifier que CSV/GPX/journal ne sont pas divulgués sans action ;
- AAB Release signé, applicationId stable, versionCode monotone ;
- vérification licences / attribution / Data safety / privacy / FGS Play Console.

---

# 23. Journal des résultats de validation — à tenir à jour

| Snapshot / contexte | Tests JVM | Audit Android statique | Audit Mapsforge | Build Android / Lint | Statut |
|---|---:|---|---|---|---|
| LOT 8B / ETAPE_13 historique | 38 / 1 288 | historiques : 17 XML / 80 Java | pas raster selon script | plusieurs runs CI antérieurs ; dernières erreurs API corrigées selon retour utilisateur | historique |
| ZIP local pré-R1 superposé sur ETAPE_14 | 39 / 1 328 après normalisation temporaire de sortie UnitsTest | 17 XML / 83 Java | 101 fichiers ; pas raster | non exécutés | audit de patch uniquement |
| ETAPE_15 / R1 relancé pour le présent dossier | **39 / 1 345** | **17 XML / 80 Java ; OK** | **98 fichiers ; aucun raster ; OK** | **non exécutés ici ; à confirmer en CI sur SHA R1** | dernier état local vérifié |

Ne jamais additionner ces résultats. La suite ETAPE_15 a été exécutée sous OpenJDK 21 dans le conteneur ; la CI configure Temurin 17. Aucun résultat Android ne doit être extrapolé de cette table.

---

# 24. Risques de confidentialité, conformité et publication à ne pas oublier

Audit du 24 novembre :

- le manifeste courant déclare `android:allowBackup="true"` sans règles d'extraction spécifiques observées dans cette passe ; les données de localisation, journaux de session et traces GPX nécessitent une décision explicite ;
- le `applicationId` est encore `com.example.gpstest` et `versionCode/versionName` restent 1 / 1.0 ; ces valeurs doivent être stabilisées avant première publication ;
- pas de preuve d'un AAB Release signé prêt à publier ; workflow en Debug ; pas de keystore release à inclure dans les archives ;
- politique de confidentialité, Data safety et déclaration FGS `location` doivent être préparées et vérifiées dans Play Console ; elles ne sont pas validées par un simple audit de manifeste ;
- attribution OpenStreetMap/Mapsforge et obligations licences/NOTICE doivent être présentes ;
- les échantillons d'avis positifs/négatifs du rapport d'audit ne sont pas statistiquement représentatifs ; les assertions de qualité terrain, consommation batterie, absence de traceurs et UX nécessitent mesures/tests indépendants.

Ces points sont à planifier avant distribution publique, mais ne doivent pas détourner les prochains lots de leur priorité : intégrité GPX et récupération après interruption.

---

# 25. Notes de précision pour les prochaines conversations

- **Archive vs dépôt :** une archive complète locale ne garantit pas l'état GitHub actuel ; toujours demander/obtenir le SHA réel de `main` si la modification dépend des derniers commits.
- **Build vs logique JVM :** un test Java avec stubs valide une logique isolée, pas les annotations, signatures, restrictions ou callbacks Android réels.
- **Flush vs durabilité :** `BufferedWriter.flush()` vide le buffer Java vers les couches inférieures ; `FileDescriptor.sync()` demande la synchronisation du fichier ; même `sync` ne permet pas une garantie absolue contre tous défauts matériels, mais il est la frontière à respecter pour un checkpoint applicatif.
- **Points écrits vs persistants :** `pointsAccepted`, `pointsQueued`, `pointsWritten`, `flushed` et `safeLengthBytes` sont des étapes différentes. Ne pas les fusionner en un compteur optimiste.
- **Dernière position vs trace :** une valeur mémorisée est une aide UI périmée, pas une donnée fiable de session ni un élément GPX.
- **Altitude :** toujours préciser le datum et la source ; ne pas associer hauteur et position si timestamps/fix ne correspondent pas.
- **Foreground service vs WorkManager :** FGS pour suivi GNSS initié par utilisateur ; WorkManager pour tâches différées persistantes.
- **GPX 1.0 vs 1.1 :** ne pas supposer que les mêmes éléments enfants, ordre ou extension sont valides ; faire valider le XML par le XSD correspondant.
- **Namespace extension :** le nom local identique ne suffit pas ; la reconnaissance est fondée sur l'URI exacte. Garder les extensions tierces inconnues.
- **(0,0) :** c'est un invariant propre à cette application, issu d'une instruction explicite, pas une règle de toutes les spécifications GPX.
- **`Locale.ROOT` :** le point décimal est voulu pour les nombres techniques même si l'interface est en français ; ne pas l'inverser en cherchant à localiser les dates et textes.
- **Android 24–36 :** toutes méthodes de plateforme modernes doivent être gardées selon leur API ; une compilation au SDK 37.2 ne prouve pas la compatibilité runtime minSdk 24.

---

# 26. Glossaire synthétique

| Terme | Signification dans GPS-Test |
|---|---|
| FGS | Foreground Service Android ; service au premier plan visible par notification/gestion système |
| GNSS | Ensemble des systèmes satellitaires de navigation (GPS, Galileo, GLONASS, BeiDou, etc.) |
| Fix 2D | Position horizontale exploitable sans altitude garantie |
| C/N0 | Rapport porteuse/bruit en dB-Hz, mesure de signal satellite reçue |
| DOP récepteur | DOP rapporté par le récepteur/NMEA |
| DOP géométrique | DOP calculé à partir de la géométrie de satellites retenus ; indicateur distinct |
| FIFO | File premier entré, premier sorti ; ordre de traitement des événements/écritures |
| `elapsedRealtime` | Horloge monotone depuis le boot, pour durées/âges |
| `currentTimeMillis` | Horloge civile Unix, pour étiquettes temporelles et nommage |
| GPX 1.0 / 1.1 | Versions de schéma GPX avec règles de structure différentes |
| XSD | XML Schema Definition ; définit la structure et le lexique autorisés |
| `trkseg` | Segment de trace GPX ; matérialise une discontinuité |
| SAF | Storage Access Framework, accès à un dossier/document sélectionné par l'utilisateur |
| `AtomicFile` | Mécanisme Android de remplacement transactionnel d'un fichier |
| Checkpoint | Offset d'un préfixe GPX réellement durable, pas simple compteur de points |
| Idempotence | Répéter une réparation/publication ne double pas ni ne corrompt le résultat |
| Mapsforge `.map` | Carte vectorielle locale lue par le moteur Mapsforge |
| WorkManager | Exécution différée persistante ; pas une horloge d'acquisition temps réel |
| OEM | Fabricant / couche système pouvant modifier le comportement énergétique et service |

---

# 27. État de clôture de ce dossier

**Dossier créé comme référence de reprise au 24 novembre 2026.** Il intègre l'ancien historique LOT 1 à LOT 8B, le LOT 8C-R1, les vérifications effectivement relancées sur ETAPE_15, l'audit technique du jour, l'inventaire des modifications locales, leurs améliorations, les défauts résiduels et une feuille de route avec critères de sortie.

**Aucun code de l'application n'a été modifié par la création de ce dossier.** Les seules commandes de validation exécutées ont relancé les scripts du snapshot ETAPE_15 ; `tools/gen_r.py` a regénéré les fichiers factices `R.java` à partir des ressources. Aucun build Android, Lint, instrumentation ou test sur appareil n'a été effectué dans cette session.

La prochaine action recommandée est de confirmer le commit exact de `main` et le statut CI de R1, puis d'organiser la fusion sélective des améliorations locales dans une branche dérivée de la base confirmée. Le chantier de fiabilité prioritaire reste la chaîne GPX : conformité XSD, ouverture non bloquante, checkpoint réellement synchronisé, registre multi-sessions et récupération idempotente.

**Fin du dossier de reprise ultra exhaustif — état consolidé au 24 novembre 2026.**

---

# 28. Addendum — premier correctif sélectif LOT 8C-R2A.1 (24 novembre 2026)

Cette section prévaut sur la phrase de clôture de la section 27 quant au prochain travail local réalisé. La section 27 décrit l'état lors de la création initiale du dossier ; cet addendum décrit les actions accomplies ensuite.

## 28.1 Base et provenance

- Base de code utilisée : archive `ETAPE_15.zip`, SHA-256 `9d476a3db724ec5a2cf5018d6d071137a00f6e8816796f8654ce8b889e3c301d`.
- Copie de travail indépendante : `/mnt/data/candidate_8c_r2a/GPS-Test` dans l'environnement de préparation.
- L'archive originale ETAPE_15 et le ZIP des modifications locales pré-R1 n'ont pas été modifiés.
- Le SHA du `main` distant et les exécutions GitHub Actions n'ont pas pu être vérifiés (échec d'accès/résolution réseau à GitHub dans cette session). La copie candidate ne doit donc pas être présentée comme le HEAD actuel du dépôt.

## 28.2 Changement réalisé

Sous-lot : **LOT 8C-R2A.1 — ordre XSD GPX 1.0**.

Dans `GpxWriter.pointXml()` :

- GPX 1.0 écrit maintenant `<ele>`, `<time>`, `<course>`, `<speed>`, puis `<hdop>` pour les champs effectivement pris en charge ;
- GPX 1.1 conserve `<ele>`, `<time>`, `<hdop>`, puis `<extensions>` lorsqu'il existe des données vitesse/cap/accuracy ;
- aucune modification du namespace d'extension ou de la structure de journal R1.

Dans `tests/AdvancedTest.java`, deux assertions couvrent l'ordre GPX 1.0 (`course < speed < hdop`) et la conservation de l'ordre GPX 1.1 (`hdop` avant `extensions`).

Seuls ces deux fichiers source/test ont été changés dans la copie candidate. Une note de lot se trouve dans `docs/LOT_8C_R2A1_GPX10_ORDRE_XSD_2026-11-24.md`.

## 28.3 Résultats relancés

| Contrôle | Base ETAPE_15 | Copie candidate R2A.1 |
|---|---:|---:|
| Suites JVM | 39 | 39 |
| Assertions JVM | 1 345 | 1 347 |
| Échecs | 0 | 0 |
| Audit Android statique | 17 XML / 80 Java ; OK | 17 XML / 80 Java ; OK |
| Audit cartographique | 98 fichiers ; pas de raster ; OK | 98 fichiers ; pas de raster ; OK |

L'ajout de deux assertions correspond aux deux nouvelles vérifications d'ordre. Le `dry-run` de l'application du patch sur une copie fraîche d'ETAPE_15 est réussi.

Toujours non réalisés : validation avec XSD officiel complet, `assembleDebug`, Android Lint, instrumentation et test terrain. Les assertions de chaîne valident l'ordre attendu mais ne se substituent pas à une validation XSD complète.

## 28.4 Livrables du sous-lot

- Correctif texte unifié : `GPS-Test-LOT-8C-R2A1-GPX10-ORDRE-XSD.patch` (SHA-256 `22d94be3ff1cbc96cc7564d8494c561becee447f59ec3e31289fafc283ddcf05`).
- Archive des deux fichiers modifiés et de leur note : `GPS-Test-LOT-8C-R2A1-GPX10-ORDRE-XSD-PATCH.zip` (SHA-256 `b955542bee982e44950dc4ab8709f99d55957c4f3f3b255e1ae4ca4f403d336b`).
- Note technique autonome : `GPS-Test-LOT-8C-R2A1-GPX10-ORDRE-XSD.md`.

Ne pas remplacer les fichiers entiers sur `main` sans comparer d'abord les versions distantes. Le diff doit être appliqué sélectivement si la branche a évolué.

## 28.5 Prochaine correction prioritaire

Poursuivre le volet conformité GPX : validation lexicale stricte des champs `xsd:decimal` dans `GpxPreservation.validKnownScalar()`, empêcher les valeurs `NaN` / `Infinity` et les notations exponentielles non conformes, puis corriger les conversions de secours `Double.toString()` susceptibles d'émettre `1.0E-10`. Ajouter des tests qui vérifient à la fois l'acceptation/rejet lexical et le résultat de re-sérialisation. Préserver intégralement les extensions tierces inconnues.

Avant toute intégration au dépôt, confirmer autant que possible le commit exact de `main` et la CI R1 ; à défaut, conserver explicitement le statut de patch candidat fondé sur ETAPE_15.



---

# 29. Addendum — LOT 8C-R2A.2 : scalaires GPX, lexique décimal et barrières writer (24 novembre 2026)

Cette section prévaut sur les consignes de prochain travail formulées à la section 28.5. R2A.2 a été réalisé dans une copie dérivée de la candidate R2A.1, elle-même fondée sur ETAPE_15. Aucun changement n'a été poussé sur GitHub.

## 29.1 Mode de collaboration retenu

L'utilisateur a précisé qu'aucun fichier n'a été modifié sur GitHub et que l'assistant doit effectuer les modifications puis livrer directement les fichiers. L'utilisateur se charge ensuite de reporter les fichiers demandés dans son dépôt. En conséquence :

- aucune commande de publication/push GitHub n'est exécutée ;
- les livraisons doivent privilégier les fichiers sources réels et une archive complète de projet ;
- ne pas demander à l'utilisateur d'exécuter `git apply` ;
- toujours préciser l'arborescence exacte des fichiers à remplacer ;
- avant remplacement manuel, comparer le dépôt de l'utilisateur avec la base locale ETAPE_15 : l'accès au HEAD distant est toujours indisponible dans cette session.

## 29.2 Base et provenance

- Base confirmée dans l'environnement : `ETAPE_15.zip`, SHA-256 `9d476a3db724ec5a2cf5018d6d071137a00f6e8816796f8654ce8b889e3c301d`.
- Candidate de livraison : `/mnt/data/candidate_8c_r2b/GPS-Test`.
- R2A.1 (ordre des éléments GPX 1.0) est conservé.
- Les changements locaux pré-R1 n'ont pas été superposés en bloc.
- `MainActivity.java`, `TrackingService.java`, `SessionJournal.java` et `SessionStore.java` n'ont pas été remplacés.
- ZIP ETAPE_15 original et GitHub restent intacts.

## 29.3 Détail des modifications R2A.2

### `GpxParser.java`

- Validation lexicale de `xsd:decimal` avec un motif précompilé : signe éventuel, chiffres et point décimal ; notation scientifique, `NaN`, `INF` et `Infinity` sont refusés.
- Application aux coordonnées et aux champs numériques interprétés.
- `speed` et `course` directs ne sont interprétés qu'en GPX 1.0.
- GPX 1.1 lit ces mesures dans le namespace GPS-Test exact, pas dans les enfants directs du namespace GPX.
- Compatibilité GPX 1.0 historique sans namespace lorsque `version="1.0"` est explicitement déclaré.

### `GpxPreservation.java`

- Validation de champs standards connus selon GPX 1.0 ou 1.1.
- Nombres décimaux contrôlés avec `BigDecimal`, entiers avec `BigInteger`.
- `course` GPX 1.0 et extension course GPS-Test 1.1 : plage 0–360 inclusive ; `magvar` GPX 1.1 : 0 inclus, 360 exclu ; GPX 1.0 accepte les variations magnétiques signées.
- `dgpsid` borné à 0–1023 ; `sat` entier non négatif ; `fix` limité aux valeurs énumérées selon la version.
- Les valeurs non négatives des DOP, de l'âge DGPS, de la vitesse et de la précision constituent un contrôle sémantique métier en plus du type lexical décimal.
- Les champs directs `speed/course` de GPX 1.1 sont supprimés du fragment préservé.
- Les extensions GPS-Test sont reconnues uniquement par l'URI exacte `https://example.org/gpstest/ext`.
- Toutes les extensions tierces inconnues restent préservées, y compris si leur contenu ressemble à une notation invalide.
- Les documents sans namespace portant `version="1.0"` ou `version="1.1"` reçoivent aussi le traitement de la version correspondante, selon les règles existantes de nettoyage.
- Les coordonnées préservées exigent aussi le lexique décimal strict, les bornes géographiques et la règle anti-(0,0).

### `GpxWriter.java`

- R2A.1 : ordre GPX 1.0 `ele`, `time`, `course`, `speed`, `hdop` ; GPX 1.1 garde `hdop` avant le bloc `extensions`.
- Repli des coordonnées/altitudes par `BigDecimal.toPlainString()` pour ne pas écrire une notation scientifique.
- Contrôle final des coordonnées avant sérialisation : valeurs finies, latitude [-90,90], longitude [-180,180], (0,0) rejeté.
- Altitude écrite uniquement si finie et dans [-10 000 m, +10 000 m].
- Vitesse, HDOP et précision ne sont écrits que si finies et non négatives ; cap uniquement s'il est fini et dans [0,360].
- Un point ou une version absente est refusé explicitement ; une coordonnée invalide provoque une erreur d'écriture et ne devient jamais une valeur GPX inventée.

## 29.4 Fichiers source/tests modifiés par rapport à ETAPE_15

1. `app/src/main/java/com/example/gpstest/GpxWriter.java`
2. `app/src/main/java/com/example/gpstest/GpxPreservation.java`
3. `app/src/main/java/com/example/gpstest/GpxParser.java`
4. `tests/AdvancedTest.java`
5. `tests/GpxPreservationTest.java`
6. `tests/SamplingAndParsingTest.java`

Documents ajoutés à la livraison :

- `docs/LOT_8C_R2A1_GPX10_ORDRE_XSD_2026-11-24.md`
- `docs/LOT_8C_R2A2_GPX_SCALAIRES_2026-11-24.md`
- `LISEZ_MOI_LOT_8C_R2A2.md`

Aucun autre fichier applicatif n'est modifié par rapport à ETAPE_15.

## 29.5 Tests et vérifications de la candidate finale

| Contrôle | Résultat final R2A.2 |
|---|---:|
| Suites JVM | **39** |
| Assertions JVM | **1 368 réussies** |
| Échecs JVM | **0** |
| Vérification statique Android | **OK** — 17 XML, 80 sources Java, ressources, manifeste, permissions, FGS, SDK, Edge-to-Edge |
| Vérification architecture cartes | **OK** — 98 fichiers, aucun moteur/provider raster, sélecteur et repli locaux présents |
| Vérification environnement build | Gradle absent, Wrapper non versionné |
| `assembleDebug` | Non exécuté |
| Android Lint | Non exécuté |
| Tests instrumentés / appareil / OEM | Non exécutés |
| Validation par XSD officiel complet | À réaliser en sous-lot dédié |

Les 1 368 assertions incluent toutes les suites de ETAPE_15, les régressions R2A.1 et les nouveaux cas R2A.2. Ne pas additionner les nombres de tests de sections anciennes : seul le résultat final du candidat est à retenir.

## 29.6 Livrables et mode d'emploi

La livraison est fournie sous forme d'une archive complète de projet (base ETAPE_15 + correctifs locaux R2A.1/R2A.2), ainsi que des fichiers modifiés séparément. Il n'y a pas besoin d'appliquer un patch Git.

Pour reporter au dépôt de l'utilisateur : remplacer les six fichiers de la section 29.4, en conservant les chemins, après confirmation que le dépôt local est compatible avec ETAPE_15. Les deux notes de lot et le `LISEZ_MOI` sont documentaires. Ne pas copier aveuglément un projet complet sur une branche qui aurait déjà évolué.

## 29.7 Limites et prochaines étapes

1. **Validation XSD complète** : construire un banc offline utilisant les schémas officiels GPX 1.0 et 1.1, valider les sorties et des documents d'exemple (éléments optionnels, extensions, ordre, namespaces). Ce point est le prochain sous-lot avant les travaux I/O plus lourds.
2. **Namespace GPS-Test** : l'URI `https://example.org/gpstest/ext` reste provisoire/hérité de la base ; choisir un URI permanent contrôlé par le projet avant publication et maintenir la lecture des anciens fichiers.
3. **R2B — ouverture GPX non bloquante** : déplacer création de fichier, en-tête et flush initial derrière une barrière FIFO du writer, sans bloquer le thread de service sous `gnssProcessingLock`.
4. **R2C — récupération durable** : écrire un vrai `safeLengthBytes` uniquement après file FIFO vidée, `flush()` puis `FileDescriptor.sync()` réussis ; rendre la réparation idempotente et distinguer les sessions interrompues via un registre UUID multi-sessions.
5. **R3 — travaux différés** : WorkManager pour nettoyage/récupération différée/publishing et installations de cartes ; ne pas l'utiliser comme cadence de collecte GNSS.
6. **Validation Android terrain** : remettre en place la distribution contrôlée du Gradle Wrapper/bootstrap en CI, exécuter build/Lint/instrumentation, puis tests sur appareils/OEM représentatifs.

**Statut de clôture R2A.2 :** candidate locale testée en JVM et vérifiée statiquement, remise directement à l'utilisateur. Non poussée, non fusionnée, non compilée Android dans cette session.


---

# ADDENDUM DE REPRISE — LOT 8C-R2A.3 (24 novembre 2026)

## État après R2A.3

L'utilisateur a confirmé que la compilation Android de l'archive R2A.2 s'est terminée sans erreur. Cette confirmation porte sur R2A.2, pas sur les outils de validation ajoutés après cette compilation.

Le sous-lot R2A.3 ajoute un banc de validation du XML complet produit par `GpxWriter` contre les schémas XSD GPX 1.0 et 1.1. Les fichiers suivants sont ajoutés au projet :

- `tools/download_gpx_schemas.py` : récupère les schémas canoniques Topografix et vérifie leurs namespaces et versions ;
- `tools/GpxXsdValidation.java` : construit des documents à partir de `GpxWriter.build()` puis valide avec JAXP ;
- `tools/validate_gpx_xsd.py` : compile et exécute le validateur ;
- `docs/LOT_8C_R2A3_BANC_XSD_2026-11-24.md` : documentation du lot.

Le workflow `.github/workflows/android-build.yml` ajoute un job indépendant `gpx-xsd`, qui télécharge les deux XSD puis exécute les validations séparément du job Android. `.gitignore` ignore `tools/xsd/`, qui contient les schémas téléchargés temporairement. `LISEZ_MOI_LOT_8C_R2A2.md` reçoit un addendum d'utilisation.



### Résultats de vérification associés à R2A.3

Sur la copie R2A.2 utilisée comme base : 39 suites JVM / 1 368 assertions / 0 échec ; audit Android statique OK (17 XML, 80 sources Java) ; audit cartographique local OK (98 fichiers). `tools/GpxXsdValidation.java` compile avec JDK 17 et les scripts Python passent `py_compile`. Le workflow YAML a été analysé et contient bien deux jobs, `android` et `gpx-xsd`.

Le harnais Java a ensuite été exécuté avec succès sur deux XSD temporaires simplifiés correspondant uniquement à la structure produite par le writer : 4 contrôles positifs/négatifs sur 4. Cette étape démontre le fonctionnement du mécanisme de test, mais les schémas temporaires ne sont pas les schémas GPX officiels. Le véritable téléchargement Topografix échoue dans l'environnement présent avec `Temporary failure in name resolution` ; la conformité XSD officielle n'est donc pas validée ici.

## Limite de validation à ne pas perdre

Dans l'environnement de préparation, les scripts Python sont syntaxiquement valides et `tools/GpxXsdValidation.java` compile avec `javac`. En revanche, l'accès réseau sortant DNS est bloqué ; les XSD Topografix n'ont pas pu être téléchargés et les 4 contrôles XSD (2 documents valides, 2 documents invalides) n'ont donc PAS été exécutés contre les schémas officiels. Le programme de validation a été smoke-testé contre deux XSD temporaires simplifiés reproduisant seulement la partie du schéma appelée par `GpxWriter` : les 4 cas de validation / rejet ont passé. Ce smoke-test confirme le fonctionnement du harnais et de ses cas positifs/négatifs, mais n'est PAS une validation contre les XSD officiels. Le banc doit obtenir son premier résultat XSD réel lors d'une exécution locale disposant d'un accès HTTPS, ou dans le job séparé GitHub Actions. Ne jamais présenter la compilation du validateur ou le smoke-test simplifié comme une validation GPX officielle réussie.

Commandes à exécuter à la racine du projet :

```bash
python3 tools/download_gpx_schemas.py
python3 tools/validate_gpx_xsd.py
```

## Base et gouvernance des fichiers

- Archive R2A.2 utilisée comme base de préparation : `GPS-Test-LOT-8C-R2A2-PROJET-COMPLET-2026-11-24.zip`.
- SHA-256 de cette archive : `4b854676f813d69369594e33de374f23ceabfaf4eef0ab0935704511191c68eb`.
- Aucun changement n'a été fait directement dans le dépôt GitHub. Les changements du R2A.3 sont des fichiers remis à l'utilisateur pour remplacement manuel.
- Aucun fichier applicatif sous `app/src/main` n'est modifié dans R2A.3 ; ce sous-lot touche aux outils de validation, au workflow CI, au gitignore et à la documentation.

## Suite de travail

1. Après remplacement des fichiers R2A.3 dans le dépôt et lancement des Actions, lire le journal de l'étape XSD. Exiger 4 validations attendues : GPX 1.0 valide, GPX 1.1 valide, GPX 1.0 invalide rejeté, GPX 1.1 invalide rejeté.
2. Si le schéma officiel rejette la sortie positive, lire le message du validateur et corriger `GpxWriter` uniquement sur la base de la contrainte exacte remontée. Ne pas modifier en bloc le service GNSS ou le journal R1.
3. Une fois le banc vert, poursuivre avec le défaut suivant de la feuille de route, de préférence `Units.formatDistance` (garde négative absente dans les modifications locales précédemment examinées) et un format d'exécution de tests normalisé, puis le chantier asynchrone d'ouverture GPX et de checkpoint durable dans les lots ultérieurs.
4. Conserver les règles projet : pas de position `(0,0)`, pas de valeur inventée, pas d'écriture d'un point sans données valides, pas de remplacement wholesale de `MainActivity.java` ou `TrackingService.java` par le ZIP local pré-R1.

---

# ADDENDUM DE REPRISE — LOT 8C-R2A.4 ET PRÉPARATION R2B (24 novembre 2026)

## 30. État exact après R2A.4

### 30.1 Retours utilisateur

- L'utilisateur a explicitement confirmé : « Compilation ok sans erreur! » après la livraison R2A.2.
- Après R2A.3, il a confirmé : « Aucune erreur! ». Cela confirme son cycle de vérification de la livraison, mais aucun journal de GitHub Actions n'a été fourni ; ne pas en déduire sans preuve que le job réseau `gpx-xsd` a effectivement validé les deux documents avec les vrais XSD Topografix.
- L'utilisateur a établi une règle de gouvernance : **aucun fichier ne doit être modifié directement sur GitHub par l'assistant**. L'assistant prépare les changements dans une copie de travail et livre les fichiers ou un projet complet. L'utilisateur reporte les fichiers dans son dépôt. Aucun push, commit, pull request ou modification distante ne doit être effectué par l'assistant.
- L'utilisateur ne souhaite pas manipuler les patchs Git ; lui remettre les fichiers modifiés directement, avec les chemins, ou une archive ZIP claire.

### 30.2 Base de code de préparation R2A.3

Archive utilisée : `GPS-Test-LOT-8C-R2A3-BANC-XSD-PROJET-COMPLET-2026-11-24.zip`.

Le projet a été extrait sous `/mnt/data/r2a3_inspect/GPS-Test` pour préparer R2A.4. L'archive R2A.3 sert de base logicielle complète présente dans cette conversation. Elle est dérivée d'ETAPE_15 + R2A.1 + R2A.2 + R2A.3 ; le SHA du dépôt distant `main` n'a pas été vérifié.

### 30.3 LOT 8C-R2A.4 — correctif de `Units.formatDistance`

Défaut : la méthode acceptait les nombres négatifs et les convertissait, affichant des valeurs physiquement incohérentes comme `-12 m`, `-1,0 mi` ou `-1,0 NM`. Elle traitait `NaN`, mais pas les infinis.

Correction dans `app/src/main/java/com/example/gpstest/Units.java` :

```java
if (!Double.isFinite(meters) || meters < 0.0) return "—";
if (meters == 0.0) meters = 0.0;
```

Le deuxième test normalise le zéro négatif IEEE-754 (`-0.0`) pour l'afficher comme `0 m`. Les conversions et précisions des distances positives restent inchangées.

Tests ajoutés dans `tests/ModesAndPostProcessTest.java` :

1. distance métrique négative rejetée ;
2. distance impériale négative rejetée ;
3. distance nautique négative rejetée ;
4. `+Infinity` rejeté ;
5. `-Infinity` rejeté ;
6. `-0.0` normalisé en `0 m`.

Deux fichiers seulement contiennent le correctif de code :

- `app/src/main/java/com/example/gpstest/Units.java`
- `tests/ModesAndPostProcessTest.java`

Documentation du lot :

- `docs/LOT_8C_R2A4_UNITES_DISTANCE_2026-11-24.md`
- `LISEZ_MOI_LOT_8C_R2A4.md`

### 30.4 Résultats mesurés après R2A.4

Le script de test a été relancé depuis la racine de la copie R2A.3 modifiée :

```bash
python3 tools/run_jvm_tests.py
```

Résultat observé :

- **39 suites JVM** ;
- **1 374 assertions réussies** ;
- **0 échec**.

Audits statiques :

```bash
python3 tools/verify_android_project.py
python3 tools/verify_offline_map_architecture.py
```

- Android statique : OK, 17 XML, 80 sources Java ; ressources R, manifeste, permissions, FGS, SDK et Edge-to-Edge cohérents.
- Cartographie : OK, 98 fichiers Android, aucun moteur/provider de tuiles raster ; repli et sélecteur locaux présents.

Compilation Android dans la session de préparation : non exécutée. `bash tools/check_android_build_environment.sh` signale explicitement que Gradle est absent et que le Gradle Wrapper n'est pas versionné. L'utilisateur a toutefois confirmé la compilation sans erreur de sa livraison précédente. Ne pas transformer cette confirmation d'une livraison antérieure en preuve d'une nouvelle compilation R2A.4.

### 30.5 Livraison R2A.4 prévue

- Archive complète : `GPS-Test-LOT-8C-R2A4-PROJET-COMPLET-2026-11-24.zip`.
- Archive fichiers à remplacer : `GPS-Test-LOT-8C-R2A4-FICHIERS-A-REMPLACER-2026-11-24.zip`.
- Seuls les deux fichiers applicatif/test ci-dessus doivent être remplacés dans l'arborescence du dépôt ; les notes sont documentaires.
- Aucune action GitHub n'a été effectuée.

Pour intégrer : décompresser l'archive « fichiers à remplacer » **à la racine locale du projet GPS-Test**, conserver les chemins, accepter le remplacement de `Units.java` et `ModesAndPostProcessTest.java`. Pas de patch Git.

## 31. Préparation du chantier majeur — R2B : ouverture GPX asynchrone

### 31.1 État observé dans R2A.3 / R2A.4

Dans `app/src/main/java/com/example/gpstest/TrackingService.java` :

- Les points sont déjà postés sur le Handler `ioFile`, thread FIFO dédié.
- Le modèle `RecordingSegment` capture l'identité du segment, son fichier, sa version GPX, son `FileOutputStream`, son `BufferedWriter`, les compteurs et l'état d'erreur/fermeture.
- `appendPoint()` poste sur `ioFile`; la file est bornée par `MAX_GPX_QUEUE_DEPTH = 120`.
- Le worker effectue `flush()` toutes les 2 secondes lorsque des points arrivent, un jalon idle périodique existe, et tente `FileDescriptor.sync()` toutes les 30 secondes. La fermeture réalise pied, flush, sync final et close sur le worker.
- `flushNow()` poste aussi son action dans la même FIFO.
- Cependant, `openRecordingSegment(int counterEpoch)` reste synchrone : `new FileOutputStream`, création du `BufferedWriter`, écriture de `GpxWriter.header(...)` et `flush()` sont effectués par l'appelant.
- `startRecording()` appelle `openRecordingSegment()` alors qu'il détient `gnssProcessingLock`. Il met `recording=true`, ouvre synchroniquement le fichier, puis remet le contrôle aux callbacks GNSS.
- `resumeTracking()` appelle aussi `openRecordingSegment()` quand l'enregistrement est actif et que `gpxStream == null`, sous le même verrou.
- Le commentaire du service dit explicitement que l'ouverture peut bloquer le thread principal si le stockage est lent. Ce risque est donc connu et toujours présent.

### 31.2 But exact de R2B.1

Déplacer uniquement les E/S d'ouverture (répertoire/fichier, `FileOutputStream`, `BufferedWriter`, entête GPX, flush initial) vers la FIFO `ioFile`. L'objectif n'est pas une réécriture du service : il faut préserver les mécanismes existants des points, compteurs, sessions, erreurs, segments et fermeture.

Aucun fix GNSS ne doit attendre l'ouverture sur le thread principal ni tenir `gnssProcessingLock` pendant une opération disque. Une ouverture en attente doit cependant conserver l'ordre : l'entête est créée avant tout point du segment, et le point ne peut être écrit avant la fin de l'ouverture.

### 31.3 État de segment à prévoir

Concevoir un état explicite plutôt que de se fonder uniquement sur `gpxStream != null` :

- `CLOSED` / aucun segment ;
- `OPENING` / ouverture inscrite dans la FIFO ;
- `OPEN` / writer disponible ;
- `CLOSING` / fermeture inscrite après toutes les écritures précédentes ;
- `FAILED` / ouverture ou écriture irrécupérable.

Le segment doit exister logiquement (identifiant unique) dès la réservation, même si le FileOutputStream n'existe pas encore. Éviter de mettre un writer `null` en place avec du code qui le considère déjà prêt.

### 31.4 Barrière FIFO d'ouverture à implémenter

La séquence cible :

1. Sous `gnssProcessingLock`, réserver un `RecordingSegment` logique avec UUID/ID, version, epoch, fichier destiné, timestamps, destination SAF et état `OPENING`.
2. Publier l'identité de ce segment comme segment actif ; aucun appel d'I/O ne se fait sous le verrou.
3. Poster l'ouverture dans `ioFile` : création sortie, BufferedWriter, en-tête complet, `flush()` initial. Ce Runnable est la barrière d'ouverture.
4. Les points qui arrivent pendant l'ouverture sont capturés avec le segment exact et mis en file derrière la barrière, ou gardés dans une file tampon explicitement bornée. Dans les deux cas ils ne dépassent pas la limite mémoire et sont écrits strictement après l'entête.
5. Une tâche d'ouverture réussie fait passer le segment à `OPEN`, puis traite/autorise les points suivants. Une tâche d'ouverture en échec doit passer à `FAILED`, fermer proprement toute ressource partielle, incrémenter les compteurs d'échecs appropriés pour les points en attente, remonter l'erreur et faire en sorte qu'aucun segment vide/incohérent ne soit annoncé comme prêt.
6. Tous les messages UI doivent repasser sur `ui`; ne pas modifier depuis le worker des propriétés partagées de la vue sans protocole sûr.

Il faut décider explicitement si les points sont postés directement derrière l'ouverture dans le Handler FIFO, ou maintenus dans une file par segment. Ne pas créer un second ordre concurrent. La file globale `ioFile` reste l'autorité d'ordre physique.

### 31.5 Courses et cas limites obligatoires

Les tests et la revue doivent couvrir :

- l'utilisateur arrête l'enregistrement juste après START, avant que l'ouverture ne se termine ;
- STOP est posté avant que le worker ait ouvert le fichier : le close/foot doit s'exécuter derrière l'ouverture ou l'échec doit être géré ;
- start suivi de stop puis start rapide : un ancien callback d'ouverture ne doit jamais écraser le segment neuf ;
- pause/suspension et reprise durant `OPENING` ;
- permissions/storage indisponibles, fichier non créable, disque plein, `write()` ou `flush()` qui échoue ;
- `ioFile == null` ou HandlerThread déjà en arrêt ;
- queue saturée pendant que l'ouverture est lente ;
- identité de session/segment et epochs qui changent durant le worker ;
- compteurs accepted / queued / written / failedBeforeQueue / failedAfterQueue cohérents ;
- `state.isRecording()`, `gpxFile`, `gpxStream`, `activeSegment`, notification, UI, `persistSession()` cohérents avec un état OPENING ou FAILED.

### 31.6 Règles de synchronisation

- Ne jamais attendre `CountDownLatch`, `Future.get()` ou blocage synchrone sur le thread principal pour attendre l'ouverture.
- Ne pas faire de disque sous `gnssProcessingLock`.
- Ne pas fermer un writer depuis un autre thread en concurrence avec la FIFO.
- Toute fermeture doit être ordonnée après les points déjà acceptés pour ce segment.
- `flush()` et `FileDescriptor.sync()` doivent rester exécutés dans la FIFO, après les données à garantir.
- Ne pas déclarer `safeLengthBytes` réel tant que le préfixe correspondant n'a pas subi une séquence flush + sync réussie.
- L'ouverture réussie ne garantit pas la durabilité de l'entête tant que le flush/sync attendu n'est pas achevé ; consigner ce qui est réellement garanti.

### 31.7 Tests suggérés pour la logique pure

Extraire si nécessaire une machine d'état/coordinateur pur testable hors Android, plutôt que d'essayer d'instancier `TrackingService` dans les tests JVM. Elle doit être capable de modéliser les événements : reserve, openSucceeded, openFailed, pointAccepted, pointQueued, pointWritten, stopRequested, closeSucceeded, closeFailed, newSession.

Vérifier les invariants :

- jamais `pointWritten` avant `openSucceeded` ;
- aucun point d'un ancien segment compté dans le segment courant ;
- aucune écriture après état fermé ;
- toutes les acceptations ont une issue : written ou une catégorie failed explicite ;
- le compteur queued ne dépasse pas les points effectivement postés ;
- un échec d'ouverture ne laisse pas `recording=true` indéfiniment avec un segment prétendument opérationnel ;
- aucune fermeture ne passe devant des points déjà postés.

### 31.8 R2B.2 et R2C ne doivent pas être confondus

R2B.1 traite la latence et l'ordonnancement de l'ouverture. R2B.2 doit ensuite définir un checkpoint de préfixe sûr ; R2C traitera la réparation/récupération durable après crash.

R1 écrit déjà `SessionJournal.safeLengthBytes`, mais `TrackingService.persistSession()` fournit encore `0L`. Il ne suffit pas de reprendre `pointsWritten`, car `BufferedWriter` peut avoir écrit dans sa mémoire sans remettre les caractères au FD et `flush()` peut remettre les octets sans preuve de `sync()` réussi.

Le checkpoint devra être fondé sur la longueur exacte du fichier après :

1. drain de la FIFO du segment jusqu'à un marqueur-barrière ;
2. flush du writer ;
3. `FileDescriptor.sync()` réussi ;
4. capture de la longueur exacte du préfixe ;
5. écriture atomique du journal correspondant à la même identité UUID/révision du segment.

La récupération doit vérifier les longueurs, tronquer uniquement vers le dernier préfixe confirmé, valider/corriger la fermeture GPX de façon idempotente et ne jamais inventer un point. Prévoir un registre de sessions interrompues indexées par UUID si plusieurs récupérations peuvent rester en attente ; une seule ligne active écrasable ne suffit pas à représenter plusieurs sessions non traitées.

## 32. Règles de livraison à respecter désormais

1. Ne jamais pousser directement sur GitHub ni modifier les fichiers distants.
2. Travailler dans une copie de projet identifiée par le nom de l'archive de base et son SHA-256.
3. Remettre en priorité un ZIP des fichiers à remplacer, avec les chemins exacts ; fournir aussi un ZIP complet quand utile.
4. Ne pas demander à l'utilisateur d'appliquer un patch Git. Les `.patch` peuvent être omis.
5. Chaque lot doit fournir : note technique, instructions de remplacement, résultats de tests exécutés, limites de validation, SHA-256.
6. Ne pas affirmer la compilation Android si seul le runner JVM a été exécuté.
7. Les résultats de GitHub Actions / XSD officiels ne sont confirmés que par un journal ou retour précis correspondant au lot concerné.
8. Ne pas intégrer wholesale le ZIP des modifications locales pré-R1 : `MainActivity` et `TrackingService` locales étaient basées sur un état antérieur et peuvent faire régresser le journal durable.

**Point de reprise conseillé :** commencer R2B.1 par une analyse complète de tous les appels à `openRecordingSegment`, `startRecording`, `resumeTracking`, `appendPoint`, `closeRecordingSegment`, `closeSegmentFallback`, `shutdownIoThreadsAfterDrain` et `persistSession`. Concevoir les états/barrières et tests de courses avant de modifier le service. Le petit correctif Units R2A.4 est limité, testable et indépendant du chantier d'écriture asynchrone.


---

# MISE À JOUR MAJ5 — LOT 8C-R2B.1 RÉALISÉ

**État daté du 24 novembre 2026 — après la livraison R2A.4 et la confirmation utilisateur de compilation sans erreur de R2A.4.**

Cette mise à jour fait foi pour l'état courant du chantier. Les recommandations des sections précédentes décrivant R2B.1 comme « à implémenter » ou « point de reprise conseillé » sont historiques : elles ont été réalisées dans une copie de travail issue du projet complet R2A.4, et sont remplacées par l'état et les livrables ci-dessous.

## 33. LOT 8C-R2B.1 — Ouverture GPX asynchrone

### 33.1 Provenance et limites de l'état

Base de travail : archive complète `GPS-Test-LOT-8C-R2A4-PROJET-COMPLET-2026-11-24.zip`, extraite dans une copie indépendante. Comparaison des fichiers avec une extraction propre de cette même archive pour déterminer le périmètre modifié. Aucune opération n'a été faite sur le dépôt GitHub, aucun commit ni push n'a été effectué.

L'utilisateur a confirmé « Compilation ok sans erreur » pour R2A.4. Cette confirmation ne prouve pas la compilation du nouveau R2B.1. Dans l'environnement de préparation, Gradle et le Wrapper ne sont pas disponibles/présents et aucun Android SDK utilisable n'a été trouvé ; la compilation Android réelle et Android Lint du lot R2B.1 n'ont donc pas été exécutés. L'utilisateur doit compiler le projet livré avant de valider le lot.

### 33.2 Défaut de départ

Avant R2B.1, les points étaient sérialisés dans `ioFile`, mais `openRecordingSegment()` ouvrait encore le fichier sur le thread appelant : `getExternalFilesDir`, `uniqueFile`, `FileOutputStream`, `BufferedWriter`, écriture et flush du header GPX. Cette zone pouvait bloquer le thread principal lors d'un START ou d'une reprise, notamment sur stockage lent/indisponible.

### 33.3 Design livré

La méthode `openRecordingSegment(int)` réserve à présent un `RecordingSegment` logique, le publie comme segment actif et poste la méthode `openRecordingSegmentOnIo(...)` dans la FIFO `ioFile`. Aucun accès disque d'ouverture n'est fait dans la méthode appelante.

La FIFO impose l'ordre physique :

1. tâche d'ouverture et en-tête complet avec flush ;
2. points acceptés pendant l'ouverture ;
3. éventuelles tâches de flush/coupure ;
4. fermeture ;
5. barrière de post-traitement/publication.

La tâche d'ouverture tourne avec le même verrou `gnssProcessingLock` pour l'ordre de post que les callbacks GNSS. Les points postés pendant `OPENING` restent derrière la tâche d'ouverture et sont soumis à la limite de file existante `MAX_GPX_QUEUE_DEPTH = 120`. Au moment d'exécuter un point, le worker exige que le segment soit READY et possède un writer valide.

### 33.4 Machine d'état

Nouveau fichier `app/src/main/java/com/example/gpstest/GpxSegmentIoState.java`.

États `OPENING`, `READY`, `FAILED`, `CLOSED`. Transitions synchronisées : `markReady`, `failOpen`, `canWrite`, `finishClose`. Un en-tête échoué rend le segment non inscriptible ; les points déjà placés en file échouent explicitement et sont comptés. La fermeture d'un segment incomplet préserve le fichier partiel éventuel et n'ajoute pas un pied GPX trompeur.

### 33.5 Arrêt, pause et course lifecycle

`closeRecordingSegment()` désactive l'acceptation puis place la fermeture derrière les tâches acceptées. Arrêt immédiat après START, suspension/reprise pendant l'ouverture et ouverture échouée sont gérés par l'état du segment et l'ordre FIFO. Le post-traitement reçoit l'objet `RecordingSegment` plutôt que de capturer un champ global `gpxFile` potentiellement nul ; une barrière `ioFile` attend que le segment soit fermé avant de lancer le nettoyeur.

Le verrou `ioLock` protège les opérations de flux et le repli de fermeture. Le chemin de publication SAF garde un repli sur un thread dédié lorsque le worker de publication est déjà arrêté.

### 33.6 Protection contre l'écrasement

`uniqueFile(File)` utilise désormais `File.createNewFile()` pour réserver atomiquement un nom disponible. L'ancien enchaînement `exists()` puis ouverture pouvait perdre une course ; après épuisement des suffixes, l'ancienne implémentation retournait le nom initial, potentiellement déjà existant. La nouvelle implémentation lève une `IOException` si les variantes sont toutes occupées et n'ouvre jamais le nom existant. Le `FileOutputStream` ouvre le fichier réservé en append, sans troncature implicite.

### 33.7 Journal de session et drainage

`persistSession()` délègue à `persistSessionTo(Handler)`. Lors de l'arrêt gracieux, la barrière finale du worker fichier prend un snapshot du nom GPX après la fin des opérations d'ouverture/fermeture déjà en file et poste ce snapshot sur le worker méta avant `quitSafely()` de ce dernier. Ceci évite qu'un `onDestroy()` survenant pendant `OPENING` laisse le dernier snapshot sans le nom du fichier qui vient d'être créé. La sauvegarde de drainage ne s'exécute pas lorsque `stopRequested` est vrai : un arrêt volontaire ne recrée pas le journal effacé.

`safeLengthBytes` reste `0L`. R2B.1 n'est pas un checkpoint durable et ne doit pas être présenté comme une garantie de réparation après SIGKILL, crash natif ou coupure électrique.

### 33.8 Fichiers modifiés/ajoutés

Six fichiers de code, test et CI :

| Chemin | Opération |
|---|---|
| `app/src/main/java/com/example/gpstest/TrackingService.java` | modifié |
| `app/src/main/java/com/example/gpstest/GpxSegmentIoState.java` | nouveau |
| `tests/GpxSegmentIoStateTest.java` | nouveau |
| `tests/GpxSegmentQueueOrderTest.java` | nouveau |
| `tools/verify_gpx_async_open.py` | nouveau |
| `.github/workflows/android-build.yml` | modifié (ajout d'une étape de contrôle statique) |

La livraison de remplacement inclut également la note `docs/LOT-8C-R2B1-OUVERTURE-GPX-ASYNCHRONE-2026-11-24.md` et `LISEZ-MOI-LOT-8C-R2B1.md`.

### 33.9 Tests réellement exécutés sur le candidat R2B.1

| Contrôle | Résultat |
|---|---|
| `python3 tools/run_jvm_tests.py` | 41 suites, 1 421 assertions, 0 échec |
| `python3 tools/verify_gpx_async_open.py` | tous les invariants structurels passent |
| `python3 tools/verify_android_project.py` | 17 XML, 81 sources Java, audit statique réussi |
| `python3 tools/verify_offline_map_architecture.py` | 99 fichiers, architecture sans raster validée |
| Parseur YAML du workflow | succès, jobs `android` et `gpx-xsd` reconnus |
| Compilation Android Gradle | non exécutée : Gradle/Wrapper/SDK absents de l'environnement |
| Android Lint / tests instrumentés / tests appareil | non exécutés |

Les tests JVM de FIFO utilisent les stubs `HandlerThread` fournis par le projet. Ils vérifient les transitions et l'ordre des tâches, mais ne remplacent pas des tests de service sous Android réel. Le script `verify_gpx_async_open.py` vérifie aussi les points d'intégration dans le vrai `TrackingService.java`.

### 33.10 Livrables R2B.1

- Archive de remplacement des fichiers : `GPS-Test-LOT-8C-R2B1-FICHIERS-A-REMPLACER-2026-11-24.zip`.
- Archive complète : `GPS-Test-LOT-8C-R2B1-PROJET-COMPLET-2026-11-24.zip`.
- Note technique : `docs/LOT-8C-R2B1-OUVERTURE-GPX-ASYNCHRONE-2026-11-24.md`.
- Instructions : `LISEZ-MOI-LOT-8C-R2B1.md`.
- SHA-256 : `GPS-Test-LOT-8C-R2B1-SHA256.txt`.

Aucun fichier GitHub n'a été modifié. L'utilisateur remplace lui-même les fichiers demandés dans le dépôt, conformément à la règle de livraison du projet. Ne jamais lui demander d'appliquer un `.patch` Git.

### 33.11 Prochaine étape

**Attendre la confirmation de compilation Android locale du projet complet R2B.1.** Une fois compilé sans erreur, continuer par R2B.2 : checkpoint d'un préfixe GPX durable, fondé sur un marqueur FIFO, flush du writer, `FileDescriptor.sync()` effectivement réussi, longueur exacte du fichier et écriture atomique de `SessionJournal`. Ne jamais calculer `safeLengthBytes` depuis `pointsWritten`.

Avant toute affirmation de robustesse finale, tester : START/STOP immédiat, stockage lent ou plein, permission révoquée pendant OPENING, suspension/reprise pendant OPENING, rafale supérieure à 120 points en attente, service détruit pendant ouverture, fichier existant/collisions et publication SAF lorsque le worker est arrêté.

---

**Fin de la mise à jour MAJ5.**


# 34. Addendum MAJ6 — LOT 8C-R2B.2 : checkpoint GPX durable

**Date :** 24 novembre 2026, Europe/Paris.  
**Base de travail :** archive complète `GPS-Test-LOT-8C-R2B1-PROJET-COMPLET-2026-11-24.zip`, copiée dans un candidat local indépendant.  
**État :** code du LOT 8C-R2B.2 préparé et contrôlé ; compilation Android de R2B.2 à confirmer par l'utilisateur.  
**Politique GitHub :** aucune modification distante. L'assistant prépare des archives de fichiers à remplacer et une archive complète ; l'utilisateur seul recopie les fichiers dans son dépôt.

## 34.1 Objectif précis

Remplacer le `safeLengthBytes=0` provisoire du journal R1/R2B.1 par une longueur de fichier qui désigne uniquement un préfixe GPX confirmé par le système de fichiers. La longueur n'est jamais déduite de `pointsWritten`, des points en file ni d'un simple `BufferedWriter.flush()`.

La base R2B.1 a été compilée sans erreur par l'utilisateur. Ce retour concerne R2B.1 ; il ne constitue pas une validation de compilation de R2B.2.

## 34.2 Séquence d'un checkpoint

La méthode centralisée `TrackingService.syncSegmentAndCheckpoint(RecordingSegment)` est exécutée dans le worker `ioFile`, dans l'ordre FIFO des opérations du segment et avec `segment.ioLock` :

1. `segment.writer.flush()` ;
2. `segment.output.getFD().sync()` ;
3. seulement si `sync()` ne lève pas d'exception, lecture de `segment.file.length()` ;
4. `segment.checkpoint.advance(segment.file.getName(), length)` ;
5. poste `queueSegmentCheckpoint()` sur `ioMeta` avec l'UUID de session, l'ID de génération, le nom de fichier et la longueur ;
6. `SessionStore.updateCheckpoint()` vérifie les quatre identités/contraintes et écrit le journal au moyen d'`AtomicFile`.

Si le `sync()` échoue, le modèle en mémoire n'avance pas et l'utilisateur reçoit un avertissement de durabilité. Les valeurs nulles, mauvaises associations de fichier ou régressions sont refusées. La synchronisation n'est pas exécutée sur le thread UI ni dans le callback GNSS.

## 34.3 Premier checkpoint, cadence et fermeture

Le premier sync est tenté immédiatement après l'en-tête afin d'enregistrer le premier préfixe sûr sans attendre le jalon de trente secondes. Pour respecter l'ordre du worker méta, `queueSegmentRegistration()` est posté dès que le nom du nouveau fichier a été réservé par `uniqueFile()`, puis l'ouverture/écriture de l'en-tête effectue son premier checkpoint. Le worker méta reçoit ainsi `registerSegment` avant `updateCheckpoint`.

Ensuite le mécanisme central est utilisé :
- pendant l'écriture des points quand le délai de synchronisation arrive (30 secondes) ;
- lors du flush périodique de secours sans fix, si le délai de sync est écoulé ;
- à la fermeture du segment, après l'écriture du pied GPX.

Lors d'une fermeture normale, le checkpoint final peut inclure le pied et correspond alors à un GPX complet. En cas de sync non confirmé, le checkpoint précédent est conservé.

## 34.4 Génération de segment et rejet des opérations retardées

Un `RecordingSegment` capture maintenant : `id` (long), `sessionId`, `GpxCheckpoint`, fichier, flux et état. Le segment est identifié indépendamment des champs globaux qui peuvent déjà appartenir au segment suivant.

`SessionStore` a reçu deux méthodes :
- `registerSegment(ctx, sessionId, segmentGeneration, fileName)` : associe le nouveau fichier et réinitialise le checkpoint à zéro ; rejette une génération plus ancienne ou un nom incohérent ;
- `updateCheckpoint(ctx, sessionId, segmentGeneration, fileName, safeLengthBytes)` : exige la même session, génération, identité de fichier et une longueur positive et monotone.

`SessionStore.write()` empêche également les snapshots UI retardés :
- génération plus ancienne rejetée ;
- même génération et même fichier : l'offset est le maximum entre snapshot et valeur courante ;
- snapshot `OPENING` avec nom vide ne peut pas effacer un fichier déjà enregistré ;
- une nouvelle génération ne doit pas hériter du checkpoint de la précédente.

Cela protège contre les tâches de `ioFile`, `ioMeta` et UI arrivant dans un ordre différent, sans permettre à un ancien segment de faire régresser le journal du nouveau segment.

## 34.5 Format 5 du journal

`SessionJournal.FORMAT_VERSION=5` ajoute `segmentGeneration=<long>`. `safeLength` garde le sens d'offset de préfixe synchronisé. Les formats 1 à 4 restent lisibles ; une génération absente vaut zéro. Le protocole de stockage reste `AtomicFile`.

Le snapshot de session reprend la valeur de `journalSegment.checkpoint.snapshot()` (segment actif, sinon dernier segment fermé). Le champ `safeLengthBytes` n'est pas fabriqué à partir du compteur de points.

## 34.6 Correction complémentaire du chemin `onDestroy()`

La revue de R2B.2 a identifié une course sémantique : `onDestroy()` demande la fermeture du segment puis remet `state.recording=false` pour refléter l'état UI arrêté. Le snapshot final, posté après drainage de `ioFile`, peut donc se produire après ce changement. Sans protection, ce snapshot écraserait `recording=true` et le journal ne décrirait plus une interruption survenue pendant un enregistrement.

Un champ `serviceDestroyedWhileRecording` capture, avant le basculement UI, si le service a été détruit de manière non volontaire pendant un enregistrement. `persistSessionTo()` conserve `journalRecording=true` dans le snapshot final seulement dans ce cas. Un arrêt volontaire (`stopRequested=true`) n'est pas transformé en interruption et continue d'effacer le journal.

Cette correction est couverte par un invariant statique ajouté à `tools/verify_gpx_checkpoint.py`. Le modèle `SessionJournal` dispose déjà de tests montrant qu'un journal actif et dirty est qualifié comme interruption GPX.

## 34.7 Fichiers modifiés / ajoutés par R2B.2

| Chemin | Nature |
|---|---|
| `app/src/main/java/com/example/gpstest/TrackingService.java` | modifié : sync initial/périodique/final, longueur réelle, checkpoint, état `onDestroy` |
| `app/src/main/java/com/example/gpstest/SessionJournal.java` | modifié : format 5 et génération |
| `app/src/main/java/com/example/gpstest/SessionStore.java` | modifié : association segment et checkpoint monotone |
| `app/src/main/java/com/example/gpstest/GpxCheckpoint.java` | nouveau : modèle pur du fichier et de sa longueur sûre |
| `tests/GpxCheckpointTest.java` | nouveau : monotonie, association, simulation de troncature au dernier préfixe sync |
| `tests/SessionJournalTest.java` | modifié : nouveau format et rétrocompatibilité |
| `tests/SessionStoreTest.java` | modifié : génération, snapshots retardés, monotonicité |
| `tools/verify_gpx_checkpoint.py` | nouveau : 15 invariants statiques de l'ordre de sync et du journal |
| `.github/workflows/android-build.yml` | modifié : ajoute le contrôle checkpoint au job Android |

Livraison documentaire : `docs/LOT-8C-R2B2-CHECKPOINT-GPX-DURABLE-2026-11-24.md` et `LISEZ-MOI-LOT-8C-R2B2.md`.

## 34.8 Contrôles réellement exécutés

Sur `/mnt/data/candidate_8c_r2b2/GPS-Test` :

| Commande / contrôle | Résultat |
|---|---|
| `python3 tools/run_jvm_tests.py` | **42 suites, 1 463 assertions réussies, 0 échec** |
| `python3 tools/verify_gpx_checkpoint.py` | **15 invariants checkpoint passés** |
| `python3 tools/verify_gpx_async_open.py` | invariants du LOT 8C-R2B.1 passés |
| `python3 tools/verify_android_project.py` | **17 XML, 82 sources Java** ; ressources/manifeste/permissions/FGS/SDK/Edge-to-Edge cohérents |
| `python3 tools/verify_offline_map_architecture.py` | **100 fichiers Android** ; aucun moteur/provider de tuiles raster |
| Parseur YAML PyYAML `.github/workflows/android-build.yml` | valide ; jobs `android` et `gpx-xsd` présents |
| `python3 -m py_compile tools/verify_gpx_checkpoint.py` | réussi |
| Compilation Gradle Android / Lint / tests instrumentés | non exécutés dans l'environnement de préparation |

Les 1 463 assertions sont le total courant de la suite JVM ; ne pas ajouter les totaux des lots précédents. Les contrôles statiques sont complémentaires et ne remplacent ni le compilateur Android, ni les essais sur appareil/OEM.

## 34.9 Limites et portée

R2B.2 stocke un offset de préfixe confirmé ; il ne restaure pas encore automatiquement la trace interrompue. Le journal peut légitimement conserver un offset plus ancien si le processus est tué après le sync du GPX mais avant l'écriture atomique de l'offset : cette situation est conservatrice et évite de prétendre que les octets sont référencés durablement. La récupération devra aussi gérer un fichier absent, trop court, une queue postérieure à l'offset, et distinguer un préfixe ouvert (pas de pied) d'un fichier déjà fermé (pied présent).

Un succès de `FileDescriptor.sync()` est la meilleure demande disponible via cette API Java, mais ne garantit pas qu'un contrôleur matériel défectueux respecte physiquement la commande. Le lot ne synchronise pas explicitement le répertoire parent ; le récupérateur devra donc traiter le cas où le fichier réservé n'existe finalement pas.

Il est interdit de présenter R2B.2 comme une récupération automatique après SIGKILL, crash natif, batterie arrachée ou coupure secteur : cela reste le lot suivant.

## 34.10 Livrables et méthode de remplacement

- `GPS-Test-LOT-8C-R2B2-FICHIERS-A-REMPLACER-2026-11-24.zip` : fichiers modifiés/nouveaux avec chemins, plus note et instructions.
- `GPS-Test-LOT-8C-R2B2-PROJET-COMPLET-2026-11-24.zip` : projet complet candidat.
- `GPS-Test-LOT-8C-R2B2-CHECKPOINT-GPX-DURABLE-2026-11-24.md` : note technique (copie également sous `docs/`).
- `GPS-Test_DOSSIER_REPRISE_ULTRA_EXHAUSTIF_2026-11-24_MAJ6.md` : présent addendum.
- `GPS-Test-LOT-8C-R2B2-SHA256.txt` : sommes de contrôle.

Aucun `.patch` Git n'est requis et aucune écriture sur GitHub n'est réalisée. L'utilisateur décompresse les fichiers à la racine de son dépôt local en acceptant les remplacements. **Avant R2B.3, attendre que l'utilisateur ait compilé R2B.2 sans erreur.**

## 34.11 Prochaine étape — LOT 8C-R2B.3 : récupération contrôlée du préfixe

Après confirmation de compilation R2B.2, implémenter une récupération prudente :

1. lire le journal dirty non acquitté et vérifier identité, génération et nom simple de fichier app-specific ;
2. vérifier fichier présent et longueur réelle `>= safeLengthBytes` ; si fichier plus court, ne jamais l'étendre ni fabriquer les octets manquants ; rapporter le défaut et préserver l'original ;
3. si la longueur dépasse l'offset, tronquer uniquement la queue non checkpointée ; synchroniser le fichier tronqué ;
4. détecter si le préfixe inclut déjà le pied GPX (checkpoint final) ; sinon fermer correctement les balises ouvertes, sans double pied ;
5. reparser et vérifier le GPX final, puis signaler le résultat ;
6. ne pas écraser l'original avant qu'une récupération validée ait été produite ; prévoir une copie/quarantaine sûre ;
7. acquitter le journal atomiquement seulement après succès, et préserver les cas d'échec pour une décision utilisateur/diagnostic.

Ne pas ajouter maintenant un registre multi-sessions sans étude séparée : la première livraison R2B.3 doit d'abord récupérer le fichier actif exact lié au journal.

---

**Fin de la mise à jour MAJ6.**


# 35. LOT 8C-R2B.3 — récupération contrôlée après interruption (MAJ7)

## 35.1 Objectif et règle de sécurité

R2B.2 persistait `SessionJournal.safeLengthBytes`, soit la longueur du préfixe GPX dont l'écriture avait franchi `BufferedWriter.flush()` puis `FileDescriptor.sync()`. R2B.3 utilise cette information pour restaurer une copie exploitable d'une trace interrompue.

**Règle centrale : le fichier source n'est jamais modifié.** Le récupérateur crée un fichier distinct avec le suffixe `_RECUPEREE`, construit exclusivement à partir des octets `[0, safeLengthBytes)`. Il n'ouvre jamais le fichier source en écriture, ne le tronque pas, ne le renomme pas et ne le supprime pas. Toute erreur laisse l'original disponible.

Le lot ne cherche pas à sauver un point partiellement écrit ni à reconstruire des octets absents. Le contenu postérieur au checkpoint est ignoré ; il peut s'agir d'une queue non synchronisée.

## 35.2 Base, provenance et portée du changement

- Base de départ : projet complet `GPS-Test-LOT-8C-R2B2-PROJET-COMPLET-2026-11-24.zip`.
- SHA-256 de cette archive R2B.2 : `342ded98cce56e3dfd2fa15c84a0d5e825489d20abacd6a90c3191112ffbbfa2`.
- Candidate préparée : `/mnt/data/work_r2b3/GPS-Test` dans l'environnement de préparation.
- Aucun fichier du dépôt GitHub n'a été modifié. Aucun push, commit ou merge distant n'a été effectué.
- Les compilations R2B.1 et R2B.2 ont été confirmées par l'utilisateur (absence de retour d'erreur selon la convention demandée). R2B.3 reste à compiler dans Android Studio.
- Le dépôt distant `main` n'a pas été comparé à un SHA réel depuis cette session ; l'archive R2B.2 est la base locale connue, pas une preuve du dernier commit distant.

## 35.3 Algorithme exact de récupération

La classe `app/src/main/java/com/example/gpstest/GpxRecovery.java` implémente le protocole :

1. Contrôler le dossier, le nom simple `.gpx`, le chemin canonique et le fait que la source reste directement dans le dossier privé prévu.
2. Vérifier que le fichier existe, est régulier et lisible, et que le checkpoint est strictement positif et ne dépasse pas la taille réelle.
3. Créer un fichier temporaire dans le même dossier.
4. Copier exactement les octets `[0, safeLengthBytes)` ; vider le tampon et synchroniser le temporaire par `FileDescriptor.sync()` ; vérifier la longueur de copie.
5. Analyser le préfixe comme document GPX complet. S'il est déjà complet, ne rien ajouter. Sinon, ajouter uniquement `GpxWriter.footer()` au temporaire et refaire le contrôle.
6. Vérifier que la taille et l'horodatage de modification de la source n'ont pas changé pendant la copie.
7. Publier le temporaire sous un nom distinct `source_RECUPEREE.gpx` (casse de l'extension conservée). Si le nom existe avec un contenu identique, réutiliser cette copie ; s'il existe avec un contenu différent, choisir `-2`, `-3`, etc. Aucun conflit de nom attendu n'est remplacé.
8. En cas d'échec, supprimer le temporaire si possible, rapporter un statut et conserver le fichier source.

Le contrôle XML utilise SAX, namespace-aware, `FEATURE_SECURE_PROCESSING`, refuse `DOCTYPE`, désactive les entités externes et le chargement de DTD, et installe un `EntityResolver` qui refuse toute entité externe. Le document doit avoir la racine `gpx`, un couple version/namespace GPX 1.0 ou 1.1 cohérent, et contenir une structure `gpx/trk/trkseg`.

**Limite explicite :** ce contrôle confirme la bonne formation XML et la structure GPX minimale attendue ; il ne fait pas une validation XSD complète, ne vérifie pas toutes les contraintes sémantiques du schéma et ne promet pas que chaque application tierce acceptera la trace. Le job GPX XSD séparé demeure un contrôle additionnel CI, avec la réserve historique que l'environnement de préparation n'a pas réussi à télécharger les XSD Topografix à cause de la résolution DNS.

## 35.4 Déclenchement et cycle de vie

### `MainActivity`

Au démarrage visible, un thread de fond lit `SessionStore`. Si `SessionJournal.isUnexpectedRecordingInterruption()` est vrai, la récupération est tentée même si une ancienne version avait déjà inscrit `noticeShown=true`. Cela rend récupérables les journaux R2B.2 déjà signalés.

- Succès : afficher le nom de la copie récupérée et rappeler que l'original est conservé ; appeler `SessionStore.clearIfSameSession()` uniquement après création réussie.
- Échec : afficher le motif ; ne pas acquitter le journal, afin qu'une prochaine ouverture puisse retenter (par exemple après remontage du volume).
- Une nouvelle session en cours ne doit pas voir sa notice remplacée par une récupération d'une session précédente.
- Les interruptions non liées à une trace GPX conservent leur ancien chemin de notice et d'acquittement.

### `TrackingService`

`checkPreviousSession()` lit le journal précédent sur `ioMeta`, puis, pour une interruption GPX, lance un thread dédié `gpx-session-recovery`. Le travail de copie et l'analyse XML ne sont réalisés ni sur `ioFile`, ni sur `ioMeta`, ni sur le thread UI. Après succès, le journal n'est effacé qu'avec `clearIfSameSession(previous.sessionId)` ; une récupération ancienne ne peut donc pas effacer le journal d'une nouvelle session avec un autre UUID.

L'Activity et le Service pouvant tous deux détecter le même état, `GpxRecovery` sérialise les appels dans le processus et réutilise un résultat déjà présent byte-à-byte identique. Il n'y a pas de doublon de copie pour ces déclenchements concurrents dans le même processus.

## 35.5 Statuts de résultat

`GpxRecovery.Result.status` expose :

- `RECOVERED` : copie créée ou copie strictement identique déjà présente réutilisée ;
- `INVALID_ARGUMENT` : dossier ou nom non sûr ;
- `NO_CHECKPOINT` : longueur non positive ;
- `SOURCE_MISSING` : source absente, non régulière ou illisible ;
- `CHECKPOINT_BEYOND_EOF` : longueur source inférieure au checkpoint ;
- `SOURCE_CHANGED` : taille/date source modifiée pendant le travail ;
- `INVALID_PREFIX` : le préfixe n'est pas un GPX valide au niveau XML/structure même après ajout éventuel du pied ;
- `IO_ERROR` : erreur de lecture, écriture, synchronisation ou publication.

La classe ne transforme pas un échec en succès partiel. Elle ne modifie pas le journal après échec ; il reste disponible pour une nouvelle tentative ou diagnostic.

## 35.6 Fichiers modifiés et ajoutés

| Chemin | Type | Responsabilité |
|---|---|---|
| `app/src/main/java/com/example/gpstest/GpxRecovery.java` | nouveau | copie du préfixe checkpointé, pied GPX conditionnel, parseur XML sécurisé, nom distinct et idempotence |
| `app/src/main/java/com/example/gpstest/MainActivity.java` | modifié | déclenchement sur le worker de démarrage, message et acquittement après succès seulement |
| `app/src/main/java/com/example/gpstest/TrackingService.java` | modifié | déclencheur de secours asynchrone, sans monopoliser `ioFile`/`ioMeta` |
| `tests/GpxRecoveryTest.java` | nouveau | 27 assertions de régression |
| `tools/verify_gpx_recovery.py` | nouveau | 15 invariants structurels de récupération et d'intégration workflow |
| `.github/workflows/android-build.yml` | modifié | étape `Verify interrupted GPX recovery contract` dans le job Android |
| `docs/LOT-8C-R2B3-RECUPERATION-GPX-2026-11-24.md` | nouveau | note technique détaillée |
| `LISEZ-MOI-LOT-8C-R2B3.md` | nouveau | consignes de remplacement |

Le format `SessionJournal` n'est pas modifié par R2B.3 ; la rétrocompatibilité format 5 introduite dans R2B.2 est conservée. Aucun code GNSS d'acquisition, filtrage de fixes, affichage de satellites ou cartographie n'est intentionnellement touché par ce lot.

## 35.7 Tests et vérifications réellement exécutés

Tous les contrôles suivants ont été relancés sur la candidate `/mnt/data/work_r2b3/GPS-Test` :

| Contrôle | Résultat observé |
|---|---|
| `python3 tools/run_jvm_tests.py` | **43 suites, 1 490 assertions réussies, 0 échec** |
| `GpxRecoveryTest` | **27 assertions réussies** |
| `python3 tools/verify_gpx_recovery.py` | **15 invariants récupération passés** |
| `python3 tools/verify_gpx_checkpoint.py` | invariants R2B.2 passés |
| `python3 tools/verify_gpx_async_open.py` | invariants R2B.1 passés |
| `python3 tools/verify_android_project.py` | 17 XML, 83 sources Java ; cohérence ressources, manifeste, permissions/FGS, SDK 37.2/target 36/min 24 et Edge-to-Edge |
| `python3 tools/verify_offline_map_architecture.py` | 101 fichiers Android analysés, aucun moteur/provider raster |
| Analyse YAML `android-build.yml` | workflow parse ; jobs `android` et `gpx-xsd` présents ; étape de vérification R2B.3 repérée |
| `python3 -m py_compile tools/verify_gpx_recovery.py` | réussi |
| Compilation Gradle Android / Lint / instrumentation / appareil | non exécutés dans l'environnement de préparation : Gradle absent et Wrapper non versionné |

Les tests `GpxRecoveryTest` couvrent notamment : récupération GPX 1.1 à partir du seul préfixe sync et exclusion de la queue non sync ; source byte-à-byte intacte ; reconnaissance d'un GPX 1.0 déjà fermé sans double pied ; checkpoint nul ou au-delà EOF ; fichier absent ; traversal et extension incorrecte ; namespace erroné ; `DOCTYPE` et entité externe ; coupure au milieu d'un `<trkpt>` ; idempotence et collision de nom sans écrasement.

Les tests JVM compilent et exécutent la classe `GpxRecovery` réelle et `GpxWriter`. Ils ne remplacent pas `:app:assembleDebug`, Android Lint ou une vérification sur smartphone : l'intégration `MainActivity` et `TrackingService` doit encore être compilée avec AGP/SDK dans Android Studio ou CI.

## 35.8 Limites connues et risques explicitement non résolus

1. **Compilation R2B.3 à confirmer.** Les lots R2B.1 et R2B.2 ont été confirmés compilés par l'utilisateur ; la nouvelle livraison n'a pas encore reçu cette confirmation.
2. **Essais réels à effectuer :** kill du processus sans `onDestroy`, crash lors d'un point, arrêt forcé, stockage saturé/démonté, fichier plus court que checkpoint, reprise et redémarrage rapide du suivi, puis ouverture des copies dans plusieurs lecteurs GPX.
3. **Pas de validation XSD dans le moteur de récupération.** Le contrôle est XML + racine/version/namespace + présence de `trk/trkseg`; la validation XSD est une vérification séparée.
4. **Pas de récupération des octets non checkpointés.** C'est volontaire : un point partiellement écrit ou la queue post-checkpoint ne sont pas revendiqués comme fiables.
5. **Pas de fsync explicite du dossier parent après renommage.** Le fichier temporaire est synchronisé avant publication, mais la durabilité de l'entrée de répertoire lors d'une coupure matérielle dépend du système de fichiers. L'original est conservé.
6. **Dossier app-specific seulement.** La copie `*_RECUPEREE` est créée dans `getExternalFilesDir(null)` ; elle n'est pas automatiquement publiée vers le dossier SAF choisi par l'utilisateur ni vers stockage public.
7. **Historique R2B.3/R2B.4 : pas de registre multi-sessions.** Cette limite a été traitée dans R2C.1, section 37 : `recovery.registry` permet maintenant plusieurs sessions et plusieurs générations. Les autres limites historiques de cette liste restent à lire au regard de la section 37.
8. **Parseur strict.** Si un appareil Android ne supporte pas l'une des protections SAX demandées, le récupérateur échoue de façon conservatrice ; l'original reste intact. Il faudra vérifier ce comportement sur les API Android réellement ciblées.
9. **Concurrence inter-processus non testée.** La sérialisation utilise un verrou statique de processus ; on ne revendique pas une coordination entre plusieurs processus Android distincts.

## 35.9 Installation et procédure pour l'utilisateur

La livraison propose deux archives :

- `GPS-Test-LOT-8C-R2B3-FICHIERS-A-REMPLACER-2026-11-24.zip` : nouveaux fichiers et fichiers modifiés avec leur arborescence exacte ; décompresser à la racine du projet local en acceptant les remplacements.
- `GPS-Test-LOT-8C-R2B3-PROJET-COMPLET-2026-11-24.zip` : projet complet, prêt à ouvrir pour inspection ou comparaison.

Ne pas appliquer de `.patch` Git : aucun patch n'est requis. Aucun fichier de GitHub n'a été modifié. Après remplacement local, ouvrir le projet dans Android Studio, faire Sync/Build et vérifier `:app:assembleDebug`. En l'absence de message d'erreur utilisateur, la convention est de traiter la compilation comme OK, mais ne pas l'annoncer avant son retour ou son silence après livraison.

## 35.10 Suite recommandée après compilation

Ne pas ajouter immédiatement un lot complexe supplémentaire sans validation appareil. D'abord conduire une petite matrice contrôlée :

1. Démarrer une trace et attendre plusieurs checkpoints ; tuer le processus depuis `adb` sans appeler `onDestroy` ; redémarrer l'app et vérifier la copie `_RECUPEREE`.
2. Vérifier que le fichier source reste octet pour octet identique et que la copie ne contient que le préfixe checkpointé + pied éventuel.
3. Simuler/obtenir un `safeLengthBytes=0`, un fichier absent et un fichier raccourci : aucune copie valide ne doit être créée, original et journal restent disponibles.
4. Tuer le processus pendant l'écriture d'un point : résultat soit récupéré au checkpoint précédent, soit refusé ; aucune coordonnée artificielle, aucun faux point tronqué.
5. Redémarrer le suivi immédiatement pendant le démarrage de l'Activity : le journal de la nouvelle session ne doit pas être effacé par une récupération ancienne.
6. Contrôler la version GPX obtenue dans plusieurs lecteurs / validateurs, dont le banc XSD officiel lorsqu'il est disponible.

Après ces essais, traiter dans un lot séparé l'expérience utilisateur (liste/choix de récupération), l'éventuelle publication SAF des copies récupérées et le registre de plusieurs sessions interrompues. Laisser `WorkManager` pour les traitements différés réellement persistants ; la récupération immédiate liée à l'ouverture de l'app/service reste distincte.

---

**Fin de la mise à jour MAJ7 — LOT 8C-R2B.3.**


# 36. LOT 8C-R2B.4 — durcissement de l'acquittement et de l'intégrité de récupération (MAJ8)

## 36.1 Statut et provenance

- Base : projet complet R2B.3, dont la compilation Android a été confirmée sans erreur par l'utilisateur.
- Candidate : `/mnt/data/work_r2b4/GPS-Test` durant la préparation.
- Aucun commit, push ou changement direct du dépôt GitHub n'a été effectué.
- La compilation R2B.4 n'est pas exécutée dans l'environnement de préparation ; elle doit être confirmée par Android Studio.

## 36.2 Problème identifié dans R2B.3

Les deux déclencheurs de récupération (`MainActivity` et `TrackingService`) appelaient `SessionStore.clearIfSameSession(context, previous.sessionId)` après succès. Ce contrôle protège bien un journal appartenant à un autre UUID, mais il ne protège pas une nouvelle révision écrite pour le même UUID pendant que la récupération tourne. `SessionStore` fournit déjà `clearIfUnchanged(context, expected)`, qui contrôle l'identifiant, la révision et `savedAtMs`.

Le récupérateur contrôlait aussi `source.length()` et `source.lastModified()` avant/après copie. Ces deux métadonnées ne suffisent pas à détecter une réécriture de même taille et de même horodatage.

## 36.3 Correction de l'acquittement

Les deux chemins de récupération utilisent désormais `SessionStore.clearIfUnchanged(context, previous)`. L'acquittement est refusé si le journal a été actualisé entre la lecture et la fin de la récupération. Le checkpoint plus récent reste donc disponible.

Le `clearIfSameSession()` générique utilisé dans d'autres opérations du service, notamment l'arrêt/effacement normal, reste inchangé. Ne pas remplacer aveuglément tous les appels à cette méthode : l'acquittement du résultat de récupération doit utiliser le snapshot exact, les autres usages doivent garder leur sémantique propre.

## 36.4 Vérification SHA-256 du préfixe

`GpxRecovery.prefixContentsEqual(source, temp, checkpoint)` calcule en flux SHA-256 des octets `[0, safeLengthBytes)` pour la source et la copie temporaire. Le contrôle est réalisé avant publication, en complément des métadonnées. La mémoire reste bornée au tampon I/O et aux deux digestes.

Si les digests diffèrent, le récupérateur retourne `SOURCE_CHANGED`, détruit son temporaire si possible et conserve la source. La comparaison ne transforme jamais la source en fichier écrivable et n'essaye pas de fusionner un contenu modifié.

`safeName()` rejette en plus les noms contenant des espaces de bord au lieu de valider `trim()` puis d'ouvrir le nom non normalisé.

## 36.5 Nouveaux tests et couverture

`GpxRecoveryTest` passe de 27 à 44 assertions :

- un checkpoint au milieu d'un caractère UTF-8 est refusé, la source reste intacte et le temporaire disparaît ;
- un document GPX sans `trk/trkseg` n'est pas présenté comme trace et aucune copie de récupération vide n'est publiée ;
- une mutation de source de même taille et même date est détectée par le digest ;
- six appels concurrents publient une seule copie finale, réutilisée par tous les appelants, sans temporaire orphelin ;
- les noms avec espaces de bord sont refusés.

`SessionStoreTest` passe de 54 à 60 assertions. Il lit un snapshot, écrit une révision plus récente de la même session avec un checkpoint accru, vérifie que l'ancien snapshot ne peut pas l'effacer, puis acquitte le snapshot réellement courant.

## 36.6 Fichiers modifiés

| Chemin | Action |
|---|---|
| `app/src/main/java/com/example/gpstest/GpxRecovery.java` | modifié : digest SHA-256, nom exact |
| `app/src/main/java/com/example/gpstest/MainActivity.java` | modifié : `clearIfUnchanged` après récupération |
| `app/src/main/java/com/example/gpstest/TrackingService.java` | modifié : `clearIfUnchanged` dans le repli |
| `tests/GpxRecoveryTest.java` | modifié : nouveaux scénarios |
| `tests/SessionStoreTest.java` | modifié : garde même session/révision |
| `tools/verify_gpx_recovery.py` | modifié : invariants structurels R2B.4 |
| `docs/LOT-8C-R2B4-SECURISATION-RECUPERATION-2026-11-25.md` | nouveau |
| `LISEZ-MOI-LOT-8C-R2B4.md` | nouveau |

Aucun changement de format journal, de logique d'acquisition GNSS, de schéma GPX ou du workflow YAML. Le workflow existant exécute déjà le script statique mis à jour.

## 36.7 Résultats réellement exécutés

| Contrôle | Résultat |
|---|---|
| `python3 tools/run_jvm_tests.py` | **43 suites, 1 513 assertions, 0 échec** |
| `GpxRecoveryTest` | **44 assertions** |
| `SessionStoreTest` | **60 assertions** |
| `python3 tools/verify_gpx_recovery.py` | tous les invariants R2B.4 passent |
| `python3 tools/verify_gpx_checkpoint.py` | passe |
| `python3 tools/verify_gpx_async_open.py` | passe |
| `python3 tools/verify_android_project.py` | 17 XML, 83 sources Java, cohérence statique passe |
| `python3 tools/verify_offline_map_architecture.py` | 101 fichiers, aucun moteur/provider raster |
| `python3 -m py_compile tools/verify_gpx_recovery.py` | passe |
| YAML workflow | valide, jobs `android` et `gpx-xsd` |
| Gradle assembleDebug / Lint / Android instrumentation / appareil | non exécutés ici |

Les tests JVM n'équivalent pas à une compilation Android des classes qui dépendent du SDK/AndroidX. La compilation R2B.4 est à demander à l'utilisateur après remplacement.

## 36.8 Installation et suite

Utiliser `GPS-Test-LOT-8C-R2B4-FICHIERS-A-REMPLACER-2026-11-25.zip`, décompresser à la racine du dépôt local en conservant les chemins et accepter les six remplacements. Pas de patch Git, pas de modification GitHub.

Cette consigne était celle de la MAJ8 et a été actualisée en MAJ9 : la compilation R2B.4 est confirmée ; le registre multi-sessions a été réalisé dans R2C.1. La campagne réelle Android/ADB reste explicitement reportée à plus tard (section 37.11) et ne doit pas être considérée comme effectuée. La publication SAF reste un lot logiciel distinct.

---

**Note historique :** la fin de MAJ8 ci-dessus est conservée pour traçabilité. Sa phrase « la publication SAF et un registre multi-sessions restent des lots séparés » est désormais dépassée pour le registre ; voir section 37. La campagne ADB reste effectivement reportée.

---

# 37. LOT 8C-R2C.1 — registre durable multi-sessions et multi-segments (MAJ9)

## 37.1 Statut et provenance

- Base immédiate : archive complète R2B.4 `GPS-Test-LOT-8C-R2B4-PROJET-COMPLET-2026-11-25.zip`.
- SHA-256 de cette base : `fe82288207ea60c67f84ce0b48317dfc87c1c0f8e1b0ed3332db9c2a643adc74`.
- La compilation Android R2B.4 a été confirmée sans erreur par l'utilisateur.
- Candidate R2C.1 : `/mnt/data/work_r2c_registry/GPS-Test` pendant la préparation ; archives de livraison R2C.1 associées à cette MAJ9.
- Aucun commit, push, modification de branche ou écriture sur GitHub n'a été effectué. L'utilisateur reste seul autorisé à reporter les fichiers dans son dépôt.
- La compilation Android R2C.1 n'a pas encore été confirmée ; elle doit être faite dans Android Studio après remplacement.

## 37.2 Défaut structurel observé dans R2B.4

R2B.4 possédait le journal primaire atomique `session.state`, qui ne représentait qu'une session et un segment courant. Un nouveau START pouvait remplacer le seul pointeur durable d'une trace ancienne encore à récupérer. Les opérations asynchrones ajoutaient un cas délicat : le dernier checkpoint ou la première ouverture d'un ancien segment pouvait arriver après que le journal courant avait changé de session ou de génération.

Le même phénomène est possible pour deux segments successifs d'une session : le suivant est réservé pendant que la fermeture et le checkpoint final du précédent circulent encore dans les files `ioFile` et `ioMeta`.

## 37.3 Architecture du registre

Nouveau fichier interne privé : `recovery.registry`, distinct de `session.state`.

Nouvelle classe pure Java : `RecoveryRegistry`. Les entrées sont uniques par `(sessionId, segmentGeneration)` et identifient aussi le `file` attendu. Plusieurs générations de la même session et plusieurs sessions peuvent coexister.

Le registre possède un format propre (`registryFormat=1`). Chaque `SessionJournal.toText()` imbriqué est encodé en UTF-8 puis en hexadécimal pour tenir sur une seule ligne `entry=`. La persistance utilise `AtomicFile`, `flush()`, `FileDescriptor.sync()` et `finishWrite()`.

Limites : `MAX_ENTRIES=64`, texte total 512 Kio, entrée hexadécimale 32 Kio. Il n'y a **aucune éviction automatique** : à la limite, l'opération est refusée et les anciennes entrées restent disponibles.

Le parseur vérifie la version, le compteur, le nombre de lignes, la ligne terminale, l'absence de CR, les entrées, l'unicité des clés, le décodage hex ASCII, l'UTF-8 strict et l'éligibilité du `SessionJournal`. Un fichier illisible est conservé ; il n'est jamais réinitialisé automatiquement comme registre vide.

## 37.4 Conditions d'éligibilité

Une entrée représente un segment potentiellement récupérable lorsque son snapshot indique `dirty=true`, `expectedAlive=true`, `recording=true`, `suspended=false`, un nom de fichier local, une génération valide et `safeLengthBytes > 0`.

Le préfixe n'est pas inventé : il doit provenir du mécanisme R2B.2 (vidage du writer, synchronisation du descripteur, mesure de longueur, journalisation). Les segments sans checkpoint positif restent traités par le chemin de notice historique ; le registre n'annonce pas qu'un préfixe inexistant est récupérable.

## 37.5 Cycle d'écriture et préservation multi-sessions

1. Avant qu'un nouveau `sessionId` remplace le journal primaire, `SessionStore.write()` préserve l'ancien snapshot éligible dans le registre.
2. Les snapshots actifs sont inscrits dans le registre avant la mise à jour du journal primaire ; une interruption entre les deux écritures ne fait donc pas perdre le checkpoint.
3. `registerSegment()` garde l'association du fichier au segment courant.
4. `updateCheckpoint()` traite normalement le segment courant, mais route aussi les callbacks retardés d'un segment précédent par session, génération et nom de fichier.
5. Si une ouverture asynchrone ne publie son premier checkpoint qu'après un nouveau START, le store peut créer un snapshot détaché à partir du fichier exact, puisque ce callback n'est déclenché qu'après `flush + fd.sync`.
6. Après la fermeture, l'entrée n'est retirée qu'après synchronisation du pied GPX et vérification `safeLengthBytes == file.length()`.
7. La récupération parcourt toutes les entrées du registre depuis le worker de démarrage de `MainActivity` ou le repli du service. Les acquittements d'un segment ne touchent pas aux autres sessions.

## 37.6 Acquittement complet et protection du journal plus récent

`RecoveryRegistry.sameSnapshot()` compare maintenant toute la sérialisation canonique `SessionJournal.toText()` : identité, révision, date, état dirty/recording/suspended, notice, compteurs, génération, fichier et offset.

`SessionStore.clearRecoveryIfUnchanged()` retire l'entrée du registre uniquement si elle correspond au snapshot lu. Le journal primaire est supprimé seulement si son snapshot complet est encore identique. Il ne suffit plus que session/segment/fichier/offset soient identiques : des points peuvent être en attente du checkpoint suivant ou des indicateurs peuvent avoir évolué. Une récupération idempotente supplémentaire est préférable à la suppression d'un état plus récent.

Le modèle empêche également qu'un `upsert` remplace un snapshot avec une révision ou une date plus ancienne, même lorsque l'offset est identique. `SessionStore.write()` conserve `savedAtMs` monotone à travers les snapshots retardés.

## 37.7 Gestion d'un registre corrompu ou saturé

- Si le registre est corrompu ou de version inconnue, `readRecoveries()` n'invente aucune entrée et le fichier reste conservé.
- Toute tentative de le remplacer par une nouvelle session échoue de façon conservatrice lorsque la préservation n'est pas prouvée.
- À 64 entrées, le registre n'évince aucune ancienne trace ; l'ajout/refraîchissement qui ne peut pas être validé échoue.
- La couche service consigne l'échec de journal/checkpoint ; la présentation utilisateur d'une alerte dédiée et un écran de diagnostic restent à améliorer dans un lot ultérieur.

Ne pas supprimer manuellement `recovery.registry`, même si une entrée est difficile à récupérer. Une procédure de diagnostic/export doit être définie avant d'offrir un bouton de purge.

## 37.8 Fichiers modifiés et ajoutés

| Chemin | Action |
|---|---|
| `app/src/main/java/com/example/gpstest/MainActivity.java` | modifié : parcours et acquittement du registre |
| `app/src/main/java/com/example/gpstest/RecoveryRegistry.java` | nouveau : registre pur, strict et borné |
| `app/src/main/java/com/example/gpstest/SessionStore.java` | modifié : AtomicFile du registre, migration, checkpoint tardif, acquittement |
| `app/src/main/java/com/example/gpstest/TrackingService.java` | modifié : routing des checkpoints et retrait segmenté à fermeture durable |
| `tests/RecoveryRegistryTest.java` | nouveau : invariants du modèle |
| `tests/SessionStoreTest.java` | modifié : intégration, sessions A/B, segments, corruption, mêmes offsets |
| `tools/verify_gpx_checkpoint.py` | modifié : garde de checkpoint et callbacks d'anciens segments |
| `tools/verify_gpx_recovery.py` | modifié : invariants R2C.1 et parser strict |
| `docs/LOT-8C-R2C1-REGISTRE-RECUPERATION-MULTI-SESSIONS-2026-11-25.md` | nouveau |
| `LISEZ-MOI-LOT-8C-R2C1.md` | nouveau |

Le format primaire `SessionJournal` reste au format 5. Le workflow CI existant exécute déjà les deux scripts statiques et le runner JVM ; aucun changement workflow n'a été nécessaire.

## 37.9 Résultats réellement exécutés

| Vérification | Résultat |
|---|---|
| `python3 tools/run_jvm_tests.py` | **44 suites, 1 581 assertions, 0 échec** |
| `RecoveryRegistryTest` | 33 assertions |
| `SessionStoreTest` | 95 assertions |
| `python3 tools/verify_gpx_recovery.py` | tous les invariants R2C.1 passent |
| `python3 tools/verify_gpx_checkpoint.py` | passe avec routage multi-segments |
| `python3 tools/verify_gpx_async_open.py` | passe |
| `python3 tools/verify_android_project.py` | 17 XML, 84 sources Java, audit statique passe |
| `python3 tools/verify_offline_map_architecture.py` | 102 fichiers, aucun moteur/provider raster |
| `python3 -m py_compile ...` | scripts Python concernés compilent |
| Compilation Android R2C.1 / Lint / instrumentation | à confirmer dans Android Studio/CI |
| Appareil réel / ADB | reporté, aucun résultat à ce jour |

Les tests couvrent plusieurs sessions et plusieurs segments d'une même session, une fermeture/checkpoint retardé, un premier checkpoint d'ouverture après un nouveau START, une récupération/effacement ciblé, la limite 64 sans éviction, un registre corrompu, les snapshots à même offset avec indicateurs modifiés, la monotonie date/révision et un parsing hex/UTF-8 strict.

## 37.10 Installation de la candidate

Utiliser `GPS-Test-LOT-8C-R2C1-FICHIERS-A-REMPLACER-2026-11-25.zip` à la racine du projet local, au même niveau que `app`, `tests`, `tools`, `docs` et `.github`. Garder l'arborescence de l'archive et accepter les remplacements. L'archive complète `GPS-Test-LOT-8C-R2C1-PROJET-COMPLET-2026-11-25.zip` sert à ouvrir/inspecter une copie autonome.

Aucun patch Git n'est requis. Aucun fichier GitHub n'a été modifié ; l'utilisateur reportera lui-même les fichiers. La compilation Android R2C.1 est la validation suivante.

## 37.11 Campagne Android réel + ADB — EXPLICITEMENT REPORTÉE

**Décision utilisateur du 25 novembre 2026 :** placer les essais sur téléphone réel et avec ADB « pour plus tard ». Cette étape reste dans la liste des tâches et ne doit pas être présentée comme effectuée. Ne pas bloquer les améliorations code/test JVM actuelles sur cette campagne, mais la réaliser avant toute affirmation de fiabilité en conditions réelles ou publication.

### Pré-requis au moment de la campagne

- Compiler et installer une version R2C ultérieure validée dans Android Studio/CI.
- Téléphone physique avec débogage USB activé, câble et `adb devices -l` montrant un seul appareil autorisé.
- Vérifier l'`applicationId` réel dans le Gradle/manifest (l'historique utilise `com.example.gpstest`, à confirmer avant les commandes).
- Autoriser les permissions nécessaires GNSS/notifications et commencer avec un stockage libre suffisant.
- Utiliser une trace de test identifiée ; ne pas utiliser une trace de production ou un GPX irremplaçable.
- Garder les logs (`adb logcat -v time`) et exporter l'original ainsi que les copies récupérées pour comparaison.

### Scénarios minimum

1. Démarrer une trace, attendre l'ouverture du fichier et au moins deux checkpoints (attendre plus que la période configurée, environ 30 s dans R2B.2), puis relever le nom et la taille du GPX.
2. Capturer `session.state` et `recovery.registry` depuis le stockage privé via `run-as` lorsque permis, ou contrôler les fichiers à travers les outils internes/export de diagnostic ; ne pas modifier les fichiers réels à la main.
3. Tuer le processus sans supposer que `onDestroy()` sera appelé. Utiliser `adb shell am force-stop <applicationId>` comme scénario reproductible de terminaison du processus, puis relancer explicitement l'application. Vérifier les logs et la récupération proposée.
4. Vérifier qu'une copie `_RECUPEREE` est produite si un checkpoint valide existe, que le préfixe copié correspond aux octets checkpointés, et que l'original n'a pas été modifié. Comparer les octets/digests en tenant compte du pied GPX ajouté dans la copie.
5. Relancer l'application plusieurs fois : vérifier l'idempotence, l'absence de copies identiques surnuméraires et l'absence d'acquittement d'un journal récent.
6. Tester un `safeLengthBytes=0`, un offset supérieur à la taille du fichier, un fichier source absent et une source tronquée au moyen de fixtures dédiées / d'un emplacement de test isolé. Ne pas altérer le fichier réel d'une trace utilisateur.
7. Simuler l'échec de stockage uniquement sur émulateur ou volume de test isolé ; ne pas remplir volontairement le stockage principal du téléphone.
8. Tester un nouveau START pendant la récupération d'une ancienne session et vérifier que les deux restent indépendantes.
9. Contrôler les GPX récupérés dans un lecteur externe et, lorsque les XSD officiels sont disponibles, avec le banc XSD GPX 1.0/1.1.

### Commandes de départ (adapter l'identifiant confirmé)

```bash
adb devices -l
adb shell am force-stop com.example.gpstest
adb shell monkey -p com.example.gpstest 1
adb logcat -v time
```

`am force-stop` est un scénario de process death reproductible ; il ne doit pas être confondu avec un arrêt utilisateur normal via l'interface et ne garantit pas le même enchaînement de callbacks. La campagne doit vérifier dans les logs que le processus a bien été relancé et que la récupération provient du journal/registre. Si l'app ID diffère, remplacer la valeur dans chaque commande.

### Critères de réussite

- le service ne dépend pas de `onDestroy()` pour conserver le dernier checkpoint déjà synchronisé ;
- le journal/registre survivent au nouveau démarrage ;
- aucun octet au-delà du checkpoint n'est revendiqué comme récupéré ;
- le fichier original reste inchangé ;
- une nouvelle session n'est pas effacée par l'acquittement d'une ancienne ;
- une récupération répétée est idempotente ;
- une panne stockage/parseur garde l'original et l'entrée de registre ;
- les GPX produits sont acceptés par les validateurs XSD attendus pour leur version.

## 37.12 Limites connues et suite

- La publication des copies de récupération vers le dossier SAF choisi n'est pas encore transactionnelle ; les copies restent dans le dossier app-specific.
- Une entrée registre corrompue ou capacité atteinte est préservée, mais une interface de diagnostic utilisateur doit encore être conçue.
- Le verrou du registre est intra-processus ; aucun support multi-processus n'est revendiqué.
- Les tests ADB/appareil réel sont reportés et absents des résultats de cette livraison.

Prochain lot logiciel recommandé : **R2C.2 — publication SAF transactionnelle des copies récupérées**, avec copie destination, digest de vérification, état durable de publication et reprise idempotente. Définir précisément à quel moment l'entrée du registre est acquittée (copie privée récupérée durable ou copie SAF publiée et vérifiée) avant de coder. Après les prochains lots, programmer la campagne réelle de la section 37.11.

---

**Fin de MAJ9 — LOT 8C-R2C.1.**

# 38. LOT 8C-R2C.2 — publication SAF transactionnelle des GPX récupérés (MAJ10)

## 38.1 Statut, provenance et base

- Date de préparation : 25 novembre 2026.
- Base immédiate : projet complet R2C.1 `GPS-Test-LOT-8C-R2C1-PROJET-COMPLET-2026-11-25.zip`.
- SHA-256 de la base R2C.1 : `9de4428ee64cc077e64239ba23ae39ad3a09339ddc9b33c21c4bf88cc7c2ef0c`.
- La compilation Android R2C.1 a été confirmée sans erreur par l'utilisateur.
- R2C.2 est une candidate locale préparée dans une copie de travail ; tests JVM et audits statiques passent. Sa compilation Android doit être confirmée dans Android Studio après remplacement.
- Aucun commit, push, branche distante, fichier GitHub ou paramètre de dépôt n'a été modifié par l'assistant.
- L'utilisateur reste seul autorisé à reporter les fichiers dans son dépôt.

## 38.2 Problème traité

R2C.1 protège plusieurs sessions/segments interrompus dans `recovery.registry`, et R2B.3/R2B.4 construisent une copie GPX locale conservatrice à partir du préfixe synchronisé. Cette copie restait dans le stockage app-specific : elle n'était pas transférée dans le dossier SAF choisi par l'utilisateur.

Une simple création et un `flush()` SAF ne suffisent pas à garantir que le fournisseur a durablement stocké les bons octets. Le nouveau lot introduit un état transactionnel séparé et exige une relecture complète après fermeture du flux.

## 38.3 Règle de durabilité retenue

Le fichier local récupéré est la source de vérité et reste conservé. L'original interrompu n'est jamais modifié par la publication.

Le workflow :

1. récupère localement le préfixe du checkpoint et vérifie la copie (mécanisme R2B.3/R2B.4) ;
2. lit ou crée une entrée du registre de publication ;
3. vérifie les permissions SAF READ + WRITE ;
4. retrouve ou crée un document dans le dossier choisi ;
5. écrit les octets, ferme le flux ;
6. relit le document intégralement et compare longueur + SHA-256 ;
7. persiste l'état `VERIFIED` ;
8. retire l'état de publication vérifié ;
9. acquitte le snapshot complet de la récupération uniquement si celui-ci est toujours inchangé.

Si une étape échoue, l'entrée de récupération et la copie locale restent disponibles. Si la publication est vérifiée mais le journal a évolué, le message distingue la publication effective de l'acquittement encore en attente.

## 38.4 Registre de publication distinct

Fichier privé : `recovery-publications.registry`. Il est indépendant du journal primaire `session.state` et du registre multi-sessions `recovery.registry`.

La classe pure `RecoveryPublicationRegistry` utilise `publicationFormat=1`, 64 entrées maximum, sans éviction automatique. Une entrée conserve publication/session/génération, fichier interrompu, nom de copie locale, longueur, digest SHA-256, URI de dossier SAF, nom de destination, URI de document et état.

États monotones :

- `PREPARED` : intention durable avant l'opération externe ;
- `DOCUMENT_CREATED` : URI du document connue et persistée ; les écritures partielles peuvent être reprises ;
- `VERIFIED` : lecture de retour complète avec longueur et digest identiques.

Le parseur vérifie version, lignes, compte, doublons, URI, caractères hex ASCII, UTF-8 strict et limites. Une corruption bloque les opérations destructives ; pas de réinitialisation silencieuse.

## 38.5 Adaptateur SAF et limites fournisseur

`SafRecoveryAccess` utilise `DocumentsContract` et `ContentResolver` :

- exige une permission persistée de lecture **et** d'écriture ;
- liste les enfants du dossier pour retrouver un document créé avant une interruption ;
- crée en MIME `application/gpx+xml` ;
- écrit avec le mode `wt` ;
- relit par `openInputStream()` ;
- limite l'analyse exhaustive à 20 000 enfants. Si la limite est dépassée, la publication reste en attente au lieu de créer un document sans avoir pu prouver l'absence de collision.

Un fournisseur cloud/SD/MTP qui refuse le listing ou la lecture de retour ne permet pas de passer à `VERIFIED`. Le fichier local est préservé, aucune publication réussie n'est annoncée. Les tests du lot utilisent un fournisseur SAF simulé ; les fournisseurs Android réels sont à valider plus tard sur téléphone.

## 38.6 Collision de noms et fenêtre create/URI

Le nom de publication est déterministe et contient un fragment de l'identifiant de transaction. Le listing préalable sert à retrouver un document après crash et éviter un doublon.

Cas important : si `createDocument()` a réussi mais que le processus meurt avant que l'URI du document soit écrite dans le registre, la transaction est encore `PREPARED`. Le document retrouvé par son nom n'est adopté que si la totalité de son contenu correspond déjà au fichier local (longueur + SHA-256). Un document homonyme illisible ou différent n'est pas écrasé ; la transaction reste en attente. L'utilisateur peut résoudre le fichier orphelin dans son dossier, puis la tentative suivante recommence.

Après sauvegarde de l'URI (`DOCUMENT_CREATED`), une écriture partielle peut être reprise sur ce document transactionnel connu. En cas de changement de dossier, l'entrée est retargetée ; l'ancien document éventuel reste intact et n'est pas supprimé automatiquement.

## 38.7 Workflow commun et exécution hors GNSS

`GpxRecoveryWorkflow.recoverAndPublish()` est utilisé par `MainActivity` et `TrackingService`. Les appels sont initiés sur thread de fond ; dans le service, la récupération est déportée sur un thread séparé après la lecture du registre sur `ioMeta`. Le verrou du workflow sérialise les récupérations simultanées dans le processus. Aucune opération SAF n'est exécutée dans le callback GNSS ou sur `ioFile`.

Les résultats exposent les états `PUBLISHED`, `LOCAL_ONLY`, `PENDING_PERMISSION`, `PENDING_PUBLICATION`, `RECOVERY_FAILED` et `JOURNAL_NOT_ACKNOWLEDGED`. Les méthodes `isPublishedExternally()`, `isExternalPublicationPending()`, `isJournalAcknowledgementPending()` et `isRecoveredLocallyOnly()` empêchent les compteurs/notifications de confondre publication vérifiée, transfert en attente, journal restant à acquitter et copie privée locale.

Le retry automatique a lieu lors du prochain parcours de récupération au démarrage de l'application ou du service. Un bouton utilisateur dédié pour relancer une publication en attente n'est pas inclus dans R2C.2.

## 38.8 Fichiers modifiés/ajoutés

| Chemin | Action |
|---|---|
| `.github/workflows/android-build.yml` | ajout du contrôle structurel de la publication SAF |
| `app/src/main/java/com/example/gpstest/MainActivity.java` | passage au workflow commun et compteurs d'état distincts |
| `app/src/main/java/com/example/gpstest/TrackingService.java` | même workflow sur worker de fond et messages précis |
| `app/src/main/java/com/example/gpstest/RecoveryPublicationRegistry.java` | nouveau registre pur, versionné et strict |
| `app/src/main/java/com/example/gpstest/RecoveryPublicationStore.java` | nouvelle persistance `AtomicFile` |
| `app/src/main/java/com/example/gpstest/SafRecoveryAccess.java` | nouvel adaptateur SAF, permission READ/WRITE et scan borné |
| `app/src/main/java/com/example/gpstest/GpxRecoveryPublisher.java` | nouvel orchestrateur write/readback/SHA |
| `app/src/main/java/com/example/gpstest/GpxRecoveryWorkflow.java` | nouveau workflow de récupération + publication + acquittement |
| `tests/RecoveryPublicationRegistryTest.java` | nouveau : 20 assertions modèle/registre |
| `tests/GpxRecoveryPublisherTest.java` | nouveau : 36 assertions de panne, reprise, collision et readback |
| `tools/verify_gpx_recovery.py` | adapté au workflow commun |
| `tools/verify_gpx_recovery_publication.py` | nouveau contrôle structurel de transaction SAF |
| `README.md` | état courant R2C.2 et section R1 requalifiée en historique |
| `docs/LOT-8C-R2C2-PUBLICATION-SAF-TRANSACTIONNELLE-2026-11-25.md` | note technique détaillée |
| `docs/DOSSIER-REPRISE-ULTRA-EXHAUSTIF-2026-11-25-MAJ10.md` | présent dossier de passation actualisé |
| `LISEZ-MOI-LOT-8C-R2C2.md` | instructions d'installation |

Aucun nouveau manifeste/permission n'est nécessaire : `Destination.setTreeUri()` persiste déjà les droits READ + WRITE. Aucun changement du writer GNSS ou du format `SessionJournal` n'est requis.

## 38.9 Résultats réellement exécutés

| Contrôle | Résultat |
|---|---|
| `python3 tools/run_jvm_tests.py` | **46 suites, 1 637 assertions, 0 échec** |
| `GpxRecoveryPublisherTest` | **36 assertions** |
| `RecoveryPublicationRegistryTest` | **20 assertions** |
| `python3 tools/verify_gpx_recovery.py` | OK |
| `python3 tools/verify_gpx_recovery_publication.py` | OK |
| `python3 tools/verify_gpx_checkpoint.py` | OK |
| `python3 tools/verify_gpx_async_open.py` | OK |
| `python3 tools/verify_android_project.py` | **17 XML, 89 sources Java**, manifeste/permissions/FGS/SDK ; OK |
| `python3 tools/verify_offline_map_architecture.py` | **107 fichiers**, aucun moteur/provider raster ; OK |
| `python3 -m py_compile tools/*.py` | OK |
| Analyse YAML du workflow | syntaxe valide, jobs `android` et `gpx-xsd` |
| Reconstruction propre : ZIP fichiers-à-remplacer appliqué sur R2C.1 | **réussie**, mêmes 46 suites / 1 637 assertions et audits statiques |

Les tests SAF sont simulés : ils couvrent création, coupure avant sauvegarde URI, écriture partielle, relecture erronée, permission manquante, listing indisponible, collision homonyme simple/multiple, récupération idempotente, changement de dossier et conservation de l'entrée en attente.

L'environnement de préparation est OpenJDK 21, sans `gradle`, `gradlew` versionné ou SDK Android configuré. Par conséquent, `assembleDebug`, Android Lint et les tests instrumentés R2C.2 n'ont pas été exécutés ici. La compilation R2C.1 est confirmée par l'utilisateur ; R2C.2 est à compiler dans Android Studio après remplacement.

## 38.10 Installation de la candidate

Utiliser `GPS-Test-LOT-8C-R2C2-FICHIERS-A-REMPLACER-2026-11-25.zip`, décompresser à la racine du projet local en conservant les chemins, accepter les remplacements, puis lancer Sync, `assembleDebug` et `lintDebug` dans Android Studio. L'archive complète `GPS-Test-LOT-8C-R2C2-PROJET-COMPLET-2026-11-25.zip` sert de copie autonome.

Aucun patch Git n'est requis. Aucun fichier GitHub n'a été modifié par l'assistant. L'utilisateur reste seul à copier les changements dans son dépôt et à faire ses commits.

## 38.11 Campagne Android réel / fournisseurs SAF / ADB — TOUJOURS REPORTÉE

La demande explicite de l'utilisateur est de placer les essais réels téléphone et ADB plus tard. Ne pas les lancer dans ce lot et ne jamais dire qu'ils sont réalisés. La section 37.11 décrit le protocole process-death GNSS/GPX détaillé. Cette campagne sera étendue au nouveau workflow SAF :

1. Sélectionner un dossier local Téléchargements via ACTION_OPEN_DOCUMENT_TREE ; vérifier permission persistée READ + WRITE.
2. Produire une trace test interrompue et récupérer localement ; comparer le digest du fichier privé.
3. Tester la publication réelle via DocumentsUI/MediaProvider ; fermer puis réouvrir le document et comparer les octets.
4. Refaire avec un fournisseur cloud (si disponible) et un support SD/MTP, sans en déduire un support universel.
5. Révoquer les permissions, déconnecter le fournisseur ou le rendre indisponible avant publication ; vérifier que copie privée et registre restent en attente.
6. Simuler une interruption de processus après création, pendant écriture et après vérification ; relancer et vérifier l'absence de doublon/destruction.
7. Pré-créer un fichier homonyme non identique et vérifier qu'il n'est jamais écrasé.
8. Vérifier la liste de plus de 20 000 enfants en environnement isolé si un tel dossier peut être reproduit ; l'app doit refuser la recherche complète plutôt que créer à l'aveugle.

Pré-requis : application R2C.2 compilée/installée, `adb devices -l` avec appareil autorisé, app id confirmé (historique `com.example.gpstest`, à revalider), permissions GNSS/notifications, dossier de test SAF et traces jetables. Les commandes de base de la section 37.11 restent utiles. Pas de test destructif sur les fichiers utilisateur.

Critères de réussite : le local reste source de vérité ; aucune permission fournisseur n'efface la trace privée ; le SAF n'est annoncé publié qu'après readback complet ; une URI transactionnelle connue peut être reprise après écriture partielle ; un homonyme non vérifié n'est jamais écrasé ; le retry est idempotent ; un snapshot journal plus récent n'est jamais acquitté par erreur.

## 38.12 Limites et prochain lot

- Le workflow n'ajoute pas encore de bouton ou écran « Réessayer la publication » ; nouvelle tentative au prochain démarrage de l'application/service.
- Un orphelin SAF créé juste avant crash, dont l'URI n'a pas été sauvegardée, peut devoir être supprimé/résolu manuellement si son contenu diffère ; l'application ne l'écrase pas.
- Les dossiers de plus de 20 000 enfants sont refusés pour garantir une recherche sans doublon.
- Un fournisseur ne permettant pas listing/readback laisse la publication en attente, même si l'écriture initiale a pu réussir.
- La capacité du registre de publication est 64 entrées, sans éviction automatique ; corruption/saturation bloque les actions destructives.
- Les essais sur téléphone réel, fournisseurs SAF réels et ADB sont reportés à plus tard (section 38.11).

Prochain lot logiciel recommandé : **R2C.3 — expérience utilisateur de reprise des publications en attente** (affichage état, cause, dossier, action de retry) avec toutes les opérations sur worker et sans possibilité de purge non confirmée. La campagne réelle/ADB restera ensuite un jalon d'acceptation indispensable avant de prétendre au fonctionnement réel avec des fournisseurs Android spécifiques.

---

**Fin de MAJ10 — LOT 8C-R2C.2.**


---

# 39. LOT 8C-R2C.3 — reprise explicite et persistante des récupérations / publications SAF (MAJ11)

**Date : 25 novembre 2026. Base : R2C.2, dont la compilation Android a été confirmée par l'utilisateur.**

## 39.1 Décision d'exécution

L'utilisateur a expressément indiqué que l'assistant n'a plus à lancer la compilation Android : il s'en occupe et la compilation passe. À partir de R2C.3, ne lancer aucune compilation Android, aucun `assembleDebug`, `lintDebug`, ni tentative de wrapper/SDK dans l'environnement de préparation. Le rôle de l'assistant sur les lots suivants est de modifier les sources dans une copie indépendante, d'exécuter les tests JVM et contrôles statiques, de reconstruire et vérifier les archives de livraison, puis de remettre les fichiers prêts à remplacer. L'utilisateur compile localement. En l'absence de retour d'erreur, considérer la compilation comme OK selon sa convention ; ne pas prétendre l'avoir exécutée soi-même.

GitHub reste en lecture seulement : l'assistant n'est jamais autorisé à modifier le dépôt distant. L'utilisateur reporte lui-même les fichiers, effectue ses commits et gère GitHub. Aucun patch Git ne doit être requis dans la livraison ; fournir une archive « fichiers à remplacer » et, si utile, un projet complet.

## 39.2 Objectif du lot

R2C.2 a mis en place le transfert SAF transactionnel (PREPARED → DOCUMENT_CREATED → VERIFIED, relecture complète et SHA-256). R2C.3 ajoute le déclenchement opérationnel des reprises et une classification explicite des résultats : attente utilisateur, retry transitoire borné, intervention, publié, copie privée et état non acquitté.

La nouvelle implémentation ne remplace ni la FIFO GPX ni les checkpoints R2B.2. Elle réutilise `GpxRecoveryWorkflow.recoverAndPublish()` et `RecoveryPublicationStore` ; aucune opération GNSS ni écriture dans `ioFile` n'est réalisée par ce worker.

## 39.3 WorkManager

Nouveaux fichiers :

- `GpxRecoveryRetryScheduler.java` : `OneTimeWorkRequest` unique `gps-test-gpx-recovery-retry`, `ExistingWorkPolicy.KEEP`, backoff exponentiel initial de 15 secondes.
- `GpxRecoveryRetryWorker.java` : lecture des registres, récupération/publication en série, résumé et compteurs via `WorkData`.
- `GpxRecoveryRetryPolicy.java` : modèle pur de classification et limite de cinq exécutions automatiques.

La dépendance `androidx.work:work-runtime:2.12.0` existait déjà. Aucun nouvel artefact ou permission n'est ajouté pour WorkManager.

Déclenchements : `MainActivity.onStart`, choix/changement du dossier SAF, bouton `RELANCER`, et transition GNSS `userWantsTracking=true → false` lorsqu'elle est observée par l'Activity. Si un worker démarre alors que le suivi est actif, il termine sans toucher aux traces. Dès que l'arrêt du suivi est observé, une tentative est reprogrammée. Si l'Activity était en arrière-plan, le prochain `onStart` s'en charge.

Le worker vérifie l'intention de suivi avant le parcours et avant chaque candidate. Il exécute le workflow de récupération/publication existant sur le thread WorkManager. La publication SAF reste hors du worker GNSS, de `ioFile` et du thread UI.

## 39.4 Politique de retry

Décisions :

- `COMPLETE` : `PUBLISHED` et `LOCAL_ONLY` ;
- `WAIT_FOR_USER` : `PENDING_PERMISSION` ou `NO_PERMISSION` ;
- `RETRY_AUTOMATICALLY` : I/O ou transfert transitoire, et journal restant à acquitter ;
- `NEEDS_INTERVENTION` : `PUBLICATION_CONFLICT`, documents ambigus, source modifiée/invalide, registre SAF illisible, préfixe GPX invalide ou état inconnu.

Maximum : cinq exécutions automatiques par chaîne. La permission manquante ne tourne pas en boucle ; le retour au sélecteur SAF ou au premier plan déclenche un nouveau passage. Les conflits et corruptions sont préservés et signalés. Une transaction SAF orpheline (présente dans `recovery-publications.registry`, sans snapshot de récupération associé) est explicitement signalée comme intervention : le worker n'invente pas l'identité de session manquante et ne la supprime pas.

## 39.5 Diagnostic indépendant du journal et du registre

`SessionStore.readRecoveriesDetailed()` distingue maintenant l'absence d'état, le registre multi-sessions illisible, le journal courant illisible et un échec de synchronisation du snapshot courant dans le registre. En particulier, si `recovery.registry` est corrompu, le journal `session.state` est encore lu indépendamment : la corruption d'un fichier ne fait pas déclarer automatiquement l'autre illisible.

Le worker s'arrête de façon conservatrice si le résultat détaillé porte un message de diagnostic, même lorsque le registre est techniquement lisible mais que la copie du snapshot n'a pas pu être synchronisée. L'UI distingue les cas et ne présente pas une liste vide comme une récupération terminée.

## 39.6 Bandeau UI

`activity_main.xml` reçoit `layoutRecoveryRetry`, un résumé et un bouton `RELANCER`. `MainActivity` observe le `WorkInfo` unique, lit les registres hors thread UI, expose les nombres de sessions encore à récupérer et de transactions SAF, et présente le résultat du dernier passage : publications, copies privées, permissions à rétablir, interventions et notice historique.

Le bouton n'enclenche rien pendant le suivi GNSS. Il est désactivé si les registres nécessaires sont illisibles. Le texte précise si le journal ou le registre est illisible ou si la synchronisation du snapshot a échoué. Les notices d'interruption historiques non destructives sont conservées dans le résumé `WorkData` afin qu'elles ne disparaissent pas quand les compteurs sont nuls.

## 39.7 Fichiers R2C.3 modifiés/ajoutés

- `.github/workflows/android-build.yml` : exécution du nouvel audit structurel.
- `app/src/main/java/com/example/gpstest/GpxRecoveryRetryPolicy.java` (nouveau).
- `app/src/main/java/com/example/gpstest/GpxRecoveryRetryScheduler.java` (nouveau).
- `app/src/main/java/com/example/gpstest/GpxRecoveryRetryWorker.java` (nouveau).
- `app/src/main/java/com/example/gpstest/GpxRecoveryWorkflow.java` : statut explicite de conflit de publication.
- `app/src/main/java/com/example/gpstest/MainActivity.java` : bandeau, bouton, observation WorkManager, retry après arrêt GNSS.
- `app/src/main/java/com/example/gpstest/SessionStore.java` : diagnostics détaillés et lecture indépendante des deux sources.
- `app/src/main/res/layout/activity_main.xml` et `app/src/main/res/values/strings.xml` : UI et chaînes.
- `tests/GpxRecoveryRetryPolicyTest.java` (nouveau), `tests/SessionStoreTest.java` (tests diagnostic étendus).
- `tools/verify_gpx_recovery.py`, `tools/verify_gpx_recovery_publication.py` adaptés ; `tools/verify_gpx_recovery_retry.py` ajouté.
- `tools/rsim/com/example/gpstest/R.java` et `tools/stubs/com/example/gpstest/R.java` régénérés depuis les ressources.
- `README.md`, nouvelle note R2C.3 et ce dossier MAJ11.

## 39.8 Résultats exactement exécutés

| Contrôle | Résultat |
|---|---:|
| `python3 tools/run_jvm_tests.py` | **47 suites, 1 663 assertions, 0 échec** |
| `GpxRecoveryRetryPolicyTest` | 20 assertions |
| `SessionStoreTest` | 101 assertions |
| Audit Android statique | **17 XML, 92 sources Java**, OK |
| Audit cartographie | **110 fichiers**, aucun moteur/provider raster, OK |
| `verify_gpx_async_open.py` | OK |
| `verify_gpx_checkpoint.py` | OK |
| `verify_gpx_recovery.py` | OK |
| `verify_gpx_recovery_publication.py` | OK |
| `verify_gpx_recovery_retry.py` | OK |
| `python3 -m py_compile tools/*.py` | OK |
| Analyse YAML du workflow | OK |

Aucune compilation Android n'a été lancée dans cette préparation, à la demande explicite de l'utilisateur. La compilation R2C.2 est confirmée. La compilation R2C.3 est à effectuer par l'utilisateur après remplacement ; les tests JVM et audits statiques ne doivent pas être décrits comme une compilation Android.

## 39.9 Conservation des copies locales

Aucun nettoyage automatique des copies `_RECUPEREE` n'est introduit. Les fichiers privés restent la copie de sécurité. La politique de rétention (âge, quota, nombre de fichiers, conditions exactes de purge après publication et réversibilité) reste un travail distinct. Avant toute purge, il faudra prouver simultanément la copie locale durable, l'état SAF VERIFIED, l'acquittement du snapshot exact et l'absence de transaction encore ouverte. Aucune purge ne devra jamais supprimer le fichier source interrompu sur la seule base d'un succès d'écriture SAF.

## 39.10 Limites et tâches différées

- Le worker est une tâche unique (`OneTimeWorkRequest`), déclenchée sur les événements listés ; pas de tâche périodique.
- Les tests SAF restent simulés. Aucun fournisseur DocumentsUI/MediaProvider/cloud/SD/MTP réel n'a été essayé.
- L'application n'a pas été lancée sur téléphone pour ce lot.
- La campagne process-death GNSS, fournisseurs SAF réels et ADB est reportée conformément à la décision utilisateur (section 38.11). Elle reste obligatoire avant de conclure à une fiabilité terrain ou à un support universel des fournisseurs SAF.
- Le registre SAF orphelin sans snapshot de récupération est conservé et signalé pour intervention ; le worker ne reconstruit pas un snapshot à partir d'informations insuffisantes.

## 39.11 Installation et reprise suivante

Remplacer les fichiers avec `GPS-Test-LOT-8C-R2C3-FICHIERS-A-REMPLACER-2026-11-25.zip`, à la racine du projet. L'archive complète est un snapshot autonome. Aucun patch Git n'est nécessaire et aucun fichier GitHub n'a été modifié. L'utilisateur réalise la compilation Android locale ; l'assistant n'en lance plus.

Prochaine étape logicielle recommandée : concevoir la politique de rétention/nettoyage des copies de récupération, sans coder de suppression automatique avant validation des invariants. La campagne réelle téléphone/ADB et fournisseurs SAF reste toujours reportée à plus tard.

**Fin MAJ11 — LOT 8C-R2C.3.**


# 40. MAJ12 — LOT 8C-R2C.4 : registre et politique de conservation

## 40.1 Objectif

R2C.4 prépare la conservation contrôlée des copies locales `*_RECUPEREE.gpx` déjà publiées en SAF et dont le snapshot de récupération a été acquitté. Il ne met **aucune suppression automatique** en service. Il ajoute une preuve de provenance durable, calcule une durée minimale et expose un résumé au worker et au bandeau existant.

## 40.2 Invariants impératifs

- Le fichier GPX source interrompu (`SessionJournal.file`) n'est jamais une cible de rétention ou de purge.
- Les copies privées non publiées, les publications SAF non vérifiées, les journaux non acquittés et les transactions en attente ne sont pas inscrits.
- Si le registre est corrompu, plein ou non synchronisable, la copie locale reste conservée sans limite.
- Le seuil de stockage ne déclenche aucune suppression.
- Une échéance de 30 jours ne vaut pas autorisation de suppression : elle autorise seulement un examen futur.
- Les fournisseurs SAF réels ne sont pas encore validés ; aucune action destructive n'est donc active.

## 40.3 Ordre d'inscription exact

Dans `GpxRecoveryWorkflow.recoverAndPublish()` l'entrée de rétention est écrite seulement après :

1. récupération locale validée depuis le checkpoint ;
2. publication SAF intégrale, fermeture, relecture, égalité de longueur et SHA-256 ;
3. retrait de l'entrée `VERIFIED` dans `recovery-publications.registry` ;
4. `SessionStore.clearRecoveryIfUnchanged(context, expected)` réussi sur le snapshot exact ;
5. ajout atomique du record de rétention.

Si le processus est arrêté entre 4 et 5, la copie locale demeure, mais aucune entrée ne l'autorise à être purgée. Si l'écriture de rétention échoue, le workflow reste publié et indique que la copie locale sera conservée sans limite. Ce choix favorise la conservation sur l'économie d'espace.

Les états `LOCAL_ONLY`, `PENDING_PERMISSION`, `PENDING_PUBLICATION`, `PUBLICATION_CONFLICT` et `JOURNAL_NOT_ACKNOWLEDGED` ne sont pas répertoriés.

## 40.4 Durée, quota et capacité

| Paramètre | Valeur R2C.4 | Comportement |
|---|---:|---|
| Conservation minimale | 30 jours | Avant l'échéance, conservation obligatoire. |
| Seuil indicatif | 512 Mio | Avertissement uniquement ; jamais de purge. |
| Capacité registre | 128 entrées | Aucune éviction ; en cas de saturation, copie conservée sans limite. |

Le timestamp `publishedAtMs` est enregistré après la publication vérifiée et l'acquittement du journal. Il ne dépend pas de `File.lastModified()`. Une horloge en recul retient la copie (`CLOCK_MOVED_BACKWARDS`) ; un calcul d'échéance qui pourrait déborder `long` est refusé.

Le quota ne représente que les copies suivies par le registre de rétention. Il n'inclut pas les copies locales non publiées, les anciennes copies non inscrites ou les autres GPX normaux. Les seuils sont des valeurs techniques de cette version, pas des préférences configurables.

## 40.5 Nouveaux composants

- `RecoveryRetentionRegistry.java` : modèle pur, format `retentionFormat=1`, parsing strict UTF-8/hex, 128 entrées et identité immuable ; une entrée exige une copie `_RECUPEREE` distincte de la source.
- `RecoveryRetentionStore.java` : persistance privée via `AtomicFile`, `flush()` puis `FileDescriptor.sync()`, conservation de tout registre illisible.
- `RecoveryRetentionPolicy.java` : 30 jours, horloge, échéance pour examen manuel et seuil indicatif de stockage ; `automaticDeletionEnabled()` retourne toujours `false`.
- `GpxRecoveryWorkflow.java` : inscription post-publication et post-acquittement uniquement ; en cas d'échec du registre, la copie locale reste conservée sans limite.
- `GpxRecoveryRetryWorker.java` : résumé du nombre de copies suivies, copies encore sous durée minimale, copies arrivées à échéance, volume et avertissement de quota ; pas de tâche périodique ni de purge.
- `MainActivity.java` : le bandeau reste visible quand le dernier résumé contient une information de conservation.

## 40.6 Tests et contrôles effectivement exécutés

| Contrôle | Résultat |
|---|---:|
| `python3 tools/run_jvm_tests.py` | **50 suites / 1 715 assertions / 0 échec** |
| `RecoveryRetentionRegistryTest` | 21 assertions |
| `RecoveryRetentionPolicyTest` | 22 assertions |
| `RecoveryRetentionStoreTest` | 9 assertions |
| Audit Android statique | **17 XML / 95 sources Java**, OK |
| Audit cartographique | **113 fichiers**, aucun moteur/provider raster, OK |
| Audit ouverture asynchrone | OK |
| Audit checkpoint durable | OK |
| Audit récupération GPX | OK |
| Audit publication SAF | OK |
| Audit retry WorkManager | OK |
| Audit rétention | OK |
| `python3 -m py_compile tools/*.py` | OK |
| Analyse YAML workflow | OK |

`RecoveryRetentionStoreTest` utilise les stubs JVM d'`AtomicFile` et un dossier temporaire ; il vérifie l'écriture/lecture, l'idempotence, le refus d'écraser une identité différente et la préservation d'un registre corrompu. Aucun test ne supprime de fichier utilisateur.

**Aucune compilation Android n'a été lancée** dans cette préparation. R2C.3 est compilé sans erreur selon l'utilisateur ; R2C.4 doit être compilé par lui après remplacement.

## 40.7 Limites connues

- Aucune suppression de fichier ou worker de purge n'est introduit.
- Les copies non publiées ou non enregistrées restent conservées sans limite et hors du quota suivi.
- Le statut « arrivée à échéance » est un compte dans le résumé ; la liste détaillée et la confirmation par fichier n'existent pas encore.
- Le SAF n'est pas relu à chaque résumé. Une future purge devra revalider le document et son SHA-256 au moment exact de l'action.
- L'absence de registre de rétention ne doit jamais être interprétée comme l'absence de fichiers locaux.
- Les essais téléphone réel, ADB, DocumentsUI, MediaProvider, cloud, MTP et carte SD restent reportés conformément à la consigne utilisateur.

## 40.8 Prochaine étape recommandée — R2C.5

Créer un écran de revue manuelle des copies suivies : nom, date de publication, échéance, taille, destination et état. Une future suppression ne pourra être proposée qu'après 30 jours et après vérification : suivi GNSS arrêté ; `recovery.registry`, `recovery-publications.registry`, `recovery-retention.registry` et `session.state` lisibles ; aucune transaction ni session ouverte pour l'identité ; chemin canonique confiné à `getExternalFilesDir(null)` ; longueur et SHA-256 locaux exacts ; permission SAF actuelle ; relecture complète du document externe avec longueur/SHA-256 identiques ; confirmation explicite de l'utilisateur.

La première purge future devra supprimer uniquement la copie locale `_RECUPEREE`, jamais le GPX source interrompu ni la destination SAF. Elle devra inclure un état transactionnel (`DELETE_PENDING`) pour reprendre après un crash entre l'intention, la suppression locale et le retrait du registre. L'automatisation, si elle est un jour souhaitée, nécessitera une activation explicite et une campagne réelle préalable.

La campagne téléphone réel / ADB / fournisseurs SAF reste un jalon distinct reporté à plus tard.

## 40.9 Installation et reprise suivante

Base de travail R2C.3 : projet complet `GPS-Test-LOT-8C-R2C3-PROJET-COMPLET-2026-11-25.zip`, SHA-256 `dd88dc5b2b02ccf20ebc4c1f6af63bd12e237debd743ce02815687b8df3c3aef`. R2C.4 est une copie dérivée. Pour le dépôt local, utiliser `GPS-Test-LOT-8C-R2C4-FICHIERS-A-REMPLACER-2026-11-25.zip` à la racine du projet ; pour ouvrir un snapshot, utiliser l'archive projet complet. Aucun patch Git n'est nécessaire. Aucun fichier GitHub n'a été modifié.

L'utilisateur effectue la compilation Android locale. L'assistant ne lance plus de compilation Android.

**Fin MAJ12 — LOT 8C-R2C.4.**

# 41. MAJ13 — LOT 8C-R2C.5 : écran consultatif de gestion des copies récupérées

## 41.1 Objectif et accès

R2C.5 ajoute une interface de consultation du registre `recovery-retention.registry`, sans modifier la chaîne GNSS/GPX et sans action destructive. L'écran est accessible dans **Réglages → Données enregistrées → GÉRER LES TRACES RÉCUPÉRÉES**.

`RecoveryManagementActivity` est déclarée `exported=false` dans le manifeste et possède `SettingsActivity` comme parent. Elle utilise `ThemeManager.applyTo()` avant `setContentView()`, puis `EdgeToEdge.apply()` conformément aux écrans existants.

## 41.2 Informations affichées

Chaque entrée montre :

- `displayName` : nom du document publié ;
- `recoveredName` : copie locale `_RECUPEREE.gpx` ;
- `sourceName` : fichier source interrompu conservé ;
- `sessionId` et `segmentGeneration` ;
- `publishedAtMs` formaté dans le fuseau local, ancienneté en jours ;
- échéance de revue (publication + 30 jours) et nombre de jours restants ;
- taille `recoveredLengthBytes` ;
- état de la copie locale ;
- existence actuelle de la permission SAF persistante sur `treeUri` ;
- indication si le dossier enregistré correspond toujours au dossier actuellement sélectionné ;
- SHA-256 enregistré, URI arbre, URI document et publication ID.

Les URI et empreintes sont sélectionnables/copiables. Les lignes sont triées par publication la plus récente ; l'égalité est départagée par `publicationId`, donc l'ordre reste déterministe.

## 41.3 Contrôles non destructifs de l'état local

`RecoveryRetentionPresentation.buildRows()` est un modèle pur. Pour chaque `recoveredName`, il vérifie :

1. que `getExternalFilesDir(null)` est disponible ;
2. que le chemin canonique du candidat reste directement enfant du dossier des traces ;
3. que le chemin existe et désigne un fichier ordinaire ;
4. que le fichier est lisible ;
5. que sa longueur correspond à `recoveredLengthBytes`.

Le résultat distingue `PRESENT_LENGTH_MATCHES`, `MISSING`, `NOT_A_REGULAR_FILE`, `NOT_READABLE`, `LENGTH_MISMATCH`, `OUTSIDE_RECOVERY_DIRECTORY`, `DIRECTORY_UNAVAILABLE` et `CHECK_FAILED`. Une anomalie ne retire jamais l'entrée du registre et ne lance aucune réparation.

## 41.4 Vérification SAF : distinction historique / actuelle

Le registre R2C.4 est inscrit après une publication SAF relue intégralement et vérifiée par égalité de longueur et SHA-256. L'écran le présente comme **« vérifiée à la publication »**. Il vérifie actuellement que la permission SAF persistante existe ou non, mais il ne relit pas le document SAF à chaque ouverture. Il ne recalcule pas non plus le SHA-256 local pendant le rendu ; le digest conservé est affiché avec un avertissement explicite.

Ce choix évite les accès réseau/cloud implicites et les lectures longues lors de l'ouverture d'un écran de gestion. Une vérification en direct, entrée par entrée, devra être une action explicite future exécutée sur worker.

## 41.5 Architecture / fichiers

- `RecoveryManagementActivity.java` : écran, lecture asynchrone du store, rendu, actualisation manuelle et `onResume()` ; executor sériel dédié à l'Activity.
- `RecoveryRetentionPresentation.java` : modèle Java pur, calcul d'échéance, jours restants/âge, tri stable et état local.
- `activity_recovery_management.xml` : layout d'écran, barre actions, résumé, état de chargement/erreur/vide et conteneur des entrées.
- `SettingsActivity.java` : nouveau bouton de navigation.
- `activity_settings.xml` : bouton ajouté au bloc Données enregistrées après le partage de la dernière trace.
- `AndroidManifest.xml` : Activity non exportée et parent hiérarchique.
- `strings.xml` : textes français, pluriel, messages d'erreur, dates et descriptions d'état.
- `tests/RecoveryRetentionPresentationTest.java` : tests purs du modèle.
- `tools/verify_gpx_recovery_management.py` : garde structurelle et non destructive, appelée par CI.
- `.github/workflows/android-build.yml` : étape `Verify GPX recovery management screen`.

## 41.6 Invariants de sécurité

1. Aucune suppression de fichier, aucune suppression d'URI SAF et aucun retrait d'entrée du registre.
2. Aucune purge automatique : `RecoveryRetentionPolicy.automaticDeletionEnabled()` reste `false`.
3. Le GPX source interrompu reste hors du traitement de l'écran.
4. Un registre illisible donne un diagnostic ; l'écran n'affiche pas une liste vide de remplacement.
5. La lecture du registre, la vérification des fichiers et les permissions SAF sont examinées hors du thread UI.
6. Les chemins locaux doivent rester confinés par chemin canonique.
7. Le statut de publication SAF est temporellement qualifié (« vérifiée à la publication »), jamais prétendu actuel.
8. Permission SAF absente, document historique, fichier absent ou longueur divergente ne lancent aucune action destructive.

## 41.7 Tests et audits réellement exécutés

| Contrôle | Résultat |
|---|---:|
| `python3 tools/run_jvm_tests.py` | **51 suites / 1 740 assertions / 0 échec** |
| `RecoveryRetentionPresentationTest` | **25 assertions** |
| `verify_android_project.py` | **18 XML / 97 sources Java**, OK |
| `verify_offline_map_architecture.py` | **116 fichiers**, aucun moteur/provider raster, OK |
| `verify_gpx_async_open.py` | OK |
| `verify_gpx_checkpoint.py` | OK |
| `verify_gpx_recovery.py` | OK |
| `verify_gpx_recovery_publication.py` | OK |
| `verify_gpx_recovery_retry.py` | OK |
| `verify_gpx_recovery_retention.py` | OK |
| `verify_gpx_recovery_management.py` | OK |
| `python3 -m py_compile tools/*.py` | OK |

Aucune compilation Android n'a été lancée par l'assistant, conformément à la consigne. R2C.4 est confirmé compilé par l'utilisateur ; R2C.5 doit l'être par l'utilisateur après remplacement. Aucun fichier du dépôt GitHub n'a été modifié.

## 41.8 Limites et tâches futures

- L'écran n'effectue pas de lecture SAF complète à l'ouverture. Une future action individuelle pourra relire documentUri et comparer longueur/SHA-256.
- Le SHA-256 local n'est pas recalculé à chaque rendu. Seule la longueur et la sécurité du chemin sont contrôlées à l'affichage.
- Aucun partage, lancement de trace ou suppression n'est proposé dans R2C.5.
- Les entrées dont la copie locale est absente ou incohérente restent dans le registre pour diagnostic.
- La campagne téléphone réel/ADB/fournisseurs SAF reste explicitement reportée à plus tard.

## 41.9 Prochaine étape recommandée — R2C.6

Proposer une vérification manuelle à la demande pour une entrée : contrôler la présence et la longueur locale, recalculer le SHA-256 local, vérifier la permission SAF persistante, relire le document externe complet et comparer longueur + SHA-256. Résultats à distinguer : conforme ; copie locale divergente ; document SAF divergent ; permission absente ; fournisseur indisponible ; métadonnées incohérentes.

R2C.6 ne doit pas encore supprimer de données. Une éventuelle suppression manuelle locale reste un lot séparé, après vérification en direct et campagne réelle sur les fournisseurs SAF.

## 41.10 Installation et base exacte

Base immédiate : archive projet complet R2C.4 `GPS-Test-LOT-8C-R2C4-PROJET-COMPLET-2026-11-25.zip`, SHA-256 `2ba488db6e4afa19ab6d4a51b9fd94c958e04f7f81069ee97235947d510b713b`. Pour mettre à jour le dépôt local, utiliser l'archive R2C.5 « fichiers à remplacer » à la racine du projet. Il n'y a pas de patch Git à appliquer. Aucun fichier GitHub n'a été modifié.

L'utilisateur effectue la compilation Android locale. L'assistant ne lance plus de compilation Android.

**Fin MAJ13 — LOT 8C-R2C.5.**
