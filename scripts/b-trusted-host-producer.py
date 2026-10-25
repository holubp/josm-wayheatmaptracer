#!/usr/bin/env python3
"""One protected Linux HOST transaction; no privileged operation runs on import.

The empty installation registry is intentional: review must bind actual source,
tools, boot/load/startup and launch-helper bytes before a real attempt is possible.
Simulation exercises the same transaction but cannot create a live HOST receipt.
Neither receipt is a capture/runtime qualification or a production init permit.
"""
from pathlib import Path
import hashlib
import grp
import json
import math
import os
import pwd
import select
import shutil
import stat
import struct
import subprocess
import sys
import tempfile
from time import monotonic, monotonic_ns

DEPENDENCY_SHA256='a8c8aa239aa878d9901a59731d33a66c44f403ec14dc686cd8ff5a2dc3693951'
# Installation-time independently reviewed source->build->output/profile tuple.
# A caller cannot supply an expected hash, boolean, manifest or simulated record.
REGISTERED_TUPLES={}
REGISTERED_BOOT_LOAD_STARTUP={}
BOOT_VERIFIER_SHA256='16cfafddfec78c09a61551c66920df8663324ac9fc1afc5da63ed20159828c67'
_BOOT_VERIFIER=None
GNU_COMPONENT_SHA256='fc6900672f2680f30624a40d85166a809ceea625fc49a3efd253d1b00be2a4ea'
_GNU_COMPONENT=None
MAX_TUPLE_BYTES=65536
READY_PREFIX=b'B_HOST_READY_1 '
MAX_READY_BYTES=len(READY_PREFIX)+19+1
GNU_DEADLINE_PROTOCOL='BGNUD001_ABSOLUTE_MONOTONIC_NS_1'
GNU_CLOCK_PROFILE='LINUX_CPYTHON_JDK_CLOCK_MONOTONIC_NS_1'

class Pending(RuntimeError):
    """An exact necessary HOST/boot/source premise is absent or mismatched."""

def require(ok,reason):
    if not ok:raise Pending(reason)

def _dependency():
    path=Path(__file__).with_name('b-shared-runtime-handoff.py')
    data=path.read_bytes()
    require(hashlib.sha256(data).hexdigest()==DEPENDENCY_SHA256,'REVIEWED_MECHANICS_SOURCE_DRIFT')
    namespace={'__name__':'reviewed_handoff','__file__':str(path)}
    exec(compile(data,str(path),'exec'),namespace)
    return namespace

def _boot_verifier():
    global _BOOT_VERIFIER
    if _BOOT_VERIFIER is None:
        path=Path(__file__).with_name('b-boot-bundle-verifier.py')
        data=path.read_bytes()
        require(hashlib.sha256(data).hexdigest()==BOOT_VERIFIER_SHA256,'BOOT_VERIFIER_SOURCE_DRIFT')
        namespace={'__name__':'reviewed_boot_verifier','__file__':str(path)}
        exec(compile(data,str(path),'exec'),namespace)
        _BOOT_VERIFIER=namespace
    return _BOOT_VERIFIER

def validate_boot_receipt(receipt,review):
    # A UNIT/simulated dict or offline observation never becomes HOST authority.
    # The receipt's producer and consumer share this exact source namespace.
    verifier=_boot_verifier()
    return verifier['consume_host'](receipt,review.data['boot_bundle'],
                                    review.data['runtime'],review.data['tools'])

def verify_boot_bundle(key,archive,signature,parent,cancel=lambda:None):
    """Actual offline producer; absent independent installed record refuses."""
    return _boot_verifier()['verify_registered'](key,archive,signature,parent,cancel)

def _gnu_component():
    """One cached issuer/consumer namespace, separate from strict boot receipts."""
    global _GNU_COMPONENT
    if _GNU_COMPONENT is None:
        path=Path(__file__).with_name('b-reached-method-component.py');data=path.read_bytes()
        require(len(data)<=65536 and hashlib.sha256(data).hexdigest()==GNU_COMPONENT_SHA256,'GNU_COMPONENT_SOURCE_DRIFT')
        namespace={'__name__':'host_gnu_component','__file__':str(path)}
        exec(compile(data,str(path),'exec'),namespace);_GNU_COMPONENT=namespace
    return _GNU_COMPONENT

def verify_gnu_boot_component(key,archive,signature,parent,cancel=lambda:None):
    """Issue the registered GNU method component through the cached namespace.

    Args:
        key: Independently installed component-record identifier.
        archive: Fixed signed supplier archive path.
        signature: Matching detached signature path.
        parent: Supported protected parent for the owned image.
        cancel: Original shared checkpoint.

    Returns:
        A borrowed-lifetime component receipt, with no HOST/runtime authority.

    Raises:
        RuntimeError: If a record, signature, source or lifetime premise fails.
    """
    return _gnu_component()['verify_gnu_registered'](key,archive,signature,parent,cancel)

def _regular_bytes(path,maximum=MAX_TUPLE_BYTES):
    with os.fdopen(os.open(path,os.O_RDONLY|os.O_NOFOLLOW),'rb') as stream:
        a=os.fstat(stream.fileno())
        require(stat.S_ISREG(a.st_mode) and 0<a.st_size<=maximum,'OPENED_FILE_PROFILE')
        data=stream.read(maximum+1);b=os.fstat(stream.fileno())
        require((a.st_dev,a.st_ino,a.st_size,a.st_mtime_ns)==
                (b.st_dev,b.st_ino,b.st_size,b.st_mtime_ns) and len(data)==a.st_size,'OPENED_FILE_DRIFT')
    return data

def _digest(path):
    digest=hashlib.sha256()
    with os.fdopen(os.open(path,os.O_RDONLY|os.O_NOFOLLOW),'rb') as stream:
        before=os.fstat(stream.fileno())
        require(stat.S_ISREG(before.st_mode) and 0<before.st_size<=64*1024*1024,'TOOL_SIZE_PROFILE')
        consumed=0
        while True:
            block=stream.read(65536)
            if not block:break
            consumed+=len(block);require(consumed<=before.st_size,'TOOL_CHANGED')
            digest.update(block)
        after=os.fstat(stream.fileno())
        require((before.st_dev,before.st_ino,before.st_size,before.st_mtime_ns)==
                (after.st_dev,after.st_ino,after.st_size,after.st_mtime_ns) and
                consumed==before.st_size,'TOOL_CHANGED')
    return digest.hexdigest()

class _Review:
    __slots__=('data',)
    def __init__(self,data):self.data=data

def _review(key):
    require(type(key) is str and key in REGISTERED_TUPLES,'INDEPENDENT_BUILD_TUPLE_ABSENT')
    # The installed path+digest is reviewed independently of the built artifact.
    path,digest=REGISTERED_TUPLES[key]
    _protected_ancestors(Path(path).absolute())
    data=_regular_bytes(path)
    require(hashlib.sha256(data).hexdigest()==digest,'INDEPENDENT_BUILD_TUPLE_DRIFT')
    value=json.loads(data)
    require(value['schema']=='B_SYSTEM_CLOSED_DIRECTORY_1','TUPLE_SCHEMA')
    proof=value['boot_load_startup']
    require(proof in REGISTERED_BOOT_LOAD_STARTUP,'BOOT_LOAD_STARTUP_UNREGISTERED')
    # This registry entry must bind exact JVM image/reached methods, namespace
    # absence, startup(A) payload/failure bodies and trusted helper enforcement.
    require(REGISTERED_BOOT_LOAD_STARTUP[proof]==value['runtime'],'BOOT_LOAD_STARTUP_DRIFT')
    return _Review(value)

def _review_gnu(key):
    """Select a distinct protected tuple; no observed receipt installs one."""
    require(type(key) is str and key in REGISTERED_TUPLES,'INDEPENDENT_BUILD_TUPLE_ABSENT')
    path,digest=REGISTERED_TUPLES[key];_protected_ancestors(Path(path).absolute())
    data=_regular_bytes(path);require(hashlib.sha256(data).hexdigest()==digest,'INDEPENDENT_BUILD_TUPLE_DRIFT')
    c=_gnu_component();value=c['_json'](data)
    require(type(value) is dict and value.get('schema') in
            ('B_SYSTEM_CLOSED_DIRECTORY_GNU_1','B_SYSTEM_CLOSED_DIRECTORY_GNU_2'),'GNU_TUPLE_SCHEMA')
    attachment=c['_host_attachment'](value.get('boot_component'))
    proof=value.get('boot_load_startup')
    require(type(proof) is str and proof==attachment['native_load_startup_proof'] and
            proof in REGISTERED_BOOT_LOAD_STARTUP,'BOOT_LOAD_STARTUP_UNREGISTERED')
    require(REGISTERED_BOOT_LOAD_STARTUP[proof]=={'runtime':value.get('runtime'),'boot_component':attachment},
            'GNU_BOOT_STARTUP_ATTACHMENT_DRIFT')
    runtime=value.get('runtime');tools=value.get('tools');roles=attachment['installed_files']
    require(type(runtime) is dict and 'java' not in runtime and 'modules' not in runtime and
            runtime.get('java_member')==roles['java']['member'] and
            runtime.get('modules_member')==roles['modules']['member'],'GNU_RUNTIME_MEMBER_JOIN')
    if value['schema']=='B_SYSTEM_CLOSED_DIRECTORY_GNU_2':
        _require_gnu_deadline_profile(runtime)
    require(type(tools) is dict and set(tools)=={'java','javac','setpriv'},'GNU_TOOL_ROLES')
    for role in ('java','javac'):
        tool=tools[role]
        require(type(tool) is dict and set(tool)=={'member','sha256'} and
                tool=={'member':roles[role]['member'],'sha256':roles[role]['sha256']},'GNU_TOOL_MEMBER_JOIN')
    require(type(tools['setpriv']) is dict and set(tools['setpriv'])=={'path','sha256'} and
            type(tools['setpriv']['path']) is str and Path(tools['setpriv']['path']).is_absolute() and
            c['_digest'](tools['setpriv']['sha256']),'GNU_EXTERNAL_HELPER_JOIN')
    files=runtime.get('external_files');absent=runtime.get('absent_paths')
    require(type(files) is list and 1<=len(files)<=64 and type(absent) is list and len(absent)<=64,
            'GNU_EXTERNAL_POLICY_REQUIRED')
    names=set()
    def path_name(name):
        require(type(name) is str and 0<len(name)<=4096 and '\0' not in name and
                Path(name).is_absolute() and '..' not in Path(name).parts,'GNU_EXTERNAL_PATH')
    for row in files:
        require(type(row) is dict and set(row)=={'path','bytes','sha256'},'GNU_EXTERNAL_RECORD')
        path_name(row['path'])
        require(row['path'] not in names and c['_integer'](row['bytes'],1,64<<20) and c['_digest'](row['sha256']),
                'GNU_EXTERNAL_RECORD');names.add(row['path'])
    require(any(row['path']==tools['setpriv']['path'] and row['sha256']==tools['setpriv']['sha256']
                for row in files),'GNU_EXTERNAL_HELPER_JOIN')
    for name in absent:
        path_name(name);require(name not in names,'GNU_EXTERNAL_RECORD');names.add(name)
    value['boot_component']=attachment
    return _Review(value)

def _gnu_launch_selection(review,borrow):
    """Derive fresh paths privately; never mutate the independently installed tuple."""
    data=dict(review.data);runtime=dict(data['runtime']);tools={k:dict(v) for k,v in data['tools'].items()}
    runtime.pop('java_member');runtime.pop('modules_member')
    runtime['java']=str(borrow.path('java'));runtime['modules']=str(borrow.path('modules'))
    for role in ('java','javac'):
        tools[role].pop('member');tools[role]['path']=str(borrow.path(role))
    data['runtime']=runtime;data['tools']=tools
    return _Review(data)

def _gnu_external_digest(path,checkpoint):
    """GNU-only bounded reads; retain the original guard and primary failure.

    The caller guards the borrowed image, selected file and ancestors around
    every callback. This helper owns only its opened descriptor/stream, never
    the selected file or image. No new timeout or strict-helper change occurs.
    """
    checkpoint();digest=hashlib.sha256();checkpoint()
    descriptor=None;stream=None;primary=None
    try:
        checkpoint();descriptor=os.open(path,os.O_RDONLY|os.O_NOFOLLOW);checkpoint()
        stream=os.fdopen(descriptor,'rb');descriptor=None;checkpoint()
        before=os.fstat(stream.fileno());checkpoint()
        require(stat.S_ISREG(before.st_mode) and 0<before.st_size<=64*1024*1024,'TOOL_SIZE_PROFILE')
        consumed=0
        while True:
            checkpoint();block=stream.read(65536);checkpoint()
            require(len(block)<=65536,'GNU_EXTERNAL_READ_LIMIT')
            if not block:break
            consumed+=len(block);require(consumed<=before.st_size,'TOOL_CHANGED')
            digest.update(block);checkpoint()
        after=os.fstat(stream.fileno());checkpoint()
        require(_GnuExternalBorrow._file_identity(before)==_GnuExternalBorrow._file_identity(after)
                and consumed==before.st_size,'TOOL_CHANGED')
        result=digest.hexdigest();checkpoint()
    except BaseException as failure:
        primary=failure;raise
    finally:
        try:
            if stream is not None:stream.close()
            elif descriptor is not None:os.close(descriptor)
        except BaseException:
            if primary is None:raise
    checkpoint() #No successful result can escape a close-side callback drift.
    return result

class _GnuExternalBorrow:
    """Finite registered external-file/absence lifetimes, separate from the image.

    Initial content checks bind expected supplier files. Later checks compare
    complete file identities and protected ancestor identities around callbacks;
    they do not repeatedly hash OS libraries or claim native semantic closure.
    """
    __slots__=('files','absent','ancestors')
    @staticmethod
    def _file_identity(info):
        return (info.st_dev,info.st_ino,info.st_mode,info.st_uid,info.st_gid,
                info.st_size,info.st_mtime_ns,info.st_ctime_ns)
    @staticmethod
    def _parent_identity(info):
        return (info.st_dev,info.st_ino,info.st_mode,info.st_uid,info.st_gid)
    def __init__(self,files,absent,checkpoint):
        checkpoint()
        self.files=[];self.absent=tuple(Path(v) for v in absent);self.ancestors=();ancestors={}
        def guarded():
            self.require_live();checkpoint();self.require_live()
        for path in self.absent:
            _protected_ancestors(path.parent)
            for parent in path.parents:ancestors[parent]=self._parent_identity(parent.lstat())
        self.ancestors=tuple(ancestors.items());guarded()
        for row in files:
            guarded()
            path=Path(row['path']);_protected_ancestors(path)
            before=path.lstat();require(stat.S_ISREG(before.st_mode) and before.st_size==row['bytes'],
                                        'GNU_EXTERNAL_FILE_DRIFT')
            self.files.append((path,self._file_identity(before)))
            for parent in path.parents:ancestors[parent]=self._parent_identity(parent.lstat())
            self.ancestors=tuple(ancestors.items())
            require(_gnu_external_digest(path,guarded)==row['sha256'],'GNU_EXTERNAL_FILE_DRIFT')
            guarded()
        self.files=tuple(self.files);self.ancestors=tuple(ancestors.items())
        guarded()
    def require_live(self):
        for path,identity in self.ancestors:
            info=path.lstat()
            require(stat.S_ISDIR(info.st_mode) and self._parent_identity(info)==identity,'GNU_EXTERNAL_ANCESTOR_DRIFT')
        for path,identity in self.files:
            require(self._file_identity(path.lstat())==identity,'GNU_EXTERNAL_FILE_DRIFT')
        for path in self.absent:
            try:path.lstat()
            except FileNotFoundError:continue
            raise Pending('GNU_EXTERNAL_ABSENCE_DRIFT')

def _path_record(path):
    value=path.lstat()
    return {'uid':value.st_uid,'mode':stat.S_IMODE(value.st_mode),
            'link':stat.S_ISLNK(value.st_mode),'regular':stat.S_ISREG(value.st_mode),
            'directory':stat.S_ISDIR(value.st_mode),'links':value.st_nlink}

def check_ancestor_records(records):
    require(bool(records),'ANCESTOR_CHAIN_EMPTY')
    for value in records:
        require(not value['link'] and value['uid']==0 and not value['mode']&0o022,
                'UNPROTECTED_ANCESTOR')

def _protected_ancestors(path):
    # Never chmod/chown ancestors. /tmp(1777), Android storage and an ordinary
    # runner-owned workspace are unsupported even if their leaf was frozen.
    paths=[path,*path.parents]
    check_ancestor_records([_path_record(p) for p in paths])

def _profile(facts):
    require(facts['linux'] and facts['host_euid']==0,'UNSUPPORTED_PRIVILEGED_HOST')
    require(facts['boot'] and facts['startup'] and facts['helper'],'BOOT_STARTUP_HELPER_UNQUALIFIED')

def _identity(facts):
    uid,gid=facts['uid'],facts['gid']
    require(type(uid) is int and type(gid) is int and uid>0 and gid>0,'CHILD_IDENTITY_NOT_DISTINCT')
    require(facts['existing'] and facts['host_uid']!=uid and facts['host_gid']!=gid,'CHILD_IDENTITY_NOT_EXISTING_DISTINCT')

def _process(facts,identity):
    require(facts['uids']==[identity['uid']]*3 and facts['gids']==[identity['gid']]*3,
            'CHILD_SAVED_IDENTITY_DRIFT')
    require(facts['groups']==[] and facts['no_new_privs']==1 and facts['caps']==[0]*5,
            'CHILD_CAPABILITY_NNP_DRIFT')

def _launch_spec(review,classes,bootstrap):
    expected=['setpriv','--reuid=UID','--regid=GID','--clear-groups','--bounding-set=-all',
              '--inh-caps=-all','--ambient-caps=-all','--no-new-privs','java',
              '-XX:+DisableAttachMechanism','-Xshare:off','-cp','OWNED_CLASSES',bootstrap,'--host-control']
    require(review['launch_template']==expected,'REVIEWED_LAUNCH_ARGUMENTS_DRIFT')
    tools=review['tools'];uid=review['child_uid'];gid=review['child_gid']
    identity={'uid':uid,'gid':gid,'existing':True,'host_uid':0,'host_gid':0}
    _identity(identity)
    command=(tools['setpriv']['path'],f'--reuid={uid}',f'--regid={gid}',*expected[3:8],
             tools['java']['path'],'-XX:+DisableAttachMechanism','-Xshare:off','-cp',str(classes),bootstrap,'--host-control')
    # No PATH, CLASSPATH, agent/option/library variables or inherited home.
    return command,{'LANG':'C','LC_ALL':'C'}

def _transaction(driver,review,cancel,borrow_live=lambda:None):
    """All backends share ordering, failure cleanup, and one spawn deadline.

    Driver actions are either real OS operations or explicitly simulated; their
    effects never substitute for one another. No result survives cleanup failure.
    """
    primary=None;started=None
    def checkpoint():
        cancel();borrow_live()
    try:
        checkpoint();_profile(driver.profile(review));identity=driver.identity(review);_identity(identity)
        check_ancestor_records(driver.ancestors())
        driver.create();checkpoint();driver.sources();driver.compile();checkpoint();driver.closure()
        driver.freeze();driver.verify();checkpoint()
        started=driver.clock();deadline=started+driver.timeout;driver.spawn()
        driver.ready(deadline);checkpoint();_remaining(driver,deadline)
        _process(driver.process(),identity);checkpoint()
        _remaining(driver,deadline);driver.release(deadline);checkpoint()
        remaining=_remaining(driver,deadline)
        result=driver.wait(remaining);checkpoint()
        require(driver.clock()-started<=driver.timeout,'ORIGINAL_SPAWN_DEADLINE')
        return result
    except BaseException as failure:
        primary=failure;raise
    finally:
        try:driver.cleanup()
        except BaseException:
            # Preserve primary identity. No suppressed array/formatting/new
            # exception wrapper is allocated to report a secondary cleanup fault.
            if primary is None:raise

def _remaining(driver,deadline):
    value=deadline-driver.clock()
    require(value>0,'ORIGINAL_SPAWN_DEADLINE')
    return value

def simulate(review,driver,cancel=lambda:None):
    """Test-only effects. A simulation never issues _HostSession or a permit."""
    require(type(review) is dict and review.get('scope')=='SIMULATED','SIMULATION_ONLY')
    result=_transaction(driver,review,cancel)
    return {'scope':'SIMULATED','qualified':False,'result':result}

class _HostSession:
    """Actual completed protected-host events, still not capture/runtime authority."""
    __slots__=('pid','exit','_closed')
    def __init__(self,pid,exit):self.pid=pid;self.exit=exit;self._closed=True
    def require_runtime(self):raise Pending('CAPTURE_COMPILER_RUNTIME_AUTHORITY_UNIMPLEMENTED')

def produce(review_id,source_root,protected_parent,cancel=lambda:None,*,boot_receipt=None):
    """Real producer. Only installed independent review records select its tuple.

    The current empty registries refuse before compiling, privilege changes or
    spawning. Never call this factory from fake-OS tests to manufacture authority.
    """
    review=_review(review_id)
    validate_boot_receipt(boot_receipt,review)
    verifier=_boot_verifier();boot_key=review.data['boot_bundle']
    def borrow_live():
        verifier['require_borrow_live'](boot_receipt,boot_key)
    backend=_LinuxHost(review,Path(source_root),Path(protected_parent))
    result=_transaction(backend,review,cancel,borrow_live)
    borrow_live()
    return _HostSession(result['pid'],result['exit'])

def produce_gnu(review_id,source_root,protected_parent,cancel=lambda:None,*,boot_receipt=None):
    """Additive protected GNU HOST route; strict produce/consume_host are unchanged.

    A genuine registered component, whole native/startup attachment and protected
    platform are prerequisites. Borrowed image ownership stays with the caller
    through child cleanup and final publication. This still issues no capture
    initializer or full runtime capability.

    Args:
        review_id: Independently installed GNU HOST tuple identifier.
        source_root: Protected source tree selected by that tuple.
        protected_parent: Existing supported parent; never repaired/installed.
        cancel: Original checkpoint, without a new spawn deadline.
        boot_receipt: Live registered component from the same cached namespace.

    Returns:
        A completed HOST event session whose require_runtime remains unsealed.

    Raises:
        RuntimeError: Missing/mismatched authority, platform or lifetime premise.
        BaseException: Preserves the primary failure through owned child cleanup;
            borrowed image ownership stays with its caller.
    """
    review=_review_gnu(review_id)
    borrow=_gnu_component()['borrow_gnu_host'](boot_receipt,review.data['boot_component'],cancel)
    def original_checkpoint():
        borrow.require_live();cancel();borrow.require_live()
    external=_GnuExternalBorrow(review.data['runtime']['external_files'],review.data['runtime']['absent_paths'],original_checkpoint)
    def live():borrow.require_live();external.require_live()
    live();cancel();live()
    selected=_gnu_launch_selection(review,borrow)
    backend_type=(_GnuDeadlineLinuxHost if selected.data['schema']=='B_SYSTEM_CLOSED_DIRECTORY_GNU_2'
                  else _LinuxHost)
    backend=backend_type(selected,Path(source_root),Path(protected_parent))
    result=_transaction(backend,selected,cancel,live)
    live()
    return _HostSession(result['pid'],result['exit'])

class _LinuxHost:
    """One source-qualified Linux/setpriv profile. Not executed by UNIT tests.

    Helper/JVM/tool bytes and helper pre-exec cap/uid/NNP enforcement are required
    in the independent tuple. /proc checks confirm facts; they are not the proof
    that the helper enforces them before exec or throughout the child's lifetime.
    """
    timeout=300
    clock=staticmethod(monotonic)
    def __init__(self,review,source_root,parent):
        self.review=review.data;self.source_root=source_root;self.parent=parent
        self.stage=None;self.child=None;self.h=_dependency();self.command=None
    def profile(self,review):
        runtime=self.review['runtime'];tools=self.review['tools']
        require(type(runtime) is dict,'RUNTIME_PROFILE')
        require(runtime['profile']=='LINUX_SINGLE_SUN_SYSTEM_DIR_1','RUNTIME_PROFILE')
        for role in ('javac','java','setpriv'):
            tool=Path(tools[role]['path']);_protected_ancestors(tool)
            require(tool.is_file() and _digest(tool)==tools[role]['sha256'],'TOOL_SOURCE_IMAGE_DRIFT')
        require(tools['java']['path']==runtime['java'],'BOOT_TOOL_JOIN')
        return {'linux':sys.platform=='linux','host_euid':os.geteuid(),
                'boot':True,'startup':True,'helper':True}
    def identity(self,review):
        uid,gid=self.review['child_uid'],self.review['child_gid']
        try:
            entry=pwd.getpwuid(uid);group=grp.getgrgid(gid)
            existing=entry.pw_gid==gid and group.gr_gid==gid
        except KeyError:existing=False
        return {'uid':uid,'gid':gid,'existing':existing,'host_uid':os.getuid(),'host_gid':os.getgid()}
    def ancestors(self):
        # Require the caller's existing protected parent, never repair it.
        path=self.parent.absolute();return [_path_record(p) for p in (path,*path.parents)]
    def create(self):
        self.stage=Path(tempfile.mkdtemp(prefix='b-host-',dir=self.parent))
        self.classes=self.stage/'classes';self.empty=self.stage/'empty'
        self.classes.mkdir();self.empty.mkdir()
    def sources(self):
        records=self.review['sources']
        require(0<len(records)<=64,'SOURCE_CLOSURE_LIMIT')
        paths=[]
        for name in records:
            relative=Path(name)
            require(not relative.is_absolute() and '..' not in relative.parts,'SOURCE_PATH')
            path=self.source_root/relative;_protected_ancestors(path)
            paths.append(path)
        self.copies=self.h['snapshot_sources'](paths,self.stage/'sources')
        for (name,expected),actual in zip(records.items(),self.copies):
            require(actual['bytes']==expected['bytes'] and actual['sha256']==expected['sha256'],'SOURCE_BUILD_JOIN_DRIFT')
    def compile(self):
        tool=self.review['tools']['javac']['path']
        self.h['run_owned']([tool,'--release','17','-proc:none','-implicit:none','-g:none',
                            '--class-path',self.empty,'--source-path',self.empty,'-d',self.classes,
                            *[r['copy'] for r in self.copies]],self.stage,timeout=60)
    def closure(self):
        self.metadata=self.h['inspect_directory'](self.classes,self.review['classes'],self.review['parents'])
        require({n:v['sha256'] for n,v in self.metadata['classes'].items()}==
                self.review['classes'],'COMPILED_EXPECTED_BYTES_DRIFT')
        # Reflective names are not discoverable from class descriptors alone.
        require(set(self.review['reflective_targets'])<=set(self.metadata['classes']),'REFLECTIVE_CLOSURE_MISSING')
    def freeze(self):
        # Only our owned stage. No account, security configuration or ancestor
        # mutation. uid0 owns all constructed files on the supported real route.
        for path in sorted(self.stage.rglob('*'),key=lambda p:len(p.parts),reverse=True):
            require(not path.is_symlink(),'STAGE_LINK')
            os.chmod(path,0o555 if path.is_dir() else 0o444)
        os.chmod(self.stage,0o555)
    def verify(self):
        for path in (self.stage,*self.stage.rglob('*')):
            r=_path_record(path)
            require(not r['link'] and r['uid']==0 and not r['mode']&0o222 and
                    (r['directory'] or r['regular'] and r['links']==1),'FROZEN_STAGE_DRIFT')
        _protected_ancestors(self.classes)
        self.h['verify_directory'](self.classes,self.metadata)
        # Exact argv/options are code-owned; the independent helper/JVM source
        # profile must bind their enforcement. No user-supplied option strings.
        self.command,self.environment=_launch_spec(self.review,self.classes,self.h['BOOTSTRAP'])
    def spawn(self):
        self.child=subprocess.Popen(self.command,cwd=self.stage,env=self.environment,
                                    stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,
                                    start_new_session=True,bufsize=0)
    def ready(self,deadline):
        # The reviewed helper execs Java in this exact PID. Bootstrap emits the
        # fixed bounded line only after Java entry, then waits for one ACK byte.
        # This is synchronization, never proof of UID/caps/boot/source authority.
        require(self.child is not None,'NO_OWNED_PID')
        data=bytearray();fd=self.child.stdout.fileno()
        while b'\n' not in data:
            available,_,_=select.select([fd],[],[],_remaining(self,deadline))
            require(bool(available),'READINESS_TIMEOUT')
            block=os.read(fd,MAX_READY_BYTES+1-len(data))
            require(bool(block),'READINESS_EOF')
            data.extend(block)
            require(len(data)<=MAX_READY_BYTES,'READINESS_OVERSIZE')
        _remaining(self,deadline)
        require(data==READY_PREFIX+str(self.child.pid).encode('ascii')+b'\n','READINESS_WRONG_CHILD_OR_PROTOCOL')
    def release(self,deadline):
        # Called only after actual OS identity/capability checks and cancellation.
        # One byte into the empty owned pipe; no future dispatch is permitted by
        # the byte itself. Current child still only returns PENDING.
        _remaining(self,deadline)
        require(self.child.stdin.write(b'\x01')==1,'CONTROL_RELEASE_WRITE')
        self.child.stdin.close();self.child.stdin=None
    def process(self):
        require(self.child is not None,'NO_OWNED_PID')
        status=Path(f'/proc/{self.child.pid}/status').read_text()
        values=dict(line.split(':',1) for line in status.splitlines() if ':' in line)
        return {'uids':list(map(int,values['Uid'].split()))[:3],
                'gids':list(map(int,values['Gid'].split()))[:3],
                'groups':list(map(int,values['Groups'].split())),
                'caps':[int(values[n],16) for n in ('CapInh','CapPrm','CapEff','CapBnd','CapAmb')],
                'no_new_privs':int(values['NoNewPrivs'])}
    def wait(self,remaining):
        # Bounded pipe draining is still a separate required source envelope;
        # inherited run_owned checks are not claimed as a whole HOST heap proof.
        out,err=self.child.communicate(timeout=remaining)
        require(len(out)<=4096 and len(err)<=16384,'CHILD_OUTPUT_PROFILE')
        require(self.child.returncode==0,'CHILD_EXIT_FAILURE')
        require(out==b'PENDING_TRUSTED_HOST_STARTUP_BOOT_LAUNCH\n','PRODUCTION_PROTOCOL_NOT_IMPLEMENTED')
        return {'pid':self.child.pid,'exit':self.child.returncode}
    def cleanup(self):
        # Stop/reap before removing the immutable directory; no live child may
        # see a replacement artifact. Preserve original primary at transaction.
        if self.child is not None:
            import signal
            if self.child.poll() is None:
                try:os.killpg(self.child.pid,signal.SIGTERM)
                except ProcessLookupError:pass
            try:self.child.communicate(timeout=1)
            except subprocess.TimeoutExpired:
                try:os.killpg(self.child.pid,signal.SIGKILL)
                except ProcessLookupError:pass
                self.child.communicate()
        if self.stage is not None:
            os.chmod(self.stage,0o700)
            for path in self.stage.rglob('*'):
                if path.is_dir():os.chmod(path,0o700)
            shutil.rmtree(self.stage)


def _require_gnu_deadline_profile(runtime):
    """Check selection syntax, never independently issue a clock/runtime proof.

    _review_gnu also requires the complete runtime and component to equal their
    independently installed startup record. These strings alone are not that
    authority; actual registries remain empty.
    """
    require(type(runtime) is dict and runtime.get('host_control')==GNU_DEADLINE_PROTOCOL
            and runtime.get('clock_profile')==GNU_CLOCK_PROFILE,'GNU_CLOCK_PROFILE_REQUIRED')


class _GnuDeadlineClock:
    """Original integer spawn clock for the GNU-only driver; mechanics only.

    The SAME object supplies _transaction's first pre-spawn clock sample and
    all subsequent samples. No clock is sampled at construction or reset at
    readiness. The child deadline is bounded by both the exact original integer
    duration and the exact value of the existing floating transaction deadline.
    This object/frame cannot issue an initialization or runtime permit.
    """
    __slots__=('start_ns','last_ns','start_seconds','start_attempted','frame_attempted')
    LIMIT=(1 << 63)-1
    DURATION=300_000_000_000
    def __init__(self):
        self.start_ns=None;self.last_ns=None;self.start_seconds=None
        self.start_attempted=False;self.frame_attempted=False
    def _read(self):
        now=monotonic_ns()
        require(type(now) is int and 0<=now<=self.LIMIT and
                (self.last_ns is None or now>=self.last_ns),'GNU_CLOCK_DOMAIN')
        self.last_ns=now
        return now
    def clock(self):
        if self.start_ns is None:
            require(not self.start_attempted and not self.frame_attempted,'GNU_CLOCK_UNAVAILABLE')
            self.start_attempted=True
            now=self._read()
            require(now<=self.LIMIT-self.DURATION,'GNU_CLOCK_DOMAIN')
            self.start_ns=now;self.start_seconds=now/1_000_000_000
            return self.start_seconds
        now=self._read()
        require(now-self.start_ns<self.DURATION,'ORIGINAL_SPAWN_DEADLINE')
        return now/1_000_000_000
    def frame(self,pid,outer_deadline):
        require(not self.frame_attempted,'GNU_CONTROL_ALREADY_ATTEMPTED')
        self.frame_attempted=True
        require(self.start_ns is not None,'GNU_ORIGINAL_CLOCK_ABSENT')
        require(type(pid) is int and 0<pid<=self.LIMIT,'GNU_CONTROL_PID_DOMAIN')
        require(type(outer_deadline) is float and math.isfinite(outer_deadline)
                and outer_deadline==self.start_seconds+300,'GNU_OUTER_DEADLINE_DOMAIN')
        numerator,denominator=outer_deadline.as_integer_ratio()
        # Multiplying a float by1e9 could round UP. Integer ratio is the exact
        # original floating deadline, with an explicitly conservative floor.
        deadline=min(self.start_ns+self.DURATION,numerator*1_000_000_000//denominator)
        require(self.start_ns<deadline<=self.LIMIT,'GNU_OUTER_DEADLINE_DOMAIN')
        now=self._read()
        require(now<deadline,'ORIGINAL_SPAWN_DEADLINE')
        return struct.pack('>8siiqqq',b'BGNUD001',1,40,pid,self.start_ns,deadline)


class _GnuDeadlineLinuxHost(_LinuxHost):
    """GNU2 fixed deadline handoff; still no production initializer authority.

    Strict _LinuxHost and its one-byte route are unchanged. Source-qualified
    selection additionally needs the installed clock/native/startup contract;
    fake-driver UNIT effects cannot select or prove a supported platform.
    """
    def __init__(self,review,source_root,parent):
        _require_gnu_deadline_profile(review.data.get('runtime'))
        super().__init__(review,source_root,parent)
        self.deadline_clock=_GnuDeadlineClock()
    def clock(self):
        return self.deadline_clock.clock()
    def verify(self):
        for path in (self.stage,*self.stage.rglob('*')):
            record=_path_record(path)
            require(not record['link'] and record['uid']==0 and not record['mode']&0o222 and
                    (record['directory'] or record['regular'] and record['links']==1),'FROZEN_STAGE_DRIFT')
        _protected_ancestors(self.classes)
        self.h['verify_directory'](self.classes,self.metadata)
        self.command,self.environment=_gnu_deadline_launch_spec(self.review,self.classes,self.h['BOOTSTRAP'])
    def release(self,deadline):
        _remaining(self,deadline)
        frame=self.deadline_clock.frame(self.child.pid,deadline)
        require(self.child.stdin.write(frame)==40,'CONTROL_RELEASE_WRITE')
        self.child.stdin.close();self.child.stdin=None


def _gnu_deadline_launch_spec(review,classes,bootstrap):
    """Literal GNU2 argv; no mutable tuple, extra flag or caller command."""
    _require_gnu_deadline_profile(review.get('runtime'))
    expected=['setpriv','--reuid=CHILD_UID','--regid=CHILD_GID','--clear-groups','--bounding-set=-all',
              '--inh-caps=-all','--ambient-caps=-all','--no-new-privs','java',
              '-XX:+DisableAttachMechanism','-Xshare:off','-cp','OWNED_CLASSES',bootstrap,'--host-control-gnu-v1']
    require(review['launch_template']==expected,'REVIEWED_LAUNCH_ARGUMENTS_DRIFT')
    tools=review['tools'];uid=review['child_uid'];gid=review['child_gid']
    _identity({'uid':uid,'gid':gid,'existing':True,'host_uid':0,'host_gid':0})
    command=(tools['setpriv']['path'],f'--reuid={uid}',f'--regid={gid}',*expected[3:8],
             tools['java']['path'],'-XX:+DisableAttachMechanism','-Xshare:off','-cp',str(classes),bootstrap,
             '--host-control-gnu-v1')
    return command,{'LANG':'C','LC_ALL':'C'}
