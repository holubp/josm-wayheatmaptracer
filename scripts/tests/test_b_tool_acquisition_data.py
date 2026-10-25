"""Controlled files/metadata text and actual tiny owned readers, no host discovery."""
from pathlib import Path
import importlib.util
import json
import sys
import tempfile
import unittest
from unittest.mock import patch

W=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('tool_data',W/'scripts/b-tool-acquisition-data.py')
M=importlib.util.module_from_spec(spec);spec.loader.exec_module(M)

def fixture(root):
    tools=root/'tools';tools.mkdir();jdk=root/'jdk';(jdk/'lib').mkdir(parents=True);(jdk/'bin').mkdir()
    libs=root/'libs';libs.mkdir();(libs/'libfixture.so').write_bytes(b'fixture library DATA')
    gpgv=tools/'gpgv';gpgv.write_bytes(b'candidate never execute');jimage=jdk/'bin/jimage';jimage.write_bytes(b'candidate never execute')
    (jdk/'lib/modules').write_bytes(b'own projector runtime, not target input');(jdk/'release').write_text('JAVA_VERSION="UNIT"\nIMPLEMENTOR="UNIT"\n')
    os_release=root/'os-release';os_release.write_text('ID=ubuntu\nVERSION_ID="24.04"\nSECRET="never export"\n')
    reader=tools/'readelf';reader.write_text('#!'+sys.executable+'\nimport sys\nfrom pathlib import Path\n'
        "p=Path(sys.argv[-1]);print('Class: ELF64');print('Data: little endian');print('Machine: Advanced Micro Devices X86-64')\n"
        "if p.name in ('gpgv','jimage'):print('0x1 (NEEDED) Shared library: [libfixture.so]')\n")
    package=tools/'dpkg-query';package.write_text('#!'+sys.executable+'\nimport sys\n'
        "if sys.argv[1]=='-S':print('unit-package: '+sys.argv[2])\n"
        "elif sys.argv[1]=='-W':print('unit-package\\t1.0\\tamd64')\n"
        "elif sys.argv[1]=='-L':print('/usr/bin/unit-public-file')\n")
    reader.chmod(0o700);package.chmod(0o700)
    return {'roots':(root,), 'search':(libs,), 'jdk':jdk,'tools':{'signer':gpgv,'projector':jimage},
            'readers':{'elf':reader,'package':package},'os_release':os_release,'runner':{},'scope':'CONTROLLED_UNIT_DATA'}

class ToolDataTests(unittest.TestCase):
    def test_review_stable_spellings_share_hash_but_each_original_alias_remains_live(self):
        for mutate in range(3):
            with self.subTest(mutate=mutate),tempfile.TemporaryDirectory() as folder:
                root=Path(folder);policy=fixture(root);path=root/'canonical';path.write_bytes(b'sixsix')
                paths=[root/'alias-one',root/'alias-two',path]
                for alias in paths[:2]:alias.symlink_to(path.name)
                session=M._Session(root/'out',policy,lambda:None,M.monotonic)
                rows=[session.file(p,'UNIT-role-%d'%i) for i,p in enumerate(paths)]
                self.assertTrue(all(r is rows[0] for r in rows));self.assertEqual(1,len(session.files));self.assertEqual(6,session.hash_bytes)
                self.assertEqual([(str(p),'UNIT-role-%d'%i) for i,p in enumerate(paths)],
                                 [(o['requested'],o['role']) for o in rows[0]['observations']])
                session.check();replacement=root/'replacement';replacement.write_bytes(b'sixsix')
                if mutate<2:
                    # Keep the former inode allocated: changed link ownership
                    # must refuse even with the same target/bytes/canonical path.
                    paths[mutate].rename(root/'moved-original-alias');paths[mutate].symlink_to(path.name)
                else:replacement.replace(path)
                with self.assertRaisesRegex(M.Failure,'ALIAS_DRIFT' if mutate<2 else 'FILE_DRIFT'):session.check()
                self.assertEqual(1,len(session.files));self.assertEqual(6,session.hash_bytes)

    def test_review_deadline_during_last_report_cleanup_inventory_or_terminal_is_retained(self):
        for phase in ('last-report','cleanup','inventory','terminal'):
            with self.subTest(phase=phase),tempfile.TemporaryDirectory() as folder:
                root=Path(folder);policy=fixture(root);clock=[0.];writer,base=M._dependency()
                original=writer['Evidence'].bytes;remove=base['_remove'];triggered=[]
                def write(self,name,data,terminal=False):
                    result=original(self,name,data,terminal)
                    if ((phase=='last-report' and name.startswith('reports/runner.json.part')) or
                        (phase=='inventory' and name.startswith('inventory/files.json.part')) or
                        (phase=='terminal' and name=='reports/status.json')):
                        clock[0]=601.;triggered.append(name)
                    return result
                def cleanup(path):
                    result=remove(path)
                    if phase=='cleanup' and Path(path).name.startswith('tool-metadata-'):
                        clock[0]=601.;triggered.append('cleanup')
                    return result
                with patch.object(M,'_dependency',return_value=(writer,base)),patch.object(writer['Evidence'],'bytes',write),patch.dict(base,{'_remove':cleanup}):
                    status=M._acquire(root/'out',policy,clock=lambda:clock[0])
                self.assertTrue(triggered);terminal=json.loads((root/'out/reports/status.json').read_bytes())
                self.assertEqual('COLLECTOR_DEADLINE',status['cause']);self.assertEqual(status,terminal)
                self.assertEqual('PARTIAL_FAILURE',status['outcome']);self.assertEqual('COMPLETE',status['cleanup'])
                self.assertGreater(status['files'],0);self.assertFalse(list(root.glob('tool-metadata-*')))

    def test_review_package_directory_markers_are_metadata_and_unsupported_rows_do_not_abort_roles(self):
        for unsafe in (False,True):
            with self.subTest(unsafe=unsafe),tempfile.TemporaryDirectory() as folder:
                root=Path(folder);policy=fixture(root);package=policy['readers']['package']
                data=package.read_text();data=data.replace("print('/usr/bin/unit-public-file')",
                    "print('/.\\n/usr\\n/lib\\n/etc\\n/usr/bin\\n/usr/bin/unit-public-file"+('\\n/usr/../private' if unsafe else '')+"')")
                package.write_text(data);status=M._acquire(root/'out',policy)
                self.assertEqual('PARTIAL_UNSEALED',status['outcome']);self.assertIsNone(status['cause'])
                roles=json.loads((root/'out/reports/roles.json.part0000').read_bytes())
                self.assertEqual({'signer','projector','projector-own-modules','projector-release','os-release'},set(roles))
                info=roles['signer']['package']
                if unsafe:
                    self.assertEqual('UNAVAILABLE',info['availability']);self.assertEqual('PACKAGE_MANIFEST_POLICY',info['cause'])
                    self.assertIn('package-provenance',status['missing'])
                else:
                    self.assertEqual(['/','/usr','/lib','/etc','/usr/bin','/usr/bin/unit-public-file'],info['manifest'])
                    self.assertEqual(['/','/usr','/lib','/etc'],info['directory_markers'])
                self.assertFalse(list(root.glob('tool-metadata-*')))

    def test_review_shared_canonical_roles_edges_and_observation_bound_are_independent(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);policy=fixture(root);signer=policy['tools']['signer'];signer.unlink();signer.symlink_to(policy['tools']['projector'])
            status=M._acquire(root/'out',policy);self.assertEqual('PARTIAL_UNSEALED',status['outcome'])
            roles=json.loads((root/'out/reports/roles.json.part0000').read_bytes())
            observed=roles['projector']['observations']
            self.assertEqual({(str(signer),'signer'),(str(policy['tools']['projector']),'projector')},
                             {(o['requested'],o['role']) for o in observed})
            graph=json.loads((root/'out/reports/candidate-graph.json.part0000').read_bytes())
            self.assertEqual([(str(root/'libs/libfixture.so'),'candidate-elf-needed')],[(e['requested'],e['role']) for e in graph])
            expected={policy['tools']['projector'],policy['readers']['elf'],policy['readers']['package'],
                      policy['jdk']/'lib/modules',policy['jdk']/'release',policy['os_release'],root/'libs/libfixture.so'}
            self.assertEqual(len(expected),status['files']);self.assertEqual(sum(p.stat().st_size for p in expected),status['hashed_bytes'])
            self.assertEqual(5+2+256,M.LIMITS['observations'])
            session=M._Session(root/'bound',policy,lambda:None,M.monotonic)
            # Reduced UNIT-only limit exercises actual admission without 263
            # redundant file operations. The public fixed policy has no knob.
            with patch.dict(M.LIMITS,{'observations':3}):
                for i in range(3):session.file(signer,'UNIT-%d'%i)
                with self.assertRaisesRegex(M.Failure,'OBSERVATION_LIMIT'):session.file(signer,'UNIT-fourth')
            self.assertEqual(3,len(session.observations));self.assertEqual(1,len(session.files))
            self.assertEqual(policy['tools']['projector'].stat().st_size,session.hash_bytes)

    def test_review_manifest_policy_keeps_directory_metadata_separate_and_rejects_traversal(self):
        paths,directories=M._manifest(b'/.\n/usr\n/lib\n/lib64\n/bin\n/sbin\n/etc\n/usr/bin\n/usr/bin/public-file\n')
        self.assertEqual(['/','/usr','/lib','/lib64','/bin','/sbin','/etc'],directories)
        self.assertEqual('/usr/bin/public-file',paths[-1])
        for raw in (b'/usr/../private\n',b'/usr//bin\n',b'/usr/./bin\n',b'//usr\n',b'/private\n',b'relative\n',b'/usr/secret\tfield\n'):
            with self.subTest(raw=raw),self.assertRaisesRegex(M.Failure,'PACKAGE_MANIFEST_POLICY'):M._manifest(raw)
        with self.assertRaisesRegex(M.Failure,'METADATA_LIMIT'):M._manifest(b'/usr\n'*4097)
        with self.assertRaisesRegex(M.Failure,'PACKAGE_MANIFEST_POLICY'):M._manifest(b'/usr/'+b'x'*4096+b'\n')

    def test_review_expired_inventory_output_owner_loss_keeps_foreign_output_unwritten(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);policy=fixture(root);out=root/'out';moved=root/'fixture-moved-output';clock=[0.]
            writer,base=M._dependency();original=writer['Evidence'].bytes;changed=[]
            def write(self,name,data,terminal=False):
                result=original(self,name,data,terminal)
                if name.startswith('inventory/files.json.part') and not changed:
                    changed.append(True);clock[0]=601.;out.rename(moved);out.mkdir();(out/'foreign-sentinel').write_bytes(b'preserve')
                return result
            with patch.object(M,'_dependency',return_value=(writer,base)),patch.object(writer['Evidence'],'bytes',write):
                status=M._acquire(out,policy,clock=lambda:clock[0])
            self.assertEqual([True],changed);self.assertEqual('COLLECTOR_DEADLINE',status['cause'])
            self.assertEqual('PARTIAL_OUTPUT_UNAVAILABLE',status['outcome']);self.assertEqual('UNAVAILABLE',status['evidence'])
            self.assertEqual(b'preserve',(out/'foreign-sentinel').read_bytes());self.assertEqual(['foreign-sentinel'],[p.name for p in out.iterdir()])
            self.assertTrue((moved/'reports/runner.json.part0000').is_file());self.assertFalse(list(root.glob('tool-metadata-*')))

    def test_actual_owned_metadata_readers_collect_roles_without_running_candidates(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);policy=fixture(root);out=root/'output'
            try:status=M._acquire(out,policy)
            except M.Failure as failure:self.fail('Missing actual DATA collector: '+str(failure))
            self.assertFalse(status['qualified']);self.assertEqual(0,status['cases'])
            self.assertEqual(0,status['candidateExecutions']);self.assertEqual(0,status['targetExecutions'])
            self.assertEqual('PARTIAL_UNSEALED',status['outcome']);self.assertGreater(status['metadataExecutions'],0)
            roles=json.loads(b''.join(p.read_bytes() for p in sorted((out/'reports').glob('roles.json.part*'))))
            self.assertEqual({'signer','projector','projector-own-modules','projector-release','os-release'},set(roles))
            self.assertEqual(str(policy['jdk']/'lib/modules'),roles['projector-own-modules']['canonical'])
            self.assertIn('public-key-current',status['missing']);self.assertIn('external-bundle-approval',status['missing'])
            self.assertFalse(list(root.glob('tool-metadata-*')))
            self.assertNotIn(b'never export',b''.join(p.read_bytes() for p in out.rglob('*') if p.is_file()))

    def test_exact_candidate_edges_and_unobserved_roles_are_not_installed_profiles(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);policy=fixture(root);status=M._acquire(root/'output',policy)
            graph=json.loads((root/'output/reports/candidate-graph.json.part0000').read_bytes())
            self.assertEqual([('gpgv','needed','libfixture.so'),('jimage','needed','libfixture.so')],
                             [(Path(e['from']).name,e['kind'],e['name']) for e in graph])
            self.assertEqual({str(root/'libs/libfixture.so')},{e['canonical'] for e in graph})
            self.assertTrue(all(e['scope']=='CANDIDATE_NOT_LOADER_AUTHORITY' for e in graph))
            self.assertTrue({'signer-config-resources','projector-vm-native-resources','owned-home-current',
                             'public-keyring-current','installed-profile-approval'}<=set(status['missing']))
            self.assertFalse(hasattr(M,'verify_source'));self.assertFalse(status['qualified'])

    def test_metadata_parser_malformed_oversize_secret_paths_and_dynamic_search_are_bounded(self):
        good=b'Class: ELF64\nMachine: Advanced Micro Devices X86-64\n'
        for bad in (good+b'0x1 (NEEDED) Shared library: [../private]\n',good+b'0x1 (NEEDED) no bracket\n',
                    good+b'[Requesting program interpreter: relative]\n',b'x'*65537,good+b'\xff',good+b'\0'):
            with self.subTest(bad=bad[:60]),self.assertRaises(M.Failure):M._elf(bad)
        parsed=M._elf(good+b'0x1 (RUNPATH) Library runpath: [SECRET_NEVER_EXPORT]\n')
        self.assertEqual('UNAVAILABLE',parsed['search'][0]['availability']);self.assertIsNone(parsed['search'][0]['paths'])
        self.assertNotIn('SECRET',str(parsed))
        parsed=M._elf(good+b'0x1 (RPATH) Library rpath: [$ORIGIN/../lib:/usr/lib]\n')
        self.assertEqual(['$ORIGIN/../lib','/usr/lib'],parsed['search'][0]['paths'])
        self.assertEqual('REQUIRES_INDEPENDENT_LOADER_SEARCH_POLICY',parsed['search'][0]['scope'])
        with self.assertRaisesRegex(M.Failure,'ELF_NEEDED_FORMAT'):
            M._elf(good+b''.join(('0x1 (NEEDED) Shared library: [lib%d.so]\n'%i).encode() for i in range(33)))

    def test_alias_resolution_cyclic_outside_root_and_alias_chain_drift_refuse(self):
        with tempfile.TemporaryDirectory() as folder,tempfile.TemporaryDirectory() as foreign:
            root=Path(folder);policy=fixture(root);one=root/'one';two=root/'two';one.symlink_to(two);two.symlink_to(one)
            with self.assertRaisesRegex(M.Failure,'ALIAS_LIMIT'):M._resolve(one,policy)
            one.unlink();two.unlink();outside=Path(foreign)/'private';outside.write_bytes(b'NEVER_EXPORT')
            one.symlink_to(outside)
            with self.assertRaisesRegex(M.Failure,'ALIAS_PATH_POLICY'):M._resolve(one,policy)
            one.unlink();target=root/'data';target.write_bytes(b'data');one.symlink_to(target)
            changed=[];session=None
            def change():
                if session is not None and session.hash_bytes and not changed:
                    changed.append(True);one.unlink();one.symlink_to(target)
            session=M._Session(root/'out',policy,change,M.monotonic)
            with self.assertRaisesRegex(M.Failure,'ALIAS_DRIFT'):session.file(one,'UNIT-alias')
            self.assertEqual([True],changed)

    def test_stream_hash_before_after_callback_and_65th_actual_file_refuse(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);policy=fixture(root);path=root/'data';path.write_bytes(b'original');changed=[];session=None
            def change():
                if session is not None and session.hash_bytes and not changed:
                    changed.append(True);path.write_bytes(b'mutated!')
            session=M._Session(root/'out-drift',policy,change,M.monotonic)
            with self.assertRaisesRegex(M.Failure,'FILE_DRIFT'):session.file(path,'UNIT-drift')
            self.assertEqual([True],changed)
            session=M._Session(root/'out-count',policy,lambda:None,M.monotonic)
            for i in range(64):
                p=root/('file-%02d'%i);p.write_bytes(bytes([i]));session.file(p,'UNIT-file')
            p=root/'file-64';p.write_bytes(b'x')
            with self.assertRaisesRegex(M.Failure,'FILE_COUNT_LIMIT'):session.file(p,'UNIT-file')
            self.assertEqual(64,len(session.files));self.assertEqual(64,session.hash_bytes)

    def test_declared_file_and_total_hash_caps_precede_first_read(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);policy=fixture(root);path=root/'sparse'
            with path.open('wb') as f:f.truncate((512<<20)+1)
            session=M._Session(root/'out',policy,lambda:None,M.monotonic)
            with patch.object(M.os,'read',side_effect=AssertionError('READ_BEFORE_SIZE_REFUSAL')):
                with self.assertRaisesRegex(M.Failure,'HASH_BYTES_LIMIT'):session.file(path,'UNIT-large')
            path.write_bytes(b'x');session.hash_bytes=512<<20
            with patch.object(M.os,'read',side_effect=AssertionError('READ_BEFORE_TOTAL_REFUSAL')):
                with self.assertRaisesRegex(M.Failure,'HASH_BYTES_LIMIT'):session.file(path,'UNIT-total')

    def test_missing_and_ambiguous_package_provenance_and_readers_stay_unavailable(self):
        for missing in (False,True):
            with self.subTest(missing=missing),tempfile.TemporaryDirectory() as folder:
                root=Path(folder);policy=fixture(root);package=policy['readers']['package']
                if missing:package.unlink();policy['readers']['elf'].unlink()
                else:package.write_text('#!'+sys.executable+'\nimport sys\n'
                    "print('one: '+sys.argv[-1]);print('two: '+sys.argv[-1])\n")
                status=M._acquire(root/'out',policy);self.assertEqual('PARTIAL_UNSEALED',status['outcome'])
                self.assertIn('package-provenance',status['missing'])
                if missing:self.assertIn('elf-reader',status['missing']);self.assertEqual(0,status['metadataExecutions'])
                roles=json.loads((root/'out/reports/roles.json.part0000').read_bytes())
                self.assertEqual('UNAVAILABLE',roles['signer']['package']['availability'])

    def test_ambiguous_library_and_outside_interpreter_do_not_become_resolved(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);policy=fixture(root);other=root/'other-libs';other.mkdir();(other/'libfixture.so').write_bytes(b'different')
            policy['search']+=(other,);status=M._acquire(root/'out',policy)
            graph=json.loads((root/'out/reports/candidate-graph.json.part0000').read_bytes())
            self.assertEqual({'DEPENDENCY_AMBIGUOUS'},{e['cause'] for e in graph});self.assertIn('candidate-elf-resolution',status['missing'])
            reader=policy['readers']['elf'];reader.write_text('#!'+sys.executable+'\n'
                "print('Class: ELF64');print('Machine: Advanced Micro Devices X86-64');print('[Requesting program interpreter: /UNAPPROVED/SECRET]')\n")
            status=M._acquire(root/'out-interp',policy);self.assertEqual('PATH_POLICY',status['cause'])
            self.assertNotIn(b'UNAPPROVED',b''.join(p.read_bytes() for p in (root/'out-interp').rglob('*') if p.is_file()))

    def test_tool_root_input_is_own_projector_image_and_source_pin_refuses_drift(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);policy=fixture(root);policy['tools']['projector']=root/'target/bin/jimage'
            status=M._acquire(root/'out',policy)
            self.assertEqual('PROJECTOR_TOOL_ROOT',status['cause']);self.assertEqual(0,status['metadataQueries'])
            with patch.object(M,'COLLECTOR_SHA256','0'*64):
                with self.assertRaisesRegex(M.Failure,'COLLECTOR_SOURCE_DRIFT'):M._dependency()

    def test_actual_owned_child_deadline_cancel_and_partial_sanitization(self):
        for deadline in (False,True):
            with self.subTest(deadline=deadline),tempfile.TemporaryDirectory() as folder:
                root=Path(folder);policy=fixture(root);clock=[0.];children=[];b=M._dependency()[1];actual=b['subprocess'].Popen
                def popen(*args,**kwargs):
                    child=actual(*args,**kwargs);children.append(child)
                    if deadline:clock[0]=601.
                    return child
                def cancel():
                    if children and not deadline:raise RuntimeError('SECRET_EXCEPTION_TEXT_NEVER_EXPORT')
                with patch.object(b['subprocess'],'Popen',side_effect=popen):
                    status=M._acquire(root/'out',policy,cancel,lambda:clock[0])
                self.assertEqual('COLLECTOR_DEADLINE' if deadline else 'DATA_CANCELLED_OR_INVALID',status['cause'])
                self.assertEqual(1,len(children));self.assertIsNotNone(children[0].poll())
                self.assertIsNone(status['metadataExecutions']);self.assertGreater(status['files'],0)
                self.assertFalse(list(root.glob('tool-metadata-*')))
                output=b''.join(p.read_bytes() for p in (root/'out').rglob('*') if p.is_file())
                self.assertNotIn(b'SECRET_EXCEPTION',output);self.assertTrue((root/'out/reports/status.json').is_file())

    def test_candidate_file_change_during_metadata_callback_never_publishes_stale_tuple(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);policy=fixture(root);b=M._dependency()[1];actual=b['subprocess'].Popen;children=[]
            def popen(*args,**kwargs):
                child=actual(*args,**kwargs);children.append(child)
                policy['tools']['signer'].write_bytes(b'changed candidate metadata binding');return child
            with patch.object(b['subprocess'],'Popen',side_effect=popen):status=M._acquire(root/'out',policy)
            self.assertEqual('FILE_DRIFT',status['cause']);self.assertFalse(status['qualified'])
            self.assertEqual(1,len(children));self.assertIsNotNone(children[0].poll());self.assertFalse(list(root.glob('tool-metadata-*')))

    def test_query_cap_fresh_output_and_output_leaf_bound_are_enforced(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);policy=fixture(root);session=M._Session(root/'out',policy,lambda:None,M.monotonic)
            session.queries=256
            with self.assertRaisesRegex(M.Failure,'QUERY_LIMIT'):session.query('elf',())
            with self.assertRaisesRegex(RuntimeError,'OUTPUT_LEAF_LIMIT'):session.e.bytes('reports/large',b'x'*65537)
            sentinel=root/'out/sentinel';sentinel.write_bytes(b'preserve')
            with self.assertRaisesRegex(RuntimeError,'OUTPUT_ALREADY_EXISTS'):M._acquire(root/'out',policy)
            self.assertEqual(b'preserve',sentinel.read_bytes())

    def test_owned_staging_or_output_replacement_preserves_foreign_sentinel(self):
        for output in (False,True):
            with self.subTest(output=output),tempfile.TemporaryDirectory() as folder:
                root=Path(folder);policy=fixture(root);out=root/'out';moved=root/'fixture-moved-original';replaced=[]
                def cancel():
                    stages=list(root.glob('tool-metadata-*'))
                    if stages and not replaced:
                        target=out if output else stages[0];target.rename(moved);target.mkdir(mode=0o700)
                        (target/'foreign-sentinel').write_bytes(b'preserve');replaced.append(target)
                status=M._acquire(out,policy,cancel)
                self.assertEqual(1,len(replaced));self.assertEqual(b'preserve',(replaced[0]/'foreign-sentinel').read_bytes())
                self.assertTrue(moved.is_dir());self.assertFalse(status['qualified'])
                self.assertEqual('PARTIAL_OUTPUT_UNAVAILABLE' if output else 'PARTIAL_FAILURE',status['outcome'])
                self.assertEqual('COMPLETE' if output else 'OWNERSHIP_OR_CLEANUP_UNAVAILABLE',status['cleanup'])
                # Only fixture-created moved/replacement paths; never pathname search.
                M._dependency()[1]['_remove'](moved)
                if not output:M._dependency()[1]['_remove'](replaced[0])

    def test_query_directory_alias_after_owned_child_start_refuses_and_reaps(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);policy=fixture(root);outside=root/'outside';outside.mkdir();sentinel=outside/'sentinel';sentinel.write_bytes(b'preserve')
            b=M._dependency()[1];actual=b['subprocess'].Popen;children=[]
            def popen(*args,**kwargs):
                child=actual(*args,**kwargs);children.append(child);query=Path(kwargs['stdout'].name).parent
                query.rename(query.parent/'original-query');query.symlink_to(outside,target_is_directory=True);return child
            with patch.object(b['subprocess'],'Popen',side_effect=popen):status=M._acquire(root/'out',policy)
            self.assertEqual('OUTPUT_OWNER_DRIFT',status['cause']);self.assertEqual('COMPLETE',status['cleanup'])
            self.assertEqual(1,len(children));self.assertIsNotNone(children[0].poll());self.assertEqual(b'preserve',sentinel.read_bytes())
            self.assertFalse(list(root.glob('tool-metadata-*')))

    def test_reader_output_cap_and_original_overall_clock_include_source_setup(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);policy=fixture(root);package=policy['readers']['package']
            package.write_text('#!'+sys.executable+"\nprint('x'*65537)\n")
            status=M._acquire(root/'out',policy);self.assertEqual('PARTIAL_UNSEALED',status['outcome']);self.assertIsNone(status['cause'])
            roles=json.loads((root/'out/reports/roles.json.part0000').read_bytes())
            self.assertEqual({'availability':'UNAVAILABLE','cause':'PACKAGE_METADATA_OUTPUT_LIMIT'},roles['signer']['package'])
            self.assertIn('package-provenance',status['missing'])
            self.assertFalse(list(root.glob('tool-metadata-*')))
            clock=[0.];actual=M._dependency
            def delayed():
                result=actual();clock[0]=601.;return result
            with patch.object(M,'_dependency',side_effect=delayed):status=M._acquire(root/'deadline',policy,clock=lambda:clock[0])
            self.assertEqual('COLLECTOR_DEADLINE',status['cause']);self.assertEqual(0,status['metadataQueries'])

    def test_caller_exception_code_and_missing_dependency_never_escape_as_public_text(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);policy=fixture(root)
            def cancel():raise M.Failure('SECRET_THAT_LOOKS_LIKE_A_CODE')
            status=M._acquire(root/'out',policy,cancel)
            self.assertEqual('DATA_CANCELLED_OR_INVALID',status['cause'])
            self.assertNotIn('SECRET',json.dumps(status));self.assertEqual('COMPLETE',status['cleanup'])
            with patch.object(M,'_dependency',side_effect=FileNotFoundError('PRIVATE_MISSING_PATH')):
                status=M.collect(root/'unavailable')
            self.assertEqual('PARTIAL_DEPENDENCY_UNAVAILABLE',status['outcome']);self.assertFalse(status['qualified'])
            self.assertNotIn('PRIVATE',json.dumps(status));self.assertFalse((root/'unavailable').exists())

if __name__=='__main__':unittest.main()
