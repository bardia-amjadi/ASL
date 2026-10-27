package dev.asl.app.terminal

import dev.asl.app.runtime.DistroInstaller
import dev.asl.app.runtime.RootfsInstaller
import dev.asl.app.runtime.RuntimePaths
import java.io.File

/** A Linux distribution a terminal session can be opened in. */
data class DistroInfo(
    val id: String,
    val label: String,
    val letter: String,
    val colorArgb: Long,
    val root: File,
    val shell: String,
    val installed: Boolean,
    /** "apt" (Debian family: apt, dpkg, desktop environments) or "alpine". */
    val family: String,
    /** True when installation downloads a manifest-pinned root filesystem (everything except the bundled Alpine). */
    val downloaded: Boolean,
) {
    val isApt: Boolean get() = family == "apt"
}

object Distros {
    fun all(paths: RuntimePaths): List<DistroInfo> = listOf(
        DistroInfo("debian", "Debian 13", "D", 0xFFD70751, paths.rootOf("debian"), "/bin/bash", DistroInstaller.isInstalled(paths, "debian"), "apt", true),
        DistroInfo("debian12", "Debian 12", "D", 0xFFA80030, paths.rootOf("debian12"), "/bin/bash", DistroInstaller.isInstalled(paths, "debian12"), "apt", true),
        DistroInfo("ubuntu", "Ubuntu 24.04", "U", 0xFFE95420, paths.rootOf("ubuntu"), "/bin/bash", DistroInstaller.isInstalled(paths, "ubuntu"), "apt", true),
        DistroInfo("alpine", "Alpine", "A", 0xFF0D597F, paths.rootOf("alpine"), "/bin/sh", RootfsInstaller.isInstalled(paths), "alpine", false),
    )

    fun installed(paths: RuntimePaths): List<DistroInfo> = all(paths).filter { it.installed }
}
