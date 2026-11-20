#!/usr/bin/env python3
"""Generate fixed JFR settings and fail closed on unexpected recorded events."""
import argparse
import hashlib
import pathlib
import re
import subprocess
import xml.etree.ElementTree as ET

ALLOWED = frozenset("""
jdk.ExecutionSample jdk.NativeMethodSample jdk.ThreadAllocationStatistics
jdk.ObjectAllocationSample jdk.ObjectAllocationInNewTLAB jdk.ObjectAllocationOutsideTLAB
jdk.GarbageCollection jdk.YoungGarbageCollection jdk.OldGarbageCollection
jdk.ParallelOldGarbageCollection jdk.G1GarbageCollection jdk.GCHeapSummary
jdk.GCPhasePause jdk.GCPhasePauseLevel1 jdk.GCPhasePauseLevel2
jdk.GCPhasePauseLevel3 jdk.GCPhasePauseLevel4 jdk.GCPhaseConcurrent
jdk.GCPhaseConcurrentLevel1 jdk.GCReferenceStatistics jdk.GCCPUTime
jdk.AllocationRequiringGC jdk.Compilation jdk.CompilationFailure jdk.Deoptimization
jdk.SafepointBegin jdk.SafepointEnd jdk.SafepointStateSynchronization
jdk.SafepointCleanup jdk.SafepointCleanupTask
""".split())
FORBIDDEN = frozenset("""
jdk.InitialEnvironmentVariable jdk.InitialSystemProperty jdk.SystemProcess
jdk.ProcessStart jdk.JVMInformation
""".split())
# JFR's own binary format records type definitions and constant pools.
HOUSEKEEPING = frozenset({"jdk.Metadata", "jdk.CheckPoint"})


def generate(profile, output):
    source = ET.parse(profile).getroot()
    root = ET.Element("configuration", version="2.0", label="Restricted diagnostic",
                      description="DIAGNOSTIC_NOT_GATE", provider="local")
    seen = set()
    for event in source.findall("event"):
        name = event.attrib["name"]
        if name in seen:
            raise ValueError("duplicate event")
        seen.add(name)
        target = ET.SubElement(root, "event", name=name)
        enabled = next((s.text or "" for s in event.findall("setting")
                        if s.get("name") == "enabled"), "false").strip()
        ET.SubElement(target, "setting", name="enabled").text = (
            enabled if name in ALLOWED and enabled in ("true", "false") else "false")
        if name in ALLOWED:
            for setting in event.findall("setting"):
                if setting.get("name") != "enabled":
                    ET.SubElement(target, "setting", name=setting.get("name")).text = setting.text
    for name in sorted(FORBIDDEN - seen):
        event = ET.SubElement(root, "event", name=name)
        ET.SubElement(event, "setting", name="enabled").text = "false"
    ET.ElementTree(root).write(output, encoding="utf-8", xml_declaration=True)


def check_summary(summary):
    rows = {}
    for line in summary.splitlines():
        match = re.fullmatch(r"\s*([A-Za-z][\w.$]*)\s+(\d+)\s+(\d+)\s*", line)
        if match:
            name, count, size = match.groups()
            if name in rows:
                raise ValueError("duplicate summary event")
            rows[name] = (int(count), int(size))
    if not rows or not FORBIDDEN <= rows.keys():
        raise ValueError("missing event summary or privacy event types")
    unsafe = sorted(name for name, (count, _) in rows.items()
                    if count and name not in ALLOWED | HOUSEKEEPING)
    return rows, unsafe


def main():
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    gen = commands.add_parser("generate")
    gen.add_argument("profile", type=pathlib.Path)
    gen.add_argument("output", type=pathlib.Path)
    check = commands.add_parser("check")
    check.add_argument("recording", type=pathlib.Path)
    check.add_argument("summary", type=pathlib.Path)
    args = parser.parse_args()
    if args.command == "generate":
        generate(args.profile, args.output)
        for label, path in (("profile", args.profile), ("settings", args.output)):
            print(label + "_sha256=" + hashlib.sha256(path.read_bytes()).hexdigest())
    else:
        # Never write raw tool output: retain event names and numeric counts only.
        result = subprocess.run(["jfr", "summary", str(args.recording)],
                                text=True, capture_output=True, check=True)
        rows, unsafe = check_summary(result.stdout)
        args.summary.write_text("".join(f"{name} {count} {size}\n"
                                        for name, (count, size) in sorted(rows.items())))
        if unsafe:
            raise ValueError("recording contains disallowed event types: " + ", ".join(unsafe))
        print("privacy_check=PASS")


if __name__ == "__main__":
    main()
