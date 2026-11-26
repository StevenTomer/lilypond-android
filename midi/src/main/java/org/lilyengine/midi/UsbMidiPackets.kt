package org.lilyengine.midi

import java.io.ByteArrayOutputStream

/**
 * USB-MIDI 1.0 event packets, as a class-compliant keyboard sends and takes
 * them on its bulk endpoints: four bytes each, the first holding the cable
 * (virtual port) in its high nibble and the Code Index Number (CIN, what
 * kind of message) in its low one, then up to three MIDI bytes.
 */
object UsbMidiPackets {
    /** How many MIDI bytes a packet with this CIN carries (0: none, reserved). */
    fun length(cin: Int): Int = when (cin and 0x0F) {
        0x5, 0xF -> 1 // single byte; SysEx ending with one byte
        0x2, 0x6, 0xC, 0xD -> 2 // two-byte system common; SysEx ending with two; program change; channel pressure
        0x3, 0x4, 0x7, 0x8, 0x9, 0xA, 0xB, 0xE -> 3
        else -> 0 // 0x0, 0x1: reserved
    }

    /**
     * The MIDI bytes in [count] bytes of packets from [buffer], from cable
     * [cable] only (-1: every cable), handed to [emit] packet by packet.
     */
    fun decode(buffer: ByteArray, count: Int, cable: Int = 0, emit: (bytes: ByteArray, offset: Int, length: Int) -> Unit) {
        var i = 0
        while (i + 4 <= count) {
            val head = buffer[i].toInt() and 0xFF
            val n = length(head)
            // An all-zero packet is padding; some devices fill the transfer with them.
            if (n > 0 && (cable < 0 || head shr 4 == cable)) emit(buffer, i + 1, n)
            i += 4
        }
    }

    /**
     * [midi] (whole messages, as the app sends them: channel messages, or one
     * complete SysEx) as packets for cable [cable].
     */
    fun encode(midi: ByteArray, cable: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        val c = (cable and 0x0F) shl 4
        fun packet(cin: Int, b1: Int, b2: Int = 0, b3: Int = 0) {
            out.write(c or cin); out.write(b1); out.write(b2); out.write(b3)
        }
        var i = 0
        var running = 0
        while (i < midi.size) {
            var status = midi[i].toInt() and 0xFF
            if (status < 0x80) { // running status: the data byte goes with the last status
                if (running == 0) { i++; continue }
                status = running
            } else i++
            when {
                status == 0xF0 -> { // SysEx: three bytes per packet, the last packet says how many end it
                    val sysex = mutableListOf(0xF0)
                    while (i < midi.size) { val b = midi[i++].toInt() and 0xFF; sysex += b; if (b == 0xF7) break }
                    var k = 0
                    while (sysex.size - k > 3) { packet(0x4, sysex[k], sysex[k + 1], sysex[k + 2]); k += 3 }
                    when (sysex.size - k) {
                        1 -> packet(0x5, sysex[k])
                        2 -> packet(0x6, sysex[k], sysex[k + 1])
                        3 -> packet(0x7, sysex[k], sysex[k + 1], sysex[k + 2])
                    }
                    running = 0
                }
                status >= 0xF8 -> packet(0xF, status) // real time: one byte, no running status change
                status >= 0xF0 -> { // system common
                    val n = when (status) { 0xF1, 0xF3 -> 1; 0xF2 -> 2; else -> 0 }
                    val d1 = if (n >= 1 && i < midi.size) midi[i++].toInt() and 0x7F else 0
                    val d2 = if (n >= 2 && i < midi.size) midi[i++].toInt() and 0x7F else 0
                    packet(when (n) { 0 -> 0x5; 1 -> 0x2; else -> 0x3 }, status, d1, d2)
                    running = 0
                }
                else -> { // channel message
                    val kind = status shr 4
                    val n = if (kind == 0xC || kind == 0xD) 1 else 2
                    if (midi.size - i < n) break // incomplete
                    val d1 = midi[i++].toInt() and 0x7F
                    val d2 = if (n == 2) midi[i++].toInt() and 0x7F else 0
                    packet(kind, status, d1, d2)
                    running = status
                }
            }
        }
        return out.toByteArray()
    }
}
