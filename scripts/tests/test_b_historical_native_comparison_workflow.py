"""Fixed historical32 launch guards: metadata only, no engine invocation."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import yaml
from hypothesis import given, settings, strategies as st

ROOT = Path(__file__).resolve().parents[2]
def module():
    spec = importlib.util.spec_from_file_location("historical_launch", ROOT / "scripts/verify-b-historical-launch.py")
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value

class HistoricalLaunchContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Controlled metadata fixtures bind the test's actual source bytes.
        # They grant no authority to the unchanged published launch tuple.
        v=module();cls.context=tempfile.TemporaryDirectory();cls.controlled_root=Path(cls.context.name)
        m=json.loads((ROOT/v.MANIFEST).read_bytes())
        for name in [*m["sourceFiles"],v.NATIVE,v.PROTOCOL]:
            target=cls.controlled_root/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(ROOT/name,target)
        for tree in v.TREES:
            shutil.copytree(ROOT/tree,cls.controlled_root/tree,dirs_exist_ok=True)
        m["sourceFiles"]["scripts/verify-b-historical-launch.py"]=v.driver.sha((ROOT/"scripts/verify-b-historical-launch.py").read_bytes())
        (cls.controlled_root/v.MANIFEST).write_bytes(v.driver.canonical(m))

    @classmethod
    def tearDownClass(cls):
        cls.context.cleanup()

    def setUp(self):
        self.v = module()
        self.root=self.controlled_root
        self.raw = (self.root / self.v.MANIFEST).read_bytes()
        self.pin = hashlib.sha256(self.raw).hexdigest()
        self.m = json.loads(self.raw)
        self.p = json.loads((self.root / self.v.PROTOCOL).read_bytes())

    def test_candidate_metadata_is_bound_without_granting_execution(self):
        self.assertEqual(32, len(self.v.verify(self.root, self.pin)["cases"]))

    def test_pending_fixture_refuses_before_any_archive_or_engine(self):
        m, p = copy.deepcopy(self.m), copy.deepcopy(self.p)
        m["approval"] = self.v.PENDING
        p.update(approval=self.v.PENDING, engineEligible=False)
        with self.assertRaisesRegex(ValueError, "approval"):
            self.v.require_approval(m, p)

    def test_explicit_promotion_changes_only_approval_metadata(self):
        m, p = copy.deepcopy(self.m), copy.deepcopy(self.p)
        m["approval"] = self.v.APPROVED
        p.update(approval=self.v.APPROVED, engineEligible=True)
        self.v.require_approval(m, p)
        p["nativePolicy"] = [97, 8000000, 128000000, 32, 8]
        with self.assertRaisesRegex(ValueError, "policy"):
            self.v.require_approval(m, p)

    def test_wrong_external_manifest_pin_is_refused(self):
        with self.assertRaisesRegex(ValueError, "manifest"):
            self.v.verify(ROOT, "0" * 64)

    def test_wrong_branch_is_refused_even_with_selfconsistent_metadata(self):
        with self.assertRaisesRegex(ValueError, "branch"):
            self.v.verify(ROOT, self.pin, branch="main")

    def test_changed_source_helper_is_refused_before_java(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for name in [self.v.MANIFEST, self.v.PROTOCOL, *self.m["sourceFiles"]]:
                target = root / name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(self.root / name, target)
            target = root / "scripts/run-b-historical-native-comparison.py"
            target.write_bytes(target.read_bytes() + b"\n# changed\n")
            with self.assertRaisesRegex(ValueError, "source"):
                self.v.verify(root, self.pin)

    def test_workflow_has_fixed32_isolation_watchdogs_and_no_retry(self):
        workflow = yaml.safe_load((ROOT / ".github/workflows/b-historical-native-comparison.yml").read_text())
        self.assertEqual({"push": {"branches": [self.v.BRANCH]}}, workflow.get("on", workflow.get(True)))
        self.assertEqual({"contents": "read"}, workflow["permissions"])
        self.assertEqual(self.v.driver.sha((ROOT/self.v.MANIFEST).read_bytes()),workflow["env"]["HISTORICAL_MANIFEST_SHA"])
        jobs = workflow["jobs"]
        self.assertIn("native", jobs)
        native = jobs["native"]
        self.assertEqual({"fail-fast": False, "max-parallel": 2, "matrix": {"case": list(self.v.driver.IDS)}}, native["strategy"])
        self.assertEqual(15, native["timeout-minutes"])
        text = json.dumps(native)
        self.assertEqual(1, text.count(" native-engine "))
        self.assertNotIn("capture-only", text)
        self.assertNotIn("retry", text.lower().replace("no retry", ""))
        self.assertIn("117baaf09f4b81a38ad4679eedc8fd1b12ed3d2a", text)
        self.assertIn("--producer-exit-code", text)
        self.assertIn("emitsActualCurrentClasspathWithoutFixtureAcquisition", text)
        self.assertNotIn("V022BNativeNeutralFixtureTest", text)

    def test_historical_two_positive_job_is_exactly_unchanged(self):
        current=yaml.safe_load((ROOT/".github/workflows/b-historical-native-comparison.yml").read_text())
        original=yaml.safe_load((ROOT/".github/workflows/b-historical-benchmark-context.yml").read_text())
        self.assertEqual(original["jobs"]["historical-context"],current["jobs"]["historical-context"])

    def test_producer_wait_precedes_checker_and_upload_precedes_failure_gate(self):
        steps=yaml.safe_load((ROOT/".github/workflows/b-historical-native-comparison.yml").read_text())["jobs"]["native"]["steps"]
        runs=[step.get("run","") for step in steps]
        producer=next(i for i,s in enumerate(runs) if " native-engine " in s)
        checker=next(i for i,s in enumerate(runs) if "BNativeHistoricalPointComparison --case" in s)
        upload=next(i for i,s in enumerate(steps) if s.get("uses")=="actions/upload-artifact@v4")
        self.assertLess(producer,checker);self.assertLess(checker,upload)
        self.assertIn("LAUNCH_CODE=$?",runs[producer]);self.assertIn("--producer-exit-code",runs[checker])
        self.assertIn("historical-native-exit.txt",runs[-1]);self.assertEqual("always()",steps[-1]["if"])
        self.assertEqual("driver/build/historical-native-artifact/",steps[upload]["with"]["path"])
        for step in steps:
            if "Compile" in step.get("name",""):self.assertNotIn("timeout 360",step.get("run",""))

    def test_selfconsistent_wrong_protocol_policy_and_case_set_refuse(self):
        for mutation in (lambda p:p.update(nativePolicy=[97,8000000,128000000,32,8]),lambda p:p.update(cases=list(reversed(p["cases"])))):
            with tempfile.TemporaryDirectory() as tmp:
                root=Path(tmp)
                # Source checks remain authentic; only the protocol/manifest fixture differs.
                for name in [*self.m["sourceFiles"],self.v.NATIVE]:
                    target=root/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(ROOT/name,target)
                for tree in self.v.TREES:
                    shutil.copytree(ROOT/tree,root/tree,dirs_exist_ok=True)
                p=copy.deepcopy(self.p);mutation(p);praw=self.v.driver.canonical(p)
                target=root/self.v.PROTOCOL;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(praw)
                m=copy.deepcopy(self.m);m["protocolSha256"]=self.v.driver.sha(praw);raw=self.v.driver.canonical(m);(root/self.v.MANIFEST).write_bytes(raw)
                with self.assertRaisesRegex(ValueError,"protocol"):
                    self.v.verify(root,self.v.driver.sha(raw))

    def test_pending_execution_refuses_without_reading_archive(self):
        if self.m["approval"]==self.v.PENDING:
            with self.assertRaisesRegex(ValueError,"approval"):
                self.v.verify(self.root,self.pin,execution=True,archive=ROOT/"missing-archive",publication_commit=None)
        else:
            # Explicit pending fixture above remains active after reviewed promotion.
            self.v.require_approval(self.m,self.p)

    def test_missing_or_partial_native_evidence_stays_typed_and_unchanged(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp=Path(tmp);raw=tmp/"raw";raw.mkdir();case=self.v.driver.IDS[0];folder=raw/case;folder.mkdir()
            original=b'{unfinished';(folder/"route-0.json.partial").write_bytes(original)
            points=tmp/"missing-points";output=tmp/"artifact"
            r=self.v.collect(self.root,case,raw,points,output,self.pin,None,None,"f"*40,tmp/"no-attestation")
            self.assertFalse(r["modelQualified"]);self.assertFalse(r["launcherObserved"]);self.assertIsNone(r["launcherWaitExitCode"])
            self.assertEqual(original,(output/"raw-route-0.json.partial").read_bytes())
            self.assertIn("point-directory",r["missing"]);self.assertNotIn("nativeStatus",r)

    def test_unknown_linked_and_oversized_leaves_refuse_before_artifact_creation(self):
        for name,data in (("unknown-private.dat",b"x"),("result.json",b"x"*65537)):
            with tempfile.TemporaryDirectory() as tmp:
                tmp=Path(tmp);raw=tmp/"raw";raw.mkdir();folder=raw/self.v.driver.IDS[0];folder.mkdir();(folder/name).write_bytes(data);output=tmp/"out"
                with self.assertRaises(ValueError):self.v.collect(self.root,self.v.driver.IDS[0],raw,tmp/"points",output,self.pin,1,None,"f"*40,tmp/"att")
                self.assertFalse(output.exists())

    @settings(max_examples=8,deadline=None)
    @given(st.integers(min_value=0,max_value=1000).filter(lambda n:n!=96))
    def test_no_alternative_state_cap_is_a_valid_promotion(self,states):
        m,p=copy.deepcopy(self.m),copy.deepcopy(self.p)
        m["approval"]=self.v.APPROVED;p.update(approval=self.v.APPROVED,engineEligible=True)
        p["nativePolicy"][0]=states
        with self.assertRaisesRegex(ValueError,"policy"):self.v.require_approval(m,p)

    def test_actual_cli_external_hash_guard_rejects_before_any_producer(self):
        r=subprocess.run([sys.executable,str(ROOT/"scripts/verify-b-historical-launch.py"),"verify","--manifest-sha","0"*64],capture_output=True,text=True,timeout=15)
        self.assertNotEqual(0,r.returncode);self.assertIn("external manifest digest",r.stderr)

    def test_junit_zero_skip_integrity_rejects_misreported_cases_and_allows_cdata(self):
        good=b'<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase name="pure"/><system-out><![CDATA[public capture only]]></system-out></testsuite>'
        self.v.require_junit(good)
        for bad in (good.replace(b'tests="1"',b'tests="2"'),good.replace(b'<testcase name="pure"/>',b'<testcase name="pure"><skipped/></testcase>'),b'<!DOCTYPE x>'+good,b'<!ENTITY x "x">'+good):
            with self.assertRaises(ValueError):self.v.require_junit(bad)

    def checkout(self, directory, approved=True):
        """Build a real clean Git fixture with authentic source/data bindings."""
        root=directory/"checkout";root.mkdir()
        for name in [*self.m["sourceFiles"],self.v.NATIVE]:
            target=root/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(ROOT/name,target)
        for tree in self.v.TREES:
            shutil.copytree(ROOT/tree,root/tree,dirs_exist_ok=True)
        p=copy.deepcopy(self.p);m=copy.deepcopy(self.m)
        p.update(approval=self.v.APPROVED if approved else self.v.PENDING,engineEligible=approved)
        m["approval"]=p["approval"];praw=self.v.driver.canonical(p);m["protocolSha256"]=self.v.driver.sha(praw)
        (root/self.v.PROTOCOL).write_bytes(praw);raw=self.v.driver.canonical(m);(root/self.v.MANIFEST).write_bytes(raw)
        (root/".gitignore").write_text("build/\n")
        subprocess.run(["git","init","-q",str(root)],check=True,capture_output=True)
        self.commit(root)
        commit=self.v.driver.git(root,"rev-parse","HEAD")
        archive=ROOT/"src/test/resources/b-observed-gap/neutral-native-public32.zip"
        self.assertEqual(1961965,archive.stat().st_size)
        self.assertEqual(m["archiveSha256"],self.v.driver.sha(archive.read_bytes()))
        return root,self.v.driver.sha(raw),commit,archive

    def commit(self, root):
        """Commit only the controlled fixture, with no hooks or signing."""
        common=["git","-c","safe.directory="+str(root),"-c","core.hooksPath=/dev/null","-C",str(root)]
        subprocess.run(common+["add","."],check=True,capture_output=True)
        subprocess.run(common+["-c","user.name=Fixture","-c","user.email=fixture@example.invalid",
                               "-c","commit.gpgsign=false","commit","-qm","controlled metadata"],check=True,capture_output=True)

    def test_full_approved_admission_uses_real_clean_git_sources_protocol_and_archive(self):
        with tempfile.TemporaryDirectory() as tmp:
            root,pin,commit,archive=self.checkout(Path(tmp))
            self.assertEqual(self.v.APPROVED,self.v.verify(root,pin,True,self.v.BRANCH,commit,archive)["approval"])
            with self.assertRaisesRegex(ValueError,"publication commit"):
                self.v.verify(root,pin,True,self.v.BRANCH,"0"*40,archive)
            with self.assertRaisesRegex(ValueError,"branch"):
                self.v.verify(root,pin,True,"main",commit,archive)
            altered=Path(tmp)/"altered.zip";altered.write_bytes(archive.read_bytes()+b"x")
            with self.assertRaisesRegex(ValueError,"archive digest"):
                self.v.verify(root,pin,True,self.v.BRANCH,commit,altered)
            (root/"unreviewed").write_text("dirty")
            with self.assertRaisesRegex(ValueError,"clean publication"):
                self.v.verify(root,pin,True,self.v.BRANCH,commit,archive)
        with tempfile.TemporaryDirectory() as tmp:
            root,pin,commit,_=self.checkout(Path(tmp),approved=False)
            with self.assertRaisesRegex(ValueError,"approval"):
                self.v.verify(root,pin,True,self.v.BRANCH,commit,root/"NO_ARCHIVE_TRANSPORT")

    def test_actual_default_console_without_marker_uses_validated_neutral_junit_output(self):
        # Exact retained passing neutral-test output (2026-10-20), not a new capture.
        console='''To honour the JVM settings for this build a single-use Daemon process will be forked. For more on this, please refer to https://docs.gradle.org/9.4.1/userguide/gradle_daemon.html#sec:disabling_the_daemon in the Gradle documentation.
Daemon will be stopped at the end of the build\x20
> Task :compileJava UP-TO-DATE
> Task :processResources NO-SOURCE
> Task :classes UP-TO-DATE
> Task :compileTestJava
Note: Some input files use unchecked or unsafe operations.
Note: Recompile with -Xlint:unchecked for details.

> Task :processTestResources UP-TO-DATE
> Task :testClasses
> Task :test

[Incubating] Problems report is available at: file:///storage/emulated/0/GitHub/josm-slide/.worktrees/b-observation-ci-20261018/build/reports/problems/problems-report.html

BUILD SUCCESSFUL in 5m 43s
4 actionable tasks: 2 executed, 2 up-to-date
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.4.1/userguide/configuration_cache_enabling.html
'''
        self.assertNotIn("NEUTRAL_REVIEW_DIRECTORY=",console)
        # Exact retained XML, including its actual methods, times and CDATA.
        neutral=b'<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.V022BNativeNeutralFixtureTest" tests="7" skipped="0" failures="0" errors="0" timestamp="2026-10-20T10:57:31.681Z" hostname="localhost" time="227.415">\n  <properties/>\n  <testcase name="all32RawInputsMatchExistingFrozenHashes()" classname="org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.V022BNativeNeutralFixtureTest" time="0.03"/>\n  <testcase name="invalidMaskOrNonfiniteScalarRefusesBeforeEngine()" classname="org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.V022BNativeNeutralFixtureTest" time="0.004"/>\n  <testcase name="all32RecapturesMatchSnapshotScalarAndBothMasks()" classname="org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.V022BNativeNeutralFixtureTest" time="133.795"/>\n  <testcase name="neutralArchiveContainsNoTruthOrRecordingPayload()" classname="org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.V022BNativeNeutralFixtureTest" time="0.083"/>\n  <testcase name="cancellationAndInsufficientFundingCloseBeforeCapture()" classname="org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.V022BNativeNeutralFixtureTest" time="0.021"/>\n  <testcase name="quarterPhaseIsNotReplacedByIntegerGrid()" classname="org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.V022BNativeNeutralFixtureTest" time="0.002"/>\n  <testcase name="unknownDuplicateTraversalAndOversizedMembersRefuse()" classname="org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.V022BNativeNeutralFixtureTest" time="0.01"/>\n  <system-out><![CDATA[NEUTRAL_REVIEW_DIRECTORY=build/native-neutral-review-2357203375609834567 ARCHIVE_SHA256=9c93ac93c6a5460a5dd07bb6ee2b78bad2113a685e2ba4cf4f09c6fc97a484db CAPTURE_ONLY_CHECKPOINTS=1392608\n]]></system-out>\n  <system-err><![CDATA[]]></system-err>\n</testsuite>\n'
        with tempfile.TemporaryDirectory() as tmp:
            root,pin,commit,archive=self.checkout(Path(tmp))
            result=root/"build/test-results/test";result.mkdir(parents=True)
            for name in ("V022BNativeNeutralFixtureTest","V022BNativeHistoricalPointComparisonTest","V022BNativeHistoricalClasspathTest"):
                data=neutral if name=="V022BNativeNeutralFixtureTest" else b'<testsuite tests="1" skipped="0" failures="0" errors="0"><testcase name="pure"/></testsuite>'
                (result/("TEST-"+name+".xml")).write_bytes(data)
            review=root/"build/native-neutral-review-2357203375609834567";review.mkdir();shutil.copyfile(archive,review/"neutral.zip")
            log=root/"build/console.log";log.write_text(console)
            out=root/"build/upload"
            self.v.preflight(root,log,pin,commit,out)
            self.assertEqual(archive.read_bytes(),(out/"neutral.zip").read_bytes())
            self.assertEqual(neutral,(out/"V022BNativeNeutralFixtureTest.xml").read_bytes())
            # Even a plausible console marker cannot replace missing JUnit output.
            log.write_text("NEUTRAL_REVIEW_DIRECTORY=build/native-neutral-review-2357203375609834567 ARCHIVE_SHA256="+self.m["archiveSha256"])
            xml=result/"TEST-V022BNativeNeutralFixtureTest.xml"
            xml.write_bytes(neutral.replace(b"NEUTRAL_REVIEW_DIRECTORY=",b"REMOVED="))
            refused=root/"build/refused"
            with self.assertRaisesRegex(ValueError,"one neutral export marker"):
                self.v.preflight(root,log,pin,commit,refused)
            self.assertFalse(refused.exists())
            xml.write_bytes(neutral.replace(b' name="org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.V022BNativeNeutralFixtureTest"',b' name="OtherTest"',1))
            with self.assertRaisesRegex(ValueError,"neutral JUnit source"):
                self.v.preflight(root,log,pin,commit,refused)

    def test_collect_failure_never_publishes_prefix_and_preserves_primary(self):
        original_open=Path.open
        for mode in ("write","close","write-and-close","move"):
            with self.subTest(mode=mode),tempfile.TemporaryDirectory() as tmp:
                tmp=Path(tmp);raw=tmp/"raw";folder=raw/self.v.driver.IDS[0];folder.mkdir(parents=True)
                (folder/"result.json").write_bytes(b'{"actual":"unchanged"}')
                output=tmp/"upload";primary=OSError(mode);secondary=OSError("secondary close")
                class FaultStream:
                    def __init__(self,stream):self.stream=stream
                    def write(self,data):
                        if mode in ("write","write-and-close"):
                            self.stream.write(data[:1]);raise primary
                        return self.stream.write(data)
                    def close(self):
                        self.stream.close()
                        if mode in ("close","write-and-close"):raise primary if mode=="close" else secondary
                    def __enter__(self):return self
                    def __exit__(self,*args):self.close()
                def opening(path,*args,**kwargs):
                    stream=original_open(path,*args,**kwargs)
                    return FaultStream(stream) if path.name=="raw-result.json" and args and args[0]=="xb" else stream
                with patch.object(Path,"open",opening),patch.object(self.v.os,"rename",side_effect=primary) if mode=="move" else patch.object(self.v.os,"getpid",wraps=self.v.os.getpid):
                    with self.assertRaises(OSError) as caught:
                        self.v.collect(self.root,self.v.driver.IDS[0],raw,tmp/"points",output,self.pin,0,0,"f"*40,tmp/"att")
                self.assertIs(primary,caught.exception)
                self.assertFalse(output.exists())
                self.assertEqual({"raw"},{p.name for p in tmp.iterdir()})

    def test_collect_preserves_existing_output_sentinel(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp=Path(tmp);out=tmp/"existing";out.mkdir();(out/"sentinel").write_bytes(b"user")
            with self.assertRaisesRegex(ValueError,"new artifact"):
                self.v.collect(self.root,self.v.driver.IDS[0],tmp/"raw",tmp/"points",out,self.pin,0,0,"f"*40,tmp/"att")
            self.assertEqual(b"user",(out/"sentinel").read_bytes())

    def test_empty_successful_checker_console_is_retained_without_inventing_result(self):
        with tempfile.TemporaryDirectory() as tmp:
            root,pin,commit,_=self.checkout(Path(tmp))
            build=root/"build";build.mkdir()
            (build/"historical-native-checker.log").write_bytes(b"")
            out=Path(tmp)/"upload"
            receipt=self.v.collect(root,self.v.driver.IDS[0],Path(tmp)/"raw",Path(tmp)/"point",out,pin,None,0,commit,Path(tmp)/"att")
            self.assertEqual(b"",(out/"historical-native-checker.log").read_bytes())
            self.assertEqual({"bytes":0,"sha256":hashlib.sha256(b"").hexdigest()},receipt["files"]["historical-native-checker.log"])
            self.assertFalse(receipt["launcherObserved"])
            self.assertTrue(receipt["checkerObserved"])
            self.assertEqual(["raw-directory","point-directory"],receipt["missing"])
            self.assertNotIn("nativeStatus",receipt)
            self.assertFalse(receipt["modelQualified"])

    def test_empty_logs_do_not_relax_nonempty_json_or_console_bounds(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp=Path(tmp);path=tmp/"console";path.write_bytes(b"")
            for name in self.v.CONSOLE_LOGS:
                self.assertEqual(b"",self.v.console_bytes(path,name))
            for name in ("result.json","binding.json","external-cutoff.json","historical-point-current-code-sources.json"):
                with self.assertRaisesRegex(ValueError,"bounded console"):
                    self.v.console_bytes(path,name)
            with self.assertRaisesRegex(ValueError,"bounded input"):
                self.v.driver.bounded(path,65536)
            path.write_bytes(b"x"*65536)
            self.assertEqual(b"x"*65536,self.v.console_bytes(path,"native.log"))
            path.write_bytes(b"x"*65537)
            with self.assertRaisesRegex(ValueError,"bounded console"):
                self.v.console_bytes(path,"native.log")
            link=tmp/"linked";link.symlink_to(path)
            with self.assertRaisesRegex(ValueError,"bounded console"):
                self.v.console_bytes(link,"native.log")
            with self.assertRaisesRegex(ValueError,"bounded console"):
                self.v.console_bytes(tmp,"native.log")

    def test_published_tuple_never_accepts_unpinned_current_verifier(self):
        raw=(ROOT/self.v.MANIFEST).read_bytes();published=json.loads(raw)
        actual=self.v.driver.sha((ROOT/"scripts/verify-b-historical-launch.py").read_bytes())
        if published["sourceFiles"]["scripts/verify-b-historical-launch.py"] != actual:
            with self.assertRaisesRegex(ValueError,"source member changed"):
                self.v.verify(ROOT,self.v.driver.sha(raw))
        else:
            # A later reviewed controller promotion is allowed, never performed here.
            self.v.verify(ROOT,self.v.driver.sha(raw))
