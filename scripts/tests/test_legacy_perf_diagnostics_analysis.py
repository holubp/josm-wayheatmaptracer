"""Diagnostic analysis preserves timing evidence without granting adoption."""
import copy
import importlib.util
import json
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


analysis = load('diagnostic_analysis', ROOT / 'scripts/analyze-legacy-performance-diagnostics.py')
base = load('diagnostic_fixture', Path(__file__).with_name('test_legacy_normal_performance_gate.py'))


def diagnostic_forks():
    values = base.forks((101_000_000,) * 3)
    for value in values:
        value.update(status='COMPLETED_ONE_DIAGNOSTIC_FORK', diagnosticOnly=True)
    return values


def test_raw_gate_rejects_diagnostic_data_and_read_only_view_preserves_it():
    values = diagnostic_forks()
    original = copy.deepcopy(values)
    with pytest.raises(ValueError, match='did not complete'):
        base.gate.evaluate_forks(values)
    assert analysis.wall_statistics(values, 'normal')['status'] == 'PASS'
    assert values == original
    values[0]['diagnosticOnly'] = False
    with pytest.raises(ValueError, match='explicitly diagnostic'):
        analysis.wall_statistics(values, 'normal')


def test_counter_unavailability_and_corruption_are_distinct():
    assert analysis.delta({'cpuStart': None, 'cpuEnd': 100}, 'cpu') is None
    assert analysis.delta({'cpuStart': 10, 'cpuEnd': 25}, 'cpu') == 15
    for start, end in [(20, 10), (True, 10), (-1, 10)]:
        with pytest.raises(ValueError):
            analysis.delta({'cpuStart': start, 'cpuEnd': end}, 'cpu')


def test_tracker_read_only_statistics_keep_actual_policy_identities():
    values = diagnostic_forks()
    candidate = 'native-reciprocal-support-paired-chord-v4'
    for summary in values:
        summary.update(comparison='TRACKER_POLICY_V1', normalPolicyId='legacy-protected-secant3-v1',
                       baselineTrackerPolicyId='legacy-v02', candidateTrackerPolicyId=candidate,
                       trackerClassSha256='3' * 64, trackerPolicyClassSha256='4' * 64,
                       productionClassesSha256='5' * 64, productionClassesUnchanged=True,
                       harnessClassesSha256='6' * 64, runtimeDependenciesSha256='7' * 64,
                       runtimeUnchanged=True, javaRuntimeVersion='17-fixture', javaVmName='fixture VM')
        for row in summary['rows']:
            row['policy'] = 'legacy-v02' if row['policy'] == 'IMMEDIATE_V1' else candidate
            row.update(trackerPolicyId=row['policy'], normalPolicyId='legacy-protected-secant3-v1',
                       trackerNormalizationRasterPx=6.0, nativeSourcePitchRasterPx=24.0,
                       trackerPolicyFallbackReason='')
    original = copy.deepcopy(values)
    result = analysis.wall_statistics(values, 'tracker')
    assert result['status'] == 'PASS'
    assert values == original
    assert all(set(group['resultHashesByPolicy']) == {'legacy-v02', candidate}
               for group in result['groups'].values())


def test_pause_alignment_uses_absolute_monotonic_time_not_millisecond_estimates():
    events, offsets = analysis.parse_vm_log(
        '[100ms][1000000000ns][100000000ns][info][gc] GC(1) Pause Young 9M->3M(512M) 10.000ms\n'
        '[101ms][1001000000ns][101000000ns][info][safepoint] Safepoint "Cleanup", Total: 500000 ns\n'
        '[102ms][1002000000ns][102000000ns][info][gc] Concurrent Mark Cycle 1.000ms\n')
    assert [(e['startNanos'], e['endNanos']) for e in events] == [
        (990000000, 1000000000), (1000500000, 1001000000)]
    assert offsets == (900000000, 900000000)
    with pytest.raises(ValueError, match='paired timenanos'):
        analysis.parse_vm_log('[100ms][info][gc] Pause Young 1.0ms')


def test_incomplete_compilation_is_not_a_zero_duration_task(tmp_path):
    path = tmp_path / 'compilation.xml'
    path.write_text('<hotspot_log><task stamp="1.0" method="M m ()V">'
                    '<task_done stamp="1.02" success="1"/></task>'
                    '<task stamp="2.0" method="N n ()V"/></hotspot_log>')
    tasks, incomplete = analysis.parse_compilations(path)
    assert incomplete == 1
    assert tasks == [{'startUptimeNanos': 1000000000, 'endUptimeNanos': 1020000000,
                      'method': 'M m ()V', 'success': '1'}]


def write_cohort(root):
    values = diagnostic_forks()
    for fork, summary in enumerate(values):
        folder = root / f'fork-{fork}'
        folder.mkdir()
        (folder / 'summary.json').write_text(json.dumps(summary))
        calls = []
        for scene in base.SCENES:
            for kind in base.KINDS:
                for phase, count in [('warmup', 12), ('measured', 20)]:
                    for pair in range(count):
                        policies = ['IMMEDIATE_V1', 'PROTECTED_SECANT3_V1']
                        for policy in (policies[::-1] if (pair + fork) & 1 else policies):
                            elapsed = 100000000 if policy == 'IMMEDIATE_V1' else 101000000
                            start = 1000000000000 + len(calls) * 1000000000
                            calls.append({'schema': 1, 'status': 'completed', 'scene': scene,
                                          'sourceKind': kind, 'pair': pair, 'policy': policy, 'phase': phase,
                                          'executionOrder': 'candidate-first' if (pair + fork) & 1 else 'baseline-first',
                                          'timingStartMonotonicNanos': start,
                                          'timingEndMonotonicNanos': start + elapsed, 'elapsedNanos': elapsed})
        (folder / 'calls.jsonl').write_text(''.join(json.dumps(c) + '\n' for c in calls))
        (root / f'fork-{fork}.jvm-gc-safepoint.log').write_text(
            '[1000ms][1000000000000ns][1000000000ns][info][gc] Using G1\n')
        (root / f'fork-{fork}.compilation.xml').write_text('<hotspot_log/>')
    return values


def test_complete_report_keeps_all_pairs_and_cannot_be_adopted(tmp_path):
    write_cohort(tmp_path)
    report = analysis.analyze(tmp_path, 'normal')
    assert report['status'] == 'DIAGNOSTIC_ONLY' and report['adoptionAllowed'] is False
    assert report['wallClockStatistics']['status'] == 'PASS'
    assert [f['calls'] for f in report['forks']] == [512] * 3
    assert len((tmp_path / 'diagnostic-pairs.jsonl').read_text().splitlines()) == 480
    assert all(v['threadCpuNanosDeltaMedian'] is None for p in report['counterMedians'].values()
               for v in p.values())
    assert all(v['threadCpuNanosAvailableCalls'] == 0 for p in report['counterMedians'].values()
               for v in p.values())
    assert all(json.loads((tmp_path / f'fork-{f}/summary.json').read_text())['diagnosticOnly']
               for f in range(3))


def test_wrong_warmup_identity_does_not_pass_by_count_alone(tmp_path):
    write_cohort(tmp_path)
    path = tmp_path / 'fork-0/calls.jsonl'
    rows = [json.loads(line) for line in path.read_text().splitlines()]
    rows[0]['scene'] = 'WRONG'
    path.write_text(''.join(json.dumps(c) + '\n' for c in rows))
    with pytest.raises(ValueError, match='exact fixed protocol'):
        analysis.analyze(tmp_path, 'normal')
    assert not (tmp_path / 'diagnostic-pairs.jsonl').exists()
