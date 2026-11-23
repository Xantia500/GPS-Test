# Lot 4 — Fiabilité GNSS, NMEA, DOP et qualité du fix

## 1. Périmètre

Ce lot traite les chapitres 4.2, 4.3 et 4.4 de l'audit, ainsi que le rejet de la coordonnée `(0,0)` en amont du GPX (point 5.1 de la demande de corrections).

Objectifs :

- distinguer les indicateurs de qualité transmis par le récepteur des estimations produites par l'application ;
- calculer un DOP géométrique à partir des satellites marqués `usedInFix` par Android ;
- ne plus rejeter une position horizontale uniquement parce qu'elle ne contient pas d'altitude ;
- ne pas présenter comme 3D une position dont la dimension n'est pas explicitement connue ;
- durcir les validations des phrases NMEA utiles et empêcher la persistance d'informations périmées à l'écran.

Le parseur GPX, la cartographie et l'écran de sélection/graphique des satellites ne sont pas modifiés dans ce lot.

## 2. Parseur NMEA

`NmeaParser` reste une classe Java autonome, sans dépendance Android. Les phrases décodées sont :

| Phrase | Données exploitées |
|---|---|
| GGA | position, qualité du fix, nombre de satellites, HDOP, altitude, géoïde, âge différentiel et identifiant de station |
| GSA | dimension 2D/3D, PDOP, HDOP, VDOP et System ID facultatif |
| RMC | statut A/V, date/heure UTC, position, vitesse, cap, mode et statut de navigation facultatifs |
| GLL | position, heure UTC, statut et mode facultatif |
| VTG | cap vrai/magnétique et vitesses nœuds/km/h |
| ZDA | heure/date UTC et décalage horaire local facultatif |
| GNS | modes multi-GNSS, position, satellites utilisés, HDOP et altitude |
| GST | résidus et indicateurs d'incertitude de la position |

### Intégrité et champs numériques

- Un checksum présent doit être valide. Une phrase sans checksum est tolérée pour les récepteurs OEM qui n'en fournissent pas ; cette tolérance ne s'applique pas à un checksum présent mais erroné.
- Les lignes NMEA brutes continuent d'être conservées dans le flux Expert/CSV indépendamment du succès du parsing.
- Les valeurs `NaN`, `Infinity`, hexadécimales ou les notations non conformes aux nombres NMEA décimaux sont refusées.
- Les coordonnées sont vérifiées selon leur format degrés/minutes, leur hémisphère et leurs limites géographiques.
- Les heures et dates sont contrôlées (y compris le calendrier et les années bissextiles).
- Les valeurs optionnelles vides restent absentes (`NaN`/valeur sentinelle) ; elles ne sont pas remplacées arbitrairement par zéro.
- Les unités GGA/VTG doivent correspondre aux champs renseignés. Les vitesses négatives, les caps hors plage et les DOP négatifs sont rejetés.

Les champs optionnels ajoutés dans les versions NMEA ultérieures restent facultatifs afin de conserver la compatibilité avec les récepteurs qui émettent des versions plus anciennes.

### Statut RMC et qualité GGA

Le statut RMC `A` est requis pour considérer les données comme valides ; `V` est invalide. Le mode facultatif `N` est également considéré comme invalide. Les messages VTG en mode `N` ne fournissent pas de vitesse exploitable.

La qualité GGA est conservée séparément de la dimension GSA :

- GGA décrit le type de solution/correction (GPS, DGPS, RTK fixe/flottant, etc.) ;
- GSA indique le type de fix 1/2/3 (aucun/2D/3D) et fournit les DOP du récepteur.

Le code GGA 9 est présenté comme SBAS/WAAS pour les profils NMEA 4.x. Les équipements peuvent toutefois avoir des particularités de firmware ; le libellé est donc un décodage de référence, pas une garantie de provenance WAAS.

### Coordonnée nulle

La politique du projet est explicite : `(0,0)` est rejeté pour les positions actives/live. Le parseur écarte cette coordonnée pour les phrases de position valides (GGA avec fix, GNS avec mode actif, RMC et GLL). Le pipeline `FixQuality` la rejette aussi directement avant enregistrement GPX. Le compteur de répétitions `(0,0)` reste un indicateur de diagnostic ; il n'est pas nécessaire d'attendre deux occurrences pour rejeter un point.

Cette règle est un choix produit assumé pour un GPS grand public. Elle exclut volontairement le point géographique réel situé dans le golfe de Guinée.

## 3. DOP récepteur et DOP géométrique estimé

Les valeurs affichées sont maintenant explicitement séparées :

- **DOP récepteur** : valeurs GSA rapportées par le récepteur ;
- **HDOP GGA** : solution de repli uniquement lorsque le HDOP GSA frais est absent ; sa source est signalée ;
- **DOP géométrique estimé** : estimation propre à l'application à partir des satellites Android marqués `usedInFix`.

Les valeurs GSA (DOP et dimension) ainsi que la qualité GGA expirent après 5 secondes sans nouvelle phrase valide. Le HDOP GGA a son propre horodatage et ne dépend pas de la disponibilité de GSA. Le résultat calculé est rafraîchi par les callbacks GnssStatus et n'est pas conservé comme s'il était actuel après la disparition des données.

### Filtrage du DOP calculé

Un satellite est retenu si :

- `usedInFix == true` ;
- C/N0 est fini et strictement supérieur à zéro, dans la plage publique Android (jusqu'à 63 dB-Hz) ;
- élévation comprise entre 0 exclu et 90 degrés ;
- azimut compris entre 0 et 360 degrés.

Le même identifiant constellation + SVID n'est compté qu'une fois, même s'il est signalé sur plusieurs porteuses. L'azimut 360° est normalisé à 0°.

### Modèle numérique et limite d'interprétation

Le calcul utilise les trois inconnues de position et un paramètre de biais d'horloge par constellation représentée. Le nombre d'inconnues est donc `3 + nombre de constellations` pour ce modèle simplifié. Si le nombre de satellites retenus est insuffisant ou si la matrice géométrique est singulière, aucun DOP estimé n'est affiché.

Le calcul est **non pondéré** : le C/N0 sert à rejeter des mesures nulles/hors domaine, mais ne donne pas de poids statistique. Cette estimation ne reproduit pas exactement le solveur interne du chipset (pondérations, exclusions, contraintes ou traitement propriétaire des biais inter-systèmes). Elle est un indicateur de géométrie, pas une prédiction directe de l'erreur de position en mètres.

La comparaison UI évite donc de déclarer les deux valeurs « cohérentes » ou équivalentes : elle montre le DOP récepteur et la géométrie `inFix` estimée, côte à côte.

## 4. Fix 2D / 3D et altitude

- Un fix horizontal accepté par Android n'est plus rejeté parce que `Location.hasAltitude()` est faux.
- Une dimension **2D** n'est annoncée que lorsqu'un GSA récent indique `fixType=2`.
- Une dimension **3D** verte n'est annoncée que lorsqu'un GSA récent indique `fixType=3`.
- Quand GSA est absent/périmé, l'interface reste prudente : « Fix horizontal » ou « Fix + alt. », en ambre, jamais « 3D » sur la seule présence d'un champ altitude.
- Un fix explicitement 2D reste exploitable pour latitude/longitude, mais l'altitude n'est pas considérée comme résolue et la balise `<ele>` est omise pour ce point GPX.
- L'ancienne altitude peut rester conservée en mémoire à titre de dernière valeur connue, mais elle n'est pas réutilisée dans le point 2D ni présentée comme une mesure verticale courante.

Le comptage des satellites/constellations reste disponible comme information de diagnostic. Il n'est plus un seuil de rejet du fix Android, car l'application n'a pas accès à toutes les contraintes internes du solveur du récepteur.

## 5. Fraîcheur des informations NMEA

Outre les DOP et la qualité GGA, la vitesse NMEA et l'heure ZDA possèdent maintenant leur propre horodatage monotone :

- vitesse NMEA masquée après 5 secondes sans donnée récente ;
- heure GNSS/ZDA masquée après 10 secondes sans donnée récente ;
- RMC `V`/mode `N`, VTG mode `N`, GGA qualité 0 ou GNS sans mode actif effacent immédiatement la vitesse NMEA affichée.

Cela évite de continuer à montrer indéfiniment une vitesse reçue avant la perte du signal. La vitesse issue de `Location` reste indépendante.

## 6. Tests et contrôles

Exécution :

```bash
bash tools/run_jvm_tests.sh
```

Résultat final dans cet environnement :

- 18 suites JVM ;
- 946 assertions réussies ;
- 0 échec ;
- 16 ressources XML analysées avec `xml.etree.ElementTree`, 0 erreur.

Les tests ajoutés/corrigés couvrent notamment les checksums NMEA absents/invalides, les statuts RMC A/V, les champs et unités, les bornes des dates/heures/coordonnées, les phrases GNS/GST, les codes de qualité GGA, la fraîcheur des DOP et des états NMEA, le filtrage `inFix`, le C/N0, les doublons multibande, l'azimut 360° et le modèle multi-constellation.

### Limites de vérification

Aucune compilation Android complète ni génération APK n'a été exécutée : le projet n'a pas de `gradlew` exécutable dans cette archive, Gradle n'est pas installé dans l'environnement et `ANDROID_HOME` n'est pas défini. Les tests JVM vérifient les classes Java autonomes et les composants couverts par le banc, mais ne remplacent pas une compilation Gradle ni les essais sur téléphone.

À réaliser ensuite sur appareils réels : vérifier plusieurs chipsets, comparer GGA/GSA/NMEA au `Location` Android, observer les transitions 2D/3D, les messages GSA multi-système, l'absence de NMEA et le comportement dans des conditions de signal dégradé.

## 7. Fichiers principaux modifiés

- `NmeaParser.java` — validation des phrases et champs NMEA ;
- `SatInfo.java`, `DopCalculator.java` — filtrage et calcul géométrique `inFix` ;
- `TrackingState.java`, `TrackingService.java` — fraîcheur et consommation des états NMEA/DOP ;
- `FixQuality.java` — clarification du rôle indicatif des satellites et acceptation des fixes 2D ;
- `FixStatePresentation.java` (nouveau), `StatusHud.java`, `MainActivity.java` — états et couleurs 2D/3D, séparation des sources ;
- `app/src/main/res/values/strings.xml` — libellés DOP ;
- `NmeaRobustnessTest.java`, `DopFilterTest.java`, `GnssQualityStateTest.java` — couverture de non-régression.

