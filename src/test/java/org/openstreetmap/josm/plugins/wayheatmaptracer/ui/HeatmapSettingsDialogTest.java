package org.openstreetmap.josm.plugins.wayheatmaptracer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.KeyboardFocusManager;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.beans.PropertyChangeListener;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.KeyStroke;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

/** Exercises real focus and keyboard events using synthetic access values in an isolated Swing process. */
class HeatmapSettingsDialogTest {
    @Test
    void enterAcceptsTheCookiePaneWhileTheTextAreaKeepsItsStandardPasteAction() throws Exception {
        var method = HeatmapSettingsDialog.class.getDeclaredMethod("cookiePastePane", JTextArea.class);
        method.setAccessible(true);
        JTextArea area = new JTextArea(); area.setText("synthetic cookie");
        JOptionPane pane = (JOptionPane) method.invoke(null, area);
        Object enter = area.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0));
        area.getActionMap().get(enter).actionPerformed(new java.awt.event.ActionEvent(area, 0, "enter"));
        assertEquals(JOptionPane.OK_OPTION, pane.getValue());
        assertEquals("synthetic cookie", area.getText(), "Enter accepts instead of modifying secret input");
        Object paste = area.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_V, KeyEvent.CTRL_DOWN_MASK));
        assertTrue(area.getActionMap().get(paste) instanceof javax.swing.text.DefaultEditorKit.PasteAction);
        assertEquals(JOptionPane.OK_CANCEL_OPTION, pane.getOptionType());
    }

    public static final class CookieKeyboardProcess {
        private static java.awt.datatransfer.Transferable previousClipboard;
        private static boolean clipboardChanged;

        public static void main(String[] args) {
            try {
                Config.setPreferencesInstance(new MemoryPreferences());
                JFrame owner = new JFrame("Synthetic settings owner");
                SwingUtilities.invokeAndWait(() -> {
                    owner.setSize(400, 200);
                    owner.setLocation(100, 100);
                    owner.setVisible(true);
                });
                var settings = new HeatmapSettingsDialog(owner);
                Robot robot = new Robot(); robot.setAutoDelay(0);
                String synthetic = "CloudFront-Key-Pair-Id=test-key; CloudFront-Policy=test-policy; "
                        + "CloudFront-Signature=test-signature; _strava_idcf=test-session";
                previousClipboard = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
                clipboardChanged = true;
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                        new java.awt.datatransfer.StringSelection(synthetic), null);
                KeyboardFocusManager focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
                CountDownLatch pasteFieldFocused = new CountDownLatch(1);
                PropertyChangeListener focusListener = event -> {
                    if (event.getNewValue() instanceof JTextArea) pasteFieldFocused.countDown();
                };
                focusManager.addPropertyChangeListener("focusOwner", focusListener);
                SwingUtilities.invokeLater(settings::showDialog);
                clickAndPasteImmediately(robot, awaitPasteButton());
                robot.waitForIdle();
                JTextArea area = awaitArea();
                Window cookieDialog = SwingUtilities.getWindowAncestor(area);
                JOptionPane cookiePane = findOptionPane(area);
                if (cookieDialog.getFocusTraversalPolicy().getInitialComponent(cookieDialog) != area)
                    throw new AssertionError("cookie text area is not the dialog's initial focus component");
                if (focusManager.getFocusOwner() == area) pasteFieldFocused.countDown();
                if (!pasteFieldFocused.await(5, TimeUnit.SECONDS))
                    throw new AssertionError("cookie text area did not receive opening focus");
                focusManager.removePropertyChangeListener("focusOwner", focusListener);
                SwingUtilities.invokeAndWait(cookiePane::selectInitialValue);
                robot.waitForIdle();
                if (focusManager.getFocusOwner() != area)
                    throw new AssertionError("JOptionPane initial-value selection stole paste-field focus");
                if (!synthetic.equals(area.getText())) throw new AssertionError("Ctrl+V did not paste into cookie text area");
                press(robot, KeyEvent.VK_ENTER);
                Thread.sleep(350);
                if (area.isShowing()) throw new AssertionError("Enter did not accept cookie dialog");
                Field field = HeatmapSettingsDialog.class.getDeclaredField("keyPairId"); field.setAccessible(true);
                if (!"test-key".equals(((JTextField) field.get(settings)).getText()))
                    throw new AssertionError("accepted cookie was not parsed");
                CountDownLatch cancelledFieldFocused = new CountDownLatch(1);
                PropertyChangeListener cancelledFocusListener = event -> {
                    if (event.getNewValue() instanceof JTextArea) cancelledFieldFocused.countDown();
                };
                focusManager.addPropertyChangeListener("focusOwner", cancelledFocusListener);
                click(robot, awaitPasteButton());
                JTextArea cancelled = awaitArea();
                if (focusManager.getFocusOwner() == cancelled) cancelledFieldFocused.countDown();
                if (!cancelledFieldFocused.await(5, TimeUnit.SECONDS))
                    throw new AssertionError("second cookie text area did not receive opening focus");
                focusManager.removePropertyChangeListener("focusOwner", cancelledFocusListener);
                SwingUtilities.invokeAndWait(() -> cancelled.setText("invalid synthetic replacement"));
                press(robot, KeyEvent.VK_ESCAPE); Thread.sleep(250);
                if (cancelled.isShowing()) throw new AssertionError("Escape did not cancel cookie dialog");
                if (!"test-key".equals(((JTextField) field.get(settings)).getText()))
                    throw new AssertionError("cancel changed accepted access values");
                verifyWorkerFailureRemainsModeless();
                restoreClipboard();
                owner.dispose();
                System.exit(0);
            } catch (Throwable failure) {
                System.err.println(failure.getClass().getSimpleName() + ": " + failure.getMessage());
                restoreClipboard();
                System.exit(1);
            }
        }

        private static void restoreClipboard() {
            if (clipboardChanged) {
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(previousClipboard == null
                        ? new java.awt.datatransfer.StringSelection("") : previousClipboard, null);
            }
        }

        private static void verifyWorkerFailureRemainsModeless() throws Exception {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    javax.swing.JFrame owner = new javax.swing.JFrame("Synthetic ordinary UI");
                    javax.swing.JButton other = new javax.swing.JButton("Other UI");
                    java.util.concurrent.atomic.AtomicInteger clicks = new java.util.concurrent.atomic.AtomicInteger();
                    other.addActionListener(event -> clicks.incrementAndGet()); owner.add(other); owner.pack(); owner.setVisible(true);
                    var factory = org.openstreetmap.josm.plugins.wayheatmaptracer.actions.AlignWayAction.class
                            .getDeclaredMethod("createWorkerFailureDialog", Window.class, String.class);
                    factory.setAccessible(true);
                    JDialog failure = (JDialog) factory.invoke(null, owner, "Synthetic worker failed safely");
                    failure.setVisible(true);
                    if (failure.isModal() || !owner.isEnabled()) throw new AssertionError("worker failure blocks other UI");
                    other.doClick();
                    if (clicks.get() != 1) throw new AssertionError("ordinary UI no longer responds");
                    failure.dispose(); owner.dispose();
                } catch (ReflectiveOperationException failure) {
                    throw new RuntimeException(failure);
                }
            });
        }

        private static void press(Robot robot, int... keys) {
            for (int key : keys) robot.keyPress(key);
            for (int i = keys.length - 1; i >= 0; i--) robot.keyRelease(keys[i]);
            robot.waitForIdle();
        }

        private static JTextArea awaitArea() throws Exception {
            for (int attempt = 0; attempt < 40; attempt++) {
                final JTextArea[] area = {null};
                SwingUtilities.invokeAndWait(() -> {
                    for (Window window : Window.getWindows()) if (window instanceof JDialog && window.isShowing()) {
                        area[0] = findArea(window); if (area[0] != null) break;
                    }
                });
                if (area[0] != null) return area[0];
                Thread.sleep(100);
            }
            throw new AssertionError("cookie dialog did not open");
        }

        private static JButton awaitPasteButton() throws Exception {
            for (int attempt = 0; attempt < 40; attempt++) {
                final JButton[] button = {null};
                SwingUtilities.invokeAndWait(() -> {
                    for (Window window : Window.getWindows()) if (window.isShowing()) {
                        button[0] = findPasteButton(window); if (button[0] != null) break;
                    }
                });
                if (button[0] != null) return button[0];
                Thread.sleep(100);
            }
            throw new AssertionError("settings dialog Paste button did not open");
        }

        private static void click(Robot robot, JButton button) {
            java.awt.Point point = button.getLocationOnScreen();
            robot.mouseMove(point.x + button.getWidth() / 2, point.y + button.getHeight() / 2);
            robot.mousePress(KeyEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(KeyEvent.BUTTON1_DOWN_MASK);
            robot.waitForIdle();
        }

        private static void clickAndPasteImmediately(Robot robot, JButton button) {
            java.awt.Point point = button.getLocationOnScreen();
            robot.mouseMove(point.x + button.getWidth() / 2, point.y + button.getHeight() / 2);
            robot.mousePress(KeyEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(KeyEvent.BUTTON1_DOWN_MASK);
            robot.keyPress(KeyEvent.VK_CONTROL);
            robot.keyPress(KeyEvent.VK_V);
            robot.keyRelease(KeyEvent.VK_V);
            robot.keyRelease(KeyEvent.VK_CONTROL);
        }

        private static JButton findPasteButton(Component component) {
            if (component instanceof JButton button && button.getText().contains("Paste cookie header")) return button;
            if (component instanceof Container parent) for (Component child : parent.getComponents()) {
                JButton found = findPasteButton(child); if (found != null) return found;
            }
            return null;
        }

        private static JOptionPane findOptionPane(Component component) {
            for (Component current = component; current != null; current = current.getParent()) {
                if (current instanceof JOptionPane pane) return pane;
            }
            throw new AssertionError("cookie JOptionPane was not found in component ancestry");
        }

        private static JTextArea findArea(Component component) {
            if (component instanceof JTextArea area) return area;
            if (component instanceof Container parent) for (Component child : parent.getComponents()) {
                JTextArea found = findArea(child); if (found != null) return found;
            }
            return null;
        }
    }
}
