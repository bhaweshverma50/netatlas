-- V2__schema.sql
-- Core schema for the netatlas ingest -> aggregate -> serve pipeline.
-- Idempotent: uses IF NOT EXISTS so it can be re-applied without error.
-- Requires PostGIS (enabled in V1__enable_postgis.sql).

-- 1. signal_readings: raw, append-only signal samples.
CREATE TABLE IF NOT EXISTS signal_readings (
    id              BIGSERIAL PRIMARY KEY,
    device_id       TEXT NOT NULL,
    ts              TIMESTAMPTZ NOT NULL,
    lat             DOUBLE PRECISION NOT NULL,
    lng             DOUBLE PRECISION NOT NULL,
    geom            geography(Point, 4326) NOT NULL,
    loc_accuracy_m  DOUBLE PRECISION NOT NULL,
    speed_mps       DOUBLE PRECISION NOT NULL,
    is_moving       BOOLEAN NOT NULL,
    h3_r10          BIGINT NOT NULL,
    mcc             INTEGER NOT NULL,
    mnc             INTEGER NOT NULL,
    carrier_name    TEXT NOT NULL,
    network_type    TEXT NOT NULL,
    signal_dbm      INTEGER NOT NULL,
    rsrq            INTEGER,
    sinr            INTEGER,
    asu             INTEGER,
    bars            INTEGER NOT NULL,
    cell_id         BIGINT,
    tac             INTEGER,
    pci             INTEGER,
    earfcn          INTEGER,
    phone_make      TEXT NOT NULL,
    phone_model     TEXT NOT NULL,
    os_version      TEXT NOT NULL,
    app_version     TEXT NOT NULL,
    source          TEXT NOT NULL DEFAULT 'CROWD',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_signal_readings_h3_r10
    ON signal_readings (h3_r10);
CREATE INDEX IF NOT EXISTS idx_signal_readings_mcc_mnc_network_type
    ON signal_readings (mcc, mnc, network_type);
CREATE INDEX IF NOT EXISTS idx_signal_readings_geom
    ON signal_readings USING GIST (geom);

-- 2. hex_aggregates: per h3 cell x carrier x network type.
CREATE TABLE IF NOT EXISTS hex_aggregates (
    h3_r10                  BIGINT NOT NULL,
    resolution              INTEGER NOT NULL,
    mcc                     INTEGER NOT NULL,
    mnc                     INTEGER NOT NULL,
    network_type            TEXT NOT NULL,
    sample_count            INTEGER NOT NULL,
    mean_dbm                DOUBLE PRECISION NOT NULL,
    median_dbm              DOUBLE PRECISION NOT NULL,
    p10_dbm                 DOUBLE PRECISION,
    stddev                  DOUBLE PRECISION NOT NULL,
    mean_rsrq               DOUBLE PRECISION,
    mean_sinr               DOUBLE PRECISION,
    confidence              DOUBLE PRECISION NOT NULL,
    coverage_class          TEXT NOT NULL,
    contributing_devices    INTEGER NOT NULL,
    center_lat              DOUBLE PRECISION NOT NULL,
    center_lng              DOUBLE PRECISION NOT NULL,
    center_geom             geography(Point, 4326) NOT NULL,
    last_updated            TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (h3_r10, mcc, mnc, network_type)
);

CREATE INDEX IF NOT EXISTS idx_hex_aggregates_center_geom
    ON hex_aggregates USING GIST (center_geom);

-- 3. hex_model_aggregates: adds the phone_model dimension.
CREATE TABLE IF NOT EXISTS hex_model_aggregates (
    h3_r10                  BIGINT NOT NULL,
    resolution              INTEGER NOT NULL,
    mcc                     INTEGER NOT NULL,
    mnc                     INTEGER NOT NULL,
    network_type            TEXT NOT NULL,
    phone_model             TEXT NOT NULL,
    sample_count            INTEGER NOT NULL,
    mean_dbm                DOUBLE PRECISION NOT NULL,
    median_dbm              DOUBLE PRECISION NOT NULL,
    stddev                  DOUBLE PRECISION NOT NULL,
    confidence              DOUBLE PRECISION NOT NULL,
    coverage_class          TEXT NOT NULL,
    contributing_devices    INTEGER NOT NULL,
    center_lat              DOUBLE PRECISION NOT NULL,
    center_lng              DOUBLE PRECISION NOT NULL,
    center_geom             geography(Point, 4326) NOT NULL,
    last_updated            TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (h3_r10, mcc, mnc, network_type, phone_model)
);

CREATE INDEX IF NOT EXISTS idx_hex_model_aggregates_center_geom
    ON hex_model_aggregates USING GIST (center_geom);

-- 4. cell_towers: OpenCelliD import target.
CREATE TABLE IF NOT EXISTS cell_towers (
    radio       TEXT,
    mcc         INTEGER NOT NULL,
    mnc         INTEGER NOT NULL,
    area        INTEGER NOT NULL,
    cell_id     BIGINT NOT NULL,
    lng         DOUBLE PRECISION NOT NULL,
    lat         DOUBLE PRECISION NOT NULL,
    range_m     INTEGER,
    samples     INTEGER,
    geom        geography(Point, 4326) NOT NULL,
    updated_at  TIMESTAMPTZ,
    PRIMARY KEY (mcc, mnc, area, cell_id)
);

CREATE INDEX IF NOT EXISTS idx_cell_towers_geom
    ON cell_towers USING GIST (geom);
