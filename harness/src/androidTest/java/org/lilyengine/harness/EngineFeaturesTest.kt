package org.lilyengine.harness

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.lilyengine.engine.LilyPondEngine
import java.io.File

/** Engine v2 features: MIDI, score map, includes, cancellation. */
@RunWith(AndroidJUnit4::class)
class EngineFeaturesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine = LilyPondEngine(context)

    @Test
    fun midiWithoutMidiBlock() = runBlocking {
        val r = engine.render(SAMPLE) // SAMPLE has \layout only
        assertTrue(r.log, r.success)
        val midi = r.midi.single()
        assertEquals("MThd", midi.inputStream().use { String(ByteArray(4).also { b -> it.read(b) }) })
    }

    @Test
    fun windowEngravesOnlyTheBarsAskedFor() = runBlocking {
        val src = pianoPiece(40) // 40 bars, 2 pages
        val full = engine.render(src)
        assertTrue(full.log, full.success)
        val r = engine.render(src, LilyPondEngine.Options(window = "20" to "23"))
        assertTrue(r.log, r.success)
        val moments = r.scoreMap!!.grobs.filter { it.type == "NoteHead" }.mapNotNull { it.momentValue }
        assertTrue(moments.isNotEmpty())
        assertTrue("moments ${moments.min()}..${moments.max()}", moments.all { it >= 20.0 && it < 23.0 })
        assertTrue("window ${r.elapsedMillis} ms vs full ${full.elapsedMillis} ms", r.elapsedMillis < full.elapsedMillis)
        // No warnings or errors from the windowing itself.
        assertTrue(r.log, r.diagnostics.isEmpty())
    }

    @Test
    fun windowOfMidi2lyOutputNeitherCrashesNorWritesMidi() = runBlocking {
        // midi2ly's layout splits long notes at bar lines (Completion_heads_engraver).
        // A window ending at the bar line the g4 crosses used to crash LilyPond
        // (signal 11), as showFirstLength still does; and the score's own \midi
        // block used to make the window write MIDI, which LilyPond's performers
        // choke on.
        val src = """
            \version "2.26.0"
            \layout { \context { \Voice \remove Note_heads_engraver \consists Completion_heads_engraver
                \remove Rest_engraver \consists Completion_rest_engraver } }
            \score {
              \new Staff \new Voice \relative c { b' r8. r8 f cis8. b,4 <c gis > <f b, >8 ais16*5 g4 }
              \layout { }
              \midi { }
            }
        """.trimIndent()
        for ((start, end) in listOf("0" to "2", "1/2" to "2", "0" to "1")) {
            val r = engine.render(src, LilyPondEngine.Options(window = start to end))
            assertEquals("window $start-$end\n${r.log}", 0, r.exitCode)
            assertTrue(r.log, r.success)
            assertTrue("no MIDI for a window", r.midi.isEmpty())
            val moments = r.scoreMap!!.grobs.filter { it.type == "NoteHead" }.mapNotNull { it.momentValue }
            assertTrue("moments $moments", moments.isNotEmpty() && moments.all { it >= start.toFraction() && it < end.toFraction() })
        }
    }

    private fun String.toFraction(): Double = split("/").let { it[0].toDouble() / (it.getOrNull(1)?.toDouble() ?: 1.0) }

    @Test
    fun scoreMapSeparatesVoices() = runBlocking {
        val r = engine.render(POLY_SAMPLE)
        assertTrue(r.log, r.success)
        val map = assertNotNullAndGet(r.scoreMap, r.log)
        val heads = map.grobs.filter { it.type == "NoteHead" }
        assertEquals(11, heads.size)
        val byVoiceId = heads.groupBy { it.voiceId }
        // << { c4 e g e } \\ { c,2 g' } >>: four heads in voice "1", two in "2".
        assertEquals(listOf("c''", "e''", "g''", "e''"), byVoiceId["1"]!!.sortedBy { it.momentValue }.map { it.pitch!!.name })
        assertEquals(listOf("c'", "g'"), byVoiceId["2"]!!.sortedBy { it.momentValue }.map { it.pitch!!.name })
        // Each voice's notes point into its own { } on line 4.
        val line4 = POLY_SAMPLE.lines()[3]
        val split = line4.indexOf("\\\\")
        assertTrue(byVoiceId["1"]!!.all { it.origin!!.line == 4 && it.origin!!.char < split })
        assertTrue(byVoiceId["2"]!!.all { it.origin!!.line == 4 && it.origin!!.char > split })
        // The <g b d> chord: three heads at the same moment, each with its own position.
        val chord = heads.filter { it.momentValue == 1.0 }
        assertEquals(listOf(-2, 0, 2), chord.map { it.staffPosition }.sortedBy { it })
        assertEquals(3, chord.map { it.origin!!.char }.toSet().size)
        assertTrue(map.grobs.any { it.type == "StaffSymbol" && it.linePositions?.size == 5 })
    }

    @Test
    fun renderFileFollowsIncludes() = runBlocking {
        val dir = File(context.cacheDir, "include-test").apply { deleteRecursively(); mkdirs() }
        File(dir, "notes.ily").writeText("melody = { c'4 d' e' f' }\n")
        val main = File(dir, "main.ly").apply {
            writeText("\\version \"2.26.0\"\n\\include \"notes.ily\"\n{ \\melody }\n")
        }
        val r = engine.renderFile(main)
        assertTrue(r.log, r.success)
        assertTrue(dir.listFiles()!!.none { it.extension == "pdf" }) // output goes to the cache
    }

    @Test
    fun cancellationStopsLilyPond() = runBlocking {
        engine.prepare()
        val start = System.nanoTime()
        val job = async { engine.render(HEAVY_SAMPLE) }
        delay(500)
        job.cancel()
        runCatching { job.await() }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("cancel took $ms ms", ms < 2500)
        // The engine still works afterwards.
        assertTrue(engine.render(SAMPLE).success)
    }

    private fun <T> assertNotNullAndGet(value: T?, message: String): T {
        assertNotNull(message, value)
        return value!!
    }
}
