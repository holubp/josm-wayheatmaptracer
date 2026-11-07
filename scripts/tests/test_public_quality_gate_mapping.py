"""Keep public performance retention distinct from absolute model-quality debt."""

from __future__ import annotations

from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[2]
BUILD_WORKFLOW = ROOT / ".github" / "workflows" / "build.yml"
ABSOLUTE_WORKFLOW = ROOT / ".github" / "workflows" / "wide-absolute-quality-analysis.yml"
ABSOLUTE_BRANCH = "codex/v022-absolute-quality-analysis-20261107"


def _workflow(path: Path) -> dict[str, object] | None:
    if not path.is_file():
        return None
    loaded = yaml.load(path.read_text(encoding="utf-8"), Loader=yaml.BaseLoader)
    assert isinstance(loaded, dict)
    return loaded


def test_public_retention_is_the_unconditional_push_and_tag_quality_check() -> None:
    workflow = _workflow(BUILD_WORKFLOW)
    assert workflow is not None
    triggers = workflow["on"]
    assert triggers["push"]["branches"] == ["**"]
    assert triggers["push"]["tags"] == ["v*"]

    jobs = workflow["jobs"]
    assert "public-wide-compact-retention" in jobs
    assert "public-wide-compact-quality" not in jobs
    job = jobs["public-wide-compact-retention"]
    assert job.get("if") is None
    assert job.get("continue-on-error") in (None, "false")
    assert all(step.get("continue-on-error") in (None, "false") for step in job["steps"])
    assert job["timeout-minutes"] == "90"
    run = next(step for step in job["steps"] if "v022WideCompactRetention" in step.get("run", ""))
    assert run["run"] == "timeout 4800s sh gradlew --no-daemon --max-workers=1 v022WideCompactRetention --console=plain"

    host = next(step for step in job["steps"] if step.get("name") == "Prepare pinned JOSM 19555 host")
    java = next(step for step in job["steps"] if step.get("uses") == "actions/setup-java@v4")
    assert java["with"]["java-version"] == "17"
    assert "scripts/prepare-ci-josm19555.py --output-dir" in host["run"]
    assert "ORG_GRADLE_PROJECT_josm19555HostJar=" in host["run"]

    upload = next(step for step in job["steps"] if step.get("uses") == "actions/upload-artifact@v4")
    assert upload["if"] == "always()"
    assert upload["with"]["name"] == "public-wide-compact-performance-retention"
    assert upload["with"]["path"].splitlines() == [
        "build/reports/wide-compact-retention/",
        "src/test/resources/public-wide-compact-v1.properties",
    ]
    assert upload["with"]["if-no-files-found"] == "error"


def test_absolute_quality_remains_a_separate_strict_public_analysis() -> None:
    workflow = _workflow(ABSOLUTE_WORKFLOW)
    assert workflow is not None
    assert set(workflow["on"]) == {"workflow_dispatch", "push"}
    assert "workflow_dispatch" in workflow["on"]
    assert workflow["on"]["push"]["branches"] == [ABSOLUTE_BRANCH]
    assert workflow["permissions"] == {"contents": "read"}
    job = workflow["jobs"]["public-wide-absolute-quality-analysis"]
    assert job.get("continue-on-error") in (None, "false")
    assert all(step.get("continue-on-error") in (None, "false") for step in job["steps"])

    host = next(step for step in job["steps"] if step.get("name") == "Prepare pinned JOSM 19555 host")
    java = next(step for step in job["steps"] if step.get("uses") == "actions/setup-java@v4")
    run = next(step for step in job["steps"] if "v022WideCompactQuality" in step.get("run", ""))
    upload = next(step for step in job["steps"] if step.get("uses") == "actions/upload-artifact@v4")
    assert java["with"]["java-version"] == "17"
    assert "scripts/prepare-ci-josm19555.py --output-dir" in host["run"]
    assert run["run"] == "timeout 4800s sh gradlew --no-daemon --max-workers=1 v022WideCompactQuality --console=plain"
    assert upload["if"] == "always()"
    assert upload["with"]["name"] == "public-wide-absolute-quality-analysis"
    assert upload["with"]["path"].splitlines() == [
        "build/reports/wide-compact-quality/",
        "src/test/resources/public-wide-compact-v1.properties",
    ]
    assert upload["with"]["if-no-files-found"] == "error"


def test_other_public_validation_gui_and_legacy_gate_mappings_are_preserved() -> None:
    workflow = _workflow(BUILD_WORKFLOW)
    assert workflow is not None
    assert workflow["permissions"] == {"contents": "read"}
    jobs = workflow["jobs"]
    assert set(jobs) == {
        "public-validation",
        "public-wide-compact-retention",
        "public-gui-smoke",
        "legacy-normal-performance",
        "legacy-tracker-performance",
    }

    assert jobs["public-validation"]["timeout-minutes"] == "300"
    gui = jobs["public-gui-smoke"]
    assert gui["needs"] == "public-validation"
    assert gui["timeout-minutes"] == "15"
    assert gui["strategy"]["matrix"]["include"] == [
        {"engine": "PROBABILISTIC", "report": "public-gui-smoke"},
        {"engine": "LEGACY_THEN_B", "report": "public-gui-smoke-hybrid"},
    ]

    normal = jobs["legacy-normal-performance"]
    tracker = jobs["legacy-tracker-performance"]
    assert normal["timeout-minutes"] == tracker["timeout-minutes"] == "30"
    assert any("legacy-normal-performance-gate.py" in step.get("run", "") for step in normal["steps"])
    assert any("legacy-tracker-performance-gate.py" in step.get("run", "") for step in tracker["steps"])
    assert all(job.get("continue-on-error") in (None, "false") for job in jobs.values())
