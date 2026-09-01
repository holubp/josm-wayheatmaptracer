"""Hostile-metadata and limit tests for the shared ZIP reader."""
import io
import tempfile
import zipfile
from pathlib import Path

import pytest

import wayheatmap_analysis.safe_zip as safe_zip_module
from wayheatmap_analysis.safe_zip import ArchiveError, ArchiveLimits, SafeArchiveReader


def make(entries):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, value in entries:
            archive.writestr(name, value)
    return output.getvalue()


def discover(data, limits=None):
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "outer.zip"
        path.write_bytes(data)
        return SafeArchiveReader(limits).discover(path)


def bundle():
    return make([("diagnostics.json", b"{}"), ("candidate-metrics.csv", b"candidate_id\na\n")])


def test_valid_depth_eight_and_rejects_depth_nine():
    data = bundle()
    for index in range(8):
        data = make([(f"{index}.zip", data)])
    assert len(discover(data)) == 1
    data = make([("ninth.zip", data)])
    with pytest.raises(ArchiveError, match="nesting") as caught:
        discover(data)
    assert caught.value.code == "NESTING_DEPTH"


@pytest.mark.parametrize("name", ["../x", "/x", "C:/x", "\\\\host\\x", "a/../../x"])
def test_rejects_unsafe_paths(name):
    with pytest.raises(ArchiveError) as caught:
        discover(make([(name, b"x")]))
    assert caught.value.code == "UNSAFE_PATH"


def test_rejects_symlink_ratio_and_conflicting_duplicates():
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        info = zipfile.ZipInfo("link")
        info.create_system = 3
        info.external_attr = 0o120777 << 16
        archive.writestr(info, b"target")
    with pytest.raises(ArchiveError) as caught:
        discover(output.getvalue())
    assert caught.value.code == "UNSAFE_TYPE"
    with pytest.raises(ArchiveError) as caught:
        discover(make([("large", b"0" * 4096)]), ArchiveLimits(max_ratio=2.0))
    assert caught.value.code == "COMPRESSION_RATIO"
    with pytest.warns(UserWarning):
        duplicate = make([("same", b"x"), ("same", b"y")])
    with pytest.raises(ArchiveError) as caught:
        discover(duplicate)
    assert caught.value.code == "DUPLICATE_CONFLICT"


def test_rejects_non_unix_special_type_and_symlinked_outer_file(tmp_path):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        info = zipfile.ZipInfo("link")
        info.create_system = 0
        info.external_attr = 0o120777 << 16
        archive.writestr(info, b"target")
    with pytest.raises(ArchiveError) as caught:
        discover(output.getvalue())
    assert caught.value.code == "UNSAFE_TYPE"

    target = tmp_path / "target.zip"
    target.write_bytes(bundle())
    link = tmp_path / "link.zip"
    link.symlink_to(target)
    with pytest.raises(ArchiveError) as caught:
        SafeArchiveReader().discover(link)
    assert caught.value.code == "UNSAFE_INPUT"


def test_accepts_identical_duplicates_and_supported_legacy_codecs():
    with pytest.warns(UserWarning):
        duplicate = make([("same.txt", b"x"), ("same.txt", b"x")])
    assert discover(duplicate) == []
    for codec in (zipfile.ZIP_BZIP2, zipfile.ZIP_LZMA):
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w", codec) as archive:
            archive.writestr("candidate-metrics.csv", b"candidate_id\na\n")
        assert len(discover(output.getvalue())) == 1


def test_rejects_entry_member_and_materialization_limits():
    with pytest.raises(ArchiveError) as caught:
        discover(make([("a", b"x"), ("b", b"x")]), ArchiveLimits(max_entries_per_zip=1))
    assert caught.value.code == "ENTRY_LIMIT"
    with pytest.raises(ArchiveError) as caught:
        discover(make([("a", b"xx")]), ArchiveLimits(max_member_bytes=1))
    assert caught.value.code == "MEMBER_SIZE"
    with pytest.raises(ArchiveError) as caught:
        discover(make([("nested.zip", bundle())]), ArchiveLimits(max_materialized_bytes=100))
    assert caught.value.code == "MEMORY_LIMIT"
    with pytest.raises(ArchiveError) as caught:
        discover(make([("diagnostics.json", b"x" * 32)]), ArchiveLimits(max_text_member_bytes=16))
    assert caught.value.code == "TEXT_SIZE"


def test_rejects_encryption_codec_and_crc_metadata_tampering():
    encrypted = bytearray(make([("a.txt", b"value")]))
    encrypted[6:8] = (1).to_bytes(2, "little")
    central = encrypted.index(b"PK\x01\x02")
    encrypted[central + 8:central + 10] = (1).to_bytes(2, "little")
    with pytest.raises(ArchiveError) as caught:
        discover(bytes(encrypted))
    assert caught.value.code == "ENCRYPTED"

    unsupported = bytearray(make([("a.txt", b"value")]))
    unsupported[8:10] = (99).to_bytes(2, "little")
    central = unsupported.index(b"PK\x01\x02")
    unsupported[central + 10:central + 12] = (99).to_bytes(2, "little")
    with pytest.raises(ArchiveError) as caught:
        discover(bytes(unsupported))
    assert caught.value.code == "UNSUPPORTED_COMPRESSION"

    stored = io.BytesIO()
    with zipfile.ZipFile(stored, "w", zipfile.ZIP_STORED) as archive:
        archive.writestr("a.txt", b"unique-payload")
    corrupted = bytearray(stored.getvalue())
    position = corrupted.index(b"unique-payload")
    corrupted[position] ^= 0x01
    with pytest.raises(ArchiveError) as caught:
        discover(bytes(corrupted))
    assert caught.value.code == "MALFORMED_ZIP"


def test_inspection_is_deterministic_for_flat_and_nested_bundles(tmp_path):
    nested = make([("nested.zip", bundle()), ("notes.txt", b"safe")])
    path = tmp_path / "outer.zip"
    path.write_bytes(nested)

    first = SafeArchiveReader().inspect(path)
    second = SafeArchiveReader().inspect(path)

    assert first.sha256 == second.sha256
    assert [item.name for item in first.bundles] == [item.name for item in second.bundles]
    assert [item.name for item in first.scannable_members] == [
        item.name for item in second.scannable_members
    ]


def test_rejects_total_compressed_and_uncompressed_budgets():
    data = make([("a.txt", b"a" * 32), ("b.txt", b"b" * 32)])
    with pytest.raises(ArchiveError) as caught:
        discover(data, ArchiveLimits(max_total_entries=1))
    assert caught.value.code == "TOTAL_ENTRY_LIMIT"

    with pytest.raises(ArchiveError) as caught:
        discover(data, ArchiveLimits(max_compressed_bytes=1))
    assert caught.value.code == "COMPRESSED_SIZE"

    with pytest.raises(ArchiveError) as caught:
        discover(data, ArchiveLimits(max_uncompressed_bytes=63))
    assert caught.value.code == "UNCOMPRESSED_SIZE"


def test_rejects_raw_nul_member_name():
    data = bytearray(make([("badXname", b"value")]))
    position = 0
    replacements = 0
    while True:
        position = data.find(b"badXname", position)
        if position < 0:
            break
        data[position + 3] = 0
        position += 1
        replacements += 1
    assert replacements == 2

    with pytest.raises(ArchiveError) as caught:
        discover(bytes(data))

    assert caught.value.code == "UNSAFE_PATH"

def test_many_nested_bundles_are_processed_with_bounded_peak_materialization():
    """Sibling debug bundles do not consume one cumulative materialization budget."""
    nested = bundle()
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_STORED) as archive:
        for index in range(24):
            archive.writestr(f"bundle-{index}.zip", nested)

    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "outer.zip"
        path.write_bytes(output.getvalue())
        bundle_names: list[str] = []
        SafeArchiveReader(ArchiveLimits(
            max_peak_materialized_bytes=len(output.getvalue()) + 2 * len(nested) + 128 * 1024,
            max_uncompressed_bytes=100_000,
        )).for_each_bundle(path, lambda found: bundle_names.append(found.name))
    assert len(bundle_names) == 24

def test_streaming_releases_finished_sibling_inner_expansion_metadata():
    """The 384 MiB-style limit applies to one active nested branch, not past siblings."""
    payload = bytes(range(100)) * 100
    nested = make([
        ("diagnostics.json", b"{}"),
        ("candidate-metrics.csv", b"candidate_id\n" + payload),
    ])
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_STORED) as archive:
        for index in range(10):
            archive.writestr(f"bundle-{index}.zip", nested)

    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "outer.zip"
        path.write_bytes(output.getvalue())
        names: list[str] = []
        SafeArchiveReader(ArchiveLimits(
            max_cumulative_expansion_bytes=200_000,
            max_peak_materialized_bytes=len(output.getvalue()) + 2 * len(nested) + 2 * len(payload) + 32 * 1024,
        )).for_each_bundle(path, lambda found: names.append(found.name))

    assert len(names) == 10


def test_retained_scannable_text_is_bounded_or_streamed():
    """Decoded member strings either count toward the peak or leave through a callback."""

    payload = b"A" * 200_000
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_STORED) as archive:
        for index in range(6):
            archive.writestr(f"member-{index}.txt", payload)
    limit = len(output.getvalue()) + 2 * len(payload) + 32 * 1024

    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "outer.zip"
        path.write_bytes(output.getvalue())
        with pytest.raises(ArchiveError) as caught:
            SafeArchiveReader(ArchiveLimits(
                max_peak_materialized_bytes=limit,
                max_text_member_bytes=len(payload) + 1,
            )).inspect(path)
        streamed: list[str] = []
        inspection = SafeArchiveReader(ArchiveLimits(
            max_peak_materialized_bytes=limit,
            max_text_member_bytes=len(payload) + 1,
        )).inspect(path, on_member=lambda member: streamed.append(member.name))

    assert caught.value.code == "MEMORY_LIMIT"
    assert len(set(streamed)) == 6
    assert len(streamed) >= 6
    assert inspection.scannable_members == ()


def test_retained_member_names_and_zip_inventory_are_peak_bounded(monkeypatch):
    """Long member names cannot evade the peak-materialization limit."""

    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_STORED) as archive:
        for index in range(256):
            name = f"{index:04d}-" + "n" * 780 + ".png"
            archive.writestr(name, b"")
    limit = len(output.getvalue()) + 64 * 1024
    opened = False
    original_zip_file = zipfile.ZipFile

    def tracking_zip_file(*args, **kwargs):
        nonlocal opened
        opened = True
        return original_zip_file(*args, **kwargs)

    monkeypatch.setattr(safe_zip_module.zipfile, "ZipFile", tracking_zip_file)

    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "outer.zip"
        path.write_bytes(output.getvalue())
        with pytest.raises(ArchiveError) as caught:
            SafeArchiveReader(ArchiveLimits(
                max_peak_materialized_bytes=limit,
                max_entries_per_zip=512,
                max_total_entries=512,
            )).inspect(path)

    assert caught.value.code == "MEMORY_LIMIT"
    assert not opened


def test_nonstreaming_discovery_charges_retained_bundle_payloads():
    """List-returning compatibility API cannot retain siblings beyond the peak limit."""
    nested = bundle()
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_STORED) as archive:
        for index in range(8):
            archive.writestr(f"bundle-{index}.zip", nested)

    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "outer.zip"
        path.write_bytes(output.getvalue())
        with pytest.raises(ArchiveError) as caught:
            SafeArchiveReader(ArchiveLimits(
                max_peak_materialized_bytes=len(output.getvalue()) + len(nested) * 2,
                max_uncompressed_bytes=100_000,
            )).discover(path)

    assert caught.value.code == "MEMORY_LIMIT"


def test_nested_expansion_budget_is_cumulative_within_active_branch():
    """Nested descendants share one active 384 MiB-style expansion budget."""
    inner = make([
        ("diagnostics.json", b"{}"),
        ("candidate-metrics.csv", b"candidate_id\\n" + b"A" * 10_000),
    ])
    middle = make([("inner.zip", inner)])
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_STORED) as archive:
        archive.writestr("middle.zip", middle)

    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "outer.zip"
        path.write_bytes(output.getvalue())
        with pytest.raises(ArchiveError) as caught:
            SafeArchiveReader(ArchiveLimits(
                max_peak_materialized_bytes=100_000,
                max_cumulative_expansion_bytes=len(middle) + len(inner) + 5_000,
                max_ratio=10_000.0,
            )).for_each_bundle(path, lambda found: None)
    assert caught.value.code == "UNCOMPRESSED_SIZE"
