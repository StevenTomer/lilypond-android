package org.lilyengine.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 * library and bytecode) ship as an asset archive that [prepare] unpacks once
 * per runtime version.
 *
 * Renders are cancellable: cancelling the calling coroutine kills the
 * LilyPond process, so an editor can drop a render as soon as a newer edit
 * arrives.
 */
class LilyPondEngine(context: Context) {
    private val appContext = context.applicationContext
    private val nativeDir = File(appContext.applicationInfo.nativeLibraryDir)
    private val runtime = RuntimeInstaller(appContext)
    private val mutex = Mutex()
    private val jobsDir = File(appContext.cacheDir, "lilypond-jobs")
    private val activeJobs = mutableSetOf<File>()

    enum class Format(val flag: String, val extension: String) {
        PDF("--pdf", "pdf"),
        PNG("--png", "png"),
        SVG("-dbackend=svg", "svg"),
    }

    data class Options(
        val format: Format = Format.PDF,
        val resolutionDpi: Int = 150,
        /** Write MIDI for every score, even ones without a \midi block. */
        val midi: Boolean = true,
        /** Write a [ScoreMap] (PDF output only). */
        val scoreMap: Boolean = true,
        /** Extra directories searched by \include. */
        val includeDirs: List<File> = emptyList(),
        val timeoutSeconds: Long = 300,
    )

    data class Result(
        val exitCode: Int,
        /** Output files (one PNG per page, or one PDF/SVG per book), in page order. */
        val outputs: List<File>,
        /** MIDI files, one per score with MIDI output. */
        val midi: List<File>,
        /** The score map, when requested and the render produced one. */
        val scoreMapFile: File?,
        val log: String,
        val diagnostics: List<Diagnostic>,
        val elapsedMillis: Long,
    ) {
        val success: Boolean get() = exitCode == 0 && outputs.isNotEmpty()
        val scoreMap: ScoreMap? by lazy { scoreMapFile?.let(ScoreMap::read) }
    }

    /** Unpacks the runtime if needed. Safe to call repeatedly; slow only once. */
    suspend fun prepare(): File = withContext(Dispatchers.IO) {
        mutex.withLock { runtime.ensureInstalled() }
    }

    /** `lilypond --version`, useful as a health check. */
    suspend fun version(): String = withContext(Dispatchers.IO) {
        val root = prepare()
        val work = File(appContext.cacheDir, "lilypond-version").apply { mkdirs() }
        run(root, work, listOf("--version"), emptyMap(), timeoutSeconds = 60).second.trim()
    }

    /** Typesets [source] (LilyPond input) in a fresh job directory. */
    suspend fun render(source: String, options: Options = Options()): Result =
        withContext(Dispatchers.IO) {
            withJob { job ->
                val input = File(job, "score.ly").apply { writeText(source) }
                renderIn(job, input, options)
            }
        }

    /** Convenience overload kept for simple callers. */
    suspend fun render(
        source: String,
        format: Format,
        resolutionDpi: Int = 150,
        timeoutSeconds: Long = 300,
    ): Result = render(source, Options(format, resolutionDpi, timeoutSeconds = timeoutSeconds))

    /**
     * Typesets a file in place, so relative \include statements work (its
     * directory is searched first). Output goes to a job directory in the
     * cache, not next to the file.
     */
    suspend fun renderFile(input: File, options: Options = Options()): Result =
        withContext(Dispatchers.IO) {
            withJob { job ->
                renderIn(job, input, options.copy(includeDirs = listOf(input.absoluteFile.parentFile!!) + options.includeDirs))
            }
        }

    private suspend fun <T> withJob(block: suspend (File) -> T): T {
        val job = synchronized(activeJobs) {
            // Old job directories are removed here, except ones still in use.
            jobsDir.listFiles()?.filter { it !in activeJobs }?.forEach { it.deleteRecursively() }
            File(jobsDir, "${System.nanoTime()}").apply { mkdirs() }.also { activeJobs += it }
        }
        try {
            return block(job)
        } finally {
            synchronized(activeJobs) { activeJobs -= job }
        }
    }

    private suspend fun renderIn(job: File, input: File, options: Options): Result {
        val root = prepare()
        val base = File(job, input.nameWithoutExtension)
        val mapFile = File(job, "${input.nameWithoutExtension}.scoremap.json")
        val env = mutableMapOf<String, String>()
        val args = buildList {
            add(options.format.flag)
            if (options.format == Format.PNG) add("-dresolution=${options.resolutionDpi}")
            if (options.midi) add("-dinclude-settings=${runtime.settingsFile("midi.ly").path}")
            if (options.scoreMap && options.format == Format.PDF) {
                add("-dinclude-settings=${runtime.settingsFile("scoremap.ly").path}")
                env["LILYENGINE_SCOREMAP"] = mapFile.path
            }
            for (dir in options.includeDirs) { add("-I"); add(dir.path) }
            add("-o"); add(base.path)
            add(input.path)
        }
        val start = System.nanoTime()
        val (exit, log) = run(root, job, args, env, options.timeoutSeconds)
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

        val byPageOrder = compareBy<File>({ it.name.length }, { it.name })
        val outputs = job.listFiles { f -> f.extension == options.format.extension }
            ?.sortedWith(byPageOrder).orEmpty()
        val midi = job.listFiles { f -> f.extension == "midi" || f.extension == "mid" }
            ?.sortedWith(byPageOrder).orEmpty()
        return Result(
            exitCode = exit,
            outputs = outputs,
            midi = midi,
            scoreMapFile = mapFile.takeIf { it.isFile && it.length() > 0 },
            log = log,
            diagnostics = Diagnostic.parse(log, input.path),
            elapsedMillis = elapsed,
        )
    }

    private suspend fun run(
        root: File,
        workDir: File,
        args: List<String>,
        extraEnv: Map<String, String>,
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
        pb.environment().putAll(extraEnv)
        val proc = pb.start()
        val out = StringBuilder()
        val reader = Thread {
            try {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { synchronized(out) { out.appendLine(it) } }
                }
            } catch (_: java.io.IOException) {
                // The stream closes under us when a cancelled or timed-out
                // process is destroyed; the log simply ends there.
            }
        }.apply { isDaemon = true; start() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        try {
            while (!proc.waitFor(25, TimeUnit.MILLISECONDS)) {
                currentCoroutineContext().ensureActive() // throws if cancelled
                if (System.nanoTime() > deadline) {
                    proc.destroyForcibly()
                    reader.join(1000)
                    return -1 to synchronized(out) { out.toString() } + "\n[timed out after ${timeoutSeconds}s]"
                }
            }
        } catch (e: Throwable) {
            proc.destroyForcibly() // cancelled: stop LilyPond right away
            throw e
        }
        reader.join()
        return proc.exitValue() to synchronized(out) { out.toString() }
    }
}
