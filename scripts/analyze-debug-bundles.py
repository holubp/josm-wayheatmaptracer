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
    manifest = json_object(read_zip_text(bundle, "manifest.json"))
    diagnostics = json_object(read_zip_text(bundle, "diagnostics.json"))
    bundle_format = int(manifest.get("formatVersion", 0) or 0)
    plugin_version = str(manifest.get("pluginVersion") or diagnostics.get("pluginVersion") or "")
    physical_warning = physical_distance_warning(bundle, bundle_format, diagnostics)
    metrics = read_zip_csv(bundle, "candidate-metrics.csv")
    optimizer_rows = read_zip_csv(bundle, "optimizer-costs.csv")
    optimizer = optimizer_summaries(optimizer_rows)
    tubes = tube_summaries(read_zip_csv(bundle, "corridor-tube.csv"))
    performance = performance_summaries(read_zip_csv(bundle, "detector-performance.csv"))
    grouping = track_grouping(read_zip_csv(bundle, "corridor-tracks.csv"))
    sparse_bundles = sparse_bundle_summaries(
        read_zip_csv(bundle, "corridor-bundles.csv"),
        read_zip_csv(bundle, "bundle-points.csv"),
        optimizer_rows,
    )
    bridge_directions = bridge_direction_summaries(read_zip_csv(bundle, "corridor-tracks.csv"))
    scale_space = scale_space_summaries(read_zip_csv(bundle, "scale-space.csv"))
    proposed_positions = read_zip_csv(bundle, "proposed-node-positions.csv")
    proposed_counts: dict[str, int] = defaultdict(int)
    for position in proposed_positions:
        proposed_counts[str(position.get("candidate_id", ""))] += 1
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
        tube_summary = tubes.get((detector, track_id), {})
        performance_summary = performance.get(detector, {})
        bridge_summary = bridge_directions.get((detector, track_id), {})
        scale_summary = scale_space.get(detector, {})
        sparse_summary = sparse_bundles.get((detector, track_id), {})
        rating = ratings.get(candidate_id, {})
        numeric = rating_score(str(rating.get("rating", ""))) if isinstance(rating, dict) else None
        negative = ",".join(rating.get("negativeFeatures", [])) if isinstance(rating, dict) else ""
        rows.append({
            "bundle": bundle.name,
            "bundle_format": bundle_format,
            "plugin_version": plugin_version,
            "build_identity": str(manifest.get("buildIdentity") or diagnostics.get("buildIdentity") or ""),
            "original_geometry_trust": "immutable" if bundle_format >= 5 else "unreliable-after-apply",
            "proposed_topology_state": "available" if bundle_format >= 7 and proposed_positions else "unavailable",
            "proposed_node_count": proposed_counts.get(candidate_id, 0),
            "physical_distance_warning": physical_warning,
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
            "measurable_quality_score": float_or_none(row.get("measurable_quality_score")),
            "detector_prior": float_or_none(row.get("detector_prior")),
            "coverage_complete": row.get("coverage_complete", ""),
            "coverage_reason": row.get("coverage_reason", ""),
            "informative_coverage_ratio": float_or_none(row.get("informative_coverage_ratio")),
            "leading_unsupported_m": float_or_none(row.get("leading_unsupported_m")),
            "trailing_unsupported_m": float_or_none(row.get("trailing_unsupported_m")),
            "max_internal_unsupported_m": float_or_none(row.get("max_internal_unsupported_m")),
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
            "tube_motion_support_mean": tube_summary.get("motion_support_mean"),
            "tube_local_stability_residual_p95_px": tube_summary.get("local_stability_residual_p95_px"),
            "tube_motion_support_reasons": tube_summary.get("motion_support_reasons", ""),
            "grouping_decision": grouping.get((detector, track_id), ""),
            "sparse_bundle_classification": sparse_summary.get("classification", "unavailable"),
            "sparse_bundle_reason": sparse_summary.get("reason", ""),
            "sparse_bundle_child_count": sparse_summary.get("child_count"),
            "sparse_bundle_direct_union_profiles": sparse_summary.get("direct_union_profiles"),
            "sparse_bundle_interpolated_profiles": sparse_summary.get("interpolated_profiles"),
            "sparse_bundle_union_support_ratio": sparse_summary.get("union_support_ratio"),
            "sparse_bundle_interpolation_ratio": sparse_summary.get("interpolation_ratio"),
            "sparse_bundle_contributor_agreement": sparse_summary.get("contributor_agreement"),
            "sparse_bundle_center_residual_p95_px": sparse_summary.get("center_residual_p95_px"),
            "sparse_bundle_diagnoses": sparse_bundle_diagnoses(sparse_summary, row),
            "detector_attempt_status": attempt_status.get(detector, ""),
            "scale_persistence": float_or_none(row.get("scale_persistence"))
            if row.get("scale_persistence", "") != "" else scale_summary.get("median_persistence"),
            "cross_scale_center_drift_px": scale_summary.get("median_center_drift_px"),
            "scale_conflict_ratio": scale_summary.get("conflict_ratio"),
            "tube_residual_mean_source_px": float_or_none(row.get("tube_residual_mean_source_px")),
            "tube_residual_p95_source_px": float_or_none(row.get("tube_residual_p95_source_px")),
            "corridor_hf_rms_source_px": float_or_none(row.get("corridor_hf_rms_source_px")),
            "corridor_hf_p95_source_px": float_or_none(row.get("corridor_hf_p95_source_px")),
            "non_sustained_hf_rms_source_px": float_or_none(row.get("non_sustained_hf_rms_source_px")),
            "non_sustained_hf_p95_source_px": float_or_none(row.get("non_sustained_hf_p95_source_px")),
            "unsupported_reversal_count": float_or_none(row.get("unsupported_reversal_count")),
            "unsupported_reversal_ratio": float_or_none(row.get("unsupported_reversal_ratio")),
            "turn_p95_deg": float_or_none(row.get("turn_p95_deg")),
            "turn_max_deg": float_or_none(row.get("turn_max_deg")),
            "curvature_change_p95_deg": float_or_none(row.get("curvature_change_p95_deg")),
            "forward_progress_violations": float_or_none(row.get("forward_progress_violations")),
            "unsupported_excursions": float_or_none(row.get("unsupported_excursions")),
            "max_gap_m": float_or_none(row.get("max_gap_m")),
            "endpoint_approach_max_turn_deg": float_or_none(row.get("endpoint_approach_max_turn_deg")),
            "true_longitudinal_persistence": float_or_none(row.get("true_longitudinal_persistence")),
            "endpoint_approaches_supported": row.get("endpoint_approaches_supported", ""),
            "detector_total_ms": performance_summary.get("total_ms"),
            "sampling_ms": performance_summary.get("sampling_ms"),
            "extraction_ms": performance_summary.get("extraction_ms"),
            "scale_association_ms": performance_summary.get("scale_association_ms"),
            "tracking_grouping_ms": performance_summary.get("tracking_grouping_ms"),
            "exact_optimization_ms": performance_summary.get("optimization_ms"),
            "diagnostic_serialization_ms": performance_summary.get("diagnostic_serialization_ms"),
            "projection_ms": performance_summary.get("projection_ms"),
            "timing_unaccounted_ratio": performance_summary.get("unaccounted_ratio"),
            "timing_dominant_phase": performance_summary.get("dominant_phase", ""),
            "timing_warning": performance_warning(bundle_format, performance_summary),
            "transition_evaluations": performance_summary.get("transition_evaluations"),
            "profile_cost_evaluations": performance_summary.get("profile_cost_evaluations"),
            "transition_to_profile_cost_ratio": performance_summary.get("transition_to_profile_cost_ratio"),
            "canonical_bridge_count": bridge_summary.get("canonical", 0),
            "backward_marker_bridge_count": bridge_summary.get("backward_marker", 0),
        })
    return rows


def json_object(text: str) -> dict[str, object]:
    """Parse a JSON object, returning an empty object for absent or invalid input."""
    try:
        value = json.loads(text or "{}")
        return value if isinstance(value, dict) else {}
    except json.JSONDecodeError:
        return {}


def physical_distance_warning(
    bundle: BundleSource,
    bundle_format: int,
    diagnostics: dict[str, object],
) -> str:
    """Return a concise warning for missing or internally inconsistent physical-distance diagnostics."""
    if bundle_format < 5:
        return "format<5 metre fields may contain raster-space values"
    sampling = diagnostics.get("sampling", {})
    if not isinstance(sampling, dict):
        return f"format-{bundle_format} sampling object missing"
    if bundle_format == 5 and sampling.get("type") == "rendered-visible-layer":
        return "format-5 visible-layer metre fields used projection units and are untrusted"
    if bundle_format >= 6:
        if int(sampling.get("samplingScaleVersion", 0) or 0) < 1:
            return "format-6 sampling scale contract missing"
        for field in ("groundMetersPerViewPixel", "groundMetersPerRasterPixel",
                      "trackerNormalizationRasterPx", "trackerNormalizationMethod"):
            if sampling.get(field) in (None, ""):
                return f"format-6 sampling field missing: {field}"
    profile_count = int(sampling.get("profileCount", 0) or 0)
    path_length = float_or_none(str(sampling.get("physicalPathLengthMeters", "")))
    spacing = sampling.get("longitudinalProfileSpacingMeters", {})
    median = float_or_none(str(spacing.get("median", ""))) if isinstance(spacing, dict) else None
    if profile_count > 1 and path_length is not None and median is not None and median > 0.0:
        expected = median * (profile_count - 1)
        ratio = path_length / expected if expected > 0.0 else 1.0
        if ratio < 0.5 or ratio > 1.5:
            return f"path length/profile spacing mismatch ratio={ratio:.3f}"
    tube_rows = read_zip_csv(bundle, "corridor-tube.csv")
    grouped: dict[tuple[str, str], list[float]] = defaultdict(list)
    for row in tube_rows:
        distance = float_or_none(row.get("distance_m"))
        if distance is not None:
            grouped[(row.get("detector", ""), row.get("track_id", ""))].append(distance)
    if any(any(after + 1e-9 < before for before, after in zip(values, values[1:]))
           for values in grouped.values()):
        return "non-monotonic corridor tube distance"
    return ""


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


def tube_summaries(rows: list[dict[str, str]]) -> dict[tuple[str, str], dict[str, object]]:
    """Summarize optional dual-window longitudinal references without requiring new bundle fields."""
    grouped: dict[tuple[str, str], list[dict[str, str]]] = defaultdict(list)
    for row in rows:
        grouped[(row.get("detector", ""), row.get("track_id", ""))].append(row)
    summaries: dict[tuple[str, str], dict[str, object]] = {}
    for key, group in grouped.items():
        supports = compact_numbers(float_or_none(row.get("motion_support")) for row in group)
        residuals = compact_numbers(
            abs(local - stable)
            for row in group
            if (local := float_or_none(row.get("local_center_px"))) is not None
            and (stable := float_or_none(row.get("stability_center_px"))) is not None
        )
        reasons = sorted({row.get("motion_support_reason", "") for row in group
                          if row.get("motion_support_reason", "")})
        summaries[key] = {
            "motion_support_mean": statistics.mean(supports) if supports else None,
            "local_stability_residual_p95_px": percentile(residuals, 0.95) if residuals else None,
            "motion_support_reasons": ",".join(reasons),
        }
    return summaries


def performance_summaries(rows: list[dict[str, str]]) -> dict[str, dict[str, object]]:
    """Parse format-6 per-detector phase timings and exact-optimizer operation counts."""
    result: dict[str, dict[str, object]] = {}
    for row in rows:
        detector = row.get("detector", "")
        total = float_or_none(row.get("total_nanos")) or 0.0
        unaccounted = float_or_none(row.get("unaccounted_nanos")) or 0.0
        transitions = float_or_none(row.get("transition_evaluations")) or 0.0
        profile_costs = float_or_none(row.get("profile_cost_evaluations")) or 0.0
        phases = {
            "sampling": (float_or_none(row.get("sampling_nanos")) or 0.0) / 1_000_000.0,
            "extraction": (float_or_none(row.get("extraction_nanos")) or 0.0) / 1_000_000.0,
            "scale-association": (float_or_none(row.get("scale_association_nanos")) or 0.0) / 1_000_000.0,
            "tracking-grouping": (float_or_none(row.get("tracking_grouping_nanos")) or 0.0) / 1_000_000.0,
            "optimization": (float_or_none(row.get("optimization_nanos")) or 0.0) / 1_000_000.0,
            "diagnostic-serialization":
                (float_or_none(row.get("diagnostic_serialization_nanos")) or 0.0) / 1_000_000.0,
            "projection": (float_or_none(row.get("projection_nanos")) or 0.0) / 1_000_000.0,
        }
        result[detector] = {
            "total_ms": total / 1_000_000.0,
            "sampling_ms": phases["sampling"],
            "extraction_ms": phases["extraction"],
            "scale_association_ms": phases["scale-association"],
            "tracking_grouping_ms": phases["tracking-grouping"],
            "optimization_ms": phases["optimization"],
            "diagnostic_serialization_ms": phases["diagnostic-serialization"],
            "projection_ms": phases["projection"],
            "dominant_phase": max(phases, key=phases.get),
            "unaccounted_ratio": unaccounted / total if total > 0.0 else 0.0,
            "transition_evaluations": transitions,
            "profile_cost_evaluations": profile_costs,
            "transition_to_profile_cost_ratio": transitions / profile_costs if profile_costs > 0.0 else 0.0,
        }
    return result


def performance_warning(bundle_format: int, summary: dict[str, object]) -> str:
    """Return a concise warning for missing or materially unreconciled format-6 timing."""
    if bundle_format < 6:
        return ""
    if not summary:
        return "format-6 detector performance row missing"
    ratio = float(summary.get("unaccounted_ratio", 0.0) or 0.0)
    return f"{ratio:.1%} detector time is unaccounted" if ratio > 0.20 else ""


def track_grouping(rows: list[dict[str, str]]) -> dict[tuple[str, str], str]:
    """Return parent/child grouping labels from optional corridor track diagnostics."""
    result: dict[tuple[str, str], str] = {}
    for row in rows:
        track_id = row.get("track_id", "")
        if track_id:
            result[(row.get("detector", ""), track_id)] = row.get("grouping_decision", "")
    return result


def sparse_bundle_summaries(
    bundle_rows_data: list[dict[str, str]],
    point_rows: list[dict[str, str]],
    optimizer_rows: list[dict[str, str]],
) -> dict[tuple[str, str], dict[str, object]]:
    """Summarize optional format-8 sparse-bundle evidence without treating absence as zero."""
    points_by_key: dict[tuple[str, str], list[dict[str, str]]] = defaultdict(list)
    chosen_by_profile: dict[tuple[str, str, int], float] = {}
    for point in point_rows:
        points_by_key[(point.get("detector", ""), point.get("bundle_id", ""))].append(point)
    for row in optimizer_rows:
        profile = int(row.get("profile_index", "0") or 0)
        chosen = float_or_none(row.get("chosen_offset_px"))
        if chosen is not None:
            chosen_by_profile[(row.get("detector", ""), row.get("track_id", ""), profile)] = chosen
    result: dict[tuple[str, str], dict[str, object]] = {}
    for row in bundle_rows_data:
        key = (row.get("detector", ""), row.get("bundle_id", ""))
        points = points_by_key.get(key, [])
        agreements = compact_numbers(float_or_none(point.get("contributor_agreement")) for point in points)
        residuals: list[float] = []
        for point in points:
            profile = int(point.get("profile_index", "0") or 0)
            chosen = chosen_by_profile.get((key[0], key[1], profile))
            center = float_or_none(point.get("center_px"))
            if chosen is not None and center is not None:
                residuals.append(abs(chosen - center))
        direct = float_or_none(row.get("direct_union_profiles"))
        interpolated = float_or_none(row.get("interpolated_profiles"))
        total = (direct or 0.0) + (interpolated or 0.0)
        child_ids = [value for value in row.get("child_track_ids", "").split(";") if value]
        result[key] = {
            "classification": row.get("classification", ""),
            "reason": row.get("reason", ""),
            "child_count": len(child_ids),
            "direct_union_profiles": direct,
            "interpolated_profiles": interpolated,
            "union_support_ratio": float_or_none(row.get("union_support_ratio")),
            "joint_support_ratio": float_or_none(row.get("joint_support_ratio")),
            "valley_persistence": float_or_none(row.get("valley_persistence")),
            "order_stability": float_or_none(row.get("order_stability")),
            "interpolation_ratio": (interpolated or 0.0) / total if total > 0.0 else None,
            "contributor_agreement": statistics.median(agreements) if agreements else None,
            "center_residual_p95_px": percentile(residuals, 0.95) if residuals else None,
        }
    return result


def sparse_bundle_diagnoses(summary: dict[str, object], candidate_row: dict[str, str]) -> str:
    """Return deterministic format-8 sparse-corridor diagnostic labels for one candidate."""
    if not summary:
        return "unavailable"
    diagnoses: list[str] = []
    support = summary.get("union_support_ratio")
    interpolation = summary.get("interpolation_ratio")
    residual = summary.get("center_residual_p95_px")
    valley = summary.get("valley_persistence")
    order = summary.get("order_stability")
    ripple = float_or_none(candidate_row.get("non_sustained_hf_p95_source_px"))
    if isinstance(support, float) and support < 0.70:
        diagnoses.append("sparse-bundle-fragmentation")
    if isinstance(residual, float) and residual > 0.75:
        diagnoses.append("dominant-child-bias")
    if isinstance(interpolation, float) and interpolation > 0.30:
        diagnoses.append("interpolation-heavy")
    if isinstance(valley, float) and isinstance(order, float) and valley >= 0.60 and order >= 0.90:
        diagnoses.append("persistent-parallel-modes")
    if ripple is not None and ripple > 0.40:
        diagnoses.append("bundle-ripple")
    return ";".join(diagnoses) if diagnoses else "none"


def bridge_direction_summaries(rows: list[dict[str, str]]) -> dict[tuple[str, str], dict[str, int]]:
    """Count canonical and legacy backward-owned bridge markers for each corridor track."""
    grouped: dict[tuple[str, str], list[dict[str, str]]] = defaultdict(list)
    for row in rows:
        if row.get("track_id", "") and row.get("profile_index", ""):
            grouped[(row.get("detector", ""), row.get("track_id", ""))].append(row)
    result: dict[tuple[str, str], dict[str, int]] = {}
    for key, track_rows in grouped.items():
        ordered = sorted(track_rows, key=lambda row: int(row.get("profile_index", "0") or 0))
        canonical = 0
        backward = 0
        for left, right in zip(ordered, ordered[1:]):
            left_index = int(left.get("profile_index", "0") or 0)
            right_index = int(right.get("profile_index", "0") or 0)
            if right_index - left_index <= 1:
                continue
            left_bridged = str(left.get("bridged", "")).lower() == "true"
            right_bridged = str(right.get("bridged", "")).lower() == "true"
            canonical += int(right_bridged)
            backward += int(left_bridged and not right_bridged)
        result[key] = {"canonical": canonical, "backward_marker": backward}
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
        total_ms = compact_numbers(row["detector_total_ms"] for row in group)
        optimization_ms = compact_numbers(row["exact_optimization_ms"] for row in group)
        cost_ratio = compact_numbers(row["transition_to_profile_cost_ratio"] for row in group)
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
            "median_detector_total_ms": statistics.median(total_ms) if total_ms else None,
            "median_exact_optimization_ms": statistics.median(optimization_ms) if optimization_ms else None,
            "median_transition_to_profile_cost_ratio": statistics.median(cost_ratio) if cost_ratio else None,
            "attempt_statuses": "; ".join(sorted({str(row["detector_attempt_status"]) for row in group
                                                   if row["detector_attempt_status"]})),
            "negative_features": negative_counts(group),
        })
    return summary


def compact_numbers(values):
    """Keep only concrete numeric values from an iterable."""
    return [float(value) for value in values if isinstance(value, (float, int))]


def percentile(values: list[float], quantile: float) -> float:
    """Return a deterministic nearest-rank percentile for a non-empty numeric sequence."""
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil(quantile * len(ordered)) - 1))
    return ordered[index]


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
        "median_detector_total_ms",
        "median_exact_optimization_ms",
        "median_transition_to_profile_cost_ratio",
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
