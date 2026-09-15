package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Immutable reference-chainage interpolation matrix {@code q=B*x} for refitting. */
public final class FrozenRefitMesh {
    /** One sparse row of {@code B}; endpoints have the same left and right occurrence. */
    public record Row(int leftControl, int rightControl, double rightWeight,
            double referenceChainageMeters) { }

    private final int controlCount;
    private final double referenceLengthMeters;
    private final List<Row> rows;

    private FrozenRefitMesh(int controlCount, double referenceLengthMeters, List<Row> rows) {
        this.controlCount = controlCount;
        this.referenceLengthMeters = referenceLengthMeters;
        this.rows = List.copyOf(rows);
    }

    /** Builds a bounded mesh without additional protected occurrences. */
    public static FrozenRefitMesh build(List<MetricPoint> controls, double maximumSpacingMeters,
            CancellationProbe cancellation) {
        return build(controls, Set.of(), maximumSpacingMeters, cancellation);
    }

    /**
     * Builds one global physical-chainage grid. True bends and protected occurrences are
     * mandatory rows; ordinary collinear insertions do not change the grid phase.
     */
    public static FrozenRefitMesh build(List<MetricPoint> controls, Set<Integer> protectedControls,
            double maximumSpacingMeters, CancellationProbe cancellation) {
        if (controls == null || controls.size() < 2 || controls.size() > 32_768
                || protectedControls == null
                || !Double.isFinite(maximumSpacingMeters) || maximumSpacingMeters <= 0.0
                || cancellation == null) {
            throw new IllegalArgumentException("Refit mesh inputs are incomplete or outside bounds");
        }
        cancellation.checkpoint();
        double[] chainage = new double[controls.size()];
        double length = 0.0;
        for (int segment = 0; segment < controls.size() - 1; segment++) {
            cancellation.checkpoint();
            double segmentLength = controls.get(segment).distanceTo(controls.get(segment + 1));
            if (!Double.isFinite(segmentLength) || !(segmentLength > 0.0)) {
                throw new IllegalArgumentException("Refit reference geometry has a degenerate segment");
            }
            length += segmentLength;
            chainage[segment + 1] = length;
            if (!Double.isFinite(length)) {
                throw new IllegalArgumentException("Refit mesh exceeds the deterministic resource bound");
            }
        }
        long divisionsLong = (long) Math.ceil(length / maximumSpacingMeters);
        long maximumCandidateCount = divisionsLong + 1L + controls.size();
        if (divisionsLong < 1L || maximumCandidateCount
                > GeometricCurvatureOperator.MAXIMUM_MESH_POINTS) {
            throw new IllegalArgumentException("Refit mesh exceeds the deterministic resource bound");
        }
        int divisions = (int) divisionsLong;
        ArrayList<Candidate> candidates = new ArrayList<>((int) maximumCandidateCount);
        for (int division = 0; division <= divisions; division++) {
            candidates.add(new Candidate(length * division / divisions, -1));
        }
        for (int control = 0; control < controls.size(); control++) {
            if (control == 0 || control == controls.size() - 1 || protectedControls.contains(control)
                    || isTrueBend(controls, control)) {
                candidates.add(new Candidate(chainage[control], control));
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::chainageMeters)
                .thenComparingInt(candidate -> candidate.exactControl() < 0 ? 1 : 0));
        ArrayList<Candidate> samples = new ArrayList<>(candidates.size());
        double tolerance = 1.0e-12 * Math.max(1.0, length);
        for (Candidate candidate : candidates) {
            if (!samples.isEmpty() && Math.abs(candidate.chainageMeters()
                    - samples.get(samples.size() - 1).chainageMeters()) <= tolerance) {
                if (candidate.exactControl() >= 0) samples.set(samples.size() - 1, candidate);
            } else {
                samples.add(candidate);
            }
        }
        if (samples.size() > GeometricCurvatureOperator.MAXIMUM_MESH_POINTS) {
            throw new IllegalArgumentException("Refit mesh exceeds the deterministic resource bound");
        }
        ArrayList<Row> rows = new ArrayList<>(samples.size());
        int segment = 0;
        for (Candidate sample : samples) {
            cancellation.checkpoint();
            if (sample.exactControl() >= 0) {
                int control = sample.exactControl();
                rows.add(new Row(control, control, 0.0, sample.chainageMeters()));
                continue;
            }
            while (segment + 1 < chainage.length - 1
                    && sample.chainageMeters() > chainage[segment + 1] + tolerance) segment++;
            double segmentLength = chainage[segment + 1] - chainage[segment];
            double fraction = (sample.chainageMeters() - chainage[segment]) / segmentLength;
            rows.add(new Row(segment, segment + 1, fraction, sample.chainageMeters()));
        }
        return new FrozenRefitMesh(controls.size(), length, rows);
    }

    private static boolean isTrueBend(List<MetricPoint> controls, int index) {
        if (index <= 0 || index >= controls.size() - 1) return false;
        MetricPoint previous = controls.get(index - 1);
        MetricPoint current = controls.get(index);
        MetricPoint next = controls.get(index + 1);
        double ax = current.xMeters() - previous.xMeters();
        double ay = current.yMeters() - previous.yMeters();
        double bx = next.xMeters() - current.xMeters();
        double by = next.yMeters() - current.yMeters();
        double scale = Math.hypot(ax, ay) * Math.hypot(bx, by);
        return Math.abs(ax * by - ay * bx) > 1.0e-12 * scale
                || ax * bx + ay * by <= 0.0;
    }

    private record Candidate(double chainageMeters, int exactControl) { }

    /** Evaluates {@code q=B*x} for current control positions. */
    public List<MetricPoint> interpolate(List<MetricPoint> controls) {
        if (controls == null || controls.size() != controlCount) {
            throw new IllegalArgumentException("Refit control occurrence count changed");
        }
        ArrayList<MetricPoint> result = new ArrayList<>(rows.size());
        for (Row row : rows) {
            MetricPoint left = controls.get(row.leftControl());
            MetricPoint right = controls.get(row.rightControl());
            double weight = row.rightWeight();
            result.add(new MetricPoint((1.0 - weight) * left.xMeters() + weight * right.xMeters(),
                    (1.0 - weight) * left.yMeters() + weight * right.yMeters()));
        }
        return List.copyOf(result);
    }

    /** Scatters a mesh-coordinate gradient through {@code B^T}. */
    public List<MetricPoint> scatter(List<? extends Object> meshGradient) {
        if (meshGradient == null || meshGradient.size() != rows.size()) {
            throw new IllegalArgumentException("Refit mesh gradient count changed");
        }
        double[] x = new double[controlCount];
        double[] y = new double[controlCount];
        for (int index = 0; index < rows.size(); index++) {
            Object value = meshGradient.get(index);
            double gx;
            double gy;
            if (value instanceof MetricPoint point) {
                gx = point.xMeters();
                gy = point.yMeters();
            } else if (value instanceof GeometricCurvatureOperator.MetricGradient gradient) {
                gx = gradient.xObjectivePerMeter();
                gy = gradient.yObjectivePerMeter();
            } else {
                throw new IllegalArgumentException("Unsupported mesh-gradient value");
            }
            Row row = rows.get(index);
            double rightWeight = row.rightWeight();
            x[row.leftControl()] += (1.0 - rightWeight) * gx;
            y[row.leftControl()] += (1.0 - rightWeight) * gy;
            x[row.rightControl()] += rightWeight * gx;
            y[row.rightControl()] += rightWeight * gy;
        }
        ArrayList<MetricPoint> result = new ArrayList<>(controlCount);
        for (int index = 0; index < controlCount; index++) {
            result.add(new MetricPoint(x[index], y[index]));
        }
        return List.copyOf(result);
    }

    public int controlCount() { return controlCount; }
    public int meshPointCount() { return rows.size(); }
    public double referenceLengthMeters() { return referenceLengthMeters; }
    public List<Row> rows() { return rows; }
}
