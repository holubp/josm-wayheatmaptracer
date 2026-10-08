#!/usr/bin/env python3
"""Run fixed public or private JOSM validation stages without an agent service."""

from __future__ import annotations

import argparse
import csv
import fcntl
import hashlib
import json
import importlib.metadata
import os
import signal
import shlex
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import zipfile
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
TIMEOUT_SECONDS = 4 * 60 * 60
MAX_MANIFEST_BYTES = 16 * 1024 * 1024
SHA256_LENGTH = 64
ACTIVE_PROCESS: subprocess.Popen[str] | None = None
INTERRUPTED = False


class ValidationError(RuntimeError):
    """A validation input, safety, or artifact check failed."""


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def atomic_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + f".{os.getpid()}.tmp")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def outside_build(path: Path) -> Path:
    resolved = path.expanduser().resolve()
    build = (ROOT / "build").resolve()
    if resolved == build or build in resolved.parents:
        raise ValidationError("output must be outside the repository build directory")
    return resolved


def regular_external_input(path: Path | None, label: str, required: bool) -> Path | None:
    if path is None:
        if required:
            raise ValidationError(f"{label} is required for the RC profile")
        return None
    unresolved = path.expanduser().absolute()
    if unresolved.is_symlink():
        raise ValidationError(f"{label} must not be a symlink")
    resolved = unresolved.resolve(strict=False)
    if ROOT == resolved or ROOT in resolved.parents:
        raise ValidationError(f"{label} must remain outside the repository")
    return resolved


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _json_file(path: Path) -> tuple[dict[str, Any], str]:
    if path.is_symlink() or not path.is_file() or path.stat().st_size <= 0 or path.stat().st_size > MAX_MANIFEST_BYTES:
        raise ValidationError("manifest must be a bounded regular non-symlink file")
    raw = path.read_bytes()
    def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        value: dict[str, Any] = {}
        for key, item in pairs:
            if key in value:
                raise ValidationError("manifest contains a duplicate JSON key")
            value[key] = item
        return value
    try:
        value = json.loads(raw, object_pairs_hook=unique_object)
    except (UnicodeDecodeError, ValueError) as exc:
        raise ValidationError("manifest JSON is invalid") from exc
    if not isinstance(value, dict):
        raise ValidationError("manifest root must be an object")
    return value, hashlib.sha256(raw).hexdigest()


def _external_file(path_value: str, base: Path) -> Path:
    if "\0" in path_value:
        raise ValidationError("manifest input path is malformed")
    raw = Path(path_value)
    unresolved = raw if raw.is_absolute() else base / raw
    if unresolved.is_symlink():
        raise ValidationError("manifest input must not be a symlink")
    try:
        path = unresolved.resolve(strict=False)
    except (OSError, ValueError) as exc:
        raise ValidationError("manifest input path is malformed") from exc
    if ROOT == path or ROOT in path.parents or not path.is_file():
        raise ValidationError("manifest input is missing or inside the repository")
    return path


def _required_file(value: Any, base: Path) -> str:
    if not isinstance(value, dict) or set(value) != {"path", "sha256"}:
        raise ValidationError("manifest required_file must contain exactly path and sha256")
    path_value, expected = value["path"], value["sha256"]
    if not isinstance(path_value, str) or not path_value or not isinstance(expected, str) or len(expected) != SHA256_LENGTH:
        raise ValidationError("manifest required_file path or SHA-256 is malformed")
    path = _external_file(path_value, base)
    actual = sha256_file(path)
    if actual != expected.lower() or any(char not in "0123456789abcdef" for char in expected.lower()):
        raise ValidationError("manifest input SHA-256 mismatch")
    return actual


def _tile_payload_identity(value: Any, base: Path) -> list[tuple[str, str]]:
    """Hash every PNG named by the benchmark's reviewed TSV input."""
    if not isinstance(value, dict) or set(value) != {"path", "sha256"}:
        raise ValidationError("benchmark tiles must be a required_file descriptor")
    tsv_hash = _required_file(value, base)
    tsv_path = _external_file(value["path"], base)
    if tsv_path.suffix.lower() != ".tsv":
        raise ValidationError("benchmark tiles input must be a TSV file")
    try:
        with tsv_path.open("r", encoding="utf-8-sig", newline="") as stream:
            reader = csv.DictReader(stream, delimiter="\t")
            if (not reader.fieldnames or len(set(reader.fieldnames)) != len(reader.fieldnames)
                    or "relativePath" not in reader.fieldnames):
                raise ValidationError("benchmark tiles TSV must have a relativePath column")
            rows = list(reader)
    except (OSError, UnicodeError, csv.Error) as exc:
        raise ValidationError("benchmark tiles TSV is malformed") from exc
    if not rows:
        raise ValidationError("benchmark tiles TSV must reference PNG payloads")
    evidence: list[tuple[str, str]] = [("tsv", tsv_hash)]
    seen: set[str] = set()
    for row in rows:
        if None in row:
            raise ValidationError("benchmark tiles TSV has malformed columns")
        relative = row.get("relativePath")
        if (not relative or "\0" in relative or "\\" in relative or Path(relative).is_absolute()
                or ".." in Path(relative).parts or relative in seen):
            raise ValidationError("benchmark tiles TSV contains an unsafe or duplicate relativePath")
        seen.add(relative)
        unresolved = tsv_path.parent
        for component in Path(relative).parts:
            unresolved = unresolved / component
            if unresolved.is_symlink():
                raise ValidationError("benchmark tile PNG path must not traverse symlinks")
        png = unresolved.resolve(strict=False)
        if tsv_path.parent not in png.parents or ROOT == png or ROOT in png.parents or not png.is_file():
            raise ValidationError("benchmark tile PNG is missing or outside its TSV directory")
        if png.suffix.lower() != ".png":
            raise ValidationError("benchmark tiles TSV references a non-PNG payload")
        with png.open("rb") as source:
            if source.read(8) != b"\x89PNG\r\n\x1a\n":
                raise ValidationError("benchmark tiles TSV references an invalid PNG payload")
        evidence.append((relative, sha256_file(png)))
    return evidence


def _worktree_identity(value: Any, base: Path) -> str:
    if not isinstance(value, dict) or not isinstance(value.get("worktree"), str):
        raise ValidationError("benchmark worktree identity is malformed")
    revision, source_hashes = value.get("revision"), value.get("sourceHashes")
    if not isinstance(revision, str) or len(revision) != 40 or any(c not in "0123456789abcdef" for c in revision.lower()):
        raise ValidationError("benchmark worktree revision must be a full Git SHA-1")
    worktree_value = Path(value["worktree"])
    if "\0" in value["worktree"] or not worktree_value.is_absolute():
        raise ValidationError("benchmark worktree path must be absolute")
    if worktree_value.is_symlink():
        raise ValidationError("benchmark worktree must not be a symlink")
    worktree = worktree_value.resolve(strict=False)
    if worktree == ROOT or ROOT in worktree.parents or worktree in ROOT.parents or not worktree.is_dir():
        raise ValidationError("benchmark worktree is missing or aliases this runner repository")
    if not isinstance(source_hashes, dict) or not source_hashes:
        raise ValidationError("benchmark sourceHashes must be a nonempty path-to-SHA-256 object")
    actual_head = subprocess.run(["git", "-c", f"safe.directory={worktree}", "-C", str(worktree),
                                  "rev-parse", "HEAD"], text=True, stdout=subprocess.PIPE,
                                 stderr=subprocess.PIPE, check=False, timeout=30)
    if actual_head.returncode != 0 or actual_head.stdout.strip().lower() != revision.lower():
        raise ValidationError("benchmark worktree revision mismatch")
    hashes: list[tuple[str, str]] = []
    declared_paths: set[str] = set()
    for relative, expected in sorted(source_hashes.items()):
        if (not isinstance(relative, str) or not relative or Path(relative).is_absolute()
                or "\0" in relative or "\\" in relative or ".." in Path(relative).parts
                or not isinstance(expected, str)
                or len(expected) != SHA256_LENGTH):
            raise ValidationError("benchmark sourceHashes entry is malformed")
        source = worktree / relative
        if source.is_symlink() or not source.is_file():
            raise ValidationError("benchmark sourceHashes references a missing or linked file")
        actual = sha256_file(source)
        if actual != expected.lower() or any(c not in "0123456789abcdef" for c in expected.lower()):
            raise ValidationError("benchmark sourceHashes mismatch")
        hashes.append((relative, actual))
        declared_paths.add(relative)
    changed = subprocess.run(["git", "-c", f"safe.directory={worktree}", "-C", str(worktree),
                              "diff", "--name-only", "-z", "HEAD"], stdout=subprocess.PIPE,
                           stderr=subprocess.PIPE, check=False, timeout=30)
    untracked = subprocess.run(["git", "-c", f"safe.directory={worktree}", "-C", str(worktree),
                                "ls-files", "--others", "--exclude-standard", "-z"],
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False, timeout=30)
    if changed.returncode != 0 or untracked.returncode != 0:
        raise ValidationError("benchmark worktree delta could not be inspected")
    changed_paths = {os.fsdecode(item) for item in changed.stdout.split(b"\0") if item}
    untracked_paths = {os.fsdecode(item) for item in untracked.stdout.split(b"\0") if item}
    if not (changed_paths | untracked_paths) <= declared_paths:
        raise ValidationError("benchmark sourceHashes does not cover every worktree change")
    return hashlib.sha256(json.dumps({"revision": revision.lower(), "sources": hashes,
                                      "changed": sorted(changed_paths),
                                      "untracked": sorted(untracked_paths)}, sort_keys=True).encode()).hexdigest()


def benchmark_manifest_identity(path: Path) -> str:
    manifest, manifest_hash = _json_file(path)
    root_fields = {"schemaVersion", "warmups", "repetitions", "runTimeoutSeconds",
                   "environment", "baseline", "candidate", "cases"}
    if set(manifest) != root_fields:
        raise ValidationError("benchmark manifest fields do not match schema version 1")
    if type(manifest.get("schemaVersion")) is not int or manifest["schemaVersion"] != 1:
        raise ValidationError("unsupported benchmark manifest schema")
    if (type(manifest.get("warmups")) is not int or manifest["warmups"] < 1
            or type(manifest.get("repetitions")) is not int or manifest["repetitions"] < 3
            or type(manifest.get("runTimeoutSeconds")) is not int
            or not 1 <= manifest["runTimeoutSeconds"] <= TIMEOUT_SECONDS):
        raise ValidationError("benchmark warmups, repetitions, or timeout are outside supported bounds")
    environment = manifest.get("environment")
    if not isinstance(environment, dict) or set(environment) != {"java", "javaMajor", "josmVersion", "josmJar"}:
        raise ValidationError("benchmark environment is missing")
    java_value = environment.get("java")
    if (not isinstance(java_value, str) or not Path(java_value).is_absolute()
            or type(environment.get("javaMajor")) is not int or environment.get("javaMajor") != 17
            or not isinstance(environment.get("josmVersion"), str)):
        raise ValidationError("benchmark environment must pin an absolute Java 17 binary")
    java_path = Path(java_value)
    if java_path.is_symlink() or not java_path.is_file() or not os.access(java_path, os.X_OK):
        raise ValidationError("benchmark Java binary is unavailable")
    selected_java = shutil.which("java")
    if selected_java is None or Path(selected_java).resolve() != java_path.resolve():
        raise ValidationError("benchmark Java binary differs from the active Gradle Java")
    java_version = subprocess.run([str(java_path), "-version"], text=True, stdout=subprocess.PIPE,
                                  stderr=subprocess.STDOUT, check=False, timeout=15)
    if java_version.returncode != 0 or 'version "17' not in java_version.stdout:
        raise ValidationError("benchmark Java binary does not report Java 17")
    expected_josm = next((line.partition("=")[2].strip() for line in
                          (ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines()
                          if line.startswith("josmVersion=")), None)
    if environment.get("josmVersion") != expected_josm:
        raise ValidationError("benchmark JOSM version differs from this build")
    josm_jar_hash = _required_file(environment.get("josmJar"), path.parent)
    pairs = [("environment.java", sha256_file(java_path)),
             ("environment.josmJar", josm_jar_hash)]
    for name in ("baseline", "candidate"):
        worktree = manifest.get(name)
        if not isinstance(worktree, dict) or set(worktree) != {"worktree", "revision", "sourceHashes"}:
            raise ValidationError(f"benchmark {name} fields do not match the approved schema")
        pairs.append((name, _worktree_identity(manifest.get(name), path.parent)))
    cases = manifest.get("cases")
    if not isinstance(cases, list) or len(cases) != 2 or [item.get("id") for item in cases if isinstance(item, dict)] != ["N1", "N2"]:
        raise ValidationError("benchmark manifest must contain the exact N1 and N2 cases")
    for case in cases:
        if not isinstance(case, dict) or set(case) != {"id", "archive", "osm", "tiles"}:
            raise ValidationError("benchmark case is malformed")
        for field in ("archive", "osm", "tiles"):
            if field == "tiles":
                pairs.extend((f"case.{case['id']}.tiles.{name}", digest)
                             for name, digest in _tile_payload_identity(case.get(field), path.parent))
            else:
                pairs.append((f"case.{case['id']}.{field}", _required_file(case.get(field), path.parent)))
    return hashlib.sha256(json.dumps({"manifest": manifest_hash, "evidence": pairs}, sort_keys=True).encode()).hexdigest()


def replay_manifest_identity(path: Path) -> str:
    manifest, manifest_hash = _json_file(path)
    if manifest.get("schema") != "wayheatmaptracer-v022-corpus-1":
        raise ValidationError("unsupported strict replay manifest schema")
    cases = manifest.get("cases")
    if not isinstance(cases, list) or not cases:
        raise ValidationError("strict replay manifest has no cases")
    evidence: list[tuple[str, str]] = []
    for case in cases:
        if not isinstance(case, dict) or not isinstance(case.get("sourcePath"), str):
            raise ValidationError("strict replay case source is malformed")
        expected = case.get("outerSha256")
        if not isinstance(expected, str) or len(expected) != SHA256_LENGTH or any(c not in "0123456789abcdef" for c in expected.lower()):
            raise ValidationError("strict replay outer hash is malformed")
        source = _external_file(case["sourcePath"], ROOT)
        actual = sha256_file(source)
        if actual != expected.lower():
            raise ValidationError("strict replay outer archive SHA-256 mismatch")
        bundle_hash = case.get("bundleSha256")
        if not isinstance(bundle_hash, str) or len(bundle_hash) != SHA256_LENGTH or any(c not in "0123456789abcdef" for c in bundle_hash.lower()):
            raise ValidationError("strict replay bundle hash is malformed")
        evidence.append((case.get("caseId", ""), actual + bundle_hash.lower()))
    return hashlib.sha256(json.dumps({"manifest": manifest_hash, "archives": sorted(evidence)}, sort_keys=True).encode()).hexdigest()


def manifest_input_identity(path: Path) -> str:
    """Bind a private benchmark or strict replay manifest and its referenced payloads."""
    manifest, _ = _json_file(path)
    if "schemaVersion" in manifest:
        return benchmark_manifest_identity(path)
    return replay_manifest_identity(path)


def source_identity() -> str:
    digest = hashlib.sha256()
    listed = subprocess.run(
        ["git", "-c", f"safe.directory={ROOT}", "ls-files", "-z"], cwd=ROOT, check=True, stdout=subprocess.PIPE,
    ).stdout.split(b"\0")
    paths = {ROOT / os.fsdecode(raw) for raw in listed if raw}
    paths.update({ROOT / "scripts/run-validation.py", ROOT / "scripts/tests/test_unattended_validation.py"})
    for path in sorted(paths):
        try:
            relative = path.relative_to(ROOT).as_posix().encode()
            digest.update(relative + b"\0")
            if path.is_symlink():
                digest.update(b"link\0" + os.readlink(path).encode())
            elif path.is_file():
                with path.open("rb") as source:
                    for block in iter(lambda: source.read(1024 * 1024), b""):
                        digest.update(block)
        except (OSError, ValueError):
            digest.update(b"unavailable\0")
    return digest.hexdigest()


def command_identity() -> dict[str, str]:
    result: dict[str, str] = {}
    for name in ("sh", "python3", "java", "git"):
        path = shutil.which(name)
        if path is None:
            result[name] = "missing"
            continue
        result[name] = str(Path(path).resolve())
        if name in {"python3", "java"}:
            version = subprocess.run([path, "--version"], text=True, stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT, check=False, timeout=15)
            result[name + "_version"] = version.stdout.strip()
    try:
        result["pytest_version"] = importlib.metadata.version("pytest")
    except importlib.metadata.PackageNotFoundError:
        result["pytest_version"] = "missing"
    return result


def run_identity(profile: str, benchmark: Path | None, replay: Path | None) -> tuple[str, dict[str, str | None], str | None]:
    input_error = None

    def input_hash(path: Path | None) -> str | None:
        nonlocal input_error
        if path is None or not path.is_file():
            return "missing" if path else None
        try:
            return manifest_input_identity(path)
        except (ValidationError, OSError, ValueError, subprocess.SubprocessError):
            input_error = "private manifest input validation failed"
            try:
                return "invalid:" + sha256_file(path)
            except OSError:
                return "invalid:unreadable"

    details: dict[str, str | None] = {
        "source_sha256": source_identity(),
        "benchmark_input_sha256": input_hash(benchmark),
        "replay_input_sha256": input_hash(replay),
        # Hash environment and executable versions without persisting possibly secret values.
        "environment_sha256": hashlib.sha256(json.dumps({
            "variables": sorted(os.environ.items()),
            "commands": command_identity(),
            "python": sys.version,
            "platform": sys.platform,
        }, sort_keys=True).encode()).hexdigest(),
        "profile": profile,
    }
    if input_error:
        details["manifest_validation"] = "invalid"
    else:
        details["manifest_validation"] = "valid"
    identity = hashlib.sha256(json.dumps(details, sort_keys=True).encode()).hexdigest()
    return identity, details, input_error


def run_command(argv: list[str], cwd: Path, log_path: Path, timeout: float,
                active: dict[str, subprocess.Popen[str]] | None = None) -> int:
    """Run a fixed argv in a killable process group and retain combined output."""
    global ACTIVE_PROCESS
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open("w", encoding="utf-8") as log:
        log.write("$ " + shlex.join(argv) + "\n")
        log.flush()
        process = subprocess.Popen(argv, cwd=cwd, stdout=log, stderr=subprocess.STDOUT,
                                   text=True, start_new_session=True)
        ACTIVE_PROCESS = process
        if active is not None:
            active["process"] = process  # type: ignore[assignment]
        try:
            return process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                return 124
            grace_deadline = time.monotonic() + 5
            while time.monotonic() < grace_deadline:
                process.poll()
                try:
                    os.killpg(process.pid, 0)
                except ProcessLookupError:
                    break
                time.sleep(0.05)
            else:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            process.wait()
            return 124
        finally:
            ACTIVE_PROCESS = None


class shared_build_lock:
    """Advisory lock shared with the private benchmark's build/JAR mutations."""

    def __init__(self, lock_path: Path | None = None):
        worktree = ROOT.resolve()
        key = hashlib.sha256(str(worktree).encode()).hexdigest()
        lock_root = canonical_lock_root()
        default_path = lock_root / "josm-v022-benchmark-locks" / f"{key}.lock"
        self.lock_path = lock_path or default_path

    def __enter__(self) -> "shared_build_lock":
        lock_path = self.lock_path
        lock_path.parent.mkdir(parents=True, exist_ok=True)
        flags = os.O_CREAT | os.O_RDWR | getattr(os, "O_NOFOLLOW", 0)
        self.fd = os.open(lock_path, flags, 0o600)
        deadline = time.monotonic() + TIMEOUT_SECONDS
        while True:
            if INTERRUPTED:
                os.close(self.fd)
                raise ValidationError("interrupted while waiting for the shared benchmark build lock")
            try:
                fcntl.flock(self.fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                return self
            except BlockingIOError:
                if time.monotonic() >= deadline:
                    os.close(self.fd)
                    raise ValidationError("timed out waiting for the shared benchmark build lock")
                time.sleep(0.1)
            except BaseException:
                os.close(self.fd)
                raise

    def __exit__(self, *_exc: Any) -> None:
        try:
            fcntl.flock(self.fd, fcntl.LOCK_UN)
        finally:
            os.close(self.fd)


def stage(name: str, command: list[str] | None, output: Path,
          check: Any = None) -> dict[str, Any]:
    started = time.monotonic()
    record: dict[str, Any] = {"name": name, "state": "running", "started_at": utc_now()}
    try:
        if command is not None:
            code = run_command(command, ROOT, output / "stages" / f"{name}.log", TIMEOUT_SECONDS)
        else:
            (output / "stages").mkdir(parents=True, exist_ok=True)
            (output / "stages" / f"{name}.log").write_text("$ internal validation\n", encoding="utf-8")
            check()
            code = 0
        record["exit_code"] = code
        record["state"] = "passed" if code == 0 else ("timeout" if code == 124 else "failed")
    except Exception as exc:  # Persist a bounded, useful stage failure.
        record["exit_code"] = 1
        record["state"] = "failed"
        record["error"] = f"{type(exc).__name__}: {exc}"
        if isinstance(exc, ValidationError) and str(exc).startswith("unavailable:"):
            record["state"] = "unavailable"
        (output / "stages").mkdir(parents=True, exist_ok=True)
        (output / "stages" / f"{name}.log").write_text(record["error"] + "\n", encoding="utf-8")
    record["duration_seconds"] = round(time.monotonic() - started, 3)
    record["finished_at"] = utc_now()
    return record


def expected_plugin_version() -> str:
    for line in (ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith("version="):
            return line.partition("=")[2].strip()
    raise ValidationError("gradle.properties has no version")


def check_artifact() -> None:
    artifact = ROOT / "build/libs/wayheatmaptracer.jar"
    if not artifact.is_file() or artifact.is_symlink():
        raise ValidationError("expected plugin jar is missing or not a regular file")
    try:
        with zipfile.ZipFile(artifact) as jar:
            bad_member = jar.testzip()
            manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8", errors="strict")
    except (OSError, zipfile.BadZipFile, KeyError, UnicodeDecodeError) as exc:
        raise ValidationError(f"plugin jar integrity failed: {type(exc).__name__}") from exc
    if bad_member:
        raise ValidationError("plugin jar contains a corrupt member")
    fields: dict[str, str] = {}
    for line in manifest.replace("\r\n", "\n").splitlines():
        key, separator, value = line.partition(":")
        if separator:
            fields[key.strip().lower()] = value.strip()
    if fields.get("plugin-version") != expected_plugin_version():
        raise ValidationError("plugin jar Plugin-Version does not match gradle.properties")
    expected_josm = next((line.partition("=")[2].strip() for line in
                          (ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines()
                          if line.startswith("josmVersion=")), None)
    expected_class = next((line.partition("=")[2].strip() for line in
                           (ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines()
                           if line.startswith("pluginClass=")), None)
    plugin_class = fields.get("plugin-class", "")
    if plugin_class != expected_class or fields.get("plugin-mainversion") != expected_josm:
        raise ValidationError("plugin jar manifest is missing required JOSM plugin fields")
    class_path = plugin_class.replace(".", "/") + ".class"
    try:
        with zipfile.ZipFile(artifact) as jar:
            class_bytes = jar.read(class_path)
    except (KeyError, OSError, zipfile.BadZipFile) as exc:
        raise ValidationError("plugin jar is missing its declared plugin class") from exc
    if len(class_bytes) < 10 or class_bytes[:4] != b"\xca\xfe\xba\xbe" or int.from_bytes(class_bytes[6:8], "big") != 61:
        raise ValidationError("plugin class is invalid or is not compiled for Java 17")
    verifier = shutil.which("javap")
    if verifier is None:
        raise ValidationError("javap is required to verify the plugin class file")
    inspected = subprocess.run([verifier, "-verbose", "-classpath", str(artifact), plugin_class],
                                text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                check=False, timeout=30)
    expected_binary_name = plugin_class.replace(".", "/")
    if (inspected.returncode != 0 or "major version: 61" not in inspected.stdout
            or expected_binary_name not in inspected.stdout):
        raise ValidationError("plugin class file failed Java 17 structural verification")


def check_junit_reports() -> None:
    report_dir = ROOT / "build/test-results/test"
    reports = sorted(report_dir.glob("TEST-*.xml")) if report_dir.is_dir() else []
    if not reports:
        raise ValidationError("Gradle produced no JUnit XML test reports")
    total_tests = total_failures = total_errors = total_skipped = 0
    for report in reports:
        if report.is_symlink() or not report.is_file() or report.stat().st_size > 16 * 1024 * 1024:
            raise ValidationError("JUnit XML report is missing, linked, or exceeds its size limit")
        try:
            document = ET.parse(report)
        except (ET.ParseError, OSError) as exc:
            raise ValidationError("JUnit XML report is malformed") from exc
        root = document.getroot()
        suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
        if not suites:
            raise ValidationError("JUnit XML contains no test suites")
        try:
            total_tests += sum(int(item.attrib.get("tests", "0")) for item in suites)
            total_failures += sum(int(item.attrib.get("failures", "0")) for item in suites)
            total_errors += sum(int(item.attrib.get("errors", "0")) for item in suites)
            total_skipped += sum(int(item.attrib.get("skipped", "0")) for item in suites)
        except ValueError as exc:
            raise ValidationError("JUnit XML has invalid suite counts") from exc
    if total_tests <= 0:
        raise ValidationError("JUnit XML reports no executed tests")
    if total_failures or total_errors or total_skipped:
        raise ValidationError("JUnit XML reports failures, errors, or skipped tests")


def java_evidence_identity() -> str:
    entries: list[tuple[str, str]] = []
    artifact = ROOT / "build/libs/wayheatmaptracer.jar"
    entries.append(("jar", sha256_file(artifact) if artifact.is_file() and not artifact.is_symlink() else "missing"))
    report_dir = ROOT / "build/test-results/test"
    if report_dir.is_dir():
        for report in sorted(report_dir.glob("TEST-*.xml")):
            if report.is_symlink() or not report.is_file():
                raise ValidationError("JUnit evidence contains a linked or non-file report")
            entries.append((report.name, sha256_file(report)))
    return hashlib.sha256(json.dumps(entries, sort_keys=True).encode()).hexdigest()


def check_environment() -> None:
    result = subprocess.run(["java", "-version"], text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, check=False, timeout=15)
    if result.returncode != 0 or 'version "17' not in result.stdout:
        raise ValidationError("Java 17 is required for this validation profile")


def check_rc_available(benchmark: Path | None, replay: Path | None) -> None:
    if benchmark is None or not benchmark.is_file():
        raise ValidationError("unavailable: RC benchmark manifest is missing")
    if replay is None or not replay.is_file():
        raise ValidationError("unavailable: strict production replay manifest is missing")
    if not (ROOT / "scripts/run-v022-benchmark.py").is_file():
        raise ValidationError("unavailable: scripts/run-v022-benchmark.py has not been implemented")


def preserve_reports(output: Path, *, copy_jar: bool = False) -> None:
    """Retain public Gradle reports after failures and a verified jar after success."""
    for relative in (Path("build/test-results/test"), Path("build/reports/tests/test")):
        source = ROOT / relative
        destination = output / "reports" / "java" / relative.name
        if destination.exists():
            shutil.rmtree(destination)
        if source.is_dir():
            shutil.copytree(source, destination, dirs_exist_ok=True)
    if copy_jar:
        artifact = ROOT / "build/libs/wayheatmaptracer.jar"
        destination = output / "artifacts" / "wayheatmaptracer.jar"
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(artifact, destination)


def discard_saved_java_outputs(output: Path) -> None:
    reports = output / "reports" / "java"
    if reports.exists():
        shutil.rmtree(reports)
    copied_jar = output / "artifacts" / "wayheatmaptracer.jar"
    copied_jar.unlink(missing_ok=True)


def canonical_lock_root() -> Path:
    """Choose one lock namespace independent of TMPDIR/TEMP/TMP overrides."""
    termux_tmp = Path("/data/data/com.termux/files/usr/tmp")
    if termux_tmp.exists():
        if not termux_tmp.is_dir() or not os.access(termux_tmp, os.W_OK):
            raise ValidationError("canonical Termux validation lock directory is unavailable")
        return termux_tmp.resolve()
    return Path("/tmp").resolve()


def worktree_lock_path() -> Path:
    directory = canonical_lock_root() / "josm-validation-locks"
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    key = hashlib.sha256(str(ROOT.resolve()).encode()).hexdigest()[:24]
    return directory / f"{key}.lock"


def write_lock_owner(lock: Path, pid: int) -> None:
    temporary = lock.with_name(lock.name + f".{os.getpid()}.tmp")
    temporary.write_text(f"{pid}\n", encoding="ascii")
    os.replace(temporary, lock)


def acquire_lock(lock: Path) -> int:
    if lock.is_symlink():
        raise ValidationError("worktree validation lock is a symlink")
    for _ in range(2):
        try:
            lock.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            fd = os.open(lock, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            os.write(fd, f"{os.getpid()}\n".encode())
            os.close(fd)
            return os.getpid()
        except FileExistsError:
            try:
                pid = int(lock.read_text(encoding="ascii").strip())
                os.kill(pid, 0)
            except ProcessLookupError:
                lock.unlink(missing_ok=True)
                continue
            except (ValueError, OSError):
                raise ValidationError("worktree validation lock exists and is unreadable")
            raise ValidationError("another validation process holds the worktree lock")
    raise ValidationError("could not acquire worktree validation lock")


def release_lock(lock: Path, owner_pid: int) -> None:
    try:
        if int(lock.read_text(encoding="ascii").strip()) == owner_pid:
            lock.unlink()
    except (OSError, ValueError):
        pass


def validate_profile_output(output: Path, profile: str, old: dict[str, Any]) -> None:
    if profile == "public" and (output / "private").exists():
        raise ValidationError("public profile cannot reuse a result directory containing private outputs")
    if "private_inputs" in old and old["private_inputs"] != (profile == "rc"):
        raise ValidationError("public and RC profiles require separate result directories")


def run_profile(profile: str, output: Path, benchmark: Path | None,
                replay: Path | None, resume: bool, *, lock_already_acquired: bool = False) -> int:
    output.mkdir(parents=True, exist_ok=True)
    lock = worktree_lock_path()
    if lock_already_acquired:
        try:
            if int(lock.read_text(encoding="ascii").strip()) != os.getpid():
                raise ValidationError("background child does not own the worktree lock")
        except (OSError, ValueError) as exc:
            raise ValidationError("background child worktree lock handoff failed") from exc
    else:
        acquire_lock(lock)
    try:
        return _run_profile_locked(profile, output, benchmark, replay, resume)
    finally:
        release_lock(lock, os.getpid())


def _run_profile_locked(profile: str, output: Path, benchmark: Path | None,
                        replay: Path | None, resume: bool) -> int:
    global INTERRUPTED
    old: dict[str, Any] = {}
    status_file = output / "status.json"
    if resume and status_file.is_file():
        try:
            old = json.loads(status_file.read_text(encoding="utf-8"))
        except (ValueError, OSError):
            old = {}
    if not isinstance(old, dict):
        old = {}
    validate_profile_output(output, profile, old)
    identity, identity_details, input_error = run_identity(profile, benchmark, replay)
    records: list[dict[str, Any]] = []
    reusable = old.get("stages", []) if old.get("identity") == identity and old.get("profile") == profile else []
    reused = 0
    if old.get("identity") != identity or old.get("profile") != profile:
        discard_saved_java_outputs(output)

    def run_or_reuse(name: str, command: list[str] | None, check: Any = None) -> dict[str, Any]:
        nonlocal reused
        previous = next((row for row in reusable if row.get("name") == name and row.get("state") == "passed"), None)
        # The artifact is mutable build output, so verify its current bytes every run.
        if name in {"artifact-integrity", "junit-report-integrity"} or (
                profile == "rc" and name in {"strict-production-replay", "production-benchmark"}):
            previous = None
        if name == "java-build" and previous is not None:
            try:
                evidence_matches = previous.get("evidence_sha256") == java_evidence_identity()
            except ValidationError:
                evidence_matches = False
            if not evidence_matches:
                previous = None
        if name == "java-build" and previous is None:
            discard_saved_java_outputs(output)
        if previous is not None and resume:
            reused += 1
            record = dict(previous)
            record["reused"] = True
            return record
        if name == "java-build":
            record = stage(name, command, output, check)
            if record["state"] == "passed":
                record["evidence_sha256"] = java_evidence_identity()
            preserve_reports(output)
        elif name == "junit-report-integrity":
            def check_bound_junit_reports() -> None:
                java_record = next((item for item in records if item.get("name") == "java-build"), None)
                if (java_record is None or java_record.get("state") != "passed"
                        or java_record.get("evidence_sha256") != java_evidence_identity()):
                    raise ValidationError("Java build report/JAR evidence changed before JUnit validation")
                check_junit_reports()
            record = stage(name, command, output, check_bound_junit_reports)
        elif name == "strict-production-replay":
            record = stage(name, command, output, check)
        else:
            record = stage(name, command, output, check)
        record["reused"] = False
        return record

    def locked_stage(name: str, command: list[str] | None, check: Any = None) -> dict[str, Any]:
        try:
            with shared_build_lock():
                return run_or_reuse(name, command, check)
        except Exception as exc:
            def record_failure() -> None:
                raise ValidationError(
                    f"lock-protected validation failed: {type(exc).__name__}"
                ) from exc
            return stage(name, None, output, record_failure)

    state: dict[str, Any] = {"schema": 1, "profile": profile, "identity": identity,
                             "identity_components": identity_details,
                             "state": "running", "started_at": utc_now(), "stages": records,
                             "reused_stages": 0, "private_inputs": profile == "rc"}
    atomic_json(status_file, state)

    def interrupt(signum: int, _frame: Any) -> None:
        global INTERRUPTED
        INTERRUPTED = True
        process = ACTIVE_PROCESS
        if process and process.poll() is None:
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            def force_kill_group(pid: int = process.pid) -> None:
                try:
                    os.killpg(pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            threading.Timer(5, force_kill_group).start()

    old_handler = signal.signal(signal.SIGTERM, interrupt)
    old_int_handler = signal.signal(signal.SIGINT, interrupt)
    try:
        offset = 0
        if profile == "rc":
            def check_rc_inputs() -> None:
                if input_error:
                    raise ValidationError("unavailable: private manifest input validation failed")
                check_rc_available(benchmark, replay)

            record = run_or_reuse("rc-input-availability", None, check_rc_inputs)
            records.append(record)
            offset = 1
            atomic_json(status_file, state)
            if record["state"] != "passed":
                state["state"] = "unavailable" if record["state"] == "unavailable" else "failed"
                state["finished_at"] = utc_now()
                summary_result = "UNAVAILABLE" if state["state"] == "unavailable" else "FAIL"
                atomic_json(output / "summary.json", {"result": summary_result, "profile": profile,
                                                      "identity": identity,
                                                      "identity_components": identity_details,
                                                      "stages": records})
                atomic_json(status_file, state)
                (output / "launch.json").unlink(missing_ok=True)
                return 1
        java_command = ["sh", "./gradlew", "--no-daemon", "test", "build", "javadoc", "compileToolsJava", "--console=plain"]
        commands: list[tuple[str, list[str] | None, Any]] = [
            ("java-environment", None, check_environment),
            ("java-build", java_command, None),
            ("junit-report-integrity", None, check_junit_reports),
            ("python-tests", ["python3", "-m", "pytest", "-q", "scripts/tests"], None),
            ("sampling-scale", ["python3", "scripts/validate-sampling-scale.py", "--pretty"], None),
            ("diff-check", ["git", "-c", f"safe.directory={ROOT}", "diff", "--check", "HEAD"], None),
        ]
        if profile == "rc":
            assert benchmark is not None and replay is not None
            replay_output = output / "private" / "replay.json"
            replay_args = ["--manifest", str(replay), "--output", str(replay_output),
                           "--engines", "A,B,HYBRID,IMAGE", "--strict", "--offline",
                           "--ablation-config", "{}"]
            commands.extend([
                ("strict-production-replay", ["sh", "./gradlew", "--no-daemon", "v022Replay",
                                                "--args=" + shlex.join(replay_args), "--console=plain"], None),
                ("production-benchmark", ["python3", "scripts/run-v022-benchmark.py", "--manifest",
                                           str(benchmark), "--output", str(output / "private" / "benchmark")], None),
            ])
        for name, command, check in commands:
            if name in {"java-build", "junit-report-integrity", "strict-production-replay"}:
                record = locked_stage(name, command, check)
            else:
                record = run_or_reuse(name, command, check)
            records.append(record)
            state["reused_stages"] = reused
            atomic_json(status_file, state)
            if INTERRUPTED:
                record["state"] = "interrupted"
                break
            if record["state"] != "passed":
                break
        if not INTERRUPTED and (not records or records[-1]["state"] == "passed"):
            def check_bound_artifact() -> None:
                java_record = next((item for item in records if item.get("name") == "java-build"), None)
                if (java_record is None or java_record.get("state") != "passed"
                        or java_record.get("evidence_sha256") != java_evidence_identity()):
                    raise ValidationError("Java build report/JAR evidence changed before artifact verification")
                check_artifact()
                preserve_reports(output, copy_jar=True)
            record = locked_stage("artifact-integrity", None, check_bound_artifact)
            records.append(record)
            if record["state"] != "passed":
                (output / "artifacts" / "wayheatmaptracer.jar").unlink(missing_ok=True)
            atomic_json(status_file, state)
        passed = (not INTERRUPTED and len(records) == len(commands) + offset + 1
                  and all(row["state"] == "passed" for row in records))
        state["state"] = "interrupted" if INTERRUPTED else ("passed" if passed else "failed")
        state["finished_at"] = utc_now()
        state["reused_stages"] = reused
        summary = {
            "result": "PASS" if passed else ("INTERRUPTED" if INTERRUPTED else "FAIL"),
            "profile": profile,
            "source_identity": identity,
            "identity_components": identity_details,
            "stages": [{"name": row["name"], "state": row["state"], "exit_code": row.get("exit_code")}
                       for row in records],
        }
        atomic_json(output / "summary.json", summary)
        atomic_json(status_file, state)
        return 0 if passed else 1
    finally:
        (output / "launch.json").unlink(missing_ok=True)
        signal.signal(signal.SIGTERM, old_handler)
        signal.signal(signal.SIGINT, old_int_handler)


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--profile", choices=("public", "rc"))
    result.add_argument("--output", type=Path, required=True)
    result.add_argument("--benchmark-manifest", type=Path)
    result.add_argument("--replay-manifest", type=Path)
    result.add_argument("--background", action="store_true")
    result.add_argument("--resume", action="store_true")
    result.add_argument("--status", action="store_true", help="print the existing run status and exit")
    result.add_argument("--_background-child", action="store_true", help=argparse.SUPPRESS)
    return result


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        output = outside_build(args.output)
        if args.status:
            launch = output / "launch.json"
            if launch.is_file():
                try:
                    launch_state = json.loads(launch.read_text(encoding="utf-8"))
                    pid = int(launch_state["pid"])
                    if pid > 0:
                        os.kill(pid, 0)
                    print(json.dumps({**launch_state, "state": "launching"}, sort_keys=True))
                    return 0
                except (OSError, ValueError, KeyError):
                    pass
            status = output / "status.json"
            if not status.is_file():
                raise ValidationError("no validation status exists at the requested output")
            print(status.read_text(encoding="utf-8"), end="")
            return 0
        if args.profile is None:
            raise ValidationError("--profile is required unless --status is used")
        if args.profile == "public" and (args.benchmark_manifest or args.replay_manifest):
            raise ValidationError("public profile does not accept private manifests")
        benchmark = regular_external_input(args.benchmark_manifest, "benchmark manifest", False)
        replay = regular_external_input(args.replay_manifest, "replay manifest", False)
        if args._background_child:
            if args.background:
                raise ValidationError("background child cannot launch another background process")
            if sys.stdin.readline().strip() != "GO":
                release_lock(worktree_lock_path(), os.getpid())
                raise ValidationError("background launch handshake failed")
            return run_profile(args.profile, output, benchmark, replay, args.resume,
                               lock_already_acquired=True)
        if args.background:
            output.mkdir(parents=True, exist_ok=True)
            lock = worktree_lock_path()
            acquire_lock(lock)
            old_state: dict[str, Any] = {}
            try:
                status_file = output / "status.json"
                if status_file.is_file():
                    try:
                        loaded = json.loads(status_file.read_text(encoding="utf-8"))
                        if isinstance(loaded, dict):
                            old_state = loaded
                    except (ValueError, OSError):
                        pass
                validate_profile_output(output, args.profile, old_state)
                launch_data: dict[str, Any] = {"schema": 1, "profile": args.profile,
                                               "state": "launching", "pid": 0,
                                               "started_at": utc_now()}
                atomic_json(output / "launch.json", launch_data)
                child_args = [sys.executable, str(Path(__file__).resolve()), "--profile", args.profile,
                              "--output", str(output), "--_background-child"]
                if benchmark:
                    child_args += ["--benchmark-manifest", str(benchmark)]
                if replay:
                    child_args += ["--replay-manifest", str(replay)]
                if args.resume:
                    child_args.append("--resume")
                log = (output / "runner.log").open("a", encoding="utf-8")
                child = subprocess.Popen(child_args, cwd=ROOT, stdin=subprocess.PIPE,
                                         stdout=log, stderr=subprocess.STDOUT, start_new_session=True,
                                         close_fds=True, text=True)
                write_lock_owner(lock, child.pid)
                launch_data["pid"] = child.pid
                atomic_json(output / "launch.json", launch_data)
                assert child.stdin is not None
                child.stdin.write("GO\n")
                child.stdin.close()
                print(f"started validation pid={child.pid}; status: {output / 'status.json'}")
                return 0
            except Exception:
                if "child" in locals():
                    child.kill()
                    child.wait()
                (output / "launch.json").unlink(missing_ok=True)
                release_lock(lock, child.pid if "child" in locals() else os.getpid())
                raise
        return run_profile(args.profile, output, benchmark, replay, args.resume)
    except (ValidationError, OSError, subprocess.SubprocessError) as exc:
        print(f"validation error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
