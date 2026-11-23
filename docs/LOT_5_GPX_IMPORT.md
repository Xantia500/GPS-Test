# Lot 5 — Import GPX, parsing XML et robustesse mémoire

## Résumé

Ce lot remplace la lecture GPX par recherche textuelle dans `GpxParser` par un
parseur SAX en flux, sans dépendance externe. L'import de `MapActivity` et le
nettoyage de `TrackCleaner` lisent maintenant les octets directement depuis
leur flux d'entrée : le document XML complet n'est plus converti en `String`
avant son analyse.

Le travail de lecture, de parsing et de préparation cartographique est réalisé
sur le worker GPX. L'interface affiche une progression, propose l'annulation
et ne remplace la trace affichée qu'une fois le nouveau document intégralement
lu, validé et préparé.

## Choix de la technologie

### SAX natif retenu

Le parseur utilise les API `javax.xml.parsers` et `org.xml.sax`, présentes sur
Android depuis les premiers niveaux d'API. Cela évite d'ajouter une bibliothèque
au projet et permet de conserver les tests JVM autonomes.

Le projet contient déjà `docs/VERIFICATION_FUSED_ET_JPX.md`, qui documente
l'étude de JPX : cette bibliothèque constitue un bon oracle de format, mais son
usage Android nécessite de résoudre sa dépendance StAX (`javax.xml.stream`),
qui n'est pas fournie dans le SDK Android. JPX n'est donc pas ajouté à l'APK.
Il pourra rester utilisé comme référence externe lors de tests comparatifs.

`XmlPullParser` aurait également été naturel sur Android, mais SAX permet ici
de partager la même API de parsing et le même moteur de tests entre Android et
JVM, sans ajouter de couche d'adaptation.

### Sécurité XML adaptée à Android

Le parseur n'utilise pas aveuglément les options JAXP propres aux implémentations
Xerces du JDK : l'implémentation SAX/Expat d'Android n'expose pas nécessairement
les mêmes options. La protection s'appuie sur les mécanismes SAX communs :

- `LexicalHandler.startDTD()` refuse toute déclaration `DOCTYPE` avant que son
  sous-ensemble interne puisse être traité ;
- `EntityResolver` refuse toute résolution d'entité externe ;
- le parseur ne lit aucune ressource réseau ou locale référencée par le XML ;
- profondeur XML, nombre d'attributs et longueur des champs texte sont bornés.

Le choix est volontairement *fail-closed* : si l'implémentation SAX ne permet
pas d'installer le gestionnaire lexical, la lecture échoue au lieu de continuer
avec une protection DTD incertaine.

## Capacités du parseur

`app/src/main/java/com/example/gpstest/GpxParser.java` prend en charge :

- GPX 1.0 et 1.1 ;
- namespaces par défaut et préfixés ;
- plusieurs traces, segments, routes et waypoints ;
- extraction des `trkpt` sans confondre `rtept` ou `wpt` ;
- latitude/longitude, altitude négative, heure ISO 8601 avec fractions et fuseaux ;
- champs applicatifs déjà exploités par le projet : vitesse, cap, HDOP et précision ;
- extensions inconnues ignorées sans faire échouer une trace valide ;
- encodages XML déclarés, UTF-8 avec BOM, UTF-16 et encodages compatibles XML
  tels que ISO-8859-1.

Les coordonnées impossibles, non finies, hors plage et `(0,0)` sont écartées
comme demandé par la politique de l'application. Leur nombre est conservé dans
`Result.invalidTrackPoints` pour le diagnostic.

### Limites de protection

| Limite | Valeur |
|---|---:|
| Octets importés depuis la carte | 32 Mio |
| Octets lus par le nettoyage | 24 Mio |
| Points de trace conservés | 250 000 |
| Segments XML | 20 000 |
| Profondeur XML | 64 niveaux |
| Attributs par élément | 128 |
| Texte scalaire capturé | 16 384 caractères |
| Points passés au moteur de rendu | 20 000 |
| Overlays de segments dessinés | 2 000 |

Les limites en octets sont contrôlées pendant la lecture, pas uniquement depuis
la taille annoncée par le fournisseur SAF ou le système de fichiers.

Un document mal formé est refusé. Les points complets reçus avant l'erreur
restent dans le résultat pour le diagnostic, mais la carte ne les affiche pas
comme une trace complète.

## Flux d'import dans MapActivity

1. L'utilisateur choisit un document via SAF.
2. Un worker ouvre le `InputStream` du document.
3. `GpxParser.parse(InputStream, maxBytes, progress, cancellation)` analyse les
   octets en streaming.
4. La progression est relayée au thread UI par `Handler`.
5. `prepareTrace()` calcule le résumé, les limites géographiques et l'échantillon
   cartographique sur le worker.
6. L'interface crée les overlays osmdroid sur le thread UI uniquement.
7. Les nouveaux overlays sont ajoutés avant le retrait des anciens ; en cas
   d'échec de préparation ou de création, la trace précédente est conservée.

Le bouton Annuler active un jeton consulté pendant le parsing et les boucles de
préparation. `onDestroy()` invalide les callbacks déjà postés et interrompt le
worker. Une lecture bloquée à l'intérieur d'un fournisseur SAF ne peut toutefois
être interrompue qu'au rythme auquel ce fournisseur revient de son opération
I/O ; l'annulation ne peut pas forcer un fournisseur externe défaillant à
répondre immédiatement.

Les erreurs de mémoire sont interceptées pendant parsing, préparation et
rendu. Le document source reste inchangé. Le rendu utilise un échantillon borné
pour éviter de créer 250 000 `GeoPoint` et polylignes sur le thread UI.

## Nettoyage des traces

`TrackCleaner.clean()` utilise le même parseur en flux, avec une limite d'entrée
de 24 Mio. La trace originale n'est jamais modifiée. Le nettoyage conserve les
horodatages réels, les champs vitesse/cap/HDOP/précision et les frontières de
segments déjà prises en charge par le pipeline.

La phase de nettoyage conserve encore une liste métier des points et crée une
liste de travail `TrackPostProcess.P`. Le parsing ne crée plus une seconde
copie complète du XML, mais cette phase peut temporairement utiliser davantage
de mémoire qu'un seul tableau de points. Les limites de taille/point et les
protections `OutOfMemoryError` réduisent le risque ; elles ne constituent pas
une garantie d'importer sans limite les traces de plusieurs millions de points.

## Vérifications du lot

- `bash tools/run_jvm_tests.sh` : **19 suites, 966 assertions, 0 échec**.
- Tests du parseur : GPX avec namespaces/extensions, segments multiples,
  waypoints/routes séparés, altitude négative, dates avec fuseau, champs
  applicatifs, coordonnées invalides, DTD/entités externes, XML tronqué,
  annulation, limite d'octets et profondeur excessive.
- Encodages : UTF-8 BOM, UTF-16, ISO-8859-1 et séquence UTF-8 invalide.
- Lecture volumineuse : fixture synthétique de 1 200 points et callbacks de
  progression sur plusieurs étapes.
- Tests de régression JVM du reste du projet conservés.
- **16 fichiers XML Android** (ressources + manifeste) analysés par
  `xml.etree.ElementTree` : tous bien formés.

## Limites restantes / validation terrain

Aucun fichier GPX réel fourni par un autre appareil n'était présent dans
l'archive de travail : les cas externes sont couverts par des fixtures
synthétiques et par les vérifications JPX déjà consignées dans
`docs/VERIFICATION_FUSED_ET_JPX.md`, pas par une campagne nouvelle de fichiers
terrain dans ce lot.

La compilation Gradle Android, la génération d'APK et l'essai sur appareil ne
sont pas réalisés dans cet environnement, qui ne dispose pas du Gradle Wrapper,
de Gradle installé ni du SDK Android. Il reste donc à tester sur un téléphone
Android réel : les providers SAF, la livraison de progression, l'annulation
pendant I/O, les GPX de plusieurs fabricants et les traces proches des limites.
