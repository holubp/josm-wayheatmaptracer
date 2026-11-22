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


def test_focused_xml_gate_uses_suite_identity_and_requires_executed_green_tests(tmp_path, monkeypatch) -> None:
    job = _workflow()["jobs"]["g004-focused-tests"]
    gate = next(step for step in job["steps"] if step.get("name") == "Require selected JUnit XML after a passing run")
    script = gate["run"].split("python3 - <<'PY'\n", 1)[1].rsplit("\nPY", 1)[0]
    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv("FOCUSED_REF", "refs/heads/codex/g004-stepwise-20261119")
    reports = tmp_path / "build/test-results/test"
    reports.mkdir(parents=True)
    names = [
        "service.VisibleRefinementWorkflowTest",
        "service.refinement.RefinementAttemptDiagnosticsTest",
        "diagnostics.replay.format15.RefinementLineageCodecTest",
        "service.refinement.RefinementSessionTest",
        "config.PluginPreferencesTest",
        "service.refinement.RefinementRootAuthorityTest",
        "ui.HeatmapSettingsDialogTest",
        "service.V022LiveBPreviewServiceTest",
        "service.refinement.RefinementSmoothingAdapterTest",
        "service.refinement.SmoothingParentBinderTest",
        "service.refinement.SmoothingWorkContextTest",
        "service.refinement.RetainedSmoothingRootTest",
        "service.ModernRefinementBootstrapTest",
        "service.tracing.V022FixedIntervalEditPlanComposerTest",
        "actions.AlignWayActionTest",
        "ui.RefinementControlsTest",
        "ui.RefinementReviewLifecycleTest",
        "actions.StepwiseRefinementApplyTest",
        "actions.StepwiseRefinementOwnershipTest",
        "service.LegacyRefinementWorkflowTest",
        "service.LegacyVisibleRefinementBootstrapTest",
        "service.refinement.RuntimeRefinementDiagnosticsTest",
        "service.refinement.RefinementPipelineTest",
        "service.refinement.RefinementIntervalPlannerTest",
        "actions.OrdinaryModernApplyPreparationTest",
    ]
    for index, name in enumerate(names):
        (reports / f"TEST-randomized-{index}.xml").write_text(
            f'<testsuite name="org.openstreetmap.josm.plugins.wayheatmaptracer.{name}" tests="1" failures="0" errors="0" skipped="0"/>')
    exec(script, {})
    import pytest
    (reports / "TEST-randomized-0.xml").write_text(
        '<testsuite name="org.openstreetmap.josm.plugins.wayheatmaptracer.service.VisibleRefinementWorkflowTest" tests="0" failures="0" errors="0" skipped="0"/>')
    with pytest.raises(SystemExit):
        exec(script, {})


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
        f"{ARTIFACT_OUTPUT}/stages/*.log",
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
    assert smoke["strategy"] == {
        "fail-fast": "false",
        "matrix": {"include": [
            {"engine": "PROBABILISTIC", "report": "public-gui-smoke-migrate-b", "mode": "PRECISE_SHAPE", "order": "A_B"},
            {"engine": "PROBABILISTIC", "report": "public-gui-smoke-native-interval", "mode": "PRECISE_SHAPE", "order": "INTERVAL"},
            {"engine": "LEGACY_THEN_B", "report": "public-gui-smoke-migrate-sequential", "mode": "PRECISE_SHAPE", "order": "A_B"},
            {"engine": "CORRIDOR_AWARE", "report": "public-gui-smoke-migrate-a", "mode": "MOVE_EXISTING_NODES", "order": "A_B"},
            {"engine": "CORRIDOR_AWARE", "report": "public-gui-smoke-native-a-precise", "mode": "PRECISE_SHAPE", "order": "A_B"},
            {"engine": "PROBABILISTIC", "report": "public-gui-smoke-native-b-move", "mode": "MOVE_EXISTING_NODES", "order": "B_A"},
            {"engine": "PROBABILISTIC", "report": "public-gui-smoke-visible-b-move", "mode": "MOVE_EXISTING_NODES", "order": "VISIBLE_B_A"},
            {"engine": "LEGACY_V02", "report": "public-gui-smoke-stepwise-precise-a-b", "mode": "PRECISE_SHAPE", "order": "A_B"},
            {"engine": "LEGACY_V02", "report": "public-gui-smoke-stepwise-precise-b-a", "mode": "PRECISE_SHAPE", "order": "B_A"},
            {"engine": "LEGACY_V02", "report": "public-gui-smoke-stepwise-move-a-b", "mode": "MOVE_EXISTING_NODES", "order": "A_B"},
            {"engine": "LEGACY_V02", "report": "public-gui-smoke-stepwise-move-b-a", "mode": "MOVE_EXISTING_NODES", "order": "B_A"},
        ]},
    }
    steps = smoke["steps"]
    assert isinstance(steps, list)
    assert all(step.get("continue-on-error") in (None, "false") for step in steps)
    assert any(step.get("run") == "command -v xvfb-run" for step in steps)
    smoke_run = next(step for step in steps if "v022PublicGuiSmoke" in step.get("run", ""))
    assert smoke_run["run"] == (
        "timeout 600s xvfb-run -a -s '-screen 0 1920x1080x24' "
        "sh gradlew v022PublicGuiSmoke -PpublicGuiEngine=${{ matrix.engine }} -PpublicGuiAlignmentMode=${{ matrix.mode }} -PpublicGuiRefinementOrder=${{ matrix.order }} --console=plain"
    )
    require_summary = next(step for step in steps if step.get("name") == "Require public GUI smoke summary after a passing run")
    assert require_summary["if"] == "success()"
    assert require_summary["run"] == "test -s build/reports/${{ matrix.report }}/summary.json"
    report_upload = next(step for step in steps if step.get("uses") == "actions/upload-artifact@v4")
    assert report_upload["if"] == "always()"
    assert report_upload["with"]["path"] == "build/reports/${{ matrix.report }}/summary.json"
    assert report_upload["with"]["name"] == "${{ matrix.report }}-summary"
    assert report_upload["with"]["if-no-files-found"] == "ignore"


def test_legacy_normal_performance_runs_three_forks_and_uploads_raw_evidence() -> None:
    workflow = _workflow()
    jobs = workflow["jobs"]
    assert isinstance(jobs, dict)
    job = jobs["legacy-normal-performance"]
    assert isinstance(job, dict)
    assert job["runs-on"] == "ubuntu-24.04"
    assert job["timeout-minutes"] == "30"
    steps = job["steps"]
    assert isinstance(steps, list)
    host = next(step for step in steps if step.get("name") == "Prepare pinned JOSM 19555 host")
    assert "scripts/prepare-ci-josm19555.py" in host["run"]
    assert "ORG_GRADLE_PROJECT_josm19555HostJar=" in host["run"]
    assert steps.index(host) < next(index for index, step in enumerate(steps)
                                    if step.get("name") == "Run three fresh public Legacy normal-policy forks")
    run = next(step for step in steps if step.get("name") == "Run three fresh public Legacy normal-policy forks")
    assert "for fork in 0 1 2" in run["run"]
    assert "legacyNormalPerformance" in run["run"]
    assert '-PlegacyNormalPerformanceFork="$fork"' in run["run"]
    assert run["env"]["PERF_ROOT"] == "${{ runner.temp }}/legacy-normal-performance"
    gate = next(step for step in steps if step.get("name") == "Gate raw fork results")
    assert "legacy-normal-performance-gate.py" in gate["run"]
    assert gate["if"] == "always()"
    upload = next(step for step in steps if step.get("uses") == "actions/upload-artifact@v4")
    assert upload["if"] == "always()"
    assert upload["with"]["name"] == "public-legacy-normal-performance"
    assert upload["with"]["if-no-files-found"] == "ignore"
    assert upload["with"]["path"] == "${{ runner.temp }}/legacy-normal-performance/"


def test_tracker_performance_uses_explicit_same_policy_and_retains_failed_forks() -> None:
    job = _workflow()["jobs"]["legacy-tracker-performance"]
    steps = job["steps"]
    run = next(step for step in steps if step.get("name") == "Run three fresh public Legacy tracker-policy forks")
    host = next(step for step in steps if step.get("name") == "Prepare pinned JOSM 19555 host")
    gate = next(step for step in steps if step.get("name") == "Gate raw tracker-policy fork results")
    policy = run["env"]["TRACKER_POLICY"]
    assert policy.startswith("native-reciprocal-support-paired-chord-v")
    assert f"--candidate-policy {policy}" in gate["run"]
    assert steps.index(host) < steps.index(run)
    assert "scripts/prepare-ci-josm19555.py" in host["run"]
    assert "for fork in 0 1 2" in run["run"]
    assert '-PlegacyTrackerPerformancePolicy="$TRACKER_POLICY"' in run["run"]
    assert "legacyNormalPerformancePreflight" not in run["run"]
    assert 'exit "$failed"' in run["run"]
    assert gate["if"] == "always()"
    assert "legacy-tracker-performance-gate.py" in gate["run"]
    for fork in range(3):
        assert f'--fork-dir "$PERF_ROOT/fork-{fork}"' in gate["run"]
    upload = next(step for step in steps if step.get("uses") == "actions/upload-artifact@v4")
    assert upload["if"] == "always()"
    assert upload["with"]["path"] == "${{ runner.temp }}/legacy-tracker-performance/"
