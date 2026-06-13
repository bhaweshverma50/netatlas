# netatlas

Crowdsourced cellular **coverage atlas** — track real-world mobile signal strength
(RSRP/dBm) from users' devices and render it as a heatmap, blended with open data
(OpenCelliD/BeaconDB) where crowd data doesn't exist yet.

- **Collector:** native Android (Kotlin) — reads real signal strength via `TelephonyManager`.
- **Viewer:** Kotlin Multiplatform + Compose Multiplatform (Android / iOS / web) with MapLibre.
- **Backend:** Postgres + PostGIS + h3-pg, aggregating readings into H3 hexagons by
  carrier × network type × phone model × area.

> iOS has no public API for cellular signal strength, so collection is Android-first;
> iOS/web are viewers. See [`docs/plans/2026-06-13-netatlas-design.md`](docs/plans/2026-06-13-netatlas-design.md)
> for the full design and feasibility analysis.

## Status

Design complete. Implementation plan next. POC = backend skeleton → Android collector → live heatmap.
