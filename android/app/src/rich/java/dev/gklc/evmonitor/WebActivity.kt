package dev.gklc.evmonitor

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.Toast
import org.json.JSONObject

/**
 * app2 ("rich" flavor): the full evMonitor web dashboard rendered in a WebView,
 * with BLE handled by the native Engine (connection priority, autoConnect
 * re-attach, foreground service). The page's transport is swapped by the
 * EvNative bridge — everything else (recorder, reports, charts) is the same
 * code that runs as the PWA.
 */
class WebActivity : Activity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#0B0E13"))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true            // localStorage: reports persist
        web.settings.allowFileAccess = true
        web.addJavascriptInterface(Bridge(), "EvNative")
        Engine.onLinkEvent = { ev ->
            runOnUiThread { web.evaluateJavascript("window.__evEvent && __evEvent(${JSONObject.quote(ev)})", null) }
        }
        setContentView(web)
        ensurePermissions { web.loadUrl("file:///android_asset/index.html") }
    }

    @Deprecated("classic back handling is fine here")
    override fun onBackPressed() {
        // a sheet pushed a history entry: back closes the sheet, not the app
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        Engine.onLinkEvent = null
        super.onDestroy()
    }

    private fun js(code: String) = runOnUiThread { web.evaluateJavascript(code, null) }
    private fun resp(id: String, r: String) = js("window.__evResp(${JSONObject.quote(id)}, ${JSONObject.quote(r)})")

    inner class Bridge {
        @JavascriptInterface
        fun send(id: String, cmd: String, timeoutMs: Int, expect: String) {
            Engine.rawSend(cmd, timeoutMs.toLong().coerceIn(200, 30000), expect) { r -> resp(id, r) }
        }

        @JavascriptInterface
        fun connectSaved(id: String) {
            val mac = Engine.savedMac(this@WebActivity)
            if (mac == null) { resp(id, "no adapter saved — pairing list opening"); runOnUiThread { startScanDialog() }; return }
            Engine.connectRaw(this@WebActivity, mac) { ok -> resp(id, if (ok) "ok" else "adapter not reachable") }
        }

        @JavascriptInterface
        fun openPairing() { runOnUiThread { startScanDialog() } }

        @JavascriptInterface
        fun disconnect() { Engine.disconnect() }
    }

    /* ---------------- pairing: scan dialog, saves the MAC ---------------- */
    @SuppressLint("MissingPermission")
    private fun startScanDialog() {
        val scanner = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
            .adapter?.bluetoothLeScanner ?: run { Toast.makeText(this, "Bluetooth is off", Toast.LENGTH_LONG).show(); return }
        val found = LinkedHashMap<String, String>()
        val labels = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
        val macs = ArrayList<String>()
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val name = r.device.name ?: return
                if (found.put(r.device.address, name) == null) {
                    macs.add(r.device.address)
                    labels.add("$name  (${r.device.address})")
                }
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Pick your OBD adapter")
            .setAdapter(labels) { _, which ->
                Engine.prefs(this).edit().putString("mac", macs[which]).apply()
                Toast.makeText(this, "Saved — tap Connect in the app", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnDismissListener { try { scanner.stopScan(cb) } catch (e: Exception) {} }
        dialog.show()
        scanner.startScan(cb)
        Handler(Looper.getMainLooper()).postDelayed({ try { scanner.stopScan(cb) } catch (e: Exception) {} }, 10000)
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
        pendingAction?.invoke()   // load the UI either way; connect will re-ask if needed
        pendingAction = null
    }
}
