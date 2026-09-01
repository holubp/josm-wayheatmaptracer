package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupEvidenceProvenance;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalShapeEvidence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalShapeEvidence.Decision;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalShapeEvidence.Reason;

/** Computes deterministic three-scale wrinkle, bend, ambiguity, and intervention evidence. */
public final class LocalShapeEvidenceEvaluator {
    /** Common physical half-window scale bank used by every cleanup preset. */
    public static final List<Double> ANALYSIS_RADII_METERS = List.of(6.0, 10.0, 20.0);
    private static final int ROBUST_ITERATIONS = 3;
    private static final double EPSILON = 1e-12;

    /** Creates a stateless deterministic local-shape evaluator. */
    public LocalShapeEvidenceEvaluator() {
    }

    /**
     * Evaluates synthetic or already-proven direct tubes without a track provenance map.
     *
     * @param tube profile-aligned robust corridor references
     * @param sourcePixelSizePx source-pixel pitch in sampled-raster pixels
     * @return profile-aligned local shape evidence
     */
    public List<LocalShapeEvidence> evaluate(LongitudinalCorridorTube tube, double sourcePixelSizePx) {
        return evaluate(null, tube, sourcePixelSizePx);
    }

    /**
     * Evaluates one immutable evidence row per tube profile.
     *
     * @param track selected corridor provenance, or {@code null} for directly observed fixtures
     * @param tube profile-aligned robust corridor references
     * @param sourcePixelSizePx source-pixel pitch in sampled-raster pixels
     * @return profile-aligned local shape evidence
     */
    public List<LocalShapeEvidence> evaluate(
        CorridorTrack track,
        LongitudinalCorridorTube tube,
        double sourcePixelSizePx
    ) {
        if (tube == null) {
            throw new IllegalArgumentException("Corridor tube must not be null");
        }
        double sourcePixel = Double.isFinite(sourcePixelSizePx) && sourcePixelSizePx > 0.0
            ? sourcePixelSizePx : 1.0;
        List<LocalShapeEvidence> result = new ArrayList<>(tube.slices().size());
        for (CorridorTubeSlice target : tube.slices()) {
            CleanupEvidenceProvenance provenance = provenance(track, target);
            result.add(evaluateProfile(track, tube, target, sourcePixel, provenance));
        }
        return List.copyOf(result);
    }

    private LocalShapeEvidence evaluateProfile(
        CorridorTrack track,
        LongitudinalCorridorTube tube,
        CorridorTubeSlice target,
        double sourcePixel,
        CleanupEvidenceProvenance provenance
    ) {
        Centers centers = centers(target, sourcePixel);
        if (provenance != CleanupEvidenceProvenance.DIRECT || !target.observed()) {
            return unavailable(target, sourcePixel, provenance, centers, Reason.NON_DIRECT);
        }
        boolean scaleConflict = target.scaleConflict() || target.parentMerge();
        List<ScaleResult> scales = new ArrayList<>(ANALYSIS_RADII_METERS.size());
        for (double radius : ANALYSIS_RADII_METERS) {
            ScaleResult scale = evaluateScale(track, tube, target.profileIndex(), sourcePixel, radius);
            if (scale.valid()) {
                scales.add(scale);
            }
        }
        if (scales.isEmpty()) {
            return unavailable(target, sourcePixel, provenance, centers,
                target.profileIndex() == 0 || target.profileIndex() == tube.slices().size() - 1
                    ? Reason.BOUNDARY_CENSORED : Reason.INSUFFICIENT_WINDOW);
        }
        ScaleResult selected = scales.stream().max((left, right) -> {
            int score = Double.compare(left.selectionScore(), right.selectionScore());
            return score != 0 ? score : Double.compare(right.radiusMeters(), left.radiusMeters());
        }).orElseThrow();
        double wrinkle = scales.stream().mapToDouble(ScaleResult::wrinkleScore).max().orElse(0.0);
        double sustainedMotion = sustainedMotionSupport(track, tube, target.profileIndex());
        double bend = Math.max(sustainedMotion,
            scales.stream().mapToDouble(ScaleResult::bendScore).max().orElse(0.0));
        double reliability = scales.stream().mapToDouble(ScaleResult::reliability).max().orElse(0.0);
        double overlap = 2.0 * Math.min(wrinkle, bend);
        double ambiguity = clamp(overlap + 0.35 * (1.0 - reliability)
            + (scaleConflict ? 1.0 : 0.0));
        double bendProtection = clamp(Math.max(bend, sustainedMotion));
        double intervention = clamp(wrinkle * square(1.0 - bendProtection)
            * (1.0 - ambiguity) * reliability);
        Decision decision;
        Reason reason;
        if (scaleConflict) {
            decision = Decision.AMBIGUOUS;
            reason = Reason.SCALE_CONFLICT;
        } else if (ambiguity >= 0.65 && intervention < 0.20) {
            decision = Decision.AMBIGUOUS;
            reason = Reason.AMBIGUOUS;
        } else if (bendProtection >= 0.45 && bendProtection > wrinkle) {
            decision = Decision.SUPPORTED_BEND;
            reason = Reason.SUPPORTED_BEND;
            intervention = 0.0;
        } else if (intervention >= 0.15) {
            decision = Decision.WRINKLE;
            reason = Reason.DIRECT_WRINKLE;
        } else {
            decision = Decision.STABLE;
            reason = Reason.STABLE;
        }
        return new LocalShapeEvidence(target.profileIndex(), target.distanceMeters(), sourcePixel,
            provenance, selected.coverage(), centers.raw(), centers.light(), centers.standard(),
            centers.core(), centers.local(), centers.stability(), centers.effective(), Double.NaN,
            selected.trendCenterSourcePx(), selected.trendSlopeSourcePxPerMeter(),
            selected.trendCurvatureSourcePxPerMeter2(), selected.uncertaintySourcePx(),
            centers.local() - selected.trendCenterSourcePx(), selected.amplitudeSourcePx(),
            selected.reversalCount(), selected.reversalSpacingMeters(), selected.channelAgreement(), reliability,
            wrinkle, bend, ambiguity, intervention, bendProtection, decision, reason);
    }

    private double sustainedMotionSupport(
        CorridorTrack track,
        LongitudinalCorridorTube tube,
        int targetIndex
    ) {
        List<CorridorTubeSlice> window = contiguousDirectWindow(
            track, tube, targetIndex, ANALYSIS_RADII_METERS.get(0));
        if (window.size() < 5) {
            return 0.0;
        }
        CorridorTubeSlice target = tube.at(targetIndex);
        double leftSpan = target.distanceMeters() - window.get(0).distanceMeters();
        double rightSpan = window.get(window.size() - 1).distanceMeters() - target.distanceMeters();
        if (leftSpan < 2.0 || rightSpan < 2.0) {
            return 0.0;
        }
        double neighborhood = window.stream().mapToDouble(CorridorTubeSlice::motionSupport)
            .average().orElse(0.0);
        return clamp(Math.min(target.motionSupport(), neighborhood));
    }

    private ScaleResult evaluateScale(
        CorridorTrack track,
        LongitudinalCorridorTube tube,
        int targetIndex,
        double sourcePixel,
        double radiusMeters
    ) {
        List<CorridorTubeSlice> window = contiguousDirectWindow(track, tube, targetIndex, radiusMeters);
        CorridorTubeSlice target = tube.at(targetIndex);
        if (window.size() < 5) {
            return ScaleResult.invalid(radiusMeters);
        }
        double leftSpan = target.distanceMeters() - window.get(0).distanceMeters();
        double rightSpan = window.get(window.size() - 1).distanceMeters() - target.distanceMeters();
        double span = leftSpan + rightSpan;
        if (span < 0.65 * radiusMeters
            || leftSpan < 0.25 * radiusMeters || rightSpan < 0.25 * radiusMeters) {
            return ScaleResult.invalid(radiusMeters);
        }
        double coverage = clamp(span / (2.0 * radiusMeters));
        List<Observation> observations = window.stream()
            .map(slice -> observation(slice, target.distanceMeters(), sourcePixel)).toList();
        PolynomialFit affine = robustFit(observations, 1, sourcePixel);
        if (!affine.valid()) {
            return ScaleResult.invalid(radiusMeters);
        }
        PolynomialFit quadratic = robustFit(observations, 2, sourcePixel);
        double affineError = weightedError(observations, affine);
        double quadraticError = quadratic.valid() ? weightedError(observations, quadratic) : affineError;
        double improvement = affineError <= EPSILON ? 0.0
            : clamp((affineError - quadraticError) / affineError);
        double curvatureAmplitude = quadratic.valid()
            ? Math.abs(quadratic.quadratic()) * radiusMeters * radiusMeters / sourcePixel : 0.0;
        boolean useQuadratic = quadratic.valid() && improvement >= 0.15 && curvatureAmplitude >= 0.08;
        PolynomialFit trend = useQuadratic ? quadratic : affine;
        List<Double> residualsSourcePx = new ArrayList<>(observations.size());
        List<Double> residualChanges = new ArrayList<>(observations.size());
        for (Observation observation : observations) {
            residualsSourcePx.add((observation.localCenterPx() - trend.value(observation.xMeters()))
                / sourcePixel);
        }
        double deadband = 0.10;
        int previousSign = 0;
        int reversals = 0;
        List<Double> reversalDistances = new ArrayList<>();
        for (int index = 1; index < residualsSourcePx.size(); index++) {
            double change = residualsSourcePx.get(index) - residualsSourcePx.get(index - 1);
            residualChanges.add(change);
            int sign = change > deadband ? 1 : change < -deadband ? -1 : 0;
            if (sign != 0) {
                if (previousSign != 0 && sign != previousSign) {
                    reversals++;
                    reversalDistances.add(window.get(index).distanceMeters());
                }
                previousSign = sign;
            }
        }
        List<Double> spacings = new ArrayList<>();
        for (int index = 1; index < reversalDistances.size(); index++) {
            spacings.add(reversalDistances.get(index) - reversalDistances.get(index - 1));
        }
        Collections.sort(spacings);
        double reversalSpacing = spacings.isEmpty() ? Double.NaN : median(spacings);
        double exposure = reversals < 2 ? 0.0
            : clamp((radiusMeters - (Double.isNaN(reversalSpacing) ? radiusMeters : reversalSpacing))
                / radiusMeters);
        double amplitude = percentileAbsolute(residualsSourcePx, 0.80);
        double uncertainty = Math.max(0.05, 1.4826 * mad(residualsSourcePx));
        double confidence = window.stream().mapToDouble(CorridorTubeSlice::confidence)
            .average().orElse(0.0);
        double reliability = coverage * clamp(confidence)
            * (1.0 - smoothStep(0.50, 1.50, uncertainty));
        double channelAgreement = observations.stream().mapToDouble(Observation::channelAgreement)
            .average().orElse(0.0);
        double localChannelSupport = observations.stream().mapToDouble(Observation::localChannelSupport)
            .average().orElse(0.0);
        double bend = clamp(smoothStep(0.08, 0.45, curvatureAmplitude) * improvement
            * channelAgreement * reliability);
        double wrinkle = clamp(exposure * smoothStep(0.08, 0.35, amplitude)
            * reliability * (0.55 + 0.45 * (1.0 - localChannelSupport)) * (1.0 - bend));
        return new ScaleResult(radiusMeters, coverage, trend.intercept() / sourcePixel,
            trend.linear() / sourcePixel, 2.0 * trend.quadratic() / sourcePixel,
            uncertainty, amplitude, reversalSpacing, channelAgreement, reliability,
            wrinkle, bend, reversals, true);
    }

    private List<CorridorTubeSlice> contiguousDirectWindow(
        CorridorTrack track,
        LongitudinalCorridorTube tube,
        int targetIndex,
        double radiusMeters
    ) {
        int left = targetIndex;
        int right = targetIndex;
        double targetDistance = tube.at(targetIndex).distanceMeters();
        while (left > 0 && direct(track, tube.at(left - 1))
            && targetDistance - tube.at(left - 1).distanceMeters() <= radiusMeters + 1e-9) {
            left--;
        }
        while (right + 1 < tube.slices().size() && direct(track, tube.at(right + 1))
            && tube.at(right + 1).distanceMeters() - targetDistance <= radiusMeters + 1e-9) {
            right++;
        }
        return List.copyOf(tube.slices().subList(left, right + 1));
    }

    private boolean direct(CorridorTrack track, CorridorTubeSlice slice) {
        if (!slice.observed()) {
            return false;
        }
        if (track == null) {
            return true;
        }
        CorridorTrackPoint point = track.points().get(slice.profileIndex());
        return point != null && point.support() == CorridorPointSupport.DIRECT_UNION;
    }

    private CleanupEvidenceProvenance provenance(CorridorTrack track, CorridorTubeSlice slice) {
        if (track == null) {
            return slice.observed() ? CleanupEvidenceProvenance.DIRECT
                : CleanupEvidenceProvenance.UNSUPPORTED;
        }
        CorridorTrackPoint point = track.points().get(slice.profileIndex());
        if (point == null) {
            return CleanupEvidenceProvenance.UNSUPPORTED;
        }
        return point.support() == CorridorPointSupport.DIRECT_UNION
            ? CleanupEvidenceProvenance.DIRECT : CleanupEvidenceProvenance.BOUNDED_INTERPOLATION;
    }

    private Observation observation(CorridorTubeSlice slice, double targetDistance, double sourcePixel) {
        double core = slice.hasIntervals() ? (slice.coreMinPx() + slice.coreMaxPx()) / 2.0
            : slice.localCenterOffsetPx();
        double[] channels = {slice.rawCenterPx(), slice.lightCenterPx(), slice.standardCenterPx(), core};
        Arrays.sort(channels);
        double consensus = (channels[1] + channels[2]) / 2.0;
        double spread = channels[3] - channels[0];
        double channelAgreement = 1.0 - smoothStep(0.20, 1.00, spread / sourcePixel);
        double localSupport = 1.0 - smoothStep(0.15, 0.75,
            Math.abs(slice.localCenterOffsetPx() - consensus) / sourcePixel);
        double weight = Math.max(0.05, slice.confidence())
            / square(Math.max(0.25 * sourcePixel, slice.uncertaintyPx()));
        return new Observation(slice.distanceMeters() - targetDistance,
            slice.localCenterOffsetPx(), consensus, weight, channelAgreement, localSupport);
    }

    private PolynomialFit robustFit(List<Observation> observations, int degree, double sourcePixel) {
        List<Double> base = observations.stream().map(Observation::weight).toList();
        List<Double> weights = new ArrayList<>(base);
        PolynomialFit fit = weightedFit(observations, weights, degree);
        if (!fit.valid()) {
            return fit;
        }
        for (int iteration = 0; iteration < ROBUST_ITERATIONS; iteration++) {
            List<Double> residuals = new ArrayList<>(observations.size());
            for (Observation observation : observations) {
                residuals.add(observation.localCenterPx() - fit.value(observation.xMeters()));
            }
            double scale = Math.max(0.05 * sourcePixel, 1.4826 * mad(residuals));
            for (int index = 0; index < observations.size(); index++) {
                double normalized = Math.abs(residuals.get(index)) / Math.max(EPSILON, 1.5 * scale);
                weights.set(index, base.get(index) * (normalized <= 1.0 ? 1.0 : 1.0 / normalized));
            }
            fit = weightedFit(observations, weights, degree);
            if (!fit.valid()) {
                return fit;
            }
        }
        return fit;
    }

    private PolynomialFit weightedFit(List<Observation> observations, List<Double> weights, int degree) {
        int size = degree + 1;
        double[][] matrix = new double[size][size];
        double[] vector = new double[size];
        for (int index = 0; index < observations.size(); index++) {
            Observation observation = observations.get(index);
            double weight = weights.get(index);
            double[] powers = {1.0, observation.xMeters(), square(observation.xMeters()),
                square(observation.xMeters()) * observation.xMeters(),
                square(square(observation.xMeters()))};
            for (int row = 0; row < size; row++) {
                vector[row] += weight * powers[row] * observation.localCenterPx();
                for (int column = 0; column < size; column++) {
                    matrix[row][column] += weight * powers[row + column];
                }
            }
        }
        double[] solution = solve(matrix, vector);
        if (solution == null) {
            return PolynomialFit.invalid();
        }
        return new PolynomialFit(solution[0], solution.length > 1 ? solution[1] : 0.0,
            solution.length > 2 ? solution[2] : 0.0, true);
    }

    private double[] solve(double[][] matrix, double[] vector) {
        int size = vector.length;
        double[][] work = new double[size][size + 1];
        for (int row = 0; row < size; row++) {
            System.arraycopy(matrix[row], 0, work[row], 0, size);
            work[row][size] = vector[row];
        }
        for (int column = 0; column < size; column++) {
            int pivot = column;
            for (int row = column + 1; row < size; row++) {
                if (Math.abs(work[row][column]) > Math.abs(work[pivot][column])) {
                    pivot = row;
                }
            }
            if (Math.abs(work[pivot][column]) <= 1e-10) {
                return null;
            }
            double[] swap = work[column];
            work[column] = work[pivot];
            work[pivot] = swap;
            double divisor = work[column][column];
            for (int index = column; index <= size; index++) {
                work[column][index] /= divisor;
            }
            for (int row = 0; row < size; row++) {
                if (row == column) {
                    continue;
                }
                double factor = work[row][column];
                for (int index = column; index <= size; index++) {
                    work[row][index] -= factor * work[column][index];
                }
            }
        }
        double[] result = new double[size];
        for (int index = 0; index < size; index++) {
            result[index] = work[index][size];
            if (!Double.isFinite(result[index])) {
                return null;
            }
        }
        return result;
    }

    private double weightedError(List<Observation> observations, PolynomialFit fit) {
        double total = 0.0;
        double weights = 0.0;
        for (Observation observation : observations) {
            double residual = observation.localCenterPx() - fit.value(observation.xMeters());
            total += observation.weight() * residual * residual;
            weights += observation.weight();
        }
        return weights <= EPSILON ? Double.POSITIVE_INFINITY : total / weights;
    }

    private Centers centers(CorridorTubeSlice slice, double sourcePixel) {
        double core = slice.hasIntervals() ? (slice.coreMinPx() + slice.coreMaxPx()) / 2.0
            : slice.localCenterOffsetPx();
        return new Centers(slice.rawCenterPx() / sourcePixel, slice.lightCenterPx() / sourcePixel,
            slice.standardCenterPx() / sourcePixel, core / sourcePixel,
            slice.localCenterOffsetPx() / sourcePixel,
            slice.stabilityCenterOffsetPx() / sourcePixel, slice.centerOffsetPx() / sourcePixel);
    }

    private LocalShapeEvidence unavailable(
        CorridorTubeSlice target,
        double sourcePixel,
        CleanupEvidenceProvenance provenance,
        Centers centers,
        Reason reason
    ) {
        return LocalShapeEvidence.unavailable(target.profileIndex(), target.distanceMeters(), sourcePixel,
            provenance, centers.raw(), centers.light(), centers.standard(), centers.core(), centers.local(),
            centers.stability(), centers.effective(), reason);
    }

    private double mad(List<Double> values) {
        List<Double> ordered = values.stream().sorted().toList();
        double center = median(ordered);
        return median(ordered.stream().map(value -> Math.abs(value - center)).sorted().toList());
    }

    private double percentileAbsolute(List<Double> values, double percentile) {
        List<Double> ordered = values.stream().map(Math::abs).sorted().toList();
        int index = Math.max(0, Math.min(ordered.size() - 1,
            (int) Math.ceil(percentile * ordered.size()) - 1));
        return ordered.get(index);
    }

    private double median(List<Double> sorted) {
        int middle = sorted.size() / 2;
        return (sorted.size() & 1) == 0
            ? (sorted.get(middle - 1) + sorted.get(middle)) / 2.0 : sorted.get(middle);
    }

    private double smoothStep(double onset, double full, double value) {
        double normalized = clamp((value - onset) / Math.max(EPSILON, full - onset));
        return normalized * normalized * (3.0 - 2.0 * normalized);
    }

    private double square(double value) {
        return value * value;
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private record Observation(double xMeters, double localCenterPx, double channelCenterPx,
        double weight, double channelAgreement, double localChannelSupport) {
    }

    private record PolynomialFit(double intercept, double linear, double quadratic, boolean valid) {
        static PolynomialFit invalid() {
            return new PolynomialFit(0.0, 0.0, 0.0, false);
        }

        double value(double x) {
            return intercept + linear * x + quadratic * x * x;
        }
    }

    private record ScaleResult(double radiusMeters, double coverage, double trendCenterSourcePx,
        double trendSlopeSourcePxPerMeter, double trendCurvatureSourcePxPerMeter2,
        double uncertaintySourcePx, double amplitudeSourcePx, double reversalSpacingMeters,
        double channelAgreement, double reliability, double wrinkleScore, double bendScore,
        int reversalCount, boolean valid) {
        static ScaleResult invalid(double radiusMeters) {
            return new ScaleResult(radiusMeters, 0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, Double.NaN, 0.0, 0.0, 0.0, 0.0, 0, false);
        }

        double selectionScore() {
            return reliability * Math.max(wrinkleScore, bendScore);
        }
    }

    private record Centers(double raw, double light, double standard, double core,
        double local, double stability, double effective) {
    }
}
