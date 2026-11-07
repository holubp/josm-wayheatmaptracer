"""Failure-path checks for the unattended real-action benchmark runner."""

import importlib.util
import json
import sys
import tempfile
import time
import unittest
import zipfile
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).resolve().parents[1] / "run-v022-benchmark.py"
SPEC = importlib.util.spec_from_file_location("run_v022_benchmark", SCRIPT)
benchmark = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(benchmark)


class BenchmarkRunnerTest(unittest.TestCase):
    def test_v5_adapter_pins_are_separate_without_changing_old_adapter_registries(self):
        old = benchmark.REVIEWED_RC6_V4_SOURCE_SHA256
        new = benchmark.REVIEWED_RC6_V5_SOURCE_SHA256
        self.assertEqual(6, len(new))
        self.assertEqual('0b22005abeb94879004fe7bde4212068b9192b32dc29e6854ea3773ccd051450',
                         old[benchmark._RC6_SOURCE+'BenchmarkHostMain.java'])
        self.assertNotEqual(old[benchmark._RC6_SOURCE+'BenchmarkHostMain.java'],
                            new[benchmark._RC6_SOURCE+'BenchmarkHostMain.java'])
        for path, value in old.items():
            if not path.endswith('/BenchmarkHostMain.java'):
                self.assertEqual(value, new[path])
        benchmark.require_reviewed_rc6(dict(new), list(benchmark.REVIEWED_RC6_V5_DIRTY), benchmark.V5_CONTRACT)
        for contract in (benchmark.STRICT_CONTRACT, benchmark.APPROVED_CONTRACT, benchmark.V4_CONTRACT):
            with self.assertRaises(benchmark.GateFailure):
                benchmark.require_reviewed_rc6(dict(new), list(benchmark.REVIEWED_RC6_V5_DIRTY), contract)
        with self.assertRaises(benchmark.GateFailure):
            benchmark.require_reviewed_rc6(dict(old), list(benchmark.REVIEWED_RC6_V4_DIRTY), benchmark.V5_CONTRACT)
        for path in new:
            with self.assertRaises(benchmark.GateFailure):
                benchmark.require_reviewed_rc6({**new, path:'0'*64}, list(benchmark.REVIEWED_RC6_V5_DIRTY), benchmark.V5_CONTRACT)
        with self.assertRaises(benchmark.GateFailure):
            benchmark.require_reviewed_rc6(dict(new), [*benchmark.REVIEWED_RC6_V5_DIRTY, '?? extra.java'], benchmark.V5_CONTRACT)
        self.assertEqual(benchmark.REVIEWED_RC6_V5_PATCH_SHA256,
                         benchmark.sha256(SCRIPT.parent/'v022-rc6-v5-benchmark-instrumentation.patch'))

    def test_v5_has_a_distinct_contract_registry_and_preserves_both_profiles(self):
        self.assertEqual('b-inventory-v5-numerical-readonly-v1', benchmark.V5_CONTRACT)
        self.assertEqual(benchmark.V5_CONTRACT,
                         benchmark.comparison_contract({'comparisonContract': benchmark.V5_CONTRACT}))
        self.assertEqual(dict(benchmark.SOURCE_PROFILES), dict(benchmark.V5_SOURCE_PROFILES))
        reference = {'diagnosticsPath': '/baseline/a.zip', 'diagnosticsSha256': 'a'*64,
                     'receiptPath': '/baseline/p.json', 'receiptSha256': 'b'*64, 'nonce': 'warmup'}
        paths = [Path('/'+name) for name in ('java', 'run', 'josm', 'plugin', 'archive', 'osm', 'tiles', 'receipt')]
        for profile in benchmark.V5_SOURCE_PROFILES:
            for version in ('baseline', 'candidate'):
                for action in ('preview', 'cancel'):
                    command = benchmark.host_command(*paths, 'nonce', 'N1:'+version, action,
                                                     benchmark.V5_CONTRACT, reference, profile)
                    self.assertIn('-Dwayheatmaptracer.benchmark.sourceProfile='+profile, command)
                    self.assertIn('-Dwayheatmaptracer.benchmark.comparisonContract='+benchmark.V5_CONTRACT, command)
                    self.assertFalse(any('parameterHash' in item for item in command))
            for scope in ('baseline-repeat', 'candidate-repeat', 'cross-version'):
                command = benchmark.comparator_command(paths[0], paths[2], paths[3], paths[4], paths[4],
                    paths[5], 'a'*64, benchmark.V5_CONTRACT, profile, scope)
                self.assertEqual([benchmark.V5_CONTRACT, profile, scope], command[-3:])

    def test_v4_baseline_registry_is_separate_and_rejects_any_unreviewed_bytes(self):
        old = benchmark.REVIEWED_RC6_SOURCE_SHA256
        new = benchmark.REVIEWED_RC6_V4_SOURCE_SHA256
        self.assertEqual(6, len(new))
        for path in old:
            if not path.endswith('/BenchmarkHostMain.java'):
                self.assertEqual(old[path], new[path])
        benchmark.require_reviewed_rc6(dict(new), list(benchmark.REVIEWED_RC6_V4_DIRTY), benchmark.V4_CONTRACT)
        for contract in (benchmark.STRICT_CONTRACT, benchmark.APPROVED_CONTRACT):
            with self.assertRaises(benchmark.GateFailure):
                benchmark.require_reviewed_rc6(dict(new), list(benchmark.REVIEWED_RC6_V4_DIRTY), contract)
        for path in new:
            changed = {**new, path: '0'*64}
            with self.assertRaises(benchmark.GateFailure):
                benchmark.require_reviewed_rc6(changed, list(benchmark.REVIEWED_RC6_V4_DIRTY), benchmark.V4_CONTRACT)
        with self.assertRaises(benchmark.GateFailure):
            benchmark.require_reviewed_rc6(dict(new), [*benchmark.REVIEWED_RC6_V4_DIRTY, '?? unexpected.java'], benchmark.V4_CONTRACT)
        self.assertEqual(benchmark.REVIEWED_RC6_V4_PATCH_SHA256,
                         benchmark.sha256(SCRIPT.parent/'v022-rc6-v4-benchmark-instrumentation.patch'))

    def test_v4_comparator_never_guesses_repeat_scope_from_build_identity(self):
        paths = [Path('/'+name) for name in ('java', 'josm', 'plugin', 'first', 'second', 'osm')]
        profile = 'managed-bluered-precise-25m-z15-v1'
        for scope in ('baseline-repeat', 'candidate-repeat', 'cross-version'):
            command = benchmark.comparator_command(*paths, 'a'*64, benchmark.V4_CONTRACT, profile, scope)
            self.assertEqual([str(item) for item in paths[3:]]+['a'*64, benchmark.V4_CONTRACT, profile, scope],
                             command[command.index(benchmark.COMPARE_CLASS)+1:])
        with self.assertRaises(benchmark.GateFailure):
            benchmark.comparator_command(*paths, 'a'*64, benchmark.V4_CONTRACT, profile, 'same-build')

    def test_selected_source_inventory_rejects_mutations_and_duplicate_addresses(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            plain = root/'input'
            plain.write_bytes(b'OSM/archive synthetic pin')
            index = root/'tiles.tsv'
            rows = []
            for zoom, count in ((15, 4), (14, 2)):
                for x in range(count):
                    png = root/f'{zoom}-{x}.png'
                    png.write_bytes(b'\x89PNG\r\n\x1a\nsynthetic')
                    rows.append(f'{zoom}\t{x}\t0\t{benchmark.sha256(png)}\t{png.name}')
            def case_for(lines):
                index.write_text('zoom\tx\ty\tsha256\trelativePath\n'+'\n'.join(lines)+'\n')
                pin = {'path': str(plain), 'sha256': benchmark.sha256(plain)}
                return {'archive': pin, 'osm': pin, 'tiles': {'path': str(index), 'sha256': benchmark.sha256(index)},
                        'sourceProfile': 'managed-bluered-precise-25m-z15-v1'}
            case = case_for(rows)
            self.assertEqual(6, len(benchmark.pinned_case_inputs(case, benchmark.V4_CONTRACT)))
            for changed in (rows[:-1], [rows[0], rows[0], *rows[2:]],
                            [rows[0].replace('15\t0', '13\t0'), *rows[1:]],
                            [rows[0].rsplit('\t', 1)[0]+'\t../escape.png', *rows[1:]]):
                with self.assertRaises(benchmark.GateFailure):
                    benchmark.pinned_case_inputs(case_for(changed), benchmark.V4_CONTRACT)
            case = case_for(rows)
            (root/'15-0.png').write_bytes(b'changed')
            with self.assertRaises(benchmark.GateFailure):
                benchmark.pinned_case_inputs(case, benchmark.V4_CONTRACT)

    def test_v4_profiles_are_explicit_on_both_versions_and_actions(self):
        root = Path('/synthetic')
        reference = {'diagnosticsPath': '/baseline/a.zip', 'diagnosticsSha256': 'a'*64,
                     'receiptPath': '/baseline/p.json', 'receiptSha256': 'b'*64, 'nonce': 'warmup'}
        for profile in benchmark.SOURCE_PROFILES:
            for version in ('baseline', 'candidate'):
                for action in ('preview', 'cancel'):
                    command = benchmark.host_command(root/'java', root, root/'josm', root/'plugin',
                        root/'archive', root/'osm', root/'tiles', root/'receipt', 'nonce',
                        'N1:'+version, action, benchmark.V4_CONTRACT, reference, profile)
                    self.assertIn('-Dwayheatmaptracer.benchmark.sourceProfile='+profile, command)
                    self.assertFalse(any('parameterHash' in item for item in command))
        with self.assertRaises(benchmark.GateFailure):
            benchmark.source_profile({'sourceProfile': 'unknown'}, benchmark.V4_CONTRACT)
        with self.assertRaises(benchmark.GateFailure):
            benchmark.source_profile({'sourceProfile': next(iter(benchmark.SOURCE_PROFILES))}, benchmark.STRICT_CONTRACT)

    def test_current_host_pins_the_actual_native_baseline_reference_for_preview_and_cancel(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            reference = {"diagnosticsPath": str(root / "native-baseline.zip"), "diagnosticsSha256": "a" * 64,
                         "receiptPath": str(root / "publication.json"), "receiptSha256": "b" * 64, "nonce": "old-run"}
            for action in ("preview", "cancel"):
                command = benchmark.host_command(root / "java", root / action, root / "josm.jar", root / "plugin.jar",
                    root / "bookmark.zip", root / "original.osm", root / "tiles.tsv", root / "receipt.json",
                    "nonce", "N1:candidate", action, benchmark.APPROVED_CONTRACT, reference)
                self.assertIn("-Dwayheatmaptracer.benchmark.baselineArchive=" + reference["diagnosticsPath"], command)
                self.assertIn("-Dwayheatmaptracer.benchmark.baselineArchiveSha256=" + reference["diagnosticsSha256"], command)
                self.assertEqual(str(root / "bookmark.zip"), command[-8], "original bookmark stays pinned as the case argument")
                self.assertIn("-Dwayheatmaptracer.benchmark.baselineReceiptSha256=" + "b" * 64, command)
                self.assertIn("-Dwayheatmaptracer.benchmark.baselineNonce=old-run", command)
            with self.assertRaisesRegex(benchmark.GateFailure, "native baseline"):
                benchmark.host_command(root / "java", root, root / "josm.jar", root / "plugin.jar",
                    root / "bookmark.zip", root / "original.osm", root / "tiles.tsv", root / "receipt.json",
                    "nonce", "N1:candidate", "preview", benchmark.APPROVED_CONTRACT)

    def test_unknown_comparison_contract_is_rejected_before_any_host_work(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "manifest.json"
            manifest.write_text(json.dumps({"schemaVersion": 1, "warmups": 1,
                "repetitions": 3, "comparisonContract": "ignore-everything",
                "cases": [{"id": "N1"}, {"id": "N2"}]}))
            output = root / "out"
            self.assertEqual(1, benchmark.main(["--manifest", str(manifest), "--output", str(output)]))
            report = json.loads((output / "benchmark.json").read_text())
            self.assertEqual("Unknown benchmark comparison contract", report["failure"])

    def test_approved_cross_version_changes_are_reported_and_within_version_stays_exact(self):
        baseline = {"selectedRasterSha256": "a" * 64, "finalGeometrySha256": "b" * 64, "kind": "single"}
        current = {**baseline, "finalGeometrySha256": "c" * 64, "kind": "interval"}
        differences = benchmark.require_cross_version_preview(baseline, current, "N1", benchmark.APPROVED_CONTRACT)
        self.assertEqual({"finalGeometrySha256", "kind"}, set(differences))
        self.assertEqual("single", differences["kind"]["baseline"])
        self.assertEqual("interval", differences["kind"]["candidate"])
        with self.assertRaisesRegex(benchmark.GateFailure, "finalGeometrySha256"):
            benchmark.require_same_preview(baseline, current, "N1")
        with self.assertRaisesRegex(benchmark.GateFailure, "selectedRasterSha256"):
            benchmark.require_cross_version_preview(baseline, {**current, "selectedRasterSha256": "d" * 64},
                "N1", benchmark.APPROVED_CONTRACT)
        with self.assertRaises(benchmark.GateFailure):
            benchmark.require_cross_version_preview(baseline, current, "N1", benchmark.STRICT_CONTRACT)
        self.assertEqual(benchmark.STRICT_CONTRACT, benchmark.comparison_contract({}))


    def test_gui_host_command_scopes_josms_required_module_exports(self):
        exports = [
            "--add-exports=java.base/sun.security.action=ALL-UNNAMED",
            "--add-exports=java.desktop/com.sun.imageio.plugins.jpeg=ALL-UNNAMED",
            "--add-exports=java.desktop/com.sun.imageio.spi=ALL-UNNAMED",
        ]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for action in ("preview", "cancel"):
                command = benchmark.host_command(
                    root / "java", root / action, root / "josm.jar",
                    root / "plugin.jar", root / "input.zip", root / "input.osm",
                    root / "tiles.json", root / "receipt.json", "nonce", "N1:baseline", action)
                self.assertEqual(exports, command[1:4])
                self.assertTrue(all(command.count(option) == 1 for option in exports))
                self.assertEqual(3, sum(option.startswith("--add-exports=") for option in command))
                self.assertIn(benchmark.HOST_CLASS, command)
                self.assertEqual(action, command[-1])

    def test_rc6_requires_exact_reviewed_instrumentation_and_no_engine_delta(self):
        base = "src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/"
        approved = {
            base + "BenchmarkHostMain.java": "f145abf4818377cdc428916a5ca74701a803cf5aadeee9c3e6e555a130ac25cb",
            base + "actions/AlignWayAction.java": "2c36b4ec8d63742c43fcab6b582d1b47430bad78b66baa4762301422d3830735",
            base + "actions/OrdinaryActionBenchmarkObserver.java": "887381014611049364712f1e4a546062367eb2fa11353e735ceb00d64fdd28a4",
            base + "diagnostics/replay/format15/Format15ProductionBundleFactory.java": "d93a58d44bf1d773ee2b30e8bf205bbdf47169b271678cd4fee9d596451a3385",
            base + "tile/ManagedTileRuntime.java": "4ae919ec3076ff1f686a9740d9deee5db011d5aa26845cda5a42b4364ffd1b20",
        }
        dirty = [" M " + base + "actions/AlignWayAction.java",
                 " M " + base + "diagnostics/replay/format15/Format15ProductionBundleFactory.java",
                 " M " + base + "tile/ManagedTileRuntime.java",
                 "?? " + base + "BenchmarkHostMain.java",
                 "?? " + base + "actions/OrdinaryActionBenchmarkObserver.java"]
        benchmark.require_reviewed_rc6(approved, dirty)
        with self.assertRaisesRegex(benchmark.GateFailure, "unreviewed RC6 worktree delta"):
            benchmark.require_reviewed_rc6(approved, dirty + [" M " + base + "service/ProbabilisticInference.java"])
        spoofed = dict(approved)
        spoofed[base + "actions/AlignWayAction.java"] = "0" * 64
        with self.assertRaisesRegex(benchmark.GateFailure, "adapter bytes"):
            benchmark.require_reviewed_rc6(spoofed, dirty)
        self.assert_rc6_preflight_rejects_manifest_pinned_mutations(benchmark, base, dirty)

    def assert_rc6_preflight_rejects_manifest_pinned_mutations(self, runner, base, dirty):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            worktree = root / "rc6"
            worktree.mkdir()
            (worktree / "gradlew").write_text("#!/bin/sh\n")
            adapter_paths = [line[3:] for line in dirty]
            engine = base + "service/ProbabilisticInference.java"
            for relative in adapter_paths + [engine]:
                file = worktree / relative
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text("controlled altered source: " + relative)
            source_hashes = {relative: runner.sha256(worktree / relative)
                             for relative in adapter_paths + [engine]}
            spec = {"worktree": str(worktree), "revision": runner.BASELINE_REVISION,
                    "sourceHashes": source_hashes}
            status_lines = dirty + [" M " + engine]
            def status(_command, _cwd, log, _timeout):
                log.write_text("\n".join(status_lines) + "\n")
            with (mock.patch.object(runner, "checked_revision"),
                  mock.patch.object(runner, "checked_process", side_effect=status)):
                with self.assertRaisesRegex(runner.GateFailure, "adapter bytes"):
                    runner.validate_plugin(spec, "baseline", root)
                spec["sourceHashes"] = {relative: source_hashes[relative]
                                        for relative in adapter_paths}
                status_lines = dirty
                with self.assertRaisesRegex(runner.GateFailure, "adapter bytes"):
                    runner.validate_plugin(spec, "baseline", root)

    def test_fixed_worktree_lock_serializes_without_shared_storage_flock(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = benchmark.fixed_lock_path(root)
            self.assertEqual("josm-v022-benchmark-locks", path.parent.name)
            self.assertEqual(path, benchmark.fixed_lock_path(root / "."))
            with mock.patch.dict("os.environ", {"TMPDIR": str(root / "other-tmp")}, clear=False):
                self.assertEqual(path, benchmark.fixed_lock_path(root))
            held = benchmark.exclusive_lock(path)
            try:
                with self.assertRaisesRegex(benchmark.GateFailure, "Concurrent benchmark"):
                    benchmark.exclusive_lock(path)
            finally:
                held.close()

    def test_preflight_rejects_changed_source_and_unpinned_delta_before_host(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            worktree = root / "candidate"
            worktree.mkdir()
            (worktree / "gradlew").write_text("#!/bin/sh\n")
            source = worktree / "source.txt"
            source.write_text("original")
            spec = {"worktree": str(worktree), "revision": "pinned",
                    "sourceHashes": {"source.txt": benchmark.sha256(source)}}
            with mock.patch.object(benchmark, "checked_revision"):
                def status(_command, _cwd, log, _timeout):
                    log.write_text("?? unpinned.txt\n")
                with mock.patch.object(benchmark, "checked_process", side_effect=status):
                    with self.assertRaisesRegex(benchmark.GateFailure, "unpinned worktree delta"):
                        benchmark.validate_plugin(spec, "candidate", root)
                source.write_text("changed")
                with self.assertRaisesRegex(benchmark.GateFailure, "checksum-mismatched"):
                    benchmark.validate_plugin(spec, "candidate", root)

    def test_missing_host_is_nonzero_and_durable(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "manifest.json"
            manifest.write_text(json.dumps({"schemaVersion": 1, "warmups": 1,
                "repetitions": 3, "cases": [{"id": "N1"}, {"id": "N2"}]}))
            output = root / "out"
            self.assertEqual(1, benchmark.main(["--manifest", str(manifest),
                                                   "--output", str(output)]))
            report = json.loads((output / "benchmark.json").read_text())
            self.assertNotEqual("PASS", report["status"])
            self.assertTrue((output / "summary.txt").is_file())

    def test_publication_requires_producer_nonce_cache_and_zooms(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            publication = root / "publication.json"
            diagnostics = root / "publication.json.format15.zip"
            tile_file = root / "publication.json.tiles.json"
            with zipfile.ZipFile(diagnostics, "w") as archive:
                archive.writestr("attempt-status.json", json.dumps(
                    {"status": "review-required", "sourceLineage": "managed-tiles"}))
                archive.writestr("frozen-input.bin", b"native-frozen-input")
            tiles = {"stats": {"transportExecutions": 0, "diskHits": 6,
                               "memoryHits": 0},
                     "recentResults": [{"status": "SUCCESS_DISK_CACHE", "color": "hot",
                                        "activity": "all", "zoom": 15}]}

            def write_receipt(producer=benchmark.PRODUCER, nonce="bound"):
                tile_file.write_text(json.dumps(tiles))
                publication.write_text(json.dumps({"producer": producer, "nonce": nonce,
                    "caseId": "N1", "version": "baseline", "kind": "single",
                    "totalNanos": 123, "finalGeometrySha256": "a" * 64,
                    "selectedRasterSha256": "b" * 64,
                    "diagnosticsFile": diagnostics.name,
                    "diagnosticsSha256": benchmark.sha256(diagnostics),
                    "tileDiagnosticsFile": tile_file.name,
                    "tileDiagnosticsSha256": benchmark.sha256(tile_file)}))

            expected = {"nonce": "bound", "caseId": "N1", "version": "baseline"}
            write_receipt()
            self.assertEqual(123, benchmark.receipt_file(publication, expected, {15})["totalNanos"])
            write_receipt(producer="synthetic-harness")
            with self.assertRaises(benchmark.GateFailure):
                benchmark.receipt_file(publication, expected, {15})
            write_receipt(nonce="stale")
            with self.assertRaises(benchmark.GateFailure):
                benchmark.receipt_file(publication, expected, {15})
            tiles["recentResults"][0]["color"] = "blue"
            write_receipt()
            with self.assertRaises(benchmark.GateFailure):
                benchmark.receipt_file(publication, expected, {15})
            tiles["recentResults"][0]["color"] = "hot"
            write_receipt()
            with self.assertRaises(benchmark.GateFailure):
                benchmark.receipt_file(publication, expected, {14, 15})
            tiles["stats"]["transportExecutions"] = 1
            write_receipt()
            with self.assertRaises(benchmark.GateFailure):
                benchmark.receipt_file(publication, expected, {15})
            tiles["stats"]["transportExecutions"] = 0
            with zipfile.ZipFile(diagnostics, "w") as archive:
                archive.writestr("attempt-status.json", json.dumps(
                    {"status": "review-required", "sourceLineage": "managed-tiles"}))
            write_receipt()
            with self.assertRaises(benchmark.GateFailure):
                benchmark.receipt_file(publication, expected, {15})
            for status in ("blocked", "resource-limited"):
                with zipfile.ZipFile(diagnostics, "w") as archive:
                    archive.writestr("attempt-status.json", json.dumps(
                        {"status": status, "sourceLineage": "managed-tiles"}))
                    archive.writestr("frozen-input.bin", b"native-frozen-input")
                write_receipt()
                with self.assertRaisesRegex(benchmark.GateFailure, "not preview-ready"):
                    benchmark.receipt_file(publication, expected, {15})

    def test_truthful_planless_interval_receipt_remains_a_gate_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            diagnostics = root / "publication.json.format15.zip"
            with zipfile.ZipFile(diagnostics, "w") as archive:
                archive.writestr("private/frozen-interval-input.bin", b"native-planless-input")
                archive.writestr("interval-production.json", json.dumps({
                    "artifactKind": "INTERVAL_PRODUCTION", "status": "PREVIEW",
                    "planIdentity": None, "applyAvailable": False,
                    "intervals": [{"disposition": "FROZEN_LOCAL_FAILURE", "reason": "NO_PRODUCTION_ROUTE"}]}))
            tiles = root / "publication.json.tiles.json"
            tiles.write_text(json.dumps({"stats": {"transportExecutions": 0, "diskHits": 6},
                "recentResults": [{"status": "SUCCESS_DISK_CACHE", "color": "hot", "activity": "all", "zoom": 15}]}))
            receipt = root / "publication.json"
            payload = {"producer": benchmark.PRODUCER, "nonce": "native-planless", "caseId": "N1",
                "version": "candidate", "kind": "interval", "totalNanos": 123,
                "planAvailable": False, "applyAvailable": False,
                "finalGeometrySha256": "a" * 64, "selectedRasterSha256": "b" * 64,
                "diagnosticsFile": diagnostics.name, "diagnosticsSha256": benchmark.sha256(diagnostics),
                "tileDiagnosticsFile": tiles.name, "tileDiagnosticsSha256": benchmark.sha256(tiles)}
            for claimed_apply in (False, True):
                payload["applyAvailable"] = claimed_apply
                receipt.write_text(json.dumps(payload))
                with self.assertRaisesRegex(benchmark.GateFailure, "Interval production status is not preview-ready"):
                    benchmark.receipt_file(receipt, {"nonce": "native-planless", "caseId": "N1", "version": "candidate"}, {15})

    def test_cancellation_refuses_publication_claim(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            receipt = root / "cancel.json"
            archive = root / "cancel.json.format15.zip"
            with zipfile.ZipFile(archive, "w") as bundle:
                bundle.writestr("attempt-status.json", json.dumps(
                    {"status": "cancelled", "sourceLineage": "managed-tiles"}))
            common = {"producer": "AlignWayAction.production-cancel-v1",
                      "nonce": "bound", "caseId": "N1", "version": "baseline",
                      "terminalState": "CANCELLED", "cancelNanos": 100,
                      "diagnosticsFile": archive.name,
                      "diagnosticsSha256": benchmark.sha256(archive)}
            receipt.write_text(json.dumps({**common, "noPreviewPublication": True}))
            expected = {"nonce": "bound", "caseId": "N1", "version": "baseline"}
            self.assertEqual(100, benchmark.cancel_receipt(receipt, expected)["cancelNanos"])
            receipt.write_text(json.dumps({**common, "noPreviewPublication": False}))
            with self.assertRaises(benchmark.GateFailure):
                benchmark.cancel_receipt(receipt, expected)

    def test_interval_publication_requires_native_plan_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            publication = root / "interval.json"
            diagnostics = root / "interval.zip"
            tile_file = root / "tiles.json"
            with zipfile.ZipFile(diagnostics, "w") as archive:
                archive.writestr("private/frozen-interval-input.bin", b"native-input")
                archive.writestr("interval-production.json", json.dumps({
                    "artifactKind": "INTERVAL_PRODUCTION", "status": "PREVIEW",
                    "planIdentity": "a" * 64, "applyAvailable": True}))
            tile_file.write_text(json.dumps({"stats": {"transportExecutions": 0,
                "diskHits": 1}, "recentResults": [{"status": "SUCCESS_DISK_CACHE",
                "color": "hot", "activity": "all", "zoom": 15}]}))
            publication.write_text(json.dumps({"producer": benchmark.PRODUCER,
                "nonce": "bound", "caseId": "N1", "version": "baseline",
                "kind": "interval", "totalNanos": 123,
                "finalGeometrySha256": "a" * 64,
                "selectedRasterSha256": "b" * 64,
                "diagnosticsFile": diagnostics.name,
                "diagnosticsSha256": benchmark.sha256(diagnostics),
                "tileDiagnosticsFile": tile_file.name,
                "tileDiagnosticsSha256": benchmark.sha256(tile_file)}))
            with self.assertRaisesRegex(benchmark.GateFailure, "interval edit plan"):
                benchmark.receipt_file(publication,
                    {"nonce": "bound", "caseId": "N1", "version": "baseline"}, {15})
            with zipfile.ZipFile(diagnostics, "w") as archive:
                archive.writestr("private/frozen-interval-input.bin", b"native-input")
                archive.writestr("private/interval-frozen-edit-plan.bin", b"native-plan")
                archive.writestr("interval-production.json", json.dumps({
                    "artifactKind": "INTERVAL_PRODUCTION", "status": "PREVIEW",
                    "planIdentity": "a" * 64, "applyAvailable": False}))
            receipt = json.loads(publication.read_text())
            receipt["diagnosticsSha256"] = benchmark.sha256(diagnostics)
            publication.write_text(json.dumps(receipt))
            with self.assertRaisesRegex(benchmark.GateFailure, "not preview-ready"):
                benchmark.receipt_file(publication,
                    {"nonce": "bound", "caseId": "N1", "version": "baseline"}, {15})

    def test_leader_exit_stops_term_ignoring_child_group(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            child_pid = root / "child.pid"
            child = ("import os,signal,time;"
                     "signal.signal(signal.SIGTERM,signal.SIG_IGN);"
                     f"open({str(child_pid)!r},'w').write(str(os.getpid()));"
                     "time.sleep(60)")
            leader = ("import subprocess,sys;"
                      f"subprocess.Popen([sys.executable,'-c',{child!r}]);"
                      "import time;time.sleep(0.4)")
            result = benchmark.run_process([sys.executable, "-c", leader], root,
                                           root / "process.log", 5)
            self.assertEqual(0, result["exitCode"])
            pid = int(child_pid.read_text())
            time.sleep(0.1)
            try:
                state = Path(f"/proc/{pid}/status").read_text().split("State:", 1)[1].splitlines()[0]
            except (FileNotFoundError, PermissionError, IndexError):
                state = "gone"
            self.assertTrue(state == "gone" or "Z" in state, state)

    def test_timeout_stops_term_ignoring_host_process(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            code = ("import signal,time;"
                    "signal.signal(signal.SIGTERM,signal.SIG_IGN);"
                    "time.sleep(60)")
            result = benchmark.run_process([sys.executable, "-c", code], root,
                                           root / "timeout.log", 1)
            self.assertEqual(124, result["exitCode"])
            self.assertTrue(result["timedOut"])
            self.assertLess(result["wallSeconds"], 6)

    def test_substantive_geometry_repetitions_and_speedup_fail_closed(self):
        reference = {"selectedRasterSha256": "a" * 64,
                     "finalGeometrySha256": "b" * 64, "kind": "single"}
        benchmark.require_same_preview(reference, dict(reference), "N1")
        with self.assertRaises(benchmark.GateFailure):
            benchmark.require_same_preview(reference,
                {**reference, "finalGeometrySha256": "c" * 64}, "N1")
        with self.assertRaises(benchmark.GateFailure):
            benchmark.require_same_preview(reference,
                {**reference, "selectedRasterSha256": "c" * 64}, "N1")
        runs = [{"phase": "warmup", "totalNanos": 100},
                {"phase": "measured", "totalNanos": 10},
                {"phase": "measured", "totalNanos": 12}]
        with self.assertRaises(benchmark.GateFailure):
            benchmark.measured_median(runs, 3, "N1")
        runs.append({"phase": "measured", "totalNanos": 11})
        self.assertEqual(11, benchmark.measured_median(runs, 3, "N1"))
        self.assertEqual(2.0, benchmark.require_speedup(20, 10, "N1"))
        with self.assertRaises(benchmark.GateFailure):
            benchmark.require_speedup(19, 10, "N1")


if __name__ == "__main__":
    unittest.main()
