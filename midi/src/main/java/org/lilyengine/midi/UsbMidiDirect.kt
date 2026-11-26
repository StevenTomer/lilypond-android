package org.lilyengine.midi

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread

/**
 * A USB MIDI keyboard spoken to directly, past Android's MIDI service: the
 * app claims the device's MIDI streaming interface and reads and writes its
 * bulk endpoints itself (USB-MIDI 1.0 packets, [UsbMidiPackets]).
 *
 * Some tablets' MIDI service never finishes opening some keyboards' ports
 * (a Galaxy Tab S7 FE on Android 11 with a Yamaha DGX-670: the open waits
 * until the cable is pulled); this way doesn't go through it at all.
 */
class UsbMidiDirect private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val input: UsbEndpoint,
    private val output: UsbEndpoint?,
    private val trace: (String) -> Unit,
    /** MIDI bytes from the keyboard (cable 0), with when they arrived (System.nanoTime). */
    private val onMidi: (bytes: ByteArray, offset: Int, count: Int, timestamp: Long) -> Unit,
) {
    @Volatile private var running = true
    private val writer = HandlerThread("usb-midi-out").apply { start() }
    private val writes = Handler(writer.looper)
    private val reader = Thread({ readLoop() }, "usb-midi-in").apply { isDaemon = true; start() }

    /** Whether the keyboard takes MIDI too (for playing through its own sounds). */
    val canSend: Boolean get() = output != null

    private fun readLoop() {
        val buffer = ByteArray(input.maxPacketSize.coerceAtLeast(64))
        val cables = mutableSetOf<Int>()
        while (running) {
            val n = connection.bulkTransfer(input, buffer, buffer.size, READ_TIMEOUT_MS)
            if (n <= 0) continue // nothing played (timeout), or a hiccup
            val now = System.nanoTime()
            // Which virtual ports speak, once each: the keyboard's notes are expected on cable 0.
            for (i in 0 until n - 3 step 4) {
                val head = buffer[i].toInt() and 0xFF
                if (UsbMidiPackets.length(head) > 0 && cables.add(head shr 4)) trace("USB: first data on cable ${head shr 4}")
            }
            UsbMidiPackets.decode(buffer, n, cable = 0) { b, o, c -> onMidi(b, o, c, now) }
        }
    }

    /** Sends whole MIDI messages at [timestamp] (System.nanoTime): later ones wait their turn. */
    fun send(bytes: ByteArray, timestamp: Long) {
        val out = output ?: return
        val packets = UsbMidiPackets.encode(bytes)
        if (packets.isEmpty()) return
        val delayMs = ((timestamp - System.nanoTime()) / 1_000_000).coerceAtLeast(0)
        writes.postDelayed({ if (running) connection.bulkTransfer(out, packets, packets.size, WRITE_TIMEOUT_MS) }, delayMs)
    }

    fun close() {
        if (!running) return
        running = false
        writes.removeCallbacksAndMessages(null)
        writer.quitSafely()
        runCatching { reader.join(READ_TIMEOUT_MS + 300L) }
        runCatching { connection.releaseInterface(usbInterface) }
        runCatching { connection.close() }
    }

    companion object {
        private const val READ_TIMEOUT_MS = 250
        private const val WRITE_TIMEOUT_MS = 500
        private const val MIDI_STREAMING = 3 // audio class subclass

        /** The device's MIDI streaming interface, with an endpoint the keyboard sends on, if it has one. */
        fun midiInterface(device: UsbDevice): UsbInterface? =
            (0 until device.interfaceCount).map(device::getInterface).firstOrNull {
                it.interfaceClass == UsbConstants.USB_CLASS_AUDIO && it.interfaceSubclass == MIDI_STREAMING && endpoint(it, UsbConstants.USB_DIR_IN) != null
            }

        private fun endpoint(i: UsbInterface, direction: Int): UsbEndpoint? =
            (0 until i.endpointCount).map(i::getEndpoint).firstOrNull {
                it.direction == direction && (it.type == UsbConstants.USB_ENDPOINT_XFER_BULK || it.type == UsbConstants.USB_ENDPOINT_XFER_INT)
            }

        /** Asks the user (once per plugging in) to let the app use [device]; [done] gets the answer, on the main thread. */
        fun requestPermission(context: Context, device: UsbDevice, done: (Boolean) -> Unit) {
            val app = context.applicationContext
            val usb = app.getSystemService(UsbManager::class.java) ?: return done(false)
            if (usb.hasPermission(device)) return done(true)
            val action = app.packageName + ".USB_MIDI_PERMISSION"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    runCatching { app.unregisterReceiver(this) }
                    done(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                }
            }
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(receiver, IntentFilter(action))
            // Mutable: Android adds the device and the answer to it. Explicit: for this app only.
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            usb.requestPermission(device, PendingIntent.getBroadcast(app, 0, Intent(action).setPackage(app.packageName), flags))
        }

        /**
         * Opens [device]'s MIDI interface, taking it from the system's driver; null
         * (and why, in [trace]) if that can't be done. Blocks briefly: not on the main thread.
         */
        fun open(
            context: Context,
            device: UsbDevice,
            trace: (String) -> Unit,
            onMidi: (bytes: ByteArray, offset: Int, count: Int, timestamp: Long) -> Unit,
        ): UsbMidiDirect? {
            val usb = context.applicationContext.getSystemService(UsbManager::class.java) ?: return null
            val intf = midiInterface(device) ?: return null.also { trace("USB: no MIDI streaming interface") }
            val input = endpoint(intf, UsbConstants.USB_DIR_IN)!!
            val output = endpoint(intf, UsbConstants.USB_DIR_OUT)
            trace("USB: interface ${intf.id}, in endpoint ${input.address}, out endpoint ${output?.address ?: "none"}")
            val connection = usb.openDevice(device) ?: return null.also { trace("USB: could not open the device") }
            // force: detach the system's driver from this interface, which is what holds it.
            if (!connection.claimInterface(intf, true)) {
                connection.close()
                trace("USB: could not claim the MIDI interface")
                return null
            }
            trace("USB: MIDI interface claimed")
            return UsbMidiDirect(connection, intf, input, output, trace, onMidi)
        }

        /** Calls [onDetached] when a USB device is unplugged, until the returned function is called. */
        fun watchDetach(context: Context, onDetached: (UsbDevice) -> Unit): () -> Unit {
            val app = context.applicationContext
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)?.let(onDetached)
                }
            }
            val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
            // A system broadcast: exported, or it doesn't arrive.
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(receiver, filter)
            return { runCatching { app.unregisterReceiver(receiver) } }
        }
    }
}
