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

SOURCE_ROOT = Path(__file__).resolve().parents[2]
SOURCE_RUNNER = SOURCE_ROOT / "scripts" / "run-validation.py"
ROOT = SOURCE_ROOT
RUNNER = SOURCE_RUNNER


@pytest.fixture(autouse=True)
def isolate_runner_checkout(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """Run every fake subprocess and runner import in a disposable mini-checkout."""
    root = tmp_path / "runner-checkout"
    (root / "scripts" / "tests").mkdir(parents=True)
    shutil.copy2(SOURCE_RUNNER, root / "scripts" / "run-validation.py")
    shutil.copy2(Path(__file__).resolve(), root / "scripts" / "tests" / "test_unattended_validation.py")
    shutil.copy2(SOURCE_ROOT / "gradle.properties", root / "gradle.properties")
    shutil.copy2(SOURCE_ROOT / "scripts" / "run-v022-benchmark.py",
                 root / "scripts" / "run-v022-benchmark.py")
    shutil.copy2(SOURCE_ROOT / ".gitignore", root / ".gitignore")
    subprocess.run(["git", "init", "-q", str(root)], check=True)
    subprocess.run(["git", "-C", str(root), "add", "."], check=True)
    subprocess.run(["git", "-C", str(root), "-c", "user.name=fixture", "-c",
                    "user.email=fixture@example.invalid", "commit", "-qm", "isolated runner fixture"],
                   check=True)
    (root / "build" / "libs").mkdir(parents=True)
    module = sys.modules[__name__]
    monkeypatch.setattr(module, "ROOT", root)
    monkeypatch.setattr(module, "RUNNER", root / "scripts" / "run-validation.py")


def _fixture_plugin_version() -> str:
    for line in (ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith("version="):
            return line.partition("=")[2].strip()
    raise AssertionError("disposable gradle.properties has no version")


def fake_tools(tmp_path: Path, *, gradle_exit: int = 0, gradle_sleep: float = 0,
               plugin_version: str | None = None) -> Path:
    if plugin_version is None:
        plugin_version = _fixture_plugin_version()
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
        "  reports = pathlib.Path('build/test-results/test')\n"
        "  reports.mkdir(parents=True, exist_ok=True)\n"
        "  (reports / 'TEST-fake.xml').write_text('<testsuite tests=\"2\" failures=\"0\" errors=\"0\" skipped=\"0\"><testcase name=\"one\"/><testcase name=\"two\"/></testsuite>')\n"
        "  (reports / 'output-events.bin').write_bytes(b'fake Gradle event stream')\n"
        "  (reports / 'results-generic.bin').write_bytes(b'fake Gradle binary result payload')\n"
        "  custom_report = os.environ.get('FAKE_JUNIT_REPORT')\n"
        "  if custom_report: (reports / 'TEST-fake.xml').write_text(pathlib.Path(custom_report).read_text())\n"
        "  html = pathlib.Path('build/reports/tests/test')\n"
        "  html.mkdir(parents=True, exist_ok=True)\n"
        "  (html / 'index.html').write_text('<html>fake report</html>')\n"
        "  mutate = os.environ.get('FAKE_MUTATE_FIXTURE')\n"
        "  if mutate: pathlib.Path(mutate).write_bytes(b'mutated during Gradle')\n"
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
            plugin_version: str | None = None) -> dict[str, str]:
    if plugin_version is None:
        plugin_version = _fixture_plugin_version()
    env = os.environ.copy()
    env["PATH"] = f"{path}:{env['PATH']}"
    env.update({"FAKE_GRADLE_EXIT": str(gradle_exit), "FAKE_GRADLE_SLEEP": str(gradle_sleep),
                "FAKE_PLUGIN_VERSION": plugin_version, "FAKE_SKIP_ARTIFACT": "0",
                "FAKE_PYTEST_FAIL_FILE": "",
                "FAKE_PLUGIN_CLASS": str(path.parent / "plugin-class/org/openstreetmap/josm/plugins/wayheatmaptracer/WayHeatmapTracerPlugin.class")})
    return env


def _source_build_sentinel_snapshot() -> dict[str, str]:
    """Hash retained build evidence in the source checkout, including absence."""
    snapshot: dict[str, str] = {}
    for relative in (Path("build/libs/wayheatmaptracer.jar"),
                     Path("build/test-results/test"), Path("build/reports/tests/test")):
        path = SOURCE_ROOT / relative
        if path.is_file():
            paths = (path,)
        elif path.is_dir():
            paths = tuple(sorted(child for child in path.rglob("*") if child.is_file()))
        else:
            snapshot[relative.as_posix()] = "missing"
            continue
        for child in paths:
            key = child.relative_to(SOURCE_ROOT).as_posix()
            snapshot[key] = hashlib.sha256(child.read_bytes()).hexdigest()
    return snapshot


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
    assert json.loads((output / "summary.json").read_text())["private_fixture_scope"] == "not-executed"
    assert (output / "reports/java/junit-xml/TEST-fake.xml").is_file()
    junit_output = output / "reports/java/junit-xml"
    assert sorted(path.name for path in junit_output.iterdir()) == ["TEST-fake.xml"]
    assert (output / "reports/java/html/index.html").is_file()
    source_reports = ROOT / "build/test-results/test"
    assert (source_reports / "output-events.bin").is_file()
    assert (source_reports / "results-generic.bin").is_file()


def test_fake_success_writes_only_disposable_build_and_preserves_source_sentinels(tmp_path: Path) -> None:
    source_before = _source_build_sentinel_snapshot()
    tools = fake_tools(tmp_path)
    output = tmp_path / "reports"
    result = invoke(tmp_path, "--profile", "public", "--output", str(output), env=env_for(tools))
    assert result.returncode == 0, result.stderr
    assert (ROOT / "build/libs/wayheatmaptracer.jar").is_file()
    assert (ROOT / "build/test-results/test/TEST-fake.xml").is_file()
    assert (ROOT / "build/reports/tests/test/index.html").is_file()
    assert _source_build_sentinel_snapshot() == source_before


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
    assert json.loads((output / "summary.json").read_text())["private_fixture_scope"] == "not-executed"
    assert (output / "reports/java/junit-xml/TEST-fake.xml").is_file()
    assert (output / "reports/java/html/index.html").is_file()
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


def test_status_reports_live_background_stage_after_handshake(tmp_path: Path) -> None:
    tools = fake_tools(tmp_path, gradle_sleep=8)
    env = env_for(tools, gradle_sleep=8)
    output = tmp_path / "progress"
    start = invoke(tmp_path, "--profile", "public", "--output", str(output), "--background", env=env)
    assert start.returncode == 0
    deadline = time.monotonic() + 4
    status = None
    while time.monotonic() < deadline:
        result = invoke(tmp_path, "--output", str(output), "--status", env=env)
        if result.returncode == 0:
            status = json.loads(result.stdout)
            if status.get("current_stage") == "java-build":
                break
        time.sleep(0.03)
    assert status is not None
    assert status["state"] == "running"
    assert status["current_stage"] == "java-build"
    final_deadline = time.monotonic() + 12
    while time.monotonic() < final_deadline:
        result = invoke(tmp_path, "--output", str(output), "--status", env=env)
        if result.returncode == 0 and json.loads(result.stdout)["state"] == "passed":
            break
        time.sleep(0.05)
    else:
        raise AssertionError("background validation did not finish")


def test_status_reports_killed_background_run_as_interrupted(tmp_path: Path) -> None:
    tools = fake_tools(tmp_path, gradle_sleep=30)
    env = env_for(tools, gradle_sleep=30)
    output = tmp_path / "killed-progress"
    gradle_pid_file = tmp_path / "gradle.pid"
    env["FAKE_PID_FILE"] = str(gradle_pid_file)
    start = invoke(tmp_path, "--profile", "public", "--output", str(output), "--background", env=env)
    assert start.returncode == 0
    deadline = time.monotonic() + 5
    current = {}
    while time.monotonic() < deadline:
        result = invoke(tmp_path, "--output", str(output), "--status", env=env)
        if result.returncode == 0:
            current = json.loads(result.stdout)
            if current.get("current_stage") == "java-build":
                break
        time.sleep(0.03)
    assert current.get("current_stage") == "java-build"
    deadline = time.monotonic() + 5
    while not gradle_pid_file.is_file() and time.monotonic() < deadline:
        time.sleep(0.02)
    assert gradle_pid_file.is_file()
    launch = json.loads((output / "launch.json").read_text())
    try:
        gradle_pid = int(gradle_pid_file.read_text())
        os.kill(launch["pid"], signal.SIGKILL)
        os.kill(gradle_pid, signal.SIGKILL)
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            result = invoke(tmp_path, "--output", str(output), "--status", env=env)
            assert result.returncode == 0
            current = json.loads(result.stdout)
            if current.get("state") in {"interrupted", "failed"}:
                break
            time.sleep(0.05)
    finally:
        for pid in (int(launch["pid"]), int(gradle_pid_file.read_text())):
            try:
                os.kill(pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
    assert current["state"] in {"interrupted", "failed"}
    assert current["run_id"] == launch["run_id"]
    assert current["current_stage"] == "java-build"
    (output / "status.json").write_text(json.dumps({"run_id": "older-run", "state": "passed",
                                                     "current_stage": "stale-stage"}))
    status = invoke(tmp_path, "--output", str(output), "--status", env=env)
    assert status.returncode == 0
    current = json.loads(status.stdout)
    assert current["state"] == "interrupted"
    assert current["run_id"] == launch["run_id"]
    assert current.get("current_stage") != "stale-stage"


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
    assert previous.get("run_id")
    launch = invoke(tmp_path, *args, "--resume", "--background", env=env)
    assert launch.returncode == 0
    state = json.loads(invoke(tmp_path, "--output", str(output), "--status", env=env).stdout)
    assert state["state"] in {"launching", "running", "passed"}
    assert state["run_id"] != previous["run_id"]
    deadline = time.monotonic() + 8
    while state["state"] not in {"passed", "failed", "interrupted"} and time.monotonic() < deadline:
        time.sleep(0.05)
        state = json.loads(invoke(tmp_path, "--output", str(output), "--status", env=env).stdout)
    assert state["state"] == "passed"
    assert state["run_id"] != previous["run_id"]
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


def _runner_root_worktree() -> dict[str, object]:
    source = ROOT / "scripts/run-validation.py"
    revision = subprocess.run(["git", "-C", str(ROOT), "rev-parse", "HEAD"], check=True,
                              text=True, stdout=subprocess.PIPE).stdout.strip()
    return {"worktree": str(ROOT), "revision": revision,
            "sourceHashes": {"scripts/run-validation.py": hashlib.sha256(source.read_bytes()).hexdigest()}}


def _required_file(path: Path) -> dict[str, str]:
    return {"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def _benchmark_fixture(tmp_path: Path, java: Path | None = None) -> tuple[Path, Path, Path, Path]:
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
    baseline = tmp_path / "baseline"
    baseline_revision, baseline_source_hash = _committed_worktree(baseline)
    candidate_identity = _runner_root_worktree()
    if java is None:
        java = tmp_path / "java17"
        java.write_text("#!/bin/sh\necho 'openjdk version \"17.0.1\"'\n")
        java.chmod(0o755)
    manifest = {
        "schemaVersion": 1, "warmups": 1, "repetitions": 3, "runTimeoutSeconds": 60,
        "environment": {"java": str(java), "javaMajor": 17, "josmVersion": 19555,
                         "josmJar": _required_file(paths["jar"])},
        "baseline": {"worktree": str(baseline), "revision": baseline_revision,
                     "sourceHashes": {"src/Plugin.java": baseline_source_hash}},
        "candidate": candidate_identity,
        "cases": [
            {"id": case_id, "archive": _required_file(paths[f"archive{index}"]),
             "osm": _required_file(paths[f"osm{index}"]), "tiles": tile_descriptors[index]}
            for case_id, index in (("N1", 1), ("N2", 2))
        ],
    }
    manifest_path = input_root / "benchmark.json"
    manifest_path.write_text(json.dumps(manifest))
    return manifest_path, paths["osm1"], java, paths["tile1_png"]


def _benchmark_validation_module(java: Path):
    module = _runner_module()
    real_run = subprocess.run
    real_which = shutil.which
    module.shutil = SimpleNamespace(which=lambda name: str(java) if name == "java" else real_which(name))

    def fake_run(args, *pargs, **kwargs):
        if args[0] == str(java) and args[1:] == ["-version"]:
            return subprocess.CompletedProcess(args, 0, 'openjdk version "17.0.1"\n', "")
        return real_run(args, *pargs, **kwargs)

    module.subprocess = SimpleNamespace(run=fake_run, PIPE=subprocess.PIPE, STDOUT=subprocess.STDOUT)
    return module


def test_numeric_josm_version_matches_benchmark_manifest_schema(tmp_path: Path) -> None:
    manifest, _, java, _ = _benchmark_fixture(tmp_path)
    module = _benchmark_validation_module(java)
    benchmark_identity = module.benchmark_manifest_identity(manifest)
    assert len(benchmark_identity) == 64
    identity, details, error = module.run_identity("rc", manifest, None)
    assert error is None
    assert details["benchmark_input_sha256"] == benchmark_identity
    assert details["manifest_validation"] == "valid"
    assert len(identity) == 64


def test_parent_benchmark_candidate_rejects_external_and_noncanonical_root_paths(tmp_path: Path) -> None:
    manifest_path, _, java, _ = _benchmark_fixture(tmp_path)
    module = _benchmark_validation_module(java)
    manifest = json.loads(manifest_path.read_text())
    candidate = dict(manifest["candidate"])
    descendant = ROOT / "candidate-descendant"
    descendant.mkdir()
    symlink = tmp_path / "candidate-symlink"
    symlink.symlink_to(ROOT, target_is_directory=True)
    external_candidate = tmp_path / "external-candidate"
    revision, source_hash = _committed_worktree(external_candidate)
    invalid_candidates = [
        {"worktree": str(external_candidate), "revision": revision,
         "sourceHashes": {"src/Plugin.java": source_hash}},
        *({**candidate, "worktree": invalid_path} for invalid_path in (
            str(ROOT.parent), str(descendant), str(symlink), str(ROOT) + "/.",
            str(ROOT / "missing" / ".."),
        )),
    ]
    for invalid_candidate in invalid_candidates:
        manifest["candidate"] = invalid_candidate
        manifest_path.write_text(json.dumps(manifest))
        with pytest.raises(module.ValidationError):
            module.benchmark_manifest_identity(manifest_path)


def test_parent_benchmark_baseline_must_be_external_and_nonoverlapping(tmp_path: Path) -> None:
    manifest_path, _, java, _ = _benchmark_fixture(tmp_path)
    module = _benchmark_validation_module(java)
    manifest = json.loads(manifest_path.read_text())
    baseline = dict(manifest["baseline"])
    descendants = ROOT / "baseline-descendant"
    descendants.mkdir()
    symlink_parent = tmp_path / "baseline-parent-symlink"
    symlink_parent.symlink_to(tmp_path, target_is_directory=True)
    symlink_ancestor_alias = symlink_parent / Path(baseline["worktree"]).name
    for invalid_path in (str(ROOT), str(ROOT.parent), str(descendants), str(symlink_ancestor_alias)):
        manifest["baseline"] = {**baseline, "worktree": invalid_path}
        manifest_path.write_text(json.dumps(manifest))
        with pytest.raises(module.ValidationError, match="baseline worktree"):
            module.benchmark_manifest_identity(manifest_path)


@pytest.mark.parametrize("mutation", ["revision", "pinned-hash", "source-bytes", "tracked-delta", "untracked-delta"])
def test_parent_benchmark_candidate_keeps_revision_and_delta_checks(tmp_path: Path, mutation: str) -> None:
    manifest_path, _, java, _ = _benchmark_fixture(tmp_path)
    module = _benchmark_validation_module(java)
    manifest = json.loads(manifest_path.read_text())
    candidate = manifest["candidate"]
    if mutation == "revision":
        candidate["revision"] = "0" * 40
    elif mutation == "pinned-hash":
        candidate["sourceHashes"]["scripts/run-validation.py"] = "0" * 64
    elif mutation == "source-bytes":
        (ROOT / "scripts/run-validation.py").write_text("changed candidate source bytes\n")
    elif mutation == "tracked-delta":
        (ROOT / "gradle.properties").write_text((ROOT / "gradle.properties").read_text() + "# changed\n")
    else:
        (ROOT / "unexpected-candidate-file.txt").write_text("undeclared\n")
    manifest_path.write_text(json.dumps(manifest))
    with pytest.raises(module.ValidationError):
        module.benchmark_manifest_identity(manifest_path)


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
    "<testsuite tests='2' failures='0' errors='0' skipped='0'/>",
    "<testsuite tests='2' failures='1' errors='0' skipped='0'><testcase/><testcase><failure/></testcase></testsuite>",
    "<testsuite tests='2' failures='0' errors='0' skipped='1'><testcase/><testcase><skipped/></testcase></testsuite>",
    "<testsuite tests='2' failures='0' errors='0' skipped='0'><testcase/></testsuite>",
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


def test_rc_requires_private_junit_case_evidence_before_private_stages(tmp_path: Path) -> None:
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
                    "--fixture-manifest", str(_fixture_manifest(tmp_path)),
                    env=env_for(tools))
    assert result.returncode != 0
    state = json.loads((output / "status.json").read_text())
    assert state["state"] == "failed"
    assert next(stage for stage in state["stages"] if stage["name"] == "junit-report-integrity")["state"] == "failed"
    assert not any(stage["name"] == "strict-production-replay" for stage in state["stages"])
    assert "required private fixture testcase identities" in (
        output / "stages" / "junit-report-integrity.log").read_text()


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


def _write_manifest_test_jar(module, plugin_class: Path, manifest: bytes) -> None:
    binary_name = "org.openstreetmap.josm.plugins.wayheatmaptracer.WayHeatmapTracerPlugin"
    artifact = module.ROOT / "build/libs/wayheatmaptracer.jar"
    artifact.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(artifact, "w") as archive:
        archive.writestr("META-INF/MANIFEST.MF", manifest)
        archive.write(plugin_class, binary_name.replace(".", "/") + ".class")
    module.shutil = SimpleNamespace(which=lambda _name: "javap")
    module.subprocess = SimpleNamespace(
        PIPE=subprocess.PIPE,
        run=lambda args, **_kwargs: subprocess.CompletedProcess(
            args, 0, f"major version: 61\n{binary_name.replace('.', '/')}", ""),
    )


def test_artifact_validation_unfolds_realistic_wrapped_main_class_and_utf8_bytes(tmp_path: Path) -> None:
    module = _runner_module()
    tools = fake_tools(tmp_path)
    plugin_class = tools.parent / "plugin-class/org/openstreetmap/josm/plugins/wayheatmaptracer/WayHeatmapTracerPlugin.class"
    manifest = (
        b"Manifest-Version: 1.0\r\n"
        b"Plugin-Class: org.openstreetmap.josm.plugins.wayheatmaptracer.WayHeatmap\r\n"
        b" TracerPlugin\r\n"
        b"Plugin-Description: Align heatmap \xc3\r\n \xa9 OSM\r\n"
        b"Plugin-Version: " + _fixture_plugin_version().encode("ascii") + b"\r\n"
        b"Plugin-Mainversion: 19555\r\n\r\n"
    )
    _write_manifest_test_jar(module, plugin_class, manifest)
    module.check_artifact()


def test_artifact_validation_uses_only_main_manifest_section(tmp_path: Path) -> None:
    module = _runner_module()
    tools = fake_tools(tmp_path)
    plugin_class = tools.parent / "plugin-class/org/openstreetmap/josm/plugins/wayheatmaptracer/WayHeatmapTracerPlugin.class"
    manifest = (
        b"Manifest-Version: 1.0\r\n"
        b"Plugin-Class: org.openstreetmap.josm.plugins.wayheatmaptracer.WayHeatmapTracerPlugin\r\n"
        b"Plugin-Version: " + _fixture_plugin_version().encode("ascii") + b"\r\n"
        b"Plugin-Mainversion: 19555\r\n\r\n"
        b"Name: plugin-entry.class\r\n"
        b"Plugin-Class: invalid.Override\r\n"
        b"Plugin-Version: invalid\r\n"
        b"Plugin-Mainversion: 1\r\n\r\n"
    )
    _write_manifest_test_jar(module, plugin_class, manifest)
    module.check_artifact()


def test_artifact_validation_rejects_a_folded_wrong_plugin_class(tmp_path: Path) -> None:
    module = _runner_module()
    tools = fake_tools(tmp_path)
    plugin_class = tools.parent / "plugin-class/org/openstreetmap/josm/plugins/wayheatmaptracer/WayHeatmapTracerPlugin.class"
    manifest = (
        b"Manifest-Version: 1.0\r\n"
        b"Plugin-Class: org.openstreetmap.josm.plugins.wayheatmaptracer.WayHeatmap\r\n"
        b" WrongPlugin\r\n"
        b"Plugin-Version: " + _fixture_plugin_version().encode("ascii") + b"\r\n"
        b"Plugin-Mainversion: 19555\r\n\r\n"
    )
    _write_manifest_test_jar(module, plugin_class, manifest)
    with pytest.raises(module.ValidationError, match="missing required JOSM plugin fields"):
        module.check_artifact()


@pytest.mark.parametrize("version", ["0.22.0-rc.6", "0.22.0-rc.7"])
def test_fake_artifact_version_tracks_disposable_plugin_metadata(tmp_path: Path, version: str) -> None:
    properties = ROOT / "gradle.properties"
    lines = properties.read_text(encoding="utf-8").splitlines()
    properties.write_text("\n".join(
        f"version={version}" if line.startswith("version=") else line for line in lines
    ) + "\n", encoding="utf-8")

    tools = fake_tools(tmp_path)
    result = invoke(tmp_path, "--profile", "public", "--output", str(tmp_path / "reports"),
                    env=env_for(tools))

    assert result.returncode == 0, result.stderr
    status = json.loads((tmp_path / "reports" / "status.json").read_text())
    assert status["state"] == "passed"


@pytest.mark.parametrize("manifest", [
    b"Manifest-Version: 1.0\r\nPlugin-Class: org.openstreetmap.josm.plugins.wayheatmaptracer.WayHeatmapTracerPlugin\r\n"
    b"Plugin-Class: org.openstreetmap.josm.plugins.wayheatmaptracer.WayHeatmapTracerPlugin\r\n"
    b"Plugin-Version: @PLUGIN_VERSION@\r\nPlugin-Mainversion: 19555\r\n\r\n",
    b" orphaned continuation\r\nManifest-Version: 1.0\r\n"
    b"Plugin-Class: org.openstreetmap.josm.plugins.wayheatmaptracer.WayHeatmapTracerPlugin\r\n"
    b"Plugin-Version: @PLUGIN_VERSION@\r\nPlugin-Mainversion: 19555\r\n\r\n",
])
def test_artifact_validation_rejects_duplicate_or_orphan_manifest_fields(
    tmp_path: Path, manifest: bytes,
) -> None:
    module = _runner_module()
    tools = fake_tools(tmp_path)
    plugin_class = tools.parent / "plugin-class/org/openstreetmap/josm/plugins/wayheatmaptracer/WayHeatmapTracerPlugin.class"
    manifest = manifest.replace(b"@PLUGIN_VERSION@", _fixture_plugin_version().encode("ascii"))
    _write_manifest_test_jar(module, plugin_class, manifest)
    with pytest.raises(module.ValidationError):
        module.check_artifact()


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


PRIVATE_CASES = {
    ("org.openstreetmap.josm.plugins.wayheatmaptracer.service.FixtureRegressionTest",
     "tracedChangedSegmentsStayCloseToManualBaseline()"),
    ("org.openstreetmap.josm.plugins.wayheatmaptracer.service.FixtureRegressionTest",
     "corridorAwareTrackerStaysInsideRealWorldAcceptanceEnvelope()"),
    ("org.openstreetmap.josm.plugins.wayheatmaptracer.service.HeatmapFixtureArchiveTest",
     "realHeatmapArchiveDecodesAndContainsExpectedStructure()"),
    ("org.openstreetmap.josm.plugins.wayheatmaptracer.service.HeatmapFixtureArchiveTest",
     "corridorAwareTrackerConsumesCompleteProfilesFromRealSparseAndDenseTiles()"),
    ("org.openstreetmap.josm.plugins.wayheatmaptracer.service.SparseCorridorDebugReplayTest",
     "currentTrackerBuildsAStableCompleteCandidateFromTheKnownSparseCorridor()"),
}


def _runner_module():
    spec = importlib.util.spec_from_file_location("validation_fixture_contract", RUNNER)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def _fixture_manifest(tmp_path: Path) -> Path:
    fixture_paths = {}
    for name in ("fixtureRegression", "heatmapArchive", "sparseCorridorDebug"):
        fixture = tmp_path / f"{name}.zip"
        fixture.write_bytes((name + " private fixture").encode())
        fixture_paths[name] = {"path": str(fixture), "sha256": hashlib.sha256(fixture.read_bytes()).hexdigest()}
    manifest = tmp_path / "fixtures.json"
    manifest.write_text(json.dumps({"schemaVersion": 1, "fixtures": fixture_paths}))
    return manifest


def _write_junit(tmp_path: Path, cases: list[tuple[str, str, bool]]) -> None:
    report_dir = tmp_path / "build" / "test-results" / "test"
    report_dir.mkdir(parents=True, exist_ok=True)
    rows = "".join(
        f'<testcase classname="{classname}" name="{method}">' + ("<skipped/>" if skipped else "")
        + "</testcase>"
        for classname, method, skipped in cases
    )
    (report_dir / "TEST-fixtures.xml").write_text(
        f'<testsuite tests="{len(cases)}" failures="0" errors="0" '
        f'skipped="{sum(skipped for _, _, skipped in cases)}">{rows}</testsuite>'
    )


def test_fixture_manifest_binds_exact_archive_set_bytes_and_resume_identity(tmp_path: Path) -> None:
    module = _runner_module()
    manifest = _fixture_manifest(tmp_path)
    first = module.fixture_manifest_identity(manifest)
    identity, details, error = module.run_identity("rc", None, None, manifest)
    assert len(first) == 64
    assert details["fixture_input_sha256"] == first
    assert error is None
    archive = tmp_path / "fixtureRegression.zip"
    archive.write_bytes(b"changed private fixture")
    with pytest.raises(module.ValidationError, match="SHA-256 mismatch"):
        module.fixture_manifest_identity(manifest)
    assert module.run_identity("rc", None, None, manifest)[0] != identity


def test_fixture_manifest_rejects_missing_or_extra_archive_descriptors(tmp_path: Path) -> None:
    module = _runner_module()
    manifest = _fixture_manifest(tmp_path)
    data = json.loads(manifest.read_text())
    del data["fixtures"]["sparseCorridorDebug"]
    manifest.write_text(json.dumps(data))
    with pytest.raises(module.ValidationError, match="exactly"):
        module.fixture_manifest_identity(manifest)
    data["fixtures"]["sparseCorridorDebug"] = {"path": "x", "sha256": "0" * 64}
    data["fixtures"]["unreviewed"] = {"path": "x", "sha256": "0" * 64}
    manifest.write_text(json.dumps(data))
    with pytest.raises(module.ValidationError, match="exactly"):
        module.fixture_manifest_identity(manifest)


def test_fixture_archives_may_remain_inside_the_local_checkout(tmp_path: Path) -> None:
    module = _runner_module()
    checkout = tmp_path / "checkout"
    checkout.mkdir()
    descriptors = {}
    for name in ("fixtureRegression", "heatmapArchive", "sparseCorridorDebug"):
        archive = checkout / f"{name}.zip"
        archive.write_bytes(name.encode())
        descriptors[name] = {"path": str(archive), "sha256": hashlib.sha256(archive.read_bytes()).hexdigest()}
    manifest = tmp_path / "fixtures.json"
    manifest.write_text(json.dumps({"schemaVersion": 1, "fixtures": descriptors}))
    module.ROOT = checkout
    assert len(module.fixture_manifest_identity(manifest)) == 64


def test_junit_profile_proves_private_fixture_case_scope_and_execution(tmp_path: Path) -> None:
    module = _runner_module()
    module.ROOT = tmp_path
    private = sorted((classname, method, False) for classname, method in PRIVATE_CASES)
    _write_junit(tmp_path, [("PublicTest", "works", False)])
    module.check_junit_reports("public")
    with pytest.raises(module.ValidationError, match="private fixture"):
        module.check_junit_reports("rc")
    _write_junit(tmp_path, private)
    module.check_junit_reports("rc")
    _write_junit(tmp_path, private[:-1])
    with pytest.raises(module.ValidationError, match="required private fixture testcase"):
        module.check_junit_reports("rc")
    _write_junit(tmp_path, [(a, b, index == 4) for index, (a, b, _) in enumerate(private)])
    with pytest.raises(module.ValidationError, match="skipped"):
        module.check_junit_reports("rc")


def test_public_junit_rejects_any_private_case_in_stale_reports(tmp_path: Path) -> None:
    module = _runner_module()
    module.ROOT = tmp_path
    classname, method = next(iter(PRIVATE_CASES))
    _write_junit(tmp_path, [(classname, method, False)])
    with pytest.raises(module.ValidationError, match="private fixture"):
        module.check_junit_reports("public")


def test_rc_fixture_manifest_is_required_and_public_rejects_it(tmp_path: Path) -> None:
    output = tmp_path / "reports"
    missing = invoke(tmp_path, "--profile", "rc", "--output", str(output))
    assert missing.returncode == 1
    missing_status = json.loads((output / "status.json").read_text())
    assert missing_status["state"] == "unavailable"
    assert missing_status["stages"][0]["name"] == "rc-input-availability"
    assert json.loads((output / "summary.json").read_text())["private_fixture_scope"] == "required"
    assert not (output / "stages" / "java-build.log").exists()
    manifest = _fixture_manifest(tmp_path)
    tools = fake_tools(tmp_path)
    result = invoke(tmp_path, "--profile", "public", "--fixture-manifest", str(manifest),
                    "--output", str(output), env=env_for(tools))
    assert result.returncode == 2
    assert "public" in result.stderr.lower()


def test_public_runner_rejects_a_private_case_left_in_junit_xml(tmp_path: Path) -> None:
    tools = fake_tools(tmp_path)
    classname, method = next(iter(PRIVATE_CASES))
    stale_report = tmp_path / "stale-junit.xml"
    stale_report.write_text(
        f'<testsuite tests="1" failures="0" errors="0" skipped="0">'
        f'<testcase classname="{classname}" name="{method}"/></testsuite>'
    )
    env = env_for(tools)
    env["FAKE_JUNIT_REPORT"] = str(stale_report)
    output = tmp_path / "public-output"
    result = invoke(tmp_path, "--profile", "public", "--output", str(output), env=env)
    assert result.returncode != 0
    state = json.loads((output / "status.json").read_text())
    assert state["stages"][-1]["name"] == "junit-report-integrity"
    assert "private fixture testcases" in (output / "stages/junit-report-integrity.log").read_text()


def test_rc_rejects_fixture_mutation_between_preflight_and_junit_gate(tmp_path: Path) -> None:
    tools = fake_tools(tmp_path)
    benchmark, _, _, _ = _benchmark_fixture(tmp_path, tools / "java")
    corpus = tmp_path / "case.wthb"
    corpus.write_bytes(b"private replay payload")
    replay = tmp_path / "replay.json"
    replay.write_text(json.dumps({"schema": "wayheatmaptracer-v022-corpus-1", "cases": [
        {"caseId": "case", "sourcePath": str(corpus),
         "outerSha256": hashlib.sha256(corpus.read_bytes()).hexdigest(), "bundleSha256": "b" * 64}
    ]}))
    fixture_manifest = _fixture_manifest(tmp_path)
    mutate = tmp_path / "fixtureRegression.zip"
    env = env_for(tools)
    env["FAKE_MUTATE_FIXTURE"] = str(mutate)
    output = tmp_path / "rc-output"
    result = invoke(tmp_path, "--profile", "rc", "--output", str(output),
                    "--benchmark-manifest", str(benchmark), "--replay-manifest", str(replay),
                    "--fixture-manifest", str(fixture_manifest), env=env)
    assert result.returncode != 0
    state = json.loads((output / "status.json").read_text())
    assert state["state"] == "failed"
    assert state["stages"][-1]["name"] == "junit-report-integrity"
    assert "fixture archive changed" in (output / "stages/junit-report-integrity.log").read_text()


def test_rc_manifest_mutation_at_availability_boundary_is_durable(tmp_path: Path, monkeypatch) -> None:
    tools = fake_tools(tmp_path)
    monkeypatch.setenv("PATH", f"{tools}:{os.environ['PATH']}")
    benchmark, _, _, _ = _benchmark_fixture(tmp_path, tools / "java")
    corpus = tmp_path / "case.wthb"
    corpus.write_bytes(b"private replay payload")
    replay = tmp_path / "replay.json"
    replay.write_text(json.dumps({"schema": "wayheatmaptracer-v022-corpus-1", "cases": [
        {"caseId": "case", "sourcePath": str(corpus),
         "outerSha256": hashlib.sha256(corpus.read_bytes()).hexdigest(), "bundleSha256": "c" * 64}
    ]}))
    fixture_manifest = _fixture_manifest(tmp_path)
    module = _runner_module()
    original_identity = module.run_identity

    def identity_then_remove_archive(profile, benchmark_path, replay_path, fixture_path=None):
        result = original_identity(profile, benchmark_path, replay_path, fixture_path)
        (tmp_path / "fixtureRegression.zip").unlink()
        return result

    monkeypatch.setattr(module, "run_identity", identity_then_remove_archive)
    output = tmp_path / "rc-preflight-output"
    result = module.run_profile("rc", output, benchmark, replay, fixture_manifest, False)
    assert result == 1
    status = json.loads((output / "status.json").read_text())
    assert status["state"] == "unavailable"
    assert status["stages"][0]["name"] == "rc-input-availability"
    assert status["stages"][0]["state"] == "unavailable"
    assert "fixture inputs changed" in (output / "stages/rc-input-availability.log").read_text()
    assert json.loads((output / "summary.json").read_text())["result"] == "UNAVAILABLE"
    assert not (output / "stages/java-build.log").exists()


def test_explicit_gradle_profiles_route_private_fixtures_without_changing_default() -> None:
    module = _runner_module()
    public = module.java_validation_command("public", None)
    assert "-PvalidationProfile=public" in public
    rc = module.java_validation_command("rc", {
        "fixtureRegression": Path("/private/fixture-regression.zip"),
        "heatmapArchive": Path("/private/extracted-tiles.zip"),
        "sparseCorridorDebug": Path("/private/sparse-debug.zip"),
    })
    assert "-PvalidationProfile=rc" in rc
    assert "-PfixtureRegressionArchive=/private/fixture-regression.zip" in rc
    assert "-PheatmapFixtureArchive=/private/extracted-tiles.zip" in rc
    assert "-PsparseCorridorDebugArchive=/private/sparse-debug.zip" in rc
    default = module.java_validation_command(None, None)
    assert not any(arg.startswith("-PvalidationProfile=") for arg in default)
