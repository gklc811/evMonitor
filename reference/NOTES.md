# EV Diagnostics Reference — extracted 2026-09-10

Sources analysed:
1. **evds.sayuk.cloud** — `raw/app.js` (v55, 514 KB) + `/api/profiles` (23 vehicle profiles) — field-tested community web app
2. **Tata TDS diagnostic software** (local dump at `~/Desktop/TDS/`) — official Tata dealer databases:
   `KPD_EV_BMS.inf`, `BMS_TacoGotion.mdb`, `Kratos_BMS_DB.inf`, `CESL_EV_BMS.INF` (Access DBs, parsed with `access_parser`)

## Files here
- `profiles.json` — all 23 vehicle profiles (Tata ×13, BYD ×4, MG ×3, Hyundai/Kia ×2, Mahindra ×1): battery kWh, cell counts, chemistry, nominal voltage
- `did-map.json` — complete per-make CAN headers + UDS DID tables with scaling (field-tested + TDS-official, conflicts flagged)
- `raw/app.js`, `raw/evds.html` — original site sources for study

## Protocol in one paragraph
All these EVs answer **UDS ReadDataByIdentifier (0x22)** over ISO-TP on 11-bit 500 kbps CAN (`ATSP6`).
Flow: `ATSH <tx-header>` → `ATCRA <rx-id>` → `1003` (extended session) → `22 <DID>` → response `62 <DID> <data>`.
Keep session alive with `3E80` tester-present every ~2.5 s.

## Key channels
| Vehicle | BMS TX/RX | Other ECUs |
|---|---|---|
| **Tata EV (all)** | **785 / 78D** | VECU 7E3/7EB (odometer DID A001) |
| MG ZS/Comet | 745 / 74D (alt 7E4/7EC) | Gateway 744/74C (odo B921) |
| MG Windsor (SGMW) | 781 / 789 + 744 | |
| BYD Blade | 781 / 789 (little-endian!) | |
| Hyundai/Kia/Mahindra | 7E4 / 7EC | |

## Tata cell-voltage truth
- Documented DIDs expose **only min & max cell** (`3415`/`3417` in mV) plus **which cell** (`3419`/`341A`) and delta (`34D5`).
- evds.sayuk.cloud's per-cell matrix is **interpolated** for all other cells (labelled "EST" in their UI) — they do not read every cell.
- Their probe list for hunting real arrays: `3420, 3421, 3422, 3425, 3430`.
- TDS official DB confirms: no per-cell array DID exists in Tata's own dealer tool either (dealer tool also shows only min/max).
- Scaling conflicts between the two sources (flagged in did-map.json): `3400` pack V (site ×0.01 vs TDS ×0.1), `3409`/`340A` (site: aux 12 V / insulation; TDS: max cell temp / probe no.). Verify on the car.

## Punch EV variants (from profiles.json)
| Variant | Pack | Cells |
|---|---|---|
| Punch EV MR | 25 kWh LFP | 102S5P cylindrical (Octillion) |
| Punch EV LR 35 | 35 kWh LFP | 102S7P cylindrical (Octillion) |
| Punch EV LR 40 | 40.4 kWh LFP | 105S prismatic TACO 120 Ah / 115S Octillion 109 Ah |

## Vehicle auto-detect logic (theirs)
1. `ATSH7DF` + `0902` → VIN (standard OBD-II)
2. Fallback: try `22F190` on headers 7E3, 785, 744, 7E0, 781, 7E4, 745
3. VIN prefix → profile match
4. CAN fingerprint: `223402` answered on 785/78D ⇒ Tata; `220005` on 781 ⇒ BYD; `22B046` on 744 ⇒ MG; `220108` ⇒ Windsor; `220101` ⇒ Hyundai/Kia

## Adapter support (theirs)
- Web Bluetooth BLE with 11 candidate service UUIDs (list in did-map.json) — FFF0/FFE0 clones, vLinker, OBDLink, Nordic UART, etc.
- WiFi OBD via TCP port 35000 / 23 (needs their cloud relay or local proxy — browser can't open raw TCP)
- Init: `ATE0 ATL0 ATH0 ATCAF1 ATSP6 ATST64 ATAR ATSH.. ATCRA.. 1003`

## Verification: the site's per-cell voltages are NOT real (confirmed 2026-09-10)

Code audit of `raw/app.js`:
- `generateDefaultCellModel()` (line ~7544) builds the entire cell array synthetically: every cell except the min/max anchors is `midpoint + sin(i*1.83 + (i%5)) * 0.35 * delta`, clamped strictly inside (minV, maxV). Cell temps likewise from a "center runs warmer" model (`generateCellTemperatureModel`).
- If the car doesn't answer, it **fabricates** the anchors too: cell IDs hardcoded (#15/#51 for Punch EV LR), voltages derived from packV/cellCount.
- The live OBD loop polls only 10 scalars (packV, packI, SOC, SOH, ΔV, maxV, minV, maxID, minID, avgTemp) then calls `generateDefaultCellModel()` — "Re-derive cell model". No cell-array request exists in any live path.
- The replay path (`telemetry_json.cells`) just restores previously synthesized arrays.

TDS side (all 4 official Tata BMS DBs, exhaustive):
- KPD 55 / CESL 81 / TacoGotion 89 / Kratos 174 DIDs — **zero** individually-numbered cell DIDs.
- Largest live-data DID payload anywhere: **6 bytes** (`$3485` BMS_RealTime). A 102-cell array would need ~204 bytes.
- ByteType sub-parameter tables: no per-cell unpacking, only CellBalanceStatus flags.

Conclusion: Tata's BMS firmware does not expose per-cell voltages over diagnostic CAN at all; only min/max + cell numbers are real. Any UI showing all cells is interpolation.

## Extended extraction (for the India EV app)

- `tata-ev-ecu-dids.json` — **508 DIDs** from the other Tata EV ECUs (TDS KPD_EV_* DBs): MCU 158, HECU 191, OBC 14, DCDC 21, BCS 40, Minda OBC/DCDC combo 87. Includes 29-bit ECU addresses (OBC 0x1BDAF1E5, HECU 0x1BDAF927, BCS 0x1BDAF13A, Minda 0x1BDAF18F) — passenger cars likely answer on 11-bit equivalents, probe both.
- Minda combo DB confirms `$30xx` BMS scaling: cell V ×0.01, pack V ×0.1, current ×0.1 −600 A.
- HECU `$0105–0109` "cell voltage" entries are calibration setpoints, not live cell reads.
- `did-map.json` now includes Hyundai/Kia E-GMP cell-array blocks (`220101–22010C` on 7E4/7EC, cell bytes ×0.02 V) — the only India-market platform with genuine per-cell voltages; byte offsets need on-car verification.

## Our own status
- `../index.html` — working prototype (BLE ELM327 + Tata KPD DID polling + DID range scanner). Paused per Gokul's instruction; awaiting direction.
