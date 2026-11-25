# GPS Test — Java + XML, sans Compose

> **Version à harmoniser avant diffusion :** le numéro `v5.14` présent dans l'historique du README n'est pas cohérent avec `versionName = "1.0"` et `versionCode = 1` dans `app/build.gradle`. Ne pas interpréter le numéro historique du README comme la version installable actuelle.

Application de test GNSS complète : position, satellites multi-constellations **avec bandes L1/L5**, DOP, datum, boussole, carte du ciel, trace GPX, et une page expert pour les mesures brutes.

**Écrite entièrement en Java**, interface en **XML** — volontairement, pour l'apprentissage.

> **État courant — 25 novembre 2026 (LOT 8C-R2C.4, candidate préparée) :** R2B.1 ouvre les segments GPX sur la FIFO dédiée ; R2B.2 synchronise des checkpoints durables après `flush + FileDescriptor.sync()` ; R2B.3/R2B.4 récupèrent le dernier préfixe sûr dans une copie distincte sans modifier l’original ; R2C.1 conserve plusieurs sessions/segments dans `recovery.registry` ; R2C.2 publie les copies récupérées via une transaction SAF relue et vérifiée (taille + SHA-256) ; R2C.3 ajoute une reprise WorkManager persistante, bornée à cinq tentatives automatiques, une relance utilisateur et un bandeau de diagnostic ; R2C.4 enregistre séparément les copies locales déjà publiées et acquittées, avec une conservation minimale de 30 jours et un seuil indicatif de 512 Mio. **Aucune suppression automatique n'est activée** : les copies privées non publiées et les sources interrompues restent hors registre de rétention. La candidate passe **50 suites JVM / 1 715 assertions, 0 échec**, ainsi que les audits statiques Android (17 XML, 95 Java), cartographie (113 fichiers), ouverture, checkpoint, récupération, publication SAF, retry et rétention. Aucune compilation Android n’est lancée par l’assistant : l’utilisateur la réalise localement ; R2C.3 est confirmé compilé, R2C.4 est à compiler après remplacement. Les essais sur téléphone, ADB et fournisseurs SAF réels restent explicitement reportés (dossier MAJ12). Aucun fichier GitHub n’a été modifié par l’assistant.

> Les mentions de `osmdroid` et Mapnik dans l'historique des premières étapes ci-dessous sont historiques. Depuis le LOT 7G, aucun moteur raster/osmdroid n'est présent dans le code Android actif ; voir la section d'état réel et `docs/LOT_7G_CARTOGRAPHIE_LOCALE_ET_PIPELINE_GNSS.md`.

---

## 1. État du projet — état courant et repères historiques (transparence)

### Référence historique — 24 novembre 2026 (LOT 8B, antérieure à R2A/R2B/R2C)

| Vérification | Résultat / niveau de preuve |
|---|---|
| Suites JVM | **38 / 38**, relancées sur l'archive source consolidée |
| Assertions JVM | **1 289 réussies, 0 échec** après fiabilisation du test de barrière FIFO dans `GnssEventPipelineTest` |
| XML du manifeste et des ressources | **17 analysés, 0 erreur** par le contrôle statique fourni |
| Contrat hors ligne (sources actives + Gradle) | **98 fichiers analysés, aucun moteur de tuiles raster/osmdroid** détecté |
| Sources Java Android auditées | **80** |
| Génération `R.java` | **162 IDs, 213 chaînes, 7 layouts**, génération sans erreur |
| Compatibilité callbacks GNSS | Contrôles `LocationListener` API 24–29 et jetons de génération présents |
| Compilation Android | `compileDebugJavaWithJavac` et `assembleDebug` **réussis dans les journaux GitHub Actions communiqués** |
| Android Lint | Après corrections API 31 et API 33, **succès rapporté par l'utilisateur** ; numéro du run, SHA et rapport Lint final restent à archiver |
| Validation sur appareil | **Non réalisée / non documentée ici** ; ne pas assimiler une CI verte à un essai GNSS réel |

Ce tableau et le paragraphe ci-dessus décrivent un état historique du LOT 8B, et non la candidate R2C.3. Pour l’état opérationnel courant, consulter le bloc d’état au début de ce README et le dossier MAJ12. À la date du LOT 8B, les 38 suites JVM étaient compilées ensemble puis exécutées individuellement dans des processus séparés. Le test du pipeline GNSS utilise maintenant une barrière FIFO : le compteur `completed` est lu depuis un événement placé après les callbacks à vérifier, et non immédiatement après un signal émis à l'intérieur du callback. Ces tests vérifient les parties Java autonomes ; ils **ne remplacent pas** une compilation Android complète, un émulateur ou un essai GNSS/cartographique sur téléphone.

### Cartographie locale — lots 7A à 7G

- Moteur Mapsforge pour les cartes régionales vectorielles `.map` installées localement ; fusion des régions via `MultiMapDataStore`.
- En l'absence de carte installée ou si Mapsforge échoue, la carte principale utilise un canevas géographique local qui conserve position et trace GPX, sans basculer vers un fond en ligne. Ce canevas **n'est pas un fond cartographique** : il indique explicitement qu'aucune région n'est installée.
- Le sélecteur régional dessine les contours `.poly` sur une grille locale. Aucun moteur de tuiles raster, cache Mapnik ou osmdroid n'est embarqué dans le code actif.
- Catalogue hiérarchique, téléchargement individuel des fichiers `.map`, progression, annulation, mise à jour et suppression. Le catalogue et les contours `.poly` peuvent nécessiter une connexion lors de leur première consultation ; leurs réponses sont mises en cache localement.
- Thèmes Mapsforge Standard, Vélo/Randonnée, Moto et OSM classique ; orientation Nord en haut / Cap en haut.
- Simplification de l'affichage des longues traces GPX sans modification du fichier source.
- Attribution OpenStreetMap visible. Aucun téléchargement de tuiles depuis le serveur public OSM.

Le relief ombré n'est pas proposé faute de données DEM associées. La validation avec de vrais fichiers régionaux et sur téléphone reste à faire. Voir `docs/LOT_7G_CARTOGRAPHIE_LOCALE_ET_PIPELINE_GNSS.md`.

### Préparer la compilation Android (LOT 8A / 8B)

La dernière vérification et mise à niveau des versions (24/11/2026) est documentée dans `docs/LOT_8B_MISE_A_JOUR_OUTILLAGE_ANDROID_2026-11-24.md` et le correctif compile SDK 37.2 dans `docs/LOT_8B_COMPILESDK_37_2.md`.

La configuration du projet est épinglée sur **AGP 9.5.2 / Gradle 9.9.2 / JDK 17+ / SDK Platform 37.2 / Build Tools 36.0.0**. `compileSdk 37` + `compileSdkMinor 2` répond aux métadonnées AAR ; `targetSdk 36` et `minSdk 24` restent inchangés.

Les fins de ligne sont normalisées en LF par `.gitattributes` et `.editorconfig`, y compris lors des extractions Windows. Les fichiers binaires (cartes `.map`, APK, archives, images et clés) sont exclus de toute conversion.

Sur une machine Android :

```bash
sdkmanager "platforms;android-37.2" "build-tools;36.0.0" "platform-tools"
bash tools/check_android_build_environment.sh
```

La CI Android de `.github/workflows/android-build.yml` a déjà été exécutée : les journaux communiqués montrent une compilation Java Android et la génération d'un APK Debug réussies. Après les corrections de compatibilité API 31 et API 33, le succès de Lint a été rapporté par l'utilisateur. Le run final (numéro, SHA de commit et rapports post-correctifs) doit encore être archivé dans la documentation du dépôt.

Le Gradle Wrapper n'est pas encore versionné dans cette archive. Pour supprimer la dépendance à une installation Gradle locale, le workflow génère désormais un Wrapper Gradle 9.9.2 épinglé, vérifie son lancement, puis publie temporairement les quatre fichiers dans l'artefact `gradle-wrapper-bootstrap`. Après le premier run de ce workflow, télécharger cet artefact, placer `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` et `gradle/wrapper/gradle-wrapper.properties` à la racine du dépôt, puis les committer. Une fois le Wrapper versionné, le bootstrap de génération pourra être retiré et la CI utilisera directement `./gradlew`.

### Historique — LOT 8C-R1 (journal de session durable, fondation achevée)

Une première étape de durcissement est préparée dans `docs/LOT_8C_R1_JOURNAL_DURABLE.md` : journal interne privé, écriture `AtomicFile`, migration legacy, UUID de session, lecture non destructive, acquittement durable de la notice, quarantaine d'un journal illisible et limites de taille/noms de fichier.

- Validation locale du snapshot R1 : **39 suites JVM / 1 345 assertions / 0 échec**.
- Contrôle statique : **17 XML / 80 sources Java**, OK ; contrôle cartographique hors ligne : **98 fichiers**, OK ; workflow YAML valide.
- **Ce snapshot R1 n'a pas encore été compilé contre le SDK Android ni exécuté sur GitHub Actions.** Ne pas confondre ces résultats JVM avec une validation Android.
- Le checkpoint GPX réel et la réparation du XML après crash ne sont pas encore implémentés : ils sont prévus dans le LOT 8C-R2. Les tâches persistantes seront traitées en R3.

Ce relevé décrit l'état historique du lot R1. Il est dépassé par les lots R2A à R2C.2 ; pour l'état opérationnel, les livraisons, les résultats exacts et les tâches à venir, consulter le dossier de reprise MAJ10. La campagne réelle sur appareil/ADB y est toujours notée comme reportée, et non réalisée.

### ⚠️ À valider avant diffusion

Un APK **Debug** a été généré par GitHub Actions, mais cela ne valide ni son comportement sur téléphone ni une version **Release**. Les essais GNSS prolongés, les vrais fichiers cartographiques, la signature Release et les validations de confidentialité restent à faire. Détails : `docs/LOT_8A_AUDIT_ANDROID.md`, `docs/LOT_8B_PREMIERE_CORRECTION_COMPILATION.md` et `docs/LOT_8B_F_CLOTURE.md`.

---

## 1bis. v3 — ce qui a été ajouté

### Double fréquence : VRAIMENT deux chemins

La méthode `GnssCapabilities.hasCarrierFrequency` **n'existe pas** dans le SDK Android. J'ai vérifié la documentation : la seule API officielle est `getGnssSignalTypes()`, disponible **à partir d'Android 14 (API 34)**. En dessous, il n'y a aucun moyen officiel de savoir — d'où deux chemins :

| Chemin | API | Ce que ça donne |
|---|---|---|
| **1. Capacités du chipset** | 34+ | Réponse **officielle** : la liste exacte des couples constellation/fréquence supportés |
| **2. Observation des satellites** | toutes | Inférence : si les `GnssStatus` remontent du 1176,45 MHz, il y a du L5 |
| **Aucun des deux** | — | L'app affiche honnêtement « non rapportées par l'appareil » au lieu d'inventer |

L'interface montre une ligne explicite : **« Bandes : L1 + L5 (double fréquence) »** ou **« bande unique »** ou **« non rapportées »**. Plus les compteurs en direct : **« L1 14 · L5 4 »**.

### Liste des satellites : triée et filtrable

Comme dans l'application originale, avec un sélecteur :

**Tous · Utilisés · GPS · GLONASS · Galileo · BeiDou · QZSS · IRNSS · SBAS**

Le tri par défaut applique votre demande exacte :
1. **Les satellites utilisés dans le fix en premier** (marqués ●)
2. Puis SNR décroissant (le meilleur signal en tête)
3. Puis constellation et numéro (ordre stable)

Les non-utilisés sont atténués à 50 % d'opacité. Un résumé par constellation s'affiche : **« GPS 6/9 · GALILEO 4/5 · GLONASS 3/7 »** (utilisés/vus).

### Le DOP : et vous aviez raison, je l'ai corrigé

Vous avez dit « je ne vois pas de téléphone qui ne puisse pas le donner via NMEA ». **Vous avez raison, et j'ai vérifié pourquoi j'avais tort** : le DOP du GSA est émis par le récepteur GNSS lui-même, indépendamment d'Android. Et j'ai trouvé mieux : **le DOP se calcule aussi par géométrie**, sans aucune dépendance au récepteur.

L'app affiche donc **deux DOP côte à côte** :

| | Source | Disponibilité |
|---|---|---|
| **DOP récepteur** | trames NMEA GSA | dépend du chipset |
| **DOP calculé** | géométrie azimut/élévation des satellites | **toujours possible** (≥ 4 satellites) |

**Le calcul** (`DopCalculator.java`), c'est de l'algèbre linéaire :

```
Pour chaque satellite, vecteur unitaire dans le repère local :
    E = cos(el)·sin(az)   N = cos(el)·cos(az)   U = sin(el)

Matrice de conception G (4 colonnes : E, N, U, biais d'horloge)
     Q = (Gᵀ·G)⁻¹

HDOP = √(Q11+Q22)   VDOP = √Q33   PDOP = √(Q11+Q22+Q33)
TDOP = √Q44         GDOP = √(Q11+Q22+Q33+Q44)
```

**Validé contre calcul à la main** : 4 satellites (1 zénith + 3 à 120°, élévation 5°) → HDOP = 1,16. Ma dérivation manuelle donne Q11 = 1/1,488, donc HDOP = √1,344 ≈ **1,16**. Le code et la main concordent.

Et une ligne de **comparaison** : « récepteur 1,4 · calculé 1,2 (cohérents) ». Si l'écart dépasse un facteur 1,6, l'app signale « calculé + optimiste » ou « + pessimiste » — signe que le récepteur utilise un jeu de satellites différent du vôtre.

### Détail qui compte : un satellite à 0° d'élévation est exclu

Le calcul ignore les satellites à l'horizon exact (élévation ≤ 0) et sous l'horizon : signal inexploitable, erreur atmosphérique maximale, et ils dégradent artificiellement le DOP. C'est ce qui a fait échouer mon premier test — le code avait raison.

---

---

## 1ter. Deuxième revue de code — ce qui a été trouvé et corrigé

Une seconde passe, plus agressive, en regardant ce que la première n'avait pas vu : **les niveaux d'API méthode par méthode** et **le code mort**.

### 🔴 Bug à l'exécution (le plus grave des deux revues)

**`GnssStatus.hasCarrierFrequencyHz(i)` est API 26, mais `minSdk 24`.**

Cela **compile sans erreur** — le compilateur ne vérifie pas la disponibilité des méthodes. Mais sur Android 7.0/7.1 (API 24-25), l'app se serait **plantée en `NoSuchMethodError` au démarrage du suivi GPS**, c'est-à-dire précisément quand l'utilisateur appuie sur DÉMARRER. Corrigé par un garde `VERSION_CODES.O`.

C'est le type de bug le plus vicieux : invisible à la compilation, invisible dans les tests unitaires, et fatal à l'exécution sur du matériel ancien.

### 🟠 Bugs fonctionnels

| # | Problème | Conséquence |
|---|---|---|
| 2 | Libellé GPX faux pendant l'enregistrement | `String.format("%d points — %s", ...)` sans second argument → affichage cassé dès le 1er point |
| 3 | `Spinner.setSelection()` jamais appelé | le listener peut ne pas se déclencher → `filterMode` restait `null` |
| 4 | Pas de garde de nullité sur `filterMode` | `SatelliteFilter.apply(list, null)` → liste vide, tous les satellites disparaissaient |
| 5 | `TextView` multilignes sans `setMovementMethod` | `android:scrollbars="vertical"` ne sert à rien : **les trames NMEA ne défilaient pas au doigt** |

### 🟡 Améliorations réelles (pas des corrections)

**⑥ Écriture GPX incrémentale.** Avant, les points s'accumulaient en mémoire et le fichier n'était écrit qu'à l'arrêt. **Si l'app était tuée — ou le téléphone s'éteignait — après 3 heures de marche, tout était perdu.** Désormais : entête écrite à l'ouverture, puis chaque point ajouté et `flush()` immédiatement. Une trace reste exploitable même après un crash.

**⑦ Modules écrits mais jamais branchés** — `Utm`, `Astro`, `RMC`, `ZDA` étaient du **code mort**. Intégrés à l'interface :

| Module | Où |
|---|---|
| **UTM** | ligne dans la section Position (`31U 448252 E 5411955 N`) |
| **Heure GPS (ZDA)** | l'horloge la plus fiable disponible sur Terre |
| **Vitesse NMEA (RMC)** | recoupe la vitesse du `LocationListener` — deux sources indépendantes |
| **Soleil / Lune (Astro)** | lever/coucher locale + phase et % d'illumination |

**⑧ CSV expert en flux** — l'ancien tampon mémoire a été remplacé par un worker disque dédié et une file bornée à 4 096 lignes en attente. Le flush périodique fonctionne même sans nouveau callback GNSS ; les lignes acceptées, écrites et perdues sont comptées séparément.

**⑨ Correction de commentaires faux** — j'avais écrit « `hasCarrierFrequencyHz` est API 30 » pour `GnssMeasurement`. **C'est API 24.** J'avais confondu avec la variante `GnssStatus` (API 26). Le garde inutile a été retiré.

### ⚠️ Ce que cette revue démontre

Les deux revues ont trouvé des choses **que mes 148 tests ne pouvaient pas voir** :
- la revue 1 a trouvé le GPX non conforme au schéma (mes tests vérifiaient la *présence* des balises, pas leur *validité*)
- la revue 2 a trouvé un bug d'API invisible à la compilation

C'est la limite structurelle des tests : **ils vérifient ce qu'on a pensé à leur demander.** La relecture humaine reste irremplaçable — et c'est exactement pour ça que vous avez eu raison d'insister.

### Ce que mon vérificateur automatique signale encore (et pourquoi c'est faux)

Deux alertes restent, toutes deux **faux positifs** :

| Alerte | Pourquoi c'est faux |
|---|---|
| `getGnssCapabilities` API 30 « sans garde » | protégé par un **early return** : `if (SDK_INT < R) { …; return; }` — le script ne lit que 6 lignes en arrière |
| `hasCarrierFrequencyHz` API 26 « sans garde » | il s'agit de `GnssMeasurement` (API **24**, minSdk = 24) et non de `GnssStatus` (API 26) |

---

## 1quater. v4 et v5 — cycle de vie, service, et politique d'écran éteint

### v4 : le service (rappel)

`TrackingService` (Foreground Service) porte le GPS, le NMEA, le GPX et la boussole.
`MainActivity` est devenue une **pure vue** : elle lit `TrackingState` à chaque tick
de 500 ms et n'abonne plus aucun capteur. Détail complet dans `docs/CYCLE_DE_VIE.md`.

### v5 : ce que la question « ne faut-il pas sauvegarder quand on quitte ? » a produit

La réponse courte : **non, pas « quand on quitte » — à chaque transition, en continu.**
Le détail pédagogique est dans `docs/CYCLE_DE_VIE.md` §9-§11. Ce qui a changé dans le code :

| Ajout | Fichier | Pourquoi |
|---|---|---|
| 3 politiques d'écran éteint, persistées | `BackgroundPolicy.java` (pur) + `Prefs.java` | choix **utilisateur**, pas développeur |
| Écran Réglages | `SettingsActivity.java` + `activity_settings.xml` | le réglage existe vraiment dans l'app (en v4, la version GPX était implémentée mais jamais appelée) |
| Récepteur `ACTION_SCREEN_ON/OFF` dans le **service** | `TrackingService` | un broadcast n'arrive dans une Activity que quand l'app est visible : inutile ici |
| Séparation abonnement / session dans le service | `subscribeSensors()` / `unsubscribeSensors()` | permet de suspendre et reprendre **sans** perdre la session |
| Segmentation GPX en mode économie | `openRecordingSegment()` / `closeRecordingSegment()` | un trou dans le temps = un nouveau fichier, pas un fichier qui ment |
| Journal de session `session.state` | `persistSession()` / `checkPreviousSession()` | seule information qui survit à un `kill -9` |
| `sessionNotice` séparé de `gpxError` | `TrackingState` | une notice d'historique n'est pas une erreur d'écriture |
| Suppression de la liste de points en mémoire | `TrackingService` | fuite mémoire silencieuse corrigée : le fichier **est** la vérité |
| Action Pause/Reprendre dans la notification | `TrackingService` (API 31+) | geste attendu, sans rouvrir l'app |

### Les trois modes, en une ligne chacun

| Mode | Écran éteint | Batterie | Trace |
|---|---|---|---|
| **Continuer** (défaut) | le GPS continue | élevé | intacte |
| **Garder l'écran allumé** | l'écran reste allumé | **le plus élevé** | intacte |
| **Pause à l'extinction** | GPS désabonné, notification « en pause » | faible | **trou assumé**, fichiers `-p1`, `-p2`… |

### Une correction que cette passe a révélée dans la v4

En v4, `GpxWriter.Version` existait et les tests la couvraient — mais **le service
appelait toujours `header(name, time)` et `pointXml(p)`**, c'est-à-dire les
surcharges par défaut (1.1). Autrement dit : **le réglage GPX 1.0/1.1 n'était
atteignable depuis aucune interface**. C'est corrigé (Réglages + `Prefs.readGpxVersion`)
et le nom de fichier porte bien le suffixe `-v10`. Leçon : un paramètre qu'aucun
appelant ne lit n'existe pas ; les tests unitaires ne l'auraient jamais vu.

### État vérifiable

- **21 sources Java** (+ 4 fichiers de tests), ~4 150 lignes d'app
- **190 tests** : Utils 26 · Advanced 81 · DopFilter 52 · **BackgroundPolicy 31**
- 66 IDs Java ⇄ 66 IDs XML (aucun orphelin), 0 string manquante
- `SettingsActivity` déclarée au manifest, action Pause/Reprendre gardée par l'API 31

---

## 1quinquies. v5.1 — audit du Foreground Service (11 défauts corrigés)

Relecture ligne par ligne du service, avec analyse AST et simulation d'états.
Détail complet dans **`docs/FOREGROUND_SERVICE.md`**. Résumé :

| # | Défaut | Gravité | Effet réel |
|---|---|---|---|
| 1 | `onDestroy` effaçait le journal dans tous les cas | 🔴 | la notice « session interrompue » ne pouvait **jamais** s'afficher |
| 2 | `startForeground()` non protégé | 🔴 | crash si permission retirée pendant la session (API 34) |
| 3 | notification orpheline si le démarrage échoue | 🟠 | notification fantôme jusqu'au reboot |
| 4 | notification figée sans fix GPS | 🟠 | « 0 satellites » pendant des heures à l'intérieur |
| 5 | écriture GPX sur le thread principal | 🟠 | ANR sur stockage lent |
| 6 | pied GPX avant les derniers points | 🟠 | fichier corrompu (conséquence de 5) |
| 7 | `smallIcon` = icône d'app | 🟠 | tache blanche dans la barre de statut |
| 8 | `startForegroundService()` pour arrêter | 🟡 | course au démarrage |
| 9 | `startForegroundService()` sans garde API 26 | 🟡 | `NoSuchMethodError` sur Android 7 |
| 10 | l'Activity écrivait un état du service | 🟡 | double session sur ARRÊTER→DÉMARRER rapide |
| 11 | abonnement capteurs non idempotent | 🟡 | état « suspendu » incohérent |

### Les trois nouvelles classes, et pourquoi

| Classe | Rôle | Pourquoi pas dans le service |
|---|---|---|
| `SessionJournal.java` | format + analyse + formulation de la notice | 100 % pur → testé hors appareil (58 tests) |
| `SessionStore.java` | accès disque au journal | doit être appelé par `MainActivity` **et** le service |
| `ic_stat_gps.xml` | small icon monochrome | une icône d'app ne survit pas à l'aplatissement en blanc |

### Le défaut de conception le plus instructif

Le journal `session.state` portait un `dirty=true`. En simulant six scénarios,
**deux faux positifs** sont apparus : ouvrir l'app pendant une session vivante,
et un simple passage écran éteint en mode économie, annonçaient tous deux
« session interrompue ».

Cause : un seul booléen ne peut pas distinguer **trois** fins (voulue, subie,
volontairement suspendue). Ajout d'`expectedAlive`. Six scénarios, six réponses
correctes — vérifiées par simulation, pas par relecture.

**Le constat qu'il faut retenir** : la relecture humaine a trouvé 11 défauts,
la compilation n'en aurait trouvé aucun, et les tests unitaires aucun non plus.
Seule la **simulation d'états** a trouvé les deux faux positifs.

### État vérifiable

- **38 sources Java** + 10 fichiers de tests, ~12 300 lignes
- **559 tests** : Utils 26 · Advanced 91 · DopFilter 52 · Policy 31 · Journal 58 · Destination 19 · Thème 31 · Signal 170 · GPX segments 19 · Échantillonnage/parsing 62
- **Thème jour/nuit** : 12 couleurs × 2 palettes, 0 couleur en dur dans le code
- **0 erreur** contre le VRAI `android.jar` API 34 (48 226 classes)
- **82 IDs Java ⇄ 82 XML**, **106 strings, 0 orpheline**
- 28/28 fichiers parsés par un vrai analyseur Java (`javalang`)
- 66 IDs Java ⇄ 66 XML, 0 string / layout / drawable manquant

---

## 1sexies. v5.2 — audit structurel : stockage, écritures, crash

Détail chiffré dans **`docs/AUDIT_STOCKAGE.md`**. Les trois réponses courtes :

### Où sont les données ?
`/Android/data/com.example.gpstest/files/` — invisible pour l'utilisateur depuis Android 11.
**Défaut structurel corrigé** : `FileProvider` + `ExportHelper` + bouton de partage
dans les Réglages. Aucune permission de stockage ajoutée.

### Écritures abusives ?
Un point = **343 octets** → **1,18 Mo/h**. Sur l'usure flash : ~210 000 ans
d'usage réel. **Ce n'était pas le problème.** Le vrai problème : `flush()`
à chaque point, **sur le thread principal**. Corrigé : flush toutes les 2 s
(1 800/h au lieu de 3 600), flush forcé sur chaque événement, et thread `gpx-io` dédié.

### Pourquoi ça crashe ?
**Boucle de permissions** : refuser `POST_NOTIFICATIONS` puis appuyer sur DÉMARRER
relançait indéfiniment la demande. Correction structurelle : séparation
`ensurePermissionThenStart()` / `startTrackingNow()`.

### Second bug grave trouvé : écrasement de traces
`fileName()` horodatait à la **minute**. Deux traces dans la même minute →
même nom → `FileWriter(f, false)` **tronquait** la première. Perte de données
silencieuse. Corrigé : horodatage à la seconde + `uniqueFile()` qui refuse
tout écrasement.

### Nouveauté de méthode : les stubs de compilation
`tools/stubs/` contient 80 classes Android minimales qui permettent de
**compiler l'intégralité du projet hors Android Studio** :

```
javac -encoding UTF-8 -d build -sourcepath "tools/stubs:app/src/main/java" \
      -cp tools/stubs $(find tools/stubs app/src/main/java -name "*.java")
```

**0 erreur de compilation.** Ce n'est pas un APK, mais c'est infiniment plus
qu'une relecture : les erreurs de type, de signature et de méthode absente
sont détectées. C'est ce qui a permis de vérifier que tout le code Java est
cohérent.

---

## 2. Les nouveaux modules (v2)

### `Datum.java` — 9 datums, transformation de Helmert 7 paramètres

La chaîne complète : `géodésique → ECEF → Helmert → ECEF → géodésique`.

**Pourquoi c'est utile** : comparer vos coordonnées GPS avec une carte papier locale (IGN en France, OS au Royaume-Uni, USGS aux USA) donne un écart de 50 à 250 m si le datum diffère. C'est LE piège des randonneurs.

Datums fournis : WGS84, ETRS89, NAD83, ED50, NAD27, OSGB36, AGD66, AGD84, SAD69.

### `Utm.java` — projection UTM

Formules standard (Snyder), gestion des bandes de latitude (C..X), du false northing austral, et des cas particuliers norvégien/svalbardais.

### `NmeaParser.java` — les trames NMEA

C'est la source de ce que l'API haut niveau ne donne pas : **DOP, qualité du fix, satellites réellement utilisés**.

| Trame | Apporte |
|---|---|
| **GGA** | position, qualité du fix, nb de satellites, HDOP, altitude |
| **GSA** | **PDOP / HDOP / VDOP** — la vraie mesure de qualité |
| **RMC** | vitesse (nœuds) et cap |
| **ZDA** | date + heure UTC complètes |

Le **DOP** explique ce que le nombre de satellites ne dit pas : 12 satellites dans une rue étroite donnent une moins bonne position que 6 en plein champ. Barème : < 1 idéal, < 2 excellent, < 5 bon, < 10 médiocre, > 20 inutilisable.

### `Astro.java` — lever/coucher du soleil et phases de lune

Algorithme NOAA, précision ~1 minute aux latitudes tempérées. Détecte correctement le **jour polaire** et la **nuit polaire**.

### `GpxWriter.java` — la trace

GPX 1.1, lisible par QGIS, Organic Maps, Google Earth, OsmAnd. C'est ce qui transforme un « testeur » en **outil**.

### `RawMeasurementsFormatter.java` — page expert

CSV en flux des mesures GNSS brutes et des trames NMEA, avec reconnaissance indicative des bandes par fréquence. Le schéma CSV stable comprend 47 colonnes : horodatages UTC et monotone, constellation/SVID/type de code, signaux et C/N0, Doppler et temps satellite, ADR/phase, champs d'horloge GNSS, références inter-signaux ISB, AGC et payload NMEA. Sur Android 12+, le mode **full tracking** est également proposé (désactivé par défaut) : il réduit le duty-cycling et peut améliorer la continuité des mesures/phase, au prix d'une consommation accrue. Android précise qu'une requête full tracking peut influer sur les autres clients GNSS de l'appareil tant qu'elle est active ; le réglage est donc explicite, réversible et désactivé par défaut. Détails : `docs/LOT_3_GNSS_EXPERT.md`.

**Note honnête** : la pseudodistance absolue reste vide. La reconstruire correctement exige une gestion rigoureuse des états de mesure, de la référence temporelle, du bouclage du temps satellite et des biais inter-signaux. Le projet n'affiche donc pas une pseudo-mesure non validée comme si elle était vraie.

L'enregistrement est activé/désactivé depuis la page Expert ; les groupes Horloge détaillée, Signaux, Doppler/temps SV, Phase/ADR et NMEA sont mémorisés dans les préférences. Le service possède le worker, tandis que l'Activity ne fait qu'afficher les données et envoyer les commandes.

**Limite Android** : les callbacks GNSS bruts et NMEA ne sont délivrés par l'API que lorsque le GPS est activé et que l'application est au premier plan. L'écran peut donc afficher une période sans mesures brutes lorsque l'application passe en arrière-plan ; le fichier reste ouvert et est périodiquement vidé sur disque, mais aucune donnée absente n'est inventée. Le suivi de position/GPX du foreground service est indépendant.

### `SevenSegmentView.java` — l'affichage 7 segments

Le style des vieux récepteurs, dessiné au Canvas. Segment par segment, comme un vrai afficheur LED.

**Pour votre projet Processing** : les deux peuvent cohabiter. Un View custom Android pour les chiffres LED, Processing pour les graphiques/dashboards complexes. Vous pouvez même passer les données à Processing par socket local ou par un fichier — tout est déjà en texte formaté.

---

## 3. Ce que fait l'application maintenant

### Écran principal

| Section | Contenu |
|---|---|
| **Position** | DMS, altitude, précision + **sélecteur de datum** et position convertie |
| **Signal** | Vus/utilisés + **compteur L1 / L5**, qualité du fix, liste par satellite avec bande |
| **Qualité/DOP** | PDOP, HDOP, VDOP avec qualification textuelle |
| **Vitesse/Cap/Boussole** | km/h, cap GPS, cap boussole (magnétomètre) |
| **Carte du ciel** | azimut/élévation, taille et couleur selon le signal |
| **Temps** | TTFF, durée, max satellites, max C/N0 |
| **Barre d'outils** | AGPS RAZ, Trace GPX, Expert |

### Écran Réglages (v5)

Trois politiques d'écran éteint (Continuer / Garder l'écran allumé / Pause) et le
choix GPX 1.0 / 1.1. Chaque clic est enregistré immédiatement et envoyé au service
par intent — le service reste le seul à décider de suspendre ou non.

### Page Expert (le « warrior »)

- **Aperçu GNSS brut en direct** dès que la page Expert est visible, sans obligation d'enregistrer
- **Mesures brutes** et trames NMEA en CSV, avec démarrer / arrêter
- **Choix persistants** des groupes Horloge, Signaux, Doppler, Phase et NMEA
- **Trames NMEA** défilantes (200 dernières)
- **Horloge 7 segments**
- Détection du support matériel : si l'appareil ne fournit pas les mesures brutes, un message clair s'affiche et les NMEA restent disponibles

---

## 4. Ouvrir le projet

1. Android Studio → **File → Open** → dossier `gps-test`
2. Laisser Gradle synchroniser (~5 min la première fois)
3. Brancher un téléphone en **débogage USB**, ou créer un émulateur
4. ▶️ Run

> ⚠️ **Ne passez pas par « New Project »** — le wizard propose Compose/Kotlin par défaut. Le projet est déjà configuré en Java.

### L'émulateur et le GPS

Emplacement simulable, mais **les satellites n'apparaîtront pas** (`GnssStatus` ne fournit pas de satellites fictifs). **Pour tester satellites, carte du ciel, L1/L5 et DOP : un vrai téléphone, dehors.**

---

## 5. Pièges spécifiques à ces nouvelles fonctions

| Piège | Symptôme | Explication |
|---|---|---|
| **AGPS RAZ** | Le fix suivant met 30-60 s | **Normal** : vous venez d'effacer les éphémérides. C'est le but — comparer un cold start et un warm start |
| **L1/L5 toujours à 0** | Pas de compteur de bandes | Votre téléphone ne remonte pas la fréquence porteuse. Les vieux appareils ne le font pas. Rien à corriger : c'est matériel |
| **DOP vide** | PDOP/HDOP/VDOP affichent « — » | Certains appareils ne fournissent pas les NMEA. Le reste fonctionne |
| **Mesures brutes indisponibles** | Message dans la page Expert | Votre chipset ne les expose pas. Très courant sous Android 10 |
| **Boussole qui tourne** | Cap instable | Interférences magnétiques. Éloignez-vous du métal/électronique. Calibrez en faisant un 8 avec le téléphone |
| **GPX introuvable** | — | Chemin : `Android/data/com.example.gpstest/files/`. Récupérable par USB ou via un gestionnaire de fichiers |
| **Datum identique** | Position convertie = position native | Normal pour WGS84, c'est le datum natif du GPS |

---

## 6. Parcours de lecture

| Ordre | Fichier | Notion |
|---|---|---|
| ① | `AndroidManifest.xml` | permissions, `ACCESS_LOCATION_EXTRA_COMMANDS` pour l'AGPS |
| ② | `res/layout/activity_main.xml` | Layouts, `layout_weight`, `Spinner`, View custom |
| ③ | `MainActivity.java` | interface, permissions et commandes du suivi |
| ④ | `SatelliteAdapter.java` | `BaseAdapter`, pattern ViewHolder |
| ⑤ | `SkyView.java` | View custom, Canvas |
| ⑥ | `Datum.java` + `Utm.java` | géodésie, mathématiques |
| ⑦ | `NmeaParser.java` | parsing de texte, checksums |
| ⑧ | `ExpertActivity.java` + `TrackingService.java` | interface Expert et acquisition GNSS brute centralisée |

### Les flux de données GNSS

```
TrackingService / LocationManager
1) LocationListener        → position / vitesse / cap et trace GPX
2) GnssStatus.Callback     → satellites (azimut, élévation, C/N0, bande observée)
3) OnNmeaMessageListener   → trames texte → DOP / qualité du fix / NMEA Expert
4) GnssMeasurementsEvent  → mesures brutes, uniquement sur demande depuis Expert

MainActivity / capteurs
5) SensorManager           → boussole (TYPE_ROTATION_VECTOR)
6) TrackingState           → snapshots partagés avec les écrans
```

Le service possède les abonnements GNSS ; l'Activity Expert ne s'abonne plus elle-même. Cette séparation évite un deuxième flux concurrent lorsque l'écran change de cycle de vie.

---

## 7. Prochaines étapes possibles

| Fonction | Effort | Intérêt |
|---|---|---|
| **Barres SNR verticales colorées** | ⭐⭐ | la vue emblématique de GPS Test |
| **Précision réelle vs estimée** | ⭐⭐ | savoir si son GPS ment |
| **MGRS / Maidenhead** | ⭐⭐ | complète UTM (déjà fait) |
| **Pseudodistance complète** | ⭐⭐⭐ | la vraie porte du RTK/PPP |
| **Dashboard Processing** | ⭐⭐⭐ | votre projet perso |
| **Carte du ciel rotative** | ⭐ | joli |

---

## 8. Note

Ce projet fait **~3 350 lignes** et reste sous le dollar de tokens à produire. Le coût réel, c'est le temps passé à le lire, le compiler et le débugger sur un vrai appareil — c'est pour ça que la doc insiste sur l'ordre de lecture.

**Si ça casse :** collez l'erreur, on corrige.
---

## 1septies. v5.3 — écritures, nom de fichier, stubs, compatibilité

Détail complet dans **`docs/ECRITURES_ET_COMPATIBILITE.md`**.

### « 3600 fois la même zone par heure » : non, et voici pourquoi

`flush()` **n'écrit pas sur la flash** : il vide le tampon Java vers le
*page cache* du noyau. L'écriture physique est décidée par Linux.

| | |
|---|---|
| 1 point GPX 1.1 | **343 octets** |
| 1 bloc du FS | 4 096 octets |
| 1 point = | **8,4 %** d'un bloc |
| 1 h à 1 Hz | **301 blocs distincts**, chacun écrit **une fois** |
| 1 photo 3 Mo | **768 blocs** |

Le fichier est ouvert en **ajout seul** : chaque point va dans un
emplacement neuf. S'y ajoutent le **wear leveling** du contrôleur et le
journal circulaire d'ext4/f2fs. Votre scénario catastrophe (une zone morte
en une heure) ne se produit pas ici. Il se produirait pour un fichier de
**taille fixe réécrit en place** — c'est pourquoi `session.state` n'est
écrit qu'aux transitions, jamais à chaque point.

### Nom de fichier (imposé, implémenté)

```
Trace_2026_11_22_12h23m.GPX        ← heure LOCALE de DÉBUT
Trace_2026_11_22_12h23m_v10.GPX    ← variante GPX 1.0
```

`:` est interdit sur FAT/exFAT/Windows → `h` et `m`. Le tri alphabétique
est le tri chronologique. Le **contenu** GPX reste en UTC (exigence de la
norme) : nom pour l'humain, contenu pour la machine.
Collision intra-minute → `uniqueFile()` ajoute `-2`, `-3` ; jamais d'écrasement.

### Stubs : ce qu'ils sont, et pourquoi je ne m'en sers plus pour la compatibilité

Les stubs sont des classes Android **écrites à la main** pour compiler vite.
Ils valident la syntaxe, mais **pas la présence réelle des API**. Preuve
faite pendant cette session : avec un garde `SDK_INT >= O` cassé
volontairement, la compilation avec stubs donnait **0 erreur**.

Remplacés par les **vrais** jars (`android-all-14` : 48 226 classes ;
`android-all-7.0.0` : 15 058 classes) + les vraies bibliothèques AndroidX.

**Bug réel trouvé par ce test** : `import android.app.NotificationChannel;`
en tête de `TrackingService.java` (classe API 26). Corrigé : référence
pleinement qualifiée **à l'intérieur** du bloc gardé.

**Nuance honnête** : compiler contre un vieux `android.jar` avec `javac`
produit **97 faux positifs** (`findViewById` n'est pas générique avant
API 26). Ce n'est pas l'outil adéquat. Le vrai vérificateur s'appelle
**Lint `NewApi`**, dans Android Studio, et il doit être à zéro.

### Ce qui reste à faire ailleurs, obligatoirement

| Étape | Outil | Pourquoi ici c'est impossible |
|---|---|---|
| Lint `NewApi` | Android Studio | raisonne sur les gardes `SDK_INT` |
| `aapt2` (ressources) | Gradle | layouts/styles non compilés ici |
| Manifest merger | Gradle | conflits de `FileProvider` |
| Test sur API 24 / 33 / 34 | appareil ou émulateur | aucun runtime ici |

**Limite résiduelle assumée** : `javac` valide les types et les classes,
pas les ressources, ni le manifeste, ni le runtime.
---

## 1octies. v5.4 — destination des traces, préférences, audit d'états

Détail complet dans **`docs/DESTINATION.md`**.

### La question : pourquoi ne pas écrire directement dans le dossier choisi ?

Parce qu'un répertoire choisi par l'utilisateur n'est **pas un chemin**, c'est
une URI de document remise à une autre application. D'après la documentation
officielle de `DocumentsProvider` :

> Mode "r" **should always be supported**. Provider should throw
> `UnsupportedOperationException` if the passing mode is not supported.
> … "w" **may or may not truncate**.

**Seul `"r"` est garanti.** L'append (`"wa"`) peut être refusé. Sans append,
ajouter 343 octets impose de réécrire le document entier :

| Trace | Taille | Réécritures (flush 2 s) | Écrit |
|---|---|---|---|
| 1 h | 1,18 Mo | 1 800 | **2,1 Go** |
| 8 h | 9,4 Mo | 14 400 | **136 Go** |

Votre scénario « 3 600 fois la même zone » serait alors **réel**. D'où :
**écriture locale (append, sûr) + publication** (création une fois, réécriture
toutes les 30 s, pied GPX ajouté si absent).

### Ce que voit l'utilisateur, qui n'est pas informaticien

**Avant chaque enregistrement**, une confirmation affiche en clair :

```
Stockage interne / Téléchargements
Fichier : Trace_2026_11_22_12h23m.GPX
```

Le nom est calculé par **la même fonction que le service** : ce qui est annoncé
est, par construction, ce qui sera écrit. Trois boutons de dossier en un geste,
plus « Choisir un autre dossier… » (SAF).

**Pendant la trace**, ligne *Destination* en permanence sur l'écran principal,
avec un état jamais mensonger — un échec dit toujours « conservée ».

### Préférences : écrites à chaque geste, aucun bouton

| Réglage | Méthode | Pourquoi |
|---|---|---|
| Politique, version GPX, destination | `commit()` | le service les relit dans la foulée, autre thread |
| Datum, filtre satellites | `apply()` | purement visuel |

**Correctif** : `selectedDatum` et `filterMode` ne survivaient pas à une
rotation d'écran. Choisir Lambert 93, tourner le téléphone → retour à WGS84.
Persistés et restaurés **avant** d'attacher l'écouteur.

**Quota SAF** : Android plafonne à **128 autorisations persistées par app**.
`setTreeUri()` libère toutes les autres avant d'en prendre une ; `clear()`
libère **avant** d'oublier l'URI, sinon l'autorisation devient orpheline et
irrécupérable.

### Audit d'états — 11 bugs trouvés dans ma propre v5.4

| # | Bug | Conséquence |
|---|---|---|
| 1 | `applyPreset` construisait l'URI de Téléchargements pour **tous** les presets | « Documents » écrivait dans Download |
| 2 | `createDocument` à chaque publication | 120 fichiers « …(1) » après 1 h |
| 3 | Publication sans pied GPX | fichier **invalide**, QGIS refuse |
| 4 | `flushNow()` (asynchrone) appelé depuis le thread de publication | copie en retard |
| 5 | Drapeau `segmentClosed` lu depuis un autre thread | publication sans pied malgré le drapeau → **drapeau supprimé**, on lit `</gpx>` dans le fichier |
| 6 | `FileWriter` flushé depuis 2 threads | `FileWriter` non synchronisé → corruption |
| 7 | Publication finale sans identité de segment | **deux traces mélangées** dans un fichier |
| 8 | Double Toast + libellé écrit deux fois | message utile masqué |
| 9 | `refreshDestination()` recochait un bouton via son écouteur | boucle et réécritures |
| 10 | `clear()` oubliait l'URI avant de libérer l'accès | fuite définitive sur le quota de 128 |
| 11 | `Destination` mélangeait logique pure et `Context` | **impossible de tester** la partie qui avait le bug → classe `DestPresets` extraite, 19 tests |

Le n° 11 est le plus grave : c'est une faute d'**architecture**. Elle est la
raison pour laquelle le n° 1 est resté invisible.

### Invariants vérifiés

| Invariant | Mécanisme |
|---|---|
| `recording=true` ⟹ fichier ouvert | `openRecordingSegment` avant le marquage |
| `recording=false` ⟹ pied + flush | `closeRecordingSegment` |
| `suspended=true` ⟹ capteurs coupés | `unsubscribeSensors` |
| Service tué ⟹ journal *dirty* | `stopRequested` |
| Une seule publication en vol | `publishInFlight` + `publishPending`, libérés en **un seul** endroit |
| Une publication ne retarde jamais la trace | thread `gpx-publish` **séparé** |
| Une tâche ne touche jamais un autre segment | `segmentId` capturé à l'envoi |
| GPX publié toujours ouvrable | `endsWithGpxClose` |

### Sur les versions Android — franchement

| `minSdk` | Couverture | Branches conditionnelles |
|---|---|---|
| **24** (Android 7) | ~99 % | channel, startForegroundService, receiver flag… |
| **26** (Android 8) | ~97 % | **celles-ci disparaissent** |

Passer à 26 supprime les trois branches les plus fragiles. **C'est votre
décision, pas la mienne.** Et aucun outil ne remplace **Lint `NewApi`** :
c'est le seul qui raisonne sur les gardes `SDK_INT` et fasse foi.
---

## 1nonies. v5.5 — thème clair / sombre / suivre le téléphone

Détail complet dans **`docs/THEME.md`**. Le renommage a été **annulé sur votre
décision** : ni le nom affiché, ni le package, ni les répertoires n'ont bougé.

### Les trois modes

Réglages → **APPARENCE** : *Suivre le téléphone* (défaut), *Clair*, *Sombre*.
Écriture immédiate, pas de bouton « Enregistrer ».

**Suivre le téléphone** ne demande aucune ligne de Java : le thème hérite de
`Theme.MaterialComponents.DayNight`, qui lit `uiMode` et choisit `values/` ou
`values-night/`.

**Forcer** un mode utilise `Resources.updateConfiguration` et **un seul**
`recreate()` à la demande de l'utilisateur — pas `AppCompatDelegate`, qui
recrée les écrans en boucle et ferait clignoter une app en cours
d'enregistrement.

### Le piège central : uiMode est écrasé pour tout le processus

Forcer « Clair » écrit `uiMode` dans les `Resources`. Cette valeur vit aussi
longtemps que le processus : **plus personne ne peut relire le réglage du
téléphone**, y compris nous. Sans garde-fou, repasser à « Suivre le
téléphone » ne restaurait rien et l'app restait claire indéfiniment.

Correctif : capture du vrai réglage **à la première utilisation**, avant tout
forçage, puis restauration.

### Les 6 défauts trouvés en audit

| # | Défaut | Conséquence |
|---|---|---|
| 1 | `colorOnPrimary = gps_text` | bouton principal **illisible en clair** (2,02:1) → blanc constant (7,87:1) |
| 2 | `ListView` transparent | **liste des satellites invisible** en mode clair → fond opaque |
| 3 | `gen_r.py` sans `R.drawable` | `R.drawable.list_bg` n'existerait jamais, erreur silencieuse |
| 4 | 5 couleurs en dur dans le dialogue | illisible dès qu'on change de mode |
| 5 | segment 7-seg éteint figé `#1B3320` | afficheur **cassé** sur fond clair → `@color/gps_seg_dim` |
| 6 | clé de préférence = **libellé** traduit | changer une faute de frappe perd le réglage → identifiants stables |

### Contraste mesuré (WCAG AA = 4,5:1)

| Élément | Clair | Sombre |
|---|---|---|
| Texte principal | **15,11:1** | **15,97:1** |
| Texte secondaire | 5,39:1 | 6,71:1 |
| Bouton principal | 7,87:1 | 5,13:1 |

Ambre (4,03:1 en clair) et rouge (4,25:1 en sombre) passent pour du **gros
texte** seulement — dit franchement plutôt que masqué.

### Ce qui n'a PAS été touché

`app_name` = « GPS Test », `applicationId` = `com.example.gpstest`,
répertoires et noms de fichiers inchangés. Votre décision, appliquée telle
quelle.
---

## 1decies. v5.6 — perte de signal, reprise, unités, alerte vocale

Détail complet dans **`docs/SIGNAL_ET_REPRISE.md`**. C'est le document le
plus important du projet : une erreur ici produit une trace fausse en silence.

### Le défaut le plus grave, corrigé

```java
// AVANT
state.setHasFix(true);   // ← jamais remis à false
if (state.isRecording() && !Double.isNaN(lat)) appendPoint(p);  // ← rien d'autre
```

Un tunnel laissait **l'écran afficher une position figée pendant des
heures**, et toute position était écrite : 500 m de précision, dix minutes
de retard, ou (0,0).

**Après** : une machine à états décide (`FixQuality.Tracker`), un watchdog
la fait avancer toutes les secondes.

### La copie entière 32 bits — mise au point sur « Q3.6 »

| Lecture | Pas | Résolution |
|---|---|---|
| Q3.6 **binaire** (26.6) | 2⁻⁶ ° | **1 740 m — inutilisable** |
| **±180.000000 décimal** | 10⁻⁶ ° | **0,111 m — implémenté** |

`FixGeometry.toMicro()` → `int` signé, ±180 000 000, tient dans un `int32`.
Le double reste la référence (pas ≈ 1e-15°).

### Les 5 conditions pour qu'un point soit écrit

Finitude · bornes · pas l'île Null · précision ≤ 50 m et **> 0** · temps
cohérent.

### Les 5 pièges de la reprise

| Piège | Réponse |
|---|---|
| Le 1ᵉʳ fix après coupure est mauvais | **3 fixes cohérents** exigés avant d'écrire |
| Saut de 2 km indécidable | vitesse implicite > 200 km/h → refusé |
| Le temps GPS recule | horodatage strictement croissant |
| Antiméridien | Haversine (testé : 22 km, pas 39 800) |
| Deux pertes consécutives | frontière **consommée** une fois, XML équilibré |

### La coupure dans le GPX

Un tunnel produisait une **ligne droite** entre l'entrée et la sortie, et
donc une distance fausse. Désormais : frontière `<trkseg>`, la
représentation officielle d'une interruption. Aucun point artificiel écrit.

### Alerte vocale : « toutes les 30 s » n'est pas si simple

Grâce **15 s** · rappel **30 s** · plafond **6 annonces** · reprise annoncée
**une fois** après 3 s · **silence total** si l'utilisateur a coupé le GPS
lui-même. Désactivable, activée par défaut.

`LossAnnouncer` **décide** (pur, testé) ; `VoiceAlert` **parle**.

### 5 bugs d'interaction trouvés en audit

| # | Bug | Conséquence |
|---|---|---|
| 1 | Watchdog actif pendant la suspension | alerte vocale à qui a demandé l'économie de batterie |
| 2 | Reprise après suspension sans réacquisition | **1ᵉʳ fix écrit**, aussi mauvais qu'en sortie de tunnel |
| 3 | Fichier rouvert sans remettre l'état à zéro | fichier neuf commençant par un point faux |
| 4 | Ordre des blocs dans `tick()` | reprise **jamais** annoncée |
| 5 | Sentinelle `== 0` pour un temps | décalage d'un tick (0 est un instant valide) |

### Grisage

`grpPosition` à **35 % d'opacité** dès que le signal est perdu. La dernière
position **reste lisible** mais visiblement inerte — plus utile que des
« — » partout.

### Unités

Métrique (défaut FR) · Impérial (ft/mi/mph) · Maritime (m/NM/**nœuds**).
Le moteur ne connaît que le SI ; la conversion est un **formatage**.
---

## 1undecies. v5.7 — corrections après revue utilisateur

Trois choix de la v5.6 étaient **faux**. Ils sont corrigés, et les tests
couvrent les scénarios terrain décrits. Détail : `docs/SIGNAL_ET_REPRISE.md`.

### Ce qui était faux

| # | v5.6 | v5.7 | Pourquoi |
|---|---|---|---|
| 1 | (0,0) accepté si précision « bonne » | **(0,0) toujours rejeté** | en acquisition un récepteur annonce 5 m sur un (0,0) non résolu |
| 2 | limite 200 km/h | **supprimée** | un avion vole à 900 km/h ; le seuil refusait des trajets réels |
| 3 | péremption jugée sur l'heure murale | **horloge monotone seulement** | un recul d'1 h du téléphone aurait fait rejeter tous les points pendant 1 h |

### Le scénario (0,0), mesuré

```
Paris établi → (0,0) du module → Paris
distance depuis le dernier point valide = 22 m
```

Sans le rejet : ~5 000 km et ~5 000 km/s. C'est exactement le « distances et
vitesses XXL » que vous décriviez.

### Le temps qui recule — la preuve

> *« the device's Unix epoch time system clock is not monotonic; it can jump
> forwards or backwards unpredictably »*
> — developer.android.com, `Location.getTime()`

La doc confirme votre doute. Ce n'est pas l'horloge des satellites, c'est
**celle du téléphone**. Toutes les durées passent donc sur
`SystemClock.elapsedRealtime()`. L'heure murale ne sert plus qu'à écrire le
`<time>` du GPX, et si elle recule, l'heure écrite est forcée croissante de
1 ms — on ne jette pas un point dont la géométrie est vraie.

### Échelle de précision

| Précision | Affiché | Enregistré |
|---|---|---|
| ≤ 50 m | oui | **oui** |
| 50–100 m | oui, **grisé** | non |
| > 100 m | non | non — **traité comme une perte** |

### Alerte vocale réglable

Jamais · 30 s · 1 min · 2 min · 5 min — plafond 1/3/6/10/sans limite —
reprise annoncée ou non. Le premier intervalle **est** la grâce.
Message parlé **court** (« Signal GPS perdu. ») ; la durée s'écrit à l'écran.
Suspension volontaire = **aucune répétition** (votre précision, à la lettre).

### Cinq états à l'écran, pas deux

| Situation | Message |
|---|---|
| jamais eu de position | « Pas de position » |
| position perdue | « Signal GPS perdu — depuis 1 min 45 s » |
| fix refusé | « Réception invalide : précision insuffisante » |
| reprise | « Position acquise » |
| économie | « En pause » |

Plus une ligne rouge sous le statut quand une session **enregistre** et que
la réception n'est pas valide : *« Aucune position enregistrée tant que la
réception n'est pas rétablie. »* — c'est le malentendu le plus coûteux.
---

## 1duodecies. v5.8 — satellites, fix 2D, époque de trace, langue

### Satellites UTILISÉS (pas « en vue »)

`getSatelliteCount()` ≠ `usedInFix()`. **Minimum 4 utilisés** : c'est le
minimum mathématique pour résoudre 4 inconnues (lat, lon, alt, horloge).
En dessous, le récepteur fabrique une solution approximative.

**Fraîcheur** : `GnssStatus` arrive dans un callback séparé. Un statut de
plus de **3 s** est ignoré (0 = inconnu) — sans cette borne, on jugerait un
fix neuf avec des données périmées, et la décision serait fausse sans le dire.

### Fix 2D = refusé, quelle que soit la précision annoncée

Un fix 2D n'a pas résolu l'altitude : le récepteur l'a supposée, et les
coordonnées peuvent être fausses de centaines de mètres. **La précision
annoncée ne le trahit pas** (elle est horizontale, et peut valoir 3 m). Il
faut donc le détecter par le **type** : `hasAltitude() == false` → refusé.

Corollaire : l'altitude n'est plus écrasée par `NaN`. On garde la dernière
altitude 3D et on la grise. Un fix 2D ne dit pas « altitude inconnue », il
dit « je ne l'ai pas mesurée ».

### Époque de trace

```
t = heure_du_premier_fix + (elapsed_maintenant − t0)
```

Ce que ça règle d'un coup : le temps ne peut plus reculer, le pansement
« +1 ms » de la v5.7 disparaît, les creux de l'heure murale sont invisibles,
les intervalles sont exacts, et la trace démarre à une heure crédible.
Contrepartie mesurée (`driftMs`) et signalée au-delà de 5 min.

### Langue = celle du téléphone

Plus de `Locale.FRANCE` forcée, et le texte est un **identifiant de
ressource** : Android choisit la variante linguistique. Le jour où l'app est
traduite, l'alerte l'est aussi.

### Défauts

Alerte **activée** · rythme **1 min** · plafond **sans limite** ·
choix **1 · 3 · 5 · 10 · sans limite**.

### Bug trouvé par les tests

`lastEpochTimeMs` initialisé à l'heure du premier fix ⇒ ce point était
considéré comme « pas après le précédent » et forcé à **+1 ms** : toute la
trace décalée, compteur faux dès le premier point. Sentinelle
`Long.MIN_VALUE`. **Troisième fois** dans ce projet qu'une sentinelle mal
choisie crée un décalage d'un cran.
---

## 1terdecies. v5.9 — analyse de `Aide.docx`

Rapport complet : **`docs/ANALYSE_AIDE_DOCX.md`**.

### La correction que le document rend évidente

Le minimum n'est pas 4 : c'est **3 + n constellations actives**.

Chaque constellation a sa propre échelle de temps. Mélanger GPS et Galileo
ajoute une inconnue (le décalage d'horloge entre systèmes).

| Configuration | Sats | Min | Verdict |
|---|---|---|---|
| 4 GPS | 4 | 4 | ✅ |
| 3 GPS + 1 Galileo | 4 | 5 | ❌ **refusé** |
| 3 GPS + 3 Galileo | 6 | 5 | ✅ |
| 3 GPS + 1 Gal + 1 Glo | 5 | 6 | ❌ **refusé** |
| 2 + 2 + 2 | 6 | 6 | ✅ |

La v5.8 exigeait 4 quel que soit le nombre de constellations : **trop
laxiste**. Une constellation ne compte que si elle a au moins un satellite
utilisé — un satellite isolé ne coûte pas d'inconnue.

### Ce que je n'ai pas repris du document

| Proposition | Pourquoi non |
|---|---|
| `3 + n + **1**` (marge) | le récepteur ne l'applique pas ; ferait afficher « pas de signal » sur des positions valides. Une ligne à changer si vous le voulez |
| `hdop > 2.5` bloquant | Android ne fournit pas le HDOP dans `Location` ; et un seuil dur jette du vrai (même défaut que notre 200 km/h de la v5.6) |
| `accuracy > 15 m` | on garde **50 m**, votre valeur |
| `fix_mode_3d` par trame GSA | `hasAltitude()` dit la même chose, sans parsing |
| `FileProvider` | on garde le SAF (v5.4) |

### Ce que le document révèle et que nous n'avons pas

**La distance totale et le dénivelé D+ ne sont pas calculés.** Le document
propose deux filtres indispensables le jour où on les ajoute :

| Filtre | Seuil | Pourquoi |
|---|---|---|
| distance min. entre points | **> 2 m** | à l'arrêt le GNSS oscille ±2–5 m → de faux kilomètres |
| hystérésis d'altitude | **> 3 m** | l'altitude oscille ±2–5 m sur terrain plat |

Ce n'est pas un bug, c'est une **absence**. Notre moteur a déjà
`Decision.distanceM` — il suffirait de l'accumuler en ignorant les sauts
< 2 m, et de remettre à zéro à chaque coupure de segment.

### Le document ne traite jamais

L'île Null (0,0) — pourtant le cas qui produit les « distances XXL » que
vous avez rencontré —, l'heure qui recule, les coupures entre deux pertes,
la réacquisition en 3 fixes, l'alerte vocale.

### Amélioration d'affichage

Quand les satellites manquent, l'écran dit **combien** : « **4 / 6**
satellites utilisés ». « Précision insuffisante » n'est pas actionnable ;
« il m'en manque deux » l'est.
---

## 1quaterdecies. v5.10 — carte, espacement des points, relecture GPX

Détail : **`docs/CARTE_ET_ECHANTILLONNAGE.md`**.

### « 2 m, c'est très peu » — vous avez raison

À 2 m, une marche à 5 km/h produit un point toutes les 1,4 s → **2 500
points par heure**. Et à l'arrêt, le GNSS oscille de ±2 à 5 m : un seuil à
2 m est franchi en permanence par du **bruit pur**.

| Critère | Plage | Défaut |
|---|---|---|
| Distance minimale | aucune · 10 m → 1 km | **25 m** |
| Temps maximal | aucun · 1 s → 10 min | **10 s** |

**Un point est écrit dès que l'UN OU L'AUTRE est atteint** — jamais les
deux à la fois. En ET, une trace deviendrait presque vide dès qu'on
ralentit. Et sans critère de temps, vingt minutes immobile = **zéro
point** : l'utilisateur croit que l'enregistrement s'est arrêté.

2 h de marche : **~400 points** avec ces réglages, contre 7 200 sans
filtre — pour une trace visuellement identique.

### Bandeau permanent

Votre proposition `18:01:07 / 3D fix / 4/10 satellites / 57%` devient :

```
18:01:07   3D fix   4/10 sats   57% ⚡
```

**Largeur fixe par champ** (sinon la ligne saute à chaque chiffre et le
regard doit la retrouver), **seul le fix change de couleur** (vert/ambre/
rouge), batterie en dernier.

**Trois états, pas deux** : `2D fix` s'affiche. Notre moteur refuse les 2D,
mais afficher « No fix » ferait croire que l'antenne ne capte rien.

### Carte + relecture GPX

Page **Carte** : position courante, suivi automatique, ouverture d'un GPX,
tracé, cadrage automatique, résumé (nom, distance, durée, points, segments).

| Décision | Raison |
|---|---|
| **fonctionne hors ligne** | hors couverture, une carte vide est inutile — et c'est là qu'on en a besoin |
| `configChanges` | sinon chaque rotation recharge les tuiles : écran blanc 2 s |
| **une polyligne par segment** | sinon un trait droit traverse le tunnel |
| osmdroid, pas Google Maps | pas de Play Services ; 560 Ko |
| `*/*` au sélecteur | beaucoup de GPX ont le type générique |

### Le lecteur GPX

Parseur **maison**, testable hors appareil, **qui ne lève jamais** :
fichier vide, HTML, balise non fermée, coordonnées aberrantes → message
clair, jamais de plantage. Ignore les extensions propriétaires. Refuse
`48,8566` (virgule) au lieu de le réparer — « 48,856 » pourrait être un
millier mal formé.

**Le test qui compte** : aller-retour writer → parser. Latitudes au 1e-9,
altitudes, horodatages, et surtout la distance qui **ne traverse pas** la
coupure (1 900 m comptés à tort sinon).

### Deux bugs trouvés par les tests

1. Le compteur de segment était incrémenté **avant** le premier `<trkseg>` :
   tous les points étaient dans le segment 1, et la distance du **premier
   segment disparaissait silencieusement**.
2. Mes deux valeurs attendues de dates étaient fausses — vérifié avec Python
   avant de toucher au code, qui était juste.

---

## 1quindecies. v5.11 — modes d'enregistrement et nettoyage de trace

### Ce qui a été ajouté

**Trois modes prédéfinis + un mode personnalisé**, choisis dans Réglages :

| Mode | Distance | Temps | Précision acceptée |
|------|----------|-------|--------------------|
| Marche / randonnée | 20 m | 10 s | 8 m |
| VTT / cyclisme | 25 m | 15 s | 10 m |
| Véhicule motorisé | 50 m | 30 s | 20 m |
| Personnalisé | libre | libre | `0,4 × distance`, borné 8–50 m |

En mode prédéfini, les deux spinners distance et temps sont **grisés** : ils
affichent les valeurs du mode, sans se régler. Un changement de mode prend
effet **au point suivant**, sans redémarrer le service (écoute des
préférences).

**Nettoyage de fin de session** (activable, tolérance 0/1/3/5/10 m) :
dédoublonnage → suppression des points aberrants → Douglas-Peucker **par
segment**. L'original n'est **jamais** modifié : une copie
`Trace_…_nettoye.GPX` est écrite à côté.

### La décision qui s'écarte de la demande

La règle de rejet demandée (`précision ≥ 0,4 × distance`) rejetait un point à
**4 m de précision** en mode marche (distance 10 m) — c'est-à-dire un point
excellent, et en forêt la quasi-totalité des points. **Plancher ajouté à 8 m.**

Le rejet sur `vitesse ≤ 1 km/h` a été **retiré** : il supprimait aussi le
critère de temps, donc une session immobile ne produisait **aucun** point. À la
place, une **ancre** est écrite toutes les `temps` du mode à l'arrêt.

Détail complet et mesures : `docs/MODES_ET_NETTOYAGE.md`.

### Fichiers

- **nouveaux** : `TrackMode.java`, `TrackPostProcess.java`, `TrackCleaner.java`,
  `tests/ModesAndPostProcessTest.java` (75 tests)
- **modifiés** : `TrackingService.java`, `TrackingState.java`,
  `SettingsActivity.java`, `Prefs.java`, `activity_settings.xml`, `strings.xml`

### Note de transparence

Le JDK et les jars Android avaient été effacés de `/tmp` par une rotation de
l'environnement ; `javac` introuvable renvoyait un silence que j'ai pris pour
« 0 erreur ». Après reconstruction (JDK 17, `android.jar` API 34, 21 jars
AndroidX + osmdroid), **quatre vrais défauts** sont apparus — méthode
inventée, doublon de méthode, argument `null` passé à `getString`, `Handler`
jamais initialisé. Tous corrigés ; la compilation à 134 classes et les 646
tests sont désormais **réels**.

---

## 1sexdecies. v5.12 — `LocationRequest` non utilisé, JPX comme oracle

### `setMinUpdateIntervalMillis` / `PRIORITY_HIGH_ACCURACY` : absents, et c'est voulu

Ce sont des API **Fused Location Provider** (Play Services). Le projet utilise
`LocationManager.requestLocationUpdates(GPS_PROVIDER, 1000L, 0f, …)`, qui
**force la puce GNSS** sans dépendance Google Play.

| Approche | Force la puce ? | Dépendance |
|---|---|---|
| `GPS_PROVIDER` | **Oui** | aucune |
| `FUSED_PROVIDER` | Non (Wi-Fi possible) | aucune |
| `LocationRequest` + `PRIORITY_HIGH_ACCURACY` | Oui | **Play Services** |

Un outil de diagnostic doit mesurer ce que la puce fait, pas ce qu'un
algorithme de fusion décide de présenter. Le Fused peut interpoler, lisser ou
remplacer un fix médiocre par du Wi-Fi : exactement ce qu'un test GNSS ne doit
pas voir.

**`minTime = 1000` n'est pas une garantie de 1 Hz** : Android peut livrer plus
vite (chipset 5–10 Hz) ou beaucoup plus lentement (écran éteint). Le code ne
suppose donc jamais la cadence — `SamplePolicy` décide point par point et
l'horodatage vient de `elapsedRealtime`.

### JPX : excellent oracle, impossible comme dépendance

`io.jenetics:jpx` (Apache 2.0) est une implémentation complète de GPX 1.0/1.1.
**Mais** : 18 de ses ~100 classes utilisent `javax.xml.stream` (StAX),
**absent du SDK Android**, et la version actuelle (4.0.1) est compilée en
**Java 25** (3.2.1 en Java 17). Son propre README conseille une implémentation
XML alternative sur Android — donc un jar tiers **en plus**.

→ Utilisé comme **oracle hors appareil**, avec JPX 1.6.0 (Java 8) :
**35 vérifications, 33 concordances** (les 2 écarts sont des limites de JPX
1.6.0 sur nos `<extensions>` propriétaires).

### Cinq défauts trouvés par ce banc

| # | Défaut | Gravité |
|---|---|---|
| 1 | `<time>` écrit **toujours** : point sans horodatage daté du **31/12/1969** | grave — date fabriquée présentée comme mesure |
| 2 | Fichier **tronqué** déclaré valide (`failed = false`) | grave — trace partielle affichée comme complète |
| 3 | Filtre « aberrant » rejetait le **bruit** (±2 m = 15 km/h) : **9 points perdus sur 300** | grave — perte de données réelles |
| 4 | Douglas-Peucker réduisait **301 points à 2** (garde-fou conditionnel ajouté) | moyen |
| 5 | 3 300 `StringBuilder` par heure sans capacité initiale | mineur |

RDP lui-même était **correct** : écart maximal réel mesuré **2,30 m** pour une
tolérance de 3 m. Le problème était la lisibilité du résultat, pas la
géométrie.

Détail complet : `docs/VERIFICATION_FUSED_ET_JPX.md`.

---


## 1octodecies. Lot 5 — import GPX en flux et robustesse

Le parseur maison par recherche de balises a été remplacé par **SAX natif**
(Android/JVM), sans nouvelle dépendance. La carte et le nettoyage lisent le
`InputStream` directement : plus de copie complète du GPX en `String` avant
analyse. Les namespaces, GPX 1.0/1.1, segments, routes, waypoints, dates et
extensions utiles sont traités ; les DTD/entités externes sont refusées.

L'import cartographique se déroule hors thread UI avec progression,
annulation, limites d'octets/points/profondeur et rendu échantillonné. La trace
précédente n'est remplacée qu'après validation et création des nouveaux
overlays. Le nettoyage continue à préserver le fichier original.

- 19 suites JVM · **966 assertions** · 0 échec.
- **16 XML Android** validés.
- Aucun Gradle/SDK Android disponible ici : pas de compilation APK ni de test
  appareil réalisés.

Détails, limites et tests : **`docs/LOT_5_GPX_IMPORT.md`**.

---

## 1septdecies. v5.13 — audit complet, 13 défauts corrigés

Audit demandé « sur toutes les fonctions, tout en détail ». Méthode : 126
passes de recherche ciblée (`grep` par thème) sur les 41 fichiers Java,
chaque constat vérifié en lecture du code puis par compilation et tests.
**13 défauts réels** trouvés, tous corrigés. Aucun n'était visible à
l'usage courant ; neuf se manifestaient seulement dans un cas limite.

### Défauts de perte de données (les plus graves)

| # | Défaut | Conséquence avant correction |
|---|---|---|
| 1 | `ACTION_STOP` arrêtait le service **sans lancer le nettoyage** | bouton « Arrêter le GPS » pendant un enregistrement : la trace n'était jamais post-traitée |
| 2 | `quitSafely(300 ms)` alors que le nettoyage prend ~130 ms à ~2 s | le service se terminait avant la fin de la copie |
| 3 | `dest.delete()` **avant** la copie du fichier nettoyé | en cas d'échec de copie : l'ancien fichier détruit **et** le nouveau absent |
| 10 | Ancienne troncature des mesures en mémoire au-delà de 50 000 | résolue dans le lot 3 : CSV en flux, file bornée et pertes explicitement comptées |

État actuel après les lots précédents : le post-traitement est exécuté par des workers indépendants et `onDestroy()` ne bloque plus le thread principal en attendant les E/S. L'original GPX reste conservé. Le CSV Expert n'accumule plus les lignes en mémoire : le lot 3 utilise un worker dédié, une file bornée et des compteurs de pertes explicites.

### Défauts d'affichage et d'état

| # | Défaut | Conséquence avant correction |
|---|---|---|
| 4 | `modeReject` figé après l'arrêt de l'enregistrement | une raison de rejet restait affichée alors qu'aucun point n'était plus filtré |
| 5 | `cleanResult` survivait à la session suivante | le résultat du nettoyage précédent s'affichait sur la nouvelle trace |
| 7 | `Units.formatDistance` sous 1 km → « 0,0 km » | à 5 m l'écran affichait « 0,0 km », à 50 m « 0,1 km » |
| 11 | `zoomToBoundingBox` sur une trace d'**un seul point** | étendue nulle : zoom maximal inutilisable ou comportement indéfini |

Corrections : `setModeReject(null)` dans `stopRecording`, `setCleanResult(null)`
dans `openRecordingSegment`, `formatDistance` en mètres sous 1 km (pieds sous
1 mile en impérial), et garde d'étendue `1e-6°` dans `MapActivity`.

### Défauts de performance

| # | Défaut | Mesure |
|---|---|---|
| 8 | `SkyView.onDraw` : `String.format` + `float[]` **par satellite et par frame** | dizaines d'allocations par image, à 20–40 satellites |
| 9 | `Astro.compute` + phase lunaire recalculées **à 2 Hz** | alors qu'elles ne changent qu'une fois par jour |
| 12 | plafond d'attente du nettoyage à **15 s** sur le thread principal | seuil d'ANR = 5 s : la protection contre la perte de données pouvait provoquer un plantage |

Corrections : `onDraw` sans allocation (`Integer.toString`, flottants locaux,
`setTextSize` hors boucle), mémoïsation de l'astro (1 fois/jour par lat/lon à
1e-4°) et du datum/UTM (si la position bouge de plus de 1e-5°), plafond
d'ANR ramené à **4 s** — mesure réelle : 1 500 points nettoyés en **127 ms**,
soit 30 fois la marge.

Un treizième point n'était pas un défaut de code : le commentaire de
`userDisabled` affirmait un comportement que le code n'avait pas. **Le code
était correct** (toute perte de réception doit être annoncée, même hors
enregistrement) et n'a pas été touché — seule la justification a été réécrite.

### Gain mesuré sur l'horodatage

`GpxWriter` appelait `SimpleDateFormat` **par point** alors que `GpxParser`
documente explicitement pourquoi il faut l'éviter. Remplacé par un calcul
manuel (algorithme de Hinnant) : **40 010 instants sur 40 010 identiques**,
et **77 ms contre 438 ms** pour 200 000 appels, soit **×5,7**.

### Vérifié sans défaut

`GpxParser` gère la balise `<trkpt/>` auto-fermante ; toutes les préférences
ont une interface (`DestUri`/`ThemeMode` passent par `Destination` et
`ThemeManager`) ; aucune couleur en dur dans les layouts ; 15 fichiers XML
valides ; aucun avertissement du compilateur sur nos sources, hors
dépréciations gardées par `Build.VERSION` (API 24 → 30).

### Limites connues, assumées

- `DopCalculator.compare` affiche « — » quand deux valeurs sont égales à
  l'arrondi près (cosmétique).
- Compteurs `pointsWritten` / `pointsSkipped` et getters `getFixAgeMs`,
  `getPublishedName`, `getPolicy` : morts, conservés pour la lisibilité de
  l'API.
- `StatusHud.clockLabel` utilise encore `SimpleDateFormat` — une fois par
  seconde, hors chemin chaud.
- `MapActivity.onActivityResult` est déprécié (pas d'`EXTRA_INITIAL_URI`).
- Numérotation `gpxSegment` : compte les fermetures de segment, pas les
  ouvertures — sans effet sur la lecture.
- 12 classes n'ont pas de test direct (`Units` a désormais la section 13).

---

## 1octodecies. v5.14 — la boussole

Demande : « une belle boussole rotative avec l'affichage de l'angle, une
option nord magnétique / nord réel, et si l'appareil n'a pas de boussole,
l'afficher au lieu de laisser un tiret muet ».

### Ce qui a été construit

| Élément | Fichier | Rôle |
|---|---|---|
| `Compass` | 240 l. | logique pure : normalisation, écart le plus court, déclinaison, rose des vents, formatage |
| `CompassView` | 350 l. | cadran dessiné au Canvas : graduations, lettres, flèche, disque central |
| `CompassSensor` | 300 l. | capteurs, repli, orientation d'écran, inclinaison, déclinaison WMM |
| `CompassTest` | 92 assertions | tout ce qui peut produire un cap faux |

### Trois défauts de l'ancienne version

**1. La boussole était morte avant « Démarrer ».** L'azimut venait d'un
capteur enregistré par `TrackingService.subscribeSensors()`. Or un cap sert
précisément *avant* de se mettre en route.

**2. Elle consommait pendant tout l'enregistrement.** Une fois le service
lancé, le capteur de rotation tournait en continu — **écran éteint
compris** — pendant une randonnée de six heures, pour alimenter une valeur
que personne ne lisait.

**3. Aucun repli.** `if (rot != null) { register… }` — sans `else`. Sur une
tablette sans `TYPE_ROTATION_VECTOR`, la ligne restait à « — » sans un mot.

Correction : la boussole est une donnée d'**affichage**, elle vit dans
`MainActivity` (`onStart` → `start()`, `onStop` → `stop()`). Le service ne
garde que ce qui sert à **enregistrer**.

### Deux corrections qui n'existaient pas du tout

**A. Orientation de l'écran.** L'azimut du capteur est relatif au haut
*matériel*. En paysage, le haut de l'écran devient un autre axe : le cap
affiché serait faux de 90° ou 180°. Démonstration du signe, écrite dans le
code et vérifiée par 6 tests :

```
capEcran = normalize(capCapteur − 90 × rotation)
```

**B. Inclinaison.** `getOrientation()` suppose l'appareil à plat. Tenu
debout, l'axe « haut » n'a plus de projection horizontale et l'azimut
devient instable. Au-delà de 25°, l'écran le dit au lieu d'afficher une
valeur qui saute.

### Nord magnétique ou nord vrai

Le capteur donne un cap **magnétique**. L'écart avec le nord géographique
est la **déclinaison**, qui vaut +1,5° à Paris, **−14° à Montréal**,
−24° au Cap, +15° à Vancouver. Ne pas en tenir compte n'est pas « un peu
imprécis » : c'est faux d'un angle qui croît avec la latitude, en silence.

Le calcul utilise `android.hardware.GeomagneticField`, qui embarque le
modèle WMM dans le système — aucune table à maintenir, aucune dépendance.
Il est mémoïsé : recalculé seulement au changement de jour ou si la
position bouge de plus d'un kilomètre. La déclinaison varie de moins de
0,1° par an ; la recalculer à 2 Hz serait le défaut corrigé en v5.13 sur
l'astronomie.

**Sans position, on n'invente pas zéro.** Le cap reste magnétique et
l'écran affiche « cap magnétique (déclinaison inconnue) ».

### Le cadran

Le **cadran tourne** de −cap, le repère fixe en haut marque la direction de
visée : c'est le comportement des applications de randonnée, où la
direction dans laquelle on va est toujours en haut.

Vérifié sur une planche de contrôle (`docs/boussole_planche.png`) : la
première version avait la lettre N chevauchant la flèche, la flèche qui
n'atteignait pas les graduations, et « Nord-Ouest » qui débordait du
disque. Trois corrections, dont une mesure de police exacte
(`measureText`) au lieu d'un ratio deviné.

Aucune allocation dans `onDraw` : `Paint`, `Path`, `RectF` et `FontMetrics`
sont créés une fois, les couleurs sont mémorisées à l'initialisation.

### Appareil sans boussole

```
compass_absent : « Boussole absente : cet appareil n'a pas de capteur de rotation »
```

Le message est complété par la raison exacte (`pas d'accéléromètre ni de
capteur de rotation`, `pas de magnétomètre…`). Le cadran est masqué — pas
d'une aiguille figée au nord, qui serait une mesure inventée.

### Bugs trouvés en relisant mon propre code

| # | Défaut | Correction |
|---|---|---|
| 1 | `ThemeManager.color()` appelé **dans** `onDraw` | couleurs mémorisées à l'init — exactement le défaut reproché à SkyView en v5.13 |
| 2 | Cadran rafraîchi à 2 Hz alors que le capteur tire à 30 Hz | rappel `OnHeadingChanged`, le cadran suit la main |
| 3 | `onMeasure` non borné dans un ScrollView | `AT_MOST` vaut toute la hauteur d'écran : cadran borné 160–260 dp |
| 4 | `(float)(NaN / 1000)` pour l'altitude absente | remplacée par 0 (niveau de la mer) avant le modèle WMM |
| 5 | Événement capteur livré après `stop()` | garde `if (!started) return;` |
| 6 | `declinationUnknown` non synchronisé dans le rappel | le cadran n'étiquette plus « NORD VRAI » un cap magnétique |
| 7 | 3 chaînes en dur, dont une **affichée à l'utilisateur** | passées en `strings.xml` |
| 8 | Exemple géographique faux : « +18° au Québec » | la déclinaison y est **négative** (−14°) — corrigé dans le code, la chaîne et les tests |

Le n° 8 mérite d'être souligné : le **calcul** était juste, mais j'avais
écrit une explication fausse dans le commentaire et — plus grave — dans la
chaîne affichée dans les réglages. Un outil qui enseigne un signe erroné à
son utilisateur est pire qu'un outil muet.

---

## Lot 6 — fidélité du nettoyage GPX

Le nettoyage GPX utilise désormais une seconde lecture SAX en flux (`GpxPreservation`)
pour préserver les métadonnées document, les attributs et namespaces de la racine,
les waypoints, routes, plusieurs traces/segments, les champs GPX non géométriques
et les extensions fournisseur. L'ordre des éléments racine est réémis dans l'ordre
source. La sortie conserve la version GPX 1.0/1.1 du fichier, indépendamment de la
préférence courante ; versions explicites inconnues, namespaces non standards ou
incohérences version/namespace provoquent un refus sûr plutôt qu'une conversion
silencieuse.

Les coordonnées, altitudes décimales valides et timestamps ISO sont préservés dans
leur forme source lorsque possible. Aucun horaire n'est inventé. Le GPX original
reste intact ; le fichier nettoyé est un nouveau fichier voisin, supprimé s'il est
incomplet. DTD et entités externes sont refusées. Des limites bornent la taille
source, le nombre de points, la profondeur XML et le budget des fragments
préservés. La préservation est sémantique, pas byte-to-byte : commentaires,
instructions de traitement, indentation et forme CDATA peuvent être normalisés.

- 20 suites JVM · **996 assertions** · 0 échec.
- **16 ressources XML** Android analysées.
- Pas de compilation APK ni de test appareil : Gradle et SDK Android indisponibles.

Détails, limites et fixtures : **`docs/LOT_6_GPX_FIDELITY.md`**.

---

## Lot 7A — fondations de cartographie hors ligne

L'étude cartographique retient Mapsforge et ses fichiers vectoriels `.map`, mais
ne remplace pas encore osmdroid : la version 0.31.0 est la dernière version
upstream consultée, alors que Maven Central référence encore 0.25.0 pour
`mapsforge-map-android`. Aucune dépendance Mapsforge n'est ajoutée tant qu'un
prototype ne peut pas être synchronisé et compilé de façon reproductible.

Le code ajoute `MapDataSource`, `MapsforgeMapHeader` et
`OfflineMapRepository` : stockage app-specific persistant, copie en flux,
annulation/progression, SHA-256 complet, validation préliminaire de l'en-tête
Mapsforge v5, métadonnées et installation sans publication partielle. Le
renderer, le catalogue de régions, l'écran de gestion et le téléchargeur ne sont
pas encore implémentés. La carte actuelle reste en ligne via osmdroid/Mapnik ;
aucun préchargement des serveurs de tuiles OSM n'est réalisé.

- 21 suites JVM · **1 018 assertions** · 0 échec.
- Fixture Mapsforge synthétique limitée à l'en-tête, aucun fichier régional réel
  validé par `MapFile` à ce stade.
- Pas de compilation APK ni de test appareil : Gradle et SDK Android indisponibles.

Étude, choix de sources, licences, architecture, limites et suite prévue :
**`docs/LOT_7_MAPS_OFFLINE.md`**.

## Lot 7D — Sélection géographique et finalisation persistante

- Sélection tactile des régions depuis les frontières `.poly`, recherche et états disponibles/installées/mises à jour.
- Cache `.poly` persistant plafonné à 64 Mio, et dessin des contours échantillonné pour éviter un coût Canvas excessif.
- Finalisation post-téléchargement par WorkManager, révision de dépôt notifiée à `MapActivity`, suppression différée des anciennes cartes après libération des MapFile.
- Documentation : `docs/LOT_7D_MAP_SELECTION.md`.
- Vérifications JVM : 31 suites / 1 133 assertions ; 17 XML valides. Pas de compilation APK dans cet environnement.

---

## Lot 7E — Expérience cartographique et navigation

Voir `docs/LOT_7E_NAVIGATION.md` pour les corrections de projection Web Mercator, les gestes de navigation, le maintien du cadrage pendant les filtres, la recherche dans les catalogues déjà mis en cache, le suivi caméra et le découpage des traces GPX à l'antiméridien.
