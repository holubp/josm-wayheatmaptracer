#!/usr/bin/env python3
"""Quantify WayHeatmapTracer slide roughness from exported debug bundles.

The script accepts a last-slide debug bundle, a directory of bundles, or an
outer zip containing last-slide bundles. It intentionally emits compact CSV and
JSON so the heavy inspection happens locally instead of in an AI conversation.
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import math
import statistics
import sys
import zipfile
from pathlib import Path
from xml.etree import ElementTree

from wayheatmap_analysis.safe_zip import ArchiveLimits, BundleSource as Bundle, SafeArchiveReader


def main() -> None:
    """Parse inputs, compute roughness metrics, and print a compact report."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", nargs="+", type=Path, help="Debug zip, outer zip, or directory")
    parser.add_argument("--csv", type=Path, help="Optional per-candidate CSV output")
    parser.add_argument("--json", type=Path, help="Optional detailed JSON output")
    parser.add_argument("--top", type=int, default=6, help="Candidates to print per bundle")
    args = parser.parse_args()

    rows: list[dict[str, object]] = []
    bundles = list(discover_bundles(args.inputs))
    for bundle in bundles:
        rows.extend(analyze_bundle(bundle))

    annotate_repeat_relationships(rows)
    annotate_cleanup_relationships(rows)
    rows.sort(key=lambda row: (str(row["bundle"]), int(row["rank"])))
    public_rows = [{key: value for key, value in row.items() if not key.startswith("_")} for row in rows]
    if args.csv:
        write_csv(args.csv, public_rows)
    if args.json:
        args.json.write_text(json.dumps({"bundles": bundle_summary(public_rows)}, indent=2), encoding="utf-8")
    print_summary(public_rows, args.top)


def discover_bundles(paths: list[Path]) -> list[Bundle]:
    """Discover last-slide debug bundles from zip files, nested zips, or directories."""
    bundles: list[Bundle] = []
    for path in paths:
        if path.is_dir():
            for child in sorted(path.glob("*.zip")):
                bundles.extend(discover_bundles([child]))
            continue
        bundles.extend(SafeArchiveReader().discover(path))
    return bundles


def discover_nested_bundle(name: str, data: bytes, depth: int) -> list[Bundle]:
    """Recursively discover debug bundles without extracting their contents to disk."""
    if depth < 0 or depth > 8:
        raise ValueError("nested traversal depth must be in [0, 8]")
    return SafeArchiveReader(ArchiveLimits(max_depth=8 - depth)).discover_bytes(name, data)


def is_debug_bundle(data: bytes) -> bool:
    """Return whether bytes look like a WayHeatmapTracer last-slide debug bundle."""
    try:
        return bool(SafeArchiveReader().discover_bytes("bundle.zip", data))
    except ValueError:
        return False


def analyze_bundle(bundle: Bundle) -> list[dict[str, object]]:
    """Compute geometry, offset, and heatmap-profile metrics for every candidate in a bundle."""
    with zipfile.ZipFile(io.BytesIO(bundle.data)) as archive:
        diagnostics = read_json(archive, "diagnostics.json")
        manifest = read_json(archive, "manifest.json")
        status = read_json(archive, "status.json")
        candidate_metrics = read_csv(archive, "candidate-metrics.csv")
        profile_rows = read_csv(archive, "profile-peaks.csv")
        original = geometry_metrics(read_text(archive, "original-segment.osm"))
        preview = geometry_metrics(read_text(archive, "preview-segment.osm"))
        applied = geometry_metrics(read_text(archive, "applied-segment.osm"))
        original_points = way_coordinates(read_text(archive, "original-segment.osm"))
        applied_points = way_coordinates(read_text(archive, "applied-segment.osm"))
        proposed_positions = read_csv(archive, "proposed-node-positions.csv")
        cleanup_by_candidate = cleanup_rows_by_candidate(read_csv(archive, "geometry-cleanup.csv"))
        cleanup_anchors = cleanup_anchors_by_candidate(read_csv(archive, "geometry-cleanup-anchors.csv"))

    bundle_format = int(manifest.get("formatVersion", 0) or 0)
    original_trust = "immutable" if bundle_format >= 5 else (
        "unreliable-after-apply" if status.get("status") == "applied" else "pre-apply-snapshot")

    profiles_by_detector = profile_summary(profile_rows)
    candidates = {candidate.get("id", ""): candidate for candidate in diagnostics.get("candidates", [])}
    selected = status.get("selectedCandidate", "")
    sampling = diagnostics.get("sampling", {})
    config = diagnostics.get("config", {})
    raster_m_per_px = fnum(sampling.get("rasterMetersPerPixel"))
    applicable_count = sum(str(metric.get("applicable", "")).lower() == "true"
                           for metric in candidate_metrics)
    rows: list[dict[str, object]] = []
    for metric in candidate_metrics:
        candidate_id = metric.get("candidate_id", "")
        candidate = candidates.get(candidate_id, {})
        offsets = numbers(candidate.get("offsetsPx", []))
        offset_stats = offset_roughness(offsets)
        profile_stats = profiles_by_detector.get(metric.get("detector", ""), {})
        rows.append({
            "bundle": bundle.name,
            "bundle_format": bundle_format,
            "plugin_version": manifest.get("pluginVersion", diagnostics.get("pluginVersion", "")),
            "build_identity": manifest.get("buildIdentity", diagnostics.get("buildIdentity", "")),
            "original_geometry_trust": original_trust,
            "proposed_topology_state": "available" if bundle_format >= 7 and proposed_positions else "unavailable",
            "proposed_node_count": sum(
                row.get("candidate_id", "") == candidate_id for row in proposed_positions),
            "rank": int(float(metric.get("rank") or 9999)),
            "selected": candidate_id == selected,
            "candidate_id": candidate_id,
            "detector": metric.get("detector", ""),
            "mode": config.get("alignmentMode", ""),
            "inference": config.get("inferenceMode", ""),
            "tile_z": sampling.get("tileZoom", ""),
            "raster_m_per_px": raster_m_per_px,
            "step_m": config.get("sampleStepMeters", ""),
            "half_width_m": config.get("searchHalfWidthMeters", ""),
            "original_nodes": original.get("points", 0),
            "preview_nodes": preview.get("points", 0),
            "applied_nodes": applied.get("points", 0),
            "original_len_m": original.get("length_m", 0.0),
            "preview_len_m": preview.get("length_m", 0.0),
            "applied_len_m": applied.get("length_m", 0.0),
            "preview_p95_turn_deg": preview.get("p95_turn_deg", 0.0),
            "preview_max_turn_deg": preview.get("max_turn_deg", 0.0),
            "preview_p95_local_residual_m": preview.get("p95_local_residual_m", 0.0),
            "preview_residual_flip_rate": preview.get("residual_flip_rate", 0.0),
            "calibrated_score": fnum(metric.get("calibrated_score")),
            "raw_score": fnum(metric.get("raw_score")),
            "support_ratio": fnum(metric.get("support_ratio")),
            "mean_intensity": fnum(metric.get("mean_intensity")),
            "mean_gradient": fnum(metric.get("mean_gradient_strength")),
            "snr": fnum(metric.get("signal_to_noise")),
            "metric_p95_delta_px": fnum(metric.get("p95_delta_px")),
            "metric_p95_accel_px": fnum(metric.get("p95_acceleration_px")),
            "metric_high_frequency_p95_px": fnum(metric.get("high_frequency_p95_px")),
            "metric_p95_delta_source_px": fnum(metric.get("p95_delta_source_px")),
            "metric_p95_accel_source_px": fnum(metric.get("p95_acceleration_source_px")),
            "metric_high_frequency_p95_source_px": fnum(metric.get("high_frequency_p95_source_px")),
            "metric_sub_source_wiggle_ratio": fnum(metric.get("sub_source_wiggle_ratio")),
            "production_tube_residual_p95_source_px": fnum(metric.get("tube_residual_p95_source_px")),
            "production_hf_rms_source_px": fnum(metric.get("corridor_hf_rms_source_px")),
            "production_hf_p95_source_px": fnum(metric.get("corridor_hf_p95_source_px")),
            "production_turn_p95_deg": fnum(metric.get("turn_p95_deg")),
            "production_turn_max_deg": fnum(metric.get("turn_max_deg")),
            "production_curvature_change_p95_deg": fnum(metric.get("curvature_change_p95_deg")),
            "production_forward_progress_violations": fnum(metric.get("forward_progress_violations")),
            "production_unsupported_excursions": fnum(metric.get("unsupported_excursions")),
            "production_true_longitudinal_persistence": fnum(metric.get("true_longitudinal_persistence")),
            "source_meters_per_pixel": optional_fnum(
                metric.get("source_meters_per_pixel") or sampling.get("sourceMetersPerPixel")),
            "metric_sign_flips": fnum(metric.get("sign_flips")),
            "offset_mean_px": mean(offsets),
            "offset_stdev_px": stdev(offsets),
            "offset_p95_delta_px": offset_stats["p95_delta"],
            "offset_p95_accel_px": offset_stats["p95_accel"],
            "offset_hf_p95_px": offset_stats["hf_p95"],
            "offset_hf_p95_m": offset_stats["hf_p95"] * raster_m_per_px,
            "offset_hf_max_px": offset_stats["hf_max"],
            "offset_hf_max_m": offset_stats["hf_max"] * raster_m_per_px,
            "offset_hf_flip_rate": offset_stats["hf_flip_rate"],
            "offset_delta_flip_rate": offset_stats["delta_flip_rate"],
            "profile_count": profile_stats.get("profile_count", 0),
            "profile_median_support_width_px": profile_stats.get("median_support_width_px", 0.0),
            "profile_median_gradient": profile_stats.get("median_gradient", 0.0),
            "profile_median_peak_count": profile_stats.get("median_peak_count", 0.0),
            "repeat_of": "",
            "repeat_input_match_max_m": 0.0,
            "repeat_point_growth": 0,
            "repeat_length_change_m": 0.0,
            "repeat_bidirectional_mean_drift_m": 0.0,
            "repeat_bidirectional_p95_drift_m": 0.0,
            "repeat_bidirectional_max_drift_m": 0.0,
            "repeat_applicable_candidate_count": applicable_count,
            "repeat_warning_delta": 0,
            "_original_points": original_points,
            "_applied_points": applied_points,
            "_selected_warning_count": len(candidate.get("safetyWarnings", []) or []),
            **cleanup_columns(
                cleanup_by_candidate.get(candidate_id),
                cleanup_anchors.get(candidate_id, []),
            ),
        })
    return rows


def cleanup_rows_by_candidate(rows: list[dict[str, str]]) -> dict[str, dict[str, str]]:
    """Index optional format-9 cleanup rows by their literal candidate identifiers."""
    result: dict[str, dict[str, str]] = {}
    for row in rows:
        candidate_id = row.get("candidate_id", "")
        if candidate_id and candidate_id not in result:
            result[candidate_id] = row
    return result


def cleanup_anchors_by_candidate(rows: list[dict[str, str]]) -> dict[str, list[dict[str, str]]]:
    """Group optional format-9 anchor rows by their literal candidate identifiers."""
    result: dict[str, list[dict[str, str]]] = {}
    for row in rows:
        candidate_id = row.get("candidate_id", "")
        if candidate_id:
            result.setdefault(candidate_id, []).append(row)
    return result


def cleanup_columns(
    cleanup: dict[str, str] | None,
    anchors: list[dict[str, str]],
) -> dict[str, object]:
    """Return normalized cleanup and parent-comparison columns.

    Args:
        cleanup: One format-9 cleanup CSV row, or ``None`` for an older bundle.
        anchors: Candidate-owned format-9 protected-anchor rows.

    Returns:
        Stable roughness-analysis columns. Missing cleanup data remains ``None`` or
        ``"unavailable"`` and is never interpreted as a measured zero.
    """
    if cleanup is None:
        return {
            "cleanup_state": "unavailable",
            "cleanup_parent_candidate_id": "",
            "cleanup_outcome": "unavailable",
            "cleanup_reason_code": "unavailable",
            "cleanup_reasons": "",
            "cleanup_before_point_count": None,
            "cleanup_smoothed_point_count": None,
            "cleanup_after_point_count": None,
            "cleanup_accepted_smoothing_passes": None,
            "cleanup_smoothing_backtrack_count": None,
            "cleanup_attempted_chord_count": None,
            "cleanup_accepted_chord_count": None,
            "cleanup_containment_failure_count": None,
            "cleanup_fit_before": None,
            "cleanup_fit_after": None,
            "cleanup_maximum_displacement_projection_units": None,
            "cleanup_projection_unit_name": "unavailable",
            "cleanup_maximum_removed_deviation_meters": None,
            "cleanup_worst_fit_retention": None,
            "cleanup_anchor_data_state": "unavailable",
            "cleanup_anchor_count": None,
            "cleanup_anchor_reason_codes": "unavailable",
            "cleanup_parent_relation_state": "unavailable",
            "cleanup_parent_offset_hf_p95_px": None,
            "cleanup_offset_hf_p95_delta_px": None,
            "cleanup_parent_warning_count": None,
            "cleanup_warning_count_delta": None,
        }
    anchor_states = sorted({row.get("anchor_data_state", "") for row in anchors if row.get("anchor_data_state", "")})
    anchor_reasons = sorted({row.get("reason_code", "") for row in anchors if row.get("reason_code", "")})
    available_anchor_count = sum(row.get("anchor_data_state", "") == "available" for row in anchors)
    return {
        "cleanup_state": "available",
        "cleanup_parent_candidate_id": cleanup.get("parent_candidate_id", ""),
        "cleanup_outcome": cleanup.get("outcome", "unavailable") or "unavailable",
        "cleanup_reason_code": cleanup.get("reason_code", "unavailable") or "unavailable",
        "cleanup_reasons": cleanup.get("reasons", ""),
        "cleanup_before_point_count": optional_int(cleanup.get("before_point_count")),
        "cleanup_smoothed_point_count": optional_int(cleanup.get("smoothed_point_count")),
        "cleanup_after_point_count": optional_int(cleanup.get("after_point_count")),
        "cleanup_accepted_smoothing_passes": optional_int(cleanup.get("accepted_smoothing_passes")),
        "cleanup_smoothing_backtrack_count": optional_int(cleanup.get("smoothing_backtrack_count")),
        "cleanup_attempted_chord_count": optional_int(cleanup.get("attempted_chord_count")),
        "cleanup_accepted_chord_count": optional_int(cleanup.get("accepted_chord_count")),
        "cleanup_containment_failure_count": optional_int(cleanup.get("containment_failure_count")),
        "cleanup_fit_before": optional_fnum(cleanup.get("fit_before")),
        "cleanup_fit_after": optional_fnum(cleanup.get("fit_after")),
        "cleanup_maximum_displacement_projection_units": optional_fnum(
            cleanup.get("maximum_displacement_projection_units")),
        "cleanup_projection_unit_name": cleanup.get("projection_unit_name", "unavailable") or "unavailable",
        "cleanup_maximum_removed_deviation_meters": optional_fnum(
            cleanup.get("maximum_removed_deviation_meters")),
        "cleanup_worst_fit_retention": optional_fnum(cleanup.get("worst_fit_retention")),
        "cleanup_anchor_data_state": ";".join(anchor_states) if anchor_states else "unavailable",
        "cleanup_anchor_count": available_anchor_count if anchors else None,
        "cleanup_anchor_reason_codes": ";".join(anchor_reasons) if anchor_reasons else "unavailable",
        "cleanup_parent_relation_state": "no-parent" if not cleanup.get("parent_candidate_id", "") else "unresolved",
        "cleanup_parent_offset_hf_p95_px": None,
        "cleanup_offset_hf_p95_delta_px": None,
        "cleanup_parent_warning_count": None,
        "cleanup_warning_count_delta": None,
    }


def read_json(archive: zipfile.ZipFile, name: str) -> dict[str, object]:
    """Read a JSON object member from a zip archive."""
    try:
        return json.loads(archive.read(name).decode("utf-8"))
    except KeyError:
        return {}


def read_text(archive: zipfile.ZipFile, name: str) -> str:
    """Read a UTF-8 text member from a zip archive."""
    try:
        return archive.read(name).decode("utf-8")
    except KeyError:
        return ""


def read_csv(archive: zipfile.ZipFile, name: str) -> list[dict[str, str]]:
    """Read a CSV member from a zip archive."""
    text = read_text(archive, name)
    return list(csv.DictReader(io.StringIO(text))) if text.strip() else []


def geometry_metrics(osm_text: str) -> dict[str, float]:
    """Compute length, turn, and local-residual metrics from a small OSM XML snippet."""
    if not osm_text.strip():
        return {}
    root = ElementTree.fromstring(osm_text)
    nodes: dict[str, tuple[float, float]] = {}
    for node in root.findall("node"):
        node_id = node.attrib.get("id")
        lat = node.attrib.get("lat")
        lon = node.attrib.get("lon")
        if node_id and lat and lon:
            nodes[node_id] = (float(lat), float(lon))
    ways = []
    for way in root.findall("way"):
        refs = [nd.attrib.get("ref", "") for nd in way.findall("nd")]
        points = [nodes[ref] for ref in refs if ref in nodes]
        if len(points) >= 2:
            ways.append(points)
    if not ways:
        return {}
    lat0 = mean([lat for way in ways for lat, _ in way])
    lon0 = mean([lon for way in ways for _, lon in way])
    projected = [project_way(max(ways, key=len), lat0, lon0)]
    points = projected[0]
    turns = turn_angles(points)
    residuals = local_residuals(points, 4)
    return {
        "points": len(points),
        "length_m": polyline_length(points),
        "p95_turn_deg": percentile([abs(value) for value in turns], 0.95),
        "max_turn_deg": max([abs(value) for value in turns], default=0.0),
        "sum_abs_turn_deg": sum(abs(value) for value in turns),
        "p95_local_residual_m": percentile([abs(value) for value in residuals], 0.95),
        "max_local_residual_m": max([abs(value) for value in residuals], default=0.0),
        "residual_flip_rate": sign_flip_rate(residuals, 0.05),
    }


def way_coordinates(osm_text: str) -> list[tuple[float, float]]:
    """Return the longest way as latitude/longitude pairs, or an empty list."""
    if not osm_text.strip():
        return []
    root = ElementTree.fromstring(osm_text)
    nodes = {
        node.attrib.get("id", ""): (float(node.attrib["lat"]), float(node.attrib["lon"]))
        for node in root.findall("node")
        if node.attrib.get("id") and node.attrib.get("lat") and node.attrib.get("lon")
    }
    ways = [
        [nodes[ref] for ref in (nd.attrib.get("ref", "") for nd in way.findall("nd")) if ref in nodes]
        for way in root.findall("way")
    ]
    usable = [way for way in ways if len(way) >= 2]
    return max(usable, key=len) if usable else []


def annotate_repeat_relationships(rows: list[dict[str, object]], match_tolerance_m: float = 0.10) -> None:
    """Annotate consecutive slide bundles whose input equals the prior applied geometry."""
    representatives: dict[str, dict[str, object]] = {}
    for row in rows:
        bundle = str(row["bundle"])
        current = representatives.get(bundle)
        if current is None or bool(row.get("selected")):
            representatives[bundle] = row
    ordered = [representatives[name] for name in sorted(representatives)]
    for previous, current in zip(ordered, ordered[1:]):
        previous_applied = previous.get("_applied_points", [])
        current_original = current.get("_original_points", [])
        if not previous_applied or not current_original:
            continue
        input_drift = bidirectional_polyline_drift(previous_applied, current_original)
        if input_drift["maximum"] > match_tolerance_m:
            continue
        output_drift = bidirectional_polyline_drift(
            current_original, current.get("_applied_points", []) or current_original)
        bundle_rows = [row for row in rows if row["bundle"] == current["bundle"]]
        for row in bundle_rows:
            row["repeat_of"] = previous["bundle"]
            row["repeat_input_match_max_m"] = input_drift["maximum"]
            row["repeat_point_growth"] = int(row.get("applied_nodes", 0)) - int(row.get("original_nodes", 0))
            row["repeat_length_change_m"] = float(row.get("applied_len_m", 0.0)) \
                - float(row.get("original_len_m", 0.0))
            row["repeat_bidirectional_mean_drift_m"] = output_drift["mean"]
            row["repeat_bidirectional_p95_drift_m"] = output_drift["p95"]
            row["repeat_bidirectional_max_drift_m"] = output_drift["maximum"]
            row["repeat_warning_delta"] = int(current.get("_selected_warning_count", 0)) \
                - int(previous.get("_selected_warning_count", 0))


def annotate_cleanup_relationships(rows: list[dict[str, object]]) -> None:
    """Compare cleaned variants with their explicit raw parent without parsing candidate ids."""
    by_bundle_and_id = {
        (str(row.get("bundle", "")), str(row.get("candidate_id", ""))): row for row in rows
    }
    for row in rows:
        if row.get("cleanup_state") != "available":
            continue
        parent_id = str(row.get("cleanup_parent_candidate_id", ""))
        if not parent_id:
            row["cleanup_parent_relation_state"] = "no-parent"
            continue
        parent = by_bundle_and_id.get((str(row.get("bundle", "")), parent_id))
        if parent is None:
            row["cleanup_parent_relation_state"] = "parent-not-exported"
            continue
        row["cleanup_parent_relation_state"] = "available"
        parent_hf = optional_fnum(parent.get("offset_hf_p95_px"))
        child_hf = optional_fnum(row.get("offset_hf_p95_px"))
        row["cleanup_parent_offset_hf_p95_px"] = parent_hf
        row["cleanup_offset_hf_p95_delta_px"] = (
            child_hf - parent_hf if child_hf is not None and parent_hf is not None else None)
        parent_warnings = optional_int(parent.get("_selected_warning_count"))
        child_warnings = optional_int(row.get("_selected_warning_count"))
        row["cleanup_parent_warning_count"] = parent_warnings
        row["cleanup_warning_count_delta"] = (
            child_warnings - parent_warnings
            if child_warnings is not None and parent_warnings is not None else None)


def bidirectional_polyline_drift(
    left: list[tuple[float, float]],
    right: list[tuple[float, float]],
) -> dict[str, float]:
    """Return symmetric point-to-polyline distances in a shared local metric plane."""
    if len(left) < 2 or len(right) < 2:
        return {"mean": 0.0, "p95": 0.0, "maximum": 0.0}
    latitude = mean(point[0] for point in left + right)
    longitude = mean(point[1] for point in left + right)
    left_xy = project_way(left, latitude, longitude)
    right_xy = project_way(right, latitude, longitude)
    distances = [point_to_polyline_distance(point, right_xy) for point in left_xy]
    distances.extend(point_to_polyline_distance(point, left_xy) for point in right_xy)
    return {
        "mean": mean(distances),
        "p95": percentile(distances, 0.95),
        "maximum": max(distances, default=0.0),
    }


def point_to_polyline_distance(
    point: tuple[float, float],
    polyline: list[tuple[float, float]],
) -> float:
    """Return minimum Euclidean distance from a point to a polyline."""
    return min(point_to_segment_distance(point, polyline[index - 1], polyline[index])
               for index in range(1, len(polyline)))


def point_to_segment_distance(point, start, end) -> float:
    """Return Euclidean point-to-segment distance."""
    dx = end[0] - start[0]
    dy = end[1] - start[1]
    denominator = dx * dx + dy * dy
    if denominator <= 1e-18:
        return distance(point, start)
    fraction = max(0.0, min(1.0,
        ((point[0] - start[0]) * dx + (point[1] - start[1]) * dy) / denominator))
    projection = (start[0] + fraction * dx, start[1] + fraction * dy)
    return distance(point, projection)


def project_way(points: list[tuple[float, float]], lat0: float, lon0: float) -> list[tuple[float, float]]:
    """Project lat/lon points to a local metric plane for roughness analysis."""
    meters_per_deg_lat = 111_320.0
    meters_per_deg_lon = math.cos(math.radians(lat0)) * 111_320.0
    return [((lon - lon0) * meters_per_deg_lon, (lat - lat0) * meters_per_deg_lat) for lat, lon in points]


def polyline_length(points: list[tuple[float, float]]) -> float:
    """Return total length of a 2D polyline."""
    return sum(distance(points[i - 1], points[i]) for i in range(1, len(points)))


def distance(left: tuple[float, float], right: tuple[float, float]) -> float:
    """Return Euclidean distance between two 2D points."""
    return math.hypot(right[0] - left[0], right[1] - left[1])


def turn_angles(points: list[tuple[float, float]]) -> list[float]:
    """Return signed turn angles between consecutive polyline segments in degrees."""
    turns = []
    for i in range(1, len(points) - 1):
        ax = points[i][0] - points[i - 1][0]
        ay = points[i][1] - points[i - 1][1]
        bx = points[i + 1][0] - points[i][0]
        by = points[i + 1][1] - points[i][1]
        left = math.atan2(ay, ax)
        right = math.atan2(by, bx)
        turns.append(math.degrees(wrap_angle(right - left)))
    return turns


def local_residuals(points: list[tuple[float, float]], window: int) -> list[float]:
    """Return signed residuals from each point to its surrounding local chord."""
    residuals = []
    for i in range(window, len(points) - window):
        start = points[i - window]
        end = points[i + window]
        residuals.append(signed_distance_to_line(points[i], start, end))
    return residuals


def signed_distance_to_line(point, start, end) -> float:
    """Return signed perpendicular distance from a point to a line."""
    vx = end[0] - start[0]
    vy = end[1] - start[1]
    norm = math.hypot(vx, vy)
    if norm <= 1e-9:
        return 0.0
    return ((point[0] - start[0]) * vy - (point[1] - start[1]) * vx) / norm


def wrap_angle(value: float) -> float:
    """Wrap an angle in radians into the ``(-pi, pi]`` interval."""
    while value <= -math.pi:
        value += math.tau
    while value > math.pi:
        value -= math.tau
    return value


def profile_summary(rows: list[dict[str, str]]) -> dict[str, dict[str, float]]:
    """Aggregate per-profile peak diagnostics by detector name."""
    grouped: dict[str, list[dict[str, str]]] = {}
    peak_counts: dict[str, dict[int, int]] = {}
    for row in rows:
        detector = row.get("detector", "")
        grouped.setdefault(detector, []).append(row)
        profile_index = int(float(row.get("profile_index") or 0))
        peak_counts.setdefault(detector, {}).setdefault(profile_index, 0)
        peak_counts[detector][profile_index] += 1
    summary = {}
    for detector, group in grouped.items():
        summary[detector] = {
            "profile_count": len(peak_counts.get(detector, {})),
            "median_support_width_px": median(fnum(row.get("support_width_px")) for row in group),
            "median_gradient": median(fnum(row.get("gradient_strength")) for row in group),
            "median_peak_count": median(peak_counts.get(detector, {}).values()),
        }
    return summary


def offset_roughness(offsets: list[float]) -> dict[str, float]:
    """Compute first-difference, acceleration, and high-frequency offset metrics."""
    deltas = [offsets[i] - offsets[i - 1] for i in range(1, len(offsets))]
    accels = [deltas[i] - deltas[i - 1] for i in range(1, len(deltas))]
    smooth = moving_average(offsets, 9)
    residuals = [offset - smooth[i] for i, offset in enumerate(offsets)]
    return {
        "p95_delta": percentile([abs(value) for value in deltas], 0.95),
        "p95_accel": percentile([abs(value) for value in accels], 0.95),
        "hf_p95": percentile([abs(value) for value in residuals], 0.95),
        "hf_max": max([abs(value) for value in residuals], default=0.0),
        "hf_flip_rate": sign_flip_rate(residuals, 0.5),
        "delta_flip_rate": sign_flip_rate(deltas, 0.5),
    }


def moving_average(values: list[float], window: int) -> list[float]:
    """Return a centered moving average with edge clipping."""
    if not values:
        return []
    radius = max(1, window // 2)
    smoothed = []
    for i in range(len(values)):
        start = max(0, i - radius)
        end = min(len(values), i + radius + 1)
        smoothed.append(mean(values[start:end]))
    return smoothed


def sign_flip_rate(values, threshold: float) -> float:
    """Return the rate at which sufficiently large signed values alternate sign."""
    filtered = [value for value in values if abs(value) >= threshold]
    if len(filtered) < 2:
        return 0.0
    flips = sum(1 for i in range(1, len(filtered)) if math.copysign(1.0, filtered[i]) != math.copysign(1.0, filtered[i - 1]))
    return flips / (len(filtered) - 1)


def numbers(values) -> list[float]:
    """Convert an optional JSON/list value to a list of floats."""
    return [float(value) for value in values or []]


def fnum(value) -> float:
    """Parse a float, returning zero for missing or invalid values."""
    try:
        return float(value)
    except (TypeError, ValueError):
        return 0.0


def optional_fnum(value) -> float | None:
    """Parse an optional float without turning unavailable physical metadata into zero."""
    try:
        return float(value) if value not in (None, "") else None
    except (TypeError, ValueError):
        return None


def optional_int(value) -> int | None:
    """Parse an optional integer without converting unavailable diagnostics to zero."""
    try:
        return int(value) if value not in (None, "") else None
    except (TypeError, ValueError):
        return None


def mean(values) -> float:
    """Return the arithmetic mean or zero for empty input."""
    values = list(values)
    return statistics.mean(values) if values else 0.0


def median(values) -> float:
    """Return the median or zero for empty input."""
    values = [float(value) for value in values]
    return statistics.median(values) if values else 0.0


def stdev(values: list[float]) -> float:
    """Return population standard deviation or zero for fewer than two values."""
    return statistics.pstdev(values) if len(values) >= 2 else 0.0


def percentile(values: list[float], fraction: float) -> float:
    """Return a nearest-rank percentile or zero for empty input."""
    values = sorted(values)
    if not values:
        return 0.0
    index = max(0, min(len(values) - 1, math.ceil(len(values) * fraction) - 1))
    return values[index]


def write_csv(path: Path, rows: list[dict[str, object]]) -> None:
    """Write dictionaries as CSV using the first row's field order."""
    if not rows:
        path.write_text("", encoding="utf-8")
        return
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)


def bundle_summary(rows: list[dict[str, object]]) -> list[dict[str, object]]:
    """Build JSON-ready per-bundle summaries from per-candidate rows."""
    bundles = []
    for bundle in sorted({str(row["bundle"]) for row in rows}):
        group = [row for row in rows if row["bundle"] == bundle]
        selected = next((row for row in group if row["selected"]), group[0])
        bundles.append({
            "bundle": bundle,
            "selected": selected,
            "best_by_hf_p95": min(group, key=lambda row: float(row["offset_hf_p95_px"])),
            "best_by_p95_accel": min(group, key=lambda row: float(row["offset_p95_accel_px"])),
            "top_ranked": group[:5],
        })
    return bundles


def print_summary(rows: list[dict[str, object]], top: int) -> None:
    """Print a compact per-bundle roughness summary to standard output."""
    if not rows:
        print("No debug bundles found.", file=sys.stderr)
        return
    for bundle in sorted({str(row["bundle"]) for row in rows}):
        group = [row for row in rows if row["bundle"] == bundle]
        selected = next((row for row in group if row["selected"]), group[0])
        best_hf = min(group, key=lambda row: float(row["offset_hf_p95_px"]))
        print(f"\n== {bundle} ==")
        print(
            "selected={candidate_id} rank={rank} mode={mode} step={step_m} half={half_width_m} "
            "hf95={offset_hf_p95_px:.2f}px accel95={offset_p95_accel_px:.2f}px "
            "({offset_hf_p95_m:.2f}m) delta95={offset_p95_delta_px:.2f}px flips={offset_delta_flip_rate:.2f} "
            "preview_turn95={preview_p95_turn_deg:.1f}deg preview_resid95={preview_p95_local_residual_m:.2f}m".format(**selected)
        )
        print(
            "smoothest={candidate_id} rank={rank} hf95={offset_hf_p95_px:.2f}px "
            "({offset_hf_p95_m:.2f}m) accel95={offset_p95_accel_px:.2f}px snr={snr:.2f}".format(**best_hf)
        )
        if selected.get("repeat_of"):
            print(
                "repeat_of={repeat_of} input_match_max={repeat_input_match_max_m:.3f}m "
                "drift_mean/p95/max={repeat_bidirectional_mean_drift_m:.3f}/"
                "{repeat_bidirectional_p95_drift_m:.3f}/{repeat_bidirectional_max_drift_m:.3f}m "
                "point_growth={repeat_point_growth:+d} applicable={repeat_applicable_candidate_count}".format(
                    **selected)
            )
        for row in group[:top]:
            print(
                "  #{rank:>2} {candidate_id:<30} sel={selected!s:<5} "
                "score={calibrated_score:.2f} snr={snr:.2f} grad={mean_gradient:.2f} "
                "hf95={offset_hf_p95_px:.2f}px acc95={offset_p95_accel_px:.2f}px "
                "supportW={profile_median_support_width_px:.0f}px peaks={profile_median_peak_count:.1f}".format(**row)
            )


if __name__ == "__main__":
    main()
