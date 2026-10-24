# Third-party notices (Phase 1)

This is an engineering inventory, not legal advice. Review before distributing the APK to anyone.

## Components bundled into the APK at build time

| Component | Where it comes from | License | Notes |
|---|---|---|---|
| PRoot (`libproot.so`, `libproot-loader*.so`) | Prebuilt Android binaries from the Termux package repository (`packages.termux.dev`), built from the Termux fork of https://github.com/proot-me/proot (packaging recipe: https://github.com/termux/termux-packages/tree/master/packages/proot) | GPL-2.0-or-later | Executed as a separate process. **Redistributing the APK means complying with the GPL for this component**, including offering the corresponding source. The exact package version and SHA-256 are written into `assets/rootfs/<abi>/runtime.info` at build time and shown in the app. |
| talloc (`libtalloc.so`) | Termux package `libtalloc` (https://github.com/termux/termux-packages/tree/master/packages/libtalloc), upstream https://talloc.samba.org | LGPL-3.0-or-later | Shipped as a separate shared library file (replaceable). |
| Alpine Linux minirootfs (test guest only) | https://dl-cdn.alpinelinux.org/alpine/ (checksum verified at build time) | Mixed per package (BusyBox GPL-2.0, musl MIT, others) | PoC guest only; Phase 2 replaces it with Debian. Source: https://git.alpinelinux.org and https://dl-cdn.alpinelinux.org/alpine/latest-stable/main/ |

## Libraries linked into the app

| Library | License |
|---|---|
| Kotlin standard library and kotlinx.coroutines | Apache-2.0 |
| AndroidX (Activity, Lifecycle) and Jetpack Compose (incl. Material 3) | Apache-2.0 |
| Apache Commons Compress (and its transitive Commons IO / Codec / Lang) | Apache-2.0 |

## Not yet decided

The license of ASL's own source code has not been chosen. Choose one deliberately before publishing,
taking the GPL-licensed PRoot binary that is distributed together with the APK into account.
