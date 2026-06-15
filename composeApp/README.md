# composeApp (Android collector)

The Android app captures cellular-coverage readings and uploads them to the backend.

## Collection pipeline (M2.4)

Package `atlas.netatlas.collect`:

- `telephony/CellSampleExtractor` — pure mapper from a framework `CellInfo` (LTE / NR /
  WCDMA / GSM) to a tech-agnostic `RawCellSample`. Side-effect-free and unit-tested.
- `telephony/TelephonyReader` — async serving-cell snapshot (`requestCellInfoUpdate`,
  falling back to `allCellInfo`) plus carrier identity from `networkOperator`.
- `location/LocationReader` — single recent GPS fix via the framework `LocationManager`
  only (no Google Play Services).
- `CollectorService` — foreground service (type `location`) that samples every ~5 s with a
  ~25 m min-distance gate, builds a `SignalReading` via `SignalMapper`, queues it in Room
  (`CollectorDb` / `ReadingDao`), and periodically triggers `UploadWorker`.

Start/stop from anywhere with `CollectorService.start(context)` / `.stop(context)`. The host
Activity is responsible for requesting the runtime location + phone-state permissions first.

## Verifying REAL signal collection (physical device required)

> **Important:** the Android emulator's telephony is **synthetic**. On the emulator
> `requestCellInfoUpdate`/`allCellInfo` returns a fabricated NR cell (and dBm/levels that do
> not correspond to any real network), and there is no SIM, so carrier MCC/MNC are not
> realistic. The emulator smoke test only proves the **pipeline is wired** (service runs,
> location + telephony readers fire, readings are persisted, upload is enqueued) — it does
> **not** validate real signal-strength values.
>
> Real signal-strength collection MUST be verified on a **physical Android phone with an
> active SIM**.

### Run steps on a physical device

1. Connect a phone with USB debugging enabled and an active SIM: `adb devices`.
2. Build + install: `./gradlew :composeApp:installDebug`.
3. Launch the app and tap **Start collection**. Grant the location ("Allow all the time" for
   background) and phone-state permissions when prompted.
4. Move ~25 m+ outdoors so the distance gate releases and fresh GPS fixes arrive.
5. Watch readings being queued: `adb logcat -s netatlas:D` — each insert logs
   `tick: inserted reading dbm=<rsrp> net=<LTE|NR_SA|...> unsent=<n>`. Confirm the dBm,
   network type, and carrier match the phone's actual service.
6. Confirm uploads drain to the backend (the service enqueues `UploadWorker` every few
   readings; point `UploadWorker.BASE_URL` at a reachable backend).

### Emulator smoke test (pipeline-only)

```
./gradlew :composeApp:installDebug
adb shell pm grant atlas.netatlas android.permission.ACCESS_FINE_LOCATION
adb shell pm grant atlas.netatlas android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant atlas.netatlas android.permission.READ_PHONE_STATE
adb shell pm grant atlas.netatlas android.permission.POST_NOTIFICATIONS
adb emu geo fix 77.5946 12.9716
adb shell am start -n atlas.netatlas/atlas.netatlas.MainActivity   # then tap "Start collection"
adb logcat -d -s netatlas:D
```

## Tests

- Unit (no device): `./gradlew :composeApp:testDebugUnitTest` — `CellSampleExtractorTest`.
- Instrumented (emulator/device): `./gradlew :composeApp:connectedDebugAndroidTest`.
