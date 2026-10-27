package dev.asl.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.asl.app.runtime.RuntimePaths
import dev.asl.app.terminal.DistroInfo
import dev.asl.app.terminal.Distros
import dev.asl.app.terminal.SessionManager
import dev.asl.app.ui.AppMark
import dev.asl.app.ui.AslColors
import dev.asl.app.ui.AslTheme
import dev.asl.app.ui.CubeIcon
import dev.asl.app.ui.DiagnosticsScreen
import dev.asl.app.ui.DesktopIcon
import dev.asl.app.ui.DesktopScreen
import dev.asl.app.ui.DistrosScreen
import dev.asl.app.ui.FilesScreen
import dev.asl.app.ui.FolderIcon
import dev.asl.app.ui.SettingsScreen
import dev.asl.app.ui.SlidersIcon
import dev.asl.app.ui.TerminalIcon
import dev.asl.app.ui.TerminalScreen

class MainActivity : ComponentActivity() {
    private val viewModel: PocViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AslTheme { AslApp(viewModel) }
        }
    }
}

private const val TAB_TERMINAL = 0
private const val TAB_DESKTOP = 1
private const val TAB_FILES = 2
private const val TAB_DISTROS = 3
private const val TAB_SETTINGS = 4

@Composable
private fun AslApp(vm: PocViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val paths = remember { RuntimePaths(context) }
    val distros = remember(state.installedIds) { Distros.all(paths) }
    val installed = distros.filter { it.installed }
    var tab by rememberSaveable { mutableIntStateOf(TAB_TERMINAL) }
    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    var logOpen by rememberSaveable { mutableStateOf(false) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun open(distro: DistroInfo) {
        if (Build.VERSION.SDK_INT >= 33 && !askedNotifications &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotifications = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (SessionManager.sessions.value.size >= SessionManager.MAX_SESSIONS) {
            Toast.makeText(context, "Limit reached: ${SessionManager.MAX_SESSIONS} sessions", Toast.LENGTH_SHORT).show()
        } else {
            SessionManager.open(context, distro, state.disableSeccomp)
        }
        tab = TAB_TERMINAL
    }

    Surface(modifier = Modifier.fillMaxSize(), color = AslColors.Bg) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TitleBar()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    TAB_TERMINAL -> TerminalScreen(
                        installed = installed,
                        onOpen = ::open,
                        onGoToDistros = { tab = TAB_DISTROS },
                    )
                    TAB_DESKTOP -> DesktopScreen(vm = vm, onGoToDistros = { tab = TAB_DISTROS })
                    TAB_FILES -> FilesScreen(installed = installed, onGoToDistros = { tab = TAB_DISTROS })
                    TAB_DISTROS -> DistrosScreen(
                        vm = vm,
                        onOpen = { id -> distros.firstOrNull { it.id == id }?.let(::open) },
                        onShowLog = {
                            logOpen = true
                            tab = TAB_SETTINGS
                        },
                    )
                    else -> if (logOpen) {
                        Column(Modifier.fillMaxSize()) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                                androidx.compose.material3.TextButton(onClick = { logOpen = false }) { Text("\u2190 Settings") }
                            }
                            Box(Modifier.weight(1f)) { DiagnosticsScreen(vm) }
                        }
                    } else {
                        SettingsScreen(vm = vm, onOpenLog = { logOpen = true })
                    }
                }
            }
            BottomBar(tab) {
                if (it == TAB_SETTINGS && tab == TAB_SETTINGS) logOpen = false
                tab = it
            }
        }
    }
}

@Composable
private fun TitleBar() {
    Row(
        Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppMark(24.dp)
        Spacer(Modifier.width(10.dp))
        Text("ASL", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Spacer(Modifier.width(8.dp))
        Text("Android Subsystem for Linux", fontSize = 11.sp, color = AslColors.TextDim)
    }
    HorizontalDivider(color = AslColors.Border)
}

@Composable
private fun BottomBar(selected: Int, onSelect: (Int) -> Unit) {
    HorizontalDivider(color = AslColors.Border)
    Row(Modifier.fillMaxWidth().background(AslColors.Surface).height(54.dp)) {
        NavItem(TerminalIcon, "Terminal", selected == TAB_TERMINAL) { onSelect(TAB_TERMINAL) }
        NavItem(DesktopIcon, "Desktop", selected == TAB_DESKTOP) { onSelect(TAB_DESKTOP) }
        NavItem(FolderIcon, "Files", selected == TAB_FILES) { onSelect(TAB_FILES) }
        NavItem(CubeIcon, "Distros", selected == TAB_DISTROS) { onSelect(TAB_DISTROS) }
        NavItem(SlidersIcon, "Settings", selected == TAB_SETTINGS) { onSelect(TAB_SETTINGS) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) AslColors.Accent else AslColors.TextDim
    Column(
        Modifier.weight(1f).fillMaxSize().clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, fontSize = 10.sp, color = tint)
    }
}
