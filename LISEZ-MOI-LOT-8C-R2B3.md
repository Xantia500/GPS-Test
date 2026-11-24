# LOT 8C-R2B.3 — fichiers à remplacer

Cette archive contient les fichiers nouveaux et modifiés du LOT 8C-R2B.3.

1. Décompresser à la racine du projet GPS-Test (au même niveau que `app`, `tests`, `tools`, `docs` et `.github`).
2. Conserver l'arborescence proposée et accepter le remplacement de `MainActivity.java`, `TrackingService.java` et du workflow.
3. Les autres fichiers sont nouveaux : le moteur de récupération, son test, le contrôle statique, la note technique et ce guide.
4. Ouvrir le projet dans Android Studio et lancer Sync/Build comme d'habitude.

Aucun patch Git n'est à appliquer. Aucun fichier de GitHub n'a été modifié par l'assistant.

## Fonctionnement à connaître

Après une interruption détectée, GPS-Test tente de produire une copie distincte `*_RECUPEREE.GPX` dans son dossier GPX app-specific, à partir de `safeLengthBytes`. Le fichier d'origine n'est jamais tronqué ou supprimé. La queue située après le checkpoint n'est pas incluse. Si le préfixe est incomplet ou non parseable, la récupération échoue sans altérer l'original.

La copie est contrôlée comme XML GPX bien formé (version et namespace). Ce n'est pas une validation XSD ; le job GPX XSD de CI demeure séparé.

## Résultats locaux de préparation

43 suites JVM, 1 490 assertions, zéro échec ; audit Android statique, audit cartographique et contrôles structurels R2B.1/R2B.2/R2B.3 réussis. La compilation Android de ce lot reste à faire dans ton Android Studio : Gradle/Wrapper ne sont pas disponibles dans l'environnement de préparation.
