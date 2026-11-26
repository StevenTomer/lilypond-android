package org.lilyengine.harness

import android.content.Context
import android.content.Intent
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.lilyengine.midi.ChordCollector
import org.lilyengine.midi.MidiKeyboard

/**
 * MIDI keyboard check (roadmap M5): does a USB keyboard (e.g. a Yamaha
 * DGX-670 on its USB TO HOST port, through a USB-C OTG cable) show up, do
 * notes and chords arrive quickly, and does output reach the keyboard?
 */
@Composable
fun MidiScreen(keyboard: MidiKeyboard) {
    val context = LocalContext.current
    val devices by keyboard.devices.collectAsState()
    val connected by keyboard.connected.collectAsState()
    val canSend by keyboard.canSend.collectAsState()
    val status by keyboard.status.collectAsState()
    val appsListening by keyboard.appsListening.collectAsState()
    val connectionLog by keyboard.connectionLog.collectAsState()
    val log = remember { mutableStateListOf<String>() }
    val latencies = remember { mutableListOf<Double>() }
    fun add(line: String) {
        log.add(0, line)
        if (log.size > 200) log.removeAt(log.lastIndex)
    }
    DisposableEffect(keyboard) {
        val chords = ChordCollector { keys ->
            add("LilyPond: " + if (keys.size == 1) noteName(keys[0]) else "<${keys.joinToString(" ") { noteName(it) }}>")
        }
        val listener: (MidiKeyboard.Event) -> Unit = { e ->
            when (e) {
                is MidiKeyboard.Event.NoteOn -> {
                    if (e.delayMillis in 0.0..1000.0) latencies += e.delayMillis
                    chords.noteOn(e.key)
                    add(String.format("note on  %-5s vel %3d  ch %d  (delay %.1f ms)", noteName(e.key), e.velocity, e.channel + 1, e.delayMillis))
                }
                is MidiKeyboard.Event.NoteOff -> chords.noteOff(e.key)
                is MidiKeyboard.Event.Control ->
                    add(if (e.number == 64) "sustain pedal ${if (e.value >= 64) "down" else "up"}" else "control ${e.number} = ${e.value}")
                is MidiKeyboard.Event.Program -> add("program change ${e.number}")
            }
        }
        keyboard.addListener(listener)
        onDispose { keyboard.removeListener(listener) }
    }
    fun report() = buildString {
        appendLine("AndroLily MIDI test")
        append(SelfTest.deviceInfo())
        appendLine("Device: ${connected?.let(MidiKeyboard::name) ?: "none"}")
        appendLine("Notes received: ${latencies.size}")
        if (latencies.isNotEmpty()) {
            val sorted = latencies.sorted()
            appendLine(String.format("Delivery delay: median %.1f ms, max %.1f ms", sorted[sorted.size / 2], sorted.last()))
        }
        appendLine("Status: ${keyboard.status.value}")
        appendLine()
        appendLine("Connecting:")
        connectionLog.forEach { appendLine(it) }
        appendLine()
        log.forEach { appendLine(it) }
    }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        if (!keyboard.available) {
            Text("This device has no MIDI support (android.software.midi).")
            return@Column
        }
        Text(
            "Connect the keyboard's USB TO HOST port to the tablet with a USB-C OTG cable " +
                "(USB-C to USB-B). Devices appear here when plugged in, and the Score tab's " +
                "Enter notes mode writes what you play into the source.",
            fontSize = 13.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            // A keyboard spoken to directly leaves Android's list: still show it, as connected.
            for (info in (devices + listOfNotNull(connected)).distinctBy { it.id }) {
                val name = MidiKeyboard.name(info)
                if (info == connected) Button(onClick = { keyboard.disconnect() }) { Text("✓ $name") }
                else OutlinedButton(onClick = { keyboard.connect(info) }) { Text(name) }
            }
            if (devices.isEmpty()) Text("No MIDI devices found.", modifier = Modifier.padding(8.dp))
        }
        Text(
            "Other MIDI apps on this tablet see AndroLily as a MIDI device named \"AndroLily\": what they " +
                "play into it is taken like a keyboard, and what AndroLily plays comes out of it. " +
                when (appsListening) {
                    0 -> "No app is listening now."
                    1 -> "1 app is listening now, so Play in the Score tab can sound there."
                    else -> "$appsListening apps are listening now, so Play in the Score tab can sound there."
                },
            fontSize = 13.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = canSend || appsListening > 0, onClick = {
                keyboard.play(listOf(60, 64, 67), 700)
                add("→ sent C major chord" + listOfNotNull(" to the keyboard".takeIf { canSend }, " to the apps listening".takeIf { appsListening > 0 }).joinToString(" and"))
            }) { Text("Play C major") }
            Button(onClick = { log.clear(); latencies.clear() }) { Text("Clear") }
            Button(onClick = { shareText(context, "AndroLily MIDI test", report()) }) { Text("Share report") }
        }
        Text(status, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
        // How connecting went, step by step and timed (Share sends it too).
        if (connectionLog.isNotEmpty()) Text(
            connectionLog.takeLast(8).joinToString("\n"),
            fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        )
        Text(
            log.joinToString("\n"),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        )
    }
}

/** Absolute LilyPond name with sharps, for the log. MIDI 60 = c'. */
internal fun noteName(key: Int): String {
    val names = arrayOf("c", "cis", "d", "dis", "e", "f", "fis", "g", "gis", "a", "ais", "b")
    val marks = key / 12 - 4 // 60 -> 1 ('), 48 -> 0, 36 -> -1 (,)
    return names[key % 12] + if (marks >= 0) "'".repeat(marks) else ",".repeat(-marks)
}

fun shareText(context: Context, subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "Share"))
}
