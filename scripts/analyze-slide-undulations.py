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
from dataclasses import dataclass
from pathlib import Path
from xml.etree import ElementTree


@dataclass(frozen=True)
class Bundle:
    name: str
    data: bytes


def main() -> None:
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

    rows.sort(key=lambda row: (str(row["bundle"]), int(row["rank"])))
    if args.csv:
        write_csv(args.csv, rows)
    if args.json:
        args.json.write_text(json.dumps({"bundles": bundle_summary(rows)}, indent=2), encoding="utf-8")
    print_summary(rows, args.top)


def discover_bundles(paths: list[Path]) -> list[Bundle]:
    bundles: list[Bundle] = []
    for path in paths:
        if path.is_dir():
            for child in sorted(path.glob("*.zip")):
                bundles.extend(discover_bundles([child]))
            continue
        data = path.read_bytes()
        if is_debug_bundle(data):
            bundles.append(Bundle(path.name, data))
            continue
        with zipfile.ZipFile(io.BytesIO(data)) as outer:
            for name in sorted(outer.namelist()):
                if not name.endswith(".zip"):
                    continue
                nested = outer.read(name)
                if is_debug_bundle(nested):
                    bundles.append(Bundle(name, nested))
    return bundles


def is_debug_bundle(data: bytes) -> bool:
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            names = set(archive.namelist())
            return "diagnostics.json" in names and "candidate-metrics.csv" in names
    except zipfile.BadZipFile:
        return False


def analyze_bundle(bundle: Bundle) -> list[dict[str, object]]:
    with zipfile.ZipFile(io.BytesIO(bundle.data)) as archive:
        diagnostics = read_json(archive, "diagnostics.json")
        status = read_json(archive, "status.json")
        candidate_metrics = read_csv(archive, "candidate-metrics.csv")
        profile_rows = read_csv(archive, "profile-peaks.csv")
        original = geometry_metrics(read_text(archive, "original-segment.osm"))
        preview = geometry_metrics(read_text(archive, "preview-segment.osm"))

    profiles_by_detector = profile_summary(profile_rows)
    candidates = {candidate.get("id", ""): candidate for candidate in diagnostics.get("candidates", [])}
    selected = status.get("selectedCandidate", "")
    sampling = diagnostics.get("sampling", {})
    config = diagnostics.get("config", {})
    raster_m_per_px = fnum(sampling.get("rasterMetersPerPixel"))
    rows: list[dict[str, object]] = []
    for metric in candidate_metrics:
        candidate_id = metric.get("candidate_id", "")
        candidate = candidates.get(candidate_id, {})
        offsets = numbers(candidate.get("offsetsPx", []))
        offset_stats = offset_roughness(offsets)
        profile_stats = profiles_by_detector.get(metric.get("detector", ""), {})
        rows.append({
            "bundle": bundle.name,
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
            "original_len_m": original.get("length_m", 0.0),
            "preview_len_m": preview.get("length_m", 0.0),
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
        })
    return rows


def read_json(archive: zipfile.ZipFile, name: str) -> dict[str, object]:
    try:
        return json.loads(archive.read(name).decode("utf-8"))
    except KeyError:
        return {}


def read_text(archive: zipfile.ZipFile, name: str) -> str:
    try:
        return archive.read(name).decode("utf-8")
    except KeyError:
        return ""


def read_csv(archive: zipfile.ZipFile, name: str) -> list[dict[str, str]]:
    text = read_text(archive, name)
    return list(csv.DictReader(io.StringIO(text))) if text.strip() else []


def geometry_metrics(osm_text: str) -> dict[str, float]:
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


def project_way(points: list[tuple[float, float]], lat0: float, lon0: float) -> list[tuple[float, float]]:
    meters_per_deg_lat = 111_320.0
    meters_per_deg_lon = math.cos(math.radians(lat0)) * 111_320.0
    return [((lon - lon0) * meters_per_deg_lon, (lat - lat0) * meters_per_deg_lat) for lat, lon in points]


def polyline_length(points: list[tuple[float, float]]) -> float:
    return sum(distance(points[i - 1], points[i]) for i in range(1, len(points)))


def distance(left: tuple[float, float], right: tuple[float, float]) -> float:
    return math.hypot(right[0] - left[0], right[1] - left[1])


def turn_angles(points: list[tuple[float, float]]) -> list[float]:
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
    residuals = []
    for i in range(window, len(points) - window):
        start = points[i - window]
        end = points[i + window]
        residuals.append(signed_distance_to_line(points[i], start, end))
    return residuals


def signed_distance_to_line(point, start, end) -> float:
    vx = end[0] - start[0]
    vy = end[1] - start[1]
    norm = math.hypot(vx, vy)
    if norm <= 1e-9:
        return 0.0
    return ((point[0] - start[0]) * vy - (point[1] - start[1]) * vx) / norm


def wrap_angle(value: float) -> float:
    while value <= -math.pi:
        value += math.tau
    while value > math.pi:
        value -= math.tau
    return value


def profile_summary(rows: list[dict[str, str]]) -> dict[str, dict[str, float]]:
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
    filtered = [value for value in values if abs(value) >= threshold]
    if len(filtered) < 2:
        return 0.0
    flips = sum(1 for i in range(1, len(filtered)) if math.copysign(1.0, filtered[i]) != math.copysign(1.0, filtered[i - 1]))
    return flips / (len(filtered) - 1)


def numbers(values) -> list[float]:
    return [float(value) for value in values or []]


def fnum(value) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return 0.0


def mean(values) -> float:
    values = list(values)
    return statistics.mean(values) if values else 0.0


def median(values) -> float:
    values = [float(value) for value in values]
    return statistics.median(values) if values else 0.0


def stdev(values: list[float]) -> float:
    return statistics.pstdev(values) if len(values) >= 2 else 0.0


def percentile(values: list[float], fraction: float) -> float:
    values = sorted(values)
    if not values:
        return 0.0
    index = max(0, min(len(values) - 1, math.ceil(len(values) * fraction) - 1))
    return values[index]


def write_csv(path: Path, rows: list[dict[str, object]]) -> None:
    if not rows:
        path.write_text("", encoding="utf-8")
        return
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)


def bundle_summary(rows: list[dict[str, object]]) -> list[dict[str, object]]:
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
        for row in group[:top]:
            print(
                "  #{rank:>2} {candidate_id:<30} sel={selected!s:<5} "
                "score={calibrated_score:.2f} snr={snr:.2f} grad={mean_gradient:.2f} "
                "hf95={offset_hf_p95_px:.2f}px acc95={offset_p95_accel_px:.2f}px "
                "supportW={profile_median_support_width_px:.0f}px peaks={profile_median_peak_count:.1f}".format(**row)
            )


if __name__ == "__main__":
    main()
