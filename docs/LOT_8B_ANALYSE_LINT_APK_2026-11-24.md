# GPS-Test — Analyse APK et Android Lint

Date : 24 novembre 2026  
Base analysée : projet `GPS-Test-lot-8B-Android-37.2-Lint-corrige-2026-11-24.zip`, artefact `gps-test-debug-apk.zip` et rapports `android-lint-reports.zip` fournis par l'utilisateur.

## 1. Résultat de la compilation transmis par GitHub Actions

Le journal confirme que les tâches suivantes ont abouti :

- `:app:compileDebugJavaWithJavac`
- `:app:checkDebugAarMetadata`
- `:app:processDebugResources`
- `:app:packageDebug`
- `:app:assembleDebug`

La tâche bloquante du job est `:app:lintDebug`, pas la création de l'APK.

## 2. Vérification de l'APK fourni

- L'archive de livraison ZIP s'ouvre sans erreur.
- `app-debug.apk` est un APK Android valide au format ZIP ; son test d'intégrité ne signale aucune entrée corrompue.
- Taille de l'APK : 8 612 087 octets (environ 8,21 Mio).
- L'APK contient cinq fichiers DEX (`classes.dex` à `classes5.dex`).
- Les métadonnées embarquées indiquent Android Gradle Plugin 9.5.2.
- Un APK Signing Block contenant l'identifiant de signature APK v2 (`0x7109871a`) est présent.

Limite de vérification : `apksigner` (Android SDK Build Tools) n'était pas disponible dans cet environnement. La présence du bloc v2 a été détectée, mais la signature et le certificat n'ont pas pu être validés cryptographiquement avec `apksigner verify`. `jarsigner` seul n'est pas l'outil de validation adapté aux signatures APK v2/v3 ; son message « jar is unsigned » ne permet donc pas de conclure que l'APK Android est non signé.

## 3. Rapport Lint : décompte exact

Les trois formats du rapport ont été examinés : HTML, SARIF 2.1.0 et texte. Le rapport texte termine par :

`6 errors, 210 warnings`

Le résumé d'une autre exécution précédemment cité annonçait 7 erreurs. Le rapport détaillé remis dans cette conversation contient 6 erreurs ; c'est ce décompte qui est retenu pour cette livraison.

### 3.1 Les six erreurs bloquantes

Toutes les six proviennent de `TrackingService.java`, dans le traitement des objets `GnssAutomaticGainControl` :

- `getConstellationType()` : deux appels
- `getCarrierFrequencyHz()` : deux appels
- `getLevelDb()` : deux appels

Ces méthodes ont été introduites en API 33 (Android 13). La récupération de la liste `event.getGnssAutomaticGainControls()` était déjà entourée d'une condition API 33, mais l'exploitation des objets retournés se trouvait en dehors de cette garde. Lint ne considère pas qu'une liste vide en API antérieures protège les appels de méthodes.

### 3.2 Correction appliquée

La boucle de traitement des AGC est désormais explicitement protégée par :

```java
if (options.signal && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    // lecture et sérialisation des GnssAutomaticGainControl (API 33+)
}
```

Les valeurs typées respectent les signatures Android : `getCarrierFrequencyHz()` retourne un `long`, `getConstellationType()` un `int`, et `getLevelDb()` un `double`.

Pour les appareils API 24 à 32, aucune méthode AGC API 33 n'est appelée. La partie AGC reste absente si l'API ou le récepteur ne fournit pas ces données ; les autres mesures brutes GNSS continuent normalement.

Le vérificateur statique `tools/verify_android_project.py` contient maintenant une assertion qui exige cette garde API 33, afin d'éviter une régression.

## 4. Répartition des 210 avertissements

| Diagnostic Lint | Nombre | Commentaire |
|---|---:|---|
| SetTextI18n | 82 | Textes construits dans le code ; à centraliser progressivement dans les ressources. |
| HardcodedText | 25 | Textes directement placés dans les layouts ; localisation à améliorer. |
| SmallSp | 18 | Textes de 8 à 10 sp, principalement sur les écrans Expert et satellites ; à vérifier visuellement et en accessibilité. |
| UnusedResources | 17 | Ressources non référencées statiquement ; vérifier les usages dynamiques avant suppression. |
| ButtonStyle | 16 | Boutons de barres d'action à revoir avec les styles Android/Material. |
| RtlSymmetry | 13 | Padding/marges Start/End incomplets. |
| ApplySharedPref | 10 | `commit()` au lieu de `apply()` ; ne pas remplacer aveuglément, certains états de téléchargement/sécurité peuvent nécessiter une écriture synchrone. |
| PluralsCandidate | 7 | Chaînes avec compteurs ; décider entre ressources pluriel et faux positifs (abréviations). |
| Overdraw | 5 | Arrière-plan de fenêtre et arrière-plan racine potentiellement dessinés deux fois. |
| ClickableViewAccessibility | 3 | Gestes tactiles du moteur cartographique : `performClick()`/sémantique d'accessibilité à compléter. |
| ObsoleteSdkInt | 3 | Tests de version devenus redondants avec minSdk 24 ou l'annotation du helper. |
| UsableSpace | 3 | Le stockage peut tenir compte des données de cache libérables ; recommandation à étudier pour les cartes volumineuses. |
| DefaultLocale | 2 | `toLowerCase()` sans `Locale.ROOT` dans `ExportHelper`. |
| TooManyViews | 2 | Layouts principaux avec plus de 80 vues ; optimisation possible après profilage. |
| Autofill | 1 | Champ de recherche dans le gestionnaire de cartes sans indication Autofill ; analyser le rôle du champ. |
| NestedScrolling | 1 | `ListView` verticale dans `ScrollView` de l'écran principal ; vérifier le comportement de défilement. |
| OldTargetApi | 1 | `targetSdk 36` alors que `compileSdk` est 37.2 ; voir la décision SDK ci-dessous. |
| UseRequiresApi | 1 | Le helper `refreshBandSupport` utilise `@TargetApi(34)` au lieu de `@RequiresApi(34)` ou d'une séparation API-safe. |

Ces avertissements ne sont pas tous de même priorité. Les problèmes d'accessibilité et de défilement, les textes trop petits et la traduction méritent un examen fonctionnel. Les avertissements `ApplySharedPref`, `UnusedResources`, `OldTargetApi` et `Overdraw` ne doivent pas être « corrigés » automatiquement sans comprendre l'intention du code.

Aucun Lint baseline ni suppression générale n'a été ajouté.

## 5. Politique SDK retenue au 24 novembre 2026

Configuration conservée :

- `compileSdk 37` + `compileSdkMinor 2` ;
- `targetSdk 36` ;
- `minSdk 24`.

La documentation Google Play consultée indique que les nouvelles applications et mises à jour mobiles doivent cibler au moins Android 16 / API 36 depuis le 31 août 2026. La documentation Android relative à Android 17 / API 37 annonce la généralisation de l'exigence API 37 pour les nouvelles applications et mises à jour Google Play en août 2027.

Ainsi, `targetSdk 36` reste conforme à l'exigence Play en vigueur le 24 novembre 2026. La recommandation Lint `OldTargetApi` est une alerte d'anticipation, pas une erreur de compilation ni la preuve d'une non-conformité actuelle.

Nous ne montons pas le targetSdk à 37 uniquement pour faire disparaître un avertissement. Cibler API 37 active notamment le nouveau comportement d'orientation/redimensionnement sur grands écrans (smallest width >= 600 dp) : l'application doit alors être testée sur tablettes, pliables et fenêtres redimensionnées. La migration vers target 37 doit être un chantier dédié après adaptation et validation de l'interface.

`minSdk 24` est conservé pour maintenir la compatibilité Android 7.0 ; la politique Google Play targetSdk ne contraint pas à relever ce minimum.

Sources officielles :

- Google Play target API requirements : https://support.google.com/googleplay/android-developer/answer/11926878
- Android 17 et échéance API 37 : https://developer.android.com/blog/posts/prepare-your-app-for-the-resizability-and-orientation-changes-in-android-17
- `GnssAutomaticGainControl` (méthodes API 33) : https://developer.android.com/reference/android/location/GnssAutomaticGainControl

## 6. Vérifications locales après correction

Résultats obtenus sur le projet source corrigé :

- Vérificateur statique Android : 17 XML et 80 sources Java contrôlés, OK.
- Contrôle de l'architecture cartographique hors ligne : 98 fichiers analysés, aucun moteur/fournisseur de tuiles raster réintroduit, OK.
- Tests JVM : 38 suites, 1 288 assertions, 0 échec.

Limite : ces contrôles ne remplacent pas un nouveau `:app:lintDebug` dans GitHub Actions. Le nombre d'erreurs Lint après correction doit être confirmé par un nouveau build CI.

## 7. Prochaine action

1. Appliquer le correctif `CORRECTIF_LINT_AGC_API33_2026-11-24.zip` au dépôt local avec GitHub Desktop.
2. Commit et Push.
3. Récupérer la nouvelle exécution GitHub Actions.
4. Vérifier si les six erreurs `NewApi` ont disparu et examiner le rapport Lint suivant.
5. Traiter les avertissements par lots cohérents, sans baseline global.
