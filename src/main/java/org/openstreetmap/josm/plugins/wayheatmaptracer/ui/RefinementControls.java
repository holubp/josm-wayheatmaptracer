package org.openstreetmap.josm.plugins.wayheatmaptracer.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.List;
import java.util.Objects;
import javax.swing.*;

public final class RefinementControls extends JPanel {
    public record Choice(String id, String label) { @Override public String toString() { return label; } }
    public enum Strength { MEDIUM, STRONG }
    public record Availability(boolean enabled, String reason) { }
    public record ViewState(String stage, List<Choice> choices, String selectedId, String quality,
            Strength strength, boolean busy, Availability selection, Availability undo, Availability redo,
            Availability refineA, Availability refineB, Availability smooth) {
        public ViewState { choices = List.copyOf(choices); }
    }
    public interface Listener {
        void selected(String id); void strengthSelected(Strength strength); void undo(); void redo();
        void refineA(); void refineB(); void smooth(Strength strength); void cancel();
    }
    private final JComboBox<Choice> candidate = new JComboBox<>();
    private final JComboBox<Strength> strength = new JComboBox<>(Strength.values());
    private final JLabel stage = new JLabel();
    private final JTextArea quality = new JTextArea(3, 25);
    private final JButton undo = button("Undo", "undo"), redo = button("Redo", "redo"),
            a = button("Refine using A", "refineA"), b = button("Refine using B", "refineB"),
            smooth = button("Smoothen", "smooth"), cancel = button("Cancel", "cancel");
    private boolean presenting;

    public RefinementControls(Listener listener) {
        Objects.requireNonNull(listener);
        setLayout(new BorderLayout(4, 4));
        JPanel top = new JPanel(new BorderLayout(4, 4));
        stage.setName("stage"); top.add(stage, BorderLayout.NORTH);
        candidate.setName("candidate"); candidate.getAccessibleContext().setAccessibleName("Candidate and color tracer");
        top.add(candidate, BorderLayout.CENTER); add(top, BorderLayout.NORTH);
        quality.setName("quality"); quality.setEditable(false); quality.setLineWrap(true); quality.setWrapStyleWord(true);
        quality.getAccessibleContext().setAccessibleName("Quality and status");
        JScrollPane scroll = new JScrollPane(quality); scroll.setPreferredSize(new Dimension(260, 65));
        add(scroll, BorderLayout.CENTER);
        JPanel actions = new JPanel(); actions.setLayout(new BoxLayout(actions, BoxLayout.Y_AXIS));
        JPanel navigation = new JPanel(new FlowLayout(FlowLayout.LEADING)); navigation.add(undo); navigation.add(redo); navigation.add(cancel);
        JPanel refinementA = new JPanel(new FlowLayout(FlowLayout.LEADING)); refinementA.add(a);
        JPanel refinementB = new JPanel(new FlowLayout(FlowLayout.LEADING)); refinementB.add(b);
        JPanel smoothing = new JPanel(new FlowLayout(FlowLayout.LEADING));
        strength.setName("strength"); strength.getAccessibleContext().setAccessibleName("Smoothing strength");
        smoothing.add(strength); smoothing.add(smooth);
        actions.add(navigation); actions.add(refinementA); actions.add(refinementB); actions.add(smoothing); add(actions, BorderLayout.SOUTH);
        candidate.addActionListener(e -> { if (!presenting && candidate.getSelectedItem() != null) listener.selected(((Choice) candidate.getSelectedItem()).id()); });
        strength.addActionListener(e -> { if (!presenting && strength.getSelectedItem() != null) listener.strengthSelected((Strength) strength.getSelectedItem()); });
        undo.addActionListener(e -> listener.undo()); redo.addActionListener(e -> listener.redo());
        a.addActionListener(e -> listener.refineA()); b.addActionListener(e -> listener.refineB());
        smooth.addActionListener(e -> listener.smooth((Strength) strength.getSelectedItem())); cancel.addActionListener(e -> listener.cancel());
    }
    private static JButton button(String text, String name) { JButton result = new JButton(text); result.setName(name); return result; }
    private static void availability(JComponent control, Availability availability, boolean busy) {
        control.setEnabled(!busy && availability.enabled());
        control.setToolTipText(busy ? "Operation in progress" : availability.enabled() ? null : availability.reason());
    }
    public void present(ViewState state) {
        Objects.requireNonNull(state);
        presenting = true;
        try {
            stage.setText(state.stage()); quality.setText(state.quality()); quality.setCaretPosition(0);
            candidate.removeAllItems(); Choice selected = null;
            for (Choice choice : state.choices()) { candidate.addItem(choice); if (Objects.equals(choice.id(), state.selectedId())) selected = choice; }
            candidate.setSelectedItem(selected); strength.setSelectedItem(state.strength());
            availability(candidate, state.selection(), state.busy()); availability(strength, state.smooth(), state.busy());
            availability(undo, state.undo(), state.busy()); availability(redo, state.redo(), state.busy());
            availability(a, state.refineA(), state.busy()); availability(b, state.refineB(), state.busy());
            availability(smooth, state.smooth(), state.busy()); cancel.setEnabled(state.busy()); cancel.setVisible(state.busy());
        } finally { presenting = false; }
    }
}
