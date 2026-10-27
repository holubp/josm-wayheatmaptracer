#!/usr/bin/env python3
"""Unattended public quality experiment; no OpenAI calls or release operations.

Compile outside attempts, authenticate a portable runner, and start one bounded
JVM per attempt. Missing results remain inconclusive, never measured zero.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import time

ROOT=Path(__file__).resolve().parents[1]
PACKAGE="org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic."
BASELINE="117baaf09f4b81a38ad4679eedc8fd1b12ed3d2a"
BASELINE_SUPPORT_SHA256="276f59cc981c9ef34737de8c87783f1f7bddf252ae5ebaf980966a08bfcaaffa"
BASELINE_PROBE_SHA256="3a6c34ac20d00b83065b6a73bec047c4ae1f61ba6834dcebf6676c9612ad0b11"


def module(name,path):
    spec=importlib.util.spec_from_file_location(name,path)
    result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result


GUARD=module("feasibility_guard",ROOT/"scripts/verify-b-shared-feasibility.py")
PROCESS=module("historical_bounded_process",ROOT/"scripts/run-b-historical-native-comparison.py")


def sha(path):
    digest=hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda:stream.read(65536),b""):digest.update(block)
    return digest.hexdigest()


def safe_relative(name):
    """Return a bounded relative artifact name, rejecting traversal/aliases."""
    p=PurePosixPath(name)
    if not name or len(name)>300 or p.is_absolute() or "\\" in name or ":" in name or ".." in p.parts or str(p)!=name:
        raise ValueError("Unsafe artifact path")
    return p


def read_json(path,limit=1048576):
    """Read bounded finite JSON with duplicate-key refusal."""
    path=Path(path)
    if path.is_symlink() or not path.is_file() or not 0<path.stat().st_size<=limit:raise ValueError("Bounded regular JSON required")
    def pairs(items):
        result={}
        for k,v in items:
            if k in result:raise ValueError("Duplicate JSON key")
            result[k]=v
        return result
    def invalid(unused):raise ValueError("Nonfinite JSON")
    return json.loads(path.read_text(),object_pairs_hook=pairs,parse_constant=invalid)


def write_json(path,value):
    """Atomically create an owned receipt; an existing leaf is never replaced."""
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    raw=(json.dumps(value,sort_keys=True,separators=(",",":"),allow_nan=False)+"\n").encode()
    if len(raw)>1048576:raise ValueError("Receipt limit")
    temporary=path.with_name(path.name+".partial");owned=False;reserved=False
    try:
        with temporary.open("xb") as stream:owned=True;stream.write(raw)
        # Claim the destination exclusively before replacing our own empty
        # sentinel. Empty bytes are not a valid receipt. This works on Android
        # executors without os.link and never replaces a pre-existing leaf.
        with path.open("xb"):reserved=True
        os.replace(temporary,path);owned=False;reserved=False
    finally:
        if owned:temporary.unlink()
        if reserved and path.is_file() and path.stat().st_size==0:path.unlink()


def verify_bundle(root,external_sha):
    """Authenticate every byte against a manifest bound outside the artifact."""
    root=Path(root)
    if root.is_symlink() or sha(root/"manifest.json")!=external_sha:raise ValueError("External runner manifest mismatch")
    manifest=read_json(root/"manifest.json",4<<20);files=manifest["files"]
    actual=set();total=0
    for path in root.rglob("*"):
        if path.is_symlink():raise ValueError("Runner links forbidden")
        if path.is_file():
            relative=path.relative_to(root).as_posix()
            if relative=="manifest.json":continue
            safe_relative(relative);actual.add(relative);size=path.stat().st_size;total+=size
            if size>64<<20 or len(actual)>5000 or total>128<<20:raise ValueError("Runner artifact bounds")
    if actual!=set(files):raise ValueError("Runner inventory differs")
    for name,expected in files.items():
        safe_relative(name);path=root/name
        if path.stat().st_size!=expected["bytes"] or sha(path)!=expected["sha256"]:raise ValueError("Runner member changed")
    return manifest


def run_process(command,prefix,seconds=360,env=None,binding=None,receipt_file=None):
    """Run once; host failure/cutoff evidence never claims a native completion."""
    prefix=Path(prefix);prefix.parent.mkdir(parents=True,exist_ok=True)
    start=time.monotonic();result=dict(processCompleted=False,nativeInvocations=None)
    # The reused watchdog does not interpret or grant the old engine protocol.
    old_env={}
    if env:
        for key,value in env.items():old_env[key]=os.environ.get(key);os.environ[key]=str(value)
    try:
        PROCESS.run_bounded(command,str(prefix)+".log",seconds)
        result.update(status="PROCESS_RETURNED",processCompleted=True,processExit=0)
    except subprocess.TimeoutExpired:result.update(status="EXTERNAL_CUTOFF",processExit=None)
    except subprocess.CalledProcessError as failure:result.update(status="PROCESS_FAILURE",processExit=failure.returncode)
    except ValueError:result.update(status="LOG_OR_EXPORT_FAILURE",processExit=None)
    finally:
        for key,value in old_env.items():
            if value is None:os.environ.pop(key,None)
            else:os.environ[key]=value
    result["hostElapsedSeconds"]=time.monotonic()-start
    if binding is not None:result["binding"]=binding
    if receipt_file is not None and Path(receipt_file).is_file():result["receiptSha256"]=sha(receipt_file)
    write_json(str(prefix)+".host.json",result);return result


def classpath(root,baseline=False):
    root=Path(root).resolve();dirs=["baseline-adapter","baseline-main"] if baseline else ["current-test","current-main","resources"]
    return os.pathsep.join(str(root/p) for p in dirs)+os.pathsep+os.pathsep.join(str(p) for p in sorted((root/"libs").glob("*.jar")))


def project_baseline_support(destination):
    """Project only the authenticated exploratory observer; preserve old authority."""
    source=(ROOT/"scripts/historical-b-native/HistoricalNativeB.java").read_bytes()
    if hashlib.sha256(source).hexdigest()!=BASELINE_SUPPORT_SHA256:raise ValueError("Historical support source changed")
    begin=b"    static final class Probe implements CancellationProbe {"
    end=b"    static void require"
    if source.count(begin)!=1 or source.count(end)!=1:raise ValueError("Probe boundaries changed")
    first=source.index(begin);last=source.index(end,first)
    if hashlib.sha256(source[first:last]).hexdigest()!=BASELINE_PROBE_SHA256:raise ValueError("Historical probe changed")
    replacement=(ROOT/"scripts/historical-b-native/ExploratoryProbe.java.inc").read_bytes()
    generated=source[:first]+replacement+source[last:]
    destination=Path(destination);destination.parent.mkdir(parents=True,exist_ok=True)
    with destination.open("xb") as stream:stream.write(generated)
    write_json(destination.with_suffix(".projection.json"),dict(originalSha256=BASELINE_SUPPORT_SHA256,
        originalProbeSha256=BASELINE_PROBE_SHA256,replacementSha256=hashlib.sha256(replacement).hexdigest(),
        generatedSha256=hashlib.sha256(generated).hexdigest(),prefixUnchanged=True,suffixUnchanged=True))
    return destination


def prepare(classpath_file,baseline_checkout,output):
    """Package compiled public classes and generate neutral inputs once in CI."""
    if os.environ.get("CI")!="true":raise ValueError("CI-only preparation")
    baseline_checkout=Path(baseline_checkout).resolve();output=Path(output).resolve()
    old=subprocess.check_output(["git","-C",str(baseline_checkout),"rev-parse","HEAD"],text=True).strip()
    if old!=BASELINE:raise ValueError("Baseline checkout changed")
    output.mkdir()
    for name,source in (("current-main",ROOT/"build/classes/java/main"),("current-test",ROOT/"build/classes/java/test"),("baseline-main",baseline_checkout/"build/classes/java/main")):
        shutil.copytree(source,output/name)
    for name in ("b-observed-gap","b-shared-feasibility"):
        shutil.copytree(ROOT/"src/test/resources"/name,output/"resources"/name)
    (output/"libs").mkdir()
    for raw in Path(classpath_file).read_text().strip().split(os.pathsep):
        path=Path(raw)
        if path.suffix==".jar":
            target=output/"libs"/(sha(path)+".jar")
            if not target.exists():shutil.copyfile(path,target)
    (output/"baseline-adapter").mkdir()
    projected=project_baseline_support(output/"generated/HistoricalNativeB.java")
    subprocess.run(["javac","--release","17","-cp",classpath(output,True),"-d",str(output/"baseline-adapter"),
        str(projected),str(ROOT/"scripts/historical-b-native/ExploratoryBaselineB.java"),
        str(ROOT/"scripts/historical-b-native/BaselineProbeRegression.java")],check=True)
    # No model invocation: verify the actual isolated adapter's capture guard,
    # low-overhead engine observer and unchanged cancellation/deadline behavior.
    subprocess.run(["java","-cp",classpath(output,True),"BaselineProbeRegression"],check=True,timeout=30,stdout=subprocess.DEVNULL)
    exported=run_process(["java","-Xmx512m","-cp",classpath(output),PACKAGE+"BSharedFeasibilityTool","export",str(output/"neutral")],output/"export",
        env={"V022_RUN_SHARED_FEASIBILITY":"true"})
    if not exported["processCompleted"]:raise ValueError("Neutral preparation did not complete")
    protocol=read_json(output/"resources/b-shared-feasibility/protocol.json");GUARD.require_baseline_launch(protocol)
    files={}
    for path in sorted(output.rglob("*")):
        if path.is_file():files[path.relative_to(output).as_posix()]={"bytes":path.stat().st_size,"sha256":sha(path)}
    commit=subprocess.check_output(["git","-C",str(ROOT),"rev-parse","HEAD"],text=True).strip()
    write_json(output/"manifest.json",dict(schema=1,evidenceClass="EXPLORATORY_QUALITY_ONLY_V1",codeCommit=commit,baselineCommit=BASELINE,files=files))
    digest=sha(output/"manifest.json");verify_bundle(output,digest)
    index=read_json(output/"neutral/index.json")
    write_json(ROOT/"build/shared-feasibility-context.json",dict(codeCommit=commit,runnerManifestSha256=digest,
        protocolSha256=sha(output/"resources/b-shared-feasibility/protocol.json"),
        snapshotHashes={key:value["snapshotHash"] for key,value in index["captures"].items()}))
    return digest


def expected_binding(context,case,setting=None):
    if context is None:return None
    value=dict(caseId=case,role="baseline" if setting is None else "candidate",codeCommit=context["codeCommit"],
        runnerManifestSha256=context["runnerManifestSha256"],protocolSha256=context["protocolSha256"],snapshotHash=context["snapshotHashes"][case])
    if setting is not None:value.update(gain=setting[0],eta=setting[1])
    return value


def attempt(root,manifest_sha,case,output,setting=None):
    """Launch one authenticated isolated baseline or candidate, with no retry."""
    root=Path(root).resolve();output=Path(output).resolve();manifest=verify_bundle(root,manifest_sha)
    if manifest.get("codeCommit")!=os.environ.get("GITHUB_SHA"):raise ValueError("Runner code revision differs")
    protocol=read_json(root/"resources/b-shared-feasibility/protocol.json");GUARD.require_baseline_launch(protocol)
    if case not in protocol["cases"] or output.exists():raise ValueError("Invalid or existing attempt namespace")
    env={"CI":"true","V022_RUN_SHARED_FEASIBILITY":"true","SHARED_FEASIBILITY_INPUTS":str(root/"neutral")}
    if setting is None:
        command=["java","-Xmx512m","-cp",classpath(root,True),"ExploratoryBaselineB",str(root/"neutral"),case,str(output),classpath(root)]
    else:
        if tuple(setting) not in GUARD.settings(protocol):raise ValueError("Unregistered setting")
        command=["java","-Xmx512m","-cp",classpath(root),PACKAGE+"BSharedFeasibilityTool","candidate",case,str(setting[0]),str(setting[1]),str(output)]
    index=read_json(root/"neutral/index.json")
    context=dict(codeCommit=manifest["codeCommit"],runnerManifestSha256=manifest_sha,
        protocolSha256=sha(root/"resources/b-shared-feasibility/protocol.json"),
        snapshotHashes={key:value["snapshotHash"] for key,value in index["captures"].items()})
    host=run_process(command,output,env=env,binding=expected_binding(context,case,setting),
        receipt_file=output/("checked.json" if setting is None else "receipt.json"))
    return host


def assessed(directory,baseline=False,expected=None):
    """Summarize actual receipts; incomplete host/quality evidence stays unknown."""
    directory=Path(directory);leaf="checked.json" if baseline else "receipt.json"
    if expected is None:return dict(completed=False,hardPassed=False,reason="UNBOUND_EVIDENCE")
    if not Path(str(directory)+".host.json").is_file() or not (directory/leaf).is_file():return dict(completed=False,hardPassed=False,reason="MISSING_EVIDENCE")
    host=read_json(str(directory)+".host.json");receipt=read_json(directory/leaf)
    if host.get("binding")!=expected or host.get("receiptSha256")!=sha(directory/leaf):raise ValueError("Attempt identity or receipt bytes differ")
    if (receipt.get("caseId")!=expected["caseId"] or ("snapshotHash" in receipt and receipt["snapshotHash"]!=expected["snapshotHash"])
            or receipt.get("evidenceClass")!="EXPLORATORY_QUALITY_ONLY_V1"):raise ValueError("Receipt case/input/evidence identity differs")
    if baseline:
        if (receipt.get("baselineSourceCommit")!=BASELINE
                or receipt.get("exactHistoricalTestedBinaryClaim") is not False):raise ValueError("Baseline identity differs")
    elif (receipt.get("gain")!=expected["gain"] or receipt.get("eta")!=expected["eta"]
            or receipt.get("sourceRevision")!=expected["codeCommit"]):raise ValueError("Candidate setting/source differs")
    quality=receipt.get("quality",{})
    complete=(host.get("processCompleted") is True and receipt.get("status")=="MODEL_RETURNED_ROUTE"
              and receipt.get("snapshotHash")==expected["snapshotHash"]
              and (not baseline or receipt.get("inputEquivalent") is True)
              and receipt.get("solverInvocations")==1 and receipt.get("trackedBytesAfterClose")==0
              and quality.get("available") is True)
    result=dict(completed=complete,hardPassed=complete and quality.get("passed") is True,
        reason=receipt.get("status"),receipt=receipt,hostElapsedSeconds=host.get("hostElapsedSeconds"))
    if complete and receipt.get("reference"):
        result["centering"]=GUARD.centering_metrics(receipt["reference"],receipt["returned"],receipt["nativePitchMeters"])
    return result


def suitability(protocol,root,output,context=None):
    """Freeze demonstrated sparse baseline failures before any candidate outcome."""
    rows=[]
    for item in protocol["screening"]:
        row=assessed(Path(root)/item["id"],True,expected_binding(context,item["id"]));row["caseId"]=item["id"];rows.append(row)
    result=GUARD.suitability(protocol,rows);result["rows"]=rows;write_json(output,result);return result


def setting_verdict(rows):
    """A demonstrated failure is decisive; missing evidence cannot erase it."""
    if any(r["completed"] and (not r["hardPassed"] or r.get("strongComparisonPassed") is False) for r in rows):return "REJECT"
    if any(not r["completed"] or r.get("metricRequired") and not r.get("centering") for r in rows):return "INCONCLUSIVE"
    return "ELIGIBLE"


def family_verdict(dispositions):
    """Return rejection only when no setting remains potentially eligible."""
    if "ELIGIBLE" in dispositions:return "FROZEN_CANDIDATE"
    return "INCONCLUSIVE" if "INCONCLUSIVE" in dispositions else "REJECT"


def select(protocol,baseline_root,candidate_root,output,context=None):
    """Select one fixed exploratory configuration, never a confirmation runner-up."""
    base={x["id"]:assessed(Path(baseline_root)/x["id"],True,expected_binding(context,x["id"])) for x in protocol["screening"]}
    suitability_rows=[dict(caseId=x["id"],completed=base[x["id"]]["completed"],hardPassed=base[x["id"]]["hardPassed"]) for x in protocol["screening"]]
    if GUARD.suitability(protocol,suitability_rows)["status"]!="SUITABLE":
        result=dict(status="INCONCLUSIVE",reason="BASELINE_SUITABILITY_NOT_ESTABLISHED",selected=None,settings=[])
        write_json(output,result);return result
    results=[];eligible=[]
    for gain,eta in GUARD.settings(protocol):
        score=[];strong=[];passed=True;rows=[]
        for item in protocol["screening"]:
            row=assessed(Path(candidate_root)/item["id"]/f"gain-{gain}-eta-{protocol['etas'].index(eta)}",expected=expected_binding(context,item["id"],(gain,eta)))
            row["caseId"]=item["id"]
            row["metricRequired"]=item["category"]=="intense" or item["id"] in protocol["quality"]["sparseNormalizationMeters"]
            if item["category"]=="intense":
                a=row.get("centering");b=base[item["id"]].get("centering")
                row["strongComparisonPassed"]=None if not a or not b else a["meanMeters"]<=b["meanMeters"]+.25 and a["p95Meters"]<=b["p95Meters"]+.5
                if a:strong.append(a["meanMeters"])
            elif row.get("centering") and item["id"] in protocol["quality"]["sparseNormalizationMeters"]:
                score.append(row["centering"]["meanMeters"]/protocol["quality"]["sparseNormalizationMeters"][item["id"]])
            rows.append({k:v for k,v in row.items() if k!="receipt"})
        disposition=setting_verdict(rows);passed=disposition=="ELIGIBLE"
        entry=dict(gain=gain,eta=eta,passed=bool(passed),disposition=disposition,rows=rows);results.append(entry)
        if passed:eligible.append((max(score,default=0),sum(strong)/len(strong),gain,eta))
    choice=min(eligible) if eligible else None
    result=dict(status=family_verdict([r["disposition"] for r in results]),settings=results,
        selected=None if choice is None else dict(gain=choice[2],eta=choice[3]),confirmationUsedForSelection=False,context=context)
    write_json(output,result);return result


def main():
    parser=argparse.ArgumentParser(description=__doc__);sub=parser.add_subparsers(dest="command",required=True)
    p=sub.add_parser("prepare");p.add_argument("classpath");p.add_argument("baseline");p.add_argument("output")
    for mode in ("baseline","screening"):
        p=sub.add_parser(mode);p.add_argument("bundle");p.add_argument("manifest_sha");p.add_argument("case");p.add_argument("output")
    for mode in ("suitability","select"):
        p=sub.add_parser(mode);p.add_argument("baseline");p.add_argument("output")
        if mode=="select":p.add_argument("candidates")
        p.add_argument("--context",required=True);p.add_argument("--context-sha",required=True)
    args=parser.parse_args();protocol=read_json(ROOT/"src/test/resources/b-shared-feasibility/protocol.json")
    if args.command=="prepare":print(prepare(args.classpath,args.baseline,args.output))
    elif args.command=="baseline":attempt(args.bundle,args.manifest_sha,args.case,args.output)
    elif args.command=="screening":
        for gain,eta in GUARD.settings(protocol):attempt(args.bundle,args.manifest_sha,args.case,Path(args.output)/f"gain-{gain}-eta-{protocol['etas'].index(eta)}",(gain,eta))
    else:
        if sha(args.context)!=args.context_sha:raise ValueError("External context hash mismatch")
        context=read_json(args.context)
        if context.get("codeCommit")!=os.environ.get("GITHUB_SHA"):raise ValueError("Context code revision mismatch")
        if context.get("protocolSha256")!=sha(ROOT/"src/test/resources/b-shared-feasibility/protocol.json"):raise ValueError("Context protocol mismatch")
        if args.command=="suitability":print(suitability(protocol,args.baseline,args.output,context)["status"])
        elif args.command=="select":print(select(protocol,args.baseline,args.candidates,args.output,context)["status"])


if __name__=="__main__":main()
