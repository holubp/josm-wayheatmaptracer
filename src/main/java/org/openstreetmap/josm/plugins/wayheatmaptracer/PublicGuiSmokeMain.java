package org.openstreetmap.josm.plugins.wayheatmaptracer;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.WindowEvent;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JDialog;
import javax.swing.JComboBox;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;

import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.plugins.wayheatmaptracer.actions.AlignWayAction;
import org.openstreetmap.josm.plugins.wayheatmaptracer.config.PluginPreferences;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.DiagnosticsRegistry;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15Archive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15ArchiveReader;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.FrozenReplayCodec;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentSourceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoverySettings;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TracingSettings;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.AlignmentJob;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.PreviewSessionController;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.data.UndoRedoHandler;

/** Runs a public analytic fixture through the registered ordinary JOSM GUI action. */
public final class PublicGuiSmokeMain {
    private PublicGuiSmokeMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("summary path and plugin jar required");
        Path report = Path.of(args[0]).toAbsolutePath().normalize();
        Path jar = Path.of(args[1]).toAbsolutePath().normalize();
        String stage = "preflight";
        try {
            requireDisplay(GraphicsEnvironment.isHeadless());
            if (!Files.isRegularFile(jar)) throw new IllegalStateException("Plugin jar is missing");
            // Both JOSM and plugin-direct source acquisition are forced offline.
            System.setProperty("wayheatmaptracer.benchmark.offline", "true");
            stage = "josm-fixture";
            PublicGuiSmokeFixture fixture = startJosmThenCreateFixture(
                    () -> startJosmWithDeadline(
                            () -> MainApplication.main(new String[] {"--offline=ALL"}),
                            report, TimeUnit.MINUTES.toMillis(3)),
                    report.getParent().resolve("public-input"));
            if (!fixture.tilesUsable()) throw new IllegalStateException("Public Hot tile is unusable");
            ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
            PluginPreferences.save(config());
            PluginPreferences.saveTracingSettings(new TracingSettings(
                    TracingSettings.CURRENT_SCHEMA_VERSION, TrackerMode.PROBABILISTIC,
                    RecoverySettings.defaults(7.01), false, AlignmentSourceMode.MANAGED_TILES));
            PluginPreferences.saveGeometryCleanup(GeometryCleanupConfig.disabled());
            Config.getPref().putLong("wayheatmaptracer.cacheBuster", 0L);
            fixture.seedTiles();
            WayHeatmapTracerPlugin plugin = onEventThread(() -> {
                WayHeatmapTracerPlugin loaded = new WayHeatmapTracerPlugin(
                        new PluginInformation(jar.toFile()));
                OsmDataLayer layer = new OsmDataLayer(fixture.dataSet(),
                        "public-analytic-osm", null);
                MainApplication.getLayerManager().addLayer(layer);
                MainApplication.getLayerManager().setActiveLayer(layer);
                fixture.dataSet().setSelected(fixture.selectedWay());
                return loaded;
            });
            AlignWayAction action = onEventThread(PublicGuiSmokeMain::registeredOrdinaryAction);
            PreviewSessionController<?> session = productionSession(plugin);
            List<String> original = wayState(fixture);
            int originalUndo = UndoRedoHandler.getInstance().getUndoCommands().size();
            stage = "preview";
            String selectedId = null;
            CompletableFuture<JDialog> visiblePreview = new CompletableFuture<>();
            AWTEventListener listener = event -> {
                if (event instanceof WindowEvent windowEvent
                        && windowEvent.getID() == WindowEvent.WINDOW_OPENED
                        && windowEvent.getWindow() instanceof JDialog dialog
                        && isPreview(dialog)) visiblePreview.complete(dialog);
            };
            Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.WINDOW_EVENT_MASK);
            try {
                onEventThread(() -> { action.actionPerformed(null); return null; });
                JDialog preview = visiblePreview.get(180, TimeUnit.SECONDS);
                if (!onEventThread(() -> preview.isVisible() && preview.isDisplayable())) {
                    throw new IllegalStateException("Production preview is not visible");
                }
                var ready = session.currentAttempt();
                if (ready == null || ready.state() != AlignmentJob.State.PREVIEW_READY) {
                    throw new IllegalStateException("Production preview attempt is not ready");
                }
                String initialStatus = artifactText(latestArchive(report.getParent()),
                        "attempt-status.json");
                if (!initialStatus.contains("\"routeIndex\":0")) {
                    throw new IllegalStateException("Initial production route diagnostics are missing");
                }
                // The ordinary preview presents two real Engine B alternatives for this
                // fixture. Its default leaves the displaced center unchanged; a user can
                // select the second, ridge-following route in the visible combo box.
                LiveBPreviewService.PreviewChoice selected = onEventThread(() ->
                        selectRidgeRoute(preview));
                selectedId = requireSafeCandidateId(selected.candidate().id());
                if (!onEventThread(() -> preview.isVisible() && preview.isDisplayable())) {
                    throw new IllegalStateException("Selected production preview is no longer visible");
                }
                var selectedAttempt = session.currentAttempt();
                if (selectedAttempt == null || selectedAttempt.sequence() != ready.sequence()
                        || selectedAttempt.state() != AlignmentJob.State.PREVIEW_READY) {
                    throw new IllegalStateException("Selected preview lost its production attempt");
                }
                Format15Archive archive = latestArchive(report.getParent());
                String status = artifactText(archive, "attempt-status.json");
                if (!status.contains("\"status\":\"preview-open\"")
                        && !status.contains("\"status\":\"review-required\"")) {
                    throw new IllegalStateException("Production preview diagnostics are not open");
                }
                if (!status.contains("\"sourceLineage\":\"managed-tiles\"")) {
                    throw new IllegalStateException("Production preview did not use managed source tiles");
                }
                if (!status.contains("\"routeIndex\":1")) {
                    throw new IllegalStateException("Selected route was not published in diagnostics");
                }
                var frozen = FrozenReplayCodec.decode(archive.artifact("frozen-input.bin")
                        .orElseThrow().bytes());
                if (frozen.request().engine() != TrackerMode.PROBABILISTIC) {
                    throw new IllegalStateException("Ordinary action did not run Engine B");
                }
                var plan = FrozenReplayCodec.decodeEditPlan(archive.artifact("frozen-edit-plan.bin")
                        .orElseThrow().bytes());
                if (!plan.routeIdentity().equals(selectedId)) {
                    throw new IllegalStateException("Selected route and frozen preview differ");
                }
                List<GeographicPoint> geometry = plan.finalPreviewWays().get(plan.selectedWayKey());
                PublicGuiSmokeOracle.verifyFinalGeometry(geometry);
                requireUnchanged(fixture, original, originalUndo);
                onEventThread(() -> {
                    preview.dispatchEvent(new WindowEvent(preview, WindowEvent.WINDOW_CLOSING));
                    return null;
                });
            } finally {
                Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
            }
            stage = "cancel";
            long cancelledSequence = onEventThread(() -> {
                action.actionPerformed(null);
                JDialog progress = currentProgressDialog();
                var active = session.currentAttempt();
                if (active == null || active.state().terminal()) {
                    throw new IllegalStateException("Cancellation had no live nonterminal attempt");
                }
                long sequence = active.sequence();
                progress.dispatchEvent(new WindowEvent(progress, WindowEvent.WINDOW_CLOSING));
                var cancelled = session.currentAttempt();
                if (cancelled == null || cancelled.sequence() != sequence
                        || cancelled.state() != AlignmentJob.State.CANCELLED) {
                    throw new IllegalStateException("Production cancellation was not terminal");
                }
                return sequence;
            });
            // The actual job uses one worker. This queued task completes only after
            // its earlier production worker; the EDT barrier drains its publication.
            productionExecutor(session).submit(() -> { }).get(90, TimeUnit.SECONDS);
            onEventThread(() -> { return null; });
            var terminal = session.currentAttempt();
            if (terminal == null || terminal.sequence() != cancelledSequence
                    || terminal.state() != AlignmentJob.State.CANCELLED
                    || hasVisiblePreview()) {
                throw new IllegalStateException("Cancelled attempt published a late preview");
            }
            String cancelStatus = artifactText(latestArchive(report.getParent()),
                    "attempt-status.json");
            if (!cancelStatus.contains("\"status\":\"cancelled\"")) {
                throw new IllegalStateException("Cancelled attempt has no terminal diagnostics");
            }
            requireUnchanged(fixture, original, originalUndo);
            writeSummary(report, "{\"schema\":\"wayheatmaptracer-public-gui-smoke-1\","
                    + "\"status\":\"PASS\",\"source\":\"public-analytic-hot\","
                    + "\"engine\":\"PROBABILISTIC\",\"ordinaryAction\":true,"
                    + "\"visiblePreview\":true,\"selectedRouteIndex\":1,"
                    + "\"selectedCandidateId\":\"" + selectedId + "\",\"geometryOracle\":true,"
                    + "\"cancelledNonterminal\":true,\"noLatePreview\":true,"
                    + "\"datasetUnchanged\":true,\"undoUnchanged\":true}\n");
            System.exit(0);
        } catch (Exception | LinkageError failure) {
            writeSummary(report, "{\"schema\":\"wayheatmaptracer-public-gui-smoke-1\","
                    + "\"status\":\"FAIL\",\"stage\":\"" + safeToken(stage)
                    + "\",\"errorType\":\"" + safeToken(failure.getClass().getSimpleName())
                    + "\"}\n");
            failure.printStackTrace();
            System.exit(1);
        }
    }

    static void requireDisplay(boolean headless) {
        if (headless) throw new IllegalStateException("Real JOSM GUI display is unavailable");
    }

    static PublicGuiSmokeFixture startJosmThenCreateFixture(Runnable startup, Path directory)
            throws Exception {
        startup.run();
        return PublicGuiSmokeFixture.create(directory);
    }

    static void startJosmWithDeadline(Runnable startup, Path report, long timeoutMillis) {
        if (timeoutMillis <= 0) throw new IllegalArgumentException("Startup deadline must be positive");
        startJosmWithDeadline(startup, report, () -> Thread.sleep(timeoutMillis));
    }

    @FunctionalInterface
    interface StartupDeadline {
        void await() throws InterruptedException;
    }

    static void startJosmWithDeadline(Runnable startup, Path report, StartupDeadline deadline) {
        Thread startupThread = Thread.currentThread();
        AtomicBoolean finished = new AtomicBoolean();
        Thread watchdog = new Thread(() -> {
            try {
                deadline.await();
            } catch (InterruptedException completed) {
                return;
            }
            if (!finished.compareAndSet(false, true)) return;
            try {
                writeSummary(report, "{\"schema\":\"wayheatmaptracer-public-gui-smoke-1\","
                        + "\"status\":\"FAIL\",\"stage\":\"josm-startup\","
                        + "\"errorType\":\"StartupTimeout\"}\n");
            } catch (Throwable reportFailure) {
                System.err.println("GUI_SMOKE_STARTUP_REPORT_WRITE_FAILED");
            }
            System.err.println("GUI_SMOKE_STARTUP_TIMEOUT");
            try {
                printSafeStartupFrames("startup", startupThread, 14);
                Thread.getAllStackTraces().keySet().stream()
                        .filter(thread -> thread.getName().startsWith("AWT-EventQueue"))
                        .limit(2)
                        .forEach(thread -> printSafeStartupFrames("event-dispatch", thread, 10));
                int shown = 0;
                for (Window window : Window.getWindows()) {
                    if (window.isShowing() && shown++ < 8) {
                        System.err.println("GUI_SMOKE_WINDOW class=" + safeSymbol(
                                window.getClass().getName()) + " modal="
                                + (window instanceof JDialog dialog && dialog.isModal()));
                    }
                }
            } catch (Throwable diagnosticFailure) {
                System.err.println("GUI_SMOKE_STARTUP_DIAGNOSTIC_FAILED");
            } finally {
                System.exit(1);
            }
        }, "public-gui-startup-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            startup.run();
        } finally {
            finished.set(true);
            watchdog.interrupt();
            try {
                watchdog.join(TimeUnit.SECONDS.toMillis(1));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Startup watchdog shutdown interrupted", interrupted);
            }
            if (watchdog.isAlive()) {
                throw new IllegalStateException("Startup watchdog did not stop");
            }
        }
    }

    private static void printSafeStartupFrames(String role, Thread thread, int maximum) {
        StackTraceElement[] frames = thread.getStackTrace();
        for (int index = 0; index < Math.min(maximum, frames.length); index++) {
            StackTraceElement frame = frames[index];
            System.err.println("GUI_SMOKE_FRAME role=" + role + " index=" + index
                    + " class=" + safeSymbol(frame.getClassName())
                    + " method=" + safeSymbol(frame.getMethodName()));
        }
    }

    private static ManagedHeatmapConfig config() {
        return new ManagedHeatmapConfig("public-key", "public-policy", "public-signature",
                "public-session", "all", "hot", "", ".*", AlignmentMode.PRECISE_SHAPE,
                TrackerMode.PROBABILISTIC, false, false, false, false, false,
                false, false, false, false, false, 7, 4, 3.0,
                InferenceMode.RAW_HIGH_RESOLUTION, 15, 14, 7.01, 1.56,
                IntensitySamplingMode.COLOR_MAPPING, 0L);
    }

    private static AlignWayAction registeredOrdinaryAction() {
        for (var component : MainApplication.getMenu().moreToolsMenu.getMenuComponents()) {
            if (component instanceof JMenuItem item
                    && item.getAction() instanceof AlignWayAction action
                    && action.forcedAlignmentMode() == null) return action;
        }
        throw new IllegalStateException("Registered ordinary Align action is missing");
    }

    private static JDialog currentProgressDialog() {
        for (Window window : Window.getWindows()) {
            if (window instanceof JDialog dialog && dialog.isVisible()
                    && dialog.getTitle().endsWith("alignment preview")) return dialog;
        }
        throw new IllegalStateException("Production progress dialog is unavailable");
    }

    private static boolean isPreview(JDialog dialog) {
        return dialog.getTitle().endsWith("Alignment Preview") && dialog.isVisible();
    }

    private static boolean hasVisiblePreview() throws Exception {
        return onEventThread(() -> {
            for (Window window : Window.getWindows()) {
                if (window instanceof JDialog dialog && isPreview(dialog)) return true;
            }
            return false;
        });
    }

    private static PreviewSessionController<?> productionSession(WayHeatmapTracerPlugin plugin)
            throws Exception {
        Field field = WayHeatmapTracerPlugin.class.getDeclaredField("modernPreviewSession");
        field.setAccessible(true);
        return (PreviewSessionController<?>) field.get(plugin);
    }

    private static ExecutorService productionExecutor(PreviewSessionController<?> session)
            throws Exception {
        Field jobField = PreviewSessionController.class.getDeclaredField("job");
        jobField.setAccessible(true);
        Object job = jobField.get(session);
        Field executorField = AlignmentJob.class.getDeclaredField("executor");
        executorField.setAccessible(true);
        return (ExecutorService) executorField.get(job);
    }

    private static Format15Archive latestArchive(Path directory) throws Exception {
        Path archive = Files.createTempFile(directory, ".public-gui-", ".zip");
        try {
            DiagnosticsRegistry.writeLatest(archive.toFile());
            return Format15ArchiveReader.read(archive);
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    private static LiveBPreviewService.PreviewChoice selectRidgeRoute(JDialog preview) {
        JComboBox<LiveBPreviewService.PreviewChoice> choices = visibleChoiceCombo(
                preview.getContentPane(), LiveBPreviewService.PreviewChoice.class);
        if (!choices.isShowing() || choices.getItemCount() < 2 || choices.getSelectedIndex() != 0) {
            throw new IllegalStateException("Expected visible initial Engine B route choices");
        }
        LiveBPreviewService.PreviewChoice first = choices.getItemAt(0);
        LiveBPreviewService.PreviewChoice ridge = choices.getItemAt(1);
        if (first.owner() != ridge.owner() || first.localRouteIndex() != 0
                || ridge.localRouteIndex() != 1
                || ridge.owner().request().engine() != TrackerMode.PROBABILISTIC
                || first.candidate().id().equals(ridge.candidate().id())) {
            throw new IllegalStateException("Expected two distinct routes from one Engine B run");
        }
        choices.setSelectedIndex(1); // Invokes the real preview listener on the EDT.
        if (choices.getSelectedItem() != ridge || !preview.isVisible()) {
            throw new IllegalStateException("Ridge route was not selected in the visible preview");
        }
        return ridge;
    }

    static <T> JComboBox<T> visibleChoiceCombo(Container root, Class<T> choiceType) {
        List<JComboBox<T>> matches = new ArrayList<>();
        findChoiceCombos(root, choiceType, matches);
        if (matches.size() != 1) {
            throw new IllegalStateException("Expected exactly one displayed route control");
        }
        return matches.get(0);
    }

    private static <T> void findChoiceCombos(Container root, Class<T> choiceType,
            List<JComboBox<T>> matches) {
        for (Component component : root.getComponents()) {
            if (component instanceof JComboBox<?> combo && combo.getItemCount() > 0
                    && choiceType.isInstance(combo.getItemAt(0))) {
                @SuppressWarnings("unchecked") JComboBox<T> typed = (JComboBox<T>) combo;
                matches.add(typed);
            }
            if (component instanceof Container nested) {
                findChoiceCombos(nested, choiceType, matches);
            }
        }
    }

    private static String requireSafeCandidateId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9._-]{1,80}")) {
            throw new IllegalStateException("Selected candidate identity is not safe for receipt");
        }
        return id;
    }

    private static String artifactText(Format15Archive archive, String name) {
        return new String(archive.artifact(name).orElseThrow().bytes(), StandardCharsets.UTF_8);
    }

    private static List<String> wayState(PublicGuiSmokeFixture fixture) {
        List<String> state = new ArrayList<>();
        for (Node node : fixture.selectedWay().getNodes()) {
            state.add(node.getUniqueId() + ":" + Double.toHexString(node.lat()) + ":"
                    + Double.toHexString(node.lon()) + ":" + node.isModified());
        }
        state.add("way:" + fixture.selectedWay().getUniqueId() + ":"
                + fixture.selectedWay().isModified() + ":"
                + fixture.dataSet().allPrimitives().size());
        return List.copyOf(state);
    }

    private static void requireUnchanged(PublicGuiSmokeFixture fixture, List<String> original,
            int originalUndo) {
        if (!wayState(fixture).equals(original)
                || UndoRedoHandler.getInstance().getUndoCommands().size() != originalUndo) {
            throw new IllegalStateException("GUI smoke mutated OSM data or Undo history");
        }
    }

    private static <T> T onEventThread(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        if (SwingUtilities.isEventDispatchThread()) task.run();
        else SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private static String safeToken(String token) {
        return token != null && token.matches("[A-Za-z0-9._-]{1,80}") ? token : "unknown";
    }

    private static String safeSymbol(String symbol) {
        return symbol != null && symbol.matches("[A-Za-z0-9_.$<>-]{1,160}")
                ? symbol : "unknown";
    }

    private static void writeSummary(Path report, String json) throws Exception {
        Files.createDirectories(report.getParent());
        Path temporary = Files.createTempFile(report.getParent(), ".public-gui-", ".tmp");
        try {
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            Files.move(temporary, report, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
