package org.lilyengine.midi

import java.io.File

/**
 * A Standard MIDI File (format 0 or 1, as LilyPond writes), read into one
 * time-ordered list of channel events, with the tempo map to turn ticks
 * into time.
 */
class MidiFile(
    /** Ticks per quarter note. */
    val division: Int,
    /** Channel messages (note on/off, controllers, program changes…) of all tracks, by tick. */
    val events: List<Event>,
    /** Tempo changes, by tick; the first is at tick 0. */
    val tempos: List<Tempo>,
) {
    /** A channel message: [bytes] is status plus data, ready to send. */
    class Event(val tick: Long, val bytes: ByteArray) {
        val status: Int get() = bytes[0].toInt() and 0xFF
        val isNoteOn: Boolean get() = status and 0xF0 == 0x90 && bytes.size > 2 && bytes[2] > 0
    }

    /** From [tick] on, a quarter note lasts [microsPerQuarter]. */
    data class Tempo(val tick: Long, val microsPerQuarter: Int)

    /** The last tick of any event. */
    val lastTick: Long = events.maxOfOrNull { it.tick } ?: 0

    /** Microseconds from the start to [tick]. */
    fun micros(tick: Long): Long {
        var us = 0.0
        for (i in tempos.indices) {
            val t = tempos[i]
            if (t.tick >= tick) break
            val end = minOf(tick, tempos.getOrNull(i + 1)?.tick ?: Long.MAX_VALUE)
            us += (end - t.tick).toDouble() * t.microsPerQuarter / division
        }
        return us.toLong()
    }

    /** The tick reached after [micros] microseconds. */
    fun tick(micros: Long): Long {
        var us = 0.0
        for (i in tempos.indices) {
            val t = tempos[i]
            val next = tempos.getOrNull(i + 1)
            val span = next?.let { (it.tick - t.tick).toDouble() * t.microsPerQuarter / division }
            if (span == null || us + span > micros) {
                return t.tick + ((micros - us) * division / t.microsPerQuarter).toLong()
            }
            us += span
        }
        return 0
    }

    /** Whole notes from the start to [tick]: LilyPond's moments, for unrepeated music without grace notes. */
    fun wholeNotes(tick: Long): Double = tick.toDouble() / (4 * division)

    val durationMicros: Long get() = micros(lastTick)

    class FormatException(message: String) : Exception(message)

    companion object {
        fun read(file: File): MidiFile = parse(file.readBytes())

        fun parse(data: ByteArray): MidiFile {
            val r = Reader(data)
            if (r.ascii(4) != "MThd") throw FormatException("not a MIDI file")
            val headerLength = r.u32().toInt()
            r.u16() // format
            val tracks = r.u16()
            val division = r.u16()
            if (division and 0x8000 != 0) throw FormatException("SMPTE time is not supported")
            r.skip(headerLength - 6)
            val events = mutableListOf<Event>()
            val tempos = mutableListOf<Tempo>()
            repeat(tracks) {
                while (r.remaining() >= 8 && r.ascii(4) != "MTrk") r.skip(r.u32().toInt()) // unknown chunk
                if (r.remaining() < 4) return@repeat
                val length = r.u32().toInt()
                val end = r.pos + length
                var tick = 0L
                var running = 0
                while (r.pos < end) {
                    tick += r.varLen()
                    var status = r.u8()
                    if (status < 0x80) { r.pos--; status = running } else if (status < 0xF0) running = status
                    when (status) {
                        0xFF -> {
                            val type = r.u8()
                            val len = r.varLen().toInt()
                            if (type == 0x51 && len == 3) tempos += Tempo(tick, (r.u8() shl 16) or (r.u8() shl 8) or r.u8())
                            else r.skip(len)
                        }
                        0xF0, 0xF7 -> r.skip(r.varLen().toInt())
                        else -> {
                            if (status < 0x80) throw FormatException("data without status at ${r.pos}")
                            val len = if (status and 0xF0 == 0xC0 || status and 0xF0 == 0xD0) 1 else 2
                            val bytes = ByteArray(1 + len)
                            bytes[0] = status.toByte()
                            for (k in 1..len) bytes[k] = r.u8().toByte()
                            events += Event(tick, bytes)
                        }
                    }
                }
                r.pos = end
            }
            // Stable: at one tick, note offs written before note ons stay first.
            events.sortBy { it.tick }
            tempos.sortBy { it.tick }
            if (tempos.firstOrNull()?.tick != 0L) tempos.add(0, Tempo(0, 500_000)) // 120 bpm default
            return MidiFile(division, events, tempos.distinctBy { it.tick })
        }
    }

    private class Reader(val data: ByteArray) {
        var pos = 0
        fun remaining() = data.size - pos
        fun u8(): Int {
            if (pos >= data.size) throw FormatException("file ends early")
            return data[pos++].toInt() and 0xFF
        }
        fun u16() = (u8() shl 8) or u8()
        fun u32() = (u16().toLong() shl 16) or u16().toLong()
        fun ascii(n: Int) = String(CharArray(n) { u8().toChar() })
        fun skip(n: Int) { pos += n }
        fun varLen(): Long {
            var v = 0L
            while (true) {
                val b = u8()
                v = (v shl 7) or (b and 0x7F).toLong()
                if (b and 0x80 == 0) return v
            }
        }
    }
}
