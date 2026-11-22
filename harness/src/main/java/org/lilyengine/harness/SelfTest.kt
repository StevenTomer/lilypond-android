package org.lilyengine.harness

import android.os.Build
import org.lilyengine.engine.Diagnostic
import org.lilyengine.engine.LilyPondEngine

/**
 * On-device checks that need no adb: the same things the instrumented tests
 * check, plus a few more, with a plain-text report that can be shared.
 */
class SelfTest(private val engine: LilyPondEngine) {
    data class Check(val name: String, val passed: Boolean, val millis: Long, val detail: String)

    suspend fun run(onProgress: (List<Check>) -> Unit): List<Check> {
        val results = mutableListOf<Check>()
        suspend fun check(name: String, body: suspend () -> String) {
            val start = System.nanoTime()
            val outcome = runCatching { body() }
            val ms = (System.nanoTime() - start) / 1_000_000
            results += Check(
                name,
                outcome.isSuccess,
                ms,
                outcome.getOrElse { it.message ?: it.toString() },
            )
            onProgress(results.toList())
        }

        check("Unpack runtime") { engine.prepare().path }
        check("lilypond --version") {
            val v = engine.version()
            require(v.contains("GNU LilyPond 2.")) { v }
            v.lineSequence().first()
        }
        check("Render PDF (uses Ghostscript)") {
            val r = engine.render(SAMPLE, LilyPondEngine.Format.PDF)
            requireOk(r)
            val pdf = r.outputs.single()
            val magic = pdf.inputStream().use { String(ByteArray(4).also { b -> it.read(b) }) }
            require(magic == "%PDF") { "Not a PDF: $magic" }
            "${pdf.length() / 1024} KB, LilyPond ran ${r.elapsedMillis} ms"
        }
        check("Render PDF again (warm)") {
            val r = engine.render(SAMPLE, LilyPondEngine.Format.PDF)
            requireOk(r)
            "${r.elapsedMillis} ms"
        }
        check("Render PNG (uses Ghostscript)") {
            val r = engine.render(SAMPLE, LilyPondEngine.Format.PNG)
            requireOk(r)
            "${r.outputs.size} page(s), ${r.elapsedMillis} ms"
        }
        check("Render SVG") {
            val r = engine.render(SAMPLE, LilyPondEngine.Format.SVG)
            requireOk(r)
            "${r.outputs.size} file(s), ${r.elapsedMillis} ms"
        }
        check("Unicode text (Hungarian, CJK, ♯♭♮)") {
            val r = engine.render(UNICODE_SAMPLE, LilyPondEngine.Format.PDF)
            requireOk(r)
            val warnings = r.diagnostics.count { it.severity == Diagnostic.Severity.WARNING }
            "$warnings warning(s), ${r.elapsedMillis} ms"
        }
        check("Error reported at the right line") {
            val r = engine.render("\\version \"2.26.0\"\n{ c'4 d'4 }}\n")
            val err = r.diagnostics.firstOrNull { it.severity == Diagnostic.Severity.ERROR }
            require(err != null && err.line == 2) { "Expected an error on line 2.\n${r.log}" }
            "line ${err.line}, column ${err.column}: ${err.message}"
        }
        return results
    }

    private fun requireOk(r: LilyPondEngine.Result) {
        require(r.success) { "exit=${r.exitCode}\n${r.log.takeLast(3000)}" }
        require(r.diagnostics.none { it.severity == Diagnostic.Severity.ERROR }) { r.log.takeLast(3000) }
    }

    companion object {
        fun deviceInfo(): String = buildString {
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        }

        fun report(checks: List<Check>): String = buildString {
            appendLine("LilyPond engine self-test")
            append(deviceInfo())
            val passed = checks.count { it.passed }
            appendLine("Result: $passed of ${checks.size} passed")
            appendLine()
            for (c in checks) {
                appendLine("${if (c.passed) "PASS" else "FAIL"}  ${c.name}  (${c.millis} ms)")
                c.detail.lineSequence().forEach { appendLine("      $it") }
            }
        }

        private val UNICODE_SAMPLE = """
            \version "2.26.0"
            \header { title = "Árvíztűrő tükörfúrógép" subtitle = "日本語の歌詞 ♯ ♭ ♮" }
            \relative c' { c4 d e f | g1 }
            \addlyrics { Ő-szi szél 歌 }
        """.trimIndent()
    }
}
