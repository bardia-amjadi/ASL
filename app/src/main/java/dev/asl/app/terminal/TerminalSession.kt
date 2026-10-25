package dev.asl.app.terminal

import android.os.ParcelFileDescriptor
import dev.asl.app.runtime.Proot
import dev.asl.app.runtime.RuntimePaths
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * One interactive shell in one distro, connected to a real PTY. Survives Activity recreation because it
 * lives in [SessionManager], not in the UI.
 */
class TerminalSession(
    val id: Int,
    val distro: DistroInfo,
    private val paths: RuntimePaths,
    private val disableSeccomp: Boolean,
    scrollbackLines: Int = 3000,
) {
    sealed interface State {
        data object Starting : State
        data object Running : State
        data class Exited(val code: Int) : State
        data class Failed(val message: String) : State
    }

    val state = MutableStateFlow<State>(State.Starting)
    val title = MutableStateFlow(distro.label)

    /** Set by the view: called (from any thread) when the screen changed. */
    @Volatile
    var onUpdate: (() -> Unit)? = null

    val emulator = TerminalEmulator(
        80, 24, scrollbackLines,
        object : TerminalEmulator.Listener {
            override fun onWrite(data: ByteArray) = writeRaw(data)
            override fun onTitleChanged(title: String) {
                this@TerminalSession.title.value = title.ifBlank { distro.label }
            }

            override fun onBell() {}
            override fun onScreenChanged() {
                onUpdate?.invoke()
            }
        },
    )

    private val lock = Any()
    private var started = false
    private var pfd: ParcelFileDescriptor? = null
    private var output: FileOutputStream? = null
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "pty-writer-$id").apply { isDaemon = true } }

    @Volatile
    private var pid = -1

    @Volatile
    private var closing = false

    /** Called by the view whenever its character grid size is known/changes. The first call starts the shell. */
    fun attachSize(cols: Int, rows: Int) {
        synchronized(lock) {
            if (!started) {
                started = true
                startLocked(cols, rows)
                return
            }
        }
        resize(cols, rows)
    }

    fun resize(cols: Int, rows: Int) {
        emulator.resize(cols, rows)
        val fd = synchronized(lock) { pfd?.fd } ?: return
        Pty.setSize(fd, rows, cols)
    }

    private fun startLocked(cols: Int, rows: Int) {
        emulator.resize(cols, rows)
        state.value = State.Starting
        try {
            val argv = Proot.interactive(paths, distro.root, distro.shell)
            val env = Proot.env(paths, disableSeccomp).map { "${it.key}=${it.value}" }
            paths.tmp.mkdirs()
            val pidOut = IntArray(1)
            val fd = Pty.spawn(argv[0], paths.filesDir.path, argv.toTypedArray(), env.toTypedArray(), rows, cols, pidOut)
            if (fd < 0) {
                fail("Could not allocate a terminal (errno ${-fd}).")
                return
            }
            val childPid = pidOut[0]
            pid = childPid
            val desc = ParcelFileDescriptor.adoptFd(fd)
            pfd = desc
            output = FileOutputStream(desc.fileDescriptor)
            val input = FileInputStream(desc.fileDescriptor)
            state.value = State.Running
            thread(name = "pty-reader-$id", isDaemon = true) { readLoop(input, desc, childPid) }
        } catch (t: Throwable) {
            fail("Could not start the session: $t")
        }
    }

    private fun fail(message: String) {
        state.value = State.Failed(message)
        emulator.feed("\u001b[31m$message\u001b[0m\r\nPress Enter to try again.\r\n")
    }

    private fun readLoop(input: FileInputStream, desc: ParcelFileDescriptor, childPid: Int) {
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                emulator.feed(buf, 0, n)
            }
        } catch (_: IOException) {
            // EIO is how a PTY master reports that the child side closed
        }
        val code = Pty.waitFor(childPid)
        synchronized(lock) {
            output = null
            pfd = null
        }
        runCatching { desc.close() }
        pid = -1
        if (!closing) {
            emulator.feed("\r\n\u001b[2m[Process completed with exit code $code. Press Enter to restart.]\u001b[0m\r\n")
            state.value = State.Exited(code)
        }
    }

    /** User input. After the process has ended, Enter restarts the shell. */
    fun write(data: ByteArray) {
        val st = state.value
        if (st is State.Exited || st is State.Failed) {
            if (data.contains('\r'.code.toByte())) restart()
            return
        }
        writeRaw(data)
    }

    fun paste(text: String) {
        val body = text.replace("\r\n", "\n").replace('\n', '\r')
        val payload = if (emulator.isBracketedPaste) "\u001b[200~$body\u001b[201~" else body
        write(payload.toByteArray(Charsets.UTF_8))
    }

    private fun writeRaw(data: ByteArray) {
        val out = synchronized(lock) { output } ?: return
        try {
            writer.execute {
                try {
                    out.write(data)
                    out.flush()
                } catch (_: IOException) {
                    // process is gone; the reader reports it
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // session already closed
        }
    }

    fun restart() {
        synchronized(lock) {
            if (pid > 0 || closing) return
            emulator.feed("\u001b[2J\u001b[H")
            startLocked(emulator.cols, emulator.rows)
        }
    }

    /** Hangs up the shell (SIGHUP), then kills it if it is still alive shortly after. */
    fun terminate() {
        closing = true
        onUpdate = null
        val p = pid
        if (p > 0) {
            Pty.signal(p, 1)
            thread(isDaemon = true, name = "pty-kill-$id") {
                Thread.sleep(500)
                if (pid == p) Pty.signal(p, 9)
            }
        }
        writer.shutdown()
    }
}
