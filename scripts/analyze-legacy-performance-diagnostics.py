#!/usr/bin/env python3
"""Read-only attribution of diagnostic cohorts; never grants qualification."""
from __future__ import annotations

import argparse
import copy
import importlib.util
import json
from pathlib import Path
import re
from statistics import median
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
GROUPS = [(scene, kind) for scene in ('STRAIGHT', 'GAUSSIAN_KINK', 'SUPPORTED_HAIRPIN', 'PARALLEL_STRANDS')
          for kind in ('VISIBLE', 'MANAGED')]


def load_gate(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def wall_statistics(summaries, comparison):
    """Compute unchanged statistics on a named in-memory view of diagnostic data."""
    if len(summaries) != 3 or any(s.get('status') != 'COMPLETED_ONE_DIAGNOSTIC_FORK'
                                or s.get('diagnosticOnly') is not True for s in summaries):
        raise ValueError('Exactly three complete explicitly diagnostic forks are required')
    script = 'legacy-normal-performance-gate.py' if comparison == 'normal' else 'legacy-tracker-performance-gate.py'
    gate = load_gate('diagnostic_wall_statistics', ROOT / 'scripts' / script)
    candidate = 'native-reciprocal-support-paired-chord-v4'
    evaluate = (lambda values: gate.evaluate_forks(values)) if comparison == 'normal' else (
        lambda values: gate.evaluate_forks(values, candidate))
    try:
        evaluate(summaries)
    except ValueError:
        pass
    else:
        raise ValueError('An adoption gate unexpectedly accepted raw diagnostic data')
    view = copy.deepcopy(summaries)
    for summary in view:
        summary['status'] = 'COMPLETED_ONE_PUBLIC_SYNTHETIC_FORK'
    return evaluate(view)


def delta(call, metric):
    """Keep unavailable counters unavailable; monotonic counters cannot decrease."""
    start, end = call.get(metric + 'Start'), call.get(metric + 'End')
    if start is None or end is None:
        return None
    if any(isinstance(value, bool) or not isinstance(value, int) or value < 0 for value in (start, end)) or end < start:
        raise ValueError('Invalid diagnostic counter: ' + metric)
    return end - start


def parse_vm_log(text):
    """Use absolute timenanos for logged pauses; retain paired JVM-clock offsets."""
    events, offsets = [], []
    for line in text.splitlines():
        decorations = re.findall(r'\[([^]]+)\]', line)
        nanos = [int(v[:-2]) for d in decorations if re.fullmatch(r'-?\d+ns', v := d.strip())]
        if len(nanos) < 2:
            continue
        timestamp, uptime = nanos[:2]  # JVM decoration order: timenanos, uptimenanos.
        offsets.append(timestamp - uptime)
        duration = None
        if any(d.strip() == 'gc' for d in decorations) and 'Pause' in line:
            match = re.search(r'(\d+(?:\.\d+)?)ms\s*$', line)
            if match:
                duration = round(float(match.group(1)) * 1_000_000)
        elif any(d.strip() == 'safepoint' for d in decorations):
            match = re.search(r'Total:\s*(\d+)\s*ns', line)
            if match:
                duration = int(match.group(1))
        if duration is not None:
            events.append({'startNanos': timestamp - duration, 'endNanos': timestamp,
                           'kind': 'gc' if any(d.strip() == 'gc' for d in decorations) else 'safepoint',
                           'log': line})
    if not offsets:
        raise ValueError('VM log lacks paired timenanos/uptimenanos decorations')
    return events, (min(offsets), max(offsets))


def parse_compilations(path):
    """Read completed compiler tasks; unfinished tasks remain counted separately."""
    tasks, incomplete = [], 0
    for task in ET.parse(path).getroot().iter('task'):
        done = task.find('task_done')
        if done is None or 'stamp' not in task.attrib or 'stamp' not in done.attrib:
            incomplete += 1
            continue
        tasks.append({'startUptimeNanos': round(float(task.attrib['stamp']) * 1e9),
                      'endUptimeNanos': round(float(done.attrib['stamp']) * 1e9),
                      'method': task.get('method'), 'success': done.get('success')})
    return tasks, incomplete


def overlaps(start, end, left, right):
    return start <= right and left <= end


def analyze(root, comparison):
    summaries = [json.loads((root / f'fork-{i}/summary.json').read_text()) for i in range(3)]
    statistics = wall_statistics(summaries, comparison)
    groups, tails, counts = {}, [], []
    metrics = ('threadCpuNanos', 'threadAllocatedBytes', 'processCpuNanos', 'gcCount',
               'gcTimeMillis', 'compilationTimeMillis')
    for fork, summary in enumerate(summaries):
        calls = [json.loads(line) for line in (root / f'fork-{fork}/calls.jsonl').read_text().splitlines()]
        if len(calls) != 512 or any(c.get('status') != 'completed' or c.get('schema') != 1 for c in calls):
            raise ValueError('Every warmup/measured call must be retained and complete')
        measured = [c for c in calls if c.get('phase') == 'measured']
        if len(measured) != 320 or sum(c.get('phase') == 'warmup' for c in calls) != 192:
            raise ValueError('Diagnostic phase counts differ from the fixed protocol')
        identities = [(c['scene'], c['sourceKind'], c['pair'], c['policy'], c['phase']) for c in calls]
        if len(set(identities)) != len(calls):
            raise ValueError('Duplicate diagnostic call identity')
        policies = ('IMMEDIATE_V1', 'PROTECTED_SECANT3_V1') if comparison == 'normal' else (
            summary['baselineTrackerPolicyId'], summary['candidateTrackerPolicyId'])
        expected = [(scene, kind, pair, policy, phase) for scene, kind in GROUPS
                    for phase, size in [('warmup', 12), ('measured', 20)] for pair in range(size)
                    for policy in (policies[::-1] if (pair + fork) & 1 else policies)]
        if identities != expected:
            raise ValueError('Diagnostic identities do not cover the exact fixed protocol')
        previous_end = None
        for call in calls:
            start, end = call['timingStartMonotonicNanos'], call['timingEndMonotonicNanos']
            order = 'candidate-first' if (call['pair'] + fork) & 1 else 'baseline-first'
            if call.get('executionOrder') != order or (previous_end is not None and start < previous_end):
                raise ValueError('Diagnostic execution order or monotonic intervals changed')
            previous_end = end
        lookup = {(c['scene'], c['sourceKind'], c['pair'], c['policy']): c for c in measured}
        events, offset = parse_vm_log((root / f'fork-{fork}.jvm-gc-safepoint.log').read_text())
        compilations, incomplete = parse_compilations(root / f'fork-{fork}.compilation.xml')
        counts.append({'fork': fork, 'calls': len(calls), 'measured': len(measured),
                       'loggedPauseEvents': len(events), 'compilerTasks': len(compilations),
                       'incompleteCompilerTasks': incomplete, 'observedJvmClockOffsetRangeNanos': offset})
        for row in summary['rows']:
            key = (row['scene'], row['sourceKind'], row['pair'], row['policy'])
            call = lookup[key]
            start, end = call['timingStartMonotonicNanos'], call['timingEndMonotonicNanos']
            if end - start != call['elapsedNanos'] or call['elapsedNanos'] != row['elapsedNanos']:
                raise ValueError('Probe interval does not match the unchanged wall measurement')
            record = {'fork': fork, 'scene': row['scene'], 'sourceKind': row['sourceKind'],
                      'pair': row['pair'], 'policy': row['policy'], 'elapsedNanos': row['elapsedNanos'],
                      **{metric + 'Delta': delta(call, metric) for metric in metrics},
                      'heapUsedBytesStart': call.get('heapUsedBytesStart'),
                      'heapCommittedBytesStart': call.get('heapCommittedBytesStart')}
            record['loggedPauseOverlaps'] = [e for e in events if overlaps(start, end, e['startNanos'], e['endNanos'])]
            # JIT alignment is approximate, unlike absolute GC timenanos; retain offset spread.
            record['approxCompilerOverlaps'] = [t for t in compilations if overlaps(
                start, end, t['startUptimeNanos'] + offset[0], t['endUptimeNanos'] + offset[1])]
            group = row['scene'] + '/' + row['sourceKind']
            groups.setdefault(group, {}).setdefault(row['policy'], []).append(record)
        baseline, candidate = ('IMMEDIATE_V1', 'PROTECTED_SECANT3_V1') if comparison == 'normal' else (
            summary['baselineTrackerPolicyId'], summary['candidateTrackerPolicyId'])
        for group, policies in groups.items():
            own = {policy: {r['pair']: r for r in values if r['fork'] == fork} for policy, values in policies.items()}
            for pair in range(20):
                left, right = own[baseline][pair], own[candidate][pair]
                tails.append({'group': group, 'fork': fork, 'pair': pair,
                              'wallDeltaNanos': right['elapsedNanos'] - left['elapsedNanos'],
                              'baseline': left, 'candidate': right})
    all_pairs = root / 'diagnostic-pairs.jsonl'
    with all_pairs.open('x') as stream:
        for pair in tails:
            stream.write(json.dumps(pair) + '\n')
    medians = {}
    for group, policies in groups.items():
        medians[group] = {}
        for policy, calls in policies.items():
            medians[group][policy] = {'calls': len(calls), **{
                metric + 'DeltaMedian': median(values) if (values := [r[metric + 'Delta'] for r in calls
                                                                     if r[metric + 'Delta'] is not None]) else None
                for metric in metrics}, **{metric + 'AvailableCalls': sum(
                    r[metric + 'Delta'] is not None for r in calls) for metric in metrics}}
    return {'schema': 1, 'status': 'DIAGNOSTIC_ONLY', 'adoptionAllowed': False,
            'comparison': comparison, 'wallClockStatistics': statistics, 'forks': counts,
            'counterMedians': medians, 'largestPairedWallDeltas': sorted(tails,
                key=lambda row: row['wallDeltaNanos'], reverse=True)[:12],
            'allMeasuredPairsFile': all_pairs.name,
            'limitations': ['All pauses remain in wall measurements; CPU is attribution only.',
                           'Counters surround timing and include bounded probe overhead.',
                           'Logged pause spans use emitted end timestamps and rounded durations.',
                           'JIT clock alignment is approximate; observed offset range is retained.']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--comparison', required=True, choices=('normal', 'tracker'))
    parser.add_argument('--root', required=True, type=Path)
    parser.add_argument('--report', required=True, type=Path)
    args = parser.parse_args()
    try:
        report = analyze(args.root, args.comparison)
    except (ValueError, OSError, KeyError, TypeError, ET.ParseError) as error:
        report = {'status': 'DIAGNOSTIC_INVALID', 'adoptionAllowed': False, 'error': str(error)}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    with args.report.open('x') as stream:
        stream.write(json.dumps(report, indent=2) + '\n')
    print('Legacy diagnostic capture:', report['status'], '(never qualification)')
    raise SystemExit(0 if report['status'] == 'DIAGNOSTIC_ONLY' else 1)


if __name__ == '__main__':
    main()
