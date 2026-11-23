# Perte de signal, reprise, et qualité des données

> v5.6. Répond à : « gère-t-on bien la perte de signal ? », « alerte vocale
> toutes les 30 s, désactivable ? », « les données doivent être grisées et
> rien ne doit être enregistré quand la réception est mauvaise ».
>
> **C'est le document le plus important du projet.** Une erreur ici ne
> plante pas l'application : elle produit une trace fausse, en silence.

---

## 1. Ce qui n'existait pas avant, et qui était grave

| Exigence | État v5.5 |
|---|---|
| Coordonnées fp64 | ✅ déjà en `double` |
| Unités métrique/impérial/nautique | ❌ **figées en km/h et mètres** |
| Copie entière 32 bits | ❌ **absente** |
| Détection de perte de signal | ❌ **absente** |
| Grisage des données | ❌ **absent** |
| Alerte vocale | ❌ **absente** |
| Refus des points invalides | ⚠️ **partiel** : seule la condition `!isNaN` |

### Le défaut le plus grave

```java
// AVANT — TrackingService.onLocationChanged()
state.setHasFix(true);          // ← jamais remis à false
if (state.isRecording() && !Double.isNaN(location.getLatitude())) {
    appendPoint(p);             // ← AUCUNE autre vérification
}
```

Conséquences réelles :

1. **Entrer dans un tunnel laissait l'écran afficher une position figée**
   pendant des heures, sans aucun signe d'alerte. `hasFix` n'était jamais
   remis à `false`, et rien ne remarquait l'arrêt des callbacks.
2. **Toute position était écrite** : à 500 m de précision, vieille de dix
   minutes, ou valant (0,0) — l'« île Null ».
3. **La sortie de tunnel écrivait un point faux** : le premier fix après une
   coupure est le pire de tous (horloge non recalée, éphémérides absentes).
4. **La trace reliait l'entrée et la sortie du tunnel par une ligne droite.**
   QGIS, Google Earth et OsmAnd calculaient une distance et une vitesse
   moyennes qui n'ont jamais existé.

---

## 2. La copie entière 32 bits — et une correction sur « Q3.6 »

### Les deux lectures possibles

**Q3.6 au sens BINAIRE** (26 bits entiers, 6 bits fraction) :

```
pas = 2^-6 = 0,015625°  ×  111 320 m/°  ≈  1 740 mètres
```

**1,7 km de résolution.** Inutilisable pour une trace : deux points à 1 m
d'intervalle tombent sur le même entier.

**« ±180.000000 » au sens DÉCIMAL** (3 chiffres avant, 6 après) :

```
pas = 10^-6 °  ≈  0,111 mètre  (11 cm)
180 000 000 tient dans un int32 signé (max 2 147 483 647)
```

**C'est cette seconde lecture qui est implémentée**, et c'est la seule qui
corresponde à ce que vous avez écrit : ±180.000000 **exactement**.

### Ce que contient `FixGeometry`

| Méthode | Rôle |
|---|---|
| `toMicro(double)` | degrés → microdegrés, lève hors ±180° |
| `fromMicro(int)` | retour exact |
| `toLatMicro` / `toLonMicro` | variantes à sentinelle (`MIN_VALUE`), pour le chemin chaud |
| `toBinaryQ3_6` | conservé **uniquement** pour rendre le chiffre 1,7 km vérifiable |
| `distanceMeters` | Haversine |
| `sameFixMicro` | identité stricte de deux positions, sans epsilon |

### Pourquoi garder les deux

Le double (53 bits de mantisse) donne, à 180°, un pas de l'ordre de 1e-15 —
soit 0,0000000000001 m. **Le double est largement suffisant et reste la
référence du moteur.** La version entière ne le remplace pas ; elle sert à :

- comparer deux positions **exactement** (sans epsilon flottant) ;
- stocker 8 octets au lieu de 16 ;
- détecter un point figé (le GPS qui renvoie la même position en boucle) ;
- transmettre une position de façon portable et reproductible.

### Pourquoi Haversine et pas la loi des cosinus

Pour de petites distances — exactement notre cas —, la loi des cosinus
souffre d'une annulation catastrophique : `acos` d'un nombre très proche de 1.
Haversine reste stable jusqu'au centimètre. Un test mesure cette stabilité
(1e-7° ≈ 1 cm).

---

## 3. Les cinq conditions qu'un fix doit remplir

Aucun octet n'entre dans le GPX sans les cinq.

| # | Condition | Pourquoi |
|---|---|---|
| 1 | Coordonnées finies | NaN et ±Inf ne sont pas des positions |
| 2 | Dans les bornes | \|lat\| ≤ 90, \|lon\| ≤ 180 |
| 3 | Pas l'île Null | (0,0) est valide… **sauf** si la précision est mauvaise : signature d'un faux fix |
| 4 | Précision ≤ 50 m et > 0 | une précision de **0,0 m n'existe pas** : c'est une valeur non renseignée |
| 5 | Temps cohérent | pas dans le futur, pas vieux de plus de 10 s |

La condition 3 mérite un mot : **(0,0) est une position parfaitement réelle**
(golfe de Guinée). On ne la rejette que si la précision est mauvaise ou
inconnue. Un test couvre les deux cas.

---

## 4. La reprise — les cinq pièges, et pourquoi c'est difficile

### A. Le premier fix après une coupure est mauvais

Un récepteur qui vient de retrouver des satellites doit recaler son horloge
et télécharger les éphémérides. Le premier fix peut être à des centaines de
mètres.

**→ On exige TROIS fixes consécutifs cohérents avant de réécrire.** L'app
reste en état `REACQUIRING` : elle affiche, elle n'enregistre pas.

### B. Le saut de position est indiscernable d'un vrai déplacement

Après un tunnel, la position peut être 2 km plus loin. Vrai (on a roulé) ou
faux (le fix est mauvais) ? **La vitesse implicite tranche** : au-delà de
200 km/h, le point est refusé.

*Limite assumée* : un TGV peut légitimement dépasser ce seuil. Le seuil est
une constante nommée, et un refus est **signalé**, jamais silencieux.

### C. Le temps GPS peut reculer

En sortie de tunnel, un fix peut porter une heure **antérieure** au dernier
point écrit. Un GPX dont les `<time>` reculent est refusé par certains outils.

**→ On n'écrit jamais un point dont l'heure n'avance pas strictement.**
« Égale » est refusée aussi : un segment de durée nulle n'a pas de sens.

### D. L'antiméridien

De −179,9° à +179,9°, la soustraction naïve donne 358° de distance. Haversine
gère ce cas ; un test le vérifie (0,2° réels ≈ 22 km, pas 39 800 km).

### E. Deux pertes consécutives sans point entre elles

Si l'on insère une frontière de segment à chaque perte, deux pertes qui se
suivent produisent `</trkseg><trkseg></trkseg><trkseg>` : XML valide, mais
segment vide. Le drapeau `pendingSegmentBreak` est donc **consommé** à
l'écriture du premier point, et non relu à chaque point.

---

## 5. Le GPX et les coupures

Sans marqueur, un tunnel de 10 minutes produit **deux points consécutifs dans
le même `<trkseg>`** : l'entrée et la sortie. Tous les outils les relient par
une ligne droite.

```
    <trkseg>
      <trkpt lat="48.8570" lon="2.3526">…</trkpt>   ← entrée du tunnel
    </trkseg>
    <trkseg>
      <trkpt lat="48.8700" lon="2.3700">…</trkpt>   ← sortie du tunnel
    </trkseg>
```

Trois segments dans un `<trk>` signifient « la trace est interrompue ici ».
**C'est la représentation correcte**, comprise par tous les lecteurs.

On n'écrit **aucun point artificiel** : pas de trou à (0,0), pas de point
dupliqué. Uniquement une frontière. 19 tests vérifient que le XML produit
reste équilibré, y compris dans le cas pathologique de deux coupures
consécutives.

---

## 6. L'alerte vocale — et pourquoi « toutes les 30 s » mérite réflexion

Pris au pied de la lettre, « toutes les 30 s » produit :

- passage sous un pont de 8 s → 1 message inutile ;
- tunnel de 4 min → **8 messages identiques** ; l'utilisateur coupe le son
  du téléphone et **n'entendra plus jamais** l'alerte dont il a besoin ;
- forêt pendant 1 h → 120 messages.

### La règle retenue

| Étape | Valeur | Raison |
|---|---|---|
| Grâce | **15 s** | pas d'alerte sous un arbre ou un péage |
| Première annonce | à 15 s | la perte est réelle et durable |
| Rappel | **toutes les 30 s** | comme demandé |
| Plafond | **6 annonces** | au-delà, l'information n'apprend plus rien |
| Reprise | **1 annonce**, après 3 s de confirmation | les satellites reviennent par vagues |
| GPS coupé par l'utilisateur | **silence total** | le lui répéter serait du harcèlement |

Le message de perte **donne la durée** (« depuis 45 secondes ») : c'est
l'information utile, pas la répétition du fait.

**Désactivable** dans Réglages → ALERTES VOCALES, activée par défaut.

### Séparation décision / effet

`LossAnnouncer` décide (**logique pure, 100 % testable**). `VoiceAlert`
prononce. C'est ce qui permet de vérifier la grâce, le plafond et le
réarmement sans aucun téléphone.

### Les pièges de `TextToSpeech`

| Piège | Conséquence | Traitement |
|---|---|---|
| Initialisation asynchrone (100 ms à 2 s) | `speak()` avant `onInit` échoue en silence | demande mémorisée, la plus récente gagne |
| Moteur absent | `onInit` renvoie ERROR ou n'arrive jamais | objet libéré, plus aucun appel |
| Voix française absente | accent anglais ou silence | repli sur la langue par défaut |
| Oubli de `shutdown()` | service lié, fuite mémoire et batterie | appelé à l'arrêt et dans `onDestroy` |

---

## 7. Le grisage

Le conteneur `grpPosition` passe à **35 % d'opacité** dès que le signal n'est
plus valide. La dernière position connue **reste lisible** — utile pour se
repérer — mais visiblement inerte.

**Pourquoi le grisage plutôt que « — » partout** : « — » efface
l'information ; le gris dit « ces valeurs ne sont plus vivantes » **sans les
effacer**. Un utilisateur perdu dans un tunnel veut encore savoir où il est
entré.

La barre de statut distingue trois cas, parce qu'ils ne veulent pas dire la
même chose :

| Situation | Message |
|---|---|
| Jamais eu de position | « Pas de position » |
| Position perdue | **« Signal GPS perdu depuis 12 s »** |
| Position valide | « Position acquise » |

---

## 8. Les bugs d'interaction — ceux qui ne se voient pas dans un test unitaire

| # | Bug | Conséquence |
|---|---|---|
| 1 | Watchdog actif pendant la suspension volontaire | alerte « perte de signal » toutes les 30 s à un utilisateur ayant demandé l'économie de batterie |
| 2 | Reprise après suspension sans réacquisition | **le premier fix après réveil était écrit** — aussi mauvais qu'une sortie de tunnel |
| 3 | `resumeTracking` rouvrait le fichier sans remettre l'état à zéro | fichier neuf commençant par un point faux |
| 4 | Ordre des blocs dans `LossAnnouncer.tick` | la reprise n'était **jamais** annoncée : le point de départ de la confirmation était réinitialisé à chaque tick |
| 5 | Sentinelle `recoveryStartMs == 0` | décalage d'un tick entier sur la confirmation (0 est un instant valide) |

Le n° 4 et le n° 5 sont instructifs : le premier était un **ordre de
conditions** faux, le second une **sentinelle mal choisie**. Les deux ont été
trouvés par les tests, pas par la compilation.

---

## 9. Unités

| Système | Précision | Distance | Vitesse |
|---|---|---|---|
| Métrique (défaut FR) | m | km | km/h |
| Impérial | ft | mi | mph |
| Maritime | m | NM | **nœuds** |

**Le moteur ne connaît que le SI.** Les conversions n'ont lieu qu'à
l'affichage : le GPX et les calculs (distance, vitesse implicite, DOP) exigent
une unité unique. Changer d'unité ne recalcule donc rien.

Les préférences sont lues **à chaque tick**, pas mémorisées dans `onCreate` :
un changement d'unité est visible immédiatement au retour des réglages.

---

## 10. Vérification

```
Tests unitaires ..................... 417 / 417
  dont FixQualityTest ............... 90
  dont GpxSegmentTest ............... 19
Compilation vrai SDK Android 14 ..... 0 erreur
```

Ce qui est **testé sans appareil** : les 5 conditions de validité, les 5
pièges de reprise, la machine à états complète (perte, réacqui-sition,
forceLoss, reset), la politique d'annonce (grâce, rappel, plafond,
réarmement, silence), les conversions d'unités, la structure XML des
coupures.

Ce qui **ne peut pas** être testé ici : l'arrivée réelle des callbacks GNSS,
le comportement de `TextToSpeech`, le rendu du grisage, le comportement sur
un appareil réel dans un tunnel. Ces points exigent un test sur le terrain —
et c'est précisément pour cela que la logique est sortie du service : ce qui
reste dans le service est de l'**effet**, pas de la **décision**.
---

# v5.7 — révision après vos corrections

Trois de mes choix étaient faux. Voici ce qui change, et pourquoi.

## 1. « ±180.000000 en int32 » — ce que ça veut dire exactement

Votre exemple tranche la question :

```
152.321574  →  +152321574        (entier signé 32 bits)
```

C'est donc du **décimal à 6 chiffres**, pas du binaire Q3.6.

| Codage | Pas | Résolution |
|---|---|---|
| Q3.6 **binaire** (26.6) | 2⁻⁶ ° | 1 740 m — inutilisable |
| **décimal ±180.000000** | 10⁻⁶ ° | **0,111 m** — retenu |

±180 000 000 tient dans un `int32` (max 2 147 483 647). Testé, y compris
le refus d'exception hors bornes.

**Pour les POI** : à 4 octets par coordonnée, 500 000 points = 4 Mo en
mémoire, et surtout des **comparaisons exactes** — plus d'epsilon flottant
pour dire « ces deux points sont-ils le même ? ». C'est ce qui rendra un
index spatial (grille, quadtree) fiable.

## 2. Le point (0,0) — vous aviez raison, je m'étais trompé

Ma v5.6 l'acceptait quand la précision annoncée était bonne, en argumentant
que (0,0) est une position réelle dans le golfe de Guinée. **Théoriquement
vrai, pratiquement faux** : en phase d'acquisition un récepteur annonce 5 m
de précision sur un (0,0) qu'il n'a pas résolu.

**Nouvelle règle : (0,0) est TOUJOURS rejeté.** Toujours. Un test le vérifie
avec 8 m, 500 m et sans précision.

Et le scénario que vous décrivez est testé de bout en bout :

```
position établie à Paris → (0,0) du module → vraie position
```

Résultat mesuré : la distance depuis le dernier point valide est de **22 m**.
Sans le rejet, elle aurait été de ~5 000 km, et la vitesse de ~5 000 km/s.

## 3. « Un saut de 2 km peut être valide » — et la vitesse XXL

Vous avez raison sur les deux points, et ils se règlent ensemble :

- La reprise est gérée par la **séquence de 3 fixes**, pas par la distance.
  Un vrai déplacement de 2 km est accepté dès que la réception est validée.
- **La limite de 200 km/h est supprimée.** Un avion de ligne vole à
  900 km/h ; le seuil refusait des trajets réels.

Ce qui reste n'est plus un seuil de plausibilité mais un **détecteur de
bug** : au-delà de Mach 1 **et** si les deux points sont à moins de 30 s
d'écart, on conclut à un défaut du récepteur. Au-delà de 30 s, aucune
conclusion n'est possible — 300 km en une heure, c'est une voiture.

## 4. « Le temps qui recule est-il vraiment possible ? »

**Oui — mais pas pour la raison que je donnais.** La documentation Android
est explicite :

> *« the device's Unix epoch time system clock is not monotonic; it can jump
> forwards or backwards unpredictably and may be changed at any time by the
> user, so this time should not be used to order or compare locations.
> Prefer getElapsedRealtimeNanos() »*
> — developer.android.com/reference/android/location/Location

Ce n'est pas l'horloge des satellites : c'est **celle du téléphone**, qui
peut être recalée par le réseau, réglée à la main, ou changer d'heure d'été.

**Correction décisive** : plus aucune décision n'utilise l'heure murale.
Toutes les durées viennent de `SystemClock.elapsedRealtime()`, qui ne recule
jamais. L'heure murale ne sert plus qu'à **écrire** le `<time>` du GPX.

Et si elle recule malgré tout, l'heure écrite est **forcée croissante** (+1 ms).
On ne jette pas le point : sa géométrie est vraie, seule son étiquette de
temps est douteuse. Perdre une position réelle parce que le téléphone a
synchronisé son horloge serait absurde.

> Le défaut que cela évitait était grave : avec l'ancienne logique, un recul
> d'une heure du téléphone aurait fait **rejeter tous les points comme
> périmés pendant une heure entière**.

## 5. « L'antiméridien ? » — oui, c'est exactement ça

Deux points à 0,001° de part et d'autre de ±180°, à 48°N : **77 m** mesurés.
Un calcul naïf en degrés donnerait 39 000 km, donc une vitesse de
13 000 km/s. Haversine traite le passage par l'angle le plus court, et le
calcul de vitesse l'utilise. Vérifié aussi à 60°N, où les méridiens
convergent (l'erreur y serait maximale avec une formule plate).

## 6. « Je n'ai pas compris le point 3.E »

Il s'agissait de **deux pertes consécutives sans aucun point entre elles** :

```
perte → (rien) → perte
```

Le code insère une frontière `</trkseg><trkseg>` à chaque perte. Deux pertes
de suite produisaient donc :

```xml
</trkseg><trkseg></trkseg><trkseg>
```

XML valide, mais un **segment vide** au milieu. Le correctif : le drapeau est
**consommé** à l'écriture du premier point, il ne se relit pas. Un test
vérifie que même dans ce cas pathologique le document reste équilibré.

## 7. L'échelle de précision — votre question « 50 m, 100 m ? »

| Précision | État | Affiché | Enregistré |
|---|---|---|---|
| ≤ 50 m | OK | oui | **oui** |
| 50 – 100 m | DÉGRADÉ | oui, **grisé** | **non** |
| > 100 m | INUTILE | non | non — **traité comme une perte** |
| inconnue / 0 m | INUTILE | non | non |

Trois paliers plutôt que deux : entre 50 et 100 m la position existe encore
(on la montre, on ne la croit pas) ; au-delà de 100 m elle n'a plus de sens
physique — le récepteur tâtonne, c'est une perte.

Détail trouvé par un test : un fix dégradé **ne compte pas** dans la séquence
de réacquisition. Sinon deux fixes à 80 m suivis d'un fix à 5 m
rétablissaient l'écriture — c'est-à-dire que la trace reprenait sur la foi
de précisément les positions auxquelles on ne fait pas confiance.

## 8. Alerte vocale — la politique est maintenant réglable

| Réglage | Valeurs |
|---|---|
| Alerte de perte | activée / désactivée |
| Rythme | **jamais · 30 s · 1 min · 2 min · 5 min** |
| Plafond | 1 · 3 · 6 · 10 · sans limite |
| Reprise | annoncée / non annoncée |

**Pas de « grâce » séparée** : le premier intervalle *est* le délai de
grâce. « Toutes les minutes » = première alerte à 1 min, puis toutes les
minutes. Un réglage séparé aurait produit des combinaisons absurdes
(grâce 10 s + rappel 5 min).

**« Jamais »** = une seule annonce, dès la détection, puis silence total.

Le message parlé est **court et constant** :
« Signal GPS perdu. » / « Signal GPS valide. »

La durée ne se dit plus : elle **s'écrit à l'écran** — « Signal GPS perdu —
depuis 1 min 45 s ». Vous aviez raison : à l'écran elle ne gêne rien, dans
une phrase parlée elle allonge tout.

**Économie de batterie** : la suspension volontaire ne produit **aucune**
répétition. Silence total — votre précision est appliquée à la lettre.

**Langue du TTS** : on demande le français ; si le moteur ne l'a pas, on
garde la langue par défaut du téléphone plutôt que de forcer une langue
absente (qui donnerait du silence).

## 9. Unités

Métrique (défaut) · Impérial · Maritime — **le moteur reste en mètres**,
la conversion n'a lieu qu'à l'affichage. Changer d'unité ne recalcule donc
rien et ne touche pas au GPX.

---

# Vérification v5.7

```
Tests unitaires .................... 461 / 461
  dont FixQualityTest .............. 134
  dont GpxSegmentTest ..............  19
Compilation vrai SDK Android 14 .... 0 erreur
```
---

# v5.8 — satellites, fix 2D, époque de trace, langue

Quatre points soulevés, dont deux qui touchent le cœur du moteur.

## 1. Satellites UTILISÉS, pas « en vue »

> « un satellite en vue n'est pas toujours utilisé ! »

`GnssStatus.getSatelliteCount()` et `usedInFix()` sont deux choses
différentes. Utiliser le premier serait une faute grossière : on peut voir
dix satellites et n'en utiliser que trois.

**Règle : minimum 4 satellites UTILISÉS.**

Pourquoi exactement 4 : c'est le minimum mathématique pour résoudre
latitude, longitude, altitude et décalage d'horloge — quatre inconnues.
En dessous, le récepteur ne peut pas produire de solution complète ; il en
fabrique une approximative, d'où les erreurs énormes.

Pourquoi pas 5 ou 6 : en forêt ou entre deux immeubles, 4 utilisés est déjà
une réception difficile mais réelle. Exiger 5 ferait afficher « pas de
signal » à quelqu'un qui en a un.

**0 satellites = information indisponible**, pas « zéro satellite ». On ne
rejette pas un fix sur une information ABSENTE — seulement sur une
information qui dit « trop peu ».

### Le piège que cette règle a révélé : la fraîcheur

`GnssStatus` est livré **séparément** du fix, dans un autre callback. Sans
borne d'âge, un statut vieux de dix minutes jugerait un fix neuf : on
accepterait un fix 2D sur la foi d'un « 8 satellites » périmé, ou on
refuserait un bon fix sur la foi d'un « 3 » périmé. Dans les deux cas la
décision serait fausse **et silencieuse**.

→ Un statut de plus de **3 s** est ignoré (0 = inconnu). 3 s : le statut
arrive normalement à chaque seconde GNSS.

## 2. Fix 2D — la source des erreurs énormes

> « il existe des fix 2D et 3D, les 2D sont parfois problématiques »

Un fix 2D **ne résout pas l'altitude** : le récepteur l'a supposée. Les
coordonnées peuvent donc être fausses de plusieurs centaines de mètres.

Le pire : **la précision annoncée peut être excellente**. Android annonce
une précision horizontale ; elle peut valoir 3 m sur un fix 2D
catastrophique. Il est donc impossible de détecter le problème par
l'échelle de précision — il faut le détecter par le **type** de fix.

→ `location.hasAltitude()` faux = fix 2D = **refusé**, quelle que soit la
précision annoncée. Testé avec une précision de 1 m : refusé.

### Corollaire : l'altitude n'est plus effacée

Avant, on écrivait `setAltitude(hasAltitude() ? … : NaN)`. Un fix 2D
effaçait donc l'altitude : l'écran passait de « 35 m » à « — » sans
explication. Or un fix 2D ne dit rien sur l'altitude — il ne dit pas
qu'elle est inconnue, il dit qu'il ne l'a pas mesurée.

→ On **garde** la dernière altitude 3D, et on la grise (le fix 2D étant
refusé, `signalOk` est faux). On ne fabrique pas une valeur.

## 3. Époque de trace — votre formule, appliquée telle quelle

> « on utilise l'heure du premier fix, on place pour référence t=0 une
> lecture immédiate de t0 = getElapsedRealtimeNanos() et à chaque nouveau
> point on ajoute à l'heure du premier point getElapsedRealtimeNanos()−t0 »

C'est exactement ce qui est implémenté :

```
t = heure_du_premier_fix + (elapsed_maintenant − t0)
```

Ce que cela règle **d'un coup** :

| Problème | Statut |
|---|---|
| Le temps peut reculer | **impossible** — les incréments sont monotones |
| Recalage « +1 ms » de la v5.7 | **supprimé** — c'était un pansement |
| Creux/bosses de l'heure murale | **invisibles** dans le fichier |
| Intervalles arrondis par l'horodatage GPS | **exacts** |
| Trace démarrant à 1970 ou à une heure fausse | **non** — ancrée sur le 1ᵉʳ fix |

**Contrepartie assumée** : si l'heure du téléphone est fausse au premier
fix, toute la trace est décalée du même montant. C'est préférable à des
timestamps qui sautent — mais on le **mesure** (`driftMs`) et on le
signale au-delà de 5 minutes.

### Le bug trouvé par le test

Ma première version initialisait `lastEpochTimeMs` à l'heure du premier
fix. Résultat : le premier point, dont l'heure vaut exactement cette
valeur, était considéré comme « pas après le précédent » et forcé à
**+1 ms**. Toute la trace décalée d'une milliseconde, et le compteur de
corrections faux dès le premier point.

→ Sentinelle `Long.MIN_VALUE`. C'est la troisième fois dans ce projet qu'une
**sentinelle mal choisie** crée un décalage d'un cran (après
`recoveryStartMs == 0`).

## 4. Langue de l'alerte : celle du téléphone

> « On devrait plutôt utiliser la langue par défaut du téléphone ? »

Oui. La v5.7 forçait `Locale.FRANCE`, ce qui aurait produit du français
prononcé par une voix espagnole sur un téléphone hispanophone.

**Correction en deux temps :**

1. `VoiceAlert` ne force plus aucune langue ; il vérifie seulement qu'une
   voix existe pour `Locale.getDefault()`.
2. Le texte n'est plus une chaîne Java mais un **identifiant de ressource**
   (`R.string.voice_text_loss`). Android choisit alors la variante
   linguistique tout seul, exactement comme le reste de l'interface. Le jour
   où l'app est traduite, l'alerte l'est aussi — sans une ligne de code.

Cas restant : un appareil dont le moteur n'a **aucune** voix pour sa propre
langue. On ne prononce alors pas un français déguisé : on le signale dans
les réglages.

## 5. Nouveaux défauts (vos valeurs)

| Réglage | Défaut |
|---|---|
| Alerte de perte | **activée** |
| Rythme | **1 minute** |
| Plafond | **sans limite** |
| Choix de plafond | **1 · 3 · 5 · 10 · sans limite** |

Le plafond par défaut à 6 de la v5.6 n'a plus de justification : le rythme
étant réglable, c'est l'utilisateur qui décide. Le plafond reste **proposé**
comme protection, pas imposé.

---

# Vérification v5.8

```
Tests unitaires .................... 497 / 497
  dont FixQualityTest .............. 170
  dont GpxSegmentTest ..............  19
Compilation vrai SDK Android 14 .... 0 erreur
XML validés ........................ 13
Couleurs en dur .................... 0
```
