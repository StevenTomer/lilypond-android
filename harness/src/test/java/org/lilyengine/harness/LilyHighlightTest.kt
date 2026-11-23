package org.lilyengine.harness

import org.junit.Assert.assertEquals
import org.junit.Test

class LilyHighlightTest {
    private fun kinds(src: String) = LilyHighlight.tokens(src).map { it.kind to src.substring(it.start, it.end) }

    @Test
    fun notesDurationsAndCommands() {
        assertEquals(
            listOf("command" to "\\relative", "note" to "c''", "structure" to "{", "note" to "cis", "duration" to "4.",
                "note" to "des,", "note" to "r", "duration" to "8", "structure" to "}"),
            kinds("\\relative c'' { cis4. des, r8 }"),
        )
    }

    @Test
    fun voicesCommentsStringsScheme() {
        assertEquals(
            listOf("structure" to "<<", "structure" to "{", "note" to "e", "structure" to "}", "voices" to "\\\\",
                "structure" to "{", "note" to "c", "structure" to "}", "structure" to ">>", "comment" to "% two voices"),
            kinds("<< { e } \\\\ { c } >> % two voices"),
        )
        assertEquals(
            listOf("command" to "\\header", "structure" to "{", "string" to "\"a c\"", "structure" to "}"),
            kinds("\\header { title = \"a c\" }").filter { it.second != "title" },
        )
        assertEquals(listOf("command" to "\\override", "scheme" to "#'(1 . 2)"), kinds("\\override X.y = #'(1 . 2)").filter { it.first != "note" })
    }

    @Test
    fun wordsAreNotNotes() {
        // "Staff", "beam" and "title" contain note letters but aren't notes.
        assertEquals(emptyList<Pair<String, String>>(), kinds("Staff beam title").filter { it.first == "note" })
    }

    @Test
    fun chordAndMarks() {
        assertEquals(
            listOf("note" to "c", "note" to "e", "note" to "g", "duration" to "2"),
            kinds("<c e g>2-.").filter { it.first == "note" || it.first == "duration" },
        )
    }
}
