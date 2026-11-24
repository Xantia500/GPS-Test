#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Compile and execute each JVM regression suite in an isolated process.

A per-suite timeout prevents an accidentally leaked non-daemon thread from
blocking the whole CI job after the assertions have already completed.
"""
from __future__ import annotations

import pathlib
import re
import subprocess
import sys
import tempfile
import time
import unicodedata

ROOT = pathlib.Path(__file__).resolve().parents[1]
TIMEOUT_SECONDS = 45


def normalize(text: str) -> str:
    text = unicodedata.normalize("NFKD", text)
    return "".join(ch for ch in text if not unicodedata.combining(ch)).lower()


def fail(message: str, code: int = 1) -> None:
    print(f"ERREUR : {message}", file=sys.stderr)
    raise SystemExit(code)


def main() -> int:
    javac = subprocess.run(["bash", "-lc", "command -v javac"], cwd=ROOT,
                           capture_output=True, text=True)
    java = subprocess.run(["bash", "-lc", "command -v java"], cwd=ROOT,
                          capture_output=True, text=True)
    if javac.returncode != 0 or java.returncode != 0:
        fail("javac et java (JDK 17 recommandé) sont nécessaires.", 2)

    try:
        subprocess.run([sys.executable, "tools/gen_r.py"], cwd=ROOT,
                       check=True, timeout=30)
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
        fail(f"génération de R.java impossible : {exc}", 3)

    sources = sorted((ROOT / "tests").glob("*Test.java"))
    if not sources:
        fail("Aucune suite *Test.java trouvée dans tests/.", 4)

    with tempfile.TemporaryDirectory(prefix="gpstest-jvm-tests-") as out_dir:
        compile_cmd = [
            "javac", "-encoding", "UTF-8", "-d", out_dir,
            "-sourcepath", "tools/stubs:app/src/main/java:tests",
            "-cp", "tools/stubs",
            *[str(path.relative_to(ROOT)) for path in sources],
        ]
        try:
            compiled = subprocess.run(compile_cmd, cwd=ROOT, capture_output=True,
                                      text=True, timeout=180)
        except subprocess.TimeoutExpired:
            fail("javac a dépassé 180 secondes.", 5)
        if compiled.returncode != 0:
            print(compiled.stdout, end="")
            print(compiled.stderr, end="", file=sys.stderr)
            fail("échec de compilation des suites JVM.", 6)

        total_assertions = 0
        failures: list[str] = []
        for index, source in enumerate(sources, start=1):
            class_name = source.stem
            start = time.monotonic()
            try:
                result = subprocess.run(
                    ["java", "-cp", f"{out_dir}:tools/stubs",
                     f"com.example.gpstest.{class_name}"],
                    cwd=ROOT, capture_output=True, text=True,
                    errors="replace", timeout=TIMEOUT_SECONDS,
                )
            except subprocess.TimeoutExpired as exc:
                failures.append(f"{class_name}: délai dépassé ({TIMEOUT_SECONDS}s)")
                print(f"[{index:02d}/{len(sources)}] TIMEOUT {class_name}")
                tail = (exc.stdout or "")
                if isinstance(tail, bytes):
                    tail = tail.decode("utf-8", "replace")
                if tail:
                    print(tail[-1500:])
                continue

            combined = normalize(result.stdout + "\n" + result.stderr)
            matches = re.findall(
                r"(\d+)\s+r.?ussis\s*,\s*0\s+.?chou.?s", combined, re.I
            )
            elapsed = time.monotonic() - start
            if result.returncode != 0 or not matches:
                failures.append(f"{class_name}: code={result.returncode}, résultat non reconnu")
                print(f"[{index:02d}/{len(sources)}] ECHEC {class_name} ({elapsed:.1f}s)")
                print((result.stdout + "\n" + result.stderr)[-2500:])
                continue

            count = int(matches[-1])
            total_assertions += count
            print(f"[{index:02d}/{len(sources)}] OK {class_name}: {count} assertions ({elapsed:.1f}s)")

        print(f"TOTAL : {len(sources)} suites, {total_assertions} assertions réussies, {len(failures)} échec(s).")
        if failures:
            for failure in failures:
                print(f" - {failure}")
            return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
