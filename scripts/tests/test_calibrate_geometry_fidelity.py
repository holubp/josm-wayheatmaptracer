"""End-to-end tests for the private-corpus manifest-only CLI."""

from __future__ import annotations

import hashlib
import io
import json
import subprocess
import sys
import zipfile
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[2]
SCRIPT = REPOSITORY / "scripts" / "calibrate-geometry-fidelity.py"


def make_bundle(*, plugin_version: str = "0.20.2") -> bytes:
    result = io.BytesIO()
    original = b"<osm><node id='1' lat='50.123456' lon='14.123456'/><node id='2' lat='50.124456' lon='14.124456'/><way id='7'><nd ref='1'/><nd ref='2'/></way></osm>"
    with zipfile.ZipFile(result, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("manifest.json", json.dumps({"formatVersion": 12, "pluginVersion": plugin_version}))
        archive.writestr("diagnostics.json", json.dumps({"candidates": [{"id": "hot/a"}]}))
        archive.writestr("status.json", json.dumps({"selectedCandidate": "hot/a", "status": "applied"}))
        archive.writestr("candidate-ratings.json", json.dumps({"hot/a": {"rating": "+"}}))
        archive.writestr("candidate-metrics.csv", "candidate_id\nhot/a\n")
        archive.writestr("profile-intensity.csv", "profile_index,intensity\n0,1\n")
        archive.writestr("optimizer-costs.csv", "candidate_id\nhot/a\n")
        archive.writestr("geometry-cleanup.csv", "candidate_id\nhot/a\n")
        archive.writestr("geometry-cleanup-anchors.csv", "candidate_id\nhot/a\n")
        archive.writestr("geometry-cleanup-local-shape.csv", "candidate_id,profile_index\nhot/a,0\n")
        archive.writestr("original-segment.osm", original)
    return result.getvalue()


def run_cli(root: Path, output: Path, *extra: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [
            sys.executable,
            str(SCRIPT),
            "--archive-root",
            str(root),
            "--include",
            "last-slide-debug*.zip",
            "--discover-osm",
            "--output-dir",
            str(output),
            "--manifest-only",
            *extra,
        ],
        cwd=REPOSITORY,
        text=True,
        capture_output=True,
        check=False,
    )


def test_manifest_only_cli_is_deterministic_redacted_and_pairs_unique_reference(tmp_path):
    root = tmp_path / "private-root"
    root.mkdir()
    (root / "last-slide-debug-example.zip").write_bytes(make_bundle())
    (root / "human-reference.osm").write_text(
        "<osm><node id='1' lat='50.123456' lon='14.123456'/><node id='2' lat='50.124456' lon='14.124456'/><way id='7'><nd ref='1'/><nd ref='2'/></way></osm>",
        encoding="utf-8",
    )
    first = tmp_path / "out-one"
    second = tmp_path / "out-two"

    assert run_cli(root, first).returncode == 0
    assert run_cli(root, second).returncode == 0

    first_manifest = (first / "private-manifest.json").read_bytes()
    second_manifest = (second / "private-manifest.json").read_bytes()
    assert first_manifest == second_manifest
    assert (first / "private-manifest.sha256").read_text().strip() == hashlib.sha256(first_manifest).hexdigest()
    payload = json.loads(first_manifest)
    assert payload["archives"][0]["debugFormatVersion"] == 12
    assert payload["archives"][0]["pluginVersion"] == "0.20.2"
    assert payload["archives"][0]["candidateCount"] == 1
    assert payload["archives"][0]["selectedCandidateId"] == "hot/a"
    assert payload["archives"][0]["appliedCandidateId"] == "hot/a"
    assert payload["archives"][0]["ratingCount"] == 1
    assert payload["archives"][0]["cleanupArtifacts"] == [
        "geometry-cleanup-anchors.csv",
        "geometry-cleanup-local-shape.csv",
        "geometry-cleanup.csv",
    ]
    assert payload["archives"][0]["replayability"] in {"R1", "R2", "R3", "R4"}
    assert payload["osmFiles"][0]["ways"][0]["wayId"] == 7
    assert json.loads((first / "pairs.local.yaml").read_text())["pairs"][0]["status"] == "paired"

    serialized = first_manifest.decode() + (first / "pairs.local.yaml").read_text()
    assert str(root) not in serialized
    assert "50.123456" not in serialized
    assert "14.123456" not in serialized


def test_secret_bearing_labels_are_replaced_and_strict_quarantine_fails(tmp_path):
    root = tmp_path / "private-root"
    root.mkdir()
    unsafe = root / "last-slide-debug-CloudFront-Signature-secret.zip"
    unsafe.write_bytes(make_bundle())
    output = tmp_path / "out"

    result = run_cli(root, output, "--strict")

    assert result.returncode == 2
    manifest = (output / "private-manifest.json").read_text()
    assert "CloudFront" not in manifest
    assert "secret" not in manifest
    assert "<redacted-" in manifest


def test_cli_does_not_discover_beyond_three_levels_or_follow_symlinks(tmp_path):
    root = tmp_path / "private-root"
    deep = root / "one" / "two" / "three" / "four"
    deep.mkdir(parents=True)
    (deep / "last-slide-debug-hidden.zip").write_bytes(make_bundle())
    outside = tmp_path / "outside.zip"
    outside.write_bytes(make_bundle())
    (root / "last-slide-debug-link.zip").symlink_to(outside)
    output = tmp_path / "out"

    result = run_cli(root, output)

    assert result.returncode == 0
    payload = json.loads((output / "private-manifest.json").read_text())
    assert payload["archiveCount"] == 1
    assert payload["archives"][0]["status"] == "quarantined"
