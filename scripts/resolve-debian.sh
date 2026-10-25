#!/usr/bin/env bash
# Resolves WHICH Debian rootfs the app is allowed to download, and pins its SHA-256.
#
# Trust chain (see docs/ADR-0002-debian-source.md):
#   1. docker-library/official-images "library/debian" names the reviewed git commit of each
#      per-architecture artifacts branch (debuerreotype/docker-debian-artifacts, dist-<arch>).
#   2. We pin that exact COMMIT (immutable), not a moving branch.
#   3. We download the artifact at that commit over HTTPS, compute its SHA-256 and write it into the APK.
#   4. On the phone the app downloads the same pinned URL and refuses anything whose SHA-256 differs.
#
# Output (git-ignored): app/src/main/assets/distro/<abi>/debian.info
# Requires: bash curl python3 sha256sum tar od stat head
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$ROOT/build-tmp/debian"
OUT="$ROOT/app/src/main/assets/distro"
TAG="${DEBIAN_TAG:-trixie-slim}"
LIB_URL="${LIB_URL:-https://raw.githubusercontent.com/docker-library/official-images/master/library/debian}"
ART_REPO="${ART_REPO:-https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts}"

for tool in curl python3 sha256sum tar od stat head awk; do
  command -v "$tool" >/dev/null 2>&1 || { echo "ERROR: required tool '$tool' not found in PATH" >&2; exit 1; }
done

rm -rf "$WORK" "$OUT"
mkdir -p "$WORK"
curl -fsSL "$LIB_URL" -o "$WORK/library-debian"

SUITE="${TAG%%-*}"
VARIANT="${TAG#*-}"
[ "$VARIANT" = "$TAG" ] && VARIANT="standard"

# "<android abi> <docker arch>"
TARGETS=("arm64-v8a arm64v8" "armeabi-v7a arm32v7")

for target in "${TARGETS[@]}"; do
  read -r ABI DARCH <<<"$target"
  echo "=== $ABI ($DARCH, tag $TAG) ==="

  entry="$(python3 "$ROOT/scripts/official_images.py" "$WORK/library-debian" "$TAG" "$DARCH")"
  IFS='|' read -r COMMIT DIR <<<"$entry"
  echo "  reviewed commit: $COMMIT  directory: $DIR"
  base="$ART_REPO/$COMMIT/$DIR"

  found=""
  for cand in "oci/blobs/rootfs.tar.gz" "rootfs.tar.xz"; do
    if curl -fsIL "$base/$cand" -o /dev/null 2>/dev/null; then found="$cand"; break; fi
  done
  [ -n "$found" ] || { echo "ERROR: no rootfs artifact found under $base (tried oci/blobs/rootfs.tar.gz, rootfs.tar.xz); the repository layout may have changed" >&2; exit 1; }
  URL="$base/$found"
  echo "  artifact: $URL"

  FILE="$WORK/$ABI.archive"
  curl -fsSL "$URL" -o "$FILE"
  SHA="$(sha256sum "$FILE" | awk '{print $1}')"
  SIZE="$(stat -c %s "$FILE")"
  MAGIC="$(head -c 6 "$FILE" | od -An -tx1 | tr -d ' \n')"
  case "$MAGIC" in
    1f8b*) FORMAT="gz" ;;
    fd377a585a00) FORMAT="xz" ;;
    *) echo "ERROR: unknown archive format (magic $MAGIC)" >&2; exit 1 ;;
  esac

  VERSION="$(tar -xOf "$FILE" ./etc/debian_version 2>/dev/null || tar -xOf "$FILE" etc/debian_version 2>/dev/null || true)"
  VERSION="$(echo "$VERSION" | head -n1 | tr -d '\r\n ')"
  [ -n "$VERSION" ] || VERSION="unknown"
  echo "  format=$FORMAT size=$SIZE sha256=$SHA debian_version=$VERSION"

  mkdir -p "$OUT/$ABI"
  {
    echo "name=Debian"
    echo "suite=$SUITE"
    echo "variant=$VARIANT"
    echo "version=$VERSION"
    echo "abi=$ABI"
    echo "url=$URL"
    echo "format=$FORMAT"
    echo "size=$SIZE"
    echo "sha256=$SHA"
    echo "source_repo=https://github.com/debuerreotype/docker-debian-artifacts"
    echo "source_commit=$COMMIT"
    echo "pinned_by=docker-library/official-images library/debian"
  } > "$OUT/$ABI/debian.info"
done

echo
echo "Debian manifests ready:"
find "$OUT" -type f -exec ls -l {} \;
