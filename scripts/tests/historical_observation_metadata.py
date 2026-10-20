"""Authenticated public historical METADATA ONLY, never executable authority.

Positive controls read the exact bde65473 archive authenticated by independent
encoded/archive/freeze roots. No archived source is compiled or executed, and no
empirical action exists here. Current source remains subject to each old guard.
"""
import base64
from functools import lru_cache
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import struct
from types import MappingProxyType
import zipfile

ROOT = Path(__file__).resolve().parents[2]
RESOURCE = ROOT / 'src/test/resources/b-observation-authority-fixtures/public-bde6547-metadata.zip.base64'
REVISION = 'bde65473d9de4df815606df57ac21339eee2c17e'
ENCODED_SHA = 'e7358d0d5f1f836984f14194c1a896679eb31c70ea95f3aadd463e1b14a5c77a'
ARCHIVE_SHA = 'e66124aef50c10c1be483f339e586642d7a6618089700829011f6b82cd3e104e'
FREEZE = 'superpowers/docs/plans/2026-10-19-b-single-train-comparator-freeze.json'
FREEZE_SHA = '5aad59b456a8c3970074e93f876febda698e6285a0cce0bcf6e7b5ed60c38ed7'
EXTRA = {
    'superpowers/docs/plans/2026-10-18-b-observation-feasibility-manifest.json': '8fb47902e402bded101cd239d52b1f67655616f12ba6d0d7a35918b203a0aa77',
    'superpowers/docs/plans/2026-10-18-b-observation-reviewed-source.json': '61246d9c65582c990ad52ed581a8de2c13b3a4671c4a3e70a0b1fd38289e17a6',
    'superpowers/docs/plans/2026-10-18-b-observation-runtime-diagnostic-freeze.json': '3c9e872713736d1f475e3c9983bdf5be01e71dc88e89f24133a8cff241fd171d',
    'superpowers/docs/plans/2026-10-18-b-observation-prospective-fit-policy.json': '53a9be63e27bd25476954f507e86e7d554c9f0953b9ea9de27851a0ef0fd3d93',
    'superpowers/docs/plans/2026-10-18-b-observation-optimized-runtime-policy.json': 'f298376bb5da000d71fa9fbb12b5d2c3635309d07e5cf758218a06feee4279c5',
    'src/test/resources/b-observation-identification/holdout-index.json': '97b134e7c983b698679258f244ee8431696bb34257615ce27675e19c21f6b092',
}


def _safe_path(name):
    return (isinstance(name, str) and 0 < len(name.encode('utf-8')) <= 256
            and not any(char in name for char in ('\\', ':', '\0'))
            and not name.startswith('/') and not name.endswith('/')
            and '..' not in PurePosixPath(name).parts and str(PurePosixPath(name)) == name)


def _bounded(path, limit):
    if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= limit:
        raise ValueError('historical metadata file bound')
    with path.open('rb') as stream:
        raw = stream.read(limit + 1)
    if len(raw) > limit:
        raise ValueError('historical metadata file bound')
    return raw


def authenticate_encoded(encoded):
    """Authenticate independent encoded and decoded fixture roots before parsing.

    Args:
        encoded: Exact bounded public fixture resource bytes.
    Returns:
        Independently authenticated historical ZIP bytes, not execution approval.
    Raises:
        ValueError: Size, base64 or either independent digest differs.
    """
    if len(encoded) != 426050 or hashlib.sha256(encoded).hexdigest() != ENCODED_SHA:
        raise ValueError('historical encoded archive digest')
    try:
        raw = base64.b64decode(b''.join(encoded.split()), validate=True)
    except ValueError as error:
        raise ValueError('historical encoded archive syntax') from error
    if len(raw) != 315387 or hashlib.sha256(raw).hexdigest() != ARCHIVE_SHA:
        raise ValueError('historical decoded archive digest')
    return raw


def parse_members(raw, allowed):
    """Parse a bounded exact member set; this parser alone authenticates nothing.

    Args:
        raw: ZIP bytes capped at2MiB.
        allowed: Complete safe member-name set, at most87 entries.
    Returns:
        Opaque member bytes, never extracted, compiled or executed.
    Raises:
        ValueError: Unsafe/duplicate/unknown/missing/oversized ZIP metadata or data.
    """
    if (len(raw) > 2097152 or not 0 < len(allowed) <= 87
            or not all(_safe_path(name) for name in allowed)):
        raise ValueError('historical archive bounds')
    end = raw.rfind(b'PK\x05\x06', max(0, len(raw) - 65557))
    if end < 0 or end + 22 > len(raw):
        raise ValueError('historical archive end record')
    disk, start_disk, disk_count, count, size, offset, comment = struct.unpack_from('<4H2IH', raw, end + 4)
    if disk or start_disk or disk_count != count or count > 87 or comment or end + 22 != len(raw) or offset + size != end:
        raise ValueError('historical archive directory bounds')
    # Verify actual bounded records before ZipFile may allocate its metadata list;
    # the untrusted EOCD count alone need not describe the central directory.
    cursor, records = offset, 0
    while cursor < end:
        if records >= count or cursor + 46 > end or raw[cursor:cursor + 4] != b'PK\x01\x02':
            raise ValueError('historical archive directory record count')
        name_bytes, extra_bytes, comment_bytes = struct.unpack_from('<3H', raw, cursor + 28)
        if not 0 < name_bytes <= 256:
            raise ValueError('historical archive directory path bound')
        cursor += 46 + name_bytes + extra_bytes + comment_bytes
        records += 1
    if cursor != end or records != count:
        raise ValueError('historical archive directory layout')
    entries = {}
    expanded = 0
    try:
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            if len(archive.infolist()) != count:
                raise ValueError('historical archive count')
            for info in archive.infolist():
                name = info.filename
                if (not _safe_path(name) or info.orig_filename != name or info.is_dir()
                        or name not in allowed or name in entries or info.flag_bits & 1
                        or info.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED)):
                    raise ValueError('historical archive path/inventory')
                if info.file_size > 1048576:
                    raise ValueError('historical archive member bound')
                with archive.open(info) as stream:
                    value = stream.read(1048577)
                expanded += len(value)
                if len(value) > 1048576 or expanded > 2097152:
                    raise ValueError('historical archive expanded bound')
                entries[name] = value
    except (zipfile.BadZipFile, EOFError, NotImplementedError) as error:
        raise ValueError('historical archive corrupt') from error
    if set(entries) != set(allowed):
        raise ValueError('historical archive incomplete inventory')
    return entries


@lru_cache(maxsize=1)
def historical_members():
    """Return immutable authenticated87-member public historical metadata.

    Returns:
        Read-only name-to-bytes map bound to the independent comparator freeze.
    Raises:
        ValueError: Resource, freeze, exact inventory or any member digest changed.
    """
    freeze = _bounded(ROOT / FREEZE, 65536)
    if hashlib.sha256(freeze).hexdigest() != FREEZE_SHA:
        raise ValueError('historical independent freeze changed')
    members = json.loads(freeze)['members']
    if len(members) != 80:
        raise ValueError('historical80-member root inventory')
    allowed = set(members) | set(EXTRA) | {FREEZE}
    if len(allowed) != 87:
        raise ValueError('historical87-member archive inventory')
    entries = parse_members(authenticate_encoded(_bounded(RESOURCE, 426050)), allowed)
    for name, expected in {**members, **EXTRA, FREEZE: FREEZE_SHA}.items():
        if hashlib.sha256(entries[name]).hexdigest() != expected:
            raise ValueError('historical member digest: ' + name)
    return MappingProxyType(entries)


def historical_bytes(name):
    """Return one authenticated opaque historical member, without execution.

    Args:
        name: Exact allowed archive-relative path.
    Returns:
        Immutable historical bytes.
    Raises:
        ValueError: The requested path is outside the authenticated public set.
    """
    if name not in historical_members():
        raise ValueError('unknown historical metadata member')
    return historical_members()[name]


def historical_source_path(member):
    """Resolve one original relative core path in the authenticated source set.

    Args:
        member: Original package-relative core member path.
    Returns:
        Its unique full historical source path.
    Raises:
        ValueError: The member is missing or ambiguous.
    """
    paths = [parent + '/org/openstreetmap/josm/plugins/wayheatmaptracer/' + member
             for parent in ('src/main/java', 'src/test/java')]
    found = [path for path in paths if path in historical_members()]
    if len(found) != 1:
        raise ValueError('historical core path ownership')
    return found[0]


def current_mismatches(members):
    """List current files refused by historical byte bindings; grants no authority.

    Args:
        members: Full historical paths with their original digest expectations.
    Returns:
        Sorted mismatched paths; no exact growing current drift-set is assumed.
    Raises:
        ValueError: A requested path is not authenticated historical metadata.
    """
    changed = []
    for path, expected in members.items():
        historical_bytes(path)
        target = ROOT / path
        if not target.is_file() or target.is_symlink() or hashlib.sha256(_bounded(target, 1048576)).hexdigest() != expected:
            changed.append(path)
    return sorted(changed)
