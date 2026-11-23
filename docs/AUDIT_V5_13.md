# Audit v5.13 — 13 défauts corrigés

Audit demandé : « toutes les fonctions, tout en détail, voir si rien n'est
oublié, que tout est fonctionnel, sans bugs, sans incohérences, avec une
logique d'ingénierie maximale ».

Méthode : 126 passes de `grep` thématique sur les 41 fichiers Java
(~12 850 lignes), chaque constat vérifié en lecture puis par recompilation
(134 classes, 0 erreur) et par les 11 suites de tests (646 assertions).

---

## 1. Perte de données

### 1.1 `ACTION_STOP` sans nettoyage

`stopTracking()` n'appelait `runPostProcess()` que si c'était le bouton
d'arrêt d'enregistrement. Quand l'utilisateur pressait « Arrêter le GPS »
**pendant** un enregistrement, le service s'arrêtait et le fichier
`.tmp_clean` n'était jamais produit : la trace restait brute.

→ `pendingCleanupOnDestroy = true` posé dans `stopTracking()` quand
`state.isRecording()`, et `runPostProcess()` appelé immédiatement.

### 1.2 `quitSafely(300 ms)` face à un nettoyage de ~1 s

Le service se tuait lui-même après 300 ms, le nettoyage tournait sur
`ioMeta` : il était interrompu au milieu de la copie.

→ `CountDownLatch cleanupFinished` + `await(4 s)` dans `onDestroy`.
`countDown()` dans un `catch (Throwable)` autour de `TrackCleaner.clean`
pour ne jamais bloquer si le nettoyage lève.

### 1.3 Suppression avant copie

```java
dest.delete();              // ← détruisait l'ancien fichier
Files.copy(tmp, dest);      // ← pouvait échouer
```

Si la copie échouait : l'ancien fichier nettoyé était perdu **et** le
nouveau absent. Les deux pertes en même temps.

→ Copie d'abord (`copyFileChecked`, taille vérifiée), remplacement
ensuite, `.tmp_clean` conservé en cas d'échec et message
`setCleanResult` honnête.

### 1.4 Troncature silencieuse à 50 000 mesures

L'écran Expert affiche « N mesures capturées ». Au-delà de
`MAX_CSV_LINES`, les lignes étaient jetées sans mention. Un outil de
mesure qui perd des données sans le dire est pire qu'un outil qui en perd.

→ Compteur `csvDropped` ; l'écran affiche
« PLAFOND ATTEINT : N mesures supplémentaires n'ont PAS été enregistrées ».

---

## 2. État et affichage

### 2.1 `modeReject` figé

Après l'arrêt de l'enregistrement, la dernière raison de rejet restait
affichée alors qu'aucun point n'était plus filtré.
→ `state.setModeReject(null)` dans `stopRecording`.

### 2.2 `cleanResult` résiduel

`cleanResult` n'était effacé que par `resetSession()`, appelé seulement
au démarrage d'un enregistrement. Le résultat du nettoyage précédent
restait affiché pendant toute la session suivante.
→ `state.setCleanResult(null)` dans `openRecordingSegment`.

### 2.3 « 0,0 km »

`Units.formatDistance(METRIC, 20)` → `"0,0 km"`, `(METRIC, 50)` → `"0,1 km"`.
Un déplacement de 20 mètres affiché comme nul.

→ Sous 1 000 m : `"%.0f m"`. Impérial sous 1 mile : pieds. Nautique : NM
inchangé.

### 2.4 Trace d'un seul point

`zoomToBoundingBox` sur `minLat == maxLat && minLon == maxLon` : étendue
nulle, calcul de zoom dégénéré. Cas réel : GPX importé à un point.
→ Garde `1e-6°` (≈11 cm) ; si l'étendue est nulle, zoom 16 et centrage.

---

## 3. Performance

### 3.1 `SkyView.onDraw` allouait par satellite et par frame

`String.format("%.0f", snr)` et un `float[]` neuf pour chaque satellite,
à chaque image (jusqu'à 40 satellites).

→ `Integer.toString`, flottants locaux réutilisés, `setTextSize` sorti de
la boucle.

### 3.2 Astronomie recalculée à 2 Hz

`Astro.compute` (position Soleil/Lune) et la phase lunaire sont recalculées
à chaque `uiTick` (500 ms) alors qu'elles ne changent qu'une fois par jour.

→ Mémoïsation par jour local et par lat/lon arrondie à 1e-4° (≈11 m).
Datum/UTM mémoïsés séparément, invalidés si la position bouge de plus de
1e-5°.

### 3.3 Plafond d'attente = risque d'ANR

`await(15 s)` sur le thread principal : le seuil d'ANR est 5 s. Une
protection contre la perte de données pouvait provoquer un plantage.
→ Plafond ramené à **4 s**. Mesure : 1 500 points nettoyés en **127 ms**.

---

## 4. Un faux défaut

Le commentaire de `userDisabled` affirmait que l'annonceur vocal se
taisait hors enregistrement. Le code ne le faisait pas — et **c'était
correct** : l'exigence est que toute perte de réception soit annoncée.
Seul le commentaire a été réécrit. Aucune correction de comportement.

---

## 5. Horodatage : ×5,7

`GpxWriter` appelait `SimpleDateFormat` **par point**, alors que
`GpxParser` documente explicitement ce piège. Remplacé par un calcul
manuel (Hinnant) :

| Mesure | Avant | Après |
|---|---|---|
| Instants produits | 40 010 | 40 010 |
| Identiques à `SimpleDateFormat` | — | **40 010 / 40 010** |
| 200 000 appels | 438 ms | **77 ms** |

---

## 6. Vérifié sans défaut

- `GpxParser` : `<trkpt/>` auto-fermante gérée (branche `selfClosing`).
- Toutes les préférences ont une interface : `DestUri` et `ThemeMode`
  passent par `Destination` et `ThemeManager`.
- Aucune couleur en dur dans les 6 layouts.
- 15 fichiers XML, tous valides.
- Aucun avertissement du compilateur sur nos sources, hors dépréciations
  gardées par `Build.VERSION` (API 24 → 30).
- 8 imports inutilisés retirés.
- `Formatage` des spinners de réglages : `applyModeVisibility()` appliqué
  aussi à la restauration d'état.

---

## 7. Limites assumées

| Limite | Raison |
|---|---|
| `DopCalculator.compare` affiche « — » pour des valeurs égales à l'arrondi | cosmétique |
| Compteurs `pointsWritten`/`pointsSkipped`, getters `getFixAgeMs`, `getPublishedName`, `getPolicy` morts | lisibilité de l'API |
| `StatusHud.clockLabel` : `SimpleDateFormat` 1×/s | hors chemin chaud |
| `MapActivity.onActivityResult` déprécié, pas d'`EXTRA_INITIAL_URI` | fonctionne ; minSdk 24 |
| Numérotation `gpxSegment` compte les fermetures | sans effet à la lecture |
| 12 classes sans test direct | dont `Units`, désormais couverte (section 13) |

---

## 8. État final

| Vérification | Résultat |
|---|---|
| `javac` (SDK 34) | **134 classes, 0 erreur** |
| Tests (11 suites) | **646 / 646** |
| JPX `Bench` (oracle indépendant) | 14 / 14 |
| JPX `Edge2` | 21 / 23 (2 limites connues de JPX 1.6.0) |
| XML | 15 valides |
| Couleurs en dur | 0 |
