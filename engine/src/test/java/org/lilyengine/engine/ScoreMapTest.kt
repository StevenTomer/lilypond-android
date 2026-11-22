package org.lilyengine.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Parses a map written by LilyPond 2.26 for the two-voice example. */
class ScoreMapTest {
    private val map = ScoreMap.parse(
        javaClass.getResource("/poly.scoremap.json")!!.readText(),
    )

    @Test
    fun parsesVoicesAndPitches() {
        val heads = map.grobs.filter { it.type == "NoteHead" }
        assertEquals(11, heads.size)
        val voice2 = heads.filter { it.voiceId == "2" }.sortedBy { it.momentValue }
        assertEquals(listOf("c'", "g'"), voice2.map { it.pitch!!.name })
        assertEquals(listOf("2", "2"), voice2.map { it.duration })
        assertEquals(4, voice2.first().origin!!.line)
    }

    @Test
    fun rationalMoments() {
        assertEquals(1.5, ScoreMap.parseRational("3/2"), 0.0)
        assertEquals(2.0, ScoreMap.parseRational("2"), 0.0)
    }

    @Test
    fun pdfRectMatchesLilyPondsLinks() {
        // c'' at 4:7; LilyPond's own link rectangle in the PDF (A4 page).
        val head = map.grobs.single { it.origin?.line == 4 && it.origin?.char == 7 }
        val rect = map.toPdfRect(head, pageHeightPt = 841.89)
        assertArrayEquals(doubleArrayOf(135.5, 783.4, 142.0, 788.9), rect.map { Math.round(it * 10) / 10.0 }.toDoubleArray(), 0.11)
    }

    @Test
    fun hitTestFindsNoteHead() {
        val head = map.grobs.single { it.origin?.line == 4 && it.origin?.char == 7 }
        val cx = (head.bbox[0] + head.bbox[2]) / 2
        val cy = (head.bbox[1] + head.bbox[3]) / 2
        val hits = map.hitTest(head.book, head.page, cx, cy)
        assertEquals(true, hits.any { it === head })
    }
}
