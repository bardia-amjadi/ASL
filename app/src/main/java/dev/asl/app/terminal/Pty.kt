package dev.asl.app.terminal

/** JNI bridge to app/src/main/cpp/pty.c. All calls are thin wrappers; errors are returned as -errno. */
object Pty {
    init {
        System.loadLibrary("aslpty")
    }

    /** Returns the PTY master fd (>= 0) and stores the child pid in [pidOut], or -errno. */
    @JvmStatic external fun spawn(
        cmd: String,
        cwd: String,
        argv: Array<String>,
        envp: Array<String>,
        rows: Int,
        cols: Int,
        pidOut: IntArray,
    ): Int

    @JvmStatic external fun setSize(fd: Int, rows: Int, cols: Int): Int

    /** Blocks until the child exits; returns its exit status or -signal. */
    @JvmStatic external fun waitFor(pid: Int): Int

    @JvmStatic external fun signal(pid: Int, sig: Int): Int
}
