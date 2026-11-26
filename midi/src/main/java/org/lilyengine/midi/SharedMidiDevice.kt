package org.lilyengine.midi

import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiDeviceService
import android.media.midi.MidiDeviceStatus
import android.media.midi.MidiReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The app as a MIDI device named "AndroLily" that other MIDI apps on the
 * tablet can see:
 * - What they send to its input ("notes in") is played into LilyPond like a
 *   keyboard: [MidiKeyboard] passes it to its listeners, so note entry and the
 *   MIDI tab take it as they take a USB keyboard.
 * - Its output ("playback out") carries what LilyPond plays (score playback,
 *   the on-screen piano) to apps listening there, e.g. a synthesizer app.
 *
 * Android starts this service when another app opens the device; it runs in
 * the app's own process, and the shared state is in the companion object.
 */
class SharedMidiDevice : MidiDeviceService() {
    private val input = object : MidiReceiver() {
        // Runs on a binder thread.
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            for (l in inputListeners) l(msg, offset, count, timestamp)
        }
    }

    override fun onGetInputPortReceivers(): Array<MidiReceiver> = arrayOf(input)

    override fun onCreate() {
        super.onCreate()
        running = this
    }

    override fun onDestroy() {
        if (running === this) running = null
        _listening.value = 0
        super.onDestroy()
    }

    override fun onDeviceStatusChanged(status: MidiDeviceStatus) {
        _listening.value = status.getOutputPortOpenCount(0)
    }

    companion object {
        @Volatile private var running: SharedMidiDevice? = null
        internal val inputListeners = CopyOnWriteArrayList<(ByteArray, Int, Int, Long) -> Unit>()

        private val _listening = MutableStateFlow(0)
        /** How many apps are listening to LilyPond's output. */
        val listening: StateFlow<Int> = _listening

        /** Sends [bytes] to the apps listening, at [timestamp] (System.nanoTime). */
        fun send(bytes: ByteArray, timestamp: Long = System.nanoTime()) {
            if (_listening.value == 0) return
            runCatching { running?.outputPortReceivers?.get(0)?.send(bytes, 0, bytes.size, timestamp) }
        }

        /** As in res/xml/lilypond_midi_device.xml. */
        const val NAME = "AndroLily"

        /**
         * Whether [info] is this device: by the service behind it, as Android may keep
         * showing a device's old names (until a reboot) after an update renames it.
         */
        @Suppress("DEPRECATION") // getParcelable(String, Class) needs API 33
        fun isIt(info: MidiDeviceInfo): Boolean {
            if (info.type != MidiDeviceInfo.TYPE_VIRTUAL) return false
            val service = info.properties.getParcelable<android.content.pm.ServiceInfo>(SERVICE_INFO)
            if (service != null) return service.name == SharedMidiDevice::class.java.name
            val names = setOf(NAME, OLD_NAME)
            return info.properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER) in names &&
                info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT) in names
        }

        /** Where Android puts a service device's ServiceInfo (MidiDeviceInfo's hidden PROPERTY_SERVICE_INFO). */
        private const val SERVICE_INFO = "service_info"

        /** What the device was called before the app was renamed. */
        private const val OLD_NAME = "LilyPond"
    }
}
