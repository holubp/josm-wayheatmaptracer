"""Offline boot DATA verification; never execute an archive's tools or classes.

Expected records belong to independent review, observations to this verifier.
UNIT records exercise bytes/control only. The production registry is empty;
neither UNIT receipts nor a generated manifest qualify a HOST/runtime. USTAR
regular files/directories (optionally gzip) are the only archive family admitted.
Python/tool qualification memory is separate from, and does not seal, case Work.
"""
from pathlib import Path, PurePosixPath
import gzip
import hashlib
import json
import os
import stat
import struct
import subprocess
import tempfile
import signal
from time import monotonic

REGISTERED_BUNDLES = {}  # independently installed (record path, size, SHA256)
REGISTERED_SIGNATURE_PROFILES = {}  # reviewed executable/keyring/protocol profiles
_ISSUED = object()  # private origin marker; not a secret/trust or build proof
CHUNK = 65536
SOURCE_COMMIT = '4f53ad68ec1ef59f1c20bb0c300fa6f17676597d'
BUILD_COMMIT = '1271f10a26c47e1489a814dd2731f936a588d621'
SOURCE_REF = 'jdk-17.0.12+7_adopt'
# Accepted source preparation, not a claim about compiled/installed bytes.
LOADER_SOURCES = {
    'BuiltinClassLoader.java': (38210, '8aac2c7437d523fa3f197eeb72583429c028d749d1d10717e45cbfc9acafb10d'),
    'ClassLoader.java': (110555, '37df05f93da920426d79281606bbfc12172b819723cc33d66789187fdd707ef0'),
    'ClassLoaders.java': (8870, 'f6822159e69d3435ccc116ef05391b5e7215fe4649dabccf81f29ecca45cd980'),
    'Resource.java': (6359, '97bec38ff8daba0df52fe1c1755d7d9a962b52e5b1e8f823bef7b6333461a5cb'),
    'SecureClassLoader.java': (10845, '176e20f6dd38cbf4e6ca0a2a4496d385f693dd3e75deff59cb34f2d24fe2d639'),
    'URLClassPath.java': (47536, '5b7b23b731164aee1b34d77a020297c0afe784ce1a58b4e1a6d891f2bd6d0f20'),
}

class Pending(RuntimeError):
    """Specific absent or mismatched artifact/tool/source premise."""

def require(condition, reason):
    if not condition:
        raise Pending(reason)

def _identity(info):
    return info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns

def _copy_opened(source, destination, size, digest, cancel):
    require(type(size) is int and size > 0, 'FILE_SIZE_EXPECTATION')
    h = hashlib.sha256(); count = 0
    with os.fdopen(os.open(source, os.O_RDONLY | os.O_NOFOLLOW), 'rb') as stream:
        before = os.fstat(stream.fileno())
        require(stat.S_ISREG(before.st_mode) and before.st_size == size, 'OPENED_SIZE_DRIFT')
        with destination.open('xb') as output:
            while count < size:
                cancel(); block = stream.read(min(CHUNK, size-count))
                require(bool(block), 'OPENED_TRUNCATED')
                count += len(block); h.update(block); output.write(block)
            require(not stream.read(1), 'OPENED_EXPANSION')
        require(_identity(before) == _identity(os.fstat(stream.fileno())), 'OPENED_MUTATION')
    require(h.hexdigest() == digest, 'OPENED_DIGEST_DRIFT')

def file_digest(path, expected_bytes, cancel=lambda: None):
    """Hash a real regular opened image/tool; no version/path-based authority."""
    h = hashlib.sha256(); count = 0
    with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW), 'rb') as stream:
        before = os.fstat(stream.fileno())
        require(stat.S_ISREG(before.st_mode) and before.st_size == expected_bytes, 'IMAGE_SIZE_DRIFT')
        while count < expected_bytes:
            cancel(); block = stream.read(min(CHUNK, expected_bytes-count))
            require(bool(block), 'IMAGE_TRUNCATED'); count += len(block); h.update(block)
        require(not stream.read(1) and _identity(before) == _identity(os.fstat(stream.fileno())),
                'IMAGE_MUTATION')
    return h.hexdigest()

def signature_status(exit_code, text, fingerprint):
    """Exact single primary signing-key profile; subkey policies need review."""
    require(type(fingerprint) is str and len(fingerprint) in (40, 64) and
            all(c in '0123456789ABCDEF' for c in fingerprint), 'TRUSTED_SIGNER_ABSENT')
    require(type(text) is str and len(text.encode('ascii', errors='replace')) <= CHUNK,
            'SIGNATURE_STATUS_SIZE')
    require(text.isascii() and exit_code == 0, 'SIGNATURE_TOOL_FAILURE')
    valid = []
    allowed = {'NEWSIG', 'KEY_CONSIDERED', 'SIG_ID', 'GOODSIG', 'VALIDSIG', 'TRUST_UNDEFINED',
               'TRUST_NEVER', 'TRUST_MARGINAL', 'TRUST_FULLY', 'TRUST_ULTIMATE'}
    for line in text.splitlines():
        require(line.startswith('[GNUPG:] '), 'SIGNATURE_STATUS_PROTOCOL')
        parts = line[9:].split()
        require(bool(parts) and parts[0] in allowed, 'SIGNATURE_BAD_OR_UNSUPPORTED_STATUS')
        if parts[0] == 'VALIDSIG':
            require(len(parts) in (10, 11), 'SIGNATURE_VALID_RECORD')
            valid.append(parts[1])
            if len(parts) == 11:
                require(parts[10] == fingerprint, 'SIGNATURE_PRIMARY_DRIFT')
    require(valid == [fingerprint], 'SIGNATURE_SIGNER_OR_MULTIPLICITY')

def _name(raw):
    require(b'\0' not in raw.rstrip(b'\0'), 'TAR_NAME_ENCODING')
    try:
        name = raw.rstrip(b'\0').decode('ascii')
    except UnicodeDecodeError as failure:
        raise Pending('TAR_NAME_ENCODING') from failure
    path = PurePosixPath(name)
    require(name and not name.startswith('/') and '\\' not in name and
            all(p not in ('', '.', '..') for p in name.rstrip('/').split('/')) and
            str(path) == name.rstrip('/'), 'TAR_PATH')
    return name.rstrip('/')

def _octal(raw):
    value = raw.rstrip(b'\0 ').lstrip(b' ')
    require(bool(value) and all(c in b'01234567' for c in value), 'TAR_OCTAL')
    return int(value, 8)

def _extract(archive, directory, expected, cancel):
    members = expected['members']
    require(type(members) is dict and bool(members), 'MEMBER_EXPECTATIONS_ABSENT')
    require(type(expected['tar_bytes']) is int and expected['tar_bytes'] >= 1024 and
            expected['tar_bytes'] % 512 == 0, 'TAR_BOUND')
    seen = set(); expanded = 0
    with archive.open('rb') as original:
        stream = gzip.GzipFile(fileobj=original) if expected.get('compression') == 'gzip' else original
        require(expected.get('compression', 'none') in ('none', 'gzip'), 'ARCHIVE_COMPRESSION')
        def read(size):
            nonlocal expanded
            require(0 <= size <= CHUNK and expanded+size <= expected['tar_bytes'], 'TAR_EXPANSION_BOUND')
            cancel(); data = stream.read(size); expanded += len(data)
            require(len(data) == size, 'TAR_TRUNCATED'); return data
        while True:
            header = read(512)
            if not any(header):
                require(not any(read(512)), 'TAR_END_MARKER')
                while expanded < expected['tar_bytes']:
                    require(not any(read(min(CHUNK, expected['tar_bytes']-expanded))), 'TAR_TRAILING_DATA')
                require(not stream.read(1), 'TAR_EXPANSION_BOUND')
                break
            require(len(seen) < len(members), 'TAR_MEMBER_BOUND')
            require(header[257:263] == b'ustar\0' and header[263:265] == b'00', 'TAR_FORMAT_UNSUPPORTED')
            require(_octal(header[148:156]) == sum(header[:148])+8*32+sum(header[156:]), 'TAR_CHECKSUM')
            name = _name(header[:100]); prefix = header[345:500].rstrip(b'\0')
            if prefix:
                name = _name(prefix+b'/'+name.encode('ascii'))
            require(name not in seen and name in members, 'TAR_DUPLICATE_OR_EXTRA')
            record = members[name]; kind = header[156:157]
            require(kind in (b'0', b'\0', b'5') and not any(header[157:257]), 'TAR_LINK_OR_SPECIAL')
            size = _octal(header[124:136]); mode = _octal(header[100:108])
            require(type(record['bytes']) is int and size == record['bytes'] and mode == record['mode']
                    and not mode & ~0o777 and bool(record.get('directory', False)) == (kind == b'5'),
                    'TAR_DECLARED_MEMBER_DRIFT')
            require(expanded+((size+511)//512)*512 <= expected['tar_bytes']-1024, 'TAR_DECLARED_EXPANSION')
            target = directory.joinpath(*name.split('/'))
            target.parent.mkdir(parents=True, exist_ok=True)
            require(not any(p.is_symlink() for p in (target, *target.parents)), 'TAR_PARENT_LINK')
            seen.add(name)
            if kind == b'5':
                require(size == 0, 'TAR_DIRECTORY_BODY'); target.mkdir(exist_ok=True)
            else:
                h = hashlib.sha256(); remaining = size
                with target.open('xb') as output:
                    while remaining:
                        block = read(min(CHUNK, remaining)); output.write(block); h.update(block)
                        remaining -= len(block)
                require(h.hexdigest() == record['sha256'], 'TAR_MEMBER_DIGEST')
                if size % 512:
                    require(not any(read(512-size % 512)), 'TAR_NONZERO_PADDING')
            os.chmod(target, mode)
        require(seen == set(members), 'TAR_MISSING_MEMBER')
        if stream is not original:
            stream.close()

def _remove(stage):
    # The owned tool has terminated; concurrent replacement of this private
    # stage is outside the supported single-threaded qualification profile.
    # Never follow even a rejected/broken directory link while opening/chmodding.
    try:
        info = stage.lstat()
    except FileNotFoundError:
        return
    if not stat.S_ISDIR(info.st_mode):
        stage.unlink(); return
    os.chmod(stage, 0o700, follow_symlinks=False)
    with os.scandir(stage) as entries:
        for entry in entries:
            path = Path(entry.path)
            if stat.S_ISDIR(entry.stat(follow_symlinks=False).st_mode):
                _remove(path)
            else:
                path.unlink()
    stage.rmdir()

class _UnitReceipt:
    __slots__ = ('directory', 'stage', 'archive_sha256', 'qualified', 'closed')
    def __init__(self, stage, digest):
        self.stage = stage; self.directory = stage/'image'; self.archive_sha256 = digest
        self.qualified = False; self.closed = False
    def require_host(self):
        require(not self.closed, 'BOOT_RECEIPT_CLOSED')
        raise Pending('UNIT_BOOT_DATA_NOT_HOST_AUTHORITY')
    def close(self):
        if not self.closed:
            self.closed = True; _remove(self.stage)

def verify_unit(archive, signature, expected, parent, signature_runner, cancel=lambda: None):
    """Real reader with injected external signer ONLY; output is always UNIT."""
    require(bool(expected.get('source')) and bool(expected.get('build')), 'SOURCE_BUILD_PROOF_ABSENT')
    require(bool(expected.get('signer')), 'TRUSTED_SIGNER_ABSENT')
    cancel(); stage = Path(tempfile.mkdtemp(prefix='boot-verify-', dir=parent))
    try:
        opened = stage/'archive'; sig = stage/'signature'
        _copy_opened(archive, opened, expected['archive_bytes'], expected['archive_sha256'], cancel)
        # Signature is also snapshotted, with an explicit independently bounded size.
        size = expected.get('signature_bytes')
        require(type(size) is int and 0 < size <= CHUNK, 'SIGNATURE_SIZE_EXPECTATION')
        signature_digest = expected.get('signature_sha256')
        require(bool(signature_digest), 'SIGNATURE_EXPECTATION_ABSENT')
        _copy_opened(signature, sig, size, signature_digest, cancel)
        cancel(); code, output = signature_runner(opened, sig, expected['signer'])
        signature_status(code, output, expected['signer']); cancel()
        require(file_digest(opened, expected['archive_bytes'], cancel) == expected['archive_sha256'],
                'SIGNED_ARCHIVE_MUTATION')
        require(file_digest(sig, size, cancel) == signature_digest, 'SIGNED_SIGNATURE_MUTATION')
        directory = stage/'image'; directory.mkdir()
        _extract(opened, directory, expected, cancel)
        for path in directory.rglob('*'):
            if path.is_file():
                os.chmod(path, 0o555 if path.stat().st_mode & 0o111 else 0o444)
        for path in sorted(directory.rglob('*'), reverse=True):
            if path.is_dir():
                os.chmod(path, 0o555)
        os.chmod(directory, 0o555); cancel()
        return _UnitReceipt(stage, expected['archive_sha256'])
    except BaseException:
        try:
            _remove(stage)
        except BaseException:
            pass  # preserve the actual primary, no addSuppressed/error allocation
        raise

def _owned_tool(command, environment, stage, timeout, cancel):
    """Trusted offline helper only: fixed argv/env, owned files/PID/deadline.

    The tool's reviewed output/storage bound is a separate mandatory premise;
    observed post-run file caps are drift detection, not an adversarial sandbox.
    """
    require(type(timeout) in (int, float) and 0 < timeout <= 300, 'TOOL_DEADLINE_PROFILE')
    cancel(); deadline = monotonic()+timeout; child = None; primary = None
    with (stage/'stdout').open('xb') as out, (stage/'stderr').open('xb') as err:
        try:
            child = subprocess.Popen(command, env=environment, stdin=subprocess.DEVNULL,
                                     stdout=out, stderr=err, start_new_session=True)
            while True:
                cancel(); remaining = deadline-monotonic()
                require(remaining > 0, 'OFFLINE_TOOL_DEADLINE')
                try:
                    code = child.wait(timeout=min(0.05, remaining)); break
                except subprocess.TimeoutExpired:
                    pass
            cancel(); require(monotonic() <= deadline, 'OFFLINE_TOOL_DEADLINE')
        except BaseException as failure:
            primary = failure; raise
        finally:
            try:
                if child is not None and child.poll() is None:
                    try:
                        os.killpg(child.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    child.wait()
            except BaseException:
                if primary is None:
                    raise
    output = []
    for name in ('stdout', 'stderr'):
        path = stage/name; require(path.stat().st_size <= CHUNK, 'OFFLINE_TOOL_OUTPUT_BOUND')
        output.append(path.read_bytes())
    return code, output[0], output[1]

def _tool_files(profile, cancel):
    """Explicit executable AND required runtime/library/image dependencies."""
    require(type(profile.get('files')) is list and bool(profile['files']), 'TOOL_DEPENDENCIES_ABSENT')
    names = set()
    for item in profile['files']:
        path = str(Path(item['path']).absolute())
        require(path not in names and path == item['path'], 'TOOL_DEPENDENCY_PATH')
        names.add(path)
        require(file_digest(path, item['bytes'], cancel) == item['sha256'], 'TOOL_DEPENDENCY_DRIFT')
    require(profile['executable'] in names, 'TOOL_EXECUTABLE_NOT_BOUND')

def _real_signature(profile, archive, signature, fingerprint, stage, cancel):
    require(profile.get('protocol') == 'GPGV_STATUS_FD1_PRIMARY_1', 'SIGNATURE_PROTOCOL_UNREVIEWED')
    _tool_files(profile, cancel)
    require(profile['keyring'] in {i['path'] for i in profile['files']}, 'TRUSTED_KEYRING_UNBOUND')
    code, out, err = _owned_tool((profile['executable'], '--status-fd', '1', '--keyring',
                                 profile['keyring'], str(signature), str(archive)),
                                {'LANG': 'C', 'LC_ALL': 'C'}, stage, profile['timeout'], cancel)
    try:
        text = out.decode('ascii')
    except UnicodeDecodeError as failure:
        raise Pending('SIGNATURE_STATUS_PROTOCOL') from failure
    signature_status(code, text, fingerprint)
    return code, text

def project_classes(modules, expected, tool, parent, cancel=lambda: None):
    """Actual jimage extraction, never a JMOD surrogate or injected projection.

    The explicit input digest, tool closure and semantic expectations are all
    checked. Caller-provided profiles here prove DATA mechanics only. Production
    calls use the independently installed record and retain the image lifetime.
    One source-reviewed exact include per expected binary name; no alternate
    source/automatic classpath. Output is owned and removed on every outcome.
    """
    require(tool.get('protocol') == 'JIMAGE_EXTRACT_EXACT_1', 'MODULE_CLASS_PROJECTION_UNSEALED')
    require(bool(expected.get('classes')), 'PROJECTED_CLASS_EXPECTATIONS_ABSENT')
    for name, record in expected['classes'].items():
        require(_name(name.encode('ascii')) == name and name.endswith('.class') and
                all(c.isalnum() or c in '/_$.\u002d' for c in name) and name.count('/') >= 2,
                'PROJECTED_BINARY_NAME')
        require(type(record['bytes']) is int and record['bytes'] > 0, 'PROJECTED_CLASS_BOUND')
    require(file_digest(modules, expected['bytes'], cancel) == expected['sha256'], 'MODULE_IMAGE_DRIFT')
    _tool_files(tool, cancel)
    stage = Path(tempfile.mkdtemp(prefix='boot-project-', dir=parent))
    primary = None
    try:
        result = {}; command_receipts = []
        for index, (name, record) in enumerate(expected['classes'].items()):
            cancel(); invocation = stage/str(index); invocation.mkdir(); output = invocation/'classes'; output.mkdir()
            command = (tool['executable'], 'extract', '--include', 'glob:/'+name,
                       '--dir', str(output), str(Path(modules).absolute()))
            code, out, err = _owned_tool(command, {'LANG': 'C', 'LC_ALL': 'C'}, invocation,
                                         tool['timeout'], cancel)
            require(code == 0, 'PROJECTION_TOOL_FAILURE')
            files = []
            for path in output.rglob('*'):
                require(not path.is_symlink(), 'PROJECTED_LINK')
                if path.is_file():
                    files.append(path.relative_to(output).as_posix())
                else:
                    require(path.is_dir(), 'PROJECTED_SPECIAL')
            require(files == [name], 'PROJECTED_MISSING_EXTRA_OR_DUPLICATE')
            path = output/name
            require(path.stat().st_size == record['bytes'], 'PROJECTED_CLASS_SIZE')
            data = path.read_bytes(); actual = normalize_class(data)
            binary_name = name.split('/', 1)[1][:-6].encode('ascii').hex()
            require(actual['name'] == (7, ('utf', binary_name)), 'PROJECTED_BINARY_NAME_DRIFT')
            require(actual == record['normalized'], 'PROJECTED_BODY_DESCRIPTOR_HANDLER_HIERARCHY_DRIFT')
            # Compare digest too only as identity, never source-conformance authority.
            require(hashlib.sha256(data).hexdigest() == record['sha256'], 'PROJECTED_CLASS_BYTES_DRIFT')
            result[name] = actual
            command_receipts.append({'argv': command, 'stdout_sha256': hashlib.sha256(out).hexdigest(),
                                     'stderr_sha256': hashlib.sha256(err).hexdigest()})
        # Recheck same opened image/tool names under the immutable HOST premise.
        require(file_digest(modules, expected['bytes'], cancel) == expected['sha256'], 'MODULE_IMAGE_DRIFT')
        _tool_files(tool, cancel); cancel()
        return {'scope': 'DATA', 'qualified': False, 'image_sha256': expected['sha256'],
                'classes': result, 'commands': command_receipts}
    except BaseException as failure:
        primary = failure; raise
    finally:
        try:
            _remove(stage)
        except BaseException:
            if primary is None:
                raise

def _protected(path):
    for item in (path, *path.parents):
        info = item.lstat()
        require(not stat.S_ISLNK(info.st_mode) and info.st_uid == 0 and not info.st_mode & 0o022,
                'INDEPENDENT_RECORD_UNPROTECTED')

def source_preparation(record, cancel=lambda: None):
    """Join actual six opened source streams to accepted immutable supplier pins.

    This checks the previously reviewed source family. Independent build/patch
    and binary/source proof identifiers are still required by the installed
    record; the source comparison cannot invent those proofs.
    """
    require(record.get('source_commit') == SOURCE_COMMIT and record.get('build_commit') == BUILD_COMMIT
            and record.get('source_ref') == SOURCE_REF, 'SUPPLIER_SOURCE_BUILD_REF_DRIFT')
    files = record.get('source_files')
    require(type(files) is dict and set(files) == set(LOADER_SOURCES), 'LOADER_SOURCE_CLOSURE')
    for name, (size, digest) in LOADER_SOURCES.items():
        parts = files[name]; require(type(parts) is list and bool(parts), 'LOADER_SOURCE_PARTS')
        h = hashlib.sha256(); total = 0
        for part in parts:
            require(type(part['bytes']) is int and 0 < part['bytes'] <= size-total, 'LOADER_SOURCE_BOUND')
            with os.fdopen(os.open(part['path'], os.O_RDONLY | os.O_NOFOLLOW), 'rb') as stream:
                before = os.fstat(stream.fileno())
                require(stat.S_ISREG(before.st_mode) and before.st_size == part['bytes'], 'LOADER_SOURCE_SIZE')
                remaining = before.st_size
                while remaining:
                    cancel(); block = stream.read(min(CHUNK, remaining)); require(bool(block), 'LOADER_SOURCE_TRUNCATED')
                    h.update(block); total += len(block); remaining -= len(block)
                require(not stream.read(1) and _identity(before) == _identity(os.fstat(stream.fileno())), 'LOADER_SOURCE_MUTATION')
        require(total == size and h.hexdigest() == digest, 'LOADER_SOURCE_BYTES_DRIFT')
    return {'source_commit': SOURCE_COMMIT, 'build_commit': BUILD_COMMIT, 'source_files': 6}

class _RegisteredReceipt:
    __slots__ = ('unit', 'record', 'key', 'origin', 'closed')
    def __init__(self, unit, record, key, origin):
        require(origin is _ISSUED, 'BOOT_ISSUER_ABSENT')
        self.unit = unit; self.record = record; self.key = key; self.origin = origin; self.closed = False
    def close(self):
        if not self.closed:
            self.closed = True; self.unit.close()

def _finish_verified_image(unit, signer_stage, publish):
    """Shared post-verification ownership tail; UNIT tests issue no authority."""
    primary = None; transferred = False
    try:
        _remove(signer_stage)
        result = publish(unit); transferred = True; return result
    except BaseException as failure:
        primary = failure; raise
    finally:
        try:
            if not transferred:
                unit.close()
        except BaseException:
            if primary is None:
                raise

def require_borrow_live(receipt, expected_key):
    """Cheap SAME-object guard; no copied identity or repeated image hashing.

    This profile is synchronous, single-threaded callback use. Concurrent receipt
    destruction is unsupported, not purportedly prevented by these checks.
    """
    require(type(receipt) is _RegisteredReceipt and receipt.origin is _ISSUED,
            'REGISTERED_BOOT_RECEIPT_REQUIRED')
    require(receipt.key == expected_key, 'BOOT_RECORD_ID_DRIFT')
    require(not receipt.closed and not receipt.unit.closed, 'BOOT_RECEIPT_CLOSED')

def consume_host(receipt, expected_key, runtime, tools):
    """Actual HOST consumer, including image/java/compiler/installation binding.

    This authenticates one offline boot component, not startup(A), helper launch,
    no-transform lifetime, capture Work, or a production initializer permit.
    """
    require_borrow_live(receipt, expected_key)
    record = receipt.record; root = receipt.unit.directory
    require(record['installed_runtime'] == runtime, 'INSTALLED_RUNTIME_RECORD_DRIFT')
    for name in ('java', 'javac', 'modules'):
        binding = record['installed_files'][name]; path = root/binding['member']
        selected = runtime.get('modules') if name == 'modules' else tools[name]['path']
        require(str(path) == selected and
                file_digest(path, binding['bytes']) == binding['sha256'], 'INSTALLED_BOOT_BYTES_DRIFT')
    require(runtime['java'] == tools['java']['path'], 'BOOT_TOOL_JOIN')
    return root

def verify_registered(key, archive, signature, parent, cancel=lambda: None):
    """Installed trusted record -> real signature/archive/image -> private receipt.

    No registry entry is supplied by callers or generated from observations.
    Expected semantic IR JSON is restored to tuple form explicitly at the seam.
    """
    require(type(key) is str and key in REGISTERED_BUNDLES, 'INDEPENDENT_BOOT_RECORD_ABSENT')
    _protected(Path(parent).absolute())
    path, size, digest = REGISTERED_BUNDLES[key]; path = Path(path).absolute(); _protected(path)
    require(file_digest(path, size, cancel) == digest, 'INDEPENDENT_BOOT_RECORD_DRIFT')
    data = path.read_bytes(); require(len(data) == size, 'INDEPENDENT_BOOT_RECORD_DRIFT')
    record = json.loads(data)
    require(record['schema'] == 'B_OFFLINE_BOOT_1' and record['proof_id'] == key and
            record.get('supplier_claim') and record.get('source_build_patch_proof') and
            record.get('binary_source_conformance_proof'), 'BOOT_SOURCE_PROOFS_ABSENT')
    source_preparation(record['source_chain'], cancel)
    signature_id = record['signature_profile']
    require(signature_id in REGISTERED_SIGNATURE_PROFILES, 'TRUSTED_SIGNATURE_TOOL_UNSEALED')
    require(record.get('projection'), 'MODULE_CLASS_PROJECTION_UNSEALED')
    signer_stage = Path(tempfile.mkdtemp(prefix='boot-sign-', dir=parent))
    unit = None; primary = None; owns_cleanup = True
    try:
        profile = REGISTERED_SIGNATURE_PROFILES[signature_id]
        unit = verify_unit(archive, signature, record['archive'], parent,
                           lambda a, s, f: _real_signature(profile, a, s, f, signer_stage, cancel), cancel)
        projection = record['projection']; image = unit.directory/projection['member']
        def tuples(value):
            if isinstance(value, list):
                return tuple(tuples(v) for v in value)
            if isinstance(value, dict):
                return {k: tuples(v) for k, v in value.items()}
            return value
        expected = projection['image']
        for value in expected['classes'].values():
            value['normalized'] = tuples(value['normalized'])
        project_classes(image, expected, projection['tool'], parent, cancel)
        cancel(); publisher = lambda owned: _RegisteredReceipt(owned, record, key, _ISSUED)
        owns_cleanup = False
        return _finish_verified_image(unit, signer_stage, publisher)
    except BaseException as failure:
        primary = failure; raise
    finally:
        try:
            if owns_cleanup and unit is not None:
                unit.close()
        except BaseException:
            if primary is None:
                raise
        finally:
            try:
                if owns_cleanup:
                    _remove(signer_stage)
            except BaseException:
                if primary is None:
                    raise

class _Reader:
    def __init__(self, data):
        self.data = data; self.pos = 0
    def take(self, n):
        require(0 <= n <= len(self.data)-self.pos, 'CLASS_TRUNCATED')
        value = self.data[self.pos:self.pos+n]; self.pos += n; return value
    def u1(self):
        return int.from_bytes(self.take(1), 'big')
    def u2(self):
        return int.from_bytes(self.take(2), 'big')
    def u4(self):
        return int.from_bytes(self.take(4), 'big')

def normalize_class(data):
    """Targeted Java17 method IR, CP-symbolic operands and ordered handlers.

    Debug/verification attributes are recorded separately, not proof of JVM
    verification or a source theorem. Unsupported CP/body attributes refuse.
    Expected IR must come from independent source/body review, never this output.
    """
    r = _Reader(data)
    require(r.u4() == 0xCAFEBABE and r.u2() == 0 and r.u2() == 61, 'CLASS_VERSION')
    count = r.u2(); require(count > 1, 'CLASS_CP_COUNT')
    # Each CP slot requires >=3 source bytes, including either half of a wide
    # constant. Reject impossible counts BEFORE constructing the CP vector.
    require(3*(count-1) <= len(data)-r.pos, 'CLASS_CP_BOUND'); cp = [None]*count
    i = 1
    while i < count:
        tag = r.u1()
        if tag == 1:
            cp[i] = ('utf', r.take(r.u2()))
        elif tag in (3, 4):
            cp[i] = (tag, r.take(4).hex())
        elif tag in (5, 6):
            cp[i] = (tag, r.take(8).hex()); i += 1
        elif tag in (7, 8, 16, 19, 20):
            cp[i] = (tag, r.u2())
        elif tag in (9, 10, 11, 12, 17, 18):
            cp[i] = (tag, r.u2(), r.u2())
        elif tag == 15:
            cp[i] = (tag, r.u1(), r.u2())
        else:
            raise Pending('CLASS_CP_UNSUPPORTED')
        i += 1
    def entry(index):
        require(0 < index < count and cp[index] is not None, 'CLASS_CP_INDEX'); return cp[index]
    # Validate the finite CP reference grammar before recursive normalization;
    # malformed DAGs cannot induce exponential expansion/deep arbitrary chains.
    for value in cp[1:]:
        if value is None:
            continue
        tag = value[0]
        if tag in (7, 8, 16, 19, 20):
            require(entry(value[1])[0] == 'utf', 'CLASS_CP_REFERENCE_KIND')
        elif tag in (9, 10, 11):
            require(entry(value[1])[0] == 7 and entry(value[2])[0] == 12, 'CLASS_CP_REFERENCE_KIND')
        elif tag == 12:
            require(entry(value[1])[0] == 'utf' and entry(value[2])[0] == 'utf', 'CLASS_CP_REFERENCE_KIND')
        elif tag == 15:
            require(1 <= value[1] <= 9 and entry(value[2])[0] in (9, 10, 11), 'CLASS_CP_REFERENCE_KIND')
        elif tag in (17, 18):
            require(entry(value[2])[0] == 12, 'CLASS_CP_REFERENCE_KIND')
    def text(index):
        value = entry(index); require(value[0] == 'utf', 'CLASS_UTF_KIND')
        try:
            return value[1].decode('ascii')
        except UnicodeDecodeError as failure:
            raise Pending('CLASS_NONASCII_IDENTIFIER') from failure
    def symbol(index, trail=()):
        require(index not in trail, 'CLASS_CP_CYCLE'); v = entry(index); tag = v[0]; t = trail+(index,)
        if tag == 'utf':
            return ('utf', v[1].hex())
        if tag in (3, 4, 5, 6):
            return v
        if tag in (7, 8, 16, 19, 20):
            return (tag, symbol(v[1], t))
        if tag in (9, 10, 11, 12):
            return (tag, symbol(v[1], t), symbol(v[2], t))
        if tag == 15:
            return (tag, v[1], symbol(v[2], t))
        if tag in (17, 18):
            return (tag, v[1], symbol(v[2], t))
        raise Pending('CLASS_CP_UNSUPPORTED')
    def attributes(reader):
        result = []
        for _ in range(reader.u2()):
            name = text(reader.u2()); result.append((name, reader.take(reader.u4())))
        return result
    access = r.u2(); name = symbol(r.u2()); super_index = r.u2()
    superclass = symbol(super_index) if super_index else None
    interfaces = tuple(symbol(r.u2()) for _ in range(r.u2()))
    fields = []
    for _ in range(r.u2()):
        flags, field, descriptor = r.u2(), text(r.u2()), text(r.u2())
        attrs = attributes(r); normalized = []
        for key, raw in attrs:
            if key == 'ConstantValue':
                require(len(raw) == 2, 'CLASS_CONSTANT_VALUE'); normalized.append((key, symbol(int.from_bytes(raw, 'big'))))
            elif key in ('Signature', 'Synthetic', 'Deprecated'):
                normalized.append((key, text(int.from_bytes(raw, 'big')) if key == 'Signature' else raw.hex()))
            else:
                raise Pending('CLASS_FIELD_ATTRIBUTE_UNSUPPORTED')
        fields.append((flags, field, descriptor, tuple(normalized)))
    methods = []
    for _ in range(r.u2()):
        flags, method, descriptor = r.u2(), text(r.u2()), text(r.u2()); body = None; exceptions = ()
        extras = []
        for key, raw in attributes(r):
            if key == 'Code':
                require(body is None, 'CLASS_DUPLICATE_CODE'); c = _Reader(raw)
                stack, locals_count, length = c.u2(), c.u2(), c.u4()
                require(0 < length <= 65535, 'CLASS_CODE_LENGTH')
                instructions, labels = _instructions(c.take(length), symbol)
                handlers = []
                for _ in range(c.u2()):
                    start, end, handler, catch = c.u2(), c.u2(), c.u2(), c.u2()
                    require(start < end and start in labels and end in labels and handler in labels and
                            handler < length, 'CLASS_HANDLER_TARGET')
                    handlers.append((labels[start], labels[end], labels[handler], symbol(catch) if catch else None))
                for attr, value in attributes(c):
                    require(attr in ('LineNumberTable', 'LocalVariableTable', 'LocalVariableTypeTable', 'StackMapTable'),
                            'CLASS_CODE_ATTRIBUTE_UNSUPPORTED')
                require(c.pos == len(raw), 'CLASS_CODE_TRAILING')
                body = (stack, locals_count, instructions, tuple(handlers))
            elif key == 'Exceptions':
                e = _Reader(raw); exceptions = tuple(symbol(e.u2()) for _ in range(e.u2()))
                require(e.pos == len(raw), 'CLASS_EXCEPTIONS_TRAILING')
            elif key in ('Signature', 'Synthetic', 'Deprecated'):
                extras.append((key, text(int.from_bytes(raw, 'big')) if key == 'Signature' else raw.hex()))
            else:
                raise Pending('CLASS_METHOD_ATTRIBUTE_UNSUPPORTED')
        methods.append((flags, method, descriptor, body, exceptions, tuple(extras)))
    class_attrs = []
    for key, raw in attributes(r):
        if key in ('SourceFile', 'SourceDebugExtension'):
            continue
        a = _Reader(raw)
        if key == 'BootstrapMethods':
            values = tuple((symbol(a.u2()), tuple(symbol(a.u2()) for _ in range(a.u2()))) for _ in range(a.u2()))
        elif key in ('NestMembers', 'PermittedSubclasses'):
            values = tuple(symbol(a.u2()) for _ in range(a.u2()))
        elif key in ('NestHost', 'Signature'):
            values = text(a.u2()) if key == 'Signature' else symbol(a.u2())
        elif key == 'InnerClasses':
            values = tuple(tuple(symbol(v) if v and j < 2 else text(v) if v and j == 2 else v
                                 for j, v in enumerate((a.u2(), a.u2(), a.u2(), a.u2()))) for _ in range(a.u2()))
        else:
            raise Pending('CLASS_ATTRIBUTE_UNSUPPORTED')
        require(a.pos == len(raw), 'CLASS_ATTRIBUTE_TRAILING'); class_attrs.append((key, values))
    require(r.pos == len(data), 'CLASS_TRAILING')
    return {'access': access, 'name': name, 'super': superclass, 'interfaces': interfaces,
            'fields': tuple(fields), 'methods': tuple(methods), 'attributes': tuple(class_attrs)}

def _instructions(code, symbol):
    r = _Reader(code); rows = []; labels = {}
    cp2 = set(range(178, 185)) | {187, 189, 192, 193}
    one = set(range(21, 26)) | set(range(54, 59)) | {16, 169, 188}
    branch2 = set(range(153, 169)) | {198, 199}
    while r.pos < len(code):
        pc = r.pos; labels[pc] = len(rows); op = r.u1(); args = ()
        require(op <= 201, 'CLASS_OPCODE_UNSUPPORTED')
        if op in cp2:
            args = (symbol(r.u2()),)
        elif op in (18, 19, 20):
            args = (symbol(r.u1() if op == 18 else r.u2()),); op = 18 if op != 20 else 20
        elif op in one:
            args = (r.u1(),)
        elif op == 17:
            args = (int.from_bytes(r.take(2), 'big', signed=True),)
        elif op == 132:
            args = (r.u1(), int.from_bytes(r.take(1), 'big', signed=True))
        elif op in branch2 or op in (200, 201):
            args = ('branch', pc+int.from_bytes(r.take(2 if op in branch2 else 4), 'big', signed=True))
        elif op in (185, 186):
            args = (symbol(r.u2()), r.u1(), r.u1())
            require(args[-1] == 0 and (op == 185 or args[-2] == 0), 'CLASS_INVOKE_RESERVED')
        elif op == 197:
            args = (symbol(r.u2()), r.u1())
        elif op == 196:
            sub = r.u1(); require(sub in one-{16, 188} or sub == 132, 'CLASS_WIDE_OPCODE')
            args = (sub, r.u2())
            if sub == 132:
                args += (int.from_bytes(r.take(2), 'big', signed=True),)
        elif op in (170, 171):
            require(not any(r.take((-r.pos) % 4)), 'CLASS_SWITCH_PADDING')
            default = pc+int.from_bytes(r.take(4), 'big', signed=True)
            a = int.from_bytes(r.take(4), 'big', signed=True)
            if op == 170:
                b = int.from_bytes(r.take(4), 'big', signed=True)
                n = b-a+1; require(0 <= n <= (len(code)-r.pos)//4, 'CLASS_SWITCH_SIZE')
                pairs = tuple((a+i, pc+int.from_bytes(r.take(4), 'big', signed=True)) for i in range(n))
            else:
                require(0 <= a <= (len(code)-r.pos)//8, 'CLASS_SWITCH_SIZE')
                pairs = tuple((int.from_bytes(r.take(4), 'big', signed=True),
                               pc+int.from_bytes(r.take(4), 'big', signed=True)) for _ in range(a))
            args = ('switch', default, pairs)
        rows.append((op, args))
    labels[len(code)] = len(rows)
    result = []
    def target(offset):
        require(offset in labels and offset < len(code), 'CLASS_BRANCH_TARGET'); return labels[offset]
    for op, args in rows:
        if args and args[0] == 'branch':
            args = ('branch', target(args[1]))
        elif args and args[0] == 'switch':
            args = ('switch', target(args[1]), tuple((k, target(v)) for k, v in args[2]))
        result.append((op, args))
    return tuple(result), labels
