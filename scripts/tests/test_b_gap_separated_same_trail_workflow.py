"""Fixed synthetic same-trail launch scope and fail-closed receipt gate controls."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/b-gap-separated-same-trail-checks.yml"
IDS = ["same-trail-three-separated-strong"]
ETAS = [0, 1, 2, 4, 8, 16, 32, 64]


def actual_gate():
    """Extract the actual CI Python gate, rather than a parallel validation implementation."""
    block = WORKFLOW.read_text().split("      - name: Verify one actual eight-setting separated execution\n", 1)[1]
    block = block.split("      - name:", 1)[0].split("        run: |\n", 1)[1]
    shell = textwrap.dedent(block)
    return shell.split("python3 - <<'PY'\n", 1)[1].rsplit("\nPY", 1)[0]


class SameTrailWorkflowTest(unittest.TestCase):
    def valid(self):
        return {
            "sourceCohort": "ADDITIONAL_SEPARATED_SAME_TRAIL_ONE",
            "sourceCaseExecutionComplete": True,
            "sourcePipeline": {"failedPhase": None, "solverInvocations": 8, "solverReturns": 8},
            "rows": [{"eta": eta, "actualSolve": True, "status": "REVIEW_REQUIRED", "work": "actual-work",
                      "wholeRouteGeometryPassed": False, "requiredControlPassed": False} for eta in ETAS],
        }

    def run_gate(self, receipt, optimized=False, summary_complete=True):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "build/gap-task5-matrix"
            root.mkdir(parents=True)
            (root / "separated-same-trail-summary.json").write_text(json.dumps({"expectedCases": IDS, "complete": summary_complete, "separatedSameTrailExecutionEnabled": True}))
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
        self.assertIn("branches: [codex/b-gap-separated-same-trail-20261020]", workflow)
        self.assertIn('V022_RUN_SEPARATED_SAME_TRAIL_CONTROL: "true"', workflow)
        self.assertIn("ref: ${{ github.sha }}", workflow)
        self.assertNotIn("workflow_dispatch", workflow)
        self.assertNotIn("--tests '*V022BGapMatchedControlsTest'", workflow)
        self.assertNotIn("v022Observation", workflow)
        paths = workflow.split("          path: |\n", 1)[1].split("          if-no-files-found:", 1)[0]
        actual = [line.strip() for line in paths.splitlines() if line.strip()]
        expected = ["build/gap-separated-same-trail-preflight/fixtures.xml", "build/gap-separated-same-trail-preflight/harness.xml", "build/test-results/test/*V022BGapMatchedControlsTest.xml",
                    "build/gap-task5-matrix/separated-same-trail-summary.json", *["build/gap-task5-matrix/" + identity + ".json" for identity in IDS],
                    "build/gap-separated-same-trail-matrix/native-fixtures.json",
                    *["build/gap-separated-same-trail-matrix/" + identity + extension for identity in IDS for extension in (".f64", ".valid")]]
        self.assertEqual(expected, actual)

    def test_metadata_preflight_precedes_the_single_opt_in_actual_execution(self):
        workflow = WORKFLOW.read_text()
        preflight = workflow.index("      - name: Run fixed pure geometry and source metadata preflight")
        preserved = workflow.index("      - name: Preserve completed preflight reports")
        actual = workflow.index("      - name: Run only the one fixed actual separated source control")
        self.assertLess(preflight, preserved)
        self.assertLess(preserved, actual)
        self.assertNotIn("V022_RUN_SEPARATED_SAME_TRAIL_CONTROL", workflow[preflight:preserved])
        self.assertNotIn("separatedSameTrailSourcePipelineProducesCompleteEightSettingMatrix", workflow[preflight:preserved])
        opt_in = workflow[actual:workflow.index("      - name: Verify one actual", actual)]
        self.assertEqual(1,opt_in.count("-PdedicatedExperiments=true"))
        self.assertNotIn("dedicatedExperiments",workflow[preflight:preserved])
        self.assertEqual(1, opt_in.count("--tests"))
        self.assertIn("separatedSameTrailSourcePipelineProducesCompleteEightSettingMatrix", opt_in)


if __name__ == "__main__":
    unittest.main()
