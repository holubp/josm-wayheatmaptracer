"""Fixed PUBLIC tool metadata DATA only; never profile or runtime authority.

CLI accepts only fresh output. Private fixture policies test DATA mechanics;
neither observations nor complete metadata can install a production profile.
No network, candidate executable, target image or ambient PATH lookup is used.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import stat
import tempfile
from time import monotonic

COLLECTOR_SHA256='4c9740f39ae3ced533a493c89baf01a217b8a7a7100f53c4a32658e5458fce17'
LIMITS={'files':64,'file_bytes':512<<20,'hash_bytes':512<<20,'aliases':8,
        'edges':256,'queries':256,'observations':5+2+256,'string':4096,'lines':4096,'seconds':600}
MISSING=('external-bundle-approval','accepted-native-loader-policy','metadata-reader-toolchain-approval',
         'projector-vm-native-resources','projector-jimage-native-resources','projector-config-resources',
         'signer-config-resources','public-key-current','public-keyring-current','owned-home-current',
         'primary-invocation-current','installed-profile-approval')
CAUSES=frozenset(('COLLECTOR_SOURCE_DRIFT','OUTPUT_OWNER_DRIFT','PATH_POLICY','PATH_PARENT_TYPE','ALIAS_LIMIT',
 'ALIAS_PATH_POLICY','METADATA_LIMIT','METADATA_ENCODING','ELF_PROFILE_UNAVAILABLE','ELF_INTERPRETER_FORMAT',
 'ELF_NEEDED_FORMAT','ELF_SEARCH_FORMAT','FILE_DRIFT','ALIAS_DRIFT','FILE_COUNT_LIMIT','FILE_TYPE','HASH_BYTES_LIMIT',
 'FILE_TRUNCATED','QUERY_LIMIT','READER_DRIFT','METADATA_READER_NONZERO','PACKAGE_OWNER_FORMAT','PACKAGE_VERSION_FORMAT',
 'PACKAGE_MANIFEST_POLICY','DATA_SCOPE','PROJECTOR_TOOL_ROOT','PUBLIC_METADATA_VALUE','PUBLIC_METADATA_DUPLICATE',
 'GRAPH_EDGE_LIMIT','RUNNER_METADATA_VALUE','OBSERVATION_LIMIT','OBSERVATION_ROLE','TERMINAL_STATUS_LIMIT'))
JDK=Path('/usr/lib/jvm/temurin-17-jdk-amd64')
POLICY={'roots':tuple(map(Path,('/usr','/lib','/lib64','/bin','/etc'))),
        'search':tuple(map(Path,('/lib/x86_64-linux-gnu','/usr/lib/x86_64-linux-gnu'))),
        'jdk':JDK,'tools':{'signer':Path('/usr/bin/gpgv'),'projector':JDK/'bin/jimage'},
        'readers':{'elf':Path('/usr/bin/readelf'),'package':Path('/usr/bin/dpkg-query')},
        'os_release':Path('/etc/os-release'),'runner':{},'scope':'PUBLIC_METADATA_DATA'}

class Failure(RuntimeError):
    """Finite safe DATA cause; never raw subprocess/configuration text."""

def require(ok,cause):
    if not ok:raise Failure(cause)

def _dependency():
    path=Path(__file__).with_name('b-boot-bundle-collect.py');data=path.read_bytes()
    require(len(data)<=65536 and hashlib.sha256(data).hexdigest()==COLLECTOR_SHA256,'COLLECTOR_SOURCE_DRIFT')
    ns={'__name__':'tool_data_evidence','__file__':str(path)};exec(compile(data,str(path),'exec'),ns)
    return ns,ns['dependency']()

def _identity(info):
    return (info.st_dev,info.st_ino,info.st_mode,info.st_uid,info.st_gid,info.st_size,info.st_mtime_ns,info.st_ctime_ns)

def _owners(path):
    rows=[]
    for p in reversed((path,*path.parents)):
        info=p.lstat();require(stat.S_ISDIR(info.st_mode),'OUTPUT_OWNER_DRIFT');rows.append((p,(info.st_dev,info.st_ino)))
    return tuple(rows)

def _live(owners):
    for path,key in owners:
        info=path.lstat();require(stat.S_ISDIR(info.st_mode) and (info.st_dev,info.st_ino)==key,'OUTPUT_OWNER_DRIFT')

def _within(path,roots):return any(path==r or r in path.parents for r in roots)

def _resolve(path,policy):
    """Bounded lexical symlink resolution with each directory identity retained."""
    path=Path(path);require(path.is_absolute() and len(str(path))<=4096 and _within(path,policy['roots']),'PATH_POLICY')
    aliases=[]
    while True:
        current=Path(path.anchor);parents=[];parts=path.parts[1:];restart=False
        for i,part in enumerate(parts):
            require(part not in ('','.','..'),'PATH_POLICY');current=current/part;info=current.lstat()
            if stat.S_ISLNK(info.st_mode):
                require(len(aliases)<LIMITS['aliases'],'ALIAS_LIMIT');target=os.readlink(current)
                require(len(target)<=4096,'ALIAS_LIMIT');base=Path(target) if target.startswith('/') else current.parent/target
                updated=Path(os.path.normpath(str(base.joinpath(*parts[i+1:]))))
                require(_within(updated,policy['roots']),'ALIAS_PATH_POLICY')
                aliases.append((str(current),target,info.st_dev,info.st_ino,info.st_mtime_ns,info.st_ctime_ns))
                path=updated;restart=True;break
            if i<len(parts)-1:
                require(stat.S_ISDIR(info.st_mode),'PATH_PARENT_TYPE');parents.append((str(current),info.st_dev,info.st_ino))
        if not restart:return path,tuple(aliases),tuple(parents)

def _text(data,lines=4096):
    require(len(data)<=65536 and data.count(b'\n')<=lines,'METADATA_LIMIT')
    try:value=data.decode('ascii')
    except UnicodeError:raise Failure('METADATA_ENCODING') from None
    require(all(c=='\n' or c=='\t' or 32<=ord(c)<127 for c in value),'METADATA_ENCODING')
    return value

def _elf(data):
    value=_text(data,1024);needed=[];interp=None;search=[]
    require('Class: ELF64' in re.sub(r' +',' ',value) and 'X86-64' in value,'ELF_PROFILE_UNAVAILABLE')
    for line in value.splitlines():
        if 'Requesting program interpreter:' in line:
            m=re.fullmatch(r'\s*\[Requesting program interpreter: (/[^\]\s]+)\]\s*',line)
            require(m is not None and interp is None,'ELF_INTERPRETER_FORMAT');interp=m[1]
        if '(NEEDED)' in line:
            m=re.fullmatch(r'\s*0x[0-9a-fA-F]+\s+\(NEEDED\)\s+Shared library: \[([A-Za-z0-9_.+\-]+)\]\s*',line)
            require(m is not None and len(needed)<32 and m[1] not in needed,'ELF_NEEDED_FORMAT');needed.append(m[1])
        if '(RPATH)' in line or '(RUNPATH)' in line:
            m=re.fullmatch(r'\s*0x[0-9a-fA-F]+\s+\((RPATH|RUNPATH)\)\s+Library (?:rpath|runpath): \[([^\]]*)\]\s*',line)
            require(m is not None and len(m[2])<=4096 and m[2].count(':')<32,'ELF_SEARCH_FORMAT')
            paths=m[2].split(':');safe=all(re.fullmatch(r'(?:\$ORIGIN|/(?:usr|lib|lib64))(?:/[A-Za-z0-9_.+\-]+)*',p) for p in paths)
            search.append({'kind':m[1],'availability':'OBSERVED_CANDIDATE' if safe else 'UNAVAILABLE',
                           'paths':paths if safe else None,'text_bytes':len(m[2]),
                           'text_sha256':hashlib.sha256(m[2].encode()).hexdigest(),
                           'scope':'REQUIRES_INDEPENDENT_LOADER_SEARCH_POLICY'})
    return {'interpreter':interp,'needed':needed,'search':search,'scope':'CANDIDATE_STATIC_ELF_ONLY'}

def _manifest(data):
    # Package root markers are metadata, never requests to read/stat a file.
    roots=('/usr','/lib','/lib64','/bin','/sbin','/etc');paths=[];directories=[]
    for raw in _text(data).splitlines():
        require(0<len(raw)<=4096,'PACKAGE_MANIFEST_POLICY')
        path='/' if raw=='/.' else raw
        require(path=='/' or ((path in roots or any(path.startswith(r+'/') for r in roots)) and
                all(p not in ('','.','..') for p in path.split('/')[1:]) and '\t' not in path),'PACKAGE_MANIFEST_POLICY')
        paths.append(path)
        if path=='/' or path in roots:directories.append(path)
    return paths,directories

class _Session:
    def __init__(self,output,policy,cancel,clock):
        start=clock();self.writer_module,self.base=_dependency();self.policy=policy
        self.clock=self.writer_module['Clock'](600,clock,cancel);self.clock.deadline=start+600
        self.e=self.writer_module['Evidence'](output,{'leaf':65536,'output':32<<20});self.output=Path(output).absolute()
        self.output_owners=_owners(self.output);self.stage=None;self.stage_owners=None
        self.files={};self.initial_reports=set();self.observations={};self.hash_bytes=0;self.queries=0;self.executions=0;self.execution_available=True
        self.roles={};self.edges=[];self.missing=set(MISSING);self.packages={}
    def check(self):
        _live(self.output_owners)
        if self.stage_owners is not None:_live(self.stage_owners)
        self.files_live()
        self.clock.check();_live(self.output_owners)
        if self.stage_owners is not None:_live(self.stage_owners)
        self.files_live()
    def files_live(self):
        for row in self.files.values():
            require(_identity(Path(row['canonical']).lstat())==row['identity'],'FILE_DRIFT')
        for observed in self.observations.values():
            canonical,aliases,parents=_resolve(Path(observed['requested']),self.policy)
            require(str(canonical)==observed['canonical'] and aliases==observed['aliases'] and parents==observed['parents'],'ALIAS_DRIFT')
    def emit(self,name,value):self.check();self.e.json(name,value);self.check()
    def observe(self,path,role,row,aliases,parents):
        require(type(role) is str and re.fullmatch(r'[A-Za-z0-9_.\-]{1,64}',role),'OBSERVATION_ROLE')
        key=(str(path),role);observed={'requested':str(path),'role':role,'canonical':row['canonical'],'aliases':aliases,'parents':parents}
        if key in self.observations:require(self.observations[key]==observed,'ALIAS_DRIFT');return
        require(len(self.observations)<LIMITS['observations'],'OBSERVATION_LIMIT')
        self.observations[key]=observed;row['observations'].append(observed)
        self.emit('reports/observations/observation-%03d.json'%len(self.observations),observed)
    def file(self,path,role):
        self.check();canonical,aliases,parents=_resolve(path,self.policy);key=str(canonical)
        if key in self.files:
            row=self.files[key];require(tuple(row['identity'])==_identity(canonical.lstat()),'FILE_DRIFT')
            self.observe(path,role,row,aliases,parents);return row
        require(len(self.observations)<LIMITS['observations'],'OBSERVATION_LIMIT')
        require(len(self.files)<64,'FILE_COUNT_LIMIT');fd=os.open(canonical,os.O_RDONLY|os.O_NOFOLLOW)
        try:
            before=_identity(os.fstat(fd));require(stat.S_ISREG(before[2]),'FILE_TYPE')
            require(0<before[5]<=512<<20 and self.hash_bytes+before[5]<=512<<20,'HASH_BYTES_LIMIT')
            h=hashlib.sha256();count=0
            while count<before[5]:
                self.check();block=os.read(fd,min(65536,before[5]-count));require(bool(block),'FILE_TRUNCATED')
                count+=len(block);self.hash_bytes+=len(block);h.update(block)
            self.check();require(before==_identity(os.fstat(fd)),'FILE_DRIFT')
            require((canonical,aliases,parents)==_resolve(path,self.policy) and before==_identity(canonical.lstat()),'ALIAS_DRIFT')
        finally:os.close(fd)
        row={'requested':str(path),'canonical':key,'aliases':aliases,'parents':parents,'identity':before,
             'bytes':count,'sha256':h.hexdigest(),'role':role,'provenance':'OBSERVED_NOT_APPROVED','observations':[]}
        self.files[key]=row;self.observe(path,role,row,aliases,parents)
        self.emit('reports/files/file-%02d.json'%len(self.files),row)
        self.initial_reports.add(key) # only after the write AND final live guard return
        return row
    def read(self,row):
        require(row['bytes']<=65536,'METADATA_LIMIT');self.check();fd=os.open(row['canonical'],os.O_RDONLY|os.O_NOFOLLOW)
        try:
            require(_identity(os.fstat(fd))==row['identity'],'FILE_DRIFT');data=os.read(fd,65537)
            self.check();require(_identity(os.fstat(fd))==row['identity'],'FILE_DRIFT')
        finally:os.close(fd)
        require(len(data)==row['bytes'],'METADATA_LIMIT');return _text(data)
    def query(self,reader,args):
        require(reader in ('elf','package') and self.queries<256,'QUERY_LIMIT');self.check()
        path=self.policy['readers'][reader]
        before=self.file(path,'metadata-reader-'+reader);self.queries+=1
        stage=self.stage/('query-%03d'%self.queries);stage.mkdir(mode=0o700)
        owners=_owners(stage)
        def checkpoint():_live(owners);self.check();_live(owners)
        try:
            code,out,err=self.base['_owned_tool']((before['canonical'],*args),{'LANG':'C','LC_ALL':'C'},
                                                stage,min(300,self.clock.remaining()),checkpoint)
            self.executions+=1
        except BaseException:self.execution_available=False;raise
        self.check();after=self.file(path,'metadata-reader-'+reader);require(before==after,'READER_DRIFT')
        self.emit('reports/queries/query-%03d.json'%self.queries,{'reader':reader,'argv':list(args),'exit':code,
                  'stdout_bytes':len(out),'stderr_bytes':len(err),'stdout_sha256':hashlib.sha256(out).hexdigest()})
        require(code==0,'METADATA_READER_NONZERO');return out
    def provenance(self,row):
        try:return self.package(row)
        except Failure as error:
            if str(error) not in ('PACKAGE_OWNER_FORMAT','PACKAGE_VERSION_FORMAT','PACKAGE_MANIFEST_POLICY',
                                  'METADATA_READER_NONZERO','METADATA_LIMIT','METADATA_ENCODING'):raise
            return {'availability':'UNAVAILABLE','cause':str(error)}
        except self.base['Pending'] as error:
            if str(error)!='OFFLINE_TOOL_OUTPUT_BOUND':raise
            return {'availability':'UNAVAILABLE','cause':'PACKAGE_METADATA_OUTPUT_LIMIT'}
    def package(self,row):
        path=self.policy['readers']['package']
        if not path.exists():return {'availability':'UNAVAILABLE','cause':'PACKAGE_READER_MISSING'}
        value=_text(self.query('package',('-S',row['canonical'])))
        lines=value.splitlines();matches=[]
        for line in lines:
            package,sep,target=line.partition(': ')
            require(sep and re.fullmatch(r'[a-z0-9][a-z0-9+.:\-]*',package) and target==row['canonical'],'PACKAGE_OWNER_FORMAT')
            matches.append(package)
        if len(matches)!=1:return {'availability':'UNAVAILABLE','cause':'PACKAGE_OWNER_AMBIGUOUS'}
        package=matches[0]
        if package not in self.packages:
            fields=_text(self.query('package',('-W','-f=${binary:Package}\t${Version}\t${Architecture}\n',package))).strip().split('\t')
            require(len(fields)==3 and fields[0]==package and all(re.fullmatch(r'[A-Za-z0-9+_.:~\-]{1,255}',v) for v in fields),'PACKAGE_VERSION_FORMAT')
            manifest,directories=_manifest(self.query('package',('-L',package)))
            self.packages[package]={'package':package,'version':fields[1],'architecture':fields[2],
                                    'manifest':manifest,'directory_markers':directories,'scope':'OBSERVED_PACKAGE_METADATA_NOT_PAYLOAD_APPROVAL'}
        return self.packages[package]

def _run(s):
    policy=s.policy;require(policy['scope'] in ('PUBLIC_METADATA_DATA','CONTROLLED_UNIT_DATA'),'DATA_SCOPE')
    s.stage=Path(tempfile.mkdtemp(prefix='tool-metadata-',dir=s.output.parent)).absolute();s.stage_owners=_owners(s.stage)
    fixed={**policy['tools'],'projector-own-modules':policy['jdk']/'lib/modules',
           'projector-release':policy['jdk']/'release','os-release':policy['os_release']}
    require(policy['tools']['projector']==policy['jdk']/'bin/jimage','PROJECTOR_TOOL_ROOT')
    queue=[]
    for role,path in fixed.items():
        if not path.exists():s.roles[role]={'availability':'UNAVAILABLE','cause':'FIXED_ROLE_MISSING'};s.missing.add(role);continue
        row=s.file(path,role);s.roles[role]=row
        row['package']={'availability':'UNAVAILABLE','cause':'PACKAGE_METADATA_NOT_COMPLETED'}
        row['package']=s.provenance(row)
        if row['package'].get('availability')=='UNAVAILABLE':s.missing.add('package-provenance')
        if role in ('signer','projector'):queue.append(row)
        if role in ('projector-release','os-release'):
            keys=('JAVA_VERSION','IMPLEMENTOR','OS_ARCH') if role=='projector-release' else ('ID','VERSION_ID','VERSION_CODENAME')
            values={}
            for line in s.read(row).splitlines():
                key,sep,value=line.partition('=')
                if key in keys and sep:
                    value=value.strip('"');require(re.fullmatch(r'[A-Za-z0-9 ._+\-]{1,255}',value) is not None,'PUBLIC_METADATA_VALUE')
                    require(key not in values,'PUBLIC_METADATA_DUPLICATE');values[key]=value
            row['public_fields']=values
    visited=set()
    while queue:
        row=queue.pop(0)
        if row['canonical'] in visited:continue
        visited.add(row['canonical']);reader=policy['readers']['elf']
        if not reader.exists():
            row['elf']={'availability':'UNAVAILABLE','cause':'ELF_READER_MISSING'}
            s.missing.add('elf-reader');continue
        row['elf']={'availability':'UNAVAILABLE','cause':'ELF_METADATA_NOT_COMPLETED'}
        info=_elf(s.query('elf',('--wide','--file-header','--program-headers','--dynamic',row['canonical'])))
        names=[('interpreter',info['interpreter'])] if info['interpreter'] else []
        names.extend(('needed',name) for name in info['needed'])
        for kind,name in names:
            require(len(s.edges)<256,'GRAPH_EDGE_LIMIT');candidates={}
            paths=(Path(name),) if kind=='interpreter' else tuple(d/name for d in policy['search'])
            for path in paths:
                require(path.is_absolute() and len(str(path))<=4096 and _within(path,policy['roots']),'PATH_POLICY')
                if path.exists():
                    canonical,aliases,parents=_resolve(path,policy);candidates[str(canonical)]=path
            if len(candidates)!=1:
                s.edges.append({'from':row['canonical'],'kind':kind,'name':name,'availability':'UNAVAILABLE',
                                'cause':'DEPENDENCY_AMBIGUOUS' if candidates else 'DEPENDENCY_MISSING'})
                s.missing.add('candidate-elf-resolution');continue
            target=s.file(next(iter(candidates.values())),'candidate-elf-'+kind)
            s.edges.append({'from':row['canonical'],'kind':kind,'name':name,'canonical':target['canonical'],
                            'requested':str(next(iter(candidates.values()))),'role':'candidate-elf-'+kind,
                            'scope':'CANDIDATE_NOT_LOADER_AUTHORITY'})
            target['package']={'availability':'UNAVAILABLE','cause':'PACKAGE_METADATA_NOT_COMPLETED'}
            target['package']=s.provenance(target)
            if target['package'].get('availability')=='UNAVAILABLE':s.missing.add('package-provenance')
            queue.append(target)
        # A failed candidate-path check must not publish its rejected raw path
        # through the final snapshot; leave the finite incomplete marker instead.
        row['elf']=info
    s.emit('reports/roles.json',s.roles);s.emit('reports/candidate-graph.json',s.edges)
    runner={}
    for key in ('ImageOS','ImageVersion','ImageRelease'):
        value=policy['runner'].get(key)
        if value is not None:
            require(type(value) is str and re.fullmatch(r'[A-Za-z0-9._/:+\-]{1,255}',value),'RUNNER_METADATA_VALUE');runner[key]=value
    s.emit('reports/runner.json',{'observed':runner,'external_approval':'UNAVAILABLE',
           'target_modules':'NOT_ACQUIRED','prior_public_runs':['40151639608','40152967008'],
           'prior_run_join':'NOT_ESTABLISHED_FOR_CURRENT_TOOLS'})

def _deadline(s,status):
    if s.clock.clock()>=s.clock.deadline:
        if status['cause'] not in (None,'COLLECTOR_DEADLINE'):status.setdefault('prior_cause',status['cause'])
        status.update(outcome='PARTIAL_FAILURE',cause='COLLECTOR_DEADLINE')

def _finish(s,status):
    """One finite guarded terminal adapter around the unchanged writer.

    At most two <=4096B status images use the existing reserved65536B terminal
    allowance. A late observed expiry corrects the terminal atomically once;
    no tool retry, second inventory, deadline reset or general writer follows.
    """
    write=s.e.bytes;terminal_data=None
    def encoded():
        data=(json.dumps(status,sort_keys=True,ensure_ascii=True)+'\n').encode('ascii')
        require(len(data)<=4096,'TERMINAL_STATUS_LIMIT');return data
    def guarded(name,data,terminal=False):
        nonlocal terminal_data
        _live(s.output_owners);_deadline(s,status)
        if terminal:data=encoded()
        result=write(name,data,terminal);_live(s.output_owners)
        if terminal:terminal_data=data
        _deadline(s,status);return result
    s.e.bytes=guarded
    try:_live(s.output_owners);_deadline(s,status);s.e.finish(status)
    finally:s.e.bytes=write
    _live(s.output_owners);_deadline(s,status);data=encoded()
    if data!=terminal_data:
        require(status['cause']=='COLLECTOR_DEADLINE','TERMINAL_STATUS_LIMIT')
        _live(s.output_owners);write('reports/status-correction.json',data,terminal=True);_live(s.output_owners)
        # Both names are inside this still-owned output. No callback intervenes
        # between ownership validation and the atomic terminal replacement.
        os.replace(s.output/'reports/status-correction.json',s.output/'reports/status.json')
        _live(s.output_owners)

def _failure_cause(s,error):
    known=(type(error) is Failure and str(error) in CAUSES) or (type(error) is s.writer_module['Failure'] and
          str(error) in ('COLLECTOR_DEADLINE','OUTPUT_TOTAL_LIMIT','OUTPUT_LEAF_LIMIT')) or (
          type(error) is s.base['Pending'] and str(error) in ('OFFLINE_TOOL_OUTPUT_BOUND','OFFLINE_TOOL_DEADLINE'))
    return str(error) if known and re.fullmatch(r'[A-Z_]{3,64}',str(error)) else (
        'DATA_IO' if isinstance(error,OSError) else 'DATA_CANCELLED_OR_INVALID')

def _acquire(output,policy,cancel=lambda:None,clock=monotonic):
    """Private DATA engine; fixture policies have no qualifying consumer."""
    s=_Session(output,policy,cancel,clock);cause=None;cleanup='COMPLETE';snapshots=0
    try:_run(s)
    except BaseException as error:cause=_failure_cause(s,error)
    finally:
        try:
            # Initial hash observations remain immutable. Serialize each reached
            # canonical record only after enrichment (also on partial failure),
            # through the same guarded/chunked writer and original deadline.
            for index,row in enumerate(s.files.values(),1):
                record=dict(row)
                record.setdefault('package',{'availability':'NOT_QUERIED'})
                record.setdefault('elf',{'availability':'NOT_QUERIED'})
                s.emit('reports/final-files/file-%02d.json'%index,{
                    'schema':'TOOL_FILE_METADATA_SNAPSHOT_1',
                    'initial_report':'reports/files/file-%02d.json'%index if row['canonical'] in s.initial_reports else None,
                    'initial_report_availability':'COMPLETE' if row['canonical'] in s.initial_reports else 'UNAVAILABLE',
                    'record':record})
                snapshots+=1
        except BaseException as error:
            if cause is None:cause=_failure_cause(s,error)
        if s.stage is not None:
            try:_live(s.stage_owners);s.base['_remove'](s.stage);s.stage=None;s.stage_owners=None
            except BaseException:cleanup='OWNERSHIP_OR_CLEANUP_UNAVAILABLE'
    status={'schema':'TOOL_ACQUISITION_DATA_1','scope':policy['scope'],'qualified':False,'cases':0,
            'candidateExecutions':0,'targetExecutions':0,'metadataQueries':s.queries,
            'metadataExecutions':s.executions if s.execution_available else None,'files':len(s.files),
            'finalFileSnapshots':snapshots,
            'hashed_bytes':s.hash_bytes,'outcome':'PARTIAL_UNSEALED' if cause is None else 'PARTIAL_FAILURE',
            'cause':cause,'cleanup':cleanup,'missing':sorted(s.missing)}
    try:_finish(s,status)
    except BaseException:
        _deadline(s,status);status.update(outcome='PARTIAL_OUTPUT_UNAVAILABLE',evidence='UNAVAILABLE')
        if status['cause']!='COLLECTOR_DEADLINE':status['cause']='OUTPUT_OWNER_OR_LIMIT'
    return status

def collect(output):
    policy=dict(POLICY);policy['runner']={k:os.environ[k] for k in ('ImageOS','ImageVersion','ImageRelease') if k in os.environ}
    try:return _acquire(output,policy)
    except BaseException:
        # Authenticated writer/fresh output may be unavailable before it can
        # create evidence. Safe stdout is honest; no fallback writer or trace.
        return {'schema':'TOOL_ACQUISITION_DATA_1','scope':'PUBLIC_METADATA_DATA','qualified':False,'cases':0,
                'candidateExecutions':0,'targetExecutions':0,'metadataQueries':None,'metadataExecutions':None,
                'outcome':'PARTIAL_DEPENDENCY_UNAVAILABLE','cause':'COLLECTOR_INITIALIZATION_UNAVAILABLE',
                'evidence':'NOT_CREATED','missing':['authenticated-collector-or-fresh-output']}

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',required=True,type=Path)
    args=parser.parse_args();result=collect(args.output);print(json.dumps(result,sort_keys=True))
    raise SystemExit(0 if result['outcome']=='PARTIAL_UNSEALED' else 2)
