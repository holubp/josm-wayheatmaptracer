"""Generate one sealed, private replacement HOLDOUT package.

Importing this module is inert. Production generation is a no-argument local
CLI operation; tests may call the bounded helpers with explicitly dummy data.
The public index contains hashes and sampling metadata only.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from typing import Callable, Mapping, Sequence


PROTOCOL_VERSION = "sealed-observation-v2"
GENERATION_ID = "sealed-observation-v2-generation-v2"
GENERATION_RELATIVE_SCOPE = Path("build/sealed-observation-v2/generation-v2")
STREAM_DOMAIN = b"b-observation-holdout-v2"
MAX_STREAM_BYTES = 8192
MAX_PROPOSALS_PER_GROUP = 64
MAX_PAYLOAD_BYTES = 256 * 1024
MAX_PRIVATE_BYTES = 64 * 1024
MAX_INDEX_BYTES = 64 * 1024
GROUP_COUNT = 7
VARIANT_COUNT = 8
CASE_COUNT = GROUP_COUNT * VARIANT_COUNT * 2
SIDES = ("left", "right")
DUMMY_ONLY_MARKER = "NONQUALIFYING-DUMMY-FIXTURE-ONLY"
CONTRASTS = (
    "concentrated-band-vs-diffuse-band",
    "displaced-strong-corridor-vs-nearby-faint-strand",
    "multiple-physical-pitches-vs-reversed-source-order",
    "palette-semantic-vs-rendered-source",
    "resolved-short-apex-vs-stair-step-alias",
    "sparse-crossing-noisy-strands-vs-persistent-parallel",
    "weak-coherent-curve-vs-disconnected-flecks",
)
VARIANTS = {
    "v0": {"phase": 0.0, "sourcePitchMeters": 0.5, "oversampling": 1},
    "v1": {"phase": 0.25, "sourcePitchMeters": 0.5, "oversampling": 2},
    "v2": {"phase": 0.5, "sourcePitchMeters": 1.0, "oversampling": 1},
    "v3": {"phase": 0.75, "sourcePitchMeters": 1.0, "oversampling": 2},
    "v4": {"phase": 0.25, "sourcePitchMeters": 1.75, "oversampling": 1},
    "v5": {"phase": 0.75, "sourcePitchMeters": 1.75, "oversampling": 2},
    "v6": {"phase": 0.0, "sourcePitchMeters": 0.5, "oversampling": 2},
    "v7": {"phase": 0.5, "sourcePitchMeters": 1.75, "oversampling": 1},
}
_HEX_256 = re.compile(r"^[0-9a-f]{64}$")
_GROUP_ID = re.compile(r"^[A-Za-z0-9:_-]{1,120}$")


class GenerationError(RuntimeError):
    """A bounded, non-sensitive producer refusal."""

    def __init__(self, code: str):
        if not isinstance(code, str) or not code.isascii() or not code.replace("_", "").isalnum():
            code = "GENERATION_REFUSED"
        self.code = code[:48]
        super().__init__(self.code)


class HmacByteStream:
    """Counter-mode HMAC-SHA256 stream with an invocation-wide byte ceiling."""

    def __init__(self, seed: bytes, max_bytes: int = MAX_STREAM_BYTES):
        if not isinstance(seed, bytes) or len(seed) != 32:
            raise GenerationError("INVALID_MASTER_SEED")
        if isinstance(max_bytes, bool) or not isinstance(max_bytes, int) or not 1 <= max_bytes <= MAX_STREAM_BYTES:
            raise GenerationError("INVALID_STREAM_BOUND")
        self._seed = seed
        self._max_bytes = max_bytes
        self._bytes_read = 0
        self._counter = 0
        self._buffer = b""

    @property
    def bytes_read(self) -> int:
        return self._bytes_read

    @property
    def blocks_read(self) -> int:
        return self._counter

    def read(self, count: int) -> bytes:
        if isinstance(count, bool) or not isinstance(count, int) or count < 0:
            raise GenerationError("INVALID_STREAM_READ")
        if count > self._max_bytes - self._bytes_read:
            raise GenerationError("RANDOM_BYTE_BUDGET")
        output = bytearray()
        while len(output) < count:
            if not self._buffer:
                message = STREAM_DOMAIN + self._counter.to_bytes(8, byteorder="big", signed=False)
                self._buffer = hmac.new(self._seed, message, hashlib.sha256).digest()
                self._counter += 1
            take = min(count - len(output), len(self._buffer))
            output.extend(self._buffer[:take])
            self._buffer = self._buffer[take:]
        self._bytes_read += count
        return bytes(output)


def offset_from_byte(value: int) -> float | None:
    """Map one unbiased accepted byte to a quarter-metre knot offset."""
    if isinstance(value, bool) or not isinstance(value, int) or not 0 <= value <= 255:
        raise GenerationError("INVALID_RANDOM_BYTE")
    return None if value >= 222 else (value % 37 - 16) / 4.0


def draw_offset(stream, max_bytes: int = MAX_STREAM_BYTES) -> float:
    """Draw one uniform value from the 37-point quarter-metre support."""
    if isinstance(max_bytes, bool) or not isinstance(max_bytes, int) or not 1 <= max_bytes <= MAX_STREAM_BYTES:
        raise GenerationError("INVALID_STREAM_BOUND")
    consumed = 0
    while consumed < max_bytes:
        byte = stream.read(1)
        if not isinstance(byte, bytes) or len(byte) != 1:
            raise GenerationError("RANDOM_STREAM_FAILURE")
        consumed += 1
        mapped = offset_from_byte(byte[0])
        if mapped is not None:
            return mapped
    raise GenerationError("RANDOM_BYTE_BUDGET")


def _five_offsets(offsets) -> tuple[float, ...]:
    try:
        raw = tuple(offsets)
        if any(isinstance(value, bool) for value in raw):
            raise GenerationError("INVALID_GEOMETRY_TUPLE")
        values = tuple(float(value) for value in raw)
    except (TypeError, ValueError, OverflowError) as failure:
        raise GenerationError("INVALID_GEOMETRY_TUPLE") from failure
    if len(values) != 5 or any(not math.isfinite(value) for value in values):
        raise GenerationError("INVALID_GEOMETRY_TUPLE")
    if any(value < -4.0 or value > 5.0 or value * 4 != round(value * 4) for value in values):
        raise GenerationError("INVALID_GEOMETRY_SUPPORT")
    return values


def valid_geometry(offsets) -> bool:
    """Apply the fixed nontrivial-curve validity conditions to a dummy or real tuple."""
    try:
        values = _five_offsets(offsets)
    except GenerationError:
        return False
    return (any(value >= 1.0 for value in values)
            and any(value <= -1.0 for value in values)
            and max(abs(value) for value in values) >= 3.0)


def canonical_geometry(offsets) -> tuple[float, ...]:
    """Give a tuple and its reversal one lexicographically canonical identity."""
    values = _five_offsets(offsets)
    reverse = tuple(reversed(values))
    return min(values, reverse)


def draw_distinct_groups(proposal_source: Callable[[], Sequence[float]], group_count: int = GROUP_COUNT,
                         max_proposals_per_group: int = MAX_PROPOSALS_PER_GROUP) -> list[tuple[float, ...]]:
    """Accept bounded, valid geometry proposals without duplicate/reversed groups."""
    if not callable(proposal_source) or isinstance(group_count, bool) or not isinstance(group_count, int) \
            or not 1 <= group_count <= GROUP_COUNT:
        raise GenerationError("INVALID_GROUP_REQUEST")
    if isinstance(max_proposals_per_group, bool) or not isinstance(max_proposals_per_group, int) \
            or not 1 <= max_proposals_per_group <= MAX_PROPOSALS_PER_GROUP:
        raise GenerationError("INVALID_PROPOSAL_BOUND")
    accepted: list[tuple[float, ...]] = []
    identities: set[tuple[float, ...]] = set()
    for _group_index in range(group_count):
        found = False
        for _proposal_index in range(max_proposals_per_group):
            proposal = tuple(proposal_source())
            if not valid_geometry(proposal):
                continue
            identity = canonical_geometry(proposal)
            if identity in identities:
                continue
            identities.add(identity)
            accepted.append(_five_offsets(proposal))
            found = True
            break
        if not found:
            raise GenerationError("GEOMETRY_PROPOSALS_EXHAUSTED")
    return accepted


def spline_value(offsets, x: float) -> float:
    """Evaluate the reviewed piecewise quintic with constant endpoint extension."""
    values = _five_offsets(offsets)
    if not isinstance(x, (int, float)) or isinstance(x, bool) or not math.isfinite(float(x)):
        raise GenerationError("INVALID_SPLINE_COORDINATE")
    x = float(x)
    if x < 0.0 or x > 120.0:
        return 30.0
    knots = (0.0, *values, 0.0)
    interval = min(int(x // 20.0), 5)
    s = (x - 20.0 * interval) / 20.0
    left = knots[interval]
    right = knots[interval + 1]
    smooth = 6.0 * s**5 - 15.0 * s**4 + 10.0 * s**3
    return 30.0 + left + (right - left) * smooth


def spline_derivatives(offsets, x: float) -> tuple[float, float]:
    """Return analytic first and second x-derivatives of the quintic spline."""
    values = _five_offsets(offsets)
    if not isinstance(x, (int, float)) or isinstance(x, bool) or not math.isfinite(float(x)):
        raise GenerationError("INVALID_SPLINE_COORDINATE")
    x = float(x)
    if x <= 0.0 or x >= 120.0:
        return 0.0, 0.0
    knots = (0.0, *values, 0.0)
    interval = min(int(x // 20.0), 5)
    s = (x - 20.0 * interval) / 20.0
    delta = knots[interval + 1] - knots[interval]
    first = delta * (30.0 * s**2 * (s - 1.0)**2) / 20.0
    second = delta * (60.0 * s * (2.0 * s**2 - 3.0 * s + 1.0)) / 400.0
    return first, second


def canonical_json_bytes(value) -> bytes:
    """Serialize bounded JSON canonically as UTF-8 with one terminal newline."""
    try:
        text = json.dumps(value, ensure_ascii=False, allow_nan=False, sort_keys=True,
                          separators=(",", ":"))
    except (TypeError, ValueError, OverflowError) as failure:
        raise GenerationError("NONCANONICAL_JSON_VALUE") from failure
    return text.encode("utf-8") + b"\n"


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _mapping(value, code: str) -> Mapping:
    if not isinstance(value, Mapping):
        raise GenerationError(code)
    return value


def _check_hash(value, code: str) -> str:
    if not isinstance(value, str) or not _HEX_256.fullmatch(value):
        raise GenerationError(code)
    return value


def _check_descriptor_id(value, code: str) -> str:
    if not isinstance(value, str) or not _GROUP_ID.fullmatch(value):
        raise GenerationError(code)
    return value


def build_public_index(groups, variants, recipes, *, payload_bytes: bytes,
                       generator_hash: str, protocol_hash: str) -> bytes:
    """Build the compact public index from private recipe records and actual payload bytes."""
    if not isinstance(payload_bytes, bytes) or len(payload_bytes) > MAX_PAYLOAD_BYTES:
        raise GenerationError("PAYLOAD_SIZE_LIMIT")
    _check_hash(generator_hash, "INVALID_GENERATOR_HASH")
    _check_hash(protocol_hash, "INVALID_PROTOCOL_HASH")
    if not isinstance(groups, Mapping) or set(groups) != {f"g{i}" for i in range(GROUP_COUNT)}:
        raise GenerationError("INVALID_GROUP_INDEX")
    if not isinstance(variants, Mapping) or set(variants) != {f"v{i}" for i in range(VARIANT_COUNT)}:
        raise GenerationError("INVALID_VARIANT_INDEX")
    normalized_groups: dict[str, dict[str, str]] = {}
    used_groups: set[str] = set()
    used_contrasts: set[str] = set()
    for group_key in sorted(groups):
        fields = _mapping(groups[group_key], "INVALID_GROUP_METADATA")
        if set(fields) != {"contrast", "generatorGroupId", "geometryGroupId", "sourceGroupId"}:
            raise GenerationError("INVALID_GROUP_METADATA")
        normalized = {name: _check_descriptor_id(fields[name], "INVALID_GROUP_METADATA")
                     for name in ("contrast", "generatorGroupId", "geometryGroupId", "sourceGroupId")}
        if normalized["contrast"] != CONTRASTS[int(group_key[1:])] or normalized["contrast"] in used_contrasts:
            raise GenerationError("ALIASED_GROUP_METADATA")
        used_contrasts.add(normalized["contrast"])
        identities = (normalized["generatorGroupId"], normalized["geometryGroupId"], normalized["sourceGroupId"])
        if len(set(identities)) != 3 or used_groups.intersection(identities):
            raise GenerationError("ALIASED_GROUP_METADATA")
        used_groups.update(identities)
        normalized_groups[group_key] = normalized
    if normalized_groups != _group_metadata():
        raise GenerationError("UNREGISTERED_GROUP_METADATA")

    normalized_variants: dict[str, dict[str, int | float]] = {}
    for variant_key in sorted(variants):
        fields = _mapping(variants[variant_key], "INVALID_VARIANT_METADATA")
        if set(fields) != {"phase", "sourcePitchMeters", "oversampling"}:
            raise GenerationError("INVALID_VARIANT_METADATA")
        phase = fields["phase"]
        pitch = fields["sourcePitchMeters"]
        oversampling = fields["oversampling"]
        if isinstance(phase, bool) or not isinstance(phase, (int, float)) or not math.isfinite(float(phase)) \
                or not 0.0 <= float(phase) < 1.0:
            raise GenerationError("INVALID_VARIANT_METADATA")
        if isinstance(pitch, bool) or not isinstance(pitch, (int, float)) or not math.isfinite(float(pitch)) \
                or float(pitch) <= 0.0:
            raise GenerationError("INVALID_VARIANT_METADATA")
        if isinstance(oversampling, bool) or not isinstance(oversampling, int) or oversampling not in (1, 2):
            raise GenerationError("INVALID_VARIANT_METADATA")
        normalized_variants[variant_key] = {
            "phase": float(phase), "sourcePitchMeters": float(pitch), "oversampling": oversampling,
        }
    if normalized_variants != VARIANTS:
        raise GenerationError("UNREGISTERED_PHYSICAL_VARIANTS")

    if not isinstance(recipes, Sequence) or isinstance(recipes, (str, bytes)) or len(recipes) != CASE_COUNT:
        raise GenerationError("INVALID_CASE_COUNT")
    entries: list[dict[str, str]] = []
    seen: set[tuple[str, str, str]] = set()
    for record in recipes:
        fields = _mapping(record, "INVALID_RECIPE_RECORD")
        if set(fields) != {"group", "variant", "side", "recipe"}:
            raise GenerationError("INVALID_RECIPE_RECORD")
        group = fields["group"]
        variant = fields["variant"]
        side = fields["side"]
        if group not in normalized_groups or variant not in normalized_variants or side not in SIDES:
            raise GenerationError("UNKNOWN_RECIPE_REFERENCE")
        identity = (group, variant, side)
        if identity in seen:
            raise GenerationError("DUPLICATE_RECIPE_REFERENCE")
        seen.add(identity)
        recipe = _mapping(fields["recipe"], "INVALID_RECIPE_BODY")
        record_bytes = canonical_json_bytes({"group": group, "variant": variant, "side": side, "recipe": recipe})
        entries.append({"id": f"b-holdout-v2/{group}/{variant}/{side}", "group": group,
                        "variant": variant, "side": side, "recipeHash": _sha256(record_bytes)})
    expected_refs = {(f"g{group}", f"v{variant}", side)
                     for group in range(GROUP_COUNT) for variant in range(VARIANT_COUNT) for side in SIDES}
    if seen != expected_refs:
        raise GenerationError("INCOMPLETE_RECIPE_REFERENCES")
    entries.sort(key=lambda entry: entry["id"])
    index = {
        "schema": 1,
        "version": PROTOCOL_VERSION,
        "generatorHash": generator_hash,
        "protocolHash": protocol_hash,
        "split": "HOLDOUT",
        "payload": {"sha256": _sha256(payload_bytes), "byteCount": len(payload_bytes)},
        "groups": normalized_groups,
        "variants": normalized_variants,
        "entries": entries,
    }
    encoded = canonical_json_bytes(index)
    if len(encoded) > MAX_INDEX_BYTES:
        raise GenerationError("INDEX_SIZE_LIMIT")
    validate_public_index(encoded)
    return encoded


def validate_public_index(data: bytes) -> bool:
    """Validate exact schema, compact references, canonical bytes and size bounds."""
    if not isinstance(data, bytes) or len(data) > MAX_INDEX_BYTES or not data.endswith(b"\n"):
        raise GenerationError("INDEX_SIZE_OR_ENCODING")
    try:
        index = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as failure:
        raise GenerationError("INDEX_JSON_INVALID") from failure
    if canonical_json_bytes(index) != data:
        raise GenerationError("INDEX_NOT_CANONICAL")
    if not isinstance(index, dict) or set(index) != {
            "schema", "version", "generatorHash", "protocolHash", "split", "payload",
            "groups", "variants", "entries"}:
        raise GenerationError("INDEX_SCHEMA_INVALID")
    if (isinstance(index["schema"], bool) or index["schema"] != 1
            or index["version"] != PROTOCOL_VERSION or index["split"] != "HOLDOUT"):
        raise GenerationError("INDEX_PROTOCOL_INVALID")
    _check_hash(index["generatorHash"], "INVALID_GENERATOR_HASH")
    _check_hash(index["protocolHash"], "INVALID_PROTOCOL_HASH")
    payload = _mapping(index["payload"], "INDEX_PAYLOAD_INVALID")
    if set(payload) != {"sha256", "byteCount"}:
        raise GenerationError("INDEX_PAYLOAD_INVALID")
    _check_hash(payload["sha256"], "INDEX_PAYLOAD_INVALID")
    if isinstance(payload["byteCount"], bool) or not isinstance(payload["byteCount"], int) \
            or not 0 <= payload["byteCount"] <= MAX_PAYLOAD_BYTES:
        raise GenerationError("INDEX_PAYLOAD_INVALID")
    groups = _mapping(index["groups"], "INDEX_GROUPS_INVALID")
    if set(groups) != {f"g{i}" for i in range(GROUP_COUNT)}:
        raise GenerationError("INDEX_GROUPS_INVALID")
    identities: set[str] = set()
    contrasts: set[str] = set()
    for group_key in sorted(groups):
        fields = _mapping(groups[group_key], "INDEX_GROUP_INVALID")
        if set(fields) != {"contrast", "generatorGroupId", "geometryGroupId", "sourceGroupId"}:
            raise GenerationError("INDEX_GROUP_INVALID")
        values = [_check_descriptor_id(fields[name], "INDEX_GROUP_INVALID") for name in
                  ("contrast", "generatorGroupId", "geometryGroupId", "sourceGroupId")]
        if values[0] != CONTRASTS[int(group_key[1:])] or values[0] in contrasts:
            raise GenerationError("INDEX_GROUP_ALIAS")
        contrasts.add(values[0])
        if len(set(values[1:])) != 3 or identities.intersection(values[1:]):
            raise GenerationError("INDEX_GROUP_ALIAS")
        identities.update(values[1:])
    if groups != _group_metadata():
        raise GenerationError("INDEX_GROUP_UNREGISTERED")
    variants = _mapping(index["variants"], "INDEX_VARIANTS_INVALID")
    if set(variants) != {f"v{i}" for i in range(VARIANT_COUNT)}:
        raise GenerationError("INDEX_VARIANTS_INVALID")
    for variant_key, expected in VARIANTS.items():
        fields = _mapping(variants[variant_key], "INDEX_VARIANT_INVALID")
        if (set(fields) != {"phase", "sourcePitchMeters", "oversampling"} or fields != expected
                or isinstance(fields["phase"], bool) or isinstance(fields["sourcePitchMeters"], bool)
                or isinstance(fields["oversampling"], bool)):
            raise GenerationError("INDEX_VARIANT_INVALID")
    entries = index["entries"]
    if not isinstance(entries, list) or len(entries) != CASE_COUNT:
        raise GenerationError("INDEX_CASE_COUNT")
    references: set[tuple[str, str, str]] = set()
    ids: set[str] = set()
    recipe_hashes: set[str] = set()
    previous_id = ""
    for entry in entries:
        fields = _mapping(entry, "INDEX_ENTRY_INVALID")
        if set(fields) != {"id", "group", "variant", "side", "recipeHash"}:
            raise GenerationError("INDEX_ENTRY_INVALID")
        group, variant, side = fields["group"], fields["variant"], fields["side"]
        identity = (group, variant, side)
        expected_id = f"b-holdout-v2/{group}/{variant}/{side}"
        if group not in groups or variant not in variants or side not in SIDES or fields["id"] != expected_id:
            raise GenerationError("INDEX_ENTRY_REFERENCE_INVALID")
        _check_hash(fields["recipeHash"], "INDEX_ENTRY_HASH_INVALID")
        if identity in references or fields["id"] in ids or fields["recipeHash"] in recipe_hashes:
            raise GenerationError("INDEX_ENTRY_DUPLICATE")
        if fields["id"] <= previous_id:
            raise GenerationError("INDEX_ENTRY_ORDER")
        previous_id = fields["id"]
        references.add(identity)
        ids.add(fields["id"])
        recipe_hashes.add(fields["recipeHash"])
    expected_references = {(f"g{group}", f"v{variant}", side)
                           for group in range(GROUP_COUNT) for variant in range(VARIANT_COUNT) for side in SIDES}
    if references != expected_references:
        raise GenerationError("INDEX_ENTRY_COVERAGE")
    return True


def primary_repository_from_common_git_dir(common_git_dir, *, is_bare: bool = False) -> Path:
    """Resolve the primary non-bare repository from `git rev-parse --git-common-dir`."""
    if is_bare or common_git_dir is None:
        raise GenerationError("PRIMARY_REPOSITORY_REQUIRED")
    common = Path(common_git_dir)
    if common.is_symlink():
        raise GenerationError("COMMON_GITDIR_SYMLINK")
    common = common.resolve()
    if common.name != ".git" or not common.is_dir():
        raise GenerationError("COMMON_GITDIR_INVALID")
    primary = common.parent
    if not primary.is_dir() or not (primary / ".git").exists():
        raise GenerationError("PRIMARY_REPOSITORY_INVALID")
    return primary


def generation_scope(primary_root) -> Path:
    """Return the one exact primary-repository generation scope."""
    if primary_root is None:
        raise GenerationError("PRIMARY_REPOSITORY_REQUIRED")
    return Path(primary_root).resolve() / GENERATION_RELATIVE_SCOPE


def _write_atomic(path: Path, data: bytes) -> None:
    if path.exists() or path.is_symlink():
        raise GenerationError("OUTPUT_ALREADY_EXISTS")
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
        try:
            directory_fd = os.open(path.parent, os.O_RDONLY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        except OSError:
            pass
    finally:
        if temporary.exists():
            temporary.unlink()


def _failure_bytes(cause: str, counters: Mapping[str, int] | None = None) -> bytes:
    if not isinstance(cause, str) or not cause.isascii() or not cause.replace("_", "").isalnum():
        cause = "GENERATION_FAILED"
    safe_counts = {}
    for key, value in (counters or {}).items():
        if isinstance(key, str) and re.fullmatch(r"[A-Za-z][A-Za-z0-9]{0,31}", key) \
                and isinstance(value, int) and not isinstance(value, bool) and 0 <= value <= 1_000_000:
            safe_counts[key] = value
    return canonical_json_bytes({"schema": 1, "status": "FAILED", "phase": "GENERATION",
                                 "cause": cause[:48], "counters": safe_counts})


def _record_failure(scope: Path, cause: str, counters: Mapping[str, int] | None = None) -> None:
    if scope.is_dir() and not scope.is_symlink() and not (scope / "failure.json").exists():
        try:
            _write_atomic(scope / "failure.json", _failure_bytes(cause, counters))
        except OSError:
            pass


def claim_generation_scope(primary_root, *, entropy_source: Callable[[int], bytes]) -> tuple[Path, bytes]:
    """Exclusively claim the fixed scope before taking any entropy."""
    if not callable(entropy_source):
        raise GenerationError("ENTROPY_SOURCE_REQUIRED")
    requested_primary = Path(primary_root)
    if requested_primary.is_symlink():
        raise GenerationError("PRIMARY_REPOSITORY_INVALID")
    primary = requested_primary.resolve()
    if not primary.is_dir():
        raise GenerationError("PRIMARY_REPOSITORY_INVALID")
    scope = generation_scope(primary)
    _validate_production_scope(primary, scope)
    scope.parent.mkdir(parents=True, exist_ok=True)
    try:
        scope.mkdir()
    except FileExistsError as failure:
        raise GenerationError("GENERATION_SCOPE_ALREADY_CLAIMED") from failure
    try:
        seed = entropy_source(32)
        if not isinstance(seed, bytes) or len(seed) != 32:
            raise GenerationError("INVALID_MASTER_SEED")
        return scope, seed
    except Exception as failure:
        _record_failure(scope, "MASTER_SEED_UNAVAILABLE")
        if isinstance(failure, GenerationError):
            raise
        raise GenerationError("MASTER_SEED_UNAVAILABLE") from failure


def draw_master_seed(size: int) -> bytes:
    """Read one 32-byte master seed from the OS entropy source."""
    if size != 32:
        raise GenerationError("INVALID_MASTER_SEED_SIZE")
    return os.urandom(size)


def _canonical_document(data: bytes, limit: int, code: str) -> dict:
    if not isinstance(data, bytes) or len(data) > limit or not data.endswith(b"\n"):
        raise GenerationError(code)
    try:
        parsed = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as failure:
        raise GenerationError(code) from failure
    if not isinstance(parsed, dict) or canonical_json_bytes(parsed) != data:
        raise GenerationError(code)
    return parsed


def _validate_package_documents(payload_bytes: bytes, private_bytes: bytes, index: dict) -> None:
    """Bind exact registered recipes and private provenance to the public index."""
    payload = _canonical_document(payload_bytes, MAX_PAYLOAD_BYTES, "PAYLOAD_INVALID_OR_OVERSIZE")
    private = _canonical_document(private_bytes, MAX_PRIVATE_BYTES, "PRIVATE_METADATA_INVALID_OR_OVERSIZE")
    payload_keys = {
        "schema", "version", "generationId", "generatorHash", "protocolHash", "renderer", "groups", "recipes",
    }
    private_keys = {
        "schema", "version", "generationId", "masterSeedHex", "streamDomain", "streamBytesRead",
        "streamBlocksRead", "proposalLimitPerGroup", "generatorHash", "protocolHash",
    }
    has_dummy_marker = "dummyMarker" in payload or "dummyMarker" in private
    if has_dummy_marker:
        payload_keys.add("dummyMarker")
        private_keys.add("dummyMarker")
        if payload.get("dummyMarker") != DUMMY_ONLY_MARKER or private.get("dummyMarker") != DUMMY_ONLY_MARKER:
            raise GenerationError("DUMMY_MARKER_MISMATCH")
    if set(payload) != payload_keys or set(private) != private_keys:
        raise GenerationError("PACKAGE_SCHEMA_INVALID")
    if (isinstance(payload["schema"], bool) or payload["schema"] != 1
            or payload["version"] != PROTOCOL_VERSION
            or payload["generationId"] != GENERATION_ID
            or payload["renderer"] != "strictmath-scalar-v2"):
        raise GenerationError("PAYLOAD_PROTOCOL_INVALID")
    if (isinstance(private["schema"], bool) or private["schema"] != 1
            or private["version"] != PROTOCOL_VERSION
            or private["generationId"] != GENERATION_ID
            or private["streamDomain"] != STREAM_DOMAIN.decode("ascii")
            or private["proposalLimitPerGroup"] != MAX_PROPOSALS_PER_GROUP):
        raise GenerationError("PRIVATE_PROTOCOL_INVALID")
    generator_hash = index["generatorHash"]
    protocol_hash = index["protocolHash"]
    if (payload["generatorHash"] != generator_hash or payload["protocolHash"] != protocol_hash
            or private["generatorHash"] != generator_hash or private["protocolHash"] != protocol_hash):
        raise GenerationError("PACKAGE_HASH_IDENTITY_MISMATCH")
    seed_hex = private["masterSeedHex"]
    if not isinstance(seed_hex, str) or not re.fullmatch(r"[0-9a-f]{64}", seed_hex):
        raise GenerationError("PRIVATE_SEED_INVALID")
    byte_count = private["streamBytesRead"]
    block_count = private["streamBlocksRead"]
    if any(isinstance(value, bool) or not isinstance(value, int) for value in (byte_count, block_count)) \
            or not 0 <= byte_count <= MAX_STREAM_BYTES or block_count != (byte_count + 31) // 32:
        raise GenerationError("PRIVATE_STREAM_COUNTERS_INVALID")

    payload_groups = _mapping(payload["groups"], "PAYLOAD_GROUPS_INVALID")
    index_groups = index["groups"]
    if set(payload_groups) != {f"g{i}" for i in range(GROUP_COUNT)}:
        raise GenerationError("PAYLOAD_GROUPS_INVALID")
    canonical_offsets: set[tuple[float, ...]] = set()
    for index_number in range(GROUP_COUNT):
        group_id = f"g{index_number}"
        group = _mapping(payload_groups[group_id], "PAYLOAD_GROUP_INVALID")
        if set(group) != {"contrast", "interiorOffsetsMeters"}:
            raise GenerationError("PAYLOAD_GROUP_INVALID")
        if group["contrast"] != CONTRASTS[index_number] or group["contrast"] != index_groups[group_id]["contrast"]:
            raise GenerationError("PAYLOAD_GROUP_CONTRAST_MISMATCH")
        offsets = _five_offsets(group["interiorOffsetsMeters"])
        if not valid_geometry(offsets) or canonical_geometry(offsets) in canonical_offsets:
            raise GenerationError("PAYLOAD_GEOMETRY_INVALID")
        canonical_offsets.add(canonical_geometry(offsets))

    recipes = payload["recipes"]
    if not isinstance(recipes, list) or len(recipes) != CASE_COUNT:
        raise GenerationError("PAYLOAD_RECIPE_COUNT")
    entry_by_reference = {
        (entry["group"], entry["variant"], entry["side"]): entry
        for entry in index["entries"]
    }
    seen: set[tuple[str, str, str]] = set()
    for record in recipes:
        fields = _mapping(record, "PAYLOAD_RECIPE_INVALID")
        if set(fields) != {"group", "variant", "side", "recipe"}:
            raise GenerationError("PAYLOAD_RECIPE_INVALID")
        group_id, variant_id, side = fields["group"], fields["variant"], fields["side"]
        if not all(isinstance(value, str) for value in (group_id, variant_id, side)):
            raise GenerationError("PAYLOAD_RECIPE_REFERENCE_INVALID")
        reference = (group_id, variant_id, side)
        if reference not in entry_by_reference or reference in seen:
            raise GenerationError("PAYLOAD_RECIPE_REFERENCE_INVALID")
        seen.add(reference)
        recipe = _mapping(fields["recipe"], "PAYLOAD_RECIPE_BODY_INVALID")
        base_keys = {
            "baseCurve", "phase", "sourcePitchMeters", "oversampling", "clamp", "background",
            "field", "truth", "renderer",
        }
        optional_recipe_keys = {"sourceOrder", "sourceCapability", "sourceLineage", "dummyMarker"}
        if not base_keys.issubset(recipe) or set(recipe) - base_keys - optional_recipe_keys:
            raise GenerationError("PAYLOAD_RECIPE_BODY_INVALID")
        if ("dummyMarker" in recipe) != has_dummy_marker or (
                "dummyMarker" in recipe and recipe["dummyMarker"] != DUMMY_ONLY_MARKER):
            raise GenerationError("DUMMY_MARKER_MISMATCH")
        if recipe["renderer"] != "strictmath-scalar-v2":
            raise GenerationError("PAYLOAD_RECIPE_RENDERER_INVALID")
        if (recipe["phase"] != VARIANTS[variant_id]["phase"]
                or recipe["sourcePitchMeters"] != VARIANTS[variant_id]["sourcePitchMeters"]
                or recipe["oversampling"] != VARIANTS[variant_id]["oversampling"]):
            raise GenerationError("PAYLOAD_RECIPE_VARIANT_MISMATCH")
        base = _mapping(recipe["baseCurve"], "PAYLOAD_RECIPE_BASE_INVALID")
        if set(base) != {"kind", "knotsXMeters", "interiorOffsetsMeters", "endpointY", "outsideY"}:
            raise GenerationError("PAYLOAD_RECIPE_BASE_INVALID")
        expected_group_offsets = payload_groups[group_id]["interiorOffsetsMeters"]
        if (base["kind"] != "quintic-spline-v1"
                or base["knotsXMeters"] != [0, 20, 40, 60, 80, 100, 120]
                or base["interiorOffsetsMeters"] != expected_group_offsets
                or base["endpointY"] != 30.0 or base["outsideY"] != 30.0):
            raise GenerationError("PAYLOAD_RECIPE_BASE_MISMATCH")
        if not isinstance(recipe["field"], dict) or not isinstance(recipe["truth"], dict):
            raise GenerationError("PAYLOAD_RECIPE_BODY_INVALID")
        truth = recipe["truth"]
        if (set(truth) != {"association", "location", "curve"}
                or not isinstance(truth["association"], str) or not isinstance(truth["location"], str)
                or truth["association"] not in {"UNIQUE", "AMBIGUOUS", "NOT_APPLICABLE"}
                or truth["location"] not in {"OBSERVED_STRUCTURE", "UNKNOWN"}
                or not isinstance(truth["curve"], dict)):
            raise GenerationError("PAYLOAD_TRUTH_INVALID")
        if group_id == f"g{CONTRASTS.index('sparse-crossing-noisy-strands-vs-persistent-parallel')}":
            expected_branches = (
                ["F+(-2+4t)", "F+(1.4-0.7t)", "F+(2-4t)"] if side == "left"
                else ["F-4", "F+4"]
            )
            if (truth["association"] != "AMBIGUOUS" or truth["location"] != "OBSERVED_STRUCTURE"
                    or truth["curve"] != {"base": "F", "branches": expected_branches}):
                raise GenerationError("PAYLOAD_PARALLEL_TRUTH_INVALID")
        if (group_id == f"g{CONTRASTS.index('resolved-short-apex-vs-stair-step-alias')}"
                and side == "right" and truth["curve"] != {"base": "F"}):
            raise GenerationError("PAYLOAD_ALIAS_TRUTH_INVALID")
        recipe_record = canonical_json_bytes({
            "group": group_id, "variant": variant_id, "side": side, "recipe": recipe,
        })
        registered_recipe = _recipe_record(
            CONTRASTS[int(group_id[1:])],
            tuple(payload_groups[group_id]["interiorOffsetsMeters"]),
            variant_id,
            side,
        )
        recipe_without_dummy_marker = dict(recipe)
        recipe_without_dummy_marker.pop("dummyMarker", None)
        if canonical_json_bytes(recipe_without_dummy_marker) != canonical_json_bytes(registered_recipe):
            raise GenerationError("PAYLOAD_RECIPE_UNREGISTERED")
        if _sha256(recipe_record) != entry_by_reference[reference]["recipeHash"]:
            raise GenerationError("PAYLOAD_RECIPE_HASH_MISMATCH")
    if seen != set(entry_by_reference):
        raise GenerationError("PAYLOAD_RECIPE_COVERAGE")


def publish_package(scope, payload_bytes: bytes, private_bytes: bytes, index_bytes: bytes) -> dict[str, str | int]:
    """Publish bounded payload/private files, then atomically publish the public index last."""
    directory = Path(scope)
    try:
        if directory.is_symlink() or not directory.is_dir():
            raise GenerationError("CLAIMED_SCOPE_REQUIRED")
        validate_public_index(index_bytes)
        index = json.loads(index_bytes.decode("utf-8"))
        if index["payload"]["sha256"] != _sha256(payload_bytes) or index["payload"]["byteCount"] != len(payload_bytes):
            raise GenerationError("PAYLOAD_INDEX_BINDING_MISMATCH")
        _validate_package_documents(payload_bytes, private_bytes, index)
        names = ("payload.json", "generation-private.json", "index.json", "failure.json")
        if any((directory / name).exists() or (directory / name).is_symlink() for name in names):
            raise GenerationError("OUTPUT_ALREADY_EXISTS")

        _write_atomic(directory / "payload.json", payload_bytes)
        _write_atomic(directory / "generation-private.json", private_bytes)
        stored_payload = (directory / "payload.json").read_bytes()
        stored_private = (directory / "generation-private.json").read_bytes()
        if stored_payload != payload_bytes or stored_private != private_bytes:
            raise GenerationError("PRIVATE_PACKAGE_VERIFY_FAILED")
        if _sha256(stored_payload) != index["payload"]["sha256"]:
            raise GenerationError("PAYLOAD_HASH_MISMATCH")
        validate_public_index(index_bytes)
        _write_atomic(directory / "index.json", index_bytes)
        return {
            "payloadSHA256": _sha256(stored_payload),
            "privateSHA256": _sha256(stored_private),
            "indexSHA256": _sha256(index_bytes),
            "payloadBytes": len(stored_payload),
            "privateBytes": len(stored_private),
            "indexBytes": len(index_bytes),
        }
    except Exception as failure:
        if isinstance(failure, GenerationError):
            code = failure.code
        else:
            code = "PACKAGE_PUBLICATION_FAILED"
        _record_failure(directory, code, {
            "payloadBytes": len(payload_bytes) if isinstance(payload_bytes, bytes) else 0,
            "privateBytes": len(private_bytes) if isinstance(private_bytes, bytes) else 0,
            "indexBytes": len(index_bytes) if isinstance(index_bytes, bytes) else 0,
        })
        if isinstance(failure, GenerationError):
            raise
        raise GenerationError(code) from failure


def _recipe_record(contrast: str, offsets: tuple[float, ...], variant_id: str, side: str) -> dict:
    variant = VARIANTS[variant_id]
    base = {"kind": "quintic-spline-v1", "knotsXMeters": [0, 20, 40, 60, 80, 100, 120],
            "interiorOffsetsMeters": list(offsets), "endpointY": 30.0, "outsideY": 30.0}
    common = {"baseCurve": base, "phase": variant["phase"], "sourcePitchMeters": variant["sourcePitchMeters"],
              "oversampling": variant["oversampling"], "clamp": [0.0, 1.0], "background": 0.02}
    curve = {"base": "F"}
    association = "UNIQUE"
    location = "OBSERVED_STRUCTURE"
    if contrast == "weak-coherent-curve-vs-disconnected-flecks":
        field = {"kind": "gaussian-band", "amplitude": 0.08, "sigmaMeters": 0.9, "center": "F"}
        if side == "right":
            field["longitudinalWindows"] = {"centersXMeters": [13, 41, 74, 106], "radiusMeters": 1.6,
                                              "power": 3, "maxCombine": True}
            association, location = "NOT_APPLICABLE", "UNKNOWN"
    elif contrast == "concentrated-band-vs-diffuse-band":
        field = {"kind": "gaussian-band", "amplitude": 0.16 if side == "left" else 0.032,
                 "sigmaMeters": 0.65 if side == "left" else 3.25, "center": "F",
                 "equalContinuousExcessIntegral": 0.104 * (2.0 * math.pi) ** 0.5}
    elif contrast == "sparse-crossing-noisy-strands-vs-persistent-parallel":
        if side == "left":
            field = {"kind": "gaussian-band-sum", "amplitude": 0.035, "sigmaMeters": 0.28,
                     "centerOffsetsFromF": [-2.0, 1.4, 2.0],
                     "offsetFunctions": ["linear-minus2-to-plus2", "linear-plus1.4-to-plus0.7",
                                         "linear-plus2-to-minus2"]}
            branches = ["F+(-2+4t)", "F+(1.4-0.7t)", "F+(2-4t)"]
        else:
            field = {"kind": "gaussian-band-sum", "amplitude": 0.44, "sigmaMeters": 0.8,
                     "centerOffsetsFromF": [-4.0, 4.0]}
            branches = ["F-4", "F+4"]
        curve = {"base": "F", "branches": branches}
        association, location = "AMBIGUOUS", "OBSERVED_STRUCTURE"
    elif contrast == "resolved-short-apex-vs-stair-step-alias":
        if side == "left":
            field = {"kind": "gaussian-band", "amplitude": 0.30, "sigmaMeters": 0.7,
                     "center": "F+4.5*C((x-60)/6)",
                     "apexFunction": {"kind": "compact-cubic", "supportMeters": 6.0,
                                      "formula": "(1-u*u)^3-if-abs-u-lt-1-else-0"}}
            curve = {"base": "F", "apex": "4.5*C((x-60)/6)"}
        else:
            field = {"kind": "gaussian-band", "amplitude": 0.14, "sigmaMeters": 0.7,
                     "center": "pitch*floor(F/pitch+0.5)", "quantizationPitch": variant["sourcePitchMeters"]}
            curve = {"base": "F"}
    elif contrast == "displaced-strong-corridor-vs-nearby-faint-strand":
        if side == "left":
            field = {"kind": "gaussian-band", "amplitude": 0.70, "sigmaMeters": 1.1, "center": "F+14"}
            curve = {"base": "F", "offsetMeters": 14.0}
        else:
            field = {"kind": "gaussian-band-sum", "components": [
                {"amplitude": 0.70, "sigmaMeters": 1.1, "center": "F+14"},
                {"amplitude": 0.035, "sigmaMeters": 0.7, "center": 30.0},
            ]}
            curve = {"base": "F", "offsetMeters": 14.0}
    elif contrast == "multiple-physical-pitches-vs-reversed-source-order":
        field = {"kind": "gaussian-band", "amplitude": 0.16, "sigmaMeters": 1.05, "center": "F"}
        if side == "right":
            common["sourceOrder"] = "REVERSED"
    elif contrast == "palette-semantic-vs-rendered-source":
        field = {"kind": "gaussian-band", "amplitude": 0.16, "sigmaMeters": 1.05, "center": "F"}
        common["sourceCapability"] = "NATIVE_SEMANTIC" if side == "left" else "RENDERED_ONLY"
        common["sourceLineage"] = "SYNTHETIC_PROXY"
    else:
        raise GenerationError("UNKNOWN_CONTRAST")

    truth = {"association": association, "location": location, "curve": curve}
    return {**common, "field": field, "truth": truth, "renderer": "strictmath-scalar-v2"}


def _group_metadata() -> dict[str, dict[str, str]]:
    groups = {}
    for index, contrast in enumerate(CONTRASTS):
        group = f"g{index}"
        groups[group] = {
            "contrast": contrast,
            "generatorGroupId": f"b-observation-holdout-v2-generator-{group}",
            "geometryGroupId": f"b-observation-holdout-v2-geometry-{group}",
            "sourceGroupId": f"b-observation-holdout-v2-source-{group}",
        }
    return groups


def build_generation_documents(seed: bytes, *, generator_hash: str, protocol_hash: str):
    """Construct bounded private documents and their redacted public index."""
    stream = HmacByteStream(seed)

    def proposal():
        values = []
        for _ in range(5):
            remaining = MAX_STREAM_BYTES - stream.bytes_read
            if remaining <= 0:
                raise GenerationError("RANDOM_BYTE_BUDGET")
            values.append(draw_offset(stream, remaining))
        return tuple(values)

    accepted = draw_distinct_groups(proposal)
    recipes = []
    for group_index, contrast in enumerate(CONTRASTS):
        for variant_id in VARIANTS:
            for side in SIDES:
                recipes.append({
                    "group": f"g{group_index}", "variant": variant_id, "side": side,
                    "recipe": _recipe_record(contrast, accepted[group_index], variant_id, side),
                })
    payload = {
        "schema": 1, "version": PROTOCOL_VERSION, "generationId": GENERATION_ID,
        "generatorHash": generator_hash, "protocolHash": protocol_hash,
        "renderer": "strictmath-scalar-v2", "groups": {
            f"g{index}": {"contrast": CONTRASTS[index], "interiorOffsetsMeters": list(accepted[index])}
            for index in range(GROUP_COUNT)
        },
        "recipes": recipes,
    }
    payload_bytes = canonical_json_bytes(payload)
    if len(payload_bytes) > MAX_PAYLOAD_BYTES:
        raise GenerationError("PAYLOAD_SIZE_LIMIT")
    private = {
        "schema": 1, "version": PROTOCOL_VERSION, "generationId": GENERATION_ID,
        "masterSeedHex": seed.hex(), "streamDomain": STREAM_DOMAIN.decode("ascii"),
        "streamBytesRead": stream.bytes_read, "streamBlocksRead": stream.blocks_read,
        "proposalLimitPerGroup": MAX_PROPOSALS_PER_GROUP,
        "generatorHash": generator_hash, "protocolHash": protocol_hash,
    }
    private_bytes = canonical_json_bytes(private)
    if len(private_bytes) > MAX_PRIVATE_BYTES:
        raise GenerationError("PRIVATE_METADATA_SIZE_LIMIT")
    index_bytes = build_public_index(
        _group_metadata(), VARIANTS, recipes, payload_bytes=payload_bytes,
        generator_hash=generator_hash, protocol_hash=protocol_hash,
    )
    return payload_bytes, private_bytes, index_bytes


def _repository_hashes(primary: Path) -> tuple[str, str]:
    """Hash the actual producer source and the one fixed reviewed protocol document."""
    script = Path(__file__).absolute()
    expected_script = primary / "scripts" / "generate-observation-holdout.py"
    if script != expected_script or script.is_symlink() or expected_script.is_symlink():
        raise GenerationError("PRODUCER_SOURCE_OUTSIDE_PRIMARY")
    protocol_path = primary / "superpowers" / "docs" / "plans" / "2026-10-18-b-sealed-holdout-generator-contract.md"
    if protocol_path.is_symlink() or protocol_path.resolve() != protocol_path or not protocol_path.is_file():
        raise GenerationError("PROTOCOL_DOCUMENT_UNAVAILABLE")
    try:
        source_bytes = script.read_bytes()
        protocol_bytes = protocol_path.read_bytes()
    except OSError as failure:
        raise GenerationError("SOURCE_HASH_UNAVAILABLE") from failure
    return _sha256(source_bytes), _sha256(protocol_bytes)


def _validate_production_scope(primary: Path, scope: Path) -> None:
    """Refuse symlinked/escaped output placement before entropy is read."""
    expected = primary / GENERATION_RELATIVE_SCOPE
    if scope != expected or scope.resolve(strict=False) != expected:
        raise GenerationError("OUTPUT_SCOPE_INVALID")
    if any(path.is_symlink() for path in (primary / "build", primary / "build" / "sealed-observation-v2")):
        raise GenerationError("OUTPUT_ANCESTOR_SYMLINK")
    worktrees = (primary / ".worktrees").resolve()
    try:
        scope.resolve(strict=False).relative_to(worktrees)
    except ValueError:
        pass
    else:
        raise GenerationError("OUTPUT_SCOPE_IN_WORKTREE")
    try:
        scope.resolve(strict=False).relative_to(primary.resolve())
    except ValueError as failure:
        raise GenerationError("OUTPUT_SCOPE_ESCAPES_PRIMARY") from failure


def run_production_once(*, primary: Path, entropy_source: Callable[[int], bytes] = draw_master_seed):
    """Run one fixed-scope generation after source/protocol binding checks."""
    requested_primary = Path(primary)
    if requested_primary.is_symlink():
        raise GenerationError("PRIMARY_REPOSITORY_INVALID")
    primary = requested_primary.resolve()
    if not primary.is_dir() or not (primary / ".git").exists():
        raise GenerationError("PRIMARY_REPOSITORY_INVALID")
    scope = generation_scope(primary)
    _validate_production_scope(primary, scope)
    generator_hash, protocol_hash = _repository_hashes(primary)
    claimed_scope, seed = claim_generation_scope(primary, entropy_source=entropy_source)
    try:
        payload, private, index = build_generation_documents(
            seed, generator_hash=generator_hash, protocol_hash=protocol_hash,
        )
        return publish_package(claimed_scope, payload, private, index)
    except Exception as failure:
        code = failure.code if isinstance(failure, GenerationError) else "GENERATION_FAILED"
        _record_failure(claimed_scope, code, {})
        raise


def _git_primary_repository(cwd: Path) -> Path:
    """Resolve Git's common directory and reject bare repositories."""
    try:
        common_text = subprocess.check_output(
            ["git", "rev-parse", "--git-common-dir"], cwd=cwd, stderr=subprocess.DEVNULL, text=True,
        ).strip()
        bare_text = subprocess.check_output(
            ["git", "rev-parse", "--is-bare-repository"], cwd=cwd, stderr=subprocess.DEVNULL, text=True,
        ).strip()
    except (OSError, subprocess.CalledProcessError) as failure:
        raise GenerationError("PRIMARY_REPOSITORY_REQUIRED") from failure
    return primary_repository_from_common_git_dir(cwd / common_text, is_bare=(bare_text != "false"))


def main(argv=None) -> dict[str, str | int]:
    """Fixed no-argument production CLI; tests should use dummy helper APIs only."""
    arguments = list(sys.argv[1:] if argv is None else argv)
    if arguments:
        raise GenerationError("ARGUMENTS_NOT_SUPPORTED")
    if os.environ.get("CI", "").strip().lower() not in ("", "0", "false", "no"):
        raise GenerationError("CI_EXECUTION_REFUSED")
    primary = _git_primary_repository(Path.cwd())
    return run_production_once(primary=primary, entropy_source=draw_master_seed)


if __name__ == "__main__":
    try:
        summary = main()
        print(canonical_json_bytes({"status": "CREATED", **summary}).decode("utf-8"), end="")
    except GenerationError as failure:
        print(canonical_json_bytes({"status": "REFUSED", "cause": failure.code}).decode("utf-8"), end="", file=sys.stderr)
        raise SystemExit(2)
