package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;

/** GAP9 pure counts: no real scene, raster acquisition or inference. */
class CorridorGapBudgetTest {
    @Test void separateR4PairAndTripleUnitsAdmitEarlyWhileR3StillRefuses() throws Exception {
        long[][] inventories = {{3, 298, 392931, 8225091}, {7, 843, 2599254, 54522594}, {8, 119, 416304, 8671824}};
        for (long[] item : inventories) {
            int tracks = (int)item[0], rows = (int)item[1];
            var budgets = rows == 298 ? new TraceBudgets(96, 7917158, 81200343, 32, 8) : TraceBudgets.defaults();
            assertThrows(CorridorResourceLimitException.class, () -> invoke("enforceSolverBudgets", tracks, rows, budgets));
            assertDoesNotThrow(() -> invokeR4(tracks, rows, budgets), "R4 compares adjacent pairs to pair cap and triples to transition cap");
            var work = CorridorAwareTracker.gapSolverWork(tracks, rows);
            assertEquals(item[2], work.pairs()); assertEquals(item[3], work.transitions());
            assertDoesNotThrow(() -> invokeR4(tracks, rows, new TraceBudgets(96, item[2], item[3], 32, 8)));
            assertThrows(CorridorResourceLimitException.class, () -> invokeR4(tracks, rows, new TraceBudgets(96, item[2] - 1, item[3], 32, 8)));
            assertThrows(CorridorResourceLimitException.class, () -> invokeR4(tracks, rows, new TraceBudgets(96, item[2], item[3] - 1, 32, 8)));
        }
    }
    private static void invokeR4(int tracks, int rows, TraceBudgets budgets) throws Exception {
        CorridorAwareTracker.enforceGapSolverBudgets(tracks, rows, budgets);
    }
    @Test void tinyCountsStateRawLimitsAndCheckedOverflowRemainExplicit() {
        for (int n : new int[] {0, 1}) {
            assertEquals(21, CorridorAwareTracker.gapSolverWork(1, n).pairs());
            assertEquals(21, CorridorAwareTracker.gapSolverWork(1, n).transitions());
        }
        assertEquals(441, CorridorAwareTracker.gapSolverWork(1, 2).pairs());
        assertEquals(441, CorridorAwareTracker.gapSolverWork(1, 2).transitions());
        assertEquals(0, CorridorAwareTracker.gapSolverWork(0, 100).pairs());
        assertThrows(CorridorResourceLimitException.class, () -> CorridorAwareTracker.enforceGapSolverBudgets(1, 2, new TraceBudgets(20, 1000, 1000, 32, 8)));
        assertThrows(CorridorResourceLimitException.class, () -> CorridorAwareTracker.enforceGapSolverBudgets(3, 2, new TraceBudgets(96, 10000, 10000, 2, 2)));
        var overflow = assertThrows(CorridorResourceLimitException.class, () -> CorridorAwareTracker.enforceGapSolverBudgets(Integer.MAX_VALUE, Integer.MAX_VALUE, TraceBudgets.defaults()));
        assertEquals(CorridorResourceLimitException.Reason.ARITHMETIC_OVERFLOW, overflow.reason());
    }
    private static void invoke(String name, int tracks, int rows, TraceBudgets budgets) throws Exception {
        var method = CorridorAwareTracker.class.getDeclaredMethod(name, int.class, int.class, TraceBudgets.class);
        method.setAccessible(true);
        try { method.invoke(null, tracks, rows, budgets); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw failure;
        }
    }
}
