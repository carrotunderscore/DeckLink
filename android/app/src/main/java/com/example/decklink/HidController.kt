package com.example.decklink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.ArrayDeque
import java.util.concurrent.Executor

/**
 * Gör telefonen till ett Bluetooth-tangentbord och en mus (HID).
 *
 * Datorn ser den som ett vanligt tangentbord, så piltangenter, Enter och Esc
 * styr MediaLibrary precis som ett fysiskt tangentbord gör. Alla knappar i
 * appen skickar HID-usage-koder via [tap].
 */
class HidController(private val context: Context) {

    companion object {
        const val KEY_ENTER = 0x28
        const val KEY_ESCAPE = 0x29
        const val KEY_BACKSPACE = 0x2A
        const val KEY_TAB = 0x2B
        const val KEY_SPACE = 0x2C
        const val KEY_RIGHT = 0x4F
        const val KEY_LEFT = 0x50
        const val KEY_DOWN = 0x51
        const val KEY_UP = 0x52
        const val KEY_F = 0x09
        const val KEY_J = 0x0D
        const val KEY_K = 0x0E
        const val KEY_L = 0x0F
        const val KEY_ENTER_NUMPAD = 0x58

        const val MOD_SHIFT = 0x02

        /**
         * HID-usage + modifierare för ett tecken, eller null om tecknet inte finns
         * på ett vanligt svenskt tangentbord. Å/Ä/Ö ligger på [ ' ; precis som på
         * ett fysiskt svenskt tangentbord – Windowslayouten avgör tecknet.
         */
        fun usageFor(ch: Char): Pair<Int, Int>? {
            val lower = ch.lowercaseChar()
            if (lower in 'a'..'z') {
                return (0x04 + (lower - 'a')) to if (ch.isUpperCase()) MOD_SHIFT else 0
            }
            if (ch in '1'..'9') return (0x1E + (ch - '1')) to 0
            return when (ch) {
                '0' -> 0x27 to 0
                ' ' -> KEY_SPACE to 0
                '-' -> 0x2D to 0
                '=' -> 0x2E to 0
                '[' -> 0x2F to 0
                ']' -> 0x30 to 0
                '\\' -> 0x31 to 0
                ';' -> 0x33 to 0
                '\'' -> 0x34 to 0
                '`' -> 0x35 to 0
                ',' -> 0x36 to 0
                '.' -> 0x37 to 0
                '/' -> 0x38 to 0
                'å' -> 0x2F to 0
                'ä' -> 0x34 to 0
                'ö' -> 0x33 to 0
                'Å' -> 0x2F to MOD_SHIFT
                'Ä' -> 0x34 to MOD_SHIFT
                'Ö' -> 0x33 to MOD_SHIFT
                '!' -> 0x1E to MOD_SHIFT
                '@' -> 0x1F to MOD_SHIFT
                '#' -> 0x20 to MOD_SHIFT
                '$' -> 0x21 to MOD_SHIFT
                '%' -> 0x22 to MOD_SHIFT
                '^' -> 0x23 to MOD_SHIFT
                '&' -> 0x24 to MOD_SHIFT
                '*' -> 0x25 to MOD_SHIFT
                '(' -> 0x26 to MOD_SHIFT
                ')' -> 0x27 to MOD_SHIFT
                '_' -> 0x2D to MOD_SHIFT
                '+' -> 0x2E to MOD_SHIFT
                ':' -> 0x33 to MOD_SHIFT
                '"' -> 0x34 to MOD_SHIFT
                '?' -> 0x38 to MOD_SHIFT
                else -> null
            }
        }

        // Windows cachar rapport-ID:na från SDP-posten som skapades när den
        // gamla DeckLink-appen parkopplades: mus = 1, tangentbord = 2.
        private const val REPORT_MOUSE = 1
        private const val REPORT_KEYBOARD = 2
        private const val TAG = "DeckLinkHid"

        /** Samma report-layout som den gamla DeckLink-appen: mus (1) + tangentbord (2). */
        private val DESCRIPTOR: ByteArray = intArrayOf(
            // Mus med rullning, report ID 1
            0x05, 0x01, 0x09, 0x02, 0xA1, 0x01,
            0x09, 0x01, 0xA1, 0x00, 0x85, 0x01,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01,
            0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x05, 0x81, 0x01,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38,
            0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
            0xC0, 0xC0,
            // Tangentbord, report ID 2
            0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, 0x02,
            0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01,
            0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
            0x75, 0x08, 0x95, 0x01, 0x81, 0x01,
            0x75, 0x08, 0x95, 0x06, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07,
            0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
            0xC0,
        ).map { it.toByte() }.toByteArray()
    }

    var onStatus: ((String) -> Unit)? = null
    var onLink: ((Boolean) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executor { command -> handler.post(command) }
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val pending = ArrayDeque<Pair<Int, ByteArray>>()
    private var pumping = false
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var registering = false
    private var stopping = false

    val linked: Boolean get() = host != null

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        val bluetooth = adapter ?: return status("Enheten har ingen Bluetooth")
        if (!hasPermission()) return status("Ge DeckLink Bluetooth-behörighet")
        if (!bluetooth.isEnabled) return status("Slå på Bluetooth och tryck Aktivera igen")
        stopping = false
        // Redan registrerad: knappen ska återuppta en tappad länk i stället för
        // att göra ingenting.
        val device = hid
        if (device != null) {
            val current = host
            if (current != null) status("Ansluten till ${safeName(current)}")
            else connectToHost()
            return
        }
        if (registering) return
        registering = true
        status("Startar Bluetooth-tangentbordet…")
        if (!bluetooth.getProfileProxy(context, listener, BluetoothProfile.HID_DEVICE)) {
            registering = false
            status("Kunde inte starta HID-profilen")
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        stopping = true
        handler.removeCallbacksAndMessages(null)
        val device = hid ?: return
        try {
            device.unregisterApp()
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, device)
        } catch (_: Exception) {
        }
        hid = null
        host = null
        onLink?.invoke(false)
    }

    private val listener = object : BluetoothProfile.ServiceListener {
        @SuppressLint("MissingPermission")
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            val device = proxy as? BluetoothHidDevice ?: return
            hid = device
            registering = false
            if (register(device)) return
            // Efter en ominstallation kan en gammal registrering ligga kvar i
            // stacken och då avvisas den nya. Prova en gång till efter att ha
            // avregistrerat den gamla.
            status("Registrerar om tangentbordet…")
            handler.postDelayed({
                if (hid !== device || stopping) return@postDelayed
                try {
                    device.unregisterApp()
                } catch (_: Exception) {
                }
                if (!register(device)) status("Kunde inte registrera tangentbordet – slå av och på Bluetooth")
            }, 700)
        }

        override fun onServiceDisconnected(profile: Int) {
            hid = null
            host = null
            registering = false
            onLink?.invoke(false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun register(device: BluetoothHidDevice): Boolean {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "DeckLink",
            "DeckLink spelkontroll",
            "DeckLink",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            DESCRIPTOR,
        )
        return try {
            device.registerApp(sdp, null, null, executor, callback)
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * Datorn kan sova, vara utom räckvidd eller ha tappat länken. Försök igen
     * några gånger innan appen ger upp och ber användaren trycka Anslut.
     */
    private fun retryConnect(attempt: Int) {
        if (host != null || stopping || attempt > 2) return
        handler.postDelayed({
            if (host != null || stopping || hid == null) return@postDelayed
            connectToHost(auto = true)
            retryConnect(attempt + 1)
        }, 2500L * (attempt + 1))
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        @SuppressLint("MissingPermission")
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (!registered) {
                if (!stopping) status("Bluetooth-tangentbordet är avstängt – tryck Aktivera")
                onLink?.invoke(false)
                return
            }
            status("Redo – anslut till datorn")
            connectToHost(auto = true)
            retryConnect(attempt = 0)
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED && device != null) {
                host = device
                status("Ansluten till ${safeName(device)}")
                onLink?.invoke(true)
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (host == device) host = null
                onLink?.invoke(false)
                status("Frånkopplad – ansluter igen…")
                if (!stopping) retryConnect(attempt = 1)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice): String =
        try {
            device.name ?: "datorn"
        } catch (_: SecurityException) {
            "datorn"
        }

    /** Anslut till den parkopplade datorn (eller första parkopplade enheten). */
    @SuppressLint("MissingPermission")
    fun connectToHost(auto: Boolean = false) {
        val bluetooth = adapter ?: return
        val device = hid ?: run {
            if (!auto) status("Tryck Aktivera först")
            return
        }
        val bonded = try {
            bluetooth.bondedDevices ?: emptySet()
        } catch (_: SecurityException) {
            emptySet()
        }
        if (bonded.isEmpty()) {
            if (!auto) {
                status("Ingen parkopplad dator – gör DeckLink synlig först")
                makeDiscoverable()
            }
            return
        }
        val target = bonded.firstOrNull {
            it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER
        } ?: bonded.first()
        val started = try {
            device.connect(target)
        } catch (_: SecurityException) {
            false
        }
        status(if (started) "Ansluter till ${safeName(target)}…" else "Kunde inte ansluta – parkoppla i Windows")
    }

    /** Gör telefonen synlig så Windows kan parkoppla den. */
    fun makeDiscoverable() {
        try {
            val intent = android.content.Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 180)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            status("Synlig i 3 minuter – parkoppla DeckLink i Windows")
        } catch (_: Exception) {
            status("Kunde inte göra telefonen synlig")
        }
    }

    /** Skickar ett tangenttryck (ned + upp) till datorn. */
    fun tap(usage: Int, modifiers: Int = 0) {
        if (host == null || hid == null) {
            status("Inte ansluten – tryck Aktivera")
            return
        }
        Log.d(TAG, "tap usage=0x${usage.toString(16)} mod=0x${modifiers.toString(16)}")
        queue(REPORT_KEYBOARD, byteArrayOf(modifiers.toByte(), 0, usage.toByte(), 0, 0, 0, 0, 0))
        queue(REPORT_KEYBOARD, byteArrayOf(modifiers.toByte(), 0, 0, 0, 0, 0, 0, 0))
    }

    /** Musrörelse/klick. Skickas direkt, utan kö, så pekaren hänger med. */
    fun mouse(dx: Int, dy: Int, buttons: Int = 0, wheel: Int = 0) {
        val device = host ?: return
        val ok = hid?.sendReport(
            device,
            REPORT_MOUSE,
            byteArrayOf(buttons.toByte(), clamp(dx), clamp(dy), clamp(wheel)),
        )
        if (ok != true) Log.w(TAG, "Kunde inte skicka musrapport")
    }

    private fun clamp(value: Int): Byte = value.coerceIn(-127, 127).toByte()

    private fun queue(reportId: Int, payload: ByteArray) {
        pending.addLast(reportId to payload)
        if (!pumping) pump()
    }

    private fun pump() {
        val next = pending.pollFirst()
        if (next == null) {
            pumping = false
            return
        }
        pumping = true
        val device = host
        if (device != null) {
            val ok = hid?.sendReport(device, next.first, next.second)
            if (ok == true) Log.d(TAG, "HID-rapport ${next.first} skickad") else Log.w(TAG, "Kunde inte skicka HID-rapport ${next.first}")
        }
        handler.postDelayed({ pump() }, 28)
    }

    private fun status(text: String) {
        onStatus?.invoke(text)
    }
}
