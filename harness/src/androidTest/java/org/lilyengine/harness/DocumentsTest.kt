package org.lilyengine.harness

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun tabsAreKeptWithTheirTextFileAndName() {
        val (before, beforeActive) = Documents.load(context)
        val a = Documents.create(context, EMPTY_SCORE.replace("Untitled", "Minuet"))
        val text = "{ c'4 d' e' f' }"
        val b = Documents.create(context, text, ScoreFiles.Current(Uri.parse("content://x/y.ly"), "y.ly", text.hashCode()))
        try {
            Documents.save(context, before + a + b, a.id)
            val (docs, active) = Documents.load(context)
            assertEquals(a.id, active)
            val la = docs.first { it.id == a.id }
            val lb = docs.first { it.id == b.id }
            assertEquals("Minuet", la.name) // no file: its title
            assertTrue(la.modified) // never saved
            assertEquals("y.ly", lb.name)
            assertFalse(lb.modified) // as saved
            assertEquals(text, lb.draft(context).readText())
        } finally {
            Documents.discard(context, a)
            Documents.discard(context, b)
            Documents.save(context, before, beforeActive)
        }
        assertFalse(a.draft(context).exists())
        assertEquals(null, ScoreFiles.load(context, b.id).uri)
    }
}
