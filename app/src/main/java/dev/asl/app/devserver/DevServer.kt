package dev.asl.app.devserver

import android.content.Context
import dev.asl.app.runtime.Proot
import dev.asl.app.runtime.ProcessRunner
import dev.asl.app.runtime.RuntimePaths
import dev.asl.app.runtime.TarExtractor
import dev.asl.app.terminal.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A genuine development workflow: install Python inside Debian, run `python3 -m http.server` as a real
 * process, show its real output, and check that its port answers.
 *
 * Networking facts: PRoot does not create a separate network namespace, so the server shares the phone's
 * loopback interface. It is bound to 127.0.0.1 only: reachable from this phone (e.g. the browser), NOT from
 * the local network.
 */
object DevServer {
    const val PORT = 8080

    sealed interface Status {
        data object Idle : Status
        data object Installing : Status
        data object Starting : Status
        data class Running(val port: Int) : Status
        data class Stopped(val exitCode: Int?) : Status
        data class Failed(val message: String) : Status
    }

    val status = MutableStateFlow<Status>(Status.Idle)
    val log = MutableStateFlow<List<String>>(emptyList())

    private val lock = Any()
    @Volatile private var process: Process? = null
    @Volatile private var stoppedByUser = false

    private const val PAGE = """<!doctype html>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ASL dev server</title>
<body style="font-family:sans-serif;max-width:32rem;margin:2rem auto;padding:0 1rem">
<h1>Hello from Debian on Android</h1>
<p>This page is served by <code>python3 -m http.server</code> running inside ASL (PRoot).</p>
<p>Edit it at <code>/root/www/index.html</code>.</p>
"""

    val running: Boolean get() = process != null

    fun isPythonInstalled(root: File): Boolean = File(root, "usr/bin/python3").exists()

    private fun append(line: String) = log.update { (it + line).takeLast(200) }

    /** Blocking (call from a background thread): `apt-get update && apt-get install python3` in Debian. */
    fun installPython(paths: RuntimePaths, root: File, disableSeccomp: Boolean) {
        if (status.value is Status.Installing || process != null) return
        status.value = Status.Installing
        log.value = listOf("Installing Python 3 (apt-get update, then apt-get install). This can take a few minutes...")
        paths.tmp.mkdirs()
        val script = """
            (apt-get update && apt-get install -y --no-install-recommends python3) > /tmp/asl-python-install.log 2>&1 && echo "RESULT: OK" || echo "RESULT: FAILED"
            tail -n 15 /tmp/asl-python-install.log
        """.trimIndent()
        val r = ProcessRunner.run(
            Proot.guestShell(paths, script, root), Proot.env(paths, disableSeccomp), paths.filesDir,
            timeoutSeconds = 900,
        )
        r.describe().lines().forEach { append(it) }
        val ok = r.succeeded && r.stdout.contains("RESULT: OK") && isPythonInstalled(root)
        status.value = if (ok) Status.Idle else Status.Failed("Python installation failed; see the log below.")
    }

    /** Starts the server (non-blocking). Status becomes Running only after the port really answers. */
    fun start(context: Context, paths: RuntimePaths, root: File, disableSeccomp: Boolean) {
        synchronized(lock) {
            if (process != null) return
            if (!isPythonInstalled(root)) {
                status.value = Status.Failed("Python 3 is not installed in this distro yet.")
                return
            }
            try {
                if (!File(root, "root/www/index.html").exists()) {
                    TarExtractor.safeWrite(root, "root/www/index.html", PAGE)
                }
                paths.tmp.mkdirs()
                val command = Proot.guestShell(
                    paths,
                    "cd /root/www && exec python3 -u -m http.server $PORT --bind 127.0.0.1",
                    root,
                )
                val builder = ProcessBuilder(command)
                builder.environment().apply {
                    clear()
                    putAll(Proot.env(paths, disableSeccomp))
                }
                builder.directory(paths.filesDir)
                builder.redirectErrorStream(true)
                log.value = listOf("$ python3 -m http.server $PORT --bind 127.0.0.1   (in /root/www)")
                stoppedByUser = false
                status.value = Status.Starting
                val p = builder.start()
                process = p
                val app = context.applicationContext
                thread(name = "devserver-out", isDaemon = true) {
                    try {
                        p.inputStream.bufferedReader().forEachLine { append(it) }
                    } catch (_: IOException) {
                    }
                }
                thread(name = "devserver-wait", isDaemon = true) {
                    val code = p.waitFor()
                    synchronized(lock) { if (process === p) process = null }
                    append("[process exited with code $code]")
                    status.value = Status.Stopped(code)
                    SessionManager.backgroundWork(app, "devserver", false)
                }
                thread(name = "devserver-probe", isDaemon = true) {
                    repeat(40) {
                        if (process !== p) return@thread
                        if (isReachable(PORT)) {
                            status.value = Status.Running(PORT)
                            SessionManager.backgroundWork(app, "devserver", true)
                            return@thread
                        }
                        Thread.sleep(500)
                    }
                    if (process === p) {
                        status.value = Status.Failed("The server did not answer on port $PORT within 20 s; see the log.")
                    }
                }
            } catch (e: IOException) {
                process = null
                status.value = Status.Failed("Could not start the server: ${e.message}")
            }
        }
    }

    fun stop() {
        val p = process ?: return
        stoppedByUser = true
        p.destroy()
        thread(isDaemon = true, name = "devserver-kill") {
            Thread.sleep(1500)
            if (p.isAlive) p.destroyForcibly()
        }
    }

    /** True if something accepts TCP connections on 127.0.0.1:[port]. */
    fun isReachable(port: Int): Boolean = try {
        Socket().use {
            it.connect(InetSocketAddress("127.0.0.1", port), 300)
            true
        }
    } catch (_: IOException) {
        false
    }
}
