"""Authenticate and stage the public historical32 launch; never run an engine.

The reviewed workflow pins the manifest, and the manifest pins this verifier,
driver, protocol and unchanged Java trees. Local self-consistent hashes are not
review authority. Execution mode always refuses pending approval.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("historical_driver", ROOT / "scripts/run-b-historical-native-comparison.py")
driver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)
BRANCH = "codex/b-historical-native-comparison-20261020"
MANIFEST = "src/test/resources/b-observed-gap/historical-native-production-comparison.json"
PROTOCOL = "src/test/resources/b-observed-gap/historical-native-production-protocol.json"
NATIVE = "src/test/resources/b-observed-gap/native-production-default-comparison.json"
PENDING = "PENDING_REVIEW_AND_CONTROLLER_FREEZE"
APPROVED = "APPROVED_AFTER_INDEPENDENT_REVIEW_AND_CONTROLLER_RULING"
OLD_TREE = "a701c0bb3fe6e213f4aa44d12763863f209d6a32"
JAR_SHA = "3afa6435ea696da4a76416d1907aa821511584ac61b89613143666d67e6b7a29"
SOURCES = {"scripts/run-b-historical-native-comparison.py", "scripts/historical-b-native/HistoricalNativeB.java",
           "scripts/verify-b-historical-launch.py", "scripts/verify-historical-benchmark-receipts.py"}
PACKAGE = "src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/"
SOURCES |= {PACKAGE + name + ".java" for name in ("BNativeNeutralFixtureExporter","BNativeHistoricalPointComparison","BNativePointAcceptance",
    "BNativeProductionDefaultComparison","BNativeProductionComparison","V022BGapMatchedControlsTest","BIdentificationReceipt",
    "BThinStrandFixtures","BThinStrandAcceptance","BThinSwitchbackFixtures","BThinSwitchbackAcceptance","BSameTrailRecordingFixtures",
    "BSeparatedSameTrailFixtures","BGapFullRouteAcceptance","BGapTestFixture")}
SOURCES |= {"src/test/resources/b-observed-gap/"+name for name in ("matched-controls.json","thin-strand-controls.json",
    "thin-switchback-controls.json","same-trail-recordings.json","same-trail-separated.json","source-contract.json")}
TREES = {"src/main/java", "src/test/java"}
LIMITS = {"attemptBytes":268435456, "sourceBytes":33554432, "reportBytes":2097152,
          "cooperativeSeconds":300, "externalSeconds":360, "jobMinutes":15, "maxParallel":2,
          "leafBytes":65536, "aggregateBytes":2147483648, "archiveBytes":4194304,
          "nativePolicy":[96,8000000,128000000,32,8]}
CAPTURE_KEYS = {"scope","historicalCommit","archiveSha256","indexSha256","nativeContractSha256",
                "cases","members","captures","sourceCommit"}
ENGINE_KEYS = {"approval","engineEligible","diagnosticParameterSha256","nativePolicy","ledgerLimitBytes"}
MANIFEST_KEYS = {"schema","scope","approval","implementationCommit","historical","identities","josm19555Sha256",
                 "protocolSha256","archiveSha256","indexSha256","nativeContractSha256","cases","sourceFiles","javaTrees",
                 "limits","branch","promotionContract","modelQualified"}
LEAVES = {"binding.json","result.json","ownership.json","phase.json"} | {"route-%d.json" % i for i in range(8)}
RAW_LEAVES = LEAVES | {name + ".partial" for name in LEAVES}
POINT_LEAVES = {"result.json","ownership.json"} | {"route-%d.json" % i for i in range(8)}

def demand(condition, reason):
    """Raise ValueError when a fixed, fail-closed contract is not met."""
    if not condition:
        raise ValueError(reason)

def require_approval(manifest, protocol):
    """Require reviewed promotion without modifying any source or native policy.

    Args:
        manifest: Parsed externally pinned launch metadata.
        protocol: Parsed externally pinned exact-schema engine protocol.
    Returns:
        None; approval is checked, never issued.
    Raises:
        ValueError: Approval or the original native policy/identity differs.
    """
    demand(manifest.get("approval") == APPROVED, "launch approval is pending")
    driver.require_engine_protocol(protocol)

def verify(root, expected, execution=False, branch=BRANCH, publication_commit=None, archive=None):
    """Validate source/data bindings; execution additionally requires promotion.

    Args:
        root: Current checkout containing the reviewed driver and checker.
        expected: Independent manifest digest from the reviewed workflow.
        execution: Refuse pending metadata and require the actual input archive.
        branch: Actual GitHub branch context, checked against the fixed route.
        publication_commit: Actual GitHub.sha, distinct from implementationCommit.
        archive: Optional detached neutral ZIP; mandatory for execution.
    Returns:
        dict: Bound manifest, never a model qualification or permission receipt.
    Raises:
        ValueError: Any pin, source, schema, policy, data or context differs.
        OSError: Reading bounded source/data metadata fails.
    """
    root = Path(root)
    demand(branch == BRANCH, "launch branch")
    raw = driver.bounded(root / MANIFEST, 65536)
    demand(re.fullmatch("[0-9a-f]{64}", expected or "") and driver.sha(raw) == expected, "external manifest digest")
    m = driver.metadata(raw)
    demand(set(m) == MANIFEST_KEYS and raw == driver.canonical(m), "canonical manifest schema")
    demand(m["schema"] == "historical-native-public32-launch-v1" and
           m["scope"] == "HISTORICAL_NATIVE_ENGINE_BOUNDARY_ONLY_NOT_MODEL_QUALIFICATION" and m["modelQualified"] is False, "manifest purpose")
    demand(m["approval"] in (PENDING, APPROVED), "manifest approval")
    demand(m["implementationCommit"] == "92eefbbb85d45ed0a0540fa27dacd6f95fee8767", "implementation source")
    demand(m["historical"]["commit"] == driver.HISTORICAL and m["historical"]["tree"] == OLD_TREE, "historical source")
    demand(m["cases"] == list(driver.IDS) and m["limits"] == LIMITS and m["branch"] == BRANCH, "fixed cases/policy/limits/branch")
    demand(m["promotionContract"] == "ONLY_APPROVAL_AND_ENGINE_ELIGIBLE_PLUS_DEPENDENT_DIGESTS", "promotion contract")
    demand(m["josm19555Sha256"] == JAR_SHA and m["nativeContractSha256"] == driver.NATIVE_SHA, "native dependency/contract")
    demand(set(m["sourceFiles"]) == SOURCES and set(m["javaTrees"]) == TREES, "source inventory")
    for name, digest in m["sourceFiles"].items():
        demand(driver.sha(driver.bounded(root / name, 2 * 1024 * 1024)) == digest, "source member changed")
    for name, digest in m["javaTrees"].items():
        demand(driver.tree_sha(root / name) == digest, "Java source tree changed")
    demand(driver.sha(driver.bounded(root / NATIVE, 65536)) == driver.NATIVE_SHA, "frozen native inputs")
    p_raw = driver.bounded(root / PROTOCOL, 65536)
    demand(driver.sha(p_raw) == m["protocolSha256"], "protocol digest")
    p = driver.metadata(p_raw)
    demand(set(p) == CAPTURE_KEYS | ENGINE_KEYS and p_raw == driver.canonical(p), "canonical protocol schema")
    demand(p["scope"] == driver.ENGINE_SCOPE and p["historicalCommit"] == driver.HISTORICAL and
           p["sourceCommit"] == "5b9abca91f207ab50736a6e506fd7a616881d20c" and p["cases"] == list(driver.IDS), "protocol source/purpose")
    for key in ("archiveSha256","indexSha256","nativeContractSha256"):
        demand(p[key] == m[key], "protocol data binding")
    demand(p["nativePolicy"] == LIMITS["nativePolicy"] and p["ledgerLimitBytes"] == LIMITS["attemptBytes"] and
           p["diagnosticParameterSha256"] == driver.DIAGNOSTIC_SHA, "protocol policy")
    demand((p["approval"], p["engineEligible"]) in ((PENDING, False), (APPROVED, True)) and
           p["approval"] == m["approval"], "protocol approval")
    demand(set(p["members"]) == driver.MEMBERS - {"index.json"} and set(p["captures"]) == set(driver.IDS), "protocol exact inventory")
    native = driver.metadata(driver.bounded(root / NATIVE, 65536))
    for case in driver.IDS:
        original = native["nativeInputs"][case]
        demand(p["members"][case+"/scalar.f64"]["sha256"] == original["rasterSha256"] and
               p["members"][case+"/validity.bin"]["sha256"] == original["validitySha256"], "original native input")
        capture = p["captures"][case]
        demand(capture["snapshotHash"] == original["snapshotHash"] and capture["width"] == original["outputWidth"]
               and capture["height"] == original["outputHeight"], "original capture phase/snapshot")
    identity = m["identities"]
    demand(identity["scope"] == "METADATA_ONLY_NOT_A_REQUEST_OR_ENGINE_EXECUTION" and
           identity["diagnosticDefaultsToStringSha256"] == driver.DIAGNOSTIC_SHA and
           identity["liveParameterIdentitySha256"] == "09fedef1d2807dd8716d6ae7154a11eb7b326fc35038b81497ee2278e2f35c2c", "diagnostic metadata identity")
    demand(identity["visibleLivePrefix"] == "live-probabilistic-v2" and identity["managedLivePrefix"] == "managed-live-probabilistic-v2" and
           identity["numericalDefaults"] == {"dataWeight":1.0,"centerWeight":1.0,"turnWeight":2.0,"orientationWeight":0.5,
             "guideWeight":0.5,"turnScaleRadians":0.5235987755982988,"temperature":1.0,"ambiguityEnergyDelta":2.0}, "old numerical defaults metadata")
    demand(identity["visibleLiveParameterHash"] == "ce865e7ca52c00402f1544e7578619dbe09ebd098306eb1e36f14693f06f53a7" and
           identity["managedLiveParameterHash"] == "9814307c79fae2c8ec105c755c7373694d3c4e893be78a747062511361241b35", "historical live identities")
    if publication_commit is not None:
        demand(re.fullmatch("[0-9a-f]{40}", publication_commit) is not None and
               driver.git(root, "rev-parse", "HEAD") == publication_commit, "actual publication commit")
    if execution:
        require_approval(m, p)  # Before archive reads, capture, compilation or engine.
        demand(publication_commit is not None and not driver.git(root, "status", "--porcelain", "--untracked-files=all"), "clean publication source")
        demand(archive is not None, "execution archive required")
    if archive is not None:
        data = driver.bounded(Path(archive), driver.MAX_ZIP)
        demand(driver.sha(data) == m["archiveSha256"], "neutral archive digest")
        entries, index = driver.archive_entries(data)
        demand(driver.sha(entries["index.json"]) == m["indexSha256"], "neutral index digest")
        demand(index["members"] == p["members"] and index["captures"] == p["captures"], "neutral protocol captures/members")
    return m

def select_jar(gradle_home):
    """Return the unique bounded old-declared JOSM19555 dependency.

    Args:
        gradle_home: Existing Gradle cache root, never an installation target.
    Returns:
        Path: The one JAR whose complete bytes match the independent digest.
    Raises:
        ValueError: Cache inventory, uniqueness or digest is invalid.
        OSError: Reading the declared cache fails.
    """
    directory = Path(gradle_home) / "caches/modules-2/files-2.1/org.openstreetmap.josm/josm/19555"
    candidates = []
    with os.scandir(directory) as entries:
        for i, entry in enumerate(entries):
            demand(i < 8 and not entry.is_symlink() and entry.is_dir(follow_symlinks=False), "JOSM cache inventory")
            path = Path(entry.path) / "josm-19555.jar"
            if path.is_file():
                candidates.append(path)
    demand(len(candidates) == 1 and driver.sha(driver.bounded(candidates[0],64*1024*1024)) == JAR_SHA, "unique pinned old JOSM19555")
    return candidates[0].resolve()

def attest(root, manifest, checkout, attestation):
    """Check actual old source/class/dependency origins after compilation.

    Args:
        root: Current checkout (no current class enters the old classpath).
        manifest: Already authenticated launch metadata.
        checkout: Literal clean117 checkout built by its own wrapper.
        attestation: Actual bounded compiler/class-origin receipt.
    Returns:
        str: Digest of the checked attestation; not independent review authority.
    Raises:
        ValueError: Historical source, classpath, adapter or dependency differs.
        OSError: Reading a bounded receipt fails.
    """
    m = manifest
    demand(driver.verify_checkout(checkout) == m["historical"], "old checkout source/build")
    a = driver.metadata(driver.bounded(Path(attestation),65536))
    cp = driver.verify_classpath(checkout,a["runtimeEntries"])
    demand(a["source"] == m["historical"] and all(a[k] == v for k,v in cp.items()), "actual old classpath")
    demand(a["adapterSourceSha256"] == m["sourceFiles"]["scripts/historical-b-native/HistoricalNativeB.java"] and
           driver.tree_sha(a["adapterClasses"]) == a["adapterSha256"] and
           a["dependencies"][0]["sha256"] == JAR_SHA, "adapter/dependency source binding")
    return driver.sha(driver.bounded(Path(attestation),65536))

def publish_batch(output, leaves):
    """Publish a complete bounded batch through one fresh owned directory.

    Args:
        output: Absent final directory with an existing parent.
        leaves: Already validated public byte leaves; neutral.zip is input only.
    Returns:
        None; the final directory appears only after every write and close.
    Raises:
        ValueError: Destination, inventory or byte bounds are invalid.
        OSError: Claim, write, close or final rename fails. Owned staging is
            removed while the primary exception and existing output survive.
    """
    output=Path(output)
    demand(not output.exists() and not output.is_symlink(), "new artifact directory")
    demand(0 < len(leaves) <= 48 and all(re.fullmatch(r"[A-Za-z0-9_.-]+",name) and
           len(data) <= (driver.MAX_ZIP if name == "neutral.zip" else 65536)
           for name,data in leaves.items()) and sum(map(len,leaves.values())) <= 2147483648,"artifact bounds")
    # Assignment follows the successful exclusive directory claim.
    stage=Path(tempfile.mkdtemp(prefix="."+output.name+"-",dir=output.parent))
    owned=[]
    try:
        for name,data in leaves.items():
            path=stage/name
            stream=path.open("xb")
            owned.append(path)  # Before write/close can fail after creating it.
            try:
                written=stream.write(data)
                demand(written == len(data),"complete artifact write")
            except BaseException:
                try:
                    stream.close()
                except BaseException:
                    pass  # Preserve the write failure, not a secondary close.
                raise
            else:
                stream.close()
        demand(not output.exists() and not output.is_symlink(),"new artifact directory")
        os.rename(stage,output)
    except BaseException:
        # Only this successfully claimed directory and its owned leaves.
        try:
            shutil.rmtree(stage)
        except OSError:
            pass
        raise

def collect(root, case, raw_root, point_root, output, expected, code, checker_code, publication, attestation):
    """Stage only closed public outputs after both processes terminated.

    Nonzero wrapper exit and missing/cutoff/partial receipts remain unavailable.
    Raw native facts stay in their original leaves. No route is selected, changed
    or truncated; collection failure refuses the upload directory.

    Args:
        root: Current source checkout containing the pinned metadata.
        case: One literal public case ID.
        raw_root: Historical producer output parent.
        point_root: Separate current checker output directory.
        output: New closed upload directory, never an existing workspace.
        expected: Externally supplied manifest digest.
        code: Actual launcher wait code, or None if never observed.
        checker_code: Actual checker wait code, or None if never observed.
        publication: Actual publication GitHub.sha, distinct from implementation.
        attestation: Optional existing actual old compilation receipt path.
    Returns:
        dict: Bounded publication inventory with no invented native result.
    Raises:
        ValueError: A tuple, path, inventory, bound or code is invalid.
        OSError: Reading or publishing a fixed leaf fails.
    """
    demand(case in driver.IDS and all(v is None or type(v) is int and 0 <= v <= 255 for v in (code,checker_code)), "fixed case/exit code")
    demand(publication is not None and re.fullmatch("[0-9a-f]{40}",publication) is not None,"publication context")
    root, output = Path(root), Path(output)
    demand(not output.exists(), "new artifact directory")
    m = verify(root, expected)
    leaves = {}
    missing = []
    for folder, allowed, prefix in ((Path(raw_root)/case,RAW_LEAVES,"raw-"),(Path(point_root),POINT_LEAVES,"point-")):
        if not folder.exists():
            missing.append(prefix+"directory")
            continue
        demand(folder.is_dir() and not folder.is_symlink(), "artifact directory type")
        with os.scandir(folder) as entries:
            for i, entry in enumerate(entries):
                demand(i < len(allowed) and entry.name in allowed and not entry.is_symlink() and entry.is_file(follow_symlinks=False), "public artifact inventory")
                leaves[prefix+entry.name] = driver.bounded(Path(entry.path),65536)
    for name,target in ((case+".log","native.log"),(case+"-external-cutoff.json","external-cutoff.json")):
        path = Path(raw_root)/name
        if path.exists() or path.is_symlink():
            leaves[target] = driver.bounded(path,65536)
    for name, path in (("manifest.json",root/MANIFEST),("protocol.json",root/PROTOCOL),("attestation.json",Path(attestation))):
        if path.exists():
            leaves[name] = driver.bounded(path,65536)
    for name in ("historical-native-wrapper.log","historical-native-checker.log","historical-point-current-code-sources.json"):
        path=root/"build"/name
        if path.exists() or path.is_symlink():
            leaves[name] = driver.bounded(path,65536)
    receipt = {"scope":"HISTORICAL_NATIVE_ENGINE_BOUNDARY_ONLY_NOT_MODEL_QUALIFICATION","case":case,
               "publicationCommit":publication,"implementationCommit":m["implementationCommit"],"manifestSha256":expected,
               "launcherWaitExitCode":code,"checkerWaitExitCode":checker_code,"missing":missing,
               "launcherObserved":code is not None,"checkerObserved":checker_code is not None,
               "nativeExitCode":"NOT_INFERRED_FROM_LAUNCHER_EXIT","modelQualified":False,
               "files":{name:{"bytes":len(data),"sha256":driver.sha(data)} for name,data in leaves.items()}}
    leaves["publication.json"] = driver.canonical(receipt)
    demand(all(len(v) <= 65536 for v in leaves.values()) and sum(map(len,leaves.values())) <= 2147483648, "artifact bounds")
    publish_batch(output,leaves)
    return receipt

def require_junit(data):
    """Require bounded UTF8 JUnit with real passing cases and zero skips.

    Args:
        data: At most65,536 bytes of actual pure-test JUnit XML.
    Returns:
        Element: Validated suite; no numerical execution is performed.
    Raises:
        ValueError: Declarations, encoding, counts or outcomes are invalid.
        ET.ParseError: XML syntax is malformed.
    """
    demand(0 < len(data) <= 65536 and b"<!DOCTYPE" not in data.upper() and b"<!ENTITY" not in data.upper() and b"\x00" not in data,"JUnit declarations/bounds")
    suite=ET.fromstring(data.decode("utf-8","strict"));cases=suite.findall("testcase")
    demand(suite.tag == "testsuite" and int(suite.get("tests","0")) == len(cases) > 0 and
           all(suite.get(k) == "0" for k in ("failures","errors","skipped")) and
           all(not list(suite.iter(tag)) for tag in ("failure","error","skipped")),"pure preflight zero-skip")
    return suite

def preflight(root, log, expected, publication, output):
    """Retain one authenticated neutral ZIP and zero-skip pure-test receipts.

    Args:
        root: Current source checkout.
        log: Bounded successful console log, never the export-marker authority.
        expected: Independent workflow manifest digest.
        publication: Actual GitHub.sha for this publication.
        output: New limited preflight artifact directory.
    Returns:
        None; writes the exact ZIP, three JUnit files and preflight receipt.
    Raises:
        ValueError: Export identity, data, XML, source or bounds differ.
        OSError: Reading or writing an owned file fails.
        ET.ParseError: Bounded JUnit XML is malformed.
    """
    root, output = Path(root), Path(output)
    driver.bounded(Path(log),65536).decode("utf-8","strict")
    leaves={};neutral=None
    for name in ("V022BNativeNeutralFixtureTest","V022BNativeHistoricalPointComparisonTest","V022BNativeHistoricalClasspathTest"):
        files = list((root/"build/test-results/test").glob("TEST-*"+name+".xml"))
        demand(len(files) == 1,"pure preflight JUnit inventory")
        data = driver.bounded(files[0],65536)
        suite=require_junit(data)
        if name == "V022BNativeNeutralFixtureTest":
            demand(suite.get("name") == PACKAGE.replace("src/test/java/", "").replace("/", ".")+name,
                   "neutral JUnit source")
            outputs=suite.findall("system-out")
            demand(len(outputs) == 1,"neutral JUnit system-out")
            neutral=outputs[0].text or ""
        leaves[name+".xml"] = data
    matches = re.findall(r"NEUTRAL_REVIEW_DIRECTORY=(\S+) ARCHIVE_SHA256=([0-9a-f]{64})",neutral)
    demand(len(matches) == 1,"one neutral export marker")
    review = root/matches[0][0]
    demand(not review.is_symlink() and review.resolve().parent == (root/"build").resolve(),"owned neutral review directory")
    review=review.resolve()
    archive = review/"neutral.zip"
    m = verify(root,expected,publication_commit=publication,archive=archive)
    demand(matches[0][1] == m["archiveSha256"],"export marker digest")
    leaves["neutral.zip"] = driver.bounded(archive,driver.MAX_ZIP)
    leaves["preflight.json"] = driver.canonical({"scope":"PUBLIC_NEUTRAL_CAPTURE_ONLY_PREFLIGHT","publicationCommit":publication,
         "manifestSha256":expected,"protocolSha256":m["protocolSha256"],"archiveSha256":m["archiveSha256"],"engineCalls":0,"modelQualified":False})
    publish_batch(output,leaves)

def main(argv=None):
    """Run explicit metadata verification/staging; never invoke an engine.

    Args:
        argv: Explicit CLI arguments, or None for the process argument vector.
    Returns:
        None; emits bounded verification metadata or a closed artifact directory.
    Raises:
        ValueError: Required pins, promotion, context or bounded metadata fail.
        OSError: Bounded source/receipt I/O fails.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode",choices=("verify","jar","attest","collect","preflight"))
    parser.add_argument("--root",type=Path,default=ROOT)
    parser.add_argument("--manifest-sha",required=True)
    parser.add_argument("--execution",action="store_true")
    parser.add_argument("--branch",default=BRANCH)
    parser.add_argument("--publication-commit")
    parser.add_argument("--archive",type=Path)
    parser.add_argument("--gradle-home",type=Path,default=Path.home()/".gradle")
    parser.add_argument("--checkout",type=Path)
    parser.add_argument("--attestation",type=Path)
    parser.add_argument("--case",choices=driver.IDS)
    parser.add_argument("--raw-root",type=Path)
    parser.add_argument("--point-root",type=Path)
    parser.add_argument("--output",type=Path)
    parser.add_argument("--launcher-exit",type=int)
    parser.add_argument("--checker-exit",type=int)
    parser.add_argument("--log",type=Path)
    a = parser.parse_args(argv)
    m = verify(a.root,a.manifest_sha,a.execution,a.branch,a.publication_commit,a.archive)
    if a.mode == "jar":
        print(select_jar(a.gradle_home))
    elif a.mode == "attest":
        print(attest(a.root,m,a.checkout,a.attestation))
    elif a.mode == "collect":
        collect(a.root,a.case,a.raw_root,a.point_root,a.output,a.manifest_sha,a.launcher_exit,a.checker_exit,a.publication_commit,a.attestation)
    elif a.mode == "preflight":
        preflight(a.root,a.log,a.manifest_sha,a.publication_commit,a.output)
    else:
        print(driver.canonical({"scope":m["scope"],"approval":m["approval"],"publicationCommit":a.publication_commit,"modelQualified":False}).decode(),end="")

if __name__ == "__main__":
    main()
