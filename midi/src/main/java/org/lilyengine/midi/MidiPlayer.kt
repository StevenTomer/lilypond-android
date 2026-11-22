package org.lilyengine.midi

import java.util.concurrent.locks.LockSupport

/**
 * Plays a [MidiFile] by handing each message to [send] at its time, on a
 * thread of its own (sending on time rather than ahead with timestamps, so
 * stopping is immediate). On stop, or at the end, every sounding note is
 * released and the sustain pedal lifted.
 */
class MidiPlayer(
    private val file: MidiFile,
    private val send: (ByteArray) -> Unit,
    /** Called on the player's thread when playback reaches the end (not when stopped). */
    private val onEnd: () -> Unit = {},
) {
    @Volatile private var thread: Thread? = null
    @Volatile private var startNanos = 0L
    @Volatile private var fromMicros = 0L

    val playing: Boolean get() = thread != null

    /** Microseconds into the music now. */
    val positionMicros: Long
        get() = if (thread == null) fromMicros else fromMicros + (System.nanoTime() - startNanos) / 1000

    fun start(atMicros: Long = 0) {
        stop()
        fromMicros = atMicros
        startNanos = System.nanoTime()
        val t = Thread({ run() }, "midi-player").apply { priority = Thread.MAX_PRIORITY }
        thread = t
        t.start()
    }

    fun stop() {
        val t = thread ?: return
        thread = null
        t.interrupt()
        runCatching { t.join(500) }
    }

    private fun run() {
        val me = Thread.currentThread()
        val sounding = mutableSetOf<Int>() // channel shl 8 or key
        val first = file.events.indexOfFirst { file.micros(it.tick) >= fromMicros }.let { if (it < 0) file.events.size else it }
        // Program changes and controllers before the start still apply.
        for (e in file.events.subList(0, first)) if (e.status and 0xF0 == 0xC0 || e.status and 0xF0 == 0xB0) send(e.bytes)
        var finished = true
        for (e in file.events.subList(first, file.events.size)) {
            val due = startNanos + (file.micros(e.tick) - fromMicros) * 1000
            while (true) {
                if (thread !== me) break
                val wait = due - System.nanoTime()
                if (wait <= 0) break
                LockSupport.parkNanos(minOf(wait, 50_000_000L))
            }
            if (thread !== me) { finished = false; break }
            val ch = e.status and 0x0F
            val key = e.bytes.getOrNull(1)?.toInt() ?: 0
            when {
                e.isNoteOn -> sounding += (ch shl 8) or key
                e.status and 0xF0 == 0x80 || e.status and 0xF0 == 0x90 -> sounding -= (ch shl 8) or key
            }
            send(e.bytes)
        }
        for (n in sounding) send(byteArrayOf((0x80 or (n shr 8)).toByte(), (n and 0x7F).toByte(), 0))
        val channels = file.events.map { it.status and 0x0F }.toSet()
        for (ch in channels) send(byteArrayOf((0xB0 or ch).toByte(), 64, 0)) // sustain off
        if (finished && thread === me) {
            thread = null
            fromMicros = file.durationMicros
            onEnd()
        }
    }
}
