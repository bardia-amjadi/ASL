#!/usr/bin/env bash
# Resolves WHICH Debian rootfs images the app is allowed to download, and pins their SHA-256.
#
# Trust chain (see docs/ADR-0002-debian-source.md):
#   1. docker-library/official-images "library/debian" names the reviewed git commit of each
#      per-architecture artifacts branch (debuerreotype/docker-debian-artifacts, dist-<arch>).
#   2. We pin that exact COMMIT (immutable), not a moving branch.
#   3. We download the artifact at that commit over HTTPS, compute its SHA-256 and write it into the APK.
#   4. On the phone the app downloads the same pinned URL and refuses anything whose SHA-256 differs.
#
# Output (git-ignored): app/src/main/assets/distro/<abi>/<id>.info   for id in: debian (13), debian12
# Requires: bash curl python3 sha256sum tar od stat head awk
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$ROOT/build-tmp/debian"
OUT="$ROOT/app/src/main/assets/distro"
LIB_URL="${LIB_URL:-https://raw.githubusercontent.com/docker-library/official-images/master/library/debian}"
ART_REPO="${ART_REPO:-https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts}"

# "<docker tag> <app distro id>"
IMAGES=("trixie-slim debian" "bookworm-slim debian12")
# "<android abi> <docker arch>"
TARGETS=("arm64-v8a arm64v8" "armeabi-v7a arm32v7")

EXPECT_LAYER_SHA=""
for tool in curl python3 sha256sum tar od stat head awk; do
  command -v "$tool" >/dev/null 2>&1 || { echo "ERROR: required tool '$tool' not found in PATH" >&2; exit 1; }
done

mkdir -p "$OUT" "$WORK"
find "$OUT" -name 'debian*.info' -delete 2>/dev/null || true
curl -fsSL "$LIB_URL" -o "$WORK/library-debian"

for image in "${IMAGES[@]}"; do
  read -r TAG ID <<<"$image"
  SUITE="${TAG%%-*}"
  VARIANT="${TAG#*-}"
  [ "$VARIANT" = "$TAG" ] && VARIANT="standard"

  for target in "${TARGETS[@]}"; do
    read -r ABI DARCH <<<"$target"
    echo "=== $ID / $ABI ($DARCH, tag $TAG) ==="

    entry="$(python3 "$ROOT/scripts/official_images.py" "$WORK/library-debian" "$TAG" "$DARCH")"
    IFS='|' read -r COMMIT DIR <<<"$entry"
    echo "  reviewed commit: $COMMIT  directory: $DIR"
    base="$ART_REPO/$COMMIT/$DIR"

    # The library file's Directory may already end in "/oci" (OCI image layout), so probe several layouts.
    found=""
    for cand in "blobs/rootfs.tar.gz" "oci/blobs/rootfs.tar.gz" "rootfs.tar.xz" "oci/rootfs.tar.gz"; do
      if curl -fsIL "$base/$cand" -o /dev/null 2>/dev/null; then found="$cand"; break; fi
    done
    # Fallback: walk the OCI layout (index.json -> manifest -> first layer blob).
    if [ -z "$found" ]; then
      for r in "" "oci/"; do
        if curl -fsSL "$base/${r}index.json" -o "$WORK/index.json" 2>/dev/null; then
          MHEX="$(python3 "$ROOT/scripts/oci_layer.py" index "$WORK/index.json")"
          curl -fsSL "$base/${r}blobs/sha256/$MHEX" -o "$WORK/manifest.json"
          LHEX="$(python3 "$ROOT/scripts/oci_layer.py" manifest "$WORK/manifest.json")"
          found="${r}blobs/sha256/$LHEX"
          EXPECT_LAYER_SHA="$LHEX"
          break
        fi
      done
    fi
    if [ -z "$found" ]; then
      echo "ERROR: no rootfs artifact found under $base" >&2
      echo "  tried blobs/rootfs.tar.gz, oci/blobs/rootfs.tar.gz, rootfs.tar.xz, oci/rootfs.tar.gz and an OCI index.json walk" >&2
      echo "  HTTP status of $base/index.json: $(curl -s -o /dev/null -w '%{http_code}' "$base/index.json" || true)" >&2
      exit 1
    fi
    URL="$base/$found"
    echo "  artifact: $URL"

    FILE="$WORK/$ID-$ABI.archive"
    curl -fsSL "$URL" -o "$FILE"
    SHA="$(sha256sum "$FILE" | awk '{print $1}')"
    if [ -n "${EXPECT_LAYER_SHA:-}" ] && [ "$SHA" != "$EXPECT_LAYER_SHA" ]; then
      echo "ERROR: downloaded blob SHA-256 $SHA does not match the OCI manifest digest $EXPECT_LAYER_SHA" >&2; exit 1
    fi
    EXPECT_LAYER_SHA=""
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
      echo "signature=pinned via official-images (no detached signature)"
    } > "$OUT/$ABI/$ID.info"
    rm -f "$FILE"
  done
done

echo
echo "Debian manifests ready:"
find "$OUT" -name 'debian*.info' -exec ls -l {} \;
