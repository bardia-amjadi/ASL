package dev.asl.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.asl.app.PocViewModel
import dev.asl.app.runtime.DebianInstaller
import dev.asl.app.runtime.RuntimePaths
import dev.asl.app.terminal.Distros

@Composable
fun DistrosScreen(vm: PocViewModel, onOpen: (String) -> Unit, onShowLog: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val paths = remember { RuntimePaths(context) }
    val distros = remember(state.debianInstalled, state.alpineInstalled) { Distros.all(paths) }
    val debian = distros.first { it.id == "debian" }
    val alpine = distros.first { it.id == "alpine" }
    val manifest = remember { paths.abi?.let { DebianInstaller.readManifest(context, it) } }
    var confirmRemove by remember { mutableStateOf<String?>(null) }

    confirmRemove?.let { which ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove ${if (which == "debian") "Debian" else "Alpine"}?") },
            text = {
                Text(
                    "This permanently deletes the system and everything stored inside it (including /root)" +
                        (if (which == "debian") ", plus any partial download" else "") +
                        ". This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (which == "debian") vm.removeDebian() else vm.removeRootfs()
                    confirmRemove = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Keep") } },
        )
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Distributions", style = MaterialTheme.typography.titleMedium)

        // ---- Debian ----
        DistroCard(
            badge = { DistroBadge(debian, 36.dp) },
            title = "Debian",
            subtitle = manifest?.let { "${it.version} (${it.suite}-${it.variant}) \u00b7 ${it.size / 1024 / 1024} MB download" }
                ?: "Debian rootfs info missing from this build",
            status = state.debianStatus,
            progress = if (state.installing) (state.debianProgress ?: -1f) else null,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (state.debianInstalled) {
                    Button(onClick = { onOpen("debian") }, modifier = Modifier.weight(1f)) { Text("Open terminal") }
                } else {
                    Button(onClick = vm::installDebian, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Install") }
                }
                if (state.installing) OutlinedButton(onClick = vm::cancelDebian) { Text("Cancel") }
                if (state.debianInstalled) {
                    OutlinedButton(onClick = { confirmRemove = "debian" }, enabled = !state.busy) { Text("Remove") }
                }
            }
            if (state.debianInstalled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { vm.runDebianProbe(); onShowLog() }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Self-test") }
                    OutlinedButton(onClick = { vm.aptUpdate(); onShowLog() }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("apt update") }
                }
            }
        }

        // ---- Alpine ----
        DistroCard(
            badge = { DistroBadge(alpine, 36.dp) },
            title = "Alpine Linux",
            subtitle = "Tiny test guest (about 4 MB), bundled in the app",
            status = if (state.alpineInstalled) "Installed" else "Not installed",
            progress = null,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (state.alpineInstalled) {
                    Button(onClick = { onOpen("alpine") }, modifier = Modifier.weight(1f)) { Text("Open terminal") }
                    OutlinedButton(onClick = { confirmRemove = "alpine" }, enabled = !state.busy) { Text("Remove") }
                } else {
                    Button(onClick = vm::installRootfs, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Install") }
                }
            }
        }

        TextButton(onClick = onShowLog) { Text("Show log and diagnostics") }
    }
}

@Composable
private fun DistroCard(
    badge: @Composable () -> Unit,
    title: String,
    subtitle: String,
    status: String,
    progress: Float?,
    actions: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = AslColors.Surface,
        border = BorderStroke(1.dp, AslColors.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                badge()
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AslColors.TextDim)
                }
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = if (status == "Installed") AslColors.Good else AslColors.TextDim)
            if (progress != null) {
                if (progress >= 0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(2.dp))
            actions()
        }
    }
}
