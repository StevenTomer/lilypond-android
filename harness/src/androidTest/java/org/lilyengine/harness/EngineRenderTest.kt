package org.lilyengine.harness

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.lilyengine.engine.Diagnostic
import org.lilyengine.engine.LilyPondEngine

/** Phase-3 check: LilyPond runs inside the app sandbox and produces a PDF. */
@RunWith(AndroidJUnit4::class)
class EngineRenderTest {
    private val engine =
        LilyPondEngine(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun versionRuns() = runBlocking {
        val v = engine.version()
        assertTrue(v, v.contains("GNU LilyPond 2."))
    }

    @Test
    fun rendersSamplePdf() = runBlocking {
        val r = engine.render(SAMPLE, LilyPondEngine.Format.PDF)
        assertEquals(r.log, 0, r.exitCode)
        val pdf = r.outputs.single()
        assertTrue(r.log, pdf.length() > 1000)
        assertEquals("%PDF", pdf.inputStream().use { String(ByteArray(4).also { b -> it.read(b) }) })
        assertTrue(r.log, r.diagnostics.none { it.severity == Diagnostic.Severity.ERROR })
    }

    @Test
    fun rendersPngPages() = runBlocking {
        val r = engine.render(SAMPLE, LilyPondEngine.Format.PNG)
        assertEquals(r.log, 0, r.exitCode)
        assertTrue(r.log, r.outputs.isNotEmpty())
    }

    @Test
    fun reportsSyntaxErrorPosition() = runBlocking {
        val r = engine.render("\\version \"2.26.0\"\n{ c'4 d'4 }}\n")
        val err = r.diagnostics.firstOrNull { it.severity == Diagnostic.Severity.ERROR }
        assertTrue(r.log, err != null && err.line == 2)
    }
}
