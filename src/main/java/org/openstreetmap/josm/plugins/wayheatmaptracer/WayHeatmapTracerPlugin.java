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
import org.openstreetmap.josm.plugins.wayheatmaptracer.actions.ExportCalibrationTilesAction;
import org.openstreetmap.josm.plugins.wayheatmaptracer.actions.ExportDiagnosticsAction;
import org.openstreetmap.josm.plugins.wayheatmaptracer.actions.HeatmapLayerSettingsAction;
import org.openstreetmap.josm.plugins.wayheatmaptracer.actions.SelectLongestSegmentAction;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;

public class WayHeatmapTracerPlugin extends Plugin {
    private static final List<ShortcutBinding> ALIGN_SHORTCUTS = List.of(
        new ShortcutBinding("wayheatmaptracer.align.global", KeyEvent.VK_Y,
            InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
        new ShortcutBinding("wayheatmaptracer.align.precise.global", KeyEvent.VK_S,
            InputEvent.ALT_DOWN_MASK | InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
        new ShortcutBinding("wayheatmaptracer.align.move-nodes.global", KeyEvent.VK_M,
            InputEvent.ALT_DOWN_MASK | InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)
    );

    private final List<JosmAction> actions;
    private final AlignWayAction alignWayAction;
    private final AlignWayAction alignPreciseAction;
    private final AlignWayAction alignMoveNodesAction;
    private Timer shortcutInstallRetryTimer;

    public WayHeatmapTracerPlugin(PluginInformation info) {
        super(info);
        this.alignWayAction = new AlignWayAction();
        this.alignPreciseAction = new AlignWayAction(AlignmentMode.PRECISE_SHAPE);
        this.alignMoveNodesAction = new AlignWayAction(AlignmentMode.MOVE_EXISTING_NODES);
        this.actions = List.of(
            alignWayAction,
            alignPreciseAction,
            alignMoveNodesAction,
            new SelectLongestSegmentAction(),
            new HeatmapLayerSettingsAction(),
            new ExportCalibrationTilesAction(),
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
        boolean installed = false;

        if (MainApplication.getMainFrame() != null) {
            JRootPane rootPane = MainApplication.getMainFrame().getRootPane();
            if (rootPane != null) {
                installShortcut(rootPane, ALIGN_SHORTCUTS.get(0), alignWayAction);
                installShortcut(rootPane, ALIGN_SHORTCUTS.get(1), alignPreciseAction);
                installShortcut(rootPane, ALIGN_SHORTCUTS.get(2), alignMoveNodesAction);
                installed = true;
            }
        }
        if (MainApplication.getMap() != null && MainApplication.getMap().mapView != null) {
            installShortcut(MainApplication.getMap().mapView, ALIGN_SHORTCUTS.get(0), alignWayAction);
            installShortcut(MainApplication.getMap().mapView, ALIGN_SHORTCUTS.get(1), alignPreciseAction);
            installShortcut(MainApplication.getMap().mapView, ALIGN_SHORTCUTS.get(2), alignMoveNodesAction);
            installed = true;
        }
        return installed;
    }

    private void uninstallGlobalShortcut() {
        if (MainApplication.getMainFrame() != null) {
            JRootPane rootPane = MainApplication.getMainFrame().getRootPane();
            if (rootPane != null) {
                uninstallShortcuts(rootPane);
            }
        }
        if (MainApplication.getMap() != null && MainApplication.getMap().mapView != null) {
            uninstallShortcuts(MainApplication.getMap().mapView);
        }
    }

    private void installShortcut(JComponent component, ShortcutBinding binding, Action action) {
        component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(binding.keyStroke(), binding.actionKey());
        component.getActionMap().put(binding.actionKey(), action);
    }

    private void uninstallShortcuts(JComponent component) {
        for (ShortcutBinding binding : ALIGN_SHORTCUTS) {
            component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).remove(binding.keyStroke());
            component.getActionMap().remove(binding.actionKey());
        }
    }

    private record ShortcutBinding(String actionKey, int keyCode, int modifiers) {
        KeyStroke keyStroke() {
            return KeyStroke.getKeyStroke(keyCode, modifiers);
        }
    }
}
