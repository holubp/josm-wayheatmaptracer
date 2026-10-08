"""Subprocess-level safety tests for the unattended validation runner."""

from __future__ import annotations

import json
import os
import importlib.util
import signal
import subprocess
import sys
import time
import zipfile
from pathlib import Path
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
                "FAKE_GRADLE_SLEEP": str(gradle_sleep), "FAKE_PLUGIN_VERSION": plugin_version})
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
                "FAKE_PYTEST_FAIL_FILE": ""})
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
    assert status_after["reused_stages"] == len(status_before["stages"]) - 1
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
    state = json.loads((output / "status.json").read_text())
    assert state["state"] in {"queued", "running", "passed"}
    deadline = time.monotonic() + 8
    while state["state"] not in {"passed", "failed", "interrupted"} and time.monotonic() < deadline:
        time.sleep(0.05)
        status = invoke(tmp_path, "--output", str(output), "--status", env=env)
        state = json.loads(status.stdout)
    assert state["state"] == "passed"


def test_output_under_build_is_rejected_without_touching_it(tmp_path: Path) -> None:
    output = ROOT / "build" / "unattended-runner-test-output"
    result = invoke(tmp_path, "--profile", "public", "--output", str(output))
    assert result.returncode != 0
    assert not output.exists()


def test_rc_missing_benchmark_implementation_is_explicitly_unavailable(tmp_path: Path) -> None:
    tools = fake_tools(tmp_path)
    benchmark = tmp_path / "benchmark.json"
    replay = tmp_path / "replay.json"
    benchmark.write_text("{}\n")
    replay.write_text("{}\n")
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
    assert resumed["stages"][2]["name"] == "python-tests"
    assert resumed["stages"][2]["reused"] is False


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
    child.write_text("import os,time,pathlib\npathlib.Path(os.environ['PIDFILE']).write_text(str(os.getpid()))\ntime.sleep(30)\n")
    previous = os.environ.copy()
    os.environ.update({"PIDFILE": str(pidfile)})
    try:
        result = module.run_command([sys.executable, str(child)], ROOT, tmp_path / "timeout.log", 3)
    finally:
        os.environ.clear()
        os.environ.update(previous)
    assert result == 124
    child_pid = int(pidfile.read_text())
    try:
        os.kill(child_pid, 0)
    except ProcessLookupError:
        pass
    else:
        raise AssertionError("timed-out child process remained alive")


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
