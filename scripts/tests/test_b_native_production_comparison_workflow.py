"""Actual native-comparison CI gate controls; never invokes Java or a renderer."""
import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest
ROOT = Path(__file__).resolve().parents[2]
WF = ROOT / ".github/workflows/b-native-production-comparison.yml"
CONTRACT = ROOT / "src/test/resources/b-observed-gap/native-production-comparison.json"

def gate():
    block = WF.read_text().split("      - name: Verify factual bounded native comparison\n", 1)[1].split("      - name:", 1)[0]
    return textwrap.dedent(block.split("        run: |\n", 1)[1]).split("python3 - <<'PY'\n", 1)[1].rsplit("\nPY", 1)[0]

class NativeComparisonWorkflowTest(unittest.TestCase):
    def fixture(self):
        contract = json.loads(CONTRACT.read_text()); identity = contract["cases"][0]
        policy = {"identity": "CURRENT_NATIVE_PRODUCTION_B_NOT_IDENTIFIED_B0_OR_HISTORICAL_RC12", "budgets": [96,8000000,128000000,32,8]}
        common = {"case": identity, "sourceRevision": "a"*40, "comparisonContractSha256": hashlib.sha256(CONTRACT.read_bytes()).hexdigest(), "policy": policy}
        binding = dict(common, nativeInput=contract["nativeInputs"][identity], nativeMetricSource=[[0,0],[2,0]])
        result = dict(common, nativeInvocations=1, nativeReturns=1, nativeStatus="COMPLETE", safeCause="NONE", comparisonBoundary="ENGINE_BOUNDARY_ONLY", modelQualification=False,
                      finalPreviewOrApplyQualification=False, routeFiles=["route-0.json"], routeDataAvailable=True, qualityComparisonAvailable=True, distinctAlternatives=1, rawAlternatives=32)
        ownership = {"engineAndSourceOwnersClosed": True, "remainingNonReportBytes": 0, "outerJUnitFinallyRequiresWholeLedgerZero": True}
        route = {"id":"native-0", "branchSignature":"native-branch", "points":[[0,0],[2,0]], "support":["FIXED_TOPOLOGY_ONLY"]*2, "geometry":{"passed":False}}
        return identity, {"binding.json":binding, "result.json":result, "ownership.json":ownership, "route-0.json":route}
    def run_gate(self, files, optimized=False):
        identity, _ = self.fixture()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); resource = root / "src/test/resources/b-observed-gap/native-production-comparison.json";resource.parent.mkdir(parents=True);resource.write_bytes(CONTRACT.read_bytes())
            output = root / "build/b-native-production-comparison" / identity;output.mkdir(parents=True)
            for name,value in files.items():(output/name).write_text(json.dumps(value))
            env=dict(os.environ,GITHUB_SHA="a"*40,V022_NATIVE_B_CASE=identity)
            return subprocess.run([sys.executable,*(["-O"] if optimized else []),"-c",gate()],cwd=root,env=env,capture_output=True,text=True).returncode
    def test_geometry_failure_is_data_and_native_resource_return_is_unavailable_quality(self):
        for optimized in (False,True):
            _, files=self.fixture();self.assertEqual(0,self.run_gate(files,optimized))
            files["result.json"].update(nativeStatus="RESOURCE_LIMIT",qualityComparisonAvailable=False)
            self.assertEqual(0,self.run_gate(files,optimized))
    def test_thrown_typed_refusal_is_not_a_returned_trace_result(self):
        for optimized in (False,True):
            _, files=self.fixture();files.pop("route-0.json");files["result.json"].update(nativeStatus=None,nativeReturns=0,safeCause="RESOURCE_LIMIT",routeFiles=[],routeDataAvailable=False,qualityComparisonAvailable=False)
            self.assertEqual(0,self.run_gate(files,optimized));files["result.json"]["nativeStatus"]="RESOURCE_LIMIT";self.assertNotEqual(0,self.run_gate(files,optimized))
    def test_actual_gate_rejects_lost_routes_false_quality_policy_identity_and_source_binding(self):
        changes=[("nativeInvocations",0),("nativeReturns",2),("modelQualification",True),("qualityComparisonAvailable",False),("sourceRevision","b"*40),("distinctAlternatives",2),("routeFiles",["../private.json"]),("safeCause","ILLEGAL_ARGUMENT"),("comparisonBoundary","APPLY")]
        for optimized in (False,True):
            for key,value in changes:
                _,files=self.fixture();files["result.json"][key]=value;self.assertNotEqual(0,self.run_gate(files,optimized),(key,value))
            _,files=self.fixture();files["result.json"]["policy"]=dict(files["result.json"]["policy"],budgets=[96,8000000,128000000,1,1]);self.assertNotEqual(0,self.run_gate(files,optimized))
            _,files=self.fixture();files["route-0.json"]["points"][0]=[1,0];self.assertNotEqual(0,self.run_gate(files,optimized))
            _,files=self.fixture();files["ownership.json"]["remainingNonReportBytes"]=1;self.assertNotEqual(0,self.run_gate(files,optimized))
    def test_launch_is_five_sequential_cases_with_one_optin_and_explicit_leaves(self):
        text=WF.read_text();self.assertIn("branches: [codex/b-native-production-comparison-20261020]",text)
        self.assertIn("max-parallel: 1",text);self.assertIn("fail-fast: false",text);self.assertNotIn("workflow_dispatch",text)
        self.assertIn('V022_RUN_NATIVE_B_COMPARISON: "true"',text);self.assertIn("timeout 360s",text)
        preflight=text.split("  native-comparison:",1)[0];self.assertNotIn("V022_RUN_NATIVE_B_COMPARISON",preflight)
        actual=text.split("      - name: One fixed current-native B attempt, no retry",1)[1].split("      - name:",1)[0]
        self.assertEqual(1,actual.count("--tests"));self.assertNotIn("V022BGapMatchedControlsTest",text)
        paths=text.split("          path: |\n",1)[1].split("          if-no-files-found",1)[0]
        lines=[x.strip() for x in paths.splitlines() if x.strip()]
        expected=["build/test-results/test/*V022BNativeProductionComparisonTest.xml"]+["build/b-native-production-comparison/${{ matrix.case }}/"+n for n in ["binding.json","result.json","ownership.json","phase.json",*["route-"+str(i)+".json" for i in range(8)]]]
        self.assertEqual(expected,lines)
if __name__=="__main__":unittest.main()
