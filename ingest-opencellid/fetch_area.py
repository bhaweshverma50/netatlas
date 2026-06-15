#!/usr/bin/env python3
"""Fetch real cell towers for a bounding box from the OpenCelliD area API and write a
CSV in the bulk-download column order that `TowerImporter` ingests.

OpenCelliD's free API caps `cell/getInArea` at ~4 km^2 and 50 results per call, with a
1000 requests/day limit, so this tiles the bbox into small cells and stitches the results.

The API key is read from the OPENCELLID_TOKEN environment variable — it is never written
to disk or committed.

Usage:
  OPENCELLID_TOKEN=pk.xxxx python3 ingest-opencellid/fetch_area.py \
      --bbox 12.86,77.50,13.08,77.72 --out /tmp/ocid-bengaluru.csv
Then import it:
  JDBC_URL=... DB_USER=... DB_PASSWORD=... ./gradlew :backend:importOpenCelliD \
      -PopencellidCsv=/tmp/ocid-bengaluru.csv     # or set OPENCELLID_CSV
"""
import argparse
import csv
import json
import os
import subprocess
import sys
import time

API = "https://opencellid.org/cell/getInArea"
TILE_DEG = 0.017          # ~1.9 km per side -> ~3.5 km^2, under the 4 km^2 cap
PAGE_LIMIT = 50           # API max results per call
THROTTLE_S = 0.12         # gentle spacing to avoid "too many requests" (code 6)


def get(token, latmin, lonmin, latmax, lonmax, offset):
    url = (f"{API}?key={token}&BBOX={latmin:.5f},{lonmin:.5f},{latmax:.5f},{lonmax:.5f}"
           f"&format=json&limit={PAGE_LIMIT}&offset={offset}")
    out = subprocess.run(["curl", "-s", "--max-time", "60", url],
                         capture_output=True, text=True).stdout
    try:
        return json.loads(out)
    except json.JSONDecodeError:
        return {"error": "bad_response", "raw": out[:200]}


def frange(start, stop, step):
    x = start
    while x < stop:
        yield x
        x += step


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bbox", required=True, help="latmin,lonmin,latmax,lonmax")
    ap.add_argument("--out", required=True)
    ap.add_argument("--max-calls", type=int, default=900, help="stay under the 1000/day limit")
    args = ap.parse_args()

    token = os.environ.get("OPENCELLID_TOKEN")
    if not token:
        sys.exit("set OPENCELLID_TOKEN (your OpenCelliD API key)")

    latmin, lonmin, latmax, lonmax = (float(x) for x in args.bbox.split(","))
    seen, rows, calls = set(), [], 0
    stop = False  # set when the daily quota or call budget is exhausted

    for la in frange(latmin, latmax, TILE_DEG):
        if stop:
            break
        for lo in frange(lonmin, lonmax, TILE_DEG):
            if calls >= args.max_calls:
                print(f"hit max-calls={args.max_calls}, stopping early", file=sys.stderr)
                stop = True
                break
            offset = 0
            while True:
                data = get(token, la, lo, min(la + TILE_DEG, latmax), min(lo + TILE_DEG, lonmax), offset)
                calls += 1
                time.sleep(THROTTLE_S)
                if "error" in data:
                    code = data.get("code")
                    if code == 6:                       # too many requests -> back off
                        time.sleep(2.0)
                        continue
                    if code == 7:                       # daily limit reached -> stop everything
                        print(f"daily request limit reached after {calls} calls; stopping", file=sys.stderr)
                        stop = True
                    break                                # other errors: skip this tile
                cells = data.get("cells", [])
                for c in cells:
                    key = (c["mcc"], c["mnc"], c["lac"], c["cellid"])
                    if key in seen:
                        continue
                    seen.add(key)
                    rows.append(c)
                if len(cells) < PAGE_LIMIT or calls >= args.max_calls:
                    break                                # tile exhausted (or budget hit)
                offset += PAGE_LIMIT
            if stop:
                break

    # Write in the bulk-download column order TowerImporter/OpenCelliDParser expects.
    with open(args.out, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["radio", "mcc", "net", "area", "cell", "unit", "lon", "lat", "range", "samples"])
        for c in rows:
            w.writerow([c.get("radio", ""), c["mcc"], c["mnc"], c["lac"], c["cellid"], "",
                        c["lon"], c["lat"], c.get("range", ""), c.get("samples", "")])
    print(f"wrote {len(rows)} unique towers to {args.out} ({calls} API calls)")


if __name__ == "__main__":
    main()
