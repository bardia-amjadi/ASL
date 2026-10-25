package dev.asl.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val viewModel: PocViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PocScreen(viewModel)
            }
        }
    }
}

@Composable
private fun PocScreen(vm: PocViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val scroll = rememberScrollState()
    var confirmRemove by remember { mutableStateOf(false) }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove Debian?") },
            text = {
                Text(
                    "This permanently deletes the Debian system, everything you stored inside it " +
                        "(including /root), and any partial download. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    vm.removeDebian()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Keep") } },
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("ASL — runtime proof of concept (Phase 2)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Real PRoot, real rootfs, real output. Nothing is simulated; a FAIL is a genuine result.",
                style = MaterialTheme.typography.bodySmall,
            )

            // ---- Debian ----
            Text("Debian: ${state.debianStatus}", style = MaterialTheme.typography.titleSmall)
            if (state.installing) {
                val p = state.debianProgress
                if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = vm::installDebian,
                    enabled = !state.busy && !state.debianInstalled,
                    modifier = Modifier.weight(1f),
                ) { Text("Install Debian") }
                OutlinedButton(onClick = vm::cancelDebian, enabled = state.installing, modifier = Modifier.weight(1f)) { Text("Cancel") }
                OutlinedButton(
                    onClick = { confirmRemove = true },
                    enabled = !state.busy && state.debianInstalled,
                    modifier = Modifier.weight(1f),
                ) { Text("Remove") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = vm::runDebianProbe, enabled = !state.busy && state.debianInstalled, modifier = Modifier.weight(1f)) { Text("Debian test") }
                OutlinedButton(onClick = vm::aptUpdate, enabled = !state.busy && state.debianInstalled, modifier = Modifier.weight(1f)) { Text("apt update") }
            }

            // ---- Phase 1 diagnostics ----
            Text("Phase 1 diagnostics (Alpine)", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = vm::runAll, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Run all") }
                OutlinedButton(onClick = vm::execExperiments, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Exec tests") }
                OutlinedButton(onClick = vm::runLinuxProbe, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("uname -m") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Switch(checked = state.disableSeccomp, onCheckedChange = vm::setDisableSeccomp, enabled = !state.busy)
                Text(
                    "  Disable PRoot seccomp acceleration (PROOT_NO_SECCOMP=1)",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(state.report)) }) { Text("Copy report") }
                TextButton(onClick = vm::clearReport, enabled = !state.busy) { Text("Clear") }
                TextButton(onClick = vm::installRootfs, enabled = !state.busy) { Text("Install Alpine") }
                TextButton(onClick = vm::removeRootfs, enabled = !state.busy) { Text("Remove Alpine") }
            }
            if (state.busy && !state.installing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 120.dp)
                    .fillMaxWidth()
                    .background(Color(0xFF0F1318), RoundedCornerShape(8.dp))
                    .padding(8.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = state.report,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.verticalScroll(scroll),
                    )
                }
            }
        }
    }
}
