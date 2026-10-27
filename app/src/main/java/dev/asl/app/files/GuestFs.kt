package dev.asl.app.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import dev.asl.app.runtime.TarExtractor
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

data class FileEntry(
    val name: String,
    val guestPath: String,
    val isDir: Boolean,
    val isSymlink: Boolean,
    val linkTarget: String?,
    val size: Long,
    val modifiedMs: Long,
    val mode: Int,
)

/**
 * File operations on a distro's private filesystem. Every path is a GUEST path ("/etc/hostname") and is
 * mapped through [GuestPaths], so guest symlinks can never lead to Android files outside the distro.
 * Errors are reported as IOException with a readable message.
 */
object GuestFs {
    const val MAX_TEXT_BYTES = 256 * 1024
    private const val MODE_DIR = 493 // 0755
    private const val MODE_FILE = 420 // 0644

    fun list(root: File, guestDir: String): List<FileEntry> {
        val dir = GuestPaths.resolve(root, guestDir, true)
        if (!dir.isDirectory) throw IOException("Not a directory: $guestDir")
        val names = dir.list() ?: throw IOException("Cannot read this directory (permission denied?)")
        return names
            .map { entry(root, GuestPaths.child(guestDir, it), File(dir, it)) }
            .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    private fun entry(root: File, guestPath: String, host: File): FileEntry {
        val st = try {
            Os.lstat(host.path)
        } catch (_: ErrnoException) {
            null
        }
        val mode = st?.st_mode ?: 0
        val isLink = st != null && OsConstants.S_ISLNK(mode)
        var isDir = st != null && OsConstants.S_ISDIR(mode)
        var target: String? = null
        if (isLink) {
            target = try {
                Os.readlink(host.path)
            } catch (_: ErrnoException) {
                null
            }
            isDir = try {
                GuestPaths.resolve(root, guestPath, true).isDirectory
            } catch (_: IOException) {
                false
            }
        }
        return FileEntry(host.name, guestPath, isDir, isLink, target, st?.st_size ?: 0L, (st?.st_mtime ?: 0L) * 1000, mode)
    }

    /** "drwxr-xr-x"-style string. */
    fun modeString(e: FileEntry): String {
        val m = e.mode
        val type = when {
            e.isSymlink -> 'l'
            e.isDir -> 'd'
            else -> '-'
        }
        val bits = "rwxrwxrwx"
        val sb = StringBuilder().append(type)
        for (i in 0 until 9) sb.append(if (m and (1 shl (8 - i)) != 0) bits[i] else '-')
        return sb.toString()
    }

    /** Reads a small text file; refuses large or binary files. */
    fun readText(root: File, guestPath: String): String {
        val f = GuestPaths.resolve(root, guestPath, true)
        if (!f.isFile) throw IOException("Not a regular file")
        if (f.length() > MAX_TEXT_BYTES) throw IOException("File is larger than ${MAX_TEXT_BYTES / 1024} KB; export it instead.")
        val bytes = f.readBytes()
        if (bytes.take(4096).any { it.toInt() == 0 }) throw IOException("Binary file; export it instead.")
        return String(bytes, Charsets.UTF_8)
    }

    fun mkdir(root: File, guestDir: String, name: String) {
        if (!GuestPaths.isSafeName(name)) throw IOException("Invalid folder name")
        val dir = GuestPaths.resolve(root, guestDir, true)
        val target = File(dir, name)
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) throw IOException("\"$name\" already exists")
        if (!target.mkdir()) throw IOException("Could not create the folder")
        Os.chmod(target.path, MODE_DIR)
    }

    /** Deletes a file, link or folder (recursively, never following symlinks). The distro root itself is protected. */
    fun delete(root: File, guestPath: String) {
        if (GuestPaths.normalize(guestPath) == "/") throw IOException("Refusing to delete the root of the distribution")
        val host = GuestPaths.resolve(root, guestPath, false)
        TarExtractor.deleteTree(host)
    }

    fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    /** Copies a user-selected Android file INTO the distro (never overwrites). */
    fun importFrom(context: Context, uri: Uri, root: File, guestDir: String, name: String) {
        if (!GuestPaths.isSafeName(name)) throw IOException("Invalid file name \"$name\"")
        val dir = GuestPaths.resolve(root, guestDir, true)
        val target = File(dir, name)
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) throw IOException("\"$name\" already exists in this folder")
        val tmp = File(dir, ".$name.asl-import")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the selected file")
            input.use { src -> FileOutputStream(tmp).use { src.copyTo(it) } }
            if (!tmp.renameTo(target)) throw IOException("Could not place the imported file")
            Os.chmod(target.path, MODE_FILE)
        } finally {
            tmp.delete()
        }
    }

    /** Copies a distro file OUT to a location the user chose with the system file picker. */
    fun exportTo(context: Context, root: File, guestPath: String, uri: Uri) {
        val f = GuestPaths.resolve(root, guestPath, true)
        if (!f.isFile) throw IOException("Only regular files can be exported")
        val out = context.contentResolver.openOutputStream(uri, "w") ?: throw IOException("Cannot write to the chosen location")
        out.use { dst -> f.inputStream().use { it.copyTo(dst) } }
    }

    /** Total size of regular files below [dir], not following symlinks. 0 if it does not exist. */
    fun treeSize(dir: File): Long {
        val root = dir.toPath()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return 0L
        var total = 0L
        Files.walkFileTree(root, object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                if (attrs.isRegularFile) total += attrs.size()
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: java.nio.file.Path, exc: IOException): java.nio.file.FileVisitResult =
                java.nio.file.FileVisitResult.CONTINUE
        })
        return total
    }
}
