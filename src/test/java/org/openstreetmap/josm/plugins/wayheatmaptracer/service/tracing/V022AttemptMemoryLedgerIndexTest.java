package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

/** Identity-index implementation contract plus the unmodified pre-P3 semantic oracle. */
class V022AttemptMemoryLedgerIndexTest {
    @Test
    void closingCanonicalLeasesNeedsNoGlobalGetAndRemovesExactlyOnFinalRelease() throws Exception {
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(7);
        var root = ledger.rootOwner();
        var child = root.child("co-owner");
        var first = root.reserve(7).adopt(new HostileIdentity());
        var second = first.fork(child);
        Object charge = field(first, "charge");
        GlobalIndex index = new GlobalIndex((Map<?, ?>) field(ledger, "charges"));
        set(ledger, "charges", index);

        first.close();
        assertEquals(7, ledger.currentBytes());
        assertEquals(0, index.removals);
        second.close();
        assertEquals(0, ledger.currentBytes());
        assertEquals(7, ledger.peakBytes());
        assertEquals(1, index.removals);
        assertSame(charge, index.lastRemovedKey);
        assertSame(charge, index.lastRemovedValue);
        assertTrue(index.isEmpty());
        assertEquals(0, index.gets, "canonical published charge certificate replaces repeated global lookup");
        assertThrows(IllegalStateException.class, first::close);
        assertThrows(IllegalStateException.class, second::close);
    }

    @Test
    void finalRemovalValidatesReturnedChargeIdentityBeforeSubtractingBytes() throws Exception {
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(7);
        var root = ledger.rootOwner();
        Object identity = new HostileIdentity();
        var lease = root.reserve(7).adopt(identity);
        Object charge = field(lease, "charge");
        Constructor<?> constructor = charge.getClass().getDeclaredConstructor(Object.class, long.class);
        constructor.setAccessible(true);
        Object equalButDifferent = constructor.newInstance(identity, 7L);
        assertTrue(charge.equals(equalButDifferent));
        assertFalse(charge == equalButDifferent);
        GlobalIndex index = new GlobalIndex((Map<?, ?>) field(ledger, "charges"));
        index.substitutedRemovalValue = equalButDifferent;
        set(ledger, "charges", index);
        // Fault injection isolates the final map-return guard: key equality alone
        // must never certify that the removed value is this canonical charge.
        assertThrows(IllegalStateException.class, lease::close);
        assertEquals(7, ledger.currentBytes(), "invalid global removal must not subtract caller bytes");
    }

    @Test
    @SuppressWarnings("unchecked")
    void adoptionRollbackInvalidatesItsUnpublishedChargeCertificate() throws Exception {
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(7);
        var root = ledger.rootOwner();
        var reservation = root.reserve(7);
        Set<Object> reservations = (Set<Object>) field(root, "reservations");
        assertTrue(reservations.remove(reservation)); // Force the sole rollback after publication.
        GlobalIndex index = new GlobalIndex((Map<?, ?>) field(ledger, "charges"));
        set(ledger, "charges", index);
        assertThrows(IllegalStateException.class, () -> reservation.adopt(new HostileIdentity()));
        Object unpublished = index.lastRemovedValue;
        int references = (Integer) field(unpublished, "references");
        assertTrue(index.isEmpty());
        assertEquals(7, ledger.currentBytes(), "the still-active caller reservation owns its bytes");
        assertTrue(reservations.add(reservation));
        reservation.close();
        assertEquals(0, ledger.currentBytes());
        assertEquals(0, references, "an unpublished rollback charge cannot retain a live registration certificate");
    }

    @Test
    void revivedDiscardedTransferHandleCannotRemoveTheSourcesReplacementLease() throws Exception {
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(4);
        var root = ledger.rootOwner();
        var source = root.child("source");
        var destination = root.child("destination");
        Object identity = new HostileIdentity();
        var discarded = source.reserve(4).adopt(identity);
        destination.retain(identity);
        source.transferTo(destination); // Destination survives; source's handle is discarded.
        var replacement = source.retain(identity);
        set(discarded, "active", true);
        assertThrows(IllegalStateException.class, discarded::close);
        assertSame(replacement, source.retainIfAbsent(identity),
                "failed expected-handle removal must leave the replacement lease registered");
        assertEquals(4, ledger.currentBytes());
        root.close();
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void canonicalChargeIsTheReusedKeyOfBothDeletionIndependentIndexes() throws Exception {
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(7);
        var owner = ledger.rootOwner();
        var destination = owner.child("destination");
        Object identity = new HostileIdentity();
        var lease = owner.reserve(7).adopt(identity);
        Object charge = field(lease, "charge");
        Map<?, ?> charges = assertInstanceOf(HashMap.class, field(ledger, "charges"),
                "the measured global index must no longer shift deleted identity-table clusters");
        Map<?, ?> leases = assertInstanceOf(HashMap.class, field(owner, "leases"));
        assertSame(charge, charges.keySet().iterator().next());
        assertSame(charge, charges.get(charge));
        assertSame(charge, leases.keySet().iterator().next());
        assertSame(lease, leases.get(charge));
        var fork = lease.fork(destination);
        assertSame(charge, ((Map<?, ?>) field(destination, "leases")).keySet().iterator().next());
        lease.close();
        assertSame(charge, charges.keySet().iterator().next());
        assertEquals(7, ledger.currentBytes());
        fork.close();
        assertTrue(charges.isEmpty());
        assertTrue(leases.isEmpty());
        assertEquals(0, ledger.currentBytes());
        assertEquals(7, ledger.peakBytes());
    }

    @Test
    void frozenLedgerDeterministicOwnershipAndFailureTracesMatchExactly() {
        assertEquals(trace(FrozenAttemptMemoryLedgerReference.class), trace(AttemptMemoryLedger.class));
    }

    @Test
    void staleChargeIsRejectedBeforeRemovingReadoptedOwnerLease() throws Exception {
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(4);
        var owner = ledger.rootOwner();
        Object identity = new HostileIdentity();
        var stale = owner.reserve(4).adopt(identity);
        stale.close();
        var current = owner.reserve(4).adopt(identity);
        // Deliberately revive only a stale handle: global identity validation must
        // reject it before touching the owner's new canonical entry.
        set(stale, "active", true);
        assertThrows(IllegalStateException.class, stale::close);
        assertEquals(4, ledger.currentBytes());
        assertSame(current, owner.retainIfAbsent(identity));
        current.close();
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    @SuppressWarnings("unchecked")
    void identityKeysAreSymmetricAndControlledCollisionTreesReleaseEveryLease() throws Exception {
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(1);
        var owner = ledger.rootOwner();
        Map<Object, Object> charges = (Map<Object, Object>) field(ledger, "charges");
        Map<Object, Object> owned = (Map<Object, Object>) field(owner, "leases");
        Constructor<?> constructor = Class.forName(AttemptMemoryLedger.class.getName() + "$IdentityKey")
                .getDeclaredConstructor(Object.class);
        constructor.setAccessible(true);
        List<Object> identities = new ArrayList<>();
        List<Object> keys = new ArrayList<>();
        List<AttemptMemoryLedger.MemoryLease> leases = new ArrayList<>();
        for (int index = 0; index < 128; index++) {
            Object identity = new HostileIdentity();
            var lease = owner.reserve(0).adopt(identity);
            Object key = field(lease, "charge");
            Object probe = constructor.newInstance(identity);
            assertTrue(key.equals(probe));
            assertTrue(probe.equals(key));
            assertEquals(System.identityHashCode(identity), key.hashCode());
            assertEquals(key.hashCode(), probe.hashCode());
            assertSame(key, charges.get(probe));
            assertSame(lease, owned.get(probe));
            assertFalse(key.equals(constructor.newInstance(new EqualIdentity())));
            identities.add(identity);
            keys.add(key);
            leases.add(lease);
        }
        // Test-only injection BEFORE rebuilding the indexes forces one bucket,
        // including its tree form; runtime hash policy remains identityHashCode.
        charges.clear();
        owned.clear();
        for (int index = 0; index < keys.size(); index++) {
            Object key = keys.get(index);
            set(key, "cachedIdentityHash", 7);
            charges.put(key, key);
            owned.put(key, leases.get(index));
        }
        assertTrue(charges.entrySet().stream().anyMatch(entry -> entry.getClass().getSimpleName().equals("TreeNode")));
        for (int index = keys.size() - 1; index >= 0; index--) {
            Object probe = constructor.newInstance(identities.get(index));
            set(probe, "cachedIdentityHash", 7);
            assertTrue(probe.equals(keys.get(index)));
            assertTrue(keys.get(index).equals(probe));
            assertSame(keys.get(index), charges.get(probe));
            assertSame(leases.get(index), owned.get(probe));
            leases.get(index).close();
            assertEquals(index, charges.size());
            assertEquals(index, owned.size());
        }
        assertEquals(0, ledger.currentBytes());
        assertEquals(0, ledger.peakBytes());
    }

    @Test
    void zeroByteIdentitiesAndEqualValuesRemainDistinctWithoutNewCountCap() {
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(1);
        var owner = ledger.rootOwner();
        var other = owner.child("other");
        List<AttemptMemoryLedger.MemoryLease> leases = new ArrayList<>();
        for (int index = 0; index < 4096; index++) {
            Object identity = new EqualIdentity();
            var lease = owner.reserve(0).adopt(identity);
            assertSame(lease, owner.retainIfAbsent(identity));
            leases.add(lease);
            other.retain(identity);
        }
        assertEquals(0, ledger.currentBytes());
        owner.reserve(1).adopt(new HostileIdentity());
        assertEquals(1, ledger.peakBytes());
        for (var lease : leases) lease.close();
        owner.close();
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void cancellationSerializesWithRetainAndInvalidatesBothOutstandingHandles() throws Exception {
        assertEquals(cancelTrace(FrozenAttemptMemoryLedgerReference.class), cancelTrace(AttemptMemoryLedger.class));
    }

    private static List<String> cancelTrace(Class<?> type) throws Exception {
        Object ledger = type.getConstructor(long.class).newInstance(9L);
        Object root = invoke(ledger, "rootOwner");
        Object child = invoke(root, "child", "worker");
        Object identity = new HostileIdentity();
        Object lease = invoke(invoke(root, "reserve", 9L), "adopt", identity);
        CountDownLatch retained = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> {
                Object fork = invoke(child, "retain", identity);
                retained.countDown();
                assertTrue(cancelled.await(10, TimeUnit.SECONDS));
                return outcome(() -> invoke(fork, "close"));
            });
            assertTrue(retained.await(10, TimeUnit.SECONDS));
            invoke(root, "close");
            cancelled.countDown();
            return List.of(result.get(10, TimeUnit.SECONDS), outcome(() -> invoke(lease, "close")),
                    invoke(ledger, "currentBytes").toString(), invoke(ledger, "peakBytes").toString());
        } finally {
            cancelled.countDown();
            executor.shutdownNow();
        }
    }

    private static List<String> trace(Class<?> type) {
        try {
            Object ledger = type.getConstructor(long.class).newInstance(10L);
            Object root = invoke(ledger, "rootOwner");
            Object source = invoke(root, "child", "source");
            Object nested = invoke(source, "child", "nested");
            Object destination = invoke(root, "child", "destination");
            Object identity = new HostileIdentity();
            List<String> log = new ArrayList<>();
            Object rootLease = invoke(invoke(root, "reserve", 7L), "adopt", identity);
            Object sourceLease = invoke(source, "retain", identity);
            Object nestedLease = invoke(nested, "retain", identity);
            Object destinationLease = invoke(destination, "retain", identity);
            record(log, ledger, "repeat", () -> invoke(destination, "retainIfAbsent", identity) == destinationLease);
            record(log, ledger, "duplicate-retain", () -> invoke(destination, "retain", identity));
            record(log, ledger, "foreign-identity", () -> invoke(destination, "retain", new HostileIdentity()));
            Object duplicate = invoke(root, "reserve", 2L);
            record(log, ledger, "duplicate-adopt", () -> invoke(duplicate, "adopt", identity));
            record(log, ledger, "rollback", () -> invoke(duplicate, "close"));
            record(log, ledger, "over-cap", () -> invoke(root, "reserve", 4L));
            record(log, ledger, "descendant-transfer", () -> invoke(source, "transferTo", nested));
            record(log, ledger, "merge", () -> invoke(source, "transferTo", destination));
            record(log, ledger, "discarded-source", () -> invoke(sourceLease, "close"));
            record(log, ledger, "discarded-nested", () -> invoke(nestedLease, "close"));
            record(log, ledger, "close-source", () -> invoke(source, "close"));
            record(log, ledger, "root-release", () -> invoke(rootLease, "close"));
            record(log, ledger, "last-release", () -> invoke(destinationLease, "close"));
            record(log, ledger, "double-release", () -> invoke(destinationLease, "close"));
            Object replacement = invoke(invoke(destination, "reserve", 0L), "adopt", identity);
            Object movable = invoke(root, "child", "movable");
            Object target = invoke(root, "child", "target");
            Object surviving = invoke(invoke(movable, "reserve", 3L), "adopt", new EqualIdentity());
            record(log, ledger, "transfer-survivor", () -> invoke(movable, "transferTo", target));
            record(log, ledger, "close-empty-source", () -> invoke(movable, "close"));
            record(log, ledger, "surviving-handle", () -> invoke(surviving, "close"));
            Object foreignLedger = type.getConstructor(long.class).newInstance(10L);
            Object foreignRoot = invoke(foreignLedger, "rootOwner");
            record(log, ledger, "cross-ledger-fork", () -> invoke(replacement, "fork", foreignRoot));
            invoke(invoke(foreignRoot, "reserve", 1L), "adopt", identity);
            record(log, ledger, "cross-ledger-transfer", () -> invoke(target, "transferTo", foreignRoot));
            invoke(foreignRoot, "close");
            Object pending = invoke(target, "reserve", 5L);
            record(log, ledger, "cancel", () -> invoke(root, "close"));
            record(log, ledger, "late-reservation", () -> invoke(pending, "close"));
            record(log, ledger, "late-zero-lease", () -> invoke(replacement, "close"));
            record(log, ledger, "closed-owner", () -> invoke(target, "reserve", 0L));
            record(log, ledger, "idempotent-root-close", () -> invoke(root, "close"));
            return log;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void record(List<String> log, Object ledger, String label, Supplier<Object> action) {
        log.add(label + ":" + outcome(action) + ":" + invoke(ledger, "currentBytes") + ":" + invoke(ledger, "peakBytes"));
    }

    private static String outcome(Supplier<Object> action) {
        try {
            Object value = action.get();
            return value == null ? "void" : value instanceof Boolean ? value.toString() : value.getClass().getSimpleName();
        } catch (RuntimeException exception) {
            return exception.getClass().getSimpleName() + ":" + exception.getMessage();
        }
    }

    private static Object invoke(Object target, String name, Object... args) {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException exception) {
                if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
                if (exception.getCause() instanceof Error error) throw error;
                throw new AssertionError(exception.getCause());
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
        }
        throw new AssertionError("Missing public method " + name);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException missing) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static final class HostileIdentity {
        @Override public boolean equals(Object other) { throw new AssertionError("User equals called"); }
        @Override public int hashCode() { throw new AssertionError("User hashCode called"); }
        @Override public String toString() { throw new AssertionError("User toString called"); }
    }

    private static final class GlobalIndex extends HashMap<Object, Object> {
        private int gets;
        private int removals;
        private Object lastRemovedKey;
        private Object lastRemovedValue;
        private Object substitutedRemovalValue;

        GlobalIndex(Map<?, ?> source) {
            source.forEach(super::put);
        }

        @Override public Object get(Object key) {
            gets++;
            return super.get(key);
        }

        @Override public Object remove(Object key) {
            removals++;
            lastRemovedKey = key;
            lastRemovedValue = super.remove(key);
            return substitutedRemovalValue == null ? lastRemovedValue : substitutedRemovalValue;
        }

        @Override public boolean remove(Object key, Object value) {
            throw new AssertionError("Global removal must validate returned Charge with ==, not referent-only equals");
        }
    }

    private static final class EqualIdentity {
        @Override public boolean equals(Object other) { return other instanceof EqualIdentity; }
        @Override public int hashCode() { return 1; }
    }
}
