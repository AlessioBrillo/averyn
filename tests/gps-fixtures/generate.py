#!/usr/bin/env python3
"""Generates synthetic GPS fixtures (track.gpx + expected.json) for the tracking engine's quality/metric
tests (TDD-0001 §6). Every fixture moves only in latitude (constant longitude) so its expected distance is
exact: for two same-longitude points, haversine distance equals R * delta-latitude-in-radians exactly
(no small-angle approximation), matching shared/metrics's haversineMeters bit-for-bit within test tolerance.

Run: python tests/gps-fixtures/generate.py
Re-run and commit the output whenever a fixture definition below changes; this script is not run by CI.
"""

import json
import math
import os
from datetime import datetime, timedelta, timezone

EARTH_RADIUS_M = 6_371_008.8  # IUGG mean radius, must match shared/metrics/Distance.kt
METERS_PER_DEG_LAT = EARTH_RADIUS_M * math.pi / 180
BASE_LON = 9.0
BASE_TIME = datetime(2026, 1, 1, 8, 0, 0, tzinfo=timezone.utc)

FIXTURES_DIR = os.path.dirname(os.path.abspath(__file__))


def lat_for(meters_north: float) -> float:
    return meters_north / METERS_PER_DEG_LAT


def iso_time(offset_s: float) -> str:
    return (BASE_TIME + timedelta(seconds=offset_s)).strftime("%Y-%m-%dT%H:%M:%SZ")


def write_fixture(name: str, points: list[tuple[float, float, float | None]], description: str, expected: dict):
    """points: list of (offset_seconds, meters_north, altitude_m_or_None), in file order (GPX <time> order,
    which may be non-monotonic on purpose for the non-monotonic fixture)."""
    fixture_dir = os.path.join(FIXTURES_DIR, name)
    os.makedirs(fixture_dir, exist_ok=True)

    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<gpx version="1.1" creator="averyn-fixtures" xmlns="http://www.topografix.com/GPX/1/1">',
        "  <trk>",
        f"    <name>{name}</name>",
        "    <trkseg>",
    ]
    for offset_s, meters_north, altitude_m in points:
        lat = lat_for(meters_north)
        ele = f"<ele>{altitude_m}</ele>" if altitude_m is not None else ""
        lines.append(f'      <trkpt lat="{lat:.7f}" lon="{BASE_LON:.7f}">{ele}<time>{iso_time(offset_s)}</time></trkpt>')
    lines += ["    </trkseg>", "  </trk>", "</gpx>"]

    with open(os.path.join(fixture_dir, "track.gpx"), "w", newline="\n") as f:
        f.write("\n".join(lines) + "\n")

    expected_json = {
        "description": description,
        "algorithm_versions": {"distance": "distance-v1", "quality": "quality-v1", "time": "time-v1"},
        "expected": expected,
    }
    with open(os.path.join(fixture_dir, "expected.json"), "w", newline="\n") as f:
        json.dump(expected_json, f, indent=2)
        f.write("\n")


write_fixture(
    "outliers-and-duplicates",
    points=[
        (0, 0, None),
        (10, 100, None),  # normal: 10 m/s
        (10, 100, None),  # exact duplicate of the previous point (same time+position)
        (11, 1100, None),  # vs the pre-duplicate baseline: 1000 m in 1 s -> JUMP
        (21, 1200, None),  # vs the JUMP sample (still becomes the new baseline): 100 m/10s -> normal
        (22, 1300, None),  # vs previous: 100 m/1s -> SPEED_OUTLIER (under the 500 m jump floor)
    ],
    description="Straight north line with an exact duplicate fix, a long jump, and a short fast blip.",
    expected={
        "distance_m": {"value": 1300.0, "tolerance_m": 1.0},
        "duration_s": 22,
        "moving_time_s": 22,
        "quality_flags": ["DUPLICATE", "NON_MONOTONIC_TIME", "JUMP", "SPEED_OUTLIER"],
    },
)

write_fixture(
    "signal-gap",
    points=[
        (0, 0, None),
        (10, 100, None),  # normal: 10 m/s
        (310, 105, None),  # 5 min signal gap, barely moved: implied speed ~0.017 m/s -> counted as stationary
        (320, 205, None),  # normal again: 10 m/s
    ],
    description="A five-minute signal gap within one recording segment; no flags, but the gap counts as stationary time.",
    expected={
        "distance_m": {"value": 205.0, "tolerance_m": 1.0},
        "duration_s": 320,
        "moving_time_s": 20,
        "quality_flags": [],
    },
)

write_fixture(
    "noisy-altitude",
    points=[
        (0, 0, 100.0),
        (10, 20, 100.0),  # 2 m/s, 0 m/s vertical: normal
        (20, 40, 250.0),  # 2 m/s horizontal, +15 m/s vertical -> ALTITUDE_SPIKE
        (30, 60, 100.0),  # 2 m/s horizontal, -15 m/s vertical -> ALTITUDE_SPIKE
        (40, 80, 105.0),  # 2 m/s horizontal, +0.5 m/s vertical: normal
    ],
    description="Steady 2 m/s walking pace with two erratic altitude jumps.",
    expected={
        "distance_m": {"value": 80.0, "tolerance_m": 1.0},
        "duration_s": 40,
        "moving_time_s": 40,
        "quality_flags": ["ALTITUDE_SPIKE"],
    },
)

write_fixture(
    "non-monotonic",
    points=[
        (0, 0, None),
        (10, 100, None),  # normal: 10 m/s
        (5, 150, None),  # <time> goes backward -> NON_MONOTONIC_TIME; excluded, doesn't become the baseline
        (20, 200, None),  # vs the pre-non-monotonic baseline: 100 m/10s -> normal
    ],
    description="One fix whose timestamp goes backward relative to the previous one.",
    expected={
        "distance_m": {"value": 200.0, "tolerance_m": 1.0},
        "duration_s": 20,
        "moving_time_s": 20,
        "quality_flags": ["NON_MONOTONIC_TIME"],
    },
)

print("Wrote fixtures to", FIXTURES_DIR)
