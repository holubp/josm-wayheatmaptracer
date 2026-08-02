#!/usr/bin/env python3
"""Regression tests for old and current WayHeatmapTracer debug-bundle readers."""

from __future__ import annotations

import csv
import io
import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[2]
DEBUG_ANALYZER = REPOSITORY / "scripts" / "analyze-debug-bundles.py"
UNDULATION_ANALYZER = REPOSITORY / "scripts" / "analyze-slide-undulations.py"


class DebugAnalyzerCompatibilityTest(unittest.TestCase):
    """Checks schema compatibility using generated bundles with no field data."""

    def test_nested_format_four_is_marked_untrusted(self) -> None:
        """Format-4 applied geometry and metre fields remain readable but untrusted."""
        inner = debug_bundle(4, "", "applied")
        outer = io.BytesIO()
        with zipfile.ZipFile(outer, "w") as archive:
            archive.writestr("nested/last-slide.zip", inner)

        rows, undulations = run_analyzers(outer.getvalue())

        self.assertEqual("4", rows[0]["bundle_format"])
        self.assertEqual("unreliable-after-apply", rows[0]["original_geometry_trust"])
        self.assertIn("raster-space", rows[0]["physical_distance_warning"])
        self.assertEqual("unreliable-after-apply", undulations[0]["original_geometry_trust"])

    def test_format_five_preserves_version_and_physical_contract(self) -> None:
        """Format 5 exposes immutable geometry, version, and coherent physical spacing."""
        rows, undulations = run_analyzers(debug_bundle(5, "0.16.2", "applied"))

        self.assertEqual("0.16.2", rows[0]["plugin_version"])
        self.assertEqual("sha256:test", rows[0]["build_identity"])
        self.assertEqual("immutable", rows[0]["original_geometry_trust"])
        self.assertEqual("", rows[0]["physical_distance_warning"])
        self.assertEqual("immutable", undulations[0]["original_geometry_trust"])
        self.assertEqual("2", undulations[0]["applied_nodes"])


def run_analyzers(data: bytes) -> tuple[list[dict[str, str]], list[dict[str, str]]]:
    """Run both command-line analyzers against one generated direct or outer bundle."""
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        bundle = root / "bundle.zip"
        raw_csv = root / "raw.csv"
        undulations_csv = root / "undulations.csv"
        bundle.write_bytes(data)
        subprocess.run(
            [sys.executable, str(DEBUG_ANALYZER), str(bundle), "--raw-csv", str(raw_csv)],
            cwd=REPOSITORY,
            check=True,
            capture_output=True,
            text=True,
        )
        subprocess.run(
            [sys.executable, str(UNDULATION_ANALYZER), str(bundle), "--csv", str(undulations_csv)],
            cwd=REPOSITORY,
            check=True,
            capture_output=True,
            text=True,
        )
        return read_csv(raw_csv), read_csv(undulations_csv)


def read_csv(path: Path) -> list[dict[str, str]]:
    """Read one generated analyzer CSV."""
    with path.open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def debug_bundle(format_version: int, plugin_version: str, status: str) -> bytes:
    """Build the smallest useful redacted debug bundle for analyzer compatibility checks."""
    result = io.BytesIO()
    manifest = {
        "formatVersion": format_version,
        "pluginVersion": plugin_version,
        "buildIdentity": "sha256:test" if format_version >= 5 else "",
    }
    diagnostics = {
        "pluginVersion": plugin_version,
        "config": {},
        "sampling": {
            "profileCount": 3,
            "physicalPathLengthMeters": 4.0,
            "longitudinalProfileSpacingMeters": {"median": 2.0},
            "rasterMetersPerPixel": 1.0,
        },
        "candidates": [{"id": "hot/strand-1", "offsetsPx": [0.0, 0.0, 0.0]}],
    }
    geometry = (
        "<?xml version=\"1.0\"?><osm version=\"0.6\">"
        "<node id=\"-1\" lat=\"0.0\" lon=\"0.0\"/>"
        "<node id=\"-2\" lat=\"0.0\" lon=\"0.00001\"/>"
        "<way id=\"-3\"><nd ref=\"-1\"/><nd ref=\"-2\"/></way></osm>"
    )
    metrics = (
        "rank,candidate_id,detector,calibrated_score,raw_score,support_ratio,"
        "mean_intensity,mean_gradient_strength,signal_to_noise\n"
        "1,hot/strand-1,hot,1.0,1.0,1.0,1.0,1.0,1.0\n"
    )
    with zipfile.ZipFile(result, "w") as archive:
        archive.writestr("manifest.json", json.dumps(manifest))
        archive.writestr("diagnostics.json", json.dumps(diagnostics))
        archive.writestr("status.json", json.dumps({"status": status, "selectedCandidate": "hot/strand-1"}))
        archive.writestr("candidate-metrics.csv", metrics)
        archive.writestr("original-segment.osm", geometry)
        archive.writestr("preview-segment.osm", geometry)
        if format_version >= 5:
            archive.writestr("applied-segment.osm", geometry)
            archive.writestr(
                "corridor-tube.csv",
                "detector,track_id,profile_index,distance_m\nhot,strand-1,0,0.0\n"
                "hot,strand-1,1,2.0\nhot,strand-1,2,4.0\n",
            )
    return result.getvalue()


if __name__ == "__main__":
    unittest.main()
