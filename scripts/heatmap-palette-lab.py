#!/usr/bin/env python3
"""Collect Strava heatmap palette samples from debug bundles, tile dumps, or JOSM cache files.

The script writes CSV summaries that are meant to be analyzed outside Codex:

  python3 scripts/heatmap-palette-lab.py last-slide-debug.zip ~/.cache/JOSM \
      --output-dir palette-lab --copy-images --write-pixels

It never needs Strava cookies. It only reads local images or WayHeatmapTracer debug
bundles, and it does not upload anything.
"""

from __future__ import annotations

import argparse
import colorsys
import csv
import hashlib
import io
import json
import math
import os
import re
import shutil
import sys
import zipfile
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

try:
    from PIL import Image
except ImportError:  # pragma: no cover - user-facing environment message
    print("This script requires Pillow: python3 -m pip install Pillow", file=sys.stderr)
    raise


SCHEMES = [
    "hot",
    "blue",
    "bluered",
    "purple",
    "gray",
    "gray-magenta",
    "gray-corridor",
    "dual",
    "hot-corridor",
    "hot-strict",
    "bluered-cool",
    "bluered-corridor",
    "dual-corridor",
    "gray-strict",
    "purple-strict",
    "bluered-combined",
    "gray-combined",
    "multi-combined",
]

BASE_EXPORT_COLORS = {"hot", "blue", "bluered", "purple", "gray"}
IMAGE_SUFFIXES = {".png", ".jpg", ".jpeg", ".webp"}


@dataclass(frozen=True)
class ImageSource:
    """Readable heatmap image discovered from a file, directory, cache, or zip bundle."""
    label: str
    scheme_hint: str
    bytes_data: bytes | None
    path: Path | None


@dataclass(frozen=True)
class PixelSample:
    """Distinct RGBA color sample with an occurrence count."""
    source: str
    scheme_hint: str
    red: int
    green: int
    blue: int
    alpha: int
    count: int


def main() -> None:
    """Run palette clustering and optional profile-filter analysis for local heatmap images."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", nargs="+", type=Path, help="Debug bundles, image files, image directories, or JOSM cache roots")
    parser.add_argument("--output-dir", type=Path, default=Path("heatmap-palette-lab"), help="Directory for CSV/image outputs")
    parser.add_argument("--copy-images", action="store_true", help="Copy readable source images into output-dir/images")
    parser.add_argument("--write-pixels", action="store_true", help="Write sampled per-color pixels; can be large")
    parser.add_argument(
        "--analyze-filters",
        action="store_true",
        help="Write filter-summary.csv with raw/B3/B5 cross-section stability metrics for each image",
    )
    parser.add_argument("--max-sampled-pixels", type=int, default=1_500_000, help="Maximum pixels sampled per image before stride sampling")
    parser.add_argument("--max-colors-per-image", type=int, default=20000, help="Maximum distinct colors sampled per image")
    parser.add_argument("--clusters", type=int, default=12, help="Number of RGB clusters per image")
    parser.add_argument("--min-alpha", type=int, default=16, help="Ignore pixels with alpha at or below this value")
    args = parser.parse_args()

    args.output_dir.mkdir(parents=True, exist_ok=True)
    image_dir = args.output_dir / "images"
    if args.copy_images:
        image_dir.mkdir(parents=True, exist_ok=True)

    sources = list(discover_sources(args.inputs))
    if not sources:
        raise SystemExit("No readable image sources found.")

    image_rows: list[dict[str, object]] = []
    cluster_rows: list[dict[str, object]] = []
    pixel_rows: list[dict[str, object]] = []
    filter_rows: list[dict[str, object]] = []
    summary_by_scheme: dict[str, Counter[tuple[int, int, int]]] = defaultdict(Counter)

    for source in sources:
        try:
            image = open_image(source)
        except Exception as exc:  # noqa: BLE001 - keep scanning imperfect cache directories
            image_rows.append({"source": source.label, "error": str(exc)})
            continue
        pixels = collect_pixels(image, source, args.min_alpha, args.max_colors_per_image, args.max_sampled_pixels)
        if not pixels:
            image_rows.append({"source": source.label, "scheme_hint": source.scheme_hint, "opaque_pixels": 0})
            continue
        for pixel in pixels:
            summary_by_scheme[source.scheme_hint].update({(pixel.red, pixel.green, pixel.blue): pixel.count})

        copied_name = ""
        if args.copy_images:
            copied_name = copy_source_image(source, image, image_dir)
        image_rows.append(image_summary_row(source, image, pixels, copied_name))
        cluster_rows.extend(cluster_rows_for_image(source, pixels, args.clusters))
        if args.write_pixels:
            pixel_rows.extend(pixel_rows_for_image(pixels))
        if args.analyze_filters:
            filter_rows.extend(filter_rows_for_image(source, image, args.min_alpha))

    write_csv(args.output_dir / "images.csv", image_rows)
    write_csv(args.output_dir / "palette-clusters.csv", cluster_rows)
    write_csv(args.output_dir / "scheme-summary.csv", scheme_summary_rows(summary_by_scheme))
    if args.write_pixels:
        write_csv(args.output_dir / "pixel-samples.csv", pixel_rows)
    if args.analyze_filters:
        write_csv(args.output_dir / "filter-summary.csv", filter_rows)
    write_json(args.output_dir / "manifest.json", {
        "sourceCount": len(sources),
        "outputs": [
            "images.csv",
            "palette-clusters.csv",
            "scheme-summary.csv",
            "pixel-samples.csv" if args.write_pixels else "",
            "filter-summary.csv" if args.analyze_filters else "",
        ],
        "notes": [
            "scheme_hint comes from file names/paths when available; inspect images.csv before using it as truth",
            "intensity columns reproduce WayHeatmapTracer color-to-intensity mappings for calibration review",
        ],
    })


def discover_sources(inputs: Iterable[Path]) -> Iterable[ImageSource]:
    """Yield image sources from files, zip bundles, directories, or cache roots."""
    for path in inputs:
        if path.is_file() and path.suffix.lower() == ".zip":
            yield from discover_zip(path)
        elif path.is_file() and path.suffix.lower() in IMAGE_SUFFIXES:
            yield ImageSource(str(path), scheme_hint_for(path.as_posix()), None, path)
        elif path.is_dir():
            yield from discover_dir(path)


def discover_zip(path: Path) -> Iterable[ImageSource]:
    """Yield image sources stored inside a zip file."""
    with zipfile.ZipFile(path) as archive:
        for name in archive.namelist():
            suffix = Path(name).suffix.lower()
            if suffix not in IMAGE_SUFFIXES:
                continue
            yield ImageSource(f"{path.name}:{name}", scheme_hint_for(name), archive.read(name), None)


def discover_dir(path: Path) -> Iterable[ImageSource]:
    """Yield image sources below a directory tree."""
    for root, _, files in os.walk(path):
        for file_name in files:
            file_path = Path(root) / file_name
            if file_path.suffix.lower() in IMAGE_SUFFIXES:
                yield ImageSource(str(file_path), scheme_hint_for(file_path.as_posix()), None, file_path)


def open_image(source: ImageSource) -> Image.Image:
    """Open an image source as RGBA pixels."""
    if source.bytes_data is not None:
        return Image.open(io.BytesIO(source.bytes_data)).convert("RGBA")
    if source.path is None:
        raise ValueError("image source has neither bytes nor path")
    return Image.open(source.path).convert("RGBA")


def collect_pixels(
    image: Image.Image,
    source: ImageSource,
    min_alpha: int,
    max_colors: int,
    max_sampled_pixels: int,
) -> list[PixelSample]:
    """Collect distinct non-transparent colors from an image with optional downsampling."""
    total_pixels = image.width * image.height
    weight = 1
    if total_pixels > max_sampled_pixels > 0:
        scale = math.sqrt(max_sampled_pixels / total_pixels)
        width = max(1, int(image.width * scale))
        height = max(1, int(image.height * scale))
        resampling = Image.Resampling.NEAREST if hasattr(Image, "Resampling") else Image.NEAREST
        image = image.resize((width, height), resampling)
        weight = max(1, round(total_pixels / (width * height)))
    colors = Counter()
    pixel_data = image.get_flattened_data() if hasattr(image, "get_flattened_data") else image.getdata()
    for red, green, blue, alpha in pixel_data:
        if alpha <= min_alpha:
            continue
        colors[(red, green, blue, alpha)] += weight
    if len(colors) > max_colors:
        colors = Counter(dict(colors.most_common(max_colors)))
    return [
        PixelSample(source.label, source.scheme_hint, red, green, blue, alpha, count)
        for (red, green, blue, alpha), count in colors.items()
    ]


def image_summary_row(source: ImageSource, image: Image.Image, pixels: list[PixelSample], copied_name: str) -> dict[str, object]:
    """Build the per-image summary row for ``images.csv``."""
    total = sum(pixel.count for pixel in pixels)
    intensities = [max(intensity(pixel.red, pixel.green, pixel.blue, scheme) for scheme in SCHEMES) for pixel in pixels]
    weighted_mean = sum(value * pixel.count for value, pixel in zip(intensities, pixels, strict=True)) / max(1, total)
    return {
        "source": source.label,
        "copied_image": copied_name,
        "scheme_hint": source.scheme_hint,
        "width": image.width,
        "height": image.height,
        "sampled_distinct_colors": len(pixels),
        "sampled_opaque_pixels": total,
        "mean_any_scheme_intensity": round(weighted_mean, 6),
    }


def cluster_rows_for_image(source: ImageSource, pixels: list[PixelSample], cluster_count: int) -> list[dict[str, object]]:
    """Cluster distinct image colors and emit color-to-intensity diagnostics for each cluster."""
    clusters = weighted_kmeans(pixels, cluster_count)
    total = sum(pixel.count for pixel in pixels)
    rows = []
    for index, members in enumerate(clusters):
        if not members:
            continue
        weight = sum(pixel.count for pixel in members)
        weights = [pixel.count for pixel in members]
        red = weighted_mean((pixel.red for pixel in members), weights)
        green = weighted_mean((pixel.green for pixel in members), weights)
        blue = weighted_mean((pixel.blue for pixel in members), weights)
        hue, saturation, value = colorsys.rgb_to_hsv(red / 255.0, green / 255.0, blue / 255.0)
        row = {
            "source": source.label,
            "scheme_hint": source.scheme_hint,
            "cluster": index,
            "count": weight,
            "pct": round(weight / max(1, total), 6),
            "red": round(red, 3),
            "green": round(green, 3),
            "blue": round(blue, 3),
            "hue": round(hue * 360.0, 3),
            "saturation": round(saturation, 6),
            "value": round(value, 6),
        }
        for scheme in SCHEMES:
            row[f"intensity_{scheme}"] = round(intensity(int(red), int(green), int(blue), scheme), 6)
        rows.append(row)
    return rows


def weighted_mean(values: Iterable[float], weights: list[int]) -> float:
    """Return a weighted arithmetic mean."""
    values_list = list(values)
    total = sum(weights)
    return sum(value * weight for value, weight in zip(values_list, weights, strict=True)) / max(1, total)


def weighted_kmeans(pixels: list[PixelSample], cluster_count: int) -> list[list[PixelSample]]:
    """Run a small weighted RGB k-means for palette summarization."""
    points = sorted(pixels, key=lambda pixel: pixel.count, reverse=True)
    centers = [(pixel.red, pixel.green, pixel.blue) for pixel in points[:max(1, cluster_count)]]
    while len(centers) < cluster_count:
        centers.append(centers[-1])
    assignments: list[list[PixelSample]] = []
    for _ in range(12):
        assignments = [[] for _ in centers]
        for pixel in points:
            best = min(range(len(centers)), key=lambda i: distance2((pixel.red, pixel.green, pixel.blue), centers[i]))
            assignments[best].append(pixel)
        new_centers = []
        for members, center in zip(assignments, centers, strict=True):
            if not members:
                new_centers.append(center)
                continue
            weights = [pixel.count for pixel in members]
            new_centers.append((
                weighted_mean((pixel.red for pixel in members), weights),
                weighted_mean((pixel.green for pixel in members), weights),
                weighted_mean((pixel.blue for pixel in members), weights),
            ))
        if max(distance2(left, right) for left, right in zip(centers, new_centers, strict=True)) < 0.5:
            break
        centers = new_centers
    return assignments


def distance2(left: tuple[float, float, float], right: tuple[float, float, float]) -> float:
    """Return squared Euclidean RGB distance."""
    return sum((a - b) ** 2 for a, b in zip(left, right, strict=True))


def pixel_rows_for_image(pixels: list[PixelSample]) -> list[dict[str, object]]:
    """Expand distinct color samples into optional per-pixel CSV rows."""
    rows = []
    for pixel in pixels:
        hue, saturation, value = colorsys.rgb_to_hsv(pixel.red / 255.0, pixel.green / 255.0, pixel.blue / 255.0)
        row = {
            "source": pixel.source,
            "scheme_hint": pixel.scheme_hint,
            "count": pixel.count,
            "red": pixel.red,
            "green": pixel.green,
            "blue": pixel.blue,
            "alpha": pixel.alpha,
            "hue": round(hue * 360.0, 3),
            "saturation": round(saturation, 6),
            "value": round(value, 6),
        }
        for scheme in SCHEMES:
            row[f"intensity_{scheme}"] = round(intensity(pixel.red, pixel.green, pixel.blue, scheme), 6)
        rows.append(row)
    return rows


def scheme_summary_rows(summary_by_scheme: dict[str, Counter[tuple[int, int, int]]]) -> list[dict[str, object]]:
    """Summarize every tested mapping against colors grouped by inferred scheme."""
    rows = []
    for scheme_hint, colors in sorted(summary_by_scheme.items()):
        total = sum(colors.values())
        for scheme in SCHEMES:
            weighted = [(intensity(red, green, blue, scheme), count) for (red, green, blue), count in colors.items()]
            rows.append({
                "scheme_hint": scheme_hint,
                "tested_mapping": scheme,
                "sampled_pixels": total,
                "mean_intensity": round(sum(value * count for value, count in weighted) / max(1, total), 6),
                "p90_intensity": round(weighted_quantile(weighted, 0.90), 6),
                "p99_intensity": round(weighted_quantile(weighted, 0.99), 6),
                "heat_pixel_ratio_0_16": round(sum(count for value, count in weighted if value > 0.16) / max(1, total), 6),
                "heat_pixel_ratio_0_35": round(sum(count for value, count in weighted if value > 0.35) / max(1, total), 6),
            })
    return rows


def filter_rows_for_image(source: ImageSource, image: Image.Image, min_alpha: int) -> list[dict[str, object]]:
    """Summarize Java-equivalent profile filters on real heatmap image scanlines.

    Args:
        source: Image source metadata used for labels and scheme hints.
        image: RGBA image whose pixels are already loaded locally.
        min_alpha: Alpha values at or below this threshold are treated as no heatmap signal.

    Returns:
        Rows for ``filter-summary.csv``. Each row compares one filter with the raw profile centers.
    """
    scheme = source.scheme_hint if source.scheme_hint in BASE_EXPORT_COLORS else "hot"
    grid = intensity_grid(image, scheme, min_alpha)
    profiles = informative_profiles(grid)
    rows: list[dict[str, object]] = []
    for filter_name, filtered_profiles in [
        ("b3_power_p2_blend", [power_binomial_filter(profile, [1.0, 2.0, 1.0], 0.45, 0.30, 0.15) for profile in profiles]),
        ("b5_power_p2_blend", [power_binomial_filter(profile, [1.0, 4.0, 6.0, 4.0, 1.0], 0.35, 0.25, 0.10) for profile in profiles]),
    ]:
        shifts: list[float] = []
        width_deltas: list[float] = []
        peak_deltas: list[float] = []
        lost = 0
        for raw, filtered in zip(profiles, filtered_profiles, strict=True):
            raw_center = profile_center(raw)
            filtered_center = profile_center(filtered)
            if raw_center is None:
                continue
            if filtered_center is None:
                lost += 1
                continue
            shifts.append(abs(filtered_center - raw_center))
            width_deltas.append(profile_width(filtered) - profile_width(raw))
            peak_deltas.append(profile_peak_count(filtered) - profile_peak_count(raw))
        rows.append({
            "source": source.label,
            "scheme_hint": source.scheme_hint,
            "tested_mapping": scheme,
            "filter": filter_name,
            "profile_count": len(profiles),
            "lost_profile_count": lost,
            "shift_median_px": round(quantile(shifts, 0.50), 6),
            "shift_p90_px": round(quantile(shifts, 0.90), 6),
            "shift_p95_px": round(quantile(shifts, 0.95), 6),
            "width_delta_median_px": round(quantile(width_deltas, 0.50), 6),
            "width_delta_p90_px": round(quantile(width_deltas, 0.90), 6),
            "peak_delta_median": round(quantile(peak_deltas, 0.50), 6),
        })
    return rows


def intensity_grid(image: Image.Image, scheme: str, min_alpha: int) -> list[list[float]]:
    """Convert an image to a scalar intensity grid using the same mappings as the plugin.

    Args:
        image: RGBA source tile or mosaic.
        scheme: Color-to-intensity mapping to apply.
        min_alpha: Alpha values at or below this threshold are ignored.

    Returns:
        Two-dimensional list indexed as ``grid[y][x]`` with values in ``[0, 1]``.
    """
    color_cache: dict[tuple[int, int, int], float] = {}
    data = list(image.get_flattened_data() if hasattr(image, "get_flattened_data") else image.getdata())
    rows: list[list[float]] = []
    for y in range(image.height):
        row: list[float] = []
        for x in range(image.width):
            red, green, blue, alpha = data[y * image.width + x]
            if alpha <= min_alpha:
                row.append(0.0)
                continue
            key = (red, green, blue)
            if key not in color_cache:
                color_cache[key] = clamp01(intensity(red, green, blue, scheme))
            row.append(color_cache[key])
        rows.append(row)
    return rows


def informative_profiles(grid: list[list[float]]) -> list[list[float]]:
    """Select horizontal and vertical scanlines with enough signal to test filters.

    Args:
        grid: Scalar intensity grid.

    Returns:
        Scanline profiles with a useful maximum and prominence.
    """
    if not grid or not grid[0]:
        return []
    profiles: list[list[float]] = []
    height = len(grid)
    width = len(grid[0])
    for y in range(0, height, 4):
        profiles.append(grid[y])
    for x in range(0, width, 4):
        profiles.append([grid[y][x] for y in range(height)])
    return [
        profile for profile in profiles
        if max(profile, default=0.0) >= 0.30
        and max(profile, default=0.0) - quantile(profile, 0.35) >= 0.08
    ]


def power_binomial_filter(
    profile: list[float],
    kernel: list[float],
    strong_blend: float,
    medium_blend: float,
    weak_blend: float,
) -> list[float]:
    """Apply the planned signal-gated power-binomial filter to one profile.

    Args:
        profile: Raw scalar intensity samples along one cross-section.
        kernel: Odd-length binomial kernel, for example ``[1, 2, 1]`` or ``[1, 4, 6, 4, 1]``.
        strong_blend: Blend factor for profiles whose maximum is at least ``0.55``.
        medium_blend: Blend factor for profiles whose maximum is at least ``0.25``.
        weak_blend: Blend factor for weaker profiles.

    Returns:
        Filtered profile with the same length as the input.
    """
    if len(profile) < 3:
        return list(profile)
    maximum = max(profile, default=0.0)
    if maximum <= 0.0:
        return list(profile)
    power = 1.25 if maximum < 0.35 else 2.0
    blend = strong_blend if maximum >= 0.55 else medium_blend if maximum >= 0.25 else weak_blend
    radius = len(kernel) // 2
    result: list[float] = []
    for index, raw in enumerate(profile):
        weighted = 0.0
        total = 0.0
        for shift in range(-radius, radius + 1):
            sample_index = max(0, min(len(profile) - 1, index + shift))
            value = profile[sample_index]
            weight = kernel[shift + radius] * signal_mask(value, maximum)
            weighted += (max(0.0, value) ** power) * weight
            total += weight
        filtered = raw if total <= 1e-9 else (weighted / total) ** (1.0 / power)
        result.append(raw * (1.0 - blend) + filtered * blend)
    return result


def signal_mask(value: float, maximum: float) -> float:
    """Return the same signal gate used by the Java profile filters.

    Args:
        value: Profile sample intensity.
        maximum: Maximum intensity of the profile.

    Returns:
        Weight multiplier in ``[0, 1]``.
    """
    if maximum <= 0.0 or value <= 0.0:
        return 0.0
    if maximum < 0.25:
        return 0.25 + 0.75 * min(1.0, value / maximum)
    floor = maximum * 0.18
    width = max(0.08, maximum * 0.42)
    return clamp01((value - floor) / width)


def profile_center(profile: list[float]) -> float | None:
    """Estimate the high-intensity core center of a profile.

    Args:
        profile: Scalar intensity samples.

    Returns:
        Center index of the maximum plateau, or ``None`` when the profile is too weak.
    """
    maximum = max(profile, default=0.0)
    if maximum < 0.25:
        return None
    threshold = max(maximum * 0.86, maximum - 0.08)
    indexes = [index for index, value in enumerate(profile) if value >= threshold]
    if not indexes:
        return None
    return (indexes[0] + indexes[-1]) / 2.0


def profile_width(profile: list[float]) -> int:
    """Measure high-signal width at sixty percent of profile maximum.

    Args:
        profile: Scalar intensity samples.

    Returns:
        Count of samples at or above the width threshold.
    """
    maximum = max(profile, default=0.0)
    if maximum < 0.25:
        return 0
    return sum(1 for value in profile if value >= maximum * 0.60)


def profile_peak_count(profile: list[float]) -> int:
    """Count local maxima above a permissive ridge threshold.

    Args:
        profile: Scalar intensity samples.

    Returns:
        Number of profile maxima that could become ridge candidates.
    """
    maximum = max(profile, default=0.0)
    if maximum < 0.25:
        return 0
    threshold = max(0.16, maximum * 0.35)
    count = 0
    for index, value in enumerate(profile):
        if value < threshold:
            continue
        previous = profile[index - 1] if index > 0 else value
        next_value = profile[index + 1] if index + 1 < len(profile) else value
        if value >= previous and value >= next_value and (value > previous or value > next_value):
            count += 1
    return count


def weighted_quantile(values: list[tuple[float, int]], quantile: float) -> float:
    """Return a weighted nearest-rank quantile."""
    if not values:
        return 0.0
    ordered = sorted(values)
    threshold = sum(weight for _, weight in ordered) * quantile
    walked = 0
    for value, weight in ordered:
        walked += weight
        if walked >= threshold:
            return value
    return ordered[-1][0]


def quantile(values: list[float], probability: float) -> float:
    """Return a nearest-rank quantile for compact calibration summaries.

    Args:
        values: Numeric values to summarize.
        probability: Desired quantile in ``[0, 1]``.

    Returns:
        Quantile value, or ``0.0`` when ``values`` is empty.
    """
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil(probability * len(ordered)) - 1))
    return ordered[index]


def copy_source_image(source: ImageSource, image: Image.Image, image_dir: Path) -> str:
    """Copy or normalize a source image into the output image directory."""
    if source.bytes_data is not None:
        source_bytes = source.bytes_data
    elif source.path is not None:
        source_bytes = source.path.read_bytes()
    else:
        source_bytes = b""
    digest = hashlib.sha256(source_bytes).hexdigest()[:12]
    name = safe_name(f"{source.scheme_hint}-{digest}.png")
    target = image_dir / name
    if source.path is not None and source.path.suffix.lower() == ".png":
        shutil.copyfile(source.path, target)
    else:
        image.save(target)
    return target.as_posix()


def safe_name(value: str) -> str:
    """Return a filesystem-safe identifier."""
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", value)


def scheme_hint_for(value: str) -> str:
    """Infer the intended Strava color scheme from a path or zip member name."""
    lowered = value.lower()
    for scheme in sorted(BASE_EXPORT_COLORS, key=len, reverse=True):
        if re.search(rf"(^|[^a-z]){re.escape(scheme)}([^a-z]|$)", lowered):
            return scheme
    for scheme in sorted(SCHEMES, key=len, reverse=True):
        if scheme in lowered:
            return scheme
    return "unknown"


def write_csv(path: Path, rows: list[dict[str, object]]) -> None:
    """Write dictionaries to a CSV file."""
    fieldnames = sorted({key for row in rows for key in row.keys()}) if rows else ["empty"]
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def write_json(path: Path, data: dict[str, object]) -> None:
    """Write a JSON file with stable key ordering."""
    with path.open("w", encoding="utf-8") as handle:
        json.dump(data, handle, indent=2, sort_keys=True)
        handle.write("\n")


def intensity(red: int, green: int, blue: int, mode: str) -> float:
    """Map an RGB color to scalar heatmap intensity for a detector mode."""
    luminance = (0.2126 * red + 0.7152 * green + 0.0722 * blue) / 255.0
    hue, saturation, value = colorsys.rgb_to_hsv(red / 255.0, green / 255.0, blue / 255.0)
    hue *= 360.0
    mode = (mode or "hot").lower()
    if mode == "bluered-combined":
        return combined(red, green, blue, hue, saturation, luminance, value, [
            ("bluered", 0.42), ("bluered-cool", 0.24), ("bluered-corridor", 0.22), ("dual-corridor", 0.12)
        ])
    if mode == "gray-combined":
        return combined(red, green, blue, hue, saturation, luminance, value, [
            ("gray", 0.40), ("gray-magenta", 0.25), ("gray-corridor", 0.23), ("dual-corridor", 0.12)
        ])
    if mode == "multi-combined":
        return combined(red, green, blue, hue, saturation, luminance, value, [
            ("dual-corridor", 0.30), ("bluered-corridor", 0.25), ("gray-corridor", 0.20),
            ("hot-corridor", 0.15), ("blue", 0.10)
        ])
    return single_intensity(red, green, blue, hue, saturation, luminance, value, mode)


def combined(red: int, green: int, blue: int, hue: float, saturation: float, luminance: float, value: float, components: list[tuple[str, float]]) -> float:
    """Blend multiple named color-to-intensity mappings before ridge extraction."""
    total = sum(weight for _, weight in components)
    if total <= 0:
        return 0.0
    return clamp01(sum(weight * single_intensity(red, green, blue, hue, saturation, luminance, value, name) for name, weight in components) / total)


def single_intensity(red: int, green: int, blue: int, hue: float, saturation: float, luminance: float, value: float, mode: str) -> float:
    """Evaluate one non-combined detector mapping."""
    if mode == "bluered":
        return blue_red_intensity(red, blue, hue, saturation, luminance, value)
    if mode == "bluered-cool":
        return blue_red_cool_intensity(red, green, blue, hue, saturation, luminance, value)
    if mode == "bluered-corridor":
        return corridor_presence(blue_red_cool_intensity(red, green, blue, hue, saturation, luminance, value))
    if mode == "gray":
        return gray_intensity(hue, saturation, luminance, value)
    if mode == "gray-magenta":
        return gray_magenta_intensity(hue, saturation, value)
    if mode == "gray-corridor":
        return corridor_presence(gray_intensity(hue, saturation, luminance, value))
    if mode == "gray-strict":
        return strict_gray_intensity(hue, saturation, luminance, value)
    if mode == "purple":
        return purple_intensity(hue, saturation, luminance, value)
    if mode == "purple-strict":
        return strict_purple_intensity(hue, saturation, luminance, value)
    if mode == "blue":
        return blue_intensity(red, green, blue, hue, saturation, luminance, value)
    if mode == "dual":
        return dual_color_intensity(red, green, blue, hue, saturation, luminance, value)
    if mode == "dual-corridor":
        return corridor_presence(dual_color_intensity(red, green, blue, hue, saturation, luminance, value))
    if mode == "hot-corridor":
        return corridor_presence(0.85 * luminance + 0.15 * value)
    if mode == "hot-strict":
        return (0.85 * luminance + 0.15 * value) ** 1.35
    return 0.85 * luminance + 0.15 * value


def blue_red_intensity(red: int, blue: int, hue: float, saturation: float, luminance: float, value: float) -> float:
    """Estimate semantic intensity for the bluered palette."""
    red_dominance = max(0.0, red - blue * 0.55) / 255.0
    red_score = max(hue_affinity(hue, 350.0, 40.0), hue_affinity(hue, 8.0, 32.0)) * saturation * (0.82 + 0.18 * value) * (1.00 + 0.32 * red_dominance)
    magenta_score = max(max(hue_affinity(hue, 318.0, 40.0), hue_affinity(hue, 334.0, 34.0)), hue_affinity(hue, 285.0, 42.0) * 0.86) * saturation * (0.72 + 0.28 * value) * (0.95 + 0.25 * red_dominance)
    dark_blue_score = hue_affinity(hue, 235.0, 52.0) * saturation * (0.34 + 0.26 * value + 0.40 * (1.0 - luminance))
    light_blue_score = max(hue_affinity(hue, 202.0, 42.0), hue_affinity(hue, 216.0, 50.0)) * (0.40 + 0.60 * saturation) * (0.20 + 0.34 * luminance + 0.16 * value)
    return max(red_score, magenta_score, dark_blue_score * 0.82, light_blue_score * 0.55)


def blue_red_cool_intensity(red: int, green: int, blue: int, hue: float, saturation: float, luminance: float, value: float) -> float:
    """Estimate bluered intensity while preserving lower-activity blue/cyan shoulders."""
    red_score = max(hue_affinity(hue, 350.0, 40.0), hue_affinity(hue, 8.0, 32.0)) * (0.84 + 0.16 * value)
    magenta_score = max(hue_affinity(hue, 318.0, 42.0), hue_affinity(hue, 334.0, 34.0)) * (0.72 + 0.28 * value)
    blue_score = max(hue_affinity(hue, 225.0, 56.0), hue_affinity(hue, 240.0, 48.0)) * (0.42 + 0.28 * (1.0 - luminance) + 0.30 * value)
    cyan_score = hue_affinity(hue, 198.0, 42.0) * (0.28 + 0.38 * value + 0.24 * saturation)
    coolness = max(0.0, blue - red * 0.45 - green * 0.05) / 255.0
    red_warmth = max(0.0, red - blue * 0.55) / 255.0
    warm = max(red_score * (0.95 + 0.35 * red_warmth), magenta_score)
    cool = max(blue_score * (0.76 + 0.24 * coolness), cyan_score * 0.76)
    return saturation * max(warm, cool)


def blue_intensity(red: int, green: int, blue: int, hue: float, saturation: float, luminance: float, value: float) -> float:
    """Estimate semantic intensity for the blue palette."""
    blue_affinity = max(hue_affinity(hue, 210.0, 55.0), hue_affinity(hue, 230.0, 45.0))
    coolness = max(0.0, blue - red * 0.65 - green * 0.15) / 255.0
    brightness = 0.72 * luminance + 0.28 * value
    saturation_fit = 1.0 - min(1.0, abs(saturation - 0.45) / 0.55)
    return (0.55 + 0.45 * blue_affinity) * brightness * (0.70 + 0.30 * saturation_fit) * (0.75 + 0.25 * coolness)


def gray_intensity(hue: float, saturation: float, luminance: float, value: float) -> float:
    """Estimate semantic intensity for gray tiles, including magenta high-activity centers."""
    gray_base = (1.0 - saturation) * (0.04 + 0.16 * luminance) * (0.45 + 0.55 * value)
    magenta_score = gray_magenta_intensity(hue, saturation, value)
    violet_score = hue_affinity(hue, 268.0, 46.0) * saturation * (0.66 + 0.34 * value)
    return max(gray_base * 0.70, magenta_score, violet_score * 0.88)


def gray_magenta_intensity(hue: float, saturation: float, value: float) -> float:
    """Score magenta/violet center colors observed in high-activity gray tiles."""
    pink_score = max(hue_affinity(hue, 318.0, 42.0), hue_affinity(hue, 334.0, 34.0)) * saturation * (0.70 + 0.30 * value)
    violet_score = hue_affinity(hue, 278.0, 42.0) * saturation * (0.58 + 0.42 * value)
    return max(pink_score, violet_score * 0.82)


def strict_gray_intensity(hue: float, saturation: float, luminance: float, value: float) -> float:
    """Return a stricter gray detector score gated by chroma and saturation."""
    chroma = gray_intensity(hue, saturation, luminance, value)
    contrast_gate = max(0.0, (chroma - 0.16) / 0.84)
    saturation_gate = max(0.0, min(1.0, (saturation - 0.22) / 0.38))
    return chroma * contrast_gate * (0.35 + 0.65 * saturation_gate)


def purple_intensity(hue: float, saturation: float, luminance: float, value: float) -> float:
    """Estimate semantic intensity for current purple/lavender tiles."""
    primary_affinity = hue_affinity(hue, 260.0, 48.0)
    legacy_affinity = max(hue_affinity(hue, 285.0, 40.0) * 0.86, hue_affinity(hue, 315.0, 38.0) * 0.72)
    affinity = max(primary_affinity, legacy_affinity)
    chroma_path = affinity * (0.42 + 0.58 * saturation) * (0.34 + 0.66 * value)
    bright_lavender = affinity * (0.52 + 0.48 * value) * (0.54 + 0.46 * luminance)
    pale_core = (0.85 * luminance + 0.15 * value) * (0.58 + 0.42 * affinity) * (1.0 - 0.40 * saturation)
    return max(chroma_path, bright_lavender * 0.95, pale_core)


def strict_purple_intensity(hue: float, saturation: float, luminance: float, value: float) -> float:
    """Return a stricter purple detector score for saturated purple centers."""
    purple = purple_intensity(hue, saturation, luminance, value)
    saturation_gate = max(0.0, min(1.0, (saturation - 0.35) / 0.45))
    brightness_gate = max(0.0, min(1.0, (value - 0.24) / 0.50))
    return purple * saturation_gate * brightness_gate


def dual_color_intensity(red: int, green: int, blue: int, hue: float, saturation: float, luminance: float, value: float) -> float:
    """Estimate intensity for palettes where hue semantics outrank raw brightness."""
    warm_cool = max(blue_red_cool_intensity(red, green, blue, hue, saturation, luminance, value), purple_intensity(hue, saturation, luminance, value), gray_intensity(hue, saturation, luminance, value) * 0.88)
    bright_center = (0.85 * luminance + 0.15 * value) * (0.65 + 0.35 * (1.0 - saturation))
    blue_center = blue_intensity(red, green, blue, hue, saturation, luminance, value) * 0.92
    return max(warm_cool, bright_center, blue_center)


def corridor_presence(value: float) -> float:
    """Convert a semantic intensity score into a broader corridor-presence score."""
    return min(1.0, max(0.0, value)) ** 0.55 if value > 0.0 else 0.0


def hue_affinity(hue: float, target: float, width: float) -> float:
    """Return triangular circular hue affinity."""
    distance = abs(hue - target)
    distance = min(distance, 360.0 - distance)
    return max(0.0, 1.0 - distance / width)


def clamp01(value: float) -> float:
    """Clamp a value into the unit interval."""
    return min(1.0, max(0.0, value))


if __name__ == "__main__":
    main()
