"""Bounded deterministic nested-ZIP traversal without filesystem extraction."""

from __future__ import annotations

import codecs
import hashlib
import io
import os
import re
import stat
import struct
import sys
import zipfile
from collections.abc import Callable
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
    # Compatibility name for the active nested-branch expansion limit.
    max_uncompressed_bytes: int = 384 * 1024**2
    # Descendant sizes accumulate within one active branch; completed siblings release them.
    max_cumulative_expansion_bytes: int | None = None
    max_member_bytes: int = 32 * 1024**2
    max_materialized_bytes: int | None = None
    max_peak_materialized_bytes: int = 128 * 1024**2
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
    live_materialized: int = 0
    peak_materialized: int = 0
    retained_materialized: int = 0


class _ReadOnlyBytes:
    """Expose immutable bytes as a seekable stream without copying the full payload."""

    def __init__(self, data: bytes) -> None:
        self._data = data
        self._position = 0

    def read(self, size: int = -1) -> bytes:
        """Read up to ``size`` bytes from the current position."""

        if size is None or size < 0:
            end = len(self._data)
        else:
            end = min(len(self._data), self._position + size)
        value = self._data[self._position:end]
        self._position = end
        return value

    def seek(self, offset: int, whence: int = os.SEEK_SET) -> int:
        """Move the current position using standard file-object semantics."""

        if whence == os.SEEK_SET:
            base = 0
        elif whence == os.SEEK_CUR:
            base = self._position
        elif whence == os.SEEK_END:
            base = len(self._data)
        else:
            raise ValueError("unsupported seek origin")
        position = base + offset
        if position < 0:
            raise ValueError("negative seek position")
        self._position = position
        return position

    def tell(self) -> int:
        """Return the current stream position."""

        return self._position

    def seekable(self) -> bool:
        """Return whether the stream supports random access."""

        return True

    def readable(self) -> bool:
        """Return whether the stream supports reads."""

        return True


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

    def inspect(
        self,
        path: Path,
        on_bundle: Callable[[BundleSource], None] | None = None,
        on_member: Callable[[ScannableMember], None] | None = None,
    ) -> ArchiveInspection:
        """Read and validate one snapshot, optionally streaming bundles and bounded text chunks."""

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
        try:
            with os.fdopen(descriptor, "rb") as handle:
                descriptor = -1
                data = handle.read(self.limits.max_outer_bytes + 1)
                if len(data) > self.limits.max_outer_bytes:
                    raise ArchiveError("COMPRESSED_SIZE", "outer archive exceeds compressed-size limit")
                digest.update(data)
        except ArchiveError:
            raise
        except OSError as error:
            raise ArchiveError("INPUT_FILE", "cannot read outer archive") from error
        self._validate_initial_materialization(len(data))
        budget = _Budget(live_materialized=len(data), peak_materialized=len(data))
        members: list[ScannableMember] = []
        bundles = self._discover(path.name, data, 0, budget, members, on_bundle, on_member)
        return ArchiveInspection(digest.hexdigest(), len(data), tuple(bundles), tuple(members))

    def discover(self, path: Path) -> list[BundleSource]:
        """Discover validated bundles below one outer ZIP path."""

        return list(self.inspect(path).bundles)

    def for_each_bundle(self, path: Path, callback: Callable[[BundleSource], None]) -> None:
        """Validate an outer ZIP and invoke callback for each bundle as it is found.

        The callback must finish using the bundle before returning; nested ZIP
        payloads are released immediately afterwards.
        """

        self.inspect(path, on_bundle=callback, on_member=lambda ignored: None)

    def discover_bytes(self, name: str, data: bytes) -> list[BundleSource]:
        """Discover validated bundles from bounded caller-owned bytes."""

        if len(data) > self.limits.max_outer_bytes:
            raise ArchiveError("COMPRESSED_SIZE", "outer archive exceeds compressed-size limit")
        self._validate_initial_materialization(len(data))
        return self._discover(name, data, 0, _Budget(live_materialized=len(data), peak_materialized=len(data)), [])

    def _discover(
        self,
        name: str,
        source: bytes,
        depth: int,
        budget: _Budget,
        members: list[ScannableMember],
        on_bundle: Callable[[BundleSource], None] | None = None,
        on_member: Callable[[ScannableMember], None] | None = None,
    ) -> list[BundleSource]:
        if depth > self.limits.max_depth:
            raise ArchiveError("NESTING_DEPTH", "maximum nesting depth exceeded")
        inventory_bound = self._zip_inventory_bound(source)
        self._reserve(inventory_bound, budget)
        try:
            try:
                with zipfile.ZipFile(_ReadOnlyBytes(source)) as archive:
                    infos = sorted(archive.infolist(),
                                   key=lambda item: (item.filename, item.header_offset))
                    if self._zip_inventory_size(infos) > inventory_bound:
                        raise ArchiveError("MEMORY_LIMIT", "ZIP inventory exceeds preflight bound")
                    if len(infos) > self.limits.max_entries_per_zip:
                        raise ArchiveError("ENTRY_LIMIT", "entry-count limit exceeded")
                    if archive.comment:
                        self._record_scannable(ScannableMember(
                            f"{name}!<zip-comment>", archive.comment.decode("utf-8", "replace")),
                            members, on_member, budget)
                    hashes: dict[int, str] = {}
                    seen: dict[str, str] = {}
                    unique_infos: list[zipfile.ZipInfo] = []
                    for info in infos:
                        self._metadata(info, budget)
                        digest = hashlib.sha256()
                        with archive.open(info) as handle:
                            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                                digest.update(chunk)
                        value = digest.hexdigest()
                        hashes[info.header_offset] = value
                        if info.filename in seen:
                            if seen[info.filename] != value:
                                raise ArchiveError("DUPLICATE_CONFLICT", "conflicting duplicate member")
                            continue
                        seen[info.filename] = value
                        unique_infos.append(info)
                    for info in unique_infos:
                        if info.is_dir():
                            continue
                        member_name = f"{name}!{info.filename}"
                        text_member = (self._is_text(info.filename)
                            and info.file_size <= self.limits.max_text_member_bytes)
                        if not text_member:
                            self._record_scannable(
                                ScannableMember(member_name, ""), members, on_member, budget)
                        elif on_member is not None:
                            self._stream_scannable(archive, info, member_name, on_member, budget)
                        else:
                            payload = io.BytesIO()
                            with archive.open(info) as handle:
                                for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                                    self._reserve(len(chunk), budget)
                                    payload.write(chunk)
                            payload_size = payload.tell()
                            view = payload.getbuffer()
                            try:
                                text = codecs.decode(view, "utf-8", "replace")
                                self._record_scannable(
                                    ScannableMember(member_name, text), members, None, budget)
                            finally:
                                view.release()
                                payload.close()
                                self._release(payload_size, budget)
                    names = {item.filename for item in infos if not item.is_dir()}
                    if "candidate-metrics.csv" in names:
                        bundle = BundleSource(name, source)
                        if on_bundle is not None:
                            on_bundle(bundle)
                            return []
                        self._retain(len(source), budget)
                        return [bundle]
                    result: list[BundleSource] = []
                    nested_seen: set[tuple[str, str]] = set()
                    for info in infos:
                        if info.is_dir() or not info.filename.lower().endswith(".zip"):
                            continue
                        identity = (info.filename, hashes[info.header_offset])
                        if identity in nested_seen:
                            continue
                        nested_seen.add(identity)
                        nested_name = f"{name}!{info.filename}"
                        nested_payload = self._read_member(archive, info, budget)
                        nested_expansion_baseline = budget.uncompressed
                        try:
                            result.extend(self._discover(nested_name, nested_payload,
                                depth + 1, budget, members, on_bundle, on_member))
                        finally:
                            budget.uncompressed = nested_expansion_baseline
                            self._release(len(nested_payload), budget)
                    return result
            except ArchiveError:
                raise
            except (OSError, EOFError, RuntimeError, NotImplementedError,
                    zipfile.BadZipFile) as error:
                raise ArchiveError(
                    "MALFORMED_ZIP", "malformed ZIP, unsupported codec, or CRC failure") from error
        finally:
            self._release(inventory_bound, budget)

    def _stream_scannable(
        self,
        archive: zipfile.ZipFile,
        info: zipfile.ZipInfo,
        member_name: str,
        on_member: Callable[[ScannableMember], None],
        budget: _Budget,
    ) -> None:
        """Decode one text member in bounded chunks while preserving boundary-spanning matches."""

        overlap = 1024
        decoder = codecs.getincrementaldecoder("utf-8")("replace")
        tail = ""
        emitted = False
        with archive.open(info) as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                self._reserve(len(chunk), budget)
                try:
                    combined = tail + decoder.decode(chunk)
                    if len(combined) > overlap:
                        self._record_scannable(
                            ScannableMember(member_name, combined[:-overlap]),
                            [], on_member, budget)
                        emitted = True
                        tail = combined[-overlap:]
                    else:
                        tail = combined
                finally:
                    self._release(len(chunk), budget)
        tail += decoder.decode(b"", final=True)
        if tail or not emitted:
            self._record_scannable(
                ScannableMember(member_name, tail), [], on_member, budget)
    def _read_member(self, archive: zipfile.ZipFile, info: zipfile.ZipInfo,
                     budget: _Budget) -> bytes:
        """Materialize one nested ZIP member under the peak live-data limit."""

        payload = io.BytesIO()
        with archive.open(info) as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                self._reserve(len(chunk), budget)
                payload.write(chunk)
        payload_size = payload.tell()
        self._reserve(payload_size, budget)
        value = payload.getvalue()
        payload.close()
        self._release(payload_size, budget)
        return value

    def _record_scannable(
        self,
        member: ScannableMember,
        members: list[ScannableMember],
        on_member: Callable[[ScannableMember], None] | None,
        budget: _Budget,
    ) -> None:
        """Retain or stream one decoded member while charging all retained storage."""

        retained_size = (sys.getsizeof(member) + sys.getsizeof(member.name)
                         + sys.getsizeof(member.text))
        self._reserve(retained_size, budget)
        if on_member is None:
            members.append(member)
            return
        try:
            on_member(member)
        finally:
            self._release(retained_size, budget)

    def _materialization_limit(self) -> int:
        """Return the active compatibility or peak materialization limit."""

        return (self.limits.max_materialized_bytes
                if self.limits.max_materialized_bytes is not None
                else self.limits.max_peak_materialized_bytes)
    def _zip_inventory_bound(self, source: bytes) -> int:
        """Bound central-directory allocation before ``ZipFile`` materializes it."""

        signature = b"PK\x05\x06"
        minimum = max(0, len(source) - (65_535 + 22))
        offset = source.rfind(signature, minimum)
        fields: tuple[object, ...] | None = None
        while offset >= 0:
            if offset + 22 <= len(source):
                candidate = struct.unpack_from("<4s4H2LH", source, offset)
                if offset + 22 + int(candidate[7]) == len(source):
                    fields = candidate
                    break
            offset = source.rfind(signature, minimum, offset)
        if fields is None:
            raise ArchiveError("MALFORMED_ZIP", "missing or invalid ZIP end record")

        entries = int(fields[4])
        central_size = int(fields[5])
        if entries == 0xFFFF or central_size == 0xFFFFFFFF or int(fields[6]) == 0xFFFFFFFF:
            locator_offset = offset - 20
            if locator_offset < 0:
                raise ArchiveError("MALFORMED_ZIP", "missing ZIP64 locator")
            locator = struct.unpack_from("<4sLQL", source, locator_offset)
            record_offset = int(locator[2])
            if locator[0] != b"PK\x06\x07" or record_offset + 56 > len(source):
                raise ArchiveError("MALFORMED_ZIP", "invalid ZIP64 locator")
            record = struct.unpack_from("<4sQ2H2L4Q", source, record_offset)
            if record[0] != b"PK\x06\x06":
                raise ArchiveError("MALFORMED_ZIP", "invalid ZIP64 end record")
            entries = int(record[7])
            central_size = int(record[8])

        if entries > self.limits.max_entries_per_zip:
            raise ArchiveError("ENTRY_LIMIT", "entry-count limit exceeded")
        if central_size < 0 or central_size > len(source):
            raise ArchiveError("MALFORMED_ZIP", "invalid ZIP central-directory size")
        return 4096 + entries * 1024 + central_size * 6


    @staticmethod
    def _zip_inventory_size(infos: list[zipfile.ZipInfo]) -> int:
        """Estimate live Python storage retained by one ZIP member inventory."""

        total = sys.getsizeof(infos)
        for info in infos:
            total += sys.getsizeof(info) + sys.getsizeof(info.filename)
            original_name = getattr(info, "orig_filename", info.filename)
            if original_name is not info.filename:
                total += sys.getsizeof(original_name)
        return total

    def _validate_initial_materialization(self, size: int) -> None:
        """Reject an outer snapshot that already exceeds the live-data bound."""

        if size > self._materialization_limit():
            raise ArchiveError("MEMORY_LIMIT", "outer archive exceeds peak materialized-data limit")

    def _retain(self, size: int, budget: _Budget) -> None:
        """Charge a list-returned bundle that remains alive after recursion unwinds."""

        budget.retained_materialized += size
        effective_live = budget.live_materialized + budget.retained_materialized - size
        budget.peak_materialized = max(budget.peak_materialized, effective_live)
        if budget.peak_materialized > self._materialization_limit():
            raise ArchiveError("MEMORY_LIMIT", "retained archive bundles exceed materialized-data limit")

    def _reserve(self, size: int, budget: _Budget) -> None:
        """Account for live decompressed data and reject excessive peaks."""

        budget.live_materialized += size
        effective_live = budget.live_materialized + budget.retained_materialized
        budget.peak_materialized = max(budget.peak_materialized, effective_live)
        if budget.peak_materialized > self._materialization_limit():
            raise ArchiveError("MEMORY_LIMIT", "peak materialized archive data exceeds limit")


    @staticmethod
    def _release(size: int, budget: _Budget) -> None:
        """Release one nested member after recursive traversal completes."""

        budget.live_materialized -= size

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
        expansion_limit = (self.limits.max_cumulative_expansion_bytes
                           if self.limits.max_cumulative_expansion_bytes is not None
                           else self.limits.max_uncompressed_bytes)
        if budget.entries > self.limits.max_total_entries:
            raise ArchiveError("TOTAL_ENTRY_LIMIT", "outer entry-count limit exceeded")
        if budget.compressed > self.limits.max_compressed_bytes:
            raise ArchiveError("COMPRESSED_SIZE", "outer compressed-size limit exceeded")
        if budget.uncompressed > expansion_limit:
            raise ArchiveError("UNCOMPRESSED_SIZE", "active nested-branch expansion limit exceeded")
