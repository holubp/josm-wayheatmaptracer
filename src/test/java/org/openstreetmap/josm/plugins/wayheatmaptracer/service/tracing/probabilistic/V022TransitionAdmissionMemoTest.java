package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

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
}
