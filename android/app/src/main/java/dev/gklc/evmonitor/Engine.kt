package dev.gklc.evmonitor

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.coroutines.resume

/**
 * BLE ELM327 transport + UDS reader — Kotlin port of the web app's engine.
 * DID map from Tata TDS KPD_EV_BMS (see /reference/did-map.json).
 */

data class DidEntry(val did: String, val bytes: Int, val f: Double, val o: Double, val sanity500: Boolean = false)

object DidMap {
    // canonical units: soc/soh %, packV V, packI A (+discharge/−charge), cells mV, temps °C, lv V
    val tata34 = mapOf(
        "soc"   to DidEntry("3402", 2, 0.1, 0.0),
        "soh"   to DidEntry("3403", 2, 0.1, 0.0),
        "packV" to DidEntry("3400", 2, 0.1, 0.0, sanity500 = true),
        "packI" to DidEntry("3401", 2, 0.1, -320.0),
        "maxV"  to DidEntry("3415", 2, 1.0, 0.0),
        "minV"  to DidEntry("3417", 2, 1.0, 0.0),
        "maxN"  to DidEntry("3419", 1, 1.0, 0.0),
        "minN"  to DidEntry("341A", 1, 1.0, 0.0),
        "delta" to DidEntry("34D5", 2, 1.0, 0.0),
        "maxT"  to DidEntry("3409", 1, 1.0, -40.0),
        "minT"  to DidEntry("340B", 1, 1.0, -40.0),
        "avgT"  to DidEntry("3412", 1, 1.0, -40.0),
        "bal"   to DidEntry("3479", 1, 1.0, 0.0),
        "lv"    to DidEntry("3492", 2, 0.001, 0.0),
    )
    val fastKeys = listOf("soc", "packV", "packI", "maxV", "minV", "maxN", "minN", "delta")
    val slowKeys = listOf("soh", "maxT", "minT", "avgT", "bal", "lv")
}

data class Telemetry(
    val values: Map<String, Double> = emptyMap(),
    val connected: Boolean = false,
    val status: String = "Not connected",
    val reads: Long = 0,
    val misses: Long = 0,
) {
    fun v(key: String): Double? = values[key]
}

@SuppressLint("MissingPermission")
object Engine {
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val CANDIDATES = listOf(
        Triple("0000fff0-0000-1000-8000-00805f9b34fb", "0000fff2-0000-1000-8000-00805f9b34fb", "0000fff1-0000-1000-8000-00805f9b34fb"),
        Triple("0000ffe0-0000-1000-8000-00805f9b34fb", "0000ffe1-0000-1000-8000-00805f9b34fb", "0000ffe1-0000-1000-8000-00805f9b34fb"),
        Triple("000018f0-0000-1000-8000-00805f9b34fb", "00002af1-0000-1000-8000-00805f9b34fb", "00002af0-0000-1000-8000-00805f9b34fb"),
        Triple("6e400001-b5a3-f393-e0a9-e50e24dcca9e", "6e400002-b5a3-f393-e0a9-e50e24dcca9e", "6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
    ).map { Triple(UUID.fromString(it.first), UUID.fromString(it.second), UUID.fromString(it.third)) }

    val state: StateFlow<Telemetry> get() = _state
    private val _state = MutableStateFlow(Telemetry())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cmdMutex = Mutex()
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var buf = StringBuilder()
    private var responseWaiter: CancellableContinuation<String>? = null
    private var servicesReady: CancellableContinuation<Boolean>? = null
    private var descriptorDone: CancellableContinuation<Boolean>? = null
    private var pollJob: Job? = null
    private var reads = 0L
    private var misses = 0L

    // BMS channel (Tata): TX 785 / RX 78D, extended session 1003
    private const val HDR = "785"
    private const val RX = "78D"

    fun prefs(ctx: Context) = ctx.getSharedPreferences("evm", Context.MODE_PRIVATE)
    fun savedMac(ctx: Context): String? = prefs(ctx).getString("mac", null)

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestMtu(185)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                update { it.copy(connected = false, status = "Disconnected") }
                pollJob?.cancel()
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { g.discoverServices() }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { servicesReady?.resume(status == BluetoothGatt.GATT_SUCCESS); servicesReady = null }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { descriptorDone?.resume(status == BluetoothGatt.GATT_SUCCESS); descriptorDone = null }
        @Deprecated("pre-33 callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) { onChunk(c.value ?: return) }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { onChunk(value) }
    }

    private fun onChunk(bytes: ByteArray) {
        buf.append(String(bytes, Charsets.ISO_8859_1))
        if (buf.contains('>')) {
            val resp = buf.toString(); buf = StringBuilder()
            responseWaiter?.resume(resp); responseWaiter = null
        }
    }

    fun connect(ctx: Context, mac: String) {
        prefs(ctx).edit().putString("mac", mac).apply()
        scope.launch {
            try {
                update { it.copy(status = "Connecting…") }
                val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                val dev = adapter.getRemoteDevice(mac)
                gatt?.close()
                gatt = dev.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
                val ok = withTimeoutOrNull(15000) {
                    suspendCancellableCoroutine { c -> servicesReady = c }
                } ?: false
                if (!ok) { update { it.copy(status = "Service discovery failed") }; return@launch }
                if (!pickCharacteristics()) { update { it.copy(status = "No ELM327 BLE service on this device") }; return@launch }
                update { it.copy(connected = true, status = "Initialising ELM…") }
                initElm()
                update { it.copy(status = "Live · BMS $HDR/$RX") }
                startPolling()
            } catch (e: Exception) {
                update { it.copy(connected = false, status = "Connect failed: ${e.message}") }
            }
        }
    }

    private suspend fun pickCharacteristics(): Boolean {
        val g = gatt ?: return false
        for ((svcU, wrU, ntU) in CANDIDATES) {
            val svc = g.getService(svcU) ?: continue
            val wr = svc.getCharacteristic(wrU) ?: continue
            val nt = svc.getCharacteristic(ntU) ?: continue
            if (enableNotify(nt)) { writeChar = wr; return true }
        }
        // generic fallback: first service exposing a notify + write pair
        for (svc in g.services) {
            val nt = svc.characteristics.firstOrNull { it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 }
            val wr = svc.characteristics.firstOrNull {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            }
            if (nt != null && wr != null && enableNotify(nt)) { writeChar = wr; return true }
        }
        return false
    }

    private suspend fun enableNotify(c: BluetoothGattCharacteristic): Boolean {
        val g = gatt ?: return false
        if (!g.setCharacteristicNotification(c, true)) return false
        val d = c.getDescriptor(CCCD) ?: return true
        @Suppress("DEPRECATION")
        run { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
        return withTimeoutOrNull(4000) { suspendCancellableCoroutine { k -> descriptorDone = k } } ?: false
    }

    private suspend fun send(cmd: String, timeoutMs: Long = 2200): String = cmdMutex.withLock {
        val g = gatt ?: return ""
        val wc = writeChar ?: return ""
        buf = StringBuilder()
        val resp = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { k ->
                responseWaiter = k
                @Suppress("DEPRECATION")
                run {
                    wc.value = (cmd + "\r").toByteArray(Charsets.ISO_8859_1)
                    wc.writeType = if (wc.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    if (!g.writeCharacteristic(wc)) { responseWaiter = null; k.resume("") }
                }
            }
        }
        responseWaiter = null
        resp ?: buf.toString().also { buf = StringBuilder() }
    }

    private suspend fun initElm() {
        send("ATZ", 4000)
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATCAF1", "ATSP6", "ATST64")) send(c, 900)
        send("ATSH$HDR"); send("ATCRA$RX"); send("1003", 1500)
    }

    private fun flatHex(resp: String): String =
        resp.uppercase().replace(Regex("SEARCHING\\.\\.\\."), "")
            .replace(Regex("\\b[0-9A-F]{1,2}:\\s?"), "")
            .replace(Regex("[^0-9A-F]"), "")

    private suspend fun readKey(key: String): Double? {
        val e = DidMap.tata34[key] ?: return null
        val flat = flatHex(send("22" + e.did))
        val idx = flat.indexOf("62" + e.did)
        if (idx < 0) { misses++; return null }
        val hex = flat.substring(idx + 6)
        if (hex.length < e.bytes * 2) { misses++; return null }
        var raw = 0L
        for (i in 0 until e.bytes) raw = (raw shl 8) or hex.substring(i * 2, i * 2 + 2).toLong(16)
        reads++
        var v = raw * e.f + e.o
        if (e.sanity500 && v > 500) v = raw * 0.01
        return v
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            var slow = 0
            var lastTP = 0L
            while (isActive && _state.value.connected) {
                val vals = _state.value.values.toMutableMap()
                for (k in DidMap.fastKeys) readKey(k)?.let { vals[k] = it }
                readKey(DidMap.slowKeys[slow++ % DidMap.slowKeys.size])?.let { vals[DidMap.slowKeys[(slow - 1) % DidMap.slowKeys.size]] = it }
                if (System.currentTimeMillis() - lastTP > 2500) { send("3E80", 800); lastTP = System.currentTimeMillis() }
                update { it.copy(values = vals, reads = reads, misses = misses) }
                delay(400)
            }
        }
    }

    fun disconnect() {
        pollJob?.cancel()
        gatt?.close(); gatt = null
        update { Telemetry(status = "Not connected") }
    }

    private fun update(fn: (Telemetry) -> Telemetry) { _state.value = fn(_state.value) }
}
