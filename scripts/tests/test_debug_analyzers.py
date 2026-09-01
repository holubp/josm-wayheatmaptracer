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

    def test_formats_one_through_three_remain_readable(self) -> None:
        """The oldest additive schemas remain analyzable with unavailable newer evidence."""
        for format_version in (1, 2, 3):
            with self.subTest(format_version=format_version):
                rows, undulations = run_analyzers(debug_bundle(format_version, "", "preview-open"))
                self.assertEqual(str(format_version), rows[0]["bundle_format"])
                self.assertEqual("unreliable-after-apply", rows[0]["original_geometry_trust"])
                self.assertEqual("pre-apply-snapshot", undulations[0]["original_geometry_trust"])

    def test_bzip2_and_lzma_bundles_run_through_both_analyzer_clis(self) -> None:
        """Legacy Python-supported ZIP codecs remain end-to-end compatible."""
        for codec in (zipfile.ZIP_BZIP2, zipfile.ZIP_LZMA):
            with self.subTest(codec=codec):
                rows, undulations = run_analyzers(recode_bundle(debug_bundle(
                    6, "0.16.3", "preview-open"), codec))
                self.assertEqual("6", rows[0]["bundle_format"])
                self.assertEqual("immutable", undulations[0]["original_geometry_trust"])

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
        self.assertEqual("unavailable", rows[0]["proposed_topology_state"])
        self.assertEqual("immutable", undulations[0]["original_geometry_trust"])
        self.assertEqual("unavailable", undulations[0]["proposed_topology_state"])

    def test_format_six_missing_performance_row_is_reported(self) -> None:
        """A damaged format-6 bundle cannot look like a measured fast detector."""
        rows, _ = run_analyzers(debug_bundle(
            6, "0.16.3", "applied", include_performance=False))

        self.assertIn("performance row missing", rows[0]["timing_warning"])

    def test_format_seven_exposes_proposed_topology_state(self) -> None:
        """Format 7 reports candidate-owned existing-node assignments to both analyzers."""
        rows, undulations = run_analyzers(debug_bundle(7, "0.17.1", "applied"))

        self.assertEqual("available", rows[0]["proposed_topology_state"])
        self.assertEqual("1", rows[0]["proposed_node_count"])
        self.assertEqual("available", undulations[0]["proposed_topology_state"])
        self.assertEqual("1", undulations[0]["proposed_node_count"])

    def test_format_eight_exposes_sparse_bundle_provenance(self) -> None:
        """Format 8 correlates parent candidates with direct, interpolated, and center evidence."""
        rows, _ = run_analyzers(debug_bundle(8, "0.18.0", "preview-open"))

        self.assertEqual("combined", rows[0]["sparse_bundle_classification"])
        self.assertEqual("2", rows[0]["sparse_bundle_child_count"])
        self.assertEqual("2.0", rows[0]["sparse_bundle_direct_union_profiles"])
        self.assertEqual("1.0", rows[0]["sparse_bundle_interpolated_profiles"])
        self.assertEqual("0.3333333333333333", rows[0]["sparse_bundle_interpolation_ratio"])
        self.assertIn("interpolation-heavy", rows[0]["sparse_bundle_diagnoses"])

    def test_nested_format_nine_keeps_literal_cleanup_parent_relation(self) -> None:
        """Format 9 joins raw and cleaned variants through CSV fields, not identifier parsing."""
        outer = io.BytesIO()
        with zipfile.ZipFile(outer, "w") as archive:
            archive.writestr("nested/last-slide.zip", debug_bundle(
                9, "0.19.0", "preview-open", include_cleanup=True))

        rows, undulations = run_analyzers(outer.getvalue())

        cleaned = next(row for row in rows if row["candidate_id"] == "hot/strand#cleaned")
        self.assertEqual("available", cleaned["cleanup_state"])
        self.assertEqual("hot/strand#raw", cleaned["cleanup_parent_candidate_id"])
        self.assertEqual("CLEANED", cleaned["cleanup_outcome"])
        self.assertEqual("0.42", cleaned["cleanup_maximum_removed_deviation_meters"])
        self.assertEqual("unavailable", cleaned["cleanup_anchor_data_state"])
        undulation = next(row for row in undulations if row["candidate_id"] == "hot/strand#cleaned")
        self.assertEqual("available", undulation["cleanup_parent_relation_state"])
        self.assertEqual("hot/strand#raw", undulation["cleanup_parent_candidate_id"])

    def test_format_nine_missing_cleanup_files_are_unavailable(self) -> None:
        """Partial format-9 archives do not turn unknown cleanup metrics into measured zeros."""
        rows, undulations = run_analyzers(debug_bundle(9, "0.19.0", "preview-open"))

        self.assertEqual("unavailable", rows[0]["cleanup_state"])
        self.assertEqual("unavailable", rows[0]["cleanup_outcome"])
        self.assertEqual("", rows[0]["cleanup_fit_after"])
        self.assertEqual("unavailable", undulations[0]["cleanup_state"])
        self.assertEqual("", undulations[0]["cleanup_maximum_removed_deviation_meters"])

    def test_format_ten_exposes_residual_authorization_and_absolute_turn_cost(self) -> None:
        """Format 10 optimizer additions remain separate and readable."""
        rows, _ = run_analyzers(debug_bundle(10, "0.19.3", "preview-open"))
        self.assertAlmostEqual(1.0 / 12.0, float(rows[0]["absolute_short_wave_turn_cost"]))
        self.assertEqual("0.4", rows[0]["residual_amplitude_source_px"])
        self.assertEqual("0.75", rows[0]["trend_authorization"])
        self.assertEqual("0.5", rows[0]["unsupported_ripple_factor"])

    def test_format_twelve_exposes_coordinate_free_cleanup_shape_evidence(self) -> None:
        """Format 12 summarizes direct local-shape evidence without geometry samples."""
        rows, _ = run_analyzers(debug_bundle(12, "0.20.2", "preview-open"))

        self.assertEqual("available", rows[0]["cleanup_shape_state"])
        self.assertEqual("2", rows[0]["cleanup_shape_direct_profile_count"])
        self.assertEqual("0.5", rows[0]["cleanup_shape_wrinkle_mean"])
        self.assertEqual("0.7", rows[0]["cleanup_shape_wrinkle_max"])
        self.assertEqual("0.4", rows[0]["cleanup_shape_bend_max"])
        self.assertEqual("0.3", rows[0]["cleanup_shape_ambiguity_max"])

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


def recode_bundle(data: bytes, codec: int) -> bytes:
    """Rewrite a generated bundle with one legacy compression codec."""
    output = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(data)) as source, zipfile.ZipFile(output, "w", codec) as target:
        for info in source.infolist():
            target.writestr(info.filename, source.read(info))
    return output.getvalue()


def debug_bundle(
    format_version: int,
    plugin_version: str,
    status: str,
    sampling_type: str = "managed-source-tiles",
    include_performance: bool = True,
    include_cleanup: bool = False,
) -> bytes:
    """Build the smallest useful redacted debug bundle for analyzer compatibility checks."""
    result = io.BytesIO()
    manifest = {
        "formatVersion": format_version,
        "pluginVersion": plugin_version,
        "buildIdentity": "sha256:test" if format_version >= 5 else "",
    }
    candidate_id = "hot/bundle-1" if format_version >= 8 else "hot/strand-1"
    raw_candidate_id = "hot/strand#raw"
    cleaned_candidate_id = "hot/strand#cleaned"
    if format_version >= 9 and include_cleanup:
        candidate_id = cleaned_candidate_id
    diagnostics = {
        "pluginVersion": plugin_version,
        "config": {},
        "sampling": {
            "profileCount": 3,
            "physicalPathLengthMeters": 4.0,
            "longitudinalProfileSpacingMeters": {"median": 2.0},
            "rasterMetersPerPixel": 1.0,
        },
        "candidates": [{"id": candidate_id, "offsetsPx": [0.0, 0.0, 0.0]}],
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
        f"1,{candidate_id},hot,1.0,1.0,1.0,1.0,1.0,1.0\n"
    )
    if format_version >= 9 and include_cleanup:
        diagnostics["candidates"] = [
            {"id": raw_candidate_id, "offsetsPx": [0.0, 0.5, 0.0]},
            {"id": cleaned_candidate_id, "offsetsPx": [0.0, 0.0, 0.0]},
        ]
        metrics = (
            "rank,candidate_id,detector,calibrated_score,raw_score,support_ratio,"
            "mean_intensity,mean_gradient_strength,signal_to_noise\n"
            f"1,{raw_candidate_id},hot,1.0,1.0,1.0,1.0,1.0,1.0\n"
            f"2,{cleaned_candidate_id},hot,0.9,0.9,1.0,1.0,1.0,1.0\n"
        )
    with zipfile.ZipFile(result, "w") as archive:
        archive.writestr("manifest.json", json.dumps(manifest))
        archive.writestr("diagnostics.json", json.dumps(diagnostics))
        archive.writestr("status.json", json.dumps({"status": status, "selectedCandidate": candidate_id}))
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
        if format_version >= 7:
            archive.writestr(
                "proposed-node-positions.csv",
                "candidate_id,node_id,original_east,original_north,proposed_east,proposed_north\n"
                f"{candidate_id},123,0.0,0.0,1.0,1.0\n",
            )
        if format_version >= 8:
            archive.writestr(
                "corridor-bundles.csv",
                "detector,bundle_id,classification,child_track_ids,direct_union_profiles,"
                "interpolated_profiles,union_support_ratio,joint_support_ratio,valley_persistence,"
                "tangent_agreement,order_stability,robust_separation_px,reason\n"
                "hot,bundle-1,combined,left;right,2,1,0.8,0.1,0.2,0.95,1.0,4.0,"
                "complementary-child-union\n",
            )
            archive.writestr(
                "bundle-points.csv",
                "detector,bundle_id,profile_index,support,direct_contributor_track_ids,"
                "predicted_contributor_track_ids,center_px,uncertainty_px,shoulder_min_px,"
                "shoulder_max_px,core_min_px,core_max_px,occupancy,contributor_agreement\n"
                "hot,bundle-1,0,DIRECT_UNION,left,right,0.0,1.0,-2.0,2.0,-1.0,1.0,1.0,0.9\n"
                "hot,bundle-1,1,BOUNDED_INTERPOLATION,,left;right,0.0,1.5,-2.0,2.0,"
                "-1.0,1.0,1.0,0.8\n"
                "hot,bundle-1,2,DIRECT_UNION,right,left,0.0,1.0,-2.0,2.0,-1.0,1.0,1.0,0.9\n",
            )
            optimizer = ("detector,track_id,profile_index,chosen_offset_px\n"
                         "hot,bundle-1,0,0.0\nhot,bundle-1,1,0.0\nhot,bundle-1,2,0.0\n")
            if format_version >= 10:
                optimizer = (
                    "detector,track_id,profile_index,chosen_offset_px,profile_spacing_px,"
                    "acceleration_cost,absolute_short_wave_turn_cost,residual_amplitude_source_px,"
                    "trend_authorization,unsupported_ripple_factor,inside_core,inside_corridor\n"
                    "hot,bundle-1,0,0.0,2.0,0.0,0.0,0.4,0.75,0.5,true,true\n"
                    "hot,bundle-1,1,0.0,2.0,0.0,0.125,0.4,0.75,0.5,true,true\n"
                    "hot,bundle-1,2,0.0,2.0,0.0,0.125,0.4,0.75,0.5,true,true\n")
            archive.writestr("optimizer-costs.csv", optimizer)
        if format_version >= 9 and include_cleanup:
            archive.writestr(
                "geometry-cleanup.csv",
                "candidate_id,parent_candidate_id,outcome,reason_code,reasons,before_point_count,"
                "smoothed_point_count,after_point_count,accepted_smoothing_passes,"
                "smoothing_backtrack_count,attempted_chord_count,accepted_chord_count,"
                "containment_failure_count,fit_before,fit_after,"
                "maximum_displacement_projection_units,projection_unit_name,"
                "maximum_removed_deviation_meters,worst_fit_retention\n"
                f"{raw_candidate_id},,CLEANED_ALTERNATIVE_AVAILABLE,cleaned-sibling,raw-kept,12,12,12,2,1,8,4,0,0.88,0.88,0.0,JOSM-projection-units,,\n"
                f"{cleaned_candidate_id},{raw_candidate_id},CLEANED,accepted,fit-retained,12,12,5,2,1,8,4,0,0.88,0.91,1.25,JOSM-projection-units,0.42,0.93\n",
            )
            archive.writestr(
                "geometry-cleanup-anchors.csv",
                "candidate_id,parent_candidate_id,cleanup_outcome,anchor_data_state,reason_code,"
                "profile_index,final_preview_index,protected,reused_node_id,"
                "source_east,source_north,proposed_east,proposed_north\n"
                f"{raw_candidate_id},,CLEANED_ALTERNATIVE_AVAILABLE,unavailable,anchor-evidence-not-exported-by-model,,,,,,,,\n"
                f"{cleaned_candidate_id},{raw_candidate_id},CLEANED,unavailable,anchor-evidence-not-exported-by-model,,,,,,,,\n",
            )
        if format_version >= 12:
            archive.writestr(
                "geometry-cleanup-local-shape.csv",
                "candidate_id,parent_candidate_id,cleanup_evidence_status,profile_index,provenance,"
                "scale_conflict,motion_support,turn_support,wrinkle_intervention,"
                "bend_protection,shape_ambiguity\n"
                f"{candidate_id},,COMPLETE,0,DIRECT,false,0.8,0.0,0.3,0.1,0.2\n"
                f"{candidate_id},,COMPLETE,1,DIRECT,false,0.7,0.2,0.7,0.4,0.3\n"
                f"{candidate_id},,COMPLETE,2,INTERPOLATED,false,0.0,0.0,1.0,1.0,1.0\n",
            )
    return result.getvalue()


if __name__ == "__main__":
    unittest.main()
