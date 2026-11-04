#!/usr/bin/env python3
"""Optional public CI comparison of ordinary Legacy kernels; never a speed qualification."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import statistics
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BASELINE_REVISION = "323c2874aa5af4a4c2f0cae618f47f6a36d10803"
PRODUCTION_BLOBS = {"src/main": "d69737612ada25d5712a6b258c33ea473bd5c20d",
                    "build.gradle.kts": "9e14906021c580004e5f669ffb1b8c17025bb5dd",
                    "gradle.properties": "861eff2ef2518e8b4f09ac4085b3e8b723f6aafd"}
FIXTURES = ("parallel-185", "weak-gap-185", "switchback-512")
FIXTURE_HASHES = dict(zip(FIXTURES, ("8e2c0425a8efd9eb7733e6d19119f7ed4a8d9960e03719b5c230b764250e1448",
    "d765915b14beaf7548dc2aef9e569cd3c102343ee4f0ae017eaf0c1a14a45271", "c2aa8c1b901d6d43925d89c26aef9023781ea993ff91be1fe2f9fa2a6f36c280")))
VERSIONS = ("RC14", "CORRECTED323")
PROTOCOL = {"schema": "ordinary-legacy-public-runtime-v1", "warmup_batches": 100, "warmup_calls": 32,
            "readiness_window": 20, "median_lower": .9, "median_upper": 1.1, "fitted_drift_max": .1,
            "measured_pairs": 16, "measured_calls": 64, "order": "alternating AB/BA; eight measured starts each"}
MAX_CALLS = 25_344
TIMEOUT_SECONDS = 480
DISK_FLOOR = 512 * 1024 * 1024
RELEASE_URL = "https://github.com/holubp/josm-wayheatmaptracer/releases/download/v0.22.0-rc.14/wayheatmaptracer.jar"
RELEASE_SIZE = 3_011_620
RELEASE_SHA = "af0fe6f4127cb44c6ce57e60070dd415361520d2010580bebf20ff9c2545514c"
HOST_SIZE = 21_355_074
HOST_SHA = "d2e2310e24fdf8f4b2d18094997446e443d5894e86f6eeac27a4241bad40338e"
PACKAGE = "org.openstreetmap.josm.plugins.wayheatmaptracer.benchmark"
HELPERS = tuple(ROOT / "src/tools/java" / Path(PACKAGE.replace(".", "/")) / name
                for name in ("LegacyRuntimeProbe.java", "LegacyRuntimeBenchmark.java"))
HEX = re.compile(r"[0-9a-f]{64}\Z")


class Refusal(RuntimeError):
    """A bounded source, protocol, artifact or result refusal."""


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()


def sha(path):
    if path.is_symlink() or not path.is_file():
        raise Refusal("Expected regular public artifact")
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(65536), b""):
            digest.update(block)
    return digest.hexdigest()


def git(root, *args):
    try:
        return subprocess.check_output(["git", "-c", "safe.directory=*", "-C", str(root), *args],
                                       text=True, stderr=subprocess.DEVNULL).strip()
    except (OSError, subprocess.CalledProcessError) as error:
        raise Refusal("Production source identity unavailable") from error


def source_guard(root, expected=None):
    """Require exact reviewed production blobs and a clean production working tree."""
    expected = PRODUCTION_BLOBS if expected is None else expected
    actual = {path: git(root, "rev-parse", f"HEAD:{path}") for path in expected}
    if actual != expected or git(root, "status", "--porcelain", "--untracked-files=all", "--ignored", "--", *expected):
        raise Refusal("Production source differs from reviewed corrected baseline")
    return {"production_baseline": BASELINE_REVISION, "production_blobs": actual, "helper_commit": git(root, "rev-parse", "HEAD")}


def require_ci(environment):
    required = {"GITHUB_ACTIONS": "true", "GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_RUN_ATTEMPT": "1",
                "ORDINARY_LEGACY_BENCHMARK": "true"}
    if any(environment.get(key) != value for key, value in required.items()):
        raise Refusal("Only a fresh explicit public CI dispatch may execute kernels")


def java_command(java, classes, baseline, corrected, host):
    return [str(java), "-Xms512m", "-Xmx512m", "-Djava.awt.headless=true", "-cp", str(classes),
            PACKAGE + ".LegacyRuntimeBenchmark", "--benchmark", str(baseline), str(corrected), str(host)]


def verify_jar(path, expected_sha, expected_size):
    if path.is_symlink() or not path.is_file() or path.stat().st_size != expected_size or sha(path) != expected_sha:
        raise Refusal("Pinned public JAR size/hash mismatch")
    try:
        with zipfile.ZipFile(path) as archive:
            if archive.testzip() is not None:
                raise Refusal("Corrupt public JAR")
    except zipfile.BadZipFile as error:
        raise Refusal("Unreadable public JAR") from error
    return expected_sha


def fetch_baseline(output, opener=urllib.request.urlopen):
    """One bounded unauthenticated public request; no mirrors or retry loop."""
    destination = output / "rc14.jar"
    try:
        with opener(RELEASE_URL, timeout=30) as response:
            body = response.read(RELEASE_SIZE + 1)
        if len(body) != RELEASE_SIZE or hashlib.sha256(body).hexdigest() != RELEASE_SHA:
            raise Refusal("Pinned public release content mismatch")
        with destination.open("xb") as target:
            target.write(body)
        verify_jar(destination, RELEASE_SHA, RELEASE_SIZE)
    except Refusal:
        raise
    except Exception as error:
        # Never export arbitrary HTTP/redirect/exception text or inherited credentials.
        raise Refusal("Public release download unavailable") from error
    return destination


def split_fields(line):
    result = {}
    for item in line.split():
        if "=" in item:
            key, value = item.split("=", 1)
            if key in result:
                raise Refusal("Duplicate evidence field")
            result[key] = value
    return result


def parse_log(text, partial=False):
    """Validate fixed-order cells; never turn a partial or unknown run into a result."""
    trailing_fragment = partial and bool(text) and not text.endswith("\n")
    if trailing_fragment:
        text = text.rpartition("\n")[0]
    samples, readiness, facts, completed = [], {}, {}, []
    outcome, stop_fixture = None, None
    for line in text.splitlines():
        row = split_fields(line)
        if line.startswith("fixture="):
            fixture = row.get("fixture")
            if fixture not in FIXTURES or fixture in facts or row.get("input") != FIXTURE_HASHES[fixture]:
                raise Refusal("Unknown or duplicate public fixture")
            facts[fixture] = row["input"]
        elif line.startswith("readiness "):
            key = row.get("fixture"), row.get("version")
            if key[0] not in facts or key[1] not in VERSIONS or key in readiness or row.get("ready") not in ("true", "false"):
                raise Refusal("Invalid readiness evidence")
            try:
                ratio, drift = float(row["medianRatio"]), float(row["fittedDrift"])
            except (KeyError, ValueError):
                ratio, drift = math.nan, math.nan
            agrees = math.isfinite(ratio) and math.isfinite(drift) and .9 <= ratio <= 1.1 and abs(drift) <= .1
            if (row["ready"] == "true") != agrees:
                raise Refusal("Readiness flag contradicts its observations")
            readiness[key] = row
        elif line.startswith("sample "):
            required = {"fixture", "version", "phase", "index", "calls", "batchNanos", "output", "batchOutput", "candidates",
                        "compilerBefore", "compilerAfter", "gcCountBefore", "gcCountAfter", "gcMillisBefore", "gcMillisAfter"}
            if set(row) != required or row["fixture"] not in facts or row["version"] not in VERSIONS:
                raise Refusal("Unknown sample fields or source")
            try:
                for key in required - {"fixture", "version", "phase", "output", "batchOutput"}:
                    row[key] = int(row[key])
            except ValueError as error:
                raise Refusal("Invalid numeric evidence") from error
            if row["batchNanos"] <= 0 or row["candidates"] < 0 or not HEX.fullmatch(row["output"]) or not HEX.fullmatch(row["batchOutput"]):
                raise Refusal("Missing positive duration or complete output bits")
            if row["phase"] == "MEASURED" and any(readiness.get((row["fixture"], v), {}).get("ready") != "true" for v in VERSIONS):
                raise Refusal("Measurement preceded readiness")
            for before, after, delta in (("compilerBefore", "compilerAfter", "compilation_delta"),
                                         ("gcCountBefore", "gcCountAfter", "gc_count_delta"),
                                         ("gcMillisBefore", "gcMillisAfter", "gc_millis_delta")):
                row[delta] = row[after] - row[before] if 0 <= row[before] <= row[after] else None
            samples.append(row)
        elif line.startswith("fixtureComplete="):
            completed.append(row["fixtureComplete"])
        elif line.startswith("outcome="):
            if outcome is not None:
                raise Refusal("Duplicate terminal outcome")
            outcome, stop_fixture = row.get("outcome"), row.get("fixture")
            if outcome not in ("COMPLETE", "INCONCLUSIVE") or (outcome == "INCONCLUSIVE" and row.get("reason") != "WARMUP_NOT_READY"):
                raise Refusal("Unknown terminal outcome")
    expected = []
    for fixture in FIXTURES:
        for phase, count, calls in (("WARMUP", 100, 32), ("MEASURED", 16, 64)):
            for index in range(count):
                for version in (VERSIONS if index % 2 == 0 else tuple(reversed(VERSIONS))):
                    expected.append((fixture, version, phase, index, calls))
    actual = [(s["fixture"], s["version"], s["phase"], s["index"], s["calls"]) for s in samples]
    if actual != expected[:len(actual)] or len(actual) > len(expected):
        raise Refusal("Fixed schedule changed")
    for fixture in facts:
        if len({s["output"] for s in samples if s["fixture"] == fixture}) > 1:
            raise Refusal("Hard complete-output disagreement")
    if outcome == "COMPLETE":
        if actual != expected or completed != list(FIXTURES) or list(facts) != list(FIXTURES):
            raise Refusal("False complete matrix")
    elif outcome == "INCONCLUSIVE":
        count = len(completed)
        if count >= len(FIXTURES) or completed != list(FIXTURES[:count]) or stop_fixture != FIXTURES[count]:
            raise Refusal("Non-prefix inconclusive stop")
        expected_cells = count * 232 + 200
        if len(samples) != expected_cells or list(facts) != list(FIXTURES[:count+1]) or not any(readiness.get((stop_fixture, v), {}).get("ready") == "false" for v in VERSIONS):
            raise Refusal("False readiness stop")
    elif not partial:
        raise Refusal("Missing terminal outcome")
    summary = {}
    for fixture in completed:
        rows = [s for s in samples if s["fixture"] == fixture and s["phase"] == "MEASURED"]
        if len(rows) != 32:
            raise Refusal("Incomplete measured fixture")
        pairs = [{s["version"]: s["batchNanos"] / 64 / 1e6 for s in rows if s["index"] == i} for i in range(16)]
        logs = [math.log(p[VERSIONS[1]] / p[VERSIONS[0]]) for p in pairs]
        ordered = sorted(logs)
        summary[fixture] = {v: {"median_ms": statistics.median(p[v] for p in pairs),
                                  "range_ms": [min(p[v] for p in pairs), max(p[v] for p in pairs)]} for v in VERSIONS}
        summary[fixture].update(paired_log_ratios=logs, paired_median_ratio=math.exp(statistics.median(logs)),
                                paired_ratio_range=[math.exp(min(logs)), math.exp(max(logs))],
                                paired_median_by_order={order: math.exp(statistics.median(logs[i] for i in range(16) if i % 2 == parity)) for order, parity in (("AB", 0), ("BA", 1))},
                                conditional_median_interval={"order_statistics": [4, 13], "ratio": [math.exp(ordered[3]), math.exp(ordered[12])],
                                    "nominal_coverage_if_independent_continuous": 1 - 2 * sum(math.comb(16, k) for k in range(4)) / 2**16,
                                    "within_jvm_dependence_not_accounted": True})
    return {"outcome": outcome or "INCONCLUSIVE", "samples": samples, "readiness": list(readiness.values()),
            "completed_calls": sum(s["calls"] for s in samples), "measured_calls": sum(s["calls"] for s in samples if s["phase"] == "MEASURED"),
            "active_batch_calls": "UNKNOWN" if partial and outcome is None else None, "trailing_log_fragment": trailing_fragment,
            "summary": summary, "no_tolerance_decision": True, "no_automatic_retry": True}


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--execute", action="store_true", help="only in the default-off public CI dispatch step")
    result.add_argument("--validation-output", type=Path)
    result.add_argument("--host-jar", type=Path)
    result.add_argument("--output", type=Path)
    return result


def write_atomic(path, lines):
    """Replace a receipt only after its complete sibling staging file closes."""
    staging = path.with_name("." + path.name + ".tmp")
    created = False
    try:
        with staging.open("x", encoding="utf-8") as target:
            created = True
            for line in lines:
                target.write(line)
        os.replace(staging, path)
    finally:
        if created:
            try:
                staging.unlink(missing_ok=True)
            except OSError:
                pass  # Cleanup must not replace the primary serialization/write/replace failure.


def write_json(path, value):
    write_atomic(path, [json.dumps(value, indent=2, allow_nan=False) + "\n"])


def execute(args):
    require_ci(os.environ)
    if any(p is None for p in (args.validation_output, args.host_jar, args.output)):
        raise Refusal("Public validation artifacts and fresh output are required")
    output = args.output
    output.mkdir(parents=True, exist_ok=False)
    environment = {}
    try:
        write_json(output / "summary.json", {"outcome": "INCONCLUSIVE", "reason": "EXECUTION_NOT_FINISHED",
                                           "completed_calls": None, "active_batch_calls": "UNKNOWN",
                                           "scope": "PUBLIC_ORDINARY_KERNEL_ONLY", "no_tolerance_decision": True, "no_automatic_retry": True})
        write_json(output / "protocol.json", {**PROTOCOL, "max_calls": MAX_CALLS, "timeout_seconds": TIMEOUT_SECONDS})
        source = source_guard(ROOT)
        report = args.validation_output / "summary.json"
        if report.is_symlink() or not report.is_file() or report.stat().st_size > 1_048_576:
            raise Refusal("Public validation receipt unavailable")
        validation = json.loads(report.read_text())
        if validation.get("result") != "PASS" or validation.get("profile") != "public" or validation.get("private_fixture_scope") != "not-executed":
            raise Refusal("Successful public validation is required")
        corrected = args.validation_output / "artifacts/wayheatmaptracer.jar"
        corrected_hash = sha(corrected)
        if corrected_hash != sha(ROOT / "build/libs/wayheatmaptracer.jar"):
            raise Refusal("Validated plugin artifact changed")
        verify_jar(args.host_jar, HOST_SHA, HOST_SIZE)
        if os.statvfs(output).f_bavail * os.statvfs(output).f_frsize < DISK_FLOOR:
            raise Refusal("512MiB disk reserve required")
        java_home = Path(os.environ["JAVA_HOME"])
        java, javac = java_home / "bin/java", java_home / "bin/javac"
        baseline = fetch_baseline(output)
        classes = output / "helper-classes"
        classes.mkdir()
        child_env = {k: v for k, v in os.environ.items() if k in {"PATH", "JAVA_HOME", "HOME", "LANG", "LC_ALL", "TZ", "TMPDIR",
                     "GITHUB_ACTIONS", "GITHUB_EVENT_NAME", "GITHUB_RUN_ATTEMPT", "ORDINARY_LEGACY_BENCHMARK"}}
        with (output / "controls.log").open("x") as log:
            subprocess.run([str(javac), "--release", "17", "-cp", os.pathsep.join((str(baseline), str(args.host_jar))),
                            "-d", str(classes), *(str(p) for p in HELPERS)], env=child_env, stdout=log, stderr=log, timeout=60, check=True)
            for main, cp in (("LegacyRuntimeProbe", os.pathsep.join((str(classes), str(baseline), str(args.host_jar)))), ("LegacyRuntimeBenchmark", str(classes))):
                subprocess.run([str(java), "-Xmx512m", "-Djava.awt.headless=true", "-cp", cp, PACKAGE + "." + main, "--controls"],
                               env=child_env, stdout=log, stderr=log, timeout=30, check=True)
        def fence():
            return {"source": source_guard(ROOT), "corrected_jar_sha256": sha(corrected), "rc14_jar_sha256": sha(baseline),
                    "host_jar_sha256": sha(args.host_jar), "java_sha256": sha(java), "javac_sha256": sha(javac),
                    "helpers": {p.name: sha(p) for p in HELPERS}, "launcher": sha(Path(__file__)),
                    "helper_classes": {str(p.relative_to(classes)): sha(p) for p in sorted(classes.rglob("*.class"))}, "protocol": PROTOCOL,
                    "fixture_input_hashes": FIXTURE_HASHES}
        before = fence()
        fingerprint = hashlib.sha256(canonical(before)).hexdigest()
        environment = {"schema": PROTOCOL["schema"], "fingerprint": fingerprint, "identities": before,
                       "jvm_flags": ["-Xms512m", "-Xmx512m", "-Djava.awt.headless=true"], "baseline_public_url": RELEASE_URL}
        write_json(output / "environment.json", environment)
        command = java_command(java, classes, baseline, corrected, args.host_jar) + [str(output), fingerprint]
        timed_out = False
        with (output / "runtime.log").open("x") as log:
            try:
                status = subprocess.run(command, env=child_env, stdout=log, stderr=log, timeout=TIMEOUT_SECONDS).returncode
            except subprocess.TimeoutExpired:
                timed_out, status = True, None
        if fence() != before:
            raise Refusal("Source/class/JAR/helper fence changed")
        text = (output / "runtime.log").read_text()
        if status not in (0, None):
            raise Refusal("Hard JVM or full-bit refusal; inspect public runtime log")
        receipt = parse_log(text, partial=timed_out)
        if timed_out:
            receipt.update(outcome="INCONCLUSIVE", reason="HOST_TIMEOUT", active_batch_calls="UNKNOWN")
        samples = receipt.pop("samples")
        write_atomic(output / "samples.jsonl", (json.dumps(sample, allow_nan=False) + "\n" for sample in samples))
        write_json(output / "summary.json", {**receipt, "fingerprint": fingerprint, "scope": "PUBLIC_ORDINARY_KERNEL_ONLY", "fences_unchanged": True})
        print("Ordinary Legacy benchmark: " + receipt["outcome"] + "; no speed threshold or workflow qualification")
        return 0
    except Exception as error:
        cause = type(error).__name__
        try:
            write_json(output / "summary.json", {"outcome": "REFUSED", "cause": cause, "scope": "PUBLIC_ORDINARY_KERNEL_ONLY",
                                               "no_tolerance_decision": True, "no_automatic_retry": True})
        except Exception:
            print("Public benchmark evidence export failed; prior receipt retained when available")
        print("Public benchmark refused; cause=" + cause + "; no result or automatic retry")
        return 1


def main(argv=None):
    args = parser().parse_args(argv)
    if not args.execute:
        print(json.dumps({"state": "METADATA_ONLY_NO_KERNEL", "source": source_guard(ROOT), "protocol": PROTOCOL, "fixtures": FIXTURE_HASHES}, indent=2))
        return 0
    return execute(args)


if __name__ == "__main__":
    raise SystemExit(main())
