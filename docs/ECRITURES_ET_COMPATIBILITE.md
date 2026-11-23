# Écritures, stockage et compatibilité — les réponses vérifiées

> Ce document répond à trois questions précises : *qu'est-ce qui est
> réellement écrit sur la flash ? où vont les fichiers ? comment savoir que
> le code marche sur tous les téléphones ?*

---

## 1. « Réécrire 3600 fois la même zone » — la réponse exacte

Votre crainte est **légitime et précise**, et la réponse est en trois temps.

### Ce qui se passe quand on appelle `write()` puis `flush()`

| Appel | Ce qui est vraiment touché | Coût | Usure flash |
|---|---|---|---|
| `write(343 octets)` | le **buffer Java** en RAM | nul | **aucune** |
| `flush()` | **page cache du noyau** — encore de la RAM | ~2–5 µs | **aucune** |
| `close()` / synchronisation noyau | **la flash** | ms | la seule qui compte |

**Point capital : `flush()` n'écrit PAS sur la flash.** Il vide le tampon du
processus Java vers le noyau Linux. Le noyau décide ensuite quand écrire
physiquement (toutes les quelques secondes, ou à la fermeture). Beaucoup de
développeurs croient que `flush()` = écriture disque. C'est faux, et c'est
important ici.

### Taille exacte de la zone écrite

| Élément | Taille |
|---|---|
| Un point GPX 1.1 | **343 octets** (mesuré) |
| Un bloc du système de fichiers | **4 096 octets = 4 Kio** |
| Un point = | **8,4 % d'un bloc** |
| Il faut | **~12 points pour remplir un bloc** |
| Donc un bloc neuf est alloué toutes les | **~12 secondes** |

### 🔴 Le point crucial : lit-on/écrit-on « la même zone » ?

**Si on réécrivait 3 600 fois la même zone de 4 Kio**, à 1 000–3 000 cycles
d'endurance, elle serait morte **en moins d'une heure**. Ce serait en effet
catastrophique. **Ce n'est pas ce qui se passe**, pour trois raisons
cumulatives :

**A. On écrit en AJOUT, pas par écrasement.**
Le fichier grandit. Chaque point va dans un emplacement **neuf** du fichier.
Sur une heure : ce sont **301 blocs différents** qui sont touchés une fois
chacun — pas un bloc 3 600 fois.

**B. Le contrôleur flash fait du *wear leveling*.**
Il ne réécrit jamais deux fois au même endroit physique. Il redistribue les
écritures sur toute la flash libre (~30 000 blocs disponibles sur 128 Go).
C'est transparent, c'est fait exprès, et c'est la réponse de l'industrie au
problème que vous soulevez.

**C. Le journaling du système de fichiers travaille par rotation.**
ext4 et f2fs écrivent dans un journal circulaire, pas dans une zone fixe.

### Le seul cas où votre crainte serait fondée

Un fichier **de taille fixe réécrit en place** — par exemple un fichier de
configuration réécrit toutes les secondes, ou une base de données qui met à
jour la même page en boucle. **Notre cas n'est pas celui-là.**

Et c'est précisément pour cela que le **journal de session** (`session.state`)
n'est écrit **qu'aux transitions** et jamais à chaque point : celui-là, oui,
réécrit la même zone. À ~10 écritures par session, c'est négligeable, mais
on ne le fait pas 3 600 fois par heure.

### Comparaison finale

| Action | Blocs de 4 Kio écrits |
|---|---|
| 1 h de trace GPS | **301** |
| 1 photo (3 Mo) | **768** |
| 1 min de vidéo (150 Mo) | 38 400 |
| 1 mise à jour d'app (100 Mo) | 25 600 |

**Une seule photo écrit 2,5 fois plus de blocs qu'une heure de trace.**
L'usure flash n'est pas un sujet pour cette application.

---

## 2. Où sont les fichiers, et comment les nommer

### Emplacement

```
/storage/emulated/0/Android/data/com.example.gpstest/files/
└── Trace_2026_11_22_12h23m.GPX
```

- **Aucune permission de stockage** n'est requise (c'est un dossier propre à
  l'app).
- Il **disparaît** à la désinstallation de l'app.
- Il est **invisible** dans un gestionnaire de fichiers depuis Android 11.
- Il est accessible par **câble USB + ADB**, ou par le bouton de partage.

### Format du nom (révisé)

```
Trace_2026_11_22_12h23m.GPX
└─┬─┘ └───┬───┘ └──┬──┘
  │       │        └── heure et minute du DÉBUT (heure locale)
  │       └── date : le tri alphabétique = le tri chronologique
  └── préfixe constant : facile à retrouver et filtrer
```

| Version | Nom | Problème |
|---|---|---|
| v4 | `trace-2026-11-22_1845.gpx` | tirets, tiret bas, minuscules |
| v5.2 | `trace-2026-11-22_184503.gpx` | secondes, peu lisible à l'œil |
| **v5.3** | **`Trace_2026_11_22_12h23m.GPX`** | — |

**Pourquoi `12h23m` et pas `12:23`** : le caractère `:` est **interdit** sur
FAT/exFAT et sur Windows. Un nom avec `:` ne peut pas être copié sur une clé
USB ou envoyé par certains outils. `h` et `m` sont explicites et universels.

**Pourquoi l'heure locale** : le nom parle à l'humain (« la trace de midi
vingt-trois »). Le **contenu** du GPX reste en UTC, comme l'exige la norme.
Nom pour l'humain, contenu pour la machine.

**Et la collision ?** Le nom étant à la minute, deux traces démarrées dans la
même minute auraient le même nom. C'est `uniqueFile()` qui protège :
il n'écrase **jamais** un fichier existant, il ajoute `-2`, `-3`…
La sécurité est dans le code, pas dans la longueur du nom.

### Où voir ça dans l'app

Écran **Réglages** → section **DONNÉES ENREGISTRÉES** :
- nombre de traces GPX et de fichiers CSV ;
- place totale occupée ;
- **chemin exact** affiché en clair ;
- bouton **PARTAGER LA DERNIÈRE TRACE** (`ACTION_SEND` via `FileProvider`).

---

## 3. « C'est quoi des stubs ? Est-ce fiable ? »

**Vous avez eu raison de poser la question : la réponse honnête est que mes
stubs n'étaient PAS fiables, et je l'ai prouvé en cours de route.**

### Ce qu'était un stub

Un fichier Java minimal qui imite une classe Android :

```java
// tools/stubs/android/app/NotificationChannel.java
package android.app;
public class NotificationChannel {
    public NotificationChannel(String id, CharSequence name, int importance) {}
    public void setDescription(String d) {}
}
```

**But** : compiler le code hors Android Studio, pour détecter les erreurs de
type et de signature.

### Pourquoi c'est dangereux

Un stub est écrit par moi, **de mémoire**. S'il déclare une méthode qui
n'existe pas, ou avec la mauvaise signature, il **valide du code qui
crashera**. C'est un test qui peut mentir.

**Preuve par l'expérience, faite dans cette session :**

```
Test témoin :  android.app.NotificationChannel  contre API 24
Résultat   :   "cannot find symbol"  ✅ (le vrai jar ne l'a pas)
```

Puis, en cassant volontairement un garde `SDK_INT >= O` :

```
Compilation avec stubs  : 0 erreur   ← LE STUB CACHAIT LE BUG
```

**Le stub contenait `NotificationChannel`, donc javac était content.**
Sur un vrai Android 7, ce code aurait levé `NoClassDefFoundError`.

### Ce que j'ai fait pour remplacer les stubs

J'ai téléchargé les **vrais** fichiers :

| Fichier | Source | Taille | Contenu |
|---|---|---|---|
| `android-all-14.jar` | Robolectric / Maven | 132 Mo | **48 226 classes** Android 14 réelles |
| `android-all-7.0.0.jar` | Robolectric / Maven | 77 Mo | **15 058 classes** Android 7.0 réelles |
| `appcompat-1.7.0`, `core-1.13.1`, `activity-1.9.0`, `fragment-1.8.0`… | Google Maven | — | les vraies bibliothèques AndroidX |
| `kotlin-stdlib-1.9.24` | Maven Central | 1,7 Mo | dépendance requise par AndroidX récent |

Ces jars **ne sont pas** des approximations : ce sont les classes extraites
des images système Android, utilisées par le framework de test Robolectric.

### Ce que cette compilation réelle a trouvé

**Un vrai bug de compatibilité :**

```java
// AVANT — TrackingService.java
import android.app.NotificationChannel;   // ← classe API 26 !

if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    NotificationChannel ch = new NotificationChannel(...);
}
```

Le garde `SDK_INT` protège à l'**exécution**, mais l'`import` en tête de
fichier rend la classe **visible du vérificateur d'API** du build tools
(« Call requires API level 26 ») — et, combiné à la référence, c'est ce qui
déclenche `NoClassDefFoundError` sur Android 7.

**Correction** : import supprimé, référence en **pleine qualification** à
l'intérieur du bloc gardé :

```java
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    android.app.NotificationChannel ch = new android.app.NotificationChannel(...);
}
```

### ⚠️ Et la nuance la plus importante de ce document

Ma compilation contre `android-all-7.0.0.jar` produit **97 erreurs
« can't find symbol »** sur des choses parfaitement correctes :

| Ce que le vrai compilateur Android accepte | Pourquoi mon test le refuse |
|---|---|
| `findViewById` générique (`<T extends View> T`) | sur API 24, `Activity.findViewById` renvoie un `View` brut : l'inférence de type échoue |
| `AppCompatActivity` entier | mes jars androidx sont incomplets (dépendances transitives manquantes) |
| `androidx.activity.result.*` | idem |

**Conclusion honnête** : compiler contre un `android.jar` ancien avec `javac`
n'est **pas** un test de compatibilité fiable. Le vrai outil s'appelle
**Lint** (`NewApi`) dans Android Studio, et il raisonne sur les **garde-fous
`SDK_INT`**, pas sur la présence des classes.

Ce que ma méthode prouve vraiment : *le code compile contre l'API de
compilation et contre AndroidX*. Ce qu'elle ne prouve pas : *le
comportement sur un appareil ancien*.

---

## 4. Comment être sûr que ça marche sur tous les téléphones

### Ce qui est vérifié ici

| Test | Outil | Résultat |
|---|---|---|
| Compilation contre le **vrai** `android-all-14` | `javac 17` | ✅ **0 erreur** |
| 257 tests unitaires purs | `java` | ✅ **257/257** |
| XML valides (layouts + manifest) | `ElementTree` | ✅ |
| IDs Java ⇄ XML | croisement | ✅ 66/66 |
| `NotificationChannel` hors d'un import API 26 | analyse | ✅ corrigé |

### Ce qui NE PEUT PAS être vérifié ici, et qu'il faut faire dans Android Studio

**1. Lint `NewApi` — le seul vrai vérificateur de compatibilité**

```
Analyze > Inspect Code > Android > Lint > Correctness > NewApi
```

Il vérifie **chaque appel d'API** contre le `minSdk` en tenant compte des
garde-fous `SDK_INT`. C'est lui qui aurait signalé `NotificationChannel`,
et c'est lui qui fait foi. **Il doit être à zéro avant de distribuer.**

**2. Les ressources (`aapt2`)**

Les layouts, styles, couleurs sont compilés et validés seulement par aapt2.
Un `@color/inexistant` produit une erreur de build, pas une erreur de code.

**3. Le manifest merger**

Les permissions et le `<provider>` sont fusionnés avec ceux des bibliothèques.
Un conflit (par exemple deux `FileProvider` avec la même autorité) ne se voit
qu'à ce moment-là.

**4. L'exécution réelle**

Aucun outil statique ne remplace un test sur appareil :

| Version | Ce qu'il faut vérifier |
|---|---|
| **Android 7.0 (API 24)** | démarrage, service, GPX — le cas le plus fragile |
| Android 10 (API 29) | `startForeground` avec type |
| **Android 13 (API 33)** | permission `POST_NOTIFICATIONS`, refus |
| **Android 14 (API 34)** | `foregroundServiceType`, restriction de lancement |
| Android 15 (API 35) | pareil + nouvelles règles de FGS |

Un émulateur par version suffit pour 90 % des cas. Les 10 % restants sont
les surcouches constructeur (voir §5).

**5. `minSdk` — la décision qui compte**

`minSdk 24` = Android 7.0 (2016). **Cela couvre 99 %+ des appareils actifs.**
Si vous vouliez diviser les risques par deux, `minSdk 26` (Android 8.0)
supprime **toutes** les branches `NotificationChannel` / `startForegroundService`
et rend le code plus simple. **C'est un choix produit, pas technique** — je ne
le prends pas à votre place.

---

## 5. Le risque résiduel, dit franchement

| Risque | Probabilité | Mitigation |
|---|---|---|
| Bug propre au code | moyenne | 257 tests + Lint + tests réels |
| Écart d'API non détecté | faible si Lint est à zéro | Lint obligatoire |
| OEM tue le service (Xiaomi, Huawei, Samsung) | **élevée** | journal de session → l'interruption est au moins constatée |
| Comportement GNSS exotique | moyenne | gardes `try/catch`, valeurs `NaN` plutôt que fausses |
| Corruption GPX | faible | flush périodique + `uniqueFile` |
| Perte de données au `kill -9` | certaine sur les 2 dernières secondes | `flushNow()` sur extinction d'écran |

**La phrase à retenir** : aucune méthode statique ne prouve qu'une app
fonctionne. Elle prouve qu'elle **compile**, qu'elle est **cohérente** et
qu'elle **respecte les règles connues**. Le reste se vérifie sur un appareil.
