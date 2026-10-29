package dev.asl.app.ui

import android.os.Build
import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.asl.app.PocViewModel
import dev.asl.app.devserver.DevServer
import dev.asl.app.files.GuestFs
import dev.asl.app.runtime.RuntimePaths
import dev.asl.app.terminal.SessionManager
import dev.asl.app.terminal.Distros
import dev.asl.app.terminal.TerminalPrefs
import dev.asl.app.terminal.TerminalThemes
import dev.asl.app.desktop.DesktopManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class Capability(val name: String, val status: String, val color: Color, val detail: String)

@Composable
fun SettingsScreen(vm: PocViewModel, onOpenLog: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val paths = remember { RuntimePaths(context) }
    val prefs = remember { TerminalPrefs(context) }
    var fontSp by remember { mutableFloatStateOf(prefs.fontSizeSp) }
    var scrollback by remember { mutableIntStateOf(prefs.scrollbackLines) }
    var confirmWipe by remember { mutableStateOf(false) }
    var themeId by remember { mutableStateOf(prefs.themeId) }
    var cursorStyle by remember { mutableIntStateOf(prefs.cursorStyle) }
    var geometry by remember { mutableStateOf(DesktopManager.geometry(context)) }
    var vncPassword by remember { mutableStateOf(DesktopManager.password(context)) }
    var showPassword by remember { mutableStateOf(false) }

    val storage by produceState<List<Pair<String, Long>>?>(null, state.installedIds, state.busy) {
        if (!state.busy) {
            value = withContext(Dispatchers.IO) {
                Distros.all(paths).map { it.label to GuestFs.treeSize(it.root) } +
                    ("Downloads" to GuestFs.treeSize(paths.downloadsDir))
            }
        }
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Delete all Linux data?") },
            text = { Text("This closes every terminal, stops the dev server and permanently deletes Debian, Alpine and all files stored in them, plus downloads. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmWipe = false
                    SessionManager.closeAll(context)
                    vm.removeAll()
                }) { Text("Delete everything") }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Keep") } },
        )
    }

    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    val capabilities = listOf(
        Capability(
            "PRoot runtime",
            if (paths.proot.exists()) "Files present" else "Missing",
            if (paths.proot.exists()) AslColors.Good else AslColors.Bad,
            "Use Log > Run all to verify it executes on this device.",
        ),
        Capability(
            "Distros installed",
            "${state.installedIds.size} of ${Distros.all(paths).size}",
            if (state.installedIds.isNotEmpty()) AslColors.Good else AslColors.TextDim,
            "",
        ),
        Capability(
            "Desktop (VNC)",
            "Experimental",
            AslColors.Warn,
            "Software-rendered desktops over a local VNC connection. Xfce, LXQt, MATE and Openbox are the usual choices; full GNOME is unlikely to start under PRoot.",
        ),
        Capability(
            "Background sessions",
            "Limited",
            AslColors.Warn,
            "A foreground service helps, but Android or the manufacturer may still stop sessions (battery limits, phantom-process killer).",
        ),
        Capability(
            "Port visibility",
            "Limited",
            AslColors.Warn,
            "Android hides /proc/net from apps; ASL checks the dev server by connecting to its port. Servers are loopback-only unless you bind them otherwise.",
        ),
        Capability("Docker", "Unsupported", AslColors.Bad, "PRoot provides no namespaces or cgroups, so a Docker daemon cannot run. Not simulated."),
        Capability("Virtual machine backend", "Not implemented", AslColors.TextDim, "A possible future backend."),
        Capability("Desktop GUI", "Not implemented", AslColors.TextDim, ""),
    )

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleMedium)

        Section("Terminal") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Text size", Modifier.weight(1f))
                OutlinedButton(onClick = { fontSp = (fontSp - 1f).coerceAtLeast(8f); prefs.fontSizeSp = fontSp }) { Text("\u2212") }
                Text("  ${fontSp.toInt()} sp  ")
                OutlinedButton(onClick = { fontSp = (fontSp + 1f).coerceAtMost(34f); prefs.fontSizeSp = fontSp }) { Text("+") }
            }
            Text("Scrollback (new sessions)", style = MaterialTheme.typography.bodySmall, color = AslColors.TextDim)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1000, 3000, 10000).forEach { n ->
                    FilterChip(
                        selected = scrollback == n,
                        onClick = { scrollback = n; prefs.scrollbackLines = n },
                        label = { Text("$n lines") },
                    )
                }
            }
            Text("Colour theme", style = MaterialTheme.typography.bodySmall, color = AslColors.TextDim)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TerminalThemes.all.forEach { t ->
                    FilterChip(
                        selected = themeId == t.id,
                        onClick = { themeId = t.id; prefs.themeId = t.id },
                        label = { Text(t.label) },
                        leadingIcon = { Box(Modifier.size(12.dp).clip(CircleShape).background(Color(t.bg))) },
                    )
                }
            }
            Text("Cursor", style = MaterialTheme.typography.bodySmall, color = AslColors.TextDim)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Auto", "Block", "Underline", "Bar").forEachIndexed { i, label ->
                    FilterChip(selected = cursorStyle == i, onClick = { cursorStyle = i; prefs.cursorStyle = i }, label = { Text(label) })
                }
            }
            Text("Tip: pinch to zoom, long-press for select / paste, Ctrl+Shift+C / V with a keyboard.", style = MaterialTheme.typography.bodySmall, color = AslColors.TextDim)
        }

        Section("Desktop") {
            Text("Desktop resolution. Lower = bigger buttons and text on a phone screen (applies the next time the desktop starts)", style = MaterialTheme.typography.bodySmall, color = AslColors.TextDim)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("800x450", "960x540", "1280x720", "1600x900").forEach { g ->
                    FilterChip(selected = geometry == g, onClick = { geometry = g; DesktopManager.setGeometry(context, g) }, label = { Text(g) })
                }
            }
            Text("VNC password (local only; the server listens on 127.0.0.1)", style = MaterialTheme.typography.bodySmall, color = AslColors.TextDim)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (showPassword) vncPassword else "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.weight(1f))
                TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show") }
                TextButton(onClick = { vncPassword = DesktopManager.regeneratePassword(context) }) { Text("New") }
            }
            Text("A new password takes effect the next time the desktop starts.", style = MaterialTheme.typography.bodySmall, color = AslColors.TextDim)
        }

        Section("Compatibility") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = state.disableSeccomp, onCheckedChange = vm::setDisableSeccomp)
                Text(
                    "  Disable PRoot seccomp acceleration (PROOT_NO_SECCOMP=1). Try this if programs crash with SIGSYS or ptrace errors. Applies to new sessions.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Section("Storage") {
            val items = storage
            if (items == null) {
                Text("Measuring...", color = AslColors.TextDim, style = MaterialTheme.typography.bodySmall)
            } else {
                items.forEach { (name, bytes) ->
                    Row {
                        Text(name, Modifier.weight(1f))
                        Text(Formatter.formatShortFileSize(context, bytes), color = AslColors.TextDim)
                    }
                }
            }
            Row {
                Text("Free on device", Modifier.weight(1f))
                Text(Formatter.formatShortFileSize(context, paths.filesDir.usableSpace), color = AslColors.TextDim)
            }
            OutlinedButton(onClick = { confirmWipe = true }, enabled = !state.busy) { Text("Delete all Linux data...") }
        }

        Section("Capabilities") {
            capabilities.forEach { c ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 5.dp).size(8.dp).clip(CircleShape).background(c.color))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Row {
                            Text(c.name, fontSize = 13.sp)
                            Text("  \u00b7  ${c.status}", fontSize = 13.sp, color = c.color)
                        }
                        if (c.detail.isNotEmpty()) Text(c.detail, fontSize = 11.sp, color = AslColors.TextDim)
                    }
                }
            }
        }

        Section("About") {
            Text("ASL $versionName (debug build)", fontSize = 13.sp)
            Text(
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) \u00b7 ABI ${paths.abi ?: "unsupported"}",
                fontSize = 11.sp, color = AslColors.TextDim,
            )
            Text(
                "Dev server: ${when (val s = DevServer.status.collectAsStateWithLifecycle().value) {
                    is DevServer.Status.Running -> "running on 127.0.0.1:${s.port}"
                    else -> "not running"
                }}",
                fontSize = 11.sp, color = AslColors.TextDim,
            )
            OutlinedButton(onClick = onOpenLog) { Text("Log and diagnostics") }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = AslColors.Surface,
        border = BorderStroke(1.dp, AslColors.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = AslColors.Accent)
            content()
        }
    }
}
