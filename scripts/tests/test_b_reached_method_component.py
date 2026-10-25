"""Synthetic actual bytes/tool processes; UNIT never issues runtime authority."""
from pathlib import Path
import copy
import ast
import hashlib
import importlib.util
import io
import json
import struct
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

W=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('reached',W/'scripts/b-reached-method-component.py')
C=importlib.util.module_from_spec(spec);spec.loader.exec_module(C)
u2=lambda n:struct.pack('>H',n)
u4=lambda n:struct.pack('>I',n)
def sha(b):return hashlib.sha256(b).hexdigest()
def utf(s):return ('utf',s.encode().hex())

def fixture(code=b'\x1a\x04\x60\xac',superclass='java/lang/Object',annotation=False,
            handlers=(),extra=False,dynamic=False,bootstrap_constant=7,
            method_descriptor='(I)I',field_descriptor='I',unused_code=b'\x03\xac',exceptions=False,
            bootstrap_owner='demo/Bootstrap',bootstrap_method='bootstrap',bootstrap_descriptor='()Ljava/lang/invoke/CallSite;',
            load_opcode=None,constant_dynamic=False):
    definitions=[('n',1,b'pkg/Fixture'),('this',7,'n'),('sn',1,superclass.encode()),('super',7,'sn'),
        ('m',1,b'm'),('desc',1,method_descriptor.encode()),('unused',1,b'unused'),('unused_desc',1,b'()I'),
        ('native',1,b'nativeM'),('field',1,b'val$source'),('int',1,field_descriptor.encode()),('Code',1,b'Code'),
        ('RuntimeVisibleAnnotations',1,b'RuntimeVisibleAnnotations'),('mark',1,b'Ldemo/Marker;'),
        ('BootstrapMethods',1,b'BootstrapMethods'),('bn',1,bootstrap_owner.encode()),('bc',7,'bn'),
        ('bname',1,bootstrap_method.encode()),('bdesc',1,bootstrap_descriptor.encode()),
        ('bnt',12,('bname','bdesc')),('bref',10,('bc','bnt')),('handle',15,(6,'bref')),
        ('dyn',1,b'dyn'),('dd',1,b'()I'),('dnt',12,('dyn','dd')),('indy',18,(0,'dnt')),
        ('arg',3,struct.pack('>I',bootstrap_constant)),('Exceptions',1,b'Exceptions'),
        ('io',1,b'java/io/IOException'),('ioc',7,'io')]
    if load_opcode is not None:
        definitions.extend([('loadname',1,b'constant'),('loadtype',1,b'J' if load_opcode==20 else b'I'),
                            ('loadnt',12,('loadname','loadtype'))])
        definitions.append(('load',17,(0,'loadnt')) if constant_dynamic else
                           ('load',5,struct.pack('>Q',7)) if load_opcode==20 else ('load',3,u4(7)))
    index={};count=1
    for name,tag,value in definitions:index[name]=count;count+=2 if tag in (5,6) else 1
    pool=[]
    for name,tag,value in definitions:
        if tag==1:body=u2(len(value))+value
        elif tag==7:body=u2(index[value])
        elif tag in (10,12):body=b''.join(u2(index[n]) for n in value)
        elif tag==15:body=bytes([value[0]])+u2(index[value[1]])
        elif tag in (17,18):body=u2(value[0])+u2(index[value[1]])
        else:body=value
        pool.append(bytes([tag])+body)
    def attr(name,body):return u2(index[name])+u4(len(body))+body
    def method(name,desc,body,flags=9,handler=(),annotate=False):
        attrs=[]
        if body is not None:
            raw=u2(2)+u2(1)+u4(len(body))+body+u2(len(handler))
            raw+=b''.join(struct.pack('>HHHH',*h) for h in handler)+u2(0)
            attrs.append(attr('Code',raw))
        if annotate:attrs.append(attr('RuntimeVisibleAnnotations',u2(1)+u2(index['mark'])+u2(0)))
        if name=='m' and exceptions:attrs.append(attr('Exceptions',u2(1)+u2(index['ioc'])))
        return u2(flags)+u2(index[name])+u2(index[desc])+u2(len(attrs))+b''.join(attrs)
    if dynamic:code=b'\xba'+u2(index['indy'])+b'\0\0\xac'
    if load_opcode is not None:
        code=bytes([load_opcode])+(bytes([index['load']]) if load_opcode==18 else u2(index['load']))
        code+=b'\xad' if load_opcode==20 else b'\xac'
    methods=[method('m','desc',code,handler=handlers,annotate=annotation),
             method('unused','unused_desc',unused_code),method('native','unused_desc',None,0x109)]
    if extra:methods.append(method('m','unused_desc',b'\x03\xac'))
    attrs=[attr('BootstrapMethods',u2(1)+u2(index['handle'])+u2(1)+u2(index['arg']))] if dynamic or constant_dynamic else []
    return (bytes.fromhex('cafebabe0000003d')+u2(count)+b''.join(pool)+u2(0x21)+u2(index['this'])+
        u2(index['super'])+u2(0)+u2(1)+u2(0x1010)+u2(index['field'])+u2(index['int'])+u2(0)+
        u2(len(methods))+b''.join(methods)+u2(len(attrs))+b''.join(attrs))

def setup(root,dynamic=False):
    data=fixture(dynamic=dynamic);image='jdk/lib/modules';binary='m/pkg/Fixture.class'
    stream=io.BytesIO()
    with tarfile.open(fileobj=stream,mode='w',format=tarfile.USTAR_FORMAT) as tar:
        item=tarfile.TarInfo(image);item.size=len(data);item.mode=0o644;tar.addfile(item,io.BytesIO(data))
    archive=root/'archive';archive.write_bytes(stream.getvalue());signature=root/'sig';signature.write_bytes(b'UNIT signature')
    tool=root/'projector'
    tool.write_text('#!'+sys.executable+'\nfrom pathlib import Path\nimport sys\n'
        'p=Path(sys.argv[5])/'+repr(binary)+'\np.parent.mkdir(parents=True)\np.write_bytes(Path(sys.argv[6]).read_bytes())\n')
    tool.chmod(0o700)
    body=(2,1,((26,()),(4,()),(96,()),(172,())),())
    bootstrap=((15,6,(10,(7,utf('demo/Bootstrap')),(12,utf('bootstrap'),utf('()Ljava/lang/invoke/CallSite;')))),((3,'00000007'),))
    if dynamic:body=(2,1,((186,((18,0,(12,utf('dyn'),utf('()I'))),0,0)),(172,())),())
    reviewed=(9,'m','(I)I',body,(),())
    declarations=[{'name':'m','descriptor':'(I)I','status':'JAVA_BODY','expected':reviewed,
                   'review':{'id':'UNIT-not-source-authority','input':sha(data)},'bindings':[(0,bootstrap)] if dynamic else []},
                  {'name':'unused','descriptor':'()I','status':'OUTSIDE_REVIEW','declaration':(9,'unused','()I',(),())},
                  {'name':'nativeM','descriptor':'()I','status':'NATIVE_BOUNDARY','expected':(0x109,'nativeM','()I',None,(),()),
                   'review':{'id':'UNIT-native-boundary-only','input':sha(data)},'bindings':[]}]
    record={'schema':'REACHED_JAVA_METHODS_1','id':'UNIT-record','approval':{'kind':'UNIT_ONLY','id':'UNIT-review'},
        'parser':{'sha256':'16cfafddfec78c09a61551c66920df8663324ac9fc1afc5da63ed20159828c67','schema':'JAVA17_CP_SYMBOLIC_1'},
        'source':{'commit':'UNIT-source','build':'UNIT-build','proof':'UNIT-not-real-proof'},
        'archive':{'archive_sha256':sha(archive.read_bytes()),'archive_bytes':archive.stat().st_size,
          'signature_bytes':signature.stat().st_size,'signature_sha256':sha(signature.read_bytes()),
          'tar_bytes':archive.stat().st_size,'signer':'A'*40,'source':'UNIT-source','build':'UNIT-build',
          'members':{image:{'bytes':len(data),'sha256':sha(data),'mode':0o644}}},
        'image':{'member':image,'bytes':len(data),'sha256':sha(data)},
        'tool':{'protocol':'JIMAGE_EXTRACT_EXACT_1','executable':str(tool),'timeout':5,
           'files':[{'path':str(tool),'bytes':tool.stat().st_size,'sha256':sha(tool.read_bytes())}]},
        'classes':[{'name':binary,'bytes':len(data),'sha256':sha(data),
           'context':{'access':0x21,'name':(7,utf('pkg/Fixture')),'super':(7,utf('java/lang/Object')),
                      'interfaces':(),'fields':((0x1010,'val$source','I',()),),'attributes':()},'methods':declarations}],
        'required':[['m','pkg/Fixture','m','(I)I'],['m','pkg/Fixture','nativeM','()I']],
        'frontier':{'exclusions':['unused body not reviewed'],'pending':['UNIT only; real GNU provenance issuer OPEN'],
                    'external_bootstraps':[['demo/Bootstrap','bootstrap','()Ljava/lang/invoke/CallSite;']]}}
    return record,archive,signature

def signer(archive,signature,fingerprint):
    return 0,'[GNUPG:] VALIDSIG '+fingerprint+' 2026-01-01 0 0 4 0 1 8 00\n'

def replace_image(record,archive,data):
    """Change UNIT byte identity explicitly so semantic expectations discriminate."""
    out=io.BytesIO()
    with tarfile.open(fileobj=out,mode='w',format=tarfile.USTAR_FORMAT) as tar:
        item=tarfile.TarInfo(record['image']['member']);item.size=len(data);item.mode=0o644;tar.addfile(item,io.BytesIO(data))
    archive.write_bytes(out.getvalue());record['archive'].update(archive_bytes=len(out.getvalue()),
        archive_sha256=sha(out.getvalue()),tar_bytes=len(out.getvalue()))
    record['image'].update(bytes=len(data),sha256=sha(data))
    record['archive']['members'][record['image']['member']].update(bytes=len(data),sha256=sha(data))
    record['classes'][0].update(bytes=len(data),sha256=sha(data))
    for method in record['classes'][0]['methods']:
        if 'review' in method:method['review']['input']=sha(data)

def update_tool(record):
    p=Path(record['tool']['executable']);record['tool']['files'][0].update(bytes=p.stat().st_size,sha256=sha(p.read_bytes()))

def setup_gnu(root,data=None):
    record,archive,signature=setup(root);data=fixture() if data is None else data;stream=io.BytesIO()
    record['classes'][0].update(bytes=len(data),sha256=sha(data));record['image'].update(bytes=len(data),sha256=sha(data))
    record['archive']['members']['jdk/lib/modules'].update(bytes=len(data),sha256=sha(data))
    for method in record['classes'][0]['methods']:
        if 'review' in method:method['review']['input']=sha(data)
    with tarfile.open(fileobj=stream,mode='w',format=tarfile.GNU_FORMAT) as tar:
        directory=tarfile.TarInfo('jdk');directory.type=tarfile.DIRTYPE;directory.mode=0o755;tar.addfile(directory)
        item=tarfile.TarInfo('jdk/lib/modules');item.size=len(data);item.mode=0o644;tar.addfile(item,io.BytesIO(data))
    raw=stream.getvalue();archive.write_bytes(raw)
    record['archive'].update(archive_bytes=len(raw),archive_sha256=sha(raw),tar_bytes=len(raw))
    profile={'schema':'SIGNED_OLDGNU_IMAGE_1','id':'UNIT-composition-image','approval':'UNIT_ONLY',
        'scope':'MODULES_ONLY','root':'jdk','modules':'jdk/lib/modules','source':'UNIT-source','build':'UNIT-build',
        'pending':['UNIT; no source/runtime authority'],
        'archive':{'bytes':len(raw),'sha256':sha(raw),'tar_bytes':len(raw),'compression':'none'},
        'signature':{'bytes':signature.stat().st_size,'sha256':sha(signature.read_bytes()),'primary':'A'*40},
        'limits':{'headers':2,'member_bytes':65536,'output_bytes':65536},
        'members':[{'name':'jdk','kind':'directory','bytes':0,'mode':0o755,'policy':'materialize','required':True},
                   {'name':'jdk/lib/modules','kind':'regular','bytes':len(data),'mode':0o644,'policy':'materialize','required':True}]}
    encoded=json.dumps(profile).encode()
    envelope={'schema':'REACHED_JAVA_METHODS_GNU_1','record':record,
              'profile':{'id':profile['id'],'sha256':sha(encoded),'scope':profile['scope']}}
    return envelope,encoded,archive,signature

class ComponentTests(unittest.TestCase):
    def verify(self,record,archive,signature,root,cancel=lambda:None):
        return C.verify_unit_component(json.dumps(record).encode(),archive,signature,root,signer,cancel)

    def assert_clean(self,root):
        self.assertFalse(list(root.glob('boot-verify-*')));self.assertFalse(list(root.glob('reached-project-*')))

    def test_genuine_owned_unit_projection_compares_only_declared_method_scope(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);record,archive,signature=setup(root)
            try:receipt=C.verify_unit_component(json.dumps(record).encode(),archive,signature,root,signer)
            except C.Pending as failure:self.fail('Actual method component missing: '+str(failure))
            with receipt:
                result=C.inspect_unit_component(receipt)
                self.assertEqual('UNIT_REACHED_METHODS_DATA',result['scope'])
                self.assertEqual((('m','pkg/Fixture','m','(I)I'),('m','pkg/Fixture','nativeM','()I')),result['covered'])
                self.assertEqual((('m','pkg/Fixture','unused','()I'),),result['outside'])
                self.assertFalse(result['qualified']);self.assertEqual('UNAVAILABLE',result['runtime'])
            self.assertFalse(list(root.glob('boot-verify-*')))

    def test_genuine_gnu_image_to_method_composition_is_partial_same_owned_image(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);envelope,profile,a,s=setup_gnu(root)
            try:receipt=C.verify_gnu_unit(json.dumps(envelope).encode(),profile,a,s,root,signer)
            except C.Pending as failure:self.fail('Actual GNU method join missing: '+str(failure))
            with receipt:
                result=C.inspect_gnu_unit(receipt)
                self.assertEqual('UNIT_GNU_REACHED_METHODS_DATA',result['scope']);self.assertFalse(result['qualified'])
                self.assertEqual((('m','pkg/Fixture','m','(I)I'),('m','pkg/Fixture','nativeM','()I')),result['covered'])
                self.assertEqual(envelope['profile']['sha256'],result['image_profile_sha256'])
                r=envelope['record'];self.assertEqual((r['archive']['archive_sha256'],r['archive']['signature_sha256'],
                    r['archive']['signer'],r['source']['commit'],r['source']['build'],r['image']['member'],
                    r['image']['sha256'],r['image']['bytes']),result['image_identity'])
                self.assertEqual(('UNIT; no source/runtime authority',),result['image_pending'])
                self.assertIs(next(m for m in receipt.image.members if m[0]==r['image']['member']),receipt.member)
                self.assertTrue(receipt.image.directory.is_dir());self.assertEqual('UNAVAILABLE',result['runtime'])
            self.assertFalse(list(root.glob('signed-gnu-*')));self.assert_clean(root)

    def test_composed_gnu_root_ancestor_alias_refuses_and_close_preserves_replacement(self):
        with tempfile.TemporaryDirectory() as folder:
            base=Path(folder);root=base/'caller';root.mkdir(mode=0o700);env,p,a,s=setup_gnu(root)
            receipt=C.verify_gnu_unit(json.dumps(env).encode(),p,a,s,root,signer)
            self.assertFalse(C.inspect_gnu_unit(receipt)['qualified']);moved=base/'moved-original'
            g=C._gnu();changed=[]
            def replace():
                changed.append(True);root.rename(moved);root.symlink_to(moved,target_is_directory=True)
            try:
                with self.assertRaisesRegex(RuntimeError,'IMAGE_OWNER_DRIFT'):C.inspect_gnu_unit(receipt,replace)
                self.assertEqual([True],changed)
                with self.assertRaisesRegex(RuntimeError,'IMAGE_OWNER_DRIFT'):receipt.close()
                self.assertTrue(receipt.closed);self.assertTrue(receipt.image.closed);receipt.close()
                with self.assertRaisesRegex(C.Pending,'GNU_COMPONENT_CLOSED'):C.inspect_gnu_unit(receipt)
                self.assertTrue(root.is_symlink());self.assertTrue(moved.is_dir())
            finally:root.unlink();g['_helper']()['_remove'](moved)

    def test_gnu_profile_and_independent_record_joins_refuse_without_projection_prefix(self):
        for change,cause in [('profile-id','GNU_PROFILE_SCOPE'),('profile-sha','GNU_PROFILE_IDENTITY'),
            ('scope','GNU_PROFILE_SCOPE'),('archive','GNU_ARCHIVE_JOIN'),('signature','GNU_SIGNATURE_JOIN'),
            ('source','GNU_SOURCE_BUILD_JOIN'),('build','GNU_SOURCE_BUILD_JOIN'),('modules','GNU_MODULES_JOIN'),
            ('module-digest','GNU_AUTHENTICATED_MODULES_JOIN'),('mode','GNU_MODULES_MODE_JOIN'),
            ('parser','PARSER_SCOPE_DRIFT'),('required','REQUIRED_SET_DRIFT'),('tool','TOOL_DEPENDENCY_DRIFT')]:
            with self.subTest(change=change),tempfile.TemporaryDirectory() as folder:
                root=Path(folder);env,p,a,s=setup_gnu(root);r=env['record']
                if change=='profile-id':env['profile']['id']='foreign'
                elif change=='profile-sha':env['profile']['sha256']='0'*64
                elif change=='scope':env['profile']['scope']='ALL_REGULAR_PAYLOAD'
                elif change=='archive':r['archive']['archive_sha256']='0'*64
                elif change=='signature':r['archive']['signature_sha256']='0'*64
                elif change in ('source','build'):
                    r['source']['commit' if change=='source' else 'build']='foreign';r['archive'][change]='foreign'
                elif change=='modules':
                    r['image']['member']='jdk/lib/other';r['archive']['members']['jdk/lib/other']=r['archive']['members'].pop('jdk/lib/modules')
                elif change=='module-digest':r['image']['sha256']='0'*64;r['archive']['members']['jdk/lib/modules']['sha256']='0'*64
                elif change=='mode':r['archive']['members']['jdk/lib/modules']['mode']=0o755
                elif change=='parser':r['parser']['sha256']='0'*64
                elif change=='required':r['required']=[]
                else:r['tool']['files'][0]['sha256']='0'*64
                with self.assertRaisesRegex(RuntimeError,cause):C.verify_gnu_unit(json.dumps(env).encode(),p,a,s,root,signer)
                self.assertFalse(list(root.glob('signed-gnu-*')));self.assert_clean(root)

    def test_gnu_actual_semantic_comparison_and_old_entry_remain_distinct(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);env,p,a,s=setup_gnu(root,fixture(code=b'\x1a\x05\x60\xac'))
            with self.assertRaisesRegex(C.Pending,'REVIEWED_METHOD_DRIFT'):
                C.verify_gnu_unit(json.dumps(env).encode(),p,a,s,root,signer)
            with self.assertRaisesRegex(RuntimeError,'TAR_FORMAT_UNSUPPORTED'):
                self.verify(env['record'],a,s,root)
            with self.assertRaisesRegex(C.Pending,'GNU_METHOD_RECORD_SCHEMA'):
                C.verify_gnu_unit(json.dumps(env['record']).encode(),p,a,s,root,signer)
            self.assertFalse(list(root.glob('signed-gnu-*')));self.assert_clean(root)

    def test_gnu_foreign_same_bytes_path_never_replaces_issued_image_owner(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);env,p,a,s=setup_gnu(root);g=C._gnu();foreign=g['verify_unit'](p,a,s,root,signer)
            try:
                foreign_path=foreign.directory/env['record']['image']['member']
                with patch.dict(g,{'borrow_member':lambda *args:foreign_path}):
                    with self.assertRaisesRegex(C.Pending,'GNU_FOREIGN_MODULES_OWNER'):
                        C.verify_gnu_unit(json.dumps(env).encode(),p,a,s,root,signer)
                self.assertFalse(foreign.closed);self.assertEqual(1,len(list(root.glob('signed-gnu-*'))))
            finally:foreign.close()
            self.assert_clean(root);self.assertFalse(list(root.glob('signed-gnu-*')))

    def test_gnu_callback_closed_or_substituted_owner_and_published_lifetime_refuse(self):
        for change in ('closed','ancestor'):
            with self.subTest(change=change),tempfile.TemporaryDirectory() as folder:
                root=Path(folder);env,p,a,s=setup_gnu(root);g=C._gnu();actual=g['verify_unit'];issued=[];changed=[False]
                def remember(*args,**kwargs):
                    value=actual(*args,**kwargs);issued.append(value);return value
                def cancel():
                    if issued and not changed[0]:
                        changed[0]=True;image=issued[0]
                        if change=='closed':image.close()
                        else:
                            (image.directory/'jdk').chmod(0o755);lib=image.directory/'jdk/lib';lib.chmod(0o755)
                            lib.rename(image.stage/'original-lib');lib.symlink_to(image.stage/'original-lib',target_is_directory=True)
                with patch.dict(g,{'verify_unit':remember}):
                    with self.assertRaisesRegex(RuntimeError,'SIGNED_IMAGE_CLOSED' if change=='closed' else 'IMAGE_ANCESTOR_DRIFT'):
                        C.verify_gnu_unit(json.dumps(env).encode(),p,a,s,root,signer,cancel)
                self.assertFalse(list(root.glob('signed-gnu-*')));self.assert_clean(root)
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);env,p,a,s=setup_gnu(root);receipt=C.verify_gnu_unit(json.dumps(env).encode(),p,a,s,root,signer)
            with self.assertRaisesRegex(C.Pending,'SOURCE_GNU_COMPONENT_REQUIRED'):C.consume_gnu_component(receipt,'UNIT-record')
            with self.assertRaisesRegex(C.Pending,'COMPONENT_RECEIPT_REQUIRED'):C.inspect_unit_component(receipt)
            with self.assertRaisesRegex(RuntimeError,'REGISTERED_BOOT_RECEIPT_REQUIRED'):C._helper()['consume_host'](receipt,'UNIT',{}, {})
            with self.assertRaisesRegex(C.Pending,'GNU_COMPONENT_CLOSED'):C.inspect_gnu_unit(receipt,receipt.image.close)
            receipt.close();self.assertFalse(list(root.glob('signed-gnu-*')))
            for key in ({},None,'UNIT-record'):
                with self.assertRaisesRegex(C.Pending,'INDEPENDENT_GNU_COMPONENT_ABSENT'):C.verify_gnu_registered(key,a,s,root)
            self.assertEqual({},C.REGISTERED_GNU_COMPONENTS)

    def test_published_gnu_getters_reject_modules_replacement_or_mutation_before_and_during_callback(self):
        for access in ('public-unit','shared-consumer-body'):
            for when in ('before','callback'):
                for change in ('new-inode-same-bytes','in-place'):
                    with self.subTest(access=access,when=when,change=change),tempfile.TemporaryDirectory() as folder:
                        root=Path(folder);env,p,a,s=setup_gnu(root)
                        receipt=C.verify_gnu_unit(json.dumps(env).encode(),p,a,s,root,signer)
                        image=receipt.image;path=image.directory/env['record']['image']['member'];changed=[False]
                        def mutate():
                            if changed[0]:return
                            changed[0]=True;data=path.read_bytes()
                            if change=='new-inode-same-bytes':
                                fresh=image.stage/'replacement-modules';fresh.write_bytes(data);fresh.chmod(0o444)
                                path.parent.chmod(0o755);fresh.replace(path)
                            else:
                                altered=bytearray(data);altered[0]^=1;path.chmod(0o644);path.write_bytes(altered);path.chmod(0o444)
                        # _inspect_gnu is the shared getter body used by both UNIT
                        # inspection and SOURCE consumption. Calling it here stays
                        # UNIT; no fake SOURCE origin or registry is installed.
                        get=C.inspect_gnu_unit if access=='public-unit' else C._inspect_gnu
                        try:
                            unchanged=get(receipt,lambda:None)
                            self.assertEqual('UNIT_GNU_REACHED_METHODS_DATA',unchanged['scope'])
                            if when=='before':mutate()
                            with self.assertRaisesRegex(RuntimeError,'IMAGE_MEMBER_DRIFT'):
                                get(receipt,mutate if when=='callback' else lambda:None)
                            # Actual SOURCE entry's common pre-guard must reject
                            # the stale member before its separate origin refusal.
                            with self.assertRaisesRegex(RuntimeError,'IMAGE_MEMBER_DRIFT'):
                                C.consume_gnu_component(receipt,'UNIT-record')
                            with self.assertRaisesRegex(RuntimeError,'IMAGE_MEMBER_DRIFT'):
                                C.inspect_gnu_unit(receipt)
                        finally:receipt.close()
                        self.assertFalse(list(root.glob('signed-gnu-*')));self.assert_clean(root)
        self.assertEqual({},C.REGISTERED_GNU_COMPONENTS)

    def test_gnu_shared_clock_spans_sign_extract_and_actual_projector_wait(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);env,p,a,s=setup_gnu(root);b=C._helper();clock=[0.];extracted=[False];children=[]
            real=b['subprocess'].Popen
            def sign(*args):clock[0]=25.;return signer(*args)
            def cancel():
                if not extracted[0] and list(root.glob('signed-gnu-*/image')):extracted[0]=True;clock[0]=45.
            def popen(*args,**kwargs):
                child=real(*args,**kwargs);children.append(child);wait=child.wait;clock[0]=61.
                def checked_wait(timeout=None):
                    if timeout is not None:raise AssertionError('COMPOSITION_DEADLINE_RESET_AFTER_IMAGE')
                    return wait(timeout=timeout)
                child.wait=checked_wait;return child
            with patch.object(C,'monotonic',side_effect=lambda:clock[0]),patch.object(b['subprocess'],'Popen',side_effect=popen):
                with self.assertRaisesRegex(C.Pending,'GNU_COMPOSITION_DEADLINE'):
                    C.verify_gnu_unit(json.dumps(env).encode(),p,a,s,root,sign,cancel)
            self.assertTrue(extracted[0]);self.assertEqual(1,len(children));self.assertIsNotNone(children[0].poll())
            self.assertFalse(list(root.glob('signed-gnu-*')));self.assert_clean(root)

    def test_gnu_owned_child_cancel_primary_cleanup_and_close_only_failure(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);env,p,a,s=setup_gnu(root);b=C._helper();g=C._gnu();gb=g['_helper']();remove=gb['_remove']
            real=b['subprocess'].Popen;children=[]
            def popen(*args,**kwargs):child=real(*args,**kwargs);children.append(child);return child
            def cancel():
                if children:raise C.Pending('CONTROLLED_COMPOSITION_CANCEL')
            def cleanup(path):
                remove(path)
                if Path(path).name.startswith('signed-gnu-'):raise RuntimeError('SECONDARY_GNU_CLOSE')
            with patch.object(b['subprocess'],'Popen',side_effect=popen),patch.dict(gb,{'_remove':cleanup}):
                with self.assertRaisesRegex(C.Pending,'CONTROLLED_COMPOSITION_CANCEL'):
                    C.verify_gnu_unit(json.dumps(env).encode(),p,a,s,root,signer,cancel)
            self.assertEqual(1,len(children));self.assertIsNotNone(children[0].poll());self.assert_clean(root)
            self.assertFalse(list(root.glob('signed-gnu-*')))
            receipt=C.verify_gnu_unit(json.dumps(env).encode(),p,a,s,root,signer)
            with patch.dict(gb,{'_remove':cleanup}):
                with self.assertRaisesRegex(RuntimeError,'SECONDARY_GNU_CLOSE'):receipt.close()
            self.assertTrue(receipt.closed);self.assertFalse(list(root.glob('signed-gnu-*')))

    def test_body_descriptor_super_capture_annotation_exception_and_override_changes_refuse(self):
        changes=[({'code':b'\x1a\x05\x60\xac'},'REVIEWED_METHOD_DRIFT'),
                 ({'method_descriptor':'(J)I'},'METHOD_INVENTORY_DRIFT'),
                 ({'superclass':'java/lang/Number'},'CLASS_CONTEXT_DRIFT'),
                 ({'field_descriptor':'J'},'CLASS_CONTEXT_DRIFT'),
                 ({'annotation':True},'REVIEWED_METHOD_DRIFT'),
                 ({'exceptions':True},'REVIEWED_METHOD_DRIFT'),({'extra':True},'METHOD_INVENTORY_DRIFT')]
        for kwargs,cause in changes:
            with self.subTest(kwargs=kwargs),tempfile.TemporaryDirectory() as folder:
                root=Path(folder);r,a,s=setup(root);replace_image(r,a,fixture(**kwargs))
                with self.assertRaisesRegex(C.Pending,cause):self.verify(r,a,s,root)
                self.assert_clean(root)

    def test_ordered_handlers_and_bootstrap_arguments_are_not_ordinal_only(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);r,a,s=setup(root);handlers=((0,1,3,0),(1,3,3,0))
            replace_image(r,a,fixture(handlers=handlers));m=r['classes'][0]['methods'][0]
            m['expected']=list(m['expected']);body=list(m['expected'][3]);body[3]=[(0,1,3,None),(1,3,3,None)];m['expected'][3]=body
            with self.verify(r,a,s,root):pass
            replace_image(r,a,fixture(handlers=tuple(reversed(handlers))))
            with self.assertRaisesRegex(C.Pending,'REVIEWED_METHOD_DRIFT'):self.verify(r,a,s,root)
            self.assert_clean(root)
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);r,a,s=setup(root,dynamic=True)
            with self.verify(r,a,s,root):pass
            replace_image(r,a,fixture(dynamic=True,bootstrap_constant=8))
            with self.assertRaisesRegex(C.Pending,'BOOTSTRAP_BINDING_DRIFT'):self.verify(r,a,s,root)
            self.assert_clean(root)
            replace_image(r,a,fixture(dynamic=True,bootstrap_owner='pkg/Fixture',bootstrap_method='unused',
                                      bootstrap_descriptor='()I'))
            with self.assertRaisesRegex(C.Pending,'BOOTSTRAP_REACHES_OUTSIDE_REVIEW'):self.verify(r,a,s,root)
            self.assert_clean(root)

    def test_wide_constant_dynamic_loads_refuse_but_ordinary_wide_loads_remain_supported(self):
        for opcode in (18,19,20):
            for dynamic in (False,True):
                with self.subTest(opcode=opcode,dynamic=dynamic),tempfile.TemporaryDirectory() as folder:
                    root=Path(folder);r,a,s=setup(root);descriptor='(I)J' if opcode==20 else '(I)I'
                    data=fixture(load_opcode=opcode,constant_dynamic=dynamic,method_descriptor=descriptor)
                    replace_image(r,a,data)
                    constant=(17,0,(12,utf('constant'),utf('J' if opcode==20 else 'I'))) if dynamic else (
                              (5,'0000000000000007') if opcode==20 else (3,'00000007'))
                    m=r['classes'][0]['methods'][0];m['descriptor']=descriptor
                    # Strict parser canonicalizes ldc_w19 to ldc18. The raw
                    # fixture still executes its distinct u2 operand path.
                    normalized_opcode=18 if opcode==19 else opcode
                    m['expected']=(9,'m',descriptor,(2,1,((normalized_opcode,(constant,)),(173 if opcode==20 else 172,())),()),(),())
                    r['required'][0][-1]=descriptor
                    # Independent expected operand must match the actual strict parser,
                    # not just a forged normalized tuple sent directly to _bindings.
                    parsed=C._helper()['normalize_class'](data)
                    self.assertEqual(m['expected'],parsed['methods'][0])
                    if dynamic:
                        with self.assertRaisesRegex(C.Pending,'CONSTANT_DYNAMIC_UNSUPPORTED'):self.verify(r,a,s,root)
                    else:
                        with self.verify(r,a,s,root):pass
                    self.assert_clean(root)

    def test_actual_registered_signer_body_observes_shared_deadline_before_wait_and_reaps(self):
        # Execute the exact nested signer body under UNIT ownership, without
        # installing a fake registered record or bypassing registered guards.
        tree=ast.parse((W/'scripts/b-reached-method-component.py').read_text())
        entry=next(n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name=='verify_registered_component')
        signer_node=next(n for n in ast.walk(entry) if isinstance(n,ast.FunctionDef) and n.name=='signer')
        for secondary in (False,True):
            with self.subTest(secondary=secondary),tempfile.TemporaryDirectory() as folder:
                root=Path(folder);r,a,s=setup(root);b=C._helper();stage=root/'unit-sign';stage.mkdir()
                tool=root/'signer';tool.write_text('#!'+sys.executable+'\nraise SystemExit(0)\n');tool.chmod(0o700)
                keyring=root/'unit-keyring';keyring.write_bytes(b'UNIT non-key DATA')
                profile={'protocol':'GPGV_STATUS_FD1_PRIMARY_1','executable':str(tool),'keyring':str(keyring),'timeout':300,
                         'files':[{'path':str(p),'bytes':p.stat().st_size,'sha256':sha(p.read_bytes())} for p in (tool,keyring)]}
                # The exact helper signature call is observed; only its profile
                # is UNIT. No assertion of actual GPG/signature authority follows.
                original_signature=b['_real_signature'];children=[];clock=[0.];real=b['subprocess'].Popen
                def signature(profile_unused,archive,signature,fingerprint,owned,callback):
                    return original_signature(profile,archive,signature,fingerprint,owned,callback)
                def popen(*args,**kwargs):
                    child=real(*args,**kwargs);children.append(child);wait=child.wait;clock[0]=61.
                    def checked_wait(timeout=None):
                        if timeout is not None:raise AssertionError('SIGNER_SHARED_DEADLINE_NOT_CHECKED')
                        return wait(timeout=timeout)
                    child.wait=checked_wait;return child
                remove=b['_remove']
                def cleanup(path):
                    remove(path)
                    if secondary and Path(path).name.startswith('boot-verify-'):raise RuntimeError('secondary cleanup')
                env={'b':b,'stage':stage,'cancel':lambda:None,'signature_profile':profile}
                exec(compile(ast.Module(body=[signer_node],type_ignores=[]),'<exact registered signer body>','exec'),env)
                try:
                    with patch.dict(b,{'_real_signature':signature,'_remove':cleanup}),patch.object(
                            b['subprocess'],'Popen',side_effect=popen),patch.object(C,'monotonic',side_effect=lambda:clock[0]):
                        with self.assertRaisesRegex(C.Pending,'COMPONENT_DEADLINE'):
                            C._verify(C._record(json.dumps(r).encode()),a,s,root,env['signer'],C._UNIT,lambda:None)
                    self.assertEqual(1,len(children));self.assertIsNotNone(children[0].poll());self.assert_clean(root)
                finally:remove(stage)
    def test_outside_body_is_not_reviewed_or_promoted_and_context_only_is_explicit(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);r,a,s=setup(root);replace_image(r,a,fixture(unused_code=b'\x04\xac'))
            with self.verify(r,a,s,root) as receipt:
                self.assertEqual((('m','pkg/Fixture','unused','()I'),),C.inspect_unit_component(receipt)['outside'])
                with self.assertRaisesRegex(C.Pending,'REGISTERED_COMPONENT_REQUIRED'):C.consume_component(receipt,'UNIT-record')
                b=C._helper()
                with self.assertRaisesRegex(RuntimeError,'REGISTERED_BOOT_RECEIPT_REQUIRED'):
                    b['consume_host'](receipt,'UNIT-record',{}, {})
            bad=copy.deepcopy(r);bad['classes'][0]['methods'][1]['expected']=[9,'unused','()I',None,[],[]]
            with self.assertRaisesRegex(C.Pending,'OUTSIDE_REVIEW_BODY_OR_PROMOTION'):self.verify(bad,a,s,root)
            context=copy.deepcopy(r);m=context['classes'][0]['methods'][1];m['status']='DECLARATION_CONTEXT'
            m['review']={'id':'UNIT-declaration-only','input':context['classes'][0]['sha256']}
            context['required'].append(['m','pkg/Fixture','unused','()I'])
            with self.verify(context,a,s,root) as receipt:self.assertEqual((),C.inspect_unit_component(receipt)['outside'])
            self.assert_clean(root)

    def test_missing_duplicate_foreign_scope_parser_source_and_approval_refuse(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);r,a,s=setup(root)
            def doubled_class(x):x['classes'].append(copy.deepcopy(x['classes'][0]))
            def doubled_method(x):x['classes'][0]['methods'].append(copy.deepcopy(x['classes'][0]['methods'][0]))
            mutations=[(lambda x:x.update(required=[]),'REQUIRED_SET_DRIFT'),
              (lambda x:x['required'].append(x['required'][0]),'REQUIRED_SET_DRIFT'),
              (doubled_class,'CLASS_DUPLICATE'),(doubled_method,'METHOD_DUPLICATE'),
              (lambda x:x['parser'].update(sha256='0'*64),'PARSER_SCOPE_DRIFT'),
              (lambda x:x['source'].update(commit='foreign'),'SOURCE_ARCHIVE_SCOPE_DRIFT'),
              (lambda x:x['source'].pop('proof'),'SOURCE_SCOPE_ABSENT'),
              (lambda x:x['approval'].update(id=''),'RECORD_APPROVAL'),
              (lambda x:x['approval'].update(kind='INDEPENDENT_SOURCE_RECORD_1'),'UNIT_APPROVAL_REQUIRED'),
              (lambda x:x['image'].update(sha256='0'*64),'IMAGE_ARCHIVE_JOIN'),
              (lambda x:x['classes'][0]['methods'][0]['review'].update(input='0'*64),'SOURCE_REVIEW_INPUT_JOIN')]
            for mutate,cause in mutations:
                with self.subTest(cause=cause):
                    bad=copy.deepcopy(r);mutate(bad)
                    with self.assertRaisesRegex(C.Pending,cause):self.verify(bad,a,s,root)
                    self.assert_clean(root)
            for caller_key in (None,{},'UNIT-record'):
                with self.assertRaisesRegex(C.Pending,'INDEPENDENT_COMPONENT_RECORD_ABSENT'):
                    C.verify_registered_component(caller_key,a,s,root)
            self.assertEqual({},C.REGISTERED_COMPONENTS)

    def test_schema_bounds_duplicate_json_and_unknown_class_grammar_refuse_without_prefix(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);r,a,s=setup(root)
            inputs=[b'{"schema":1,"schema":2}',b'['*65+b']'*65,b'{}'+b' '*(C.MAX_RECORD+1)]
            for raw in inputs:
                with self.assertRaises(C.Pending):C.verify_unit_component(raw,a,s,root,signer)
                self.assert_clean(root)
            bad=copy.deepcopy(r);bad['classes']=[r['classes'][0]]*65
            with self.assertRaisesRegex(C.Pending,'CLASS_COUNT'):self.verify(bad,a,s,root)
            bad=copy.deepcopy(r);bad['classes'][0]['methods']=[r['classes'][0]['methods'][0]]*513
            with self.assertRaisesRegex(C.Pending,'METHOD_COUNT'):self.verify(bad,a,s,root)
            # Existing strict grammar remains the parser; major18 is refused.
            data=bytearray(fixture());data[7]=62;replace_image(r,a,bytes(data))
            with self.assertRaisesRegex(RuntimeError,'CLASS_VERSION'):self.verify(r,a,s,root)
            self.assert_clean(root)

    def test_missing_owned_archive_and_tool_inputs_refuse_before_staging(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);r,a,s=setup(root)
            for section,key in [('archive','archive_sha256'),('archive','signature_bytes'),
                                ('archive','signature_sha256'),('archive','signer'),
                                ('tool','executable'),('tool','timeout')]:
                with self.subTest(section=section,key=key):
                    bad=copy.deepcopy(r);bad[section].pop(key)
                    with self.assertRaises(C.Pending):self.verify(bad,a,s,root)
                    self.assert_clean(root)

    def test_tool_and_foreign_projected_class_identity_are_bound(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);r,a,s=setup(root);tool=Path(r['tool']['executable'])
            original=tool.read_text();tool.write_text(original.replace('Path(sys.argv[6])','Path(sys.argv[7])'))
            with self.assertRaisesRegex(RuntimeError,'TOOL_DEPENDENCY_DRIFT'):self.verify(r,a,s,root)
            tool.write_text(original.replace('Path(sys.argv[6]).read_bytes()',repr(fixture(code=b'\x03\xac'))))
            update_tool(r)
            with self.assertRaisesRegex(C.Pending,'PROJECTED_CLASS_SIZE|CLASS_IDENTITY_DRIFT'):self.verify(r,a,s,root)
            self.assert_clean(root)

    def test_borrowed_image_close_and_receipt_callback_invalidation_refuse(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);r,a,s=setup(root);b=C._helper();seen=[];actual=b['verify_unit']
            def remember(*args,**kwargs):
                result=actual(*args,**kwargs);seen.append(result);return result
            def close():
                if seen:seen[0].close()
            with patch.dict(b,{'verify_unit':remember}):
                with self.assertRaisesRegex(C.Pending,'BORROWED_IMAGE_CLOSED'):self.verify(r,a,s,root,close)
            self.assert_clean(root)
            receipt=self.verify(r,a,s,root)
            with self.assertRaisesRegex(C.Pending,'COMPONENT_CLOSED'):
                C.inspect_unit_component(receipt,receipt.close)
            with self.assertRaisesRegex(C.Pending,'COMPONENT_CLOSED'):C.inspect_unit_component(receipt)
            self.assert_clean(root)

    def test_owned_child_cancel_deadline_and_primary_cleanup_preserve_no_result(self):
        for deadline in (False,True):
            with self.subTest(deadline=deadline),tempfile.TemporaryDirectory() as folder:
                root=Path(folder);r,a,s=setup(root);b=C._helper();children=[];real=b['subprocess'].Popen;time=[0.]
                def popen(*args,**kwargs):
                    child=real(*args,**kwargs);children.append(child)
                    if deadline:time[0]=61.
                    return child
                def cancel():
                    if children and not deadline:raise C.Pending('CONTROLLED_CANCEL')
                with patch.object(b['subprocess'],'Popen',side_effect=popen),patch.object(C,'monotonic',side_effect=lambda:time[0]):
                    with self.assertRaisesRegex(C.Pending,'COMPONENT_DEADLINE' if deadline else 'CONTROLLED_CANCEL'):
                        self.verify(r,a,s,root,cancel)
                self.assertEqual(1,len(children));self.assertIsNotNone(children[0].poll());self.assert_clean(root)
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);r,a,s=setup(root);b=C._helper();tool=Path(r['tool']['executable'])
            tool.write_text('#!'+sys.executable+'\nraise SystemExit(7)\n');tool.chmod(0o700);update_tool(r)
            remove=b['_remove']
            def fail_after_remove(path):
                remove(path)
                if Path(path).name.startswith(('reached-project-','boot-verify-')):
                    raise RuntimeError('bounded secondary')
            with patch.dict(b,{'_remove':fail_after_remove}):
                with self.assertRaisesRegex(C.Pending,'PROJECTION_TOOL_FAILURE'):self.verify(r,a,s,root)
            self.assert_clean(root)
