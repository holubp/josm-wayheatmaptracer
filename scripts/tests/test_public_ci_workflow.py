"""Semantic checks for the public-only GitHub Actions artifact boundary."""

from __future__ import annotations

from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "build.yml"
PUBLIC_OUTPUT = "$RUNNER_TEMP/josm-public-validation"
ARTIFACT_OUTPUT = "${{ runner.temp }}/josm-public-validation"


def _workflow() -> dict[str, object]:
    # BaseLoader keeps the GitHub Actions `on` key as a string.
    loaded = yaml.load(WORKFLOW.read_text(encoding="utf-8"), Loader=yaml.BaseLoader)
    assert isinstance(loaded, dict)
    return loaded


def test_public_artifact_upload_is_exactly_allowlisted_and_gated() -> None:
    workflow = _workflow()
    assert workflow["permissions"] == {"contents": "read"}
    jobs = workflow["jobs"]
    assert isinstance(jobs, dict)
    public = jobs["public-validation"]
    assert isinstance(public, dict)
    assert public.get("continue-on-error") in (None, "false")
    steps = public["steps"]
    assert isinstance(steps, list)
    assert all(step.get("continue-on-error") in (None, "false") for step in steps)
    python = next(step for step in steps if step.get("uses") == "actions/setup-python@v5")
    assert python["with"]["python-version"] == "3.11"
    install = next(step for step in steps if step.get("name") == "Install public validation dependencies")
    assert install["run"] == 'python3 -m pip install "pytest==9.1.1" "PyYAML==6.0.2"'

    runner = next(step for step in steps if step.get("id") == "public-validation")
    assert runner["run"] == (
        "python3 scripts/run-validation.py --profile public "
        f"--output \"{PUBLIC_OUTPUT}\""
    )

    uploads = [step for step in steps if step.get("uses") == "actions/upload-artifact@v4"]
    assert len(uploads) == 2
    report_upload, jar_upload = uploads
    assert report_upload["if"] == "always()"
    assert report_upload["with"]["path"].splitlines() == [
        f"{ARTIFACT_OUTPUT}/reports/java/junit-xml/",
        f"{ARTIFACT_OUTPUT}/reports/java/html/",
        f"{ARTIFACT_OUTPUT}/summary.json",
        f"{ARTIFACT_OUTPUT}/status.json",
    ]
    assert report_upload["with"]["if-no-files-found"] == "ignore"

    assert jar_upload["if"] == "steps.public-validation.outcome == 'success'"
    assert jar_upload["with"]["path"] == f"{ARTIFACT_OUTPUT}/artifacts/wayheatmaptracer.jar"
    assert jar_upload["with"]["if-no-files-found"] == "error"


def test_public_gui_smoke_is_xvfb_gated_and_failure_remains_a_job_failure() -> None:
    workflow = _workflow()
    jobs = workflow["jobs"]
    assert isinstance(jobs, dict)
    public = jobs["public-validation"]
    smoke = jobs["public-gui-smoke"]
    assert isinstance(public, dict) and isinstance(smoke, dict)
    assert smoke.get("continue-on-error") in (None, "false")
    assert smoke["runs-on"] == "ubuntu-24.04"
    assert smoke["needs"] == "public-validation"
    assert smoke["timeout-minutes"] == "15"
    steps = smoke["steps"]
    assert isinstance(steps, list)
    assert all(step.get("continue-on-error") in (None, "false") for step in steps)
    assert any(step.get("run") == "command -v xvfb-run" for step in steps)
    smoke_run = next(step for step in steps if "v022PublicGuiSmoke" in step.get("run", ""))
    assert smoke_run["run"] == (
        "timeout 600s xvfb-run -a -s '-screen 0 1920x1080x24' "
        "sh gradlew v022PublicGuiSmoke --console=plain"
    )
    require_summary = next(step for step in steps if step.get("name") == "Require public GUI smoke summary after a passing run")
    assert require_summary["if"] == "success()"
    assert require_summary["run"] == "test -s build/reports/public-gui-smoke/summary.json"
    report_upload = next(step for step in steps if step.get("uses") == "actions/upload-artifact@v4")
    assert report_upload["if"] == "always()"
    assert report_upload["with"]["path"] == "build/reports/public-gui-smoke/summary.json"
    assert report_upload["with"]["if-no-files-found"] == "ignore"
