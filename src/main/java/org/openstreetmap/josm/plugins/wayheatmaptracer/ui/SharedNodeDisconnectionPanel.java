package org.openstreetmap.josm.plugins.wayheatmaptracer.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SharedNodeDisconnectionIntent;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.SharedNodeDisconnectionEligibility;

/** Session-local occurrence chooser; choices never persist beyond the current preview session. */
public final class SharedNodeDisconnectionPanel extends JPanel {
    public record Choice(int occurrenceIndex, PrimitiveKey nodeKey, int affectedConnections,
            SharedNodeDisconnectionEligibility.Decision eligibility) { }
    private final NetworkSnapshot source;
    private final PrimitiveKey way;
    private final OccurrenceRange range;
    private final String sourceIdentity;
    private final Map<Integer, Choice> choices = new LinkedHashMap<>();
    private final Map<Integer, JCheckBox> controls = new LinkedHashMap<>();

    /** Derives every displayed reason from the immutable original pre-island capture. */
    public SharedNodeDisconnectionPanel(NetworkSnapshot source, PrimitiveKey way, OccurrenceRange range) {
        this(source, way, range, SharedNodeDisconnectionIntent.none());
    }

    /** Reopens the current session choice without granting any new authority. */
    public SharedNodeDisconnectionPanel(NetworkSnapshot source, PrimitiveKey way, OccurrenceRange range,
            SharedNodeDisconnectionIntent current) {
        this.source = java.util.Objects.requireNonNull(source);
        this.way = java.util.Objects.requireNonNull(way);
        this.range = java.util.Objects.requireNonNull(range);
        this.sourceIdentity = source.canonicalHash();
        if (!(source.primitives().get(way) instanceof DetachedWay selected)
                || range.lastIndex() >= selected.nodeKeys().size()) {
            throw new IllegalArgumentException("Disconnection selection is outside its source");
        }
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        add(new JLabel("Connections are preserved unless you choose shared node occurrences below."));
        Map<Integer, PrimitiveKey> candidates = new LinkedHashMap<>();
        Map<Integer, Integer> connectionCounts = new LinkedHashMap<>();
        for (int index = range.firstIndex(); index <= range.lastIndex(); index++) {
            PrimitiveKey node = selected.nodeKeys().get(index);
            var incoming = source.incomingReferrerWatches().get(node);
            int count = incoming == null ? 0 : (int) incoming.stream()
                    .filter(key -> key.type() == PrimitiveKey.Type.WAY && !key.equals(way)).count();
            if (count == 0 && incoming != null) continue;
            candidates.put(index, node); connectionCounts.put(index, count);
        }
        var decisions = candidates.isEmpty() ? Map.<Integer, SharedNodeDisconnectionEligibility.Decision>of()
                : SharedNodeDisconnectionEligibility.evaluate(source,
                        new SharedNodeDisconnectionIntent(way, range, candidates, sourceIdentity)).decisions();
        for (var entry : candidates.entrySet()) {
            int index = entry.getKey(); PrimitiveKey node = entry.getValue(); int count = connectionCounts.get(index);
            var decision = decisions.get(index);
            Choice choice = new Choice(index, node, count, decision);
            choices.put(index, choice);
            JCheckBox control = new JCheckBox("Occurrence " + index + " (node " + node.id() + "): "
                    + count + " connection(s). " + decision.explanation());
            control.setEnabled(decision.eligible());
            controls.put(index, control); add(control);
        }
        if (choices.isEmpty()) add(new JLabel("No shared nodes occur in this selection."));
        java.util.Objects.requireNonNull(current);
        if (!current.isEmpty()) {
            for (int index : current.originalNodeKeys().keySet()) selectOccurrence(index, true);
            if (!current.equals(selectedIntent())) throw new IllegalArgumentException("Current disconnection choice is stale");
        }
    }

    /** Immutable eligible and unavailable occurrences, with factual referrer counts. */
    public List<Choice> choices() { return List.copyOf(choices.values()); }
    /** Selects only occurrences proven eligible in the original capture. */
    public void selectAllEligible() { controls.values().stream().filter(JCheckBox::isEnabled)
            .forEach(control -> control.setSelected(true)); }
    /** Changes one eligible occurrence; unavailable rows never grant authority. */
    public void selectOccurrence(int index, boolean selected) {
        JCheckBox control = controls.get(index);
        if (control == null || !control.isEnabled()) throw new IllegalArgumentException("Occurrence is unavailable");
        control.setSelected(selected);
    }
    /** Counts every connection to a different way at the explicitly chosen occurrences. */
    public int affectedConnectionCount() { return choices.values().stream()
            .filter(choice -> controls.get(choice.occurrenceIndex()).isSelected())
            .mapToInt(Choice::affectedConnections).sum(); }
    /** Returns the exact source-bound session choice, or inert empty authority. */
    public SharedNodeDisconnectionIntent selectedIntent() {
        Map<Integer, PrimitiveKey> selected = new LinkedHashMap<>();
        choices.values().stream().filter(choice -> controls.get(choice.occurrenceIndex()).isSelected())
                .forEach(choice -> selected.put(choice.occurrenceIndex(), choice.nodeKey()));
        return selected.isEmpty() ? SharedNodeDisconnectionIntent.none()
                : new SharedNodeDisconnectionIntent(way, range, selected, sourceIdentity);
    }
}
