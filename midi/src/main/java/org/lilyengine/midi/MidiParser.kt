package org.lilyengine.midi

/**
 * Splits a MIDI byte stream into channel messages, keeping the running
 * status across calls (a keyboard may send `90 3C 40 3E 40` for two notes).
 * System real-time bytes (clock, active sensing) and system exclusive
 * messages are skipped.
 */
class MidiParser {
    private var status = 0 // running status; 0: none
    private var inSysex = false
    private val data = IntArray(2)
    private var have = 0

    /** Calls [emit] with (status byte, data 1, data 2) for each complete channel message. */
    fun parse(bytes: ByteArray, offset: Int, count: Int, emit: (status: Int, d1: Int, d2: Int) -> Unit) {
        for (i in offset until offset + count) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b >= 0xF8 -> {} // real-time (clock, active sensing): may come anywhere, even mid-message
                b == 0xF0 -> { inSysex = true; status = 0 }
                b == 0xF7 -> inSysex = false
                b >= 0xF0 -> { inSysex = false; status = 0 } // other system common: ignored
                b >= 0x80 -> { inSysex = false; status = b; have = 0 }
                inSysex || status == 0 -> {} // sysex data, or data without a status
                else -> {
                    data[have++] = b
                    val needed = if (status and 0xF0 == 0xC0 || status and 0xF0 == 0xD0) 1 else 2
                    if (have == needed) {
                        emit(status, data[0], if (needed == 2) data[1] else 0)
                        have = 0
                    }
                }
            }
        }
    }
}

/**
 * Groups played keys into chords for step entry: keys pressed while
 * another is still held belong together, and the chord is finished when
 * every key is up again. So a chord can be played slightly arpeggiated, and
 * a single note comes out when its key is released.
 */
class ChordCollector(private val onChord: (keys: List<Int>) -> Unit) {
    private val held = mutableSetOf<Int>()
    private val chord = sortedSetOf<Int>()

    fun noteOn(key: Int) {
        held += key
        chord += key
    }

    fun noteOff(key: Int) {
        if (!held.remove(key)) return
        if (held.isEmpty() && chord.isNotEmpty()) {
            val keys = chord.toList()
            chord.clear()
            onChord(keys)
        }
    }

    /** Forgets keys held so far (e.g. after the keyboard was disconnected). */
    fun reset() {
        held.clear()
        chord.clear()
    }
}
