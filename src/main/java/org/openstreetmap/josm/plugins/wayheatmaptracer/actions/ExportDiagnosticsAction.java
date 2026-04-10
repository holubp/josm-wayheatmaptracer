package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.DiagnosticsRegistry;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.PluginDirectories;
import org.openstreetmap.josm.tools.Shortcut;

public class ExportDiagnosticsAction extends JosmAction {
    public ExportDiagnosticsAction() {
        super(
            tr("Export Diagnostics"),
            null,
            tr("Export the last redacted WayHeatmapTracer diagnostics bundle"),
            Shortcut.registerShortcut(
                "wayheatmaptracer:export-diagnostics",
                tr("WayHeatmapTracer: Export Diagnostics"),
                KeyEvent.VK_D,
                Shortcut.ALT_CTRL_SHIFT
            ),
            false
        );
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        String payload = DiagnosticsRegistry.getLastBundle();
        if (payload == null || payload.isBlank()) {
            JOptionPane.showMessageDialog(
                MainApplication.getMainFrame(),
                tr("No diagnostics bundle is available yet. Run an alignment first."),
                tr("WayHeatmapTracer"),
                JOptionPane.WARNING_MESSAGE
            );
            return;
        }

        try {
            File dir = PluginDirectories.ensurePluginDataDirectory();
            File file = new File(dir, "diagnostics-" + System.currentTimeMillis() + ".json");
            Files.writeString(file.toPath(), payload, StandardCharsets.UTF_8);
            JOptionPane.showMessageDialog(
                MainApplication.getMainFrame(),
                tr("Diagnostics exported to {0}", file.getAbsolutePath()),
                tr("WayHeatmapTracer"),
                JOptionPane.INFORMATION_MESSAGE
            );
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(
                MainApplication.getMainFrame(),
                tr("Failed to export diagnostics: {0}", ex.getMessage()),
                tr("WayHeatmapTracer"),
                JOptionPane.ERROR_MESSAGE
            );
        }
    }
}
