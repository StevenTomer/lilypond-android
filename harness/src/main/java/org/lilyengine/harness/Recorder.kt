package org.lilyengine.harness

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.lilyengine.lilytools.LilyTools
import org.lilyengine.midi.MidiKeyboard

/**
 * Records what is played on the MIDI keyboard (or the on-screen piano) in
 * real time: a bar of metronome clicks to count in, then clicks on every
 * beat while the notes are taken down, each with its onset and release in
 * quarter notes from the first beat after the count-in. The clicks sound on
 * the keyboard (a woodblock on the drum channel) if one is connected, else
 * as a beep on the tablet.
 */
class Recorder(
    private val keyboard: MidiKeyboard,
    private val bpm: Int,
    private val beatsPerBar: Int = 4,
) {
    /** The beat being played: negative during the count-in (-4 … -1), then 0, 1, 2… */
    var beat by mutableIntStateOf(-beatsPerBar)
        private set

    private val beatNanos = 60_000_000_000L / bpm
    private var t0 = 0L
    private val played = mutableListOf<LilyTools.Played>()
    private val held = mutableMapOf<Int, Double>()
    private var job: Job? = null
    private var tone: ToneGenerator? = null

    private fun quarters(nanos: Long) = (nanos - t0).toDouble() / beatNanos

    private val listener: (MidiKeyboard.Event) -> Unit = { e ->
        when (e) {
            is MidiKeyboard.Event.NoteOn -> held[e.key] = quarters(e.timestamp)
            is MidiKeyboard.Event.NoteOff -> held.remove(e.key)?.let { on ->
                played += LilyTools.Played(on, quarters(e.timestamp), e.key)
            }
            else -> {}
        }
    }

    fun start(scope: CoroutineScope) {
        t0 = System.nanoTime() + beatsPerBar * beatNanos
        if (!keyboard.canSend.value) tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull()
        keyboard.addListener(listener)
        job = scope.launch {
            var b = -beatsPerBar
            while (isActive) {
                val wait = (t0 + b * beatNanos - System.nanoTime()) / 1_000_000
                if (wait > 0) delay(wait)
                beat = b
                click(accent = Math.floorMod(b, beatsPerBar) == 0)
                b++
            }
        }
    }

    /** Stops, and returns what was played (keys still down end now). */
    fun stop(): List<LilyTools.Played> {
        job?.cancel()
        keyboard.removeListener(listener)
        val now = quarters(System.nanoTime())
        for ((key, on) in held) played += LilyTools.Played(on, now, key)
        held.clear()
        tone?.release()
        tone = null
        // Played during the count-in, just before the first beat, still counts from it.
        return played.filter { it.release > -0.25 }.map { it.copy(onset = maxOf(it.onset, 0.0)) }.sortedBy { it.onset }
    }

    private fun click(accent: Boolean) {
        if (keyboard.canSend.value) {
            val note = if (accent) 76 else 77 // high and low woodblock (General MIDI drums)
            val now = System.nanoTime()
            keyboard.send(byteArrayOf(0x99.toByte(), note.toByte(), (if (accent) 110 else 80).toByte()), now)
            keyboard.send(byteArrayOf(0x89.toByte(), note.toByte(), 0), now + 50_000_000)
        } else {
            tone?.startTone(if (accent) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 40)
        }
    }
}
