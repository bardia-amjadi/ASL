package dev.asl.app.runtime

import android.content.Context
import android.os.Build
import java.io.File
import java.io.IOException

/**
 * The experiments that decide whether the approach works. Each one reports what actually happened on
 * THIS device. Nothing is assumed: results are PASS/FAIL/INFO with the raw process output attached.
 */
object Experiments {

    fun describeEnvironment(context: Context, paths: RuntimePaths): String = buildString {
        appendLine("=== Environment ===")
        appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("supported ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("installed as ABI: ${paths.abi ?: "UNSUPPORTED (lib dir '${paths.nativeDir.name}')"}")
        appendLine("targetSdk: ${paths.targetSdk}")
        appendLine("nativeLibraryDir: ${paths.nativeDir}")
        appendLine("SELinux enforce node: ${selinux()}")
        for (f in listOf(paths.proot, paths.loader, paths.loader32, paths.talloc)) {
            appendLine("  ${f.name}: ${describeFile(f)}")
        }
        val abi = paths.abi
        if (abi != null) {
            RootfsInstaller.readRuntimeInfo(context, abi)?.let {
                appendLine("runtime provenance (from build):")
                appendLine(it.prependIndent("  "))
            } ?: appendLine("runtime provenance: asset missing (APK built without fetch-runtime-assets.sh?)")
        }
    }.trimEnd()

    /** Experiment A: can we exec a binary that was extracted into nativeLibraryDir? */
    fun execFromNativeDir(paths: RuntimePaths, disableSeccomp: Boolean): Finding {
        val title = "A. exec libproot.so from nativeLibraryDir (`--version`)"
        if (!paths.proot.exists()) return Finding(title, Verdict.FAIL, "libproot.so not present in ${paths.nativeDir}. Native libraries were not packaged/extracted.")
        val r = ProcessRunner.run(listOf(paths.proot.path, "--version"), Proot.env(paths, disableSeccomp), paths.filesDir)
        return Finding(title, if (r.succeeded) Verdict.PASS else Verdict.FAIL, r.describe())
    }

    /**
     * Experiment B: the control. Copy the same executable into app-private data and try to exec it.
     * On targetSdk >= 29 devices this is expected to be denied (W^X / SELinux). It is reported as INFO,
     * because the product does not depend on its outcome; it documents why nativeLibraryDir is used.
     */
    fun execFromDataDir(paths: RuntimePaths): Finding {
        val title = "B. control: exec a COPY from app data dir (expected: blocked)"
        if (!paths.proot.exists() || !paths.talloc.exists()) return Finding(title, Verdict.FAIL, "Runtime files missing; cannot run control.")
        return try {
            RootfsInstaller.deleteTree(paths.execTestDir)
            paths.execTestDir.mkdirs()
            val copy = File(paths.execTestDir, "proot-copy")
            paths.proot.copyTo(copy, overwrite = true)
            paths.talloc.copyTo(File(paths.execTestDir, "libtalloc.so"), overwrite = true)
            copy.setExecutable(true, false)
            val env = mapOf("LD_LIBRARY_PATH" to paths.execTestDir.path, "HOME" to paths.filesDir.path)
            val r = ProcessRunner.run(listOf(copy.path, "--version"), env, paths.filesDir)
            val verdictText = when {
                r.startError != null && (r.startError.contains("error=13") || r.startError.contains("Permission denied", true)) ->
                    "BLOCKED as expected (EACCES). This is the Android 10+ restriction on executing files in app data."
                r.succeeded -> "ALLOWED on this device. Not relied upon: other devices/OS versions enforce the restriction."
                else -> "Failed in a different way (see below)."
            }
            Finding(title, Verdict.INFO, verdictText + "\n" + r.describe())
        } catch (e: IOException) {
            Finding(title, Verdict.INFO, "Control could not be prepared: ${e.message}")
        } finally {
            RootfsInstaller.deleteTree(paths.execTestDir)
        }
    }

    /** Experiment C: run real commands inside the Linux rootfs via PRoot and report the real output. */
    fun linuxProbe(paths: RuntimePaths, disableSeccomp: Boolean): Finding {
        val title = "C. real Linux commands inside PRoot (Alpine guest)"
        if (!RootfsInstaller.isInstalled(paths)) return Finding(title, Verdict.FAIL, "Test rootfs is not installed. Run \"Install rootfs\" first.")
        paths.tmp.mkdirs()
        val script = """
            echo "uname -m : $(uname -m)"
            echo "uname -sr: $(uname -sr)"
            echo "id       : $(id)"
            echo "pwd      : $(pwd)"
            echo "release  : $(cat /etc/alpine-release)"
            busybox | head -n 1
        """.trimIndent()
        val r = ProcessRunner.run(Proot.guestShell(paths, script), Proot.env(paths, disableSeccomp), paths.filesDir)

        val machine = Regex("""uname -m : (\S+)""").find(r.stdout)?.groupValues?.get(1)
        val release = Regex("""release  : (\S+)""").find(r.stdout)?.groupValues?.get(1)
        val ok = r.succeeded && machine != null && release != null
        val note = when (paths.abi) {
            "arm64-v8a" -> "expected uname -m for an arm64 app: aarch64"
            "armeabi-v7a" -> "expected uname -m for an arm32 app: armv7l (or armv8l on a 64-bit kernel)"
            else -> ""
        }
        val summary = if (ok) "Real guest userland answered (Alpine $release). $note" else "Probe did not complete. If stderr mentions ptrace/seccomp, retry with the seccomp switch toggled."
        return Finding(title, if (ok) Verdict.PASS else Verdict.FAIL, summary + "\n" + r.describe())
    }

    private fun selinux(): String = try {
        File("/sys/fs/selinux/enforce").readText().trim().let { if (it == "1") "1 (enforcing)" else "$it (permissive)" }
    } catch (e: Exception) {
        "not readable from the app sandbox"
    }

    private fun describeFile(f: File): String {
        if (!f.exists()) return "MISSING"
        return "${f.length()} bytes, canExecute=${f.canExecute()}, ${elfSummary(f)}"
    }

    /** Reads the ELF header so ABI/machine mismatches are visible instead of guessed. */
    private fun elfSummary(f: File): String {
        val h = ByteArray(20)
        try {
            val n = f.inputStream().use { it.read(h) }
            if (n < 20) return "too short for ELF"
        } catch (e: IOException) {
            return "unreadable (${e.message})"
        }
        if (h[0] != 0x7F.toByte() || h[1] != 'E'.code.toByte() || h[2] != 'L'.code.toByte() || h[3] != 'F'.code.toByte()) return "NOT an ELF file"
        val cls = when (h[4].toInt()) { 1 -> "ELF32"; 2 -> "ELF64"; else -> "ELF?" }
        val type = when (h[16].toInt()) { 2 -> "EXEC"; 3 -> "DYN"; else -> "type=${h[16]}" }
        val machine = (h[18].toInt() and 0xFF) or ((h[19].toInt() and 0xFF) shl 8)
        val m = when (machine) {
            0xB7 -> "AArch64"
            0x28 -> "ARM"
            0x03 -> "x86"
            0x3E -> "x86-64"
            else -> "machine=0x${machine.toString(16)}"
        }
        return "$cls $m $type"
    }
}
