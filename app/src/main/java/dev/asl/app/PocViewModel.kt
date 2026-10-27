package dev.asl.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.asl.app.runtime.DistroInstaller
import dev.asl.app.runtime.Experiments
import dev.asl.app.runtime.Finding
import dev.asl.app.terminal.Distros
import dev.asl.app.runtime.RootfsInstaller
import dev.asl.app.runtime.RuntimePaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val busy: Boolean = false,
    val disableSeccomp: Boolean = false,
    val report: String = "",
    /** Id of the distro being installed right now, if any. */
    val installingId: String? = null,
    val installedIds: Set<String> = emptySet(),
    val statusById: Map<String, String> = emptyMap(),
    val progress: Float? = null,
) {
    val installing: Boolean get() = installingId != null
    fun isInstalled(id: String): Boolean = id in installedIds
}

class PocViewModel(application: Application) : AndroidViewModel(application) {
    private val paths = RuntimePaths(application)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var installJob: Job? = null

    init {
        _state.update { it.withDistroStatus() }
        task { append(Experiments.describeEnvironment(getApplication<Application>(), paths) + "\n") }
    }

    fun setDisableSeccomp(value: Boolean) = _state.update { it.copy(disableSeccomp = value) }

    fun clearReport() = _state.update { it.copy(report = "") }

    // ---- Phase 1 diagnostics ----------------------------------------------------------------

    fun runAll() = task {
        val seccomp = _state.value.disableSeccomp
        append(Experiments.describeEnvironment(getApplication<Application>(), paths) + "\n")
        log(Experiments.execFromNativeDir(paths, seccomp))
        log(Experiments.execFromDataDir(paths))
        if (!RootfsInstaller.isInstalled(paths)) log(RootfsInstaller.install(getApplication<Application>(), paths))
        log(Experiments.linuxProbe(paths, seccomp))
    }

    fun execExperiments() = task {
        val seccomp = _state.value.disableSeccomp
        log(Experiments.execFromNativeDir(paths, seccomp))
        log(Experiments.execFromDataDir(paths))
    }

    fun installRootfs() = task { log(RootfsInstaller.install(getApplication<Application>(), paths)) }

    fun runLinuxProbe() = task { log(Experiments.linuxProbe(paths, _state.value.disableSeccomp)) }

    fun removeRootfs() = task {
        RootfsInstaller.remove(paths)
        append("[INFO] Alpine test rootfs and temporary files removed.\n")
    }

    // ---- Distros (download-based: Debian 13/12, Ubuntu) ---------------------------------------

    private fun labelOf(id: String): String = Distros.all(paths).firstOrNull { it.id == id }?.label ?: id

    fun installDistro(id: String) {
        if (_state.value.busy) return
        val label = labelOf(id)
        _state.update { it.copy(busy = true, installingId = id, progress = null, statusById = it.statusById + (id to "Starting...")) }
        installJob = viewModelScope.launch {
            var lastKey = ""
            try {
                val finding = DistroInstaller.install(
                    getApplication<Application>(), paths, _state.value.disableSeccomp, id, label,
                ) { phase, fraction ->
                    val percent = fraction?.let { (it * 100).toInt() }
                    val key = "$phase:$percent"
                    if (key != lastKey) {
                        lastKey = key
                        _state.update {
                            it.copy(
                                statusById = it.statusById + (id to if (percent != null) "$phase $percent%" else "$phase..."),
                                progress = fraction,
                            )
                        }
                    }
                }
                log(finding)
            } catch (e: CancellationException) {
                append("[INFO] $label install cancelled. A partial download is kept so it can resume.\n")
                throw e
            } catch (t: Throwable) {
                append("[FAIL] Unexpected error: $t\n")
            } finally {
                _state.update { it.copy(busy = false, installingId = null, progress = null).withDistroStatus() }
            }
        }
    }

    fun cancelInstall() {
        installJob?.cancel()
    }

    fun runDistroProbe(id: String) = task { log(DistroInstaller.probe(paths, _state.value.disableSeccomp, id, labelOf(id))) }

    fun aptUpdate(id: String) = task { log(DistroInstaller.aptUpdate(paths, _state.value.disableSeccomp, id, labelOf(id))) }

    fun removeDistro(id: String) = task {
        if (id == "alpine") RootfsInstaller.remove(paths) else DistroInstaller.remove(paths, id)
        append("[INFO] ${labelOf(id)} and its downloads were removed.\n")
    }

    fun removeAll() = task {
        dev.asl.app.devserver.DevServer.stop()
        dev.asl.app.desktop.DesktopManager.stop()
        RootfsInstaller.remove(paths)
        Distros.all(paths).filter { it.downloaded }.forEach { DistroInstaller.remove(paths, it.id) }
        append("[INFO] All Linux data (distros, downloads) was removed.\n")
    }

    // ---- plumbing ------------------------------------------------------------------------------

    /** Runs blocking work off the main thread; ignores taps while another task is running. */
    private fun task(block: () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (t: Throwable) {
                append("[FAIL] Unexpected error: $t\n")
            } finally {
                _state.update { it.copy(busy = false).withDistroStatus() }
            }
        }
    }

    private fun UiState.withDistroStatus(): UiState {
        val all = Distros.all(paths)
        val installed = all.filter { it.installed }.map { it.id }.toSet()
        val partial = DistroInstaller.hasPartialDownload(paths)
        val status = all.associate { d ->
            d.id to when {
                d.installed -> "Installed"
                partial && d.downloaded -> "Not installed (a partial download may be kept; Install resumes it)"
                else -> "Not installed"
            }
        }
        return copy(installedIds = installed, statusById = if (installing) statusById else status)
    }

    private fun log(f: Finding) = append("[${f.verdict}] ${f.title}\n${f.detail.prependIndent("    ")}\n")

    private fun append(text: String) = _state.update { it.copy(report = it.report + text + "\n") }
}
