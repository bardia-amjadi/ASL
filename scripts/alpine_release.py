#!/usr/bin/env python3
"""Print 'file|sha256|version' for the Alpine minirootfs entry in a latest-releases.yaml file.

The yaml is parsed with a tiny tolerant reader (no PyYAML dependency). sha256 may be empty if the
metadata does not list it; the caller then falls back to the published '<file>.sha256' sidecar.
"""
import re
import sys


def parse(text: str):
    entries, cur = [], {}
    for raw in text.splitlines():
        line = raw.rstrip()
        if not line or line.lstrip().startswith("#") or line == "---":
            continue
        m = re.match(r"^-\s*(.*)$", line)  # list item start (unindented '-')
        if m:
            if cur:
                entries.append(cur)
            cur = {}
            line = m.group(1)
            if not line:
                continue
        m = re.match(r"^\s*([A-Za-z0-9_]+):\s*(.*)$", line)
        if m:
            cur[m.group(1)] = m.group(2).strip().strip("\"'")
    if cur:
        entries.append(cur)
    return entries


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: alpine_release.py <latest-releases.yaml>", file=sys.stderr)
        return 2
    with open(sys.argv[1], encoding="utf-8", errors="replace") as fh:
        entries = parse(fh.read())

    for e in entries:
        name = e.get("file") or e.get("iso") or ""
        if e.get("flavor") == "alpine-minirootfs" and name.endswith(".tar.gz"):
            print(f"{name}|{e.get('sha256', '')}|{e.get('version', '')}")
            return 0

    print("ERROR: no alpine-minirootfs entry found; metadata format may have changed.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
