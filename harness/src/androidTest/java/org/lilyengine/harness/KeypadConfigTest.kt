package org.lilyengine.harness

import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeypadConfigTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aRearrangedKeypadIsKeptBetweenSessions() {
        val before = KeypadConfig.load(context)
        try {
            val pages = KeypadConfig.DEFAULT_PAGES.toMutableList()
            pages[0] = pages[0].copy(name = "Mine", slots = pages[0].slots.toMutableList().also { it[19] = "mark.fermata"; it[3] = null })
            val config = KeypadConfig(
                columns = 6, rows = 3, pages = pages, follow = false, dock = "side", translucent = true,
                floating = listOf(FloatingPage("marks", Offset(1f, 0.4f), Offset(0.02f, 0.2f), collapsed = true)),
            )
            KeypadConfig.save(context, config)
            assertEquals(config, KeypadConfig.load(context))
        } finally {
            KeypadConfig.save(context, before)
        }
    }

    @Test
    fun everyDefaultPageFillsTheDefaultGrid() {
        val c = KeypadConfig()
        for (p in KeypadConfig.DEFAULT_PAGES) assertEquals(p.name, c.columns * c.rows, p.slots.size)
    }
}
