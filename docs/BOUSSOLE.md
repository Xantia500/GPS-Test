# La boussole (v5.14)

## Pourquoi elle a déménagé du service vers l'Activity

Jusqu'à la v5.13, l'azimut était lu par `TrackingService`. Deux conséquences :

1. **La boussole était morte avant « Démarrer ».** Le capteur n'était
   enregistré que dans `subscribeSensors()`, appelé au lancement du suivi.
   Or un cap sert précisément **avant** de se mettre en route.
2. **Elle consommait pendant tout l'enregistrement.** Une fois le service
   lancé, le capteur de rotation tournait en continu — **écran éteint
   compris** — pendant une randonnée de six heures, pour alimenter une
   valeur que personne ne lisait.

Règle appliquée désormais :

| Capteur | Où | Pourquoi |
|---|---|---|
| position, statut GNSS, NMEA | `TrackingService` | nécessaires à l'**enregistrement**, doivent survivre à l'Activity |
| boussole (`CompassSensor`) | `MainActivity` | sert à l'**affichage**, doit s'arrêter avec l'écran |

Le service ne connaît plus l'orientation du téléphone. `TrackingState` a
perdu son champ `compassAzimuth`, devenu mort.

---

## Le nord magnétique n'est pas le nord vrai

Le capteur donne un cap **magnétique**. L'écart avec le nord géographique
est la **déclinaison**, qui dépend du lieu :

| Lieu | Déclinaison | Convention |
|---|---|---|
| Paris | +1,5° | est positif |
| Vancouver | +15° | est positif |
| Montréal | **−14°** | **négatif** |
| Le Cap | −24° | négatif |
| Tokyo | −7° | négatif |

> ⚠️ Une erreur corrigée en cours de route : les commentaires et la chaîne
> affichée dans les réglages annonçaient « +18° au Québec ». C'est faux, le
> Canada de l'Est est à déclinaison **ouest**, donc négative. L'arithmétique
> du code était juste, l'explication était fausse — corrigée partout.

### Le signe, démontré une fois pour toutes

Hypothèse : D = +1,5°, donc le nord magnétique est à 1,5° **à l'est** du
nord vrai.

| Cas | Azimut magnétique | Direction réelle (vraie) | Formule |
|---|---|---|---|
| on vise le nord magnétique | 0 | 1,5 | 0 + 1,5 = 1,5 ✔ |
| on vise le nord vrai | 358,5 | 0 | 358,5 + 1,5 = 360 → 0 ✔ |
| Le Cap (D = −15), on vise le nord magnétique | 0 | 345 | 0 − 15 = −15 → 345 ✔ |

```
azimutVrai = azimutMagnétique + déclinaison
```

### Le calcul de la déclinaison

`android.hardware.GeomagneticField` embarque le modèle WMM dans le système :
aucune table à maintenir, aucune dépendance, aucune mise à jour à publier.

**Mémoïsation** : recalculé seulement si le jour local change ou si la
position bouge de plus de 0,01° (≈ 1,1 km). La déclinaison dérive de moins
de 0,1° par an — la recalculer à 2 Hz serait exactement le défaut corrigé en
v5.13 sur l'astronomie.

**Altitude absente** : `TrackingState` initialise l'altitude à `NaN`. Or
`(float)(NaN / 1000)` vaut `NaN`, et passer `NaN` dans un modèle
d'interpolation polaire ne donne rien de défini. On remplace par 0 (niveau
de la mer) : l'erreur est inférieure à 0,01° sur les 3 000 premiers mètres.

**Sans position, on n'invente pas zéro.** Le cap reste magnétique et l'écran
l'annonce : « cap magnétique (déclinaison inconnue) ».

---

## Repli et absence de capteur

Trois sources, dans cet ordre :

1. `TYPE_ROTATION_VECTOR` — fusion déjà faite par le système.
2. `ACCELEROMETER` + `MAGNETIC_FIELD` — repli classique, recombiné par nous
   via `SensorManager.getRotationMatrix()`.
3. Aucun des deux → la boussole est déclarée **absente**.

```
Boussole absente : cet appareil n'a pas de capteur de rotation
(pas de magnétomètre ni de capteur de rotation)
```

Le cadran est masqué. Afficher une aiguille figée au nord serait une mesure
inventée, pas une absence de mesure.

---

## Orientation de l'écran

L'azimut du capteur est relatif au « haut » **matériel**. En paysage, le
haut de l'écran devient un autre axe physique : sans correction, le cap est
faux de 90° ou 180°.

Démonstration (une seule règle pour les quatre rotations) : en
`ROTATION_90`, le remappage canonique donne « haut d'écran = −X de
l'appareil ». L'azimut de −X vaut celui de Y plus 180°, et l'azimut de Y
vaut celui de X plus 90°. D'où :

```
capEcran = normalize(capCapteur − 90 × rotation)
```

| Rotation | Capteur | Écran |
|---|---|---|
| 0 | 0 | 0 |
| 90 | 0 | 270 |
| 180 | 0 | 180 |
| 270 | 0 | 90 |
| 90 | 90 | 0 |

Les cinq lignes sont des tests (`CompassTest`).

---

## Inclinaison

`getOrientation()` suppose l'appareil **à plat**. Tenu debout, l'axe « haut »
n'a plus de projection horizontale : l'azimut devient instable et saute.
C'est physique, pas logiciel.

Au-delà de 25° d'inclinaison, l'écran le dit :

```
Appareil incliné à 41° : cap peu fiable, tenez-le à plat
```

---

## Le cadran

- **Le cadran tourne** de −cap ; le repère fixe en haut marque la direction
  de visée. La direction dans laquelle on va est toujours en haut — le
  comportement des applications de randonnée.
- **L'aiguille** est dans la couronne extérieure : la pointe au ras des
  graduations, la base à 0,80 r, donc **jamais** par-dessus les lettres
  (placées à 0,74 r).
- **Le disque central** isole le chiffre du cadran qui passe derrière.
  Sans lui, une graduation vient couper le « 045° » au moment où on en a
  le plus besoin.
- **Aucune allocation dans `onDraw`** : tous les `Paint`, `Path`, `RectF` et
  `FontMetrics` sont créés une fois ; les couleurs sont mémorisées à
  l'initialisation.
- **Seuil de redessin à 0,4°** : le capteur tire à ~30 Hz et oscille de
  quelques dixièmes même à l'arrêt. En dessous du seuil, l'image précédente
  est toujours exacte.

### Ce que la planche de vérification a révélé

Le premier dessin compilait et « semblait » bon. Rendu en image
(`docs/boussole_planche.png`), trois défauts sont apparus :

| Défaut | Correction |
|---|---|
| la lettre N chevauchait la flèche | lettres à 0,74 r, flèche dans la couronne |
| la pointe de la flèche n'atteignait pas les graduations | pointe à `r − 2 dp` |
| « Nord-Ouest » débordait du disque | taille calculée par `measureText`, marge de 15 % |

Le troisième point mérite une note : un simple seuil « abréger si trop
long » ne suffit pas — il ne change rien quand la chaîne dépasse de 3 %.
La taille est donc calculée par le rapport exact
`largeurUtile / largeurMesurée`.

---

## Ce qui est testé (92 assertions)

`CompassTest` couvre tout ce qui peut produire un cap **faux** :

- normalisation : −30 → 330, 370 → 10, 360 → 0, **NaN reste NaN** (un cap
  absent ne doit jamais devenir 0, qui est un cap valide) ;
- écart le plus court : 350 → 10 vaut +20, pas −340 ;
- orientation d'écran : les 6 cas du tableau ci-dessus ;
- déclinaison : Paris +1,5, Montréal −14 (→ 356), Vancouver +15, Le Cap
  −15 (→ 345), et **déclinaison inconnue → cap magnétique, pas de
  fabrication** ;
- rose des vents : les 16 secteurs sont atteignables, aucun trou ;
- formatage : « 007° », « 045° », et **359,7° → 000°** (360 n'existe pas) ;
- déclinaison signée : « +0,0° » et non « 0,0° » — un signe est le seul
  moyen de distinguer une valeur nulle d'une absence ;
- préférences : aller-retour des identifiants, valeur inconnue → défaut
  sans exception ;
- abréviation : « Ouest-Sud-Ouest » → « O-S-O », aucune abréviation plus
  longue que l'original.

---

## Bugs trouvés en relisant le code écrit

| # | Défaut | Correction |
|---|---|---|
| 1 | `ThemeManager.color()` appelé **dans** `onDraw` | couleurs mémorisées à l'init |
| 2 | cadran rafraîchi à 2 Hz alors que le capteur tire à 30 Hz | rappel `OnHeadingChanged` |
| 3 | `onMeasure` non borné dans un `ScrollView` | borné 160–260 dp |
| 4 | `(float)(NaN / 1000)` dans le modèle WMM | remplacé par 0 |
| 5 | événement capteur livré après `stop()` | garde `if (!started) return;` |
| 6 | `declinationUnknown` non synchronisé dans le rappel | étiquette correcte |
| 7 | 3 chaînes en dur, dont une affichée à l'utilisateur | passées en `strings.xml` |
| 8 | « +18° au Québec », géographiquement faux | corrigé partout |

Le n° 1 est le plus instructif : j'avais reproché exactement cette faute à
`SkyView` en v5.13, et je l'ai reproduite ici avant de la voir en relisant.

---

## Limites assumées

- **Pas de correction par gyroscope.** Un filtre complémentaire gyro +
  magnéto donnerait un cap plus stable en mouvement. `TYPE_ROTATION_VECTOR`
  le fait déjà au niveau système sur la plupart des appareils ; le refaire
  par-dessus serait redondant.
- **Pas d'étalonnage guidé.** Android expose
  `SensorManager.SENSOR_STATUS_UNRELIABLE`, qu'on signale à l'écran
  (« Champ magnétique perturbé… »), mais on ne propose pas d'écran de
  calibration en 8. À faire si le besoin se présente.
- **Déclinaison non vérifiable hors appareil.** `GeomagneticField` est un
  stub dans le jar de compilation : les valeurs ne peuvent être contrôlées
  que sur un téléphone. Le calcul est donc testé pour sa **cohérence**
  (signe, rebouclage, absence de fabrication), pas contre une table WMM de
  référence.
