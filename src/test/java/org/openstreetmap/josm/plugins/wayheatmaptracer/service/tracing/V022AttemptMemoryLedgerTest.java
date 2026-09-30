package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class V022AttemptMemoryLedgerTest {
    @Test
    void admitsBelowAndExactlyAtLimitAndTracksPeakSeparately() {
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(10);
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();

        AttemptMemoryLedger.Reservation first = owner.reserve(4);
        assertEquals(4, ledger.currentBytes());
        assertEquals(4, ledger.peakBytes());
        AttemptMemoryLedger.Reservation second = owner.reserve(6);
        assertEquals(10, ledger.currentBytes());
        assertEquals(10, ledger.peakBytes());
        first.close();
        assertEquals(6, ledger.currentBytes());
        assertEquals(10, ledger.peakBytes());
        second.close();
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void rejectsOneByteOverAndInvalidLimitsOrEstimates() {
        assertEquals(AttemptMemoryLedger.MAX_BYTES, AttemptMemoryLedger.production().limitBytes());
        assertThrows(IllegalArgumentException.class, () -> new AttemptMemoryLedger(0));
        assertThrows(IllegalArgumentException.class,
                () -> new AttemptMemoryLedger(AttemptMemoryLedger.MAX_BYTES + 1));
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(10);
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();
        owner.reserve(10);
        assertThrows(AttemptMemoryLedger.ResourceLimitException.class, () -> owner.reserve(1));
        assertThrows(IllegalArgumentException.class, () -> owner.reserve(-1));
        assertEquals(10, ledger.currentBytes());
    }

    @Test
    void rejectsLongOverflowRatherThanWrapping() {
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(10);
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();
        owner.reserve(1);
        assertThrows(AttemptMemoryLedger.ResourceLimitException.class,
                () -> owner.reserve(Long.MAX_VALUE));
        assertThrows(ArithmeticException.class,
                () -> AttemptMemoryLedger.arrayBytes(Long.MAX_VALUE, 8));
        assertEquals(1, ledger.currentBytes());
    }

    @Test
    void ownerReportsAttemptCurrentAndPeakAfterReleaseAndRefusal() {
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(10);
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();
        AttemptMemoryLedger.Reservation first = owner.reserve(8);
        assertEquals(8, owner.currentBytes());
        assertEquals(8, owner.peakBytes());
        first.close();

        AttemptMemoryLedger.Reservation second = owner.reserve(5);
        assertEquals(5, owner.currentBytes());
        assertEquals(8, owner.peakBytes());
        AttemptMemoryLedger.ResourceLimitException refusal = assertThrows(
                AttemptMemoryLedger.ResourceLimitException.class, () -> owner.reserve(6));
        assertEquals(5, refusal.currentBytes());
        assertEquals(5, owner.currentBytes());
        assertEquals(8, owner.peakBytes());
        second.close();
    }

    @Test
    void reservationAdoptionKeepsChargeAndIsAtomic() {
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(10);
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();
        Object identity = new Object();
        AttemptMemoryLedger.Reservation reservation = owner.reserve(10);
        AttemptMemoryLedger.MemoryLease lease = reservation.adopt(identity);
        assertEquals(10, ledger.currentBytes());
        assertEquals(10, ledger.peakBytes());
        reservation.close(); // AutoCloseable cleanup is harmless after adoption transferred its charge.
        assertEquals(10, ledger.currentBytes());
        lease.close();
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void identityCanBeSharedAtFullCapacityAndReleasesOnLastOwner() {
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(10);
        AttemptMemoryLedger.Owner root = ledger.rootOwner();
        AttemptMemoryLedger.Owner child = root.child("shared");
        AttemptMemoryLedger.Owner forkOwner = root.child("fork");
        Object identity = new Object();
        AttemptMemoryLedger.MemoryLease rootLease = root.reserve(10).adopt(identity);

        AttemptMemoryLedger.MemoryLease childLease = child.retain(identity);
        AttemptMemoryLedger.MemoryLease fork = rootLease.fork(forkOwner);
        assertEquals(10, ledger.currentBytes());
        assertThrows(IllegalArgumentException.class, () -> child.retain(new Object()));
        rootLease.close();
        assertEquals(10, ledger.currentBytes());
        childLease.close();
        assertEquals(10, ledger.currentBytes());
        fork.close();
        assertEquals(0, ledger.currentBytes());
        assertEquals(10, ledger.peakBytes());
    }

    @Test
    void idempotentRetainUsesTheOwnerLeaseTableAtACompletelyFullCap() {
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(10);
        AttemptMemoryLedger.Owner root = ledger.rootOwner();
        AttemptMemoryLedger.Owner child = root.child("idempotent-shared");
        Object identity = new Object();
        AttemptMemoryLedger.MemoryLease source = root.reserve(10).adopt(identity);

        AttemptMemoryLedger.MemoryLease first = child.retainIfAbsent(identity);
        AttemptMemoryLedger.MemoryLease repeated = child.retainIfAbsent(identity);

        assertSame(first, repeated);
        assertThrows(IllegalStateException.class, () -> child.retain(identity),
                "the original strict retain contract still rejects a duplicate owner lease");
        assertThrows(IllegalArgumentException.class,
                () -> child.retainIfAbsent(new Object()));
        assertEquals(10L, ledger.currentBytes());
        assertEquals(10L, ledger.peakBytes());
        source.close();
        assertEquals(10L, ledger.currentBytes());
        child.close();
        assertEquals(0L, ledger.currentBytes());
    }

    @Test
    void rejectsDuplicateAdoptionWithoutDoubleCharging() {
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(10);
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();
        Object identity = new Object();
        AttemptMemoryLedger.MemoryLease existing = owner.reserve(3).adopt(identity);
        AttemptMemoryLedger.Reservation duplicate = owner.reserve(2);
        assertThrows(IllegalStateException.class, () -> duplicate.adopt(identity));
        duplicate.close();
        assertEquals(3, ledger.currentBytes());
        existing.close();
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void ownershipTransferMovesAllLeasesWithoutChangingCharge() {
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(10);
        AttemptMemoryLedger.Owner root = ledger.rootOwner();
        AttemptMemoryLedger.Owner source = root.child("source");
        AttemptMemoryLedger.Owner nestedSource = source.child("nested");
        AttemptMemoryLedger.Owner destination = root.child("destination");
        Object firstIdentity = new Object();
        Object secondIdentity = new Object();
        AttemptMemoryLedger.MemoryLease first = source.reserve(3).adopt(firstIdentity);
        nestedSource.reserve(4).adopt(secondIdentity);

        source.transferTo(destination);
        assertEquals(7, ledger.currentBytes());
        source.close();
        assertEquals(7, ledger.currentBytes());
        first.close();
        assertEquals(4, ledger.currentBytes());
        destination.close();
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void ownershipTransferRejectsDescendantDestinationBeforeMovingAnyLease() {
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(10);
        AttemptMemoryLedger.Owner root = ledger.rootOwner();
        AttemptMemoryLedger.Owner source = root.child("source");
        AttemptMemoryLedger.Owner descendant = source.child("descendant");
        source.reserve(3).adopt(new Object());

        assertThrows(IllegalArgumentException.class, () -> source.transferTo(descendant));
        assertEquals(3, ledger.currentBytes());
        source.close();
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void ownershipTransferMergesDestinationAndDescendantCoOwnersByIdentity() {
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(10);
        AttemptMemoryLedger.Owner root = ledger.rootOwner();
        AttemptMemoryLedger.Owner source = root.child("source");
        AttemptMemoryLedger.Owner descendant = source.child("descendant");
        AttemptMemoryLedger.Owner destination = root.child("destination");
        Object identity = new Object();
        AttemptMemoryLedger.MemoryLease rootLease = root.reserve(4).adopt(identity);
        destination.retain(identity);
        source.retain(identity);
        descendant.retain(identity);

        source.transferTo(destination);
        assertEquals(4, ledger.currentBytes());
        source.close();
        assertEquals(4, ledger.currentBytes());
        rootLease.close();
        assertEquals(4, ledger.currentBytes());
        destination.close();
        assertEquals(0, ledger.currentBytes());
        assertEquals(4, ledger.peakBytes());
    }

    @Test
    void nestedRootCloseReleasesCoOwnedIdentityAndInvalidatesOutstandingHandles() {
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(10);
        AttemptMemoryLedger.Owner root = ledger.rootOwner();
        AttemptMemoryLedger.Owner left = root.child("left");
        AttemptMemoryLedger.Owner right = root.child("right");
        Object identity = new Object();
        AttemptMemoryLedger.MemoryLease leftLease = left.reserve(5).adopt(identity);
        AttemptMemoryLedger.MemoryLease rightLease = right.retain(identity);
        AttemptMemoryLedger.Reservation outstanding = right.reserve(2);
        assertEquals(7, root.currentBytes());

        root.close();
        assertEquals(0, root.currentBytes());
        assertEquals(7, root.peakBytes());
        assertThrows(IllegalStateException.class, leftLease::close);
        assertThrows(IllegalStateException.class, rightLease::close);
        assertThrows(IllegalStateException.class, outstanding::close);
        root.close();
        assertEquals(0, root.currentBytes());
    }

    @Test
    void separateCopiesAndReplacementReservationsCoexist() {
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(12);
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();
        byte[] oldArray = new byte[4];
        byte[] newArray = new byte[4];
        assertNotSame(oldArray, newArray);
        AttemptMemoryLedger.MemoryLease oldLease = owner.reserve(6).adopt(oldArray);
        AttemptMemoryLedger.Reservation replacement = owner.reserve(6);
        assertEquals(12, ledger.currentBytes());
        AttemptMemoryLedger.MemoryLease newLease = replacement.adopt(newArray);
        assertEquals(12, ledger.currentBytes());
        newLease.close();
        assertEquals(6, ledger.currentBytes());
        oldLease.close();
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void cancellationOwnerCloseReleasesReservationsAndRetainedObjects() {
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(10);
        AttemptMemoryLedger.Owner root = ledger.rootOwner();
        AttemptMemoryLedger.Owner child = root.child("in-flight");
        child.reserve(2);
        child.reserve(3).adopt(new Object());
        root.close();
        assertEquals(0, ledger.currentBytes());
        assertEquals(5, ledger.peakBytes());
        assertThrows(IllegalStateException.class, () -> child.reserve(1));
    }

    @Test
    void reservationAndLeaseDoubleCloseAreDetected() {
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(10);
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();
        AttemptMemoryLedger.Reservation reservation = owner.reserve(1);
        reservation.close();
        assertThrows(IllegalStateException.class, reservation::close);
        AttemptMemoryLedger.MemoryLease lease = owner.reserve(2).adopt(new Object());
        lease.close();
        assertThrows(IllegalStateException.class, lease::close);
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void chargeHelpersUseCheckedConservativeAlignedSizes() {
        assertEquals(24, AttemptMemoryLedger.arrayBytes(1, 1));
        assertEquals(32, AttemptMemoryLedger.arrayBytes(2, 8));
        assertEquals(24, AttemptMemoryLedger.objectBytes(1));
        assertEquals(24, AttemptMemoryLedger.referenceArrayBytes(1));
        assertThrows(IllegalArgumentException.class,
                () -> AttemptMemoryLedger.arrayBytes(1, -1));
    }
}
