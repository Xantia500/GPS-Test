#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Download and sanity-check the canonical Topografix GPX XSD schemas.

The files are generated local test inputs and are intentionally git-ignored.
No arbitrary schema URL or schemaLocation from a GPX document is ever fetched.
"""
from __future__ import annotations

import pathlib
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from urllib.parse import urlparse

ROOT = pathlib.Path(__file__).resolve().parents[1]
DEST = ROOT / "tools" / "xsd"
MAX_BYTES = 256 * 1024
XSD_NS = "http://www.w3.org/2001/XMLSchema"
SCHEMAS = (
    (
        "gpx-1.0.xsd",
        "https://www.topografix.com/GPX/1/0/gpx.xsd",
        "http://www.topografix.com/GPX/1/0",
        "1.0",
    ),
    (
        "gpx-1.1.xsd",
        "https://www.topografix.com/GPX/1/1/gpx.xsd",
        "http://www.topografix.com/GPX/1/1",
        "1.1",
    ),
)


def download(url: str) -> bytes:
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": "GPS-Test-GPX-XSD-Validator/1.0",
            "Accept": "application/xml, text/xml;q=0.9, */*;q=0.1",
        },
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        final_url = response.geturl()
        parsed = urlparse(final_url)
        if parsed.scheme.lower() != "https" or parsed.hostname not in {
            "www.topografix.com", "topografix.com"
        }:
            raise RuntimeError(f"redirection vers un hôte non autorisé refusée : {final_url}")
        data = response.read(MAX_BYTES + 1)
    if not data or len(data) > MAX_BYTES:
        raise RuntimeError(f"taille du schéma invalide ({len(data)} octets)")
    if b"<!DOCTYPE" in data.upper() or b"<!ENTITY" in data.upper():
        raise RuntimeError("DTD/entity externe interdit dans le schéma téléchargé")
    return data


def verify_schema(data: bytes, expected_namespace: str, version: str) -> None:
    root = ET.fromstring(data)
    if root.tag != f"{{{XSD_NS}}}schema":
        raise RuntimeError("la racine reçue n'est pas xsd:schema")
    if root.attrib.get("targetNamespace") != expected_namespace:
        raise RuntimeError(
            f"targetNamespace inattendu : {root.attrib.get('targetNamespace')!r}"
        )
    declarations = root.findall(f".//{{{XSD_NS}}}attribute[@name='version']")
    values = {
        node.attrib.get("fixed") or node.attrib.get("default")
        for node in declarations
    }
    if version not in values:
        raise RuntimeError(
            f"la déclaration du numéro de version {version!r} est absente ou inattendue"
        )
    if root.find(f"{{{XSD_NS}}}element[@name='gpx']") is None:
        raise RuntimeError("déclaration globale xsd:element name='gpx' absente")


def main() -> int:
    DEST.mkdir(parents=True, exist_ok=True)
    for filename, url, namespace, version in SCHEMAS:
        try:
            data = download(url)
            verify_schema(data, namespace, version)
            target = DEST / filename
            temporary = target.with_suffix(target.suffix + ".tmp")
            temporary.write_bytes(data)
            temporary.replace(target)
            print(f"OK {filename}: {len(data)} octets, namespace et version vérifiés")
        except (OSError, urllib.error.URLError, ET.ParseError, RuntimeError) as exc:
            print(f"ECHEC {filename}: {exc}", file=sys.stderr)
            return 1
    print(f"Schémas GPX téléchargés et contrôlés dans : {DEST.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
