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
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField;

/** Evaluates the exact final-preview polyline with physical local and topology checks. */
public final class FinalGeometryEvaluator {
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
        PROTECTED_ASSIGNMENT_MISMATCH
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

    /** Evaluates topology, localized excursions, image support and deterministic eligibility. */
    public Result evaluate(Request request) {
        List<Finding> findings = new ArrayList<>();
        DirectedSamplingMemo sampling = new DirectedSamplingMemo(2_048);
        inspectProtectedAssignments(request, findings);
        inspectIntersections(request, findings);
        inspectBacktracks(request.points(), findings);
        inspectLocalExcursions(request, findings, sampling);
        inspectIncidentCrossings(request, findings);
        if (request.branchAmbiguous()) {
            findings.add(review(FindingCode.AMBIGUOUS_BRANCH, 0, request.points().size() - 1, 0));
        }
        if (request.searchTruncated()) {
            findings.add(review(FindingCode.SEARCH_TRUNCATED, 0, request.points().size() - 1, 0));
        }
        if (request.optimizerFailed()) {
            findings.add(review(FindingCode.OPTIMIZER_FAILURE, 0, request.points().size() - 1, 0));
        }

        SupportMetrics support = supportMetrics(request.points(), request.image(), request.sourcePitchMeters(), sampling);
        double meanImageCenterCost = request.image().meanRoutePolylineCost(request.points());
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
        return new Result(request.id(), disposition, findings, support.totalLength, support.directLength,
                support.worstUnsupportedSpan, meanImageCenterCost,
                roughness(request.points()));
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
            DirectedSamplingMemo sampling) {
        double onset = Math.max(0.75, 0.5 * request.sourcePitchMeters());
        double[] chainage = chainage(request.points());
        int reversalCount = 0;
        double previousTurn = 0.0;
        for (int index = 1; index < request.points().size() - 1; index++) {
            double turn = orientation(request.points().get(index - 1), request.points().get(index),
                    request.points().get(index + 1));
            if (previousTurn * turn < 0.0) {
                reversalCount++;
            }
            if (Math.abs(turn) > 1.0e-9) {
                previousTurn = turn;
            }
        }
        if (reversalCount >= 3) {
            findings.add(review(FindingCode.REPEATED_SHORT_WAVE_WRINKLE, 0,
                    request.points().size() - 1, reversalCount));
        }

        for (int first = 0; first < request.points().size() - 2; first++) {
            for (int last = first + 2; last < request.points().size(); last++) {
                double span = chainage[last] - chainage[first];
                if (span > 20.0) {
                    break;
                }
                if (span < 4.0) {
                    continue;
                }
                double amplitude = 0.0;
                for (int index = first + 1; index < last; index++) {
                    amplitude = Math.max(amplitude, pointSegmentDistance(request.points().get(index),
                            request.points().get(first), request.points().get(last)));
                }
                if (amplitude <= onset) {
                    continue;
                }
                List<MetricPoint> local = request.points().subList(first, last + 1);
                double routeCost = request.image().meanRoutePolylineCost(local);
                double chordCost = request.image().meanRouteSegmentCost(request.points().get(first),
                        request.points().get(last));
                SupportMetrics localSupport = supportMetrics(local, request.image(),
                        request.sourcePitchMeters(), sampling);
                boolean directlySupported = localSupport.directLength >= 0.95 * localSupport.totalLength;
                boolean costsFinite = Double.isFinite(routeCost) && Double.isFinite(chordCost);
                boolean routeUnknownWithinSourceUncertainty = !Double.isFinite(routeCost)
                        && Double.isFinite(chordCost)
                        && localSupport.worstUnsupportedSpan <= request.sourcePitchMeters();
                boolean supportedBend = directlySupported
                        || routeUnknownWithinSourceUncertainty
                        || costsFinite && routeCost <= chordCost + 0.02;
                if (!supportedBend) {
                    findings.add(review(FindingCode.UNSUPPORTED_ISOLATED_EXCURSION,
                            first, last, amplitude));
                }
            }
        }

        if (request.points().size() >= 3) {
            inspectTerminalKink(request.points(), request.image(), onset, false, findings, sampling);
            inspectTerminalKink(request.points(), request.image(), onset, true, findings, sampling);
        }
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
        double routeCost = image.meanRoutePolylineCost(route);
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
    private static final class DirectedSamplingMemo {
        private final int capacity;
        private final Map<SupportKey, SupportSamples> supports;

        DirectedSamplingMemo(int capacity) {
            this.capacity = capacity;
            this.supports = new LinkedHashMap<>(capacity + 1, 0.75f, true);
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
                total += localLength * Math.acos(cosine);
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
        return Math.hypot(point.xMeters(), point.yMeters());
    }

    private static double dot(MetricPoint first, MetricPoint second) {
        return first.xMeters() * second.xMeters() + first.yMeters() * second.yMeters();
    }

    private static double orientation(MetricPoint a, MetricPoint b, MetricPoint c) {
        return (b.xMeters() - a.xMeters()) * (c.yMeters() - a.yMeters())
                - (b.yMeters() - a.yMeters()) * (c.xMeters() - a.xMeters());
    }

    private enum IntersectionKind { NONE, PROPER, TOUCH, OVERLAP }

    private record SupportMetrics(double totalLength, double directLength, double worstUnsupportedSpan) {
    }
}
