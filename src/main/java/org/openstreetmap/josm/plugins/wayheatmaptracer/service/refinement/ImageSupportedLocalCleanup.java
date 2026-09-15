package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.ExistingWayNodeOccurrence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Cleans independently measurable image-supported intervals without moving frozen islands. */
public final class ImageSupportedLocalCleanup {
    /** Requested cleanup behavior. */
    public enum Mode { OFF, REDUCE_POINTS_ONLY, REFIT_AND_REDUCE }

    /** Whole-candidate cleanup outcome. */
    public enum Status { SKIPPED, UNCHANGED, CLEANED, PARTIALLY_CLEANED, REJECTED }

    /** Outcome of one maximal local interval. */
    public enum IntervalDisposition {
        CLEANED,
        UNCHANGED,
        REJECTED,
        FROZEN_MISSING_EVIDENCE,
        FROZEN_PROTECTED_BOUNDARY,
        SKIPPED
    }

    /** Immutable local interval diagnostic. */
    public record IntervalResult(int firstOriginalIndex, int lastOriginalIndex,
            IntervalDisposition disposition, boolean changed, List<FinalRoutePointId> frozenOccurrenceIds,
            String detail) {
        /** Copies local diagnostic collections. */
        public IntervalResult {
            frozenOccurrenceIds = List.copyOf(frozenOccurrenceIds);
            Objects.requireNonNull(disposition, "disposition");
            Objects.requireNonNull(detail, "detail");
        }
    }

    /** Immutable local cleanup request with occurrence identity and original assignments. */
    public record Request(List<FinalRoutePointId> occurrenceIds, List<MetricPoint> points,
            Set<Integer> retainedIndices, Set<Integer> protectedIndices,
            ImageCostField image, MetricRegion branchCorridor, Mode mode,
            ImageSupportedRefitter.Config refitConfig, double reductionToleranceMeters,
            Map<FinalRoutePointId, MetricPoint> originalAssignments,
            List<ImageSupportedRefitter.GeometryValidator> validators) {
        /** Validates aligned occurrence identity and deeply copies containers. */
        public Request {
            occurrenceIds = List.copyOf(occurrenceIds);
            points = List.copyOf(points);
            retainedIndices = Set.copyOf(retainedIndices);
            protectedIndices = Set.copyOf(protectedIndices);
            originalAssignments = Map.copyOf(new LinkedHashMap<>(originalAssignments));
            validators = List.copyOf(validators);
            Objects.requireNonNull(image, "image");
            Objects.requireNonNull(branchCorridor, "branchCorridor");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(refitConfig, "refitConfig");
            boolean invalidRetainedIndex = false;
            for (int index : retainedIndices) {
                invalidRetainedIndex |= index < 0 || index >= points.size();
            }
            boolean invalidProtectedIndex = false;
            for (int index : protectedIndices) {
                invalidProtectedIndex |= index < 0 || index >= points.size();
            }
            boolean assignmentsMatch = originalAssignments.keySet()
                    .equals(new LinkedHashSet<>(occurrenceIds));
            boolean occurrenceOrderValid = validExistingOccurrenceOrder(occurrenceIds);
            for (int index = 0; assignmentsMatch && index < points.size(); index++) {
                assignmentsMatch = points.get(index).equals(
                        originalAssignments.get(occurrenceIds.get(index)));
            }
            if (points.size() < 2 || points.size() != occurrenceIds.size()
                    || new LinkedHashSet<>(occurrenceIds).size() != occurrenceIds.size()
                    || invalidRetainedIndex || invalidProtectedIndex
                    || !occurrenceOrderValid || !assignmentsMatch
                    || !Double.isFinite(reductionToleranceMeters) || reductionToleranceMeters < 0.0) {
                throw new IllegalArgumentException("Cleanup occurrence and geometry inputs are inconsistent");
            }
        }

        private static boolean validExistingOccurrenceOrder(List<FinalRoutePointId> identities) {
            Map<PrimitiveKey, Integer> lastOccurrence = new LinkedHashMap<>();
            Map<PrimitiveKey, Set<Integer>> occupiedIndexes = new LinkedHashMap<>();
            Map<PrimitiveKey, Set<PrimitiveKey>> nodesByWay = new LinkedHashMap<>();
            for (FinalRoutePointId identity : identities) {
                if (!(identity instanceof ExistingWayNodeOccurrence existing)) {
                    continue;
                }
                int index = existing.originalOccurrenceIndex();
                Integer previous = lastOccurrence.put(existing.wayKey(), index);
                if ((previous != null && index <= previous)
                        || !occupiedIndexes.computeIfAbsent(existing.wayKey(),
                                ignored -> new LinkedHashSet<>()).add(index)
                        || !nodesByWay.computeIfAbsent(existing.wayKey(),
                                ignored -> new LinkedHashSet<>()).add(existing.nodeKey())) {
                    return false;
                }
            }
            return true;
        }
    }

    /** Immutable cleaned sibling geometry, interval diagnostics and freshly rebuilt assignments. */
    public record Result(Status status, List<FinalRoutePointId> occurrenceIds,
            List<MetricPoint> points, List<IntervalResult> intervals,
            Map<FinalRoutePointId, MetricPoint> assignments) {
        /** Copies all output containers. */
        public Result {
            occurrenceIds = List.copyOf(occurrenceIds);
            points = List.copyOf(points);
            intervals = List.copyOf(intervals);
            assignments = Map.copyOf(new LinkedHashMap<>(assignments));
            boolean aligned = occurrenceIds.size() == points.size()
                    && assignments.keySet().equals(new LinkedHashSet<>(occurrenceIds));
            for (int index = 0; aligned && index < points.size(); index++) {
                aligned = points.get(index).equals(assignments.get(occurrenceIds.get(index)));
            }
            if (!aligned || new LinkedHashSet<>(occurrenceIds).size() != occurrenceIds.size()) {
                throw new IllegalArgumentException("Cleanup result assignments are incomplete or reordered");
            }
        }
    }

    private final ImageSupportedRefitter refitter = new ImageSupportedRefitter();

    /** Evaluates and cleans each directly measurable interval independently. */
    public Result clean(Request request) {
        return clean(request, CancellationProbe.NONE);
    }

    /** Evaluates local intervals with cooperative cancellation through refitting and reduction. */
    public Result clean(Request request, CancellationProbe cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        cancellation.checkpoint();
        if (request.mode() == Mode.OFF) {
            ImageSupportedRefitter.Validation validation = validateWholeGeometry(request, request.points());
            if (!validation.feasible()) {
                return result(Status.REJECTED, request.occurrenceIds(), request.points(),
                        List.of(new IntervalResult(0, request.points().size() - 1,
                                IntervalDisposition.REJECTED, false, List.of(), validation.code())));
            }
            return result(Status.SKIPPED, request.occurrenceIds(), request.points(),
                    List.of(new IntervalResult(0, request.points().size() - 1,
                            IntervalDisposition.SKIPPED, false, List.of(), "cleanup-off")));
        }

        boolean[] supported = new boolean[request.points().size()];
        for (int index = 0; index < supported.length; index++) {
            cancellation.checkpoint();
            supported[index] = request.image().supports(request.points().get(index));
        }
        List<Run> runs = partition(supported);
        List<FinalRoutePointId> outputIds = new ArrayList<>();
        List<MetricPoint> outputPoints = new ArrayList<>();
        List<IntervalResult> intervalResults = new ArrayList<>();
        boolean anyChanged = false;
        boolean anyFrozen = false;
        boolean anyRejected = false;
        boolean anyNonconverged = false;
        boolean anyEvaluated = false;

        for (Run run : runs) {
            cancellation.checkpoint();
            List<FinalRoutePointId> runIds = request.occurrenceIds().subList(run.first, run.last + 1);
            List<MetricPoint> runPoints = request.points().subList(run.first, run.last + 1);
            if (!run.supported || runPoints.size() < 3) {
                append(outputIds, outputPoints, runIds, runPoints);
                anyFrozen = true;
                intervalResults.add(new IntervalResult(run.first, run.last,
                        IntervalDisposition.FROZEN_MISSING_EVIDENCE, false, runIds,
                        run.supported ? "too-short-for-independent-cleanup" : "missing-current-image-evidence"));
                continue;
            }

            Set<Integer> localFixed = new LinkedHashSet<>();
            Set<Integer> localRetained = new LinkedHashSet<>();
            anyEvaluated = true;
            localFixed.add(0);
            localFixed.add(runPoints.size() - 1);
            localRetained.add(0);
            localRetained.add(runPoints.size() - 1);
            for (int global : request.retainedIndices()) {
                if (global >= run.first && global <= run.last) {
                    localRetained.add(global - run.first);
                }
            }
            for (int global : request.protectedIndices()) {
                if (global >= run.first && global <= run.last) {
                    localFixed.add(global - run.first);
                }
            }
            List<MetricPoint> refined = runPoints;
            boolean rejected = false;
            boolean refitNonconverged = false;
            if (request.mode() == Mode.REFIT_AND_REDUCE) {
                ImageSupportedRefitter.Request refitRequest = new ImageSupportedRefitter.Request(
                        runPoints, runPoints, localFixed, request.image(), request.branchCorridor(),
                        ImageSupportedRefitter.Mode.IMAGE_SUPPORTED, request.refitConfig(), request.validators());
                ImageSupportedRefitter.Result refit = refitter.refit(refitRequest, cancellation);
                rejected = refit.status() == ImageSupportedRefitter.Status.REVERTED;
                refitNonconverged = refit.status() != ImageSupportedRefitter.Status.CONVERGED;
                anyNonconverged |= refitNonconverged;
                if (!rejected && refit.acceptedAlternative()) {
                    refined = refit.points();
                }
            }

            Reduction reduction = reduce(runIds, refined, localRetained, request.image(),
                    request.branchCorridor(), request.reductionToleranceMeters(), cancellation);
            boolean moved = maximumDistance(runPoints, refined) > 0.05;
            boolean changed = moved || reduction.points.size() < runPoints.size();
            if (rejected && !changed) {
                anyRejected = true;
                append(outputIds, outputPoints, runIds, runPoints);
                intervalResults.add(new IntervalResult(run.first, run.last,
                        IntervalDisposition.REJECTED, false, List.of(), "refit-validation-rejected"));
            } else {
                append(outputIds, outputPoints, reduction.ids, reduction.points);
                anyChanged |= changed;
                String detail = !changed ? "no-beneficial-change"
                        : request.mode() == Mode.REFIT_AND_REDUCE
                                && refitNonconverged
                        ? "retained-nonconverged-refit" : "image-supported-local-change";
                intervalResults.add(new IntervalResult(run.first, run.last,
                        changed ? IntervalDisposition.CLEANED : IntervalDisposition.UNCHANGED,
                        changed, List.of(), detail));
            }
        }

        String provenanceFailure = validateRetainedAndProtected(request, outputIds, outputPoints);
        if (provenanceFailure != null) {
            List<IntervalResult> rejectedIntervals = new ArrayList<>(intervalResults);
            rejectedIntervals.add(new IntervalResult(0, request.points().size() - 1,
                    IntervalDisposition.REJECTED, false, List.of(), provenanceFailure));
            return result(Status.REJECTED, request.occurrenceIds(), request.points(), rejectedIntervals);
        }
        cancellation.checkpoint();
        ImageSupportedRefitter.Validation validation = validateWholeGeometry(request, List.copyOf(outputPoints));
        if (!validation.feasible()) {
            List<IntervalResult> rejectedIntervals = new ArrayList<>(intervalResults);
            rejectedIntervals.add(new IntervalResult(0, request.points().size() - 1,
                    IntervalDisposition.REJECTED, false, List.of(), validation.code()));
            return result(Status.REJECTED, request.occurrenceIds(), request.points(), rejectedIntervals);
        }

        Status status;
        if (!anyEvaluated) {
            status = Status.SKIPPED;
        } else if (anyChanged && (anyFrozen || anyRejected || anyNonconverged)) {
            status = Status.PARTIALLY_CLEANED;
        } else if (anyChanged) {
            status = Status.CLEANED;
        } else if (anyRejected) {
            status = Status.REJECTED;
        } else {
            status = Status.UNCHANGED;
        }
        return result(status, outputIds, outputPoints, intervalResults);
    }

    private static Result result(Status status, List<FinalRoutePointId> ids,
            List<MetricPoint> points, List<IntervalResult> intervals) {
        Map<FinalRoutePointId, MetricPoint> assignments = new LinkedHashMap<>();
        for (int index = 0; index < ids.size(); index++) {
            assignments.put(ids.get(index), points.get(index));
        }
        return new Result(status, ids, points, intervals, assignments);
    }

    private static List<Run> partition(boolean[] supported) {
        List<Run> runs = new ArrayList<>();
        int first = 0;
        while (first < supported.length) {
            int last = first;
            while (last + 1 < supported.length && supported[last + 1] == supported[first]) {
                last++;
            }
            runs.add(new Run(first, last, supported[first]));
            first = last + 1;
        }
        return runs;
    }

    private static String validateRetainedAndProtected(Request request,
            List<FinalRoutePointId> outputIds, List<MetricPoint> outputPoints) {
        Map<FinalRoutePointId, Integer> outputIndex = new LinkedHashMap<>();
        for (int index = 0; index < outputIds.size(); index++) {
            if (outputIndex.put(outputIds.get(index), index) != null) {
                return "duplicate-final-occurrence-identity";
            }
        }
        Map<FinalRoutePointId, Integer> originalIndex = new LinkedHashMap<>();
        for (int index = 0; index < request.occurrenceIds().size(); index++) {
            originalIndex.put(request.occurrenceIds().get(index), index);
        }
        int previous = -1;
        for (FinalRoutePointId id : outputIds) {
            int original = originalIndex.getOrDefault(id, -1);
            if (original < 0 || original <= previous) {
                return "missing-or-reordered-final-occurrence-identity";
            }
            previous = original;
        }
        for (int original : request.retainedIndices()) {
            FinalRoutePointId id = request.occurrenceIds().get(original);
            if (!outputIndex.containsKey(id)) {
                return "missing-retained-occurrence-identity";
            }
        }
        for (int original : request.protectedIndices()) {
            FinalRoutePointId id = request.occurrenceIds().get(original);
            Integer current = outputIndex.get(id);
            if (current != null
                    && !request.originalAssignments().get(id).equals(outputPoints.get(current))) {
                return "protected-occurrence-coordinate-changed";
            }
        }
        return null;
    }

    private static ImageSupportedRefitter.Validation validateWholeGeometry(Request request,
            List<MetricPoint> points) {
        for (ImageSupportedRefitter.GeometryValidator validator : request.validators()) {
            ImageSupportedRefitter.Validation validation = validator.validate(points);
            if (!validation.feasible()) {
                return validation;
            }
        }
        return ImageSupportedRefitter.Validation.accepted();
    }

    private static Reduction reduce(List<FinalRoutePointId> ids, List<MetricPoint> points,
            Set<Integer> protectedIndices,
            ImageCostField image, MetricRegion branchCorridor, double tolerance,
            CancellationProbe cancellation) {
        if (tolerance <= 0.0 || points.size() <= 2) {
            return new Reduction(List.copyOf(ids), List.copyOf(points));
        }
        boolean[] keep = new boolean[points.size()];
        keep[0] = true;
        keep[points.size() - 1] = true;
        for (int index : protectedIndices) {
            keep[index] = true;
        }
        reduceRange(points, 0, points.size() - 1, tolerance, image, branchCorridor, keep,
                protectedIndices, cancellation);
        List<FinalRoutePointId> reducedIds = new ArrayList<>();
        List<MetricPoint> reducedPoints = new ArrayList<>();
        for (int index = 0; index < points.size(); index++) {
            if (keep[index]) {
                reducedIds.add(ids.get(index));
                reducedPoints.add(points.get(index));
            }
        }
        return new Reduction(List.copyOf(reducedIds), List.copyOf(reducedPoints));
    }

    private static void reduceRange(List<MetricPoint> points, int first, int last, double tolerance,
            ImageCostField image, MetricRegion branchCorridor, boolean[] keep,
            Set<Integer> protectedIndices, CancellationProbe cancellation) {
        cancellation.checkpoint();
        if (last <= first + 1) {
            return;
        }
        for (int index = first + 1; index < last; index++) {
            if (protectedIndices.contains(index)) {
                keep[index] = true;
                reduceRange(points, first, index, tolerance, image, branchCorridor, keep,
                        protectedIndices, cancellation);
                reduceRange(points, index, last, tolerance, image, branchCorridor, keep,
                        protectedIndices, cancellation);
                return;
            }
        }
        double maximum = -1.0;
        int maximumIndex = -1;
        for (int index = first + 1; index < last; index++) {
            double distance = pointSegmentDistance(points.get(index), points.get(first), points.get(last));
            if (distance > maximum) {
                maximum = distance;
                maximumIndex = index;
            }
        }
        double originalCost = image.meanPolylineCost(points.subList(first, last + 1));
        double chordCost = image.meanSegmentCost(points.get(first), points.get(last));
        boolean chordSupported = Double.isFinite(chordCost) && Double.isFinite(originalCost)
                && chordCost <= originalCost + 0.02
                && branchCorridor.containsSegment(points.get(first), points.get(last));
        if (maximum <= tolerance && chordSupported) {
            return;
        }
        keep[maximumIndex] = true;
        reduceRange(points, first, maximumIndex, tolerance, image, branchCorridor, keep,
                protectedIndices, cancellation);
        reduceRange(points, maximumIndex, last, tolerance, image, branchCorridor, keep,
                protectedIndices, cancellation);
    }

    private static double pointSegmentDistance(MetricPoint point, MetricPoint start, MetricPoint end) {
        double dx = end.xMeters() - start.xMeters();
        double dy = end.yMeters() - start.yMeters();
        double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared == 0.0) {
            return point.distanceTo(start);
        }
        double fraction = ((point.xMeters() - start.xMeters()) * dx
                + (point.yMeters() - start.yMeters()) * dy) / lengthSquared;
        fraction = Math.max(0.0, Math.min(1.0, fraction));
        return point.distanceTo(new MetricPoint(start.xMeters() + fraction * dx,
                start.yMeters() + fraction * dy));
    }

    private static double maximumDistance(List<MetricPoint> first, List<MetricPoint> second) {
        double maximum = 0.0;
        for (int index = 0; index < first.size(); index++) {
            maximum = Math.max(maximum, first.get(index).distanceTo(second.get(index)));
        }
        return maximum;
    }

    private static void append(List<FinalRoutePointId> targetIds,
            List<MetricPoint> targetPoints, List<FinalRoutePointId> ids,
            List<MetricPoint> points) {
        targetIds.addAll(ids);
        targetPoints.addAll(points);
    }

    private record Run(int first, int last, boolean supported) {
    }

    private record Reduction(List<FinalRoutePointId> ids, List<MetricPoint> points) {
    }
}
