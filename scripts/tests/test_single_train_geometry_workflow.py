"""Fixed single-TRAIN routing and freeze guards; no empirical process is launched."""
from pathlib import Path
import itertools
import hashlib
import json
import os
import subprocess
import sys
import copy
import yaml
import pytest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / '.github/workflows/observation-single-train-geometry.yml'


def test_new_fixed_diagnostic_route_exists_and_cannot_fall_through_to_old_experiments():
    assert WORKFLOW.exists(), 'fixed manual-only workflow missing'
    build = yaml.load((ROOT / '.github/workflows/build.yml').read_text(), Loader=yaml.BaseLoader)
    choice = build['on']['workflow_dispatch']['inputs']['observation_protocol']
    assert choice['default'] == 'legacy'
    assert 'single-train-geometry' in choice['options']
    jobs = build['jobs']
    assert jobs['observation-single-train-geometry']['if'] == "github.event_name == 'workflow_dispatch' && inputs.observation_protocol == 'single-train-geometry'"
    for name in ('observation-identification', 'observation-public-diagnostics', 'observation-train-runtime', 'observation-train-runtime-optimized', 'observation-prospective-fit'):
        assert "inputs.observation_protocol != 'single-train-geometry'" in jobs[name]['if']


def test_new_route_is_manual_bounded_and_uses_only_explicit_upload_paths():
    assert WORKFLOW.exists(), 'fixed manual-only workflow missing'
    workflow = yaml.load(WORKFLOW.read_text(), Loader=yaml.BaseLoader)
    assert set(workflow['on']) == {'workflow_call'}
    job = workflow['jobs']['observation-single-train-geometry']
    assert job['if'] == "github.event_name == 'workflow_dispatch'"
    assert job['timeout-minutes'] == '15'
    steps = job['steps']
    commands = '\n'.join(s.get('run', '') for s in steps)
    assert 'timeout 360s' in commands
    assert 'v022SingleTrainGeometryDiagnostic' in commands
    assert commands.index('sha256') < commands.index('compileObservationIdentifyJava')
    assert 'gh workflow' not in commands
    upload = next(s for s in steps if s.get('uses', '').startswith('actions/upload-artifact@'))
    paths = upload['with']['path'].splitlines()
    assert len(paths) == 24 and len(set(paths)) == 24
    assert all('*' not in p and '/scratch' not in p and '..' not in p for p in paths)


def _snippet(marker):
    workflow = yaml.load(WORKFLOW.read_text(), Loader=yaml.BaseLoader)
    runs = [s.get('run', '') for s in workflow['jobs']['observation-single-train-geometry']['steps']]
    run = next(r for r in runs if marker in r)
    return run.split("python3 - <<'PY'\n", 1)[1].split('\nPY', 1)[0]


def _run(script, root, optimized, **environment):
    return subprocess.run([sys.executable, *(['-O'] if optimized else []), '-c', script], cwd=root,
                          env={**os.environ, **environment}, capture_output=True, text=True)


@pytest.mark.parametrize('optimized', [False, True])
def test_actual_precompile_snippet_refuses_pending_tamper_and_wrong_purpose(tmp_path, optimized):
    source = tmp_path / 'src/C.java'
    source.parent.mkdir()
    source.write_text('class C {}\n')
    freeze = tmp_path / 'superpowers/docs/plans/2026-10-18-b-single-train-geometry-freeze.json'
    freeze.parent.mkdir(parents=True)
    fields = {'schema': 1, 'purpose': 'SINGLE_ORIGINAL_TRAIN_GEOMETRY_DIAGNOSTIC',
              'approval': 'APPROVED_AFTER_INDEPENDENT_REVIEW_AND_CONTROLLER_RULING',
              'members': {'src/C.java': hashlib.sha256(source.read_bytes()).hexdigest()}}
    def write(value):
        freeze.write_text(json.dumps(value, sort_keys=True, separators=(',', ':')) + '\n')
        return hashlib.sha256(freeze.read_bytes()).hexdigest()
    expected = write(fields)
    script = _snippet('freeze=Path')
    assert _run(script, tmp_path, optimized, SINGLE_TRAIN_FREEZE_SHA256=expected).returncode == 0
    for key, value in [('approval', 'PENDING_INDEPENDENT_APPROVAL'), ('purpose', 'PROSPECTIVE_FIT'), ('members', {})]:
        bad = copy.deepcopy(fields); bad[key] = value
        assert _run(script, tmp_path, optimized, SINGLE_TRAIN_FREEZE_SHA256=write(bad)).returncode != 0
    write(fields); source.write_text('class Mutated {}\n')
    assert _run(script, tmp_path, optimized, SINGLE_TRAIN_FREEZE_SHA256=expected).returncode != 0
    joint = copy.deepcopy(fields); joint['members']['src/C.java'] = hashlib.sha256(source.read_bytes()).hexdigest()
    write(joint)
    assert _run(script, tmp_path, optimized, SINGLE_TRAIN_FREEZE_SHA256=expected).returncode != 0


@pytest.mark.parametrize('optimized', [False, True])
def test_actual_terminal_gate_is_explicit_fail_closed_under_optimization(tmp_path, optimized):
    root = tmp_path / 'josm-single-train-geometry'; root.mkdir()
    purpose = 'SINGLE_ORIGINAL_TRAIN_GEOMETRY_DIAGNOSTIC'
    digest = 'a' * 64
    receipts = {
        'status': {'purpose': purpose, 'status': 'EXHAUSTIVE_ABSENCE', 'rasterReads': 1, 'labelReads': 1,
                   'selectReads': 0, 'holdoutReads': 0, 'ownerBytesAfterClose': 0, 'selectionAuthority': 'NONE',
                   'peakAccountedBytes': 1234, 'uncertainOrdinaryReservationBytes': 0, 'diskChargeBeforeStatusUpperBound': 400,
                   'phases': {p: {'attempted': 1, 'succeeded': 1, 'failed': 0, 'active': 0}
                              for p in ('ACQUISITION', 'DECODE', 'GEOMETRY', 'SEARCH', 'EXPORT')}},
        'binding': {'purpose': purpose, 'freezeSha256': digest, 'case': 'sparse-crossing-noisy-strands-vs-persistent-parallel/train/v0/right',
                    'sourceProofOnly': '94d7ea9a03134cab331b317a1764b0d517bd2dc1dea6d2a78482bcf8313dc6de', 'selectionAuthority': 'NONE'},
        'paths': {'newMapSolves': 0, 'newLikelihoodScores': 0, 'paths': {'G1-L1-B0': {}, 'G1-L1-B1': {}}},
        'search': {'status': 'EXHAUSTIVE_ABSENCE', 'exhaustive': True},
        'witness': {'states': [], 'points': [], 'actualWholeOraclePassed': False},
    }
    def write(value):
        for name, data in value.items(): (root / (name + '.json')).write_text(json.dumps(data))
    script = _snippet('status=json.loads')
    environment = dict(RUNNER_TEMP=str(tmp_path), SINGLE_TRAIN_FREEZE_SHA256=digest)
    write(receipts)
    assert _run(script, tmp_path, optimized, **environment).returncode == 0
    variants = [('status', 'status', 'INCOMPLETE_UNKNOWN'), ('status', 'rasterReads', 2), ('status', 'ownerBytesAfterClose', 1),
                ('status', 'purpose', 'PROSPECTIVE_FIT'), ('status', 'uncertainOrdinaryReservationBytes', 3),
                ('paths', 'newMapSolves', 1), ('search', 'exhaustive', False), ('binding', 'freezeSha256', 'b' * 64)]
    for name, key, value in variants:
        bad = copy.deepcopy(receipts); bad[name][key] = value; write(bad)
        assert _run(script, tmp_path, optimized, **environment).returncode != 0, (name, key)
    bad = copy.deepcopy(receipts); bad['status']['phases']['SEARCH'] = {'attempted': 1, 'succeeded': 0, 'failed': 1, 'active': 0}; write(bad)
    assert _run(script, tmp_path, optimized, **environment).returncode != 0
    write(receipts); (root / 'witness.json').write_text('{broken')
    assert _run(script, tmp_path, optimized, **environment).returncode != 0
    (root / 'witness.json').unlink()
    assert _run(script, tmp_path, optimized, **environment).returncode != 0


def test_every_new_choice_old_flag_combination_is_exclusive():
    from test_observation_prospective_workflow import _enabled
    build = yaml.load((ROOT / '.github/workflows/build.yml').read_text(), Loader=yaml.BaseLoader)
    for event, runtime, renderer in itertools.product(('workflow_dispatch', 'push', 'pull_request'), (False, True), (False, True)):
        inputs = {'observation_protocol': 'single-train-geometry', 'observation_train_runtime': runtime, 'observation_diagnostics': renderer}
        actual = [name for name, job in build['jobs'].items() if name.startswith('observation-') and _enabled(job['if'], event, inputs)]
        assert actual == (['observation-single-train-geometry'] if event == 'workflow_dispatch' else [])


def test_actual_candidate_freeze_is_canonical_complete_and_externally_pinned():
    workflow = yaml.load(WORKFLOW.read_text(), Loader=yaml.BaseLoader)
    job = workflow['jobs']['observation-single-train-geometry']
    freeze = (ROOT / 'superpowers/docs/plans/2026-10-18-b-single-train-geometry-freeze.json').read_bytes()
    assert job['env']['SINGLE_TRAIN_FREEZE_SHA256'] == hashlib.sha256(freeze).hexdigest()
    fields = json.loads(freeze)
    assert freeze == (json.dumps(fields, sort_keys=True, separators=(',', ':')) + '\n').encode()
    assert fields['approval'] in ('PENDING_INDEPENDENT_APPROVAL', 'APPROVED_AFTER_INDEPENDENT_REVIEW_AND_CONTROLLER_RULING')
    assert fields['purpose'] == 'SINGLE_ORIGINAL_TRAIN_GEOMETRY_DIAGNOSTIC'
    assert fields['mapSolves'] == 0 and fields['materializations'] == 1
    assert len(fields['members']) == 48
    assert all(hashlib.sha256((ROOT / p).read_bytes()).hexdigest() == h for p, h in fields['members'].items())
    assert '.github/workflows/observation-single-train-geometry.yml' not in fields['members'], 'external trust anchor cannot recursively hash its own literal'
    assert all('BSingleTrain' + suffix + '.java' in '\n'.join(fields['members']) for suffix in (
        'DiagnosticBridge', 'DiagnosticManifest', 'GeometryDiagnosticTool', 'GeometryReachability'))
