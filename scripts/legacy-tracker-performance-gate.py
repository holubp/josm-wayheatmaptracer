#!/usr/bin/env python3
"""Gate an explicit tracker-policy comparison without changing the normal-policy gate."""
from __future__ import annotations

import argparse
import copy
import importlib.util
import json
import math
from pathlib import Path
import re

_spec = importlib.util.spec_from_file_location(
    'legacy_normal_statistics', Path(__file__).with_name('legacy-normal-performance-gate.py'))
_normal = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_normal)
GateError = _normal.GateError
ORIGINAL = 'legacy-v02'
NORMAL = 'legacy-protected-secant3-v1'
COMPARISON = 'TRACKER_POLICY_V1'


def _positive(value, label):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0:
        raise GateError(f'invalid {label}')
    return float(value)


def evaluate_forks(summaries, candidate_policy):
    """Bind actual source/policy rows, then reuse the unchanged paired statistical oracle.

    The label adapter is in-memory only. Original evidence is never rewritten;
    both normal policies are explicitly protected, and actual tracker IDs/scales
    are validated before applying the same 3-fork/8-group/20-pair thresholds.
    """
    if (not isinstance(candidate_policy, str) or candidate_policy == ORIGINAL
            or not re.fullmatch(r'[a-z][a-z0-9-]{1,127}', candidate_policy)):
        raise GateError('an explicit non-original candidate tracker policy is required')
    if not isinstance(summaries, list) or len(summaries) != 3:
        raise GateError('exactly three fresh JVM fork summaries are required')
    adapted = copy.deepcopy(summaries)
    labels = {ORIGINAL: 'IMMEDIATE_V1', candidate_policy: 'PROTECTED_SECANT3_V1'}
    shared_facts = {}
    shared_runtime = None
    for summary in adapted:
        if not isinstance(summary, dict):
            raise GateError('tracker summary must be an object')
        expected = {'comparison': COMPARISON, 'normalPolicyId': NORMAL,
                    'baselineTrackerPolicyId': ORIGINAL, 'candidateTrackerPolicyId': candidate_policy}
        if any(summary.get(key) != value for key, value in expected.items()):
            raise GateError('tracker comparison identity mismatch')
        tracker_hashes = []
        if summary.get('productionClassesUnchanged') is not True:
            raise GateError('production classes changed during the fork')
        if summary.get('runtimeUnchanged') is not True:
            raise GateError('runtime changed during the fork')
        runtime_identity = []
        for key in ['javaRuntimeVersion', 'javaVmName']:
            value = summary.get(key)
            if not isinstance(value, str) or not value.strip() or len(value) > 256:
                raise GateError(f'invalid {key}')
            runtime_identity.append(value)
        if shared_runtime is None:
            shared_runtime = runtime_identity
        elif shared_runtime != runtime_identity:
            raise GateError('JVM identity changed across forks')
        for key in ['trackerClassSha256', 'trackerPolicyClassSha256', 'productionClassesSha256',
                    'harnessClassesSha256', 'runtimeDependenciesSha256']:
            value = summary.get(key)
            if not isinstance(value, str) or any(not _normal.SHA256.fullmatch(p) for p in value.split('/')):
                raise GateError(f'invalid {key}')
            tracker_hashes.append(value)
        original_harness_hash = summary.get('harnessClassSha256')
        if not isinstance(original_harness_hash, str) or not _normal.SHA256.fullmatch(original_harness_hash):
            raise GateError('invalid harnessClassSha256')
        # The existing oracle already enforces exact cross-fork class bindings.
        summary['harnessClassSha256'] = '/'.join([original_harness_hash] + tracker_hashes)
        rows = summary.get('rows')
        if not isinstance(rows, list):
            raise GateError('tracker rows are required')
        facts = {}
        for row in rows:
            if not isinstance(row, dict):
                raise GateError('tracker row must be an object')
            policy = row.get('policy')
            if (not isinstance(policy, str) or policy not in labels
                    or row.get('trackerPolicyId') != policy or row.get('normalPolicyId') != NORMAL
                    or row.get('trackerPolicyFallbackReason') != ''):
                raise GateError('actual source tracker policy bypass or mismatch')
            normalization = _positive(row.get('trackerNormalizationRasterPx'), 'tracker normalization')
            pitch = _positive(row.get('nativeSourcePitchRasterPx'), 'actual native source pitch')
            scene, kind = row.get('scene'), row.get('sourceKind')
            if not isinstance(scene, str) or not isinstance(kind, str):
                raise GateError('invalid source group')
            key = (scene, kind)
            previous = facts.setdefault(key, (normalization, pitch))
            if previous != (normalization, pitch):
                raise GateError('actual source pitch/normalization changed within group')
            if shared_facts.setdefault(key, (normalization, pitch)) != (normalization, pitch):
                raise GateError('actual source pitch/normalization changed across forks')
            row['policy'] = labels[policy]
    result = _normal.evaluate_forks(adapted)
    for group in result['groups'].values():
        hashes = group['resultHashesByPolicy']
        group['resultHashesByPolicy'] = {policy: hashes[label] for policy, label in labels.items()}
    result.update(comparison=COMPARISON, normalPolicyId=NORMAL,
                  baselineTrackerPolicyId=ORIGINAL, candidateTrackerPolicyId=candidate_policy)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fork-dir', type=Path, action='append', required=True)
    parser.add_argument('--candidate-policy', required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    try:
        summaries = [json.loads((p / 'summary.json').read_text()) for p in args.fork_dir]
        report = evaluate_forks(summaries, args.candidate_policy)
        code = 0 if report['status'] == 'PASS' else 1
    except (GateError, OSError, json.JSONDecodeError) as exc:
        report = {'schemaVersion': 1, 'status': 'INVALID_INPUT', 'error': str(exc)}
        code = 2
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
    print(f"Legacy tracker performance gate: {report['status']} ({args.report})")
    raise SystemExit(code)


if __name__ == '__main__':
    main()
