package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateCleanupEvidence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateCleanupProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupEvidenceProvenance;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupSamplingProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.HeatmapConstrainedLaplacianResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.HeatmapConstrainedLaplacianResult.FailureReason;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.HeatmapConstrainedLaplacianResult.Metrics;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.HeatmapConstrainedLaplacianResult.Status;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProjectedLateralTransform;

/**
 * Applies optional line-Laplacian smoothing while retaining candidate-owned heatmap evidence.
 *
 * <p>The service is pure: it accepts and returns projected geometry, never reads the current map
 * view, and never creates or mutates JOSM primitives. All moves are simultaneous, normal-only,
 * bounded in source-pixel units, and accepted only while the selected corridor, scalar fit, and
 * source topology remain valid.</p>
 */
public final class HeatmapConstrainedLaplacianSmoother {
    private static final double EPSILON = 1e-9;
    private static final double MIN_AUTHORIZING_SIGNAL = 1e-6;
    private static final double OFFSET_LINE_TOLERANCE = 1e-6;
    private static final double MAX_STEP_SOURCE_PIXELS = 0.75;
    private static final int MAX_BACKTRACKS_PER_PASS = 10;

    /** Creates a stateless constrained smoother. */
    public HeatmapConstrainedLaplacianSmoother() {
        // Stateless service.
    }

    /**
     * One independently smoothed point-index interval whose endpoints remain fixed.
     *
     * @param startIndex inclusive protected start index
     * @param endIndex inclusive protected end index
     */
    public record ProtectedInterval(int startIndex, int endIndex) {
        /** Validates the local ordering; geometry-size validation occurs at invocation time. */
        public ProtectedInterval {
            if (startIndex < 0 || endIndex <= startIndex) {
                throw new IllegalArgumentException("Protected interval must contain at least one segment");
            }
        }
    }

    /**
     * Smooths final-preview geometry under the retained candidate evidence.
     *
     * @param finalPreview projected final-preview points, aligned one-to-one with cleanup profiles
     * @param protectedIntervals independent smoothing intervals with fixed endpoints
     * @param protectedPointIndexes additional fixed/shared/tagged/junction/endpoint indexes
     * @param evidence candidate-owned scalar and corridor evidence
     * @param config slide-time cleanup configuration
     * @return immutable geometry and structured smoothing diagnostics
     */
    public HeatmapConstrainedLaplacianResult smooth(
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

        LinkedHashSet<FailureReason> reasons = new LinkedHashSet<>();
        List<EastNorth> source = copyGeometry(finalPreview);
        if (source == null) {
            reasons.add(FailureReason.INVALID_GEOMETRY);
            return result(List.of(), Status.REJECTED, reasons,
                emptyMetrics(finalPreview.size(), 0));
        }
        Set<Integer> protectedIndexes = normalizedProtectedIndexes(
            source.size(), protectedIntervals, protectedPointIndexes, reasons);
        if (!validGeometry(source) || protectedIndexes == null) {
            reasons.add(FailureReason.INVALID_GEOMETRY);
            return result(source, Status.REJECTED, reasons,
                emptyMetrics(source.size(), protectedIndexes == null ? 0 : protectedIndexes.size()));
        }
        List<ProtectedInterval> effectiveIntervals = splitAtProtectedPoints(
            protectedIntervals, protectedIndexes);
        if (hasSelfIntersection(source)) {
            reasons.add(FailureReason.SELF_INTERSECTION);
            return result(source, Status.REJECTED, reasons,
                emptyMetrics(source.size(), protectedIndexes.size()));
        }
        if (config.mode() != GeometryCleanupMode.CONSTRAINED_SMOOTH_AND_REDUCE
            || config.laplacianStrength() <= 0.0) {
            reasons.add(FailureReason.MODE_DISABLED);
            return result(source, Status.UNCHANGED, reasons,
                emptyMetrics(source.size(), protectedIndexes.size()));
        }
        if (!evidence.eligible()) {
            reasons.add(FailureReason.INELIGIBLE_EVIDENCE);
            return result(source, Status.REJECTED, reasons,
                emptyMetrics(source.size(), protectedIndexes.size()));
        }

        EvidenceView evidenceView = validateEvidence(source, effectiveIntervals, evidence);
        if (evidenceView == null) {
            reasons.add(FailureReason.MISALIGNED_EVIDENCE);
            return result(source, Status.REJECTED, reasons,
                emptyMetrics(source.size(), protectedIndexes.size()));
        }

        boolean[] intervalInterior = intervalInterior(source.size(), effectiveIntervals);
        boolean[] authorized = authorizedPoints(source, protectedIndexes, intervalInterior, evidenceView);
        int authorizedCount = countTrue(authorized);
        double fitBefore = aggregateFit(source, evidenceView);
        if (authorizedCount == 0) {
            reasons.add(FailureReason.NO_AUTHORIZED_MOVEMENT);
            return result(source, Status.UNCHANGED, reasons,
                metrics(source, source, 0, 0, protectedIndexes.size(), 0, 0, 0,
                    fitBefore, fitBefore, evidenceView, effectiveIntervals));
        }

        List<EastNorth> current = source;
        int acceptedPasses = 0;
        int backtracks = 0;
        int containmentFailures = 0;
        int fitFailures = 0;
        for (int pass = 0; pass < config.laplacianPassCount(); pass++) {
            double strength = config.laplacianStrength();
            boolean accepted = false;
            boolean hadMovementProposal = false;
            for (int attempt = 0; attempt <= MAX_BACKTRACKS_PER_PASS; attempt++) {
                Proposal proposal = proposePass(
                    source, current, effectiveIntervals, authorized, evidenceView, strength);
                hadMovementProposal |= proposal.changed();
                if (!proposal.changed()) {
                    break;
                }
                Validation validation = validateProposal(
                    source, proposal.geometry(), proposal.movedIndexes(), evidenceView,
                    config.minimumFitRetention(), effectiveIntervals);
                containmentFailures += validation.containmentFailures();
                fitFailures += validation.fitFailures();
                if (validation.accepted()) {
                    current = proposal.geometry();
                    acceptedPasses++;
                    accepted = true;
                    break;
                }
                reasons.addAll(validation.reasons());
                backtracks++;
                strength *= 0.5;
            }
            if (!accepted) {
                if (hadMovementProposal) {
                    reasons.add(FailureReason.BACKTRACK_LIMIT_REACHED);
                }
                break;
            }
        }

        double fitAfter = aggregateFit(current, evidenceView);
        Status status = acceptedPasses > 0 && !sameGeometry(source, current)
            ? Status.APPLIED : Status.UNCHANGED;
        if (status == Status.UNCHANGED && reasons.isEmpty()) {
            reasons.add(FailureReason.NO_AUTHORIZED_MOVEMENT);
        }
        return result(current, status, reasons,
            metrics(source, current, acceptedPasses, backtracks, protectedIndexes.size(),
                authorizedCount, containmentFailures, fitFailures, fitBefore, fitAfter,
                evidenceView, effectiveIntervals));
    }

    private EvidenceView validateEvidence(
        List<EastNorth> source,
        List<ProtectedInterval> intervals,
        CandidateCleanupEvidence evidence
    ) {
        List<CleanupSamplingProfile> samples = evidence.samplingFrame().profiles();
        List<CandidateCleanupProfile> rows = evidence.profiles();
        if (samples.size() != source.size() || rows.size() != source.size()) {
            return null;
        }
        double[] sourceOffsets = new double[source.size()];
        Arrays.fill(sourceOffsets, Double.NaN);
        for (int index = 0; index < source.size(); index++) {
            CleanupSamplingProfile sample = samples.get(index);
            CandidateCleanupProfile row = rows.get(index);
            if (sample.profileIndex() != index || row.profileIndex() != index
                || sample.projectedLateralTransform() == null) {
                return null;
            }
            sourceOffsets[index] = offsetOf(source.get(index), sample.projectedLateralTransform());
            if (!Double.isFinite(sourceOffsets[index]) && row.provenance() == CleanupEvidenceProvenance.DIRECT) {
                return null;
            }
        }
        for (ProtectedInterval interval : intervals) {
            for (int index = interval.startIndex() + 1; index <= interval.endIndex(); index++) {
                if (samples.get(index).cumulativeGroundDistanceMeters()
                    <= samples.get(index - 1).cumulativeGroundDistanceMeters() + EPSILON) {
                    return null;
                }
            }
        }
        return new EvidenceView(samples, rows, sourceOffsets);
    }

    private Set<Integer> normalizedProtectedIndexes(
        int pointCount,
        List<ProtectedInterval> intervals,
        Collection<Integer> requested,
        Set<FailureReason> reasons
    ) {
        if (pointCount < 3 || intervals.isEmpty()) {
            return null;
        }
        List<ProtectedInterval> ordered = new ArrayList<>(intervals);
        ordered.sort(Comparator.comparingInt(ProtectedInterval::startIndex));
        int previousEnd = -1;
        Set<Integer> result = new LinkedHashSet<>();
        for (ProtectedInterval interval : ordered) {
            if (interval.endIndex() >= pointCount || interval.startIndex() < previousEnd) {
                return null;
            }
            previousEnd = interval.endIndex();
            result.add(interval.startIndex());
            result.add(interval.endIndex());
        }
        for (Integer index : requested) {
            if (index == null || index < 0 || index >= pointCount) {
                reasons.add(FailureReason.INVALID_GEOMETRY);
                return null;
            }
            result.add(index);
        }
        return Set.copyOf(result);
    }

    private boolean[] intervalInterior(int pointCount, List<ProtectedInterval> intervals) {
        boolean[] interior = new boolean[pointCount];
        for (ProtectedInterval interval : intervals) {
            for (int index = interval.startIndex() + 1; index < interval.endIndex(); index++) {
                interior[index] = true;
            }
        }
        return interior;
    }

    private List<ProtectedInterval> splitAtProtectedPoints(
        List<ProtectedInterval> intervals,
        Set<Integer> protectedIndexes
    ) {
        List<ProtectedInterval> ordered = new ArrayList<>(intervals);
        ordered.sort(Comparator.comparingInt(ProtectedInterval::startIndex));
        List<ProtectedInterval> result = new ArrayList<>();
        for (ProtectedInterval interval : ordered) {
            int sectionStart = interval.startIndex();
            List<Integer> interiorProtections = protectedIndexes.stream()
                .filter(index -> index > interval.startIndex() && index < interval.endIndex())
                .sorted()
                .toList();
            for (int protectedIndex : interiorProtections) {
                result.add(new ProtectedInterval(sectionStart, protectedIndex));
                sectionStart = protectedIndex;
            }
            result.add(new ProtectedInterval(sectionStart, interval.endIndex()));
        }
        return List.copyOf(result);
    }

    private boolean[] authorizedPoints(
        List<EastNorth> source,
        Set<Integer> protectedIndexes,
        boolean[] intervalInterior,
        EvidenceView evidence
    ) {
        boolean[] authorized = new boolean[source.size()];
        boolean[] directUsableSupport = new boolean[source.size()];
        for (int index = 0; index < source.size(); index++) {
            CandidateCleanupProfile row = evidence.rows().get(index);
            CleanupSamplingProfile sample = evidence.samples().get(index);
            double offset = evidence.sourceOffsets()[index];
            Fit currentFit = fit(sample, offset);
            directUsableSupport[index] = row.provenance() == CleanupEvidenceProvenance.DIRECT
                && !row.scaleConflict()
                && sample.anchorWithinRaster()
                && Double.isFinite(offset)
                && within(offset, row.selectedShoulderMinPx(), row.selectedShoulderMaxPx())
                && currentFit.valid()
                && currentFit.hasSignal();
        }
        for (int index = 1; index < source.size() - 1; index++) {
            authorized[index] = intervalInterior[index]
                && !protectedIndexes.contains(index)
                && directUsableSupport[index - 1]
                && directUsableSupport[index]
                && directUsableSupport[index + 1];
        }
        return authorized;
    }

    private Proposal proposePass(
        List<EastNorth> source,
        List<EastNorth> current,
        List<ProtectedInterval> intervals,
        boolean[] authorized,
        EvidenceView evidence,
        double strength
    ) {
        List<EastNorth> proposed = new ArrayList<>(current);
        Set<Integer> moved = new LinkedHashSet<>();
        for (ProtectedInterval interval : intervals) {
            for (int index = interval.startIndex() + 1; index < interval.endIndex(); index++) {
                if (!authorized[index]) {
                    continue;
                }
                CleanupSamplingProfile sample = evidence.samples().get(index);
                CandidateCleanupProfile row = evidence.rows().get(index);
                double leftDistance = sample.cumulativeGroundDistanceMeters()
                    - evidence.samples().get(index - 1).cumulativeGroundDistanceMeters();
                double rightDistance = evidence.samples().get(index + 1).cumulativeGroundDistanceMeters()
                    - sample.cumulativeGroundDistanceMeters();
                EastNorth laplacianTarget = chainageInterpolation(
                    current.get(index - 1), current.get(index + 1), leftDistance, rightDistance);
                ProjectedLateralTransform transform = sample.projectedLateralTransform();
                double currentOffset = offsetOf(current.get(index), transform);
                double laplacianNormalOffset = normalComponentOffset(
                    current.get(index), laplacianTarget, transform);
                if (!Double.isFinite(currentOffset) || !Double.isFinite(laplacianNormalOffset)) {
                    continue;
                }
                double supportDamping = 1.0 - 0.75 * row.motionSupport();
                double delta = strength * supportDamping * laplacianNormalOffset;
                double maximumStep = MAX_STEP_SOURCE_PIXELS * sample.sourcePixelPitchRasterPx();
                delta = clamp(delta, -maximumStep, maximumStep);
                delta = retainSupportedTurn(
                    source, index, evidence, row, currentOffset, delta, transform);
                if (Math.abs(delta) <= EPSILON) {
                    continue;
                }
                EastNorth point = transform.atOffset(currentOffset + delta);
                proposed.set(index, point);
                moved.add(index);
            }
        }
        return new Proposal(List.copyOf(proposed), Set.copyOf(moved), !moved.isEmpty());
    }

    private double retainSupportedTurn(
        List<EastNorth> source,
        int index,
        EvidenceView evidence,
        CandidateCleanupProfile row,
        double currentOffset,
        double proposedDelta,
        ProjectedLateralTransform transform
    ) {
        if (row.turnSupport() <= 0.0) {
            return proposedDelta;
        }
        CleanupSamplingProfile sample = evidence.samples().get(index);
        double leftDistance = sample.cumulativeGroundDistanceMeters()
            - evidence.samples().get(index - 1).cumulativeGroundDistanceMeters();
        double rightDistance = evidence.samples().get(index + 1).cumulativeGroundDistanceMeters()
            - sample.cumulativeGroundDistanceMeters();
        EastNorth sourceChord = chainageInterpolation(
            source.get(index - 1), source.get(index + 1), leftDistance, rightDistance);
        double sourceOffset = evidence.sourceOffsets()[index];
        double sourceDeviation = Math.abs(normalComponentOffset(sourceChord, source.get(index), transform));
        if (!Double.isFinite(sourceDeviation)) {
            return 0.0;
        }
        double maximumTotalMovement = sourceDeviation
            * (1.0 - 0.90 * row.turnSupport());
        double currentTotalMovement = currentOffset - sourceOffset;
        double requestedTotalMovement = currentTotalMovement + proposedDelta;
        double boundedTotalMovement = clamp(
            requestedTotalMovement, -maximumTotalMovement, maximumTotalMovement);
        return boundedTotalMovement - currentTotalMovement;
    }

    private Validation validateProposal(
        List<EastNorth> source,
        List<EastNorth> proposed,
        Set<Integer> movedIndexes,
        EvidenceView evidence,
        double minimumFitRetention,
        List<ProtectedInterval> intervals
    ) {
        LinkedHashSet<FailureReason> reasons = new LinkedHashSet<>();
        int containmentFailures = 0;
        int fitFailures = 0;
        for (int index : movedIndexes) {
            CandidateCleanupProfile row = evidence.rows().get(index);
            CleanupSamplingProfile sample = evidence.samples().get(index);
            double beforeOffset = evidence.sourceOffsets()[index];
            double afterOffset = offsetOf(proposed.get(index), sample.projectedLateralTransform());
            if (!Double.isFinite(afterOffset)
                || !within(afterOffset, row.selectedShoulderMinPx(), row.selectedShoulderMaxPx())
                || (within(beforeOffset, row.selectedCoreMinPx(), row.selectedCoreMaxPx())
                    && !within(afterOffset, row.selectedCoreMinPx(), row.selectedCoreMaxPx()))) {
                containmentFailures++;
                reasons.add(FailureReason.CORRIDOR_CONTAINMENT);
                continue;
            }
            Fit before = fit(sample, beforeOffset);
            Fit after = fit(sample, afterOffset);
            if (!after.valid() || !after.retains(before, minimumFitRetention)) {
                fitFailures++;
                reasons.add(FailureReason.FIT_RETENTION);
            }
        }
        if (hasSelfIntersection(proposed)) {
            reasons.add(FailureReason.SELF_INTERSECTION);
        }
        if (hasFoldback(source, proposed, intervals)) {
            reasons.add(FailureReason.FOLDBACK);
        }
        if (!retainsSupportedTurns(source, proposed, evidence, intervals)) {
            reasons.add(FailureReason.SUPPORTED_TURN_RETENTION);
        }
        return new Validation(reasons.isEmpty(), List.copyOf(reasons), containmentFailures, fitFailures);
    }

    private HeatmapConstrainedLaplacianResult result(
        List<EastNorth> geometry,
        Status status,
        Set<FailureReason> reasons,
        Metrics metrics
    ) {
        return new HeatmapConstrainedLaplacianResult(geometry, status, List.copyOf(reasons), metrics);
    }

    private Metrics emptyMetrics(int pointCount, int protectedCount) {
        return new Metrics(pointCount, 0, 0, protectedCount, 0, 0, 0,
            0.0, 0.0, 0.0, 0.0, 0.0, 1.0);
    }

    private Metrics metrics(
        List<EastNorth> source,
        List<EastNorth> result,
        int acceptedPasses,
        int backtracks,
        int protectedCount,
        int authorizedCount,
        int containmentFailures,
        int fitFailures,
        double fitBefore,
        double fitAfter,
        EvidenceView evidence,
        List<ProtectedInterval> intervals
    ) {
        double[] displacements = new double[source.size()];
        for (int index = 0; index < source.size(); index++) {
            displacements[index] = distance(source.get(index), result.get(index));
        }
        Arrays.sort(displacements);
        return new Metrics(source.size(), acceptedPasses, backtracks, protectedCount,
            authorizedCount, containmentFailures, fitFailures,
            percentile(displacements, 0.50), percentile(displacements, 0.95),
            displacements.length == 0 ? 0.0 : displacements[displacements.length - 1],
            clamp(fitBefore, 0.0, 1.0), clamp(fitAfter, 0.0, 1.0),
            supportedTurnRetention(source, result, evidence, intervals));
    }

    private double aggregateFit(List<EastNorth> geometry, EvidenceView evidence) {
        double total = 0.0;
        int count = 0;
        for (int index = 0; index < geometry.size(); index++) {
            if (evidence.rows().get(index).provenance() != CleanupEvidenceProvenance.DIRECT) {
                continue;
            }
            double offset = offsetOf(
                geometry.get(index), evidence.samples().get(index).projectedLateralTransform());
            Fit fit = fit(evidence.samples().get(index), offset);
            if (fit.valid()) {
                total += fit.mean();
                count++;
            }
        }
        return count == 0 ? 0.0 : total / count;
    }

    private double supportedTurnRetention(
        List<EastNorth> source,
        List<EastNorth> result,
        EvidenceView evidence,
        List<ProtectedInterval> intervals
    ) {
        boolean[] interior = intervalInterior(source.size(), intervals);
        double retention = 1.0;
        for (int index = 1; index < source.size() - 1; index++) {
            CandidateCleanupProfile row = evidence.rows().get(index);
            if (!interior[index] || row.provenance() != CleanupEvidenceProvenance.DIRECT
                || row.turnSupport() <= 0.0) {
                continue;
            }
            CleanupSamplingProfile sample = evidence.samples().get(index);
            double leftDistance = sample.cumulativeGroundDistanceMeters()
                - evidence.samples().get(index - 1).cumulativeGroundDistanceMeters();
            double rightDistance = evidence.samples().get(index + 1).cumulativeGroundDistanceMeters()
                - sample.cumulativeGroundDistanceMeters();
            ProjectedLateralTransform transform = sample.projectedLateralTransform();
            double sourceDeviation = localDeviation(
                source, index, leftDistance, rightDistance, transform);
            if (sourceDeviation <= EPSILON) {
                continue;
            }
            double resultDeviation = localDeviation(
                result, index, leftDistance, rightDistance, transform);
            retention = Math.min(retention, Math.min(1.0, resultDeviation / sourceDeviation));
        }
        return clamp(retention, 0.0, 1.0);
    }

    private boolean retainsSupportedTurns(
        List<EastNorth> source,
        List<EastNorth> proposed,
        EvidenceView evidence,
        List<ProtectedInterval> intervals
    ) {
        boolean[] interior = intervalInterior(source.size(), intervals);
        for (int index = 1; index < source.size() - 1; index++) {
            CandidateCleanupProfile row = evidence.rows().get(index);
            if (!interior[index] || row.provenance() != CleanupEvidenceProvenance.DIRECT
                || row.turnSupport() <= 0.0) {
                continue;
            }
            CleanupSamplingProfile sample = evidence.samples().get(index);
            double leftDistance = sample.cumulativeGroundDistanceMeters()
                - evidence.samples().get(index - 1).cumulativeGroundDistanceMeters();
            double rightDistance = evidence.samples().get(index + 1).cumulativeGroundDistanceMeters()
                - sample.cumulativeGroundDistanceMeters();
            ProjectedLateralTransform transform = sample.projectedLateralTransform();
            double sourceDeviation = localDeviation(
                source, index, leftDistance, rightDistance, transform);
            if (sourceDeviation <= EPSILON) {
                continue;
            }
            double proposedDeviation = localDeviation(
                proposed, index, leftDistance, rightDistance, transform);
            double requiredRetention = 0.90;
            if (proposedDeviation + EPSILON < sourceDeviation * requiredRetention) {
                return false;
            }
        }
        return true;
    }

    private double localDeviation(
        List<EastNorth> geometry,
        int index,
        double leftDistance,
        double rightDistance,
        ProjectedLateralTransform transform
    ) {
        EastNorth chord = chainageInterpolation(
            geometry.get(index - 1), geometry.get(index + 1), leftDistance, rightDistance);
        double deviation = normalComponentOffset(chord, geometry.get(index), transform);
        return Double.isFinite(deviation) ? Math.abs(deviation) : 0.0;
    }

    private Fit fit(CleanupSamplingProfile profile, double offset) {
        if (!Double.isFinite(offset) || profile.sampleCount() == 0) {
            return Fit.invalid();
        }
        for (int index = 0; index < profile.sampleCount(); index++) {
            if (Math.abs(profile.offsetPxAt(index) - offset) <= EPSILON) {
                return profile.insideRasterAt(index)
                    ? new Fit(profile.nativeIntensityAt(index), profile.lightFilteredIntensityAt(index),
                        profile.standardFilteredIntensityAt(index), true)
                    : Fit.invalid();
            }
        }
        for (int index = 1; index < profile.sampleCount(); index++) {
            double leftOffset = profile.offsetPxAt(index - 1);
            double rightOffset = profile.offsetPxAt(index);
            if (!profile.insideRasterAt(index - 1) || !profile.insideRasterAt(index)
                || rightOffset <= leftOffset || offset < leftOffset || offset > rightOffset) {
                continue;
            }
            double fraction = (offset - leftOffset) / (rightOffset - leftOffset);
            return new Fit(
                interpolate(profile.nativeIntensityAt(index - 1), profile.nativeIntensityAt(index), fraction),
                interpolate(profile.lightFilteredIntensityAt(index - 1),
                    profile.lightFilteredIntensityAt(index), fraction),
                interpolate(profile.standardFilteredIntensityAt(index - 1),
                    profile.standardFilteredIntensityAt(index), fraction),
                true);
        }
        return Fit.invalid();
    }

    private double offsetOf(EastNorth point, ProjectedLateralTransform transform) {
        double east = point.east() - transform.zeroOffset().east();
        double north = point.north() - transform.zeroOffset().north();
        double normSquared = transform.eastPerRasterPixel() * transform.eastPerRasterPixel()
            + transform.northPerRasterPixel() * transform.northPerRasterPixel();
        double offset = (east * transform.eastPerRasterPixel()
            + north * transform.northPerRasterPixel()) / normSquared;
        double residualEast = east - offset * transform.eastPerRasterPixel();
        double residualNorth = north - offset * transform.northPerRasterPixel();
        double tolerance = OFFSET_LINE_TOLERANCE * Math.max(1.0, Math.sqrt(normSquared));
        return Math.hypot(residualEast, residualNorth) <= tolerance ? offset : Double.NaN;
    }

    private double normalComponentOffset(
        EastNorth from,
        EastNorth to,
        ProjectedLateralTransform transform
    ) {
        double deltaEast = to.east() - from.east();
        double deltaNorth = to.north() - from.north();
        double normSquared = transform.eastPerRasterPixel() * transform.eastPerRasterPixel()
            + transform.northPerRasterPixel() * transform.northPerRasterPixel();
        return (deltaEast * transform.eastPerRasterPixel()
            + deltaNorth * transform.northPerRasterPixel()) / normSquared;
    }

    private EastNorth chainageInterpolation(
        EastNorth left,
        EastNorth right,
        double leftDistance,
        double rightDistance
    ) {
        double total = leftDistance + rightDistance;
        double rightFraction = leftDistance / total;
        return new EastNorth(
            left.east() + rightFraction * (right.east() - left.east()),
            left.north() + rightFraction * (right.north() - left.north()));
    }

    private boolean hasFoldback(
        List<EastNorth> source,
        List<EastNorth> proposed,
        List<ProtectedInterval> intervals
    ) {
        for (ProtectedInterval interval : intervals) {
            for (int index = interval.startIndex(); index < interval.endIndex(); index++) {
                double sourceEast = source.get(index + 1).east() - source.get(index).east();
                double sourceNorth = source.get(index + 1).north() - source.get(index).north();
                double proposedEast = proposed.get(index + 1).east() - proposed.get(index).east();
                double proposedNorth = proposed.get(index + 1).north() - proposed.get(index).north();
                if (Math.hypot(sourceEast, sourceNorth) <= EPSILON
                    || Math.hypot(proposedEast, proposedNorth) <= EPSILON
                    || sourceEast * proposedEast + sourceNorth * proposedNorth <= 0.0) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasSelfIntersection(List<EastNorth> geometry) {
        for (int first = 0; first < geometry.size() - 1; first++) {
            for (int second = first + 2; second < geometry.size() - 1; second++) {
                if (first == 0 && second == geometry.size() - 2
                    && samePoint(geometry.get(0), geometry.get(geometry.size() - 1))) {
                    continue;
                }
                if (segmentsIntersect(
                    geometry.get(first), geometry.get(first + 1),
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

    private boolean validGeometry(List<EastNorth> geometry) {
        if (geometry.size() < 3) {
            return false;
        }
        for (int index = 0; index < geometry.size(); index++) {
            EastNorth point = geometry.get(index);
            if (point == null || !Double.isFinite(point.east()) || !Double.isFinite(point.north())
                || index > 0 && samePoint(point, geometry.get(index - 1))) {
                return false;
            }
        }
        return true;
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

    private boolean sameGeometry(List<EastNorth> left, List<EastNorth> right) {
        for (int index = 0; index < left.size(); index++) {
            if (!samePoint(left.get(index), right.get(index))) {
                return false;
            }
        }
        return true;
    }

    private boolean samePoint(EastNorth left, EastNorth right) {
        return Math.abs(left.east() - right.east()) <= EPSILON
            && Math.abs(left.north() - right.north()) <= EPSILON;
    }

    private int countTrue(boolean[] values) {
        int count = 0;
        for (boolean value : values) {
            if (value) {
                count++;
            }
        }
        return count;
    }

    private double percentile(double[] sorted, double percentile) {
        if (sorted.length == 0) {
            return 0.0;
        }
        double position = percentile * (sorted.length - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        return interpolate(sorted[lower], sorted[upper], position - lower);
    }

    private double interpolate(double left, double right, double fraction) {
        return left + fraction * (right - left);
    }

    private double distance(EastNorth left, EastNorth right) {
        return Math.hypot(left.east() - right.east(), left.north() - right.north());
    }

    private boolean within(double value, double minimum, double maximum) {
        return Double.isFinite(value) && Double.isFinite(minimum) && Double.isFinite(maximum)
            && value >= minimum - EPSILON && value <= maximum + EPSILON;
    }

    private double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private record EvidenceView(
        List<CleanupSamplingProfile> samples,
        List<CandidateCleanupProfile> rows,
        double[] sourceOffsets
    ) {
    }

    private record Proposal(List<EastNorth> geometry, Set<Integer> movedIndexes, boolean changed) {
    }

    private record Validation(
        boolean accepted,
        List<FailureReason> reasons,
        int containmentFailures,
        int fitFailures
    ) {
    }

    private record Fit(double raw, double light, double standard, boolean valid) {
        private static Fit invalid() {
            return new Fit(0.0, 0.0, 0.0, false);
        }

        private boolean retains(Fit before, double minimumRetention) {
            return valid && before.valid
                && raw + EPSILON >= before.raw * minimumRetention
                && light + EPSILON >= before.light * minimumRetention
                && standard + EPSILON >= before.standard * minimumRetention;
        }

        private double mean() {
            return clampIntensity((raw + light + standard) / 3.0);
        }

        private boolean hasSignal() {
            return Math.max(raw, Math.max(light, standard)) > MIN_AUTHORIZING_SIGNAL;
        }

        private static double clampIntensity(double value) {
            return Math.max(0.0, Math.min(1.0, value));
        }
    }
}
