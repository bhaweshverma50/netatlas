# netatlas — Crowdsourced Cellular Coverage Atlas

**Design document**
Date: 2026-06-13
Status: Approved (brainstorming complete)

---

## 1. Concept

Track real-world mobile-network signal strength and render it as a heatmap, built
from data **crowdsourced by users** (the *Where is my Train* model: data from users,
for users). Where we already have data, average and improve it over time; where we
don't, fall back to existing open data (OpenCelliD/BeaconDB) and replace it with
real readings as they arrive. Cross-reference signal quality by **carrier × network
type × phone model × geographic area**.

## 2. Feasibility verdict

**Viable, with one hard constraint that shapes the architecture.**

The crowdsourcing model is proven at scale — OpenSignal and Tutela (now
Ookla/Comlinkdata) run passive collectors on millions of Android devices, aggregate
overlapping readings, and use ML/rule-based anomaly detection to discard bad data.
There is strong open-source precedent to build on:

- **OpenCelliD** (Unwired Labs) — largest open DB of cell-tower locations, downloadable.
- **TowerCollector** — open-source Android app (F-Droid) that crowdsources readings to
  OpenCelliD/BeaconDB. A working reference for the collector.
- **BeaconDB** — community successor to Mozilla Location Service (shut down 2024).
- **Uber H3** + **MapLibre** — standard open stack for hexagon aggregation and map rendering.

**The hard constraint:** iOS has **no public API** for cellular signal strength.
`CoreTelephony` exposes carrier/radio type but not dBm; the private routines are
sandbox-blocked and any workaround is grounds for App Store rejection. MetricKit's
`MXCellularConditionMetric` gives only aggregate signal *bars*, delivered ~once/day,
not tied to precise location — useless for live collection. Android, by contrast,
exposes everything (`TelephonyManager.getAllCellInfo()`, `CellSignalStrengthLte.getRsrp()`,
real-time `TelephonyCallback`). This is why the entire category is Android-first.

**Consequence:** A truly cross-platform *collector* is impossible. The architecture is
**Android-first collection** + a **cross-platform viewer** (iOS/Android/web); iOS can
contribute only crude proxies (throughput/latency) later.

### Sources
- OpenSignal / Tutela crowdsourcing: tutela.com; crowdsourcingweek.com; arxiv 2511.03016 (data trust)
- OpenCelliD: opencellid.org; TowerCollector: f-droid.org/packages/info.zamojski.soft.towercollector
- Android API: developer.android.com/reference/android/telephony/SignalStrength
- iOS limitation: developer.apple.com/forums/thread/751785, /703371; MetricKit MXCellularConditionMetric docs
- *Where is my Train* model: skytup.com blog; en.wikipedia.org/wiki/Where_Is_My_Train
- H3 + MapLibre heatmaps: deck.gl H3HexagonLayer; Uber H3 docs

## 3. Decisions (from brainstorming)

| Decision | Choice |
|---|---|
| Platform strategy | Native **Android collector** + **cross-platform viewer** (iOS/Android/web); iOS = viewer + optional proxy |
| POC scope | **Full vision** as north star, built as a thin complete vertical slice first, then widened |
| Viewer stack | **Kotlin Multiplatform + Compose Multiplatform** (one language end-to-end, reuses collector models) |
| Backend stack | **Postgres + PostGIS + h3-pg** (Supabase managed to start; swappable to custom) |
| Map rendering | **MapLibre** (native SDK on mobile, GL JS on web — no Mapbox token) |
| Repo | New **public** repo `netatlas` under GitHub `bhaweshverma50` |

## 4. Architecture

Single KMP monorepo:

```
netatlas/
├── shared/            # KMP: data models, API client, H3 helpers, repositories, view-models
├── composeApp/        # Compose Multiplatform UI — the heatmap viewer
│   ├── androidMain/   # + the native collector (telephony is Android-only)
│   ├── iosMain/       # viewer + optional throughput/latency proxy contribution
│   └── wasmJsMain/    # web viewer
├── iosApp/            # iOS host (Xcode)
├── backend/
│   ├── db/            # PostGIS + h3-pg migrations, aggregation SQL/functions
│   └── api/           # ingestion + query endpoints (start: Supabase; swap to custom later)
├── ingest-opencellid/ # importer for OpenCelliD/BeaconDB open tower data
├── docker-compose.yml # local Postgres + PostGIS + h3 for dev
└── docs/plans/
```

One Android app both **collects and views**; iOS/web are viewers (iOS may add proxy data).

### Data flow

```
Android device                     Backend (Postgres/PostGIS/h3)            Any viewer
TelephonyCallback ─┐
LocationProvider ──┼─► reading ──► POST /readings ──► validate/anomaly ──► raw table
phone make/model ──┘   (batched,    (HTTPS, batched)   filter              │
                        offline-                                            ├─► aggregate job (h3) ──► hex_aggregates
                        queued)                          OpenCelliD seed ──►┤   (mean/median/confidence per hex×carrier×network×model)
                                                                            │
                                   GET /hexes?bbox&carrier&network&model ◄──┴──► MapLibre heatmap + filters
```

## 5. Data model

**`signal_readings`** (raw, append-only):
- `device_id` (anonymous salted hash — no account), `ts`
- `lat`, `lng`, `loc_accuracy_m`, `speed_mps`, `h3_r10` (precomputed), `is_moving`
- `mcc`, `mnc`, `carrier_name`, `network_type` (GSM/UMTS/LTE/NR-NSA/NR-SA)
- `signal_dbm` (RSRP/SS-RSRP/RSCP/RSSI by tech), `rsrq`, `sinr`, `asu`, `bars`
- `cell_id`, `tac`, `pci`, `earfcn` (tower identity → links OpenCelliD)
- `phone_make`, `phone_model`, `os_version`, `app_version`, `source` (`crowd`|`opencellid`)

**`cell_towers`** — imported OpenCelliD/BeaconDB towers (`cell_id → lat/lng/range`), for blending + triangulation.

**`hex_aggregates`** — materialized per `(h3_cell, resolution, mcc, mnc, network_type)`:
- `sample_count`, `mean_dbm`, `median_dbm`, `p10_dbm`, `stddev`, `mean_rsrq`, `mean_sinr`
- `confidence` (↑ with sample count, device diversity, recency; ↓ with variance)
- `coverage_class` (no-signal/poor/fair/good/excellent), `contributing_devices`, `last_updated`

**`hex_model_aggregates`** — same, keyed *also* by `phone_model` (Android-model vs Android-model comparisons).

### The three emphasized ideas, mapped
1. **Use existing data, else build/improve** — crowd-backed hex shows aggregated mean (high confidence); empty hex shows modeled estimate from nearest OpenCelliD towers (low confidence). Crowd overrides model as readings arrive.
2. **Average & improve over time** — incremental aggregation (Welford running mean/variance) with recency-weighting; map self-heals.
3. **Phone-model × carrier × area** — `hex_model_aggregates` is exactly this cross-tab.

Plus an **anomaly filter** on ingest (impossible dBm, poor GPS accuracy, speed/teleport checks, per-hex outlier rejection).

## 6. Collection flow (Android)

- **Telephony:** `TelephonyManager` + `TelephonyCallback` (API 31+; `PhoneStateListener` fallback) → `signal_dbm`, `rsrq`, `sinr`, `bars`; `getAllCellInfo()` for cell identity.
- **Location:** `FusedLocationProviderClient`, balanced power; lat/lng + accuracy + speed per reading.
- **Sampling:** event-driven (signal-change callback) + time/distance floor (~5 s / ~25 m) to avoid flooding while stationary. Tag `is_moving` to favor travel-route data.
- **Foreground service** + persistent notification while collecting (required for background location; keeps it visible/honest).
- **Offline-first:** Room queue + WorkManager opportunistic upload — essential since weak/no-coverage areas are the target and upload there will fail.

## 7. Permissions & privacy

- Permissions: `ACCESS_FINE_LOCATION` + `ACCESS_BACKGROUND_LOCATION` (with rationale screen), `READ_PHONE_STATE`, foreground-service-location type.
- **Anonymous by default:** salted random `device_id`; no login, no PII, no IMEI/IMSI.
- **Location fuzzing:** persist coordinates snapped to H3 hex centroid (r10 ≈ 120 m) — never store an exact GPS trail, only "signal X in hex Y."
- Explicit opt-in, clear start/stop, easy data deletion.

## 8. Backend API (thin, swappable)

Start on **Supabase** (managed PostGIS); small surface so it can move to a custom service:
- `POST /readings` — batched ingest → validate + anomaly-filter → insert raw → enqueue aggregation.
- `GET /hexes?bbox=&res=&mcc=&mnc=&network=&model=` — aggregated hexes for visible area + filters (heatmap feed).
- `GET /carriers`, `GET /models` — filter menus.
- **Aggregation:** PostGIS + `h3-pg`; incremental on ingest + periodic recompute for recency-weighting and roll-up r10→r8→r6.
- **OpenCelliD ingest:** refreshable script loads towers into `cell_towers`; modeled-coverage fallback fills empty hexes.

## 9. Viewer (Compose Multiplatform + MapLibre)

- MapLibre native SDK (Android/iOS), MapLibre GL JS (web) — no Mapbox token.
- Heatmap = H3 hexagons colored by `coverage_class`/`mean_dbm`; opacity scaled by `confidence` (modeled-only faint, crowd-backed solid — the map visibly improves).
- Filters: carrier, network type (4G/5G), phone model → re-query `/hexes`.
- Tap a hex → detail sheet (mean/median dBm, sample count, confidence, per-model breakdown).
- Android build also hosts the collector (start/stop + "my contributions").

## 10. Milestones

POC = M1–M3 (a real crowdsourced heatmap loop). M4–M5 complete the full vision.

1. **M1 – Backend skeleton:** docker-compose Postgres+PostGIS+h3, schema migrations, `POST /readings` + `GET /hexes`, aggregation function. Seed with synthetic readings.
2. **M2 – Android collector:** telephony + location + foreground service + offline queue + upload. Walk-test produces real readings.
3. **M3 – Viewer (Android first):** Compose + MapLibre heatmap from `/hexes`, carrier/network filters. End-to-end: walk → upload → hex lights up.
4. **M4 – OpenCelliD blend:** import towers, modeled fallback, confidence-based opacity.
5. **M5 – Phone-model dimension + iOS/web viewer:** `hex_model_aggregates`, model filter, iOS + web viewer builds.

## 11. Testing

- Backend: unit tests on anomaly filter + aggregation math (Welford, recency weighting, confidence) with fixtures.
- Shared KMP: H3 helpers, API client, repository.
- Android: instrumented test mocking `TelephonyManager`/location to verify reading construction + offline-queue/upload.
