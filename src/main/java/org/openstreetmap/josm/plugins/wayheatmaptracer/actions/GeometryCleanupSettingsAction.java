package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.wayheatmaptracer.ui.GeometryCleanupSettingsDialog;

/** Opens the configuration-only editor for future geometry cleanup candidates. */
public final class GeometryCleanupSettingsAction extends JosmAction {
    /** Creates an always-available action without a keyboard shortcut. */
    public GeometryCleanupSettingsAction() {
        super(tr("Geometry Cleanup Settings..."), null,
            tr("Configure optional cleanup for future heatmap alignment candidates"), null, false);
    }

    /**
     * Opens the settings editor without reading or modifying JOSM geometry.
     *
     * @param event Swing action event; its payload is not used
     */
    @Override
    public void actionPerformed(ActionEvent event) {
        new GeometryCleanupSettingsDialog(MainApplication.getMainFrame()).showDialog();
    }
}
