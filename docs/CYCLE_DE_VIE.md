# Cycle de vie Android pour une app GNSS — le cours

> Pourquoi une app qui « marche » peut être une app ratée, et comment faire la différence.

---

## 1. Le cycle de vie en 30 secondes

```
        ┌──────────────┐
        │   onCreate   │  création du process : gonfler le XML, brancher les vues
        └──────┬───────┘
               ↓
        ┌──────────────┐
        │   onStart    │  l'écran devient VISIBLE
        └──────┬───────┘
               ↓
        ┌──────────────┐
        │   onResume   │  l'écran a le FOCUS (on peut le toucher)
        └──────┬───────┘
               ↓  ⇅  (dizaines de fois par session)
        ┌──────────────┐
        │   onPause    │  perte du FOCUS : un dialogue s'ouvre, on te regarde,
        └──────┬───────┘  mais l'écran reste visible (split-screen, permission)
               ↓
        ┌──────────────┐
        │   onStop     │  INVISIBLE : une autre app est au premier plan
        └──────┬───────┘
               ↓
        ┌──────────────┐
        │  onDestroy   │  destruction (rotation, ou mort du process)
        └──────────────┘
```

### La différence essentielle : Pause ≠ Stop

| | onPause | onStop |
|---|---|---|
| L'écran est encore visible ? | **oui** | non |
| Cas typiques | dialogue de permission, menu déroulant, l'autre moitié du split-screen, fenêtre de volume | l'utilisateur est passé sur WhatsApp, a verrouillé l'écran, ou est retourné au bureau |
| Fréquence | très élevée | moins fréquente mais fréquente quand même |

**Confondre les deux est la source n°1 des bugs de cycle de vie.** Un dialogue de permission déclenche `onPause` → si vous coupez le GPS dans `onPause`, la demande de permission coupe votre propre GPS.

---

## 2. Ce qui se passe concrètement pour une app GNSS

### ✅ Ce qui est simple : la rotation

Tourner l'écran **détruit et recrée l'Activity**. Toutes les variables d'instance de `MainActivity` repartent à zéro. Votre `tracking = true` redevient `false`, et l'utilisateur voit le bouton DÉMARRER alors que le GPS tourne peut-être encore.

**Deux réponses :**

- **`android:configChanges`** (solution paresseuse) : on déclare dans le manifest qu'on gère soi-même la rotation. L'Activity n'est **pas** recréée.
  ```xml
  android:configChanges="orientation|screenSize|keyboardHidden"
  ```
  Simple, mais **déconseillé** : vous vous engagez à réagir vous-même à tous les changements de taille, et les conflits multi-fenêtres reviennent par la porte de derrière.

- **Un état dans un objet qui survit** (solution propre) : Activity ou Service qui garde l'état. Voir la section 4.

### ⚠️ Ce qui est piégeux : l'écran s'éteint

Verrouiller l'écran → **`onPause` puis `onStop`**. L'Activity est invisible.

À partir d'**Android 8 (API 26)**, un process en arrière-plan est **fortement restreint** :
- les mises à jour de localisation cessent d'arriver après un délai,
- le système peut **tuer** votre process à tout moment s'il manque de mémoire,
- en pratique, votre trace s'arrête au bout de quelques minutes.

**Il n'existe pas de solution au niveau de l'Activity.** C'est un choix explicite d'Android depuis 2017 : économiser la batterie.

### ⚠️ Ce qui est piégeux : changer d'application

Même chose que l'écran éteint, sans le verrouillage. Retour à l'app → `onStart` → `onResume`. Mais si votre code a fait `tracking = false` dans `onStop`, **vous devez réappuyer sur DÉMARRER**, et vos données sont perdues.

---

## 3. La règle d'or

> **Séparer l'INTENTION de l'utilisateur de la SOUSCRIPTION technique.**

| Concept | Question | Qui le porte |
|---|---|---|
| **Intention** | « l'utilisateur veut-il que le GPS tourne ? » | l'utilisateur — persiste jusqu'à ce qu'il appuie sur ARRÊTER |
| **Souscription** | « suis-je actuellement abonné aux callbacks ? » | le système — monte et descend au rythme du cycle de vie |

Deux drapeaux distincts. Un seul ne suffit jamais :

```java
boolean userWantsTracking;   // piloté par le bouton, persiste
boolean subscribed;          // piloté par onStart/onStop, technique
```

Appliquer ça au code existant :

| Méthode | Ce qu'il faut faire | Ce qu'il ne faut PAS faire |
|---|---|---|
| `onCreate` | gonfler le XML, brancher les vues, lire l'état sauvegardé | s'abonner au GPS |
| `onStart` | **si** `userWantsTracking` → se réabonner | remettre `userWantsTracking` à zéro |
| `onResume` | rafraîchir l'affichage depuis l'état courant | — |
| `onPause` | **rien** pour le GPS | couper le GPS |
| `onStop` | se **désabonner** (technique), garder l'intention | perdre l'intention |
| `onDestroy` | fermer fichiers / ressources | — |

Le désabonnement dans `onStop` reste **obligatoire** : un écouteur GPS actif quand l'écran est éteint, c'est une fuite de batterie et une fuite mémoire.

---

## 4. La vraie solution pour survivre en arrière-plan : un Service de premier plan

Si vous voulez qu'un enregistrement continue quand l'écran s'éteint, **l'Activity n'est pas l'outil**. Il faut un **Foreground Service** : un composant qui tourne indépendamment de l'état de l'écran, avec une **notification permanente** (le prix à payer pour échapper aux restrictions d'arrière-plan).

```
┌─────────────────────┐        ┌──────────────────────────┐
│    MainActivity     │  bind  │    TrackingService       │
│  (écran, UI seule)  │◀──────▶│  (GPS, GPX, notifications)│
│                     │  état  │  survit à onStop/rotation │
└─────────────────────┘        └──────────────────────────┘
     tue/recrée à la rotation        tourne tant que
     du téléphone                     l'utilisateur le veut
```

### Ce que ça impose

| Élément | Rôle |
|---|---|
| `startForeground(id, notification)` | déclare le service comme « important » — sans ça, Android le tue |
| `FOREGROUND_SERVICE` (API 28+) | permission |
| `FOREGROUND_SERVICE_LOCATION` (API 34+) | permission spécifique au type |
| `android:foregroundServiceType="location"` | obligatoire depuis API 34 |
| `POST_NOTIFICATIONS` (API 33+) | sans la permission, la notification est **silencieuse** mais le service tourne quand même |
| `START_NOT_STICKY` | si le système tue le service, il **ne redémarre pas** tout seul |

### Ce que ça change pour l'utilisateur

- la trace continue écran éteint **et** app en arrière-plan,
- il voit une notification permanente « Enregistrement en cours — 142 points »,
- il peut arrêter depuis la notification.

C'est exactement ce que font les apps sérieuses (GPS Logger, Strava, OsmAnd…). Et c'est **obligatoire** pour un usage réel.

---

## 5. Rotation : le piège des données temporaires

Deux mécanismes, souvent confondus :

| Mécanisme | Quand l'utiliser | Taille |
|---|---|---|
| `onSaveInstanceState(Bundle)` | petite UI transitoire : numéro d'onglet, texte d'un champ, position de défilement | **≤ 100 Ko environ**, sérialisé rapide |
| Service / objet persistant | données longues : trace GPX, session GPS | illimité |

**Ne mettez JAMAIS une trace GPS dans un Bundle.** 3 heures de marche = des dizaines de milliers de points = plusieurs Mo → `TransactionTooLargeException`. Android tue votre app pour ça.

---

## 6. Le résumé opérationnel

| Situation | Ce qui doit se passer |
|---|---|
| Rotation de l'écran | l'UI se reconstruit, **la session GPS continue**, l'affichage se remet à jour |
| Dialogue de permission | le GPS **ne doit pas** s'arrêter (onPause seulement) |
| Ouverture du clavier, notification déroulée | rien ne s'arrête (onPause) |
| App en arrière-plan | le GPS continue **si** un Foreground Service, sinon Android le coupe |
| Écran éteint | idem |
| Retour dans l'app | l'UI **se resynchronise** sur l'état courant, sans intervention |
| Mort du process (mémoire) | au redémarrage : session perdue, **fichier GPX lisible** (grâce au flush incrémental) |

---

## 7. Ce que ça coûte de ne pas le faire

Voici ce que notre code faisait **avant** cette leçon — et c'est instructif :

| Code | Conséquence |
|---|---|
| `onStop() → stopTracking()` qui met `tracking = false` | retour dans l'app : **bouton DÉMARRER affiché**, GPS arrêté, l'utilisateur croit devoir relancer |
| `onStop()` ferme le flux GPX | **enregistrement terminé silencieusement** au premier changement d'app |
| Aucun état sauvegardé | une rotation d'écran **efface** la session en cours |
| Aucun service | impossible d'enregistrer électroniquement une heure de marche fiable |

**Une app qui ne gère pas ça n'est pas « presque bonne » : elle est inutilisable pour son usage réel.** C'est la différence entre un prototype et un outil.

---

## 8. Checklist de validation (à tester à la main sur le téléphone)

### Cycle de vie de base (v4)

- [ ] Démarrer → tourner l'écran → **le compteur de temps doit continuer**, pas repartir de zéro
- [ ] Démarrer → ouvrir WhatsApp → attendre 30 s → revenir → **les satellites reviennent**, la session n'a pas redémarré
- [ ] Enregistrer une trace → éteindre l'écran 60 s → rallumer → **les points ont continué d'arriver**
- [ ] Enregistrement en cours → voir la notification permanente avec le compteur
- [ ] Arrêter depuis la notification → le service s'arrête proprement, **le fichier GPX est fermé et valide**
- [ ] Refuser la permission caméra d'un autre dialogue → **le GPS continue**
- [ ] Forcer l'arrêt de l'app pendant l'enregistrement → **le fichier GPX partiel reste lisible**

> **Pour l'anatomie complète du service** (contrat Android, 11 défauts
> corrigés, table de vérification), voir **`docs/FOREGROUND_SERVICE.md`**.

### Politique d'écran éteint (v5)

- [ ] **Continuer** : enregistrer → éteindre 2 min → rallumer → le compteur de points a **augmenté d'environ 120**, un seul fichier
- [ ] **Garder l'écran allumé** : enregistrer → ne pas toucher → l'écran **ne s'éteint pas** au bout du délai habituel
- [ ] **Pause à l'extinction** : enregistrer → éteindre 1 min → rallumer → la notification disait **« en pause »**, et un fichier **`-p2`** est apparu au rallumage
- [ ] Changer de politique **pendant** un enregistrement → la session ne repart pas de zéro, le fichier courant est fermé proprement
- [ ] Changer de politique **sans** session active → **aucune notification permanente** n'apparaît
- [ ] Basculer en GPX 1.0 en cours de session → le segment **suivant** est en 1.0, le précédent reste en 1.1
- [ ] Tuer l'app (Paramètres → Forcer l'arrêt) pendant l'enregistrement → relancer → **la notice « session précédente interrompue »** s'affiche avec le nom du fichier
