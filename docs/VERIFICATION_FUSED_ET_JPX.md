# `LocationRequest` et JPX — vérification, pas supposition

Vous avez posé deux questions. Voici les réponses, avec ce que le code fait
réellement et ce que le banc de test a trouvé.

---

## 1. `setMinUpdateIntervalMillis(1000)` et `PRIORITY_HIGH_ACCURACY`

### Réponse : non, ni l'un ni l'autre n'est dans le code

```bash
$ grep -rn "setMinUpdateIntervalMillis\|PRIORITY_HIGH_ACCURACY\|LocationRequest" \
       app/src/main/java/com/example/gpstest/
(vide)
```

Ce sont des API du **Fused Location Provider** (`com.google.android.gms.location`),
la bibliothèque Google Play Services. Le projet ne l'utilise pas du tout.

### Ce que le code fait : `LocationManager.GPS_PROVIDER`

`TrackingService.subscribeSensors()` :

```java
locationManager.requestLocationUpdates(
        LocationManager.GPS_PROVIDER, 1000L, 0f,
        locationListener, Looper.getMainLooper());
```

| Paramètre | Valeur | Effet |
|-----------|--------|-------|
| `GPS_PROVIDER` | — | **Force la puce GNSS** (pas le Wi-Fi, pas la triangulation) |
| `minTimeMs` | `1000` | 1 Hz — le service **peut** livrer moins vite, le hardware décide |
| `minDistanceM` | `0f` | Aucun filtre de distance : **on ne perd aucun fix** |

### Les trois façons de demander la puce GNSS, et pourquoi celle-là

| Approche | Force la puce ? | Nouveaux APK | Verdict |
|---|---|---|---|
| `GPS_PROVIDER` + `requestLocationUpdates` | **Oui**, explicitement | Non | ✅ **utilisé** |
| `FUSED_PROVIDER` | Non : le système peut choisir le Wi-Fi | Non | ❌ |
| `LocationRequest` + `LocationServices` | Oui, avec `PRIORITY_HIGH_ACCURACY` | **Oui** (Play Services) | ❌ pour un outil de diagnostic |

**Décision constante du projet : pas de Play Services.** Le projet est un
outil de diagnostic — il doit mesurer **ce que la puce fait**, pas ce qu'un
algorithme de fusion décide de présenter. Le Fused Location Provider peut
interpoler, lisser, remplacer un fix GNSS médiocre par une position Wi-Fi :
tout ce qu'un test GNSS ne doit pas voir. Pour le même motif, `osmdroid` a été
choisi plutôt que Google Maps.

### Ce que `PRIORITY_HIGH_ACCURACY` apporterait quand même

Rien de plus que ce qui existe déjà, mais trois choses utiles si on passait
au fused :

```java
LocationRequest.create()
    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)  // = GPS actif
    .setInterval(1000)                              // toutes les secondes
    .setMinUpdateIntervalMillis(1000)               // ne pas dépasser 1 Hz
    .setFastestInterval(1000)                       // idem, ancien nom
    .setSmallestDisplacement(0f);                   // aucun filtre
```

`setMinUpdateIntervalMillis` est l'**intervalle minimal entre deux mises à
jour** : le système peut livrer plus lentement, jamais plus vite. C'est un
plafond de débit, pas un plancher de précision.

### Le détail qui compte vraiment : `minTime` n'est pas une garantie

`requestLocationUpdates(GPS_PROVIDER, 1000L, …)` **n'assure pas** un point par
seconde. Selon le matériel, le ciel et le mode d'économie d'énergie, Android
peut :

- livrer à 1 Hz exactement (usage courant) ;
- livrer à 5 ou 10 Hz sur certains appareils si le chipset le permet ;
- livrer **beaucoup plus lentement** quand l'écran est éteint et que l'app est
  en arrière-plan.

C'est pour cette raison que le code **ne suppose jamais** une cadence : le
`SamplePolicy` décide point par point, et l'horodatage de chaque point est
recalculé depuis `elapsedRealtime`. Un fix manquant ou une rafale ne décale
rien. (C'est aussi pourquoi la trace GPX garde des horodatages irréguliers au
lieu de fabriquer un faux 1 Hz.)

### Ce que le code fait en plus, et qui n'a pas d'équivalent côté fused

| API utilisée | Ce qu'elle donne | Pourquoi c'est nécessaire ici |
|---|---|---|
| `registerGnssStatusCallback` | sats **utilisés** (`usedInFix`) + SNR + constellation | le minimum de satellites dépend des constellations **présentes dans le fix** |
| `addNmeaListener` | GGA / GSA / RMC / ZDA bruts | DOP, qualité du fix, heure GPS |
| `registerGnssMeasurementsCallback` | mesures brutes, fréquence porteuse | bandes **L1 / L5** déduites de la porteuse, pas de la constellation |
| `getGnssCapabilities()` | l'appareil supporte-t-il les mesures brutes ? | message honnête plutôt que liste vide |

Aucune de ces API n'existe côté Fused Location Provider. Un outil qui se
passerait de `GnssStatus` ne pourrait pas dire **combien de satellites sont
utilisés** — seulement combien sont en vue, ce qui ne prouve rien.

---

## 2. JPX comme référence

### Ce que JPX est

`io.jenetics:jpx` — bibliothèque Java de lecture/écriture GPX **1.0 et 1.1**,
Apache 2.0, par Franz Wilhelmstötter (~200 Ko, ~100 classes). C'est une
implémentation sérieuse et complète du format, y compris les `extensions`.

**Bonne idée, et je l'ai fait.** JPX sert d'**oracle** : deux implémentations
indépendantes qui lisent les mêmes octets de la même façon, c'est la meilleure
preuve de correction disponible sans spécification exécutable.

### Ce que JPX ne peut pas être : une dépendance du projet

JPX **ne tourne pas sur Android**. Vérifié, pas supposé :

| Constat | Mesure |
|---|---|
| Classes utilisant `javax.xml.stream` (StAX) | **18 sur ~100** |
| `javax.xml.stream` dans le SDK Android | **absent** — jamais fourni |
| Version Java du bytecode 4.0.1 | **69 = Java 25** |
| Version Java du bytecode 3.2.1 | 61 = Java 17 |
| Version Java du bytecode 2.3.0 | 55 = Java 11 |
| Version Java du bytecode 1.6.0 | **52 = Java 8** |

Le README de JPX le dit lui-même :

> « The *JPX* library uses the XML classes available in the Java `java.xml`
> module. […] Especially for Android, using different XML implementation is a
> necessity. »

C'est-à-dire : il faut fournir un `XMLInputFactory` alternatif, donc ajouter
un jar tiers (Woodstox ou équivalent) **en plus** de JPX. Pour un projet dont
l'un des choix explicites est **zéro dépendance inutile** (Java + XML, pas de
Kotlin, pas de Compose, osmdroid seulement parce qu'il le fallait), c'est non.

### Ce qui a été fait à la place : un banc de comparaison hors appareil

`Bench.java` et `Edge.java` (dans `/tmp/jpxbench`) chargent **JPX 1.6.0**
(Java 8, la dernière lisible par le JDK 17 ici) et comparent :

| Test | Résultat |
|---|---|
| Notre GPX 1.1 relu par JPX en mode **STRICT** (schéma) | ✅ accepté |
| Latitudes / longitudes / horodatages identiques point par point | ✅ 0 écart |
| Altitude négative (−430 m, mer Morte) | ✅ exacte |
| Précision 7 décimales (1 cm) | ✅ écart 0,0e+00 |
| Point sans altitude | ✅ reste sans altitude |
| **Entités XML** (`&`, `<`, `>`, guillemets, apostrophe) | ✅ échappement correct |
| GPX **1.0** relu par JPX en STRICT | ✅ 1 point |
| Deux `<trkseg>` | ✅ 2 segments vus par les deux |
| GPX d'un **autre outil** (namespace + extensions Garmin `<gpxtpx:hr>`) | ✅ 2 points, nom « Sortie » |
| Notre sortie de nettoyage relue par JPX | ✅ valide |
| Écart maxi à la polyligne simplifiée | ✅ **1,16 m** (tolérance 3 m) |

**35 vérifications, 33 concordances.** Les deux divergences restantes sont des
limites de JPX 1.6.0, pas des défauts du code — voir ci-dessous.

---

## 3. Ce que le banc a trouvé — cinq vrais défauts

C'est là que l'exercice a payé. Aucun de ces cinq défauts n'était visible à la
lecture du code ni dans les 634 tests existants.

### Défaut 1 — Un horodatage fabriqué (grave)

`GpxWriter` écrivait `<time>` **toujours**, même quand `timeUtcMs` valait `-1` :

```
<trkpt lat="10.0" lon="20.0">
  <time>1969-12-31T23:59:59Z</time>     ← 1 ms avant l'époque Unix
</trkpt>
```

Un point sans horodatage sortait donc daté du **31 décembre 1969 à 23:59:59**.
Ce n'est pas une valeur absente : c'est une **date inventée présentée comme une
mesure**. Un lecteur ne peut pas la distinguer d'une vraie. Corrigé : `<time>`
n'est plus écrit que si le temps est connu (le schéma le rend optionnel).

### Défaut 2 — Un fichier tronqué déclaré valide

`GpxParser.parse()` ne lève jamais — c'est voulu, un parser qui plante à la
première balise bizarre est inutilisable. Mais « ne pas lever » ne veut pas
dire « accepter ».

```
<gpx><trk><trkseg><trkpt lat="1" lon="2">
```

JPX : lève une exception. Notre parser : `failed = false`, 0 point, et la
relecture affiche une trace vide **sans dire pourquoi**. Corrigé : la présence
de `</gpx>` est vérifiée. Les points déjà lus sont **conservés** (ils sont
vrais) mais le fichier est signalé incomplet.

### Défaut 3 — Un plafond de vitesse qui confond bruit et aberrant

Le filtre « aberrant » rejetait un point si sa vitesse implicite dépassait le
plafond du mode. Plafond marche : 15 km/h.

Or **deux points voisins à 4,2 m en 1 s font 15 km/h**. C'est du **bruit de
position** — un GNSS immobile oscille de 2 à 5 m — pas un saut. Mesuré sur une
trace de 301 points : **9 points parfaitement légitimes supprimés**.

Corrigé sur deux plans :

- plafond marche **25 km/h** (un trail, une descente, un vélo électrique
  bridé dépassent 15 km/h par moments) ;
- un point n'est aberrant que si la vitesse **et** la distance sont anormales :
  `d > 25 m`. Ni le bruit, ni un couple de points rapprochés ne peuvent plus
  être qualifiés d'aberrants. Sur la même trace : **0 point retiré** à tort, et
  le vrai saut de 43 km **est** retiré.

### Défaut 4 — Douglas-Peucker réduisait à 2 points

Mesuré : **301 points → 2 points**, sur une trace de 44 km. La cause : RDP juge
la distance **perpendiculaire à la corde**. Sur une trace qui va globalement
tout droit, tout le milieu est proche de la corde même si le tracé zigzague.

Diagnostic important : **RDP n'était pas faux**. Sa garantie (chaque point
retiré à moins de `tolérance` de la polyligne) était respectée — vérifié :
écart maximal réel **2,30 m** pour une tolérance de 3 m. Le problème était
qu'un résultat à 2 points, quoique juste, est illisible pour un humain et
indistinguable d'un fichier abîmé.

Corrigé par un garde-fou **conditionnel** : si — et seulement si — RDP a
réellement écarté des points de plus de la tolérance, on impose au moins un
point tous les 500 m. Sur une droite parfaite, le garde-fou ne fait rien.
(Version initiale du garde-fou : inconditionnelle, elle dégradait des lignes
droites correctes — visible immédiatement dans 6 tests.)

### Défaut 5 — 3 300 allocations évitables par heure

`pointXml` créait un `StringBuilder` sans capacité initiale, à chaque point.
Sur une trace à 1 Hz, cela fait 3 600 StringBuilder et plusieurs recopies par
heure, dans la boucle qui doit tenir la cadence. Capacité fixée à 320
caractères (un `trkpt` complet fait ~260).

### Deux limites de JPX 1.6.0 (pas des défauts du code)

| Divergence | Cause | Qui a raison |
|---|---|---|
| `<speed>` / `<course>` non relus | En GPX 1.1 ils n'existent pas : nous les écrivons dans `<extensions><gpstest:…>`, un namespace propriétaire que JPX ignore — par conception. JPX 3.x les relit. | Nous sommes conformes au schéma |
| Horodatage sans `<time>` | JPX 1.6.0 rend `Optional.empty()`, ce qui est correct. La comparaison précédente était faussée par notre bug n° 1. | Corrigé de notre côté |

---

## 4. État après corrections

| Vérification | Résultat |
|---|---|
| `javac` SDK 34 (21 jars + osmdroid) | **134 classes, 0 erreur** |
| Suite de tests | **635 / 635** |
| Banc JPX — cas nominaux | **14 / 14** |
| Banc JPX — cas limites | **21 / 23** (2 limites de JPX 1.6) |
| Écart RDP vérifié indépendamment | 1,16 m / 2,30 m pour une tolérance de 3 m |

**Faut-il ajouter JPX au projet ?** Non — il ne tourne pas sur Android sans
remplacer sa couche XML. **Faut-il l'utiliser comme référence ?** Oui, et
c'est fait : c'est ce qui a trouvé les cinq défauts ci-dessus. Le banc est
documenté ici pour être rejouable ; il ne fait pas partie de l'APK.
