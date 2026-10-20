"""Historical tests must retain genuine, bounded receipts rather than skip."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "verify-historical-benchmark-receipts.py"


class HistoricalReceiptsTest(unittest.TestCase):
    def test_workflow_uses_literal_historical_checkout_and_exact_public_selectors(self):
        workflow = SCRIPT.parents[1] / ".github/workflows/b-historical-benchmark-context.yml"
        self.assertTrue(workflow.is_file(), "Historical CI job is missing")
        text = workflow.read_text()
        for required in ("117baaf09f4b81a38ad4679eedc8fd1b12ed3d2a", "-PvalidationProfile=public",
                         "junit.jupiter.tempdir.cleanup.mode.default=NEVER", "java.io.tmpdir=",
                         "approvedContractKeepsEachNativeEnvelopeAndOriginalPin",
                         "approvedMainAdmitsIndependentSingleAndFactualIntervalCompatibilityOutputs",
                         "contents: read", "git status --porcelain --untracked-files=all"):
            self.assertIn(required, text)
        self.assertNotIn("workflow_dispatch", text)
        self.assertNotIn("contents: write", text)

    def test_verifier_exists(self):
        self.assertTrue(SCRIPT.is_file(), "Historical receipt validation is missing")

    def fixture(self, root):
        temporary = root / "temporary"
        for directory, names in (("a", ("baseline.zip", "candidate.zip", "original.osm")),
                                 ("b", ("single.zip", "interval.zip", "original.osm"))):
            target = temporary / directory
            target.mkdir(parents=True)
            for name in names:
                (target / name).write_bytes(b"synthetic fixture")
        xml = root / "results.xml"
        names = ("approvedContractKeepsEachNativeEnvelopeAndOriginalPin", "approvedMainAdmitsIndependentSingleAndFactualIntervalCompatibilityOutputs")
        xml.write_text('<testsuite tests="2" failures="0" errors="0" skipped="0">' + ''.join(
            '<testcase classname="org.openstreetmap.josm.plugins.wayheatmaptracer.BenchmarkDecisionComparatorIntegrationTest" name="' + name + '(Path)"/>' for name in names) + '</testsuite>')
        return temporary, xml

    def verifier(self):
        self.assertTrue(SCRIPT.is_file(), "Historical receipt validation is missing")
        spec = importlib.util.spec_from_file_location("historical_receipts", SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module.verify

    def test_valid_six_files_are_hashed_without_claiming_current_qualification(self):
        verify = self.verifier()
        with tempfile.TemporaryDirectory() as folder:
            temporary, xml = self.fixture(Path(folder))
            result = verify(temporary, xml)
            self.assertEqual("HISTORICAL_CONTEXT_ONLY", result["scope"])
            self.assertEqual(6, len(result["files"]))
            self.assertTrue(all(len(item["sha256"]) == 64 for item in result["files"]))

    def test_missing_extra_duplicate_symlink_or_oversized_receipts_are_rejected(self):
        verify = self.verifier()
        for mutation in ("missing", "extra", "duplicate", "symlink", "oversized"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as folder:
                temporary, xml = self.fixture(Path(folder))
                target = temporary / "a" / "baseline.zip"
                if mutation == "missing":
                    target.unlink()
                elif mutation == "extra":
                    (temporary / "a" / "unexpected").write_text("x")
                elif mutation == "duplicate":
                    import shutil
                    shutil.copytree(temporary / "a", temporary / "c")
                elif mutation == "symlink":
                    target.unlink()
                    target.symlink_to(temporary / "b" / "single.zip")
                else:
                    with target.open("wb") as stream:
                        stream.truncate(8 * 1024 * 1024 + 1)
                with self.assertRaises(ValueError):
                    verify(temporary, xml)

    def test_skipped_failed_wrong_or_extra_tests_are_rejected(self):
        verify = self.verifier()
        for mutation in ("skip", "failure", "wrong", "extra", "counts"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as folder:
                temporary, xml = self.fixture(Path(folder))
                text = xml.read_text()
                if mutation in ("skip", "failure"):
                    text = text.replace('/>', '><' + ("skipped" if mutation == "skip" else "failure") + '/></testcase>', 1)
                elif mutation == "wrong":
                    text = text.replace("approvedMainAdmits", "wrongMainAdmits")
                elif mutation == "extra":
                    text = text.replace('</testsuite>', '<testcase name="extra"/></testsuite>')
                else:
                    text = text.replace('tests="2"', 'tests="1"')
                xml.write_text(text)
                with self.assertRaises(ValueError):
                    verify(temporary, xml)


if __name__ == "__main__":
    unittest.main()
