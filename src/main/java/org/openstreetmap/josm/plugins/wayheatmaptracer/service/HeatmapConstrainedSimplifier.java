package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateCleanupEvidence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateCleanupProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupEvidenceProvenance;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupSamplingProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.HeatmapConstrainedSimplificationResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.HeatmapConstrainedSimplificationResult.ChordRejection;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.HeatmapConstrainedSimplificationResult.FailureReason;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.HeatmapConstrainedSimplificationResult.Metrics;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.HeatmapConstrainedSimplificationResult.Status;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProjectedLateralTransform;

/**
 * Performs pure Douglas-Peucker reduction constrained by candidate-owned heatmap evidence.
 *
 * <p>Every accepted chord is evaluated at each retained physical-chainage profile. Lateral
 * raster offsets are converted to ground metres only through the factual slide-time scale stored
 * in {@code CleanupSamplingFrame}. Retained points keep their exact projected coordinates and
 * order; the service never redistributes points or mutates JOSM primitives.</p>
 */
public final class HeatmapConstrainedSimplifier {
    private static final double EPSILON = 1e-9;
    private static final double MIN_AUTHORIZING_SIGNAL = 1e-6;
    private static final double MIN_BEND_PROTECTION = 0.45;
    private static final double MIN_AMBIGUITY_PROTECTION = 0.65;

    /** Creates a stateless constrained simplifier. */
    public HeatmapConstrainedSimplifier() {
        // Stateless service.
    }

    /**
     * One independently reduced source-index interval whose endpoints remain exact.
     *
     * @param startIndex inclusive protected start index
     * @param endIndex inclusive protected end index
     */
    public record ProtectedInterval(int startIndex, int endIndex) {
        /** Validates local ordering; invocation validates geometry bounds and overlap. */
        public ProtectedInterval {
            if (startIndex < 0 || endIndex <= startIndex) {
                throw new IllegalArgumentException("Protected interval must contain at least one segment");
            }
        }
    }

    /**
     * Reduces projected final-preview geometry under physical and heatmap constraints.
     *
     * @param finalPreview projected geometry aligned one-to-one with cleanup evidence
     * @param protectedIntervals explicit independent intervals; their endpoints remain exact
     * @param protectedPointIndexes additional fixed/shared/tagged/junction/endpoint source indexes
     * @param evidence candidate-owned scalar and selected-corridor evidence
     * @param config slide-time cleanup configuration
     * @return immutable retained geometry and structured diagnostics
     */
    public HeatmapConstrainedSimplificationResult simplify(
        List<EastNorth> finalPreview,
        List<ProtectedInterval> protectedIntervals,
        Collection<Integer> protectedPointIndexes,
        CandidateCleanupEvidence evidence,
        GeometryCleanupConfig config
    ) {
        Objects.requireNonNull(finalPreview, "finalPreview");
        Objects.requireNonNull(protectedIntervals, "protectedIntervals");
        Objects.requireNonNull(protectedPointIndexes, "protectedPointIndexes");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(config, "config");

        List<EastNorth> source = copyGeometry(finalPreview);
        LinkedHashSet<FailureReason> reasons = new LinkedHashSet<>();
        if (source == null || !validGeometry(source)) {
            reasons.add(FailureReason.INVALID_GEOMETRY);
            return unchanged(source == null ? List.of() : source, Status.REJECTED, reasons,
                List.of(), Set.of(), 0);
        }
        IntervalValidation intervalValidation = validateIntervals(
            source.size(), protectedIntervals, protectedPointIndexes);
        if (!intervalValidation.valid()) {
            reasons.add(FailureReason.INVALID_PROTECTED_INTERVALS);
            return unchanged(source, Status.REJECTED, reasons, List.of(), Set.of(), 0);
        }
        if (hasTopologyContact(source)) {
            reasons.add(FailureReason.SOURCE_TOPOLOGY_UNSAFE);
            return unchanged(source, Status.REJECTED, reasons, List.of(),
                intervalValidation.protectedIndexes(), 0);
        }
        if (config.mode() == GeometryCleanupMode.NONE) {
            reasons.add(FailureReason.MODE_DISABLED);
            return unchanged(source, Status.UNCHANGED, reasons, List.of(),
                intervalValidation.protectedIndexes(), 0);
        }
        if (!evidence.eligible()) {
            reasons.add(FailureReason.INELIGIBLE_EVIDENCE);
            return unchanged(source, Status.REJECTED, reasons, List.of(),
                intervalValidation.protectedIndexes(), 0);
        }
        if (!alignedEvidence(source, evidence)) {
            reasons.add(FailureReason.MISALIGNED_EVIDENCE);
            return unchanged(source, Status.REJECTED, reasons, List.of(),
                intervalValidation.protectedIndexes(), 0);
        }

        Set<Integer> mandatory = mandatoryIndexes(intervalValidation.protectedIndexes(), evidence);
        List<ProtectedInterval> reductionSpans = splitAtMandatory(
            intervalValidation.orderedIntervals(), mandatory);
        int supportedAnchorCount = countSupportedAnchors(evidence);
        double groundScale = evidence.samplingFrame().groundMetersPerRasterPixel();
        if (!evidence.samplingFrame().hasGroundScale()) {
            reasons.add(FailureReason.MISSING_GROUND_SCALE);
            List<ChordRejection> rejections = new ArrayList<>();
            for (ProtectedInterval span : reductionSpans) {
                if (span.endIndex() - span.startIndex() > 1) {
                    rejections.add(new ChordRejection(
                        span.startIndex(), span.endIndex(), -1, FailureReason.MISSING_GROUND_SCALE));
                }
            }
            return unchanged(source, Status.UNCHANGED, reasons, rejections,
                intervalValidation.protectedIndexes(), supportedAnchorCount);
        }

        ReductionState state = new ReductionState(source.size());
        for (ProtectedInterval interval : intervalValidation.orderedIntervals()) {
            for (ProtectedInterval span : splitAtMandatory(List.of(interval), mandatory)) {
                reduceSpan(source, span, evidence, config, groundScale, state);
            }
        }
        reasons.addAll(state.reasons);
        List<Integer> retainedIndexes = retainedIndexes(state.retained);
        List<EastNorth> simplified = retainedGeometry(source, retainedIndexes);
        if (hasTopologyContact(simplified)) {
            reasons.add(FailureReason.TOPOLOGY_CONTACT);
            return unchanged(source, Status.REJECTED, reasons, state.rejections,
                intervalValidation.protectedIndexes(), supportedAnchorCount);
        }
        Status status = retainedIndexes.size() < source.size() ? Status.SIMPLIFIED : Status.UNCHANGED;
        Metrics metrics = new Metrics(
            source.size(), simplified.size(), intervalValidation.protectedIndexes().size(),
            supportedAnchorCount, state.attemptedChordCount, state.acceptedChordCount,
            state.containmentFailureCount,
            state.acceptedChordCount == 0
                ? OptionalDouble.empty() : OptionalDouble.of(state.maximumRemovedDeviationMeters),
            state.acceptedChordCount == 0
                ? OptionalDouble.empty() : OptionalDouble.of(state.worstFitRetention),
            1.0);
        return new HeatmapConstrainedSimplificationResult(
            simplified, retainedIndexes, status, List.copyOf(reasons), state.rejections, metrics);
    }

    private void reduceSpan(
        List<EastNorth> source,
        ProtectedInterval span,
        CandidateCleanupEvidence evidence,
        GeometryCleanupConfig config,
        double groundScale,
        ReductionState state
    ) {
        if (span.endIndex() - span.startIndex() <= 1) {
            return;
        }
        Deque<ProtectedInterval> pending = new ArrayDeque<>();
        pending.push(span);
        while (!pending.isEmpty()) {
            ProtectedInterval current = pending.pop();
            if (current.endIndex() - current.startIndex() <= 1) {
                continue;
            }
            state.attemptedChordCount++;
            ChordEvaluation evaluation = evaluateChord(
                source, current, evidence, config, groundScale);
            if (evaluation.accepted()) {
                for (int index = current.startIndex() + 1; index < current.endIndex(); index++) {
                    state.retained[index] = false;
                }
                state.acceptedChordCount++;
                state.maximumRemovedDeviationMeters = Math.max(
                    state.maximumRemovedDeviationMeters, evaluation.maximumDeviationMeters());
                state.worstFitRetention = Math.min(
                    state.worstFitRetention, evaluation.worstFitRetention());
                continue;
            }

            state.reasons.add(evaluation.reason());
            state.containmentFailureCount += evaluation.reason() == FailureReason.CORRIDOR_CONTAINMENT ? 1 : 0;
            state.rejections.add(new ChordRejection(
                current.startIndex(), current.endIndex(), evaluation.blockingIndex(), evaluation.reason()));
            int split = evaluation.blockingIndex();
            if (split <= current.startIndex() || split >= current.endIndex()) {
                split = significantInteriorIndex(source, current, evidence, groundScale);
            }
            if (split <= current.startIndex() || split >= current.endIndex()) {
                continue;
            }
            state.retained[split] = true;
            pending.push(new ProtectedInterval(split, current.endIndex()));
            pending.push(new ProtectedInterval(current.startIndex(), split));
        }
    }

    private ChordEvaluation evaluateChord(
        List<EastNorth> source,
        ProtectedInterval span,
        CandidateCleanupEvidence evidence,
        GeometryCleanupConfig config,
        double groundScale
    ) {
        int start = span.startIndex();
        int end = span.endIndex();
        List<CleanupSamplingProfile> samples = evidence.samplingFrame().profiles();
        List<CandidateCleanupProfile> rows = evidence.profiles();
        double startChainage = samples.get(start).cumulativeGroundDistanceMeters();
        double endChainage = samples.get(end).cumulativeGroundDistanceMeters();
        if (endChainage <= startChainage + EPSILON) {
            return ChordEvaluation.rejected(-1, FailureReason.NON_MONOTONIC_PROGRESS);
        }

        EastNorth startPoint = source.get(start);
        EastNorth endPoint = source.get(end);
        double chordEast = endPoint.east() - startPoint.east();
        double chordNorth = endPoint.north() - startPoint.north();
        if (Math.hypot(chordEast, chordNorth) <= EPSILON) {
            return ChordEvaluation.rejected(-1, FailureReason.FOLDBACK);
        }
        for (int index = start; index < end; index++) {
            double segmentEast = source.get(index + 1).east() - source.get(index).east();
            double segmentNorth = source.get(index + 1).north() - source.get(index).north();
            if (segmentEast * chordEast + segmentNorth * chordNorth <= EPSILON) {
                return ChordEvaluation.rejected(Math.min(index + 1, end - 1), FailureReason.FOLDBACK);
            }
        }
        if (chordTouchesOutsideSource(source, start, end)) {
            return ChordEvaluation.rejected(-1, FailureReason.TOPOLOGY_CONTACT);
        }

        boolean directlyAuthorized = false;
        double maximumDeviation = 0.0;
        int maximumDeviationIndex = start + 1;
        double worstFit = 1.0;
        for (int index = start + 1; index < end; index++) {
            CleanupSamplingProfile sample = samples.get(index);
            CandidateCleanupProfile row = rows.get(index);
            if (!sample.anchorWithinRaster()) {
                return ChordEvaluation.rejected(index, FailureReason.OFF_RASTER_GAP);
            }
            if (row.provenance() == CleanupEvidenceProvenance.UNSUPPORTED) {
                return ChordEvaluation.rejected(index, FailureReason.UNSUPPORTED_GAP);
            }
            if (!hasSignal(sample)) {
                return ChordEvaluation.rejected(index, FailureReason.NO_SIGNAL_GAP);
            }
            directlyAuthorized |= row.provenance() == CleanupEvidenceProvenance.DIRECT
                && !row.scaleConflict();

            double fraction = (sample.cumulativeGroundDistanceMeters() - startChainage)
                / (endChainage - startChainage);
            if (!(fraction > 0.0 && fraction < 1.0)) {
                return ChordEvaluation.rejected(index, FailureReason.NON_MONOTONIC_PROGRESS);
            }
            EastNorth chordPoint = new EastNorth(
                startPoint.east() + fraction * chordEast,
                startPoint.north() + fraction * chordNorth);
            double sourceOffset = offsetOf(source.get(index), sample.projectedLateralTransform());
            double chordOffset = offsetOf(chordPoint, sample.projectedLateralTransform());
            if (!Double.isFinite(sourceOffset) || !Double.isFinite(chordOffset)) {
                return ChordEvaluation.rejected(index, FailureReason.MISALIGNED_EVIDENCE);
            }
            double deviationMeters = Math.abs(chordOffset - sourceOffset) * groundScale;
            if (deviationMeters > maximumDeviation) {
                maximumDeviation = deviationMeters;
                maximumDeviationIndex = index;
            }
            if (chordOffset < row.selectedShoulderMinPx() - EPSILON
                || chordOffset > row.selectedShoulderMaxPx() + EPSILON) {
                return ChordEvaluation.rejected(index, FailureReason.CORRIDOR_CONTAINMENT);
            }
            double fitRetention = fitRetention(sample, sourceOffset, chordOffset);
            if (!Double.isFinite(fitRetention)) {
                return ChordEvaluation.rejected(index, FailureReason.OFF_RASTER_GAP);
            }
            worstFit = Math.min(worstFit, fitRetention);
            if (fitRetention + EPSILON < config.minimumFitRetention()) {
                return ChordEvaluation.rejected(index, FailureReason.FIT_RETENTION);
            }
            double sourceCenterResidual = Math.abs(sourceOffset - row.tubeCenterOffsetPx());
            double chordCenterResidual = Math.abs(chordOffset - row.tubeCenterOffsetPx());
            double centerTolerance = 0.25 * sample.sourcePixelPitchRasterPx();
            if (chordCenterResidual > Math.max(sourceCenterResidual, centerTolerance) + EPSILON) {
                return ChordEvaluation.rejected(index, FailureReason.CENTER_RETENTION);
            }
        }
        if (!directlyAuthorized) {
            return ChordEvaluation.rejected(-1, FailureReason.NO_DIRECT_AUTHORIZATION);
        }
        if (maximumDeviation > config.simplificationDeviationMeters() + EPSILON) {
            return ChordEvaluation.rejected(maximumDeviationIndex, FailureReason.DEVIATION_LIMIT);
        }
        return ChordEvaluation.accepted(maximumDeviation, worstFit);
    }

    private double fitRetention(CleanupSamplingProfile sample, double sourceOffset, double chordOffset) {
        double worst = 1.0;
        for (int band = 0; band < 3; band++) {
            double before = intensityAt(sample, sourceOffset, band);
            double after = intensityAt(sample, chordOffset, band);
            if (!Double.isFinite(before) || !Double.isFinite(after)) {
                return Double.NaN;
            }
            double retention = before <= MIN_AUTHORIZING_SIGNAL
                ? (after + EPSILON >= before ? 1.0 : 0.0)
                : Math.min(1.0, after / before);
            worst = Math.min(worst, retention);
        }
        return worst;
    }

    private double intensityAt(CleanupSamplingProfile profile, double offset, int band) {
        for (int index = 0; index < profile.sampleCount(); index++) {
            double currentOffset = profile.offsetPxAt(index);
            if (Math.abs(offset - currentOffset) <= EPSILON) {
                return profile.insideRasterAt(index) ? intensity(profile, index, band) : Double.NaN;
            }
            if (index == 0 || offset > currentOffset) {
                continue;
            }
            int left = index - 1;
            if (!profile.insideRasterAt(left) || !profile.insideRasterAt(index)) {
                return Double.NaN;
            }
            double leftOffset = profile.offsetPxAt(left);
            double width = currentOffset - leftOffset;
            if (width <= EPSILON || offset < leftOffset - EPSILON) {
                return Double.NaN;
            }
            double fraction = (offset - leftOffset) / width;
            return intensity(profile, left, band)
                + fraction * (intensity(profile, index, band) - intensity(profile, left, band));
        }
        return Double.NaN;
    }

    private double intensity(CleanupSamplingProfile profile, int index, int band) {
        return switch (band) {
            case 0 -> profile.nativeIntensityAt(index);
            case 1 -> profile.lightFilteredIntensityAt(index);
            case 2 -> profile.standardFilteredIntensityAt(index);
            default -> throw new IllegalArgumentException("Unknown scalar evidence band");
        };
    }

    private boolean hasSignal(CleanupSamplingProfile profile) {
        for (int index = 0; index < profile.sampleCount(); index++) {
            if (profile.insideRasterAt(index)
                && Math.max(profile.nativeIntensityAt(index),
                    Math.max(profile.lightFilteredIntensityAt(index),
                        profile.standardFilteredIntensityAt(index))) > MIN_AUTHORIZING_SIGNAL) {
                return true;
            }
        }
        return false;
    }

    private int significantInteriorIndex(
        List<EastNorth> source,
        ProtectedInterval span,
        CandidateCleanupEvidence evidence,
        double groundScale
    ) {
        int bestIndex = -1;
        double bestSignificance = -1.0;
        CleanupSamplingProfile startSample = evidence.samplingFrame().profiles().get(span.startIndex());
        CleanupSamplingProfile endSample = evidence.samplingFrame().profiles().get(span.endIndex());
        double startChainage = startSample.cumulativeGroundDistanceMeters();
        double endChainage = endSample.cumulativeGroundDistanceMeters();
        EastNorth start = source.get(span.startIndex());
        EastNorth end = source.get(span.endIndex());
        for (int index = span.startIndex() + 1; index < span.endIndex(); index++) {
            CleanupSamplingProfile sample = evidence.samplingFrame().profiles().get(index);
            double fraction = (sample.cumulativeGroundDistanceMeters() - startChainage)
                / (endChainage - startChainage);
            EastNorth chordPoint = new EastNorth(
                start.east() + fraction * (end.east() - start.east()),
                start.north() + fraction * (end.north() - start.north()));
            double significance = Math.abs(
                offsetOf(source.get(index), sample.projectedLateralTransform())
                    - offsetOf(chordPoint, sample.projectedLateralTransform())) * groundScale;
            CandidateCleanupProfile row = evidence.profiles().get(index);
            significance += configEvidenceSignificance(row);
            if (significance > bestSignificance + EPSILON) {
                bestSignificance = significance;
                bestIndex = index;
            }
        }
        return bestIndex;
    }

    private double configEvidenceSignificance(CandidateCleanupProfile row) {
        if (row.provenance() == CleanupEvidenceProvenance.UNSUPPORTED) {
            return 1_000_000.0;
        }
        return 10_000.0 * row.bendProtection() + 1_000.0 * row.turnSupport()
            + 100.0 * row.shapeAmbiguity() + 10.0 * row.motionSupport();
    }

    private boolean alignedEvidence(List<EastNorth> source, CandidateCleanupEvidence evidence) {
        List<CleanupSamplingProfile> samples = evidence.samplingFrame().profiles();
        List<CandidateCleanupProfile> rows = evidence.profiles();
        if (samples.size() != source.size() || rows.size() != source.size()) {
            return false;
        }
        double previousChainage = -1.0;
        for (int index = 0; index < source.size(); index++) {
            CleanupSamplingProfile sample = samples.get(index);
            CandidateCleanupProfile row = rows.get(index);
            if (sample.profileIndex() != index || row.profileIndex() != index
                || sample.projectedLateralTransform() == null
                || index > 0
                    && sample.cumulativeGroundDistanceMeters() <= previousChainage + EPSILON
                || !strictlyIncreasingOffsets(sample)) {
                return false;
            }
            previousChainage = sample.cumulativeGroundDistanceMeters();
        }
        return true;
    }

    private boolean strictlyIncreasingOffsets(CleanupSamplingProfile profile) {
        if (profile.sampleCount() < 2) {
            return false;
        }
        for (int index = 1; index < profile.sampleCount(); index++) {
            if (profile.offsetPxAt(index) <= profile.offsetPxAt(index - 1) + EPSILON) {
                return false;
            }
        }
        return true;
    }

    private Set<Integer> mandatoryIndexes(
        Set<Integer> protectedIndexes,
        CandidateCleanupEvidence evidence
    ) {
        LinkedHashSet<Integer> mandatory = new LinkedHashSet<>(protectedIndexes);
        List<CleanupSamplingProfile> samples = evidence.samplingFrame().profiles();
        for (int index = 0; index < evidence.profiles().size(); index++) {
            CandidateCleanupProfile row = evidence.profiles().get(index);
            CleanupSamplingProfile sample = samples.get(index);
            if (isFrozen(row, sample) || isSupportedShapeAnchor(row)) {
                mandatory.add(index);
            }
        }
        return Set.copyOf(mandatory);
    }

    private int countSupportedAnchors(CandidateCleanupEvidence evidence) {
        int count = 0;
        for (CandidateCleanupProfile row : evidence.profiles()) {
            if (isSupportedShapeAnchor(row)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns whether this profile must delimit a reduction span because its evidence cannot
     * authorize a replacement chord. The point remains exact while adjacent usable spans may
     * still be simplified independently.
     */
    private boolean isFrozen(CandidateCleanupProfile row, CleanupSamplingProfile sample) {
        return row.provenance() != CleanupEvidenceProvenance.DIRECT
            || !sample.anchorWithinRaster()
            || !hasSignal(sample)
            || row.scaleConflict();
    }

    /**
     * Returns whether direct multiscale shape evidence requires the exact existing coordinate.
     * Ambiguous evidence is retained conservatively because it cannot safely be classified as a
     * removable wrinkle; supported bends and legacy turn anchors retain their full amplitude.
     */
    private boolean isSupportedShapeAnchor(CandidateCleanupProfile row) {
        return row.provenance() == CleanupEvidenceProvenance.DIRECT
            && (row.turnSupport() > 0.0
                || row.bendProtection() >= MIN_BEND_PROTECTION
                || row.shapeAmbiguity() >= MIN_AMBIGUITY_PROTECTION);
    }

    private List<ProtectedInterval> splitAtMandatory(
        List<ProtectedInterval> intervals,
        Set<Integer> mandatory
    ) {
        List<ProtectedInterval> result = new ArrayList<>();
        for (ProtectedInterval interval : intervals) {
            int start = interval.startIndex();
            for (int index = start + 1; index < interval.endIndex(); index++) {
                if (mandatory.contains(index)) {
                    result.add(new ProtectedInterval(start, index));
                    start = index;
                }
            }
            result.add(new ProtectedInterval(start, interval.endIndex()));
        }
        return List.copyOf(result);
    }

    private IntervalValidation validateIntervals(
        int pointCount,
        List<ProtectedInterval> intervals,
        Collection<Integer> requestedProtected
    ) {
        if (pointCount < 3 || intervals.isEmpty()) {
            return IntervalValidation.invalid();
        }
        List<ProtectedInterval> ordered = new ArrayList<>(intervals);
        ordered.sort(Comparator.comparingInt(ProtectedInterval::startIndex));
        LinkedHashSet<Integer> protectedIndexes = new LinkedHashSet<>();
        int previousEnd = -1;
        for (ProtectedInterval interval : ordered) {
            if (interval.endIndex() >= pointCount || interval.startIndex() < previousEnd) {
                return IntervalValidation.invalid();
            }
            previousEnd = interval.endIndex();
            protectedIndexes.add(interval.startIndex());
            protectedIndexes.add(interval.endIndex());
        }
        for (Integer index : requestedProtected) {
            if (index == null || index < 0 || index >= pointCount) {
                return IntervalValidation.invalid();
            }
            protectedIndexes.add(index);
        }
        return new IntervalValidation(true, List.copyOf(ordered), Set.copyOf(protectedIndexes));
    }

    private boolean chordTouchesOutsideSource(List<EastNorth> source, int start, int end) {
        EastNorth chordStart = source.get(start);
        EastNorth chordEnd = source.get(end);
        for (int segment = 0; segment < source.size() - 1; segment++) {
            if (segment >= start && segment < end || segment == start - 1 || segment == end) {
                continue;
            }
            if (segmentsIntersect(chordStart, chordEnd, source.get(segment), source.get(segment + 1))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasTopologyContact(List<EastNorth> geometry) {
        for (int middle = 1; middle < geometry.size() - 1; middle++) {
            EastNorth before = geometry.get(middle - 1);
            EastNorth pivot = geometry.get(middle);
            EastNorth after = geometry.get(middle + 1);
            double incomingEast = pivot.east() - before.east();
            double incomingNorth = pivot.north() - before.north();
            double outgoingEast = after.east() - pivot.east();
            double outgoingNorth = after.north() - pivot.north();
            double cross = incomingEast * outgoingNorth - incomingNorth * outgoingEast;
            double dot = incomingEast * outgoingEast + incomingNorth * outgoingNorth;
            if (Math.abs(cross) <= EPSILON && dot < -EPSILON) {
                return true;
            }
        }
        for (int first = 0; first < geometry.size() - 1; first++) {
            for (int second = first + 2; second < geometry.size() - 1; second++) {
                if (segmentsIntersect(geometry.get(first), geometry.get(first + 1),
                    geometry.get(second), geometry.get(second + 1))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean segmentsIntersect(EastNorth a, EastNorth b, EastNorth c, EastNorth d) {
        double abC = orientation(a, b, c);
        double abD = orientation(a, b, d);
        double cdA = orientation(c, d, a);
        double cdB = orientation(c, d, b);
        if (((abC > EPSILON && abD < -EPSILON) || (abC < -EPSILON && abD > EPSILON))
            && ((cdA > EPSILON && cdB < -EPSILON) || (cdA < -EPSILON && cdB > EPSILON))) {
            return true;
        }
        return Math.abs(abC) <= EPSILON && pointOnSegment(c, a, b)
            || Math.abs(abD) <= EPSILON && pointOnSegment(d, a, b)
            || Math.abs(cdA) <= EPSILON && pointOnSegment(a, c, d)
            || Math.abs(cdB) <= EPSILON && pointOnSegment(b, c, d);
    }

    private double orientation(EastNorth a, EastNorth b, EastNorth c) {
        return (b.east() - a.east()) * (c.north() - a.north())
            - (b.north() - a.north()) * (c.east() - a.east());
    }

    private boolean pointOnSegment(EastNorth point, EastNorth start, EastNorth end) {
        return point.east() >= Math.min(start.east(), end.east()) - EPSILON
            && point.east() <= Math.max(start.east(), end.east()) + EPSILON
            && point.north() >= Math.min(start.north(), end.north()) - EPSILON
            && point.north() <= Math.max(start.north(), end.north()) + EPSILON;
    }

    private double offsetOf(EastNorth point, ProjectedLateralTransform transform) {
        double east = transform.eastPerRasterPixel();
        double north = transform.northPerRasterPixel();
        double denominator = east * east + north * north;
        return ((point.east() - transform.zeroOffset().east()) * east
            + (point.north() - transform.zeroOffset().north()) * north) / denominator;
    }

    private List<Integer> retainedIndexes(boolean[] retained) {
        List<Integer> result = new ArrayList<>();
        for (int index = 0; index < retained.length; index++) {
            if (retained[index]) {
                result.add(index);
            }
        }
        return List.copyOf(result);
    }

    private List<EastNorth> retainedGeometry(List<EastNorth> source, List<Integer> retainedIndexes) {
        List<EastNorth> result = new ArrayList<>(retainedIndexes.size());
        for (int index : retainedIndexes) {
            result.add(source.get(index));
        }
        return result;
    }

    private HeatmapConstrainedSimplificationResult unchanged(
        List<EastNorth> geometry,
        Status status,
        Collection<FailureReason> reasons,
        List<ChordRejection> rejections,
        Set<Integer> protectedIndexes,
        int retainedSupportedAnchors
    ) {
        List<Integer> indexes = new ArrayList<>(geometry.size());
        for (int index = 0; index < geometry.size(); index++) {
            indexes.add(index);
        }
        Metrics metrics = new Metrics(
            geometry.size(), geometry.size(), protectedIndexes.size(), retainedSupportedAnchors,
            rejections.size(), 0, 0, OptionalDouble.empty(), OptionalDouble.empty(), 1.0);
        return new HeatmapConstrainedSimplificationResult(
            geometry, indexes, status, List.copyOf(reasons), rejections, metrics);
    }

    private List<EastNorth> copyGeometry(List<EastNorth> geometry) {
        List<EastNorth> copy = new ArrayList<>(geometry.size());
        for (EastNorth point : geometry) {
            if (point == null || !Double.isFinite(point.east()) || !Double.isFinite(point.north())) {
                return null;
            }
            copy.add(new EastNorth(point.east(), point.north()));
        }
        return copy;
    }

    private boolean validGeometry(List<EastNorth> geometry) {
        if (geometry.size() < 3) {
            return false;
        }
        for (int index = 1; index < geometry.size(); index++) {
            if (samePoint(geometry.get(index - 1), geometry.get(index))) {
                return false;
            }
        }
        return true;
    }

    private boolean samePoint(EastNorth left, EastNorth right) {
        return Math.abs(left.east() - right.east()) <= EPSILON
            && Math.abs(left.north() - right.north()) <= EPSILON;
    }

    private static final class ReductionState {
        private final boolean[] retained;
        private final List<ChordRejection> rejections = new ArrayList<>();
        private final LinkedHashSet<FailureReason> reasons = new LinkedHashSet<>();
        private int attemptedChordCount;
        private int acceptedChordCount;
        private int containmentFailureCount;
        private double maximumRemovedDeviationMeters;
        private double worstFitRetention = 1.0;

        private ReductionState(int pointCount) {
            retained = new boolean[pointCount];
            java.util.Arrays.fill(retained, true);
        }
    }

    private record ChordEvaluation(
        boolean accepted,
        int blockingIndex,
        FailureReason reason,
        double maximumDeviationMeters,
        double worstFitRetention
    ) {
        private static ChordEvaluation accepted(double deviationMeters, double fitRetention) {
            return new ChordEvaluation(true, -1, null, deviationMeters, fitRetention);
        }

        private static ChordEvaluation rejected(int blockingIndex, FailureReason reason) {
            return new ChordEvaluation(false, blockingIndex, Objects.requireNonNull(reason), 0.0, 1.0);
        }
    }

    private record IntervalValidation(
        boolean valid,
        List<ProtectedInterval> orderedIntervals,
        Set<Integer> protectedIndexes
    ) {
        private static IntervalValidation invalid() {
            return new IntervalValidation(false, List.of(), Set.of());
        }
    }
}
