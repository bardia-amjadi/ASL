package dev.asl.app.runtime

import android.system.ErrnoException
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Hardened tar extraction into an app-private directory. Shared by every rootfs installer.
 * Rejects absolute paths and "..", refuses to write through symlinks that leave the install root,
 * and never follows symlinks when deleting.
 */
object TarExtractor {
    private const val OWNER_RWX = 448 // 0700
    private const val OWNER_RW = 384 // 0600

    data class Stats(var files: Int = 0, var dirs: Int = 0, var symlinks: Int = 0, var hardlinks: Int = 0, var skipped: Int = 0)

    /**
     * @param tarStream an already DECOMPRESSED tar stream
     * @param onEntry called once per archive entry (use it for progress and cancellation checks)
     */
    fun extract(tarStream: InputStream, root: File, onEntry: () -> Unit = {}): Stats {
        val stats = Stats()
        val rootCanon = root.canonicalPath
        TarArchiveInputStream(tarStream).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                onEntry()
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
    fun sanitize(raw: String): String? {
        var n = raw.replace('\\', '/')
        while (n.startsWith("./")) n = n.removePrefix("./")
        if (n.startsWith("/")) throw IOException("Absolute path in archive: $raw")
        n = n.trimEnd('/')
        if (n.isEmpty() || n == ".") return null
        if (n.split('/').any { it == ".." }) throw IOException("Path traversal in archive: $raw")
        return n
    }

    /** Removes a file or symlink (the link itself, never its target). No-op if absent. */
    fun removeQuietly(f: File) {
        try {
            Os.remove(f.path)
        } catch (_: ErrnoException) {
            // did not exist
        }
    }

    /**
     * Writes [text] to [rel] under [root], replacing any existing file or symlink, after checking
     * that the parent directory really is inside [root] (guest symlinks can point anywhere).
     */
    fun safeWrite(root: File, rel: String, text: String) {
        val target = File(root, rel)
        val parent = target.parentFile ?: throw IOException("Bad path: $rel")
        parent.mkdirs()
        val rootCanon = root.canonicalPath
        val parentCanon = parent.canonicalPath
        if (parentCanon != rootCanon && !parentCanon.startsWith("$rootCanon/")) {
            throw IOException("Refusing to write outside the install root: $rel")
        }
        removeQuietly(target)
        target.writeText(text)
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
