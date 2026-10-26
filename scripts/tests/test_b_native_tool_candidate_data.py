"""Fixed public native-candidate metadata; dummy candidates never execute."""
from pathlib import Path
import importlib.util
import hashlib
import json
import re
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class NativeCandidateDataTest(unittest.TestCase):
    def module(self):
        path = ROOT / 'scripts/b-native-tool-candidate-data.py'
        self.assertTrue(path.is_file(), 'fixed native-candidate collector is missing')
        return load(path, 'native_candidate_unit')

    def fixture(self, root):
        old = load(Path(__file__).with_name('test_b_tool_acquisition_data.py'), 'old_tool_unit')
        policy = old.fixture(root)
        native = policy['jdk'] / 'lib/server/libjvm.so'
        native.parent.mkdir()
        native.write_bytes(b'UNIT candidate bytes: must never execute')
        config = policy['jdk'] / 'lib/jvm.cfg'
        config.write_bytes(b'-server KNOWN\n')
        return old, policy, (('projector-vm', native, True), ('projector-vm-config', config, False))

    def test_actual_dummy_readers_keep_native_candidates_read_only_and_enriched(self):
        m = self.module()
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            old, policy, extra = self.fixture(root)
            out = root / 'out'
            result = m._acquire(out, policy, extra)
            self.assertEqual(result['outcome'], 'PARTIAL_UNSEALED')
            self.assertEqual(result['cleanup'], 'COMPLETE')
            self.assertIsNone(result['cause'])
            self.assertFalse(result['qualified'])
            self.assertEqual((result['cases'], result['candidateExecutions'], result['targetExecutions']), (0, 0, 0))
            rows = old.ToolDataTests().final_files(out)
            vm = rows[str(extra[0][1])]['record']
            self.assertIn('package', vm)
            self.assertEqual(vm['elf']['needed'], [])
            self.assertEqual(result['files'], result['finalFileSnapshots'])
            self.assertGreater(result['metadataExecutions'], 0)

    def test_new_soname_is_reported_without_searching_or_reading_its_file(self):
        m = self.module()
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            old, policy, extra = self.fixture(root)
            unknown = root / 'libs/libnotselected.so'
            unknown.write_bytes(b'not selected, not read' * 100)
            reader = policy['readers']['elf']
            reader.write_text(reader.read_text() +
                             "if p.name=='libjvm.so':print('0x9 (NEEDED) Shared library: [libnotselected.so]')\n")
            out = root / 'out'
            result = m._acquire(out, policy, extra)
            rows = old.ToolDataTests().final_files(out)
            self.assertNotIn(str(unknown), rows)
            graph = json.loads((out / 'reports/native-candidate-graph.json.part0000').read_bytes())
            self.assertTrue(any(row.get('name') == 'libnotselected.so' and
                                row.get('availability') == 'UNRESOLVED_NOT_ACQUIRED' for row in graph))
            self.assertIn('native-candidate-required-role-policy', result['missing'])

    def test_alias_roles_hash_the_same_file_once(self):
        m = self.module()
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            old, policy, extra = self.fixture(root)
            alias = extra[0][1].with_name('libvm-alias.so')
            alias.symlink_to(extra[0][1].name)
            extra = (*extra, ('projector-vm-alias', alias, True))
            out = root / 'out'
            result = m._acquire(out, policy, extra)
            rows = old.ToolDataTests().final_files(out)
            vm = rows[str(extra[0][1])]['record']
            self.assertEqual(len([x for x in rows if x == str(extra[0][1])]), 1)
            self.assertTrue(any(x['role'] == 'projector-vm-alias' for x in vm['observations']))
            self.assertEqual(result['files'], len(rows))

    def test_absent_fixed_candidate_is_explicit_and_never_promoted_to_a_required_file(self):
        m = self.module()
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            old, policy, extra = self.fixture(root)
            extra[0][1].unlink()
            out = root / 'out'
            result = m._acquire(out, policy, extra)
            roles = json.loads((out / 'reports/native-candidate-roles.json.part0000').read_bytes())
            self.assertEqual(roles['projector-vm']['availability'], 'ABSENT_CANDIDATE')
            self.assertFalse(roles['projector-vm']['requiredness_established'])
            self.assertEqual(result['outcome'], 'PARTIAL_UNSEALED')

    def test_dependency_drift_refuses_before_creating_output(self):
        m = self.module()
        with patch.object(m, 'SOURCE_SHA256', '0' * 64):
            with self.assertRaisesRegex(ValueError, '^NATIVE_CANDIDATE_DEPENDENCY_DRIFT$'):
                m._dependency()

    def test_inaccessible_candidate_is_not_reported_as_absent(self):
        m = self.module()
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            _, policy, extra = self.fixture(root)
            denied = extra[0][1]
            actual_stat, actual_exists, actual_link = Path.lstat, Path.exists, Path.is_symlink

            def lstat(path, *args, **kwargs):
                if path == denied:
                    raise PermissionError('UNIT denied candidate')
                return actual_stat(path, *args, **kwargs)

            # Newer pathlib versions may return False instead of propagating
            # access failures from exists()/is_symlink(). Absence needs lstat.
            with patch.object(Path, 'lstat', lstat), \
                    patch.object(Path, 'exists', lambda p: False if p == denied else actual_exists(p)), \
                    patch.object(Path, 'is_symlink', lambda p: False if p == denied else actual_link(p)):
                result = m._acquire(root / 'out', policy, extra)
            self.assertEqual(result['outcome'], 'PARTIAL_FAILURE')
            self.assertEqual(result['cause'], 'DATA_IO')

    def test_fixed_policy_has_no_arbitrary_scan_or_candidate_executable(self):
        m = self.module()
        paths = m.fixed_candidates(m.JDK)
        self.assertEqual(len(paths), 8)
        self.assertEqual(len({role for role, _, _ in paths}), 8)
        self.assertTrue(all(path.is_absolute() for _, path, _ in paths))
        self.assertEqual(sum(1 for _, _, elf in paths if elf), 5)
        self.assertNotIn('gpg', {role for role, _, _ in paths})
        for invalid in ('/arbitrary-string', Path('relative')):
            with self.subTest(root=invalid), self.assertRaisesRegex(ValueError, '^NATIVE_CANDIDATE_JDK_ROOT$'):
                m.fixed_candidates(invalid)

    def test_observed_needed_os_candidates_are_literal_and_root_bounded(self):
        m = self.module()
        self.assertTrue(hasattr(m, 'fixed_os_candidates'), 'observed needed OS candidates are missing')
        rows = m.fixed_os_candidates(Path('/'))
        self.assertEqual(rows, (
            ('projector-needed-math', Path('/usr/lib/x86_64-linux-gnu/libm.so.6'), True),
            ('projector-needed-realtime', Path('/usr/lib/x86_64-linux-gnu/librt.so.1'), True),
        ))
        self.assertEqual(m.fixed_os_candidates(Path('/unit')),
                         tuple((role, Path('/unit') / path.relative_to('/'), elf)
                               for role, path, elf in rows))
        for invalid in ('/', Path('relative')):
            with self.subTest(root=invalid), self.assertRaisesRegex(ValueError, '^NATIVE_CANDIDATE_OS_ROOT$'):
                m.fixed_os_candidates(invalid)

    def test_public_entry_adds_only_the_two_observed_needed_os_candidates(self):
        m = self.module()
        captured = []
        with patch.object(m, '_acquire', side_effect=lambda output, policy, rows, **kw:
                          captured.append(rows) or {'outcome': 'UNIT_INTERCEPTED'}):
            result = m.collect(Path('/unit-output-not-created'))
        self.assertEqual(result['outcome'], 'UNIT_INTERCEPTED')
        self.assertEqual(len(captured), 1)
        rows = captured[0]
        self.assertEqual(len(rows), 10, 'public collection must include both newly observed dependencies')
        self.assertEqual(rows[:8], m.fixed_candidates(m.JDK))
        self.assertEqual(tuple(path.name for _, path, _ in rows[8:]), ('libm.so.6', 'librt.so.1'))

    def test_dependency_setup_does_not_restart_the_original_six_hundred_seconds(self):
        m = self.module()
        dep = m._dependency()
        times = iter((0, 40))
        observed = []

        def clock():
            return next(times, 40)

        def stop_before_readers(session):
            observed.append(session.clock.deadline)
            raise dep.Failure('DATA_SCOPE')

        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            _, policy, extra = self.fixture(root)
            with patch.object(m, '_dependency', return_value=dep), patch.object(dep, '_run', stop_before_readers):
                result = m._acquire(root / 'out', policy, extra, clock=clock)
            self.assertEqual(observed, [600])
            self.assertEqual(result['cause'], 'DATA_SCOPE')
            self.assertEqual(result['metadataExecutions'], 0)

    def test_public_entry_starts_deadline_before_its_first_dependency_load(self):
        m = self.module()
        dep = m._dependency()
        clock = [0]
        seen = []

        def dependency():
            clock[0] = 40
            return dep

        def acquire(*args, **kwargs):
            seen.append(kwargs.get('started'))
            return {'scope': 'CONTROLLED_UNIT_DATA'}

        with patch.object(m, 'monotonic', side_effect=lambda: clock[0]), \
                patch.object(m, '_dependency', dependency), patch.object(m, '_acquire', acquire):
            m.collect(Path('/unused-unit-output'))
        self.assertEqual(seen, [0])

    def test_workflow_exposes_a_separate_fixed_manual_mode_and_only_public_reports(self):
        text = (ROOT / '.github/workflows/public-python-diagnostics.yml').read_text()
        self.assertIn('          - native-tool-candidates\n', text)
        self.assertIn("case \"$COLLECTION\" in boot-artifact|tool-metadata|native-tool-candidates|ubuntu-package-metadata)", text)
        for name, path in (('NATIVE_DATA_SHA256', 'scripts/b-native-tool-candidate-data.py'),
                           ('NATIVE_DATA_TEST_SHA256', 'scripts/tests/test_b_native_tool_candidate_data.py')):
            match = re.search(r'(?m)^          ' + name + r': ([0-9a-f]{64})$', text)
            self.assertIsNotNone(match, name)
            self.assertEqual(match[1], hashlib.sha256((ROOT / path).read_bytes()).hexdigest())
        steps = re.split(r'(?m)^      - ', text)[1:]
        collection = [s for s in steps if 'python3 scripts/b-native-tool-candidate-data.py --output ' in s]
        self.assertEqual(len(collection), 1)
        self.assertIn("if: ${{ inputs.collection == 'native-tool-candidates' }}", collection[0])
        self.assertIn('timeout --signal=TERM --kill-after=10s 610s ', collection[0])
        uploads = [s for s in steps if '\n          name: native-tool-candidates-public-data\n' in s]
        self.assertEqual(len(uploads), 1)
        self.assertIn("if: ${{ always() && inputs.collection == 'native-tool-candidates' }}", uploads[0])
        paths = re.search(r'(?m)^          path: \|\n((?:            [^\n]+\n)+)', uploads[0])
        self.assertIsNotNone(paths)
        self.assertEqual([line.strip() for line in paths[1].splitlines()], [
            '${{ runner.temp }}/native-tool-evidence-public/reports/',
            '${{ runner.temp }}/native-tool-evidence-public/inventory/'])

    def test_cancel_after_fixed_native_capture_keeps_truthful_failure_and_cleans_owned_stage(self):
        m = self.module()
        dep = m._dependency()
        session_type = dep._Session
        stopped = False

        def cancel():
            if stopped:
                raise RuntimeError('UNIT cancellation: never export arbitrary text')

        def session_factory(*args):
            session = session_type(*args)
            original = session.file

            def file(path, role):
                nonlocal stopped
                row = original(path, role)
                if role == 'projector-vm':
                    stopped = True
                return row

            session.file = file
            return session

        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            _, policy, extra = self.fixture(root)
            with patch.object(m, '_dependency', return_value=dep), patch.object(dep, '_Session', session_factory):
                result = m._acquire(root / 'out', policy, extra, cancel=cancel)
            self.assertEqual(result['outcome'], 'PARTIAL_FAILURE')
            self.assertEqual(result['cause'], 'DATA_CANCELLED_OR_INVALID')
            self.assertEqual(result['cleanup'], 'COMPLETE')
            self.assertEqual(result['finalFileSnapshots'], 0)
            self.assertEqual(list(root.glob('tool-metadata-*')), [])
            self.assertNotIn('UNIT cancellation', json.dumps(result))


if __name__ == '__main__':
    unittest.main()
