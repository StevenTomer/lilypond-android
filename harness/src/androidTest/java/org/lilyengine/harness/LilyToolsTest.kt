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
    fun marksGoOnTheRightVoice() = runBlocking {
        var src = POLY_SAMPLE
        suspend fun toggle(token: String, what: String) {
            src = tools.toggle(src, src.indexOf(token), what).applyTo(src)
        }
        toggle("c,2", "staccato") // voice 2
        toggle("c,2", "p")
        toggle("c4 e", "slur-start") // voice 1: c4( e)
        toggle("e g e }", "slur-end")
        toggle("<g b d>", "fermata") // the chord after the << >>
        assertTrue(src, src.contains("{ c4( e) g e } \\\\ { c,2-.\\p g' }") && src.contains("<g b d>2\\fermata"))
        assertEquals(setOf("staccato", "p"), tools.marks(src, src.indexOf("c,2")))

        val r = engine.render(src)
        assertTrue(r.log, r.success)
        val grobs = r.scoreMap!!.grobs
        fun voices(type: String) = grobs.filter { it.type == type }.map { it.voiceId.orEmpty() }
        assertEquals(listOf("2", ""), voices("Script").sortedDescending()) // staccato, fermata
        assertEquals(listOf("2"), voices("DynamicText"))
        assertEquals(listOf("1"), voices("Slur"))
        // The notes themselves are as before.
        assertEquals(heads(POLY_SAMPLE), heads(src))

        // And off again.
        toggle("c,2", "staccato")
        toggle("c,2", "p")
        assertEquals(emptySet<String>(), tools.marks(src, src.indexOf("c,2")))
    }

    @Test
    fun rangeAcrossTwoVoices() = runBlocking {
        // The first bar of both voices, as a pen box would select it.
        val tokens = listOf("c4 e", "e g", "g e", "e }", "c,2", "g' }")
        var offsets = tokens.map { POLY_SAMPLE.indexOf(it) }
        var src = POLY_SAMPLE
        suspend fun apply(r: LilyTools.RangeEdit) {
            src = r.edit.applyTo(src)
            offsets = r.offsets
        }
        apply(tools.range(src, offsets, LilyTools.RangeOp.Step(1)))
        apply(tools.range(src, offsets, LilyTools.RangeOp.Toggle("staccato")))
        // A slur in each voice, from its first to its last note.
        apply(tools.span(src, listOf(offsets[0] to offsets[3], offsets[4] to offsets[5]), "slur", offsets))
        assertTrue(src, src.contains("<< { d4-.( fis-. a-. fis-.) } \\\\ { d,2-.( a'-.) } >>"))
        assertEquals(tokens.size, offsets.size)

        val r = engine.render(src)
        assertTrue(r.log, r.success)
        val grobs = r.scoreMap!!.grobs
        assertEquals(listOf("1", "2"), grobs.filter { it.type == "Slur" }.map { it.voiceId }.sortedBy { it })
        assertEquals(6, grobs.count { it.type == "Script" })
        // Stepped up a scale step in G major; the music after the << >> is untouched.
        val after = heads(src)
        assertEquals(listOf("d''", "fis''", "a''", "fis''"), after["1"])
        assertEquals(listOf("d'", "a'"), after["2"])
        assertEquals(heads(POLY_SAMPLE)[""], after[""])

        // Delete takes the whole range, and the next bar still sounds the same.
        val d = tools.range(src, offsets, LilyTools.RangeOp.Delete)
        assertTrue(d.offsets.isEmpty())
        assertEquals(heads(POLY_SAMPLE)[""], heads(d.edit.applyTo(src))[""])
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
    fun midiImportRoundTrip() = runBlocking {
        val src = """
            \version "2.26.0"
            \new PianoStaff <<
              \new Staff \relative c'' { \key g \major << { b4 c d2 | e4 d c b } \\ { g,2 fis | g4 a fis g } >> | <fis a d>1 }
              \new Staff \relative c { \clef bass \key g \major g2 d' | c b | d1 }
            >>
        """.trimIndent()
        val original = engine.render(src)
        assertTrue(original.log, original.success)
        val imported = tools.importMidi(original.midi.single())
        assertTrue(imported.text, imported.text.contains("\\version \"2.26.0\""))
        val back = engine.render(imported.text)
        assertTrue(imported.text + back.log, back.success)
        // Same pitches at the same moments (voices may come back as chords).
        fun notes(r: LilyPondEngine.Result) = r.scoreMap!!.grobs.filter { it.type == "NoteHead" }
            .map { it.momentValue to it.pitch!!.name }.sortedWith(compareBy({ it.first }, { it.second }))
        assertEquals(notes(original), notes(back))
    }

    @Test
    fun stepEntryIntoEachVoiceSoundsAsPlayed() = runBlocking {
        val before = heads(POLY_SAMPLE)
        // Voice 2 (c,2 g'): MIDI 61 after the g', in G major: cis', not des'.
        val one = tools.insert(POLY_SAMPLE, POLY_SAMPLE.indexOf("g' }"), listOf(61), "4", after = true)
        val src = one.edit.applyTo(POLY_SAMPLE)
        assertTrue(src.substring(one.start), src.startsWith("cis", one.start))
        // Voice 1: a C major chord an octave down after the last e''.
        val e = src.indexOf("e }")
        val two = tools.insert(src, e, listOf(48, 52, 55), "4", after = true)
        val after = heads(two.edit.applyTo(src))
        assertEquals(listOf("c''", "e''", "g''", "e''", "c", "e", "g"), after["1"])
        assertEquals(listOf("c'", "g'", "cis'"), after["2"])
        assertEquals(before[""], after[""])
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
