"""Fixed four-request PUBLIC DATA collector; all transports/verifiers are fake."""
import hashlib
import importlib.util
import io
import json
import os
import re
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
import urllib.error
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts/b-ubuntu-metadata-collect.py'


def load():
    if not SCRIPT.is_file():
        raise AssertionError('fixed PUBLIC Ubuntu metadata CI collector is missing')
    spec = importlib.util.spec_from_file_location('ubuntu_metadata_collect_unit', SCRIPT)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


class Response(io.BytesIO):
    status = 200

    def __init__(self, data, url, headers=None):
        super().__init__(data)
        self.url = url
        self.headers = {} if headers is None else headers
        self.request_sizes = []

    def geturl(self):
        return self.url

    def read(self, size=-1):
        if not 0 < size <= 65536:
            raise AssertionError('transport read must be bounded')
        self.request_sizes.append(size)
        return super().read(size)


class UbuntuMetadataCollectTest(unittest.TestCase):
    def test_opener_http_error_closes_only_its_actual_response_and_preserves_primary(self):
        m = load()
        for secondary in (None, OSError('UNIT close'), KeyboardInterrupt('UNIT interrupt')):
            with self.subTest(secondary=type(secondary).__name__):
                response = Response(b'not read', m.URLS[0])
                error = urllib.error.HTTPError(m.URLS[0], 404, 'UNIT', {}, response)
                closed = []
                def close():
                    closed.append(1)
                    io.BytesIO.close(response)
                    if secondary is not None:
                        raise secondary
                opener = SimpleNamespace(open=lambda *args, **kwargs: (_ for _ in ()).throw(error))
                with patch.object(m.urllib.request, 'build_opener', return_value=opener), \
                        patch.object(response, 'close', close), patch.object(response, 'read') as read:
                    with self.assertRaises(urllib.error.HTTPError) as raised:
                        m._open(m.URLS[0], 1)
                    self.assertIs(raised.exception, error)
                    self.assertEqual(closed, [1])
                    self.assertTrue(response.closed)
                    read.assert_not_called()

    def test_acquired_response_interrupt_keeps_primary_and_closes_once(self):
        m = load()
        row = dict(m._manifest()['sources'][0], bytes=4, sha256=hashlib.sha256(b'UNIT').hexdigest())
        response = Response(b'UNIT', row['url'])
        primary = InterruptedError('UNIT cancellation')
        closed = []
        def close():
            closed.append(1)
            io.BytesIO.close(response)
            raise OSError('UNIT close secondary')
        with patch.object(m, '_open', return_value=response), \
                patch.object(response, 'geturl', side_effect=primary), patch.object(response, 'close', close):
            with self.assertRaises(InterruptedError) as raised:
                m._fetch(row, m._Clock(), {'bytes': 0})
        self.assertIs(raised.exception, primary)
        self.assertEqual(closed, [1])
        self.assertTrue(response.closed)

    def test_failed_fetch_preserves_actual_received_bytes_in_terminal(self):
        for failure_mode, expected in [('read', 6), ('hash', 8), ('close', 8), ('expiry', 8),
                                       ('extent', 9), ('digest', 8)]:
            with self.subTest(mode=failure_mode), tempfile.TemporaryDirectory() as directory:
                m = load()
                manifest = json.loads(json.dumps(m._manifest()))
                for row in manifest['sources']:
                    row.update(bytes=4, sha256=hashlib.sha256(b'UNIT').hexdigest())
                now = [0.]
                responses = []
                def opener(url, timeout):
                    response = Response(b'UNIT', url)
                    responses.append(response)
                    if len(responses) == 2:
                        if failure_mode == 'read':
                            count = [0]
                            def read(size):
                                count[0] += 1
                                if count[0] == 1:
                                    return b'UN'
                                raise OSError('UNIT read primary')
                            response.read = read
                        elif failure_mode == 'hash':
                            response = Response(b'fail', url)
                            responses[-1] = response
                        elif failure_mode == 'close':
                            def close():
                                io.BytesIO.close(response)
                                raise OSError('UNIT close only')
                            response.close = close
                        elif failure_mode == 'expiry':
                            def read(size):
                                now[0] = 600.
                                return b'UNIT'
                            response.read = read
                        elif failure_mode == 'extent':
                            response = Response(b'UNIT!', url)
                            responses[-1] = response
                    return response
                root = Path(directory)
                deps = m._dependencies()
                digest = m.hashlib.sha256
                armed = [True]
                class BrokenDigest:
                    def update(self, block):
                        raise OSError('UNIT digest update')
                def sha256(data=b''):
                    if failure_mode == 'digest' and len(responses) == 2 and not data and armed[0]:
                        armed[0] = False
                        return BrokenDigest()
                    return digest(data)
                with patch.object(m, '_manifest', return_value=manifest), \
                        patch.object(m, '_dependencies', return_value=deps), \
                        patch.object(m, '_open', opener), patch.object(m.time, 'monotonic', lambda: now[0]), \
                        patch.object(m.hashlib, 'sha256', sha256):
                    status = m.collect(root / 'output', root / 'scratch')
                self.assertEqual(status['downloaded_bytes'], expected)
                self.assertEqual(status['outcome'], 'PARTIAL_UNSEALED')
                self.assertEqual(status['cause'], 'ACQUISITION_REFUSED')
                self.assertEqual(status['cleanup'], 'COMPLETE')
                self.assertTrue(all(r.closed for r in responses))
                self.assertFalse((root / 'scratch').exists())
                self.assertTrue((root / 'output/reports/fetch-0.json.part0000').is_file())
                self.assertFalse((root / 'output/reports/fetch-1.json.part0000').exists())
                self.assertFalse((root / 'output/reports/packages.json.part0000').exists())
                terminal = json.loads((root / 'output/reports/status.json').read_text())
                self.assertEqual(terminal['downloaded_bytes'], expected)

    def test_cli_refuses_unsupported_manual_ci_before_any_collector_activity(self):
        m = load()
        supported = dict(GITHUB_ACTIONS='true', GITHUB_EVENT_NAME='workflow_dispatch',
                         RUNNER_OS='Linux', RUNNER_ARCH='X64')
        contexts = [{}] + [dict(supported, **{key: value}) for key, value in
                           [('GITHUB_ACTIONS', 'false'), ('GITHUB_EVENT_NAME', 'push'),
                            ('RUNNER_OS', 'Windows'), ('RUNNER_ARCH', 'ARM64')]]
        for env in contexts:
            with self.subTest(env=env), patch.dict(os.environ, env, clear=True), \
                    patch.object(m, 'collect', return_value={'outcome': 'COMPLETE_METADATA_OBSERVATION'}) as collect, \
                    patch.object(m, '_dependencies') as deps, \
                    patch.object(m, '_open') as opener, patch('sys.stdout', new=io.StringIO()) as output:
                self.assertEqual(m.main(['--collect', '--output', 'UNIT', '--scratch', 'UNIT']), 1)
                self.assertEqual(json.loads(output.getvalue())['cause'], 'MANUAL_CI_PROFILE_REQUIRED')
                collect.assert_not_called(); deps.assert_not_called(); opener.assert_not_called()
        with patch.dict(os.environ, supported, clear=True), \
                patch.object(m, 'collect', return_value={'outcome': 'COMPLETE_METADATA_OBSERVATION'}) as collect, \
                patch.object(m, '_dependencies') as deps, patch.object(m, '_open') as opener, \
                patch('sys.stdout', new=io.StringIO()):
            self.assertEqual(m.main(['--collect', '--output', 'UNIT', '--scratch', 'UNIT']), 0)
            collect.assert_called_once_with(Path('UNIT'), Path('UNIT'))
            deps.assert_not_called(); opener.assert_not_called()

    def test_fixed_manifest_names_only_four_public_metadata_sources(self):
        m = load()
        value = m._manifest()
        self.assertEqual(len(value['sources']), 4)
        self.assertEqual(sum(row['bytes'] for row in value['sources']), 3891927)
        self.assertEqual(len(value['expected_packages']), 8)
        self.assertFalse(value['qualified'])
        self.assertFalse(value['dispatch_authorized'])
        self.assertEqual(value['redirects'], 0)
        self.assertEqual(value['retries'], 0)
        self.assertTrue(all(row['url'].endswith(('InRelease', 'Packages.gz'))
                            for row in value['sources']))

    def test_manual_workflow_source_pins_match_actual_files(self):
        workflow = (ROOT / '.github/workflows/public-python-diagnostics.yml').read_text()
        variables = dict(re.findall(r'^\s+([A-Z][A-Z0-9_]*): ([0-9a-f]+)$', workflow, re.MULTILINE))
        pairs = re.findall(r'"\$([A-Z][A-Z0-9_]*)" (scripts/[A-Za-z0-9_./-]+) \| sha256sum', workflow)
        self.assertGreaterEqual(len(pairs), 15)
        for variable, path in pairs:
            with self.subTest(variable=variable, path=path):
                self.assertEqual(variables[variable], hashlib.sha256((ROOT / path).read_bytes()).hexdigest())
        self.assertIn('          - ubuntu-package-metadata\n', workflow)
        self.assertIn('ubuntu-metadata-public/reports/', workflow)
        self.assertIn('ubuntu-metadata-public/inventory/', workflow)

    def test_fixed_fetch_reads_exact_body_and_closes_once(self):
        m = load()
        row = dict(m._manifest()['sources'][0])
        row.update(bytes=4, sha256=hashlib.sha256(b'UNIT').hexdigest())
        response = Response(b'UNIT', row['url'], {'Content-Length': '4'})
        with patch.object(m, '_open', return_value=response) as opener:
            raw, observed = m._fetch(row, m._Clock(), {'bytes': 0})
        self.assertEqual(raw, b'UNIT')
        self.assertEqual(observed['sha256'], row['sha256'])
        self.assertTrue(response.closed)
        self.assertEqual(opener.call_count, 1)
        self.assertTrue(all(0 < n <= 65536 for n in response.request_sizes))

    def test_wrong_url_hash_size_redirect_and_declared_length_refuse(self):
        m = load()
        row = dict(m._manifest()['sources'][0])
        row.update(bytes=4, sha256=hashlib.sha256(b'UNIT').hexdigest())
        with patch.object(m, '_open') as opener:
            with self.assertRaises(ValueError):
                m._fetch(dict(row, url='https://invalid.example/private'), m._Clock(), {'bytes': 0})
        opener.assert_not_called()
        for body, url, headers in [(b'bad!', row['url'], {}), (b'UNI', row['url'], {}),
                                   (b'UNIT!', row['url'], {}), (b'UNIT', row['url'] + '/extra', {}),
                                   (b'UNIT', row['url'], {'Content-Length': '5'}),
                                   (b'UNIT', row['url'], {'Content-Length': '-1'})]:
            response = Response(body, url, headers)
            with self.subTest(body=body, url=url, headers=headers), \
                    patch.object(m, '_open', return_value=response) as opener:
                with self.assertRaises(ValueError):
                    m._fetch(row, m._Clock(), {'bytes': 0})
                self.assertEqual(opener.call_count, 1)
                self.assertTrue(response.closed)

    def test_deadline_and_download_limit_preserve_primary_during_close(self):
        m = load()
        row = dict(m._manifest()['sources'][0])
        row.update(bytes=4, sha256=hashlib.sha256(b'UNIT').hexdigest())
        response = Response(b'UNIT', row['url'])
        original = ValueError('UNIT read primary')
        with patch.object(response, 'read', side_effect=original), \
                patch.object(response, 'close', side_effect=OSError('UNIT close secondary')), \
                patch.object(m, '_open', return_value=response):
            with self.assertRaises(ValueError) as raised:
                m._fetch(row, m._Clock(), {'bytes': 0})
        self.assertIs(raised.exception, original)
        response.close()
        with patch.object(m, '_open') as opener:
            with self.assertRaisesRegex(ValueError, 'DOWNLOAD_LIMIT'):
                m._fetch(row, m._Clock(), {'bytes': 16 << 20})
        opener.assert_not_called()
        with patch.object(m.time, 'monotonic', side_effect=[0., 600.]):
            clock = m._Clock()
            with self.assertRaisesRegex(ValueError, 'ACQUISITION_DEADLINE'):
                m._fetch(row, clock, {'bytes': 0})

    def run_unit(self, m, root, fail_at=None):
        actual_manifest = m._manifest()
        manifest = json.loads(json.dumps(actual_manifest))
        for row in manifest['sources']:
            row.update(bytes=4, sha256=hashlib.sha256(b'UNIT').hexdigest())
        calls = []
        clocks = []
        def observe(signed, name, stage, clock, tool):
            clocks.append(clock)
            calls.append(('verify', name))
            if fail_at == name:
                raise ValueError('UNIT_SIGNATURE_FAILURE')
            return {'scope': 'UNIT_FAKE_SIGNATURE', 'release': name, 'qualified': False}
        def index(observation, compressed, clock):
            clocks.append(clock)
            name = observation['release']
            packages = {key: dict(record) for key, record in manifest['expected_packages'].items()
                        if (key in ('libassuan0', 'libnpth0t64')) == (name == 'noble')}
            return {'scope': 'UNIT_FAKE_INDEX', 'packages': packages, 'qualified': False}
        real_deps = m._dependencies()
        deps = dict(real_deps)
        deps['ubuntu'] = SimpleNamespace(_observe_inrelease=observe, _package_index_data=index)
        def fetch(row, clock, total):
            calls.append(('fetch', row['url']))
            clocks.append(clock)
            total['bytes'] += 4
            return b'UNIT', {'bytes': 4, 'sha256': hashlib.sha256(b'UNIT').hexdigest(), 'url': row['url']}
        with patch.object(m, '_manifest', return_value=manifest), \
                patch.object(m, '_dependencies', return_value=deps), patch.object(m, '_fetch', fetch):
            result = m.collect(root / 'output', root / 'scratch')
        self.assertTrue(clocks)
        self.assertTrue(all(clock is clocks[0] for clock in clocks))
        self.assertFalse((root / 'scratch').exists())
        return result, calls

    def test_complete_observation_has_four_requests_two_verifications_and_inventory(self):
        m = load()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            result, calls = self.run_unit(m, root)
            self.assertEqual(result['outcome'], 'COMPLETE_METADATA_OBSERVATION')
            self.assertFalse(result['qualified'])
            self.assertEqual(result['cases'], 0)
            self.assertEqual(len([c for c in calls if c[0] == 'fetch']), 4)
            self.assertEqual([c[1] for c in calls if c[0] == 'verify'],
                             ['noble', 'snapshot-20261012-noble-updates'])
            outputs = list((root / 'output').rglob('*'))
            self.assertTrue(all(p.stat().st_size <= 65536 for p in outputs if p.is_file()))
            terminal = json.loads((root / 'output/reports/status.json').read_text())
            self.assertEqual(terminal['outcome'], result['outcome'])
            self.assertFalse(any(p.suffix in ('.deb', '.asc', '.gz') for p in outputs))
            inventories = sorted((root / 'output/inventory').glob('files.json.*'))
            inv = json.loads(b''.join(p.read_bytes() for p in inventories))
            for row in inv['files']:
                data = (root / 'output' / row['path']).read_bytes()
                self.assertEqual(len(data), row['bytes'])
                self.assertEqual(hashlib.sha256(data).hexdigest(), row['sha256'])

    def test_second_signature_failure_never_publishes_complete_package_inventory(self):
        m = load()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            result, _ = self.run_unit(m, root, 'snapshot-20261012-noble-updates')
            self.assertEqual(result['outcome'], 'PARTIAL_UNSEALED')
            self.assertFalse(result['qualified'])
            self.assertFalse((root / 'output/reports/packages.json.part0000').exists())
            self.assertEqual(result['phase'], 'VERIFY_RELEASE')
            self.assertEqual(result['cause'], 'ACQUISITION_REFUSED')

    def test_existing_workspace_and_output_are_preserved(self):
        m = load()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            scratch = root / 'scratch'
            scratch.mkdir()
            (scratch / 'unrelated').write_bytes(b'preserve')
            with patch.object(m, '_fetch') as fetch:
                with self.assertRaises(FileExistsError):
                    m.collect(root / 'output', scratch)
            fetch.assert_not_called()
            self.assertEqual((scratch / 'unrelated').read_bytes(), b'preserve')

    def test_dependency_identity_is_checked_before_transport(self):
        m = load()
        with patch.object(m, 'UBUNTU_SHA256', '0' * 64):
            with self.assertRaisesRegex(ValueError, 'DEPENDENCY_IDENTITY'):
                m._dependencies()

    def test_denied_redirect_closes_its_owned_response(self):
        m = load()
        response = Response(b'not read', 'https://invalid.example/')
        with self.assertRaisesRegex(ValueError, 'REDIRECT_REFUSED'):
            m._NoRedirect().redirect_request(None, response, 302, 'UNIT', {}, 'https://invalid.example/')
        self.assertTrue(response.closed)

    def test_source_read_keeps_primary_when_descriptor_close_also_fails(self):
        m = load()
        original = RuntimeError('UNIT source read')
        real_close = os.close
        closed = []
        def close(fd):
            real_close(fd)
            closed.append(fd)
            raise OSError('UNIT secondary')
        with patch.object(m.os, 'read', side_effect=original), patch.object(m.os, 'close', close):
            with self.assertRaises(RuntimeError) as raised:
                m._read_source(SCRIPT)
        self.assertIs(raised.exception, original)
        self.assertEqual(len(closed), 1)

    def test_expiry_during_first_verification_uses_downloads_original_clock(self):
        m = load()
        now = [0.]
        dependencies = m._dependencies()
        manifest = m._manifest()
        calls = []
        def fetch(row, clock, total):
            now[0] += 149
            clock.check()
            calls.append(row['url'])
            total['bytes'] += 4
            return b'UNIT', {'bytes': 4, 'sha256': hashlib.sha256(b'UNIT').hexdigest()}
        def observe(signed, name, stage, clock, tool):
            self.assertEqual(clock.deadline, 600.)
            now[0] += 5
            clock.check()
            raise AssertionError('expired verifier must not return')
        dependencies['ubuntu'] = SimpleNamespace(_observe_inrelease=observe)
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(m.time, 'monotonic', lambda: now[0]), \
                patch.object(m, '_dependencies', return_value=dependencies), \
                patch.object(m, '_manifest', return_value=manifest), patch.object(m, '_fetch', fetch):
            root = Path(directory)
            result = m.collect(root / 'output', root / 'scratch')
            self.assertEqual(len(calls), 4)
            self.assertEqual(result['outcome'], 'PARTIAL_UNSEALED')
            self.assertEqual(result['phase'], 'VERIFY_RELEASE')
            self.assertFalse((root / 'scratch').exists())
            self.assertFalse((root / 'output/reports/packages.json.part0000').exists())


if __name__ == '__main__':
    unittest.main()
