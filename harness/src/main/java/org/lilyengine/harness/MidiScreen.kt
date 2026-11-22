package org.lilyengine.harness

import android.content.Context
import android.content.Intent
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * MIDI keyboard spike (roadmap M5): does a USB keyboard (e.g. a Yamaha
 * DGX-670 on its USB TO HOST port, through a USB-C OTG cable) show up, do
 * notes and chords arrive quickly, and does output reach the keyboard?
 */
@Composable
fun MidiScreen() {
    val context = LocalContext.current
    val tester = remember { MidiTester(context) }
    DisposableEffect(Unit) {
        tester.start()
        onDispose { tester.stop() }
    }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        if (!tester.available) {
            Text("This device has no MIDI support (android.software.midi).")
            return@Column
        }
        Text(
            "Connect the keyboard's USB TO HOST port to the tablet with a USB-C OTG cable " +
                "(USB-C to USB-B). Devices appear here when plugged in.",
            fontSize = 13.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            for (info in tester.devices) {
                val name = MidiTester.name(info)
                if (info == tester.connectedInfo) Button(onClick = { tester.disconnect() }) { Text("✓ $name") }
                else OutlinedButton(onClick = { tester.connect(info) }) { Text(name) }
            }
            if (tester.devices.isEmpty()) Text("No MIDI devices found.", modifier = Modifier.padding(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = tester.canSend, onClick = { tester.playTestChord() }) { Text("Play C major on keyboard") }
            Button(onClick = { tester.clear() }) { Text("Clear") }
            Button(onClick = { shareText(context, "LilyPond MIDI test", tester.report()) }) { Text("Share") }
        }
        Text(tester.status, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
        Text(
            tester.log.joinToString("\n"),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        )
    }
}

class MidiTester(private val context: Context) {
    private val manager = context.getSystemService(Context.MIDI_SERVICE) as MidiManager?
    private val main = Handler(Looper.getMainLooper())
    val available get() = manager != null
    val devices = mutableStateListOf<MidiDeviceInfo>()
    val log = mutableStateListOf<String>()
    var status by mutableStateOf("")
    var connectedInfo by mutableStateOf<MidiDeviceInfo?>(null)
    var canSend by mutableStateOf(false)

    private var device: MidiDevice? = null
    private var outPort: MidiOutputPort? = null   // keyboard -> us
    private var inPort: MidiInputPort? = null     // us -> keyboard
    private val held = sortedSetOf<Int>()
    private var chord = mutableListOf<Int>()
    private var chordStart = 0L
    private val latencies = mutableListOf<Double>()
    private var notes = 0

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) { refresh(); status = "Connected: ${name(info)}" }
        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            if (info == connectedInfo) disconnect()
            refresh(); status = "Removed: ${name(info)}"
        }
    }

    @Suppress("DEPRECATION") // getDevicesForTransport needs API 33
    private fun refresh() {
        devices.clear()
        manager?.devices?.let { devices.addAll(it) }
    }

    fun start() {
        manager ?: return
        refresh()
        manager.registerDeviceCallback(callback, main)
        devices.firstOrNull()?.let(::connect)
    }

    fun stop() {
        manager?.unregisterDeviceCallback(callback)
        disconnect()
    }

    fun connect(info: MidiDeviceInfo) {
        disconnect()
        status = "Opening ${name(info)}…"
        manager?.openDevice(info, { dev ->
            if (dev == null) { status = "Could not open ${name(info)}"; return@openDevice }
            device = dev
            connectedInfo = info
            // A device's *output* ports carry data from it to us.
            if (info.outputPortCount > 0) {
                outPort = dev.openOutputPort(0)?.also { it.connect(receiver) }
            }
            if (info.inputPortCount > 0) inPort = dev.openInputPort(0)
            canSend = inPort != null
            status = "Listening to ${name(info)}: play some notes and chords. " +
                "(ports: ${info.outputPortCount} out, ${info.inputPortCount} in)"
        }, main)
    }

    fun disconnect() {
        runCatching { outPort?.close() }
        runCatching { inPort?.close() }
        runCatching { device?.close() }
        outPort = null; inPort = null; device = null
        connectedInfo = null; canSend = false
    }

    fun clear() { log.clear(); latencies.clear(); notes = 0 }

    fun playTestChord() {
        val port = inPort ?: return
        val now = System.nanoTime()
        val on = byteArrayOf(0x90.toByte(), 60, 90, 0x90.toByte(), 64, 90, 0x90.toByte(), 67, 90)
        val off = byteArrayOf(0x80.toByte(), 60, 0, 0x80.toByte(), 64, 0, 0x80.toByte(), 67, 0)
        port.send(on, 0, on.size, now)
        port.send(off, 0, off.size, now + 700_000_000L) // scheduled 0.7 s later
        add("→ sent C major chord to the keyboard")
    }

    fun report(): String = buildString {
        appendLine("LilyPond MIDI test")
        append(SelfTest.deviceInfo())
        appendLine("Device: ${connectedInfo?.let(::name) ?: "none"}")
        appendLine("Notes received: $notes")
        if (latencies.isNotEmpty()) {
            val sorted = latencies.sorted()
            appendLine(String.format("Delivery delay: median %.1f ms, max %.1f ms", sorted[sorted.size / 2], sorted.last()))
        }
        appendLine()
        log.forEach { appendLine(it) }
    }

    private fun add(line: String) {
        log.add(0, line)
        if (log.size > 200) log.removeAt(log.lastIndex)
    }

    /** Parses the byte stream (with running status); runs on a binder thread. */
    private val receiver = object : MidiReceiver() {
        private var runningStatus = 0
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            val arrived = System.nanoTime()
            var i = offset
            val end = offset + count
            while (i < end) {
                var b = msg[i].toInt() and 0xFF
                if (b >= 0xF8) { i++; continue } // clock, active sensing: ignore
                if (b >= 0x80) { runningStatus = b; i++ } else b = runningStatus
                val type = b and 0xF0
                val len = when (type) { 0xC0, 0xD0 -> 1; 0xF0 -> 0; else -> 2 }
                if (type == 0xF0) { // sysex: skip to its end
                    while (i < end && (msg[i].toInt() and 0xFF) != 0xF7) i++
                    i++; continue
                }
                if (i + len > end) break
                val d1 = msg[i].toInt() and 0x7F
                val d2 = if (len == 2) msg[i + 1].toInt() and 0x7F else 0
                i += len
                val delayMs = (arrived - timestamp) / 1e6
                main.post { handle(type, b and 0x0F, d1, d2, timestamp, delayMs) }
            }
        }
    }

    private fun handle(type: Int, channel: Int, d1: Int, d2: Int, timestamp: Long, delayMs: Double) {
        when {
            type == 0x90 && d2 > 0 -> {
                notes++
                if (delayMs in 0.0..1000.0) latencies += delayMs
                held += d1
                // Keys pressed within 60 ms of each other form one chord.
                if (chord.isEmpty() || timestamp - chordStart > 60_000_000L) {
                    flushChord()
                    chordStart = timestamp
                }
                chord += d1
                main.removeCallbacks(flush)
                main.postDelayed(flush, 80)
                add(String.format("note on  %-5s vel %3d  ch %d  (delay %.1f ms)", noteName(d1), d2, channel + 1, delayMs))
            }
            type == 0x80 || type == 0x90 -> held -= d1
            type == 0xB0 && d1 == 64 -> add("sustain pedal ${if (d2 >= 64) "down" else "up"}")
            type == 0xB0 -> add("control $d1 = $d2")
            type == 0xC0 -> add("program change $d1")
            type == 0xE0 -> {} // pitch bend: ignore
        }
    }

    private val flush = Runnable { flushChord() }

    private fun flushChord() {
        if (chord.isEmpty()) return
        val sorted = chord.sorted()
        val text = if (sorted.size == 1) noteName(sorted[0]) else "<${sorted.joinToString(" ") { noteName(it) }}>"
        add("LilyPond: $text")
        chord = mutableListOf()
    }

    companion object {
        fun name(info: MidiDeviceInfo): String =
            info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
                ?: info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT) ?: "MIDI device ${info.id}"

        /** Absolute LilyPond name with sharps (key-aware spelling comes later). MIDI 60 = c'. */
        fun noteName(key: Int): String {
            val names = arrayOf("c", "cis", "d", "dis", "e", "f", "fis", "g", "gis", "a", "ais", "b")
            val marks = key / 12 - 4 // 60 -> 1 ('), 48 -> 0, 36 -> -1 (,)
            return names[key % 12] + if (marks >= 0) "'".repeat(marks) else ",".repeat(-marks)
        }
    }
}

fun shareText(context: Context, subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "Share"))
}
