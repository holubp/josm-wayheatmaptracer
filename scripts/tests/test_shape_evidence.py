"""Synthetic regression tests for offline local-shape evidence."""

from __future__ import annotations

import math

import pytest

from wayheatmap_analysis.shape_evidence import (
    ANALYSIS_RADII_METERS,
    ShapeEvidenceAnalyzer,
    ShapeObservation,
    analyze_shape_evidence,
)


def observations(values: list[float], *, scale: float = 1.0) -> list[ShapeObservation]:
    """Build four agreeing channels from scalar lateral samples."""
    return [
        ShapeObservation(
            chainage_m=float(index * 2),
            center_px=value * scale,
            raw_center_px=value * scale,
            light_center_px=value * scale,
            standard_center_px=value * scale,
            core_center_px=value * scale,
            uncertainty_px=0.08 * scale,
        )
        for index, value in enumerate(values)
    ]


def test_alternating_residual_is_wrinkle_but_quadratic_shape_is_bend() -> None:
    """The classifier must distinguish short alternation from coherent curvature."""
    chainage = [index * 2.0 for index in range(31)]
    wrinkle = [0.25 * (index % 2 * 2 - 1) for index in range(31)]
    bend = [0.0025 * (distance - 30.0) ** 2 for distance in chainage]

    wrinkle_result = analyze_shape_evidence(observations(wrinkle), 1.0)
    bend_result = analyze_shape_evidence(observations(bend), 1.0)

    center_wrinkle = wrinkle_result[15]
    center_bend = bend_result[15]
    assert center_wrinkle.decision == "WRINKLE"
    assert center_wrinkle.wrinkle_score > center_wrinkle.bend_score
    assert center_wrinkle.cleanup_intervention >= 0.15
    assert center_bend.decision == "SUPPORTED_BEND"
    assert center_bend.bend_score > center_bend.wrinkle_score
    assert center_bend.cleanup_intervention == 0.0
    assert {scale.radius_m for scale in center_bend.scales} == set(ANALYSIS_RADII_METERS)


def test_source_pixel_scaling_preserves_classification_and_normalized_metrics() -> None:
    """Changing raster magnification must not change source-normalized evidence."""
    values = [0.12 * math.sin(index * 0.9) for index in range(31)]
    base = analyze_shape_evidence(observations(values), 1.0)
    scaled = analyze_shape_evidence(observations(values, scale=4.0), 4.0)

    for left, right in zip(base, scaled):
        assert left.decision == right.decision
        assert left.reason == right.reason
        assert left.reversal_count == right.reversal_count
        assert left.wrinkle_score == pytest.approx(right.wrinkle_score, abs=1e-12)
        assert left.bend_score == pytest.approx(right.bend_score, abs=1e-12)
        assert left.cleanup_intervention == pytest.approx(right.cleanup_intervention, abs=1e-12)
        assert left.residual_amplitude_source_px == pytest.approx(
            right.residual_amplitude_source_px, abs=1e-12
        )


def test_boundaries_and_non_direct_gap_abstain_without_bridging() -> None:
    """A physical boundary or direct-evidence gap cannot authorize cleanup."""
    profiles = observations([0.0 for _ in range(21)])
    profiles[10] = ShapeObservation(
        chainage_m=profiles[10].chainage_m,
        center_px=4.0,
        direct=False,
    )
    result = analyze_shape_evidence(profiles, 1.0)

    assert result[0].decision == "UNAVAILABLE"
    assert result[0].reason == "boundary-censored"
    assert result[10].decision == "UNAVAILABLE"
    assert result[10].reason == "non-direct"
    assert result[9].decision == "UNAVAILABLE"
    assert result[9].cleanup_intervention == 0.0
    assert result[11].decision == "UNAVAILABLE"
    assert result[11].cleanup_intervention == 0.0


def test_output_is_deterministic_and_input_size_is_bounded() -> None:
    """Repeated analysis and bounded-input rejection are deterministic."""
    profiles = observations([0.08 * math.sin(index) for index in range(31)])
    analyzer = ShapeEvidenceAnalyzer(max_observations=31)
    first = [item.as_dict() for item in analyzer.analyze(profiles, 1.0)]
    second = [item.as_dict() for item in analyzer.analyze(tuple(profiles), 1.0)]
    assert first == second

    with pytest.raises(ValueError, match="exceeds configured bound"):
        analyzer.analyze(profiles + profiles[:1], 1.0)
