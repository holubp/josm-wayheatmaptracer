package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

class V022TransitionAdmissionMemoTest {
    @Test
    void retainsOnlyBoundedFirstUseAdmissionsAndRecomputesEvictedKeys() {
        TransitionAdmissionMemo memo = new TransitionAdmissionMemo(2);
        int[] calls = {0};

        assertTrue(memo.allowed(0, 0, 0, 0, () -> ++calls[0] == 1));
        assertFalse(memo.allowed(0, 0, 0, 1, () -> ++calls[0] == 1));
        assertTrue(memo.allowed(0, 0, 0, 0, () -> { throw new AssertionError("must hit cache"); }));
        assertTrue(memo.allowed(0, 0, 0, 2, () -> ++calls[0] == 3));

        // The least recently used second key was evicted. Re-evaluation retains the exact predicate result.
        assertFalse(memo.allowed(0, 0, 0, 1, () -> { calls[0]++; return false; }));
        assertEquals(4, calls[0]);
        assertEquals(2, memo.size());
    }

    @Test
    void refusesBeforePredicateAndKeepsThePreviouslyCachedEntry() {
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(719);
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();
        TransitionAdmissionMemo memo = new TransitionAdmissionMemo(1, owner);
        int[] calls = {0};

        assertThrows(ProbabilisticInference.MemoryLimit.class,
                () -> memo.allowed(0, 0, 0, 0, () -> { calls[0]++; return true; }));

        assertEquals(0, calls[0], "allocation refusal must precede predicate evaluation");
        assertEquals(0, memo.size());
        assertEquals(128L, ledger.currentBytes(),
                "only the two empty map objects remain after refused table admission");
        owner.close();
        assertEquals(0L, ledger.currentBytes());
    }

    @Test
    void chargedMemoRetainsOldAndNewEntriesUntilSuccessfulEviction() {
        AttemptMemoryLedger ledger = AttemptMemoryLedger.production();
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();
        TransitionAdmissionMemo memo = new TransitionAdmissionMemo(1, owner);

        assertTrue(memo.allowed(0, 0, 0, 0, () -> true));
        long oneEntry = ledger.currentBytes();
        assertEquals(720L, oneEntry,
                "two maps, two tables, one key, two nodes and lease bookkeeping are charged");
        assertFalse(memo.allowed(0, 0, 0, 1, () -> false));

        assertEquals(oneEntry, ledger.currentBytes(),
                "successful eviction leaves one fully charged map entry");
        assertEquals(1_024L, ledger.peakBytes(),
                "old and replacement entries coexist until insertion succeeds");
        owner.close();
        assertEquals(0L, ledger.currentBytes());
    }

    @Test
    void thirteenthEntryReservesBothReplacementTablesBeforeResize() {
        AttemptMemoryLedger ledger = AttemptMemoryLedger.production();
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();
        TransitionAdmissionMemo memo = new TransitionAdmissionMemo(13, owner);
        for (int index = 0; index < 12; index++) {
            int discriminator = index;
            assertTrue(memo.allowed(0, 0, 0, discriminator, () -> true));
        }
        assertEquals(4_064L, ledger.currentBytes());

        assertTrue(memo.allowed(0, 0, 0, 12, () -> true));

        assertEquals(4_624L, ledger.currentBytes());
        assertEquals(4_912L, ledger.peakBytes(),
                "both old 16-slot and new 32-slot tables coexist during insertion 13");
        owner.close();
        assertEquals(0L, ledger.currentBytes());
    }
}
