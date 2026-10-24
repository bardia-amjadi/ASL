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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("ASL — Phase 1 runtime proof of concept", style = MaterialTheme.typography.titleMedium)
            Text(
                "Runs real PRoot + an Alpine rootfs and shows the real output. " +
                    "Nothing on this screen is simulated; a FAIL is a genuine result.",
                style = MaterialTheme.typography.bodySmall,
            )

            Button(onClick = vm::runAll, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Run all tests")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = vm::execExperiments, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Exec tests") }
                OutlinedButton(onClick = vm::installRootfs, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Install rootfs") }
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
                TextButton(onClick = vm::removeRootfs, enabled = !state.busy) { Text("Remove rootfs") }
            }
            if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            Box(
                modifier = Modifier
                    .weight(1f)
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
