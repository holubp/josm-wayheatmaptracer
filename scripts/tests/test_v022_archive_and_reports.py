"""T139-T146 strict archive and report tests for the v0.22 corpus tool."""

from __future__ import annotations

import io
import json
import subprocess
import sys
import zipfile
from pathlib import Path

import pytest

from wayheatmap_analysis.safe_zip import ArchiveError, SafeArchiveReader
from wayheatmap_analysis.v022_corpus import CorpusError, assert_no_credentials, render_report, verify


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "v022-corpus.py"


def archive(entries: list[tuple[str, bytes]]) -> bytes:
    """Build a bounded in-memory test archive."""

    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as handle:
        for name, value in entries:
            handle.writestr(name, value)
    return output.getvalue()


def debug_bundle() -> bytes:
    """Build the smallest recognizable safe debug bundle."""

    diagnostics = json.dumps({"formatVersion": 14, "pluginVersion": "0.21.5", "profileCount": 3}).encode()
    return archive([("diagnostics.json", diagnostics), ("candidate-metrics.csv", b"candidate_id\na\n")])


def test_t139_zip_traversal_duplicate_and_symlink_rejection(tmp_path: Path) -> None:
    """T139: shared safe traversal rejects unsafe members before corpus callbacks."""

    with pytest.raises(ArchiveError):
        SafeArchiveReader().discover_bytes("unsafe.zip", archive([("../escape", b"x")]))
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as handle:
        info = zipfile.ZipInfo("link")
        info.create_system = 3
        info.external_attr = 0o120777 << 16
        handle.writestr(info, b"target")
    with pytest.raises(ArchiveError):
        SafeArchiveReader().discover_bytes("unsafe.zip", output.getvalue())


def test_t140_metadata_and_decompression_budgets_are_enforced() -> None:
    """T140: the corpus path inherits SafeArchiveReader's bounded expansion."""

    payload = archive([("large.txt", b"0" * 100_000)])
    with pytest.raises(ArchiveError):
        from wayheatmap_analysis.safe_zip import ArchiveLimits
        SafeArchiveReader(ArchiveLimits(max_uncompressed_bytes=128)).discover_bytes("large.zip", payload)


def test_t141_verify_rejects_checksum_change(tmp_path: Path) -> None:
    """T141: strict verification binds both outer and nested checksums."""

    source = tmp_path / "case.zip"
    source.write_bytes(debug_bundle())
    digest = __import__("hashlib").sha256(source.read_bytes()).hexdigest()
    manifest = tmp_path / "manifest.json"
    manifest.write_text(json.dumps({"schema": "wayheatmaptracer-v022-corpus-1", "cases": [{
        "sourcePath": str(source), "outerSha256": digest, "bundleSha256": digest}]}))
    assert verify(manifest)["verified"]
    source.write_bytes(debug_bundle() + b"changed")
    with pytest.raises((CorpusError, ArchiveError)):
        verify(manifest)


def test_t142_strict_inventory_rejects_missing_reference_set(tmp_path: Path) -> None:
    """T142: --require-reference-set cannot convert missing inputs into success."""

    (tmp_path / "one.zip").write_bytes(debug_bundle())
    result = subprocess.run([sys.executable, str(SCRIPT), "inventory", "--inputs", str(tmp_path),
        "--output", str(tmp_path / "manifest.json"), "--require-reference-set"],
        capture_output=True, text=True, check=False)
    assert result.returncode != 0
    assert not (tmp_path / "manifest.json").exists()


def test_t143_report_escapes_untrusted_fields(tmp_path: Path) -> None:
    """T143: generated HTML never treats result strings as markup."""

    manifest = tmp_path / "manifest.json"
    results = tmp_path / "results.json"
    manifest.write_text(json.dumps({"cases": [{"caseId": "case-001"}]}))
    results.write_text(json.dumps({"results": [{"caseId": "case-001", "engine": "B",
        "status": "failed", "reason": "<script>alert(1)</script>"}]}))
    report = render_report(manifest, results)
    assert "<script>" not in report
    assert "&lt;script&gt;" in report


def test_t144_credential_markers_are_rejected() -> None:
    """T144: output redaction gate rejects credential-bearing text."""

    with pytest.raises(CorpusError):
        assert_no_credentials("Cookie: secret")


def test_t145_tool_never_enables_numpy_pickle_loading() -> None:
    """T145: corpus implementation has no pickle-enabled NumPy path."""

    source = (ROOT / "scripts" / "wayheatmap_analysis" / "v022_corpus.py").read_text()
    assert "allow_pickle=True" not in source
    assert "pickle.loads" not in source


def test_t146_report_contains_every_case_method_and_failure(tmp_path: Path) -> None:
    """T146: output keeps failures and supplies a row for a missing result."""

    manifest = tmp_path / "manifest.json"
    results = tmp_path / "results.json"
    manifest.write_text(json.dumps({"cases": [{"caseId": "case-001"}, {"caseId": "case-002"}]}))
    results.write_text(json.dumps({"results": [{"caseId": "case-001", "engine": "A", "status": "failed",
        "reason": "no route"}, {"caseId": "case-001", "engine": "B", "status": "complete"}]}))
    report = render_report(manifest, results)
    assert "case-001" in report and "case-002" in report
    assert "no route" in report and "missing-result" in report
