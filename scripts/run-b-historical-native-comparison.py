#!/usr/bin/env python3
"""Prepare neutral public bytes and run isolated historical CAPTURE_ONLY recapture.

This launcher has no engine mode. A separately invoked preparation process exits
before the historical JVM starts. Protocol digests supplied locally are integrity
bindings, not reviewed experiment authority. All generated tuples remain labelled
preparation-only until separately reviewed and frozen.

Usage: run this file with --help. No command retries a failed capture.
"""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import struct
import subprocess
import sys
import zipfile

HISTORICAL = "117baaf09f4b81a38ad4679eedc8fd1b12ed3d2a"
NATIVE_SHA = "34c0c300332f3d1eb5701eecf9b980a64c94940188d8fc5e6c221b5e4609639c"
SCOPE = "CAPTURE_ONLY_PREPARATION_NOT_ENGINE_ELIGIBLE"
MAX_ZIP = 4 * 1024 * 1024
# Actual old tree:946 entries/depth9/156 UTF8 bytes; adapter:4/1/29.
# Rounded fixed metadata ceilings bound traversal before any retained path list.
MAX_TREE_ENTRIES = 1024
MAX_TREE_DEPTH = 16
MAX_TREE_PATH_BYTES = 256
IDS = ('strong-parallel-gap', 'connected-straight', 'connected-short-bend', 'connected-sharp-apex',
       'faint-six-metre-bend', 'faint-twelve-metre-bend', 'genuine-junction',
       'positive-twenty-five-representation', 'negative-twenty-five-representation',
       'flecks-versus-faint-coherent', 'valid-sparse-gap', 'invalid-unknown-gap', 'censored-flank',
       'strong-modulated-versus-quiet-faint', 'unknown-detour', 'uniform-null', 'alternating-null',
       'half-pitch-alias', 'quarter-phase-alias', 'reverse-original-mesh', 'thin-three-strands',
       'thin-three-strands-intermittent', 'thin-strands-merge-separate', 'drifting-strong-with-faint-original',
       'thin-strong-sharp-bend', 'thin-faint-coherent-sharp-bend', 'thin-local-overlap-noise',
       'same-trail-three-strong', 'same-trail-five-strong', 'switchback-thin-strong',
       'switchback-thin-faint', 'same-trail-three-separated-strong')
MEMBERS = frozenset({'index.json'} | {case + '/' + leaf for case in IDS
                    for leaf in ('scalar.f64', 'validity.bin', 'descriptor.json')})


def sha(raw):
    """Return SHA256 of bounded caller-owned bytes."""
    return hashlib.sha256(raw).hexdigest()


def canonical(value):
    """Encode finite metadata deterministically, preserving signed-zero values."""
    return (json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False) + '\n').encode()


def bounded(path, limit):
    """Read one nonsymlink regular file with a strict byte ceiling.

    Args:
        path: Exact expected file path.
        limit: Maximum nonempty byte length.
    Returns:
        The bounded bytes.
    Raises:
        ValueError: The file is absent, linked, empty or oversized.
    """
    path = Path(path)
    if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= limit:
        raise ValueError('bounded input file')
    with path.open('rb') as stream:
        raw = stream.read(limit + 1)
    if not 0 < len(raw) <= limit:
        raise ValueError('bounded input file')
    return raw


def metadata(raw, limit=65536, nodes=4096):
    """Parse bounded strict UTF8 JSON, rejecting duplicate keys and expansion."""
    if not 0 < len(raw) <= limit:
        raise ValueError('metadata size')
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError('duplicate metadata key')
            result[key] = value
        return result
    try:
        text = raw.decode('utf-8', 'strict')
        # Bound parser recursion before constructing a generic object tree.
        depth = 0
        quoted = escaped = False
        for char in text:
            if quoted:
                if escaped:
                    escaped = False
                elif char == '\\':
                    escaped = True
                elif char == '"':
                    quoted = False
            elif char == '"':
                quoted = True
            elif char in '[{':
                depth += 1
                if depth > 8:
                    raise ValueError('metadata depth')
            elif char in ']}':
                depth -= 1
        value = json.loads(text, object_pairs_hook=pairs,
                           parse_constant=lambda unused: (_ for _ in ()).throw(ValueError('nonfinite metadata')))
    except (UnicodeError, json.JSONDecodeError, RecursionError) as failure:
        raise ValueError('metadata encoding') from failure
    count = 0
    def visit(item):
        nonlocal count
        count += 1
        if count > nodes:
            raise ValueError('metadata nodes')
        if isinstance(item, dict):
            for key, child in item.items():
                visit(key)
                visit(child)
        elif isinstance(item, list):
            for child in item:
                visit(child)
        elif isinstance(item, str) and len(item) > 256:
            raise ValueError('metadata string')
        elif isinstance(item, (int, float)):
            if len(str(item)) > 64 or not math.isfinite(item):
                raise ValueError('metadata numeric token')
    visit(value)
    if not isinstance(value, dict):
        raise ValueError('metadata object')
    return value


def git(checkout, *args):
    """Read only the selected checkout's git metadata."""
    result = subprocess.run(['git', '-c', 'safe.directory=' + str(Path(checkout).resolve()),
                             '-C', str(checkout), *args], capture_output=True, check=False)
    if result.returncode:
        raise ValueError('historical source git metadata unavailable')
    return result.stdout.decode('utf-8', 'strict').strip()


def verify_checkout(checkout):
    """Require the literal clean historical checkout before compile and after use.

    Args:
        checkout: Historical detached worktree, never the current source tree.
    Returns:
        Exact commit/tree and wrapper/build source hashes.
    Raises:
        ValueError: HEAD, tracked bytes or untracked source overlays differ.
    """
    checkout = Path(checkout).resolve()
    if git(checkout, 'rev-parse', 'HEAD') != HISTORICAL or git(checkout, 'status', '--porcelain', '--untracked-files=all'):
        raise ValueError('historical source must be literal clean117baaf')
    files = ['gradlew', 'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties',
             'build.gradle.kts', 'gradle.properties']
    return {'commit': HISTORICAL, 'tree': git(checkout, 'rev-parse', 'HEAD^{tree}'),
            'buildFiles': {name: sha(bounded(checkout / name, 2 * 1024 * 1024)) for name in files}}


def tree_sha(directory):
    """Hash a tree after bounding every visited entry before path retention."""
    directory = Path(directory).resolve()
    files = []
    entries = 0
    total = 0
    def scan(parent, depth):
        nonlocal entries, total
        with os.scandir(parent) as children:
            for entry in children:
                entries += 1
                if entries > MAX_TREE_ENTRIES:
                    raise ValueError('classpath tree entry bound')
                if depth > MAX_TREE_DEPTH:
                    raise ValueError('classpath tree depth')
                path = Path(entry.path)
                if len(path.relative_to(directory).as_posix().encode('utf-8', 'strict')) > MAX_TREE_PATH_BYTES:
                    raise ValueError('classpath tree path bytes')
                if entry.is_symlink():
                    raise ValueError('classpath tree link')
                if entry.is_dir(follow_symlinks=False):
                    scan(path, depth + 1)
                elif entry.is_file(follow_symlinks=False):
                    size = entry.stat(follow_symlinks=False).st_size
                    total += size
                    if not 0 < size <= 2 * 1024 * 1024 or total > 64 * 1024 * 1024:
                        raise ValueError('classpath tree bytes')
                    files.append(path)
                else:
                    raise ValueError('classpath tree regular files')
    scan(directory, 1)
    if not files:
        raise ValueError('classpath tree inventory')
    digest = hashlib.sha256()
    for path in sorted(files):
        raw = bounded(path, 2 * 1024 * 1024)
        digest.update(path.relative_to(directory).as_posix().encode() + b'\0' + sha(raw).encode() + b'\n')
    return digest.hexdigest()


def verify_classpath(checkout, entries):
    """Require only old main output/resources plus its declared JOSM19555 JAR.

    Args:
        checkout: Literal old checkout.
        entries: Old class/resource directories and one declared JOSM dependency.
    Returns:
        Independently hashed exact runtime entries.
    Raises:
        ValueError: Current/test classes, overlays or unknown dependencies occur.
    """
    checkout = Path(checkout).resolve()
    main = checkout / 'build/classes/java/main'
    resources = checkout / 'build/resources/main'
    resolved = [Path(path).resolve() for path in entries]
    allowed_dirs = {main} | ({resources} if resources.is_dir() else set())
    if len(set(resolved)) != len(resolved) or set(path for path in resolved if path.is_dir()) != allowed_dirs:
        raise ValueError('historical classpath directories')
    jars = [path for path in resolved if path.is_file()]
    if len(jars) != 1 or jars[0].name != 'josm-19555.jar' or len(resolved) != len(allowed_dirs) + 1:
        raise ValueError('historical classpath dependencies')
    with zipfile.ZipFile(jars[0]) as archive:
        if 'org/openstreetmap/josm/data/Version.class' not in archive.namelist() or any(
                name.startswith('org/openstreetmap/josm/plugins/wayheatmaptracer/') for name in archive.namelist()):
            raise ValueError('historical classpath dependency contains plugin overlay')
    return {'mainClasses': str(main), 'mainSha256': tree_sha(main),
            'resources': str(resources) if resources.is_dir() else '',
            'resourcesSha256': tree_sha(resources) if resources.is_dir() else '',
            'dependencies': [{'path': str(jars[0]), 'sha256': sha(bounded(jars[0], 64 * 1024 * 1024))}],
            'runtimeEntries': [str(main)] + ([str(resources)] if resources.is_dir() else []) + [str(jars[0])]}


def cap(name):
    """Return the exact neutral member ceiling."""
    return 65536 if name == 'index.json' else 52488 if name.endswith('/scalar.f64') else 6561 if name.endswith('/validity.bin') else 8192


def archive_entries(raw):
    """Authenticate closed STORED inventory and bound every neutral payload."""
    import io
    if not 0 < len(raw) <= MAX_ZIP:
        raise ValueError('archive size')
    # Bound zipfile's own directory allocation before it parses member objects.
    if len(raw) < 22 or raw[-22:-18] != b'PK\x05\x06':
        raise ValueError('archive exact directory footer')
    footer = struct.unpack('<4s4H2LH', raw[-22:])
    if footer[1:5] != (0, 0, 97, 97) or footer[7] != 0 or footer[5] + footer[6] != len(raw) - 22:
        raise ValueError('archive exact97 directory')
    result = {}
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        for entry in archive.infolist():
            name = entry.filename
            if name not in MEMBERS or name in result or entry.compress_type != zipfile.ZIP_STORED or not 0 < entry.file_size <= cap(name):
                raise ValueError('archive exact member inventory/size')
            if (entry.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError('archive symlink')
            result[name] = archive.read(entry)
    if set(result) != MEMBERS:
        raise ValueError('archive exact97 inventory')
    index = metadata(result['index.json'])
    if set(index) != {'schema', 'cases', 'members', 'captures', 'preparation'} or index['schema'] != 'neutral-native-public32-v1' or index['cases'] != list(IDS):
        raise ValueError('archive index schema')
    if set(index['members']) != MEMBERS - {'index.json'} or set(index['captures']) != set(IDS):
        raise ValueError('archive index inventory')
    for name, item in index['members'].items():
        if item != {'bytes': len(result[name]), 'sha256': sha(result[name])}:
            raise ValueError('archive member digest')
    for case in IDS:
        values = result[case + '/scalar.f64']
        mask = result[case + '/validity.bin']
        if len(values) != 52488 or len(mask) != 6561 or any(value not in (0, 1) for value in mask):
            raise ValueError('archive scalar/mask')
        for (value,) in struct.iter_unpack('>d', values):
            if not 0 <= value <= 1:
                raise ValueError('archive nonfinite scalar')
        descriptor = metadata(result[case + '/descriptor.json'], 8192, 256)
        if set(descriptor) != {'schema', 'id', 'width', 'height', 'origin', 'pitchMeters', 'source', 'frame', 'outputGrid', 'capture', 'inputRasterOrigin'} or descriptor['id'] != case:
            raise ValueError('archive descriptor schema')
    return result, index


def make_protocol(archive, native_contract, output):
    """Produce integrity metadata only; no approval or engine eligibility.

    Args:
        archive: Existing preparation-only public source archive.
        native_contract: Untouched independently pinned native input contract.
        output: New protocol file.
    Returns:
        Protocol SHA256 and detached bounded data tuple.
    Raises:
        ValueError: Factual input/capture identities differ from frozen controls.
    """
    raw = bounded(archive, MAX_ZIP)
    entries, index = archive_entries(raw)
    contract_raw = bounded(native_contract, 65536)
    if sha(contract_raw) != NATIVE_SHA:
        raise ValueError('native frozen contract digest')
    contract = metadata(contract_raw)
    if contract['cases'] != list(IDS):
        raise ValueError('native frozen case inventory')
    for case in IDS:
        original, captured = contract['nativeInputs'][case], index['captures'][case]
        if sha(entries[case + '/scalar.f64']) != original['rasterSha256'] or sha(entries[case + '/validity.bin']) != original['validitySha256'] or captured['snapshotHash'] != original['snapshotHash'] or (captured['width'], captured['height']) != (original['outputWidth'], original['outputHeight']):
            raise ValueError('native frozen source/capture changed')
    protocol = {'scope': SCOPE, 'historicalCommit': HISTORICAL, 'archiveSha256': sha(raw),
                'indexSha256': sha(entries['index.json']), 'nativeContractSha256': NATIVE_SHA,
                'cases': list(IDS), 'members': index['members'], 'captures': index['captures'],
                'sourceCommit': '5b9abca91f207ab50736a6e506fd7a616881d20c'}
    encoded = canonical(protocol)
    if len(encoded) > 65536:
        raise ValueError('protocol size')
    Path(output).write_bytes(encoded)
    return sha(encoded), protocol


def prepare(archive, expected_sha, destination, protocol, protocol_sha):
    """Extract a pinned closed archive in a process that precedes old Java.

    Args:
        archive: Neutral preparation archive, never read by the old JVM.
        expected_sha: Independently supplied exact archive digest.
        destination: New staging directory with exactly97 regular files.
        protocol: Detached protocol object.
        protocol_sha: Independently supplied canonical protocol digest.
    Returns:
        Actual bounded archive/index bindings.
    Raises:
        ValueError: A digest, inventory, schema or member changes.
    """
    raw = bounded(archive, MAX_ZIP)
    if sha(raw) != expected_sha:
        raise ValueError('external archive digest')
    if sha(canonical(protocol)) != protocol_sha:
        raise ValueError('external protocol digest')
    if protocol.get('scope') != SCOPE or protocol.get('historicalCommit') != HISTORICAL or protocol.get('archiveSha256') != expected_sha:
        raise ValueError('protocol purpose/source')
    entries, index = archive_entries(raw)
    if sha(entries['index.json']) != protocol.get('indexSha256') or index['members'] != protocol.get('members') or index['captures'] != protocol.get('captures'):
        raise ValueError('external protocol/index tuple')
    destination = Path(destination)
    destination.mkdir()
    for name, data in entries.items():
        target = destination / name
        target.parent.mkdir(exist_ok=True)
        with target.open('xb') as stream:
            stream.write(data)
    return {'scope': SCOPE, 'archiveSha256': expected_sha, 'indexSha256': protocol['indexSha256'], 'files': 97}


def compile_adapter(checkout, dependency, adapter_source, directory):
    """Compile the external capture adapter against attested old main/JARs.

    Args:
        checkout: Clean literal historical worktree, already main-compiled.
        dependency: Its declared JOSM19555 JAR.
        adapter_source: External source file; no current test/helper imports.
        directory: New external class-output directory.
    Returns:
        Actual source/class-directory/dependency integrity attestation.
    Raises:
        ValueError: Source or classpath isolation fails.
        subprocess.CalledProcessError: Historical-classpath javac fails.
    """
    identity = verify_checkout(checkout)
    entries = [Path(checkout) / 'build/classes/java/main']
    resources = Path(checkout) / 'build/resources/main'
    if resources.is_dir():
        entries.append(resources)
    entries.append(Path(dependency))
    attestation = verify_classpath(checkout, entries)
    directory = Path(directory).resolve()
    directory.mkdir()
    java_home = Path(os.environ['JAVA_HOME'])
    subprocess.run([str(java_home / 'bin/javac'), '--release', '17', '-encoding', 'UTF-8',
                    '-cp', os.pathsep.join(map(str, entries)), '-d', str(directory), str(Path(adapter_source).resolve())], check=True)
    attestation.update({'source': identity, 'adapterClasses': str(directory), 'adapterSha256': tree_sha(directory),
                        'adapterSourceSha256': sha(bounded(adapter_source, 65536))})
    verify_checkout(checkout)
    return attestation


def launch(checkout, attestation_path, attestation_sha, protocol_path, protocol_sha,
           prepared, native_contract, output, cases, self_test=False):
    """Launch one capture per fixed case; no engine, retry or current classpath.

    Args:
        checkout: Unmodified historical worktree.
        attestation_path: Bounded actual compilation attestation.
        attestation_sha: Independently supplied exact attestation digest.
        protocol_path: Capture-only detached input protocol.
        protocol_sha: Independently supplied exact protocol digest.
        prepared: Flat raw inputs from the earlier exited preparation process.
        native_contract: Independently pinned factual native-source contract.
        output: New directory for bounded case receipts/logs.
        cases: Literal fixed IDs, each invoked at most once by this call.
        self_test: Controlled adapter checks instead of a scene capture.
    Returns:
        Preparation-only source and invocation metadata.
    Raises:
        ValueError: Source/classpath/input bindings fail.
        subprocess.CalledProcessError: A capture refuses or fails; never retried.
        subprocess.TimeoutExpired: External360s ceiling expires.
    """
    identity = verify_checkout(checkout)
    attestation_raw = bounded(attestation_path, 65536)
    if sha(attestation_raw) != attestation_sha:
        raise ValueError('external classpath attestation')
    attestation = metadata(attestation_raw)
    if attestation['source'] != identity:
        raise ValueError('historical source attestation changed')
    cp = verify_classpath(checkout, attestation['runtimeEntries'])
    if any(cp[key] != attestation[key] for key in cp) or tree_sha(attestation['adapterClasses']) != attestation['adapterSha256']:
        raise ValueError('historical classpath attestation changed')
    output = Path(output)
    output.mkdir()
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java')
    for case in cases:
        if case not in IDS:
            raise ValueError('fixed public case')
        command = [java, '-Djava.awt.headless=true', '-cp', os.pathsep.join([attestation['adapterClasses'], *cp['runtimeEntries']]),
                   'HistoricalNativeB', 'SELF_TEST' if self_test else 'CAPTURE_ONLY',
                   str(Path(attestation_path).resolve()), attestation_sha, str(Path(protocol_path).resolve()), protocol_sha,
                   str(Path(prepared).resolve()), str(Path(native_contract).resolve()), case, str((output / (case + '.json')).resolve())]
        with (output / (case + '.log')).open('xb') as log:
            subprocess.run(command, check=True, timeout=360, stdout=log, stderr=subprocess.STDOUT)
        verify_checkout(checkout)
    after = verify_classpath(checkout, attestation['runtimeEntries'])
    if after != cp or tree_sha(attestation['adapterClasses']) != attestation['adapterSha256']:
        raise ValueError('historical classpath changed after capture')
    return {'scope': SCOPE, 'attempted': len(cases), 'engineInvocations': 0, 'historicalSource': identity}


def main(argv=None):
    """Run explicit preparation, compilation or CAPTURE_ONLY, without an engine.

    Args:
        argv: Optional explicit argument list; otherwise command-line arguments.
    Returns:
        None. Writes only requested new bounded preparation/receipt directories.
    Raises:
        ValueError: A bounded input or integrity contract is invalid.
        subprocess.SubprocessError: A child fails or exceeds its run ceiling.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='mode', required=True)
    make = sub.add_parser('protocol')
    for name in ('archive', 'native-contract', 'output'):
        make.add_argument('--' + name, required=True, type=Path)
    prep = sub.add_parser('prepare')
    for name in ('archive', 'protocol', 'destination'):
        prep.add_argument('--' + name, required=True, type=Path)
    prep.add_argument('--archive-sha', required=True)
    prep.add_argument('--protocol-sha', required=True)
    compile_command = sub.add_parser('compile-adapter')
    for name in ('checkout', 'dependency', 'adapter-source', 'classes', 'attestation'):
        compile_command.add_argument('--' + name, required=True, type=Path)
    run = sub.add_parser('capture-only')
    for name in ('checkout', 'attestation', 'protocol', 'archive', 'prepared', 'native-contract', 'output'):
        run.add_argument('--' + name, required=True, type=Path)
    run.add_argument('--attestation-sha', required=True)
    run.add_argument('--protocol-sha', required=True)
    run.add_argument('--case', choices=(*IDS, 'ALL'), required=True)
    run.add_argument('--self-test', action='store_true')
    args = parser.parse_args(argv)
    if args.mode == 'protocol':
        digest, unused = make_protocol(args.archive, args.native_contract, args.output)
        print(digest)
    elif args.mode == 'prepare':
        result = prepare(args.archive, args.archive_sha, args.destination,
                         metadata(bounded(args.protocol, 65536)), args.protocol_sha)
        print(canonical(result).decode(), end='')
    elif args.mode == 'compile-adapter':
        data = canonical(compile_adapter(args.checkout, args.dependency, args.adapter_source, args.classes))
        args.attestation.write_bytes(data)
        print(sha(data))
    else:
        protocol_raw = bounded(args.protocol, 65536)
        if sha(protocol_raw) != args.protocol_sha:
            raise ValueError('external protocol digest')
        protocol = metadata(protocol_raw)
        # This process waits for separate preparation to terminate before Java starts.
        subprocess.run([sys.executable, str(Path(__file__).resolve()), 'prepare', '--archive', str(args.archive),
                        '--archive-sha', protocol['archiveSha256'], '--protocol', str(args.protocol),
                        '--protocol-sha', args.protocol_sha, '--destination', str(args.prepared)], check=True, timeout=60)
        result = launch(args.checkout, args.attestation, args.attestation_sha, args.protocol, args.protocol_sha,
                        args.prepared, args.native_contract, args.output, IDS if args.case == 'ALL' else [args.case], args.self_test)
        print(canonical(result).decode(), end='')


if __name__ == '__main__':
    main()
