# evMonitor

**[⬇ Download the Android app (v1.0)](https://github.com/gklc811/evMonitor/releases/latest)** — native APK, no Play Store needed. Requires a BLE (Bluetooth 4.0+) ELM327 adapter.


Web app that reads live BMS telemetry from EVs over a **Bluetooth LE OBD2 adapter** — no install, runs in Chrome/Edge on Android as a PWA.

Built for the **Tata Punch EV** (works across the Tata EV range; MG and BYD profiles are experimental).

## Features

- **Auto-detection** — reads the VIN over OBD (`0902` / UDS `22 F190`), matches the model from the VIN prefix table, falls back to CAN fingerprinting (`22 3402` on `785/78D` ⇒ Tata BMS)
- **Instrument panel** — SOC, SOH, pack voltage/current/power, max/min cell voltage with cell numbers, ΔV imbalance, max/min/avg battery temperature with probe numbers, balancing state, session energy in/out and Wh/km (odometer from the VECU)
- **Night charge watch** — the same panel morphs into a true-black dimmed view when charging is detected (or via the moon toggle); screen wake-lock keeps polling alive overnight
- **95→100% charge recorder** — LFP imbalance only shows near the top, so recording arms at 95% SOC and stops when the charger does; the report freezes the **top-of-charge snapshot** (ΔV + max/min cell with numbers) before the pack relaxes into the flat zone, plus a ΔV-vs-SOC trace and window extremes; exports CSV
- **Tools** — raw AT/UDS terminal, DID range scanner (hunt for undocumented per-cell arrays), one-shot read of the full TDS parameter list

## Requirements

- **BLE (Bluetooth 4.0+) ELM327-compatible adapter** — e.g. vGate iCar Pro BLE4, vLinker MC+. Classic-Bluetooth-only adapters do **not** work with Web Bluetooth.
- Chrome or Edge on **Android** (or desktop). iOS Safari has no Web Bluetooth (the Bluefy app works).
- Served over **HTTPS** (GitHub Pages works) — Web Bluetooth requires a secure context.

## Deploy

Enable GitHub Pages on this repo (Settings → Pages → deploy from `main`, root). Open the page on your phone, Connect once, then *Add to Home Screen*.

## Reference data (`/reference`)

Protocol intelligence extracted from Tata TDS dealer-tool databases and community field data — kept here for future development:

| File | Contents |
|---|---|
| `did-map.json` | CAN headers + UDS DID tables with scaling for Tata (`$34xx`, `$30xx`), MG (`$B0xx`), SGMW (`$01xx`), BYD, Hyundai E-GMP cell-array blocks, Mahindra; VIN prefix table; discovery presets; BLE adapter UUIDs |
| `tata-ev-ecu-dids.json` | 508 DIDs across the other Tata EV ECUs: MCU (motor), HECU, OBC, DCDC, BCS (battery cooling), Minda OBC/DCDC combo — with ECU addresses |
| `profiles.json` | 23 vehicle spec profiles (pack kWh, cell count/configuration, chemistry, nominal voltage) |
| `NOTES.md` | Protocol summary, per-cell-voltage findings, source provenance |

**Key finding:** Tata's BMS only exposes *min and max* cell voltage (+ which cell) over diagnostic CAN — no per-cell array DID exists, even in the dealer tool. Use the built-in DID scanner to probe for undocumented ranges.

## Architecture notes

Single-file app (`index.html`), no framework. The BLE transport is an isolated class (`Elm`) so it can be swapped for a Capacitor native-BLE plugin later (enables screen-off background recording and an APK build) without touching the rest of the app.

UDS over ISO-TP: `ATSH <hdr>` → `ATCRA <rx>` → `10 03` (extended session) → `22 <DID>` reads, tester-present `3E 80` every 2.5 s. Decode: `value = raw × factor + offset` per `reference/did-map.json`.
