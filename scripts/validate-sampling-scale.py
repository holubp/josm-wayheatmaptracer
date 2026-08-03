#!/usr/bin/env python3
"""Validate WayHeatmapTracer pixel, projection, tile, and ground scale conversions."""

from __future__ import annotations

import argparse
import json
import math
from dataclasses import asdict, dataclass


WEB_MERCATOR_RADIUS_M = 6_378_137.0
GEOGRAPHIC_ORACLE_RADIUS_M = 6_371_008.8
MAX_MERCATOR_LATITUDE = 85.05112878


@dataclass(frozen=True)
class ValidationResult:
    """Maximum errors and matrix size from one deterministic validation run."""

    cases: int
    maximum_tile_relative_error: float
    maximum_round_trip_error_m: float
    maximum_oversampling_error_m: float
    source_pixel_zoom_ratio_error: float


def tile_ground_meters_per_pixel(zoom: int, latitude: float, tile_size: int) -> float:
    """Return Web Mercator ground metres represented by one native tile pixel."""
    if zoom < 0 or zoom > 30:
        raise ValueError("zoom must be in [0, 30]")
    if tile_size <= 0:
        raise ValueError("tile size must be positive")
    if not math.isfinite(latitude) or abs(latitude) > MAX_MERCATOR_LATITUDE:
        raise ValueError("latitude must be finite and inside Web Mercator")
    return (math.cos(math.radians(latitude)) * math.tau * WEB_MERCATOR_RADIUS_M
            / (tile_size * 2**zoom))


def adjacent_world_pixel_oracle(zoom: int, latitude: float, tile_size: int) -> float:
    """Return great-circle distance across one world-pixel longitude interval."""
    projected_pixel = math.tau * WEB_MERCATOR_RADIUS_M / (tile_size * 2**zoom)
    return projected_displacement_ground_oracle(latitude, projected_pixel, 0.0)


def projected_displacement_ground_oracle(
    latitude: float,
    east_projection_units: float,
    north_projection_units: float,
) -> float:
    """Invert a projected displacement and return its great-circle ground distance."""
    if not math.isfinite(latitude) or abs(latitude) > MAX_MERCATOR_LATITUDE:
        raise ValueError("latitude must be finite and inside Web Mercator")
    if not math.isfinite(east_projection_units) or not math.isfinite(north_projection_units):
        raise ValueError("projected displacement must be finite")
    latitude_radians = math.radians(latitude)
    northing = WEB_MERCATOR_RADIUS_M * math.asinh(math.tan(latitude_radians))
    target_latitude = math.atan(math.sinh(
        (northing + north_projection_units) / WEB_MERCATOR_RADIUS_M))
    target_longitude = east_projection_units / WEB_MERCATOR_RADIUS_M
    delta_latitude = target_latitude - latitude_radians
    haversine = (math.sin(delta_latitude / 2.0) ** 2
                 + math.cos(latitude_radians) * math.cos(target_latitude)
                 * math.sin(target_longitude / 2.0) ** 2)
    return 2.0 * GEOGRAPHIC_ORACLE_RADIUS_M * math.asin(
        min(1.0, math.sqrt(max(0.0, haversine))))


def ground_meters_per_raster_pixel(
    projection_units_per_view_pixel: float,
    latitude: float,
    raster_scale: float,
) -> float:
    """Convert a Web Mercator projected view scale to ground metres per raster pixel."""
    if projection_units_per_view_pixel <= 0.0 or not math.isfinite(projection_units_per_view_pixel):
        raise ValueError("projection scale must be finite and positive")
    if raster_scale <= 0.0 or not math.isfinite(raster_scale):
        raise ValueError("raster scale must be finite and positive")
    if not math.isfinite(latitude) or abs(latitude) > MAX_MERCATOR_LATITUDE:
        raise ValueError("latitude must be finite and inside Web Mercator")
    return projection_units_per_view_pixel * math.cos(math.radians(latitude)) / raster_scale


def validate_matrix(
    zooms: tuple[int, ...] = tuple(range(10, 17)),
    latitudes: tuple[float, ...] = (-70.0, -49.44, 0.0, 49.44, 70.0),
    tile_sizes: tuple[int, ...] = (256, 512),
    projected_scales: tuple[float, ...] = (0.09725, 0.1945, 0.389, 0.778, 1.556),
    raster_scales: tuple[float, ...] = (1.0, 6.0, 24.0),
    physical_offsets: tuple[float, ...] = (1.0, 6.0, 28.0),
) -> ValidationResult:
    """Run a caller-selected acceptance matrix and return maximum numerical errors."""
    maximum_tile_relative_error = 0.0
    maximum_round_trip_error = 0.0
    maximum_oversampling_error = 0.0
    cases = 0

    if not all((zooms, latitudes, tile_sizes, projected_scales, raster_scales, physical_offsets)):
        raise ValueError("validation matrix dimensions must not be empty")
    for tile_size in tile_sizes:
        for zoom in zooms:
            for latitude in latitudes:
                calculated = tile_ground_meters_per_pixel(zoom, latitude, tile_size)
                oracle = adjacent_world_pixel_oracle(zoom, latitude, tile_size)
                maximum_tile_relative_error = max(
                    maximum_tile_relative_error, abs(calculated - oracle) / oracle)
                cases += 1

    for latitude in latitudes:
        for projected_scale in projected_scales:
            reconstructed_by_scale: dict[float, list[float]] = {}
            for raster_scale in raster_scales:
                raster_mpp = ground_meters_per_raster_pixel(
                    projected_scale, latitude, raster_scale)
                reconstructed_by_scale[raster_scale] = []
                one_raster_pixel_oracle = projected_displacement_ground_oracle(
                    latitude, projected_scale / raster_scale, 0.0)
                maximum_round_trip_error = max(maximum_round_trip_error,
                    abs(one_raster_pixel_oracle - raster_mpp))
                cases += 1
                for distance_m in physical_offsets:
                    projected_distance = distance_m / math.cos(math.radians(latitude))
                    direction_distances = []
                    for east_factor, north_factor in (
                        (1.0, 0.0), (-1.0, 0.0), (0.0, 1.0), (0.0, -1.0),
                        (math.sqrt(0.5), math.sqrt(0.5)),
                        (-math.sqrt(0.5), math.sqrt(0.5)),
                        (math.sqrt(0.5), -math.sqrt(0.5)),
                        (-math.sqrt(0.5), -math.sqrt(0.5)),
                    ):
                        east_raster_pixels = (projected_distance * east_factor
                                              / projected_scale * raster_scale)
                        north_raster_pixels = (projected_distance * north_factor
                                               / projected_scale * raster_scale)
                        reconstructed_east = east_raster_pixels / raster_scale * projected_scale
                        reconstructed_north = north_raster_pixels / raster_scale * projected_scale
                        reconstructed = projected_displacement_ground_oracle(
                            latitude, reconstructed_east, reconstructed_north)
                        direction_distances.append(reconstructed)
                        maximum_round_trip_error = max(
                            maximum_round_trip_error, abs(reconstructed - distance_m))
                        cases += 1
                    reconstructed_by_scale[raster_scale].extend(direction_distances)
            reference = reconstructed_by_scale[raster_scales[0]]
            for values in reconstructed_by_scale.values():
                maximum_oversampling_error = max(maximum_oversampling_error,
                    max(abs(left - right) for left, right in zip(reference, values)))

    ordered_zooms = sorted(set(zooms))
    consecutive = [(left, right) for left, right in zip(ordered_zooms, ordered_zooms[1:])
                   if right == left + 1]
    ratio_error = max((abs(tile_ground_meters_per_pixel(left, latitudes[0], tile_sizes[-1])
                           / tile_ground_meters_per_pixel(right, latitudes[0], tile_sizes[-1]) - 2.0)
                       for left, right in consecutive), default=0.0)
    return ValidationResult(cases, maximum_tile_relative_error, maximum_round_trip_error,
        maximum_oversampling_error, ratio_error)


def main() -> None:
    """Run validation, print machine-readable results, and fail outside acceptance limits."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pretty", action="store_true", help="Indent JSON output")
    parser.add_argument("--zooms", default="10,11,12,13,14,15,16", help="Comma-separated tile zooms")
    parser.add_argument("--latitudes", default="-70,-49.44,0,49.44,70", help="Comma-separated latitudes")
    parser.add_argument("--tile-size", dest="tile_sizes", default="256,512",
                        help="Comma-separated native tile sizes")
    parser.add_argument("--view-projection-scale", dest="projected_scales",
                        default="0.09725,0.1945,0.389,0.778,1.556",
                        help="Comma-separated projection units per view pixel")
    parser.add_argument("--raster-scale", dest="raster_scales", default="1,6,24",
                        help="Comma-separated raster oversampling factors")
    args = parser.parse_args()
    result = validate_matrix(
        tuple(parse_csv(args.zooms, int, "zoom")),
        tuple(parse_csv(args.latitudes, float, "latitude")),
        tuple(parse_csv(args.tile_sizes, int, "tile size")),
        tuple(parse_csv(args.projected_scales, float, "projection scale")),
        tuple(parse_csv(args.raster_scales, float, "raster scale")),
    )
    print(json.dumps(asdict(result), indent=2 if args.pretty else None, sort_keys=True))
    if result.maximum_tile_relative_error > 0.0015:
        raise SystemExit("tile resolution exceeds 0.15% geographic-oracle tolerance")
    if result.maximum_round_trip_error_m > 0.05:
        raise SystemExit("physical round trip exceeds 0.05 m tolerance")
    if result.maximum_oversampling_error_m > 1e-9:
        raise SystemExit("raster oversampling changes reconstructed physical displacement")
    if result.source_pixel_zoom_ratio_error > 1e-12:
        raise SystemExit("native source-pixel zoom ratio is not exactly two")


def parse_csv(value: str, converter, name: str) -> list[int | float]:
    """Parse one non-empty comma-separated numeric option with a useful error."""
    try:
        parsed = [converter(item.strip()) for item in value.split(",") if item.strip()]
    except ValueError as error:
        raise SystemExit(f"invalid {name} list: {value}") from error
    if not parsed:
        raise SystemExit(f"{name} list must not be empty")
    return parsed


if __name__ == "__main__":
    main()
