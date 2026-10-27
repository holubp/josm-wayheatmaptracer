"""Bounded runner mechanics only; no original fixture or model invocation."""
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import os
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]


class RunnerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec=importlib.util.spec_from_file_location("shared_runner",ROOT/"scripts/run-b-shared-feasibility.py")
        cls.api=importlib.util.module_from_spec(spec);spec.loader.exec_module(cls.api)

    def test_exploratory_projection_preserves_historical_authority_and_other_code(self):
        original=(ROOT/"scripts/historical-b-native/HistoricalNativeB.java").read_bytes()
        with tempfile.TemporaryDirectory() as tmp:
            target=Path(tmp)/"generated/HistoricalNativeB.java"
            self.api.project_baseline_support(target)
            actual=target.read_bytes();start=original.index(b"    static final class Probe")
            end=original.index(b"    static void require",start)
            self.assertEqual(original[:start],actual[:start])
            self.assertTrue(actual.endswith(original[end:]))
            self.assertIn(b"!allowEngine&&forbiddenFrame.getAsBoolean()",actual)
            self.assertEqual(original,(ROOT/"scripts/historical-b-native/HistoricalNativeB.java").read_bytes())
            with patch.object(self.api,"BASELINE_SUPPORT_SHA256","0"*64):
                with self.assertRaisesRegex(ValueError,"source changed"):
                    self.api.project_baseline_support(Path(tmp)/"refused.java")
            self.assertFalse((Path(tmp)/"refused.java").exists())

    def test_manifest_requires_external_hash_and_rejects_extra_or_changed_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);(root/"data").write_bytes(b"public synthetic bytes")
            manifest={"files":{"data":{"bytes":22,"sha256":hashlib.sha256(b"public synthetic bytes").hexdigest()}}}
            raw=(json.dumps(manifest)+"\n").encode();(root/"manifest.json").write_bytes(raw)
            sha=hashlib.sha256(raw).hexdigest()
            self.api.verify_bundle(root,sha)
            with self.assertRaises(ValueError):self.api.verify_bundle(root,"0"*64)
            (root/"extra").write_bytes(b"extra")
            with self.assertRaises(ValueError):self.api.verify_bundle(root,sha)
            (root/"extra").unlink();(root/"data").write_bytes(b"changed")
            with self.assertRaises(ValueError):self.api.verify_bundle(root,sha)

    def test_actual_launcher_uses_extended_timeout_only_for_baseline(self):
        protocol=self.api.read_json(ROOT/"src/test/resources/b-shared-feasibility/protocol.json")
        case=protocol["screening"][0]["id"]
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);(root/"resources/b-shared-feasibility").mkdir(parents=True);(root/"neutral").mkdir()
            self.api.write_json(root/"resources/b-shared-feasibility/protocol.json",protocol)
            self.api.write_json(root/"neutral/index.json",dict(captures={case:dict(snapshotHash="d"*64)}))
            with patch.dict(os.environ,{"GITHUB_SHA":"a"*40}),patch.object(self.api,"verify_bundle",return_value=dict(codeCommit="a"*40)):
                for setting,seconds in ((None,960),((1,0),360)):
                    with self.subTest(setting=setting),patch.object(self.api,"run_process",return_value={}) as launched:
                        self.api.attempt(root,"b"*64,case,root/"attempt",setting)
                        self.assertEqual(seconds,launched.call_args.kwargs.get("seconds",360))

    def test_baseline_receipt_cannot_relabel_ordinary_limits_as_extended(self):
        case="strong-modulated-versus-quiet-faint";policy="SOURCE117_DEFAULT_OBJECTIVE_EXTENDED_OFFLINE_V1"
        context=dict(codeCommit="a"*40,runnerManifestSha256="b"*64,protocolSha256="c"*64,snapshotHashes={case:"d"*64})
        binding=self.api.expected_binding(context,case)
        with tempfile.TemporaryDirectory() as tmp:
            for maximum in (1024000000,128000000):
                p=Path(tmp)/str(maximum);p.mkdir()
                receipt=dict(caseId=case,snapshotHash="d"*64,evidenceClass="EXPLORATORY_QUALITY_ONLY_V1",
                    baselineSourceCommit=self.api.BASELINE,inputEquivalent=True,exactHistoricalTestedBinaryClaim=False,
                    executionLimitsPolicy=policy,baselineRequest=dict(nativePolicy=[96,8000000,maximum,32,8],executionLimitsPolicy=policy),
                    status="MODEL_RETURNED_ROUTE",solverInvocations=1,trackedBytesAfterClose=0,quality=dict(available=True,passed=True))
                self.api.write_json(p/"checked.json",receipt)
                self.api.write_json(str(p)+".host.json",dict(processCompleted=True,binding=binding,receiptSha256=self.api.sha(p/"checked.json")))
                if maximum==1024000000:self.assertTrue(self.api.assessed(p,True,binding)["completed"])
                else:
                    with self.assertRaisesRegex(ValueError,"execution limits"):
                        self.api.assessed(p,True,binding)

    def test_atomic_receipt_never_overwrites_existing(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/"receipt.json";self.api.write_json(p,{"status":"UNIT"})
            before=p.read_bytes()
            with self.assertRaises(FileExistsError):self.api.write_json(p,{"status":"wrong"})
            self.assertEqual(before,p.read_bytes())

    def test_process_failure_and_cutoff_never_become_completed_model_results(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            result=self.api.run_process([sys.executable,"-c","raise SystemExit(7)"],root/"failed",2)
            self.assertEqual("PROCESS_FAILURE",result["status"])
            self.assertFalse(result["processCompleted"])
            result=self.api.run_process([sys.executable,"-c","import threading;threading.Event().wait()"],root/"cutoff",.05)
            self.assertEqual("EXTERNAL_CUTOFF",result["status"])
            self.assertIsNone(result["nativeInvocations"])

    def test_duplicate_json_and_paths_refuse(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/"bad.json";p.write_text('{"status":1,"status":2}')
            with self.assertRaises(ValueError):self.api.read_json(p)
        for name in ("../outside","/absolute","a\\b","a/../b"):
            with self.assertRaises(ValueError):self.api.safe_relative(name)

    def test_selection_cannot_skip_demonstrating_baseline_failure(self):
        protocol=self.api.read_json(ROOT/"src/test/resources/b-shared-feasibility/protocol.json")
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            for item in protocol["screening"]:
                p=root/"baseline"/item["id"];p.mkdir(parents=True)
                self.api.write_json(str(p)+".host.json",dict(processCompleted=True))
                self.api.write_json(p/"checked.json",dict(status="MODEL_RETURNED_ROUTE",solverInvocations=1,
                    trackedBytesAfterClose=0,quality=dict(available=True,passed=True)))
            result=self.api.select(protocol,root/"baseline",root/"absent-candidates",root/"selection.json")
            self.assertEqual("INCONCLUSIVE",result["status"])
            self.assertEqual("BASELINE_SUITABILITY_NOT_ESTABLISHED",result["reason"])
            self.assertIsNone(result["selected"])

    def test_assessment_rejects_swapped_case_and_setting_and_changed_receipt(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/"case";p.mkdir()
            binding=dict(caseId="strong-parallel-gap",role="candidate",gain=1,eta=1,
                codeCommit="a"*40,runnerManifestSha256="b"*64,protocolSha256="c"*64,snapshotHash="d"*64)
            receipt=dict(caseId=binding["caseId"],gain=1,eta=1,sourceRevision=binding["codeCommit"],
                evidenceClass="EXPLORATORY_QUALITY_ONLY_V1",snapshotHash=binding["snapshotHash"],
                status="MODEL_RETURNED_ROUTE",solverInvocations=1,trackedBytesAfterClose=0,
                quality=dict(available=True,passed=True))
            self.api.write_json(p/"receipt.json",receipt)
            self.api.write_json(str(p)+".host.json",dict(processCompleted=True,binding=binding,receiptSha256=self.api.sha(p/"receipt.json")))
            self.assertTrue(self.api.assessed(p,expected=binding)["completed"])
            for key,value in (("caseId","connected-straight"),("gain",4),("eta",4)):
                wrong=dict(binding);wrong[key]=value
                with self.assertRaises(ValueError):self.api.assessed(p,expected=wrong)
            (p/"receipt.json").write_text('{}')
            with self.assertRaises(ValueError):self.api.assessed(p,expected=binding)

    def test_known_failure_rejects_setting_even_with_another_missing_row(self):
        good=dict(completed=True,hardPassed=True)
        bad=dict(completed=True,hardPassed=False)
        missing=dict(completed=False,hardPassed=False)
        self.assertEqual("REJECT",self.api.setting_verdict([bad,missing]))
        self.assertEqual("INCONCLUSIVE",self.api.setting_verdict([good,missing]))
        self.assertEqual("REJECT",self.api.setting_verdict([dict(good,strongComparisonPassed=False),missing]))
        self.assertEqual("INCONCLUSIVE",self.api.setting_verdict([dict(good,metricRequired=True)]))
        self.assertEqual("ELIGIBLE",self.api.setting_verdict([good,good]))
        self.assertEqual("REJECT",self.api.family_verdict(["REJECT","REJECT"]))
        self.assertEqual("INCONCLUSIVE",self.api.family_verdict(["REJECT","INCONCLUSIVE"]))

    def test_pre_capture_refusal_does_not_invent_snapshot_or_break_collection(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/"case";p.mkdir()
            binding=dict(caseId="strong-parallel-gap",role="candidate",gain=1,eta=1,
                codeCommit="a"*40,runnerManifestSha256="b"*64,protocolSha256="c"*64,snapshotHash="d"*64)
            receipt=dict(caseId=binding["caseId"],gain=1,eta=1,sourceRevision=binding["codeCommit"],
                evidenceClass="EXPLORATORY_QUALITY_ONLY_V1",status="INCONCLUSIVE",failurePhase="CAPTURE",solverInvocations=0,trackedBytesAfterClose=0)
            self.api.write_json(p/"receipt.json",receipt)
            self.api.write_json(str(p)+".host.json",dict(processCompleted=True,binding=binding,receiptSha256=self.api.sha(p/"receipt.json")))
            value=self.api.assessed(p,expected=binding)
            self.assertFalse(value["completed"]);self.assertFalse(value["hardPassed"])
            self.assertNotIn("snapshotHash",value["receipt"])


if __name__=="__main__":unittest.main()
