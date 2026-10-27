package dev.asl.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.asl.app.terminal.DistroInfo
import dev.asl.app.terminal.InputModifiers
import dev.asl.app.terminal.KeyEncoder
import dev.asl.app.terminal.SessionManager
import dev.asl.app.terminal.TerminalPrefs
import dev.asl.app.terminal.TerminalSession
import dev.asl.app.terminal.TerminalTheme
import dev.asl.app.terminal.TerminalThemes
import dev.asl.app.terminal.TerminalView

@Composable
fun TerminalScreen(
    installed: List<DistroInfo>,
    onOpen: (DistroInfo) -> Unit,
    onGoToDistros: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { TerminalPrefs(context) }
    val sessions by SessionManager.sessions.collectAsStateWithLifecycle()
    val selectedId by SessionManager.selectedId.collectAsStateWithLifecycle()
    val current = sessions.firstOrNull { it.id == selectedId }
    val modifiers = remember { InputModifiers() }
    var viewRef by remember { mutableStateOf<TerminalView?>(null) }
    var fontSp by remember { mutableFloatStateOf(prefs.fontSizeSp) }
    val theme = remember { TerminalThemes.byId(prefs.themeId) }
    val cursorStyle = remember { prefs.cursorStyle }

    fun zoom(delta: Float) {
        fontSp = (fontSp + delta).coerceIn(8f, 34f)
        prefs.fontSizeSp = fontSp
        viewRef?.textSizePx = fontSp * context.resources.displayMetrics.scaledDensity
    }

    fun paste() {
        val cm = context.getSystemService(ClipboardManager::class.java)
        val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (!text.isNullOrEmpty()) current?.paste(text)
    }

    Column(Modifier.fillMaxSize()) {
        SessionTabs(
            sessions = sessions,
            selectedId = selectedId,
            installed = installed,
            onSelect = SessionManager::select,
            onClose = { SessionManager.close(context, it) },
            onOpen = onOpen,
            onGoToDistros = onGoToDistros,
            hasCurrent = current != null,
            onPaste = ::paste,
            onCopyScreen = {
                val text = current?.emulator?.screenText().orEmpty()
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("terminal", text))
            },
            onZoomIn = { zoom(1f) },
            onZoomOut = { zoom(-1f) },
            onRestart = { current?.restart() },
        )
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(theme.bg))) {
            if (current == null) {
                EmptyState(installed, onOpen, onGoToDistros)
            } else {
                key(current.id) {
                    TerminalHost(
                        session = current,
                        modifiers = modifiers,
                        theme = theme,
                        cursorStyle = cursorStyle,
                        fontSp = fontSp,
                        onFontChanged = { sp ->
                            fontSp = sp
                            prefs.fontSizeSp = sp
                        },
                        onLongPress = ::paste,
                        onView = { viewRef = it },
                    )
                }
            }
        }
        if (current != null) ExtraKeys(current, modifiers) { viewRef }
    }
}

@Composable
private fun TerminalHost(
    session: TerminalSession,
    modifiers: InputModifiers,
    theme: TerminalTheme,
    cursorStyle: Int,
    fontSp: Float,
    onFontChanged: (Float) -> Unit,
    onLongPress: () -> Unit,
    onView: (TerminalView?) -> Unit,
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val density = ctx.resources.displayMetrics.scaledDensity
            TerminalView(ctx).also { v ->
                v.modifiers = modifiers
                v.theme = theme
                v.cursorStyleOverride = cursorStyle
                v.textSizePx = fontSp * density
                v.onTextSizeChanged = { px -> onFontChanged(px / density) }
                v.onLongPressListener = onLongPress
                v.session = session
                onView(v)
                v.post {
                    v.requestFocus()
                    ctx.getSystemService(InputMethodManager::class.java)?.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        },
        update = { v -> if (v.session !== session) v.session = session },
        onRelease = { v ->
            v.session = null
            onView(null)
        },
    )
}

@Composable
private fun EmptyState(installed: List<DistroInfo>, onOpen: (DistroInfo) -> Unit, onGoToDistros: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(TerminalIcon, contentDescription = null, tint = AslColors.TextDim, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(10.dp))
        Text("No terminal sessions", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            if (installed.isEmpty()) "Install a Linux distribution first." else "Open a shell in one of your distributions.",
            color = AslColors.TextDim,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(14.dp))
        if (installed.isEmpty()) {
            Button(onClick = onGoToDistros) { Text("Get a distro") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                installed.forEach { d ->
                    OutlinedButton(onClick = { onOpen(d) }) {
                        DistroBadge(d, 18.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(d.label)
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionTabs(
    sessions: List<TerminalSession>,
    selectedId: Int?,
    installed: List<DistroInfo>,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onOpen: (DistroInfo) -> Unit,
    onGoToDistros: () -> Unit,
    hasCurrent: Boolean,
    onPaste: () -> Unit,
    onCopyScreen: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onRestart: () -> Unit,
) {
    var newMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            sessions.forEach { s -> SessionTab(s, s.id == selectedId, { onSelect(s.id) }, { onClose(s.id) }) }
        }
        Box {
            IconButton(onClick = { newMenu = true }, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.Add, contentDescription = "New session", tint = AslColors.Accent)
            }
            DropdownMenu(expanded = newMenu, onDismissRequest = { newMenu = false }) {
                if (installed.isEmpty()) {
                    DropdownMenuItem(text = { Text("Install a distro...") }, onClick = {
                        newMenu = false
                        onGoToDistros()
                    })
                }
                installed.forEach { d ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                DistroBadge(d, 20.dp)
                                Spacer(Modifier.width(10.dp))
                                Text("New ${d.label} session")
                            }
                        },
                        onClick = {
                            newMenu = false
                            onOpen(d)
                        },
                    )
                }
            }
        }
        Box {
            IconButton(onClick = { moreMenu = true }, modifier = Modifier.size(34.dp), enabled = hasCurrent) {
                Icon(Icons.Default.MoreVert, contentDescription = "More", tint = AslColors.TextDim)
            }
            DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                DropdownMenuItem(text = { Text("Paste") }, onClick = { moreMenu = false; onPaste() })
                DropdownMenuItem(text = { Text("Copy screen text") }, onClick = { moreMenu = false; onCopyScreen() })
                DropdownMenuItem(text = { Text("Larger text") }, onClick = { onZoomIn() })
                DropdownMenuItem(text = { Text("Smaller text") }, onClick = { onZoomOut() })
                DropdownMenuItem(text = { Text("Restart shell") }, onClick = { moreMenu = false; onRestart() })
            }
        }
    }
}

@Composable
private fun SessionTab(session: TerminalSession, selected: Boolean, onSelect: () -> Unit, onClose: () -> Unit) {
    val state by session.state.collectAsStateWithLifecycle()
    val title by session.title.collectAsStateWithLifecycle()
    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) AslColors.SurfaceHigh else AslColors.Surface,
        border = BorderStroke(1.dp, if (selected) AslColors.Accent else AslColors.Border),
        modifier = Modifier.height(34.dp),
    ) {
        Row(Modifier.padding(start = 8.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            DistroBadge(session.distro, 18.dp)
            Spacer(Modifier.width(6.dp))
            Text(title, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 110.dp))
            val dot = when (state) {
                is TerminalSession.State.Running -> AslColors.Good
                is TerminalSession.State.Starting -> AslColors.Warn
                else -> AslColors.Bad
            }
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
            IconButton(onClick = onClose, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close session", tint = AslColors.TextDim, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun ExtraKeys(session: TerminalSession, mods: InputModifiers, view: () -> TerminalView?) {
    fun modCode() = 1 + (if (mods.alt) 2 else 0) + (if (mods.ctrl) 4 else 0)

    fun raw(s: String) {
        view()?.sendRaw(s)
        mods.clear()
    }

    fun cursor(letter: Char) = raw(KeyEncoder.cursorKey(letter, session.emulator.isAppCursorKeys, modCode()))

    Row(
        Modifier.fillMaxWidth().background(AslColors.Bg).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Key("ESC") { raw("\u001b") }
        Key("TAB") { raw("\t") }
        Key("CTRL", active = mods.ctrl) { mods.ctrl = !mods.ctrl }
        Key("ALT", active = mods.alt) { mods.alt = !mods.alt }
        Key("\u2190") { cursor('D') }
        Key("\u2193") { cursor('B') }
        Key("\u2191") { cursor('A') }
        Key("\u2192") { cursor('C') }
        Key("-") { view()?.typed("-") }
        Key("/") { view()?.typed("/") }
        Key("|") { view()?.typed("|") }
        Key("~") { view()?.typed("~") }
        Key("HOME") { cursor('H') }
        Key("END") { cursor('F') }
        Key("PGUP") { raw("\u001b[5~") }
        Key("PGDN") { raw("\u001b[6~") }
        Key("^C") { raw("\u0003") }
        Key("^D") { raw("\u0004") }
        Key("^Z") { raw("\u001a") }
    }
}

@Composable
private fun Key(label: String, active: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(7.dp),
        color = if (active) AslColors.Accent else AslColors.Surface,
        contentColor = if (active) AslColors.OnAccent else AslColors.Text,
        border = BorderStroke(1.dp, if (active) AslColors.Accent else AslColors.Border),
        modifier = Modifier.height(34.dp).widthIn(min = 40.dp),
    ) {
        Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 12.sp, color = Color.Unspecified)
        }
    }
}
