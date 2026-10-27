#!/usr/bin/env bash
# Resolves the Ubuntu 24.04 "Ubuntu Base" root filesystem and pins its SHA-256.
#
#   Source:  https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/  (official Ubuntu CD image server)
#   Hashes:  SHA256SUMS in the same directory; SHA256SUMS.gpg is a detached signature.
#   The GPG check is BEST EFFORT: if the signing key cannot be fetched or the signature does not verify, the build
#   continues, but the pinned manifest says "signature=NOT VERIFIED" and the app shows that.
#
# Output (git-ignored): app/src/main/assets/distro/<abi>/ubuntu.info
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$ROOT/build-tmp/ubuntu"
OUT="$ROOT/app/src/main/assets/distro"
BASE="${UBUNTU_BASE_URL:-https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release}"
# Ubuntu CD Image Automatic Signing Keys (2012 and 2018), from memory: if one is wrong the check just reports "not verified".
KEYS=("843938DF228D22F7B3742BC0D94AA3F0EFE21092" "C5986B4F1257FFA86632CBA746181433FBB75451")

for tool in curl sha256sum awk sort grep stat; do
  command -v "$tool" >/dev/null 2>&1 || { echo "ERROR: required tool '$tool' not found in PATH" >&2; exit 1; }
done
rm -rf "$WORK"; mkdir -p "$WORK" "$OUT"
find "$OUT" -name 'ubuntu.info' -delete 2>/dev/null || true

curl -fsSL "$BASE/SHA256SUMS" -o "$WORK/SHA256SUMS"
SIGNATURE="NOT VERIFIED (gpg unavailable)"
if command -v gpg >/dev/null 2>&1 && curl -fsSL "$BASE/SHA256SUMS.gpg" -o "$WORK/SHA256SUMS.gpg" 2>/dev/null; then
  export GNUPGHOME="$WORK/gnupg"; mkdir -p "$GNUPGHOME"; chmod 700 "$GNUPGHOME"
  for k in "${KEYS[@]}"; do
    gpg --batch --keyserver hkps://keyserver.ubuntu.com --recv-keys "$k" >/dev/null 2>&1 || true
  done
  if gpg --batch --verify "$WORK/SHA256SUMS.gpg" "$WORK/SHA256SUMS" >"$WORK/gpg.log" 2>&1; then
    SIGNATURE="verified (GPG, Ubuntu CD image signing key)"
  else
    SIGNATURE="NOT VERIFIED (signature check failed or key unavailable)"
  fi
  echo "  gpg: $SIGNATURE"; sed 's/^/    /' "$WORK/gpg.log" || true
fi

# "<android abi> <ubuntu arch>"
for target in "arm64-v8a arm64" "armeabi-v7a armhf"; do
  read -r ABI UARCH <<<"$target"
  echo "=== ubuntu / $ABI ($UARCH) ==="
  line="$(grep -E " \*?ubuntu-base-24\.04\.[0-9]+-base-$UARCH\.tar\.gz$" "$WORK/SHA256SUMS" | sort -V -k2 | tail -n1 || true)"
  [ -n "$line" ] || { echo "ERROR: no ubuntu-base-24.04.x-base-$UARCH.tar.gz entry in SHA256SUMS" >&2; exit 1; }
  SHA="$(echo "$line" | awk '{print $1}')"
  FILE="$(echo "$line" | awk '{print $2}' | tr -d '*')"
  VERSION="$(echo "$FILE" | sed -E 's/^ubuntu-base-([0-9.]+)-base-.*/\1/')"
  echo "  $FILE sha256=$SHA"
  curl -fsSL "$BASE/$FILE" -o "$WORK/$FILE"
  echo "$SHA  $WORK/$FILE" | sha256sum -c -
  SIZE="$(stat -c %s "$WORK/$FILE")"
  mkdir -p "$OUT/$ABI"
  {
    echo "name=Ubuntu"
    echo "suite=noble"
    echo "variant=base"
    echo "version=$VERSION"
    echo "abi=$ABI"
    echo "url=$BASE/$FILE"
    echo "format=gz"
    echo "size=$SIZE"
    echo "sha256=$SHA"
    echo "source_repo=$BASE/"
    echo "source_commit=n/a"
    echo "pinned_by=SHA256SUMS from the same official directory"
    echo "signature=$SIGNATURE"
  } > "$OUT/$ABI/ubuntu.info"
  rm -f "$WORK/$FILE"
done
echo "Ubuntu manifests ready:"; find "$OUT" -name 'ubuntu.info' -exec ls -l {} \;
