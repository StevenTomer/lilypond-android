package org.lilyengine.harness

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.lilyengine.midi.MidiKeyboard
import org.lilyengine.midi.SharedMidiDevice
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * LilyPond's own MIDI device, as another MIDI app sees it: notes played into
 * it reach the app like a keyboard's, and what the app plays comes out.
 */
@RunWith(AndroidJUnit4::class)
class SharedMidiDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var manager: MidiManager
    private lateinit var keyboard: MidiKeyboard
    private var device: MidiDevice? = null
    private val events = LinkedBlockingQueue<MidiKeyboard.Event>()

    @Before
    fun openAsAnotherApp() {
        val m = context.getSystemService(Context.MIDI_SERVICE) as MidiManager?
        assumeTrue("no MIDI on this device", m != null)
        manager = m!!
        keyboard = MidiKeyboard(context)
        keyboard.addListener { events += it }
        keyboard.start()
        @Suppress("DEPRECATION")
        val info = manager.devices.single { SharedMidiDevice.isIt(it) }
        assertEquals(1, info.inputPortCount)
        assertEquals(1, info.outputPortCount)
        val opened = LinkedBlockingQueue<MidiDevice>()
        manager.openDevice(info, { d -> if (d != null) opened += d }, Handler(Looper.getMainLooper()))
        device = opened.poll(10, TimeUnit.SECONDS) ?: error("couldn't open LilyPond's MIDI device")
    }

    @After
    fun close() {
        if (!::keyboard.isInitialized) return
        runCatching { device?.close() }
        keyboard.stop()
    }

    @Test
    fun notesPlayedIntoLilyPondReachTheApp() {
        val port = device!!.openInputPort(0) ?: error("no input port")
        port.use {
            val b = byteArrayOf(0x90.toByte(), 60, 100, 64, 90) // the second in running status
            it.send(b, 0, b.size)
            val off = byteArrayOf(0x80.toByte(), 60, 0, 64, 0)
            it.send(off, 0, off.size)
            val got = List(4) { events.poll(5, TimeUnit.SECONDS) ?: error("event ${it + 1} of 4 didn't arrive") }
            assertEquals(listOf(60, 64), got.filterIsInstance<MidiKeyboard.Event.NoteOn>().map { e -> e.key })
            assertEquals(listOf(60, 64), got.filterIsInstance<MidiKeyboard.Event.NoteOff>().map { e -> e.key })
        }
    }

    @Test
    fun whatLilyPondPlaysComesOut() {
        val heard = LinkedBlockingQueue<List<Int>>()
        val port = device!!.openOutputPort(0) ?: error("no output port")
        port.connect(object : MidiReceiver() {
            override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                heard += (offset until offset + count).map { msg[it].toInt() and 0xFF }
            }
        })
        waitFor("the app to see a listener") { keyboard.appsListening.value == 1 }
        // The on-screen piano: entry gets the note, and it sounds in the listening app.
        keyboard.tap(67, millis = 50)
        assertEquals(67, (events.poll(5, TimeUnit.SECONDS) as MidiKeyboard.Event.NoteOn).key)
        assertEquals(listOf(0x90, 67, 80), heard.poll(5, TimeUnit.SECONDS))
        assertEquals(listOf(0x80, 67, 0), heard.poll(5, TimeUnit.SECONDS))
        port.close()
        waitFor("the listener to be gone") { keyboard.appsListening.value == 0 }
    }

    private fun waitFor(what: String, ok: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000L
        while (!ok()) {
            check(System.nanoTime() < end) { "timed out waiting for $what" }
            Thread.sleep(50)
        }
        assertTrue(ok())
    }
}
