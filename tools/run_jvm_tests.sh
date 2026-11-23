#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

command -v javac >/dev/null 2>&1 || { echo "Erreur : javac est requis (JDK 17 recommandé)." >&2; exit 2; }
command -v java >/dev/null 2>&1 || { echo "Erreur : java est requis." >&2; exit 2; }
command -v python3 >/dev/null 2>&1 || { echo "Erreur : python3 est requis pour générer R.java et agréger les résultats." >&2; exit 2; }

python3 tools/gen_r.py
OUT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/gpstest-jvm-tests.XXXXXX")"
trap 'rm -rf "$OUT_DIR"' EXIT

mapfile -t SOURCES < <(find tests -maxdepth 1 -name '*Test.java' -type f | sort)
if [[ ${#SOURCES[@]} -eq 0 ]]; then
  echo "Aucune suite de test trouvée dans tests/." >&2
  exit 3
fi

javac -encoding UTF-8 \
  -d "$OUT_DIR" \
  -sourcepath "tools/stubs:app/src/main/java:tests" \
  -cp tools/stubs \
  "${SOURCES[@]}"

TOTAL=0
for SOURCE in "${SOURCES[@]}"; do
  CLASS="$(basename "$SOURCE" .java)"
  echo "===== $CLASS ====="
  OUTPUT="$(java -cp "$OUT_DIR:tools/stubs" "com.example.gpstest.$CLASS")"
  printf '%s\n' "$OUTPUT"
  COUNT="$(printf '%s\n' "$OUTPUT" | python3 -c 'import re,sys; s=sys.stdin.buffer.read().decode("utf-8", "replace"); m=re.findall(r"(?:RESULTAT\s*:\s*)?(\d+)\s+r.?ussis\s*,\s*0\s+.?chou.?s", s, re.I); print(m[-1] if m else "")')"
  if [[ -z "$COUNT" ]]; then
    echo "Impossible de déterminer le résultat de $CLASS." >&2
    exit 4
  fi
  TOTAL=$((TOTAL + COUNT))
done

echo "TOTAL : ${#SOURCES[@]} suites, $TOTAL assertions réussies, 0 échec."
