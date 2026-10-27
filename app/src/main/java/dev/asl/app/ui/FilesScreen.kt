package dev.asl.app.ui

import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.asl.app.files.FileEntry
import dev.asl.app.files.GuestFs
import dev.asl.app.files.GuestPaths
import dev.asl.app.terminal.DistroInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun FilesScreen(installed: List<DistroInfo>, onGoToDistros: () -> Unit) {
    if (installed.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(FolderIcon, contentDescription = null, tint = AslColors.TextDim, modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(10.dp))
            Text("No Linux files yet", style = MaterialTheme.typography.titleMedium)
            Text("Install a distribution to browse its files.", color = AslColors.TextDim, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(14.dp))
            Button(onClick = onGoToDistros) { Text("Get a distro") }
        }
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var distroId by rememberSaveable { mutableStateOf(installed.first().id) }
    val distro = installed.firstOrNull { it.id == distroId } ?: installed.first()
    var path by rememberSaveable(distro.id) { mutableStateOf("/root") }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var viewing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var deleting by remember { mutableStateOf<FileEntry?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf<FileEntry?>(null) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    LaunchedEffect(distro.id, path, reload) {
        val result = withContext(Dispatchers.IO) { runCatching { GuestFs.list(distro.root, path) } }
        result.onSuccess {
            entries = it
            error = null
        }.onFailure {
            entries = emptyList()
            error = it.message ?: it.toString()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    val name = GuestFs.displayName(context, uri) ?: "imported-file"
                    GuestFs.importFrom(context, uri, distro.root, path, name)
                    "Imported $name into $path"
                }.getOrElse { "Import failed: ${it.message}" }
            }
            toast(msg)
            reload++
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val entry = exporting
        if (uri != null && entry != null) scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    GuestFs.exportTo(context, distro.root, entry.guestPath, uri)
                    "Exported ${entry.name}"
                }.getOrElse { "Export failed: ${it.message}" }
            }
            toast(msg)
        }
        exporting = null
    }

    viewing?.let { (title, text) ->
        AlertDialog(
            onDismissRequest = { viewing = null },
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Box(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
                }
            },
            confirmButton = { TextButton(onClick = { viewing = null }) { Text("Close") } },
        )
    }

    deleting?.let { e ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${e.name}?") },
            text = { Text("This permanently deletes ${if (e.isDir && !e.isSymlink) "the folder and everything in it" else "it"} from ${distro.label}. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        val msg = withContext(Dispatchers.IO) {
                            runCatching { GuestFs.delete(distro.root, e.guestPath); "Deleted ${e.name}" }.getOrElse { "Delete failed: ${it.message}" }
                        }
                        toast(msg)
                        reload++
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } },
        )
    }

    if (creatingFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creatingFolder = false },
            title = { Text("New folder") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = {
                TextButton(onClick = {
                    creatingFolder = false
                    scope.launch {
                        val msg = withContext(Dispatchers.IO) {
                            runCatching { GuestFs.mkdir(distro.root, path, name.trim()); "Created ${name.trim()}" }.getOrElse { "Failed: ${it.message}" }
                        }
                        toast(msg)
                        reload++
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { creatingFolder = false }) { Text("Cancel") } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        // Distro selector (which distribution's files)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            installed.forEach { d ->
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
        Text(
            "Linux files, private to ASL. Use Import / Export to exchange files with Android.",
            color = AslColors.TextDim,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        // Path bar
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { path = GuestPaths.parent(path) }, enabled = path != "/", modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Up one folder", tint = AslColors.Accent)
            }
            Text(
                path,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { reload++ }, modifier = Modifier.size(38.dp)) { Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = AslColors.TextDim) }
            IconButton(onClick = { creatingFolder = true }, modifier = Modifier.size(38.dp)) { Icon(Icons.Default.Add, contentDescription = "New folder", tint = AslColors.TextDim) }
            TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Import") }
        }
        HorizontalDivider(color = AslColors.Border)

        if (error != null) {
            Text("Cannot open this folder: $error", color = AslColors.Bad, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        } else if (entries.isEmpty()) {
            Text("This folder is empty.", color = AslColors.TextDim, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(entries, key = { it.guestPath }) { e ->
                FileRow(
                    entry = e,
                    onClick = {
                        if (e.isDir) {
                            path = e.guestPath
                        } else {
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { runCatching { GuestFs.readText(distro.root, e.guestPath) } }
                                r.onSuccess { viewing = e.name to it }.onFailure { toast(it.message ?: "Cannot open") }
                            }
                        }
                    },
                    onExport = {
                        exporting = e
                        exportLauncher.launch(e.name)
                    },
                    onDelete = { deleting = e },
                )
            }
        }
    }
}

@Composable
private fun FileRow(entry: FileEntry, onClick: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val meta = buildString {
        append(GuestFs.modeString(entry))
        if (!entry.isDir) append(" \u00b7 ").append(Formatter.formatShortFileSize(context, entry.size))
        if (entry.modifiedMs > 0) append(" \u00b7 ").append(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.modifiedMs)))
        if (entry.isSymlink) append(" \u00b7 \u2192 ").append(entry.linkTarget ?: "?")
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (entry.isDir) FolderIcon else FileIcon,
            contentDescription = null,
            tint = if (entry.isDir) AslColors.Accent else AslColors.TextDim,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
            Text(meta, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp, color = AslColors.TextDim, fontFamily = FontFamily.Monospace)
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "Actions", tint = AslColors.TextDim)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (!entry.isDir) DropdownMenuItem(text = { Text("Export to Android...") }, onClick = { menu = false; onExport() })
                DropdownMenuItem(text = { Text("Delete...") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}
