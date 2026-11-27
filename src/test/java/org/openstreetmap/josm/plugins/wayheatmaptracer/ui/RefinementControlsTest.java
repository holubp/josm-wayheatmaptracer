package org.openstreetmap.josm.plugins.wayheatmaptracer.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Component;
import java.awt.Container;
import java.util.*;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.ui.RefinementControls.*;

class RefinementControlsTest {
    private static Component find(Container c, String name) {
        for (Component child : c.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) { Component found = find(nested, name); if (found != null) return found; }
        }
        return null;
    }
    private static final Availability YES = new Availability(true, "");
    private static final Availability NO = new Availability(false, "No earlier stage");
    private static ViewState state(String id, Strength strength, boolean busy, String quality) {
        return new ViewState("Legacy", List.of(new Choice("a", "Blue"), new Choice("b", "Hot")), id,
                quality, strength, busy, YES, NO, YES, YES, YES, YES);
    }
    private static class Events implements Listener {
        final List<String> events = new ArrayList<>();
        public void selected(String id) { events.add("select:" + id); }
        public void strengthSelected(Strength s) { events.add("strength:" + s); }
        public void undo() { events.add("undo"); } public void redo() { events.add("redo"); }
        public void refineA() { events.add("a"); } public void refineB() { events.add("b"); }
        public void smooth(Strength s) { events.add("smooth:" + s); } public void cancel() { events.add("cancel"); }
    }
    @Test void restoresWithoutCallbacksAndUserActionsAreExplicit() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Events e = new Events(); RefinementControls p = new RefinementControls(e);
            assertEquals("Refine using A",((JButton)find(p,"refineA")).getText());
            assertEquals("Refine using B",((JButton)find(p,"refineB")).getText());
            assertEquals("Smoothen",((JButton)find(p,"smooth")).getText());
            p.present(state("a", Strength.MEDIUM, false, "First"));
            p.present(state("b", Strength.STRONG, false, "Second"));
            assertTrue(e.events.isEmpty());
            JComboBox<?> choices = (JComboBox<?>) find(p, "candidate");
            assertEquals("b", ((Choice) choices.getSelectedItem()).id());
            JComboBox<?> strength = (JComboBox<?>) find(p, "strength");
            assertEquals(Strength.STRONG, strength.getSelectedItem());
            assertEquals("Second", ((JTextArea) find(p, "quality")).getText());
            choices.setSelectedIndex(0); strength.setSelectedItem(Strength.MEDIUM);
            ((JButton) find(p, "smooth")).doClick(); ((JButton) find(p, "redo")).doClick();
            assertEquals(List.of("select:a", "strength:MEDIUM", "smooth:MEDIUM", "redo"), e.events);
        });
    }
    @Test void busyAndBoundaryStatesRemainAccessibleAndTextIsBounded() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Events e = new Events(); RefinementControls p = new RefinementControls(e);
            p.present(state("a", Strength.MEDIUM, false, "Long finding ".repeat(500)));
            JButton undo = (JButton) find(p, "undo");
            assertFalse(undo.isEnabled()); assertEquals("No earlier stage", undo.getToolTipText());
            assertTrue(p.getPreferredSize().height < 500);
            p.present(state("a", Strength.MEDIUM, true, "Working"));
            assertFalse(find(p, "candidate").isEnabled()); assertFalse(find(p, "strength").isEnabled());
            assertFalse(find(p, "redo").isEnabled()); assertFalse(find(p, "refineA").isEnabled());
            assertFalse(find(p, "refineB").isEnabled()); assertFalse(find(p, "smooth").isEnabled());
            JButton cancel = (JButton) find(p, "cancel"); assertTrue(cancel.isEnabled()); cancel.doClick();
            assertEquals(List.of("cancel"), e.events);
        });
    }
    @Test void refinementActionsShareCompactRowAndLongChoiceDoesNotWidenDialog() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RefinementControls p = new RefinementControls(new Events());
            p.present(new ViewState("Stage", List.of(new Choice("a", "Long detector ".repeat(100))), "a",
                "Long finding ".repeat(500), Strength.MEDIUM, false, YES, NO, YES, YES, YES, YES));
            assertSame(find(p, "refineA").getParent(), find(p, "refineB").getParent());
            assertTrue(p.getPreferredSize().width < 700, "long detector label must not widen dialog");
        });
    }
    private static void layoutTree(Container c) {
        c.doLayout();
        for (Component child : c.getComponents()) if (child instanceof Container nested) layoutTree(nested);
    }
    @Test void narrowLayoutContainsEveryActionWithoutOverlap() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RefinementControls p = new RefinementControls(new Events());
            p.present(state("a", Strength.MEDIUM, true, "Working"));
            p.setSize(320, p.getPreferredSize().height); layoutTree(p);
            List<java.awt.Rectangle> bounds = new ArrayList<>();
            for (String name : List.of("undo", "redo", "cancel", "refineA", "refineB", "strength", "smooth")) {
                Component control = find(p, name);
                assertTrue(control.isVisible(), name);
                Component current = control;
                while (current != p) {
                    Container parent = current.getParent();
                    assertTrue(parent.getBounds().width >= current.getX() + current.getWidth(), name + " width in " + parent);
                    assertTrue(parent.getBounds().height >= current.getY() + current.getHeight(), name + " height in " + parent);
                    current = parent;
                }
                java.awt.Rectangle rect = SwingUtilities.convertRectangle(control.getParent(), control.getBounds(), p);
                for (java.awt.Rectangle previous : bounds) assertFalse(rect.intersects(previous), name + " overlaps another control");
                bounds.add(rect);
            }
        });
    }
}
