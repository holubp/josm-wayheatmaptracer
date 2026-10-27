"""Prospective quality-only experiment; these are not numerical model results."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
PROTOCOL = ROOT / "src/test/resources/b-shared-feasibility/protocol.json"
SCRIPT = ROOT / "scripts/verify-b-shared-feasibility.py"


class FeasibilityProtocolTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.protocol = json.loads(PROTOCOL.read_text())
        spec = importlib.util.spec_from_file_location("quality_feasibility", SCRIPT)
        cls.api = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.api)

    def test_literal_public_inventory_and_fixed_family(self):
        p = self.api.validate_protocol(self.protocol)
        self.assertEqual(32, len(p["cases"]))
        self.assertEqual(8, len(p["screening"]))
        self.assertEqual([(g, e) for g in (1, 4, 16, 64) for e in (0, .25, 1, 4)],
                         self.api.settings(p))

    def test_each_screening_category_is_protected(self):
        for category in ("intense", "faint-intermittent", "representative"):
            p = copy.deepcopy(self.protocol)
            p["screening"] = [x for x in p["screening"] if x["category"] != category]
            with self.subTest(category=category), self.assertRaises(ValueError):
                self.api.validate_protocol(p)

    def test_unknown_duplicate_or_omitted_case_cannot_pass(self):
        for mutate in (lambda p: p["cases"].pop(),
                       lambda p: p["cases"].__setitem__(0, "unknown"),
                       lambda p: p["cases"].__setitem__(0, p["cases"][1])):
            p = copy.deepcopy(self.protocol)
            mutate(p)
            with self.assertRaises(ValueError):
                self.api.validate_protocol(p)

    def test_qualified_or_unbounded_memory_claim_refused(self):
        for field, value in (("evidenceClass", "QUALIFIED"),
                             ("totalPluginMemoryQualified", True)):
            p = copy.deepcopy(self.protocol)
            p[field] = value
            with self.assertRaises(ValueError):
                self.api.validate_protocol(p)

    def test_no_launch_with_unresolved_user_tested_baseline(self):
        p = copy.deepcopy(self.protocol)
        p["baseline"]["status"] = "UNRESOLVED_USER_TESTED_IDENTITY"
        with self.assertRaisesRegex(ValueError, "baseline"):
            self.api.require_baseline_launch(p)

    def test_user_approved_source_defaults_reference_is_explicit(self):
        b = self.api.require_baseline_launch(self.protocol)
        self.assertEqual("117baaf09f4b81a38ad4679eedc8fd1b12ed3d2a", b["sourceCommit"])

    def test_extended_offline_reference_budget_preserves_candidate_limits_and_history(self):
        b=self.api.require_baseline_launch(self.protocol)
        self.assertEqual([96,8000000,1024000000,32,8],b["nativePolicy"])
        self.assertEqual("SOURCE117_DEFAULT_OBJECTIVE_EXTENDED_OFFLINE_V1",b["executionLimitsPolicy"])
        self.assertEqual((900,960),(b["cooperativeSeconds"],b["externalSeconds"]))
        self.assertEqual((128000000,300,360),tuple(self.protocol["limits"][k]
            for k in ("solverScores","cooperativeSeconds","externalSeconds")))
        self.assertEqual((16,208,64),tuple(self.protocol["schedule"][k]
            for k in ("previousOriginalAttempts","maximumAttempts","maximumCaseJobs")))
        self.assertIs(False, b["exactHistoricalTestedBinaryClaim"])
        self.assertEqual("FIRST_NATIVE_RETURNED_ALTERNATIVE", b["primarySelectionPolicy"])
        for key, value in (("sourceCommit", "0" * 40),
                           ("exactHistoricalTestedBinaryClaim", True),
                           ("inputEquivalenceRequired", False)):
            p = copy.deepcopy(self.protocol)
            p["baseline"][key] = value
            with self.assertRaises(ValueError):
                self.api.require_baseline_launch(p)

    def test_candidate_source_revision_does_not_prove_tested_binary(self):
        p = copy.deepcopy(self.protocol)
        p["baseline"]["status"] = "BOUND"
        with self.assertRaisesRegex(ValueError, "baseline"):
            self.api.require_baseline_launch(p)

    def test_quality_units_and_untracked_coverage_cannot_disappear(self):
        for mutate in (lambda p: p["quality"].pop("sampleSpacingNativePixels"),
                       lambda p: p["quality"].__setitem__("meanToleranceNativePixels", 1),
                       lambda p: p["memoryCoverage"].__setitem__("excluded", [])):
            p = copy.deepcopy(self.protocol)
            mutate(p)
            with self.assertRaises(ValueError):
                self.api.validate_protocol(p)

    def test_limits_and_historical_roles_cannot_be_relaxed(self):
        for mutate in (lambda p: p["limits"].__setitem__("states", 97),
                       lambda p: p.__setitem__("caseRolePolicy", "all-optional"),
                       lambda p: p["limits"].__setitem__("searchHalfWidthMeters", 14)):
            p = copy.deepcopy(self.protocol)
            mutate(p)
            with self.assertRaises(ValueError):
                self.api.validate_protocol(p)

    def test_baseline_suitability_requires_actual_sparse_failure(self):
        rows = [{"caseId": x["id"], "completed": True, "hardPassed": True}
                for x in self.protocol["screening"]]
        self.assertEqual("INCONCLUSIVE", self.api.suitability(self.protocol, rows)["status"])
        rows[-1]["hardPassed"] = False
        verdict = self.api.suitability(self.protocol, rows)
        self.assertEqual("SUITABLE", verdict["status"])
        self.assertEqual([rows[-1]["caseId"]], verdict["demonstratedSparseFailures"])
        rows[0]["completed"] = False
        self.assertEqual("INCONCLUSIVE", self.api.suitability(self.protocol, rows)["status"])

    def test_missing_duplicate_or_unknown_baseline_receipts_refuse(self):
        rows = [{"caseId": x["id"], "completed": True, "hardPassed": True}
                for x in self.protocol["screening"]]
        for bad in (rows[:-1], rows + [rows[0]], rows + [dict(rows[0], caseId="unknown")]):
            with self.assertRaises(ValueError):
                self.api.suitability(self.protocol, bad)

    def test_centering_metric_is_invariant_to_collinear_output_density(self):
        truth = [(0., 0.), (4., 0.)]
        sparse = [(0., 1.), (4., 1.)]
        dense = [(0., 1.), (1., 1.), (2., 1.), (4., 1.)]
        a = self.api.centering_metrics(truth, sparse, 1.)
        self.assertEqual(a, self.api.centering_metrics(truth, dense, 1.))
        self.assertEqual(9, a["sampleCount"])
        self.assertEqual(1., a["meanMeters"])
        self.assertEqual(1., a["p95Meters"])

    def test_centering_includes_final_endpoint_once_and_validates_geometry(self):
        m = self.api.centering_metrics([(0., 0.), (1.2, 0.)], [(0., 0.), (1., 0.)], 1.)
        self.assertEqual(4, m["sampleCount"])
        self.assertAlmostEqual(.05, m["meanMeters"])
        self.assertAlmostEqual(.2, m["p95Meters"])
        for truth, returned, pitch in (([(0, 0)], [(0, 0), (1, 0)], 1),
                ([(0, 0), (1, 0)], [], 1),
                ([(0, 0), (float("nan"), 0)], [(0, 0), (1, 0)], 1),
                ([(0, 0), (1, 0)], [(0, 0), (1, 0)], 0)):
            with self.assertRaises(ValueError):
                self.api.centering_metrics(truth, returned, pitch)

    def test_ci_gate_protects_baseline_before_candidates_and_never_releases(self):
        source = (ROOT / ".github/workflows/b-shared-feasibility.yml").read_text()
        self.assertIn("workflow_dispatch:", source)
        self.assertIn("codex/b-shared-quality-feasibility-20261027", source)
        self.assertNotIn('branches: ["**"]', source)
        self.assertIn('java-version: "17.0.12+7"', source)
        self.assertIn("contents: read", source)
        self.assertIn("*V022BSharedFeasibilityComparisonTest", source)
        self.assertNotIn("--tests '*V022BSharedPositionKernelTest'", source)
        self.assertNotIn("--tests '*V022BSharedSolverParityTest'", source)
        self.assertNotIn("V022_RUN_SHARED_FEASIBILITY:", source)
        self.assertNotIn("gh release", source)
        self.assertNotIn("test-data/", source)
        self.assertNotIn("build/\n", source)
        self.assertIn("needs: [prepare, suitability]", source)
        self.assertIn("needs.suitability.outputs.suitable == 'true'", source)
        self.assertIn("max-parallel: 1", source)


if __name__ == "__main__":
    unittest.main()
