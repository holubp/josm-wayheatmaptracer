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
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.PipelineAdmissionObserver;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.OperationTicket;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.StageKind;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileRuntime;
import org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.ManagedImageryService;

/** Runs a public analytic fixture through the registered ordinary JOSM GUI action. */
public final class PublicGuiSmokeMain {
    private static String firstRefinementFailureEvidence;
    private static String laterRefinementFailureEvidence;
    private static String shortcutFailureEvidence;
    private static boolean shortcutRegistryBefore;
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
            boolean intervalGui="INTERVAL".equals(System.getProperty("wayheatmaptracer.guiSmoke.refinementOrder"));
            boolean visibleGui="VISIBLE_B_A".equals(System.getProperty("wayheatmaptracer.guiSmoke.refinementOrder"));
            if(intervalGui)onEventThread(()->{configureIntervalFixture(fixture);return null;});
            AlignmentMode requestedMode=AlignmentMode.valueOf(System.getProperty("wayheatmaptracer.guiSmoke.alignmentMode","PRECISE_SHAPE"));
            AlignmentMode savedMode = engine == TrackerMode.LEGACY_THEN_B
                ? AlignmentMode.MOVE_EXISTING_NODES
                : engine == TrackerMode.CORRIDOR_AWARE ? AlignmentMode.PRECISE_SHAPE : requestedMode;
            // Supported starting engines survive; historical Hybrid remains a migration row.
            PluginPreferences.save((visibleGui?config(false,false).withAlignmentMode(savedMode):stepwiseConfig(savedMode)).withTrackerMode(engine));
            PluginPreferences.saveTracingSettings(new TracingSettings(
                    TracingSettings.CURRENT_SCHEMA_VERSION, engine,
                    RecoverySettings.defaults(7.01), false, visibleGui?AlignmentSourceMode.VISIBLE_LAYER:AlignmentSourceMode.MANAGED_TILES));
            PluginPreferences.saveGeometryCleanup(GeometryCleanupConfig.disabled());
            Config.getPref().putLong("wayheatmaptracer.cacheBuster", 0L);
            fixture.seedTiles();
            fixture.seedRefinementTiles();
            var expectedStartingEngine=engine==TrackerMode.CORRIDOR_AWARE||engine==TrackerMode.PROBABILISTIC?engine:TrackerMode.LEGACY_V02;
            if (PluginPreferences.load().trackerMode() != expectedStartingEngine
                    || PluginPreferences.loadTracingSettings().engine() != expectedStartingEngine) {
                throw new IllegalStateException("Saved starting algorithm was not preserved");
            }
            WayHeatmapTracerPlugin plugin = onEventThread(() -> {
                WayHeatmapTracerPlugin loaded = new WayHeatmapTracerPlugin(
                        new PluginInformation(jar.toFile()));
                OsmDataLayer layer = new OsmDataLayer(fixture.dataSet(),
                        "public-analytic-osm", null);
                MainApplication.getLayerManager().addLayer(layer);
                if(visibleGui) {
                    for(var old:List.copyOf(MainApplication.getLayerManager().getLayers()))if(old instanceof org.openstreetmap.josm.gui.layer.ImageryLayer)MainApplication.getLayerManager().removeLayer(old);
                    MainApplication.getLayerManager().addLayer(new AnalyticVisibleLayer());
                } else addOfflineManagedLayer(PluginPreferences.load(), MainApplication.getLayerManager()::addLayer);
                MainApplication.getLayerManager().setActiveLayer(layer);
                fixture.dataSet().setSelected(fixture.selectedWay());
                return loaded;
            });
            if (engine != TrackerMode.LEGACY_V02) onEventThread(() -> {
                for (Window window : Window.getWindows()) if (window.isShowing()) requireNoObsoleteMethodUi(window);
                return null;
            });
            AlignWayAction action = onEventThread(() -> engine == TrackerMode.LEGACY_V02
                ||engine==TrackerMode.CORRIDOR_AWARE&&requestedMode==AlignmentMode.PRECISE_SHAPE
                ? registeredOrdinaryAction() : registeredEntryAction(engine));
            PreviewSessionController<?> session = productionSession(plugin);
            List<String> original = wayState(fixture);
            int originalUndo = UndoRedoHandler.getInstance().getUndoCommands().size();
            if(PluginPreferences.loadTracingSettings().engine()==TrackerMode.LEGACY_V02){
                stage="stepwise-controls";runStepwiseGui(report,fixture,action,session,original,originalUndo,requestedMode,engine);System.exit(0);return;
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
                JDialog preview;
                try{preview=visiblePreview.get(180,TimeUnit.SECONDS);}
                catch(java.util.concurrent.TimeoutException timeout){
                    var attempt=session.currentAttempt();System.err.println("GUI_SMOKE_PREVIEW_TIMEOUT state="+(attempt==null?"unavailable":attempt.state())+" cause="+(attempt==null?"unavailable":safeToken(attempt.failureReason())));
                    for(Window window:Window.getWindows())if(window.isShowing()&&window instanceof JDialog dialog)System.err.println("GUI_SMOKE_PREVIEW_WINDOW title="+safeToken(dialog.getTitle())+" modal="+dialog.isModal()+" name="+safeToken(String.valueOf(dialog.getName())));
                    for(var thread:Thread.getAllStackTraces().keySet())if(thread.getName().startsWith("AWT-EventQueue")||thread.getName().contains("alignment"))printSafeStartupFrames("preview-timeout",thread,12);
                    throw timeout;
                }
                if (!onEventThread(() -> preview.isVisible() && preview.isDisplayable() && !preview.isModal())) {
                    throw new IllegalStateException("Production preview is not visible and modeless");
                }
                onEventThread(()->{
                    for(var name:List.of("refineA","refineB","smooth"))if(!requiredNamed(preview.getContentPane(),name,javax.swing.JButton.class).isShowing())throw new IllegalStateException("Native result lacks optional stage controls");
                    return null;
                });
                var ready = session.currentAttempt();
                if (ready == null || ready.state() != AlignmentJob.State.PREVIEW_READY) {
                    throw new IllegalStateException("Production preview attempt is not ready");
                }
                if(intervalGui){runNativeIntervalGui(report,fixture,preview,session,original,originalUndo,action);System.exit(0);return;}
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
                if (!status.contains("\"sourceLineage\":\""+(visibleGui?"visible-layer":"managed-tiles")+"\"")) {
                    throw new IllegalStateException("Production preview did not use its requested source lineage");
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
                if(engine==TrackerMode.CORRIDOR_AWARE||engine==TrackerMode.PROBABILISTIC) {
                    exerciseOriginalNativeApply(preview,fixture,geometry,original,originalUndo);
                    exerciseOriginalNativeStaleInspection(action,session,fixture,original,originalUndo);
                    if(engine==TrackerMode.CORRIDOR_AWARE&&requestedMode==AlignmentMode.PRECISE_SHAPE
                            ||engine==TrackerMode.PROBABILISTIC&&requestedMode==AlignmentMode.MOVE_EXISTING_NODES)
                        exerciseSmoothThenRefineGui(report,action,session,fixture,original,originalUndo,engine==TrackerMode.PROBABILISTIC?StageKind.REFINE_A:StageKind.REFINE_B);
                } else onEventThread(() -> {preview.dispatchEvent(new WindowEvent(preview, WindowEvent.WINDOW_CLOSING));return null;});
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
                    + "\"" + (intervalEvidence==null?"":",\"nativeInterval\":"+intervalEvidence)
                    + (firstRefinementFailureEvidence == null ? "" : ",\"firstRefinement\":" + firstRefinementFailureEvidence)
                    + (laterRefinementFailureEvidence == null ? "" : ",\"laterRefinement\":" + laterRefinementFailureEvidence)
                    + (shortcutFailureEvidence == null ? "" : ",\"shortcut\":" + shortcutFailureEvidence)
                    + "}\n");
            failure.printStackTrace();
            System.exit(1);
        }
    }

    /** Drives the actual Legacy modeless dialog and its existing refinement controls. */
    private static void configureIntervalFixture(PublicGuiSmokeFixture fixture) {
        var nodes=fixture.selectedWay().getNodes();
        for(int i=3;i<=7;i++) {
            double east=-40+8*i;var point=PublicGuiSmokeOracle.point(east,4*Math.max(0,Math.cos(Math.PI*east/64)));
            nodes.get(i).setCoor(new org.openstreetmap.josm.data.coor.LatLon(point.latitudeDegrees(),point.longitudeDegrees()));
        }
        nodes.get(3).put("note","public fixed island boundary");nodes.get(7).put("note","public fixed island boundary");
        var point=PublicGuiSmokeOracle.point(0,34);var other=new Node(new org.openstreetmap.josm.data.coor.LatLon(point.latitudeDegrees(),point.longitudeDegrees()));other.setOsmId(9001,1);fixture.dataSet().addPrimitive(other);
        var branch=new org.openstreetmap.josm.data.osm.Way();branch.setOsmId(9002,1);branch.setNodes(List.of(nodes.get(5),other));branch.put("highway","path");fixture.dataSet().addPrimitive(branch);
        fixture.dataSet().allPrimitives().forEach(value->value.setModified(false));
    }
    private static volatile String intervalEvidence;
    private static void runNativeIntervalGui(Path report,PublicGuiSmokeFixture fixture,JDialog preview,PreviewSessionController<?> session,List<String> original,int originalUndo,AlignWayAction action) throws Exception {
        var sparseOwner=onEventThread(()->(LegacyRefinementWorkflow)preview.getRootPane().getClientProperty("wayheatmaptracer.refinementOwner"));
        var sparseChoice=onEventThread(sparseOwner::selectedChoiceIdentity);
        clickAndAwaitStage(report,preview,sparseOwner,"refineA",StageKind.ROOT_NATIVE);
        var sparseExport=Format15ArchiveReader.read(exportThroughActualMenu());
        var sparseStatus=parseReviewStatus(new String(sparseExport.artifact("review-state.json").orElseThrow().bytes(),StandardCharsets.UTF_8));
        if(!"PROBABILISTIC".equals(sparseStatus.getString("rootEngine"))||!"AVAILABLE".equals(sparseStatus.getString("latestAttemptAvailability"))
                ||sparseExport.artifact("root/stage-0-choice-0.json").isEmpty()||sparseExport.artifact("native-root-diagnostics.zip").isEmpty())
            throw new IllegalStateException("Actual menu export lacks retained native root and failed-operation evidence");
        var originalContext=Format15ArchiveReader.read(new java.io.ByteArrayInputStream(sparseExport.artifact("native-root-diagnostics.zip").orElseThrow().bytes()));
        var sourceChoices=parseReviewStatus(new String(originalContext.artifact("source-choices.json").orElseThrow().bytes(),StandardCharsets.UTF_8));
        var sourceOwner=onEventThread(()->(LiveBPreviewService.Computed)session.currentAttempt().result());
        for(var actual:sourceOwner.productionRuns()) {
            var recorded=sourceChoices.getJsonArray("attempts").stream().map(value->value.asJsonObject())
                .filter(value->value.containsKey("work")&&value.getString("tier").equals(actual.options().sourceTier())).findFirst().orElseThrow();
            var work=recorded.getJsonObject("work");
            if(!work.getString("scope").equals("INTERVAL_SOURCE_BATCH")||!work.getString("engine").equals(actual.request().engine().name())
                    ||work.getJsonArray("runs").size()!=actual.intervalBatch().runs().size())throw new IllegalStateException("Original native source work scope differs");
            for(int i=0;i<actual.intervalBatch().runs().size();i++) {
                var run=actual.intervalBatch().runs().get(i);var row=work.getJsonArray("runs").getJsonObject(i);
                if(!row.getString("requestHash").equals(org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.FrozenReplayCodec.canonicalRequestHash(run.request()))
                        ||row.getJsonNumber("evaluatedTransitions").longValueExact()!=run.result().inference().evaluatedTransitions()
                        ||row.getJsonNumber("finalRoutes").intValueExact()!=run.routes().size())throw new IllegalStateException("Original native source work does not match its actual run");
            }
        }
        onEventThread(()->{
            var diagnostic=sparseOwner.latestAttemptDiagnostics().orElseThrow();
            if(diagnostic.publication()!=org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementAttemptDiagnostics.Publication.NO_CHILD
                    ||diagnostic.binding().ticket().kind()!=StageKind.REFINE_A||sparseOwner.actualNativeCalls()!=0
                    ||!sparseChoice.equals(sparseOwner.selectedChoiceIdentity())||!preview.isShowing())
                throw new IllegalStateException("Sparse native interval refusal did not retain its exact visible parent");
            preview.dispatchEvent(new WindowEvent(preview,WindowEvent.WINDOW_CLOSING));return null;
        });
        requireUnchanged(fixture,original,originalUndo);
        // Same original piecewise-linear geometry and protected nodes, with one midpoint
        // per edge. The8m exterior intervals now have three original occurrences,
        // satisfying the production planner's unchanged >2-occurrence requirement.
        onEventThread(()->{
            var previous=fixture.selectedWay().getNodes();var dense=new ArrayList<Node>();
            for(int i=0;i<previous.size()-1;i++) {
                dense.add(previous.get(i));var a=previous.get(i).getEastNorth();var b=previous.get(i+1).getEastNorth();
                var coordinate=org.openstreetmap.josm.data.projection.ProjectionRegistry.getProjection().eastNorth2latlon(new org.openstreetmap.josm.data.coor.EastNorth((a.east()+b.east())/2,(a.north()+b.north())/2));
                var midpoint=new Node(coordinate);midpoint.setOsmId(9100+i,1);fixture.dataSet().addPrimitive(midpoint);dense.add(midpoint);
            }
            dense.add(previous.get(previous.size()-1));fixture.selectedWay().setNodes(dense);
            fixture.dataSet().allPrimitives().forEach(value->value.setModified(false));fixture.dataSet().setSelected(fixture.selectedWay());return null;
        });
        var opened=new CompletableFuture<JDialog>();
        AWTEventListener listener=event->{if(event instanceof WindowEvent e&&e.getID()==WindowEvent.WINDOW_OPENED&&e.getWindow() instanceof JDialog dialog&&isPreview(dialog))opened.complete(dialog);};
        Toolkit.getDefaultToolkit().addAWTEventListener(listener,AWTEvent.WINDOW_EVENT_MASK);
        try{onEventThread(()->{action.actionPerformed(null);return null;});runNativeIntervalPositiveGui(report,fixture,opened.get(180,TimeUnit.SECONDS),session,wayState(fixture),originalUndo);}
        finally{Toolkit.getDefaultToolkit().removeAWTEventListener(listener);}
    }
    private static void runNativeIntervalPositiveGui(Path report,PublicGuiSmokeFixture fixture,JDialog preview,PreviewSessionController<?> session,List<String> original,int originalUndo) throws Exception {
        var owner=onEventThread(()->{
            var attempt=session.currentAttempt();if(attempt==null||!(attempt.result() instanceof LiveBPreviewService.Computed computed)||!computed.partitioned()
                    ||computed.intervalBatch().runs().stream().noneMatch(run->run.usage().transitions()>0))throw new IllegalStateException("Actual native interval inference unavailable");
            intervalEvidence="{\"capturedPartition\":"+(computed.captured().intervalPartition()!=null)+",\"producedSources\":"+computed.productionRuns().size()+",\"intervals\":"+computed.intervalBatch().runs().size()+",\"sourceEngine\":\""+computed.request().engine().name()+"\",\"originalStatuses\":\""+computed.intervalBatch().runs().stream().map(run->run.result().inference().status().name()).toList()+"\",\"traceRanges\":\""+computed.intervalBatch().runs().stream().map(run->run.interval().traceRange()).toList()+"\",\"fixedRanges\":\""+computed.intervalBatch().partition().fixedIslands().stream().map(island->island.range()).toList()+"\",\"editableRanges\":\""+computed.captured().network().closure().editableWayOccurrences().get(computed.request().selectedWayKey())+"\"}";
            var value=preview.getRootPane().getClientProperty("wayheatmaptracer.refinementOwner");
            if(!(value instanceof LegacyRefinementWorkflow workflow)||preview.isModal())throw new IllegalStateException("Native interval modeless refinement owner unavailable");
            for(String name:List.of("refineA","refineB","smooth"))if(!requiredNamed(preview.getContentPane(),name,javax.swing.JButton.class).isShowing())throw new IllegalStateException("Native interval optional control unavailable");
            return workflow;
        });
        clickAndAwaitStage(report,preview,owner,"refineA",StageKind.REFINE_A);
        var firstParent=onEventThread(()->owner.currentStage().parentChoice().orElseThrow());int calls=owner.actualNativeCalls();
        onEventThread(()->{requiredNamed(preview.getContentPane(),"undo",javax.swing.JButton.class).doClick();return null;});
        awaitIntervalSelection(preview,owner,()->{
            var sources=requiredNamed(preview.getContentPane(),"refinement-root-source",JComboBox.class);
            if(!sources.isShowing()||sources.getItemCount()<2)throw new IllegalStateException("Native interval fixture needs actual source alternatives");sources.setSelectedIndex(1);
        });
        var rootChoice=onEventThread(()->{
            if(!owner.atRoot()||owner.actualNativeCalls()!=calls||owner.selectedChoiceIdentity().equals(firstParent.value()))throw new IllegalStateException("Root source selection did not retain its exact independent candidate: "+fixedFailureBoundary(owner.computationEvidence()));
            var id=owner.selectedChoiceIdentity();requiredNamed(preview.getContentPane(),"redo",javax.swing.JButton.class).doClick();
            if(!owner.currentStage().parentChoice().orElseThrow().equals(firstParent))throw new IllegalStateException("Root selection rewrote saved descendant lineage");
            requiredNamed(preview.getContentPane(),"undo",javax.swing.JButton.class).doClick();
            if(!id.equals(owner.selectedChoiceIdentity())||requiredNamed(preview.getContentPane(),"refinement-root-source",JComboBox.class).getSelectedIndex()!=1)throw new IllegalStateException("Cached native interval root controls were not restored");return id;
        });
        requireUnchanged(fixture,original,originalUndo);
        onEventThread(()->{if(owner.actualNativeCalls()!=calls)throw new IllegalStateException("Cached interval navigation performed inference");preview.dispatchEvent(new WindowEvent(preview,WindowEvent.WINDOW_CLOSING));return null;});
        requireUnchanged(fixture,original,originalUndo);
        writeSummary(report,"{\"schema\":\"wayheatmaptracer-public-gui-smoke-1\",\"status\":\"PASS\",\"engine\":\"PROBABILISTIC\",\"nativeInterval\":true,\"sparseNoChildRetained\":true,\"modeless\":true,\"cachedUndoRedo\":true,\"rootSourceChoice\":\""+rootChoice+"\",\"actualNativeCalls\":"+calls+"}\n");
    }
    private static void awaitIntervalSelection(JDialog dialog,LegacyRefinementWorkflow owner,Runnable choose) throws Exception {
        var ready=new CompletableFuture<Void>();var observed=new AtomicBoolean();
        Runnable check=()->{if(!observed.get())return;if(owner.busy()){requireComputingView(dialog);return;}if(owner.ownsPublication())ready.complete(null);else ready.completeExceptionally(new IllegalStateException("Interval root selection lost its owner"));};
        java.beans.PropertyChangeListener listener=event->SwingUtilities.invokeLater(check);
        var control=onEventThread(()->requiredNamed(dialog.getContentPane(),"refineA",javax.swing.JButton.class));
        try{onEventThread(()->{control.addPropertyChangeListener(listener);observed.set(true);choose.run();SwingUtilities.invokeLater(check);return null;});ready.get(180,TimeUnit.SECONDS);}
        finally{onEventThread(()->{control.removePropertyChangeListener(listener);return null;});}
    }
    private static void runStepwiseGui(Path report,PublicGuiSmokeFixture fixture,AlignWayAction action,
            PreviewSessionController<?> session,List<String> original,int originalUndo,AlignmentMode requestedMode,
            TrackerMode savedEngine) throws Exception {
        ManagedHeatmapConfig savedSettings = PluginPreferences.load();
        TracingSettings savedTracing = PluginPreferences.loadTracingSettings();
        var opened=new CompletableFuture<JDialog>();
        var blocked=new CompletableFuture<JDialog>();
        AWTEventListener openedListener=event->{if(event instanceof WindowEvent e&&e.getID()==WindowEvent.WINDOW_OPENED
            &&e.getWindow() instanceof JDialog dialog) {
                if ("legacy-review".equals(dialog.getName())) opened.complete(dialog);
                else if (savedEngine != TrackerMode.LEGACY_V02 && dialog.isModal()) blocked.complete(dialog);
            }};
        Toolkit.getDefaultToolkit().addAWTEventListener(openedListener,AWTEvent.WINDOW_EVENT_MASK);
        JDialog preview;
        try{
            if (savedEngine == TrackerMode.LEGACY_V02) onEventThread(()->{action.actionPerformed(null);return null;});
            else dispatchEntryShortcut(savedEngine, action);
            if (savedEngine == TrackerMode.LEGACY_V02) preview=opened.get(180,TimeUnit.SECONDS);
            else {
                try {
                    Object first = CompletableFuture.anyOf(opened, blocked).get(180, TimeUnit.SECONDS);
                    if (!opened.isDone() || first != opened.getNow(null)) {
                        shortcutFailureEvidence = boundedShortcutEvidence(savedEngine, action, true);
                        throw new IllegalStateException("Shortcut produced a modal before Legacy review");
                    }
                    preview = opened.getNow(null);
                } catch (java.util.concurrent.TimeoutException timeout) {
                    shortcutFailureEvidence = boundedShortcutEvidence(savedEngine, action, true);
                    throw timeout;
                }
            }
        }
        finally{Toolkit.getDefaultToolkit().removeAWTEventListener(openedListener);}
        var owner=onEventThread(()->{
            if(preview.isModal()||!preview.isVisible())throw new IllegalStateException("Legacy review is not modeless");
            Object value=preview.getRootPane().getClientProperty("wayheatmaptracer.refinementOwner");
            if(!(value instanceof LegacyRefinementWorkflow workflow))throw new IllegalStateException("Actual refinement owner missing");
            if(workflow.actualNativeCalls()!=0)throw new IllegalStateException("Legacy root performed hidden native work");
            return workflow;
        });
        List<Node> originalNodes=onEventThread(()->List.copyOf(fixture.selectedWay().getNodes()));
        List<GeographicPoint> originalCoordinates=onEventThread(()->originalNodes.stream().map(n->new GeographicPoint(n.lat(),n.lon())).toList());
        var rootReview=onEventThread(()->originalReview(owner));
        onEventThread(()->{checkedMode(requestedMode,rootReview.originalConfig().heatmap().alignmentMode());return null;});
        if (savedEngine != TrackerMode.LEGACY_V02) {
            AlignmentMode savedMode = savedEngine == TrackerMode.LEGACY_THEN_B
                ? AlignmentMode.MOVE_EXISTING_NODES : AlignmentMode.PRECISE_SHAPE;
            checkedMode(savedMode, PluginPreferences.load().alignmentMode());
            if (!savedSettings.equals(PluginPreferences.load()) || !savedTracing.equals(PluginPreferences.loadTracingSettings()))
                throw new IllegalStateException("Shortcut changed saved alignment or source settings");
            requireLegacyMigrationRoot(() -> rootReview.originalConfig().heatmap().trackerMode());
        }
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
        boolean bFirst=System.getProperty("wayheatmaptracer.guiSmoke.refinementOrder","A_B").endsWith("B_A");
        var first=bFirst?StageKind.REFINE_B:StageKind.REFINE_A;
        var second=bFirst?StageKind.REFINE_A:StageKind.REFINE_B;
        checkDialogLayout(preview);
        keyboardAndAwaitStage(report,preview,owner,bFirst?"refineB":"refineA",first);
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
        clickAndAwaitStage(report,preview,owner,bFirst?"refineA":"refineB",second);
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
        var appliedExpected=onEventThread(()->snapshotExport(owner,requestedMode,originalCoordinates,rootChoice,rootGeometry,firstStage,firstChoice,firstGeometry));
        var appliedClosed=observeClose(preview);
        onEventThread(()->{
            var apply=requiredNamed(preview.getContentPane(),"legacy-apply",javax.swing.JButton.class);
            if(!apply.isEnabled())throw new IllegalStateException("Actual final Apply remains disabled");apply.doClick();return null;
        });
        appliedClosed.get(30,TimeUnit.SECONDS);
        onEventThread(()->null);
        String appliedArchive=checkClosedExport(report.resolveSibling("stepwise-applied.zip"),owner,preview,action,appliedExpected,"applied",plan.finalPreviewWays().get(plan.selectedWayKey()));
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
        String cancelledArchive;
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
            var cancelledExpected=onEventThread(()->{
                if(!entry.workerReturned()||!entry.cancellationObserved()||cancelOwner.busy()||cancelOwner.nativeSelected())throw new IllegalStateException("Native cancellation failed to settle/discard its result");
                if(!cancelOwner.currentStage().id().equals(entry.ticket().parent()))throw new IllegalStateException("Cancelled native result published a child");
                requireOverlayGeometry(action,cancelOwner.selectedGeometry());requireUnchanged(fixture,original,originalUndo);
                return snapshotExport(cancelOwner,requestedMode,originalCoordinates,cancelOwner.selectedChoiceIdentity(),cancelOwner.selectedGeometry(),null,null,null);
            });
            var cancelClosed=observeClose(cancelled);
            onEventThread(()->{cancelled.dispatchEvent(new WindowEvent(cancelled,WindowEvent.WINDOW_CLOSING));return null;});
            cancelClosed.get(30,TimeUnit.SECONDS);onEventThread(()->null);
            cancelledArchive=checkClosedExport(report.resolveSibling("stepwise-cancelled.zip"),cancelOwner,cancelled,action,cancelledExpected,"cancelled",null);
        } finally{Toolkit.getDefaultToolkit().removeAWTEventListener(cancelListener);}
        if (savedEngine != TrackerMode.LEGACY_V02) {
            verifySilentSettingsSave();
            requireUnchanged(fixture, original, originalUndo);
        }
        if(savedEngine==TrackerMode.LEGACY_V02&&requestedMode==AlignmentMode.PRECISE_SHAPE&&!bFirst)
            exerciseSmoothThenRefineGui(report,action,session,fixture,original,originalUndo,StageKind.REFINE_B);
        String entryEvidence = savedEngine == TrackerMode.LEGACY_V02 ? "" :
            ",\"keyboardEntry\":\"" + entryActionKey(savedEngine) + "\",\"settingsAcceptedWithoutNotice\":true";
        writeSummary(report,"{\"schema\":\"wayheatmaptracer-public-gui-smoke-1\",\"status\":\"PASS\",\"engine\":\"LEGACY_V02\",\"savedEngine\":\""+savedEngine.name()+"\""+entryEvidence+",\"stepwise\":true,\"order\":\""+(bFirst?"B_A":"A_B")+"\",\"mode\":\""+appliedMode+"\",\"firstOutcome\":\""+firstOutcome+"\",\"secondOutcome\":\""+secondOutcome+"\",\"actualNativeCalls\":"+owner.actualNativeCalls()+",\"modeless\":true,\"cachedUndoRedo\":true,\"panZoomOverlay\":true,\"oneCommandApply\":true,\"commandUndoRedo\":true,\"cancelSettled\":true,\"nativeCancellationEntered\":true,\"nativeCancellationObserved\":true,\"nativePhysicalReturned\":true,\"noninitialLegacyParent\":true,\"allStageFindingsRestored\":true,\"archiveBuild\":\"runtime-stepwise-refinement-v1\",\"appliedArchive\":\"stepwise-applied.zip\",\"cancelledArchive\":\"stepwise-cancelled.zip\",\"appliedArchiveSha256\":\""+appliedArchive+"\",\"cancelledArchiveSha256\":\""+cancelledArchive+"\"}\n");
    }

    private record ExpectedExport(AlignmentMode mode,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.StageId current,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.CandidateId choice, String candidateIdentity,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.StageId root,
            String rootChoice, List<org.openstreetmap.josm.data.coor.EastNorth> rootGeometry,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.StageId parent,
            String parentChoice, List<org.openstreetmap.josm.data.coor.EastNorth> parentGeometry,
            List<org.openstreetmap.josm.data.coor.EastNorth> currentGeometry, List<GeographicPoint> original,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementAttemptDiagnostics.Availability availability,
            java.util.Optional<org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementOperationReceipt> receipt,
            java.util.Optional<org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementAttemptDiagnostics> diagnostic) { }
    private static ExpectedExport snapshotExport(LegacyRefinementWorkflow owner,AlignmentMode mode,List<GeographicPoint> original,
            String rootChoice,List<org.openstreetmap.josm.data.coor.EastNorth> rootGeometry,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.Stage parent,
            String parentChoice,List<org.openstreetmap.josm.data.coor.EastNorth> parentGeometry) {
        var stage=owner.currentStage();
        var root=parent==null?stage.id():parent.parent().orElseThrow();
        String identity=stage.candidates().stream().filter(c->c.id().equals(stage.selected())).findFirst().orElseThrow().immutableIdentity();
        return new ExpectedExport(mode,stage.id(),stage.selected(),identity,root,rootChoice,List.copyOf(rootGeometry),
            parent==null?null:parent.id(),parentChoice,parentGeometry==null?null:List.copyOf(parentGeometry),
            List.copyOf(owner.selectedGeometry()),List.copyOf(original),owner.attemptDiagnosticsAvailability(),
            owner.latestAttemptDiagnostics().flatMap(d->d.pipeline()),owner.latestAttemptDiagnostics());
    }
    static void requireLatestAttemptBinding(
            java.util.Optional<org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementAttemptDiagnostics> expected,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementAttemptDiagnostics.Availability expectedAvailability,
            java.util.Optional<org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.RefinementLineageCodec.LatestPublication> actual,
            java.util.Optional<org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementOperationReceipt> receipt,
            String actualAvailability, org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.RefinementLineageCodec.CandidateKey selection) {
        if(!expectedAvailability.name().equals(actualAvailability)
            ||!actual.map(value->value.diagnostic()).equals(expected)
            ||!receipt.equals(expected.flatMap(value->value.pipeline()))
            ||actual.isPresent()&&selection!=null&&!actual.get().resultingSelection().equals(selection)
            ||expected.isPresent()&&expectedAvailability!=org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementAttemptDiagnostics.Availability.AVAILABLE)
            throw new IllegalStateException("Latest operation binding differs");
    }
    private static jakarta.json.JsonObject parseReviewStatus(String json) {
        try {
            var keys=new java.util.ArrayDeque<java.util.Set<String>>();
            try(var parser=jakarta.json.Json.createParser(new java.io.StringReader(json))) {
                while(parser.hasNext()) {
                    var event=parser.next();
                    if(event==jakarta.json.stream.JsonParser.Event.START_OBJECT)keys.push(new java.util.HashSet<>());
                    else if(event==jakarta.json.stream.JsonParser.Event.KEY_NAME && (keys.isEmpty()||!keys.peek().add(parser.getString())))throw new IllegalStateException("Duplicate status field");
                    else if(event==jakarta.json.stream.JsonParser.Event.END_OBJECT)keys.pop();
                }
            }
            try(var reader=jakarta.json.Json.createReader(new java.io.StringReader(json))) { return reader.readObject(); }
        } catch(RuntimeException malformed) {throw new IllegalStateException("Malformed review status",malformed);}
    }
    static void requireReviewStatus(String json,String state,String mode,String availability,String stage,String choice,String geometry,String hash,boolean applied) {
        try {
            var object=parseReviewStatus(json);
            if(!state.equals(object.getString("state"))||!mode.equals(object.getString("mode"))
                ||!availability.equals(object.getString("latestAttemptAvailability"))||!stage.equals(object.getString("currentStage"))
                ||!choice.equals(object.getString("currentChoice"))||!geometry.equals(object.getString("currentGeometry"))
                ||!hash.equals(object.getString("currentGeometrySha256")))throw new IllegalStateException("Review status binding differs");
            if(!object.containsKey("applied"))throw new IllegalStateException("Applied status missing");
            if(applied) {
                var key=object.getJsonObject("applied");
                if(key==null||!stage.equals(key.getString("stage"))||!choice.equals(key.getString("choice")))throw new IllegalStateException("Applied status identity differs");
            } else if(!object.isNull("applied"))throw new IllegalStateException("Unexpected applied status");
        } catch(RuntimeException malformed) {throw new IllegalStateException("Review status invalid",malformed);}
    }
    private static CompletableFuture<Void> observeClose(JDialog dialog) throws Exception {
        var closed=new CompletableFuture<Void>();
        onEventThread(()->{dialog.addWindowListener(new java.awt.event.WindowAdapter(){
            @Override public void windowClosed(WindowEvent event){closed.complete(null);}
        });return null;});
        return closed;
    }
    private static boolean overlayAttached(AlignWayAction action) throws Exception {
        Field field=AlignWayAction.class.getDeclaredField("overlay");field.setAccessible(true);Object overlay=field.get(action);
        Field attached=overlay.getClass().getDeclaredField("attached");attached.setAccessible(true);return attached.getBoolean(overlay);
    }
    private static String checkClosedExport(Path path,LegacyRefinementWorkflow owner,JDialog dialog,AlignWayAction action,
            ExpectedExport expected,String state,List<GeographicPoint> applied) throws Exception {
        var observations=onEventThread(()->new long[]{owner.workflowOwnedBytes(),owner.registryOwnedBytes(),owner.ownedBytes(),
            dialog.isDisplayable()?1:0,hasVisiblePreview()?1:0,overlayAttached(action)?1:0});
        System.err.println("Closed review "+state+" workflowBytes="+observations[0]+" registryBytes="+observations[1]
            +" totalBytes="+observations[2]+" windowDisplayable="+(observations[3]!=0)+" previewVisible="+(observations[4]!=0)+" overlayAttached="+(observations[5]!=0));
        Format15Archive archive;
        try { DiagnosticsRegistry.writeLatest(path.toFile()); archive=Format15ArchiveReader.read(path); }
        catch(Exception unavailable) {
            requireClosedExportAccounting(observations[0],observations[1],observations[2],false,observations[3]!=0,observations[4]!=0,observations[5]!=0);
            throw unavailable;
        }
        requireClosedExportAccounting(observations[0],observations[1],observations[2],true,observations[3]!=0,observations[4]!=0,observations[5]!=0);
        if(archive.formatVersion()!=15||!archive.buildIdentity().equals("runtime-stepwise-refinement-v1"))throw new IllegalStateException("Export format or build identity differs");
        var bundle=new org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15Bundle(
            archive.buildIdentity(),archive.sourceIdentityHash(),archive.parameterHash(),archive.artifacts());
        var lineage=org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.RefinementLineageCodec
            .readBound(bundle,expected.current(),expected.choice()).orElseThrow();
        requireExportIdentity(expected.mode(),lineage.binding().mode(),expected.current().value().toString(),
            lineage.binding().current().value().toString(),expected.choice().value(),lineage.binding().currentChoice().value(),
            archive.artifact(lineage.originalOsm().geometry().artifact()).isPresent(),lineage.latestAttempt().equals(expected.receipt()));
        if(!lineage.binding().root().equals(expected.root())
            ||!lineage.latestPublication().map(p->p.diagnostic()).equals(expected.diagnostic()))
            throw new IllegalStateException("Export lineage or receipt availability differs");
        var currentStage=lineage.stages().stream().filter(v->v.id().equals(expected.current())).findFirst().orElseThrow();
        var currentCandidate=currentStage.candidates().stream().filter(v->v.id().equals(expected.choice())).findFirst().orElseThrow();
        if(!currentCandidate.immutableIdentity().equals(expected.candidateIdentity()))throw new IllegalStateException("Current candidate identity differs");
        if(expected.receipt().isPresent()&&!lineage.originalOsm().immutableIdentity().equals(expected.receipt().get().originalNetworkHash()))
            throw new IllegalStateException("Original authority identity differs");
        if(expected.parent()==null) {
            if(currentStage.parent().isPresent())throw new IllegalStateException("Root gained a parent");
        } else {
            if(!currentStage.parent().equals(java.util.Optional.of(expected.parent()))
                ||!currentStage.parentChoice().map(c->c.value()).equals(java.util.Optional.of(expected.parentChoice())))throw new IllegalStateException("Current parent identity differs");
            var parentStage=lineage.stages().stream().filter(v->v.id().equals(expected.parent())).findFirst().orElseThrow();
            if(!parentStage.parent().equals(java.util.Optional.of(expected.root()))
                ||!parentStage.parentChoice().map(c->c.value()).equals(java.util.Optional.of(expected.rootChoice())))throw new IllegalStateException("Original parent identity differs");
        }
        checkExportGeometry(archive,lineage,expected.root(),expected.rootChoice(),expected.rootGeometry());
        if(expected.parent()!=null)checkExportGeometry(archive,lineage,expected.parent(),expected.parentChoice(),expected.parentGeometry());
        checkExportGeometry(archive,lineage,expected.current(),expected.choice().value(),expected.currentGeometry());
        var original=archive.artifact(lineage.originalOsm().geometry().artifact()).orElseThrow();
        String[] originalRows=new String(original.bytes(),StandardCharsets.UTF_8).strip().split("\n");
        if(originalRows.length!=expected.original().size()+1)throw new IllegalStateException("Original geometry size differs");
        for(int i=0;i<expected.original().size();i++) {String[] fields=originalRows[i+1].split(",");var point=expected.original().get(i);
            if(fields.length!=7||Integer.parseInt(fields[2])!=i||Double.compare(Double.parseDouble(fields[5]),point.latitudeDegrees())!=0
                ||Double.compare(Double.parseDouble(fields[6]),point.longitudeDegrees())!=0)throw new IllegalStateException("Original geometry differs");}
        String status=new String(archive.artifact("review-state.json").orElseThrow().bytes(),StandardCharsets.UTF_8);
        requireReviewStatus(status,state,expected.mode().name(),expected.availability().name(),expected.current().value().toString(),
            expected.choice().value(),currentCandidate.geometry().artifact(),currentCandidate.geometry().sha256(),applied!=null);
        if(applied!=null) {
            if(archive.artifact("host-command-publication.txt").isEmpty())throw new IllegalStateException("Applied publication unavailable");
            String[] rows=new String(archive.artifact("applied-geographic.csv").orElseThrow().bytes(),StandardCharsets.UTF_8).strip().split("\n");
            if(rows.length!=applied.size()+1)throw new IllegalStateException("Applied geometry size differs");
            for(int i=0;i<applied.size();i++){String[] f=rows[i+1].split(",");var point=applied.get(i);
                if(f.length!=3||Integer.parseInt(f[0])!=i||Double.compare(Double.parseDouble(f[1]),point.latitudeDegrees())!=0
                    ||Double.compare(Double.parseDouble(f[2]),point.longitudeDegrees())!=0)throw new IllegalStateException("Applied geometry differs");}
        } else if(archive.artifact("applied-geographic.csv").isPresent())throw new IllegalStateException("Cancelled export claims applied geometry");
        String identity=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        DiagnosticsRegistry.setLastBundle(null);
        onEventThread(()->{if(owner.workflowOwnedBytes()!=0||owner.registryOwnedBytes()!=0||owner.ownedBytes()!=0)throw new IllegalStateException("Retired export retained memory");return null;});
        return identity;
    }
    private static void checkExportGeometry(Format15Archive archive,
            org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.RefinementLineageCodec.Document lineage,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.StageId stage,String choice,
            List<org.openstreetmap.josm.data.coor.EastNorth> geometry) {
        var selected=lineage.stages().stream().filter(v->v.id().equals(stage)).findFirst().orElseThrow().candidates().stream()
            .filter(v->v.id().value().equals(choice)).findFirst().orElseThrow();
        String[] rows=new String(archive.artifact(selected.geometry().artifact()).orElseThrow().bytes(),StandardCharsets.UTF_8).strip().split("\n");
        if(rows.length!=geometry.size()+1)throw new IllegalStateException("Preview geometry size differs");
        for(int i=0;i<geometry.size();i++){String[] f=rows[i+1].split(",");var p=geometry.get(i);
            if(f.length!=4||!f[0].equals("EPSG:3857")||Integer.parseInt(f[1])!=i||Double.compare(Double.parseDouble(f[2]),p.east())!=0
                ||Double.compare(Double.parseDouble(f[3]),p.north())!=0)throw new IllegalStateException("Preview geometry differs");}
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

    private record LaterExportSnapshot(
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.StageId stage,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.CandidateId choice,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementAttemptDiagnostics.Availability availability,
            java.util.Optional<org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementAttemptDiagnostics> diagnostic,
            boolean owned, boolean busy, boolean currentAttempt, String evidence) { }
    private static LaterExportSnapshot laterExportSnapshot(LegacyRefinementWorkflow owner,StageKind expected,
            boolean actionObserved,PipelineAdmissionObserver.Scope admissions) {
        var selected=owner.currentStage();
        var availability=owner.attemptDiagnosticsAvailability(); var diagnostic=owner.latestAttemptDiagnostics();
        String evidence=firstChildEvidence(owner,expected,actionObserved,false,null,admissions)
            .replace("\"keyDelivered\":false", "\"keyDelivered\":null")
            .replace("\"nativeEntryObserved\":false", "\"nativeEntryObserved\":null");
        boolean currentAttempt=isCurrentAttemptDiagnostic(diagnostic,selected.id(),selected.selected(),expected)
            ||isPublishedAttemptDiagnostic(diagnostic,selected.kind(),selected.parent(),selected.parentChoice(),expected);
        return new LaterExportSnapshot(selected.id(),selected.selected(),availability,diagnostic,owner.ownsPublication(),owner.busy(),currentAttempt,evidence);
    }
    static boolean isPublishedAttemptDiagnostic(
            java.util.Optional<org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementAttemptDiagnostics> diagnostic,
            StageKind currentKind,java.util.Optional<org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.StageId> parent,
            java.util.Optional<org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.CandidateId> parentChoice,StageKind expected) {
        return currentKind==expected && diagnostic.map(d->d.publication()==org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementAttemptDiagnostics.Publication.PUBLISHED
            &&parent.equals(java.util.Optional.of(d.binding().ticket().parent()))&&parentChoice.equals(java.util.Optional.of(d.binding().ticket().choice()))
            &&d.binding().ticket().kind()==expected).orElse(false);
    }
    static boolean isCurrentAttemptDiagnostic(
            java.util.Optional<org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementAttemptDiagnostics> diagnostic,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.StageId stage,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.CandidateId choice,StageKind expected) {
        return diagnostic.map(d->d.binding().ticket().parent().equals(stage)&&d.binding().ticket().choice().equals(choice)
            &&d.binding().ticket().kind()==expected).orElse(false);
    }
    private static void clickAndAwaitStage(Path report,JDialog dialog,LegacyRefinementWorkflow owner,String button,StageKind expected) throws Exception {
        try(var admissions=PipelineAdmissionObserver.openForTesting(value->{})) {
            var ready=new CompletableFuture<Void>();
            Runnable check=()->{
                if(owner.busy()){requireComputingView(dialog);return;}
                if(owner.nativeSelected()&&owner.currentStage().kind()==expected)ready.complete(null);
                else ready.completeExceptionally(new IllegalStateException("Requested live refinement did not publish a child"));
            };
            java.beans.PropertyChangeListener listener=event->SwingUtilities.invokeLater(check);
            var actionObserved=new AtomicBoolean();
            java.awt.event.ActionListener actionListener=event->actionObserved.set(true);
            try {
                onEventThread(()->{
                    requiredNamed(dialog.getContentPane(),"stage",javax.swing.JLabel.class).addPropertyChangeListener(listener);
                    requiredNamed(dialog.getContentPane(),"refineA",javax.swing.JButton.class).addPropertyChangeListener(listener);
                    var target=requiredNamed(dialog.getContentPane(),button,javax.swing.JButton.class);
                    target.addActionListener(actionListener);
                    if(!target.isEnabled())throw new IllegalStateException("Requested refinement button is disabled");target.doClick();if(owner.busy())requireComputingView(dialog);SwingUtilities.invokeLater(check);return null;
                });
                ready.get(180,TimeUnit.SECONDS);
            } catch(Exception failure) {
                try {
                    var observed=onEventThread(()->laterExportSnapshot(owner,expected,actionObserved.get(),admissions));
                    String evidence=observed.evidence();
                    String exportStatus="UNAVAILABLE"; String archiveHash=null;
                    try {
                        Path archive=report.resolveSibling("later-refinement-failure.zip");
                        DiagnosticsRegistry.writeLatest(archive.toFile());
                        var read=Format15ArchiveReader.read(archive);
                        if(read.formatVersion()!=15)throw new IllegalStateException("Refinement format unavailable");
                        var bundle=new org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15Bundle(read.buildIdentity(),read.sourceIdentityHash(),read.parameterHash(),read.artifacts());
                        var lineage=org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.RefinementLineageCodec.readBound(bundle,observed.stage(),observed.choice()).orElseThrow();
                        String status=new String(read.artifact("review-state.json").orElseThrow().bytes(),StandardCharsets.UTF_8);
                        String availability=parseReviewStatus(status).getString("latestAttemptAvailability");
                        var selection=new org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.RefinementLineageCodec.CandidateKey(observed.stage(),observed.choice());
                        boolean currentAttempt=observed.currentAttempt();
                        requireLatestAttemptBinding(observed.diagnostic(),observed.availability(),lineage.latestPublication(),lineage.latestAttempt(),availability,currentAttempt?selection:null);
                        var after=onEventThread(()->laterExportSnapshot(owner,expected,actionObserved.get(),admissions));
                        if(!observed.owned()||!after.owned()||observed.busy()!=after.busy()||observed.currentAttempt()!=after.currentAttempt()||!observed.stage().equals(after.stage())
                            ||!observed.choice().equals(after.choice())||observed.availability()!=after.availability()
                            ||!observed.diagnostic().equals(after.diagnostic()))throw new IllegalStateException("Refinement owner changed during export");
                        archiveHash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive)));
                        exportStatus=currentAttempt?"AVAILABLE":"CURRENT_ATTEMPT_UNAVAILABLE";
                    } catch(Exception unavailable) { exportStatus="UNAVAILABLE"; }
                    laterRefinementFailureEvidence=evidence.substring(0,evidence.length()-1)+",\"registryExport\":\""+exportStatus+"\",\"archiveSha256\":"+(archiveHash==null?"null":"\""+archiveHash+"\"")+"}";
                    System.err.println("Later refinement diagnostics: "+laterRefinementFailureEvidence);
                    writeSummary(report.resolveSibling("later-refinement-diagnostics.json"),laterRefinementFailureEvidence+"\n");
                } catch(Exception diagnosticFailure) { failure.addSuppressed(diagnosticFailure); }
                throw failure;
            } finally {onEventThread(()->{requiredNamed(dialog.getContentPane(),"stage",javax.swing.JLabel.class).removePropertyChangeListener(listener);requiredNamed(dialog.getContentPane(),"refineA",javax.swing.JButton.class).removePropertyChangeListener(listener);requiredNamed(dialog.getContentPane(),button,javax.swing.JButton.class).removeActionListener(actionListener);return null;});}
        }
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

    /** Continuous public imagery painted through the actual JOSM visible-layer capture port. */
    private static final class AnalyticVisibleLayer extends org.openstreetmap.josm.gui.layer.ImageryLayer {
        AnalyticVisibleLayer(){super(new org.openstreetmap.josm.data.imagery.ImageryInfo("Strava public analytic visible heatmap"));}
        @Override public void paint(java.awt.Graphics2D graphics,org.openstreetmap.josm.gui.MapView view,org.openstreetmap.josm.data.Bounds bounds) {
            int width=view.getWidth(),height=view.getHeight();if(width<1||height<1)return;
            var image=new java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_RGB);
            double radians=Math.PI/180,earth=PublicGuiSmokeOracle.EARTH_RADIUS_METERS;
            for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
                var point=view.getLatLon(x,y);
                double east=(point.lon()-PublicGuiSmokeOracle.BASE_LONGITUDE)*radians*earth*Math.cos(PublicGuiSmokeOracle.BASE_LATITUDE*radians);
                double north=(point.lat()-PublicGuiSmokeOracle.BASE_LATITUDE)*radians*earth;
                double ridge=4*Math.max(0,Math.cos(Math.PI*east/64));
                // Match the public native z15 image's 1.2-source-pixel Gaussian ridge.
                double pitch=2*Math.PI*earth/(512*(1<<15));
                double distance=(north-ridge)/pitch;
                int gray=(int)Math.round(3+250*Math.exp(-.5*distance*distance/1.44));
                image.setRGB(x,y,gray<<16|gray<<8|gray);
            }
            graphics.drawImage(image,0,0,null);
        }
        @Override public String getToolTipText(){return "Public analytic visible heatmap";}
        @Override public void visitBoundingBox(org.openstreetmap.josm.data.osm.visitor.BoundingXYVisitor visitor){}
        @Override public javax.swing.Action[] getMenuEntries(){return new javax.swing.Action[0];}
        @Override protected javax.swing.Action getAdjustAction(){return new javax.swing.AbstractAction("Public source"){public void actionPerformed(java.awt.event.ActionEvent event){}};}
        @Override protected List<org.openstreetmap.josm.gui.layer.ImageryLayer.OffsetMenuEntry> getOffsetMenuEntries(){return List.of();}
    }

    static void addOfflineManagedLayer(ManagedHeatmapConfig config,
            java.util.function.Consumer<org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.ManagedHeatmapLayer> add) {
        if (!config.hasManagedAccessValues()) throw new IllegalStateException("Managed heatmap access values are incomplete");
        String activity = config.activity().trim().toLowerCase(java.util.Locale.ROOT);
        String color = config.color().trim().toLowerCase(java.util.Locale.ROOT);
        var template = new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileUrlBuilder()
            .buildJosmTemplate(activity, color, new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration(Math.max(0L, config.cacheBuster())));
        var info = new org.openstreetmap.josm.data.imagery.ImageryInfo(
            ManagedImageryService.MANAGED_LAYER_NAME + " (" + activity + "/" + color + ")", template,
            "tms", null, config.toCookieHeader(), ManagedImageryService.MANAGED_LAYER_ID);
        info.setDefaultMaxZoom(15);
        var layer = new org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.ManagedHeatmapLayer(info);
        layer.getDisplaySettings().setAutoLoad(false);
        add.accept(layer);
    }
    static void requireClosedExportAccounting(long workflow, long registry, long total, boolean available,
            boolean windowDisplayable, boolean previewVisible, boolean overlayAttached) {
        if (workflow != 0 || registry <= 0 || total != registry || !available || windowDisplayable || previewVisible || overlayAttached)
            throw new IllegalStateException("Closed review/export accounting differs");
    }
    static void requireExportIdentity(AlignmentMode expectedMode, AlignmentMode actualMode, String expectedStage,
            String actualStage, String expectedChoice, String actualChoice, boolean geometryAvailable, boolean receiptAvailable) {
        if (expectedMode != actualMode || !java.util.Objects.equals(expectedStage, actualStage)
            || !java.util.Objects.equals(expectedChoice, actualChoice) || !geometryAvailable || !receiptAvailable)
            throw new IllegalStateException("Exported review identity or evidence differs");
    }
    static void requireKeyboardReady(boolean enabled, boolean focused) {
        if (!enabled || !focused) throw new IllegalStateException("Keyboard target is disabled or unfocused");
    }
    static void requireLegacyMigrationRoot(java.util.function.Supplier<TrackerMode> readEngine) throws Exception {
        if (onEventThread(readEngine::get) != TrackerMode.LEGACY_V02)
            throw new IllegalStateException("Keyboard entry did not start Legacy");
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
    enum FirstChildState { WAIT, CHILD, NO_CHILD, LOST_OWNER }
    static FirstChildState firstChildState(boolean actionObserved, boolean busy, boolean owns,
            boolean expectedChild) {
        if (!actionObserved) return FirstChildState.WAIT;
        if (!owns) return FirstChildState.LOST_OWNER;
        if (busy) return FirstChildState.WAIT;
        return expectedChild ? FirstChildState.CHILD : FirstChildState.NO_CHILD;
    }
    private static void keyboardAndAwaitStage(Path report, JDialog dialog, LegacyRefinementWorkflow owner,
            String button, StageKind expected) throws Exception {
        try(var admissions=PipelineAdmissionObserver.openForTesting(value->{})) {
            keyboardAndAwaitObservedStage(report,dialog,owner,button,expected,admissions);
        }
    }
    private static void keyboardAndAwaitObservedStage(Path report,JDialog dialog,LegacyRefinementWorkflow owner,
            String button,StageKind expected,PipelineAdmissionObserver.Scope admissions) throws Exception {
        var focused = new CompletableFuture<Void>();
        var keyDelivered = new CompletableFuture<Void>();
        var actionObserved = new AtomicBoolean();
        var entry = new java.util.concurrent.atomic.AtomicReference<LegacyRefinementWorkflow.NativeEntry>();
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
            if(owner.busy())requireComputingView(dialog);
            boolean child = owner.nativeSelected() && owner.currentStage().kind() == expected;
            switch (firstChildState(actionObserved.get(), owner.busy(), owner.ownsPublication(), child)) {
                case CHILD -> ready.complete(null);
                case NO_CHILD -> ready.completeExceptionally(new IllegalStateException("First refinement settled without requested child"));
                case LOST_OWNER -> ready.completeExceptionally(new IllegalStateException("First refinement lost publication ownership"));
                case WAIT -> { }
            }
        };
        java.awt.event.ActionListener actionListener = event -> { actionObserved.set(true); SwingUtilities.invokeLater(check); };
        java.beans.PropertyChangeListener listener = event -> SwingUtilities.invokeLater(check);
        javax.swing.event.DocumentListener documentListener = new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent event) { SwingUtilities.invokeLater(check); }
            public void removeUpdate(javax.swing.event.DocumentEvent event) { SwingUtilities.invokeLater(check); }
            public void changedUpdate(javax.swing.event.DocumentEvent event) { SwingUtilities.invokeLater(check); }
        };
        owner.firstNativeEntry().whenComplete((nativeEntry, failure) -> {
            if (nativeEntry != null) {
                entry.set(nativeEntry);
                nativeEntry.settlement().whenComplete((ignored, settlementFailure) -> SwingUtilities.invokeLater(check));
            }
        });
        onEventThread(() -> {
            if (!target.isEnabled()) throw new IllegalStateException("Keyboard refinement is disabled");
            target.addFocusListener(focusListener); target.addKeyListener(keyListener); target.addActionListener(actionListener);
            requiredNamed(dialog.getContentPane(), "quality", javax.swing.JTextArea.class).getDocument().addDocumentListener(documentListener);
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
        } catch (Exception failure) {
            try {
                String evidence = onEventThread(() -> firstChildEvidence(owner, expected, actionObserved.get(), keyDelivered.isDone(), entry.get(),admissions));
                firstRefinementFailureEvidence = evidence;
                System.err.println("First refinement diagnostics: " + evidence);
                writeSummary(report.resolveSibling("first-refinement-diagnostics.json"), evidence + "\n");
            } catch (Exception diagnosticFailure) { failure.addSuppressed(diagnosticFailure); }
            throw failure;
        } finally {
            onEventThread(() -> { target.removeFocusListener(focusListener); target.removeKeyListener(keyListener); target.removeActionListener(actionListener);
                requiredNamed(dialog.getContentPane(), "quality", javax.swing.JTextArea.class).getDocument().removeDocumentListener(documentListener);
                requiredNamed(dialog.getContentPane(), "stage", javax.swing.JLabel.class).removePropertyChangeListener(listener);
                requiredNamed(dialog.getContentPane(), "refineA", javax.swing.JButton.class).removePropertyChangeListener(listener); return null; });
        }
    }
    static String diagnosticAdmission(java.util.Optional<PipelineAdmissionObserver.Rejection> observation,OperationTicket ticket) {
        var matching=observation.filter(value->value.ticket().equals(ticket));
        if(matching.isEmpty())return "null";
        var value=matching.get();
        String current=value.currentEvidence().map(grid->"{\"inputWidth\":"+grid.inputWidth()+",\"inputHeight\":"+grid.inputHeight()
            +",\"outputWidth\":"+grid.outputWidth()+",\"outputHeight\":"+grid.outputHeight()+",\"fields\":"+grid.fields()+"}").orElse("null");
        return "{\"gate\":\""+value.gate().name()+"\",\"requestedBytes\":"+diagnosticNumber(value.requestedBytes())
            +",\"currentBytes\":"+diagnosticNumber(value.currentBytes())+",\"limitBytes\":"+diagnosticNumber(value.limitBytes())
            +",\"elapsedNanos\":"+diagnosticNumber(value.elapsedNanos())+",\"deadlineNanos\":"+diagnosticNumber(value.deadlineNanos())
            +",\"parentPoints\":"+value.parentPoints()+",\"selectedOccurrences\":"+value.selectedOccurrences()+",\"intervals\":"+value.intervals()
            +",\"assembledVertexBound\":"+diagnosticNumber(value.assembledVertexBound())+",\"rowBound\":"+diagnosticNumber(value.rowBound())
            +",\"rootConfiguredStepMeters\":"+value.rootConfiguredStepMeters()+",\"rootProfileRows\":"+value.rootProfileRows()
            +",\"rootRasterWidth\":"+value.rootRasterWidth()+",\"rootRasterHeight\":"+value.rootRasterHeight()+",\"currentEvidence\":"+current+"}";
    }
    private static String diagnosticNumber(java.util.OptionalLong value){return value.isPresent()?Long.toString(value.getAsLong()):"null";}
    private static final int MAX_DIAGNOSTIC_ATTEMPT_ROWS = 64;
    private static final java.util.Set<String> DIAGNOSTIC_PALETTES = java.util.Set.of("hot", "blue", "bluered", "purple", "gray");
    private static final java.util.Set<String> DIAGNOSTIC_MAPPINGS = java.util.Set.of(
        "hot", "blue", "blue-corridor", "bluered", "purple", "purple-corridor", "gray", "gray-magenta", "gray-corridor",
        "dual", "hot-corridor", "hot-strict", "bluered-cool", "bluered-corridor", "dual-corridor", "gray-strict",
        "purple-strict", "bluered-combined", "gray-combined", "multi-combined", "all-colors-combined");
    private static final java.util.Set<String> DIAGNOSTIC_REASONS = java.util.Set.of(
        "operation-aborted", "operation-budget-stopped", "operation-cancelled", "source-unavailable", "native-receipt-unavailable",
        "existing-parent-evidence-required", "visible-direct-intensity-unsupported", "visible-palette-unavailable",
        "direct-mapping-variant-unavailable", "direct-aggregate-unavailable", "visible-aggregate-unavailable",
        "COMPLETE", "PARTIAL", "NO_USABLE_RESULT", "CANCELLED", "STALE", "RESOURCE_LIMIT", "FAILED",
        "ELIGIBLE", "TOO_FEW_ORIGINAL_OCCURRENCES", "MAPPING_UNAVAILABLE", "MAPPING_AMBIGUOUS", "DEGENERATE_REFERENCE",
        "OUTSIDE_EDIT_AUTHORITY", "REPEATED_OCCURRENCE", "UNKNOWN_TOPOLOGY", "FROZEN_GEOMETRY_PROOF_UNAVAILABLE",
        "FROZEN_GEOMETRY_SPAN", "FROZEN_INTERIOR", "EVIDENCE_COVERAGE", "SPATIAL_RESOLUTION_UNAVAILABLE");
    private static String diagnosticToken(String value, java.util.Set<String> allowed) {
        return value != null && allowed.contains(value) ? value : "UNAVAILABLE";
    }
    static String diagnosticAttemptRows(java.util.List<org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementOperationReceipt.Attempt> attempts) {
        var rows = new ArrayList<String>();
        for (var attempt : attempts.subList(0, Math.min(attempts.size(), MAX_DIAGNOSTIC_ATTEMPT_ROWS))) {
            String outcome = attempt.outcome().map(value -> "\"" + value.name() + "\"").orElse("null");
            String nativeStatus = attempt.nativeStatus().map(value -> "\"" + value.name() + "\"").orElse("null");
            String calls = attempt.nativeCalls().isPresent() ? Integer.toString(attempt.nativeCalls().getAsInt()) : "null";
            rows.add("{\"palette\":\"" + diagnosticToken(attempt.palette(), DIAGNOSTIC_PALETTES)
                + "\",\"mapping\":\"" + diagnosticToken(attempt.mapping(), DIAGNOSTIC_MAPPINGS)
                + "\",\"kind\":\"" + attempt.kind().name()
                + "\",\"reason\":\"" + diagnosticToken(attempt.reason(), DIAGNOSTIC_REASONS)
                + "\",\"outcome\":" + outcome + ",\"nativeCalls\":" + calls + ",\"nativeStatus\":" + nativeStatus
                + ",\"requestPresent\":" + attempt.samplerId().isPresent()
                + ",\"evidencePresent\":" + attempt.evidenceHash().isPresent()
                + ",\"parameterPresent\":" + attempt.parameterHash().isPresent() + "}");
        }
        return "[" + String.join(",", rows) + "]";
    }
    private static String firstChildEvidence(LegacyRefinementWorkflow owner, StageKind expected,
            boolean action, boolean key, LegacyRefinementWorkflow.NativeEntry entry,PipelineAdmissionObserver.Scope admissions) {
        var diagnostics = owner.latestAttemptDiagnostics();
        var pipeline = diagnostics.flatMap(d -> d.pipeline());
        String phase = diagnostics.map(d -> d.phase().name()).orElse("UNAVAILABLE");
        String cause = diagnostics.map(d -> d.cause().name()).orElse("UNAVAILABLE");
        String publication = diagnostics.map(d -> d.publication().name()).orElse("UNAVAILABLE");
        String operationKind = diagnostics.map(d -> d.binding().ticket().kind().name()).orElse("UNAVAILABLE");
        String outcome = pipeline.map(p -> p.outcome().name()).orElse("UNAVAILABLE");
        String actualStage = owner.nativeSelected() ? owner.currentStage().kind().name() : "ROOT_LEGACY";
        return "{\"schema\":\"wayheatmaptracer-first-refinement-1\",\"expected\":\"" + expected.name()
            + "\",\"stage\":\"" + actualStage + "\",\"keyDelivered\":" + key + ",\"actionObserved\":" + action
            + ",\"owned\":" + owner.ownsPublication() + ",\"busy\":" + owner.busy()
            + ",\"nativeCalls\":" + owner.actualNativeCalls() + ",\"nativeEntryObserved\":" + (entry != null)
            + ",\"nativeEntered\":" + (entry == null ? "null" : Boolean.toString(entry.entered()))
            + ",\"workerReturned\":" + (entry == null ? "null" : Boolean.toString(entry.workerReturned()))
            + ",\"settled\":" + (entry == null ? "null" : Boolean.toString(entry.settlement().toCompletableFuture().isDone()))
            + ",\"diagnosticsAvailability\":\"" + owner.attemptDiagnosticsAvailability().name()
            + "\",\"frozenGeometryProof\":\""+diagnostics.map(d->d.binding().frozenGeometryIdentity().equals("frozen-geometry-unavailable")?"UNAVAILABLE":"AVAILABLE").orElse("UNAVAILABLE")
            + "\",\"refusalReasons\":\""+DIAGNOSTIC_REASONS.stream().filter(owner.computationEvidence()::contains).sorted().toList()
            + "\",\"failureBoundary\":\""+fixedFailureBoundary(owner.computationEvidence())
            + "\",\"operationKind\":\"" + operationKind + "\",\"phase\":\"" + phase + "\",\"cause\":\"" + cause
            + "\",\"publication\":\"" + publication + "\",\"pipelineOutcome\":\"" + outcome
            + "\",\"pipelineAttempts\":" + pipeline.map(p -> Integer.toString(p.attempts().size())).orElse("null")
            + ",\"attemptRows\":" + pipeline.map(p -> diagnosticAttemptRows(p.attempts())).orElse("null")
            + ",\"pipelineAdmission\":" + diagnostics.map(d -> diagnosticAdmission(admissions.latest(d.binding().ticket()),d.binding().ticket())).orElse("null")
            + ",\"attemptRowsTruncated\":" + pipeline.map(p -> Boolean.toString(p.attempts().size() > MAX_DIAGNOSTIC_ATTEMPT_ROWS)).orElse("null") + "}";
    }
    private static String fixedFailureBoundary(String evidence) {
        var match=java.util.regex.Pattern.compile("; failed=([A-Za-z0-9_$]{1,80}); phase=([A-Z_]{1,40}); boundary=([A-Za-z0-9_.$:]{1,160})").matcher(evidence);
        return match.find()?match.group(1)+":"+match.group(2)+":"+match.group(3):"unavailable";
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
                requireNoObsoleteMethodUi(dialog.getContentPane());
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
            onEventThread(() -> {
                for (Window window : Window.getWindows()) if (window.isShowing()) requireNoObsoleteMethodUi(window);
                return null;
            });
            if (unexpectedModal.get() || onEventThread(dialog::isVisible)) {
                throw new IllegalStateException("Successful Settings save opened an unexpected modal");
            }
            if (!PluginPreferences.load().verbose()) {
                throw new IllegalStateException("Settings OK did not persist the changed control");
            }
            if(PluginPreferences.load().trackerMode()!=previousTracing.engine()||PluginPreferences.loadTracingSettings().engine()!=previousTracing.engine())
                throw new IllegalStateException("Settings acceptance changed the selected starting engine");
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

    static void requireNoObsoleteMethodUi(Container root) {
        for (Component component : root.getComponents()) {
            String text = component instanceof javax.swing.JLabel label ? label.getText()
                : component instanceof javax.swing.JTextArea area ? area.getText() : null;
            if (text != null && (text.contains("old A+B Hybrid") || text.contains("tracing method is now Legacy")))
                throw new IllegalStateException("Obsolete method notice remains visible");
            if (component instanceof JComboBox<?> combo) {
                var engines=new ArrayList<TrackerMode>();
                for(int i=0;i<combo.getItemCount();i++)if(combo.getItemAt(i) instanceof TrackerMode engine)engines.add(engine);
                if(!engines.isEmpty()&&!engines.equals(List.of(TrackerMode.LEGACY_V02,TrackerMode.CORRIDOR_AWARE,TrackerMode.PROBABILISTIC)))
                    throw new IllegalStateException("Settings starting algorithm choices differ");
            }
            if (component instanceof Container nested) requireNoObsoleteMethodUi(nested);
        }
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

    static String entryActionKey(TrackerMode savedEngine) {
        return switch (savedEngine) {
            case PROBABILISTIC -> "wayheatmaptracer.align.global";
            case LEGACY_THEN_B -> "wayheatmaptracer.align.precise.global";
            case CORRIDOR_AWARE -> "wayheatmaptracer.align.move-nodes.global";
            default -> throw new IllegalArgumentException("No migration keyboard case");
        };
    }

    private static AlignWayAction registeredEntryAction(TrackerMode savedEngine) {
        var root = MainApplication.getMainFrame().getRootPane();
        Object action = root.getActionMap().get(entryActionKey(savedEngine));
        if (!(action instanceof AlignWayAction align)) throw new IllegalStateException("Registered shortcut action missing");
        AlignmentMode expected = savedEngine == TrackerMode.PROBABILISTIC ? null
            : savedEngine == TrackerMode.LEGACY_THEN_B ? AlignmentMode.PRECISE_SHAPE : AlignmentMode.MOVE_EXISTING_NODES;
        if (align.forcedAlignmentMode() != expected) throw new IllegalStateException("Shortcut owns wrong mode override");
        if (expected == null && align != registeredOrdinaryAction()) throw new IllegalStateException("Ordinary shortcut owns another action");
        return align;
    }

    private static void dispatchEntryShortcut(TrackerMode savedEngine, AlignWayAction expected) throws Exception {
        shortcutRegistryBefore = onEventThread(DiagnosticsRegistry::hasLatest);
        javax.swing.KeyStroke stroke;
        try { stroke = entryStroke(savedEngine, expected); }
        catch (Exception failure) {
            shortcutFailureEvidence = boundedShortcutEvidence(savedEngine, expected, false);
            throw failure;
        }
        int key = stroke.getKeyCode();
        int modifiers = stroke.getModifiers() & (java.awt.event.InputEvent.CTRL_DOWN_MASK
            | java.awt.event.InputEvent.SHIFT_DOWN_MASK | java.awt.event.InputEvent.ALT_DOWN_MASK
            | java.awt.event.InputEvent.META_DOWN_MASK | java.awt.event.InputEvent.ALT_GRAPH_DOWN_MASK);
        var focused = new CompletableFuture<Void>();
        var delivered = new CompletableFuture<Void>();
        var manager = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager();
        java.beans.PropertyChangeListener focusListener = event -> {
            if (event.getNewValue() instanceof Component component
                    && SwingUtilities.getWindowAncestor(component) == MainApplication.getMainFrame()) focused.complete(null);
        };
        AWTEventListener keyListener = event -> {
            if (event instanceof java.awt.event.KeyEvent pressed && pressed.getID() == java.awt.event.KeyEvent.KEY_PRESSED
                    && pressed.getKeyCode() == key && (pressed.getModifiersEx() & modifiers) == modifiers
                    && SwingUtilities.getWindowAncestor(pressed.getComponent()) == MainApplication.getMainFrame()) delivered.complete(null);
        };
        manager.addPropertyChangeListener("focusOwner", focusListener);
        Toolkit.getDefaultToolkit().addAWTEventListener(keyListener, AWTEvent.KEY_EVENT_MASK);
        try {
            onEventThread(() -> {
                shortcutRegistryBefore = DiagnosticsRegistry.hasLatest();
                if (MainApplication.getRegisteredActionShortcut(expected.getShortcut()) != expected) {
                    shortcutFailureEvidence = shortcutEvidence(savedEngine, expected, false);
                    throw new IllegalStateException("JOSM shortcut no longer resolves to expected action");
                }
                if (!expected.isEnabled()) {
                    shortcutFailureEvidence = shortcutEvidence(savedEngine, expected, false);
                    throw new IllegalStateException("Registered shortcut action is disabled");
                }
                MainApplication.getMainFrame().toFront();
                MainApplication.getMap().mapView.requestFocusInWindow();
                Component owner = manager.getFocusOwner();
                if (owner != null && SwingUtilities.getWindowAncestor(owner) == MainApplication.getMainFrame()) focused.complete(null);
                return null;
            });
            focused.get(30, TimeUnit.SECONDS);
            var robot = new java.awt.Robot();
            int[] masks = {java.awt.event.InputEvent.CTRL_DOWN_MASK, java.awt.event.InputEvent.SHIFT_DOWN_MASK,
                java.awt.event.InputEvent.ALT_DOWN_MASK, java.awt.event.InputEvent.META_DOWN_MASK,
                java.awt.event.InputEvent.ALT_GRAPH_DOWN_MASK};
            int[] keys = {java.awt.event.KeyEvent.VK_CONTROL, java.awt.event.KeyEvent.VK_SHIFT,
                java.awt.event.KeyEvent.VK_ALT, java.awt.event.KeyEvent.VK_META, java.awt.event.KeyEvent.VK_ALT_GRAPH};
            for (int i = 0; i < masks.length; i++) if ((modifiers & masks[i]) != 0) robot.keyPress(keys[i]);
            robot.keyPress(key); robot.keyRelease(key);
            for (int i = masks.length - 1; i >= 0; i--) if ((modifiers & masks[i]) != 0) robot.keyRelease(keys[i]);
            delivered.get(30, TimeUnit.SECONDS);
        } catch (Exception failure) {
            if (shortcutFailureEvidence == null)
                shortcutFailureEvidence = boundedShortcutEvidence(savedEngine, expected, delivered.isDone());
            throw failure;
        } finally {
            manager.removePropertyChangeListener("focusOwner", focusListener);
            Toolkit.getDefaultToolkit().removeAWTEventListener(keyListener);
        }
    }

    static javax.swing.KeyStroke entryStroke(TrackerMode savedEngine, AlignWayAction expected) throws Exception {
        return onEventThread(() -> {
            AlignmentMode mode = savedEngine == TrackerMode.PROBABILISTIC ? null
                : savedEngine == TrackerMode.LEGACY_THEN_B ? AlignmentMode.PRECISE_SHAPE
                : savedEngine == TrackerMode.CORRIDOR_AWARE ? AlignmentMode.MOVE_EXISTING_NODES
                : throwUnknownEntry();
            if (expected.forcedAlignmentMode() != mode) throw new IllegalStateException("Shortcut owns wrong mode override");
            var shortcut = expected.getShortcut();
            var stroke = shortcut == null ? null : shortcut.getKeyStroke();
            if (stroke == null || stroke.isOnKeyRelease() || stroke.getKeyCode() == java.awt.event.KeyEvent.VK_UNDEFINED
                    || MainApplication.getRegisteredActionShortcut(shortcut) != expected)
                throw new IllegalStateException("JOSM shortcut is unavailable or resolves to another action");
            return stroke;
        });
    }

    private static AlignmentMode throwUnknownEntry() {
        throw new IllegalArgumentException("No migration keyboard case");
    }

    private static String boundedShortcutEvidence(TrackerMode savedEngine, AlignWayAction expected, boolean delivered) {
        var evidence = new CompletableFuture<String>();
        SwingUtilities.invokeLater(() -> {
            try { evidence.complete(shortcutEvidence(savedEngine, expected, delivered)); }
            catch (RuntimeException failure) { evidence.complete("{\"capture\":\"failed\"}"); }
        });
        try { return evidence.get(2, TimeUnit.SECONDS); }
        catch (Exception unavailable) { return "{\"capture\":\"edt-unavailable\",\"keyDelivered\":" + delivered + "}"; }
    }

    /** Bounded synthetic-CI facts; an absent retained log is not proof that the action never ran. */
    private static String shortcutEvidence(TrackerMode savedEngine, AlignWayAction expected, boolean delivered) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Shortcut evidence requires EDT");
        var registered = expected.getShortcut();
        var stroke = registered == null ? null : registered.getKeyStroke();
        String mode = expected.forcedAlignmentMode() == null ? "CONFIGURED" : expected.forcedAlignmentMode().name();
        String expectedMode = savedEngine == TrackerMode.LEGACY_THEN_B ? "PRECISE_SHAPE"
            : savedEngine == TrackerMode.CORRIDOR_AWARE ? "MOVE_EXISTING_NODES" : "CONFIGURED";
        if (stroke == null) return "{\"keyDelivered\":" + delivered + ",\"registeredStroke\":\"unavailable\",\"expectedMode\":\""
            + expectedMode + "\",\"actionMode\":\"" + mode + "\",\"actionIdentity\":" + System.identityHashCode(expected)
            + ",\"actionEnabled\":" + expected.isEnabled() + ",\"registryBefore\":" + shortcutRegistryBefore
            + ",\"registryAfter\":" + DiagnosticsRegistry.hasLatest() + "}";
        var root = MainApplication.getMainFrame().getRootPane();
        Object rootKey = root.getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).get(stroke);
        var map = MainApplication.getMap().mapView;
        Object mapKey = map.getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).get(stroke);
        Component focused = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        String focusedBinding = "unavailable";
        boolean focusedResolvesAction = false;
        if (focused instanceof javax.swing.JComponent component) {
            Object value = component.getInputMap(javax.swing.JComponent.WHEN_FOCUSED).get(stroke);
            focusedBinding = value == null ? "none" : entryActionKey(savedEngine).equals(value) ? "expected" : "other";
            focusedResolvesAction = value != null && component.getActionMap().get(value) == expected;
        }
        String ancestorBinding = "none";
        for (Component cursor = focused == null ? null : focused.getParent(); cursor != null; cursor = cursor.getParent()) {
            if (cursor instanceof javax.swing.JComponent component) {
                Object value = component.getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(stroke);
                if (value != null) {
                    ancestorBinding = component.getActionMap().get(value) == expected ? "expected" : "other";
                    break;
                }
            }
        }
        var matches = org.openstreetmap.josm.tools.Shortcut.listAll().stream()
            .filter(shortcut -> stroke.equals(shortcut.getKeyStroke())).limit(12)
            .map(shortcut -> shortcut.getShortText() == null ? "unknown"
                : safeToken(shortcut.getShortText().replaceAll("[^A-Za-z0-9._-]", "_")))
            .map(name -> "\"" + name + "\"").toList();
        int visible = 0, modal = 0, review = 0;
        for (Window window : Window.getWindows()) if (window.isShowing()) {
            visible++;
            if (window instanceof JDialog dialog) {
                if (dialog.isModal()) modal++;
                if ("legacy-review".equals(dialog.getName())) review++;
            }
        }
        boolean retained = org.openstreetmap.josm.plugins.wayheatmaptracer.util.PluginLog.currentSlideLog()
            .contains("Align Way to Heatmap invoked.");
        return "{\"keyDelivered\":" + delivered + ",\"expectedMode\":\"" + expectedMode
            + "\",\"actionMode\":\"" + mode + "\",\"actionEnabled\":" + expected.isEnabled()
            + ",\"actionIdentity\":" + System.identityHashCode(expected)
            + ",\"josmResolvesAction\":" + (MainApplication.getRegisteredActionShortcut(registered) == expected)
            + ",\"rootResolvesAction\":" + (rootKey != null && root.getActionMap().get(rootKey) == expected)
            + ",\"mapResolvesAction\":" + (mapKey != null && map.getActionMap().get(mapKey) == expected)
            + ",\"focusedClass\":\"" + (focused == null ? "none" : safeSymbol(focused.getClass().getName()))
            + "\",\"focusedBinding\":\"" + focusedBinding + "\",\"focusedResolvesAction\":"
            + focusedResolvesAction + ",\"ancestorBinding\":\"" + ancestorBinding + "\",\"assignedKey\":"
            + (registered == null ? -1 : registered.getAssignedKey()) + ",\"assignedModifiers\":"
            + (registered == null ? -1 : registered.getAssignedModifier()) + ",\"registryMatches\":["
            + String.join(",", matches) + "],\"registryBefore\":" + shortcutRegistryBefore
            + ",\"registryAfter\":" + DiagnosticsRegistry.hasLatest() + ",\"entryLogRetained\":" + retained
            + ",\"visibleWindows\":" + visible + ",\"modalDialogs\":" + modal + ",\"legacyReviews\":" + review + "}";
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
    private static void requireComputingView(JDialog review) {
        if(review.isVisible())throw new IllegalStateException("Old result review remains visible during optional work");
        JDialog progress=null;
        for(Window window:Window.getWindows())if(window instanceof JDialog dialog&&dialog.isShowing()&&"refinement-progress".equals(dialog.getName())) {
            if(progress!=null)throw new IllegalStateException("Multiple optional-work progress windows");progress=dialog;
        }
        if(progress==null||progress.isModal()||!requiredNamed(progress.getContentPane(),"refinement-cancel",javax.swing.JButton.class).isShowing())
            throw new IllegalStateException("Modeless optional-work progress and Cancel are unavailable");
        if(!hasEnabledDiagnosticsExport(MainApplication.getMenu().moreToolsMenu))throw new IllegalStateException("Diagnostics menu is inaccessible during optional work");
    }

    private static boolean hasEnabledDiagnosticsExport(javax.swing.JMenu menu) {
        return diagnosticsExportItem(menu)!=null;
    }
    private static javax.swing.JButton labelledButton(Container parent,String label) {
        var buttons=new ArrayList<javax.swing.JButton>();collectLabelledButtons(parent,org.openstreetmap.josm.tools.I18n.tr(label),buttons);
        if(buttons.size()!=1)throw new IllegalStateException("Expected one original native footer control");return buttons.get(0);
    }
    private static void collectLabelledButtons(Container parent,String label,List<javax.swing.JButton> found) {
        for(Component child:parent.getComponents()){if(child instanceof javax.swing.JButton button&&label.equals(button.getText()))found.add(button);else if(child instanceof Container nested)collectLabelledButtons(nested,label,found);}
    }
    private static void confirmOriginalNative(JDialog dialog) {
        var confirm=labelledButton(dialog.getContentPane(),"Confirm review");if(confirm.isEnabled())confirm.doClick();
        if(!labelledButton(dialog.getContentPane(),"Apply").isEnabled())throw new IllegalStateException("Exact original native plan is not applicable after its review");
    }
    private static void exerciseOriginalNativeApply(JDialog dialog,PublicGuiSmokeFixture fixture,List<GeographicPoint> expected,List<String> original,int originalUndo) throws Exception {
        var modal=new AtomicBoolean();AWTEventListener dismiss=event->{if(event instanceof WindowEvent e&&e.getID()==WindowEvent.WINDOW_OPENED&&e.getWindow() instanceof JDialog warning&&warning.isModal()){modal.set(true);SwingUtilities.invokeLater(warning::dispose);}};
        Toolkit.getDefaultToolkit().addAWTEventListener(dismiss,AWTEvent.WINDOW_EVENT_MASK);
        try{onEventThread(()->{
            var owner=(LegacyRefinementWorkflow)dialog.getRootPane().getClientProperty("wayheatmaptracer.refinementOwner");
            if(owner.nativeSelected()||owner.busy())throw new IllegalStateException("Original native Apply must need no optional operation");
            confirmOriginalNative(dialog);labelledButton(dialog.getContentPane(),"Apply").doClick();return null;
        });}finally{Toolkit.getDefaultToolkit().removeAWTEventListener(dismiss);}
        if(modal.get()||onEventThread(dialog::isDisplayable))throw new IllegalStateException("Original native Apply did not commit and close its review");
        var applied=wayState(fixture);if(applied.equals(original)||UndoRedoHandler.getInstance().getUndoCommands().size()!=originalUndo+1)throw new IllegalStateException("Original native Apply did not publish one command");
        var actual=fixture.selectedWay().getNodes().stream().map(node->new GeographicPoint(node.lat(),node.lon())).toList();
        if(!expected.equals(actual))throw new IllegalStateException("Original native command differs from its exact stored plan");
        onEventThread(()->{UndoRedoHandler.getInstance().undo();return null;});requireUnchanged(fixture,original,originalUndo);
        onEventThread(()->{UndoRedoHandler.getInstance().redo();return null;});if(!applied.equals(wayState(fixture)))throw new IllegalStateException("Original native Redo changed its stored geometry");
        onEventThread(()->{UndoRedoHandler.getInstance().undo();return null;});requireUnchanged(fixture,original,originalUndo);
    }
    private static void exerciseOriginalNativeStaleInspection(AlignWayAction action,PreviewSessionController<?> session,PublicGuiSmokeFixture fixture,List<String> original,int originalUndo) throws Exception {
        var opened=new CompletableFuture<JDialog>();var modal=new AtomicBoolean();
        AWTEventListener listener=event->{if(event instanceof WindowEvent e&&e.getID()==WindowEvent.WINDOW_OPENED&&e.getWindow() instanceof JDialog dialog){
            if(isPreview(dialog))opened.complete(dialog);else if(dialog.isModal()){modal.set(true);SwingUtilities.invokeLater(dialog::dispose);}
        }};
        Toolkit.getDefaultToolkit().addAWTEventListener(listener,AWTEvent.WINDOW_EVENT_MASK);String color=Config.getPref().get("wayheatmaptracer.color","hot");
        try {
            onEventThread(()->{action.actionPerformed(null);return null;});var dialog=opened.get(180,TimeUnit.SECONDS);
            onEventThread(()->{
                var computed=(LiveBPreviewService.Computed)session.currentAttempt().result();selectRidgeRoute(dialog,computed);confirmOriginalNative(dialog);
                Config.getPref().put("wayheatmaptracer.color",color.equals("blue")?"hot":"blue");
                org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.begin();
                try{labelledButton(dialog.getContentPane(),"Apply").doClick();if(org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.snapshot().containsKey("apply.commandExecutions"))throw new IllegalStateException("Stale source preflight entered Command.execute");}
                finally{org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.end();}return null;
            });
            if(!onEventThread(()->dialog.isShowing()&&!dialog.isModal()&&!labelledButton(dialog.getContentPane(),"Apply").isEnabled()))throw new IllegalStateException("Stale original native review must remain modeless and inspectable with Apply disabled");
            requireUnchanged(fixture,original,originalUndo);Format15ArchiveReader.read(exportThroughActualMenu());
            onEventThread(()->{dialog.dispatchEvent(new WindowEvent(dialog,WindowEvent.WINDOW_CLOSING));return null;});requireUnchanged(fixture,original,originalUndo);
        }finally{onEventThread(()->{Config.getPref().put("wayheatmaptracer.color",color);return null;});Toolkit.getDefaultToolkit().removeAWTEventListener(listener);}
    }
    private static void exerciseSmoothThenRefineGui(Path report,AlignWayAction action,PreviewSessionController<?> session,
            PublicGuiSmokeFixture fixture,List<String> original,int originalUndo,StageKind continuation) throws Exception {
        var opened=new CompletableFuture<JDialog>();
        AWTEventListener listener=event->{if(event instanceof WindowEvent e&&e.getID()==WindowEvent.WINDOW_OPENED
                &&e.getWindow() instanceof JDialog dialog&&isPreview(dialog))opened.complete(dialog);};
        Toolkit.getDefaultToolkit().addAWTEventListener(listener,AWTEvent.WINDOW_EVENT_MASK);
        try {
            onEventThread(()->{fixture.dataSet().setSelected(fixture.selectedWay());action.actionPerformed(null);return null;});
            var dialog=opened.get(180,TimeUnit.SECONDS);
            var owner=onEventThread(()->{
                if(dialog.isModal())throw new IllegalStateException("Smoothing review must be modeless");
                var attempt=session.currentAttempt();if(attempt!=null&&attempt.result() instanceof LiveBPreviewService.Computed computed)selectRidgeRoute(dialog,computed);
                return (LegacyRefinementWorkflow)dialog.getRootPane().getClientProperty("wayheatmaptracer.refinementOwner");
            });
            var parentGeometry=onEventThread(owner::selectedGeometry);var parentChoice=onEventThread(owner::selectedChoiceIdentity);
            int calls=owner.actualNativeCalls();var source=ManagedTileRuntime.initializedCoordinator().stats();
            clickAndAwaitStage(report,dialog,owner,"smooth",StageKind.SMOOTH);
            var medium=onEventThread(()->{
                var stage=owner.currentStage();
                if(!stage.parentChoice().orElseThrow().value().equals(parentChoice)||owner.actualNativeCalls()!=calls
                        ||!ManagedTileRuntime.initializedCoordinator().stats().equals(source)
                        ||owner.currentNative().orElseThrow().smoothingReceipt().isEmpty())throw new IllegalStateException("Actual smoothing changed source/native work or parent ownership");
                requiredNamed(dialog.getContentPane(),"undo",javax.swing.JButton.class).doClick();
                if(!parentGeometry.equals(owner.selectedGeometry())||!parentChoice.equals(owner.selectedChoiceIdentity()))throw new IllegalStateException("Smoothing Undo lost its exact selected parent");
                requiredNamed(dialog.getContentPane(),"redo",javax.swing.JButton.class).doClick();
                if(owner.currentStage()!=stage)throw new IllegalStateException("Smoothing Redo recomputed its stage");return stage;
            });
            var smooth=selectStrongAndAwait(dialog,owner,medium);
            onEventThread(()->{
                if(!smooth.parent().equals(medium.parent())||!smooth.parentChoice().equals(medium.parentChoice())||owner.actualNativeCalls()!=calls
                        ||!ManagedTileRuntime.initializedCoordinator().stats().equals(source))throw new IllegalStateException("Strong must replace Medium from the same unsmoothed source/parent without inference or capture");
                requiredNamed(dialog.getContentPane(),"undo",javax.swing.JButton.class).doClick();
                if(!parentGeometry.equals(owner.selectedGeometry())||!parentChoice.equals(owner.selectedChoiceIdentity()))throw new IllegalStateException("Strong Undo lost the pinned unsmoothed parent");
                requiredNamed(dialog.getContentPane(),"redo",javax.swing.JButton.class).doClick();
                if(owner.currentStage()!=smooth)throw new IllegalStateException("Strong Redo did not restore its exact cached publication");return null;
            });
            requireUnchanged(fixture,original,originalUndo);
            try{Format15ArchiveReader.read(exportThroughActualMenu());}
            catch(Exception unavailable){throw new IllegalStateException("Actual smoothing export unavailable: "+fixedFailureBoundary(onEventThread(owner::computationEvidence)),unavailable);}
            clickAndAwaitStage(report,dialog,owner,continuation==StageKind.REFINE_A?"refineA":"refineB",continuation);
            var plan=onEventThread(()->{
                if(!owner.currentStage().parent().orElseThrow().equals(smooth.id())
                        ||!owner.currentStage().parentChoice().orElseThrow().equals(smooth.selected())||owner.actualNativeCalls()<=calls)
                    throw new IllegalStateException("Post-smoothing refinement did not use its displayed smoothed parent");
                return owner.currentNative().orElseThrow().plan();
            });
            requireUnchanged(fixture,original,originalUndo);
            boolean legacy="legacy-review".equals(dialog.getName());
            var confirm=onEventThread(()->{var button=legacy?requiredNamed(dialog.getContentPane(),"legacy-confirm",javax.swing.JButton.class):labelledButton(dialog.getContentPane(),"Confirm review");return button.isVisible()&&button.isEnabled();});
            if(confirm){if(legacy)confirmStepwise(dialog);else confirmStepwiseNative(dialog);}
            onEventThread(()->{var apply=labelledButton(dialog.getContentPane(),"Apply");if(!apply.isEnabled())throw new IllegalStateException("Post-smoothing native child is not applicable");apply.doClick();return null;});
            if(onEventThread(dialog::isDisplayable)||UndoRedoHandler.getInstance().getUndoCommands().size()!=originalUndo+1)throw new IllegalStateException("Smoothing chain did not publish exactly one command");
            var expected=plan.finalPreviewWays().get(plan.selectedWayKey());
            if(!expected.equals(fixture.selectedWay().getNodes().stream().map(node->new GeographicPoint(node.lat(),node.lon())).toList()))throw new IllegalStateException("Smoothing chain command differs from its stored plan");
            var applied=wayState(fixture);onEventThread(()->{UndoRedoHandler.getInstance().undo();return null;});requireUnchanged(fixture,original,originalUndo);
            onEventThread(()->{UndoRedoHandler.getInstance().redo();return null;});if(!applied.equals(wayState(fixture)))throw new IllegalStateException("Smoothing chain Redo changed stored geometry");
            onEventThread(()->{UndoRedoHandler.getInstance().undo();return null;});requireUnchanged(fixture,original,originalUndo);
        }finally{Toolkit.getDefaultToolkit().removeAWTEventListener(listener);}
    }
    private static org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.Stage selectStrongAndAwait(
            JDialog dialog,LegacyRefinementWorkflow owner,org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.Stage medium) throws Exception {
        var ready=new CompletableFuture<org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.Stage>();
        Runnable check=()->{
            if(owner.busy()){requireComputingView(dialog);return;}
            var stage=owner.currentStage();
            if(stage.kind()==StageKind.SMOOTH&&!stage.id().equals(medium.id())&&stage.smoothingInput().orElseThrow().preset().equals("Strong"))ready.complete(stage);
            else ready.completeExceptionally(new IllegalStateException("Strong replacement retained its prior stage: "+fixedFailureBoundary(owner.computationEvidence())));
        };
        java.beans.PropertyChangeListener listener=event->SwingUtilities.invokeLater(check);
        try {
            onEventThread(()->{
                requiredNamed(dialog.getContentPane(),"stage",javax.swing.JLabel.class).addPropertyChangeListener(listener);
                requiredNamed(dialog.getContentPane(),"refineA",javax.swing.JButton.class).addPropertyChangeListener(listener);
                var strength=requiredNamed(dialog.getContentPane(),"strength",JComboBox.class);
                if(!strength.isEnabled())throw new IllegalStateException("Actual smoothing strength control is disabled");
                strength.setSelectedItem(org.openstreetmap.josm.plugins.wayheatmaptracer.ui.RefinementControls.Strength.STRONG);
                if(owner.busy())requireComputingView(dialog);SwingUtilities.invokeLater(check);return null;
            });return ready.get(180,TimeUnit.SECONDS);
        }finally{onEventThread(()->{requiredNamed(dialog.getContentPane(),"stage",javax.swing.JLabel.class).removePropertyChangeListener(listener);requiredNamed(dialog.getContentPane(),"refineA",javax.swing.JButton.class).removePropertyChangeListener(listener);return null;});}
    }
    private static void confirmStepwiseNative(JDialog dialog) throws Exception {
        AWTEventListener listener=event->{if(event instanceof WindowEvent e&&e.getID()==WindowEvent.WINDOW_OPENED&&e.getWindow() instanceof JDialog modal&&modal.isModal()){
            var panes=new ArrayList<javax.swing.JOptionPane>();collectOptionPanes(modal.getContentPane(),panes);
            if(panes.size()==1&&panes.get(0).getOptionType()==javax.swing.JOptionPane.YES_NO_OPTION)SwingUtilities.invokeLater(()->panes.get(0).setValue(javax.swing.JOptionPane.YES_OPTION));
        }};
        Toolkit.getDefaultToolkit().addAWTEventListener(listener,AWTEvent.WINDOW_EVENT_MASK);
        try{onEventThread(()->{labelledButton(dialog.getContentPane(),"Confirm review").doClick();return null;});}
        finally{Toolkit.getDefaultToolkit().removeAWTEventListener(listener);}
    }
    private static JMenuItem diagnosticsExportItem(javax.swing.JMenu menu) {
        for(Component component:menu.getMenuComponents()) {
            if(component instanceof javax.swing.JMenu child&&child.isEnabled()){var found=diagnosticsExportItem(child);if(found!=null)return found;}
            if(component instanceof JMenuItem item&&item.getAction() instanceof org.openstreetmap.josm.plugins.wayheatmaptracer.actions.ExportDiagnosticsAction&&item.isEnabled())return item;
        }
        return null;
    }
    private static Path exportThroughActualMenu() throws Exception {
        var exported=new CompletableFuture<Path>();
        AWTEventListener listener=event->{if(event instanceof WindowEvent e&&e.getID()==WindowEvent.WINDOW_OPENED&&e.getWindow() instanceof JDialog dialog&&dialog.isModal()) {
            var fields=new ArrayList<javax.swing.JTextField>();collectTextFields(dialog.getContentPane(),fields);
            if(fields.size()==1&&!fields.get(0).isEditable()&&fields.get(0).getText().endsWith(".zip"))exported.complete(Path.of(fields.get(0).getText()));
            else exported.completeExceptionally(new IllegalStateException("Actual diagnostics menu did not show its exported file"));
            SwingUtilities.invokeLater(dialog::dispose);
        }};
        Toolkit.getDefaultToolkit().addAWTEventListener(listener,AWTEvent.WINDOW_EVENT_MASK);
        try{onEventThread(()->{var item=diagnosticsExportItem(MainApplication.getMenu().moreToolsMenu);if(item==null)throw new IllegalStateException("Actual diagnostics menu is unavailable");item.doClick();return null;});return exported.get(30,TimeUnit.SECONDS);}
        finally{Toolkit.getDefaultToolkit().removeAWTEventListener(listener);}
    }
    private static void collectTextFields(Container parent,List<javax.swing.JTextField> fields) {
        for(Component child:parent.getComponents()){if(child instanceof javax.swing.JTextField field)fields.add(field);else if(child instanceof Container nested)collectTextFields(nested,fields);}
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
            var routeOwner=choice.owner();
            int routeIndex=choice.localRouteIndex();
            if (routeOwner.request().engine()!=owner.request().engine()
                    || routeIndex<0||routeIndex>=routeOwner.pipeline().routes().size()
                    || !routeOwner.pipeline().routes().get(routeIndex).hypothesis().id().equals(choice.candidate().id())
                    || !ids.add(choice.reviewIdentity())) {
                throw new IllegalStateException("Expected distinct routes bound to their actual production owners");
            }
            if (engine == TrackerMode.LEGACY_THEN_B && routeOwner.pipeline().routes().get(routeIndex).hypothesis()
                    .diagnostics().getOrDefault("hasBRefinement", 0.0) != 1.0) continue;
            var assessment = new ModernSingleWayEditPlanAdapter().assess(routeOwner, routeIndex);
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
