"""Bounded deterministic nested-ZIP traversal without filesystem extraction."""

from __future__ import annotations

import hashlib
import io
import os
import re
import stat
import zipfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath


@dataclass(frozen=True)
class ArchiveLimits:
    """Resource limits applied to one outer archive and all nested ZIPs."""

    max_depth: int = 8
    max_entries_per_zip: int = 20_000
    max_total_entries: int = 100_000
    max_outer_bytes: int = 96 * 1024**2
    max_compressed_bytes: int = 256 * 1024**2
    max_uncompressed_bytes: int = 384 * 1024**2
    max_member_bytes: int = 32 * 1024**2
    max_materialized_bytes: int = 384 * 1024**2
    max_text_member_bytes: int = 16 * 1024**2
    max_ratio: float = 200.0
    max_name_bytes: int = 1_024


class ArchiveError(ValueError):
    """Typed rejection whose message contains no archive content."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class BundleSource:
    """One validated direct or nested debug bundle."""

    name: str
    data: bytes


@dataclass(frozen=True)
class ScannableMember:
    """One deduplicated member name plus bounded decoded text, when text-like."""

    name: str
    text: str


@dataclass(frozen=True)
class ArchiveInspection:
    """One immutable outer-file snapshot and its validated nested inventory."""

    sha256: str
    byte_size: int
    bundles: tuple[BundleSource, ...]
    scannable_members: tuple[ScannableMember, ...]


@dataclass
class _Budget:
    entries: int = 0
    compressed: int = 0
    uncompressed: int = 0
    materialized: int = 0


class SafeArchiveReader:
    """Validate one immutable ZIP snapshot before returning debug bundles."""

    SUPPORTED = {
        zipfile.ZIP_STORED,
        zipfile.ZIP_DEFLATED,
        zipfile.ZIP_BZIP2,
        zipfile.ZIP_LZMA,
    }
    BINARY_SUFFIXES = (
        ".7z", ".bz2", ".class", ".gif", ".gz", ".jar", ".jpeg", ".jpg",
        ".lzma", ".pbf", ".png", ".webp", ".xz", ".zip",
    )

    def __init__(self, limits: ArchiveLimits | None = None) -> None:
        self.limits = limits or ArchiveLimits()

    def inspect(self, path: Path) -> ArchiveInspection:
        """Read one non-symlink regular file once, then validate that snapshot."""

        descriptor = -1
        try:
            descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_CLOEXEC", 0)
                | getattr(os, "O_NOFOLLOW", 0))
            metadata = os.fstat(descriptor)
            if not stat.S_ISREG(metadata.st_mode):
                raise ArchiveError("UNSAFE_INPUT", "outer archive must be a non-symlink regular file")
            if metadata.st_size > self.limits.max_outer_bytes:
                raise ArchiveError("COMPRESSED_SIZE", "outer archive exceeds compressed-size limit")
        except ArchiveError:
            if descriptor >= 0:
                os.close(descriptor)
            raise
        except OSError as error:
            if descriptor >= 0:
                os.close(descriptor)
            raise ArchiveError("UNSAFE_INPUT", "cannot safely open outer archive") from error
        digest = hashlib.sha256()
        snapshot = io.BytesIO()
        try:
            with os.fdopen(descriptor, "rb") as handle:
                descriptor = -1
                for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                    if snapshot.tell() + len(chunk) > self.limits.max_outer_bytes:
                        raise ArchiveError("COMPRESSED_SIZE", "outer archive exceeds compressed-size limit")
                    digest.update(chunk)
                    snapshot.write(chunk)
        except ArchiveError:
            raise
        except OSError as error:
            raise ArchiveError("INPUT_FILE", "cannot read outer archive") from error
        data = snapshot.getvalue()
        budget = _Budget(materialized=len(data))
        members: list[ScannableMember] = []
        bundles = self._discover(path.name, data, 0, budget, members)
        return ArchiveInspection(digest.hexdigest(), len(data), tuple(bundles), tuple(members))

    def discover(self, path: Path) -> list[BundleSource]:
        """Discover validated bundles below one outer ZIP path."""

        return list(self.inspect(path).bundles)

    def discover_bytes(self, name: str, data: bytes) -> list[BundleSource]:
        """Discover validated bundles from bounded caller-owned bytes."""

        if len(data) > self.limits.max_outer_bytes:
            raise ArchiveError("COMPRESSED_SIZE", "outer archive exceeds compressed-size limit")
        return self._discover(name, data, 0, _Budget(materialized=len(data)), [])

    def _discover(
        self,
        name: str,
        source: bytes,
        depth: int,
        budget: _Budget,
        members: list[ScannableMember],
    ) -> list[BundleSource]:
        if depth > self.limits.max_depth:
            raise ArchiveError("NESTING_DEPTH", "maximum nesting depth exceeded")
        try:
            with zipfile.ZipFile(io.BytesIO(source)) as archive:
                infos = sorted(archive.infolist(), key=lambda item: (item.filename, item.header_offset))
                if len(infos) > self.limits.max_entries_per_zip:
                    raise ArchiveError("ENTRY_LIMIT", "entry-count limit exceeded")
                if archive.comment:
                    members.append(ScannableMember(f"{name}!<zip-comment>",
                        archive.comment.decode("utf-8", "replace")))
                payloads: dict[int, bytes] = {}
                hashes: dict[int, str] = {}
                seen: dict[str, str] = {}
                for info in infos:
                    self._metadata(info, budget)
                    needs_payload = (not info.is_dir() and
                        (info.filename.lower().endswith(".zip")
                            or (self._is_text(info.filename)
                                and info.file_size <= self.limits.max_text_member_bytes)))
                    digest = hashlib.sha256()
                    payload = io.BytesIO() if needs_payload else None
                    with archive.open(info) as handle:
                        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                            digest.update(chunk)
                            if payload is not None:
                                if budget.materialized + len(chunk) > self.limits.max_materialized_bytes:
                                    raise ArchiveError("MEMORY_LIMIT", "materialized archive data exceeds limit")
                                budget.materialized += len(chunk)
                                payload.write(chunk)
                    value = digest.hexdigest()
                    hashes[info.header_offset] = value
                    if info.filename in seen:
                        if seen[info.filename] != value:
                            raise ArchiveError("DUPLICATE_CONFLICT", "conflicting duplicate member")
                        continue
                    seen[info.filename] = value
                    member_name = f"{name}!{info.filename}"
                    content = payload.getvalue() if payload is not None else b""
                    if not info.is_dir():
                        text = (content.decode("utf-8", "replace")
                            if self._is_text(info.filename)
                                and info.file_size <= self.limits.max_text_member_bytes else "")
                        members.append(ScannableMember(member_name, text))
                    if info.filename.lower().endswith(".zip"):
                        payloads[info.header_offset] = content
                names = {item.filename for item in infos if not item.is_dir()}
                if "candidate-metrics.csv" in names:
                    return [BundleSource(name, source)]
                result: list[BundleSource] = []
                nested_seen: set[tuple[str, str]] = set()
                for info in infos:
                    if info.is_dir() or not info.filename.lower().endswith(".zip"):
                        continue
                    identity = (info.filename, hashes[info.header_offset])
                    if identity in nested_seen:
                        continue
                    nested_seen.add(identity)
                    result.extend(self._discover(f"{name}!{info.filename}", payloads[info.header_offset],
                        depth + 1, budget, members))
                return result
        except ArchiveError:
            raise
        except (OSError, EOFError, RuntimeError, NotImplementedError, zipfile.BadZipFile) as error:
            raise ArchiveError("MALFORMED_ZIP", "malformed ZIP, unsupported codec, or CRC failure") from error

    def _is_text(self, name: str) -> bool:
        return not name.lower().endswith(self.BINARY_SUFFIXES)

    def _metadata(self, info: zipfile.ZipInfo, budget: _Budget) -> None:
        # ZipInfo keeps the pre-NUL input in orig_filename on supported Python versions.
        # Validate both forms because filename itself is deliberately truncated at NUL.
        original_name = getattr(info, "orig_filename", info.filename)
        name = info.filename
        normalized = name.replace("\\", "/")
        if ("\x00" in original_name
            or len(original_name.encode("utf-8", "surrogatepass")) > self.limits.max_name_bytes
            or normalized.startswith("/") or normalized.startswith("//")
            or re.match(r"^[A-Za-z]:", normalized)
            or ".." in PurePosixPath(normalized).parts):
            raise ArchiveError("UNSAFE_PATH", "unsafe ZIP member path")
        mode = info.external_attr >> 16
        if stat.S_IFMT(mode) not in (0, stat.S_IFREG, stat.S_IFDIR):
            raise ArchiveError("UNSAFE_TYPE", "non-regular ZIP member")
        if info.flag_bits & 1:
            raise ArchiveError("ENCRYPTED", "encrypted ZIP member")
        if info.compress_type not in self.SUPPORTED:
            raise ArchiveError("UNSUPPORTED_COMPRESSION", "unsupported ZIP compression")
        if info.file_size > self.limits.max_member_bytes:
            raise ArchiveError("MEMBER_SIZE", "member size limit exceeded")
        if self._is_text(info.filename) and info.file_size > self.limits.max_text_member_bytes:
            raise ArchiveError("TEXT_SIZE", "text-like member exceeds privacy scan limit")
        if info.file_size / max(1, info.compress_size) > self.limits.max_ratio:
            raise ArchiveError("COMPRESSION_RATIO", "compression-ratio limit exceeded")
        budget.entries += 1
        budget.compressed += info.compress_size
        budget.uncompressed += info.file_size
        if budget.entries > self.limits.max_total_entries:
            raise ArchiveError("TOTAL_ENTRY_LIMIT", "outer entry-count limit exceeded")
        if budget.compressed > self.limits.max_compressed_bytes:
            raise ArchiveError("COMPRESSED_SIZE", "outer compressed-size limit exceeded")
        if budget.uncompressed > self.limits.max_uncompressed_bytes:
            raise ArchiveError("UNCOMPRESSED_SIZE", "outer uncompressed-size limit exceeded")
