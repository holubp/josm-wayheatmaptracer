"""Bounded DATA fixtures only; no supplier archive is downloaded by tests."""
from pathlib import Path
import base64
import copy
import gzip
import hashlib
import importlib.util
import io
import json
import os
import re
import sys
import struct
import tarfile
import tempfile
import unittest
from unittest.mock import patch

W=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('boot_collect',W/'scripts/b-boot-bundle-collect.py')
C=importlib.util.module_from_spec(spec);spec.loader.exec_module(C)

# Actual first signed archive header retained by DATA CI40151639608. This is a
# public format witness, not a signed archive/approval/runtime substitute.
CI_GNU_HEADER=base64.b64decode(
    'amRrLTE3LjAuMTIrNy8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADAwMDA3NTUAMDAwMDAwMAAwMDAwMDAwADAwMDAwMDAwMDAwADE0NjQ1NTcyNjQ2ADAxMTMxNQAgNQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB1c3RhciAgAHJvb3QAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAcm9vdAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=')

def fixture_class():
    def u2(n):return struct.pack('>H',n)
    cp=[]
    for name in (b'java/lang/Cloneable',b'java/lang/Object'):cp.extend([b'\x01'+u2(len(name))+name,b'\x07'+u2(len(cp)+1)])
    return b'\xca\xfe\xba\xbe'+u2(0)+u2(61)+u2(5)+b''.join(cp)+u2(0x601)+u2(2)+u2(4)+u2(0)*4

def setup(root,**changes):
    b=dict(C.BOUNDS);b.update(changes);e=C.Evidence(root/'public',b);return b,e

def tar_bytes(members,fmt=tarfile.USTAR_FORMAT):
    output=io.BytesIO()
    with tarfile.open(fileobj=output,mode='w',format=fmt) as tar:
        for name,data in members:
            item=tarfile.TarInfo(name);item.size=len(data);item.mode=0o644
            tar.addfile(item,io.BytesIO(data))
    return gzip.compress(output.getvalue())

def changed_header(data,changes):
    raw=bytearray(gzip.decompress(data));header=bytearray(raw[:512])
    for start,value in changes:header[start:start+len(value)]=value
    header[148:156]=b' '*8
    header[148:156]=('%06o\0 '%sum(header)).encode('ascii')
    raw[:512]=header;return gzip.compress(raw)

class CollectorTests(unittest.TestCase):
    def test_actual_ci_oldgnu_directory_then_regular_modules_is_data(self):
        self.assertEqual(512,len(CI_GNU_HEADER))
        self.assertEqual('b037bb5163b92dbc253db0305d162e8bb64e8c5e54a22674576e3224ede8235b',
                         hashlib.sha256(CI_GNU_HEADER).hexdigest())
        synthetic=gzip.decompress(tar_bytes([('jdk-17.0.12+7/lib/modules',b'TINY_IMAGE')],tarfile.GNU_FORMAT))
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);archive=root/'signed';archive.write_bytes(gzip.compress(CI_GNU_HEADER+synthetic));b,e=setup(root)
            try:result=C.inventory_archive(archive,root,e,b,lambda:None)
            except C.Failure as failure:self.fail('Actual ordinary GNU header rejected: '+str(failure))
            self.assertEqual(2,result['headers']);self.assertEqual(b'TINY_IMAGE',result['modules'].read_bytes())
            record=json.loads((e.root/'reports/tar/header-00001.json.part0000').read_text())
            self.assertEqual('jdk-17.0.12+7',record['name']);self.assertEqual('OLDGNU',record['format'])
            self.assertFalse((root/'jdk-17.0.12+7').exists())

    def test_oldgnu_times_are_metadata_not_ustar_path_prefix(self):
        data=changed_header(tar_bytes([('jdk/lib/modules',b'DATA')],tarfile.GNU_FORMAT),
                            [(345,b'00000000001\0'),(357,b'00000000002\0')])
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);archive=root/'signed';archive.write_bytes(data);b,e=setup(root)
            result=C.inventory_archive(archive,root,e,b,lambda:None)
            self.assertEqual('jdk/lib/modules',result['name']);self.assertEqual(b'DATA',result['modules'].read_bytes())
            record=json.loads((e.root/'reports/tar/header-00001.json.part0000').read_text())
            self.assertEqual(1,record['atime']);self.assertEqual(2,record['ctime'])

    def test_oldgnu_extension_fields_and_record_types_refuse_before_selection(self):
        ordinary=tar_bytes([('jdk/lib/modules',b'DATA')],tarfile.GNU_FORMAT)
        # offset, longnames, unused byte, sparse offset/length, extended flag,
        # real size and reserved tail cannot silently acquire ordinary semantics.
        changes=[(369,b'00000000001\0'),(381,b'x'),(385,b'x'),(386,b'1'),
                 (398,b'1'),(482,b'1'),(483,b'1'),(495,b'x')]
        changes += [(156,kind) for kind in (b'x',b'g',b'L',b'K',b'S',b'M',b'V',b'D',b'N',b'X')]
        for change in changes:
            with self.subTest(change=change),tempfile.TemporaryDirectory() as folder:
                root=Path(folder);archive=root/'signed';archive.write_bytes(changed_header(ordinary,[change]));b,e=setup(root)
                with self.assertRaisesRegex(C.Failure,'TAR_(EXTENSION|TYPE)'):
                    C.inventory_archive(archive,root,e,b,lambda:None)
                self.assertFalse((root/'selected-modules').exists())

    def test_oldgnu_keeps_checksum_path_size_and_cancellation_guards(self):
        ordinary=tar_bytes([('jdk/lib/modules',b'DATA')],tarfile.GNU_FORMAT)
        bad=bytearray(gzip.decompress(ordinary));bad[0]^=1
        controls=[(gzip.compress(bad),{},'TAR_CHECKSUM'),
                  (tar_bytes([('../escape',b'DATA')],tarfile.GNU_FORMAT),{},'TAR_PATH'),
                  (ordinary,{'modules':3},'MODULES_SIZE_LIMIT')]
        for data,limits,code in controls:
            with self.subTest(code=code),tempfile.TemporaryDirectory() as folder:
                root=Path(folder);archive=root/'signed';archive.write_bytes(data);b,e=setup(root,**limits)
                with self.assertRaisesRegex(RuntimeError,code):C.inventory_archive(archive,root,e,b,lambda:None)
                self.assertFalse((root/'selected-modules').exists())
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);archive=root/'signed';archive.write_bytes(ordinary);b,e=setup(root)
            def cancel():raise C.Failure('CONTROLLED_CANCEL')
            with self.assertRaisesRegex(C.Failure,'CONTROLLED_CANCEL'):C.inventory_archive(archive,root,e,b,cancel)
            self.assertFalse((root/'selected-modules').exists())

    def test_ustar_prefix_remains_a_path_prefix(self):
        name='j'*90+'/lib/modules'
        data=tar_bytes([(name,b'USTAR')])
        self.assertTrue(any(gzip.decompress(data)[345:500]))
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);archive=root/'signed';archive.write_bytes(data);b,e=setup(root)
            result=C.inventory_archive(archive,root,e,b,lambda:None)
            self.assertEqual(name,result['name']);self.assertEqual(b'USTAR',result['modules'].read_bytes())
            record=json.loads((e.root/'reports/tar/header-00001.json.part0000').read_text())
            self.assertEqual('USTAR',record['format'])

    def test_actual_signed_stage_inventory_selects_only_regular_modules(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);archive=root/'signed.gz'
            archive.write_bytes(tar_bytes([('jdk/bin/java',b'NEVER_EXECUTE'),('jdk/lib/modules',b'JIMAGE_DATA')]))
            b,e=setup(root);result=C.inventory_archive(archive,root,e,b,lambda:None)
            self.assertEqual(b'JIMAGE_DATA',result['modules'].read_bytes())
            self.assertEqual(2,result['headers']);self.assertFalse((root/'jdk/bin/java').exists())

    def test_actual_tar_extensions_links_traversal_duplicate_and_size_limits(self):
        def special(kind,name='jdk/link',link='elsewhere'):
            out=io.BytesIO()
            with tarfile.open(fileobj=out,mode='w',format=tarfile.USTAR_FORMAT) as tar:
                item=tarfile.TarInfo(name);item.type=kind;item.linkname=link;tar.addfile(item)
            return gzip.compress(out.getvalue())
        controls=[(special(b'x'),'TAR_EXTENSION'),(special(b'2','jdk/lib/modules'),'MODULES_NONREGULAR'),
                  (tar_bytes([('../escape',b'x')]),'TAR_PATH'),
                  (tar_bytes([('jdk/lib/modules',b'1'),('jdk/lib/modules',b'2')]),'TAR_DUPLICATE'),
                  (tar_bytes([('a/lib/modules',b'1'),('b/lib/modules',b'2')]),'MODULES_DUPLICATE')]
        for data,code in controls:
            with self.subTest(code=code),tempfile.TemporaryDirectory() as folder:
                root=Path(folder);archive=root/'signed';archive.write_bytes(data);b,e=setup(root)
                with self.assertRaisesRegex(RuntimeError,code):C.inventory_archive(archive,root,e,b,lambda:None)
                self.assertFalse((root.parent/'escape').exists())
        for limits,code in [({'modules':1},'MODULES_SIZE_LIMIT'),({'expanded':1024},'TAR_DECLARED_EXPANSION'),
                            ({'headers':1},'TAR_HEADER_LIMIT')]:
            with tempfile.TemporaryDirectory() as folder:
                root=Path(folder);archive=root/'signed';archive.write_bytes(tar_bytes([('jdk/file',b'1'),('jdk/lib/modules',b'12')]))
                b,e=setup(root,**limits)
                with self.assertRaisesRegex(RuntimeError,code):C.inventory_archive(archive,root,e,b,lambda:None)

    def test_real_link_header_is_inventory_only_and_never_followed(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);outside=root/'outside';outside.mkdir();outside.chmod(0o755)
            (outside/'keep').write_bytes(b'KEEP');out=io.BytesIO()
            with tarfile.open(fileobj=out,mode='w',format=tarfile.USTAR_FORMAT) as tar:
                item=tarfile.TarInfo('jdk/link');item.type=tarfile.SYMTYPE;item.linkname=str(outside);tar.addfile(item)
                item=tarfile.TarInfo('jdk/lib/modules');item.size=4;tar.addfile(item,io.BytesIO(b'DATA'))
            archive=root/'signed';archive.write_bytes(gzip.compress(out.getvalue()));b,e=setup(root)
            result=C.inventory_archive(archive,root,e,b,lambda:None)
            self.assertEqual(2,result['headers']);self.assertFalse((root/'jdk/link').exists())
            self.assertEqual(0o755,outside.stat().st_mode&0o777);self.assertEqual(b'KEEP',(outside/'keep').read_bytes())

    def test_output_total_leaf_and_terminal_reservation_are_independent(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);b,e=setup(root,leaf=512,output=2048)
            e.chunks('raw/small',[b'x'*1536])
            with self.assertRaisesRegex(C.Failure,'OUTPUT_TOTAL_LIMIT'):e.bytes('raw/too-much',b'x')
            status={'qualified':False,'caseCount':0,'candidateExecutions':0,'outcome':'COLLECTED_DATA_ONLY'}
            e.finish(status);self.assertEqual('PARTIAL_OUTPUT',status['outcome'])
            self.assertTrue((e.root/'reports/status.json').is_file())
            self.assertLessEqual(sum(p.stat().st_size for p in e.root.rglob('*') if p.is_file()),2048)
            with self.assertRaisesRegex(C.Failure,'OUTPUT_NAME'):e.bytes('raw/../outside',b'x')
            with self.assertRaisesRegex(C.Failure,'OUTPUT_LEAF_LIMIT'):e.bytes('raw/overleaf',b'x'*513)

    def test_clock_cancel_deadline_has_no_reset(self):
        time=[0.];clock=C.Clock(10,clock=lambda:time[0]);time[0]=9.;self.assertEqual(1,clock.remaining())
        time[0]=10.
        with self.assertRaisesRegex(C.Failure,'COLLECTOR_DEADLINE'):clock.check()
        failure=C.Failure('CONTROLLED_CANCEL')
        def cancel():raise failure
        with self.assertRaises(C.Failure) as caught:C.Clock(10,cancel=cancel).check()
        self.assertIs(failure,caught.exception)

    def test_source_metadata_pin_and_class_family_drift(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'metadata'
            value={'sha256':C.ARCHIVE_SHA256,'scmRef':'jdk-17.0.12+7_adopt',
                   'openjdk_source':'https://github.com/adoptium/jdk17u/commit/'+C.SOURCE,
                   'buildRef':'https://github.com/adoptium/temurin-build/commit/'+C.BUILD}
            path.write_text(json.dumps(value));C._metadata(path)
            for name in value:
                wrong=dict(value,**{name:'DRIFT'});path.write_text(json.dumps(wrong))
                with self.assertRaisesRegex(C.Failure,'SUPPLIER_METADATA_DRIFT'):C._metadata(path)
        text=b'jimage: DATA\n\nModule: java.base\n java/lang/Cloneable.class\n'
        self.assertEqual(['java.base/java/lang/Cloneable.class'],C._listed_names(text,'java/lang/Cloneable'))
        for changed in (text.replace(b'java.base',b'other'),text.replace(b'Cloneable.class',b'Other.class')):
            with self.assertRaises(C.Failure):C._listed_names(changed,'java/lang/Cloneable')
        with patch.object(C,'HELPER_SHA256','0'*64):
            with self.assertRaisesRegex(C.Failure,'HELPER_SOURCE_DRIFT'):C.dependency()

    def test_signature_checksum_primary_policy_and_posttool_mutation(self):
        base=C.dependency()
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);archive=root/'archive';archive.write_bytes(b'UNIT')
            sig=root/'sig';sig.write_bytes(b'UNIT');key=root/'key';key.write_bytes(b'UNIT')
            home=root/'home';home.mkdir();clock=C.Clock(10);tools={'gpgv':{'path':'/UNIT/NOT_EXECUTED'}}
            expected=hashlib.sha256(b'UNIT').hexdigest()
            good=b'[GNUPG:] VALIDSIG '+C.FINGERPRINT.encode()+b' 2026-01-01 0 0 4 0 1 8 00\n'
            for i,(code,out) in enumerate([(1,b''),(0,good.replace(C.FINGERPRINT.encode(),b'A'*40)),
                (0,good.replace(C.FINGERPRINT.encode(),b'B'*40).rstrip()+b' '+C.FINGERPRINT.encode()+b'\n')]):
                case=root/str(i);case.mkdir();b,e=setup(case)
                with patch.object(C,'run_tool',return_value=(code,out)):
                    with self.assertRaises(RuntimeError):C.authenticate(archive,sig,key,home,tools,root,e,clock,base,expected)
            case=root/'good';case.mkdir();b,e=setup(case)
            with patch.object(C,'run_tool',return_value=(0,good)):
                C.authenticate(archive,sig,key,home,tools,root,e,clock,base,expected)
            case=root/'mutation';case.mkdir();b,e=setup(case)
            def mutate(*args):archive.write_bytes(b'EVIL');return 0,good
            with patch.object(C,'run_tool',side_effect=mutate):
                with self.assertRaisesRegex(C.Failure,'SIGNED_ARCHIVE_MUTATION'):
                    C.authenticate(archive,sig,key,home,tools,root,e,clock,base,expected)

    def test_https_stream_bounds_truncation_and_redirect_host_policy(self):
        class Response(io.BytesIO):
            status=200
            def __init__(self,data,length):super().__init__(data);self.headers={} if length is None else {'Content-Length':length}
        class Opener:
            def __init__(self,response):self.response=response
            def open(self,*args,**kwargs):return self.response
        for data,length,limit,code in [(b'XX','100',1,'FETCH_DECLARED_LIMIT'),(b'XX',None,1,'FETCH_ACTUAL_LIMIT'),
                                       (b'X','2',2,'FETCH_TRUNCATED')]:
            with self.subTest(code=code),tempfile.TemporaryDirectory() as folder:
                with patch.object(C.urllib.request,'build_opener',return_value=Opener(Response(data,length))):
                    with self.assertRaisesRegex(C.Failure,code):C.fetch('key',Path(folder)/'raw',limit,C.Clock(10))
        redirect=C._Redirect({'keyserver.ubuntu.com'})
        with self.assertRaisesRegex(C.Failure,'FETCH_REDIRECT_PROFILE'):
            redirect.redirect_request(None,None,302,'',{},'https://arbitrary.example/key')

    def test_source_pin_failure_is_typed_and_terminal_even_before_stage_allocation(self):
        with tempfile.TemporaryDirectory() as folder,patch.object(C,'HELPER_SHA256','0'*64):
            root=Path(folder);result=C.collect(root/'public')
            self.assertEqual('HELPER_SOURCE_DRIFT',result['cause']);self.assertEqual('NOT_REACHED',result['cleanup'])
            self.assertTrue((root/'public/reports/status.json').exists())

    def test_real_existing_jimage_discovery_projection_and_typed_attributes(self):
        base=C.dependency();tools=C.runner_tools(base);tool=tools['jimage']['path']
        image=Path(tool).parents[1]/'lib/modules';self.assertTrue(image.is_file())
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);b,e=setup(root);clock=C.Clock(30)
            result=C.observe_projection(image,tool,root,e,b,clock,base,('java/lang/Cloneable',))
            outputs=list((e.root/'classes/java.base/java/lang').glob('Cloneable.class.bin.part*'))
            self.assertTrue(outputs);self.assertLessEqual(outputs[0].stat().st_size,65536)
            data=b''.join(p.read_bytes() for p in sorted(outputs));major=int.from_bytes(data[6:8],'big')
            self.assertEqual({'classes':1,'unsupported':0 if major==61 else 1},result)
            record=json.loads((e.root/'reports/classes/000.json.part0000').read_text())
            if major!=61:self.assertEqual('CLASS_VERSION',record['cause'])
            else:self.assertEqual((7,('utf',b'java/lang/Cloneable'.hex())),base['normalize_class'](data)['name'])
            # Deliberate parser refusal on the SAME real raw projection retains
            # the raw class. No altered helper is installed/qualified.
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);b,e=setup(root);refuse=dict(base)
            def unsupported(data):raise base['Pending']('CLASS_METHOD_ATTRIBUTE_UNSUPPORTED')
            refuse['normalize_class']=unsupported
            result=C.observe_projection(image,tool,root,e,b,C.Clock(30),refuse,('java/lang/Cloneable',))
            self.assertEqual(1,result['unsupported']);self.assertTrue(list((e.root/'classes').rglob('*.part0000')))

    def test_collector_failure_cleans_stage_preserves_unrelated_and_emits_only_data(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);unrelated=root/'keep';unrelated.write_bytes(b'KEEP')
            with patch.object(C,'runner_tools',side_effect=C.Failure('RUNNER_TOOL_MISSING')):
                result=C.collect(root/'public')
            self.assertEqual('RUNNER_TOOL_MISSING',result['cause']);self.assertEqual('COMPLETE',result['cleanup'])
            self.assertFalse(result['qualified']);self.assertEqual(0,result['candidateExecutions'])
            self.assertTrue((root/'public/reports/status.json').exists());self.assertFalse(list(root.glob('boot-data-*')))
            self.assertEqual(b'KEEP',unrelated.read_bytes())

    def test_collect_cancel_no_follow_and_secondary_cleanup_preserve_primary_cause(self):
        base=C.dependency()
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);outside=root/'outside';outside.mkdir();outside.chmod(0o755)
            (outside/'keep').write_bytes(b'KEEP')
            def cancel_tools(*args):
                stage=next(root.glob('boot-data-*'));(stage/'bad-link').symlink_to(outside,target_is_directory=True)
                raise C.Failure('CONTROLLED_CANCEL')
            with patch.object(C,'runner_tools',side_effect=cancel_tools):result=C.collect(root/'public')
            self.assertEqual('CONTROLLED_CANCEL',result['cause']);self.assertEqual('COMPLETE',result['cleanup'])
            self.assertEqual(0o755,outside.stat().st_mode&0o777);self.assertEqual(b'KEEP',(outside/'keep').read_bytes())
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);altered=dict(base)
            def cleanup(path):base['_remove'](path);raise RuntimeError('secondary is never published')
            altered['_remove']=cleanup
            with patch.object(C,'dependency',return_value=altered),patch.object(C,'runner_tools',side_effect=C.Failure('CONTROLLED_PRIMARY')):
                result=C.collect(root/'public')
            self.assertEqual('CONTROLLED_PRIMARY',result['cause']);self.assertEqual('FAILED',result['cleanup'])
            self.assertFalse(list(root.glob('boot-data-*')))

    def test_actual_owned_helper_child_is_reaped_on_deterministic_cancel(self):
        base=C.dependency();children=[];calls=0
        real=base['subprocess'].Popen
        def popen(*args,**kwargs):
            child=real(*args,**kwargs);children.append(child);return child
        def cancel():
            nonlocal calls
            calls+=1
            if children:raise C.Failure('CONTROLLED_CHILD_CANCEL')
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);b,e=setup(root);clock=C.Clock(10,cancel=cancel)
            with patch.object(base['subprocess'],'Popen',side_effect=popen):
                with self.assertRaisesRegex(C.Failure,'CONTROLLED_CHILD_CANCEL'):
                    C.run_tool((sys.executable,'-c','import time; time.sleep(30)'),root/'tool',clock,e,'cancel',base)
            self.assertEqual(1,len(children));self.assertIsNotNone(children[0].poll())

    def test_cli_refuses_archive_acquisition_outside_manual_ci_before_download(self):
        with patch.dict(os.environ,{},clear=True),patch.object(sys,'argv',['collector','--collect','--output','/unused']),patch.object(C,'collect') as collect:
            with self.assertRaisesRegex(C.Failure,'MANUAL_CI_ONLY'):C.main()
            self.assertFalse(collect.called)

    def test_workflow_is_manual_public_scoped_pinned_and_has_no_install_or_candidate(self):
        text=(W/'.github/workflows/public-python-diagnostics.yml').read_text()
        self.assertIn('workflow_dispatch:',text);self.assertIn('contents: read',text)
        self.assertIn('persist-credentials: false',text);self.assertIn('timeout-minutes: 15',text)
        for disallowed in ('push:','pull_request','setup-java','pip install','sudo ','gradlew','--candidate','secrets.'):
            self.assertNotIn(disallowed,text)
        self.assertIn('actions/checkout@11d5960a326750d5838078e36cf38b85af677262',text)
        self.assertIn('actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02',text)
        self.assertNotIn('build/**',text)
        self.assertRegex(text,r'(?m)^on:\n  workflow_dispatch:\n    inputs:\n      collection:\n'
                             r'        description: Fixed PUBLIC DATA collection\n'
                             r'        type: choice\n        required: true\n'
                             r'        default: boot-artifact\n        options:\n'
                             r'          - boot-artifact\n          - tool-metadata\n\npermissions:')
        self.assertIn('case "$COLLECTION" in boot-artifact|tool-metadata) ;; *) exit 1 ;; esac',text)
        self.assertIn('COLLECTION: ${{ inputs.collection }}',text)
        self.assertIn('ref: ${{ github.sha }}',text)
        self.assertIn('test "$(git rev-parse HEAD)" = "$GITHUB_SHA"',text)
        steps=re.split(r'(?m)^      - ',text)[1:]
        uploads=[step for step in steps if re.search(r'(?m)^        uses: actions/upload-artifact@',step)]
        self.assertEqual(2,len(uploads))
        for mode,artifact,folder,prefixes in (
                ('boot-artifact','boot-artifact-public-data','boot-evidence-public',
                 ('reports','raw','classes','methods','inventory')),
                ('tool-metadata','tool-acquisition-public-data','tool-evidence-public',
                 ('reports','inventory'))):
            matches=[step for step in uploads if '\n          name: '+artifact+'\n' in step]
            self.assertEqual(1,len(matches),mode)
            upload=matches[0]
            self.assertIn("\n        if: ${{ always() && inputs.collection == '"+mode+"' }}\n",upload)
            self.assertIn('\n        uses: actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02\n',upload)
            paths=re.search(r'(?m)^          path: \|\n((?:            [^\n]+\n)+)',upload)
            self.assertIsNotNone(paths,mode)
            self.assertEqual(['${{ runner.temp }}/'+folder+'/'+prefix+'/' for prefix in prefixes],
                             [line.strip() for line in paths.group(1).splitlines()])
            self.assertIn('\n          if-no-files-found: warn\n',upload)
            self.assertIn('\n          retention-days: 5',upload)
        self.assertEqual(
            ['actions/checkout@11d5960a326750d5838078e36cf38b85af677262']+
            ['actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02']*2,
            [left or right for left,right in re.findall(r'(?m)^      - uses: (\S+)|^        uses: (\S+)',text)])
        for name,file in [('COLLECTOR_SHA256','scripts/b-boot-bundle-collect.py'),
                          ('TEST_SHA256','scripts/tests/test_b_boot_bundle_collect.py'),
                          ('HELPER_SHA256','scripts/b-boot-bundle-verifier.py'),
                          ('HELPER_TEST_SHA256','scripts/tests/test_b_boot_bundle_verifier.py'),
                          ('TOOL_DATA_SHA256','scripts/b-tool-acquisition-data.py'),
                          ('TOOL_DATA_TEST_SHA256','scripts/tests/test_b_tool_acquisition_data.py')]:
            match=re.search(r'(?m)^          '+name+r': ([0-9a-f]{64})$',text)
            self.assertIsNotNone(match,name)
            self.assertEqual(hashlib.sha256((W/file).read_bytes()).hexdigest(),match.group(1))
            self.assertIn('printf \'%s  %s\\n\' "$'+name+'" '+file+' | sha256sum --check --status',text)

if __name__=='__main__':unittest.main()
