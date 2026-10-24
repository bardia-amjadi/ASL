#!/usr/bin/env python3
"""Print 'Filename|SHA256|Version' for one package from an apt 'Packages' index."""
import sys


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: termux_index.py <Packages-file> <package>", file=sys.stderr)
        return 2
    path, wanted = sys.argv[1], sys.argv[2]
    with open(path, encoding="utf-8", errors="replace") as fh:
        text = fh.read()

    found = None
    for stanza in text.split("\n\n"):
        fields = {}
        for line in stanza.splitlines():
            if line.startswith((" ", "\t")) or ":" not in line:
                continue  # continuation line
            key, _, value = line.partition(":")
            fields[key.strip()] = value.strip()
        if fields.get("Package") == wanted:
            found = fields  # keep the last entry if several versions are listed

    if not found:
        print(f"ERROR: package '{wanted}' not found in {path}", file=sys.stderr)
        return 1
    for key in ("Filename", "SHA256", "Version"):
        if not found.get(key):
            print(f"ERROR: package '{wanted}' has no '{key}' field", file=sys.stderr)
            return 1
    print(f"{found['Filename']}|{found['SHA256']}|{found['Version']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
