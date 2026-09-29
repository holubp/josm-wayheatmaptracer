package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.DefaultListCellRenderer;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.help.HelpUtil;
import org.openstreetmap.josm.gui.layer.ImageryLayer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.config.PluginPreferences;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.DiagnosticsRegistry;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.LastSlideDebugBundle;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15Bundle;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15ProductionBundleFactory;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15ProductionBundleFactory.IntervalArtifactStatus;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15ProductionBundleFactory.IntervalSourceReceipt;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.FrozenReplayInput;
import org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.AggregateIntensityLayer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.HeatmapLayerResolver;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentSourceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateAssessment;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateReviewConfirmation;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateGeometryCleanup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateRating;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ModernAlignmentInvocation;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoveryPermissions;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.JunctionPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TracingSettings;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ValidationReport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.AlignmentJob;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.AlignmentService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.SelectionIntegrity;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.TileHeatmapSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.ManagedModernPreviewSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.PreviewSessionController;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.LiveNetworkSnapshotValidator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.NetworkSnapshotCapture;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.ManualJunctionEligibility;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.SelectedWayIntervalPartitioner;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.FixedIntervalEditPlanComposer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.IntervalTraceBatch;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernSingleWayEditPlanAdapter;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.CredentialSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileRuntime;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration;
import org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.ManagedHeatmapLayer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.VisibleSourceEpoch;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchCoordinator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.SelectionResolver;
import org.openstreetmap.josm.plugins.wayheatmaptracer.ui.PreviewOverlay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.ui.PreviewReviewState;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ApplyAlignmentEditPlanCommand;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.VisibleSourceLockedApplyValidator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ManagedSourceLockedApplyValidator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.LockedApplyValidator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ManagedSourceReceipt;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.MoveNodesCommand;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.PluginLog;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ReplaceWaySegmentCommand;
import org.openstreetmap.josm.tools.GBC;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * JOSM action that samples the selected way against the heatmap, opens candidate preview, and applies the chosen result.
 */
public class AlignWayAction extends JosmAction {
    static final int MAXIMUM_PREVIEW_FAILURE_CHARACTERS = 1_200;
    private static final String PREVIEW_FAILURE_LOG_SUFFIX = "…\n\nSee the JOSM log for full details.";
    private static final String[] RATING_VALUES = {"", "++", "+", "0", "-", "--"};
    /** Largest half-width offered by ordinary search-edge recovery. */
    private static final double MAX_ORDINARY_SEARCH_HALF_WIDTH_METERS = 14.0;
    private static final String FEATURE_OFF_THE_LINE = "off-the-line";
    private static final String FEATURE_JUMPING = "jumping";
    private static final String FEATURE_UNNECESSARY_KINKS = "unnecessary-kinks";
    private static final String FEATURE_BAD_JUNCTION_SHAPES = "bad-junction-shapes";


    /** Production route selected by the ordinary action before source acquisition. */
    enum OrdinaryPipeline { LEGACY_COMPATIBILITY, MODERN_VISIBLE, MODERN_MANAGED }

    /** Immutable ordinary-action route; modern routes retain their complete frozen invocation. */
    record OrdinaryRoute(OrdinaryPipeline pipeline, ModernAlignmentInvocation invocation) {
        OrdinaryRoute {
            Objects.requireNonNull(pipeline, "pipeline");
            if ((pipeline == OrdinaryPipeline.LEGACY_COMPATIBILITY) != (invocation == null)) {
                throw new IllegalArgumentException("Ordinary route and modern invocation disagree");
            }
        }
    }

    /** Complete source-routing decision used directly by the ordinary action. */
    record OrdinaryActionRouting<T>(OrdinaryRoute route, T visibleSource) {
        OrdinaryActionRouting {
            Objects.requireNonNull(route, "route");
            if ((route.pipeline() == OrdinaryPipeline.MODERN_VISIBLE) != (visibleSource != null)) {
                if (route.pipeline() != OrdinaryPipeline.LEGACY_COMPATIBILITY) {
                    throw new IllegalArgumentException("Ordinary action route and visible source disagree");
                }
            }
        }
    }

    /** Captures the external visible pixels for one frozen ordinary invocation. */
    @FunctionalInterface
    interface OrdinaryVisibleRasterCapture<S> {
        LiveBPreviewService.VisibleRaster capture(S source,
                ModernAlignmentInvocation invocation, RecoveryPermissions permissions);
    }

    /** Acquires the external managed pixels for one EDT-captured ordinary seed. */
    @FunctionalInterface
    interface OrdinaryManagedRasterAcquire {
        ManagedModernPreviewSource.Raster acquire(LiveBPreviewService.ManagedCaptureSeed seed,
                ModernAlignmentInvocation invocation, AlignmentJob.JobContext context) throws Exception;
    }

    /** Production ordinary capture, compute, and current-owner publication assembly. */
    static final class OrdinaryModernAttemptAssembly {
        private final LiveBPreviewService previewService = new LiveBPreviewService();

        <S> AlignmentJob.StartResult<LiveBPreviewService.Computed> start(
                PreviewSessionController<LiveBPreviewService.Computed> session,
                PreviewSessionController.Owner owner, OrdinaryActionRouting<S> routing,
                DataSet dataSet, SelectionContext selection, String sourceIdentity,
                OrdinaryVisibleRasterCapture<S> visibleCapture,
                OrdinaryManagedRasterAcquire managedAcquire,
                AlignmentJob.PreviewPublisher<LiveBPreviewService.Computed> publisher) {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(routing, "routing");
            Objects.requireNonNull(dataSet, "dataSet");
            Objects.requireNonNull(selection, "selection");
            Objects.requireNonNull(sourceIdentity, "sourceIdentity");
            ModernAlignmentInvocation invocation = Objects.requireNonNull(
                    routing.route().invocation(), "Ordinary modern invocation");
            RecoveryPermissions permissions = invocation.recovery().toPermissions();
            return session.startDetached(owner, () -> switch (routing.route().pipeline()) {
                case MODERN_VISIBLE -> {
                    LiveBPreviewService.VisibleRaster raster = Objects.requireNonNull(
                            visibleCapture.capture(routing.visibleSource(), invocation, permissions),
                            "visible ordinary raster");
                    LiveBPreviewService.Captured captured = previewService.capture(dataSet,
                            selection, raster, invocation.config(), true, permissions);
                    yield new AlignmentJob.CapturedAttempt<>(snapshot(captured, sourceIdentity),
                            new OrdinaryCapturedSource(invocation, captured, null));
                }
                case MODERN_MANAGED -> {
                    LiveBPreviewService.ManagedCaptureSeed seed = previewService.captureManagedSeed(
                            dataSet, selection, invocation.config(), sourceIdentity, permissions);
                    yield new AlignmentJob.CapturedAttempt<>(snapshot(seed, sourceIdentity),
                            new OrdinaryCapturedSource(invocation, null, seed));
                }
                case LEGACY_COMPATIBILITY -> throw new IllegalArgumentException(
                        "Legacy compatibility alignment cannot enter the modern attempt assembly");
            }, (captured, context) -> {
                if (captured.visible() != null) {
                    return previewService.compute(captured.visible(), context);
                }
                context.checkpoint();
                ManagedModernPreviewSource.Raster raster = managedAcquire.acquire(
                        captured.managed(), captured.invocation(), context);
                return previewService.compute(
                        previewService.attachManagedRaster(captured.managed(), raster), context);
            }, publisher);
        }

        private static AlignmentJob.AttemptSnapshot snapshot(
                LiveBPreviewService.Captured captured, String sourceIdentity) {
            return new AlignmentJob.AttemptSnapshot(captured.network().snapshotId(), sourceIdentity,
                    captured.settingsHash(), captured.network().canonicalHash());
        }

        private static AlignmentJob.AttemptSnapshot snapshot(
                LiveBPreviewService.ManagedCaptureSeed seed, String sourceIdentity) {
            return new AlignmentJob.AttemptSnapshot(seed.network().snapshotId(), sourceIdentity,
                    seed.settingsHash(), seed.network().canonicalHash());
        }

        private record OrdinaryCapturedSource(ModernAlignmentInvocation invocation,
                LiveBPreviewService.Captured visible,
                LiveBPreviewService.ManagedCaptureSeed managed) {
            OrdinaryCapturedSource {
                Objects.requireNonNull(invocation, "invocation");
                if ((visible == null) == (managed == null)) {
                    throw new IllegalArgumentException(
                            "Ordinary attempt requires exactly one detached source capture");
                }
            }
        }
    }

    /** Session-local route choices and exact composed review state for one frozen interval batch. */
    public static final class IntervalPreviewState {
        private final IntervalTraceBatch batch;
        private final FixedIntervalEditPlanComposer composer = new FixedIntervalEditPlanComposer();
        private Map<Integer, Integer> routeChoices = Map.of();
        private FixedIntervalEditPlanComposer.Assessment assessment;
        private PreviewReviewState review;

        /** Composes the initial ranked routes from the immutable production batch. */
        public IntervalPreviewState(IntervalTraceBatch batch) {
            this.batch = Objects.requireNonNull(batch, "batch");
            recompose();
        }

        /** Returns the sole frozen inference batch for this preview session. */
        public IntervalTraceBatch batch() { return batch; }
        /** Returns selected production route indexes keyed by ordered interval index. */
        public Map<Integer, Integer> routeChoices() { return routeChoices; }
        /** Returns the current complete selected and affected-way assessment. */
        public FixedIntervalEditPlanComposer.Assessment assessment() { return assessment; }
        /** Returns the exact current plan review, or null when no plan can Apply. */
        public PreviewReviewState review() { return review; }

        /** Selects an existing production route and clears confirmation, even for equal geometry. */
        public void choose(int intervalIndex, int routeIndex) {
            Map<Integer, Integer> next = new LinkedHashMap<>(routeChoices);
            next.put(intervalIndex, routeIndex);
            FixedIntervalEditPlanComposer.Assessment recomposed = composer.compose(batch, next);
            routeChoices = Map.copyOf(next);
            assessment = recomposed;
            review = unconfirmedReview(recomposed);
        }

        /** Confirms exactly the currently composed complete plan. */
        public void confirmReview() {
            if (review == null) {
                throw new IllegalStateException("No applicable composed preview can be reviewed");
            }
            review = review.confirm();
        }

        /** Returns whether the composed plan has the review state required for Apply. */
        public boolean applyAvailable() {
            return assessment.applyAvailable() && review != null && review.canApply();
        }

        /** Revalidates from retained production routes before the command is constructed. */
        public AlignmentEditPlan currentPlanForApply() {
            FixedIntervalEditPlanComposer.Assessment current = composer.compose(batch, routeChoices);
            if (!current.applyAvailable() || review == null || !review.canApply()) {
                throw new IllegalStateException("The composed interval preview is not applicable");
            }
            AlignmentEditPlan currentPlan = current.plan().orElseThrow();
            PreviewReviewState currentReview = PreviewReviewState.fromEditPlan(
                    choiceIdentity(), currentPlan);
            if (!review.equals(currentReview) && !review.matches(currentReview)) {
                throw new IllegalStateException("The reviewed interval plan is stale");
            }
            return currentPlan;
        }

        private void recompose() {
            assessment = composer.compose(batch, routeChoices);
            review = unconfirmedReview(assessment);
        }

        private PreviewReviewState unconfirmedReview(FixedIntervalEditPlanComposer.Assessment value) {
            return value.applyAvailable()
                    ? PreviewReviewState.fromEditPlan(choiceIdentity(), value.plan().orElseThrow())
                    : null;
        }

        private String choiceIdentity() {
            return "intervals:" + batch.runs().size() + ":" + java.util.stream.IntStream
                    .range(0, batch.runs().size())
                    .mapToObj(index -> index + "=" + routeChoices.getOrDefault(index, 0))
                    .reduce((left, right) -> left + "," + right).orElse("");
        }
    }

    /** Stateless alignment orchestrator shared by action invocations. */
    private final AlignmentService alignmentService = new AlignmentService();
    /** Map overlay used for candidate preview. */
    private final PreviewOverlay overlay = PreviewOverlay.getInstance();
    /** Plugin-wide owner for modern alignment work, windows, and overlay cleanup. */
    private final PreviewSessionController<LiveBPreviewService.Computed> livePreviewSession;
    /** Whether this action created and therefore closes its private fallback preview session. */
    private final boolean ownsLivePreviewSession;
    /** Stateless exact capture and production B pipeline adapter. */
    private final LiveBPreviewService livePreviewService = new LiveBPreviewService();
    /** Real ordinary capture/compute assembly shared by every modern engine. */
    private final OrdinaryModernAttemptAssembly ordinaryAttemptAssembly =
            new OrdinaryModernAttemptAssembly();
    /** Optional shortcut-specific mode override, or null for configured behavior. */
    private final AlignmentMode forcedAlignmentMode;
    /** Explicit session-local modern engine, or null for ordinary alignment. */
    private final TrackerMode forcedLivePreviewEngine;
    /** Explicit selected managed-source preview, or false for visible and ordinary actions. */
    private final boolean forcedManagedPreview;
    /** Current modeless preview dialog, if one is open. */
    private JDialog activePreviewDialog;
    /** This action's current plugin-wide preview authority, if any. */
    private PreviewSessionController.Owner activeLivePreviewOwner;

    /**
     * Creates the default alignment action using the mode configured in plugin settings.
     */
    public AlignWayAction() {
        this(null, null, false, new PreviewSessionController<>(SwingUtilities::invokeLater), true);
    }

    /** Creates the default action with a plugin-owned shared modern-attempt authority. */
    public AlignWayAction(PreviewSessionController<LiveBPreviewService.Computed> session) {
        this(null, null, false, session, false);
    }

    /**
     * Creates an alignment action with an optional one-shot mode override.
     *
     * @param forcedAlignmentMode alignment mode to force for this action, or {@code null} to use settings
     */
    public AlignWayAction(AlignmentMode forcedAlignmentMode) {
        this(forcedAlignmentMode, null, false,
                new PreviewSessionController<>(SwingUtilities::invokeLater), true);
    }

    /** Creates a mode override with a plugin-owned shared modern-attempt authority. */
    public AlignWayAction(AlignmentMode forcedAlignmentMode,
            PreviewSessionController<LiveBPreviewService.Computed> session) {
        this(forcedAlignmentMode, null, false, session, false);
    }

    /** Creates the explicit session-local visible-source Engine A preview action. */
    public static AlignWayAction experimentalCorridorAwareVisiblePreview() {
        return new AlignWayAction(null, TrackerMode.CORRIDOR_AWARE, false,
                new PreviewSessionController<>(SwingUtilities::invokeLater), true);
    }

    /** Creates Engine A visible preview with the plugin-owned shared session. */
    public static AlignWayAction experimentalCorridorAwareVisiblePreview(
            PreviewSessionController<LiveBPreviewService.Computed> session) {
        return new AlignWayAction(null, TrackerMode.CORRIDOR_AWARE, false, session, false);
    }

    /** Creates the explicit session-local visible-source Engine B preview action. */
    public static AlignWayAction experimentalProbabilisticVisiblePreview() {
        return new AlignWayAction(null, TrackerMode.PROBABILISTIC, false,
                new PreviewSessionController<>(SwingUtilities::invokeLater), true);
    }

    /** Creates Engine B visible preview with the plugin-owned shared session. */
    public static AlignWayAction experimentalProbabilisticVisiblePreview(
            PreviewSessionController<LiveBPreviewService.Computed> session) {
        return new AlignWayAction(null, TrackerMode.PROBABILISTIC, false, session, false);
    }

    /** Creates the explicit session-local visible-source Directional Image preview action. */
    public static AlignWayAction experimentalDirectionalImageVisiblePreview() {
        return new AlignWayAction(null, TrackerMode.DIRECTIONAL_IMAGE, false,
                new PreviewSessionController<>(SwingUtilities::invokeLater), true);
    }

    /** Creates Directional Image visible preview with the plugin-owned shared session. */
    public static AlignWayAction experimentalDirectionalImageVisiblePreview(
            PreviewSessionController<LiveBPreviewService.Computed> session) {
        return new AlignWayAction(null, TrackerMode.DIRECTIONAL_IMAGE, false, session, false);
    }

    /** Creates the explicit session-local visible-source Hybrid A+B preview action. */
    public static AlignWayAction experimentalHybridVisiblePreview() {
        return new AlignWayAction(null, TrackerMode.HYBRID, false,
                new PreviewSessionController<>(SwingUtilities::invokeLater), true);
    }

    /** Creates Hybrid A+B visible preview with the plugin-owned shared session. */
    public static AlignWayAction experimentalHybridVisiblePreview(
            PreviewSessionController<LiveBPreviewService.Computed> session) {
        return new AlignWayAction(null, TrackerMode.HYBRID, false, session, false);
    }

    /** Creates the explicit session-local managed-source Engine A preview action. */
    public static AlignWayAction experimentalCorridorAwareManagedPreview() {
        return new AlignWayAction(null, TrackerMode.CORRIDOR_AWARE, true,
                new PreviewSessionController<>(SwingUtilities::invokeLater), true);
    }

    /** Creates Engine A managed preview with the plugin-owned shared session. */
    public static AlignWayAction experimentalCorridorAwareManagedPreview(
            PreviewSessionController<LiveBPreviewService.Computed> session) {
        return new AlignWayAction(null, TrackerMode.CORRIDOR_AWARE, true, session, false);
    }

    /** Creates the explicit session-local managed-source Engine B preview action. */
    public static AlignWayAction experimentalProbabilisticManagedPreview() {
        return new AlignWayAction(null, TrackerMode.PROBABILISTIC, true,
                new PreviewSessionController<>(SwingUtilities::invokeLater), true);
    }

    /** Creates Engine B managed preview with the plugin-owned shared session. */
    public static AlignWayAction experimentalProbabilisticManagedPreview(
            PreviewSessionController<LiveBPreviewService.Computed> session) {
        return new AlignWayAction(null, TrackerMode.PROBABILISTIC, true, session, false);
    }

    private AlignWayAction(AlignmentMode forcedAlignmentMode, TrackerMode forcedLivePreviewEngine,
            boolean forcedManagedPreview,
            PreviewSessionController<LiveBPreviewService.Computed> livePreviewSession,
            boolean ownsLivePreviewSession) {
        super(
            actionName(forcedAlignmentMode, forcedLivePreviewEngine, forcedManagedPreview),
            null,
            actionTooltip(forcedAlignmentMode, forcedLivePreviewEngine, forcedManagedPreview),
            shortcut(forcedAlignmentMode, forcedLivePreviewEngine, forcedManagedPreview),
            true
        );
        if (forcedLivePreviewEngine != null && forcedLivePreviewEngine != TrackerMode.CORRIDOR_AWARE
                && forcedLivePreviewEngine != TrackerMode.PROBABILISTIC
                && forcedLivePreviewEngine != TrackerMode.HYBRID
                && forcedLivePreviewEngine != TrackerMode.DIRECTIONAL_IMAGE) {
            throw new IllegalArgumentException("Only explicit Corridor-aware A, Probabilistic B, "
                    + "visible Hybrid A+B, or visible Directional Image preview is supported");
        }
        if ((forcedLivePreviewEngine == TrackerMode.HYBRID
                || forcedLivePreviewEngine == TrackerMode.DIRECTIONAL_IMAGE)
                && forcedManagedPreview) {
            throw new IllegalArgumentException("Hybrid and Directional Image previews are visible-source only");
        }
        this.forcedAlignmentMode = forcedAlignmentMode;
        this.forcedLivePreviewEngine = forcedLivePreviewEngine;
        this.forcedManagedPreview = forcedManagedPreview;
        this.livePreviewSession = Objects.requireNonNull(livePreviewSession, "livePreviewSession");
        this.ownsLivePreviewSession = ownsLivePreviewSession;
        putValue("help", HelpUtil.ht("/Plugin/WayHeatmapTracer"));
    }

    /**
     * Returns the one-shot mode override used by this action.
     *
     * @return forced mode, or {@code null} when settings control the mode
     */
    public AlignmentMode forcedAlignmentMode() {
        return forcedAlignmentMode;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (activePreviewDialog != null && activePreviewDialog.isDisplayable()) {
            AlignmentJob.Attempt<LiveBPreviewService.Computed> attempt = livePreviewSession.currentAttempt();
            if (attempt != null && !attempt.state().terminal()) {
                if (livePreviewSession.close(activeLivePreviewOwner)) {
                    overlay.hide();
                }
                activePreviewDialog.dispose();
                PluginLog.endSlideSession();
            } else {
                activePreviewDialog.toFront();
            }
            return;
        }
        ManagedHeatmapConfig config = null;
        String diagnosticAttemptIdentity = beginDiagnosticAttempt();
        String diagnosticSourceLineage = "unavailable";
        PluginLog.beginSlideSession();
        try {
            PluginLog.verbose("Align Way to Heatmap invoked.");
            DataSet dataSet = MainApplication.getLayerManager().getEditDataSet();
            if (dataSet == null) {
                recordModernUnavailable("failed", "unavailable", diagnosticAttemptIdentity);
                PluginLog.endSlideSession();
                showError(tr("No editable data layer is active."));
                return;
            }
            if (MainApplication.getMap() == null || MainApplication.getMap().mapView == null) {
                recordModernUnavailable("failed", "unavailable", diagnosticAttemptIdentity);
                PluginLog.endSlideSession();
                showError(tr("No map view is available."));
                return;
            }

            ManagedHeatmapConfig persistedConfig = PluginPreferences.load();
            TracingSettings tracing = PluginPreferences.loadTracingSettings();
            config = effectiveConfig(forcedLivePreviewEngine == null
                ? persistedConfig.withTrackerMode(tracing.engine()) : persistedConfig);
            boolean legacySelection = !config.trackerMode().capabilities().requiresEvidenceSnapshot();
            SelectionContext selection = SelectionResolver.resolve(dataSet,
                    legacySelection && config.adjustJunctionNodes());
            if (!config.allowUndownloadedAlignment()) {
                requireDownloadedAreaCoverage(selection, dataSet);
            } else {
                PluginLog.verbose("Downloaded-area coverage checks are disabled by settings.");
            }
            GeometryCleanupConfig cleanupConfig = PluginPreferences.loadGeometryCleanup();
            AlignmentConfig persistedSlideConfig = new AlignmentConfig(persistedConfig, cleanupConfig);
            AlignmentConfig slideConfig = new AlignmentConfig(config, cleanupConfig);
            OrdinaryActionRouting<ImageryLayer> ordinaryRouting = forcedLivePreviewEngine == null
                    ? resolveOrdinaryAction(tracing, slideConfig, HeatmapLayerResolver::resolve,
                            () -> HeatmapLayerResolver.resolveOptional().orElse(null)) : null;
            OrdinaryRoute ordinaryRoute = ordinaryRouting == null ? null : ordinaryRouting.route();
            boolean modern = forcedLivePreviewEngine != null
                    || ordinaryRoute.pipeline() != OrdinaryPipeline.LEGACY_COMPATIBILITY;
            AlignmentSourceMode sourceMode = forcedManagedPreview ? AlignmentSourceMode.MANAGED_TILES
                : forcedLivePreviewEngine != null ? AlignmentSourceMode.VISIBLE_LAYER
                : ordinaryRoute.pipeline() == OrdinaryPipeline.MODERN_MANAGED
                    ? AlignmentSourceMode.MANAGED_TILES : AlignmentSourceMode.VISIBLE_LAYER;
            diagnosticSourceLineage = sourceMode == AlignmentSourceMode.MANAGED_TILES
                    ? "managed-tiles" : "visible-layer";
            ImageryLayer imageryLayer = forcedLivePreviewEngine != null
                    ? (forcedManagedPreview ? null : HeatmapLayerResolver.resolve())
                    : ordinaryRouting.visibleSource();
            MapView mapView = MainApplication.getMap().mapView;
            if (modern) {
                RecoveryPermissions recovery = ordinaryRoute == null ? null
                        : ordinaryRoute.invocation().recovery().toPermissions();
                startLiveBPreview(dataSet, selection, imageryLayer, mapView, slideConfig,
                        persistedSlideConfig, tracing, recovery, ordinaryRouting,
                        sourceMode == AlignmentSourceMode.VISIBLE_LAYER,
                        sourceMode == AlignmentSourceMode.MANAGED_TILES,
                        diagnosticAttemptIdentity);
                return;
            }
            AlignmentResult result = alignmentService.align(selection, imageryLayer, mapView, slideConfig);
            updateAggregateIntensityLayer(result, config);
            DiagnosticsRegistry.setLastBundle(LastSlideDebugBundle.fromResult(
                result, initialCandidate(result), initialCandidate(result), "preview-open", PluginLog.currentSlideLog(), Map.of()));

            showCandidatePreview(dataSet, selection, result, slideConfig, imageryLayer, mapView, new LinkedHashMap<>());
        } catch (AlignmentService.AlignmentFailureException ex) {
            overlay.hide();
            if (config != null) {
                updateAggregateIntensityLayer(ex.partialResult(), config);
            }
            Logging.warn("WayHeatmapTracer alignment failed without applying geometry: " + ex.getMessage());
            PluginLog.verbose("Alignment failed without applying geometry: %s", ex.toString());
            if (config != null && config.trackerMode().capabilities().requiresEvidenceSnapshot()) {
                recordModernUnavailable("failed", diagnosticSourceLineage,
                        diagnosticAttemptIdentity);
            } else {
                DiagnosticsRegistry.setLastBundle(LastSlideDebugBundle.fromResult(
                    ex.partialResult(),
                    ex.partialResult().candidates().isEmpty() ? null : ex.partialResult().candidates().get(0),
                    "failed",
                    PluginLog.currentSlideLog()
                ));
            }
            PluginLog.endSlideSession();
            showError(tr("WayHeatmapTracer failed: {0}", ex.getMessage()));
        } catch (Exception ex) {
            recordModernUnavailable("failed", diagnosticSourceLineage,
                    diagnosticAttemptIdentity);
            overlay.hide();
            Logging.error(ex);
            PluginLog.verbose("Alignment failed with exception: %s", ex.toString());
            PluginLog.endSlideSession();
            showError(tr("WayHeatmapTracer failed: {0}", ex.getMessage()));
        }
    }

    private void startLiveBPreview(DataSet dataSet, SelectionContext selection,
            ImageryLayer imageryLayer, MapView mapView, AlignmentConfig slideConfig,
            AlignmentConfig persistedSlideConfig, TracingSettings tracingAtCapture,
            RecoveryPermissions recoveryPermissions,
            OrdinaryActionRouting<ImageryLayer> ordinaryRouting,
            boolean explicitVisibleSource, boolean managedSource,
            String diagnosticAttemptIdentity) {
        if (!managedSource) {
            LiveBPreviewService.requireSupported(selection,
                    ProjectionRegistry.getProjection().toCode(), slideConfig, explicitVisibleSource);
        }
        overlay.hide();
        String engineLabel = livePreviewEngineLabel(slideConfig.heatmap().trackerMode());
        JDialog progress = new JDialog(MainApplication.getMainFrame(),
                tr("{0} alignment preview", engineLabel), false);
        JLabel status = new JLabel(tr("Capturing the selected source and computing a {0} preview...",
                engineLabel));
        JButton cancel = new JButton(tr("Cancel"));
        JPanel panel = new JPanel();
        panel.add(status);
        panel.add(cancel);
        progress.setContentPane(panel);
        progress.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        progress.pack();
        progress.setLocationRelativeTo(MainApplication.getMainFrame());
        activePreviewDialog = progress;
        PreviewSessionController.Owner previewOwner = livePreviewSession.open(progress::dispose);
        activeLivePreviewOwner = previewOwner;
        String sourceIdentity = managedSource ? "managed-selected-" + slideConfig.heatmap().color()
                + "-g" + slideConfig.heatmap().cacheBuster() : liveLayerIdentity(imageryLayer);
        String sourceLineage = managedSource ? "managed-tiles" : "visible-layer";
        final TileFetchCoordinator previewSourceOwner = managedSource
                ? ManagedTileRuntime.initializedCoordinator() : null;
        DiagnosticsRegistry.setLastModernBundle(Format15ProductionBundleFactory.createUnavailableLive(
                LastSlideDebugBundle.buildIdentity(), "started", sourceLineage,
                diagnosticAttemptIdentity));
        javax.swing.Timer failureMonitor = new javax.swing.Timer(150, event -> {
            AlignmentJob.Attempt<LiveBPreviewService.Computed> current = livePreviewSession.currentAttempt();
            if (!livePreviewSession.isCurrent(previewOwner)
                    || activePreviewDialog != progress || current == null) {
                ((javax.swing.Timer) event.getSource()).stop();
            } else if (current.state() == AlignmentJob.State.FAILED) {
                ((javax.swing.Timer) event.getSource()).stop();
                boolean closed = livePreviewSession.close(previewOwner);
                progress.dispose();
                if (closed) {
                    String failureReason = current.failureReason() == null ? ""
                            : current.failureReason().toLowerCase(java.util.Locale.ROOT);
                    ManualJunctionEligibility.Reason captureJunction =
                            manualCaptureReason(current.failureReason());
                    recordModernUnavailable(captureJunction != null ? "blocked"
                                    : failureReason.contains("budget")
                                            || failureReason.contains("resource")
                                        ? "resource-limited" : "failed",
                            sourceLineage, diagnosticAttemptIdentity, captureJunction);
                    overlay.hide();
                    PluginLog.endSlideSession();
                    showError(tr("{0} alignment preview failed safely: {1}", engineLabel,
                            current.failureReason()));
                }
            } else if (current.state() == AlignmentJob.State.CANCELLED) {
                ((javax.swing.Timer) event.getSource()).stop();
                closeAndPublishIfCurrent(livePreviewSession, previewOwner, () ->
                        recordModernUnavailable("cancelled", sourceLineage,
                                diagnosticAttemptIdentity));
                progress.dispose();
            }
        });
        progress.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                boolean closed = closeAndPublishIfCurrent(livePreviewSession, previewOwner,
                        () -> recordModernUnavailable("cancelled", sourceLineage,
                                diagnosticAttemptIdentity));
                if (closed) {
                    overlay.hide();
                    PluginLog.endSlideSession();
                }
            }
            @Override public void windowClosed(WindowEvent event) {
                failureMonitor.stop();
                if (activePreviewDialog == progress) {
                    activePreviewDialog = null;
                }
            }
        });
        cancel.addActionListener(event -> progress.dispatchEvent(
                new WindowEvent(progress, WindowEvent.WINDOW_CLOSING)));
        try {
            if (ordinaryRouting != null) {
                final CredentialSnapshot credentials = managedSource
                        ? CredentialSnapshot.fromConfig(slideConfig.heatmap()) : null;
                final TileFetchCoordinator coordinator = previewSourceOwner;
                ordinaryAttemptAssembly.start(livePreviewSession, previewOwner, ordinaryRouting,
                        dataSet, selection, sourceIdentity,
                        (frozenSource, invocation, permissions) ->
                                alignmentService.captureLiveBVisibleRaster(selection, frozenSource,
                                        mapView, invocation.config(), sourceIdentity, permissions),
                        (seed, invocation, context) -> {
                            ManagedModernPreviewSource source = new ManagedModernPreviewSource(coordinator);
                            return source.acquire(ManagedModernPreviewSource.selectedOnly(
                                    seed.sourceGeographic(), invocation.config().heatmap(), sourceIdentity),
                                    credentials, context);
                        }, attempt -> publishLiveBPreview(previewOwner, progress, dataSet, selection,
                                imageryLayer, mapView, slideConfig, persistedSlideConfig,
                                tracingAtCapture, attempt.result(), diagnosticAttemptIdentity,
                                previewSourceOwner));
            } else if (managedSource) {
                final LiveBPreviewService.ManagedCaptureSeed[] seed = new LiveBPreviewService.ManagedCaptureSeed[1];
                final CredentialSnapshot credentials = CredentialSnapshot.fromConfig(slideConfig.heatmap());
                final TileFetchCoordinator coordinator = previewSourceOwner;
                livePreviewSession.start(previewOwner, () -> {
                    seed[0] = recoveryPermissions == null
                            ? livePreviewService.captureManagedSeed(dataSet, selection, slideConfig, sourceIdentity)
                            : livePreviewService.captureManagedSeed(dataSet, selection, slideConfig,
                                    sourceIdentity, recoveryPermissions);
                    return new AlignmentJob.AttemptSnapshot(seed[0].network().snapshotId(), sourceIdentity,
                            seed[0].settingsHash(), seed[0].network().canonicalHash());
                }, (snapshot, context) -> {
                    ManagedModernPreviewSource source = new ManagedModernPreviewSource(coordinator);
                    ManagedModernPreviewSource.Raster raster = source.acquire(
                            ManagedModernPreviewSource.selectedOnly(seed[0].sourceGeographic(),
                                    slideConfig.heatmap(), sourceIdentity), credentials, context);
                    return livePreviewService.compute(livePreviewService.attachManagedRaster(seed[0], raster), context);
                }, attempt -> publishLiveBPreview(previewOwner, progress, dataSet, selection, imageryLayer, mapView,
                        slideConfig, persistedSlideConfig, tracingAtCapture, attempt.result(),
                        diagnosticAttemptIdentity, previewSourceOwner));
            } else {
                livePreviewSession.startDetached(previewOwner, () -> {
                    LiveBPreviewService.VisibleRaster raster = alignmentService.captureLiveBVisibleRaster(
                            selection, imageryLayer, mapView, slideConfig, sourceIdentity,
                            recoveryPermissions);
                    LiveBPreviewService.Captured captured = recoveryPermissions == null
                            ? livePreviewService.capture(dataSet, selection, raster, slideConfig,
                                    explicitVisibleSource)
                            : livePreviewService.capture(dataSet, selection, raster, slideConfig,
                                    explicitVisibleSource, recoveryPermissions);
                    AlignmentJob.AttemptSnapshot snapshot = new AlignmentJob.AttemptSnapshot(
                            captured.network().snapshotId(), sourceIdentity, captured.settingsHash(),
                            captured.network().canonicalHash());
                    return new AlignmentJob.CapturedAttempt<>(snapshot, captured);
                }, (captured, context) -> livePreviewService.compute(captured, context),
                        attempt -> publishLiveBPreview(previewOwner, progress, dataSet, selection, imageryLayer, mapView,
                                slideConfig, persistedSlideConfig, tracingAtCapture, attempt.result(),
                                diagnosticAttemptIdentity, null));
            }
        } catch (RuntimeException exception) {
            ManualJunctionEligibility.Reason captureJunction = exception
                    instanceof LiveBPreviewService.ManualJunctionCaptureException manual
                        ? manual.reason() : null;
            recordModernUnavailable(captureJunction == null ? "failed" : "blocked",
                    sourceLineage, diagnosticAttemptIdentity, captureJunction);
            livePreviewSession.close(previewOwner);
            progress.dispose();
            throw exception;
        }
        failureMonitor.start();
        progress.setVisible(true);
    }

    private void publishLiveBPreview(PreviewSessionController.Owner previewOwner, JDialog progress, DataSet dataSet,
            SelectionContext selection, ImageryLayer imageryLayer, MapView mapView,
            AlignmentConfig slideConfig, AlignmentConfig persistedSlideConfig,
            TracingSettings tracingAtCapture, LiveBPreviewService.Computed computed,
            String diagnosticAttemptIdentity, TileFetchCoordinator previewSourceOwner) {
        if (!livePreviewSession.isCurrent(previewOwner)
                || activePreviewDialog != progress || !progress.isDisplayable()) {
            return;
        }
        if (computed.partitioned()) {
            try {
                requireLiveBCurrent(dataSet, selection, imageryLayer, mapView, slideConfig,
                        persistedSlideConfig, tracingAtCapture, computed.captured(), previewSourceOwner);
                progress.dispose();
                showIntervalPreviewDialog(previewOwner, dataSet, selection, imageryLayer, mapView,
                        slideConfig, persistedSlideConfig, tracingAtCapture, computed,
                        diagnosticAttemptIdentity, previewSourceOwner);
            } catch (RuntimeException exception) {
                boolean closed = closeAndPublishIfCurrent(livePreviewSession, previewOwner, () -> {
                    try {
                        recordIntervalDiagnostics(computed,
                                new IntervalPreviewState(computed.intervalBatch()),
                                IntervalArtifactStatus.FAILED, diagnosticAttemptIdentity);
                    } catch (RuntimeException compositionFailure) {
                        recordModernUnavailable("failed", computed.captured().managedRaster() == null
                                ? "visible-layer" : "managed-tiles", diagnosticAttemptIdentity);
                    }
                });
                progress.dispose();
                if (closed) {
                    overlay.hide();
                    PluginLog.endSlideSession();
                    showError(tr("Interval alignment preview was rejected: {0}", exception.getMessage()));
                }
            }
            return;
        }
        String terminalStatus = "failed";
        ManualJunctionEligibility.Reason terminalManualReason = null;
        try {
            ValidationReport.Disposition initialDisposition = computed.pipeline().routes().isEmpty()
                    ? ValidationReport.Disposition.HARD_BLOCKED
                    : switch (computed.pipeline().routes().get(0).quality().disposition()) {
                        case APPLICABLE -> ValidationReport.Disposition.APPLICABLE;
                        case REVIEW_REQUIRED -> ValidationReport.Disposition.REVIEW_REQUIRED;
                        case HARD_BLOCKED -> ValidationReport.Disposition.HARD_BLOCKED;
                    };
            recordModernDiagnostics(computed, modernPreviewStatus(
                    computed.pipeline().inference().status(), initialDisposition),
                    0, null, false, false,
                    diagnosticAttemptIdentity);
            PluginLog.verbose("Modern support engine=%s source=%s inference=%s states=%d transitions=%d routes=%d counters=%d",
                    computed.request().engine(),
                    computed.captured().managedRaster() == null ? "visible-layer" : "managed-tiles",
                    computed.pipeline().inference().status(),
                    computed.pipeline().inference().evaluatedStates(),
                    computed.pipeline().inference().evaluatedTransitions(),
                    computed.pipeline().routes().size(), computed.counters().size());
            requireLiveBCurrent(dataSet, selection, imageryLayer, mapView, slideConfig, persistedSlideConfig,
                    tracingAtCapture, computed.captured(), previewSourceOwner);
            List<CenterlineCandidate> candidates = livePreviewService.adapt(computed,
                    point -> ProjectionRegistry.getProjection().latlon2eastNorth(
                            new LatLon(point.latitudeDegrees(), point.longitudeDegrees())));
            if (candidates.isEmpty()) {
                terminalStatus = modernPreviewStatus(computed.pipeline().inference().status(),
                        initialDisposition);
                ManualJunctionEligibility.Decision manual = noRouteJunctionDecision(
                        computed.captured());
                terminalManualReason = manual == null ? null : manual.reason();
                throw new IllegalStateException(noPreviewableRouteMessage(computed.captured(),
                        slideConfig.heatmap().trackerMode()));
            }
            progress.dispose();
            showLiveBReadOnlyDialog(previewOwner, dataSet, selection, imageryLayer, mapView,
                    slideConfig, persistedSlideConfig, tracingAtCapture, computed, candidates,
                    diagnosticAttemptIdentity, previewSourceOwner);
        } catch (RuntimeException exception) {
            recordModernDiagnostics(computed, terminalStatus, 0, null, false, false,
                    diagnosticAttemptIdentity, terminalManualReason);
            boolean closed = livePreviewSession.close(previewOwner);
            progress.dispose();
            if (closed) {
                overlay.hide();
                PluginLog.endSlideSession();
                showError(tr("{0} alignment preview was rejected: {1}",
                        livePreviewEngineLabel(slideConfig.heatmap().trackerMode()), exception.getMessage()));
            }
        }
    }

    /** Displays one composer-owned complete preview with a route choice for every slide interval. */
    private void showIntervalPreviewDialog(PreviewSessionController.Owner owner, DataSet dataSet,
            SelectionContext selection, ImageryLayer imageryLayer, MapView mapView,
            AlignmentConfig slideConfig, AlignmentConfig persistedSlideConfig,
            TracingSettings tracingAtCapture, LiveBPreviewService.Computed computed,
            String diagnosticAttemptIdentity, TileFetchCoordinator previewSourceOwner) {
        IntervalPreviewState state = new IntervalPreviewState(computed.intervalBatch());
        JDialog dialog = new JDialog(MainApplication.getMainFrame(),
                tr("Interval Alignment Preview"), false);
        JPanel panel = new JPanel();
        JPanel intervalControls = new JPanel(new java.awt.GridLayout(0, 2));
        List<JComboBox<String>> choices = new java.util.ArrayList<>();
        for (int index = 0; index < state.batch().runs().size(); index++) {
            IntervalTraceBatch.IntervalRun run = state.batch().runs().get(index);
            intervalControls.add(new JLabel(tr("Interval {0} ({1}–{2})", index + 1,
                    run.interval().range().firstIndex(), run.interval().range().lastIndex())));
            String[] routeLabels = run.routes().isEmpty()
                    ? new String[] {tr("No production route")}
                    : java.util.stream.IntStream.range(0, run.routes().size())
                            .mapToObj(route -> route == 0
                                    ? tr("Route {0} (recommended)", route + 1)
                                    : tr("Route {0}", route + 1))
                            .toArray(String[]::new);
            JComboBox<String> choice = new JComboBox<>(routeLabels);
            choice.setEnabled(!run.routes().isEmpty());
            intervalControls.add(choice);
            choices.add(choice);
        }
        JTextArea quality = new JTextArea(8, 78);
        quality.setEditable(false);
        quality.setLineWrap(true);
        quality.setWrapStyleWord(true);
        JScrollPane qualityScroll = new JScrollPane(quality);
        qualityScroll.setPreferredSize(new Dimension(680, 170));
        JButton confirm = new JButton(tr("Confirm review"));
        JButton apply = new JButton(tr("Apply"));
        JButton close = new JButton(tr("Close preview"));
        boolean[] applying = {false};
        boolean[] completed = {false};
        JScrollPane intervalScroll = new JScrollPane(intervalControls);
        intervalScroll.setPreferredSize(new Dimension(680, 180));
        panel.add(intervalScroll);
        panel.add(qualityScroll);
        panel.add(confirm);
        panel.add(apply);
        panel.add(close);
        dialog.setContentPane(panel);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.pack();
        dialog.setLocationRelativeTo(MainApplication.getMainFrame());
        activePreviewDialog = dialog;
        livePreviewSession.replaceWindow(owner, dialog::dispose);

        Runnable refresh = () -> {
            if (!livePreviewSession.isCurrentWindow(owner, dialog, activePreviewDialog,
                    dialog.isDisplayable())) {
                throw new IllegalStateException("The interval preview no longer owns this attempt");
            }
            FixedIntervalEditPlanComposer.Assessment assessment = state.assessment();
            List<EastNorth> selectedPreview = assessment.selectedWayPreview().stream()
                    .map(AlignWayAction::projectGeographic).toList();
            List<EastNorth> source = computed.captured().sourceGeographic().stream()
                    .map(AlignWayAction::projectGeographic).toList();
            CenterlineCandidate carrier = new CenterlineCandidate(
                    "composed-interval-preview", 0.0, List.of(), List.of());
            AlignmentResult display = new AlignmentResult(selection, null, List.of(carrier), source,
                    selectedPreview, List.of(), null, null, List.of(), List.of());
            Map<PrimitiveKey, List<EastNorth>> finalWays = new LinkedHashMap<>();
            if (assessment.plan().isPresent()) {
                assessment.plan().orElseThrow().finalPreviewWays().forEach((key, geographic) ->
                        finalWays.put(key, geographic.stream().map(AlignWayAction::projectGeographic)
                                .toList()));
            } else {
                finalWays.put(state.batch().fullRequest().selectedWayKey(), selectedPreview);
            }
            ValidationReport.Disposition disposition = assessment.plan().isPresent()
                    ? assessment.plan().orElseThrow().validation().disposition()
                    : ValidationReport.Disposition.HARD_BLOCKED;
            overlay.show(selection, display, carrier, switch (disposition) {
                case APPLICABLE -> CandidateAssessment.Disposition.APPLICABLE;
                case REVIEW_REQUIRED -> CandidateAssessment.Disposition.REVIEW_REQUIRED;
                case HARD_BLOCKED -> CandidateAssessment.Disposition.HARD_BLOCKED;
            }, state.review() != null && state.review().confirmed(),
                    PluginPreferences.isDebugEnabled(), finalWays);
            ModernApplyPreflight preflight = modernApplyPreflight(computed.captured(), slideConfig);
            boolean sourceApply = intervalApplySourceAvailable(computed.captured());
            quality.setText(intervalPreviewSummary(state)
                    + "\nApply: " + (preflight == ModernApplyPreflight.READY && sourceApply
                            && state.applyAvailable() ? "available" : "unavailable")
                    + (preflight == ModernApplyPreflight.READY ? ""
                            : "\n" + modernApplyPreflightMessage(preflight))
                    + (sourceApply ? "" : "\nVisible interval preview has no locked source"
                            + " revision receipt for Apply."));
            quality.setCaretPosition(0);
            confirm.setEnabled(preflight == ModernApplyPreflight.READY && sourceApply
                    && state.review() != null && !state.review().confirmed()
                    && state.review().disposition() == ValidationReport.Disposition.REVIEW_REQUIRED);
            apply.setEnabled(preflight == ModernApplyPreflight.READY && sourceApply
                    && state.applyAvailable() && !applying[0]);
        };
        Runnable staleFailure = () -> {
            boolean closed = closeAndPublishIfCurrent(livePreviewSession, owner, () ->
                    recordIntervalDiagnostics(computed, state, IntervalArtifactStatus.FAILED,
                            diagnosticAttemptIdentity));
            dialog.dispose();
            if (closed) {
                overlay.hide();
                PluginLog.endSlideSession();
                showError(tr("Interval preview became stale; run alignment again."));
            }
        };
        for (int index = 0; index < choices.size(); index++) {
            final int intervalIndex = index;
            choices.get(index).addActionListener(event -> {
                try {
                    requireLiveBCurrent(dataSet, selection, imageryLayer, mapView, slideConfig,
                            persistedSlideConfig, tracingAtCapture, computed.captured(), previewSourceOwner);
                    state.choose(intervalIndex, choices.get(intervalIndex).getSelectedIndex());
                    refresh.run();
                    recordIntervalDiagnostics(computed, state, IntervalArtifactStatus.PREVIEW,
                            diagnosticAttemptIdentity);
                } catch (RuntimeException stale) {
                    staleFailure.run();
                }
            });
        }
        confirm.addActionListener(event -> {
            try {
                requireLiveBCurrent(dataSet, selection, imageryLayer, mapView, slideConfig,
                        persistedSlideConfig, tracingAtCapture, computed.captured(), previewSourceOwner);
                state.confirmReview();
                refresh.run();
                recordIntervalDiagnostics(computed, state, IntervalArtifactStatus.CONFIRMED,
                        diagnosticAttemptIdentity);
            } catch (RuntimeException stale) {
                staleFailure.run();
            }
        });
        apply.addActionListener(event -> {
            if (applying[0]) return;
            applying[0] = true;
            apply.setEnabled(false);
            try {
                if (!livePreviewSession.isCurrentWindow(owner, dialog, activePreviewDialog,
                        dialog.isDisplayable())) {
                    throw new IllegalStateException("The interval preview no longer owns this attempt");
                }
                if (modernApplyPreflight(computed.captured(), slideConfig)
                        != ModernApplyPreflight.READY) {
                    throw new IllegalStateException("Interval Apply configuration is unavailable");
                }
                if (!intervalApplySourceAvailable(computed.captured())) {
                    throw new IllegalStateException("Interval Apply needs a locked source revision receipt");
                }
                requireSupportedApplySource(computed.captured(), imageryLayer);
                requireLiveBCurrent(dataSet, selection, imageryLayer, mapView, slideConfig,
                        persistedSlideConfig, tracingAtCapture, computed.captured(), previewSourceOwner);
                AlignmentEditPlan currentPlan = state.currentPlanForApply();
                NetworkSnapshotCapture.CapturedSnapshot receipt = NetworkSnapshotCapture.captureBound(
                        dataSet, computed.captured().specification());
                LiveNetworkSnapshotValidator network = new LiveNetworkSnapshotValidator(receipt,
                        currentPlan,
                        () -> ManagedTileRuntime.initializedCoordinator().activeGenerationValue());
                ManagedSourceReceipt managedReceipt = ManagedSourceReceipt.forCurrentPlugin(
                        previewSourceOwner, computed.captured(), slideConfig.heatmap());
                ApplyAlignmentEditPlanCommand command = new ApplyAlignmentEditPlanCommand(dataSet,
                        currentPlan, new ManagedSourceLockedApplyValidator(network, livePreviewService,
                            computed.captured(), () -> {
                                managedReceipt.requireCurrent();
                                requireLiveBSourceOwnerCurrent(dataSet, imageryLayer,
                                        slideConfig, persistedSlideConfig, tracingAtCapture,
                                        computed.captured(), previewSourceOwner);
                            }, redoFailureReporter(this::showError)),
                        tr("Apply modern interval alignment"));
                applyWithPreparedIntervalDiagnostics(
                        () -> createIntervalDiagnostics(computed, state,
                                state.review() != null && state.review().confirmed()
                                    ? IntervalArtifactStatus.APPLIED_AFTER_REVIEW
                                    : IntervalArtifactStatus.APPLIED),
                        currentPlan.canonicalHash(),
                        () -> UndoRedoHandler.getInstance().add(command),
                        () -> livePreviewSession.isCurrentWindow(owner, dialog,
                                activePreviewDialog, dialog.isDisplayable()));
                completed[0] = true;
                try {
                    dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
                } catch (RuntimeException closeFailure) {
                    PluginLog.verbose("Interval preview close after Apply failed: %s",
                            closeFailure.getClass().getSimpleName());
                }
            } catch (RuntimeException failure) {
                completed[0] = true;
                if (livePreviewSession.isCurrent(owner)) {
                    recordIntervalDiagnostics(computed, state, IntervalArtifactStatus.FAILED,
                            diagnosticAttemptIdentity);
                }
                dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
                showError(tr("Interval alignment Apply failed: {0}", failure.getMessage()));
            }
        });
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                if (closeAndPublishIfCurrent(livePreviewSession, owner, () -> {
                    if (!completed[0]) {
                        recordIntervalDiagnostics(computed, state, IntervalArtifactStatus.CANCELLED,
                                diagnosticAttemptIdentity);
                    }
                })) {
                    overlay.hide();
                    PluginLog.endSlideSession();
                }
            }
            @Override public void windowClosed(WindowEvent event) {
                if (activePreviewDialog == dialog) activePreviewDialog = null;
            }
        });
        close.addActionListener(event -> dialog.dispatchEvent(
                new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING)));
        try {
            refresh.run();
            recordIntervalDiagnostics(computed, state, IntervalArtifactStatus.PREVIEW,
                    diagnosticAttemptIdentity);
            dialog.setVisible(true);
        } catch (RuntimeException failure) {
            dialog.dispose();
            throw failure;
        }
    }

    private static EastNorth projectGeographic(
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint point) {
        return ProjectionRegistry.getProjection().latlon2eastNorth(
                new LatLon(point.latitudeDegrees(), point.longitudeDegrees()));
    }

    /** Formats fixed-island guidance and typed interval outcomes for the modeless preview. */
    public static String intervalPreviewSummary(IntervalPreviewState state) {
        StringBuilder summary = new StringBuilder();
        for (SelectedWayIntervalPartitioner.FixedIsland island
                : state.batch().partition().fixedIslands()) {
            summary.append("Fixed junction occurrences ").append(island.range().firstIndex())
                    .append('–').append(island.range().lastIndex()).append(": ")
                    .append(island.reasons()).append(". Adjust this junction manually first.\n");
        }
        for (FixedIntervalEditPlanComposer.IntervalAssessment interval
                : state.assessment().intervals()) {
            String outcome = switch (interval.disposition()) {
                case CHANGED -> "Ready to slide";
                case FROZEN_LOCAL_FAILURE -> "Kept in place";
                case BLOCKED_GLOBAL -> "Blocked by whole-way validation";
                case UNCHANGED_NOOP -> "No change";
            };
            summary.append("Interval ").append(interval.intervalIndex() + 1).append(": ")
                    .append(outcome).append(" (").append(interval.reason())
                    .append("); route ").append(interval.routeIndex() + 1).append('\n');
        }
        if (state.assessment().plan().isPresent()) {
            AlignmentEditPlan plan = state.assessment().plan().orElseThrow();
            summary.append("Complete preview: ").append(plan.validation().disposition())
                    .append("; affected ways: ").append(plan.affectedWayKeys().size())
                    .append("; findings: ").append(plan.validation().findingCodes()).append('\n');
        }
        return summary.toString();
    }

    static String noPreviewableRouteMessage(LiveBPreviewService.Captured captured,
            TrackerMode engine) {
        ManualJunctionEligibility.Decision decision = noRouteJunctionDecision(captured);
        if (decision != null) {
            return decision.manualInstruction();
        }
        return "Production " + livePreviewEngineLabel(engine)
                + " returned no previewable final route";
    }

    private static ManualJunctionEligibility.Decision manualJunctionDecision(
            LiveBPreviewService.Captured captured) {
        ManualJunctionEligibility.Decision decision = capturedJunctionDecision(captured);
        return decision != null && decision.manualOnly() ? decision : null;
    }

    private static ManualJunctionEligibility.Decision noRouteJunctionDecision(
            LiveBPreviewService.Captured captured) {
        ManualJunctionEligibility.Decision decision = capturedJunctionDecision(captured);
        if (decision != null && decision.reason() == ManualJunctionEligibility.Reason.SIMPLE_T) {
            return new ManualJunctionEligibility.Decision(
                    ManualJunctionEligibility.Reason.MISSING_RECEIVER_EVIDENCE,
                    decision.junction(), decision.receiver(), decision.affectedNodes());
        }
        return decision != null && decision.manualOnly() ? decision : null;
    }

    private static ManualJunctionEligibility.Decision capturedJunctionDecision(
            LiveBPreviewService.Captured captured) {
        if (captured == null || captured.network() == null || captured.specification() == null
                || captured.specification().permissions().junctionPolicy() == JunctionPolicy.FIXED) {
            return null;
        }
        ManualJunctionEligibility.Decision decision = captured.junctionDecision() != null
                ? captured.junctionDecision()
                : ManualJunctionEligibility.evaluate(captured.network(), captured.specification());
        return decision;
    }

    private void showLiveBReadOnlyDialog(PreviewSessionController.Owner previewOwner, DataSet dataSet,
            SelectionContext selection, ImageryLayer imageryLayer, MapView mapView,
            AlignmentConfig slideConfig, AlignmentConfig persistedSlideConfig,
            TracingSettings tracingAtCapture, LiveBPreviewService.Computed computed,
            List<CenterlineCandidate> candidates, String diagnosticAttemptIdentity,
            TileFetchCoordinator previewSourceOwner) {
        JComboBox<CenterlineCandidate> choices = new JComboBox<>(
                candidates.toArray(CenterlineCandidate[]::new));
        choices.setRenderer(new DefaultListCellRenderer() {
            @Override public java.awt.Component getListCellRendererComponent(JList<?> list,
                    Object value, int index, boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                if (value instanceof CenterlineCandidate candidate) {
                    setText(candidate.displayName());
                }
                return this;
            }
        });
        JTextArea quality = new JTextArea(liveBQualitySummary(computed, 0), 5, 76);
        quality.setEditable(false);
        quality.setLineWrap(true);
        quality.setWrapStyleWord(true);
        quality.setCaretPosition(0);
        JScrollPane qualityScroll = new JScrollPane(quality);
        qualityScroll.setPreferredSize(new Dimension(640, 110));
        JLabel diagnostics = new JLabel(tr(
                "This preview is bound to its captured source and network snapshot."));
        ModernSingleWayEditPlanAdapter planAdapter = new ModernSingleWayEditPlanAdapter();
        AlignmentEditPlan[] plan = {null};
        PreviewReviewState[] review = {null};
        ModernSingleWayEditPlanAdapter.Assessment[] assessment = {null};
        boolean[] applying = {false};
        boolean[] completed = {false};
        JButton confirm = new JButton(tr("Confirm review"));
        JButton apply = new JButton(tr("Apply"));
        confirm.setEnabled(false);
        apply.setEnabled(false);
        JButton close = new JButton(tr("Close preview"));
        JPanel panel = new JPanel();
        panel.add(new JLabel(tr("{0} final geometry",
                livePreviewEngineLabel(slideConfig.heatmap().trackerMode()))));
        if (candidates.size() > 1) {
            panel.add(choices);
        }
        panel.add(qualityScroll);
        panel.add(diagnostics);
        panel.add(confirm);
        panel.add(apply);
        panel.add(close);
        JDialog dialog = new JDialog(MainApplication.getMainFrame(),
                tr("{0} Alignment Preview",
                        livePreviewEngineLabel(slideConfig.heatmap().trackerMode())), false);
        dialog.setContentPane(panel);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.pack();
        dialog.setLocationRelativeTo(MainApplication.getMainFrame());
        activePreviewDialog = dialog;
        livePreviewSession.replaceWindow(previewOwner, dialog::dispose);
        Runnable refresh = () -> {
            if (!livePreviewSession.isCurrentWindow(previewOwner, dialog,
                    activePreviewDialog, dialog.isDisplayable())) {
                throw new IllegalStateException("The preview window no longer owns this attempt");
            }
            int index = Math.max(0, choices.getSelectedIndex());
            requireLiveBCurrent(dataSet, selection, imageryLayer, mapView, slideConfig, persistedSlideConfig,
                    tracingAtCapture, computed.captured(), previewSourceOwner);
            CenterlineCandidate candidate = candidates.get(index);
            FinalGeometryEvaluator.Disposition disposition =
                    computed.pipeline().routes().get(index).quality().disposition();
            plan[0] = null;
            review[0] = null;
            assessment[0] = null;
            ModernApplyPreflight preflight = modernApplyPreflight(computed.captured(), slideConfig);
            if (preflight == ModernApplyPreflight.READY) {
                assessment[0] = planAdapter.assess(computed, index);
                if (assessment[0].plan().isPresent()) {
                    plan[0] = assessment[0].plan().orElseThrow();
                    review[0] = PreviewReviewState.fromEditPlan(candidate.id(), plan[0]);
                }
            }
            AlignmentResult display = liveBDisplayResult(selection, computed, candidates,
                    candidate, plan[0]);
            ValidationReport.Disposition displayedDisposition = liveBDisplayedDisposition(
                    disposition, assessment[0], plan[0]);
            Map<PrimitiveKey, List<EastNorth>> projectedPreview =
                    assessment[0] == null || assessment[0].plan().isEmpty()
                        ? Map.of()
                        : assessment[0].projectFinalPreviewWays(point -> ProjectionRegistry.getProjection()
                                .latlon2eastNorth(new LatLon(point.latitudeDegrees(),
                                        point.longitudeDegrees())));
            overlay.show(selection, display, candidate, switch (displayedDisposition) {
                case APPLICABLE -> CandidateAssessment.Disposition.APPLICABLE;
                case REVIEW_REQUIRED -> CandidateAssessment.Disposition.REVIEW_REQUIRED;
                case HARD_BLOCKED -> CandidateAssessment.Disposition.HARD_BLOCKED;
            }, false, PluginPreferences.isDebugEnabled(), projectedPreview);
            String availability = assessment[0] == null ? modernApplyPreflightMessage(preflight)
                    : assessment[0].detail();
            List<String> reasons = plan[0] == null
                    ? computed.pipeline().routes().get(index).quality().findings().stream()
                            .map(finding -> finding.code().name()).toList()
                    : plan[0].validation().findingCodes();
            String finalDisposition = assessment[0] != null
                    && assessment[0].availability()
                            == ModernSingleWayEditPlanAdapter.ApplyAvailability.MANUAL_JUNCTION
                    ? "MANUAL_JUNCTION"
                    : plan[0] == null ? disposition.name()
                    : plan[0].validation().disposition().name();
            recordModernDiagnostics(computed, modernPreviewStatus(
                    computed.pipeline().inference().status(), displayedDisposition),
                    index, plan[0], false, false, diagnosticAttemptIdentity,
                    assessment[0] == null ? null : assessment[0].junctionReason());
            String sourceLabel = computed.captured().managedRaster() == null
                    ? tr("visible layer") : tr("managed tiles");
            quality.setText(liveBQualitySummary(computed, index) + "\n\n"
                    + modernPreviewSummary(livePreviewEngineLabel(computed.request().engine()),
                            sourceLabel, finalDisposition,
                            plan[0] == null ? 0 : plan[0].affectedWayKeys().size(), reasons,
                            review[0] != null && review[0].confirmed(), availability));
            quality.setCaretPosition(0);
            if (assessment[0] != null && !assessment[0].applyAvailable()) {
                PluginLog.verbose("Modern Apply is unavailable for candidate %s: %s",
                        candidate.id(), assessment[0].detail());
            }
            confirm.setEnabled(review[0] != null && review[0].disposition()
                    == org.openstreetmap.josm.plugins.wayheatmaptracer.model.ValidationReport.Disposition.REVIEW_REQUIRED);
            apply.setEnabled(review[0] != null && review[0].canApply() && !applying[0]);
        };
        choices.addActionListener(event -> {
            try {
                refresh.run();
            } catch (RuntimeException exception) {
                completed[0] = true;
                recordModernDiagnostics(computed, "failed", Math.max(0, choices.getSelectedIndex()),
                        plan[0], false, false, diagnosticAttemptIdentity);
                boolean closed = livePreviewSession.close(previewOwner);
                dialog.dispose();
                if (closed) {
                    overlay.hide();
                    PluginLog.endSlideSession();
                    showError(tr("{0} alignment preview became stale: {1}",
                            livePreviewEngineLabel(slideConfig.heatmap().trackerMode()), exception.getMessage()));
                }
            }
        });
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                boolean closed = closeAndPublishIfCurrent(livePreviewSession, previewOwner, () -> {
                    if (!completed[0]) {
                        completed[0] = true;
                        recordModernDiagnostics(computed, "cancelled", Math.max(0, choices.getSelectedIndex()),
                                plan[0], review[0] != null && review[0].confirmed(), false,
                                diagnosticAttemptIdentity);
                    }
                });
                if (closed) {
                    overlay.hide();
                    PluginLog.endSlideSession();
                }
            }
            @Override public void windowClosed(WindowEvent event) {
                if (livePreviewSession.close(previewOwner)) {
                    overlay.hide();
                }
                if (activePreviewDialog == dialog) {
                    activePreviewDialog = null;
                }
            }
        });
        confirm.addActionListener(event -> {
            try {
                refresh.run();
                if (review[0] == null || review[0].disposition()
                        != org.openstreetmap.josm.plugins.wayheatmaptracer.model.ValidationReport.Disposition.REVIEW_REQUIRED) {
                    throw new IllegalStateException("This candidate does not require a confirmable review");
                }
                review[0] = review[0].confirm();
                int index = Math.max(0, choices.getSelectedIndex());
                recordModernDiagnostics(computed, "confirmed", index, plan[0], true, false,
                        diagnosticAttemptIdentity);
                confirm.setEnabled(false);
                apply.setEnabled(true);
                CenterlineCandidate candidate = candidates.get(index);
                AlignmentResult display = liveBDisplayResult(selection, computed, candidates,
                        candidate, plan[0]);
                Map<PrimitiveKey, List<EastNorth>> projectedPreview =
                        assessment[0].projectFinalPreviewWays(point -> ProjectionRegistry.getProjection()
                                .latlon2eastNorth(new LatLon(point.latitudeDegrees(),
                                        point.longitudeDegrees())));
                overlay.show(selection, display, candidate,
                        CandidateAssessment.Disposition.REVIEW_REQUIRED, true,
                        PluginPreferences.isDebugEnabled(), projectedPreview);
                quality.setText(liveBQualitySummary(computed, index) + "\n\n"
                        + modernPreviewSummary(livePreviewEngineLabel(computed.request().engine()),
                                computed.captured().managedRaster() == null
                                        ? tr("visible layer") : tr("managed tiles"),
                                plan[0].validation().disposition().name(),
                                plan[0].affectedWayKeys().size(),
                                plan[0].validation().findingCodes(), true,
                                tr("review confirmed; Apply available")));
            } catch (RuntimeException exception) {
                completed[0] = true;
                recordModernDiagnostics(computed, "failed", Math.max(0, choices.getSelectedIndex()),
                        plan[0], false, false, diagnosticAttemptIdentity);
                dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
                showError(tr("Alignment preview became stale: {0}", exception.getMessage()));
            }
        });
        apply.addActionListener(event -> {
            if (applying[0]) {
                return;
            }
            applying[0] = true;
            apply.setEnabled(false);
            try {
                int index = Math.max(0, choices.getSelectedIndex());
                PreparedModernApply prepared = prepareModernApply(dataSet, computed, index,
                        candidates.get(index).id(), review[0], () -> {
                            if (!livePreviewSession.isCurrentWindow(previewOwner, dialog, activePreviewDialog,
                                    dialog.isDisplayable())) {
                                throw new IllegalStateException("The preview window no longer owns this attempt");
                            }
                            requireSupportedApplySource(computed.captured(), imageryLayer);
                            requireLiveBCurrent(dataSet, selection, imageryLayer, mapView, slideConfig,
                                    persistedSlideConfig, tracingAtCapture, computed.captured(), previewSourceOwner);
                        }, computed.captured().managedRaster() != null
                            ? () -> ManagedTileRuntime.initializedCoordinator().activeGenerationValue()
                            : () -> computed.captured().network().sourceGeneration(),
                        (network, currentPlan) -> {
                            ManagedSourceReceipt managedReceipt = computed.captured().managedRaster() == null ? null
                                : ManagedSourceReceipt.forCurrentPlugin(previewSourceOwner,
                                    computed.captured(), slideConfig.heatmap());
                            return computed.captured().managedRaster() != null
                            ? new ManagedSourceLockedApplyValidator(network, livePreviewService,
                                computed.captured(), () -> {
                                    managedReceipt.requireCurrent();
                                    requireLiveBSourceOwnerCurrent(dataSet,
                                        imageryLayer, slideConfig, persistedSlideConfig, tracingAtCapture,
                                        computed.captured(), previewSourceOwner);
                                },
                                redoFailureReporter(this::showError))
                            : new VisibleSourceLockedApplyValidator(network, livePreviewService, computed.captured(),
                                () -> alignmentService.captureLiveBVisibleRaster(selection, imageryLayer, mapView,
                                        slideConfig, liveLayerIdentity(imageryLayer),
                                        computed.captured().specification().permissions()),
                                visibleSourceEpoch(imageryLayer),
                                () -> requireLiveBSourceOwnerCurrent(dataSet, imageryLayer, slideConfig,
                                        persistedSlideConfig, tracingAtCapture, computed.captured(), null),
                                redoFailureReporter(this::showError));
                        });
                AlignmentEditPlan currentPlan = prepared.plan();
                ApplyAlignmentEditPlanCommand command = prepared.command();
                applyWithPreparedDiagnostics(
                    () -> createModernDiagnostics(computed, "applied", index, currentPlan,
                            review[0].confirmed(), true, null),
                    () -> UndoRedoHandler.getInstance().add(command));
                completed[0] = true;
                try {
                    dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
                } catch (RuntimeException closeFailure) {
                    PluginLog.verbose("Modern preview close after Apply failed: %s",
                            closeFailure.getClass().getSimpleName());
                }
            } catch (RuntimeException exception) {
                completed[0] = true;
                recordModernDiagnostics(computed, "failed", Math.max(0, choices.getSelectedIndex()),
                        plan[0], review[0] != null && review[0].confirmed(), false,
                        diagnosticAttemptIdentity);
                dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
                showError(tr("Alignment Apply failed: {0}", exception.getMessage()));
            }
        });
        close.addActionListener(event -> dialog.dispatchEvent(
                new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING)));
        try {
            refresh.run();
            dialog.setVisible(true);
        } catch (RuntimeException exception) {
            dialog.dispose();
            throw exception;
        }
    }

    /** Routes persisted modern engines through their detached live pipeline. */
    static boolean requiresLiveModernPreview(TrackerMode engine) {
        return Objects.requireNonNull(engine, "engine").capabilities().requiresEvidenceSnapshot();
    }

    /** Returns whether the declared engine can consume an authenticated managed tile raster. */
    static boolean supportsManagedModernSource(TrackerMode engine) {
        return Objects.requireNonNull(engine, "engine").capabilities().supportsManagedSource();
    }

    /** Typed configuration-only gate used before exact route-to-plan assessment. */
    enum ModernApplyPreflight {
        READY,
        CLEANUP_UNAVAILABLE_FOR_ENGINE,
        SOURCE_LINEAGE_UNAVAILABLE,
        CONFIGURATION_UNSUPPORTED
    }

    static String modernApplyPreflightMessage(ModernApplyPreflight preflight) {
        return switch (Objects.requireNonNull(preflight, "preflight")) {
            case READY -> tr("Exact final-plan assessment is available");
            case CLEANUP_UNAVAILABLE_FOR_ENGINE ->
                tr("Cleanup is unavailable for Probabilistic B");
            case SOURCE_LINEAGE_UNAVAILABLE ->
                tr("Exact source lineage is unavailable for Apply");
            case CONFIGURATION_UNSUPPORTED ->
                tr("This engine, source, or geometry configuration is unavailable for Apply");
        };
    }

    static ModernApplyPreflight modernApplyPreflight(LiveBPreviewService.Captured captured,
            AlignmentConfig config) {
        if (captured == null || config == null
                || !"EPSG:3857".equals(captured.projectionCode())) {
            return ModernApplyPreflight.CONFIGURATION_UNSUPPORTED;
        }
        ManagedHeatmapConfig heatmap = config.effectiveHeatmap();
        if (captured.engine() == TrackerMode.PROBABILISTIC && !config.cleanup().isDisabled()) {
            return ModernApplyPreflight.CLEANUP_UNAVAILABLE_FOR_ENGINE;
        }
        if (heatmap.intensitySamplingMode() != IntensitySamplingMode.COLOR_MAPPING
                || heatmap.multiColorDetection() || heatmap.aggregateAllColorSchemes()) {
            return ModernApplyPreflight.SOURCE_LINEAGE_UNAVAILABLE;
        }
        boolean engineSupported = captured.engine().capabilities().requiresEvidenceSnapshot();
        boolean geometrySupported = captured.geometryMode() == heatmap.alignmentMode()
                && (heatmap.alignmentMode() == AlignmentMode.MOVE_EXISTING_NODES
                    || !heatmap.simplifyEnabled());
        return engineSupported && geometrySupported
                ? ModernApplyPreflight.READY : ModernApplyPreflight.CONFIGURATION_UNSUPPORTED;
    }

    static boolean supportsModernVisibleApply(LiveBPreviewService.Captured captured,
            AlignmentConfig config) {
        return modernApplyPreflight(captured, config) == ModernApplyPreflight.READY;
    }

    static String modernPreviewSummary(String engine, String source, String disposition,
            int affectedWayCount, List<String> reasons, boolean confirmed,
            String applyAvailability) {
        String reasonText = reasons == null || reasons.isEmpty() ? tr("none")
                : String.join(", ", reasons);
        return tr("Engine: {0}\nSource: {1}\nDisposition: {2}\nAffected ways: {3}"
                        + "\nReasons: {4}\nConfirmation: {5}\nApply: {6}",
                engine, source, disposition, affectedWayCount, reasonText,
                confirmed ? tr("confirmed") : tr("not confirmed"), applyAvailability);
    }

    private AlignmentResult liveBDisplayResult(SelectionContext selection,
            LiveBPreviewService.Computed computed, List<CenterlineCandidate> candidates,
            CenterlineCandidate selected, AlignmentEditPlan exactPlan) {
        List<EastNorth> source = computed.captured().sourceGeographic().stream()
                .map(point -> ProjectionRegistry.getProjection().latlon2eastNorth(
                        new LatLon(point.latitudeDegrees(), point.longitudeDegrees())))
                .toList();
        List<EastNorth> preview = exactPlan == null ? selected.finalPreviewPoints()
                : exactPlan.finalPreviewWays().get(exactPlan.selectedWayKey()).stream()
                        .map(point -> ProjectionRegistry.getProjection().latlon2eastNorth(
                                new LatLon(point.latitudeDegrees(), point.longitudeDegrees())))
                        .toList();
        return new AlignmentResult(selection, null, candidates, source,
                preview, List.of(), null, null, List.of(), List.of());
    }

    /** Side-effect-free preparation shared by the ordinary listener and headless boundary checks. */
    static PreparedModernApply prepareModernApply(DataSet dataSet, LiveBPreviewService.Computed computed,
            int routeIndex, String candidateId, PreviewReviewState review,
            Runnable requirePreviewAndSourceCurrent, LongSupplier sourceGeneration,
            BiFunction<LiveNetworkSnapshotValidator, AlignmentEditPlan, LockedApplyValidator> validatorFactory) {
        Objects.requireNonNull(requirePreviewAndSourceCurrent, "preview/source freshness").run();
        Objects.requireNonNull(sourceGeneration, "source generation");
        Objects.requireNonNull(validatorFactory, "locked validator factory");
        ModernSingleWayEditPlanAdapter.Assessment assessment =
                new ModernSingleWayEditPlanAdapter().assess(computed, routeIndex);
        if (!assessment.applyAvailable()) {
            throw new IllegalStateException(assessment.detail());
        }
        AlignmentEditPlan plan = assessment.plan().orElseThrow();
        PreviewReviewState current = PreviewReviewState.fromEditPlan(candidateId, plan);
        if (review == null || !(review.equals(current) || review.matches(current)) || !review.canApply()) {
            throw new IllegalStateException("The reviewed candidate plan is stale");
        }
        NetworkSnapshotCapture.CapturedSnapshot receipt = NetworkSnapshotCapture.captureBound(
                dataSet, computed.captured().specification());
        LiveNetworkSnapshotValidator network = new LiveNetworkSnapshotValidator(receipt, plan, sourceGeneration);
        LockedApplyValidator validator = Objects.requireNonNull(validatorFactory.apply(network, plan),
                "locked validator");
        return new PreparedModernApply(plan, new ApplyAlignmentEditPlanCommand(dataSet, plan, validator,
                tr("Apply modern alignment")));
    }

    record PreparedModernApply(AlignmentEditPlan plan, ApplyAlignmentEditPlanCommand command) { }

    private String liveBQualitySummary(LiveBPreviewService.Computed computed, int index) {
        FinalGeometryEvaluator.Result quality = computed.pipeline().routes().get(index).quality();
        String findings = quality.findings().isEmpty() ? tr("none")
                : quality.findings().stream()
                        .map(finding -> finding.code().name() + " (" + finding.severity().name() + ")")
                        .reduce((left, right) -> left + ", " + right).orElse(tr("none"));
        return tr("Final quality: {0}; findings: {1}; supported length: {2} m of {3} m",
                quality.disposition().name(), findings,
                String.format(Locale.ROOT, "%.1f", quality.directlySupportedLengthMeters()),
                String.format(Locale.ROOT, "%.1f", quality.totalLengthMeters()));
    }

    private void requireLiveBCurrent(DataSet dataSet, SelectionContext selection,
            ImageryLayer imageryLayer, MapView mapView, AlignmentConfig slideConfig,
            AlignmentConfig persistedSlideConfig, TracingSettings tracingAtCapture,
            LiveBPreviewService.Captured captured, TileFetchCoordinator previewSourceOwner) {
        requireLiveBSourceOwnerCurrent(dataSet, imageryLayer, slideConfig,
                persistedSlideConfig, tracingAtCapture, captured, previewSourceOwner);
        if (captured.managedRaster() != null) {
            livePreviewService.requireCurrent(dataSet, captured);
        } else {
            LiveBPreviewService.VisibleRaster currentRaster = alignmentService.captureLiveBVisibleRaster(
                    selection, imageryLayer, mapView, slideConfig, liveLayerIdentity(imageryLayer),
                    captured.specification().permissions());
            livePreviewService.requireCurrent(dataSet, captured, currentRaster);
        }
    }

    private void requireLiveBSourceOwnerCurrent(DataSet dataSet, ImageryLayer imageryLayer,
            AlignmentConfig slideConfig, AlignmentConfig persistedSlideConfig,
            TracingSettings tracingAtCapture, LiveBPreviewService.Captured captured,
            TileFetchCoordinator sourceOwner) {
        AlignmentConfig currentPersisted = new AlignmentConfig(PluginPreferences.load(),
                PluginPreferences.loadGeometryCleanup());
        TracingSettings currentTracing = PluginPreferences.loadTracingSettings();
        boolean managed = captured.managedRaster() != null;
        if (MainApplication.getLayerManager().getEditDataSet() != dataSet
                || !matchesLivePreviewSettings(persistedSlideConfig, slideConfig, currentPersisted,
                        tracingAtCapture, currentTracing, forcedAlignmentMode, forcedLivePreviewEngine)
                || managed && !slideConfig.heatmap().hasSameManagedSource(currentPersisted.heatmap())
                || managed && sourceOwner != ManagedTileRuntime.initializedCoordinator()
                || managed && !ManagedTileRuntime.initializedCoordinator().isActiveGeneration(
                        new ManagedTileGeneration(Math.max(0L, slideConfig.heatmap().cacheBuster())))
                || managed && !captured.managedRaster().sourceIdentity().equals(
                        "managed-selected-" + slideConfig.heatmap().color()
                                + "-g" + slideConfig.heatmap().cacheBuster())
                || !managed && (!imageryLayer.isVisible()
                        || HeatmapLayerResolver.resolveOptional().orElse(null) != imageryLayer
                        || !captured.raster().sourceIdentity().equals(liveLayerIdentity(imageryLayer)))) {
            throw new IllegalStateException("The dataset, source layer, or settings changed after capture");
        }
    }

    private static String liveLayerIdentity(ImageryLayer layer) {
        return layer.getClass().getName() + "@"
                + Integer.toUnsignedString(System.identityHashCode(layer)) + ":"
                + safeLayerNameIdentity(layer.getName());
    }

    static VisibleSourceEpoch visibleSourceEpoch(ImageryLayer layer) {
        if (layer instanceof ManagedHeatmapLayer managedLayer) {
            return managedLayer.sourceEpoch();
        }
        return null;
    }

    /** Refuses a rendered managed layer whose mutable JOSM filters bypass its source epoch. */
    static void requireSupportedApplySource(LiveBPreviewService.Captured captured,
            ImageryLayer layer) {
        if (captured.managedRaster() == null && layer instanceof ManagedHeatmapLayer) {
            throw new IllegalStateException("The managed rendered source cannot verify unchanged pixels; "
                    + "use direct managed tiles for Apply.");
        }
    }

    /** Interval Apply currently requires a plugin-owned direct tile receipt and locked validator. */
    static boolean intervalApplySourceAvailable(LiveBPreviewService.Captured captured) {
        return captured != null && captured.managedRaster() != null;
    }

    /** Queues one fixed, credential-free host Redo failure for the user-visible error surface. */
    public static Consumer<String> redoFailureReporter(Consumer<String> showError) {
        Objects.requireNonNull(showError, "showError");
        return reason -> {
            String safeReason = "This visible source cannot verify unchanged tiles for Redo; run a new alignment."
                    .equals(reason)
                ? "This visible source cannot verify unchanged tiles for Redo; run a new alignment."
                : "The captured source or network changed; recompute alignment before applying.";
            SwingUtilities.invokeLater(() -> showError.accept(tr("Alignment Redo failed: {0}", safeReason)));
        };
    }

    static String safeLayerNameIdentity(String name) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(
                String.valueOf(name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for source identity", exception);
        }
    }

    static String modernPreviewStatus(TraceHypothesisSet.Status inference,
            ValidationReport.Disposition disposition) {
        if (inference == null || disposition == null) {
            throw new IllegalArgumentException("Modern diagnostic status is incomplete");
        }
        if (inference == TraceHypothesisSet.Status.RESOURCE_LIMIT) return "resource-limited";
        if (inference == TraceHypothesisSet.Status.CANCELLED) return "cancelled";
        if (inference == TraceHypothesisSet.Status.NO_ROUTE
                || disposition == ValidationReport.Disposition.HARD_BLOCKED) return "blocked";
        return disposition == ValidationReport.Disposition.REVIEW_REQUIRED
                ? "review-required" : "preview-open";
    }

    static ValidationReport.Disposition liveBDisplayedDisposition(
            FinalGeometryEvaluator.Disposition routeDisposition,
            ModernSingleWayEditPlanAdapter.Assessment assessment,
            AlignmentEditPlan plan) {
        if (routeDisposition == null) {
            throw new IllegalArgumentException("Modern route disposition is required");
        }
        if (assessment != null && assessment.availability()
                == ModernSingleWayEditPlanAdapter.ApplyAvailability.MANUAL_JUNCTION) {
            return ValidationReport.Disposition.HARD_BLOCKED;
        }
        if (plan != null) {
            return plan.validation().disposition();
        }
        return switch (routeDisposition) {
            case APPLICABLE -> ValidationReport.Disposition.APPLICABLE;
            case REVIEW_REQUIRED -> ValidationReport.Disposition.REVIEW_REQUIRED;
            case HARD_BLOCKED -> ValidationReport.Disposition.HARD_BLOCKED;
        };
    }

    static void recordModernUnavailable(String status, String sourceLineage,
            String attemptIdentity) {
        recordModernUnavailable(status, sourceLineage, attemptIdentity, null);
    }

    static void recordModernUnavailable(String status, String sourceLineage,
            String attemptIdentity, ManualJunctionEligibility.Reason manualReason) {
        DiagnosticsRegistry.setLastModernBundle(Format15ProductionBundleFactory.createUnavailableLive(
                LastSlideDebugBundle.buildIdentity(), status, sourceLineage, attemptIdentity,
                manualReason));
    }

    static ManualJunctionEligibility.Reason manualCaptureReason(String failure) {
        String marker = LiveBPreviewService.ManualJunctionCaptureException.class.getSimpleName()
                + ": ";
        if (failure == null || !failure.startsWith(marker)) {
            return null;
        }
        for (ManualJunctionEligibility.Reason reason : ManualJunctionEligibility.Reason.values()) {
            if (failure.startsWith(marker + reason.name() + ":")) {
                return reason;
            }
        }
        return null;
    }

    static String beginDiagnosticAttempt() {
        String identity = java.util.UUID.randomUUID().toString();
        recordModernUnavailable("started", "unavailable", identity);
        return identity;
    }

    static boolean closeAndPublishIfCurrent(
            PreviewSessionController<LiveBPreviewService.Computed> session,
            PreviewSessionController.Owner owner, Runnable publish) {
        synchronized (session) {
            if (!session.close(owner)) {
                return false;
            }
            publish.run();
            return true;
        }
    }

    static void recordModernDiagnostics(LiveBPreviewService.Computed computed,
            String status, int routeIndex, AlignmentEditPlan plan, boolean reviewed,
            boolean applied, String attemptIdentity) {
        recordModernDiagnostics(computed, status, routeIndex, plan, reviewed, applied,
                attemptIdentity, null);
    }

    /** Uses only source metadata captured for this attempt; never exports a raw identity. */
    static IntervalSourceReceipt intervalSourceReceipt(LiveBPreviewService.Captured captured) {
        if (captured.managedRaster() != null) {
            var raster = captured.managedRaster();
            return new Format15ProductionBundleFactory.ManagedTileSourceReceipt(
                    raster.generation().value(), raster.zoom(), safeLayerNameIdentity(raster.sourceIdentity()));
        }
        var raster = captured.raster();
        var receipt = raster.sourceReceipt();
        return receipt == null
                ? Format15ProductionBundleFactory.VisibleLayerSourceReceipt.unavailable()
                : new Format15ProductionBundleFactory.VisibleLayerSourceReceipt(
                        receipt.revision(), null, safeLayerNameIdentity(raster.sourceIdentity()));
    }

    /** Serializes the exact composer state displayed by the interval preview. */
    static Format15Bundle createIntervalDiagnostics(LiveBPreviewService.Computed computed,
            IntervalPreviewState state, IntervalArtifactStatus status) {
        if (!computed.partitioned() || state.batch() != computed.intervalBatch()) {
            throw new IllegalArgumentException("Interval diagnostics require the current production batch");
        }
        if (status == IntervalArtifactStatus.PREVIEW) {
            if (state.batch().runs().stream().anyMatch(run -> run.result().inference().status()
                    == TraceHypothesisSet.Status.RESOURCE_LIMIT)) {
                status = IntervalArtifactStatus.RESOURCE_LIMIT;
            } else if (state.batch().runs().stream().anyMatch(run -> run.result().inference().status()
                    == TraceHypothesisSet.Status.CANCELLED)) {
                status = IntervalArtifactStatus.CANCELLED;
            }
        }
        if ((status == IntervalArtifactStatus.CONFIRMED
                || status == IntervalArtifactStatus.APPLIED_AFTER_REVIEW)
                && (state.review() == null || !state.review().confirmed())) {
            throw new IllegalStateException("The composed interval plan has not been confirmed");
        }
        String reviewed = (status == IntervalArtifactStatus.CONFIRMED
                || status == IntervalArtifactStatus.REVIEWED
                || status == IntervalArtifactStatus.APPLIED_AFTER_REVIEW)
                ? state.assessment().plan().orElseThrow().canonicalHash() : null;
        String applied = (status == IntervalArtifactStatus.APPLIED
                || status == IntervalArtifactStatus.APPLIED_AFTER_REVIEW)
                ? state.assessment().plan().orElseThrow().canonicalHash() : null;
        Format15Bundle bundle = Format15ProductionBundleFactory.createLiveIntervals(
                LastSlideDebugBundle.buildIdentity(), state.batch(), state.assessment(),
                state.routeChoices(), intervalSourceReceipt(computed.captured()), status,
                reviewed, applied);
        var batch = state.batch();
        return Format15ProductionBundleFactory.withCurrentNumericalPolicy(bundle,
                new FrozenReplayInput(batch.fullRequest(), batch.evidence(), batch.network(), batch.options()),
                batch.fullRequest().engine());
    }

    private static void recordIntervalDiagnostics(LiveBPreviewService.Computed computed,
            IntervalPreviewState state, IntervalArtifactStatus status, String attemptIdentity) {
        try {
            DiagnosticsRegistry.setLastModernBundle(createIntervalDiagnostics(computed, state, status));
        } catch (RuntimeException failure) {
            String reason = failure.getMessage() == null ? ""
                    : failure.getMessage().toLowerCase(Locale.ROOT);
            String terminal = reason.contains("budget") || reason.contains("limit")
                    || reason.contains("large") ? "resource-limited" : "failed";
            recordModernUnavailable(terminal, computed.captured().managedRaster() == null
                    ? "visible-layer" : "managed-tiles", attemptIdentity);
            PluginLog.verbose("Format15 interval export unavailable status=%s cause=%s",
                    terminal, format15FailureCode(failure));
        }
    }

    /** Prepares full interval evidence before mutation and publishes only after command success. */
    static void applyWithPreparedIntervalDiagnostics(Supplier<Format15Bundle> prepare,
            String expectedPlanHash, Runnable apply, BooleanSupplier currentOwner) {
        if (!currentOwner.getAsBoolean()) {
            throw new IllegalStateException("The interval preview no longer owns this attempt");
        }
        Format15Bundle prepared = Objects.requireNonNull(prepare.get(),
                "Prepared interval diagnostics are required");
        if (!prepared.artifactNames().containsAll(java.util.Set.of("interval-production.json",
                "private/interval-composed-preview.json", "private/interval-point-provenance.json"))) {
            throw new IllegalArgumentException("Applied interval diagnostics lack preview evidence");
        }
        String index = new String(prepared.artifact("interval-production.json").bytes(),
                StandardCharsets.UTF_8);
        if (!index.contains("\"status\":\"APPLIED\"")
                && !index.contains("\"status\":\"APPLIED_AFTER_REVIEW\"")) {
            throw new IllegalArgumentException("Applied interval diagnostics have the wrong status");
        }
        if (expectedPlanHash == null || !index.contains("\"planIdentity\":\""
                + expectedPlanHash + "\"") || !index.contains("\"appliedPlanIdentity\":\""
                + expectedPlanHash + "\"")) {
            throw new IllegalArgumentException("Applied interval diagnostics differ from the current plan");
        }
        if (!currentOwner.getAsBoolean()) {
            throw new IllegalStateException("The interval preview no longer owns this attempt");
        }
        apply.run();
        if (currentOwner.getAsBoolean()) {
            DiagnosticsRegistry.setLastModernBundle(prepared);
        }
    }

    static void recordModernDiagnostics(LiveBPreviewService.Computed computed,
            String status, int routeIndex, AlignmentEditPlan plan, boolean reviewed,
            boolean applied, String attemptIdentity,
            ManualJunctionEligibility.Reason assessedManualReason) {
        String sourceLineage = computed.captured().managedRaster() == null
                ? "visible-layer" : "managed-tiles";
        try {
            DiagnosticsRegistry.setLastModernBundle(createModernDiagnostics(computed,
                    status, routeIndex, plan, reviewed, applied, assessedManualReason));
        } catch (RuntimeException failure) {
            String reason = failure.getMessage() == null ? "" : failure.getMessage()
                    .toLowerCase(java.util.Locale.ROOT);
            String diagnosticStatus = reason.contains("budget") || reason.contains("limit")
                    || reason.contains("large") ? "resource-limited" : "failed";
            recordModernUnavailable(diagnosticStatus, sourceLineage, attemptIdentity);
            PluginLog.verbose("Format15 support export unavailable status=%s cause=%s",
                    diagnosticStatus, format15FailureCode(failure));
        }
    }

    private static String format15FailureCode(RuntimeException failure) {
        // Only fixed local codes leave this boundary; exception/server text may contain credentials.
        return switch (failure.getMessage() == null ? "" : failure.getMessage()) {
            case "final-output-invalid" -> "final-output-invalid";
            case "final-output-budget" -> "final-output-budget";
            case "scalar-output-invalid" -> "scalar-output-invalid";
            case "scalar-output-budget" -> "scalar-output-budget";
            case "Modern counter value is invalid" -> "counter-invalid";
            case "Modern counter inventory exceeds budget" -> "counter-budget";
            default -> "export-failed";
        };
    }

    private static Format15Bundle createModernDiagnostics(LiveBPreviewService.Computed computed,
            String status, int routeIndex, AlignmentEditPlan plan, boolean reviewed,
            boolean applied, ManualJunctionEligibility.Reason assessedManualReason) {
        FrozenReplayInput input = new FrozenReplayInput(computed.request(),
                computed.evidence(), computed.captured().network(), computed.options());
        int selectedRoute = computed.pipeline().routes().isEmpty() ? -1 : routeIndex;
        ManualJunctionEligibility.Decision junction = computed.request().permissions()
                .junctionPolicy() == JunctionPolicy.FIXED ? null
                : computed.captured().junctionDecision() != null
                    ? computed.captured().junctionDecision()
                    : ManualJunctionEligibility.evaluate(computed.captured().network(),
                            computed.captured().specification());
        Format15Bundle bundle = Format15ProductionBundleFactory.createLive(LastSlideDebugBundle.buildIdentity(),
                input, computed.pipeline(), status,
                computed.captured().managedRaster() == null ? "visible-layer" : "managed-tiles",
                selectedRoute, plan, reviewed, applied, computed.counters(),
                assessedManualReason != null ? assessedManualReason
                        : junction != null && junction.manualOnly() ? junction.reason()
                        : junction != null && junction.reason()
                            == ManualJunctionEligibility.Reason.SIMPLE_T
                            && computed.pipeline().routes().isEmpty()
                                ? ManualJunctionEligibility.Reason.MISSING_RECEIVER_EVIDENCE
                                : null);
        return Format15ProductionBundleFactory.withCurrentNumericalPolicy(bundle, input, input.request().engine());
    }

    /** Builds the complete applied receipt before the real command can mutate the dataset. */
    public static void applyWithPreparedDiagnostics(java.util.function.Supplier<Format15Bundle> prepare,
            Runnable apply) {
        Format15Bundle prepared = java.util.Objects.requireNonNull(prepare.get(),
                "Prepared applied diagnostics are required");
        if (!prepared.artifactNames().containsAll(java.util.Set.of("frozen-input.bin",
                "frozen-edit-plan.bin", "edit-plan-identity.json", "applied-geometry.json",
                "attempt-status.json"))
                || !new String(prepared.artifact("attempt-status.json").bytes(),
                    java.nio.charset.StandardCharsets.UTF_8).contains("\"status\":\"applied\"")) {
            throw new IllegalArgumentException("Applied diagnostics lack exact plan or geometry evidence");
        }
        apply.run();
        DiagnosticsRegistry.setLastModernBundle(prepared);
    }

    @Override
    public void destroy() {
        if (livePreviewSession.close(activeLivePreviewOwner)) {
            overlay.hide();
        }
        if (ownsLivePreviewSession) {
            livePreviewSession.close();
        }
        if (activePreviewDialog != null) {
            activePreviewDialog.dispose();
            activePreviewDialog = null;
        }
        super.destroy();
    }

    ManagedHeatmapConfig effectiveConfig(ManagedHeatmapConfig config) {
        if (forcedAlignmentMode != null) {
            PluginLog.verbose("Using one-shot alignment mode override: %s.", forcedAlignmentMode);
        }
        if (forcedLivePreviewEngine != null) {
            PluginLog.verbose("Using explicit visible-source modern alignment: %s.",
                    forcedLivePreviewEngine);
        }
        return effectiveConfig(config, forcedAlignmentMode, forcedLivePreviewEngine);
    }

    static ManagedHeatmapConfig effectiveConfig(ManagedHeatmapConfig config,
            AlignmentMode forcedAlignmentMode, TrackerMode forcedLivePreviewEngine) {
        ManagedHeatmapConfig effective = forcedAlignmentMode == null ? config
                : config.withAlignmentMode(forcedAlignmentMode);
        return forcedLivePreviewEngine == null ? effective
                : effective.withAlignmentMode(AlignmentMode.PRECISE_SHAPE)
                        .withTrackerMode(forcedLivePreviewEngine);
    }

    /** Selects a required rendered layer for an explicit visible-source preview before UI setup. */
    static <T> T selectVisibleSource(boolean explicitVisibleSource, Supplier<T> requiredVisibleSource,
            Supplier<T> ordinarySource) {
        Objects.requireNonNull(requiredVisibleSource, "requiredVisibleSource");
        Objects.requireNonNull(ordinarySource, "ordinarySource");
        return explicitVisibleSource ? Objects.requireNonNull(requiredVisibleSource.get(),
                "Visible alignment requires a current rendered heatmap layer")
                : ordinarySource.get();
    }

    /** Resolves the persisted engine and source policy into one immutable production route. */
    static OrdinaryRoute resolveOrdinaryRoute(TracingSettings tracing, AlignmentConfig config) {
        Objects.requireNonNull(tracing, "tracing");
        Objects.requireNonNull(config, "config");
        if (!tracing.engine().capabilities().requiresEvidenceSnapshot()) {
            return new OrdinaryRoute(OrdinaryPipeline.LEGACY_COMPATIBILITY, null);
        }
        ModernAlignmentInvocation invocation = ModernAlignmentInvocation.resolve(tracing, config);
        OrdinaryPipeline pipeline = switch (invocation.resolvedSourceMode()) {
            case VISIBLE_LAYER -> OrdinaryPipeline.MODERN_VISIBLE;
            case MANAGED_TILES -> OrdinaryPipeline.MODERN_MANAGED;
            case AUTOMATIC -> throw new IllegalStateException("Modern source resolution remained automatic");
        };
        return new OrdinaryRoute(pipeline, invocation);
    }

    /** Executes the exact source-routing seam consumed by the ordinary action. */
    static <T> OrdinaryActionRouting<T> resolveOrdinaryAction(TracingSettings tracing,
            AlignmentConfig config, Supplier<T> requiredVisibleSource,
            Supplier<T> legacyVisibleSource) {
        OrdinaryRoute route = resolveOrdinaryRoute(tracing, config);
        return new OrdinaryActionRouting<>(route, selectOrdinarySource(route, config.heatmap(),
                requiredVisibleSource, legacyVisibleSource));
    }

    /** Acquires only the source required by the frozen ordinary route. */
    static <T> T selectOrdinarySource(OrdinaryRoute route, ManagedHeatmapConfig ignoredConfig,
            Supplier<T> requiredVisibleSource, Supplier<T> legacyVisibleSource) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(ignoredConfig, "ignoredConfig");
        Objects.requireNonNull(requiredVisibleSource, "requiredVisibleSource");
        Objects.requireNonNull(legacyVisibleSource, "legacyVisibleSource");
        return switch (route.pipeline()) {
            case LEGACY_COMPATIBILITY -> legacyVisibleSource.get();
            case MODERN_VISIBLE -> Objects.requireNonNull(requiredVisibleSource.get(),
                    "Modern visible alignment requires a current rendered heatmap layer");
            case MODERN_MANAGED -> null;
        };
    }

    static boolean matchesLivePreviewSettings(AlignmentConfig persistedAtCapture,
            AlignmentConfig effectiveAtCapture, AlignmentConfig currentPersisted,
            AlignmentMode forcedAlignmentMode, TrackerMode forcedLivePreviewEngine) {
        if (!persistedAtCapture.equals(currentPersisted)) {
            return false;
        }
        AlignmentConfig currentEffective = new AlignmentConfig(
                effectiveConfig(currentPersisted.heatmap(), forcedAlignmentMode,
                        forcedLivePreviewEngine), currentPersisted.cleanup());
        return effectiveAtCapture.equals(currentEffective);
    }

    static boolean matchesLivePreviewSettings(AlignmentConfig persistedAtCapture,
            AlignmentConfig effectiveAtCapture, AlignmentConfig currentPersisted,
            TracingSettings tracingAtCapture, TracingSettings currentTracing,
            AlignmentMode forcedAlignmentMode, TrackerMode forcedLivePreviewEngine) {
        if (!persistedAtCapture.equals(currentPersisted)) {
            return false;
        }
        if (forcedLivePreviewEngine == null
                && !Objects.equals(tracingAtCapture, currentTracing)) {
            return false;
        }
        ManagedHeatmapConfig currentBase = forcedLivePreviewEngine == null
                ? currentPersisted.heatmap().withTrackerMode(currentTracing.engine())
                : currentPersisted.heatmap();
        AlignmentConfig currentEffective = new AlignmentConfig(
                effectiveConfig(currentBase, forcedAlignmentMode, forcedLivePreviewEngine),
                currentPersisted.cleanup());
        return effectiveAtCapture.equals(currentEffective);
    }

    private static String livePreviewEngineLabel(TrackerMode engine) {
        return switch (engine) {
            case CORRIDOR_AWARE -> tr("Corridor-aware A");
            case PROBABILISTIC -> tr("Probabilistic B");
            case HYBRID -> tr("Hybrid A+B");
            case DIRECTIONAL_IMAGE -> tr("Directional Image");
            default -> tr("Modern");
        };
    }

    private static String livePreviewEngineShortLabel(TrackerMode engine) {
        return switch (engine) {
            case CORRIDOR_AWARE -> "A";
            case PROBABILISTIC -> "B";
            case HYBRID -> "Hybrid A+B";
            case DIRECTIONAL_IMAGE -> "Image";
            default -> "Modern";
        };
    }

    private void updateAggregateIntensityLayer(AlignmentResult result, ManagedHeatmapConfig config) {
        if (!config.showAggregateIntensityLayer() || !config.hasManagedAccessValues()) {
            AggregateIntensityLayer.removeExisting();
        }
    }

    private static String actionName(AlignmentMode forcedAlignmentMode,
            TrackerMode forcedLivePreviewEngine, boolean managedSource) {
        if (forcedLivePreviewEngine != null) {
            return managedSource
                ? tr("Engine {0} Managed Alignment",
                        livePreviewEngineShortLabel(forcedLivePreviewEngine))
                : tr("Engine {0} Visible Alignment",
                        livePreviewEngineShortLabel(forcedLivePreviewEngine));
        }
        if (forcedAlignmentMode == AlignmentMode.PRECISE_SHAPE) {
            return tr("Align Way to Heatmap Precisely");
        }
        if (forcedAlignmentMode == AlignmentMode.MOVE_EXISTING_NODES) {
            return tr("Align Way to Heatmap by Moving Nodes");
        }
        return tr("Align Way to Heatmap");
    }

    private static String actionTooltip(AlignmentMode forcedAlignmentMode,
            TrackerMode forcedLivePreviewEngine, boolean managedSource) {
        if (forcedLivePreviewEngine != null) {
            return managedSource
                ? tr("Preview Engine {0} from the selected managed tile source using configured access values",
                        livePreviewEngineShortLabel(forcedLivePreviewEngine))
                : tr("Preview Engine {0} using only the current rendered visible layer; stored credentials are not used for acquisition",
                        livePreviewEngineShortLabel(forcedLivePreviewEngine));
        }
        if (forcedAlignmentMode == AlignmentMode.PRECISE_SHAPE) {
            return tr("Align the selected way to a heatmap and rebuild the selected segment precisely");
        }
        if (forcedAlignmentMode == AlignmentMode.MOVE_EXISTING_NODES) {
            return tr("Align the selected way to a heatmap by moving the existing selected nodes");
        }
        return tr("Align the selected way geometry to a heatmap imagery layer");
    }

    private static Shortcut shortcut(AlignmentMode forcedAlignmentMode,
            TrackerMode forcedLivePreviewEngine, boolean managedSource) {
        if (forcedLivePreviewEngine != null) {
            String engine = livePreviewEngineShortLabel(forcedLivePreviewEngine).toLowerCase(Locale.ROOT);
            String source = managedSource ? "managed" : "visible";
            return Shortcut.registerShortcut("wayheatmaptracer:experimental-" + engine + "-" + source + "-preview",
                    tr("WayHeatmapTracer: Engine {0} {1} Alignment",
                            livePreviewEngineShortLabel(forcedLivePreviewEngine),
                            managedSource ? tr("Managed") : tr("Visible")),
                    KeyEvent.VK_UNDEFINED, Shortcut.NONE);
        }
        if (forcedAlignmentMode == AlignmentMode.PRECISE_SHAPE) {
            return Shortcut.registerShortcut("wayheatmaptracer:align-precise",
                tr("WayHeatmapTracer: Align Way to Heatmap Precisely"), KeyEvent.VK_S, Shortcut.ALT_CTRL_SHIFT);
        }
        if (forcedAlignmentMode == AlignmentMode.MOVE_EXISTING_NODES) {
            return Shortcut.registerShortcut("wayheatmaptracer:align-move-nodes",
                tr("WayHeatmapTracer: Align Way to Heatmap by Moving Nodes"), KeyEvent.VK_M, Shortcut.ALT_CTRL_SHIFT);
        }
        return Shortcut.registerShortcut("wayheatmaptracer:align",
            tr("WayHeatmapTracer: Align Way to Heatmap"), KeyEvent.VK_Y, Shortcut.CTRL_SHIFT);
    }

    @Override
    protected void updateEnabledState() {
        setEnabled(MainApplication.getLayerManager().getEditDataSet() != null);
    }

    private void showCandidatePreview(
        DataSet dataSet,
        SelectionContext selection,
        AlignmentResult result,
        AlignmentConfig slideConfig,
        ImageryLayer imageryLayer,
        MapView mapView,
        Map<String, CandidateRating> candidateRatings
    ) {
        ManagedHeatmapConfig config = slideConfig.heatmap();
        GeometryCleanupConfig cleanupConfig = slideConfig.cleanup();
        if (result.candidates().isEmpty()) {
            throw new IllegalStateException(tr("No centerline candidate could be extracted from the heatmap."));
        }
        CenterlineCandidate initial = initialCandidate(result);
        boolean ratingMode = config.candidateRatingEnabled();
        boolean[] loadingRating = {false};
        CandidateReviewConfirmation[] reviewConfirmation = {null};
        PreviewSelection[] current = {buildPreviewSelection(dataSet, result, initial, initial, config)};
        CandidateAssessment initialAssessment = AlignmentService.assessCandidate(initial);
        overlay.show(selection, current[0].result(), initial, initialAssessment.disposition(), false,
            PluginPreferences.isDebugEnabled());
        JComboBox<CenterlineCandidate> comboBox = new JComboBox<>();
        comboBox.setModel(new DefaultComboBoxModel<>(result.candidates().toArray(CenterlineCandidate[]::new)));
        comboBox.setSelectedItem(initial);
        comboBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean selected, boolean focused
            ) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                if (value instanceof CenterlineCandidate candidate) {
                    CandidateAssessment assessment = AlignmentService.assessCandidate(candidate);
                    setText(candidateListLabel(candidate, assessment,
                        confirmationMatches(reviewConfirmation[0], candidate, current[0])));
                }
                return this;
            }
        });
        JComboBox<String> ratingBox = new JComboBox<>(RATING_VALUES);
        JCheckBox offTheLine = new JCheckBox(tr("off-the-line"));
        JCheckBox jumping = new JCheckBox(tr("jumping"));
        JCheckBox unnecessaryKinks = new JCheckBox(tr("unnecessary kinks"));
        JCheckBox badJunctionShapes = new JCheckBox(tr("bad junction shapes"));
        JButton apply = new JButton(tr("Apply"));
        apply.setEnabled(initialAssessment.automaticallyApplicable());
        JButton confirm = new JButton(tr("Confirm reviewed candidate"));
        confirm.setVisible(canConfirmCandidate(initialAssessment));
        confirm.setEnabled(canConfirmCandidate(initialAssessment));
        JButton retry = new JButton(tr("Retry with wider search..."));
        configureRetryButton(retry, initial, slideConfig, result);

        JPanel panel = buildSummaryPanel(
            current[0].result(),
            initial,
            config,
            cleanupConfig,
            result.candidates().size() > 1 ? comboBox : null,
            ratingMode ? ratingBox : null,
            offTheLine,
            jumping,
            unnecessaryKinks,
            badJunctionShapes
        );
        double currentSearchHalfWidth = retrySearchBounds(slideConfig, result).currentMeters();
        JLabel selectedCoverageStatus = new JLabel(coverageStatus(
            initial, currentSearchHalfWidth, initialAssessment, false));
        panel.add(selectedCoverageStatus, GBC.eol());
        JLabel selectedCleanupStatus = new JLabel(cleanupStatus(initial));
        panel.add(selectedCleanupStatus, GBC.eol());
        JLabel selectedCandidateDetail = new JLabel(cleanupDetail(initial));
        panel.add(selectedCandidateDetail, GBC.eol());
        comboBox.addActionListener(event -> {
            CenterlineCandidate selected = (CenterlineCandidate) comboBox.getSelectedItem();
            if (selected == null) {
                return;
            }
            try {
                reviewConfirmation[0] = null;
                current[0] = buildPreviewSelection(dataSet, result, current[0].initialCandidate(), selected, config);
                CandidateAssessment assessment = AlignmentService.assessCandidate(selected);
                overlay.show(selection, current[0].result(), selected, assessment.disposition(), false,
                    PluginPreferences.isDebugEnabled());
                selectedCoverageStatus.setText(coverageStatus(
                    selected, currentSearchHalfWidth, assessment, false));
                selectedCleanupStatus.setText(cleanupStatus(selected));
                apply.setEnabled(assessment.automaticallyApplicable());
                confirm.setVisible(canConfirmCandidate(assessment));
                confirm.setEnabled(canConfirmCandidate(assessment));
                confirm.setText(tr("Confirm reviewed candidate"));
                java.awt.Window previewWindow = SwingUtilities.getWindowAncestor(confirm);
                if (previewWindow != null) {
                    previewWindow.pack();
                }
                selectedCandidateDetail.setText(cleanupDetail(selected));
                configureRetryButton(retry, selected, slideConfig, result);
                comboBox.repaint();
                loadingRating[0] = true;
                loadCandidateRating(candidateRatings.get(selected.id()), ratingBox, offTheLine, jumping, unnecessaryKinks, badJunctionShapes);
                loadingRating[0] = false;
                updatePreviewBundle(current[0], candidateRatings, "preview-open");
            } catch (Exception ex) {
                Logging.warn("WayHeatmapTracer rejected preview candidate: " + ex.getMessage());
                showError(tr("WayHeatmapTracer failed: {0}", ex.getMessage()));
                comboBox.setSelectedItem(current[0].candidate());
            }
        });
        ratingBox.addActionListener(event -> {
            if (!loadingRating[0]) {
                saveCandidateRating(current[0], candidateRatings, ratingBox, offTheLine, jumping, unnecessaryKinks, badJunctionShapes);
            }
        });
        offTheLine.addActionListener(event -> saveCandidateRating(current[0], candidateRatings, ratingBox, offTheLine, jumping, unnecessaryKinks, badJunctionShapes));
        jumping.addActionListener(event -> saveCandidateRating(current[0], candidateRatings, ratingBox, offTheLine, jumping, unnecessaryKinks, badJunctionShapes));
        unnecessaryKinks.addActionListener(event -> saveCandidateRating(current[0], candidateRatings, ratingBox, offTheLine, jumping, unnecessaryKinks, badJunctionShapes));
        badJunctionShapes.addActionListener(event -> saveCandidateRating(current[0], candidateRatings, ratingBox, offTheLine, jumping, unnecessaryKinks, badJunctionShapes));

        JButton cancel = new JButton(tr("Cancel"));
        JPanel buttons = new JPanel();
        buttons.add(confirm);
        buttons.add(apply);
        buttons.add(retry);
        buttons.add(cancel);
        panel.add(buttons, GBC.eol());

        JDialog dialog = new JDialog(MainApplication.getMainFrame(), tr("Preview Heatmap Alignment"), false);
        activePreviewDialog = dialog;
        dialog.setContentPane(panel);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.pack();
        dialog.setLocationRelativeTo(MainApplication.getMainFrame());
        confirm.addActionListener(event -> {
            try {
                CandidateAssessment assessment = AlignmentService.assessCandidate(current[0].candidate());
                if (!canConfirmCandidate(assessment)) {
                    throw new IllegalStateException(tr("This candidate cannot be enabled by review."));
                }
                int answer = JOptionPane.showConfirmDialog(
                    dialog,
                    tr("Confirm that you reviewed the complete displayed geometry. Evidence uncertainty will be "
                        + "accepted, but all geometry and topology safety checks will remain mandatory."),
                    tr("Confirm reviewed candidate"),
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
                );
                if (answer != JOptionPane.YES_OPTION) {
                    return;
                }
                SelectionIntegrity.requirePreviewSourceUnchanged(
                    dataSet, selection, current[0].result().sourcePolyline());
                requireCandidateAssignmentPlan(selection, current[0].result(),
                    current[0].candidate(), config);
                alignmentService.requireCurrentTopologySafe(current[0].candidate(), selection);
                if (!config.allowUndownloadedAlignment()) {
                    requirePreviewWithinDownloadedArea(current[0].result().previewPolyline(), dataSet);
                }
                reviewConfirmation[0] = CandidateReviewConfirmation.capture(
                    current[0].candidate(), current[0].result().previewPolyline());
                apply.setEnabled(true);
                confirm.setEnabled(false);
                confirm.setText(tr("Review confirmed"));
                selectedCoverageStatus.setText(coverageStatus(current[0].candidate(),
                    currentSearchHalfWidth, assessment, true));
                overlay.show(selection, current[0].result(), current[0].candidate(),
                    assessment.disposition(), true, PluginPreferences.isDebugEnabled());
                comboBox.repaint();
                PluginLog.verbose("Candidate review confirmed: candidate=%s previewPoints=%d.",
                    current[0].candidate().id(), current[0].result().previewPolyline().size());
                updatePreviewBundle(current[0], candidateRatings, "review-confirmed");
            } catch (Exception ex) {
                PluginLog.verbose("Candidate review confirmation failed: %s", ex.toString());
                updatePreviewBundle(current[0], candidateRatings, "review-confirmation-failed");
                showError(tr("WayHeatmapTracer review confirmation failed: {0}", ex.getMessage()));
            }
        });
        retry.addActionListener(event -> retryWithWiderSearch(
            dialog, dataSet, selection, current[0], slideConfig, imageryLayer, mapView, candidateRatings
        ));
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                activePreviewDialog = null;
            }

            @Override
            public void windowClosing(WindowEvent e) {
                cancelPreview(current[0], candidateRatings);
            }
        });
        apply.addActionListener(event -> {
            try {
                applyPreview(dataSet, selection, current[0], config, candidateRatings,
                    reviewConfirmation[0]);
                dialog.dispose();
                overlay.hide();
                PluginLog.endSlideSession();
            } catch (Exception ex) {
                Logging.error(ex);
                PluginLog.verbose("Alignment apply failed with exception: %s", ex.toString());
                DiagnosticsRegistry.setLastBundle(LastSlideDebugBundle.fromResult(
                    current[0].result(),
                    current[0].candidate(),
                    current[0].initialCandidate(),
                    reviewConfirmation[0] == null ? "apply-failed" : "review-apply-failed",
                    PluginLog.currentSlideLog(),
                    candidateRatings
                ));
                reviewConfirmation[0] = null;
                overlay.hide();
                PluginLog.endSlideSession();
                dialog.dispose();
                showError(tr("WayHeatmapTracer failed: {0}", ex.getMessage()));
            }
        });
        cancel.addActionListener(event -> {
            cancelPreview(current[0], candidateRatings);
            dialog.dispose();
        });
        dialog.setVisible(true);
    }

    /**
     * Selects the candidate initially shown by preview and recorded by diagnostics.
     *
     * @param result completed alignment result
     * @return first applicable candidate, then the first review-required candidate, then the first blocked candidate
     * @throws IllegalStateException when the result contains no candidates
     */
    static CenterlineCandidate initialCandidate(AlignmentResult result) {
        List<CenterlineCandidate> preferredCandidates = result.applicableCandidates().isEmpty()
            ? result.candidates().stream()
                .filter(candidate -> AlignmentService.assessCandidate(candidate).reviewRequired())
                .toList()
            : result.applicableCandidates();
        CenterlineCandidate selected = InitialPreviewCandidatePolicy.select(
            result.candidates(), preferredCandidates);
        if (selected != null) {
            return selected;
        }
        throw new IllegalStateException(tr("No centerline candidate could be extracted from the heatmap."));
    }

    private PreviewSelection buildPreviewSelection(
        DataSet dataSet,
        AlignmentResult base,
        CenterlineCandidate initialCandidate,
        CenterlineCandidate candidate,
        ManagedHeatmapConfig config
    ) {
        SelectionIntegrity.requirePreviewSourceUnchanged(dataSet, base.selection(), base.sourcePolyline());
        CandidateAssessment assessment = AlignmentService.assessCandidate(candidate);
        if (assessment.disposition() == CandidateAssessment.Disposition.HARD_BLOCKED) {
            List<EastNorth> geometry = candidate.finalPreviewPoints().size() >= 2
                ? candidate.finalPreviewPoints()
                : candidate.eastNorthPoints().size() >= 2 ? candidate.eastNorthPoints() : base.sourcePolyline();
            AlignmentResult diagnostic = new AlignmentResult(base.selection(), base.capturedHeatmap(),
                base.candidates(), base.sourcePolyline(), geometry, List.of(), base.diagnostics(), base.tileMosaics(),
                base.detectorAttempts(), base.applicableCandidates());
            return new PreviewSelection(initialCandidate, candidate, diagnostic);
        }
        AlignmentResult candidateResult = assessment.automaticallyApplicable()
            ? alignmentService.applyCandidate(base, candidate, config)
            : alignmentService.previewCandidate(base, candidate, config);
        if (!config.allowUndownloadedAlignment()) {
            requirePreviewWithinDownloadedArea(candidateResult.previewPolyline(), dataSet);
        }
        return new PreviewSelection(initialCandidate, candidate, candidateResult);
    }


    /**
     * Validates the complete candidate-owned assignment plan before review or Apply.
     *
     * @param selection slide-time selected segment
     * @param preview exact candidate preview
     * @param candidate candidate owning existing-node targets
     * @param config slide-time heatmap configuration
     */
    private static void requireCandidateAssignmentPlan(
        SelectionContext selection,
        AlignmentResult preview,
        CenterlineCandidate candidate,
        ManagedHeatmapConfig config
    ) {
        if (config.trackerMode() == TrackerMode.CORRIDOR_AWARE
            && AlignmentService.effectiveAlignmentMode(selection, config) == AlignmentMode.PRECISE_SHAPE) {
            ReplaceWaySegmentCommand.validateProposedNodePositions(
                selection,
                preview.sourcePolyline(),
                preview.previewPolyline(),
                candidate.proposedNodePositions()
            );
        }
    }

    private static boolean confirmationMatches(
        CandidateReviewConfirmation confirmation,
        CenterlineCandidate candidate,
        PreviewSelection preview
    ) {
        return confirmation != null && preview != null && preview.candidate().id().equals(candidate.id())
            && confirmation.matches(candidate, preview.result().previewPolyline());
    }

    /**
     * Builds a candidate label from its typed preview disposition.
     *
     * @param candidate candidate represented by the list row
     * @param assessment current typed disposition
     * @param reviewConfirmed whether this exact preview was explicitly confirmed
     * @return user-facing candidate label
     */
    static String candidateListLabel(
        CenterlineCandidate candidate,
        CandidateAssessment assessment,
        boolean reviewConfirmed
    ) {
        String disposition = switch (assessment.disposition()) {
            case APPLICABLE -> tr("applicable");
            case REVIEW_REQUIRED -> reviewConfirmed ? tr("review confirmed") : tr("review required");
            case HARD_BLOCKED -> tr("blocked");
        };
        String reason = candidate.evidence().corridorCoverage().reason();
        if ("complete-with-search-edge-bridge".equals(reason)) {
            return tr("{0} - {1} - search-edge gaps bridged", candidate.displayName(), disposition);
        }
        if ("unresolved-search-edge-censoring".equals(reason)) {
            return tr("{0} - {1} - incomplete search-edge evidence", candidate.displayName(), disposition);
        }
        return tr("{0} - {1}", candidate.displayName(), disposition);
    }

    /**
     * Builds a selected-candidate coverage message without treating reviewable uncertainty as a hard stop.
     *
     * @param candidate selected preview candidate
     * @param searchHalfWidthMeters factual slide-time half-width
     * @param assessment current typed disposition
     * @param reviewConfirmed whether this exact preview was explicitly confirmed
     * @return user-facing coverage status
     */
    static String coverageStatus(
        CenterlineCandidate candidate,
        double searchHalfWidthMeters,
        CandidateAssessment assessment,
        boolean reviewConfirmed
    ) {
        var coverage = candidate.evidence().corridorCoverage();
        if (!coverage.measured()) {
            return tr("Corridor coverage: not measured for this detector.");
        }
        if ("complete-with-search-edge-bridge".equals(coverage.reason())) {
            String message = tr("Search-edge gaps were interpolated from surrounding evidence.");
            return assessment.automaticallyApplicable() || reviewConfirmed
                ? message
                : tr("{0} Another safety finding blocks this candidate.", message);
        }
        if (assessment.reviewRequired()) {
            if (reviewConfirmed) {
                return tr("Incomplete corridor evidence was reviewed and confirmed for this preview.");
            }
            if ("unresolved-search-edge-censoring".equals(coverage.reason())) {
                return tr("Review required: heatmap evidence reaches the configured {0} m search boundary; review the complete "
                        + "preview before confirming it.",
                    String.format(Locale.ROOT, "%.1f", searchHalfWidthMeters));
            }
            return tr("Review required: corridor evidence is incomplete; review the complete preview before confirming it.");
        }
        if (coverage.complete()) {
            return assessment.automaticallyApplicable()
                ? tr("Corridor coverage: complete.")
                : tr("Corridor coverage is complete, but a structural safety finding blocks this candidate.");
        }
        return tr("Corridor evidence is incomplete and another safety finding blocks this candidate.");
    }

    /** Returns whether explicit review can promote this candidate. */
    static boolean canConfirmCandidate(CandidateAssessment assessment) {
        return assessment != null && assessment.reviewRequired();
    }


    /**
     * Returns whether a candidate has bridge or unresolved search-edge coverage that warrants an
     * explicit larger acquisition. This deliberately excludes generic no-signal failures.
     *
     * @param candidate selected preview candidate
     * @return whether the preview should offer wider-search retry
     */
    static boolean canRetryWithWiderSearch(CenterlineCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        var coverage = candidate.evidence().corridorCoverage();
        return coverage.measured() && ("complete-with-search-edge-bridge".equals(coverage.reason())
            || "unresolved-search-edge-censoring".equals(coverage.reason()));
    }

    private void configureRetryButton(
        JButton retry,
        CenterlineCandidate candidate,
        AlignmentConfig slideConfig,
        AlignmentResult result
    ) {
        boolean offer = canRetryWithWiderSearch(candidate);
        retry.setVisible(offer);
        if (!offer) {
            return;
        }
        RetrySearchBounds bounds = retrySearchBounds(slideConfig, result);
        boolean canExpand = bounds.maximumMeters() > bounds.currentMeters() + 1e-6;
        retry.setEnabled(canExpand);
        retry.setToolTipText(canExpand
            ? tr("Re-run the complete selected segment with a larger search width")
            : tr("No wider ordinary retry is available within the 14 m and sampler limits"));
    }

    private RetrySearchBounds retrySearchBounds(AlignmentConfig slideConfig, AlignmentResult result) {
        ManagedHeatmapConfig source = slideConfig.effectiveHeatmap();
        if (source.hasManagedAccessValues()) {
            double current = source.searchHalfWidthMeters();
            return new RetrySearchBounds(current, ordinaryRetryMaximumMeters(current,
                TileHeatmapSampler.maximumSearchHalfWidthMeters(source, result.sourcePolyline())));
        }
        double current = AlignmentService.visibleSearchHalfWidthMeters(slideConfig, result.sourcePolyline());
        return new RetrySearchBounds(current, ordinaryRetryMaximumMeters(current,
            AlignmentService.maximumVisibleSearchHalfWidthMeters(result.sourcePolyline())));
    }

    private void retryWithWiderSearch(
        JDialog dialog,
        DataSet dataSet,
        SelectionContext selection,
        PreviewSelection current,
        AlignmentConfig slideConfig,
        ImageryLayer imageryLayer,
        MapView mapView,
        Map<String, CandidateRating> candidateRatings
    ) {
        if (!canRetryWithWiderSearch(current.candidate())) {
            return;
        }
        RetrySearchBounds bounds = retrySearchBounds(slideConfig, current.result());
        Double requested = promptRetryWidth(dialog, bounds);
        if (requested == null) {
            return;
        }
        try {
            SelectionIntegrity.requirePreviewSourceUnchanged(dataSet, selection, current.result().sourcePolyline());
            requireRetrySourceUnchanged(slideConfig.heatmap(), imageryLayer);
            AlignmentConfig retryConfig = slideConfig.withSearchHalfWidthMetersOverride(requested);
            PluginLog.verbose("Wider-search retry requested: priorHalfWidth=%.3f m, newHalfWidth=%.3f m, "
                + "candidate=%s, coverageReason=%s.", bounds.currentMeters(), requested,
                current.candidate().id(), current.candidate().evidence().corridorCoverage().reason());
            AlignmentResult retried = alignmentService.align(selection, imageryLayer, mapView, retryConfig);
            updateAggregateIntensityLayer(retried, retryConfig.effectiveHeatmap());
            CenterlineCandidate retryInitial = initialCandidate(retried);
            DiagnosticsRegistry.setLastBundle(LastSlideDebugBundle.fromResult(
                retried, retryInitial, retryInitial, "preview-open", PluginLog.currentSlideLog(), candidateRatings));
            overlay.hide();
            activePreviewDialog = null;
            dialog.dispose();
            showCandidatePreview(dataSet, selection, retried, retryConfig, imageryLayer, mapView, candidateRatings);
        } catch (AlignmentService.AlignmentFailureException exception) {
            AlignmentResult failed = exception.partialResult();
            CenterlineCandidate failedCandidate = failed.candidates().isEmpty()
                ? null : failed.candidates().get(0);
            DiagnosticsRegistry.setLastBundle(LastSlideDebugBundle.fromResult(
                failed, failedCandidate, failedCandidate, "wider-search-failed",
                PluginLog.currentSlideLog(), candidateRatings));
            PluginLog.verbose("Wider-search retry failed without changing the current preview: %s", exception.getMessage());
            showError(tr("Wider-search retry failed: {0}. The existing preview and ratings were kept.",
                exception.getMessage()));
        } catch (Exception exception) {
            DiagnosticsRegistry.setLastBundle(LastSlideDebugBundle.fromResult(
                current.result(), current.candidate(), current.candidate(), "wider-search-rejected",
                PluginLog.currentSlideLog(), candidateRatings));
            PluginLog.verbose("Wider-search retry was rejected without changing the current preview: %s",
                exception.getMessage());
            showError(tr("Wider-search retry was not run: {0}. The existing preview and ratings were kept.",
                exception.getMessage()));
        }
    }

    /** Returns the bounded ordinary retry limit without shrinking an explicit existing width. */
    static double ordinaryRetryMaximumMeters(double currentMeters, double samplerMaximumMeters) {
        return Math.max(currentMeters,
            Math.min(MAX_ORDINARY_SEARCH_HALF_WIDTH_METERS, samplerMaximumMeters));
    }

    /** Returns the default one-shot retry width within the already bounded range. */
    static double defaultRetryWidthMeters(double currentMeters, double maximumMeters) {
        return Math.min(maximumMeters, currentMeters * 2.0);
    }

    private Double promptRetryWidth(JDialog dialog, RetrySearchBounds bounds) {
        if (bounds.maximumMeters() <= bounds.currentMeters() + 1e-6) {
            showError(tr("No wider ordinary retry is available within the 14 m and sampler limits."));
            return null;
        }
        double defaultWidth = defaultRetryWidthMeters(bounds.currentMeters(), bounds.maximumMeters());
        String answer = JOptionPane.showInputDialog(
            dialog,
            tr("Search half-width in metres ({0} to {1})",
                String.format(Locale.ROOT, "%.2f", bounds.currentMeters()),
                String.format(Locale.ROOT, "%.2f", bounds.maximumMeters())),
            String.format(Locale.ROOT, "%.2f", defaultWidth)
        );
        if (answer == null) {
            return null;
        }
        try {
            double requested = Double.parseDouble(answer.trim().replace(",", "."));
            if (!Double.isFinite(requested) || requested <= bounds.currentMeters() + 1e-6
                || requested > bounds.maximumMeters() + 1e-6) {
                throw new IllegalArgumentException(tr("Enter a width larger than {0} and no greater than {1} metres.",
                    String.format(Locale.ROOT, "%.2f", bounds.currentMeters()),
                    String.format(Locale.ROOT, "%.2f", bounds.maximumMeters())));
            }
            return requested;
        } catch (NumberFormatException exception) {
            showError(tr("Enter a finite number of metres."));
            return null;
        } catch (IllegalArgumentException exception) {
            showError(exception.getMessage());
            return null;
        }
    }

    private void requireRetrySourceUnchanged(ManagedHeatmapConfig sourceConfig, ImageryLayer sourceLayer) {
        ManagedHeatmapConfig currentConfig = effectiveConfig(PluginPreferences.load());
        if (HeatmapLayerResolver.resolveOptional().orElse(null) != sourceLayer) {
            throw new IllegalStateException(
                "The heatmap layer changed after the preview opened. Run a new slide.");
        }
        if (sourceConfig.hasManagedAccessValues()) {
            if (!sourceConfig.hasSameManagedSource(currentConfig)) {
                throw new IllegalStateException("Heatmap source settings changed after the preview opened. Run a new slide.");
            }
            return;
        }
        if (currentConfig.hasManagedAccessValues()
            || !java.util.Objects.equals(sourceConfig.color(), currentConfig.color())
            || !java.util.Objects.equals(sourceConfig.manualLayerName(), currentConfig.manualLayerName())
            || !java.util.Objects.equals(sourceConfig.layerRegex(), currentConfig.layerRegex())
            || HeatmapLayerResolver.resolve() != sourceLayer) {
            throw new IllegalStateException("The rendered heatmap layer changed after the preview opened. Run a new slide.");
        }
    }

    private record RetrySearchBounds(double currentMeters, double maximumMeters) {
        private RetrySearchBounds {
            if (!Double.isFinite(currentMeters) || !Double.isFinite(maximumMeters)
                || currentMeters <= 0.0 || maximumMeters < 0.0) {
                throw new IllegalArgumentException("Retry search bounds must be finite and non-negative");
            }
        }
    }

    private void loadCandidateRating(
        CandidateRating rating,
        JComboBox<String> ratingBox,
        JCheckBox offTheLine,
        JCheckBox jumping,
        JCheckBox unnecessaryKinks,
        JCheckBox badJunctionShapes
    ) {
        ratingBox.setSelectedItem(rating == null ? "" : rating.rating());
        List<String> features = rating == null ? List.of() : rating.negativeFeatures();
        offTheLine.setSelected(features.contains(FEATURE_OFF_THE_LINE));
        jumping.setSelected(features.contains(FEATURE_JUMPING));
        unnecessaryKinks.setSelected(features.contains(FEATURE_UNNECESSARY_KINKS));
        badJunctionShapes.setSelected(features.contains(FEATURE_BAD_JUNCTION_SHAPES));
    }

    private void saveCandidateRating(
        PreviewSelection preview,
        Map<String, CandidateRating> candidateRatings,
        JComboBox<String> ratingBox,
        JCheckBox offTheLine,
        JCheckBox jumping,
        JCheckBox unnecessaryKinks,
        JCheckBox badJunctionShapes
    ) {
        String rating = (String) ratingBox.getSelectedItem();
        List<String> features = negativeFeatures(offTheLine, jumping, unnecessaryKinks, badJunctionShapes);
        CandidateRating candidateRating = new CandidateRating(rating, features);
        if (candidateRating.isEmpty()) {
            candidateRatings.remove(preview.candidate().id());
        } else {
            candidateRatings.put(preview.candidate().id(), candidateRating);
        }
        PluginLog.verbose("CandidateRating candidate=%s rating='%s' negativeFeatures=%s.",
            preview.candidate().id(), rating == null ? "" : rating, features);
        updatePreviewBundle(preview, candidateRatings, "preview-open");
    }

    private List<String> negativeFeatures(JCheckBox offTheLine, JCheckBox jumping, JCheckBox unnecessaryKinks, JCheckBox badJunctionShapes) {
        List<String> features = new java.util.ArrayList<>();
        if (offTheLine.isSelected()) {
            features.add(FEATURE_OFF_THE_LINE);
        }
        if (jumping.isSelected()) {
            features.add(FEATURE_JUMPING);
        }
        if (unnecessaryKinks.isSelected()) {
            features.add(FEATURE_UNNECESSARY_KINKS);
        }
        if (badJunctionShapes.isSelected()) {
            features.add(FEATURE_BAD_JUNCTION_SHAPES);
        }
        return features;
    }

    private void updatePreviewBundle(PreviewSelection preview, Map<String, CandidateRating> candidateRatings, String status) {
        DiagnosticsRegistry.setLastBundle(LastSlideDebugBundle.fromResult(
            preview.result(),
            preview.candidate(),
            preview.initialCandidate(),
            status,
            PluginLog.currentSlideLog(),
            candidateRatings
        ));
    }
    /**
     * Builds the prominent candidate-specific cleanup notification shown above technical details.
     *
     * @param candidate selected preview candidate
     * @return concise human-readable cleanup status
     */
    static String cleanupStatus(CenterlineCandidate candidate) {
        CandidateGeometryCleanup cleanup = candidate.geometryCleanup();
        return switch (cleanup.outcome()) {
            case NOT_REQUESTED -> tr("Cleanup status: not requested.");
            case SKIPPED -> tr("Cleanup status: skipped; no safe interval was changed ({0}).",
                cleanupReasonLabel(cleanup.reasonCode()));
            case UNCHANGED -> cleanup.frozenIntervalCount() > 0
                ? tr("Cleanup status: {0} safe interval(s) were evaluated; {1} protected neighborhood(s) "
                    + "stayed unchanged and no geometric change was accepted.",
                    cleanup.eligibleIntervalCount(), cleanup.frozenIntervalCount())
                : tr("Cleanup status: evaluated safely, but no geometric change was accepted.");
            case CLEANED_ALTERNATIVE_AVAILABLE ->
                tr("Cleanup status: a separate cleaned result is available in the candidate list.");
            case CLEANED -> tr("Cleanup status: fully cleaned ({0} to {1} points).",
                cleanup.beforePointCount(), cleanup.afterPointCount());
            case PARTIALLY_CLEANED -> partialCleanupStatus(cleanup);
            case REJECTED -> tr("Cleanup status: rejected for safety; the raw traced result is shown.");
        };
    }


    private static String partialCleanupStatus(CandidateGeometryCleanup cleanup) {
        if (cleanup.frozenIntervalCount() > 0) {
            return tr(
                "Cleanup status: partially cleaned in {0} interval(s); "
                    + "{1} protected neighborhood(s) stayed unchanged.",
                cleanup.changedIntervalCount(), cleanup.frozenIntervalCount());
        }
        return tr(
            "Cleanup status: partially cleaned in {0} interval(s); "
                + "other eligible geometry stayed unchanged for safety.",
            cleanup.changedIntervalCount());
    }
    private static String cleanupReasonLabel(String reasonCode) {
        return switch (reasonCode) {
            case "no-eligible-cleanup-interval" ->
                tr("no independently safe cleanup interval exists outside the protected neighborhood");
            case "alignment-mode-ineligible" -> tr("cleanup requires Precise Shape mode");
            case "tracker-mode-ineligible" -> tr("cleanup requires Corridor Aware tracking");
            default -> reasonCode;
        };
    }


    private void cancelPreview(PreviewSelection preview, Map<String, CandidateRating> candidateRatings) {
        PluginLog.verbose("Alignment cancelled at preview dialog.");
        DiagnosticsRegistry.setLastBundle(LastSlideDebugBundle.fromResult(
            preview.result(),
            preview.candidate(),
            preview.initialCandidate(),
            "cancelled",
            PluginLog.currentSlideLog(),
            candidateRatings
        ));
        overlay.hide();
        PluginLog.endSlideSession();
    }

    private void applyPreview(
        DataSet dataSet,
        SelectionContext selection,
        PreviewSelection preview,
        ManagedHeatmapConfig config,
        Map<String, CandidateRating> candidateRatings,
        CandidateReviewConfirmation reviewConfirmation
    ) {
        CenterlineCandidate chosen = preview.candidate();
        AlignmentResult chosenResult = preview.result();
        CandidateAssessment assessment = AlignmentService.assessCandidate(chosen);
        if (assessment.disposition() == CandidateAssessment.Disposition.HARD_BLOCKED) {
            throw new IllegalStateException(tr("This candidate is blocked by a structural or signal-safety finding."));
        }
        if (assessment.reviewRequired()
            && !confirmationMatches(reviewConfirmation, chosen, preview)) {
            throw new IllegalStateException(tr("Review and confirm this exact candidate preview before applying it."));
        }

        SelectionIntegrity.requirePreviewSourceUnchanged(dataSet, selection, chosenResult.sourcePolyline());
        requireCandidateAssignmentPlan(selection, chosenResult, chosen, config);
        if (config.trackerMode() == TrackerMode.CORRIDOR_AWARE) {
            alignmentService.requireCurrentTopologySafe(chosen, selection);
        }
        if (!config.allowUndownloadedAlignment()) {
            requirePreviewWithinDownloadedArea(chosenResult.previewPolyline(), dataSet);
        }

        AlignmentMode effectiveMode = AlignmentService.effectiveAlignmentMode(selection, config);
        if (effectiveMode == AlignmentMode.MOVE_EXISTING_NODES
            && chosenResult.nodeMoves().isEmpty()) {
            throw new IllegalStateException(tr("No movable interior nodes were found in the selected segment."));
        }

        if (effectiveMode == AlignmentMode.MOVE_EXISTING_NODES) {
            PluginLog.verbose("Applying move-existing-nodes alignment for candidate %s with %d node moves.", chosen.id(), chosenResult.nodeMoves().size());
            UndoRedoHandler.getInstance().add(new MoveNodesCommand(
                dataSet,
                chosenResult.nodeMoves(),
                tr("Align way to heatmap")
            ));
        } else {
            PluginLog.verbose("Applying precise-shape alignment for candidate %s with %d preview points.", chosen.id(), chosenResult.previewPolyline().size());
            Map<Long, EastNorth> proposedNodePositions = config.trackerMode() == TrackerMode.CORRIDOR_AWARE
                ? chosen.proposedNodePositions() : null;
            if (config.trackerMode() == TrackerMode.CORRIDOR_AWARE && proposedNodePositions.isEmpty()) {
                throw new IllegalStateException(
                    "The corridor-aware preview has no existing-node assignment plan. Run the slide again.");
            }
            UndoRedoHandler.getInstance().add(new ReplaceWaySegmentCommand(
                dataSet,
                selection.way(),
                selection,
                chosenResult.previewPolyline(),
                proposedNodePositions,
                tr("Align way to heatmap precisely")
            ));
        }
        DiagnosticsRegistry.setLastBundle(LastSlideDebugBundle.fromResult(
            chosenResult, chosen, preview.initialCandidate(),
            reviewConfirmation == null ? "applied" : "applied-after-review",
            PluginLog.currentSlideLog(), candidateRatings));
    }

    private JPanel buildSummaryPanel(
        AlignmentResult result,
        CenterlineCandidate chosen,
        ManagedHeatmapConfig config,
        GeometryCleanupConfig cleanupConfig,
        JComboBox<CenterlineCandidate> candidates,
        JComboBox<String> ratingBox,
        JCheckBox offTheLine,
        JCheckBox jumping,
        JCheckBox unnecessaryKinks,
        JCheckBox badJunctionShapes
    ) {
        JPanel panel = new JPanel(new java.awt.GridBagLayout());
        if (candidates != null) {
            panel.add(new JLabel(tr("Detected ridge")), GBC.std());
            panel.add(candidates, GBC.eol().fill(GBC.HORIZONTAL));
            panel.add(new JLabel(tr("Changing the ridge updates the map preview immediately.")), GBC.eol());
        } else {
            panel.add(new JLabel(tr("Candidate: {0}", chosen.toString())), GBC.eol());
        }
        if (ratingBox != null) {
            panel.add(new JLabel(tr("Visual rating")), GBC.std());
            panel.add(ratingBox, GBC.eol());
            panel.add(offTheLine, GBC.std());
            panel.add(jumping, GBC.eol());
            panel.add(unnecessaryKinks, GBC.std());
            panel.add(badJunctionShapes, GBC.eol());
            panel.add(new JLabel(tr("Ratings and negative feature tags are saved in the debug export.")), GBC.eol());
        }
        AlignmentMode effectiveMode = AlignmentService.effectiveAlignmentMode(result.selection(), config);
        String modeLabel = effectiveMode == config.alignmentMode()
            ? config.alignmentMode().displayName()
            : tr("{0} (automatic for rough sketch)", effectiveMode.displayName());
        panel.add(new JLabel(tr("Mode: {0}", modeLabel)), GBC.eol());
        panel.add(new JLabel(tr("Junction/end nodes: {0}", config.adjustJunctionNodes() ? "adjustable" : "fixed")), GBC.eol());
        panel.add(new JLabel(cleanupSummary(result, cleanupConfig)), GBC.eol());
        panel.add(new JLabel(tr("Legacy post-slide simplification: {0}",
            cleanupConfig.isDisabled()
                ? (config.simplifyEnabled() ? "enabled" : "disabled")
                : "inactive while geometry cleanup is enabled")), GBC.eol());
        panel.add(new JLabel(tr("Sampling: {0}", result.diagnostics().samplingSummary())), GBC.eol());
        panel.add(new JLabel(tr("Diagnostics file can be exported from More tools.")), GBC.eol());
        if (PluginPreferences.isDebugEnabled()) {
            panel.add(new JLabel(tr("Debug overlay is enabled.")), GBC.eol());
        }
        panel.add(new JLabel(tr("Preview legend: solid blue = selected result; orange dashed = original; dashed labeled lines = other detected ridges.")), GBC.eol());
        return panel;
    }

    /** Returns slide-time cleanup configuration and generated-sibling count for the preview. */
    private String cleanupSummary(AlignmentResult result, GeometryCleanupConfig cleanupConfig) {
        if (cleanupConfig.isDisabled()) {
            return tr("Geometry cleanup: off");
        }
        long alternatives = result.candidates().stream()
            .filter(candidate -> candidate.geometryCleanup().cleanedCandidate())
            .count();
        return tr("Geometry cleanup: {0}, {1} ({2} m); cleaned alternatives: {3}",
            cleanupConfig.mode(), cleanupConfig.preset(), cleanupConfig.rippleScaleMeters(), alternatives);
    }

    /**
     * Builds the cleanup status line for the candidate currently shown in the modeless preview.
     *
     * <p>The wording intentionally reports only candidate-owned cleanup facts. It does not infer
     * a successful cleanup from the current settings, nor does it imply that an inspection-only
     * candidate can be applied.</p>
     *
     * @param candidate selected preview candidate
     * @return user-readable cleanup outcome, reason, and point/operation counts
     */
    static String cleanupDetail(CenterlineCandidate candidate) {
        var cleanup = candidate.geometryCleanup();
        return tr("Selected cleanup: {0}; reason: {1}; points: before {2}, smoothed {3}, after {4}; "
                + "smoothing: accepted {5}, backtracks {6}; reduction: accepted {7}/{8}; containment failures: {9}",
            cleanupOutcomeLabel(cleanup.outcome()), cleanup.reasonCode(), cleanup.beforePointCount(),
            cleanup.smoothedPointCount(),
            cleanup.afterPointCount(), cleanup.acceptedSmoothingPasses(), cleanup.smoothingBacktrackCount(),
            cleanup.acceptedChordCount(), cleanup.attemptedChordCount(), cleanup.containmentFailureCount());
    }

    private static String cleanupOutcomeLabel(
        CandidateGeometryCleanup.Outcome outcome
    ) {
        return switch (outcome) {
            case NOT_REQUESTED -> tr("not requested");
            case SKIPPED -> tr("skipped");
            case UNCHANGED -> tr("unchanged");
            case CLEANED_ALTERNATIVE_AVAILABLE -> tr("cleaned alternative available");
            case CLEANED -> tr("fully applied");
            case PARTIALLY_CLEANED -> tr("partially applied");
            case REJECTED -> tr("rejected");
        };
    }

    private record PreviewSelection(
        CenterlineCandidate initialCandidate,
        CenterlineCandidate candidate,
        AlignmentResult result
    ) {
    }

    private void showError(String message) {
        JTextArea text = new JTextArea(previewFailureText(message), 7, 76);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(text);
        scroll.setPreferredSize(new Dimension(640, 180));
        JOptionPane.showMessageDialog(
            MainApplication.getMainFrame(),
            scroll,
            tr("WayHeatmapTracer"),
            JOptionPane.ERROR_MESSAGE
        );
    }

    static String previewFailureText(String message) {
        String safe = message == null || message.isBlank() ? tr("Unknown error") : message;
        if (safe.length() <= MAXIMUM_PREVIEW_FAILURE_CHARACTERS) {
            return safe;
        }
        return safe.substring(0, MAXIMUM_PREVIEW_FAILURE_CHARACTERS) + PREVIEW_FAILURE_LOG_SUFFIX;
    }

    private void requireDownloadedAreaCoverage(SelectionContext selection, DataSet dataSet) {
        List<Bounds> bounds = dataSet.getDataSourceBounds();
        if (bounds == null || bounds.isEmpty()) {
            throw new IllegalStateException("This data layer has no downloaded area metadata. Download the area in JOSM before aligning ways.");
        }
        for (org.openstreetmap.josm.data.osm.Node node : selection.segmentNodes()) {
            LatLon point = node.getCoor();
            if (point == null || !isWithinDownloadedBounds(point, bounds)) {
                throw new IllegalStateException("Selected segment extends outside the downloaded area. Download a larger area first.");
            }
        }
    }

    private void requirePreviewWithinDownloadedArea(List<EastNorth> preview, DataSet dataSet) {
        List<Bounds> bounds = dataSet.getDataSourceBounds();
        if (bounds == null || bounds.isEmpty()) {
            return;
        }
        for (EastNorth point : preview) {
            LatLon latLon = org.openstreetmap.josm.data.projection.ProjectionRegistry.getProjection().eastNorth2latlon(point);
            if (!isWithinDownloadedBounds(latLon, bounds)) {
                throw new IllegalStateException("Aligned geometry would extend outside the downloaded area. Download a larger area first.");
            }
        }
    }

    private boolean isWithinDownloadedBounds(LatLon point, List<Bounds> bounds) {
        for (Bounds bound : bounds) {
            if (bound.contains(point)) {
                return true;
            }
        }
        return false;
    }
}
