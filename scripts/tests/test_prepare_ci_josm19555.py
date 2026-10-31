from __future__ import annotations

import hashlib
import io
import json
import importlib.util
import os
from pathlib import Path
import zipfile

import pytest


HELPER_PATH = Path(__file__).resolve().parents[1] / "prepare-ci-josm19555.py"
SPEC = importlib.util.spec_from_file_location("prepare_ci_josm19555", HELPER_PATH)
assert SPEC is not None and SPEC.loader is not None
helper = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(helper)


def _jar(revision: str = "19555") -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("REVISION", f"Revision: {revision}\n")
    return output.getvalue()


class _Response(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *_args):
        self.close()


def _prepare(output: Path, content: bytes, **overrides):
    kwargs = {
        "expected_size": len(content),
        "expected_sha256": hashlib.sha256(content).hexdigest(),
        "expected_revision": "19555",
        "download_url": "https://example.invalid/test.jar",
        "asset_name": "test.jar",
        "opener": lambda _url, timeout: _Response(content),
    }
    kwargs.update(overrides)
    return helper.prepare_host(output, **kwargs)


def test_download_verifies_and_writes_redacted_fixed_receipt(tmp_path: Path) -> None:
    content = _jar()
    jar, receipt = _prepare(tmp_path, content)

    assert jar.read_bytes() == content
    data = json.loads(receipt.read_text(encoding="utf-8"))
    assert data == {
        "publisher": "JOSM/josm",
        "assetName": "test.jar",
        "url": "https://example.invalid/test.jar",
        "size": len(content),
        "sha256": hashlib.sha256(content).hexdigest(),
        "revision": "19555",
        "hostVariant": "official-linux-java11-19555",
    }
    assert sorted(path.name for path in tmp_path.iterdir()) == ["host-runtime.json", "test.jar"]


@pytest.mark.parametrize(
    ("overrides", "message"),
    [
        ({"expected_size": 1}, "size"),
        ({"expected_sha256": "0" * 64}, "unexpected SHA-256"),
        ({"expected_revision": "19556"}, "unexpected REVISION"),
    ],
)
def test_download_rejects_size_hash_or_revision_mismatch(
    tmp_path: Path, overrides: dict[str, object], message: str
) -> None:
    content = _jar()
    with pytest.raises(ValueError, match=message):
        _prepare(tmp_path, content, **overrides)
    assert not (tmp_path / "test.jar").exists()
    assert sorted(path.name for path in tmp_path.iterdir()) == []


def test_cached_valid_target_is_reused_without_download(tmp_path: Path) -> None:
    content = _jar()
    target = tmp_path / "test.jar"
    target.write_bytes(content)
    expected_sha256 = hashlib.sha256(content).hexdigest()
    receipt = tmp_path / "host-runtime.json"
    helper._write_receipt(
        receipt,
        "https://example.invalid/test.jar",
        "test.jar",
        len(content),
        expected_sha256,
        "19555",
    )
    original_receipt = receipt.read_bytes()

    def unexpected_download(*_args, **_kwargs):
        raise AssertionError("valid cached host should not be downloaded")

    jar, receipt = helper.prepare_host(
        tmp_path,
        expected_size=len(content),
        expected_sha256=expected_sha256,
        expected_revision="19555",
        asset_name="test.jar",
        download_url="https://example.invalid/test.jar",
        opener=unexpected_download,
    )

    assert jar == target
    assert jar.read_bytes() == content
    assert receipt.is_file()
    assert receipt.read_bytes() == original_receipt


def test_invalid_existing_target_is_rejected_and_unrelated_files_survive(tmp_path: Path) -> None:
    unrelated = tmp_path / "keep.txt"
    unrelated.write_text("private local artifact", encoding="utf-8")
    target = tmp_path / "test.jar"
    invalid = _jar("19554")
    target.write_bytes(invalid)
    original = target.read_bytes()

    with pytest.raises(ValueError, match="unexpected REVISION"):
        helper.prepare_host(
            tmp_path,
            expected_size=len(invalid),
            expected_sha256=hashlib.sha256(invalid).hexdigest(),
            expected_revision="19555",
            asset_name="test.jar",
            download_url="https://example.invalid/test.jar",
        )

    assert target.read_bytes() == original
    assert unrelated.read_text(encoding="utf-8") == "private local artifact"
    assert not (tmp_path / "host-runtime.json").exists()


def test_unrelated_existing_receipt_is_refused_and_preserved(tmp_path: Path) -> None:
    content = _jar()
    target = tmp_path / "test.jar"
    target.write_bytes(content)
    receipt = tmp_path / "host-runtime.json"
    unrelated_receipt = b'{"owner":"local","private":"keep"}\n'
    receipt.write_bytes(unrelated_receipt)

    with pytest.raises(ValueError, match="does not match expected metadata"):
        helper.prepare_host(
            tmp_path,
            expected_size=len(content),
            expected_sha256=hashlib.sha256(content).hexdigest(),
            expected_revision="19555",
            asset_name="test.jar",
            download_url="https://example.invalid/test.jar",
            opener=lambda *_args, **_kwargs: pytest.fail("cached JAR should not download"),
        )

    assert receipt.read_bytes() == unrelated_receipt
    assert target.read_bytes() == content


def test_oversized_existing_receipt_is_bounded_and_preserved(tmp_path: Path) -> None:
    content = _jar()
    target = tmp_path / "test.jar"
    target.write_bytes(content)
    receipt = tmp_path / "host-runtime.json"
    oversized_receipt = b"x" * 4097
    receipt.write_bytes(oversized_receipt)

    with pytest.raises(ValueError, match="size limit"):
        helper.prepare_host(
            tmp_path,
            expected_size=len(content),
            expected_sha256=hashlib.sha256(content).hexdigest(),
            expected_revision="19555",
            asset_name="test.jar",
            download_url="https://example.invalid/test.jar",
        )

    assert receipt.read_bytes() == oversized_receipt


def test_symlink_receipt_is_not_followed_or_replaced(tmp_path: Path) -> None:
    content = _jar()
    target = tmp_path / "test.jar"
    target.write_bytes(content)
    unrelated = tmp_path / "keep.json"
    unrelated.write_text('{"owner":"local"}', encoding="utf-8")
    receipt = tmp_path / "host-runtime.json"
    receipt.symlink_to(unrelated.name)

    with pytest.raises(ValueError, match="existing host receipt is invalid"):
        helper.prepare_host(
            tmp_path,
            expected_size=len(content),
            expected_sha256=hashlib.sha256(content).hexdigest(),
            expected_revision="19555",
            asset_name="test.jar",
            download_url="https://example.invalid/test.jar",
        )

    assert receipt.is_symlink()
    assert unrelated.read_text(encoding="utf-8") == '{"owner":"local"}'


@pytest.mark.parametrize("matching", [False, True])
def test_concurrent_receipt_is_reused_only_when_metadata_matches(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, matching: bool
) -> None:
    content = _jar()
    target = tmp_path / "test.jar"
    target.write_bytes(content)
    receipt = tmp_path / "host-runtime.json"
    expected_receipt = {
        "publisher": "JOSM/josm",
        "assetName": "test.jar",
        "url": "https://example.invalid/test.jar",
        "size": len(content),
        "sha256": hashlib.sha256(content).hexdigest(),
        "revision": "19555",
        "hostVariant": "official-linux-java11-19555",
    }
    concurrent_bytes = (
        (json.dumps(expected_receipt, sort_keys=True, indent=2) + "\n").encode("utf-8")
        if matching
        else b'{"owner":"local"}\n'
    )
    original_open = os.open
    injected = False

    def create_receipt_during_publish(path, flags, mode=0o777):
        nonlocal injected
        if Path(path) == receipt and flags & os.O_CREAT:
            injected = True
            receipt.write_bytes(concurrent_bytes)
        return original_open(path, flags, mode)

    monkeypatch.setattr(helper.os, "open", create_receipt_during_publish)
    arguments = {
        "expected_size": len(content),
        "expected_sha256": hashlib.sha256(content).hexdigest(),
        "expected_revision": "19555",
        "asset_name": "test.jar",
        "download_url": "https://example.invalid/test.jar",
        "opener": lambda *_args, **_kwargs: pytest.fail("cached JAR should not download"),
    }

    if matching:
        helper.prepare_host(tmp_path, **arguments)
    else:
        with pytest.raises(ValueError, match="does not match expected metadata"):
            helper.prepare_host(tmp_path, **arguments)

    assert injected
    assert receipt.read_bytes() == concurrent_bytes


def test_short_download_is_removed_without_touching_unrelated_file(tmp_path: Path) -> None:
    unrelated = tmp_path / "keep.bin"
    unrelated.write_bytes(b"keep")
    content = _jar()

    with pytest.raises(ValueError, match="unexpected file size"):
        _prepare(tmp_path, content, expected_size=len(content) + 1)

    assert unrelated.read_bytes() == b"keep"
    assert sorted(path.name for path in tmp_path.iterdir()) == ["keep.bin"]


def test_concurrent_unrelated_target_is_preserved_and_rejected(tmp_path: Path) -> None:
    content = _jar()
    target = tmp_path / "test.jar"
    concurrent_bytes = b"unrelated concurrent host artifact"

    def create_unrelated_target(_url: str, timeout: int) -> _Response:
        assert timeout == 60
        target.write_bytes(concurrent_bytes)
        return _Response(content)

    with pytest.raises(ValueError, match="unexpected file size|unexpected SHA-256"):
        _prepare(tmp_path, content, opener=create_unrelated_target)

    assert target.read_bytes() == concurrent_bytes
    assert not (tmp_path / "host-runtime.json").exists()
    assert sorted(path.name for path in tmp_path.iterdir()) == ["test.jar"]


def test_concurrent_valid_target_is_verified_and_reused(tmp_path: Path) -> None:
    content = _jar()
    target = tmp_path / "test.jar"
    downloads = 0

    def create_valid_target(_url: str, timeout: int) -> _Response:
        nonlocal downloads
        assert timeout == 60
        downloads += 1
        target.write_bytes(content)
        return _Response(content)

    jar, receipt = _prepare(tmp_path, content, opener=create_valid_target)

    assert downloads == 1
    assert jar == target
    assert target.read_bytes() == content
    assert receipt.is_file()
    assert sorted(path.name for path in tmp_path.iterdir()) == ["host-runtime.json", "test.jar"]


def test_workflow_pins_same_host_for_validation_and_both_gui_engines() -> None:
    import yaml

    workflow_path = HELPER_PATH.parents[1] / ".github" / "workflows" / "build.yml"
    workflow = yaml.load(workflow_path.read_text(encoding="utf-8"), Loader=yaml.BaseLoader)
    jobs = workflow["jobs"]
    public_steps = jobs["public-validation"]["steps"]
    gui_steps = jobs["public-gui-smoke"]["steps"]
    for steps in (public_steps, gui_steps):
        prepare = next(step for step in steps if step.get("name") == "Prepare pinned JOSM 19555 host")
        assert "scripts/prepare-ci-josm19555.py --output-dir" in prepare["run"]
        assert "ORG_GRADLE_PROJECT_josm19555HostJar=" in prepare["run"]
        assert "JOSM-Linux-java11-19555.jar" in prepare["run"]
    copy_receipt = next(step for step in public_steps if step.get("name") == "Include JOSM host receipt in public evidence")
    assert copy_receipt["if"] == "always()"
    assert "host-runtime.json" in copy_receipt["run"]
    assert "reports/java/html" in copy_receipt["run"]
    gui_copy_receipt = next(step for step in gui_steps if step.get("name") == "Include JOSM host receipt in GUI report")
    assert gui_copy_receipt["if"] == "always()"
    assert "${{ matrix.report }}" in gui_copy_receipt["run"]
    assert "host-runtime.json" in gui_copy_receipt["run"]
    gui_receipt_upload = next(step for step in gui_steps if step.get("name") == "Upload public GUI host receipt")
    assert gui_receipt_upload["if"] == "always()"
    assert gui_receipt_upload["with"]["name"] == "${{ matrix.report }}-host-runtime"
    assert gui_receipt_upload["with"]["path"] == "build/reports/${{ matrix.report }}/host-runtime.json"
    assert jobs["public-gui-smoke"]["strategy"]["matrix"]["include"] == [
        {"engine": "PROBABILISTIC", "report": "public-gui-smoke"},
        {"engine": "LEGACY_THEN_B", "report": "public-gui-smoke-hybrid"},
    ]
