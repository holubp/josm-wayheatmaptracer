"""Fixed-five V3 orchestration and authentic source/receipt guard controls."""
from pathlib import Path
import copy
import importlib.util
import io
import json
import shutil
import struct
import subprocess

import pytest
import yaml
from hypothesis import given, strategies as st

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/b-exact-cross-stream-checks.yml"
IDS = ["switchback-thin-strong", "switchback-thin-faint", "same-trail-three-strong",
       "same-trail-five-strong", "same-trail-three-separated-strong"]
SPEC = importlib.util.spec_from_file_location("cross_stream_verifier", ROOT/"scripts/verify-b-exact-cross-stream.py")
v = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(v)


def test_actual_workflow_has_one_fixed_five_case_sequential_schedule():
    workflow = yaml.safe_load(WORKFLOW.read_text())
    actual = workflow["jobs"].get("comparison", {}).get("strategy", {})
    assert actual.get("matrix", {}).get("case") == IDS
    assert actual.get("max-parallel") == 1
    assert actual.get("fail-fast") is False
    for job in workflow["jobs"].values():
        checkout=next(step for step in job["steps"] if step.get("uses","").startswith("actions/checkout"))
        assert checkout["with"] == {"ref":"${{ github.sha }}","fetch-depth":0}
    assert workflow["env"]["V022_EXACT_CROSS_STREAM_PROTOCOL_SHA256"] == v.sha(v.read(ROOT/v.PROTOCOL))


@pytest.fixture(scope="module")
def authentic_checkout(tmp_path_factory):
    """Create a real local clean Git fixture from current committed public source.

    No source/hash/Git guard is stubbed. Promotion is UNIT ONLY; no compilation,
    capture, numerical engine, remote Git operation or workflow is invoked.
    """
    root = tmp_path_factory.mktemp("cross-stream-authentic")/"checkout"
    subprocess.run(["git", "-c", "gc.auto=0", "-c", "maintenance.auto=false", "clone", "--shared", "--no-hardlinks", str(ROOT), str(root)], check=True, capture_output=True)
    subprocess.run(["git", "config", "gc.auto", "0"], cwd=root, check=True)
    subprocess.run(["git", "config", "maintenance.auto", "false"], cwd=root, check=True)
    for path in v.SOURCE_FILES | {v.PROTOCOL}:
        (root/path).parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT/path, root/path)
    p = v.document(v.read(root/v.PROTOCOL)); p["approval"] = p["engineEligible"] = True
    (root/v.PROTOCOL).write_bytes(v.canonical(p))
    subprocess.run(["git", "add", "--", *sorted(v.SOURCE_FILES | {v.PROTOCOL})], cwd=root, check=True, capture_output=True)
    subprocess.run(["git", "-c", "user.name=Unit fixture", "-c", "user.email=unit@example.invalid", "commit", "-m", "UNIT ONLY approved metadata context"], cwd=root, check=True, capture_output=True)
    return root


@pytest.fixture
def checkout(authentic_checkout, tmp_path):
    root = tmp_path/"checkout"
    shutil.copytree(authentic_checkout, root, symlinks=True)
    return root


def admission(root, execution=True, **changes):
    """Use actual source hashes, Git HEAD and protocol; no authority shortcuts."""
    return v.verify(root, v.sha(v.read(root/v.PROTOCOL)), execution, publication=v.git(root, "rev-parse", "HEAD"), **changes)


def rewrite_protocol(root, change):
    """Mutate a controlled metadata fixture and commit it as actual publication."""
    p = v.document(v.read(root/v.PROTOCOL)); change(p)
    (root/v.PROTOCOL).write_bytes(v.canonical(p))
    subprocess.run(["git", "add", "--", v.PROTOCOL], cwd=root, check=True, capture_output=True)
    subprocess.run(["git", "-c", "user.name=Unit fixture", "-c", "user.email=unit@example.invalid", "commit", "-m", "UNIT metadata variant"], cwd=root, check=True, capture_output=True)
    return p


def test_authentic_pending_and_full_approved_source_admission(checkout):
    p = admission(checkout)
    assert p["cases"] == IDS and p["reviewedCodeCommit"] != v.git(checkout, "rev-parse", "HEAD")
    rewrite_protocol(checkout, lambda p: p.update(approval=False, engineEligible=False))
    assert admission(checkout, False)["approval"] is False
    with pytest.raises(ValueError, match="pending"):
        admission(checkout)


@pytest.mark.parametrize("field,value", [("cases", list(reversed(IDS))), ("eta", 2), ("eta", False),
    ("nullPotential", False), ("B0", [True, 1, 0]), ("executionPolicy", "terminal-path-count-scored-work-v2")])
def test_mutated_policy_refuses_with_actual_digest_and_git(checkout, field, value):
    rewrite_protocol(checkout, lambda p: p.update({field:value}))
    with pytest.raises(ValueError):
        admission(checkout)


def test_source_branch_publication_and_support_pins_are_actual_guards(checkout):
    with pytest.raises(ValueError, match="branch"):
        admission(checkout, branch="main")
    with pytest.raises(ValueError, match="publication"):
        v.verify(checkout, v.sha(v.read(checkout/v.PROTOCOL)), True, v.BRANCH, "0"*40)
    with pytest.raises(ValueError, match="digest"):
        v.verify(checkout, "0"*64)
    core = checkout/"src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/ProbabilisticInference.java"
    original = core.read_bytes(); core.write_bytes(original+b"\n// UNIT tamper\n")
    with pytest.raises(ValueError, match="closure"):
        admission(checkout)
    core.write_bytes(original)
    helper = checkout/v.INIT; helper.write_bytes(helper.read_bytes()+b"\n// UNIT tamper\n")
    with pytest.raises(ValueError, match="support source"):
        admission(checkout)


@pytest.mark.parametrize("key,value", [("raw", True), ("distinct", 8), ("transitions", 128000001), ("bytes", 268435456),
                                     ("cooperativeSeconds", 301), ("externalSeconds", 361)])
def test_no_counter_cap_or_boolean_coercion(checkout, key, value):
    rewrite_protocol(checkout, lambda p: p["limits"].update({key:value}))
    with pytest.raises(ValueError):
        admission(checkout)


def test_guard_parser_rejects_duplicate_and_nonfinite_json():
    for data in (b'{"case":1,"case":2}', b'{"x":NaN}', b'{"x":Infinity}'):
        with pytest.raises(ValueError):
            v.document(data)


@given(st.one_of(st.booleans(), st.floats(allow_nan=True, allow_infinity=True)))
def test_integer_is_never_a_boolean_or_binary64(value):
    with pytest.raises(ValueError):
        v.integer(value)


def compiled_fixture(root):
    """Write UNIT-ONLY compiled-shape bytes to exercise exact tree sealing."""
    for name in v.COMPILED_ROOTS:
        (root/name).mkdir(parents=True, exist_ok=True)
    for prefix, name in [(v.COMPILED_ROOTS[0], "ProbabilisticInference"), (v.COMPILED_ROOTS[1], "BExactCrossStreamComparison"),
                         (v.COMPILED_ROOTS[1], "V022ExactCrossStreamComparisonTest")]:
        p = root/prefix/v.PACKAGE/(name+".class"); p.parent.mkdir(parents=True, exist_ok=True); p.write_bytes(b"UNIT_ONLY_NOT_EXECUTABLE")
    seal = root/"build/exact-cross-stream-host/compiled.json"; seal.parent.mkdir(parents=True, exist_ok=True)
    digest = v.sha(v.read(root/v.PROTOCOL)); seal.write_bytes(v.canonical(v.compiled(root, digest)))
    return seal


def test_precompiled_closure_is_not_implicitly_resealed(checkout):
    seal = compiled_fixture(checkout); expected = v.sha(v.read(checkout/v.PROTOCOL))
    assert v.compiled(checkout, expected, seal)["compiled"]["files"] == 3
    changed = checkout/v.COMPILED_ROOTS[0]/v.PACKAGE/"ProbabilisticInference.class"; changed.write_bytes(b"CHANGED_UNIT")
    with pytest.raises(ValueError, match="precompiled closure"):
        v.compiled(checkout, expected, seal)


def test_genuine_no_source_resource_root_is_explicit_and_cannot_materialize(checkout):
    seal=compiled_fixture(checkout)
    absent=checkout/"build/resources/main";absent.rmdir()
    digest=v.sha(v.read(checkout/v.PROTOCOL));state=v.compiled(checkout,digest)
    assert state["compiled"]["roots"]["build/resources/main"] == "ABSENT_NO_SOURCE"
    seal.write_bytes(v.canonical(state));v.compiled(checkout,digest,seal)
    absent.mkdir();(absent/"unexpected.txt").write_bytes(b"injected")
    with pytest.raises(ValueError,match="precompiled closure"):
        v.compiled(checkout,digest,seal)


def test_actual_committed_build_configuration_change_is_refused(checkout):
    build=checkout/"build.gradle.kts";build.write_bytes(build.read_bytes()+b"\n// UNIT committed build configuration tamper\n")
    subprocess.run(["git","add","--","build.gradle.kts"],cwd=checkout,check=True,capture_output=True)
    subprocess.run(["git","-c","user.name=Unit fixture","-c","user.email=unit@example.invalid","commit","-m","UNIT committed build tamper"],cwd=checkout,check=True,capture_output=True)
    with pytest.raises(ValueError,match="support source"):
        admission(checkout)


def test_atomic_publication_owns_only_fresh_directory(tmp_path, monkeypatch):
    out = tmp_path/"out"; out.mkdir(); sentinel=out/"sentinel"; sentinel.write_bytes(b"preserved")
    with pytest.raises(ValueError, match="fresh"):
        v.atomic_directory(out, {"one.json":b"{}"})
    assert sentinel.read_bytes() == b"preserved"
    fresh=tmp_path/"fresh"
    def fail_move(self, target):
        raise OSError("UNIT move failure")
    monkeypatch.setattr(Path, "rename", fail_move)
    with pytest.raises(OSError, match="UNIT move"):
        v.atomic_directory(fresh, {"one.json":b"{}"})
    assert not fresh.exists() and sorted(p.name for p in tmp_path.iterdir()) == ["out"]


def xml_bytes(passed=True):
    return ('<testsuite tests="1" failures="%d" errors="0" skipped="0"><testcase classname="%s" name="executesOneFrozenCase()"%s></testsuite>' %
            (0 if passed else 1, v.TEST_CLASS, '/>' if passed else '><failure/></testcase>')).encode()


def test_missing_cutoff_partial_and_empty_console_never_invent_solver_facts(checkout, tmp_path):
    identity=IDS[0]; log=tmp_path/"console.log";log.write_bytes(b""); xml=tmp_path/"absent.xml"
    out=tmp_path/"out"; digest=v.sha(v.read(checkout/v.PROTOCOL)); publication=v.git(checkout,"rev-parse","HEAD")
    result=v.collect(checkout,digest,identity,124,log,xml,out,publication=publication)
    assert result["feasibilityPassed"] is False and result["externalCutoffObserved"] is True
    assert "actualSolverEntries" not in result and "normalReturns" not in result
    assert result["entryReturnAndClosure"] == "UNKNOWN_WITHOUT_FACTUAL_RECEIPT_AND_HOST_XML"
    assert (out/"console.log").read_bytes() == b""


def test_known_owned_staging_is_raw_only_and_unknown_paths_refuse(checkout, tmp_path):
    identity=IDS[0]; base=checkout/"build/exact-cross-stream"/identity;base.mkdir(parents=True)
    staged=base/".owned-batch-UNIT";staged.mkdir();(staged/(identity+".json")).write_bytes(b'{"unfinished":')
    log=tmp_path/"log";log.write_bytes(b"")
    result=v.collect(checkout,v.sha(v.read(checkout/v.PROTOCOL)),identity,137,log,tmp_path/"absent",tmp_path/"out",
                     publication=v.git(checkout,"rev-parse","HEAD"))
    assert result["feasibilityPassed"] is False and "actualSolverEntries" not in result
    (base/"unrecognized.json").write_bytes(b"{}")
    with pytest.raises(ValueError,match="unexpected"):
        v.collect(checkout,v.sha(v.read(checkout/v.PROTOCOL)),identity,137,log,tmp_path/"absent",tmp_path/"refused",
                  publication=v.git(checkout,"rev-parse","HEAD"))


def test_only_exact_safe_workflow_route_and_single_forced_timed_test():
    workflow=yaml.safe_load(WORKFLOW.read_text());jobs=workflow["jobs"]
    text=WORKFLOW.read_text(); triggers=workflow.get("on",workflow.get(True))
    assert triggers == {"push":{"branches":[v.BRANCH]}}
    assert workflow["permissions"] == {"contents":"read"}
    assert jobs["comparison"]["timeout-minutes"] == 15
    steps=jobs["comparison"]["steps"]
    run=[s["run"] for s in steps if "run" in s]
    compile_index=next(i for i,s in enumerate(run) if "clean testClasses" in s)
    seal_index=next(i for i,s in enumerate(run) if " seal " in s)
    timed_index=next(i for i,s in enumerate(run) if "timeout 360s" in s)
    assert compile_index < seal_index < timed_index
    timed=run[timed_index]
    assert timed.count("timeout 360s") == 1 and timed.count("sh ./gradlew") == 1
    assert "--rerun-tasks" not in timed and "testClasses" not in timed
    for task in ("compileJava","compileTestJava","processResources","processTestResources"):
        assert "-x "+task in timed
    assert "-PdedicatedExperiments=true" in timed and "executesOneFrozenCase" in timed
    assert "code=$?" in timed and timed.index("code=$?") < timed.index(" collect ")
    assert "V022_RUN_EXACT_CROSS_STREAM=true" in timed
    assert "V022_RUN_EXACT_CROSS_STREAM=true" not in "\n".join(run[:timed_index])
    init=(ROOT/v.INIT).read_text()
    assert "rootProject.tasks.named('test', org.gradle.api.tasks.testing.Test)" in init
    assert "outputs.upToDateWhen { false }" in init
    assert not any(s in init for s in ("classpath", "includeTags", "excludeTags", "inputs.property"))
    upload=next(s for s in steps if s.get("uses","").startswith("actions/upload-artifact"))["with"]["path"].splitlines()
    assert all(p.startswith("build/exact-cross-stream-upload/${{ matrix.case }}/") for p in upload)
    assert not any("**" in p for p in upload)
    assert "workflow_dispatch" not in text and "pull_request" not in text
    assert "No tests found for given includes" in text
    assert "dedicatedExperiments must be true or false" in text


@pytest.mark.parametrize("stage", ["write", "close"])
def test_write_or_close_failure_rolls_back_owned_batch(tmp_path, monkeypatch, stage):
    original=Path.open
    class Failing:
        def __init__(self, stream): self.stream=stream
        def __enter__(self): return self
        def write(self, data):
            self.stream.write(data[:1])
            if stage == "write": raise OSError("UNIT primary write")
            self.stream.write(data[1:])
        def __exit__(self, kind, value, traceback):
            self.stream.close()
            if kind is None and stage == "close": raise OSError("UNIT primary close")
            return False
    def opening(path, *args, **kwargs):
        stream=original(path,*args,**kwargs)
        return Failing(stream) if path.name == "one.json" else stream
    monkeypatch.setattr(Path,"open",opening)
    with pytest.raises(OSError,match="UNIT primary "+stage):
        v.atomic_directory(tmp_path/"out",{"one.json":b"{}","two.json":b"{}"})
    assert not list(tmp_path.iterdir())


def _utf(text):
    data=text.encode();return struct.pack(">H",len(data))+data


def complete_unit_evidence(root, identity):
    """Generate UNIT-ONLY producer-shaped evidence, never fixed-source outcomes.

    All 57 rows and complete binary cell/marginal layouts exercise the decoder;
    no image, profile factory, optimizer, actual route or quality measurement is
    generated. A deliberately failing geometry verdict remains report data.
    """
    def unit_pins(p):
        for row in p["baselineInputs"]:
            row.update(originalPairInventory=220,observedForwardBackwardScores=16,modes=57)
    protocol=rewrite_protocol(root,unit_pins);digest=v.sha(v.read(root/v.PROTOCOL));publication=v.git(root,"rev-parse","HEAD")
    compiled_fixture(root)
    base=root/"build/exact-cross-stream"/identity;base.mkdir(parents=True)
    chunks=[];cells=[];points=[];bits=[];states=[];counts=[]
    for row in range(57):
        offsets=[0] if row in (0,56) else [-25,25];counts.append(len(offsets));states.append(0)
        points.append([float(row),float(offsets[0])]);bits.append([format(struct.unpack(">Q",struct.pack(">d",x))[0],"x") for x in points[-1]])
        for state,offset in enumerate(offsets):
            fields=[float(row),float(row),0.,0.,1.,float(offset),1.,float(row),float(offset),0.,0.,1.]
            cell=struct.pack(">ii12dB",row,state,*fields,1 if row in (0,56) else 0)+_utf("SOURCE")+b"\0"+_utf("UNIT")+_utf("MISSING")+struct.pack(">dii",0.,0,1)+struct.pack(">d",1.)
            cells.append(cell)
    for ordinal,start in enumerate(range(0,len(cells),64)):
        segment=cells[start:start+64];data=b"CSG1"+struct.pack(">i",len(segment))+b"".join(struct.pack(">i",len(c))+c for c in segment)
        name="graph-"+identity+"-%05d.bin"%ordinal;(base/name).write_bytes(data)
        chunks.append(dict(file=name,cells=len(segment),bytes=len(data),sha256=v.sha(data)))
    manifest=dict(format="cross-stream-graph-big-endian-raw-binary64-v1",profiles=57,cells=len(cells),chunks=chunks,
                  retainedBytes=sum(c["bytes"] for c in chunks),graphBytesSha256=v.sha(b"".join((base/c["file"]).read_bytes() for c in chunks)))
    (base/("graph-manifest-"+identity+".json")).write_bytes(v.canonical(manifest))
    posterior=[]
    for kind in ("position","component","forward","backward"):
        data=b"CSP1"+_utf(kind)+struct.pack(">i",57)
        for count in ([1]*57 if kind=="component" else counts):data+=struct.pack(">i",count)+struct.pack(">"+"d"*count,*([1.]*count))
        name="posterior-"+identity+"-"+kind+".bin";(base/name).write_bytes(data);posterior.append(dict(file=name,bytes=len(data),sha256=v.sha(data)))
    route_name="route-"+identity+"-0.json"
    (base/route_name).write_bytes(v.canonical(dict(states=states,points=points,pointBits=bits,truthOracle=dict(available=True,passed=False,oracle="UNIT_ONLY_NOT_A_NUMERICAL_RESULT"))))
    pin=next(p for p in protocol["baselineInputs"] if p["case"]==identity)
    receipt={key:pin[key] for key in ("snapshotHash","factsAndLatticeHash","profiles","modes","originalPairInventory")}
    receipt.update(case=identity,protocolSha256=digest,comparisonIdentity=v.IDENTITY,actualCheckoutCommit=publication,
                   reviewedCodeCommit=protocol["reviewedCodeCommit"],sourceClosureSha256=protocol["sourceClosureSha256"],
                   actualSolverEntries=1,normalReturns=1,pipelineComplete=True,deadlineExpired=False,safeCause=None,failurePhase=None,
                   peakBytes=34*1024**2,metadataBytesStillFundedAtPublication=1024**2,remainingNonMetadataBytesAtPublication=0,
                   admissionCoarsened=False,fullTwentyFiveMetreDomain=True,graph=manifest,posteriorFiles=posterior,routeFiles=[route_name],
                   returnedRouteCount=1,routeDataAvailable=True,modelQualified=False,
                   solver=dict(status="COMPLETE",posteriorUsable=True,rawCount=1,distinctCount=1,completion={},
                     work=dict(executionPolicy=v.VERSION,forwardBackwardScores=16,kBestScores=4,logicalKBestExtensions=8,refusedScoreAttempts=0),
                     boundWork={key:(3_200_000_008 if key=="boundUnits" else 0) for key in v.METRICS}))
    (base/(identity+".json")).write_bytes(v.canonical(receipt));(base/"phase.json").write_bytes(v.canonical(dict(case=identity,terminal=True)))
    (base/"summary.json").write_bytes(v.canonical(dict(recordedCases=[identity])))
    return base,receipt,digest,publication


def test_complete_unit_only_container_retains_geometry_fail_and_long_metrics(checkout,tmp_path):
    identity=IDS[0];base,receipt,digest,publication=complete_unit_evidence(checkout,identity)
    log=tmp_path/"log";log.write_bytes(b"");xml=tmp_path/"host.xml";xml.write_bytes(xml_bytes())
    output=tmp_path/"out";result=v.collect(checkout,digest,identity,0,log,xml,output,publication=publication)
    assert result["feasibilityPassed"] is True and result["geometryOracle"]["passed"] is False
    assert result["modelQualified"] is False and result["solver"]["boundWork"]["boundUnits"]==3_200_000_008
    assert (output/("route-"+identity+"-0.json")).read_bytes()==(base/("route-"+identity+"-0.json")).read_bytes()
    assert result["entryReturnAndClosure"]=="FACTUAL_RESULT_PLUS_EXACT_PASSING_HOST_POSTCLOSE_ASSERTION"


@pytest.mark.parametrize("bad", ["counter_bool","graph_hash","posterior_hash","route_bits","partial","exit","xml","resource","missing","extra"])
def test_factual_failure_and_contradictions_cannot_pass(checkout,tmp_path,bad):
    identity=IDS[0];base,receipt,digest,publication=complete_unit_evidence(checkout,identity)
    log=tmp_path/"log";log.write_bytes(b"");xml=tmp_path/"host.xml";xml.write_bytes(xml_bytes());exit_code=0;refuse=False
    if bad=="counter_bool":receipt["normalReturns"]=True;refuse=True
    elif bad=="graph_hash":(base/receipt["graph"]["chunks"][0]["file"]).write_bytes(b"MUTATED");refuse=True
    elif bad=="posterior_hash":(base/receipt["posteriorFiles"][0]["file"]).write_bytes(b"MUTATED");refuse=True
    elif bad=="route_bits":
        route=base/receipt["routeFiles"][0];value=v.document(v.read(route));value["pointBits"][1][0]="0";route.write_bytes(v.canonical(value));refuse=True
    elif bad=="partial":(base/"phase.json.partial").write_bytes(b'{"unfinished":')
    elif bad=="exit":exit_code=124
    elif bad=="xml":xml.write_bytes(xml_bytes(False))
    elif bad=="resource":receipt.update(pipelineComplete=False,safeCause="RESOURCE_LIMIT");receipt["solver"]["status"]="RESOURCE_LIMIT"
    elif bad=="missing":(base/(identity+".json")).unlink()
    else:(base/"surplus.json").write_bytes(b"{}");refuse=True
    if bad!="missing":(base/(identity+".json")).write_bytes(v.canonical(receipt))
    if refuse:
        with pytest.raises((ValueError,KeyError)):
            v.collect(checkout,digest,identity,exit_code,log,xml,tmp_path/"out",publication=publication)
        assert not (tmp_path/"out").exists()
    else:
        result=v.collect(checkout,digest,identity,exit_code,log,xml,tmp_path/"out",publication=publication)
        assert result["feasibilityPassed"] is False and "geometryOracle" not in result
