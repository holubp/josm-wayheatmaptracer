"""Capture-only historical isolation and detached neutral handoff contracts."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/run-b-historical-native-comparison.py"


def module():
    spec = importlib.util.spec_from_file_location("historical_native_capture", SCRIPT)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


class HistoricalNativeCaptureContracts(unittest.TestCase):
    def test_capture_only_or_pending_protocol_never_grants_engine_eligibility(self):
        capture = module()
        for protocol in ({'scope': capture.SCOPE}, {'scope': capture.ENGINE_SCOPE, 'approval': 'PENDING'}):
            with self.subTest(protocol=protocol), self.assertRaisesRegex(ValueError, 'engine protocol'):
                capture.require_engine_protocol(protocol)

    def test_engine_protocol_refuses_wrong_diagnostic_identity_or_changed_defaults(self):
        capture = module()
        control = {'scope': capture.ENGINE_SCOPE, 'approval': capture.APPROVAL, 'engineEligible': True,
                   'historicalCommit': capture.HISTORICAL, 'diagnosticParameterSha256': capture.DIAGNOSTIC_SHA,
                   'nativePolicy': [96, 8000000, 128000000, 32, 8], 'ledgerLimitBytes': 256 * 1024 * 1024}
        for key, value in (('diagnosticParameterSha256', '0' * 64), ('nativePolicy', [96, 8000000, 128000000, 8, 4]),
                           ('ledgerLimitBytes', 64 * 1024 * 1024)):
            changed = dict(control)
            changed[key] = value
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'engine protocol'):
                capture.require_engine_protocol(changed)

    def test_external_cutoff_carries_only_last_safe_phase_and_unknown_native_outcome(self):
        phase = {'phase': 'NATIVE_ENGINE_CALL_PENDING', 'nativeInvocationsAtCheckpoint': 0,
                 'nativeReturnsAtCheckpoint': 0, 'nativeCallMayBeInProgress': True}
        result = module().external_cutoff(phase)
        self.assertEqual('EXTERNALLY_CUT_OFF_NOT_A_NATIVE_RESULT', result.get('status'))
        self.assertEqual(phase, result.get('lastSafePhase'))
        for key in ('nativeStatus', 'engineReturned', 'finalRootBytes'):
            self.assertIsNone(result.get(key))
        self.assertEqual('UNKNOWN_AFTER_EXTERNAL_CUTOFF', result.get('nativeEntryCertainty'))
        self.assertIn('nativeNormalReturns', result)
        self.assertIsNone(result['nativeNormalReturns'])
        self.assertIs(result.get('qualityComparisonAvailable'), False)

    def test_process_log_overflow_is_terminal_with_explicit_bounded_prefix(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / 'attempt.log'
            causes = []
            with self.assertRaisesRegex(ValueError, 'log overflow'):
                module().run_bounded([sys.executable, '-c', 'import os; os.write(1,b"x"*65537)'],
                                     log, on_cutoff=causes.append)
            self.assertEqual(65536, log.stat().st_size)
            self.assertEqual(['PROCESS_LOG_EXPORT_OVERFLOW'], causes)

    def test_external_kill_after_before_call_marker_does_not_prove_native_entry(self):
        with tempfile.TemporaryDirectory() as directory:
            phase = Path(directory) / 'phase.json'
            script = ('import json,sys; from pathlib import Path; '
                      'Path(sys.argv[1]).write_text(json.dumps({"phase":"NATIVE_ENGINE_CALL_PENDING",'
                      '"nativeInvocationsAtCheckpoint":0,"nativeReturnsAtCheckpoint":0})); '
                      'print("READY",flush=True); sys.stdin.read()')
            with subprocess.Popen([sys.executable, '-c', script, str(phase)], stdin=subprocess.PIPE,
                                  stdout=subprocess.PIPE, text=True) as child:
                self.assertEqual('READY\n', child.stdout.readline())
                child.kill()
                child.wait(timeout=10)
            result = module().external_cutoff(json.loads(phase.read_text()))
            self.assertEqual('UNKNOWN_AFTER_EXTERNAL_CUTOFF', result['nativeEntryCertainty'])
            self.assertIsNone(result['nativeNormalReturns'])
            self.assertIsNone(result['nativeStatus'])
            self.assertIsNone(result['finalRootBytes'])

    def test_normal_process_output_and_nonzero_exit_preserve_actual_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / 'attempt.log'
            with self.assertRaises(subprocess.CalledProcessError):
                module().run_bounded([sys.executable, '-c', 'print("factual"); raise SystemExit(7)'], log)
            self.assertEqual(b'factual\n', log.read_bytes())

    def test_failed_process_still_revalidates_old_source_and_classpath(self):
        capture = module()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            attestation = {'source': {'commit': capture.HISTORICAL}, 'runtimeEntries': ['old-only'],
                           'adapterClasses': 'external-adapter', 'adapterSha256': 'fixed-tree'}
            raw = capture.canonical(attestation)
            path = root / 'attestation.json'
            path.write_bytes(raw)
            with patch.dict(os.environ, {'JAVA_HOME': '/bounded-jdk'}), \
                    patch.object(capture, 'verify_checkout', return_value=attestation['source']) as source, \
                    patch.object(capture, 'verify_classpath', return_value={'runtimeEntries': ['old-only']}) as cp, \
                    patch.object(capture, 'tree_sha', return_value='fixed-tree'), \
                    patch.object(capture, 'run_bounded', side_effect=subprocess.CalledProcessError(7, ['controlled'])):
                with self.assertRaises(subprocess.CalledProcessError):
                    capture.launch(root, path, capture.sha(raw), root / 'unused', '0' * 64, root,
                                   root / 'unused', root / 'output', ['connected-straight'])
                self.assertEqual(2, source.call_count)
                self.assertEqual(2, cp.call_count)

    def test_wrong_source_refuses_before_compilation(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            subprocess.run(["git", "init", "-q", path], check=True)
            with self.assertRaisesRegex(ValueError, "historical source"):
                module().verify_checkout(path)

    def test_current_classpath_refuses_even_if_names_match(self):
        with self.assertRaisesRegex(ValueError, "classpath"):
            module().verify_classpath(ROOT, [ROOT / "build/classes/java/main"])

    def test_declared_dependency_inventory_does_not_require_removed_josm_main_class(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            main = root / 'build/classes/java/main'
            main.mkdir(parents=True)
            (main / 'SyntheticInventoryOnly.class').write_bytes(b'not executable; inventory unit fixture')
            jar = root / 'josm-19555.jar'
            with zipfile.ZipFile(jar, 'w') as output:
                output.writestr('org/openstreetmap/josm/data/Version.class', b'inventory only')
            result = module().verify_classpath(root, [main, jar])
            self.assertEqual(str(main), result['mainClasses'])
            with zipfile.ZipFile(jar, 'a') as output:
                output.writestr('org/openstreetmap/josm/plugins/wayheatmaptracer/Injected.class', b'forbidden overlay')
            with self.assertRaisesRegex(ValueError, 'plugin overlay'):
                module().verify_classpath(root, [main, jar])

    def test_external_archive_pin_refuses_before_destination_creation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "input.zip"
            archive.write_bytes(b"not an authenticated archive")
            destination = root / "prepared"
            with self.assertRaisesRegex(ValueError, "archive"):
                module().prepare(archive, "0" * 64, destination, {}, "0" * 64)
            self.assertFalse(destination.exists())

    def test_external_protocol_pin_cannot_be_replaced_by_selfconsistent_index(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "input.zip"
            with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_STORED) as output:
                output.writestr("index.json", "{}")
            raw = archive.read_bytes()
            with self.assertRaisesRegex(ValueError, "protocol"):
                module().prepare(archive, hashlib.sha256(raw).hexdigest(), root / "prepared", {"indexSha256": "0" * 64}, "0" * 64)

    def test_metadata_parser_rejects_duplicate_deep_nonfinite_and_invalid_utf8(self):
        capture = module()
        for raw in (b'{"x":1,"x":2}', b'{"x":NaN}', b'{"x":1e999}', b'\xff', b'[' * 9 + b']' * 9):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                capture.metadata(raw)
        self.assertEqual(-1.0, capture.metadata(b'{"x":-1.0}')['x'])

    def test_preparation_rejects_duplicate_traversal_extra_and_oversized_before_extract(self):
        import io
        import warnings
        capture = module()
        for names in (['../escape'], ['unknown'], ['index.json', 'index.json']):
            raw = io.BytesIO()
            with warnings.catch_warnings(), zipfile.ZipFile(raw, 'w', compression=zipfile.ZIP_STORED) as archive:
                warnings.simplefilter('ignore', UserWarning)
                for name in names:
                    archive.writestr(name, '{}')
            with self.subTest(names=names), self.assertRaisesRegex(ValueError, 'archive'):
                capture.archive_entries(raw.getvalue())
        raw = io.BytesIO()
        with zipfile.ZipFile(raw, 'w', compression=zipfile.ZIP_STORED) as archive:
            archive.writestr('index.json', b' ' * 65537)
        with self.assertRaisesRegex(ValueError, 'archive'):
            capture.archive_entries(raw.getvalue())

    def test_capture_only_source_retains_no_numerical_entry_observer_and_separate_native_call(self):
        source = (ROOT / 'scripts/historical-b-native/HistoricalNativeB.java').read_text()
        self.assertEqual(1, source.count('.traceWithUsage('))
        self.assertIn('if(forbidden&&!allowEngine)', source)
        self.assertIn('Numerical entry in CAPTURE_ONLY', source)
        self.assertNotIn('ZipInputStream', source)
        self.assertIn('AttemptMemoryLedger.production()', source)
        self.assertIn('source.reserve(COLD);source.reserve(CAPTURE)', source)
        self.assertIn('Final root ownership', source)

    def test_class_tree_bounds_total_entries_depth_and_utf8_path_before_retention(self):
        # Fixed metadata contract: old946 entries/depth9/path156 bytes fit1024/16/256.
        for mutation in ('empty-directories', 'files', 'depth', 'utf8-path'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / 'one.class').write_bytes(b'one')
                if mutation in ('empty-directories', 'files'):
                    for index in range(1024):
                        path = root / ('entry-' + str(index))
                        path.mkdir() if mutation == 'empty-directories' else path.write_bytes(b'x')
                elif mutation == 'depth':
                    path = root
                    for index in range(17):
                        path = path / 'd'
                        path.mkdir()
                    (path / 'x.class').write_bytes(b'x')
                else:
                    path = root / ('x' * 100) / ('y' * 100)
                    path.mkdir(parents=True)
                    (path / ('z' * 60 + '.class')).write_bytes(b'x')
                with self.assertRaisesRegex(ValueError, 'classpath tree'):
                    module().tree_sha(root)


def adapter_guards(attestation_path, protocol_path, prepared, native_contract, output):
    """Run only refused old-JVM entries; never a scene capture or engine.

    The guard cases retain exact commands/receipts. Any changed
    input remains bound to the original externally supplied protocol digest.
    Tiny actual capture, cancellation and funding tests are in Java SELF_TEST.
    """
    check = unittest.TestCase()
    attestation_path, protocol_path, prepared, native_contract, output = map(Path,
        (attestation_path, protocol_path, prepared, native_contract, output))
    attestation = json.loads(attestation_path.read_text())
    expected_attestation = hashlib.sha256(attestation_path.read_bytes()).hexdigest()
    expected_protocol = hashlib.sha256(protocol_path.read_bytes()).hexdigest()
    classpath = os.pathsep.join([attestation['adapterClasses'], *attestation['runtimeEntries']])
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java')
    output.mkdir()
    cases = ('index', 'scalar', 'mask', 'grid', 'joint-index-grid', 'protocol', 'classpath', 'unknown-case', 'native-purpose',
             'tree-directories', 'tree-files', 'tree-depth', 'tree-path')
    commands = []
    for mutation in cases:
        directory = output / mutation
        directory.mkdir()
        files = directory / 'prepared'
        shutil.copytree(prepared, files)
        protocol = directory / 'protocol.json'
        protocol.write_bytes(protocol_path.read_bytes())
        selected_attestation = attestation_path
        selected_attestation_sha = expected_attestation
        case = 'connected-straight'
        target = files / case
        if mutation == 'unknown-case':
            case = 'not-a-public-case'
        elif mutation == 'index':
            with (files / 'index.json').open('ab') as stream:
                stream.write(b' ')
        elif mutation == 'scalar':
            raw = bytearray((target / 'scalar.f64').read_bytes())
            raw[0] ^= 1
            (target / 'scalar.f64').write_bytes(raw)
        elif mutation == 'mask':
            raw = bytearray((target / 'validity.bin').read_bytes())
            raw[0] = 2
            (target / 'validity.bin').write_bytes(raw)
        elif mutation in ('grid', 'joint-index-grid'):
            descriptor = json.loads((target / 'descriptor.json').read_text())
            descriptor['outputGrid']['origin'][0] += .25
            raw = module().canonical(descriptor)
            (target / 'descriptor.json').write_bytes(raw)
            if mutation == 'joint-index-grid':
                index = json.loads((files / 'index.json').read_text())
                index['members'][case + '/descriptor.json'] = {'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}
                (files / 'index.json').write_bytes(module().canonical(index))
        elif mutation == 'protocol':
            raw = json.loads(protocol.read_text())
            raw['scope'] = 'UNAPPROVED_ENGINE_EXECUTION'
            protocol.write_bytes(module().canonical(raw))
        elif mutation.startswith('tree-'):
            tree = directory / 'controlled-class-tree'
            tree.mkdir()
            (tree / 'one.class').write_bytes(b'bounded inventory fixture, never loaded')
            if mutation in ('tree-directories', 'tree-files'):
                for index in range(1024):
                    path = tree / ('entry-' + str(index))
                    path.mkdir() if mutation == 'tree-directories' else path.write_bytes(b'x')
            elif mutation == 'tree-depth':
                path = tree
                for index in range(17):
                    path = path / 'd'
                    path.mkdir()
                (path / 'x.class').write_bytes(b'x')
            else:
                path = tree / ('x' * 100) / ('y' * 100)
                path.mkdir(parents=True)
                (path / ('z' * 60 + '.class')).write_bytes(b'x')
            changed = dict(attestation)
            changed['mainClasses'] = str(tree.resolve())
            selected_attestation = directory / 'controlled-attestation.json'
            raw = module().canonical(changed)
            selected_attestation.write_bytes(raw)
            selected_attestation_sha = hashlib.sha256(raw).hexdigest()
        cp = classpath + (os.pathsep + str(ROOT / 'build/classes/java/main') if mutation == 'classpath' else '')
        receipt_directory = directory / 'receipts'
        receipt_directory.mkdir()
        result_path = receipt_directory / 'result.json'
        native = mutation == 'native-purpose'
        destination = receipt_directory / 'native-attempt' if native else result_path
        command = [java, '-Djava.awt.headless=true', '-cp', cp, 'HistoricalNativeB', 'NATIVE_ENGINE' if native else 'CAPTURE_ONLY',
                   str(selected_attestation.resolve()), selected_attestation_sha, str(protocol.resolve()), expected_protocol,
                   str(files.resolve()), str(native_contract.resolve()), case, str(destination.resolve())]
        completed = subprocess.run(command, capture_output=True, text=True, timeout=360)
        (directory / 'process.log').write_text(completed.stdout + completed.stderr)
        check.assertNotEqual(0, completed.returncode, mutation)
        if native:
            result_path = destination / 'result.json'
        receipt = json.loads(result_path.read_text())
        check.assertEqual('TERMINAL_PRODUCER_FAILURE' if native else 'REFUSED_CAPTURE_ONLY', receipt['status'], mutation)
        check.assertEqual(0, receipt['captureInvocations'], mutation)
        check.assertEqual(0, receipt['captureReturns'], mutation)
        check.assertEqual(0, receipt['engineInvocations'], mutation)
        check.assertEqual(0, receipt['sourceBytesAfterClose'], mutation)
        check.assertEqual(2 * 1024 * 1024, receipt['publicationBytesLive'], mutation)
        closed = json.loads(completed.stdout.strip())
        check.assertEqual(0, closed['finalRootBytes'], mutation)
        if mutation.startswith('tree-'):
            expected = 'Classpath tree entry bound' if mutation in ('tree-directories', 'tree-files') else 'Classpath tree depth' if mutation == 'tree-depth' else 'Classpath tree path bytes'
            check.assertIn(expected, completed.stderr, mutation)
        commands.append({'mutation': mutation, 'command': command, 'exit': completed.returncode,
                         'receiptSha256': hashlib.sha256(result_path.read_bytes()).hexdigest()})
    (output / 'commands.json').write_bytes(module().canonical({'scope': module().SCOPE, 'checks': commands}))
    print(json.dumps({'guardCases': len(cases), 'captureInvocations': 0, 'engineInvocations': 0, 'allFinalRootBytes': 0}))


if __name__ == "__main__":
    if len(sys.argv) == 7 and sys.argv[1] == '--adapter-guards':
        adapter_guards(*sys.argv[2:])
    else:
        unittest.main()
