package dev.gklc.evmonitor.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.coroutineScope
import dev.gklc.evmonitor.Engine
import dev.gklc.evmonitor.Telemetry
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Live BMS tiles on the head unit. Data flows from the phone's BLE engine. */
class DashboardScreen(ctx: CarContext) : Screen(ctx) {

    private var job: Job? = null

    init {
        // reconnect to the saved adapter automatically when the car session starts
        Engine.savedMac(ctx)?.let { mac ->
            if (!Engine.state.value.connected) Engine.connect(ctx, mac)
        }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                job = owner.lifecycle.coroutineScope.launch {
                    Engine.state.collect { invalidate() }
                }
            }
            override fun onStop(owner: LifecycleOwner) { job?.cancel() }
        })
    }

    private fun f(t: Telemetry, k: String, dp: Int = 1): String =
        t.v(k)?.let { String.format("%.${dp}f", it) } ?: "—"

    override fun onGetTemplate(): Template {
        val t = Engine.state.value
        val power = t.v("packV")?.let { v -> t.v("packI")?.let { i -> v * i / 1000 } }
        val delta = t.v("delta") ?: run {
            val a = t.v("maxV"); val b = t.v("minV")
            if (a != null && b != null) a - b else null
        }
        val pane = Pane.Builder()
            .addRow(row("Charge", "${f(t, "soc")} %   ·   SOH ${f(t, "soh")} %"))
            .addRow(row("Power", (power?.let { String.format("%.1f kW %s", kotlin.math.abs(it), if (it < 0) "in" else "out") } ?: "—") +
                    "   ·   ${f(t, "packI")} A"))
            .addRow(row("Pack", "${f(t, "packV")} V   ·   12V ${f(t, "lv", 2)}"))
            .addRow(row("Cell max / min",
                (t.v("maxV")?.let { String.format("%.3f", it / 1000) } ?: "—") + " #" + (t.v("maxN")?.toInt() ?: "—") +
                "   ·   " +
                (t.v("minV")?.let { String.format("%.3f", it / 1000) } ?: "—") + " #" + (t.v("minN")?.toInt() ?: "—")))
            .addRow(row("Imbalance ΔV", (delta?.let { "${it.toInt()} mV" } ?: "—") +
                    "   ·   ${f(t, "maxT", 0)} / ${f(t, "minT", 0)} °C"))
            .build()

        return PaneTemplate.Builder(pane)
            .setTitle(t.status)
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    private fun row(title: String, text: String): Row =
        Row.Builder().setTitle(title).addText(text).build()
}
