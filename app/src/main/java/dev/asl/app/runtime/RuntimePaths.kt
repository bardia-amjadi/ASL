package dev.asl.app.runtime

import android.content.Context
import java.io.File

/** All filesystem locations used by the PoC runtime. */
class RuntimePaths(context: Context) {
    private val app = context.applicationContext

    val filesDir: File = app.filesDir
    val targetSdk: Int = app.applicationInfo.targetSdkVersion

    /** Directory Android extracted our lib*.so files into at install time (executable location). */
    val nativeDir: File = File(app.applicationInfo.nativeLibraryDir)

    /**
     * ABI the package manager actually installed us as (derived from nativeLibraryDir's name,
     * e.g. ".../lib/arm64"). This is more reliable than Build.SUPPORTED_ABIS[0].
     */
    val abi: String? = when (nativeDir.name) {
        "arm64" -> "arm64-v8a"
        "arm" -> "armeabi-v7a"
        else -> null
    }

    val proot = File(nativeDir, "libproot.so")
    val loader = File(nativeDir, "libproot-loader.so")
    val loader32 = File(nativeDir, "libproot-loader32.so")
    val talloc = File(nativeDir, "libtalloc.so")

    val rootfs = File(filesDir, "rootfs/alpine")
    val rootfsStaging = File(filesDir, "rootfs/alpine.staging")
    val installedMarker = File(filesDir, "rootfs/alpine.installed")

    val downloadsDir = File(filesDir, "downloads")
    val debianRoot = File(filesDir, "rootfs/debian")
    val debianStaging = File(filesDir, "rootfs/debian.staging")
    val debianMarker = File(filesDir, "rootfs/debian.installed")

    val tmp = File(app.cacheDir, "proot-tmp")
    val execTestDir = File(filesDir, "exec-test")
}
