#!/usr/bin/env python3
"""Tests for the independent sampling-scale acceptance oracle."""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "validate-sampling-scale.py"
SPEC = importlib.util.spec_from_file_location("validate_sampling_scale", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class SamplingScaleValidatorTest(unittest.TestCase):
    """Checks matrix bounds and invalid-input guards."""

    def test_acceptance_matrix(self) -> None:
        """All supported resolutions preserve physical displacement within contract limits."""
        result = MODULE.validate_matrix()
        self.assertGreater(result.cases, 200)
        self.assertLessEqual(result.maximum_tile_relative_error, 0.0015)
        self.assertLessEqual(result.maximum_round_trip_error_m, 0.05)
        self.assertLessEqual(result.maximum_oversampling_error_m, 1e-9)
        self.assertLessEqual(result.source_pixel_zoom_ratio_error, 1e-12)

    def test_invalid_inputs_fail_explicitly(self) -> None:
        """Invalid scale values raise errors instead of propagating NaN."""
        with self.assertRaises(ValueError):
            MODULE.tile_ground_meters_per_pixel(-1, 0.0, 512)
        with self.assertRaises(ValueError):
            MODULE.tile_ground_meters_per_pixel(15, 90.0, 512)
        with self.assertRaises(ValueError):
            MODULE.tile_ground_meters_per_pixel(15, 0.0, 0)
        with self.assertRaises(ValueError):
            MODULE.ground_meters_per_raster_pixel(0.0, 0.0, 6.0)

    def test_known_z15_resolution_and_zoom_ratio(self) -> None:
        """The z15 Strava value and adjacent zoom factor remain calibrated."""
        z15 = MODULE.tile_ground_meters_per_pixel(15, 49.44, 512)
        z14 = MODULE.tile_ground_meters_per_pixel(14, 49.44, 512)
        self.assertAlmostEqual(1.5532099391668734, z15, places=12)
        self.assertAlmostEqual(2.0, z14 / z15, places=12)


if __name__ == "__main__":
    unittest.main()
