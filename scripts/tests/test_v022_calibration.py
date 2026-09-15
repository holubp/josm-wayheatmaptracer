"""T171-T176 leakage and availability gates for v0.22 confidence calibration."""

from __future__ import annotations

import json

import pytest

from wayheatmap_analysis.v022_calibration import (
    CalibrationError,
    CalibrationExample,
    CalibrationModel,
    SolverConfig,
    build_policy,
    grouped_split,
    one_sided_binomial_upper_bound,
    _evaluate,
    validate_result_label,
    verify_locked_evaluation,
)


HASH = "a" * 64


def example(group: str, *, domain: str = "managed-z15", parameter_hash: str = HASH,
            correct: bool = True, direct_coverage: float = 0.8) -> CalibrationExample:
    """Create one valid independent synthetic calibration row."""

    return CalibrationExample(group, correct, "independent-human-reference", {
        "direct_coverage": direct_coverage,
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


def test_t176_group_count_alone_cannot_claim_validated_calibration() -> None:
    """A claimed empirical calibration must include an actual held-out validation result."""

    import json

    rows = [example(f"independent-{index}", correct=index % 2 == 0) for index in range(80)]
    policy = build_policy(rows, "managed-z15", HASH)
    payload = json.loads(policy.as_json())
    if policy.calibrated:
        validation = payload.get("validation", {})
        assert validation.get("evaluatedGroupCount") == len(policy.heldout_groups), (
            "Independent group counts alone cannot certify calibration without held-out predictions"
        )
        assert validation.get("modelHash"), "Validation must bind the fitted versioned model"
        assert validation.get("uncertainty"), "Validation must report sampling uncertainty"



def test_t176_fits_group_weighted_signal_and_evaluates_heldout_predictions() -> None:
    """A frozen regularized model learns a signal without reading held-out labels."""

    rows = [example(f"signal-{index}", direct_coverage=index / 79,
                    correct=index >= 40) for index in range(80)]
    policy = build_policy(rows, "managed-z15", HASH)

    assert not policy.calibrated
    assert policy.reason in {"validation-inconclusive", "validation-failed"}
    assert policy.model is not None
    assert policy.validation is not None
    assert policy.validation.evaluated_group_count == len(policy.heldout_groups)
    predictions = policy.validation.as_payload()["predictions"]
    assert predictions["groupWeightedCount"] == len(policy.heldout_groups)
    assert predictions["minimum"] < predictions["maximum"]
    assert policy.validation.accuracy >= 0.75
    assert policy.model.coefficients[0] > 0.0


def test_t176_permutation_and_duplicate_variants_do_not_change_group_weighted_fit() -> None:
    """Input order and exact repeated crops cannot alter a site-weighted model."""

    rows = [example(f"site-{index}", direct_coverage=index / 79,
                    correct=index >= 40) for index in range(80)]
    baseline = build_policy(rows, "managed-z15", HASH)
    duplicated_and_reversed = list(reversed(rows + [rows[7], rows[23], rows[23]]))
    repeated = build_policy(duplicated_and_reversed, "managed-z15", HASH)

    assert baseline.model is not None
    assert repeated.model is not None
    assert baseline.model.model_hash == repeated.model.model_hash
    assert baseline.validation == repeated.validation


def test_t176_heldout_labels_cannot_change_fitted_coefficients() -> None:
    """The deterministic holdout is evaluated only after the model has been fitted."""

    rows = [example(f"site-{index}", direct_coverage=index / 79,
                    correct=index >= 40) for index in range(80)]
    _, heldout = grouped_split(rows)
    heldout_groups = {row.location_group for row in heldout}
    changed_heldout = [
        CalibrationExample(row.location_group,
                           not row.label_correct if row.location_group in heldout_groups else row.label_correct,
                           row.label_source, row.features, row.parameter_hash, row.domain_id)
        for row in rows
    ]

    baseline = build_policy(rows, "managed-z15", HASH)
    changed = build_policy(changed_heldout, "managed-z15", HASH)
    assert baseline.model is not None
    assert changed.model is not None
    assert baseline.model.coefficients == changed.model.coefficients
    assert baseline.model.intercept == changed.model.intercept
    assert baseline.model.model_hash == changed.model.model_hash
    assert baseline.validation != changed.validation


def test_t176_stable_model_round_trip_and_tamper_rejection() -> None:
    """Public model serialization has a stable hash and rejects altered metadata."""

    rows = [example(f"site-{index}", direct_coverage=index / 79,
                    correct=index >= 40) for index in range(80)]
    model = build_policy(rows, "managed-z15", HASH,
                         solver_config=SolverConfig(gradient_tolerance=1e-3)).model
    assert model is not None
    restored = CalibrationModel.from_json(model.as_json())
    assert restored.model_hash == model.model_hash
    assert restored.predict(rows[10].features) == model.predict(rows[10].features)

    tampered = json.loads(model.as_json())
    tampered["domainId"] = "visible-render"
    with pytest.raises(CalibrationError):
        CalibrationModel.from_json(json.dumps(tampered))
    tampered = json.loads(model.as_json())
    tampered["validationConfig"]["reliabilityErrorLimit"] = 0.2
    with pytest.raises(CalibrationError):
        CalibrationModel.from_json(json.dumps(tampered))


def test_t176_schema_solver_and_domain_mismatch_make_probability_unavailable() -> None:
    """A valid model is unusable outside its exact schema, parameter, and solver contract."""

    rows = [example(f"site-{index}", direct_coverage=index / 79,
                    correct=index >= 40) for index in range(80)]
    policy = build_policy(rows, "managed-z15", HASH)
    assert not policy.confidence_available("managed-z15", HASH, True)
    assert not policy.confidence_available("managed-z15", HASH, True,
                                           feature_schema=("direct_coverage",))
    assert not policy.confidence_available("visible-render", HASH, True)
    assert not policy.confidence_available("managed-z15", HASH, False)
    truncated = build_policy(rows, "managed-z15", HASH,
                             solver_config=SolverConfig(max_iterations=0))
    assert not truncated.calibrated
    assert truncated.reason == "solver-incomplete"


def test_t176_zero_one_and_invalid_labels_export_truthful_unavailable_status() -> None:
    """Zero/one matching groups and invalid labels do not crash public status export."""

    for rows in ([], [example("only-site")]):
        payload = json.loads(build_policy(rows, "managed-z15", HASH).as_json())
        assert not payload["calibrated"]
        assert payload["reason"] == "insufficient-independent-labeled-groups"

    invalid = CalibrationExample("unsafe", "yes", "independent-human-reference",  # type: ignore[arg-type]
                                 example("shape").features, HASH, "managed-z15")
    payload = json.loads(build_policy([invalid], "managed-z15", HASH).as_json())
    assert not payload["calibrated"]
    assert payload["reason"] == "invalid-calibration-data"


def test_t176_validation_uncertainty_and_severe_error_bound_are_group_level() -> None:
    """Intervals state their group assumption; zero-error 1% certification needs 299 sites."""

    rows = [example(f"site-{index}", direct_coverage=index / 79,
                    correct=index >= 40) for index in range(80)]
    validation = build_policy(rows, "managed-z15", HASH).validation
    assert validation is not None
    uncertainty = validation.as_payload()["uncertainty"]
    assert uncertainty["method"] == "two-sided-hoeffding-95-group-level"
    assert 0.0 <= uncertainty["brierScoreInterval"][0] <= uncertainty["brierScoreInterval"][1] <= 1.0
    assert one_sided_binomial_upper_bound(0, 298) > 0.01
    assert one_sided_binomial_upper_bound(0, 299) <= 0.01
    assert one_sided_binomial_upper_bound(1, 299) > one_sided_binomial_upper_bound(0, 299)


def test_t176_public_model_and_status_do_not_serialize_group_identifiers() -> None:
    """Public artifacts expose counts, never location-group identifiers or coordinates."""

    private_group = "private-site-48.1234-17.5678-osm-998877"
    rows = [example(private_group if index == 0 else f"site-{index}", direct_coverage=index / 79,
                    correct=index >= 40) for index in range(80)]
    policy = build_policy(rows, "managed-z15", HASH)
    assert private_group not in policy.as_json()
    assert policy.model is not None
    assert private_group not in policy.model.as_json()



def test_t176_constant_features_with_mixed_labels_remain_an_uncertain_base_rate() -> None:
    """No feature discrimination is not itself a reason to invent unavailable status."""

    rows = [example(f"base-rate-{index}", correct=index % 2 == 0) for index in range(80)]
    policy = build_policy(rows, "managed-z15", HASH)
    assert not policy.calibrated
    assert policy.reason == "validation-inconclusive"
    assert policy.model is not None
    assert policy.validation is not None
    assert policy.model.coefficients == (0.0,) * len(policy.model.coefficients)
    assert policy.validation.as_payload()["uncertainty"]["brierScoreInterval"] != [0.0, 0.0]



def test_r16_empty_no_extra_review_cohort_cannot_use_all_heldout_groups() -> None:
    """Zero qualifying predictions is unavailable, even with 299 heldout sites."""

    rows = [example(f"constant-{index}", correct=index % 2 == 0) for index in range(1_196)]
    policy = build_policy(rows, "managed-z15", HASH)
    assert policy.validation is not None
    severe = policy.validation.as_payload()["severeError"]
    assert severe["eligibleGroupCount"] == 0
    assert severe["eligibilityThreshold"] == 0.95
    assert severe["oneSidedUpper95"] is None
    assert not severe["certifiedNoExtraReview"]


def test_r16_heldout_inversion_is_evaluated_but_not_validated() -> None:
    """A completed fit with inverted heldout labels must leave confidence unavailable."""

    rows = [example(f"signal-{index}", direct_coverage=index / 79,
                    correct=index >= 40) for index in range(80)]
    _, heldout = grouped_split(rows)
    heldout_groups = {row.location_group for row in heldout}
    inverted = [CalibrationExample(row.location_group,
                                   not row.label_correct if row.location_group in heldout_groups else row.label_correct,
                                   row.label_source, row.features, row.parameter_hash, row.domain_id)
                for row in rows]
    policy = build_policy(inverted, "managed-z15", HASH)
    assert policy.model is not None and policy.model.solver_complete
    assert policy.validation is not None
    assert not policy.calibrated
    assert policy.reason == "validation-failed"


def test_r16_distinct_outcomes_keep_site_weight_without_synthetic_mean_route() -> None:
    """Each site's actual variants remain outcomes; mixed labels cannot become a majority label."""

    rows = []
    for index in range(80):
        rows.extend((
            example(f"site-{index}", direct_coverage=0.05, correct=False),
            example(f"site-{index}", direct_coverage=0.95, correct=True),
        ))
    policy = build_policy(rows, "managed-z15", HASH)
    assert policy.validation is not None
    assert policy.validation.evaluated_outcome_count == 2 * len(policy.heldout_groups)
    assert policy.validation.mixed_label_group_count == len(policy.heldout_groups)


def test_r16_model_encoding_export_is_copy_and_cannot_change_prediction_or_hash() -> None:
    """Mutating an exported encoding mapping cannot mutate model-owned behavior."""

    rows = []
    for index in range(80):
        row = example(f"constraint-{index}", correct=index % 2 == 1)
        features = {**row.features, "constraint_class": "movable" if index % 2 else "fixed"}
        rows.append(CalibrationExample(row.location_group, row.label_correct, row.label_source,
                                       features, row.parameter_hash, row.domain_id))
    model = build_policy(rows, "managed-z15", HASH,
                         solver_config=SolverConfig(gradient_tolerance=1e-3)).model
    assert model is not None
    probe = rows[0].features
    before = model.predict(probe)
    exported = model.feature_encoding
    exported["constraint_class"]["fixed"] = 10.0
    assert model.predict(probe) == before
    assert model.model_hash == CalibrationModel.from_json(model.as_json()).model_hash


def test_r16_invalid_solver_scalars_and_divergence_are_truthfully_unavailable() -> None:
    """Bool scalars and overflow-prone solvers cannot leak malformed usable models."""

    with pytest.raises(CalibrationError):
        SolverConfig(regularization=True).validate()  # type: ignore[arg-type]
    rows = [example(f"signal-{index}", direct_coverage=index / 79,
                    correct=index >= 40) for index in range(80)]
    policy = build_policy(rows, "managed-z15", HASH,
                          solver_config=SolverConfig(learning_rate=1e308, max_iterations=10))
    assert not policy.calibrated
    assert policy.reason in {"invalid-solver-configuration", "solver-numerical-failure"}
    assert policy.model is None
    json.loads(policy.as_json())


def test_r16_locked_evaluation_requires_all_and_only_reserved_groups_once() -> None:
    """Later callers cannot cherry-pick a favorable heldout subset or duplicate a group."""

    rows = [example(f"site-{index}", direct_coverage=index / 79,
                    correct=index >= 40) for index in range(80)]
    policy = build_policy(rows, "managed-z15", HASH)
    assert policy.model is not None
    verify_locked_evaluation(policy, HASH, policy.heldout_groups, model_hash=policy.model.model_hash)
    with pytest.raises(CalibrationError):
        verify_locked_evaluation(policy, HASH, policy.heldout_groups[:-1])
    with pytest.raises(CalibrationError):
        verify_locked_evaluation(policy, HASH, (*policy.heldout_groups, "extra-site"))
    with pytest.raises(CalibrationError):
        verify_locked_evaluation(policy, HASH, (*policy.heldout_groups, policy.heldout_groups[0]))


def test_r16_predeclared_reliability_gate_needs_support_but_accepts_large_constant_base_rate() -> None:
    """80 sites are inconclusive; enough independent constant-rate evidence can validate."""

    small = build_policy([example(f"small-{index}", correct=index % 2 == 0) for index in range(80)],
                         "managed-z15", HASH)
    assert not small.calibrated
    assert small.reason == "validation-inconclusive"

    rows = [example(f"large-{index}", correct=index % 2 == 0) for index in range(8_000)]
    large = build_policy(rows, "managed-z15", HASH)
    assert large.calibrated
    assert large.confidence_available("managed-z15", HASH, True)
    assert large.validation is not None
    assert large.validation.as_payload()["reliability"]["accepted"]



def test_r16_balanced_global_error_cannot_hide_binwise_reliability_failure() -> None:
    """Opposite reliability errors in separate predicted-probability bins do not cancel."""

    training = [example(f"train-{index}", direct_coverage=index / 79,
                        correct=index >= 40) for index in range(80)]
    model = build_policy(training, "managed-z15", HASH).model
    assert model is not None and model.solver_complete
    heldout = []
    for index in range(80):
        heldout.extend((
            example(f"biased-{index}", direct_coverage=0.0, correct=True),
            example(f"biased-{index}", direct_coverage=1.0, correct=False),
        ))
    validation = _evaluate(model, heldout)
    assert abs(validation.mean_calibration_error) < 0.1
    assert validation.reliability_status == "failed"


def test_r16_eligible_mixed_site_counts_one_conservative_severe_event() -> None:
    """One wrong qualifying variant makes its independently eligible site an error event."""

    training = [example(f"train-{index}", direct_coverage=index / 79,
                        correct=index >= 40) for index in range(80)]
    base = build_policy(training, "managed-z15", HASH).model
    assert base is not None
    from dataclasses import replace
    forced = replace(base, intercept=10.0, solver_complete=True)
    heldout = [
        example("mixed-site", correct=True),
        example("mixed-site", correct=False),
        example("safe-site", correct=True),
    ]
    validation = _evaluate(forced, heldout)
    severe = validation.as_payload()["severeError"]
    assert severe["eligibleGroupCount"] == 2
    assert severe["severeSiteEventCount"] == 1
    assert not severe["certifiedNoExtraReview"]
    assert severe["doesNotGrantApplyPermission"]


@pytest.mark.parametrize("value", [10**400, -(10**400)])
def test_r16_huge_numeric_inputs_return_unavailable(value: int) -> None:
    """Range admission precedes conversion of arbitrary Python integers."""
    from dataclasses import replace

    invalid_solver = build_policy([], "managed-z15", HASH,
                                  solver_config=SolverConfig(learning_rate=value))
    assert invalid_solver.reason == "invalid-solver-configuration"
    row = example("huge")
    row = replace(row, features={**row.features, "worst_gap_m": value})
    invalid_data = build_policy([row], "managed-z15", HASH)
    assert invalid_data.reason == "invalid-calibration-data"
    assert not invalid_solver.calibrated and not invalid_data.calibrated


def test_r16_model_loading_wraps_numeric_overflow() -> None:
    """Serialized numeric overflow is a typed model-validation failure."""
    rows = [example(f"load-{i}", direct_coverage=i / 79, correct=i >= 40)
            for i in range(80)]
    model = build_policy(rows, "managed-z15", HASH).model
    assert model is not None
    for field in ("intercept", "coefficients"):
        payload = model.as_payload()
        payload[field] = 10**400 if field == "intercept" else [10**400] * 6
        with pytest.raises(CalibrationError):
            CalibrationModel.from_json(json.dumps(payload))


def test_r16_reliability_bins_include_both_probability_endpoints() -> None:
    """Internal edges start their bin; saturated one belongs to the final bin."""
    from wayheatmap_analysis.v022_calibration import DEFAULT_VALIDATION_CONFIG, _reliability

    probabilities = [0.0, 0.25, 0.5, 0.75, 1.0]
    stats = [(p, p, [p], [p]) for p in probabilities]
    _, bins = _reliability(stats, DEFAULT_VALIDATION_CONFIG)
    assert [row["groupCount"] for row in bins] == [1, 1, 1, 2]
    assert [row["bin"] for row in bins] == [[0.0, 0.25], [0.25, 0.5],
                                            [0.5, 0.75], [0.75, 1.0]]


@pytest.mark.parametrize("probability", [float("nan"), float("inf"), -0.01, 1.01])
def test_r16_reliability_rejects_invalid_probabilities(probability: float) -> None:
    """Out-of-domain predictions cannot silently enter a reliability bin."""
    from wayheatmap_analysis.v022_calibration import DEFAULT_VALIDATION_CONFIG, _reliability

    with pytest.raises(CalibrationError):
        _reliability([(0.0, 0.0, [probability], [0.0])], DEFAULT_VALIDATION_CONFIG)


def test_r16_saturated_model_predictions_evaluate_without_boundary_errors() -> None:
    """Finite saturated logits exercise the actual prediction-to-validation path."""
    from dataclasses import replace

    rows = [example(f"saturation-{i}", direct_coverage=i / 79, correct=i >= 40)
            for i in range(80)]
    model = build_policy(rows, "managed-z15", HASH).model
    assert model is not None
    for intercept, probability in ((-1000.0, 0.0), (1000.0, 1.0)):
        saturated = replace(model, intercept=intercept,
                            coefficients=(0.0,) * 6, solver_complete=True)
        assert saturated.predict(rows[0].features) == probability
        validation = _evaluate(saturated, rows)
        assert len(validation.reliability_bins) == 1
        assert validation.reliability_bins[0]["groupCount"] == 80
        assert validation.reliability_status == "failed"
