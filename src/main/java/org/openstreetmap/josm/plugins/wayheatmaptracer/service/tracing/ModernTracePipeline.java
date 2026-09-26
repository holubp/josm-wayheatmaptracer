package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.ExistingWayNodeOccurrence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.GeneratedCandidatePoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesis;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.CandidateRankingPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageSupportedLocalCleanup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageSupportedRefitter;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.image.DirectionalImageTraceEngine;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.EvidenceModelParameters;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticTraceEngine;

/**
 * Runs one modern detached engine through common image refinement, final-geometry assessment,
 * and physical cross-engine ranking.
 *
 * <p>This class has no JOSM live-object dependency. Capture and edit-plan construction remain
 * separate ownership boundaries, which makes the numerical pipeline replayable and cancellable.</p>
 */
public final class ModernTracePipeline {
    private static final double FRACTION_TOLERANCE = 1.0e-9;
    /** Immutable pipeline options derived from one slide-time configuration. */
    public record Options(String fieldName, GeometryCleanupConfig cleanup,
            String sourceTier, int mappingPreference) {
        /** Validates common final-processing settings. */
        public Options {
            if (fieldName == null || fieldName.isBlank() || cleanup == null
                    || sourceTier == null || sourceTier.isBlank() || mappingPreference < 0) {
                throw new IllegalArgumentException("Modern pipeline options are incomplete");
            }
        }
    }

    /** One final route and the common physical evidence used to rank it. */
    public record Route(TraceHypothesis rawHypothesis, TraceHypothesis hypothesis,
            List<FinalRoutePointId> pointIds, Map<FinalRoutePointId, MetricPoint> assignments,
            Map<FinalRoutePointId, ObservationOwnership> sourceOwnership,
            FinalGeometryEvaluator.Result quality,
            ImageSupportedLocalCleanup.Status cleanupStatus, boolean geometryChanged) {
        /** Validates aligned immutable raw/final geometry, identity and source provenance. */
        public Route {
            if (rawHypothesis == null || hypothesis == null || pointIds == null
                    || assignments == null || sourceOwnership == null || quality == null
                    || cleanupStatus == null || !quality.id().equals(hypothesis.id())
                    || !rawHypothesis.id().equals(hypothesis.id())) {
                throw new IllegalArgumentException("Modern final route is inconsistent");
            }
            pointIds = List.copyOf(pointIds);
            assignments = Map.copyOf(new LinkedHashMap<>(assignments));
            sourceOwnership = Map.copyOf(new LinkedHashMap<>(sourceOwnership));
            boolean aligned = pointIds.size() == hypothesis.points().size()
                    && new LinkedHashSet<>(pointIds).size() == pointIds.size()
                    && assignments.keySet().equals(new LinkedHashSet<>(pointIds))
                    && sourceOwnership.keySet().equals(new LinkedHashSet<>(pointIds));
            for (int index = 0; aligned && index < pointIds.size(); index++) {
                aligned = hypothesis.points().get(index).equals(assignments.get(pointIds.get(index)));
            }
            if (!aligned) {
                throw new IllegalArgumentException("Modern final route provenance is incomplete or reordered");
            }
        }

        /** Returns complete candidate-owned assignments for retained existing occurrences. */
        public Map<ExistingWayNodeOccurrence, MetricPoint> existingAssignments() {
            Map<ExistingWayNodeOccurrence, MetricPoint> result = new LinkedHashMap<>();
            for (FinalRoutePointId id : pointIds) {
                if (id instanceof ExistingWayNodeOccurrence existing) {
                    result.put(existing, assignments.get(id));
                }
            }
            return Map.copyOf(result);
        }
    }

    /** Engine result plus final routes ordered by common physical quality. */
    public record Result(TraceHypothesisSet inference, List<Route> routes) {
        /** Copies the ranked route list. */
        public Result {
            if (inference == null || routes == null) {
                throw new IllegalArgumentException("Modern pipeline result is incomplete");
            }
            routes = List.copyOf(routes);
        }
    }

    private final TraceEngine corridorProposalEngine;

    /**
     * Creates a pipeline with an explicit detached A proposal engine.
     *
     * @param corridorProposalEngine detached A adapter used by A and Hybrid
     */
    public ModernTracePipeline(TraceEngine corridorProposalEngine) {
        if (corridorProposalEngine == null) {
            throw new IllegalArgumentException("A proposal engine is required");
        }
        this.corridorProposalEngine = corridorProposalEngine;
    }

    /**
     * Runs inference and every common post-inference stage on immutable inputs.
     *
     *  request frozen engine request and budgets
     *  evidence frozen scalar raster evidence
     *  network frozen OSM network closure
     *  options common refinement and ranking options
     *  cancellation cooperative cancellation boundary
     *  inference diagnostics and physically ranked final routes
     */
    public Result run(TraceRequest request, EvidenceSnapshot evidence, NetworkSnapshot network,
            Options options, CancellationProbe cancellation) {
        return run(request, evidence, network, options, cancellation, Set.of());
    }

    /** Runs production inference with additional original occurrences held as exact trace anchors. */
    public Result run(TraceRequest request, EvidenceSnapshot evidence, NetworkSnapshot network,
            Options options, CancellationProbe cancellation, Set<Integer> fixedOccurrences) {
        if (request == null || evidence == null || network == null || options == null
                || cancellation == null || fixedOccurrences == null
                || fixedOccurrences.stream().anyMatch(index -> index == null
                        || index < request.selectedRange().firstIndex()
                        || index > request.selectedRange().lastIndex())) {
            throw new IllegalArgumentException("Modern pipeline inputs are incomplete");
        }
        Set<Integer> exactFixed = Set.copyOf(fixedOccurrences);
        if (!exactFixed.isEmpty()) {
            DetachedPrimitive selected = network.primitives().get(request.selectedWayKey());
            if (!(selected instanceof DetachedWay way)
                    || request.selectedRange().lastIndex() >= way.nodeKeys().size()) {
                throw new IllegalArgumentException("Fixed interval occurrences lack a captured selected way");
            }
            for (int index : exactFixed) {
                PrimitiveKey key = way.nodeKeys().get(index);
                if (!(network.primitives().get(key) instanceof DetachedNode)
                        || java.util.Collections.frequency(way.nodeKeys(), key) != 1) {
                    throw new IllegalArgumentException("Fixed interval occurrence identity is ambiguous");
                }
            }
        }
        TraceEngine engine = engine(request.engine(), options.fieldName());
        long inferenceStarted = System.nanoTime();
        TraceHypothesisSet inference = engine.trace(request, evidence, network, cancellation);
        long inferenceNanos = System.nanoTime() - inferenceStarted;
        if (inference.hypotheses().isEmpty()) {
            return new Result(inference, List.of());
        }
        ScalarEvidenceField scalar = evidence.fields().get(options.fieldName());
        if (scalar == null) {
            throw new IllegalArgumentException("Configured scalar field is unavailable");
        }
        double pitch = evidence.resolution().effectivePitchMeters();
        ImageCostField image = new ImageCostField(scalar, evidence.transform(),
                evidence.decisionRegion(), pitch);
        List<Route> routes = new ArrayList<>();
        long finalizationNanos = 0L;
        for (TraceHypothesis hypothesis : inference.hypotheses()) {
            cancellation.checkpoint();
            long routeStarted = System.nanoTime();
            routes.add(finalizeRoute(hypothesis, inference, image, evidence, network,
                    request, options, pitch, cancellation, exactFixed));
            finalizationNanos += System.nanoTime() - routeStarted;
        }
        long rankingStarted = System.nanoTime();
        List<Route> ranked = rank(routes, request.engine(), options);
        long rankingNanos = System.nanoTime() - rankingStarted;
        if (request.engine() == TrackerMode.PROBABILISTIC) {
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
                "pipeline.inferenceMs", millis(inferenceNanos));
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
                "pipeline.finalizationMs", millis(finalizationNanos));
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
                "pipeline.rankingMs", millis(rankingNanos));
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
                "pipeline.routes", ranked.size());
        }
        return new Result(inference, ranked);
    }

    private static long millis(long nanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    private TraceEngine engine(TrackerMode mode, String fieldName) {
        return switch (mode) {
            case CORRIDOR_AWARE -> corridorProposalEngine;
            case PROBABILISTIC -> new ProbabilisticTraceEngine(fieldName);
            case HYBRID -> new HybridTraceEngine(corridorProposalEngine,
                    new ProbabilisticTraceEngine(fieldName, EvidenceModelParameters.defaults(),
                        ProbabilisticTraceEngine.ReliabilityPolicy.BASELINE));
            case DIRECTIONAL_IMAGE -> new DirectionalImageTraceEngine(fieldName);
            case LEGACY_V02 -> throw new IllegalArgumentException(
                    "Legacy tracing does not use the modern detached pipeline");
        };
    }

    private static Route finalizeRoute(TraceHypothesis source, TraceHypothesisSet inference,
            ImageCostField image, EvidenceSnapshot evidence, NetworkSnapshot network,
            TraceRequest request, Options options, double pitch, CancellationProbe cancellation,
            Set<Integer> fixedOccurrences) {
        SeedGeometry seed = seedGeometry(source, evidence, network, request, fixedOccurrences);
        ImageSupportedLocalCleanup.Mode mode = request.geometryMode()
                == AlignmentMode.MOVE_EXISTING_NODES
                || request.engine() == TrackerMode.PROBABILISTIC
                ? ImageSupportedLocalCleanup.Mode.OFF
                : cleanupMode(options.cleanup().mode());
        long cleanupStarted = System.nanoTime();
        ImageSupportedLocalCleanup.Result cleanup = new ImageSupportedLocalCleanup().clean(
                new ImageSupportedLocalCleanup.Request(seed.pointIds(), seed.points(),
                    seed.retainedIndices(), seed.protectedIndices(), image,
                    evidence.decisionRegion(), mode, ImageSupportedRefitter.Config.defaults(pitch),
                    options.cleanup().simplificationDeviationMeters(), seed.assignments(), List.of()),
                cancellation);
        long cleanupNanos = System.nanoTime() - cleanupStarted;
        cancellation.checkpoint();
        validateFinalProvenance(seed, cleanup, network.closure().removableExistingNodeKeys());
        List<FinalRoutePointId> finalIds = cleanup.occurrenceIds();
        Map<FinalRoutePointId, MetricPoint> finalAssignments = cleanup.assignments();
        List<MetricPoint> finalPoints = cleanup.points();
        boolean preciseShapeRequired = false;
        if (request.geometryMode() == AlignmentMode.MOVE_EXISTING_NODES) {
            finalIds = cleanup.occurrenceIds().stream()
                    .filter(ExistingWayNodeOccurrence.class::isInstance).toList();
            Map<FinalRoutePointId, MetricPoint> sparseAssignments = new LinkedHashMap<>();
            for (FinalRoutePointId id : finalIds) {
                sparseAssignments.put(id, cleanup.assignments().get(id));
            }
            finalAssignments = Map.copyOf(sparseAssignments);
            finalPoints = finalIds.stream().map(finalAssignments::get).toList();
            preciseShapeRequired = maximumDistanceToPolyline(cleanup.points(), finalPoints)
                    > pitch + 1.0e-9;
        }
        Map<FinalRoutePointId, ObservationOwnership> retainedSource = new LinkedHashMap<>();
        for (FinalRoutePointId id : finalIds) {
            retainedSource.put(id, seed.sourceOwnership().get(id));
        }
        Map<Integer, MetricPoint> protectedAssignments = protectedAssignments(seed,
                finalIds, finalAssignments);
        long supportStarted = System.nanoTime();
        List<ObservationOwnership> support = freshSupport(finalPoints,
                protectedAssignments.keySet(), image);
        long supportNanos = System.nanoTime() - supportStarted;
        boolean changed = !finalPoints.equals(source.points());
        Map<String, Double> diagnostics = new LinkedHashMap<>(source.diagnostics());
        diagnostics.put("commonFinalProcessing", 1.0);
        diagnostics.put("cleanupChanged", changed ? 1.0 : 0.0);
        diagnostics.put("cleanupStatus", (double) cleanup.status().ordinal());
        if (request.engine() == TrackerMode.PROBABILISTIC && !options.cleanup().isDisabled()) {
            diagnostics.put("cleanupSuppressedForDirectReliability", 1.0);
        }
        diagnostics.put("retainedExistingOccurrences",
                (double) cleanup.occurrenceIds().stream()
                    .filter(ExistingWayNodeOccurrence.class::isInstance).count());
        diagnostics.put("protectedExistingOccurrences", (double) protectedAssignments.size());
        TraceHypothesis finalized = new TraceHypothesis(source.id(), source.branchSignature(),
                finalPoints, support, source.objective(), source.posteriorProbability(), diagnostics);
        long finalGeometryStarted = System.nanoTime();
        FinalGeometryEvaluator.Result quality = new FinalGeometryEvaluator().evaluate(
                new FinalGeometryEvaluator.Request(finalized.id(), finalized.points(),
                    finalIds, image, pitch, protectedAssignments, List.of(), changed,
                    inference.status() == TraceHypothesisSet.Status.AMBIGUOUS,
                    inference.alternativesTruncated(),
                    inference.status() == TraceHypothesisSet.Status.RESOURCE_LIMIT));
        if (preciseShapeRequired) {
            List<FinalGeometryEvaluator.Finding> findings = new ArrayList<>(quality.findings());
            findings.add(new FinalGeometryEvaluator.Finding(
                    FinalGeometryEvaluator.FindingCode.PRECISE_SHAPE_REQUIRED,
                    FinalGeometryEvaluator.Severity.HARD_BLOCK, 0, finalPoints.size() - 1,
                    maximumDistanceToPolyline(cleanup.points(), finalPoints)));
            quality = new FinalGeometryEvaluator.Result(quality.id(),
                    FinalGeometryEvaluator.Disposition.HARD_BLOCKED, findings,
                    quality.totalLengthMeters(), quality.directlySupportedLengthMeters(),
                    quality.worstUnsupportedSpanMeters(), quality.meanImageCenterCost(),
                    quality.bendPreservingRoughness());
        }
        long finalGeometryNanos = System.nanoTime() - finalGeometryStarted;
        if (request.engine() == TrackerMode.PROBABILISTIC) {
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.add(
                "final.cleanupMs", millis(cleanupNanos));
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.add(
                "final.supportMs", millis(supportNanos));
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.add(
                "final.geometryMs", millis(finalGeometryNanos));
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.add(
                "final.rawPoints", source.points().size());
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.add(
                "final.finalPoints", finalPoints.size());
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.add(
                "final.findings", quality.findings().size());
        }
        return new Route(source, finalized, finalIds, finalAssignments,
                retainedSource, quality, cleanup.status(), changed);
    }

    private static SeedGeometry seedGeometry(TraceHypothesis source, EvidenceSnapshot evidence,
            NetworkSnapshot network, TraceRequest request, Set<Integer> fixedOccurrences) {
        DetachedPrimitive selectedPrimitive = network.primitives().get(request.selectedWayKey());
        if (!(selectedPrimitive instanceof DetachedWay selected)
                || request.selectedRange().lastIndex() >= selected.nodeKeys().size()) {
            throw new IllegalArgumentException("Selected occurrence range is absent from captured network");
        }
        validateSelectedOccurrenceIdentity(selected, request);
        List<MetricPoint> selectedPoints = new ArrayList<>(request.selectedRange().size());
        List<ExistingWayNodeOccurrence> existingIds = new ArrayList<>(request.selectedRange().size());
        for (int occurrence = request.selectedRange().firstIndex();
                occurrence <= request.selectedRange().lastIndex(); occurrence++) {
            PrimitiveKey nodeKey = selected.nodeKeys().get(occurrence);
            DetachedPrimitive nodePrimitive = network.primitives().get(nodeKey);
            if (!(nodePrimitive instanceof DetachedNode node)) {
                throw new IllegalArgumentException("Selected occurrence node is absent from captured network");
            }
            selectedPoints.add(evidence.coordinateFrame().toMetric(node.coordinate()));
            existingIds.add(new ExistingWayNodeOccurrence(request.selectedWayKey(), nodeKey, occurrence));
        }
        double[] sourceFractions = normalizedChainage(selectedPoints, "captured selected geometry");
        double[] candidateFractions = normalizedChainage(source.points(), "raw candidate geometry");
        List<SeedEntry> entries = new ArrayList<>(source.points().size() + existingIds.size());
        for (int index = 0; index < source.points().size(); index++) {
            entries.add(new SeedEntry(candidateFractions[index], 1, index,
                    new GeneratedCandidatePoint(source.id(), index), source.points().get(index),
                    source.support().get(index), false, false));
        }
        Set<PrimitiveKey> movable = network.closure().movableExistingNodeKeys();
        Set<PrimitiveKey> removable = network.closure().removableExistingNodeKeys();
        Set<PrimitiveKey> protectedNodes = network.closure().protectedExistingNodeKeys();
        for (int local = 0; local < existingIds.size(); local++) {
            ExistingWayNodeOccurrence id = existingIds.get(local);
            PrimitiveKey nodeKey = id.nodeKey();
            boolean protectedCoordinate = protectedNodes.contains(nodeKey);
            boolean movableCoordinate = movable.contains(nodeKey);
            boolean removableIdentity = removable.contains(nodeKey);
            if (!protectedCoordinate && !removableIdentity && !movableCoordinate) {
                throw new IllegalArgumentException(
                        "Selected existing occurrence lacks movement, protection or removal authority");
            }
            double fraction = sourceFractions[local];
            int exactCandidate = exactFractionIndex(candidateFractions, fraction);
            boolean ambiguousMapping = exactCandidate == -2
                    || fractionMultiplicity(sourceFractions, fraction) > 1;
            boolean fixedCoordinate = protectedCoordinate || !movableCoordinate || ambiguousMapping
                    || fixedOccurrences.contains(id.originalOccurrenceIndex());
            MetricPoint assignment = fixedCoordinate ? selectedPoints.get(local)
                    : interpolateAt(source.points(), candidateFractions, fraction);
            ObservationOwnership ownership = fixedCoordinate
                    ? ObservationOwnership.FIXED_TOPOLOGY_ONLY
                    : exactCandidate >= 0 ? source.support().get(exactCandidate)
                    : ObservationOwnership.INFERRED_GAP;
            entries.add(new SeedEntry(fraction, 0, local, id, assignment, ownership,
                    !removableIdentity || fixedOccurrences.contains(id.originalOccurrenceIndex()),
                    fixedCoordinate));
        }
        entries.sort(Comparator.comparingDouble(SeedEntry::fraction)
                .thenComparingInt(SeedEntry::priority).thenComparingInt(SeedEntry::ordinal));
        List<FinalRoutePointId> pointIds = new ArrayList<>();
        List<MetricPoint> points = new ArrayList<>();
        Set<Integer> retainedIndices = new LinkedHashSet<>();
        Set<Integer> protectedIndices = new LinkedHashSet<>();
        Map<FinalRoutePointId, MetricPoint> assignments = new LinkedHashMap<>();
        Map<FinalRoutePointId, ObservationOwnership> ownership = new LinkedHashMap<>();
        for (SeedEntry entry : entries) {
            if (entry.id() instanceof GeneratedCandidatePoint
                    && containsFraction(sourceFractions, entry.fraction())) {
                continue;
            }
            int outputIndex = pointIds.size();
            if (assignments.put(entry.id(), entry.point()) != null) {
                throw new IllegalArgumentException("Duplicate final route point identity");
            }
            pointIds.add(entry.id());
            points.add(entry.point());
            ownership.put(entry.id(), entry.sourceOwnership());
            if (entry.retained()) {
                retainedIndices.add(outputIndex);
            }
            if (entry.protectedCoordinate()) {
                protectedIndices.add(outputIndex);
            }
        }
        if (points.size() < 2) {
            throw new IllegalArgumentException("Final route provenance has fewer than two points");
        }
        return new SeedGeometry(List.copyOf(pointIds), List.copyOf(points),
                Set.copyOf(retainedIndices), Set.copyOf(protectedIndices),
                Map.copyOf(assignments), Map.copyOf(ownership));
    }

    private static void validateSelectedOccurrenceIdentity(DetachedWay selected,
            TraceRequest request) {
        Map<PrimitiveKey, Integer> occurrenceCounts = new LinkedHashMap<>();
        for (PrimitiveKey node : selected.nodeKeys()) {
            occurrenceCounts.merge(node, 1, Integer::sum);
        }
        for (int selectedIndex = request.selectedRange().firstIndex();
                selectedIndex <= request.selectedRange().lastIndex(); selectedIndex++) {
            PrimitiveKey node = selected.nodeKeys().get(selectedIndex);
            if (occurrenceCounts.getOrDefault(node, 0) != 1) {
                throw new IllegalArgumentException(
                        "Selected node identity has repeated or ambiguous way occurrences");
            }
        }
    }

    private static double[] normalizedChainage(List<MetricPoint> points, String description) {
        double[] chainage = new double[points.size()];
        for (int index = 1; index < points.size(); index++) {
            double segment = points.get(index - 1).distanceTo(points.get(index));
            if (!Double.isFinite(segment)) {
                throw new IllegalArgumentException(description + " has non-finite extent");
            }
            chainage[index] = chainage[index - 1] + segment;
        }
        double total = chainage[chainage.length - 1];
        if (!(total > 0.0) || !Double.isFinite(total)) {
            throw new IllegalArgumentException(description + " has no finite forward extent");
        }
        for (int index = 0; index < chainage.length; index++) {
            chainage[index] /= total;
        }
        return chainage;
    }

    private static MetricPoint interpolateAt(List<MetricPoint> points, double[] fractions,
            double target) {
        int segment = Math.max(0, Math.min(fractions.length - 2,
                upperBound(fractions, target) - 1));
        double width = fractions[segment + 1] - fractions[segment];
        if (!(width > 0.0)) {
            throw new IllegalArgumentException("Candidate assignment interval is ambiguous");
        }
        double local = (target - fractions[segment]) / width;
        MetricPoint first = points.get(segment);
        MetricPoint last = points.get(segment + 1);
        return new MetricPoint(first.xMeters() + local * (last.xMeters() - first.xMeters()),
                first.yMeters() + local * (last.yMeters() - first.yMeters()));
    }

    private static int exactFractionIndex(double[] fractions, double target) {
        int first = lowerBound(fractions, target - FRACTION_TOLERANCE);
        int last = upperBound(fractions, target + FRACTION_TOLERANCE);
        return last == first ? -1 : last == first + 1 ? first : -2;
    }

    private static int fractionMultiplicity(double[] fractions, double target) {
        return upperBound(fractions, target + FRACTION_TOLERANCE)
                - lowerBound(fractions, target - FRACTION_TOLERANCE);
    }

    private static boolean containsFraction(double[] fractions, double target) {
        return lowerBound(fractions, target - FRACTION_TOLERANCE)
                < upperBound(fractions, target + FRACTION_TOLERANCE);
    }

    private static int lowerBound(double[] values, double target) {
        int first = 0;
        int last = values.length;
        while (first < last) {
            int middle = (first + last) >>> 1;
            if (values[middle] < target) {
                first = middle + 1;
            } else {
                last = middle;
            }
        }
        return first;
    }

    private static int upperBound(double[] values, double target) {
        int first = 0;
        int last = values.length;
        while (first < last) {
            int middle = (first + last) >>> 1;
            if (values[middle] <= target) {
                first = middle + 1;
            } else {
                last = middle;
            }
        }
        return first;
    }

    private static void validateFinalProvenance(SeedGeometry seed,
            ImageSupportedLocalCleanup.Result cleanup, Set<PrimitiveKey> removable) {
        Set<FinalRoutePointId> finalIds = new LinkedHashSet<>(cleanup.occurrenceIds());
        Map<FinalRoutePointId, Integer> seedIndices = indexByIdentity(seed.pointIds());
        if (finalIds.size() != cleanup.occurrenceIds().size()
                || !seedIndices.keySet().containsAll(cleanup.occurrenceIds())) {
            throw new IllegalStateException("Cleanup returned duplicate or unknown occurrence identity");
        }
        int previous = -1;
        for (FinalRoutePointId id : cleanup.occurrenceIds()) {
            int sourceIndex = seedIndices.get(id);
            if (sourceIndex <= previous) {
                throw new IllegalStateException("Cleanup reordered final occurrence identity");
            }
            previous = sourceIndex;
        }
        for (int index : seed.retainedIndices()) {
            FinalRoutePointId id = seed.pointIds().get(index);
            if (!finalIds.contains(id)) {
                throw new IllegalStateException("Cleanup omitted required existing occurrence identity");
            }
        }
        for (FinalRoutePointId id : seed.pointIds()) {
            if (id instanceof ExistingWayNodeOccurrence existing
                    && !removable.contains(existing.nodeKey()) && !finalIds.contains(id)) {
                throw new IllegalStateException("Final route lacks a required existing-node assignment");
            }
        }
    }

    private static Map<Integer, MetricPoint> protectedAssignments(SeedGeometry seed,
            List<FinalRoutePointId> finalIds,
            Map<FinalRoutePointId, MetricPoint> finalAssignments) {
        Map<Integer, MetricPoint> result = new LinkedHashMap<>();
        Map<FinalRoutePointId, Integer> finalIndices = indexByIdentity(finalIds);
        for (int sourceIndex : seed.protectedIndices()) {
            FinalRoutePointId id = seed.pointIds().get(sourceIndex);
            int finalIndex = finalIndices.getOrDefault(id, -1);
            MetricPoint expected = seed.assignments().get(id);
            if (finalIndex < 0) {
                if (seed.retainedIndices().contains(sourceIndex)) {
                    throw new IllegalStateException("Protected occurrence assignment disappeared");
                }
                continue;
            }
            if (!expected.equals(finalAssignments.get(id))) {
                throw new IllegalStateException("Protected occurrence assignment changed");
            }
            result.put(finalIndex, expected);
        }
        return Map.copyOf(result);
    }

    private static double maximumDistanceToPolyline(List<MetricPoint> points,
            List<MetricPoint> polyline) {
        if (polyline.size() < 2) {
            return Double.POSITIVE_INFINITY;
        }
        double maximum = 0.0;
        for (MetricPoint point : points) {
            double nearest = Double.POSITIVE_INFINITY;
            for (int index = 1; index < polyline.size(); index++) {
                nearest = Math.min(nearest, pointSegmentDistance(point,
                        polyline.get(index - 1), polyline.get(index)));
            }
            maximum = Math.max(maximum, nearest);
        }
        return maximum;
    }

    private static double pointSegmentDistance(MetricPoint point, MetricPoint start,
            MetricPoint end) {
        double dx = end.xMeters() - start.xMeters();
        double dy = end.yMeters() - start.yMeters();
        double lengthSquared = dx * dx + dy * dy;
        if (!(lengthSquared > 0.0)) {
            return point.distanceTo(start);
        }
        double fraction = ((point.xMeters() - start.xMeters()) * dx
                + (point.yMeters() - start.yMeters()) * dy) / lengthSquared;
        fraction = Math.max(0.0, Math.min(1.0, fraction));
        return point.distanceTo(new MetricPoint(start.xMeters() + fraction * dx,
                start.yMeters() + fraction * dy));
    }

    private static Map<FinalRoutePointId, Integer> indexByIdentity(
            List<FinalRoutePointId> identities) {
        Map<FinalRoutePointId, Integer> result = new LinkedHashMap<>();
        for (int index = 0; index < identities.size(); index++) {
            if (result.put(identities.get(index), index) != null) {
                throw new IllegalStateException("Duplicate final route point identity");
            }
        }
        return result;
    }

    private static List<ObservationOwnership> freshSupport(List<MetricPoint> points,
            Set<Integer> protectedIndices, ImageCostField image) {
        List<ObservationOwnership> result = new ArrayList<>(points.size());
        for (int index = 0; index < points.size(); index++) {
            if (protectedIndices.contains(index)) {
                result.add(ObservationOwnership.FIXED_TOPOLOGY_ONLY);
                continue;
            }
            MetricPoint tangent = tangent(points, index);
            if (tangent == null) {
                result.add(ObservationOwnership.NO_RASTER);
                continue;
            }
            ImageCostField.RouteSample sample = image.sampleRoute(points.get(index), tangent).orElse(null);
            result.add(sample == null ? ObservationOwnership.NO_RASTER : sample.ownership());
        }
        return List.copyOf(result);
    }

    private static MetricPoint tangent(List<MetricPoint> points, int index) {
        MetricPoint start = points.get(Math.max(0, index - 1));
        MetricPoint end = points.get(Math.min(points.size() - 1, index + 1));
        double x = end.xMeters() - start.xMeters();
        double y = end.yMeters() - start.yMeters();
        return Math.hypot(x, y) <= 1.0e-12 ? null : new MetricPoint(x, y);
    }

    private static ImageSupportedLocalCleanup.Mode cleanupMode(GeometryCleanupMode mode) {
        return switch (mode) {
            case NONE -> ImageSupportedLocalCleanup.Mode.OFF;
            case REDUCE_POINTS_ONLY -> ImageSupportedLocalCleanup.Mode.REDUCE_POINTS_ONLY;
            case CONSTRAINED_SMOOTH_AND_REDUCE -> ImageSupportedLocalCleanup.Mode.REFIT_AND_REDUCE;
        };
    }

    private record SeedEntry(double fraction, int priority, int ordinal,
            FinalRoutePointId id, MetricPoint point, ObservationOwnership sourceOwnership,
            boolean retained, boolean protectedCoordinate) {
    }

    private record SeedGeometry(List<FinalRoutePointId> pointIds, List<MetricPoint> points,
            Set<Integer> retainedIndices, Set<Integer> protectedIndices,
            Map<FinalRoutePointId, MetricPoint> assignments,
            Map<FinalRoutePointId, ObservationOwnership> sourceOwnership) {
    }

    private static List<Route> rank(List<Route> routes, TrackerMode engine, Options options) {
        CandidateRankingPolicy ranking = new CandidateRankingPolicy();
        Map<String, Route> byId = new LinkedHashMap<>();
        List<CandidateRankingPolicy.Candidate> candidates = new ArrayList<>();
        List<Route> unavailable = new ArrayList<>();
        for (Route route : routes) {
            FinalGeometryEvaluator.Result quality = route.quality();
            byId.put(route.hypothesis().id(), route);
            if (quality.has(FinalGeometryEvaluator.FindingCode.UNAVAILABLE_IMAGE_QUALITY)) {
                unavailable.add(route);
                continue;
            }
            long hard = quality.findings().stream()
                    .filter(finding -> finding.severity() == FinalGeometryEvaluator.Severity.HARD_BLOCK)
                    .count();
            int branch = quality.has(FinalGeometryEvaluator.FindingCode.BRANCH_SWITCH)
                    || quality.has(FinalGeometryEvaluator.FindingCode.AMBIGUOUS_BRANCH) ? 1 : 0;
            int severe = Math.toIntExact(Math.min(Integer.MAX_VALUE,
                    quality.findings().size() - branch));
            CandidateRankingPolicy.PhysicalQuality physical = new CandidateRankingPolicy.PhysicalQuality(
                    quality.directlySupportedLengthMeters() + 1.0e-6 >= quality.totalLengthMeters(),
                    hard > 0, severe, branch, 0, quality.directlySupportedLengthMeters(),
                    quality.totalLengthMeters(), quality.worstUnsupportedSpanMeters(),
                    quality.meanImageCenterCost(), 0.0, quality.bendPreservingRoughness(),
                    options.mappingPreference(), route.hypothesis().diagnostics()
                        .getOrDefault("guideReliability", 0.0));
            candidates.add(new CandidateRankingPolicy.Candidate(route.hypothesis().id(), engine,
                    options.sourceTier(), physical, route.hypothesis().objective(),
                    route.hypothesis().points().size(), route.geometryChanged(),
                    route.hypothesis().branchSignature(), route.geometryChanged()));
        }
        Map<String, Integer> finiteRank = new LinkedHashMap<>();
        int position = 0;
        for (CandidateRankingPolicy.Candidate candidate : ranking.rank(candidates)) {
            finiteRank.put(candidate.id(), position++);
        }
        unavailable.sort(Comparator.comparing((Route route) -> route.hypothesis().branchSignature())
                .thenComparing(route -> route.hypothesis().id()));
        List<Route> ordered = new ArrayList<>(routes);
        ordered.sort(Comparator.comparing((Route route) -> isHardBlocked(route.quality()))
                .thenComparing(route -> !isComplete(route.quality()))
                .thenComparing(route -> route.quality().has(
                        FinalGeometryEvaluator.FindingCode.UNAVAILABLE_IMAGE_QUALITY))
                .thenComparingInt(route -> finiteRank.getOrDefault(route.hypothesis().id(),
                        Integer.MAX_VALUE))
                .thenComparing(route -> route.hypothesis().branchSignature())
                .thenComparing(route -> route.hypothesis().id()));
        return List.copyOf(ordered);
    }

    private static boolean isHardBlocked(FinalGeometryEvaluator.Result quality) {
        return quality.findings().stream().anyMatch(
                finding -> finding.severity() == FinalGeometryEvaluator.Severity.HARD_BLOCK);
    }

    private static boolean isComplete(FinalGeometryEvaluator.Result quality) {
        return quality.directlySupportedLengthMeters() + 1.0e-6 >= quality.totalLengthMeters();
    }
}
