package dev.asl.app.desktop

import android.content.Context
import dev.asl.app.devserver.DevServer
import dev.asl.app.runtime.ProcessRunner
import dev.asl.app.runtime.Proot
import dev.asl.app.runtime.RuntimePaths
import dev.asl.app.runtime.TarExtractor
import dev.asl.app.terminal.DistroInfo
import dev.asl.app.terminal.SessionManager
import dev.asl.app.vnc.RfbClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Installs a desktop environment plus a VNC server inside an apt-based distro, runs it as a real process
 * (Xtigervnc on loopback, password protected) and connects the in-app VNC viewer to it.
 *
 * Nothing here is simulated: if the desktop does not start, the guest's own output is shown in [log].
 */
object DesktopManager {
    const val VNC_PORT = 5901

    sealed interface State {
        data object Idle : State
        data class Installing(val label: String) : State
        data object Starting : State
        data class Running(val distroId: String, val envId: String) : State
        data class Failed(val message: String) : State
    }

    val state = MutableStateFlow<State>(State.Idle)
    val log = MutableStateFlow<List<String>>(emptyList())
    val viewerStatus = MutableStateFlow("Not connected")

    private val lock = Any()
    @Volatile private var process: Process? = null
    @Volatile private var installProcess: Process? = null
    @Volatile private var stoppedByUser = false

    @Volatile var client: RfbClient? = null
        private set

    /** Set by the viewer widget; receives frames from the active connection. */
    @Volatile var viewerListener: RfbClient.Listener? = null

    private fun append(line: String) = log.update { (it + line).takeLast(300) }

    fun isInstalled(root: File, env: DesktopEnv): Boolean =
        File(root, "usr/bin/Xtigervnc").exists() && File(root, env.marker).exists()

    // ---- settings ---------------------------------------------------------------------------------

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("desktop", Context.MODE_PRIVATE)

    /** Local-only VNC password (VNC passwords are at most 8 characters). Stored in app-private preferences. */
    fun password(context: Context): String {
        val p = prefs(context)
        p.getString("vnc_pw", null)?.let { return it }
        return regeneratePassword(context)
    }

    fun regeneratePassword(context: Context): String {
        val alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val rnd = SecureRandom()
        val pw = (1..8).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
        prefs(context).edit().putString("vnc_pw", pw).apply()
        return pw
    }

    fun geometry(context: Context): String = prefs(context).getString("geometry", "1280x720") ?: "1280x720"

    fun setGeometry(context: Context, value: String) {
        prefs(context).edit().putString("geometry", value).apply()
    }

    // ---- install ----------------------------------------------------------------------------------

    private fun writeScripts(context: Context, root: File) {
        for (name in listOf("asl-desktop-install.sh", "asl-desktop-start.sh", "asl-vnc-passwd.sh")) {
            val text = context.assets.open("guest/$name").bufferedReader().use { it.readText() }
            TarExtractor.safeWrite(root, "root/.asl/$name", text)
        }
    }

    /** Blocking: apt-get installs the VNC server and the desktop. Call from a background thread. */
    fun install(context: Context, paths: RuntimePaths, distro: DistroInfo, env: DesktopEnv, disableSeccomp: Boolean) {
        if (state.value is State.Installing || process != null) return
        state.value = State.Installing(env.label)
        log.value = listOf("Installing ${env.label} in ${distro.label} (about ${env.approxMb} MB to download). This can take a long time...")
        paths.tmp.mkdirs()
        var ok = false
        val rc = try {
            writeScripts(context, distro.root)
            val cmd = Proot.guestShell(paths, "sh /root/.asl/asl-desktop-install.sh ${env.packages.joinToString(" ")}", distro.root)
            ProcessRunner.runStreaming(cmd, Proot.env(paths, disableSeccomp), paths.filesDir, onProcess = { installProcess = it }) { line ->
                append(line)
                if (line.contains("ASL-INSTALL-OK")) ok = true
            }
        } catch (e: IOException) {
            append("error: ${e.message}")
            -1
        }
        installProcess = null
        state.value = if (rc == 0 && ok && isInstalled(distro.root, env)) {
            State.Idle
        } else {
            State.Failed("Installation did not finish (exit code $rc). Read the log for the exact apt/dpkg error; failed or cancelled installs can be retried.")
        }
    }

    fun cancelInstall() {
        installProcess?.destroyForcibly()
    }

    // ---- run --------------------------------------------------------------------------------------

    /** Starts Xtigervnc + the desktop session. Returns immediately; state becomes Running once the VNC port answers. */
    fun start(context: Context, paths: RuntimePaths, distro: DistroInfo, env: DesktopEnv, disableSeccomp: Boolean) {
        synchronized(lock) {
            if (process != null || state.value is State.Starting) return
            if (!isInstalled(distro.root, env)) {
                state.value = State.Failed("${env.label} is not installed in ${distro.label}.")
                return
            }
            stoppedByUser = false
            state.value = State.Starting
            log.value = listOf("Starting ${env.label} in ${distro.label}...")
        }
        val app = context.applicationContext
        thread(name = "desktop-start", isDaemon = true) {
            paths.tmp.mkdirs()
            try {
                writeScripts(app, distro.root)
                val rc = ProcessRunner.runStreaming(
                    Proot.guestShell(paths, "sh /root/.asl/asl-vnc-passwd.sh", distro.root),
                    Proot.env(paths, disableSeccomp), paths.filesDir, stdin = password(app) + "\n",
                ) { append(it) }
                if (rc != 0) {
                    state.value = State.Failed("Could not set the VNC password (exit code $rc). Is tigervnc-tools installed?")
                    return@thread
                }
                val session = env.sessionCommand.joinToString(" ")
                val geometry = geometry(app)
                val command = Proot.guestShell(
                    paths,
                    "sh /root/.asl/asl-desktop-start.sh $geometry '${env.xdgDesktop}' $session",
                    distro.root,
                )
                thread(name = "desktop-probe", isDaemon = true) {
                    repeat(90) {
                        if (process == null && it > 2) return@thread
                        if (DevServer.isReachable(VNC_PORT)) {
                            state.value = State.Running(distro.id, env.id)
                            SessionManager.backgroundWork(app, "desktop", true)
                            return@thread
                        }
                        Thread.sleep(1000)
                    }
                    if (state.value is State.Starting) state.value = State.Failed("The VNC server did not answer on port $VNC_PORT within 90 s. See the log.")
                }
                val exit = ProcessRunner.runStreaming(command, Proot.env(paths, disableSeccomp), paths.filesDir, onProcess = { process = it }) { append(it) }
                process = null
                disconnectViewer()
                SessionManager.backgroundWork(app, "desktop", false)
                append("[desktop process exited with code $exit]")
                if (state.value !is State.Failed) state.value = if (stoppedByUser) State.Idle else State.Failed("The desktop process ended (exit code $exit). See the log.")
            } catch (e: IOException) {
                state.value = State.Failed("Could not start the desktop: ${e.message}")
            }
        }
    }

    fun stop() {
        stoppedByUser = true
        disconnectViewer()
        process?.destroyForcibly()
    }

    // ---- viewer connection ----------------------------------------------------------------------------

    private val forwarding = object : RfbClient.Listener {
        override fun onFrame() { viewerListener?.onFrame() }
        override fun onResize(width: Int, height: Int) { viewerListener?.onResize(width, height) }
        override fun onBell() { viewerListener?.onBell() }
        override fun onClosed(reason: String) {
            client = null
            viewerStatus.value = "Disconnected: $reason"
            viewerListener?.onClosed(reason)
        }
    }

    fun connectViewer(context: Context) {
        if (client != null) return
        val app = context.applicationContext
        viewerStatus.value = "Connecting..."
        thread(name = "desktop-connect", isDaemon = true) {
            try {
                val c = RfbClient.connect("127.0.0.1", VNC_PORT, password(app), 5000, forwarding)
                client = c
                viewerStatus.value = "Connected: ${c.desktopName()}"
                viewerListener?.onResize(c.width(), c.height())
                viewerListener?.onFrame()
            } catch (e: IOException) {
                viewerStatus.value = "Connection failed: ${e.message}"
            }
        }
    }

    fun disconnectViewer() {
        val c = client ?: return
        client = null
        c.close()
        viewerStatus.value = "Not connected"
    }
}
