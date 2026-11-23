# Lot 1 — fiabilité du flux d'écriture GPX

Ce lot est volontairement limité au pipeline d'enregistrement local GPX et à ses compteurs. Les autres chantiers du projet restent à valider séparément ; ce document ne constitue pas une certification de l'application Android entière.

## Horloges et unités

- `Location.getElapsedRealtimeNanos()` est exprimé en ns ; il est converti une seule fois en ms par division par `1_000_000L`.
- `SystemClock.elapsedRealtime()` est déjà en ms et sert aux intervalles, aux délais et au calcul monotone.
- `Location.getTime()` / `System.currentTimeMillis()` sont en ms depuis l'époque Unix et ne servent qu'à ancrer une date GPX réelle.
- Le GPX n'écrit jamais directement une valeur `elapsedRealtime` : `FixQuality.Tracker` construit une heure UTC à partir d'une ancre murale et du temps monotone écoulé. On évite ainsi de confondre une durée depuis le démarrage du téléphone avec une date Unix.

## Vidage et durabilité

- `BufferedWriter.flush()` toutes les ~2 s lorsque les points arrivent.
- Un flush de garde toutes les ~15 s même si le flux GNSS devient silencieux.
- `FileDescriptor.sync()` environ toutes les 30 s, après flush du buffer Java, et à la fermeture du segment.
- Les opérations de flush/sync/fermeture s'exécutent sur le worker fichier, pas sur le thread UI.

Un `flush()` transfère les caractères du buffer Java vers la couche de flux sous-jacente ; ce n'est pas une preuve de persistance physique. `sync()` demande au système de synchroniser le descripteur, avec un coût possible de latence/énergie. Le compromis de 30 s évite le sync à chaque fix (1 Hz) tout en limitant la fenêtre de données qui n'a pas reçu de demande explicite de synchronisation. Aucune stratégie logicielle ne peut garantir la survie à toute panne matérielle ou à un contrôleur de stockage défaillant.

## Segments et publication SAF

Chaque worker capture un `RecordingSegment` propre à la trace : writer, fichier, version GPX, destination SAF, document et génération de compteurs. La fermeture est postée après les points dans la même FIFO. La publication SAF attend la fermeture, copie un instantané de longueur fixe puis publie cet instantané ; elle ne consulte pas les champs globaux d'un segment qui aurait pu être remplacé.

Les callbacks d'erreur, de durabilité et de publication sont appliqués à l'interface uniquement si l'identifiant **et** la génération correspondent encore au segment courant. Une erreur différée d'une ancienne trace ne doit donc pas remplacer l'état d'une nouvelle.

## Compteurs

- **Acceptés** : points passés par le filtre qualité/échantillonnage.
- **Mis en file** : points pour lesquels `Handler.post()` a accepté la tâche.
- **Sérialisés** : XML du point écrit sans exception dans le `BufferedWriter`.
- Les échecs avant/après mise en file sont distincts ; la profondeur en attente est `queued - written - failedAfterQueue`.
- La file est bornée à 120 points en attente : un stockage bloqué ne fait pas croître sans limite la mémoire.
- `resetSession()` incrémente maintenant aussi la génération ; les écritures en retard d'une ancienne session ne polluent pas les nouveaux compteurs.

« Sérialisé » ne signifie pas « physiquement durable » : cette dernière étape dépend du prochain flush/sync.

## Validation disponible

Commande reproductible depuis la racine du projet :

```bash
bash tools/run_jvm_tests.sh
```

Résultat observé : **15 suites, 795 assertions réussies, 0 échec**.

Ce banc valide des classes Java indépendantes et plusieurs règles métier. Il ne compile pas à lui seul l'application Android complète. Dans cet environnement, aucun `gradlew`, Gradle installé ou Android SDK n'est disponible ; aucun APK, lint Android, test instrumentation ou essai sur téléphone n'a donc été exécuté.
