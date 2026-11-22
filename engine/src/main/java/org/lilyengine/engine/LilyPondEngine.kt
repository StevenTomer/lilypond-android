package org.lilyengine.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs the native LilyPond engine bundled with the app.
 *
 * LilyPond runs as a child process: it keeps global state and calls exit(),
 * and Guile's garbage collector owns the whole process, so in-process use via
 * JNI is not an option. The executable ships as `liblilypond.so` in
 * nativeLibraryDir; its data files (Scheme, .ly init files, fonts, Guile's
 * library and bytecode) ship as an asset zip that [prepare] unpacks once per
 * runtime version.
 */
class LilyPondEngine(context: Context) {
    private val appContext = context.applicationContext
    private val nativeDir = File(appContext.applicationInfo.nativeLibraryDir)
    private val runtime = RuntimeInstaller(appContext)
    private val mutex = Mutex()

    enum class Format(val flag: String, val extension: String) {
        PDF("--pdf", "pdf"),
        PNG("--png", "png"),
        SVG("-dbackend=svg", "svg"),
    }

    data class Result(
        val exitCode: Int,
        /** Output files (one PNG per page, or one PDF/SVG), in page order. */
        val outputs: List<File>,
        val log: String,
        val diagnostics: List<Diagnostic>,
        val elapsedMillis: Long,
    ) {
        val success: Boolean get() = exitCode == 0 && outputs.isNotEmpty()
    }

    /** Unpacks the runtime if needed. Safe to call repeatedly; slow only once. */
    suspend fun prepare(): File = withContext(Dispatchers.IO) {
        mutex.withLock { runtime.ensureInstalled() }
    }

    /** `lilypond --version`, useful as a health check. */
    suspend fun version(): String = withContext(Dispatchers.IO) {
        val root = prepare()
        val work = File(appContext.cacheDir, "lilypond-version").apply { mkdirs() }
        run(root, work, listOf("--version"), timeoutSeconds = 60).second.trim()
    }

    /**
     * Typesets [source] (LilyPond input) and returns the produced files.
     * Each call uses a fresh job directory under the cache dir.
     */
    suspend fun render(
        source: String,
        format: Format = Format.PDF,
        resolutionDpi: Int = 150,
        timeoutSeconds: Long = 300,
    ): Result = withContext(Dispatchers.IO) {
        val root = prepare()
        val jobs = File(appContext.cacheDir, "lilypond-jobs")
        jobs.listFiles()?.forEach { it.deleteRecursively() }
        val job = File(jobs, System.currentTimeMillis().toString()).apply { mkdirs() }
        val input = File(job, "score.ly").apply { writeText(source) }

        val args = buildList {
            add(format.flag)
            if (format == Format.PNG) add("-dresolution=$resolutionDpi")
            add("-o"); add(File(job, "score").path)
            add(input.path)
        }
        val start = System.nanoTime()
        val (exit, log) = run(root, job, args, timeoutSeconds)
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

        val outputs = job.listFiles { f -> f.extension == format.extension }
            ?.sortedWith(compareBy({ it.name.length }, { it.name }))
            .orEmpty()
        Result(exit, outputs, log, Diagnostic.parse(log, input.path), elapsed)
    }

    private fun run(
        root: File,
        workDir: File,
        args: List<String>,
        timeoutSeconds: Long,
    ): Pair<Int, String> {
        val native = File(nativeDir, "liblilypond.so")
        check(native.canExecute()) { "LilyPond executable missing: $native" }
        // Run through the bin/lilypond symlink: LilyPond derives its data
        // prefix from argv[0].
        val exe = runtime.executable(root)
        val pb = ProcessBuilder(listOf(exe.path) + args)
            .directory(workDir)
            .redirectErrorStream(true)
        pb.environment().putAll(runtime.environment(root, nativeDir))
        val proc = pb.start()
        val out = StringBuilder()
        val reader = Thread {
            proc.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { synchronized(out) { out.appendLine(it) } }
            }
        }.apply { start() }
        if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            reader.join(1000)
            return -1 to synchronized(out) { out.toString() } + "\n[timed out after ${timeoutSeconds}s]"
        }
        reader.join()
        return proc.exitValue() to synchronized(out) { out.toString() }
    }
}
