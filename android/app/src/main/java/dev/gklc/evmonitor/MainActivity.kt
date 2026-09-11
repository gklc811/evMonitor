package dev.gklc.evmonitor

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Minimal phone UI: pair the BLE OBD adapter once, then the Android Auto
 * dashboard (and this readout) run from the shared Engine.
 * The rich UI remains the PWA; this app exists for the car display.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var live: TextView
    private lateinit var list: LinearLayout
    private val found = LinkedHashMap<String, String>() // mac -> name
    private var collectJob: Job? = null
    private val uiScope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0E13"))
            setPadding(pad, pad * 2, pad, pad)
        }
        fun label(txt: String, size: Float = 14f, color: String = "#93A1B8") = TextView(this).apply {
            text = txt; textSize = size; setTextColor(Color.parseColor(color))
            typeface = Typeface.MONOSPACE
        }
        val title = label("evMonitor", 24f, "#E8EDF6").apply { typeface = Typeface.DEFAULT_BOLD }
        status = label("Not connected")
        live = label("", 15f, "#E8EDF6")
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val scanBtn = Button(this).apply {
            text = "Scan for OBD adapter"
            setOnClickListener { ensurePermissions { startScan() } }
        }
        val reconnectBtn = Button(this).apply {
            text = "Connect saved adapter"
            setOnClickListener {
                val mac = Engine.savedMac(this@MainActivity)
                if (mac != null) ensurePermissions { Engine.connect(this@MainActivity, mac) }
                else status.text = "No adapter saved yet — scan first"
            }
        }
        val disconnectBtn = Button(this).apply {
            text = "Disconnect"
            setOnClickListener { Engine.disconnect() }
        }

        root.addView(title); root.addView(status)
        root.addView(scanBtn); root.addView(reconnectBtn); root.addView(disconnectBtn)
        root.addView(live); root.addView(list)
        setContentView(ScrollView(this).apply { addView(root) })

        collectJob = uiScope.launch {
            Engine.state.collect { t ->
                status.text = t.status + "   (${t.reads} reads / ${t.misses} misses)"
                val d = t.v("delta") ?: run {
                    val a = t.v("maxV"); val b = t.v("minV")
                    if (a != null && b != null) a - b else null
                }
                live.text = if (!t.connected) "" else buildString {
                    fun f(k: String, dp: Int = 1) = t.v(k)?.let { String.format("%.${dp}f", it) } ?: "—"
                    appendLine("SOC ${f("soc")} %    SOH ${f("soh")} %")
                    appendLine("Pack ${f("packV")} V   ${f("packI")} A")
                    appendLine("Cell max ${t.v("maxV")?.div(1000)?.let { String.format("%.3f", it) } ?: "—"} V #${t.v("maxN")?.toInt() ?: "—"}")
                    appendLine("Cell min ${t.v("minV")?.div(1000)?.let { String.format("%.3f", it) } ?: "—"} V #${t.v("minN")?.toInt() ?: "—"}")
                    appendLine("ΔV ${d?.toInt() ?: "—"} mV    12V ${f("lv", 2)}")
                    appendLine("Temp ${f("maxT", 0)}/${f("minT", 0)}/${f("avgT", 0)} °C   bal raw ${t.v("bal")?.toInt() ?: "—"}")
                }
            }
        }
    }

    /* ---------------- permissions ---------------- */
    private var pendingAction: (() -> Unit)? = null
    private fun ensurePermissions(action: () -> Unit) {
        val needed = if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        else if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action()
        else { pendingAction = action; requestPermissions(missing.toTypedArray(), 1) }
    }
    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (res.all { it == PackageManager.PERMISSION_GRANTED }) pendingAction?.invoke()
        else status.text = "Bluetooth permission denied"
        pendingAction = null
    }

    /* ---------------- scan ---------------- */
    @SuppressLint("MissingPermission")
    private fun startScan() {
        val scanner = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
            .adapter?.bluetoothLeScanner ?: run { status.text = "Bluetooth off"; return }
        found.clear(); list.removeAllViews()
        status.text = "Scanning 8 s…"
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val name = r.device.name ?: return
                if (found.put(r.device.address, name) == null) addRow(r.device.address, name)
            }
        }
        scanner.startScan(cb)
        Handler(Looper.getMainLooper()).postDelayed({
            scanner.stopScan(cb)
            if (status.text.startsWith("Scanning")) status.text = "Pick your adapter (${found.size} found)"
        }, 8000)
    }

    @SuppressLint("MissingPermission")
    private fun addRow(mac: String, name: String) {
        list.addView(Button(this).apply {
            text = "$name  ($mac)"
            gravity = Gravity.START
            setOnClickListener { Engine.connect(this@MainActivity, mac) }
        })
    }

    override fun onDestroy() { collectJob?.cancel(); super.onDestroy() }
}
