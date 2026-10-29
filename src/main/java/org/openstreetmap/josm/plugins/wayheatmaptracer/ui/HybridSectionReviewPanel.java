package org.openstreetmap.josm.plugins.wayheatmaptracer.ui;

import java.awt.BorderLayout;
import java.util.List;
import java.util.Objects;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBAssessor.AssessedCandidate;

/** Bounded section report bound to the selected immutable Hybrid geometry. */
public final class HybridSectionReviewPanel extends JPanel {
    private final HybridSectionTableModel model = new HybridSectionTableModel();
    private final JTable table = model.createTable();
    private final JLabel summary = new JLabel();
    private final PreviewOverlay overlay;
    private List<EastNorth> projected = List.of();

    public HybridSectionReviewPanel(PreviewOverlay overlay) {
        super(new BorderLayout(0, 4));
        this.overlay = Objects.requireNonNull(overlay);
        add(summary, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        table.getSelectionModel().addListSelectionListener(event -> {
            if (event.getValueIsAdjusting()) return;
            int selected = table.getSelectedRow();
            if (selected < 0) overlay.clearHighlightedSection();
            else overlay.setHighlightedSection(projected,
                    model.selectedVertexRange(table.convertRowIndexToModel(selected)));
        });
        setVisible(false);
    }

    /** Clears the old highlight and binds factual final vertex ranges to slide-time projected points. */
    public void setCandidate(AssessedCandidate candidate, List<EastNorth> finalProjected) {
        table.clearSelection();
        overlay.clearHighlightedSection();
        projected = List.of();
        model.setCandidate(null);
        setVisible(false);
        if (candidate != null) {
            var copy = List.copyOf(finalProjected);
            if (copy.size() != candidate.candidate().points().size()) {
                throw new IllegalArgumentException("Hybrid section highlight needs complete final geometry");
            }
            projected = copy;
            model.setCandidate(candidate);
            setVisible(true);
        }
        summary.setText(model.summary());
    }

    JTable table() { return table; }
    String summaryText() { return summary.getText(); }
}
