package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.DiagnosticsRegistry;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.LastSlideDebugBundle;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.PluginDirectories;
import org.openstreetmap.josm.tools.GBC;
import org.openstreetmap.josm.tools.Shortcut;

public class ExportDiagnosticsAction extends JosmAction {
    public ExportDiagnosticsAction() {
        super(
            tr("Export Last Slide Debug Bundle"),
            null,
            tr("Export the last redacted WayHeatmapTracer slide debug bundle"),
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
        LastSlideDebugBundle bundle = DiagnosticsRegistry.getLastBundle();
        if (bundle == null) {
            JOptionPane.showMessageDialog(
                MainApplication.getMainFrame(),
                tr("No debug bundle is available yet. Run an alignment first."),
                tr("WayHeatmapTracer"),
                JOptionPane.WARNING_MESSAGE
            );
            return;
        }

        try {
            File dir = PluginDirectories.ensurePluginDataDirectory();
            File file = new File(dir, "last-slide-debug-" + System.currentTimeMillis() + ".zip");
            bundle.writeTo(file);
            showExportedDialog(file);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(
                MainApplication.getMainFrame(),
                tr("Failed to export debug bundle: {0}", ex.getMessage()),
                tr("WayHeatmapTracer"),
                JOptionPane.ERROR_MESSAGE
            );
        }
    }

    private void showExportedDialog(File file) {
        JPanel panel = new JPanel(new java.awt.GridBagLayout());
        JTextField path = new JTextField(file.getAbsolutePath(), 48);
        path.setEditable(false);
        JButton copyFile = new JButton(tr("Copy file path"));
        copyFile.addActionListener(event -> copy(file.getAbsolutePath()));
        JButton copyFolder = new JButton(tr("Copy folder path"));
        copyFolder.addActionListener(event -> copy(file.getParentFile().getAbsolutePath()));

        panel.add(new JLabel(tr("Debug bundle exported:")), GBC.eol());
        panel.add(path, GBC.eol().fill(GBC.HORIZONTAL));
        panel.add(copyFile, GBC.std());
        panel.add(copyFolder, GBC.eol());
        JOptionPane.showMessageDialog(MainApplication.getMainFrame(), panel, tr("WayHeatmapTracer"), JOptionPane.INFORMATION_MESSAGE);
    }

    private void copy(String value) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value), null);
    }
}
