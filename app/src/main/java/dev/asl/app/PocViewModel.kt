package dev.asl.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.asl.app.runtime.DebianInstaller
import dev.asl.app.runtime.Experiments
import dev.asl.app.runtime.Finding
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
    val installing: Boolean = false,
    val debianInstalled: Boolean = false,
    val debianStatus: String = "Checking...",
    val debianProgress: Float? = null,
)

class PocViewModel(application: Application) : AndroidViewModel(application) {
    private val paths = RuntimePaths(application)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var installJob: Job? = null

    init {
        _state.update { it.withDebianStatus() }
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

    // ---- Phase 2: Debian ---------------------------------------------------------------------

    fun installDebian() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, installing = true, debianStatus = "Starting...", debianProgress = null) }
        installJob = viewModelScope.launch {
            var lastKey = ""
            try {
                val finding = DebianInstaller.install(
                    getApplication<Application>(), paths, _state.value.disableSeccomp,
                ) { phase, fraction ->
                    val percent = fraction?.let { (it * 100).toInt() }
                    val key = "$phase:$percent"
                    if (key != lastKey) {
                        lastKey = key
                        _state.update {
                            it.copy(
                                debianStatus = if (percent != null) "$phase $percent%" else "$phase...",
                                debianProgress = fraction,
                            )
                        }
                    }
                }
                log(finding)
            } catch (e: CancellationException) {
                append("[INFO] Debian install cancelled. A partial download is kept so it can resume.\n")
                throw e
            } catch (t: Throwable) {
                append("[FAIL] Unexpected error: $t\n")
            } finally {
                _state.update { it.copy(busy = false, installing = false, debianProgress = null).withDebianStatus() }
            }
        }
    }

    fun cancelDebian() {
        installJob?.cancel()
    }

    fun runDebianProbe() = task { log(DebianInstaller.probe(paths, _state.value.disableSeccomp)) }

    fun aptUpdate() = task { log(DebianInstaller.aptUpdate(paths, _state.value.disableSeccomp)) }

    fun removeDebian() = task {
        DebianInstaller.remove(paths)
        append("[INFO] Debian and its downloads were removed.\n")
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
                _state.update { it.copy(busy = false).withDebianStatus() }
            }
        }
    }

    private fun UiState.withDebianStatus(): UiState {
        val installed = DebianInstaller.isInstalled(paths)
        val status = when {
            installed -> "Installed"
            DebianInstaller.hasPartialDownload(paths) -> "Not installed (partial download kept; Install resumes it)"
            else -> "Not installed"
        }
        return copy(debianInstalled = installed, debianStatus = if (installing) debianStatus else status)
    }

    private fun log(f: Finding) = append("[${f.verdict}] ${f.title}\n${f.detail.prependIndent("    ")}\n")

    private fun append(text: String) = _state.update { it.copy(report = it.report + text + "\n") }
}
