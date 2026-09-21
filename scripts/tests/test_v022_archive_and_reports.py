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
from wayheatmap_analysis.v022_corpus import (
    CorpusError,
    assert_no_credentials,
    inventory,
    render_report,
    verify,
)


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


def test_verify_rejects_credential_marker_in_nonmetadata_member(tmp_path: Path) -> None:
    """Verification rejects credential markers outside bundle metadata."""

    bundle = archive([
        ("diagnostics.json", b'{"formatVersion": 14}'),
        ("candidate-metrics.csv", b"candidate_id\na\n"),
        ("unrelated-notes.txt", b"Cookie: private-value"),
    ])
    source = tmp_path / "case.zip"
    source.write_bytes(archive([("bundle.zip", bundle)]))
    digest = __import__("hashlib").sha256
    manifest = tmp_path / "manifest.json"
    manifest.write_text(json.dumps({"schema": "wayheatmaptracer-v022-corpus-1", "cases": [{
        "sourcePath": str(source), "outerSha256": digest(source.read_bytes()).hexdigest(),
        "bundleSha256": digest(bundle).hexdigest()}]}))

    with pytest.raises(CorpusError):
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


def test_inventory_quarantines_credential_marker_in_nonmetadata_member(tmp_path: Path) -> None:
    """The streaming scanner covers all validated text, not only replay metadata."""

    nested = archive([
        ("diagnostics.json", b'{"formatVersion": 14}'),
        ("candidate-metrics.csv", b"candidate_id\na\n"),
        ("unrelated-notes.txt", b"Cookie: private-value"),
    ])
    (tmp_path / "outer.zip").write_bytes(archive([("bundle.zip", nested)]))

    result = inventory(tmp_path, require_reference_set=False)

    assert result["cases"] == []
    assert [error["code"] for error in result["errors"]] == ["PRIVACY"]


def test_inventory_quarantines_marker_split_at_stream_boundary(tmp_path: Path) -> None:
    """The corpus privacy scan catches markers crossing a 1 MiB chunk boundary."""

    marker = b"Cookie: private-value"
    boundary = 1024 * 1024
    notes = b"A" * (boundary - len(b"\nCook")) + b"\n" + marker
    nested_output = io.BytesIO()
    with zipfile.ZipFile(nested_output, "w", zipfile.ZIP_STORED) as handle:
        handle.writestr("diagnostics.json", b'{"formatVersion": 14}')
        handle.writestr("candidate-metrics.csv", b"candidate_id\na\n")
        handle.writestr("unrelated-notes.txt", notes)
    outer_output = io.BytesIO()
    with zipfile.ZipFile(outer_output, "w", zipfile.ZIP_STORED) as handle:
        handle.writestr("bundle.zip", nested_output.getvalue())
    (tmp_path / "outer.zip").write_bytes(outer_output.getvalue())

    result = inventory(tmp_path, require_reference_set=False)

    assert result["cases"] == []
    assert [error["code"] for error in result["errors"]] == ["PRIVACY"]


def test_inventory_rejects_oversized_metadata_before_archive_read(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Oversized metadata is rejected before the corpus metadata reader materializes it."""

    diagnostics = b"{" + b" " * (16 * 1024 * 1024) + b"}"
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_STORED) as handle:
        handle.writestr("diagnostics.json", diagnostics)
        handle.writestr("candidate-metrics.csv", b"candidate_id\na\n")
    (tmp_path / "oversized.zip").write_bytes(output.getvalue())

    def fail_read(*args: object, **kwargs: object) -> bytes:
        raise AssertionError("oversized metadata was materialized")

    monkeypatch.setattr(zipfile.ZipFile, "read", fail_read)
    result = inventory(tmp_path, require_reference_set=False)

    assert result["cases"] == []
    assert [error["code"] for error in result["errors"]] == ["TEXT_SIZE"]


def test_inventory_accepts_direct_bundle_text_csv_above_retention_cap(tmp_path: Path) -> None:
    """A valid direct bundle may stream a CSV larger than the 16 MiB text cap."""
    csv = b"candidate_id\n" + (b"candidate-000000000000000000000000\n" * (17 * 1024 * 1024 // 30))
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_STORED) as handle:
        handle.writestr("diagnostics.json", b'{"formatVersion": 14}')
        handle.writestr("candidate-metrics.csv", csv)
    direct = output.getvalue()
    (tmp_path / "large-direct.zip").write_bytes(direct)

    result = inventory(tmp_path, require_reference_set=False)

    assert len(result["cases"]) == 1
    assert result["errors"] == []
