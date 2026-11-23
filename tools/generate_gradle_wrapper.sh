#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
command -v gradle >/dev/null 2>&1 || {
  echo "Gradle local absent. Installer Gradle 8.13 ou utiliser la CI Android fournie." >&2
  exit 2
}
VERSION="$(gradle --version | awk '/^Gradle / {print $2; exit}')"
[[ "$VERSION" == "8.13" ]] || {
  echo "Gradle 8.13 est requis pour générer ce wrapper (détecté : ${VERSION:-inconnu})." >&2
  exit 2
}
gradle wrapper --gradle-version 8.13 --distribution-type bin

echo "Wrapper généré. Vérifier les fichiers gradlew, gradlew.bat et gradle/wrapper/ avant de les versionner."
