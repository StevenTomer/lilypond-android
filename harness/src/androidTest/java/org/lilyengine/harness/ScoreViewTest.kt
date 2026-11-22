package org.lilyengine.harness

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.lilyengine.engine.LilyPondEngine
import org.lilyengine.scoreview.ScoreDocument
import org.lilyengine.scoreview.ScoreLookup
import org.lilyengine.scoreview.SourcePositions

/** The score view's pieces on a real render: PDF pages, hit-testing, source positions. */
@RunWith(AndroidJUnit4::class)
class ScoreViewTest {
    private val engine = LilyPondEngine(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun noteBoxesLineUpWithTheDrawnPage() = runBlocking {
        val r = engine.render(POLY_SAMPLE)
        assertTrue(r.log, r.success)
        val lookup = ScoreLookup(r.scoreMap!!)
        ScoreDocument.open(r.outputs.single()).use { doc ->
            assertEquals(1, doc.pageCount)
            val (wPt, _) = doc.pageSizes[0]
            val widthPx = 1200
            val bitmap = doc.render(0, widthPx)!!
            val px = widthPx / wPt
            // Every note head's box must contain dark (inked) pixels where the
            // PDF was drawn: the map's coordinates match the page.
            val heads = lookup.grobsOnPage(1).filter { it.type == "NoteHead" }
            assertEquals(11, heads.size)
            for (g in heads) {
                val (l, t, rt, b) = lookup.pageRect(g).map { it * px }
                var dark = 0
                for (x in l.toInt()..rt.toInt()) for (y in t.toInt()..b.toInt()) {
                    if (Color.luminance(bitmap.getPixel(x, y)) < 0.5f) dark++
                }
                assertTrue("no ink under ${g.pitch?.name} at ${g.origin}", dark > 10)
            }
        }
    }

    @Test
    fun tapAndCursorAgree() = runBlocking {
        val r = engine.render(POLY_SAMPLE)
        assertTrue(r.log, r.success)
        val lookup = ScoreLookup(r.scoreMap!!)
        // Voice 2's c' (written "c,2" on line 4): tap its centre...
        val head = lookup.grobsOnPage(1).single { it.type == "NoteHead" && it.voiceId == "2" && it.pitch?.name == "c'" }
        val (l, t, rt, b) = lookup.pageRect(head).toList()
        val tapped = lookup.objectAt(1, ((l + rt) / 2).toDouble(), ((t + b) / 2).toDouble())
        assertEquals(head, tapped)
        // ...and its origin selects exactly "c,2" in the source.
        val o = assertNotNullAndGet(tapped!!.origin)
        val start = SourcePositions.offsetOf(POLY_SAMPLE, o.line, o.char)
        assertEquals("c,2", POLY_SAMPLE.substring(start, SourcePositions.tokenEnd(POLY_SAMPLE, start)))
        // A cursor inside that token finds the same note.
        val (line, char) = SourcePositions.locationOf(POLY_SAMPLE, start + 1)
        assertTrue(head in lookup.objectsAtSource(line, char, "score.ly"))
    }

    private fun <T> assertNotNullAndGet(v: T?): T { assertNotNull(v); return v!! }
}
