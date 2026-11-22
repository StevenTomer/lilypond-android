package org.lilyengine.harness

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDeviceService
import android.media.midi.MidiReceiver
import java.util.concurrent.LinkedBlockingQueue

/**
 * A stand-in for a USB MIDI keyboard, for tests on emulators (debug builds
 * only; the product's own MIDI device is SharedMidiDevice):
 * [play] sends bytes as if keys were played, and what the app sends to the
 * keyboard lands in [received]. Runs in the app's own process, so a test
 * reaches it through the companion object.
 *
 * Unplugging (disabling the service) doesn't destroy a running instance, and
 * plugging in again may start another, so the newest one is the keyboard.
 */
class TestKeyboardService : MidiDeviceService() {
    private val input = object : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            received += msg.copyOfRange(offset, offset + count)
        }
    }

    override fun onGetInputPortReceivers(): Array<MidiReceiver> = arrayOf(input)

    override fun onCreate() {
        super.onCreate()
        newest = this
    }

    override fun onDestroy() {
        if (newest === this) newest = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var newest: TestKeyboardService? = null

        /** Messages the app sent to the keyboard, one per send. */
        val received = LinkedBlockingQueue<ByteArray>()

        /** Whether the keyboard is running (it starts when the app opens it). */
        val running: Boolean get() = newest != null

        /** Sends [bytes] to the app, as the keyboard playing. */
        fun play(vararg bytes: Int, timestamp: Long = System.nanoTime()) {
            val b = ByteArray(bytes.size) { bytes[it].toByte() }
            val k = newest ?: error("the test keyboard isn't running")
            k.outputPortReceivers[0].send(b, 0, b.size, timestamp)
        }

        /** The device's name as [org.lilyengine.midi.MidiKeyboard.name] gives it. */
        const val PRODUCT = "Test keyboard"

        /** Plugs the keyboard in (true) or pulls it out (false). */
        fun plugIn(context: Context, on: Boolean) {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, TestKeyboardService::class.java),
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
