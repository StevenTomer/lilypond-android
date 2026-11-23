package org.lilyengine.harness

import org.junit.Assert.assertEquals
import org.junit.Test

class IncludeFolderTest {
    @Test
    fun includesOfTheScoreNotLilyPondsOwn() {
        val text = """
            \version "2.26.0"
            \include "defs.ly"
            \include "parts/violin.ly"
            \include "defs.ly"
            \include "/usr/share/x.ly"
            \include "C:/x.ly"
        """.trimIndent()
        assertEquals(listOf("defs.ly", "parts/violin.ly"), IncludeFolder.includes(text))
    }
}
