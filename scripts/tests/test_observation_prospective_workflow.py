"""Purpose-specific dispatch and external pin checks; never runs a renderer or FIT."""
from pathlib import Path
import hashlib
import itertools
import json
import os
import re
import subprocess
import copy

import yaml
import pytest

ROOT = Path(__file__).resolve().parents[2]
PINS = Path("superpowers/docs/plans/2026-10-18-b-observation-prospective-pins.json")
NEW = {
    "optimized-train-runtime": ("observation-train-runtime-optimized", ".github/workflows/observation-train-runtime-optimized.yml"),
    "prospective-fit": ("observation-prospective-fit", ".github/workflows/observation-prospective-fit.yml"),
}


def _load(relative):
    return yaml.load((ROOT / relative).read_text(), Loader=yaml.BaseLoader)


def _enabled(expression, event, inputs):
    for term in expression.split(" && "):
        left, operator, right = term.split()
        actual = event if left == "github.event_name" else inputs.get(left.removeprefix("inputs."), False)
        expected = right == "true" if right in ("true", "false") else right.strip("'")
        if (actual == expected) != (operator == "=="):
            return False
    return True


def test_explicit_choice_keeps_existing_default_and_new_protocols_default_off():
    option = _load(".github/workflows/build.yml")["on"]["workflow_dispatch"]["inputs"].get("observation_protocol")
    assert option == {
        "description": "Choose a separate prospective protocol only after controller approval",
        "type": "choice", "default": "legacy", "options": ["legacy", "optimized-train-runtime", "prospective-fit"],
    }


def test_all_choice_and_old_flag_combinations_are_exclusive_and_push_cannot_run_them():
    build = _load(".github/workflows/build.yml")
    names = ["observation-identification", "observation-public-diagnostics", "observation-train-runtime"]
    names += [entry[0] for entry in NEW.values()]
    for event in ("workflow_dispatch", "push", "pull_request"):
        for choice, runtime, renderer in itertools.product(("legacy", *NEW), (False, True), (False, True)):
            inputs = {"observation_protocol": choice, "observation_train_runtime": runtime, "observation_diagnostics": renderer}
            actual = [name for name in names if name in build["jobs"] and _enabled(build["jobs"][name]["if"], event, inputs)]
            expected = [] if event != "workflow_dispatch" else [NEW[choice][0]] if choice in NEW else [
                "observation-train-runtime" if runtime else "observation-public-diagnostics" if renderer else "observation-identification"
            ]
            assert actual == expected, (event, inputs)


def _expected_pins(workflow):
    value = workflow["jobs"][next(iter(workflow["jobs"]))]["env"]["PROSPECTIVE_PINS_SHA256"]
    assert re.fullmatch(r"[0-9a-f]{64}", value), "only a final literal candidate digest, never a runtime generated hash"
    return value


def test_both_routes_check_external_pin_and_approval_before_compilation():
    for choice, (job_name, path) in NEW.items():
        workflow = _load(path)
        assert set(workflow["on"]) == {"workflow_call"}
        assert workflow["permissions"] == {"contents": "read"}
        job = workflow["jobs"][job_name]
        assert job["if"] == "github.event_name == 'workflow_dispatch'"
        expected = _expected_pins(workflow)
        assert expected == hashlib.sha256((ROOT / PINS).read_bytes()).hexdigest()
        steps = job["steps"]
        check = next(i for i, step in enumerate(steps) if "sha256sum --check --status" in step.get("run", ""))
        compile_at = next(i for i, step in enumerate(steps) if "compileObservationIdentifyJava" in step.get("run", ""))
        assert check < compile_at
        assert "APPROVED_AFTER_INDEPENDENT_REVIEW_AND_CONTROLLER_RULING" in steps[check]["run"]
        runs = "\n".join(step.get("run", "") for step in steps)
        assert '"$PROSPECTIVE_PINS_SHA256"' in runs
        assert "git rev-parse HEAD" in runs
        assert "gh workflow" not in runs and "generate-" not in runs
        assert all(step.get("continue-on-error") in (None, "false") for step in steps)
        if choice == "optimized-train-runtime":
            assert job["timeout-minutes"] == "15" and "timeout 360s" in runs
            assert "v022ObservationOptimizedTrainRuntimeDiagnostic" in runs
            assert "FIT_SELECT" not in runs
            upload = next(s for s in steps if s.get("uses", "").startswith("actions/upload-artifact@"))
            assert upload["with"]["path"].splitlines() == [
                "${{ runner.temp }}/josm-train-runtime-optimized/aggregate/binding.json",
                "${{ runner.temp }}/josm-train-runtime-optimized/aggregate/progress.jsonl",
                "${{ runner.temp }}/josm-train-runtime-optimized/aggregate/status.json",
            ]
        else:
            assert job["timeout-minutes"] == "60" and "timeout 3500s" in runs
            assert "v022ObservationProspectiveIdentify" in runs
            assert "-PprospectivePhase=CATALOG" in runs and "-PprospectivePhase=FIT_SELECT" in runs


def test_joint_source_registry_pin_tampering_fails_the_actual_precompile_hash_line(tmp_path):
    workflow = _load(NEW["prospective-fit"][1])
    expected = _expected_pins(workflow)
    steps = workflow["jobs"][NEW["prospective-fit"][0]]["steps"]
    verify = next(s["run"] for s in steps if "sha256sum --check --status" in s.get("run", ""))
    line = next(line for line in verify.splitlines() if "sha256sum --check --status" in line)
    target = tmp_path / PINS
    target.parent.mkdir(parents=True)
    target.write_bytes((ROOT / PINS).read_bytes())
    env = {**os.environ, "PROSPECTIVE_PINS_SHA256": expected}
    assert subprocess.run(["sh", "-c", line], cwd=tmp_path, env=env, capture_output=True).returncode == 0
    pins = json.loads(target.read_bytes())
    registry_path = Path("superpowers/docs/plans/2026-10-18-b-observation-prospective-source.json")
    registry = json.loads((ROOT / registry_path).read_bytes())
    package = Path("org/openstreetmap/josm/plugins/wayheatmaptracer")
    sources = {}
    for member in registry["core"]["sourceMembers"]:
        original = next(ROOT / parent / package / member for parent in ("src/main/java", "src/test/java")
                        if (ROOT / parent / package / member).is_file())
        copied = tmp_path / original.relative_to(ROOT)
        copied.parent.mkdir(parents=True, exist_ok=True)
        copied.write_bytes(original.read_bytes())
        sources[member] = copied
    for relative in registry["protocolMembers"]:
        copied = tmp_path / relative
        copied.parent.mkdir(parents=True, exist_ok=True)
        copied.write_bytes((ROOT / relative).read_bytes())
    member = next(iter(registry["core"]["sourceMembers"]))
    sources[member].write_bytes(sources[member].read_bytes() + b"\n// changed\n")
    registry["core"]["sourceMembers"][member] = hashlib.sha256(sources[member].read_bytes()).hexdigest()
    assert all(hashlib.sha256(sources[path].read_bytes()).hexdigest() == value
               for path, value in registry["core"]["sourceMembers"].items())
    canonical = lambda value: json.dumps(value, sort_keys=True, separators=(",", ":")).encode()
    registry["core"]["sourceSha256"] = hashlib.sha256(canonical(registry["core"]["sourceMembers"])).hexdigest()
    registry["sourceSha256"] = hashlib.sha256(canonical({"coreSourceSha256": registry["core"]["sourceSha256"],
                                                        "protocolMembers": registry["protocolMembers"]})).hexdigest()
    registry_bytes = canonical(registry) + b"\n"
    (tmp_path / registry_path).write_bytes(registry_bytes)
    pins["sourceRegistrySha256"] = hashlib.sha256(registry_bytes).hexdigest()
    target.write_bytes(canonical(pins) + b"\n")
    assert hashlib.sha256(target.read_bytes()).hexdigest() != expected
    assert subprocess.run(["sh", "-c", line], cwd=tmp_path, env=env, capture_output=True).returncode != 0


def test_candidate_registry_binds_actual_core_and_all_five_runtime_authority_helpers():
    registry = json.loads((ROOT / "superpowers/docs/plans/2026-10-18-b-observation-prospective-source.json").read_bytes())
    canonical = lambda value: json.dumps(value, sort_keys=True, separators=(",", ":")).encode()
    package = "org/openstreetmap/josm/plugins/wayheatmaptracer"
    core = registry["core"]["sourceMembers"]
    assert len(core) == 27
    for member, expected in core.items():
        paths = [ROOT / parent / package / member for parent in ("src/main/java", "src/test/java")]
        actual = [path for path in paths if path.is_file()]
        assert len(actual) == 1
        assert hashlib.sha256(actual[0].read_bytes()).hexdigest() == expected
    helpers = registry["protocolMembers"]
    names = {"BProspectiveIdentificationManifest.java", "BObservationOptimizedTrainRuntimeDiagnosticTool.java",
             "BProspectiveObservationIdentificationTool.java", "BObservationTrainRuntimeDiagnosticTool.java",
             "BObservationRuntimeTelemetry.java"}
    assert {Path(path).name for path in helpers} == names and len(helpers) == 5
    for path, expected in helpers.items():
        assert hashlib.sha256((ROOT / path).read_bytes()).hexdigest() == expected
    assert registry["core"]["sourceSha256"] == hashlib.sha256(canonical(core)).hexdigest()
    assert registry["sourceSha256"] == hashlib.sha256(canonical({
        "coreSourceSha256": registry["core"]["sourceSha256"], "protocolMembers": helpers,
    })).hexdigest()


def test_precompile_pending_refusal_survives_python_optimization_with_controlled_fixtures(tmp_path):
    target = tmp_path / PINS
    target.parent.mkdir(parents=True)
    for _, path in NEW.values():
        workflow = _load(path)
        job = workflow["jobs"][next(iter(workflow["jobs"]))]
        verify = next(s["run"] for s in job["steps"] if "sha256sum --check --status" in s.get("run", ""))
        body = verify.split("python3 - <<'PY'\n", 1)[1].split("\nPY", 1)[0]
        for approval, expected_success in (("PENDING_INDEPENDENT_APPROVAL", False),
                ("APPROVED_AFTER_INDEPENDENT_REVIEW_AND_CONTROLLER_RULING", True)):
            target.write_text(json.dumps({"approval": approval}))
            result = subprocess.run(["python3", "-O", "-c", body], cwd=tmp_path, capture_output=True)
            assert (result.returncode == 0) == expected_success


def _complete_candidate_fixture():
    header = {"schema": 1, "phase": "FIT_SELECT", "scope": "FULL_PUBLIC_PROTOCOL",
              "protocolPurpose": "PROSPECTIVE_FIT", "controllerApproval": "APPROVED_AFTER_INDEPENDENT_REVIEW_AND_CONTROLLER_RULING",
              "featureVersion": "b-identification-facts-3", "rendererCancellation": "checkpoints-before-and-after-bounded-render-not-mid-pixel",
              "selectionProtocol": "train-feasible-fixed-prefix-top4-score-order-v3",
              "trainingControlOrder": "fixed-v0-right-v4-right-then-selector-first-pitch-desc-variant-contrast-side-v1",
              "skippedErrors": "UNVISITED_UNKNOWN_NOT_PROVED_ABSENT",
              "launchContext": {"repository": "fixture/repository", "runId": "1", "commit": "a" * 40,
                                "workflowRef": "fixture/repository/.github/workflows/build.yml@refs/heads/fixture", "runAttempt": "1"}}
    for field in ("manifestHash", "sourceHash", "catalogHash", "sourceRegistryHash", "executionPinsHash",
                  "baseFamilyManifestHash", "acceptanceConventionsHash"):
        header[field] = "a" * 64
    header["status"] = "FROZEN_PENDING_REGRESSION"
    parameters = {"G": 1.0, "lambda": 1.0, "B": 0.0}
    summary = {**header, "frozenArtifact": "frozen-candidate.json", "trainCoverage": "ALL_ORIGINAL_TRAIN",
               "selectionAuthority": "FULL_PUBLIC_PROTOCOL", "initialTrainScenes": 112, "shortlist": [parameters],
               "selectConfigurations": [{"parameters": parameters, "meanLogLoss": 0.2, "rankableFinite": True}],
               "baselineSelect": {"meanLogLoss": 0.3}, "acceptanceReports": ["fixture-check.json"]}
    candidate = {**header, "parameters": parameters, "modelHash": "b" * 64, "receiptSetHash": "c" * 64,
                 "selectScore": 0.2, "baselineSelect": 0.3, "selectCases": [f"FIXTURE_ONLY_SELECT_{i}" for i in range(112)],
                 "acceptanceReports": ["fixture-check.json"], "holdoutAuthority": False,
                 "regressionPolicy": "once-after-this-freeze-failure-rejects-no-reselection"}
    return summary, candidate, header


@pytest.mark.parametrize("optimized", [False, True])
def test_actual_final_receipt_gate_refuses_invalid_candidates_normally_and_under_optimization(tmp_path, optimized):
    workflow = _load(NEW["prospective-fit"][1])
    job = workflow["jobs"][NEW["prospective-fit"][0]]
    step = next(s for s in job["steps"] if s.get("name") == "Require a complete candidate receipt")
    body = step["run"].split("python3 - <<'PY'\n", 1)[1].split("\nPY", 1)[0]
    summary, candidate, header = _complete_candidate_fixture()
    cases = [("valid", summary, candidate, False, True)]
    for status in ("FAMILY_REJECTED_NO_FEASIBLE_TRAIN_CANDIDATE", "FIT_SCORED_PENDING_GEOMETRY"):
        cases.append((status, {**summary, "status": status}, candidate, False, False))
    for field in ("protocolPurpose", "controllerApproval", "trainCoverage", "selectionAuthority", "frozenArtifact"):
        cases.append(("summary-" + field, {**summary, field: "WRONG"}, candidate, False, False))
    for field in header:
        bad = copy.deepcopy(candidate)
        bad[field] = "WRONG"
        cases.append(("candidate-binding-" + field, summary, bad, False, False))
    for field in ("parameters", "modelHash", "receiptSetHash", "selectScore", "baselineSelect", "selectCases",
                  "acceptanceReports", "regressionPolicy", "holdoutAuthority"):
        bad = copy.deepcopy(candidate)
        del bad[field]
        cases.append(("candidate-missing-" + field, summary, bad, False, False))
    cases += [("missing-candidate", summary, None, False, False), ("malformed-candidate", summary, "{", False, False),
              ("candidate-object-required", summary, [], False, False), ("failure", summary, candidate, True, False),
              ("missing-summary", None, candidate, False, False), ("malformed-summary", "{", candidate, False, False)]
    for name, summary_value, candidate_value, failure, success in cases:
        root = tmp_path / (str(optimized) + "-" + name) / "josm-observation-prospective" / "fit-select"
        root.mkdir(parents=True)
        for filename, value in (("summary.json", summary_value), ("frozen-candidate.json", candidate_value)):
            if value is not None:
                (root / filename).write_text(value if isinstance(value, str) else json.dumps(value))
        if failure:
            (root / "failure.json").write_text("{}")
        command = ["python3", *(["-O"] if optimized else []), "-c", body]
        result = subprocess.run(command, env={**os.environ, "RUNNER_TEMP": str(root.parents[1])}, capture_output=True)
        assert (result.returncode == 0) == success, (optimized, name, result.stderr.decode())
