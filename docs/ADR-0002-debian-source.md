# ADR-0002: Debian rootfs source, trust model and installation (Phase 2)

Status: **proposed, unverified on any device until the Phase 2 tests in the README pass.**

## Decision

Install **Debian 13 "trixie", slim variant**, from the reproducible rootfs tarballs built by
`debuerreotype` and published in https://github.com/debuerreotype/docker-debian-artifacts
(branches `dist-arm64v8` and `dist-arm32v7`). These are the same artifacts behind the Docker Official
Images for Debian. Not a custom or third-party remix.

## Trust model (what is and is not verified)

1. `docker-library/official-images` file `library/debian` names the reviewed git **commit** per architecture.
2. `scripts/resolve-debian.sh` (CI, build time) pins that commit, downloads the artifact at that
   immutable commit over HTTPS and records its SHA-256 in `assets/distro/<abi>/debian.info`.
3. The app downloads the **same pinned URL** and refuses to install unless the SHA-256 matches.
   A corrupted download, truncated transfer, or a compromised mirror/CDN is detected on the device.

Not covered: the build step itself trusts GitHub over HTTPS at build time (trust on first use). The tarball is
not independently checked against a Debian-signed hash; Debian signs *repositories* (via apt, inside the
installed system), not these tarballs. Pinned hashes are therefore only as trustworthy as the official-images
review process. Upstream additionally publishes a diff_id for the OCI layer; checking it is a possible hardening.

## Assumptions that CI/devices must confirm (I could not check them offline)

- `library/debian` has the block layout the parser expects (the script fails loudly if not).
- The artifact lives at `<Directory>/oci/blobs/rootfs.tar.gz` (current layout, as seen referenced by third-party
  projects) or `<Directory>/rootfs.tar.xz` (older layout). The script probes both and fails if neither exists.
- The server honours HTTP `Range` for resume; if it answers 200 the app restarts the download from zero.

## Installation steps (each failure is reported, never faked)

storage check, resumable download, SHA-256 verify, hardened extraction into a staging directory (no absolute
paths, `..`, or writes through escaping symlinks), PRoot-specific configuration, atomic rename, **smoke test
through PRoot (`dpkg --print-architecture`, `/etc/debian_version`, `id`, `uname -m`)**, and only then the
"installed" marker. If the smoke test fails the install is rolled back and the verified download is kept.

PRoot-specific configuration written into the guest:
- `/etc/resolv.conf` from the device's active-network DNS servers (fallback 1.1.1.1 / 8.8.8.8 if Android hides them).
- `/etc/hosts` if missing.
- `/etc/apt/apt.conf.d/99asl-proot` with `APT::Sandbox::User "root"`, because PRoot cannot switch to the `_apt` user.
  (A known requirement for apt under PRoot; unverified on this runtime until `apt update` is tested.)

## Known limits

- Background execution: a download interrupted by Android keeps its partial file and resumes on retry. There is
  no foreground service yet (Phase 3).
- Extraction needs roughly the compressed size + 500 MB free; xz archives need extra memory for the decoder.
- `apt install` of packages that need real kernel features, systemd, or privileged operations will fail; errors are
  shown as-is.
