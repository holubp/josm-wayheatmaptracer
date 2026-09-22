package org.openstreetmap.josm.plugins.wayheatmaptracer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;

import javax.swing.Action;
import javax.swing.JPanel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.wayheatmaptracer.actions.AlignWayAction;
import org.openstreetmap.josm.plugins.wayheatmaptracer.actions.GeometryCleanupSettingsAction;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.PreviewSessionController;
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
                    "Experimental Engine A Visible Preview (Read Only)",
                    "Experimental Engine B Visible Preview (Read Only)",
                    "Experimental Engine Hybrid A+B Visible Preview (Read Only)",
                    "Experimental Engine Image Visible Preview (Read Only)",
                    "Experimental Engine A Managed Preview (Read Only)",
                    "Experimental Engine B Managed Preview (Read Only)"),
                    actions.subList(3, 9).stream()
                            .map(action -> action.getValue(Action.NAME)).toList());
        } finally {
            actions.forEach(JosmAction::destroy);
            session.close();
        }
    }
}
