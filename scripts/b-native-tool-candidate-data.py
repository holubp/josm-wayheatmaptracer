"""Fixed PUBLIC native-tool candidate metadata, without target execution.

Reuse the immutable reviewed collector's bounded readers, guards, output and
cleanup. Add only eight literal JDK resource candidates. New ELF dependency
names are reported against already observed files, never searched or acquired.
This module cannot approve requiredness, installed payloads or any runtime.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
from time import monotonic
from types import SimpleNamespace

SOURCE_SHA256 = '05c212cdb5d0eb7688a576821eb37805ed303de744fda99f9547dee41bbb9c4b'
JDK = Path('/usr/lib/jvm/temurin-17-jdk-amd64')


def _dependency():
    path = Path(__file__).with_name('b-tool-acquisition-data.py')
    data = path.read_bytes()
    if len(data) > 65536 or hashlib.sha256(data).hexdigest() != SOURCE_SHA256:
        raise ValueError('NATIVE_CANDIDATE_DEPENDENCY_DRIFT')
    module = {'__name__': 'native_candidate_dependency', '__file__': str(path)}
    exec(compile(data, str(path), 'exec'), module)
    return SimpleNamespace(**module)


def fixed_candidates(jdk):
    """Return the literal candidate roles, paths and ELF-reader eligibility.

    Args:
        jdk: The fixed installed projector root, or an isolated UNIT root.

    Returns:
        Eight immutable role/path/ELF tuples. Requiredness remains unproved.

    Raises:
        ValueError: If the supplied root is not an absolute Path.
    """
    if not isinstance(jdk, Path) or not jdk.is_absolute():
        raise ValueError('NATIVE_CANDIDATE_JDK_ROOT')
    return tuple((role, jdk / path, elf) for role, path, elf in (
        ('projector-jli', 'lib/libjli.so', True),
        ('projector-vm', 'lib/server/libjvm.so', True),
        ('projector-image-reader', 'lib/libjimage.so', True),
        ('projector-java-runtime', 'lib/libjava.so', True),
        ('projector-zip-runtime', 'lib/libzip.so', True),
        ('projector-vm-config', 'lib/jvm.cfg', False),
        ('projector-default-cds', 'lib/server/classes.jsa', False),
        ('projector-default-cds-no-coops', 'lib/server/classes_nocoops.jsa', False),
    ))


def _extra(session, module, candidates):
    roles = {}
    session.missing.add('native-candidate-required-role-policy')
    for role, path, elf in candidates:
        session.check()
        try:
            path.lstat()
        except FileNotFoundError:
            roles[role] = {'availability': 'ABSENT_CANDIDATE', 'requiredness_established': False}
            continue
        row = session.file(path, role)
        roles[role] = {'availability': 'OBSERVED_CANDIDATE', 'requiredness_established': False,
                       'canonical': row['canonical'], 'bytes': row['bytes'], 'sha256': row['sha256']}
        row['package'] = session.provenance(row)
        if elf:
            row['elf'] = {'availability': 'UNAVAILABLE', 'cause': 'ELF_METADATA_NOT_COMPLETED'}
            row['elf'] = module._elf(session.query('elf',
                ('--wide', '--file-header', '--program-headers', '--dynamic', row['canonical'])))
    session.emit('reports/native-candidate-roles.json', roles)
    # Only actual already observed aliases/canonical names are comparison
    # candidates. This is not loader selection, RPATH evaluation or discovery.
    observed = {}
    for row in session.files.values():
        session.check()
        for name in (row['canonical'], *(item['requested'] for item in row['observations'])):
            observed.setdefault(Path(name).name, set()).add(row['canonical'])
    graph = []
    for row in session.files.values():
        session.check()
        info = row.get('elf', {})
        names = [('needed', name) for name in info.get('needed', ())]
        if info.get('interpreter'):
            names.append(('interpreter', info['interpreter']))
        for kind, name in names:
            module.require(len(graph) < module.LIMITS['edges'], 'GRAPH_EDGE_LIMIT')
            found = observed.get(Path(name).name, set())
            if kind == 'interpreter':
                found = {target for target in found if target == name or any(
                    item['requested'] == name for item in session.files[target]['observations'])}
            graph.append({'from': row['canonical'], 'kind': kind, 'name': name,
                          'availability': 'OBSERVED_CANDIDATE_ONLY' if len(found) == 1 else 'UNRESOLVED_NOT_ACQUIRED',
                          'canonical': next(iter(found)) if len(found) == 1 else None,
                          'scope': 'NOT_LOADER_OR_REQUIREDNESS_AUTHORITY'})
    session.emit('reports/native-candidate-graph.json', graph)


def _acquire(output, policy, candidates, cancel=lambda: None, clock=monotonic, *, started=None):
    """Private UNIT/DATA engine; no caller fixture can issue qualification."""
    if started is None:
        started = clock()
    module = _dependency()
    session = module._Session(output, policy, cancel, clock)
    session.clock.deadline = started + 600
    cause = None
    cleanup = 'COMPLETE'
    snapshots = 0
    try:
        module._run(session)
        _extra(session, module, candidates)
    except BaseException as error:
        cause = module._failure_cause(session, error)
    finally:
        try:
            for index, row in enumerate(session.files.values(), 1):
                record = dict(row)
                record.setdefault('package', {'availability': 'NOT_QUERIED'})
                record.setdefault('elf', {'availability': 'NOT_QUERIED'})
                session.emit('reports/final-files/file-%02d.json' % index, {
                    'schema': 'TOOL_FILE_METADATA_SNAPSHOT_1',
                    'initial_report': 'reports/files/file-%02d.json' % index
                        if row['canonical'] in session.initial_reports else None,
                    'initial_report_availability': 'COMPLETE'
                        if row['canonical'] in session.initial_reports else 'UNAVAILABLE',
                    'record': record})
                snapshots += 1
        except BaseException as error:
            if cause is None:
                cause = module._failure_cause(session, error)
        if session.stage is not None:
            try:
                module._live(session.stage_owners)
                session.base['_remove'](session.stage)
                session.stage = None
                session.stage_owners = None
            except BaseException:
                cleanup = 'OWNERSHIP_OR_CLEANUP_UNAVAILABLE'
    status = {'schema': 'TOOL_NATIVE_CANDIDATE_DATA_1', 'scope': policy['scope'],
              'qualified': False, 'cases': 0, 'candidateExecutions': 0, 'targetExecutions': 0,
              'metadataQueries': session.queries,
              'metadataExecutions': session.executions if session.execution_available else None,
              'files': len(session.files), 'finalFileSnapshots': snapshots,
              'hashed_bytes': session.hash_bytes, 'cleanup': cleanup, 'cause': cause,
              'outcome': 'PARTIAL_UNSEALED' if cause is None and cleanup == 'COMPLETE' else 'PARTIAL_FAILURE',
              'missing': sorted(session.missing)}
    try:
        module._finish(session, status)
    except BaseException:
        module._deadline(session, status)
        status.update(outcome='PARTIAL_OUTPUT_UNAVAILABLE', evidence='UNAVAILABLE')
        if status['cause'] != 'COLLECTOR_DEADLINE':
            status['cause'] = 'OUTPUT_OWNER_OR_LIMIT'
    return status


def collect(output):
    """Collect fixed PUBLIC candidate metadata into a fresh owned output.

    Args:
        output: Fresh evidence directory; no existing directory is replaced.

    Returns:
        Safe, explicitly unqualified status. Only metadata readers execute.
        Missing candidate files remain observations, not requiredness decisions.
    """
    started = monotonic()
    try:
        module = _dependency()
        policy = dict(module.POLICY)
        policy['runner'] = {key: os.environ[key] for key in ('ImageOS', 'ImageVersion', 'ImageRelease')
                            if key in os.environ}
        return _acquire(output, policy, fixed_candidates(JDK), started=started)
    except BaseException:
        return {'schema': 'TOOL_NATIVE_CANDIDATE_DATA_1', 'scope': 'PUBLIC_METADATA_DATA',
                'qualified': False, 'cases': 0, 'candidateExecutions': 0, 'targetExecutions': 0,
                'outcome': 'PARTIAL_DEPENDENCY_UNAVAILABLE', 'cause': 'COLLECTOR_INITIALIZATION_UNAVAILABLE',
                'evidence': 'NOT_CREATED'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    result = collect(parser.parse_args().output)
    print(json.dumps(result, sort_keys=True))
    raise SystemExit(0 if result['outcome'] == 'PARTIAL_UNSEALED' else 2)
