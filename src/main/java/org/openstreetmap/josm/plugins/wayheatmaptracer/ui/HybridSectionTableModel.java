package org.openstreetmap.josm.plugins.wayheatmaptracer.ui;

import static org.openstreetmap.josm.tools.I18n.tr;
import java.awt.Component;
import java.awt.Dimension;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellRenderer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBAssessor.AssessedCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBAssessor.SectionSupport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBPipeline.Source;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBPipeline.VertexRange;

/** Read-only section evidence and final highlight ranges, independent of the user's current viewport. */
public final class HybridSectionTableModel extends AbstractTableModel {
    private List<SectionSupport> rows = List.of();

    /** Replaces rows for the exact selected candidate; null clears the previous candidate. */
    public void setCandidate(AssessedCandidate candidate) {
        rows = candidate == null ? List.of() : candidate.sections();
        fireTableDataChanged();
    }

    public String summary() {
        if (rows.isEmpty()) { return tr("No Hybrid candidate selected."); }
        long refined = rows.stream().filter(row -> row.choice().source() == Source.B).count();
        return tr("B refined {0} of {1} sections; {2} retained Legacy",refined,rows.size(),rows.size()-refined);
    }

    public VertexRange selectedVertexRange(int row) { return rows.get(row).choice().vertices(); }
    public OccurrenceRange originalRange(int row) { return rows.get(row).choice().section().originalRange(); }
    @Override public int getRowCount() { return rows.size(); }
    @Override public int getColumnCount() { return 5; }
    @Override public Class<?> getColumnClass(int column) { return String.class; }
    @Override public String getColumnName(int column) {
        return switch(column) {
            case 0 -> tr("Section"); case 1 -> tr("Geometry"); case 2 -> tr("Heatmap support");
            case 3 -> tr("Status"); case 4 -> tr("Warnings");
            default -> throw new IndexOutOfBoundsException(column);
        };
    }

    @Override public Object getValueAt(int index, int column) {
        SectionSupport row = rows.get(index);
        return switch(column) {
            case 0 -> originalRange(index).firstIndex()+"–"+originalRange(index).lastIndex();
            case 1 -> row.choice().source() == Source.B ? tr("B refined")
                    : row.choice().section().frozen() ? tr("Left untouched") : tr("Legacy");
            case 2 -> row.directlySupportedLengthMeters().isPresent()
                    ? String.format(Locale.ROOT,"%.1f m of %.1f m",
                            row.directlySupportedLengthMeters().orElseThrow(),row.totalLengthMeters())
                    : tr("Unavailable ({0} m total)",String.format(Locale.ROOT,"%.1f",row.totalLengthMeters()));
            case 3 -> status(row);
            case 4 -> row.findings().stream().map(finding -> finding.code().name()).distinct()
                    .map(HybridSectionTableModel::readableCode).collect(Collectors.joining(", "));
            default -> throw new IndexOutOfBoundsException(column);
        };
    }

    private static String status(SectionSupport row) {
        return switch(row.choice().reason()) {
            case B_REFINED -> tr("Refined using B");
            case LEGACY_ALTERNATIVE -> tr("Legacy alternative");
            case FIXED_SECTION -> tr("Protected section left untouched");
            case NO_ROUTE -> tr("B found no usable route; Legacy retained");
            case NO_USABLE_REFINEMENT -> tr("B result did not pass local safety or support checks; Legacy retained");
            case DEGENERATE_REFERENCE -> tr("Section too short for B; Legacy retained");
            case EVIDENCE_COVERAGE -> tr("B imagery coverage unavailable; Legacy retained");
            case SPATIAL_RESOLUTION_UNAVAILABLE -> tr("B image resolution unavailable; Legacy retained");
            case WORK_BUDGET -> tr("B work limit reached; Legacy retained");
            case RESOURCE_LIMIT -> tr("B resource limit reached; Legacy retained");
            case DEADLINE -> tr("B time limit reached; Legacy retained");
        };
    }

    private static String readableCode(String code) {
        String text = code.toLowerCase(Locale.ROOT).replace('_',' ');
        return Character.toUpperCase(text.charAt(0))+text.substring(1);
    }

    /** Bounded, wrapped rows keep long diagnostics from expanding the surrounding modeless dialog. */
    public JTable createTable() {
        var table = new JTable(this);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        table.setPreferredScrollableViewportSize(new Dimension(640,144));
        table.setDefaultRenderer(String.class,new WrappedRenderer());
        return table;
    }

    private static final class WrappedRenderer extends JTextArea implements TableCellRenderer {
        private WrappedRenderer() { setLineWrap(true); setWrapStyleWord(true); setOpaque(true); }
        @Override public Component getTableCellRendererComponent(JTable table,Object value,boolean selected,
                boolean focus,int row,int column) {
            setText(value == null ? "" : value.toString());
            setFont(table.getFont());
            setForeground(selected ? table.getSelectionForeground() : table.getForeground());
            setBackground(selected ? table.getSelectionBackground() : table.getBackground());
            setSize(Math.max(30,table.getColumnModel().getColumn(column).getWidth()),96);
            int required = Math.min(96,Math.max(table.getRowHeight(row),getPreferredSize().height));
            if (required != table.getRowHeight(row)) { table.setRowHeight(row,required); }
            return this;
        }
    }
}
