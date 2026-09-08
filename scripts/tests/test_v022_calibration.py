"""T171-T176 leakage and availability gates for v0.22 confidence calibration."""

from __future__ import annotations

import pytest

from wayheatmap_analysis.v022_calibration import (
    CalibrationError,
    CalibrationExample,
    build_policy,
    grouped_split,
    validate_result_label,
    verify_locked_evaluation,
)


HASH = "a" * 64


def example(group: str, *, domain: str = "managed-z15", parameter_hash: str = HASH,
            correct: bool = True) -> CalibrationExample:
    """Create one valid independent synthetic calibration row."""

    return CalibrationExample(group, correct, "independent-human-reference", {
        "direct_coverage": 0.8,
        "worst_gap_m": 2.0,
        "branch_ambiguity": 0.1,
        "local_defect_count": 0.0,
        "solver_complete": 1.0,
        "constraint_class": "fixed",
    }, parameter_hash, domain)


def test_t171_split_is_by_geographic_group() -> None:
    """T171: all examples from a geographic group stay on one side."""

    rows = [example(f"site-{index // 2}", correct=index % 2 == 0) for index in range(20)]
    training, heldout = grouped_split(rows)
    assert {row.location_group for row in training}.isdisjoint(
        {row.location_group for row in heldout})


def test_t172_overlapping_way_rows_do_not_inflate_group_count() -> None:
    """T172: several overlapping routes/detectors still count as one site."""

    rows = [example("same-place") for _ in range(21)] + [example("other-place")]
    policy = build_policy(rows, "managed-z15", HASH, minimum_training_groups=2)
    assert len(policy.training_groups) + len(policy.heldout_groups) == 2


def test_t173_apply_or_selection_is_never_a_truth_label() -> None:
    """T173: UI decisions cannot silently become correctness labels."""

    with pytest.raises(CalibrationError):
        validate_result_label({"labelSource": "independent-human-reference", "wasApplied": True})


def test_t174_heldout_parameters_are_locked() -> None:
    """T174: evaluation rejects a parameter hash changed after splitting."""

    policy = build_policy([example(f"site-{index}") for index in range(8)],
                          "managed-z15", HASH, minimum_training_groups=2)
    with pytest.raises(CalibrationError):
        verify_locked_evaluation(policy, "b" * 64, policy.heldout_groups)


def test_t175_optimizer_objective_is_not_an_independent_accuracy_feature() -> None:
    """T175: the fixed schema excludes self-scored image/objective fit."""

    row = example("site")
    unsafe = CalibrationExample(row.location_group, row.label_correct, row.label_source,
                                {**row.features, "optimizer_objective": 0.1}, HASH, row.domain_id)
    with pytest.raises(CalibrationError):
        unsafe.validate()


def test_t176_insufficient_or_out_of_domain_confidence_is_unavailable() -> None:
    """T176: small, OOD, changed-parameter, and truncated domains get no probability."""

    policy = build_policy([example(f"site-{index}") for index in range(8)],
                          "managed-z15", HASH, minimum_training_groups=50)
    assert not policy.calibrated
    assert not policy.confidence_available("managed-z15", HASH, True)
    assert not policy.confidence_available("visible-render", HASH, True)
    assert not policy.confidence_available("managed-z15", "b" * 64, True)
    assert not policy.confidence_available("managed-z15", HASH, False)
