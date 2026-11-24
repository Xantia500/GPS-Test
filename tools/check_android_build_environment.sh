#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

fail() { echo "ERREUR : $*" >&2; exit 2; }
command -v java >/dev/null 2>&1 || fail "JDK absent. Installer JDK 17 ou supérieur."

# Prefer the versioned project Wrapper once it has been added to the repo.
# Keep the pinned system-Gradle fallback for checkouts predating that commit.
GRADLE_CMD=()
if [[ -f "$ROOT/gradlew" ]]; then
  GRADLE_CMD=(bash "$ROOT/gradlew")
elif command -v gradle >/dev/null 2>&1; then
  GRADLE_CMD=(gradle)
else
  fail "Gradle absent et Gradle Wrapper non versionné. Récupérer gradle-wrapper-bootstrap depuis GitHub Actions, ou installer Gradle 9.9.2."
fi

JAVA_VERSION="$(java -version 2>&1 | head -1)"
JAVA_MAJOR="$(printf '%s' "$JAVA_VERSION" | sed -E 's/.*version "([0-9]+).*/\1/')"
[[ "$JAVA_MAJOR" =~ ^[0-9]+$ ]] || fail "Impossible de déterminer la version Java : $JAVA_VERSION"
(( JAVA_MAJOR >= 17 )) || fail "JDK 17 minimum requis par Android Gradle Plugin 9.5.2 (détecté : $JAVA_VERSION)."

GRADLE_VERSION="$("${GRADLE_CMD[@]}" --version | awk '/^Gradle / {print $2; exit}')"
[[ "$GRADLE_VERSION" == "9.9.2" ]] || fail "Gradle 9.9.2 attendu pour ce build épinglé (détecté : ${GRADLE_VERSION:-inconnu})."

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[[ -n "$SDK_ROOT" && -d "$SDK_ROOT" ]] || fail "ANDROID_SDK_ROOT/ANDROID_HOME absent ou invalide."
[[ -f "$SDK_ROOT/platforms/android-37.2/android.jar" ]] || fail "Android SDK Platform 37.2 absent : sdkmanager 'platforms;android-37.2'."
[[ -d "$SDK_ROOT/build-tools/36.0.0" ]] || fail "Android Build Tools 36.0.0 absents : sdkmanager 'build-tools;36.0.0'."

python3 tools/verify_android_project.py
python3 tools/verify_offline_map_architecture.py
bash tools/run_jvm_tests.sh

echo "--- Compilation Android ---"
"${GRADLE_CMD[@]}" --no-daemon --stacktrace :app:assembleDebug :app:lintDebug

echo "APK debug : app/build/outputs/apk/debug/app-debug.apk"
