package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class V022RefinementBudgetProofTest {
    @Test void omiceLowerBoundProvesImpossible() {
        assertEquals(BRefinementBudget.Decision.PROVEN_UNAFFORDABLE,
            BRefinementBudget.assess(new CompletedBPassWork(37818535,18909255,74006408,558166),53993592));
    }
    @Test void drasovRemainsUnknown() {
        assertEquals(BRefinementBudget.Decision.UNKNOWN,
            BRefinementBudget.assess(new CompletedBPassWork(15141344,7570672,30566216,1),97433784));
    }
    @Test void equalLowerBoundDoesNotFallback() {
        assertEquals(BRefinementBudget.Decision.UNKNOWN,
            BRefinementBudget.assess(new CompletedBPassWork(10,5,20,1),15));
    }
    @Test void upperBoundCannotAuthorizeFallback() {
        long unrelatedDenseUpperBound=Long.MAX_VALUE;
        assertTrue(unrelatedDenseUpperBound>100);
        assertEquals(BRefinementBudget.Decision.UNKNOWN,
            BRefinementBudget.assess(new CompletedBPassWork(10,5,20,1),100));
    }
    @Test void invalidOrOverflowedWitnessIsRejected() {
        assertThrows(IllegalArgumentException.class,()->new CompletedBPassWork(-1,0,0,0));
        assertThrows(IllegalArgumentException.class,()->new CompletedBPassWork(1,-1,1,0));
        assertThrows(IllegalArgumentException.class,()->new CompletedBPassWork(1,1,1,0));
        assertThrows(IllegalArgumentException.class,()->new CompletedBPassWork(0,0,0,-1));
        assertThrows(IllegalArgumentException.class,()->new CompletedBPassWork(Long.MAX_VALUE,1,Long.MAX_VALUE,0));
        assertThrows(IllegalArgumentException.class,()->BRefinementBudget.assess(new CompletedBPassWork(0,0,0,0),-1));
    }
}
