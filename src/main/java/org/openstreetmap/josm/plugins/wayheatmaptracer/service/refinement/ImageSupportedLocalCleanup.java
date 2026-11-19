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
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupInterval;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
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

    /** Explicit native-control entry. A retained identity alone never fixes a coordinate.
     * The mandatory safety guard must certify topology, the source envelope, and exact
     * changed-segment raster-cell validity on the complete stored output. Discrete source
     * probes below are fit checks, not a continuous cell-support certificate. The optional reduction
     * certificate must additionally certify broad features on that reduced polyline.
     */
    public record LongScaleRequest(Request cleanup, FinalControlBasis basis,
            Map<Integer, Integer> nativeAliases, List<ObservationOwnership> sourceOwnership,
            boolean sourceAvailable, long maximumWorkVisits,
            ImageSupportedRefitter.GeometryValidator safetyGuard,
            java.util.Optional<ImageSupportedRefitter.GeometryValidator> reductionCertificate) {
        public LongScaleRequest {
            Objects.requireNonNull(cleanup); Objects.requireNonNull(basis);
            Objects.requireNonNull(safetyGuard); Objects.requireNonNull(reductionCertificate);
            nativeAliases = Map.copyOf(nativeAliases);
            sourceOwnership = List.copyOf(sourceOwnership);
            Set<Integer> nativeSlots = new LinkedHashSet<>(nativeAliases.values());
            if (!basis.initialCoordinates().equals(cleanup.points())
                    || sourceOwnership.size() != basis.size() || maximumWorkVisits < 0
                    || !basis.protectedIndices().containsAll(cleanup.protectedIndices())
                    || Math.abs(cleanup.image().sourcePitchMeters()
                        - cleanup.refitConfig().sourcePitchMeters()) > 1e-9
                    || nativeSlots.size() != nativeAliases.size())
                throw new IllegalArgumentException("Native cleanup authority is inconsistent");
            for (int raw = 0; raw < nativeAliases.size(); raw++) {
                Integer slot = nativeAliases.get(raw);
                if (slot == null || slot < 0 || slot >= basis.size()
                        || !basis.rows().get(slot).independent())
                    throw new IllegalArgumentException("Native aliases must be complete independent slots");
                if (cleanup.occurrenceIds().get(slot) instanceof FinalRoutePointId.GeneratedCandidatePoint generated
                        && generated.originalPointIndex() != raw)
                    throw new IllegalArgumentException("Generated occurrence contradicts native origin");
            }
            for (int slot : basis.independentIndices())
                if (!nativeSlots.contains(slot) && !basis.protectedIndices().contains(slot))
                    throw new IllegalArgumentException("Unowned independent control");
        }
    }

    /** Proposal-only wrapper. Changed output always needs session-local review and revalidation. */
    public record LongScaleResult(Result cleanup,
            java.util.Optional<LongScaleNormalFairing.Result> proposal,
            boolean reviewRequired, List<CleanupInterval> eligibility) {
        public LongScaleResult { eligibility = List.copyOf(eligibility); }
    }

    /** Runs one budgeted native proposal without changing the historical cleanup entry. */
    public LongScaleResult cleanLongScale(LongScaleRequest nativeRequest, CancellationProbe cancellation) {
        Objects.requireNonNull(nativeRequest); Objects.requireNonNull(cancellation);
        cancellation.checkpoint();
        Request request = nativeRequest.cleanup();
        if (request.mode() != Mode.REFIT_AND_REDUCE)
            return new LongScaleResult(clean(request, cancellation), java.util.Optional.empty(), false, List.of());
        if (!nativeRequest.sourceAvailable())
            return unavailableLongScale(request, "source-cleanup-unavailable");
        double[] chainage = new double[request.points().size()];
        for (int i = 1; i < chainage.length; i++)
            chainage[i] = chainage[i-1] + request.points().get(i-1).distanceTo(request.points().get(i));
        double spacing = Math.min(1., request.image().sourcePitchMeters() * .5);
        long count = 0;
        for (int i = 1; i < chainage.length; i++) {
            double needed = Math.max(1., Math.ceil((chainage[i]-chainage[i-1])/spacing));
            if (!Double.isFinite(needed) || needed > 65_536 || (count += (long)needed + 1) > 65_536)
                return unavailableLongScale(request, "source-probe-resource-limit");
        }
        double halfWidth = Math.max(4 * request.image().sourcePitchMeters(),
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.EvidenceModelParameters
                    .defaults().localization().routeProfileHalfWidthMeters());
        double samples = Math.ceil(2 * halfWidth / (request.image().sourcePitchMeters() * .25)) + 2;
        // Reserve conservatively for retained profile arrays, sample objects and transient
        // extraction storage before asking ImageCostField to allocate any frozen profile.
        if (!Double.isFinite(samples) || samples > 65_536
                || (count + 2) * (512 + 64 * samples) > 64L * 1024 * 1024)
            return unavailableLongScale(request, "source-profile-resource-limit");
        // Profile authority is frozen at the original native slots, before any trial. Unknown
        // curvature is deliberately absent from this eligibility rule.
        var controls = nativeRequest.basis().independentIndices();
        var frozen = new java.util.TreeSet<>(nativeRequest.basis().protectedIndices());
        frozen.add(0); frozen.add(request.points().size()-1);
        var nativeSlots = new LinkedHashSet<>(nativeRequest.nativeAliases().values());
        for (int c = 0; c < controls.size(); c++) {
            cancellation.checkpoint(); int slot = controls.get(c);
            boolean direct = nativeSlots.contains(slot)
                    && nativeRequest.sourceOwnership().get(slot) == ObservationOwnership.DIRECT_TWO_SIDED;
            MetricPoint direction = tangent(request.points(), slot);
            if (direction.distanceTo(new MetricPoint(0,0)) <= 1e-12)
                return unavailableLongScale(request, "source-degenerate-tangent");
            var profile = request.image().freezeProfile(request.points().get(slot), direction, cancellation);
            if (!direct || profile.support() != ImageCostField.FrozenSupport.MEASURED
                    || profile.observedModeStatus() != ImageCostField.ObservedModeStatus.OBSERVED_UNIQUE) {
                frozen.add(slot);
                // The nearest observed controls bound the missing/censored island; a moved
                // neighboring endpoint must not modify an uncertified segment into that island.
                if (c > 0) frozen.add(controls.get(c-1));
                if (c+1 < controls.size()) frozen.add(controls.get(c+1));
            }
        }
        List<CleanupInterval> intervals = new ArrayList<>();
        List<Integer> boundaries = List.copyOf(frozen);
        for (int i = 1; i < boundaries.size(); i++) {
            int first = boundaries.get(i-1), last = boundaries.get(i);
            int direct = 0;
            for (int slot : controls) if (slot > first && slot < last && !frozen.contains(slot)) direct++;
            intervals.add(new CleanupInterval(first, last, chainage[first], chainage[last],
                    direct, direct > 0, direct > 0, direct > 0,
                    List.of(direct > 0 ? "native-source-island" : "frozen-source-boundary")));
        }
        List<SourceProbe> probes = new ArrayList<>();
        for (int i = 1; i < chainage.length; i++) {
            int divisions = Math.max(1, (int)Math.ceil((chainage[i]-chainage[i-1])/spacing));
            MetricPoint direction = difference(request.points().get(i), request.points().get(i-1));
            if (direction.distanceTo(new MetricPoint(0,0)) <= 1e-12)
                return unavailableLongScale(request, "source-degenerate-segment");
            for (int j = 0; j <= divisions; j++) {
                cancellation.checkpoint(); double t = (double)j/divisions;
                MetricPoint point = interpolate(request.points().get(i-1), request.points().get(i), t);
                var profile = request.image().freezeProfile(point, direction, cancellation);
                probes.add(new SourceProbe(i-1, t, point, profile));
            }
        }
        ImageSupportedRefitter.GeometryValidator sourceGuard = points -> validateNativeSource(
                nativeRequest, probes, request.occurrenceIds(), points, chainage, cancellation);
        List<ImageSupportedRefitter.GeometryValidator> guards = new ArrayList<>(request.validators());
        guards.add(sourceGuard); guards.add(nativeRequest.safetyGuard());
        var proposal = LongScaleNormalFairing.propose(new LongScaleNormalFairing.Request(
                nativeRequest.basis(), request.image().sourcePitchMeters(), intervals,
                guards, nativeRequest.maximumWorkVisits()), cancellation);
        List<MetricPoint> points = proposal.points();
        List<FinalRoutePointId> ids = request.occurrenceIds();
        String reductionReason = "reduction-not-requested";
        if (request.reductionToleranceMeters() > 0
                && proposal.status() != LongScaleNormalFairing.Status.REJECTED
                && proposal.status() != LongScaleNormalFairing.Status.REFUSED_RESOURCE) {
            reductionReason = "reduction-validation-unavailable";
            if (nativeRequest.reductionCertificate().isPresent()) {
                Set<Integer> keep = new LinkedHashSet<>(request.retainedIndices());
                keep.addAll(frozen); keep.addAll(request.protectedIndices());
                Reduction reduced = reduce(ids, points, keep, request.image(), request.branchCorridor(),
                        request.reductionToleranceMeters(), cancellation);
                var validation = validateNativeSource(nativeRequest, probes, reduced.ids(),
                        reduced.points(), chainage, cancellation);
                if (validation.feasible()) validation = nativeRequest.reductionCertificate().orElseThrow()
                        .validate(reduced.points());
                if (validation.feasible()) validation = nativeRequest.safetyGuard().validate(reduced.points());
                if (validation.feasible()) validation = validateWholeGeometry(request, reduced.points());
                reductionReason = validation.feasible() ? "stored-reduction-certified" : validation.code();
                if (validation.feasible()) { ids = reduced.ids(); points = reduced.points(); }
            }
        }
        String failure = validateRetainedAndProtected(request, ids, points);
        if (failure != null) return unavailableLongScale(request, failure);
        boolean changed = !ids.equals(request.occurrenceIds()) || !points.equals(request.points());
        List<IntervalResult> diagnostics = new ArrayList<>();
        for (CleanupInterval interval : intervals) if (!interval.smoothingEligible())
            diagnostics.add(new IntervalResult(interval.startIndex(), interval.endIndex(),
                    IntervalDisposition.FROZEN_MISSING_EVIDENCE, false, List.of(), "frozen-source-boundary"));
        for (var interval : proposal.intervals()) diagnostics.add(new IntervalResult(interval.firstIndex(),
                interval.lastIndex(), switch(interval.disposition()) {
                    case FROZEN -> IntervalDisposition.FROZEN_MISSING_EVIDENCE;
                    case REJECTED -> IntervalDisposition.REJECTED;
                    case UNCHANGED -> IntervalDisposition.UNCHANGED;
                    case PROPOSED -> IntervalDisposition.CLEANED;
                }, interval.disposition() == LongScaleNormalFairing.Disposition.PROPOSED,
                List.of(), interval.reason()));
        diagnostics.add(new IntervalResult(0, request.points().size()-1, IntervalDisposition.SKIPPED,
                false, List.of(), reductionReason));
        boolean partial = diagnostics.stream().anyMatch(i -> i.disposition() == IntervalDisposition.FROZEN_MISSING_EVIDENCE
                || i.disposition() == IntervalDisposition.REJECTED);
        Status status = changed ? partial ? Status.PARTIALLY_CLEANED : Status.CLEANED
                : proposal.status() == LongScaleNormalFairing.Status.REJECTED
                    || proposal.status() == LongScaleNormalFairing.Status.REFUSED_RESOURCE ? Status.REJECTED : Status.UNCHANGED;
        return new LongScaleResult(result(status, ids, points, diagnostics), java.util.Optional.of(proposal),
                changed, intervals);
    }

    private static LongScaleResult unavailableLongScale(Request request, String reason) {
        return new LongScaleResult(result(Status.SKIPPED, request.occurrenceIds(), request.points(),
                List.of(new IntervalResult(0, request.points().size()-1,
                        IntervalDisposition.FROZEN_MISSING_EVIDENCE, false, List.of(), reason))),
                java.util.Optional.empty(), false, List.of());
    }

    private record SourceProbe(int segment, double fraction, MetricPoint origin,
            ImageCostField.FrozenProfile profile) { }

    private static ImageSupportedRefitter.Validation validateNativeSource(LongScaleRequest nativeRequest,
            List<SourceProbe> probes, List<FinalRoutePointId> ids, List<MetricPoint> points,
            double[] chainage, CancellationProbe cancellation) {
        Request request = nativeRequest.cleanup();
        Map<FinalRoutePointId,Integer> original = new LinkedHashMap<>();
        for (int i=0; i<request.occurrenceIds().size(); i++) original.put(request.occurrenceIds().get(i), i);
        int reducedSegment = 0;
        for (SourceProbe probe : probes) {
            cancellation.checkpoint();
            double s = chainage[probe.segment()] + probe.fraction()
                    * (chainage[probe.segment()+1]-chainage[probe.segment()]);
            while (reducedSegment+2 < ids.size()
                    && chainage[original.get(ids.get(reducedSegment+1))] < s) reducedSegment++;
            int a = original.get(ids.get(reducedSegment)), b = original.get(ids.get(reducedSegment+1));
            if (b == a+1 && points.get(reducedSegment).equals(request.points().get(a))
                    && points.get(reducedSegment+1).equals(request.points().get(b))) continue;
            double width = chainage[b]-chainage[a];
            if (!(width > 0)) return ImageSupportedRefitter.Validation.rejected("source-degenerate-segment");
            MetricPoint point = interpolate(points.get(reducedSegment), points.get(reducedSegment+1),
                    (s-chainage[a])/width);
            // Frozen exterior/missing islands need no invented certificate when exact.
            if (point.equals(probe.origin())) continue;
            var actualFit = validateActualSourceSample(request.image(), probe.origin(), point);
            if (!actualFit.feasible()) return actualFit;
            var profile = probe.profile();
            var before = profile.evaluate(probe.origin()); var after = profile.evaluate(point);
            if (profile.support() != ImageCostField.FrozenSupport.MEASURED
                    || profile.observedModeStatus() != ImageCostField.ObservedModeStatus.OBSERVED_UNIQUE
                    || before.isEmpty() || after.isEmpty()
                    || point.distanceTo(probe.origin()) > 1.25 * request.image().sourcePitchMeters()
                    || after.orElseThrow().cost() > before.orElseThrow().cost() + .02)
                return ImageSupportedRefitter.Validation.rejected("frozen-native-branch-fit");
        }
        for (int i=1; i<points.size(); i++) {
            cancellation.checkpoint(); int a=original.get(ids.get(i-1)), b=original.get(ids.get(i));
            if (b == a+1 && points.get(i-1).equals(request.points().get(a))
                    && points.get(i).equals(request.points().get(b))) continue;
            double before = request.image().meanPolylineCost(request.points().subList(a,b+1));
            double after = request.image().meanSegmentCost(points.get(i-1),points.get(i));
            if (!request.branchCorridor().containsSegment(points.get(i-1),points.get(i))
                    || !Double.isFinite(before) || !Double.isFinite(after) || after > before+.02)
                return ImageSupportedRefitter.Validation.rejected("stored-segment-source-fit");
        }
        return ImageSupportedRefitter.Validation.accepted();
    }

    /** Actual 2D source fit, kept separate from the one-dimensional frozen branch field. */
    static ImageSupportedRefitter.Validation validateActualSourceSample(ImageCostField image,
            MetricPoint origin, MetricPoint point) {
        var before = image.sample(origin); var after = image.sample(point);
        if (before.isEmpty() || after.isEmpty())
            return ImageSupportedRefitter.Validation.rejected("actual-source-sample-unavailable");
        double originalCost = before.orElseThrow().presenceCost() + before.orElseThrow().centerCost();
        double proposedCost = after.orElseThrow().presenceCost() + after.orElseThrow().centerCost();
        return Double.isFinite(originalCost) && Double.isFinite(proposedCost) && proposedCost <= originalCost + .02
                ? ImageSupportedRefitter.Validation.accepted()
                : ImageSupportedRefitter.Validation.rejected("actual-source-sample-fit");
    }

    private static MetricPoint tangent(List<MetricPoint> points, int slot) {
        return difference(points.get(Math.min(points.size()-1,slot+1)), points.get(Math.max(0,slot-1)));
    }
    private static MetricPoint difference(MetricPoint a, MetricPoint b) {
        return new MetricPoint(a.xMeters()-b.xMeters(),a.yMeters()-b.yMeters());
    }
    private static MetricPoint interpolate(MetricPoint a, MetricPoint b, double t) {
        if (t == 0) return a; if (t == 1) return b;
        return new MetricPoint(a.xMeters()+t*(b.xMeters()-a.xMeters()), a.yMeters()+t*(b.yMeters()-a.yMeters()));
    }

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
