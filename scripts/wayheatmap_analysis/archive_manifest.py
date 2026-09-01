"""Privacy-safe metadata extraction from already validated debug bundles."""

from __future__ import annotations

import csv
import hashlib
import io
import json
import zipfile
from dataclasses import dataclass

from .pairing import BundleWay
from .privacy import safe_label, safe_scalar
from .safe_osm import OsmError, parse_osm_bytes
from .safe_zip import BundleSource


@dataclass(frozen=True)
class BundleAnalysis:
    """Canonical debug metadata and any local-only original-way geometries."""

    metadata: dict[str, object]
    ways: tuple[BundleWay, ...]


def analyze_bundle(source: BundleSource) -> BundleAnalysis:
    """Extract bounded known artifacts without retaining arbitrary member text."""

    digest = hashlib.sha256(source.data).hexdigest()
    try:
        with zipfile.ZipFile(io.BytesIO(source.data)) as archive:
            names = sorted(info.filename for info in archive.infolist() if not info.is_dir())
            manifest = _json_object(archive, "manifest.json")
            diagnostics = _json_object(archive, "diagnostics.json")
            status = _json_object(archive, "status.json")
            ratings = _json_value(archive, "candidate-ratings.json")
            candidate_count = _candidate_count(archive, diagnostics)
            format_version = _integer(manifest.get("formatVersion"), 0)
            plugin_version = safe_scalar(manifest.get("pluginVersion")
                                         or diagnostics.get("pluginVersion"))
            cleanup = [name for name in ("geometry-cleanup.csv", "geometry-cleanup-anchors.csv",
                                          "geometry-cleanup-local-shape.csv")
                       if name in names]
            intensity = [name for name in ("profile-intensity.csv", "palette-samples.csv",
                                           "aggregate-intensity/metadata.json") if name in names]
            profile = [name for name in ("profile-peaks.csv", "corridor-bands.csv",
                                         "corridor-tracks.csv") if name in names]
            selected = safe_scalar(status.get("selectedCandidate")
                                   or status.get("selectedCandidateId")
                                   or status.get("candidateId"))
            applied = selected if safe_scalar(status.get("status")).lower() == "applied" else ""
            if isinstance(ratings, list):
                rating_count = len(ratings)
            elif isinstance(ratings, dict) and isinstance(ratings.get("ratings"), list):
                rating_count = len(ratings["ratings"])
            elif isinstance(ratings, dict):
                rating_count = len(ratings)
            else:
                rating_count = 0
            replayability = _replayability(names)
            label = safe_label(source.name, digest)
            metadata = {
                "bundleSha256": digest,
                "bundleLabel": label,
                "debugFormatVersion": format_version,
                "pluginVersion": plugin_version,
                "candidateCount": candidate_count,
                "cleanupArtifacts": cleanup,
                "intensityEvidence": intensity,
                "profileEvidence": profile,
                "selectedCandidateId": selected,
                "appliedCandidateId": applied,
                "ratingCount": rating_count,
                "replayability": replayability,
            }
            ways = _original_ways(archive, digest, label)
            return BundleAnalysis(metadata, ways)
    except (OSError, RuntimeError, zipfile.BadZipFile, json.JSONDecodeError, UnicodeError) as error:
        raise ValueError("validated bundle metadata is malformed") from error


def _json_value(archive: zipfile.ZipFile, name: str) -> dict | list:
    """Read one optional JSON artifact and keep only container roots."""

    if name not in archive.namelist():
        return {}
    value = json.loads(archive.read(name).decode("utf-8"))
    return value if isinstance(value, (dict, list)) else {}


def _json_object(archive: zipfile.ZipFile, name: str) -> dict:
    """Read one optional JSON artifact that must have an object root."""

    value = _json_value(archive, name)
    return value if isinstance(value, dict) else {}


def _candidate_count(archive, diagnostics) -> int:
    """Count candidate rows, falling back to the diagnostics array."""

    if "candidate-metrics.csv" in archive.namelist():
        rows = list(csv.DictReader(io.StringIO(archive.read("candidate-metrics.csv").decode("utf-8"))))
        return len(rows)
    candidates = diagnostics.get("candidates", []) if isinstance(diagnostics, dict) else []
    return len(candidates) if isinstance(candidates, list) else 0


def _integer(value, fallback: int) -> int:
    """Parse an integer metadata value or return its conservative fallback."""

    try:
        return int(value)
    except (TypeError, ValueError):
        return fallback


def _replayability(names: list[str]) -> str:
    """Assign the highest replay level supported by present artifacts."""

    present = set(names)
    source_raster = ("rendered-layer-capture.png" in present
                     or any(name.startswith("tiles/") and name.endswith(".png")
                            for name in present))
    if source_raster and {"diagnostics.json", "profile-intensity.csv",
                          "optimizer-costs.csv", "tile-manifest.json"} <= present:
        return "R4"
    if {"profile-intensity.csv", "optimizer-costs.csv", "scale-space.csv",
        "corridor-bands.csv"} <= present:
        return "R3"
    if {"profile-intensity.csv", "geometry-cleanup.csv",
        "geometry-cleanup-anchors.csv"} <= present:
        return "R2"
    if present & {"candidate-metrics.csv", "profile-peaks.csv", "geometry-cleanup.csv"}:
        return "R1"
    return "R0"


def _original_ways(archive, digest: str, label: str) -> tuple[BundleWay, ...]:
    """Parse local-only immutable source geometry for pairing when available."""

    if "original-segment.osm" not in archive.namelist():
        return ()
    try:
        document = parse_osm_bytes(archive.read("original-segment.osm"), source_name="original-segment.osm")
    except OsmError:
        return ()
    return tuple(BundleWay(digest, label, way) for way in document.ways)
