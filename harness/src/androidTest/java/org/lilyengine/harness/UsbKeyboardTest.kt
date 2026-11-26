package org.lilyengine.harness

import android.content.Context
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.lilyengine.engine.LilyPondEngine
import org.lilyengine.lilytools.LilyTools
import org.lilyengine.midi.ChordCollector
import org.lilyengine.midi.MidiFile
import org.lilyengine.midi.MidiKeyboard
import org.lilyengine.midi.SharedMidiDevice
import org.lilyengine.midi.MidiPlayer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The MIDI keyboard path end to end, with [TestKeyboardService] standing
 * in for a USB keyboard (as a MIDI device of another app, it goes the same
 * way through Android's MIDI service): finding and connecting, notes and chords played,
 * step entry, sending and playback to the keyboard, unplugging and replugging.
 */
@RunWith(AndroidJUnit4::class)
class UsbKeyboardTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var keyboard: MidiKeyboard
    private val events = LinkedBlockingQueue<MidiKeyboard.Event>()

    /** When Android last added or removed a MIDI device (System.nanoTime). */
    @Volatile private var lastDeviceChange = System.nanoTime()
    private val deviceWatch = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) { lastDeviceChange = System.nanoTime() }
        override fun onDeviceRemoved(device: MidiDeviceInfo) { lastDeviceChange = System.nanoTime() }
    }

    @Before
    fun plugIn() {
        val manager = context.getSystemService(Context.MIDI_SERVICE) as MidiManager?
        assumeTrue("no MIDI on this device", manager != null)
        manager!!.registerDeviceCallback(deviceWatch, android.os.Handler(android.os.Looper.getMainLooper()))
        keyboard = MidiKeyboard(context)
        keyboard.addListener { events += it }
        TestKeyboardService.plugIn(context, true)
        keyboard.start()
        awaitConnected()
    }

    @After
    fun unplug() {
        if (!::keyboard.isInitialized) return
        keyboard.stop()
        TestKeyboardService.plugIn(context, false)
        (context.getSystemService(Context.MIDI_SERVICE) as MidiManager).unregisterDeviceCallback(deviceWatch)
    }

    @Test
    fun notesAndChordsArrive() {
        val v = service()
        // A note, then a chord in running status, released with note-on velocity 0.
        v.play(0x90, 60, 100)
        v.play(0x80, 60, 0)
        v.play(0x90, 64, 90, 67, 91, 72, 92)
        v.play(0x90, 64, 0, 67, 0, 72, 0)
        v.play(0xB0, 64, 127) // sustain pedal
        val got = List(9) { events.poll(5, TimeUnit.SECONDS) ?: error("event ${it + 1} of 9 didn't arrive") }
        val noteOn = got.filterIsInstance<MidiKeyboard.Event.NoteOn>()
        assertEquals(listOf(60, 64, 67, 72), noteOn.map { it.key })
        assertEquals(listOf(100, 90, 91, 92), noteOn.map { it.velocity })
        assertEquals(listOf(60, 64, 67, 72), got.filterIsInstance<MidiKeyboard.Event.NoteOff>().map { it.key })
        assertEquals(MidiKeyboard.Event.Control(64, 127, 0), got.last())
        Log.i(TAG, "delay of a played note: ${noteOn.map { it.delayMillis }} ms")
        assertTrue(noteOn.all { it.delayMillis in 0.0..1000.0 })

        // Step entry groups them as played: a note, then a chord.
        val chords = mutableListOf<List<Int>>()
        val collector = ChordCollector { chords += it }
        for (e in got) when (e) {
            is MidiKeyboard.Event.NoteOn -> collector.noteOn(e.key)
            is MidiKeyboard.Event.NoteOff -> collector.noteOff(e.key)
            else -> {}
        }
        assertEquals(listOf(listOf(60), listOf(64, 67, 72)), chords)
    }

    @Test
    fun playedChordIsWrittenIntoTheSecondVoice() = runBlocking {
        val v = service()
        val chords = LinkedBlockingQueue<List<Int>>()
        val collector = ChordCollector { chords += it }
        keyboard.addListener { e ->
            when (e) {
                is MidiKeyboard.Event.NoteOn -> collector.noteOn(e.key)
                is MidiKeyboard.Event.NoteOff -> collector.noteOff(e.key)
                else -> {}
            }
        }
        v.play(0x90, 62, 80, 66, 80) // d' fis', spelled for D major
        v.play(0x80, 66, 0, 62, 0)
        val keys = chords.poll(5, TimeUnit.SECONDS)
        assertEquals(listOf(62, 66), keys)

        val text = "\\relative { \\key d \\major << { a'4 b } \\\\ { d,2 } >> }"
        val at = text.indexOf("d,2")
        val r = LilyTools(context).insert(text, at, keys!!, "2", after = true)
        val after = r.edit.applyTo(text)
        Log.i(TAG, after)
        assertEquals("\\relative { \\key d \\major << { a'4 b } \\\\ { d,2 <d fis> } >> }", after)
    }

    @Test
    fun appSendsAndPlaysToTheKeyboard() = runBlocking {
        val v = service()
        assertTrue("can't send to the keyboard", keyboard.canSend.value)
        v.received.clear()
        keyboard.play(listOf(60, 64), millis = 100)
        val sent = List(2) { v.received.poll(5, TimeUnit.SECONDS) ?: error("nothing sent") }.map { b -> b.map { it.toInt() and 0xFF } }
        assertEquals(listOf(listOf(0x90, 60, 80, 0x90, 64, 80), listOf(0x80, 60, 0, 0x80, 64, 0)), sent)

        // A LilyPond score, played through the keyboard.
        val r = LilyPondEngine(context).render("{ \\tempo 4 = 240 c'4 e' g'2 }", LilyPondEngine.Options(midi = true))
        assertTrue(r.log, r.success)
        val file = MidiFile.read(r.midi.single())
        v.received.clear()
        val done = CountDownLatch(1)
        MidiPlayer(file, { keyboard.send(it) }, onEnd = { done.countDown() }).start()
        assertTrue("playback didn't end", done.await(10, TimeUnit.SECONDS))
        val messages = generateSequence { v.received.poll(1, TimeUnit.SECONDS) }.map { b -> b.map { it.toInt() and 0xFF } }.toList()
        val ons = messages.filter { it[0] and 0xF0 == 0x90 && it[2] > 0 }.map { it[1] }
        val offs = messages.filter { it[0] and 0xF0 == 0x80 || (it[0] and 0xF0 == 0x90 && it[2] == 0) }.map { it[1] }
        assertEquals(messages.toString(), listOf(60, 64, 67), ons)
        assertEquals(setOf(60, 64, 67), offs.toSet())
        assertTrue("no sustain-off at the end", messages.any { it[0] and 0xF0 == 0xB0 && it[1] == 64 && it[2] == 0 })
    }

    @Test
    fun recordInRealTime() = runBlocking {
        val v = service()
        val recorder = Recorder(keyboard, bpm = 240) // a beat is 250 ms
        val main = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { recorder.start(main) }
        val beat = 250L
        val t0 = System.currentTimeMillis() + 4 * beat // after the count-in
        fun at(beats: Double) { val wait = t0 + (beats * beat).toLong() - System.currentTimeMillis(); if (wait > 0) Thread.sleep(wait) }
        // c' d' e' as quarter notes (released a little early), then <g' b'> for two beats.
        for ((i, key) in listOf(60, 62, 64).withIndex()) {
            at(i.toDouble()); v.play(0x90, key, 90)
            at(i + 0.8); v.play(0x80, key, 0)
        }
        at(3.0); v.play(0x90, 67, 90, 71, 90)
        at(5.0); v.play(0x80, 67, 0, 71, 0)
        Thread.sleep(300)
        val played = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { recorder.stop() }
        main.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        Log.i(TAG, "played: $played")
        assertEquals(listOf(60, 62, 64, 67, 71), played.map { it.key }.sorted())

        val src = "{ \\time 4/4 c'4 }"
        val r = org.lilyengine.lilytools.LilyTools(context).record(src, src.indexOf("c'4"), played, 0.25)
        val music = r.edit.applyTo(src).let { it.substring(r.start, r.end) }
        assertEquals("c'4 d'4 e'4 <g' b'>2", music)
    }

    @Test
    fun connectingIsLoggedStepByStep() {
        awaitConnected()
        val log = keyboard.connectionLog.value.joinToString("\n")
        for (step in listOf("connect: ", "device opened after", "output port opened after", "input port opened after")) {
            assertTrue("missing \"$step\" in:\n$log", log.contains(step))
        }
    }

    @Test
    fun unplugAndReplug() {
        TestKeyboardService.plugIn(context, false)
        // Gone from the list too (a late re-listing can show it once more first).
        waitFor("unplugged") { keyboard.connected.value == null && keyboard.devices.value.none(::isTestKeyboard) }
        // The app's own device is never offered as a keyboard.
        assertTrue(keyboard.devices.value.none(SharedMidiDevice::isIt))
        TestKeyboardService.plugIn(context, true)
        awaitConnected()
        // And it works again.
        service().play(0x90, 70, 64)
        val e = events.poll(5, TimeUnit.SECONDS)
        assertEquals(70, (e as MidiKeyboard.Event.NoteOn).key)
    }

    private fun isTestKeyboard(info: MidiDeviceInfo) = MidiKeyboard.name(info).contains(TestKeyboardService.PRODUCT)

    private fun awaitConnected() {
        // Turning the service on or off (plugging in and out) makes Android re-list all
        // of the app's MIDI devices a little later, briefly disconnecting them: wait until
        // the list has been quiet for two seconds, then for the keyboard.
        waitFor("the MIDI devices to settle") { System.nanoTime() - lastDeviceChange > QUIET_NANOS }
        waitFor("the test keyboard to connect") {
            keyboard.connected.value?.let(::isTestKeyboard) == true && keyboard.canSend.value
        }
        Log.i(TAG, "connected to ${MidiKeyboard.name(keyboard.connected.value!!)}")
        events.clear()
    }

    private fun service(): TestKeyboardService.Companion {
        check(TestKeyboardService.running)
        TestKeyboardService.received.clear()
        return TestKeyboardService
    }

    private fun waitFor(what: String, ok: () -> Boolean) {
        val end = System.nanoTime() + 15_000_000_000L
        while (!ok()) {
            check(System.nanoTime() < end) { "timed out waiting for $what (keyboard status: ${keyboard.status.value})" }
            Thread.sleep(50)
        }
    }

    private companion object {
        const val TAG = "UsbKeyboardTest"
        const val QUIET_NANOS = 2_000_000_000L
    }
}
