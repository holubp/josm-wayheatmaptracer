"""Diagnostic CI must retain release checks and never claim qualification."""
from pathlib import Path
import subprocess

import yaml

ROOT = Path(__file__).resolve().parents[2]
BASE = '71d1228b809e80d75eea69d7222dc0f39e3ac8c1'
BRANCH = 'codex/legacy-perf-diagnostics-v4'


def workflow(name):
    return yaml.load((ROOT / '.github/workflows' / name).read_text(), Loader=yaml.BaseLoader)


def test_diagnostic_branch_does_not_modify_release_job_graph():
    baseline = yaml.load(subprocess.check_output(
        ['git', '-c', f'safe.directory={ROOT}', '-C', str(ROOT), 'show',
         BASE + ':.github/workflows/build.yml'], text=True), Loader=yaml.BaseLoader)
    current = workflow('build.yml')
    assert current['jobs'] == baseline['jobs']
    assert current['permissions'] == baseline['permissions']
    assert current['on']['pull_request'] == baseline['on']['pull_request']
    assert current['on']['push']['tags'] == baseline['on']['push']['tags']
    assert current['on']['push']['branches-ignore'] == [BRANCH]
    assert 'branches' not in current['on']['push']


def test_diagnostic_cohort_is_explicit_and_complete():
    definition = workflow('legacy-performance-diagnostics.yml')
    assert 'not qualification' in definition['name']
    assert definition['on'] == {'push': {'branches': [BRANCH]}}
    assert definition['permissions'] == {'contents': 'read'}
    job = definition['jobs']['diagnostic-cohort']
    assert job['strategy']['matrix']['comparison'] == ['normal', 'tracker']
    assert job['strategy']['fail-fast'] == 'false'
    assert job['timeout-minutes'] == '30'
    steps = job['steps']
    source = next(s for s in steps if s.get('name') == 'Verify production code is unchanged')
    assert BASE in source['run'] and '-- src/main/java' in source['run']
    run = next(s for s in steps if s.get('name') == 'Run the fixed diagnostic cohort')
    assert 'for fork in 0 1 2' in run['run']
    assert '-PlegacyNormalPerformanceDiagnostics=true' in run['run']
    assert '-PlegacyNormalPerformanceFork="$fork"' in run['run']
    assert 'legacyNormalPerformancePreflight' not in run['run']
    assert 'native-reciprocal-support-paired-chord-v4' in run['run']
    assert 'exit "$failed"' in run['run']
    analysis = next(s for s in steps if s.get('name') == 'Analyze without granting qualification')
    assert analysis['if'] == 'always()'
    assert 'analyze-legacy-performance-diagnostics.py' in analysis['run']
    upload = next(s for s in steps if s.get('uses') == 'actions/upload-artifact@v4')
    assert upload['if'] == 'always()'
    assert upload['with']['name'] == 'legacy-performance-diagnostics-${{ matrix.comparison }}'
