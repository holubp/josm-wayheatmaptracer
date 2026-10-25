"""Fixed PUBLIC_DATA boot collector. Never issues runtime/candidate authority.

Only --collect in the reviewed manual CI job acquires the supplier archive.
--prepare-key fetches the one fixed public key only. Archive executables never
run; pre-existing runner gpg/gpgv/jimage are ordinary DATA tools, not qualified
HOST/runtime proofs. Discovery observations never become strict expectations.
"""
from pathlib import Path
import argparse
import gzip
import hashlib
import json
import os
import shutil
import signal
import stat
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from time import monotonic

HELPER_SHA256='864feadb68c4483b4da5272fb989624e00b596ecbfc9cabc6dabdb80a500ce20'
FINGERPRINT='3B04D753C9050D9A5D343F39843C48A565F8F04B'
BASE='https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.12%2B7/'
NAME='OpenJDK17U-jdk_x64_linux_hotspot_17.0.12_7.tar.gz'
URLS={'archive':BASE+NAME,'signature':BASE+NAME+'.sig','metadata':BASE+NAME+'.json',
      'key':'https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x'+FINGERPRINT}
ARCHIVE_SHA256='9d4dd339bf7e6a9dcba8347661603b74c61ab2a5083ae67bf76da6285da8a778'
SOURCE='4f53ad68ec1ef59f1c20bb0c300fa6f17676597d'
BUILD='1271f10a26c47e1489a814dd2731f936a588d621'
FAMILIES=('jdk/internal/loader/BuiltinClassLoader','java/lang/ClassLoader',
          'jdk/internal/loader/ClassLoaders','jdk/internal/loader/Resource',
          'java/security/SecureClassLoader','jdk/internal/loader/URLClassPath')
BOUNDS={'compressed':512<<20,'expanded':2<<30,'headers':32768,'modules':512<<20,
        'classes':256,'class_bytes':1<<20,'leaf':65536,'output':32<<20,'seconds':600}
PREFIXES=('reports/','raw/','classes/','methods/','inventory/')

class Failure(RuntimeError):
    """Allowlisted stage/cause code; never arbitrary network/exception text."""

def require(ok,code):
    if not ok:raise Failure(code)

def dependency():
    path=Path(__file__).with_name('b-boot-bundle-verifier.py');data=path.read_bytes()
    require(hashlib.sha256(data).hexdigest()==HELPER_SHA256,'HELPER_SOURCE_DRIFT')
    namespace={'__name__':'approved_boot_data','__file__':str(path)}
    exec(compile(data,str(path),'exec'),namespace);return namespace

class Evidence:
    """Fresh generated-only output; reserve one bounded terminal leaf."""
    def __init__(self,root,bounds):
        self.root=Path(root);self.bounds=dict(bounds);self.used=0;self.files=[]
        require(not self.root.exists(),'OUTPUT_ALREADY_EXISTS');self.root.mkdir(parents=True)
        require(0<bounds['leaf']<=65536 and bounds['output']>=2*bounds['leaf'],'OUTPUT_BOUND_PROFILE')
    def bytes(self,name,data,terminal=False):
        require(name.startswith(PREFIXES) and not name.startswith('/') and
                all(p not in ('','.','..') for p in name.split('/')),'OUTPUT_NAME')
        require(len(data)<=self.bounds['leaf'],'OUTPUT_LEAF_LIMIT')
        limit=self.bounds['output']-(0 if terminal else self.bounds['leaf'])
        require(self.used+len(data)<=limit,'OUTPUT_TOTAL_LIMIT')
        target=self.root/name;target.parent.mkdir(parents=True,exist_ok=True)
        with target.open('xb') as stream:stream.write(data)
        self.used+=len(data);self.files.append({'path':name,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()})
    def chunks(self,name,blocks):
        pending=bytearray();index=0
        for block in blocks:
            view=memoryview(block)
            while view:
                n=min(len(view),self.bounds['leaf']-len(pending));pending.extend(view[:n]);view=view[n:]
                if len(pending)==self.bounds['leaf']:
                    self.bytes(name+'.part%04d'%index,bytes(pending));index+=1;pending.clear()
        if pending or index==0:self.bytes(name+'.part%04d'%index,bytes(pending))
    def json(self,name,value):
        self.chunks(name, (s.encode('ascii') for s in json.JSONEncoder(ensure_ascii=True,sort_keys=True).iterencode(value)))
    def finish(self,status):
        # Inventory excludes itself/terminal, explicitly; its own chunks still
        # consume the same output budget. Failure here cannot erase terminal.
        try:self.json('inventory/files.json',{'excludes':['inventory/files.json.*','reports/status.json'],
                                             'files':list(self.files)})
        except Failure:status.update(inventory='OUTPUT_LIMIT_PARTIAL',outcome='PARTIAL_OUTPUT')
        data=(json.dumps(status,sort_keys=True,ensure_ascii=True)+'\n').encode('ascii')
        self.bytes('reports/status.json',data,terminal=True)

class Clock:
    def __init__(self,seconds,clock=monotonic,cancel=lambda:None):
        self.clock=clock;self.cancel=cancel;self.deadline=clock()+seconds
    def check(self):
        self.cancel();require(self.clock()<self.deadline,'COLLECTOR_DEADLINE')
    def remaining(self):
        self.check();return self.deadline-self.clock()

class _Redirect(urllib.request.HTTPRedirectHandler):
    def __init__(self,hosts):self.hosts=hosts;self.count=0
    def redirect_request(self,request,fp,code,message,headers,newurl):
        self.count+=1;parsed=urllib.parse.urlsplit(newurl)
        require(self.count<=8 and parsed.scheme=='https' and parsed.hostname in self.hosts,
                'FETCH_REDIRECT_PROFILE')
        return super().redirect_request(request,fp,code,message,headers,newurl)

def fetch(role,target,limit,clock):
    require(role in URLS,'FETCH_ROLE');url=URLS[role]
    hosts={'keyserver.ubuntu.com'} if role=='key' else {'github.com','release-assets.githubusercontent.com','objects.githubusercontent.com'}
    opener=urllib.request.build_opener(_Redirect(hosts));h=hashlib.sha256();size=0
    try:
        with opener.open(urllib.request.Request(url,headers={'User-Agent':'josm-boot-public-data/1'}),
                         timeout=min(30,clock.remaining())) as response:
            require(response.status==200,'FETCH_HTTP_STATUS')
            declared=response.headers.get('Content-Length')
            if declared is not None:require(declared.isdecimal() and int(declared)<=limit,'FETCH_DECLARED_LIMIT')
            with target.open('xb') as output:
                while True:
                    clock.check();block=response.read(min(65536,limit-size+1))
                    if not block:break
                    require(size+len(block)<=limit,'FETCH_ACTUAL_LIMIT');size+=len(block);h.update(block);output.write(block)
            if declared is not None:require(size==int(declared),'FETCH_TRUNCATED')
    except (urllib.error.URLError,OSError) as failure:
        raise Failure('FETCH_TRANSPORT') from None
    require(size>0,'FETCH_EMPTY');return {'url':url,'bytes':size,'sha256':h.hexdigest()}

def _emit(e,name,data):
    e.chunks(name,[data])

def _tool(path,base,checkpoint=lambda:None):
    path=Path(path).resolve();require(path.is_file() and os.access(path,os.X_OK),'RUNNER_TOOL_MISSING')
    size=path.stat().st_size;require(0<size<=64<<20,'RUNNER_TOOL_SIZE')
    return {'path':str(path),'bytes':size,'sha256':base['file_digest'](path,size,checkpoint)}

def runner_tools(base,checkpoint=lambda:None):
    result={}
    for name in ('gpg','gpgv'):
        path=shutil.which(name);require(path is not None,'RUNNER_TOOL_MISSING');result[name]=_tool(path,base,checkpoint)
    home=os.environ.get('JAVA_HOME_17_X64')
    candidate=Path(home)/'bin/jimage' if home else None
    path=candidate if candidate and candidate.is_file() else shutil.which('jimage')
    require(path is not None,'RUNNER_JIMAGE_MISSING');result['jimage']=_tool(path,base,checkpoint)
    result['scope']='OBSERVED_RUNNER_TOOLS_NOT_QUALIFIED'
    return result

def run_tool(argv,stage,clock,e,role,base):
    clock.check();before=_tool(argv[0],base,clock.check);stage.mkdir()
    code,out,err=base['_owned_tool'](tuple(str(p) for p in argv),{'LANG':'C','LC_ALL':'C'},
                                   stage,min(300,clock.remaining()),clock.check)
    after=_tool(argv[0],base,clock.check)
    e.json('reports/tool-'+role+'.json',{'argv':[str(p) for p in argv],'exit':code,'image_before':before,'image_after':after,
           'stdout_bytes':len(out),'stderr_bytes':len(err),'stdout_sha256':hashlib.sha256(out).hexdigest(),
           'stderr_sha256':hashlib.sha256(err).hexdigest()})
    require(before==after,'RUNNER_TOOL_MUTATION');clock.check();return code,out

def prepare_key(stage,e,clock,tools,base):
    key=stage/'supplier.asc';record=fetch('key',key,65536,clock)
    home=stage/'gnupg';home.mkdir(mode=0o700)
    args=(tools['gpg']['path'],'--batch','--no-options','--no-autostart','--homedir',home,
          '--with-colons','--import-options','show-only','--import',key)
    code,out=run_tool(args,stage/'key-show',clock,e,'key-show',base)
    require(code==0,'KEY_INSPECTION_FAILED')
    try:rows=[line.split(':') for line in out.decode('ascii').splitlines()]
    except UnicodeDecodeError:raise Failure('KEY_COLON_ENCODING') from None
    primary=[];expect=False;pubs=0
    for row in rows:
        if row[0]=='pub':pubs+=1;expect=True
        elif row[0]=='fpr' and expect:
            require(len(row)>9,'KEY_COLON_RECORD');primary.append(row[9]);expect=False
        elif row[0]=='sub':expect=False
    require(pubs==1 and primary==[FINGERPRINT],'KEY_PRIMARY_FINGERPRINT')
    _emit(e,'raw/supplier-key.asc',key.read_bytes());_emit(e,'raw/key-colons.txt',out)
    keyring=stage/'supplier.gpg'
    code,_=run_tool((tools['gpg']['path'],'--batch','--no-options','--no-autostart','--homedir',home,
                     '--yes','--dearmor','--output',keyring,key),stage/'key-dearmor',clock,e,'key-dearmor',base)
    require(code==0 and keyring.is_file() and 0<keyring.stat().st_size<=65536,'KEYRING_PRODUCTION_FAILED')
    e.json('reports/key.json',{'scope':'PUBLIC_KEY_DATA','primary':FINGERPRINT,'fetch':record,
                              'keyring_sha256':base['file_digest'](keyring,keyring.stat().st_size)})
    return keyring,home

def authenticate(archive,signature,keyring,home,tools,stage,e,clock,base,expected=ARCHIVE_SHA256):
    require(base['file_digest'](archive,archive.stat().st_size,clock.check)==expected,'ARCHIVE_CHECKSUM')
    code,out=run_tool((tools['gpgv']['path'],'--homedir',home,'--status-fd','1','--keyring',keyring,
                       signature,archive),stage/'signature-check',clock,e,'signature',base)
    _emit(e,'raw/signature-status.txt',out)
    # Only a successfully parsed cryptographic signature is public raw evidence;
    # never upload an unexpected HTTP200 error body as if it were a signature.
    if code==0:_emit(e,'raw/signature',signature.read_bytes())
    try:text=out.decode('ascii')
    except UnicodeDecodeError:raise Failure('SIGNATURE_ENCODING') from None
    # Existing PRIMARY-only policy, deliberately not widened for subkeys/expiry.
    base['signature_status'](code,text,FINGERPRINT)
    require(base['file_digest'](archive,archive.stat().st_size,clock.check)==expected,'SIGNED_ARCHIVE_MUTATION')

def inventory_archive(archive,stage,evidence,bounds,checkpoint):
    """Actual signed USTAR discovery. Never a strict approved manifest/admission."""
    base=dependency();b=bounds;e=evidence;headers=0;expanded=0;selected=None;seen=set()
    def read(stream,n):
        nonlocal expanded
        require(0<=n<=65536 and expanded+n<=b['expanded'],'TAR_EXPANDED_LIMIT');checkpoint()
        data=stream.read(n);expanded+=len(data);require(len(data)==n,'TAR_TRUNCATED');return data
    with gzip.open(archive,'rb') as stream:
        while True:
            header=read(stream,512)
            if not any(header):
                require(not any(read(stream,512)),'TAR_END_MARKER')
                while True:
                    checkpoint();data=stream.read(min(65536,b['expanded']-expanded+1))
                    if not data:break
                    expanded+=len(data);require(expanded<=b['expanded'],'TAR_EXPANDED_LIMIT')
                    require(not any(data),'TAR_TRAILING_DATA')
                break
            require(headers<b['headers'],'TAR_HEADER_LIMIT');headers+=1
            require(base['_octal'](header[148:156])==sum(header[:148])+8*32+sum(header[156:]),'TAR_CHECKSUM')
            kind=header[156:157];magic=header[257:265]
            if magic!=b'ustar\x0000' or kind in (b'x',b'g',b'L',b'K'):
                _emit(e,'raw/unsupported-header.bin',header)
                raise Failure('TAR_EXTENSION_OR_FORMAT_UNSUPPORTED')
            name=base['_name'](header[:100]);prefix=header[345:500].rstrip(b'\0')
            if prefix:name=base['_name'](prefix+b'/'+name.encode('ascii'))
            require(name not in seen,'TAR_DUPLICATE');seen.add(name)
            size=base['_octal'](header[124:136]);mode=base['_octal'](header[100:108])
            require(expanded+((size+511)//512)*512+1024<=b['expanded'],'TAR_DECLARED_EXPANSION')
            link=header[157:257].rstrip(b'\0')
            e.json('reports/tar/header-%05d.json'%headers,{'name':name,'type':kind.hex(),'bytes':size,'mode':mode,
                    'link_bytes_hex':link.hex(),'header_sha256':hashlib.sha256(header).hexdigest(),
                    'offset':expanded-512,'format':'USTAR'})
            require(kind in (b'0',b'\0',b'5',b'1',b'2'),'TAR_TYPE_UNSUPPORTED')
            parts=name.split('/');is_modules=len(parts)==3 and parts[1:]==['lib','modules']
            if is_modules:
                require(kind in (b'0',b'\0'),'MODULES_NONREGULAR')
                require(selected is None,'MODULES_DUPLICATE');require(size<=b['modules'],'MODULES_SIZE_LIMIT')
                selected={'name':name,'bytes':size,'modules':stage/'selected-modules'}
            output=selected['modules'].open('xb') if is_modules else None
            try:
                h=hashlib.sha256();remaining=size
                while remaining:
                    block=read(stream,min(65536,remaining));remaining-=len(block);h.update(block)
                    if output is not None:output.write(block)
            finally:
                if output is not None:output.close()
            if is_modules:selected['sha256']=h.hexdigest()
            if size%512:require(not any(read(stream,512-size%512)),'TAR_PADDING')
    require(selected is not None,'MODULES_MISSING');selected.update(headers=headers,expanded=expanded)
    return selected

def _listed_names(out,family):
    names=[]
    try:lines=out.decode('ascii').splitlines()
    except UnicodeDecodeError:raise Failure('JIMAGE_LIST_ENCODING') from None
    module=None
    for line in lines:
        value=line.strip()
        if not value:continue
        if value.startswith('jimage: '):continue
        if value.startswith('Module: '):module=value[8:];require(module=='java.base','JIMAGE_MODULE_DRIFT');continue
        require(module=='java.base' and value.endswith('.class') and len(value)<=255,'JIMAGE_LIST_PROTOCOL')
        binary=value[:-6]
        require(binary==family or binary.startswith(family+'$'),'JIMAGE_FAMILY_DRIFT')
        require(all(c.isalnum() or c in '/_$' for c in binary),'JIMAGE_BINARY_NAME')
        names.append('java.base/'+value)
    require(len(names)==len(set(names)),'JIMAGE_LIST_DUPLICATE');return names

def observe_projection(image,tool,stage,e,b,clock,base,families=FAMILIES):
    """Discover raw DATA, no fabricated strict expected hashes or method proof."""
    names=[]
    for i,family in enumerate(families):
        # jimage splits the include list at commas before glob parsing; a brace
        # alternation containing a comma is therefore not an accepted argument.
        pattern='glob:/java.base/'+family+'.class,glob:/java.base/'+family+'$*.class'
        code,out=run_tool((tool,'list','--include',pattern,image),stage/('list-%d'%i),clock,e,'list-%d'%i,base)
        require(code==0,'JIMAGE_LIST_FAILURE');_emit(e,'raw/list-%d.txt'%i,out)
        found=_listed_names(out,family);require('java.base/'+family+'.class' in found,'JIMAGE_OUTER_MISSING')
        names.extend(found);require(len(names)<=b['classes'],'CLASS_COUNT_LIMIT')
    require(len(names)==len(set(names)),'JIMAGE_CLASS_DUPLICATE')
    partial=0
    for i,name in enumerate(sorted(names)):
        call=stage/('class-%d'%i);call.mkdir();output=call/'output';output.mkdir()
        code,out=run_tool((tool,'extract','--include','glob:/'+name,'--dir',output,image),call/'tool',clock,e,'class-%d'%i,base)
        require(code==0,'JIMAGE_EXTRACT_FAILURE');files=[]
        for path in output.rglob('*'):
            require(not path.is_symlink(),'PROJECTED_LINK')
            if path.is_file():files.append(path.relative_to(output).as_posix())
            else:require(path.is_dir(),'PROJECTED_SPECIAL')
        require(files==[name],'PROJECTED_CLOSURE')
        path=output/name;size=path.stat().st_size;require(0<size<=b['class_bytes'],'CLASS_SIZE_LIMIT')
        data=path.read_bytes();_emit(e,'classes/'+name+'.bin',data)
        record={'module_binary':name,'bytes':size,'sha256':hashlib.sha256(data).hexdigest(),'scope':'OBSERVED_NOT_APPROVED'}
        try:
            actual=base['normalize_class'](data)
            require(actual['name']==(7,('utf',name.split('/',1)[1][:-6].encode('ascii').hex())),'PROJECTED_BINARY_NAME')
            e.json('methods/'+name+'.json',actual);record['normalizer']='PARSED_NOT_SOURCE_EQUIVALENT'
        except base['Pending'] as failure:
            partial+=1;record['normalizer']='UNSUPPORTED';record['cause']=str(failure)
        e.json('reports/classes/%03d.json'%i,record)
    return {'classes':len(names),'unsupported':partial}

def _metadata(path):
    value=json.loads(path.read_bytes())
    require(value.get('sha256')==ARCHIVE_SHA256 and value.get('scmRef')=='jdk-17.0.12+7_adopt' and
            value.get('openjdk_source')=='https://github.com/adoptium/jdk17u/commit/'+SOURCE and
            value.get('buildRef')=='https://github.com/adoptium/temurin-build/commit/'+BUILD,'SUPPLIER_METADATA_DRIFT')

def collect(output,prepare_only=False,bounds=None):
    b=dict(BOUNDS if bounds is None else bounds);e=Evidence(output,b);clock=Clock(b['seconds']);base=None
    status={'schema':'BOOT_PUBLIC_DATA_1','qualified':False,'caseCount':0,'candidateExecutions':0,
            'stage':'START','outcome':'FAILED','cleanup':'NOT_REACHED','runtimeAuthority':'UNAVAILABLE',
            'available':[],'missing':['publicKeyPrimary','primarySignedArchive','signedTarInventory',
                                    'selectedModules','rawClassObservations','completeParsedMethods','runtimeAuthority']}
    stage=None;primary=None
    try:
        status['stage']='SOURCE_PIN';base=dependency();status['available'].append('sourcePin')
        stage=Path(tempfile.mkdtemp(prefix='boot-data-',dir=Path(output).parent))
        status['stage']='RUNNER_TOOLS';tools=runner_tools(base,clock.check);e.json('reports/tools.json',tools)
        status['available'].append('runnerToolObservations')
        status['stage']='PUBLIC_KEY';keyring,home=prepare_key(stage,e,clock,tools,base)
        status['available'].append('publicKeyPrimary');status['missing'].remove('publicKeyPrimary')
        if prepare_only:status.update(outcome='KEY_DATA_ONLY',stage='COMPLETE');return status
        status['stage']='FETCH_FIXED_ASSETS';records={}
        for role,limit in [('metadata',65536),('signature',65536),('archive',b['compressed'])]:
            records[role]=fetch(role,stage/role,limit,clock)
        e.json('reports/acquisition.json',records);_metadata(stage/'metadata')
        _emit(e,'raw/metadata', (stage/'metadata').read_bytes())
        status['stage']='SIGNATURE';authenticate(stage/'archive',stage/'signature',keyring,home,tools,stage,e,clock,base)
        status['available'].append('primarySignedArchive');status['missing'].remove('primarySignedArchive')
        status['stage']='SIGNED_TAR';selected=inventory_archive(stage/'archive',stage,e,b,clock.check)
        e.json('reports/modules.json',{k:str(v) if isinstance(v,Path) else v for k,v in selected.items()})
        for name in ('signedTarInventory','selectedModules'):
            status['available'].append(name);status['missing'].remove(name)
        status['stage']='EXECUTED_IMAGE_DATA';result=observe_projection(selected['modules'],tools['jimage']['path'],stage,e,b,clock,base)
        require(base['file_digest'](selected['modules'],selected['bytes'],clock.check)==selected['sha256'],'MODULE_IMAGE_MUTATION')
        status['available'].extend(['rawClassObservations','perClassNormalizerOutcomes'])
        status['missing'].remove('rawClassObservations')
        if not result['unsupported']:
            status['available'].append('completeParsedMethods');status['missing'].remove('completeParsedMethods')
        status.update(stage='COMPLETE',outcome='PARTIAL_NORMALIZER' if result['unsupported'] else 'COLLECTED_DATA_ONLY',**result)
    except Failure as failure:
        primary=failure;status['cause']=str(failure)
    except Exception as failure:
        primary=failure
        status['cause']=str(failure) if base is not None and isinstance(failure,base['Pending']) else 'UNEXPECTED_COLLECTOR_FAILURE'
    finally:
        if stage is not None:
            try:base['_remove'](stage);status['cleanup']='COMPLETE'
            except Exception:
                status['cleanup']='FAILED'
                if primary is None:status.update(outcome='FAILED',cause='CLEANUP_FAILURE')
        e.finish(status)
    return status

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',required=True);group=parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--prepare-key',action='store_true');group.add_argument('--collect',action='store_true')
    args=parser.parse_args()
    if args.collect:
        require(os.environ.get('GITHUB_ACTIONS')=='true' and os.environ.get('GITHUB_EVENT_NAME')=='workflow_dispatch',
                'MANUAL_CI_ONLY_ARCHIVE_ACQUISITION')
    def stop(signum,frame):raise Failure('COLLECTOR_CANCELLED')
    signal.signal(signal.SIGTERM,stop)
    result=collect(Path(args.output),prepare_only=args.prepare_key)
    print(json.dumps({k:result[k] for k in ('stage','outcome','qualified','caseCount','candidateExecutions')}))
    return 0 if result['outcome'] in ('COLLECTED_DATA_ONLY','KEY_DATA_ONLY') else 1

if __name__=='__main__':raise SystemExit(main())
