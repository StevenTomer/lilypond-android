package org.lilyengine.midi

import org.junit.Assert.assertEquals
import org.junit.Test

class MidiParserTest {
    private fun parse(vararg chunks: IntArray): List<Triple<Int, Int, Int>> {
        val out = mutableListOf<Triple<Int, Int, Int>>()
        val p = MidiParser()
        for (c in chunks) {
            val bytes = c.map { it.toByte() }.toByteArray()
            p.parse(bytes, 0, bytes.size) { s, a, b -> out += Triple(s, a, b) }
        }
        return out
    }

    @Test
    fun runningStatusAcrossCalls() {
        assertEquals(
            listOf(Triple(0x90, 60, 64), Triple(0x90, 64, 64), Triple(0x90, 67, 0)),
            parse(intArrayOf(0x90, 60, 64, 64, 64), intArrayOf(67, 0)),
        )
    }

    @Test
    fun realTimeAndSysexAreSkipped() {
        assertEquals(
            listOf(Triple(0x80, 60, 0), Triple(0xC1, 5, 0), Triple(0xB0, 64, 127)),
            parse(
                intArrayOf(0xFE, 0x80, 0xF8, 60, 0),
                intArrayOf(0xF0, 0x43, 0x10, 0x4C, 0xF7, 0xC1, 5),
                intArrayOf(0xF0, 0x7E, 1), intArrayOf(2, 0xF7, 0xB0, 64, 127),
            ),
        )
    }

    @Test
    fun chordsCloseWhenAllKeysAreUp() {
        val chords = mutableListOf<List<Int>>()
        val c = ChordCollector { chords += it }
        c.noteOn(64); c.noteOn(60); c.noteOff(64); c.noteOn(67); c.noteOff(60)
        assertEquals(emptyList<List<Int>>(), chords)
        c.noteOff(67)
        c.noteOn(62); c.noteOff(62)
        c.noteOff(99) // stray release: nothing
        assertEquals(listOf(listOf(60, 64, 67), listOf(62)), chords)
    }
}
