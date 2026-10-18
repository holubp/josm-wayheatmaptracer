"""Keep the explicit observation experiment synthetic-only and fail closed."""

from pathlib import Path
import hashlib
import json
import re

import yaml


ROOT = Path(__file__).resolve().parents[2]


def _workflow():
    return yaml.load(
        (ROOT / ".github/workflows/observation-identification.yml").read_text(),
        Loader=yaml.BaseLoader,
    )


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
    assert "6e44cdc2b75d6d960861bea90f0916a809ff10145341c7431dc52fcdedc16003" in text
    assert "f71a908b223b43ce49c3e88abae0bd7dad0ad5aa91ac3fe1626e3bacee307917" in text
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
    assert f'"sourceHash":"{expected}"' in runs


def test_registered_build_workflow_can_call_experiment_without_building_a_jar():
    build = yaml.load((ROOT / ".github/workflows/build.yml").read_text(), Loader=yaml.BaseLoader)
    assert "workflow_dispatch" in build["on"]
    call = build["jobs"]["observation-identification"]
    assert call["if"] == "github.event_name == 'workflow_dispatch' && inputs.observation_diagnostics != true"
    assert call["uses"] == "./.github/workflows/observation-identification.yml"
    assert build["jobs"]["public-validation"]["if"] == "github.event_name != 'workflow_dispatch'"


def test_renderer_diagnostic_dispatch_is_bounded_and_synthetic_only():
    build = yaml.load((ROOT / ".github/workflows/build.yml").read_text(), Loader=yaml.BaseLoader)
    option = build["on"]["workflow_dispatch"]["inputs"]["observation_diagnostics"]
    assert option["type"] == "boolean"
    assert option["default"] == "false"
    job = build["jobs"]["observation-public-diagnostics"]
    assert job["if"] == "github.event_name == 'workflow_dispatch' && inputs.observation_diagnostics == true"
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
    ]
    assert all(".jar" not in str(step) and "release" not in step.get("uses", "") for step in uploads)
