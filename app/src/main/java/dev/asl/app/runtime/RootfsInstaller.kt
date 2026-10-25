package dev.asl.app.runtime

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest

/** Installs the bundled Alpine minirootfs (the Phase 1 PoC guest). Debian is handled by [DebianInstaller]. */
object RootfsInstaller {
    private const val MIN_FREE_BYTES = 100L * 1024 * 1024

    fun isInstalled(paths: RuntimePaths): Boolean =
        paths.installedMarker.isFile && File(paths.rootfs, "bin/busybox").isFile

    fun readInfo(context: Context, abi: String): Map<String, String>? = try {
        context.assets.open("rootfs/$abi/rootfs.info").bufferedReader().readLines()
            .mapNotNull { line -> line.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
            .toMap()
    } catch (_: IOException) {
        null
    }

    fun readRuntimeInfo(context: Context, abi: String): String? = try {
        context.assets.open("rootfs/$abi/runtime.info").bufferedReader().readText().trim()
    } catch (_: IOException) {
        null
    }

    fun install(context: Context, paths: RuntimePaths): Finding {
        val title = "Install test rootfs (Alpine minirootfs)"
        val abi = paths.abi ?: return Finding(
            title, Verdict.FAIL,
            "Unsupported ABI: nativeLibraryDir is '${paths.nativeDir.name}'. Only arm64-v8a and armeabi-v7a are shipped.",
        )
        val info = readInfo(context, abi) ?: return Finding(
            title, Verdict.FAIL,
            "Asset rootfs/$abi/rootfs.info is missing. The APK was built without running scripts/fetch-runtime-assets.sh.",
        )
        val expectedSha = info["sha256"] ?: return Finding(title, Verdict.FAIL, "rootfs.info has no sha256 entry.")
        val assetPath = "rootfs/$abi/alpine-minirootfs.bin"

        paths.filesDir.mkdirs()
        if (paths.filesDir.usableSpace < MIN_FREE_BYTES) {
            return Finding(title, Verdict.FAIL, "Not enough free storage (need >= 100 MB, have ${paths.filesDir.usableSpace / 1024 / 1024} MB).")
        }

        return try {
            val actualSha = sha256(context, assetPath)
            if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                return Finding(title, Verdict.FAIL, "SHA-256 mismatch.\n  expected: $expectedSha\n  actual:   $actualSha")
            }

            TarExtractor.deleteTree(paths.rootfsStaging)
            paths.rootfsStaging.mkdirs()
            val stats = context.assets.open(assetPath).use {
                TarExtractor.extract(GzipCompressorInputStream(BufferedInputStream(it)), paths.rootfsStaging)
            }

            if (!File(paths.rootfsStaging, "bin/busybox").isFile || !isSymlinkOrFile(File(paths.rootfsStaging, "bin/sh"))) {
                throw IOException("Extracted tree looks wrong: bin/busybox or bin/sh missing.")
            }

            paths.installedMarker.delete()
            TarExtractor.deleteTree(paths.rootfs)
            if (!paths.rootfsStaging.renameTo(paths.rootfs)) throw IOException("Could not move staging directory into place.")
            paths.installedMarker.writeText(actualSha)
            paths.tmp.mkdirs()

            Finding(
                title, Verdict.PASS,
                "ABI: $abi\nsource: ${info["source_url"] ?: "?"}\nversion: ${info["version"] ?: "?"}\n" +
                    "sha256 (verified): $actualSha\nextracted: ${stats.files} files, ${stats.dirs} dirs, ${stats.symlinks} symlinks, " +
                    "${stats.hardlinks} hardlinks (copied), ${stats.skipped} special entries skipped\ninstalled at: ${paths.rootfs}",
            )
        } catch (e: FileNotFoundException) {
            TarExtractor.deleteTree(paths.rootfsStaging)
            val listing = context.assets.list("rootfs/$abi")?.joinToString() ?: "(none)"
            Finding(title, Verdict.FAIL, "Bundled archive missing: ${e.message}\nassets in rootfs/$abi: $listing")
        } catch (e: IOException) {
            TarExtractor.deleteTree(paths.rootfsStaging)
            Finding(title, Verdict.FAIL, "Install failed: ${e.message}")
        } catch (e: ErrnoException) {
            TarExtractor.deleteTree(paths.rootfsStaging)
            Finding(title, Verdict.FAIL, "Install failed (errno): ${e.message}")
        }
    }

    /** Removes the Alpine PoC rootfs and temporary files. */
    fun remove(paths: RuntimePaths) {
        paths.installedMarker.delete()
        TarExtractor.deleteTree(paths.rootfs)
        TarExtractor.deleteTree(paths.rootfsStaging)
        TarExtractor.deleteTree(paths.execTestDir)
        TarExtractor.deleteTree(paths.tmp)
    }

    fun deleteTree(dir: File) = TarExtractor.deleteTree(dir)

    private fun isSymlinkOrFile(f: File): Boolean = try {
        Os.lstat(f.path)
        true
    } catch (_: ErrnoException) {
        false
    }

    private fun sha256(context: Context, assetPath: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        context.assets.open(assetPath).use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
