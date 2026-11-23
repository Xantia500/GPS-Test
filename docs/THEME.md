# Thème — clair, sombre, et suivre le téléphone

> v5.5. Répond à : « suivre le thème actuel du téléphone MAIS aussi proposer
> une option ».
>
> **Le renommage a été annulé sur votre décision.** Rien n'a été modifié :
> `app_name` reste « GPS Test », `com.example.gpstest` reste l'`applicationId`,
> les répertoires et les noms de fichiers sont inchangés.

---

## 1. Les trois modes

| Mode | Effet | Qui décide |
|---|---|---|
| **Suivre le téléphone** (défaut) | bascule automatique jour/nuit | le système |
| **Clair** | toujours clair, même la nuit | l'utilisateur |
| **Sombre** | toujours sombre | l'utilisateur |

Réglages → **APPARENCE**, trois boutons radio, écriture **immédiate** (pas de
bouton « Enregistrer »), comme tout le reste de cet écran.

### Pourquoi proposer un mode explicite alors que le téléphone a déjà ce réglage

Parce qu'un utilisateur peut vouloir une app GPS **toujours sombre** (usage de
nuit, en voiture) pendant que son téléphone bascule automatiquement au coucher
du soleil. Depuis Android 10, un réglage d'apparence par application est une
attente normale ; son absence se remarque.

---

## 2. Comment « suivre le téléphone » fonctionne — et pourquoi je ne me sers pas d'AppCompat

`Theme.GpsTest` hérite de `Theme.MaterialComponents.DayNight.NoActionBar`.
La famille `DayNight` fait exactement ce qu'on veut : elle lit `uiMode` et
sélectionne `values/` ou `values-night/`. **Aucune ligne de Java** n'est
nécessaire pour suivre le système.

### Pourquoi pas `AppCompatDelegate.setDefaultNightMode`

C'est l'API « officielle » pour forcer un mode. Elle **recrée les Activities**
à chaque changement, y compris quand le framework le décide. Pour une app de
mesure GNSS qui peut être en train d'enregistrer une trace, un clignotement
d'écran et une perte d'état ne sont pas acceptables.

On force donc `uiMode` directement dans les `Resources` (API 17+), et on
appelle `recreate()` **une seule fois**, à la demande explicite de
l'utilisateur. C'est ce que fait AppCompat en interne, sans la magie.

### La limite honnête de `updateConfiguration`

Dépréciée depuis l'API 25 pour les changements de configuration généraux. Elle
reste le seul mécanisme **synchrone** pour forcer `uiMode`, et elle fonctionne
sur toutes les versions actuelles. Elle n'est **pas garantie à long terme**.

Tout le mécanisme est isolé dans `ThemeManager.java` : si un jour il faut
migrer vers `AppCompatDelegate`, **un seul fichier** bouge.

---

## 3. Le piège que j'ai trouvé en écrivant ce code

**Quand on force le mode, on écrase `uiMode` dans les `Resources` — et cette
modification vit aussi longtemps que le processus.** Après avoir forcé
« Clair », plus personne, y compris l'application, ne peut relire le réglage
du téléphone : il a été écrasé.

Scénario réel sans garde-fou :

1. téléphone en mode nuit, l'app force **Clair** ;
2. l'utilisateur repasse à « Suivre le téléphone » → on ne force plus rien,
   donc **on ne restaure rien** ;
3. l'app **reste claire** alors que le téléphone est en mode nuit — jusqu'au
   prochain redémarrage du processus.

**Correctif** : à la première utilisation dans le processus, *avant* qu'aucun
forçage n'ait eu lieu, on lit le vrai réglage et on le mémorise
(`systemNightBits`). « Suivre le téléphone » ensuite **restaure** cette valeur
au lieu de ne rien faire.

---

## 4. Les défauts trouvés en audit — et il y en avait de graves

### 4.1 Bouton principal invisible en mode clair

`colorOnPrimary` valait `@color/gps_text`, soit `#14261A` en clair. Sur le vert
foncé `#1B5E20` du bouton principal :

| Contraste | Verdict |
|---|---|
| avant : #14261A sur #1B5E20 | **2,02:1 — illisible** |
| après : #FFFFFF sur #1B5E20 | 7,87:1 — conforme |

C'est le piège classique du thème : un jeton **correct dans un mode et faux
dans l'autre**. Le vert foncé étant foncé dans les deux modes, la couleur du
texte dessus doit être **constante**, pas thémée.

### 4.2 Liste des satellites invisible en mode clair

Un `ListView` est **transparent** par défaut. En mode sombre, cela ne se voyait
pas : le texte clair tombait sur le fond sombre de l'écran. En mode clair, le
même texte reste clair et devient **invisible sur fond clair**.

Correctif : `@drawable/list_bg`, opaque, tiré de `@color/gps_card`.

### 4.3 Générateur `R.java` incomplet

`tools/gen_r.py` n'émettait **aucune classe `R.drawable`**. `R.drawable.list_bg`
n'aurait donc jamais existé, même après `aapt2` — et l'erreur est silencieuse
tant qu'aucun code Java ne référence un drawable.

Correctif : le générateur émet maintenant `drawable` (formes XML **et** PNG
mipmap) et `mipmap`.

### 4.4 Couleurs figées dans les layouts et le code

| Endroit | Avant | Après |
|---|---|---|
| `dialog_start_confirm.xml` | 5 couleurs en dur | `@color/…` |
| `activity_main.xml` | 1 couleur en dur | `@color/gps_text_dim` |
| `SkyView` | 3 `Color.parseColor` | ressources + `onConfigurationChanged` |
| `SevenSegmentView` | 3 `Color.parseColor` | ressources + `onConfigurationChanged` |
| `SatelliteAdapter` | 5 `Color.parseColor` | ressources |

**Résultat : 0 couleur en dur dans tout le projet.**

### 4.5 Le segment éteint de l'afficheur 7 segments

Sur fond sombre, les segments **éteints** sont peints en `#1B3320` : c'est ce
qui donne l'aspect « LED éteinte ». Sur fond clair, la même couleur serait
invisible — l'afficheur paraîtrait cassé. D'où `@color/gps_seg_dim`, qui vaut
`#C8D6CA` en clair et `#1B3320` en sombre.

### 4.6 Clé de préférence instable, deux fois

| Ce qui était persisté | Le problème | Correction |
|---|---|---|
| `dest_label` — le **libellé** du dossier | texte affiché, donc traduit, et contenant une apostrophe | `dest_preset_id` = `"download"` / `"documents"` / `"app"` |
| mode de thème | — | `"system"` / `"light"` / `"dark"`, figés par des tests |

Un texte affiché **n'est pas une clé** : corriger une faute de frappe dans un
libellé ferait perdre son réglage à tous les utilisateurs.

---

## 5. Contraste mesuré (WCAG)

Seuil WCAG AA pour du texte normal : **4,5:1**. Mesuré, pas estimé :

| Élément | Mode clair | Mode sombre |
|---|---|---|
| Texte principal | **15,11:1** | **15,97:1** |
| Texte secondaire | 5,39:1 | 6,71:1 |
| Vert (accent) | 4,88:1 | 10,18:1 |
| Ambre | 4,03:1 ⚠ | 11,02:1 |
| Rouge | 5,35:1 | 4,25:1 ⚠ |
| Bouton principal (blanc sur vert) | 7,87:1 | 5,13:1 |

⚠ = passe pour du **gros texte ou du graphique**, en dessous du seuil pour du
texte fin. L'ambre et le rouge servent à des alertes courtes et souvent en
gras — acceptable, mais je préfère le dire plutôt que de prétendre que tout
est parfait.

---

## 6. Fichiers touchés

| Fichier | Rôle |
|---|---|
| `res/values/colors.xml` | palette **claire** (12 noms) |
| `res/values-night/colors.xml` | palette **sombre** (les 12 mêmes noms) |
| `res/values/themes.xml` | `DayNight`, barres système, overlay de dialogue |
| `res/drawable/list_bg.xml` | fond opaque des listes |
| `res/layout/activity_settings.xml` | section APPARENCE |
| `res/layout/dialog_start_confirm.xml` | couleurs référencées |
| `res/layout/activity_main.xml` | fond de la liste |
| `ThemeMode.java` | logique **pure** (testée) |
| `ThemeManager.java` | effets Android (`uiMode`, couleurs) |
| `Prefs.java` | `theme_mode`, `dest_preset_id` |
| `SkyView.java`, `SevenSegmentView.java`, `SatelliteAdapter.java` | couleurs par ressources |
| `SettingsActivity.java` | sélecteur, `recreate()` unique |
| `MainActivity.java`, `ExpertActivity.java` | `applyTo()` avant `setContentView` |
| `tools/gen_r.py` | émet `drawable` et `mipmap` |

---

## 7. Ce qui ne peut PAS être vérifié ici

| Point | Pourquoi |
|---|---|
| Rendu réel des couleurs | pas d'écran, pas d'`aapt2` |
| `values-night` pris en compte | c'est le rôle d'`aapt2` au build |
| Bascule réelle de `uiMode` | besoin d'un appareil ou d'un émulateur |
| `recreate()` visible | idem |

**Vérifié ici** : compilation contre le vrai `android.jar` API 34 (**0
erreur**), **308 tests** unitaires, contrat des 12 couleurs jour/nuit
identique, **0 couleur en dur**, 89 IDs Java ⇄ XML, 0 string orpheline.
