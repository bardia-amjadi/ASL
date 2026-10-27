# ASL — Android Subsystem for Linux (Phase 4.5: desktop, more distros, better terminal)

Phase 1 answers exactly one question: **can an ordinary, unrooted Android 12+ app (targetSdk 34) run a real
Linux userland through PRoot and show real command output?** The app is deliberately minimal. It has no
terminal, no Debian and no polished UI yet.

> **Status — read this first**
> - Written without access to an Android SDK, a build server or a device. **It has never been compiled or run.**
> - "CI is green" will only prove that the project **compiles and packages**.
> - Whether Linux actually executes is proven **only** by the on-device tests below. Until you have run them
>   on a real device, treat runtime compatibility as **unverified** (ARM64 and ARM32 alike).

## Phase 4.5 additions (desktop, more distros, better terminal)

**Status: none of Phases 3, 4 or 4.5 has been run on a device yet.** This phase stacks a lot of untested Android code on top of them; please
test in order (Phase 3 terminal first). Verified in the sandbox where it was written: 33 JVM unit tests pass (terminal emulator 20, guest-path
safety 8, VNC client 5 incl. an independent openssl cross-check of the VNC password DES response), the guest shell scripts pass `sh -n` / `dash -n`, and the
PTY helper worked on a Linux host. Everything else (Kotlin, Compose, NDK build, PRoot behaviour, apt installs, VNC servers) is unverified.

### Desktop (the "requirements" for GNOME and friends)
Running a graphical desktop needs a display. ASL now contains its own **VNC viewer** (a small RFB client: password auth, raw/CopyRect/resize,
touch to mouse, key row) and a **desktop manager** that installs and starts, inside Debian/Ubuntu, an `Xtigervnc` server (127.0.0.1:5901 only,
password protected, no X-over-TCP) plus a desktop environment, using real `apt-get`:

| Desktop | Status in the app | Notes |
|---|---|---|
| Openbox | Usually works | tiny; best first test |
| Xfce | Recommended | the usual choice for PRoot desktops |
| LXQt, MATE | Usually works | |
| GNOME Flashback | Experimental | the realistic "GNOME": panel + Metacity, no GPU compositing needed |
| GNOME (full) | Experimental, likely to fail | GNOME Shell expects systemd-logind and GPU compositing, which PRoot does not provide; a confirmation explains this |

"Usually works" and "Recommended" reflect common reports for PRoot setups, not tests done here. Rendering is software only, so it will be slow.
Open the **Desktop** tab, pick a distro, install a desktop (hundreds of MB; try Openbox first), tap **Start desktop**, then **Open desktop**.
Gestures: tap = click, long-press = right click, drag = drag, two-finger drag = scroll. If something fails, the Desktop tab's log shows the guest's real output.

### More distros
Debian 13, **Debian 12**, **Ubuntu 24.04** (Ubuntu Base from the official Ubuntu image server) and Alpine. Each is pinned at build time (see the new
step "Resolve Ubuntu manifest"; the Debian step now resolves both Debian versions). Ubuntu's `SHA256SUMS` GPG signature is checked on a best-effort basis
and the result is shown on the distro card ("signature: ..."). Not added: Fedora, Arch, Kali, because I could not verify trustworthy download sources
and checksums for them; the code is data-driven, so adding one means a new resolve script and a catalog entry.

### Better terminal
Colour themes (Campbell, One Dark, Solarized Dark, Dracula), cursor style, text selection with copy (long-press, "Select text", drag), paste,
Ctrl+Shift+C / Ctrl+Shift+V, and basic mouse reporting (taps and wheel for programs such as tmux, htop, vim that request it).

### Settings
Theme, cursor, scrollback, desktop resolution, local VNC password (show/new), per-distro storage, capability list (now including the experimental desktop).

### Phase 4.5 test steps
1. Replace repo files with the zip contents, keep paths, run the workflow. Report the failing step's log if the build fails (new risk: the Ubuntu and second-Debian resolve steps).
2. Terminal: try `Settings > theme/cursor`, long-press > Select text, `htop` or `less` scrolling.
3. Distros: install Debian 12 and/or Ubuntu 24.04 and run Self-test.
4. Desktop: Openbox first, then Xfce. If the screen stays black or the VNC connection fails, copy the Desktop log and send it to me.

## Phase 4 additions (files, dev server, settings)

**Status: written on top of Phase 3, which itself has not been run on a device yet. Nothing in Phase 4 has been compiled or run on Android.**
Verified in the sandbox: the guest-path resolver (`GuestPaths.java`) passes 8 JVM tests (symlinks, absolute links re-rooted into the
distro, `..` and links cannot escape, loop detection); together with the 20 terminal tests the CI "Unit tests" step runs 28 tests.

- **Files tab:** browse Debian or Alpine (distro chips on top), see permissions/size/date/link targets, open small text files,
  create folders, delete (with confirmation), **Import** from Android and **Export** to Android through the system file picker
  (no storage permission needed). Linux files stay private to ASL; only files you pick are copied.
- **Dev server (Distros tab, Debian card):** "Install Python" runs real `apt-get update && apt-get install python3` in Debian;
  "Start server" runs `python3 -m http.server 8080 --bind 127.0.0.1` as a real process, shows its live output, only reports
  "Running" after the port really answers, and "Open in browser" opens `http://127.0.0.1:8080`. It is loopback-only (this phone, not your
  network). A running server keeps the foreground service alive.
- **Settings tab:** text size, scrollback (1000/3000/10000 for new sessions), the PRoot seccomp compatibility switch, storage per distro and free space,
  a capability list (Docker: unsupported, VM and desktop GUI: not implemented, background execution and port visibility: limited, with reasons),
  "Delete all Linux data" (confirmation), about info, and a link to the log/diagnostics screen.

### Phase 4 test steps
1. Build and install as before. Open **Distros**: Debian card now has a "Developer workflow" card.
2. Tap **Install Python** (needs network; a few minutes), then **Start server**, wait for "Running", tap **Open in browser**: you should see
   "Hello from Debian on Android". Tap **Stop server**.
3. **Files** tab: go to /root/www, open index.html, create a folder, import a file from Android, export it back, delete it.
4. **Settings**: check storage numbers, change text size, open Log, try "Delete all Linux data" last (it removes Debian).
5. Report anything that fails with the exact on-screen text.

Not done in Phase 4: a process list, an `apt`-level package manager UI, text selection in the terminal, ARM32 device testing.

## Phase 3 additions (terminal)

Phases 1 and 2 were validated by the author on one device (Samsung SM-A266B, Android 16, arm64). **Phase 3 has never been
run on a device.** What was verified in the sandbox where it was written: the terminal emulator core passes 20 JVM
unit tests, and the native PTY helper works on a Linux host (real tty, window size, input, exit code, Ctrl-C).
The Kotlin/Compose/Android parts and the Android NDK build were not compiled or run.

- **Real PTY:** `app/src/main/cpp/pty.c` (about 100 lines of C, built with the NDK) opens `/dev/ptmx`, forks and execs PRoot with
  the slave as controlling terminal. Terminal resize calls `TIOCSWINSZ`.
- **Terminal emulator:** `TerminalEmulator.java` is a pure-Java VT100/xterm core (UTF-8, wide chars, 16/256/truecolour,
  scroll regions, scrollback, alternate screen, DEC line drawing, bracketed paste, device reports).
- **Terminal view:** a custom Android `View` with a proper IME connection, hardware keys, pinch zoom, drag scrolling,
  long-press paste, and a key row (ESC, TAB, sticky CTRL/ALT, arrows, HOME/END/PGUP/PGDN, ^C ^D ^Z, `- / | ~`).
- **Tabs per distro:** each tab is a session in a distro (Debian or Alpine). The `+` button opens a new session in any installed
  distro. Sessions survive rotation and leaving the app; a restart prompt appears after a shell exits (press Enter).
- **Foreground service:** runs only while sessions exist, with a notification and a "Close all" action. Android may still stop
  background processes (battery limits, phantom-process killer on Android 12+); the app does not promise indefinite execution.
- **App shell:** three tabs (Terminal, Distros, Log), custom icons, launcher icon, dark Windows 11-inspired styling.

### Test steps (please report the results)
1. Replace repo files with the Phase 3 zip, keeping paths. Run the workflow. New CI steps: "Pick installed NDK and CMake" and, at the
   end, "Unit tests". If the APK build fails, send me the failing step's log; if only "Unit tests" fails the APK is still available.
2. Check the CI step "Show APK contents": it must list `libaslpty.so` for both ABIs.
3. Install (uninstall the old app first if Android refuses), open **Distros**, make sure Debian is installed, then tap **Open terminal**.
4. In the shell try: `uname -m`, `ls --color`, `echo $TERM`, `stty size`, `top` (q to quit), `nano` or `less` (install with
   `apt install nano`), Ctrl+C on `sleep 100`, Ctrl+D to exit then Enter to restart, rotate the phone and run `stty size` again,
   open a second tab with `+` (try Alpine), close tabs, leave the app and come back.
5. Report anything that looks wrong: garbled colours, keyboard problems (Backspace, Enter, Tab), wrong size after rotation, crashes.

Known limits: no text selection (use long-press to paste and menu "Copy screen text"), no mouse reporting, no reflow on resize,
combining characters are dropped, no font choice, scrollback fixed at 3000 lines, terminal settings are font size only.

## Phase 2 additions (Debian install)

Phase 1 was validated by the author on one device (Samsung SM-A266B, Android 16, arm64): PRoot ran from
`nativeLibraryDir` and an Alpine guest answered `uname -m`. Phase 2 is **unverified until you run it**.

New in the app: **Install Debian** (resumable download, SHA-256 check, hardened extraction, PRoot configuration,
smoke test), **Cancel** (keeps the partial download), **Remove** (asks for confirmation), **Debian test** and
**apt update** buttons that show the real command output. Debian trixie *slim* is downloaded from the pinned URL in
`assets/distro/<abi>/debian.info`; see `docs/ADR-0002-debian-source.md` for the trust model and assumptions.

### Upgrade and test steps
1. Upload/replace the files from the Phase 2 zip, keeping paths. New files: `scripts/resolve-debian.sh`,
   `scripts/official_images.py`, `docs/ADR-0002-debian-source.md`, `TarExtractor.kt`, `DebianInstaller.kt`.
2. Run the workflow. The new step **"Resolve Debian manifest"** can fail if my assumptions about the upstream
   layout are wrong; if so, send me that step's log.
3. If installing the new APK over the old one is refused (debug builds from different CI runs have different signing
   keys), uninstall the old app first.
4. Needs roughly 700 MB free storage and an internet connection. Tap **Install Debian** and wait. Expected end state:
   status "Installed" and a `[PASS] Install Debian` entry that includes `dpkg arch : arm64`.
5. Tap **Debian test** (real commands) and **apt update** (needs working network/DNS and apt under PRoot).
   A `[FAIL]` here is a real finding, not a display bug. **Copy report** and send it to me.
6. Also test: **Cancel** during download, then **Install Debian** again (should resume), airplane mode during
   download (should fail cleanly and resume later), and **Remove** (should free the space).

Unverified so far: all of the above, ARM32 Debian (`armhf`), and apt behaviour under PRoot.

## What the app does

Press **Run all tests**. It runs three experiments and prints the raw results (PASS / FAIL / INFO):

| # | Experiment | What it proves |
|---|---|---|
| A | exec `libproot.so --version` from `nativeLibraryDir` | Whether Android lets the app execute a bundled native executable |
| B | exec a *copy* of it from app data (control) | Documents the Android 10+ restriction; **expected to be blocked** (INFO, not a failure) |
| C | install a bundled Alpine rootfs, then run `uname -m`, `id`, `pwd`, `cat /etc/alpine-release` through PRoot | Whether real Linux commands execute and return genuine output |

It also prints device/ABI/targetSdk info and the ELF class/machine of every shipped binary.

## Repository layout

```
.github/workflows/build.yml        CI: fetch runtime -> ./gradlew assembleDebug -> upload APK artifact
scripts/fetch-runtime-assets.sh    downloads + verifies PRoot (Termux repo) and Alpine minirootfs
scripts/termux_index.py            parses the apt index
scripts/alpine_release.py          parses Alpine release metadata
docs/ADR-0001-runtime.md           architecture decision record and blockers (runtime)
docs/ADR-0002-debian-source.md     Debian source, trust model, installation steps
docs/ADR-0005-desktop.md           desktop/VNC architecture and limits
scripts/resolve-ubuntu.sh          pins the Ubuntu Base rootfs (best-effort GPG check)
app/src/main/assets/guest/         shell scripts written into the distro (VNC install/start)
app/src/main/java/.../vnc/         RFB (VNC) client in pure Java
app/src/main/cpp/pty.c             native PTY helper (NDK)
app/src/main/java/.../terminal/    emulator, view, sessions, service
scripts/resolve-debian.sh          pins the Debian rootfs (reviewed commit + SHA-256) at build time
scripts/official_images.py         parses docker-library/official-images library/debian
THIRD_PARTY_NOTICES.md             licenses and source pointers
app/ ...                           Kotlin + Jetpack Compose app
settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/wrapper/gradle-wrapper.properties
```

Generated at build time and **not** stored in git: `app/src/main/jniLibs/`, `app/src/main/assets/rootfs/`, `build-tmp/`.

### About the Gradle wrapper
`gradlew`, `gradlew.bat` and `gradle-wrapper.jar` are not included because the JAR is a binary that could not be
produced here. The workflow generates them (`gradle wrapper`, Gradle 8.9) when the JAR is missing and then builds with
`./gradlew assembleDebug`. Each run also uploads them as the `gradle-wrapper-files` artifact; you can commit those four
files later to make the repository fully self-contained.

## 1. Create the repository and upload the files

1. On GitHub: **New repository**, empty (no README/.gitignore/license), default branch `main`.
2. Upload **every file in this project with the exact same relative path.** Easiest: unzip the zip locally, then
   use **Add file → Upload files** and drag the *contents* of the folder (including the `app`, `docs`, `scripts`,
   `gradle` folders). Hidden items are the common failure:
   - `.github/workflows/build.yml` — if your file manager hides dot-folders, use **Add file → Create new file**,
     type `.github/workflows/build.yml` as the name, and paste the contents.
   - Also make sure `.gitignore` and `.gitattributes` arrive.
3. Commit to `main`. The workflow starts automatically.

## 2. Build with GitHub Actions

1. Open the **Actions** tab → *Build debug APK* → the latest run. (You can also start it with **Run workflow**.)
2. Wait for the run to finish. If it fails, open the failing step: the runtime-asset step prints exactly which
   download, checksum or dependency check failed. **Send me that log excerpt and I will fix the script.**
3. On success, download the artifact **`asl-debug-apk`** from the run summary (it contains `app-debug.apk`).
   The step "Show APK contents" lists `lib/arm64-v8a/libproot.so` etc. so you can confirm packaging.

I cannot confirm that this workflow succeeds; only a real run can.

## 3. Install and test on a real device

Use a **physical ARM device** (Android 12+). x86 emulators are unsupported (only ARM ABIs are shipped).

```bash
# unzip the artifact first; USB debugging enabled on the phone
adb install -r app-debug.apk
adb shell am start -n dev.asl.app/.MainActivity
```
(Or copy the APK to the phone and open it; allow "install unknown apps" for your file manager.)

Then in the app:

1. Tap **Run all tests**.
2. Expected on a working ARM64 device:
   - `[PASS] A. exec libproot.so …` with a PRoot version string.
   - `[INFO] B. control …` — normally "BLOCKED as expected (EACCES)". Either outcome is acceptable.
   - `[PASS] Install test rootfs …` with a verified SHA-256.
   - `[PASS] C. real Linux commands …` showing `uname -m : aarch64`, an `id` line, and an Alpine release number.
3. Tap **Copy report** and keep the text.

### If something fails

| Symptom | Likely meaning | What to do |
|---|---|---|
| A: `error=2` / `libproot.so` MISSING | Native libs not extracted/packaged | Check the CI "APK contents" step; send me the report |
| A: `error=13 Permission denied` | Exec from `nativeLibraryDir` denied on this device | Core assumption failed here; send me the report (device, Android version). Fallback options are in the ADR |
| A: `CANNOT LINK EXECUTABLE … libtalloc` | Linker namespace didn't honor RUNPATH/LD_LIBRARY_PATH | Send the report; the fix is a code change |
| C: stderr mentions `ptrace`, `seccomp`, `SIGSYS`, or the process dies | Device/ROM seccomp policy | Toggle **Disable PRoot seccomp acceleration** and rerun C |
| C: works, then random `Killed` later | Android phantom-process killer | Not Phase 1; discussed for the foreground-service phase |

### ARM32 (`armeabi-v7a`)
The APK contains both ABIs, but a 64-bit phone always uses the arm64 libraries. To test ARM32 you need a
device that is genuinely 32-bit (`adb shell getprop ro.product.cpu.abilist` shows only `armeabi-v7a`/`armeabi`),
or force it on a 64-bit device only with an `armeabi-v7a`-only APK build. Until such a test passes, **ARM32 is unverified.**
The report's "installed as ABI" line tells you which set was actually used.

## Building locally (optional)

Requires JDK 17, Android SDK (API 34), and Linux/macOS/WSL for the asset script:

```bash
sudo apt-get install -y patchelf binutils xz-utils curl python3
bash scripts/fetch-runtime-assets.sh
gradle wrapper --gradle-version 8.9     # once, if you have no wrapper yet
./gradlew assembleDebug                  # APK: app/build/outputs/apk/debug/app-debug.apk
```

## Security notes for Phase 1

- Permissions: `INTERNET` (Debian download) and `ACCESS_NETWORK_STATE` (to read the device DNS servers). The Alpine test rootfs ships inside the APK; Debian is downloaded.
- If Android does not expose its DNS servers, Debian falls back to public resolvers 1.1.1.1 and 8.8.8.8 (a privacy choice you may want to change).
- Build-time downloads are SHA-256 verified against the source's own metadata fetched over HTTPS. The Termux
  repository's GPG signature is **not** verified yet (documented in the ADR); GitHub Actions are pinned to major
  versions, not commit SHAs, because I could not look up reviewed SHAs offline.
- Archive extraction rejects absolute paths and `..`, refuses to write through symlinks that leave the install
  root, and never follows symlinks when deleting.

## Licenses
See `THIRD_PARTY_NOTICES.md`. The bundled PRoot binary is GPL-2.0-or-later; ASL's own license is intentionally not chosen yet.
