# ADR-0004: Files, dev server, settings (Phase 4)

Status: **proposed; unverified on a device.**

## Decisions
1. **Guest-path resolution in pure Java (`GuestPaths`).** The file manager never uses a guest path directly as a host path. Symlinks are resolved
   the way the guest would: absolute targets are re-rooted at the distro directory and `..` is clamped at it. A symlink inside a distro therefore
   cannot expose Android files. Covered by JVM unit tests.
2. **Android file sharing only through the system picker** (`OpenDocument` / `CreateDocument`): no storage permissions requested. Imports never
   overwrite; exports copy one regular file.
3. **Dev server as a plain child process, not a PTY session.** Output is captured line by line (last 200 lines kept). "Running" is only reported
   after a real TCP connection to 127.0.0.1:8080 succeeds. Default bind is 127.0.0.1, so nothing is exposed to the LAN; making it wider is a manual
   decision inside a terminal.
4. **Port visibility is limited by design.** Android 10+ blocks apps from reading `/proc/net/tcp`, so ASL does not pretend to list listening ports;
   it probes the known port instead. Shown in Settings > Capabilities.
5. **Docker, VM backend and desktop GUI are shown as unsupported / not implemented** in the capability list. No fake screens.

## Known limits
Only one dev-server preset; no process list; no text editor (viewer only, 256 KB limit); file operations run as the app user, so Linux
permission bits are informational (PRoot fakes root).
