package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.List;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters;

/** Immutable O(states + adjacent pairs) preparation for the explicitly selected cached backend. */
final class PreparedBKernel implements BTransitionAdmission {
    private final double[][] x, y, unary, logMeasure, heading, pair;
    private final byte[][] allowed;
    private final double[] turnSpan;
    private final int[] widths;
    private final EvidenceModelParameters parameters;

    private PreparedBKernel(int count, EvidenceModelParameters parameters,
            AttemptMemoryLedger.Owner owner) {
        this.parameters = parameters;
        x = rows(count, owner); y = rows(count, owner); unary = rows(count, owner);
        logMeasure = rows(count, owner); heading = rows(count, owner); pair = rows(count, owner);
        allowed = ProbabilisticInference.allocated(owner, AttemptMemoryLedger.referenceArrayBytes(count),
                "cached pair tables", () -> new byte[count][]);
        turnSpan = doubles(count, owner);
        widths = ProbabilisticInference.allocated(owner, AttemptMemoryLedger.arrayBytes(count, Integer.BYTES),
                "cached pair tables", () -> new int[count]);
    }

    static PreparedBKernel prepare(List<InferenceProfile> profiles, EvidenceModelParameters parameters,
            MetricRegion region, CancellationProbe cancellation, AttemptMemoryLedger.Owner owner) {
        cancellation.checkpoint();
        long beforeBytes = owner.currentBytes();
        var kernel = ProbabilisticInference.allocated(owner, AttemptMemoryLedger.objectBytes(80),
                "cached pair tables", () -> new PreparedBKernel(profiles.size(), parameters, owner));
        long slots = 0, legal = 0;
        try (var scratch = owner.child("cache-preparation-points")) {
            MetricPoint[][] points = ProbabilisticInference.allocated(scratch,
                    AttemptMemoryLedger.referenceArrayBytes(profiles.size()), "cache coordinates",
                    () -> new MetricPoint[profiles.size()][]);
            for (int row = 0; row < profiles.size(); row++) {
                cancellation.checkpoint();
                var profile = profiles.get(row); int n = profile.cells().size(); kernel.widths[row] = n;
                kernel.x[row] = doubles(n, owner); kernel.y[row] = doubles(n, owner);
                kernel.unary[row] = doubles(n, owner); kernel.logMeasure[row] = doubles(n, owner);
                points[row] = ProbabilisticInference.allocated(scratch,
                        AttemptMemoryLedger.referenceArrayBytes(n), "cache coordinates", () -> new MetricPoint[n]);
                for (int state = 0; state < n; state++) {
                    cancellation.checkpoint(); final int s = state;
                    MetricPoint point = ProbabilisticInference.allocated(scratch,
                            AttemptMemoryLedger.objectBytes(16), "cache coordinates", () -> profile.point(s));
                    points[row][state] = point; kernel.x[row][state] = point.xMeters();
                    kernel.y[row][state] = point.yMeters();
                    kernel.unary[row][state] = ProbabilisticInference.profileEnergy(profiles, row, state, parameters);
                    kernel.logMeasure[row][state] = ProbabilisticInference.logMeasure(profile.cells().get(state));
                }
                if (row >= 2) kernel.turnSpan[row] = Math.max(1e-6,
                        0.5 * (profile.chainageMeters() - profiles.get(row - 2).chainageMeters()));
            }
            for (int row = 0; row + 1 < profiles.size(); row++) {
                int n = Math.multiplyExact(kernel.widths[row], kernel.widths[row + 1]);
                slots = Math.addExact(slots, n);
                kernel.heading[row] = doubles(n, owner); kernel.pair[row] = doubles(n, owner);
                kernel.allowed[row] = ProbabilisticInference.allocated(owner,
                        AttemptMemoryLedger.arrayBytes(n, Byte.BYTES), "cached pair tables", () -> new byte[n]);
                for (int first = 0; first < kernel.widths[row]; first++) {
                    for (int second = 0; second < kernel.widths[row + 1]; second++) {
                        cancellation.checkpoint(); int index = first * kernel.widths[row + 1] + second;
                        MetricPoint a = points[row][first], b = points[row + 1][second];
                        double h = StrictMath.atan2(b.yMeters() - a.yMeters(), b.xMeters() - a.xMeters());
                        kernel.heading[row][index] = h;
                        kernel.pair[row][index] = parameters.orientationWeight() == 0.0 ? 0.0
                                : ProbabilisticInference.pairEnergyAtHeading(profiles.get(row),
                                    profiles.get(row + 1), first, second, parameters, h);
                        boolean admit = region == null || region.contains(a) && region.contains(b)
                                && region.containsSegment(a, b);
                        kernel.allowed[row][index] = (byte) (admit ? 1 : 0);
                        if (admit) legal++;
                    }
                }
            }
        }
        ModernDiagnosticCounters.add("b.cache.pairSlots", slots);
        ModernDiagnosticCounters.add("b.cache.legalPairSlots", legal);
        ModernDiagnosticCounters.add("b.cache.retainedBytes", owner.currentBytes() - beforeBytes);
        return kernel;
    }

    boolean allowed(int row, int first, int second) { return allowed[row][first * widths[row + 1] + second] != 0; }
    public boolean allowed(int row, int first, int second, List<InferenceProfile> profiles, MetricRegion region) {
        return allowed(row, first, second);
    }
    double unary(int row, int state) { return unary[row][state]; }
    double measure(int row, int state) { return logMeasure[row][state]; }
    double pair(int row, int first, int second) { return pair[row][first * widths[row + 1] + second]; }
    double turn(int row, int before, int prior, int state) {
        if (parameters.turnWeight() == 0.0) return 0.0;
        double first = heading[row - 2][before * widths[row - 1] + prior];
        double second = heading[row - 1][prior * widths[row] + state];
        double delta = second - first;
        double normalized = StrictMath.atan2(StrictMath.sin(delta), StrictMath.cos(delta))
                / parameters.turnScaleRadians();
        return parameters.turnWeight() * EvidenceModelParameters.huber(normalized) / turnSpan[row];
    }
    private static double[] doubles(int n, AttemptMemoryLedger.Owner owner) {
        return ProbabilisticInference.allocated(owner, AttemptMemoryLedger.arrayBytes(n, Double.BYTES),
                "cached pair tables", () -> new double[n]);
    }
    private static double[][] rows(int n, AttemptMemoryLedger.Owner owner) {
        return ProbabilisticInference.allocated(owner, AttemptMemoryLedger.referenceArrayBytes(n),
                "cached pair tables", () -> new double[n][]);
    }
}
