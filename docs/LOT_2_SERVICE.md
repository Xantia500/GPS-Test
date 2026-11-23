# Lot 2 — Service Android et fiabilité du suivi GNSS

## Périmètre

Ce lot ne modifie pas le moteur de parsing GPX, les mesures GNSS brutes de l'écran Expert, la carte ni le traitement DOP. Il renforce le service qui porte l'acquisition GNSS continue et l'enregistrement existant.

## Décisions de conception

### Foreground Service de localisation

- Le suivi est lancé depuis `MainActivity` visible, après vérification de `ACCESS_FINE_LOCATION` et de l'activation du fournisseur GPS.
- Le manifeste déclare `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION` et `android:foregroundServiceType="location"`.
- À partir d'Android 10, l'appel `startForeground()` fournit le type `FOREGROUND_SERVICE_TYPE_LOCATION`.
- Le service se promeut au premier plan immédiatement dans `onStartCommand()`. La création des workers, la lecture du journal, les préférences et l'enregistrement des receivers sont reportés après cette promotion.
- `ACCESS_BACKGROUND_LOCATION` n'est pas ajouté : l'architecture attend un démarrage explicite depuis une activité visible. Cette permission ne doit pas être ajoutée pour contourner les restrictions de démarrage des FGS.
- `START_NOT_STICKY` est conservé. Une session GPS ne redémarre pas toute seule après la mort du processus. C'est un choix de sécurité et de contrôle utilisateur : au prochain lancement, le journal de session permet de signaler l'interruption et de conserver la trace brute déjà écrite.

### Cadence de position et watchdog

La demande reste réglée à 1 000 ms pour le moment. Sur Android 12+, la requête `LocationRequest` fixe l'intervalle, l'intervalle minimal, la haute précision, l'âge maximal initial à zéro et le délai maximal de batching à zéro. Sur Android 7 à 11, l'API historique exprime l'intervalle minimal et une distance nulle.

**Cela ne garantit pas un callback exactement chaque seconde.** Le récepteur GNSS, Android, les politiques d'énergie, les conditions de réception et le constructeur restent maîtres de la fréquence réelle. Aucun appel supplémentaire ne peut forcer le chipset à produire un fix inexistant. Le futur réglage 1/2/5/10 s pourra réutiliser la même préférence et réabonner le fournisseur sans modifier le modèle de suivi.

Le watchdog distingue maintenant :

1. l'échec réel d'installation d'un abonnement (nouvelle tentative espacée de 5 s) ;
2. le silence des callbacks de position après un abonnement accepté (signalement après 30 s, sans réinitialiser le listener) ;
3. la désactivation du fournisseur ou la révocation de permission (traitement dédié).

Il ne réabonne donc pas en boucle lorsqu'un utilisateur passe dans un tunnel ou entre dans un bâtiment. La fraîcheur GNSS utilisée pour qualifier le statut satellite est calculée à partir de l'intervalle configuré : `max(3 s, 3 × intervalle)`.

Le watchdog et le ticker de notification sont des mécanismes de surveillance, pas une garantie d'exécution à la seconde lorsque le CPU est profondément endormi. Leur cadence réelle doit être vérifiée sur appareil, en particulier avec les modes d'économie d'énergie des constructeurs.

### Notifications et permission Android 13+

- La demande `POST_NOTIFICATIONS` est expliquée au premier démarrage d'un suivi long.
- Un refus, ou l'annulation de la boîte de dialogue, ne bloque pas le suivi GNSS. L'utilisateur reçoit une explication et le choix est mémorisé afin d'éviter une répétition intempestive.
- L'état des notifications système et du canal `gps_tracking` est contrôlé avant démarrage. Si le canal ou les notifications sont bloqués, l'application prévient l'utilisateur que la notification peut ne pas apparaître dans le tiroir.
- La notification conserve une visibilité `PRIVATE`, car elle accompagne un suivi de localisation.
- La notification permet l'arrêt, et sur Android 12+ la suspension/reprise. Les commandes ne sont pas la source de vérité : l'état est décidé par le service.

Refuser `POST_NOTIFICATIONS` n'empêche pas Android de faire fonctionner le FGS de localisation, mais réduit la visibilité et l'accès ordinaire à ses commandes. Les applications actives / Task Manager restent le point de contrôle système.

### Arrêts, redémarrages et sessions interrompues

- `ACTION_STOP` reste un arrêt explicite ; il est le seul chemin qui marque la session comme terminée proprement.
- `START_NOT_STICKY` évite qu'une session de localisation soit relancée automatiquement après destruction du processus.
- `stopWithTask="false"` conserve le service lorsqu'on retire simplement l'activité de l'écran des tâches (comportement distinct de l'action « Arrêter » des applications actives).
- Android 13+ permet à l'utilisateur d'arrêter un FGS depuis Task Manager. Cette action tue le processus et sa pile d'activités sans callback exploitable : on ne tente pas de la contourner. Le journal persistant reste la voie de détection au prochain lancement.
- On ne s'appuie jamais sur `onDestroy()` pour garantir la sauvegarde : Android ne garantit pas ce callback lors d'une terminaison brutale.
- Le booléen `stopRequested` est réinitialisé à chaque nouvelle session et `stopSelf(startId)` protège les arrêts contre les demandes de démarrage rapprochées.
- Le journal est écrit dès le début de la session, y compris avant le premier fix.
- La lecture du journal précédent passe avant la première écriture de la nouvelle session dans la file `ioMeta`. La notice d'interruption n'est pas effacée par le simple démarrage du GPS ; elle reste affichable même si l'utilisateur relance le suivi avant que la lecture asynchrone de l'historique soit terminée.
- Le code ne déduit pas automatiquement la cause précise de la mort du processus à partir de `ApplicationExitInfo.REASON_USER_REQUESTED` : avant Android 14, cette raison pouvait aussi correspondre à une mise à jour de l'application ou à un changement d'état de composant. Le journal fournit l'information utile et vérifiable (« session non terminée proprement ») sans attribuer une cause potentiellement fausse.

### `onDestroy()` et workers

`onDestroy()` ne réalise aucune attente bloquante. Les fermetures GPX sont placées dans la file d'écriture FIFO, les métadonnées dans leur file dédiée, et les workers sont arrêtés après drainage des tâches déjà acceptées. La fermeture du worker de publication est également garantie si la barrière de la file GPX ne peut pas être mise en file.

L'initialisation des trois workers est désormais idempotente et protégée : un échec partiel désenregistre les receivers, arrête les threads créés et termine le démarrage avec une erreur visible plutôt que de laisser un service incomplet actif.

Les callbacks déjà en attente sont ignorés après suspension, arrêt ou destruction. Ils ne peuvent ainsi pas remettre à jour l'interface ou les statistiques d'une session qui n'est plus active.

### `WAKE_LOCK`

Aucun `WAKE_LOCK` explicite n'est ajouté et la permission n'est pas déclarée. `LocationManager` utilise déjà un mécanisme interne de réveil temporaire pour délivrer certains callbacks de localisation ; il ne maintient pas le CPU éveillé indéfiniment. Les callbacks de cette application font un travail court puis délèguent les écritures aux workers. Un `PARTIAL_WAKE_LOCK` permanent consommerait davantage de batterie et ne garantirait toujours pas une position GPS à 1 Hz.

Si des essais réels démontrent une perte de callbacks causée spécifiquement par le sommeil CPU sur un appareil ciblé, on pourra réévaluer un verrou temporaire et borné, avec acquisition/libération tracées. Il n'est pas justifié par défaut dans cette architecture.

## Vérifications de ce lot

Résultats exécutés dans l'environnement de travail :

- **15 suites JVM, 798 assertions réussies, 0 échec**.
- **16 ressources XML** analysées sans erreur.
- Contrôles statiques : ordre `startForeground()` avant initialisation des workers, permissions/type du manifeste, absence de `WAKE_LOCK` et `ACCESS_BACKGROUND_LOCATION`, gardes des cinq callbacks GNSS, aucun `await()`/`join()` bloquant dans `onDestroy()`.
- La compilation Android Gradle et l'APK n'ont pas pu être produits dans cet environnement : aucun `gradle`, `gradlew`, `ANDROID_HOME` ni `ANDROID_SDK_ROOT` n'est disponible. Les tests JVM ne compilent pas le service Android complet et ne remplacent donc pas cette vérification.

Contrôles détaillés :

- Manifest : permissions FGS de base et `location`, type `location`, service non exporté, `stopWithTask=false`.
- Pas de permission `ACCESS_BACKGROUND_LOCATION` ajoutée.
- Pas de permission `WAKE_LOCK`, ni de `PowerManager.WakeLock` explicite.
- Pas d'appel direct à `stopSelf()` en dehors du helper qui utilise `stopSelf(startId)`.
- Les callbacks de localisation, statut GNSS et NMEA sont gardés par l'état `running`, la suspension et la destruction.
- Le parseur XML de toutes les ressources est vérifié séparément, ainsi que les suites JVM.

## À tester obligatoirement sur téléphone

1. Android 13, 14, 15 et 16 : démarrage depuis l'activité visible, vérification de la notification et de l'état FGS (`adb shell dumpsys activity services com.example.gpstest`).
2. Android 13+ : autoriser, refuser, annuler, puis désactiver les notifications ou le canal depuis les réglages. Le GPS doit pouvoir démarrer dans tous ces cas si les permissions de localisation sont accordées.
3. Couper puis réactiver le fournisseur GPS pendant le suivi ; vérifier la perte puis la réacquisition, sans plantage ni faux fix.
4. Retirer la permission de localisation pendant le suivi ; le service doit arrêter proprement la localisation, fermer le GPX et conserver le journal d'interruption.
5. Enregistrement d'une heure écran éteint, en mode normal puis économie d'énergie : examiner les intervalles réels, la qualité GNSS, les trous, les horodatages et l'intégrité du GPX.
6. Arrêter depuis la notification, retirer l'application des récents, puis utiliser l'action système « Arrêter » dans Applications actives. Vérifier que ces trois scénarios n'ont pas le même résultat.
7. Appuyer rapidement sur ARRÊTER puis DÉMARRER ; vérifier qu'aucun nouveau fichier ou journal de session n'est effacé par l'ancienne commande d'arrêt.
8. Simuler un stockage lent ou indisponible et vérifier que l'interface reste réactive et qu'aucun worker ne reste bloqué après arrêt.

Les tests JVM ne remplacent pas ces essais : seul un appareil permet de valider la politique d'énergie réelle et la fréquence d'acquisition fournie par son chipset.
