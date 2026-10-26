"""Bounded, offline Ubuntu acquisition DATA; never a production runtime receipt.

Only the two previously selected historical InRelease identities are accepted.
An existing CI verifier is an independently trusted acquisition instrument, not
a tool qualified by its own successful result. Network dispatch, package payload
comparison and protected runtime installation remain separate gates.
"""
import datetime
import hashlib
import importlib.util
import os
from pathlib import Path
import re
import shutil
import stat
import time
import zlib

ROOT = Path(__file__).resolve().parent
PRIMARY = 'F6ECB3762474EDA9D21B7022871920D1991BC93C'
REFERENCE = datetime.datetime(2026, 10, 12, tzinfo=datetime.timezone.utc)
MAX_SIGNED = 512 << 10
MAX_INDEX = 4 << 20
MAX_EXPANDED = 8 << 20
RELEASE_PROFILES = {
    'noble': {'suite': 'noble', 'bytes': 255850,
              'sha256': 'cdb2f31d809f589719a53c6ad15f255b27569c4059542ada282aaa21b8e164b0'},
    'snapshot-20261012-noble-updates': {
        'suite': 'noble-updates', 'bytes': 126125,
        'sha256': '73c1f36093b3247c866091dc6a486e887aa73665c013672cf240276f32348dd4'},
}
EXPECTED_PACKAGES = {
    'noble': {'libassuan0': '2.5.6-1build1', 'libnpth0t64': '1.6-3.1build1'},
    'snapshot-20261012-noble-updates': {
        'gpgv': '2.4.4-2ubuntu17.6', 'libc6': '2.39-0ubuntu8.9',
        'zlib1g': '1:1.3.dfsg-3.1ubuntu2.2', 'libbz2-1.0': '1.0.8-5.1ubuntu0.1',
        'libgcrypt20': '1.10.3-2ubuntu0.2', 'libgpg-error0': '1.47-3build2.1'},
}


def _need(condition, cause):
    if not condition:
        raise ValueError(cause)


def _sha(data):
    return hashlib.sha256(data).hexdigest()


def _module(filename):
    spec = importlib.util.spec_from_file_location(filename.replace('-', '_'), ROOT / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class _Clock:
    """One original acquisition deadline, including verification and parsing."""
    def __init__(self):
        self.deadline = time.monotonic() + 600

    def remaining(self):
        value = self.deadline - time.monotonic()
        _need(value > 0, 'ACQUISITION_DEADLINE')
        return value

    def check(self):
        self.remaining()


def _public_ring(clock=None):
    armor, _ = _read_file(ROOT / 'tests/fixtures/ubuntu-archive-2018.asc',
                          _Clock() if clock is None else clock, 1660, True)
    _need(len(armor) == 1660 and _sha(armor) ==
          '2a3cc57ab6b47626b496a101c29af6dfe54d54d03d613f2326b9f2a30a15c39b',
          'UBUNTU_KEY_SOURCE')
    raw, primary = _module('b-public-keyring-data.py')._decode_armored_public_key(armor)
    _need(primary == PRIMARY and len(raw) == 1167 and _sha(raw) ==
          '5ebbeeb474034b1fa7e50abbe6f136e177fc826219e01f314b3623f7b3097e96',
          'UBUNTU_KEY_TRANSCRIPTION')
    return raw


def _cleartext(signed):
    """Extract the exact LF-only, dash-unescaped clear-sign plaintext profile."""
    _need(type(signed) is bytes and 0 < len(signed) <= MAX_SIGNED, 'SIGNED_INPUT_SIZE')
    prefix = b'-----BEGIN PGP SIGNED MESSAGE-----\nHash: SHA512\n\n'
    start = b'-----BEGIN PGP SIGNATURE-----\n'
    end = b'-----END PGP SIGNATURE-----\n'
    _need(signed.startswith(prefix) and signed.endswith(end) and b'\r' not in signed
          and signed.count(start) == 1 and signed.count(end) == 1, 'CLEARSIGN_FRAME')
    body, signature = signed[len(prefix):].split(start)
    _need(body.endswith(b'\n') and signature.startswith(b'\n'), 'CLEARSIGN_FRAME')
    rows = []
    for row in body.splitlines(keepends=True):
        _need(row.endswith(b'\n') and b'\0' not in row, 'CLEARSIGN_LINE')
        _need(not row.startswith(b'-') or row.startswith(b'- '), 'CLEARSIGN_DASH_ESCAPE')
        rows.append(row[2:] if row.startswith(b'- ') else row)
    return b''.join(rows)


def _stamp(path, clock, maximum):
    """No-follow bounded file digest plus before/after identity, never an issuer."""
    return _read_file(path, clock, maximum, False)[1]


def _read_file(path, clock, maximum, retain):
    """Digest and optionally return bytes from the SAME bounded no-follow FD."""
    clock.check()
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    primary = None
    try:
        before = os.fstat(fd)
        _need(stat.S_ISREG(before.st_mode) and 0 < before.st_size <= maximum, 'FILE_PROFILE')
        h = hashlib.sha256()
        count = 0
        chunks = [] if retain else None
        while True:
            clock.check()
            chunk = os.read(fd, min(65536, maximum - count + 1))
            if not chunk:
                break
            count += len(chunk)
            _need(count <= maximum, 'FILE_SIZE_LIMIT')
            h.update(chunk)
            if retain:
                chunks.append(chunk)
        after = os.fstat(fd)
        _need(_identity(before) == _identity(after) == _identity(os.stat(path, follow_symlinks=False))
              and count == before.st_size, 'FILE_IDENTITY_DRIFT')
        record = {'identity': _identity(before), 'bytes': count, 'sha256': h.hexdigest()}
        return (b''.join(chunks) if retain else None), record
    except BaseException as failure:
        primary = failure
        raise
    finally:
        try:
            os.close(fd)
        except BaseException:
            if primary is None:
                raise


def _write_owned(path, data, clock):
    """Exclusive owned writer; preserve primary through its one close attempt."""
    clock.check()
    stream = path.open('xb')
    primary = None
    try:
        _need(stream.write(data) == len(data), 'OWNED_WRITE_INCOMPLETE')
        clock.check()
    except BaseException as failure:
        primary = failure
        raise
    finally:
        try:
            stream.close()
        except BaseException:
            if primary is None:
                raise


def _identity(value):
    return (value.st_dev, value.st_ino, value.st_mode, value.st_uid, value.st_gid,
            value.st_size, value.st_mtime_ns, value.st_ctime_ns)


def _owned_verify(argv, clock):
    # The inherited helper owns kill/reap and primary-preserving process cleanup.
    base = _module('b-boot-bundle-verifier.py')
    log = Path(argv[2]).parent / 'verifier-output'
    log.mkdir(mode=0o700)
    code, out, _ = base._owned_tool(argv, {'LANG': 'C', 'LC_ALL': 'C'}, log,
                                   min(300, clock.remaining()), clock.check)
    return code, out


def _acquisition_status(code, status):
    """Exact clear-sign output row; old detached-signature parser stays strict.

    Upstream2.4.4 armor emits a partial literal packet, mode't', empty name,
    zero timestamp. plaintext.c therefore emits PLAINTEXT74/0/empty and no
    PLAINTEXT_LENGTH. Other literal-data modes/length records are unsupported.
    """
    _need(type(status) is bytes and len(status) <= 65536, 'SIGNATURE_STATUS_SIZE')
    _need(all(byte == 10 or 32 <= byte <= 126 for byte in status), 'UBUNTU_SIGNATURE_REFUSED')
    rows = status.decode('ascii').splitlines()
    signature, plaintext = [], 0
    for row in rows:
        if row.startswith('[GNUPG:] PLAINTEXT'):
            _need(row == '[GNUPG:] PLAINTEXT 74 0 ', 'UBUNTU_PLAINTEXT_STATUS')
            plaintext += 1
        else:
            signature.append(row)
    _need(plaintext == 1, 'UBUNTU_PLAINTEXT_STATUS')
    try:
        _module('b-boot-bundle-verifier.py').signature_status(code, '\n'.join(signature), PRIMARY)
    except RuntimeError:
        raise ValueError('UBUNTU_SIGNATURE_REFUSED') from None
    return {'mode_hex': '74', 'timestamp': 0, 'filename': '',
            'length_status': 'ABSENT_PARTIAL_CLEARSIGN'}


def _date(value):
    # The selected signed Ubuntu releases use the explicit UTC spelling.
    try:
        parsed = datetime.datetime.strptime(value, '%a, %d %b %Y %H:%M:%S UTC')
    except (ValueError, TypeError):
        raise ValueError('RELEASE_DATE_FORMAT') from None
    _need(parsed.strftime('%a, %d %b %Y %H:%M:%S UTC') == value, 'RELEASE_DATE_FORMAT')
    return parsed.replace(tzinfo=datetime.timezone.utc)


def _fields(data):
    _need(type(data) is bytes and len(data) <= MAX_EXPANDED and b'\r' not in data
          and b'\0' not in data, 'METADATA_ENCODING')
    try:
        text = data.decode('utf-8')
    except UnicodeDecodeError:
        raise ValueError('METADATA_ENCODING') from None
    result = {}
    key = None
    for line in text.split('\n'):
        if not line:
            continue
        _need(len(line.encode('utf-8')) <= 65536 and
              all(ord(c) >= 32 or c == '\t' for c in line), 'METADATA_LINE')
        if line[0] in ' \t':
            _need(key is not None, 'METADATA_CONTINUATION')
            result[key] += '\n' + line
        else:
            key, colon, value = line.partition(':')
            _need(colon and re.fullmatch(r'[A-Za-z][A-Za-z0-9-]*', key)
                  and key not in result, 'METADATA_FIELD')
            result[key] = value.lstrip(' ')
    return result


def _release_fields(plain, name):
    fields = _fields(plain)
    profile = RELEASE_PROFILES[name]
    _need(fields.get('Origin') == fields.get('Label') == 'Ubuntu'
          and fields.get('Suite') == profile['suite'] and fields.get('Codename') == 'noble'
          and 'amd64' in fields.get('Architectures', '').split()
          and 'main' in fields.get('Components', '').split(), 'RELEASE_IDENTITY')
    date = _date(fields.get('Date'))
    _need(date <= REFERENCE, 'RELEASE_FUTURE')
    if name != 'noble':
        _need(REFERENCE - date <= datetime.timedelta(hours=48), 'HISTORICAL_SNAPSHOT_AGE')
    until = fields.get('Valid-Until')
    if until is not None:
        _need(date < _date(until) and REFERENCE <= _date(until), 'HISTORICAL_RELEASE_EXPIRED')
    entries = {}
    rows = fields.get('SHA256', '').splitlines()
    _need(len(rows) <= 4096, 'RELEASE_HASH_COUNT')
    for row in rows:
        if not row:
            continue
        parts = row.split()
        _need(len(parts) == 3 and re.fullmatch('[0-9a-f]{64}', parts[0])
              and re.fullmatch('[0-9]{1,12}', parts[1]), 'RELEASE_HASH_RECORD')
        path = parts[2]
        _need(path not in entries and not path.startswith('/') and '\\' not in path
              and all(x not in ('', '.', '..') for x in path.split('/')), 'RELEASE_HASH_PATH')
        entries[path] = {'bytes': int(parts[1]), 'sha256': parts[0]}
    required = ('main/binary-amd64/Packages.gz', 'main/binary-amd64/Packages')
    _need(all(path in entries for path in required), 'PACKAGE_INDEX_BINDING_ABSENT')
    return fields, {path: entries[path] for path in required}, (
        'HISTORICAL_ABSENCE_EXPLICIT' if until is None else 'HISTORICAL_VALID_UNTIL_CHECKED')


def _observe_inrelease(signed, name, stage, clock, tool):
    _need(name in RELEASE_PROFILES, 'RELEASE_PROFILE')
    profile = RELEASE_PROFILES[name]
    plain = _cleartext(signed)
    _need(len(signed) == profile['bytes'] and _sha(signed) == profile['sha256'], 'RELEASE_SOURCE')
    clock.check()
    stage = Path(stage).parent.resolve(strict=True) / Path(stage).name
    _need(stage.name not in ('', '.', '..'), 'STAGE_PATH')
    stage.mkdir(mode=0o700)
    original_stage = os.stat(stage, follow_symlinks=False)
    primary = None
    try:
        home = stage / 'home'
        home.mkdir(mode=0o700)
        home_identity = os.stat(home, follow_symlinks=False)
        source = stage / 'InRelease'
        ring = stage / 'ubuntu.gpg'
        output = stage / 'verified.Release'
        for path, data in ((source, signed), (ring, _public_ring(clock))):
            _write_owned(path, data, clock)
        before = [_stamp(path, clock, limit) for path, limit in
                  ((tool, 64 << 20), (source, MAX_SIGNED), (ring, 4096))]
        argv = (str(tool), '--homedir', str(home), '--status-fd', '1', '--keyring', str(ring),
                '--output', str(output), str(source))
        #These role paths are the actual immutable argv positions passed below.
        #Only role names, not private stage paths, enter the observation.
        role_paths = {0: tool, 2: home, 6: ring, 8: output, 9: source}
        _need(all(argv[index] == str(path) for index, path in role_paths.items()), 'INVOCATION_ROLE_JOIN')
        invocation = {'protocol': 'UBUNTU2018_OWNED_HOME_CLEARSIGN_1',
                      'argv_roles': ['EXISTING_CI_GPGV', '--homedir', 'OWNED_EMPTY_HOME', '--status-fd', '1',
                                     '--keyring', 'OWNED_RING', '--output', 'OWNED_PLAINTEXT', 'OWNED_SIGNED_INPUT'],
                      'environment': {'LANG': 'C', 'LC_ALL': 'C'}}
        code, status = _owned_verify(argv, clock)
        clock.check()
        plaintext_status = _acquisition_status(code, status)
        after = [_stamp(path, clock, limit) for path, limit in
                 ((tool, 64 << 20), (source, MAX_SIGNED), (ring, 4096))]
        _need(before == after, 'VERIFIER_INPUT_OR_TOOL_DRIFT')
        _need(_identity(os.stat(home, follow_symlinks=False)) == _identity(home_identity)
              and not list(home.iterdir()), 'OWNED_HOME_DRIFT')
        actual, consumed = _read_file(output, clock, MAX_SIGNED, True)
        _need(actual == plain, 'SIGNED_PLAINTEXT_MISMATCH')
        plaintext_status['consumed_bytes'] = consumed['bytes']
        fields, indexes, validity = _release_fields(actual, name)
        clock.check()
        return {'scope': 'ACQUISITION_SIGNATURE_DATA', 'qualified': False,
                'factual_authority': 'ACQUISITION_OBSERVATION_ONLY', 'release': name,
                'primary': PRIMARY, 'signed_sha256': _sha(signed), 'signed_bytes': len(signed),
                'plaintext_sha256': _sha(actual), 'plaintext_bytes': len(actual),
                'tool_before': before[0], 'tool_after': after[0],
                'signed_input_before': before[1], 'signed_input_after': after[1],
                'ring_before': before[2], 'ring_after': after[2],
                'verification_invocation': invocation, 'consumed_plaintext': consumed,
                'plaintext_status': plaintext_status,
                'status_sha256': _sha(status), 'status_bytes': len(status), 'exit': code,
                'date': fields['Date'], 'valid_until': fields.get('Valid-Until'),
                'valid_until_policy': validity, 'reference': REFERENCE.isoformat(),
                'current_repository_freshness': False, 'indexes': indexes,
                'target_executions': 0, 'candidate_executions': 0, 'cases': 0}
    except BaseException as failure:
        primary = failure
        raise
    finally:
        try:
            current = os.stat(stage, follow_symlinks=False)
            _need(current.st_ino == original_stage.st_ino and current.st_dev == original_stage.st_dev
                  and stat.S_ISDIR(current.st_mode), 'OWNED_STAGE_DRIFT')
            shutil.rmtree(stage)
        except BaseException:
            if primary is None:
                raise


def ubuntu_release_data(signed, name, stage):
    """Verify one exact retained PUBLIC release using fixed existing CI gpgv.

    This performs offline acquisition-tool verification only. It creates and
    removes an exclusive owned stage and propagates failures without publishing
    an authenticated result. The original 600-second clock covers this operation.
    No target, package code, network, installation or production registry runs.
    """
    return _observe_inrelease(signed, name, stage, _Clock(), Path('/usr/bin/gpgv'))


def package_index_data(observation, compressed):
    """Check selected Packages bytes/records against an acquisition observation.

    The dictionary is diagnostic DATA, not an unforgeable authorization token.
    The eventual collector must retain the actual preceding observation in the
    same owned transaction. No caller dictionary can qualify a runtime.
    """
    return _package_index_data(observation, compressed, _Clock())


def _package_index_data(observation, compressed, clock):
    clock.check()
    _need(type(observation) is dict and observation.get('scope') == 'ACQUISITION_SIGNATURE_DATA'
          and observation.get('qualified') is False, 'RELEASE_OBSERVATION')
    name = observation.get('release')
    _need(name in EXPECTED_PACKAGES and type(compressed) is bytes
          and 0 < len(compressed) <= MAX_INDEX, 'PACKAGE_INDEX_PROFILE')
    expected = observation['indexes']['main/binary-amd64/Packages.gz']
    _need(len(compressed) == expected['bytes'] and _sha(compressed) == expected['sha256'],
          'PACKAGE_INDEX_COMPRESSED_HASH')
    inflater = zlib.decompressobj(16 + zlib.MAX_WBITS)
    try:
        expanded = inflater.decompress(compressed, MAX_EXPANDED + 1)
    except zlib.error:
        raise ValueError('PACKAGE_GZIP') from None
    _need(len(expanded) <= MAX_EXPANDED and inflater.eof and not inflater.unused_data
          and not inflater.unconsumed_tail, 'PACKAGE_GZIP_EXTENT')
    clock.check()
    expected = observation['indexes']['main/binary-amd64/Packages']
    _need(len(expanded) == expected['bytes'] and _sha(expanded) == expected['sha256'],
          'PACKAGE_INDEX_EXPANDED_HASH')
    found = {}
    count = 0
    for paragraph in expanded.split(b'\n\n'):
        clock.check()
        if not paragraph.strip():
            continue
        count += 1
        _need(count <= 131072 and len(paragraph) <= 65536, 'PACKAGE_PARAGRAPH_LIMIT')
        fields = _fields(paragraph)
        package = fields.get('Package')
        if package not in EXPECTED_PACKAGES[name]:
            continue
        _need(package not in found, 'PACKAGE_DUPLICATE')
        _need(fields.get('Version') == EXPECTED_PACKAGES[name][package]
              and fields.get('Architecture') == 'amd64', 'PACKAGE_IDENTITY')
        path = fields.get('Filename', '')
        _need(path.startswith('pool/main/') and path.endswith('.deb') and '\\' not in path
              and all(x not in ('', '.', '..') for x in path.split('/'))
              and re.fullmatch(r'[A-Za-z0-9+_./~-]+', path), 'PACKAGE_FILENAME')
        _need(re.fullmatch('[0-9]{1,10}', fields.get('Size', ''))
              and 0 < int(fields['Size']) <= 512 << 20
              and re.fullmatch('[0-9a-f]{64}', fields.get('SHA256', '')), 'PACKAGE_PAYLOAD_IDENTITY')
        found[package] = {key: fields[key] for key in
                          ('Package', 'Version', 'Architecture', 'Filename', 'Size', 'SHA256')}
    _need(set(found) == set(EXPECTED_PACKAGES[name]), 'SELECTED_PACKAGE_MISSING')
    clock.check()
    return {'scope': 'ACQUISITION_PACKAGE_METADATA_DATA', 'qualified': False,
            'release': name, 'signed_sha256': observation['signed_sha256'],
            'compressed_sha256': _sha(compressed), 'expanded_sha256': _sha(expanded),
            'compressed_bytes': len(compressed), 'expanded_bytes': len(expanded),
            'packages': found, 'target_executions': 0, 'candidate_executions': 0, 'cases': 0}


def ubuntu_package_data(signed, compressed, name, stage):
    """Verify a retained release and its exact selected index under ONE deadline.

    Callers provide already acquired PUBLIC bytes. This is the complete offline
    signature-to-package-metadata join; it neither downloads nor compares deb
    payloads and cannot install or qualify a production tool/runtime.
    """
    clock = _Clock()
    observation = _observe_inrelease(signed, name, stage, clock, Path('/usr/bin/gpgv'))
    packages = _package_index_data(observation, compressed, clock)
    return {'scope': 'ACQUISITION_PACKAGE_CHAIN_DATA', 'qualified': False,
            'release_observation': observation, 'package_metadata': packages,
            'target_executions': 0, 'candidate_executions': 0, 'cases': 0}


def ubuntu_package_set_data(signed_releases, package_indexes, stage):
    """Join BOTH fixed releases and all eight exact packages under one clock.

    Partial input never becomes an apparently complete tool package inventory.
    Inputs are already acquired PUBLIC bytes; network dispatch and payload
    comparison still require the separate reviewed collector/manifest.
    """
    names = ('noble', 'snapshot-20261012-noble-updates')
    _need(type(signed_releases) is dict and type(package_indexes) is dict
          and len(signed_releases) == len(package_indexes) == 2, 'RELEASE_SET')
    for mapping in (signed_releases, package_indexes):
        _need(all(type(name) is str and name in names for name in mapping), 'RELEASE_SET')
    clock = _Clock()
    stage = Path(stage).parent.resolve(strict=True) / Path(stage).name
    _need(stage.name not in ('', '.', '..'), 'STAGE_PATH')
    stage.mkdir(mode=0o700)
    original = os.stat(stage, follow_symlinks=False)
    primary = None
    try:
        releases = []
        packages = {}
        for name in names:
            observed = _observe_inrelease(signed_releases[name], name, stage / name,
                                          clock, Path('/usr/bin/gpgv'))
            indexed = _package_index_data(observed, package_indexes[name], clock)
            _need(not set(packages).intersection(indexed['packages']), 'PACKAGE_RELEASE_CONFLICT')
            packages.update(indexed['packages'])
            releases.append({'observation': observed, 'index': indexed})
        _need(len(packages) == 8, 'COMPLETE_PACKAGE_SET')
        return {'scope': 'ACQUISITION_COMPLETE_PACKAGE_METADATA_DATA', 'qualified': False,
                'releases': releases, 'packages': packages, 'current_repository_freshness': False,
                'target_executions': 0, 'candidate_executions': 0, 'cases': 0}
    except BaseException as failure:
        primary = failure
        raise
    finally:
        try:
            current = os.stat(stage, follow_symlinks=False)
            _need(current.st_ino == original.st_ino and current.st_dev == original.st_dev
                  and stat.S_ISDIR(current.st_mode), 'OWNED_STAGE_DRIFT')
            shutil.rmtree(stage)
        except BaseException:
            if primary is None:
                raise
