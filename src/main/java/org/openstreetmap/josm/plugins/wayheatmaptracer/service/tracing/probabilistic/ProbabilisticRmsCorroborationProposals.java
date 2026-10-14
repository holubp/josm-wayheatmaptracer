package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Non-authoritative eligibility only; no promoted observations or raster support. */
final class ProbabilisticRmsCorroborationProposals {
    /** Exact borrowed identities; both candidate-specific raster legs still require proof. */
    record Proposal(ProbabilisticProfile profile,
            LocalScalarProfileExtractor.SuppressedMode suppression,
            ProbabilisticProfile leftProfile, ProbabilisticProfile.Mode leftMode,
            ProbabilisticProfile rightProfile, ProbabilisticProfile.Mode rightMode) { }

    private ProbabilisticRmsCorroborationProposals() { }

    /**
     * Scans only the frozen original observations. Callers must keep the input profiles and
     * detailed results (and their memory owners) alive for the entire lifetime of the returned
     * descriptors. Only newly allocated descriptors, lists, copies and scratch are charged here.
     * The work hook belongs to the caller's existing cumulative cap; refusal or cancellation
     * discards all new owned output. Eligibility grants no orientation, reliability or support.
     */
    static List<Proposal> build(List<ProbabilisticProfile> profiles,
            List<LocalScalarProfileExtractor.DetailedResult> details,
            CancellationProbe cancellation, AttemptMemoryLedger.Owner owner, LongConsumer charge) {
        if (profiles == null || details == null || profiles.size() != details.size()
                || cancellation == null || charge == null) {
            throw new IllegalArgumentException("RMS corroboration inputs are incomplete");
        }
        visit(cancellation, charge);
        var retained = owner == null ? null : owner.child("rms-corroboration-proposals");
        var scratch = retained == null ? null : retained.child("builder");
        try {
            int capacity = Math.max(0, profiles.size() - 2);
            List<Proposal> output = allocated(scratch, AttemptMemoryLedger.objectBytes(24)
                    + AttemptMemoryLedger.referenceArrayBytes(capacity),
                    () -> new ArrayList<>(capacity));
            for (int index = 0; index < profiles.size(); index++) {
                visit(cancellation, charge);
                if (profiles.get(index) == null || details.get(index) == null) {
                    throw new IllegalArgumentException("RMS corroboration row is missing");
                }
            }
            for (int index = 1; index + 1 < profiles.size(); index++) {
                visit(cancellation, charge);
                var profile = profiles.get(index);
                var detail = details.get(index);
                var left = profiles.get(index - 1);
                var right = profiles.get(index + 1);
                if (profile.exactAnchorOffsetMeters().isPresent() || !profile.nativePitchKnown()
                        || !left.nativePitchKnown() || !right.nativePitchKnown()
                        || !(profile.chainageMeters() > left.chainageMeters())
                        || !(right.chainageMeters() > profile.chainageMeters())
                        || detail.suppressedModes().isEmpty()) continue;
                var pairOwner = scratch == null ? null : scratch.child("exterior-pairs");
                try {
                    double[][] scores = allocated(pairOwner, ProbabilisticInference.deepArrayBytes(
                            left.modes().size(), right.modes().size(), Double.BYTES),
                            () -> new double[left.modes().size()][right.modes().size()]);
                    for (int l = 0; l < left.modes().size(); l++) {
                        for (int r = 0; r < right.modes().size(); r++) {
                            visit(cancellation, charge);
                            var leftMode = left.modes().get(l);
                            var rightMode = right.modes().get(r);
                            if (leftMode.groupedParent() || rightMode.groupedParent()) continue;
                            var points = pairOwner == null ? null : reserve(pairOwner,
                                    2L * AttemptMemoryLedger.objectBytes(16));
                            try {
                                scores[l][r] = LongitudinalModeReliability.pairScore(
                                        left, leftMode, right, rightMode);
                            } finally {
                                if (points != null) points.close();
                            }
                        }
                    }
                    LocalScalarProfileExtractor.SuppressedMode selected = null;
                    ProbabilisticProfile.Mode selectedLeft = null;
                    ProbabilisticProfile.Mode selectedRight = null;
                    long reciprocalPairs = 0;
                    for (int l = 0; l < left.modes().size(); l++) {
                        for (int r = 0; r < right.modes().size(); r++) {
                            visit(cancellation, charge);
                            if (reciprocal(scores, l, r, cancellation, charge)) reciprocalPairs++;
                        }
                    }
                    long qualifying = 0;
                    for (var suppression : detail.suppressedModes()) {
                        visit(cancellation, charge);
                        boolean complete = complete(profile, suppression, cancellation, charge);
                        for (int l = 0; l < left.modes().size(); l++) {
                            for (int r = 0; r < right.modes().size(); r++) {
                                visit(cancellation, charge);
                                var leftMode = left.modes().get(l);
                                var rightMode = right.modes().get(r);
                                if (!complete || reciprocalPairs != 1
                                        || !reciprocal(scores, l, r, cancellation, charge)
                                        || !nearChord(left, leftMode, right, rightMode, profile,
                                            suppression.mode().centerMeters())
                                        || !uncontested(left, leftMode, details.get(index - 1),
                                            cancellation, charge)
                                        || !uncontested(right, rightMode, details.get(index + 1),
                                            cancellation, charge)) continue;
                                qualifying++;
                                selected = suppression;
                                selectedLeft = leftMode;
                                selectedRight = rightMode;
                            }
                        }
                    }
                    if (qualifying == 1) {
                        visit(cancellation, charge);
                        var suppression = selected;
                        var leftMode = selectedLeft;
                        var rightMode = selectedRight;
                        output.add(allocated(retained, AttemptMemoryLedger.objectBytes(48),
                                () -> new Proposal(profile, suppression, left, leftMode, right, rightMode)));
                    }
                } finally {
                    if (pairOwner != null) pairOwner.close();
                }
            }
            visit(cancellation, charge);
            // List.copyOf(ArrayList) materializes a temporary reference array as well as
            // the returned immutable list/array; admit both before copying.
            var copyArray = scratch == null ? null : reserve(scratch,
                    AttemptMemoryLedger.referenceArrayBytes(output.size()));
            List<Proposal> result;
            try {
                result = output.isEmpty() ? List.of() : allocated(retained,
                        ProbabilisticInference.listBytes(output.size()), () -> List.copyOf(output));
            } finally {
                if (copyArray != null) copyArray.close();
            }
            visit(cancellation, charge);
            if (scratch != null) scratch.close();
            if (retained != null) retained.transferTo(owner);
            return result;
        } finally {
            if (retained != null) retained.close();
        }
    }

    private static boolean reciprocal(double[][] scores, int left, int right,
            CancellationProbe cancellation, LongConsumer charge) {
        double selected = scores[left][right];
        if (!(selected > 0.0)) return false;
        // Matches actual pair ordering: strict greater wins, an equal second gives zero margin.
        for (int r = 0; r < scores[left].length; r++) {
            visit(cancellation, charge);
            if (r != right && scores[left][r] >= selected) return false;
        }
        for (int l = 0; l < scores.length; l++) {
            visit(cancellation, charge);
            if (l != left && scores[l][right] >= selected) return false;
        }
        return true;
    }

    private static boolean complete(ProbabilisticProfile profile,
            LocalScalarProfileExtractor.SuppressedMode suppression,
            CancellationProbe cancellation, LongConsumer charge) {
        var mode = suppression.mode();
        double minimum = mode.coreMinimumMeters();
        double maximum = mode.coreMaximumMeters();
        if (!Double.isFinite(minimum) || !Double.isFinite(maximum) || minimum > maximum
                || minimum <= profile.minimumOffsetMeters() || maximum >= profile.maximumOffsetMeters()
                || !Double.isFinite(mode.localizationSigmaMeters()) || mode.localizationSigmaMeters() <= 0
                || !(mode.existenceConfidence() > 0) || !(mode.localizationConfidence() > 0)
                || suppression.reason() != LocalScalarProfileExtractor.SuppressionReason.RMS_ONLY
                || suppression.lastSampleIndex() >= profile.samples().size()) return false;
        boolean before = false;
        boolean after = false;
        for (int index = 0; index < profile.samples().size(); index++) {
            visit(cancellation, charge);
            var sample = profile.samples().get(index);
            if (index >= suppression.firstSampleIndex() && index <= suppression.lastSampleIndex()
                    && !sample.valid()) return false;
            // Include both interpolation brackets of the stored core, never bridge invalid support.
            boolean intersects = sample.offsetMeters() >= minimum && sample.offsetMeters() <= maximum
                    || index + 1 < profile.samples().size() && sample.offsetMeters() <= maximum
                        && profile.samples().get(index + 1).offsetMeters() >= minimum
                    || index > 0 && sample.offsetMeters() >= minimum
                        && profile.samples().get(index - 1).offsetMeters() <= maximum;
            if (intersects && !sample.valid()) return false;
            before |= sample.offsetMeters() < minimum && sample.valid();
            after |= sample.offsetMeters() > maximum && sample.valid();
        }
        return before && after;
    }

    private static boolean uncontested(ProbabilisticProfile profile, ProbabilisticProfile.Mode mode,
            LocalScalarProfileExtractor.DetailedResult detail,
            CancellationProbe cancellation, LongConsumer charge) {
        boolean observed = false;
        for (var peak : detail.rawPeaks()) {
            visit(cancellation, charge);
            if (peak.lastSampleIndex() >= profile.samples().size()
                    || peak.offsetMeters() < mode.coreMinimumMeters() - profile.sourcePitchMeters()
                    || peak.offsetMeters() > mode.coreMaximumMeters() + profile.sourcePitchMeters()) return false;
            observed = true;
        }
        return observed;
    }

    private static boolean nearChord(ProbabilisticProfile left, ProbabilisticProfile.Mode leftMode,
            ProbabilisticProfile right, ProbabilisticProfile.Mode rightMode,
            ProbabilisticProfile profile, double offset) {
        double x = left.anchor().xMeters() + left.normalUnit().xMeters() * leftMode.coreCenterMeters();
        double y = left.anchor().yMeters() + left.normalUnit().yMeters() * leftMode.coreCenterMeters();
        double dx = right.anchor().xMeters() + right.normalUnit().xMeters() * rightMode.coreCenterMeters() - x;
        double dy = right.anchor().yMeters() + right.normalUnit().yMeters() * rightMode.coreCenterMeters() - y;
        double px = profile.anchor().xMeters() + profile.normalUnit().xMeters() * offset - x;
        double py = profile.anchor().yMeters() + profile.normalUnit().yMeters() * offset - y;
        double denominator = dx * profile.normalUnit().yMeters() - dy * profile.normalUnit().xMeters();
        double length = StrictMath.hypot(dx, dy);
        if (!(length > 0) || Math.abs(denominator) <= 1e-9 * length) return false;
        double ax = profile.anchor().xMeters() - x;
        double ay = profile.anchor().yMeters() - y;
        double fraction = (ax * profile.normalUnit().yMeters() - ay * profile.normalUnit().xMeters())
                / denominator;
        double chordOffset = (ax * dy - ay * dx) / denominator;
        double nearestFraction = Math.max(0.0, Math.min(1.0, (px * dx + py * dy) / (length * length)));
        double segmentDistance = StrictMath.hypot(px - nearestFraction * dx, py - nearestFraction * dy);
        return Double.isFinite(fraction) && fraction > 0 && fraction < 1
                && Double.isFinite(chordOffset) && chordOffset >= profile.minimumOffsetMeters()
                && chordOffset <= profile.maximumOffsetMeters()
                && segmentDistance <= profile.sourcePitchMeters();
    }

    private static void visit(CancellationProbe cancellation, LongConsumer charge) {
        cancellation.checkpoint();
        charge.accept(1L);
    }

    private static AttemptMemoryLedger.Reservation reserve(AttemptMemoryLedger.Owner owner, long bytes) {
        try {
            return owner.reserve(bytes);
        } catch (AttemptMemoryLedger.ResourceLimitException exception) {
            throw new ProbabilisticInference.MemoryLimit("RMS corroboration proposals", exception);
        }
    }

    private static <T> T allocated(AttemptMemoryLedger.Owner owner, long bytes,
            java.util.function.Supplier<T> allocation) {
        return owner == null ? allocation.get()
                : ProbabilisticInference.allocated(owner, bytes, "RMS corroboration proposals", allocation);
    }
}
