package org.openstreetmap.josm.plugins.wayheatmaptracer.imagery;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class VisibleSourceEpochTest {
    @Test
    void aPendingTileLoadInvalidatesTheRenderedSourceReceipt() {
        VisibleSourceEpoch epoch = new VisibleSourceEpoch();
        VisibleSourceEpoch.Receipt before = epoch.captureStable();

        epoch.beginLoad();
        assertThrows(IllegalStateException.class, epoch::captureStable);
        assertThrows(IllegalStateException.class, () -> epoch.requireCurrent(before));
        epoch.finishLoad();
        assertThrows(IllegalStateException.class, () -> epoch.requireCurrent(before));
    }

    @Test
    void displayChangesInvalidateTheRenderedSourceReceipt() {
        VisibleSourceEpoch epoch = new VisibleSourceEpoch();
        VisibleSourceEpoch.Receipt before = epoch.captureStable();

        epoch.sourceChanged();

        assertThrows(IllegalStateException.class, () -> epoch.requireCurrent(before));
    }
}
