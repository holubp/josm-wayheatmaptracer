"""Tracker comparisons must retain the existing statistical gate and bind real policies."""
import copy
import importlib.util
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


base = load('normal_gate_tests', Path(__file__).with_name('test_legacy_normal_performance_gate.py'))
gate = load('tracker_gate', ROOT / 'scripts/legacy-tracker-performance-gate.py')
POLICY = 'native-reciprocal-support-v1'


def inputs(times=(101_000_000,) * 3):
    summaries = base.forks(times)
    for summary in summaries:
        summary.update(comparison='TRACKER_POLICY_V1', baselineTrackerPolicyId='legacy-v02',
                       candidateTrackerPolicyId=POLICY,
                       normalPolicyId='legacy-protected-secant3-v1',
                       trackerClassSha256='3' * 64, trackerPolicyClassSha256='4' * 64,
                       productionClassesSha256='6' * 64, productionClassesUnchanged=True,
                       harnessClassesSha256='8' * 64, runtimeDependenciesSha256='9' * 64,
                       runtimeUnchanged=True, javaRuntimeVersion='17.0-test', javaVmName='test VM')
        for row in summary['rows']:
            row['policy'] = 'legacy-v02' if row['policy'] == 'IMMEDIATE_V1' else POLICY
            row.update(trackerPolicyId=row['policy'], normalPolicyId='legacy-protected-secant3-v1',
                       trackerNormalizationRasterPx=6.0, nativeSourcePitchRasterPx=24.0,
                       trackerPolicyFallbackReason='')
    return summaries


def test_valid_tracker_comparison_preserves_input_and_thresholds():
    evidence = inputs()
    original = copy.deepcopy(evidence)
    result = gate.evaluate_forks(evidence, POLICY)
    assert result['status'] == 'PASS'
    assert result['comparison'] == 'TRACKER_POLICY_V1'
    assert result['candidateTrackerPolicyId'] == POLICY
    assert result['matchedPairsPerForkGroup'] == 20
    assert evidence == original


@pytest.mark.parametrize('field,value', [
    ('comparison', 'NORMAL_POLICY'), ('normalPolicyId', 'legacy-immediate-v1'),
    ('baselineTrackerPolicyId', POLICY), ('candidateTrackerPolicyId', 'legacy-v02'),
    ('trackerClassSha256', ''),
])
def test_rejects_false_comparison_identity(field, value):
    evidence = inputs()
    evidence[0][field] = value
    with pytest.raises(gate.GateError):
        gate.evaluate_forks(evidence, POLICY)


@pytest.mark.parametrize('field,value', [
    ('trackerPolicyId', 'legacy-v02'), ('normalPolicyId', 'legacy-immediate-v1'),
    ('nativeSourcePitchRasterPx', 6.0), ('trackerNormalizationRasterPx', float('nan')),
    ('trackerPolicyFallbackReason', 'native-pitch-unavailable'),
])
def test_rejects_bypass_or_inconsistent_actual_row(field, value):
    evidence = inputs()
    candidate = next(row for row in evidence[0]['rows'] if row['policy'] == POLICY)
    candidate[field] = value
    with pytest.raises(gate.GateError):
        gate.evaluate_forks(evidence, POLICY)


def test_original_normal_gate_stays_default_strict():
    with pytest.raises(base.gate.GateError, match='invalid row identity'):
        base.gate.evaluate_forks(inputs())


def test_tracker_does_not_waive_slowdown_or_cross_fork_class_changes():
    assert gate.evaluate_forks(inputs((120_000_000,) * 3), POLICY)['status'] == 'BLOCKED'
    evidence = inputs()
    evidence[1]['trackerClassSha256'] = '5' * 64
    with pytest.raises(gate.GateError):
        gate.evaluate_forks(evidence, POLICY)


def test_rejects_cross_fork_source_scale_change():
    evidence = inputs()
    for row in evidence[1]['rows']:
        row['nativeSourcePitchRasterPx'] = 25.0
    with pytest.raises(gate.GateError):
        gate.evaluate_forks(evidence, POLICY)


@pytest.mark.parametrize('value', [None, '', 'invalid'])
def test_rejects_missing_complete_production_class_binding(value):
    evidence = inputs()
    evidence[0]['productionClassesSha256'] = value
    with pytest.raises(gate.GateError):
        gate.evaluate_forks(evidence, POLICY)


def test_rejects_production_classes_changed_during_or_between_forks():
    evidence = inputs()
    evidence[0]['productionClassesUnchanged'] = False
    with pytest.raises(gate.GateError):
        gate.evaluate_forks(evidence, POLICY)
    evidence = inputs()
    evidence[1]['productionClassesSha256'] = '7' * 64
    with pytest.raises(gate.GateError):
        gate.evaluate_forks(evidence, POLICY)


@pytest.mark.parametrize('field,value', [
    ('harnessClassesSha256', None), ('runtimeDependenciesSha256', 'invalid'),
    ('runtimeUnchanged', False), ('javaRuntimeVersion', ''), ('javaVmName', None),
])
def test_rejects_missing_or_changed_runtime_binding(field, value):
    evidence = inputs()
    evidence[0][field] = value
    with pytest.raises(gate.GateError):
        gate.evaluate_forks(evidence, POLICY)


@pytest.mark.parametrize('field,value', [
    ('harnessClassesSha256', 'a' * 64), ('runtimeDependenciesSha256', 'b' * 64),
    ('javaRuntimeVersion', '17-other'), ('javaVmName', 'other VM'),
])
def test_rejects_runtime_changes_between_forks(field, value):
    evidence = inputs()
    evidence[1][field] = value
    with pytest.raises(gate.GateError):
        gate.evaluate_forks(evidence, POLICY)
