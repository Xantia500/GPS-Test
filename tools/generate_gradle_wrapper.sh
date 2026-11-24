#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
command -v gradle >/dev/null 2>&1 || {
  echo "Gradle local absent. Installer Gradle 9.9.2 ou utiliser la CI Android fournie." >&2
  exit 2
}
VERSION="$(gradle --version | awk '/^Gradle / {print $2; exit}')"
[[ "$VERSION" == "9.9.2" ]] || {
  echo "Gradle 9.9.2 est requis pour générer ce wrapper (détecté : ${VERSION:-inconnu})." >&2
  exit 2
}
gradle wrapper --gradle-version 9.9.2 --distribution-type bin \
  --gradle-distribution-sha256-sum 971fcd7150ca4ba04852c0b2ca0d2d0ab9468e14b03473eab19528809914c76b

echo "Wrapper généré. Vérifier les fichiers gradlew, gradlew.bat et gradle/wrapper/ avant de les versionner."
