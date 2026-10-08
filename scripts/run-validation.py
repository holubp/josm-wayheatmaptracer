#!/usr/bin/env python3
"""Run fixed public or private JOSM validation stages without an agent service."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import signal
import shlex
import shutil
import subprocess
import sys
import threading
import time
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
TIMEOUT_SECONDS = 4 * 60 * 60
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
    return result


def run_identity(profile: str, benchmark: Path | None, replay: Path | None) -> tuple[str, dict[str, str | None]]:
    def input_hash(path: Path | None) -> str | None:
        return sha256_file(path) if path is not None and path.is_file() else ("missing" if path else None)

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
    return hashlib.sha256(json.dumps(details, sort_keys=True).encode()).hexdigest(), details


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
                process.wait(timeout=5)
            except (ProcessLookupError, subprocess.TimeoutExpired):
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait()
            return 124
        finally:
            ACTIVE_PROCESS = None


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
    if fields.get("plugin-class") != expected_class or fields.get("plugin-mainversion") != expected_josm:
        raise ValidationError("plugin jar manifest is missing required JOSM plugin fields")


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
        if source.is_dir():
            destination = output / "reports" / "java" / relative.name
            shutil.copytree(source, destination, dirs_exist_ok=True)
    if copy_jar:
        artifact = ROOT / "build/libs/wayheatmaptracer.jar"
        destination = output / "artifacts" / "wayheatmaptracer.jar"
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(artifact, destination)


def acquire_lock(output: Path) -> int:
    lock = output / ".run.lock"
    for _ in range(2):
        try:
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
                raise ValidationError("run lock exists and is unreadable; inspect it before removal")
            raise ValidationError("another validation process holds the output lock")
    raise ValidationError("could not acquire output lock")


def run_profile(profile: str, output: Path, benchmark: Path | None,
                replay: Path | None, resume: bool) -> int:
    global INTERRUPTED
    output.mkdir(parents=True, exist_ok=True)
    identity, identity_details = run_identity(profile, benchmark, replay)
    old: dict[str, Any] = {}
    status_file = output / "status.json"
    if resume and status_file.is_file():
        try:
            old = json.loads(status_file.read_text(encoding="utf-8"))
        except (ValueError, OSError):
            old = {}
    lock_pid = acquire_lock(output)
    records: list[dict[str, Any]] = []
    reusable = old.get("stages", []) if old.get("identity") == identity and old.get("profile") == profile else []
    reused = 0

    def run_or_reuse(name: str, command: list[str] | None, check: Any = None) -> dict[str, Any]:
        nonlocal reused
        previous = next((row for row in reusable if row.get("name") == name and row.get("state") == "passed"), None)
        # The artifact is mutable build output, so verify its current bytes every run.
        if name == "artifact-integrity":
            previous = None
        if previous is not None and resume:
            reused += 1
            record = dict(previous)
            record["reused"] = True
            return record
        record = stage(name, command, output, check)
        record["reused"] = False
        return record

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
            record = run_or_reuse("rc-input-availability", None,
                                  lambda: check_rc_available(benchmark, replay))
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
                return 1
        java_command = ["sh", "./gradlew", "--no-daemon", "test", "build", "javadoc", "compileToolsJava", "--console=plain"]
        commands: list[tuple[str, list[str] | None, Any]] = [
            ("java-environment", None, check_environment),
            ("java-build", java_command, None),
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
            record = run_or_reuse(name, command, check)
            records.append(record)
            if name == "java-build":
                preserve_reports(output)
            if name == "artifact-integrity" and record["state"] == "passed":
                preserve_reports(output, copy_jar=True)
            state["reused_stages"] = reused
            atomic_json(status_file, state)
            if INTERRUPTED:
                record["state"] = "interrupted"
                break
            if record["state"] != "passed":
                break
        if not INTERRUPTED and (not records or records[-1]["state"] == "passed"):
            record = run_or_reuse("artifact-integrity", None, check_artifact)
            records.append(record)
            if record["state"] == "passed":
                preserve_reports(output, copy_jar=True)
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
        signal.signal(signal.SIGTERM, old_handler)
        signal.signal(signal.SIGINT, old_int_handler)
        (output / ".run.lock").unlink(missing_ok=True)


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--profile", choices=("public", "rc"))
    result.add_argument("--output", type=Path, required=True)
    result.add_argument("--benchmark-manifest", type=Path)
    result.add_argument("--replay-manifest", type=Path)
    result.add_argument("--background", action="store_true")
    result.add_argument("--resume", action="store_true")
    result.add_argument("--status", action="store_true", help="print the existing run status and exit")
    return result


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        output = outside_build(args.output)
        if args.status:
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
        if args.background:
            output.mkdir(parents=True, exist_ok=True)
            child_args = [sys.executable, str(Path(__file__).resolve()), "--profile", args.profile,
                          "--output", str(output)]
            if benchmark:
                child_args += ["--benchmark-manifest", str(benchmark)]
            if replay:
                child_args += ["--replay-manifest", str(replay)]
            if args.resume:
                child_args.append("--resume")
            log = (output / "runner.log").open("a", encoding="utf-8")
            atomic_json(output / "status.json", {"schema": 1, "profile": args.profile,
                                                  "state": "queued", "started_at": utc_now(), "stages": []})
            child = subprocess.Popen(child_args, cwd=ROOT, stdin=subprocess.DEVNULL,
                                     stdout=log, stderr=subprocess.STDOUT, start_new_session=True,
                                     close_fds=True)
            current = json.loads((output / "status.json").read_text(encoding="utf-8"))
            if current.get("state") == "queued":
                current["pid"] = child.pid
                atomic_json(output / "status.json", current)
            print(f"started validation pid={child.pid}; status: {output / 'status.json'}")
            return 0
        return run_profile(args.profile, output, benchmark, replay, args.resume)
    except (ValidationError, OSError, subprocess.SubprocessError) as exc:
        print(f"validation error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
