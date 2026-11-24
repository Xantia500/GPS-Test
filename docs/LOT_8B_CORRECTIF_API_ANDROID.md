# LOT 8B.1 — Correctifs de compatibilité des API Android

Date : 23 novembre 2026  
Base : `GPS-Test-lot-8B.zip`  
Déclencheur : première compilation réelle GitHub Actions (`:app:compileDebugJavaWithJavac`).

## 1. Erreur `DownloadManager.ERROR_HTTP_NOT_FOUND`

Fichier : `app/src/main/java/com/example/gpstest/OfflineMapsActivity.java`.

L'API publique Android `android.app.DownloadManager` ne définit pas de constante `ERROR_HTTP_NOT_FOUND`. Elle expose `ERROR_UNHANDLED_HTTP_CODE` pour le cas où le gestionnaire reçoit un code HTTP qu'il ne sait pas traiter.

Correction : suppression du `case` inexistant. Le message associé à `ERROR_UNHANDLED_HTTP_CODE` précise désormais qu'une carte absente ou une URL incorrecte est une possibilité, sans affirmer qu'il s'agit nécessairement d'une réponse HTTP 404 : `COLUMN_REASON` ne fournit pas le code HTTP exact.

Référence : https://developer.android.com/reference/android/app/DownloadManager#ERROR_UNHANDLED_HTTP_CODE

## 2. Erreur `LocationRequest.Builder.setMaxUpdateAgeMillis`

Fichier : `app/src/main/java/com/example/gpstest/TrackingService.java`.

Le code utilise `android.location.LocationRequest.Builder` (API Android plateforme, disponible à partir de l'API 31). Cette classe ne définit pas `setMaxUpdateAgeMillis(long)`. Cette méthode ne doit pas être confondue avec les méthodes de builders proposés par d'autres API de localisation.

Correction : suppression de cet appel. Les paramètres pris en charge restent :

- intervalle demandé : `locationIntervalMs` ;
- intervalle minimal : `locationIntervalMs` ;
- distance minimale : `0 m` ;
- qualité : `QUALITY_HIGH_ACCURACY` ;
- délai maximal de batching : `0 ms`.

La fraîcheur réelle des positions reçues continue d'être contrôlée dans `FixQuality` avec l'horloge monotone fournie par `Location.getElapsedRealtimeNanos()` et `MAX_FIX_AGE_MS = 10 000 ms`. Aucun contrôle n'est déplacé sur l'heure civile.

Référence : https://developer.android.com/reference/android/location/LocationRequest.Builder

## 3. Fidélité des stubs et prévention de régression

Le stub JVM `tools/stubs/android/location/LocationRequest.java` ne contient plus la méthode fictive `setMaxUpdateAgeMillis()`. Avant cette correction, ce stub incomplet/inexact laissait passer la méthode dans les tests JVM alors que le SDK Android réel la rejetait.

Le vérificateur `tools/verify_android_project.py` contrôle désormais :

- l'absence de la constante inexistante `ERROR_HTTP_NOT_FOUND` ;
- la présence du traitement `ERROR_UNHANDLED_HTTP_CODE` ;
- l'absence d'appel à `.setMaxUpdateAgeMillis(...)` sur l'API plateforme ;
- la présence du contrôle d'âge monotone dans le chemin GNSS.

## 4. Vérifications effectuées après correction

| Contrôle | Résultat |
|---|---:|
| Vérification statique Android | OK — 17 XML, 80 sources Java |
| Architecture cartographique locale | OK — 98 fichiers examinés |
| Compilation des sources de tests JVM | OK |
| Suites JVM | 38 / 38 |
| Assertions | 1 288 réussies, 0 échec |
| Intégrité des archives | OK |

Les tests JVM ne compilent pas l'ensemble des classes Android contre `android.jar` et les dépendances AndroidX/Mapsforge. La compilation Gradle réelle n'a pas été réexécutée dans cet environnement. La prochaine étape est de pousser ces modifications dans le dépôt GitHub, puis de relancer `Android build and audit` pour découvrir si d'autres erreurs de compilation suivent.
