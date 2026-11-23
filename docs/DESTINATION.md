# Destination des traces — où vont les fichiers, et pourquoi

> v5.4. Répond à : « beaucoup d'utilisateurs se demandent où sont écrits leurs
> fichiers », « ça doit être sauvegardé dans les préférences », et « pourquoi
> une copie à la fin plutôt qu'écrire directement ? »

---

## 1. Votre question : pourquoi ne pas écrire directement ?

**Parce que sur Android, le répertoire choisi par l'utilisateur n'est pas un
chemin. C'est une URI de document, et un contrat passé à une autre application.**

```
file:///storage/…/files/Trace_….GPX        ← un fichier : open/write/flush/append
content://com.android.externalstorage.…/tree/primary%3ADownload
                                           ← une URI : "écris à ma place"
```

### Le fait décisif : il n'y a pas d'append garanti sur une URI

Documentation officielle de `DocumentsProvider` :

> Mode "r" **should always be supported**. Provider should throw
> `UnsupportedOperationException` if the passing mode is not supported.

Autrement dit : **seul `"r"` est garanti.** `"wa"` (append) peut lever, selon
le fournisseur. Et sur `"w"` :

> … "w" **may or may not truncate**.

Ce n'est donc pas une limite théorique : c'est un contrat qui autorise
explicitement le fournisseur à refuser l'ajout.

### Ce que coûterait d'écrire directement quand même

Sans append, ajouter 343 octets impose de **réécrire le document entier** :

| Durée de trace | Taille | Réécritures | Données écrites |
|---|---|---|---|
| 1 h | 1,18 Mo | 1 800 (flush/2 s) | **2,1 Go** |
| 8 h | 9,4 Mo | 14 400 | **136 Go** |

Votre scénario « 3 600 fois la même zone par heure » serait alors **réel** —
et sur une carte SD en plus. Ajoutez qu'aucun fournisseur ne garantit une
écriture atomique : un processus tué en cours laisse un fichier tronqué.

**Verdict : techniquement possible, structurellement absurde.**

---

## 2. Les trois stratégies, comparées

| | Append direct SAF | Écriture locale + publication | Tout en local |
|---|---|---|---|
| Écritures | 2,1 Go/h | 1,18 Mo/h | 1,18 Mo/h |
| Marche partout | **non** (`wa` optionnel) | oui | oui |
| Fichier visible par l'utilisateur | oui | **oui** | non |
| Risque de troncature | à chaque flush | fenêtre de ms | aucune |
| Perte si cloud/SD déconnecté | trace perdue | **trace locale intacte** | — |

**Choix retenu : écriture locale + publication.** Le fichier local est la
vérité garantie ; le fichier publié est la version lisible. On ne troque
jamais une garantie contre une commodité.

---

## 3. Ce que voit l'utilisateur

### Au démarrage : une confirmation qui dit où

```
┌────────────────────────────────────────────┐
│ Démarrer l'enregistrement ?                │
│                                            │
│ Votre trace sera enregistrée dans :        │
│ Stockage interne / Téléchargements         │
│                                            │
│ Fichier : Trace_2026_11_22_12h23m.GPX      │
│                                            │
│ Le fichier apparaît dans ce dossier dès le │
│ premier point et grossit pendant la trace. │
│                                            │
│ DESTINATION DES TRACES                     │
│ [ Téléchargements ]                        │
│ [ Documents ]                              │
│ [ Dossier de l'application ]               │
│ [ Choisir un autre dossier… ]              │
│                                            │
│         [ Annuler ]  [ DÉMARRER ]          │
└────────────────────────────────────────────┘
```

Le nom affiché est calculé par **la même fonction** que celle utilisée par le
service (`GpxWriter.fileName`). Ce qui est annoncé est donc, par construction,
ce qui sera écrit.

### Pendant la trace

Ligne **Destination** sur l'écran principal, en permanence :

- `Trace enregistrée dans le dossier choisi`
- `En attente d'écriture dans le dossier choisi…`
- `Dossier choisi inaccessible — trace conservée dans le dossier de l'application`

**Jamais de message qui laisse croire à une perte.** La phrase d'erreur
contient toujours « conservée », et un test le vérifie.

### Quand le fichier apparaît dans le dossier choisi

La trace est écrite en continu dans le dossier privé de l'application. Cette
copie locale est la source de vérité pendant l'enregistrement. La copie SAF
est publiée **à la fermeture du segment** (arrêt ou pause liée à la politique
écran éteint). Pendant le segment actif, la destination choisie peut donc
être en retard : l'interface doit l'indiquer et ne pas prétendre que le fichier
externe est déjà à jour.

Cette stratégie évite de réécrire le fichier complet toutes les 30 secondes.
Une réécriture intégrale répétée devient quadratique avec la durée de la trace
et peut solliciter inutilement les fournisseurs cloud, cartes SD et stockage.
L'écriture locale append-only est indépendante de la destination SAF ; une
panne SAF n'interrompt pas le suivi GNSS ni la source locale.

---

## 4. Pourquoi pas toutes les secondes ?

La publication externe est volontairement déclenchée à la fermeture d'un
segment, et non à chaque point ou selon un intervalle fixe. Enregistrer les
points localement à 1 Hz est une écriture séquentielle peu coûteuse ; recopier
le fichier complet à chaque point ou toutes les 30 secondes ne l'est pas.

La fermeture de segment force `flush`, une tentative `FileDescriptor.sync()`
puis `close` avant la création de la copie SAF. La synchronisation physique
reste une demande au système de fichiers, pas une garantie absolue contre une
panne matérielle ou un fournisseur défaillant.

---

## 5. Les préférences : écrites à chaque modification

Aucun bouton « Enregistrer » dans l'écran de réglages. **Chaque geste écrit.**

| Réglage | Écriture | Pourquoi |
|---|---|---|
| Politique d'arrière-plan | `commit()` | le service la relit dans `onCreate`, autre thread |
| Version GPX | `commit()` | lue à l'ouverture du segment, juste après un appui |
| Destination | `commit()` | le service démarre immédiatement après le dialogue |
| Datum de la carte | `apply()` | purement visuel |
| Filtre satellites | `apply()` | purement visuel |

`apply()` = mémoire tout de suite, disque en arrière-plan. `commit()` = attend
le disque. Une valeur que le **service** lit dans les millisecondes suivantes
exige `commit()`, sinon il peut lire l'ancienne — bug intermittent et
impossible à reproduire en debuggage.

### Correctif : les deux sélecteurs de la carte

**Avant** : changer le datum sur Lambert 93, tourner le téléphone en paysage →
retour à WGS84. L'utilisateur croit à un bug, à juste titre.

**Après** : `selectedDatum` et `filterMode` sont persistés, et restaurés
**avant** d'attacher l'écouteur (sinon `setSelection` réécrit la préférence
qu'on vient de lire).

### Le piège du quota : 128 autorisations par app

`takePersistableUriPermission()` persiste un accès. Android plafonne à
**128 par application**. Une app qui en accumule finit par échouer
silencieusement.

**Remède** : `setTreeUri()` libère **toutes** les autres autorisations avant
d'en prendre une nouvelle. Et `clear()` libère l'autorisation **avant**
d'oublier l'URI — sinon l'autorisation devient orpheline et irrécupérable,
pour toujours.

---

## 6. Les bugs que j'ai trouvés dans ma propre première version

Écrits ici parce qu'ils sont instructifs, pas parce qu'ils sont jolis.

| # | Bug | Conséquence | Correctif |
|---|---|---|---|
| 1 | `applyPreset` construisait `primary:Download` pour **tous** les presets | « Documents » écrivait dans « Téléchargements », silencieusement | `preset.documentId()` |
| 2 | `createDocument` à chaque publication | 120 fichiers « …(1) », « …(2) » après 1 h | créé **une fois**, puis réécrit |
| 3 | Publication en cours de session sans pied | GPX sans `</gpx>` → **invalide**, QGIS refuse | pied ajouté si absent |
| 4 | `flushNow()` appelé depuis le thread de publication | `flushNow` **poste** sur `ioFile`, ne flushe pas → copie en retard | flush direct |
| 5 | `segmentClosed` (drapeau) lu par un autre thread | publication sans pied malgré le drapeau | **drapeau supprimé** : on lit `</gpx>` dans le fichier |
| 6 | `FileWriter` flushé depuis 2 threads | `FileWriter` n'est pas synchronisé → corruption possible | verrou unique sur le writer |
| 7 | Publication finale sans identité de segment | si un segment s'ouvre entre-temps : **deux traces mélangées dans un fichier** | `segmentId` capturé et comparé |
| 8 | `Preference` libellé écrit deux fois + double Toast | seul le 2ᵉ message visible | un seul chemin |
| 9 | `refreshDestination()` recochait un bouton via son écouteur | boucle + réécritures parasites | garde `suppressDestEvents` |
| 10 | `clear()` effaçait l'URI avant de libérer l'accès | autorisation orpheline, fuite vers le quota de 128 | ordre inversé |
| 11 | `Destination` mélangeait logique pure et `Context` | **impossible de tester** la partie qui avait le bug | classe `DestPresets` pure |

Le n° 11 est le plus important : c'est une faute d'**architecture**, pas de
code. Le bug n° 1 est resté invisible parce qu'aucun test ne pouvait
s'exécuter. D'où l'extraction de `DestPresets`, et 19 tests qui tournent
réellement.

---

## 7. Vérification d'état — la liste des invariants

| Invariant | Vérifié par |
|---|---|
| `recording == true` ⟹ un `FileWriter` est ouvert | lecture du code : `startRecording` appelle `openRecordingSegment` avant de marquer |
| `recording == false` ⟹ segment fermé (pied + flush) | `stopRecording` → `closeRecordingSegment` |
| `suspended == true` ⟹ capteurs désabonnés | `suspendTracking` → `unsubscribeSensors()` |
| Service tué ⟹ journal reste *dirty* | `stopRequested` distingue arrêt voulu et mort subite |
| Arrêt utilisateur ⟹ journal effacé | `clearSessionJournal()` dans `stopTracking` |
| Une seule publication en vol | `publishInFlight` + `publishPending`, libérés par **un seul** endroit |
| Une publication ne retard une trace | thread `gpx-publish` **séparé** |
| Une tache de publication ne touche jamais un autre segment | `segmentId` capturé à l'envoi, comparé à l'exécution |
| Le GPX publié est toujours ouvrable | `endsWithGpxClose` ajoute le pied si nécessaire |

---

## 8. Ce qui reste impossible ici, et où le vérifier

| Point | Outil | Pourquoi |
|---|---|---|
| Écriture réelle via SAF | appareil | pas de `DocumentsProvider` dans le sandbox |
| Compilation des ressources | `aapt2` (Gradle) | pas d'outil aapt2 ici |
| Manifest merger (FileProvider) | Gradle | — |
| Test API 24 / 33 / 34 | émulateur | aucun runtime Android ici |

**Ce qui EST vérifié** : compilation contre le vrai `android.jar` API 34
(0 erreur), 277 tests unitaires, 82 IDs Java ⇄ 82 IDs XML.

---

## 9. Sur les versions Android — franchement

Votre inquiétude est fondée : tester réellement sur toutes les versions est
hors de portée. Deux décisions réduisent le risque.

**a) `minSdk` : 24 ou 26 ?**

| `minSdk` | Couverture | Branches conditionnelles | Risque |
|---|---|---|---|
| **24** (Android 7) | ~99 % | `NotificationChannel`, `startForegroundService`, `registerReceiver` flag… | plus élevé |
| **26** (Android 8) | ~97 % | **toutes celles-ci disparaissent** | nettement plus faible |

Passer à 26 supprime les trois branches les plus fragiles du projet. C'est
un choix produit, pas technique, et je ne le prends pas à votre place.

**b) Lint `NewApi` est le seul vérificateur qui fait foi**

Il raisonne sur les gardes `SDK_INT`, pas sur la présence des classes. C'est
lui qui aurait signalé `NotificationChannel` — et c'est lui seul qui permet
de dire « ce code respecte `minSdk` » sans posséder chaque version d'Android.

**c) Ce qu'aucun outil statique ne couvre**

Les surcouches constructeur (Xiaomi, Huawei, Samsung) qui tuent les services
d'arrière-plan. C'est pour cela que le journal de session existe : l'app ne
peut pas empêcher l'interruption, mais elle la **constate** et le dit.
