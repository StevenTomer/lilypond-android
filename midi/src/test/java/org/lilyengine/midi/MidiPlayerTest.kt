package org.lilyengine.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class MidiPlayerTest {
    /** Format 0 at 120 bpm (the default), 96 ticks a quarter: c' for an eighth, then e' held 2 s. */
    private val smf = listOf(
        0x4D, 0x54, 0x68, 0x64, 0, 0, 0, 6, 0, 0, 0, 1, 0, 96,
        0x4D, 0x54, 0x72, 0x6B, 0, 0, 0, 17,
        0x00, 0x90, 60, 80,
        0x30, 0x80, 60, 0, // after 48 ticks = 0.25 s
        0x00, 0x90, 64, 80,
        0x83, 0x00, 0x80, 64, 0, // 384 ticks = 2 s later
    ).map { it.toByte() }.toByteArray()

    private class Sent(val millis: Long, val bytes: List<Int>)

    @Test
    fun sendsOnTimeAndReleasesNotesWhenStopped() {
        val file = MidiFile.parse(smf)
        val sent = Collections.synchronizedList(mutableListOf<Sent>())
        val t0 = System.nanoTime()
        val player = MidiPlayer(file, { b -> sent += Sent((System.nanoTime() - t0) / 1_000_000, b.map { it.toInt() and 0xFF }) })
        player.start()
        Thread.sleep(600)
        assertTrue(player.playing)
        assertTrue(player.positionMicros in 550_000..800_000)
        player.stop()
        val msgs = sent.map { it.bytes }
        assertEquals(listOf(0x90, 60, 80), msgs[0])
        assertEquals(listOf(0x80, 60, 0), msgs[1])
        assertEquals(listOf(0x90, 64, 80), msgs[2])
        assertTrue("c' off at ${sent[1].millis} ms", sent[1].millis in 230..400)
        // Stopped while e' sounds: it is released, and the pedal lifted.
        assertEquals(listOf(listOf(0x80, 64, 0), listOf(0xB0, 64, 0)), msgs.drop(3))
        assertTrue(!player.playing)
    }

    @Test
    fun startsPartWayThrough() {
        val file = MidiFile.parse(smf)
        val sent = Collections.synchronizedList(mutableListOf<List<Int>>())
        var ended = false
        val player = MidiPlayer(file, { b -> sent += b.map { it.toInt() and 0xFF } }, onEnd = { ended = true })
        player.start(atMicros = 2_000_000) // past e' starting: only its end is left
        Thread.sleep(500)
        assertTrue(ended)
        assertEquals(listOf(listOf(0x80, 64, 0), listOf(0xB0, 64, 0)), sent.toList())
    }
}
