#!/usr/bin/env python3
"""Resolve the rootfs layer blob inside an OCI image layout, using only JSON files.

usage: oci_layer.py index <index.json>            -> prints the first manifest digest hex
       oci_layer.py manifest <manifest.json>      -> prints the first layer digest hex
"""
import json
import sys


def hex_of(digest: str) -> str:
    algo, _, value = digest.partition(":")
    if algo != "sha256" or not value:
        raise SystemExit(f"ERROR: unsupported digest '{digest}'")
    return value


def main() -> int:
    if len(sys.argv) != 3 or sys.argv[1] not in ("index", "manifest"):
        print(__doc__, file=sys.stderr)
        return 2
    with open(sys.argv[2], encoding="utf-8") as fh:
        doc = json.load(fh)
    if sys.argv[1] == "index":
        items = doc.get("manifests") or []
        if not items:
            print("ERROR: no manifests in index.json", file=sys.stderr)
            return 1
        print(hex_of(items[0]["digest"]))
    else:
        layers = doc.get("layers") or []
        if not layers:
            print("ERROR: no layers in manifest", file=sys.stderr)
            return 1
        print(hex_of(layers[0]["digest"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
