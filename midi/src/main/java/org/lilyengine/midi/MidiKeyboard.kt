package org.lilyengine.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A MIDI keyboard (USB, e.g. a Yamaha DGX-670 on its USB TO HOST port
 * through a USB-C OTG cable, or Bluetooth once paired): finds devices as
 * they're plugged in, connects to the first one, and passes what is played
 * to the listeners, on the main thread. Can also send to the keyboard.
 *
 * Notes other apps play into LilyPond's own MIDI device ([SharedMidiDevice])
 * reach the listeners the same way, as do the on-screen piano's ([tap]).
 *
 * One instance is shared by the whole app, so a keyboard stays connected
 * while moving between screens.
 */
class MidiKeyboard(context: Context) {
    sealed interface Event {
        /** [delayMillis]: from the keyboard's timestamp to arrival here, a measure of latency. */
        data class NoteOn(val key: Int, val velocity: Int, val channel: Int, val timestamp: Long, val delayMillis: Double) : Event
        data class NoteOff(val key: Int, val channel: Int) : Event
        data class Control(val number: Int, val value: Int, val channel: Int) : Event
        data class Program(val number: Int, val channel: Int) : Event
    }

    private val manager = context.applicationContext.getSystemService(Context.MIDI_SERVICE) as MidiManager?
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(Event) -> Unit>()

    /** False if the device has no MIDI support at all. */
    val available: Boolean get() = manager != null

    private val _devices = MutableStateFlow<List<MidiDeviceInfo>>(emptyList())
    val devices: StateFlow<List<MidiDeviceInfo>> = _devices
    private val _connected = MutableStateFlow<MidiDeviceInfo?>(null)
    val connected: StateFlow<MidiDeviceInfo?> = _connected
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status
    private val _canSend = MutableStateFlow(false)
    val canSend: StateFlow<Boolean> = _canSend

    private var device: MidiDevice? = null
    private var fromKeyboard: MidiOutputPort? = null // a device's output ports carry data to us
    private var toKeyboard: MidiInputPort? = null
    private var started = false
    /** The device being opened, until it is. */
    private var opening: MidiDeviceInfo? = null

    /** How many other apps listen to LilyPond's MIDI output ([sendToApps]). */
    val appsListening: StateFlow<Int> = SharedMidiDevice.listening

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) {
            if (isOwnDevice(info)) return
            refresh()
            _status.value = "Found ${name(info)}"
            if (device == null) connect(info)
        }

        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            if (isOwnDevice(info)) return
            if (info == _connected.value) disconnect()
            refresh()
            _status.value = "Removed ${name(info)}"
        }
    }

    fun addListener(listener: (Event) -> Unit) { listeners += listener }
    fun removeListener(listener: (Event) -> Unit) { listeners -= listener }

    /** Starts watching for devices and connects to the first one with output. */
    fun start() {
        val m = manager ?: return
        if (started) return
        started = true
        SharedMidiDevice.inputListeners += fromApps
        // Watch first, then look: a keyboard plugged in between is then not missed.
        m.registerDeviceCallback(callback, main)
        refresh()
        _devices.value.firstOrNull { it.outputPortCount > 0 }?.let(::connect)
    }

    fun stop() {
        if (!started) return
        started = false
        SharedMidiDevice.inputListeners -= fromApps
        manager?.unregisterDeviceCallback(callback)
        disconnect()
    }

    fun connect(info: MidiDeviceInfo) {
        if (info == opening || info == _connected.value) return // already on its way
        disconnect()
        opening = info
        _status.value = "Opening ${name(info)}…"
        manager?.openDevice(info, { dev ->
            if (opening != info) { dev?.close(); return@openDevice } // another was chosen meanwhile
            opening = null
            if (dev == null) {
                _status.value = "Could not open ${name(info)}"
                return@openDevice
            }
            device = dev
            _connected.value = info
            if (info.outputPortCount > 0) fromKeyboard = dev.openOutputPort(0)?.also { it.connect(receiver) }
            if (info.inputPortCount > 0) toKeyboard = dev.openInputPort(0)
            _canSend.value = toKeyboard != null
            _status.value = "Connected to ${name(info)}"
        }, main)
    }

    fun disconnect() {
        opening = null
        runCatching { fromKeyboard?.close() }
        runCatching { toKeyboard?.close() }
        runCatching { device?.close() }
        fromKeyboard = null
        toKeyboard = null
        device = null
        _connected.value = null
        _canSend.value = false
    }

    /** Sends MIDI bytes to the keyboard, at [timestamp] (System.nanoTime) or now. */
    fun send(bytes: ByteArray, timestamp: Long = System.nanoTime()) {
        toKeyboard?.send(bytes, 0, bytes.size, timestamp)
    }

    /** Sends MIDI bytes to the apps listening to LilyPond's own MIDI device. */
    fun sendToApps(bytes: ByteArray, timestamp: Long = System.nanoTime()) = SharedMidiDevice.send(bytes, timestamp)

    /**
     * A key of the on-screen piano, tapped: the listeners get it as pressed
     * and released at once (so note entry takes it now), and it sounds for
     * [millis] on the keyboard and in the apps listening.
     */
    fun tap(key: Int, millis: Long = 300, velocity: Int = 80) {
        val now = System.nanoTime()
        listeners.forEach { it(Event.NoteOn(key, velocity, 0, now, 0.0)) }
        listeners.forEach { it(Event.NoteOff(key, 0)) }
        play(listOf(key), millis, velocity)
    }

    /** Sounds [keys] on the keyboard, and in the apps listening, for [millis]. */
    fun play(keys: List<Int>, millis: Long = 500, velocity: Int = 80) {
        if (keys.isEmpty()) return
        val now = System.nanoTime()
        val on = keys.flatMap { listOf(0x90, it, velocity) }.map { it.toByte() }.toByteArray()
        val off = keys.flatMap { listOf(0x80, it, 0) }.map { it.toByte() }.toByteArray()
        send(on, now)
        send(off, now + millis * 1_000_000)
        sendToApps(on, now)
        sendToApps(off, now + millis * 1_000_000)
    }

    @Suppress("DEPRECATION") // getDevicesForTransport needs API 33
    private fun refresh() {
        _devices.value = manager?.devices?.filterNot(::isOwnDevice).orEmpty()
    }

    /** LilyPond's own MIDI device: what it gets is ours already, so it isn't a keyboard to connect to. */
    private fun isOwnDevice(info: MidiDeviceInfo): Boolean = SharedMidiDevice.isIt(info)

    private val receiver = object : MidiReceiver() {
        private val parser = MidiParser()

        // Runs on a binder thread.
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) = dispatch(parser, msg, offset, count, timestamp)
    }

    /** Notes other apps play into LilyPond's MIDI device; their own parser, as running status is per stream. */
    private val fromApps = object : (ByteArray, Int, Int, Long) -> Unit {
        private val parser = MidiParser()
        override fun invoke(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            synchronized(parser) { dispatch(parser, msg, offset, count, timestamp) }
        }
    }

    private fun dispatch(parser: MidiParser, msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
        val arrived = System.nanoTime()
        parser.parse(msg, offset, count) { status, d1, d2 ->
            val channel = status and 0x0F
            val event = when (status and 0xF0) {
                0x90 -> if (d2 > 0) Event.NoteOn(d1, d2, channel, timestamp, (arrived - timestamp) / 1e6) else Event.NoteOff(d1, channel)
                0x80 -> Event.NoteOff(d1, channel)
                0xB0 -> Event.Control(d1, d2, channel)
                0xC0 -> Event.Program(d1, channel)
                else -> null // aftertouch, pitch bend
            }
            if (event != null) main.post { listeners.forEach { it(event) } }
        }
    }

    companion object {
        fun name(info: MidiDeviceInfo): String =
            info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
                ?: info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT) ?: "MIDI device ${info.id}"
    }
}
