#!/usr/bin/env python3
"""Fetch and verify the pinned official JOSM 19555 CI host runtime."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import tempfile
import urllib.error
import urllib.request
import zipfile


ASSET_NAME = "JOSM-Linux-java11-19555.jar"
DOWNLOAD_URL = "https://github.com/JOSM/josm/releases/download/19555/JOSM-Linux-java11-19555.jar"
EXPECTED_SIZE = 21_355_074
EXPECTED_SHA256 = "d2e2310e24fdf8f4b2d18094997446e443d5894e86f6eeac27a4241bad40338e"
EXPECTED_REVISION = "19555"
PUBLISHER = "JOSM/josm"
HOST_VARIANT = "official-linux-java11-19555"
RECEIPT_NAME = "host-runtime.json"
_CHUNK_SIZE = 64 * 1024


def _revision(jar_path: Path) -> str:
    try:
        with zipfile.ZipFile(jar_path) as archive:
            entry = archive.getinfo("REVISION")
            if entry.file_size > 4096:
                raise ValueError("host JAR REVISION entry exceeds the size limit")
            with archive.open(entry) as revision_file:
                content = revision_file.read(4097)
    except (OSError, zipfile.BadZipFile, KeyError) as error:
        raise ValueError("host JAR has no readable REVISION entry") from error
    if len(content) > 4096:
        raise ValueError("host JAR REVISION entry exceeds the size limit")
    match = re.search(rb"(?m)^Revision:\s*(\d+)\s*$", content)
    if match is None:
        raise ValueError("host JAR REVISION entry is not recognized")
    return match.group(1).decode("ascii")


def _verify(path: Path, *, expected_size: int, expected_sha256: str, expected_revision: str) -> None:
    try:
        mode = path.lstat().st_mode
    except OSError as error:
        raise ValueError("host JAR is unavailable") from error
    if not stat.S_ISREG(mode):
        raise ValueError("host JAR target must be a regular file")
    if path.stat().st_size != expected_size:
        raise ValueError("host JAR has an unexpected file size")
    digest = hashlib.sha256()
    with path.open("rb") as host_file:
        for chunk in iter(lambda: host_file.read(_CHUNK_SIZE), b""):
            digest.update(chunk)
    if digest.hexdigest() != expected_sha256:
        raise ValueError("host JAR has an unexpected SHA-256")
    if _revision(path) != expected_revision:
        raise ValueError("host JAR has an unexpected REVISION")


def prepare_host(
    output_dir: Path,
    *,
    expected_size: int = EXPECTED_SIZE,
    expected_sha256: str = EXPECTED_SHA256,
    expected_revision: str = EXPECTED_REVISION,
    download_url: str = DOWNLOAD_URL,
    asset_name: str = ASSET_NAME,
    opener=urllib.request.urlopen,
) -> tuple[Path, Path]:
    """Prepare the pinned JOSM host JAR and its runtime receipt.

    The host is streamed to an owned temporary file and published only after
    its size, SHA-256, and bounded ``REVISION`` entry match the expected
    metadata. A valid cached target is reused. Existing unrelated targets or
    receipts are never replaced.

    Args:
        output_dir: Directory that will contain the host JAR and receipt.
        expected_size: Required download size. The CLI uses the pinned default.
        expected_sha256: Required lowercase SHA-256. The CLI uses the pinned default.
        expected_revision: Required JOSM revision. The CLI uses the pinned default.
        download_url: Official asset URL used by the CLI and written to the receipt.
        asset_name: Published asset filename.
        opener: URL opener. This injection point lets tests provide bounded fake
            responses; normal CLI use relies on ``urllib.request.urlopen``.

    Returns:
        A pair containing the verified JAR path and its ``host-runtime.json`` path.

    Raises:
        OSError: If the output directory or receipt cannot be written.
        ValueError: If an existing target, download, or receipt fails verification.

    Limits:
        At most ``expected_size + 1`` response bytes are read, and the ZIP
        ``REVISION`` entry is limited to 4096 bytes. CLI use is pinned to one
        official GitHub release asset and the fixed metadata constants above.
    """
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    target = output_dir / asset_name
    receipt = output_dir / RECEIPT_NAME

    if target.exists() or target.is_symlink():
        _verify(
            target,
            expected_size=expected_size,
            expected_sha256=expected_sha256,
            expected_revision=expected_revision,
        )
        _write_receipt(receipt, download_url, asset_name, expected_size, expected_sha256, expected_revision)
        return target, receipt

    fd, temporary_name = tempfile.mkstemp(prefix=".josm19555-", suffix=".download", dir=output_dir)
    temporary = Path(temporary_name)
    digest = hashlib.sha256()
    byte_count = 0
    try:
        with os.fdopen(fd, "wb") as destination:
            try:
                response = opener(download_url, timeout=60)
            except (OSError, urllib.error.URLError) as error:
                raise ValueError("official JOSM host download failed") from error
            with response:
                while True:
                    chunk = response.read(min(_CHUNK_SIZE, expected_size + 1 - byte_count))
                    if not chunk:
                        break
                    byte_count += len(chunk)
                    if byte_count > expected_size:
                        raise ValueError("official JOSM host download exceeds the size limit")
                    digest.update(chunk)
                    destination.write(chunk)
        if byte_count != expected_size:
            raise ValueError("official JOSM host download has an unexpected file size")
        if digest.hexdigest() != expected_sha256:
            raise ValueError("official JOSM host download has an unexpected SHA-256")
        if _revision(temporary) != expected_revision:
            raise ValueError("official JOSM host download has an unexpected REVISION")
        try:
            target_fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        except FileExistsError:
            # Another writer won the publication race. Reuse only its verified host.
            _verify(
                target,
                expected_size=expected_size,
                expected_sha256=expected_sha256,
                expected_revision=expected_revision,
            )
        else:
            target_stat = os.fstat(target_fd)
            try:
                with os.fdopen(target_fd, "wb") as destination:
                    with temporary.open("rb") as source:
                        shutil.copyfileobj(source, destination, length=_CHUNK_SIZE)
                    destination.flush()
                    if os.fstat(destination.fileno()).st_size != expected_size:
                        raise ValueError("published JOSM host has an unexpected file size")
                _verify(
                    target,
                    expected_size=expected_size,
                    expected_sha256=expected_sha256,
                    expected_revision=expected_revision,
                )
                current_stat = target.lstat()
                if (current_stat.st_dev, current_stat.st_ino) != (target_stat.st_dev, target_stat.st_ino):
                    raise ValueError("JOSM host target changed during publication")
            except Exception:
                try:
                    current_stat = target.lstat()
                    if (current_stat.st_dev, current_stat.st_ino) == (target_stat.st_dev, target_stat.st_ino):
                        target.unlink()
                except OSError:
                    pass
                raise
        _write_receipt(receipt, download_url, asset_name, expected_size, expected_sha256, expected_revision)
        return target, receipt
    finally:
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass


def _write_receipt(
    receipt: Path,
    download_url: str,
    asset_name: str,
    expected_size: int,
    expected_sha256: str,
    expected_revision: str,
) -> None:
    content = {
        "publisher": PUBLISHER,
        "assetName": asset_name,
        "url": download_url,
        "size": expected_size,
        "sha256": expected_sha256,
        "revision": expected_revision,
        "hostVariant": HOST_VARIANT,
    }
    expected_json = (json.dumps(content, sort_keys=True, indent=2) + "\n").encode("utf-8")

    def reuse_matching_receipt() -> None:
        if not hasattr(os, "O_NOFOLLOW"):
            raise ValueError("cannot safely inspect an existing host receipt on this platform")
        receipt_fd: int | None = None
        try:
            receipt_fd = os.open(receipt, os.O_RDONLY | os.O_NOFOLLOW)
            receipt_stat = os.fstat(receipt_fd)
            if not stat.S_ISREG(receipt_stat.st_mode):
                raise ValueError("existing host receipt is not a regular file")
            if receipt_stat.st_size > 4096:
                raise ValueError("existing host receipt exceeds the size limit")
            with os.fdopen(receipt_fd, "rb") as receipt_file:
                receipt_fd = None
                existing_bytes = receipt_file.read(4097)
            if len(existing_bytes) > 4096:
                raise ValueError("existing host receipt exceeds the size limit")
            existing = json.loads(existing_bytes.decode("utf-8"))
        except FileNotFoundError:
            raise
        except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ValueError("existing host receipt is invalid; preserving it") from error
        finally:
            if receipt_fd is not None:
                os.close(receipt_fd)
        if existing != content:
            raise ValueError("existing host receipt does not match expected metadata; preserving it")

    if receipt.exists() or receipt.is_symlink():
        reuse_matching_receipt()
        return

    try:
        fd = os.open(receipt, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        # Preserve a concurrently created receipt unless it exactly matches.
        reuse_matching_receipt()
        return

    opened_stat = os.fstat(fd)
    try:
        with os.fdopen(fd, "wb") as receipt_file:
            receipt_file.write(expected_json)
    except OSError:
        try:
            current_stat = receipt.lstat()
            if (current_stat.st_dev, current_stat.st_ino) == (opened_stat.st_dev, opened_stat.st_ino):
                receipt.unlink()
        except OSError:
            pass
        raise


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    try:
        jar, receipt = prepare_host(args.output_dir)
    except (OSError, ValueError) as error:
        print(f"prepare-ci-josm19555: {error}")
        return 1
    print(f"Verified JOSM 19555 host: {jar}")
    print(f"Host receipt: {receipt}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
