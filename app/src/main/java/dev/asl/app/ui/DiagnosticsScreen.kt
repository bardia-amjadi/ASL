package dev.asl.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.asl.app.PocViewModel

@Composable
fun DiagnosticsScreen(vm: PocViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val scroll = rememberScrollState()

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Log and diagnostics", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = vm::runAll, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Run all") }
            OutlinedButton(onClick = vm::execExperiments, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Exec tests") }
            OutlinedButton(onClick = vm::runLinuxProbe, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("uname -m") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Switch(checked = state.disableSeccomp, onCheckedChange = vm::setDisableSeccomp, enabled = !state.busy)
            Text(
                "  Compatibility: disable PRoot seccomp acceleration (applies to new sessions)",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { clipboard.setText(AnnotatedString(state.report)) }) { Text("Copy report") }
            TextButton(onClick = vm::clearReport, enabled = !state.busy) { Text("Clear") }
        }
        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(AslColors.TerminalBg, RoundedCornerShape(8.dp))
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
