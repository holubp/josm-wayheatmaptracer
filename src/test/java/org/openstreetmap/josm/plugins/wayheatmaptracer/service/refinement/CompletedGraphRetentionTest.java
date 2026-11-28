package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class CompletedGraphRetentionTest {
    @Test
    void roundedGraphCapacityCannotSpendPastTheMetadataCeilingDuringExchange() {
        long maximumMetadata = 64L * 1024 * 1024;
        long maximumSlots = (maximumMetadata - 4096L) / 512L;
        var ledger = AttemptMemoryLedger.production();
        try (var operation = ledger.rootOwner();
                var graph = operation.child("graph-inventory");
                var working = operation.child("working");
                var destination = operation.child("destination")) {
            var capacity = new CompletedGraphRetention.InventoryCapacity(graph);
            var traversal = new RefinementRootAuthority.Inventory.Traversal(
                    CancellationProbe.NONE, (int) maximumSlots, true, capacity::admit);
            traversal.allocate(maximumSlots - 15);
            assertEquals(maximumMetadata, ledger.currentBytes(),
                    "Rounded capacity has already reserved the entire graph metadata limit");
            assertThrows(RefinementSession.Refused.class, () -> capacity.prepareExchange(traversal, working, destination));
            assertEquals(maximumMetadata, ledger.currentBytes());
            assertFalse(working.isClosed());
            assertFalse(destination.isClosed());
        }
        assertEquals(0, ledger.currentBytes());
    }
}
