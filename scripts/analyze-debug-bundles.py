#!/usr/bin/env python3
"""Summarize WayHeatmapTracer debug bundles for detector calibration."""

from __future__ import annotations

import argparse
import csv
import io
import json
import math
import statistics
import sys
import zipfile
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class BundleSource:
    """One direct or nested debug bundle held entirely in memory."""

    name: str
    data: bytes


def read_zip_text(bundle: BundleSource, name: str) -> str:
    """Return a UTF-8 text member from a debug bundle, or an empty string when absent."""
    with zipfile.ZipFile(io.BytesIO(bundle.data)) as archive:
        try:
            return archive.read(name).decode("utf-8")
        except KeyError:
            return ""


def read_zip_csv(bundle: BundleSource, name: str) -> list[dict[str, str]]:
    """Return CSV rows from a debug bundle member."""
    text = read_zip_text(bundle, name)
    if not text.strip():
        return []
    return list(csv.DictReader(io.StringIO(text)))


def rating_score(value: str) -> int | None:
    """Convert a human preview rating token into a numeric score."""
    return {
        "++": 2,
        "+": 1,
        "0": 0,
        "-": -1,
        "--": -2,
    }.get((value or "").strip())


def bundle_rows(bundle: BundleSource) -> list[dict[str, object]]:
    """Merge candidate metrics and optional human ratings for one debug bundle."""
    metrics = read_zip_csv(bundle, "candidate-metrics.csv")
    optimizer = optimizer_summaries(read_zip_csv(bundle, "optimizer-costs.csv"))
    grouping = track_grouping(read_zip_csv(bundle, "corridor-tracks.csv"))
    scale_space = scale_space_summaries(read_zip_csv(bundle, "scale-space.csv"))
    try:
        attempts = json.loads(read_zip_text(bundle, "detector-attempts.json") or "[]")
    except json.JSONDecodeError:
        attempts = []
    attempt_status = {
        str(attempt.get("mappingName", "")): str(attempt.get("status", ""))
        for attempt in attempts if isinstance(attempt, dict)
    }
    try:
        ratings = json.loads(read_zip_text(bundle, "candidate-ratings.json") or "{}")
    except json.JSONDecodeError:
        ratings = {}
    rows: list[dict[str, object]] = []
    for row in metrics:
        candidate_id = row.get("candidate_id", "")
        detector, track_id = candidate_track_identity(candidate_id)
        optimizer_summary = optimizer.get((detector, track_id), {})
        scale_summary = scale_space.get(detector, {})
        rating = ratings.get(candidate_id, {})
        numeric = rating_score(str(rating.get("rating", ""))) if isinstance(rating, dict) else None
        negative = ",".join(rating.get("negativeFeatures", [])) if isinstance(rating, dict) else ""
        rows.append({
            "bundle": bundle.name,
            "candidate_id": candidate_id,
            "detector": row.get("detector", ""),
            "visible_color": row.get("visible_color", ""),
            "intensity_source": row.get("intensity_source", ""),
            "source_tier": float_or_none(row.get("source_tier")),
            "applicable": row.get("applicable", ""),
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
            "signal_existence_confidence": float_or_none(row.get("signal_existence_confidence")),
            "localization_confidence": float_or_none(row.get("localization_confidence")),
            "optimizer_cost": float_or_none(row.get("optimizer_cost")),
            "in_corridor_fraction": float_or_none(row.get("in_corridor_fraction")),
            "p95_delta_px": float_or_none(row.get("p95_delta_px")),
            "p95_acceleration_px": float_or_none(row.get("p95_acceleration_px")),
            "high_frequency_p95_px": float_or_none(row.get("high_frequency_p95_px")),
            "p95_delta_source_px": float_or_none(row.get("p95_delta_source_px")),
            "p95_acceleration_source_px": float_or_none(row.get("p95_acceleration_source_px")),
            "high_frequency_p95_source_px": float_or_none(row.get("high_frequency_p95_source_px")),
            "sub_source_wiggle_ratio": float_or_none(row.get("sub_source_wiggle_ratio")),
            "sign_flips": float_or_none(row.get("sign_flips")),
            "edge_ratio": float_or_none(row.get("edge_ratio")),
            "corridor_center_wander_px": optimizer_summary.get("center_wander_px"),
            "corridor_lateral_acceleration": optimizer_summary.get("lateral_acceleration"),
            "in_core_or_shoulder_fraction": optimizer_summary.get("in_corridor_fraction"),
            "in_core_fraction": optimizer_summary.get("in_core_fraction"),
            "endpoint_approach_angle_degrees": optimizer_summary.get("endpoint_approach_angle_degrees"),
            "grouping_decision": grouping.get((detector, track_id), ""),
            "detector_attempt_status": attempt_status.get(detector, ""),
            "scale_persistence": float_or_none(row.get("scale_persistence"))
            if row.get("scale_persistence", "") != "" else scale_summary.get("median_persistence"),
            "cross_scale_center_drift_px": scale_summary.get("median_center_drift_px"),
            "scale_conflict_ratio": scale_summary.get("conflict_ratio"),
            "tube_residual_mean_source_px": float_or_none(row.get("tube_residual_mean_source_px")),
            "tube_residual_p95_source_px": float_or_none(row.get("tube_residual_p95_source_px")),
            "corridor_hf_rms_source_px": float_or_none(row.get("corridor_hf_rms_source_px")),
            "corridor_hf_p95_source_px": float_or_none(row.get("corridor_hf_p95_source_px")),
            "turn_p95_deg": float_or_none(row.get("turn_p95_deg")),
            "turn_max_deg": float_or_none(row.get("turn_max_deg")),
            "curvature_change_p95_deg": float_or_none(row.get("curvature_change_p95_deg")),
            "forward_progress_violations": float_or_none(row.get("forward_progress_violations")),
            "unsupported_excursions": float_or_none(row.get("unsupported_excursions")),
            "max_gap_m": float_or_none(row.get("max_gap_m")),
            "endpoint_approach_max_turn_deg": float_or_none(row.get("endpoint_approach_max_turn_deg")),
            "true_longitudinal_persistence": float_or_none(row.get("true_longitudinal_persistence")),
            "endpoint_approaches_supported": row.get("endpoint_approaches_supported", ""),
        })
    return rows


def candidate_track_identity(candidate_id: str) -> tuple[str, str]:
    """Return detector and corridor track ids from a namespaced candidate id."""
    parts = (candidate_id or "").split("/")
    return (parts[0], parts[1]) if len(parts) >= 2 else ("", candidate_id or "")


def optimizer_summaries(rows: list[dict[str, str]]) -> dict[tuple[str, str], dict[str, float]]:
    """Calculate stable geometry metrics from optional corridor optimizer diagnostics."""
    grouped: dict[tuple[str, str], list[dict[str, str]]] = defaultdict(list)
    for row in rows:
        grouped[(row.get("detector", ""), row.get("track_id", ""))].append(row)
    summaries: dict[tuple[str, str], dict[str, float]] = {}
    for key, group in grouped.items():
        ordered = sorted(group, key=lambda row: int(row.get("profile_index", "0") or 0))
        offsets = compact_numbers(float_or_none(row.get("chosen_offset_px")) for row in ordered)
        spacings = compact_numbers(float_or_none(row.get("profile_spacing_px")) for row in ordered)
        accelerations = compact_numbers(float_or_none(row.get("acceleration_cost")) for row in ordered)
        in_core = [str(row.get("inside_core", "")).lower() == "true" for row in ordered]
        contained = [str(row.get("inside_corridor", "")).lower() == "true" for row in ordered]
        if not offsets:
            continue
        linear = []
        if len(offsets) == 1:
            linear = offsets
        else:
            linear = [offsets[0] + (offsets[-1] - offsets[0]) * index / (len(offsets) - 1)
                      for index in range(len(offsets))]
        residuals = [offset - baseline for offset, baseline in zip(offsets, linear)]
        start_spacing = spacings[1] if len(spacings) > 1 else (spacings[0] if spacings else 1.0)
        end_spacing = spacings[-1] if spacings else 1.0
        endpoint_slope = max(
            abs(offsets[1] - offsets[0]) / max(1e-9, start_spacing) if len(offsets) > 1 else 0.0,
            abs(offsets[-1] - offsets[-2]) / max(1e-9, end_spacing) if len(offsets) > 1 else 0.0,
        )
        summaries[key] = {
            "center_wander_px": statistics.pstdev(residuals) if len(residuals) > 1 else 0.0,
            "lateral_acceleration": statistics.mean(accelerations) if accelerations else 0.0,
            "in_corridor_fraction": sum(contained) / len(contained) if contained else 0.0,
            "in_core_fraction": sum(in_core) / len(in_core) if in_core else 0.0,
            "endpoint_approach_angle_degrees": math.degrees(math.atan(endpoint_slope)),
        }
    return summaries


def track_grouping(rows: list[dict[str, str]]) -> dict[tuple[str, str], str]:
    """Return parent/child grouping labels from optional corridor track diagnostics."""
    result: dict[tuple[str, str], str] = {}
    for row in rows:
        track_id = row.get("track_id", "")
        if track_id:
            result[(row.get("detector", ""), track_id)] = row.get("grouping_decision", "")
    return result


def scale_space_summaries(rows: list[dict[str, str]]) -> dict[str, dict[str, float]]:
    """Summarize persistence, center drift, and conflicts from optional scale-space diagnostics."""
    grouped: dict[str, list[dict[str, str]]] = defaultdict(list)
    for row in rows:
        grouped[row.get("detector", "")].append(row)
    result: dict[str, dict[str, float]] = {}
    for detector, detector_rows in grouped.items():
        persistence = compact_numbers(float_or_none(row.get("scale_persistence")) for row in detector_rows)
        conflicts = [str(row.get("scale_conflict", "")).lower() == "true"
                     for row in detector_rows if row.get("level", "") == "0"]
        centers: dict[int, list[float]] = defaultdict(list)
        for row in detector_rows:
            center = float_or_none(row.get("center_px"))
            if center is not None:
                centers[int(row.get("profile_index", "0") or 0)].append(center)
        drift = [max(values) - min(values) for values in centers.values() if len(values) >= 2]
        result[detector] = {
            "median_persistence": statistics.median(persistence) if persistence else 0.0,
            "median_center_drift_px": statistics.median(drift) if drift else 0.0,
            "conflict_ratio": sum(conflicts) / len(conflicts) if conflicts else 0.0,
        }
    return result


def float_or_none(value: str | None) -> float | None:
    """Parse a float, returning ``None`` for blank or invalid values."""
    try:
        return float(value) if value not in (None, "") else None
    except ValueError:
        return None


def detector_summary(rows: list[dict[str, object]]) -> list[dict[str, object]]:
    """Aggregate per-candidate rows by visible color, intensity source, and detector."""
    grouped: dict[tuple[str, str, str], list[dict[str, object]]] = defaultdict(list)
    for row in rows:
        grouped[(str(row["visible_color"]), str(row["intensity_source"]), str(row["detector"]))].append(row)
    summary = []
    for (visible_color, intensity_source, detector), group in sorted(grouped.items()):
        rated = [row for row in group if row["rating_score"] is not None]
        scores = [int(row["rating_score"]) for row in rated]
        snr = compact_numbers(row["signal_to_noise"] for row in group)
        rough = compact_numbers(row["p95_delta_px"] for row in group)
        source_rough = compact_numbers(row["high_frequency_p95_source_px"] for row in group)
        gradient = compact_numbers(row["mean_gradient_strength"] for row in group)
        stability = compact_numbers(row["longitudinal_stability"] for row in group)
        existence = compact_numbers(row["signal_existence_confidence"] for row in group)
        localization = compact_numbers(row["localization_confidence"] for row in group)
        in_corridor = compact_numbers(row["in_corridor_fraction"] for row in group)
        wander = compact_numbers(row["corridor_center_wander_px"] for row in group)
        acceleration = compact_numbers(row["corridor_lateral_acceleration"] for row in group)
        endpoint_angle = compact_numbers(row["endpoint_approach_angle_degrees"] for row in group)
        core_fraction = compact_numbers(row["in_core_fraction"] for row in group)
        persistence = compact_numbers(row["scale_persistence"] for row in group)
        scale_drift = compact_numbers(row["cross_scale_center_drift_px"] for row in group)
        scale_conflicts = compact_numbers(row["scale_conflict_ratio"] for row in group)
        tube_residual = compact_numbers(row["tube_residual_p95_source_px"] for row in group)
        corridor_hf = compact_numbers(row["corridor_hf_p95_source_px"] for row in group)
        turns = compact_numbers(row["turn_p95_deg"] for row in group)
        persistence_physical = compact_numbers(row["true_longitudinal_persistence"] for row in group)
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
            "median_signal_existence_confidence": statistics.median(existence) if existence else None,
            "median_localization_confidence": statistics.median(localization) if localization else None,
            "median_in_corridor_fraction": statistics.median(in_corridor) if in_corridor else None,
            "median_corridor_center_wander_px": statistics.median(wander) if wander else None,
            "median_corridor_lateral_acceleration": statistics.median(acceleration) if acceleration else None,
            "median_endpoint_approach_angle_degrees": statistics.median(endpoint_angle) if endpoint_angle else None,
            "median_in_core_fraction": statistics.median(core_fraction) if core_fraction else None,
            "median_p95_delta_px": statistics.median(rough) if rough else None,
            "median_high_frequency_p95_source_px": statistics.median(source_rough) if source_rough else None,
            "median_scale_persistence": statistics.median(persistence) if persistence else None,
            "median_cross_scale_center_drift_px": statistics.median(scale_drift) if scale_drift else None,
            "median_scale_conflict_ratio": statistics.median(scale_conflicts) if scale_conflicts else None,
            "median_tube_residual_p95_source_px": statistics.median(tube_residual) if tube_residual else None,
            "median_corridor_hf_p95_source_px": statistics.median(corridor_hf) if corridor_hf else None,
            "median_turn_p95_deg": statistics.median(turns) if turns else None,
            "median_true_longitudinal_persistence": statistics.median(persistence_physical)
            if persistence_physical else None,
            "attempt_statuses": "; ".join(sorted({str(row["detector_attempt_status"]) for row in group
                                                   if row["detector_attempt_status"]})),
            "negative_features": negative_counts(group),
        })
    return summary


def compact_numbers(values):
    """Keep only concrete numeric values from an iterable."""
    return [float(value) for value in values if isinstance(value, (float, int))]


def negative_counts(rows: list[dict[str, object]]) -> str:
    """Count negative feature tags across candidate rows."""
    counts: dict[str, int] = defaultdict(int)
    for row in rows:
        for feature in str(row.get("negative_features", "")).split(","):
            feature = feature.strip()
            if feature:
                counts[feature] += 1
    return "; ".join(f"{key}:{value}" for key, value in sorted(counts.items()))


def print_table(rows: list[dict[str, object]]) -> None:
    """Write summary rows as CSV to standard output."""
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
        "median_signal_existence_confidence",
        "median_localization_confidence",
        "median_in_corridor_fraction",
        "median_corridor_center_wander_px",
        "median_corridor_lateral_acceleration",
        "median_endpoint_approach_angle_degrees",
        "median_in_core_fraction",
        "median_p95_delta_px",
        "median_high_frequency_p95_source_px",
        "median_scale_persistence",
        "median_cross_scale_center_drift_px",
        "median_scale_conflict_ratio",
        "median_tube_residual_p95_source_px",
        "median_corridor_hf_p95_source_px",
        "median_turn_p95_deg",
        "median_true_longitudinal_persistence",
        "attempt_statuses",
        "negative_features",
    ]
    writer = csv.DictWriter(sys.stdout, fieldnames=fields)
    writer.writeheader()
    for row in rows:
        writer.writerow({field: row.get(field, "") for field in fields})


def debug_bundles(path: Path) -> list[BundleSource]:
    """Return direct and recursively nested WayHeatmapTracer debug bundles from one zip path."""
    return nested_debug_bundles(path.name, path.read_bytes(), 0)


def nested_debug_bundles(name: str, data: bytes, depth: int) -> list[BundleSource]:
    """Discover debug bundles in a possibly nested zip without extracting user data to disk."""
    if depth > 8:
        raise ValueError(f"Nested zip depth exceeds safety limit: {name}")
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            names = set(archive.namelist())
            if "candidate-metrics.csv" in names:
                return [BundleSource(name, data)]
            result: list[BundleSource] = []
            for member in sorted(value for value in names if value.lower().endswith(".zip")):
                result.extend(nested_debug_bundles(f"{name}!{member}", archive.read(member), depth + 1))
            return result
    except zipfile.BadZipFile as error:
        raise ValueError(f"Not a readable zip bundle: {name}") from error


def main() -> None:
    """Parse command-line arguments and print detector calibration summaries."""
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
    bundles: list[BundleSource] = []
    for path in bundle_paths:
        bundles.extend(debug_bundles(path))
    rows: list[dict[str, object]] = []
    for bundle in bundles:
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
