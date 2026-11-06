#!/usr/bin/env python3
"""Gate three public Legacy normal-policy performance forks from raw paired times."""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from pathlib import Path
from statistics import median
from typing import Any


SCENES = ("STRAIGHT", "GAUSSIAN_KINK", "SUPPORTED_HAIRPIN", "PARALLEL_STRANDS")
SOURCE_KINDS = ("VISIBLE", "MANAGED")
GROUPS = tuple(f"{scene}/{kind}" for scene in SCENES for kind in SOURCE_KINDS)
POLICIES = {"IMMEDIATE_V1", "PROTECTED_SECANT3_V1"}
FORK_COUNT = 3
PAIR_COUNT = 20
SHA256 = re.compile(r"^[0-9a-f]{64}$")


class GateError(ValueError):
    """Raised for incomplete, mismatched, or malformed public benchmark evidence."""


def _mapping(value: Any, where: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise GateError(f"{where} must be an object")
    return value


def _positive_finite(value: Any, where: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise GateError(f"{where} must be a positive finite number")
    number = float(value)
    if not math.isfinite(number) or number <= 0:
        raise GateError(f"{where} must be a positive finite number")
    return number


def _percentile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    if not ordered:
        raise GateError("cannot compute a percentile from no values")
    return ordered[max(0, math.ceil(fraction * len(ordered)) - 1)]


def _class_hashes(summary: dict[str, Any], fork: int) -> dict[str, str]:
    hashes: dict[str, str] = {}
    for key in ("runnerClassSha256", "samplerClassSha256", "harnessClassSha256"):
        value = summary.get(key)
        parts = value.split("/") if isinstance(value, str) else []
        if not parts or any(not SHA256.fullmatch(part) for part in parts):
            raise GateError(f"fork {fork}: invalid {key}")
        hashes[key] = value
    return hashes


def _validate_fork(summary_value: Any, expected_fork: int) -> dict[str, Any]:
    summary = _mapping(summary_value, f"fork {expected_fork} summary")
    fork_value = summary.get("fork")
    if isinstance(fork_value, bool) or not isinstance(fork_value, int) or fork_value != expected_fork:
        raise GateError(f"expected fork {expected_fork}, got {summary.get('fork')!r}")
    if summary.get("status") != "COMPLETED_ONE_PUBLIC_SYNTHETIC_FORK":
        raise GateError(f"fork {expected_fork}: run did not complete")
    if summary.get("noBExecution") is not True:
        raise GateError(f"fork {expected_fork}: noBExecution must be true")
    expected_counts = {
        "plannedWarmupPairsPerSceneAndSource": 12,
        "plannedMeasuredPairsPerSceneAndSource": PAIR_COUNT,
        "plannedFreshJvmForks": FORK_COUNT,
    }
    for key, expected in expected_counts.items():
        if summary.get(key) != expected:
            raise GateError(f"fork {expected_fork}: {key} must be {expected}")
    classes = _class_hashes(summary, expected_fork)

    rows_value = summary.get("rows")
    if not isinstance(rows_value, list) or len(rows_value) != len(GROUPS) * PAIR_COUNT * 2:
        raise GateError(f"fork {expected_fork}: expected all native rows for 8 groups x 20 pairs x 2 policies")
    rows: dict[tuple[str, int, str], dict[str, Any]] = {}
    fixture_by_group: dict[str, set[str]] = {group: set() for group in GROUPS}
    for index, raw_row in enumerate(rows_value):
        row = _mapping(raw_row, f"fork {expected_fork} row {index}")
        scene, kind, policy = row.get("scene"), row.get("sourceKind"), row.get("policy")
        group = f"{scene}/{kind}"
        pair = row.get("pair")
        if (not isinstance(scene, str) or not isinstance(kind, str) or not isinstance(policy, str)
                or group not in GROUPS or policy not in POLICIES or isinstance(pair, bool)
                or not isinstance(pair, int) or not 0 <= pair < PAIR_COUNT):
            raise GateError(f"fork {expected_fork}: invalid row identity at row {index}")
        key = (group, pair, policy)
        if key in rows:
            raise GateError(f"fork {expected_fork}: duplicate row {key}")
        elapsed = _positive_finite(row.get("elapsedNanos"), f"fork {expected_fork} row elapsedNanos")
        if not elapsed.is_integer():
            raise GateError(f"fork {expected_fork}: elapsedNanos must be integral")
        fixture_hash = row.get("fixtureSha256")
        if not isinstance(fixture_hash, str) or not SHA256.fullmatch(fixture_hash):
            raise GateError(f"fork {expected_fork}: invalid fixture hash")
        fixture_by_group[group].add(fixture_hash)
        rows[key] = row
    if len(rows) != len(GROUPS) * PAIR_COUNT * 2:
        raise GateError(f"fork {expected_fork}: missing policy/pair rows")
    if any(len(hashes) != 1 for hashes in fixture_by_group.values()):
        raise GateError(f"fork {expected_fork}: fixture hash mismatch within a scene/source group")

    paired = _mapping(summary.get("pairedTimingSummary"), f"fork {expected_fork} pairedTimingSummary")
    if set(paired) != set(GROUPS):
        raise GateError(f"fork {expected_fork}: paired summary must contain exactly 8 scene/source groups")
    group_pairs: dict[str, list[dict[str, float]]] = {}
    for group in GROUPS:
        item = _mapping(paired[group], f"fork {expected_fork} {group}")
        raw_pairs = item.get("rawPairs")
        if item.get("pairedCount") != PAIR_COUNT or not isinstance(raw_pairs, list) or len(raw_pairs) != PAIR_COUNT:
            raise GateError(f"fork {expected_fork} {group}: expected 20 matched pairs")
        by_pair: dict[int, dict[str, float]] = {}
        for raw in raw_pairs:
            pair = _mapping(raw, f"fork {expected_fork} {group} raw pair")
            pair_id = pair.get("pair")
            if isinstance(pair_id, bool) or not isinstance(pair_id, int) or not 0 <= pair_id < PAIR_COUNT or pair_id in by_pair:
                raise GateError(f"fork {expected_fork} {group}: invalid or duplicate pair number")
            baseline = _positive_finite(pair.get("baselineNs"), "baselineNs")
            candidate = _positive_finite(pair.get("candidateNs"), "candidateNs")
            if not baseline.is_integer() or not candidate.is_integer():
                raise GateError(f"fork {expected_fork} {group}: raw nanosecond times must be integral")
            baseline_row = rows.get((group, pair_id, "IMMEDIATE_V1"))
            candidate_row = rows.get((group, pair_id, "PROTECTED_SECANT3_V1"))
            if baseline_row is None or candidate_row is None:
                raise GateError(f"fork {expected_fork} {group}: missing matched policy row")
            if float(baseline_row["elapsedNanos"]) != baseline or float(candidate_row["elapsedNanos"]) != candidate:
                raise GateError(f"fork {expected_fork} {group}: raw pair time differs from preserved row")
            by_pair[pair_id] = {"baselineNs": baseline, "candidateNs": candidate}
        if set(by_pair) != set(range(PAIR_COUNT)):
            raise GateError(f"fork {expected_fork} {group}: expected 20 matched pairs numbered 0..19")
        group_pairs[group] = [by_pair[index] for index in range(PAIR_COUNT)]
    return {"fork": expected_fork, "classes": classes, "fixtures": {
        group: next(iter(values)) for group, values in fixture_by_group.items()
    }, "pairs": group_pairs}


def evaluate_forks(summaries: list[dict[str, Any]]) -> dict[str, Any]:
    """Validate three raw forks and recompute the declared per-scene/source gate."""
    if not isinstance(summaries, list) or len(summaries) != FORK_COUNT:
        raise GateError("exactly three fresh JVM fork summaries are required")
    forks = [_validate_fork(summary, index) for index, summary in enumerate(summaries)]
    for key in ("runnerClassSha256", "samplerClassSha256", "harnessClassSha256"):
        if len({fork["classes"][key] for fork in forks}) != 1:
            raise GateError(f"class hash mismatch across forks for {key}")
    for group in GROUPS:
        if len({fork["fixtures"][group] for fork in forks}) != 1:
            raise GateError(f"fixture hash mismatch across forks for {group}")

    group_reports: dict[str, Any] = {}
    breach_count = 0
    blocked_groups: list[str] = []
    isolated_groups: list[str] = []
    for group in GROUPS:
        fork_reports = []
        group_breaches = 0
        for fork in forks:
            pairs = fork["pairs"][group]
            baseline = [pair["baselineNs"] for pair in pairs]
            candidate = [pair["candidateNs"] for pair in pairs]
            overhead = [new - old for old, new in zip(baseline, candidate)]
            baseline_median = median(baseline)
            baseline_p95 = _percentile(baseline, 0.95)
            overhead_median = median(overhead)
            overhead_p95 = _percentile(overhead, 0.95)
            median_limit = max(5_000_000.0, 0.10 * baseline_median)
            p95_limit = max(10_000_000.0, 0.20 * baseline_p95)
            breached = overhead_median > median_limit or overhead_p95 > p95_limit
            if breached:
                group_breaches += 1
                breach_count += 1
            fork_reports.append({
                "fork": fork["fork"], "pairedCount": len(pairs),
                "medianBaselineNs": baseline_median, "p95BaselineNs": baseline_p95,
                "medianOverheadNs": overhead_median, "p95OverheadNs": overhead_p95,
                "medianLimitNs": median_limit, "p95LimitNs": p95_limit,
                "breach": breached,
            })
        if group_breaches >= 2:
            blocked_groups.append(group)
        elif group_breaches == 1:
            isolated_groups.append(group)
        group_reports[group] = {"breachForkCount": group_breaches, "forks": fork_reports}
    status = "BLOCKED" if blocked_groups else "INCONCLUSIVE" if isolated_groups else "PASS"
    return {
        "schemaVersion": 1, "status": status, "forkCount": FORK_COUNT,
        "sceneSourceGroupCount": len(GROUPS), "matchedPairsPerForkGroup": PAIR_COUNT,
        "breachForkCount": breach_count, "blockedGroups": blocked_groups,
        "isolatedBreachGroups": isolated_groups,
        "thresholds": {
            "medianOverheadNs": "max(5000000, 10% of median baseline)",
            "p95OverheadNs": "max(10000000, 20% of p95 baseline)",
            "block": "either threshold breached in at least 2 of 3 forks for a group",
            "isolated": "one fork breach is inconclusive and exits nonzero",
        },
        "groups": group_reports,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fork-dir", type=Path, action="append", required=True,
                        help="directory containing one fork's summary.json; provide exactly three")
    parser.add_argument("--report", type=Path, required=True, help="new gate report JSON path")
    args = parser.parse_args(argv)
    try:
        if len(args.fork_dir) != FORK_COUNT:
            raise GateError("exactly three --fork-dir arguments are required")
        summaries = []
        for directory in args.fork_dir:
            try:
                summaries.append(json.loads((directory / "summary.json").read_text(encoding="utf-8")))
            except (OSError, json.JSONDecodeError) as exc:
                raise GateError(f"cannot read valid summary.json from {directory}") from exc
        report = evaluate_forks(summaries)
        exit_code = 0 if report["status"] == "PASS" else 1
    except GateError as exc:
        report = {"schemaVersion": 1, "status": "INVALID_INPUT", "error": str(exc)}
        exit_code = 2
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Legacy normal performance gate: {report['status']} ({args.report})")
    if exit_code:
        print(report.get("error", "; ".join(report.get("blockedGroups", []) + report.get("isolatedBreachGroups", []))), file=sys.stderr)
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
