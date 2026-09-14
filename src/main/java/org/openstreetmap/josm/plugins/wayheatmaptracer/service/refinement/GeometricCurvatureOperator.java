package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;

/**
 * Pure geometric-curvature energy on one supplied fine metric mesh.
 *
 * <p>For interior mesh point {@code i}, this operator uses adjacent unit tangents
 * {@code t}, segment lengths {@code l}, curvature
 * {@code kappa=2(t[i]-t[i-1])/(l[i]+l[i-1])}, and current-length weight
 * {@code w=(l[i]+l[i-1])/(2*referenceLength)}. Each target is an explicit immutable
 * inverse-metre vector; this class does not infer image support, build a mesh, or scatter
 * through an interpolation matrix.</p>
 */
public final class GeometricCurvatureOperator {
    /** Hard linear-work and allocation bound for a single evaluation. */
    public static final int MAXIMUM_MESH_POINTS = 65_536;

    /** A Cartesian vector whose two components are explicitly measured in inverse metres. */
    public record InverseMetersVector(double xPerMeter, double yPerMeter) {
        /** Rejects non-finite inverse-metre components. */
        public InverseMetersVector {
            if (!Double.isFinite(xPerMeter) || !Double.isFinite(yPerMeter)) {
                throw new IllegalArgumentException("Curvature target components must be finite inverse metres");
            }
        }
    }

    /** An objective derivative with respect to one metric-coordinate occurrence. */
    public record MetricGradient(double xObjectivePerMeter, double yObjectivePerMeter) {
        /** Rejects a non-finite analytic derivative. */
        public MetricGradient {
            if (!Double.isFinite(xObjectivePerMeter) || !Double.isFinite(yObjectivePerMeter)) {
                throw new IllegalArgumentException("Metric-coordinate gradient must be finite");
            }
        }
    }

    /** Immutable objective and analytic gradient for all supplied mesh points. */
    public record Evaluation(double objective, List<MetricGradient> gradient) {
        /** Validates the finite nonnegative objective and copies the result container. */
        public Evaluation {
            if (!Double.isFinite(objective) || objective < 0.0 || gradient == null) {
                throw new IllegalArgumentException("Curvature evaluation must be finite and complete");
            }
            try {
                gradient = List.copyOf(gradient);
            } catch (NullPointerException exception) {
                throw new IllegalArgumentException("Curvature gradient entries are required", exception);
            }
        }
    }

    private final double pitchMeters;
    private final double referenceLengthMeters;
    private final double lambda;
    private final List<InverseMetersVector> targets;

    /**
     * Freezes physical scales, the nonnegative multiplier, and one target per interior point.
     *
     * @param pitchMeters positive physical pitch {@code p} used to normalize curvature residuals
     * @param referenceLengthMeters positive frozen reference length {@code L0}
     * @param lambda finite nonnegative curvature multiplier
     * @param targets explicit inverse-metre target vector for each future interior mesh point
     */
    public GeometricCurvatureOperator(double pitchMeters, double referenceLengthMeters,
            double lambda, List<InverseMetersVector> targets) {
        if (!positive(pitchMeters) || !positive(referenceLengthMeters) || !nonnegative(lambda)
                || targets == null) {
            throw new IllegalArgumentException("Curvature parameters must be finite, physical, and complete");
        }
        int targetCount = targets.size();
        if (targetCount == 0 || targetCount > MAXIMUM_MESH_POINTS - 2) {
            throw new IllegalArgumentException("Curvature target count is outside the deterministic bound");
        }
        List<InverseMetersVector> copiedTargets;
        try {
            copiedTargets = List.copyOf(targets);
        } catch (NullPointerException exception) {
            throw new IllegalArgumentException("Curvature targets are required", exception);
        }
        if (copiedTargets.isEmpty() || copiedTargets.size() > MAXIMUM_MESH_POINTS - 2) {
            throw new IllegalArgumentException("Curvature target count is outside the deterministic bound");
        }
        this.pitchMeters = pitchMeters;
        this.referenceLengthMeters = referenceLengthMeters;
        this.lambda = lambda;
        this.targets = copiedTargets;
    }

    /**
     * Evaluates the exact Huber curvature objective and its full metric-coordinate gradient.
     *
     * <p>The derivative includes both unit-tangent curvature and current segment-length weights.
     * Zero-valued targets remain explicit mathematical targets; they carry no image-evidence
     * interpretation.</p>
     *
     * @param meshPoints finite metric points matching the frozen interior target count
     * @return finite objective and one gradient vector per supplied point
     * @throws IllegalArgumentException for count, size, degeneracy, or non-finite arithmetic
     */
    public Evaluation evaluate(List<MetricPoint> meshPoints) {
        List<MetricPoint> points = copyAndValidateCount(meshPoints);
        int pointCount = points.size();
        int segmentCount = pointCount - 1;
        double[] lengths = new double[segmentCount];
        double[] tangentX = new double[segmentCount];
        double[] tangentY = new double[segmentCount];

        for (int segment = 0; segment < segmentCount; segment++) {
            MetricPoint start = points.get(segment);
            MetricPoint end = points.get(segment + 1);
            double dx = end.xMeters() - start.xMeters();
            double dy = end.yMeters() - start.yMeters();
            double length = Math.hypot(dx, dy);
            if (!Double.isFinite(dx) || !Double.isFinite(dy) || !positive(length)) {
                throw new IllegalArgumentException("Curvature mesh contains a degenerate or overflowing segment");
            }
            lengths[segment] = length;
            tangentX[segment] = dx / length;
            tangentY[segment] = dy / length;
            requireFinite(tangentX[segment], tangentY[segment]);
        }

        for (int interior = 1; interior < pointCount - 1; interior++) {
            requirePositiveFinite(lengths[interior - 1] + lengths[interior]);
        }
        if (lambda == 0.0) {
            return new Evaluation(0.0,
                    Collections.nCopies(pointCount, new MetricGradient(0.0, 0.0)));
        }

        double[] gradientX = new double[pointCount];
        double[] gradientY = new double[pointCount];
        double objective = 0.0;
        for (int interior = 1; interior < pointCount - 1; interior++) {
            int left = interior - 1;
            int right = interior;
            double lengthSum = lengths[left] + lengths[right];
            double curvatureX = 2.0 * (tangentX[right] - tangentX[left]) / lengthSum;
            double curvatureY = 2.0 * (tangentY[right] - tangentY[left]) / lengthSum;
            InverseMetersVector target = targets.get(interior - 1);
            double residualX = curvatureX - target.xPerMeter();
            double residualY = curvatureY - target.yPerMeter();
            double residualNorm = Math.hypot(residualX, residualY);
            double normalizedResidual = pitchMeters * residualNorm;
            double weight = (0.5 * lengthSum) / referenceLengthMeters;
            requireFinite(curvatureX, curvatureY, residualX, residualY, residualNorm,
                    normalizedResidual, weight);

            double huber = normalizedResidual <= 1.0
                    ? 0.5 * normalizedResidual * normalizedResidual
                    : normalizedResidual - 0.5;
            double contribution = lambda * weight * huber;
            requireFinite(huber, contribution);
            objective += contribution;
            requireFinite(objective);

            double curvatureGradientX = 0.0;
            double curvatureGradientY = 0.0;
            if (residualNorm > 0.0) {
                double huberDerivative = normalizedResidual <= 1.0 ? normalizedResidual : 1.0;
                double radialMagnitude = lambda * weight * pitchMeters * huberDerivative;
                curvatureGradientX = radialMagnitude * (residualX / residualNorm);
                curvatureGradientY = radialMagnitude * (residualY / residualNorm);
                requireFinite(huberDerivative, radialMagnitude,
                        curvatureGradientX, curvatureGradientY);
            }

            double curvatureDotGradient = curvatureX * curvatureGradientX
                    + curvatureY * curvatureGradientY;
            double weightDerivative = (0.5 * lambda * huber) / referenceLengthMeters;
            double lengthSumCoefficient = weightDerivative - curvatureDotGradient / lengthSum;
            requireFinite(curvatureDotGradient, weightDerivative, lengthSumCoefficient);

            double leftProjectionDot = tangentX[left] * curvatureGradientX
                    + tangentY[left] * curvatureGradientY;
            double leftProjectedX = curvatureGradientX - tangentX[left] * leftProjectionDot;
            double leftProjectedY = curvatureGradientY - tangentY[left] * leftProjectionDot;
            double leftEdgeX = -scaledProjectedDerivative(leftProjectedX, lengthSum, lengths[left])
                    + lengthSumCoefficient * tangentX[left];
            double leftEdgeY = -scaledProjectedDerivative(leftProjectedY, lengthSum, lengths[left])
                    + lengthSumCoefficient * tangentY[left];

            double rightProjectionDot = tangentX[right] * curvatureGradientX
                    + tangentY[right] * curvatureGradientY;
            double rightProjectedX = curvatureGradientX - tangentX[right] * rightProjectionDot;
            double rightProjectedY = curvatureGradientY - tangentY[right] * rightProjectionDot;
            double rightEdgeX = scaledProjectedDerivative(rightProjectedX, lengthSum, lengths[right])
                    + lengthSumCoefficient * tangentX[right];
            double rightEdgeY = scaledProjectedDerivative(rightProjectedY, lengthSum, lengths[right])
                    + lengthSumCoefficient * tangentY[right];
            requireFinite(leftProjectionDot, leftProjectedX, leftProjectedY,
                    leftEdgeX, leftEdgeY, rightProjectionDot, rightProjectedX, rightProjectedY,
                    rightEdgeX, rightEdgeY);

            add(gradientX, gradientY, left, -leftEdgeX, -leftEdgeY);
            add(gradientX, gradientY, interior, leftEdgeX - rightEdgeX, leftEdgeY - rightEdgeY);
            add(gradientX, gradientY, right + 1, rightEdgeX, rightEdgeY);
        }

        ArrayList<MetricGradient> gradient = new ArrayList<>(pointCount);
        for (int index = 0; index < pointCount; index++) {
            gradient.add(new MetricGradient(gradientX[index], gradientY[index]));
        }
        return new Evaluation(objective, gradient);
    }

    private List<MetricPoint> copyAndValidateCount(List<MetricPoint> meshPoints) {
        if (meshPoints == null || meshPoints.size() < 3
                || meshPoints.size() > MAXIMUM_MESH_POINTS
                || meshPoints.size() != targets.size() + 2) {
            throw new IllegalArgumentException("Curvature mesh and target counts are inconsistent");
        }
        try {
            return List.copyOf(meshPoints);
        } catch (NullPointerException exception) {
            throw new IllegalArgumentException("Curvature mesh points are required", exception);
        }
    }

    private static void add(double[] gradientX, double[] gradientY, int index, double x, double y) {
        gradientX[index] += x;
        gradientY[index] += y;
        requireFinite(gradientX[index], gradientY[index]);
    }

    private static double scaledProjectedDerivative(double projectedGradient,
            double lengthSum, double segmentLength) {
        // Cancel the gradient's length scale before applying the second inverse length.
        // Materializing 2/(lengthSum*segmentLength) loses precision for large meshes,
        // even when the complete derivative is an ordinary representable double.
        double normalizedGradient = projectedGradient / segmentLength;
        double inverseLength = 2.0 / lengthSum;
        double derivative = normalizedGradient * inverseLength;
        requireFinite(normalizedGradient, inverseLength, derivative);
        if (inverseLength <= 0.0 || (projectedGradient != 0.0
                && (normalizedGradient == 0.0 || derivative == 0.0))) {
            throw new IllegalArgumentException("Curvature derivative computation underflowed");
        }
        return derivative;
    }


    private static void requirePositiveFinite(double value) {
        if (!positive(value)) {
            throw new IllegalArgumentException("Curvature arithmetic produced a non-positive length scale");
        }
    }

    private static void requireFinite(double... values) {
        for (double value : values) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Curvature arithmetic produced a non-finite result");
            }
        }
    }

    private static boolean positive(double value) {
        return Double.isFinite(value) && value > 0.0;
    }

    private static boolean nonnegative(double value) {
        return Double.isFinite(value) && value >= 0.0;
    }
}
