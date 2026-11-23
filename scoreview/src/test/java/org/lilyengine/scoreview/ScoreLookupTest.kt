package org.lilyengine.scoreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lilyengine.engine.ScoreMap

/**
 * Uses the score map LilyPond wrote for this source (poly.ly):
 *
 * ```
 * \version "2.26.0"
 * \relative c'' {
 *   \key g \major \time 4/4
 *   << { c4 e g e } \\ { c,2 g' } >> |
 *   <g b d>2 r4 fis8 g |
 * }
 * ```
 */
class ScoreLookupTest {
    private val map = ScoreMap.parse(javaClass.getResource("/poly.scoremap.json")!!.readText())
    private val lookup = ScoreLookup(map)
    private val k = lookup.pointsPerStaffSpace

    /** Page point at the centre of a grob's box. */
    private fun centre(g: ScoreMap.Grob) =
        ((g.bbox[0] + g.bbox[2]) / 2 * k) to (-(g.bbox[1] + g.bbox[3]) / 2 * k)

    private fun head(name: String, voice: String) =
        map.grobs.first { it.type == "NoteHead" && it.pitch?.name == name && it.voiceId == voice }

    @Test
    fun staffPlaceUnderEveryNoteHeadIsItsPitch() {
        val heads = lookup.grobsOnPage(1).filter { it.type == "NoteHead" }
        assertTrue(heads.size > 5)
        for (h in heads) {
            val (x, y) = centre(h)
            val p = lookup.pitchAt(1, x, y)
            assertNotNull("no staff at ${h.pitch?.name}", p)
            assertEquals(h.pitch!!.name, h.staffPosition, p!!.position)
            assertEquals(h.pitch!!.name, h.pitch!!.notename to h.pitch!!.octave, p.step to p.octave)
            // One line or space higher is the next note up.
            val up = lookup.pitchAt(1, x, y - p.halfSpacePt)!!
            assertEquals((h.pitch!!.octave * 7 + h.pitch!!.notename + 1), up.octave * 7 + up.step)
        }
        // Far away from any staff: nothing.
        assertNull(lookup.pitchAt(1, 1.0, 1.0))
    }

    @Test
    fun pitchNamesAsLilyPondWritesThem() {
        assertEquals("c'", ScoreLookup.pitchName(0, 0.0, 0))
        assertEquals("fis''", ScoreLookup.pitchName(3, 0.5, 1))
        assertEquals("bes", ScoreLookup.pitchName(6, -0.5, -1))
        assertEquals("es,", ScoreLookup.pitchName(2, -0.5, -2))
        assertEquals("as'", ScoreLookup.pitchName(5, -0.5, 0))
        assertEquals("cisis'''", ScoreLookup.pitchName(0, 1.0, 2))
    }

    @Test
    fun tapOnANoteHeadFindsIt() {
        val g = head("c''", "1")
        val (x, y) = centre(g)
        assertEquals(g, lookup.objectAt(1, x, y))
    }

    @Test
    fun tapsTellTheTwoVoicesApart() {
        // c'' (voice 1) and c' (voice 2) start together at moment 0, one above the other.
        val upper = head("c''", "1")
        val lower = head("c'", "2")
        assertEquals("0", upper.moment)
        assertEquals("0", lower.moment)
        assertEquals(upper, lookup.objectAt(1, centre(upper).first, centre(upper).second))
        assertEquals(lower, lookup.objectAt(1, centre(lower).first, centre(lower).second))
    }

    @Test
    fun nearMissStillHitsButFarMissDoesNot() {
        val g = head("e''", "1")
        val right = g.bbox[2] * k
        val midY = -(g.bbox[1] + g.bbox[3]) / 2 * k
        assertEquals(g, lookup.objectAt(1, right + 0.3 * k, midY))
        assertNull(lookup.objectAt(1, right + 5 * k, midY - 20 * k))
    }

    @Test
    fun pageRectIsInTopDownPoints() {
        val g = head("c''", "1")
        val r = lookup.pageRect(g)
        assertTrue(r[0] < r[2] && r[1] < r[3])
        assertTrue(r[1] > 0) // below the top of the page
        assertEquals(map.toPdfRect(g, 842.0)[0], r[0].toDouble(), 1e-3)
    }

    @Test
    fun cursorFindsTheNoteItIsIn() {
        // Line 4 is "  << { c4 e g e } \\ { c,2 g' } >> |"; g is at char 12.
        val g = lookup.objectsAtSource(line = 4, char = 12).single { it.type == "NoteHead" }
        assertEquals("g''", g.pitch?.name)
        // Anywhere inside "c,2" (chars 23..25) finds voice 2's c'.
        for (c in 23..25) {
            val heads = lookup.objectsAtSource(4, c).filter { it.type == "NoteHead" }
            assertEquals("char $c", listOf("c'"), heads.map { it.pitch?.name })
            assertEquals("2", heads.single().voiceId)
        }
        // Before the first note on the line there is nothing.
        assertTrue(lookup.objectsAtSource(4, 2).isEmpty())
    }

    @Test
    fun windowAroundIsBarAligned() {
        // The fixture has one printed bar line, at moment 1 (2 bars of 4/4).
        assertEquals("0" to "1", lookup.windowAround("1/2", barsBefore = 1, barsAfter = 0))
        assertEquals("0" to ScoreLookup.OPEN_END, lookup.windowAround("3/2"))
        assertEquals("1" to ScoreLookup.OPEN_END, lookup.windowAround("3/2", barsBefore = 0))
    }

    @Test
    fun sourcePositionsRoundTrip() {
        val text = "\\version \"2.26.0\"\n{ c'4 é d'4 }\n"
        val offset = SourcePositions.offsetOf(text, 2, 8)
        assertEquals('d', text[offset])
        assertEquals(2 to 8, SourcePositions.locationOf(text, offset))
        // Characters outside the BMP count once, as in LilyPond.
        val emoji = "{ 𝄞 c }"
        assertEquals('c', emoji[SourcePositions.offsetOf(emoji, 1, 4)])
    }

    @Test
    fun tokenEnds() {
        fun tok(s: String, at: Int = 0) = s.substring(at, SourcePositions.tokenEnd(s, at))
        assertEquals("fis''8.", tok("fis''8. g"))
        assertEquals("c,2", tok("c,2}"))
        assertEquals("<g b d>2", tok("<g b d>2 r4"))
        assertEquals("<g b d>", tok("<g b d> r4"))
        assertEquals("\\fermata", tok("\\fermata\\p"))
        assertEquals("r4", tok("r4|"))
    }

    @Test
    fun durationsInWholeNotes() {
        assertEquals(0.375, ScoreLookup.durationLength("4.")!!, 1e-9)
        assertEquals(1.0 / 12, ScoreLookup.durationLength("8*2/3")!!, 1e-9)
        assertEquals(2.0, ScoreLookup.durationLength("\\breve")!!, 1e-9)
        assertEquals(0.4375, ScoreLookup.durationLength("4..")!!, 1e-9)
    }

    @Test
    fun soundingNotesInBothVoices() {
        fun names(m: Double) = lookup.soundingAt(m).filter { it.type == "NoteHead" }.map { it.pitch!!.name }.sorted()
        assertEquals(listOf("c'", "c''"), names(0.0))
        assertEquals(listOf("c'", "e''"), names(0.3)) // voice 2's half note still sounds
        assertEquals(listOf("e''", "g'"), names(0.8))
        assertEquals(listOf("b'", "d''", "g'"), names(1.2)) // the chord
        assertEquals(listOf("fis'"), names(1.8))
        assertTrue(lookup.soundingAt(1.6).any { it.type == "Rest" })
    }
}
