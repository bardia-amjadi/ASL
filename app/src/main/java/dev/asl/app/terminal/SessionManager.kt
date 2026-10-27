package dev.asl.app.terminal

import android.content.Context
import android.content.Intent
import dev.asl.app.runtime.RuntimePaths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Process-wide owner of terminal sessions, so they outlive the Activity. */
object SessionManager {
    const val MAX_SESSIONS = 8

    private val _sessions = MutableStateFlow<List<TerminalSession>>(emptyList())
    val sessions: StateFlow<List<TerminalSession>> = _sessions.asStateFlow()

    private val _selectedId = MutableStateFlow<Int?>(null)
    val selectedId: StateFlow<Int?> = _selectedId.asStateFlow()

    private val background = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Names of non-terminal background work that keeps the foreground service alive ("devserver", "desktop"). */
    val backgroundNames: Set<String> get() = background.toSet()

    private var nextId = 1
    private var lastOpenAt = 0L
    private val lock = Any()

    /** Returns null when the limit is reached or the tap was a duplicate within 600 ms. */
    fun open(context: Context, distro: DistroInfo, disableSeccomp: Boolean): TerminalSession? {
        val app = context.applicationContext
        val session: TerminalSession
        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (now - lastOpenAt < 600) return null
            if (_sessions.value.size >= MAX_SESSIONS) return null
            lastOpenAt = now
            session = TerminalSession(nextId++, distro, RuntimePaths(app), disableSeccomp, TerminalPrefs(app).scrollbackLines)
            _sessions.update { it + session }
            _selectedId.value = session.id
        }
        syncService(app)
        return session
    }

    fun select(id: Int) {
        if (_sessions.value.any { it.id == id }) _selectedId.value = id
    }

    fun close(context: Context, id: Int) {
        synchronized(lock) {
            val list = _sessions.value
            val index = list.indexOfFirst { it.id == id }
            if (index < 0) return
            list[index].terminate()
            val remaining = list.filterNot { it.id == id }
            _sessions.value = remaining
            if (_selectedId.value == id) _selectedId.value = remaining.getOrNull(minOf(index, remaining.size - 1))?.id
        }
        syncService(context.applicationContext)
    }

    fun closeAll(context: Context) {
        synchronized(lock) {
            _sessions.value.forEach { it.terminate() }
            _sessions.value = emptyList()
            _selectedId.value = null
        }
        syncService(context.applicationContext)
    }

    fun backgroundWork(context: Context, name: String, running: Boolean) {
        if (running) background.add(name) else background.remove(name)
        syncService(context.applicationContext)
    }

    /** Foreground service runs exactly while a session or the dev server exists. */
    private fun syncService(app: Context) {
        val intent = Intent(app, TerminalService::class.java)
        try {
            if (_sessions.value.isEmpty() && background.isEmpty()) app.stopService(intent) else app.startForegroundService(intent)
        } catch (_: RuntimeException) {
            // Starting a foreground service from the background can be refused by Android; sessions still work.
        }
    }
}
