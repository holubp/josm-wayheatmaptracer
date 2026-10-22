"""Independent arithmetic and fail-closed tests for the Task0 proof worksheet."""

import copy
import importlib.util
import json
import math
from fractions import Fraction as F
from pathlib import Path

import pytest
from hypothesis import given, strategies as st


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "shared_resource_contract", ROOT / "scripts/verify-b-shared-resource-contract.py"
)
contract = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(contract)


def counts(**changes):
    """Return declared tiny algebraic counts, not a captured or inferred graph."""
    result = dict(n=3, modes=2, width=4, height=3, source_vertices=12,
                  source_cells=6, samples=9, states=6, components=2,
                  mixture_entries=4, local_entries=4, metadata_slots=32,
                  footprint_vertices=8, pairs=8, region_edges=0,
                  live_existing_bytes=1024, source_region_edges=4, source_region_polygons=1)
    result.update(changes)
    return result


def test_normalization_pins_and_negative_unary_density():
    pins = contract.arithmetic_pins()
    assert pins["wide"] == ["1/21", "1/3", "13/21"]
    assert pins["narrow"] == ["25/12", "10/3", "55/12"]
    assert sum(map(F, pins["wide"])) == 1
    assert sum(map(F, pins["narrow"])) * F(1, 10) == 1
    assert all(F(p) > 1 for p in pins["narrow"])


def test_excess_before_b3_and_wrong_order_are_distinguished():
    pins = contract.arithmetic_pins()
    assert F(pins["excess_then_b3"]) == F(1, 200)
    assert F(pins["b3_then_excess"]) == 0
    # Independent 2D centre weight: (2/4)*(2/4), on excess1/50.
    assert F(1, 50) * F(1, 4) == F(pins["excess_then_b3"])
    with pytest.raises(contract.ProofError, match="arithmetic pin"):
        contract.validate_pins({**pins, "excess_then_b3": "0"})


def test_polynomial_simpson_pin_is_independent_of_chord_code():
    # D(t,t)=2t+2t²; Simpson over t∈[0,1], multiply by sqrt2 later.
    coefficient = (F(0) + 4 * (2 * F(1, 2) + 2 * F(1, 2) ** 2) + 4) / 6
    assert coefficient == F(5, 3)
    assert F(contract.arithmetic_pins()["diagonal_sqrt2_coefficient"]) == coefficient


def test_response_count_is_nine_per_footprint_and_four_corners_per_local_cell():
    report = contract.derive(counts())
    assert report["counts"]["response_locations"] == 8 + 4 * 4
    assert report["counts"]["stencil_reads"] == 9 * (8 + 4 * 4)
    assert report["counts"]["interval_events_upper"] == 8 * (4 + 3 + 4)
    for wrong in (9 * 3, 9 * 8, 9 * (8 + 4)):
        mutated = copy.deepcopy(report)
        mutated["counts"]["stencil_reads"] = wrong
        with pytest.raises(contract.ProofError, match="claim differs"):
            contract.validate_claim(counts(), mutated)


def test_independent_tiny_census_and_closed_scalar_blocks():
    report = contract.derive(counts())
    assert report["counts"]["exact_api_upper"] == 53760
    assert report["work_upper"] == 56592450944 + (12 + 9) * (32 + 16 + 128 * 4)
    assert report["proposed_peak_bytes"] == 3209480
    assert report["scalar_block_ceilings"] == dict(metadata=256, response=1024,
                                                   context=128, deficit=96,
                                                   pair=512, event=4096)
    assert report["payload_bytes"] == dict(source=552, descriptors=1536, planes=2088,
                                           coordinates=288, table=1352, exact=2151440,
                                           row=2624, region=0, report=1048576)


def test_old_gap_budget_is_not_a_source_bound_proof():
    report = contract.derive(counts())
    assert report["work_upper"] != 78320919079993196
    report["work_upper"] = 78320919079993196
    with pytest.raises(contract.ProofError, match="claim differs"):
        contract.validate_claim(counts(), report)


def test_original_evidence_region_queries_have_an_independent_work_bound():
    original = counts(source_region_edges=4, source_region_polygons=1)
    report = contract.derive(original)
    # Twelve analysis vertices and nine original samples, each with a separate
    # four-edge source-authority membership check. Chord region is absent.
    assert report["counts"]["source_region_queries"] == 21
    assert report["counts"]["source_region_query_upper"] == 32 + 16 + 128 * 4
    larger = contract.derive({**original, "source_region_edges": 7})
    assert larger["work_upper"] - report["work_upper"] == 21 * 128 * 3
    another_polygon = contract.derive({**original, "source_region_edges": 7,
                                      "source_region_polygons": 2})
    assert another_polygon["work_upper"] - larger["work_upper"] == 21 * 16


@pytest.mark.parametrize("edges,polygons", [(0, 0), (2, 1), (4, 2)])
def test_source_region_cannot_be_omitted_or_have_impossible_polygon_counts(edges, polygons):
    with pytest.raises(contract.ProofError, match="capability"):
        contract.derive(counts(source_region_edges=edges, source_region_polygons=polygons))


@pytest.mark.parametrize("allocation", ["source", "table", "report", "exact"])
def test_omitted_owned_allocation_is_refused(allocation):
    report = contract.derive(counts())
    assert report["payload_bytes"][allocation] > 0
    del report["payload_bytes"][allocation]
    with pytest.raises(contract.ProofError, match="claim differs"):
        contract.validate_claim(counts(), report)


def test_bit_expression_chain_and_mutated_word_cap():
    bits = contract.bit_bounds()
    assert bits == dict(operand=2098, difference=2099, determinant=4197,
                       affine=4198, delta=4199, face_numerator=4216,
                       face_denominator=4199, midpoint_numerator=8416,
                       midpoint_denominator=8399, fractional=12617,
                       rounded_conversion=13691, ceiling=16384)
    assert bits["rounded_conversion"] < bits["ceiling"]
    with pytest.raises(contract.ProofError, match="word ceiling"):
        contract.bit_bounds(words=427)


def test_negative_power_magnitude_and_preallocation_shift_guard():
    # Java BigInteger.bitLength(-2^k)==k; magnitude needs k+1 bits.
    negative = -(1 << 16384)
    java_signed_bit_length = (-negative - 1).bit_length()
    assert java_signed_bit_length == 16384
    assert abs(negative).bit_length() == 16385
    assert contract.check_exact_size(-(1 << 16383)) == 16384
    with pytest.raises(contract.ProofError, match="absolute magnitude"):
        contract.check_exact_size(negative)
    with pytest.raises(contract.ProofError, match="before allocation"):
        contract.check_exact_size(1 << 16382, shift=2)
    assert contract.check_exact_size(0, shift=16385) == 0


def test_boundary_fraction_and_tie_proof_does_not_use_float_epsilon():
    # Two face events are equal by a crossproduct, despite different denominators.
    assert F(1, 3) == F(2, 6)
    assert F(1, 3) < F(1, 3) + F(1, 1 << 200)
    assert contract.boundary_owner(F(1), 4) == 0
    assert contract.boundary_owner(F(0), 4) == 0
    assert contract.boundary_owner(F(4), 4) == 3
    assert contract.boundary_owner(F(3, 2), 4) == 1


def test_fp_enclosures_and_refusal_are_distinct_from_universal_achievability():
    lo, hi = contract.rounded_enclosure(F(1, 3))
    assert F(lo) <= F(1, 3) <= F(hi)
    for value in (1.0, math.nextafter(1.0, 0.0), math.nextafter(1.0, math.inf)):
        lo, hi = contract.one_ulp_enclosure(value)
        assert lo < value < hi
    lo, hi = contract.one_ulp_enclosure(float.fromhex("0x1.6a09e667f3bcdp+0"))
    assert F(lo) ** 2 < 2 < F(hi) ** 2
    with pytest.raises(contract.ProofError, match="underflow"):
        contract.rounded_enclosure(F(1, 1 << 1076))
    with pytest.raises(contract.ProofError, match="nonfinite"):
        contract.one_ulp_enclosure(float.fromhex("0x1.fffffffffffffp+1023"))


@given(st.integers(min_value=0, max_value=10**6),
       st.integers(min_value=0, max_value=64))
def test_aligned_ledger_charge_is_conservative(length, width):
    size = contract.array_bytes(length, width)
    assert size >= 16 + length * width
    assert size % 8 == 0
    assert size - (16 + length * width) < 8


@given(st.integers(min_value=0, max_value=10**6))
def test_weak_envelope_mass_bound_tends_to_zero(integer):
    epsilon = F(integer, 10**12)
    h = 64 * epsilon * F(3, 10)
    q = h / (1 + h)
    assert 0 <= q <= h
    assert 0 <= epsilon * F(3, 4) <= epsilon


@pytest.mark.parametrize("bad", [True, -1, 1.5, "3", None])
def test_counts_reject_bool_and_non_integer_shapes(bad):
    with pytest.raises(contract.ProofError):
        contract.derive(counts(states=bad))


def test_checked_overflow_and_envelope_refusal():
    with pytest.raises(contract.ProofError, match="signed-long"):
        contract.checked_add((1 << 63) - 1, 1)
    with pytest.raises(contract.ProofError, match="signed-long"):
        contract.checked_mul(1 << 62, 4)
    with pytest.raises(contract.ProofError, match="64MiB"):
        contract.derive(counts(live_existing_bytes=64 * 1024 * 1024))
    with pytest.raises(contract.ProofError, match="capability"):
        contract.derive(counts(n=82))


def test_capability_maxima_are_not_a_promise_of_memory_or_long_feasibility():
    worst = counts(n=81, modes=8192, width=401, height=477,
                   source_vertices=191401, source_cells=190404, samples=81 * 401,
                   states=81 * 96, components=8273, mixture_entries=8273 * 96,
                   local_entries=8192 * 96, metadata_slots=1000000,
                   footprint_vertices=8192 * 401 * 477, pairs=80 * 96 * 96)
    with pytest.raises(contract.ProofError, match="64MiB"):
        contract.derive(worst)
    # A narrow grid has more face events despite a similar vertex count.
    worst.update(width=95700, height=2, footprint_vertices=8192 * 95700 * 2)
    with pytest.raises(contract.ProofError, match="signed-long"):
        contract.derive(worst)


@pytest.mark.parametrize("change", [dict(modes=8193), dict(samples=1204),
                                    dict(footprint_vertices=25), dict(region_edges=2)])
def test_separate_lower_guards_are_not_hidden_in_u(change):
    with pytest.raises(contract.ProofError, match="capability"):
        contract.derive(counts(**change))


def test_proposal_does_not_claim_unimplemented_bodies_or_jdk_transients_proven():
    report = contract.derive(counts())
    assert report["entryEligible"] is False
    assert report["status"] == "PROOF_CANDIDATE_REVIEW_REQUIRED"
    assert report["unproved"] == []
    assert report["exact_unit"] == "WEIGHTED_QUADRATIC_LIMB_PAIR_PROXY"
    assert report["future_compliance"] == ["body-DAG", "owned-layouts", "checkpoint-spacing"]
    assert report["payload_bytes"]["exact"] == 2151440
    assert sum(report["jdk"]["caller_slots"].values()) == 136
    assert report["jdk"]["library_slots"] + sum(report["jdk"]["caller_slots"].values()) == 512
    assert report["jdk"]["public_magnitude_limbs"] == 512
    assert report["jdk"]["internal_limbs"] == 1024
    assert report["work_upper"] > 1 << 31
    with pytest.raises(contract.ProofError, match="source proof"):
        contract.require_entry(report)
    with pytest.raises(contract.ProofError, match="source proof"):
        contract.require_entry({"entryEligible": True, "status": "APPROVED"})


def test_cli_is_metadata_only_and_closed(tmp_path, capsys):
    source = tmp_path / "counts.json"
    source.write_text(json.dumps(counts()), encoding="utf-8")
    assert contract.main([str(source)]) == 0
    result = json.loads(capsys.readouterr().out)
    assert result == contract.derive(counts())
    assert result["entryEligible"] is False
    source.write_text('{"n":1,"n":2}', encoding="utf-8")
    with pytest.raises(contract.ProofError, match="duplicate"):
        contract.main([str(source)])
    source.write_bytes(b"x" * 8193)
    with pytest.raises(contract.ProofError, match="bounded"):
        contract.main([str(source)])


def test_source_pin_drift_and_symlink_refuse_without_loading_java(tmp_path, monkeypatch):
    name, digest = next(iter(contract.SOURCE_PINS.items()))
    monkeypatch.setattr(contract, "ROOT", tmp_path)
    target = tmp_path / name
    target.parent.mkdir(parents=True)
    target.write_bytes(b"changed actual source")
    with pytest.raises(contract.ProofError, match="source proof pin differs"):
        contract.derive(counts())
    other = tmp_path / "same-size-foreign-source"
    other.write_bytes(b"public synthetic")
    target.unlink()
    target.symlink_to(other)
    with pytest.raises(contract.ProofError, match="bounded regular"):
        contract.derive(counts())
