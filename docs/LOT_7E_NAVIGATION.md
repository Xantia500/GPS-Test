# LOT 7E — Expérience cartographique et navigation

## Périmètre

Ce lot améliore le sélecteur de régions `.poly`, la navigation dans les catalogues déjà consultés, le suivi caméra de la carte et l'affichage des traces GPX. Il ne remplace pas le moteur Mapsforge ni le catalogue de téléchargement des lots 7A à 7D.

## 1. Projection et sélection géographique

- La vue `MapRegionSelectionView` utilise maintenant une projection Web Mercator cohérente : les coordonnées sont converties en radians et les ordonnées sont calculées avec la fonction Mercator.
- L'emprise longitudinale est calculée circulairement. Les polygones qui traversent l'antiméridien ne sont plus étirés sur presque toute la largeur du monde.
- Le rendu des anneaux déroule les longitudes successives, afin de dessiner correctement les contours traversant ±180°.
- Les tests de sélection et de projection utilisent le même modèle de coordonnées.
- La position du centre longitudinal des polygones est pré-calculée au parsing plutôt que recalculée à chaque image.

### Gestes

- Le pincement à deux doigts conserve le point géographique situé sous le milieu des doigts, y compris lorsque ce milieu se déplace.
- Le déplacement manuel pan la vue.
- Un toucher sans déplacement sélectionne la région sous le doigt ; il ne provoque pas de déplacement artificiel.
- Le bouton **Recentrer** ramène la vue à son cadrage initial pour le dossier chargé.
- Une grille géographique avec graduations de latitude et longitude facilite l'orientation.
- Modifier le filtre de recherche ou l'état des régions ne réinitialise pas le zoom ni le panoramique. Le chargement d'un nouveau dossier, lui, recalcule l'emprise.

**Limite visuelle assumée :** le sélecteur présente les frontières vectorielles `.poly` et une grille sur fond sombre. Ce n'est pas encore un fond topographique ou une carte mondiale comparable à Organic Maps. Aucune tuile raster n'est préchargée ou téléchargée.

## 2. Navigation et catalogue hors ligne

`OfflineMapsActivity` exploite le cache persistant `OfflineCatalogRepository` :

- Chaque index de dossier consulté peut être conservé localement.
- En cas d'échec réseau, l'index local du dossier courant peut être affiché avec un indicateur explicite de copie ancienne.
- La recherche différée interroge les index déjà consultés et permet d'ouvrir les résultats correspondants.
- La recherche hors ligne ne prétend pas connaître l'intégralité du catalogue mondial : elle ne couvre que les dossiers dont l'index a déjà été mis en cache.
- Un changement de dossier invalide l'ancienne requête asynchrone ; une réponse tardive ne peut pas remplacer le contenu du nouveau dossier.
- Le cache de frontières `.poly` du dossier courant peut servir de repli hors ligne. Les frontières en cache sont signalées comme potentiellement anciennes.
- Les états installé/disponible/mise à jour sont recalculés lorsque l'inventaire local est chargé ou change.

Le bouton **Recentrer** et les indications de légende sont disponibles dans l'activité du gestionnaire. La légende précise que les contours viennent des fichiers `.poly` et qu'aucune tuile de fond n'est utilisée.

## 3. Suivi caméra de `MapActivity`

- Le renderer expose un callback `setOnUserInteractionListener`.
- Le moteur osmdroid et le moteur Mapsforge détectent un véritable glissement ou un pincement en tenant compte du seuil tactile Android (`touchSlop`).
- Un simple toucher/clic ne désactive pas le suivi automatique.
- Lors d'un déplacement/zoom manuel, le recentrage automatique est désactivé afin de laisser l'utilisateur explorer la carte.
- La réactivation explicite du suivi invalide la dernière position de caméra connue, de façon à recentrer sans attendre un déplacement significatif du GNSS.
- Les listeners sont retirés à la destruction du renderer.

## 4. Traces GPX et antiméridien

La classe `MapTraceSegmenter` coupe maintenant les polylignes au passage de +180°/-180° et ajoute les deux points d'intersection interpolés. Cela évite une ligne artificielle traversant toute la carte, même pour une trace constituée de seulement deux points qui passent de part et d'autre de l'antiméridien.

`MapActivity` utilise ce découpage uniquement pour la représentation cartographique. Le GPX source et les coordonnées des points enregistrés ne sont pas modifiés. Les intersections synthétiques ne sont pas comptées comme des points GNSS originaux.

## 5. Tests et vérifications

- 34 suites JVM compilées ensemble et exécutées par groupes : **1 163 assertions réussies, aucun échec**.
- Parmi les tests ciblés : projection et emprise circulaire, centre de polygone traversant l'antiméridien, gestes de navigation, découpage GPX au passage de la ligne de changement de date, parsing `.poly`, cache des frontières, recherche dans les index locaux et intégrité du dépôt Mapsforge.
- 17 fichiers XML du projet (manifeste compris) analysés sans erreur.
- Les classes Java modifiées ont fait l'objet d'un contrôle syntaxique structurel complémentaire.

Le script monolithique `tools/run_jvm_tests.sh` dépasse la limite de durée de cette session lorsqu'il exécute toutes les suites à la suite. Les sources de test ont été compilées ensemble, puis les 34 classes de test ont été exécutées par groupes ; le total ci-dessus est la somme de leurs résultats individuels.

## 6. Limites non vérifiées

- Aucun Gradle, Gradle Wrapper ou SDK Android n'est disponible dans cet environnement : la compilation Android complète et la génération d'un APK n'ont pas été réalisées.
- Aucun appareil Android n'a permis de valider les gestes tactiles, les insets, les performances Canvas ou les interactions du renderer Mapsforge.
- Aucun véritable fichier cartographique régional `.map` n'a été utilisé pour un test visuel sur appareil dans ce lot.
- Le sélecteur `.poly` ne possède toujours pas de fond topographique. La navigation hors ligne dans le catalogue se limite aux index déjà consultés et mis en cache.

Ces points doivent être vérifiés sur un environnement Android réel avant de considérer l'expérience cartographique comme prête à publier.
