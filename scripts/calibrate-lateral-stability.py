#!/usr/bin/env python3
"""Create a bounded redacted manifest for local debug-bundle calibration."""

from __future__ import annotations

import argparse
import fnmatch
import hashlib
import json
import math
import os
import re
import stat
import sys
from pathlib import Path

from wayheatmap_analysis.safe_zip import ArchiveError, SafeArchiveReader


RULES = {
    "COOKIE": re.compile(r"\bCookie\b", re.I),
    "AUTHORIZATION": re.compile(r"\bAuthorization\b|\bBearer\s+", re.I),
    "CLOUDFRONT": re.compile(r"CloudFront-(?:Key-Pair-Id|Policy|Signature)", re.I),
    "STRAVA_IDCF": re.compile(r"_strava_idcf", re.I),
    "TOKEN": re.compile(r"(?:access|refresh)_token", re.I),
    "SIGNED_URL": re.compile(r"X-Amz-(?:Credential|Signature)|signed_?url", re.I),
}


def main() -> None:
    """Discover only explicit-root archives and write deterministic reports."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive-root", required=True, type=Path)
    parser.add_argument("--include", action="append", default=[])
    parser.add_argument("--exclude", action="append", default=[])
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--manifest-only", action="store_true")
    parser.add_argument("--strict", action="store_true")
    args = parser.parse_args()
    root = args.archive_root.resolve()
    includes = args.include or ["last-slide-debug*.zip", "problems-*.zip"]
    paths = discover(root, includes, args.exclude)
    if len(paths) > 1_000:
        parser.error("archive count exceeds limit")
    records = []
    failed = False
    for path in paths:
        relative = path.relative_to(root).as_posix()
        path_findings = scan_label(relative)
        try:
            bundle_names: list[str] = []
            member_findings = []
            inspection = SafeArchiveReader().inspect(
                path,
                on_bundle=lambda bundle: bundle_names.append(bundle.name),
                on_member=lambda member: member_findings.extend(scan_members((member,))),
            )
            findings = merge_findings(path_findings, member_findings)
            status = "quarantined" if findings else "validated"
            failed |= bool(findings)
            public_relative, public_basename = public_names(relative, path.name,
                inspection.sha256, bool(path_findings))
            records.append({"relativePath": public_relative, "basename": public_basename,
                "byteSize": inspection.byte_size, "sha256": inspection.sha256, "status": status,
                "nestedBundleCount": len(bundle_names), "privacyFindings": findings,
                "warnings": ["PRIVACY"] if findings else []})
        except ArchiveError as error:
            failed = True
            digest, byte_size = rejected_identity(path)
            public_relative, public_basename = public_names(relative, path.name,
                digest, bool(path_findings))
            records.append({"relativePath": public_relative, "basename": public_basename,
                "byteSize": byte_size, "sha256": digest, "status": "quarantined",
                "nestedBundleCount": 0, "privacyFindings": path_findings,
                "warnings": sorted(set([error.code] + (["PRIVACY"] if path_findings else [])))})
    ordered_hashes = sorted((record["sha256"] for record in records if record["status"] == "validated"),
        key=lambda value: hashlib.sha256(value.encode("ascii")).hexdigest())
    training_end = math.ceil(len(ordered_hashes) * 0.6)
    validation_end = training_end + math.ceil(len(ordered_hashes) * 0.2)
    roles = {value: "training" if index < training_end else
             ("validation" if index < validation_end else "holdout")
             for index, value in enumerate(ordered_hashes)}
    split = {"schemaVersion": 1, "quarantinedArchiveCount": len(records) - len(ordered_hashes), "groups": [
        {"outerArchiveSha256": value, "role": roles[value]} for value in sorted(roles)]}
    payload = {"schemaVersion": 1, "archiveCount": len(records), "archives": records}
    encoded = (json.dumps(payload, sort_keys=True, separators=(",", ":")) + "\n").encode()
    output = args.output_dir
    output.mkdir(parents=True, exist_ok=True)
    (output / "archive-manifest.json").write_bytes(encoded)
    manifest_hash = hashlib.sha256(encoded).hexdigest()
    (output / "archive-manifest.sha256").write_text(manifest_hash + "\n", encoding="ascii")
    (output / "split-lock.json").write_text(
        json.dumps(split, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    print(f"archives={len(records)} quarantined={sum(r['status'] == 'quarantined' for r in records)} "
          f"manifest_sha256={manifest_hash}")
    if failed and args.strict:
        raise SystemExit(2)


def discover(root: Path, includes: list[str], excludes: list[str]) -> list[Path]:
    """Return deterministic matches at root or one directory below it."""
    selected = {}
    for pattern in includes:
        for candidate_pattern in (pattern, f"*/{pattern}"):
            for path in root.glob(candidate_pattern):
                try:
                    metadata = path.lstat()
                except OSError:
                    continue
                if stat.S_ISREG(metadata.st_mode) or stat.S_ISLNK(metadata.st_mode):
                    relative = path.relative_to(root).as_posix()
                    if not any(fnmatch.fnmatch(relative, item) or fnmatch.fnmatch(path.name, item)
                               for item in excludes):
                        selected[relative] = path
    return [selected[key] for key in sorted(selected)]


def scan_members(members) -> list[dict[str, object]]:
    """Scan the complete validated inventory without returning matched values."""
    totals = {}
    seen = set()
    for member in members:
        identity = (member.name, hashlib.sha256(member.text.encode("utf-8")).hexdigest())
        if identity in seen:
            continue
        seen.add(identity)
        member_hash = hashlib.sha256(member.name.encode()).hexdigest()
        for rule, pattern in RULES.items():
            count = len(pattern.findall(member.name)) + len(pattern.findall(member.text))
            if count:
                totals[(rule, member_hash)] = totals.get((rule, member_hash), 0) + count
    return [{"rule": rule, "severity": "high", "memberHash": member, "count": count}
            for (rule, member), count in sorted(totals.items())]


def scan_label(value: str) -> list[dict[str, object]]:
    """Scan an outer relative name and return only redacted rule/count evidence."""
    label_hash = hashlib.sha256(value.encode("utf-8", "replace")).hexdigest()
    return [{"rule": rule, "severity": "high", "memberHash": label_hash, "count": len(matches)}
            for rule, pattern in sorted(RULES.items()) if (matches := pattern.findall(value))]


def merge_findings(*groups) -> list[dict[str, object]]:
    """Merge redacted findings deterministically without duplicating identities."""
    totals = {}
    for group in groups:
        for finding in group:
            key = (finding["rule"], finding["memberHash"])
            totals[key] = totals.get(key, 0) + int(finding["count"])
    return [{"rule": rule, "severity": "high", "memberHash": member, "count": count}
            for (rule, member), count in sorted(totals.items())]


def public_names(relative: str, basename: str, digest: str, redact: bool) -> tuple[str, str]:
    """Hide an outer name itself when it triggered a credential-like rule."""
    if not redact:
        return relative, basename
    marker = f"<redacted-{digest[:12]}>"
    return marker, marker


def rejected_identity(path: Path) -> tuple[str, int]:
    """Return a bounded identity for a rejected input without following symlinks."""
    digest = hashlib.sha256()
    descriptor = -1
    try:
        descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0))
        metadata = os.fstat(descriptor)
        if stat.S_ISREG(metadata.st_mode) and metadata.st_size <= 96 * 1024**2:
            with os.fdopen(descriptor, "rb") as handle:
                descriptor = -1
                for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                    digest.update(chunk)
            return digest.hexdigest(), metadata.st_size
    except OSError:
        pass
    finally:
        if descriptor >= 0:
            os.close(descriptor)
    digest.update(path.name.encode("utf-8", "replace"))
    return digest.hexdigest(), 0


if __name__ == "__main__":
    main()
