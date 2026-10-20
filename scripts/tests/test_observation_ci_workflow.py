"""Keep the explicit observation experiment synthetic-only and fail closed."""

from pathlib import Path
import hashlib
import json
import re

import yaml
from scripts.tests.historical_observation_metadata import historical_bytes, historical_source_path, current_mismatches


ROOT = Path(__file__).resolve().parents[2]

def test_train_runtime_diagnostic_is_manual_only_fixed_and_cannot_dispatch_fitting():
    text = (ROOT / ".github/workflows/observation-train-runtime-diagnostic.yml").read_text()
    workflow = yaml.load(text, Loader=yaml.BaseLoader)
    assert set(workflow["on"]) == {"workflow_call"}
    job = workflow["jobs"]["train-runtime-diagnostic"]
    assert job["if"] == "github.event_name == 'workflow_dispatch'"
    assert job["timeout-minutes"] == "15"
    runs = "\n".join(step.get("run", "") for step in job["steps"])
    assert "timeout 360s" in runs
    assert "v022ObservationTrainRuntimeDiagnostic" in runs
    assert "v022ObservationIdentify" not in runs
    assert "v022ObservationRendererDiagnostics" not in runs
    assert "FIT_SELECT" not in runs
    assert "sealed-observation" not in runs
    assert "sha256sum --check --status" in runs
    assert "git rev-parse HEAD" in runs
    assert "compileObservationIdentifyJava" in runs


def test_train_runtime_upload_is_a_fixed_aggregate_allowlist_without_scratch_globs():
    text = (ROOT / ".github/workflows/observation-train-runtime-diagnostic.yml").read_text()
    workflow = yaml.load(text, Loader=yaml.BaseLoader)
    job = workflow["jobs"]["train-runtime-diagnostic"]
    upload = next(step for step in job["steps"] if step.get("uses", "").startswith("actions/upload-artifact@"))
    assert upload["if"] == "always()"
    assert upload["with"]["path"].splitlines() == [
        "${{ runner.temp }}/josm-train-runtime/aggregate/binding.json",
        "${{ runner.temp }}/josm-train-runtime/aggregate/progress.jsonl",
        "${{ runner.temp }}/josm-train-runtime/aggregate/status.json",
    ]


def test_archived_train_runtime_freeze_and_manual_workflow_digest_stay_immutable():
    protocol_path = ROOT / "superpowers/docs/plans/2026-10-18-b-observation-runtime-diagnostic-freeze.json"
    protocol_bytes = protocol_path.read_bytes()
    protocol = json.loads(protocol_bytes)
    assert hashlib.sha256(protocol_bytes).hexdigest() == "3c9e872713736d1f475e3c9983bdf5be01e71dc88e89f24133a8cff241fd171d"
    assert protocol["sourceSha256"] == "477d160264ce7fb191f2b1f392b2d597fe85f5a94849c78335e5e4e176bbf8cd"
    old = json.loads((ROOT / "superpowers/docs/plans/2026-10-18-b-observation-reviewed-source.json").read_bytes())
    current = protocol["sourceMembers"]
    assert set(current) == set(old["sourceMembers"])
    assert len(current) == 27
    for member, digest in current.items():
        historical_source_path(member)  # Unique authenticated historical ownership.
        if member != "service/tracing/probabilistic/BObservationIdentificationTool.java":
            assert digest == old["sourceMembers"][member]
    actual = {}
    for member in current:
        actual[member] = hashlib.sha256(historical_bytes(historical_source_path(member))).hexdigest()
    assert {member for member in current if current[member] != actual[member]} == {
        "service/tracing/probabilistic/BObservationIdentificationTool.java",
        "service/tracing/probabilistic/ProbabilisticInference.java",
    }, "the authentic bde context already had these two changes; the old verifier stays pinned"
    assert current_mismatches({historical_source_path(p): h for p, h in current.items()}), \
        "current source must remain ineligible for the old runtime authority"
    assert hashlib.sha256(json.dumps(current, sort_keys=True, separators=(",", ":")).encode()).hexdigest() == protocol["sourceSha256"]
    assert len(protocol["diagnosticMembers"]) == 2
    for path, digest in protocol["diagnosticMembers"].items():
        assert hashlib.sha256(historical_bytes(path)).hexdigest() == digest
    workflow = (ROOT / ".github/workflows/observation-train-runtime-diagnostic.yml").read_text()
    assert hashlib.sha256(protocol_bytes).hexdigest() in workflow
    assert "REVIEW_PENDING" not in workflow


def test_registered_build_routes_train_runtime_exclusively_including_both_flags():
    build = yaml.load((ROOT / ".github/workflows/build.yml").read_text(), Loader=yaml.BaseLoader)
    names = ("observation-identification", "observation-public-diagnostics", "observation-train-runtime")

    def enabled(expression, event, inputs):
        if expression == "false":
            return False
        for term in expression.split(" && "):
            left, operator, right = term.split()
            actual = event if left == "github.event_name" else inputs.get(left.removeprefix("inputs."), False)
            expected = right == "true" if right in ("true", "false") else right.strip("'")
            if (actual == expected) != (operator == "=="):
                return False
        return True

    for event, inputs, expected in (
        ("workflow_dispatch", {"observation_train_runtime": True}, ["observation-train-runtime"]),
        ("workflow_dispatch", {"observation_train_runtime": True, "observation_diagnostics": True}, ["observation-train-runtime"]),
        ("workflow_dispatch", {}, ["observation-identification"]),
        ("workflow_dispatch", {"observation_train_runtime": False, "observation_diagnostics": False}, ["observation-identification"]),
        ("workflow_dispatch", {"observation_diagnostics": True}, ["observation-public-diagnostics"]),
        ("push", {"observation_train_runtime": True}, []),
        ("pull_request", {"observation_train_runtime": True}, []),
    ):
        actual = [name for name in names if enabled(build["jobs"].get(name, {}).get("if", "false"), event, inputs)]
        assert actual == expected
    option = build["on"]["workflow_dispatch"]["inputs"]["observation_train_runtime"]
    assert option == {"description": "Run only the fixed TRAIN runtime diagnostic", "type": "boolean", "default": "false"}
    assert build["jobs"]["observation-train-runtime"]["uses"] == "./.github/workflows/observation-train-runtime-diagnostic.yml"


def _workflow():
    return yaml.load(
        (ROOT / ".github/workflows/observation-identification.yml").read_text(),
        Loader=yaml.BaseLoader,
    )


def test_fitting_checks_external_reviewed_registry_before_compilation_and_catalog():
    steps = _workflow()["jobs"]["observation-identification"]["steps"]
    checkout = next(s for s in steps if s.get("uses") == "actions/checkout@v4")
    assert checkout.get("with", {}).get("ref") == "${{ github.sha }}"
    verify = next((s for s in steps if s.get("name") == "Verify reviewed fitting inputs"), None)
    assert verify is not None, "Fitting requires the externally reviewed source registry"
    assert "2026-10-18-b-observation-reviewed-source.json" in verify["run"]
    assert "sha256sum --check --status" in verify["run"]
    assert steps.index(verify) < next(i for i, s in enumerate(steps)
                                    if "compileObservationIdentifyJava" in s.get("run", ""))
    compile_step = next(s for s in steps if "compileObservationIdentifyJava" in s.get("run", ""))
    assert "observationIdentifyClasses" in compile_step["run"], "Standalone runtime needs the public index"


def test_manual_fixed_experiment_has_no_inputs_or_release_permissions():
    workflow = _workflow()
    assert set(workflow["on"]) == {"workflow_dispatch", "workflow_call"}
    assert workflow["on"]["workflow_dispatch"] in (None, "")
    assert workflow["permissions"] == {"contents": "read"}
    job = workflow["jobs"]["observation-identification"]
    assert "runner.temp" not in str(job.get("env", {}))
    output = next(s for s in job["steps"] if s.get("name") == "Set synthetic output location")
    assert 'OBSERVATION_OUTPUT=$RUNNER_TEMP/josm-observation-identification' in output["run"]
    assert '"$GITHUB_ENV"' in output["run"]
    assert job["timeout-minutes"] == "60"
    assert job.get("continue-on-error") in (None, "false")
    assert all(s.get("continue-on-error") in (None, "false") for s in job["steps"])
    text = "\n".join(s.get("run", "") for s in job["steps"])
    assert "compileObservationIdentifyJava" in text
    assert "testClasses" not in text
    assert "-PobservationPhase=CATALOG" in text
    assert "-PobservationPhase=FIT_SELECT" in text
    assert "-PobservationBinding=" in text
    assert "timeout 3500s" in text
    manifest = ROOT / "superpowers/docs/plans/2026-10-18-b-observation-feasibility-manifest.json"
    assert hashlib.sha256(manifest.read_bytes()).hexdigest() in text
    registry = ROOT / "superpowers/docs/plans/2026-10-18-b-observation-reviewed-source.json"
    assert hashlib.sha256(registry.read_bytes()).hexdigest() in text
    assert json.loads(registry.read_text())["sourceSha256"] in text
    assert "HOLDOUT" not in text
    assert "gh release" not in text
    assert "test-data" not in text


def test_only_synthetic_receipts_are_uploaded_and_failure_is_not_hidden():
    steps = _workflow()["jobs"]["observation-identification"]["steps"]
    uploads = [s for s in steps if s.get("uses") == "actions/upload-artifact@v4"]
    assert len(uploads) == 1
    upload = uploads[0]
    assert upload["if"] == "always()"
    assert upload["with"]["path"].splitlines() == [
        "${{ runner.temp }}/josm-observation-identification/catalog/*.json",
        "${{ runner.temp }}/josm-observation-identification/fit-select/*.json",
    ]
    assert upload["with"]["if-no-files-found"] == "ignore"
    assert upload["with"]["retention-days"] == "5"
    assert not any(".jar" in str(s) or "release" in s.get("uses", "") for s in uploads)
    run = next(s for s in steps if s.get("id") == "fit")
    assert run.get("if") in (None, "success()")
    assert "|| true" not in run["run"]
    require = next(s for s in steps if s.get("name") == "Require a complete frozen candidate receipt")
    assert require.get("if") in (None, "success()")
    assert "FROZEN_PENDING_REGRESSION" in require["run"]
    assert "assert" in require["run"]


def test_source_binding_covers_both_geometry_admission_implementations():
    package = Path("org/openstreetmap/josm/plugins/wayheatmaptracer")
    receipt = ROOT / "src/test/java" / package / "service/tracing/probabilistic/BIdentificationReceipt.java"
    declaration = receipt.read_text().split("SOURCE_FILES = List.of(", 1)[1].split(");", 1)[0]
    members = re.findall(r'"([^\"]+\.java)"', declaration)
    assert len(members) == len(set(members)) == 27
    assert "service/tracing/probabilistic/TransitionAdmissionMemo.java" in members
    assert "service/tracing/probabilistic/IndexedGeometryAdmissions.java" in members
    inventory = {}
    for member in members:
        locations = [ROOT / source / package / member for source in ("src/main/java", "src/test/java")]
        locations = [path for path in locations if path.is_file()]
        assert len(locations) == 1, member
        assert not locations[0].is_symlink()
        inventory[member] = hashlib.sha256(locations[0].read_bytes()).hexdigest()
    canonical = json.dumps(inventory, sort_keys=True, separators=(",", ":")).encode()
    expected = hashlib.sha256(canonical).hexdigest()
    runs = "\n".join(step.get("run", "") for step in _workflow()["jobs"]["observation-identification"]["steps"])
    old = json.loads((ROOT / "superpowers/docs/plans/2026-10-18-b-observation-reviewed-source.json").read_bytes())
    assert f'"sourceHash":"{old["sourceSha256"]}"' in runs
    assert expected != old["sourceSha256"], "old FIT authority must not be rebound to current source"


def test_registered_build_workflow_can_call_experiment_without_building_a_jar():
    build = yaml.load((ROOT / ".github/workflows/build.yml").read_text(), Loader=yaml.BaseLoader)
    assert "workflow_dispatch" in build["on"]
    call = build["jobs"]["observation-identification"]
    assert call["if"] == "github.event_name == 'workflow_dispatch' && inputs.observation_diagnostics != true && inputs.observation_train_runtime != true && inputs.observation_protocol != 'optimized-train-runtime' && inputs.observation_protocol != 'prospective-fit' && inputs.observation_protocol != 'single-train-geometry'"
    assert call["uses"] == "./.github/workflows/observation-identification.yml"
    assert build["jobs"]["public-validation"]["if"] == "github.event_name != 'workflow_dispatch'"


def test_renderer_diagnostic_dispatch_is_bounded_and_synthetic_only():
    build = yaml.load((ROOT / ".github/workflows/build.yml").read_text(), Loader=yaml.BaseLoader)
    option = build["on"]["workflow_dispatch"]["inputs"]["observation_diagnostics"]
    assert option["type"] == "boolean"
    assert option["default"] == "false"
    job = build["jobs"]["observation-public-diagnostics"]
    assert job["if"] == "github.event_name == 'workflow_dispatch' && inputs.observation_diagnostics == true && inputs.observation_train_runtime != true && inputs.observation_protocol != 'optimized-train-runtime' && inputs.observation_protocol != 'prospective-fit' && inputs.observation_protocol != 'single-train-geometry'"
    assert job["timeout-minutes"] == "15"
    assert build["permissions"] == {"contents": "read"}
    commands = "\n".join(step.get("run", "") for step in job["steps"])
    assert "v022ObservationRendererDiagnostics" in commands
    assert "timeout 600s" in commands
    assert "FIT_SELECT" not in commands
    assert "HOLDOUT" not in commands
    assert "test-data" not in commands
    uploads = [step for step in job["steps"] if step.get("uses") == "actions/upload-artifact@v4"]
    assert len(uploads) == 1
    assert uploads[0]["if"] == "always()"
    assert uploads[0]["with"]["path"].splitlines() == [
        "build/reports/public-observation-renderer/diagnostic.log",
        "build/reports/public-observation-renderer/receipt/binding.json",
        "build/reports/public-observation-renderer/receipt/bound-geometry.csv",
        "build/reports/public-observation-renderer/receipt/objective.json",
        "build/reports/public-observation-renderer/receipt/objective-costs.csv",
        "build/reports/public-observation-renderer/receipt/objective-evidence.jsonl",
    ]
    assert all(".jar" not in str(step) and "release" not in step.get("uses", "") for step in uploads)
