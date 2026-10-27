package dev.asl.app.ui

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.asl.app.PocViewModel
import dev.asl.app.desktop.DesktopCatalog
import dev.asl.app.desktop.DesktopEnv
import dev.asl.app.desktop.DesktopManager
import dev.asl.app.desktop.Maturity
import dev.asl.app.desktop.VncView
import dev.asl.app.runtime.RuntimePaths
import dev.asl.app.terminal.DistroInfo
import dev.asl.app.terminal.Distros
import dev.asl.app.terminal.InputModifiers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun DesktopScreen(vm: PocViewModel, onGoToDistros: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val paths = remember { RuntimePaths(context) }
    val distros = remember(state.installedIds) { Distros.all(paths).filter { it.installed && it.isApt } }
    val desktopState by DesktopManager.state.collectAsStateWithLifecycle()
    var viewing by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(desktopState) {
        if (desktopState !is DesktopManager.State.Running) viewing = false
    }
    if (viewing && desktopState is DesktopManager.State.Running) {
        DesktopViewer(onBack = { viewing = false })
        return
    }

    if (distros.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("No desktop-capable distro yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Desktops need Debian or Ubuntu (apt). Install one first.",
                color = AslColors.TextDim, style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(14.dp))
            Button(onClick = onGoToDistros) { Text("Get a distro") }
        }
        return
    }

    val scope = rememberCoroutineScope()
    var distroId by rememberSaveable { mutableStateOf(distros.first().id) }
    val distro = distros.firstOrNull { it.id == distroId } ?: distros.first()
    var confirmGnome by remember { mutableStateOf<DesktopEnv?>(null) }
    val log by DesktopManager.log.collectAsStateWithLifecycle()
    val busyDesktop = desktopState is DesktopManager.State.Installing || desktopState is DesktopManager.State.Starting
    val running = desktopState as? DesktopManager.State.Running

    confirmGnome?.let { env ->
        AlertDialog(
            onDismissRequest = { confirmGnome = null },
            title = { Text("Install ${env.label}?") },
            text = { Text("${env.note}\n\nIt needs roughly ${env.approxMb} MB of downloads and more storage when unpacked. Continue?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmGnome = null
                    scope.launch(Dispatchers.IO) { DesktopManager.install(context, paths, distro, env, state.disableSeccomp) }
                }) { Text("Install anyway") }
            },
            dismissButton = { TextButton(onClick = { confirmGnome = null }) { Text("Cancel") } },
        )
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Desktop", style = MaterialTheme.typography.titleMedium)
        Text(
            "Runs a graphical desktop inside a distro and shows it here over VNC. The VNC server listens on this phone only (127.0.0.1) " +
                "and is password protected. Software rendering only: expect it to be slow.",
            style = MaterialTheme.typography.bodySmall, color = AslColors.TextDim,
        )

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            distros.forEach { d ->
                val selected = d.id == distro.id
                Surface(
                    onClick = { distroId = d.id },
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected) AslColors.SurfaceHigh else AslColors.Surface,
                    border = BorderStroke(1.dp, if (selected) AslColors.Accent else AslColors.Border),
                    modifier = Modifier.height(34.dp),
                ) {
                    Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        DistroBadge(d, 18.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(d.label, fontSize = 12.sp)
                    }
                }
            }
        }

        // Status / controls
        Surface(shape = RoundedCornerShape(12.dp), color = AslColors.Surface, border = BorderStroke(1.dp, AslColors.Border), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val (text, color) = when (val s = desktopState) {
                    is DesktopManager.State.Idle -> "Desktop stopped." to AslColors.TextDim
                    is DesktopManager.State.Installing -> "Installing ${s.label}..." to AslColors.Warn
                    is DesktopManager.State.Starting -> "Starting the desktop..." to AslColors.Warn
                    is DesktopManager.State.Running -> "Running (${DesktopCatalog.byId(s.envId)?.label ?: s.envId} in ${s.distroId})" to AslColors.Good
                    is DesktopManager.State.Failed -> s.message to AslColors.Bad
                }
                Text(text, style = MaterialTheme.typography.bodySmall, color = color)
                if (busyDesktop) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (running != null) {
                        Button(onClick = {
                            DesktopManager.connectViewer(context)
                            viewing = true
                        }, modifier = Modifier.weight(1f)) { Text("Open desktop") }
                        OutlinedButton(onClick = { DesktopManager.stop() }) { Text("Stop") }
                    }
                    if (desktopState is DesktopManager.State.Installing) {
                        OutlinedButton(onClick = { DesktopManager.cancelInstall() }) { Text("Cancel install") }
                    }
                }
            }
        }

        DesktopCatalog.all.forEach { env ->
            val installed = DesktopManager.isInstalled(distro.root, env) || (running?.envId == env.id && running.distroId == distro.id)
            EnvCard(
                env = env,
                installed = installed,
                canAct = !busyDesktop && running == null && !state.busy,
                onInstall = {
                    if (env.maturity == Maturity.EXPERIMENTAL) confirmGnome = env
                    else scope.launch(Dispatchers.IO) { DesktopManager.install(context, paths, distro, env, state.disableSeccomp) }
                },
                onStart = { DesktopManager.start(context, paths, distro, env, state.disableSeccomp) },
            )
        }

        if (log.isNotEmpty()) {
            Text("Log", style = MaterialTheme.typography.titleSmall)
            Column(
                Modifier.fillMaxWidth().heightIn(max = 220.dp).background(AslColors.TerminalBg, RoundedCornerShape(8.dp))
                    .verticalScroll(rememberScrollState()).padding(8.dp),
            ) {
                Text(log.takeLast(80).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp)
            }
        }
    }
}

@Composable
private fun EnvCard(env: DesktopEnv, installed: Boolean, canAct: Boolean, onInstall: () -> Unit, onStart: () -> Unit) {
    val tag = when (env.maturity) {
        Maturity.RECOMMENDED -> AslColors.Good
        Maturity.USUALLY_WORKS -> AslColors.Accent
        Maturity.EXPERIMENTAL -> AslColors.Warn
    }
    Surface(shape = RoundedCornerShape(12.dp), color = AslColors.Surface, border = BorderStroke(1.dp, AslColors.Border), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(env.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(env.maturity.label, fontSize = 11.sp, color = tag)
            }
            Text(env.note, style = MaterialTheme.typography.bodySmall, color = AslColors.TextDim)
            Text("About ${env.approxMb} MB download \u00b7 ${if (installed) "installed" else "not installed"}", fontSize = 11.sp, color = AslColors.TextDim)
            if (installed) {
                Button(onClick = onStart, enabled = canAct) { Text("Start desktop") }
            } else {
                OutlinedButton(onClick = onInstall, enabled = canAct) { Text("Install") }
            }
        }
    }
}

@Composable
private fun DesktopViewer(onBack: () -> Unit) {
    val context = LocalContext.current
    val status by DesktopManager.viewerStatus.collectAsStateWithLifecycle()
    val mods = remember { InputModifiers() }
    var viewRef by remember { mutableStateOf<VncView?>(null) }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            Modifier.fillMaxWidth().background(AslColors.Bg).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("\u2190 Back") }
            Text(status, fontSize = 11.sp, color = AslColors.TextDim, modifier = Modifier.weight(1f))
            TextButton(onClick = { viewRef?.showKeyboard() }) { Text("Keyboard") }
            TextButton(onClick = {
                DesktopManager.disconnectViewer()
                DesktopManager.connectViewer(context)
            }) { Text("Reconnect") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> VncView(ctx).also { it.modifiers = mods; viewRef = it } },
                onRelease = { viewRef = null },
            )
        }
        Row(
            Modifier.fillMaxWidth().background(AslColors.Bg).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ViewerKey("ESC") { viewRef?.tap(0xFF1B) }
            ViewerKey("TAB") { viewRef?.tap(0xFF09) }
            ViewerKey("CTRL", mods.ctrl) { mods.ctrl = !mods.ctrl }
            ViewerKey("ALT", mods.alt) { mods.alt = !mods.alt }
            ViewerKey("SUPER") { viewRef?.tap(0xFFEB) }
            ViewerKey("\u2190") { viewRef?.tap(0xFF51) }
            ViewerKey("\u2193") { viewRef?.tap(0xFF54) }
            ViewerKey("\u2191") { viewRef?.tap(0xFF52) }
            ViewerKey("\u2192") { viewRef?.tap(0xFF53) }
            ViewerKey("DEL") { viewRef?.tap(0xFFFF) }
            ViewerKey("F11") { viewRef?.tap(0xFFC8) }
        }
    }
}

@Composable
private fun ViewerKey(label: String, active: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(7.dp),
        color = if (active) AslColors.Accent else AslColors.Surface,
        contentColor = if (active) AslColors.OnAccent else AslColors.Text,
        border = BorderStroke(1.dp, if (active) AslColors.Accent else AslColors.Border),
        modifier = Modifier.height(34.dp),
    ) {
        Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) { Text(label, fontSize = 12.sp, color = Color.Unspecified) }
    }
}
