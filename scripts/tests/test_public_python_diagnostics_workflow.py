import copy
import shlex
from pathlib import Path

import pytest
import yaml


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/public-python-diagnostics.yml"
DIAGNOSTIC_SOURCE = "bbc1f4904ff69801508fee74b6da368437141d47"


def assert_public_python_workflow(workflow):
    assert workflow["on"] == {
        "push": {"branches": ["codex/public-python311-diagnostics-20261020"]},
        "workflow_dispatch": "",
    }
    assert workflow["permissions"] == {"contents": "read"}
    assert set(workflow["jobs"]) == {"public-python-diagnostics"}
    job = workflow["jobs"]["public-python-diagnostics"]
    assert job["runs-on"] == "ubuntu-24.04"
    assert job["timeout-minutes"] == "15"
    assert job.get("continue-on-error") in (None, "false")
    steps = job["steps"]

    assert [step.get("uses") for step in steps if "uses" in step] == [
        "actions/checkout@v4", "actions/setup-java@v4", "actions/setup-python@v5",
    ]
    checkout = next(step for step in steps if step.get("uses", "").startswith("actions/checkout@"))
    assert checkout["uses"] == "actions/checkout@v4"
    assert checkout["with"] == {"persist-credentials": "false", "ref": DIAGNOSTIC_SOURCE}
    setup_java = next(step for step in steps if step.get("uses", "").startswith("actions/setup-java@"))
    assert setup_java["uses"] == "actions/setup-java@v4"
    assert setup_java["with"] == {"distribution": "temurin", "java-version": "17"}
    setup = next(step for step in steps if step.get("uses", "").startswith("actions/setup-python@"))
    assert setup["uses"] == "actions/setup-python@v5"
    assert setup["with"] == {"python-version": "3.11"}

    commands = [step.get("run", "") for step in steps]
    all_commands = "\n".join(commands)
    installs = [shlex.split(command) for command in commands if "pip install" in command]
    assert installs == [[
        "python3", "-m", "pip", "install", "pytest==9.1.1", "PyYAML==6.0.2", "hypothesis==6.165.2",
    ]]
    assert "python3 -m pytest -q scripts/tests" in commands
    assert "python3 --version" in all_commands
    assert f'test "$(git rev-parse HEAD)" = "{DIAGNOSTIC_SOURCE}"' in all_commands
    assert "case \"$GITHUB_SHA\" in ''|*[!0-9a-f]*) exit 1;; esac" in all_commands
    assert "printf 'workflow_event_commit=%s\\n' \"$GITHUB_SHA\"" in all_commands
    assert "printf 'tested_source_commit=%s\\n' \"$(git rev-parse HEAD)\"" in all_commands
    assert "pytest --version" in all_commands
    assert "PyYAML" in all_commands and "hypothesis" in all_commands

    forbidden = ("gradlew", "java", "benchmark", "replay", "capture", "private",
                 "upload-artifact", "secrets.", "continue-on-error", "--ignore", "-k ")
    assert not any(token.lower() in all_commands.lower() for token in forbidden)
    assert all(step.get("continue-on-error") in (None, "false") for step in steps)


def test_public_python_diagnostics_is_closed_and_runs_only_the_pinned_public_suite():
    assert WORKFLOW.is_file(), "Python-only public diagnostics workflow is missing"
    workflow = yaml.load(WORKFLOW.read_text(encoding="utf-8"), Loader=yaml.BaseLoader)
    assert_public_python_workflow(workflow)


@pytest.mark.parametrize("mutation", (
    "upload-action", "job-continue-on-error", "extra-install-token", "wrong-source-ref",
    "missing-source-guard", "missing-event-commit",
))
def test_public_python_workflow_guard_rejects_unsafe_mutations(mutation):
    workflow = yaml.load(WORKFLOW.read_text(encoding="utf-8"), Loader=yaml.BaseLoader)
    workflow = copy.deepcopy(workflow)
    job = workflow["jobs"]["public-python-diagnostics"]
    if mutation == "upload-action":
        job["steps"].append({"uses": "actions/upload-artifact@v4"})
    elif mutation == "job-continue-on-error":
        job["continue-on-error"] = "true"
    elif mutation == "wrong-source-ref":
        checkout = next(step for step in job["steps"] if step.get("uses", "").startswith("actions/checkout@"))
        checkout["with"]["ref"] = "arbitrary-ref"
    elif mutation == "missing-source-guard":
        identity = next(step for step in job["steps"] if step.get("name") == "Verify diagnostic source and report runtime")
        identity["run"] = identity["run"].replace(
            f'test "$(git rev-parse HEAD)" = "{DIAGNOSTIC_SOURCE}"', "git rev-parse HEAD",
        )
    elif mutation == "missing-event-commit":
        identity = next(step for step in job["steps"] if step.get("name") == "Verify diagnostic source and report runtime")
        identity["run"] = identity["run"].replace("workflow_event_commit=%s\\n", "event_commit=%s\\n")
    else:
        install = next(step for step in job["steps"] if "pip install" in step.get("run", ""))
        install["run"] += " extra-package==1.0"

    with pytest.raises(AssertionError):
        assert_public_python_workflow(workflow)
