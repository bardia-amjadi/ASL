#!/usr/bin/env bash
# Fetches the PRoot runtime and a tiny test rootfs, verifies them, and lays them out for the APK build.
#
#   PRoot + its shared libraries <- Termux package repository (prebuilt Android binaries, SHA-256 from the repo index)
#   Alpine minirootfs            <- dl-cdn.alpinelinux.org (SHA-256 from Alpine's release metadata)
#
# Output (all git-ignored, regenerated every build):
#   app/src/main/jniLibs/<abi>/libproot.so, libproot-loader.so, [libproot-loader32.so], lib<dep>.so ...
#   app/src/main/assets/rootfs/<abi>/alpine-minirootfs.bin, rootfs.info, runtime.info
#
# Requires: bash curl dpkg-deb patchelf readelf python3 sha256sum xz gzip  (Linux/macOS/WSL; CI has them)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$ROOT/build-tmp/runtime"
JNI_DIR="$ROOT/app/src/main/jniLibs"
ASSET_DIR="$ROOT/app/src/main/assets/rootfs"
TERMUX_REPO="${TERMUX_REPO:-https://packages.termux.dev/apt/termux-main}"
ALPINE_BASE="${ALPINE_BASE:-https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases}"
TERMUX_LIB_DIR="data/data/com.termux/files/usr/lib"

for tool in curl dpkg-deb patchelf readelf python3 sha256sum xz gzip; do
  command -v "$tool" >/dev/null 2>&1 || { echo "ERROR: required tool '$tool' not found in PATH" >&2; exit 1; }
done

rm -rf "$WORK" "$JNI_DIR" "$ASSET_DIR"
mkdir -p "$WORK"

# "<android abi> <termux arch> <alpine arch>"
TARGETS=("arm64-v8a aarch64 aarch64" "armeabi-v7a arm armv7")

# Libraries provided by Android itself; everything else must be bundled.
SYSTEM_LIBS=" libc.so libdl.so libm.so liblog.so "
is_system_lib() { case "$SYSTEM_LIBS" in *" $1 "*) return 0 ;; *) return 1 ;; esac; }

needed_of() { readelf -d "$1" | sed -n 's/.*Shared library: \[\(.*\)\].*/\1/p'; }

fetch_index() { # $1 termux arch, $2 output file
  local base="$TERMUX_REPO/dists/stable/main/binary-$1"
  if curl -fsSL "$base/Packages" -o "$2" 2>/dev/null; then return 0; fi
  if curl -fsSL "$base/Packages.xz" -o "$2.xz" 2>/dev/null; then xz -dc "$2.xz" > "$2"; return 0; fi
  if curl -fsSL "$base/Packages.gz" -o "$2.gz" 2>/dev/null; then gzip -dc "$2.gz" > "$2"; return 0; fi
  echo "ERROR: could not fetch the Termux package index for '$1' from $base" >&2
  return 1
}

# Downloads + verifies + extracts one Termux package into $W/<pkg> (once). Uses globals W and A.
fetch_termux_pkg() { # $1 package name
  local pkg="$1" entry FILENAME SHA VERSION
  if [ -d "$W/$pkg" ]; then return 0; fi
  if ! entry="$(python3 "$ROOT/scripts/termux_index.py" "$W/Packages" "$pkg")"; then
    echo "ERROR: Termux package '$pkg' not found. A dependency name may not map to a package name 1:1; add a mapping." >&2
    return 1
  fi
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
}

# Bundles the library named by a NEEDED entry (e.g. libtalloc.so.2 -> $J/libtalloc.so), then its own deps.
bundle_lib() { # $1 soname; uses globals W J
  local soname="$1" base out real n
  base="${soname%%.so*}"        # libtalloc.so.2 -> libtalloc
  out="$J/$base.so"
  if [ -f "$out" ]; then return 0; fi
  fetch_termux_pkg "$base"
  real="$(readlink -f "$W/$base/$TERMUX_LIB_DIR/$soname")"
  if [ ! -f "$real" ]; then
    echo "ERROR: $soname not found inside Termux package '$base'" >&2
    return 1
  fi
  cp "$real" "$out"
  chmod 755 "$out"
  for n in $(needed_of "$out"); do
    if ! is_system_lib "$n"; then bundle_lib "$n"; fi
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

  # --- PRoot and everything it needs, from the Termux repository ---------------------------------
  fetch_index "$TARCH" "$W/Packages"
  : > "$A/runtime.info"
  fetch_termux_pkg proot
  echo "source=Termux package repository (https://github.com/termux/termux-packages); proot is GPL-2.0, libtalloc LGPL-3.0, other bundled libraries see THIRD_PARTY_NOTICES.md" >> "$A/runtime.info"

  PFX="$W/proot/data/data/com.termux/files/usr"
  [ -f "$PFX/bin/proot" ] || { echo "ERROR: $PFX/bin/proot not found; package layout changed" >&2; exit 1; }
  [ -f "$PFX/libexec/proot/loader" ] || { echo "ERROR: proot loader not found; package layout changed" >&2; exit 1; }

  cp "$PFX/bin/proot" "$J/libproot.so"
  cp "$PFX/libexec/proot/loader" "$J/libproot-loader.so"
  if [ -f "$PFX/libexec/proot/loader32" ]; then cp "$PFX/libexec/proot/loader32" "$J/libproot-loader32.so"; fi
  chmod 755 "$J"/*.so

  echo "  proot NEEDED: $(needed_of "$J/libproot.so" | tr '\n' ' ')"
  for n in $(needed_of "$J/libproot.so"); do
    if ! is_system_lib "$n"; then bundle_lib "$n"; fi
  done

  # Android only extracts files named lib*.so, so versioned names (libtalloc.so.2) become libtalloc.so,
  # and the Termux-specific RUNPATH is replaced by $ORIGIN (the directory the libraries are extracted to).
  for f in "$J"/*.so; do
    case "$(basename "$f")" in libproot-loader*.so) continue ;; esac
    for n in $(needed_of "$f"); do
      if is_system_lib "$n"; then continue; fi
      base="${n%%.so*}"
      if [ "$n" != "$base.so" ]; then patchelf --replace-needed "$n" "$base.so" "$f"; fi
    done
    patchelf --set-rpath '$ORIGIN' "$f"
  done

  # Final verification: every remaining dependency must be an Android system lib or shipped next to it.
  for f in "$J"/*.so; do
    case "$(basename "$f")" in libproot-loader*.so) continue ;; esac
    echo "  $(basename "$f") NEEDED (patched): $(needed_of "$f" | tr '\n' ' ')"
    for n in $(needed_of "$f"); do
      if is_system_lib "$n"; then continue; fi
      [ -f "$J/$n" ] || { echo "ERROR: $(basename "$f") needs '$n' which is neither a system library nor bundled" >&2; exit 1; }
    done
  done
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
  cp "$W/$AFILE" "$A/alpine-minirootfs.bin"
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
