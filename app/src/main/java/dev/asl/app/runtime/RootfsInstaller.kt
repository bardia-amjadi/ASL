package dev.asl.app.runtime

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/**
 * Installs the bundled Alpine minirootfs (a deliberately tiny PoC guest; Debian comes in Phase 2).
 * Extraction is hardened: no absolute paths, no "..", no writing through symlinks that leave the root.
 */
object RootfsInstaller {
    private const val OWNER_RWX = 448 // 0700
    private const val OWNER_RW = 384 // 0600
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
            "Unsupported ABI: nativeLibraryDir is '${paths.nativeDir.name}'. Phase 1 ships arm64-v8a and armeabi-v7a only.",
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
            // 1. Integrity: hash the bundled archive and compare to the checksum CI verified against Alpine's metadata.
            val actualSha = sha256(context, assetPath)
            if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                return Finding(title, Verdict.FAIL, "SHA-256 mismatch.\n  expected: $expectedSha\n  actual:   $actualSha")
            }

            // 2. Extract into a staging directory, then swap in.
            deleteTree(paths.rootfsStaging)
            paths.rootfsStaging.mkdirs()
            val stats = context.assets.open(assetPath).use { extract(it, paths.rootfsStaging) }

            if (!File(paths.rootfsStaging, "bin/busybox").isFile || !isSymlinkOrFile(File(paths.rootfsStaging, "bin/sh"))) {
                throw IOException("Extracted tree looks wrong: bin/busybox or bin/sh missing.")
            }

            paths.installedMarker.delete()
            deleteTree(paths.rootfs)
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
            deleteTree(paths.rootfsStaging)
            val listing = context.assets.list("rootfs/$abi")?.joinToString() ?: "(none)"
            Finding(title, Verdict.FAIL, "Bundled archive missing: ${e.message}\nassets in rootfs/$abi: $listing")
        } catch (e: IOException) {
            deleteTree(paths.rootfsStaging)
            Finding(title, Verdict.FAIL, "Install failed: ${e.message}")
        } catch (e: ErrnoException) {
            deleteTree(paths.rootfsStaging)
            Finding(title, Verdict.FAIL, "Install failed (errno): ${e.message}")
        }
    }

    fun remove(paths: RuntimePaths) {
        paths.installedMarker.delete()
        deleteTree(paths.rootfs)
        deleteTree(paths.rootfsStaging)
        deleteTree(paths.execTestDir)
        deleteTree(paths.tmp)
    }

    private data class Stats(var files: Int = 0, var dirs: Int = 0, var symlinks: Int = 0, var hardlinks: Int = 0, var skipped: Int = 0)

    private fun extract(input: java.io.InputStream, root: File): Stats {
        val stats = Stats()
        val rootCanon = root.canonicalPath
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(input))).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                val rel = sanitize(entry.name) ?: continue
                val target = File(root, rel)
                val parent = target.parentFile ?: throw IOException("Bad entry: ${entry.name}")
                parent.mkdirs()
                val parentCanon = parent.canonicalPath
                if (parentCanon != rootCanon && !parentCanon.startsWith("$rootCanon/")) {
                    throw IOException("Entry would be written outside the install root: ${entry.name}")
                }
                when {
                    entry.isDirectory -> {
                        target.mkdirs()
                        Os.chmod(target.path, (entry.mode and 0xFFF) or OWNER_RWX)
                        stats.dirs++
                    }
                    entry.isSymbolicLink -> {
                        removeQuietly(target)
                        Os.symlink(entry.linkName, target.path)
                        stats.symlinks++
                    }
                    entry.isLink -> {
                        // Hard links are not usable inside app storage; materialise as a copy.
                        val srcRel = sanitize(entry.linkName) ?: throw IOException("Bad link target: ${entry.linkName}")
                        val src = File(root, srcRel)
                        if (!src.isFile) throw IOException("Hard link source missing: ${entry.linkName}")
                        removeQuietly(target)
                        src.copyTo(target, overwrite = true)
                        Os.chmod(target.path, (entry.mode and 0xFFF) or OWNER_RW)
                        stats.hardlinks++
                    }
                    entry.isFile -> {
                        removeQuietly(target)
                        FileOutputStream(target).use { tar.copyTo(it) }
                        Os.chmod(target.path, (entry.mode and 0xFFF) or OWNER_RW)
                        stats.files++
                    }
                    else -> stats.skipped++ // device nodes, FIFOs: cannot be created unprivileged; /dev is bound from the host
                }
            }
        }
        return stats
    }

    /** Returns a clean relative path, null for the archive root itself, throws for anything unsafe. */
    private fun sanitize(raw: String): String? {
        var n = raw.replace('\\', '/')
        while (n.startsWith("./")) n = n.removePrefix("./")
        if (n.startsWith("/")) throw IOException("Absolute path in archive: $raw")
        n = n.trimEnd('/')
        if (n.isEmpty() || n == ".") return null
        if (n.split('/').any { it == ".." }) throw IOException("Path traversal in archive: $raw")
        return n
    }

    private fun removeQuietly(f: File) {
        try {
            Os.remove(f.path)
        } catch (_: ErrnoException) {
            // did not exist
        }
    }

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

    /** Deletes a tree WITHOUT following symlinks (guest symlinks may point at real host directories). */
    fun deleteTree(dir: File) {
        val root: Path = dir.toPath()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
