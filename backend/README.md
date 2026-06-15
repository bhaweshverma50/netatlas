# netatlas backend

Ktor + PostGIS service that ingests crowd-sourced cellular `SignalReading`s, maintains
per-hex (`H3` res 10) aggregates, and serves a coverage-query API.

## Prerequisites

- Docker (daemon running) — for the PostGIS database and the test suite.
- JDK 17 (the Gradle toolchain targets 17).

## Local workflow

All commands run from the repo root and use the Gradle wrapper.

### 1. Start PostGIS

```bash
docker compose up -d --wait
```

Brings up `imresamu/postgis:16-3.4` on `localhost:5432` with db/user/password all
`netatlas`. `--wait` blocks until the container's healthcheck passes.

> Note: the data directory is bind-mounted at `backend/db/data`. Postgres only
> initializes the `netatlas` role/database when that directory is empty. If you ever
> change credentials or hit `FATAL: role "netatlas" does not exist`, the volume was
> initialized with different settings — stop the stack and remove the data dir
> (`docker compose down && rm -rf backend/db/data`) to force a clean re-init.

### 2. Start the API

```bash
./gradlew :backend:run
```

Boots Ktor on `:8080` (override with `PORT`) and applies migrations on startup.
DB connection is read from `JDBC_URL`, `DB_USER`, `DB_PASSWORD` (defaults match
docker-compose). Leave this running in its own terminal.

### 3. Load synthetic data

```bash
./gradlew :backend:seed
```

Generates ~500 deterministic `SignalReading`s along a Bengaluru travel route and
POSTs them, in batches of 100, to the running server. Output ends with:

```
netatlas seed: DONE. total accepted=500 rejected=0
```

The seeder targets `http://localhost:8080` by default; override with the
`NETATLAS_URL` env var. It uses the JDK's built-in HTTP client — no extra deps.

The data spans two carriers (mcc 404 / mnc 45 "Airtel" on LTE, mcc 404 / mnc 10
"Jio" on NR_SA), several devices, and a smooth signal dip ("dead zone") in the
middle of the route so coverage classes vary from `GOOD` down to `POOR`.

## Querying the API

Health check:

```bash
curl -s localhost:8080/healthz
# {"status":"ok"}
```

All hexes in a Bengaluru bounding box (note: `minLng,minLat,maxLng,maxLat`):

```bash
curl -s "localhost:8080/hexes?minLng=77.5&minLat=12.9&maxLng=77.65&maxLat=13.05"
```

Returns a JSON array of `HexAggregate` objects, e.g.:

```json
{
  "h3": "8a61b46640b7fff",
  "resolution": 10,
  "mcc": 404,
  "mnc": 45,
  "networkType": "LTE",
  "sampleCount": 3,
  "meanDbm": -84.6,
  "medianDbm": -85.0,
  "stddev": 1.5,
  "confidence": 0.12,
  "coverageClass": "GOOD",
  "centerLat": 12.9355,
  "centerLng": 77.6243
}
```

Filter to a single carrier (fewer hexes — one carrier only):

```bash
curl -s "localhost:8080/hexes?minLng=77.5&minLat=12.9&maxLng=77.65&maxLat=13.05&mnc=45"
```

`/hexes` also accepts optional `mcc` and `network` (a `NetworkType` name, e.g. `LTE`).

Distinct carriers present in the aggregates:

```bash
curl -s localhost:8080/carriers
# [{"mcc":404,"mnc":10,"carrierName":"Jio"},{"mcc":404,"mnc":45,"carrierName":"Airtel"}]
```

## Tests

```bash
./gradlew :backend:test
```

Requires a running Docker daemon — the tests spin up a throwaway PostGIS container
via Testcontainers (no need for `docker compose up`). On macOS the build resolves
the active docker-context endpoint and pins the Docker API version automatically.

## Tear down

```bash
docker compose down
```

Add `-v` (or remove `backend/db/data`) to also discard the database contents.
