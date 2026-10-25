package dev.asl.app.runtime

import java.io.File

/** Builds PRoot command lines and the (minimal, explicit) process environment. */
object Proot {
    private const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /** Environment for the HOST-side proot process. The Android environment is deliberately NOT inherited. */
    fun env(paths: RuntimePaths, disableSeccomp: Boolean): Map<String, String> {
        val env = linkedMapOf(
            "PROOT_TMP_DIR" to paths.tmp.path,
            "PROOT_LOADER" to paths.loader.path,
            "LD_LIBRARY_PATH" to paths.nativeDir.path,
            "HOME" to paths.filesDir.path,
            "TMPDIR" to paths.tmp.path,
        )
        if (paths.loader32.exists()) env["PROOT_LOADER_32"] = paths.loader32.path
        if (disableSeccomp) env["PROOT_NO_SECCOMP"] = "1"
        return env
    }

    /** `proot ... /bin/sh -c <script>` inside [rootfs] (Alpine PoC by default), with a clean guest environment. */
    fun guestShell(paths: RuntimePaths, script: String, rootfs: File = paths.rootfs): List<String> = listOf(
        paths.proot.path,
        "--kill-on-exit",
        "--link2symlink",
        "-0",
        "-r", rootfs.path,
        "-b", "/dev",
        "-b", "/proc",
        "-b", "/sys",
        "-w", "/root",
        "/usr/bin/env", "-i",
        "HOME=/root",
        "PATH=$GUEST_PATH",
        "TERM=xterm-256color",
        "/bin/sh", "-c", script,
    )

    /**
     * Interactive login shell inside [rootfs] (used with a PTY). The guest gets a clean environment via
     * `env -i`; the host-side proot process gets [env].
     */
    fun interactive(paths: RuntimePaths, rootfs: File, shell: String): List<String> = listOf(
        paths.proot.path,
        "--kill-on-exit",
        "--link2symlink",
        "-0",
        "-r", rootfs.path,
        "-b", "/dev",
        "-b", "/proc",
        "-b", "/sys",
        "-w", "/root",
        "/usr/bin/env", "-i",
        "HOME=/root",
        "USER=root",
        "LOGNAME=root",
        "SHELL=$shell",
        "PATH=$GUEST_PATH",
        "TERM=xterm-256color",
        "COLORTERM=truecolor",
        "LANG=C.UTF-8",
        shell, "-l",
    )
}
