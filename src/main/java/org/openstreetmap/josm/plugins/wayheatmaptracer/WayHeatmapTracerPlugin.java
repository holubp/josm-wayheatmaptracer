package org.openstreetmap.josm.plugins.wayheatmaptracer;

import java.awt.Component;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.ActionEvent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import javax.swing.Timer;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MainMenu;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.plugins.wayheatmaptracer.actions.AlignWayAction;
import org.openstreetmap.josm.plugins.wayheatmaptracer.actions.ExportDiagnosticsAction;
import org.openstreetmap.josm.plugins.wayheatmaptracer.actions.HeatmapLayerSettingsAction;

public class WayHeatmapTracerPlugin extends Plugin {
    private static final String ALIGN_SHORTCUT_ACTION_KEY = "wayheatmaptracer.align.global";

    private final List<JosmAction> actions;
    private final AlignWayAction alignWayAction;
    private Timer shortcutInstallRetryTimer;

    public WayHeatmapTracerPlugin(PluginInformation info) {
        super(info);
        this.alignWayAction = new AlignWayAction();
        this.actions = List.of(
            alignWayAction,
            new HeatmapLayerSettingsAction(),
            new ExportDiagnosticsAction()
        );

        for (JosmAction action : actions) {
            MainMenu.add(MainApplication.getMenu().moreToolsMenu, action);
        }
        scheduleShortcutInstall();
    }

    public void destroy() {
        JMenu menu = MainApplication.getMenu().moreToolsMenu;
        Map<Action, Component> byAction = Arrays.stream(menu.getMenuComponents())
            .filter(JMenuItem.class::isInstance)
            .map(JMenuItem.class::cast)
            .collect(Collectors.toMap(JMenuItem::getAction, item -> item, (left, right) -> left, LinkedHashMap::new));

        for (JosmAction action : actions) {
            Component component = byAction.get(action);
            if (component != null) {
                menu.remove(component);
            }
            action.destroy();
        }
        uninstallGlobalShortcut();
        if (shortcutInstallRetryTimer != null) {
            shortcutInstallRetryTimer.stop();
            shortcutInstallRetryTimer = null;
        }
    }

    private void scheduleShortcutInstall() {
        if (installGlobalShortcut()) {
            return;
        }
        shortcutInstallRetryTimer = new Timer(750, event -> {
            if (installGlobalShortcut()) {
                ((Timer) event.getSource()).stop();
                shortcutInstallRetryTimer = null;
            }
        });
        shortcutInstallRetryTimer.setRepeats(true);
        shortcutInstallRetryTimer.start();
    }

    private boolean installGlobalShortcut() {
        KeyStroke stroke = KeyStroke.getKeyStroke(
            KeyEvent.VK_Y,
            InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK
        );
        boolean installed = false;

        if (MainApplication.getMainFrame() != null) {
            JRootPane rootPane = MainApplication.getMainFrame().getRootPane();
            if (rootPane != null) {
                rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(stroke, ALIGN_SHORTCUT_ACTION_KEY);
                rootPane.getActionMap().put(ALIGN_SHORTCUT_ACTION_KEY, alignWayAction);
                installed = true;
            }
        }
        if (MainApplication.getMap() != null && MainApplication.getMap().mapView != null) {
            MainApplication.getMap().mapView.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(stroke, ALIGN_SHORTCUT_ACTION_KEY);
            MainApplication.getMap().mapView.getActionMap().put(ALIGN_SHORTCUT_ACTION_KEY, alignWayAction);
            installed = true;
        }
        return installed;
    }

    private void uninstallGlobalShortcut() {
        KeyStroke stroke = KeyStroke.getKeyStroke(
            KeyEvent.VK_Y,
            InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK
        );
        if (MainApplication.getMainFrame() != null) {
            JRootPane rootPane = MainApplication.getMainFrame().getRootPane();
            if (rootPane != null) {
                rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).remove(stroke);
                rootPane.getActionMap().remove(ALIGN_SHORTCUT_ACTION_KEY);
            }
        }
        if (MainApplication.getMap() != null && MainApplication.getMap().mapView != null) {
            MainApplication.getMap().mapView.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).remove(stroke);
            MainApplication.getMap().mapView.getActionMap().remove(ALIGN_SHORTCUT_ACTION_KEY);
        }
    }
}
