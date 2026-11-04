"""Public-only ordinary Legacy benchmark contracts; never run a timed kernel."""

import importlib.util
import hashlib
import io
import json
from pathlib import Path
import subprocess
from types import SimpleNamespace
import zipfile

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "ordinary-legacy-benchmark.py"


def module():
    spec = importlib.util.spec_from_file_location("ordinary_legacy_benchmark", SCRIPT)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def test_workflow_is_manual_default_off_and_uploads_only_allowlisted_public_evidence():
    workflow = yaml.load((ROOT / ".github/workflows/build.yml").read_text(), Loader=yaml.BaseLoader)
    setting = workflow["on"]["workflow_dispatch"]["inputs"]["ordinary_legacy_benchmark"]
    assert setting["type"] == "boolean" and setting["default"] == "false"
    steps = workflow["jobs"]["public-validation"]["steps"]
    run = next(s for s in steps if s.get("id") == "ordinary-legacy-benchmark")
    assert run["if"] == "github.event_name == 'workflow_dispatch' && inputs.ordinary_legacy_benchmark && steps.public-validation.outcome == 'success'"
    assert "--execute" in run["run"] and "--input" not in run["run"]
    assert run["timeout-minutes"] == "9"
    upload = next(s for s in steps if s.get("with", {}).get("name") == "ordinary-legacy-benchmark-evidence")
    assert upload["if"] == "always() && github.event_name == 'workflow_dispatch' && inputs.ordinary_legacy_benchmark"
    assert upload["with"]["path"].splitlines() == [
        "${{ runner.temp }}/ordinary-legacy-benchmark/summary.json",
        "${{ runner.temp }}/ordinary-legacy-benchmark/protocol.json",
        "${{ runner.temp }}/ordinary-legacy-benchmark/environment.json",
        "${{ runner.temp }}/ordinary-legacy-benchmark/samples.jsonl",
        "${{ runner.temp }}/ordinary-legacy-benchmark/runtime.log",
        "${{ runner.temp }}/ordinary-legacy-benchmark/controls.log",
    ]


def test_fixed_protocol_and_public_fixture_surface():
    m = module()
    assert m.FIXTURES == ("parallel-185", "weak-gap-185", "switchback-512")
    assert m.PROTOCOL["warmup_batches"] == 100 and m.PROTOCOL["warmup_calls"] == 32
    assert m.PROTOCOL["readiness_window"] == 20
    assert m.PROTOCOL["measured_pairs"] == 16 and m.PROTOCOL["measured_calls"] == 64
    assert m.MAX_CALLS == 25_344 and m.TIMEOUT_SECONDS == 480
    with pytest.raises(SystemExit):
        m.parser().parse_args(["--input", "unexpected.csv"])
    assert m.parser().parse_args([]).execute is False


def test_source_guard_accepts_only_unchanged_production_and_allows_helper_changes(tmp_path):
    m = module()
    def git(*args):
        return subprocess.check_output(["git", "-c", "safe.directory=*", "-C", str(tmp_path), *args], text=True).strip()
    subprocess.run(["git", "init", "-q", str(tmp_path)], check=True)
    (tmp_path / "src/main/java").mkdir(parents=True)
    source = tmp_path / "src/main/java/Public.java"
    source.write_text("class Public {}\n")
    (tmp_path / "build.gradle.kts").write_text("// fixed build\n")
    (tmp_path / "gradle.properties").write_text("version=synthetic\n")
    git("add", ".")
    git("-c", "user.name=synthetic", "-c", "user.email=synthetic@example.invalid", "commit", "-qm", "synthetic")
    expected = {p: git("rev-parse", f"HEAD:{p}") for p in m.PRODUCTION_BLOBS}
    m.source_guard(tmp_path, expected)
    (tmp_path / "helper.py").write_text("# helper only\n")
    m.source_guard(tmp_path, expected)
    extra = tmp_path / "src/main/java/Extra.java"
    extra.write_text("class Extra {}\n")
    with pytest.raises(m.Refusal):
        m.source_guard(tmp_path, expected)
    extra.unlink()
    (tmp_path / ".gitignore").write_text("ignored.java\n")
    ignored = tmp_path / "src/main/java/ignored.java"
    ignored.write_text("class Ignored {}\n")
    with pytest.raises(m.Refusal):
        m.source_guard(tmp_path, expected)
    ignored.unlink()
    source.write_text("class Changed {}\n")
    with pytest.raises(m.Refusal):
        m.source_guard(tmp_path, expected)
    git("add", ".")
    git("-c", "user.name=synthetic", "-c", "user.email=synthetic@example.invalid", "commit", "-qm", "changed source")
    with pytest.raises(m.Refusal):
        m.source_guard(tmp_path, expected)


def test_actual_source_guard_accepts_this_helper_only_worktree():
    m = module()
    # The actual source guard is also exercised against this clean helper-only worktree.
    assert m.source_guard(ROOT)["production_baseline"] == "323c2874aa5af4a4c2f0cae618f47f6a36d10803"


def test_normal_jvm_and_ci_only_admission():
    m = module()
    command = m.java_command(Path("java"), Path("classes"), Path("baseline.jar"), Path("corrected.jar"), Path("host.jar"))
    assert "-Xms512m" in command and "-Xmx512m" in command
    assert all(not any(t in arg for t in ("Xint", "Tiered", "CompileThreshold", "javaagent")) for arg in command)
    with pytest.raises(m.Refusal):
        m.require_ci({})
    env = {"GITHUB_ACTIONS": "true", "GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_RUN_ATTEMPT": "1", "ORDINARY_LEGACY_BENCHMARK": "true"}
    m.require_ci(env)
    with pytest.raises(m.Refusal):
        m.require_ci({**env, "GITHUB_RUN_ATTEMPT": "2"})


def fake_log(m, complete=True):
    rows = []
    for fixture in (m.FIXTURES if complete else m.FIXTURES[:1]):
        rows.append(f"fixture={fixture} input={m.FIXTURE_HASHES[fixture]}")
        for phase, count, calls in (("WARMUP", 100, 32), ("MEASURED", 16 if complete else 0, 64)):
            for index in range(count):
                for version in (m.VERSIONS if index % 2 == 0 else tuple(reversed(m.VERSIONS))):
                    rows.append(f"sample fixture={fixture} version={version} phase={phase} index={index} calls={calls} batchNanos=64000 output={'2' * 64} batchOutput={'3' * 64} candidates=2 compilerBefore=-1 compilerAfter=-1 gcCountBefore=0 gcCountAfter=0 gcMillisBefore=0 gcMillisAfter=0")
            if phase == "WARMUP":
                for version in m.VERSIONS:
                    rows.append(f"readiness fixture={fixture} version={version} ready={'true' if complete else 'false'} medianRatio={1 if complete else .5} fittedDrift=0")
        if complete:
            rows.append(f"fixtureComplete={fixture}")
    rows.append("outcome=COMPLETE" if complete else f"outcome=INCONCLUSIVE fixture={m.FIXTURES[0]} reason=WARMUP_NOT_READY")
    return "\n".join(rows)


def test_complete_and_inconclusive_receipts_keep_every_cell_and_unknown_counters():
    m = module()
    full = m.parse_log(fake_log(m))
    assert full["completed_calls"] == 25_344 and len(full["samples"]) == 696
    assert full["no_tolerance_decision"] is True
    assert full["samples"][0]["compilation_delta"] is None
    assert full["samples"][0]["gc_count_delta"] == 0
    stopped = m.parse_log(fake_log(m, False))
    assert stopped["completed_calls"] == 6400 and stopped["measured_calls"] == 0
    assert stopped["summary"] == {} and stopped["outcome"] == "INCONCLUSIVE"


@pytest.mark.parametrize("mutation", [
    lambda text: text.replace("outcome=COMPLETE", "outcome=COMPLETE_BUT_UNKNOWN"),
    lambda text: text.replace("ready=true", "ready=false"),
    lambda text: text.replace("output=" + "2" * 64, "output=" + "4" * 64, 1),
    lambda text: text.replace("fixture=parallel-185", "fixture=unknown", 1),
    lambda text: text.replace(" index=0 calls=32", " index=1 calls=32", 1),
])
def test_receipt_disagreement_or_schedule_bypass_is_hard_failure(mutation):
    m = module()
    with pytest.raises(m.Refusal):
        m.parse_log(mutation(fake_log(m)))


def test_helper_sources_accept_no_external_fixture_data_or_special_vm_modes():
    paths = list((ROOT / "src/tools/java/org/openstreetmap/josm/plugins/wayheatmaptracer/benchmark").glob("*.java"))
    assert len(paths) == 2
    text = "\n".join(p.read_text() for p in paths)
    assert all(name in text for name in ("parallel-185", "weak-gap-185", "switchback-512"))
    for forbidden in ("native-profiles.csv", "/data/data/", "/storage/emulated/", "Drasov", "readAllLines", "URL.open", "-Xint", "System.gc("):
        assert forbidden not in text


def test_pinned_jar_verifier_rejects_wrong_hash_size_and_symlink(tmp_path):
    m = module()
    jar = tmp_path / "public.jar"
    with zipfile.ZipFile(jar, "w") as archive:
        archive.writestr("public.txt", "synthetic")
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    assert m.verify_jar(jar, digest, jar.stat().st_size) == digest
    for expected, size in (("0" * 64, jar.stat().st_size), (digest, jar.stat().st_size + 1)):
        with pytest.raises(m.Refusal):
            m.verify_jar(jar, expected, size)
    link = tmp_path / "link.jar"
    link.symlink_to(jar)
    with pytest.raises(m.Refusal):
        m.verify_jar(link, digest, jar.stat().st_size)


def test_public_download_is_one_bounded_request_and_rejects_wrong_content(tmp_path):
    m = module()
    calls = []
    class Response(io.BytesIO):
        def read(self, size=-1):
            assert size == m.RELEASE_SIZE + 1
            return super().read(size)
    def opener(url, timeout):
        calls.append((url, timeout))
        return Response(b"wrong public bytes")
    with pytest.raises(m.Refusal):
        m.fetch_baseline(tmp_path, opener)
    assert calls == [(m.RELEASE_URL, 30)] and not list(tmp_path.iterdir())


def test_failed_validation_never_downloads_or_executes_a_kernel(tmp_path, monkeypatch):
    m = module()
    for key, value in {"GITHUB_ACTIONS": "true", "GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_RUN_ATTEMPT": "1", "ORDINARY_LEGACY_BENCHMARK": "true"}.items():
        monkeypatch.setenv(key, value)
    validation = tmp_path / "validation"
    validation.mkdir()
    (validation / "summary.json").write_text(json.dumps({"result": "FAIL", "profile": "public"}))
    def forbidden(*args):
        raise AssertionError("Network or kernel should not be entered")
    monkeypatch.setattr(m, "fetch_baseline", forbidden)
    args = m.parser().parse_args(["--execute", "--validation-output", str(validation), "--host-jar", str(tmp_path / "host.jar"), "--output", str(tmp_path / "output")])
    assert m.execute(args) == 1
    assert json.loads((tmp_path / "output/summary.json").read_text())["outcome"] == "REFUSED"


def test_timeout_keeps_complete_prefix_and_marks_active_calls_unknown():
    m = module()
    text = "\n".join(fake_log(m).splitlines()[:3]) + "\nsample fixture=parallel-185 incomplete"
    result = m.parse_log(text, partial=True)
    assert len(result["samples"]) == 2 and result["completed_calls"] == 64
    assert result["active_batch_calls"] == "UNKNOWN" and result["trailing_log_fragment"] is True
    assert result["summary"] == {} and result["outcome"] == "INCONCLUSIVE"


@pytest.mark.parametrize("fault", ["write", "replace", "serialization"])
def test_json_replacement_failure_preserves_complete_existing_receipt(tmp_path, monkeypatch, fault):
    m = module()
    receipt = tmp_path / "summary.json"
    initial = b'{"outcome":"INCONCLUSIVE","reason":"EXECUTION_NOT_FINISHED"}\n'
    receipt.write_bytes(initial)
    if fault == "replace":
        monkeypatch.setattr(m.os, "replace", lambda *args: (_ for _ in ()).throw(OSError("unsafe details")))
    elif fault == "write":
        original_open = Path.open
        def failing_open(path, *args, **kwargs):
            target = original_open(path, *args, **kwargs)
            if args and args[0] in ("w", "x"):
                original_write = target.write
                def fail(text):
                    original_write(text[:4])
                    raise OSError("unsafe details")
                target.write = fail
            return target
        monkeypatch.setattr(Path, "open", failing_open)
    with pytest.raises((OSError, ValueError)):
        m.write_json(receipt, {"outcome": "COMPLETE", "value": float("nan") if fault == "serialization" else 1})
    assert receipt.read_bytes() == initial
    assert list(tmp_path.iterdir()) == [receipt]


def synthetic_execution(tmp_path, monkeypatch, m, text=None, timed_out=False):
    """Replace only acquisition/JVM boundaries; exercise real receipt/parser/export code."""
    for key, value in {"GITHUB_ACTIONS": "true", "GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_RUN_ATTEMPT": "1", "ORDINARY_LEGACY_BENCHMARK": "true", "JAVA_HOME": str(tmp_path / "jdk")}.items():
        monkeypatch.setenv(key, value)
    validation = tmp_path / "validation"
    validation.mkdir()
    (validation / "summary.json").write_text(json.dumps({"result": "PASS", "profile": "public", "private_fixture_scope": "not-executed"}))
    monkeypatch.setattr(m, "source_guard", lambda *args: {"source": "synthetic"})
    monkeypatch.setattr(m, "sha", lambda *args: "0" * 64)
    monkeypatch.setattr(m, "verify_jar", lambda *args: None)
    monkeypatch.setattr(m, "fetch_baseline", lambda *args: tmp_path / "baseline.jar")
    monkeypatch.setattr(m.os, "statvfs", lambda *args: SimpleNamespace(f_bavail=m.DISK_FLOOR, f_frsize=1))
    calls = []
    def fake_process(command, **kwargs):
        calls.append(command)
        if "--benchmark" in command:
            kwargs["stdout"].write(fake_log(m) if text is None else text)
            if timed_out:
                raise subprocess.TimeoutExpired(command, m.TIMEOUT_SECONDS)
        return SimpleNamespace(returncode=0)
    monkeypatch.setattr(m.subprocess, "run", fake_process)
    return m.parser().parse_args(["--execute", "--validation-output", str(validation), "--host-jar", str(tmp_path / "host.jar"), "--output", str(tmp_path / "output")]), calls


@pytest.mark.parametrize("log_case", ["complete", "inconclusive", "timeout"])
def test_synthetic_execution_publishes_exact_samples_and_final_receipt(tmp_path, monkeypatch, log_case):
    m = module()
    raw = fake_log(m, log_case == "complete")
    if log_case == "timeout":
        raw = "\n".join(fake_log(m).splitlines()[:3]) + "\nsample fixture=parallel-185 incomplete"
    args, calls = synthetic_execution(tmp_path, monkeypatch, m, raw, log_case == "timeout")
    expected = m.parse_log(raw, partial=log_case == "timeout")
    assert m.execute(args) == 0 and len(calls) == 4
    receipt = json.loads((args.output / "summary.json").read_text())
    samples = [json.loads(line) for line in (args.output / "samples.jsonl").read_text().splitlines()]
    assert samples == expected["samples"] and receipt["completed_calls"] == expected["completed_calls"]
    assert receipt["outcome"] == expected["outcome"] and receipt["fences_unchanged"] is True
    assert receipt["active_batch_calls"] == expected["active_batch_calls"]
    assert (args.output / "runtime.log").read_text() == raw
    assert not list(args.output.glob(".*.tmp"))


@pytest.mark.parametrize("primary", ["source", "parser", "statistics"])
def test_primary_failure_survives_refusal_export_failure(tmp_path, monkeypatch, capsys, primary):
    m = module()
    args, calls = synthetic_execution(tmp_path, monkeypatch, m)
    original = m.write_json
    initial = []
    def receipt(path, value):
        if value.get("outcome") == "REFUSED":
            # A failing replacement may write partial staging bytes, never the old receipt.
            monkeypatch.setattr(m.os, "replace", lambda *args: (_ for _ in ()).throw(OSError("secondary secret")))
        original(path, value)
        if value.get("reason") == "EXECUTION_NOT_FINISHED":
            initial.append(path.read_bytes())
    monkeypatch.setattr(m, "write_json", receipt)
    if primary == "source":
        monkeypatch.setattr(m, "source_guard", lambda *args: (_ for _ in ()).throw(m.Refusal("primary secret")))
        cause = "Refusal"
    elif primary == "parser":
        monkeypatch.setattr(m, "parse_log", lambda *args, **kwargs: (_ for _ in ()).throw(m.Refusal("primary secret")))
        cause = "Refusal"
    else:
        monkeypatch.setattr(m.statistics, "median", lambda *args: (_ for _ in ()).throw(ValueError("primary secret")))
        cause = "ValueError"
    assert m.execute(args) == 1
    assert (args.output / "summary.json").read_bytes() == initial[0]
    console = capsys.readouterr().out
    assert "cause=" + cause in console and "Public benchmark evidence export failed" in console
    assert "secret" not in console and "COMPLETE" not in console
    assert len(calls) == (0 if primary == "source" else 4)
    if calls:
        assert (args.output / "runtime.log").read_text() == fake_log(m)


@pytest.mark.parametrize("stage", ["samples", "final"])
@pytest.mark.parametrize("log_case", ["complete", "inconclusive", "timeout"])
def test_partial_export_write_and_refusal_write_keep_initial_receipt_and_raw_log(tmp_path, monkeypatch, capsys, stage, log_case):
    m = module()
    raw = fake_log(m, log_case == "complete")
    if log_case == "timeout":
        raw = "\n".join(fake_log(m).splitlines()[:3]) + "\nsample fixture=parallel-185 incomplete"
    args, calls = synthetic_execution(tmp_path, monkeypatch, m, raw, log_case == "timeout")
    original_json = m.write_json
    initial = []
    def save_initial(path, value):
        original_json(path, value)
        if value.get("reason") == "EXECUTION_NOT_FINISHED":
            initial.append(path.read_bytes())
    monkeypatch.setattr(m, "write_json", save_initial)
    original_open = Path.open
    def failing_open(path, *args, **kwargs):
        target = original_open(path, *args, **kwargs)
        if args and args[0] == "x" and path.name in (".samples.jsonl.tmp", ".summary.json.tmp"):
            original_write = target.write
            def fail(text):
                summary = path.name == ".summary.json.tmp"
                value = json.loads(text) if summary else {}
                if ((summary and value.get("outcome") == "REFUSED")
                        or (stage == "final" and summary and value.get("reason") != "EXECUTION_NOT_FINISHED")
                        or (stage == "samples" and not summary)):
                    original_write(text[:4])
                    raise OSError("unsafe partial write")
                return original_write(text)
            target.write = fail
        return target
    monkeypatch.setattr(Path, "open", failing_open)
    assert m.execute(args) == 1
    summary = args.output / "summary.json"
    assert summary.read_bytes() == initial[0]
    receipt = json.loads(summary.read_text())
    assert receipt["completed_calls"] is None and receipt["active_batch_calls"] == "UNKNOWN"
    assert (args.output / "runtime.log").read_text() == raw and len(calls) == 4
    if stage == "samples":
        assert not (args.output / "samples.jsonl").exists()
    elif log_case == "timeout":
        assert len((args.output / "samples.jsonl").read_text().splitlines()) == 2
    assert not list(args.output.glob(".*.tmp"))
    console = capsys.readouterr().out
    assert "cause=OSError" in console and "Public benchmark evidence export failed" in console
    assert "unsafe" not in console and "COMPLETE" not in console


@pytest.mark.parametrize("stage", ["initial", "protocol", "samples", "final"])
@pytest.mark.parametrize("log_complete", [True, False])
def test_export_failure_is_nonzero_and_never_publishes_success(tmp_path, monkeypatch, capsys, stage, log_complete):
    m = module()
    raw = fake_log(m, log_complete)
    args, calls = synthetic_execution(tmp_path, monkeypatch, m, raw)
    original_replace = m.os.replace
    initial = []
    def fail_replace(source, target):
        target = Path(target)
        if target.name == "summary.json":
            value = json.loads(Path(source).read_text())
            if value.get("outcome") == "REFUSED" or (stage == "initial" and not initial) or (stage == "final" and value.get("reason") != "EXECUTION_NOT_FINISHED"):
                raise OSError("export secret")
        if target.name == {"protocol": "protocol.json", "samples": "samples.jsonl"}.get(stage):
            raise OSError("export secret")
        original_replace(source, target)
        if target.name == "summary.json":
            initial.append(target.read_bytes())
    monkeypatch.setattr(m.os, "replace", fail_replace)
    assert m.execute(args) == 1
    summary = args.output / "summary.json"
    if stage == "initial":
        assert not summary.exists() and not calls
    else:
        assert summary.read_bytes() == initial[0]
        assert json.loads(summary.read_text())["reason"] == "EXECUTION_NOT_FINISHED"
    assert len(calls) == (0 if stage in ("initial", "protocol") else 4)
    if calls:
        assert (args.output / "runtime.log").read_text() == raw
    if stage == "samples":
        assert not (args.output / "samples.jsonl").exists()
    assert not list(args.output.glob(".*.tmp"))
    console = capsys.readouterr().out
    assert "cause=OSError" in console and "Public benchmark evidence export failed" in console
    assert "secret" not in console and "COMPLETE" not in console
