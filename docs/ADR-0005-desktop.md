# ADR-0005: Desktop environments over VNC (Phase 4.5)

Status: **proposed; unverified on a device.**

## Why VNC
A graphical desktop needs an X server and a way to see it. Android apps cannot host a native X server easily, so ASL runs `Xtigervnc` inside
the distro and shows its framebuffer with an in-app VNC client. This is the approach commonly used for desktops over PRoot.

## Decisions
1. **In-app VNC client (pure Java, JVM-tested).** Supports "None" and VNC-password authentication, 32-bit true colour, Raw, CopyRect and DesktopSize.
   It advertises only those encodings, so servers never send anything else. Cost: bandwidth, acceptable on loopback.
2. **VNC password even on loopback.** Any app on the phone can open 127.0.0.1 sockets, so the server requires a random 8-character password kept in app-private
   preferences. The server also uses `-localhost` and `-nolisten tcp`.
3. **Guest-side scripts are real files** (`assets/guest/*.sh`, syntax-checked) written to `/root/.asl/` in the distro, not shell code embedded in Kotlin strings.
4. **Install and run are real processes** whose output is streamed to the UI. Installs set `policy-rc.d` (no init system under PRoot), use `DEBIAN_FRONTEND=noninteractive`.
5. **GNOME honesty.** GNOME Shell needs systemd-logind and GPU compositing; under PRoot it is likely to fail. The catalog offers GNOME Flashback (Metacity, no GPU needed)
   as the realistic option and asks for confirmation before installing full GNOME. Statuses in the catalog (Recommended / Usually works / Experimental) are judgments from common
   PRoot practice, not results of tests in this project.

## Known limits
Software rendering only (slow); no sound; no clipboard sync; no GPU; desktops are heavy (hundreds of MB, GNOME about 1.5 GB); PRoot may break individual apps;
one desktop at a time (display :1, port 5901); the dev server and desktop keep the app alive via the foreground service but Android may still stop them.
