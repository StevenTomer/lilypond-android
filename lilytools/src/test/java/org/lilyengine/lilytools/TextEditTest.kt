package org.lilyengine.lilytools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextEditTest {
    @Test
    fun smallestEdit() {
        val old = "\\relative { c'4 d e f }"
        val new = "\\relative { c'4 g'' e, f }"
        val e = TextEdit.between(old, new)
        assertEquals(new, e.applyTo(old))
        assertEquals("\\relative { c'4 ".length, e.start)
        assertEquals("g'' e,", e.replacement)
    }

    @Test
    fun insertionAndNoChange() {
        val e = TextEdit.between("{ c'4 d' }", "{ c'4 d'8 }")
        assertEquals(TextEdit(8, 8, "8"), e)
        assertTrue(TextEdit.between("abc", "abc").isEmpty)
    }

    @Test
    fun repeatedCharacters() {
        val e = TextEdit.between("c c c", "c c c c")
        assertEquals("c c c c", e.applyTo("c c c"))
    }
}
