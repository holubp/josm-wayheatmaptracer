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
        """Managed format 5 exposes immutable geometry, version, and coherent physical spacing."""
        rows, undulations = run_analyzers(debug_bundle(5, "0.16.2", "applied"))

        self.assertEqual("0.16.2", rows[0]["plugin_version"])
        self.assertEqual("sha256:test", rows[0]["build_identity"])
        self.assertEqual("immutable", rows[0]["original_geometry_trust"])
        self.assertEqual("", rows[0]["physical_distance_warning"])
        self.assertEqual("immutable", undulations[0]["original_geometry_trust"])
        self.assertEqual("2", undulations[0]["applied_nodes"])

    def test_format_five_visible_physical_fields_are_marked_untrusted(self) -> None:
        """Visible format-5 metre labels are not silently treated as geographic distances."""
        rows, _ = run_analyzers(debug_bundle(
            5, "0.16.2", "applied", sampling_type="rendered-visible-layer"))

        self.assertIn("projection units", rows[0]["physical_distance_warning"])

    def test_format_six_exposes_scale_contract_and_optimizer_performance(self) -> None:
        """Format 6 reports authoritative physical scale and invariant-work reduction."""
        rows, undulations = run_analyzers(debug_bundle(6, "0.16.3", "applied"))

        self.assertEqual("", rows[0]["physical_distance_warning"])
        self.assertEqual("12.0", rows[0]["detector_total_ms"])
        self.assertEqual("8.0", rows[0]["exact_optimization_ms"])
        self.assertEqual("25.0", rows[0]["transition_to_profile_cost_ratio"])
        self.assertEqual("optimization", rows[0]["timing_dominant_phase"])
        self.assertEqual("", rows[0]["timing_warning"])
        self.assertEqual("immutable", undulations[0]["original_geometry_trust"])

    def test_format_six_missing_performance_row_is_reported(self) -> None:
        """A damaged format-6 bundle cannot look like a measured fast detector."""
        rows, _ = run_analyzers(debug_bundle(
            6, "0.16.3", "applied", include_performance=False))

        self.assertIn("performance row missing", rows[0]["timing_warning"])

    def test_consecutive_applied_and_original_geometries_are_reported_as_repeat(self) -> None:
        """The undulation analyzer correlates a repeated slide without exporting coordinates."""
        outer = io.BytesIO()
        with zipfile.ZipFile(outer, "w") as archive:
            archive.writestr("first.zip", debug_bundle(6, "0.16.3", "applied"))
            archive.writestr("second.zip", debug_bundle(6, "0.16.3", "applied"))

        _, undulations = run_analyzers(outer.getvalue())

        repeat = next(row for row in undulations if "second.zip" in row["bundle"])
        self.assertIn("first.zip", repeat["repeat_of"])
        self.assertEqual("0.0", repeat["repeat_input_match_max_m"])
        self.assertEqual("0.0", repeat["repeat_bidirectional_max_drift_m"])


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


def debug_bundle(
    format_version: int,
    plugin_version: str,
    status: str,
    sampling_type: str = "managed-source-tiles",
    include_performance: bool = True,
) -> bytes:
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
    if format_version >= 5:
        diagnostics["sampling"]["type"] = sampling_type
    if format_version >= 6:
        diagnostics["sampling"].update({
            "samplingScaleVersion": 1,
            "type": "managed-source-tiles",
            "groundMetersPerViewPixel": 0.389,
            "groundMetersPerRasterPixel": 0.064833,
            "trackerNormalizationRasterPx": 6.0,
            "trackerNormalizationMethod": "native-source-pixel",
        })
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
        if format_version >= 6 and include_performance:
            archive.writestr(
                "detector-performance.csv",
                "detector,sampling_nanos,extraction_nanos,scale_association_nanos,"
                "tracking_grouping_nanos,optimization_nanos,diagnostic_serialization_nanos,"
                "projection_nanos,total_nanos,unaccounted_nanos,profile_count,band_count,"
                "track_count,candidate_count,allowed_state_count,transition_evaluations,"
                "profile_cost_evaluations,retained_pair_state_allocations,diagnostic_characters\n"
                "hot,1000000,500000,500000,1000000,8000000,500000,250000,12000000,250000,"
                "3,3,1,1,40,1000,40,200,1000\n",
            )
    return result.getvalue()


if __name__ == "__main__":
    unittest.main()
