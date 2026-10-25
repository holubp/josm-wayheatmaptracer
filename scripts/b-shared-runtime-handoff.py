#!/usr/bin/env python3
"""Source compilation -> owned class directory -> owned fresh UNIT JVM.

The real candidate consumer refuses while the independent trusted HOST/startup
producer is absent. Neither this module's output manifest nor UNIT compilation
can supply that authority. No privilege/configuration changes are performed.
"""
from pathlib import Path
import hashlib
import json
import os
import re
import signal
import stat
import struct
import subprocess
from time import monotonic

PACKAGE='org.openstreetmap.josm.plugins.wayheatmaptracer'
BOOTSTRAP=PACKAGE+'.service.tracing.probabilistic.BSharedRuntimeBootstrap'
LEDGER=PACKAGE+'.service.tracing.AttemptMemoryLedger'
UNIT_ENTRY='runtimeunit.ClosedDirectoryProbe'
MAX_FILES=64
MAX_FILE_BYTES=65536
MAX_ARTIFACT_BYTES=2*1024*1024
LONG_MAX=(1<<63)-1

class Pending(RuntimeError):
    """Required producer absent; no source/launch/initialization authority."""

def need(value,message):
    if not value:raise ValueError(message)

def count(n):
    need(type(n) is int and 0<=n<=LONG_MAX,'checked count')
    return n

def array(n,width=1):
    p=count(count(n)*count(width));return count(16+8*((p+7)//8))

def resource_byte_arrays(length):
    """Exact stable-file Resource.getBytes growth; ONLY its byte-array subterm."""
    count(length);need(length<=MAX_FILE_BYTES,'supported class length')
    sizes=[0];n=0
    while n<length:
        n=min(length,2*n+1024);sizes.append(n)
    return {'sizes':sizes,'allocation_sum':sum(array(n) for n in sizes),
            'whole_load_payload_sealed':False}

class Reader:
    def __init__(self,data):self.data=data;self.at=0
    def take(self,n):
        need(n>=0 and self.at+n<=len(self.data),'truncated classfile')
        out=self.data[self.at:self.at+n];self.at+=n;return out
    def u1(self):return self.take(1)[0]
    def u2(self):return struct.unpack('>H',self.take(2))[0]
    def u4(self):return struct.unpack('>I',self.take(4))[0]

def class_info(data):
    """Bounded Java17 class metadata, including class/descriptor/nest/sealed deps."""
    need(0<len(data)<=MAX_FILE_BYTES,'class bytes')
    r=Reader(data);need(r.u4()==0xcafebabe,'class magic');r.u2();need(r.u2()==61,'Java17 only')
    cp=[None]*r.u2();i=1
    while i<len(cp):
        tag=r.u1()
        if tag==1:cp[i]=(tag,r.take(r.u2()))
        elif tag in (7,8,16,19,20):cp[i]=(tag,r.u2())
        elif tag in (3,4):r.take(4)
        elif tag in (5,6):r.take(8);i+=1
        elif tag in (9,10,11,12,17,18):r.take(4)
        elif tag==15:r.take(3)
        else:raise ValueError('unsupported class constant')
        i+=1
    def utf(index):
        need(0<index<len(cp) and cp[index] and cp[index][0]==1,'class UTF8')
        #Names/descriptors used here are ASCII. Arbitrary String constants are
        #not decoded as UTF8/MUTF8 source authority.
        return cp[index][1].decode('ascii')
    def cls(index):
        need(0<index<len(cp) and cp[index] and cp[index][0]==7,'class reference')
        return utf(cp[index][1])
    r.u2();name=cls(r.u2());r.u2()
    for _ in range(r.u2()):r.u2()
    descriptors=[]
    for _ in range(2):
        for _ in range(r.u2()):
            r.u2();r.u2();descriptors.append(utf(r.u2()))
            for _ in range(r.u2()):r.u2();r.take(r.u4())
    nest=[];permitted=[]
    for _ in range(r.u2()):
        attr=utf(r.u2());part=Reader(r.take(r.u4()))
        if attr=='NestHost':nest.append(cls(part.u2()))
        elif attr in ('NestMembers','PermittedSubclasses'):
            values=[cls(part.u2()) for _ in range(part.u2())]
            (permitted if attr=='PermittedSubclasses' else nest).extend(values)
        else:continue
        need(part.at==len(part.data),'class attribute length')
    need(r.at==len(data),'class trailing bytes')
    dependencies=set(nest+permitted)
    for value in cp:
        if value and value[0]==7:
            value=utf(value[1]);dependencies.update(re.findall(r'L([^;]+);',value) if value.startswith('[') else [value])
        elif value and value[0]==1:
            #All descriptor/signature class references (including bootstrap
            #MethodType descriptors) must be present. Non-ASCII string bodies
            #are irrelevant here; they cannot introduce a class reference.
            text=value[1].decode('ascii',errors='ignore')
            dependencies.update(re.findall(r'L([\w/$]+)(?:;|<)',text))
    dependencies.discard(name)
    return {'name':name.replace('/','.'),'dependencies':sorted(n.replace('/','.') for n in dependencies),
            'nest':sorted(n.replace('/','.') for n in nest),
            'permitted':sorted(n.replace('/','.') for n in permitted)}

def inspect_directory(directory,expected=None,allowed_parents=None):
    directory=Path(directory);need(directory.is_dir() and not directory.is_symlink(),'class directory')
    records={};total=0
    for path in sorted(directory.rglob('*')):
        need(not path.is_symlink(),'class symlink')
        if path.is_dir():continue
        need(path.is_file() and path.suffix=='.class','non-class artifact member')
        #Bound the actual opened file before allocating its input buffer.
        with os.fdopen(os.open(path,os.O_RDONLY|os.O_NOFOLLOW),'rb') as stream:
            before=os.fstat(stream.fileno())
            need(stat.S_ISREG(before.st_mode) and 0<before.st_size<=MAX_FILE_BYTES,'class file profile')
            need(total+before.st_size<=MAX_ARTIFACT_BYTES and len(records)<MAX_FILES,'bounded artifact')
            data=stream.read(MAX_FILE_BYTES+1);after=os.fstat(stream.fileno())
            need(len(data)==before.st_size and
                 (before.st_ino,before.st_dev,before.st_size,before.st_mtime_ns)==
                 (after.st_ino,after.st_dev,after.st_size,after.st_mtime_ns),'class changed while reading')
        total=count(total+len(data))
        need(total<=MAX_ARTIFACT_BYTES and len(records)<MAX_FILES,'bounded artifact')
        info=class_info(data);name=info['name']
        need(path.relative_to(directory).as_posix()==name.replace('.','/')+'.class','declared class path')
        need(name not in records,'duplicate class')
        records[name]={**info,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()}
    need(bool(records),'empty class directory')
    if expected is not None:need(set(records)==set(expected),'output closure drift')
    external=set()
    for value in records.values():
        for name in value['dependencies']:
            if name not in records:
                need(not name.startswith(PACKAGE+'.') and not name.startswith('runtimeunit.'),'missing project closure')
                external.add(name)
    if allowed_parents is not None:need(external<=set(allowed_parents),'unapproved parent dependency')
    return {'classes':records,'observed_parent_dependencies':sorted(external),'class_bytes':total,
            'authority':'DATA_ONLY_NOT_PROVENANCE'}

def verify_directory(directory,baseline):
    current=inspect_directory(directory,expected=baseline['classes'])
    need({k:v['sha256'] for k,v in current['classes'].items()}==
         {k:v['sha256'] for k,v in baseline['classes'].items()},'class byte drift')
    return current

def snapshot_sources(sources,destination):
    """Copy exact opened regular-file bytes; this is DATA consistency, not trust."""
    destination=Path(destination);destination.mkdir()
    records=[]
    for i,path in enumerate(sources):
        path=Path(path)
        with os.fdopen(os.open(path,os.O_RDONLY|os.O_NOFOLLOW),'rb') as stream:
            before=os.fstat(stream.fileno())
            need(stat.S_ISREG(before.st_mode) and before.st_size<=MAX_FILE_BYTES,'source file profile')
            data=stream.read(MAX_FILE_BYTES+1);after=os.fstat(stream.fileno())
            need(len(data)==before.st_size and
                 (before.st_ino,before.st_dev,before.st_size,before.st_mtime_ns)==
                 (after.st_ino,after.st_dev,after.st_size,after.st_mtime_ns),'source changed while reading')
        own=destination/str(i);own.mkdir();copy=own/path.name;copy.write_bytes(data)
        records.append({'source':str(path),'copy':str(copy),'bytes':len(data),
                        'sha256':hashlib.sha256(data).hexdigest()})
    return records

INJECT=('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH','LD_PRELOAD','LD_AUDIT')

def child_environment():
    #Deliberate allowlist, not inherited option/agent/library state.
    return {'PATH':os.environ.get('PATH',''),'LANG':'C','LC_ALL':'C'}

def run_owned(argv,cwd,timeout=15,checkpoint=None):
    """Own the actual process/pipes; reap on timeout/failure before returning."""
    need(0<timeout<=300,'worker ceiling')
    if checkpoint is not None:checkpoint('before_spawn',None)
    started=monotonic()
    process=subprocess.Popen([str(x) for x in argv],cwd=cwd,env=child_environment(),
                             stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,
                             start_new_session=True)
    try:
        if checkpoint is not None:checkpoint('spawned',process.pid)
        remaining=timeout-(monotonic()-started)
        if remaining<=0:raise subprocess.TimeoutExpired(argv,timeout)
        stdout,stderr=process.communicate(timeout=remaining)
    except BaseException:
        if process.poll() is None:
            try:os.killpg(process.pid,signal.SIGTERM)
            except ProcessLookupError:pass
        try:process.communicate(timeout=1)
        except subprocess.TimeoutExpired:
            try:os.killpg(process.pid,signal.SIGKILL)
            except ProcessLookupError:pass
            process.communicate()
        raise
    need(len(stdout)<=4096 and len(stderr)<=16384,'unexpected child output')
    need(process.returncode==0,'owned child failed')
    if checkpoint is not None:checkpoint('before_publication',process.pid)
    if monotonic()-started>timeout:raise subprocess.TimeoutExpired(argv,timeout)
    return {'pid':process.pid,'stdout':stdout.decode('ascii'),'stderr':stderr.decode('ascii'),
            'exit':process.returncode,'reaped':process.poll() is not None}

def validate_unit_report(stdout):
    need(stdout=='UNIT_ROOT_CREATED_CLOSED\n','UNIT protocol drift')

def candidate_preflight(reviewed_host_session=None):
    """Real consumer remains closed; data dictionaries cannot create a session.

    The independent reviewed HOST producer and complete startup/load envelope
    have not been lowered. No public expected-hash/boolean constructor exists.
    """
    if reviewed_host_session is None:raise Pending('TRUSTED_HOST_BUILD_PRODUCER_UNIMPLEMENTED')
    raise Pending('STARTUP_BOOT_LAUNCH_SOURCE_ENVELOPES_UNSEALED')

def unit_handoff(workspace,temporary,jdk):
    """Actual UNIT source -> javac -> class directory -> fresh JVM -> Java root.

    No immutable/root-owned/capability-stripped HOST profile is claimed here.
    The real candidate preflight above cannot consume this DATA result.
    """
    workspace=Path(workspace);temporary=Path(temporary);jdk=Path(jdk)
    need(temporary.is_dir() and not any(temporary.iterdir()),'owned empty UNIT stage')
    classes=temporary/'classes';empty=temporary/'empty';classes.mkdir();empty.mkdir()
    package=PACKAGE.replace('.','/')
    bootstrap=workspace/'src/test/java'/package/'service/tracing/probabilistic/BSharedRuntimeBootstrap.java'
    ledger=workspace/'src/main/java'/package/'service/tracing/AttemptMemoryLedger.java'
    fixture=temporary/'ClosedDirectoryProbe.java'
    fixture.write_text('package runtimeunit; public final class ClosedDirectoryProbe {'
                       'public sealed interface Marker permits Leaf {} '
                       'public static final class Leaf implements Marker {} '
                       'public static int initialize(){return 32;} }\n')
    sources=snapshot_sources([bootstrap,ledger,fixture],temporary/'source-snapshot')
    command=[jdk/'bin/javac','--release','17','-proc:none','-implicit:none','-g:none',
             '--class-path',empty,'--source-path',empty,'-d',classes,*[p['copy'] for p in sources]]
    compile_result=run_owned(command,temporary,timeout=60)
    metadata=inspect_directory(classes)
    #UNIT observations are not a prospective approved parent allowlist.
    need(BOOTSTRAP in metadata['classes'] and LEDGER in metadata['classes'] and UNIT_ENTRY in metadata['classes'],'UNIT output')
    verify_directory(classes,metadata)
    result=run_owned([jdk/'bin/java','-cp',classes,BOOTSTRAP,'--unit'],temporary)
    validate_unit_report(result['stdout']);verify_directory(classes,metadata)
    return {**result,'scope':'UNIT_ONLY','runtimeQualified':False,'hostEnforcementQualified':False,
            'classes':sorted(metadata['classes']),'metadata':metadata,
            'compile':compile_result,'sources':sources,'source_digests':{p['source']:p['sha256'] for p in sources}}

if __name__=='__main__':
    #No actual candidate entry can be unlocked by CLI flags or a UNIT manifest.
    try:candidate_preflight()
    except Pending as pending:print(json.dumps({'scope':'PENDING','reason':str(pending),'runtimeQualified':False}))
