#!/usr/bin/env python3
"""Print 'GitCommit|Directory' for one tag/architecture from Docker Official Images' library/debian file.

The file is a sequence of blank-line separated blocks: a global header (defaults such as
'arm64v8-GitCommit: ...') followed by one block per group of tags. A block may override a global key.
"""
import re
import sys


def parse_block(block: str) -> dict:
    fields = {}
    for line in block.splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        m = re.match(r"^([A-Za-z0-9_-]+):\s*(.*)$", line)
        if m:
            fields[m.group(1)] = m.group(2).strip()
    return fields


def main() -> int:
    if len(sys.argv) != 4:
        print("usage: official_images.py <library-file> <tag> <arch e.g. arm64v8>", file=sys.stderr)
        return 2
    path, tag, arch = sys.argv[1:]
    with open(path, encoding="utf-8", errors="replace") as fh:
        text = fh.read()

    defaults, stanza = {}, None
    for block in re.split(r"\n\s*\n", text):
        fields = parse_block(block)
        if not fields:
            continue
        if "Tags" in fields:
            tags = [t.strip() for t in fields["Tags"].split(",")]
            if tag in tags:
                stanza = {**defaults, **fields}
                break
        else:
            defaults.update(fields)

    if stanza is None:
        print(f"ERROR: tag '{tag}' not found in {path}", file=sys.stderr)
        return 1
    archs = [a.strip() for a in stanza.get("Architectures", "").split(",") if a.strip()]
    if archs and arch not in archs:
        print(f"ERROR: architecture '{arch}' is not published for tag '{tag}' (have: {', '.join(archs)})", file=sys.stderr)
        return 1
    commit = stanza.get(f"{arch}-GitCommit") or stanza.get("GitCommit")
    directory = stanza.get("Directory")
    if not commit or not directory:
        print(f"ERROR: no {arch}-GitCommit / Directory for tag '{tag}'", file=sys.stderr)
        return 1
    print(f"{commit}|{directory}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
