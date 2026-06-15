# OpenCelliD ingest

Imports OpenCelliD-format cell-tower data into the `cell_towers` table so the
backend can model coverage in areas that crowd data has not reached yet.

## CSV format

OpenCelliD distributes a CSV with this header (one tower per row):

```
radio,mcc,net,area,cell,unit,lon,lat,range,samples,changeable,created,updated,averageSignal
LTE,404,45,678,12345,,77.5946,12.9716,1500,42,1,1700000000,1700000000,-95
```

Columns used by the importer (everything else is ignored):

| CSV column | `cell_towers` column |
|------------|----------------------|
| `radio`    | `radio`              |
| `mcc`      | `mcc`                |
| `net`      | `mnc`                |
| `area`     | `area`               |
| `cell`     | `cell_id`            |
| `lon`      | `lng`                |
| `lat`      | `lat`                |
| `range`    | `range_m`            |
| `samples`  | `samples`            |

`geom` is derived from `lng`/`lat` and `updated_at` is set to import time. Rows are
UPSERTed on `(mcc, mnc, area, cell_id)`, so re-importing a dump is idempotent.

The parser is tolerant: the header row, blank lines, and malformed rows (wrong
column count, non-numeric fields, or out-of-range lat/lng) are skipped silently.

## Sample data

`sample-bengaluru.csv` contains ~30 illustrative towers spread across the
Bengaluru bounding box (~12.85–13.10 N, 77.50–77.70 E): `mcc=404`, Airtel
`mnc=45` + Jio `mnc=10`, a mix of LTE and NR, with ranges 1000–3000 m. They are
deliberately spaced so each tower's modeled k-ring overlaps its neighbours and
the demo reads as broad, continuous coverage rather than isolated patches.

This is **sample data only** — hand-placed to make the demo look complete. Real
coverage density comes from real OpenCelliD downloads (see below) plus crowd
collection from the app; do not treat these towers as accurate field data.

## Real data

Real dumps require a free OpenCelliD API key. Register at
[opencellid.org](https://www.opencellid.org/), then download the per-MCC CSV
(e.g. MCC 404 for India). Large dumps go under `ingest-opencellid/data/`, which is
git-ignored.

## Usage

Against a running PostGIS (defaults to `jdbc:postgresql://localhost:5432/netatlas`,
user/password `netatlas`):

```bash
# Import the bundled sample (default path):
./gradlew :backend:importOpenCelliD

# Import a specific CSV (CLI arg or OPENCELLID_CSV env var):
OPENCELLID_CSV=ingest-opencellid/data/404.csv ./gradlew :backend:importOpenCelliD

# Override DB connection:
JDBC_URL=jdbc:postgresql://127.0.0.1:5433/netatlas \
DB_USER=netatlas DB_PASSWORD=netatlas \
  ./gradlew :backend:importOpenCelliD
```

The task prints the number of towers imported.
