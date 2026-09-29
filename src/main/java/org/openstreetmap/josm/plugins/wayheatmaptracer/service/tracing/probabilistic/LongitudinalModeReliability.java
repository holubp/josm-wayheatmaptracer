package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ImageOrientationSupport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Bounded direct-observation continuation for Engine B weak-signal reliability. */
final class LongitudinalModeReliability {
    private static final double WINDOW_METERS = 20.0;
    private static final double MINIMUM_DIRECT_SPAN_METERS = 4.0;
    private static final long MAXIMUM_PAIR_EVALUATIONS = 1_000_000L;
    private static final long MAXIMUM_SUPPORT_EDGE_VISITS = 4_000_000L;
    private static final double EPSILON = 1e-12;

    /** Scores direct scalar, validity, and decision-region evidence between adjacent modes. */
    @FunctionalInterface
    interface DirectIntervalEvidence {
        double support(ProbabilisticProfile leftProfile, ProbabilisticProfile.Mode leftMode,
                ProbabilisticProfile rightProfile, ProbabilisticProfile.Mode rightMode,
                CancellationProbe cancellation);
    }

    private static final DirectIntervalEvidence ASSUME_OBSERVED =
            (leftProfile, leftMode, rightProfile, rightMode, cancellation) -> 1.0;

    /** Frozen profiles plus separately accounted association work. */
    record Result(List<ProbabilisticProfile> profiles, long pairEvaluations,
            long supportEdgeVisits) {
        Result {
            profiles = List.copyOf(profiles);
        }
    }

    /** Typed bounded-work failure translated by the engine to RESOURCE_LIMIT. */
    static final class ResourceLimitException extends RuntimeException {
        ResourceLimitException() {
            super("weak-signal association work limit");
        }
    }

    private LongitudinalModeReliability() { }

    /**
     * Applies direct continuation to already proved synthetic/profile-only observations.
     * Production callers must supply raster interval evidence through the three-argument overload.
     */
    static Result apply(List<ProbabilisticProfile> profiles, CancellationProbe cancellation) {
        return apply(profiles, ASSUME_OBSERVED, cancellation);
    }

    /** Applies the versioned direct-continuation policy without changing admitted modes or states. */
    static Result apply(List<ProbabilisticProfile> profiles,
            DirectIntervalEvidence intervalEvidence, CancellationProbe cancellation) {
        if (profiles == null || intervalEvidence == null || cancellation == null) {
            throw new IllegalArgumentException("Longitudinal reliability inputs are incomplete");
        }
        WorkBudget workBudget = new WorkBudget();
        List<Map<Integer, Edge>> forward = new ArrayList<>(Math.max(0, profiles.size() - 1));
        List<Map<Integer, Edge>> backward = new ArrayList<>(Math.max(0, profiles.size() - 1));
        long pairEvaluations = 0L;
        for (int index = 0; index + 1 < profiles.size(); index++) {
            cancellation.checkpoint();
            ProbabilisticProfile left = profiles.get(index);
            ProbabilisticProfile right = profiles.get(index + 1);
            long work = (long) left.modes().size() * right.modes().size();
            if (work > MAXIMUM_PAIR_EVALUATIONS - pairEvaluations) {
                throw new ResourceLimitException();
            }
            pairEvaluations += work;
            EdgeMaps maps = associate(left, right, intervalEvidence, cancellation);
            forward.add(maps.forward());
            backward.add(maps.backward());
        }

        List<double[]> triplet = tripletConfidence(profiles, forward, backward, cancellation);
        List<ProbabilisticProfile> result = new ArrayList<>(profiles.size());
        for (int profileIndex = 0; profileIndex < profiles.size(); profileIndex++) {
            cancellation.checkpoint();
            ProbabilisticProfile profile = profiles.get(profileIndex);
            List<ProbabilisticProfile.Mode> modes = new ArrayList<>(profile.modes().size());
            for (int modeIndex = 0; modeIndex < profile.modes().size(); modeIndex++) {
                ProbabilisticProfile.Mode mode = profile.modes().get(modeIndex);
                double left = integratedSupport(profiles, forward, backward, triplet,
                        profileIndex, modeIndex, -1, workBudget, cancellation);
                double right = integratedSupport(profiles, forward, backward, triplet,
                        profileIndex, modeIndex, 1, workBudget, cancellation);
                double continuation = Math.sqrt(left * right);
                double coherence = mode.localizationConfidence() * continuation;
                modes.add(copy(mode, coherence,
                        ProbabilisticProfile.Mode.combineReliability(
                                mode.scalarAmplitudeReliability(), coherence)));
            }
            result.add(copy(profile, modes));
        }
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
                "reliability.associationPairs", pairEvaluations);
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
                "reliability.supportEdgeVisits", workBudget.edgeVisits());
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
                "reliability.windowMeters", WINDOW_METERS);
        return new Result(result, pairEvaluations, workBudget.edgeVisits());
    }

    private static EdgeMaps associate(ProbabilisticProfile left, ProbabilisticProfile right,
            DirectIntervalEvidence intervalEvidence, CancellationProbe cancellation) {
        int leftCount = left.modes().size();
        int rightCount = right.modes().size();
        double[][] score = new double[leftCount][rightCount];
        for (int leftIndex = 0; leftIndex < leftCount; leftIndex++) {
            for (int rightIndex = 0; rightIndex < rightCount; rightIndex++) {
                cancellation.checkpoint();
                score[leftIndex][rightIndex] = pairScore(left, left.modes().get(leftIndex),
                        right, right.modes().get(rightIndex));
            }
        }
        Best[] leftBest = new Best[leftCount];
        Best[] rightBest = new Best[rightCount];
        for (int leftIndex = 0; leftIndex < leftCount; leftIndex++) {
            leftBest[leftIndex] = best(score[leftIndex]);
        }
        for (int rightIndex = 0; rightIndex < rightCount; rightIndex++) {
            double[] column = new double[leftCount];
            for (int leftIndex = 0; leftIndex < leftCount; leftIndex++) {
                column[leftIndex] = score[leftIndex][rightIndex];
            }
            rightBest[rightIndex] = best(column);
        }
        Map<Integer, Edge> forward = new HashMap<>();
        Map<Integer, Edge> backward = new HashMap<>();
        for (int leftIndex = 0; leftIndex < leftCount; leftIndex++) {
            Best fromLeft = leftBest[leftIndex];
            if (fromLeft.index() < 0) continue;
            Best fromRight = rightBest[fromLeft.index()];
            if (fromRight.index() != leftIndex) continue;
            ProbabilisticProfile.Mode leftMode = left.modes().get(leftIndex);
            ProbabilisticProfile.Mode rightMode = right.modes().get(fromLeft.index());
            double intervalSupport = intervalEvidence.support(
                    left, leftMode, right, rightMode, cancellation);
            if (!Double.isFinite(intervalSupport) || intervalSupport < 0.0
                    || intervalSupport > 1.0) {
                throw new IllegalArgumentException("Direct interval support must be finite and normalized");
            }
            double confidence = fromLeft.score() * Math.sqrt(
                    margin(fromLeft) * margin(fromRight)) * intervalSupport;
            if (confidence <= 0.0) continue;
            Edge edge = new Edge(leftIndex, fromLeft.index(), confidence);
            forward.put(leftIndex, edge);
            backward.put(fromLeft.index(), edge);
        }
        return new EdgeMaps(Map.copyOf(forward), Map.copyOf(backward));
    }

    private static double pairScore(ProbabilisticProfile leftProfile,
            ProbabilisticProfile.Mode leftMode, ProbabilisticProfile rightProfile,
            ProbabilisticProfile.Mode rightMode) {
        double chainageGap = rightProfile.chainageMeters() - leftProfile.chainageMeters();
        if (!(chainageGap > 0.0) || chainageGap > WINDOW_METERS + EPSILON
                || leftMode.groupedParent() != rightMode.groupedParent()
                || !directOrientation(leftMode.orientationSupport())
                || !directOrientation(rightMode.orientationSupport())) {
            return 0.0;
        }
        MetricPoint left = center(leftProfile, leftMode);
        MetricPoint right = center(rightProfile, rightMode);
        double dx = right.xMeters() - left.xMeters();
        double dy = right.yMeters() - left.yMeters();
        double distance = StrictMath.hypot(dx, dy);
        if (!(distance > 0.0)) return 0.0;
        double bearing = StrictMath.atan2(dy, dx);
        double leftResidual = distance * Math.sqrt(
                leftMode.orientationSupport().mismatchSquared(bearing));
        double rightResidual = distance * Math.sqrt(
                rightMode.orientationSupport().mismatchSquared(bearing));
        double uncertainty = Math.sqrt(square(leftMode.localizationSigmaMeters())
                + square(rightMode.localizationSigmaMeters())
                + square(leftProfile.sourcePitchMeters())
                + square(rightProfile.sourcePitchMeters()));
        double normalized = (square(leftResidual) + square(rightResidual))
                / square(uncertainty);
        double certainty = Math.sqrt(leftMode.orientationSupport().certainty()
                * rightMode.orientationSupport().certainty());
        return certainty * StrictMath.exp(-0.5 * normalized);
    }

    private static boolean directOrientation(ImageOrientationSupport support) {
        return support.status() == ImageOrientationSupport.Status.MEASURED_TWO_SIDED;
    }

    private static Best best(double[] values) {
        int index = -1;
        double first = 0.0;
        double second = 0.0;
        for (int candidate = 0; candidate < values.length; candidate++) {
            double value = values[candidate];
            if (value > first) {
                second = first;
                first = value;
                index = candidate;
            } else if (value > second) {
                second = value;
            }
        }
        return new Best(index, first, second);
    }

    private static double margin(Best best) {
        return best.score() <= 0.0 ? 0.0
                : Math.max(0.0, (best.score() - best.second())
                        / Math.max(best.score(), EPSILON));
    }

    private static List<double[]> tripletConfidence(List<ProbabilisticProfile> profiles,
            List<Map<Integer, Edge>> forward, List<Map<Integer, Edge>> backward,
            CancellationProbe cancellation) {
        List<double[]> result = new ArrayList<>(profiles.size());
        for (int profileIndex = 0; profileIndex < profiles.size(); profileIndex++) {
            ProbabilisticProfile profile = profiles.get(profileIndex);
            double[] confidence = new double[profile.modes().size()];
            if (profileIndex > 0 && profileIndex + 1 < profiles.size()) {
                for (int modeIndex = 0; modeIndex < profile.modes().size(); modeIndex++) {
                    cancellation.checkpoint();
                    Edge incoming = backward.get(profileIndex - 1).get(modeIndex);
                    Edge outgoing = forward.get(profileIndex).get(modeIndex);
                    if (incoming == null || outgoing == null) continue;
                    ProbabilisticProfile leftProfile = profiles.get(profileIndex - 1);
                    ProbabilisticProfile rightProfile = profiles.get(profileIndex + 1);
                    ProbabilisticProfile.Mode leftMode = leftProfile.modes().get(incoming.leftMode());
                    ProbabilisticProfile.Mode centerMode = profile.modes().get(modeIndex);
                    ProbabilisticProfile.Mode rightMode = rightProfile.modes().get(outgoing.rightMode());
                    double fraction = (profile.chainageMeters() - leftProfile.chainageMeters())
                            / (rightProfile.chainageMeters() - leftProfile.chainageMeters());
                    MetricPoint left = center(leftProfile, leftMode);
                    MetricPoint right = center(rightProfile, rightMode);
                    MetricPoint predicted = new MetricPoint(
                            left.xMeters() + fraction * (right.xMeters() - left.xMeters()),
                            left.yMeters() + fraction * (right.yMeters() - left.yMeters()));
                    double residual = center(profile, centerMode).distanceTo(predicted);
                    double uncertainty = Math.sqrt(square(leftMode.localizationSigmaMeters())
                            + square(centerMode.localizationSigmaMeters())
                            + square(rightMode.localizationSigmaMeters())
                            + square(leftProfile.sourcePitchMeters())
                            + square(profile.sourcePitchMeters())
                            + square(rightProfile.sourcePitchMeters()));
                    confidence[modeIndex] = StrictMath.exp(-0.5 * square(residual / uncertainty));
                }
            }
            result.add(confidence);
        }
        return List.copyOf(result);
    }

    private static double integratedSupport(List<ProbabilisticProfile> profiles,
            List<Map<Integer, Edge>> forward, List<Map<Integer, Edge>> backward,
            List<double[]> triplet, int originProfile, int originMode, int direction,
            WorkBudget workBudget, CancellationProbe cancellation) {
        double originChainage = profiles.get(originProfile).chainageMeters();
        double confidence = triplet.get(originProfile)[originMode];
        if (confidence <= 0.0) return 0.0;
        double integral = 0.0;
        double observedKernelMass = 0.0;
        double observedSpan = 0.0;
        int profileIndex = originProfile;
        int modeIndex = originMode;
        while (true) {
            cancellation.checkpoint();
            int edgeIndex = direction > 0 ? profileIndex : profileIndex - 1;
            if (edgeIndex < 0 || edgeIndex >= forward.size()) break;
            workBudget.visitEdge();
            Edge edge = direction > 0 ? forward.get(edgeIndex).get(modeIndex)
                    : backward.get(edgeIndex).get(modeIndex);
            if (edge == null) break;
            int nextProfile = profileIndex + direction;
            int nextMode = direction > 0 ? edge.rightMode() : edge.leftMode();
            double startDistance = Math.abs(
                    profiles.get(profileIndex).chainageMeters() - originChainage);
            double endDistance = Math.abs(
                    profiles.get(nextProfile).chainageMeters() - originChainage);
            if (startDistance >= WINDOW_METERS) break;
            double boundedEnd = Math.min(endDistance, WINDOW_METERS);
            double midpoint = 0.5 * (startDistance + boundedEnd);
            confidence = Math.min(confidence, edge.confidence());
            if (nextProfile > 0 && nextProfile + 1 < profiles.size()) {
                confidence = Math.min(confidence, triplet.get(nextProfile)[nextMode]);
            }
            double kernelMass = (boundedEnd - startDistance)
                    * Math.max(0.0, 1.0 - midpoint / WINDOW_METERS);
            integral += kernelMass * confidence;
            observedKernelMass += kernelMass;
            observedSpan = boundedEnd;
            if (endDistance >= WINDOW_METERS) break;
            profileIndex = nextProfile;
            modeIndex = nextMode;
        }
        if (observedSpan + EPSILON < MINIMUM_DIRECT_SPAN_METERS
                || observedKernelMass <= EPSILON) {
            return 0.0;
        }
        return Math.min(1.0, integral / observedKernelMass);
    }

    private static MetricPoint center(ProbabilisticProfile profile,
            ProbabilisticProfile.Mode mode) {
        double offset = mode.coreCenterMeters();
        return new MetricPoint(profile.anchor().xMeters() + profile.normalUnit().xMeters() * offset,
                profile.anchor().yMeters() + profile.normalUnit().yMeters() * offset);
    }

    private static ProbabilisticProfile.Mode copy(ProbabilisticProfile.Mode mode,
            double coherence, double reliability) {
        return new ProbabilisticProfile.Mode(mode.id(), mode.evidenceLineage(),
                mode.coreMinimumMeters(), mode.coreMaximumMeters(),
                mode.localizationSigmaMeters(), mode.existenceConfidence(),
                mode.localizationConfidence(), mode.peakOffsetsMeters(),
                mode.nestedCenterOffsetsMeters(), mode.groupedParent(),
                mode.orientationSupport(), mode.scalarAmplitudeReliability(),
                coherence, reliability);
    }

    private static ProbabilisticProfile copy(ProbabilisticProfile profile,
            List<ProbabilisticProfile.Mode> modes) {
        return new ProbabilisticProfile(profile.profileIndex(), profile.chainageMeters(),
                profile.anchor(), profile.normalUnit(), profile.minimumOffsetMeters(),
                profile.maximumOffsetMeters(), profile.sourcePitchMeters(),
                profile.nativePitchKnown(), profile.noiseFloor(), profile.samples(), modes,
                profile.censoredModes(), profile.exactAnchorOffsetMeters(),
                profile.orientationSupport());
    }

    private static double square(double value) {
        return value * value;
    }

    private static final class WorkBudget {
        private long edgeVisits;

        void visitEdge() {
            if (edgeVisits >= MAXIMUM_SUPPORT_EDGE_VISITS) {
                throw new ResourceLimitException();
            }
            edgeVisits++;
        }

        long edgeVisits() {
            return edgeVisits;
        }
    }

    private record Edge(int leftMode, int rightMode, double confidence) { }
    private record EdgeMaps(Map<Integer, Edge> forward, Map<Integer, Edge> backward) { }
    private record Best(int index, double score, double second) { }
}
