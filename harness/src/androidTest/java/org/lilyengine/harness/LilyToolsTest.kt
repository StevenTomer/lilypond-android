package org.lilyengine.harness

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.lilyengine.engine.LilyPondEngine
import org.lilyengine.lilytools.LilyTools

/** Structural edits through the embedded Python, checked against LilyPond's own reading. */
@RunWith(AndroidJUnit4::class)
class LilyToolsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val tools = LilyTools(context)
    private val engine = LilyPondEngine(context)

    /** Absolute pitches of all note heads, by voice, as LilyPond engraved them. */
    private suspend fun heads(source: String): Map<String?, List<String>> {
        val r = engine.render(source)
        assertTrue(r.log, r.success)
        return r.scoreMap!!.grobs.filter { it.type == "NoteHead" }
            .groupBy { it.voiceId }
            .mapValues { (_, g) -> g.sortedWith(compareBy({ it.momentValue }, { it.pitch!!.octave * 7 + it.pitch!!.notename })).map { it.pitch!!.name } }
    }

    @Test
    fun pitchEditInVoiceTwoLeavesEverythingElse() = runBlocking {
        val before = heads(POLY_SAMPLE)
        val e = tools.setPitch(POLY_SAMPLE, POLY_SAMPLE.indexOf("c,2"), LilyTools.Pitch(5, 0, 0)) // c' -> a'
        val after = heads(e.applyTo(POLY_SAMPLE))
        assertEquals(before["1"], after["1"])
        assertEquals(listOf("a'", "g'"), after["2"])
        assertEquals(before[""], after[""]) // the chord and the notes after the << >>
    }

    @Test
    fun durationAndDelete() = runBlocking {
        val src = "\\version \"2.26.0\"\n\\relative { c''4 d e f | g1 }\n"
        val d = tools.setDuration(src, src.indexOf("d e"), "8").applyTo(src)
        assertEquals("\\version \"2.26.0\"\n\\relative { c''4 d8 e4 f | g1 }\n", d)
        val x = tools.delete(src, src.indexOf("e f")).applyTo(src)
        assertEquals(listOf("c''", "d''", "f''", "g''"), heads(x).values.flatten())
    }

    @Test
    fun stepFollowsTheKey() = runBlocking {
        val src = "\\version \"2.26.0\"\n\\relative { \\key g \\major e''4 d c b }\n"
        val out = tools.step(src, src.indexOf("e''"), 1).applyTo(src)
        assertEquals(listOf("fis''", "d''", "c''", "b'"), heads(out).values.flatten())
    }

    @Test
    fun errorsComeBackAsExceptions() = runBlocking {
        try {
            tools.setPitch("{ c'4 }", 0, LilyTools.Pitch(0, 0, 0))
            error("expected an EditException")
        } catch (e: LilyTools.EditException) {
            assertTrue(e.message!!.contains("no note"))
        }
    }
}
