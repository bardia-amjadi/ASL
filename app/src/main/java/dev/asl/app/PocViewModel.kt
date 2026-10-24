package dev.asl.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.asl.app.runtime.Experiments
import dev.asl.app.runtime.Finding
import dev.asl.app.runtime.RootfsInstaller
import dev.asl.app.runtime.RuntimePaths
import kotlinx.coroutines.Dispatchers
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
)

class PocViewModel(application: Application) : AndroidViewModel(application) {
    private val paths = RuntimePaths(application)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        task { append(Experiments.describeEnvironment(getApplication<Application>(), paths) + "\n") }
    }

    fun setDisableSeccomp(value: Boolean) = _state.update { it.copy(disableSeccomp = value) }

    fun clearReport() = _state.update { it.copy(report = "") }

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
        append("[INFO] Test rootfs and temporary files removed.\n")
    }

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
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun log(f: Finding) = append("[${f.verdict}] ${f.title}\n${f.detail.prependIndent("    ")}\n")

    private fun append(text: String) = _state.update { it.copy(report = it.report + text + "\n") }
}
