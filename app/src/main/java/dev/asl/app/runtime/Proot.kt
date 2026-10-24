package dev.asl.app.runtime

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

    /** `proot ... /bin/sh -c <script>` inside the Alpine test rootfs, with a clean guest environment. */
    fun guestShell(paths: RuntimePaths, script: String): List<String> = listOf(
        paths.proot.path,
        "--kill-on-exit",
        "--link2symlink",
        "-0",
        "-r", paths.rootfs.path,
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
}
