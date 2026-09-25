package org.openstreetmap.josm.plugins.wayheatmaptracer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.Action;
import javax.swing.JPanel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.wayheatmaptracer.actions.AlignWayAction;
import org.openstreetmap.josm.plugins.wayheatmaptracer.actions.GeometryCleanupSettingsAction;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.PreviewSessionController;
import org.openstreetmap.josm.plugins.wayheatmaptracer.ui.PreviewOverlay;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class WayHeatmapTracerPluginTest {
    @BeforeEach
    void resetPreferences() throws Exception {
        Config.setPreferencesInstance(new MemoryPreferences());
        Field content = MainApplication.class.getDeclaredField("contentPanePrivate");
        content.setAccessible(true);
        content.set(null, new JPanel());
    }

    @Test
    void geometryCleanupSettingsActionIsAlwaysAvailableAndHasNoShortcut() {
        GeometryCleanupSettingsAction action = new GeometryCleanupSettingsAction();

        assertEquals("Geometry Cleanup Settings...", action.getValue(javax.swing.Action.NAME));
        assertTrue(action.isEnabled());
        action.destroy();
    }

    @Test
    void ordinaryActionsAndTemporaryLaunchersShareThePluginAttemptAuthority() throws Exception {
        PreviewSessionController<LiveBPreviewService.Computed> session =
                new PreviewSessionController<>(Runnable::run);
        List<JosmAction> actions = WayHeatmapTracerPlugin.createRegisteredActions(session);
        try {
            Field authority = AlignWayAction.class.getDeclaredField("livePreviewSession");
            authority.setAccessible(true);
            for (int index = 0; index < 9; index++) {
                assertTrue(actions.get(index) instanceof AlignWayAction);
                assertSame(session, authority.get(actions.get(index)));
            }
            assertEquals(List.of(
                    "Engine A Visible Alignment",
                    "Engine B Visible Alignment",
                    "Engine Hybrid A+B Visible Alignment",
                    "Engine Image Visible Alignment",
                    "Engine A Managed Alignment",
                    "Engine B Managed Alignment"),
                    actions.subList(3, 9).stream()
                            .map(action -> action.getValue(Action.NAME)).toList());
        } finally {
            actions.forEach(JosmAction::destroy);
            session.close();
        }
    }

    @Test
    void staleActionDestroyPreservesCurrentOverlayUntilPluginGlobalTeardown() throws Exception {
        PreviewSessionController<LiveBPreviewService.Computed> session =
                new PreviewSessionController<>(Runnable::run);
        List<JosmAction> actions = WayHeatmapTracerPlugin.createRegisteredActions(session);
        AlignWayAction first = (AlignWayAction) actions.get(0);
        AlignWayAction second = (AlignWayAction) actions.get(1);
        PreviewOverlay overlay = PreviewOverlay.getInstance();
        Field chosenCandidate = PreviewOverlay.class.getDeclaredField("chosenCandidate");
        chosenCandidate.setAccessible(true);
        Field activeOwner = AlignWayAction.class.getDeclaredField("activeLivePreviewOwner");
        activeOwner.setAccessible(true);

        PreviewSessionController.Owner firstOwner = session.open(() -> { });
        activeOwner.set(first, firstOwner);
        PreviewSessionController.Owner secondOwner = session.open(() -> { });
        activeOwner.set(second, secondOwner);
        chosenCandidate.set(overlay, new CenterlineCandidate("active-preview", 0.0,
                List.of(), List.of()));

        first.destroy();

        assertSame(secondOwner, activeOwner.get(second));
        assertTrue(session.isCurrent(secondOwner));
        assertTrue(chosenCandidate.get(overlay) instanceof CenterlineCandidate);

        AtomicInteger overlayHideCalls = new AtomicInteger();
        AtomicInteger runtimeCloseCalls = new AtomicInteger();
        WayHeatmapTracerPlugin.closeModernPreviewRuntime(session, () -> {
            assertFalse(session.isCurrent(secondOwner));
            overlayHideCalls.incrementAndGet();
            overlay.hide();
        }, runtimeCloseCalls::incrementAndGet);

        assertEquals(1, overlayHideCalls.get());
        assertEquals(1, runtimeCloseCalls.get());
        assertNull(chosenCandidate.get(overlay));
        actions.subList(1, actions.size()).forEach(JosmAction::destroy);
    }
}
