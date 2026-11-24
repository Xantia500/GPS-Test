# GPS-Test — récupération du LOT 8C-R2A.3

Ce lot ajoute le banc de validation des documents GPX complets par les schémas Topografix 1.0 et 1.1. Aucun code de l'application (`app/src/main`) n'est modifié dans ce lot.

## Fichiers à remplacer / ajouter

Décompresser l'archive des fichiers à remplacer **à la racine du projet GPS-Test**, en conservant l'arborescence et en acceptant le remplacement de :

- `.gitignore`
- `.github/workflows/android-build.yml`
- `LISEZ_MOI_LOT_8C_R2A2.md` (addendum)
- `docs/LOT_8C_R2A3_BANC_XSD_2026-11-24.md`
- `tools/GpxXsdValidation.java`
- `tools/download_gpx_schemas.py`
- `tools/validate_gpx_xsd.py`

Les modifications de GitHub Actions sont uniquement des fichiers à déposer dans ton dépôt ; rien n'a été poussé à distance.

## Lancer localement la vérification XSD

Depuis la racine du projet :

```bash
python3 tools/download_gpx_schemas.py
python3 tools/validate_gpx_xsd.py
```

Cela nécessite Python 3, un JDK (javac + java) et un accès HTTPS aux domaines Topografix. Les XSD sont téléchargés dans `tools/xsd/`, ignoré par Git.

Le job CI `gpx-xsd` exécute ces mêmes commandes dans un job indépendant du build Android. Le job vérifie 4 cas : sortie GPX 1.0 valide, sortie GPX 1.1 valide, ordre GPX 1.0 erroné rejeté et élément `speed` GPX 1.1 illégal rejeté.

## Limite de cette remise

Dans l'environnement de préparation, DNS sortant est bloqué. Les scripts sont syntaxiquement valides et le validateur Java compile ; le harnais a aussi passé 4 tests sur des XSD minimaux temporaires. **La conformité contre les véritables XSD Topografix reste à confirmer** par l'exécution locale ou le job GitHub Actions. Ne pas confondre ces vérifications partielles avec un résultat XSD officiel.

Voir `docs/LOT_8C_R2A3_BANC_XSD_2026-11-24.md` pour les détails.
