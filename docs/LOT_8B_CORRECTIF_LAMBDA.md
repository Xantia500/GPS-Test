# Correctif complémentaire LOT 8B — capture de `fullTracking` dans une lambda

Date : 24 novembre 2026

## Erreur remontée par GitHub Actions

La tâche `:app:compileDebugJavaWithJavac` échouait dans `TrackingService.java` autour de la ligne 3574 :

```text
local variables referenced from a lambda expression must be final or effectively final
```

La variable locale `Boolean fullTracking` est initialisée à `null`, puis éventuellement renseignée lorsque `GnssMeasurementsEvent` annonce `hasIsFullTracking()` sur Android 14+. Elle est donc réaffectée et ne peut pas être capturée directement par la lambda passée à `ui.post()`.

## Correction

Le texte d'état est maintenant construit avant la lambda dans une chaîne immuable :

```java
final String fullTrackingStatus = Build.VERSION.SDK_INT >= 34
        ? " · full tracking rapporté : "
                + (fullTracking == null ? "—" : fullTracking.toString())
        : "";
```

La lambda UI concatène ensuite uniquement `fullTrackingStatus`. Le comportement affiché reste inchangé :

- API 34+ et valeur `true` : `true` est affiché ;
- API 34+ et valeur `false` : `false` est affiché ;
- API 34+ et champ absent/non fourni : `—` est affiché ;
- API antérieure à 34 : le suffixe n'est pas affiché.

Les valeurs utilisées dans l'écriture CSV (`fullTracking`) restent inchangées et continuent d'être passées directement au formateur avant la publication UI.

## Prévention de régression

`tools/verify_android_project.py` vérifie désormais que :

1. `fullTrackingStatus` est une variable `final` préparée avant publication ;
2. la lambda UI utilise cet instantané ;
3. la lambda ne référence plus directement la variable mutable `fullTracking`.

Ce contrôle statique complète la compilation Android réelle ; il ne la remplace pas.

## Vérifications de cette livraison

- `tools/verify_android_project.py` : à exécuter après modification ;
- `tools/verify_offline_map_architecture.py` : à exécuter après modification ;
- tests JVM : 38 suites, 1 288 assertions attendues ;
- compilation Android AGP 9.5.2 : non exécutée dans l'environnement local. Le dernier journal utilisateur concernait Gradle 8.13 et signalait cette erreur Java avant la mise à jour d'outillage ; il faut pousser la livraison combinée puis relancer GitHub Actions pour valider l'ensemble.

## Fichiers modifiés

- `app/src/main/java/com/example/gpstest/TrackingService.java`
- `tools/verify_android_project.py`
