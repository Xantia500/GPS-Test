#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Compile and run the JVM validator against the downloaded Topografix XSDs."""
from __future__ import annotations

import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]


def run(command: list[str]) -> None:
    print("$ " + " ".join(command), flush=True)
    subprocess.run(command, cwd=ROOT, check=True, timeout=180)


def main() -> int:
    required = [ROOT / "tools" / "xsd" / "gpx-1.0.xsd",
                ROOT / "tools" / "xsd" / "gpx-1.1.xsd"]
    missing = [p for p in required if not p.is_file()]
    if missing:
        print("Schémas GPX absents : " + ", ".join(str(p.relative_to(ROOT)) for p in missing),
              file=sys.stderr)
        print("Exécuter : python3 tools/download_gpx_schemas.py", file=sys.stderr)
        return 2

    javac = shutil.which("javac")
    java = shutil.which("java")
    if not javac or not java:
        print("JDK (javac et java) requis.", file=sys.stderr)
        return 2

    with tempfile.TemporaryDirectory(prefix="gpstest-xsd-") as output:
        run([
            javac, "-encoding", "UTF-8", "-d", output,
            "-sourcepath", "tools/stubs:app/src/main/java:tools",
            "-cp", "tools/stubs", "tools/GpxXsdValidation.java",
        ])
        run([
            java, "-cp", os.pathsep.join((output, str(ROOT / "tools" / "stubs"))),
            "com.example.gpstest.GpxXsdValidation", "tools/xsd",
        ])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
