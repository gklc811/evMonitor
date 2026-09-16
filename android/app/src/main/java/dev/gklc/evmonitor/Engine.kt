package dev.gklc.evmonitor

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.coroutines.resume

/**
 * BLE ELM327 transport + UDS reader — native engine for evMonitor.
 * Reliability features the web app cannot have:
 *  - CONNECTION_PRIORITY_HIGH (short connection interval, drop-resistant)
 *  - autoConnect re-attach: the OS silently reconnects when the adapter is in range
 *  - runs under a foreground service, so polling survives screen-off
 * Regen safety (learned on the car): prefer the DEFAULT diagnostic session;
 * only fall back to extended (10 03 + tester-present) if data DIDs refuse.
 */

data class DidEntry(
    val did: String, val bytes: Int, val f: Double, val o: Double,
    val sanity500: Boolean = false,
    /* physically possible range — anything outside is a misparsed frame, not a reading */
    val lo: Double = -1e9, val hi: Double = 1e9,
)

object DidMap {
    val tata34 = mapOf(
        "soc"   to DidEntry("3402", 2, 0.1, 0.0, lo = 0.0, hi = 100.5),
        "soh"   to DidEntry("3403", 2, 0.1, 0.0, lo = 0.0, hi = 100.5),
        "packV" to DidEntry("3400", 2, 0.1, 0.0, sanity500 = true, lo = 40.0, hi = 900.0),
        "packI" to DidEntry("3401", 2, 0.1, -320.0),
        "maxV"  to DidEntry("3415", 2, 1.0, 0.0, lo = 1000.0, hi = 5000.0),
        "minV"  to DidEntry("3417", 2, 1.0, 0.0, lo = 1000.0, hi = 5000.0),
        "maxN"  to DidEntry("3419", 1, 1.0, 0.0, lo = 0.0, hi = 255.0),
        "minN"  to DidEntry("341A", 1, 1.0, 0.0, lo = 0.0, hi = 255.0),
        "delta" to DidEntry("34D5", 2, 1.0, 0.0, lo = 0.0, hi = 2000.0),
        "maxT"  to DidEntry("3409", 1, 1.0, -40.0, lo = -40.0, hi = 125.0),
        "minT"  to DidEntry("340B", 1, 1.0, -40.0, lo = -40.0, hi = 125.0),
        "avgT"  to DidEntry("3412", 1, 1.0, -40.0, lo = -40.0, hi = 125.0),
        "bal"   to DidEntry("3479", 1, 1.0, 0.0, lo = 0.0, hi = 255.0),
        "lv"    to DidEntry("3492", 2, 0.001, 0.0, lo = 5.0, hi = 20.0),
    )
    // batches capped at 3 DIDs: 4-DID requests need a multiframe TX many clones refuse
    val batches = listOf(listOf("soc", "packV", "packI"), listOf("maxV", "minV", "maxN"), listOf("minN", "delta", "lv"))
    val slowKeys = listOf("soh", "maxT", "minT", "avgT", "bal")
}

data class Telemetry(
    val values: Map<String, Double> = emptyMap(),
    val connected: Boolean = false,
    val status: String = "Not connected",
    val sessMode: String = "—",
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
    private var app: Context? = null
    private var device: BluetoothDevice? = null
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var buf = StringBuilder()
    private val bufLock = Any()
    private var expect: String? = null
    private var responseWaiter: CancellableContinuation<String>? = null
    private var servicesReady: CancellableContinuation<Boolean>? = null
    private var descriptorDone: CancellableContinuation<Boolean>? = null
    private var pollJob: Job? = null
    private var reads = 0L
    private var misses = 0L
    private var sessMode = "default"
    /* current ($3401) decode self-calibration: two field-confirmed KPD encodings, see decode() */
    private var iZero = 0L
    private var iAlt = 0L
    private var iAltN = 0
    private var userDisconnect = false
    /* raw transport mode (rich flavor): the WebView UI drives the ELM/UDS protocol */
    private var rawMode = false
    var onLinkEvent: ((String) -> Unit)? = null
    private var transportReadyCb: ((Boolean) -> Unit)? = null
    private val badDids = HashSet<String>()

    private const val HDR = "785"
    private const val RX = "78D"

    fun prefs(ctx: Context) = ctx.getSharedPreferences("evm", Context.MODE_PRIVATE)
    fun savedMac(ctx: Context): String? = prefs(ctx).getString("mac", null)

    private val gattCb: BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                g.requestMtu(185)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                pollJob?.cancel()
                if (userDisconnect) {
                    update { Telemetry(status = "Not connected") }
                } else {
                    // autoConnect re-attach: the stack reconnects the moment the adapter is back in range
                    update { it.copy(connected = false, status = "Link lost — auto re-attach armed") }
                    if (rawMode) onLinkEvent?.invoke("disconnected")
                    val ctx = app; val dev = device
                    if (ctx != null && dev != null) {
                        scope.launch {
                            delay(300)
                            // fenced: a manual connect/disconnect in the meantime owns the link now
                            if (userDisconnect || gatt !== g) return@launch
                            try { g.close() } catch (e: Exception) {}
                            gatt = dev.connectGatt(ctx, true, gattCb, BluetoothDevice.TRANSPORT_LE)
                        }
                    }
                }
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { g.discoverServices() }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val waiter = servicesReady; servicesReady = null
            val ok = status == BluetoothGatt.GATT_SUCCESS
            if (waiter != null && waiter.isActive) waiter.resume(ok)
            else if (ok) scope.launch { startSession() }   // auto re-attach path (or a waiter that already timed out)
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            val k = descriptorDone; descriptorDone = null                 // clear first, then resume
            if (k != null && k.isActive) k.resume(status == BluetoothGatt.GATT_SUCCESS)
        }
        @Deprecated("pre-33 callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) { onChunk(c.value ?: return) }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { onChunk(value) }
    }

    private fun onChunk(bytes: ByteArray) = synchronized(bufLock) {
        buf.append(String(bytes, Charsets.ISO_8859_1))
        // segment parsing with stale-response rejection (same fix as the web app)
        while (true) {
            val i = buf.indexOf(">")
            if (i < 0) break
            val seg = buf.substring(0, i + 1)
            buf.delete(0, i + 1)
            val w = responseWaiter ?: continue
            if (!w.isActive) { responseWaiter = null; continue }
            val exp = expect
            if (exp != null) {
                val flat = seg.uppercase().replace(Regex("[^0-9A-F]"), "")
                val isErr = seg.contains("NO DATA", true) || seg.contains("ERROR", true) ||
                        seg.contains("?") || seg.contains("STOPPED", true) || flat.contains("7F")
                if (!flat.contains(exp) && !isErr) continue
            }
            responseWaiter = null
            w.resume(seg)
        }
    }

    fun connectRaw(ctx: Context, mac: String, cb: (Boolean) -> Unit) {
        transportReadyCb = cb
        connect(ctx, mac, raw = true)
    }

    /** Everything learned about one car. The adapter gets moved between vehicles, so none
        of it may survive into the next connection. */
    private fun resetVehicleState() {
        badDids.clear(); nullRun.clear()
        multiOk = null
        iZero = 0L; iAlt = 0L; iAltN = 0
        reads = 0; misses = 0
        sessMode = "default"
    }

    fun connect(ctx: Context, mac: String, raw: Boolean = false) {
        app = ctx.applicationContext
        prefs(ctx).edit().putString("mac", mac).apply()
        userDisconnect = false
        rawMode = raw                                   // the caller decides who drives the protocol
        pollJob?.cancel()                               // an old loop must not interleave with the new link's init
        servicesReady = null
        resetVehicleState()
        scope.launch {
            try {
                update { Telemetry(status = "Connecting…") }   // fresh: never carry another car's values
                val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                val dev = adapter.getRemoteDevice(mac)
                device = dev
                gatt?.close()
                gatt = dev.connectGatt(app, false, gattCb, BluetoothDevice.TRANSPORT_LE)
                val ok = withTimeoutOrNull(20000) {
                    suspendCancellableCoroutine { c -> servicesReady = c }
                } ?: false
                servicesReady = null                    // a timed-out waiter must not swallow the next discovery
                if (!ok) {
                    update { it.copy(status = "Adapter not reachable") }
                    transportReadyCb?.invoke(false); transportReadyCb = null
                    return@launch
                }
                startSession()
            } catch (e: Exception) {
                update { it.copy(connected = false, status = "Connect failed: ${e.message}") }
                transportReadyCb?.invoke(false); transportReadyCb = null
            }
        }
    }

    /** Runs on first connect and every silent re-attach: chars → ELM init → session probe → poll. */
    private suspend fun startSession() {
        try {
            if (!pickCharacteristics()) {
                update { it.copy(status = "No ELM327 BLE service on this adapter") }
                transportReadyCb?.invoke(false); transportReadyCb = null
                return
            }
            if (rawMode) {
                update { it.copy(connected = true, status = "Native link · UI drives protocol", sessMode = "raw") }
                app?.let { PollService.start(it) }
                val cb = transportReadyCb
                if (cb != null) { transportReadyCb = null; cb(true) }
                else onLinkEvent?.invoke("reconnected")     // silent re-attach path
                return
            }
            update { it.copy(connected = true, status = "Initialising ELM…") }
            send("ATZ", 4000)
            for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATCAF1", "ATSP6", "ATST32")) send(c, 900)
            send("ATSH$HDR"); send("ATCRA$RX")
            // regen-safe: default session first, extended only if the DIDs refuse
            val socDid = DidMap.tata34.getValue("soc").did
            var ok = flatHex(send("22$socDid", 1200, "62$socDid")).contains("62$socDid")
            sessMode = if (ok) "default" else {
                send("1003", 1200)
                ok = flatHex(send("22$socDid", 1200, "62$socDid")).contains("62$socDid")
                if (ok) "extended" else "default"
            }
            update { it.copy(connected = true, sessMode = sessMode, status = if (ok) "Live · BMS $HDR/$RX · $sessMode" else "BMS not answering (ignition on?)") }
            app?.let { PollService.start(it) }
            if (ok) startPolling()
        } catch (e: Exception) {
            update { it.copy(status = "Session failed: ${e.message}") }
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
        val ok = withTimeoutOrNull(4000) {
            suspendCancellableCoroutine { k ->
                descriptorDone = k                      // waiter first, then the write
                @Suppress("DEPRECATION")
                run {
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    if (!g.writeDescriptor(d)) { descriptorDone = null; k.resume(false) }
                }
            }
        } ?: false
        descriptorDone = null
        return ok
    }

    private suspend fun send(cmd: String, timeoutMs: Long = 1500, expectMarker: String? = null): String = cmdMutex.withLock {
        val g = gatt ?: return ""
        val wc = writeChar ?: return ""
        if (!_state.value.connected && !cmd.startsWith("AT")) return ""
        synchronized(bufLock) { buf = StringBuilder() }
        expect = expectMarker?.uppercase()
        val resp = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { k ->
                responseWaiter = k
                @Suppress("DEPRECATION")
                run {
                    wc.value = (cmd + "\r").toByteArray(Charsets.ISO_8859_1)
                    wc.writeType = if (wc.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    if (!g.writeCharacteristic(wc)) { responseWaiter = null; if (k.isActive) k.resume("") }
                }
            }
        }
        responseWaiter = null; expect = null
        resp ?: synchronized(bufLock) { buf.toString().also { buf = StringBuilder() } }
    }

    private fun flatHex(resp: String): String =
        resp.uppercase().replace(Regex("SEARCHING\\.\\.\\."), "")
            .replace(Regex("\\b[0-9A-F]{1,2}:\\s?"), "")
            .replace(Regex("[^0-9A-F]"), "")

    private fun decode(e: DidEntry, hex: String): Double? {
        if (hex.length < e.bytes * 2) return null
        var raw = 0L
        for (i in 0 until e.bytes) raw = (raw shl 8) or hex.substring(i * 2, i * 2 + 2).toLong(16)
        if (e.did == "3401") {
            /* Always 0.1 A per bit; only the raw value meaning 0 A differs, and the two
               are far enough apart that ±320 A of travel never crosses the gap:
                 zero 3200  → raw 0…6400      (Punch EV)
                 zero 32000 → raw 28800…35200 (Nexon.ev LR, Tigor EV)
               Anything in between is a misparsed frame. */
            // floor 600 (−260 A), not 0: a truncated frame often reads near zero, and at
            // zero the old floor produced a confident −320 A. Nothing real charges that hard.
            val fam = when {
                raw in 600L..9000L -> 3200L
                raw in 24000L..40000L -> 32000L
                else -> return null
            }
            if (kotlin.math.abs((raw - fam) * 0.1) > 700.0) return null   // non-physical: never adopt on it
            if (iZero == 0L) iZero = fam
            else if (fam != iZero) {
                // one frame in the other band is a misparse, not a BMS swap: need a run
                iAltN = if (fam == iAlt) iAltN + 1 else 1
                iAlt = fam
                if (iAltN < 3) return null
                iZero = fam; iAltN = 0
            } else iAltN = 0
            val a = (raw - iZero) * 0.1
            return if (kotlin.math.abs(a) > 700.0) null else a   // non-physical ⇒ bad frame
        }
        var v = raw * e.f + e.o
        if (e.sanity500 && v > 500) v = raw * 0.01
        if (v < e.lo || v > e.hi) return null      // impossible value ⇒ bad frame
        return v
    }

    /** Multi-DID batch read with per-DID NRC blacklist and single-read fallback. */
    private var multiOk: Boolean? = null
    private suspend fun readBatch(keys: List<String>, vals: MutableMap<String, Double>) {
        val entries = keys.mapNotNull { k -> DidMap.tata34[k]?.takeIf { it.did !in badDids }?.let { k to it } }
        if (entries.isEmpty()) return
        if (multiOk == false || entries.size == 1) { for ((k, _) in entries) readOne(k, vals); return }
        val cmd = "22" + entries.joinToString("") { it.second.did }
        val flat = flatHex(send(cmd, 1400, "62" + entries[0].second.did))
        var ok = false        // at least one value decoded
        var parsed = false    // the adapter answered a multi-DID request at all
        val i = flat.indexOf("62")
        if (i >= 0) {
            var p = i + 2; var guard = 0
            while (p + 4 <= flat.length && guard++ < 10) {
                val did = flat.substring(p, p + 4)
                val hit = entries.firstOrNull { it.second.did == did } ?: break
                p += 4
                if (p + hit.second.bytes * 2 > flat.length) break
                parsed = true
                val v = decode(hit.second, flat.substring(p))
                if (v != null) { vals[hit.first] = v; reads++; ok = true } else misses++
                noteDecode(hit.first, v != null, vals)
                p += hit.second.bytes * 2
            }
        }
        // latch the multi-DID verdict on whether the adapter can FRAME the request,
        // never on whether the values passed their bounds
        if (multiOk == null && parsed) multiOk = true
        if (!ok) { if (multiOk == null) multiOk = false; for ((k, _) in entries) readOne(k, vals) }
    }

    private suspend fun readOne(key: String, vals: MutableMap<String, Double>) {
        val e = DidMap.tata34[key] ?: return
        if (e.did in badDids) return
        val resp = send("22" + e.did, 1000, "62" + e.did)
        val flat = flatHex(resp)
        val idx = flat.indexOf("62" + e.did)
        if (idx < 0) {
            misses++
            // only a definitive "not supported" blacklists; busy/pending are transient
            val nrc = Regex("7F22([0-9A-F]{2})").find(flat)?.groupValues?.get(1)
            if (nrc == "31" || nrc == "12") badDids.add(e.did)
            return
        }
        val v = decode(e, flat.substring(idx + 6))
        if (v != null) { vals[key] = v; reads++ } else misses++
        noteDecode(key, v != null, vals)
    }

    /* A value that stops decoding must not linger on screen looking live. A couple of
       bad frames keep the last good reading; a DID that never decodes is dropped. */
    private val nullRun = HashMap<String, Int>()
    private fun noteDecode(key: String, ok: Boolean, vals: MutableMap<String, Double>) {
        if (ok) { nullRun.remove(key); return }
        val n = (nullRun[key] ?: 0) + 1
        nullRun[key] = n
        if (n >= 5) vals.remove(key)
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            var slow = 0
            var lastTP = 0L
            while (isActive && _state.value.connected) {
                val vals = _state.value.values.toMutableMap()
                for (b in DidMap.batches) readBatch(b, vals)
                readOne(DidMap.slowKeys[slow++ % DidMap.slowKeys.size], vals)
                if (sessMode == "extended" && System.currentTimeMillis() - lastTP > 2500) {
                    send("3E80", 800); lastTP = System.currentTimeMillis()
                }
                update { it.copy(values = vals, reads = reads, misses = misses) }
                delay(150)
            }
        }
    }

    /** Raw command from the WebView bridge — expect may be empty. */
    fun rawSend(cmd: String, timeoutMs: Long, expectMarker: String, cb: (String) -> Unit) {
        scope.launch { cb(send(cmd, timeoutMs, expectMarker.ifEmpty { null })) }
    }

    fun disconnect() {
        userDisconnect = true
        resetVehicleState()
        pollJob?.cancel()
        gatt?.close(); gatt = null
        app?.let { PollService.stop(it) }
        update { Telemetry(status = "Not connected") }
    }

    private fun update(fn: (Telemetry) -> Telemetry) { _state.update(fn) }   // CAS: Binder vs IO thread writes cannot lose each other
}
