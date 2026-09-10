# evMonitor — native Android app (Android Auto)

Native Kotlin companion to the PWA so the BMS dashboard can appear on the **Android Auto** head-unit display. The phone talks BLE to the OBD adapter (same ELM327/UDS engine, ported to Kotlin); the car screen renders live tiles via Google's Car App Library (`IOT` category, template UI — free-form dashboards aren't allowed on AA).

## Structure

| File | Role |
|---|---|
| `app/.../Engine.kt` | BLE GATT transport + ELM init + UDS `22` reads + polling loop (Tata `$34xx` DID map), exposed as a `StateFlow` |
| `app/.../car/EvCarAppService.kt` | Android Auto entry point (session/service, IOT category) |
| `app/.../car/DashboardScreen.kt` | Head-unit screen: SOC/SOH, power/current, pack + 12V, cell max/min with numbers, ΔV + temps |
| `app/.../MainActivity.kt` | Phone UI: scan → pick adapter once (MAC persisted), live readout, reconnect/disconnect |

Native BLE connects by saved MAC — persistence is absolute (no chooser ever again), and unlike the web app it can later run with the screen off via a foreground service.

## Build

1. Install [Android Studio](https://developer.android.com/studio) (any recent version)
2. *Open* → select this `android/` folder → let Gradle sync
3. Plug in your phone (USB debugging on) → **Run ▶**

## Get it on the car display (no Play Store needed)

1. On the phone: Android Auto app → tap version number 10× to unlock Developer settings
2. Developer settings → enable **Unknown sources**
3. Connect the phone to the car → **evMonitor** appears in the AA launcher
4. First time: open the phone app once, scan, tap your OBD adapter — after that the car screen auto-connects to the saved adapter whenever AA starts

## Notes / next steps

- Refresh rate on the head unit is throttled by AA template quotas (~1–2 s effective — fine for battery data)
- TODO: foreground service (`FOREGROUND_SERVICE_CONNECTED_DEVICE`) so polling and the 95–100% charge recorder survive screen-off; port the recorder + reports from the PWA
- TODO: charge-watch template variant while charging (big SOC + ETA)
- Play Store distribution would require the `androidx.car.app.category.IOT` review track and a host allow-list instead of `ALLOW_ALL_HOSTS_VALIDATOR`
