# Foreground Service — anatomie, pièges et vérifications

> Compagnon de `CYCLE_DE_VIE.md`. Ce document-ci ne parle que du **service** :
> ce que le système exige, ce qu'il fait vraiment, et les onze défauts réels
> trouvés en relisant le code ligne par ligne.

---

## 1. Le contrat imposé par Android (non négociable)

| Exigence | Depuis | Ce qui arrive si on l'ignore |
|---|---|---|
| `startForeground()` dans les **5 secondes** après `startForegroundService()` | API 26 | `ANR` puis `Context.startForegroundService() did not then call Service.startForeground()` — **crash à la seconde près** |
| permission `FOREGROUND_SERVICE` | API 28 | `SecurityException` au lancement |
| permission `FOREGROUND_SERVICE_LOCATION` | API 34 | `SecurityException` au lancement |
| `android:foregroundServiceType="location"` au manifest | API 29+ (obligatoire 34) | `MissingForegroundServiceTypeException` |
| `startForeground(id, notif, TYPE_LOCATION)` | API 29+ | type implicite → refusé sur 34 |
| permission de localisation **déjà accordée** | toujours | `SecurityException` (type location) |
| notification avec **small icon valide** | toujours | `RuntimeException: invalid notification` |
| `stopForeground()` avant `stopSelf()` | toujours | **notification fantôme** dans le volet, jusqu'au reboot |
| receiver enregistré avec un flag d'export explicite | API 34 | `SecurityException` |

**Conclusion pratique** : un Foreground Service n'est pas « un service avec une
notification ». C'est un contrat dont chaque clause, violée, produit un crash
ou une notification zombie. Il n'y a pas de zone grise.

---

## 2. Ce que le système garantit — et ce qu'il ne garantit pas

| Situation | Le service survit ? | `onDestroy` appelé ? |
|---|---|---|
| Écran éteint | **oui** | non |
| Changement d'app | **oui** | non |
| Rotation | **oui** (indépendant de l'Activity) | non |
| App retirée des récents (`stopWithTask="false"`) | **oui** | non |
| Mémoire basse | parfois non | **parfois** |
| Nettoyeur OEM (Xiaomi, Huawei, Samsung) | **non** | **non** |
| `kill -9` / batterie arrachée | **non** | **non** |
| Utilisateur → « Forcer l'arrêt » | non | non |

**La ligne du milieu est la plus dangereuse** : `onDestroy` est *parfois* appelé,
sans moyen de savoir à l'avance. Un code qui repose sur `onDestroy` pour
sauvegarder est donc un code qui perd des données **une fois sur deux**.

C'est précisément pourquoi l'écriture GPX est **continue + flushée à chaque
point**, et pourquoi le journal de session est écrit **aux transitions**.

---

## 3. Les onze défauts trouvés en relecture (v5 → v5.1)

Chacun était réel. Aucun n'était visible à la compilation.

### 🔴 1. `onDestroy` effaçait le journal dans tous les cas

```java
// AVANT
public void onDestroy() {
    unregisterScreenReceiver();
    stopTracking();   // <- clearSessionJournal() à l'intérieur
    super.onDestroy();
}
```

Quand le système tue le service, `stopTracking()` marquait la session comme
« terminée proprement » et **supprimait le journal**. Le message
« session précédente interrompue » ne pouvait donc **jamais** s'afficher : le
mécanisme était mort-né, tout en donnant l'illusion d'exister.

**Correction** : un booléen `stopRequested`, mis à `true` uniquement par
`ACTION_STOP`. Dans `onDestroy` : arrêt voulu → on efface ; arrêt subi → on
laisse `dirty=true`.

### 🔴 2. `startForeground()` non protégé

Sur API 34, si l'utilisateur retire la permission de localisation pendant la
session, `startForeground(…, TYPE_LOCATION)` lève `SecurityException` → **crash**.
Idem si l'app est en arrière-plan sans exemption.

**Correction** : `try/catch` autour de l'appel, avec message utilisateur exploitable
et `stopSelf()` propre.

### 🟠 3. Notification orpheline si `startTracking` échoue

`startTracking()` appelait `stopSelf()` quand la permission manquait ou que le
GPS était coupé — alors que `startForeground()` venait d'être appelé. Un service
qui s'arrête sans `stopForeground()` laisse sa notification **dans le volet**,
souvent jusqu'au reboot.

**Correction** : `stopForegroundCompat(true)` avant chaque `stopSelf()`, et un
message d'erreur lisible au lieu d'un arrêt silencieux.

### 🟠 4. La notification ne se rafraîchissait que s'il y avait un fix

`maybeUpdateNotification()` n'était appelé que depuis `onLocationChanged`.
À l'intérieur d'un bâtiment, dans un tunnel, ou sans fix : **aucun callback**,
donc la notification restait figée sur « 0 satellites » pendant des heures.

**Correction** : un ticker de 1 s (`notificationTick`) indépendant des capteurs,
plus une **signature de contenu** pour ne pas envoyer un IPC par seconde quand
rien n'a changé.

### 🟠 5. L'écriture GPX se faisait sur le thread principal

`FileWriter.write()` + `flush()` dans `onLocationChanged`, lui-même livré sur
`Looper.getMainLooper()`. Sur un stockage lent, c'est `ANR` garanti à terme.

**Correction** : un `HandlerThread` dédié (priorité `THREAD_PRIORITY_BACKGROUND`),
avec **deux files distinctes** — les points (ordre strict) et le journal
(jamais bloqué derrière une rafale de points).

### 🟠 6. Le pied GPX pouvait être écrit avant les derniers points

Conséquence directe du point 5 : si les points passent par un autre thread, la
fermeture doit y passer aussi, sinon le `</trkseg></gpx>` part avant le dernier
`<trkpt>` et le fichier est **corrompu**.

**Correction** : `closeRecordingSegment()` détache le flux immédiatement (plus
aucun point accepté) puis poste la fermeture dans **la même file FIFO**. L'ordre
est garanti par la file, pas par la chance.

### 🟠 7. `smallIcon(R.mipmap.ic_launcher)` = tache blanche

Android ne conserve que le **canal alpha** d'une small icon et la teinte en
blanc. Une icône d'application (colorée, détaillée) devient un pâté illisible.

**Correction** : `ic_stat_gps.xml`, un vecteur monochrome de 24 dp.

### 🟡 8. `startForegroundService()` utilisée pour **arrêter**

`stop()` et `stopRecording()` passaient par `startForegroundService()`, ce qui
**oblige** le service à se déclarer en premier plan dans les 5 s — absurdité
pour un arrêt, et source de course.

**Correction** : `startService()` pour les arrêts, `startForegroundService()`
uniquement pour les démarrages.

### 🟡 9. `startForegroundService()` sans garde API 26

`minSdk = 24`. Sur Android 7, l'appel lève `NoSuchMethodError` — et seulement
au moment d'appuyer sur DÉMARRER. Régression que j'ai introduite puis corrigée
dans la même passe : `startCompat()` avec `Build.VERSION.SDK_INT >= O`.

### 🟡 10. L'Activity écrivait un état que le service possède

`btnToggle` faisait `state.setUserWantsTracking(false)` en plus de demander
l'arrêt. Deux écrivains pour un même champ, sans verrou : un appui
ARRÊTER→DÉMARRER rapide créait une seconde session pendant que la première se
fermait.

**Correction** : l'Activity **demande**, le service **décide**. Plus aucune
écriture d'état possédé par le service depuis l'UI.

### 🟡 11. `subscribeSensors()` / `unsubscribeSensors()` non idempotents

Appelables dans n'importe quel ordre depuis trois sources (démarrage, écran,
réglage). Aucun drapeau ne garantissait la cohérence « abonné / suspendu ».

**Correction** : un champ `subscribed` explicite, et les deux méthodes
deviennent des no-op si l'état est déjà le bon.

---

## 4. Le cas qui a demandé le plus de soin : la notice d'interruption

Trois fins de session, et il faut les distinguer **exactement** :

| Fin | `dirty` | `expectedAlive` | Notice affichée |
|---|---|---|---|
| Bouton ARRÊTER | false | — | non |
| Session **vivante** (écran rallumé, retour dans l'app) | true | true | **non** |
| `kill -9` pendant une session active | true | true | **oui** |
| Pause volontaire (économie batterie) | false | false | non |
| `kill -9` pendant une pause volontaire | false | false | non |

Deux faux positifs ont été trouvés **par simulation**, pas par lecture :

1. Ouvrir l'app pendant une session vivante lisait le journal de cette session
   et annonçait « interrompue ». → garde `!state.isUserWantsTracking()`.
2. Un simple passage écran éteint en mode économie annonçait « interrompue ».
   → champ `expectedAlive`, qui rend une pause volontaire explicitement
   **non** interruptive.

**La leçon** : un message d'alerte qui se déclenche à tort devient du bruit, et
le jour où il a raison, personne ne le lit. La précision d'une alerte fait
partie de sa fonction.

---

## 5. Table de vérification (ce qui est contrôlé, et comment)

| Vérification | Méthode | Résultat |
|---|---|---|
| 28 fichiers Java parsent | `javalang` (AST complet) | ✅ 28/28 |
| `startForeground` avant tout traitement | analyse AST de l'ordre des appels | ✅ index 3 < 12 |
| `try/catch` autour de `startForeground` | présence d'un `TryStatement` | ✅ |
| `onDestroy` n'appelle plus `stopTracking` | analyse des invocations | ✅ |
| Toutes les actions ont une sortie | inspection des 6 blocs `if` | ✅ 6/6 |
| Fermeture idempotente | `gpxStream == null` en garde | ✅ |
| Journal : aucun faux positif | simulation de 6 scénarios | ✅ 6/6 |
| Journal : format robuste | 58 tests unitaires | ✅ 58/58 |
| Politique d'écran : table de vérité | 31 tests unitaires | ✅ 31/31 |
| IDs Java ⇄ XML | 66 = 66 | ✅ aucun orphelin |
| strings / drawables / layouts | croisement code ↔ ressources | ✅ 0 manquant |
| Tests purs | Utils 26 · Advanced 81 · DopFilter 52 · Policy 31 · Journal 58 | ✅ **248/248** |

---

## 6. Ce qui ne peut PAS être vérifié ici

Aucun SDK Android dans cet environnement : **pas de compilation, pas d'APK**.
Tout ce qui précède est de l'analyse statique et de la simulation — utile, mais
pas équivalent à une exécution.

À valider obligatoirement sur un téléphone :

- [ ] Lancer une session → vérifier que la notification apparaît **immédiatement**
- [ ] Vérifier l'**icône** dans la barre de statut (elle doit être un dessin net, pas une tache)
- [ ] `adb shell dumpsys activity services com.example.gpstest` → `isForeground=true`, `foregroundServiceType=location`
- [ ] Couper le GPS système pendant une session → message clair, **pas de crash**
- [ ] Retirer la permission de localisation pendant une session (API 33+) → arrêt propre
- [ ] Appuyer sur ARRÊTER → la notification **disparaît** du volet
- [ ] `adb shell am force-stop com.example.gpstest` pendant un enregistrement → relancer → la notice s'affiche
- [ ] Changer de politique en cours de session → la notification reste cohérente
- [ ] Écran éteint en mode économie → notification « en pause »
- [ ] Batterie faible + économiseur d'énergie → observer si le service survit (adapter le comportement selon l'OEM)

La commande `dumpsys` est la seule façon de vérifier **ce que le système pense
vraiment** du service. Une notification visible ne prouve pas que le service
est enregistré comme *foreground*.
