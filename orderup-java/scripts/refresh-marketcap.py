#!/usr/bin/env python3
"""Rebuild data/marketcap-amfi.csv from the latest NSE Indices constituent lists.

Usage:
    python3 orderup-java/scripts/refresh-marketcap.py

Downloads the three NSE index CSVs (Nifty 100 / Midcap 150 / Smallcap 250) and
produces a single SYMBOL,CATEGORY file that ClassificationService can read.
This is the SEBI/AMFI methodology equivalent:
    top 100 by full mcap  -> LARGE_CAP
    ranks 101 - 250       -> MID_CAP
    ranks 251 - 500       -> SMALL_CAP

After running, POST /admin/reload-classifications on each running app to pick
up the change with zero downtime, then POST /admin/backfill-classifications
to stamp any historical rows that are missing the new fields.
"""
from __future__ import annotations

import csv
import os
import sys
import urllib.request
from collections import Counter
from datetime import date

SOURCES = [
    ("https://niftyindices.com/IndexConstituent/ind_nifty100list.csv",         "LARGE_CAP"),
    ("https://niftyindices.com/IndexConstituent/ind_niftymidcap150list.csv",   "MID_CAP"),
    ("https://niftyindices.com/IndexConstituent/ind_niftysmallcap250list.csv", "SMALL_CAP"),
]

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
JAVA_ROOT = os.path.join(REPO_ROOT, "orderup-java")

# ClassificationService looks for the external file relative to the running
# app's CWD, so write it under every module's data/ dir plus the shared root.
OUT_PATHS = [
    os.path.join(JAVA_ROOT, "data", "marketcap-amfi.csv"),
    os.path.join(JAVA_ROOT, "orderup-app", "data", "marketcap-amfi.csv"),
    os.path.join(JAVA_ROOT, "orderup-chartink-app", "data", "marketcap-amfi.csv"),
    os.path.join(JAVA_ROOT, "orderup-common", "src", "main", "resources", "marketcap-amfi.csv"),
]


def fetch(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return resp.read().decode("utf-8-sig")


def collect() -> list[tuple[str, str]]:
    rows: list[tuple[str, str]] = []
    seen: set[str] = set()
    for url, cat in SOURCES:
        text = fetch(url)
        r = csv.DictReader(text.splitlines())
        keys = {k.strip().lower(): k for k in (r.fieldnames or [])}
        sym_key = keys.get("symbol")
        if not sym_key:
            raise SystemExit(f"no Symbol column in {url}")
        for row in r:
            sym = (row[sym_key] or "").strip().upper()
            if not sym or sym in seen:
                continue
            seen.add(sym)
            rows.append((sym, cat))
    rows.sort()
    return rows


def write(rows: list[tuple[str, str]]) -> None:
    header = (
        f"# Market-cap classification derived from NSE Indices constituent lists ({date.today().isoformat()}).\n"
        "# Source (SEBI/AMFI methodology equivalent):\n"
        "#   Nifty 100          -> LARGE_CAP (top 100 by full market cap)\n"
        "#   Nifty Midcap 150   -> MID_CAP   (ranks 101-250)\n"
        "#   Nifty Smallcap 250 -> SMALL_CAP (ranks 251-500)\n"
        "# Refresh: rerun scripts/refresh-marketcap.py, then POST\n"
        "# /admin/reload-classifications on each app (zero-downtime hot reload).\n"
        "SYMBOL,CATEGORY\n"
    )
    for p in OUT_PATHS:
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w", encoding="utf-8") as f:
            f.write(header)
            for sym, cat in rows:
                f.write(f"{sym},{cat}\n")
        print(f"wrote {p} ({len(rows)} symbols)")


def main() -> int:
    rows = collect()
    write(rows)
    print("category counts:", dict(Counter(c for _, c in rows)))
    return 0


if __name__ == "__main__":
    sys.exit(main())

