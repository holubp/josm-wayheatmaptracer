#!/usr/bin/env python3
"""Inventory private geometry-fidelity evidence into deterministic redacted manifests."""

from __future__ import annotations

import argparse
import fnmatch
import hashlib
import io
import json
import os
import stat
from pathlib import Path

from wayheatmap_analysis.archive_manifest import analyze_bundle
from wayheatmap_analysis.pairing import pair_references
from wayheatmap_analysis.privacy import findings, safe_label
from wayheatmap_analysis.safe_osm import OsmError, OsmLimits, parse_osm_bytes
from wayheatmap_analysis.safe_zip import ArchiveError, SafeArchiveReader


MAX_ARCHIVES = 1_000


def main() -> None:
    """Discover only the explicit root and write canonical local-only outputs."""

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive-root", required=True, type=Path)
    parser.add_argument("--include", action="append", default=[])
    parser.add_argument("--exclude", action="append", default=[])
    parser.add_argument("--discover-osm", action="store_true")
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--manifest-only", action="store_true")
    parser.add_argument("--strict", action="store_true")
    args = parser.parse_args()
    root = args.archive_root.resolve(strict=True)
    if not root.is_dir():
        parser.error("archive root must be a directory")
    includes = args.include or ["last-slide-debug*.zip", "problems-*.zip"]
    archive_paths = discover(root, includes, args.exclude, include_osm=False)
    if len(archive_paths) > MAX_ARCHIVES:
        parser.error("archive count exceeds limit")

    archives = []
    bundle_ways = []
    failed = False
    reader = SafeArchiveReader()
    for path in archive_paths:
        relative = path.relative_to(root).as_posix()
        label_findings = findings(relative)
        try:
            analyses = []
            member_findings = []
            inspection = reader.inspect(
                path,
                on_bundle=lambda bundle: analyses.append(analyze_bundle(bundle)),
                on_member=lambda member: member_findings.extend(_member_findings((member,))),
            )
            privacy_findings = _merge_findings(label_findings, member_findings)
            bundle_ways.extend(way for analysis in analyses for way in analysis.ways)
            public_relative = safe_label(relative, inspection.sha256)
            summaries = [analysis.metadata for analysis in analyses]
            primary = summaries[0] if len(summaries) == 1 else {}
            status = "quarantined" if privacy_findings else "validated"
            failed |= status == "quarantined"
            archives.append({
                "relativePath": public_relative,
                "basename": safe_label(path.name, inspection.sha256),
                "byteSize": inspection.byte_size,
                "modificationTimeNs": _mtime_ns(path),
                "sha256": inspection.sha256,
                "status": status,
                "warnings": ["PRIVACY"] if privacy_findings else [],
                "privacyFindings": privacy_findings,
                "nestedBundleCount": len(summaries),
                "debugFormatVersion": primary.get("debugFormatVersion", 0),
                "pluginVersion": primary.get("pluginVersion", ""),
                "candidateCount": sum(int(item["candidateCount"]) for item in summaries),
                "cleanupArtifacts": sorted({value for item in summaries
                                            for value in item["cleanupArtifacts"]}),
                "intensityEvidence": sorted({value for item in summaries
                                             for value in item["intensityEvidence"]}),
                "profileEvidence": sorted({value for item in summaries
                                           for value in item["profileEvidence"]}),
                "selectedCandidateId": primary.get("selectedCandidateId", ""),
                "appliedCandidateId": primary.get("appliedCandidateId", ""),
                "ratingCount": sum(int(item["ratingCount"]) for item in summaries),
                "replayability": _maximum_replayability(summaries),
                "bundles": summaries,
            })
        except (ArchiveError, ValueError) as error:
            failed = True
            digest, size = _safe_identity(path)
            archives.append({
                "relativePath": safe_label(relative, digest),
                "basename": safe_label(path.name, digest),
                "byteSize": size,
                "modificationTimeNs": _mtime_ns(path),
                "sha256": digest,
                "status": "quarantined",
                "warnings": [getattr(error, "code", "MALFORMED_BUNDLE")],
                "privacyFindings": label_findings,
                "nestedBundleCount": 0,
                "debugFormatVersion": 0,
                "pluginVersion": "",
                "candidateCount": 0,
                "cleanupArtifacts": [],
                "intensityEvidence": [],
                "profileEvidence": [],
                "selectedCandidateId": "",
                "appliedCandidateId": "",
                "ratingCount": 0,
                "replayability": "R0",
                "bundles": [],
            })

    osm_records, references, osm_failed = _inventory_osm(root, args.exclude) if args.discover_osm \
        else ([], [], False)
    failed |= osm_failed
    decisions = pair_references(references, bundle_ways)
    ambiguous = any(decision.status == "ambiguous" for decision in decisions)
    failed |= ambiguous
    manifest = {
        "schemaVersion": 1,
        "archiveCount": len(archives),
        "osmFileCount": len(osm_records),
        "archives": sorted(archives, key=lambda item: (item["relativePath"], item["sha256"])),
        "osmFiles": sorted(osm_records, key=lambda item: (item["relativePath"], item["sha256"])),
        "pairingSummary": {
            "paired": sum(decision.status == "paired" for decision in decisions),
            "ambiguous": sum(decision.status == "ambiguous" for decision in decisions),
            "unmatched": sum(decision.status == "unmatched" for decision in decisions),
        },
    }
    output = args.output_dir
    output.mkdir(parents=True, exist_ok=True)
    encoded = _canonical(manifest)
    (output / "private-manifest.json").write_bytes(encoded)
    (output / "private-manifest.sha256").write_text(hashlib.sha256(encoded).hexdigest() + "\n",
                                                     encoding="ascii")
    pairs = {"schemaVersion": 1, "pairs": [decision.to_manifest() for decision in decisions]}
    (output / "pairs.local.yaml").write_bytes(_canonical(pairs))
    print(f"archives={len(archives)} osm_files={len(osm_records)} "
          f"paired={manifest['pairingSummary']['paired']} "
          f"ambiguous={manifest['pairingSummary']['ambiguous']}")
    if failed and args.strict:
        raise SystemExit(2)


def discover(root: Path, includes: list[str], excludes: list[str], *, include_osm: bool) -> list[Path]:
    """Return deterministic matches no deeper than three directories without following links."""

    selected = {}
    for current, directories, filenames in os.walk(root, topdown=True, followlinks=False):
        current_path = Path(current)
        depth = len(current_path.relative_to(root).parts)
        directories[:] = sorted(name for name in directories
                                if depth < 3 and not (current_path / name).is_symlink())
        if depth > 3:
            continue
        for name in sorted(filenames):
            relative = (current_path / name).relative_to(root).as_posix()
            is_osm = name.lower().endswith((".osm", ".osm.gz"))
            matches = is_osm if include_osm else any(
                fnmatch.fnmatch(name, pattern) or fnmatch.fnmatch(relative, pattern)
                for pattern in includes
            )
            if matches and not any(fnmatch.fnmatch(name, pattern)
                                   or fnmatch.fnmatch(relative, pattern) for pattern in excludes):
                selected[relative] = current_path / name
    return [selected[key] for key in sorted(selected)]


def _inventory_osm(root: Path, excludes: list[str]):
    """Inventory bounded OSM inputs and retain geometry only for local pairing."""

    records = []
    references = []
    failed = False
    for path in discover(root, [], excludes, include_osm=True):
        relative = path.relative_to(root).as_posix()
        digest, size = _safe_identity(path)
        label = safe_label(relative, digest)
        label_findings = findings(relative)
        try:
            source, digest, size = _read_regular(path, OsmLimits().max_compressed_bytes)
            document = parse_osm_bytes(source, source_name=path.name)
            status = "quarantined" if label_findings else "validated"
            failed |= status == "quarantined"
            records.append({"relativePath": label, "basename": safe_label(path.name, digest),
                            "byteSize": size, "modificationTimeNs": _mtime_ns(path),
                            "sha256": digest, "status": status,
                            "warnings": ["PRIVACY"] if label_findings else [],
                            "root": document.root_name, "format": document.format_name,
                            "nodeCount": document.node_count, "wayCount": document.way_count,
                            "relationCount": document.relation_count,
                            "ways": [signature.to_manifest() for signature in document.way_signatures]})
            if status == "validated":
                references.extend((digest, label, way) for way in document.ways)
        except (OSError, OsmError) as error:
            failed = True
            records.append({"relativePath": label, "basename": safe_label(path.name, digest),
                            "byteSize": size, "modificationTimeNs": _mtime_ns(path),
                            "sha256": digest, "status": "quarantined",
                            "warnings": [getattr(error, "code", "INPUT_FILE")],
                            "root": "", "format": "", "nodeCount": 0, "wayCount": 0,
                            "relationCount": 0, "ways": []})
    return records, references, failed


def _member_findings(members):
    """Scan bounded text and member labels for credential-like material."""

    result = []
    for member in members:
        result.extend(findings(member.name, identity=member.name))
        result.extend(findings(member.text, identity=member.name))
    return result


def _merge_findings(*groups):
    """Combine redacted privacy findings by rule and member hash."""

    totals = {}
    for group in groups:
        for item in group:
            key = (item["rule"], item["memberHash"])
            totals[key] = totals.get(key, 0) + int(item["count"])
    return [{"rule": rule, "severity": "high", "memberHash": member, "count": count}
            for (rule, member), count in sorted(totals.items())]


def _maximum_replayability(summaries):
    """Return the highest honest replayability among nested bundles."""

    levels = [item["replayability"] for item in summaries]
    return max(levels, key=lambda value: int(value[1:]), default="R0")


def _safe_identity(path: Path) -> tuple[str, int]:
    """Stream-hash a regular file without following links or retaining content."""

    descriptor = -1
    try:
        descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_CLOEXEC", 0)
                             | getattr(os, "O_NOFOLLOW", 0))
        metadata = os.fstat(descriptor)
        if not stat.S_ISREG(metadata.st_mode):
            raise OSError
        digest = hashlib.sha256()
        with os.fdopen(descriptor, "rb") as handle:
            descriptor = -1
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(chunk)
        return digest.hexdigest(), metadata.st_size
    except OSError:
        return hashlib.sha256(path.name.encode("utf-8", "replace")).hexdigest(), 0
    finally:
        if descriptor >= 0:
            os.close(descriptor)


def _read_regular(path: Path, maximum_bytes: int) -> tuple[bytes, str, int]:
    """Read one immutable bounded regular-file snapshot without following symlinks."""

    descriptor = -1
    try:
        descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_CLOEXEC", 0)
                             | getattr(os, "O_NOFOLLOW", 0))
        metadata = os.fstat(descriptor)
        if not stat.S_ISREG(metadata.st_mode):
            raise OsmError("UNSAFE_INPUT", "OSM input must be a regular non-symlink file")
        if metadata.st_size > maximum_bytes:
            raise OsmError("COMPRESSED_SIZE", "OSM input exceeds compressed-size limit")
        digest = hashlib.sha256()
        snapshot = io.BytesIO()
        with os.fdopen(descriptor, "rb") as handle:
            descriptor = -1
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                if snapshot.tell() + len(chunk) > maximum_bytes:
                    raise OsmError("COMPRESSED_SIZE", "OSM input exceeds compressed-size limit")
                digest.update(chunk)
                snapshot.write(chunk)
        return snapshot.getvalue(), digest.hexdigest(), metadata.st_size
    except OsmError:
        raise
    except OSError as error:
        raise OsmError("UNSAFE_INPUT", "cannot safely open OSM input") from error
    finally:
        if descriptor >= 0:
            os.close(descriptor)


def _mtime_ns(path: Path) -> int:
    """Return local traceability time without following a symbolic link."""

    try:
        return path.stat(follow_symlinks=False).st_mtime_ns
    except OSError:
        return 0


def _canonical(value) -> bytes:
    """Encode one deterministic newline-terminated JSON document."""

    return (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")


if __name__ == "__main__":
    main()
