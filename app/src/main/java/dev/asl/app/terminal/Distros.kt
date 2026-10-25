package dev.asl.app.terminal

import dev.asl.app.runtime.DebianInstaller
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
)

object Distros {
    fun all(paths: RuntimePaths): List<DistroInfo> = listOf(
        DistroInfo("debian", "Debian", "D", 0xFFD70751, paths.debianRoot, "/bin/bash", DebianInstaller.isInstalled(paths)),
        DistroInfo("alpine", "Alpine", "A", 0xFF0D597F, paths.rootfs, "/bin/sh", RootfsInstaller.isInstalled(paths)),
    )

    fun installed(paths: RuntimePaths): List<DistroInfo> = all(paths).filter { it.installed }
}
