"""Fixed synthetic switchback launch scope and fail-closed receipt gate controls."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/b-gap-switchback-checks.yml"
IDS = ["switchback-thin-strong", "switchback-thin-faint"]
ETAS = [0, 1, 2, 4, 8, 16, 32, 64]


def actual_gate():
    """Extract the actual CI Python gate, rather than a parallel validation implementation."""
    block = WORKFLOW.read_text().split("      - name: Verify both actual eight-setting source executions\n", 1)[1]
    block = block.split("      - name:", 1)[0].split("        run: |\n", 1)[1]
    shell = textwrap.dedent(block)
    return shell.split("python3 - <<'PY'\n", 1)[1].rsplit("\nPY", 1)[0]


class SwitchbackWorkflowTest(unittest.TestCase):
    def valid(self):
        return {
            "sourceCohort": "ADDITIONAL_FROZEN_SWITCHBACK_TWO",
            "sourceCaseExecutionComplete": True,
            "sourcePipeline": {"failedPhase": None, "solverInvocations": 8, "solverReturns": 8},
            "rows": [{"eta": eta, "actualSolve": True, "status": "REVIEW_REQUIRED", "work": "actual-work",
                      "wholeRouteGeometryPassed": False, "requiredControlPassed": False} for eta in ETAS],
        }

    def run_gate(self, receipt, optimized=False, summary_complete=True):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "build/gap-task5-matrix"
            root.mkdir(parents=True)
            (root / "switchback-summary.json").write_text(json.dumps({"expectedCases": IDS, "complete": summary_complete, "switchbackExecutionEnabled": True}))
            for identity in IDS:
                bound = copy.deepcopy(receipt)
                bound.setdefault("case", identity)
                (root / (identity + ".json")).write_text(json.dumps(bound))
            result = subprocess.run([sys.executable, *(["-O"] if optimized else []), "-c", actual_gate()],
                                    cwd=directory, capture_output=True, text=True)
            return result.returncode

    def test_real_geometry_failure_is_data_and_no_route_is_an_executed_outcome(self):
        for optimized in (False, True):
            self.assertEqual(0, self.run_gate(self.valid(), optimized))
            no_route = self.valid()
            no_route["rows"][0]["status"] = "NO_ROUTE"
            self.assertEqual(0, self.run_gate(no_route, optimized))

    def test_missing_unvisited_and_terminal_source_work_fail_even_under_python_optimized(self):
        variants = []
        value = self.valid(); value["rows"] = value["rows"][:-1]; variants.append(value)
        for status in (None, "RESOURCE_LIMIT", "NUMERIC_FAILURE"):
            value = self.valid(); value["rows"][3]["status"] = status; variants.append(value)
        for field, replacement in (("actualSolve", False), ("work", "unavailable"), ("eta", 3)):
            value = self.valid(); value["rows"][3][field] = replacement; variants.append(value)
        value = self.valid(); value["sourcePipeline"]["failedPhase"] = "COMPLETE"; variants.append(value)
        value = self.valid(); value["sourcePipeline"]["solverInvocations"] = 0; variants.append(value)
        value = self.valid(); value["sourcePipeline"]["solverReturns"] = 7; variants.append(value)
        value = self.valid(); value["sourceCaseExecutionComplete"] = False; variants.append(value)
        value = self.valid(); value["case"] = "wrong-case"; variants.append(value)
        value = self.valid(); value["sourceCohort"] = "wrong-cohort"; variants.append(value)
        for optimized in (False, True):
            for value in variants:
                with self.subTest(optimized=optimized, value=value):
                    self.assertNotEqual(0, self.run_gate(copy.deepcopy(value), optimized))
            self.assertNotEqual(0, self.run_gate(self.valid(), optimized, summary_complete=False))

    def test_only_new_fixed_cases_can_launch_and_only_explicit_public_files_upload(self):
        workflow = WORKFLOW.read_text()
        self.assertIn("branches: [codex/b-gap-switchbacks-20261020]", workflow)
        self.assertIn('V022_RUN_SWITCHBACK_CONTROLS: "true"', workflow)
        self.assertIn("ref: ${{ github.sha }}", workflow)
        self.assertNotIn("workflow_dispatch", workflow)
        self.assertNotIn("--tests '*V022BGapMatchedControlsTest'", workflow)
        self.assertNotIn("v022Observation", workflow)
        paths = workflow.split("          path: |\n", 1)[1].split("          if-no-files-found:", 1)[0]
        actual = [line.strip() for line in paths.splitlines() if line.strip()]
        expected = ["build/test-results/test/*V022BThinSwitchbackTest.xml", "build/test-results/test/*V022BGapMatchedControlsTest.xml",
                    "build/gap-task5-matrix/switchback-summary.json", *["build/gap-task5-matrix/" + identity + ".json" for identity in IDS],
                    "build/gap-switchback-matrix/native-fixtures.json",
                    *["build/gap-switchback-matrix/" + identity + extension for identity in IDS for extension in (".f64", ".valid")]]
        self.assertEqual(expected, actual)


if __name__ == "__main__":
    unittest.main()
