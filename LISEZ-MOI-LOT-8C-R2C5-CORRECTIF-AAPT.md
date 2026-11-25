# LOT 8C-R2C.5-AAPT.1 — Correctif de ressources

1. Décompresser cette archive à la racine du projet GPS-Test.
2. Accepter le remplacement de `app/src/main/res/values/strings.xml` et `tools/verify_android_project.py`.
3. Les notes et le dossier de reprise sont également mis à jour.
4. Relancer ta compilation habituelle `./gradlew --no-daemon --stacktrace :app:assembleDebug`.

Le correctif échappe les apostrophes AAPT2 dans les nouvelles chaînes et ajoute un contrôle précoce des apostrophes et des séquences `\\uXXXX` dans `verify_android_project.py`.

Les tests JVM et audits statiques ont été relancés et passent. La compilation Gradle n'a pas été relancée par l'assistant, conformément à la consigne : elle reste à confirmer de ton côté.

Aucun patch Git à appliquer. Aucun fichier GitHub n'a été modifié.

Détails : `docs/LOT-8C-R2C5-CORRECTIF-AAPT-APOSTROPHES-2026-11-25.md`.
