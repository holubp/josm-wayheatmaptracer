"""Distinct signed OLDGNU component; operational limits are not case Work.

Only independently installed SOURCE profiles could issue SOURCE components.
The registry is empty. UNIT records/signers demonstrate owned DATA mechanics.
Neither scope is a full-image/HOST/runtime receipt or native semantics proof.
"""
from pathlib import Path
import gzip
import hashlib
import json
import os
import stat
import tempfile
from time import monotonic

SCHEMA='SIGNED_OLDGNU_IMAGE_1'
HELPER_SHA256='16cfafddfec78c09a61551c66920df8663324ac9fc1afc5da63ed20159828c67'
REGISTERED_PROFILES={}
MAX_PROFILE=1<<20
_UNIT=object();_SOURCE=object();_HELPER=None

class Pending(RuntimeError):
    """Specific absent or mismatched image premise."""

def require(ok,cause):
    if not ok:raise Pending(cause)

def _helper():
    global _HELPER
    if _HELPER is None:
        path=Path(__file__).with_name('b-boot-bundle-verifier.py');data=path.read_bytes()
        require(len(data)<=65536 and hashlib.sha256(data).hexdigest()==HELPER_SHA256,'HELPER_SOURCE_DRIFT')
        ns={'__name__':'gnu_helper','__file__':str(path)};exec(compile(data,str(path),'exec'),ns);_HELPER=ns
    return _HELPER

def _integer(v,lo,hi):return type(v) is int and lo<=v<=hi
def _text(v,limit=255):return type(v) is str and 0<len(v)<=limit and v.isascii()
def _digest(v):return type(v) is str and len(v)==64 and all(c in '0123456789abcdef' for c in v)
def _name(name):
    require(_text(name,100),'MEMBER_NAME_BOUND');require(_helper()['_name'](name.encode())==name,'MEMBER_CANONICAL')
    return name

def _profile(data):
    require(type(data) is bytes and 0<len(data)<=MAX_PROFILE,'PROFILE_BOUND')
    # Bound structure before JSON constructs containers; duplicate keys refuse.
    depth=0;quoted=False;escape=False
    for byte in data:
        if quoted:
            if escape:escape=False
            elif byte==92:escape=True
            elif byte==34:quoted=False
        elif byte==34:quoted=True
        elif byte in (91,123):depth+=1;require(depth<=16,'PROFILE_DEPTH')
        elif byte in (93,125):depth-=1;require(depth>=0,'PROFILE_JSON')
    require(depth==0 and not quoted,'PROFILE_JSON')
    def pairs(values):
        require(len(values)<=1024,'PROFILE_OBJECT_BOUND');out={}
        for key,value in values:require(key not in out,'PROFILE_DUPLICATE');out[key]=value
        return out
    def number(value):raise Pending('PROFILE_NUMBER')
    try:p=json.loads(data,object_pairs_hook=pairs,parse_float=number,parse_constant=number)
    except (ValueError,UnicodeError,RecursionError) as error:raise Pending('PROFILE_JSON') from error
    require(type(p) is dict and p.get('schema')==SCHEMA and _text(p.get('id')),'PROFILE_SCHEMA')
    require(p.get('approval') in ('UNIT_ONLY','INDEPENDENT_SOURCE_PROFILE_1'),'PROFILE_APPROVAL')
    require(p.get('scope') in ('MODULES_ONLY','ALL_REGULAR_PAYLOAD'),'PROFILE_SCOPE')
    root=_name(p.get('root'));require('/' not in root,'PROFILE_ROOT')
    require(_text(p.get('source')) and _text(p.get('build')),'SOURCE_BUILD_ABSENT')
    require(type(p.get('pending')) is list and 0<len(p['pending'])<=64 and all(_text(v) for v in p['pending']),
            'PENDING_FRONTIER_ABSENT')
    a=p.get('archive');s=p.get('signature');limits=p.get('limits')
    require(type(a) is dict and _integer(a.get('bytes'),1,512<<20) and _digest(a.get('sha256')) and
            _integer(a.get('tar_bytes'),1024,2<<30) and a['tar_bytes']%512==0 and
            a.get('compression') in ('none','gzip'),'ARCHIVE_INPUT_BOUND')
    require(type(s) is dict and _integer(s.get('bytes'),1,65536) and _digest(s.get('sha256')) and
            _text(s.get('primary')) and len(s['primary'])==40 and
            all(c in '0123456789ABCDEF' for c in s['primary']),'SIGNATURE_INPUT_BOUND')
    require(type(limits) is dict and _integer(limits.get('headers'),1,1024) and
            _integer(limits.get('member_bytes'),1,512<<20) and _integer(limits.get('output_bytes'),1,1<<30),
            'EXTRACTION_LIMITS')
    members=p.get('members');require(type(members) is list and 0<len(members)<=limits['headers'],'MEMBER_COUNT_BOUND')
    by_name={};total=0
    for m in members:
        require(type(m) is dict,'MEMBER_RECORD');name=_name(m.get('name'))
        require(name==root or name.startswith(root+'/'),'MEMBER_ROOT')
        require(name not in by_name,'PROFILE_MEMBER_DUPLICATE');by_name[name]=m
        require(m.get('kind') in ('directory','regular','symlink') and m.get('policy') in ('materialize','exclude') and
                type(m.get('required')) is bool and _integer(m.get('mode'),0,0o777) and
                _integer(m.get('bytes'),0,limits['member_bytes']),'MEMBER_POLICY')
        require(m['kind']=='regular' or m['bytes']==0,'NONREGULAR_BODY')
        if m['policy']=='exclude':
            require(not m['required'] and _text(m.get('reason')),'EXCLUSION_REASON')
        if m['kind']=='symlink':
            require(m['policy']=='exclude' and name.startswith(root+'/legal/') and
                    _text(m.get('target'),100) and '\0' not in m['target'],'LEGAL_LINK_POLICY')
        else:require('target' not in m,'NONLINK_TARGET')
        if m['kind']=='directory':require(m['policy']=='materialize','DIRECTORY_POLICY')
        if m['kind']=='regular' and m['policy']=='materialize':total+=m['bytes']
    require(total<=limits['output_bytes'],'OUTPUT_BOUND')
    require(root in by_name and by_name[root]['kind']=='directory','ROOT_DIRECTORY_ABSENT')
    for name in by_name:
        parts=name.split('/')
        for i in range(1,len(parts)):
            parent='/'.join(parts[:i]);require(parent not in by_name or by_name[parent]['kind']=='directory','ANCESTOR_COLLISION')
    modules=_name(p.get('modules'));require(modules in by_name and by_name[modules]['kind']=='regular' and
        by_name[modules]['policy']=='materialize' and by_name[modules]['required'],'MODULES_REQUIRED')
    if p['scope']=='MODULES_ONLY':
        require(all(m['name']==modules or m['kind']!='regular' or m['policy']=='exclude' for m in members),'MODULES_SCOPE')
    else:require(all(m['kind']!='regular' or m['policy']=='materialize' for m in members),'REGULAR_PAYLOAD_INCOMPLETE')
    return p,by_name

def _string(raw):
    head,sep,tail=raw.partition(b'\0');require(not any(tail),'GNU_STRING_PADDING')
    try:return head.decode('ascii')
    except UnicodeError as error:raise Pending('GNU_STRING_ENCODING') from error

def _octal(raw,optional=False):
    if optional and not raw.strip(b'\0 '):return 0
    return _helper()['_octal'](raw)

def _ancestors(directory,owners):
    # In insertion order each parent was registered before its children. Check
    # it with lstat before any descendant pathname could follow a replacement.
    for name,key in owners:
        info=(directory/name).lstat()
        require(stat.S_ISDIR(info.st_mode) and (info.st_dev,info.st_ino)==key,'IMAGE_ANCESTOR_DRIFT')

def _root_identity(stage):
    # No callbacks during capture; outermost-first checks never follow an
    # already rejected ancestor. Concurrent writers are outside this profile.
    owners=[]
    for path in reversed((stage,*stage.parents)):
        info=path.lstat();require(stat.S_ISDIR(info.st_mode),'IMAGE_OWNER_DRIFT')
        owners.append((path,(info.st_dev,info.st_ino)))
    return tuple(owners)

def _root_live(owners):
    require(owners is not None,'IMAGE_ROOT_IDENTITY_UNAVAILABLE')
    for path,key in owners:
        info=path.lstat()
        require(stat.S_ISDIR(info.st_mode) and (info.st_dev,info.st_ino)==key,'IMAGE_OWNER_DRIFT')

def _remove_root(stage,owners):
    b=_helper();require(owners is not None,'IMAGE_ROOT_IDENTITY_UNAVAILABLE')
    require(owners[-1][0]==stage,'IMAGE_ROOT_PATH_DRIFT');_root_live(owners)
    # No callback between the shared ownership guard and the old remover.
    # Ownership loss retains both the replacement and any externally moved
    # original; this component must not search for the latter's new pathname.
    b['_remove'](stage)

def _directory(directory,name,owners):
    _ancestors(directory,owners.items());path=directory/name
    if name not in owners:
        path.mkdir(mode=0o700)  # an existing unregistered directory is not owned
        info=path.lstat();owners[name]=(info.st_dev,info.st_ino)
    _ancestors(directory,owners.items());return path

def _parents(directory,name,owners):
    parts=name.split('/')
    for count in range(1,len(parts)):_directory(directory,'/'.join(parts[:count]),owners)
    return directory/name

class _Closing:
    """Close one owned stream, preserving an already active primary failure."""
    __slots__=('stream',)
    def __init__(self,stream):self.stream=stream
    def __enter__(self):return self.stream
    def __exit__(self,kind,error,tb):
        if self.stream is not None:
            try:self.stream.close()
            except BaseException:
                if kind is None:raise

def _extract(archive,directory,p,policy,owners,checkpoint):
    b=_helper();seen=set();identities=[];excluded=[];headers=[];expanded=0;output=0
    with _Closing(archive.open('rb')) as original:
        stream=gzip.GzipFile(fileobj=original) if p['archive']['compression']=='gzip' else original
        with _Closing(stream if stream is not original else None):
            def read(size):
                nonlocal expanded
                require(0<=size<=65536 and expanded+size<=p['archive']['tar_bytes'],'TAR_EXPANSION_BOUND')
                checkpoint();data=stream.read(size);expanded+=len(data);require(len(data)==size,'TAR_TRUNCATED');return data
            while True:
                h=read(512)
                if not any(h):
                    require(not any(read(512)),'TAR_END_MARKER')
                    while expanded<p['archive']['tar_bytes']:
                        require(not any(read(min(65536,p['archive']['tar_bytes']-expanded))),'TAR_TRAILING_DATA')
                    checkpoint();require(not stream.read(1),'TAR_EXPANSION_BOUND');break
                require(len(seen)<p['limits']['headers'],'TAR_HEADER_BOUND')
                require(h[257:265]==b'ustar  \0','OLDGNU_FORMAT')
                require(_octal(h[148:156])==sum(h[:148])+8*32+sum(h[156:]),'TAR_CHECKSUM')
                require(not any(h[369:512]),'GNU_EXTENSION_UNSUPPORTED')
                for raw in (h[108:116],h[116:124],h[136:148]):_octal(raw)
                # OLDGNU atime/ctime are numeric metadata, never a USTAR prefix.
                for raw in (h[329:337],h[337:345],h[345:357],h[357:369]):_octal(raw,True)
                _string(h[265:297]);_string(h[297:329])
                name=b['_name'](h[:100]);require(name not in seen and name in policy,'TAR_DUPLICATE_OR_EXTRA')
                m=policy[name];kind={b'0':'regular',b'\0':'regular',b'5':'directory',b'2':'symlink'}.get(h[156:157])
                require(kind is not None and kind==m['kind'],'TAR_KIND')
                size=_octal(h[124:136]);mode=_octal(h[100:108]);target=_string(h[157:257])
                require(size==m['bytes'] and mode==m['mode'],'TAR_MEMBER_POLICY_DRIFT')
                require(target==m['target'] if kind=='symlink' else not target,'TAR_LINK_TARGET')
                require(expanded+((size+511)//512)*512<=p['archive']['tar_bytes']-1024,'TAR_DECLARED_EXPANSION')
                require(kind=='regular' or size==0,'TAR_NONREGULAR_BODY');seen.add(name)
                path=None;hbody=hashlib.sha256()
                if m['policy']=='materialize':
                    path=_parents(directory,name,owners)
                    if kind=='directory':
                        path=_directory(directory,name,owners)
                    else:
                        require(output+size<=p['limits']['output_bytes'],'OUTPUT_BOUND');output+=size
                if kind=='regular':
                    fd=os.fdopen(os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600),'wb') if path is not None else None
                    with _Closing(fd):
                        remaining=size
                        while remaining:
                            block=read(min(65536,remaining));hbody.update(block);remaining-=len(block)
                            if fd is not None:fd.write(block)
                    if size%512:require(not any(read(512-size%512)),'TAR_PADDING')
                    if path is not None:
                        outmode=0o555 if mode&0o111 else 0o444;os.chmod(path,outmode,follow_symlinks=False)
                        identities.append((name,size,hbody.hexdigest(),mode,outmode,hashlib.sha256(h).hexdigest(),
                                           b['_identity'](path.lstat())))
                if m['policy']=='exclude':excluded.append((name,kind,m.get('target'),m['reason']))
                headers.append((name,kind,size,mode,hashlib.sha256(h).hexdigest(),
                                hbody.hexdigest() if kind=='regular' else None,m['policy']))
            require(seen==set(policy),'TAR_MISSING_MEMBER')
            # All implicit/explicit owned directories receive the recorded
            # readonly transformation only after their complete child creation.
            for path in sorted(directory.rglob('*'),reverse=True):
                checkpoint();info=path.lstat();require(not stat.S_ISLNK(info.st_mode),'OWNED_IMAGE_LINK')
                relative=path.relative_to(directory).as_posix()
                if stat.S_ISDIR(info.st_mode):
                    require(relative in owners,'OWNED_DIRECTORY');os.chmod(path,0o555,follow_symlinks=False)
                else:require(stat.S_ISREG(info.st_mode) and any(v[0]==relative for v in identities),'OWNED_IMAGE_EXTRA')
            os.chmod(directory,0o555,follow_symlinks=False)
            return tuple(identities),tuple(excluded),tuple(headers)

class _Image:
    __slots__=('stage','directory','profile_id','profile_sha256','archive_sha256','scope','members','exclusions','pending',
               'origin','closed','busy','stage_key','directory_key','headers','ancestors','root_owners')
    def __init__(self,stage,p,digest,members,exclusions,headers,origin,stage_key,directory_key,owners,root_owners):
        self.stage=stage;self.directory=stage/'image';self.profile_id=p['id'];self.profile_sha256=digest
        self.archive_sha256=p['archive']['sha256'];self.scope=p['scope'];self.members=members
        self.exclusions=exclusions;self.pending=tuple(p['pending']);self.origin=origin;self.closed=False;self.busy=False
        self.headers=headers;self.stage_key=stage_key;self.directory_key=directory_key
        self.ancestors=tuple(owners.items());self.root_owners=root_owners
    def close(self):
        if not self.closed:self.closed=True;_remove_root(self.stage,self.root_owners)
    def __enter__(self):_live(self);return self
    def __exit__(self,kind,error,tb):
        try:self.close()
        except BaseException:
            if kind is None:raise

def _live(image):
    require(type(image) is _Image and image.origin in (_UNIT,_SOURCE),'SIGNED_IMAGE_RECEIPT_REQUIRED')
    require(not image.closed,'SIGNED_IMAGE_CLOSED')
    _root_live(image.root_owners)
    for path,key in ((image.stage,image.stage_key),(image.directory,image.directory_key)):
        info=path.lstat();require(stat.S_ISDIR(info.st_mode) and (info.st_dev,info.st_ino)==key,'IMAGE_OWNER_DRIFT')
    _ancestors(image.directory,image.ancestors)
def _member_live(image,record):
    info=(image.directory/record[0]).lstat()
    require(stat.S_ISREG(info.st_mode) and _helper()['_identity'](info)==record[6] and
            stat.S_IMODE(info.st_mode)==record[4],'IMAGE_MEMBER_DRIFT')
def _guard(image,callback):_live(image);callback();_live(image)
def inspect_unit(image,cancel=lambda:None):
    _live(image);require(image.origin is _UNIT,'UNIT_IMAGE_REQUIRED');_guard(image,cancel)
    return {'qualified':False,'scope':image.scope,'profile':image.profile_id,'archive_sha256':image.archive_sha256,
            'members':tuple({'name':v[0],'bytes':v[1],'sha256':v[2],'archive_mode':v[3],'output_mode':v[4],
                             'header_sha256':v[5]} for v in image.members),
            'headers':image.headers,'exclusions':image.exclusions,'pending':image.pending}
def borrow_member(image,name,scope,cancel=lambda:None):
    _live(image);require(not image.busy,'IMAGE_REENTRANT_BORROW');image.busy=True
    try:
        _guard(image,cancel);require(scope==image.scope,'IMAGE_SCOPE_MISMATCH')
        record=next((v for v in image.members if v[0]==name),None);require(record is not None,'IMAGE_MEMBER_UNAVAILABLE')
        path=image.directory/name
        def checkpoint():_guard(image,cancel);_member_live(image,record)
        checkpoint();require(_helper()['file_digest'](path,record[1],checkpoint)==record[2],'IMAGE_MEMBER_DRIFT')
        checkpoint();return path
    finally:image.busy=False
def require_runtime(image):
    _live(image);raise Pending('SOURCE_NATIVE_PROFILE_STARTUP_RUNTIME_UNSEALED')

def _verify(data,p,policy,archive,signature,parent,signer,origin,cancel):
    b=_helper();deadline=monotonic()+60;stage=None;stage_key=None;directory=None;directory_key=None;result=None;primary=None;owners={};root_owners=None
    def owned():
        if stage is not None:_root_live(root_owners)
        for path,key in ((stage,stage_key),(directory,directory_key)):
            if path is not None:
                info=path.lstat();require(stat.S_ISDIR(info.st_mode) and (info.st_dev,info.st_ino)==key,'IMAGE_STAGE_DRIFT')
        if directory is not None:_ancestors(directory,owners.items())
    def checkpoint():
        require(monotonic()<deadline,'GNU_IMAGE_DEADLINE');owned();cancel()
        require(monotonic()<deadline,'GNU_IMAGE_DEADLINE');owned()
    try:
        checkpoint();stage=Path(tempfile.mkdtemp(prefix='signed-gnu-',dir=parent)).absolute()
        root_owners=_root_identity(stage);stage_key=root_owners[-1][1];opened=stage/'archive';sig=stage/'signature'
        b['_copy_opened'](archive,opened,p['archive']['bytes'],p['archive']['sha256'],checkpoint)
        b['_copy_opened'](signature,sig,p['signature']['bytes'],p['signature']['sha256'],checkpoint)
        checkpoint();code,text=signer(opened,sig,p['signature']['primary'],checkpoint,stage)
        b['signature_status'](code,text,p['signature']['primary']);checkpoint()
        def recheck():
            require(b['file_digest'](opened,p['archive']['bytes'],checkpoint)==p['archive']['sha256'],'SIGNED_ARCHIVE_MUTATION')
            require(b['file_digest'](sig,p['signature']['bytes'],checkpoint)==p['signature']['sha256'],'SIGNED_SIGNATURE_MUTATION')
        recheck();directory=stage/'image';directory.mkdir(mode=0o700);info=directory.lstat();directory_key=(info.st_dev,info.st_ino)
        owners['']=directory_key
        members,excluded,headers=_extract(opened,directory,p,policy,owners,checkpoint);recheck();checkpoint()
        result=_Image(stage,p,hashlib.sha256(data).hexdigest(),members,excluded,headers,origin,stage_key,directory_key,owners,root_owners)
        checkpoint()
        for record in members:_member_live(result,record)
        return result
    except BaseException as error:primary=error;raise
    finally:
        if primary is not None and stage is not None:
            if result is not None:result.closed=True
            try:_remove_root(stage,root_owners)
            except BaseException:pass

def verify_unit(profile,archive,signature,parent,signature_runner,cancel=lambda:None):
    p,policy=_profile(profile);require(p['approval']=='UNIT_ONLY','UNIT_PROFILE_REQUIRED')
    def signer(a,s,f,checkpoint,stage):
        checkpoint();result=signature_runner(a,s,f);checkpoint();return result
    return _verify(profile,p,policy,archive,signature,parent,signer,_UNIT,cancel)

def verify_source(key,archive,signature,parent,cancel=lambda:None):
    require(type(key) is str and key in REGISTERED_PROFILES,'INDEPENDENT_IMAGE_PROFILE_ABSENT')
    b=_helper();path,size,digest=REGISTERED_PROFILES[key];require(_integer(size,1,MAX_PROFILE),'PROFILE_BOUND')
    b['_protected'](path);require(b['file_digest'](path,size,cancel)==digest,'PROFILE_IDENTITY_DRIFT')
    data=Path(path).read_bytes();p,policy=_profile(data)
    require(p['id']==key and p['approval']=='INDEPENDENT_SOURCE_PROFILE_1','SOURCE_PROFILE_REQUIRED')
    require(p['source']==b['SOURCE_COMMIT'] and p['build']==b['BUILD_COMMIT'] and _digest(p.get('approval_artifact')),
            'SOURCE_PROFILE_PROVENANCE_UNSEALED')
    require(type(p.get('source_chain')) is dict,'SOURCE_CHAIN_ABSENT');b['source_preparation'](p['source_chain'],cancel)
    signature_id=p.get('signature_profile')
    require(type(signature_id) is str and signature_id in b['REGISTERED_SIGNATURE_PROFILES'],'TRUSTED_SIGNER_PROFILE_ABSENT')
    profile=b['REGISTERED_SIGNATURE_PROFILES'][signature_id]
    def signer(a,s,f,checkpoint,stage):return _signature(profile,a,s,f,stage,checkpoint)
    return _verify(data,p,policy,archive,signature,parent,signer,_SOURCE,cancel)

def _signature(profile,archive,signature,fingerprint,stage,cancel):
    b=_helper();protocol=profile.get('protocol')
    if protocol=='GPGV_STATUS_FD1_PRIMARY_1':return b['_real_signature'](profile,archive,signature,fingerprint,stage,cancel)
    require(protocol=='GPGV_STATUS_FD1_PRIMARY_OWNED_HOME_1','SIGNATURE_PROTOCOL_UNREVIEWED')
    return _owned_home_signature(profile,archive,signature,fingerprint,stage,cancel)

def _owned_home_signature(profile,archive,signature,fingerprint,stage,cancel):
    """GNU-only fixed-config signer, borrowing its actual issuer's stage.

    UNIT callers exercise this body without registering SOURCE authority. The
    caller owns stage rollback; this adapter owns the single new empty home.
    Directory descriptors prevent redirected-path cleanup after a bad callback.
    """
    b=_helper();require(set(profile)=={'protocol','executable','keyring','files','timeout'},'SIGNATURE_PROFILE_FIELDS')
    require(type(profile['files']) is list and 0<len(profile['files'])<=64 and
            type(profile['timeout']) in (int,float) and 0<profile['timeout']<=300,'SIGNATURE_PROFILE_BOUND')
    require(type(profile['executable']) is str and type(profile['keyring']) is str,'SIGNATURE_PROFILE_PATHS')
    for item in profile['files']:
        require(type(item) is dict and set(item)=={'path','bytes','sha256'} and _text(item['path'],4096) and
                _integer(item['bytes'],1,512<<20) and _digest(item['sha256']),'SIGNATURE_DEPENDENCY_BOUND')
    require(profile['keyring'] in {v['path'] for v in profile['files']},'TRUSTED_KEYRING_UNBOUND')
    stage=Path(stage).absolute();owners=[];stage_fd=None;home_fd=None;home_key=None;created=False;primary=None
    require(Path(archive).absolute()==stage/'archive' and Path(signature).absolute()==stage/'signature','SIGNATURE_INPUT_OWNER')
    for path in reversed((stage,*stage.parents)):
        info=path.lstat();require(stat.S_ISDIR(info.st_mode),'SIGNATURE_STAGE_ANCESTOR')
        owners.append((path,(info.st_dev,info.st_ino)))
    stage_key=owners[-1][1];stage_uid=stage.lstat().st_uid
    require(stage_uid==os.geteuid() and stat.S_IMODE(stage.lstat().st_mode)==0o700,'SIGNATURE_STAGE_OWNER')
    home=stage/'gnupg'
    def owned():
        for path,key in owners:
            info=path.lstat();require(stat.S_ISDIR(info.st_mode) and (info.st_dev,info.st_ino)==key,'SIGNATURE_STAGE_DRIFT')
        info=os.fstat(stage_fd)
        require((info.st_dev,info.st_ino)==stage_key and info.st_uid==stage_uid and
                stat.S_IMODE(info.st_mode)==0o700,'SIGNATURE_STAGE_DRIFT')
        if created:
            info=os.stat('gnupg',dir_fd=stage_fd,follow_symlinks=False)
            require(stat.S_ISDIR(info.st_mode) and (info.st_dev,info.st_ino)==home_key and info.st_uid==stage_uid and
                    stat.S_IMODE(info.st_mode)==0o700,'SIGNATURE_HOME_DRIFT')
            require(os.fstat(home_fd).st_ino==info.st_ino,'SIGNATURE_HOME_DRIFT')
            with os.scandir(home_fd) as entries:require(next(entries,None) is None,'SIGNATURE_HOME_NONEMPTY')
    def checkpoint():owned();cancel();owned()
    try:
        stage_fd=os.open(stage,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW);checkpoint()
        b['_tool_files'](profile,checkpoint)
        os.mkdir('gnupg',mode=0o700,dir_fd=stage_fd)
        info=os.stat('gnupg',dir_fd=stage_fd,follow_symlinks=False);home_key=(info.st_dev,info.st_ino);created=True
        home_fd=os.open('gnupg',os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=stage_fd)
        checkpoint()
        code,out,err=b['_owned_tool']((profile['executable'],'--homedir',str(home),'--status-fd','1','--keyring',
                                     profile['keyring'],str(signature),str(archive)),
                                    {'LANG':'C','LC_ALL':'C'},stage,profile['timeout'],checkpoint)
        checkpoint();b['_tool_files'](profile,checkpoint);checkpoint()
        try:text=out.decode('ascii')
        except UnicodeError as error:raise Pending('SIGNATURE_STATUS_PROTOCOL') from error
        b['signature_status'](code,text,fingerprint);checkpoint();return code,text
    except BaseException as error:primary=error;raise
    finally:
        secondary=None
        if created:
            try:
                # Relative to the still-open original stage, never through a
                # pathname whose parent callback may have replaced. A changed
                # home is left to the stage owner rather than deleting a target.
                info=os.stat('gnupg',dir_fd=stage_fd,follow_symlinks=False)
                require(stat.S_ISDIR(info.st_mode) and (info.st_dev,info.st_ino)==home_key,'SIGNATURE_HOME_DRIFT')
                os.rmdir('gnupg',dir_fd=stage_fd)
            except BaseException as error:secondary=error
        for fd in (home_fd,stage_fd):
            if fd is not None:
                try:os.close(fd)
                except BaseException as error:
                    if secondary is None:secondary=error
        if primary is None and secondary is not None:raise secondary
