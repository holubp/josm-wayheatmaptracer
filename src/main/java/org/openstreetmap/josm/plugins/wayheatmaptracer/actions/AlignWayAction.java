package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.FrozenReplayCodec;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
import java.util.function.Function;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementAttemptDiagnostics.FailureFacts;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.LegacyReviewOwner;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.LegacyRefinementWorkflow;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ModernReviewRoot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementPayload;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.Candidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.ui.RefinementReviewLifecycle;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.InvocationSourceWatch;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.LegacyVisibleSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.ui.RefinementControls;

import javax.swing.JButton;
import javax.swing.JComponent;
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
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.help.HelpUtil;
import org.openstreetmap.josm.gui.layer.ImageryLayer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.config.PluginPreferences;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.DiagnosticsRegistry;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.LastSlideDebugBundle;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15Bundle;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15Artifact;
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
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ModernAlignmentInvocation;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
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
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LegacyFinalPreviewSafety;
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
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.CredentialSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileRuntime;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration;
import org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.ManagedHeatmapLayer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.VisibleSourceEpoch;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchCoordinator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FindingSummary;
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

    /** Recovery supplied only by the ordinary modern invocation, never fabricated for Legacy. */
    static RecoveryPermissions ordinaryRecoveryPermissions(OrdinaryRoute route) {
        return route==null||route.pipeline()==OrdinaryPipeline.LEGACY_COMPATIBILITY?null:route.invocation().recovery().toPermissions();
    }

    /** Complete source-routing decision used directly by the ordinary action. */
    record OrdinaryActionRouting<T>(OrdinaryRoute route, T visibleSource,
            AlignmentConfig requestedConfig) {
        OrdinaryActionRouting {
            Objects.requireNonNull(route, "route");
            Objects.requireNonNull(requestedConfig, "requestedConfig");
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
        default Optional<AlignmentService.VisibleInputReceipt> inputReceipt(LiveBPreviewService.VisibleRaster raster){return Optional.empty();}
    }

    /** Acquires the external managed pixels for one EDT-captured ordinary seed. */
    @FunctionalInterface
    interface OrdinaryManagedRasterAcquire {
        ManagedModernPreviewSource.Raster acquire(LiveBPreviewService.ManagedCaptureSeed seed,
                ModernAlignmentInvocation invocation, AlignmentJob.JobContext context) throws Exception;
    }

    /** Acquires every frozen managed source choice through one coordinator owner. */
    @FunctionalInterface
    interface OrdinaryManagedSourcesAcquire {
        ManagedModernPreviewSource.SourceRasters acquire(LiveBPreviewService.ManagedCaptureSeed seed,
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
            return startInternal(session, owner, routing, dataSet, selection, sourceIdentity,
                    visibleCapture, managedAcquire, null, null, publisher, null, ignored -> { });
        }

        <S> AlignmentJob.StartResult<LiveBPreviewService.Computed> startWithSources(
                PreviewSessionController<LiveBPreviewService.Computed> session,
                PreviewSessionController.Owner owner, OrdinaryActionRouting<S> routing,
                DataSet dataSet, SelectionContext selection, String sourceIdentity,
                OrdinaryVisibleRasterCapture<S> visibleCapture,
                OrdinaryManagedSourcesAcquire managedAcquire, TileFetchCoordinator sourceOwner,
                AlignmentJob.PreviewPublisher<LiveBPreviewService.Computed> publisher) {
            return startInternal(session, owner, routing, dataSet, selection, sourceIdentity,
                    visibleCapture, null, managedAcquire, sourceOwner, publisher, null, ignored -> { });
        }

        <S> AlignmentJob.StartResult<LiveBPreviewService.Computed> startWithSources(
                PreviewSessionController<LiveBPreviewService.Computed> session,
                PreviewSessionController.Owner owner, OrdinaryActionRouting<S> routing,
                DataSet dataSet, SelectionContext selection, String sourceIdentity,
                OrdinaryVisibleRasterCapture<S> visibleCapture,
                OrdinaryManagedSourcesAcquire managedAcquire, TileFetchCoordinator sourceOwner,
                AlignmentJob.PreviewPublisher<LiveBPreviewService.Computed> publisher,
                String diagnosticAttemptIdentity, Consumer<AlignmentJob.CancellationDiagnosticEvent> diagnostics) {
            return startInternal(session, owner, routing, dataSet, selection, sourceIdentity,
                    visibleCapture, null, managedAcquire, sourceOwner, publisher,
                    diagnosticAttemptIdentity, diagnostics);
        }

        private <S> AlignmentJob.StartResult<LiveBPreviewService.Computed> startInternal(
                PreviewSessionController<LiveBPreviewService.Computed> session,
                PreviewSessionController.Owner owner, OrdinaryActionRouting<S> routing,
                DataSet dataSet, SelectionContext selection, String sourceIdentity,
                OrdinaryVisibleRasterCapture<S> visibleCapture,
                OrdinaryManagedRasterAcquire rasterAcquire,
                OrdinaryManagedSourcesAcquire sourcesAcquire, TileFetchCoordinator sourceOwner,
                AlignmentJob.PreviewPublisher<LiveBPreviewService.Computed> publisher,
                String diagnosticAttemptIdentity, Consumer<AlignmentJob.CancellationDiagnosticEvent> diagnostics) {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(routing, "routing");
            Objects.requireNonNull(dataSet, "dataSet");
            Objects.requireNonNull(selection, "selection");
            Objects.requireNonNull(sourceIdentity, "sourceIdentity");
            ModernAlignmentInvocation invocation = Objects.requireNonNull(
                    routing.route().invocation(), "Ordinary modern invocation");
            RecoveryPermissions permissions = invocation.recovery().toPermissions();
            AlignmentJob.DetachedCapture<OrdinaryCapturedSource> capture = () -> switch (routing.route().pipeline()) {
                case MODERN_VISIBLE -> {
                    LiveBPreviewService.VisibleRaster raster = Objects.requireNonNull(
                            visibleCapture.capture(routing.visibleSource(), invocation, permissions),
                            "visible ordinary raster");
                    var receipt=visibleCapture.inputReceipt(raster);
                    LiveBPreviewService.Captured captured = receipt.isPresent()?previewService.capture(dataSet,
                            selection,raster,invocation.config(),true,permissions,receipt.orElseThrow()):previewService.capture(dataSet,
                            selection, raster, invocation.config(), true, permissions);
                    yield new AlignmentJob.CapturedAttempt<>(snapshot(captured, sourceIdentity),
                            new OrdinaryCapturedSource(invocation, captured, null,
                                    legacyBInvocation(selection, invocation.config())));
                }
                case MODERN_MANAGED -> {
                    LiveBPreviewService.ManagedCaptureSeed seed = previewService.captureManagedSeed(
                            dataSet, selection, invocation.config(), sourceIdentity, permissions);
                    yield new AlignmentJob.CapturedAttempt<>(snapshot(seed, sourceIdentity),
                            new OrdinaryCapturedSource(invocation, null, seed,
                                    legacyBInvocation(selection, invocation.config())));
                }
                case LEGACY_COMPATIBILITY -> throw new IllegalArgumentException(
                        "Legacy compatibility alignment cannot enter the modern attempt assembly");
            };
            AlignmentJob.DetachedWorker<OrdinaryCapturedSource, LiveBPreviewService.Computed> worker = (captured, context) -> {
                if (captured.visible() != null) {
                    return previewService.compute(captured.visible(), context, captured.legacyB())
                            .withSettingsResolution(settingsResolutionJson(
                                    routing.requestedConfig(), captured.invocation().config()));
                }
                context.checkpoint();
                LiveBPreviewService.Captured managed = sourcesAcquire == null
                        ? previewService.attachManagedRaster(captured.managed(),
                                rasterAcquire.acquire(captured.managed(), captured.invocation(), context))
                        : previewService.attachManagedSources(captured.managed(),
                                sourcesAcquire.acquire(captured.managed(), captured.invocation(), context),
                                sourceOwner);
                return previewService.compute(managed, context, captured.legacyB())
                        .withSettingsResolution(settingsResolutionJson(
                                routing.requestedConfig(), captured.invocation().config()));
            };
            return diagnosticAttemptIdentity == null
                    ? session.startDetached(owner, capture, worker, publisher)
                    : session.startDetached(owner, capture, worker, publisher, diagnosticAttemptIdentity, diagnostics);
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

        private static LiveBPreviewService.LegacyBInvocation legacyBInvocation(
                SelectionContext selection, AlignmentConfig config) {
            return config.heatmap().trackerMode() == TrackerMode.LEGACY_THEN_B
                    ? LiveBPreviewService.LegacyBInvocation.capture(selection, config) : null;
        }

        private record OrdinaryCapturedSource(ModernAlignmentInvocation invocation,
                LiveBPreviewService.Captured visible,
                LiveBPreviewService.ManagedCaptureSeed managed,
                LiveBPreviewService.LegacyBInvocation legacyB) {
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
        private IntervalTraceBatch batch;
        private final List<LiveBPreviewService.Computed> sourceRuns;
        private int sourceIndex;
        private final FixedIntervalEditPlanComposer composer = new FixedIntervalEditPlanComposer();
        private Map<Integer, Integer> routeChoices = Map.of();
        private FixedIntervalEditPlanComposer.Assessment assessment;
        private PreviewReviewState review;

        /** Composes the initial ranked routes from the immutable production batch. */
        public IntervalPreviewState(IntervalTraceBatch batch) {
            this.batch = Objects.requireNonNull(batch, "batch");
            this.sourceRuns = List.of();
            recompose();
        }

        /** Retains every produced interval source choice and its own immutable batch. */
        public IntervalPreviewState(LiveBPreviewService.Computed computed) {
            if (computed == null || !computed.partitioned()) {
                throw new IllegalArgumentException("Partitioned source choices are required");
            }
            this.sourceRuns = computed.productionRuns().stream()
                    .filter(LiveBPreviewService.Computed::partitioned).toList();
            this.batch = sourceRuns.get(0).intervalBatch();
            recompose();
        }
        private IntervalPreviewState(List<LiveBPreviewService.Computed> sources,LiveBPreviewService.Computed selected,RefinementPayload.RootReviewState stored) {
            sourceRuns=List.copyOf(sources);sourceIndex=-1;
            for(int i=0;i<sourceRuns.size();i++)if(sourceRuns.get(i)==selected)sourceIndex=i;
            if(sourceIndex<0||!selected.partitioned()||stored.intervalAssessment().isEmpty())throw new IllegalArgumentException("Stored native interval context unavailable");
            batch=selected.intervalBatch();routeChoices=stored.routeChoices();assessment=stored.intervalAssessment().orElseThrow();review=unconfirmedReview(assessment);
            if(assessment.plan().isPresent()&&assessment.plan().orElseThrow().before()!=batch.network())throw new IllegalArgumentException("Stored native interval context has foreign authority");
        }

        /** Returns the sole frozen inference batch for this preview session. */
        public IntervalTraceBatch batch() { return batch; }
        /** Immutable produced source owners in native-first order. */
        public List<LiveBPreviewService.Computed> sourceRuns() { return sourceRuns; }
        /** Selected source owner, or null for the legacy single-batch constructor. */
        public LiveBPreviewService.Computed selectedRun() {
            return sourceRuns.isEmpty() ? null : sourceRuns.get(sourceIndex);
        }
        /** Switches complete source evidence and interval runs, clearing route review. */
        public void chooseSource(int index) {
            if (index < 0 || index >= sourceRuns.size()) {
                throw new IllegalArgumentException("Source choice index is unavailable");
            }
            sourceIndex = index;
            batch = sourceRuns.get(index).intervalBatch();
            routeChoices = Map.of();
            recompose();
        }
        /** Returns selected production route indexes keyed by ordered interval index. */
        public Map<Integer, Integer> routeChoices() { return routeChoices; }
        /** Returns the current complete selected and affected-way assessment. */
        public FixedIntervalEditPlanComposer.Assessment assessment() { return assessment; }
        /** Restores one already validated cached history composition without inference or recomposition. */
        public void restoreSelection(ModernReviewRoot.IntervalSelection stored) {
            int found=-1;for(int i=0;i<sourceRuns.size();i++)if(sourceRuns.get(i)==stored.owner()){found=i;break;}
            if(found<0)throw new IllegalArgumentException("Stored interval source owner unavailable");
            sourceIndex=found;batch=stored.owner().intervalBatch();routeChoices=stored.routeChoices();assessment=stored.assessment();review=unconfirmedReview(assessment);
        }
        public int sourceIndex(){return sourceIndex;}
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
            return assessment.noChangeProof().isPresent()||assessment.applyAvailable() && review != null && review.canApply();
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
            return org.openstreetmap.josm.plugins.wayheatmaptracer.service.ModernPlanReview
                    .intervalCandidateIdentity(batch, routeChoices);
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

    /** @deprecated Standalone preview is unavailable; start ordinary Legacy alignment instead. */
    @Deprecated
    public static AlignWayAction experimentalCorridorAwareVisiblePreview() {
        throw obsoleteStandalonePreview();
    }

    /** @deprecated Standalone preview is unavailable; start ordinary Legacy alignment instead. */
    @Deprecated
    public static AlignWayAction experimentalCorridorAwareVisiblePreview(
            PreviewSessionController<LiveBPreviewService.Computed> session) {
        throw obsoleteStandalonePreview();
    }

    /** @deprecated Standalone preview is unavailable; start ordinary Legacy alignment instead. */
    @Deprecated
    public static AlignWayAction experimentalProbabilisticVisiblePreview() {
        throw obsoleteStandalonePreview();
    }

    /** @deprecated Standalone preview is unavailable; start ordinary Legacy alignment instead. */
    @Deprecated
    public static AlignWayAction experimentalProbabilisticVisiblePreview(
            PreviewSessionController<LiveBPreviewService.Computed> session) {
        throw obsoleteStandalonePreview();
    }

    /** @deprecated Standalone preview is unavailable; start ordinary Legacy alignment instead. */
    @Deprecated
    public static AlignWayAction experimentalDirectionalImageVisiblePreview() {
        throw obsoleteStandalonePreview();
    }

    /** @deprecated Standalone preview is unavailable; start ordinary Legacy alignment instead. */
    @Deprecated
    public static AlignWayAction experimentalDirectionalImageVisiblePreview(
            PreviewSessionController<LiveBPreviewService.Computed> session) {
        throw obsoleteStandalonePreview();
    }

    /** @deprecated Standalone preview is unavailable; start ordinary Legacy alignment instead. */
    @Deprecated
    public static AlignWayAction experimentalHybridVisiblePreview() {
        throw obsoleteStandalonePreview();
    }

    /** @deprecated Standalone preview is unavailable; start ordinary Legacy alignment instead. */
    @Deprecated
    public static AlignWayAction experimentalHybridVisiblePreview(
            PreviewSessionController<LiveBPreviewService.Computed> session) {
        throw obsoleteStandalonePreview();
    }

    /** @deprecated Standalone preview is unavailable; start ordinary Legacy alignment instead. */
    @Deprecated
    public static AlignWayAction experimentalCorridorAwareManagedPreview() {
        throw obsoleteStandalonePreview();
    }

    /** @deprecated Standalone preview is unavailable; start ordinary Legacy alignment instead. */
    @Deprecated
    public static AlignWayAction experimentalCorridorAwareManagedPreview(
            PreviewSessionController<LiveBPreviewService.Computed> session) {
        throw obsoleteStandalonePreview();
    }

    /** @deprecated Standalone preview is unavailable; start ordinary Legacy alignment instead. */
    @Deprecated
    public static AlignWayAction experimentalProbabilisticManagedPreview() {
        throw obsoleteStandalonePreview();
    }

    /** @deprecated Standalone preview is unavailable; start ordinary Legacy alignment instead. */
    @Deprecated
    public static AlignWayAction experimentalProbabilisticManagedPreview(
            PreviewSessionController<LiveBPreviewService.Computed> session) {
        throw obsoleteStandalonePreview();
    }

    private static UnsupportedOperationException obsoleteStandalonePreview() {
        return new UnsupportedOperationException("Standalone preview is unavailable; start ordinary Legacy alignment.");
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
        OrdinaryActionBenchmarkObserver.actionStarted();
        ManagedHeatmapConfig config = null;
        String diagnosticAttemptIdentity = beginDiagnosticAttempt(livePreviewSession);
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
            AlignmentConfig requestedSlideConfig = new AlignmentConfig(config, cleanupConfig);
            AlignmentConfig slideConfig = requestedSlideConfig.forEffectiveModernAttempt();
            String settingsNotice = modernSettingsNotice(requestedSlideConfig, slideConfig);
            if (!settingsNotice.isEmpty()) {
                PluginLog.verbose("Modern attempt settings: %s", settingsNotice);
            }
            OrdinaryActionRouting<ImageryLayer> ordinaryRouting = forcedLivePreviewEngine == null
                    ? resolveOrdinaryAction(tracing, requestedSlideConfig, HeatmapLayerResolver::resolve,
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
            RecoveryPermissions recovery = ordinaryRecoveryPermissions(ordinaryRoute);
            final ManagedHeatmapConfig invocationConfig=config;
            final String invocationLineage=diagnosticSourceLineage;
            java.util.function.Consumer<LegacyReviewOwner> execute=originalReview->runPreparedInvocation(dataSet,selection,imageryLayer,mapView,
                slideConfig,requestedSlideConfig,persistedSlideConfig,tracing,recovery,ordinaryRouting,sourceMode,modern,
                invocationConfig,diagnosticAttemptIdentity,originalReview);
            Object intentKey=new Object();
            if(!livePreviewSession.hasUnsettledWork()) {
                if(!requestPreparedInvocation(intentKey,()->execute.accept(null),()->{}))
                    throw new IllegalStateException("Alignment work began while preparing an idle invocation");
                execute.accept(null);
            } else {
                InvocationSourceWatch watch=InvocationSourceWatch.capture(dataSet,selection,config.alignmentMode());
                JDialog[] waiting={null};
                Runnable closeWaiting=()->{
                    if(waiting[0]!=null){waiting[0].dispose();if(activePreviewDialog==waiting[0])activePreviewDialog=null;}
                };
                Runnable abandon=()->{watch.close();closeWaiting.run();};
                Runnable resume=()->{
                    closeWaiting.run();LegacyReviewOwner originalReview=null;
                    try {
                        DataSet active=MainApplication.getLayerManager().getEditDataSet();
                        if(active!=dataSet||MainApplication.getMap()==null||MainApplication.getMap().mapView!=mapView)
                            throw new IllegalStateException("The requested edit layer or map changed while waiting");
                        if(imageryLayer!=null&&!MainApplication.getLayerManager().getLayers().contains(imageryLayer))
                            throw new IllegalStateException("The requested source layer changed while waiting");
                        SelectionContext current=SelectionResolver.resolve(active,legacySelection&&invocationConfig.adjustJunctionNodes());
                        watch.requireCurrent(active,current,effectiveConfig(PluginPreferences.load()).alignmentMode());
                        if(!modern){
                            originalReview=LegacyReviewOwner.begin(dataSet,selection,slideConfig);
                            watch.requireCurrent(active,current,invocationConfig.alignmentMode());
                        }
                        execute.accept(originalReview);
                        if(modern)watch.requireCurrent(active,current,invocationConfig.alignmentMode());
                    }catch(Exception failed){
                        if(originalReview!=null)originalReview.close();
                        livePreviewSession.close(activeLivePreviewOwner);
                        handleInvocationFailure(failed,invocationConfig,invocationLineage,diagnosticAttemptIdentity);
                    }finally{watch.close();}
                };
                if(requestPreparedInvocation(intentKey,resume,abandon))resume.run();
                else {
                    waiting[0]=new JDialog(MainApplication.getMainFrame(),tr("Waiting for alignment work"),false);
                    JButton cancelPending=new JButton(tr("Cancel"));
                    JPanel pendingPanel=new JPanel(new BorderLayout(4,4));
                    pendingPanel.add(new JLabel(tr("Waiting for previous alignment work to stop...")),BorderLayout.CENTER);
                    pendingPanel.add(cancelPending,BorderLayout.SOUTH);waiting[0].setContentPane(pendingPanel);
                    waiting[0].setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
                    cancelPending.addActionListener(event->livePreviewSession.workGate().cancelPending(intentKey));
                    waiting[0].addWindowListener(new WindowAdapter(){@Override public void windowClosing(WindowEvent event){livePreviewSession.workGate().cancelPending(intentKey);}});
                    waiting[0].pack();waiting[0].setLocationRelativeTo(MainApplication.getMainFrame());
                    activePreviewDialog=waiting[0];waiting[0].setVisible(true);
                }
            }
        } catch (Exception ex) {
            handleInvocationFailure(ex,config,diagnosticSourceLineage,diagnosticAttemptIdentity);
        }
    }

    boolean requestPreparedInvocation(Object intentKey,Runnable resume,Runnable abandon) {
        return livePreviewSession.requestInvocation(intentKey,resume,abandon);
    }

    private void runPreparedInvocation(DataSet dataSet,SelectionContext selection,ImageryLayer imageryLayer,MapView mapView,
            AlignmentConfig slideConfig,AlignmentConfig requestedSlideConfig,AlignmentConfig persistedSlideConfig,TracingSettings tracing,
            RecoveryPermissions recovery,OrdinaryActionRouting<ImageryLayer> ordinaryRouting,AlignmentSourceMode sourceMode,boolean modern,
            ManagedHeatmapConfig config,String diagnosticAttemptIdentity,LegacyReviewOwner originalReview) {
            if (modern) {
                startLiveBPreview(dataSet, selection, imageryLayer, mapView, slideConfig,
                        requestedSlideConfig,
                        persistedSlideConfig, tracing, recovery, ordinaryRouting,
                        sourceMode == AlignmentSourceMode.VISIBLE_LAYER,
                        sourceMode == AlignmentSourceMode.MANAGED_TILES,
                        diagnosticAttemptIdentity);
                return;
            }
            PreviewSessionController.Owner rootOwner=livePreviewSession.open(()->{});
            activeLivePreviewOwner=rootOwner;
            LegacyReviewOwner legacyReview = originalReview==null?LegacyReviewOwner.begin(dataSet,selection,slideConfig):originalReview;
            try {
                completeLegacyReview(legacyReview,
                    capture -> alignmentService.alignLegacyReviewed(selection, imageryLayer, mapView, slideConfig, capture));
            } catch(RuntimeException|Error failed){livePreviewSession.close(rootOwner);throw failed;}
            try {
                AlignmentResult result = legacyReview.originalResult();
                updateAggregateIntensityLayer(result, config);
                DiagnosticsRegistry.setLastBundle(LastSlideDebugBundle.fromResult(
                    result, initialCandidate(result), initialCandidate(result), "preview-open", PluginLog.currentSlideLog(), Map.of()));
                showCandidatePreview(dataSet, selection, result, slideConfig, imageryLayer, mapView,
                    new LinkedHashMap<>(), legacyReview,rootOwner);
            } catch (RuntimeException | Error failure) {
                legacyReview.close();livePreviewSession.close(rootOwner);
                throw failure;
            }
    }

    private void handleInvocationFailure(Exception ex,ManagedHeatmapConfig config,String diagnosticSourceLineage,String diagnosticAttemptIdentity) {
        if(ex instanceof AlignmentService.AlignmentFailureException failure) {
            overlay.hide();
            if (config != null) {
                updateAggregateIntensityLayer(failure.partialResult(), config);
            }
            Logging.warn("WayHeatmapTracer alignment failed without applying geometry: " + failure.getMessage());
            PluginLog.verbose("Alignment failed without applying geometry: %s", failure.toString());
            if (config != null && config.trackerMode().capabilities().requiresEvidenceSnapshot()) {
                recordModernUnavailable("failed", diagnosticSourceLineage,
                        diagnosticAttemptIdentity);
            } else {
                DiagnosticsRegistry.setLastBundle(LastSlideDebugBundle.fromResult(
                    failure.partialResult(),
                    failure.partialResult().candidates().isEmpty() ? null : failure.partialResult().candidates().get(0),
                    "failed",
                    PluginLog.currentSlideLog()
                ));
            }
            PluginLog.endSlideSession();
            showError(tr("WayHeatmapTracer failed: {0}", failure.getMessage()));
        } else {
            recordCaptureFailure(ex, diagnosticSourceLineage, diagnosticAttemptIdentity);
            overlay.hide();
            Logging.error(ex);
            PluginLog.verbose("Alignment failed with exception: %s", ex.toString());
            PluginLog.endSlideSession();
            showWorkerFailure(captureFailureMessage(ex));
        }
    }

    private void startLiveBPreview(DataSet dataSet, SelectionContext selection,
            ImageryLayer imageryLayer, MapView mapView, AlignmentConfig slideConfig,
            AlignmentConfig requestedSlideConfig,
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
        String settingsNotice = modernSettingsNotice(persistedSlideConfig, slideConfig);
        if (!settingsNotice.isEmpty()) {
            panel.add(settingsNoticeComponent(settingsNotice));
        }
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
                progress.dispose();
                publishFailedWorkerIfCurrent(livePreviewSession, previewOwner, current,
                        sourceLineage, diagnosticAttemptIdentity, message -> {
                    overlay.hide();
                    PluginLog.endSlideSession();
                    showWorkerFailure(message);
                });
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
                    OrdinaryActionBenchmarkObserver.actionCancelled(livePreviewSession.currentAttempt());
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
                ordinaryAttemptAssembly.startWithSources(livePreviewSession, previewOwner, ordinaryRouting,
                        dataSet, selection, sourceIdentity,
                        visibleRasterCapture(selection,mapView,sourceIdentity),
                        (seed, invocation, context) -> {
                            ManagedModernPreviewSource source = new ManagedModernPreviewSource(coordinator);
                            return source.acquireSources(seed.sourceGeographic(),
                                    invocation.config().heatmap(), sourceIdentity, credentials, context);
                        }, coordinator, attempt -> publishLiveBPreview(previewOwner, progress, dataSet, selection,
                                imageryLayer, mapView, slideConfig, persistedSlideConfig,
                                tracingAtCapture, attempt.result(), diagnosticAttemptIdentity,
                                previewSourceOwner), diagnosticAttemptIdentity,
                        event -> publishFinalizationCancellationIfLatest(livePreviewSession, previewOwner,
                                event, sourceLineage, diagnosticAttemptIdentity));
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
                    ManagedModernPreviewSource.SourceRasters rasters = source.acquireSources(
                            seed[0].sourceGeographic(), slideConfig.heatmap(), sourceIdentity,
                            credentials, context);
                    return livePreviewService.compute(livePreviewService.attachManagedSources(
                            seed[0], rasters, coordinator), context)
                            .withSettingsResolution(settingsResolutionJson(requestedSlideConfig,
                                    slideConfig));
                }, attempt -> publishLiveBPreview(previewOwner, progress, dataSet, selection, imageryLayer, mapView,
                        slideConfig, persistedSlideConfig, tracingAtCapture, attempt.result(),
                        diagnosticAttemptIdentity, previewSourceOwner));
            } else {
                livePreviewSession.startDetached(previewOwner, () -> {
                    var authoritative=imageryLayer instanceof ManagedHeatmapLayer managed?alignmentService.captureAuthoritativeVisibleInput(selection,managed,mapView,slideConfig,sourceIdentity,recoveryPermissions):null;
                    LiveBPreviewService.VisibleRaster raster = authoritative==null?alignmentService.captureLiveBVisibleRaster(
                            selection, imageryLayer, mapView, slideConfig, sourceIdentity,recoveryPermissions):authoritative.raster();
                    LiveBPreviewService.Captured captured = authoritative!=null?livePreviewService.capture(dataSet,selection,raster,slideConfig,explicitVisibleSource,recoveryPermissions,authoritative.receipt()):recoveryPermissions == null
                            ? livePreviewService.capture(dataSet, selection, raster, slideConfig,
                                    explicitVisibleSource)
                            : livePreviewService.capture(dataSet, selection, raster, slideConfig,
                                    explicitVisibleSource, recoveryPermissions);
                    AlignmentJob.AttemptSnapshot snapshot = new AlignmentJob.AttemptSnapshot(
                            captured.network().snapshotId(), sourceIdentity, captured.settingsHash(),
                            captured.network().canonicalHash());
                    return new AlignmentJob.CapturedAttempt<>(snapshot, captured);
                }, (captured, context) -> livePreviewService.compute(captured, context)
                        .withSettingsResolution(settingsResolutionJson(requestedSlideConfig,
                                slideConfig)),
                        attempt -> publishLiveBPreview(previewOwner, progress, dataSet, selection, imageryLayer, mapView,
                                slideConfig, persistedSlideConfig, tracingAtCapture, attempt.result(),
                                diagnosticAttemptIdentity, null), diagnosticAttemptIdentity,
                        event -> publishFinalizationCancellationIfLatest(livePreviewSession, previewOwner,
                                event, sourceLineage, diagnosticAttemptIdentity));
            }
        } catch (RuntimeException exception) {
            boolean closed = closeAndPublishIfCurrent(livePreviewSession, previewOwner,
                    () -> recordCaptureFailure(exception, sourceLineage, diagnosticAttemptIdentity));
            progress.dispose();
            if (closed) {
                overlay.hide();
                PluginLog.endSlideSession();
                showWorkerFailure(captureFailureMessage(exception));
            }
            return;
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
                warnOptionalAggregateSource(computed);
                showIntervalPreviewDialog(previewOwner, dataSet, selection, imageryLayer, mapView,
                        slideConfig, persistedSlideConfig, tracingAtCapture, computed,
                        diagnosticAttemptIdentity, previewSourceOwner);
            } catch (RuntimeException exception) {
                boolean closed = closeAndPublishIfCurrent(livePreviewSession, previewOwner, () -> {
                    try {
                        recordRejectedModernPreview(computed, "failed", diagnosticAttemptIdentity, null);
                    } catch (RuntimeException compositionFailure) {
                        recordModernUnavailable("failed", computed.captured().managedRaster() == null
                                ? "visible-layer" : "managed-tiles", diagnosticAttemptIdentity);
                    }
                });
                progress.dispose();
                if (closed) {
                    overlay.hide();
                    PluginLog.endSlideSession();
                    showWorkerFailure(tr("Interval alignment preview was rejected: {0}. "
                            + "No changes were applied. Use Export Last Alignment Diagnostics for details.",
                            exception.getMessage()));
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
            List<LiveBPreviewService.PreviewChoice> previewChoices = livePreviewService.adaptChoices(computed,
                    point -> ProjectionRegistry.getProjection().latlon2eastNorth(
                            new LatLon(point.latitudeDegrees(), point.longitudeDegrees())));
            progress.dispose();
            warnOptionalAggregateSource(computed);
            if (previewChoices.isEmpty()) {
                terminalStatus = modernPreviewStatus(computed.pipeline().inference().status(),
                        initialDisposition);
                ManualJunctionEligibility.Decision manual = noRouteJunctionDecision(
                        computed.captured());
                terminalManualReason = manual == null ? null : manual.reason();
                throw new IllegalStateException(noPreviewableRouteMessage(computed));
            }
            showLiveBReadOnlyDialog(previewOwner, dataSet, selection, imageryLayer, mapView,
                    slideConfig, persistedSlideConfig, tracingAtCapture, computed, previewChoices,
                    diagnosticAttemptIdentity, previewSourceOwner);
        } catch (RuntimeException exception) {
            String rejectedStatus = terminalStatus;
            ManualJunctionEligibility.Reason rejectedManualReason = terminalManualReason;
            boolean closed = closeAndPublishIfCurrent(livePreviewSession, previewOwner,
                    () -> recordRejectedModernPreview(computed, rejectedStatus,
                            diagnosticAttemptIdentity, rejectedManualReason));
            progress.dispose();
            if (closed) {
                overlay.hide();
                PluginLog.endSlideSession();
                showWorkerFailure(tr("{0} alignment preview was rejected: {1}. "
                                + "No changes were applied. Use Export Last Alignment Diagnostics for details.",
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
        IntervalPreviewState state = new IntervalPreviewState(computed);
        ModernReviewRoot[] refinementRoot={null};LegacyRefinementWorkflow[] refinement={null};
        boolean[] rebuildingChoices={false};
        JDialog dialog = new JDialog(MainApplication.getMainFrame(),
                tr("Interval Alignment Preview"), false);
        JPanel panel = new JPanel(new BorderLayout(4,4));
        JPanel body=new JPanel(new BorderLayout(4,4));
        JPanel footer=new JPanel(new BorderLayout(4,4));JPanel buttons=new JPanel(new java.awt.GridLayout(0, 2, 4, 4));
        JPanel intervalControls = new JPanel(new java.awt.GridLayout(0, 2));
        List<JComboBox<String>> choices = new java.util.ArrayList<>();
        JComboBox<String> sourceChoices = new JComboBox<>(state.sourceRuns().stream()
                .map(run -> run.options().sourceTier().equals("selected-visible")
                        ? tr("Selected palette")
                        : run.options().sourceTier().equals("all-colors-combined")
                                ? tr("All-color aggregate")
                                : tr("Selected raster: {0}", run.options().sourceTier()
                                        .substring("selected-mapping-".length())))
                .toArray(String[]::new));
        sourceChoices.setName("refinement-root-source");
        for (int index = 0; index < state.batch().runs().size(); index++) {
            IntervalTraceBatch.IntervalRun run = state.batch().runs().get(index);
            intervalControls.add(new JLabel(tr("Interval {0} ({1}–{2})", index + 1,
                    run.interval().range().firstIndex(), run.interval().range().lastIndex())));
            JComboBox<String> choice = new JComboBox<>();
            choice.setName("refinement-root-interval-"+index);
            choice.setPrototypeDisplayValue(tr("Detector and candidate selection"));
            populateIntervalRouteChoices(choice, run.routes());
            intervalControls.add(choice);
            choices.add(choice);
        }
        JTextArea quality = new JTextArea(8, 78);
        quality.setName("modern-quality");
        quality.setEditable(false);
        quality.setLineWrap(true);
        quality.setWrapStyleWord(true);
        JScrollPane qualityScroll = new JScrollPane(quality);
        qualityScroll.setPreferredSize(new Dimension(680, 170));
        JButton confirm = new JButton(tr("Confirm review"));
        JButton apply = new JButton(tr("Apply"));
        confirm.setName("modern-confirm"); apply.setName("modern-apply");
        JButton close = new JButton(tr("Close preview"));
        close.setName("modern-close");
        boolean[] applying = {false};
        boolean[] completed = {false};
        JButton disconnect = new JButton(tr("Disconnect selected way at shared nodes…"));
        disconnect.setName("modern-disconnect");
        disconnect.addActionListener(event -> showDisconnectionChooser(owner, dialog, dataSet,
                selection, imageryLayer, mapView, slideConfig, persistedSlideConfig, tracingAtCapture,
                computed, diagnosticAttemptIdentity, previewSourceOwner, () -> completed[0] = true));
        JScrollPane intervalScroll = new JScrollPane(intervalControls);
        intervalScroll.setPreferredSize(new Dimension(680, 180));
        String settingsNotice = modernSettingsNotice(persistedSlideConfig, slideConfig);
        if (!settingsNotice.isEmpty()) {
            body.add(settingsNoticeComponent(settingsNotice), BorderLayout.NORTH);
        }
        sourceChoices.setPrototypeDisplayValue(tr("Selected raster: detector and source"));
        sourceChoices.setVisible(sourceChoices.getItemCount() > 1);
        panel.add(sourceChoices, BorderLayout.NORTH);
        intervalScroll.setName("interval-route-scroll");
        qualityScroll.setName("interval-quality-scroll");
        var split = new javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT, intervalScroll, qualityScroll);
        split.setResizeWeight(0.5);
        split.setPreferredSize(new Dimension(680, 260));
        body.add(split, BorderLayout.CENTER);
        buttons.add(disconnect);buttons.add(confirm);buttons.add(apply);buttons.add(close);
        footer.add(buttons,BorderLayout.SOUTH);panel.add(footer,BorderLayout.SOUTH);
        panel.add(body,BorderLayout.CENTER);
        dialog.setContentPane(panel);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        packReviewDialog(dialog);
        dialog.setLocationRelativeTo(MainApplication.getMainFrame());
        activePreviewDialog = dialog;
        livePreviewSession.replaceWindow(owner, dialog::dispose);

        Runnable refresh = () -> {
            if (!livePreviewSession.isCurrentWindow(owner, dialog, activePreviewDialog,
                    dialog.isDisplayable())) {
                throw new IllegalStateException("The interval preview no longer owns this attempt");
            }
            if(refinement[0]!=null&&refinement[0].nativeSelected()) {
                var workflow=refinement[0];boolean root=workflow.atRoot(),busy=workflow.busy();
                if(root) {
                    rebuildingChoices[0]=true;
                    try {
                        state.restoreSelection(refinementRoot[0].retainedIntervalSelection(workflow.selectedChoiceIdentity()));
                        sourceChoices.setSelectedIndex(state.sourceIndex());
                        for(int i=0;i<choices.size();i++) {populateIntervalRouteChoices(choices.get(i),state.batch().runs().get(i).routes());choices.get(i).setSelectedIndex(state.routeChoices().getOrDefault(i,0));}
                    }finally{rebuildingChoices[0]=false;}
                }
                sourceChoices.setVisible(root&&sourceChoices.getItemCount()>1);intervalScroll.setVisible(root);qualityScroll.setVisible(root);
                sourceChoices.setEnabled(root&&!busy);for(int i=0;i<choices.size();i++)choices.get(i).setEnabled(root&&!busy&&!state.batch().runs().get(i).routes().isEmpty());
                workflow.controls().externalRootSelection(root);
                var plan=workflow.currentNative().map(RefinementPayload.NativeReview::plan);quality.setText(intervalPreviewSummary(state));
                confirm.setEnabled(!busy&&plan.filter(value->value.validation().disposition()==ValidationReport.Disposition.REVIEW_REQUIRED).isPresent()&&!workflow.canApplyNative());
                apply.setEnabled(!busy&&!applying[0]&&workflow.canApplyNative());disconnect.setEnabled(false);return;
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
                    : assessment.noChangeProof().map(proof->proof.finalSelection().validation().disposition()).orElse(ValidationReport.Disposition.HARD_BLOCKED);
            overlay.setDisconnectionLocations(disconnectionLocations(assessment.plan().orElse(null)));
            overlay.show(selection, display, carrier, switch (disposition) {
                case APPLICABLE -> CandidateAssessment.Disposition.APPLICABLE;
                case REVIEW_REQUIRED -> CandidateAssessment.Disposition.REVIEW_REQUIRED;
                case HARD_BLOCKED -> CandidateAssessment.Disposition.HARD_BLOCKED;
            }, state.review() != null && state.review().confirmed(),
                    PluginPreferences.isDebugEnabled(), finalWays);
            ModernApplyPreflight preflight = modernApplyPreflight(computed.captured(), slideConfig);
            boolean sourceApply = intervalApplySourceAvailable(computed.captured());
            quality.setText(intervalPreviewSummary(state)
                    + org.openstreetmap.josm.plugins.wayheatmaptracer.service.ModernPlanReview.summary(
                            assessment.plan().orElse(null), state.batch().evidence(),
                            state.batch().fullRequest().permissions().ordinaryRadiusMeters())
                    + (assessment.unownedDisconnectionOccurrences().isEmpty() ? ""
                            : "\nChosen disconnections have no safe production route: "
                                    + assessment.unownedDisconnectionOccurrences())
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
            if(refinement[0]!=null&&!refinement[0].currentSourceAvailable()){apply.setEnabled(false);confirm.setEnabled(false);disconnect.setEnabled(false);}
        };
        Runnable staleFailure = () -> {
            if(refinement[0]!=null) {
                if(refinement[0].atRoot()) {
                    rebuildingChoices[0]=true;
                    try {
                        state.restoreSelection(refinementRoot[0].retainedIntervalSelection(refinementRoot[0].choiceId(refinementRoot[0].selectedCandidate())));
                        sourceChoices.setSelectedIndex(state.sourceIndex());
                        for(int i=0;i<choices.size();i++){populateIntervalRouteChoices(choices.get(i),state.batch().runs().get(i).routes());choices.get(i).setSelectedIndex(state.routeChoices().getOrDefault(i,0));}
                    }finally{rebuildingChoices[0]=false;}
                }
                refinement[0].refresh();if(refinement[0].currentSourceAvailable())refresh.run();quality.setText(tr("Root selection unavailable; the previous composition is retained. Run alignment again if its source changed.")+"\n"+intervalPreviewSummary(state));return;
            }
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
        sourceChoices.addActionListener(event -> {
            if(rebuildingChoices[0])return;
            try {
                if(refinementRoot[0]!=null)refinementRoot[0].requireAuthorityCurrent();else requireLiveBCurrent(dataSet, selection, imageryLayer, mapView, slideConfig,
                        persistedSlideConfig, tracingAtCapture, computed.captured(), previewSourceOwner);
                state.chooseSource(sourceChoices.getSelectedIndex());
                rebuildingChoices[0] = true;
                try {
                    for (int index = 0; index < choices.size(); index++) {
                        var run = state.batch().runs().get(index);
                        var choice = choices.get(index);
                        populateIntervalRouteChoices(choice, run.routes());
                    }
                } finally {
                    rebuildingChoices[0] = false;
                }
                if(refinement[0]!=null) {
                    refinement[0].selectIntervalComposition(new ModernReviewRoot.IntervalSelection(state.selectedRun(),state.routeChoices(),state.assessment()));return;
                }
                refresh.run();
                recordIntervalDiagnostics(computed, state, IntervalArtifactStatus.PREVIEW,
                        diagnosticAttemptIdentity);
            } catch (RuntimeException stale) {
                staleFailure.run();
            }
        });
        for (int index = 0; index < choices.size(); index++) {
            final int intervalIndex = index;
            choices.get(index).addActionListener(event -> {
                if (rebuildingChoices[0]) return;
                try {
                    if(refinementRoot[0]!=null)refinementRoot[0].requireAuthorityCurrent();else requireLiveBCurrent(dataSet, selection, imageryLayer, mapView, slideConfig,
                            persistedSlideConfig, tracingAtCapture, computed.captured(), previewSourceOwner);
                    state.choose(intervalIndex, choices.get(intervalIndex).getSelectedIndex());
                    if(refinement[0]!=null) {refinement[0].selectIntervalComposition(new ModernReviewRoot.IntervalSelection(state.selectedRun(),state.routeChoices(),state.assessment()));return;}
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
                if(refinement[0]!=null&&refinement[0].nativeSelected()){refinement[0].confirm();refresh.run();return;}
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
            boolean[] commandAttempted = {false};
            if(refinement[0]!=null&&refinement[0].nativeSelected()) {
                boolean applied=applyRefinementReview(refinement[0],dataSet,slideConfig,()->completed[0]=true,()->{dialog.dispose();overlay.hide();PluginLog.endSlideSession();});
                applying[0]=false;if(!applied&&dialog.isDisplayable())refresh.run();return;
            }
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
                if(state.assessment().noChangeProof().isPresent()) {
                    var currentAssessment=new FixedIntervalEditPlanComposer().compose(state.batch(),state.routeChoices());
                    if(!currentAssessment.equals(state.assessment()))throw new IllegalStateException("Unchanged interval composition changed before acceptance");
                    var current=state.selectedRun().captured().originalReceipt().orElseThrow().requireCurrent(dataSet);
                    currentAssessment.noChangeProof().orElseThrow().requireMatches(state.batch(),state.routeChoices(),current);
                    recordIntervalDiagnostics(computed,state,IntervalArtifactStatus.PREVIEW,diagnosticAttemptIdentity);
                    completed[0]=true;dialog.dispose();overlay.hide();return;
                }
                AlignmentEditPlan currentPlan = state.currentPlanForApply();
                NetworkSnapshotCapture.CapturedSnapshot receipt = computed.captured().originalReceipt().orElseGet(()->NetworkSnapshotCapture.captureBound(
                        dataSet, computed.captured().specification()));
                var generation=detachedNativeCommandGeneration(computed.captured(),previewSourceOwner,detachedCommandContext(dataSet,computed.captured().managedRaster()==null?imageryLayer:null,computed.captured().managedRaster()!=null?previewSourceOwner:null));
                LiveNetworkSnapshotValidator network = new LiveNetworkSnapshotValidator(receipt,
                        currentPlan,generation);
                if (computed.captured().managedRaster() != null) {
                    ManagedSourceReceipt.forCurrentPlugin(previewSourceOwner, computed.captured(),
                            slideConfig.heatmap(), state.batch().options().sourceTier()).requireCurrent();
                }
                ApplyAlignmentEditPlanCommand command = new ApplyAlignmentEditPlanCommand(dataSet,
                        currentPlan,detachedNativeApplyValidator(network,generation,detachedSourceFailureReporter()),
                        tr("Apply modern interval alignment"));
                applyWithPreparedIntervalDiagnostics(
                        () -> createIntervalDiagnostics(computed, state,
                                state.review() != null && state.review().confirmed()
                                    ? IntervalArtifactStatus.APPLIED_AFTER_REVIEW
                                    : IntervalArtifactStatus.APPLIED),
                        currentPlan.canonicalHash(),
                        () -> {commandAttempted[0]=true;UndoRedoHandler.getInstance().add(command);},
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
                if (livePreviewSession.isCurrent(owner)) {
                    recordIntervalDiagnostics(computed, state, IntervalArtifactStatus.FAILED,
                            diagnosticAttemptIdentity);
                }
                if(commandAttempted[0]||refinement[0]==null) {
                    completed[0]=true;
                    dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
                } else {
                    applying[0]=false;refinement[0].refresh();
                    apply.setEnabled(false);confirm.setEnabled(false);disconnect.setEnabled(false);
                }
                showError(tr("Interval alignment Apply failed: {0}", failure.getMessage()));
            }
        });
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                if (closeAndPublishIfCurrent(livePreviewSession, owner, () -> {
                    if (!completed[0]) {
                        if(refinement[0]!=null&&refinement[0].nativeSelected())return;
                        recordIntervalDiagnostics(computed, state, IntervalArtifactStatus.CANCELLED,
                                diagnosticAttemptIdentity);
                    }
                })) {
                    overlay.hide();
                    PluginLog.endSlideSession();
                }
            }
            @Override public void windowClosed(WindowEvent event) {
                if(refinement[0]!=null)refinement[0].close();if(refinementRoot[0]!=null)refinementRoot[0].close();
                if (activePreviewDialog == dialog) activePreviewDialog = null;
            }
        });
        close.addActionListener(event -> dialog.dispatchEvent(
                new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING)));
        if(slideConfig.heatmap().trackerMode()==TrackerMode.CORRIDOR_AWARE||slideConfig.heatmap().trackerMode()==TrackerMode.PROBABILISTIC) {
            try {
                var initial=new ModernReviewRoot.IntervalSelection(state.selectedRun(),state.routeChoices(),state.assessment());
                refinementRoot[0]=ModernReviewRoot.fromIntervalSelection(dataSet,selection,slideConfig,initial,state.sourceRuns(),previewSourceOwner,
                    ()->requireLiveBSourceOwnerCurrent(dataSet,imageryLayer,slideConfig,persistedSlideConfig,tracingAtCapture,computed.captured(),previewSourceOwner));
                refinement[0]=new LegacyRefinementWorkflow(refinementRoot[0],previewSourceOwner,candidate->{
                    state.restoreSelection(refinementRoot[0].retainedIntervalSelection(refinementRoot[0].choiceId(candidate)));refresh.run();
                },displayed->{
                    showNativeRefinementDisplay(selection,displayed,computed.captured().projectionCode());
                },message->{
                    if(refinement[0]==null)return;
                    if(refinement[0].busy()) {sourceChoices.setEnabled(false);choices.forEach(choice->choice.setEnabled(false));apply.setEnabled(false);confirm.setEnabled(false);disconnect.setEnabled(false);}
                    else if(refinement[0].currentSourceAvailable())refresh.run();
                    else {sourceChoices.setEnabled(false);choices.forEach(choice->choice.setEnabled(false));apply.setEnabled(false);confirm.setEnabled(false);disconnect.setEnabled(false);quality.setText(tr("The source changed. This cached result is available for inspection and diagnostics; run alignment again before applying."));}
                },nativeVisibleCapturePort(computed.captured(),imageryLayer,mapView,refinementRoot[0]::requireAuthorityCurrent));
                refinement[0].controls().externalRootSelection(true);refinement[0].enableNativeRuntimeDiagnostics(candidate->createNativeRootContext(computed,candidate,false),candidate->createNativeRootContext(computed,candidate,true),()->originalNativeWork(computed));
                refinement[0].attachCommandContext(detachedCommandContext(dataSet,computed.captured().managedRaster()==null?imageryLayer:null,computed.captured().managedRaster()!=null?previewSourceOwner:null));
                footer.add(refinement[0].controls(),BorderLayout.CENTER);
                dialog.getRootPane().putClientProperty("wayheatmaptracer.refinementOwner",refinement[0]);
                livePreviewSession.replaceWindow(owner,()->{refinement[0].close();dialog.dispose();overlay.hide();});
                refinement[0].attachOwnership(livePreviewSession.workGate(),owner,()->{livePreviewSession.close(owner);dialog.dispose();overlay.hide();});
                attachRefinementLifecycle(dialog,refinement[0]);
                panel.setPreferredSize(new Dimension(700, 600));
                packReviewDialog(dialog);
            }catch(RuntimeException unavailable) {
                if(refinement[0]!=null){footer.remove(refinement[0].controls());refinement[0].close();}if(refinementRoot[0]!=null)refinementRoot[0].close();refinement[0]=null;refinementRoot[0]=null;
                if(livePreviewSession.isCurrent(owner))livePreviewSession.replaceWindow(owner,dialog::dispose);
                body.add(new JLabel(tr("Optional refinement preparation is unavailable for this capture.")), BorderLayout.SOUTH);
                PluginLog.verbose("Native interval refinement attachment unavailable: %s",unavailable.getClass().getSimpleName());
            }
        }
        try {
            refresh.run();
            recordIntervalDiagnostics(computed, state, IntervalArtifactStatus.PREVIEW,
                    diagnosticAttemptIdentity);
            dialog.setVisible(true);
            OrdinaryActionBenchmarkObserver.previewReadyInterval(computed, state::assessment);
        } catch (RuntimeException failure) {
            dialog.dispose();
            throw failure;
        }
    }

    private static List<EastNorth> disconnectionLocations(AlignmentEditPlan plan) {
        if (plan == null || plan.disconnectionSource() == null) return List.of();
        return plan.permissions().sharedNodeDisconnectionIntent().originalNodeKeys().values().stream()
                .map(key -> (org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode)
                        plan.disconnectionSource().primitives().get(key))
                .map(node -> projectGeographic(node.coordinate())).toList();
    }

    /** Keeps choices local to this window and recomputes through a fresh owned live capture. */
    private void showDisconnectionChooser(PreviewSessionController.Owner owner, JDialog preview,
            DataSet dataSet, SelectionContext selection, ImageryLayer imageryLayer, MapView mapView,
            AlignmentConfig config, AlignmentConfig persisted, TracingSettings tracing,
            LiveBPreviewService.Computed computed, String attemptIdentity,
            TileFetchCoordinator sourceOwner, Runnable beforeRestart) {
        try {
            if (!livePreviewSession.isCurrentWindow(owner, preview, activePreviewDialog, preview.isDisplayable()))
                throw new IllegalStateException("This preview no longer owns the current attempt");
            requireLiveBCurrent(dataSet, selection, imageryLayer, mapView, config, persisted, tracing,
                    computed.captured(), sourceOwner);
            RecoveryPermissions current = computed.captured().specification().permissions();
            if (current.junctionPolicy() != JunctionPolicy.FIXED || current.reconstructIncidentWays())
                throw new IllegalArgumentException("Use fixed junctions before explicitly disconnecting selected-way occurrences.");
            var source = computed.captured().disconnectionSource();
            if (source == null) throw new IllegalStateException("The original pre-island source is unavailable; run a new slide.");
            JDialog chooser = new JDialog(preview, tr("Disconnect selected way at shared nodes"), false);
            var buttons = disconnectionChooserControls(source, computed.request().selectedWayKey(),
                    computed.request().selectedRange(), current, () -> {
                        if (!livePreviewSession.isCurrentWindow(owner, preview, activePreviewDialog,
                                preview.isDisplayable())) throw new IllegalStateException("The disconnection choice is stale");
                        requireLiveBCurrent(dataSet, selection, imageryLayer, mapView, config, persisted,
                                tracing, computed.captured(), sourceOwner);
                    }, chooser::dispose, permissions -> {
                        beforeRestart.run();
                        if (!livePreviewSession.close(owner)) throw new IllegalStateException("The preview was superseded");
                        chooser.dispose(); preview.dispose();
                        boolean managed = computed.captured().managedRaster() != null;
                        startLiveBPreview(dataSet, selection, imageryLayer, mapView, config, config,
                                persisted, tracing, permissions, null, !managed, managed,
                                attemptIdentity + "-disconnect");
                    }, failure -> {
                        chooser.dispose();
                        failDisconnectionPreview(owner, preview, computed, attemptIdentity,
                                tr("Disconnection preview was not started: {0}", failure.getMessage()));
                    });
            var choice = buttons.choices();
            JButton all = new JButton(tr("Select all eligible"));
            JLabel count = new JLabel(tr("Connections removed: {0}", choice.affectedConnectionCount()));
            Runnable recount = () -> count.setText(tr("Connections removed: {0}", choice.affectedConnectionCount()));
            for (java.awt.Component component : choice.getComponents())
                if (component instanceof javax.swing.JCheckBox check) check.addItemListener(event -> recount.run());
            JPanel controls = new JPanel(); controls.add(all); controls.add(count);
            controls.add(buttons.recompute()); controls.add(buttons.cancel());
            JPanel content = new JPanel(new BorderLayout(0, 6));
            content.add(new JScrollPane(choice), BorderLayout.CENTER); content.add(controls, BorderLayout.SOUTH);
            chooser.setContentPane(content); chooser.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            chooser.addWindowListener(new WindowAdapter() {
                @Override public void windowClosed(WindowEvent event) {
                    if (livePreviewSession.isCurrent(owner) && preview.isDisplayable()) preview.setEnabled(true);
                }
            });
            all.addActionListener(event -> choice.selectAllEligible());
            chooser.setPreferredSize(new Dimension(800, 300)); chooser.pack();
            chooser.setLocationRelativeTo(preview); preview.setEnabled(false); chooser.setVisible(true);
        } catch (IllegalArgumentException unavailable) {
            if (livePreviewSession.isCurrent(owner))
                showWorkerFailure(tr("Disconnection is unavailable: {0}", unavailable.getMessage()));
        } catch (RuntimeException failure) {
            failDisconnectionPreview(owner, preview, computed, attemptIdentity,
                    tr("Disconnection is unavailable: {0}", failure.getMessage()));
        }
    }

    record DisconnectionChooserControls(
            org.openstreetmap.josm.plugins.wayheatmaptracer.ui.SharedNodeDisconnectionPanel choices,
            JButton recompute, JButton cancel) { }

    /** The same button handlers used by the modeless dialog and its orchestration regression. */
    static DisconnectionChooserControls disconnectionChooserControls(
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot source,
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey way,
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange range,
            RecoveryPermissions current, Runnable requireCurrent, Runnable closeChooser,
            java.util.function.Consumer<RecoveryPermissions> restart,
            java.util.function.Consumer<RuntimeException> failed) {
        var choices = new org.openstreetmap.josm.plugins.wayheatmaptracer.ui.SharedNodeDisconnectionPanel(
                source, way, range, current.sharedNodeDisconnectionIntent());
        JButton recompute = new JButton(tr("Recompute preview"));
        JButton cancel = new JButton(tr("Cancel"));
        cancel.addActionListener(event -> closeChooser.run());
        recompute.addActionListener(event -> {
            try {
                requireCurrent.run();
                var intent = choices.selectedIntent();
                if (intent.isEmpty() && current.sharedNodeDisconnectionIntent().isEmpty()) {
                    closeChooser.run(); return;
                }
                if (!intent.isEmpty() && org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot
                        .SharedNodeDisconnectionEligibility.evaluate(source, intent).decisions().values()
                        .stream().anyMatch(decision -> !decision.eligible()))
                    throw new IllegalStateException("A chosen occurrence requires manual handling");
                restart.accept(new RecoveryPermissions(current.widerDiscovery(), current.ordinaryRadiusMeters(),
                        current.maximumDiscoveryRadiusMeters(), JunctionPolicy.FIXED, false, intent));
            } catch (RuntimeException failure) { failed.accept(failure); }
        });
        return new DisconnectionChooserControls(choices, recompute, cancel);
    }

    private void failDisconnectionPreview(PreviewSessionController.Owner owner, JDialog preview,
            LiveBPreviewService.Computed computed, String attemptIdentity, String message) {
        boolean closed = closeAndPublishIfCurrent(livePreviewSession, owner, () -> {
            try {
                if (computed.partitioned()) {
                    recordIntervalDiagnostics(computed, new IntervalPreviewState(computed),
                            IntervalArtifactStatus.FAILED, attemptIdentity);
                } else {
                    recordModernDiagnostics(computed, "failed", 0, null, false, false, attemptIdentity);
                }
            } catch (RuntimeException failure) {
                var fallback = Format15ProductionBundleFactory.createUnavailableLive(
                        LastSlideDebugBundle.buildIdentity(), "failed",
                        computed.captured().managedRaster() == null ? "visible-layer" : "managed-tiles", attemptIdentity);
                DiagnosticsRegistry.setLastModernBundle(withFailureSummary(fallback,
                        "PREVIEW", "DISCONNECTION_RECOMPUTE_REJECTED", computed));
            }
        });
        if (closed) {
            preview.dispose(); overlay.hide(); PluginLog.endSlideSession(); showWorkerFailure(message);
        }
    }

    private static EastNorth projectGeographic(
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint point) {
        return ProjectionRegistry.getProjection().latlon2eastNorth(
                new LatLon(point.latitudeDegrees(), point.longitudeDegrees()));
    }

    /** Populates both initial and source-rebuilt interval controls from actual route lineage. */
    static void populateIntervalRouteChoices(JComboBox<String> choice, List<ModernTracePipeline.Route> routes) {
        choice.removeAllItems();
        if (routes.isEmpty()) choice.addItem(tr("No production route"));
        for (int index = 0; index < routes.size(); index++) {
            var route = routes.get(index);
            String label = index == 0 ? tr("Route {0} (recommended)", index + 1) : tr("Route {0}", index + 1);
            boolean child = route.lineage().variant() != ModernTracePipeline.RouteVariant.BASE;
            boolean paired = child || routes.stream().anyMatch(other ->
                    other.lineage().variant() != ModernTracePipeline.RouteVariant.BASE
                        && other.lineage().nativeParentId().equals(route.hypothesis().id()));
            if (paired) label = tr("{0} — {1}", label, child ? tr("After smoothing") : tr("Before smoothing"));
            choice.addItem(label);
        }
        choice.setEnabled(!routes.isEmpty());
        choice.setSelectedIndex(0);
    }

    /** Formats fixed-island guidance and typed interval outcomes for the modeless preview. */
    public static String intervalPreviewSummary(IntervalPreviewState state) {
        StringBuilder summary = new StringBuilder();
        var field = state.batch().evidence().fields().get(state.batch().options().fieldName());
        String source = switch (field.lineage().acquisitionKind()) {
            case MANAGED_TILE -> tr("managed tiles");
            case VISIBLE_RENDER -> tr("visible rendered layer");
            case FROZEN_REPLAY -> tr("frozen replay evidence");
            case SYNTHETIC -> tr("synthetic evidence");
        };
        summary.append("Source: ").append(source).append('\n');
        for (SelectedWayIntervalPartitioner.FixedIsland island
                : state.batch().partition().fixedIslands()) {
            summary.append("Fixed junction occurrences ").append(island.range().firstIndex())
                    .append('–').append(island.range().lastIndex()).append(": ")
                    .append(FindingSummary.summarize(island.reasons().stream()
                            .map(Enum::name).toList()));
            summary.append(state.batch().fullRequest().permissions().junctionPolicy() == JunctionPolicy.FIXED
                    ? ". Junction adjustment is off; this approach stays fixed.\n"
                    : ". Adjust this junction manually first.\n");
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
                    .append("; findings: ").append(FindingSummary.summarize(
                            plan.validation().findingCodes())).append('\n');
        } else if (state.assessment().noChangeProof().isPresent()) {
            summary.append("Already aligned: no changes are proposed; no edit command or history entry "
                    + "will be created.\n").append("Validation: ").append(state.assessment().noChangeProof().orElseThrow().finalSelection().validation()).append('\n');
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

    /** Uses the typed completed inference result to explain a resource-limited no-preview failure. */
    static String noPreviewableRouteMessage(LiveBPreviewService.Computed computed) {
        if (computed == null) {
            return "Alignment returned no previewable final route";
        }
        TraceHypothesisSet inference = computed.pipeline() == null
                ? null : computed.pipeline().inference();
        if (inference == null || inference.status() != TraceHypothesisSet.Status.RESOURCE_LIMIT) {
            return noPreviewableRouteMessage(computed.captured(), computed.request().engine());
        }
        String message = tr("{0} stopped at its computation work limit: {1}. No preview was produced. "
                        + "Try a shorter selected segment or reduce Search half-width (currently {2} m) "
                        + "for an already aligned refinement, then run again. "
                        + "Use Export Last Alignment Diagnostics for details.",
                livePreviewEngineLabel(computed.request().engine()),
                previewFailureText(inference.explanation()),
                String.format(Locale.ROOT, "%.1f", computed.captured().searchRadiusMeters()));
        List<String> uncompleted = computed.sourceAttempts().stream()
                .filter(attempt -> attempt.status() == LiveBPreviewService.DetectorAttemptStatus.RESOURCE_LIMIT)
                .map(LiveBPreviewService.DetectorAttempt::mapping).distinct().toList();
        if (!uncompleted.isEmpty()) {
            message += tr(" Requested detector runs did not complete: {0}. Their route availability is unknown.",
                    String.join(", ", uncompleted));
        }
        List<String> unavailable = computed.sourceAttempts().stream()
                .filter(attempt -> attempt.status() == LiveBPreviewService.DetectorAttemptStatus.SOURCE_UNAVAILABLE)
                .map(LiveBPreviewService.DetectorAttempt::mapping).distinct().toList();
        if (!unavailable.isEmpty()) {
            message += tr(" Detector sources unavailable and not run: {0}.",
                    String.join(", ", unavailable));
        }
        return message;
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
            List<LiveBPreviewService.PreviewChoice> previewChoices, String diagnosticAttemptIdentity,
            TileFetchCoordinator previewSourceOwner) {
        List<CenterlineCandidate> candidates = previewChoices.stream()
                .map(LiveBPreviewService.PreviewChoice::candidate).toList();
        JComboBox<LiveBPreviewService.PreviewChoice> choices = new JComboBox<>(
                previewChoices.toArray(LiveBPreviewService.PreviewChoice[]::new)) {
            @Override public Dimension getPreferredSize() {
                Dimension size = super.getPreferredSize();
                return new Dimension(Math.min(size.width, 480), size.height);
            }
        };
        choices.setName("modern-root-choice");
        choices.setRenderer(new DefaultListCellRenderer() {
            @Override public java.awt.Component getListCellRendererComponent(JList<?> list,
                    Object value, int index, boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                if (value instanceof LiveBPreviewService.PreviewChoice choice) {
                    setText(choice.sourceLabel() + " — " + choice.candidate().displayName());
                }
                return this;
            }
        });
        LiveBPreviewService.PreviewChoice firstChoice = previewChoices.get(0);
        JTextArea quality = new JTextArea(liveBQualitySummary(firstChoice.owner(),
                firstChoice.localRouteIndex()), 5, 76);
        quality.setName("modern-quality");
        quality.setEditable(false);
        quality.setLineWrap(true);
        quality.setWrapStyleWord(true);
        quality.setCaretPosition(0);
        JLabel diagnostics = new JLabel(tr(
                "This preview is bound to its captured source and network snapshot."));
        ModernSingleWayEditPlanAdapter planAdapter = new ModernSingleWayEditPlanAdapter();
        AlignmentEditPlan[] plan = {null};
        PreviewReviewState[] review = {null};
        ModernSingleWayEditPlanAdapter.Assessment[] assessment = {null};
        NetworkSnapshot[] noChangeCurrent = {null};
        int[] benchmarkDisplayedIndex = {-1};
        AlignmentEditPlan[] benchmarkDisplayedPlan = {null};
        boolean[] applying = {false};
        boolean[] completed = {false};
        LegacyRefinementWorkflow[] refinement = {null};
        ModernReviewRoot[] refinementRoot = {null};
        JButton confirm = new JButton(tr("Confirm review"));
        JButton apply = new JButton(tr("Apply"));
        confirm.setName("modern-confirm"); apply.setName("modern-apply");
        JButton preciseRerun = new JButton(tr("Rerun once with Precise Shape"));
        preciseRerun.setName("modern-precise-rerun");
        JButton disconnect = new JButton(tr("Disconnect selected way at shared nodes…"));
        disconnect.setName("modern-disconnect");
        confirm.setEnabled(false);
        apply.setEnabled(false);
        preciseRerun.setVisible(false);
        JButton close = new JButton(tr("Close preview"));
        close.setName("modern-close");
        JPanel header = new JPanel();
        header.setLayout(new javax.swing.BoxLayout(header, javax.swing.BoxLayout.Y_AXIS));
        choices.setPrototypeDisplayValue(firstChoice);
        header.add(new JLabel(tr("{0} final geometry",
                livePreviewEngineLabel(slideConfig.heatmap().trackerMode()))));
        String settingsNotice = modernSettingsNotice(persistedSlideConfig, slideConfig);
        if (!settingsNotice.isEmpty()) {
            header.add(settingsNoticeComponent(settingsNotice));
        }
        if (candidates.size() > 1) {
            header.add(choices);
        }
        header.add(diagnostics);
        JPanel buttons = new JPanel(new java.awt.GridLayout(0, 2, 6, 4));
        buttons.add(confirm);
        buttons.add(apply);
        buttons.add(preciseRerun);
        buttons.add(disconnect);
        buttons.add(close);
        JPanel panel = modernReviewContent(quality, buttons);
        var hybridSections = new org.openstreetmap.josm.plugins.wayheatmaptracer.ui.HybridSectionReviewPanel(overlay);
        if (computed.legacyB() != null) {
            java.awt.Component reviewScroll = ((BorderLayout) panel.getLayout()).getLayoutComponent(BorderLayout.CENTER);
            JPanel reviewBody = new JPanel(new BorderLayout(0, 6));
            reviewBody.add(reviewScroll, BorderLayout.CENTER);
            reviewBody.add(hybridSections, BorderLayout.SOUTH);
            panel.add(reviewBody, BorderLayout.CENTER);
            panel.setPreferredSize(new Dimension(680, 435));
        }
        panel.add(header, BorderLayout.NORTH);
        JDialog dialog = new JDialog(MainApplication.getMainFrame(),
                tr("{0} Alignment Preview",
                        livePreviewEngineLabel(slideConfig.heatmap().trackerMode())), false);
        dialog.setContentPane(panel);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        packReviewDialog(dialog);
        dialog.setLocationRelativeTo(MainApplication.getMainFrame());
        activePreviewDialog = dialog;
        livePreviewSession.replaceWindow(previewOwner, dialog::dispose);
        disconnect.addActionListener(event -> showDisconnectionChooser(previewOwner, dialog,
                dataSet, selection, imageryLayer, mapView, slideConfig, persistedSlideConfig,
                tracingAtCapture, computed, diagnosticAttemptIdentity, previewSourceOwner,
                () -> completed[0] = true));
        Runnable refresh = () -> {
            benchmarkDisplayedIndex[0] = -1;
            benchmarkDisplayedPlan[0] = null;
            if (!livePreviewSession.isCurrentWindow(previewOwner, dialog,
                    activePreviewDialog, dialog.isDisplayable())) {
                throw new IllegalStateException("The preview window no longer owns this attempt");
            }
            int index = Math.max(0, choices.getSelectedIndex());
            LiveBPreviewService.PreviewChoice choice = previewChoices.get(index);
            LiveBPreviewService.Computed run = choice.owner();
            int routeIndex = choice.localRouteIndex();
            requireLiveBCurrent(dataSet, selection, imageryLayer, mapView, slideConfig, persistedSlideConfig,
                    tracingAtCapture, computed.captured(), previewSourceOwner);
            CenterlineCandidate candidate = choice.candidate();
            FinalGeometryEvaluator.Disposition disposition =
                    run.pipeline().routes().get(routeIndex).quality().disposition();
            plan[0] = null;
            review[0] = null;
            assessment[0] = null;
            noChangeCurrent[0] = null;
            ModernApplyPreflight preflight = modernApplyPreflight(computed.captured(), slideConfig);
            if (preflight == ModernApplyPreflight.READY) {
                assessment[0] = planAdapter.assess(run, routeIndex);
                if (assessment[0].availability()
                        == ModernSingleWayEditPlanAdapter.ApplyAvailability.NO_CHANGE) {
                    noChangeCurrent[0] = NetworkSnapshotCapture.capture(dataSet,
                            computed.captured().specification());
                    requireNoChangeCurrent(assessment[0], run, noChangeCurrent[0]);
                }
                if (assessment[0].plan().isPresent()) {
                    plan[0] = assessment[0].plan().orElseThrow();
                    review[0] = PreviewReviewState.fromEditPlan(choice.reviewIdentity(), plan[0]);
                }
            }
            if(refinementRoot[0]!=null)refinementRoot[0].bindDisplayedSelection(choice,assessment[0]);
            AlignmentResult display = liveBDisplayResult(selection, run, candidates,
                    candidate, plan[0],assessment[0]==null?Optional.empty():assessment[0].noChangeProof());
            ValidationReport.Disposition displayedDisposition = liveBDisplayedDisposition(
                    disposition, assessment[0], plan[0]);
            Map<PrimitiveKey, List<EastNorth>> projectedPreview =
                    assessment[0] == null || assessment[0].plan().isEmpty()&&assessment[0].noChangeProof().isEmpty()
                        ? Map.of()
                        : assessment[0].projectFinalPreviewWays(point -> ProjectionRegistry.getProjection()
                                .latlon2eastNorth(new LatLon(point.latitudeDegrees(),
                                        point.longitudeDegrees())));
            overlay.setDisconnectionLocations(disconnectionLocations(plan[0]));
            overlay.show(selection, display, candidate, switch (displayedDisposition) {
                case APPLICABLE -> CandidateAssessment.Disposition.APPLICABLE;
                case REVIEW_REQUIRED -> CandidateAssessment.Disposition.REVIEW_REQUIRED;
                case HARD_BLOCKED -> CandidateAssessment.Disposition.HARD_BLOCKED;
            }, false, PluginPreferences.isDebugEnabled(), projectedPreview);
            if (run.legacyB() != null) {
                String routeId = run.pipeline().routes().get(routeIndex).hypothesis().id();
                var sectionAssessment = run.legacyB().result().assessments().stream()
                        .filter(row -> row.candidate().id().equals(routeId)).findFirst().orElseThrow(() ->
                                new IllegalArgumentException("Hybrid section report lost its selected route"));
                hybridSections.setCandidate(sectionAssessment, candidate.finalPreviewPoints());
            } else {
                hybridSections.setCandidate(null, List.of());
            }
            String availability = assessment[0] == null ? modernApplyPreflightMessage(preflight)
                    : modernApplyAvailabilityMessage(assessment[0], preflight);
            List<String> reasons = plan[0] == null
                    ? run.pipeline().routes().get(routeIndex).quality().findings().stream()
                            .map(finding -> finding.code().name() + " (" + finding.severity().name() + ")")
                            .toList()
                    : plan[0].validation().findingCodes();
            String finalDisposition = assessment[0] != null
                    && assessment[0].availability()
                            == ModernSingleWayEditPlanAdapter.ApplyAvailability.MANUAL_JUNCTION
                    ? "MANUAL_JUNCTION"
                    : plan[0] == null ? disposition.name()
                    : plan[0].validation().disposition().name();
            recordModernDiagnostics(computed, run, modernPreviewStatus(
                    run.pipeline().inference().status(), displayedDisposition),
                    routeIndex, plan[0], false, false, diagnosticAttemptIdentity,
                    assessment[0] == null ? null : assessment[0].junctionReason(),
                    assessment[0], noChangeCurrent[0]);
            String sourceLabel = computed.captured().managedRaster() == null
                    ? tr("visible layer") : tr("managed tiles");
            String preciseRecovery = preciseRerunUnsupportedReason(selection, tracingAtCapture,
                    persistedSlideConfig, imageryLayer);
            boolean needsPrecise = assessment[0] != null && assessment[0].availability()
                    == ModernSingleWayEditPlanAdapter.ApplyAvailability.PRECISE_SHAPE_REQUIRED;
            if (needsPrecise) {
                availability += preciseRecovery == null
                        ? "\n" + tr("Recovery: a one-shot Precise Shape rerun is available")
                        : "\n" + tr("Precise Shape recovery unavailable: {0}", preciseRecovery);
            }
            quality.setText(org.openstreetmap.josm.plugins.wayheatmaptracer.service.ModernPlanReview.summary(
                    plan[0], run.evidence(), computed.captured().searchRadiusMeters())
                    + liveBQualitySummary(run, routeIndex) + "\n\n"
                    + modernPreviewSummary(livePreviewEngineLabel(run.request().engine()),
                            sourceLabel + " / " + choice.sourceLabel(), finalDisposition,
                            plan[0] == null ? null : plan[0].affectedWayKeys().size(), reasons,
                            review[0] != null && review[0].confirmed(), availability));
            quality.setCaretPosition(0);
            if (assessment[0] != null && !assessment[0].applyAvailable()) {
                PluginLog.verbose("Modern Apply is unavailable for candidate %s: %s",
                        candidate.id(), assessment[0].detail());
            }
            confirm.setEnabled(review[0] != null && review[0].disposition()
                    == org.openstreetmap.josm.plugins.wayheatmaptracer.model.ValidationReport.Disposition.REVIEW_REQUIRED
                    && assessment[0] != null && assessment[0].applyAvailable());
            apply.setEnabled(modernApplyEnabled(assessment[0] != null
                    && assessment[0].applyAvailable(), review[0], applying[0], false));
            preciseRerun.setVisible(needsPrecise);
            preciseRerun.setEnabled(needsPrecise && preciseRecovery == null);
            preciseRerun.setToolTipText(needsPrecise && preciseRecovery != null
                    ? preciseRecovery : tr("Runs one new preview in Precise Shape without changing saved settings"));
            benchmarkDisplayedIndex[0] = index;
            benchmarkDisplayedPlan[0] = plan[0];
            };
        choices.addActionListener(event -> {
            try {
                if(refinementRoot[0]!=null)refinementRoot[0].select(previewChoices.get(Math.max(0,choices.getSelectedIndex())).candidate());
                refresh.run();
                if(refinement[0]!=null)refinement[0].refresh();
            } catch (RuntimeException exception) {
                LiveBPreviewService.PreviewChoice failedChoice = previewChoices.get(
                        Math.max(0, choices.getSelectedIndex()));
                recordModernDiagnostics(computed, failedChoice.owner(), "failed", failedChoice.localRouteIndex(),
                        null, false, false, diagnosticAttemptIdentity);
                completed[0]=true;
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
                if(refinement[0]!=null){if(refinement[0].handlesRuntimeDiagnostics())completed[0]=true;refinement[0].close();}
                boolean closed = closeAndPublishIfCurrent(livePreviewSession, previewOwner, () -> {
                    if (!completed[0]) {
                        completed[0] = true;
                        LiveBPreviewService.PreviewChoice closingChoice = previewChoices.get(
                                Math.max(0, choices.getSelectedIndex()));
                        recordModernDiagnostics(computed, closingChoice.owner(), "cancelled",
                                closingChoice.localRouteIndex(),
                                null, false, false,
                                diagnosticAttemptIdentity);
                    }
                });
                if (closed) {
                    overlay.hide();
                    PluginLog.endSlideSession();
                }
            }
            @Override public void windowClosed(WindowEvent event) {
                if(refinement[0]!=null)refinement[0].close();
                if(refinementRoot[0]!=null)refinementRoot[0].close();
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
                if(refinement[0]!=null&&refinement[0].nativeSelected()) {
                    int answer=JOptionPane.showConfirmDialog(dialog,tr("Confirm that you reviewed the complete displayed refinement geometry."),tr("Confirm reviewed candidate"),JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE);
                    if(answer==JOptionPane.YES_OPTION){refinement[0].confirm();refinement[0].refresh();}
                    return;
                }
                refresh.run();
                if (review[0] == null || review[0].disposition()
                        != org.openstreetmap.josm.plugins.wayheatmaptracer.model.ValidationReport.Disposition.REVIEW_REQUIRED
                        || assessment[0] == null || !assessment[0].applyAvailable()) {
                    throw new IllegalStateException("This candidate does not require a confirmable review");
                }
                review[0] = review[0].confirm();
                int index = Math.max(0, choices.getSelectedIndex());
                LiveBPreviewService.PreviewChoice choice = previewChoices.get(index);
                LiveBPreviewService.Computed run = choice.owner();
                int routeIndex = choice.localRouteIndex();
                recordModernDiagnostics(computed, run, "confirmed", routeIndex, plan[0], true, false,
                        diagnosticAttemptIdentity, null, assessment[0], null);
                confirm.setEnabled(false);
                apply.setEnabled(modernApplyEnabled(assessment[0] != null
                        && assessment[0].applyAvailable(), review[0], applying[0], false));
                CenterlineCandidate candidate = choice.candidate();
                AlignmentResult display = liveBDisplayResult(selection, run, candidates,
                        candidate, plan[0]);
                Map<PrimitiveKey, List<EastNorth>> projectedPreview =
                        assessment[0].projectFinalPreviewWays(point -> ProjectionRegistry.getProjection()
                                .latlon2eastNorth(new LatLon(point.latitudeDegrees(),
                                        point.longitudeDegrees())));
                overlay.show(selection, display, candidate,
                        CandidateAssessment.Disposition.REVIEW_REQUIRED, true,
                        PluginPreferences.isDebugEnabled(), projectedPreview);
                quality.setText(org.openstreetmap.josm.plugins.wayheatmaptracer.service.ModernPlanReview.summary(
                        plan[0], run.evidence(), computed.captured().searchRadiusMeters())
                        + liveBQualitySummary(run, routeIndex) + "\n\n"
                        + modernPreviewSummary(livePreviewEngineLabel(run.request().engine()),
                                computed.captured().managedRaster() == null
                                        ? tr("visible layer") : tr("managed tiles / ")
                                            + choice.sourceLabel(),
                                plan[0].validation().disposition().name(),
                                plan[0].affectedWayKeys().size(),
                                plan[0].validation().findingCodes(), true,
                                tr("review confirmed; Apply available")));
            } catch (RuntimeException exception) {
                LiveBPreviewService.PreviewChoice failedChoice = previewChoices.get(
                        Math.max(0, choices.getSelectedIndex()));
                recordModernDiagnostics(computed, failedChoice.owner(), "failed", failedChoice.localRouteIndex(),
                        null, false, false, diagnosticAttemptIdentity);
                if(refinement[0]!=null) {
                    refinement[0].refresh();apply.setEnabled(false);confirm.setEnabled(false);disconnect.setEnabled(false);preciseRerun.setEnabled(false);
                } else {
                    completed[0]=true;dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
                }
                showError(tr("Alignment preview became stale: {0}", exception.getMessage()));
            }
        });
        preciseRerun.addActionListener(event -> {
            try {
                if (assessment[0] == null || assessment[0].availability()
                        != ModernSingleWayEditPlanAdapter.ApplyAvailability.PRECISE_SHAPE_REQUIRED) {
                    throw new IllegalStateException(tr("Precise Shape rerun is not available for this candidate"));
                }
                requireLiveBCurrent(dataSet, selection, imageryLayer, mapView, slideConfig,
                        persistedSlideConfig, tracingAtCapture, computed.captured(), previewSourceOwner);
                OrdinaryActionRouting<ImageryLayer> rerun = resolvePreciseRerun(
                        assessment[0].availability(), tracingAtCapture, persistedSlideConfig,
                        () -> imageryLayer, () -> imageryLayer);
                AlignmentConfig routedConfig = rerun.route().invocation().config();
                if (rerun.route().pipeline() == OrdinaryPipeline.MODERN_VISIBLE) {
                    LiveBPreviewService.requireSupported(selection,
                            ProjectionRegistry.getProjection().toCode(), routedConfig, true);
                } else if (!routedConfig.heatmap().trackerMode().capabilities().supportsManagedSource()) {
                    throw new IllegalStateException(tr("The selected engine cannot use the managed source for Precise Shape"));
                }
                completed[0] = true;
                if (!livePreviewSession.close(previewOwner)) {
                    throw new IllegalStateException(tr("The preview is no longer current"));
                }
                dialog.dispose();
                startLiveBPreview(dataSet, selection, rerun.visibleSource(), mapView,
                        routedConfig, rerun.requestedConfig(), persistedSlideConfig, tracingAtCapture,
                        rerun.route().invocation().recovery().toPermissions(), rerun,
                        rerun.route().pipeline() == OrdinaryPipeline.MODERN_VISIBLE,
                        rerun.route().pipeline() == OrdinaryPipeline.MODERN_MANAGED,
                        diagnosticAttemptIdentity + "-precise-rerun");
            } catch (RuntimeException exception) {
                showError(tr("Precise Shape rerun is unavailable: {0}", exception.getMessage()));
            }
        });
        apply.addActionListener(event -> {
            if (applying[0]) {
                return;
            }
            applying[0] = true;
            apply.setEnabled(false);
            boolean[] commandAttempted = {false};
            try {
                if(refinement[0]!=null&&refinement[0].nativeSelected()) {
                    boolean applied=applyRefinementReview(refinement[0],dataSet,slideConfig,()->completed[0]=true,()->{dialog.dispose();overlay.hide();PluginLog.endSlideSession();});
                    applying[0]=false;if(!applied&&dialog.isDisplayable())refresh.run();return;
                }
                int index = Math.max(0, choices.getSelectedIndex());
                LiveBPreviewService.PreviewChoice choice = previewChoices.get(index);
                LiveBPreviewService.Computed run = choice.owner();
                int routeIndex = choice.localRouteIndex();
                boolean detached=run.captured().originalReceipt().isPresent()&&(run.captured().managedRaster()!=null||run.captured().raster().sourceReceipt()!=null);
                long capturedGeneration=run.captured().network().sourceGeneration();
                LongSupplier generation=detached?detachedNativeCommandGeneration(run.captured(),previewSourceOwner,
                    detachedCommandContext(dataSet,run.captured().managedRaster()==null?imageryLayer:null,run.captured().managedRaster()==null?null:previewSourceOwner)):
                    run.captured().managedRaster()!=null?()->ManagedTileRuntime.initializedCoordinator().activeGenerationValue():()->capturedGeneration;
                PreparedModernApply prepared = prepareModernApply(dataSet, run, routeIndex,
                        choice.reviewIdentity(), review[0], () -> {
                            if (!livePreviewSession.isCurrentWindow(previewOwner, dialog, activePreviewDialog,
                                    dialog.isDisplayable())) {
                                throw new IllegalStateException("The preview window no longer owns this attempt");
                            }
                            requireSupportedApplySource(computed.captured(), imageryLayer);
                            requireLiveBCurrent(dataSet, selection, imageryLayer, mapView, slideConfig,
                                    persistedSlideConfig, tracingAtCapture, computed.captured(), previewSourceOwner);
                        },generation,
                        (network, currentPlan) -> {
                            ManagedSourceReceipt managedReceipt = computed.captured().managedRaster() == null ? null
                                : ManagedSourceReceipt.forCurrentPlugin(previewSourceOwner,
                                    computed.captured(), slideConfig.heatmap(), run.options().sourceTier());
                            if(managedReceipt!=null)managedReceipt.requireCurrent();
                            if(detached)return detachedNativeApplyValidator(network,generation,detachedSourceFailureReporter());
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
                    () -> createModernDiagnostics(computed, run, "applied", routeIndex, currentPlan,
                            review[0].confirmed(), true, null, assessment[0], null),
                    () -> {commandAttempted[0]=true;UndoRedoHandler.getInstance().add(command);});
                completed[0] = true;
                try {
                    dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
                } catch (RuntimeException closeFailure) {
                    PluginLog.verbose("Modern preview close after Apply failed: %s",
                            closeFailure.getClass().getSimpleName());
                }
            } catch (RuntimeException exception) {
                LiveBPreviewService.PreviewChoice failedChoice = previewChoices.get(
                        Math.max(0, choices.getSelectedIndex()));
                recordModernDiagnostics(computed, failedChoice.owner(), "failed", failedChoice.localRouteIndex(),
                        null, false, false,
                        diagnosticAttemptIdentity);
                if(commandAttempted[0]||refinement[0]==null) {
                    completed[0]=true;dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
                } else {
                    applying[0]=false;refinement[0].refresh();
                    apply.setEnabled(false);confirm.setEnabled(false);disconnect.setEnabled(false);preciseRerun.setEnabled(false);
                }
                showError(tr("Alignment Apply failed: {0}", exception.getMessage()));
            }
        });
        close.addActionListener(event -> dialog.dispatchEvent(
                new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING)));
        var originalReviewBody=((BorderLayout)panel.getLayout()).getLayoutComponent(BorderLayout.CENTER);
        if(slideConfig.heatmap().trackerMode()==TrackerMode.CORRIDOR_AWARE||slideConfig.heatmap().trackerMode()==TrackerMode.PROBABILISTIC) {
            try {
                refinementRoot[0]=ModernReviewRoot.fromFullChoices(dataSet,selection,slideConfig,previewChoices,previewSourceOwner,
                    ()->requireLiveBSourceOwnerCurrent(dataSet,imageryLayer,slideConfig,persistedSlideConfig,tracingAtCapture,computed.captured(),previewSourceOwner));
                refinement[0]=new LegacyRefinementWorkflow(refinementRoot[0],previewSourceOwner,
                    candidate->{for(int i=0;i<previewChoices.size();i++)if(previewChoices.get(i).candidate()==candidate){choices.setSelectedIndex(i);return;}throw new IllegalArgumentException("Foreign native root choice");},
                    displayed->{
                        plan[0]=displayed.nativeReview().map(RefinementPayload.NativeReview::plan).orElse(null);
                        showNativeRefinementDisplay(selection,displayed,computed.captured().projectionCode());
                    },message->{
                        if(refinement[0]==null)return;
                        boolean nativeStage=refinement[0].nativeSelected(),busy=refinement[0].busy();
                        refinement[0].controls().externalRootSelection(!nativeStage);
                        choices.setVisible(!nativeStage&&candidates.size()>1);
                        if(nativeStage) {
                            quality.setText(message);quality.setCaretPosition(0);
                            if(refinement[0].currentNoChangeRoot().isPresent())confirm.setEnabled(false);
                            else {var actual=refinement[0].currentNative().orElseThrow().plan();confirm.setEnabled(!busy&&actual.validation().disposition()==ValidationReport.Disposition.REVIEW_REQUIRED&&!refinement[0].canApplyNative());}
                            apply.setEnabled(!busy&&!applying[0]&&refinement[0].canApplyNative());
                            disconnect.setEnabled(false);preciseRerun.setVisible(false);
                        } else if(busy){apply.setEnabled(false);confirm.setEnabled(false);disconnect.setEnabled(false);preciseRerun.setEnabled(false);}
                        else if(refinement[0].currentSourceAvailable()){disconnect.setEnabled(true);refresh.run();}
                        else {apply.setEnabled(false);confirm.setEnabled(false);disconnect.setEnabled(false);preciseRerun.setEnabled(false);quality.setText(tr("The source changed. This cached result is available for inspection and diagnostics; run alignment again before applying."));}
                    },nativeVisibleCapturePort(computed.captured(),imageryLayer,mapView,refinementRoot[0]::requireAuthorityCurrent));
                refinement[0].controls().externalRootSelection(true);
                refinement[0].attachCommandContext(detachedCommandContext(dataSet,computed.captured().managedRaster()==null?imageryLayer:null,computed.captured().managedRaster()!=null?previewSourceOwner:null));
                refinement[0].enableNativeRuntimeDiagnostics(candidate->createNativeRootContext(computed,candidate,false),candidate->createNativeRootContext(computed,candidate,true),()->originalNativeWork(computed));
                var reviewBody=new JPanel(new BorderLayout(4,4));reviewBody.add(originalReviewBody,BorderLayout.CENTER);reviewBody.add(refinement[0].controls(),BorderLayout.SOUTH);panel.add(reviewBody,BorderLayout.CENTER);
                dialog.getRootPane().putClientProperty("wayheatmaptracer.refinementOwner",refinement[0]);
                livePreviewSession.replaceWindow(previewOwner,()->{refinement[0].close();dialog.dispose();overlay.hide();});
                refinement[0].attachOwnership(livePreviewSession.workGate(),previewOwner,()->{livePreviewSession.close(previewOwner);dialog.dispose();overlay.hide();});
                attachRefinementLifecycle(dialog,refinement[0]);
                panel.setPreferredSize(new Dimension(700, 600));
                packReviewDialog(dialog);
            } catch(RuntimeException unavailable) {
                if(refinement[0]!=null)refinement[0].close();if(refinementRoot[0]!=null)refinementRoot[0].close();refinement[0]=null;refinementRoot[0]=null;
                panel.add(originalReviewBody,BorderLayout.CENTER);choices.setVisible(candidates.size()>1);
                if(livePreviewSession.isCurrent(previewOwner))livePreviewSession.replaceWindow(previewOwner,dialog::dispose);
                header.add(new JLabel(tr("Optional refinement preparation is unavailable for this capture.")));
                PluginLog.verbose("Native refinement attachment unavailable: %s",unavailable.getClass().getSimpleName());
            }
        }
        try {
            refresh.run();
            dialog.setVisible(true);
            OrdinaryActionBenchmarkObserver.previewReady("single", computed, () -> {
                int displayedIndex = benchmarkDisplayedIndex[0];
                if (displayedIndex < 0 || displayedIndex >= previewChoices.size()
                        || plan[0] != benchmarkDisplayedPlan[0]) {
                    throw new IllegalStateException("Benchmark selected preview changed after display");
                }
                List<BenchmarkPreviewRoute> flattened = previewChoices.stream()
                        .map(choice -> new BenchmarkPreviewRoute(choice.owner().pipeline().routes()
                                .get(choice.localRouteIndex()), choice.owner().evidence().coordinateFrame()))
                        .toList();
                AlignmentEditPlan displayedPlan = benchmarkDisplayedPlan[0];
                List<GeographicPoint> exactPlanGeometry = displayedPlan == null ? null
                        : benchmarkSelectedPlanGeometry(displayedPlan,
                                previewChoices.get(displayedIndex).owner(),
                                previewChoices.get(displayedIndex).localRouteIndex());
                return benchmarkDisplayedGeometry(flattened, displayedIndex,
                        choices.getSelectedIndex(), exactPlanGeometry);
            });
        } catch (RuntimeException exception) {
            dialog.dispose();
            throw exception;
        }
    }

    /** Exact pre-projection final route for one flattened source-owned preview choice. */
    record BenchmarkPreviewRoute(ModernTracePipeline.Route route, LocalMetricFrame frame) {
        BenchmarkPreviewRoute {
            Objects.requireNonNull(route, "final route");
            Objects.requireNonNull(frame, "route coordinate frame");
        }
    }

    /** Supplies the geometry already displayed, without constructing or requiring an Apply plan. */
    static List<GeographicPoint> benchmarkDisplayedGeometry(List<BenchmarkPreviewRoute> flattened,
            int displayedIndex, int selectedIndex, List<GeographicPoint> exactPlanGeometry) {
        if (flattened == null || displayedIndex < 0 || displayedIndex >= flattened.size()
                || selectedIndex != displayedIndex) {
            throw new IllegalStateException("Benchmark selected preview changed after display");
        }
        BenchmarkPreviewRoute source = flattened.get(displayedIndex);
        List<GeographicPoint> geometry = exactPlanGeometry == null
                ? source.route().hypothesis().points().stream()
                        .map(source.frame()::toGeographic).toList()
                : exactPlanGeometry;
        if (geometry.size() < 2) {
            throw new IllegalStateException("Benchmark displayed final geometry is incomplete");
        }
        return geometry;
    }

    /** Observes the selected interval of an exact owner-bound single-way plan, without hiding other edits. */
    public static List<GeographicPoint> benchmarkSelectedPlanGeometry(AlignmentEditPlan plan,
            LiveBPreviewService.Computed owner, int routeIndex) {
        if (plan == null || owner == null || owner.partitioned() || owner.captured() == null
                || owner.request() == null || owner.evidence() == null || owner.pipeline() == null
                || routeIndex < 0 || routeIndex >= owner.pipeline().routes().size()) {
            throw new IllegalStateException("Benchmark selected plan owner is incomplete");
        }
        var request = owner.request();
        var route = owner.pipeline().routes().get(routeIndex);
        var frame = owner.evidence().coordinateFrame();
        if (!plan.before().equals(owner.captured().network()) || !plan.metricFrame().equals(frame)
                || !request.networkSnapshotId().equals(owner.captured().network().snapshotId())
                || !request.networkContentHash().equals(owner.captured().network().canonicalHash())
                || !request.evidenceSnapshotId().equals(owner.evidence().snapshotId())
                || !request.evidenceContentHash().equals(owner.evidence().canonicalHash())
                || !plan.selectedWayKey().equals(request.selectedWayKey())
                || !plan.selectedRange().equals(request.selectedRange())
                || !plan.permissions().equals(request.permissions())
                || !plan.settingsHash().equals(request.settingsHash())
                || !plan.parameterHash().equals(request.parameterHash())
                || !plan.evidenceHash().equals(owner.evidence().canonicalHash())
                || !plan.routeIdentity().equals(route.hypothesis().id())) {
            throw new IllegalStateException("Benchmark selected plan differs from its native owner");
        }
        if (!plan.affectedWayKeys().equals(Set.of(plan.selectedWayKey()))) {
            throw new IllegalStateException("Benchmark selected receipt would hide another affected way");
        }
        DetachedWay before = (DetachedWay) plan.before().primitives().get(plan.selectedWayKey());
        DetachedWay after = (DetachedWay) plan.after().primitives().get(plan.selectedWayKey());
        int first = plan.selectedRange().firstIndex();
        int suffix = before.nodeKeys().size() - plan.selectedRange().lastIndex() - 1;
        int end = after.nodeKeys().size() - suffix;
        if (first < 0 || suffix < 0 || end <= first
                || new HashSet<>(before.nodeKeys()).size() != before.nodeKeys().size()
                || new HashSet<>(after.nodeKeys()).size() != after.nodeKeys().size()
                || !before.nodeKeys().subList(0, first).equals(after.nodeKeys().subList(0, first))
                || !before.nodeKeys().subList(before.nodeKeys().size() - suffix, before.nodeKeys().size())
                    .equals(after.nodeKeys().subList(end, after.nodeKeys().size()))
                || !before.nodeKeys().get(first).equals(after.nodeKeys().get(first))
                || !before.nodeKeys().get(plan.selectedRange().lastIndex()).equals(after.nodeKeys().get(end - 1))) {
            throw new IllegalStateException("Benchmark selected occurrence boundaries or order differ");
        }
        for (int index = 0; index < before.nodeKeys().size(); index++) {
            if (index >= first && index <= plan.selectedRange().lastIndex()) continue;
            PrimitiveKey key = before.nodeKeys().get(index);
            if (!plan.before().primitives().get(key).equals(plan.after().primitives().get(key))) {
                throw new IllegalStateException("Benchmark outside selected occurrence changed");
            }
        }
        List<GeographicPoint> full = plan.finalPreviewWays().get(plan.selectedWayKey());
        if (full == null || full.size() != after.nodeKeys().size()
                || end - first != route.pointIds().size()
                || route.pointIds().size() != route.hypothesis().points().size()) {
            throw new IllegalStateException("Benchmark selected geometry differs from its native route");
        }
        for (int index = 0; index < route.pointIds().size(); index++) {
            FinalRoutePointId id = route.pointIds().get(index);
            var metric = route.assignments().get(id);
            if (metric == null || !metric.equals(route.hypothesis().points().get(index))) {
                throw new IllegalStateException("Benchmark selected native assignment is missing or inconsistent");
            }
            PrimitiveKey key;
            GeographicPoint expected = frame.toGeographic(metric);
            if (id instanceof FinalRoutePointId.ExistingWayNodeOccurrence existing) {
                key = existing.nodeKey();
                if (!existing.wayKey().equals(plan.selectedWayKey())
                        || existing.originalOccurrenceIndex() < first
                        || existing.originalOccurrenceIndex() > plan.selectedRange().lastIndex()
                        || !before.nodeKeys().get(existing.originalOccurrenceIndex()).equals(key)) {
                    throw new IllegalStateException("Benchmark selected native occurrence identity differs");
                }
                DetachedNode original = (DetachedNode) plan.before().primitives().get(key);
                if (metric.equals(frame.toMetric(original.coordinate()))) expected = original.coordinate();
            } else if (id instanceof FinalRoutePointId.GeneratedCandidatePoint generated) {
                key = PrimitiveKey.planned(PrimitiveKey.Type.NODE, generated.originalPointIndex());
            } else {
                throw new IllegalStateException("Benchmark selected native point identity is unsupported");
            }
            if (!after.nodeKeys().get(first + index).equals(key)
                    || !((DetachedNode) plan.after().primitives().get(key)).coordinate().equals(expected)
                    || !full.get(first + index).equals(expected)) {
                throw new IllegalStateException("Benchmark selected plan geometry or assignments differ");
            }
        }
        return List.copyOf(full.subList(first, end));
    }

    /** Routes persisted modern engines through their detached live pipeline. */
    static boolean requiresLiveModernPreview(TrackerMode engine) {
        return Objects.requireNonNull(engine, "engine") != TrackerMode.HYBRID
                && engine.capabilities().requiresEvidenceSnapshot();
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

    /** User-visible per-attempt explanation for saved B settings that cannot be applied. */
    static String modernSettingsNotice(AlignmentConfig requested, AlignmentConfig effective) {
        if (requested == null || effective == null
                || effective.heatmap().trackerMode() != TrackerMode.PROBABILISTIC) {
            return "";
        }
        boolean cleanupSuppressed = !requested.cleanup().isDisabled()
                && effective.cleanup().isDisabled();
        boolean simplifySuppressed = requested.heatmap().simplifyEnabled()
                && !effective.heatmap().simplifyEnabled();
        if (!cleanupSuppressed && !simplifySuppressed) {
            return "";
        }
        return tr("For this Probabilistic B attempt, geometry cleanup and legacy "
                + "simplification are Off because B cannot apply them safely. Saved settings "
                + "are unchanged; use Corridor-aware A with Precise Shape for cleanup.");
    }

    private static JTextArea settingsNoticeComponent(String notice) {
        JTextArea component = new JTextArea(notice, 3, 60);
        component.setEditable(false);
        component.setLineWrap(true);
        component.setWrapStyleWord(true);
        component.setOpaque(false);
        component.getAccessibleContext().setAccessibleName(tr("Effective alignment settings"));
        return component;
    }

    private static String settingsResolutionJson(AlignmentConfig requested,
            AlignmentConfig effective) {
        return "{\"requestedIdentity\":\"" + settingsIdentity(requested)
                + "\",\"effectiveIdentity\":\"" + settingsIdentity(effective)
                + "\",\"requestedEngine\":\"" + requested.heatmap().trackerMode().name()
                + "\",\"effectiveEngine\":\"" + effective.heatmap().trackerMode().name()
                + "\",\"requestedGeometryMode\":\"" + requested.heatmap().alignmentMode().name()
                + "\",\"effectiveGeometryMode\":\"" + effective.heatmap().alignmentMode().name()
                + "\",\"requestedSimplification\":" + requested.heatmap().simplifyEnabled()
                + ",\"effectiveSimplification\":" + effective.heatmap().simplifyEnabled()
                + ",\"requestedCleanupMode\":\"" + requested.cleanup().mode().name()
                + "\",\"effectiveCleanupMode\":\"" + effective.cleanup().mode().name()
                + "\",\"requestedCleanupPreset\":\"" + requested.cleanup().preset().name()
                + "\",\"effectiveCleanupPreset\":\"" + effective.cleanup().preset().name()
                + "\"}";
    }

    private static String settingsIdentity(AlignmentConfig config) {
        try {
            String redacted = config.heatmap().toRedactedJson() + config.cleanup().toRedactedJson()
                    + config.searchHalfWidthMetersOverride();
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(
                    redacted.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for settings identity", exception);
        }
    }

    private static Format15Bundle withSettingsResolution(Format15Bundle bundle,
            LiveBPreviewService.Computed computed) {
        if (computed.settingsResolutionJson() == null) {
            return bundle;
        }
        Map<String, Format15Artifact> artifacts = new LinkedHashMap<>(bundle.artifacts());
        artifacts.put("settings-resolution.json", Format15Artifact.text(
                "settings-resolution.json", computed.settingsResolutionJson()));
        return new Format15Bundle(bundle.buildIdentity(), bundle.sourceIdentityHash(),
                bundle.parameterHash(), artifacts);
    }

    /** Records requested detector outcomes and the exact frozen owner selected for this bundle. */
    private static Format15Bundle createNativeRootContext(LiveBPreviewService.Computed root,Candidate candidate,boolean metadataOnly) {
        var state=candidate.payload().rootReviewState().orElseThrow(()->new IllegalArgumentException("Native root context metadata unavailable"));
        var matching=root.productionRuns().stream().filter(owner->(owner.options().sourceTier()+":"+owner.request().evidenceContentHash()).equals(state.sourceOwnerIdentity())).toList();
        if(matching.size()!=1)throw new IllegalArgumentException("Native root context source owner differs");
        var selected=matching.get(0);
        if(candidate.payload().rootNoChangeReview().isPresent()) {
            var metadata=Format15ProductionBundleFactory.createUnavailableLive(LastSlideDebugBundle.buildIdentity(),"preview-open",selected.captured().managedRaster()==null?"visible-layer":"managed-tiles","original-native-no-change-context",null);
            var artifacts=new LinkedHashMap<>(metadata.artifacts());var status=Format15Artifact.text("original-native-no-change-context.txt","factual-original-no-change-root\nno-edit-plan-exists\nfrozen-native-inputs-unavailable-in-bounded-context\n");artifacts.put(status.name(),status);
            return withSourceChoices(new Format15Bundle(metadata.buildIdentity(),metadata.sourceIdentityHash(),metadata.parameterHash(),artifacts),root,selected);
        }
        if(metadataOnly) {
            var fallback=Format15ProductionBundleFactory.createUnavailableLive(LastSlideDebugBundle.buildIdentity(),"resource-limited",selected.captured().managedRaster()==null?"visible-layer":"managed-tiles","original-native-root-context",null);
            return withSourceChoices(withFailureSummary(fallback,"ORIGINAL_ROOT_DIAGNOSTICS","native-frozen-inputs-unavailable",selected),root,selected);
        }
        if(selected.partitioned())return createIntervalDiagnostics(root,new IntervalPreviewState(root.productionRuns().stream().filter(LiveBPreviewService.Computed::partitioned).toList(),selected,state),IntervalArtifactStatus.PREVIEW);
        return createModernDiagnostics(root,selected,"preview-open",state.fullRouteIndex().orElseThrow(),candidate.payload().nativeReview().orElseThrow().plan(),false,false,null,null,null);
    }
    private static Format15Bundle withSourceChoices(Format15Bundle bundle,
            LiveBPreviewService.Computed root, LiveBPreviewService.Computed selected) {
        if (root == null || selected == null || root.productionRuns().stream()
                .noneMatch(run -> run == selected)) {
            throw new IllegalArgumentException("Diagnostic source owner is not part of this attempt");
        }
        StringBuilder json = new StringBuilder("{\"nativeTier\":\"selected-visible\",\"selectedTier\":\"")
                .append(safeChoiceToken(selected.options().sourceTier())).append("\",\"attempts\":[");
        boolean first = true;
        for (LiveBPreviewService.DetectorAttempt attempt : root.detectorAttempts()) {
            if (!first) json.append(',');
            first = false;
            json.append("{\"mapping\":\"").append(safeChoiceToken(attempt.mapping()))
                    .append("\",\"tier\":\"").append(safeChoiceToken(attempt.sourceTier()))
                    .append("\",\"status\":\"").append(attempt.status().name()).append('"');
            if (attempt.owner() != null) {
                var owner = attempt.owner();
                json.append(",\"evidenceHash\":\"")
                        .append(owner.request().evidenceContentHash())
                        .append("\",\"networkHash\":\"")
                        .append(owner.request().networkContentHash())
                        .append("\",\"intervals\":")
                        .append(owner.partitioned() ? owner.intervalBatch().runs().size() : 0)
                        .append(",\"routes\":")
                        .append(owner.partitioned() ? owner.intervalBatch().runs().stream()
                                .mapToInt(run -> run.routes().size()).sum()
                                : owner.pipeline().routes().size());
                appendOriginalSourceWork(json,owner);
            }
            json.append('}');
        }
        json.append(']');
        var sources = root.captured().sourceRasters();
        if (sources != null) {
            json.append(",\"aggregateAvailability\":\"")
                    .append(sources.aggregateAvailability().name())
                    .append("\",\"liveCompleteAggregateProof\":")
                    .append(sources.provenCompleteAggregate(root.captured().sourceOwner()));
        }
        json.append("}\n");
        Map<String, Format15Artifact> artifacts = new LinkedHashMap<>(bundle.artifacts());
        artifacts.put("source-choices.json", Format15Artifact.text("source-choices.json", json.toString()));
        return new Format15Bundle(bundle.buildIdentity(), bundle.sourceIdentityHash(),
                bundle.parameterHash(), artifacts);
    }
    private static Format15Artifact originalNativeWork(LiveBPreviewService.Computed root) {
        var json=new StringBuilder("{\"schema\":1,\"scope\":\"original-production-attempt\",\"sources\":[");
        boolean first=true;
        for(var owner:root.productionRuns()) {
            if(!first)json.append(',');first=false;
            json.append("{\"sourceTier\":\"").append(safeChoiceToken(owner.options().sourceTier()))
                .append("\",\"evidenceHash\":\"").append(owner.request().evidenceContentHash()).append('"');
            appendOriginalSourceWork(json,owner);json.append('}');
            if(json.length()>65536)throw new IllegalStateException("Original production metadata exceeds bounded fallback");
        }
        return Format15Artifact.text("original-native-work.json",json.append("]}\n").toString());
    }
    private static void appendOriginalSourceWork(StringBuilder json,LiveBPreviewService.Computed owner) {
        json.append(",\"work\":{\"schema\":1,\"scope\":\"").append(owner.partitioned()?"INTERVAL_SOURCE_BATCH":"FULL_SELECTED_SOURCE_RUN")
            .append("\",\"acquisitionKind\":\"").append(owner.captured().managedRaster()==null?"VISIBLE":"MANAGED")
            .append("\",\"engine\":\"").append(owner.request().engine().name()).append("\",\"runs\":[");
        if(owner.partitioned()) {
            for(int i=0;i<owner.intervalBatch().runs().size();i++) {
                if(i>0)json.append(',');var run=owner.intervalBatch().runs().get(i);
                json.append("{\"intervalIndex\":").append(i).append(",\"editFirst\":").append(run.interval().range().firstIndex()).append(",\"editLast\":").append(run.interval().range().lastIndex()).append(',');
                appendOriginalRunWork(json,run.request(),run.result(),Optional.of(run.usage()),run.corridorReceipt());json.append('}');
            }
        } else {json.append('{');appendOriginalRunWork(json,owner.request(),owner.pipeline(),Optional.empty(),owner.corridorReceipt());json.append('}');}
        json.append("]}");
    }
    private static void appendOriginalRunWork(StringBuilder json,org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest request,ModernTracePipeline.Result result,
            Optional<org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.TraceWorkUsage> charged,Optional<org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CorridorExecutionReceipt> corridor) {
        var inference=result.inference();
        json.append("\"requestHash\":\"").append(FrozenReplayCodec.canonicalRequestHash(request)).append("\",\"parameterHash\":\"").append(request.parameterHash()).append("\",\"corridorPolicy\":").append(request.corridorInput().map(input->"\""+input.executionPolicy().name()+"\"").orElse("null"))
            .append(",\"traceFirst\":").append(request.selectedRange().firstIndex()).append(",\"traceLast\":").append(request.selectedRange().lastIndex())
            .append(",\"requestedProfileCount\":").append(request.profileChainage().cumulativeGroundMeters().size()).append(",\"status\":\"").append(inference.status().name())
            .append("\",\"evaluatedStates\":").append(inference.evaluatedStates()).append(",\"evaluatedTransitions\":").append(inference.evaluatedTransitions())
            .append(",\"rawHypotheses\":").append(inference.hypotheses().size()).append(",\"finalRoutes\":").append(result.routes().size()).append(",\"intervalChargedWork\":");
        if(charged.isEmpty())json.append("null");else {var work=charged.orElseThrow();json.append("{\"pairVisits\":").append(work.pairVisits()).append(",\"transitions\":").append(work.transitions()).append(",\"rawAlternatives\":").append(work.rawAlternatives()).append(",\"distinctAlternatives\":").append(work.distinctAlternatives()).append('}');}
        json.append(",\"scoredExecutionWork\":");
        if(inference.workAccounting().isEmpty())json.append("null");else {
            var work=inference.workAccounting().orElseThrow();json.append("{\"otherEngineTransitions\":").append(work.otherEngineTransitions()).append(",\"bStages\":[");
            for(int i=0;i<work.bStages().size();i++) {if(i>0)json.append(',');var stage=work.bStages().get(i);
                json.append("{\"executionPolicy\":\"").append(safeChoiceToken(stage.executionPolicy())).append("\",\"forwardBackwardScores\":").append(stage.forwardBackwardScores()).append(",\"kBestScores\":").append(stage.kBestScores()).append(",\"logicalKBestExtensions\":").append(stage.logicalKBestExtensions()).append(",\"refusedScoreAttempts\":").append(stage.refusedScoreAttempts()).append('}');}
            json.append("]}");
        }
        json.append(",\"corridorWork\":");
        if(corridor.isEmpty())json.append("null");else {
            var receipt=corridor.orElseThrow();var work=receipt.totals();
            json.append("{\"phase\":\"").append(receipt.phase().name()).append("\",\"originalStates\":").append(work.originalStates()).append(",\"retainedUnaryEvaluations\":").append(work.retainedUnaryEvaluations()).append(",\"bridgeUnaryEvaluations\":").append(work.bridgeUnaryEvaluations())
                .append(",\"admissionContacts\":").append(work.admissionContacts()).append(",\"admissionPairs\":").append(work.admissionPairs()).append(",\"adjacentObjectiveEntries\":").append(work.adjacentObjectiveEntries()).append(",\"solverStarts\":").append(work.solverStarts())
                .append(",\"attemptedTransitions\":").append(work.attemptedTransitions()).append(",\"testedTriples\":").append(work.testedTriples()).append(",\"rejectedTriples\":").append(work.rejectedTriples()).append(",\"scoredTransitions\":").append(work.scoredTransitions())
                .append(",\"gapAdmissionUnits\":").append(work.gapAdmissionUnits().isPresent()?Long.toString(work.gapAdmissionUnits().getAsLong()):"null").append(",\"observedAdmissionUnits\":").append(work.observedAdmissionUnits().isPresent()?Long.toString(work.observedAdmissionUnits().getAsLong()):"null").append('}');
        }
    }

    private static String safeChoiceToken(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,80}")) {
            throw new IllegalArgumentException("Detector choice token is unsafe");
        }
        return value;
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
        if (captured.intensityMode() != heatmap.intensitySamplingMode()
                || heatmap.intensitySamplingMode() == IntensitySamplingMode.COLOR_MAPPING
                        && (heatmap.multiColorDetection() || heatmap.aggregateAllColorSchemes())
                        && (captured.sourceRasters() == null
                                || !captured.sourceRasters().plan()
                                        .equals(org.openstreetmap.josm.plugins.wayheatmaptracer.service
                                                .AlignmentTileSourcePlan.from(heatmap))
                                || heatmap.multiColorDetection()
                                        && captured.alternativeMappings().isEmpty())) {
            return ModernApplyPreflight.SOURCE_LINEAGE_UNAVAILABLE;
        }
        boolean engineSupported = captured.engine().capabilities().requiresEvidenceSnapshot();
        boolean geometrySupported = captured.geometryMode() == heatmap.alignmentMode()
                && (heatmap.alignmentMode() == AlignmentMode.MOVE_EXISTING_NODES
                    || !heatmap.simplifyEnabled() || captured.engine() == TrackerMode.LEGACY_THEN_B);
        return engineSupported && geometrySupported
                ? ModernApplyPreflight.READY : ModernApplyPreflight.CONFIGURATION_UNSUPPORTED;
    }

    static boolean supportsModernVisibleApply(LiveBPreviewService.Captured captured,
            AlignmentConfig config) {
        return modernApplyPreflight(captured, config) == ModernApplyPreflight.READY;
    }

    static String modernPreviewSummary(String engine, String source, String disposition,
            Integer affectedWayCount, List<String> reasons, boolean confirmed,
            String applyAvailability) {
        String reasonText = FindingSummary.summarize(reasons == null ? List.of() : reasons);
        String affected = affectedWayCount == null
                ? tr("unavailable (no applicable plan)") : affectedWayCount.toString();
        String confirmation = affectedWayCount == null
                ? tr("unavailable (no exact plan)")
                : disposition.equals(ValidationReport.Disposition.APPLICABLE.name())
                    ? tr("not required")
                    : disposition.equals(ValidationReport.Disposition.HARD_BLOCKED.name())
                        ? tr("unavailable (plan is blocked)")
                        : confirmed ? tr("confirmed") : tr("not confirmed");
        return tr("Engine: {0}\nSource: {1}\nDisposition: {2}\nAffected ways: {3}"
                        + "\nReasons: {4}\nConfirmation: {5}\nApply: {6}",
                engine, source, disposition, affected, reasonText,
                confirmation, applyAvailability);
    }

    /** Packs review controls once, bounded by the usable monitor area. */
    private static void packReviewDialog(JDialog dialog) {
        dialog.pack();
        java.awt.GraphicsConfiguration configuration = dialog.getGraphicsConfiguration();
        java.awt.Rectangle bounds = configuration.getBounds();
        java.awt.Insets insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        int width = Math.max(1, bounds.width - insets.left - insets.right);
        int height = Math.max(1, bounds.height - insets.top - insets.bottom);
        dialog.setSize(Math.min(dialog.getWidth(), width), Math.min(dialog.getHeight(), height));
    }

    /** Builds a fixed-height scrollable review body with controls outside the scroll region. */
    static JPanel modernReviewContent(JTextArea summary, JComponent footer) {
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(footer, "footer");
        JScrollPane scroll = new JScrollPane(summary);
        scroll.setPreferredSize(new Dimension(640, 170));
        JPanel content = new JPanel(new BorderLayout(0, 6));
        content.add(scroll, BorderLayout.CENTER);
        content.add(footer, BorderLayout.SOUTH);
        content.setPreferredSize(new Dimension(680, 235));
        return content;
    }

    /** Applies only when an exact safe plan exists and its separate review state permits it. */
    static boolean modernApplyEnabled(boolean safePlanAvailable, PreviewReviewState review,
            boolean applying, boolean stale) {
        return safePlanAvailable && review != null && review.canApply() && !applying && !stale;
    }

    /** Builds the one-shot geometry-mode copy while leaving caller preferences untouched. */
    static AlignmentConfig oneShotPreciseConfig(AlignmentConfig saved) {
        Objects.requireNonNull(saved, "saved");
        return new AlignmentConfig(saved.heatmap().withAlignmentMode(AlignmentMode.PRECISE_SHAPE),
                saved.cleanup(), saved.searchHalfWidthMetersOverride());
    }

    /** The same requested/effective route used by the one-shot recovery button. */
    static <T> OrdinaryActionRouting<T> resolvePreciseRerun(TracingSettings tracing,
            AlignmentConfig saved, Supplier<T> requiredVisibleSource,
            Supplier<T> legacyVisibleSource) {
        AlignmentConfig normalized = new AlignmentConfig(
                saved.heatmap().withTrackerMode(tracing.engine()), saved.cleanup(),
                saved.searchHalfWidthMetersOverride());
        return resolveOrdinaryAction(tracing, oneShotPreciseConfig(normalized),
                requiredVisibleSource, legacyVisibleSource);
    }

    static <T> OrdinaryActionRouting<T> resolvePreciseRerun(
            ModernSingleWayEditPlanAdapter.ApplyAvailability availability,
            TracingSettings tracing, AlignmentConfig saved,
            Supplier<T> requiredVisibleSource, Supplier<T> legacyVisibleSource) {
        if (availability != ModernSingleWayEditPlanAdapter.ApplyAvailability.PRECISE_SHAPE_REQUIRED) {
            throw new IllegalStateException("Precise Shape rerun requires an exact typed recovery reason");
        }
        return resolvePreciseRerun(tracing, saved, requiredVisibleSource, legacyVisibleSource);
    }

    private static String modernApplyAvailabilityMessage(
            ModernSingleWayEditPlanAdapter.Assessment assessment, ModernApplyPreflight preflight) {
        if (preflight != ModernApplyPreflight.READY) {
            return tr("Apply unavailable: {0}", modernApplyPreflightMessage(preflight));
        }
        if (assessment == null) {
            return tr("Apply unavailable: exact plan assessment is unavailable");
        }
        if (assessment.applyAvailable()) {
            return assessment.plan().orElseThrow().validation().reviewRequired()
                    ? tr("available after explicit confirmation") : tr("available");
        }
        if (assessment.availability() == ModernSingleWayEditPlanAdapter.ApplyAvailability.NO_CHANGE) {
            return tr("Already aligned: no edit command or history entry will be created");
        }
        return tr("Apply unavailable: {0}", assessment.detail());
    }

    /** Binds a no-write assessment to the same frozen and freshly recaptured network. */
    static void requireNoChangeCurrent(ModernSingleWayEditPlanAdapter.Assessment assessment,
            LiveBPreviewService.Computed computed, NetworkSnapshot current) {
        if (assessment == null || assessment.availability()
                    != ModernSingleWayEditPlanAdapter.ApplyAvailability.NO_CHANGE
                || computed == null || computed.captured() == null
                || computed.captured().specification() == null
                || computed.captured().network() == null || current == null) {
            throw new IllegalStateException("The no-change preview has no current source proof");
        }
        ModernSingleWayEditPlanAdapter.NoChangeWatch watch =
                assessment.noChangeWatch().orElseThrow(() -> new IllegalStateException(
                        "The no-change preview has no frozen network watch"));
        NetworkSnapshot frozen = computed.captured().network();
        if (!watch.selectedWayKey().equals(computed.request().selectedWayKey())
                || !watch.selectedRange().equals(computed.request().selectedRange())
                || !watch.selectedWayKey().equals(computed.captured().specification().selectedWayKey())
                || !watch.selectedRange().equals(computed.captured().specification().selectedRange())
                || !watch.snapshotId().equals(frozen.snapshotId())
                || !watch.datasetIdentity().equals(frozen.datasetIdentity())
                || watch.sourceGeneration() != frozen.sourceGeneration()
                || !watch.networkContentHash().equals(frozen.canonicalHash())
                || !watch.snapshotId().equals(current.snapshotId())
                || !watch.datasetIdentity().equals(current.datasetIdentity())
                || watch.sourceGeneration() != current.sourceGeneration()
                || !watch.networkContentHash().equals(current.canonicalHash())) {
            throw new IllegalStateException("The no-change preview became stale after capture");
        }
    }

    private static String preciseRerunUnsupportedReason(SelectionContext selection,
            TracingSettings tracing, AlignmentConfig persistedConfig, ImageryLayer imageryLayer) {
        try {
            OrdinaryActionRouting<ImageryLayer> route = resolvePreciseRerun(
                    tracing, persistedConfig, () -> imageryLayer, () -> imageryLayer);
            if (route.route().pipeline() == OrdinaryPipeline.LEGACY_COMPATIBILITY) {
                return tr("The saved engine has no Precise Shape live-preview route");
            }
            AlignmentConfig routed = route.route().invocation().config();
            if (route.route().pipeline() == OrdinaryPipeline.MODERN_VISIBLE) {
                LiveBPreviewService.requireSupported(selection,
                        ProjectionRegistry.getProjection().toCode(), routed, true);
            } else {
                if (!routed.heatmap().hasManagedAccessValues()) {
                    return tr("The configured managed source is unavailable");
                }
                if (!routed.heatmap().trackerMode().capabilities().supportsManagedSource()) {
                    return tr("The selected engine cannot use the managed source");
                }
                if (routed.heatmap().simplifyEnabled()
                        && routed.heatmap().trackerMode() != TrackerMode.LEGACY_THEN_B) {
                    return tr("Precise Shape requires simplification Off for this engine");
                }
                if (routed.heatmap().parallelWayAwareness()) {
                    return tr("Managed Precise Shape preview cannot use nearby-way awareness");
                }
                if (routed.searchHalfWidthMetersOverride().isPresent()) {
                    return tr("Managed Precise Shape preview cannot use a temporary search-width override");
                }
            }
            return null;
        } catch (RuntimeException unsupported) {
            return unsupported.getMessage() == null
                    ? tr("The source does not support this Precise Shape rerun")
                    : unsupported.getMessage();
        }
    }

    private AlignmentResult liveBDisplayResult(SelectionContext selection,
            LiveBPreviewService.Computed computed, List<CenterlineCandidate> candidates,
            CenterlineCandidate selected, AlignmentEditPlan exactPlan) {
        return liveBDisplayResult(selection,computed,candidates,selected,exactPlan,Optional.empty());
    }
    private AlignmentResult liveBDisplayResult(SelectionContext selection,
            LiveBPreviewService.Computed computed,List<CenterlineCandidate> candidates,CenterlineCandidate selected,
            AlignmentEditPlan exactPlan,Optional<ModernSingleWayEditPlanAdapter.NoChangeProof> noChange) {
        List<EastNorth> source = computed.captured().sourceGeographic().stream()
                .map(point -> ProjectionRegistry.getProjection().latlon2eastNorth(
                        new LatLon(point.latitudeDegrees(), point.longitudeDegrees())))
                .toList();
        List<EastNorth> preview = exactPlan == null ? noChange.map(proof->proof.finalSelection().selectedGeometry().stream()
                .map(point->ProjectionRegistry.getProjection().latlon2eastNorth(new LatLon(point.latitudeDegrees(),point.longitudeDegrees()))).toList()).orElseGet(selected::finalPreviewPoints)
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
        NetworkSnapshotCapture.CapturedSnapshot receipt = computed.captured().originalReceipt().orElseGet(()->NetworkSnapshotCapture.captureBound(
                dataSet, computed.captured().specification()));
        LiveNetworkSnapshotValidator network = new LiveNetworkSnapshotValidator(receipt, plan, sourceGeneration);
        LockedApplyValidator validator = Objects.requireNonNull(validatorFactory.apply(network, plan),
                "locked validator");
        return new PreparedModernApply(plan, new ApplyAlignmentEditPlanCommand(dataSet, plan, validator,
                tr("Apply modern alignment")));
    }

    record PreparedModernApply(AlignmentEditPlan plan, ApplyAlignmentEditPlanCommand command) { }

    private String liveBQualitySummary(LiveBPreviewService.Computed computed, int index) {
        FinalGeometryEvaluator.Result quality = computed.pipeline().routes().get(index).quality();
        String findings = FindingSummary.summarize(quality.findings().stream()
                .map(finding -> finding.code().name() + " (" + finding.severity().name() + ")")
                .toList());
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

    private OrdinaryVisibleRasterCapture<ImageryLayer> visibleRasterCapture(SelectionContext selection,MapView view,String identity) {
        return new OrdinaryVisibleRasterCapture<>() {
            private AlignmentService.VisibleInputReceipt receipt;
            public LiveBPreviewService.VisibleRaster capture(ImageryLayer layer,ModernAlignmentInvocation invocation,RecoveryPermissions permissions){
                if(layer instanceof ManagedHeatmapLayer managed){var input=alignmentService.captureAuthoritativeVisibleInput(selection,managed,view,invocation.config(),identity,permissions);receipt=input.receipt();return input.raster();}
                receipt=null;return alignmentService.captureLiveBVisibleRaster(selection,layer,view,invocation.config(),identity,permissions);
            }
            public Optional<AlignmentService.VisibleInputReceipt> inputReceipt(LiveBPreviewService.VisibleRaster raster){if(receipt!=null)receipt.requireRaster(raster);return Optional.ofNullable(receipt);}
        };
    }
    private interface DetachedNativeGeneration extends org.openstreetmap.josm.plugins.wayheatmaptracer.util.PreparedCommandSource {
        default void withSource(Runnable transaction){getAsLong();transaction.run();}
    }
    private record ManagedCommandGeneration(TileFetchCoordinator coordinator,ManagedTileGeneration generation,long sourceGeneration,Runnable context) implements DetachedNativeGeneration {
        public long getAsLong(){context.run();if(!coordinator.isActiveGeneration(generation))throw new IllegalStateException("The original managed command source changed");return sourceGeneration;}
    }
    private record VisibleCommandGeneration(VisibleSourceEpoch.Receipt receipt,long sourceGeneration,Runnable context,AlignmentService.VisibleCommandAuthority authority) implements DetachedNativeGeneration {
        public long getAsLong(){context.run();if(authority!=null)return authority.getAsLong();receipt.owner().requireCurrent(receipt);return sourceGeneration;}
        public Runnable prepareExecution(boolean redo){context.run();if(authority==null){getAsLong();return ()->getAsLong();}var prepared=authority.prepareExecution(redo);return ()->{context.run();prepared.run();};}
        public void withSource(Runnable transaction){synchronized(receipt.owner()){getAsLong();transaction.run();}}
    }
    /** Creates a command-only generation witness without retaining the capture or its images. */
    static LongSupplier detachedNativeCommandGeneration(LiveBPreviewService.Captured captured,TileFetchCoordinator coordinator,Runnable context) {
        Objects.requireNonNull(context);long value=captured.network().sourceGeneration();
        DetachedNativeGeneration guard;
        if(captured.managedRaster()!=null) {
            if(coordinator==null||captured.sourceOwner()!=null&&captured.sourceOwner()!=coordinator)throw new IllegalStateException("Original managed command owner differs");
            guard=new ManagedCommandGeneration(coordinator,captured.managedRaster().generation(),value,context);
        } else guard=new VisibleCommandGeneration(Objects.requireNonNull(captured.raster().sourceReceipt(),"Original visible command receipt unavailable"),value,context,captured.visibleInputReceipt().map(proof->proof.commandAuthority(value)).orElse(null));
        guard.getAsLong();return guard;
    }
    private record DetachedNativeApplyValidator(LiveNetworkSnapshotValidator network,DetachedNativeGeneration source,Consumer<String> reporter) implements LockedApplyValidator {
        public String datasetIdentity(){return network.datasetIdentity();}
        public boolean returnsNormallyAfterCompletedTransaction(){return true;}
        public Runnable prepareExecution(DataSet data,boolean redo){return source.prepareExecution(redo);}
        public void executeWithPreparedSource(Runnable transaction){source.withSource(transaction);}
        public void validateLocked(DataSet data,AlignmentEditPlan plan,boolean requireSource){network.validateLocked(data,plan,true);}
        public void validateUndoLocked(DataSet data,AlignmentEditPlan plan){network.validateUndoLocked(data,plan);}
        public void reportRejectedRedo(RuntimeException failure){reporter.accept("The captured source or network changed; recompute alignment before applying.");}
        public void reportRejectedUndo(RuntimeException failure){reporter.accept("Alignment Undo was refused because the OSM context changed.");}
    }
    static LockedApplyValidator detachedNativeApplyValidator(LiveNetworkSnapshotValidator network,LongSupplier generation,Consumer<String> reporter) {
        if(!(generation instanceof DetachedNativeGeneration source))throw new IllegalArgumentException("Detached native source witness required");
        return new DetachedNativeApplyValidator(network,source,Objects.requireNonNull(reporter));
    }
    private static Consumer<String> detachedSourceFailureReporter(){return message->JOptionPane.showMessageDialog(MainApplication.getMainFrame(),message,tr("WayHeatmapTracer"),JOptionPane.WARNING_MESSAGE);}

    /** Small detached command guard: no capture, candidate, history, dialog or credential strings. */
    private record DetachedCommandContext(DataSet data,ImageryLayer layer,TileFetchCoordinator coordinator,
            org.openstreetmap.josm.spi.preferences.IPreferences preferences,java.util.concurrent.atomic.AtomicLong epoch,long capturedEpoch,
            String settings,String tracing,String projection) implements Runnable {
        public void run() {
            if(preferences!=org.openstreetmap.josm.spi.preferences.Config.getPref()
                    ||capturedEpoch==Long.MAX_VALUE||epoch.get()!=capturedEpoch
                    ||!settings.equals(commandSettingsIdentity())||!tracing.equals(PluginPreferences.loadTracingSettings().toString())
                    ||!projection.equals(ProjectionRegistry.getProjection().toCode())
                    ||data!=null&&MainApplication.getLayerManager().getEditDataSet()!=data
                    ||coordinator!=null&&ManagedTileRuntime.initializedCoordinator()!=coordinator
                    ||layer!=null&&(!layer.isVisible()||HeatmapLayerResolver.resolveOptional().orElse(null)!=layer))
                throw new IllegalStateException("The command dataset, source or settings changed");
        }
    }
    private record PreferenceEpoch(java.lang.ref.WeakReference<org.openstreetmap.josm.spi.preferences.IPreferences> preferences,
            java.util.concurrent.atomic.AtomicLong stamp) { }
    private static final List<PreferenceEpoch> COMMAND_PREFERENCE_EPOCHS=new ArrayList<>();
    private static synchronized java.util.concurrent.atomic.AtomicLong commandPreferenceEpoch(org.openstreetmap.josm.spi.preferences.IPreferences preferences) {
        COMMAND_PREFERENCE_EPOCHS.removeIf(value->value.preferences().get()==null);
        for(var value:COMMAND_PREFERENCE_EPOCHS)if(value.preferences().get()==preferences)return value.stamp();
        var stamp=new java.util.concurrent.atomic.AtomicLong();
        preferences.addPreferenceChangeListener(event->{
            if(LegacyReviewOwner.isMaterialSetting(event.getKey()))stamp.updateAndGet(value->value==Long.MAX_VALUE?value:value+1);
        });
        COMMAND_PREFERENCE_EPOCHS.add(new PreferenceEpoch(new java.lang.ref.WeakReference<>(preferences),stamp));
        return stamp;
    }
    static Runnable detachedCommandContext(DataSet data,ImageryLayer layer,TileFetchCoordinator coordinator) {
        var preferences=org.openstreetmap.josm.spi.preferences.Config.getPref();
        var epoch=commandPreferenceEpoch(preferences);
        return new DetachedCommandContext(data,layer,coordinator,preferences,epoch,epoch.get(),commandSettingsIdentity(),PluginPreferences.loadTracingSettings().toString(),ProjectionRegistry.getProjection().toCode());
    }
    private static String commandSettingsIdentity() {
        var config=PluginPreferences.load();
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            for(String value:java.util.Arrays.asList(config.toRedactedJson(),PluginPreferences.loadGeometryCleanup().toRedactedJson(),config.keyPairId(),config.policy(),config.signature(),config.sessionToken())) {
                var bytes=Objects.toString(value,"").getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        }catch(java.security.NoSuchAlgorithmException unavailable){throw new IllegalStateException("Required command digest unavailable");}
    }
    private void requireLiveBSourceOwnerCurrent(DataSet dataSet, ImageryLayer imageryLayer,
            AlignmentConfig slideConfig, AlignmentConfig persistedSlideConfig,
            TracingSettings tracingAtCapture, LiveBPreviewService.Captured captured,
            TileFetchCoordinator sourceOwner) {
        captured.visibleInputReceipt().ifPresent(AlignmentService.VisibleInputReceipt::requireCurrent);
        AlignmentConfig currentPersisted = new AlignmentConfig(PluginPreferences.load(),
                PluginPreferences.loadGeometryCleanup());
        TracingSettings currentTracing = PluginPreferences.loadTracingSettings();
        boolean managed = captured.managedRaster() != null;
        if (MainApplication.getLayerManager().getEditDataSet() != dataSet
                || !matchesLivePreviewSettings(persistedSlideConfig, slideConfig, currentPersisted,
                        tracingAtCapture, currentTracing, forcedLivePreviewEngine)
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
            if(captured.visibleInputReceipt().isPresent()){var proof=captured.visibleInputReceipt().orElseThrow();proof.requireRaster(captured.raster());proof.requireOwner(layer);return;}
            throw new IllegalStateException("The managed rendered source cannot verify unchanged pixels; "
                    + "use direct managed tiles for Apply.");
        }
    }

    /** Interval Apply currently requires a plugin-owned direct tile receipt and locked validator. */
    static boolean intervalApplySourceAvailable(LiveBPreviewService.Captured captured) {
        if(captured==null)return false;
        if(captured.managedRaster()!=null)return true;
        try{captured.visibleInputReceipt().orElseThrow().requireRaster(captured.raster());return true;}
        catch(RuntimeException unavailable){return false;}
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

    /** Capture failures have no frozen input: retain a safe cause without inventing replay authority. */
    static void recordCaptureFailure(Exception failure, String sourceLineage, String attemptIdentity) {
        var manual = failure instanceof LiveBPreviewService.ManualJunctionCaptureException junction
                ? junction.reason() : null;
        var facts = FailureFacts.capture(failure);
        String code = captureFailureCode(failure, facts);
        Format15Bundle base = Format15ProductionBundleFactory.createUnavailableLive(
                LastSlideDebugBundle.buildIdentity(), manual != null ? "blocked" : facts.resourceLimited() ? "resource-limited" : "failed",
                sourceLineage, attemptIdentity, manual);
        DiagnosticsRegistry.setLastModernBundle(withFailureSummary(base, "CAPTURE", code, null, facts, null));
    }

    static String captureFailureMessage(Exception failure) {
        String code = captureFailureCode(failure, FailureFacts.capture(failure));
        String instruction = code.equals("INCOMPLETE_RELATION_MEMBERS")
                ? tr("Required relation members are not downloaded. Download the required relation members, then run alignment again.")
                : failure instanceof LiveBPreviewService.ManualJunctionCaptureException manual
                    ? manual.reason().name() + ". " + tr("Adjust this junction manually, then run alignment again.")
                    : tr("Alignment could not start ({0}).", code);
        return instruction + " " + tr("No changes were applied. Use Export Last Alignment Diagnostics for details.");
    }

    private static String captureFailureCode(Exception failure, FailureFacts facts) {
        if (facts.resourceLimited()) return "RESOURCE_LIMIT";
        String code = workerFailureCode(failure.getClass().getSimpleName() + ": " + failure.getMessage());
        return code.equals("RESOURCE_LIMIT") ? "ALIGNMENT_WORKER_FAILURE" : code;
    }

    /** Values are fixed local codes/enums and numeric work counters, never exception or source text. */
    private static Format15Bundle withFailureSummary(Format15Bundle base, String phase, String code,
            LiveBPreviewService.Computed computed) {
        return withFailureSummary(base,phase,code,computed,null,null);
    }
    private static Format15Bundle withFailureSummary(Format15Bundle base,String phase,String code,
            LiveBPreviewService.Computed computed,FailureFacts failure,String boundary) {
        String scope = computed == null ? "ATTEMPT" : computed.partitioned()
                ? "INTERVAL_SOURCE_BATCH" : "SOURCE_RUN";
        String work;
        if (computed == null) {
            work = "";
        } else if (computed.partitioned()) {
            // No fictional full-way inference: preserve the actual interval work and status counts.
            Map<TraceHypothesisSet.Status, Integer> statuses = new java.util.EnumMap<>(TraceHypothesisSet.Status.class);
            for (var run : computed.intervalBatch().runs()) {
                statuses.merge(run.result().inference().status(), 1, Integer::sum);
            }
            String statusCounts = statuses.entrySet().stream()
                    .map(entry -> "\"" + entry.getKey().name() + "\":" + entry.getValue())
                    .collect(java.util.stream.Collectors.joining(","));
            work = ",\"intervalRuns\":" + computed.intervalBatch().runs().size()
                    + ",\"intervalTransitions\":" + computed.intervalBatch().totalUsage().transitions()
                    + ",\"intervalPairVisits\":" + computed.intervalBatch().totalUsage().pairVisits()
                    + ",\"intervalStatusCounts\":{" + statusCounts + "}";
        } else {
            work = ",\"inferenceStatus\":\""
                + computed.pipeline().inference().status().name() + "\",\"evaluatedStates\":"
                + computed.pipeline().inference().evaluatedStates() + ",\"evaluatedTransitions\":"
                + computed.pipeline().inference().evaluatedTransitions() + ",\"finalRoutes\":"
                + computed.pipeline().routes().size();
        }
        Map<String, Format15Artifact> artifacts = new LinkedHashMap<>(base.artifacts());
        artifacts.put("failure-summary.json", Format15Artifact.text("failure-summary.json",
                "{\"phase\":\"" + phase + "\",\"failureCode\":\"" + code
                + "\",\"scope\":\"" + scope + "\",\"applyPlanIncluded\":false" + work
                + (boundary == null ? "" : ",\"boundary\":\"" + boundary + "\"")
                + ("DIAGNOSTIC_EXPORT".equals(phase) ? ",\"impact\":\"EXPORT_ONLY\"" : "")
                + (failure == null ? "" : failure.jsonMembers()) + "}\n"));
        return new Format15Bundle(base.buildIdentity(), base.sourceIdentityHash(), base.parameterHash(), artifacts);
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

    /** The common ordinary/override entry revokes late sidecars even when capture fails before open. */
    static String beginDiagnosticAttempt(PreviewSessionController<?> session) {
        synchronized (session) {
            session.revokeCancellationDiagnostics();
            return beginDiagnosticAttempt();
        }
    }

    /** Builds bounded evidence outside the session monitor, then atomically consumes its owner token. */
    static boolean publishFinalizationCancellationIfLatest(
            PreviewSessionController<LiveBPreviewService.Computed> session,
            PreviewSessionController.Owner owner, AlignmentJob.CancellationDiagnosticEvent event,
            String sourceLineage, String diagnosticAttemptIdentity) {
        Format15Bundle base = Format15ProductionBundleFactory.createUnavailableLive(
                LastSlideDebugBundle.buildIdentity(), "cancelled", sourceLineage, diagnosticAttemptIdentity);
        Format15Bundle evidence = org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15
                .CorridorExecutionArtifacts.attachCancellation(base, event.summary());
        return session.publishCancellationDiagnosticsIfLatest(owner, event, diagnosticAttemptIdentity,
                () -> DiagnosticsRegistry.setLastModernBundle(evidence));
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

    /** Publishes only the current terminal worker's safe, exportable failure; never a preview or Apply plan. */
    static boolean publishFailedWorkerIfCurrent(PreviewSessionController<LiveBPreviewService.Computed> session,
            PreviewSessionController.Owner owner, AlignmentJob.Attempt<LiveBPreviewService.Computed> failed,
            String sourceLineage, String attemptIdentity, Consumer<String> showFailure) {
        synchronized (session) {
            if (failed == null || failed.state() != AlignmentJob.State.FAILED
                    || session.currentAttempt() != failed || !session.isCurrent(owner)) return false;
            var failure = failed.workerFailure();
            boolean resourceLimited = failure != null && failure.facts().resourceLimited();
            String reasonCode = workerFailureCode(failed.failureReason());
            String code = resourceLimited ? "RESOURCE_LIMIT"
                    : reasonCode.equals("RESOURCE_LIMIT") ? "ALIGNMENT_WORKER_FAILURE" : reasonCode;
            var manual = manualCaptureReason(failed.failureReason());
            String status = manual != null ? "blocked" : resourceLimited ? "resource-limited" : "failed";
            return closeAndPublishIfCurrent(session, owner, () -> {
                Format15Bundle base = Format15ProductionBundleFactory.createUnavailableLive(
                        LastSlideDebugBundle.buildIdentity(), status, sourceLineage, attemptIdentity, manual);
                Map<String, org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15Artifact>
                        artifacts = new LinkedHashMap<>(base.artifacts());
                var snapshot = failed.snapshot();
                artifacts.put("worker-failure.json", org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15
                        .Format15Artifact.text("worker-failure.json", "{\"failureCode\":\"" + code
                        + "\",\"applyAvailable\":false,\"snapshotIdentityHash\":\"" + safeLayerNameIdentity(snapshot.attemptId())
                        + "\",\"sourceIdentityHash\":\"" + safeLayerNameIdentity(snapshot.sourceIdentity())
                        + "\",\"settingsIdentityHash\":\"" + safeLayerNameIdentity(snapshot.settingsHash())
                        + "\",\"networkIdentityHash\":\"" + safeLayerNameIdentity(snapshot.contentHash()) + "\""
                        + (failure == null ? "" : ",\"phase\":\"" + failure.phase().name() + "\"" + failure.facts().jsonMembers())
                        + "}\n"));
                DiagnosticsRegistry.setLastModernBundle(new Format15Bundle(base.buildIdentity(), base.sourceIdentityHash(),
                        base.parameterHash(), artifacts));
                showFailure.accept(tr("Alignment preview failed safely ({0}). No changes were applied. "
                        + "Use Export Last Alignment Diagnostics for details.", code));
            });
        }
    }

    static String workerFailureCode(String reason) {
        if (manualCaptureReason(reason) != null) return "MANUAL_JUNCTION_BLOCKED";
        if (reason != null && (reason.startsWith("IllegalStateException: Required relation members are incomplete: ")
                || reason.startsWith("IllegalArgumentException: Required relation members are incomplete: "))) {
            return "INCOMPLETE_RELATION_MEMBERS";
        }
        if ("IllegalArgumentException: Valid scalar source must be normalized".equals(reason)
                || "IllegalArgumentException: Valid scalar evidence must be finite and normalized".equals(reason)) {
            return "SCALAR_SOURCE_NOT_NORMALIZED";
        }
        if (reason != null && (reason.startsWith("ResourceLimitException:") || reason.equals("ResourceLimitException"))) {
            return "RESOURCE_LIMIT";
        }
        return "ALIGNMENT_WORKER_FAILURE";
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
        LiveBPreviewService.Computed selected = state.selectedRun() == null
                ? computed : state.selectedRun();
        if (!computed.partitioned() || computed.productionRuns().stream().noneMatch(run -> run == selected)
                || state.batch() != selected.intervalBatch()) {
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
                state.routeChoices(), intervalSourceReceipt(selected.captured()), status,
                reviewed, applied);
        var batch = state.batch();
        bundle = org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15
                .CorridorExecutionArtifacts.attachIntervals(bundle,batch);
        return withSourceChoices(withSettingsResolution(
                Format15ProductionBundleFactory.withCurrentNumericalPolicy(bundle,
                new FrozenReplayInput(batch.fullRequest(), batch.evidence(), batch.network(), batch.options()),
                batch.fullRequest().engine(), batch.runs().stream()
                        .map(run -> run.result().inference()).toList()), selected), computed, selected);
    }

    private static void recordIntervalDiagnostics(LiveBPreviewService.Computed computed,
            IntervalPreviewState state, IntervalArtifactStatus status, String attemptIdentity) {
        try {
            DiagnosticsRegistry.setLastModernBundle(createIntervalDiagnostics(computed, state, status));
        } catch (RuntimeException failure) {
            var facts = FailureFacts.capture(failure);
            String terminal = facts.resourceLimited() ? "resource-limited" : "failed";
            Format15Bundle fallback = Format15ProductionBundleFactory.createUnavailableLive(
                    LastSlideDebugBundle.buildIdentity(), terminal, computed.captured().managedRaster() == null
                            ? "visible-layer" : "managed-tiles", attemptIdentity);
            LiveBPreviewService.Computed selected = state.selectedRun() == null ? computed : state.selectedRun();
            DiagnosticsRegistry.setLastModernBundle(withFailureSummary(fallback, "DIAGNOSTIC_EXPORT",
                    format15FailureCode(failure), selected, facts, "INTERVAL_DIAGNOSTIC_BUNDLE"));
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
        recordModernDiagnostics(computed, computed, status, routeIndex, plan, reviewed,
                applied, attemptIdentity, assessedManualReason);
    }

    private static void recordModernDiagnostics(LiveBPreviewService.Computed root,
            LiveBPreviewService.Computed computed, String status, int routeIndex,
            AlignmentEditPlan plan, boolean reviewed, boolean applied,
            String attemptIdentity) {
        recordModernDiagnostics(root, computed, status, routeIndex, plan, reviewed,
                applied, attemptIdentity, null);
    }

    private static void recordModernDiagnostics(LiveBPreviewService.Computed root,
            LiveBPreviewService.Computed computed, String status, int routeIndex,
            AlignmentEditPlan plan, boolean reviewed, boolean applied,
            String attemptIdentity, ManualJunctionEligibility.Reason assessedManualReason) {
        recordModernDiagnostics(root, computed, status, routeIndex, plan, reviewed, applied,
                attemptIdentity, assessedManualReason, null, null);
    }

    private static void recordModernDiagnostics(LiveBPreviewService.Computed root,
            LiveBPreviewService.Computed computed, String status, int routeIndex,
            AlignmentEditPlan plan, boolean reviewed, boolean applied,
            String attemptIdentity, ManualJunctionEligibility.Reason assessedManualReason,
            ModernSingleWayEditPlanAdapter.Assessment assessment,
            NetworkSnapshot currentNoChangeNetwork) {
        recordModernDiagnostics(root, computed, status, routeIndex, plan, reviewed, applied,
                attemptIdentity, assessedManualReason, assessment, currentNoChangeNetwork, false);
    }

    /** Retains computed evidence when publication fails; accepts no arbitrary exception text. */
    static void recordRejectedModernPreview(LiveBPreviewService.Computed computed, String status,
            String attemptIdentity, ManualJunctionEligibility.Reason manualReason) {
        if (computed.partitioned()) {
            try {
                Format15Bundle bundle = createIntervalDiagnostics(computed,
                        new IntervalPreviewState(computed.intervalBatch()), IntervalArtifactStatus.FAILED);
                DiagnosticsRegistry.setLastModernBundle(withFailureSummary(bundle,
                        "PREVIEW_PUBLICATION", "COMPUTED_PREVIEW_REJECTED", computed));
            } catch (RuntimeException failure) {
                recordIntervalDiagnostics(computed, new IntervalPreviewState(computed.intervalBatch()),
                        IntervalArtifactStatus.FAILED, attemptIdentity);
            }
            return;
        }
        recordModernDiagnostics(computed, computed, status, 0, null, false, false,
                attemptIdentity, manualReason, null, null, true);
    }

    private static void recordModernDiagnostics(LiveBPreviewService.Computed root,
            LiveBPreviewService.Computed computed, String status, int routeIndex,
            AlignmentEditPlan plan, boolean reviewed, boolean applied,
            String attemptIdentity, ManualJunctionEligibility.Reason assessedManualReason,
            ModernSingleWayEditPlanAdapter.Assessment assessment,
            NetworkSnapshot currentNoChangeNetwork, boolean publicationRejected) {
        String sourceLineage = computed.captured().managedRaster() == null
                ? "visible-layer" : "managed-tiles";
        try {
            Format15Bundle bundle = createModernDiagnostics(root, computed,
                    status, routeIndex, plan, reviewed, applied, assessedManualReason,
                    assessment, currentNoChangeNetwork);
            if (publicationRejected) {
                bundle = withFailureSummary(bundle, "PREVIEW_PUBLICATION",
                        "COMPUTED_PREVIEW_REJECTED", computed);
            } else if (computed.pipeline().routes().isEmpty()) {
                bundle = withFailureSummary(bundle, "PREVIEW", "NO_PREVIEWABLE_ROUTE", computed);
            }
            DiagnosticsRegistry.setLastModernBundle(bundle);
        } catch (RuntimeException failure) {
            var facts = FailureFacts.capture(failure);
            String diagnosticStatus = facts.resourceLimited() ? "resource-limited" : "failed";
            Format15Bundle fallback = Format15ProductionBundleFactory.createUnavailableLive(
                    LastSlideDebugBundle.buildIdentity(), diagnosticStatus, sourceLineage, attemptIdentity,
                    assessedManualReason);
            DiagnosticsRegistry.setLastModernBundle(withFailureSummary(fallback, "DIAGNOSTIC_EXPORT",
                    format15FailureCode(failure), computed, facts, "FULL_ROUTE_DIAGNOSTIC_BUNDLE"));
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
            case "Replay scalar field exceeds artifact budget" -> "frozen-scalar-artifact-budget";
            case "Replay aggregate collection budget exceeded" -> "frozen-aggregate-budget";
            case "Replay aggregate retained-memory budget exceeded" -> "frozen-retention-budget";
            case "Frozen replay input exceeds byte budget" -> "frozen-input-byte-budget";
            case "Diagnostic artifact exceeds the per-file limit" -> "artifact-byte-limit";
            case "Replay collection exceeds component budget" -> "frozen-component-budget";
            default -> "export-failed";
        };
    }

    private static Format15Bundle createModernDiagnostics(LiveBPreviewService.Computed computed,
            String status, int routeIndex, AlignmentEditPlan plan, boolean reviewed,
            boolean applied, ManualJunctionEligibility.Reason assessedManualReason) {
        return createModernDiagnostics(computed, computed, status, routeIndex, plan, reviewed,
                applied, assessedManualReason);
    }

    static Format15Bundle createModernDiagnostics(LiveBPreviewService.Computed root,
            LiveBPreviewService.Computed computed, String status, int routeIndex,
            AlignmentEditPlan plan, boolean reviewed, boolean applied,
            ManualJunctionEligibility.Reason assessedManualReason) {
        return createModernDiagnostics(root, computed, status, routeIndex, plan, reviewed,
                applied, assessedManualReason, null, null);
    }

    static Format15Bundle createModernDiagnostics(LiveBPreviewService.Computed root,
            LiveBPreviewService.Computed computed, String status, int routeIndex,
            AlignmentEditPlan plan, boolean reviewed, boolean applied,
            ManualJunctionEligibility.Reason assessedManualReason,
            ModernSingleWayEditPlanAdapter.Assessment assessment,
            NetworkSnapshot currentNoChangeNetwork) {
        FrozenReplayInput input = new FrozenReplayInput(computed.request(),
                computed.evidence(), computed.captured().network(), computed.options(),
                computed.captured().searchRadiusMeters(), computed.legacyB() == null ? null
                    : org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15
                            .FrozenLegacyBInput.capture(computed.legacyB()));
        int selectedRoute = computed.pipeline().routes().isEmpty() ? -1 : routeIndex;
        ManualJunctionEligibility.Decision junction = computed.request().permissions()
                .junctionPolicy() == JunctionPolicy.FIXED ? null
                : computed.captured().junctionDecision() != null
                    ? computed.captured().junctionDecision()
                    : ManualJunctionEligibility.evaluate(computed.captured().network(),
                            computed.captured().specification());
        ManualJunctionEligibility.Reason reason = assessedManualReason != null ? assessedManualReason
                        : junction != null && junction.manualOnly() ? junction.reason()
                        : junction != null && junction.reason()
                            == ManualJunctionEligibility.Reason.SIMPLE_T
                            && computed.pipeline().routes().isEmpty()
                                ? ManualJunctionEligibility.Reason.MISSING_RECEIVER_EVIDENCE
                                : null;
        String sourceLineage = computed.captured().managedRaster() == null
                ? "visible-layer" : "managed-tiles";
        Format15Bundle bundle;
        if (assessment == null && !"failed".equals(status) && !"cancelled".equals(status)) {
            bundle = Format15ProductionBundleFactory.createLive(LastSlideDebugBundle.buildIdentity(),
                    input, computed.pipeline(), status, sourceLineage, selectedRoute,
                    plan, reviewed, applied, computed.counters(), reason);
        } else {
            Format15ProductionBundleFactory.PlanAvailability availability;
            if ("failed".equals(status)) {
                availability = Format15ProductionBundleFactory.PlanAvailability.failed();
            } else if ("cancelled".equals(status)) {
                availability = Format15ProductionBundleFactory.PlanAvailability.cancelled();
            } else if (assessment.availability()
                    == ModernSingleWayEditPlanAdapter.ApplyAvailability.NO_CHANGE) {
                assessment.noChangeProof().orElseThrow().requireOwner(computed);
                availability = Format15ProductionBundleFactory.PlanAvailability.noChange(assessment);
            } else if (assessment.availability()
                    == ModernSingleWayEditPlanAdapter.ApplyAvailability.PLAN_AVAILABLE) {
                availability = plan != null && plan.validation().reviewRequired()
                        ? Format15ProductionBundleFactory.PlanAvailability.reviewRequired(reviewed,
                                assessment.availability())
                        : Format15ProductionBundleFactory.PlanAvailability.available();
            } else {
                availability = Format15ProductionBundleFactory.PlanAvailability.unavailable(
                        assessment.availability());
            }
            if (assessment != null && !"failed".equals(status) && !"cancelled".equals(status)
                    && !Objects.equals(assessment.plan().orElse(null), plan)) {
                throw new IllegalArgumentException("Diagnostic plan differs from selected assessment");
            }
            bundle = Format15ProductionBundleFactory.createLiveWithPlanAvailability(
                    LastSlideDebugBundle.buildIdentity(), input, computed.pipeline(), status,
                    sourceLineage, selectedRoute, plan, reviewed, applied, computed.counters(),
                    reason, availability, currentNoChangeNetwork);
        }
        if (computed.legacyB() != null) {
            bundle = org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15
                    .LegacyBReplayWork.attach(bundle, input, computed.legacyB());
        }
        if (computed.corridorReceipt().isPresent()) {
            bundle = org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.CorridorExecutionArtifacts.attach(
                    bundle,input,computed.pipeline().inference(),computed.corridorReceipt().orElseThrow());
        }
        return withSourceChoices(withSettingsResolution(
                Format15ProductionBundleFactory.withCurrentNumericalPolicy(
                        bundle, input, input.request().engine(),
                        java.util.List.of(computed.pipeline().inference())), computed), root, computed);
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
        ManagedHeatmapConfig result = forcedLivePreviewEngine == null ? effective
                : effective.withAlignmentMode(AlignmentMode.PRECISE_SHAPE)
                        .withTrackerMode(forcedLivePreviewEngine);
        if (result.trackerMode() == TrackerMode.HYBRID) {
            throw new IllegalArgumentException("Historical A+B Hybrid is no longer supported");
        }
        if (result.trackerMode() == TrackerMode.LEGACY_THEN_B
                && result.alignmentMode() != AlignmentMode.PRECISE_SHAPE) {
            throw new IllegalArgumentException(
                    "Hybrid requires Precise Shape. Select Precise Shape or another tracing method.");
        }
        return result;
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
        if (tracing.engine() == TrackerMode.HYBRID || config.heatmap().trackerMode() == TrackerMode.HYBRID) {
            throw new IllegalArgumentException("Historical A+B Hybrid is no longer supported");
        }
        if (!tracing.engine().capabilities().requiresEvidenceSnapshot()) {
            return new OrdinaryRoute(OrdinaryPipeline.LEGACY_COMPATIBILITY, null);
        }
        ModernAlignmentInvocation invocation = ModernAlignmentInvocation.resolve(tracing,
                config.forEffectiveModernAttempt());
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
                requiredVisibleSource, legacyVisibleSource), config);
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
                        forcedLivePreviewEngine), currentPersisted.cleanup())
                .forEffectiveModernAttempt();
        return effectiveAtCapture.equals(currentEffective);
    }

    /** Revalidates the immutable attempt mode, including an authorized Precise recovery. */
    static boolean matchesLivePreviewSettings(AlignmentConfig persistedAtCapture,
            AlignmentConfig effectiveAtCapture, AlignmentConfig currentPersisted,
            TracingSettings tracingAtCapture, TracingSettings currentTracing,
            TrackerMode forcedLivePreviewEngine) {
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
                effectiveConfig(currentBase, effectiveAtCapture.heatmap().alignmentMode(),
                        forcedLivePreviewEngine),
                currentPersisted.cleanup()).forEffectiveModernAttempt();
        return effectiveAtCapture.equals(currentEffective);
    }

    private static String livePreviewEngineLabel(TrackerMode engine) {
        return switch (engine) {
            case CORRIDOR_AWARE -> tr("Corridor-aware A");
            case PROBABILISTIC -> tr("Probabilistic B");
            case HYBRID -> tr("Hybrid A+B");
            case LEGACY_THEN_B -> tr("Hybrid: Legacy then B");
            case DIRECTIONAL_IMAGE -> tr("Directional Image");
            default -> tr("Modern");
        };
    }

    private static String livePreviewEngineShortLabel(TrackerMode engine) {
        return switch (engine) {
            case CORRIDOR_AWARE -> "A";
            case PROBABILISTIC -> "B";
            case HYBRID -> "Hybrid A+B";
            case LEGACY_THEN_B -> "Legacy then B";
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

    /** Starts ownership before the original Legacy computation; native bootstrap remains explicit. */
    static LegacyReviewOwner captureLegacyReview(DataSet dataSet, SelectionContext selection,
            AlignmentConfig config, Function<AlignmentService.LegacyCaptureObserver, AlignmentResult> alignment) {
        return completeLegacyReview(LegacyReviewOwner.begin(dataSet, selection, config),alignment);
    }
    private static LegacyReviewOwner completeLegacyReview(LegacyReviewOwner owner,
            Function<AlignmentService.LegacyCaptureObserver,AlignmentResult> alignment) {
        LegacyVisibleSource[] source = {null};
        TileHeatmapSampler.AcquisitionReceipt[] managed = {null};
        try {
            AlignmentResult result = alignment.apply(new AlignmentService.LegacyCaptureObserver() {
                @Override public void visible(LegacyVisibleSource frame) { source[0] = frame; }
                @Override public void managed(TileHeatmapSampler.AcquisitionReceipt receipt) { managed[0] = receipt; }
            });
            owner.captured(result, source[0], managed[0]);
            return owner;
        } catch (RuntimeException | Error failure) {
            owner.close();
            throw failure;
        }
    }

    /** Synchronous root-selection callback; explicit warning paths remain outside owned restoration. */
    static final class LegacyRootPresentation {
        private boolean owned,failed;
        void present(Runnable selection){
            if(owned)throw new IllegalStateException("Legacy root presentation is already running");
            owned=true;failed=false;
            try{selection.run();}finally{owned=false;}
            if(failed)throw new IllegalStateException("The retained Legacy preview could not be displayed");
        }
        boolean failedOwnedSelection(){if(!owned)return false;failed=true;return true;}
    }

    record ReviewButtonContext(CandidateAssessment.Disposition disposition,boolean matchingConfirmation,
            boolean busy,boolean nativeSelection,boolean nativeCanApply,boolean currentOwner) { }
    /** The live workflow status callback; no acquisition or computation belongs here. */
    static Runnable createReviewButtonRefresh(LegacyReviewOwner owner,JButton apply,JButton confirm,
            Supplier<ReviewButtonContext> current) {
        return ()->{var state=current.get();boolean authority=state.currentOwner();
            try{owner.requireLegacyAuthorityCurrent();}catch(IllegalStateException stale){authority=false;}
            boolean review=state.disposition()==CandidateAssessment.Disposition.REVIEW_REQUIRED;
            boolean applicable=state.nativeSelection()?state.nativeCanApply():state.disposition()==CandidateAssessment.Disposition.APPLICABLE||review&&state.matchingConfirmation();
            apply.setEnabled(authority&&!state.busy()&&applicable);
            confirm.setVisible(review);confirm.setEnabled(authority&&!state.busy()&&review&&!state.matchingConfirmation());
            confirm.setText(authority&&review&&state.matchingConfirmation()?tr("Review confirmed"):tr("Confirm reviewed candidate"));
            String unavailable=!authority?tr("The original alignment source or settings changed; run alignment again.")
                :state.busy()?tr("Wait for refinement to stop."):null;
            apply.setToolTipText(unavailable);confirm.setToolTipText(unavailable);
        };
    }
    static CandidateReviewConfirmation confirmLegacyReview(LegacyReviewOwner owner,AlignmentService service,
            DataSet dataSet,SelectionContext selection,AlignmentResult result,CenterlineCandidate candidate,ManagedHeatmapConfig config) {
        owner.requireLegacyAuthorityCurrent();
        if(owner.selectedCandidate()!=candidate)throw new IllegalStateException("The selected Legacy candidate changed");
        if(!canConfirmCandidate(AlignmentService.assessCandidate(candidate)))throw new IllegalStateException("This candidate cannot be enabled by review");
        requirePreviewSourceAndTopologySafe(service,dataSet,selection,result,candidate,config);
        if(!config.allowUndownloadedAlignment())requirePreviewWithinDownloadedArea(result.previewPolyline(),dataSet);
        return CandidateReviewConfirmation.capture(candidate,result.previewPolyline());
    }
    static org.openstreetmap.josm.command.Command createLegacyReviewCommand(LegacyReviewOwner owner,AlignmentService service,
            DataSet dataSet,SelectionContext selection,AlignmentResult result,CenterlineCandidate candidate,ManagedHeatmapConfig config,
            CandidateReviewConfirmation confirmation) {
        owner.requireLegacyAuthorityCurrent();
        if(owner.selectedCandidate()!=candidate)throw new IllegalStateException("The selected Legacy candidate changed");
        return createPreviewCommand(service,dataSet,selection,result,candidate,config,confirmation);
    }

    /** Root-only presentation while native child assembly and qualified smoothing remain unavailable. */
    static RefinementControls createLegacyRefinementControls(LegacyReviewOwner owner,
            Consumer<CenterlineCandidate> select, Supplier<String> quality) {
        RefinementControls[] controls = {null};
        controls[0] = new RefinementControls(new RefinementControls.Listener() {
            private void unavailable(String reason) {
                presentLegacyRefinementControls(controls[0], owner, reason);
            }
            public void selected(String id) {
                CenterlineCandidate candidate = owner.candidateForChoice(id);
                select.accept(candidate);
                presentLegacyRefinementControls(controls[0], owner, quality.get());
            }
            public void strengthSelected(RefinementControls.Strength strength) {
                unavailable(tr("Smoothing is not available in this build."));
            }
            public void undo() { unavailable(tr("This is the first preview stage.")); }
            public void redo() { unavailable(tr("There is no later preview stage.")); }
            public void refineA() { unavailable(tr("Refinement is not available in this build.")); }
            public void refineB() { unavailable(tr("Refinement is not available in this build.")); }
            public void smooth(RefinementControls.Strength strength) {
                unavailable(tr("Smoothing is not available in this build."));
            }
            public void cancel() { unavailable(tr("No refinement is running.")); }
        });
        presentLegacyRefinementControls(controls[0], owner, quality.get());
        return controls[0];
    }

    private static void presentLegacyRefinementControls(RefinementControls controls,
            LegacyReviewOwner owner, String quality) {
        var choices = owner.choices().stream().map(choice ->
            new RefinementControls.Choice(choice.id(), tr("Legacy / {0}", choice.candidate().displayName()))).toList();
        var noRefinement = new RefinementControls.Availability(false, tr("Refinement is not available in this build."));
        var noSmoothing = new RefinementControls.Availability(false, tr("Smoothing is not available in this build."));
        controls.present(new RefinementControls.ViewState(tr("Legacy — stage 1"), choices,
            owner.choiceId(owner.selectedCandidate()), quality, RefinementControls.Strength.MEDIUM, false,
            new RefinementControls.Availability(true, ""),
            new RefinementControls.Availability(false, tr("This is the first preview stage.")),
            new RefinementControls.Availability(false, tr("There is no later preview stage.")),
            noRefinement, noRefinement, noSmoothing));
    }

    private void showNativeRefinementDisplay(SelectionContext selection,LegacyRefinementWorkflow.Display displayed,String projectionCode) {
        var plan=displayed.nativeReview().map(RefinementPayload.NativeReview::plan);
        var geometry=plan.map(AlignmentEditPlan::finalPreviewWays).orElseGet(()->displayed.noChange().orElseThrow().finalSelection().finalPreviewWays());
        var validation=plan.map(AlignmentEditPlan::validation).orElseGet(()->displayed.noChange().orElseThrow().validation());
        var projection=Projections.getProjectionByCode(projectionCode);var projected=new LinkedHashMap<PrimitiveKey,List<EastNorth>>();
        geometry.forEach((key,points)->projected.put(key,points.stream().map(point->projection.latlon2eastNorth(new LatLon(point.latitudeDegrees(),point.longitudeDegrees()))).toList()));
        overlay.setDisconnectionLocations(disconnectionLocations(plan.orElse(null)));
        overlay.show(selection,displayed.result(),displayed.candidate(),CandidateAssessment.Disposition.valueOf(validation.disposition().name()),false,PluginPreferences.isDebugEnabled(),projected);
    }
    /** Once the edit commits, diagnostic/presentation failures cannot turn it into a rejected edit. */
    private boolean applyRefinementReview(LegacyRefinementWorkflow workflow,DataSet data,AlignmentConfig config,Runnable committed,Runnable close) {
        boolean applied=false,commandAttempted=false;
        try {
            if(workflow.currentNoChangeRoot().isPresent()) {
                workflow.acceptNoChangeRoot();applied=true;committed.run();close.run();return true;
            }
            if(!config.heatmap().allowUndownloadedAlignment())requirePreviewWithinDownloadedArea(workflow.selectedGeometry(),data);
            var command=workflow.command();commandAttempted=true;
            var publication=addReviewedCommand(UndoRedoHandler.getInstance(),command);applied=true;
            committed.run();
            try{workflow.appliedDiagnostics(publication==CommandPublication.COMMITTED_NOTIFICATION_FAILED);}
            catch(Exception failed){PluginLog.verbose("Committed refinement diagnostics unavailable: %s",failed.getClass().getSimpleName());}
            try{close.run();}catch(Exception failed){PluginLog.verbose("Committed refinement review close unavailable: %s",failed.getClass().getSimpleName());}
        }catch(Exception failed) {
            if(applied)PluginLog.verbose("Committed refinement notification unavailable: %s",failed.getClass().getSimpleName());
            else {
                try{recordRefinementApplyFailure(failed,commandAttempted);}catch(RuntimeException unavailable){ /* Failure diagnostics cannot change transaction handling. */ }
                try{workflow.diagnosticsChanged("apply-failed");workflow.refresh();}
                catch(Exception unavailable){PluginLog.verbose("Refinement failure presentation unavailable: %s",unavailable.getClass().getSimpleName());}
                if(commandAttempted) {
                    try{close.run();}catch(Exception unavailable){PluginLog.verbose("Failed refinement review close unavailable: %s",unavailable.getClass().getSimpleName());}
                    showError(tr("The selected refinement could not be applied. Run alignment again and inspect the failure diagnostics."));
                } else showError(tr("The selected refinement could not be applied. Inspect the retained preview and run alignment again if its source changed."));
            }
        }
        return applied;
    }
    /** Bounded actual failure facts; exception text, coordinates and arbitrary server data are excluded. */
    private static void recordRefinementApplyFailure(Throwable failure,boolean commandAttempted) {
        var prefix="org.openstreetmap.josm.plugins.wayheatmaptracer.";
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.add("apply.refinementFailure.phase."+(commandAttempted?"COMMAND":"PREFLIGHT"),1);
        for(int cause=0;failure!=null&&cause<8;cause++,failure=failure.getCause()) {
            String kind=failure instanceof org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.LimitReached?"WorkLimit"
                :failure instanceof org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.ResourceLimitException?"MemoryLimit"
                :failure instanceof java.util.concurrent.CancellationException?"Cancelled"
                :failure instanceof IllegalArgumentException?"IllegalArgument":failure instanceof IllegalStateException?"IllegalState":"Other";
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.add("apply.refinementFailure.cause"+cause+"."+kind,1);
            String type="unavailable",method="unavailable";int line=-1;
            for(var frame:failure.getStackTrace())if(frame.getClassName().startsWith(prefix)) {
                type=refinementDiagnosticSymbol(frame.getClassName().substring(frame.getClassName().lastIndexOf('.')+1));
                method=refinementDiagnosticSymbol(frame.getMethodName());line=frame.getLineNumber();break;
            }
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.add("apply.refinementFailure.class"+cause+"."+type,1);
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.add("apply.refinementFailure.method"+cause+"."+method,1);
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record("apply.refinementFailure.line"+cause,line);
        }
    }
    /** Plugin stack identifiers only; separate fields keep every counter within its81-character contract. */
    private static String refinementDiagnosticSymbol(String symbol) {
        if(!symbol.matches("[A-Za-z_$][A-Za-z0-9_$]*"))return "unavailable";
        String safe=symbol.replace('$','.').replace('_','.');
        return safe.substring(0,Math.min(40,safe.length()));
    }
    private org.openstreetmap.josm.plugins.wayheatmaptracer.service.VisibleRefinementCapture.Port nativeVisibleCapturePort(
            LiveBPreviewService.Captured captured,ImageryLayer layer,MapView view,Runnable sourceGuard) {
        if(captured.managedRaster()!=null)return (request,work)->{throw new UnsupportedOperationException("visible-capture-not-applicable-to-managed-source");};
        return new org.openstreetmap.josm.plugins.wayheatmaptracer.service.VisibleRefinementCapture(
            alignmentService.visibleRefinementRenderer(layer,view,sourceGuard),SwingUtilities::invokeLater,
            java.util.concurrent.ForkJoinPool.commonPool(),320,320).port();
    }
    private void attachRefinementLifecycle(JDialog review,LegacyRefinementWorkflow owner) {
        var progress=new JDialog(MainApplication.getMainFrame(),tr("Updating heatmap result"),false);
        progress.setName("refinement-progress");
        progress.getRootPane().putClientProperty("wayheatmaptracer.refinementOwner",owner);
        var status=new JTextArea(tr("Updating the selected result. The map and diagnostics menu remain available."),3,42);
        status.setEditable(false);status.setLineWrap(true);status.setWrapStyleWord(true);
        var cancel=new JButton(tr("Cancel"));cancel.setName("refinement-cancel");
        Runnable cancelWork=()->{status.setText(tr("Cancelling; waiting for the current operation to settle."));owner.cancel();};
        cancel.addActionListener(event->cancelWork.run());
        var buttons=new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.TRAILING));buttons.add(cancel);
        var contents=new JPanel(new BorderLayout(4,4));contents.add(new JScrollPane(status),BorderLayout.CENTER);contents.add(buttons,BorderLayout.SOUTH);
        progress.setContentPane(contents);progress.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);progress.pack();progress.setLocationRelativeTo(MainApplication.getMainFrame());
        progress.addWindowListener(new WindowAdapter(){@Override public void windowClosing(WindowEvent event){cancelWork.run();}});
        review.addWindowListener(new WindowAdapter(){@Override public void windowClosed(WindowEvent event){progress.dispose();}});
        owner.attachLifecycle(new RefinementReviewLifecycle(()->review.setVisible(false),
            ()->{if(owner.ownsPublication()&&review.isDisplayable())review.setVisible(true);},
            ()->{if(!owner.ownsPublication()||!review.isDisplayable())throw new IllegalStateException("The review owner is closed");status.setText(tr("Updating the selected result. The map and diagnostics menu remain available."));progress.setVisible(true);},
            ()->progress.setVisible(false),message->{status.setText(message);}));
    }

    private void showCandidatePreview(
        DataSet dataSet,
        SelectionContext selection,
        AlignmentResult result,
        AlignmentConfig slideConfig,
        ImageryLayer imageryLayer,
        MapView mapView,
        Map<String, CandidateRating> candidateRatings,
        LegacyReviewOwner legacyReview
    ) {
        PreviewSessionController.Owner rootOwner=livePreviewSession.open(()->{});activeLivePreviewOwner=rootOwner;
        showCandidatePreview(dataSet,selection,result,slideConfig,imageryLayer,mapView,candidateRatings,legacyReview,rootOwner);
    }
    private void showCandidatePreview(DataSet dataSet,SelectionContext selection,AlignmentResult result,AlignmentConfig slideConfig,
            ImageryLayer imageryLayer,MapView mapView,Map<String,CandidateRating> candidateRatings,
            LegacyReviewOwner legacyReview,PreviewSessionController.Owner rootOwner) {
        ManagedHeatmapConfig config = slideConfig.heatmap();
        GeometryCleanupConfig cleanupConfig = slideConfig.cleanup();
        if (result.candidates().isEmpty()) {
            throw new IllegalStateException(tr("No centerline candidate could be extracted from the heatmap."));
        }
        CenterlineCandidate initial = initialCandidate(result);
        legacyReview.select(initial);
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
        apply.setName("legacy-apply");
        apply.setEnabled(initialAssessment.automaticallyApplicable());
        JButton confirm = new JButton(tr("Confirm reviewed candidate"));
        confirm.setName("legacy-confirm");
        confirm.setVisible(canConfirmCandidate(initialAssessment));
        confirm.setEnabled(canConfirmCandidate(initialAssessment));
        JButton retry = new JButton(tr("Retry with wider search..."));
        configureRetryButton(retry, initial, slideConfig, result);

        double currentSearchHalfWidth = retrySearchBounds(slideConfig, result).currentMeters();
        JTextArea selectedCoverageStatus = reviewStatus(coverageStatus(
            initial, currentSearchHalfWidth, initialAssessment, false));
        JTextArea selectedCleanupStatus = reviewStatus(cleanupStatus(initial));
        JTextArea selectedCandidateDetail = reviewStatus(cleanupDetail(initial));
        selectedCoverageStatus.setName("legacy-coverage");
        selectedCleanupStatus.setName("legacy-cleanup");
        selectedCandidateDetail.setName("legacy-detail");
        Supplier<String> currentQuality = () -> selectedCoverageStatus.getText() + "\n"
            + selectedCleanupStatus.getText() + "\n" + selectedCandidateDetail.getText();
        LegacyRefinementWorkflow[] workflow = {null};
        LegacyRootPresentation rootPresentation=new LegacyRootPresentation();
        Runnable refreshButtons=createReviewButtonRefresh(legacyReview,apply,confirm,()->{
            boolean nativeChoice=workflow[0]!=null&&workflow[0].nativeSelected();
            boolean busy=workflow[0]!=null&&workflow[0].busy();
            var disposition=nativeChoice?CandidateAssessment.Disposition.valueOf(workflow[0].currentNative().orElseThrow().plan().validation().disposition().name())
                :AlignmentService.assessCandidate(current[0].candidate()).disposition();
            boolean confirmed=nativeChoice?workflow[0].canApplyNative():confirmationMatches(reviewConfirmation[0],current[0].candidate(),current[0]);
            return new ReviewButtonContext(disposition,confirmed,busy,nativeChoice,nativeChoice&&workflow[0].canApplyNative(),livePreviewSession.isCurrent(rootOwner)&&(workflow[0]==null||workflow[0].hasCurrentPresentation()));
        });
        workflow[0] = new LegacyRefinementWorkflow(legacyReview,
            org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileRuntime.coordinator(),
            candidate -> rootPresentation.present(()->{comboBox.setSelectedItem(null);comboBox.setSelectedItem(candidate);}),
            displayed -> {
                current[0] = new PreviewSelection(initial, displayed.candidate(), displayed.result());
                reviewConfirmation[0] = null;
                var disposition = displayed.nativeReview().orElseThrow().plan().validation().disposition();
                overlay.show(selection, displayed.result(), displayed.candidate(),
                    CandidateAssessment.Disposition.valueOf(disposition.name()), false, PluginPreferences.isDebugEnabled());
                selectedCleanupStatus.setText(""); selectedCandidateDetail.setText("");
                refreshButtons.run();
                retry.setEnabled(false);
            },
            message -> {
                if(workflow[0] != null) {
                    boolean busy = workflow[0].busy();
                    retry.setEnabled(!busy && !workflow[0].nativeSelected());
                    refreshButtons.run();
                }
                if(!message.isEmpty()) selectedCoverageStatus.setText(message);
            }, new org.openstreetmap.josm.plugins.wayheatmaptracer.service.VisibleRefinementCapture(
                alignmentService.visibleRefinementRenderer(imageryLayer,mapView,legacyReview::requireLegacyAuthorityCurrent),
                SwingUtilities::invokeLater,java.util.concurrent.ForkJoinPool.commonPool(),320,320).port());
        var originalLegacyDebug=DiagnosticsRegistry.getLastBundle();
        workflow[0].enableRuntimeDiagnostics(()->originalLegacyDebug,()->candidateRatings);
        refreshButtons.run();
        RefinementControls refinementControls = workflow[0].controls();
        JPanel panel = buildSummaryPanel(
            current[0].result(), initial, config, cleanupConfig, null,
            ratingMode ? ratingBox : null, offTheLine, jumping, unnecessaryKinks, badJunctionShapes);
        panel.add(new JScrollPane(selectedCoverageStatus), GBC.eol().fill(GBC.HORIZONTAL));
        panel.add(new JScrollPane(selectedCleanupStatus), GBC.eol().fill(GBC.HORIZONTAL));
        panel.add(new JScrollPane(selectedCandidateDetail), GBC.eol().fill(GBC.HORIZONTAL));
        comboBox.addActionListener(event -> {
            CenterlineCandidate selected = (CenterlineCandidate) comboBox.getSelectedItem();
            if (selected == null) {
                return;
            }
            try {
                reviewConfirmation[0] = null;
                current[0] = buildPreviewSelection(dataSet, result, current[0].initialCandidate(), selected, config);
                legacyReview.select(selected);
                CandidateAssessment assessment = AlignmentService.assessCandidate(selected);
                overlay.show(selection, current[0].result(), selected, assessment.disposition(), false,
                    PluginPreferences.isDebugEnabled());
                selectedCoverageStatus.setText(coverageStatus(
                    selected, currentSearchHalfWidth, assessment, false));
                selectedCleanupStatus.setText(cleanupStatus(selected));
                refreshButtons.run();
                selectedCandidateDetail.setText(cleanupDetail(selected));
                configureRetryButton(retry, selected, slideConfig, result);
                comboBox.repaint();
                loadingRating[0] = true;
                loadCandidateRating(candidateRatings.get(selected.id()), ratingBox, offTheLine, jumping, unnecessaryKinks, badJunctionShapes);
                loadingRating[0] = false;
                refreshButtons.run();
                updatePreviewBundle(current[0], candidateRatings, "preview-open", workflow[0]);
            } catch (Exception ex) {
                if(rootPresentation.failedOwnedSelection())return;
                Logging.warn("WayHeatmapTracer rejected preview candidate: " + ex.getMessage());
                showError(tr("WayHeatmapTracer failed: {0}", ex.getMessage()));
                comboBox.setSelectedItem(current[0].candidate());
            }
        });
        ratingBox.addActionListener(event -> {
            if (!loadingRating[0]) {
                saveCandidateRating(current[0], candidateRatings, ratingBox, offTheLine, jumping, unnecessaryKinks, badJunctionShapes, workflow[0]);
            }
        });
        offTheLine.addActionListener(event -> saveCandidateRating(current[0], candidateRatings, ratingBox, offTheLine, jumping, unnecessaryKinks, badJunctionShapes, workflow[0]));
        jumping.addActionListener(event -> saveCandidateRating(current[0], candidateRatings, ratingBox, offTheLine, jumping, unnecessaryKinks, badJunctionShapes, workflow[0]));
        unnecessaryKinks.addActionListener(event -> saveCandidateRating(current[0], candidateRatings, ratingBox, offTheLine, jumping, unnecessaryKinks, badJunctionShapes, workflow[0]));
        badJunctionShapes.addActionListener(event -> saveCandidateRating(current[0], candidateRatings, ratingBox, offTheLine, jumping, unnecessaryKinks, badJunctionShapes, workflow[0]));

        JButton cancel = new JButton(tr("Cancel"));
        JPanel buttons = new JPanel(new java.awt.GridLayout(0, 2, 4, 4));
        buttons.add(confirm);
        buttons.add(apply);
        buttons.add(retry);
        buttons.add(cancel);
        JPanel contents = new JPanel(new BorderLayout(4, 4));
        JScrollPane reviewScroll = new JScrollPane(panel);
        reviewScroll.setName("legacy-review-scroll");
        java.awt.Rectangle screen = MainApplication.getMainFrame() == null
            ? new java.awt.Rectangle(0, 0, 1000, 800)
            : MainApplication.getMainFrame().getGraphicsConfiguration().getBounds();
        Dimension preferred = panel.getPreferredSize();
        reviewScroll.setPreferredSize(new Dimension(Math.max(300, Math.min(preferred.width + 24, screen.width - 80)),
            Math.max(220, Math.min(preferred.height + 12, screen.height - 180))));
        contents.add(refinementControls, BorderLayout.NORTH);
        contents.add(reviewScroll, BorderLayout.CENTER);
        contents.add(buttons, BorderLayout.SOUTH);

        JDialog dialog = new JDialog(MainApplication.getMainFrame(), tr("Preview Heatmap Alignment"), false);
        dialog.setName("legacy-review");
        dialog.getRootPane().putClientProperty("wayheatmaptracer.refinementOwner",workflow[0]);
        activePreviewDialog = dialog;
        dialog.setContentPane(contents);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        packReviewDialog(dialog);
        dialog.setLocationRelativeTo(MainApplication.getMainFrame());
        livePreviewSession.replaceWindow(rootOwner,()->{workflow[0].close();dialog.dispose();overlay.hide();});
        workflow[0].attachOwnership(livePreviewSession.workGate(),rootOwner,()->{
            livePreviewSession.close(rootOwner);dialog.dispose();overlay.hide();
        });
        attachRefinementLifecycle(dialog,workflow[0]);
        workflow[0].attachCommandContext(detachedCommandContext(dataSet,legacyReview.managedReceipt()==null?imageryLayer:null,legacyReview.managedReceipt()!=null?ManagedTileRuntime.initializedCoordinator():null));
        confirm.addActionListener(event -> {
            try {
                if(!livePreviewSession.isCurrent(rootOwner))throw new IllegalStateException("The preview owner was superseded");
                if(workflow[0].busy())throw new IllegalStateException("Refinement is still running");
                legacyReview.requireLegacyAuthorityCurrent();
                if (workflow[0].nativeSelected()) {
                    int answer = JOptionPane.showConfirmDialog(dialog,
                        tr("Confirm that you reviewed the complete displayed refinement geometry."),
                        tr("Confirm reviewed candidate"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                    if(answer == JOptionPane.YES_OPTION) {
                        if(!config.allowUndownloadedAlignment()) requirePreviewWithinDownloadedArea(current[0].result().previewPolyline(), dataSet);
                        workflow[0].confirm();refreshButtons.run();
                    }
                    return;
                }
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
                reviewConfirmation[0] = confirmLegacyReview(legacyReview,alignmentService,dataSet,selection,
                    current[0].result(),current[0].candidate(),config);
                refreshButtons.run();
                selectedCoverageStatus.setText(coverageStatus(current[0].candidate(),
                    currentSearchHalfWidth, assessment, true));
                overlay.show(selection, current[0].result(), current[0].candidate(),
                    assessment.disposition(), true, PluginPreferences.isDebugEnabled());
                comboBox.repaint();
                workflow[0].reviewedLegacy(reviewConfirmation[0]);workflow[0].refresh();
                PluginLog.verbose("Candidate review confirmed: candidate=%s previewPoints=%d.",
                    current[0].candidate().id(), current[0].result().previewPolyline().size());
                updatePreviewBundle(current[0], candidateRatings, "review-confirmed", workflow[0]);
            } catch (Exception ex) {
                if(!livePreviewSession.isCurrent(rootOwner))return;
                refreshButtons.run();
                PluginLog.verbose("Candidate review confirmation failed: %s", ex.toString());
                updatePreviewBundle(current[0], candidateRatings, "review-confirmation-failed", workflow[0]);
                showError(tr("WayHeatmapTracer review confirmation failed: {0}", ex.getMessage()));
            }
        });
        retry.addActionListener(event -> retryWithWiderSearch(
            dialog, dataSet, selection, current[0], slideConfig, imageryLayer, mapView, candidateRatings
        ));
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                workflow[0].close();
                livePreviewSession.close(rootOwner);
                legacyReview.close();
                if (activePreviewDialog == dialog) activePreviewDialog = null;
            }

            @Override
            public void windowClosing(WindowEvent e) {
                cancelPreview(current[0], candidateRatings,workflow[0]);
            }
        });
        apply.addActionListener(event -> {
            try {
                if(!livePreviewSession.isCurrent(rootOwner))throw new IllegalStateException("The preview owner was superseded");
                if(workflow[0].nativeSelected()) {
                    applyRefinementReview(workflow[0],dataSet,slideConfig,()->{},()->{dialog.dispose();overlay.hide();PluginLog.endSlideSession();});return;
                } else {
                    if(workflow[0].busy())throw new IllegalStateException(tr("Refinement is still running."));
                    applyPreview(dataSet, selection, current[0], config, candidateRatings, reviewConfirmation[0],legacyReview,workflow[0]);
                }
                if(!livePreviewSession.isCurrent(rootOwner))return;
                dialog.dispose();
                overlay.hide();
                PluginLog.endSlideSession();
            } catch (Exception ex) {
                ownedApplyFailure(livePreviewSession,rootOwner,workflow[0],()->{
                    Logging.error(ex);
                    PluginLog.verbose("Alignment apply failed with exception: %s", ex.toString());
                    updatePreviewBundle(current[0],candidateRatings,"apply-failed",workflow[0]);
                    reviewConfirmation[0] = null;
                    overlay.hide();
                    PluginLog.endSlideSession();
                    dialog.dispose();
                    if(livePreviewSession.isCurrent(rootOwner))showError(tr("WayHeatmapTracer failed: {0}", ex.getMessage()));
                });
            }
        });
        cancel.addActionListener(event -> {
            cancelPreview(current[0], candidateRatings,workflow[0]);
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


    /** Checks freshness before revalidating the exact candidate geometry at review and Apply. */
    static void requirePreviewSourceAndTopologySafe(AlignmentService service, DataSet dataSet,
            SelectionContext selection, AlignmentResult preview, CenterlineCandidate candidate,
            ManagedHeatmapConfig config) {
        SelectionIntegrity.requirePreviewSourceUnchanged(dataSet, selection, preview.sourcePolyline());
        requireCandidateAssignmentPlan(selection, preview, candidate, config);
        if (config.trackerMode() == TrackerMode.LEGACY_V02) {
            LegacyFinalPreviewSafety.requireSafe(preview.sourcePolyline(), candidate.finalPreviewPoints(),
                preview.previewPolyline());
        } else if (config.trackerMode() == TrackerMode.CORRIDOR_AWARE) {
            service.requireCurrentTopologySafe(candidate, selection);
        }
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
            LegacyReviewOwner retryReview = captureLegacyReview(dataSet, selection, retryConfig,
                capture -> alignmentService.alignLegacyReviewed(selection, imageryLayer, mapView, retryConfig, capture));
            try {
                AlignmentResult retried = retryReview.originalResult();
                updateAggregateIntensityLayer(retried, retryConfig.effectiveHeatmap());
                CenterlineCandidate retryInitial = initialCandidate(retried);
                DiagnosticsRegistry.setLastBundle(LastSlideDebugBundle.fromResult(
                    retried, retryInitial, retryInitial, "preview-open", PluginLog.currentSlideLog(), candidateRatings));
                overlay.hide();
                activePreviewDialog = null;
                dialog.dispose();
                showCandidatePreview(dataSet, selection, retried, retryConfig, imageryLayer, mapView, candidateRatings, retryReview);
            } catch (RuntimeException | Error failure) {
                retryReview.close();
                throw failure;
            }
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
        JCheckBox badJunctionShapes,
        LegacyRefinementWorkflow workflow
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
        updatePreviewBundle(preview, candidateRatings, "preview-open",workflow);
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

    private void updatePreviewBundle(PreviewSelection preview, Map<String, CandidateRating> candidateRatings, String status,LegacyRefinementWorkflow workflow) {
        if(!workflow.ownsPublication())return;
        if(workflow.handlesRuntimeDiagnostics()){workflow.diagnosticsChanged(status);return;}
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


    private void cancelPreview(PreviewSelection preview, Map<String, CandidateRating> candidateRatings,LegacyRefinementWorkflow workflow) {
        PluginLog.verbose("Alignment cancelled at preview dialog.");
        updatePreviewBundle(preview,candidateRatings,"cancelled",workflow);
        overlay.hide();
        PluginLog.endSlideSession();
    }

    /** Builds the guarded stored-geometry command without acquisition or engine work. */
    static org.openstreetmap.josm.command.Command createPreviewCommand(AlignmentService service,
            DataSet dataSet, SelectionContext selection, AlignmentResult chosenResult,
            CenterlineCandidate chosen, ManagedHeatmapConfig config) {
        return createPreviewCommand(service, dataSet, selection, chosenResult, chosen, config, null);
    }

    static org.openstreetmap.josm.command.Command createPreviewCommand(AlignmentService service,
            DataSet dataSet, SelectionContext selection, AlignmentResult chosenResult,
            CenterlineCandidate chosen, ManagedHeatmapConfig config, CandidateReviewConfirmation confirmation) {
        CandidateAssessment assessment = AlignmentService.assessCandidate(chosen);
        if (assessment.disposition() == CandidateAssessment.Disposition.HARD_BLOCKED) {
            throw new IllegalStateException(tr("This candidate is blocked by a structural or signal-safety finding."));
        }
        if (assessment.reviewRequired()
            && (confirmation == null || !confirmation.matches(chosen, chosenResult.previewPolyline()))) {
            throw new IllegalStateException(tr("Review and confirm this exact candidate preview before applying it."));
        }

        requirePreviewSourceAndTopologySafe(service, dataSet, selection, chosenResult, chosen, config);
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
            return new MoveNodesCommand(
                dataSet,
                chosenResult.nodeMoves(),
                tr("Align way to heatmap")
            );
        } else {
            PluginLog.verbose("Applying precise-shape alignment for candidate %s with %d preview points.", chosen.id(), chosenResult.previewPolyline().size());
            Map<Long, EastNorth> proposedNodePositions = config.trackerMode() == TrackerMode.CORRIDOR_AWARE
                ? chosen.proposedNodePositions() : null;
            if (config.trackerMode() == TrackerMode.CORRIDOR_AWARE && proposedNodePositions.isEmpty()) {
                throw new IllegalStateException(
                    "The corridor-aware preview has no existing-node assignment plan. Run the slide again.");
            }
            return new ReplaceWaySegmentCommand(
                dataSet,
                selection.way(),
                selection,
                chosenResult.previewPolyline(),
                proposedNodePositions,
                tr("Align way to heatmap precisely")
            );
        }
    }

    static void ownedApplyFailure(PreviewSessionController<?> sessions,PreviewSessionController.Owner owner,LegacyRefinementWorkflow workflow,Runnable effects){
        if(sessions.isCurrent(owner)&&workflow.ownsPublication())effects.run();
    }

    /** A void host add is not proof that executeCommand accepted the reviewed transaction. */
    enum CommandPublication { COMMITTED, COMMITTED_NOTIFICATION_FAILED }
    static CommandPublication addReviewedCommand(UndoRedoHandler history,org.openstreetmap.josm.command.Command command){
        synchronized(history) {
            if(!command.executeCommand())throw new IllegalStateException("The reviewed alignment command was refused");
            history.addNoRedraw(command,false); // Queue the original object exactly once before host notifications.
            try{history.afterAdd(command);}catch(RuntimeException notificationFailed){return CommandPublication.COMMITTED_NOTIFICATION_FAILED;}
            return CommandPublication.COMMITTED;
        }
    }

    private void applyPreview(
        DataSet dataSet,
        SelectionContext selection,
        PreviewSelection preview,
        ManagedHeatmapConfig config,
        Map<String, CandidateRating> candidateRatings,
        CandidateReviewConfirmation reviewConfirmation,
        LegacyReviewOwner legacyReview,
        LegacyRefinementWorkflow workflow
    ) {
        CenterlineCandidate chosen = preview.candidate();
        AlignmentResult chosenResult = preview.result();
        var publication=addReviewedCommand(UndoRedoHandler.getInstance(),createLegacyReviewCommand(legacyReview,alignmentService,
            dataSet, selection, chosenResult, chosen, config, reviewConfirmation));
        if(!workflow.ownsPublication())return;
        boolean notificationFailed=publication==CommandPublication.COMMITTED_NOTIFICATION_FAILED;
        if(workflow.handlesRuntimeDiagnostics())workflow.appliedDiagnostics(notificationFailed);
        else DiagnosticsRegistry.setLastBundle(LastSlideDebugBundle.fromResult(
            chosenResult, chosen, preview.initialCandidate(),
            reviewConfirmation == null ? "applied" : "applied-after-review",
            PluginLog.currentSlideLog()+(notificationFailed?"\ncommand-publication=committed-notification-failed\n":""), candidateRatings));
    }

    private static JTextArea reviewStatus(String text) {
        JTextArea status = new JTextArea(text, 2, 50);
        status.setEditable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        return status;
    }

    private JPanel buildSummaryPanel(
        AlignmentResult result,
        CenterlineCandidate chosen,
        ManagedHeatmapConfig config,
        GeometryCleanupConfig cleanupConfig,
        JComponent candidates,
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

    private void showWorkerFailure(String message) {
        JDialog dialog = createWorkerFailureDialog(MainApplication.getMainFrame(), message);
        dialog.setVisible(true);
    }

    static JDialog createWorkerFailureDialog(java.awt.Window owner, String message) {
        JDialog dialog = new JDialog(owner, tr("Alignment preview failed"), java.awt.Dialog.ModalityType.MODELESS);
        JTextArea text = new JTextArea(previewFailureText(message), 7, 76);
        text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true);
        JButton close = new JButton(tr("Close")); close.addActionListener(event -> dialog.dispose());
        JPanel content = new JPanel(new java.awt.BorderLayout());
        content.add(new JScrollPane(text), java.awt.BorderLayout.CENTER);
        content.add(close, java.awt.BorderLayout.SOUTH);
        dialog.setContentPane(content); dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.pack(); dialog.setLocationRelativeTo(owner);
        return dialog;
    }

    static String aggregateSourceWarning(ManagedModernPreviewSource.AggregateFailure failure) {
        if (failure == null) return "";
        return tr("All-color aggregate unavailable: {0} at z{1} returned {2}. "
                        + "The selected-source preview can continue where its own evidence is usable.",
                failure.palette(), failure.zoom(), failure.status().name());
    }

    static String aggregateSourceWarning(LiveBPreviewService.Computed computed) {
        var sources = computed.captured().sourceRasters();
        if (sources == null) return "";
        return switch (sources.aggregateAvailability()) {
            case NOT_REQUESTED, COMPLETE -> "";
            case PALETTE_UNAVAILABLE -> aggregateSourceWarning(sources.aggregateFailure());
            case BUDGET_UNAVAILABLE -> tr("All-color aggregate unavailable: the required "
                    + "five-palette sampling frame exceeds the alignment input-pixel budget. "
                    + "The selected-source preview can continue where its own evidence is usable.");
        };
    }

    private void warnOptionalAggregateSource(LiveBPreviewService.Computed computed) {
        String warning = aggregateSourceWarning(computed);
        if (warning.isEmpty()) return;
        JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                warning,
                tr("WayHeatmapTracer source warning"), JOptionPane.WARNING_MESSAGE);
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

    private static void requirePreviewWithinDownloadedArea(List<EastNorth> preview, DataSet dataSet) {
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

    private static boolean isWithinDownloadedBounds(LatLon point, List<Bounds> bounds) {
        for (Bounds bound : bounds) {
            if (bound.contains(point)) {
                return true;
            }
        }
        return false;
    }
}
