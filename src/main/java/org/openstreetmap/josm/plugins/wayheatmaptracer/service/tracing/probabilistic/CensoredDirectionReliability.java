package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.Arrays;
import java.util.List;
import java.util.function.LongConsumer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Observed censored-band corroboration, granting only a soft directional weight. */
final class CensoredDirectionReliability {
    private static final double EPSILON = 1e-12;
    private static final String STAGE = "censored directional reliability";

    /** The caller proves sampled scalar inequality, never a center or a strand identity. */
    @FunctionalInterface
    interface BandEvidence {
        boolean observed(ProbabilisticProfile left, ProbabilisticProfile.CensoredMode leftBand,
                ProbabilisticProfile right, ProbabilisticProfile.CensoredMode rightBand,
                CancellationProbe cancellation);
    }

    private CensoredDirectionReliability() { }

    /** Copies factual observations, changing only their soft directional weight. */
    static List<ProbabilisticProfile> apply(List<ProbabilisticProfile> profiles, BandEvidence evidence,
            CancellationProbe cancellation, LongConsumer charge, AttemptMemoryLedger.Owner owner) {
        var retained = owner == null ? null : owner.child("censored-reliability-result");
        var scratch = retained == null ? null : retained.child("censored-reliability-builder");
        try {
            double[][] weights = confidence(profiles,evidence,cancellation,charge,scratch);
            var result = allocated(scratch,ProbabilisticInference.listBytes(profiles.size()),
                    () -> new java.util.ArrayList<ProbabilisticProfile>(profiles.size()));
            for (int i = 0; i < profiles.size(); i++) {
                tick(cancellation,charge,1);
                var p = profiles.get(i);
                var bands = allocated(scratch,ProbabilisticInference.listBytes(p.censoredModes().size()),
                        () -> new java.util.ArrayList<ProbabilisticProfile.CensoredMode>(p.censoredModes().size()));
                for (int j = 0; j < p.censoredModes().size(); j++) {
                    tick(cancellation,charge,1);
                    var b = p.censoredModes().get(j); double weight = weights[i][j];
                    bands.add(allocated(retained,AttemptMemoryLedger.objectBytes(72),()->
                            new ProbabilisticProfile.CensoredMode(b.id(),b.evidenceLineage(),b.side(),
                                    b.observedBoundaryMeters(),b.existenceConfidence(),b.gradientSupported(),
                                    b.inwardExtentMeasured(),b.inwardSupportOffsetMeters(),b.shoulderThreshold(),weight)));
                }
                List<ProbabilisticProfile.CensoredMode> frozen = bands.isEmpty() ? List.of()
                        : allocated(retained,ProbabilisticInference.listBytes(bands.size()),()->List.copyOf(bands));
                if (retained != null) {
                    ProbabilisticProfileFactory.retainSharedProfileGraph(p,retained);
                    if (!p.modes().isEmpty()) {
                        retained.retainIfAbsent(p.modes());
                        for (var mode : p.modes()) { tick(cancellation,charge,1); retained.retainIfAbsent(mode); }
                    }
                }
                result.add(retained == null ? new ProbabilisticProfile(p.profileIndex(),p.chainageMeters(),
                        p.anchor(),p.normalUnit(),p.minimumOffsetMeters(),p.maximumOffsetMeters(),p.sourcePitchMeters(),
                        p.nativePitchKnown(),p.noiseFloor(),p.samples(),p.modes(),frozen,p.exactAnchorOffsetMeters(),
                        p.orientationSupport()) : ProbabilisticProfileFactory.ownedCensoredReliabilityProfile(p,frozen,retained));
            }
            List<ProbabilisticProfile> copied = allocated(retained,ProbabilisticInference.listBytes(result.size()),
                    () -> List.copyOf(result));
            if (scratch != null) scratch.close();
            if (retained != null) retained.transferTo(owner);
            return copied;
        } finally {
            if (scratch != null) scratch.close();
            if (retained != null) retained.close();
        }
    }

    /** Returns one confidence per original censored occurrence, without changing input evidence. */
    static double[][] confidence(List<ProbabilisticProfile> profiles, BandEvidence evidence,
            CancellationProbe cancellation, LongConsumer charge, AttemptMemoryLedger.Owner owner) {
        if (profiles == null || profiles.isEmpty() || evidence == null || cancellation == null || charge == null)
            throw new IllegalArgumentException("Censored corroboration inputs are incomplete");
        var retained = owner == null ? null : owner.child(STAGE);
        var scratch = retained == null ? null : retained.child("adjacent-band-links");
        try {
            tick(cancellation, charge, profiles.size());
            int count = profiles.size();
            double[][] result = allocated(retained, AttemptMemoryLedger.referenceArrayBytes(count),
                    () -> new double[count][]);
            int[][] forward = allocated(scratch, AttemptMemoryLedger.referenceArrayBytes(count),
                    () -> new int[count][]);
            int[][] backward = allocated(scratch, AttemptMemoryLedger.referenceArrayBytes(count),
                    () -> new int[count][]);
            for (int i = 0; i < count; i++) {
                tick(cancellation, charge, 1);
                var profile = profiles.get(i);
                if (profile == null || i > 0 && !(profile.chainageMeters() > profiles.get(i-1).chainageMeters()))
                    throw new IllegalArgumentException("Censored profile chainage is not strictly ordered");
                int size = profile.censoredModes().size();
                tick(cancellation, charge, 3L * size);
                result[i] = allocated(retained, AttemptMemoryLedger.arrayBytes(size, Double.BYTES),
                        () -> new double[size]);
                forward[i] = allocated(scratch, AttemptMemoryLedger.arrayBytes(size, Integer.BYTES),
                        () -> new int[size]);
                backward[i] = allocated(scratch, AttemptMemoryLedger.arrayBytes(size, Integer.BYTES),
                        () -> new int[size]);
                Arrays.fill(forward[i], -1);
                Arrays.fill(backward[i], -1);
            }
            for (int i = 0; i + 1 < count; i++) {
                tick(cancellation, charge, 1);
                var left = profiles.get(i);
                var right = profiles.get(i+1);
                if (right.profileIndex() != left.profileIndex() + 1
                        || right.chainageMeters() - left.chainageMeters()
                            > LongitudinalModeReliability.WINDOW_METERS) continue;
                var edgeOwner = scratch == null ? null : scratch.child("pair-census");
                try {
                    int lc = left.censoredModes().size(), rc = right.censoredModes().size();
                    tick(cancellation, charge, lc + (long)rc);
                    int[] leftDegree = allocated(edgeOwner, AttemptMemoryLedger.arrayBytes(lc, Integer.BYTES),
                            () -> new int[lc]);
                    int[] rightDegree = allocated(edgeOwner, AttemptMemoryLedger.arrayBytes(rc, Integer.BYTES),
                            () -> new int[rc]);
                    // Every candidate is examined before a successful pair can be called unique.
                    for (int a = 0; a < lc; a++) for (int b = 0; b < rc; b++) {
                        tick(cancellation, charge, 1);
                        var first = left.censoredModes().get(a);
                        var second = right.censoredModes().get(b);
                        if (!eligible(first, left) || !eligible(second, right) || first.side() != second.side()) continue;
                        if (evidence.observed(left, first, right, second, cancellation)) {
                            leftDegree[a]++;
                            rightDegree[b]++;
                            forward[i][a] = b;
                            backward[i+1][b] = a;
                        }
                    }
                    for (int a = 0; a < lc; a++) {
                        tick(cancellation, charge, 1);
                        int b = forward[i][a];
                        if (b < 0 || leftDegree[a] != 1 || rightDegree[b] != 1) forward[i][a] = -1;
                    }
                    for (int b = 0; b < rc; b++) {
                        tick(cancellation, charge, 1);
                        int a = backward[i+1][b];
                        if (a < 0 || rightDegree[b] != 1 || leftDegree[a] != 1) backward[i+1][b] = -1;
                    }
                } finally { if (edgeOwner != null) edgeOwner.close(); }
            }
            for (int i = 0; i < count; i++) for (int band = 0; band < result[i].length; band++) {
                tick(cancellation, charge, 1);
                if (!eligible(profiles.get(i).censoredModes().get(band), profiles.get(i))) continue;
                double left = integrated(profiles, forward, backward, i, band, -1, cancellation, charge);
                double right = integrated(profiles, forward, backward, i, band, 1, cancellation, charge);
                result[i][band] = StrictMath.sqrt(left * right);
            }
            if (scratch != null) scratch.close();
            if (retained != null) retained.transferTo(owner);
            return result;
        } finally {
            if (scratch != null) scratch.close();
            if (retained != null) retained.close();
        }
    }

    private static boolean eligible(ProbabilisticProfile.CensoredMode band, ProbabilisticProfile profile) {
        return band.gradientSupported() && band.existenceConfidence() > 0
                && band.inwardExtentMeasured() && Double.isFinite(band.shoulderThreshold())
                && band.observedBoundaryMeters() >= profile.minimumOffsetMeters()
                && band.observedBoundaryMeters() <= profile.maximumOffsetMeters()
                && band.inwardSupportOffsetMeters() >= profile.minimumOffsetMeters()
                && band.inwardSupportOffsetMeters() <= profile.maximumOffsetMeters();
    }

    private static double integrated(List<ProbabilisticProfile> profiles, int[][] forward, int[][] backward,
            int origin, int band, int direction, CancellationProbe cancellation, LongConsumer charge) {
        double window = LongitudinalModeReliability.WINDOW_METERS;
        double originChainage = profiles.get(origin).chainageMeters();
        int current = origin;
        double span = 0;
        double integral = 0;
        while (current + direction >= 0 && current + direction < profiles.size()) {
            tick(cancellation, charge, 1);
            int nextBand = direction > 0 ? forward[current][band] : backward[current][band];
            if (nextBand < 0) break;
            int next = current + direction;
            double start = Math.abs(profiles.get(current).chainageMeters() - originChainage);
            double end = Math.min(window, Math.abs(profiles.get(next).chainageMeters() - originChainage));
            if (start >= window) break;
            integral += (end-start) * (1 - .5 * (start+end) / window);
            span = end;
            if (end >= window) break;
            current = next;
            band = nextBand;
        }
        if (span + EPSILON < LongitudinalModeReliability.MINIMUM_DIRECT_SPAN_METERS) return 0;
        double boundary = direction > 0 ? profiles.get(profiles.size()-1).chainageMeters()
                : profiles.get(0).chainageMeters();
        double available = Math.min(window, Math.abs(boundary-originChainage));
        double mass = available * (1 - .5 * available / window);
        return mass <= EPSILON ? 0 : Math.min(1, integral/mass);
    }

    private static void tick(CancellationProbe cancellation, LongConsumer charge, long units) {
        cancellation.checkpoint();
        charge.accept(units);
    }
    private static <T> T allocated(AttemptMemoryLedger.Owner owner, long bytes,
            java.util.function.Supplier<T> supplier) {
        return owner == null ? supplier.get() : ProbabilisticInference.allocated(owner, bytes, STAGE, supplier);
    }
}
