"""Four literal PUBLIC metadata requests and offline authentication on CI.

Generated-only DATA reports, no binary uploads, installation or runtime permit.
Dispatch requires separate review/controller authorization; a pinned manifest
is an input selection, not permission or factual supplier authority.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import stat
import time
from types import SimpleNamespace
import urllib.request
import urllib.error

ROOT = Path(__file__).resolve().parent
UBUNTU_SHA256 = 'be8a0521fab54ad61ae9dea2d1ce4d0232c8bdd3844bdf1dd578ff3cbabff1df'
MANIFEST_SHA256 = 'e17df9eaf29ef26880eaaa4509c5b38b0029297e1f1e70dcd79a663ee34479af'
SOURCE_PINS = {
    'b-boot-bundle-collect.py': '4c9740f39ae3ced533a493c89baf01a217b8a7a7100f53c4a32658e5458fce17',
    'b-boot-bundle-verifier.py': '16cfafddfec78c09a61551c66920df8663324ac9fc1afc5da63ed20159828c67',
    'b-public-keyring-data.py': 'cbd40ab75070c6228d1da1b6b3beba57f9033d7b4725297a09b6d8a6b09f4fb5',
}
URLS = (
    'https://archive.ubuntu.com/ubuntu/dists/noble/InRelease',
    'https://archive.ubuntu.com/ubuntu/dists/noble/main/binary-amd64/Packages.gz',
    'https://snapshot.ubuntu.com/ubuntu/20261012T000000Z/dists/noble-updates/InRelease',
    'https://snapshot.ubuntu.com/ubuntu/20261012T000000Z/dists/noble-updates/main/binary-amd64/Packages.gz',
)
LIMIT = 16 << 20


def _need(condition, cause):
    if not condition:
        raise ValueError(cause)


class _Clock:
    def __init__(self):
        self.deadline = time.monotonic() + 600

    def remaining(self):
        remaining = self.deadline - time.monotonic()
        _need(remaining > 0, 'ACQUISITION_DEADLINE')
        return remaining

    def check(self):
        self.remaining()


def _read_source(path):
    """Bound the actual no-follow source read; preserve a read/identity primary."""
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    primary = None
    try:
        before = os.fstat(fd)
        _need(stat.S_ISREG(before.st_mode) and 0 < before.st_size <= 65536, 'SOURCE_PROFILE')
        data = bytearray()
        while True:
            block = os.read(fd, min(65536, 65537 - len(data)))
            if not block:
                break
            data.extend(block)
            _need(len(data) <= 65536, 'SOURCE_LIMIT')
        identity = lambda s: (s.st_dev, s.st_ino, s.st_mode, s.st_size, s.st_mtime_ns, s.st_ctime_ns)
        _need(identity(before) == identity(os.fstat(fd)) == identity(os.stat(path, follow_symlinks=False))
              and len(data) == before.st_size, 'SOURCE_DRIFT')
        return bytes(data)
    except BaseException as failure:
        primary = failure
        raise
    finally:
        try:
            os.close(fd)
        except BaseException:
            if primary is None:
                raise


def _manifest():
    raw = _read_source(ROOT / 'data/b-ubuntu-metadata-acquisition.json')
    _need(hashlib.sha256(raw).hexdigest() == MANIFEST_SHA256, 'MANIFEST_IDENTITY')
    value = json.loads(raw)
    _need(tuple(row['url'] for row in value['sources']) == URLS, 'MANIFEST_URLS')
    return value


def _dependencies():
    sources = {}
    for name, expected in {**SOURCE_PINS, 'b-ubuntu-package-data.py': UBUNTU_SHA256}.items():
        raw = _read_source(ROOT / name)
        _need(hashlib.sha256(raw).hexdigest() == expected, 'DEPENDENCY_IDENTITY')
        sources[name] = raw
    result = {}
    for role, name in (('ubuntu', 'b-ubuntu-package-data.py'), ('collector', 'b-boot-bundle-collect.py')):
        namespace = {'__name__': 'fixed_ubuntu_' + role, '__file__': str(ROOT / name)}
        exec(compile(sources[name], str(ROOT / name), 'exec'), namespace)
        result[role] = SimpleNamespace(**namespace)
    return result


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        failure = ValueError('REDIRECT_REFUSED')
        try:
            fp.close()
        finally:
            raise failure


def _open(url, timeout):
    opener = urllib.request.build_opener(_NoRedirect())
    request = urllib.request.Request(url, headers={'User-Agent': 'josm-fixed-ubuntu-public-data/1'})
    try:
        return opener.open(request, timeout=timeout)
    except urllib.error.HTTPError as failure:
        # This exact urllib exception owns its acquired response. Do not read it
        # or claim ownership of arbitrary attributes on unrelated exceptions.
        try:
            failure.close()
        except BaseException:
            pass  # Preserve the original HTTP failure even if close interrupts.
        raise


def _fetch(row, clock, total):
    clock.check()
    _need(row['url'] in URLS and type(row['bytes']) is int and 0 < row['bytes'] <= 4 << 20,
          'FETCH_PROFILE')
    _need(total['bytes'] + row['bytes'] <= LIMIT, 'DOWNLOAD_LIMIT')
    response = _open(row['url'], min(30, clock.remaining()))
    primary = None
    try:
        _need(response.status == 200 and response.geturl() == row['url'], 'FETCH_HTTP_OR_URL')
        declared = response.headers.get('Content-Length')
        _need(declared is None or (type(declared) is str and re.fullmatch('[0-9]{1,10}', declared)
                                  and int(declared) == row['bytes']), 'FETCH_DECLARED_SIZE')
        data = bytearray()
        h = hashlib.sha256()
        while True:
            clock.check()
            block = response.read(min(65536, row['bytes'] - len(data) + 1))
            _need(type(block) is bytes, 'FETCH_BODY_TYPE')
            total['bytes'] += len(block)
            clock.check()  # Count the actual returned bytes before expiry/refusal.
            if not block:
                break
            _need(total['bytes'] <= LIMIT and len(data) + len(block) <= row['bytes'], 'FETCH_BODY_SIZE')
            data.extend(block)
            h.update(block)
        _need(len(data) == row['bytes'] and h.hexdigest() == row['sha256'], 'FETCH_BODY_IDENTITY')
        result = bytes(data), {'url': row['url'], 'bytes': len(data), 'sha256': h.hexdigest()}
    except BaseException as failure:
        primary = failure
        raise
    finally:
        try:
            response.close()
        except BaseException:
            if primary is None:
                raise
    clock.check()
    return result


def collect(output, scratch):
    """Run the fixed acquisition once, preserving bounded truthful partial DATA."""
    clock = _Clock()  # Original clock precedes dependency loading or acquisition.
    dependencies = _dependencies()
    clock.check()
    manifest = _manifest()
    clock.check()
    scratch = Path(scratch).parent.resolve(strict=True) / Path(scratch).name
    _need(scratch.name not in ('', '.', '..'), 'SCRATCH_PATH')
    scratch.mkdir(mode=0o700)
    original = os.stat(scratch, follow_symlinks=False)
    evidence = None
    primary = None
    status = {'scope': 'PUBLIC_UBUNTU_METADATA_DATA', 'qualified': False,
              'outcome': 'PARTIAL_UNSEALED', 'cause': None, 'phase': 'PREPARE',
              'cases': 0, 'candidate_executions': 0, 'target_executions': 0,
              'current_repository_freshness': False, 'deadline_seconds': 600,
              'external_timeout_seconds': 610, 'downloaded_bytes': 0, 'cleanup': 'PENDING'}
    total = {'bytes': 0}
    try:
        evidence = dependencies['collector'].Evidence(output, {'leaf': 65536, 'output': 32 << 20})
        evidence.json('reports/policy.json', {'manifest_sha256': MANIFEST_SHA256,
                      'dependencies': {**SOURCE_PINS, 'b-ubuntu-package-data.py': UBUNTU_SHA256},
                      'reference': manifest['reference'], 'download_limit': LIMIT,
                      'redirects': 0, 'retries': 0, 'qualified': False})
        downloaded = {}
        for number, row in enumerate(manifest['sources']):
            status['phase'] = 'FETCH_METADATA'
            raw, observed = _fetch(row, clock, total)
            status['downloaded_bytes'] = total['bytes']
            downloaded[(row['release'], row['kind'])] = raw
            evidence.json('reports/fetch-%d.json' % number, observed)
        packages = {}
        for number, name in enumerate(('noble', 'snapshot-20261012-noble-updates')):
            status['phase'] = 'VERIFY_RELEASE'
            observation = dependencies['ubuntu']._observe_inrelease(
                downloaded[(name, 'release')], name, scratch / name, clock, Path('/usr/bin/gpgv'))
            evidence.json('reports/release-%d.json' % number, observation)
            status['phase'] = 'VERIFY_INDEX'
            indexed = dependencies['ubuntu']._package_index_data(observation, downloaded[(name, 'index')], clock)
            _need(not set(packages).intersection(indexed['packages']), 'PACKAGE_RELEASE_CONFLICT')
            packages.update(indexed['packages'])
            evidence.json('reports/index-%d.json' % number, indexed)
        _need(len(packages) == 8 and set(packages) == set(manifest['expected_packages']), 'PACKAGE_SET')
        for name, expected in manifest['expected_packages'].items():
            _need(all(packages[name].get(key) == value for key, value in expected.items()), 'PACKAGE_IDENTITY')
        clock.check()
        _dependencies()  # Detect source drift; this cannot install a runtime registry.
        clock.check()
        evidence.json('reports/packages.json', {'packages': packages, 'qualified': False})
        status.update(outcome='COMPLETE_METADATA_OBSERVATION', phase='COMPLETE')
    except Exception as failure:
        primary = failure
        if evidence is None:
            raise
        status.update(outcome='PARTIAL_UNSEALED', cause='ACQUISITION_REFUSED')
    finally:
        status['downloaded_bytes'] = total['bytes']
        try:
            current = os.stat(scratch, follow_symlinks=False)
            _need(current.st_dev == original.st_dev and current.st_ino == original.st_ino
                  and stat.S_ISDIR(current.st_mode), 'SCRATCH_DRIFT')
            shutil.rmtree(scratch)
            status['cleanup'] = 'COMPLETE'
        except BaseException:
            status['cleanup'] = 'FAILED'
            status['outcome'] = 'PARTIAL_UNSEALED'
            if primary is None:
                status['cause'] = 'CLEANUP_REFUSED'
    try:
        clock.check()
    except Exception:
        status['outcome'] = 'PARTIAL_UNSEALED'
        if primary is None:
            status['cause'] = 'ACQUISITION_REFUSED'
    evidence.finish(status)
    return status


def main(argv=None):
    if (os.environ.get('GITHUB_ACTIONS') != 'true'
            or os.environ.get('GITHUB_EVENT_NAME') != 'workflow_dispatch'
            or os.environ.get('RUNNER_OS') != 'Linux'
            or os.environ.get('RUNNER_ARCH') != 'X64'):
        print(json.dumps({'outcome': 'PARTIAL_UNSEALED', 'cause': 'MANUAL_CI_PROFILE_REQUIRED',
                          'qualified': False}))
        return 1
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--collect', action='store_true', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--scratch', type=Path, required=True)
    args = parser.parse_args(argv)
    def interrupted(signum, frame):
        raise InterruptedError('ACQUISITION_CANCELLED')
    signal.signal(signal.SIGTERM, interrupted)
    try:
        result = collect(args.output, args.scratch)
    except Exception:
        print(json.dumps({'outcome': 'PARTIAL_UNSEALED', 'cause': 'ACQUISITION_START_REFUSED', 'qualified': False}))
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0 if result['outcome'] == 'COMPLETE_METADATA_OBSERVATION' else 1


if __name__ == '__main__':
    raise SystemExit(main())
