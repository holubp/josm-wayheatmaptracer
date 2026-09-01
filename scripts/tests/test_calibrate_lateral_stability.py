"""CLI privacy and determinism tests for calibration manifests."""
import json
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "calibrate-lateral-stability.py"


def write_bundle(path: Path, text: str = "") -> None:
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("diagnostics.json", "{}")
        archive.writestr("candidate-metrics.csv", "candidate_id\na\n")
        if text:
            archive.writestr("verbose.log", text)


def test_manifest_is_relative_split_locked_and_privacy_redacted():
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        write_bundle(root / "last-slide-debug-a.zip")
        write_bundle(root / "last-slide-debug-b.zip", "Authorization: Bearer never-echo")
        output = root / "out"
        result = subprocess.run([sys.executable, str(SCRIPT), "--archive-root", str(root),
            "--output-dir", str(output), "--manifest-only"], cwd=ROOT,
            check=True, capture_output=True, text=True)
        manifest = (output / "archive-manifest.json").read_text()
        assert "AUTHORIZATION" in manifest
        assert "never-echo" not in manifest + result.stdout
        assert str(root) not in manifest + result.stdout
        payload = json.loads(manifest)
        assert [archive["nestedBundleCount"] for archive in payload["archives"]] == [1, 1]
        split = json.loads((output / "split-lock.json").read_text())
        assert len(split["groups"]) == 1
        assert split["quarantinedArchiveCount"] == 1


def test_outer_wrapper_privacy_and_symlink_are_quarantined_and_manifest_is_deterministic():
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        write_bundle(root / "inner.zip")
        with zipfile.ZipFile(root / "problems-wrapper.zip", "w") as archive:
            archive.writestr("inner.zip", (root / "inner.zip").read_bytes())
            archive.writestr("settings.properties", "CloudFront-Signature=never-echo")
            archive.comment = b"Authorization marker"
        link = root / "problems-link.zip"
        link.symlink_to(root / "inner.zip")
        sensitive_name = root / "problems-CloudFront-Signature.zip"
        write_bundle(sensitive_name)

        outputs = []
        for name in ("out-a", "out-b"):
            output = root / name
            subprocess.run([sys.executable, str(SCRIPT), "--archive-root", str(root),
                "--output-dir", str(output), "--manifest-only"], cwd=ROOT,
                check=True, capture_output=True, text=True)
            outputs.append((output / "archive-manifest.json").read_bytes())
            manifest = outputs[-1].decode()
            assert "CLOUDFRONT" in manifest
            assert "AUTHORIZATION" in manifest
            assert "never-echo" not in manifest
            assert sensitive_name.name not in manifest
            assert json.loads((output / "split-lock.json").read_text())["groups"] == []
        assert outputs[0] == outputs[1]
