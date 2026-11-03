package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class LegacyEmissionFamilyAdmissionTest {
    @Test
    void bothGeneratedStateKindsShareTheNinetySeventhMemberRefusalGate() {
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        LegacyEmissionKernel.requireFamilyCapacity(7, 95, budget);
        var refusal = assertThrows(LegacyEmissionDomain.StateFamilyLimitException.class,
            () -> LegacyEmissionKernel.requireFamilyCapacity(7, 96, budget));
        assertEquals(7, refusal.originalRow());
        assertEquals(97, refusal.familySize());
        assertEquals(96, refusal.limit());
        assertTrue(budget.snapshot().workUsed() > 0);
    }
}
