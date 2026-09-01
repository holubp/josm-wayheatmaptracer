package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.SelectedSourceProbeResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileCachePolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchStatus;

class HeatmapLayerSettingsActionTest {
    @Test
    void createsDeterministicCenterAndInsetCornerStencil() {
        assertEquals(List.of(
            new Point(500, 400),
            new Point(250, 200),
            new Point(749, 200),
            new Point(250, 599),
            new Point(749, 599)
        ), HeatmapLayerSettingsAction.probeStencilPixels(1000, 800));
        assertEquals(5, HeatmapLayerSettingsAction.probeStencilPixels(1000, 800).size());
        assertEquals(TileCachePolicy.BYPASS_READ_ALLOW_WRITE, HeatmapLayerSettingsAction.settingsProbePolicy());
    }

    @Test
    void dispatchesProbePresentationOnSwingEventDispatchThread() throws Exception {
        CountDownLatch presented = new CountDownLatch(1);
        AtomicBoolean onEdt = new AtomicBoolean();
        SelectedSourceProbeResult result = new SelectedSourceProbeResult(
            TileFetchStatus.NO_TILE, false, "spatial result", null);

        HeatmapLayerSettingsAction.dispatchProbeResult(result, null, (value, error) -> {
            onEdt.set(SwingUtilities.isEventDispatchThread());
            presented.countDown();
        });

        assertTrue(presented.await(2, TimeUnit.SECONDS));
        assertTrue(onEdt.get());
    }

    @Test
    void presentsSuccessfulProbeAsNonModalAndFailuresAsModal() {
        SelectedSourceProbeResult success = new SelectedSourceProbeResult(
            TileFetchStatus.SUCCESS_NETWORK, true, "fresh network success", null);
        SelectedSourceProbeResult failure = new SelectedSourceProbeResult(
            TileFetchStatus.AUTH_FAILURE, false, "authentication failed", null);

        HeatmapLayerSettingsAction.ProbePresentation successPresentation =
            HeatmapLayerSettingsAction.probePresentation(success, null);
        HeatmapLayerSettingsAction.ProbePresentation failurePresentation =
            HeatmapLayerSettingsAction.probePresentation(failure, null);
        HeatmapLayerSettingsAction.ProbePresentation exceptionPresentation =
            HeatmapLayerSettingsAction.probePresentation(null, new IllegalStateException("failed"));

        assertFalse(successPresentation.modal());
        assertEquals("fresh network success", successPresentation.message());
        assertTrue(failurePresentation.modal());
        assertEquals("authentication failed", failurePresentation.message());
        assertTrue(exceptionPresentation.modal());
    }
}
