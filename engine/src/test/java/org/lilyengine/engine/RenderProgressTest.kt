package org.lilyengine.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class RenderProgressTest {
    @Test
    fun followsLilyPondsPhases() {
        val p = RenderProgress()
        assertEquals("starting", p.phase)
        p.onOutput("Processing `score.ly'")
        assertEquals("starting", p.phase) // not a phase line
        p.onOutput("Parsing...")
        assertEquals("reading the input", p.phase)
        p.onOutput("Interpreting music...")
        assertEquals("interpreting music", p.phase)
        // The bar counter arrives on the same line, without line breaks.
        p.onOutput("Interpreting music...[8][16][24]")
        assertEquals("interpreting music, bar 24", p.phase)
        p.onOutput("score.ly:3:4: warning: bar check failed")
        assertEquals("interpreting music, bar 24", p.phase)
        p.onOutput("[32][40]")
        assertEquals("interpreting music, bar 40", p.phase)
        p.onOutput("Preprocessing graphical objects...")
        assertEquals("placing objects", p.phase)
        p.onOutput("Fitting music on 118 or 119 pages...")
        assertEquals("fitting music on 118 or 119 pages", p.phase)
        p.onOutput("[3]") // not counting bars any more
        assertEquals("fitting music on 118 or 119 pages", p.phase)
        p.onOutput("Drawing systems...")
        assertEquals("drawing systems", p.phase)
        p.onOutput("Converting to `score.pdf'...")
        assertEquals("writing the PDF", p.phase)
    }
}
