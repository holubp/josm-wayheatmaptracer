"""Public-only ordinary Legacy benchmark contracts; never run a timed kernel."""

import importlib.util
import hashlib
import io
import json
from pathlib import Path
import subprocess
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
