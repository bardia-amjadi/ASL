# ADR-0001: Unrooted Linux execution approach (Phase 1)

Status: **proposed, experimentally unverified.** It becomes "accepted" only after the on-device
experiments in the README pass on at least one real arm64 device.

## Problem

Run real Linux userland (starting with a shell) from an ordinary, unrooted Android 12+ app that targets a
modern SDK, without simulating anything.

## Blockers investigated

1. **Executing files from app data is denied (Android 10+, targetSdk >= 29).** Apps can no longer `exec()` files
   they wrote into their own data directory (SELinux `execute_no_trans` on `app_data_file`).
   *Consequence:* downloading or extracting a `proot` binary at runtime does not work.
2. **Files in `nativeLibraryDir` remain executable.** The package manager extracts `lib/<abi>/lib*.so` there
   (`apk_data_file`). Android does not verify that these files are real JNI libraries.
   *Decision:* ship `proot`, its loader and `libtalloc` renamed as `lib*.so`, with `useLegacyPackaging = true`
   so they are extracted to disk rather than mmapped from the APK.
3. **Targeting an old SDK (<= 28) to avoid (1)** is the alternative used by Termux. Rejected as the default:
   Google Play requires a recent target SDK, and it sidesteps rather than solves the restriction (Android 14+ already refuses to
   install apps targeting below API 23, so the escape hatch is shrinking). Kept as a documented fallback only if experiment A fails on real devices.
4. **PRoot's dynamic dependency.** The Termux `proot` binary needs `libtalloc.so.2` via a Termux-specific RUNPATH.
   The build rewrites NEEDED to `libtalloc.so` and RUNPATH to `$ORIGIN` (patchelf); the app also sets
   `LD_LIBRARY_PATH`. The build fails if any other non-system dependency appears.
5. **PRoot's loader.** PRoot injects a tiny static loader into the traced process. It is also shipped as
   `lib*.so` and selected via `PROOT_LOADER` / `PROOT_LOADER_32`.
6. **ELF/ABI compatibility.** The app reads the ELF header of every shipped binary at runtime and reports class
   and machine. The ABI is taken from the directory Android installed us into (`lib/arm64` vs `lib/arm`).
7. **Symlinks / hard links in the rootfs.** App storage cannot create hard links; PRoot runs with
   `--link2symlink`. The extractor materialises tar hard links as copies, rejects absolute paths and `..`,
   refuses to write through symlinks leaving the install root, and deletes trees without following symlinks.

## Candidates compared

| Approach | Needs root | Works on arm32 | Verdict |
|---|---|---|---|
| PRoot (ptrace) from `nativeLibraryDir` | No | Yes (Termux builds `arm`) | **Chosen for the PoC** |
| chroot / unshare / namespaces | Yes (or special kernel config) | - | Not possible for a normal app |
| QEMU user/system emulation | No | Yes | Much slower; distribution/licensing burden; future optional backend |
| Android Virtualization Framework (pKVM) | No, but device/OEM specific | arm64 only | Future optional backend behind the runtime abstraction |
| Reuse Termux app/runtime as a dependency | No | Yes | Same-prefix/sharedUserId constraints and GPLv3; not suitable as an embedded dependency |

## Known limits (stated, not hidden)

- PRoot is syscall interception via ptrace: slower than native, no real namespaces/cgroups, so **Docker is not
  supported** and is not simulated.
- Android 12+ "phantom process killer" can kill PRoot's child processes on some devices.
- SELinux policy and seccomp behaviour differ per OEM/kernel; the PoC has a switch for `PROOT_NO_SECCOMP`.
- Termux binaries are taken from a third-party repository over HTTPS with SHA-256 from its index. The repository's
  GPG signature is **not** verified yet. Pin versions/hashes (or build from source) before Phase 2 ships.

## Unverified until tested on a device

- That experiment A (exec from `nativeLibraryDir`) succeeds on any given device.
- That PRoot's `RUNPATH $ORIGIN` / `LD_LIBRARY_PATH` resolution finds `libtalloc.so` under Android's linker namespaces.
- That the exact download URLs / metadata formats used by `scripts/fetch-runtime-assets.sh` are as assumed (CI will show).
- Everything about arm32 (`armeabi-v7a`).
