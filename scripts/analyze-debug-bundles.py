#!/usr/bin/env python3
"""Summarize WayHeatmapTracer debug bundles for detector calibration."""

from __future__ import annotations

import argparse
import csv
import io
import json
import statistics
import sys
import zipfile
from collections import defaultdict
from pathlib import Path


def read_zip_text(bundle: Path, name: str) -> str:
    with zipfile.ZipFile(bundle) as archive:
        try:
            return archive.read(name).decode("utf-8")
        except KeyError:
            return ""


def read_zip_csv(bundle: Path, name: str) -> list[dict[str, str]]:
    text = read_zip_text(bundle, name)
    if not text.strip():
        return []
    return list(csv.DictReader(io.StringIO(text)))


def rating_score(value: str) -> int | None:
    return {
        "++": 2,
        "+": 1,
        "0": 0,
        "-": -1,
        "--": -2,
    }.get((value or "").strip())


def bundle_rows(bundle: Path) -> list[dict[str, object]]:
    metrics = read_zip_csv(bundle, "candidate-metrics.csv")
    try:
        ratings = json.loads(read_zip_text(bundle, "candidate-ratings.json") or "{}")
    except json.JSONDecodeError:
        ratings = {}
    rows: list[dict[str, object]] = []
    for row in metrics:
        candidate_id = row.get("candidate_id", "")
        rating = ratings.get(candidate_id, {})
        numeric = rating_score(str(rating.get("rating", ""))) if isinstance(rating, dict) else None
        negative = ",".join(rating.get("negativeFeatures", [])) if isinstance(rating, dict) else ""
        rows.append({
            "bundle": bundle.name,
            "candidate_id": candidate_id,
            "detector": row.get("detector", ""),
            "visible_color": row.get("visible_color", ""),
            "intensity_source": row.get("intensity_source", ""),
            "rating": rating.get("rating", "") if isinstance(rating, dict) else "",
            "rating_score": numeric,
            "negative_features": negative,
            "calibrated_score": float_or_none(row.get("calibrated_score")),
            "support_ratio": float_or_none(row.get("support_ratio")),
            "mean_intensity": float_or_none(row.get("mean_intensity")),
            "mean_gradient_strength": float_or_none(row.get("mean_gradient_strength")),
            "longitudinal_stability": float_or_none(row.get("longitudinal_stability")),
            "signal_to_noise": float_or_none(row.get("signal_to_noise")),
            "ambiguity": float_or_none(row.get("ambiguity")),
            "p95_delta_px": float_or_none(row.get("p95_delta_px")),
            "p95_acceleration_px": float_or_none(row.get("p95_acceleration_px")),
            "sign_flips": float_or_none(row.get("sign_flips")),
            "edge_ratio": float_or_none(row.get("edge_ratio")),
        })
    return rows


def float_or_none(value: str | None) -> float | None:
    try:
        return float(value) if value not in (None, "") else None
    except ValueError:
        return None


def detector_summary(rows: list[dict[str, object]]) -> list[dict[str, object]]:
    grouped: dict[tuple[str, str, str], list[dict[str, object]]] = defaultdict(list)
    for row in rows:
        grouped[(str(row["visible_color"]), str(row["intensity_source"]), str(row["detector"]))].append(row)
    summary = []
    for (visible_color, intensity_source, detector), group in sorted(grouped.items()):
        rated = [row for row in group if row["rating_score"] is not None]
        scores = [int(row["rating_score"]) for row in rated]
        snr = compact_numbers(row["signal_to_noise"] for row in group)
        rough = compact_numbers(row["p95_delta_px"] for row in group)
        gradient = compact_numbers(row["mean_gradient_strength"] for row in group)
        stability = compact_numbers(row["longitudinal_stability"] for row in group)
        summary.append({
            "visible_color": visible_color,
            "intensity_source": intensity_source,
            "detector": detector,
            "count": len(group),
            "rated": len(rated),
            "mean_rating": statistics.mean(scores) if scores else None,
            "median_snr": statistics.median(snr) if snr else None,
            "median_gradient": statistics.median(gradient) if gradient else None,
            "median_longitudinal_stability": statistics.median(stability) if stability else None,
            "median_p95_delta_px": statistics.median(rough) if rough else None,
            "negative_features": negative_counts(group),
        })
    return summary


def compact_numbers(values):
    return [float(value) for value in values if isinstance(value, (float, int))]


def negative_counts(rows: list[dict[str, object]]) -> str:
    counts: dict[str, int] = defaultdict(int)
    for row in rows:
        for feature in str(row.get("negative_features", "")).split(","):
            feature = feature.strip()
            if feature:
                counts[feature] += 1
    return "; ".join(f"{key}:{value}" for key, value in sorted(counts.items()))


def print_table(rows: list[dict[str, object]]) -> None:
    fields = [
        "visible_color",
        "intensity_source",
        "detector",
        "count",
        "rated",
        "mean_rating",
        "median_snr",
        "median_gradient",
        "median_longitudinal_stability",
        "median_p95_delta_px",
        "negative_features",
    ]
    writer = csv.DictWriter(sys.stdout, fieldnames=fields)
    writer.writeheader()
    for row in rows:
        writer.writerow({field: row.get(field, "") for field in fields})


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundles", nargs="+", type=Path, help="Debug bundle .zip files or directories containing them")
    parser.add_argument("--raw-csv", type=Path, help="Optional path for per-candidate raw rows")
    args = parser.parse_args()

    bundle_paths: list[Path] = []
    for path in args.bundles:
        if path.is_dir():
            bundle_paths.extend(sorted(path.glob("*.zip")))
        else:
            bundle_paths.append(path)
    rows: list[dict[str, object]] = []
    for bundle in bundle_paths:
        rows.extend(bundle_rows(bundle))

    if args.raw_csv:
        with args.raw_csv.open("w", newline="", encoding="utf-8") as handle:
            fieldnames = list(rows[0].keys()) if rows else ["bundle"]
            writer = csv.DictWriter(handle, fieldnames=fieldnames)
            writer.writeheader()
            writer.writerows(rows)
    print_table(detector_summary(rows))


if __name__ == "__main__":
    main()
