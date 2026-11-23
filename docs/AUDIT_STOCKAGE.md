# Audit structurel : stockage, écritures, et causes de crash

> Trois questions, trois réponses chiffrées — puis un audit complet des
> erreurs **structurelles** (pas les fautes de frappe : les décisions
> d'architecture qui produisent des bugs en série).

---

## 1. « Où sont sauvegardées les données ? »

Un seul dossier, à un seul endroit, pour tout :

```
/storage/emulated/0/Android/data/com.example.gpstest/files/
```

| Fichier | Écrit par | Taille typique | Fréquence |
|---|---|---|---|
| `trace-2026-11-22_184503.gpx` | `TrackingService` | 1,2 Mo/h | 1 point/s |
| `mesures-brutes-<ms>.csv` | `ExpertActivity` | quelques Ko | à la demande |
| `session.state` | `TrackingService` | ~200 octets | aux transitions |

C'est `Context.getExternalFilesDir(null)`. Ce choix a des conséquences
précises :

| Propriété | Valeur | Conséquence |
|---|---|---|
| Visible par l'app | ✅ | lecture/écriture directe |
| Nécessite une permission | ❌ | aucune permission de stockage — c'est un bon point |
| Visible par l'utilisateur | **❌ depuis Android 11** | `Android/data` est masqué dans les gestionnaires de fichiers |
| Accessible aux autres apps | ❌ | sauf partage explicite |
| Survivre à la désinstallation | ❌ | les traces partent avec l'app |

### ⚠️ Le défaut structurel que ça a révélé

**L'application enregistrait des traces que l'utilisateur ne pouvait pas
récupérer.** Sans câble USB et sans ADB, un fichier GPX produit par l'app
était inaccessible. QGIS, OsmAnd, Google Earth : aucun ne pouvait l'ouvrir.
Un enregistreur dont on ne peut pas sortir les données ne remplit pas son
contrat, quel que soit son code.

**Correction** (v5.2) : `FileProvider` + `ExportHelper`.
- `res/xml/file_paths.xml` déclare ce qui est partageable.
- Le manifest déclare le `provider` avec `grantUriPermissions="true"`.
- `ExportHelper.shareLatestGpx()` envoie la trace vers n'importe quelle app
  (mail, Drive, Telegram, QGIS, OsmAnd) via `ACTION_SEND`.
- **Aucune permission supplémentaire** : le droit est accordé temporairement
  et nominativement à l'application que l'utilisateur choisit.
- L'écran **Réglages** affiche désormais : nombre de traces, nombre de CSV,
  place occupée, **chemin exact**, et un bouton de partage.

Ce qui n'est **pas** fait, volontairement : écrire dans `Documents/` du
stockage partagé. Cela exigerait des permissions que Google restreint depuis
Android 10, avec de nouvelles obligations à chaque version. Le partage couvre
le besoin réel sans dette technique.

---

## 2. « Fait-on des écritures abusives ? » — les chiffres

Un point GPX 1.1 fait **343 octets**. Mesuré, pas estimé.

| Durée | Points | Volume |
|---|---|---|
| 1 h | 3 600 | **1,18 Mo** |
| 3 h | 10 800 | 3,53 Mo |
| 8 h | 28 800 | 9,42 Mo |
| 100 h | 360 000 | 118 Mo |

### Le point qui compte : `flush()` n'est pas une écriture disque

```
write()  -> mémoire du processus (buffer Java)      : gratuit
flush()  -> noyau (write syscall)                   : un appel systeme
close()  -> le noyau decide quand ecrire physiquement: la flash
```

**Trois niveaux, trois coûts.** La v5 faisait `flush()` à chaque point : 3 600
appels système par heure. Ce n'est pas de l'usure de flash — c'est une perte
de temps CPU et de batterie, et surtout, à l'origine, c'était fait **sur le
thread principal** (cause d'ANR).

**Correction** : `FLUSH_EVERY_MS = 2000`. Le `flush()` a lieu au plus toutes
les 2 secondes, **plus un flush forcé** (`flushNow()`) sur chaque événement
qui compte : extinction d'écran, fermeture de segment, arrêt.

| | Avant | Après |
|---|---|---|
| `flush()` par heure | 3 600 | **1 800** |
| Données perdues au pire (`kill -9`) | 1 point | **2 secondes de points** |
| Écriture sur le thread principal | **oui** | non (thread `gpx-io`) |

### L'usure de la flash : ordre de grandeur honnête

Hypothèses : flash 128 Go, blocs de 4 Mo, 1 500 cycles P/E, wear leveling
sur 92 % de la capacité.

| Usage | Volume/an | Temps avant usure théorique |
|---|---|---|
| 2 h/jour | 0,8 Go | **~210 000 ans** |
| 24 h/24 | 10 Go | **~17 500 ans** |

**Conclusion sans détour : l'usure de la flash n'est pas un sujet.**
Le téléphone mourra d'autre chose. Ce qui use vraiment la flash d'un
téléphone, ce sont les photos, les vidéos et les mises à jour d'applications :

- **1 photo** (3 Mo) ≈ 2 h 30 de trace GPS
- **1 min de vidéo** (150 Mo) ≈ 127 h de trace GPS
- **1 mise à jour d'app** (100 Mo) ≈ 85 h de trace GPS

Une année de traces GPS intensives pèse moins qu'une dizaine de photos.

---

## 3. « Mon application crashe » — la cause trouvée

### 🔴 La boucle de permissions (crash reproductible)

```
Utilisateur refuse POST_NOTIFICATIONS une premiere fois.
        ↓
Il appuie sur DEMARRER.
        ↓
ensurePermissionThenStart() voit FINE=ok → startSession()
        ↓
startSession() voit POST_NOTIFICATIONS=refusee → relance le launcher
        ↓
Android ne reaffiche PAS le dialogue (deja refuse)
→ il rappelle IMMEDIATEMENT le callback avec FINE=true
        ↓
le callback faisait startSession() → meme etat → relance encore
        ↓
        ∞   (StackOverflowError, ou ANR par inflation de dialogues)
```

**Pourquoi c'est une erreur structurelle et non une faute de frappe** : la
méthode « démarrer » contenait aussi la responsabilité « demander une
permission ». Une méthode qui peut déclencher un callback qui la rappelle
elle-même est une boucle en puissance, quel que soit le soin apporté aux
lignes.

**La correction est structurelle**, pas cosmétique : séparation en deux
méthodes à responsabilité unique.

```
ensurePermissionThenStart()  -> demande, puis appelle startTrackingNow()
startTrackingNow()           -> démarre. Et RIEN d'autre. Aucune permission.
```

Le callback de permission ne peut plus revenir dans la demande de permission :
il n'y a plus de chemin de code qui le permette. Et un refus de notification
**n'empêche plus le démarrage** — le suivi fonctionne, la notification est
simplement silencieuse, avec un message qui le dit.

### 🔴 L'écrasement silencieux de traces (perte de données)

`GpxWriter.fileName()` utilisait `yyyy-MM-dd_HHmm` — **à la minute**.

Deux traces démarrées dans la même minute produisaient le même nom. Et
l'ouverture se fait en mode troncature (`FileWriter(f, false)`). Résultat :
**la seconde trace écrasait la première, sans aucun avertissement.**

Ce qu'on croyait avoir : 2 traces. Ce qu'on avait : 1 trace, la seconde.

| Avant | Après |
|---|---|
| `trace-2026-11-22_1845.gpx` | `trace-2026-11-22_184503.gpx` |
| 2 traces dans la minute → **1 fichier** | 2 fichiers distincts |
| rien ne le signale | `uniqueFile()` refuse d'écraser, ajoute `-2` |

`uniqueFile()` est une défense en profondeur : même si un changement d'heure
ou une horloge remise à zéro recréait une collision, **aucune trace existante
n'est jamais écrasée.**

### 🟠 Autres corrections de cette passe

| Défaut | Nature | Correction |
|---|---|---|
| Lecture du journal dans `onCreate` (thread principal) | structurel | thread de fond `session-journal-read` |
| `startActivity` sans garde | robustesse | `openSafely()` — `ActivityNotFoundException` absorbée |
| `getSystemService` non vérifié avant `.isProviderEnabled()` | NPE | `lm == null ||` |
| `spSatFilter.setSelection(0)` = « Tous » | logique | la liste est de toute façon triée utilisés-d'abord par le comparateur |

---

## 4. Ce qui a été vérifié, et comment

| Test | Méthode | Résultat |
|---|---|---|
| **Compilation complète de tous les fichiers Android** | 80 stubs maison + `javac` | ✅ **0 erreur** |
| Parsing AST des 28 sources | `javalang` | ✅ 28/28 |
| `findViewById` vs XML, vue par vue | croisement | ✅ 66/66, aucun orphelin |
| Layouts + manifest bien formés | `ElementTree` | ✅ tous |
| `@string` / `@color` / `@style` référencés existent | croisement | ✅ aucun manquant |
| Collision de nom de fichier | test unitaire + mesure | ✅ corrigé, verrouillé par test |
| Taille d'un point GPX | mesure réelle | ✅ 343 octets |
| Budget d'écritures | calcul | ✅ 1 800 flush/h |
| Boucle de permissions | trace du flux | ✅ structurellement impossible |
| Tests purs | Utils 26 · Advanced 90 · DopFilter 52 · Policy 31 · Journal 58 | ✅ **257/257** |

### ⚠️ Ce qui ne peut PAS être vérifié ici

**Pas de SDK Android, pas d'émulateur, pas d'APK.** Les stubs maison
permettent de compiler et de détecter les erreurs de types et de signatures —
ce qui a déjà révélé plusieurs problèmes — mais ils **ne disent rien** de :

- la validité des ressources compilées (aapt2) ;
- le comportement réel d'`AndroidManifest` (validation, permissions) ;
- les erreurs à l'exécution (inflation XML, cycle de vie réel) ;
- les restrictions spécifiques d'un OEM.

Le crash de la boucle de permissions a été trouvé par **analyse du flux**,
pas en l'exécutant. Il est très probablement votre crash, mais si l'app crashe
**au lancement** (avant tout appui), le coupable est ailleurs et il faut le
logcat :

```
adb logcat -d | grep -A 30 "FATAL EXCEPTION"
```

C'est la seule chose qui donne la pile d'appel exacte. Sans elle, je ne peux
que raisonner — et je préfère vous le dire plutôt que d'inventer.

---

## 5. Prochaine étape recommandée

1. **Compiler dans Android Studio** — le premier build révélera ce que les
   stubs ne peuvent pas voir (aapt2, manifest merger, versions de Gradle).
2. **Au premier crash, garder le logcat complet.** La pile d'appel nomme la
   classe et la ligne ; sans elle, tout diagnostic est une conjecture.
3. Vérifier sur l'appareil que le partage fonctionne : enregistrer 2 minutes,
   Réglages → « PARTAGER LA DERNIÈRE TRACE », choisir QGIS ou un mail.
4. Vérifier le budget d'écriture réel : enregistrer 1 h, comparer la taille
   du fichier aux 1,18 Mo annoncés.
