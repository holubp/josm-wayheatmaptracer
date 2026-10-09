#!/usr/bin/env python3
"""Run the private RC6/current ordinary JOSM GUI action benchmark.

The fixed Java host entry point invokes the registered production action. A
missing display, fixture, production publication, or comparison is a failed
gate. This runner neither calls an AI service nor imports external timings.
"""

from __future__ import annotations

import argparse
import fcntl
import hashlib
import json
import os
import secrets
import signal
import statistics
import subprocess
import sys
import time
import zipfile
from pathlib import Path
from types import MappingProxyType


HOST_CLASS = "org.openstreetmap.josm.plugins.wayheatmaptracer.BenchmarkHostMain"
COMPARE_CLASS = "org.openstreetmap.josm.plugins.wayheatmaptracer.BenchmarkDecisionComparatorMain"
HOST_JVM_EXPORTS = (
    "--add-exports=java.base/sun.security.action=ALL-UNNAMED",
    "--add-exports=java.desktop/com.sun.imageio.plugins.jpeg=ALL-UNNAMED",
    "--add-exports=java.desktop/com.sun.imageio.spi=ALL-UNNAMED",
)
PRODUCER = "AlignWayAction.production-preview-v1"
BASELINE_REVISION = "6288aafe6dc79e948a6981ae795d27ee56a653fe"
HEX64 = set("0123456789abcdef")
_RC6_SOURCE = "src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/"
REVIEWED_RC6_SOURCE_SHA256 = MappingProxyType({
    _RC6_SOURCE + "BenchmarkHostMain.java": "f145abf4818377cdc428916a5ca74701a803cf5aadeee9c3e6e555a130ac25cb",
    _RC6_SOURCE + "actions/AlignWayAction.java": "2c36b4ec8d63742c43fcab6b582d1b47430bad78b66baa4762301422d3830735",
    _RC6_SOURCE + "actions/OrdinaryActionBenchmarkObserver.java": "887381014611049364712f1e4a546062367eb2fa11353e735ceb00d64fdd28a4",
    _RC6_SOURCE + "diagnostics/replay/format15/Format15ProductionBundleFactory.java":
        "d93a58d44bf1d773ee2b30e8bf205bbdf47169b271678cd4fee9d596451a3385",
    _RC6_SOURCE + "tile/ManagedTileRuntime.java": "4ae919ec3076ff1f686a9740d9deee5db011d5aa26845cda5a42b4364ffd1b20",
})
REVIEWED_RC6_DIRTY = frozenset({
    " M " + _RC6_SOURCE + "actions/AlignWayAction.java",
    " M " + _RC6_SOURCE + "diagnostics/replay/format15/Format15ProductionBundleFactory.java",
    " M " + _RC6_SOURCE + "tile/ManagedTileRuntime.java",
    "?? " + _RC6_SOURCE + "BenchmarkHostMain.java",
    "?? " + _RC6_SOURCE + "actions/OrdinaryActionBenchmarkObserver.java",
})


class GateFailure(Exception):
    """An unavailable or failed required benchmark gate."""


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def required_file(spec: dict, label: str) -> Path:
    if not isinstance(spec, dict) or set(spec) != {"path", "sha256"}:
        raise GateFailure(f"{label}: path and sha256 are required")
    path = Path(spec["path"]).expanduser().resolve()
    expected = spec["sha256"]
    if not isinstance(expected, str) or len(expected) != 64 or set(expected) - HEX64:
        raise GateFailure(f"{label}: invalid SHA-256")
    if not path.is_file() or sha256(path) != expected:
        raise GateFailure(f"{label}: missing or checksum-mismatched input")
    return path


def run_process(command: list[str], cwd: Path, log: Path, timeout: int,
                environment: dict[str, str] | None = None) -> dict:
    started = time.monotonic()
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open("wb") as stream:
        process = subprocess.Popen(command, cwd=cwd, stdout=stream,
                                   stderr=subprocess.STDOUT, env=environment,
                                   start_new_session=True)
        try:
            exit_code = process.wait(timeout=timeout)
            stop_group(process)
            timed_out = False
        except subprocess.TimeoutExpired:
            stop_group(process)
            exit_code, timed_out = 124, True
        except BaseException:
            stop_group(process)
            raise
    return {"exitCode": exit_code, "timedOut": timed_out,
            "wallSeconds": round(time.monotonic() - started, 3),
            "log": str(log)}


def stop_group(process: subprocess.Popen) -> None:
    """Stop the whole isolated GUI/Gradle process group, including descendants."""
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        process.wait(timeout=3)
    except subprocess.TimeoutExpired:
        pass
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    process.wait()


def fixed_lock_path(resource: Path) -> Path:
    canonical = str(resource.resolve()).encode("utf-8")
    name = hashlib.sha256(canonical).hexdigest() + ".lock"
    termux_tmp = Path("/data/data/com.termux/files/usr/tmp")
    if termux_tmp.exists():
        if not termux_tmp.is_dir() or not os.access(termux_tmp, os.W_OK):
            raise GateFailure("Fixed Termux lock root is unavailable")
        lock_root = termux_tmp
    else:
        lock_root = Path("/tmp")
    return lock_root.resolve() / "josm-v022-benchmark-locks" / name


def exclusive_lock(path: Path):
    path.parent.mkdir(parents=True, exist_ok=True)
    stream = path.open("a+b")
    try:
        fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError as failure:
        stream.close()
        raise GateFailure(f"Concurrent benchmark owns lock: {path}") from failure
    return stream


def checked_process(command: list[str], cwd: Path, log: Path, timeout: int,
                    environment: dict[str, str] | None = None) -> dict:
    result = run_process(command, cwd, log, timeout, environment)
    if result["exitCode"]:
        raise GateFailure(f"Process failed ({result['exitCode']}): {log}")
    return result


def check_host(manifest: dict, output: Path) -> tuple[Path, Path]:
    environment = manifest.get("environment", {})
    if not os.environ.get("DISPLAY") and not os.environ.get("WAYLAND_DISPLAY"):
        raise GateFailure("Real JOSM GUI display unavailable (DISPLAY/WAYLAND_DISPLAY unset)")
    java = Path(environment.get("java", "")).expanduser().resolve()
    if not java.is_file() or not os.access(java, os.X_OK):
        raise GateFailure("Configured Java executable unavailable")
    josm = required_file(environment.get("josmJar"), "JOSM 19555 jar")
    if environment.get("josmVersion") != 19555 or environment.get("javaMajor") != 17:
        raise GateFailure("Reference host must declare JOSM 19555 and Java 17")
    result = checked_process([str(java), "-version"], output,
                             output / "host-java-version.log", 20)
    version_text = Path(result["log"]).read_text(errors="replace")
    if 'version "17.' not in version_text and 'openjdk 17.' not in version_text:
        raise GateFailure("Configured host Java is not Java 17")
    return java, josm


def host_command(java: Path, run_dir: Path, josm: Path, plugin: Path,
                 archive: Path, osm: Path, tiles: Path, receipt: Path,
                 nonce: str, case_version: str, action: str) -> list[str]:
    """Build one production GUI host invocation for preview or cancellation."""
    return [str(java), *HOST_JVM_EXPORTS, f"-Djava.io.tmpdir={run_dir}",
            f"-Djosm.pref={run_dir / 'josm-pref'}",
            f"-Djosm.userdata={run_dir / 'josm-userdata'}",
            f"-Djosm.cache={run_dir / 'josm-cache'}", "-cp",
            os.pathsep.join((str(josm), str(plugin))), HOST_CLASS,
            str(archive), str(osm), str(tiles), str(receipt),
            str(plugin), nonce, case_version, action]


def checked_revision(worktree: Path, revision: str, output: Path, name: str) -> None:
    if not (worktree / "gradlew").is_file():
        raise GateFailure(f"{name}: worktree unavailable")
    result = checked_process(["git", "-c", f"safe.directory={worktree}", "rev-parse", "HEAD"],
                             worktree, output / f"{name}-revision.log", 15)
    found = Path(result["log"]).read_text().strip()
    if found != revision:
        raise GateFailure(f"{name}: revision differs")


def require_reviewed_rc6(source_hashes: dict[str, str], dirty_lines: list[str]) -> None:
    if source_hashes != REVIEWED_RC6_SOURCE_SHA256:
        raise GateFailure("Baseline adapter bytes differ from the reviewed RC6 instrumentation")
    if len(dirty_lines) != len(REVIEWED_RC6_DIRTY) or set(dirty_lines) != REVIEWED_RC6_DIRTY:
        raise GateFailure("Baseline has an unreviewed RC6 worktree delta")


def validate_plugin(spec: dict, name: str, output: Path) -> Path:
    worktree = Path(spec["worktree"]).expanduser().resolve()
    revision = spec["revision"]
    if name == "baseline" and revision != BASELINE_REVISION:
        raise GateFailure("Baseline is not the exact RC6 revision")
    checked_revision(worktree, revision, output, name)
    source_hashes = spec.get("sourceHashes")
    if not isinstance(source_hashes, dict) or not source_hashes:
        raise GateFailure(f"{name}: instrumented source hashes are required")
    for relative, expected in sorted(source_hashes.items()):
        if Path(relative).is_absolute() or ".." in Path(relative).parts:
            raise GateFailure(f"{name}: source path escapes worktree")
        required_file({"path": str(worktree / relative), "sha256": expected},
                      f"{name} source {relative}")
    status_log = output / f"{name}-delta.log"
    checked_process(["git", "-c", f"safe.directory={worktree}", "status", "--porcelain",
                     "--untracked-files=all"], worktree, status_log, 15)
    dirty_lines = status_log.read_text().splitlines()
    if name == "baseline":
        require_reviewed_rc6(source_hashes, dirty_lines)
    else:
        changed = {line[3:] for line in dirty_lines if len(line) >= 4}
        if not changed.issubset(source_hashes):
            raise GateFailure(f"{name}: unpinned worktree delta exists")
    return worktree


def build_plugin(worktree: Path, name: str, output: Path) -> Path:
    checked_process(["sh", "./gradlew", "--offline", "--no-daemon", "jar",
                     "--console=plain"], worktree, output / f"{name}-build.log", 600)
    jar = worktree / "build" / "libs" / "wayheatmaptracer.jar"
    if not jar.is_file():
        raise GateFailure(f"{name}: plugin jar missing after build")
    return jar


def receipt_file(path: Path, expected: dict, expected_zooms: set[int]) -> dict:
    if not path.is_file():
        raise GateFailure(f"Production publication receipt missing: {path}")
    receipt = json.loads(path.read_text())
    for key, value in expected.items():
        if receipt.get(key) != value:
            raise GateFailure(f"Production receipt {key} differs: {path}")
    if receipt.get("producer") != PRODUCER or receipt.get("kind") not in {"single", "interval"}:
        raise GateFailure(f"Non-production publication receipt: {path}")
    if not isinstance(receipt.get("totalNanos"), int) or receipt["totalNanos"] <= 0:
        raise GateFailure(f"Invalid action-start to visible-preview time: {path}")
    for key in ("finalGeometrySha256", "selectedRasterSha256"):
        value = receipt.get(key)
        if not isinstance(value, str) or len(value) != 64 or set(value) - HEX64:
            raise GateFailure(f"Invalid {key}: {path}")
    for stem in ("diagnostics", "tileDiagnostics"):
        artifact = path.parent / receipt.get(stem + "File", "")
        if artifact.parent != path.parent or not artifact.is_file() or sha256(artifact) != receipt.get(stem + "Sha256"):
            raise GateFailure(f"Missing or corrupt {stem} artifact: {path}")
        receipt[stem + "Path"] = str(artifact)
    with zipfile.ZipFile(receipt["diagnosticsPath"]) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise GateFailure(f"Duplicate diagnostic archive members: {path}")
        required = ("frozen-input.bin" if receipt["kind"] == "single"
                    else "private/frozen-interval-input.bin")
        if required not in names:
            raise GateFailure(f"Production decision input absent: {path}")
        if receipt["kind"] == "single":
            if "attempt-status.json" not in names:
                raise GateFailure(f"Production status absent: {path}")
            attempt = json.loads(archive.read("attempt-status.json"))
            if (attempt.get("status") not in {"preview-open", "review-required"}
                    or attempt.get("sourceLineage") != "managed-tiles"):
                raise GateFailure(f"Production diagnostic status is not preview-ready: {path}")
        else:
            if "interval-production.json" not in names:
                raise GateFailure(f"Interval production index absent: {path}")
            attempt = json.loads(archive.read("interval-production.json"))
            if (attempt.get("artifactKind") != "INTERVAL_PRODUCTION"
                    or attempt.get("status") != "PREVIEW"
                    or attempt.get("applyAvailable") is not True):
                raise GateFailure(f"Interval production status is not preview-ready: {path}")
            interval_plan = "private/interval-frozen-edit-plan.bin"
            if (interval_plan in names) != (attempt.get("planIdentity") is not None):
                raise GateFailure(f"Benchmark interval edit plan availability differs: {path}")
    tiles = json.loads(Path(receipt["tileDiagnosticsPath"]).read_text())
    stats = tiles.get("stats", {})
    if stats.get("transportExecutions") != 0 or stats.get("diskHits", 0) + stats.get("memoryHits", 0) < 1:
        raise GateFailure(f"Action was not served entirely from positive source cache: {path}")
    observations = tiles.get("recentResults", [])
    if not observations or any(item.get("status") not in {"SUCCESS_DISK_CACHE", "SUCCESS_MEMORY_CACHE"}
                               or item.get("color") != "hot" or item.get("activity") != "all"
                               for item in observations):
        raise GateFailure(f"Wrong palette or noncached tile outcome: {path}")
    observed_zooms = {item.get("zoom") for item in observations}
    if observed_zooms != expected_zooms:
        raise GateFailure(f"Selected source zooms differ: {path}: {observed_zooms}")
    receipt["observedZooms"] = sorted(observed_zooms)
    return receipt


def cancel_receipt(path: Path, expected: dict) -> dict:
    if not path.is_file():
        raise GateFailure(f"Production cancellation receipt missing: {path}")
    receipt = json.loads(path.read_text())
    for key, value in expected.items():
        if receipt.get(key) != value:
            raise GateFailure(f"Cancellation receipt {key} differs: {path}")
    if (receipt.get("producer") != "AlignWayAction.production-cancel-v1"
            or receipt.get("terminalState") != "CANCELLED"
            or receipt.get("noPreviewPublication") is not True
            or not isinstance(receipt.get("cancelNanos"), int)
            or receipt["cancelNanos"] <= 0):
        raise GateFailure(f"Cancellation did not reach the production terminal boundary: {path}")
    archive = path.parent / receipt.get("diagnosticsFile", "")
    if archive.parent != path.parent or not archive.is_file() or sha256(archive) != receipt.get("diagnosticsSha256"):
        raise GateFailure(f"Cancellation diagnostics absent or corrupt: {path}")
    with zipfile.ZipFile(archive) as bundle:
        if bundle.namelist().count("attempt-status.json") != 1:
            raise GateFailure(f"Cancellation status artifact absent or duplicated: {path}")
        attempt = json.loads(bundle.read("attempt-status.json"))
        if attempt.get("status") != "cancelled" or attempt.get("sourceLineage") != "managed-tiles":
            raise GateFailure(f"Cancellation diagnostic status differs: {path}")
    return receipt


def require_same_preview(reference: dict, run: dict, case_id: str) -> None:
    for key in ("selectedRasterSha256", "finalGeometrySha256", "kind"):
        if run.get(key) != reference.get(key):
            raise GateFailure(f"{case_id}: {key} differs")


def measured_median(runs: list[dict], repetitions: int, case_id: str) -> float:
    measured = [run["totalNanos"] for run in runs if run["phase"] == "measured"]
    warmups = [run for run in runs if run["phase"] == "warmup"]
    if len(warmups) < 1 or len(measured) != repetitions or repetitions < 3:
        raise GateFailure(f"{case_id}: warmup or measured repetitions are incomplete")
    return statistics.median(measured)


def require_speedup(baseline: float, candidate: float, case_id: str) -> float:
    if baseline <= 0 or candidate <= 0:
        raise GateFailure(f"{case_id}: nonpositive total action time")
    ratio = baseline / candidate
    if ratio < 2.0:
        raise GateFailure(f"{case_id}: cached total preview speedup {ratio:.3f}x is below 2x")
    return ratio


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)
    output = args.output.expanduser().resolve()
    output.mkdir(parents=True, exist_ok=True)
    report: dict = {"schemaVersion": 1, "status": "UNAVAILABLE", "runs": [],
                    "comparisons": [], "cases": {}}
    exit_code = 1
    locks = []
    previous_int = signal.getsignal(signal.SIGINT)
    previous_term = signal.getsignal(signal.SIGTERM)
    def interrupt(_signum, _frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGINT, interrupt)
    signal.signal(signal.SIGTERM, interrupt)
    try:
        locks.append(exclusive_lock(fixed_lock_path(output)))
        manifest_path = args.manifest.expanduser().resolve()
        manifest = json.loads(manifest_path.read_text())
        report["manifestSha256"] = sha256(manifest_path)
        if manifest.get("schemaVersion") != 1 or manifest.get("warmups", 0) < 1 or manifest.get("repetitions", 0) < 3:
            raise GateFailure("Manifest requires schema 1, >=1 warmup and >=3 measured repetitions")
        cases = manifest.get("cases")
        if not isinstance(cases, list) or len(cases) != 2 or len({c.get("id") for c in cases}) != 2:
            raise GateFailure("Exactly two distinct long cases are required")
        worktrees = sorted({Path(manifest[name]["worktree"]).expanduser().resolve()
                            for name in ("baseline", "candidate")})
        if len(worktrees) != 2:
            raise GateFailure("Baseline and candidate require distinct worktrees")
        runner_root = Path(__file__).resolve().parents[1]
        build_locks = {fixed_lock_path(root) for root in (*worktrees, runner_root)}
        for path in sorted(build_locks):
            locks.append(exclusive_lock(path))
        validated_worktrees = {name: validate_plugin(manifest[name], name, output)
                               for name in ("baseline", "candidate")}
        for case in cases:
            case_id = case["id"]
            if not isinstance(case_id, str) or not case_id.replace("-", "").isalnum():
                raise GateFailure("Case ID is not a safe token")
            for field in ("archive", "osm", "tiles"):
                required_file(case[field], f"{case_id} {field}")
        java, josm = check_host(manifest, output)
        plugins = {name: build_plugin(validated_worktrees[name], name, output)
                   for name in ("baseline", "candidate")}
        report["plugins"] = {name: {"revision": manifest[name]["revision"],
                                    "jarSha256": sha256(jar)} for name, jar in plugins.items()}
        timeout = int(manifest.get("runTimeoutSeconds", 900))
        if timeout < 60 or timeout > 3600:
            raise GateFailure("Run timeout must be 60-3600 seconds")
        for case in cases:
            case_id = case["id"]
            archive = required_file(case["archive"], f"{case_id} archive")
            osm = required_file(case["osm"], f"{case_id} OSM")
            tiles = required_file(case["tiles"], f"{case_id} tile inventory")
            grouped: dict[str, list[dict]] = {"baseline": [], "candidate": []}
            for version in ("baseline", "candidate"):
                for iteration in range(manifest["warmups"] + manifest["repetitions"]):
                    phase = "warmup" if iteration < manifest["warmups"] else "measured"
                    run_dir = output / case_id / version / f"{iteration:02d}-{phase}"
                    if run_dir.exists():
                        raise GateFailure(f"Run directory already exists; choose a fresh output: {run_dir}")
                    run_dir.mkdir(parents=True, exist_ok=True)
                    nonce = secrets.token_hex(16)
                    receipt = run_dir / "publication.json"
                    host_env = os.environ.copy()
                    host_env["JAVA_TOOL_OPTIONS"] = ""
                    command = host_command(
                        java, run_dir, josm, plugins[version], archive, osm, tiles,
                        receipt, nonce, f"{case_id}:{version}", "preview")
                    result = run_process(command, run_dir, run_dir / "host.log", timeout, host_env)
                    run_record = {"case": case_id, "version": version, "iteration": iteration,
                                  "phase": phase, **result}
                    report["runs"].append(run_record)
                    if result["exitCode"]:
                        raise GateFailure(f"Real host run failed: {result['log']}")
                    observed = receipt_file(receipt,
                        {"caseId": case_id, "version": version, "nonce": nonce},
                        {15} if version == "baseline" else {14, 15})
                    run_record.update(observed)
                    grouped[version].append(run_record)
            reference = grouped["baseline"][0]
            for version in ("baseline", "candidate"):
                for run in grouped[version]:
                    require_same_preview(reference, run, case_id)
                    comparison_log = output / case_id / "comparisons" / f"{version}-{run['iteration']:02d}.log"
                    compared = run_process([str(java), "-cp", os.pathsep.join((str(josm), str(plugins["candidate"]))),
                                            COMPARE_CLASS, reference["diagnosticsPath"],
                                            run["diagnosticsPath"]], output, comparison_log, 120)
                    report["comparisons"].append({"case": case_id, "version": version,
                                                  "iteration": run["iteration"], **compared})
                    if compared["exitCode"]:
                        raise GateFailure(f"Typed decision input differs: {comparison_log}")
            medians = {version: measured_median(grouped[version], manifest["repetitions"], case_id)
                       for version in ("baseline", "candidate")}
            ratio = require_speedup(medians["baseline"], medians["candidate"], case_id)
            report["cases"][case_id] = {"medianTotalNanos": medians, "speedup": ratio,
                                        "sameDecisionInput": True, "sameFinalGeometry": True}
            cancellation: dict[str, list[int]] = {"baseline": [], "candidate": []}
            for version in ("baseline", "candidate"):
                for iteration in range(3):
                    run_dir = output / case_id / version / f"cancel-{iteration:02d}"
                    if run_dir.exists():
                        raise GateFailure(f"Run directory already exists; choose a fresh output: {run_dir}")
                    run_dir.mkdir(parents=True, exist_ok=True)
                    nonce = secrets.token_hex(16)
                    receipt = run_dir / "cancellation.json"
                    command = host_command(
                        java, run_dir, josm, plugins[version], archive, osm, tiles,
                        receipt, nonce, f"{case_id}:{version}", "cancel")
                    result = run_process(command, run_dir, run_dir / "host.log", timeout)
                    record = {"case": case_id, "version": version, "iteration": iteration,
                              "phase": "cancel", **result}
                    report["runs"].append(record)
                    if result["exitCode"]:
                        raise GateFailure(f"Real cancellation host failed: {result['log']}")
                    observed = cancel_receipt(receipt,
                        {"caseId": case_id, "version": version, "nonce": nonce})
                    record.update(observed)
                    cancellation[version].append(observed["cancelNanos"])
            cancel_medians = {version: statistics.median(values)
                              for version, values in cancellation.items()}
            report["cases"][case_id]["medianCancellationNanos"] = cancel_medians
            if cancel_medians["candidate"] > cancel_medians["baseline"]:
                raise GateFailure(f"{case_id}: cancellation terminal response regressed")
        report["status"] = "PASS"
        exit_code = 0
    except (GateFailure, OSError, ValueError, KeyError, TypeError) as failure:
        report["failure"] = str(failure)
        report["status"] = "UNAVAILABLE" if "unavailable" in str(failure).lower() else "FAIL"
    except KeyboardInterrupt:
        report["failure"] = "Benchmark interrupted; process group stopped"
        report["status"] = "INTERRUPTED"
    finally:
        (output / "benchmark.json").write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
        (output / "summary.txt").write_text(
            f"Status: {report['status']}\nReason: {report.get('failure', 'none')}\n")
        for lock in reversed(locks):
            lock.close()
        signal.signal(signal.SIGINT, previous_int)
        signal.signal(signal.SIGTERM, previous_term)
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
