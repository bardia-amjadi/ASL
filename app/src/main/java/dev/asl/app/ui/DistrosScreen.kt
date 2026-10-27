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
import dev.asl.app.runtime.DistroInstaller
import dev.asl.app.runtime.RuntimePaths
import dev.asl.app.terminal.DistroInfo
import dev.asl.app.terminal.Distros

@Composable
fun DistrosScreen(vm: PocViewModel, onOpen: (String) -> Unit, onShowLog: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val paths = remember { RuntimePaths(context) }
    val distros = remember(state.installedIds) { Distros.all(paths) }
    var confirmRemove by remember { mutableStateOf<DistroInfo?>(null) }

    confirmRemove?.let { d ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove ${d.label}?") },
            text = { Text("This permanently deletes ${d.label} and everything stored inside it (including /root), plus any partial download. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeDistro(d.id)
                    confirmRemove = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Keep") } },
        )
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Distributions", style = MaterialTheme.typography.titleMedium)

        distros.forEach { d ->
            val manifest = remember(d.id) { if (d.downloaded) paths.abi?.let { DistroInstaller.readManifest(context, it, d.id) } else null }
            val subtitle = when {
                !d.downloaded -> "Tiny test guest (about 4 MB), bundled in the app"
                manifest != null -> "${manifest.version} \u00b7 ${manifest.size / 1024 / 1024} MB download \u00b7 signature: ${manifest.signature}"
                else -> "Not included in this build (the matching resolve script did not run)"
            }
            val installingThis = state.installingId == d.id
            DistroCard(
                badge = { DistroBadge(d, 36.dp) },
                title = d.label,
                subtitle = subtitle,
                status = state.statusById[d.id] ?: "",
                progress = if (installingThis) (state.progress ?: -1f) else null,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (d.installed) {
                        Button(onClick = { onOpen(d.id) }, modifier = Modifier.weight(1f)) { Text("Open terminal") }
                        OutlinedButton(onClick = { confirmRemove = d }, enabled = !state.busy) { Text("Remove") }
                    } else {
                        val canInstall = !state.busy && (!d.downloaded || manifest != null)
                        Button(
                            onClick = { if (d.downloaded) vm.installDistro(d.id) else vm.installRootfs() },
                            enabled = canInstall,
                            modifier = Modifier.weight(1f),
                        ) { Text("Install") }
                        if (installingThis) OutlinedButton(onClick = vm::cancelInstall) { Text("Cancel") }
                    }
                }
                if (d.installed && d.isApt) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { vm.runDistroProbe(d.id); onShowLog() }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Self-test") }
                        OutlinedButton(onClick = { vm.aptUpdate(d.id); onShowLog() }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("apt update") }
                    }
                }
            }
        }

        // The dev-server workflow works in any installed apt distro; use the first one.
        distros.firstOrNull { it.installed && it.isApt }?.let { d ->
            DevServerCard(paths = paths, root = d.root, distroLabel = d.label, disableSeccomp = state.disableSeccomp, busy = state.busy)
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
