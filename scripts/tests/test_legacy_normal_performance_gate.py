from __future__ import annotations

import importlib.util
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "legacy_normal_performance_gate", ROOT / "scripts" / "legacy-normal-performance-gate.py"
)
assert SPEC and SPEC.loader
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)


SCENES = ("STRAIGHT", "GAUSSIAN_KINK", "SUPPORTED_HAIRPIN", "PARALLEL_STRANDS")
KINDS = ("VISIBLE", "MANAGED")
GROUPS = tuple(f"{scene}/{kind}" for scene in SCENES for kind in KINDS)


def summary(fork: int, candidate_ns: int = 101_000_000) -> dict[str, object]:
    paired: dict[str, object] = {}
    rows: list[dict[str, object]] = []
    for group in GROUPS:
        scene, kind = group.split("/")
        pairs = []
        fixture = f"{(scene + kind).encode().hex():0<64}"[:64]
        for pair in range(20):
            baseline = 100_000_000
            candidate = candidate_ns
            pairs.append({"pair": pair, "baselineNs": baseline, "candidateNs": candidate})
            for policy, elapsed in (("IMMEDIATE_V1", baseline), ("PROTECTED_SECANT3_V1", candidate)):
                rows.append({
                    "scene": scene, "sourceKind": kind, "pair": pair, "policy": policy,
                    "elapsedNanos": elapsed, "fixtureSha256": fixture,
                })
        paired[group] = {"pairedCount": 20, "rawPairs": pairs}
    return {
        "status": "COMPLETED_ONE_PUBLIC_SYNTHETIC_FORK",
        "fork": fork,
        "noBExecution": True,
        "plannedWarmupPairsPerSceneAndSource": 12,
        "plannedMeasuredPairsPerSceneAndSource": 20,
        "plannedFreshJvmForks": 3,
        "runnerClassSha256": "a" * 64,
        "samplerClassSha256": "b" * 64 + "/" + "c" * 64,
        "harnessClassSha256": "d" * 64,
        "rows": rows,
        "pairedTimingSummary": paired,
    }


def forks(candidate_times: tuple[int, int, int] = (101_000_000,) * 3) -> list[dict[str, object]]:
    return [summary(i, candidate_times[i]) for i in range(3)]


def test_rejects_missing_fork() -> None:
    with pytest.raises(gate.GateError, match="exactly three"):
        gate.evaluate_forks(forks()[:2])


def test_rejects_malformed_raw_pairs() -> None:
    inputs = forks()
    inputs[0]["pairedTimingSummary"][GROUPS[0]]["rawPairs"].pop()
    with pytest.raises(gate.GateError, match="20 matched pairs"):
        gate.evaluate_forks(inputs)


def test_rejects_malformed_row_identity_without_crashing() -> None:
    inputs = forks()
    inputs[0]["rows"][0]["policy"] = []
    with pytest.raises(gate.GateError, match="invalid row identity"):
        gate.evaluate_forks(inputs)


def test_rejects_cross_fork_fixture_mismatch() -> None:
    inputs = forks()
    for row in inputs[2]["rows"]:
        if row["scene"] == "STRAIGHT" and row["sourceKind"] == "VISIBLE":
            row["fixtureSha256"] = "f" * 64
    with pytest.raises(gate.GateError, match="fixture hash mismatch"):
        gate.evaluate_forks(inputs)


def test_rejects_cross_fork_class_mismatch() -> None:
    inputs = forks()
    inputs[1]["harnessClassSha256"] = "e" * 64
    with pytest.raises(gate.GateError, match="class hash mismatch"):
        gate.evaluate_forks(inputs)


def test_slow_candidate_blocks_when_repeated_in_two_forks() -> None:
    result = gate.evaluate_forks(forks((250_000_000, 250_000_000, 101_000_000)))
    assert result["status"] == "BLOCKED"
    assert result["breachForkCount"] == 8 * 2


def test_isolated_slow_fork_is_inconclusive() -> None:
    result = gate.evaluate_forks(forks((250_000_000, 101_000_000, 101_000_000)))
    assert result["status"] == "INCONCLUSIVE"
    assert result["breachForkCount"] == 8


def test_zero_block_threshold_breaches_pass() -> None:
    result = gate.evaluate_forks(forks())
    assert result["status"] == "PASS"
    assert result["breachForkCount"] == 0
    assert len(result["groups"]) == 8
