"""Bound the one reviewed raw-one experiment; never acquire data or run inference.

Usage: ``verify-b-exact-cross-stream.py {verify,seal,compiled,collect,gate} ...``.
The workflow independently pins the protocol. Pending metadata cannot execute.
The numerical/harness commit and actual publication commit are distinct facts.
"""
import argparse
import hashlib
import io
import json
import math
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile
import xml.etree.ElementTree as ET

BRANCH = "codex/b-exact-cross-stream-20261020"
PROTOCOL = "src/test/resources/b-exact-cross-stream/protocol.json"
INIT = "scripts/gradle/exact-cross-stream-test.gradle"
SELF = "scripts/verify-b-exact-cross-stream.py"
IDENTITY = "IDENTIFIED_B0_EXACT_CROSS_STREAM_RAW1_V3_NOT_NATIVE_DEFAULT_OR_MODEL_QUALIFICATION"
VERSION = "terminal-path-count-cross-stream-raw1-v3"
IDS = ["switchback-thin-strong", "switchback-thin-faint", "same-trail-three-strong",
       "same-trail-five-strong", "same-trail-three-separated-strong"]
LIMITS = dict(states=96, pairs=8_000_000, transitions=128_000_000, raw=1, distinct=1,
              bytes=67_108_864, cooperativeSeconds=300, externalSeconds=360)
TREE_ROOTS = ["src/main/java", "src/test/java", "src/main/resources"]
SOURCE_FILES = {SELF, INIT} | {"src/test/resources/b-observed-gap/"+name for name in
    ("matched-controls.json", "thin-strand-controls.json", "thin-switchback-controls.json",
     "same-trail-recordings.json", "same-trail-separated.json", "source-contract.json")}
SOURCE_FILES |= {"build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat",
                 "gradle/wrapper/gradle-wrapper.properties", "gradle/wrapper/gradle-wrapper.jar"}
COMPILED_ROOTS = ["build/classes/java/main", "build/classes/java/test", "build/resources/main", "build/resources/test"]
PACKAGE = "org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/"
TEST_CLASS = PACKAGE.replace("/", ".") + "V022ExactCrossStreamComparisonTest"
METRICS = ["stageCertificates", "certificateFallbacks", "boundQueries", "boundFallbacks", "finiteTests", "magnitudeTests",
           "arithmeticOps", "outwardSteps", "partialAdditions", "strictComparisons", "boundCheckpoints", "skippedStreams",
           "skippedLogicalPrefixes", "firstHeadingEvaluations", "secondHeadingEvaluations", "wrapEvaluations", "huberEvaluations",
           "tripleEvaluations", "ceilingChecks", "boundUnits"]
LEAF = 65_536


def require(condition, reason):
    """Raise ValueError with a fixed safe reason for a violated contract."""
    if not condition:
        raise ValueError(reason)


def canonical(value):
    """Return deterministic strict JSON bytes for bounded metadata."""
    return (json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False) + "\n").encode()


def sha(data):
    """Return the SHA256 of bytes without interpreting their contents."""
    return hashlib.sha256(data).hexdigest()


def integer(value, low=0, high=2**63-1):
    """Require an exact JSON integer; booleans and rounded floats are refused."""
    require(type(value) is int and low <= value <= high, "exact bounded integer required")
    return value


def read(path, empty=False, maximum=LEAF):
    """Read a bounded regular nonsymlink leaf, allowing empty console logs only.

    Args:
        path: Exact expected file, never a recursive acquisition root.
        empty: Whether the caller's named console-log role allows zero bytes.
        maximum: Maximum byte size before allocation.
    Returns:
        bytes: Complete file bytes without truncation.
    Raises:
        ValueError: File role, size, or symlink violates the contract.
        OSError: Opening/reading the file fails.
    """
    path = Path(path)
    require(not path.is_symlink() and path.is_file(), "regular leaf required")
    require((0 if empty else 1) <= path.stat().st_size <= maximum, "bounded leaf required")
    with path.open("rb") as stream:
        data = stream.read(maximum + 1)
    require((0 if empty else 1) <= len(data) <= maximum, "bounded leaf changed")
    return data


def document(data):
    """Decode strict UTF8 JSON, rejecting duplicate keys/nonfinite tokens."""
    def pairs(values):
        result = {}
        for key, value in values:
            require(key not in result, "duplicate JSON field")
            result[key] = value
        return result
    def invalid(_):
        raise ValueError("nonfinite JSON token")
    value = json.loads(data.decode("utf-8"), object_pairs_hook=pairs, parse_constant=invalid)
    def walk(item, depth, budget):
        require(depth <= 24 and budget[0] < 32_768, "bounded JSON structure required")
        budget[0] += 1
        if isinstance(item, dict):
            for key, child in item.items():
                require(isinstance(key, str), "JSON string key required")
                walk(child, depth+1, budget)
        elif isinstance(item, list):
            for child in item:
                walk(child, depth+1, budget)
    walk(value, 0, [0])
    require(isinstance(value, dict), "JSON object required")
    return value


def git(root, *args):
    """Read actual Git identity without changing a checkout or fetching data."""
    return subprocess.check_output(["git", "-c", "safe.directory="+str(Path(root).resolve()), *args], cwd=root).decode().strip()


def source_trees(root, commit):
    """Hash the exact tracked numerical/helper/resource closure at a real commit.

    The new protocol is excluded to avoid a self-hash cycle. Its independent
    workflow digest binds it. All other actual tracked membership must match.
    """
    result = {}
    for prefix in TREE_ROOTS:
        expected = git(root, "ls-tree", "-r", "--name-only", commit, "--", prefix).splitlines()
        actual = git(root, "ls-files", "--", prefix).splitlines()
        expected = [p for p in expected if p != PROTOCOL]
        actual = [p for p in actual if p != PROTOCOL]
        require(actual == expected, "source tree membership changed")
        entries = []
        for name in expected:
            entries.append([name, sha(read(Path(root)/name, maximum=4*1024*1024))])
        result[prefix] = sha(canonical(entries))
    return result


def verify(root, expected, execution=False, branch=BRANCH, publication=None):
    """Authenticate pending metadata or admit one reviewed execution context.

    Args:
        root: Actual current checkout containing the literal source closure.
        expected: Independent protocol SHA256 from the reviewed workflow.
        execution: Require approval and the actual clean publication identity.
        branch: Factual GitHub ref name, not inferred from detached HEAD.
        publication: Factual GitHub.sha; distinct from reviewedCodeCommit.
    Returns:
        dict: Authenticated protocol metadata, never model qualification.
    Raises:
        ValueError: Any digest/source/policy/context or approval differs.
        OSError: Reading actual bounded source/Git metadata fails.
    """
    root = Path(root)
    data = read(root/PROTOCOL)
    require(re.fullmatch(r"[0-9a-f]{64}", expected or "") and sha(data) == expected, "protocol digest changed")
    value = document(data)
    require(type(value.get("schema")) is int and value["schema"] == 1 and value.get("comparisonIdentity") == IDENTITY, "protocol identity changed")
    require(value.get("cases") == IDS and value.get("executionPolicy") == VERSION, "policy/case inventory changed")
    require(value.get("branch") == BRANCH and type(value.get("eta")) is int and value["eta"] == 0
            and value.get("nullPotential") is True and value.get("B0") == [1, 1, 0]
            and all(type(n) is int for n in value["B0"]), "objective contract changed")
    require(value.get("limits", {}).keys() == LIMITS.keys(), "limit inventory changed")
    for key, expected_value in LIMITS.items():
        require(integer(value["limits"][key]) == expected_value, "limit changed")
    require(type(value.get("approval")) is bool and type(value.get("engineEligible")) is bool, "typed approval required")
    code = value.get("reviewedCodeCommit")
    require(isinstance(code, str) and re.fullmatch(r"[0-9a-f]{40}", code), "reviewed commit required")
    require(value.get("sourceTrees") == source_trees(root, code), "numerical source closure changed")
    require(value.get("sourceFiles", {}).keys() == SOURCE_FILES, "support source inventory changed")
    for name, digest in value["sourceFiles"].items():
        require(sha(read(root/name)) == digest, "support source digest changed")
    closure = {"sourceTrees": value["sourceTrees"], "sourceFiles": value["sourceFiles"]}
    require(value.get("sourceClosureSha256") == sha(canonical(closure)), "source closure digest changed")
    pins = value.get("baselineInputs")
    require(isinstance(pins, list) and [p.get("case") for p in pins] == IDS, "archived case order changed")
    for row in pins:
        for key in ("snapshotHash", "factsAndLatticeHash", "archiveSha256", "memberSha256"):
            require(isinstance(row.get(key), str) and re.fullmatch(r"[0-9a-f]{64}", row[key]), "archived pin required")
        require(integer(row.get("profiles")) == 57 and integer(row.get("modes")) > 0, "archived profile inventory changed")
        require(integer(row.get("originalPairInventory")) <= 8_000_000 and integer(row.get("observedForwardBackwardScores")) > 0,
                "archived work inventory changed")
        require(row.get("selectedAdmissionCoarsened") is False and row.get("fullTwentyFiveMetreDomain") is True,
                "archived graph admission changed")
    if execution:
        require(value["approval"] is True and value["engineEligible"] is True, "pending protocol cannot execute")
        require(branch == BRANCH, "execution branch changed")
        require(isinstance(publication, str) and re.fullmatch(r"[0-9a-f]{40}", publication)
                and git(root, "rev-parse", "HEAD") == publication, "actual publication changed")
        require(not git(root, "status", "--porcelain", "--untracked-files=no"), "tracked checkout is dirty")
        require(set(git(root, "ls-files", "--", *sorted(SOURCE_FILES | {PROTOCOL})).splitlines()) == SOURCE_FILES | {PROTOCOL},
                "support sources must belong to actual committed publication")
        for prefix in TREE_ROOTS:
            require(not git(root, "ls-files", "--others", "--exclude-standard", "--", prefix), "untracked compiler inputs refused")
    return value


def compiled_tree(root):
    """Census precompiled outputs before retention, with bounded walk metadata."""
    entries = []; total = 0; visited = 0; roots = {}
    for prefix in COMPILED_ROOTS:
        base = Path(root)/prefix
        if prefix == "build/resources/main" and not base.exists() and not base.is_symlink():
            source = Path(root)/"src/main/resources"
            require(not source.exists() and not source.is_symlink() and not git(root, "ls-files", "--", "src/main/resources"),
                    "missing compiled resources have actual source")
            roots[prefix] = "ABSENT_NO_SOURCE"
            continue
        require(base.is_dir() and not base.is_symlink(), "precompile outputs missing")
        roots[prefix] = "PRESENT"
        for directory, dirs, files in os.walk(base, followlinks=False):
            require(len(Path(directory).relative_to(base).parts) <= 32, "compiled directory depth")
            for name in sorted(dirs + files):
                visited += 1
                path = Path(directory)/name
                rel = path.relative_to(root).as_posix()
                require(visited <= 16_384 and len(rel.encode()) <= 512 and not path.is_symlink(), "compiled inventory bound")
                if path.is_dir():
                    continue
                data = read(path, empty=True, maximum=16*1024*1024)
                total += len(data)
                require(total <= 512*1024*1024, "compiled closure byte bound")
                entries.append([rel, len(data), sha(data)])
    names = {row[0] for row in entries}
    for prefix, name in [(COMPILED_ROOTS[0], "ProbabilisticInference"), (COMPILED_ROOTS[1], "BExactCrossStreamComparison"),
                         (COMPILED_ROOTS[1], "V022ExactCrossStreamComparisonTest")]:
        require(prefix+"/"+PACKAGE+name+".class" in names, "required compiled class missing")
    return {"sha256": sha(canonical(sorted(entries))), "files": len(entries), "bytes": total, "roots": roots}


def compiled(root, expected, seal=None):
    """Seal or verify fresh precompiled outputs; never compile/reseal implicitly."""
    protocol = verify(root, expected)
    state = {"protocolSha256": expected, "actualCheckoutCommit": git(root, "rev-parse", "HEAD"),
             "sourceClosureSha256": protocol["sourceClosureSha256"], "compiled": compiled_tree(root)}
    if seal is not None:
        require(state == document(read(seal)), "precompiled closure changed after verified compile")
    return state


def atomic_directory(destination, leaves):
    """Publish a complete bounded owned batch, preserving pre-existing output.

    Args:
        destination: Fresh final directory; an existing object is refused.
        leaves: Exact flat filename to complete bytes mapping.
    Returns:
        None after one directory rename commits the complete batch.
    Raises:
        ValueError: Inventory, path, leaf, aggregate, or ownership is invalid.
        OSError: Write/close/rename fails; only owned staging is removed.
    """
    destination = Path(destination)
    require(not destination.exists() and not destination.is_symlink(), "fresh publication required")
    require(1 <= len(leaves) <= 170, "publication inventory bound")
    require(sum(len(b) for b in leaves.values()) <= 2*1024**3, "publication aggregate bound")
    destination.parent.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix=".owned-cross-stream-", dir=destination.parent))
    try:
        for name, data in leaves.items():
            require(re.fullmatch(r"[a-zA-Z0-9_.-]+", name) and 0 <= len(data) <= LEAF, "bounded flat publication leaf required")
            with (stage/name).open("xb") as stream:
                stream.write(data)
        require(not destination.exists() and not destination.is_symlink(), "publication already claimed")
        stage.rename(destination)
    except BaseException as primary:
        try:
            shutil.rmtree(stage)
        except OSError as cleanup:
            primary.add_note("Owned staging cleanup failed: "+type(cleanup).__name__)
        raise


def junit(data):
    """Validate the exact one-case host observation, without inferring from logs."""
    require(b"\x00" not in data and b"<!DOCTYPE" not in data.upper() and b"<!ENTITY" not in data.upper(), "unsafe JUnit XML")
    data.decode("utf-8")
    root = ET.fromstring(data)
    require(root.tag == "testsuite", "one suite XML required")
    cases = root.findall("testcase")
    return (root.get("tests") == "1" and root.get("failures") == "0" and root.get("errors") == "0"
            and root.get("skipped") == "0" and len(cases) == 1 and cases[0].get("classname") == TEST_CLASS
            and cases[0].get("name") == "executesOneFrozenCase()" and not list(cases[0]))


def host_evidence(root, explicit=None):
    """Read one bounded host report without guessing Gradle's shortened basename.

    Args:
        root: Current checkout; automatic discovery reads only its direct test
            result directory, at most256 entries and512 UTF8 bytes per path.
        explicit: Optional existing report path, preserving explicit CLI use.
    Returns:
        tuple: Optional bounded raw bytes, exact dedicated-test success, and a
            fixed availability token. Invalid/missing host evidence cannot
            discard the other case diagnostics or confer entry/root facts.
    """
    unavailable = "UNAVAILABLE_HOST_REPORT_"
    try:
        if explicit is None:
            directory = Path(root)/"build/test-results/test"
            if directory.is_symlink() or any(p.is_symlink() for p in directory.parents):
                return None, False, unavailable+"UNSAFE_PATH"
            if not directory.is_dir():
                return None, False, unavailable+"MISSING"
            path = None
            with os.scandir(directory) as entries:
                for index, entry in enumerate(entries):
                    if index >= 256 or len(entry.name.encode("utf-8")) > 255:
                        return None, False, unavailable+"INVENTORY_BOUND"
                    if not (entry.name.startswith("TEST-") and entry.name.endswith(".V022ExactCrossStreamComparisonTest.xml")):
                        continue
                    if len(str(Path(entry.path)).encode("utf-8")) > 512:
                        return None, False, unavailable+"PATH_BOUND"
                    if entry.is_symlink() or not entry.is_file(follow_symlinks=False):
                        return None, False, unavailable+"UNSAFE_PATH"
                    if path is not None:
                        return None, False, unavailable+"AMBIGUOUS"
                    path = Path(entry.path)
            if path is None:
                return None, False, unavailable+"MISSING"
        else:
            path = Path(explicit)
            if len(str(path).encode("utf-8")) > 512:
                return None, False, unavailable+"PATH_BOUND"
            if path.is_symlink() or any(p.is_symlink() for p in path.parents):
                return None, False, unavailable+"UNSAFE_PATH"
            if not path.is_file():
                return None, False, unavailable+"MISSING"
        data = read(path)
    except (OSError, ValueError, UnicodeError):
        return None, False, unavailable+"UNREADABLE_OR_OUT_OF_BOUNDS"
    try:
        passed = junit(data)
    except (ValueError, ET.ParseError, UnicodeError):
        return data, False, unavailable+"MALFORMED"
    return data, passed, "AVAILABLE_EXACT_DEDICATED_HOST_TEST" if passed else "UNAVAILABLE_HOST_TEST_NOT_PASSED"


def graph_evidence(raw, identity, manifest):
    """Validate complete CSG1 cell order and return factual per-cell point bits."""
    require(manifest.get("format") == "cross-stream-graph-big-endian-raw-binary64-v1" and manifest.get("profiles") == 57,
            "graph format/profile changed")
    points = {}; counts = [0]*57; digest = hashlib.sha256(); names = set(); total = 0; previous_row = 0
    chunks = manifest.get("chunks")
    require(isinstance(chunks, list) and 1 <= len(chunks) <= 128, "graph chunk inventory bound")
    for ordinal, row in enumerate(chunks):
        name = "graph-"+identity+"-%05d.bin" % ordinal
        require(row.get("file") == name, "graph chunk order changed")
        data = raw[name]; names.add(name); digest.update(data); total += len(data)
        require(integer(row.get("bytes")) == len(data) and row.get("sha256") == sha(data), "graph chunk bytes changed")
        stream = io.BytesIO(data)
        def number():
            value = stream.read(4); require(len(value) == 4, "short graph integer"); return struct.unpack(">i", value)[0]
        require(number() == 0x43534731, "graph magic changed")
        n = number(); require(integer(row.get("cells"), 1, 64) == n, "graph chunk cell count")
        for _ in range(n):
            size = number(); require(1 <= size <= LEAF, "bounded cell required")
            cell = io.BytesIO(stream.read(size)); require(len(cell.getbuffer()) == size, "short graph cell")
            def take(length):
                value = cell.read(length); require(len(value) == length, "short graph field"); return value
            def cint():
                return struct.unpack(">i", take(4))[0]
            def utf():
                take(struct.unpack(">H", take(2))[0])
            profile, state = cint(), cint()
            require(previous_row <= profile < 57 and state == counts[profile] and state < 96, "graph cell order changed")
            require(profile == 0 or counts[profile-1] > 0, "graph row missing")
            previous_row = profile
            fields = struct.unpack(">12Q", take(96)); points[profile, state] = fields[7:9]; counts[profile] += 1
            take(1); utf(); take(1); utf(); utf(); take(8)
            modes = cint(); require(0 <= modes <= 8192, "orientation count bound"); take(32*modes)
            responsibilities = cint(); require(0 <= responsibilities <= 8192, "responsibility count bound"); take(8*responsibilities)
            require(not cell.read(1), "surplus graph cell fields")
        require(not stream.read(1), "surplus graph chunk bytes")
    require(all(counts) and sum(counts) == integer(manifest.get("cells")), "incomplete graph cell sequence")
    require(manifest.get("graphBytesSha256") == digest.hexdigest() and integer(manifest.get("retainedBytes")) == total,
            "graph stream digest changed")
    return names, counts, points


def collect(root, expected, identity, exit_code, log, xml, output, branch=BRANCH, publication=None):
    """Preserve one terminal shell attempt and validate feasibility independently.

    Missing/partial/failed outputs remain evidence, never successful inference.
    Only known current-ID leaves and the exact console/XML roles are published.
    No engine invocation or root closure is inferred from shell intent.
    """
    require(identity in IDS, "fixed case required")
    integer(exit_code, 0, 255)
    protocol = verify(root, expected, True, branch, publication)
    base = Path(root)/"build/exact-cross-stream"/identity
    allowed = {identity+".json", "summary.json", "phase.json", "phase.json.partial", "graph-manifest-"+identity+".json",
               "route-"+identity+"-0.json"} | {"posterior-"+identity+"-"+k+".bin" for k in ("position", "component", "forward", "backward")}
    allowed |= {"graph-"+identity+"-%05d.bin" % i for i in range(128)}
    raw = {}; incomplete = False; empty_phase = False
    if base.exists():
        require(base.is_dir() and not base.is_symlink(), "case directory type changed")
        for entry in base.iterdir():
            if entry.is_dir() and not entry.is_symlink() and re.fullmatch(r"\.owned-(graph|batch)-[A-Za-z0-9_.-]{1,64}", entry.name):
                incomplete = True
                for child in entry.iterdir():
                    require(child.name in allowed and len(raw) < 165, "unexpected staged evidence path")
                    name = "staged-"+entry.name[1:]+"-"+child.name
                    require(name not in raw, "duplicate staged evidence")
                    raw[name] = read(child, empty=True)
            else:
                require(entry.name in allowed and len(raw) < 165, "unexpected case evidence path")
                # An interrupted initial claim can leave an empty phase. Keep
                # its bytes/hash only; it cannot supply any authoritative fact.
                raw[entry.name] = read(entry, empty=entry.name in {"phase.json", "phase.json.partial"})
                if entry.name in {"phase.json", "phase.json.partial"} and not raw[entry.name]:
                    empty_phase = True; incomplete = True
                incomplete |= entry.name.endswith(".partial")
    leaves = dict(raw)
    leaves["console.log"] = read(log, empty=True)
    leaves["protocol.json"] = read(Path(root)/PROTOCOL)
    seal = Path(root)/"build/exact-cross-stream-host/compiled.json"
    compiled_ok = False
    if seal.exists():
        compiled(root, expected, seal)
        leaves["compiled.json"] = read(seal)
        compiled_ok = True
    host, xml_pass, host_availability = host_evidence(root, xml)
    if host is not None:
        leaves["host.xml"] = host
    facts = {"case": identity, "protocolSha256": expected, "actualCheckoutCommit": publication,
             "reviewedCodeCommit": protocol["reviewedCodeCommit"], "shellWaitExitCode": exit_code,
             "externalCutoffObserved": exit_code in (124, 137), "nativePolicy": "NOT_NATIVE_DEFAULT",
             "emptyPhaseEvidence": empty_phase,
             "hostReportAvailability": host_availability,
             "modelQualified": False, "feasibilityPassed": False, "entryReturnAndClosure": "UNKNOWN_WITHOUT_FACTUAL_RECEIPT_AND_HOST_XML",
             "rawInventory": [{"file": n, "bytes": len(b), "sha256": sha(b)} for n, b in sorted(raw.items())]}
    reason = "UNAVAILABLE_MISSING_OR_PARTIAL_OR_FAILED_EXECUTION"
    if identity+".json" in raw:
        case = document(raw[identity+".json"])
        require(case.get("case") == identity and case.get("protocolSha256") == expected and case.get("comparisonIdentity") == IDENTITY
                and case.get("actualCheckoutCommit") == publication and case.get("reviewedCodeCommit") == protocol["reviewedCodeCommit"]
                and case.get("sourceClosureSha256") == protocol["sourceClosureSha256"], "case source/protocol binding changed")
        for name in ("actualSolverEntries", "normalReturns"):
            facts[name] = integer(case.get(name), 0, 1)
        facts["safeCause"] = case.get("safeCause"); facts["failurePhase"] = case.get("failurePhase")
        facts["peakBytes"] = integer(case.get("peakBytes"), 0, LIMITS["bytes"])
        facts["metadataBytesStillFundedAtPublication"] = integer(case.get("metadataBytesStillFundedAtPublication"))
        facts["remainingNonMetadataBytesAtPublication"] = integer(case.get("remainingNonMetadataBytesAtPublication"))
        candidate = (exit_code == 0 and xml_pass and compiled_ok and not incomplete and case.get("pipelineComplete") is True
                     and case.get("deadlineExpired") is False and case.get("safeCause") is None
                     and facts["actualSolverEntries"] == facts["normalReturns"] == 1)
        if candidate:
            pinned = next(row for row in protocol["baselineInputs"] if row["case"] == identity)
            for key in ("snapshotHash", "factsAndLatticeHash", "profiles", "modes", "originalPairInventory"):
                require(case.get(key) == pinned[key] and (type(case[key]) is int if type(pinned[key]) is int else True), "archived factual input differs")
            require(case.get("admissionCoarsened") is False and case.get("fullTwentyFiveMetreDomain") is True, "graph admission differs")
            solver = case["solver"]; work = solver["work"]; metrics = solver["boundWork"]
            require(solver.get("status") not in (None, "RESOURCE_LIMIT", "NUMERIC_FAILURE", "NO_ROUTE") and solver.get("posteriorUsable") is True
                    and integer(solver.get("rawCount")) == 1 and integer(solver.get("distinctCount")) == 1 and solver.get("completion") is not None,
                    "failed solver feasibility")
            require(work.get("executionPolicy") == VERSION and integer(work.get("forwardBackwardScores")) == pinned["observedForwardBackwardScores"]
                    and integer(work.get("refusedScoreAttempts")) == 0, "original work/score refusal changed")
            require(integer(work.get("forwardBackwardScores")) + integer(work.get("kBestScores")) <= LIMITS["transitions"], "score cap exceeded")
            require(set(metrics) == set(METRICS), "bound metric inventory changed")
            for number in metrics.values():
                integer(number)
            require(facts["remainingNonMetadataBytesAtPublication"] == 0 and facts["metadataBytesStillFundedAtPublication"] == 1_048_576,
                    "publication ownership differs")
            manifest_name = "graph-manifest-"+identity+".json"
            manifest = document(raw[manifest_name]); require(case.get("graph") == manifest, "graph manifest differs")
            graph_names, counts, points = graph_evidence(raw, identity, manifest)
            require(sum(a*b for a,b in zip(counts, counts[1:])) == pinned["originalPairInventory"], "graph adjacent pair inventory differs")
            expected_names = {identity+".json", "summary.json", "phase.json", manifest_name} | graph_names
            post = case.get("posteriorFiles"); require(isinstance(post, list) and len(post) == 4, "posterior inventory changed")
            for row, kind in zip(post, ("position", "component", "forward", "backward")):
                name = "posterior-"+identity+"-"+kind+".bin"; expected_names.add(name)
                data = raw[name]
                require(row.get("file") == name and integer(row.get("bytes")) == len(data) and row.get("sha256") == sha(data), "posterior leaf digest changed")
                stream = io.BytesIO(data)
                require(stream.read(4) == b"CSP1", "posterior magic changed")
                size = struct.unpack(">H", stream.read(2))[0]
                require(stream.read(size).decode() == kind and struct.unpack(">i", stream.read(4))[0] == 57, "posterior kind/rows changed")
                for index in range(57):
                    size = struct.unpack(">i", stream.read(4))[0]
                    require(0 <= size <= 8192 and (kind == "component" or size == counts[index]), "posterior row width changed")
                    require(len(stream.read(size*8)) == size*8, "short posterior row")
                require(not stream.read(1), "surplus posterior bytes")
            name = "route-"+identity+"-0.json"; expected_names.add(name)
            require(case.get("routeFiles") == [name] and integer(case.get("returnedRouteCount")) == 1 and case.get("routeDataAvailable") is True,
                    "raw alternative order changed")
            route = document(raw[name]); require(len(route.get("states", [])) == len(route.get("pointBits", [])) == len(route.get("points", [])) == 57,
                                               "route point inventory changed")
            for index, state in enumerate(route["states"]):
                integer(state, 0, counts[index]-1)
                require(tuple(int(b, 16) for b in route["pointBits"][index]) == points[index, state], "returned route graph bits differ")
                require(tuple(struct.unpack(">Q", struct.pack(">d", n))[0] for n in route["points"][index]) == points[index, state], "returned coordinate/raw bits differ")
            oracle = route.get("truthOracle", {})
            require(type(oracle.get("available")) is bool and type(oracle.get("passed")) is bool, "typed original oracle facts required")
            require(set(raw) == expected_names and case.get("modelQualified") is False, "surplus successful evidence or qualification")
            require(document(raw["summary.json"]).get("recordedCases") == [identity], "summary case inventory differs")
            facts.update(feasibilityPassed=True, geometryOracle=oracle, solver=solver,
                         entryReturnAndClosure="FACTUAL_RESULT_PLUS_EXACT_PASSING_HOST_POSTCLOSE_ASSERTION")
            reason = "COMPLETE_RESOURCE_FEASIBILITY_ONLY_GEOMETRY_IS_DATA"
    facts["availability"] = reason
    leaves["execution.json"] = canonical(facts)
    atomic_directory(output, leaves)
    return facts


def main():
    """Validate/collect explicit paths; never run Gradle, a capture, or an engine."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("verify", "seal", "compiled", "collect", "gate"))
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--protocol-sha256", required=True)
    parser.add_argument("--branch", default=os.environ.get("GITHUB_REF_NAME"))
    parser.add_argument("--publication", default=os.environ.get("GITHUB_SHA"))
    parser.add_argument("--execution", action="store_true")
    parser.add_argument("--seal", type=Path)
    parser.add_argument("--case", choices=IDS)
    parser.add_argument("--exit-code", type=int)
    parser.add_argument("--log", type=Path)
    parser.add_argument("--xml", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.command == "verify":
        verify(args.root, args.protocol_sha256, args.execution, args.branch, args.publication)
    elif args.command == "seal":
        state = compiled(args.root, args.protocol_sha256)
        with args.seal.open("xb") as stream:
            stream.write(canonical(state))
    elif args.command == "compiled":
        compiled(args.root, args.protocol_sha256, args.seal)
    elif args.command == "collect":
        collect(args.root, args.protocol_sha256, args.case, args.exit_code, args.log, args.xml, args.output, args.branch, args.publication)
    else:
        value = document(read(args.output/"execution.json"))
        require(value.get("feasibilityPassed") is True, "fixed-case feasibility failed; geometry is not qualification")


if __name__ == "__main__":
    main()
