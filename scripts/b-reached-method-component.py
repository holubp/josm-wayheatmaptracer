"""Independent reached-method sibling; never full-class/runtime equivalence.

UNIT records exercise real reader/projection/comparison mechanics only. The
production registry is empty. GNU composition uses its separately authenticated
image issuer; public collector DATA cannot supply that origin. A typed HOST
borrow checks selected members, but grants no native/startup/runtime authority.
"""
from pathlib import Path
import hashlib
import json
import tempfile
from time import monotonic

SCHEMA='REACHED_JAVA_METHODS_1'
HELPER_SHA256='16cfafddfec78c09a61551c66920df8663324ac9fc1afc5da63ed20159828c67'
REGISTERED_COMPONENTS={}  # controller-installed protected record (path,bytes,SHA256), never caller input
REGISTERED_GNU_COMPONENTS={}
GNU_HELPER_SHA256='c512f6f9bbea55173ebee2068f1450373efe3b433b58b0d703e1ca2408079aec'
MAX_RECORD=2<<20
MAX_CLASSES=64
MAX_METHODS=512
MAX_CLASS_BYTES=1<<20
_HELPER=None
_GNU=None
_UNIT=object()
_REGISTERED=object()

class Pending(RuntimeError):
    """Specific missing or mismatched component premise."""

def require(ok,cause):
    if not ok:raise Pending(cause)

def _helper():
    global _HELPER
    if _HELPER is None:
        path=Path(__file__).with_name('b-boot-bundle-verifier.py');data=path.read_bytes()
        require(len(data)<=65536 and hashlib.sha256(data).hexdigest()==HELPER_SHA256,'PARSER_SOURCE_DRIFT')
        ns={'__name__':'reached_image_helper','__file__':str(path)}
        exec(compile(data,str(path),'exec'),ns);_HELPER=ns
    return _HELPER

def _tuples(value):
    if type(value) is list:return tuple(_tuples(v) for v in value)
    if type(value) is dict:return {k:_tuples(v) for k,v in value.items()}
    return value

def _json(data):
    require(type(data) is bytes and 0<len(data)<=MAX_RECORD,'RECORD_SIZE')
    # Bound JSON nesting BEFORE constructing its containers, respecting strings.
    depth=0;quoted=False;escaped=False
    for byte in data:
        if quoted:
            if escaped:escaped=False
            elif byte==92:escaped=True
            elif byte==34:quoted=False
        elif byte==34:quoted=True
        elif byte in (91,123):
            depth+=1;require(depth<=64,'RECORD_DEPTH')
        elif byte in (93,125):
            depth-=1;require(depth>=0,'RECORD_FORMAT')
    require(depth==0 and not quoted,'RECORD_FORMAT')
    def pairs(items):
        require(len(items)<=1024,'RECORD_OBJECT_BOUND');result={}
        for key,value in items:
            require(key not in result,'RECORD_DUPLICATE_KEY');result[key]=value
        return result
    def number(value):raise Pending('RECORD_NUMBER_KIND')
    try:r=json.loads(data,object_pairs_hook=pairs,parse_float=number,parse_constant=number)
    except (ValueError,UnicodeError,RecursionError) as failure:raise Pending('RECORD_FORMAT') from failure
    return r

def _record(data):
    r=_json(data);_validate(r)
    # Keep source/tool/archive API lists in their existing strict ABI. Only
    # symbolic IR and immutable published scope are restored to tuple form.
    for cls in r['classes']:
        cls['context']=_tuples(cls['context'])
        for method in cls['methods']:
            for key in ('expected','declaration','bindings'):
                if key in method:method[key]=_tuples(method[key])
    r['required']=_tuples(r['required']);r['frontier']=_tuples(r['frontier'])
    return r

def _text(value):return type(value) is str and 0<len(value)<=255
def _integer(value,lo,hi):return type(value) is int and lo<=value<=hi
def _digest(value):return type(value) is str and len(value)==64 and all(c in '0123456789abcdef' for c in value)
def _key(name):
    require(_text(name) and name.isascii() and name.endswith('.class') and '/' in name,'CLASS_KEY')
    _helper()['_name'](name.encode('ascii'));module,binary=name.split('/',1)
    return module,binary[:-6]

def _validate(r):
    require(type(r) is dict and r.get('schema')==SCHEMA and _text(r.get('id')),'RECORD_SCHEMA')
    require(type(r.get('approval')) is dict and _text(r['approval'].get('id')) and
            r['approval'].get('kind') in ('UNIT_ONLY','INDEPENDENT_SOURCE_RECORD_1'),'RECORD_APPROVAL')
    require(r.get('parser')=={'sha256':HELPER_SHA256,'schema':'JAVA17_CP_SYMBOLIC_1'},'PARSER_SCOPE_DRIFT')
    require(type(r.get('source')) is dict and all(_text(r['source'].get(k)) for k in ('commit','build','proof')),
            'SOURCE_SCOPE_ABSENT')
    require(type(r.get('archive')) is dict and _integer(r['archive'].get('archive_bytes'),1,512<<20) and
            _integer(r['archive'].get('tar_bytes'),1024,2<<30),'ARCHIVE_BOUND')
    require(_digest(r['archive'].get('archive_sha256')) and _integer(r['archive'].get('signature_bytes'),1,65536)
            and _digest(r['archive'].get('signature_sha256')) and type(r['archive'].get('signer')) is str
            and len(r['archive']['signer'])==40 and all(c in '0123456789ABCDEF' for c in r['archive']['signer']),
            'ARCHIVE_SIGNATURE_INPUTS')
    require(r['archive'].get('source')==r['source']['commit'] and r['archive'].get('build')==r['source']['build'],
            'SOURCE_ARCHIVE_SCOPE_DRIFT')
    require(type(r['archive'].get('members')) is dict and 0<len(r['archive']['members'])<=1024,'MEMBER_BOUND')
    for name,item in r['archive']['members'].items():
        require(_text(name) and name.isascii() and type(item) is dict and _integer(item.get('bytes'),0,2<<30)
                and _digest(item.get('sha256')) and _integer(item.get('mode'),0,0o777),'MEMBER_INPUTS')
        _helper()['_name'](name.encode('ascii'))
    image=r.get('image');require(type(image) is dict and _text(image.get('member')) and
            _integer(image.get('bytes'),1,512<<20) and _digest(image.get('sha256')),'IMAGE_SCOPE')
    require(image['member'].isascii(),'IMAGE_SCOPE');_helper()['_name'](image['member'].encode('ascii'))
    binding=r['archive']['members'].get(image['member'])
    require(type(binding) is dict and binding.get('bytes')==image['bytes'] and binding.get('sha256')==image['sha256'],
            'IMAGE_ARCHIVE_JOIN')
    tool=r.get('tool');require(type(tool) is dict and tool.get('protocol')=='JIMAGE_EXTRACT_EXACT_1' and
            type(tool.get('files')) is list and 0<len(tool['files'])<=64,'TOOL_SCOPE')
    require(type(tool.get('executable')) is str and 0<len(tool['executable'])<=4096
            and _integer(tool.get('timeout'),1,60),'TOOL_INPUTS')
    for item in tool['files']:
        require(type(item) is dict and type(item.get('path')) is str and 0<len(item['path'])<=4096
                and _integer(item.get('bytes'),1,512<<20) and _digest(item.get('sha256')),'TOOL_FILE_INPUTS')
    frontier=r.get('frontier');require(type(frontier) is dict,'FRONTIER_ABSENT')
    for field in ('exclusions','pending'):
        require(type(frontier.get(field)) is list and 0<len(frontier[field])<=1024 and
                all(_text(v) for v in frontier[field]),'FRONTIER_ABSENT')
    external=frontier.get('external_bootstraps')
    require(type(external) is list and len(external)<=1024 and
            all(type(v) is list and len(v)==3 and all(_text(s) for s in v) for v in external),'BOOTSTRAP_FRONTIER')
    classes=r.get('classes');require(type(classes) is list and 0<len(classes)<=MAX_CLASSES,'CLASS_COUNT')
    names=set();selected=set()
    for cls in classes:
        require(type(cls) is dict,'CLASS_RECORD');name=cls.get('name');module,binary=_key(name)
        require(name not in names,'CLASS_DUPLICATE');names.add(name)
        require(_integer(cls.get('bytes'),1,MAX_CLASS_BYTES) and _digest(cls.get('sha256')),'CLASS_BOUND')
        context=cls.get('context')
        require(type(context) is dict and set(context)=={'access','name','super','interfaces','fields','attributes'} and
                _integer(context['access'],0,65535),'CLASS_CONTEXT')
        require(type(context['fields']) is list and len(context['fields'])<=256,'FIELD_COUNT')
        require(type(context['attributes']) is list and len(context['attributes'])<=64 and
                all(type(a) is list and len(a)==2 and a[0]!='BootstrapMethods' for a in context['attributes']),
                'CONTEXT_BOOTSTRAPS_NOT_SEMANTIC_AUTHORITY')
        methods=cls.get('methods');require(type(methods) is list and 0<len(methods)<=MAX_METHODS,'METHOD_COUNT')
        keys=set()
        for m in methods:
            require(type(m) is dict and _text(m.get('name')) and _text(m.get('descriptor')),'METHOD_KEY')
            key=(m['name'],m['descriptor']);require(key not in keys,'METHOD_DUPLICATE');keys.add(key)
            status=m.get('status');require(status in ('JAVA_BODY','NATIVE_BOUNDARY','DECLARATION_CONTEXT','OUTSIDE_REVIEW'),
                                         'METHOD_STATUS')
            if status in ('JAVA_BODY','NATIVE_BOUNDARY'):
                require(set(m)=={'name','descriptor','status','expected','review','bindings'},'METHOD_RECORD')
                e=m['expected'];require(type(e) is list and len(e)==6 and _integer(e[0],0,65535) and
                        e[1:3]==list(key),'METHOD_EXPECTATION')
                if status=='JAVA_BODY':require(type(e[3]) is list and len(e[3])==4 and not e[0]&0x500,'JAVA_BODY_EXPECTED')
                else:require(e[3] is None and bool(e[0]&0x100),'NATIVE_DECLARATION_EXPECTED')
                require(type(m['bindings']) is list and len(m['bindings'])<=65535,'BOOTSTRAP_BINDING_BOUND')
            else:
                allowed={'name','descriptor','status','declaration'}|({'review'} if status=='DECLARATION_CONTEXT' else set())
                require(set(m)==allowed,'OUTSIDE_REVIEW_BODY_OR_PROMOTION')
                e=m['declaration'];require(type(e) is list and len(e)==5 and _integer(e[0],0,65535) and
                        e[1:3]==list(key),'DECLARATION_EXPECTATION')
            if status!='OUTSIDE_REVIEW':
                review=m.get('review');require(type(review) is dict and _text(review.get('id')) and
                        review.get('input')==cls['sha256'],'SOURCE_REVIEW_INPUT_JOIN')
                selected.add((module,binary,*key))
    required=r.get('required');require(type(required) is list and len(required)<=MAX_CLASSES*MAX_METHODS and
            all(type(k) is list and len(k)==4 and all(_text(s) for s in k) for k in required),'REQUIRED_KEYS')
    required_set={tuple(k) for k in required}
    require(len(required_set)==len(required) and required_set==selected,'REQUIRED_SET_DRIFT')

def _bindings(actual,method,records,external):
    body=method[3]
    if body is None:return ()
    tables=[v for k,v in actual['attributes'] if k=='BootstrapMethods']
    require(len(tables)<=1,'BOOTSTRAP_TABLE_DUPLICATE');result=[]
    def handle(value):
        require(value[0]==15,'BOOTSTRAP_HANDLE')
        member=value[2];require(member[0] in (10,11),'BOOTSTRAP_METHOD_HANDLE')
        owner=bytes.fromhex(member[1][1][1]).decode('ascii');nt=member[2]
        key=(owner,bytes.fromhex(nt[1][1]).decode('ascii'),bytes.fromhex(nt[2][1]).decode('ascii'))
        local=[v for (module,binary),v in records.items() if binary==owner]
        if local:
            require(len(local)==1 and any(m['name']==key[1] and m['descriptor']==key[2] and
                    m['status'] in ('JAVA_BODY','NATIVE_BOUNDARY') for m in local[0]['methods']),
                    'BOOTSTRAP_REACHES_OUTSIDE_REVIEW')
        else:require(key in external,'BOOTSTRAP_EXTERNAL_FRONTIER')
    for index,(op,args) in enumerate(body[2]):
        if op in (18,19,20) and args and args[0][0]==17:raise Pending('CONSTANT_DYNAMIC_UNSUPPORTED')
        if op!=186:continue
        value=args[0];require(value[0]==18 and len(tables)==1 and 0<=value[1]<len(tables[0]),'BOOTSTRAP_INDEX')
        binding=tables[0][value[1]];handle(binding[0])
        for argument in binding[1]:
            require(argument[0] in (3,4,5,6,7,8,15,16),'BOOTSTRAP_ARGUMENT_UNSUPPORTED')
            if argument[0]==15:handle(argument)
        result.append((index,binding))
    return tuple(result)

def _compare(record,cls,data):
    require(len(data)==cls['bytes'] and hashlib.sha256(data).hexdigest()==cls['sha256'],'CLASS_IDENTITY_DRIFT')
    actual=_helper()['normalize_class'](data)
    context={k:v for k,v in actual.items() if k!='methods'}
    context['attributes']=tuple(a for a in context['attributes'] if a[0]!='BootstrapMethods')
    require(context==cls['context'],'CLASS_CONTEXT_DRIFT')
    _,binary=_key(cls['name']);require(actual['name']==(7,('utf',binary.encode('ascii').hex())),'CLASS_BINARY_NAME_DRIFT')
    methods={}
    for m in actual['methods']:
        key=m[1:3];require(key not in methods,'ACTUAL_METHOD_DUPLICATE');methods[key]=m
    require(set(methods)=={(m['name'],m['descriptor']) for m in cls['methods']},'METHOD_INVENTORY_DRIFT')
    records={_key(c['name']):c for c in record['classes']}
    external=set(record['frontier']['external_bootstraps'])
    for m in cls['methods']:
        value=methods[(m['name'],m['descriptor'])]
        if m['status'] in ('JAVA_BODY','NATIVE_BOUNDARY'):
            require(value==m['expected'],'REVIEWED_METHOD_DRIFT')
            require(_bindings(actual,value,records,external)==m['bindings'],'BOOTSTRAP_BINDING_DRIFT')
        else:
            declaration=(value[0],value[1],value[2],value[4],value[5])
            require(declaration==m['declaration'],'DECLARATION_CONTEXT_DRIFT')

class _ComponentReceipt:
    __slots__=('unit','record_id','covered','outside','exclusions','pending','origin','closed')
    def __init__(self,unit,record,origin):
        require(origin in (_UNIT,_REGISTERED),'COMPONENT_ISSUER')
        self.unit=unit;self.record_id=record['id'];self.covered=tuple(record['required'])
        self.outside=tuple((*_key(c['name']),m['name'],m['descriptor']) for c in record['classes']
                           for m in c['methods'] if m['status']=='OUTSIDE_REVIEW')
        self.exclusions=record['frontier']['exclusions'];self.pending=record['frontier']['pending']
        self.origin=origin;self.closed=False
    def close(self):
        if not self.closed:self.closed=True;self.unit.close()
    def __enter__(self):_live(self);return self
    def __exit__(self,kind,error,tb):
        try:self.close()
        except BaseException:
            if kind is None:raise

def _live(receipt):
    require(type(receipt) is _ComponentReceipt and receipt.origin in (_UNIT,_REGISTERED),'COMPONENT_RECEIPT_REQUIRED')
    require(not receipt.closed and not receipt.unit.closed,'COMPONENT_CLOSED')

def _inspect(receipt,cancel):
    _live(receipt);cancel();_live(receipt)
    return {'scope':'UNIT_REACHED_METHODS_DATA' if receipt.origin is _UNIT else 'REGISTERED_REACHED_JAVA_COMPONENT',
            'qualified':False,'runtime':'UNAVAILABLE','covered':receipt.covered,'outside':receipt.outside,
            'exclusions':receipt.exclusions,'pending':receipt.pending}

def inspect_unit_component(receipt,cancel=lambda:None):
    _live(receipt);require(receipt.origin is _UNIT,'UNIT_COMPONENT_REQUIRED');return _inspect(receipt,cancel)

def consume_component(receipt,key,cancel=lambda:None):
    _live(receipt);require(receipt.origin is _REGISTERED and receipt.record_id==key,'REGISTERED_COMPONENT_REQUIRED')
    return _inspect(receipt,cancel)

def _project_image(record,image,parent,checkpoint,deadline):
    """Shared exact projection/comparison body, with caller-owned live input."""
    b=_helper();stage=None;primary=None
    try:
        checkpoint();require(b['file_digest'](image,record['image']['bytes'],checkpoint)==record['image']['sha256'],'IMAGE_DRIFT')
        b['_tool_files'](record['tool'],checkpoint)
        stage=Path(tempfile.mkdtemp(prefix='reached-project-',dir=parent))
        for index,cls in enumerate(record['classes']):
            checkpoint();invocation=stage/str(index);invocation.mkdir();output=invocation/'classes';output.mkdir()
            command=(record['tool']['executable'],'extract','--include','glob:/'+cls['name'],
                     '--dir',str(output),str(image.absolute()))
            code,out,err=b['_owned_tool'](command,{'LANG':'C','LC_ALL':'C'},invocation,
                min(record['tool']['timeout'],max(0.001,deadline-monotonic())),checkpoint)
            checkpoint();require(code==0,'PROJECTION_TOOL_FAILURE');files=[];seen=0
            for path in output.rglob('*'):
                checkpoint();seen+=1;require(seen<=256 and not path.is_symlink(),'PROJECTED_CLOSURE')
                if path.is_file():
                    require(len(files)<1,'PROJECTED_CLOSURE');files.append(path.relative_to(output).as_posix())
                else:require(path.is_dir(),'PROJECTED_CLOSURE')
            require(files==[cls['name']],'PROJECTED_CLOSURE')
            path=output/cls['name'];require(path.stat().st_size==cls['bytes'],'PROJECTED_CLASS_SIZE')
            _compare(record,cls,path.read_bytes());checkpoint()
        require(b['file_digest'](image,record['image']['bytes'],checkpoint)==record['image']['sha256'],'IMAGE_DRIFT')
        b['_tool_files'](record['tool'],checkpoint);checkpoint()
        b['_remove'](stage);stage=None;checkpoint()
    except BaseException as error:primary=error;raise
    finally:
        if stage is not None:
            try:b['_remove'](stage)
            except BaseException:
                if primary is None:raise

def _verify(record,archive,signature,parent,signer,origin,cancel):
    b=_helper();deadline=monotonic()+60;unit=None;stage=None;transferred=False;primary=None
    def checkpoint():
        require(monotonic()<deadline,'COMPONENT_DEADLINE')
        if unit is not None:require(not unit.closed,'BORROWED_IMAGE_CLOSED')
        cancel()
        require(monotonic()<deadline,'COMPONENT_DEADLINE')
        if unit is not None:require(not unit.closed,'BORROWED_IMAGE_CLOSED')
    def shared_signer(a,s,f):return signer(a,s,f,checkpoint)
    try:
        checkpoint();unit=b['verify_unit'](archive,signature,record['archive'],parent,shared_signer,checkpoint)
        _project_image(record,unit.directory/record['image']['member'],parent,checkpoint,deadline)
        result=_ComponentReceipt(unit,record,origin);checkpoint();transferred=True;return result
    except BaseException as error:primary=error;raise
    finally:
        try:
            if stage is not None:b['_remove'](stage)
        except BaseException:
            if primary is None:raise
        finally:
            if unit is not None and not transferred:
                try:unit.close()
                except BaseException:
                    if primary is None:raise

def verify_unit_component(record_bytes,archive,signature,parent,signature_runner,cancel=lambda:None):
    """Real USTAR/tool DATA mechanics only; caller records cannot issue authority."""
    record=_record(record_bytes);require(record['approval']['kind']=='UNIT_ONLY','UNIT_APPROVAL_REQUIRED')
    def signer(a,s,f,checkpoint):
        checkpoint();result=signature_runner(a,s,f);checkpoint();return result
    return _verify(record,archive,signature,parent,signer,_UNIT,cancel)

def verify_registered_component(key,archive,signature,parent,cancel=lambda:None):
    """Future installed source record; strict image limitations remain unchanged."""
    require(type(key) is str and key in REGISTERED_COMPONENTS,'INDEPENDENT_COMPONENT_RECORD_ABSENT')
    b=_helper();path,size,digest=REGISTERED_COMPONENTS[key];require(_integer(size,1,MAX_RECORD),'RECORD_SIZE')
    b['_protected'](path);require(b['file_digest'](path,size,cancel)==digest,'INDEPENDENT_RECORD_DRIFT')
    record=_record(Path(path).read_bytes())
    require(record['id']==key and record['approval']['kind']=='INDEPENDENT_SOURCE_RECORD_1','REGISTERED_SCOPE')
    require(_digest(record['approval'].get('artifact_sha256')),'INDEPENDENT_APPROVAL_INPUT_ABSENT')
    require(record['source']['commit']==b['SOURCE_COMMIT'] and record['source']['build']==b['BUILD_COMMIT'],
            'REGISTERED_SOURCE_SCOPE_DRIFT')
    require(type(record.get('source_chain')) is dict and _text(record.get('signature_profile')),
            'REGISTERED_PROVENANCE_UNSEALED')
    b['source_preparation'](record['source_chain'],cancel)
    signature_id=record['signature_profile']
    require(signature_id in b['REGISTERED_SIGNATURE_PROFILES'],'TRUSTED_SIGNATURE_TOOL_UNSEALED')
    signature_profile=b['REGISTERED_SIGNATURE_PROFILES'][signature_id]
    stage=Path(tempfile.mkdtemp(prefix='reached-sign-',dir=parent));result=None;primary=None
    try:
        def signer(a,s,f,checkpoint):return b['_real_signature'](signature_profile,a,s,f,stage,checkpoint)
        result=_verify(record,archive,signature,parent,signer,_REGISTERED,cancel)
        b['_remove'](stage);stage=None;cancel();_live(result);return result
    except BaseException as error:primary=error;raise
    finally:
        try:
            if stage is not None:b['_remove'](stage)
        except BaseException:
            if primary is None:raise
        finally:
            if primary is not None and result is not None:
                try:result.close()
                except BaseException:pass

def _gnu():
    global _GNU
    if _GNU is None:
        path=Path(__file__).with_name('b-signed-gnu-image.py');data=path.read_bytes()
        require(len(data)<=65536 and hashlib.sha256(data).hexdigest()==GNU_HELPER_SHA256,'GNU_ISSUER_SOURCE_DRIFT')
        ns={'__name__':'method_gnu_issuer','__file__':str(path)};exec(compile(data,str(path),'exec'),ns);_GNU=ns
    return _GNU

def _gnu_record(data):
    envelope=_json(data)
    require(type(envelope) is dict and set(envelope)=={'schema','record','profile'} and
            envelope['schema']=='REACHED_JAVA_METHODS_GNU_1','GNU_METHOD_RECORD_SCHEMA')
    record=_record(json.dumps(envelope['record']).encode());binding=envelope['profile']
    require(type(binding) is dict and set(binding)=={'id','sha256','scope'} and _text(binding['id']) and
            _digest(binding['sha256']) and binding['scope'] in ('MODULES_ONLY','ALL_REGULAR_PAYLOAD'),'GNU_PROFILE_BINDING')
    return record,binding

def _join_profile(record,binding,data):
    require(type(data) is bytes and 0<len(data)<=_gnu()['MAX_PROFILE'] and
            hashlib.sha256(data).hexdigest()==binding['sha256'],'GNU_PROFILE_IDENTITY')
    profile,policy=_gnu()['_profile'](data)
    require(profile['id']==binding['id'] and profile['scope']==binding['scope'],'GNU_PROFILE_SCOPE')
    a=profile['archive'];s=profile['signature'];expected=record['archive']
    require((a['bytes'],a['sha256'],a['tar_bytes'])==
            (expected['archive_bytes'],expected['archive_sha256'],expected['tar_bytes']),'GNU_ARCHIVE_JOIN')
    require((s['bytes'],s['sha256'],s['primary'])==
            (expected['signature_bytes'],expected['signature_sha256'],expected['signer']),'GNU_SIGNATURE_JOIN')
    require((profile['source'],profile['build'])==(record['source']['commit'],record['source']['build']),'GNU_SOURCE_BUILD_JOIN')
    name=record['image']['member'];require(profile['modules']==name,'GNU_MODULES_JOIN')
    member=policy.get(name);require(type(member) is dict and member['kind']=='regular' and member['policy']=='materialize' and
            member['required'] and member['bytes']==record['image']['bytes'],'GNU_MODULES_JOIN')
    require(member['mode']==expected['members'][name]['mode'],'GNU_MODULES_MODE_JOIN')
    return profile

class _GnuReceipt:
    __slots__=('image','record_id','covered','outside','exclusions','pending','origin','closed','binding','member','issuance','source_proof')
    def __init__(self,image,record,binding,origin,member,issuance=None):
        self.image=image;self.record_id=record['id'];self.covered=tuple(record['required'])
        self.outside=tuple((*_key(c['name']),m['name'],m['descriptor']) for c in record['classes']
                           for m in c['methods'] if m['status']=='OUTSIDE_REVIEW')
        self.exclusions=record['frontier']['exclusions'];self.pending=record['frontier']['pending']
        self.origin=origin;self.closed=False
        self.binding=(binding['id'],binding['sha256'],binding['scope'],record['archive']['archive_sha256'],
            record['archive']['signature_sha256'],record['archive']['signer'],record['source']['commit'],
            record['source']['build'],record['image']['member'],record['image']['sha256'],record['image']['bytes'])
        self.member=member  # exact immutable issuer record already checked/projected
        self.issuance=issuance  # actual envelope length/hash/approval, not record id alone
        self.source_proof=record['source']['proof']
    def close(self):
        if not self.closed:self.closed=True;self.image.close()
    def __enter__(self):_gnu_live(self);return self
    def __exit__(self,kind,error,tb):
        try:self.close()
        except BaseException:
            if kind is None:raise

def _gnu_live(receipt):
    require(type(receipt) is _GnuReceipt and receipt.origin in (_UNIT,_REGISTERED),'GNU_COMPONENT_RECEIPT_REQUIRED')
    require(not receipt.closed and not receipt.image.closed,'GNU_COMPONENT_CLOSED');g=_gnu();g['_live'](receipt.image)
    require((receipt.image.profile_id,receipt.image.profile_sha256,receipt.image.scope,receipt.image.archive_sha256)==
            receipt.binding[:4],'GNU_IMAGE_BINDING')
    require(any(m is receipt.member for m in receipt.image.members) and
            (receipt.member[0],receipt.member[2],receipt.member[1])==receipt.binding[8:11],'GNU_COMPONENT_MEMBER_BINDING')
    g['_member_live'](receipt.image,receipt.member)
def _inspect_gnu(receipt,cancel):
    _gnu_live(receipt);cancel();_gnu_live(receipt)
    return {'scope':'UNIT_GNU_REACHED_METHODS_DATA' if receipt.origin is _UNIT else 'SOURCE_GNU_REACHED_METHODS_COMPONENT',
            'qualified':False,'runtime':'UNAVAILABLE','covered':receipt.covered,'outside':receipt.outside,
            'exclusions':receipt.exclusions,'pending':receipt.pending,'image_profile_sha256':receipt.binding[1],
            'image_profile_id':receipt.binding[0],'image_scope':receipt.binding[2],'image_identity':receipt.binding[3:],
            'image_exclusions':receipt.image.exclusions,'image_pending':receipt.image.pending}
def inspect_gnu_unit(receipt,cancel=lambda:None):
    _gnu_live(receipt);require(receipt.origin is _UNIT,'UNIT_GNU_COMPONENT_REQUIRED');return _inspect_gnu(receipt,cancel)
def consume_gnu_component(receipt,key,cancel=lambda:None):
    _gnu_live(receipt);require(receipt.origin is _REGISTERED and receipt.record_id==key,'SOURCE_GNU_COMPONENT_REQUIRED')
    return _inspect_gnu(receipt,cancel)

def _compose_gnu(record,binding,profile_data,archive,signature,parent,issuer,origin,cancel,deadline,issuance=None):
    g=_gnu();image=None;member=None;transferred=False;primary=None
    def checkpoint():
        def live():
            if image is not None:
                g['_live'](image)
                if member is not None:g['_member_live'](image,member)
        require(monotonic()<deadline,'GNU_COMPOSITION_DEADLINE');live();cancel()
        require(monotonic()<deadline,'GNU_COMPOSITION_DEADLINE');live()
    try:
        checkpoint();profile=_join_profile(record,binding,profile_data);checkpoint()
        image=issuer(checkpoint);checkpoint()
        require(type(image) is g['_Image'] and image.origin is (g['_UNIT'] if origin is _UNIT else g['_SOURCE']),
                'GNU_IMAGE_ORIGIN')
        require((image.profile_id,image.profile_sha256,image.scope)==(binding['id'],binding['sha256'],binding['scope']) and
                image.archive_sha256==record['archive']['archive_sha256'],'GNU_IMAGE_BINDING')
        member=next((m for m in image.members if m[0]==record['image']['member']),None)
        require(member is not None and (member[1],member[2])==(record['image']['bytes'],record['image']['sha256']),
                'GNU_AUTHENTICATED_MODULES_JOIN')
        checkpoint();path=g['borrow_member'](image,member[0],binding['scope'],checkpoint)
        require(path==image.directory/member[0],'GNU_FOREIGN_MODULES_OWNER');checkpoint()
        _project_image(record,path,parent,checkpoint,deadline);checkpoint()
        result=_GnuReceipt(image,record,binding,origin,member,issuance);checkpoint();transferred=True;return result
    except BaseException as error:primary=error;raise
    finally:
        if image is not None and not transferred:
            try:image.close()
            except BaseException:
                if primary is None:raise

def verify_gnu_unit(envelope,profile,archive,signature,parent,signature_runner,cancel=lambda:None):
    deadline=monotonic()+60;record,binding=_gnu_record(envelope)
    require(record['approval']['kind']=='UNIT_ONLY','UNIT_APPROVAL_REQUIRED')
    def issuer(checkpoint):return _gnu()['verify_unit'](profile,archive,signature,parent,signature_runner,checkpoint)
    return _compose_gnu(record,binding,profile,archive,signature,parent,issuer,_UNIT,cancel,deadline,
                        (len(envelope),hashlib.sha256(envelope).hexdigest(),None))

def verify_gnu_registered(key,archive,signature,parent,cancel=lambda:None):
    deadline=monotonic()+60
    require(type(key) is str and key in REGISTERED_GNU_COMPONENTS,'INDEPENDENT_GNU_COMPONENT_ABSENT')
    b=_helper();path,size,digest=REGISTERED_GNU_COMPONENTS[key];require(_integer(size,1,MAX_RECORD),'RECORD_SIZE')
    def checkpoint():
        require(monotonic()<deadline,'GNU_COMPOSITION_DEADLINE');cancel();require(monotonic()<deadline,'GNU_COMPOSITION_DEADLINE')
    b['_protected'](path);require(b['file_digest'](path,size,checkpoint)==digest,'INDEPENDENT_RECORD_DRIFT')
    envelope=Path(path).read_bytes()
    require(len(envelope)==size and hashlib.sha256(envelope).hexdigest()==digest,'INDEPENDENT_RECORD_DRIFT')
    record,binding=_gnu_record(envelope)
    require(record['id']==key and record['approval']['kind']=='INDEPENDENT_SOURCE_RECORD_1' and
            _digest(record['approval'].get('artifact_sha256')),'SOURCE_GNU_RECORD_REQUIRED')
    g=_gnu();profile_key=binding['id'];require(profile_key in g['REGISTERED_PROFILES'],'INDEPENDENT_IMAGE_PROFILE_ABSENT')
    profile_path,profile_size,profile_digest=g['REGISTERED_PROFILES'][profile_key]
    require(_integer(profile_size,1,g['MAX_PROFILE']) and profile_digest==binding['sha256'],'GNU_PROFILE_IDENTITY')
    b['_protected'](profile_path);require(b['file_digest'](profile_path,profile_size,checkpoint)==profile_digest,'GNU_PROFILE_IDENTITY')
    data=Path(profile_path).read_bytes()
    def issuer(shared):return g['verify_source'](profile_key,archive,signature,parent,shared)
    return _compose_gnu(record,binding,data,archive,signature,parent,issuer,_REGISTERED,cancel,deadline,
                        (len(envelope),hashlib.sha256(envelope).hexdigest(),record['approval']['artifact_sha256']))


def _host_attachment(value,unit=False):
    """Bound/copy an expected attachment before any caller callback or I/O."""
    require(type(value) is dict and set(value)=={'protocol','component','image_profile','source','archive',
            'installed_files','runtime_members','native_load_startup_proof'},'HOST_ATTACHMENT_SCHEMA')
    require(value['protocol']=='GNU_REACHED_HOST_BINDING_1' and _text(value['native_load_startup_proof']),
            'HOST_ATTACHMENT_PROTOCOL')
    component=value['component'];profile=value['image_profile'];source=value['source'];archive=value['archive']
    require(type(component) is dict and set(component)=={'id','bytes','sha256','approval_artifact_sha256'} and
            _text(component['id']) and _integer(component['bytes'],1,MAX_RECORD) and _digest(component['sha256']) and
            ((unit and component['approval_artifact_sha256'] is None) or _digest(component['approval_artifact_sha256'])),
            'HOST_COMPONENT_BINDING')
    require(type(profile) is dict and set(profile)=={'id','bytes','sha256','scope'} and _text(profile['id']) and
            _integer(profile['bytes'],1,_gnu()['MAX_PROFILE']) and _digest(profile['sha256']) and
            profile['scope']=='ALL_REGULAR_PAYLOAD','HOST_IMAGE_BINDING')
    require(type(source) is dict and set(source)=={'commit','build','source_proof'} and
            all(_text(v) for v in source.values()),'HOST_SOURCE_BINDING')
    require(type(archive) is dict and set(archive)=={'sha256','signature_sha256','primary'} and
            _digest(archive['sha256']) and _digest(archive['signature_sha256']) and type(archive['primary']) is str and
            len(archive['primary'])==40 and all(v in '0123456789ABCDEF' for v in archive['primary']),'HOST_ARCHIVE_BINDING')
    roles=value['installed_files'];members=value['runtime_members']
    require(type(roles) is dict and set(roles)=={'java','javac','modules'} and
            type(members) is list and 3<=len(members)<=64,'HOST_MEMBER_BOUND')
    seen=set()
    def member(v):
        require(type(v) is dict and set(v)=={'member','bytes','sha256'} and _text(v['member']) and
                _integer(v['bytes'],1,512<<20) and _digest(v['sha256']),'HOST_MEMBER_BINDING')
        _gnu()['_name'](v['member'])
    for v in members:
        member(v);require(v['member'] not in seen,'HOST_MEMBER_DUPLICATE');seen.add(v['member'])
    for v in roles.values():member(v);require(v in members,'HOST_ROLE_MEMBER_JOIN')
    require(len({v['member'] for v in roles.values()})==3,'HOST_ROLE_DUPLICATE')
    raw=json.dumps(value,separators=(',',':')).encode()
    require(len(raw)<=65536,'HOST_ATTACHMENT_BOUND')
    return _json(raw)


class _GnuHostBorrow:
    """Borrowed authenticated members; no ownership transfer/runtime permit."""
    __slots__=('receipt','members','roles','origin')
    def __init__(self,receipt,members,roles,origin):
        self.receipt=receipt;self.members=tuple(members);self.roles=tuple(roles);self.origin=origin
    def require_live(self):
        _gnu_live(self.receipt)
        require(self.receipt.origin is self.origin,'GNU_HOST_ORIGIN_DRIFT')
        g=_gnu()
        for member in self.members:
            require(any(m is member for m in self.receipt.image.members),'GNU_HOST_MEMBER_OWNER')
            g['_member_live'](self.receipt.image,member)
    def path(self,role):
        self.require_live()
        require(role in ('java','javac','modules'),'GNU_HOST_ROLE')
        return self.receipt.image.directory/dict(self.roles)[role]
    def runtime_qualified(self):self.require_live();return False


def _borrow_gnu_host(receipt,attachment,profile_data,origin,cancel):
    _gnu_live(receipt);require(receipt.origin is origin,'SOURCE_GNU_HOST_REQUIRED')
    attachment=_host_attachment(attachment,origin is _UNIT)
    c=attachment['component'];p=attachment['image_profile'];s=attachment['source'];a=attachment['archive']
    require(receipt.record_id==c['id'] and receipt.issuance==
            (c['bytes'],c['sha256'],c['approval_artifact_sha256']),'GNU_HOST_ISSUANCE_JOIN')
    require(receipt.binding[:4]==(p['id'],p['sha256'],p['scope'],a['sha256']) and
            receipt.binding[4:8]==(a['signature_sha256'],a['primary'],s['commit'],s['build']) and
            receipt.source_proof==s['source_proof'],'GNU_HOST_IMAGE_SOURCE_JOIN')
    require(type(profile_data) is bytes and len(profile_data)==p['bytes'] and
            hashlib.sha256(profile_data).hexdigest()==p['sha256'],'GNU_HOST_PROFILE_IDENTITY')
    profile,policy=_gnu()['_profile'](profile_data)
    require((profile['id'],profile['scope'])==(p['id'],p['scope']),'GNU_HOST_PROFILE_IDENTITY')
    members=[]
    for expected in attachment['runtime_members']:
        entry=policy.get(expected['member'])
        require(entry is not None and entry['kind']=='regular' and entry['policy']=='materialize' and entry['required'],
                'GNU_HOST_REQUIRED_MEMBER')
        actual=next((m for m in receipt.image.members if m[0]==expected['member']),None)
        require(actual is not None and actual[1:3]==(expected['bytes'],expected['sha256']),'GNU_HOST_MEMBER_BINDING')
        members.append(actual)
    roles=attachment['installed_files']
    require((roles['modules']['member'],roles['modules']['sha256'],roles['modules']['bytes'])==receipt.binding[8:11],
            'GNU_HOST_PROJECTED_MODULES_JOIN')
    for role in ('java','javac'):
        m=next(v for v in members if v[0]==roles[role]['member'])
        require(m[3]&0o111 and m[4]==0o555,'GNU_HOST_EXECUTABLE_MEMBER')
    borrow=_GnuHostBorrow(receipt,members,((k,v['member']) for k,v in roles.items()),origin)
    borrow.require_live();cancel();borrow.require_live()
    return borrow


def _borrow_gnu_host_unit(receipt,attachment,profile_data,cancel=lambda:None):
    return _borrow_gnu_host(receipt,attachment,profile_data,_UNIT,cancel)


def borrow_gnu_host(receipt,attachment,cancel=lambda:None):
    """Borrow SOURCE image members against an independently installed HOST attachment.

    This checks image/component/member identity only. The HOST consumer must
    additionally validate the registered native/load/startup and launch tuple.
    No inspection dictionary or returned path grants runtime authority.

    Args:
        receipt: Genuine registered receipt from this exact issuer namespace.
        attachment: Independently installed bounded HOST attachment.
        cancel: Original caller checkpoint; no new deadline is started.

    Returns:
        A private live member borrow. Caller retains image ownership.

    Raises:
        Pending: For missing, foreign, closed or mismatched source bindings.
        BaseException: Preserves cancellation or file failure without closing
            the caller's borrowed image.
    """
    _gnu_live(receipt);require(receipt.origin is _REGISTERED,'SOURCE_GNU_HOST_REQUIRED')
    attachment=_host_attachment(attachment);p=attachment['image_profile'];g=_gnu();b=_helper()
    require(p['id'] in g['REGISTERED_PROFILES'],'INDEPENDENT_IMAGE_PROFILE_ABSENT')
    path,size,digest=g['REGISTERED_PROFILES'][p['id']]
    require((size,digest)==(p['bytes'],p['sha256']),'GNU_HOST_PROFILE_IDENTITY')
    def checkpoint():_gnu_live(receipt);cancel();_gnu_live(receipt)
    checkpoint();b['_protected'](path)
    require(b['file_digest'](path,size,checkpoint)==digest,'GNU_HOST_PROFILE_IDENTITY')
    data=Path(path).read_bytes()
    return _borrow_gnu_host(receipt,attachment,data,_REGISTERED,checkpoint)
