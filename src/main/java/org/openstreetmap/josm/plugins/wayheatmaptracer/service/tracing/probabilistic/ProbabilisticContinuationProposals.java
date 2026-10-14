package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.Arrays;
import java.util.List;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ImageOrientationSupport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters;

/** Bounded geometric choices, never interpolated observations or longitudinal support. */
final class ProbabilisticContinuationProposals {
    private static final double MAXIMUM_SPAN_METERS = 64.0;
    private static final int MAXIMUM_PROFILE_INTERVALS = 64;
    private static final long MAXIMUM_BOUNDARY_PAIRS = 8;
    private static final long MAXIMUM_WORK_UNITS = 1_000_000L;

    /** Exhaustion refuses the entire proposal array rather than emitting partial choices. */
    static final class ResourceLimitException extends RuntimeException {
        ResourceLimitException() {
            super("continuation proposal work limit");
        }
    }

    private ProbabilisticContinuationProposals() { }

    /** Returns NaN where the existing lattice must remain unchanged. */
    static double[] build(List<ProbabilisticProfile> profiles, CancellationProbe cancellation,
            AttemptMemoryLedger.Owner owner) {
        return build(profiles, cancellation, owner, EvidenceModelParameters.defaults().localization());
    }

    /** Uses the immutable descriptor policy that actually produced these measured directions. */
    static double[] build(List<ProbabilisticProfile> profiles, CancellationProbe cancellation,
            AttemptMemoryLedger.Owner owner, EvidenceModelParameters.Localization localization) {
        return build(profiles, cancellation, owner, MAXIMUM_WORK_UNITS, localization);
    }

    /** Package-scoped cap injection permits deterministic admission/refusal tests. */
    static double[] build(List<ProbabilisticProfile> profiles, CancellationProbe cancellation,
            AttemptMemoryLedger.Owner owner, long maximumWorkUnits) {
        return build(profiles, cancellation, owner, maximumWorkUnits,
                EvidenceModelParameters.defaults().localization());
    }

    private static double[] build(List<ProbabilisticProfile> profiles, CancellationProbe cancellation,
            AttemptMemoryLedger.Owner owner, long maximumWorkUnits,
            EvidenceModelParameters.Localization localization) {
        if (profiles == null || cancellation == null || maximumWorkUnits < 0 || localization == null) {
            throw new IllegalArgumentException("Continuation proposal inputs are incomplete");
        }
        cancellation.checkpoint();
        var retained = owner == null ? null : owner.child("continuation-proposals");
        try {
            var workOwner = retained == null ? null : retained.child("work-counter");
            var work = workOwner == null ? new WorkBudget(maximumWorkUnits, cancellation)
                    : ProbabilisticInference.allocated(workOwner,
                        AttemptMemoryLedger.objectBytes(24), "continuation proposals",
                        () -> new WorkBudget(maximumWorkUnits, cancellation));
            double[] offsets = retained == null ? new double[profiles.size()]
                    : ProbabilisticInference.allocated(retained,
                        AttemptMemoryLedger.arrayBytes(profiles.size(), Double.BYTES),
                        "continuation proposals", () -> new double[profiles.size()]);
            Arrays.fill(offsets, Double.NaN);
            long pairVisits = 0;
            long emitted = 0;
            long oversized = 0;
            long ambiguous = 0;
            for (int start = 0; start < profiles.size(); start++) {
                work.visit();
                if (!weak(profiles.get(start), work)) continue;
                int end = start;
                while (end + 1 < profiles.size() && weak(profiles.get(end + 1), work)) {
                    work.visit();
                    end++;
                }
                if (start == 0 || end + 1 == profiles.size()) {
                    start = end;
                    continue;
                }
                int leftIndex = start - 1;
                int rightIndex = end + 1;
                ProbabilisticProfile left = profiles.get(leftIndex);
                ProbabilisticProfile right = profiles.get(rightIndex);
                double span = right.chainageMeters() - left.chainageMeters();
                long pairCount = (long) left.modes().size() * right.modes().size();
                if (rightIndex - leftIndex > MAXIMUM_PROFILE_INTERVALS || span > MAXIMUM_SPAN_METERS
                        || pairCount > MAXIMUM_BOUNDARY_PAIRS) {
                    oversized++;
                    start = end;
                    continue;
                }
                if (!(span > 0.0)) {
                    start = end;
                    continue;
                }
                int eligible = 0;
                ProbabilisticProfile.Mode selectedLeft = null;
                ProbabilisticProfile.Mode selectedRight = null;
                for (var leftMode : left.modes()) {
                    for (var rightMode : right.modes()) {
                        work.visit();
                        pairVisits++;
                        if (!boundary(leftMode, true) || !boundary(rightMode, false)
                                || !compatible(left, leftMode, right, rightMode, localization)
                                || !validIntersections(profiles, leftIndex, rightIndex, leftMode, rightMode,
                                    work)) continue;
                        eligible++;
                        selectedLeft = leftMode;
                        selectedRight = rightMode;
                    }
                }
                if (eligible == 1) {
                    for (int index = start; index <= end; index++) {
                        work.visit();
                        offsets[index] = intersection(left, selectedLeft, right, selectedRight,
                                profiles.get(index), false, work);
                        emitted++;
                    }
                } else if (eligible > 1) {
                    ambiguous++;
                }
                start = end;
            }
            ModernDiagnosticCounters.record("continuation.pairVisits", pairVisits);
            ModernDiagnosticCounters.record("continuation.proposedPositions", emitted);
            ModernDiagnosticCounters.record("continuation.oversizedRuns", oversized);
            ModernDiagnosticCounters.record("continuation.ambiguousRuns", ambiguous);
            ModernDiagnosticCounters.record("continuation.workUnits", work.units);
            if (workOwner != null) workOwner.close();
            if (retained != null) retained.transferTo(owner);
            return offsets;
        } finally {
            if (retained != null) retained.close();
        }
    }

    private static boolean weak(ProbabilisticProfile profile, WorkBudget work) {
        work.visit();
        for (var mode : profile.modes()) {
            work.visit();
            if (mode.branchCoherence() > 0.0) return false;
        }
        return true;
    }

    private static boolean boundary(ProbabilisticProfile.Mode mode, boolean left) {
        return !mode.groupedParent()
                && mode.branchCoherence() > 0.0
                && mode.orientationSupport().status() == ImageOrientationSupport.Status.MEASURED_TWO_SIDED
                && (left ? mode.leftDirectSpanMeters() : mode.rightDirectSpanMeters()) >= 4.0;
    }

    private static boolean compatible(ProbabilisticProfile left, ProbabilisticProfile.Mode leftMode,
            ProbabilisticProfile right, ProbabilisticProfile.Mode rightMode,
            EvidenceModelParameters.Localization localization) {
        if (!left.nativePitchKnown() || !right.nativePitchKnown()) return false;
        double dx = x(right, rightMode) - x(left, leftMode);
        double dy = y(right, rightMode) - y(left, leftMode);
        double length = StrictMath.hypot(dx, dy);
        if (!(length > 0.0) || !Double.isFinite(length)) return false;
        double bearing = StrictMath.atan2(dy, dx);
        double leftRay = Math.max(localization.minimumOrientationRayMeters(),
                localization.orientationRayLengthPitches() * left.sourcePitchMeters());
        double rightRay = Math.max(localization.minimumOrientationRayMeters(),
                localization.orientationRayLengthPitches() * right.sourcePitchMeters());
        return leftRay * Math.sqrt(leftMode.orientationSupport().mismatchSquared(bearing))
                    <= 1.5 * left.sourcePitchMeters()
                && rightRay * Math.sqrt(rightMode.orientationSupport().mismatchSquared(bearing))
                    <= 1.5 * right.sourcePitchMeters();
    }

    private static boolean validIntersections(List<ProbabilisticProfile> profiles, int start, int end,
            ProbabilisticProfile.Mode leftMode, ProbabilisticProfile.Mode rightMode,
            WorkBudget work) {
        double previousFraction = 0.0;
        for (int index = start + 1; index < end; index++) {
            work.visit();
            var profile = profiles.get(index);
            if (profile.exactAnchorOffsetMeters().isPresent()) return false;
            double fraction = intersection(profiles.get(start), leftMode, profiles.get(end),
                    rightMode, profile, true, work);
            double offset = intersection(profiles.get(start), leftMode, profiles.get(end),
                    rightMode, profile, false, work);
            if (!Double.isFinite(fraction) || fraction <= previousFraction || fraction >= 1.0
                    || !Double.isFinite(offset) || offset < profile.minimumOffsetMeters()
                    || offset > profile.maximumOffsetMeters()) return false;
            previousFraction = fraction;
        }
        return true;
    }

    private static double intersection(ProbabilisticProfile left, ProbabilisticProfile.Mode leftMode,
            ProbabilisticProfile right, ProbabilisticProfile.Mode rightMode,
            ProbabilisticProfile profile, boolean fraction, WorkBudget work) {
        work.visit();
        double dx = x(right, rightMode) - x(left, leftMode);
        double dy = y(right, rightMode) - y(left, leftMode);
        double nx = profile.normalUnit().xMeters();
        double ny = profile.normalUnit().yMeters();
        double denominator = dx * ny - dy * nx;
        if (Math.abs(denominator) <= 1e-9 * StrictMath.hypot(dx, dy)) return Double.NaN;
        double ax = profile.anchor().xMeters() - x(left, leftMode);
        double ay = profile.anchor().yMeters() - y(left, leftMode);
        return fraction ? (ax * ny - ay * nx) / denominator
                : (ax * dy - ay * dx) / denominator;
    }

    private static double x(ProbabilisticProfile profile, ProbabilisticProfile.Mode mode) {
        return profile.anchor().xMeters() + profile.normalUnit().xMeters() * mode.coreCenterMeters();
    }

    private static double y(ProbabilisticProfile profile, ProbabilisticProfile.Mode mode) {
        return profile.anchor().yMeters() + profile.normalUnit().yMeters() * mode.coreCenterMeters();
    }

    private static final class WorkBudget {
        private final long limit;
        private final CancellationProbe cancellation;
        private long units;

        WorkBudget(long limit, CancellationProbe cancellation) {
            this.limit = limit;
            this.cancellation = cancellation;
        }

        void visit() {
            if (units >= limit) throw new ResourceLimitException();
            units++;
            cancellation.checkpoint();
        }
    }
}
