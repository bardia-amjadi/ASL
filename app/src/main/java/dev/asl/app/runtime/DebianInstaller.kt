package dev.asl.app.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.system.ErrnoException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest

/** What the build pinned for this ABI (see scripts/resolve-debian.sh and docs/ADR-0002). */
data class DebianManifest(
    val url: String,
    val sha256: String,
    val size: Long,
    val format: String,
    val version: String,
    val suite: String,
    val variant: String,
    val sourceCommit: String,
)

/**
 * Downloads, verifies, extracts, configures and smoke-tests a real Debian rootfs.
 * Success is reported only after a real shell has run inside it through PRoot.
 */
object DebianInstaller {
    private const val MIN_FREE_BYTES = 500L * 1024 * 1024
    private const val BUFFER = 64 * 1024

    fun readManifest(context: Context, abi: String): DebianManifest? {
        val m = try {
            context.assets.open("distro/$abi/debian.info").bufferedReader().readLines()
                .mapNotNull { line -> line.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
                .toMap()
        } catch (_: IOException) {
            return null
        }
        return DebianManifest(
            url = m["url"] ?: return null,
            sha256 = m["sha256"] ?: return null,
            size = m["size"]?.toLongOrNull() ?: return null,
            format = m["format"] ?: return null,
            version = m["version"] ?: "unknown",
            suite = m["suite"] ?: "?",
            variant = m["variant"] ?: "?",
            sourceCommit = m["source_commit"] ?: "?",
        )
    }

    fun isInstalled(paths: RuntimePaths): Boolean =
        paths.debianMarker.isFile && File(paths.debianRoot, "usr/bin/dash").isFile

    fun hasPartialDownload(paths: RuntimePaths): Boolean =
        paths.downloadsDir.listFiles()?.any { it.isFile && it.length() > 0 } == true

    /** Cancellable. [progress] receives (phase, fraction 0..1 or null if indeterminate) from a background thread. */
    suspend fun install(
        context: Context,
        paths: RuntimePaths,
        disableSeccomp: Boolean,
        progress: (String, Float?) -> Unit,
    ): Finding = withContext(Dispatchers.IO) {
        val ctx = currentCoroutineContext()
        installBlocking(context, paths, disableSeccomp, progress) { ctx.ensureActive() }
    }

    private fun installBlocking(
        context: Context,
        paths: RuntimePaths,
        disableSeccomp: Boolean,
        progress: (String, Float?) -> Unit,
        checkActive: () -> Unit,
    ): Finding {
        val title = "Install Debian"
        val abi = paths.abi ?: return Finding(title, Verdict.FAIL, "Unsupported ABI: nativeLibraryDir is '${paths.nativeDir.name}'.")
        val m = readManifest(context, abi) ?: return Finding(
            title, Verdict.FAIL,
            "Asset distro/$abi/debian.info is missing. The APK was built without running scripts/resolve-debian.sh.",
        )
        if (!m.url.startsWith("https://")) return Finding(title, Verdict.FAIL, "Refusing non-HTTPS download URL: ${m.url}")
        if (!paths.proot.exists()) return Finding(title, Verdict.FAIL, "PRoot is missing; run the Phase 1 tests first.")

        paths.filesDir.mkdirs()
        val needed = MIN_FREE_BYTES + m.size
        if (paths.filesDir.usableSpace < needed) {
            return Finding(
                title, Verdict.FAIL,
                "Not enough free storage: need about ${needed / 1024 / 1024} MB, have ${paths.filesDir.usableSpace / 1024 / 1024} MB. Free some space and try again.",
            )
        }

        val archive = File(paths.downloadsDir, "debian-$abi.download")
        try {
            // 1. Download (resumable)
            progress("Downloading", 0f)
            download(m, archive, progress, checkActive)

            // 2. Integrity
            progress("Verifying checksum", null)
            val actual = sha256(archive, checkActive)
            if (!actual.equals(m.sha256, ignoreCase = true)) {
                archive.delete()
                return Finding(
                    title, Verdict.FAIL,
                    "SHA-256 mismatch: the download was corrupted or tampered with, and has been deleted.\n  expected: ${m.sha256}\n  actual:   $actual",
                )
            }

            // 3. Extract into staging
            progress("Extracting", 0f)
            TarExtractor.deleteTree(paths.debianStaging)
            paths.debianStaging.mkdirs()
            val stats = extractArchive(archive, m.format, paths.debianStaging, progress, checkActive)
            if (!File(paths.debianStaging, "usr/bin/dash").isFile || !File(paths.debianStaging, "etc/debian_version").exists()) {
                throw IOException("Extracted tree does not look like Debian (usr/bin/dash or etc/debian_version missing).")
            }

            // 4. Configure for PRoot/Android
            progress("Configuring", null)
            configure(context, paths.debianStaging)

            // 5. Swap into place
            paths.debianMarker.delete()
            TarExtractor.deleteTree(paths.debianRoot)
            if (!paths.debianStaging.renameTo(paths.debianRoot)) throw IOException("Could not move staging directory into place.")
            paths.tmp.mkdirs()

            // 6. Real smoke test: only now is the install reported as successful
            progress("Running smoke test", null)
            val smoke = smokeTest(paths, disableSeccomp)
            if (!smoke.ok) {
                TarExtractor.deleteTree(paths.debianRoot)
                return Finding(
                    title, Verdict.FAIL,
                    "Files were installed but a real shell could not run inside Debian, so the install was rolled back " +
                        "(the verified download is kept, so retrying skips the download).\n${smoke.detail}",
                )
            }
            paths.debianMarker.writeText(actual)
            archive.delete()

            return Finding(
                title, Verdict.PASS,
                "ABI: $abi\nsource: ${m.url}\nsource commit: ${m.sourceCommit}\nsha256 (verified): $actual\n" +
                    "extracted: ${stats.files} files, ${stats.dirs} dirs, ${stats.symlinks} symlinks, ${stats.hardlinks} hardlinks (copied), " +
                    "${stats.skipped} special entries skipped\ninstalled at: ${paths.debianRoot}\n${smoke.detail}",
            )
        } catch (e: IOException) {
            // Cancellation is NOT an IOException and propagates; the partial download is kept for resume.
            return Finding(title, Verdict.FAIL, "Install failed: ${e.message}\nTap \"Install Debian\" again to retry; a partial download resumes.")
        } catch (e: ErrnoException) {
            return Finding(title, Verdict.FAIL, "Install failed (errno): ${e.message}")
        } finally {
            TarExtractor.deleteTree(paths.debianStaging)
        }
    }

    // ---------------------------------------------------------------------------------------------

    private fun download(m: DebianManifest, dest: File, progress: (String, Float?) -> Unit, checkActive: () -> Unit) {
        dest.parentFile?.mkdirs()
        var existing = if (dest.exists()) dest.length() else 0L
        if (existing > m.size) {
            dest.delete()
            existing = 0L
        }
        if (existing == m.size) {
            progress("Downloading", 1f)
            return
        }

        val conn = (URL(m.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ASL-PoC/0.2")
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        try {
            val append = when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK -> false
                HttpURLConnection.HTTP_PARTIAL -> true
                else -> throw IOException("Server answered HTTP $code for ${m.url}")
            }
            var total = if (append) existing else 0L
            FileOutputStream(dest, append).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        checkActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        progress("Downloading", (total.toFloat() / m.size).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (dest.length() != m.size) {
            throw IOException("Download incomplete: have ${dest.length()} of ${m.size} bytes (connection dropped?).")
        }
    }

    private fun sha256(file: File, checkActive: () -> Unit): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { s ->
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                checkActive()
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private class CountingInputStream(source: InputStream) : FilterInputStream(source) {
        @Volatile
        var count: Long = 0

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) count++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) count += n
            return n
        }
    }

    private fun extractArchive(
        archive: File,
        format: String,
        dest: File,
        progress: (String, Float?) -> Unit,
        checkActive: () -> Unit,
    ): TarExtractor.Stats {
        val total = archive.length().coerceAtLeast(1)
        val counting = CountingInputStream(BufferedInputStream(FileInputStream(archive), BUFFER))
        return counting.use {
            val decompressed: InputStream = when (format) {
                "gz" -> GzipCompressorInputStream(counting)
                "xz" -> XZCompressorInputStream(counting)
                else -> throw IOException("Unsupported archive format '$format'")
            }
            TarExtractor.extract(decompressed, dest) {
                checkActive()
                progress("Extracting", (counting.count.toFloat() / total).coerceIn(0f, 1f))
            }
        }
    }

    /** Settings the rootfs needs to behave under PRoot on Android. */
    private fun configure(context: Context, root: File) {
        TarExtractor.safeWrite(root, "etc/resolv.conf", dnsServers(context).joinToString("\n") { "nameserver $it" } + "\n")

        val hosts = File(root, "etc/hosts")
        if (Files.isSymbolicLink(hosts.toPath()) || !hosts.exists() || hosts.length() == 0L) {
            TarExtractor.safeWrite(root, "etc/hosts", "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
        }

        // PRoot cannot drop to the unprivileged _apt user (setgroups fails); run the apt download sandbox as root.
        TarExtractor.safeWrite(
            root, "etc/apt/apt.conf.d/99asl-proot",
            "// ASL: PRoot cannot switch to the _apt user\nAPT::Sandbox::User \"root\";\n",
        )
    }

    private fun dnsServers(context: Context): List<String> {
        val found = try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm?.getLinkProperties(cm.activeNetwork)?.dnsServers
                ?.mapNotNull { it.hostAddress }
                ?.filter { '%' !in it }
                .orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }
        // Fallback only if the device does not expose its DNS servers to apps.
        return found.ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
    }

    // ---------------------------------------------------------------------------------------------

    private data class Smoke(val ok: Boolean, val detail: String)

    private fun smokeTest(paths: RuntimePaths, disableSeccomp: Boolean): Smoke {
        val script = """
            echo "debian_version: $(cat /etc/debian_version)"
            echo "dpkg arch  : $(dpkg --print-architecture)"
            echo "uname -m   : $(uname -m)"
            echo "id         : $(id)"
            echo "pwd        : $(pwd)"
            grep PRETTY_NAME /etc/os-release
        """.trimIndent()
        paths.tmp.mkdirs()
        val r = ProcessRunner.run(Proot.guestShell(paths, script, paths.debianRoot), Proot.env(paths, disableSeccomp), paths.filesDir)
        val version = Regex("""debian_version: (\S+)""").find(r.stdout)?.groupValues?.get(1)
        val dpkgArch = Regex("""dpkg arch  : (\S+)""").find(r.stdout)?.groupValues?.get(1)
        val expectedArch = if (paths.abi == "arm64-v8a") "arm64" else "armhf"
        val ok = r.succeeded && version != null && dpkgArch == expectedArch
        val summary = if (ok) "Real Debian shell answered (Debian $version, dpkg arch $dpkgArch)." else
            "Smoke test failed (expected dpkg arch '$expectedArch', got '${dpkgArch ?: "none"}'). If stderr mentions ptrace/seccomp, retry with the seccomp switch toggled."
        return Smoke(ok, summary + "\n" + r.describe())
    }

    /** Runs real commands in the installed Debian. */
    fun probe(paths: RuntimePaths, disableSeccomp: Boolean): Finding {
        val title = "Debian: real commands inside PRoot"
        if (!isInstalled(paths)) return Finding(title, Verdict.FAIL, "Debian is not installed.")
        val s = smokeTest(paths, disableSeccomp)
        return Finding(title, if (s.ok) Verdict.PASS else Verdict.FAIL, s.detail)
    }

    /** Real `apt-get update`: shows the actual outcome, including runtime limitation errors. */
    fun aptUpdate(paths: RuntimePaths, disableSeccomp: Boolean): Finding {
        val title = "Debian: apt-get update"
        if (!isInstalled(paths)) return Finding(title, Verdict.FAIL, "Debian is not installed.")
        paths.tmp.mkdirs()
        val script = """
            apt-get update > /tmp/apt.log 2>&1 && echo "RESULT: OK" || echo "RESULT: FAILED"
            tail -n 25 /tmp/apt.log
        """.trimIndent()
        val r = ProcessRunner.run(
            Proot.guestShell(paths, script, paths.debianRoot), Proot.env(paths, disableSeccomp), paths.filesDir,
            timeoutSeconds = 180,
        )
        val ok = r.succeeded && r.stdout.contains("RESULT: OK")
        return Finding(title, if (ok) Verdict.PASS else Verdict.FAIL, r.describe())
    }

    /** Deletes Debian and every temporary/partial file belonging to it. */
    fun remove(paths: RuntimePaths) {
        paths.debianMarker.delete()
        TarExtractor.deleteTree(paths.debianRoot)
        TarExtractor.deleteTree(paths.debianStaging)
        TarExtractor.deleteTree(paths.downloadsDir)
    }
}
