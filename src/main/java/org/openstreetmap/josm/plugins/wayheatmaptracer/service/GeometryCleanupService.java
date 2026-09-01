package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateGeometryCleanup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupInterval;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupPointDisposition;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalPreviewCleanupContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.HeatmapConstrainedLaplacianResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.HeatmapConstrainedSimplificationResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProjectedLateralTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.PreviewNodeAssignmentPlanner;

/**
 * Produces at most one heatmap-constrained cleaned sibling for a raw final-preview candidate.
 *
 * <p>This service is pure. It never mutates JOSM data and never re-runs ridge tracking. Every
 * skipped, rejected, or unchanged request returns the raw candidate with a typed immutable
 * report. A cleaned sibling is created only after final-preview/profile reconciliation, protected
 * anchor preservation, constrained geometry processing, and fresh candidate-owned assignments.</p>
 */
public final class GeometryCleanupService {
    private final HeatmapConstrainedLaplacianSmoother smoother;
    private final HeatmapConstrainedSimplifier simplifier;

    /** Creates the default stateless cleanup pipeline. */
    public GeometryCleanupService() {
        this(new HeatmapConstrainedLaplacianSmoother(), new HeatmapConstrainedSimplifier());
    }

    /**
     * Creates a pipeline with explicit pure processing services, primarily for focused tests.
     *
     * @param smoother constrained Laplacian smoother
     * @param simplifier heatmap-constrained point reducer
     */
    public GeometryCleanupService(
        HeatmapConstrainedLaplacianSmoother smoother,
        HeatmapConstrainedSimplifier simplifier
    ) {
        this.smoother = Objects.requireNonNull(smoother, "smoother");
        this.simplifier = Objects.requireNonNull(simplifier, "simplifier");
    }

    /**
     * Expands one raw candidate into itself plus zero or one cleaned sibling.
     *
     * @param raw candidate after final-preview topology reconstruction
     * @param selection selected source segment
     * @param sourcePolyline immutable selected source geometry in node order
     * @param alignmentMode slide-time geometry application mode
     * @param trackerMode slide-time ridge tracker implementation
     * @param config slide-time cleanup configuration
     * @return raw candidate with an attempt report, followed only by a valid cleaned sibling
     */
    public List<CenterlineCandidate> expand(
        CenterlineCandidate raw,
        SelectionContext selection,
        List<EastNorth> sourcePolyline,
        AlignmentMode alignmentMode,
        TrackerMode trackerMode,
        GeometryCleanupConfig config
    ) {
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(config, "config");
        if (config.isDisabled() || !config.cleanedAlternativeRequested()) {
            return List.of(raw.withGeometryCleanup(report("", CandidateGeometryCleanup.Outcome.NOT_REQUESTED,
                "cleanup-disabled", List.of("cleanup-disabled"), raw.finalPreviewPoints().size(),
                raw.finalPreviewPoints().size(), raw.finalPreviewPoints().size(), null, null)));
        }
        if (alignmentMode != AlignmentMode.PRECISE_SHAPE) {
            return skipped(raw, "alignment-mode-ineligible", List.of("alignment-mode-ineligible"));
        }
        if (trackerMode != TrackerMode.CORRIDOR_AWARE) {
            return skipped(raw, "tracker-mode-ineligible", List.of("tracker-mode-ineligible"));
        }
        if (raw.geometryCleanup().cleanedCandidate()) {
            return skipped(raw, "already-cleaned", List.of("already-cleaned"));
        }

        FinalPreviewCleanupContext.CleanupReconciliation reconciliation =
            FinalPreviewCleanupContext.reconcile(raw, selection, sourcePolyline);
        if (!reconciliation.cleanable()) {
            String reason = reconciliation.status() == FinalPreviewCleanupContext.Status.NONADJACENT_PROTECTED_ANCHOR
                ? "no-eligible-cleanup-interval"
                : "context-" + reconciliation.status().name().toLowerCase(java.util.Locale.ROOT);
            return skipped(raw, reason, List.of(reconciliation.status().name()));
        }
        if (!reconciliation.globallyComplete()) {
            return expandLocally(raw, selection, sourcePolyline, config, reconciliation);
        }
        FinalPreviewCleanupContext context = reconciliation.slices().get(0).context();

        CleanupEligibilityPartitioner.Partition partition =
            new CleanupEligibilityPartitioner().partition(context);
        if (!hasEligibleInterval(partition, config.mode())) {
            return skipped(raw, "no-eligible-cleanup-interval", List.of("NO_ELIGIBLE_INTERVAL"));
        }

        HeatmapConstrainedLaplacianResult smoothing = smooth(context, partition, config);
        if (smoothing.status() == HeatmapConstrainedLaplacianResult.Status.REJECTED) {
            return rejected(raw, smoothing, null, "smoothing-rejected");
        }
        HeatmapConstrainedSimplificationResult reduction = simplify(
            smoothing.geometry(), context, partition, config);
        if (reduction.status() == HeatmapConstrainedSimplificationResult.Status.REJECTED) {
            return rejected(raw, smoothing, reduction, "reduction-rejected");
        }
        if (sameGeometry(raw.finalPreviewPoints(), reduction.geometry())) {
            CandidateGeometryCleanup unchanged = report(raw.id(), CandidateGeometryCleanup.Outcome.UNCHANGED,
                "cleanup-unchanged", reasons(smoothing, reduction), raw.finalPreviewPoints().size(),
                smoothing.geometry().size(), reduction.geometry().size(), smoothing, reduction);
            return List.of(raw.withGeometryCleanup(withGlobalIntervalSummary(
                unchanged, partition, context, config.mode(), reduction)));
        }

        try {
            Map<Long, EastNorth> freshAssignments = freezeExistingTopologyTargets(raw, selection, sourcePolyline,
                reduction.geometry(), PreviewNodeAssignmentPlanner.targetMap(
                    PreviewNodeAssignmentPlanner.preciseAssignments(selection, sourcePolyline, reduction.geometry())));
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateCleanupEvidence cleanedEvidence =
                reducedEvidence(context, reduction.retainedSourceIndexes());
            boolean partial = partiallyApplied(partition, smoothing, reduction);
            CandidateGeometryCleanup availableReport = withGlobalIntervalSummary(report(raw.id(),
                CandidateGeometryCleanup.Outcome.CLEANED_ALTERNATIVE_AVAILABLE, "cleaned-sibling-created",
                reasons(smoothing, reduction),
                raw.finalPreviewPoints().size(), smoothing.geometry().size(), reduction.geometry().size(),
                smoothing, reduction), partition, context, config.mode(), reduction);
            CandidateGeometryCleanup cleanedReport = withGlobalIntervalSummary(report(raw.id(), partial
                    ? CandidateGeometryCleanup.Outcome.PARTIALLY_CLEANED
                    : CandidateGeometryCleanup.Outcome.CLEANED,
                partial ? "cleanup-partially-applied" : "cleanup-applied",
                reasons(smoothing, reduction), raw.finalPreviewPoints().size(),
                smoothing.geometry().size(), reduction.geometry().size(), smoothing, reduction),
                partition, context, config.mode(), reduction);
            CenterlineCandidate reportedRaw = raw.withGeometryCleanup(availableReport);
            CenterlineCandidate cleaned = raw.withId(raw.id() + "#cleaned")
                .withProjectedGeometryAndOffsets(
                    reduction.geometry(), projectedOffsets(reduction.geometry(), cleanedEvidence))
                .withFinalPreviewGeometry(reduction.geometry(), freshAssignments)
                .withCleanupEvidence(cleanedEvidence)
                .withGeometryCleanup(cleanedReport);
            return List.of(reportedRaw, cleaned);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return rejected(raw, smoothing, reduction, "fresh-assignment-rejected");
        }
    }

    private static boolean partiallyApplied(
        CleanupEligibilityPartitioner.Partition partition,
        HeatmapConstrainedLaplacianResult smoothing,
        HeatmapConstrainedSimplificationResult reduction
    ) {
        boolean frozenEvidence = partition.dispositions().stream().anyMatch(disposition ->
            disposition != CleanupPointDisposition.DIRECT_USABLE
                && disposition != CleanupPointDisposition.PROTECTED_ANCHOR);
        return frozenEvidence || smoothing.failureReasons().stream().anyMatch(reason ->
            reason == HeatmapConstrainedLaplacianResult.FailureReason.BACKTRACK_LIMIT_REACHED
                || reason == HeatmapConstrainedLaplacianResult.FailureReason.NO_AUTHORIZED_MOVEMENT)
            || reductionPartiallyApplied(partition, reduction);
    }

    /** Returns whether one eligible interval reduced while another rejected interval stayed exact. */
    private static boolean reductionPartiallyApplied(
        CleanupEligibilityPartitioner.Partition partition,
        HeatmapConstrainedSimplificationResult reduction
    ) {
        boolean changedInterval = false;
        boolean rejectedUnchangedInterval = false;
        for (CleanupInterval interval : partition.intervals()) {
            if (!interval.simplificationEligible()) {
                continue;
            }
            boolean removed = false;
            for (int index = interval.startIndex() + 1; index < interval.endIndex(); index++) {
                if (!reduction.retainedSourceIndexes().contains(index)) {
                    removed = true;
                    break;
                }
            }
            boolean rejected = reduction.chordRejections().stream().anyMatch(rejection ->
                rejection.startSourceIndex() >= interval.startIndex()
                    && rejection.endSourceIndex() <= interval.endIndex());
            changedInterval |= removed;
            rejectedUnchangedInterval |= rejected && !removed;
        }
        return changedInterval && rejectedUnchangedInterval;
    }

    private static boolean hasEligibleInterval(
        CleanupEligibilityPartitioner.Partition partition,
        GeometryCleanupMode mode
    ) {
        return partition.intervals().stream().anyMatch(interval -> intervalEligible(interval, mode));
    }


    private static boolean intervalEligible(CleanupInterval interval, GeometryCleanupMode mode) {
        return mode == GeometryCleanupMode.REDUCE_POINTS_ONLY
            ? interval.simplificationEligible()
            : interval.smoothingEligible() || interval.simplificationEligible();
    }

    private static CandidateGeometryCleanup withGlobalIntervalSummary(
        CandidateGeometryCleanup report,
        CleanupEligibilityPartitioner.Partition partition,
        FinalPreviewCleanupContext context,
        GeometryCleanupMode mode,
        HeatmapConstrainedSimplificationResult reduction
    ) {
        int[] outputBySource = new int[context.geometry().size()];
        java.util.Arrays.fill(outputBySource, -1);
        for (int outputIndex = 0; outputIndex < reduction.retainedSourceIndexes().size(); outputIndex++) {
            int sourceIndex = reduction.retainedSourceIndexes().get(outputIndex);
            if (sourceIndex < 0 || sourceIndex >= outputBySource.length || outputBySource[sourceIndex] >= 0) {
                throw new IllegalStateException("Cleanup result contains invalid retained source indexes");
            }
            outputBySource[sourceIndex] = outputIndex;
        }
        int eligibleIntervals = 0;
        int changedIntervals = 0;
        for (CleanupInterval interval : partition.intervals()) {
            if (!intervalEligible(interval, mode)) {
                continue;
            }
            eligibleIntervals++;
            boolean changed = false;
            for (int sourceIndex = interval.startIndex() + 1;
                sourceIndex < interval.endIndex() && !changed; sourceIndex++) {
                int outputIndex = outputBySource[sourceIndex];
                changed = outputIndex < 0
                    || context.geometry().get(sourceIndex).distance(reduction.geometry().get(outputIndex)) > 1e-9;
            }
            if (changed) {
                changedIntervals++;
            }
        }
        int frozenIntervals = contiguousInternalRuns(
            partition.immutableIndexes(), context.geometry().size());
        return report.withIntervalSummary(eligibleIntervals, changedIntervals, frozenIntervals);
    }

    private static int contiguousInternalRuns(java.util.Set<Integer> indexes, int geometrySize) {
        int runs = 0;
        int previous = Integer.MIN_VALUE;
        for (int index : indexes.stream().filter(value -> value > 0 && value < geometrySize - 1)
            .sorted().toList()) {
            if (previous == Integer.MIN_VALUE || index != previous + 1) {
                runs++;
            }
            previous = index;
        }
        return runs;
    }
    private List<CenterlineCandidate> expandLocally(
        CenterlineCandidate raw,
        SelectionContext selection,
        List<EastNorth> sourcePolyline,
        GeometryCleanupConfig config,
        FinalPreviewCleanupContext.CleanupReconciliation reconciliation
    ) {
        List<EastNorth> merged = new ArrayList<>(reconciliation.geometry());
        boolean[] retained = new boolean[merged.size()];
        java.util.Arrays.fill(retained, true);
        List<SliceAttempt> attempts = new ArrayList<>();
        boolean changed = false;
        for (FinalPreviewCleanupContext.CleanupSlice slice : reconciliation.slices()) {
            FinalPreviewCleanupContext context = slice.context();
            CleanupEligibilityPartitioner.Partition partition = new CleanupEligibilityPartitioner().partition(context);
            if (!hasEligibleInterval(partition, config.mode())) {
                continue;
            }
            HeatmapConstrainedLaplacianResult smoothing = smooth(context, partition, config);
            if (smoothing.status() == HeatmapConstrainedLaplacianResult.Status.REJECTED) {
                attempts.add(SliceAttempt.rejected(slice, smoothing, null));
                continue;
            }
            HeatmapConstrainedSimplificationResult reduction = simplify(
                smoothing.geometry(), context, partition, config);
            if (reduction.status() == HeatmapConstrainedSimplificationResult.Status.REJECTED) {
                attempts.add(SliceAttempt.rejected(slice, smoothing, reduction));
                continue;
            }
            attempts.add(SliceAttempt.accepted(slice, smoothing, reduction));
            for (int outputIndex = 0; outputIndex < reduction.retainedSourceIndexes().size(); outputIndex++) {
                int localIndex = reduction.retainedSourceIndexes().get(outputIndex);
                int globalIndex = slice.geometryIndexes().get(localIndex);
                merged.set(globalIndex, reduction.geometry().get(outputIndex));
            }
            for (int localIndex = 0; localIndex < slice.geometryIndexes().size(); localIndex++) {
                if (!reduction.retainedSourceIndexes().contains(localIndex)) {
                    retained[slice.geometryIndexes().get(localIndex)] = false;
                    changed = true;
                }
            }
            changed |= !sameGeometry(context.geometry(), reduction.geometry());
        }
        if (attempts.isEmpty()) {
            CandidateGeometryCleanup report = report(raw.id(), CandidateGeometryCleanup.Outcome.SKIPPED,
                "no-eligible-cleanup-interval", List.of("NO_ELIGIBLE_INTERVAL"),
                raw.finalPreviewPoints().size(), raw.finalPreviewPoints().size(),
                raw.finalPreviewPoints().size(), null, null);
            return List.of(raw.withGeometryCleanup(
                withLocalIntervalSummary(report, reconciliation, List.of())));
        }
        if (attempts.stream().noneMatch(SliceAttempt::accepted)) {
            CandidateGeometryCleanup rejected = report(raw.id(), CandidateGeometryCleanup.Outcome.REJECTED,
                "local-intervals-rejected", localReasons(attempts), raw.finalPreviewPoints().size(),
                raw.finalPreviewPoints().size(), raw.finalPreviewPoints().size(), null, null);
            return List.of(raw.withGeometryCleanup(
                withLocalIntervalSummary(rejected, reconciliation, attempts)));
        }
        if (!changed) {
            return List.of(raw.withGeometryCleanup(withLocalIntervalSummary(
                report(raw.id(), CandidateGeometryCleanup.Outcome.UNCHANGED,
                "cleanup-unchanged", localReasons(attempts), raw.finalPreviewPoints().size(),
                raw.finalPreviewPoints().size(), raw.finalPreviewPoints().size(), null, null), reconciliation, attempts)));
        }
        List<EastNorth> cleanedGeometry = new ArrayList<>();
        for (int index = 0; index < merged.size(); index++) {
            if (retained[index]) {
                cleanedGeometry.add(merged.get(index));
            }
        }
        try {
            Map<Long, EastNorth> freshAssignments = freezeExistingTopologyTargets(raw, selection, sourcePolyline,
                cleanedGeometry, PreviewNodeAssignmentPlanner.targetMap(
                    PreviewNodeAssignmentPlanner.preciseAssignments(selection, sourcePolyline, cleanedGeometry)));
            SliceAttempt representative = attempts.stream().filter(SliceAttempt::accepted).findFirst().orElseThrow();
            CenterlineCandidate reportedRaw = raw.withGeometryCleanup(withLocalIntervalSummary(report(raw.id(),
                CandidateGeometryCleanup.Outcome.CLEANED_ALTERNATIVE_AVAILABLE, "cleaned-sibling-created",
                localReasons(attempts), raw.finalPreviewPoints().size(), raw.finalPreviewPoints().size(),
                cleanedGeometry.size(), representative.smoothing(), representative.reduction()),
                reconciliation, attempts));
            CenterlineCandidate cleaned = raw.withId(raw.id() + "#cleaned")
                .withFinalPreviewGeometry(cleanedGeometry, freshAssignments)
                .withCleanupEvidence(org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateCleanupEvidence.empty())
                .withGeometryCleanup(withLocalIntervalSummary(report(raw.id(), CandidateGeometryCleanup.Outcome.PARTIALLY_CLEANED,
                    "cleanup-partially-applied", localReasons(attempts), raw.finalPreviewPoints().size(),
                    raw.finalPreviewPoints().size(), cleanedGeometry.size(), representative.smoothing(),
                    representative.reduction()), reconciliation, attempts));
            return List.of(reportedRaw, cleaned);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return rejected(raw, null, null, "fresh-assignment-rejected");
        }
    }

    private static List<String> localReasons(List<SliceAttempt> attempts) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        result.add("NONADJACENT_PROTECTED_ANCHOR");
        for (SliceAttempt attempt : attempts) {
            result.addAll(reasons(attempt.smoothing(), attempt.reduction()));
            if (!attempt.accepted()) {
                result.add("local-interval-rejected");
            }
        }
        return List.copyOf(result);
    }

    private static CandidateGeometryCleanup withLocalIntervalSummary(
        CandidateGeometryCleanup report,
        FinalPreviewCleanupContext.CleanupReconciliation reconciliation,
        List<SliceAttempt> attempts
    ) {
        int changedIntervals = (int) attempts.stream().filter(SliceAttempt::changed).count();
        List<Integer> frozen = reconciliation.frozenIndexes().stream().sorted().toList();
        int frozenIntervals = 0;
        int previous = Integer.MIN_VALUE;
        for (int index : frozen) {
            if (previous == Integer.MIN_VALUE || index != previous + 1) {
                frozenIntervals++;
            }
            previous = index;
        }
        return report.withIntervalSummary(attempts.size(), changedIntervals, frozenIntervals);
    }

    private record SliceAttempt(
        FinalPreviewCleanupContext.CleanupSlice slice,
        HeatmapConstrainedLaplacianResult smoothing,
        HeatmapConstrainedSimplificationResult reduction,
        boolean accepted,
        boolean changed
    ) {
        private static SliceAttempt accepted(
            FinalPreviewCleanupContext.CleanupSlice slice,
            HeatmapConstrainedLaplacianResult smoothing,
            HeatmapConstrainedSimplificationResult reduction
        ) {
            return new SliceAttempt(slice, smoothing, reduction, true,
                !sameGeometry(slice.context().geometry(), reduction.geometry()));
        }

        private static SliceAttempt rejected(
            FinalPreviewCleanupContext.CleanupSlice slice,
            HeatmapConstrainedLaplacianResult smoothing,
            HeatmapConstrainedSimplificationResult reduction
        ) {
            return new SliceAttempt(slice, smoothing, reduction, false, false);
        }
    }

    private HeatmapConstrainedLaplacianResult smooth(
        FinalPreviewCleanupContext context,
        CleanupEligibilityPartitioner.Partition partition,
        GeometryCleanupConfig config
    ) {
        if (config.mode() != GeometryCleanupMode.CONSTRAINED_SMOOTH_AND_REDUCE) {
            return unchangedSmoothing(context.geometry(), partition.immutableIndexes().size(),
                HeatmapConstrainedLaplacianResult.FailureReason.MODE_DISABLED);
        }
        List<HeatmapConstrainedLaplacianSmoother.ProtectedInterval> intervals = smoothingIntervals(partition);
        if (intervals.isEmpty()) {
            return unchangedSmoothing(context.geometry(), partition.immutableIndexes().size(),
                HeatmapConstrainedLaplacianResult.FailureReason.NO_AUTHORIZED_MOVEMENT);
        }
        return smoother.smooth(context.geometry(), intervals, partition.immutableIndexes(), context.evidence(), config);
    }

    private HeatmapConstrainedSimplificationResult simplify(
        List<EastNorth> geometry,
        FinalPreviewCleanupContext context,
        CleanupEligibilityPartitioner.Partition partition,
        GeometryCleanupConfig config
    ) {
        List<HeatmapConstrainedSimplifier.ProtectedInterval> intervals = simplifierIntervals(partition);
        if (intervals.isEmpty()) {
            return unchangedSimplification(geometry, partition.immutableIndexes().size());
        }
        return simplifier.simplify(geometry, intervals, partition.immutableIndexes(), context.evidence(), config);
    }

    private static List<HeatmapConstrainedLaplacianSmoother.ProtectedInterval> smoothingIntervals(
        CleanupEligibilityPartitioner.Partition partition
    ) {
        List<CleanupInterval> eligible = partition.intervals().stream()
            .filter(CleanupInterval::smoothingEligible)
            .toList();
        return eligible.stream()
            .map(interval -> new HeatmapConstrainedLaplacianSmoother.ProtectedInterval(
                interval.startIndex(), interval.endIndex()))
            .toList();
    }

    private static List<HeatmapConstrainedSimplifier.ProtectedInterval> simplifierIntervals(
        CleanupEligibilityPartitioner.Partition partition
    ) {
        List<CleanupInterval> eligible = partition.intervals().stream()
            .filter(CleanupInterval::simplificationEligible)
            .toList();
        return eligible.stream()
            .map(interval -> new HeatmapConstrainedSimplifier.ProtectedInterval(
                interval.startIndex(), interval.endIndex()))
            .toList();
    }

    private static HeatmapConstrainedLaplacianResult unchangedSmoothing(
        List<EastNorth> geometry,
        int protectedPointCount,
        HeatmapConstrainedLaplacianResult.FailureReason reason
    ) {
        return new HeatmapConstrainedLaplacianResult(geometry,
            HeatmapConstrainedLaplacianResult.Status.UNCHANGED,
            List.of(reason),
            new HeatmapConstrainedLaplacianResult.Metrics(geometry.size(), 0, 0,
                protectedPointCount, 0, 0, 0, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0));
    }

    private static HeatmapConstrainedSimplificationResult unchangedSimplification(
        List<EastNorth> geometry,
        int protectedPointCount
    ) {
        List<Integer> retained = new ArrayList<>(geometry.size());
        for (int index = 0; index < geometry.size(); index++) {
            retained.add(index);
        }
        return new HeatmapConstrainedSimplificationResult(geometry, retained,
            HeatmapConstrainedSimplificationResult.Status.UNCHANGED,
            List.of(HeatmapConstrainedSimplificationResult.FailureReason.NO_DIRECT_AUTHORIZATION),
            List.of(), new HeatmapConstrainedSimplificationResult.Metrics(
                geometry.size(), geometry.size(), protectedPointCount, 0, 0, 0, 0,
                OptionalDouble.empty(), OptionalDouble.empty(), 1.0));
    }

    private static List<CenterlineCandidate> skipped(CenterlineCandidate raw, String reason, List<String> reasons) {
        int points = raw.finalPreviewPoints().size();
        return List.of(raw.withGeometryCleanup(report(raw.id(), CandidateGeometryCleanup.Outcome.SKIPPED,
            reason, reasons, points, points, points, null, null)));
    }

    private static List<CenterlineCandidate> rejected(
        CenterlineCandidate raw,
        HeatmapConstrainedLaplacianResult smoothing,
        HeatmapConstrainedSimplificationResult reduction,
        String reason
    ) {
        int smoothedCount = smoothing == null ? raw.finalPreviewPoints().size() : smoothing.geometry().size();
        int finalCount = reduction == null ? smoothedCount : reduction.geometry().size();
        return List.of(raw.withGeometryCleanup(report(raw.id(), CandidateGeometryCleanup.Outcome.REJECTED,
            reason, reasons(smoothing, reduction), raw.finalPreviewPoints().size(), smoothedCount, finalCount,
            smoothing, reduction)));
    }

    private static CandidateGeometryCleanup report(
        String parentId,
        CandidateGeometryCleanup.Outcome outcome,
        String reason,
        List<String> reasons,
        int before,
        int smoothed,
        int after,
        HeatmapConstrainedLaplacianResult smoothing,
        HeatmapConstrainedSimplificationResult reduction
    ) {
        HeatmapConstrainedLaplacianResult.Metrics smoothingMetrics = smoothing == null ? null : smoothing.metrics();
        HeatmapConstrainedSimplificationResult.Metrics reductionMetrics = reduction == null ? null : reduction.metrics();
        return new CandidateGeometryCleanup(parentId, outcome, reason, reasons, before, smoothed, after,
            smoothingMetrics == null ? 0 : smoothingMetrics.acceptedPassCount(),
            smoothingMetrics == null ? 0 : smoothingMetrics.backtrackCount(),
            reductionMetrics == null ? 0 : reductionMetrics.attemptedChordCount(),
            reductionMetrics == null ? 0 : reductionMetrics.acceptedChordCount(),
            (smoothingMetrics == null ? 0 : smoothingMetrics.containmentFailureCount())
                + (reductionMetrics == null ? 0 : reductionMetrics.containmentFailureCount()),
            smoothingMetrics == null ? 1.0 : smoothingMetrics.fitBefore(),
            smoothingMetrics == null ? 1.0 : smoothingMetrics.fitAfter(),
            smoothingMetrics == null ? 0.0 : smoothingMetrics.maximumDisplacementProjectionUnits(),
            reductionMetrics == null ? OptionalDouble.empty() : reductionMetrics.maximumRemovedPointDeviationMeters(),
            reductionMetrics == null ? OptionalDouble.empty() : reductionMetrics.worstFitRetention());
    }

    private static List<String> reasons(
        HeatmapConstrainedLaplacianResult smoothing,
        HeatmapConstrainedSimplificationResult reduction
    ) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (smoothing != null) {
            smoothing.failureReasons().forEach(reason -> result.add("smoothing-" + reason.name()));
        }
        if (reduction != null) {
            reduction.failureReasons().forEach(reason -> result.add("reduction-" + reason.name()));
        }
        return List.copyOf(result);
    }

    private static org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateCleanupEvidence reducedEvidence(
        FinalPreviewCleanupContext context,
        List<Integer> retainedContextIndexes
    ) {
        return context.retainedEvidence(retainedContextIndexes);
    }

    private static List<Double> projectedOffsets(
        List<EastNorth> geometry,
        org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateCleanupEvidence evidence
    ) {
        if (geometry.size() != evidence.samplingFrame().profiles().size()) {
            throw new IllegalArgumentException("Cleaned geometry and retained evidence must align");
        }
        java.util.ArrayList<Double> offsets = new java.util.ArrayList<>(geometry.size());
        for (int index = 0; index < geometry.size(); index++) {
            ProjectedLateralTransform transform = evidence.samplingFrame().profiles().get(index)
                .projectedLateralTransform();
            double east = transform.eastPerRasterPixel();
            double north = transform.northPerRasterPixel();
            double denominator = east * east + north * north;
            if (!(denominator > 0.0) || !Double.isFinite(denominator)) {
                throw new IllegalArgumentException("Cleaned evidence contains an invalid projected transform");
            }
            EastNorth point = geometry.get(index);
            offsets.add(((point.east() - transform.zeroOffset().east()) * east
                + (point.north() - transform.zeroOffset().north()) * north) / denominator);
        }
        return List.copyOf(offsets);
    }

    private static Map<Long, EastNorth> freezeExistingTopologyTargets(
        CenterlineCandidate raw,
        SelectionContext selection,
        List<EastNorth> sourcePolyline,
        List<EastNorth> cleanedPreview,
        Map<Long, EastNorth> freshAssignments
    ) {
        Map<Long, EastNorth> result = new LinkedHashMap<>(freshAssignments);
        int last = selection.segmentNodes().size() - 1;
        for (int index = 0; index <= last; index++) {
            org.openstreetmap.josm.data.osm.Node node = selection.segmentNodes().get(index);
            boolean topologyAnchor = index == 0 || index == last || node.hasKeys()
                || node.getReferrers().stream().anyMatch(referrer -> referrer != selection.way());
            if (!topologyAnchor || selection.fixedNodes().contains(node)) {
                continue;
            }
            EastNorth target = raw.proposedNodePositions().get(node.getUniqueId());
            if (target == null || !containsExact(cleanedPreview, target)) {
                throw new IllegalStateException("Cleaned preview does not preserve a proposed topology target");
            }
            EastNorth commandTarget = result.get(node.getUniqueId());
            if (commandTarget == null || commandTarget.distance(target) > 1e-7) {
                throw new IllegalStateException(
                    "Cleaned preview changes the command-owned topology target");
            }
        }
        return Map.copyOf(result);
    }

    private static boolean containsExact(List<EastNorth> geometry, EastNorth target) {
        return geometry.stream().anyMatch(point -> point.distance(target) <= 1e-7);
    }

    private static boolean sameGeometry(List<EastNorth> left, List<EastNorth> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            if (left.get(index).distance(right.get(index)) > 1e-9) {
                return false;
            }
        }
        return true;
    }
}
