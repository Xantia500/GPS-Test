# Analyse de `Aide.docx` face au projet

> Document fourni : ~2 700 lignes de conversation sur la validation GNSS.
> Verdict d'ensemble : **le document est solide sur les principes et faux ou
> dangereux sur plusieurs détails d'implémentation.** Il nous a fait trouver
> un vrai défaut dans notre code. Il propose aussi des choses que je
> **déconseille** — je les liste noir sur blanc.

---

## 1. Ce qui colle parfaitement

| Point du document | Notre état |
|---|---|
| `usedInFix` et non « en vue » | ✅ v5.8 |
| Fix 2D refusé (altitude non résolue) | ✅ v5.8 |
| Timeout sans fix valide → perte de signal | ✅ watchdog 1 s, seuil 5 s |
| Foreground service + `foregroundServiceType="location"` | ✅ v5.2 |
| GPS_PROVIDER seulement, jamais le réseau | ✅ |
| Coupure `<trkseg>` quand le signal est perdu | ✅ v5.6 |
| Minuterie annulée dans `onPause`/`onStop` | ✅ |
| Précision `location.getAccuracy()` plutôt que HDOP | ✅ (50 m au lieu de 15, notre choix) |
| `String.format(Locale.ROOT)` pour le GPX | ✅ vérifié : aucun risque de virgule décimale |
| Marge anti-faux-positif sur le timeout | ✅ 5 s pour un intervalle de 1 s |

---

## 2. La correction que le document rend évidente

### Le nombre minimal n'est pas 4 : c'est **3 + n constellations**

Chaque constellation a **sa propre échelle de temps**. Mélanger GPS et Galileo
ajoute une inconnue : le décalage d'horloge entre les deux systèmes.

```
min = 3 (X, Y, Z) + 1 par constellation réellement utilisée
```

| Configuration | Satellites | Minimum | Verdict |
|---|---|---|---|
| 4 GPS | 4 | 4 | ✅ |
| 3 GPS + 1 Galileo | 4 | 5 | ❌ **refusé** |
| 3 GPS + 3 Galileo | 6 | 5 | ✅ |
| 3 GPS + 1 Gal + 1 Glo | 5 | 6 | ❌ **refusé** |
| 2 GPS + 2 Gal + 2 Glo | 6 | 6 | ✅ |

Notre v5.8 exigeait **4 quel que soit le nombre de constellations**. C'était
trop laxiste : la première ligne du tableau ci-dessus serait passée en
« valide » alors qu'elle ne l'est pas.

**Précision du comptage** : une constellation ne compte que si elle a **au
moins un satellite utilisé**. C'est exactement ce que le document décrit —
un satellite isolé ne coûte pas d'inconnue, donc 3 GPS + 2 Galileo + 1
GLONASS isolé donne 5 équations pour 5 inconnues, et passe.

### Ce que je n'ai **pas** repris : le `+ 1` de marge

Le document propose `3 + n + 1`. Je ne l'ai pas fait, et voici pourquoi :

- Le récepteur ne l'applique pas. Il calcule dès que le système est
  résoluble. Exiger un satellite de plus ferait afficher « pas de signal »
  sur des positions que le téléphone juge excellentes.
- Ce serait un mensonge dans l'autre sens, et l'utilisateur n'a aucun moyen
  de comprendre pourquoi l'app refuse une position que trois autres
  applications acceptent.
- La marge, ici, c'est **la précision** : elle doit rester ≤ 50 m.

Si vous préférez le `+1` du document, c'est **une ligne** :
`return SATELLITE_BASE + n + 1;`

---

## 3. Ce que le document révèle et que nous avions raté

### La fraîcheur de `GnssStatus` — déjà corrigée en v5.8

Le document ne le mentionne pas, mais en cherchant comment compter les
constellations, la question se pose : `GnssStatus` arrive dans un callback
**séparé** du `Location`. Un statut de dix minutes jugerait un fix neuf.

→ Borne de **3 s**, déjà en place.

### En revanche : la distance et le D+ ne sont **pas calculés**

Le document propose un `TrackSummary` avec deux filtres anti-bruit :

| Filtre | Seuil proposé | Pourquoi |
|---|---|---|
| Distance minimale entre points | **> 2 m** | à l'arrêt, le GNSS oscille de ±2–5 m et fabrique de faux kilomètres |
| Hystérésis sur le dénivelé | **> 3 m** | l'altitude brute oscille de ±2–5 m sur terrain plat |

**Constat honnête : notre application n'affiche ni distance totale ni D+.**
Ce n'est donc pas un bug, mais c'est une **absence** que le document rend
visible. Le jour où on l'ajoute, ces deux filtres sont indispensables — sans
eux on annonce 3 km pour une promenade de 400 m.

Notre moteur a déjà la pièce nécessaire : `Decision.distanceM` calcule la
distance à chaque point accepté. Il suffirait de l'accumuler en ignorant les
sauts inférieurs à 2 m, et de **remettre le compteur à zéro à chaque coupure
de segment**.

---

## 4. Ce que le document dit et qui est **faux ou dangereux**

Je les liste parce que les avoir lus ne suffit pas : il faut savoir lesquels
ne pas appliquer.

### ❌ `hdop > 2.5` comme critère bloquant

Trois problèmes :

1. **Android ne fournit pas le HDOP** dans `Location`. Il n'est accessible
   que par les trames NMEA — que beaucoup d'appareils ne livrent pas.
   Le document le reconnaît lui-même deux pages plus loin, en contradiction
   avec son propre pseudo-code.
2. **Le HDOP n'est pas une qualité, c'est une géométrie.** Un HDOP de 4 avec
   des signaux parfaits donne une position moins précise qu'un HDOP de 1,5
   avec du bruit — mais `getAccuracy()` intègre déjà les deux. Le document le
   dit aussi (« l'accuracy remplace avantageusement le HDOP »), puis
   l'oublie.
3. **Un seuil dur sur le HDOP jette des positions réelles.** C'est le même
   défaut que notre limite de 200 km/h de la v5.6 : un seuil qui refuse des
   cas légitimes sans attraper les vrais problèmes.

→ Décision : **on ne bloque pas sur le DOP.** On l'affiche (écran expert),
on ne s'en sert pas comme porte.

### ❌ `fix_mode_3d` via « GSA mode == 3 »

Le document propose de lire le mode 3D dans la trame NMEA GSA. Sous Android,
c'est inutile et fragile : `location.hasAltitude()` dit exactement la même
chose, pour tous les appareils, sans parsing. On garde notre méthode.

### ⚠️ `GNSS_TIMEOUT_MS = 3500`

Le document propose 3,5 s. Nous avons 5 s. **Les deux sont défendables**, mais
la raison invoquée par le document — « 3,5× l'intervalle de 1 s pour éviter
une fausse alerte » — est le bon raisonnement, appliqué avec un facteur
arbitraire. Notre 5 s vient du même raisonnement.

Un détail que le document rate : son timer ne repart **que sur un fix
valide**. Conséquence : dans une zone où le téléphone fournit des positions
à 80 m en continu, son app annonce une perte totale de signal alors que le
récepteur parle. Chez nous, ce cas est `REACQUIRING` (position affichée mais
grisée), pas `NO_SIGNAL`. La distinction est importante pour l'utilisateur :
« rien ne rentre » et « ce qui rentre est mauvais » ne se corrigent pas de
la même façon.

### ⚠️ `accuracy > 15 m → refusé`

Le document propose 15 m **dans son code** et 50 m dans votre consigne. À
15 m, on refuse des positions urbaines parfaitement utilisables, et on
fabrique le même problème que le HDOP : un seuil qui rejette du vrai.

→ On garde **50 m**, comme vous l'avez fixé.

### ⚠️ Le partage de fichiers par `FileProvider`

Le document propose `FileProvider` + `<external-files-path>`. Nous avons
choisi **SAF** (l'utilisateur désigne son répertoire). Les deux sont
valides ; le SAF est plus respectueux de l'utilisateur, le FileProvider plus
simple. **On ne change rien** — ce serait refaire la v5.4.

---

## 5. Ce que le document ne traite pas du tout

| Sujet | Notre état |
|---|---|
| **L'île Null (0,0)** | ✅ rejetée systématiquement (v5.7) |
| **L'heure qui recule** | ✅ époque monotone (v5.8) |
| **Coupure entre deux pertes** | ✅ drapeau consommé |
| **Réacquisition en 3 fixes** | ✅ |
| **Hystérésis de reprise** | ✅ 3 s confirmées |
| **Alerte vocale** | ✅ absente du document |

Le document ne parle **jamais** du (0,0). C'est pourtant le cas que vous
avez rencontré sur le terrain, et celui qui produit les « distances XXL ».
Notre traitement reste le bon.

---

## 6. Résumé des décisions

| Sujet | Décision | Où |
|---|---|---|
| Minimum satellites | **3 + n constellations actives** | `FixQuality.minSatellitesFor` |
| Marge `+1` du document | **non reprise** (1 ligne à changer si vous voulez) | idem |
| Précision max | **50 m**, limite incluse | `ACCURACY_RECORD_MAX_M` |
| HDOP bloquant | **non** — affiché seulement | écran expert |
| Timeout | **5 s** (GPS actif) / 1,5 s (GPS coupé) | `LOSS_TIMEOUT_MS` |
| Distance / D+ | **non calculés** — absence identifiée, filtres 2 m / 3 m à appliquer le jour où | à faire |

---

# Vérification

```
Tests unitaires .................... 497 / 497
  dont FixQualityTest .............. 170
Compilation vrai SDK Android 14 .... 0 erreur
```
