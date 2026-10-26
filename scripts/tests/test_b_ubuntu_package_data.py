"""Small acquisition-mechanics tests; fake verifier never authenticates a runtime."""
import gzip
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts/b-ubuntu-package-data.py'
PRIMARY = 'F6ECB3762474EDA9D21B7022871920D1991BC93C'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def load():
    if not SCRIPT.is_file():
        raise AssertionError('bounded Ubuntu signed-metadata bootstrap is missing')
    spec = importlib.util.spec_from_file_location('ubuntu_package_data_unit', SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def package_index(version='2.4.4-2ubuntu17.6', architecture='amd64'):
    first = (f'Package: gpgv\nVersion: {version}\nArchitecture: {architecture}\n'
            'Filename: pool/main/g/gnupg2/unit.deb\nSize: 4\n'
            f'SHA256: {digest(b"unit")}\nDescription: synthetic package\n continuation\n\n').encode()
    others = [('libc6', '2.39-0ubuntu8.9'), ('zlib1g', '1:1.3.dfsg-3.1ubuntu2.2'),
              ('libbz2-1.0', '1.0.8-5.1ubuntu0.1'), ('libgcrypt20', '1.10.3-2ubuntu0.2'),
              ('libgpg-error0', '1.47-3build2.1')]
    return first + b''.join((f'Package: {name}\nVersion: {value}\nArchitecture: amd64\n'
                             'Filename: pool/main/u/unit/unit.deb\nSize: 4\n'
                             f'SHA256: {digest(b"unit")}\n\n').encode() for name, value in others)


def release(index, suite='noble-updates', extra=''):
    compressed = gzip.compress(index, mtime=0)
    plain = (f'Origin: Ubuntu\nLabel: Ubuntu\nSuite: {suite}\nCodename: noble\n'
             'Date: Sun, 11 Oct 2026 19:48:57 UTC\nArchitectures: amd64 arm64\n'
             f'Components: main\n{extra}SHA256:\n'
             f' {digest(compressed)} {len(compressed)} main/binary-amd64/Packages.gz\n'
             f' {digest(index)} {len(index)} main/binary-amd64/Packages\n').encode()
    signed = (b'-----BEGIN PGP SIGNED MESSAGE-----\nHash: SHA512\n\n'
              + plain + b'-----BEGIN PGP SIGNATURE-----\n\nUNIT\n-----END PGP SIGNATURE-----\n')
    return signed, plain, compressed


class UbuntuPackageDataTest(unittest.TestCase):
    def gzip_observation(self, m, expanded, compressed):
        signed, plain, _ = release(expanded)
        observation = self.observe(m, signed, plain)
        observation['indexes']['main/binary-amd64/Packages.gz'] = {
            'bytes': len(compressed), 'sha256': digest(compressed)}
        return observation

    def test_signed_nonempty_plus_empty_gzip_member_is_accepted(self):
        m = load()
        expanded = package_index()
        compressed = gzip.compress(expanded, mtime=0) + gzip.compress(b'', mtime=0)
        self.assertEqual(len(gzip.compress(b'', mtime=0)), 20)
        observed = self.gzip_observation(m, expanded, compressed)
        result = m.package_index_data(observed, compressed)
        self.assertEqual(result['expanded_bytes'], len(expanded))
        self.assertEqual(result['expanded_sha256'], digest(expanded))
        self.assertEqual(result['compressed_bytes'], len(compressed))
        self.assertEqual(result['compressed_sha256'], digest(compressed))
        self.assertEqual(set(result['packages']), set(m.EXPECTED_PACKAGES[observed['release']]))
        self.assertFalse(result['qualified'])

    def test_two_members_can_split_records_but_signed_whole_hashes_remain_required(self):
        m = load()
        expanded = package_index()
        mid = 17
        compressed = gzip.compress(expanded[:mid], mtime=0) + gzip.compress(expanded[mid:], mtime=0)
        observed = self.gzip_observation(m, expanded, compressed)
        self.assertEqual(m.package_index_data(observed, compressed)['expanded_sha256'], digest(expanded))
        observed['indexes']['main/binary-amd64/Packages']['sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, '^PACKAGE_INDEX_EXPANDED_HASH$'):
            m.package_index_data(observed, compressed)
        observed['indexes']['main/binary-amd64/Packages.gz']['sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, '^PACKAGE_INDEX_COMPRESSED_HASH$'):
            m.package_index_data(observed, compressed)

    def test_concatenated_gzip_rejects_junk_truncation_crc_and_excess_members(self):
        m = load()
        expanded = package_index()
        first = gzip.compress(expanded, mtime=0)
        empty = gzip.compress(b'', mtime=0)
        corrupt = bytearray(empty); corrupt[-8] ^= 1
        for suffix in (b'junk', empty[:-1], bytes(corrupt), empty + empty):
            compressed = first + suffix
            observed = self.gzip_observation(m, expanded, compressed)
            with self.subTest(suffix=suffix.hex()), self.assertRaisesRegex(ValueError, '^PACKAGE_GZIP'):
                m.package_index_data(observed, compressed)

    def test_concatenated_gzip_has_one_total_expansion_budget_and_original_clock(self):
        m = load()
        expanded = package_index()
        compressed = gzip.compress(b'x' * (4 << 20), mtime=0) + gzip.compress(b'x' * ((4 << 20) + 1), mtime=0)
        observed = self.gzip_observation(m, expanded, compressed)
        with self.assertRaisesRegex(ValueError, '^PACKAGE_GZIP_EXTENT$'):
            m.package_index_data(observed, compressed)
        compressed = gzip.compress(expanded, mtime=0) + gzip.compress(b'', mtime=0)
        observed = self.gzip_observation(m, expanded, compressed)
        primary = InterruptedError('UNIT original clock cancellation')
        class Clock:
            calls = 0
            def check(self):
                self.calls += 1
                if self.calls == 4:
                    raise primary
        clock = Clock()
        real = m.zlib.decompressobj
        with patch.object(m.zlib, 'decompressobj', wraps=real) as decoder:
            with self.assertRaises(InterruptedError) as raised:
                m._package_index_data(observed, compressed, clock)
        self.assertIs(raised.exception, primary)
        self.assertEqual(decoder.call_count, 1)
        self.assertEqual(clock.calls, 4)

    def test_source_derived_clearsign_plaintext_status_is_accepted(self):
        m = load()
        signed, plain, _ = release(package_index())
        status = (f'[GNUPG:] PLAINTEXT 74 0 \n[GNUPG:] NEWSIG\n'
                  f'[GNUPG:] VALIDSIG {PRIMARY} 2026-10-11 1791748137 0 4 0 1 10 01 {PRIMARY}\n').encode()
        result = self.observe(m, signed, plain, callback=lambda argv, clock: (0, status))
        self.assertEqual(result['plaintext_status'], {'mode_hex': '74', 'timestamp': 0, 'filename': '',
                         'length_status': 'ABSENT_PARTIAL_CLEARSIGN', 'consumed_bytes': len(plain)})

    def test_plaintext_status_refuses_other_mode_time_name_length_missing_and_duplicate(self):
        m = load()
        signed, plain, _ = release(package_index())
        valid = f'[GNUPG:] VALIDSIG {PRIMARY} 2026-10-11 1791748137 0 4 0 1 10 01 {PRIMARY}\n'.encode()
        rows = (b'', b'[GNUPG:] PLAINTEXT 62 0 \n', b'[GNUPG:] PLAINTEXT 74 1 \n',
                b'[GNUPG:] PLAINTEXT 74 0 name\n', b'[GNUPG:] PLAINTEXT 74 0\n',
                b'[GNUPG:] PLAINTEXT 74 0 \n'*2,
                b'[GNUPG:] PLAINTEXT 74 0 \n[GNUPG:] PLAINTEXT_LENGTH 352\n')
        for row in rows:
            with self.subTest(row=row), self.assertRaisesRegex(ValueError, '^UBUNTU_PLAINTEXT_STATUS$'):
                self.observe(m, signed, plain, callback=lambda argv, clock: (0, row+valid))
            self.assertFalse((Path(self.tmp.name) / 'owned').exists())

    def test_bounded_reader_rejects_growth_replacement_link_truncation_and_oversize(self):
        m = load()
        path = Path(self.tmp.name) / 'read-input'
        foreign = Path(self.tmp.name) / 'unrelated'
        foreign.write_bytes(b'keep')
        clock = SimpleNamespace(check=lambda: None)
        real_read = m.os.read
        for mode in ('growth', 'replacement', 'link', 'truncated', 'oversized', 'initial-link'):
            with self.subTest(mode=mode):
                path.unlink(missing_ok=True)
                path.write_bytes(b'four')
                if mode == 'oversized': path.write_bytes(b'large')
                if mode == 'initial-link':
                    path.unlink()
                    path.symlink_to(foreign)
                calls = []
                def read(fd, count):
                    calls.append(count)
                    if mode == 'truncated': return b''
                    value = real_read(fd, count)
                    if len(calls) == 1:
                        if mode == 'growth':
                            with path.open('ab') as stream: stream.write(b'x')
                        elif mode == 'replacement':
                            moved = path.with_name('old-read-input')
                            path.rename(moved)
                            path.write_bytes(b'four')
                        elif mode == 'link':
                            path.unlink()
                            path.symlink_to(foreign)
                    return value
                with patch.object(m.os, 'read', read):
                    with self.assertRaises((ValueError, OSError)):
                        m._read_file(path, clock, 4, True)
                self.assertTrue(all(1 <= n <= 5 for n in calls))
                if mode in ('oversized', 'initial-link'): self.assertEqual(calls, [])
                self.assertEqual(foreign.read_bytes(), b'keep')

    def test_public_key_actual_read_is_capped_at_fixed1660_plus_discriminator(self):
        m = load()
        real_read = m.os.read
        counts = []
        def read(fd, count):
            counts.append(count)
            return real_read(fd, count)
        with patch.object(m.os, 'read', read):
            self.assertEqual(len(m._public_ring()), 1167)
        self.assertTrue(counts)
        self.assertTrue(all(1 <= n <= 1661 for n in counts))

    def test_fixed_two_key_shape_never_invokes_foreign_key_equality(self):
        m = load()
        class Key:
            def __hash__(self): return hash('noble')
            def __eq__(self, other): raise AssertionError('foreign key equality')
        names = ('noble', 'snapshot-20261012-noble-updates')
        for which in (0, 1):
            inputs = [dict.fromkeys(names), dict.fromkeys(names)]
            inputs[which] = {Key(): None, names[1]: None}
            with patch.object(m, '_Clock') as clock, patch.object(m, '_observe_inrelease') as verifier:
                with self.assertRaisesRegex(ValueError, '^RELEASE_SET$'):
                    m.ubuntu_package_set_data(*inputs, '/unused')
            clock.assert_not_called()
            verifier.assert_not_called()

    def test_stamp_preserves_read_or_clock_primary_through_close_and_propagates_close_only(self):
        m = load()
        path = Path(self.tmp.name) / 'stamp-input'
        path.write_bytes(b'bounded')
        real_close, real_read = m.os.close, m.os.read
        for where in ('read', 'clock', 'none'):
            with self.subTest(where=where):
                primary, secondary = RuntimeError('UNIT read/check primary'), OSError('UNIT close secondary')
                calls, checks = [], [0]
                def close(fd):
                    calls.append(fd)
                    real_close(fd)
                    raise secondary
                def read(fd, count):
                    if where == 'read':
                        raise primary
                    return real_read(fd, count)
                def check():
                    checks[0] += 1
                    if where == 'clock' and checks[0] == 2:
                        raise primary
                with patch.object(m.os, 'close', close), patch.object(m.os, 'read', read):
                    with self.assertRaises(BaseException) as caught:
                        m._stamp(path, SimpleNamespace(check=check), 1024)
                self.assertIs(caught.exception, secondary if where == 'none' else primary)
                self.assertEqual(len(calls), 1)
                with self.assertRaises(OSError):
                    m.os.fstat(calls[0])

    def test_owned_writers_preserve_write_primary_and_close_once_without_publication(self):
        m = load()
        signed, plain, _ = release(package_index())
        original_open = Path.open
        for leaf in ('InRelease', 'ubuntu.gpg'):
            for write_fails in (True, False):
                with self.subTest(leaf=leaf, write_fails=write_fails):
                    primary, secondary = RuntimeError('UNIT write primary'), OSError('UNIT writer close')
                    closes = []
                    class Stream:
                        def __init__(self, stream): self.stream = stream
                        def __enter__(self): return self
                        def __exit__(self, *unused): self.close()
                        def write(self, data):
                            if write_fails: raise primary
                            return self.stream.write(data)
                        def close(self):
                            closes.append(leaf)
                            self.stream.close()
                            raise secondary
                    def opened(path, mode='r', *args, **kwargs):
                        stream = original_open(path, mode, *args, **kwargs)
                        return Stream(stream) if path.name == leaf and mode == 'xb' else stream
                    with patch.object(Path, 'open', opened):
                        with self.assertRaises(BaseException) as caught:
                            self.observe(m, signed, plain)
                    self.assertIs(caught.exception, primary if write_fails else secondary)
                    self.assertEqual(closes, [leaf])
                    self.assertFalse((Path(self.tmp.name) / 'owned').exists())

    def test_consumed_plaintext_never_uses_an_unbounded_following_path_read(self):
        m = load()
        signed, plain, _ = release(package_index())
        original = Path.read_bytes
        def read(path):
            if path.name == 'verified.Release':
                raise AssertionError('actual plaintext used following unbounded read_bytes')
            return original(path)
        with patch.object(Path, 'read_bytes', read):
            result = self.observe(m, signed, plain)
        self.assertEqual(result['plaintext_bytes'], len(plain))

    def test_complete_set_refuses_cardinality_before_sets_or_arbitrary_key_methods(self):
        m = load()
        names = ('noble', 'snapshot-20261012-noble-updates')
        with patch.object(m, 'set', side_effect=AssertionError('unbounded caller key set'), create=True), \
                patch.object(m, '_Clock') as clock, patch.object(m, '_observe_inrelease') as verifier:
            with self.assertRaisesRegex(ValueError, '^RELEASE_SET$'):
                m.ubuntu_package_set_data(dict.fromkeys(range(3)), dict.fromkeys(names), '/unused')
        clock.assert_not_called()
        verifier.assert_not_called()

    def test_observation_preserves_actual_ring_input_and_fixed_invocation_profile(self):
        m = load()
        signed, plain, _ = release(package_index())
        result = self.observe(m, signed, plain)
        for role, raw in (('ring', b'UNIT public ring'), ('signed_input', signed)):
            self.assertIn(role+'_before', result)
            self.assertEqual(result[role+'_before'], result[role+'_after'])
            self.assertEqual(result[role+'_before']['bytes'], len(raw))
            self.assertEqual(result[role+'_before']['sha256'], digest(raw))
            self.assertEqual(len(result[role+'_before']['identity']), 8)
        self.assertEqual(result['verification_invocation'], {
            'protocol': 'UBUNTU2018_OWNED_HOME_CLEARSIGN_1',
            'argv_roles': ['EXISTING_CI_GPGV', '--homedir', 'OWNED_EMPTY_HOME', '--status-fd', '1',
                           '--keyring', 'OWNED_RING', '--output', 'OWNED_PLAINTEXT', 'OWNED_SIGNED_INPUT'],
            'environment': {'LANG': 'C', 'LC_ALL': 'C'}})
        self.assertNotIn(self.tmp.name, str(result))

    def test_exact_retained_ubuntu_key_transcription(self):
        raw = load()._public_ring()
        self.assertEqual(len(raw), 1167)
        self.assertEqual(digest(raw), '5ebbeeb474034b1fa7e50abbe6f136e177fc826219e01f314b3623f7b3097e96')

    def test_missing_actual_bootstrap_is_not_replaced_by_an_observed_hash(self):
        m = load()
        self.assertTrue(callable(m.ubuntu_release_data))
        self.assertTrue(callable(m.package_index_data))

    def observe(self, m, signed, plain, *, callback=None):
        """Patch only OS verification and retained PUBLIC identities for a UNIT input."""
        stage = Path(self.tmp.name) / 'owned'
        tool = Path(self.tmp.name) / 'fake-gpgv'
        tool.write_bytes(b'UNIT tool never executed')
        profile = dict(m.RELEASE_PROFILES['snapshot-20261012-noble-updates'])
        profile.update(bytes=len(signed), sha256=digest(signed))

        def fake(argv, clock):
            self.assertEqual(argv[1:3], ('--homedir', str(stage / 'home')))
            self.assertEqual(argv[3:5], ('--status-fd', '1'))
            self.assertEqual(argv[5], '--keyring')
            self.assertEqual(argv[7], '--output')
            self.assertEqual(Path(argv[9]).read_bytes(), signed)
            self.assertEqual(list((stage / 'home').iterdir()), [])
            Path(argv[8]).write_bytes(plain)
            if callback:
                return callback(argv, clock)
            return 0, (f'[GNUPG:] PLAINTEXT 74 0 \n[GNUPG:] NEWSIG\n[GNUPG:] GOODSIG 871920D1991BC93C UNIT\n'
                       f'[GNUPG:] VALIDSIG {PRIMARY} 2026-10-11 1791748137 0 4 0 1 10 01 {PRIMARY}\n').encode()

        with patch.dict(m.RELEASE_PROFILES, {'snapshot-20261012-noble-updates': profile}), \
                patch.object(m, '_owned_verify', fake), \
                patch.object(m, '_public_ring', return_value=b'UNIT public ring'):
            result = m._observe_inrelease(signed, 'snapshot-20261012-noble-updates', stage,
                                         m._Clock(), tool)
        self.assertFalse(stage.exists(), 'owned transaction files must be cleaned')
        return result

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()

    def tearDown(self):
        self.tmp.cleanup()

    def test_actual_signed_bytes_output_and_exact_package_chain(self):
        m = load()
        index = package_index()
        signed, plain, compressed = release(index)
        result = self.observe(m, signed, plain)
        self.assertEqual(result['signed_sha256'], digest(signed))
        self.assertEqual(result['plaintext_sha256'], digest(plain))
        self.assertEqual(result['primary'], PRIMARY)
        self.assertFalse(result['qualified'])
        self.assertFalse(result['current_repository_freshness'])
        self.assertEqual(result['valid_until_policy'], 'HISTORICAL_ABSENCE_EXPLICIT')
        records = m.package_index_data(result, compressed)
        self.assertEqual(records['packages']['gpgv']['Version'], '2.4.4-2ubuntu17.6')
        self.assertEqual(records['packages']['gpgv']['SHA256'], digest(b'unit'))
        self.assertFalse(records['qualified'])
        self.assertEqual(records['target_executions'], 0)

    def test_substituted_plaintext_is_rejected_after_successful_verifier(self):
        m = load()
        signed, plain, _ = release(package_index())
        with self.assertRaisesRegex(ValueError, 'SIGNED_PLAINTEXT_MISMATCH'):
            self.observe(m, signed, plain.replace(b'Origin: Ubuntu', b'Origin: Forged'))

    def test_verifier_failure_wrong_primary_and_bad_status_refuse(self):
        m = load()
        signed, plain, _ = release(package_index())
        for code, status in [(1, b''), (0, b'[GNUPG:] VALIDSIG ' + b'0' * 40 + b'\n'),
                             (0, b'[GNUPG:] BADSIG 123 UNIT\n'),
                             (0, b'[GNUPG:] VALIDSIG ' + PRIMARY.encode() + b'\n')]:
            with self.subTest(code=code, status=status):
                with self.assertRaises(ValueError):
                    self.observe(m, signed, plain, callback=lambda a, c: (code, b'[GNUPG:] PLAINTEXT 74 0 \n'+status))

    def test_signed_input_ring_tool_and_home_mutation_refuse(self):
        m = load()
        signed, plain, _ = release(package_index())
        for which in ('input', 'ring', 'tool', 'home'):
            def mutate(argv, clock):
                if which == 'home':
                    (Path(argv[2]) / 'unexpected').write_bytes(b'x')
                else:
                    at = {'input': 9, 'ring': 6, 'tool': 0}[which]
                    Path(argv[at]).write_bytes(b'changed')
                return 0, (f'[GNUPG:] VALIDSIG {PRIMARY} 2026-10-11 1791748137 0 4 0 1 10 01 {PRIMARY}\n').encode()
            with self.subTest(which=which), self.assertRaises(ValueError):
                self.observe(m, signed, plain, callback=mutate)

    def test_wrong_suite_date_expiry_and_duplicate_release_fields_refuse(self):
        m = load()
        for suite, extra in [('noble-security', ''), ('noble-updates', 'Origin: Ubuntu\n'),
                             ('noble-updates', 'Valid-Until: Sat, 10 Oct 2026 00:00:00 UTC\n')]:
            signed, plain, _ = release(package_index(), suite, extra)
            with self.subTest(suite=suite, extra=extra), self.assertRaises(ValueError):
                self.observe(m, signed, plain)
        signed, plain, _ = release(package_index())
        for date in (b'Tue, 13 Oct 2026 00:00:00 UTC', b'Thu, 01 Oct 2026 00:00:00 UTC'):
            changed = plain.replace(b'Sun, 11 Oct 2026 19:48:57 UTC', date)
            signed = release(package_index())[0].replace(plain, changed)
            with self.assertRaises(ValueError):
                self.observe(m, signed, changed)

    def test_gzip_hash_expansion_extra_members_and_selected_record_identity_refuse(self):
        m = load()
        signed, plain, compressed = release(package_index())
        result = self.observe(m, signed, plain)
        for data in (compressed[:-1], compressed + compressed, b'bad'):
            with self.subTest(size=len(data)), self.assertRaises(ValueError):
                m.package_index_data(result, data)
        for index in (package_index(version='2.4.4-other'), package_index(architecture='arm64'),
                      package_index() + package_index(),
                      package_index().replace(b'pool/main/g/gnupg2/unit.deb', b'../escape.deb')):
            signed, plain, data = release(index)
            result = self.observe(m, signed, plain)
            with self.assertRaises(ValueError):
                m.package_index_data(result, data)

    def test_cleartext_dash_escape_and_signature_framing_are_exact(self):
        m = load()
        signed, plain, _ = release(package_index())
        escaped = signed.replace(b'Origin: Ubuntu', b'- -unit\nOrigin: Ubuntu')
        self.assertEqual(m._cleartext(escaped), b'-unit\n' + plain)
        for data in (signed + b'extra', signed.replace(b'Hash: SHA512', b'Hash: SHA1'),
                     signed.replace(b'Origin: Ubuntu', b'-undashed'),
                     signed.replace(b'\n', b'\r\n')):
            with self.subTest(size=len(data)), self.assertRaises(ValueError):
                m._cleartext(data)

    def test_clock_is_original_and_terminal_failure_does_not_reset_it(self):
        m = load()
        with patch.object(m.time, 'monotonic', side_effect=[100.0, 699.99, 700.0, 701.0]):
            clock = m._Clock()
            self.assertAlmostEqual(clock.remaining(), .01)
            for _ in range(2):
                with self.assertRaisesRegex(ValueError, 'ACQUISITION_DEADLINE'):
                    clock.check()

    def test_complete_chain_carries_one_clock_through_verifier_and_index(self):
        m = load()
        observed = []
        clock = object()
        def observe(signed, name, stage, original, tool):
            self.assertIs(original, clock)
            observed.append(('observe', signed, name, Path(tool)))
            return {'UNIT': True}
        def index(observation, compressed, original):
            self.assertIs(original, clock)
            self.assertEqual(observation, {'UNIT': True})
            observed.append(('index', compressed))
            return {'UNIT packages': True}
        with patch.object(m, '_Clock', return_value=clock) as constructor, \
                patch.object(m, '_observe_inrelease', observe), \
                patch.object(m, '_package_index_data', index):
            result = m.ubuntu_package_data(b'signed', b'index', 'noble', Path(self.tmp.name) / 'owned')
        constructor.assert_called_once_with()
        self.assertEqual(observed, [('observe', b'signed', 'noble', Path('/usr/bin/gpgv')),
                                    ('index', b'index')])
        self.assertFalse(result['qualified'])

    def test_existing_stage_and_unrelated_files_are_preserved(self):
        m = load()
        signed, _, _ = release(package_index())
        stage = Path(self.tmp.name) / 'owned'
        stage.mkdir()
        unrelated = stage / 'unrelated'
        unrelated.write_bytes(b'preserve')
        profile = dict(m.RELEASE_PROFILES['snapshot-20261012-noble-updates'])
        profile.update(bytes=len(signed), sha256=digest(signed))
        with patch.dict(m.RELEASE_PROFILES, {'snapshot-20261012-noble-updates': profile}), \
                patch.object(m, '_owned_verify') as verifier:
            with self.assertRaises(FileExistsError):
                m._observe_inrelease(signed, 'snapshot-20261012-noble-updates', stage,
                                     m._Clock(), Path('/never-executed'))
        verifier.assert_not_called()
        self.assertEqual(unrelated.read_bytes(), b'preserve')

    def test_wrong_retained_source_never_creates_stage_or_runs_a_tool(self):
        m = load()
        signed, _, _ = release(package_index())
        stage = Path(self.tmp.name) / 'owned'
        with patch.object(m, '_owned_verify') as verifier:
            with self.assertRaisesRegex(ValueError, 'RELEASE_SOURCE'):
                m._observe_inrelease(signed, 'noble', stage, m._Clock(), Path('/never-executed'))
        verifier.assert_not_called()
        self.assertFalse(stage.exists())

    def test_owned_runner_uses_fixed_environment_and_original_remaining_budget(self):
        m = load()
        stage = Path(self.tmp.name)
        home = stage / 'home'
        home.mkdir()
        argv = ('/usr/bin/gpgv', '--homedir', str(home), '--status-fd', '1', '--keyring',
                str(stage / 'ring'), '--output', str(stage / 'plain'), str(stage / 'signed'))
        clock = SimpleNamespace(remaining=lambda: 12.5, check=lambda: None)
        def owned(command, environment, logs, timeout, cancel):
            self.assertEqual(command, argv)
            self.assertEqual(environment, {'LANG': 'C', 'LC_ALL': 'C'})
            self.assertEqual(timeout, 12.5)
            self.assertIs(cancel, clock.check)
            self.assertEqual(logs, stage / 'verifier-output')
            return 0, b'status', b'not exported'
        with patch.object(m, '_module', return_value=SimpleNamespace(_owned_tool=owned)):
            self.assertEqual(m._owned_verify(argv, clock), (0, b'status'))

    def test_static_release_historical_policy_has_no_snapshot_age_rule(self):
        m = load()
        _, plain, _ = release(package_index(), suite='noble')
        plain = plain.replace(b'Sun, 11 Oct 2026 19:48:57 UTC', b'Thu, 25 Apr 2024 15:10:33 UTC')
        _, _, policy = m._release_fields(plain, 'noble')
        self.assertEqual(policy, 'HISTORICAL_ABSENCE_EXPLICIT')

    def test_expanded_hash_and_bomb_are_bounded_independently(self):
        m = load()
        signed, plain, compressed = release(package_index())
        observation = self.observe(m, signed, plain)
        observation['indexes']['main/binary-amd64/Packages']['sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'PACKAGE_INDEX_EXPANDED_HASH'):
            m.package_index_data(observation, compressed)
        bomb = gzip.compress(b'x' * ((8 << 20) + 1), mtime=0)
        observation['indexes']['main/binary-amd64/Packages.gz'] = {'bytes': len(bomb), 'sha256': digest(bomb)}
        with self.assertRaisesRegex(ValueError, 'PACKAGE_GZIP_EXTENT'):
            m.package_index_data(observation, bomb)

    def test_primary_failure_survives_cleanup_failure(self):
        m = load()
        signed, plain, _ = release(package_index())
        original = RuntimeError('UNIT primary')
        def fail(argv, clock):
            raise original
        with patch.object(m.shutil, 'rmtree', side_effect=OSError('UNIT secondary')):
            with self.assertRaises(RuntimeError) as raised:
                self.observe(m, signed, plain, callback=fail)
        self.assertIs(raised.exception, original)

    def test_complete_two_release_set_uses_one_clock_and_rejects_partial_input(self):
        m = load()
        self.assertTrue(hasattr(m, 'ubuntu_package_set_data'), 'complete eight-package join is missing')
        names = ('noble', 'snapshot-20261012-noble-updates')
        clock = object()
        calls = []
        def observe(signed, name, stage, original, tool):
            self.assertIs(original, clock)
            calls.append(name)
            return {'release': name}
        def index(observation, compressed, original):
            self.assertIs(original, clock)
            name = observation['release']
            return {'packages': {key: {'Version': value} for key, value in m.EXPECTED_PACKAGES[name].items()}}
        stage = Path(self.tmp.name) / 'complete'
        with patch.object(m, '_Clock', return_value=clock) as constructor, \
                patch.object(m, '_observe_inrelease', observe), \
                patch.object(m, '_package_index_data', index):
            result = m.ubuntu_package_set_data(dict.fromkeys(names, b'signed'),
                                               dict.fromkeys(names, b'index'), stage)
        constructor.assert_called_once_with()
        self.assertEqual(calls, list(names))
        self.assertEqual(len(result['packages']), 8)
        self.assertFalse(result['qualified'])
        self.assertFalse(stage.exists())
        with patch.object(m, '_observe_inrelease') as verifier:
            with self.assertRaisesRegex(ValueError, 'RELEASE_SET'):
                m.ubuntu_package_set_data({'noble': b'signed'}, {'noble': b'index'}, stage)
        verifier.assert_not_called()


if __name__ == '__main__':
    unittest.main()
