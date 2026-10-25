"""Deterministic simulated OS effects; never executes privilege changes."""
from pathlib import Path
import importlib.util
import hashlib
import json
import os
import subprocess
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

W=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('protected_host',W/'scripts/b-trusted-host-producer.py')
P=importlib.util.module_from_spec(spec);spec.loader.exec_module(P)

class FakeOS:
    """Simulated OS observations. No privileged call or real session issuance."""
    timeout=300
    def __init__(self,fail=None):
        self.events=[];self.fail=fail;self.failure=RuntimeError('controlled primary')
        self.cleanup_failure=None;self.time=100.;self.advance=0
        self.profile_facts={'linux':True,'host_euid':0,'boot':True,'startup':True,'helper':True}
        self.id_facts={'uid':65534,'gid':65534,'existing':True,'host_uid':0,'host_gid':0}
        self.paths=[{'uid':0,'mode':0o755,'link':False}]
        self.process_facts={'uids':[65534]*3,'gids':[65534]*3,'groups':[],
                            'no_new_privs':1,'caps':[0]*5}
        self.spawned=False;self.reaped=False;self.stage=False
    def event(self,name):
        self.events.append(name)
        if name==self.fail:raise self.failure
    def profile(self,review):self.event('profile');return self.profile_facts
    def identity(self,review):self.event('identity');return self.id_facts
    def ancestors(self):self.event('ancestors');return self.paths
    def create(self):self.stage=True;self.event('create')
    def sources(self):self.event('sources')
    def compile(self):self.event('compile')
    def closure(self):self.event('closure')
    def freeze(self):self.event('freeze')
    def verify(self):self.event('verify')
    def clock(self):return self.time
    def spawn(self):self.spawned=True;self.event('spawn')
    def ready(self,deadline):self.event('ready')
    def release(self,deadline):self.event('release')
    def process(self):
        self.event('process');self.time+=self.advance;return self.process_facts
    def wait(self,remaining):
        self.event('wait');self.remaining=remaining;self.reaped=True;return {'exit':0}
    def cleanup(self):
        self.events.append('cleanup')
        if self.spawned:self.reaped=True
        self.stage=False
        if self.cleanup_failure is not None:raise self.cleanup_failure

class ProducerTests(unittest.TestCase):
    def test_post_ready_reentrant_borrow_close_prevents_ack_and_publication(self):
        boot_spec=importlib.util.spec_from_file_location('unit_borrow',W/'scripts/b-boot-bundle-verifier.py')
        boot=importlib.util.module_from_spec(boot_spec);boot_spec.loader.exec_module(boot)
        for phase in ('verify','ready','process','wait'):
            with self.subTest(phase=phase),tempfile.TemporaryDirectory() as folder:
                stage=Path(folder)/'unit-image';stage.mkdir();(stage/'image').mkdir()
                receipt=boot._UnitReceipt(stage,'UNIT_NOT_AUTHORITY');self.assertFalse(receipt.qualified)
                driver=FakeOS()
                def cancel():
                    if driver.events and driver.events[-1]==phase:receipt.close()
                def live():boot.require(not receipt.closed,'BOOT_RECEIPT_CLOSED')
                with self.assertRaisesRegex(RuntimeError,'BOOT_RECEIPT_CLOSED'):
                    P._transaction(driver,{'scope':'SIMULATED'},cancel,live)
                if phase!='wait':self.assertNotIn('release',driver.events)
                if phase=='verify':self.assertFalse(driver.spawned)
                else:self.assertTrue(driver.reaped)
                self.assertFalse(driver.stage);self.assertEqual(1,driver.events.count('cleanup'))

    def test_helper_transition_is_not_checked_until_same_child_ready(self):
        class Transition(FakeOS):
            def __init__(self):
                super().__init__();self.post_drop=self.process_facts
                self.process_facts={'uids':[0]*3,'gids':[0]*3,'groups':[0],
                                    'no_new_privs':0,'caps':[1]*5}
            def ready(self,deadline):
                self.event('ready');self.process_facts=self.post_drop
                self.ready_deadline=deadline
            def release(self,deadline):self.event('release')
        d=Transition();result=P.simulate({'scope':'SIMULATED'},d)
        self.assertFalse(result['qualified'])
        self.assertEqual(['spawn','ready','process','release','wait','cleanup'],d.events[-6:])
        self.assertEqual(400,d.ready_deadline)

    def test_transaction_orders_real_operations_before_the_simulated_spawn(self):
        driver=FakeOS();r=P.simulate({'scope':'SIMULATED'},driver)
        self.assertEqual('SIMULATED',r['scope'])
        self.assertFalse(r['qualified'])
        self.assertEqual(['profile','identity','ancestors','create','sources','compile','closure',
                          'freeze','verify','spawn','ready','process','release','wait','cleanup'],driver.events)

    def test_no_registered_tuple_or_simulated_manifest_can_issue_real_session(self):
        for key in (None,{},'fake',{'qualified':True}):
            with self.assertRaisesRegex(P.Pending,'INDEPENDENT_BUILD_TUPLE_ABSENT'):
                P.produce(key,'/unused','/unused')
        for value in ({'scope':'QUALIFIED'},None):
            with self.assertRaisesRegex(P.Pending,'SIMULATION_ONLY'):P.simulate(value,FakeOS())
        result=P.simulate({'scope':'SIMULATED','qualified':True},FakeOS())
        self.assertFalse(result['qualified']);self.assertNotIsInstance(result,P._HostSession)

    def test_profile_and_identity_failures_precede_owned_stage_and_spawn(self):
        for field,value in (('linux',False),('host_euid',123),('boot',False),('startup',False),('helper',False)):
            d=FakeOS();d.profile_facts[field]=value
            with self.subTest(field=field),self.assertRaises(P.Pending):P.simulate({'scope':'SIMULATED'},d)
            self.assertEqual(['profile','cleanup'],d.events)
        for field,value in (('uid',0),('gid',0),('existing',False),('host_uid',65534),('host_gid',65534)):
            d=FakeOS();d.id_facts[field]=value
            with self.subTest(field=field),self.assertRaises(P.Pending):P.simulate({'scope':'SIMULATED'},d)
            self.assertEqual(['profile','identity','cleanup'],d.events)

    def test_every_ancestor_must_be_protected_no_global_repair(self):
        for changed in ({'mode':0o1777},{'mode':0o775},{'uid':1000},{'link':True}):
            d=FakeOS();d.paths.append({**d.paths[0],**changed})
            with self.subTest(changed=changed),self.assertRaisesRegex(P.Pending,'UNPROTECTED_ANCESTOR'):
                P.simulate({'scope':'SIMULATED'},d)
            self.assertNotIn('create',d.events);self.assertNotIn('spawn',d.events)
        with self.assertRaisesRegex(P.Pending,'ANCESTOR_CHAIN_EMPTY'):P.check_ancestor_records([])

    def test_all_failure_prefixes_roll_back_and_preserve_exact_primary(self):
        for point in ('profile','identity','ancestors','create','sources','compile','closure','freeze','verify','spawn','ready','process','release','wait'):
            d=FakeOS(point);d.cleanup_failure=RuntimeError('secondary cleanup')
            with self.subTest(point=point),self.assertRaises(RuntimeError) as caught:
                P.simulate({'scope':'SIMULATED'},d)
            self.assertIs(d.failure,caught.exception);self.assertEqual('cleanup',d.events[-1])
            self.assertFalse(d.stage)
            if d.spawned:self.assertTrue(d.reaped)

    def test_cleanup_failure_alone_prevents_result(self):
        d=FakeOS();d.cleanup_failure=RuntimeError('cleanup only')
        with self.assertRaises(RuntimeError) as caught:P.simulate({'scope':'SIMULATED'},d)
        self.assertIs(d.cleanup_failure,caught.exception);self.assertTrue(d.reaped)

    def test_cancellation_before_spawn_and_after_wait_has_no_result(self):
        for checkpoint in ('before','compiled','verified','ready','process','release','wait'):
            d=FakeOS();primary=RuntimeError('cancel')
            def cancel():
                last=d.events[-1] if d.events else 'before'
                if last=={'compiled':'compile','verified':'verify'}.get(checkpoint,checkpoint):raise primary
            with self.subTest(checkpoint=checkpoint),self.assertRaises(RuntimeError) as caught:
                P.simulate({'scope':'SIMULATED'},d,cancel)
            self.assertIs(primary,caught.exception);self.assertFalse(d.stage)
            if d.spawned:self.assertTrue(d.reaped)

    def test_saved_identity_capabilities_and_nnp_are_all_required(self):
        mutations=[('uids',[65534,65534,0]),('gids',[65534,65534,0]),('groups',[1]),
                   ('no_new_privs',0)]
        mutations += [('caps',[int(i==j) for i in range(5)]) for j in range(5)]
        for field,value in mutations:
            d=FakeOS();d.process_facts[field]=value
            with self.subTest(field=field,value=value),self.assertRaises(P.Pending):P.simulate({'scope':'SIMULATED'},d)
            self.assertTrue(d.reaped);self.assertNotIn('wait',d.events)

    def test_spawn_deadline_is_not_reset_by_process_observation(self):
        d=FakeOS();d.advance=301
        with self.assertRaisesRegex(P.Pending,'ORIGINAL_SPAWN_DEADLINE'):P.simulate({'scope':'SIMULATED'},d)
        self.assertNotIn('wait',d.events);self.assertTrue(d.reaped)
        d=FakeOS();d.advance=17;P.simulate({'scope':'SIMULATED'},d)
        self.assertEqual(283,d.remaining)

    def test_final_callback_cannot_extend_deadline_or_publish(self):
        d=FakeOS()
        def advance():
            if d.events and d.events[-1]=='wait':d.time+=301
        with self.assertRaisesRegex(P.Pending,'ORIGINAL_SPAWN_DEADLINE'):
            P.simulate({'scope':'SIMULATED'},d,advance)
        self.assertTrue(d.reaped)

    def launch_review(self):
        return {'child_uid':65534,'child_gid':65534,
                'tools':{'setpriv':{'path':'/reviewed/setpriv'},'java':{'path':'/reviewed/java'}},
                'launch_template':['setpriv','--reuid=UID','--regid=GID','--clear-groups','--bounding-set=-all',
                                   '--inh-caps=-all','--ambient-caps=-all','--no-new-privs','java',
                                   '-XX:+DisableAttachMechanism','-Xshare:off','-cp','OWNED_CLASSES','ClosedBootstrap','--host-control']}

    def test_real_backend_launch_spec_is_fixed_not_inherited_or_caller_options(self):
        review=self.launch_review()
        with patch.dict(os.environ,{'JAVA_TOOL_OPTIONS':'-javaagent:bad','LD_PRELOAD':'bad','CLASSPATH':'bad'}):
            try:argv,env=P._launch_spec(review,Path('/owned/classes'),'ClosedBootstrap')
            except P.Pending as failure:self.fail('Required explicit non-CDS template refused: '+str(failure))
        self.assertEqual(('/reviewed/setpriv','--reuid=65534','--regid=65534','--clear-groups',
                          '--bounding-set=-all','--inh-caps=-all','--ambient-caps=-all','--no-new-privs',
                          '/reviewed/java','-XX:+DisableAttachMechanism','-Xshare:off','-cp','/owned/classes',
                          'ClosedBootstrap','--host-control'),argv)
        self.assertEqual({'LANG':'C','LC_ALL':'C'},env)
        for changed in (review['launch_template']+['-javaagent:bad'],review['launch_template'][:-1]):
            with self.assertRaisesRegex(P.Pending,'ARGUMENTS_DRIFT'):
                P._launch_spec({**review,'launch_template':changed},'/owned/classes','ClosedBootstrap')

    def test_non_cds_reviewed_template_rejects_missing_alternate_duplicate_and_injected_options(self):
        review=self.launch_review();base=review['launch_template'];position=base.index('-Xshare:off')
        alternatives=[base[:position]+base[position+1:]]
        for flag in ('-Xshare:auto','-Xshare:on'):
            alternatives.append(base[:position]+[flag]+base[position+1:])
        alternatives += [base[:position]+['-Xshare:off']+base[position:],
                         base[:position]+['-XX:SharedArchiveFile=/unreviewed']+base[position:],
                         base+['-Xshare:on']]
        for template in alternatives:
            with self.subTest(template=template),self.assertRaisesRegex(P.Pending,'REVIEWED_LAUNCH_ARGUMENTS_DRIFT'):
                P._launch_spec({**review,'launch_template':template},'/owned/classes','ClosedBootstrap')

    def test_real_spawn_uses_exact_spec_pipes_pid_and_session_without_running_helper(self):
        d=P._LinuxHost.__new__(P._LinuxHost)
        d.command,d.environment=P._launch_spec(self.launch_review(),'/owned/classes','ClosedBootstrap')
        d.stage=Path('/owned');d.child=None
        sentinel=object()
        with patch.object(P.subprocess,'Popen',return_value=sentinel) as spawn:d.spawn()
        self.assertIs(sentinel,d.child)
        self.assertEqual((d.command,),spawn.call_args.args)
        self.assertEqual({'cwd':d.stage,'env':{'LANG':'C','LC_ALL':'C'},'stdin':subprocess.PIPE,
                          'stdout':subprocess.PIPE,'stderr':subprocess.PIPE,'start_new_session':True,
                          'bufsize':0},spawn.call_args.kwargs)

    def readiness_backend(self):
        d=P._LinuxHost.__new__(P._LinuxHost)
        d.child=SimpleNamespace(pid=321,stdout=SimpleNamespace(fileno=lambda:7))
        d.clock=lambda:100
        return d

    def test_bounded_fragmented_same_pid_readiness_is_only_synchronization(self):
        d=self.readiness_backend();line=b'B_HOST_READY_1 321\n'
        with patch.object(P.select,'select',return_value=([7],[],[])) as poll,\
             patch.object(P.os,'read',side_effect=[bytes([v]) for v in line]) as read:
            d.ready(400)
        self.assertEqual(len(line),read.call_count);self.assertEqual(len(line),poll.call_count)
        self.assertEqual(([7],[],[],300),poll.call_args.args)
        self.assertTrue(all(c.args[0]==7 and 0<c.args[1]<=36 for c in read.call_args_list))

    def test_readiness_missing_wrong_child_oversize_and_protocol_drift_refuse(self):
        for value,reason in ((b'','READINESS_EOF'),(b'B_HOST_READY_1 322\n','WRONG_CHILD_OR_PROTOCOL'),
                             (b'EARLY_HELPER_MESSAGE\n','WRONG_CHILD_OR_PROTOCOL'),
                             (b'B_HOST_READY_1 321\nEXTRA','WRONG_CHILD_OR_PROTOCOL'),
                             (b'x'*36,'READINESS_OVERSIZE')):
            with self.subTest(value=value),patch.object(P.select,'select',return_value=([7],[],[])),\
                 patch.object(P.os,'read',return_value=value),self.assertRaisesRegex(P.Pending,reason):
                self.readiness_backend().ready(400)
        with patch.object(P.select,'select',return_value=([],[],[])),\
             patch.object(P.os,'read',side_effect=AssertionError('no read after timeout')),\
             self.assertRaisesRegex(P.Pending,'READINESS_TIMEOUT'):self.readiness_backend().ready(400)

    def test_readiness_cannot_reset_deadline_or_grant_root_state_release(self):
        d=FakeOS();old=d.ready
        def late(deadline):old(deadline);d.time=401
        d.ready=late
        with self.assertRaisesRegex(P.Pending,'ORIGINAL_SPAWN_DEADLINE'):P.simulate({'scope':'SIMULATED'},d)
        self.assertNotIn('process',d.events);self.assertNotIn('release',d.events);self.assertTrue(d.reaped)
        d=FakeOS();d.process_facts={**d.process_facts,'uids':[0]*3}
        with self.assertRaisesRegex(P.Pending,'IDENTITY_DRIFT'):P.simulate({'scope':'SIMULATED'},d)
        self.assertIn('ready',d.events);self.assertNotIn('release',d.events);self.assertTrue(d.reaped)
        real=self.readiness_backend();ticks=iter([100,401]);real.clock=lambda:next(ticks)
        with patch.object(P.select,'select',return_value=([7],[],[])),\
             patch.object(P.os,'read',return_value=b'B_HOST_READY_1 321\n'),\
             self.assertRaisesRegex(P.Pending,'ORIGINAL_SPAWN_DEADLINE'):real.ready(400)

    def test_release_is_one_byte_and_closed_before_final_wait(self):
        class Pipe:
            def __init__(self):self.data=[];self.closed=False
            def write(self,data):self.data.append(data);return len(data)
            def close(self):self.closed=True
        d=self.readiness_backend();pipe=Pipe();d.child.stdin=pipe
        d.release(400)
        self.assertEqual([b'\x01'],pipe.data);self.assertTrue(pipe.closed);self.assertIsNone(d.child.stdin)
        d.child.stdin=SimpleNamespace(write=lambda data:0)
        with self.assertRaisesRegex(P.Pending,'CONTROL_RELEASE_WRITE'):d.release(400)

    def test_real_unprivileged_java_ready_waits_for_host_release_without_qualifying(self):
        # Actual small JVM mechanics only. No setpriv/profile/provenance claim;
        # this direct UNIT control does not call produce() or issue a session.
        jdk=Path('/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk');h=P._dependency()
        with tempfile.TemporaryDirectory(dir='/data/data/com.termux/files/usr/tmp') as temporary:
            root=Path(temporary);h['unit_handoff'](W,root,jdk)
            d=P._LinuxHost.__new__(P._LinuxHost);d.stage=None
            d.child=subprocess.Popen([jdk/'bin/java','-cp',root/'classes',h['BOOTSTRAP'],'--host-control'],
                                     stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,
                                     start_new_session=True,bufsize=0)
            deadline=P.monotonic()+15
            try:
                d.ready(deadline);self.assertIsNone(d.child.poll())
                d.release(deadline);result=d.wait(deadline-P.monotonic())
                self.assertEqual(d.child.pid,result['pid']);self.assertEqual(0,result['exit'])
            finally:d.cleanup()

    def test_real_proc_parser_does_not_discard_saved_ids_or_any_capability(self):
        d=P._LinuxHost.__new__(P._LinuxHost);d.child=type('Child',(),{'pid':123})()
        text='Uid:\t1 2 3 4\nGid:\t5 6 7 8\nGroups:\t9 10\nCapInh:\t1\nCapPrm:\t2\nCapEff:\t4\nCapBnd:\t8\nCapAmb:\t10\nNoNewPrivs:\t1\n'
        with patch.object(Path,'read_text',return_value=text):actual=d.process()
        self.assertEqual([1,2,3],actual['uids']);self.assertEqual([5,6,7],actual['gids'])
        self.assertEqual([1,2,4,8,16],actual['caps']);self.assertEqual([9,10],actual['groups'])

    def test_real_stage_freeze_and_cleanup_only_touch_owned_tree(self):
        with tempfile.TemporaryDirectory(dir='/data/data/com.termux/files/usr/tmp') as temporary:
            d=P._LinuxHost.__new__(P._LinuxHost);d.parent=Path(temporary);d.stage=None;d.child=None
            mode=d.parent.stat().st_mode;d.create();stage=d.stage
            (d.classes/'Entry.class').write_bytes(b'unit');d.freeze()
            self.assertEqual(0o555,stage.stat().st_mode&0o777)
            self.assertEqual(0o444,(d.classes/'Entry.class').stat().st_mode&0o777)
            self.assertEqual(mode,d.parent.stat().st_mode)
            d.cleanup();self.assertFalse(stage.exists());self.assertEqual(mode,d.parent.stat().st_mode)

    def test_exited_child_still_drains_and_closes_owned_pipes_on_cleanup(self):
        class Child:
            def __init__(self):self.communications=0
            def poll(self):return 0
            def communicate(self,**kwargs):self.communications+=1;return b'',b''
        d=P._LinuxHost.__new__(P._LinuxHost);d.child=Child();d.stage=None
        d.cleanup();self.assertEqual(1,d.child.communications)

    def test_child_gid_must_exist_independently_of_passwd_primary_gid(self):
        d=P._LinuxHost.__new__(P._LinuxHost);d.review={'child_uid':65534,'child_gid':65534}
        with patch.object(P.pwd,'getpwuid',return_value=SimpleNamespace(pw_gid=65534)),\
             patch('grp.getgrgid',side_effect=KeyError()):
            self.assertFalse(d.identity(None)['existing'])

    def test_reviewed_dependency_and_opened_size_are_bound_before_execution(self):
        namespace=P._dependency();self.assertIn('unit_handoff',namespace)
        with patch.object(Path,'read_bytes',return_value=b'raise AssertionError("must not execute")'):
            with self.assertRaisesRegex(P.Pending,'MECHANICS_SOURCE_DRIFT'):P._dependency()
        with tempfile.TemporaryDirectory(dir='/data/data/com.termux/files/usr/tmp') as temporary:
            path=Path(temporary)/'record';path.write_bytes(b'x'*65537)
            with self.assertRaisesRegex(P.Pending,'OPENED_FILE_PROFILE'):P._regular_bytes(path)
            path.unlink();path.symlink_to('/missing')
            with self.assertRaises(OSError):P._regular_bytes(path)

    def test_independent_record_drift_schema_and_unregistered_boot_stop_before_backend(self):
        # Fault-injected file/registry reads test refusals only. No synthetic
        # record reaches produce(), _HostSession, Java or a runtime permit.
        with tempfile.TemporaryDirectory(dir='/data/data/com.termux/files/usr/tmp') as temporary:
            path=Path(temporary)/'review.json'
            for value,proofs,reason in (
                    ({'schema':'wrong'}, {},'TUPLE_SCHEMA'),
                    ({'schema':'B_SYSTEM_CLOSED_DIRECTORY_1','boot_load_startup':'missing'}, {},'BOOT_LOAD_STARTUP_UNREGISTERED'),
                    ({'schema':'B_SYSTEM_CLOSED_DIRECTORY_1','boot_load_startup':'unit','runtime':{'java':'a'}},
                     {'unit':{'java':'b'}},'BOOT_LOAD_STARTUP_DRIFT')):
                data=json.dumps(value).encode();path.write_bytes(data)
                with patch.dict(P.REGISTERED_TUPLES,{'unit':(path,hashlib.sha256(data).hexdigest())},clear=True),\
                     patch.dict(P.REGISTERED_BOOT_LOAD_STARTUP,proofs,clear=True),\
                     patch.object(P,'_protected_ancestors'),self.assertRaisesRegex(P.Pending,reason):
                    P._review('unit')
            with patch.dict(P.REGISTERED_TUPLES,{'unit':(path,'0'*64)},clear=True),\
                 patch.object(P,'_protected_ancestors'),\
                 self.assertRaisesRegex(P.Pending,'INDEPENDENT_BUILD_TUPLE_DRIFT'):P._review('unit')

if __name__=='__main__':unittest.main()
