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
        /** [timestamp]: when the key came up (System.nanoTime), as the keyboard said, else on arrival. */
        data class NoteOff(val key: Int, val channel: Int, val timestamp: Long = System.nanoTime()) : Event
        data class Control(val number: Int, val value: Int, val channel: Int) : Event
        data class Program(val number: Int, val channel: Int) : Event
    }

    private val manager = context.applicationContext.getSystemService(Context.MIDI_SERVICE) as MidiManager?
    private val main = Handler(Looper.getMainLooper())
    /**
     * Opening a device and its ports are calls into Android's MIDI service that
     * can take long (or hang, with some USB keyboards): never on the main thread.
     */
    private val worker = Handler(android.os.HandlerThread("midi-open").apply { start() }.looper)
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

    @Volatile private var device: MidiDevice? = null
    @Volatile private var fromKeyboard: MidiOutputPort? = null // a device's output ports carry data to us
    @Volatile private var toKeyboard: MidiInputPort? = null
    private var started = false
    /** The device being opened, until it is. */
    @Volatile private var opening: MidiDeviceInfo? = null

    private val _log = MutableStateFlow<List<String>>(emptyList())
    /** What happened while finding and opening devices, timed: for a report when a keyboard won't connect. */
    val connectionLog: StateFlow<List<String>> = _log
    private val clock = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT)
    private fun trace(line: String) {
        android.util.Log.i(TAG, line)
        synchronized(_log) { _log.value = (_log.value + (clock.format(java.util.Date()) + "  " + line)).takeLast(LOG_LINES) }
    }

    /** How many other apps listen to LilyPond's MIDI output ([sendToApps]). */
    val appsListening: StateFlow<Int> = SharedMidiDevice.listening

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) {
            if (isOwnDevice(info)) return
            trace("added: " + describe(info))
            refresh()
            _status.value = "Found ${name(info)}"
            if (device == null) connect(info)
        }

        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            if (isOwnDevice(info)) return
            trace("removed ${name(info)} (id ${info.id})")
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
        trace("connect: " + describe(info))
        disconnect()
        opening = info
        _status.value = "Opening ${name(info)}…"
        val asked = System.nanoTime()
        fun ms() = (System.nanoTime() - asked) / 1_000_000
        // If Android's MIDI service never answers, say so rather than wait in silence.
        main.postDelayed({
            if (opening == info) {
                trace("still opening after ${ms()} ms")
                _status.value = "${name(info)} doesn't answer. Unplug the cable, plug it in again, and tap it once more."
            }
        }, OPEN_TIMEOUT_MS)
        val m = manager ?: return
        // Asked for on the worker, after the closing disconnect() queued there: Android must
        // see the old connection closed before the new one opens, or the late close can end
        // the new one too (it did when a device was re-listed under a new id).
        worker.post { m.openDevice(info, { dev ->
            trace("device ${if (dev == null) "NOT opened" else "opened"} after ${ms()} ms")
            if (opening != info) { dev?.close(); return@openDevice } // another was chosen meanwhile
            if (dev == null) {
                opening = null
                _status.value = "Could not open ${name(info)}"
                return@openDevice
            }
            device = dev
            // The keyboard's notes come out of its output port: what entry and recording need.
            if (info.outputPortCount > 0) {
                _status.value = "Opening ${name(info)}: its notes…"
                trace("opening output port 0")
                fromKeyboard = runCatching { dev.openOutputPort(0) }
                    .onFailure { trace("output port failed: $it") }.getOrNull()?.also { it.connect(receiver) }
                trace("output port ${if (fromKeyboard == null) "NOT opened" else "opened"} after ${ms()} ms")
            }
            if (opening != info) return@openDevice // given up on meanwhile
            if (info.inputPortCount > 0) {
                _status.value = "Opening ${name(info)}: its sound…"
                trace("opening input port 0")
                toKeyboard = runCatching { dev.openInputPort(0) }.onFailure { trace("input port failed: $it") }.getOrNull()
                trace("input port ${if (toKeyboard == null) "NOT opened (another app may have it)" else "opened"} after ${ms()} ms")
            }
            if (opening != info) return@openDevice
            opening = null
            _connected.value = info
            _canSend.value = toKeyboard != null
            _status.value = if (fromKeyboard == null && info.outputPortCount > 0) "Connected to ${name(info)}, but its notes can't be read (port busy?)"
            else "Connected to ${name(info)}"
        }, worker) }
    }

    fun disconnect() {
        if (device != null || opening != null) trace("disconnect ${_connected.value?.id ?: opening?.id}")
        opening = null
        val ports = listOfNotNull(fromKeyboard, toKeyboard)
        val dev = device
        fromKeyboard = null
        toKeyboard = null
        device = null
        _connected.value = null
        _canSend.value = false
        // Closing is a service call too: off the main thread.
        if (ports.isNotEmpty() || dev != null) worker.post {
            ports.forEach { runCatching { it.close() } }
            runCatching { dev?.close() }
        }
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
                0x90 -> if (d2 > 0) Event.NoteOn(d1, d2, channel, timestamp, (arrived - timestamp) / 1e6) else Event.NoteOff(d1, channel, timestamp)
                0x80 -> Event.NoteOff(d1, channel, timestamp)
                0xB0 -> Event.Control(d1, d2, channel)
                0xC0 -> Event.Program(d1, channel)
                else -> null // aftertouch, pitch bend
            }
            if (event != null) main.post { listeners.forEach { it(event) } }
        }
    }

    companion object {
        private const val TAG = "MidiKeyboard"
        private const val OPEN_TIMEOUT_MS = 10_000L
        private const val LOG_LINES = 80

        /** What a device says about itself, for the connection log. */
        fun describe(info: MidiDeviceInfo): String = buildString {
            append(name(info)).append(" (id ").append(info.id).append(", ")
            append(when (info.type) { MidiDeviceInfo.TYPE_USB -> "USB"; MidiDeviceInfo.TYPE_VIRTUAL -> "virtual"; MidiDeviceInfo.TYPE_BLUETOOTH -> "Bluetooth"; else -> "type ${info.type}" })
            append(", ").append(info.outputPortCount).append(" out / ").append(info.inputPortCount).append(" in")
            if (android.os.Build.VERSION.SDK_INT >= 33) append(", protocol ").append(info.defaultProtocol)
            val p = info.properties
            for (k in listOf(MidiDeviceInfo.PROPERTY_MANUFACTURER, MidiDeviceInfo.PROPERTY_PRODUCT, MidiDeviceInfo.PROPERTY_VERSION)) {
                p.getString(k)?.let { append(", ").append(k).append('=').append(it) }
            }
            for (port in info.ports) {
                append(", ").append(if (port.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT) "out" else "in").append(port.portNumber)
                if (port.name.isNotEmpty()) append("=\"").append(port.name).append('"')
            }
            append(")")
        }

        fun name(info: MidiDeviceInfo): String =
            info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
                ?: info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT) ?: "MIDI device ${info.id}"
    }
}
