# ADR-0003: Terminal architecture (Phase 3)

Status: **proposed; the Android parts are unverified until run on a device.**

## Decisions

1. **Own small PTY helper in C instead of a third-party terminal library.** Considered: Termux's terminal-emulator/terminal-view
   libraries (JitPack coordinates and build reliability could not be verified offline) and `script`-over-pipes (no real
   window-size control). A ~100-line native helper using `/dev/ptmx` is simple, testable on Linux, and needs only the NDK.
   Cost: we need an NDK + CMake in CI (the workflow passes the runner's installed versions to Gradle).
2. **Emulator core in pure Java** so it can be unit-tested on the JVM without Android. 20 tests cover wrapping, cursor
   control, SGR (incl. truecolour), scrollback, UTF-8 split across reads, wide chars, alternate screen, scroll regions,
   insert/delete, line drawing, resize, device reports and garbage input.
3. **Rendering in an Android `View`, not Compose,** because a correct soft-keyboard `InputConnection` is only practical on a View.
   The rest of the UI is Compose.
4. **Sessions live in a process-wide `SessionManager`,** not in the Activity, so rotation/recreation does not kill shells.
   The activity also handles configuration changes itself, so rotation only resizes the PTY.
5. **Foreground service (`specialUse`) while sessions exist.** No guarantee of indefinite execution; documented in the UI/README.
   `POST_NOTIFICATIONS` is requested once; the app works if it is denied.

## Known limits

No mouse reporting, no selection, no reflow, combining characters dropped, fixed 3000-line scrollback, Ctrl+C is delivered through the PTY
line discipline to PRoot's process group (works in the host test; behaviour through PRoot is unverified), and PRoot's `/proc`
and `/dev/pts` handling may break some programs.
