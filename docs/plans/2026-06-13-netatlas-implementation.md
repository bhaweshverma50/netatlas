# netatlas Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build the POC of a crowdsourced cellular-coverage heatmap — Android device collects real signal-strength readings, a Kotlin/Ktor backend aggregates them into H3 hexagons, and a Compose Multiplatform viewer renders the heatmap.

**Architecture:** Single Kotlin Multiplatform monorepo. `shared` holds models + API client + H3 helpers. A Ktor backend (reusing `shared` models) ingests batched readings into Postgres/PostGIS, filters anomalies, computes H3 indexes via the `com.uber:h3` JVM library, and serves aggregated hexes. The Android target of `composeApp` both collects (native telephony + location) and views; iOS/web are viewers (Phase 2).

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Ktor (server + client), Postgres 16 + PostGIS, `com.uber:h3` (4.x), Room (offline queue), WorkManager, MapLibre, JUnit5 + Testcontainers, Docker Compose.

**Scope of this plan:** POC = M1 (backend) → M2 (Android collector) → M3 (Android viewer). Phase 2 (M4 OpenCelliD blend, M5 phone-model dimension + iOS/web viewers) is outlined at the end, not yet broken into TDD tasks.

**Design doc:** `docs/plans/2026-06-13-netatlas-design.md`

---

## Prerequisites (verify before starting)

**Step P.1: Verify toolchain**

Run each; all must succeed:
```bash
java -version          # expect JDK 17+
docker --version       # expect Docker present and daemon running
docker compose version # expect v2+
```
Android SDK / Android Studio (Hedgehog+) needed for M2/M3 device work. If `java` is missing, install Temurin 17.

**Step P.2: Verify Docker daemon runs**

Run: `docker run --rm hello-world`
Expected: "Hello from Docker!"

If any prerequisite fails, STOP and report to the user — do not improvise installs.

---

## Phase 0: Project Scaffold

### Task 0.1: Gradle KMP + version catalog

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`
- Create: `shared/build.gradle.kts`, `backend/build.gradle.kts`, `composeApp/build.gradle.kts`

**Step 1: Scaffold with the KMP wizard, then trim.**

Easiest reliable path: generate a base with the Compose Multiplatform template, then add a `backend` JVM module. Either use Android Studio's "Kotlin Multiplatform" template (targets: Android, iOS, Web/Wasm, plus shared) into this `netatlas/` dir, OR hand-write the Gradle files below.

`settings.gradle.kts`:
```kotlin
rootProject.name = "netatlas"
include(":shared", ":composeApp", ":backend")
```

`gradle/libs.versions.toml` (key entries — pin latest stable at implementation time):
```toml
[versions]
kotlin = "2.1.0"
ktor = "3.0.0"
exposed = "0.56.0"
postgres = "42.7.4"
h3 = "4.1.1"
testcontainers = "1.20.4"
room = "2.7.0"
workmanager = "2.10.0"
compose-mp = "1.7.0"
maplibre-android = "11.5.0"

[libraries]
ktor-server-core = { module = "io.ktor:ktor-server-core", version.ref = "ktor" }
ktor-server-netty = { module = "io.ktor:ktor-server-netty", version.ref = "ktor" }
ktor-server-content-negotiation = { module = "io.ktor:ktor-server-content-negotiation", version.ref = "ktor" }
ktor-serialization-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-cio = { module = "io.ktor:ktor-client-cio", version.ref = "ktor" }
exposed-core = { module = "org.jetbrains.exposed:exposed-core", version.ref = "exposed" }
exposed-jdbc = { module = "org.jetbrains.exposed:exposed-jdbc", version.ref = "exposed" }
postgres-driver = { module = "org.postgresql:postgresql", version.ref = "postgres" }
h3 = { module = "com.uber:h3", version.ref = "h3" }
testcontainers-postgres = { module = "org.testcontainers:postgresql", version.ref = "testcontainers" }
```

**Step 2: Verify build configures.**

Run: `./gradlew :shared:tasks --offline` (after first online resolve, drop `--offline`)
Expected: task list prints, no configuration errors.

**Step 3: Commit**
```bash
git add -A && git commit -m "chore: scaffold KMP monorepo (shared, composeApp, backend)"
```

### Task 0.2: docker-compose for Postgres+PostGIS

**Files:**
- Create: `docker-compose.yml`
- Create: `backend/db/migrations/V1__enable_postgis.sql`

`docker-compose.yml`:
```yaml
services:
  db:
    image: postgis/postgis:16-3.4
    environment:
      POSTGRES_DB: netatlas
      POSTGRES_USER: netatlas
      POSTGRES_PASSWORD: netatlas
    ports: ["5432:5432"]
    volumes: ["./backend/db/data:/var/lib/postgresql/data"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U netatlas"]
      interval: 5s
      timeout: 3s
      retries: 10
```

`V1__enable_postgis.sql`:
```sql
CREATE EXTENSION IF NOT EXISTS postgis;
```

**Step: Verify DB comes up.**
Run: `docker compose up -d && sleep 8 && docker compose exec -T db psql -U netatlas -c "SELECT postgis_version();"`
Expected: a PostGIS version row. Then `docker compose down`.

**Commit:** `git add -A && git commit -m "chore: add postgis docker-compose + postgis extension migration"`

---

## Phase M1: Backend (ingest → aggregate → serve)

### Task M1.1: Shared data models

**Files:**
- Create: `shared/src/commonMain/kotlin/atlas/model/Models.kt`
- Test: `shared/src/commonTest/kotlin/atlas/model/ModelsTest.kt`

**Step 1: Write failing serialization test**
```kotlin
class ModelsTest {
    @Test fun reading_roundtrips_json() {
        val r = SignalReading(
            deviceId = "dev1", tsEpochMs = 1_700_000_000_000,
            lat = 12.97, lng = 77.59, locAccuracyM = 8.0, speedMps = 0.0, isMoving = false,
            mcc = 404, mnc = 45, carrierName = "Airtel", networkType = NetworkType.LTE,
            signalDbm = -95, rsrq = -10, sinr = 12, asu = 16, bars = 3,
            cellId = 12345L, tac = 678, pci = 90, earfcn = 1850,
            phoneMake = "Samsung", phoneModel = "SM-S911B",
            osVersion = "14", appVersion = "0.1.0", source = Source.CROWD)
        val json = Json.encodeToString(r)
        assertEquals(r, Json.decodeFromString<SignalReading>(json))
    }
}
```

**Step 2: Run → FAIL** (`SignalReading` undefined).
Run: `./gradlew :shared:allTests --tests "*ModelsTest*"`

**Step 3: Implement models**
```kotlin
@Serializable enum class NetworkType { GSM, UMTS, LTE, NR_NSA, NR_SA, UNKNOWN }
@Serializable enum class Source { CROWD, OPENCELLID }

@Serializable
data class SignalReading(
    val deviceId: String, val tsEpochMs: Long,
    val lat: Double, val lng: Double, val locAccuracyM: Double,
    val speedMps: Double, val isMoving: Boolean,
    val mcc: Int, val mnc: Int, val carrierName: String, val networkType: NetworkType,
    val signalDbm: Int, val rsrq: Int?, val sinr: Int?, val asu: Int?, val bars: Int,
    val cellId: Long?, val tac: Int?, val pci: Int?, val earfcn: Int?,
    val phoneMake: String, val phoneModel: String,
    val osVersion: String, val appVersion: String, val source: Source = Source.CROWD,
)

@Serializable data class ReadingBatch(val readings: List<SignalReading>)

@Serializable
data class HexAggregate(
    val h3: String, val resolution: Int,
    val mcc: Int, val mnc: Int, val networkType: NetworkType,
    val sampleCount: Int, val meanDbm: Double, val medianDbm: Double,
    val stddev: Double, val confidence: Double,
    val coverageClass: CoverageClass, val centerLat: Double, val centerLng: Double,
)
@Serializable enum class CoverageClass { NO_SIGNAL, POOR, FAIR, GOOD, EXCELLENT }
```

**Step 4: Run → PASS.** **Step 5: Commit** `feat(shared): signal reading + hex aggregate models`.

### Task M1.2: Anomaly filter (pure logic, TDD)

**Files:**
- Create: `shared/src/commonMain/kotlin/atlas/ingest/AnomalyFilter.kt`
- Test: `shared/src/commonTest/kotlin/atlas/ingest/AnomalyFilterTest.kt`

**Step 1: Failing tests** — cover each rule:
```kotlin
class AnomalyFilterTest {
    private val ok = validReading()  // helper builds a plausible reading
    @Test fun accepts_plausible() = assertTrue(AnomalyFilter.isValid(ok).accepted)
    @Test fun rejects_dbm_too_high() = assertFalse(AnomalyFilter.isValid(ok.copy(signalDbm = 10)).accepted)
    @Test fun rejects_dbm_too_low() = assertFalse(AnomalyFilter.isValid(ok.copy(signalDbm = -200)).accepted)
    @Test fun rejects_poor_gps() = assertFalse(AnomalyFilter.isValid(ok.copy(locAccuracyM = 500.0)).accepted)
    @Test fun rejects_impossible_speed() = assertFalse(AnomalyFilter.isValid(ok.copy(speedMps = 200.0)).accepted)
    @Test fun rejects_bad_latlng() = assertFalse(AnomalyFilter.isValid(ok.copy(lat = 99.0)).accepted)
}
```

**Step 2: Run → FAIL.**

**Step 3: Implement**
```kotlin
object AnomalyFilter {
    data class Result(val accepted: Boolean, val reason: String? = null)
    fun isValid(r: SignalReading): Result {
        if (r.lat !in -90.0..90.0 || r.lng !in -180.0..180.0) return Result(false, "bad_latlng")
        if (r.signalDbm !in -140..-30) return Result(false, "dbm_out_of_range")
        if (r.locAccuracyM > 100.0) return Result(false, "poor_gps")     // tune later
        if (r.speedMps > 140.0) return Result(false, "impossible_speed") // ~500 km/h
        if (r.bars !in 0..4) return Result(false, "bad_bars")
        return Result(true)
    }
}
```

**Step 4: Run → PASS.** **Step 5: Commit** `feat(ingest): reading anomaly filter with range/gps/speed rules`.

### Task M1.3: Aggregation math (Welford + confidence, TDD)

**Files:**
- Create: `shared/src/commonMain/kotlin/atlas/aggregate/Aggregation.kt`
- Test: `shared/src/commonTest/kotlin/atlas/aggregate/AggregationTest.kt`

**Step 1: Failing tests**
```kotlin
class AggregationTest {
    @Test fun mean_and_stddev_match_known_values() {
        val acc = listOf(-90, -100, -95, -85).fold(RunningStats()) { a, d -> a.add(d.toDouble()) }
        assertEquals(-92.5, acc.mean, 1e-6); assertEquals(4, acc.count)
        assertEquals(5.59, acc.stddev, 0.01)
    }
    @Test fun confidence_rises_with_samples() {
        val low = Aggregation.confidence(count = 1, deviceCount = 1, stddev = 8.0)
        val high = Aggregation.confidence(count = 50, deviceCount = 10, stddev = 2.0)
        assertTrue(high > low); assertTrue(high in 0.0..1.0)
    }
    @Test fun coverage_class_thresholds() {
        assertEquals(CoverageClass.EXCELLENT, Aggregation.classify(-75.0))
        assertEquals(CoverageClass.POOR, Aggregation.classify(-115.0))
    }
}
```

**Step 2: Run → FAIL.**

**Step 3: Implement** Welford `RunningStats`, `confidence()` (monotone in count & deviceCount, decreasing in stddev, clamped 0..1), `classify()` (RSRP thresholds: ≥-80 EXCELLENT, -90 GOOD, -100 FAIR, -110 POOR, else NO_SIGNAL).

**Step 4: Run → PASS.** **Step 5: Commit** `feat(aggregate): running stats, confidence score, coverage classifier`.

### Task M1.4: DB schema migration

**Files:**
- Create: `backend/db/migrations/V2__schema.sql`

Tables `signal_readings`, `hex_aggregates`, `hex_model_aggregates`, `cell_towers` per design doc §5. Key columns: `h3_r10 BIGINT` index, `geom geography(Point)` for PostGIS bbox queries, composite indexes on `(mcc, mnc, network_type)` and `h3_r10`. Include the unique constraint on `hex_aggregates (h3_r10, mcc, mnc, network_type)`.

**Step: Verify migration applies.**
Run against the compose DB: `docker compose up -d && for f in backend/db/migrations/V*.sql; do docker compose exec -T db psql -U netatlas -f - < "$f"; done && docker compose exec -T db psql -U netatlas -c "\dt"`
Expected: all four tables listed.

**Commit:** `feat(backend): core schema migration (readings, aggregates, towers)`.

### Task M1.5: H3 helper (JVM)

**Files:**
- Create: `backend/src/main/kotlin/atlas/backend/geo/H3.kt`
- Test: `backend/src/test/kotlin/atlas/backend/geo/H3Test.kt`

**Step 1: Failing test**
```kotlin
class H3Test {
    @Test fun lat_lng_to_cell_is_stable() {
        val a = H3.cell(12.9716, 77.5946, 10)
        val b = H3.cell(12.9716, 77.5946, 10)
        assertEquals(a, b)
    }
    @Test fun center_round_trips_into_same_cell() {
        val cell = H3.cell(12.9716, 77.5946, 10)
        val (lat, lng) = H3.center(cell)
        assertEquals(cell, H3.cell(lat, lng, 10))
    }
}
```

**Step 2: Run → FAIL.**

**Step 3: Implement** thin wrapper over `com.uber.h3core.H3Core` (`latLngToCell`, `cellToLatLng`, `cellToBoundary`).

**Step 4: Run → PASS.** **Step 5: Commit** `feat(backend): H3 indexing helper`.

### Task M1.6: Ingest repository — insert + aggregate (integration TDD via Testcontainers)

**Files:**
- Create: `backend/src/main/kotlin/atlas/backend/db/Database.kt` (Exposed wiring, runs migrations on boot)
- Create: `backend/src/main/kotlin/atlas/backend/repo/ReadingRepository.kt`
- Test: `backend/src/test/kotlin/atlas/backend/repo/ReadingRepositoryTest.kt`

**Step 1: Failing integration test** (Testcontainers PostGIS):
```kotlin
@Testcontainers
class ReadingRepositoryTest {
    companion object { @Container val db = PostgreSQLContainer("postgis/postgis:16-3.4") }
    // boot Database against db.jdbcUrl, run migrations
    @Test fun ingest_then_aggregate_produces_hex() {
        val repo = ReadingRepository(database)
        repo.ingest(listOf(reading(dbm=-90), reading(dbm=-100)))  // same hex/carrier/network
        val hexes = repo.hexes(bbox = wholeWorld, filters = none())
        assertEquals(1, hexes.size)
        assertEquals(-95.0, hexes[0].meanDbm, 0.01)
        assertEquals(2, hexes[0].sampleCount)
    }
    @Test fun anomalous_readings_are_dropped() {
        val repo = ReadingRepository(database)
        repo.ingest(listOf(reading(dbm=-90), reading(dbm=10 /*invalid*/)))
        assertEquals(1, repo.hexes(wholeWorld, none())[0].sampleCount)
    }
}
```

**Step 2: Run → FAIL.**

**Step 3: Implement** `ingest()` = run each through `AnomalyFilter`, compute `h3_r10` via `H3`, insert raw rows, then upsert `hex_aggregates` (recompute mean/median/stddev/confidence/coverage_class for affected `(h3, mcc, mnc, network)` keys via SQL `GROUP BY`). `hexes(bbox, filters)` = SELECT from `hex_aggregates` joined to centroid, filtered by PostGIS `ST_MakeEnvelope` and optional carrier/network/model.

**Step 4: Run → PASS.** **Step 5: Commit** `feat(backend): reading ingest + hex aggregation repository`.

### Task M1.7: Ktor HTTP API

**Files:**
- Create: `backend/src/main/kotlin/atlas/backend/Application.kt`
- Create: `backend/src/main/kotlin/atlas/backend/Routes.kt`
- Test: `backend/src/test/kotlin/atlas/backend/RoutesTest.kt` (Ktor `testApplication`)

**Step 1: Failing tests** — `POST /readings` returns 202 + accepted count; `GET /hexes?bbox=...` returns the aggregated hex; `GET /carriers` lists distinct carriers.

**Step 2: Run → FAIL.**

**Step 3: Implement** routes delegating to `ReadingRepository`, `ContentNegotiation` with kotlinx JSON, health route `GET /healthz`.

**Step 4: Run → PASS.** **Step 5: Commit** `feat(backend): ktor routes for ingest, hexes, carriers`.

### Task M1.8: Seed script + manual end-to-end check

**Files:**
- Create: `backend/src/main/kotlin/atlas/backend/dev/Seed.kt` (generates ~500 synthetic readings along a route)

**Step:** `docker compose up -d`, run backend (`./gradlew :backend:run`), run seed, then `curl "localhost:8080/hexes?bbox=..."` and confirm hexes return with sane `meanDbm`/`coverageClass`. Document the curl in `backend/README.md`.

**Commit:** `feat(backend): dev seed + run instructions`.

**M1 DONE** — backend ingests, aggregates, and serves a heatmap feed, verified against a real PostGIS.

---

## Phase M2: Android Collector

> Telephony reading construction and the offline queue are unit/instrumented-testable; the foreground service and live device run are manual. Use @superpowers:test-driven-development for the testable units.

### Task M2.1: Telephony → SignalReading mapper (testable, no Android runtime needed for the mapper)

**Files:**
- Create: `composeApp/src/androidMain/kotlin/atlas/collect/SignalMapper.kt`
- Test: `composeApp/src/androidMain/.../SignalMapperTest.kt` (Robolectric or pure mapper over a data holder)

**Approach:** Keep `SignalMapper` pure — it takes a plain `RawCellSample` data class (dbm, rsrq, sinr, bars, cellId, tac, pci, earfcn, networkType) + a `LocationFix` + device info, and returns a `SignalReading`. The Android-specific extraction from `CellInfoLte`/`CellSignalStrengthNr` lives in a thin adapter that builds `RawCellSample` (covered by instrumented test M2.4). This keeps the dBm-by-tech logic unit-tested.

**Step 1: Failing tests** — LTE sample → `signalDbm = rsrp`, `networkType = LTE`; NR sample → `SS-RSRP`; missing optional fields → nulls preserved; bars derived correctly.

**Steps 2-4:** TDD the mapper. **Commit** `feat(android): telephony sample → SignalReading mapper`.

### Task M2.2: Room offline queue

**Files:**
- Create: `composeApp/src/androidMain/.../db/ReadingEntity.kt`, `ReadingDao.kt`, `CollectorDb.kt`
- Test: instrumented `ReadingDaoTest` (in-memory Room)

**Step 1: Failing tests** — insert N, `unsent()` returns them ordered, `markSent(ids)` clears them, `count()` correct.

**Steps 2-4:** Implement Room entities/DAO. **Commit** `feat(android): room offline queue for readings`.

### Task M2.3: Upload worker

**Files:**
- Create: `composeApp/src/androidMain/.../UploadWorker.kt` (WorkManager `CoroutineWorker`)
- Create: shared `ApiClient` in `shared/src/commonMain/.../net/ApiClient.kt` (Ktor client `POST /readings`)
- Test: `shared` `ApiClientTest` with Ktor `MockEngine`; instrumented worker test with a stubbed client.

**Step 1: Failing tests** — `ApiClient.postReadings(batch)` serializes correctly and treats 202 as success, non-2xx as retryable failure.

**Steps 2-4:** Implement. Worker pulls `unsent()` in batches → `ApiClient.postReadings` → `markSent` on success → `Result.retry()` on network failure. **Commit** `feat: shared api client + android upload worker`.

### Task M2.4: Collector foreground service + telephony adapter (manual verification)

**Files:**
- Create: `composeApp/src/androidMain/.../CollectorService.kt` (foreground service)
- Create: `composeApp/src/androidMain/.../TelephonyReader.kt` (`TelephonyCallback`/`getAllCellInfo` → `RawCellSample`)
- Create: `composeApp/src/androidMain/.../LocationReader.kt` (`FusedLocationProviderClient`)
- Modify: `composeApp/src/androidMain/AndroidManifest.xml` (permissions + FGS type + service)
- Instrumented test: `TelephonyReaderTest` asserts a `RawCellSample` is produced on a device/emulator with a SIM.

**Behavior:** sampling cadence (signal-change event + 5 s / 25 m floor), `is_moving` tagging, write each reading to Room, schedule `UploadWorker`. Permission rationale + runtime request handled in M3 UI.

**Step (manual):** Build, install on a physical Android device with SIM, grant permissions, start collection, walk ~100 m, confirm rows appear in Room (debug screen) and then in backend after upload. Document steps in `composeApp/README.md`.

**Commit:** `feat(android): foreground collector service + telephony/location readers`.

**M2 DONE** — a real device produces real readings that reach the backend.

---

## Phase M3: Android Viewer (heatmap)

### Task M3.1: Shared repository + view-model for hexes

**Files:**
- Create: `shared/src/commonMain/.../HexRepository.kt`, `MapViewModel.kt` (filters: carrier, network; bbox; exposes `StateFlow<List<HexAggregate>>`)
- Test: `shared` test with `MockEngine` — changing filters/bbox re-queries and updates state.

**Steps 1-4:** TDD. **Commit** `feat(shared): hex repository + map view-model`.

### Task M3.2: MapLibre heatmap (Android, manual visual verification)

**Files:**
- Create: `composeApp/src/androidMain/.../MapScreen.kt` (MapLibre `MapView` in `AndroidView`)
- Add MapLibre Android dependency + a free style URL (e.g. demotiles or a self-hosted style).

**Behavior:** render `HexAggregate` list as filled H3 polygons (`H3.cellToBoundary` → GeoJSON `FillLayer`), color by `coverageClass`, opacity by `confidence`. Move/zoom updates bbox → repository re-query. Use @accessibility-compliance:ui-visual-validator after build to confirm the heatmap renders.

**Step (manual):** Run app, pan to a seeded/collected area, confirm colored hexes appear and recolor when filters change.

**Commit:** `feat(android): maplibre H3 heatmap screen`.

### Task M3.3: Filters + hex detail + collector controls UI

**Files:**
- Create: `composeApp/src/commonMain/.../HeatmapUi.kt` (Compose filter bar: carrier/network dropdowns; bottom sheet on hex tap showing mean/median dBm, sample count, confidence)
- Create: `composeApp/src/androidMain/.../CollectorControls.kt` (Start/Stop collection, "my contributions" count, permission rationale flow)

**Step (manual):** End-to-end demo — start collection, walk, stop, see your hexes light up; toggle carrier filter; tap a hex for details.

**Commit:** `feat: heatmap filters, hex detail sheet, collector controls`.

**M3 DONE — POC COMPLETE.** Walk → upload → aggregated heatmap, on a real device, with filters.

---

## Phase 2 (outline — break into TDD tasks after POC demo)

- **M4 OpenCelliD blend:** `ingest-opencellid/` importer → `cell_towers`; modeled-coverage fallback for hexes with zero crowd samples; `source`-aware confidence; viewer renders modeled hexes faint.
- **M5 phone-model dimension + iOS/web viewers:** populate `hex_model_aggregates`; add model filter + per-model breakdown in hex detail; bring up `composeApp` iOS target + `iosApp` host and `wasmJsMain` web target as viewers (MapLibre iOS SDK / GL JS); optional iOS throughput/latency proxy contribution.

---

## GitHub

After Phase 0 (or earlier), create the public repo and push:
```bash
gh repo create bhaweshverma50/netatlas --public --source=. --remote=origin --description "Crowdsourced cellular coverage heatmap (Android collector + Compose Multiplatform viewer + PostGIS/H3 backend)" --push
```
(Requires `gh auth status` to be logged in.)
