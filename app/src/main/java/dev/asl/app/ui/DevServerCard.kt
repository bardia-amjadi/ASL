package dev.asl.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.asl.app.devserver.DevServer
import dev.asl.app.runtime.RuntimePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Real dev workflow: install Python in Debian, run an HTTP server, see its output, open it in the browser. */
@Composable
fun DevServerCard(paths: RuntimePaths, root: java.io.File, distroLabel: String, disableSeccomp: Boolean, busy: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by DevServer.status.collectAsStateWithLifecycle()
    val log by DevServer.log.collectAsStateWithLifecycle()
    val pythonInstalled = DevServer.isPythonInstalled(root) || status is DevServer.Status.Running || status is DevServer.Status.Starting
    val installing = status is DevServer.Status.Installing
    val running = status is DevServer.Status.Running || status is DevServer.Status.Starting

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = AslColors.Surface,
        border = BorderStroke(1.dp, AslColors.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Developer workflow: Python web server ($distroLabel)", style = MaterialTheme.typography.titleSmall)
            Text(
                "Runs python3 -m http.server inside $distroLabel on 127.0.0.1:${DevServer.PORT}. Reachable from this phone only (not from your network). " +
                    "The page lives in /root/www; edit it from the Files tab or a terminal.",
                style = MaterialTheme.typography.bodySmall,
                color = AslColors.TextDim,
            )
            val (text, color) = when (val s = status) {
                is DevServer.Status.Idle -> (if (pythonInstalled) "Python installed. Server stopped." else "Python is not installed in $distroLabel yet.") to AslColors.TextDim
                is DevServer.Status.Installing -> "Installing Python..." to AslColors.Warn
                is DevServer.Status.Starting -> "Starting..." to AslColors.Warn
                is DevServer.Status.Running -> "Running: http://127.0.0.1:${s.port}" to AslColors.Good
                is DevServer.Status.Stopped -> "Stopped (exit code ${s.exitCode ?: "?"})" to AslColors.TextDim
                is DevServer.Status.Failed -> s.message to AslColors.Bad
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = color)
            if (installing || status is DevServer.Status.Starting) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (!pythonInstalled) {
                    Button(
                        onClick = { scope.launch(Dispatchers.IO) { DevServer.installPython(paths, root, disableSeccomp) } },
                        enabled = !installing && !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("Install Python") }
                } else if (running) {
                    Button(onClick = { DevServer.stop() }, modifier = Modifier.weight(1f)) { Text("Stop server") }
                } else {
                    Button(
                        onClick = { scope.launch(Dispatchers.IO) { DevServer.start(context, paths, root, disableSeccomp) } },
                        enabled = !installing && !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("Start server") }
                }
                OutlinedButton(
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:${DevServer.PORT}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(context, "No browser found", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = status is DevServer.Status.Running,
                ) { Text("Open in browser") }
            }

            if (log.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 140.dp)
                        .background(AslColors.TerminalBg, RoundedCornerShape(8.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                ) {
                    Text(log.takeLast(40).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp)
                }
            }
        }
    }
}
