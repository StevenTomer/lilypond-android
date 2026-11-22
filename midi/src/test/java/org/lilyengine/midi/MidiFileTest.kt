package org.lilyengine.midi

import org.junit.Assert.assertEquals
import org.junit.Test

class MidiFileTest {
    private fun bytes(vararg b: Int) = b.map { it.toByte() }.toByteArray()

    /** Format 1, 384 ticks a quarter: a conductor track with two tempos, and a track with two notes. */
    private val smf = bytes(
        0x4D, 0x54, 0x68, 0x64, 0, 0, 0, 6, 0, 1, 0, 2, 0x01, 0x80,
        0x4D, 0x54, 0x72, 0x6B, 0, 0, 0, 19,
        0x00, 0xFF, 0x51, 0x03, 0x0F, 0x42, 0x40, // 1 s a quarter
        0x83, 0x00, 0xFF, 0x51, 0x03, 0x03, 0xD0, 0x90, // tick 384: 0.25 s a quarter
        0x00, 0xFF, 0x2F, 0x00,
        0x4D, 0x54, 0x72, 0x6B, 0, 0, 0, 19,
        0x00, 0xC0, 0x00, // piano
        0x00, 0x90, 0x3C, 0x40, // c' on
        0x83, 0x00, 0x3C, 0x00, // running status: c' off (velocity 0) at 384
        0x00, 0x40, 0x40, // e' on
        0x83, 0x00, 0x80, 0x40, 0x00, // e' off at 768
        // (no end-of-track: the chunk length ends it)
    )

    @Test
    fun eventsAndTempoMap() {
        val f = MidiFile.parse(smf)
        assertEquals(384, f.division)
        assertEquals(5, f.events.size)
        assertEquals(listOf(0L, 0L, 384L, 384L, 768L), f.events.map { it.tick })
        assertEquals(listOf(true, false, true), f.events.filter { it.status and 0xF0 == 0x90 }.map { it.isNoteOn }.let { listOf(it[0], it[1], it[2]) })
        assertEquals(1_000_000L, f.micros(384))
        assertEquals(1_250_000L, f.micros(768))
        assertEquals(1_250_000L, f.durationMicros)
        assertEquals(192L, f.tick(500_000))
        assertEquals(576L, f.tick(1_125_000))
        assertEquals(0.5, f.wholeNotes(768), 1e-9)
    }
}
