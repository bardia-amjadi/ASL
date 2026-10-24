#!/usr/bin/env bash
# Fetches the PRoot runtime and a tiny test rootfs, verifies them, and lays them out for the APK build.
#
#   PRoot + libtalloc  <- Termux package repository (prebuilt Android binaries, SHA-256 from the repo index)
#   Alpine minirootfs  <- dl-cdn.alpinelinux.org (SHA-256 from Alpine's release metadata)
#
# Output (all git-ignored, regenerated every build):
#   app/src/main/jniLibs/<abi>/libproot.so, libproot-loader.so, [libproot-loader32.so], libtalloc.so
#   app/src/main/assets/rootfs/<abi>/alpine-minirootfs.tar.gz, rootfs.info, runtime.info
#
# Requires: bash curl dpkg-deb patchelf readelf python3 sha256sum xz gzip  (Linux/macOS/WSL; CI installs them)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$ROOT/build-tmp/runtime"
JNI_DIR="$ROOT/app/src/main/jniLibs"
ASSET_DIR="$ROOT/app/src/main/assets/rootfs"
TERMUX_REPO="${TERMUX_REPO:-https://packages.termux.dev/apt/termux-main}"
ALPINE_BASE="${ALPINE_BASE:-https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases}"

for tool in curl dpkg-deb patchelf readelf python3 sha256sum xz gzip; do
  command -v "$tool" >/dev/null 2>&1 || { echo "ERROR: required tool '$tool' not found in PATH" >&2; exit 1; }
done

rm -rf "$WORK" "$JNI_DIR" "$ASSET_DIR"
mkdir -p "$WORK"

# "<android abi> <termux arch> <alpine arch>"
TARGETS=("arm64-v8a aarch64 aarch64" "armeabi-v7a arm armv7")

fetch_index() { # $1 termux arch, $2 output file
  local base="$TERMUX_REPO/dists/stable/main/binary-$1"
  if curl -fsSL "$base/Packages" -o "$2" 2>/dev/null; then return 0; fi
  if curl -fsSL "$base/Packages.xz" -o "$2.xz" 2>/dev/null; then xz -dc "$2.xz" > "$2"; return 0; fi
  if curl -fsSL "$base/Packages.gz" -o "$2.gz" 2>/dev/null; then gzip -dc "$2.gz" > "$2"; return 0; fi
  echo "ERROR: could not fetch the Termux package index for '$1' from $base" >&2
  return 1
}

check_needed() { # $1 elf file, $2 label : fail on unexpected shared-library dependencies
  local needed n
  needed="$(readelf -d "$1" | sed -n 's/.*Shared library: \[\(.*\)\].*/\1/p')"
  echo "  $2 NEEDED: $(echo "$needed" | tr '\n' ' ')"
  for n in $needed; do
    case "$n" in
      libc.so|libdl.so|libm.so|liblog.so|libtalloc.so|libtalloc.so.2) ;;
      *) echo "ERROR: $2 depends on '$n', which is not an Android system library. It must be bundled too (see docs/ADR-0001)." >&2; exit 1 ;;
    esac
  done
}

check_machine() { # $1 file, $2 expected substring from readelf -h "Machine:"
  local machine
  machine="$(readelf -h "$1" | sed -n 's/^ *Machine: *//p')"
  echo "  $(basename "$1"): Machine: $machine"
  case "$machine" in *"$2"*) ;; *) echo "ERROR: $1 has unexpected machine '$machine' (wanted '$2')" >&2; exit 1 ;; esac
}

for target in "${TARGETS[@]}"; do
  read -r ABI TARCH AARCH <<<"$target"
  echo "=== $ABI (termux: $TARCH, alpine: $AARCH) ==="
  W="$WORK/$ABI"; J="$JNI_DIR/$ABI"; A="$ASSET_DIR/$ABI"
  mkdir -p "$W" "$J" "$A"
  case "$ABI" in arm64-v8a) WANT_MACHINE="AArch64" ;; *) WANT_MACHINE="ARM" ;; esac

  # --- PRoot + libtalloc from the Termux repository ---------------------------------------------
  fetch_index "$TARCH" "$W/Packages"
  : > "$A/runtime.info"
  for pkg in proot libtalloc; do
    entry="$(python3 "$ROOT/scripts/termux_index.py" "$W/Packages" "$pkg")"
    IFS='|' read -r FILENAME SHA VERSION <<<"$entry"
    echo "  $pkg $VERSION ($FILENAME)"
    curl -fsSL "$TERMUX_REPO/$FILENAME" -o "$W/$pkg.deb"
    echo "$SHA  $W/$pkg.deb" | sha256sum -c -
    dpkg-deb -x "$W/$pkg.deb" "$W/$pkg"
    {
      echo "$pkg.version=$VERSION"
      echo "$pkg.deb=$TERMUX_REPO/$FILENAME"
      echo "$pkg.deb.sha256=$SHA"
    } >> "$A/runtime.info"
  done
  echo "source=Termux package repository (https://github.com/termux/termux-packages); binaries are GPL-2.0 (proot) / LGPL-3.0 (talloc), see THIRD_PARTY_NOTICES.md" >> "$A/runtime.info"

  PFX="$W/proot/data/data/com.termux/files/usr"
  TPFX="$W/libtalloc/data/data/com.termux/files/usr"
  [ -f "$PFX/bin/proot" ] || { echo "ERROR: $PFX/bin/proot not found; package layout changed" >&2; exit 1; }
  [ -f "$PFX/libexec/proot/loader" ] || { echo "ERROR: proot loader not found; package layout changed" >&2; exit 1; }
  TALLOC_REAL="$(readlink -f "$TPFX/lib/libtalloc.so.2")"
  [ -f "$TALLOC_REAL" ] || { echo "ERROR: libtalloc.so.2 not found; package layout changed" >&2; exit 1; }

  cp "$PFX/bin/proot" "$J/libproot.so"
  cp "$PFX/libexec/proot/loader" "$J/libproot-loader.so"
  if [ -f "$PFX/libexec/proot/loader32" ]; then cp "$PFX/libexec/proot/loader32" "$J/libproot-loader32.so"; fi
  cp "$TALLOC_REAL" "$J/libtalloc.so"
  chmod 755 "$J"/*.so

  check_needed "$J/libproot.so" "proot"
  check_needed "$J/libtalloc.so" "talloc"

  # proot links against libtalloc.so.2 and carries a Termux-specific RUNPATH. Android only extracts
  # files named lib*.so, so point it at the sibling libtalloc.so via $ORIGIN instead.
  patchelf --replace-needed libtalloc.so.2 libtalloc.so --set-rpath '$ORIGIN' "$J/libproot.so"
  check_machine "$J/libproot.so" "$WANT_MACHINE"
  check_machine "$J/libproot-loader.so" "$WANT_MACHINE"

  # --- Alpine minirootfs (tiny PoC guest) ---------------------------------------------------------
  curl -fsSL "$ALPINE_BASE/$AARCH/latest-releases.yaml" -o "$W/latest-releases.yaml"
  entry="$(python3 "$ROOT/scripts/alpine_release.py" "$W/latest-releases.yaml")"
  IFS='|' read -r AFILE ASHA AVER <<<"$entry"
  if [ -z "$ASHA" ]; then
    curl -fsSL "$ALPINE_BASE/$AARCH/$AFILE.sha256" -o "$W/$AFILE.sha256"
    ASHA="$(awk '{print $1}' "$W/$AFILE.sha256")"
  fi
  [ -n "$ASHA" ] || { echo "ERROR: no SHA-256 available for $AFILE" >&2; exit 1; }
  echo "  alpine $AVER ($AFILE)"
  curl -fsSL "$ALPINE_BASE/$AARCH/$AFILE" -o "$W/$AFILE"
  echo "$ASHA  $W/$AFILE" | sha256sum -c -
  cp "$W/$AFILE" "$A/alpine-minirootfs.tar.gz"
  {
    echo "source_url=$ALPINE_BASE/$AARCH/$AFILE"
    echo "file=$AFILE"
    echo "version=$AVER"
    echo "sha256=$ASHA"
    echo "abi=$ABI"
  } > "$A/rootfs.info"
done

echo
echo "Runtime assets ready:"
find "$JNI_DIR" "$ASSET_DIR" -type f -exec ls -l {} \;
