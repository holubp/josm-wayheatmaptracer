package org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.ExistingWayNodeOccurrence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.GeneratedCandidatePoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ImageOrientationSupport.AngularMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField.FrozenProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField.ObservedModeStatus;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField.UniqueSegmentStatus;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Evaluates the exact final-preview polyline with physical local and topology checks. */
public final class FinalGeometryEvaluator {
    private static final double[] LOCAL_WINDOW_SPANS_METERS = {6.0, 10.0, 20.0};
    private static final int MAXIMUM_LOCAL_WINDOW_ROWS = 65_536;
    private static final int MAXIMUM_CACHED_PROFILE_ROWS = 65_536;
    private static final long MAXIMUM_SINGLE_APEX_CHORD_EVALUATIONS = 4_000_000L;
    /** Stable defect codes shared by all modern engines. */
    public enum FindingCode {
        SELF_INTERSECTION,
        NONADJACENT_TOUCH,
        COLLINEAR_OVERLAP,
        ADJACENT_BACKTRACK,
        TERMINAL_OVERSHOOT,
        UNSUPPORTED_TERMINAL_KINK,
        UNSUPPORTED_ISOLATED_EXCURSION,
        REPEATED_SHORT_WAVE_WRINKLE,
        BRANCH_SWITCH,
        SUPPORT_MISMATCH,
        PREJUNCTION_CROSSING,
        AMBIGUOUS_BRANCH,
        SEARCH_TRUNCATED,
        OPTIMIZER_FAILURE,
        INSUFFICIENT_DIRECT_SUPPORT,
        UNAVAILABLE_IMAGE_QUALITY,
        PROTECTED_ASSIGNMENT_MISMATCH,
        PRECISE_SHAPE_REQUIRED,
        LOCAL_SHAPE_IMAGE_AMBIGUITY
    }

    /** Severity remains separate from empirical confidence or average fit. */
    public enum Severity { REVIEW, HARD_BLOCK }

    /** Final deterministic applicability state. */
    public enum Disposition { APPLICABLE, REVIEW_REQUIRED, HARD_BLOCKED }

    /** One localized final-geometry finding. */
    public record Finding(FindingCode code, Severity severity, int firstVertex, int lastVertex,
            double amplitudeMeters) {
    }

    /** Immutable evaluator input; {@code cleaned} is diagnostic metadata only. */
    public record Request(String id, List<MetricPoint> points,
            List<FinalRoutePointId> pointIds, ImageCostField image,
            double sourcePitchMeters, Map<Integer, MetricPoint> protectedAssignments,
            List<List<MetricPoint>> proposedIncidentGeometry, boolean cleaned,
            boolean branchAmbiguous, boolean searchTruncated, boolean optimizerFailed) {
        /** Copies final geometry and validates physical inputs. */
        public Request {
            if (id == null || id.isBlank() || points == null || points.size() < 2
                    || pointIds == null || pointIds.size() != points.size() || image == null
                    || !Double.isFinite(sourcePitchMeters) || sourcePitchMeters <= 0.0
                    || protectedAssignments == null || proposedIncidentGeometry == null) {
                throw new IllegalArgumentException("Final geometry evaluation input is incomplete");
            }
            points = List.copyOf(points);
            pointIds = List.copyOf(pointIds);
            if (new LinkedHashSet<>(pointIds).size() != pointIds.size()) {
                throw new IllegalArgumentException("Final geometry occurrence identities are duplicated");
            }
            protectedAssignments = Map.copyOf(new LinkedHashMap<>(protectedAssignments));
            for (Map.Entry<Integer, MetricPoint> entry : protectedAssignments.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null || entry.getKey() < 0
                        || entry.getKey() >= points.size()) {
                    throw new IllegalArgumentException("Protected final assignments are invalid");
                }
            }
            List<List<MetricPoint>> incidents = new ArrayList<>();
            for (List<MetricPoint> incident : proposedIncidentGeometry) {
                incidents.add(List.copyOf(incident));
            }
            proposedIncidentGeometry = List.copyOf(incidents);
        }

        /** Compatibility constructor for callers without typed final occurrence identities. */
        public Request(String id, List<MetricPoint> points, ImageCostField image,
                double sourcePitchMeters, Map<Integer, MetricPoint> protectedAssignments,
                List<List<MetricPoint>> proposedIncidentGeometry, boolean cleaned,
                boolean branchAmbiguous, boolean searchTruncated, boolean optimizerFailed) {
            this(id, points, generatedIdentities(id, points), image, sourcePitchMeters,
                    protectedAssignments, proposedIncidentGeometry, cleaned, branchAmbiguous,
                    searchTruncated, optimizerFailed);
        }

        /** Compatibility constructor for callers whose protected coordinates are the supplied points. */
        public Request(String id, List<MetricPoint> points, ImageCostField image,
                double sourcePitchMeters, Set<Integer> protectedIndices,
                List<List<MetricPoint>> proposedIncidentGeometry, boolean cleaned,
                boolean branchAmbiguous, boolean searchTruncated, boolean optimizerFailed) {
            this(id, points, generatedIdentities(id, points), image, sourcePitchMeters,
                    protectedAssignments(points, protectedIndices), proposedIncidentGeometry,
                    cleaned, branchAmbiguous, searchTruncated, optimizerFailed);
        }

        /** Returns the protected indexes retained for source compatibility. */
        public Set<Integer> protectedIndices() {
            return protectedAssignments.keySet();
        }

        private static List<FinalRoutePointId> generatedIdentities(String id,
                List<MetricPoint> points) {
            if (id == null || id.isBlank() || points == null) {
                throw new IllegalArgumentException("Final geometry identities are incomplete");
            }
            List<FinalRoutePointId> result = new ArrayList<>(points.size());
            for (int index = 0; index < points.size(); index++) {
                result.add(new GeneratedCandidatePoint(id, index));
            }
            return List.copyOf(result);
        }

        private static Map<Integer, MetricPoint> protectedAssignments(List<MetricPoint> points,
                Set<Integer> protectedIndices) {
            if (points == null || protectedIndices == null) {
                throw new IllegalArgumentException("Protected final assignments are incomplete");
            }
            Map<Integer, MetricPoint> result = new LinkedHashMap<>();
            for (int index : protectedIndices) {
                if (index < 0 || index >= points.size()) {
                    throw new IllegalArgumentException("Protected final assignment index is invalid");
                }
                result.put(index, points.get(index));
            }
            return Map.copyOf(result);
        }
    }

    /** Common physical metrics and localized findings for the geometry that would be applied. */
    public record Result(String id, Disposition disposition, List<Finding> findings,
            double totalLengthMeters, double directlySupportedLengthMeters,
            double worstUnsupportedSpanMeters, double meanImageCenterCost,
            double bendPreservingRoughness) {
        /** Copies findings. */
        public Result {
            findings = List.copyOf(findings);
            boolean unavailableImageQuality = findings.stream()
                    .anyMatch(finding -> finding.code() == FindingCode.UNAVAILABLE_IMAGE_QUALITY);
            if ((!Double.isFinite(meanImageCenterCost)
                    && meanImageCenterCost != Double.POSITIVE_INFINITY)
                    || meanImageCenterCost < 0.0
                    || unavailableImageQuality != (meanImageCenterCost == Double.POSITIVE_INFINITY)) {
                throw new IllegalArgumentException("Final image quality availability is inconsistent");
            }
        }

        /** Returns whether a stable finding code is present. */
        public boolean has(FindingCode code) {
            return findings.stream().anyMatch(finding -> finding.code() == code);
        }
    }

    /** Test-facing truthful counters for the bounded local-warning classifier. */
    record LocalWarningStats(long windowVisits, long qualifyingWindows,
            long candidateComparisons, long maximumDiagnosticRows,
            long maximumCachedProfileRows, long rowBudgetAbstentions,
            long singleApexComparisons, long singleApexProfileAmbiguities,
            long singleApexOrientationAmbiguities, long singleApexResidualAmbiguities,
            long singleApexSegmentAmbiguities, long singleApexChordEvaluations,
            long singleApexScanBudgetAbstentions) { }

    /** Local-warning result and its bounded-work evidence. */
    record LocalWarningInspection(List<Finding> findings, LocalWarningStats stats) {
        LocalWarningInspection {
            findings = List.copyOf(findings);
        }
    }

    /** Focused classifier entry point used to prove its work and retention bounds. */
    LocalWarningInspection inspectRepeatedShortWavesForTest(Request request,
            CancellationProbe cancellation) {
        List<Finding> findings = new ArrayList<>();
        MutableLocalWarningStats stats = new MutableLocalWarningStats();
        double onset = Math.max(0.75, 0.5 * request.sourcePitchMeters());
        inspectRepeatedShortWaves(request, findings, chainage(request.points()), onset,
                cancellation, stats);
        return new LocalWarningInspection(findings, stats.snapshot());
    }

    /** Focused complete local-shape entry point used to prove apex work bounds. */
    LocalWarningInspection inspectLocalExcursionsForTest(Request request,
            CancellationProbe cancellation) {
        List<Finding> findings = new ArrayList<>();
        MutableLocalWarningStats stats = new MutableLocalWarningStats();
        inspectLocalExcursions(request, findings, new DirectedSamplingMemo(2_048),
                cancellation, stats);
        return new LocalWarningInspection(findings, stats.snapshot());
    }

    /** Evaluates topology, localized excursions, image support and deterministic eligibility. */
    public Result evaluate(Request request) {
        return evaluate(request, CancellationProbe.NONE);
    }

    /** Evaluates with bounded cooperative cancellation for local physical diagnostics. */
    public Result evaluate(Request request, CancellationProbe cancellation) {
        Objects.requireNonNull(cancellation, "cancellation");
        List<Finding> findings = new ArrayList<>();
        MutableLocalWarningStats localWarningStats = new MutableLocalWarningStats();
        DirectedSamplingMemo sampling = new DirectedSamplingMemo(2_048);
        long protectedStarted = System.nanoTime();
        inspectProtectedAssignments(request, findings);
        long protectedNanos = System.nanoTime() - protectedStarted;
        long intersectionsStarted = System.nanoTime();
        inspectIntersections(request, findings);
        long intersectionsNanos = System.nanoTime() - intersectionsStarted;
        long backtracksStarted = System.nanoTime();
        inspectBacktracks(request.points(), findings);
        long backtracksNanos = System.nanoTime() - backtracksStarted;
        long excursionsStarted = System.nanoTime();
        inspectLocalExcursions(request, findings, sampling, cancellation, localWarningStats);
        long excursionsNanos = System.nanoTime() - excursionsStarted;
        long incidentsStarted = System.nanoTime();
        inspectIncidentCrossings(request, findings);
        long incidentsNanos = System.nanoTime() - incidentsStarted;
        if (request.branchAmbiguous()) {
            findings.add(review(FindingCode.AMBIGUOUS_BRANCH, 0, request.points().size() - 1, 0));
        }
        if (request.searchTruncated()) {
            findings.add(review(FindingCode.SEARCH_TRUNCATED, 0, request.points().size() - 1, 0));
        }
        if (request.optimizerFailed()) {
            findings.add(review(FindingCode.OPTIMIZER_FAILURE, 0, request.points().size() - 1, 0));
        }

        long supportStarted = System.nanoTime();
        SupportMetrics support = supportMetrics(request.points(), request.image(), request.sourcePitchMeters(), sampling);
        long supportNanos = System.nanoTime() - supportStarted;
        long routeCostStarted = System.nanoTime();
        double meanImageCenterCost = sampling.routePolylineCost(request.points(), request.image());
        long routeCostNanos = System.nanoTime() - routeCostStarted;
        if (meanImageCenterCost == Double.POSITIVE_INFINITY) {
            findings.add(review(FindingCode.UNAVAILABLE_IMAGE_QUALITY, 0,
                    request.points().size() - 1, support.worstUnsupportedSpan));
        }
        if (support.directLength + 1.0e-9 < 0.95 * support.totalLength
                || support.worstUnsupportedSpan > 10.0) {
            findings.add(review(FindingCode.INSUFFICIENT_DIRECT_SUPPORT, 0,
                    request.points().size() - 1, support.worstUnsupportedSpan));
        }
        findings = deduplicate(findings);
        Disposition disposition = findings.stream().anyMatch(finding -> finding.severity() == Severity.HARD_BLOCK)
                ? Disposition.HARD_BLOCKED
                : findings.isEmpty() ? Disposition.APPLICABLE : Disposition.REVIEW_REQUIRED;
        long roughnessStarted = System.nanoTime();
        double roughness = roughness(request.points());
        long roughnessNanos = System.nanoTime() - roughnessStarted;
        if (request.id().startsWith("probabilistic-")) {
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.add(
                "finalEvaluator.protectedMs", millis(protectedNanos));
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.add(
                "finalEvaluator.intersectionsMs", millis(intersectionsNanos));
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.add(
                "finalEvaluator.backtracksMs", millis(backtracksNanos));
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.add(
                "finalEvaluator.excursionsMs", millis(excursionsNanos));
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.add(
                "finalEvaluator.incidentsMs", millis(incidentsNanos));
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.add(
                "finalEvaluator.supportMs", millis(supportNanos));
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.add(
                "finalEvaluator.routeCostMs", millis(routeCostNanos));
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.add(
                "finalEvaluator.roughnessMs", millis(roughnessNanos));
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.add(
                "finalEvaluator.findings", findings.size());
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.add(
                "finalEvaluator.points", request.points().size());
        }
        return new Result(request.id(), disposition, findings, support.totalLength, support.directLength,
                support.worstUnsupportedSpan, meanImageCenterCost, roughness);
    }

    private static long millis(long nanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    private static void inspectProtectedAssignments(Request request, List<Finding> findings) {
        request.protectedAssignments().forEach((index, expected) -> {
            if (!expected.equals(request.points().get(index))) {
                findings.add(hard(FindingCode.PROTECTED_ASSIGNMENT_MISMATCH,
                        index, index, expected.distanceTo(request.points().get(index))));
            }
        });
    }

    private static void inspectIntersections(Request request, List<Finding> findings) {
        List<MetricPoint> points = request.points();
        ZeroLengthAdjacency adjacency = new ZeroLengthAdjacency(points, request.pointIds());
        for (int first = 0; first < points.size() - 1; first++) {
            for (int second = first + 2; second < points.size() - 1; second++) {
                IntersectionKind kind = intersection(points.get(first), points.get(first + 1),
                        points.get(second), points.get(second + 1));
                if (kind == IntersectionKind.NONE
                        || (kind == IntersectionKind.TOUCH
                                && adjacency.connects(first, second))) {
                    continue;
                }
                FindingCode code = switch (kind) {
                    case PROPER -> FindingCode.SELF_INTERSECTION;
                    case TOUCH -> FindingCode.NONADJACENT_TOUCH;
                    case OVERLAP -> FindingCode.COLLINEAR_OVERLAP;
                    case NONE -> throw new IllegalStateException();
                };
                findings.add(hard(code, first, second + 1, 0));
            }
        }
    }

    /** Prepares exact typed zero-length connector runs once for constant-time pair queries. */
    static final class ZeroLengthAdjacency {
        private final int[] runs;

        ZeroLengthAdjacency(List<MetricPoint> points, List<FinalRoutePointId> pointIds) {
            if (points == null || pointIds == null || points.size() != pointIds.size()) {
                throw new IllegalArgumentException("Connector geometry and identities must match");
            }
            runs = new int[points.size()];
            for (int index = 1; index < points.size(); index++) {
                boolean connected = points.get(index - 1).equals(points.get(index))
                        && pointIds.get(index - 1) instanceof ExistingWayNodeOccurrence first
                        && pointIds.get(index) instanceof ExistingWayNodeOccurrence second
                        && first.wayKey().equals(second.wayKey())
                        && second.originalOccurrenceIndex() == first.originalOccurrenceIndex() + 1;
                runs[index] = runs[index - 1] + (connected ? 0 : 1);
            }
        }

        boolean connects(int firstSegment, int secondSegment) {
            return runs[firstSegment + 1] == runs[secondSegment];
        }
    }

    private static void inspectBacktracks(List<MetricPoint> points, List<Finding> findings) {
        for (int index = 1; index < points.size() - 1; index++) {
            MetricPoint incoming = subtract(points.get(index), points.get(index - 1));
            MetricPoint outgoing = subtract(points.get(index + 1), points.get(index));
            double product = norm(incoming) * norm(outgoing);
            if (product > 0.0 && dot(incoming, outgoing) < -0.25 * product) {
                findings.add(hard(FindingCode.ADJACENT_BACKTRACK, index - 1, index + 1, 0));
                if (index == points.size() - 2) {
                    findings.add(hard(FindingCode.TERMINAL_OVERSHOOT, index - 1, index + 1, 0));
                }
            }
        }
    }

    private static void inspectLocalExcursions(Request request, List<Finding> findings,
            DirectedSamplingMemo sampling, CancellationProbe cancellation,
            MutableLocalWarningStats localWarningStats) {
        double onset = Math.max(0.75, 0.5 * request.sourcePitchMeters());
        double[] chainage = chainage(request.points());
        inspectRepeatedShortWaves(request, findings, chainage, onset, cancellation,
                localWarningStats);
        PhysicalSampleIndex samples = PhysicalSampleIndex.create(request.points(), chainage,
                Math.min(1.0, request.sourcePitchMeters() / 2.0), cancellation);
        Map<Integer, List<SingleApexCandidate>> candidatesByApex = new LinkedHashMap<>();
        boolean scanBudgetExceeded = false;

        scan:
        for (int centerIndex = 0; centerIndex < samples.uniformCount(); centerIndex++) {
            cancellation.checkpoint();
            double centerChainage = samples.uniformAt(centerIndex).chainageMeters();
            for (double requestedSpan : LOCAL_WINDOW_SPANS_METERS) {
                double start;
                double end;
                boolean wholeShortRoute = samples.totalLengthMeters() + 1.0e-9
                        < requestedSpan;
                if (wholeShortRoute) {
                    if (centerIndex != 0 || requestedSpan != LOCAL_WINDOW_SPANS_METERS[0]) {
                        continue;
                    }
                    start = 0.0;
                    end = samples.totalLengthMeters();
                } else {
                    double halfSpan = 0.5 * requestedSpan;
                    if (centerChainage < halfSpan - 1.0e-9
                            || centerChainage > samples.totalLengthMeters()
                                - halfSpan + 1.0e-9) {
                        continue;
                    }
                    start = centerChainage - halfSpan;
                    end = centerChainage + halfSpan;
                }
                double span = end - start;
                if (!wholeShortRoute && span < 4.0) {
                    continue;
                }
                MetricPoint firstPoint = samples.sampleAt(start).point();
                MetricPoint lastPoint = samples.sampleAt(end).point();
                double amplitude = 0.0;
                int apex = -1;
                int firstInterior = PhysicalSampleIndex.lowerBound(chainage, start + 1.0e-9);
                int afterLastInterior = PhysicalSampleIndex.upperBound(chainage, end - 1.0e-9);
                for (int index = firstInterior; index < afterLastInterior; index++) {
                    if (localWarningStats.singleApexChordEvaluations
                            >= MAXIMUM_SINGLE_APEX_CHORD_EVALUATIONS) {
                        scanBudgetExceeded = true;
                        break scan;
                    }
                    localWarningStats.singleApexChordEvaluations++;
                    if ((localWarningStats.singleApexChordEvaluations & 1023L) == 0L) {
                        cancellation.checkpoint();
                    }
                    double distance = pointSegmentDistance(request.points().get(index),
                            firstPoint, lastPoint);
                    if (distance > amplitude) {
                        amplitude = distance;
                        apex = index;
                    }
                }
                if (apex < 0 || amplitude <= onset) {
                    continue;
                }
                int first = Math.max(0, PhysicalSampleIndex.upperBound(chainage, start) - 1);
                int last = Math.min(request.points().size() - 1,
                        PhysicalSampleIndex.lowerBound(chainage, end));
                SingleApexCandidate candidate = new SingleApexCandidate(first, last, apex,
                        amplitude, span, start, end);
                List<SingleApexCandidate> candidates = candidatesByApex
                        .computeIfAbsent(apex, ignored -> new ArrayList<>());
                int sameSpan = -1;
                for (int index = 0; index < candidates.size(); index++) {
                    if (Math.abs(candidates.get(index).spanMeters() - span) <= 1.0e-9) {
                        sameSpan = index;
                        break;
                    }
                }
                if (sameSpan < 0) {
                    candidates.add(candidate);
                } else if (candidate.betterCenteredThan(candidates.get(sameSpan),
                        chainage[apex])) {
                    candidates.set(sameSpan, candidate);
                }
            }
        }

        if (scanBudgetExceeded) {
            localWarningStats.singleApexScanBudgetAbstentions++;
            findings.add(review(FindingCode.LOCAL_SHAPE_IMAGE_AMBIGUITY, 0,
                    request.points().size() - 1, 0.0));
            if (request.points().size() >= 3) {
                inspectTerminalKink(request.points(), request.image(), onset, false, findings, sampling);
                inspectTerminalKink(request.points(), request.image(), onset, true, findings, sampling);
            }
            return;
        }

        ProfileCache profiles = new ProfileCache(localWarningStats);
        for (List<SingleApexCandidate> candidates : resolutionDistinctCandidateGroups(
                candidatesByApex.values(), chainage, request.sourcePitchMeters())) {
            cancellation.checkpoint();
            SingleApexCandidate ambiguous = candidates.get(0);
            double isolationAmplitude = candidates.stream()
                    .mapToDouble(SingleApexCandidate::amplitudeMeters)
                    .max().orElseThrow();
            boolean resolved = false;
            for (SingleApexCandidate candidate : candidates) {
                localWarningStats.singleApexComparisons++;
                LocalComparison comparison = compareSingleApex(request, samples, candidate,
                        isolationAmplitude, profiles, onset, cancellation, localWarningStats);
                if (comparison == LocalComparison.CONTRADICTED) {
                    findings.add(review(FindingCode.UNSUPPORTED_ISOLATED_EXCURSION,
                            candidate.firstVertex(), candidate.lastVertex(),
                            candidate.amplitudeMeters()));
                    resolved = true;
                    break;
                }
                if (comparison == LocalComparison.SUPPORTED) {
                    resolved = true;
                    break;
                }
            }
            if (!resolved) {
                findings.add(review(FindingCode.LOCAL_SHAPE_IMAGE_AMBIGUITY,
                        ambiguous.firstVertex(), ambiguous.lastVertex(),
                        ambiguous.amplitudeMeters()));
            }
        }

        if (request.points().size() >= 3) {
            inspectTerminalKink(request.points(), request.image(), onset, false, findings, sampling);
            inspectTerminalKink(request.points(), request.image(), onset, true, findings, sampling);
        }
    }

    private static List<List<SingleApexCandidate>> resolutionDistinctCandidateGroups(
            java.util.Collection<List<SingleApexCandidate>> candidateGroups, double[] chainage,
            double sourcePitchMeters) {
        List<List<SingleApexCandidate>> ordered = new ArrayList<>();
        for (List<SingleApexCandidate> candidates : candidateGroups) {
            candidates.sort((first, second) -> first.betterThan(second) ? -1
                    : second.betterThan(first) ? 1 : 0);
            ordered.add(List.copyOf(candidates));
        }
        ordered.sort(Comparator.comparingDouble(candidates ->
                chainage[candidates.get(0).apexVertex()]));
        List<List<SingleApexCandidate>> result = new ArrayList<>();
        List<SingleApexCandidate> best = null;
        double clusterRepresentative = Double.NEGATIVE_INFINITY;
        for (List<SingleApexCandidate> candidates : ordered) {
            SingleApexCandidate candidate = candidates.get(0);
            double apexChainage = chainage[candidate.apexVertex()];
            if (best == null || apexChainage - clusterRepresentative
                    > sourcePitchMeters + 1.0e-9) {
                if (best != null) result.add(best);
                best = candidates;
                clusterRepresentative = apexChainage;
            } else if (candidate.betterThan(best.get(0))) {
                best = candidates;
            }
        }
        if (best != null) result.add(best);
        result.sort(Comparator.comparingInt(candidates -> candidates.get(0).firstVertex()));
        return List.copyOf(result);
    }

    static List<Integer> resolutionDistinctApexesForTest(List<Double> chainages,
            double sourcePitchMeters) {
        List<Integer> result = new ArrayList<>();
        double representative = Double.NEGATIVE_INFINITY;
        for (int index = 0; index < chainages.size(); index++) {
            double chainage = chainages.get(index);
            if (result.isEmpty() || chainage - representative > sourcePitchMeters + 1.0e-9) {
                result.add(index);
                representative = chainage;
            }
        }
        return List.copyOf(result);
    }

    private static LocalComparison compareSingleApex(Request request,
            PhysicalSampleIndex samples, SingleApexCandidate candidate,
            double isolationAmplitude, ProfileCache profiles, double onset,
            CancellationProbe cancellation,
            MutableLocalWarningStats stats) {
        Set<Integer> sampledExact = new LinkedHashSet<>(request.protectedIndices());
        sampledExact.add(candidate.apexVertex());
        double start = candidate.startChainageMeters();
        double end = candidate.endChainageMeters();
        List<PhysicalSample> rows = samples.comparisonWindow(start, end, sampledExact,
                cancellation, stats);
        if (rows == null || stats.singleApexRows + rows.size() > MAXIMUM_LOCAL_WINDOW_ROWS) {
            stats.rowBudgetAbstentions++;
            return LocalComparison.AMBIGUOUS;
        }
        stats.singleApexRows += rows.size();
        MetricPoint windowStart = samples.sampleAt(start).point();
        MetricPoint windowTangent = subtract(samples.sampleAt(end).point(), windowStart);
        if (!(norm(windowTangent) > 1.0e-12)) return LocalComparison.AMBIGUOUS;
        List<FrozenProfile> measured = new ArrayList<>(rows.size());
        List<MetricPoint> centers = new ArrayList<>(rows.size());
        List<MetricPoint> alternative = new ArrayList<>(rows.size());
        double[] rowChainage = new double[rows.size()];
        double[] squaredResidual = new double[rows.size()];
        double[] candidateCosts = new double[rows.size()];
        double[] alternativeCosts = new double[rows.size()];
        double maximumResidual = 0.0;
        boolean routeFollowsMeasuredCenter = true;
        int apexRow = -1;
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            PhysicalSample row = rows.get(rowIndex);
            cancellation.checkpoint();
            rowChainage[rowIndex] = row.chainageMeters();
            if (row.originalVertex() == candidate.apexVertex()) apexRow = rowIndex;
            boolean exactBoundary = row.chainageMeters() == start
                    || row.chainageMeters() == end;
            FrozenProfile profile = frozenProfile(request.image(), row.point(), windowTangent,
                    profiles, cancellation);
            if (profile == null || profile.support() != ImageCostField.FrozenSupport.MEASURED) {
                if (exactBoundary) {
                    // An exact local boundary is never moved.  A supported bend may therefore
                    // retain an image-edge endpoint, but an unmeasured endpoint cannot later
                    // certify a contradictory alternative segment.
                    measured.add(null);
                    centers.add(null);
                    alternative.add(row.point());
                    squaredResidual[rowIndex] = Double.NaN;
                    candidateCosts[rowIndex] = Double.NaN;
                    alternativeCosts[rowIndex] = Double.NaN;
                    continue;
                }
                stats.singleApexProfileAmbiguities++;
                return LocalComparison.AMBIGUOUS;
            }
            double offset = 0.5 * (profile.coreMinimumMeters()
                    + profile.coreMaximumMeters());
            double interventionTube = Math.abs(offset) + isolationAmplitude
                    + request.sourcePitchMeters();
            boolean locallyIsolated = profile.branchIsolation().measured()
                    && profile.branchIsolation().nearestCensoredExtentDistanceMeters()
                        > interventionTube
                    && profile.branchIsolation().nearestCompleteCompetitorDistanceMeters()
                        > interventionTube;
            if (!locallyIsolated) {
                stats.singleApexProfileAmbiguities++;
                return LocalComparison.AMBIGUOUS;
            }
            MetricPoint center = new MetricPoint(
                    row.point().xMeters() + profile.normal().xMeters() * offset,
                    row.point().yMeters() + profile.normal().yMeters() * offset);
            boolean exact = exactBoundary
                    || samples.isProtected(row.chainageMeters(), request.protectedIndices());
            MetricPoint proposed = exact ? row.point() : center;
            var currentSample = profile.evaluate(row.point());
            var proposedSample = profile.evaluate(proposed);
            if (currentSample.isEmpty() || proposedSample.isEmpty()) {
                stats.singleApexProfileAmbiguities++;
                return LocalComparison.AMBIGUOUS;
            }
            double residual = row.point().distanceTo(center);
            squaredResidual[rowIndex] = residual * residual;
            maximumResidual = Math.max(maximumResidual, residual);
            candidateCosts[rowIndex] = currentSample.orElseThrow().cost();
            alternativeCosts[rowIndex] = proposedSample.orElseThrow().cost();
            routeFollowsMeasuredCenter &= residual
                    <= 0.5 * request.sourcePitchMeters() + 1.0e-9;
            measured.add(profile);
            centers.add(center);
            alternative.add(proposed);
        }
        if (apexRow < 0) {
            stats.singleApexProfileAmbiguities++;
            return LocalComparison.AMBIGUOUS;
        }
        if (routeFollowsMeasuredCenter) {
            boolean coherent = directionallyCoherent(
                    rows.stream().map(PhysicalSample::point).toList(),
                    centers, measured, rowChainage, apexRow, request.sourcePitchMeters());
            if (coherent) {
                return LocalComparison.SUPPORTED;
            }
            stats.singleApexOrientationAmbiguities++;
            return LocalComparison.AMBIGUOUS;
        }

        if (measured.stream().anyMatch(Objects::isNull)) {
            stats.singleApexProfileAmbiguities++;
            return LocalComparison.AMBIGUOUS;
        }
        double residualMean = weightedMean(rowChainage, squaredResidual);
        double candidateCost = weightedMean(rowChainage, candidateCosts);
        double alternativeCost = weightedMean(rowChainage, alternativeCosts);
        double rmsResidual = StrictMath.sqrt(residualMean);
        double scale = Math.max(1.0, Math.max(Math.abs(candidateCost), Math.abs(alternativeCost)));
        if (!(maximumResidual > onset && rmsResidual > 0.5 * request.sourcePitchMeters()
                && alternativeCost + 1.0e-12 * scale < candidateCost)) {
            stats.singleApexResidualAmbiguities++;
            return LocalComparison.AMBIGUOUS;
        }
        double windowLength = norm(windowTangent);
        double ux = windowTangent.xMeters() / windowLength;
        double uy = windowTangent.yMeters() / windowLength;
        MetricPoint firstAlternative = alternative.get(0);
        double previousProjection = (firstAlternative.xMeters()
                - windowStart.xMeters()) * ux
                + (firstAlternative.yMeters()
                - windowStart.yMeters()) * uy;
        for (int index = 1; index < alternative.size(); index++) {
            cancellation.checkpoint();
            MetricPoint first = alternative.get(index - 1);
            MetricPoint second = alternative.get(index);
            double length = first.distanceTo(second);
            double chainageDelta = rows.get(index).chainageMeters()
                    - rows.get(index - 1).chainageMeters();
            double maximumRowGap = Math.min(1.0, request.sourcePitchMeters() / 2.0);
            double projection = (second.xMeters() - windowStart.xMeters())
                    * ux + (second.yMeters()
                    - windowStart.yMeters()) * uy;
            if (!(length > 1.0e-12) || chainageDelta <= 0.0
                    || chainageDelta > maximumRowGap + 1.0e-9
                    || length > chainageDelta + request.sourcePitchMeters() + 1.0e-9
                    || projection + 0.5 * request.sourcePitchMeters()
                        < previousProjection) {
                stats.singleApexSegmentAmbiguities++;
                return LocalComparison.AMBIGUOUS;
            }
            previousProjection = projection;
        }
        if (!directionallyCoherent(centers, centers, measured, rowChainage, apexRow,
                request.sourcePitchMeters())) {
            stats.singleApexOrientationAmbiguities++;
            return LocalComparison.AMBIGUOUS;
        }
        return LocalComparison.CONTRADICTED;
    }

    private static double angularUncertainty(double sourcePitchMeters, double lengthMeters) {
        if (!(lengthMeters > sourcePitchMeters)) return Double.NaN;
        return StrictMath.asin(Math.min(1.0, 0.5 * sourcePitchMeters / lengthMeters));
    }

    private static boolean directionallyCoherent(List<MetricPoint> route,
            List<MetricPoint> centers, List<FrozenProfile> profiles, double[] chainage,
            int apexIndex, double sourcePitchMeters) {
        if (apexIndex < 0 || apexIndex >= centers.size() || centers.get(apexIndex) == null
                || profiles.get(apexIndex) == null) {
            return false;
        }
        int left = -1;
        for (int index = 0; index < apexIndex; index++) {
            if (centers.get(index) != null && profiles.get(index) != null
                    && chainage[apexIndex] - chainage[index] > sourcePitchMeters + 1.0e-9) {
                left = index;
                break;
            }
        }
        int right = -1;
        for (int index = centers.size() - 1; index > apexIndex; index--) {
            if (centers.get(index) != null && profiles.get(index) != null
                    && chainage[index] - chainage[apexIndex] > sourcePitchMeters + 1.0e-9) {
                right = index;
                break;
            }
        }
        return left >= 0 && right >= 0
                && continuousCenters(centers, chainage, left, right, sourcePitchMeters)
                && coherentLeg(route.get(left), route.get(apexIndex), centers.get(left),
                        centers.get(apexIndex), profiles, left, apexIndex - 1,
                        sourcePitchMeters)
                && coherentLeg(route.get(apexIndex), route.get(right), centers.get(apexIndex),
                        centers.get(right), profiles, apexIndex + 1, right,
                        sourcePitchMeters)
                && apexDirectionCoherent(centers.get(left), centers.get(apexIndex),
                        centers.get(right), profiles.get(apexIndex), sourcePitchMeters);
    }

    private static boolean coherentLeg(MetricPoint routeFirst, MetricPoint routeSecond,
            MetricPoint centerFirst, MetricPoint centerSecond, List<FrozenProfile> profiles,
            int firstIndex, int secondIndex, double sourcePitchMeters) {
        double routeLength = routeFirst.distanceTo(routeSecond);
        double centerLength = centerFirst.distanceTo(centerSecond);
        double baseline = Math.min(routeLength, centerLength);
        double allowance = angularUncertainty(sourcePitchMeters, baseline);
        if (!Double.isFinite(allowance)) {
            return false;
        }
        double routeBearing = StrictMath.atan2(routeSecond.yMeters() - routeFirst.yMeters(),
                routeSecond.xMeters() - routeFirst.xMeters());
        double centerBearing = StrictMath.atan2(centerSecond.yMeters() - centerFirst.yMeters(),
                centerSecond.xMeters() - centerFirst.xMeters());
        if (undirectedDistance(routeBearing, centerBearing) > allowance + 1.0e-12) {
            return false;
        }
        boolean hasMeasuredRayDirection = false;
        boolean hasCoherentRayDirection = false;
        for (int index = Math.min(firstIndex, secondIndex);
                index <= Math.max(firstIndex, secondIndex); index++) {
            FrozenProfile profile = profiles.get(index);
            if (profile == null || profile.coreOrientationModes().isEmpty()
                    || !(profile.coreOrientationCertainty() > 0.0)) {
                continue;
            }
            hasMeasuredRayDirection = true;
            hasCoherentRayDirection |= profile.coreOrientationModes().stream()
                    .anyMatch(mode -> mode.distanceTo(centerBearing) <= allowance + 1.0e-12);
        }
        return hasMeasuredRayDirection && hasCoherentRayDirection;
    }

    private static boolean apexDirectionCoherent(MetricPoint left, MetricPoint apex,
            MetricPoint right, FrozenProfile apexProfile, double sourcePitchMeters) {
        double incomingLength = left.distanceTo(apex);
        double outgoingLength = apex.distanceTo(right);
        double centeredLength = left.distanceTo(right);
        double incomingAllowance = angularUncertainty(sourcePitchMeters, incomingLength);
        double outgoingAllowance = angularUncertainty(sourcePitchMeters, outgoingLength);
        double centeredAllowance = angularUncertainty(sourcePitchMeters, centeredLength);
        double incoming = StrictMath.atan2(apex.yMeters() - left.yMeters(),
                apex.xMeters() - left.xMeters());
        double outgoing = StrictMath.atan2(right.yMeters() - apex.yMeters(),
                right.xMeters() - apex.xMeters());
        double centered = StrictMath.atan2(right.yMeters() - left.yMeters(),
                right.xMeters() - left.xMeters());
        if (!Double.isFinite(incomingAllowance) || !Double.isFinite(outgoingAllowance)) {
            return false;
        }
        boolean resolutionSignificantCorner = undirectedDistance(incoming, outgoing)
                > incomingAllowance + outgoingAllowance + 1.0e-12;
        if (!resolutionSignificantCorner) {
            return Double.isFinite(centeredAllowance)
                    && apexProfile.coreOrientationCertainty() > 0.0
                    && apexProfile.coreOrientationModes().stream().anyMatch(mode ->
                            mode.distanceTo(centered) <= centeredAllowance + 1.0e-12);
        }
        if (apexProfile.observedModeStatus() != ObservedModeStatus.OBSERVED_UNIQUE
                || !apexProfile.branchIsolation().measured()
                || apexProfile.branchIsolation().nearestCompleteCompetitorDistanceMeters()
                        != Double.POSITIVE_INFINITY
                || apexProfile.branchIsolation().nearestCensoredExtentDistanceMeters()
                        != Double.POSITIVE_INFINITY) {
            return false;
        }
        if (apexProfile.directedOrientationModes().size() != 2) return false;
        double outwardLeft = incoming + Math.PI;
        int leftMode = -1;
        int rightMode = -1;
        for (int index = 0; index < apexProfile.directedOrientationModes().size(); index++) {
            var mode = apexProfile.directedOrientationModes().get(index);
            if (mode.distanceTo(outwardLeft) <= incomingAllowance + 1.0e-12) leftMode = index;
            if (mode.distanceTo(outgoing) <= outgoingAllowance + 1.0e-12) rightMode = index;
        }
        return leftMode >= 0 && rightMode >= 0 && leftMode != rightMode;
    }

    private static boolean continuousCenters(List<MetricPoint> centers, double[] chainage,
            int first, int last, double sourcePitchMeters) {
        double maximumRowGap = Math.min(1.0, sourcePitchMeters / 2.0);
        for (int index = first + 1; index <= last; index++) {
            if (centers.get(index - 1) == null || centers.get(index) == null) return false;
            double delta = chainage[index] - chainage[index - 1];
            double measured = centers.get(index - 1).distanceTo(centers.get(index));
            if (!(delta > 0.0) || delta > maximumRowGap + 1.0e-9
                    || !(measured > 1.0e-12)
                    || measured > delta + sourcePitchMeters + 1.0e-9) {
                return false;
            }
        }
        return true;
    }

    private static double undirectedDistance(double first, double second) {
        double normalizedFirst = normalizeBearing(first);
        double normalizedSecond = normalizeBearing(second);
        double difference = Math.abs(normalizedFirst - normalizedSecond);
        return Math.min(difference, Math.PI - difference);
    }

    private static double normalizeBearing(double bearing) {
        double normalized = bearing % Math.PI;
        return normalized < 0.0 ? normalized + Math.PI : normalized;
    }

    private static double weightedMean(double[] chainage, double[] values) {
        if (chainage.length != values.length || chainage.length < 2) {
            throw new IllegalArgumentException("Physical quadrature inputs are incomplete");
        }
        double weighted = 0.0;
        double totalWeight = 0.0;
        for (int index = 0; index < values.length; index++) {
            double previous = index == 0 ? chainage[index] : chainage[index - 1];
            double next = index == values.length - 1 ? chainage[index] : chainage[index + 1];
            double weight = 0.5 * (next - previous);
            if (!Double.isFinite(values[index]) || !(weight >= 0.0)) {
                return Double.NaN;
            }
            weighted += weight * values[index];
            totalWeight += weight;
        }
        return totalWeight > 0.0 ? weighted / totalWeight : Double.NaN;
    }

    static double weightedMeanForTest(double[] chainage, double[] values) {
        return weightedMean(chainage.clone(), values.clone());
    }

    static boolean directionallyCoherentForTest(List<MetricPoint> route,
            List<MetricPoint> centers, List<AngularMode> modes, double sourcePitchMeters) {
        if (route.size() != 3 || centers.size() != 3 || modes.size() != 3) return false;
        return coherentLegForTest(route.get(0), route.get(1), centers.get(0), centers.get(1),
                modes.get(0), modes.get(1), sourcePitchMeters)
                && coherentLegForTest(route.get(1), route.get(2), centers.get(1), centers.get(2),
                        modes.get(1), modes.get(2), sourcePitchMeters);
    }

    private static boolean coherentLegForTest(MetricPoint routeFirst, MetricPoint routeSecond,
            MetricPoint centerFirst, MetricPoint centerSecond, AngularMode firstMode,
            AngularMode secondMode, double sourcePitchMeters) {
        double routeLength = routeFirst.distanceTo(routeSecond);
        double centerLength = centerFirst.distanceTo(centerSecond);
        double allowance = angularUncertainty(sourcePitchMeters,
                Math.min(routeLength, centerLength));
        if (!Double.isFinite(allowance)) return false;
        double routeBearing = StrictMath.atan2(routeSecond.yMeters() - routeFirst.yMeters(),
                routeSecond.xMeters() - routeFirst.xMeters());
        double centerBearing = StrictMath.atan2(centerSecond.yMeters() - centerFirst.yMeters(),
                centerSecond.xMeters() - centerFirst.xMeters());
        return undirectedDistance(routeBearing, centerBearing) <= allowance + 1.0e-12
                && firstMode.distanceTo(centerBearing) <= allowance + 1.0e-12
                && secondMode.distanceTo(centerBearing) <= allowance + 1.0e-12;
    }

    private static void inspectRepeatedShortWaves(Request request, List<Finding> findings,
            double[] vertexChainage, double onset, CancellationProbe cancellation,
            MutableLocalWarningStats stats) {
        PhysicalSampleIndex samples = PhysicalSampleIndex.create(request.points(), vertexChainage,
                Math.min(1.0, request.sourcePitchMeters() / 2.0), cancellation);
        if (request.points().size() < 5 || exactlyCollinear(request.points())) {
            return;
        }
        ProfileCache profiles = new ProfileCache(stats);
        WindowCandidate contradicted = null;
        WindowCandidate ambiguous = null;
        BudgetWindow budgetLimited = null;
        for (double span : LOCAL_WINDOW_SPANS_METERS) {
            if (samples.totalLengthMeters() + 1.0e-9 < span) {
                continue;
            }
            for (int centerIndex = 0; centerIndex < samples.uniformCount(); centerIndex++) {
                cancellation.checkpoint();
                PhysicalSample center = samples.uniformAt(centerIndex);
                double start = center.chainageMeters() - span / 2.0;
                double end = center.chainageMeters() + span / 2.0;
                if (start < -1.0e-9 || end > samples.totalLengthMeters() + 1.0e-9) {
                    continue;
                }
                stats.windowVisits++;
                WindowMeasurement measurement = measureWindow(samples, Math.max(0.0, start),
                        Math.min(samples.totalLengthMeters(), end), request.sourcePitchMeters(),
                        onset, cancellation, stats);
                if (measurement.rowBudgetExceeded()) {
                    stats.rowBudgetAbstentions++;
                    BudgetWindow current = new BudgetWindow(Math.max(0.0, start),
                            Math.min(samples.totalLengthMeters(), end));
                    if (budgetLimited == null || current.spanMeters() < budgetLimited.spanMeters()
                            || current.spanMeters() == budgetLimited.spanMeters()
                                    && current.startChainageMeters()
                                            < budgetLimited.startChainageMeters()) {
                        budgetLimited = current;
                    }
                    continue;
                }
                WindowCandidate candidate = measurement.candidate();
                if (candidate == null || candidate.qualifiedReversals() < 3) {
                    continue;
                }
                stats.qualifyingWindows++;
                stats.candidateComparisons++;
                LocalComparison comparison = compareObservedUniqueMode(request, samples,
                        candidate, profiles, cancellation, stats);
                if (comparison == LocalComparison.CONTRADICTED
                        && (contradicted == null || candidate.betterThan(contradicted))) {
                    contradicted = candidate;
                } else if (comparison == LocalComparison.AMBIGUOUS
                        && (ambiguous == null || candidate.betterThan(ambiguous))) {
                    ambiguous = candidate;
                }
            }
        }
        if (contradicted != null) {
            findings.add(review(FindingCode.REPEATED_SHORT_WAVE_WRINKLE,
                    samples.firstVertex(contradicted.startChainageMeters()),
                    samples.lastVertex(contradicted.endChainageMeters()),
                    contradicted.amplitudeMeters()));
        } else if (ambiguous != null) {
            findings.add(review(FindingCode.LOCAL_SHAPE_IMAGE_AMBIGUITY,
                    samples.firstVertex(ambiguous.startChainageMeters()),
                    samples.lastVertex(ambiguous.endChainageMeters()), ambiguous.amplitudeMeters()));
        } else if (budgetLimited != null) {
            findings.add(review(FindingCode.LOCAL_SHAPE_IMAGE_AMBIGUITY,
                    samples.firstVertex(budgetLimited.startChainageMeters()),
                    samples.lastVertex(budgetLimited.endChainageMeters()), 0.0));
        }
    }

    private static boolean exactlyCollinear(List<MetricPoint> points) {
        MetricPoint first = points.get(0);
        int distinct = 1;
        while (distinct < points.size() && points.get(distinct).equals(first)) {
            distinct++;
        }
        if (distinct == points.size()) {
            return true;
        }
        MetricPoint second = points.get(distinct);
        for (int index = distinct + 1; index < points.size(); index++) {
            if (orientation(first, second, points.get(index)) != 0.0) {
                return false;
            }
        }
        return true;
    }

    private static WindowMeasurement measureWindow(PhysicalSampleIndex samples, double start,
            double end, double sourcePitchMeters, double onset, CancellationProbe cancellation,
            MutableLocalWarningStats stats) {
        List<PhysicalSample> fitRows = samples.uniformWindow(start, end);
        if (fitRows == null) {
            return new WindowMeasurement(null, true);
        }
        if (fitRows.size() < 4) {
            return new WindowMeasurement(null, false);
        }
        MetricPoint startPoint = fitRows.get(0).point();
        MetricPoint endPoint = fitRows.get(fitRows.size() - 1).point();
        MetricPoint direction = subtract(endPoint, startPoint);
        double length = norm(direction);
        if (!(length > 1.0e-12)) {
            return new WindowMeasurement(null, false);
        }
        MetricPoint normal = new MetricPoint(-direction.yMeters() / length,
                direction.xMeters() / length);
        double center = 0.5 * (start + end);
        List<TrendObservation> observations = new ArrayList<>(fitRows.size());
        for (PhysicalSample row : fitRows) {
            observations.add(new TrendObservation(row.chainageMeters() - center,
                    dot(subtract(row.point(), startPoint), normal)));
        }
        QuadraticTrend trend = robustQuadratic(observations, sourcePitchMeters);
        if (trend == null) {
            return new WindowMeasurement(null, false);
        }
        List<PhysicalSample> diagnosticRows = samples.diagnosticWindow(start, end,
                cancellation, stats);
        if (diagnosticRows == null) {
            return new WindowMeasurement(null, true);
        }
        List<ResidualPoint> residuals = new ArrayList<>(diagnosticRows.size());
        for (PhysicalSample row : diagnosticRows) {
            double localX = row.chainageMeters() - center;
            double transverse = dot(subtract(row.point(), startPoint), normal);
            residuals.add(new ResidualPoint(row, transverse - trend.value(localX)));
        }
        List<ResidualPoint> lobes = qualifiedLobes(samples, residuals, onset);
        if (lobes.size() < 3) {
            return new WindowMeasurement(null, false);
        }
        double amplitude = lobes.stream().mapToDouble(lobe -> Math.abs(lobe.residualMeters()))
                .max().orElse(0.0);
        return new WindowMeasurement(new WindowCandidate(start, end, List.copyOf(lobes),
                lobes.size(), amplitude), false);
    }

    private static List<ResidualPoint> qualifiedLobes(PhysicalSampleIndex samples,
            List<ResidualPoint> residuals, double onset) {
        if (residuals.size() < 5) {
            return List.of();
        }
        List<ResidualPlateau> plateaus = new ArrayList<>();
        int start = 0;
        for (int index = 1; index <= residuals.size(); index++) {
            if (index < residuals.size()
                    && residuals.get(index).residualMeters()
                            == residuals.get(start).residualMeters()) {
                continue;
            }
            int end = index - 1;
            double middleChainage = 0.5 * (residuals.get(start).sample().chainageMeters()
                    + residuals.get(end).sample().chainageMeters());
            ResidualPoint representative = residuals.get(start);
            plateaus.add(new ResidualPlateau(representative, middleChainage));
            start = index;
        }
        if (plateaus.size() < 5) {
            return List.of();
        }
        List<ResidualPlateau> extrema = new ArrayList<>();
        extrema.add(plateaus.get(0));
        int previousDirection = 0;
        for (int index = 1; index < plateaus.size(); index++) {
            double movement = plateaus.get(index).point().residualMeters()
                    - plateaus.get(index - 1).point().residualMeters();
            int direction = movement > 0.0 ? 1 : movement < 0.0 ? -1 : 0;
            if (direction != 0 && previousDirection != 0 && direction != previousDirection) {
                extrema.add(plateaus.get(index - 1));
            }
            if (direction != 0) {
                previousDirection = direction;
            }
        }
        extrema.add(plateaus.get(plateaus.size() - 1));
        List<ResidualPoint> result = new ArrayList<>();
        for (int index = 1; index < extrema.size() - 1; index++) {
            ResidualPlateau previous = extrema.get(index - 1);
            ResidualPlateau current = extrema.get(index);
            ResidualPlateau next = extrema.get(index + 1);
            double value = current.point().residualMeters();
            if (Math.abs(value) > onset
                    && Math.abs(value - previous.point().residualMeters()) > onset
                    && Math.abs(next.point().residualMeters() - value) > onset) {
                PhysicalSample sample = samples.sampleAt(current.middleChainageMeters());
                result.add(new ResidualPoint(sample, value));
            }
        }
        return List.copyOf(result);
    }

    private static LocalComparison compareObservedUniqueMode(Request request,
            PhysicalSampleIndex samples, WindowCandidate window,
            ProfileCache profiles, CancellationProbe cancellation,
            MutableLocalWarningStats stats) {
        for (ResidualPoint lobe : window.lobes()) {
            cancellation.checkpoint();
            if (samples.isProtected(lobe.sample().chainageMeters(), request.protectedIndices())) {
                return LocalComparison.AMBIGUOUS;
            }
            MetricPoint tangent = samples.tangentAt(lobe.sample().chainageMeters());
            FrozenProfile profile = frozenProfile(request.image(), lobe.sample().point(), tangent,
                    profiles, cancellation);
            if (profile == null
                    || profile.observedModeStatus() != ObservedModeStatus.OBSERVED_UNIQUE) {
                return LocalComparison.AMBIGUOUS;
            }
            if (profile.coreMinimumMeters() <= 0.0 && profile.coreMaximumMeters() >= 0.0) {
                return LocalComparison.SUPPORTED;
            }
            double offset = 0.5 * (profile.coreMinimumMeters() + profile.coreMaximumMeters());
            MetricPoint alternative = new MetricPoint(
                    lobe.sample().point().xMeters() + profile.normal().xMeters() * offset,
                    lobe.sample().point().yMeters() + profile.normal().yMeters() * offset);
            var candidateCost = profile.evaluate(lobe.sample().point());
            var alternativeCost = profile.evaluate(alternative);
            if (candidateCost.isEmpty() || alternativeCost.isEmpty()
                    || !(alternativeCost.orElseThrow().cost()
                            < candidateCost.orElseThrow().cost())) {
                return LocalComparison.AMBIGUOUS;
            }
        }
        List<PhysicalSample> rows = samples.comparisonWindow(window.startChainageMeters(),
                window.endChainageMeters(), request.protectedIndices(), cancellation, stats);
        if (rows == null) {
            stats.rowBudgetAbstentions++;
            return LocalComparison.AMBIGUOUS;
        }
        List<MetricPoint> alternatives = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            cancellation.checkpoint();
            PhysicalSample row = rows.get(index);
            MetricPoint tangent = localTangent(rows, index);
            FrozenProfile profile = frozenProfile(request.image(), row.point(), tangent,
                    profiles, cancellation);
            if (profile == null
                    || profile.observedModeStatus() != ObservedModeStatus.OBSERVED_UNIQUE) {
                return LocalComparison.AMBIGUOUS;
            }
            boolean retained = index == 0 || index == rows.size() - 1
                    || row.originalVertex() == 0
                    || row.originalVertex() == request.points().size() - 1
                    || samples.isProtected(row.chainageMeters(), request.protectedIndices());
            double offset = 0.5 * (profile.coreMinimumMeters() + profile.coreMaximumMeters());
            alternatives.add(retained ? row.point() : new MetricPoint(
                    row.point().xMeters() + profile.normal().xMeters() * offset,
                    row.point().yMeters() + profile.normal().yMeters() * offset));
        }
        for (int index = 1; index < alternatives.size(); index++) {
            cancellation.checkpoint();
            if (request.image().certifyDirectUniqueSegment(alternatives.get(index - 1),
                    alternatives.get(index), cancellation)
                    != UniqueSegmentStatus.DIRECT_UNIQUE) {
                return LocalComparison.AMBIGUOUS;
            }
        }
        return LocalComparison.CONTRADICTED;
    }

    private static FrozenProfile frozenProfile(ImageCostField image, MetricPoint point,
            MetricPoint tangent, ProfileCache profiles,
            CancellationProbe cancellation) {
        return profiles.getOrMeasure(image, new ProfileKey(point, tangent), cancellation);
    }

    private static MetricPoint localTangent(List<PhysicalSample> rows, int index) {
        int first = Math.max(0, index - 1);
        int last = Math.min(rows.size() - 1, index + 1);
        return subtract(rows.get(last).point(), rows.get(first).point());
    }

    private static QuadraticTrend robustQuadratic(List<TrendObservation> observations,
            double sourcePitchMeters) {
        double[] weights = new double[observations.size()];
        java.util.Arrays.fill(weights, 1.0);
        QuadraticTrend fit = weightedQuadratic(observations, weights);
        if (fit == null) {
            return null;
        }
        for (int iteration = 0; iteration < 3; iteration++) {
            List<Double> residuals = new ArrayList<>(observations.size());
            for (TrendObservation observation : observations) {
                residuals.add(observation.transverseMeters() - fit.value(observation.xMeters()));
            }
            double scale = Math.max(0.05 * sourcePitchMeters, 1.4826 * medianAbsoluteDeviation(residuals));
            for (int index = 0; index < observations.size(); index++) {
                double normalized = Math.abs(residuals.get(index)) / (1.5 * scale);
                weights[index] = normalized <= 1.0 ? 1.0 : 1.0 / normalized;
            }
            fit = weightedQuadratic(observations, weights);
            if (fit == null) {
                return null;
            }
        }
        return fit;
    }

    private static QuadraticTrend weightedQuadratic(List<TrendObservation> observations,
            double[] weights) {
        double[][] matrix = new double[3][3];
        double[] vector = new double[3];
        for (int index = 0; index < observations.size(); index++) {
            TrendObservation observation = observations.get(index);
            double x = observation.xMeters();
            double[] powers = {1.0, x, x * x, x * x * x, x * x * x * x};
            for (int row = 0; row < 3; row++) {
                vector[row] += weights[index] * powers[row] * observation.transverseMeters();
                for (int column = 0; column < 3; column++) {
                    matrix[row][column] += weights[index] * powers[row + column];
                }
            }
        }
        double[] solution = solveLinearSystem(matrix, vector);
        return solution == null ? null : new QuadraticTrend(solution[0], solution[1], solution[2]);
    }

    private static double[] solveLinearSystem(double[][] matrix, double[] vector) {
        double[][] work = new double[3][4];
        for (int row = 0; row < 3; row++) {
            System.arraycopy(matrix[row], 0, work[row], 0, 3);
            work[row][3] = vector[row];
        }
        for (int column = 0; column < 3; column++) {
            int pivot = column;
            for (int row = column + 1; row < 3; row++) {
                if (Math.abs(work[row][column]) > Math.abs(work[pivot][column])) {
                    pivot = row;
                }
            }
            if (Math.abs(work[pivot][column]) <= 1.0e-10) {
                return null;
            }
            double[] swap = work[column];
            work[column] = work[pivot];
            work[pivot] = swap;
            double divisor = work[column][column];
            for (int index = column; index < 4; index++) {
                work[column][index] /= divisor;
            }
            for (int row = 0; row < 3; row++) {
                if (row == column) continue;
                double factor = work[row][column];
                for (int index = column; index < 4; index++) {
                    work[row][index] -= factor * work[column][index];
                }
            }
        }
        double[] result = {work[0][3], work[1][3], work[2][3]};
        return java.util.Arrays.stream(result).allMatch(Double::isFinite) ? result : null;
    }

    private static double medianAbsoluteDeviation(List<Double> values) {
        List<Double> ordered = values.stream().sorted().toList();
        double center = median(ordered);
        return median(ordered.stream().map(value -> Math.abs(value - center)).sorted().toList());
    }

    private static double median(List<Double> ordered) {
        int middle = ordered.size() / 2;
        return (ordered.size() & 1) == 0
                ? 0.5 * (ordered.get(middle - 1) + ordered.get(middle)) : ordered.get(middle);
    }

    private static void inspectTerminalKink(List<MetricPoint> points, ImageCostField image,
            double onset, boolean start, List<Finding> findings, DirectedSamplingMemo sampling) {
        int endIndex = start ? 0 : points.size() - 1;
        int apexIndex = start ? 1 : points.size() - 2;
        int approachIndex = start ? 2 : points.size() - 3;
        MetricPoint end = points.get(endIndex);
        MetricPoint apex = points.get(apexIndex);
        MetricPoint approach = points.get(approachIndex);
        double amplitude = pointSegmentDistance(apex, approach, end);
        List<MetricPoint> route = start ? List.of(end, apex, approach) : List.of(approach, apex, end);
        double routeCost = sampling.routePolylineCost(route, image);
        double chordCost = image.meanRouteSegmentCost(approach, end);
        SupportMetrics support = supportMetrics(route, image, image.sourcePitchMeters(), sampling);
        boolean directlySupported = support.directLength >= 0.95 * support.totalLength;
        boolean costsFinite = Double.isFinite(routeCost) && Double.isFinite(chordCost);
        boolean routeUnknownWithinSourceUncertainty = !Double.isFinite(routeCost)
                && Double.isFinite(chordCost)
                && support.worstUnsupportedSpan <= image.sourcePitchMeters();
        boolean supportedBend = directlySupported
                || routeUnknownWithinSourceUncertainty
                || costsFinite && routeCost <= chordCost + 0.02;
        if (amplitude > onset && !supportedBend) {
            findings.add(review(FindingCode.UNSUPPORTED_TERMINAL_KINK,
                    Math.min(endIndex, approachIndex), Math.max(endIndex, approachIndex), amplitude));
        }
    }

    private static void inspectIncidentCrossings(Request request, List<Finding> findings) {
        for (List<MetricPoint> incident : request.proposedIncidentGeometry()) {
            for (int candidateSegment = 0; candidateSegment < request.points().size() - 1; candidateSegment++) {
                for (int incidentSegment = 0; incidentSegment < incident.size() - 1; incidentSegment++) {
                    IntersectionKind kind = intersection(request.points().get(candidateSegment),
                            request.points().get(candidateSegment + 1), incident.get(incidentSegment),
                            incident.get(incidentSegment + 1));
                    if (kind != IntersectionKind.NONE && !sharedTerminalContact(kind, request.points(), incident,
                            candidateSegment, incidentSegment)) {
                        findings.add(hard(FindingCode.PREJUNCTION_CROSSING, candidateSegment,
                                candidateSegment + 1, 0));
                    }
                }
            }
        }
    }

    private static boolean sharedTerminalContact(IntersectionKind kind, List<MetricPoint> candidate,
            List<MetricPoint> incident,
            int candidateSegment, int incidentSegment) {
        if (kind != IntersectionKind.TOUCH) {
            return false;
        }
        boolean candidateTerminal = candidateSegment == 0 || candidateSegment == candidate.size() - 2;
        boolean incidentTerminal = incidentSegment == 0 || incidentSegment == incident.size() - 2;
        if (!candidateTerminal || !incidentTerminal) {
            return false;
        }
        Set<MetricPoint> candidateEnds = Set.of(candidate.get(0), candidate.get(candidate.size() - 1));
        return candidateEnds.contains(incident.get(0)) || candidateEnds.contains(incident.get(incident.size() - 1));
    }

    private static SupportMetrics supportMetrics(List<MetricPoint> points, ImageCostField image,
            double pitch, DirectedSamplingMemo sampling) {
        double total = 0.0;
        double direct = 0.0;
        double currentUnsupported = 0.0;
        double worstUnsupported = 0.0;
        for (int segment = 0; segment < points.size() - 1; segment++) {
            SupportSamples samples = sampling.support(points.get(segment), points.get(segment + 1),
                image, pitch);
            for (boolean supported : samples.directlyLocalized()) {
                total += samples.pieceMeters();
                if (supported) {
                    direct += samples.pieceMeters();
                    currentUnsupported = 0.0;
                } else {
                    currentUnsupported += samples.pieceMeters();
                    worstUnsupported = Math.max(worstUnsupported, currentUnsupported);
                }
            }
        }
        return new SupportMetrics(total, direct, worstUnsupported);
    }

    /** Evaluation-local bounded cache which preserves directed support sample order. */
    static final class DirectedSamplingMemo {
        private final int capacity;
        private final Map<SupportKey, SupportSamples> supports;
        private final Map<RouteCostKey, Double> routeCosts;

        DirectedSamplingMemo(int capacity) {
            this.capacity = capacity;
            this.supports = new LinkedHashMap<>(capacity + 1, 0.75f, true);
            this.routeCosts = new LinkedHashMap<>(capacity + 1, 0.75f, true);
        }

        double routePolylineCost(List<MetricPoint> points, ImageCostField image) {
            double weighted = 0.0;
            double length = 0.0;
            for (int index = 1; index < points.size(); index++) {
                MetricPoint start = points.get(index - 1);
                MetricPoint end = points.get(index);
                double segmentLength = start.distanceTo(end);
                if (segmentLength <= 1.0e-12) {
                    continue;
                }
                double cost = routeSegmentCost(start, end, image);
                if (!Double.isFinite(cost)) {
                    return Double.POSITIVE_INFINITY;
                }
                weighted += segmentLength * cost;
                length += segmentLength;
            }
            return length > 0.0 ? weighted / length : Double.POSITIVE_INFINITY;
        }

        private double routeSegmentCost(MetricPoint start, MetricPoint end, ImageCostField image) {
            RouteCostKey key = new RouteCostKey(start, end);
            Double cached = routeCosts.get(key);
            if (cached != null) {
                return cached;
            }
            double cost = image.meanRouteSegmentCost(start, end);
            routeCosts.put(key, cost);
            if (routeCosts.size() > capacity) {
                routeCosts.remove(routeCosts.keySet().iterator().next());
            }
            return cost;
        }

        SupportSamples support(MetricPoint start, MetricPoint end, ImageCostField image, double pitch) {
            if (start.distanceTo(end) <= 1.0e-12) {
                return SupportSamples.EMPTY;
            }
            SupportKey key = new SupportKey(start, end, pitch);
            SupportSamples cached = supports.get(key);
            if (cached != null) {
                return cached;
            }
            double step = Math.min(1.0, pitch / 2.0);
            double length = start.distanceTo(end);
            int count = Math.max(1, (int) Math.ceil(length / step));
            double piece = length / count;
            MetricPoint tangent = subtract(end, start);
            boolean[] direct = new boolean[count];
            for (int index = 0; index < count; index++) {
                MetricPoint point = interpolate(start, end, (index + 0.5) / count);
                direct[index] = image.sampleRoute(point, tangent)
                    .map(ImageCostField.RouteSample::directlyLocalized).orElse(false);
            }
            SupportSamples value = new SupportSamples(direct, piece);
            supports.put(key, value);
            if (supports.size() > capacity) {
                supports.remove(supports.keySet().iterator().next());
            }
            return value;
        }
    }

    private record SupportKey(MetricPoint start, MetricPoint end, double pitch) { }

    private record RouteCostKey(MetricPoint start, MetricPoint end) { }

    private record SupportSamples(boolean[] directlyLocalized, double pieceMeters) {
        private static final SupportSamples EMPTY = new SupportSamples(new boolean[0], 0.0);
    }

    private static double roughness(List<MetricPoint> points) {
        points = uniformSamples(points, 1.0);
        double total = 0.0;
        double length = 0.0;
        for (int index = 1; index < points.size() - 1; index++) {
            MetricPoint first = subtract(points.get(index), points.get(index - 1));
            MetricPoint second = subtract(points.get(index + 1), points.get(index));
            double denominator = norm(first) * norm(second);
            if (denominator > 0.0) {
                double cosine = Math.max(-1.0, Math.min(1.0, dot(first, second) / denominator));
                double localLength = 0.5 * (norm(first) + norm(second));
                total += localLength * StrictMath.acos(cosine);
                length += localLength;
            }
        }
        return length > 0.0 ? total / length : 0.0;
    }

    private static List<MetricPoint> uniformSamples(List<MetricPoint> points, double spacingMeters) {
        double[] chainage = chainage(points);
        double totalLength = chainage[chainage.length - 1];
        int sampleCount = Math.max(2, (int) Math.ceil(totalLength / spacingMeters) + 1);
        List<MetricPoint> samples = new ArrayList<>(sampleCount);
        int segment = 0;
        for (int index = 0; index < sampleCount; index++) {
            double target = index == sampleCount - 1 ? totalLength
                    : index * totalLength / (sampleCount - 1.0);
            while (segment + 1 < chainage.length - 1 && chainage[segment + 1] < target) {
                segment++;
            }
            double segmentLength = chainage[segment + 1] - chainage[segment];
            double fraction = segmentLength > 0.0 ? (target - chainage[segment]) / segmentLength : 0.0;
            samples.add(interpolate(points.get(segment), points.get(segment + 1), fraction));
        }
        return List.copyOf(samples);
    }

    private static List<Finding> deduplicate(List<Finding> findings) {
        return findings.stream().sorted(Comparator.comparing(Finding::code)
                .thenComparingInt(Finding::firstVertex).thenComparingInt(Finding::lastVertex))
                .distinct().toList();
    }

    private static Finding review(FindingCode code, int first, int last, double amplitude) {
        return new Finding(code, Severity.REVIEW, first, last, amplitude);
    }

    private static Finding hard(FindingCode code, int first, int last, double amplitude) {
        return new Finding(code, Severity.HARD_BLOCK, first, last, amplitude);
    }

    private static double[] chainage(List<MetricPoint> points) {
        double[] result = new double[points.size()];
        for (int index = 1; index < points.size(); index++) {
            result[index] = result[index - 1] + points.get(index - 1).distanceTo(points.get(index));
        }
        return result;
    }

    private static IntersectionKind intersection(MetricPoint a, MetricPoint b, MetricPoint c, MetricPoint d) {
        double o1 = orientation(a, b, c);
        double o2 = orientation(a, b, d);
        double o3 = orientation(c, d, a);
        double o4 = orientation(c, d, b);
        double epsilon = 1.0e-9;
        if (o1 * o2 < -epsilon && o3 * o4 < -epsilon) {
            return IntersectionKind.PROPER;
        }
        boolean collinear = Math.abs(o1) <= epsilon && Math.abs(o2) <= epsilon
                && Math.abs(o3) <= epsilon && Math.abs(o4) <= epsilon;
        if (collinear) {
            double overlap = overlapLength(a, b, c, d);
            if (overlap > epsilon) {
                return IntersectionKind.OVERLAP;
            }
        }
        if (Math.abs(o1) <= epsilon && onSegment(a, b, c)
                || Math.abs(o2) <= epsilon && onSegment(a, b, d)
                || Math.abs(o3) <= epsilon && onSegment(c, d, a)
                || Math.abs(o4) <= epsilon && onSegment(c, d, b)) {
            return IntersectionKind.TOUCH;
        }
        return IntersectionKind.NONE;
    }

    private static double overlapLength(MetricPoint a, MetricPoint b, MetricPoint c, MetricPoint d) {
        boolean useX = Math.abs(b.xMeters() - a.xMeters()) >= Math.abs(b.yMeters() - a.yMeters());
        double a0 = useX ? a.xMeters() : a.yMeters();
        double a1 = useX ? b.xMeters() : b.yMeters();
        double c0 = useX ? c.xMeters() : c.yMeters();
        double c1 = useX ? d.xMeters() : d.yMeters();
        return Math.max(0.0, Math.min(Math.max(a0, a1), Math.max(c0, c1))
                - Math.max(Math.min(a0, a1), Math.min(c0, c1)));
    }

    private static boolean onSegment(MetricPoint a, MetricPoint b, MetricPoint point) {
        return point.xMeters() >= Math.min(a.xMeters(), b.xMeters()) - 1.0e-9
                && point.xMeters() <= Math.max(a.xMeters(), b.xMeters()) + 1.0e-9
                && point.yMeters() >= Math.min(a.yMeters(), b.yMeters()) - 1.0e-9
                && point.yMeters() <= Math.max(a.yMeters(), b.yMeters()) + 1.0e-9;
    }

    private static double pointSegmentDistance(MetricPoint point, MetricPoint start, MetricPoint end) {
        MetricPoint delta = subtract(end, start);
        double lengthSquared = dot(delta, delta);
        if (lengthSquared == 0.0) {
            return point.distanceTo(start);
        }
        double fraction = dot(subtract(point, start), delta) / lengthSquared;
        fraction = Math.max(0.0, Math.min(1.0, fraction));
        return point.distanceTo(interpolate(start, end, fraction));
    }

    private static MetricPoint interpolate(MetricPoint a, MetricPoint b, double fraction) {
        return new MetricPoint(a.xMeters() + fraction * (b.xMeters() - a.xMeters()),
                a.yMeters() + fraction * (b.yMeters() - a.yMeters()));
    }

    private static MetricPoint subtract(MetricPoint a, MetricPoint b) {
        return new MetricPoint(a.xMeters() - b.xMeters(), a.yMeters() - b.yMeters());
    }

    private static double norm(MetricPoint point) {
        return StrictMath.hypot(point.xMeters(), point.yMeters());
    }

    private static double dot(MetricPoint first, MetricPoint second) {
        return first.xMeters() * second.xMeters() + first.yMeters() * second.yMeters();
    }

    private static double orientation(MetricPoint a, MetricPoint b, MetricPoint c) {
        return (b.xMeters() - a.xMeters()) * (c.yMeters() - a.yMeters())
                - (b.yMeters() - a.yMeters()) * (c.xMeters() - a.xMeters());
    }

    private enum IntersectionKind { NONE, PROPER, TOUCH, OVERLAP }

    private enum LocalComparison { CONTRADICTED, SUPPORTED, AMBIGUOUS }

    private record TrendObservation(double xMeters, double transverseMeters) { }

    private record QuadraticTrend(double intercept, double linear, double quadratic) {
        double value(double xMeters) {
            return intercept + linear * xMeters + quadratic * xMeters * xMeters;
        }
    }

    private record PhysicalSample(MetricPoint point, double chainageMeters,
            int segmentIndex, int originalVertex) { }

    private record ResidualPoint(PhysicalSample sample, double residualMeters) { }

    private record ResidualPlateau(ResidualPoint point, double middleChainageMeters) { }

    private record ProfileKey(MetricPoint point, MetricPoint tangent) { }

    private record WindowMeasurement(WindowCandidate candidate, boolean rowBudgetExceeded) { }

    private record BudgetWindow(double startChainageMeters, double endChainageMeters) {
        double spanMeters() {
            return endChainageMeters - startChainageMeters;
        }
    }

    private record SingleApexCandidate(int firstVertex, int lastVertex, int apexVertex,
            double amplitudeMeters, double spanMeters, double startChainageMeters,
            double endChainageMeters) {
        boolean betterCenteredThan(SingleApexCandidate other, double apexChainageMeters) {
            double centerDistance = Math.abs(0.5 * (startChainageMeters
                    + endChainageMeters) - apexChainageMeters);
            double otherCenterDistance = Math.abs(0.5 * (other.startChainageMeters
                    + other.endChainageMeters) - apexChainageMeters);
            int centered = Double.compare(centerDistance, otherCenterDistance);
            if (centered != 0) return centered < 0;
            int amplitude = Double.compare(amplitudeMeters, other.amplitudeMeters);
            if (amplitude != 0) return amplitude > 0;
            int start = Double.compare(startChainageMeters, other.startChainageMeters);
            if (start != 0) return start < 0;
            int end = Double.compare(endChainageMeters, other.endChainageMeters);
            if (end != 0) return end < 0;
            if (firstVertex != other.firstVertex) return firstVertex < other.firstVertex;
            return lastVertex < other.lastVertex;
        }

        boolean betterThan(SingleApexCandidate other) {
            int span = Double.compare(spanMeters, other.spanMeters);
            if (span != 0) return span < 0;
            int amplitude = Double.compare(amplitudeMeters, other.amplitudeMeters);
            if (amplitude != 0) return amplitude > 0;
            int start = Double.compare(startChainageMeters, other.startChainageMeters);
            if (start != 0) return start < 0;
            int end = Double.compare(endChainageMeters, other.endChainageMeters);
            if (end != 0) return end < 0;
            if (firstVertex != other.firstVertex) return firstVertex < other.firstVertex;
            return lastVertex < other.lastVertex;
        }
    }

    private record WindowCandidate(double startChainageMeters, double endChainageMeters,
            List<ResidualPoint> lobes, int qualifiedReversals, double amplitudeMeters) {
        double firstLobeChainageMeters() {
            return lobes.get(0).sample().chainageMeters();
        }

        double lastLobeChainageMeters() {
            return lobes.get(lobes.size() - 1).sample().chainageMeters();
        }

        boolean betterThan(WindowCandidate other) {
            int reversals = Integer.compare(qualifiedReversals, other.qualifiedReversals);
            if (reversals != 0) return reversals > 0;
            int amplitude = Double.compare(amplitudeMeters, other.amplitudeMeters);
            if (amplitude != 0) return amplitude > 0;
            double span = endChainageMeters - startChainageMeters;
            double otherSpan = other.endChainageMeters - other.startChainageMeters;
            if (Double.compare(span, otherSpan) != 0) return span < otherSpan;
            return startChainageMeters < other.startChainageMeters;
        }
    }

    private static final class MutableLocalWarningStats {
        private long windowVisits;
        private long qualifyingWindows;
        private long candidateComparisons;
        private long maximumDiagnosticRows;
        private long maximumCachedProfileRows;
        private long rowBudgetAbstentions;
        private long singleApexRows;
        private long singleApexComparisons;
        private long singleApexProfileAmbiguities;
        private long singleApexOrientationAmbiguities;
        private long singleApexResidualAmbiguities;
        private long singleApexSegmentAmbiguities;
        private long singleApexChordEvaluations;
        private long singleApexScanBudgetAbstentions;

        LocalWarningStats snapshot() {
            return new LocalWarningStats(windowVisits, qualifyingWindows, candidateComparisons,
                    maximumDiagnosticRows, maximumCachedProfileRows, rowBudgetAbstentions,
                    singleApexComparisons, singleApexProfileAmbiguities,
                    singleApexOrientationAmbiguities, singleApexResidualAmbiguities,
                    singleApexSegmentAmbiguities, singleApexChordEvaluations,
                    singleApexScanBudgetAbstentions);
        }
    }

    private static final class ProfileCache {
        private final Map<ProfileKey, FrozenProfile> profiles =
                new LinkedHashMap<>(256, 0.75f, true);
        private final MutableLocalWarningStats stats;
        private long retainedRows;

        private ProfileCache(MutableLocalWarningStats stats) {
            this.stats = stats;
        }

        FrozenProfile getOrMeasure(ImageCostField image, ProfileKey key,
                CancellationProbe cancellation) {
            FrozenProfile cached = profiles.get(key);
            if (cached != null) {
                return cached;
            }
            FrozenProfile measured = image.freezeProfileWithCoreOrientation(
                    key.point(), key.tangent(), cancellation);
            int rows = measured.sampleCount();
            if (rows > MAXIMUM_CACHED_PROFILE_ROWS) {
                return null;
            }
            while (!profiles.isEmpty()
                    && retainedRows + rows > MAXIMUM_CACHED_PROFILE_ROWS) {
                var iterator = profiles.entrySet().iterator();
                Map.Entry<ProfileKey, FrozenProfile> eldest = iterator.next();
                retainedRows -= eldest.getValue().sampleCount();
                iterator.remove();
            }
            profiles.put(key, measured);
            retainedRows += rows;
            stats.maximumCachedProfileRows = Math.max(stats.maximumCachedProfileRows, retainedRows);
            return measured;
        }
    }

    private static final class PhysicalSampleIndex {
        private final List<MetricPoint> points;
        private final double[] vertexChainage;
        private final int uniformIntervals;
        private final double spacingMeters;
        private final Map<Integer, PhysicalSample> uniformCache = new LinkedHashMap<>(2_049, 0.75f, true);

        private PhysicalSampleIndex(List<MetricPoint> points, double[] vertexChainage,
                int uniformIntervals, double spacingMeters) {
            this.points = points;
            this.vertexChainage = vertexChainage;
            this.uniformIntervals = uniformIntervals;
            this.spacingMeters = spacingMeters;
        }

        static PhysicalSampleIndex create(List<MetricPoint> points, double[] vertexChainage,
                double maximumSpacingMeters, CancellationProbe cancellation) {
            cancellation.checkpoint();
            double total = vertexChainage[vertexChainage.length - 1];
            if (!(total > 0.0)) {
                return new PhysicalSampleIndex(points, vertexChainage, 1, maximumSpacingMeters);
            }
            long requestedIntervals = (long) Math.ceil(total / maximumSpacingMeters);
            if (requestedIntervals > Integer.MAX_VALUE - 1L) {
                throw new IllegalArgumentException("Local physical sampling exceeds indexed address space");
            }
            int intervals = Math.max(1, (int) requestedIntervals);
            return new PhysicalSampleIndex(points, vertexChainage, intervals, total / intervals);
        }

        int uniformCount() {
            return uniformIntervals + 1;
        }

        PhysicalSample uniformAt(int index) {
            if (index < 0 || index > uniformIntervals) {
                throw new IndexOutOfBoundsException(index);
            }
            PhysicalSample cached = uniformCache.get(index);
            if (cached != null) {
                return cached;
            }
            PhysicalSample row = sampleAt(index == uniformIntervals
                    ? totalLengthMeters() : index * spacingMeters);
            uniformCache.put(index, row);
            if (uniformCache.size() > 2_048) {
                uniformCache.remove(uniformCache.keySet().iterator().next());
            }
            return row;
        }

        double totalLengthMeters() {
            return vertexChainage[vertexChainage.length - 1];
        }

        double vertexChainage(int index) {
            return vertexChainage[index];
        }

        List<PhysicalSample> uniformWindow(double start, double end) {
            int firstInterior = Math.max(0, (int) Math.floor(start / spacingMeters) + 1);
            int lastInterior = Math.min(uniformIntervals - 1,
                    (int) Math.ceil(end / spacingMeters) - 1);
            long rowCount = Math.max(0, lastInterior - firstInterior + 1L) + 2L;
            if (rowCount > MAXIMUM_LOCAL_WINDOW_ROWS) {
                return null;
            }
            List<PhysicalSample> rows = new ArrayList<>((int) rowCount);
            rows.add(sampleAt(start));
            for (int index = firstInterior; index <= lastInterior; index++) {
                PhysicalSample row = uniformAt(index);
                if (row.chainageMeters() > start + 1.0e-9
                        && row.chainageMeters() < end - 1.0e-9) {
                    rows.add(row);
                }
            }
            rows.add(sampleAt(end));
            return List.copyOf(rows);
        }

        List<PhysicalSample> diagnosticWindow(double start, double end,
                CancellationProbe cancellation, MutableLocalWarningStats stats) {
            List<PhysicalSample> uniform = uniformWindow(start, end);
            if (uniform == null) {
                return null;
            }
            int firstVertex = lowerBound(vertexChainage, start - 1.0e-9);
            int lastVertex = upperBound(vertexChainage, end + 1.0e-9);
            if ((long) uniform.size() + lastVertex - firstVertex
                    > MAXIMUM_LOCAL_WINDOW_ROWS) {
                return null;
            }
            List<PhysicalSample> rows = new ArrayList<>(uniform);
            for (int index = firstVertex; index < lastVertex; index++) {
                if ((index & 1023) == 0) cancellation.checkpoint();
                rows.add(new PhysicalSample(points.get(index), vertexChainage[index],
                        Math.max(0, Math.min(points.size() - 2, index)), index));
            }
            List<PhysicalSample> distinct = orderedDistinct(rows);
            stats.maximumDiagnosticRows = Math.max(stats.maximumDiagnosticRows, distinct.size());
            return distinct;
        }

        List<PhysicalSample> comparisonWindow(double start, double end,
                Set<Integer> protectedIndices, CancellationProbe cancellation,
                MutableLocalWarningStats stats) {
            List<PhysicalSample> uniform = uniformWindow(start, end);
            if (uniform == null) {
                return null;
            }
            long protectedRows = 0;
            for (int index : protectedIndices) {
                if (vertexChainage[index] >= start - 1.0e-9
                        && vertexChainage[index] <= end + 1.0e-9) {
                    protectedRows++;
                }
            }
            if ((long) uniform.size() + protectedRows > MAXIMUM_LOCAL_WINDOW_ROWS) {
                return null;
            }
            List<PhysicalSample> rows = new ArrayList<>(uniform);
            int visited = 0;
            for (int index : protectedIndices) {
                if ((visited++ & 1023) == 0) cancellation.checkpoint();
                if (vertexChainage[index] >= start - 1.0e-9
                        && vertexChainage[index] <= end + 1.0e-9) {
                    rows.add(new PhysicalSample(points.get(index), vertexChainage[index],
                            Math.max(0, Math.min(points.size() - 2, index)), index));
                }
            }
            List<PhysicalSample> distinct = orderedDistinct(rows);
            stats.maximumDiagnosticRows = Math.max(stats.maximumDiagnosticRows, distinct.size());
            return distinct;
        }

        private static List<PhysicalSample> orderedDistinct(List<PhysicalSample> rows) {
            rows.sort(Comparator.comparingDouble(PhysicalSample::chainageMeters)
                    .thenComparingInt(row -> row.originalVertex() < 0 ? 1 : 0));
            List<PhysicalSample> distinct = new ArrayList<>(rows.size());
            for (PhysicalSample row : rows) {
                if (!distinct.isEmpty()
                        && Math.abs(row.chainageMeters()
                                - distinct.get(distinct.size() - 1).chainageMeters()) <= 1.0e-9) {
                    if (distinct.get(distinct.size() - 1).originalVertex() < 0
                            && row.originalVertex() >= 0) {
                        distinct.set(distinct.size() - 1, row);
                    }
                } else {
                    distinct.add(row);
                }
            }
            return List.copyOf(distinct);
        }

        PhysicalSample sampleAt(double target) {
            double bounded = Math.max(0.0, Math.min(totalLengthMeters(), target));
            int segment = Math.max(0, Math.min(points.size() - 2,
                    upperBound(vertexChainage, bounded) - 1));
            while (segment + 1 < vertexChainage.length - 1
                    && vertexChainage[segment + 1] - vertexChainage[segment] <= 1.0e-12) {
                segment++;
            }
            double segmentLength = vertexChainage[segment + 1] - vertexChainage[segment];
            double fraction = segmentLength > 0.0
                    ? (bounded - vertexChainage[segment]) / segmentLength : 0.0;
            int original = -1;
            if (Math.abs(bounded - vertexChainage[segment]) <= 1.0e-12) {
                original = segment;
            } else if (Math.abs(bounded - vertexChainage[segment + 1]) <= 1.0e-12) {
                original = segment + 1;
            }
            return new PhysicalSample(interpolate(points.get(segment), points.get(segment + 1),
                    fraction), bounded, segment, original);
        }

        private static int lowerBound(double[] values, double target) {
            int low = 0;
            int high = values.length;
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (values[middle] < target) {
                    low = middle + 1;
                } else {
                    high = middle;
                }
            }
            return low;
        }

        private static int upperBound(double[] values, double target) {
            int low = 0;
            int high = values.length;
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (values[middle] <= target) {
                    low = middle + 1;
                } else {
                    high = middle;
                }
            }
            return low;
        }

        MetricPoint tangentAt(double chainageMeters) {
            double left = Math.max(0.0, chainageMeters - spacingMeters);
            double right = Math.min(totalLengthMeters(), chainageMeters + spacingMeters);
            if (!(right > left)) {
                return new MetricPoint(1.0, 0.0);
            }
            return subtract(sampleAt(right).point(), sampleAt(left).point());
        }

        boolean isProtected(double chainageMeters, Set<Integer> protectedIndices) {
            for (int index : protectedIndices) {
                if (Math.abs(vertexChainage[index] - chainageMeters) <= 1.0e-9) {
                    return true;
                }
            }
            return false;
        }

        int firstVertex(double chainageMeters) {
            return Math.max(0, Math.min(points.size() - 1,
                    upperBound(vertexChainage, chainageMeters - 1.0e-9) - 1));
        }

        int lastVertex(double chainageMeters) {
            return Math.min(points.size() - 1,
                    lowerBound(vertexChainage, chainageMeters - 1.0e-9));
        }
    }

    private record SupportMetrics(double totalLength, double directLength, double worstUnsupportedSpan) {
    }
}
