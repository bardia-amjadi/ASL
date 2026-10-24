package dev.asl.app.runtime

import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class RunResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val millis: Long,
    /** Non-null if the process could not even be started (e.g. "error=13, Permission denied"). */
    val startError: String?,
    val timedOut: Boolean,
) {
    val succeeded: Boolean get() = startError == null && !timedOut && exitCode == 0

    fun describe(): String = buildString {
        if (startError != null) {
            appendLine("could not start process: $startError")
            return@buildString
        }
        appendLine("exit code: $exitCode${if (timedOut) " (TIMED OUT, killed)" else ""}  [${millis} ms]")
        if (stdout.isNotBlank()) {
            appendLine("stdout:")
            appendLine(stdout.trimEnd().prependIndent("  "))
        }
        if (stderr.isNotBlank()) {
            appendLine("stderr:")
            appendLine(stderr.trimEnd().prependIndent("  "))
        }
    }.trimEnd()
}

/** Blocking process runner. Call from a background dispatcher only. */
object ProcessRunner {
    private const val MAX_CAPTURE = 64 * 1024

    fun run(
        command: List<String>,
        env: Map<String, String>,
        workDir: File?,
        timeoutSeconds: Long = 30,
    ): RunResult {
        val started = SystemClock.elapsedRealtime()
        val builder = ProcessBuilder(command)
        builder.environment().apply {
            clear()
            putAll(env)
        }
        if (workDir != null) builder.directory(workDir)

        val process = try {
            builder.start()
        } catch (e: IOException) {
            return RunResult(
                exitCode = null, stdout = "", stderr = "",
                millis = SystemClock.elapsedRealtime() - started,
                startError = e.message ?: e.toString(), timedOut = false,
            )
        }
        runCatching { process.outputStream.close() }

        val out = StringBuffer()
        val err = StringBuffer()
        val outThread = pump(process.inputStream, out)
        val errThread = pump(process.errorStream, err)

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }
        outThread.join(2000)
        errThread.join(2000)

        return RunResult(
            exitCode = if (finished) process.exitValue() else null,
            stdout = out.toString(),
            stderr = err.toString(),
            millis = SystemClock.elapsedRealtime() - started,
            startError = null,
            timedOut = !finished,
        )
    }

    private fun pump(stream: InputStream, sink: StringBuffer): Thread = thread(isDaemon = true) {
        try {
            val reader = stream.bufferedReader()
            val buf = CharArray(4096)
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                // Keep draining after the cap so the child never blocks on a full pipe.
                if (sink.length < MAX_CAPTURE) sink.append(buf, 0, n)
            }
        } catch (_: IOException) {
            // stream closed because the process was killed
        }
    }
}
