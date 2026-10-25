"""Actual synthetic GNU images and simulated HOST; no privilege operations."""
from pathlib import Path
from contextlib import contextmanager
import copy
import hashlib
import importlib.util
import io
import json
import tarfile
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


FIX = load(Path(__file__).with_name('test_b_reached_method_component.py'), 'gnu_host_fixture')
HOST = load(ROOT/'scripts/b-trusted-host-producer.py', 'gnu_host_test_host')


def fixture(root):
    envelope, _, archive, signature = FIX.setup_gnu(root)
    record = envelope['record']
    data = FIX.fixture()
    entries = [('jdk/lib/modules', data, 0o644), ('jdk/bin/java', b'UNIT-java-not-executed', 0o755),
               ('jdk/bin/javac', b'UNIT-javac-not-executed', 0o755),
               ('jdk/lib/libjava.so', b'UNIT-native-not-executed', 0o644)]
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode='w', format=tarfile.GNU_FORMAT) as tar:
        item = tarfile.TarInfo('jdk');item.type=tarfile.DIRTYPE;item.mode=0o755;tar.addfile(item)
        for name, body, mode in entries:
            item=tarfile.TarInfo(name);item.size=len(body);item.mode=mode;tar.addfile(item,io.BytesIO(body))
    archive.write_bytes(stream.getvalue())
    profile = {'schema':'SIGNED_OLDGNU_IMAGE_1','id':'UNIT-host-image','approval':'UNIT_ONLY',
               'scope':'ALL_REGULAR_PAYLOAD','root':'jdk','modules':'jdk/lib/modules',
               'source':'UNIT-source','build':'UNIT-build','pending':['UNIT-no-platform-authority'],
               'archive':{'bytes':archive.stat().st_size,'sha256':FIX.sha(archive.read_bytes()),
                          'tar_bytes':archive.stat().st_size,'compression':'none'},
               'signature':{'bytes':signature.stat().st_size,'sha256':FIX.sha(signature.read_bytes()),'primary':'A'*40},
               'limits':{'headers':5,'member_bytes':65536,'output_bytes':65536},
               'members':[{'name':'jdk','kind':'directory','bytes':0,'mode':0o755,'policy':'materialize','required':True}]+
                         [{'name':n,'kind':'regular','bytes':len(b),'mode':m,'policy':'materialize','required':True}
                          for n,b,m in entries]}
    encoded = json.dumps(profile).encode()
    record['archive'].update(archive_bytes=archive.stat().st_size,
                             archive_sha256=FIX.sha(archive.read_bytes()),tar_bytes=archive.stat().st_size)
    envelope['profile']={'id':profile['id'],'sha256':FIX.sha(encoded),'scope':profile['scope']}
    raw=json.dumps(envelope).encode()
    selected=[{'member':n,'bytes':len(b),'sha256':FIX.sha(b)} for n,b,_ in entries]
    attachment={'protocol':'GNU_REACHED_HOST_BINDING_1',
        'component':{'id':record['id'],'bytes':len(raw),'sha256':FIX.sha(raw),'approval_artifact_sha256':None},
        'image_profile':{'id':profile['id'],'bytes':len(encoded),'sha256':FIX.sha(encoded),'scope':profile['scope']},
        'source':{'commit':record['source']['commit'],'build':record['source']['build'],'source_proof':record['source']['proof']},
        'archive':{'sha256':record['archive']['archive_sha256'],'signature_sha256':record['archive']['signature_sha256'],'primary':'A'*40},
        'installed_files':{role:next(dict(v) for v in selected if v['member']==name)
                           for role,name in (('java','jdk/bin/java'),('javac','jdk/bin/javac'),('modules','jdk/lib/modules'))},
        'runtime_members':selected,'native_load_startup_proof':'UNIT-no-platform-authority'}
    return raw,encoded,archive,signature,attachment


class GnuHostJoinTests(unittest.TestCase):
    @contextmanager
    def external_hash_case(self,mode):
        """Real UNIT image/FDs; simulated SOURCE selection,never a real permit."""
        c=self.component()
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);raw,profile,archive,signature,attachment=fixture(root)
            receipt=c['verify_gnu_unit'](raw,profile,archive,signature,root,FIX.signer)
            borrow=c['_borrow_gnu_host_unit'](receipt,attachment,profile)
            paths=[root/'external-a',root/'external-b']
            paths[0].write_bytes(b'a'*(2*65536+1));paths[1].write_bytes(b'b'*17)
            rows=[{'path':str(p),'bytes':p.stat().st_size,'sha256':FIX.sha(p.read_bytes())} for p in paths]
            review=HOST._Review({'boot_component':attachment,'runtime':{'external_files':rows,'absent_paths':[]}})
            primary=RuntimeError('UNIT original hash failure');secondary=OSError('UNIT secondary close')
            seen={'opens':0,'reads':[],'closes':[],'armed':False}
            original_fdopen=HOST.os.fdopen
            def cancel():
                if not seen['armed']:return
                if mode.startswith('cancel'):raise primary
                if mode=='closed':receipt.close()
                if mode=='external':paths[0].write_bytes(b'UNIT external changed')
            class Stream:
                def __init__(self,stream,index):self.stream=stream;self.index=index
                def fileno(self):return self.stream.fileno()
                def read(self,n):
                    seen['reads'].append((self.index,n))
                    if mode=='read-close':raise primary
                    data=self.stream.read(n)
                    if data:seen['armed']=True
                    return data
                def close(self):
                    seen['closes'].append(self.index);self.stream.close()
                    if mode in ('read-close','cancel-close','close-only'):raise secondary
                def __enter__(self):return self
                def __exit__(self,*unused):self.close()
            def fdopen(fd,*args,**kwargs):
                index=seen['opens'];seen['opens']+=1
                return Stream(original_fdopen(fd,*args,**kwargs),index)
            def run():
                #Patch only authority selection,not borrowed-image/hash bodies.
                #The actual production registries remain empty and unmodified.
                with patch.object(HOST,'_review_gnu',return_value=review), \
                        patch.object(HOST,'_gnu_component',return_value={'borrow_gnu_host':lambda *args:borrow}), \
                        patch.object(HOST,'_protected_ancestors',lambda p:None), \
                        patch.object(HOST.os,'fdopen',fdopen),patch.object(HOST,'_LinuxHost') as backend:
                    try:return HOST.produce_gnu('SIMULATED','/unused','/unused',cancel,boot_receipt=receipt)
                    finally:backend.assert_not_called()
            try:yield receipt,run,seen,primary,secondary
            finally:receipt.close()

    def test_external_multiblock_hash_observes_original_cancel_before_next_read_or_file(self):
        with self.external_hash_case('cancel') as (receipt,run,seen,primary,_):
            with self.assertRaises(BaseException) as raised:run()
            self.assertIs(raised.exception,primary)
            self.assertEqual(seen['reads'],[(0,65536)])
            self.assertEqual(seen['opens'],1);self.assertEqual(seen['closes'],[0])
            self.assertFalse(receipt.closed);self.assertTrue(receipt.image.directory.is_dir())

    def test_external_hash_rechecks_borrowed_image_and_file_across_original_callback(self):
        for mode,reason in (('closed','GNU_COMPONENT_CLOSED'),('external','GNU_EXTERNAL_FILE_DRIFT')):
            with self.subTest(mode=mode),self.external_hash_case(mode) as (receipt,run,seen,_,__):
                with self.assertRaisesRegex(RuntimeError,reason):run()
                self.assertEqual(seen['reads'],[(0,65536)])
                self.assertEqual(seen['opens'],1);self.assertEqual(seen['closes'],[0])
                self.assertEqual(receipt.closed,mode=='closed','Only the original callback closes its image')

    def test_external_hash_preserves_read_or_cancel_primary_through_secondary_close(self):
        for mode in ('read-close','cancel-close'):
            with self.subTest(mode=mode),self.external_hash_case(mode) as (receipt,run,seen,primary,_):
                with self.assertRaises(BaseException) as raised:run()
                self.assertIs(raised.exception,primary)
                self.assertEqual(seen['reads'],[(0,65536)])
                self.assertEqual(seen['opens'],1);self.assertEqual(seen['closes'],[0])
                self.assertFalse(receipt.closed);self.assertTrue(receipt.image.directory.is_dir())

    def test_external_hash_close_only_failure_cannot_publish_borrow_or_start_backend(self):
        with self.external_hash_case('close-only') as (receipt,run,seen,_,secondary):
            with self.assertRaises(BaseException) as raised:run()
            self.assertIs(raised.exception,secondary)
            self.assertEqual(seen['opens'],1);self.assertEqual(seen['closes'],[0])
            self.assertTrue(all(size==65536 for _,size in seen['reads']))
            self.assertFalse(receipt.closed);self.assertTrue(receipt.image.directory.is_dir())

    def component(self):
        self.assertTrue(hasattr(HOST, '_gnu_component'), 'same-namespace GNU HOST join is missing')
        return HOST._gnu_component()

    def test_unit_receipt_keeps_the_exact_envelope_issuance_identity(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);raw,profile,archive,signature,_=fixture(root)
            receipt=FIX.C.verify_gnu_unit(raw,profile,archive,signature,root,FIX.signer)
            try:
                self.assertTrue(hasattr(receipt,'issuance'), 'issued record identity is missing')
                self.assertEqual(receipt.issuance,(len(raw),hashlib.sha256(raw).hexdigest(),None))
            finally:receipt.close()

    def test_no_registered_gnu_tuple_refuses_before_backend_or_target(self):
        self.assertTrue(hasattr(HOST,'produce_gnu'), 'registered GNU HOST route is missing')
        for key in (None,{},'missing'):
            with self.subTest(key=key), self.assertRaisesRegex(HOST.Pending,'INDEPENDENT_BUILD_TUPLE_ABSENT'):
                HOST.produce_gnu(key,'/unused','/unused',boot_receipt={})

    def test_real_unit_members_map_to_the_same_owned_image_without_authority(self):
        c=self.component()
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);raw,profile,archive,signature,attachment=fixture(root)
            with c['verify_gnu_unit'](raw,profile,archive,signature,root,FIX.signer) as receipt:
                borrow=c['_borrow_gnu_host_unit'](receipt,attachment,profile)
                self.assertEqual(borrow.path('java'),receipt.image.directory/'jdk/bin/java')
                self.assertEqual(borrow.path('javac'),receipt.image.directory/'jdk/bin/javac')
                self.assertEqual(borrow.path('modules'),receipt.image.directory/'jdk/lib/modules')
                self.assertFalse(borrow.runtime_qualified())
                self.assertFalse(receipt.closed)
                with self.assertRaisesRegex(c['Pending'],'SOURCE_GNU_HOST_REQUIRED'):
                    c['borrow_gnu_host'](receipt,attachment)

    def test_member_and_envelope_mismatches_do_not_consume_the_borrowed_image(self):
        c=self.component()
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);raw,profile,archive,signature,attachment=fixture(root)
            with c['verify_gnu_unit'](raw,profile,archive,signature,root,FIX.signer) as receipt:
                mutations=(('component','sha256','0'*64),('component','bytes',len(raw)+1),
                           ('source','source_proof','other'),('archive','signature_sha256','0'*64),
                           ('image_profile','scope','MODULES_ONLY'))
                for section,key,value in mutations:
                    wrong=copy.deepcopy(attachment);wrong[section][key]=value
                    with self.subTest(section=section,key=key),self.assertRaises(c['Pending']):
                        c['_borrow_gnu_host_unit'](receipt,wrong,profile)
                    self.assertFalse(receipt.closed)
                foreign=FIX.C.verify_gnu_unit(raw,profile,archive,signature,root,FIX.signer)
                try:
                    with self.assertRaisesRegex(c['Pending'],'GNU_COMPONENT_RECEIPT_REQUIRED'):
                        c['_borrow_gnu_host_unit'](foreign,attachment,profile)
                finally:foreign.close()

    def test_every_selected_member_is_guarded_after_callbacks_and_through_simulated_cleanup(self):
        c=self.component()
        for name in ('jdk/bin/java','jdk/bin/javac','jdk/lib/libjava.so'):
            with self.subTest(member=name),tempfile.TemporaryDirectory() as folder:
                root=Path(folder);raw,profile,archive,signature,attachment=fixture(root)
                with c['verify_gnu_unit'](raw,profile,archive,signature,root,FIX.signer) as receipt:
                    borrow=c['_borrow_gnu_host_unit'](receipt,attachment,profile)
                    path=receipt.image.directory/name
                    path.chmod(0o644);path.write_bytes(b'UNIT changed')
                    with self.assertRaisesRegex(RuntimeError,'IMAGE_MEMBER_DRIFT'):
                        borrow.require_live()
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);raw,profile,archive,signature,attachment=fixture(root)
            receipt=c['verify_gnu_unit'](raw,profile,archive,signature,root,FIX.signer)
            with self.assertRaisesRegex(RuntimeError,'GNU_COMPONENT_CLOSED'):
                c['_borrow_gnu_host_unit'](receipt,attachment,profile,receipt.close)
            self.assertTrue(receipt.closed)

    def test_relative_launch_selection_preserves_the_installed_expected_tuple(self):
        c=self.component()
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);raw,profile,archive,signature,attachment=fixture(root)
            with c['verify_gnu_unit'](raw,profile,archive,signature,root,FIX.signer) as receipt:
                borrow=c['_borrow_gnu_host_unit'](receipt,attachment,profile)
                value={'runtime':{'profile':'UNIT','java_member':'jdk/bin/java','modules_member':'jdk/lib/modules'},
                       'tools':{'java':{'member':'jdk/bin/java','sha256':'a'*64},
                                'javac':{'member':'jdk/bin/javac','sha256':'b'*64},
                                'setpriv':{'path':'/unit-helper','sha256':'c'*64}}}
                original=copy.deepcopy(value)
                selected=HOST._gnu_launch_selection(HOST._Review(value),borrow)
                self.assertEqual(value,original)
                self.assertEqual(selected.data['runtime']['java'],str(borrow.path('java')))
                self.assertNotIn('member',selected.data['tools']['javac'])

    def test_external_helper_and_absence_guards_reject_late_changes(self):
        self.assertTrue(hasattr(HOST,'_GnuExternalBorrow'),'GNU external dependency lifetime guard is missing')
        for mutation in ('file','absent'):
            with self.subTest(mutation=mutation),tempfile.TemporaryDirectory() as folder:
                root=Path(folder);tool=root/'helper';tool.write_bytes(b'UNIT helper not executed')
                missing=root/'no-preload'
                rows=[{'path':str(tool),'bytes':tool.stat().st_size,'sha256':FIX.sha(tool.read_bytes())}]
                with patch.object(HOST,'_protected_ancestors',lambda path:None):
                    guard=HOST._GnuExternalBorrow(rows,[str(missing)],lambda:None) #UNIT-only,no authority
                guard.require_live()
                if mutation=='file':tool.write_bytes(b'UNIT changed helper')
                else:missing.write_bytes(b'UNIT unexpected preload')
                with self.assertRaisesRegex(HOST.Pending,'GNU_EXTERNAL_(FILE|ABSENCE)_DRIFT'):
                    guard.require_live()

    def test_expected_tuple_binds_the_whole_startup_attachment_and_refuses_unit_launch(self):
        # Synthetic installed-record mechanics only. The protection check is
        # explicitly mocked; no SOURCE record, real backend or HOST is issued.
        c=self.component()
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);raw,profile,archive,signature,attachment=fixture(root)
            attachment['component']['approval_artifact_sha256']='f'*64
            roles=attachment['installed_files']
            helper=root/'setpriv';helper.write_bytes(b'UNIT-helper-not-executed')
            helper_record={'path':str(helper),'bytes':helper.stat().st_size,'sha256':FIX.sha(helper.read_bytes())}
            runtime={'profile':'LINUX_SINGLE_SUN_SYSTEM_DIR_1','java_member':roles['java']['member'],
                     'modules_member':roles['modules']['member'],'external_files':[helper_record],
                     'absent_paths':[str(root/'no-preload')]}
            tools={r:{'member':roles[r]['member'],'sha256':roles[r]['sha256']} for r in ('java','javac')}
            tools['setpriv']={k:helper_record[k] for k in ('path','sha256')}
            value={'schema':'B_SYSTEM_CLOSED_DIRECTORY_GNU_1','boot_load_startup':'UNIT-no-platform-authority',
                   'boot_component':attachment,'runtime':runtime,'tools':tools}
            proof={'runtime':copy.deepcopy(runtime),'boot_component':copy.deepcopy(attachment)}
            path=root/'unit-record.json'
            def installed(v):
                path.write_text(json.dumps(v))
                return {'UNIT':(str(path),FIX.sha(path.read_bytes()))}
            with patch.object(HOST,'_protected_ancestors',lambda p:None), \
                    patch.dict(HOST.REGISTERED_TUPLES,installed(value),clear=True), \
                    patch.dict(HOST.REGISTERED_BOOT_LOAD_STARTUP,{'UNIT-no-platform-authority':proof},clear=True):
                self.assertEqual(HOST._review_gnu('UNIT').data['runtime'],runtime)
                with c['verify_gnu_unit'](raw,profile,archive,signature,root,FIX.signer) as receipt, \
                        patch.object(HOST,'_LinuxHost') as backend:
                    with self.assertRaisesRegex(c['Pending'],'SOURCE_GNU_HOST_REQUIRED'):
                        HOST.produce_gnu('UNIT','/unused','/unused',boot_receipt=receipt)
                    backend.assert_not_called()
                for mutation in ('attachment','runtime','external'):
                    wrong=copy.deepcopy(value)
                    if mutation=='attachment':wrong['boot_component']['component']['sha256']='0'*64
                    elif mutation=='runtime':wrong['runtime']['profile']='other-platform'
                    else:wrong['runtime'].pop('external_files')
                    with self.subTest(mutation=mutation),patch.dict(HOST.REGISTERED_TUPLES,installed(wrong),clear=True), \
                            self.assertRaisesRegex(HOST.Pending,'GNU_BOOT_STARTUP_ATTACHMENT_DRIFT'):
                        HOST._review_gnu('UNIT')

    def test_actual_unit_borrow_stays_guarded_until_simulated_host_cleanup(self):
        simulation=load(Path(__file__).with_name('test_b_trusted_host_producer.py'),'gnu_host_simulation')
        c=self.component()
        for phase in ('ready','wait','cleanup'):
            with self.subTest(phase=phase),tempfile.TemporaryDirectory() as folder:
                root=Path(folder);raw,profile,archive,signature,attachment=fixture(root)
                receipt=c['verify_gnu_unit'](raw,profile,archive,signature,root,FIX.signer)
                try:
                    borrow=c['_borrow_gnu_host_unit'](receipt,attachment,profile)
                    class Driver(simulation.FakeOS):
                        def cleanup(self):
                            super().cleanup()
                            if phase=='cleanup':receipt.close()
                    driver=Driver()
                    def cancel():
                        if driver.events and driver.events[-1]==phase:receipt.close()
                    with self.assertRaisesRegex(RuntimeError,'GNU_COMPONENT_CLOSED'):
                        HOST._transaction(driver,{'scope':'SIMULATED'},cancel,borrow.require_live)
                        borrow.require_live()  # same final boundary as produce_gnu
                    self.assertTrue(driver.reaped)
                    self.assertFalse(driver.stage)
                    if phase=='ready':self.assertNotIn('release',driver.events)
                finally:receipt.close()
