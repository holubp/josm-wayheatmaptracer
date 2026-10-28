package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.config.PluginPreferences;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.SelectedSourceProbeResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileCachePolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchStatus;

class HeatmapLayerSettingsActionTest {
    @Test
    void successfulSaveRefreshesWithoutInformationalModalWithOrWithoutCredentials() {
        Config.setPreferencesInstance(new MemoryPreferences());
        for (boolean managed : new boolean[] {false, true}) {
            Config.getPref().put("wayheatmaptracer.keyPairId", managed ? "synthetic-key" : "");
            Config.getPref().put("wayheatmaptracer.policy", managed ? "synthetic-policy" : "");
            Config.getPref().put("wayheatmaptracer.signature", managed ? "synthetic-signature" : "");
            Config.getPref().put("wayheatmaptracer.session", managed ? "synthetic-session" : "");
            RecordingSettingsHost host = new RecordingSettingsHost(false);
            HeatmapLayerSettingsAction.runSavedSettings(true, PluginPreferences.load(), host);
            assertEquals(managed ? List.of("runtime", "refresh", "probe") : List.of("runtime", "refresh"),
                    host.calls);
        }
    }

    @Test
    void cancelDoesNothingAndRefreshFailureRemainsVisibleWithoutStartingProbe() {
        Config.setPreferencesInstance(new MemoryPreferences());
        RecordingSettingsHost cancelled = new RecordingSettingsHost(false);
        HeatmapLayerSettingsAction.runSavedSettings(false, PluginPreferences.load(), cancelled);
        assertEquals(List.of(), cancelled.calls);
        RecordingSettingsHost failed = new RecordingSettingsHost(true);
        HeatmapLayerSettingsAction.runSavedSettings(true, PluginPreferences.load(), failed);
        assertEquals(List.of("runtime", "refresh", "failure"), failed.calls);
    }

    private static final class RecordingSettingsHost implements HeatmapLayerSettingsAction.SettingsHost {
        final List<String> calls = new ArrayList<>();
        final boolean fail;
        RecordingSettingsHost(boolean fail) { this.fail = fail; }
        public void updateRuntime(ManagedHeatmapConfig config) { calls.add("runtime"); }
        public void refresh(ManagedHeatmapConfig config) {
            calls.add("refresh");
            if (fail) { throw new IllegalStateException("synthetic-refresh-failure"); }
        }
        public void probe(ManagedHeatmapConfig config) { calls.add("probe"); }
        public void information(String message) { calls.add("information"); }
        public void failure() { calls.add("failure"); }
    }

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
    void suppressesSuccessfulProbeAndKeepsFailuresModal() {
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

        assertEquals(HeatmapLayerSettingsAction.ProbePresentationKind.NONE, successPresentation.kind());
        assertEquals("fresh network success", successPresentation.message());
        assertEquals(HeatmapLayerSettingsAction.ProbePresentationKind.MODAL_WARNING,
            failurePresentation.kind());
        assertEquals("authentication failed", failurePresentation.message());
        assertEquals(HeatmapLayerSettingsAction.ProbePresentationKind.MODAL_WARNING,
            exceptionPresentation.kind());
    }
}
