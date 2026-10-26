"""GNU deadline mechanics only: no platform permit or privileged child."""
from pathlib import Path
import copy
import hashlib
import importlib.util
import io
import json
import struct
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('gnu_deadline_host', ROOT/'scripts/b-trusted-host-producer.py')
HOST = importlib.util.module_from_spec(spec)
spec.loader.exec_module(HOST)

def load_fixture(name):
    fixture_spec = importlib.util.spec_from_file_location('deadline_'+name, Path(__file__).with_name(name+'.py'))
    module = importlib.util.module_from_spec(fixture_spec)
    fixture_spec.loader.exec_module(module)
    return module

FAKE = load_fixture('test_b_trusted_host_producer')


class GnuDeadlineTests(unittest.TestCase):
    def test_actual_gnu_wait_accepts_the_java_unit_pending_literal(self):
        #Literal is independently asserted by the actual Java GNU branch test.
        #Fake owned-child transport exercises the real typed GNU2 consumer only.
        calls = []
        class Child:
            pid = 123
            returncode = 0
            def communicate(self, timeout):
                calls.append(timeout)
                return b'PENDING_GNU_CLOCK_SOURCE_RUNTIME\n', b''
        driver = object.__new__(HOST._GnuDeadlineLinuxHost)
        driver.child = Child()
        self.assertEqual(driver.wait(17.25), {'pid': 123, 'exit': 0})
        self.assertEqual(calls, [17.25])

    def test_gnu_wait_rejects_wrong_exit_protocol_and_output_extents(self):
        literal = b'PENDING_GNU_CLOCK_SOURCE_RUNTIME\n'
        cases = ((literal, b'', 7, 'CHILD_EXIT_FAILURE'),
                 (b'PENDING_TRUSTED_HOST_STARTUP_BOOT_LAUNCH\n', b'', 0, 'PRODUCTION_PROTOCOL_NOT_IMPLEMENTED'),
                 (b'', b'', 0, 'PRODUCTION_PROTOCOL_NOT_IMPLEMENTED'),
                 (literal+b'x', b'', 0, 'PRODUCTION_PROTOCOL_NOT_IMPLEMENTED'),
                 (b'x'*4097, b'', 0, 'CHILD_OUTPUT_PROFILE'),
                 (literal, b'x'*16385, 0, 'CHILD_OUTPUT_PROFILE'))
        for out, err, code, cause in cases:
            with self.subTest(cause=cause, output=len(out), error=len(err)):
                class Child:
                    pid = 123
                    returncode = code
                    def communicate(self, timeout):
                        self.timeout = timeout
                        return out, err
                driver = object.__new__(HOST._GnuDeadlineLinuxHost)
                driver.child = Child()
                with self.assertRaisesRegex(HOST.Pending, '^'+cause+'$'):
                    driver.wait(3.5)
                self.assertEqual(driver.child.timeout, 3.5)
        driver.child = None
        with self.assertRaisesRegex(HOST.Pending, '^NO_OWNED_PID$'):
            driver.wait(3.5)

    def test_actual_gnu_wait_failure_and_timeout_preserve_transaction_cleanup(self):
        for timeout in (False, True):
            with self.subTest(timeout=timeout):
                primary = HOST.subprocess.TimeoutExpired(('UNIT-owned-child',), 300)
                class Child:
                    pid = 123
                    returncode = 0
                    def communicate(self, timeout):
                        self.timeout = timeout
                        if fail_timeout:
                            raise primary
                        return b'wrong protocol\n', b''
                fail_timeout = timeout
                class Driver(FAKE.FakeOS):
                    def __init__(self):
                        super().__init__()
                        self.child = Child()
                    def wait(self, remaining):
                        self.event('wait')
                        return HOST._GnuDeadlineLinuxHost.wait(self, remaining)
                driver = Driver()
                with self.assertRaises(HOST.subprocess.TimeoutExpired if timeout else HOST.Pending) as caught:
                    HOST.simulate({'scope': 'SIMULATED'}, driver)
                if timeout:
                    self.assertIs(caught.exception, primary)
                else:
                    self.assertEqual(str(caught.exception), 'PRODUCTION_PROTOCOL_NOT_IMPLEMENTED')
                self.assertEqual(driver.child.timeout, 300)
                self.assertTrue(driver.reaped)
                self.assertFalse(driver.stage)
                self.assertEqual(driver.events[-2:], ['wait', 'cleanup'])
                self.assertEqual(driver.events.count('cleanup'), 1)

    def test_frame_keeps_original_start_after_readiness_delay(self):
        with patch.object(HOST, 'monotonic_ns', side_effect=[1_000_000_000, 21_000_000_000], create=True):
            clock = HOST._GnuDeadlineClock()
            started = clock.clock()
            frame = clock.frame(123, started+300)
        self.assertEqual(struct.unpack('>8siiqqq', frame),
                         (b'BGNUD001', 1, 40, 123, 1_000_000_000, 301_000_000_000))
        self.assertEqual(len(frame), 40)
        self.assertEqual(frame, bytes.fromhex((Path(__file__).with_name('fixtures')/'gnu-deadline-unit-40.hex').read_text().strip()))

    def test_frame_cannot_start_or_restart_clock(self):
        clock = HOST._GnuDeadlineClock()
        with self.assertRaisesRegex(HOST.Pending, 'GNU_ORIGINAL_CLOCK_ABSENT'):
            clock.frame(123, 300.0)
        with self.assertRaisesRegex(HOST.Pending, 'GNU_CONTROL_ALREADY_ATTEMPTED'):
            clock.frame(123, 300.0)

    def test_one_frame_attempt_only_including_failed_attempt(self):
        for pid in (123, 0):
            with self.subTest(pid=pid), patch.object(HOST, 'monotonic_ns', return_value=1_000_000_000, create=True):
                clock = HOST._GnuDeadlineClock()
                start = clock.clock()
                if pid:
                    clock.frame(pid, start+300)
                else:
                    with self.assertRaisesRegex(HOST.Pending, 'GNU_CONTROL_PID_DOMAIN'):
                        clock.frame(pid, start+300)
                with self.assertRaisesRegex(HOST.Pending, 'GNU_CONTROL_ALREADY_ATTEMPTED'):
                    clock.frame(123, start+300)

    def test_integer_deadline_never_exceeds_exact_float_transaction_deadline(self):
        outer = 1000000000.0000001
        numerator, denominator = outer.as_integer_ratio()
        exact_floor = numerator*1_000_000_000//denominator
        initial = exact_floor-300_000_000_000
        with patch.object(HOST, 'monotonic_ns', side_effect=[initial, initial+1], create=True):
            clock = HOST._GnuDeadlineClock()
            clock.clock()
            frame = clock.frame(123, outer)
        _, _, _, _, start, deadline = struct.unpack('>8siiqqq', frame)
        self.assertEqual(start, initial)
        self.assertLessEqual(deadline, exact_floor)
        self.assertLessEqual(deadline-start, 300_000_000_000)

    def test_delay_expiry_and_clock_regression_refuse(self):
        for now, cause in ((301_000_000_000, 'ORIGINAL_SPAWN_DEADLINE'),
                           (999_999_999, 'GNU_CLOCK_DOMAIN')):
            with self.subTest(now=now), patch.object(HOST, 'monotonic_ns', side_effect=[1_000_000_000, now], create=True):
                clock = HOST._GnuDeadlineClock()
                start = clock.clock()
                with self.assertRaisesRegex(HOST.Pending, cause):
                    clock.frame(123, start+300)

    def test_clock_domain_and_overflow_refuse_before_frame(self):
        for value in (-1, True, (1 << 63)-1, 1 << 63):
            with self.subTest(value=value), patch.object(HOST, 'monotonic_ns', return_value=value, create=True):
                clock = HOST._GnuDeadlineClock()
                with self.assertRaisesRegex(HOST.Pending, 'GNU_CLOCK_DOMAIN'):
                    clock.clock()

    def test_wrong_outer_deadline_refuses(self):
        for outer in (float('nan'), float('inf'), True, 1.0, 302.0):
            with self.subTest(outer=outer), patch.object(HOST, 'monotonic_ns', return_value=1_000_000_000, create=True):
                clock = HOST._GnuDeadlineClock()
                clock.clock()
                with self.assertRaisesRegex(HOST.Pending, 'GNU_OUTER_DEADLINE_DOMAIN'):
                    clock.frame(123, outer)

    def test_deadline_driver_sends_only_fixed_frame_and_closes_pipe(self):
        class Pipe(io.BytesIO):
            def close(self):
                self.sent = self.getvalue()
                super().close()
        class Child:
            pid = 123
            stdin = Pipe()
        driver = object.__new__(HOST._GnuDeadlineLinuxHost)
        driver.child = Child()
        driver.deadline_clock = HOST._GnuDeadlineClock()
        with patch.object(HOST, 'monotonic_ns', return_value=1_000_000_000, create=True):
            start = driver.clock()
            pipe = driver.child.stdin
            driver.release(start+300)
        self.assertEqual(len(pipe.sent), 40)
        self.assertTrue(pipe.closed)
        self.assertIsNone(driver.child.stdin)
        self.assertEqual(struct.unpack('>8siiqqq', pipe.sent)[3:],
                         (123, 1_000_000_000, 301_000_000_000))

    def test_missing_or_wrong_registered_clock_contract_cannot_select_driver(self):
        for runtime in ({}, {'host_control': 'BGNUD001_ABSOLUTE_MONOTONIC_NS_1'},
                        {'host_control': 'wrong', 'clock_profile': 'LINUX_CPYTHON_JDK_CLOCK_MONOTONIC_NS_1'}):
            with self.subTest(runtime=runtime), self.assertRaisesRegex(HOST.Pending, 'GNU_CLOCK_PROFILE_REQUIRED'):
                HOST._require_gnu_deadline_profile(runtime)

    def test_actual_transaction_uses_one_pre_spawn_clock_and_reaps_on_expiry(self):
        for expired in (False, True):
            with self.subTest(expired=expired):
                now = [1_000_000_000]
                class Driver(FAKE.FakeOS):
                    def __init__(self):
                        super().__init__()
                        self.deadline_clock = HOST._GnuDeadlineClock()
                        self.child = SimpleNamespace(pid=123, stdin=io.BytesIO())
                    def clock(self):
                        return self.deadline_clock.clock()
                    def spawn(self):
                        self.original_start = self.deadline_clock.start_ns
                        super().spawn()
                    def ready(self, deadline):
                        super().ready(deadline)
                        now[0] = 301_000_000_000 if expired else 21_000_000_000
                    def release(self, deadline):
                        self.event('release')
                        # Real frame producer, no fake frame or privileged OS.
                        self.frame = self.deadline_clock.frame(self.child.pid, deadline)
                    def cleanup(self):
                        self.child.stdin.close()
                        super().cleanup()
                driver = Driver()
                with patch.object(HOST, 'monotonic_ns', side_effect=lambda: now[0]):
                    if expired:
                        with self.assertRaisesRegex(HOST.Pending, 'ORIGINAL_SPAWN_DEADLINE'):
                            HOST.simulate({'scope': 'SIMULATED'}, driver)
                        self.assertNotIn('release', driver.events)
                    else:
                        result = HOST.simulate({'scope': 'SIMULATED'}, driver)
                        self.assertFalse(result['qualified'])
                        self.assertEqual(struct.unpack('>8siiqqq', driver.frame)[4:],
                                         (1_000_000_000, 301_000_000_000))
                self.assertEqual(driver.original_start, 1_000_000_000)
                self.assertTrue(driver.reaped)
                self.assertFalse(driver.stage)
                self.assertEqual(driver.events.count('cleanup'), 1)

    def test_short_control_write_is_terminal_and_not_retried(self):
        class ShortPipe(io.BytesIO):
            def write(self, raw):
                super().write(raw[:20])
                return 20
        driver = object.__new__(HOST._GnuDeadlineLinuxHost)
        pipe = ShortPipe()
        driver.child = SimpleNamespace(pid=123, stdin=pipe)
        driver.deadline_clock = HOST._GnuDeadlineClock()
        try:
            with patch.object(HOST, 'monotonic_ns', return_value=1_000_000_000):
                start = driver.clock()
                with self.assertRaisesRegex(HOST.Pending, 'CONTROL_RELEASE_WRITE'):
                    driver.release(start+300)
                with self.assertRaisesRegex(HOST.Pending, 'GNU_CONTROL_ALREADY_ATTEMPTED'):
                    driver.release(start+300)
            self.assertEqual(len(pipe.getvalue()), 20)
        finally:
            pipe.close()

    def test_literal_deadline_launch_cannot_add_options_or_use_old_protocol(self):
        runtime = {'host_control': HOST.GNU_DEADLINE_PROTOCOL, 'clock_profile': HOST.GNU_CLOCK_PROFILE}
        bootstrap = 'unit.Bootstrap'
        template = ['setpriv', '--reuid=CHILD_UID', '--regid=CHILD_GID', '--clear-groups', '--bounding-set=-all',
                    '--inh-caps=-all', '--ambient-caps=-all', '--no-new-privs', 'java',
                    '-XX:+DisableAttachMechanism', '-Xshare:off', '-cp', 'OWNED_CLASSES', bootstrap, '--host-control-gnu-v1']
        value = {'runtime': runtime, 'launch_template': template, 'child_uid': 65534, 'child_gid': 65534,
                 'tools': {'setpriv': {'path': '/unit/setpriv'}, 'java': {'path': '/unit/java'}}}
        command, environment = HOST._gnu_deadline_launch_spec(value, Path('/unit/classes'), bootstrap)
        self.assertEqual(command[-1], '--host-control-gnu-v1')
        self.assertEqual(environment, {'LANG': 'C', 'LC_ALL': 'C'})
        for tail in ('--host-control', '-javaagent:unit'):
            bad = copy.deepcopy(value)
            bad['launch_template'][-1] = tail
            with self.assertRaisesRegex(HOST.Pending, 'REVIEWED_LAUNCH_ARGUMENTS_DRIFT'):
                HOST._gnu_deadline_launch_spec(bad, Path('/unit/classes'), bootstrap)

    def test_registered_gnu2_requires_whole_clock_runtime_proof_and_unit_cannot_launch(self):
        # SIMULATED protected installation only. No record is actually installed,
        # no SOURCE receipt is minted, and no privileged backend is constructed.
        fixture = load_fixture('test_b_gnu_host_join')
        component = HOST._gnu_component()
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            raw, profile, archive, signature, attachment = fixture.fixture(root)
            attachment['component']['approval_artifact_sha256'] = 'f'*64
            roles = attachment['installed_files']
            helper = root/'setpriv'
            helper.write_bytes(b'UNIT helper never executed')
            helper_record = {'path': str(helper), 'bytes': helper.stat().st_size,
                             'sha256': hashlib.sha256(helper.read_bytes()).hexdigest()}
            runtime = {'profile': 'LINUX_SINGLE_SUN_SYSTEM_DIR_1', 'java_member': roles['java']['member'],
                       'modules_member': roles['modules']['member'], 'external_files': [helper_record],
                       'absent_paths': [], 'host_control': HOST.GNU_DEADLINE_PROTOCOL, 'clock_profile': HOST.GNU_CLOCK_PROFILE}
            tools = {role: {key: roles[role][key] for key in ('member', 'sha256')} for role in ('java', 'javac')}
            tools['setpriv'] = {key: helper_record[key] for key in ('path', 'sha256')}
            value = {'schema': 'B_SYSTEM_CLOSED_DIRECTORY_GNU_2', 'boot_load_startup': 'UNIT-no-platform-authority',
                     'boot_component': attachment, 'runtime': runtime, 'tools': tools}
            proof = {'runtime': copy.deepcopy(runtime), 'boot_component': copy.deepcopy(attachment)}
            path = root/'record.json'
            path.write_text(json.dumps(value))
            with patch.object(HOST, '_protected_ancestors', lambda unused: None), \
                 patch.dict(HOST.REGISTERED_TUPLES, {'UNIT': (str(path), hashlib.sha256(path.read_bytes()).hexdigest())}, clear=True), \
                 patch.dict(HOST.REGISTERED_BOOT_LOAD_STARTUP, {'UNIT-no-platform-authority': proof}, clear=True):
                self.assertEqual(HOST._review_gnu('UNIT').data['runtime'], runtime)
                with component['verify_gnu_unit'](raw, profile, archive, signature, root, fixture.FIX.signer) as receipt, \
                     patch.object(HOST, '_GnuDeadlineLinuxHost') as backend:
                    with self.assertRaisesRegex(component['Pending'], 'SOURCE_GNU_HOST_REQUIRED'):
                        HOST.produce_gnu('UNIT', '/unused', '/unused', boot_receipt=receipt)
                    backend.assert_not_called()
                proof['runtime']['clock_profile'] = 'different-clock'
                with self.assertRaisesRegex(HOST.Pending, 'GNU_BOOT_STARTUP_ATTACHMENT_DRIFT'):
                    HOST._review_gnu('UNIT')
