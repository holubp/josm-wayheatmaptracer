"""Validate retained historical-only JUnit outputs; never run an engine."""
import hashlib
import json
import os
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

HISTORICAL_SOURCE = "117baaf09f4b81a38ad4679eedc8fd1b12ed3d2a"
CLASS = "org.openstreetmap.josm.plugins.wayheatmaptracer.BenchmarkDecisionComparatorIntegrationTest"
METHODS = {"approvedContractKeepsEachNativeEnvelopeAndOriginalPin",
           "approvedMainAdmitsIndependentSingleAndFactualIntervalCompatibilityOutputs"}
GROUPS = ({"baseline.zip", "candidate.zip", "original.osm"},
          {"single.zip", "interval.zip", "original.osm"})
MAX_FILE = 8 * 1024 * 1024


def bounded(path, limit):
    if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= limit:
        raise ValueError("Missing, linked, empty or oversized receipt")
    with path.open("rb") as stream:
        data = stream.read(limit + 1)
    if not 0 < len(data) <= limit:
        raise ValueError("Receipt changed size during read")
    return data


def verify(temporary, xml):
    """Require both unchanged historical methods and exactly six retained leaves."""
    temporary, xml = Path(temporary), Path(xml)
    raw = bounded(xml, 512 * 1024)
    if b"<!" in raw:
        raise ValueError("XML declarations with external content are forbidden")
    suite = ET.fromstring(raw)
    expected_counts = {"tests": "2", "failures": "0", "errors": "0", "skipped": "0"}
    if suite.tag != "testsuite" or any(suite.get(k) != v for k, v in expected_counts.items()):
        raise ValueError("Exactly two passing historical tests required")
    cases = suite.findall("testcase")
    methods = [case.get("name", "").split("(", 1)[0] for case in cases]
    if len(cases) != 2 or set(methods) != METHODS or any(
            case.get("classname") != CLASS or list(case) for case in cases):
        raise ValueError("Historical selectors failed, skipped or changed")
    if temporary.is_symlink() or not temporary.is_dir():
        raise ValueError("Invalid retained temporary root")
    found = [None, None]
    entries = 0
    for directory, subdirs, files in os.walk(temporary, followlinks=False):
        current = Path(directory)
        entries += len(subdirs) + len(files)
        if entries > 4096 or len(current.relative_to(temporary).parts) > 8:
            raise ValueError("Temporary inventory exceeds bounds")
        if any((current / name).is_symlink() for name in subdirs + files):
            raise ValueError("Linked temporary receipt forbidden")
        names = set(files)
        if not names.intersection(set.union(*GROUPS)):
            continue
        indexes = [i for i, group in enumerate(GROUPS) if names == group and not subdirs]
        if len(indexes) != 1 or found[indexes[0]] is not None:
            raise ValueError("Extra, missing or duplicate native receipt group")
        found[indexes[0]] = current
    if any(directory is None for directory in found):
        raise ValueError("Both native output groups must survive TempDir cleanup")
    receipts, total = [], 0
    for index, directory in enumerate(found):
        for name in sorted(GROUPS[index]):
            data = bounded(directory / name, MAX_FILE)
            total += len(data)
            if total > 32 * 1024 * 1024:
                raise ValueError("Native receipts exceed aggregate bound")
            receipts.append({"path": (directory / name).relative_to(temporary).as_posix(),
                             "size": len(data), "sha256": hashlib.sha256(data).hexdigest()})
    return {"scope": "HISTORICAL_CONTEXT_ONLY", "sourceRevision": HISTORICAL_SOURCE,
            "tests": sorted(METHODS), "files": receipts,
            "xmlSha256": hashlib.sha256(raw).hexdigest(), "currentModelQualified": False}


def main(arguments):
    if len(arguments) != 3:
        raise ValueError("Expected retained-temp-root, JUnit XML and new output directory")
    temporary, xml, output = map(Path, arguments)
    result = verify(temporary, xml)
    output.mkdir(exist_ok=False)
    for index, receipt in enumerate(result["files"]):
        # Copy only explicitly validated synthetic leaves, then recheck published bytes.
        source = temporary / receipt["path"]
        data = bounded(source, MAX_FILE)
        if hashlib.sha256(data).hexdigest() != receipt["sha256"]:
            raise ValueError("Native receipt changed before publication")
        target = output / (str(index) + "-" + source.name)
        target.write_bytes(data)
        receipt["artifact"] = target.name
    xml_data = bounded(xml, 512 * 1024)
    if hashlib.sha256(xml_data).hexdigest() != result["xmlSha256"]:
        raise ValueError("JUnit result changed before publication")
    (output / "results.xml").write_bytes(xml_data)
    (output / "receipt.json").write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")


if __name__ == "__main__":
    main(sys.argv[1:])
