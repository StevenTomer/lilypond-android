package org.lilyengine.midi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class UsbMidiPacketsTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    private fun decoded(buffer: ByteArray, cable: Int = 0): List<List<Int>> = buildList {
        UsbMidiPackets.decode(buffer, buffer.size, cable) { b, o, n -> add((o until o + n).map { b[it].toInt() and 0xFF }) }
    }

    @Test
    fun notesFromTheKeyboard() {
        // Note on c' velocity 100, note off c', sustain pedal down, a padding packet.
        val usb = bytes(0x09, 0x90, 60, 100, 0x08, 0x80, 60, 0, 0x0B, 0xB0, 64, 127, 0, 0, 0, 0)
        assertEquals(listOf(listOf(0x90, 60, 100), listOf(0x80, 60, 0), listOf(0xB0, 64, 127)), decoded(usb))
    }

    @Test
    fun onlyTheChosenCable() {
        // The same note on cable 0 and cable 1 (the DGX-670 has two ports).
        val usb = bytes(0x09, 0x90, 60, 100, 0x19, 0x91, 62, 90)
        assertEquals(listOf(listOf(0x90, 60, 100)), decoded(usb, cable = 0))
        assertEquals(listOf(listOf(0x91, 62, 90)), decoded(usb, cable = 1))
        assertEquals(2, decoded(usb, cable = -1).size)
    }

    @Test
    fun twoByteMessagesAndATrailingPartialPacket() {
        val usb = bytes(0x0C, 0xC0, 5, 0, 0x0D, 0xD0, 40, 0, 0x09, 0x90)
        assertEquals(listOf(listOf(0xC0, 5), listOf(0xD0, 40)), decoded(usb, cable = 0))
    }

    @Test
    fun chordsAndRunningStatusToTheKeyboard() {
        // As MidiKeyboard.play sends them: note on, note on in running status, program change.
        assertArrayEquals(
            bytes(0x09, 0x90, 60, 80, 0x09, 0x90, 64, 80, 0x0C, 0xC0, 3, 0),
            UsbMidiPackets.encode(bytes(0x90, 60, 80, 64, 80, 0xC0, 3)),
        )
    }

    @Test
    fun sysExIsSplitIntoThrees() {
        assertArrayEquals(
            bytes(0x14, 0xF0, 0x43, 0x10, 0x16, 0x4C, 0xF7, 0),
            UsbMidiPackets.encode(bytes(0xF0, 0x43, 0x10, 0x4C, 0xF7), cable = 1),
        )
    }
}
