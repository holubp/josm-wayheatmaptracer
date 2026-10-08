"""Subprocess-level safety tests for the unattended validation runner."""

from __future__ import annotations

import json
import os
import importlib.util
import shutil
import signal
import subprocess
import sys
import time
import zipfile
import hashlib
import fcntl
from pathlib import Path
from types import SimpleNamespace
import pytest

ROOT = Path(__file__).resolve().parents[2]
RUNNER = ROOT / "scripts" / "run-validation.py"


@pytest.fixture(autouse=True)
def preserve_build_jar():
    jar = ROOT / "build" / "libs" / "wayheatmaptracer.jar"
    previous = jar.read_bytes() if jar.is_file() else None
    yield
    if previous is None:
        jar.unlink(missing_ok=True)
    else:
        jar.parent.mkdir(parents=True, exist_ok=True)
        jar.write_bytes(previous)


def fake_tools(tmp_path: Path, *, gradle_exit: int = 0, gradle_sleep: float = 0,
               plugin_version: str = "0.22.0-rc.6") -> Path:
    bindir = tmp_path / "bin"
    bindir.mkdir()
    class_root = tmp_path / "plugin-class"
    source = tmp_path / "plugin-src" / "org/openstreetmap/josm/plugins/wayheatmaptracer/WayHeatmapTracerPlugin.java"
    source.parent.mkdir(parents=True)
    source.write_text("package org.openstreetmap.josm.plugins.wayheatmaptracer; public class WayHeatmapTracerPlugin { public WayHeatmapTracerPlugin() {} }\n")
    class_root.mkdir()
    subprocess.run(["javac", "--release", "17", "-d", str(class_root), str(source)], check=True,
                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    plugin_class = class_root / "org/openstreetmap/josm/plugins/wayheatmaptracer/WayHeatmapTracerPlugin.class"
    sh = bindir / "sh"
    sh.write_text(
        f"#!{sys.executable}\n"
        "import os, pathlib, sys, time\n"
        "if './gradlew' in sys.argv:\n"
        "  pidfile = os.environ.get('FAKE_PID_FILE')\n"
        "  if pidfile: pathlib.Path(pidfile).write_text(str(os.getpid()))\n"
        "  time.sleep(float(os.environ.get('FAKE_GRADLE_SLEEP', '0')))\n"
        "  if int(os.environ.get('FAKE_GRADLE_EXIT', '0')) == 0 and os.environ.get('FAKE_SKIP_ARTIFACT') != '1':\n"
        "    import zipfile\n"
        "    jar = pathlib.Path('build/libs/wayheatmaptracer.jar')\n"
        "    jar.parent.mkdir(parents=True, exist_ok=True)\n"
        "    with zipfile.ZipFile(jar, 'w') as z:\n"
        "      z.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\\nPlugin-Version: ' + os.environ.get('FAKE_PLUGIN_VERSION', '" + plugin_version + "') + '\\nPlugin-Class: org.openstreetmap.josm.plugins.wayheatmaptracer.WayHeatmapTracerPlugin\\nPlugin-Mainversion: 19555\\n')\n"
        "      z.write(os.environ['FAKE_PLUGIN_CLASS'], 'org/openstreetmap/josm/plugins/wayheatmaptracer/WayHeatmapTracerPlugin.class')\n"
        "    reports = pathlib.Path('build/test-results/test')\n"
        "    reports.mkdir(parents=True, exist_ok=True)\n"
        "    (reports / 'TEST-fake.xml').write_text('<testsuite tests=\"2\" failures=\"0\" errors=\"0\" skipped=\"0\"/>')\n"
        "  raise SystemExit(int(os.environ.get('FAKE_GRADLE_EXIT', '0')))\n"
        "raise SystemExit(0)\n",
        encoding="utf-8",
    )
    sh.chmod(0o755)
    python = bindir / "python3"
    python.write_text(
        "#!/bin/sh\n"
        "if [ \"$1\" = \"-m\" ] && [ \"$2\" = \"pytest\" ] && [ -n \"$FAKE_PYTEST_FAIL_FILE\" ] && [ ! -e \"$FAKE_PYTEST_FAIL_FILE\" ]; then\n"
        "  touch \"$FAKE_PYTEST_FAIL_FILE\"\n  exit 8\nfi\nexit 0\n",
        encoding="utf-8",
    )
    python.chmod(0o755)
    java = bindir / "java"
    java.write_text("#!/bin/sh\necho 'openjdk version \"17.0.1\"'\n", encoding="utf-8")
    java.chmod(0o755)
    env = os.environ.copy()
    env.update({"PATH": f"{bindir}:{env['PATH']}", "FAKE_GRADLE_EXIT": str(gradle_exit),
                "FAKE_GRADLE_SLEEP": str(gradle_sleep), "FAKE_PLUGIN_VERSION": plugin_version,
                "FAKE_PLUGIN_CLASS": str(plugin_class)})
    return bindir


def invoke(tmp_path: Path, *args: str, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(RUNNER), *args], cwd=ROOT, env=env,
        text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20,
    )


def env_for(path: Path, *, gradle_exit: int = 0, gradle_sleep: float = 0,
            plugin_version: str = "0.22.0-rc.6") -> dict[str, str]:
    env = os.environ.copy()
    env["PATH"] = f"{path}:{env['PATH']}"
    env.update({"FAKE_GRADLE_EXIT": str(gradle_exit), "FAKE_GRADLE_SLEEP": str(gradle_sleep),
                "FAKE_PLUGIN_VERSION": plugin_version, "FAKE_SKIP_ARTIFACT": "0",
                "FAKE_PYTEST_FAIL_FILE": "",
                "FAKE_PLUGIN_CLASS": str(path.parent / "plugin-class/org/openstreetmap/josm/plugins/wayheatmaptracer/WayHeatmapTracerPlugin.class")})
    return env


def test_failed_java_stage_is_nonzero_and_persisted(tmp_path: Path) -> None:
    tools = fake_tools(tmp_path, gradle_exit=7)
    output = tmp_path / "reports"
    result = invoke(tmp_path, "--profile", "public", "--output", str(output), env=env_for(tools, gradle_exit=7))
    assert result.returncode != 0
    state = json.loads((output / "status.json").read_text())
    assert state["state"] == "failed"
    assert state["stages"][1]["exit_code"] == 7
    assert (output / "stages" / "java-build.log").exists()
    assert json.loads((output / "summary.json").read_text())["result"] == "FAIL"


def test_resume_reuses_only_successful_stages_and_rechecks_changed_inputs(tmp_path: Path) -> None:
    tools = fake_tools(tmp_path)
    output = tmp_path / "reports"
    env = env_for(tools)
    args = ("--profile", "public", "--output", str(output))
    assert invoke(tmp_path, *args, env=env).returncode == 0
    status_before = json.loads((output / "status.json").read_text())
    assert status_before["state"] == "passed"
    assert len(status_before["identity_components"]["source_sha256"]) == 64
    assert len(status_before["identity_components"]["environment_sha256"]) == 64
    assert (output / "artifacts" / "wayheatmaptracer.jar").is_file()
    assert invoke(tmp_path, *args, "--resume", env=env).returncode == 0
    status_after = json.loads((output / "status.json").read_text())
    assert status_after["reused_stages"] == len(status_before["stages"]) - 2
    changed = dict(env, FAKE_GRADLE_EXIT="9")
    assert invoke(tmp_path, *args, "--resume", env=changed).returncode != 0
    failed = json.loads((output / "status.json").read_text())
    assert failed["state"] == "failed"
    assert len(failed["stages"]) == 2
    assert failed["stages"][1]["state"] == "failed"


def test_background_status_reports_plain_process_state(tmp_path: Path) -> None:
    tools = fake_tools(tmp_path, gradle_sleep=0.4)
    output = tmp_path / "reports"
    env = env_for(tools, gradle_sleep=0.4)
    start = invoke(tmp_path, "--profile", "public", "--output", str(output), "--background", env=env)
    assert start.returncode == 0
    status = invoke(tmp_path, "--output", str(output), "--status", env=env)
    assert status.returncode == 0
    state = json.loads(status.stdout)
    assert state["state"] in {"launching", "running", "passed"}
    deadline = time.monotonic() + 8
    while state["state"] not in {"passed", "failed", "interrupted"} and time.monotonic() < deadline:
        time.sleep(0.05)
        status = invoke(tmp_path, "--output", str(output), "--status", env=env)
        state = json.loads(status.stdout)
    assert state["state"] == "passed"


def test_worktree_lock_serializes_different_output_directories(tmp_path: Path) -> None:
    tools = fake_tools(tmp_path, gradle_sleep=1.5)
    env = env_for(tools, gradle_sleep=1.5)
    first_output = tmp_path / "first"
    second_output = tmp_path / "second"
    first = invoke(tmp_path, "--profile", "public", "--output", str(first_output), "--background", env=env)
    assert first.returncode == 0
    deadline = time.monotonic() + 5
    while not (first_output / "status.json").exists() and time.monotonic() < deadline:
        time.sleep(0.02)
    second = invoke(tmp_path, "--profile", "public", "--output", str(second_output), env=env)
    assert second.returncode != 0
    assert "lock" in second.stderr.lower()
    status_file = first_output / "status.json"
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        if status_file.is_file() and json.loads(status_file.read_text()).get("state") == "passed":
            break
        time.sleep(0.03)
    assert status_file.is_file() and json.loads(status_file.read_text())["state"] == "passed"
    lock_spec = importlib.util.spec_from_file_location("validation_lock_test", RUNNER)
    assert lock_spec and lock_spec.loader
    lock_module = importlib.util.module_from_spec(lock_spec)
    sys.modules[lock_spec.name] = lock_module
    lock_spec.loader.exec_module(lock_module)
    deadline = time.monotonic() + 3
    while lock_module.worktree_lock_path().exists() and time.monotonic() < deadline:
        time.sleep(0.02)


def test_gradle_mutation_waits_for_shared_benchmark_lock(tmp_path: Path) -> None:
    tools = fake_tools(tmp_path)
    env = env_for(tools, gradle_sleep=0.1)
    pidfile = tmp_path / "gradle.started"
    env["FAKE_PID_FILE"] = str(pidfile)
    key = hashlib.sha256(str(ROOT.resolve()).encode()).hexdigest()
    termux_tmp = Path("/data/data/com.termux/files/usr/tmp")
    if termux_tmp.exists():
        assert termux_tmp.is_dir() and os.access(termux_tmp, os.W_OK)
        lock_root = termux_tmp.resolve()
    else:
        lock_root = Path("/tmp").resolve()
    lock_path = lock_root / "josm-v022-benchmark-locks" / f"{key}.lock"
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(lock_path, os.O_CREAT | os.O_RDWR, 0o600)
    fcntl.flock(fd, fcntl.LOCK_EX)
    output = tmp_path / "reports"
    process = subprocess.Popen(
        [sys.executable, str(RUNNER), "--profile", "public", "--output", str(output)],
        cwd=ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
    )
    try:
        time.sleep(0.7)
        assert not pidfile.exists(), "Gradle started before the shared build lock was released"
    finally:
        fcntl.flock(fd, fcntl.LOCK_UN)
        os.close(fd)
    _stdout, stderr = process.communicate(timeout=20)
    assert process.returncode == 0, stderr
    assert pidfile.exists()


def test_shared_benchmark_lock_path_ignores_temporary_directory_overrides(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch,
) -> None:
    spec = importlib.util.spec_from_file_location("validation_lock_path_test", RUNNER)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    expected = module.shared_build_lock().lock_path
    expected_worktree_lock = module.worktree_lock_path()
    for name, value in (("TMPDIR", tmp_path / "tmpdir"),
                        ("TMP", tmp_path / "tmp"), ("TEMP", tmp_path / "temp")):
        monkeypatch.setenv(name, str(value))
    assert module.shared_build_lock().lock_path == expected
    assert module.worktree_lock_path() == expected_worktree_lock


def test_background_resume_preserves_and_reuses_prior_success(tmp_path: Path) -> None:
    tools = fake_tools(tmp_path, gradle_sleep=0.4)
    env = env_for(tools, gradle_sleep=0.4)
    output = tmp_path / "reports"
    args = ("--profile", "public", "--output", str(output))
    assert invoke(tmp_path, *args, env=env).returncode == 0
    previous = json.loads((output / "status.json").read_text())
    launch = invoke(tmp_path, *args, "--resume", "--background", env=env)
    assert launch.returncode == 0
    state = json.loads(invoke(tmp_path, "--output", str(output), "--status", env=env).stdout)
    assert state["state"] in {"launching", "running", "passed"}
    deadline = time.monotonic() + 8
    while state["state"] not in {"passed", "failed", "interrupted"} and time.monotonic() < deadline:
        time.sleep(0.05)
        state = json.loads(invoke(tmp_path, "--output", str(output), "--status", env=env).stdout)
    assert state["state"] == "passed"
    assert state["reused_stages"] >= len(previous["stages"]) - 2


def _committed_worktree(path: Path) -> tuple[str, str]:
    path.mkdir()
    subprocess.run(["git", "init", "-q", str(path)], check=True)
    source = path / "src/Plugin.java"
    source.parent.mkdir()
    source.write_text("class Plugin {}\n")
    subprocess.run(["git", "-C", str(path), "add", "src/Plugin.java"], check=True)
    subprocess.run(["git", "-C", str(path), "-c", "user.name=test", "-c", "user.email=test@example.invalid",
                    "commit", "-qm", "fixture"], check=True)
    revision = subprocess.run(["git", "-C", str(path), "rev-parse", "HEAD"], check=True,
                              text=True, stdout=subprocess.PIPE).stdout.strip()
    return revision, hashlib.sha256(source.read_bytes()).hexdigest()


def _required_file(path: Path) -> dict[str, str]:
    return {"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def _benchmark_fixture(tmp_path: Path, java: Path | None = None) -> tuple[Path, Path, Path]:
    spec = importlib.util.spec_from_file_location("validation_manifest_test", RUNNER)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    input_root = tmp_path / "payloads"
    input_root.mkdir()
    paths = {}
    for key, suffix in (("jar", ".jar"), ("archive1", ".zip"), ("osm1", ".osm"),
                        ("archive2", ".zip"), ("osm2", ".osm")):
        item = input_root / (key + suffix)
        item.write_bytes(("payload:" + key).encode())
        paths[key] = item
    tile_descriptors = {}
    for case_index in (1, 2):
        tile_dir = input_root / f"tiles{case_index}"
        tile_dir.mkdir()
        pngs = []
        for png_index in range(10):
            png = tile_dir / f"tile-{png_index}.png"
            png.write_bytes(b"\x89PNG\r\n\x1a\n" + f"fixture-{case_index}-{png_index}".encode())
            pngs.append(png)
        tsv = tile_dir / "tiles.tsv"
        tsv.write_text("relativePath\n" + "".join(png.name + "\n" for png in pngs))
        tile_descriptors[case_index] = _required_file(tsv)
        if case_index == 1:
            paths["tile1_png"] = pngs[0]
    baseline, candidate = tmp_path / "baseline", tmp_path / "candidate"
    baseline_revision, baseline_source_hash = _committed_worktree(baseline)
    candidate_revision, candidate_source_hash = _committed_worktree(candidate)
    if java is None:
        java = tmp_path / "java17"
        java.write_text("#!/bin/sh\necho 'openjdk version \"17.0.1\"'\n")
        java.chmod(0o755)
    manifest = {
        "schemaVersion": 1, "warmups": 1, "repetitions": 3, "runTimeoutSeconds": 60,
        "environment": {"java": str(java), "javaMajor": 17, "josmVersion": "19555",
                         "josmJar": _required_file(paths["jar"])},
        "baseline": {"worktree": str(baseline), "revision": baseline_revision,
                     "sourceHashes": {"src/Plugin.java": baseline_source_hash}},
        "candidate": {"worktree": str(candidate), "revision": candidate_revision,
                      "sourceHashes": {"src/Plugin.java": candidate_source_hash}},
        "cases": [
            {"id": case_id, "archive": _required_file(paths[f"archive{index}"]),
             "osm": _required_file(paths[f"osm{index}"]), "tiles": tile_descriptors[index]}
            for case_id, index in (("N1", 1), ("N2", 2))
        ],
    }
    manifest_path = input_root / "benchmark.json"
    manifest_path.write_text(json.dumps(manifest))
    return manifest_path, paths["osm1"], java, paths["tile1_png"]


def test_manifest_identity_binds_referenced_input_bytes(tmp_path: Path) -> None:
    spec = importlib.util.spec_from_file_location("validation_manifest_test", RUNNER)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    manifest, source, java, tile_png = _benchmark_fixture(tmp_path)
    real_run = subprocess.run
    module.shutil = SimpleNamespace(which=lambda _name: str(java))
    def fake_run(args, *pargs, **kwargs):
        if args[0] == str(java) and args[1:] == ["-version"]:
            return subprocess.CompletedProcess(args, 0, 'openjdk version "17.0.1"\n', "")
        return real_run(args, *pargs, **kwargs)
    module.subprocess = SimpleNamespace(run=fake_run, PIPE=subprocess.PIPE, STDOUT=subprocess.STDOUT)
    first = module.manifest_input_identity(manifest)
    tile_png.write_bytes(b"\x89PNG\r\n\x1a\nchanged tile bytes")
    second = module.manifest_input_identity(manifest)
    assert first != second
    assert len(first) == 64


def test_java_build_evidence_change_invalidates_resume(tmp_path: Path) -> None:
    tools = fake_tools(tmp_path)
    env = env_for(tools)
    output = tmp_path / "reports"
    args = ("--profile", "public", "--output", str(output))
    assert invoke(tmp_path, *args, env=env).returncode == 0
    jar = ROOT / "build/libs/wayheatmaptracer.jar"
    with zipfile.ZipFile(jar, "w") as archive:
        archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nPlugin-Version: corrupt\n")
    assert invoke(tmp_path, *args, "--resume", env=env).returncode == 0
    status = json.loads((output / "status.json").read_text())
    java_build = next(stage for stage in status["stages"] if stage["name"] == "java-build")
    assert java_build["reused"] is False


@pytest.mark.parametrize("xml", [
    "<testsuite tests='0' failures='0' errors='0' skipped='0'/>",
    "<testsuite tests='2' failures='1' errors='0' skipped='0'/>",
    "<testsuite tests='2' failures='0' errors='0' skipped='1'/>",
])
def test_junit_gate_rejects_empty_failed_or_skipped_reports(tmp_path: Path, xml: str) -> None:
    spec = importlib.util.spec_from_file_location("validation_junit_test", RUNNER)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    root = tmp_path / "repo"
    reports = root / "build/test-results/test"
    reports.mkdir(parents=True)
    (reports / "TEST-case.xml").write_text(xml)
    module.ROOT = root
    with pytest.raises(module.ValidationError):
        module.check_junit_reports()


def test_output_under_build_is_rejected_without_touching_it(tmp_path: Path) -> None:
    output = ROOT / "build" / "unattended-runner-test-output"
    result = invoke(tmp_path, "--profile", "public", "--output", str(output))
    assert result.returncode != 0
    assert not output.exists()


def test_rc_missing_benchmark_implementation_is_explicitly_unavailable(tmp_path: Path) -> None:
    tools = fake_tools(tmp_path)
    benchmark, _, _, _ = _benchmark_fixture(tmp_path, tools / "java")
    replay = tmp_path / "replay.json"
    corpus = tmp_path / "case.wthb"
    corpus.write_bytes(b"case payload")
    replay.write_text(json.dumps({"schema": "wayheatmaptracer-v022-corpus-1", "cases": [
        {"caseId": "fixture", "sourcePath": str(corpus),
         "outerSha256": hashlib.sha256(corpus.read_bytes()).hexdigest(), "bundleSha256": "a" * 64}
    ]}))
    output = tmp_path / "private-reports"
    result = invoke(tmp_path, "--profile", "rc", "--output", str(output),
                    "--benchmark-manifest", str(benchmark), "--replay-manifest", str(replay),
                    env=env_for(tools))
    assert result.returncode != 0
    state = json.loads((output / "status.json").read_text())
    assert state["state"] == "unavailable"
    assert state["stages"][0]["state"] == "unavailable"
    assert "run-v022-benchmark.py" in (output / "stages" / "rc-input-availability.log").read_text()


def test_resume_never_reuses_a_failed_stage(tmp_path: Path) -> None:
    tools = fake_tools(tmp_path)
    env = env_for(tools)
    env["FAKE_PYTEST_FAIL_FILE"] = str(tmp_path / "pytest-failed-once")
    output = tmp_path / "reports"
    args = ("--profile", "public", "--output", str(output))
    assert invoke(tmp_path, *args, env=env).returncode != 0
    failed = json.loads((output / "status.json").read_text())
    assert failed["stages"][-1]["name"] == "python-tests"
    assert failed["stages"][-1]["state"] == "failed"
    assert invoke(tmp_path, *args, "--resume", env=env).returncode == 0
    resumed = json.loads((output / "status.json").read_text())
    assert resumed["state"] == "passed"
    assert resumed["stages"][0]["reused"] is True
    assert resumed["stages"][1]["reused"] is True
    assert resumed["stages"][2]["name"] == "junit-report-integrity"
    assert resumed["stages"][2]["reused"] is False
    assert resumed["stages"][3]["name"] == "python-tests"
    assert resumed["stages"][3]["reused"] is False


def test_resume_discards_malformed_status_and_non_success_stage_records(tmp_path: Path) -> None:
    tools = fake_tools(tmp_path)
    env = env_for(tools)
    output = tmp_path / "reports"
    args = ("--profile", "public", "--output", str(output))
    assert invoke(tmp_path, *args, env=env).returncode == 0
    status_path = output / "status.json"
    state = json.loads(status_path.read_text())
    state["stages"][0]["state"] = "running"
    status_path.write_text(json.dumps(state))
    assert invoke(tmp_path, *args, "--resume", env=env).returncode == 0
    resumed = json.loads(status_path.read_text())
    assert resumed["stages"][0]["reused"] is False
    status_path.write_text("{partial")
    assert invoke(tmp_path, *args, "--resume", env=env).returncode == 0
    resumed = json.loads(status_path.read_text())
    assert all(stage["reused"] is False for stage in resumed["stages"])


def test_artifact_validation_rejects_missing_or_wrong_plugin_manifest(tmp_path: Path) -> None:
    tools = fake_tools(tmp_path, plugin_version="wrong")
    jar = ROOT / "build" / "libs" / "wayheatmaptracer.jar"
    previous = jar.read_bytes() if jar.exists() else None
    try:
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nPlugin-Version: wrong\n")
        env = env_for(tools, plugin_version="wrong")
        env["FAKE_SKIP_ARTIFACT"] = "1"
        result = invoke(tmp_path, "--profile", "public", "--output", str(tmp_path / "reports"), env=env)
        assert result.returncode != 0
        status = json.loads((tmp_path / "reports" / "status.json").read_text())
        assert status["state"] == "failed"
        assert status["stages"][-1]["name"] == "artifact-integrity"
    finally:
        if previous is None:
            jar.unlink(missing_ok=True)
        else:
            jar.write_bytes(previous)


def test_stage_timeout_kills_the_whole_process_group(tmp_path: Path) -> None:
    spec = importlib.util.spec_from_file_location("validation_runner", RUNNER)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    pidfile = tmp_path / "child.pid"
    child = tmp_path / "sleep.py"
    child.write_text("import os,time,pathlib,signal\npid=os.fork()\nif pid==0:\n signal.signal(signal.SIGTERM, signal.SIG_IGN)\n pathlib.Path(os.environ['PIDFILE']).write_text(str(os.getpid()))\n time.sleep(30)\nelse:\n time.sleep(30)\n")
    previous = os.environ.copy()
    os.environ.update({"PIDFILE": str(pidfile)})
    try:
        result = module.run_command([sys.executable, str(child)], ROOT, tmp_path / "timeout.log", 1)
    finally:
        os.environ.clear()
        os.environ.update(previous)
    assert result == 124
    child_pid = int(pidfile.read_text())
    deadline = time.monotonic() + 3
    while time.monotonic() < deadline:
        try:
            stat = Path(f"/proc/{child_pid}/stat")
            if not stat.exists() or stat.read_text().split()[2] == "Z":
                break
        except (FileNotFoundError, ProcessLookupError):
            break
        time.sleep(0.02)
    else:
        raise AssertionError("TERM-ignoring process-group descendant remained alive")


def test_public_profile_rejects_existing_private_output_without_deleting_it(tmp_path: Path) -> None:
    tools = fake_tools(tmp_path)
    output = tmp_path / "shared-output"
    private_marker = output / "private" / "evidence.bin"
    private_marker.parent.mkdir(parents=True)
    private_marker.write_bytes(b"private evidence")
    result = invoke(tmp_path, "--profile", "public", "--output", str(output), env=env_for(tools))
    assert result.returncode == 2
    assert "private" in result.stderr.lower()
    assert private_marker.read_bytes() == b"private evidence"


def test_sigterm_marks_run_interrupted_and_stops_stage_process(tmp_path: Path) -> None:
    tools = fake_tools(tmp_path, gradle_sleep=30)
    output = tmp_path / "reports"
    pidfile = tmp_path / "gradle.pid"
    env = env_for(tools, gradle_sleep=30)
    env["FAKE_PID_FILE"] = str(pidfile)
    process = subprocess.Popen([sys.executable, str(RUNNER), "--profile", "public", "--output", str(output)],
                               cwd=ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    deadline = time.monotonic() + 5
    while not pidfile.exists() and time.monotonic() < deadline:
        time.sleep(0.02)
    assert pidfile.exists()
    process.send_signal(signal.SIGTERM)
    process.communicate(timeout=10)
    state = json.loads((output / "status.json").read_text())
    assert state["state"] == "interrupted"
    child_pid = int(pidfile.read_text())
    try:
        os.kill(child_pid, 0)
    except ProcessLookupError:
        pass
    else:
        raise AssertionError("interrupted stage process remained alive")
