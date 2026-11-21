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
import org.openstreetmap.josm.data.coor.LatLon;
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
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernSingleWayEditPlanAdapter;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.LegacyRefinementWorkflow;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.StageKind;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileRuntime;
import org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.ManagedImageryService;

/** Runs a public analytic fixture through the registered ordinary JOSM GUI action. */
public final class PublicGuiSmokeMain {
    private PublicGuiSmokeMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("summary path and plugin jar required");
        Path report = Path.of(args[0]).toAbsolutePath().normalize();
        Path jar = Path.of(args[1]).toAbsolutePath().normalize();
        String stage = "preflight";
        try {
            TrackerMode engine = guiEngine(System.getProperty("wayheatmaptracer.guiSmoke.engine", "PROBABILISTIC"));
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
            AlignmentMode requestedMode=AlignmentMode.valueOf(System.getProperty("wayheatmaptracer.guiSmoke.alignmentMode","PRECISE_SHAPE"));
            PluginPreferences.save(engine==TrackerMode.LEGACY_V02?stepwiseConfig(requestedMode):config().withTrackerMode(engine).withAlignmentMode(requestedMode));
            PluginPreferences.saveTracingSettings(new TracingSettings(
                    TracingSettings.CURRENT_SCHEMA_VERSION, engine,
                    RecoverySettings.defaults(7.01), false, AlignmentSourceMode.MANAGED_TILES));
            PluginPreferences.saveGeometryCleanup(GeometryCleanupConfig.disabled());
            Config.getPref().putLong("wayheatmaptracer.cacheBuster", 0L);
            fixture.seedTiles();
            if(engine==TrackerMode.LEGACY_V02)fixture.seedRefinementTiles();
            WayHeatmapTracerPlugin plugin = onEventThread(() -> {
                WayHeatmapTracerPlugin loaded = new WayHeatmapTracerPlugin(
                        new PluginInformation(jar.toFile()));
                OsmDataLayer layer = new OsmDataLayer(fixture.dataSet(),
                        "public-analytic-osm", null);
                MainApplication.getLayerManager().addLayer(layer);
                if(engine==TrackerMode.LEGACY_V02)ManagedImageryService.applyOrUpdateManagedLayer();
                MainApplication.getLayerManager().setActiveLayer(layer);
                fixture.dataSet().setSelected(fixture.selectedWay());
                return loaded;
            });
            AlignWayAction action = onEventThread(PublicGuiSmokeMain::registeredOrdinaryAction);
            PreviewSessionController<?> session = productionSession(plugin);
            List<String> original = wayState(fixture);
            int originalUndo = UndoRedoHandler.getInstance().getUndoCommands().size();
            if(engine==TrackerMode.LEGACY_V02){
                stage="stepwise-controls";runStepwiseGui(report,fixture,action,session,original,originalUndo,requestedMode);System.exit(0);return;
            }
            stage = "preview";
            String selectedId = null;
            int selectedRouteIndex = -1;
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
                if (!onEventThread(() -> preview.isVisible() && preview.isDisplayable() && !preview.isModal())) {
                    throw new IllegalStateException("Production preview is not visible and modeless");
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
                // Choose an actual ridge-following production route. A correct
                // single result is valid; never manufacture an alternative.
                if (!(ready.result() instanceof LiveBPreviewService.Computed computed)) {
                    throw new IllegalStateException("Preview has no production computation");
                }
                LiveBPreviewService.PreviewChoice selected = onEventThread(() ->
                        selectRidgeRoute(preview, computed));
                selectedId = requireSafeCandidateId(selected.candidate().id());
                selectedRouteIndex = selected.localRouteIndex();
                var selectedPlan = new ModernSingleWayEditPlanAdapter()
                        .assess(selected.owner(), selectedRouteIndex).plan().orElseThrow();
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
                if (!status.contains("\"routeIndex\":" + selectedRouteIndex)) {
                    throw new IllegalStateException("Selected route was not published in diagnostics");
                }
                var frozen = FrozenReplayCodec.decode(archive.artifact("frozen-input.bin")
                        .orElseThrow().bytes());
                if (frozen.request().engine() != engine) {
                    throw new IllegalStateException("Ordinary action did not run the requested engine");
                }
                var plan = FrozenReplayCodec.decodeEditPlan(archive.artifact("frozen-edit-plan.bin")
                        .orElseThrow().bytes());
                if (!plan.routeIdentity().equals(selectedId)
                        || !plan.canonicalHash().equals(selectedPlan.canonicalHash())) {
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
            stage = "settings-save";
            verifySilentSettingsSave();
            requireUnchanged(fixture, original, originalUndo);
            writeSummary(report, "{\"schema\":\"wayheatmaptracer-public-gui-smoke-1\","
                    + "\"status\":\"PASS\",\"source\":\"public-analytic-hot\","
                    + "\"engine\":\"" + engine.name() + "\",\"ordinaryAction\":true,"
                    + "\"visiblePreview\":true,\"selectedRouteIndex\":" + selectedRouteIndex + ","
                    + "\"selectedCandidateId\":\"" + selectedId + "\",\"geometryOracle\":true,"
                    + "\"cancelledNonterminal\":true,\"noLatePreview\":true,"
                    + "\"datasetUnchanged\":true,\"undoUnchanged\":true,\"silentSettingsSave\":true}\n");
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

    /** Drives the actual Legacy modeless dialog and its existing refinement controls. */
    private static void runStepwiseGui(Path report,PublicGuiSmokeFixture fixture,AlignWayAction action,
            PreviewSessionController<?> session,List<String> original,int originalUndo,AlignmentMode requestedMode) throws Exception {
        var opened=new CompletableFuture<JDialog>();
        AWTEventListener openedListener=event->{if(event instanceof WindowEvent e&&e.getID()==WindowEvent.WINDOW_OPENED
            &&e.getWindow() instanceof JDialog dialog&&"legacy-review".equals(dialog.getName()))opened.complete(dialog);};
        Toolkit.getDefaultToolkit().addAWTEventListener(openedListener,AWTEvent.WINDOW_EVENT_MASK);
        JDialog preview;
        try{onEventThread(()->{action.actionPerformed(null);return null;});preview=opened.get(180,TimeUnit.SECONDS);}
        finally{Toolkit.getDefaultToolkit().removeAWTEventListener(openedListener);}
        var owner=onEventThread(()->{
            if(preview.isModal()||!preview.isVisible())throw new IllegalStateException("Legacy review is not modeless");
            Object value=preview.getRootPane().getClientProperty("wayheatmaptracer.refinementOwner");
            if(!(value instanceof LegacyRefinementWorkflow workflow))throw new IllegalStateException("Actual refinement owner missing");
            if(workflow.actualNativeCalls()!=0)throw new IllegalStateException("Legacy root performed hidden native work");
            return workflow;
        });
        List<Node> originalNodes=onEventThread(()->List.copyOf(fixture.selectedWay().getNodes()));
        var rootReview=onEventThread(()->originalReview(owner));
        onEventThread(()->{checkedMode(requestedMode,rootReview.originalConfig().heatmap().alignmentMode());return null;});
        String initialChoice=onEventThread(owner::selectedChoiceIdentity);
        String rootChoice=onEventThread(()->{
            var choices=requiredNamed(preview.getContentPane(),"candidate",JComboBox.class);
            if(choices.getItemCount()<2)throw new IllegalStateException("Public fixture supplied no alternative Legacy parent");
            int chosen=(choices.getSelectedIndex()+1)%choices.getItemCount();choices.setSelectedIndex(chosen);
            return checkedNoninitialChoice(initialChoice,owner.selectedChoiceIdentity(),choices.getItemCount());
        });
        var rootCandidate=onEventThread(rootReview::selectedCandidate);
        var rootFindings=onEventThread(()->displayedFindings(preview));
        var rootGeometry=onEventThread(owner::selectedGeometry);
        requireUnchanged(fixture,original,originalUndo);
        boolean bFirst="B_A".equals(System.getProperty("wayheatmaptracer.guiSmoke.refinementOrder","A_B"));
        var first=bFirst?StageKind.REFINE_B:StageKind.REFINE_A;
        var second=bFirst?StageKind.REFINE_A:StageKind.REFINE_B;
        checkDialogLayout(preview);
        keyboardAndAwaitStage(preview,owner,bFirst?"refineB":"refineA",first);
        checkDialogLayout(preview);
        onEventThread(()->{
            checkedMode(requestedMode,owner.currentNative().orElseThrow().operation().orElseThrow().ticket().mode());
            requireExactLegacyParent(owner,rootReview,rootChoice,rootCandidate,rootGeometry);
            var choices=requiredNamed(preview.getContentPane(),"candidate",JComboBox.class);
            if(choices.getItemCount()>1)choices.setSelectedIndex(choices.getItemCount()-1);
            return null;
        });
        var firstGeometry=onEventThread(owner::selectedGeometry);
        String firstChoice=onEventThread(owner::selectedChoiceIdentity);
        var firstFindings=onEventThread(()->displayedFindings(preview));
        String firstOutcome=onEventThread(()->owner.currentNative().orElseThrow().operation().orElseThrow().outcome().name());
        int firstCalls=owner.actualNativeCalls();var firstSource=ManagedTileRuntime.initializedCoordinator().stats();
        onEventThread(()->{
            if(firstCalls<=0)throw new IllegalStateException("No actual native calls in first refinement");
            var map=MainApplication.getMap().mapView;var center=map.getCenter();double scale=map.getScale();
            map.zoomTo(center.add(4*scale,-3*scale),scale*.9);
            requireOverlayGeometry(action,firstGeometry);
            requiredNamed(preview.getContentPane(),"undo",javax.swing.JButton.class).doClick();
            if(!owner.selectedChoiceIdentity().equals(rootChoice)||!owner.selectedGeometry().equals(rootGeometry)||!displayedFindings(preview).equals(rootFindings))throw new IllegalStateException("Undo did not restore exact Legacy selection/geometry");
            requiredNamed(preview.getContentPane(),"redo",javax.swing.JButton.class).doClick();
            if(!owner.selectedChoiceIdentity().equals(firstChoice)||!owner.selectedGeometry().equals(firstGeometry)
                ||!displayedFindings(preview).equals(firstFindings))throw new IllegalStateException("Redo did not restore exact candidate/findings");
            requireOverlayGeometry(action,firstGeometry);
            return null;
        });
        if(owner.actualNativeCalls()!=firstCalls||!ManagedTileRuntime.initializedCoordinator().stats().equals(firstSource))throw new IllegalStateException("Cached navigation performed extra native/source work");
        var firstStage=onEventThread(owner::currentStage);
        requireUnchanged(fixture,original,originalUndo);
        clickAndAwaitStage(preview,owner,bFirst?"refineA":"refineB",second);
        var plan=onEventThread(()->{
            var stage=owner.currentStage();
            checkedMode(requestedMode,owner.currentNative().orElseThrow().operation().orElseThrow().ticket().mode());
            if(!stage.parent().orElseThrow().equals(firstStage.id())||!stage.parentChoice().orElseThrow().value().equals(firstChoice))throw new IllegalStateException("Chained refinement did not pin selected parent");
            if(owner.actualNativeCalls()<=firstCalls)throw new IllegalStateException("Opposite engine was not executed");
            var actual=owner.currentNative().orElseThrow();
            if(actual.operation().orElseThrow().outcome()==org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.Outcome.RESOURCE_LIMIT
                &&!requiredNamed(preview.getContentPane(),"quality",javax.swing.JTextArea.class).getText().contains("Resource limit reached"))throw new IllegalStateException("Resource status missing from live review");
            requireOverlayGeometry(action,owner.selectedGeometry());return actual.plan();
        });
        String secondOutcome=onEventThread(()->owner.currentNative().orElseThrow().operation().orElseThrow().outcome().name());
        String appliedMode=onEventThread(()->checkedMode(requestedMode,owner.currentNative().orElseThrow().operation().orElseThrow().ticket().mode()).name());
        var finalFindings=onEventThread(()->displayedFindings(preview));
        var finalGeometry=onEventThread(owner::selectedGeometry);String finalChoice=onEventThread(owner::selectedChoiceIdentity);
        int finalCalls=owner.actualNativeCalls();var finalSource=ManagedTileRuntime.initializedCoordinator().stats();
        onEventThread(()->{
            requiredNamed(preview.getContentPane(),"undo",javax.swing.JButton.class).doClick();
            if(!owner.selectedChoiceIdentity().equals(firstChoice)||!owner.selectedGeometry().equals(firstGeometry)||!displayedFindings(preview).equals(firstFindings))throw new IllegalStateException("Chained Undo lost selected parent/findings");
            requiredNamed(preview.getContentPane(),"redo",javax.swing.JButton.class).doClick();
            if(!owner.selectedChoiceIdentity().equals(finalChoice)||!owner.selectedGeometry().equals(finalGeometry)||!displayedFindings(preview).equals(finalFindings))throw new IllegalStateException("Chained Redo lost selected child/findings");
            requireOverlayGeometry(action,finalGeometry);return null;
        });
        if(finalCalls!=owner.actualNativeCalls()||!finalSource.equals(ManagedTileRuntime.initializedCoordinator().stats()))throw new IllegalStateException("Chained cached navigation performed work");
        PublicGuiSmokeOracle.verifyFinalGeometry(plan.finalPreviewWays().get(plan.selectedWayKey()));
        requireUnchanged(fixture,original,originalUndo);
        if(onEventThread(()->requiredNamed(preview.getContentPane(),"legacy-confirm",javax.swing.JButton.class).isVisible()))confirmStepwise(preview);
        onEventThread(()->{
            var apply=requiredNamed(preview.getContentPane(),"legacy-apply",javax.swing.JButton.class);
            if(!apply.isEnabled())throw new IllegalStateException("Actual final Apply remains disabled");apply.doClick();return null;
        });
        onEventThread(()->{
            if(preview.isDisplayable())throw new IllegalStateException("Applied review window remained open");
            if(UndoRedoHandler.getInstance().getUndoCommands().size()!=originalUndo+1)throw new IllegalStateException("Final Apply did not add exactly one command");
            var applied=checkedAppliedGeometry(requestedMode,originalNodes,fixture.selectedWay().getNodes());
            if(!applied.equals(plan.finalPreviewWays().get(plan.selectedWayKey())))throw new IllegalStateException("Applied geometry differs from stored final preview");
            UndoRedoHandler.getInstance().undo();requireUnchanged(fixture,original,originalUndo);
            UndoRedoHandler.getInstance().redo();
            var redo=checkedAppliedGeometry(requestedMode,originalNodes,fixture.selectedWay().getNodes());
            if(!redo.equals(applied))throw new IllegalStateException("Command Redo changed stored final coordinates");
            UndoRedoHandler.getInstance().undo();requireUnchanged(fixture,original,originalUndo);return null;
        });
        // A second real window proves Cancel/Close leave no late result or mutation.
        var cancelledWindow=new CompletableFuture<JDialog>();
        AWTEventListener cancelListener=event->{if(event instanceof WindowEvent e&&e.getID()==WindowEvent.WINDOW_OPENED
            &&e.getWindow() instanceof JDialog dialog&&"legacy-review".equals(dialog.getName()))cancelledWindow.complete(dialog);};
        Toolkit.getDefaultToolkit().addAWTEventListener(cancelListener,AWTEvent.WINDOW_EVENT_MASK);
        try {
            onEventThread(()->{fixture.dataSet().setSelected(fixture.selectedWay());action.actionPerformed(null);return null;});
            var cancelled=cancelledWindow.get(180,TimeUnit.SECONDS);
            var cancelOwner=onEventThread(()->{
                Object value=cancelled.getRootPane().getClientProperty("wayheatmaptracer.refinementOwner");
                if(!(value instanceof LegacyRefinementWorkflow actual))throw new IllegalStateException("Cancellation owner missing");
                checkedMode(requestedMode,originalReview(actual).originalConfig().heatmap().alignmentMode());return actual;
            });
            var cancelledEntry=new CompletableFuture<LegacyRefinementWorkflow.NativeEntry>();
            cancelOwner.firstNativeEntry().whenComplete((entry,failure)->{
                try {
                    if(failure!=null)throw new IllegalStateException("Native entry failed",failure);
                    if(!SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Native entry notification left EDT");
                    var cancel=requiredNamed(cancelled.getContentPane(),"cancel",javax.swing.JButton.class);
                    requireNativeCancellation(true,cancel.isEnabled(),cancelOwner.busy(),cancelOwner.ownsNativeEntry(entry),entry.entered(),entry.settlement().toCompletableFuture().isDone(),cancelOwner.ownedBytes());
                    checkedMode(requestedMode,entry.ticket().mode());cancel.doClick();
                    if(!entry.cancellationRequested())throw new IllegalStateException("Actual Cancel did not target native ticket");
                    cancelledEntry.complete(entry);
                }catch(Throwable refused){cancelledEntry.completeExceptionally(refused);}
            });
            onEventThread(()->{
                var refine=requiredNamed(cancelled.getContentPane(),"refineA",javax.swing.JButton.class);
                if(!refine.isEnabled())throw new IllegalStateException("Cancellation Refine A is disabled");refine.doClick();return null;
            });
            var entry=cancelledEntry.get(180,TimeUnit.SECONDS);
            entry.settlement().toCompletableFuture().get(90,TimeUnit.SECONDS);
            session.workGate().settlement().toCompletableFuture().get(90,TimeUnit.SECONDS);
            onEventThread(()->{
                if(!entry.workerReturned()||!entry.cancellationObserved()||cancelOwner.busy()||cancelOwner.nativeSelected())throw new IllegalStateException("Native cancellation failed to settle/discard its result");
                if(!cancelOwner.currentStage().id().equals(entry.ticket().parent()))throw new IllegalStateException("Cancelled native result published a child");
                requireOverlayGeometry(action,cancelOwner.selectedGeometry());requireUnchanged(fixture,original,originalUndo);
                cancelled.dispatchEvent(new WindowEvent(cancelled,WindowEvent.WINDOW_CLOSING));
                if(cancelOwner.ownedBytes()!=0||cancelled.isDisplayable()||hasVisiblePreview())throw new IllegalStateException("Cancelled review retained work/window");return null;
            });
        } finally{Toolkit.getDefaultToolkit().removeAWTEventListener(cancelListener);}
        writeSummary(report,"{\"schema\":\"wayheatmaptracer-public-gui-smoke-1\",\"status\":\"PASS\",\"engine\":\"LEGACY_V02\",\"stepwise\":true,\"order\":\""+(bFirst?"B_A":"A_B")+"\",\"mode\":\""+appliedMode+"\",\"firstOutcome\":\""+firstOutcome+"\",\"secondOutcome\":\""+secondOutcome+"\",\"actualNativeCalls\":"+owner.actualNativeCalls()+",\"modeless\":true,\"cachedUndoRedo\":true,\"panZoomOverlay\":true,\"oneCommandApply\":true,\"commandUndoRedo\":true,\"cancelSettled\":true,\"nativeCancellationEntered\":true,\"nativeCancellationObserved\":true,\"nativePhysicalReturned\":true,\"noninitialLegacyParent\":true,\"allStageFindingsRestored\":true}\n");
    }

    private static org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.LegacyReviewOwner originalReview(LegacyRefinementWorkflow owner) throws Exception {
        Field field=LegacyRefinementWorkflow.class.getDeclaredField("review");field.setAccessible(true);
        return (org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.LegacyReviewOwner)field.get(owner);
    }
    private static List<String> displayedFindings(JDialog dialog) {
        return List.of(requiredNamed(dialog.getContentPane(),"quality",javax.swing.JTextArea.class).getText(),
            requiredNamed(dialog.getContentPane(),"legacy-coverage",javax.swing.JLabel.class).getText(),
            requiredNamed(dialog.getContentPane(),"legacy-cleanup",javax.swing.JLabel.class).getText(),
            requiredNamed(dialog.getContentPane(),"legacy-detail",javax.swing.JLabel.class).getText());
    }
    private static void requireExactLegacyParent(LegacyRefinementWorkflow owner,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.LegacyReviewOwner review,String choice,
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate actual,List<org.openstreetmap.josm.data.coor.EastNorth> geometry) throws Exception {
        Field field=LegacyRefinementWorkflow.class.getDeclaredField("session");field.setAccessible(true);
        var history=(org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementSession)field.get(owner);
        var root=history.history().get(0);var pinned=root.candidates().stream().filter(c->c.id().value().equals(choice)).findFirst().orElseThrow();
        var palette=review.originalResult().detectorAttempts().stream().filter(a->a.candidateIds().contains(actual.id())).map(a->a.sourcePalette()).distinct().toList();
        var stored=pinned.payload().nativeGeometry().stream().filter(c->c.id().equals(actual.id())).findFirst().orElseThrow();
        var expected=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ParentReferenceBinder().bind(history.authority(),root.id(),pinned);
        var receipt=owner.currentNative().orElseThrow().operation().orElseThrow();
        if(!root.selected().value().equals(choice)||!receipt.ticket().choice().value().equals(choice)||!owner.currentStage().parentChoice().orElseThrow().value().equals(choice)
            ||!stored.finalPreviewPoints().equals(geometry)||!pinned.nativeCandidateId().equals(actual.id())||palette.size()!=1||!pinned.sourcePalette().equals(palette.get(0))
            ||!receipt.parentIdentity().equals(expected.canonicalIdentity()))throw new IllegalStateException("Refinement did not bind exact selected Legacy geometry/palette/identity");
    }

    private static void clickAndAwaitStage(JDialog dialog,LegacyRefinementWorkflow owner,String button,StageKind expected) throws Exception {
        var ready=new CompletableFuture<Void>();
        Runnable check=()->{
            if(owner.busy())return;
            if(owner.nativeSelected()&&owner.currentStage().kind()==expected)ready.complete(null);
            else ready.completeExceptionally(new IllegalStateException("Requested live refinement did not publish a child"));
        };
        java.beans.PropertyChangeListener listener=event->SwingUtilities.invokeLater(check);
        onEventThread(()->{
            requiredNamed(dialog.getContentPane(),"stage",javax.swing.JLabel.class).addPropertyChangeListener(listener);
            requiredNamed(dialog.getContentPane(),"refineA",javax.swing.JButton.class).addPropertyChangeListener(listener);
            var target=requiredNamed(dialog.getContentPane(),button,javax.swing.JButton.class);
            if(!target.isEnabled())throw new IllegalStateException("Requested refinement button is disabled");target.doClick();SwingUtilities.invokeLater(check);return null;
        });
        try{ready.get(180,TimeUnit.SECONDS);}finally{onEventThread(()->{requiredNamed(dialog.getContentPane(),"stage",javax.swing.JLabel.class).removePropertyChangeListener(listener);requiredNamed(dialog.getContentPane(),"refineA",javax.swing.JButton.class).removePropertyChangeListener(listener);return null;});}
    }
    private static void confirmStepwise(JDialog dialog) throws Exception {
        var accepted=new AtomicBoolean();
        AWTEventListener listener=event->{if(event instanceof WindowEvent e&&e.getID()==WindowEvent.WINDOW_OPENED&&e.getWindow() instanceof JDialog modal&&modal.isModal()){
            var panes=new ArrayList<javax.swing.JOptionPane>();collectOptionPanes(modal.getContentPane(),panes);
            if(panes.size()==1&&panes.get(0).getOptionType()==javax.swing.JOptionPane.YES_NO_OPTION){accepted.set(true);SwingUtilities.invokeLater(()->panes.get(0).setValue(javax.swing.JOptionPane.YES_OPTION));}
        }};
        Toolkit.getDefaultToolkit().addAWTEventListener(listener,AWTEvent.WINDOW_EVENT_MASK);
        try{onEventThread(()->{requiredNamed(dialog.getContentPane(),"legacy-confirm",javax.swing.JButton.class).doClick();return null;});if(!accepted.get())throw new IllegalStateException("Real review confirmation dialog was not accepted");}
        finally{Toolkit.getDefaultToolkit().removeAWTEventListener(listener);}
    }
    private static void collectOptionPanes(Container root,List<javax.swing.JOptionPane> found){for(Component child:root.getComponents()){if(child instanceof javax.swing.JOptionPane pane)found.add(pane);else if(child instanceof Container nested)collectOptionPanes(nested,found);}}
    static <T extends Component> T requiredNamed(Container root,String name,Class<T> type) {
        var found=new ArrayList<T>();collectNamed(root,name,type,found);
        if(found.size()!=1)throw new IllegalStateException("Expected one named "+name+" control");return found.get(0);
    }
    private static <T extends Component> void collectNamed(Container root,String name,Class<T> type,List<T> found){for(Component child:root.getComponents()){if(name.equals(child.getName())){if(!type.isInstance(child))throw new IllegalStateException("Named control has wrong type");found.add(type.cast(child));}if(child instanceof Container nested)collectNamed(nested,name,type,found);}}
    private static void requireOverlayGeometry(AlignWayAction action,List<org.openstreetmap.josm.data.coor.EastNorth> expected) throws Exception {
        Field overlayField=AlignWayAction.class.getDeclaredField("overlay");overlayField.setAccessible(true);Object overlay=overlayField.get(action);
        Field resultField=overlay.getClass().getDeclaredField("result");resultField.setAccessible(true);
        var result=(org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentResult)resultField.get(overlay);
        if(result==null||!result.previewPolyline().equals(expected))throw new IllegalStateException("Actual overlay differs from stored selected geometry");
    }

    static void requireKeyboardReady(boolean enabled, boolean focused) {
        if (!enabled || !focused) throw new IllegalStateException("Keyboard target is disabled or unfocused");
    }
    static void requireOperationChild(boolean operation, boolean child) {
        if (!operation || !child) throw new IllegalStateException("Keyboard activation did not publish requested operation and child");
    }
    static void requireFooterBounds(java.awt.Rectangle visible, java.awt.Rectangle control) {
        if (control == null || control.isEmpty() || !visible.contains(control))
            throw new IllegalStateException("Required footer control is missing or clipped");
    }
    static void requireFooterHierarchy(Container root, Component control, boolean showing) {
        requireFooterHierarchy(root, control, showing, component -> component instanceof Window);
    }
    static void requireFooterHierarchy(Container root, Component control, boolean showing,
            java.util.function.Predicate<Component> windowBoundary) {
        if (!showing || control == null) throw new IllegalStateException("Footer control is not showing");
        var rectangle = new java.awt.Rectangle(0, 0, control.getWidth(), control.getHeight());
        if (rectangle.isEmpty()) throw new IllegalStateException("Footer control is empty");
        boolean reachedRoot = false;
        for (Component current = control; current != null; current = current.getParent()) {
            if (!current.isVisible()) throw new IllegalStateException("Footer ancestor is hidden");
            if (current == root) reachedRoot = true;
            if (current != control) {
                var inAncestor = SwingUtilities.convertRectangle(control, rectangle, current);
                requireFooterBounds(new java.awt.Rectangle(0, 0, current.getWidth(), current.getHeight()), inAncestor);
            }
            if (windowBoundary.test(current)) break;
        }
        if (!reachedRoot) throw new IllegalStateException("Footer control is outside the dialog");
    }
    private static void keyboardAndAwaitStage(JDialog dialog, LegacyRefinementWorkflow owner,
            String button, StageKind expected) throws Exception {
        var focused = new CompletableFuture<Void>();
        var keyDelivered = new CompletableFuture<Void>();
        var ready = new CompletableFuture<Void>();
        var target = onEventThread(() -> requiredNamed(dialog.getContentPane(), button, javax.swing.JButton.class));
        java.awt.event.FocusListener focusListener = new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent event) { focused.complete(null); }
        };
        java.awt.event.KeyListener keyListener = new java.awt.event.KeyAdapter() {
            @Override public void keyPressed(java.awt.event.KeyEvent event) {
                if (event.getKeyCode() == java.awt.event.KeyEvent.VK_SPACE && target.isFocusOwner()) keyDelivered.complete(null);
            }
        };
        Runnable check = () -> {
            if (owner.busy()) return;
            if (owner.nativeSelected() && owner.currentStage().kind() == expected) ready.complete(null);
        };
        java.beans.PropertyChangeListener listener = event -> SwingUtilities.invokeLater(check);
        onEventThread(() -> {
            if (!target.isEnabled()) throw new IllegalStateException("Keyboard refinement is disabled");
            target.addFocusListener(focusListener); target.addKeyListener(keyListener);
            requiredNamed(dialog.getContentPane(), "stage", javax.swing.JLabel.class).addPropertyChangeListener(listener);
            requiredNamed(dialog.getContentPane(), "refineA", javax.swing.JButton.class).addPropertyChangeListener(listener);
            target.scrollRectToVisible(new java.awt.Rectangle(0, 0, target.getWidth(), target.getHeight()));
            if (target.isFocusOwner()) focused.complete(null); else target.requestFocusInWindow();
            return null;
        });
        try {
            focused.get(30, TimeUnit.SECONDS);
            onEventThread(() -> { requireKeyboardReady(target.isEnabled(), target.isFocusOwner()); return null; });
            var robot = new java.awt.Robot();
            robot.keyPress(java.awt.event.KeyEvent.VK_SPACE);
            robot.keyRelease(java.awt.event.KeyEvent.VK_SPACE);
            keyDelivered.get(30, TimeUnit.SECONDS);
            ready.get(180, TimeUnit.SECONDS);
            onEventThread(() -> {
                var nativeReview = owner.currentNative();
                requireOperationChild(nativeReview.isPresent() && nativeReview.get().operation().isPresent()
                    && nativeReview.get().operation().get().ticket().kind() == expected,
                    owner.nativeSelected() && owner.currentStage().kind() == expected);
                return null;
            });
        } finally {
            onEventThread(() -> { target.removeFocusListener(focusListener); target.removeKeyListener(keyListener);
                requiredNamed(dialog.getContentPane(), "stage", javax.swing.JLabel.class).removePropertyChangeListener(listener);
                requiredNamed(dialog.getContentPane(), "refineA", javax.swing.JButton.class).removePropertyChangeListener(listener); return null; });
        }
    }
    private static void checkDialogLayout(JDialog dialog) throws Exception {
        onEventThread(() -> {
            var size = dialog.getSize();
            try {
                verifyDialogLayout(dialog);
                dialog.setSize(800, 600); dialog.validate();
                verifyDialogLayout(dialog);
            } finally { dialog.setSize(size); dialog.validate(); }
            return null;
        });
    }
    private static void verifyDialogLayout(JDialog dialog) {
        var root = dialog.getContentPane();
        var apply = requiredNamed(root, "legacy-apply", javax.swing.JButton.class);
        var confirm = requiredNamed(root, "legacy-confirm", javax.swing.JButton.class);
        var reviewScroll = requiredNamed(root, "legacy-review-scroll", javax.swing.JScrollPane.class);
        var quality = requiredNamed(root, "quality", javax.swing.JTextArea.class);
        var undo = requiredNamed(root, "undo", javax.swing.JButton.class);
        if (!quality.getLineWrap() || !quality.getWrapStyleWord() || !(quality.getParent() instanceof javax.swing.JViewport))
            throw new IllegalStateException("Reasons must wrap in a scroll viewport");
        if (!undo.isEnabled() && (undo.getToolTipText() == null || undo.getToolTipText().isBlank()
                || undo.getAccessibleContext().getAccessibleDescription() == null))
            throw new IllegalStateException("Unavailable control needs an accessible explanation");
        if (!(reviewScroll.getViewport().getView() instanceof Container)) throw new IllegalStateException("Review scroll missing content");
        for (String name : List.of("refineA", "refineB", "smooth", "redo"))
            requiredNamed(root, name, javax.swing.JButton.class);
        var viewport = (javax.swing.JViewport) quality.getParent();
        var reasonScroll = (javax.swing.JScrollPane) SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane.class, quality);
        if (reasonScroll == null) throw new IllegalStateException("Reasons are not scrollable");
        String oldText = quality.getText(); int oldCaret = quality.getCaretPosition();
        java.awt.Point oldPosition = viewport.getViewPosition();
        try {
            quality.setText("Long interval reason with factual explanation and retained parent section. ".repeat(40));
            quality.setCaretPosition(quality.getDocument().getLength());
            reasonScroll.validate();
            var bar = reasonScroll.getVerticalScrollBar();
            if (bar.getMaximum() <= bar.getVisibleAmount()) throw new IllegalStateException("Long reasons do not scroll");
            bar.setValue(bar.getMaximum());
            if (bar.getValue() <= bar.getMinimum()) throw new IllegalStateException("Long reason end cannot be reached");
        } finally { quality.setText(oldText); quality.setCaretPosition(Math.min(oldCaret, quality.getDocument().getLength())); viewport.setViewPosition(oldPosition); }
        Container footer = apply.getParent();
        javax.swing.JButton cancel = null;
        for (Component component : footer.getComponents()) {
            if (component instanceof javax.swing.JButton b && org.openstreetmap.josm.tools.I18n.tr("Cancel").equals(b.getText())) {
                if (cancel != null) throw new IllegalStateException("Ambiguous footer Cancel");
                cancel = b;
            }
            if (component.isVisible()) requireFooterHierarchy(root, component, component.isShowing());
        }
        requireFooterHierarchy(root, cancel, cancel != null && cancel.isShowing());
        requireFooterHierarchy(root, apply, apply.isShowing());
        if (confirm.isVisible()) requireFooterHierarchy(root, confirm, confirm.isShowing());
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
        return config(true);
    }

    private static ManagedHeatmapConfig config(boolean authenticated) {
        return config(authenticated,false);
    }
    private static ManagedHeatmapConfig config(boolean authenticated,boolean aggregate) {
        return new ManagedHeatmapConfig(authenticated ? "public-key" : "", authenticated ? "public-policy" : "",
                authenticated ? "public-signature" : "", authenticated ? "public-session" : "",
                "all", "hot", "", ".*", AlignmentMode.PRECISE_SHAPE,
                TrackerMode.PROBABILISTIC, false, false, false, aggregate, false,
                false, false, false, false, false, 7, 4, 3.0,
                InferenceMode.RAW_HIGH_RESOLUTION, 15, 14, 7.01, 1.56,
                IntensitySamplingMode.COLOR_MAPPING, 0L);
    }

    static void requireNativeCancellation(boolean refineEnabled,boolean cancelEnabled,boolean busy,boolean owns,boolean nativeEntered,boolean settled,long ownedBytes) {
        if(!refineEnabled||!cancelEnabled||!busy||!owns||!nativeEntered||settled||ownedBytes<=0)throw new IllegalStateException("Cancellation requires factual owned nonterminal native work");
    }

    static AlignmentMode checkedMode(AlignmentMode requested,AlignmentMode actual){
        if(requested!=actual)throw new IllegalStateException("Requested alignment mode differs from actual root/stage");return actual;
    }
    static List<GeographicPoint> checkedAppliedGeometry(AlignmentMode mode,List<Node> original,List<Node> actual) {
        if(mode==AlignmentMode.MOVE_EXISTING_NODES) {
            if(original.size()!=actual.size())throw new IllegalStateException("Move changed node occurrence count");
            for(int i=0;i<original.size();i++)if(original.get(i)!=actual.get(i))throw new IllegalStateException("Move replaced/reordered original node occurrence");
        }
        return actual.stream().map(n->new GeographicPoint(n.lat(),n.lon())).toList();
    }
    static String checkedNoninitialChoice(String initial,String selected,int available){
        if(available<2||initial.equals(selected))throw new IllegalStateException("A distinct noninitial Legacy choice is required");return selected;
    }
    static ManagedHeatmapConfig stepwiseConfig(AlignmentMode mode){return config(true,true).withTrackerMode(TrackerMode.LEGACY_V02).withAlignmentMode(mode);}

    static TrackerMode guiEngine(String name) {
        TrackerMode engine = TrackerMode.valueOf(name);
        if (engine != TrackerMode.PROBABILISTIC && engine != TrackerMode.LEGACY_THEN_B
                && engine != TrackerMode.CORRIDOR_AWARE && engine != TrackerMode.LEGACY_V02) {
            throw new IllegalArgumentException("GUI smoke requires Legacy stepwise, Corridor A, independent B, or sequential Hybrid");
        }
        return engine;
    }

    /** Accepts the real settings dialog and fails if successful saving opens another modal. */
    private static void verifySilentSettingsSave() throws Exception {
        var settings = new CompletableFuture<JDialog>();
        var completed = new CompletableFuture<Void>();
        var unexpectedModal = new AtomicBoolean();
        var previous = PluginPreferences.load();
        var previousTracing = PluginPreferences.loadTracingSettings();
        AWTEventListener listener = event -> {
            if (!(event instanceof WindowEvent opened) || opened.getID() != WindowEvent.WINDOW_OPENED
                    || !(opened.getWindow() instanceof JDialog dialog) || !dialog.isModal()) return;
            if (dialog.getTitle().equals("WayHeatmapTracer Settings") && !settings.isDone()) settings.complete(dialog);
            else {
                unexpectedModal.set(true);
                SwingUtilities.invokeLater(dialog::dispose);
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.WINDOW_EVENT_MASK);
        try {
            // No access values means a successful offline save, with no network health probe.
            PluginPreferences.save(config(false).withTrackerMode(previousTracing.engine()));
            SwingUtilities.invokeLater(() -> {
                try {
                    for (var component : MainApplication.getMenu().moreToolsMenu.getMenuComponents()) {
                        if (component instanceof JMenuItem item && item.getAction() instanceof
                                org.openstreetmap.josm.plugins.wayheatmaptracer.actions.HeatmapLayerSettingsAction action) {
                            action.actionPerformed(null);
                            completed.complete(null);
                            return;
                        }
                    }
                    throw new IllegalStateException("Registered Settings action is missing");
                } catch (Exception failure) { completed.completeExceptionally(failure); }
            });
            JDialog dialog = settings.get(30, TimeUnit.SECONDS);
            onEventThread(() -> {
                var verbose = requiredCheckbox(dialog.getContentPane(), "Verbose logging");
                if (verbose.isSelected()) throw new IllegalStateException("Settings fixture must start quiet");
                verbose.doClick();
                var accept = dialog.getRootPane().getDefaultButton();
                if (accept == null || !accept.isShowing() || !accept.isEnabled()) {
                    throw new IllegalStateException("Visible Settings accept button is unavailable");
                }
                accept.doClick();
                return null;
            });
            completed.get(30, TimeUnit.SECONDS);
            if (unexpectedModal.get() || onEventThread(dialog::isVisible)) {
                throw new IllegalStateException("Successful Settings save opened an unexpected modal");
            }
            if (!PluginPreferences.load().verbose()) {
                throw new IllegalStateException("Settings OK did not persist the changed control");
            }
        } finally {
            Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
            PluginPreferences.save(previous);
            PluginPreferences.saveTracingSettings(previousTracing);
        }
    }

    static javax.swing.JCheckBox requiredCheckbox(Container root, String label) {
        var found = new ArrayList<javax.swing.JCheckBox>();
        findCheckboxes(root, label, found);
        if (found.size() != 1) throw new IllegalStateException("Expected one Settings checkbox");
        return found.get(0);
    }

    private static void findCheckboxes(Container root, String label, List<javax.swing.JCheckBox> found) {
        for (var component : root.getComponents()) {
            if (component instanceof javax.swing.JCheckBox box && label.equals(box.getText())) found.add(box);
            if (component instanceof Container nested) findCheckboxes(nested, label, found);
        }
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
        return (dialog.getTitle().endsWith("Alignment Preview")||"legacy-review".equals(dialog.getName())) && dialog.isVisible();
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

    private static LiveBPreviewService.PreviewChoice selectRidgeRoute(JDialog preview,
            LiveBPreviewService.Computed computed) {
        if (computed.partitioned()) {
            throw new IllegalStateException("Junctionless analytic fixture unexpectedly has interval routes");
        }
        List<LiveBPreviewService.PreviewChoice> production = new LiveBPreviewService().adaptChoices(computed,
                point -> ProjectionRegistry.getProjection().latlon2eastNorth(
                        new LatLon(point.latitudeDegrees(), point.longitudeDegrees())));
        if (production.size() == 1) {
            LiveBPreviewService.PreviewChoice sole = soleChoiceWithoutControl(
                    preview.getContentPane(), production, LiveBPreviewService.PreviewChoice.class);
            if (sole.owner() != computed || oraclePassingRouteIndex(production) != 0) {
                throw new IllegalStateException("Sole route is not the production analytic ridge");
            }
            return sole;
        }
        JComboBox<LiveBPreviewService.PreviewChoice> choices = visibleChoiceCombo(
                preview.getContentPane(), LiveBPreviewService.PreviewChoice.class);
        if (!choices.isShowing() || choices.getItemCount() < 1 || choices.getSelectedIndex() != 0) {
            throw new IllegalStateException("Expected visible initial production route choices");
        }
        if (choices.getItemCount() != production.size()) {
            throw new IllegalStateException("Displayed routes differ from the production run");
        }
        List<LiveBPreviewService.PreviewChoice> actual = new ArrayList<>();
        for (int index = 0; index < choices.getItemCount(); index++) {
            var choice = choices.getItemAt(index);
            if (choice.owner() != production.get(index).owner()
                    || !choice.candidate().id().equals(production.get(index).candidate().id())) {
                throw new IllegalStateException("Displayed route ownership differs from production");
            }
            actual.add(choice);
        }
        int index = oraclePassingRouteIndex(actual);
        LiveBPreviewService.PreviewChoice ridge = choices.getItemAt(index);
        choices.setSelectedIndex(index); // Invokes the real listener when the route changes.
        if (choices.getSelectedItem() != ridge || !preview.isVisible()) {
            throw new IllegalStateException("Ridge route was not selected in the visible preview");
        }
        return ridge;
    }

    static <T> T soleChoiceWithoutControl(Container root, List<T> production, Class<T> choiceType) {
        List<JComboBox<T>> controls = new ArrayList<>();
        findChoiceCombos(root, choiceType, controls);
        if (production.size() != 1 || !controls.isEmpty()) {
            throw new IllegalStateException("Expected one production route with no attached chooser");
        }
        return production.get(0);
    }

    static int oraclePassingRouteIndex(List<LiveBPreviewService.PreviewChoice> choices) {
        if (choices.isEmpty()) throw new IllegalStateException("Production preview has no routes");
        var owner = choices.get(0).owner();
        var engine = guiEngine(owner.request().engine().name());
        if (engine == TrackerMode.LEGACY_THEN_B && (owner.legacyB() == null
                || owner.legacyB().result().batch().usage().transitions() <= 0)) {
            throw new IllegalStateException("Hybrid GUI smoke requires actual B execution");
        }
        var ids = new java.util.HashSet<String>();
        int selected = -1;
        for (int i = 0; i < choices.size(); i++) {
            var choice = choices.get(i);
            if (choice.owner() != owner || choice.localRouteIndex() != i
                    || !ids.add(choice.candidate().id())) {
                throw new IllegalStateException("Expected distinct owned routes from one production run");
            }
            if (engine == TrackerMode.LEGACY_THEN_B && owner.pipeline().routes().get(i).hypothesis()
                    .diagnostics().getOrDefault("hasBRefinement", 0.0) != 1.0) continue;
            var assessment = new ModernSingleWayEditPlanAdapter().assess(owner, i);
            if (assessment.plan().isEmpty()) continue;
            var plan = assessment.plan().orElseThrow();
            try {
                PublicGuiSmokeOracle.verifyFinalGeometry(plan.finalPreviewWays().get(plan.selectedWayKey()));
                selected = i;
            } catch (IllegalStateException rejectedGeometry) {
                // Other actual alternatives may disagree with the analytic ridge.
            }
        }
        if (selected < 0) throw new IllegalStateException("No production route follows the analytic ridge");
        return selected;
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
        if (id == null || !id.matches("[A-Za-z0-9._/-]{1,256}")) {
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
