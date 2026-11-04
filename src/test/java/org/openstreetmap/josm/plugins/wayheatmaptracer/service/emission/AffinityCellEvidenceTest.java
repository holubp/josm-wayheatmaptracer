package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class AffinityCellEvidenceTest {
    @Test void closedSupercoverIncludesBothGridEdgeSidesAndEveryVertexContact() {
        var ledger = AttemptMemoryLedger.production();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var root = ledger.rootOwner()) {
            var horizontal = OrdinaryAffinityClassifier.closedCellSupercover(new RasterPoint(1, 1),
                new RasterPoint(2, 1), budget, root);
            assertEquals(Set.of(cell(0, 0), cell(1, 0), cell(2, 0), cell(0, 1), cell(1, 1), cell(2, 1)), Set.copyOf(horizontal));
            var diagonal = OrdinaryAffinityClassifier.closedCellSupercover(new RasterPoint(.25, .25),
                new RasterPoint(1.75, 1.75), budget, root);
            assertEquals(Set.of(cell(0, 0), cell(1, 0), cell(0, 1), cell(1, 1)), Set.copyOf(diagonal));
            assertFalse(OrdinaryAffinityClassifier.crossesCellInterior(new RasterPoint(0, 0), new RasterPoint(1, 1), cell(1, 1)));
            assertFalse(OrdinaryAffinityClassifier.crossesCellInterior(new RasterPoint(0, 1), new RasterPoint(2, 1), cell(0, 0)));
            assertFalse(OrdinaryAffinityClassifier.crossesCellInterior(new RasterPoint(.5, .5), new RasterPoint(.5, .5), cell(0, 0)));
            assertTrue(OrdinaryAffinityClassifier.crossesCellInterior(new RasterPoint(0, 0), new RasterPoint(1, 1), cell(0, 0)));
        }
        assertEquals(0, ledger.currentBytes());
        assertTrue(budget.snapshot().workUsed() > 0);
    }

    @Test void strictDarkCellProofRejectsEqualityAndMissingNodesAndChargesReads() {
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        var grid = new BilinearSegmentIntegral.ReadOnlyScalarGrid() {
            public int width() { return 2; } public int height() { return 2; }
            public boolean supportsCell(int x, int y) { return true; }
            public double nodeValue(int x, int y) { return .5; }
        };
        assertNull(OrdinaryAffinityClassifier.darkCellEvidence(grid, cell(0, 0), .5, budget));
        assertNotNull(OrdinaryAffinityClassifier.darkCellEvidence(grid, cell(0, 0), Math.nextUp(.5), budget));
        assertNull(OrdinaryAffinityClassifier.darkCellEvidence(grid, cell(-1, 0), .6, budget));
        var missing = new BilinearSegmentIntegral.ReadOnlyScalarGrid() {
            public int width() { return 2; } public int height() { return 2; }
            public boolean supportsCell(int x, int y) { return false; }
            public double nodeValue(int x, int y) { fail("Unavailable cells must not read nodes"); return 0; }
        };
        assertNull(OrdinaryAffinityClassifier.darkCellEvidence(missing, cell(0, 0), .6, budget));
        assertEquals(5, budget.snapshot().operationUnits().get(EmissionWorkBudget.Operation.NODE_READ));
    }
    private static OrdinaryAffinityClassifier.Cell cell(int x, int y) { return new OrdinaryAffinityClassifier.Cell(x, y); }
}
