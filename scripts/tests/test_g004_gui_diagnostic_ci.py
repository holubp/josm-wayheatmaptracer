"""Contract checks for the approved G004 native and Legacy diagnostic GUI rows."""

from __future__ import annotations

from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "build.yml"
GUI_RUN = (
    "timeout 600s xvfb-run -a -s '-screen 0 1920x1080x24' "
    "sh gradlew v022PublicGuiSmoke -PpublicGuiEngine=${{ matrix.engine }} "
    "-PpublicGuiAlignmentMode=${{ matrix.mode }} "
    "-PpublicGuiRefinementOrder=${{ matrix.order }} --console=plain"
)
DIAGNOSTIC_MATRIX = [
    {"engine": "PROBABILISTIC", "report": "public-gui-smoke-native-interval", "mode": "PRECISE_SHAPE", "order": "INTERVAL"},
    {"engine": "PROBABILISTIC", "report": "public-gui-smoke-migrate-b", "mode": "PRECISE_SHAPE", "order": "A_B"},
    {"engine": "CORRIDOR_AWARE", "report": "public-gui-smoke-migrate-a", "mode": "MOVE_EXISTING_NODES", "order": "A_B"},
    {"engine": "CORRIDOR_AWARE", "report": "public-gui-smoke-native-a-precise", "mode": "PRECISE_SHAPE", "order": "A_B"},
    {"engine": "PROBABILISTIC", "report": "public-gui-smoke-native-b-move", "mode": "MOVE_EXISTING_NODES", "order": "B_A"},
    {"engine": "PROBABILISTIC", "report": "public-gui-smoke-visible-b-move", "mode": "MOVE_EXISTING_NODES", "order": "VISIBLE_B_A"},
    {"engine": "PROBABILISTIC", "report": "public-gui-smoke-visible-stable-b-move", "mode": "MOVE_EXISTING_NODES", "order": "VISIBLE_B_A_STABLE"},
    {"engine": "LEGACY_V02", "report": "public-gui-smoke-stepwise-precise-a-b", "mode": "PRECISE_SHAPE", "order": "A_B"},
    {"engine": "LEGACY_V02", "report": "public-gui-smoke-stepwise-precise-b-a", "mode": "PRECISE_SHAPE", "order": "B_A"},
    {"engine": "LEGACY_V02", "report": "public-gui-smoke-stepwise-move-a-b", "mode": "MOVE_EXISTING_NODES", "order": "A_B"},
    {"engine": "LEGACY_V02", "report": "public-gui-smoke-stepwise-move-b-a", "mode": "MOVE_EXISTING_NODES", "order": "B_A"},
    {"engine": "LEGACY_THEN_B", "report": "public-gui-smoke-migrate-sequential", "mode": "PRECISE_SHAPE", "order": "A_B"},
]


def _workflow() -> dict[str, object]:
    loaded = yaml.load(WORKFLOW.read_text(encoding="utf-8"), Loader=yaml.BaseLoader)
    assert isinstance(loaded, dict)
    return loaded


def test_gradle_migration_report_paths_match_matrix() -> None:
    gradle = (ROOT / "build.gradle.kts").read_text(encoding="utf-8")
    for name in ("public-gui-smoke-migrate-b", "public-gui-smoke-migrate-sequential",
                 "public-gui-smoke-migrate-a"):
        assert f'"reports/{name}"' in gradle


def test_g004_diagnostic_gui_is_fast_native_and_diagnostic_only() -> None:
    workflow = _workflow()
    jobs = workflow["jobs"]
    assert isinstance(jobs, dict)
    job = jobs["g004-gui-diagnostic"]
    assert isinstance(job, dict)
    assert job.get("if") == (
        "github.event_name == 'push' && (startsWith(github.ref, 'refs/heads/codex/g004-') "
        "|| github.ref == 'refs/heads/codex/g009-rc17-20261127')"
    )
    assert job["runs-on"] == "ubuntu-24.04"
    assert job["timeout-minutes"] == "15"
    assert "needs" not in job
    assert job["strategy"] == {"fail-fast": "false", "matrix": {"include": DIAGNOSTIC_MATRIX}}

    steps = job["steps"]
    assert isinstance(steps, list)
    assert any(step.get("uses") == "actions/checkout@v4" for step in steps)
    java = next(step for step in steps if step.get("uses") == "actions/setup-java@v4")
    python = next(step for step in steps if step.get("uses") == "actions/setup-python@v5")
    assert java["with"] == {"distribution": "temurin", "java-version": "17"}
    assert python["with"] == {"python-version": "3.11"}
    prepare = next(step for step in steps if step.get("name") == "Prepare pinned JOSM 19555 host")
    assert "scripts/prepare-ci-josm19555.py --output-dir \"$RUNNER_TEMP/josm19555-host\"" in prepare["run"]
    assert "ORG_GRADLE_PROJECT_josm19555HostJar=$RUNNER_TEMP/josm19555-host/JOSM-Linux-java11-19555.jar" in prepare["run"]
    assert any(step.get("run") == "command -v xvfb-run" for step in steps)
    gui = next(step for step in steps if step.get("name") == "Run diagnostic Legacy GUI smoke test")
    assert gui["run"] == GUI_RUN
    assert "fake" not in gui["run"].lower()

    summary = next(step for step in steps if step.get("name") == "Require diagnostic GUI summary after a passing run")
    assert summary["if"] == "success()"
    assert summary["run"] == "test -s build/reports/${{ matrix.report }}/summary.json"
    uploads = [step for step in steps if step.get("uses") == "actions/upload-artifact@v4"]
    assert len(uploads) == 1
    upload = uploads[0]
    assert upload["if"] == "always()"
    assert upload["with"]["name"] == "g004-diagnostic-${{ matrix.report }}"
    assert upload["with"]["if-no-files-found"] == "ignore"
    paths = upload["with"]["path"].splitlines()
    assert paths == [
        "build/reports/${{ matrix.report }}/summary.json",
        "build/reports/${{ matrix.report }}/host-runtime.json",
        "build/reports/${{ matrix.report }}/diagnostic-metadata.json",
    ]
    metadata = next(step for step in steps if step.get("name") == "Write fixed diagnostic-only metadata")
    assert metadata["if"] == "always()"
    assert "GITHUB_SHA" in metadata["run"]
    assert "releaseQualified" in metadata["run"]
    assert '"releaseQualified": False' in metadata["run"]
    assert metadata["env"]["G004_ENGINE"] == "${{ matrix.engine }}"
    assert '"engine": actual_engine' in metadata["run"]
    assert '"expectedEngine": expected_engine' in metadata["run"]
    assert '"engineRole": "observed" if actual_engine is not None else "unavailable"' in metadata["run"]
    assert '"savedEngine": saved_engine' in metadata["run"]


def test_diagnostic_job_does_not_change_public_release_gates_or_permissions() -> None:
    workflow = _workflow()
    assert workflow["permissions"] == {"contents": "read"}
    jobs = workflow["jobs"]
    assert set(jobs) == {
        "public-validation", "public-gui-smoke", "g004-gui-diagnostic",
        "legacy-normal-performance", "legacy-tracker-performance", "g004-focused-tests",
    }
    assert jobs["public-gui-smoke"]["needs"] == "public-validation"
    public_matrix = jobs["public-gui-smoke"]["strategy"]["matrix"]["include"]
    assert len(public_matrix) == 12
    assert public_matrix[-4:] == DIAGNOSTIC_MATRIX[7:11]
    assert jobs["public-gui-smoke"]["strategy"]["matrix"]["include"][:3] == [
        {"engine": "PROBABILISTIC", "report": "public-gui-smoke-migrate-b", "mode": "PRECISE_SHAPE", "order": "A_B"},
        {"engine": "PROBABILISTIC", "report": "public-gui-smoke-native-interval", "mode": "PRECISE_SHAPE", "order": "INTERVAL"},
        {"engine": "LEGACY_THEN_B", "report": "public-gui-smoke-migrate-sequential", "mode": "PRECISE_SHAPE", "order": "A_B"},
    ]
    assert public_matrix[2] == DIAGNOSTIC_MATRIX[-1]
    assert public_matrix[3:] == DIAGNOSTIC_MATRIX[2:-1]
    assert jobs["public-validation"]["timeout-minutes"] == "300"
    assert jobs["public-validation"]["steps"][-1]["with"]["name"] == "public-validated-wayheatmaptracer"
