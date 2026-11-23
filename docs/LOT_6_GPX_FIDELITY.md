# Lot 6 — Fidélité du nettoyage GPX

## Objectif

Le nettoyage géométrique retire volontairement des points. Il ne doit pas
supprimer en plus les métadonnées, les autres traces, les routes, les
waypoints, les champs standards non utilisés par le filtre ou les extensions
fournisseur. Le GPX original doit rester strictement intact.

Ce lot porte sur le chemin `GpxParser` → modèle de préservation →
`TrackPostProcess` → `TrackCleaner` → `GpxWriter`. Il ne modifie pas le
fonctionnement du suivi GNSS ni l'import cartographique du lot 5.

## Décision d'architecture

Aucune nouvelle dépendance n'a été ajoutée. Le lecteur SAX natif utilisé au
lot 5 reste le parseur XML. Le nettoyage effectue deux lectures en flux :

1. `GpxParser` construit la vue numérique des points utilisée par le filtre
   géométrique.
2. `GpxPreservation` relit le fichier avec SAX et capture les fragments XML
   non géométriques, les métadonnées et les coordonnées/altitudes textuelles.

Les points provenant des deux lectures sont comparés avant de nettoyer :
nombre, segment, latitude et longitude. La taille et la date de modification
du fichier sont également contrôlées entre les lectures. En cas de divergence,
le nettoyage est refusé ; le fichier original n'est pas touché.

Ce compromis évite de charger le document entier dans une `String`. Les
fragments capturés sont réémis en XML UTF-8 autonome : les déclarations de
namespaces requises sont incluses dans chaque fragment. Le contenu XML est
reconstruit depuis les événements SAX ; ce n'est donc pas une copie octet pour
octet de la mise en forme source.

## Informations préservées

- Version GPX 1.0 ou 1.1 du document source, indépendamment de la préférence
  courante de l'application.
- `creator`, attributs de racine non standards, namespaces et
  `xsi:schemaLocation` source.
- Métadonnées document, y compris auteur, liens, mots-clés, limites et
  extensions.
- Waypoints (`wpt`) et itinéraires (`rte`) complets.
- Ordre des éléments racine, notamment les `<extensions>` qui suivent les
  traces.
- Plusieurs traces `<trk>` séparées, avec leurs noms, commentaires, source,
  type, numéro et extensions.
- Plusieurs segments `<trkseg>` par trace et leurs extensions.
- Pour chaque point conservé : coordonnées d'origine, altitude, heure,
  champs GPX supplémentaires et extensions fournisseur.
- Valeur décimale source des coordonnées et de l'altitude lorsqu'elle est
  valide, ainsi que le texte ISO de l'heure (fraction de seconde et fuseau
  compris).

Les waypoints et routes sont préservés, mais ne sont pas simplifiés : le filtre
ne porte que sur les points des traces (`trkpt`). Les extensions attachées à
un point qui est éliminé par le filtre sont éliminées avec ce point ; toutes
les données attachées aux points retenus sont conservées.

## Horodatages et données numériques

Aucun timestamp n'est fabriqué. Un point sans heure source reste sans élément
`<time>`. Une heure que le parseur ne peut pas interpréter est omise au lieu
d'être remplacée par une date artificielle.

L'altitude est réémise uniquement si sa valeur numérique est finie. Lorsque le
texte source est un décimal valide correspondant à la valeur analysée, sa
forme lexicale est conservée (par exemple `-12.7500000000`). Sinon, le writer
utilise la valeur numérique analysée. Une altitude absente ou non exploitable
n'est pas inventée.

Les attributs `lat` et `lon` sont conservés textuellement lorsqu'ils sont des
décimaux valides et cohérents avec les valeurs analysées. Les champs standards
non utilisés par le nettoyeur (par exemple `magvar`, `geoidheight`, `fix`,
`sat`, `vdop`, `pdop`, `link`) ne sont pas reconstruits depuis des valeurs
approximatives : leurs fragments XML source sont réémis.

## Version et compatibilité

Le format source prime sur le réglage de préférence :

- source GPX 1.0 → sortie GPX 1.0 ;
- source GPX 1.1 → sortie GPX 1.1 ;
- namespace et attribut `version` contradictoires → refus ;
- version explicitement inconnue (par exemple 1.2) → refus ;
- namespace non standard non vide → refus plutôt que conversion silencieuse.

Si le fichier ne déclare ni une version exploitable ni un namespace connu,
le réglage courant (GPX 1.1 par défaut, GPX 1.0 sélectionnable dans les
préférences) sert de repli.

La préférence existante n'a pas été modifiée : `Prefs.readGpxVersion()` garde
GPX 1.1 par défaut et `SettingsActivity` permet toujours de choisir GPX 1.0.
Le lot 6 corrige le nettoyage pour qu'il ne convertisse pas implicitement un
fichier importé selon cette préférence.

## Protection, erreurs et fichiers

- Lecture source limitée à 24 Mio pour le nettoyage.
- Maximum de 250 000 points de trace, 20 000 segments et 20 000 traces.
- Profondeur XML maximale : 64 ; maximum 128 attributs par élément.
- Champ scalaire maximal : 16 384 caractères.
- Maximum de 50 000 éléments racine à préserver.
- Budget cumulé des fragments préservés : 4 194 304 caractères.
- DTD et entités externes refusées par les deux parseurs concernés.
- Le chemin de sortie est réservé avec `createNewFile()` pour éviter une
  collision entre deux nettoyages simultanés.
- La sortie est écrite dans un fichier voisin distinct ; `FileDescriptor.sync()`
  est effectué avant d'annoncer le succès.
- Une sortie partielle est supprimée en cas d'erreur ou de manque mémoire.
- Le GPX original n'est jamais modifié ni supprimé.

Si le budget des métadonnées/extensions est dépassé, l'opération échoue sans
sacrifier silencieusement ces données. L'utilisateur garde donc le fichier
brut et peut l'ouvrir sans nettoyage.

## Limites connues

La préservation est sémantique, pas octet pour octet. Les espaces, indentations,
choix de guillemets, références d'entités déjà résolues et CDATA peuvent être
normalisés. Les commentaires XML et instructions de traitement ne sont pas
réémis. Les valeurs d'éléments inconnus, leurs attributs, leur hiérarchie et
leurs namespaces sont conservés dans les fragments capturés.

Le second passage conserve les champs non géométriques dans des chaînes XML
et `TrackPostProcess` maintient une liste de points en mémoire ; ce n'est pas
un nettoyage à mémoire constante. Les limites ci-dessus bornent les tailles
acceptées, mais les fichiers proches du maximum nécessitent des essais sur
appareils disposant de peu de mémoire.

Les tests du lot ne constituent pas une validation exhaustive par le schéma
XSD officiel GPX ni par JPX. Aucune bibliothèque JPX n'a été ajoutée au projet.
Les jeux d'essai sont des fixtures ciblées générées pour ce lot ; il reste
utile de tester des GPX réels issus de plusieurs fabricants et applications.

## Vérifications réalisées

- 20 suites de tests JVM.
- 996 assertions réussies, 0 échec.
- Fixtures GPX 1.0 et 1.1, plusieurs traces et segments, waypoints, routes,
  métadonnées et extensions inconnues.
- Vérification de l'ordre racine et des attributs/namespaces personnalisés.
- Vérification de la préservation lexicale des coordonnées, altitude et
  timestamps avec fractions et fuseaux horaires.
- Vérification qu'aucun timestamp n'est ajouté à un point qui n'en avait pas.
- Vérification que le GPX source reste strictement inchangé.
- Vérification des versions et namespaces inconnus/incompatibles : le nettoyage
  échoue sans conversion et sans modification de l'original.
- Vérification du refus des DTD et entités externes par le parseur de
  préservation.
- 16 ressources XML Android analysées comme XML bien formé.
- Archive ZIP contrôlée.

La compilation Gradle Android, la génération APK et les essais sur appareils
ne sont pas réalisés dans cet environnement : Gradle/Gradle Wrapper et SDK
Android ne sont pas disponibles ici.
