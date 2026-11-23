# Carte, espacement des points, relecture GPX — v5.10

Trois demandes traitées : l'espacement des points, le bandeau permanent,
et la carte avec relecture des traces.

---

## 1. Espacement des points : « 2 m, c'est très peu » — vous avez raison

Le document proposait un seuil fixe à 2 m. Deux raisons de le refuser :

1. **À 2 m, on ne filtre rien.** Une marche à 5 km/h produit un point
   toutes les 1,4 s → **2 500 points par heure**, dont l'immense majorité
   n'apporte aucune information de forme.
2. **À l'arrêt, un GNSS oscille de ±2 à 5 m.** Un seuil à 2 m est donc
   franchi en permanence par du bruit pur. Il ne peut pas fonctionner.

### Deux critères indépendants

| Critère | Plage | Rôle |
|---|---|---|
| **Distance** minimale | aucune · 10 m → 1 km | évite le bruit à l'arrêt |
| **Temps** maximal | aucun · 1 s → 10 min | évite les trous |

**Un point est écrit dès que l'UN OU L'AUTRE est atteint** — jamais les
deux à la fois. C'est le point que la plupart des implémentations ratent :
en appliquant les deux en ET, une trace devient presque vide dès qu'on
ralentit.

### Pourquoi le critère de temps est indispensable

Sans lui, quelqu'un qui reste immobile vingt minutes n'a **aucun point**
dans son GPX. En rouvrant sa trace, il croit que l'enregistrement s'est
arrêté — et le fichier devient irrégulier pour un lecteur qui attend un
échantillonnage régulier.

### Défauts retenus

**25 m / 10 s.** Une sortie de 2 h en marche rapide fait alors environ
400 points, contre 7 200 sans filtre — pour une trace visuellement
identique.

### Le compteur de points écartés

Sans lui, un utilisateur qui règle « 1 km / 10 min » et obtient 3 points
pour une randonnée de 2 h croit à un bug. L'écran expert affiche
désormais « 142 points écartés », et la politique devient compréhensible.

---

## 2. Bandeau permanent

Votre proposition était :

> `18:01:07 / 3D fix / 4/10 satellites / 57% (en charge/no charge)`

### Ce qui est plus élégant, et pourquoi

```
18:01:07   3D fix   4/10 sats   57% ⚡
```

Trois décisions :

1. **Largeur fixe par champ**, en texte tabulaire. Sans cela la ligne
   *saute* à chaque changement de chiffre et le regard doit la retrouver à
   chaque seconde.
2. **Seul le fix change de couleur** : vert 3D, ambre 2D, rouge aucun.
   Une ligne multicolore est une ligne qu'on n'apprend pas.
3. **Batterie en dernier** : c'est l'information la moins volatile et la
   moins critique. Elle a sa place, pas la vedette.

### Trois états de fix, pas deux

Notre moteur **refuse** les fixes 2D. Mais afficher « No fix » quand le
récepteur fournit une position 2D ferait croire que l'antenne ne capte
rien, et l'utilisateur chercherait le problème au mauvais endroit.

| Affiché | Sens |
|---|---|
| **3D fix** | position complète, enregistrable |
| **2D fix** | position horizontale reçue, **rien n'est écrit** |
| **No fix** | rien du tout |

### Une incohérence gérée

`GnssStatus` peut arriver en retard d'un cycle : « 12 sats utilisés / 10 en
vue » est alors possible. On affiche la valeur cohérente la plus grande
plutôt qu'un `12/10` qui ferait douter de toute l'application.

---

## 3. La carte

### Ce qui a été ajouté

- Une page **Carte** accessible depuis l'écran principal.
- La **position courante**, lue depuis le service (pas de second
  abonnement GNSS : ce serait de la batterie brûlée pour la même donnée).
- **Suivi automatique** activable/désactivable.
- **Relecture d'un GPX** : sélection du fichier, tracé, cadrage
  automatique, résumé (nom, distance, durée, points, segments).

### Les décisions qui comptent

| Décision | Raison |
|---|---|
| **Fonctionne hors ligne** | hors couverture, une carte qui n'affiche rien est inutile — et c'est précisément là qu'on en a besoin |
| `configChanges="orientation\|screenSize"` | sans cela, chaque rotation recharge toutes les tuiles : écran blanc de deux secondes |
| **Une polyligne par segment** | sinon la trace montre un trait droit à travers un tunnel — exactement ce que nos coupures servent à éviter |
| `setType("*/*")` pour le sélecteur | beaucoup de GPX sont enregistrés avec le type générique ; un filtre strict les rendrait invisibles |
| osmdroid, pas Google Maps | pas de Google Play Services, l'app reste utilisable sur un téléphone sans services Google — cohérent avec le refus du `FUSED_PROVIDER` |
| `User-Agent` configuré | OpenStreetMap l'exige dans sa politique d'usage ; sans lui, les requêtes peuvent être bloquées |
| Cache dans `getCacheDir()` | rien à demander à l'utilisateur, rien dans son répertoire personnel |

**Poids ajouté** : une seule dépendance, **560 Ko**.

---

## 4. Le lecteur GPX

### Pourquoi un parseur maison et pas `XmlPullParser`

`XmlPullParser` est la bonne réponse pour un fichier de plusieurs centaines
de milliers de points : il lit en flux. Mais il est **lié à Android**, donc
non testable ici, et il **lève des exceptions** sur un fichier légèrement
malformé — ce qui est le cas de la moitié des GPX trouvés sur Internet.

Le parseur retenu charge tout en mémoire (quelques Mo pour 100 000 points,
acceptable sur un téléphone moderne) et surtout :

- **ne lève jamais** : il renvoie ce qu'il peut lire et dit ce qui a échoué ;
- tolère attributs dans le désordre, ordre variable, auto-fermeture,
  commentaires, namespaces, GPX 1.0 comme 1.1 ;
- **ignore** les extensions propriétaires sans erreur.

### Ce qu'il refuse délibérément

`48,8566` (virgule décimale) est **invalide** en GPX. On ne le répare pas :
« 48,856 » pourrait tout aussi bien être un millier mal formé. On rejette
et on le dit.

### Le test le plus important : l'aller-retour

On écrit un GPX avec **notre** writer, on le relit avec **notre** parser.
Un aller-retour ne pardonne pas : si l'un des deux est faux, les valeurs ne
retombent pas. Le test vérifie latitudes au 1e-9, altitudes, horodatages,
et surtout que **la distance ne traverse pas la coupure** (1 900 m comptés
à tort sinon).

### Deux bugs trouvés par les tests

1. **Décalage de segment.** Le compteur était incrémenté *avant* le premier
   `<trkseg>`, donc tous les points étaient dans le segment 1 et la
   comparaison `prev.segment != p.segment` était vraie dès le premier
   point : la distance du premier segment disparaissait silencieusement.
2. **Deux tests de date faux** — c'étaient mes valeurs attendues qui étaient
   erronées, pas le code. Vérifié avec Python avant de « corriger le code »,
   qui était juste.

---

## 5. Ce qui n'a pas été fait, et pourquoi

| Point | État |
|---|---|
| Tuiles hors ligne (téléchargement préalable) | non — hors ligne, le fond est quadrillé mais position et traces restent affichées. Un vrai cache hors ligne demande un choix de zone et un écran dédié |
| Distance/D+ en direct sur la trace | non — le résumé existe à la relecture. En direct, il faut les filtres 2 m/3 m (voir analyse du document) |
| Défilement de la trace chargée dans le temps | non — serait utile, c'est un curseur à ajouter |

---

# Vérification v5.10

```
Tests unitaires .................... 559 / 559
  dont SamplingAndParsingTest ......  62
  dont FixQualityTest .............. 170
Compilation vrai SDK Android 14 .... 0 erreur
XML validés ........................ 15
Couleurs en dur .................... 0
```

**Ce qui n'est pas vérifiable ici** : le rendu réel des tuiles, la fluidité
du zoom, le comportement de la carte sans réseau, et le sélecteur de
fichiers sur un appareil donné. Ces points exigent un téléphone.
