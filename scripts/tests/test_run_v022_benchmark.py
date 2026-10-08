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
